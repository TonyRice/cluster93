package automately.core.storage;

import automately.core.runtime.HostException;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantQuotaTest {

    private final AtomicLong stored = new AtomicLong();
    private final AtomicInteger counts = new AtomicInteger();
    private final TenantQuota.Usage usage = tenant -> {
        counts.incrementAndGet();
        return Future.succeededFuture(tenant.equals("acme") ? stored.get() : 0L);
    };

    @Test
    void remainingIsAlwaysAFreshCount() throws Exception {
        TenantQuota quota = new TenantQuota(1_000, usage);
        stored.set(300);
        assertThat(await(quota.remaining("acme"))).isEqualTo(700);
        stored.set(900);
        assertThat(await(quota.remaining("acme"))).isEqualTo(100);
        assertThat(await(quota.remaining("globex"))).isEqualTo(1_000);
        assertThat(counts).hasValue(3);
    }

    @Test
    void writesAreAdmittedFromARecentCountPlusWhatWasAdmittedSince() throws Exception {
        TenantQuota quota = new TenantQuota(1_000, usage, Duration.ofHours(1));
        stored.set(600);

        await(quota.ensureFits("acme", 300));
        await(quota.ensureFits("acme", 100));
        assertThatThrownBy(() -> await(quota.ensureFits("acme", 1)))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOf(HostException.class).hasMessageContaining("quota");
        assertThat(counts).as("counted once, then tracked").hasValue(1);
    }

    @Test
    void bytesStoredElsewhereOnThisNodeAreCounted() throws Exception {
        TenantQuota quota = new TenantQuota(1_000, usage, Duration.ofHours(1));
        stored.set(100);
        await(quota.ensureFits("acme", 100));
        quota.added("acme", 700);   // an upload finished

        assertThatThrownBy(() -> await(quota.ensureFits("acme", 101))).isInstanceOf(ExecutionException.class);
        await(quota.ensureFits("acme", 100));
    }

    @Test
    void aStaleCountIsRefreshed() throws Exception {
        TenantQuota quota = new TenantQuota(1_000, usage, Duration.ofMillis(50));
        stored.set(900);
        assertThatThrownBy(() -> await(quota.ensureFits("acme", 200))).isInstanceOf(ExecutionException.class);

        stored.set(100);   // the tenant deleted things
        Thread.sleep(80);
        await(quota.ensureFits("acme", 200));
        assertThat(counts).hasValue(2);
    }

    @Test
    void theErrorSaysQuota() {
        HostException error = TenantQuota.exceeded(40);
        assertThat(error.code()).isEqualTo("limit_exceeded");
        assertThat(TenantQuota.isExceeded(error)).isTrue();
        assertThat(TenantQuota.isExceeded(HostException.limit("Value is over 256 KB"))).isFalse();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
