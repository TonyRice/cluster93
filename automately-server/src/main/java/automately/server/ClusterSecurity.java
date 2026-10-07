package automately.server;

import automately.cluster.ignite.IgniteCluster;
import automately.core.host.KvStore;
import automately.core.queue.QueueService;
import automately.tls.ClusterTls;
import automately.tls.NodeBundle;
import automately.tls.NodeIdentity;
import automately.tls.Revocations;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.TrustOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node certificates between core nodes (D-066, D-067): installing a join bundle, loading the node's identity, the
 * revocation list, and the TLS settings for Ignite, the event bus and the internal endpoint.
 */
final class ClusterSecurity {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterSecurity.class);
    static final String REVOKED_FILE = "revoked.json";
    static final String REVOKED_NAMESPACE = "revoked-nodes";
    static final String REVOKED_ADDRESS = "automately.cluster.revoked";
    static final Duration EXPIRY_WARNING = Duration.ofDays(90);

    private ClusterSecurity() {
    }

    /** What to do when a clustered node has no certificates. */
    static String howToJoin(ServerConfig config) {
        return "Clustered nodes need node certificates (D-066). Create them with `automately cluster init` for a new cluster, or "
                + "`automately node invite " + config.nodeId() + " --host <address> --ca ./ca.key` to add this node, and start it with "
                + "AUTOMATELY_JOIN set to the line that prints. Development only: AUTOMATELY_CLUSTER_TLS=off.";
    }

    /** Installs {@code AUTOMATELY_JOIN} if set, then loads the identity from the TLS directory. */
    static NodeIdentity identity(ServerConfig config) {
        Path dir = config.tlsDirectory();
        if (dir == null) {
            throw new IllegalStateException("No TLS directory: set AUTOMATELY_DATA_DIR or AUTOMATELY_TLS_DIR. " + howToJoin(config));
        }
        if (config.join() != null) {
            NodeBundle.Contents contents = NodeBundle.decode(config.join());
            PrivateKey own = null;
            if (contents.key() == null) {
                own = NodeIdentity.preparedKey(dir);
                if (own == null) {
                    throw new IllegalStateException("This line carries a certificate only; run `automately-server --prepare "
                            + config.nodeId() + "` on this machine first, so the key is here");
                }
            }
            NodeIdentity joining = contents.identity(own);
            if (!joining.nodeId().equals(config.nodeId())) {
                throw new IllegalStateException("This join line is for node " + joining.nodeId() + ", but this node is "
                        + config.nodeId() + " (AUTOMATELY_NODE_ID)");
            }
            joining.write(dir);
            LOG.info("Installed the node certificate for {} in {}", joining.nodeId(), dir);
        }
        if (!NodeIdentity.existsIn(dir)) {
            throw new IllegalStateException("No node certificate in " + dir + ". " + howToJoin(config));
        }
        NodeIdentity identity = NodeIdentity.load(dir);
        if (!identity.nodeId().equals(config.nodeId())) {
            throw new IllegalStateException("The certificate in " + dir + " is for node " + identity.nodeId() + ", but this node is "
                    + config.nodeId() + " (AUTOMATELY_NODE_ID)");
        }
        warnIfExpiring(identity);
        return identity;
    }

    static void warnIfExpiring(NodeIdentity identity) {
        Duration left = Duration.between(Instant.now(), identity.expiresAt());
        if (left.compareTo(EXPIRY_WARNING) < 0) {
            LOG.warn("This node's certificate expires in {} days ({}). Renew it: automately node invite {} --ca ./ca.key, then restart "
                    + "with the new AUTOMATELY_JOIN", left.toDays(), identity.expiresAt(), identity.nodeId());
        }
    }

    /** The revocations this node last saw, so it refuses revoked nodes even before it has rejoined the cluster. */
    static Revocations revocations(Path dir) {
        Revocations revocations = new Revocations();
        Path file = dir.resolve(REVOKED_FILE);
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Long> all = new HashMap<>();
                new JsonObject(Files.readString(file)).forEach(entry -> all.put(entry.getKey(), ((Number) entry.getValue()).longValue()));
                revocations.replaceAll(all);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Ignoring an unreadable {}: {}", file, e.getMessage());
            }
        }
        return revocations;
    }

    static void save(Revocations revocations, Path dir) {
        try {
            JsonObject json = new JsonObject();
            revocations.all().forEach(json::put);
            Files.writeString(dir.resolve(REVOKED_FILE), json.encodePrettily());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The clustered event bus: TLS with node certificates required on both sides. Vert.x copies event-bus options, which
     * loses wrapped key and trust managers, so the material goes in as PEM: it trusts the cluster CA but doesn't consult
     * the revocation list. A revoked node can't rejoin Ignite discovery (which does) and is stopped when revoked.
     */
    static void secure(EventBusOptions options, ClusterTls tls) {
        NodeIdentity identity = tls.identity();
        options.setSsl(true)
                .setKeyCertOptions(new io.vertx.core.net.PemKeyCertOptions()
                        .setKeyValue(io.vertx.core.buffer.Buffer.buffer(automately.tls.Pem.privateKey(identity.key())))
                        .setCertValue(io.vertx.core.buffer.Buffer.buffer(automately.tls.Pem.certificate(identity.certificate()))))
                .setTrustOptions(new io.vertx.core.net.PemTrustOptions()
                        .addCertValue(io.vertx.core.buffer.Buffer.buffer(automately.tls.Pem.certificate(identity.ca()))))
                .setClientAuth(ClientAuth.REQUIRED);
    }

    /** The internal endpoint: HTTPS that only other nodes can call (it replaces D-036's shared secret). */
    static HttpServerOptions internalServer(ClusterTls tls) {
        return new HttpServerOptions().setSsl(true)
                .setKeyCertOptions(KeyCertOptions.wrap(tls.keyManager()))
                .setTrustOptions(TrustOptions.wrap(tls.trustManager()))
                .setClientAuth(ClientAuth.REQUIRED);
    }

    /** The ingress's client for the internal endpoint: presents this node's certificate and trusts only cluster nodes. */
    static HttpClientOptions internalClient(HttpClientOptions options, ClusterTls tls) {
        // Any node the CA signed is a member, so the address isn't checked against the certificate's names.
        return options.setSsl(true).setVerifyHost(false)
                .setKeyCertOptions(KeyCertOptions.wrap(tls.keyManager()))
                .setTrustOptions(TrustOptions.wrap(tls.trustManager()));
    }

    static final Duration REFRESH = Duration.ofMinutes(1);

    /**
     * Keeps the revocations current: broadcasts as they happen, and a full reload from the cluster now and every
     * {@link #REFRESH} (in case a broadcast was missed), each saved locally. Never holds up or fails the node's start: a
     * reload that fails (partitions still moving on a fresh cluster) is retried, and the local copy covers the meantime.
     */
    static Future<Void> follow(Vertx vertx, KvStore kv, Revocations revocations, Path dir, String self, Runnable stopSelf) {
        listen(vertx, revocations, dir, self, stopSelf);
        reload(vertx, kv, revocations, dir, 1);
        vertx.setPeriodic(REFRESH.toMillis(), id -> reload(vertx, kv, revocations, dir, 1));
        return Future.succeededFuture();
    }

    private static void reload(Vertx vertx, KvStore kv, Revocations revocations, Path dir, int attempt) {
        load(kv, revocations, dir).onFailure(err -> {
            LOG.debug("Reloading revocations failed (attempt {}): {}", attempt, err.getMessage());
            vertx.setTimer(Math.min(30_000, 1000L * attempt), id -> reload(vertx, kv, revocations, dir, attempt + 1));
        });
    }

    private static void listen(Vertx vertx, Revocations revocations, Path dir, String self, Runnable stopSelf) {
        vertx.eventBus().<JsonObject>consumer(REVOKED_ADDRESS, message -> {
            String nodeId = message.body().getString("nodeId");
            revocations.revoke(nodeId, message.body().getLong("revokedAt"));
            save(revocations, dir);
            if (nodeId.equals(self)) {
                LOG.error("This node ({}) was revoked; stopping", self);
                stopSelf.run();
            }
        });
    }

    private static Future<Void> load(KvStore kv, Revocations revocations, Path dir) {
        return kv.list(QueueService.SYSTEM_TENANT, REVOKED_NAMESPACE, "", 1000, null).compose(page -> {
            List<Future<Object>> reads = new ArrayList<>();
            List<String> ids = page.getJsonArray("keys").stream().map(String::valueOf).toList();
            ids.forEach(id -> reads.add(kv.get(QueueService.SYSTEM_TENANT, REVOKED_NAMESPACE, id)));
            return Future.all(reads).map(done -> {
                for (int i = 0; i < ids.size(); i++) {
                    if (reads.get(i).result() instanceof Number at) {
                        revocations.revoke(ids.get(i), at.longValue());
                    }
                }
                save(revocations, dir);
                return null;
            });
        });
    }

    /**
     * Revokes a node: recorded durably, broadcast to every node (their TLS refuses it from then on, and the node itself
     * stops if it's well behaved), and forced out of the cluster if it's in it now.
     */
    static Future<JsonObject> revoke(Vertx vertx, KvStore kv, IgniteCluster cluster, String nodeId) {
        long now = System.currentTimeMillis();
        return kv.set(QueueService.SYSTEM_TENANT, REVOKED_NAMESPACE, nodeId, now, null).map(v -> {
            vertx.eventBus().publish(REVOKED_ADDRESS, new JsonObject().put("nodeId", nodeId).put("revokedAt", now));
            boolean stopped = cluster != null && cluster.failNode(nodeId);
            LOG.warn("Node {} was revoked{}", nodeId, stopped ? " and stopped" : "");
            return new JsonObject().put("nodeId", nodeId).put("revokedAt", now).put("stopped", stopped);
        });
    }
}
