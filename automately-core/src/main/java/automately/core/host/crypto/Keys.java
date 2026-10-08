package automately.core.host.crypto;

import automately.core.runtime.HostException;
import io.vertx.core.json.JsonObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asymmetric keys and signatures for Node's {@code crypto} (D-101), on the JVM's {@link KeyFactory},
 * {@link KeyPairGenerator} and {@link Signature}.
 *
 * <p>Keys stay in the job: JavaScript holds each one as DER (PKCS#8 for a private key, SPKI for a public one) and hands
 * it back with every call, and nothing is kept here between calls. A key comes back as its <em>info</em>: {@code type},
 * {@code asymmetricKeyType}, {@code der} (base64) and {@code details}, the same DER whatever format it was read from, so
 * exporting it again gives what Node gives.
 */
public final class Keys {

    /** Biggest RSA key generated (D-095). */
    public static final int MAX_RSA_GENERATE_BITS = 4096;
    /** Biggest RSA key read: verifying with a bigger key someone else made is still cheap enough. */
    public static final int MAX_RSA_BITS = 8192;
    /** Most bytes signed or verified at once. */
    public static final int MAX_SIGNED_BYTES = 32 * 1024 * 1024;

    static final String RSA = "1.2.840.113549.1.1.1";
    static final String RSA_PSS = "1.2.840.113549.1.1.10";
    static final String EC = "1.2.840.10045.2.1";
    static final String ED25519 = "1.3.101.112";

    /** A curve: Node's (OpenSSL's) name, the JOSE name, the OID and the JVM's name. */
    record Curve(String node, String jose, String oid, String jvm, int bytes) {
    }

    static final List<Curve> CURVES = List.of(
            new Curve("prime256v1", "P-256", "1.2.840.10045.3.1.7", "secp256r1", 32),
            new Curve("secp384r1", "P-384", "1.3.132.0.34", "secp384r1", 48),
            new Curve("secp521r1", "P-521", "1.3.132.0.35", "secp521r1", 66));

    private Keys() {
    }

    public static List<String> curves() {
        return CURVES.stream().map(Curve::node).toList();
    }

    static Curve curve(String name) {
        for (Curve curve : CURVES) {
            if (curve.node.equalsIgnoreCase(name) || curve.jose.equalsIgnoreCase(name) || curve.jvm.equalsIgnoreCase(name) || curve.oid.equals(name)) {
                return curve;
            }
        }
        throw HostException.invalid("Unsupported curve: " + name + " (the isolate has " + String.join(", ", curves()) + ")");
    }

    // --- reading ---

    /**
     * A key from {@code data} in {@code format} (pem, der or jwk; for der, {@code type} is pkcs1, pkcs8, sec1 or spki).
     * With {@code wantPrivate} false, private material gives its public key, as {@code createPublicKey} does.
     */
    public static JsonObject read(boolean wantPrivate, byte[] data, String format, String type) {
        Decoded decoded = switch (format == null ? "pem" : format) {
            case "pem" -> pem(new String(data, StandardCharsets.UTF_8), wantPrivate);
            case "der" -> der(data, type);
            case "jwk" -> jwk(new JsonObject(new String(data, StandardCharsets.UTF_8)));
            default -> throw HostException.invalid("Unsupported key format: " + format);
        };
        if (wantPrivate && decoded.publicKey != null) {
            throw new HostException("TypeError", "ERR_INVALID_ARG_VALUE", "A private key was expected; this is a public key");
        }
        if (!wantPrivate && decoded.privateKey != null) {
            return info(publicOf(decoded.privateKey));
        }
        return decoded.privateKey != null ? info(decoded.privateKey) : info(decoded.publicKey);
    }

    /** The public key of a private key held as PKCS#8. */
    public static JsonObject publicFromPrivate(byte[] pkcs8) {
        return info(publicOf(privateKey(pkcs8)));
    }

    private record Decoded(PrivateKey privateKey, PublicKey publicKey) {
    }

    private static Decoded pem(String text, boolean wantPrivate) {
        int at = 0;
        while (true) {
            int begin = text.indexOf("-----BEGIN ", at);
            if (begin < 0) {
                throw HostException.invalid(wantPrivate ? "Failed to read private key: no PEM block found" : "Failed to read key: no PEM block found");
            }
            int labelEnd = text.indexOf("-----", begin + 11);
            if (labelEnd < 0) {
                throw HostException.invalid("Malformed PEM");
            }
            String label = text.substring(begin + 11, labelEnd);
            String endMarker = "-----END " + label + "-----";
            int end = text.indexOf(endMarker, labelEnd);
            if (end < 0) {
                throw HostException.invalid("Malformed PEM: no " + endMarker);
            }
            String body = text.substring(labelEnd + 5, end);
            at = end + endMarker.length();
            if (label.equals("EC PARAMETERS")) {
                continue;
            }
            if (body.contains("Proc-Type") || label.equals("ENCRYPTED PRIVATE KEY")) {
                throw HostException.invalid("Encrypted private keys aren't supported in the isolate yet; decrypt it first");
            }
            byte[] der;
            try {
                der = Base64.getMimeDecoder().decode(body.strip());
            } catch (IllegalArgumentException e) {
                throw HostException.invalid("Malformed PEM: " + e.getMessage());
            }
            return switch (label) {
                case "PRIVATE KEY" -> der(der, "pkcs8");
                case "RSA PRIVATE KEY" -> der(der, "pkcs1-private");
                case "EC PRIVATE KEY" -> der(der, "sec1");
                case "PUBLIC KEY" -> der(der, "spki");
                case "RSA PUBLIC KEY" -> der(der, "pkcs1-public");
                case "CERTIFICATE" -> new Decoded(null, certificateKey(der));
                default -> throw HostException.invalid("Unsupported PEM type: " + label);
            };
        }
    }

    private static Decoded der(byte[] der, String type) {
        String kind = type == null ? "" : type;
        return switch (kind) {
            case "pkcs8" -> new Decoded(privateKey(der), null);
            case "spki" -> new Decoded(null, publicKey(der));
            case "sec1" -> new Decoded(privateKey(sec1ToPkcs8(der)), null);
            case "pkcs1-private" -> new Decoded(privateKey(Der.sequence(Der.integer(0), Der.sequence(Der.oid(RSA), Der.nul()), Der.octets(der))), null);
            case "pkcs1-public" -> new Decoded(null, publicKey(Der.sequence(Der.sequence(Der.oid(RSA), Der.nul()), Der.bits(der))));
            // Node's "pkcs1" means either; a private RSAPrivateKey starts with its version, 0.
            case "pkcs1" -> Der.one(der).children().getFirst().tag() == Der.INTEGER && Der.one(der).children().size() > 2
                    ? der(der, "pkcs1-private") : der(der, "pkcs1-public");
            default -> throw HostException.invalid("A DER key needs its type: pkcs1, pkcs8, sec1 or spki");
        };
    }

    private static PublicKey certificateKey(byte[] der) {
        try {
            return checked(CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der)).getPublicKey());
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Failed to read the certificate: " + e.getMessage());
        }
    }

    /** The algorithm OID (and, for EC, the curve OID) of a PKCS#8 or SPKI structure. */
    private static String[] algorithm(byte[] der, boolean pkcs8) {
        List<Der.Element> parts = Der.one(der).children();
        List<Der.Element> id = parts.get(pkcs8 ? 1 : 0).children();
        String oid = id.getFirst().oid();
        String param = id.size() > 1 && id.get(1).tag() == Der.OID ? id.get(1).oid() : null;
        return new String[] {oid, param};
    }

    static PrivateKey privateKey(byte[] pkcs8) {
        String[] algorithm = algorithm(pkcs8, true);
        try {
            return checked(switch (algorithm[0]) {
                case RSA -> KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                case EC -> {
                    curve(algorithm[1]);
                    yield KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                }
                case ED25519 -> KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                default -> throw unsupported(algorithm[0]);
            });
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Failed to read private key: " + e.getMessage());
        }
    }

    static PublicKey publicKey(byte[] spki) {
        String[] algorithm = algorithm(spki, false);
        try {
            return checked(switch (algorithm[0]) {
                case RSA -> KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
                case EC -> {
                    curve(algorithm[1]);
                    yield KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
                }
                case ED25519 -> KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
                default -> throw unsupported(algorithm[0]);
            });
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Failed to read public key: " + e.getMessage());
        }
    }

    private static HostException unsupported(String oid) {
        String name = switch (oid) {
            case RSA_PSS -> "rsa-pss";
            case "1.3.101.110" -> "x25519";
            case "1.3.101.113" -> "ed448";
            case "1.2.840.10040.4.1" -> "dsa";
            default -> oid;
        };
        return HostException.invalid("Unsupported key type in the isolate: " + name + " (rsa, ec and ed25519 are supported)");
    }

    private static <K extends java.security.Key> K checked(K key) {
        if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() > MAX_RSA_BITS
                || key instanceof RSAPrivateCrtKey crt && crt.getModulus().bitLength() > MAX_RSA_BITS) {
            throw HostException.limit("RSA keys of at most " + MAX_RSA_BITS + " bits");
        }
        if (key instanceof java.security.interfaces.RSAPrivateKey && !(key instanceof RSAPrivateCrtKey)) {
            throw HostException.invalid("RSA private keys need their CRT parameters");
        }
        return key;
    }

    /** SEC1's ECPrivateKey → PKCS#8, taking the curve from its parameters. */
    private static byte[] sec1ToPkcs8(byte[] sec1) {
        String curveOid = null;
        for (Der.Element part : Der.one(sec1).children()) {
            if (part.tag() == 0xa0) {
                curveOid = Der.one(part.content()).oid();
            }
        }
        if (curveOid == null) {
            throw HostException.invalid("The EC private key doesn't name its curve");
        }
        return Der.sequence(Der.integer(0), Der.sequence(Der.oid(EC), Der.oid(curve(curveOid).oid)), Der.octets(sec1));
    }

    private static Decoded jwk(JsonObject jwk) {
        String kty = jwk.getString("kty", "");
        try {
            switch (kty) {
                case "RSA" -> {
                    BigInteger n = number(jwk, "n");
                    BigInteger e = number(jwk, "e");
                    KeyFactory factory = KeyFactory.getInstance("RSA");
                    if (jwk.containsKey("d")) {
                        return new Decoded(checked(factory.generatePrivate(new RSAPrivateCrtKeySpec(n, e, number(jwk, "d"), number(jwk, "p"),
                                number(jwk, "q"), number(jwk, "dp"), number(jwk, "dq"), number(jwk, "qi")))), null);
                    }
                    return new Decoded(null, checked(factory.generatePublic(new RSAPublicKeySpec(n, e))));
                }
                case "EC" -> {
                    Curve curve = curve(jwk.getString("crv", ""));
                    ECParameterSpec params = params(curve);
                    KeyFactory factory = KeyFactory.getInstance("EC");
                    if (jwk.containsKey("d")) {
                        return new Decoded(factory.generatePrivate(new ECPrivateKeySpec(number(jwk, "d"), params)), null);
                    }
                    return new Decoded(null, factory.generatePublic(new ECPublicKeySpec(new ECPoint(number(jwk, "x"), number(jwk, "y")), params)));
                }
                case "OKP" -> {
                    if (!"Ed25519".equals(jwk.getString("crv"))) {
                        throw HostException.invalid("Unsupported OKP curve: " + jwk.getString("crv") + " (Ed25519 is supported)");
                    }
                    KeyFactory factory = KeyFactory.getInstance("Ed25519");
                    if (jwk.containsKey("d")) {
                        return new Decoded(factory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, base64url(jwk, "d"))), null);
                    }
                    return new Decoded(null, factory.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, edPoint(base64url(jwk, "x")))));
                }
                default -> throw HostException.invalid("Unsupported JWK key type: " + kty + " (RSA, EC and OKP are supported)");
            }
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Invalid JWK: " + e.getMessage());
        }
    }

    private static byte[] base64url(JsonObject jwk, String field) {
        String value = jwk.getString(field);
        if (value == null) {
            throw HostException.invalid("Invalid JWK: \"" + field + "\" is missing");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw HostException.invalid("Invalid JWK: \"" + field + "\" isn't base64url");
        }
    }

    private static BigInteger number(JsonObject jwk, String field) {
        return new BigInteger(1, base64url(jwk, field));
    }

    // --- info and writing ---

    static JsonObject info(java.security.Key key) {
        JsonObject details = new JsonObject();
        String type;
        byte[] der;
        if (key instanceof RSAPublicKey rsa) {
            type = "rsa";
            details.put("modulusLength", rsa.getModulus().bitLength()).put("publicExponent", rsa.getPublicExponent().longValue());
            der = rsa.getEncoded();
        } else if (key instanceof RSAPrivateCrtKey rsa) {
            type = "rsa";
            details.put("modulusLength", rsa.getModulus().bitLength()).put("publicExponent", rsa.getPublicExponent().longValue());
            der = rsa.getEncoded();
        } else if (key instanceof ECPublicKey ec) {
            type = "ec";
            details.put("namedCurve", curveOf(ec.getParams()).node);
            der = ec.getEncoded();
        } else if (key instanceof ECPrivateKey ec) {
            type = "ec";
            details.put("namedCurve", curveOf(ec.getParams()).node);
            der = ecPkcs8(ec);
        } else if (key instanceof EdECPublicKey ed) {
            type = "ed25519";
            der = ed.getEncoded();
        } else if (key instanceof EdECPrivateKey ed) {
            type = "ed25519";
            der = Der.sequence(Der.integer(0), Der.sequence(Der.oid(ED25519)), Der.octets(Der.octets(seed(ed))));
        } else {
            throw HostException.invalid("Unsupported key");
        }
        return new JsonObject().put("type", key instanceof PrivateKey ? "private" : "public").put("asymmetricKeyType", type)
                .put("der", Base64.getEncoder().encodeToString(der)).put("details", details);
    }

    private static byte[] seed(EdECPrivateKey key) {
        return key.getBytes().orElseThrow(() -> HostException.invalid("This Ed25519 key's bytes can't be read"));
    }

    /** PKCS#8 as OpenSSL (and so Node) writes an EC key: SEC1 inside, with the public key and without repeating the curve. */
    private static byte[] ecPkcs8(ECPrivateKey key) {
        Curve curve = curveOf(key.getParams());
        byte[] sec1 = Der.sequence(Der.integer(1), Der.octets(fixed(key.getS(), curve.bytes)),
                Der.explicit(1, Der.bits(point(curve, multiply(key.getParams(), key.getS())))));
        return Der.sequence(Der.integer(0), Der.sequence(Der.oid(EC), Der.oid(curve.oid)), Der.octets(sec1));
    }

    static Curve curveOf(ECParameterSpec params) {
        for (Curve curve : CURVES) {
            ECParameterSpec known = params(curve);
            if (known.getCurve().equals(params.getCurve()) && known.getGenerator().equals(params.getGenerator())) {
                return curve;
            }
        }
        throw HostException.invalid("Unsupported curve");
    }

    private static final Map<String, ECParameterSpec> PARAMS = new java.util.concurrent.ConcurrentHashMap<>();

    static ECParameterSpec params(Curve curve) {
        return PARAMS.computeIfAbsent(curve.jvm, name -> {
            try {
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec(name));
                return parameters.getParameterSpec(ECParameterSpec.class);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static byte[] fixed(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) {
            return bytes;
        }
        byte[] out = new byte[length];
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, bytes.length - copy, out, length - copy, copy);
        return out;
    }

    private static byte[] point(Curve curve, ECPoint p) {
        return Der.concat(new byte[] {4}, fixed(p.getAffineX(), curve.bytes), fixed(p.getAffineY(), curve.bytes));
    }

    static PublicKey publicOf(PrivateKey key) {
        try {
            if (key instanceof RSAPrivateCrtKey rsa) {
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
            }
            if (key instanceof ECPrivateKey ec) {
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(multiply(ec.getParams(), ec.getS()), ec.getParams()));
            }
            if (key instanceof EdECPrivateKey ed) {
                // The JVM derives an Ed25519 public key only while generating a pair, from random bytes it asks for: give it the seed.
                byte[] seed = seed(ed);
                KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
                generator.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
                    @Override
                    public void nextBytes(byte[] bytes) {
                        System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
                    }
                });
                return generator.generateKeyPair().getPublic();
            }
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Couldn't derive the public key: " + e.getMessage());
        }
        throw HostException.invalid("Unsupported key");
    }

    /** {@code k}·G on the curve, in affine coordinates. Only for a job's own key, to find its public half. */
    static ECPoint multiply(ECParameterSpec params, BigInteger k) {
        BigInteger p = ((java.security.spec.ECFieldFp) params.getCurve().getField()).getP();
        BigInteger a = params.getCurve().getA();
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = params.getGenerator();
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) {
                result = add(result, addend, a, p);
            }
            addend = add(addend, addend, a, p);
        }
        return result;
    }

    private static ECPoint add(ECPoint s, ECPoint t, BigInteger a, BigInteger p) {
        if (s.equals(ECPoint.POINT_INFINITY)) {
            return t;
        }
        if (t.equals(ECPoint.POINT_INFINITY)) {
            return s;
        }
        BigInteger x1 = s.getAffineX(), y1 = s.getAffineY(), x2 = t.getAffineX(), y2 = t.getAffineY();
        BigInteger lambda;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) {
                return ECPoint.POINT_INFINITY;
            }
            lambda = x1.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(y1.shiftLeft(1).modInverse(p)).mod(p);
        } else {
            lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p);
        }
        BigInteger x3 = lambda.pow(2).subtract(x1).subtract(x2).mod(p);
        BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new ECPoint(x3, y3);
    }

    /** An Ed25519 public key's 32 bytes (RFC 8032: little-endian y, the top bit x's parity) as the JVM's point. */
    private static EdECPoint edPoint(byte[] encoded) {
        if (encoded.length != 32) {
            throw HostException.invalid("Invalid JWK: an Ed25519 key is 32 bytes");
        }
        byte[] bigEndian = new byte[32];
        for (int i = 0; i < 32; i++) {
            bigEndian[i] = encoded[31 - i];
        }
        boolean xOdd = (bigEndian[0] & 0x80) != 0;
        bigEndian[0] &= 0x7f;
        return new EdECPoint(xOdd, new BigInteger(1, bigEndian));
    }

    private static byte[] edBytes(EdECPoint point) {
        byte[] y = fixed(point.getY(), 32);
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = y[31 - i];
        }
        if (point.isXOdd()) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return b64(bytes[0] == 0 && bytes.length > 1 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }

    /**
     * The key held as {@code der} ({@code isPrivate}: PKCS#8, else SPKI) in {@code format}: a PEM string, DER bytes or a
     * JWK object.
     */
    public static Object export(boolean isPrivate, byte[] der, String format, String type) {
        java.security.Key key = isPrivate ? privateKey(der) : publicKey(der);
        if ("jwk".equals(format)) {
            return jwkOf(key);
        }
        String kind = type == null ? (isPrivate ? "pkcs8" : "spki") : type;
        byte[] out;
        String label;
        if (isPrivate) {
            switch (kind) {
                case "pkcs8" -> {
                    out = der;
                    label = "PRIVATE KEY";
                }
                case "pkcs1" -> {
                    requireType(key, "rsa", "pkcs1");
                    out = Der.one(der).children().get(2).content();
                    label = "RSA PRIVATE KEY";
                }
                case "sec1" -> {
                    requireType(key, "ec", "sec1");
                    ECPrivateKey ec = (ECPrivateKey) key;
                    Curve curve = curveOf(ec.getParams());
                    out = Der.sequence(Der.integer(1), Der.octets(fixed(ec.getS(), curve.bytes)), Der.explicit(0, Der.oid(curve.oid)),
                            Der.explicit(1, Der.bits(point(curve, multiply(ec.getParams(), ec.getS())))));
                    label = "EC PRIVATE KEY";
                }
                default -> throw HostException.invalid("Unsupported private key encoding: " + kind);
            }
        } else {
            switch (kind) {
                case "spki" -> {
                    out = der;
                    label = "PUBLIC KEY";
                }
                case "pkcs1" -> {
                    requireType(key, "rsa", "pkcs1");
                    out = Der.one(der).children().get(1).bits();
                    label = "RSA PUBLIC KEY";
                }
                default -> throw HostException.invalid("Unsupported public key encoding: " + kind);
            }
        }
        if ("der".equals(format)) {
            return out;
        }
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(out);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private static void requireType(java.security.Key key, String wanted, String encoding) {
        String type = info(key).getString("asymmetricKeyType");
        if (!type.equals(wanted)) {
            throw HostException.invalid("The " + encoding + " encoding is only for " + wanted + " keys, not " + type);
        }
    }

    private static JsonObject jwkOf(java.security.Key key) {
        if (key instanceof RSAPrivateCrtKey rsa) {
            return new JsonObject().put("kty", "RSA").put("n", b64(rsa.getModulus())).put("e", b64(rsa.getPublicExponent()))
                    .put("d", b64(rsa.getPrivateExponent())).put("p", b64(rsa.getPrimeP())).put("q", b64(rsa.getPrimeQ()))
                    .put("dp", b64(rsa.getPrimeExponentP())).put("dq", b64(rsa.getPrimeExponentQ())).put("qi", b64(rsa.getCrtCoefficient()));
        }
        if (key instanceof RSAPublicKey rsa) {
            return new JsonObject().put("kty", "RSA").put("n", b64(rsa.getModulus())).put("e", b64(rsa.getPublicExponent()));
        }
        if (key instanceof ECPrivateKey ec) {
            Curve curve = curveOf(ec.getParams());
            ECPoint w = multiply(ec.getParams(), ec.getS());
            return new JsonObject().put("kty", "EC").put("x", b64(fixed(w.getAffineX(), curve.bytes))).put("y", b64(fixed(w.getAffineY(), curve.bytes)))
                    .put("crv", curve.jose).put("d", b64(fixed(ec.getS(), curve.bytes)));
        }
        if (key instanceof ECPublicKey ec) {
            Curve curve = curveOf(ec.getParams());
            return new JsonObject().put("kty", "EC").put("x", b64(fixed(ec.getW().getAffineX(), curve.bytes)))
                    .put("y", b64(fixed(ec.getW().getAffineY(), curve.bytes))).put("crv", curve.jose);
        }
        if (key instanceof EdECPrivateKey ed) {
            EdECPublicKey pub = (EdECPublicKey) publicOf(ed);
            return new JsonObject().put("crv", "Ed25519").put("d", b64(seed(ed))).put("x", b64(edBytes(pub.getPoint()))).put("kty", "OKP");
        }
        if (key instanceof EdECPublicKey ed) {
            return new JsonObject().put("crv", "Ed25519").put("x", b64(edBytes(ed.getPoint()))).put("kty", "OKP");
        }
        throw HostException.invalid("Unsupported key");
    }

    // --- generating ---

    /** A new pair: {@code {privateKey, publicKey}}, each as its info. */
    public static JsonObject generate(String type, JsonObject options) {
        try {
            KeyPair pair;
            switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
                case "rsa" -> {
                    int bits = options.getInteger("modulusLength", 0);
                    if (bits < 512 || bits > MAX_RSA_GENERATE_BITS) {
                        throw HostException.limit("modulusLength must be from 512 to " + MAX_RSA_GENERATE_BITS + " bits; got " + bits);
                    }
                    long exponent = options.getLong("publicExponent", 65537L);
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(new RSAKeyGenParameterSpec(bits, BigInteger.valueOf(exponent)));
                    pair = generator.generateKeyPair();
                }
                case "ec" -> {
                    Curve curve = curve(options.getString("namedCurve", ""));
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                    generator.initialize(new ECGenParameterSpec(curve.jvm));
                    pair = generator.generateKeyPair();
                }
                case "ed25519" -> pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                default -> throw HostException.invalid("Unsupported key type in the isolate: " + type + " (rsa, ec and ed25519 are supported)");
            }
            return new JsonObject().put("privateKey", info(pair.getPrivate())).put("publicKey", info(pair.getPublic()));
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Couldn't generate the key pair: " + e.getMessage());
        }
    }

    // --- signing ---

    /** Node's RSA padding constants. */
    static final int RSA_PKCS1_PADDING = 1;
    static final int RSA_PKCS1_PSS_PADDING = 6;
    static final int RSA_PSS_SALTLEN_DIGEST = -1;
    static final int RSA_PSS_SALTLEN_MAX = -2;

    /**
     * Signs {@code data} with the PKCS#8 key. {@code digest} may be null: Ed25519 takes none, and RSA and ECDSA then use
     * SHA-256. Options: {@code padding}, {@code saltLength}, {@code dsaEncoding} ("der" or "ieee-p1363").
     */
    public static byte[] sign(String digest, byte[] data, byte[] pkcs8, JsonObject options) {
        checkSize(data);
        PrivateKey key = privateKey(pkcs8);
        try {
            Signature signature = signature(digest, key, options, keyBytes(key), true);
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Signing failed: " + e.getMessage());
        }
    }

    /** Verifies with a key held as DER: SPKI, or PKCS#8 when {@code isPrivate} (its public half is used). */
    public static boolean verify(String digest, byte[] data, byte[] der, boolean isPrivate, byte[] signed, JsonObject options) {
        checkSize(data);
        PublicKey key = isPrivate ? publicOf(privateKey(der)) : publicKey(der);
        int keyBytes = keyBytes(key);
        // Node's default for verifying PSS is to accept any salt length; the JVM needs one, so the usual ones are tried.
        boolean anySalt = options.getInteger("padding", 0) == RSA_PKCS1_PSS_PADDING && options.getInteger("saltLength", RSA_PSS_SALTLEN_MAX) == RSA_PSS_SALTLEN_MAX;
        List<JsonObject> attempts = anySalt
                ? List.of(options.copy().put("saltLength", RSA_PSS_SALTLEN_DIGEST), options.copy().put("saltLength", RSA_PSS_SALTLEN_MAX), options.copy().put("saltLength", 0))
                : List.of(options);
        for (JsonObject attempt : attempts) {
            try {
                Signature signature = signature(digest, key, attempt, keyBytes, false);
                signature.initVerify(key);
                signature.update(data);
                if (signature.verify(signed)) {
                    return true;
                }
            } catch (java.security.SignatureException e) {
                // A malformed signature doesn't verify; Node answers false too.
            } catch (GeneralSecurityException e) {
                throw HostException.invalid("Verifying failed: " + e.getMessage());
            }
        }
        return false;
    }

    private static void checkSize(byte[] data) {
        if (data.length > MAX_SIGNED_BYTES) {
            throw HostException.limit("At most " + MAX_SIGNED_BYTES + " bytes can be signed or verified at once");
        }
    }

    private static int keyBytes(java.security.Key key) {
        if (key instanceof RSAPublicKey rsa) {
            return (rsa.getModulus().bitLength() + 7) / 8;
        }
        if (key instanceof RSAPrivateCrtKey rsa) {
            return (rsa.getModulus().bitLength() + 7) / 8;
        }
        return 0;
    }

    private static Signature signature(String digest, java.security.Key key, JsonObject options, int keyBytes, boolean signing)
            throws GeneralSecurityException {
        String type = info(key).getString("asymmetricKeyType");
        if (type.equals("ed25519")) {
            if (digest != null && !digest.isEmpty()) {
                throw HostException.invalid("Ed25519 signs without a digest: pass null as the algorithm");
            }
            return Signature.getInstance("Ed25519");
        }
        String hash = Hashes.canonical(digest == null || digest.isEmpty() ? "sha256" : digest);
        if (hash == null) {
            throw HostException.invalid("Invalid digest: " + digest);
        }
        String jvmHash = Hashes.jvmName(hash).replace("-", "").replace("/", "");
        if (type.equals("ec")) {
            String encoding = options.getString("dsaEncoding", "der");
            if (!encoding.equals("der") && !encoding.equals("ieee-p1363")) {
                throw HostException.invalid("dsaEncoding must be \"der\" or \"ieee-p1363\"");
            }
            return Signature.getInstance(jvmHash + "withECDSA" + (encoding.equals("ieee-p1363") ? "inP1363Format" : ""));
        }
        int padding = options.getInteger("padding", RSA_PKCS1_PADDING);
        if (padding == RSA_PKCS1_PADDING) {
            return Signature.getInstance(jvmHash + "withRSA");
        }
        if (padding != RSA_PKCS1_PSS_PADDING) {
            throw HostException.invalid("Unsupported RSA padding for signatures: " + padding);
        }
        int hashLength = Hashes.length(hash);
        int salt = options.getInteger("saltLength", signing ? RSA_PSS_SALTLEN_MAX : RSA_PSS_SALTLEN_DIGEST);
        if (salt == RSA_PSS_SALTLEN_DIGEST) {
            salt = hashLength;
        } else if (salt == RSA_PSS_SALTLEN_MAX) {
            salt = keyBytes - hashLength - 2;
        }
        if (salt < 0) {
            throw HostException.invalid("Invalid saltLength: " + salt);
        }
        String jdkHash = Hashes.jvmName(hash);
        Signature pss = Signature.getInstance("RSASSA-PSS");
        pss.setParameter(new PSSParameterSpec(jdkHash, "MGF1", new MGF1ParameterSpec(jdkHash), salt, 1));
        return pss;
    }
}
