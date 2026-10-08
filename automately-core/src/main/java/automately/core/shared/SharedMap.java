package automately.core.shared;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.AsyncMap;

import java.util.function.Function;

/**
 * A Vert.x shared-data map, looked up on every use (D-099). A future completes on the context that made it, so a store
 * that kept the {@code getAsyncMap} future from its constructor answered every caller on that one context: an API
 * handler then wrote its response from another event loop than its request's, raced the request's own thread, and
 * under load threw before answering, leaving the client waiting forever. Looked up per use, the map's futures (and
 * everything composed on them) complete on the caller's context. For a local map the lookup is a map get.
 */
public final class SharedMap<K, V> {

    private final Vertx vertx;
    private final String name;

    public SharedMap(Vertx vertx, String name) {
        this.vertx = vertx;
        this.name = name;
    }

    public <T> Future<T> compose(Function<AsyncMap<K, V>, Future<T>> use) {
        return vertx.sharedData().<K, V>getAsyncMap(name).compose(use);
    }
}
