package automately.core.auth;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenFormatTest {

    @Test
    void generatedTokensHaveThePrefixAndAValidChecksum() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = TokenFormat.generate(random);
            assertThat(token).startsWith("atly_").hasSize(5 + 43 + 6).matches("atly_[0-9A-Za-z]+");
            assertThat(TokenFormat.looksValid(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void aTypoOrAMadeUpStringFailsTheChecksum() {
        String token = TokenFormat.generate(new SecureRandom());
        char last = token.charAt(10);
        String typo = token.substring(0, 10) + (last == 'a' ? 'b' : 'a') + token.substring(11);

        assertThat(TokenFormat.looksValid(typo)).isFalse();
        assertThat(TokenFormat.looksValid("atly_" + "x".repeat(49))).isFalse();
        assertThat(TokenFormat.looksValid(token.substring(1))).isFalse();
        assertThat(TokenFormat.looksValid(null)).isFalse();
    }

    @Test
    void hashesAreStableHexAndComparedSafely() {
        assertThat(TokenFormat.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(TokenFormat.sameHash(TokenFormat.hash("abc"), TokenFormat.hash("abc"))).isTrue();
        assertThat(TokenFormat.sameHash(TokenFormat.hash("abc"), TokenFormat.hash("abd"))).isFalse();
        assertThat(TokenFormat.sameHash(null, "x")).isFalse();
    }
}
