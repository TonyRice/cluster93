package automately.core.runtime.api;

import io.vertx.core.Future;

/**
 * The tenant's secrets, read-only ({@code secrets.get(name)}, D-068). Only for scripts of jobs submitted with
 * {@code secrets:use}, of services defined with it, and of jobs those start; other scripts get {@code forbidden}.
 */
public interface SecretsApi {

    /** The value, or null if there's no such secret. */
    Future<String> get(String name);
}
