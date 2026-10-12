package automately.core.apps;

import automately.core.shared.SharedMap;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Apps and deployments in Vert.x {@link AsyncMap}s, so the same code runs against a local map or the clustered
 * (Ignite-backed) shared data of a real cluster. App records are keyed {@code tenant/name}, deployments
 * {@code tenant/app/deploymentId}; promotion and rollback are compare-and-set updates on the app record.
 */
public final class SharedDataAppStore implements AppStore {

    static final String APPS_MAP = "automately.apps";
    static final String DEPLOYMENTS_MAP = "automately.app.deployments";
    static final int MAX_UPDATE_ATTEMPTS = 20;

    private final SharedMap<String, JsonObject> apps;
    private final SharedMap<String, JsonObject> deployments;
    private final LongSupplier clock;

    public SharedDataAppStore(Vertx vertx) {
        this(vertx, System::currentTimeMillis);
    }

    SharedDataAppStore(Vertx vertx, LongSupplier clock) {
        this.apps = new SharedMap<>(vertx, APPS_MAP);
        this.deployments = new SharedMap<>(vertx, DEPLOYMENTS_MAP);
        this.clock = clock;
    }

    @Override
    public Future<AppRecord> getApp(String tenant, String name) {
        return apps.compose(m -> m.get(AppRecord.key(tenant, name)))
                .map(json -> json == null ? null : AppRecord.fromJson(json));
    }

    @Override
    public Future<List<AppRecord>> listApps(String tenant) {
        return apps.compose(AsyncMap::values).map(values -> values.stream()
                .map(AppRecord::fromJson)
                .filter(record -> record.tenant().equals(tenant))
                .sorted(Comparator.comparing(AppRecord::name))
                .toList());
    }

    @Override
    public Future<AppDeployment> getDeployment(String tenant, String app, String deploymentId) {
        return deployments.compose(m -> m.get(deploymentKey(tenant, app, deploymentId)))
                .map(json -> json == null ? null : AppDeployment.fromJson(json));
    }

    @Override
    public Future<List<AppDeployment>> listDeployments(String tenant, String app) {
        return deployments.compose(AsyncMap::values).map(values -> values.stream()
                .map(AppDeployment::fromJson)
                .filter(deployment -> deployment.tenant().equals(tenant) && deployment.app().equals(app))
                .sorted(Comparator.comparingLong(AppDeployment::createdAt).thenComparing(AppDeployment::deploymentId))
                .toList());
    }

    @Override
    public Future<AppDeployment> saveDeployment(AppDeployment deployment) {
        String key = deploymentKey(deployment.tenant(), deployment.app(), deployment.deploymentId());
        JsonObject json = deployment.toJson();
        return deployments.compose(m -> m.putIfAbsent(key, json)).compose(existing -> {
            if (existing == null || existing.equals(json)) {
                return Future.succeededFuture(deployment);
            }
            return Future.failedFuture(new IllegalStateException(
                    "Deployment " + deployment.deploymentId() + " already exists for app " + deployment.app()
                            + " with different content"));
        });
    }

    @Override
    public Future<AppRecord> promote(String tenant, String app, String deploymentId) {
        return getDeployment(tenant, app, deploymentId).compose(deployment -> {
            if (deployment == null) {
                return Future.failedFuture(new IllegalArgumentException(
                        "No deployment " + deploymentId + " for app " + app + " in tenant " + tenant));
            }
            long now = clock.getAsLong();
            String key = AppRecord.key(tenant, app);
            return apps.compose(m -> update(m, key, current -> Optional.of(current == null
                    ? new AppRecord(tenant, app, deploymentId, now, List.of(deploymentId))
                    : current.promoted(deploymentId, now)), 1))
                    .compose(record -> record
                            .<Future<AppRecord>>map(Future::succeededFuture)
                            .orElseGet(() -> Future.failedFuture(
                                    "Could not promote " + deploymentId + " for app " + app + " in tenant " + tenant)));
        });
    }

    @Override
    public Future<AppRecord> rollback(String tenant, String app) {
        long now = clock.getAsLong();
        String key = AppRecord.key(tenant, app);
        return apps.compose(m -> update(m, key,
                        current -> current == null ? Optional.empty() : current.rolledBack(now), 1))
                .compose(record -> record
                        .<Future<AppRecord>>map(Future::succeededFuture)
                        .orElseGet(() -> Future.failedFuture(new IllegalStateException(
                                "App " + app + " in tenant " + tenant + " has no previous deployment to roll back to"))));
    }

    @Override
    public Future<Boolean> deleteApp(String tenant, String app) {
        String key = AppRecord.key(tenant, app);
        return apps.compose(m -> m.get(key).compose(existing -> {
            if (existing == null) {
                return Future.succeededFuture(false);
            }
            return m.remove(key).compose(v -> deleteDeployments(tenant, app)).map(true);
        }));
    }

    private Future<Void> deleteDeployments(String tenant, String app) {
        return deployments.compose(m -> m.values().compose(values -> {
            List<Future<JsonObject>> removals = new ArrayList<>();
            for (JsonObject value : values) {
                AppDeployment deployment = AppDeployment.fromJson(value);
                if (deployment.tenant().equals(tenant) && deployment.app().equals(app)) {
                    removals.add(m.remove(deploymentKey(tenant, app, deployment.deploymentId())));
                }
            }
            return Future.all(removals).mapEmpty();
        }));
    }

    /**
     * Compare-and-set update of an app record, retrying on conflict. {@code change} is handed the current record (null
     * when the app is new) and returns the replacement, or empty to leave the app alone.
     */
    private Future<Optional<AppRecord>> update(AsyncMap<String, JsonObject> m, String key,
                                               Function<AppRecord, Optional<AppRecord>> change, int tries) {
        return m.get(key).compose(current -> {
            Optional<AppRecord> next = change.apply(current == null ? null : AppRecord.fromJson(current));
            if (next.isEmpty()) {
                return Future.succeededFuture(Optional.<AppRecord>empty());
            }
            JsonObject json = next.get().toJson();
            if (current == null) {
                return m.putIfAbsent(key, json).compose(existing -> existing == null
                        ? Future.succeededFuture(next)
                        : retryUpdate(m, key, change, tries));
            }
            return m.replaceIfPresent(key, current, json).compose(replaced -> replaced
                    ? Future.succeededFuture(next)
                    : retryUpdate(m, key, change, tries));
        });
    }

    private Future<Optional<AppRecord>> retryUpdate(AsyncMap<String, JsonObject> m, String key,
                                                    Function<AppRecord, Optional<AppRecord>> change, int tries) {
        if (tries >= MAX_UPDATE_ATTEMPTS) {
            return Future.failedFuture("Gave up updating app " + key + " after " + tries + " conflicting writes");
        }
        return update(m, key, change, tries + 1);
    }

    private static String deploymentKey(String tenant, String app, String deploymentId) {
        return tenant + "/" + app + "/" + deploymentId;
    }
}
