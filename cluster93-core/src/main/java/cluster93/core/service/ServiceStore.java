package cluster93.core.service;

import io.vertx.core.Future;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where service definitions live. The SharedData implementation is lost on a full-cluster restart;
 * a durable implementation (Postgres) is planned so services come back on their own, as they did in 2017.
 */
public interface ServiceStore {

    /** Stores a new service; completes with false if one with that tenant and name already exists. */
    Future<Boolean> create(ServiceDefinition service);

    /** Completes with null when there is no such service. */
    Future<ServiceDefinition> get(String tenant, String name);

    Future<List<ServiceDefinition>> list(String tenant);

    Future<List<ServiceDefinition>> all();

    /** Compare-and-set update, like {@code JobStore#update}. Empty when missing or the change doesn't apply. */
    Future<Optional<ServiceDefinition>> update(String tenant, String name, Function<ServiceDefinition, Optional<ServiceDefinition>> change);

    Future<Void> delete(String tenant, String name);
}
