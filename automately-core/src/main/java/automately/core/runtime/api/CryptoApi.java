package automately.core.runtime.api;

/**
 * Cryptography for scripts (D-095): computation and secure randomness only, from the JVM's own implementations, so a
 * script reaches nothing new. Every call is bounded; state (an incremental hash) lives only as long as the execution.
 * Calls are synchronous: each is cheap and capped. Key derivation and key generation, which aren't cheap, come with
 * their own off-thread calls.
 */
public interface CryptoApi {

    /** Most random bytes one call returns: Web Crypto's own limit for {@code getRandomValues}. */
    int MAX_RANDOM_BYTES = 65_536;
    /** Most incremental hashes or HMACs one execution can have open at once. */
    int MAX_OPEN = 1_000;

    /** {@code length} bytes from a cryptographically secure generator. */
    byte[] randomBytes(int length);

    /** A random (version 4) UUID, from the same generator. */
    String randomUUID();

    /** The digest of {@code data}: md5, sha1, sha224, sha256, sha384, sha512, sha512-224, sha512-256, sha3-224 to sha3-512. */
    byte[] digest(String algorithm, byte[] data);

    /** Starts an incremental hash, or an HMAC when {@code hmacKey} isn't null; answers its handle. */
    int open(String algorithm, byte[] hmacKey);

    /** Adds bytes to an open hash. */
    void update(int handle, byte[] data);

    /** Finishes an open hash and closes it. */
    byte[] finish(int handle);

    /** A copy of an open hash, for Node's {@code hash.copy()}. */
    int copy(int handle);

    /** Whether two byte arrays are equal, in time that doesn't depend on where they differ. Lengths must match. */
    boolean timingSafeEqual(byte[] a, byte[] b);

    /** The hash algorithms {@link #digest} and {@link #open} accept, by Node's names. */
    java.util.List<String> hashes();
}
