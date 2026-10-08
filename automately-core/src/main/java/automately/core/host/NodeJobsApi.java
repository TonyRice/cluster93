package automately.core.host;

import automately.core.callback.Callbacks;
import automately.core.job.Job;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.HostException;
import automately.core.runtime.Inbound;
import automately.core.runtime.api.HostArgs;
import automately.core.runtime.api.JobsApi;
import automately.core.runtime.api.Registration;
import automately.core.scheduler.Addresses;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** The script's own job and the jobs it starts (D-062, D-063), through the node's scheduler and job store. */
final class NodeJobsApi implements JobsApi {

    /** How often {@link #result} looks, in case it missed the job's end event. */
    static final long RESULT_CHECK_MS = 2_000;

    private final HostScope scope;
    private final JobsWiring jobs;
    private final AtomicInteger started = new AtomicInteger();

    NodeJobsApi(HostScope scope, JobsWiring jobs) {
        this.scope = scope;
        this.jobs = jobs;
    }

    private JobsWiring wiring() {
        if (jobs == null) {
            throw new HostException("Error", "unavailable", "Jobs are not available here");
        }
        return jobs;
    }

    @Override
    public Future<Void> setCallback(String url, Object data) {
        return Names.guarded(() -> {
            JobsWiring wiring = wiring();
            Callbacks.validate(url, data);
            return wiring.jobs().update(scope.jobId(), job -> job.withCallback(url, data)).map(updated -> {
                if (updated.isEmpty()) {
                    throw new HostException("Error", "not_found", "This job has already ended");
                }
                return null;
            });
        });
    }

    @Override
    public Future<JsonObject> run(String script, JsonObject input, JsonObject options) {
        return Names.guarded(() -> {
            JobsWiring wiring = wiring();
            if (script == null || script.isBlank()) {
                throw HostException.invalid("A job needs a script: an ES module source, or a function");
            }
            if (script.length() > MAX_SCRIPT_BYTES) {
                throw HostException.limit("A script is limited to " + MAX_SCRIPT_BYTES + " bytes");
            }
            JsonObject o = options == null ? new JsonObject() : options;
            Duration timeout = wiring.defaultTimeout();
            if (o.getValue("timeoutMs") != null) {
                long ms = HostArgs.integer(o.getValue("timeoutMs"), "timeoutMs");
                if (ms < 1 || ms > wiring.maxTimeout().toMillis()) {
                    throw HostException.invalid("\"timeoutMs\" must be between 1 and " + wiring.maxTimeout().toMillis());
                }
                timeout = Duration.ofMillis(ms);
            }
            OrphanPolicy onOrphan = OrphanPolicy.fromApi(String.valueOf(o.getValue("onOrphan", "fail")));
            if (onOrphan == null) {
                throw HostException.invalid("\"onOrphan\" must be \"fail\", \"retry\" or \"resume\"");
            }
            int maxAttempts = onOrphan == OrphanPolicy.FAIL ? 1 : OrphanPolicy.DEFAULT_MAX_ATTEMPTS;
            if (o.getValue("maxAttempts") != null) {
                maxAttempts = HostArgs.intBetween(o.getValue("maxAttempts"), "maxAttempts", 1, onOrphan == OrphanPolicy.FAIL ? 1 : OrphanPolicy.MAX_ATTEMPTS_LIMIT);
            }
            // Its own process.env (D-095); the parent's only if it asks for it with inheritEnv.
            String badEnv = automately.core.job.JobEnv.invalid(o.getValue("env"));
            if (badEnv != null) {
                throw HostException.invalid(badEnv);
            }
            JsonObject ownEnv = o.getJsonObject("env");
            boolean inheritEnv = Boolean.TRUE.equals(o.getValue("inheritEnv"));
            UnaryOperator<Job> callback = UnaryOperator.identity();
            if (o.getValue("callback") != null) {
                if (!(o.getValue("callback") instanceof JsonObject hook) || !(hook.getValue("url") instanceof String url)) {
                    throw HostException.invalid("\"callback\" must be an object with a \"url\"");
                }
                Callbacks.validate(url, hook.getValue("data"));
                Object data = hook.getValue("data");
                callback = job -> job.withCallback(url, data).orElseThrow();
            }
            if (started.incrementAndGet() > MAX_STARTS_PER_RUN) {
                throw HostException.limit("A run can start at most " + MAX_STARTS_PER_RUN + " jobs");
            }
            if (wiring.limiter() != null && wiring.limiter().enabled() && !wiring.limiter().acquire(scope.tenant(), System.nanoTime()).allowed()) {
                throw HostException.limit("The tenant's rate limit is reached; try again shortly");
            }
            Duration jobTimeout = timeout;
            int attempts = maxAttempts;
            UnaryOperator<Job> withCallback = callback;
            return wiring.jobs().get(scope.jobId()).compose(self -> {
                int depth = self == null ? 0 : self.depth();
                if (depth + 1 > MAX_DEPTH) {
                    return Future.failedFuture(HostException.limit("Jobs can start jobs at most " + MAX_DEPTH + " levels deep"));
                }
                JsonObject env = inheritEnv && self != null && self.env() != null ? self.env().copy() : new JsonObject();
                if (ownEnv != null) {
                    env.mergeIn(ownEnv);
                }
                if (automately.core.job.JobEnv.invalid(env) != null) {
                    return Future.failedFuture(HostException.invalid(automately.core.job.JobEnv.invalid(env)));
                }
                if (automately.core.job.JobEnv.usesSecrets(env) && (self == null || !self.secrets())) {
                    return Future.failedFuture(new HostException("Error", "forbidden",
                            "env names secrets, but this job may not read them, so the job it starts can't either"));
                }
                JsonObject lock;
                try {
                    // It can import what the job starting it could (D-089).
                    lock = automately.core.packages.PackageStore.inherit(script, self == null ? null : self.packages());
                } catch (HostException e) {
                    return Future.failedFuture(e);
                }
                return wiring.scheduler().submit(scope.tenant(), script, input == null ? new JsonObject() : input, jobTimeout, onOrphan, attempts,
                        // A started job may read secrets only if the job starting it may (D-068).
                        job -> withCallback.apply(job.startedBy(scope.jobId(), depth).withSecrets(self != null && self.secrets()).withPackages(lock).withEnv(env)));
            }).map(Job::toSummaryJson);
        });
    }

