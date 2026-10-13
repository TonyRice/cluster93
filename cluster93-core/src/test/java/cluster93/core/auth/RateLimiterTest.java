package cluster93.core.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aTenantGetsAMinutesWorthThenWaits() {
        RateLimiter limiter = new RateLimiter(60);
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire("acme", 0).allowed()).as("request " + i).isTrue();
        }
        RateLimiter.Decision refused = limiter.acquire("acme", 0);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);
        assertThat(limiter.acquire("globex", 0).allowed()).as("tenants are separate").isTrue();
        assertThat(limiter.acquire("acme", SECOND).allowed()).as("one per second refills").isTrue();
        assertThat(limiter.acquire("acme", SECOND).allowed()).isFalse();
    }

    @Test
    void eachNodeEnforcesItsShare() {
        RateLimiter limiter = new RateLimiter(60);
        limiter.nodes(3);
        int allowed = 0;
        for (int i = 0; i < 60; i++) {
            if (limiter.acquire("acme", 0).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(20);
    }

    @Test
    void theDecisionCarriesTheHeaders() {
        RateLimiter limiter = new RateLimiter(120);
        RateLimiter.Decision first = limiter.acquire("acme", 0);

        assertThat(first.limit()).isEqualTo(120);
        assertThat(first.remaining()).isEqualTo(119);
        assertThat(first.resetSeconds()).isEqualTo(1);
        assertThat(new RateLimiter(0).enabled()).isFalse();
    }
}
