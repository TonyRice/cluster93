package automately.core.host;

import io.vertx.core.http.HttpConnection;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a script's socket stand-in reports about a connection the host made for it (D-114), in Node's shapes: the
 * addresses, whether the connection came from the pool, and for TLS the protocol, cipher, ALPN, whether the peer was
 * trusted and its certificate chain as Node's {@code getPeerCertificate()} describes it.
 */
public final class ConnectionInfo {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    /** OpenSSL's short names for the attribute types Java spells differently. */
    private static final Map<String, String> ATTRIBUTES = Map.of("EMAILADDRESS", "emailAddress", "1.2.840.113549.1.9.1", "emailAddress",
            "SERIALNUMBER", "serialNumber", "2.5.4.5", "serialNumber", "DC", "DC", "2.5.4.15", "businessCategory");

    private ConnectionInfo() {
    }

    /**
     * @param reused   whether the pool handed back a connection an earlier request used
     * @param verified whether the host verified the peer (it does unless the request opted out)
     */
    public static JsonObject of(HttpConnection connection, boolean reused, boolean verified) {
        JsonObject info = new JsonObject().put("reused", reused);
        address(info, "remote", connection.remoteAddress());
        address(info, "local", connection.localAddress());
        if (!connection.isSsl()) {
            return info;
        }
        SSLSession session = connection.sslSession();
        JsonObject tls = new JsonObject();
        if (session != null) {
            tls.put("protocol", session.getProtocol()).put("cipher", session.getCipherSuite());
        }
        List<Certificate> chain;
        try {
            chain = connection.peerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            chain = List.of();
        }
        tls.put("peerCertificates", certificates(chain));
        String error = verified ? null : authorizationError(chain);
        tls.put("authorized", error == null);
        if (error != null) {
            tls.put("authorizationError", error);
        }
        return info.put("tls", tls);
    }

    private static void address(JsonObject info, String side, SocketAddress address) {
        if (address == null || address.hostAddress() == null) {
            return;
        }
        String host = address.hostAddress();
        info.put(side + "Address", host).put(side + "Port", address.port()).put(side + "Family", host.contains(":") ? "IPv6" : "IPv4");
    }

