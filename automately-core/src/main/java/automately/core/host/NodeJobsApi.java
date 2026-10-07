package automately.core.host;

import automately.core.callback.Callbacks;
import automately.core.job.JobStore;
import automately.core.runtime.HostException;
import automately.core.runtime.api.JobsApi;
import io.vertx.core.Future;

/** The script's own job, in the node's {@link JobStore}. */
final class NodeJobsApi implements JobsApi {

    private final HostScope scope;
    private final JobStore jobs;

    NodeJobsApi(HostScope scope, JobStore jobs) {
        this.scope = scope;
        this.jobs = jobs;
    }

    @Override
    public Future<Void> setCallback(String url, Object data) {
        return Names.guarded(() -> {
            if (jobs == null) {
                throw new HostException("Error", "unavailable", "Callbacks are not available here");
            }
            Callbacks.validate(url, data);
            return jobs.update(scope.jobId(), job -> job.withCallback(url, data)).map(updated -> {
                if (updated.isEmpty()) {
                    throw new HostException("Error", "not_found", "This job has already ended");
                }
                return null;
            });
        });
    }
}
