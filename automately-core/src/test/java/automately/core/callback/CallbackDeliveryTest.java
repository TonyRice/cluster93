package automately.core.callback;

import automately.core.files.StorageCapacity;
import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.SharedDataKvStore;
import automately.core.job.Job;
import automately.core.job.OrphanPolicy;
import automately.core.job.SharedDataJobStore;
import automately.core.queue.QueueService;
import automately.core.queue.SharedDataQueueStore;
import automately.core.storage.TenantQuota;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Which job endings queue a callback, and what delivery does with an address the egress policy refuses (D-062). */
class CallbackDeliveryTest {

    private Vertx vertx;
    private QueueService queues;
    private CallbackJobStore jobs;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        queues = new QueueService(vertx, new SharedDataQueueStore(vertx), TenantQuota.unlimited(), StorageCapacity.UNLIMITED);
        jobs = new CallbackJobStore(new SharedDataJobStore(vertx), queues);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private Job queuedWithCallback(String id, String url) throws Exception {
        Job job = Job.queued(id, "acme", "export default () => 1", new JsonObject(), 1000, OrphanPolicy.FAIL, 1, 0)
                .withCallback(url, new JsonObject().put("order", 7)).orElseThrow();
        await(jobs.save(job));
        return job;
    }

    private long queued() throws Exception {
        return await(queues.stats(QueueService.SYSTEM_TENANT, Callbacks.QUEUE)).getLong("ready");
    }

    @Test
    void everyWayAJobEndsQueuesOneDelivery() throws Exception {
        queuedWithCallback("rejected", "https://example.com/hook");
        queuedWithCallback("cancelled", "https://example.com/hook");
        queuedWithCallback("orphaned", "https://example.com/hook");
        await(jobs.save(Job.queued("no-callback", "acme", "s", new JsonObject(), 1000, OrphanPolicy.FAIL, 1, 0)));

        await(jobs.update("orphaned", job -> job.started(1, "node-a", "instance-a", 1)));
        assertThat(queued()).as("starting isn't ending").isZero();

        await(jobs.update("rejected", job -> job.rejected("no nodes", 2)));
        await(jobs.update("cancelled", job -> job.cancelRequested("changed my mind", 2)));
        await(jobs.update("orphaned", job -> job.orphaned(1, "instance-a", 3)));
        await(jobs.update("no-callback", job -> job.rejected("no nodes", 2)));
        assertThat(queued()).isEqualTo(3);

        // Recording a delivery is a change to an ended job, not an ending.
        await(jobs.update("rejected", job -> job.callbackDelivery("PENDING", 1, "HTTP 500", null)));
        assertThat(queued()).isEqualTo(3);
    }

    @Test
    void aPrivateAddressIsRefusedOnceAndNotRetried() throws Exception {
        queuedWithCallback("job-1", "http://169.254.169.254/latest/meta-data/");
        await(jobs.update("job-1", job -> job.rejected("no nodes", 2)));
        CallbackDelivery delivery = new CallbackDelivery(queues, jobs, new FetchClient(vertx, EgressPolicy.PUBLIC_ONLY),
                new CallbackSecrets(new SharedDataKvStore(vertx), null));
        await(delivery.start());

        long deadline = System.currentTimeMillis() + 10_000;
        JsonObject callback;
        do {
            Thread.sleep(50);
            callback = await(jobs.get("job-1")).callback();
        } while (!"FAILED".equals(callback.getString("status")) && System.currentTimeMillis() < deadline);

        assertThat(callback.getString("status")).isEqualTo("FAILED");
        assertThat(callback.getInteger("attempts")).isEqualTo(1);
        assertThat(callback.getString("lastError")).contains("private or reserved address");
        assertThat(await(queues.stats(QueueService.SYSTEM_TENANT, Callbacks.QUEUE)))
                .isEqualTo(new JsonObject().put("ready", 0L).put("inFlight", 0L).put("dead", 0L));
    }

    @Test
    void secretsAreCreatedOnceAndSealedWhenThereIsAKey() throws Exception {
        SharedDataKvStore kv = new SharedDataKvStore(vertx);
        CallbackSecrets sealed = new CallbackSecrets(kv, new automately.core.storage.SecretBox("k".repeat(40)));
        String first = await(sealed.secret("acme"));

        assertThat(await(sealed.secret("acme"))).isEqualTo(first);
        assertThat(await(sealed.secret("globex"))).isNotEqualTo(first);
        assertThat(((JsonObject) await(kv.get(QueueService.SYSTEM_TENANT, CallbackSecrets.NAMESPACE, "acme"))).getString("sealed"))
                .isNotNull().doesNotContain(first);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
