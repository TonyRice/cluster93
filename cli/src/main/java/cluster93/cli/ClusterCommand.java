package cluster93.cli;

import cluster93.tls.ClusterInit;
import cluster93.tls.NodeBundle;
import cluster93.tls.Pem;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The operator's front door (D-188): the cluster's CA and inventory, live membership, and the admin commands that
 * belong to running the cluster rather than using it as a tenant.
 */
@Command(name = "cluster", description = "Run and manage a Cluster93 cluster: certificates, membership, nodes, tenants, users and audit.",
        subcommands = {ClusterCommand.Init.class, ClusterCommand.Status.class, ClusterCommand.Nodes.class,
                ClusterCommand.Doctor.class, ClusterCommand.Invite.class, ClusterCommand.Revoke.class,
                DevCommand.class, TenantsCommand.class, UsersCommand.class, AuditCommand.class})
final class ClusterCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    /** The inventory this command uses: --file, then the active profile's bundle, then ./cluster-tls. */
    static Path inventory(Base base, Path file) {
        if (file != null) {
            return ClusterFile.resolve(file);
        }
        JsonNode cluster = ProfileStore.meta(base.io(), base.profile()).get("cluster");
        if (cluster != null && cluster.isTextual() && !cluster.asText().isBlank()) {
            return ClusterFile.resolve(Path.of(cluster.asText()));
        }
        return ClusterFile.resolve(Path.of("cluster-tls"));
    }

    @Command(name = "init", description = "Create the cluster's CA, its inventory and a certificate for each first node. "
            + "ca.key stays with you: the cluster never stores it.")
    static final class Init extends Base {
        @Option(names = "--nodes", required = true, split = ",", paramLabel = "ID,ID", description = "The first nodes' ids, e.g. node1,node2,node3.")
        List<String> nodes;

        @Option(names = "--host", paramLabel = "ID=ADDRESS", description = "An extra address for a node's certificate, e.g. node1=10.0.0.11 (repeatable).")
        List<String> hosts = new ArrayList<>();

        @Option(names = "--name", defaultValue = "c93", description = "A name for the CA and the inventory.")
        String name;

        @Option(names = "--api", paramLabel = "URL", description = "The cluster API URL to record in the inventory (optional).")
        String api;

        @Option(names = "--out", defaultValue = "./cluster-tls", paramLabel = "DIR")
        Path out;

        @Override
        int execute() {
            Map<String, List<String>> extra = new HashMap<>();
            for (String pair : hosts) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) {
                    throw new CliException("--host takes ID=ADDRESS, e.g. node1=10.0.0.11", 2);
                }
                extra.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
            }
            ClusterInit.Result result;
            try {
                result = ClusterInit.init(name, nodes, extra, out);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new CliException(e.getMessage(), 1);
            }

            Path dir = out.toAbsolutePath().normalize();
            ClusterFile cluster = ClusterFile.create(dir.resolve(ClusterFile.FILE), name, api);
            for (String id : nodes) {
                String line = result.joinLines().get(id);
                try {
                    NodeBundle.Contents contents = NodeBundle.decode(line);
                    Path certificate = dir.resolve(id + ".crt");
                    Files.writeString(certificate, Pem.certificate(contents.certificate()));
                    List<String> addresses = new ArrayList<>();
                    addresses.add(id);
                    addresses.addAll(extra.getOrDefault(id, List.of()));
                    cluster.putNode(id, addresses, certificate, contents.certificate().getNotAfter().toInstant(),
                            dir.resolve(id + ".join"));
                } catch (IOException | IllegalArgumentException e) {
                    throw new CliException("Can't record " + id + " in the inventory: " + e.getMessage(), 1);
                }
            }
            cluster.save();
            com.fasterxml.jackson.databind.node.ObjectNode meta = ProfileStore.meta(io(), profile());
            ProfileStore.writeMeta(io(), profile(), meta.put("cluster", dir.toString()));

            if (json()) {
                Output.json(out(), Json.object().put("ca", result.ca().fingerprint()).put("out", dir.toString())
                        .put("inventory", cluster.file().toString())
                        .put("nodes", Json.strings(nodes)));
                return 0;
            }
            out().println("Cluster CA " + result.ca().fingerprint());
            out().println();
            out().println("  " + dir.resolve("ca.key") + "   keep it safe and back it up: it's how nodes are added. The cluster never stores it.");
            out().println("  " + dir.resolve("ca.crt"));
            out().println("  " + cluster.file() + "   the inventory cluster status and doctor read");
            for (String id : nodes) {
                out().println("  " + dir.resolve(id + ".join") + "   start " + id + " with C93_JOIN=$(cat " + id + ".join)");
            }
            return 0;
        }
    }

    @Command(name = "status", description = "Show the inventory, the live nodes, certificate expiry and any drift.")
    static final class Status extends Base {

        @Option(names = "--file", paramLabel = "FILE", description = "The cluster inventory; defaults to the active profile's bundle.")
        Path file;

        @Option(names = "--check", description = "Exit non-zero when a node is missing, unlisted, expired or the API is unreachable.")
        boolean check;

        @Override
        int execute() {
            return ClusterStatus.status(this, inventory(this, file), check);
        }
    }

    @Command(name = "nodes", description = "Show the live nodes the cluster API reports.")
    static final class Nodes extends Base {

        @Override
        int execute() {
            return ClusterStatus.nodes(this);
        }
    }

    @Command(name = "doctor", description = "Check the CA, certificates, inventory and live membership; exits non-zero on a failure.")
    static final class Doctor extends Base {

        @Option(names = "--file", paramLabel = "FILE", description = "The cluster inventory; defaults to the active profile's bundle.")
        Path file;

        @Override
        int execute() {
            return ClusterStatus.doctor(this, inventory(this, file));
        }
    }

    @Command(name = "invite", description = "Sign a certificate for a node with ca.key, print its join line, and record it in the inventory.")
    static final class Invite extends InviteSupport {

        @Override
        int execute() {
            Path key = ca;
            // A custom `cluster init --out` recorded its bundle in the profile; use that CA unless --ca is explicit.
            if (ca == null || "./cluster-tls/ca.key".equals(ca.toString())) {
                Path inventory = file != null ? ClusterFile.resolve(file) : inventory(this, null);
                if (inventory != null && Files.isRegularFile(inventory.getParent().resolve("ca.key"))) {
                    key = inventory.getParent().resolve("ca.key");
                }
            }
            return ClusterInvite.run(this, nodeId, hosts, key, publicKey, file);
        }
    }

    @Command(name = "revoke", description = "Revoke a node through the cluster API, and mark it in the inventory.")
    static final class Revoke extends RevokeSupport {

        @Override
        int execute() {
            return ClusterRevoke.run(this, nodeId, file);
        }
    }
}
