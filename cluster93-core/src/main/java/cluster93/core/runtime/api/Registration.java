package cluster93.core.runtime.api;

import io.vertx.core.Future;

/** A listener (bus subscription or handler, an HTTP server) that is active until cancelled. */
@FunctionalInterface
public interface Registration {

    Future<Void> cancel();
}
