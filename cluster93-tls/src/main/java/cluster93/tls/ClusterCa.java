package cluster93.tls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The cluster's own certificate authority (D-066): a key pair and a self-signed root that signs a certificate for each
 * core node. Nothing outside the cluster trusts it. Its private key stays on the operator's machine ({@code ca.key});
 * the cluster never stores it.
 */
public record ClusterCa(PrivateKey key, X509Certificate certificate) {

    public static final Duration CA_VALIDITY = Duration.ofDays(3650);
    public static final Duration NODE_VALIDITY = Duration.ofDays(3 * 365);
    public static final String KEY_FILE = "ca.key";
    public static final String CERT_FILE = "ca.crt";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new CA. {@code name} labels it, for example the cluster's name. */
    public static ClusterCa create(String name) {
        KeyPair pair = Keys.generate();
        Instant now = Instant.now();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.O, "Cluster93").addRDN(BCStyle.CN, "Cluster93 cluster CA " + name).build();
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, serial(), Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(CA_VALIDITY)), subject, pair.getPublic());
            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.getPublic()));
            return new ClusterCa(pair.getPrivate(), sign(builder, pair.getPrivate()));
        } catch (GeneralSecurityException | IOException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new IllegalStateException("Can't create the cluster CA", e);
        }
    }

    /** Reads {@code ca.key} and {@code ca.crt}; the key file may be given directly, with {@code ca.crt} beside it. */
    public static ClusterCa load(Path keyFile) {
        try {
            Path cert = keyFile.resolveSibling(CERT_FILE);
            ClusterCa ca = new ClusterCa(Pem.toPrivateKey(Pem.only(Files.readString(keyFile), "PRIVATE KEY")),
                    Pem.toCertificate(Pem.only(Files.readString(cert), "CERTIFICATE")));
            if (!Keys.matches(ca.key, ca.certificate.getPublicKey())) {
                throw new IllegalArgumentException(keyFile + " isn't the key of " + cert);
            }
            return ca;
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read the cluster CA from " + keyFile, e);
        }
    }

    /** Writes {@code ca.key} (owner-only) and {@code ca.crt} into {@code directory}. */
    public void write(Path directory) {
        Secrets.writePrivate(directory.resolve(KEY_FILE), Pem.privateKey(key));
        Secrets.writePublic(directory.resolve(CERT_FILE), Pem.certificate(certificate));
    }

    /** SHA-256 of the CA's public key (SubjectPublicKeyInfo), hex: what a join string pins. */
    public String fingerprint() {
        return fingerprint(certificate.getPublicKey());
    }

    public static String fingerprint(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Signs a certificate for core node {@code nodeId}: its common name is the node id, and its subject alternative
     * names are the node id and {@code hosts} (host names or IP addresses), for both TLS server and client use.
     */
    public X509Certificate issue(String nodeId, List<String> hosts, PublicKey nodeKey, Duration validity) {
        if (nodeId == null || !nodeId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")) {
            throw new IllegalArgumentException("A node id is letters, digits, '.', '_' and '-' (it was " + nodeId + ")");
        }
        Instant now = Instant.now();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.O, "Cluster93").addRDN(BCStyle.CN, nodeId).build();
        List<GeneralName> names = new ArrayList<>();
        names.add(new GeneralName(GeneralName.dNSName, nodeId));
        for (String host : hosts) {
            if (host == null || host.isBlank() || host.equals(nodeId)) {
                continue;
            }
            names.add(IPV4.matcher(host).matches() || host.contains(":") ? new GeneralName(GeneralName.iPAddress, host)
                    : new GeneralName(GeneralName.dNSName, host));
        }
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(certificate, serial(),
                    Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(validity)), subject, nodeKey);
            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(GeneralName[]::new)));
            builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(certificate));
            return sign(builder, key);
        } catch (GeneralSecurityException | IOException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new IllegalStateException("Can't issue a certificate for " + nodeId, e);
        }
    }

    /** A new key pair for {@code nodeId}, and its certificate. */
    public NodeIdentity issueNode(String nodeId, List<String> hosts) {
        KeyPair pair = Keys.generate();
        return new NodeIdentity(pair.getPrivate(), issue(nodeId, hosts, pair.getPublic(), NODE_VALIDITY), certificate);
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey signer)
            throws org.bouncycastle.operator.OperatorCreationException, GeneralSecurityException {
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer)));
    }

    /**
     * The issue time in milliseconds in the high bits, and 64 random bits below. Certificate dates only have
     * second precision, so revocation (D-066) reads the exact issue time from here: a node invited again in the same
     * second it was revoked is still let back in.
     */
    private static BigInteger serial() {
        return BigInteger.valueOf(System.currentTimeMillis()).shiftLeft(64).or(new BigInteger(64, RANDOM));
    }

    /** When a certificate was issued, in milliseconds: from our serials, else its start date plus the 5-minute backdating. */
    static long issuedAt(java.security.cert.X509Certificate certificate) {
        long fromSerial = certificate.getSerialNumber().shiftRight(64).longValue();
        long notBefore = certificate.getNotBefore().getTime();
        // Our serials put the issue time within a few minutes after the (back-dated) start date.
        if (fromSerial >= notBefore && fromSerial <= notBefore + Duration.ofMinutes(10).toMillis()) {
            return fromSerial;
        }
        return notBefore + Duration.ofMinutes(5).toMillis();
    }
}
