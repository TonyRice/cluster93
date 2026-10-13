package cluster93.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.Shareable;

import java.time.Duration;

/**
 * Cluster93's own metrics (phase 5), next to the ones Vert.x records for HTTP, the event bus and pools. Each node has
 * its own registry, kept in its Vert.x local map, so any component with the {@code Vertx} records without plumbing,
 * and several nodes in one JVM (the tests) don't mix. Labels stay low-cardinality: no job ids, and tenants only where a
 * tenant is the point (who is being rate limited).
 */
public final class Metrics implements Shareable {

    private static final String KEY = "cluster93.metrics";
    /** Records nothing: for code running without a node (unit tests). */
    private static final Metrics NONE = new Metrics(new CompositeMeterRegistry());

    private final MeterRegistry registry;

    private Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static void install(Vertx vertx, MeterRegistry registry) {
        vertx.sharedData().<String, Metrics>getLocalMap(KEY).put("registry", new Metrics(registry));
    }

    public static Metrics of(Vertx vertx) {
        Metrics metrics = vertx == null ? null : vertx.sharedData().<String, Metrics>getLocalMap(KEY).get("registry");
        return metrics != null ? metrics : NONE;
    }

    public MeterRegistry registry() {
        return registry;
    }

    // --- jobs and isolates ---

    public void jobSubmitted(boolean service) {
        Counter.builder("cluster93.jobs.submitted").description("Jobs submitted (service runs included)")
                .tag("kind", service ? "service" : "job").register(registry).increment();
    }

    /** From queued to a node starting it. */
    public void jobStarted(Duration queued) {
        Timer.builder("cluster93.jobs.queue.wait").description("Time from submission to a node starting the job")
                .publishPercentileHistogram().register(registry).record(queued);
    }

    public void jobFinished(String status, Duration ran) {
        Timer.builder("cluster93.jobs.duration").description("How long attempts ran, by how they ended")
                .tag("status", status).publishPercentileHistogram().register(registry).record(ran);
    }

    /** Creating a V8 isolate until the script's module starts. */
    public void isolateStarted(Duration took) {
        Timer.builder("cluster93.isolates.start").description("Cold start: creating an isolate and loading the script")
                .publishPercentileHistogram().register(registry).record(took);
    }

    /** A package file looked up in the node's cache before a job runs (D-092): served from memory, or fetched. */
    public void packageFile(boolean cached) {
        Counter.builder("cluster93.packages.files").description("Package files a job needed, served from the node's cache or fetched")
                .tag("source", cached ? "cache" : "fetched").register(registry).increment();
    }

    /** Fetching and checking a job's packages before its isolate is made (D-089). */
    public void packagesLoaded(boolean ok, Duration took) {
        Timer.builder("cluster93.packages.load").description("Fetching and checking a job's packages before it runs")
                .tag("outcome", ok ? "ok" : "failed").publishPercentileHistogram().register(registry).record(took);
    }

    public void serviceRunStarted(boolean restart) {
        Counter.builder("cluster93.services.runs").description("Service runs started; restarts after a crash or node loss are tagged")
                .tag("restart", String.valueOf(restart)).register(registry).increment();
    }

    // --- the API and storage ---

    /** A script's connection refused by the egress gate (D-108), by tenant and reason. */
    public void egressDenied(String tenant, String reason) {
        Counter.builder("cluster93.egress.denied").description("Script connections refused by the egress gate")
                .tag("tenant", tenant).tag("reason", reason).register(registry).increment();
    }

    /** Bytes a script's socket sent or received (D-116); {@code direction} is {@code out} or {@code in}. */
    public void egressBytes(String tenant, String direction, long bytes) {
        Counter.builder("cluster93.egress.bytes").description("Bytes scripts' sockets sent and received").baseUnit("bytes")
                .tag("tenant", tenant).tag("direction", direction).register(registry).increment(bytes);
    }

    /** A socket a script opened (D-116). */
    public void socketOpened(String tenant, boolean tls) {
        Counter.builder("cluster93.egress.sockets").description("Sockets scripts opened").tag("tenant", tenant)
                .tag("tls", String.valueOf(tls)).register(registry).increment();
    }

    public void rateLimited(String tenant) {
        Counter.builder("cluster93.api.rate.limited").description("Requests refused with 429").tag("tenant", tenant).register(registry).increment();
    }

    /** {@code backend}: cluster, disk, s3, or provider for a tenant's own. */
    public void fileStored(String backend, long bytes) {
        Counter.builder("cluster93.files.stored.bytes").description("Bytes of files stored, by backend kind").baseUnit("bytes")
                .tag("backend", backend).register(registry).increment(bytes);
        Counter.builder("cluster93.files.stored").description("Files stored, by backend kind").tag("backend", backend).register(registry).increment();
    }

    /** {@code reason}: quota, allotment, insufficient_storage, disabled, unavailable. */
    public void storageRefused(String reason) {
        Counter.builder("cluster93.files.refused").description("Uploads refused, by reason").tag("reason", reason).register(registry).increment();
    }

    /**
     * A queue message changed hands (D-061): pushed, acked, retried (a handler failed), dead (attempts used up),
     * released (its consumer stopped) or reclaimed (its consumer's lease ran out, so another took it).
     */
    public void queueMessage(String outcome) {
        Counter.builder("cluster93.queue.messages").description("Queue messages by what happened to them").tag("outcome", outcome)
                .register(registry).increment();
    }

    /** A completion callback delivery (D-062): delivered, retry (it will be tried again) or failed (given up). */
    public void callbackDelivery(String outcome) {
        Counter.builder("cluster93.callbacks.deliveries").description("Completion callback deliveries by outcome").tag("outcome", outcome)
                .register(registry).increment();
    }

    /** "cluster", "disk", "s3", or "provider" for any tenant's own. */
    public static String backendKind(String backend) {
        return backend.startsWith("provider:") ? "provider" : backend;
    }
}
