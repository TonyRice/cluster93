package automately.cluster.ignite.store;

import automately.core.service.ServiceDefinition;
import automately.core.service.ServiceStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.ignite.Ignite;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Service definitions in a durable, replicated table, indexed by tenant. They survive a full-cluster restart. */
public final class IgniteServiceStore implements ServiceStore {

    static final Table.Definition TABLE = new Table.Definition("SERVICES",
            Table.columns("tenant", String.class, "name", String.class), List.of(Table.index("tenant", "name")));

    private final Table table;

    public IgniteServiceStore(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> Map.of("tenant", json.getString("tenant"), "name", json.getString("name")));
    }

    @Override
    public Future<Boolean> create(ServiceDefinition service) {
        return table.putIfAbsent(service.key(), service.toJson());
    }

    @Override
    public Future<ServiceDefinition> get(String tenant, String name) {
        return table.get(ServiceDefinition.key(tenant, name)).map(json -> json == null ? null : ServiceDefinition.fromJson(json));
    }

    @Override
    public Future<List<ServiceDefinition>> list(String tenant) {
        return table.queryJson("SELECT json FROM SERVICES WHERE tenant = ? ORDER BY name", tenant)
                .map(rows -> rows.stream().map(ServiceDefinition::fromJson).toList());
    }

    @Override
    public Future<List<ServiceDefinition>> all() {
        return table.queryJson("SELECT json FROM SERVICES ORDER BY tenant, name")
                .map(rows -> rows.stream().map(ServiceDefinition::fromJson).toList());
    }

    @Override
    public Future<Optional<ServiceDefinition>> update(String tenant, String name,
                                                      Function<ServiceDefinition, Optional<ServiceDefinition>> change) {
        return table.update(ServiceDefinition.key(tenant, name), json -> change.apply(ServiceDefinition.fromJson(json)).map(ServiceDefinition::toJson))
                .map(updated -> updated.map(ServiceDefinition::fromJson));
    }

    @Override
    public Future<Void> delete(String tenant, String name) {
        return table.remove(ServiceDefinition.key(tenant, name)).mapEmpty();
    }
}
