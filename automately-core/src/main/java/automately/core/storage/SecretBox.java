package automately.core.storage;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts tenants' storage secrets at rest (D-057): AES-256-GCM with a key derived from
 * {@code AUTOMATELY_SECRETS_KEY}, a fresh 96-bit nonce per secret, and the tenant and provider id as associated data,
 * so a sealed secret can't be moved to another tenant's provider and still open.
 */
public final class SecretBox {

    public static final int MIN_KEY_LENGTH = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(String secretsKey) {
        if (secretsKey == null || secretsKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("AUTOMATELY_SECRETS_KEY must be at least " + MIN_KEY_LENGTH + " characters");
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(("automately-secrets:" + secretsKey).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Base64 of nonce then ciphertext; {@code context} must be given again to open it. */
    public String seal(String plaintext, String context) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("Can't seal a secret", e);
        }
    }

    /** Fails if the key, the context or the sealed text is wrong. */
    public String open(String sealed, String context) {
        try {
            byte[] all = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("A stored secret can't be opened: was AUTOMATELY_SECRETS_KEY changed?", e);
        }
    }
}
