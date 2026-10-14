package cluster93.core.packages;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemverTest {

    private static boolean matches(String range, String version) {
        return Semver.Range.parse(range).matches(Semver.Version.parse(version));
    }

    @Test
    void versionsParseStrictlyAndOrderAsSemverSays() {
        assertThat(Semver.Version.parse("1.4.0")).hasToString("1.4.0");
        assertThat(Semver.Version.parse("2.0.0-beta.1").pre()).isEqualTo("beta.1");
        for (String bad : new String[] {"1.4", "01.0.0", "v1.0.0", "1.0.0+build", "1.0.0-", "", "1.0.0.0"}) {
            assertThat(Semver.Version.parse(bad)).as(bad).isNull();
        }
        String[] ordered = {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
                "1.0.0-rc.1", "1.0.0", "1.2.0", "1.10.0", "2.0.0"};
        for (int i = 1; i < ordered.length; i++) {
            assertThat(Semver.Version.parse(ordered[i - 1])).as(ordered[i - 1] + " < " + ordered[i])
                    .isLessThan(Semver.Version.parse(ordered[i]));
        }
    }

    @Test
    void rangesMatchAsNpmReadsThem() {
        assertThat(matches("^1.2.3", "1.9.0")).isTrue();
        assertThat(matches("^1.2.3", "2.0.0")).isFalse();
        assertThat(matches("^1.2.3", "1.2.2")).isFalse();
        assertThat(matches("^0.2.3", "0.2.9")).isTrue();
        assertThat(matches("^0.2.3", "0.3.0")).isFalse();
        assertThat(matches("^0.0.3", "0.0.4")).isFalse();
        assertThat(matches("^1.2", "1.9.9")).isTrue();
        assertThat(matches("~1.2.3", "1.2.9")).isTrue();
        assertThat(matches("~1.2.3", "1.3.0")).isFalse();
        assertThat(matches("~1", "1.9.0")).isTrue();
        assertThat(matches("1.x", "1.5.0")).isTrue();
        assertThat(matches("1.2.*", "1.3.0")).isFalse();
        assertThat(matches("1", "1.0.1")).isTrue();
        assertThat(matches("*", "9.9.9")).isTrue();
        assertThat(matches("1.4.0", "1.4.0")).isTrue();
        assertThat(matches("=1.4.0", "1.4.1")).isFalse();
    }

    @Test
    void preReleasesMatchOnlyWhenNamedExactly() {
        assertThat(matches("^1.0.0", "1.1.0-beta.1")).isFalse();
        assertThat(matches("*", "1.0.0-rc.1")).isFalse();
        assertThat(matches("1.0.0-rc.1", "1.0.0-rc.1")).isTrue();
    }

    @Test
    void rangesItDoesntUnderstandAreRefused() {
        for (String bad : new String[] {">=1.0.0", "1.0.0 - 2.0.0", "^1.0.0 || ^2.0.0", "latest", "^1.0.0-beta", "=1.x", "file:../x"}) {
            assertThat(Semver.Range.parse(bad)).as(bad).isNull();
        }
    }
}
