package cluster93.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cluster CA, node identities, bundles, and TLS that requires a cluster certificate on both sides (D-066, D-067). */
class ClusterTlsTest {

    private final ClusterCa ca = ClusterCa.create("test");

    @Test
    void aNodeCertificateNamesItsNodeAndHostsAndChainsToTheCa(@TempDir Path dir) throws Exception {
        NodeIdentity node = ca.issueNode("node1", List.of("node1", "10.0.0.11", "node1.internal"));

        assertThat(node.nodeId()).isEqualTo("node1");
        assertThat(node.hosts()).contains("node1", "10.0.0.11", "node1.internal");
        node.verify();

        node.write(dir);
        assertThat(NodeIdentity.existsIn(dir)).isTrue();
        assertThat(NodeIdentity.load(dir).certificate()).isEqualTo(node.certificate());
        if (Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(NodeIdentity.KEY_FILE))))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void theCaRoundTripsThroughItsFiles(@TempDir Path dir) {
        ca.write(dir);
        ClusterCa loaded = ClusterCa.load(dir.resolve(ClusterCa.KEY_FILE));

        assertThat(loaded.fingerprint()).isEqualTo(ca.fingerprint()).hasSize(64);
        loaded.issueNode("node2", List.of()).verify();
    }

    @Test
    void aBundleCarriesTheNodeKeyButNeverTheCaKey() {
        NodeIdentity node = ca.issueNode("node4", List.of("10.0.0.14"));
        String bundle = NodeBundle.bundle(node);

        assertThat(bundle).startsWith(NodeBundle.BUNDLE_PREFIX).doesNotContain(" ").hasSizeLessThan(4096);
        NodeBundle.Contents contents = NodeBundle.decode(bundle);
        assertThat(contents.key()).isEqualTo(node.key());
        assertThat(contents.identity(null).nodeId()).isEqualTo("node4");
        // The CA's private key appears nowhere in it.
        String unpacked = new String(java.util.Base64.getUrlDecoder().decode(bundle.substring(NodeBundle.BUNDLE_PREFIX.length())));
        assertThat(contents.key()).isNotEqualTo(ca.key());
        assertThat(unpacked).doesNotContain(java.util.Base64.getEncoder().encodeToString(ca.key().getEncoded()));
    }

    @Test
    void aCertificateOnlyBundleNeedsTheNodesOwnKey() {
        KeyPair own = Keys.generate();
        String line = NodeBundle.certificateOnly(ca.issue("node5", List.of(), NodeBundle.publicKeyFromLine(NodeBundle.publicKeyLine(own.getPublic())),
                ClusterCa.NODE_VALIDITY), ca.certificate());

        NodeBundle.Contents contents = NodeBundle.decode(line);
        assertThat(contents.key()).isNull();
        assertThat(contents.identity(own.getPrivate()).nodeId()).isEqualTo("node5");
        assertThatThrownBy(() -> contents.identity(Keys.generate().getPrivate()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("doesn't belong");
    }

    @Test
    void damagedOrForeignBundlesAreRefused() {
        String bundle = NodeBundle.bundle(ca.issueNode("node6", List.of()));
        assertThatThrownBy(() -> NodeBundle.decode(bundle.substring(0, bundle.length() - 10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeBundle.decode("c93_join_nope")).hasMessageContaining("Not a node bundle");

        // A certificate from another CA, packed with this CA's certificate, doesn't verify.
        NodeIdentity foreign = ClusterCa.create("other").issueNode("node6", List.of());
        NodeIdentity mixed = new NodeIdentity(foreign.key(), foreign.certificate(), ca.certificate());
        assertThatThrownBy(mixed::verify).hasMessageContaining("isn't signed by this cluster's CA");
    }

    @Test
    void serverEnginesRequireTheClientCertificate() throws Exception {
        SSLContext context = new ClusterTls(ca.issueNode("node1", List.of()), new Revocations()).sslContext();
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setWantClientAuth(false);   // a library asking for less still gets "required"
        assertThat(engine.getNeedClientAuth()).isTrue();
        try (SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0)) {
            assertThat(socket.getNeedClientAuth()).isTrue();
        }
    }

    @Test
    void onlyMembersCompleteAHandshake() throws Exception {
        Revocations revocations = new Revocations();
        ClusterTls server = new ClusterTls(ca.issueNode("node1", List.of("127.0.0.1")), revocations);

        assertThat(handshake(server, new ClusterTls(ca.issueNode("node2", List.of()), new Revocations()).sslContext())).isTrue();

        // No certificate at all: a client that trusts the CA but presents nothing.
        SSLContext anonymous = SSLContext.getInstance(ClusterTls.PROTOCOL);
        anonymous.init(null, new javax.net.ssl.TrustManager[] {server.trustManager()}, null);
        assertThat(handshake(server, anonymous)).isFalse();

        // A certificate from another CA.
        assertThat(handshake(server, new ClusterTls(ClusterCa.create("other").issueNode("node3", List.of()), new Revocations()).sslContext())).isFalse();

        // A revoked node; a certificate issued to it again afterwards is fine, even within the same second (certificate
        // dates only have second precision, so the exact issue time comes from the serial).
        NodeIdentity node4 = ca.issueNode("node4", List.of());
        Thread.sleep(2);
        revocations.revoke("node4", System.currentTimeMillis());
        assertThat(handshake(server, new ClusterTls(node4, new Revocations()).sslContext())).isFalse();
        Thread.sleep(2);
        NodeIdentity reinvited = ca.issueNode("node4", List.of());
        assertThat(reinvited.certificate().getNotBefore().getTime() / 1000).isLessThanOrEqualTo(System.currentTimeMillis() / 1000);
        assertThat(handshake(server, new ClusterTls(reinvited, new Revocations()).sslContext())).isTrue();
    }

    /** Whether a client using {@code client} completes a handshake and exchanges a byte with {@code server}. */
    private static boolean handshake(ClusterTls server, SSLContext client) throws Exception {
        try (SSLServerSocket listener = (SSLServerSocket) server.sslContext().getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Boolean> accepted = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    out.write(in.read());
                    out.flush();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            });
            boolean clientOk;
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(42);
                socket.getOutputStream().flush();
                clientOk = socket.getInputStream().read() == 42;
            } catch (IOException e) {
                clientOk = false;
            }
            return accepted.get(10, TimeUnit.SECONDS) && clientOk;
        }
    }
}
