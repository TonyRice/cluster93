package automately.core.apps;

import io.vertx.core.Future;

import java.util.List;

/**
 * Where apps and their deployments live. The {@link SharedDataAppStore} implementation shares state across the
 * cluster; a durable implementation (Postgres) is planned so apps survive a full-cluster restart.
 */
public interface AppStore {

    /** Completes with null when there is no such app. */
    Future<AppRecord> getApp(String tenant, String name);

    Future<List<AppRecord>> listApps(String tenant);

    /** Completes with null when there is no such deployment. */
    Future<AppDeployment> getDeployment(String tenant, String app, String deploymentId);

    Future<List<AppDeployment>> listDeployments(String tenant, String app);

    /** Registers an immutable deployment. Fails rather than overwrite an existing one with different content. */
    Future<AppDeployment> saveDeployment(AppDeployment deployment);

    /**
     * Points the app at a deployment, creating the app when it is new and appending the deployment to its history.
     * Fails when the deployment was never registered.
     */
    Future<AppRecord> promote(String tenant, String app, String deploymentId);

    /**
     * Promotes the deployment that was live before the current one. Fails when the app is unknown or has only ever
     * had one deployment.
     */
    Future<AppRecord> rollback(String tenant, String app);

    /** Removes the app and every deployment it has. Completes with false when the app did not exist. */
    Future<Boolean> deleteApp(String tenant, String app);
}