    @Override
    public Future<JsonObject> get(String id) {
        return Names.guarded(() -> own(id).map(job -> job == null ? null : job.toSummaryJson()));
    }

    @Override
    public Future<JsonObject> stop(String id, String reason) {
        return Names.guarded(() -> own(id).compose(job -> job == null
                ? Future.failedFuture(notFound(id))
                : wiring().scheduler().cancel(id, reason == null ? "stopped by job " + scope.jobId() : reason).map(Job::toSummaryJson)));
    }

    /**
     * Listens for the job's end event first, then looks, so an end between the two isn't missed; and looks again every
     * {@value #RESULT_CHECK_MS} ms in case the event was lost.
     */
    @Override
    public Future<JsonObject> result(String id) {
        return Names.guarded(() -> {
            if (id == null || id.isEmpty()) {
                throw HostException.invalid("A job id must be a non-empty string");
            }
            wiring();
            Promise<JsonObject> done = Promise.promise();
            Runnable check = () -> own(id).onComplete(ar -> {
                if (ar.failed()) {
                    done.tryFail(ar.cause());
                } else if (ar.result() == null) {
                    done.tryFail(notFound(id));
                } else if (ar.result().status().isTerminal()) {
                    Job job = ar.result();
                    done.tryComplete(new JsonObject().put("id", job.id()).put("status", job.status().name()).put("output", job.output())
                            .put("error", job.error()));
                }
            });
            MessageConsumer<JsonObject> ends = scope.vertx().eventBus().consumer(Addresses.jobEvents(id), event -> {
                if ("end".equals(event.body().getString("type"))) {
                    check.run();
                }
            });
            long timer = scope.vertx().setPeriodic(RESULT_CHECK_MS, t -> check.run());
            Registration registration = scope.track(() -> {
                scope.vertx().cancelTimer(timer);
                return ends.unregister();
            });
            ends.completion().onComplete(ar -> check.run());
            return done.future().andThen(ar -> registration.cancel());
        });
    }

    @Override
    public Future<Registration> events(String id, Consumer<Inbound> listener) {
        return Names.guarded(() -> own(id).compose(job -> {
            if (job == null) {
                return Future.failedFuture(notFound(id));
            }
            MessageConsumer<JsonObject> consumer = scope.vertx().eventBus().consumer(Addresses.jobEvents(id), event -> listener.accept(event(event.body())));
            Registration registration = scope.track(consumer::unregister);
            return consumer.completion().map(registration);
        }));
    }

    /** The job if it's this tenant's; null otherwise, so another tenant's jobs look like missing ones. */
    private Future<Job> own(String id) {
        if (id == null || id.isEmpty()) {
            throw HostException.invalid("A job id must be a non-empty string");
        }
        return wiring().jobs().get(id).map(job -> job != null && job.tenant().equals(scope.tenant()) ? job : null);
    }

    private static HostException notFound(String id) {
        return new HostException("Error", "not_found", "No such job: " + id);
    }

    private static Inbound event(JsonObject body) {
        return new Inbound() {
            @Override
            public Object payload() {
                return body;
            }

            @Override
            public boolean expectsReply() {
                return false;
            }

            @Override
            public void reply(Object value) {
            }

            @Override
            public void fail(int code, String message) {
            }
        };
    }
}
