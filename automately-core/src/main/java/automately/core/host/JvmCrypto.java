package automately.core.host;

import automately.core.runtime.HostException;
import automately.core.runtime.api.CryptoApi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link CryptoApi} on the JVM's providers (D-095): {@link SecureRandom}, {@link MessageDigest} and {@link Mac}. One per
 * execution; its open hashes are dropped with it, so nothing is shared between jobs.
 */
public final class JvmCrypto implements CryptoApi {

    /** One generator for the node: SecureRandom is thread-safe, and seeding is the costly part. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Node's names → the JVM's. */
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

    /** Node's spellings that mean the same algorithm. */
    private static final Map<String, String> ALIASES = Map.of("sha-1", "sha1", "sha-224", "sha224", "sha-256", "sha256", "sha-384", "sha384",
            "sha-512", "sha512", "rsa-sha256", "sha256", "rsa-sha1", "sha1", "rsa-sha512", "sha512");

    /** An open hash: a digest, or a MAC for an HMAC. */
    private record Open(MessageDigest digest, Mac mac) {

        void update(byte[] data) {
            if (digest != null) {
                digest.update(data);
            } else {
                mac.update(data);
            }
        }

        byte[] finish() {
            return digest != null ? digest.digest() : mac.doFinal();
        }

        Open copy() throws CloneNotSupportedException {
            return digest != null ? new Open((MessageDigest) digest.clone(), null) : new Open(null, (Mac) mac.clone());
        }
    }

    private final Map<Integer, Open> open = new HashMap<>();
    private int next = 1;

    @Override
    public byte[] randomBytes(int length) {
        if (length < 0 || length > MAX_RANDOM_BYTES) {
            throw HostException.limit("At most " + MAX_RANDOM_BYTES + " random bytes at a time; asked for " + length);
        }
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    @Override
    public String randomUUID() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (bytes[i] & 0xff);
            low = (low << 8) | (bytes[i + 8] & 0xff);
        }
        return new UUID(high, low).toString();
    }

    @Override
    public byte[] digest(String algorithm, byte[] data) {
        return messageDigest(algorithm).digest(data);
    }

    @Override
    public synchronized int open(String algorithm, byte[] hmacKey) {
        if (open.size() >= MAX_OPEN) {
            throw HostException.limit("At most " + MAX_OPEN + " hashes can be open at once; finish some with digest()");
        }
        Open hash;
        if (hmacKey == null) {
            hash = new Open(messageDigest(algorithm), null);
        } else {
            String jvm = "Hmac" + jvmName(algorithm).replace("-", "").replace("/", "");
            try {
                Mac mac = Mac.getInstance(jvm);
                // An empty key is valid for HMAC, but SecretKeySpec refuses one; a single zero byte is equivalent (RFC 2104 pads keys with zeros).
                mac.init(new SecretKeySpec(hmacKey.length == 0 ? new byte[1] : hmacKey, jvm));
                hash = new Open(null, mac);
            } catch (GeneralSecurityException e) {
                throw HostException.invalid("HMAC with " + algorithm + " isn't supported");
            }
        }
        int handle = next++;
        open.put(handle, hash);
        return handle;
    }

    @Override
    public synchronized void update(int handle, byte[] data) {
        hash(handle).update(data);
    }

    @Override
    public synchronized byte[] finish(int handle) {
        Open hash = hash(handle);
        open.remove(handle);
        return hash.finish();
    }

    @Override
    public synchronized int copy(int handle) {
        if (open.size() >= MAX_OPEN) {
            throw HostException.limit("At most " + MAX_OPEN + " hashes can be open at once");
        }
        try {
            int copy = next++;
            open.put(copy, hash(handle).copy());
            return copy;
        } catch (CloneNotSupportedException e) {
            throw HostException.invalid("This hash can't be copied");
        }
    }

    @Override
    public boolean timingSafeEqual(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new HostException("RangeError", "invalid_argument", "Input buffers must have the same byte length");
        }
        return MessageDigest.isEqual(a, b);
    }

    @Override
    public List<String> hashes() {
        return List.copyOf(DIGESTS.keySet());
    }

    private Open hash(int handle) {
        Open hash = open.get(handle);
        if (hash == null) {
            throw new HostException("Error", "invalid_state", "Digest already called");
        }
        return hash;
    }

    private static String jvmName(String algorithm) {
        String name = algorithm == null ? "" : algorithm.toLowerCase(java.util.Locale.ROOT);
        name = ALIASES.getOrDefault(name, name);
        String jvm = DIGESTS.get(name);
        if (jvm == null) {
            throw HostException.invalid("Digest method not supported: " + algorithm);
        }
        return jvm;
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(jvmName(algorithm));
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("Digest method not supported: " + algorithm);
        }
    }
}
