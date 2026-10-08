package automately.core.runtime;

import io.vertx.core.json.JsonObject;

import java.time.Duration;

/**
 * Everything a runtime needs to run one attempt of a job.
 *
 * @param timeout     wall-clock limit; {@link Duration#ZERO} means none (services)
 * @param longRunning true for services: an idle script waits for timers or a stop instead of failing
 *                    with "never settles"
 * @param packages    what the script can import, fetched and checked before it starts (D-089)
 * @param env         its {@code process.env}: what the job declared, secrets already read (D-095)
 */
public record Execution(String jobId, String tenant, String script, JsonObject input, Duration timeout, JobContext context,
                        boolean longRunning, Packages packages, java.util.Map<String, String> env) {

    public Execution {
        packages = packages == null ? Packages.NONE : packages;
        env = env == null ? java.util.Map.of("NODE_ENV", "production") : java.util.Map.copyOf(env);
    }

    public Execution(String jobId, String tenant, String script, JsonObject input, Duration timeout, JobContext context, boolean longRunning,
                     Packages packages) {
        this(jobId, tenant, script, input, timeout, context, longRunning, packages, null);
    }

    public Execution(String jobId, String tenant, String script, JsonObject input, Duration timeout, JobContext context, boolean longRunning) {
        this(jobId, tenant, script, input, timeout, context, longRunning, Packages.NONE);
    }

    /** A normal, finite job. */
    public Execution(String jobId, String tenant, String script, JsonObject input, Duration timeout, JobContext context) {
        this(jobId, tenant, script, input, timeout, context, false);
    }

    public boolean hasTimeout() {
        return timeout != null && !timeout.isZero();
    }
}
