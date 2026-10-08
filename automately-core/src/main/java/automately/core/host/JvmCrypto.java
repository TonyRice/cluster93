package automately.core.host;

import automately.core.host.crypto.Ciphers;
import automately.core.host.crypto.Hashes;
import automately.core.host.crypto.Kdf;
import automately.core.host.crypto.Keys;
import automately.core.runtime.HostException;
import automately.core.runtime.api.CryptoApi;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonObject;

import javax.crypto.Mac;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * {@link CryptoApi} on the JVM's providers (D-095, D-097, D-101). One per execution; its open hashes and ciphers are
 * dropped with it, so nothing is shared between jobs.
 */
public final class JvmCrypto implements CryptoApi {

    /** One generator for the node: SecureRandom is thread-safe, and seeding is the costly part. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** How many threads the node's crypto pool has: half the cores, at least 2. */
    public static final int POOL_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    /** The pool's name: a Vert.x shared worker pool, one per node. */
    public static final String POOL_NAME = "automately-crypto";

    /**
     * Where costly crypto (PBKDF2, scrypt, key generation) runs: the node's small pool, so it bounds the CPU and memory
     * those take (an scrypt may use 64 MB) however many jobs ask at once.
     */
    public interface Pool {
        <T> Future<T> run(Callable<T> work);

        /** The node's pool. */
        static Pool of(WorkerExecutor executor) {
            return new Pool() {
                @Override
                public <T> Future<T> run(Callable<T> work) {
                    // Completed from the pool's thread, not through the caller's context: a ...Sync call blocks the
                    // isolate's thread waiting for this, so an answer queued to that context would never be delivered.
                    Promise<T> promise = Promise.promise();
                    executor.executeBlocking(() -> {
                        try {
                            promise.complete(work.call());
                        } catch (Throwable e) {
                            promise.fail(e);
                        }
                        return null;
                    }, false);
                    return promise.future();
                }
            };
        }

        /** On the calling thread: for tests and hosts without a node. */
        static Pool inline() {
            return new Pool() {
                @Override
                public <T> Future<T> run(Callable<T> work) {
                    try {
                        return Future.succeededFuture(work.call());
                    } catch (Exception e) {
                        return Future.failedFuture(e);
                    }
                }
            };
        }
    }

    private final Pool pool;

    /** Costly calls run inline. */
    public JvmCrypto() {
        this(Pool.inline());
    }

    public JvmCrypto(Pool pool) {
        this.pool = pool;
    }

    /** An open hash: a digest, or a MAC for an HMAC. */
    private record Hash(MessageDigest digest, Mac mac) {

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

        Hash copy() throws CloneNotSupportedException {
            return digest != null ? new Hash((MessageDigest) digest.clone(), null) : new Hash(null, (Mac) mac.clone());
        }
    }

    private final Map<Integer, Hash> hashes = new HashMap<>();
    private final Map<Integer, Ciphers.Open> ciphers = new HashMap<>();
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
        return Hashes.digest(algorithm).digest(data);
    }

    private void checkRoom() {
        if (hashes.size() + ciphers.size() >= MAX_OPEN) {
            throw HostException.limit("At most " + MAX_OPEN + " hashes and ciphers can be open at once; finish some with digest() or final()");
        }
    }

    @Override
    public synchronized int open(String algorithm, byte[] hmacKey) {
        checkRoom();
        Hash hash = hmacKey == null ? new Hash(Hashes.digest(algorithm), null) : new Hash(null, Kdf.hmac(algorithm, hmacKey));
        int handle = next++;
        hashes.put(handle, hash);
        return handle;
    }

    @Override
    public synchronized void update(int handle, byte[] data) {
        hash(handle).update(data);
    }

    @Override
    public synchronized byte[] finish(int handle) {
        Hash hash = hash(handle);
        hashes.remove(handle);
        return hash.finish();
    }

    @Override
    public synchronized int copy(int handle) {
        checkRoom();
        try {
            int copy = next++;
            hashes.put(copy, hash(handle).copy());
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
        return Hashes.names();
    }

    private Hash hash(int handle) {
        Hash hash = hashes.get(handle);
        if (hash == null) {
            throw new HostException("Error", "invalid_state", "Digest already called");
        }
        return hash;
    }

    // --- key derivation ---

    @Override
    public Future<byte[]> pbkdf2(byte[] password, byte[] salt, int iterations, int length, String digest) {
        return pool.run(() -> Kdf.pbkdf2(password, salt, iterations, length, digest));
    }

    @Override
    public byte[] hkdf(String digest, byte[] key, byte[] salt, byte[] info, int length) {
        return Kdf.hkdf(digest, key, salt, info, length);
    }

    @Override
    public Future<byte[]> scrypt(byte[] password, byte[] salt, int length, JsonObject options) {
        long n = options.getLong("N", options.getLong("cost", 16384L));
        int r = options.getInteger("r", options.getInteger("blockSize", 8));
        int p = options.getInteger("p", options.getInteger("parallelization", 1));
        long maxmem = options.getLong("maxmem", 32L * 1024 * 1024);
        return pool.run(() -> Kdf.scrypt(password, salt, length, n, r, p, maxmem));
    }

    // --- ciphers ---

    @Override
    public synchronized int cipher(String algorithm, boolean encrypt, byte[] key, byte[] iv, int authTagLength) {
        checkRoom();
        Ciphers.Open open = Ciphers.open(algorithm, encrypt, key, iv, authTagLength);
        int handle = next++;
        ciphers.put(handle, open);
        return handle;
    }

    private Ciphers.Open cipher(int handle) {
        Ciphers.Open open = ciphers.get(handle);
        if (open == null) {
            throw new HostException("Error", "ERR_CRYPTO_INVALID_STATE", "Unsupported state: final() was already called");
        }
        return open;
    }

    @Override
    public synchronized void cipherPadding(int handle, boolean on) {
        cipher(handle).padding(on);
    }

    @Override
    public synchronized void cipherAad(int handle, byte[] aad) {
        cipher(handle).aad(aad);
    }

    @Override
    public synchronized byte[] cipherUpdate(int handle, byte[] data) {
        return cipher(handle).update(data);
    }

    @Override
    public synchronized byte[] cipherFinal(int handle, byte[] tag) {
        Ciphers.Open open = cipher(handle);
        ciphers.remove(handle);
        return open.finish(tag);
    }

    @Override
    public List<String> ciphers() {
        return Ciphers.names();
    }

    // --- keys and signatures ---

    @Override
    public JsonObject readKey(boolean wantPrivate, byte[] data, String format, String type) {
        return Keys.read(wantPrivate, data, format, type);
    }

    @Override
    public JsonObject publicKeyOf(byte[] pkcs8) {
        return Keys.publicFromPrivate(pkcs8);
    }

    @Override
    public Object exportKey(boolean isPrivate, byte[] der, String format, String type) {
        return Keys.export(isPrivate, der, format, type);
    }

    @Override
    public Future<JsonObject> generateKeyPair(String type, JsonObject options) {
        return pool.run(() -> Keys.generate(type, options));
    }

    @Override
    public byte[] sign(String digest, byte[] data, byte[] pkcs8, JsonObject options) {
        return Keys.sign(digest, data, pkcs8, options);
    }

    @Override
    public boolean verify(String digest, byte[] data, byte[] der, boolean isPrivate, byte[] signature, JsonObject options) {
        return Keys.verify(digest, data, der, isPrivate, signature, options);
    }

    @Override
    public List<String> curves() {
        return Keys.curves();
    }
}