    /**
     * Why a chain wouldn't have been trusted, with OpenSSL's code as Node reports it, or null if the JVM's trust store
     * trusts it. Only asked when the request turned verification off, so it can still say whether the peer was trusted.
     */
    static String authorizationError(List<Certificate> chain) {
        if (chain.isEmpty()) {
            return "UNABLE_TO_GET_ISSUER_CERT";
        }
        X509Certificate[] x509 = chain.stream().filter(X509Certificate.class::isInstance).map(X509Certificate.class::cast)
                .toArray(X509Certificate[]::new);
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager trust) {
                    // "UNKNOWN" is what TLS 1.3 passes: the chain is judged, not the key exchange.
                    trust.checkServerTrusted(x509, "UNKNOWN");
                    return null;
                }
            }
        } catch (CertificateException e) {
            X509Certificate last = x509[x509.length - 1];
            if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal())) {
                return x509.length == 1 ? "DEPTH_ZERO_SELF_SIGNED_CERT" : "SELF_SIGNED_CERT_IN_CHAIN";
            }
            return x509.length == 1 ? "UNABLE_TO_VERIFY_LEAF_SIGNATURE" : "UNABLE_TO_GET_ISSUER_CERT_LOCALLY";
        } catch (NoSuchAlgorithmException | java.security.KeyStoreException e) {
            return "UNABLE_TO_GET_ISSUER_CERT_LOCALLY";
        }
        return "UNABLE_TO_GET_ISSUER_CERT_LOCALLY";
    }

    /** The chain, leaf first, each as Node's {@code getPeerCertificate()} describes one (without the links between them). */
    public static JsonArray certificates(List<Certificate> chain) {
        JsonArray out = new JsonArray();
        for (Certificate certificate : chain) {
            if (certificate instanceof X509Certificate x509) {
                out.add(describe(x509));
            }
        }
        return out;
    }

    static JsonObject describe(X509Certificate cert) {
        JsonObject json = new JsonObject()
                .put("subject", names(cert.getSubjectX500Principal().getName()))
                .put("issuer", names(cert.getIssuerX500Principal().getName()))
                .put("ca", cert.getBasicConstraints() >= 0);
        String alt = subjectAltName(cert);
        if (alt != null) {
            json.put("subjectaltname", alt);
        }
        if (cert.getPublicKey() instanceof RSAPublicKey rsa) {
            json.put("bits", rsa.getModulus().bitLength())
                    .put("exponent", "0x" + rsa.getPublicExponent().toString(16))
                    .put("modulus", rsa.getModulus().toString(16).toUpperCase(Locale.ROOT));
        } else if (cert.getPublicKey() instanceof ECPublicKey ec) {
            json.put("bits", ec.getParams().getCurve().getField().getFieldSize());
            String params = ec.getParams().toString();
            if (params.contains("secp256r1") || params.contains("P-256")) {
                json.put("asn1Curve", "prime256v1").put("nistCurve", "P-256");
            } else if (params.contains("secp384r1") || params.contains("P-384")) {
                json.put("asn1Curve", "secp384r1").put("nistCurve", "P-384");
            } else if (params.contains("secp521r1") || params.contains("P-521")) {
                json.put("asn1Curve", "secp521r1").put("nistCurve", "P-521");
            }
        }
        json.put("pubkey", Base64.getEncoder().encodeToString(cert.getPublicKey().getEncoded()))
                .put("valid_from", openSslDate(cert.getNotBefore()))
                .put("valid_to", openSslDate(cert.getNotAfter()));
        try {
            byte[] der = cert.getEncoded();
            json.put("fingerprint", fingerprint("SHA-1", der))
                    .put("fingerprint256", fingerprint("SHA-256", der))
                    .put("fingerprint512", fingerprint("SHA-512", der))
                    .put("raw", Base64.getEncoder().encodeToString(der));
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            // Without its encoding, the certificate is described without fingerprints.
        }
        try {
            List<String> usage = cert.getExtendedKeyUsage();
            if (usage != null) {
                json.put("ext_key_usage", new JsonArray(new ArrayList<>(usage)));
            }
        } catch (CertificateParsingException e) {
            // As OpenSSL does, an unreadable extension is left out.
        }
        return json.put("serialNumber", serial(cert.getSerialNumber()));
    }

    /** A distinguished name as Node's object: {@code {C: 'US', O: 'Example', CN: 'example.com'}}, repeats as lists. */
    static JsonObject names(String rfc2253) {
        JsonObject out = new JsonObject();
        try {
            // LdapName lists the RDNs from the last in the string, which is the order they're encoded in.
            for (Rdn rdn : new LdapName(rfc2253).getRdns()) {
                String type = ATTRIBUTES.getOrDefault(rdn.getType().toUpperCase(Locale.ROOT), rdn.getType());
                String value = rdn.getValue() instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(rdn.getValue());
                Object existing = out.getValue(type);
                if (existing == null) {
                    out.put(type, value);
                } else if (existing instanceof JsonArray list) {
                    list.add(value);
                } else {
                    out.put(type, new JsonArray().add(existing).add(value));
                }
            }
        } catch (InvalidNameException e) {
            // An unparseable name is described as empty, as an unreadable one would be.
        }
        return out;
    }

    private static String subjectAltName(X509Certificate cert) {
        try {
            var names = cert.getSubjectAlternativeNames();
            if (names == null) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (List<?> name : names) {
                int type = (Integer) name.get(0);
                String value = String.valueOf(name.get(1));
                switch (type) {
                    case 1 -> parts.add("email:" + value);
                    case 2 -> parts.add("DNS:" + value);
                    case 6 -> parts.add("URI:" + value);
                    case 7 -> parts.add("IP Address:" + value);
                    default -> {
                    }
                }
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        } catch (CertificateParsingException e) {
            return null;
        }
    }

    /** OpenSSL's {@code ASN1_TIME_print}: {@code "Jan  1 00:00:00 2026 GMT"}. */
    static String openSslDate(java.util.Date date) {
        ZonedDateTime t = date.toInstant().atZone(ZoneOffset.UTC);
        return String.format(Locale.ROOT, "%s %2d %02d:%02d:%02d %d GMT", MONTHS[t.getMonthValue() - 1], t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond(), t.getYear());
    }

    private static String fingerprint(String algorithm, byte[] der) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(der);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) {
            if (!out.isEmpty()) {
                out.append(':');
            }
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }

    /** A serial number's hex as OpenSSL prints it: upper case, an even number of digits. */
    private static String serial(BigInteger serial) {
        String hex = serial.toString(16).toUpperCase(Locale.ROOT);
        return hex.length() % 2 == 0 ? hex : "0" + hex;
    }
}
