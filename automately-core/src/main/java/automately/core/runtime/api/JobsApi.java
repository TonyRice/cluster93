package automately.core.runtime.api;

import io.vertx.core.Future;

/** The job a script runs in, and (D-063) the jobs it starts. */
public interface JobsApi {

    /** Where the result goes when this job ends ({@code ctx.setCallback}, D-062); replaces any callback it had. */
    Future<Void> setCallback(String url, Object data);
}
