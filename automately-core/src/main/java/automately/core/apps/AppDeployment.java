package automately.core.apps;

import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * One immutable build of an app: its parsed manifest, the VFS directory its static assets were uploaded to, and the
 * optional revision of the backend service it needs. A deployment never changes; a new build mints a new one, and
 * promoting it only moves the app's pointer. See docs/design/apps-and-console.md.
 *
 * @param tenant          the tenant that owns the app
 * @param app             the app's name
 * @param deploymentId    a content-addressed id (or uuid), unique within the app
 * @param createdAt       when the deployment was registered, epoch millis
 * @param commitHash      the git commit it was built from, or null when unknown
 * @param manifest        the parsed {@code automately.json}
 * @param vfsPrefix       the VFS directory holding its static assets, e.g. {@code /.apps/shop/deployments/dpl_abc}
 * @param serviceRevision the revision of the companion backend service to run, or null when there is none
 */
public record AppDeployment(String tenant, String app, String deploymentId, long createdAt, String commitHash,
                            AppManifest manifest, String vfsPrefix, Integer serviceRevision) {

    public AppDeployment {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(manifest, "manifest");
        vfsPrefix = vfsPrefix == null || vfsPrefix.isBlank() ? vfsPrefixFor(app, deploymentId) : vfsPrefix;
    }

    /** The VFS directory a deployment's static assets live in: {@code /.apps/<app>/deployments/<deploymentId>}. */
    public static String vfsPrefixFor(String app, String deploymentId) {
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(deploymentId, "deploymentId");
        return "/.apps/" + app + "/deployments/" + deploymentId;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("tenant", tenant)
                .put("app", app)
                .put("deploymentId", deploymentId)
                .put("createdAt", createdAt)
                .put("commitHash", commitHash)
                .put("manifest", manifest.toJson())
                .put("vfsPrefix", vfsPrefix)
                .put("serviceRevision", serviceRevision);
    }

    public static AppDeployment fromJson(JsonObject json) {
        return new AppDeployment(
                json.getString("tenant"),
                json.getString("app"),
                json.getString("deploymentId"),
                json.getLong("createdAt", 0L),
                json.getString("commitHash"),
                AppManifest.fromJson(json.getJsonObject("manifest")),
                json.getString("vfsPrefix"),
                json.getInteger("serviceRevision"));
    }
}
