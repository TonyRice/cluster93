package automately.core.service;

import automately.core.job.Job;
import automately.core.job.JobStore;
import automately.core.job.ServiceRef;
import automately.core.node.NodeInfo;
import automately.core.node.NodeRegistry;
import automately.core.scheduler.JobScheduler;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Keeps services running (docs/design/services.md). Every node runs one; the live instance with the
 * lowest id acts, as with the orphan reaper (D-012). Each sweep reconciles every service:
 * <ol>
 *   <li>if its current run ended, record how and decide when the next may start (backoff on failure);</li>
 *   <li>if it should be stopped, or runs an old revision, ask its run to stop;</li>
 *   <li>if it should be running and isn't, claim a new run (compare-and-set) and submit it.</li>
 * </ol>
 * Claiming before submitting means overlapping supervisors can never start two runs.
 */
public final class ServiceSupervisor extends VerticleBase {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceSupervisor.class);

    /** A run that was claimed but whose job never appeared is given up after this long. */
    static final Duration LOST_RUN_AFTER = Duration.ofSeconds(10);

    private final String instanceId;
    private final ServiceStore services;
    private final JobStore jobs;
    private final NodeRegistry nodes;
    private final JobScheduler scheduler;
    private final Duration interval;
    private final Timing timing;
    private boolean sweeping;
    private long timer;

    /**
     * @param stableAfter a run that stays up this long resets the failure count
     * @param baseBackoff first restart delay after a failure (doubles per consecutive failure)
     * @param maxBackoff  longest restart delay
     */
    public record Timing(Duration stableAfter, Duration baseBackoff, Duration maxBackoff) {

        public static final Timing DEFAULT = new Timing(Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofSeconds(60));
    }

    public ServiceSupervisor(String instanceId, ServiceStore services, JobStore jobs, NodeRegistry nodes, JobScheduler scheduler,
                             Duration interval, Timing timing) {
        this.instanceId = instanceId;
        this.services = services;
        this.jobs = jobs;
        this.nodes = nodes;
        this.scheduler = scheduler;
        this.interval = interval;
        this.timing = timing;
    }

    @Override
    public Future<?> start() {
        timer = vertx.setPeriodic(interval.toMillis(), id -> sweep());
        return Future.succeededFuture();
    }

    @Override
    public Future<?> stop() {
        vertx.cancelTimer(timer);
        return Future.succeededFuture();
    }

    private void sweep() {
        if (sweeping) {
            return;
        }
        sweeping = true;
        nodes.liveNodes()
                .compose(live -> isLeader(live) ? services.all().compose(this::reconcileAll) : Future.succeededFuture())
                .timeout(interval.multipliedBy(5).compareTo(Duration.ofSeconds(2)) > 0 ? interval.multipliedBy(5) : Duration.ofSeconds(2))
                .onFailure(err -> LOG.warn("Service sweep did not complete", err))
                .onComplete(ar -> sweeping = false);
    }

    private boolean isLeader(List<NodeInfo> live) {
        return live.stream().map(NodeInfo::instanceId).min(Comparator.naturalOrder()).map(instanceId::equals).orElse(false);
    }

    private Future<Void> reconcileAll(List<ServiceDefinition> all) {
        List<Future<Void>> work = new ArrayList<>();
        for (ServiceDefinition service : all) {
            work.add(reconcile(service).recover(err -> {
                LOG.warn("Could not reconcile service {}", service.key(), err);
                return Future.succeededFuture();
            }));
        }
        return Future.join(work).mapEmpty();
    }

    private Future<Void> reconcile(ServiceDefinition service) {
        if (service.currentJobId() == null) {
            return startIfDue(service);
        }
        String jobId = service.currentJobId();
        return jobs.get(jobId).compose(job -> {
            long now = System.currentTimeMillis();
            boolean lost = job == null && now - service.currentSince() >= LOST_RUN_AFTER.toMillis();
            if (lost || (job != null && job.status().isTerminal())) {
                return services.update(service.tenant(), service.name(),
                                current -> current.runEnded(jobId, job, timing.stableAfter(), timing.baseBackoff(), timing.maxBackoff(), now))
                        .compose(ended -> ended.isPresent() ? logExit(ended.get()).compose(v -> startIfDue(ended.get())) : Future.succeededFuture());
            }
            if (job == null || job.cancelRequested() != null) {
                return Future.succeededFuture();
            }
            if (!service.enabled()) {
                return scheduler.cancel(jobId, "service " + service.name() + " stopped").mapEmpty();
            }
            if (job.service() != null && job.service().revision() < service.revision()) {
                return scheduler.cancel(jobId, "service " + service.name() + " updated to revision " + service.revision()).mapEmpty();
            }
            return Future.succeededFuture();
        });
    }

    private Future<Void> logExit(ServiceDefinition service) {
        if (service.lastExit() != null) {
            LOG.info("Service {} run ended {} ({} consecutive failures; next start in {} ms)", service.key(),
                    service.lastExit().getString("status"), service.consecutiveFailures(),
                    Math.max(0, service.nextStartAt() - System.currentTimeMillis()));
        }
        return Future.succeededFuture();
    }

    private Future<Void> startIfDue(ServiceDefinition service) {
        long now = System.currentTimeMillis();
        if (!service.enabled() || service.completed() || now < service.nextStartAt()) {
            return Future.succeededFuture();
        }
        String jobId = UUID.randomUUID().toString();
        String previousJobId = service.lastExit() == null ? null : service.lastExit().getString("jobId");
        return services.update(service.tenant(), service.name(), current -> current.startingRun(jobId, now))
                .compose(claimed -> {
                    if (claimed.isEmpty()) {
                        return Future.succeededFuture();
                    }
                    ServiceDefinition run = claimed.get();
                    LOG.info("Starting service {} run {} (revision {}) as job {}", run.key(), run.runs(), run.revision(), jobId);
                    automately.core.metrics.Metrics.of(vertx).serviceRunStarted(run.runs() > 1);
                    return scheduler.submitServiceRun(jobId, run.tenant(), run.script(), run.config(),
                            new ServiceRef(run.name(), run.revision(), run.runs(), previousJobId), run.secrets(), run.packages()).mapEmpty();
                });
    }

}
