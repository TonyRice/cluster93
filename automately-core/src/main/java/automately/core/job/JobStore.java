package automately.core.job;

import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Where jobs live. Implementations must be safe to call from any Vert.x context. */
public interface JobStore {

    /** Stores a new job. Changes to an existing job go through {@link #update}. */
    Future<Void> save(Job job);

    /** Completes with {@code null} when no job has that id. */
    Future<Job> get(String id);

    /** All jobs owned by a tenant, newest first. */
    Future<List<Job>> list(String tenant);

    /** All jobs still {@code QUEUED} or {@code RUNNING}, across tenants. Used by the orphan reaper. */
    Future<List<Job>> active();

    /**
     * Atomically applies a transition: reads the job, applies {@code transition}, and writes the result
     * only if nobody changed the job in between, retrying on conflict. Completes with empty when the
     * job does not exist or the transition does not apply (for example a stale attempt).
     */
    Future<Optional<Job>> update(String id, Function<Job, Optional<Job>> transition);
}
