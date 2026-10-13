package cluster93.core.host.crypto;

import cluster93.core.runtime.HostException;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Node's hash names and the JVM's (D-097). */
public final class Hashes {

    private static final Map<String, String> DIGESTS = new LinkedHashMap<>();

    static {
        DIGESTS.put("md5", "MD5");
        DIGESTS.put("sha1", "SHA-1");
        DIGESTS.put("sha224", "SHA-224");
        DIGESTS.put("sha256", "SHA-256");
        DIGESTS.put("sha384", "SHA-384");
        DIGESTS.put("sha512", "SHA-512");
        DIGESTS.put("sha512-224", "SHA-512/224");
        DIGESTS.put("sha512-256", "SHA-512/256");
        DIGESTS.put("sha3-224", "SHA3-224");
        DIGESTS.put("sha3-256", "SHA3-256");
        DIGESTS.put("sha3-384", "SHA3-384");
        DIGESTS.put("sha3-512", "SHA3-512");
    }

    private Hashes() {
    }

    /** Node's name for a hash in any of its spellings ("SHA-256", "RSA-SHA256", "ecdsa-with-SHA256"), or null if unknown. */
    public static String canonical(String algorithm) {
        if (algorithm == null) {
            return null;
        }
        String name = algorithm.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"rsa-", "ecdsa-with-"}) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
        }
        if (!DIGESTS.containsKey(name) && name.startsWith("sha-")) {
            name = "sha" + name.substring(4);
        }
        return DIGESTS.containsKey(name) ? name : null;
    }

    /** The JVM's name for a hash Node names {@code algorithm}. */
    public static String jvmName(String algorithm) {
        String name = canonical(algorithm);
        if (name == null) {
            throw HostException.invalid("Digest method not supported: " + algorithm);
        }
        return DIGESTS.get(name);
    }

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(jvmName(algorithm));
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Digest method not supported: " + algorithm);
        }
    }

    /** The hash's output length in bytes. */
    public static int length(String algorithm) {
        return digest(algorithm).getDigestLength();
    }

    public static List<String> names() {
        return List.copyOf(DIGESTS.keySet());
    }
}
