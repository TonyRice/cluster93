package automately.core.service;

import io.vertx.core.Future;
import automately.core.shared.SharedMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Service definitions in a Vert.x {@link AsyncMap}, keyed {@code tenant/name}, with compare-and-set updates. */
public final class SharedDataServiceStore implements ServiceStore {

    static final String MAP_NAME = "automately.services";
    static final int MAX_UPDATE_ATTEMPTS = 20;

    private final SharedMap<String, JsonObject> map;

    public SharedDataServiceStore(Vertx vertx) {
        this.map = new SharedMap<>(vertx, MAP_NAME);
    }

    @Override
    public Future<Boolean> create(ServiceDefinition service) {
        return map.compose(m -> m.putIfAbsent(service.key(), service.toJson())).map(existing -> existing == null);
    }

    @Override
    public Future<ServiceDefinition> get(String tenant, String name) {
        return map.compose(m -> m.get(ServiceDefinition.key(tenant, name))).map(json -> json == null ? null : ServiceDefinition.fromJson(json));
    }

    @Override
    public Future<List<ServiceDefinition>> list(String tenant) {
        return all().map(all -> all.stream().filter(s -> s.tenant().equals(tenant)).toList());
    }

    @Override
    public Future<List<ServiceDefinition>> all() {
        return map.compose(AsyncMap::values).map(values -> values.stream()
                .map(ServiceDefinition::fromJson)
                .sorted(Comparator.comparing(ServiceDefinition::key))
                .toList());
    }

    @Override
    public Future<Optional<ServiceDefinition>> update(String tenant, String name,
                                                      Function<ServiceDefinition, Optional<ServiceDefinition>> change) {
        String key = ServiceDefinition.key(tenant, name);
        return map.compose(m -> update(m, key, change, 1));
    }

    private Future<Optional<ServiceDefinition>> update(AsyncMap<String, JsonObject> m, String key,
                                                       Function<ServiceDefinition, Optional<ServiceDefinition>> change, int tries) {
        return m.get(key).compose(current -> {
            if (current == null) {
                return Future.succeededFuture(Optional.empty());
            }
            Optional<ServiceDefinition> next = change.apply(ServiceDefinition.fromJson(current));
            if (next.isEmpty()) {
                return Future.succeededFuture(Optional.empty());
            }
            return m.replaceIfPresent(key, current, next.get().toJson()).compose(replaced -> {
                if (replaced) {
                    return Future.succeededFuture(next);
                }
                if (tries >= MAX_UPDATE_ATTEMPTS) {
                    return Future.failedFuture("Gave up updating service " + key + " after " + tries + " conflicting writes");
                }
                return update(m, key, change, tries + 1);
            });
        });
    }

    @Override
    public Future<Void> delete(String tenant, String name) {
        return map.compose(m -> m.remove(ServiceDefinition.key(tenant, name))).mapEmpty();
    }
}
