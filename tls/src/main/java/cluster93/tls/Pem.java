package cluster93.tls;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PEM text for keys and certificates, with only the JDK: PKCS#8 private keys, X.509 public keys and certificates. */
public final class Pem {

    /** One PEM block: its label (CERTIFICATE, PRIVATE KEY, PUBLIC KEY) and DER bytes. */
    public record Block(String label, byte[] der) {
    }

    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private Pem() {
    }

    public static String encode(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    public static String certificate(X509Certificate certificate) {
        try {
            return encode("CERTIFICATE", certificate.getEncoded());
        } catch (CertificateException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String privateKey(PrivateKey key) {
        return encode("PRIVATE KEY", key.getEncoded());
    }

    public static String publicKey(PublicKey key) {
        return encode("PUBLIC KEY", key.getEncoded());
    }

    /** Every block in {@code text}, in order. */
    public static List<Block> blocks(String text) {
        List<Block> blocks = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(text);
        while (matcher.find()) {
            blocks.add(new Block(matcher.group(1), Base64.getMimeDecoder().decode(matcher.group(2).replaceAll("\\s", ""))));
        }
        return blocks;
    }

    public static X509Certificate toCertificate(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new IllegalArgumentException("Not an X.509 certificate", e);
        }
    }

    public static PrivateKey toPrivateKey(byte[] der) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not an EC private key (PKCS#8)", e);
        }
    }

    public static PublicKey toPublicKey(byte[] der) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not an EC public key", e);
        }
    }

    /** The single block with {@code label} in {@code text}. */
    public static byte[] only(String text, String label) {
        List<byte[]> found = blocks(text).stream().filter(b -> b.label().equals(label)).map(Block::der).toList();
        if (found.size() != 1) {
            throw new IllegalArgumentException("Expected one " + label + " block, found " + found.size());
        }
        return found.getFirst();
    }
}
