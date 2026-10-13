package cluster93.core.host.crypto;

import cluster93.core.runtime.HostException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Key derivation (D-101): PBKDF2 and HKDF on the JVM's HMAC, and scrypt (RFC 7914), which the JVM doesn't have. PBKDF2
 * is written out too: the JVM's takes the password as characters, and Node's are bytes.
 */
public final class Kdf {

    /** Most PBKDF2 iterations (D-095). */
    public static final int MAX_PBKDF2_ITERATIONS = 1_000_000;
    /** Most HMAC blocks one PBKDF2 call computes (iterations × output blocks), so a long output can't multiply the cost. */
    public static final long MAX_PBKDF2_WORK = 4L * MAX_PBKDF2_ITERATIONS;
    /** Most bytes one derivation outputs. */
    public static final int MAX_LENGTH = 65_536;
    /** Most memory one scrypt uses (D-095): 128 × N × r bytes. */
    public static final long MAX_SCRYPT_MEMORY = 64L * 1024 * 1024;
    /** Most scrypt parallelization (each lane costs the full time again). */
    public static final int MAX_SCRYPT_PARALLELIZATION = 16;

    private Kdf() {
    }

    /** An HMAC keyed with {@code key}. */
    public static Mac hmac(String digest, byte[] key) {
        String jvm = "Hmac" + Hashes.jvmName(digest).replace("-", "").replace("/", "");
        try {
            Mac mac = Mac.getInstance(jvm);
            // An empty key is valid for HMAC, but SecretKeySpec refuses one; a single zero byte is equivalent (RFC 2104).
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, jvm));
            return mac;
        } catch (GeneralSecurityException e) {
            throw HostException.invalid("HMAC with " + digest + " isn't supported");
        }
    }

    private static void checkLength(int length) {
        if (length < 0 || length > MAX_LENGTH) {
            throw HostException.limit("At most " + MAX_LENGTH + " bytes can be derived at once; asked for " + length);
        }
    }

    public static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length, String digest) {
        checkLength(length);
        if (iterations < 1 || iterations > MAX_PBKDF2_ITERATIONS) {
            throw HostException.limit("PBKDF2 iterations must be from 1 to " + MAX_PBKDF2_ITERATIONS + "; got " + iterations);
        }
        Mac mac = hmac(digest, password);
        int hashLength = mac.getMacLength();
        int blocks = (length + hashLength - 1) / hashLength;
        if ((long) blocks * iterations > MAX_PBKDF2_WORK) {
            throw HostException.limit("This PBKDF2 would compute " + ((long) blocks * iterations) + " HMACs; the most is " + MAX_PBKDF2_WORK
                    + " (iterations × output blocks)");
        }
        return pbkdf2(mac, salt, iterations, length);
    }

    private static byte[] pbkdf2(Mac mac, byte[] salt, int iterations, int length) {
        int hashLength = mac.getMacLength();
        byte[] out = new byte[length];
        byte[] u = new byte[hashLength];
        byte[] t = new byte[hashLength];
        for (int block = 1, at = 0; at < length; block++, at += hashLength) {
            mac.update(salt);
            mac.update(new byte[] {(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            try {
                mac.doFinal(u, 0);
                System.arraycopy(u, 0, t, 0, hashLength);
                for (int i = 1; i < iterations; i++) {
                    mac.update(u);
                    mac.doFinal(u, 0);
                    for (int j = 0; j < hashLength; j++) {
                        t[j] ^= u[j];
                    }
                }
            } catch (javax.crypto.ShortBufferException e) {
                throw new IllegalStateException(e);
            }
            System.arraycopy(t, 0, out, at, Math.min(hashLength, length - at));
        }
        return out;
    }

    /** HKDF (RFC 5869): extract, then expand. */
    public static byte[] hkdf(String digest, byte[] key, byte[] salt, byte[] info, int length) {
        if (info.length > 1024) {
            throw HostException.limit("The HKDF info must be at most 1024 bytes");
        }
        Mac extract = hmac(digest, salt.length == 0 ? new byte[Hashes.length(digest)] : salt);
        byte[] prk = extract.doFinal(key);
        Mac expand = hmac(digest, prk);
        int hashLength = expand.getMacLength();
        if (length < 0 || length > 255 * hashLength) {
            throw new HostException("RangeError", "ERR_CRYPTO_INVALID_KEYLEN", "Invalid key length: at most " + (255 * hashLength) + " for " + digest);
        }
        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        for (int i = 1, at = 0; at < length; i++, at += hashLength) {
            expand.update(previous);
            expand.update(info);
            expand.update((byte) i);
            previous = expand.doFinal();
            System.arraycopy(previous, 0, out, at, Math.min(hashLength, length - at));
        }
        return out;
    }

    /**
     * scrypt (RFC 7914). {@code maxmem} is the caller's own limit (Node's default is 32 MiB); the node's cap applies
     * whatever it says.
     */
    public static byte[] scrypt(byte[] password, byte[] salt, int length, long n, int r, int p, long maxmem) {
        checkLength(length);
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new HostException("RangeError", "ERR_CRYPTO_INVALID_SCRYPT_PARAMS", "Invalid scrypt params: N must be a power of 2 greater than 1");
        }
        if (r < 1 || p < 1 || p > MAX_SCRYPT_PARALLELIZATION) {
            throw new HostException("RangeError", "ERR_CRYPTO_INVALID_SCRYPT_PARAMS",
                    "Invalid scrypt params: r must be at least 1 and p from 1 to " + MAX_SCRYPT_PARALLELIZATION);
        }
        long memory = 128L * n * r;
        long allowed = Math.min(maxmem, MAX_SCRYPT_MEMORY);
        if (memory > allowed || n > Integer.MAX_VALUE / 2 || (long) r * 128 * p > Integer.MAX_VALUE / 2) {
            throw new HostException("RangeError", "ERR_CRYPTO_INVALID_SCRYPT_PARAMS", "Invalid scrypt params: needs " + memory
                    + " bytes of memory, more than " + allowed + (allowed == MAX_SCRYPT_MEMORY ? " (the node's limit)" : " (maxmem)"));
        }
        Mac mac = hmac("sha256", password);
        int blockBytes = 128 * r;
        byte[] b = pbkdf2(mac, salt, 1, p * blockBytes);
        int[] x = new int[32 * r];
        int[] v = new int[(int) (32 * r * n)];
        int[] scratch = new int[32 * r];
        for (int lane = 0; lane < p; lane++) {
            int offset = lane * blockBytes;
            for (int i = 0; i < 32 * r; i++) {
                int at = offset + i * 4;
                x[i] = (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
            }
            roMix(x, v, scratch, r, (int) n);
            for (int i = 0; i < 32 * r; i++) {
                int at = offset + i * 4;
                b[at] = (byte) x[i];
                b[at + 1] = (byte) (x[i] >>> 8);
                b[at + 2] = (byte) (x[i] >>> 16);
                b[at + 3] = (byte) (x[i] >>> 24);
            }
        }
        return pbkdf2(mac, b, 1, length);
    }

    private static void roMix(int[] x, int[] v, int[] scratch, int r, int n) {
        int words = 32 * r;
        for (int i = 0; i < n; i++) {
            System.arraycopy(x, 0, v, i * words, words);
            blockMix(x, scratch, r);
        }
        for (int i = 0; i < n; i++) {
            int j = x[(2 * r - 1) * 16] & (n - 1);
            for (int k = 0; k < words; k++) {
                x[k] ^= v[j * words + k];
            }
            blockMix(x, scratch, r);
        }
    }

    private static void blockMix(int[] b, int[] y, int r) {
        int[] x = new int[16];
        System.arraycopy(b, (2 * r - 1) * 16, x, 0, 16);
        for (int i = 0; i < 2 * r; i++) {
            for (int k = 0; k < 16; k++) {
                x[k] ^= b[i * 16 + k];
            }
            salsa20_8(x);
            // Even blocks go to the first half, odd to the second.
            System.arraycopy(x, 0, y, ((i & 1) * r + (i >> 1)) * 16, 16);
        }
        System.arraycopy(y, 0, b, 0, 32 * r);
    }

    private static void salsa20_8(int[] b) {
        int x0 = b[0], x1 = b[1], x2 = b[2], x3 = b[3], x4 = b[4], x5 = b[5], x6 = b[6], x7 = b[7];
        int x8 = b[8], x9 = b[9], x10 = b[10], x11 = b[11], x12 = b[12], x13 = b[13], x14 = b[14], x15 = b[15];
        for (int i = 0; i < 8; i += 2) {
            x4 ^= Integer.rotateLeft(x0 + x12, 7); x8 ^= Integer.rotateLeft(x4 + x0, 9);
            x12 ^= Integer.rotateLeft(x8 + x4, 13); x0 ^= Integer.rotateLeft(x12 + x8, 18);
            x9 ^= Integer.rotateLeft(x5 + x1, 7); x13 ^= Integer.rotateLeft(x9 + x5, 9);
            x1 ^= Integer.rotateLeft(x13 + x9, 13); x5 ^= Integer.rotateLeft(x1 + x13, 18);
            x14 ^= Integer.rotateLeft(x10 + x6, 7); x2 ^= Integer.rotateLeft(x14 + x10, 9);
            x6 ^= Integer.rotateLeft(x2 + x14, 13); x10 ^= Integer.rotateLeft(x6 + x2, 18);
            x3 ^= Integer.rotateLeft(x15 + x11, 7); x7 ^= Integer.rotateLeft(x3 + x15, 9);
            x11 ^= Integer.rotateLeft(x7 + x3, 13); x15 ^= Integer.rotateLeft(x11 + x7, 18);
            x1 ^= Integer.rotateLeft(x0 + x3, 7); x2 ^= Integer.rotateLeft(x1 + x0, 9);
            x3 ^= Integer.rotateLeft(x2 + x1, 13); x0 ^= Integer.rotateLeft(x3 + x2, 18);
            x6 ^= Integer.rotateLeft(x5 + x4, 7); x7 ^= Integer.rotateLeft(x6 + x5, 9);
            x4 ^= Integer.rotateLeft(x7 + x6, 13); x5 ^= Integer.rotateLeft(x4 + x7, 18);
            x11 ^= Integer.rotateLeft(x10 + x9, 7); x8 ^= Integer.rotateLeft(x11 + x10, 9);
            x9 ^= Integer.rotateLeft(x8 + x11, 13); x10 ^= Integer.rotateLeft(x9 + x8, 18);
            x12 ^= Integer.rotateLeft(x15 + x14, 7); x13 ^= Integer.rotateLeft(x12 + x15, 9);
            x14 ^= Integer.rotateLeft(x13 + x12, 13); x15 ^= Integer.rotateLeft(x14 + x13, 18);
        }
        b[0] += x0; b[1] += x1; b[2] += x2; b[3] += x3; b[4] += x4; b[5] += x5; b[6] += x6; b[7] += x7;
        b[8] += x8; b[9] += x9; b[10] += x10; b[11] += x11; b[12] += x12; b[13] += x13; b[14] += x14; b[15] += x15;
    }
}
