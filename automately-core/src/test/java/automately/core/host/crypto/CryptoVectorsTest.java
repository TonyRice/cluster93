package automately.core.host.crypto;

import automately.core.host.JvmCrypto;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Key derivation, ciphers and signatures against their standards' test vectors, and the caps (D-101). */
class CryptoVectorsTest {

    private static final HexFormat HEX = HexFormat.of();

    private static byte[] hex(String text) {
        return HEX.parseHex(text);
    }

    private static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] der(JsonObject info) {
        return Base64.getDecoder().decode(info.getString("der"));
    }

    @Test
    void pbkdf2MatchesRfc6070AndIsCapped() {
        assertThat(hex(Kdf.pbkdf2(utf8("password"), utf8("salt"), 1, 20, "sha1"))).isEqualTo("0c60c80f961f0e71f3a9b524af6012062fe037a6");
        assertThat(hex(Kdf.pbkdf2(utf8("password"), utf8("salt"), 4096, 20, "sha1"))).isEqualTo("4b007901b765489abead49d926f721d065a429c1");
        assertThat(hex(Kdf.pbkdf2(utf8("password"), utf8("salt"), 1, 32, "sha256")))
                .isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
        assertThatThrownBy(() -> Kdf.pbkdf2(utf8("p"), utf8("s"), Kdf.MAX_PBKDF2_ITERATIONS + 1, 32, "sha256")).hasMessageContaining("iterations");
        // A million iterations is fine for one block, not for a long output.
        assertThatThrownBy(() -> Kdf.pbkdf2(utf8("p"), utf8("s"), Kdf.MAX_PBKDF2_ITERATIONS, 32 * 5, "sha256")).hasMessageContaining("output blocks");
    }

    @Test
    void hkdfMatchesRfc5869() {
        byte[] okm = Kdf.hkdf("sha256", hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertThat(hex(okm)).isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        assertThatThrownBy(() -> Kdf.hkdf("sha256", new byte[1], new byte[0], new byte[0], 255 * 32 + 1)).hasMessageContaining("Invalid key length");
    }

    @Test
    void scryptMatchesRfc7914AndItsMemoryIsCapped() {
        assertThat(hex(Kdf.scrypt(new byte[0], new byte[0], 64, 16, 1, 1, Long.MAX_VALUE))).isEqualTo(
                "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906");
        assertThat(hex(Kdf.scrypt(utf8("password"), utf8("NaCl"), 64, 1024, 8, 16, Long.MAX_VALUE))).isEqualTo(
                "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640");
        // N = 2^20 with r = 8 needs 1 GiB.
        assertThatThrownBy(() -> Kdf.scrypt(utf8("p"), utf8("s"), 32, 1 << 20, 8, 1, Long.MAX_VALUE)).hasMessageContaining("the node's limit");
        assertThatThrownBy(() -> Kdf.scrypt(utf8("p"), utf8("s"), 32, 1 << 16, 8, 1, 32L * 1024 * 1024)).hasMessageContaining("maxmem");
        assertThatThrownBy(() -> Kdf.scrypt(utf8("p"), utf8("s"), 32, 1000, 8, 1, Long.MAX_VALUE)).hasMessageContaining("power of 2");
    }

    @Test
    void aesGcmMatchesTheSpecAndRefusesATamperedMessage() {
        JvmCrypto crypto = new JvmCrypto();
        byte[] key = hex("feffe9928665731c6d6a8f9467308308");
        byte[] iv = hex("cafebabefacedbaddecaf888");
        byte[] plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255");
        int enc = crypto.cipher("aes-128-gcm", true, key, iv, 0);
        byte[] out = Der.concat(crypto.cipherUpdate(enc, java.util.Arrays.copyOf(plain, 10)),
                crypto.cipherUpdate(enc, java.util.Arrays.copyOfRange(plain, 10, plain.length)), crypto.cipherFinal(enc, null));
        assertThat(hex(out)).isEqualTo("42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa05"
                + "1ba30b396a0aac973d58e091473f5985" + "4d5c2af327cd64a62cf35abd2ba6fab4");

        byte[] body = java.util.Arrays.copyOf(out, plain.length);
        byte[] tag = java.util.Arrays.copyOfRange(out, plain.length, out.length);
        int dec = crypto.cipher("aes-128-gcm", false, key, iv, 0);
        assertThat(crypto.cipherUpdate(dec, body)).as("nothing unauthenticated comes out before final").isEmpty();
        assertThat(crypto.cipherFinal(dec, tag)).isEqualTo(plain);

        body[0] ^= 1;
        int tampered = crypto.cipher("aes-128-gcm", false, key, iv, 0);
        crypto.cipherUpdate(tampered, body);
        assertThatThrownBy(() -> crypto.cipherFinal(tampered, tag)).hasMessageContaining("unable to authenticate data");
        assertThatThrownBy(() -> crypto.cipherFinal(tampered, tag)).as("final closes it").hasMessageContaining("final() was already called");
    }

    @Test
    void chacha20Poly1305MatchesRfc8439() {
        JvmCrypto crypto = new JvmCrypto();
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hex("070000004041424344454647");
        int enc = crypto.cipher("chacha20-poly1305", true, key, nonce, 16);
        crypto.cipherAad(enc, hex("50515253c0c1c2c3c4c5c6c7"));
        byte[] out = Der.concat(crypto.cipherUpdate(enc, utf8("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")),
                crypto.cipherFinal(enc, null));
        assertThat(hex(out)).startsWith("d31a8d34648e60db7b86afbc53ef7ec2").endsWith("1ae10b594f09e26a7e902ecbd0600691");
    }

    @Test
    void cbcPadsAndSaysBadDecryptForTheWrongKey() {
        JvmCrypto crypto = new JvmCrypto();
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        int enc = crypto.cipher("aes-256-cbc", true, key, iv, 0);
        byte[] out = Der.concat(crypto.cipherUpdate(enc, utf8("seventeen bytes!!")), crypto.cipherFinal(enc, null));
        assertThat(out).hasSize(32);
        int dec = crypto.cipher("aes-256-cbc", false, key, iv, 0);
        assertThat(new String(Der.concat(crypto.cipherUpdate(dec, out), crypto.cipherFinal(dec, null)), StandardCharsets.UTF_8)).isEqualTo("seventeen bytes!!");

        byte[] wrong = new byte[32];
        wrong[0] = 1;
        int bad = crypto.cipher("aes-256-cbc", false, wrong, iv, 0);
        crypto.cipherUpdate(bad, out);
        assertThatThrownBy(() -> crypto.cipherFinal(bad, null)).hasMessage("bad decrypt");
        assertThatThrownBy(() -> crypto.cipher("aes-256-cbc", true, new byte[16], iv, 0)).hasMessage("Invalid key length");
        assertThatThrownBy(() -> crypto.cipher("des-ede3-cbc", true, key, iv, 0)).hasMessageContaining("Invalid cipher");
    }

    @Test
    void ed25519MatchesRfc8032() {
        String seed = Base64.getUrlEncoder().withoutPadding().encodeToString(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"));
        JsonObject privateKey = Keys.read(true, utf8(new JsonObject().put("kty", "OKP").put("crv", "Ed25519").put("d", seed)
                .put("x", "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo").encode()), "jwk", null);
        assertThat(privateKey.getString("asymmetricKeyType")).isEqualTo("ed25519");
        byte[] signature = Keys.sign(null, new byte[0], der(privateKey), new JsonObject());
        assertThat(hex(signature)).isEqualTo("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

        JsonObject publicKey = Keys.publicFromPrivate(der(privateKey));
        JsonObject jwk = (JsonObject) Keys.export(false, der(publicKey), "jwk", null);
        assertThat(jwk.getString("x")).as("the derived public key is RFC 8032's").isEqualTo("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo");
        assertThat(Keys.verify(null, new byte[0], der(publicKey), false, signature, new JsonObject())).isTrue();
        assertThat(Keys.verify(null, new byte[] {1}, der(publicKey), false, signature, new JsonObject())).isFalse();
    }

    @Test
    void rsaKeysRoundTripThroughEveryFormatAndSign() {
        JsonObject pair = Keys.generate("rsa", new JsonObject().put("modulusLength", 2048));
        byte[] pkcs8 = der(pair.getJsonObject("privateKey"));
        byte[] spki = der(pair.getJsonObject("publicKey"));
        assertThat(pair.getJsonObject("publicKey").getJsonObject("details").getInteger("modulusLength")).isEqualTo(2048);

        String pkcs1 = (String) Keys.export(true, pkcs8, "pem", "pkcs1");
        assertThat(pkcs1).startsWith("-----BEGIN RSA PRIVATE KEY-----\n");
        assertThat(der(Keys.read(true, utf8(pkcs1), "pem", null))).isEqualTo(pkcs8);
        JsonObject jwk = (JsonObject) Keys.export(true, pkcs8, "jwk", null);
        assertThat(der(Keys.read(true, utf8(jwk.encode()), "jwk", null))).isEqualTo(pkcs8);
        assertThat(der(Keys.read(false, utf8((String) Keys.export(true, pkcs8, "pem", null)), "pem", null))).as("public from private").isEqualTo(spki);

        byte[] data = utf8("hello");
        byte[] pkcs1Signature = Keys.sign("RSA-SHA256", data, pkcs8, new JsonObject());
        assertThat(Keys.sign("sha256", data, pkcs8, new JsonObject())).as("PKCS#1 v1.5 is deterministic").isEqualTo(pkcs1Signature);
        assertThat(Keys.verify("sha256", data, spki, false, pkcs1Signature, new JsonObject())).isTrue();
        JsonObject pss = new JsonObject().put("padding", Keys.RSA_PKCS1_PSS_PADDING).put("saltLength", Keys.RSA_PSS_SALTLEN_DIGEST);
        byte[] pssSignature = Keys.sign("sha256", data, pkcs8, pss);
        assertThat(Keys.verify("sha256", data, spki, false, pssSignature, pss)).isTrue();
        assertThat(Keys.verify("sha256", data, spki, false, pssSignature, new JsonObject().put("padding", Keys.RSA_PKCS1_PSS_PADDING)))
                .as("any salt length, by default").isTrue();
        assertThat(Keys.verify("sha256", utf8("other"), pkcs8, true, pkcs1Signature, new JsonObject())).isFalse();

        assertThatThrownBy(() -> Keys.generate("rsa", new JsonObject().put("modulusLength", 8192))).hasMessageContaining("4096");
    }

    @Test
    void ecKeysFindTheirPublicHalfAndSignInBothEncodings() {
        for (String curve : new String[] {"P-256", "secp384r1", "prime256v1", "P-521"}) {
            JsonObject pair = Keys.generate("ec", new JsonObject().put("namedCurve", curve));
            byte[] pkcs8 = der(pair.getJsonObject("privateKey"));
            byte[] spki = der(pair.getJsonObject("publicKey"));
            assertThat(der(Keys.publicFromPrivate(pkcs8))).as(curve + ": k·G is the generated public key").isEqualTo(spki);
            String sec1 = (String) Keys.export(true, pkcs8, "pem", "sec1");
            assertThat(der(Keys.read(true, utf8(sec1), "pem", null))).isEqualTo(pkcs8);
            assertThat(der(Keys.read(true, utf8(((JsonObject) Keys.export(true, pkcs8, "jwk", null)).encode()), "jwk", null))).isEqualTo(pkcs8);

            byte[] data = utf8("hello");
            byte[] derSignature = Keys.sign("sha256", data, pkcs8, new JsonObject());
            assertThat(Keys.verify("sha256", data, spki, false, derSignature, new JsonObject())).isTrue();
            JsonObject p1363 = new JsonObject().put("dsaEncoding", "ieee-p1363");
            byte[] raw = Keys.sign("sha256", data, pkcs8, p1363);
            assertThat(raw).hasSize(2 * Keys.curve(curve).bytes());
            assertThat(Keys.verify("sha256", data, spki, false, raw, p1363)).isTrue();
        }
    }

    @Test
    void whatTheIsolateDoesntSupportSaysSo() {
        assertThatThrownBy(() -> Keys.generate("x25519", new JsonObject())).hasMessageContaining("rsa, ec and ed25519");
        assertThatThrownBy(() -> Keys.read(true, utf8("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAA==\n-----END ENCRYPTED PRIVATE KEY-----\n"), "pem", null))
                .hasMessageContaining("Encrypted private keys");
        assertThatThrownBy(() -> Keys.read(true, utf8("not a key"), "pem", null)).hasMessageContaining("no PEM block");
    }
}
