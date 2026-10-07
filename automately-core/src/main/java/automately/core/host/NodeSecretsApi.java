package automately.core.host;

import automately.core.job.Job;
import automately.core.runtime.HostException;
import automately.core.runtime.api.SecretsApi;
import automately.core.secrets.TenantSecrets;
import io.vertx.core.Future;

/**
 * {@code secrets.get} on the node's {@link TenantSecrets} (D-068). Whether this execution may read them is on its job,
 * set when the job was submitted, so it's looked up once, on first use.
 */
final class NodeSecretsApi implements SecretsApi {

    private final HostScope scope;
    private final TenantSecrets secrets;
    private final JobsWiring jobs;
    private Future<Boolean> allowed;

    NodeSecretsApi(HostScope scope, TenantSecrets secrets, JobsWiring jobs) {
        this.scope = scope;
        this.secrets = secrets;
        this.jobs = jobs;
    }

    @Override
    public Future<String> get(String name) {
        if (secrets == null || jobs == null || !secrets.available()) {
            return Future.failedFuture(new HostException("Error", "unavailable",
                    "Secrets are not available here: the cluster needs AUTOMATELY_SECRETS_KEY"));
        }
        if (!TenantSecrets.validName(name)) {
            return Future.failedFuture(HostException.invalid("\"name\" must be a secret's name: letters, digits, '_', '.' or '-'"));
        }
        return allowed().compose(ok -> ok
                ? secrets.value(scope.tenant(), name).recover(err -> Future.failedFuture(
                        new HostException("Error", "unavailable", "The secret \"" + name + "\" can't be opened; was AUTOMATELY_SECRETS_KEY changed?")))
                : Future.failedFuture(new HostException("Error", "forbidden",
                        "This script may not read secrets: submit the job (or define the service) with a token that has secrets:use")));
    }

    private synchronized Future<Boolean> allowed() {
        if (allowed == null) {
            allowed = jobs.jobs().get(scope.jobId()).map(job -> job != null && job.tenant().equals(scope.tenant()) && job.secrets());
        }
        return allowed;
    }
}
