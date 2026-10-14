package cluster93.core.host;

import cluster93.core.runtime.api.CryptoApi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Known answers from the standards, and the caps that keep crypto from being a way to exhaust a node (D-095). */
class JvmCryptoTest {

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void digestsMatchTheirStandardTestVectors() {
        JvmCrypto crypto = new JvmCrypto();
        // FIPS 180-4 and RFC 1321 examples for "abc".
        assertThat(hex(crypto.digest("sha256", utf8("abc")))).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(hex(crypto.digest("SHA-1", utf8("abc")))).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
        assertThat(hex(crypto.digest("md5", utf8("abc")))).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        assertThat(hex(crypto.digest("sha3-256", utf8("abc")))).isEqualTo("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532");
        assertThatThrownBy(() -> crypto.digest("whirlpool", utf8("x"))).hasMessageContaining("Digest method not supported");
    }

    @Test
    void hmacMatchesRfc4231AndHashesCanBeFedInPieces() {
        JvmCrypto crypto = new JvmCrypto();
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        int hmac = crypto.open("sha256", utf8("Jefe"));
        crypto.update(hmac, utf8("what do ya "));
        crypto.update(hmac, utf8("want for nothing?"));
        assertThat(hex(crypto.finish(hmac))).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");

        int hash = crypto.open("sha256", null);
        crypto.update(hash, utf8("ab"));
        int copy = crypto.copy(hash);
        crypto.update(hash, utf8("c"));
        assertThat(hex(crypto.finish(hash))).isEqualTo(hex(crypto.digest("sha256", utf8("abc"))));
        assertThat(hex(crypto.finish(copy))).isEqualTo(hex(crypto.digest("sha256", utf8("ab"))));
        assertThatThrownBy(() -> crypto.finish(hash)).hasMessageContaining("Digest already called");
        assertThat(hex(crypto.finish(crypto.open("sha256", new byte[0])))).as("an empty HMAC key is valid")
                .isEqualTo("b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad");
    }

    @Test
    void randomnessIsCappedPerCallAndUuidsAreVersion4() {
        JvmCrypto crypto = new JvmCrypto();
        assertThat(crypto.randomBytes(CryptoApi.MAX_RANDOM_BYTES)).hasSize(CryptoApi.MAX_RANDOM_BYTES);
        assertThatThrownBy(() -> crypto.randomBytes(CryptoApi.MAX_RANDOM_BYTES + 1)).hasMessageContaining("At most 65536");
        assertThatThrownBy(() -> crypto.randomBytes(-1)).hasMessageContaining("At most");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String uuid = crypto.randomUUID();
            assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
            seen.add(uuid);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void anExecutionCanHoldOnlySoManyOpenHashes() {
        JvmCrypto crypto = new JvmCrypto();
        for (int i = 0; i < CryptoApi.MAX_OPEN; i++) {
            crypto.open("sha256", null);
        }
        assertThatThrownBy(() -> crypto.open("sha256", null)).hasMessageContaining("At most 1000 hashes");
        assertThat(new JvmCrypto().open("sha256", null)).as("each execution has its own").isEqualTo(1);
    }

    @Test
    void timingSafeEqualNeedsEqualLengths() {
        JvmCrypto crypto = new JvmCrypto();
        assertThat(crypto.timingSafeEqual(utf8("same"), utf8("same"))).isTrue();
        assertThat(crypto.timingSafeEqual(utf8("same"), utf8("diff"))).isFalse();
        assertThatThrownBy(() -> crypto.timingSafeEqual(utf8("a"), utf8("ab"))).hasMessageContaining("same byte length");
    }
}
