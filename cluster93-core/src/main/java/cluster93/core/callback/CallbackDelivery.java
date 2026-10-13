package cluster93.core.callback;

import cluster93.core.host.FetchClient;
import cluster93.core.job.Job;
import cluster93.core.job.JobStore;
import cluster93.core.queue.QueueConsumer;
import cluster93.core.queue.QueueService;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers completion callbacks (D-062). Every node consumes the system's callback queue, so a delivery survives the
 * node that queued it. Each delivery reads the job as it is now, signs the body with the tenant's secret, and POSTs it
 * through the egress policy (D-026). A 2xx answer is delivered; anything else is retried by the queue with backoff, up to
 * {@link Callbacks#MAX_DELIVERIES}. An address the policy refuses isn't retried. Each attempt is recorded on the job.
 */
public final class CallbackDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackDelivery.class);
    static final int CONCURRENCY = 8;

    private final QueueService queues;
    private final JobStore jobs;
    private final FetchClient http;
    private final CallbackSecrets secrets;

    public CallbackDelivery(QueueService queues, JobStore jobs, FetchClient http, CallbackSecrets secrets) {
        this.queues = queues;
        this.jobs = jobs;
        this.http = http;
        this.secrets = secrets;
    }

    public Future<QueueConsumer> start() {
        return QueueConsumer.start(queues, QueueService.SYSTEM_TENANT, Callbacks.QUEUE, CONCURRENCY, this::deliver);
    }

    /** The body a receiver gets. */
    static JsonObject body(Job job) {
        JsonObject callback = job.callback();
        return new JsonObject().put("jobId", job.id()).put("status", job.status().name()).put("output", job.output()).put("error", job.error())
                .put("attempt", job.attempt()).put("finishedAt", job.finishedAt())
                .put("service", job.service() == null ? null : job.service().name())
                .put("data", callback.getValue("data"));
    }

    private void deliver(QueueConsumer.Delivery delivery) {
        String jobId = ((JsonObject) delivery.message().body()).getString("jobId");
        int attempt = delivery.message().attempt();
        jobs.get(jobId).compose(job -> {
            if (job == null || job.callback() == null) {
                delivery.ack();
                return Future.succeededFuture();
            }
            String body = body(job).encode();
            return secrets.secret(job.tenant()).compose(secret -> {
                JsonObject headers = new JsonObject()
                        .put("content-type", "application/json")
                        .put("user-agent", "Cluster93-Callback/2")
                        .put(Callbacks.SIGNATURE_HEADER.toLowerCase(), Callbacks.signature(secret, System.currentTimeMillis() / 1000, body))
                        .put("cluster93-delivery", delivery.message().id())
                        .put("cluster93-attempt", String.valueOf(attempt));
                JsonObject request = new JsonObject().put("url", job.callback().getString("url")).put("method", "POST").put("headers", headers)
                        .put("body", body).put("timeoutMs", Callbacks.TIMEOUT_MS);
                return http.fetch(job.tenant(), request, null, () -> { }).map(response -> {
                    response.body().cancel();
                    return response.meta().getInteger("status");
                });
            }).transform(ar -> {
                if (ar.succeeded() && ar.result() >= 200 && ar.result() < 300) {
                    cluster93.core.metrics.Metrics.of(queues.vertx()).callbackDelivery("delivered");
                    delivery.ack();
                    return record(job, "DELIVERED", attempt, null, System.currentTimeMillis());
                }
                String error = ar.succeeded() ? "HTTP " + ar.result() : String.valueOf(ar.cause().getMessage());
                boolean permanent = ar.failed() && ar.cause() instanceof HostException host
                        && (host.code().equals("egress_denied") || host.code().equals("invalid_argument"));
                boolean last = permanent || attempt >= delivery.message().maxAttempts();
                LOG.info("Callback of job {} attempt {} failed: {}{}", job.id(), attempt, error, last ? " (giving up)" : "");
                cluster93.core.metrics.Metrics.of(queues.vertx()).callbackDelivery(last ? "failed" : "retry");
                if (permanent) {
                    delivery.ack();
                } else {
                    delivery.fail(error);
                }
                return record(job, last ? "FAILED" : "PENDING", attempt, error, null);
            });
        }).onFailure(err -> {
            LOG.warn("Couldn't deliver the callback of job {}: {}", jobId, err.getMessage());
            delivery.fail(String.valueOf(err.getMessage()));
        });
    }

    private Future<Void> record(Job job, String status, int attempt, String error, Long deliveredAt) {
        return jobs.update(job.id(), current -> current.callbackDelivery(status, attempt, error, deliveredAt)).mapEmpty();
    }
}
