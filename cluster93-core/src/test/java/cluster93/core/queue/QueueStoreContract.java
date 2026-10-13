package cluster93.core.queue;

import cluster93.core.files.StorageCapacity;
import cluster93.core.runtime.HostException;
import cluster93.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The queue rules (D-061) over a {@link QueueStore}: each store's test extends this. Uses a short lease so expiry can be
 * shown without waiting 30 s.
 */
public abstract class QueueStoreContract {

    static final long LEASE_MS = 300;

    /** Set by the store's test before each test. */
    protected QueueStore store;
    protected Vertx vertx;

    private QueueService service() {
        return service(TenantQuota.unlimited());
    }

    private QueueService service(TenantQuota quota) {
        return new QueueService(vertx, store, quota, StorageCapacity.UNLIMITED, LEASE_MS);
    }

    @Test
    public void aPushedMessageIsClaimedOnceAndAckRemovesIt() throws Exception {
        QueueService queues = service();
        String id = await(queues.push("acme", "emails", new JsonObject().put("to", "a@b.c"), null, null, null));

        QueueMessage claimed = await(queues.claim("acme", "emails", "consumer-1")).orElseThrow();
        assertThat(claimed.id()).isEqualTo(id);
        assertThat(claimed.body()).isEqualTo(new JsonObject().put("to", "a@b.c"));
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(await(queues.claim("acme", "emails", "consumer-2"))).as("in flight, so nobody else gets it").isEmpty();
        assertThat(await(queues.stats("acme", "emails"))).isEqualTo(counts(0, 1, 0));

        assertThat(await(queues.ack(claimed))).isTrue();
        assertThat(await(queues.stats("acme", "emails"))).isEqualTo(counts(0, 0, 0));
    }

