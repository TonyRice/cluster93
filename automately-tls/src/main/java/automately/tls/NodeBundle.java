package automately.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What a node needs to join, in one line (D-067): gzipped PEM blocks, base64url-encoded, behind a prefix that says
 * what's inside.
 * <ul>
 *   <li>{@code atly_nodebundle_…}: the node's private key, its certificate and the CA certificate. A secret for
 *   that node only: it can't create other nodes or sign anything.</li>
 *   <li>{@code atly_nodecert_…}: the certificate and the CA certificate, for a node that made its own key
 *   ({@code --prepare}); nothing secret travels.</li>
 * </ul>
 * It never contains the CA's private key.
 */
public final class NodeBundle {

    public static final String BUNDLE_PREFIX = "atly_nodebundle_";
    public static final String CERT_PREFIX = "atly_nodecert_";

    /** A decoded bundle; {@code key} is null for a certificate-only one. */
    public record Contents(PrivateKey key, X509Certificate certificate, X509Certificate ca) {

        /** The node's identity, using {@code ownKey} when the bundle carries no key. */
        public NodeIdentity identity(PrivateKey ownKey) {
            NodeIdentity identity = new NodeIdentity(key != null ? key : ownKey, certificate, ca);
            identity.verify();
            return identity;
        }
    }

    private NodeBundle() {
    }

    public static String bundle(NodeIdentity identity) {
        return BUNDLE_PREFIX + pack(Pem.privateKey(identity.key()) + Pem.certificate(identity.certificate()) + Pem.certificate(identity.ca()));
    }

    public static String certificateOnly(X509Certificate certificate, X509Certificate ca) {
        return CERT_PREFIX + pack(Pem.certificate(certificate) + Pem.certificate(ca));
    }

    public static boolean looksLikeOne(String text) {
        return text != null && (text.startsWith(BUNDLE_PREFIX) || text.startsWith(CERT_PREFIX));
    }

    public static Contents decode(String text) {
        String value = text.trim();
        boolean withKey = value.startsWith(BUNDLE_PREFIX);
        if (!withKey && !value.startsWith(CERT_PREFIX)) {
            throw new IllegalArgumentException("Not a node bundle (expected " + BUNDLE_PREFIX + "… or " + CERT_PREFIX + "…)");
        }
        String pem = unpack(value.substring(withKey ? BUNDLE_PREFIX.length() : CERT_PREFIX.length()));
        List<Pem.Block> blocks = Pem.blocks(pem);
        PrivateKey key = null;
        int next = 0;
        if (withKey) {
            if (blocks.isEmpty() || !blocks.getFirst().label().equals("PRIVATE KEY")) {
                throw new IllegalArgumentException("The bundle has no node key");
            }
            key = Pem.toPrivateKey(blocks.getFirst().der());
            next = 1;
        }
        if (blocks.size() != next + 2 || blocks.stream().skip(next).anyMatch(b -> !b.label().equals("CERTIFICATE"))) {
            throw new IllegalArgumentException("The bundle should hold the node certificate and the CA certificate");
        }
        if (blocks.stream().anyMatch(b -> b.label().contains("PRIVATE")) && !withKey) {
            throw new IllegalArgumentException("A certificate-only bundle must not hold a private key");
        }
        return new Contents(key, Pem.toCertificate(blocks.get(next).der()), Pem.toCertificate(blocks.get(next + 1).der()));
    }

    /** For {@code --prepare}: the public key a node prints for the operator, as one line. */
    public static String publicKeyLine(PublicKey key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded());
    }

    public static PublicKey publicKeyFromLine(String line) {
        return Pem.toPublicKey(Base64.getUrlDecoder().decode(line.trim()));
    }

    private static String pack(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private static String unpack(String text) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(text)))) {
            byte[] bytes = gzip.readNBytes(64 * 1024);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("The bundle is damaged (copy the whole line)", e);
        }
    }
}
