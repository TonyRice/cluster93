package automately.cluster.ignite.store;

import automately.core.apps.AppDeployment;
import automately.core.apps.AppRecord;
import automately.core.apps.AppStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Apps and their deployments in durable Ignite tables, so a deployed app survives a full-cluster restart (D-030).
 * App records are indexed by {@code (tenant, name)}, deployments by {@code (tenant, app)}; promotion and rollback
 * are compare-and-set updates on the app record, the same contract the in-memory store has.
 */
public final class IgniteAppStore implements AppStore {

    static final Table.Definition APPS = new Table.Definition("APPS",
            Table.columns("tenant", String.class, "name", String.class),
            List.of(Table.index("tenant", "name")));
    static final Table.Definition APP_DEPLOYMENTS = new Table.Definition("APP_DEPLOYMENTS",
            Table.columns("tenant", String.class, "app", String.class, "deploymentId", String.class),
            List.of(Table.index("tenant", "app")));
    static final int MAX_UPDATE_ATTEMPTS = 20;

    private final Table appsTable;
    private final Table deploymentsTable;
    private final LongSupplier clock;

    public IgniteAppStore(Vertx vertx, Ignite ignite) {
        this(vertx, ignite, System::currentTimeMillis);
    }

    IgniteAppStore(Vertx vertx, Ignite ignite, LongSupplier clock) {
        this.appsTable = new Table(vertx, ignite, APPS,
                json -> Map.of("tenant", json.getString("tenant"), "name", json.getString("name")));
        this.deploymentsTable = new Table(vertx, ignite, APP_DEPLOYMENTS,
                json -> Map.of("tenant", json.getString("tenant"), "app", json.getString("app"),
                        "deploymentId", json.getString("deploymentId")));
        this.clock = clock;
    }

    @Override
    public Future<AppRecord> getApp(String tenant, String name) {
        return appsTable.get(AppRecord.key(tenant, name))
                .map(json -> json == null ? null : AppRecord.fromJson(json));
    }

    @Override
    public Future<List<AppRecord>> listApps(String tenant) {
        return appsTable.queryJson("SELECT json FROM APPS WHERE tenant = ? ORDER BY name", tenant)
                .map(rows -> rows.stream().map(AppRecord::fromJson).toList());
    }

    @Override
    public Future<AppDeployment> getDeployment(String tenant, String app, String deploymentId) {
        return deploymentsTable.get(deploymentKey(tenant, app, deploymentId))
                .map(json -> json == null ? null : AppDeployment.fromJson(json));
    }

    @Override
    public Future<List<AppDeployment>> listDeployments(String tenant, String app) {
        return deploymentsTable.queryJson("SELECT json FROM APP_DEPLOYMENTS WHERE tenant = ? AND app = ?", tenant, app)
                .map(rows -> rows.stream()
                        .map(AppDeployment::fromJson)
                        .sorted(Comparator.comparingLong(AppDeployment::createdAt).thenComparing(AppDeployment::deploymentId))
                        .toList());
    }

    @Override
    public Future<AppDeployment> saveDeployment(AppDeployment deployment) {
        String key = deploymentKey(deployment.tenant(), deployment.app(), deployment.deploymentId());
        JsonObject json = deployment.toJson();
        return deploymentsTable.putIfAbsent(key, json).compose(written -> {
            if (written) {
                return Future.succeededFuture(deployment);
            }
            return deploymentsTable.get(key).compose(existing -> existing != null && existing.equals(json)
                    ? Future.succeededFuture(deployment)
                    : Future.failedFuture(new IllegalStateException(
                            "Deployment " + deployment.deploymentId() + " already exists for app " + deployment.app()
                                    + " with different content")));
        });
    }

    @Override
    public Future<AppRecord> promote(String tenant, String app, String deploymentId) {
        return getDeployment(tenant, app, deploymentId).compose(deployment -> {
            if (deployment == null) {
                return Future.failedFuture(new IllegalArgumentException(
                        "No deployment " + deploymentId + " for app " + app + " in tenant " + tenant));
            }
            return promote(tenant, app, deploymentId, 1);
        });
    }

    /**
     * Optimistic update of the app record: create it with {@code putIfAbsent} when it is new, otherwise
     * compare-and-set it. Both are retried when they lose a race.
     */
    private Future<AppRecord> promote(String tenant, String app, String deploymentId, int tries) {
        String key = AppRecord.key(tenant, app);
        return appsTable.get(key).compose(json -> {
            if (json == null) {
                AppRecord record = new AppRecord(tenant, app, deploymentId, clock.getAsLong(), List.of(deploymentId));
                return appsTable.putIfAbsent(key, record.toJson()).compose(written -> written
                        ? Future.succeededFuture(record)
                        : retry(tenant, app, deploymentId, tries));
            }
            return appsTable.update(key, current -> Optional.of(
                            AppRecord.fromJson(current).promoted(deploymentId, clock.getAsLong()).toJson()))
                    .compose(updated -> updated
                            .<Future<AppRecord>>map(record -> Future.succeededFuture(AppRecord.fromJson(record)))
                            .orElseGet(() -> retry(tenant, app, deploymentId, tries)));
        });
    }

    private Future<AppRecord> retry(String tenant, String app, String deploymentId, int tries) {
        if (tries >= MAX_UPDATE_ATTEMPTS) {
            return Future.failedFuture("Could not promote " + deploymentId + " for app " + app + " in tenant " + tenant);
        }
        return promote(tenant, app, deploymentId, tries + 1);
    }

    @Override
    public Future<AppRecord> rollback(String tenant, String app) {
        String key = AppRecord.key(tenant, app);
        return appsTable.update(key, json -> {
            if (json == null) {
                return Optional.empty();
            }
            return AppRecord.fromJson(json).rolledBack(clock.getAsLong()).map(AppRecord::toJson);
        }).compose(updated -> updated
                .<Future<AppRecord>>map(record -> Future.succeededFuture(AppRecord.fromJson(record)))
                .orElseGet(() -> Future.failedFuture(new IllegalStateException(
                        "App " + app + " in tenant " + tenant + " has no previous deployment to roll back to"))));
    }

    @Override
    public Future<Boolean> deleteApp(String tenant, String app) {
        return appsTable.remove(AppRecord.key(tenant, app)).compose(removed -> {
            if (removed == null) {
                return Future.succeededFuture(false);
            }
            return listDeployments(tenant, app)
                    .compose(deployments -> Future.all(deployments.stream()
                                    .map(deployment -> deploymentsTable.remove(deploymentKey(tenant, app, deployment.deploymentId())))
                                    .toList())
                            .map(true));
        });
    }

    private static String deploymentKey(String tenant, String app, String deploymentId) {
        return tenant + "/" + app + "/" + deploymentId;
    }
}