    @Test
    public void racingConsumersEachGetDifferentMessages() throws Exception {
        QueueService queues = service();
        for (int i = 0; i < 20; i++) {
            await(queues.push("acme", "work", i, null, null, null));
        }
        List<Future<Optional<QueueMessage>>> claims = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            claims.add(queues.claim("acme", "work", "consumer-" + i));
        }
        await(Future.join(claims));
        List<Object> bodies = new ArrayList<>();
        for (Future<Optional<QueueMessage>> claim : claims) {
            if (claim.succeeded() && claim.result().isPresent()) {
                bodies.add(claim.result().get().body());
            }
        }
        assertThat(bodies).doesNotHaveDuplicates();
        assertThat(bodies.size() + await(queues.stats("acme", "work")).getLong("ready")).isEqualTo(20);
    }

    @Test
    public void aFailureRetriesLaterAndTheLastOneDeadLetters() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "body", null, null, 2));

        QueueMessage first = await(queues.claim("acme", "q", "c")).orElseThrow();
        assertThat(await(queues.fail(first, "boom", false))).isTrue();
        assertThat(await(queues.claim("acme", "q", "c"))).as("backing off").isEmpty();
        assertThat(await(queues.stats("acme", "q"))).isEqualTo(counts(1, 0, 0));

        Thread.sleep(QueueService.backoff(1) + 100);
        QueueMessage second = await(queues.claim("acme", "q", "c")).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.lastError()).isEqualTo("boom");
        await(queues.fail(second, "boom again", false));

        assertThat(await(queues.stats("acme", "q"))).isEqualTo(counts(0, 0, 1));
        JsonObject dead = await(queues.dead("acme", "q", 10, null));
        assertThat(dead.getJsonArray("messages")).hasSize(1);
        assertThat(dead.getJsonArray("messages").getJsonObject(0).getString("lastError")).isEqualTo("boom again");
    }

    @Test
    public void anExpiredLeaseIsTakenBackAndTheLateAckDoesNothing() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "body", null, null, null));
        QueueMessage lost = await(queues.claim("acme", "q", "dies")).orElseThrow();

        Thread.sleep(LEASE_MS + 100);
        QueueMessage again = await(queues.claim("acme", "q", "survivor")).orElseThrow();

        assertThat(again.attempt()).isEqualTo(2);
        assertThat(await(queues.ack(lost))).as("the first consumer no longer owns it").isFalse();
        assertThat(await(queues.ack(again))).isTrue();
    }

    @Test
    public void extendingTheLeaseKeepsTheMessage() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "body", null, null, null));
        QueueMessage claimed = await(queues.claim("acme", "q", "slow")).orElseThrow();

        for (int i = 0; i < 4; i++) {
            Thread.sleep(LEASE_MS / 2);
            assertThat(await(queues.extend(claimed))).isTrue();
        }
        assertThat(await(queues.claim("acme", "q", "other"))).isEmpty();
    }

    @Test
    public void aLeaseRunningOutOnTheLastAttemptDeadLetters() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "body", null, null, 1));
        await(queues.claim("acme", "q", "dies")).orElseThrow();

        Thread.sleep(LEASE_MS + 100);

        assertThat(await(queues.claim("acme", "q", "survivor"))).isEmpty();
        assertThat(await(queues.stats("acme", "q"))).isEqualTo(counts(0, 0, 1));
    }

    @Test
    public void releasingDoesNotCountAsAnAttempt() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "body", null, null, 1));
        QueueMessage claimed = await(queues.claim("acme", "q", "stopping")).orElseThrow();

        await(queues.fail(claimed, null, true));
        QueueMessage again = await(queues.claim("acme", "q", "next")).orElseThrow();

        assertThat(again.attempt()).isEqualTo(1);
    }

    @Test
    public void deadLettersCanBeRetriedOrDiscarded() throws Exception {
        QueueService queues = service();
        String a = await(queues.push("acme", "q", "a", null, null, 1));
        String b = await(queues.push("acme", "q", "b", null, null, 1));
        for (int i = 0; i < 2; i++) {
            await(queues.fail(await(queues.claim("acme", "q", "c")).orElseThrow(), "no", false));
        }
        assertThat(await(queues.stats("acme", "q"))).isEqualTo(counts(0, 0, 2));
        JsonObject page = await(queues.dead("acme", "q", 1, null));
        assertThat(page.getJsonArray("messages")).hasSize(1);
        assertThat(page.getString("cursor")).isNotNull();
        assertThat(await(queues.dead("acme", "q", 1, page.getString("cursor"))).getJsonArray("messages")).hasSize(1);

        assertThat(await(queues.retry("acme", "q", a))).isTrue();
        assertThat(await(queues.discard("acme", "q", b))).isTrue();
        assertThat(await(queues.retry("acme", "q", "nope"))).isFalse();

        assertThat(await(queues.stats("acme", "q"))).isEqualTo(counts(1, 0, 0));
        assertThat(await(queues.claim("acme", "q", "c")).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    public void aKeyMakesThePushIdempotent() throws Exception {
        QueueService queues = service();
        String first = await(queues.push("acme", "q", "once", null, "order-7", null));
        String second = await(queues.push("acme", "q", "twice", null, "order-7", null));

        assertThat(second).isEqualTo(first);
        assertThat(await(queues.stats("acme", "q"))).isEqualTo(counts(1, 0, 0));
        assertThat(await(queues.claim("acme", "q", "c")).orElseThrow().body()).isEqualTo("once");
    }

    @Test
    public void aDelayedMessageWaits() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "later", 400L, null, null));

        assertThat(await(queues.claim("acme", "q", "c"))).isEmpty();
        Thread.sleep(500);
        assertThat(await(queues.claim("acme", "q", "c"))).isPresent();
    }

    @Test
    public void queuesAreScopedToTheirTenant() throws Exception {
        QueueService queues = service();
        await(queues.push("acme", "q", "acme's", null, null, null));

        assertThat(await(queues.claim("globex", "q", "c"))).isEmpty();
        assertThat(await(queues.stats("globex", "q"))).isEqualTo(counts(0, 0, 0));
        assertThat(await(queues.claim("acme", "q", "c"))).isPresent();
    }

    @Test
    public void queuedBytesCountTowardTheTenantsQuota() throws Exception {
        TenantQuota quota = new TenantQuota(2_000, store::usage);
        QueueService queues = service(quota);
        await(queues.push("acme", "q", "x".repeat(1_000), null, null, null));

        assertThat(await(store.usage("acme"))).isGreaterThan(1_000);
        assertThatThrownBy(() -> await(queues.push("acme", "q", "x".repeat(1_000), null, null, null)))
                .isInstanceOf(ExecutionException.class).cause().satisfies(e -> assertThat(TenantQuota.isExceeded(e)).isTrue());
    }

    @Test
    public void argumentsAreValidated() {
        QueueService queues = service();
        assertInvalid(queues.push("acme", "has space", 1, null, null, null));
        assertInvalid(queues.push("acme", "q", 1, -1L, null, null));
        assertInvalid(queues.push("acme", "q", 1, null, null, 0));
        assertInvalid(queues.push("acme", "q", 1, null, "bad key", null));
        assertThatThrownBy(() -> await(queues.push("acme", "q", "x".repeat(QueueService.MAX_MESSAGE_BYTES), null, null, null)))
                .cause().isInstanceOf(HostException.class).hasMessageContaining("bytes");
    }

    private static void assertInvalid(Future<?> future) {
        assertThatThrownBy(() -> await(future)).cause().isInstanceOf(HostException.class)
                .satisfies(e -> assertThat(((HostException) e).code()).isEqualTo("invalid_argument"));
    }

    static JsonObject counts(long ready, long inFlight, long dead) {
        return new JsonObject().put("ready", ready).put("inFlight", inFlight).put("dead", dead);
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
