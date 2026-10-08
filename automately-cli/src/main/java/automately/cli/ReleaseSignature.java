package automately.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks a release's signature (D-069): an ECDSA P-256 signature over SHA256SUMS, in the DER form
 * {@code openssl dgst -sha256 -sign} writes, against the release key built into the CLI.
 */
final class ReleaseSignature {

    private ReleaseSignature() {
    }

    /** {@code AUTOMATELY_RELEASE_PUBLIC_KEY} (a PEM file, for a mirror that re-signs), else the built-in key. */
    static PublicKey key(Io io) {
        String override = io.env().get("AUTOMATELY_RELEASE_PUBLIC_KEY");
        try {
            if (override != null && !override.isBlank()) {
                return parse(Files.readString(Path.of(override)));
            }
            try (InputStream in = ReleaseSignature.class.getResourceAsStream("/automately-release.pem")) {
                if (in == null) {
                    throw new CliException("This build has no release key, so it can't check releases");
                }
                return parse(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new CliException("Can't read the release key: " + e.getMessage());
        }
    }

    static PublicKey parse(String pem) throws GeneralSecurityException {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    /** Whether {@code signature} is the key's signature over {@code data}. */
    static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature check = Signature.getInstance("SHA256withECDSA");
            check.initVerify(key);
            check.update(data);
            return check.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
