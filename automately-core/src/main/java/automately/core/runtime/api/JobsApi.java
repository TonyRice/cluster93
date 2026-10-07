package automately.core.runtime.api;

import automately.core.runtime.Inbound;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.function.Consumer;

/**
 * The script's own job ({@code ctx.setCallback}, D-062) and the jobs it starts ({@code jobs}, D-063): 2017's
 * {@code Automately.startJob}, {@code getJob} and {@code stopJob}, without blocking a thread. Every job a script starts
 * belongs to its tenant, counts toward the tenant's rate limit, and records its parent.
 */
public interface JobsApi {

    /** A job may start jobs that start jobs, this many levels deep. */
    int MAX_DEPTH = 5;
    /** Jobs one run may start. */
    int MAX_STARTS_PER_RUN = 100;
    int MAX_SCRIPT_BYTES = 256 * 1024;

    /** Where the result goes when this job ends; replaces any callback it had. */
    Future<Void> setCallback(String url, Object data);

    /**
     * Starts a job; completes with its summary once it's queued.
     *
     * @param options {@code {timeoutMs, onOrphan, maxAttempts, callback: {url, data}}}, as on {@code POST /v1/jobs}
     */
    Future<JsonObject> run(String script, JsonObject input, JsonObject options);

    /** One of the tenant's jobs, or null. */
    Future<JsonObject> get(String id);

    /** Completes when the job has ended: {@code {id, status, output, error}}. Fails with {@code not_found}. */
    Future<JsonObject> result(String id);

    /** Asks the job to stop; completes with its summary. */
    Future<JsonObject> stop(String id, String reason);

    /** The job's events as they happen ({@code log}, {@code attempt}, {@code end}), as on its event stream. */
    Future<Registration> events(String id, Consumer<Inbound> listener);
}
