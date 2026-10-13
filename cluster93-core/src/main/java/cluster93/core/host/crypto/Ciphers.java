package cluster93.core.host.crypto;

import cluster93.core.runtime.HostException;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Node's {@code createCipheriv}/{@code createDecipheriv} on the JVM's {@link Cipher} (D-101): AES in CBC, CTR and GCM
 * modes, and ChaCha20-Poly1305. One open cipher is per-execution state, like an open hash.
 *
 * <p>The JVM's cipher is made at the first {@code update} (or {@code final}), because Node lets padding be turned off
 * until then. Authenticated decryption collects its input and decrypts at {@code final}, once the tag is known: that's
 * when Node checks it too, and nothing unauthenticated is handed out before.
 */
public final class Ciphers {

    /** Most bytes an authenticated decryption collects before {@code final}. */
    public static final int MAX_AEAD_BYTES = 32 * 1024 * 1024;

    enum Mode { CBC, CTR, GCM, CHACHA20_POLY1305 }

    record Spec(Mode mode, int keyLength, int ivLength) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        for (int bits : new int[] {128, 192, 256}) {
            SPECS.put("aes-" + bits + "-cbc", new Spec(Mode.CBC, bits / 8, 16));
            SPECS.put("aes-" + bits + "-ctr", new Spec(Mode.CTR, bits / 8, 16));
            SPECS.put("aes-" + bits + "-gcm", new Spec(Mode.GCM, bits / 8, -1));
        }
        SPECS.put("aes128", new Spec(Mode.CBC, 16, 16));
        SPECS.put("aes192", new Spec(Mode.CBC, 24, 16));
        SPECS.put("aes256", new Spec(Mode.CBC, 32, 16));
        SPECS.put("chacha20-poly1305", new Spec(Mode.CHACHA20_POLY1305, 32, 12));
    }

    public static List<String> names() {
        return List.copyOf(SPECS.keySet());
    }

    /** One cipher being fed. */
    public static final class Open {
        private final Spec spec;
        private final boolean encrypt;
        private final byte[] key;
        private final byte[] iv;
        private final int tagLength;
        private boolean padding = true;
        private Cipher cipher;
        private ByteArrayOutputStream aad;
        private ByteArrayOutputStream collected;

        Open(Spec spec, boolean encrypt, byte[] key, byte[] iv, int tagLength) {
            this.spec = spec;
            this.encrypt = encrypt;
            this.key = key;
            this.iv = iv;
            this.tagLength = tagLength;
        }

        boolean aead() {
            return spec.mode == Mode.GCM || spec.mode == Mode.CHACHA20_POLY1305;
        }

        public void padding(boolean on) {
            if (cipher != null) {
                throw new HostException("Error", "ERR_CRYPTO_INVALID_STATE", "setAutoPadding must be called before update");
            }
            padding = on;
        }

        public void aad(byte[] data) {
            if (!aead()) {
                throw new HostException("Error", "ERR_CRYPTO_INVALID_STATE", "setAAD is only for authenticated ciphers");
            }
            if (cipher != null || (collected != null && collected.size() > 0)) {
                throw new HostException("Error", "ERR_CRYPTO_INVALID_STATE", "setAAD must be called before update");
            }
            if (aad == null) {
                aad = new ByteArrayOutputStream();
            }
            aad.writeBytes(data);
        }

        public byte[] update(byte[] data) {
            if (aead() && !encrypt) {
                if (collected == null) {
                    collected = new ByteArrayOutputStream();
                }
                if ((long) collected.size() + data.length > MAX_AEAD_BYTES) {
                    throw HostException.limit("At most " + MAX_AEAD_BYTES + " bytes can be decrypted with an authenticated cipher at once");
                }
                collected.writeBytes(data);
                return new byte[0];
            }
            byte[] out = started(null).update(data);
            return out == null ? new byte[0] : out;
        }

        /** The rest of the output; for an authenticated encryption, followed by the tag. */
        public byte[] finish(byte[] tag) {
            try {
                if (aead() && !encrypt) {
                    if (tag == null) {
                        throw new HostException("Error", "ERR_CRYPTO_INVALID_STATE", "Unsupported state or unable to authenticate data");
                    }
                    Cipher decrypting = started(tag);
                    byte[] body = collected == null ? new byte[0] : collected.toByteArray();
                    return decrypting.doFinal(Der.concat(body, tag));
                }
                return started(null).doFinal();
            } catch (AEADBadTagException e) {
                throw new HostException("Error", "ERR_CRYPTO_INVALID_AUTH_TAG", "Unsupported state or unable to authenticate data");
            } catch (BadPaddingException e) {
                throw new HostException("Error", "ERR_OSSL_BAD_DECRYPT", "bad decrypt");
            } catch (IllegalBlockSizeException e) {
                throw new HostException("Error", "ERR_OSSL_WRONG_FINAL_BLOCK_LENGTH", encrypt ? "data not multiple of block length" : "wrong final block length");
            }
        }

        private Cipher started(byte[] tag) {
            if (cipher != null) {
                return cipher;
            }
            try {
                int mode = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
                switch (spec.mode) {
                    case CBC -> {
                        cipher = Cipher.getInstance(padding ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
                        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    }
                    case CTR -> {
                        cipher = Cipher.getInstance("AES/CTR/NoPadding");
                        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    }
                    case GCM -> {
                        int bits = 8 * (tag != null ? tag.length : tagLength);
                        cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(bits, iv));
                    }
                    case CHACHA20_POLY1305 -> {
                        cipher = Cipher.getInstance("ChaCha20-Poly1305");
                        cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(iv));
                    }
                }
                if (aad != null) {
                    cipher.updateAAD(aad.toByteArray());
                }
                return cipher;
            } catch (GeneralSecurityException e) {
                throw HostException.invalid("The cipher couldn't start: " + e.getMessage());
            }
        }
    }

    public static Open open(String algorithm, boolean encrypt, byte[] key, byte[] iv, int authTagLength) {
        Spec spec = algorithm == null ? null : SPECS.get(algorithm.toLowerCase(Locale.ROOT));
        if (spec == null) {
            throw HostException.invalid("Invalid cipher: " + algorithm + " (the isolate has " + String.join(", ", SPECS.keySet()) + ")");
        }
        if (key.length != spec.keyLength) {
            throw new HostException("RangeError", "ERR_CRYPTO_INVALID_KEYLEN", "Invalid key length");
        }
        if (iv == null || (spec.ivLength > 0 ? iv.length != spec.ivLength : iv.length == 0 || iv.length > 1024)) {
            throw new HostException("TypeError", "ERR_CRYPTO_INVALID_IV", "Invalid initialization vector");
        }
        int tagLength = 16;
        if (authTagLength > 0) {
            boolean valid = spec.mode == Mode.GCM ? authTagLength >= 12 && authTagLength <= 16
                    : spec.mode == Mode.CHACHA20_POLY1305 && authTagLength == 16;
            if (!valid) {
                throw HostException.invalid("Invalid authentication tag length: " + authTagLength
                        + (spec.mode == Mode.GCM ? " (the isolate's GCM takes 12 to 16 bytes)" : " (the isolate's chacha20-poly1305 uses 16)"));
            }
            tagLength = authTagLength;
        }
        return new Open(spec, encrypt, key.clone(), iv.clone(), tagLength);
    }
}
