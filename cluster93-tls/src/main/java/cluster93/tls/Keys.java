package cluster93.tls;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** EC P-256 keys: supported everywhere TLS is (JSSE, Ignite, Vert.x). */
final class Keys {

    private Keys() {
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether {@code privateKey} belongs to {@code publicKey}: signs a nonce and verifies it. */
    static boolean matches(PrivateKey privateKey, PublicKey publicKey) {
        try {
            byte[] nonce = new byte[32];
            new java.security.SecureRandom().nextBytes(nonce);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(nonce);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(nonce);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
