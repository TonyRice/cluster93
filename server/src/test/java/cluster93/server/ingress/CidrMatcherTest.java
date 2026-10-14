package cluster93.server.ingress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link CidrMatcher}: parsing and matching IPv4 and IPv6 CIDR blocks. */
class CidrMatcherTest {

    @Test
    @DisplayName("IPv4 blocks match the addresses inside them and nothing else")
    void ipv4Blocks() {
        CidrMatcher matcher = CidrMatcher.parse(List.of("127.0.0.1/32", "10.0.0.0/8", "192.168.0.0/16"));

        assertThat(matcher.matches("127.0.0.1")).isTrue();
        assertThat(matcher.matches("127.0.0.2")).isFalse();
        assertThat(matcher.matches("10.5.6.7")).isTrue();
        assertThat(matcher.matches("11.0.0.1")).isFalse();
        assertThat(matcher.matches("192.168.1.1")).isTrue();
        assertThat(matcher.matches("192.169.1.1")).isFalse();
    }

    @Test
    @DisplayName("IPv6 blocks match the addresses inside them and nothing else")
    void ipv6Blocks() {
        CidrMatcher matcher = CidrMatcher.parse(List.of("::1/128", "fd00::/8"));

        assertThat(matcher.matches("::1")).isTrue();
        assertThat(matcher.matches("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(matcher.matches("::2")).isFalse();
        assertThat(matcher.matches("fd00::abcd")).isTrue();
        assertThat(matcher.matches("fe80::1")).isFalse();
    }

    @Test
    @DisplayName("A bare address is a host route")
    void bareAddressIsAHostRoute() {
        CidrMatcher matcher = CidrMatcher.parse(List.of("127.0.0.1", "::1"));

        assertThat(matcher.matches("127.0.0.1")).isTrue();
        assertThat(matcher.matches("127.0.0.2")).isFalse();
        assertThat(matcher.matches("::1")).isTrue();
    }

    @Test
    @DisplayName("A /0 block matches every address of its family")
    void zeroPrefixMatchesEveryAddressOfItsFamily() {
        assertThat(CidrMatcher.parse(List.of("0.0.0.0/0")).matches("203.0.113.9")).isTrue();
        assertThat(CidrMatcher.parse(List.of("::/0")).matches("2001:db8::1")).isTrue();
        assertThat(CidrMatcher.parse(List.of("0.0.0.0/0")).matches("2001:db8::1")).isFalse();
    }

    @Test
    @DisplayName("An IPv4-mapped IPv6 client matches an IPv4 block")
    void ipv4MappedIpv6MatchesIpv4() {
        assertThat(CidrMatcher.parse(List.of("127.0.0.1/32")).matches("::ffff:127.0.0.1")).isTrue();
        assertThat(CidrMatcher.parse(List.of("10.0.0.0/8")).matches("::ffff:10.1.2.3")).isTrue();
    }

    @Test
    @DisplayName("An empty matcher imposes no restriction")
    void emptyMatcherAllowsEverything() {
        CidrMatcher matcher = CidrMatcher.parse(List.of());
        assertThat(matcher.isEmpty()).isTrue();
        assertThat(matcher.matches("203.0.113.9")).isTrue();
        assertThat(matcher.matches(null)).isTrue();
    }

    @Test
    @DisplayName("A non-empty matcher refuses a null or unparseable address")
    void nonEmptyMatcherRefusesUnknownAddresses() {
        CidrMatcher matcher = CidrMatcher.parse(List.of("10.0.0.0/8"));
        assertThat(matcher.matches(null)).isFalse();
        assertThat(matcher.matches("not-an-address")).isFalse();
    }

    @Test
    @DisplayName("A malformed CIDR is refused")
    void malformedCidrsAreRefused() {
        for (String cidr : List.of("10.0.0.0/33", "10.0.0.256/8", "10.0.0/8", "::1/129", "hello", "10.0.0.0/x")) {
            assertThatThrownBy(() -> CidrMatcher.parse(List.of(cidr)))
                    .as(cidr)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
