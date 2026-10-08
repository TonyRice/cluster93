package automately.core.runtime.api;

/**
 * Cryptography for scripts (D-095): computation and secure randomness only, from the JVM's own implementations, so a
 * script reaches nothing new. Every call is bounded; state (an incremental hash) lives only as long as the execution.
 * Most calls are synchronous: each is cheap and capped. Key derivation and key generation, which aren't cheap, answer
 * futures: they run on a small pool of the node's own, so the costly ones can't pile up on every isolate's thread.
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

    // --- key derivation (D-101): the costly ones run on the node's crypto pool, for the sync forms too ---

    io.vertx.core.Future<byte[]> pbkdf2(byte[] password, byte[] salt, int iterations, int length, String digest);

    byte[] hkdf(String digest, byte[] key, byte[] salt, byte[] info, int length);

    /** Options as Node's: {@code N}/{@code cost}, {@code r}/{@code blockSize}, {@code p}/{@code parallelization}, {@code maxmem}. */
    io.vertx.core.Future<byte[]> scrypt(byte[] password, byte[] salt, int length, io.vertx.core.json.JsonObject options);

    // --- ciphers (D-101): open ones count toward MAX_OPEN with the hashes ---

    int cipher(String algorithm, boolean encrypt, byte[] key, byte[] iv, int authTagLength);

    void cipherPadding(int handle, boolean on);

    void cipherAad(int handle, byte[] aad);

    byte[] cipherUpdate(int handle, byte[] data);

    /** The rest of the output, closing the cipher. Authenticated encryption appends the tag; decryption takes it as {@code tag}. */
    byte[] cipherFinal(int handle, byte[] tag);

    java.util.List<String> ciphers();

    // --- keys and signatures (D-101): keys are passed as DER on every call and never kept ---

    /** A key's info ({@code type}, {@code asymmetricKeyType}, {@code der} as base64, {@code details}) from pem, der or jwk. */
    io.vertx.core.json.JsonObject readKey(boolean wantPrivate, byte[] data, String format, String type);

    io.vertx.core.json.JsonObject publicKeyOf(byte[] pkcs8);

    /** A PEM string, DER bytes or a JWK object. */
    Object exportKey(boolean isPrivate, byte[] der, String format, String type);

    io.vertx.core.Future<io.vertx.core.json.JsonObject> generateKeyPair(String type, io.vertx.core.json.JsonObject options);

    byte[] sign(String digest, byte[] data, byte[] pkcs8, io.vertx.core.json.JsonObject options);

    boolean verify(String digest, byte[] data, byte[] der, boolean isPrivate, byte[] signature, io.vertx.core.json.JsonObject options);

    java.util.List<String> curves();

    // --- RSA encryption and key agreement (D-104) ---

    /** publicEncrypt, privateDecrypt, privateEncrypt or publicDecrypt, with Node's padding options. */
    byte[] rsa(String operation, byte[] der, boolean isPrivate, byte[] data, io.vertx.core.json.JsonObject options);

    /** The shared secret of a private key (PKCS#8) and a public key (SPKI): ECDH or X25519. */
    byte[] agree(byte[] pkcs8, byte[] spki);

    /** createECDH's raw keys: {@code privateKey}, {@code publicKey} (uncompressed), base64; new when {@code privateValue} is null. */
    io.vertx.core.json.JsonObject ecdhKeys(String curve, byte[] privateValue);

    /** A public point in any SEC1 encoding, checked to be on the curve, uncompressed. */
    byte[] ecdhPoint(String curve, byte[] encoded);

    byte[] ecdhCompute(String curve, byte[] privateValue, byte[] otherPublic);
}
