package automately.tls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import javax.security.auth.x500.X500Principal;

/**
 * A core node's TLS identity (D-066): its private key, its certificate, and the cluster CA's certificate, which is the
 * only thing it trusts. Kept in the node's TLS directory as {@code node.key} (owner-only), {@code node.crt} and
 * {@code ca.crt}, so a node restarts with what it has.
 */
public record NodeIdentity(PrivateKey key, X509Certificate certificate, X509Certificate ca) {

    public static final String KEY_FILE = "node.key";
    public static final String CERT_FILE = "node.crt";

    public static boolean existsIn(Path directory) {
        return Files.isRegularFile(directory.resolve(KEY_FILE)) && Files.isRegularFile(directory.resolve(CERT_FILE))
                && Files.isRegularFile(directory.resolve(ClusterCa.CERT_FILE));
    }

    public static NodeIdentity load(Path directory) {
        try {
            NodeIdentity identity = new NodeIdentity(
                    Pem.toPrivateKey(Pem.only(Files.readString(directory.resolve(KEY_FILE)), "PRIVATE KEY")),
                    Pem.toCertificate(Pem.only(Files.readString(directory.resolve(CERT_FILE)), "CERTIFICATE")),
                    Pem.toCertificate(Pem.only(Files.readString(directory.resolve(ClusterCa.CERT_FILE)), "CERTIFICATE")));
            identity.verify();
            return identity;
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read the node's TLS identity from " + directory, e);
        }
    }

    public void write(Path directory) {
        Secrets.writePrivate(directory.resolve(KEY_FILE), Pem.privateKey(key));
        Secrets.writePublic(directory.resolve(CERT_FILE), Pem.certificate(certificate));
        Secrets.writePublic(directory.resolve(ClusterCa.CERT_FILE), Pem.certificate(ca));
    }

    /**
     * For the strict join ({@code --prepare}): makes this node's key pair, keeps the private key in {@code directory}
     * (owner-only), and returns the public key as one line for the operator's {@code node invite --key}.
     */
    public static String prepareKey(Path directory) {
        java.security.KeyPair pair = Keys.generate();
        Secrets.writePrivate(directory.resolve(KEY_FILE), Pem.privateKey(pair.getPrivate()));
        return NodeBundle.publicKeyLine(pair.getPublic());
    }

    /** The private key {@code --prepare} left in {@code directory}, or null. */
    public static PrivateKey preparedKey(Path directory) {
        try {
            Path file = directory.resolve(KEY_FILE);
            return Files.isRegularFile(file) ? Pem.toPrivateKey(Pem.only(Files.readString(file), "PRIVATE KEY")) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The node id the certificate was issued to (its common name). */
    public String nodeId() {
        return commonName(certificate);
    }

    public Instant expiresAt() {
        return certificate.getNotAfter().toInstant();
    }

    /**
     * Checks the parts belong together: the key is the certificate's, the certificate is signed by the CA and valid
     * now, and the CA is a CA. Throws {@link IllegalArgumentException} saying what's wrong.
     */
    public void verify() {
        if (!Keys.matches(key, certificate.getPublicKey())) {
            throw new IllegalArgumentException("The node key doesn't belong to the node certificate");
        }
        try {
            certificate.verify(ca.getPublicKey());
        } catch (Exception e) {
            throw new IllegalArgumentException("The node certificate isn't signed by this cluster's CA");
        }
        try {
            certificate.checkValidity();
        } catch (Exception e) {
            throw new IllegalArgumentException("The node certificate has expired or isn't valid yet (" + certificate.getNotBefore()
                    + " to " + certificate.getNotAfter() + "); issue a new one with: automately node invite " + nodeId());
        }
        if (ca.getBasicConstraints() < 0) {
            throw new IllegalArgumentException("ca.crt isn't a CA certificate");
        }
    }

    /** The certificate's subject alternative names (host names and addresses). */
    public List<String> hosts() {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            return names == null ? List.of() : names.stream().map(name -> String.valueOf(name.get(1))).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    static String commonName(X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName(X500Principal.RFC2253);
        for (String part : dn.split(",")) {
            if (part.startsWith("CN=")) {
                return part.substring(3);
            }
        }
        return null;
    }
}
