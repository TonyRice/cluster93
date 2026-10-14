package cluster93.core.files;

import cluster93.core.identity.SharedDataRecords;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Advisory locks (D-080): one holder at a time, expiry, renewal, and per-tenant names. */
class FileLocksTest {

    private Vertx vertx;
    private FileLocks locks;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        locks = new FileLocks(new SharedDataRecords(vertx, FileLocks.KIND));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void anExpiredLockCanBeTakenAndItsOldHolderCantRenewIt() throws Exception {
        JsonObject first = await(locks.acquire("acme", "jobs/index", Duration.ofMillis(50)));
        assertThatThrownBy(() -> await(locks.acquire("acme", "jobs/index", Duration.ofSeconds(5))))
                .isInstanceOf(ExecutionException.class).cause().isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("busy"));
        assertThat(await(locks.acquire("globex", "jobs/index", Duration.ofSeconds(5)))).as("another tenant's name").isNotNull();

        Thread.sleep(100);
        JsonObject second = await(locks.acquire("acme", "jobs/index", Duration.ofSeconds(5)));
        assertThat(second.getString("token")).isNotEqualTo(first.getString("token"));
        assertThatThrownBy(() -> await(locks.renew("acme", "jobs/index", first.getString("token"), Duration.ofSeconds(5))))
                .cause().isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("not_found"));
        assertThat(await(locks.release("acme", "jobs/index", first.getString("token")))).as("not the holder now").isFalse();
        assertThat(await(locks.renew("acme", "jobs/index", second.getString("token"), Duration.ofSeconds(10))).getLong("expiresAt"))
                .isGreaterThan(second.getLong("expiresAt"));
        assertThat(await(locks.release("acme", "jobs/index", second.getString("token")))).isTrue();
    }

    @Test
    void locksLastAnHourAtMost() {
        assertThatThrownBy(() -> await(locks.acquire("acme", "x", Duration.ofHours(2)))).cause()
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("invalid_argument"));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
