package cluster93.core.apps;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An app in a tenant and which of its deployments is live. Mutable state is replaced through the store's
 * compare-and-set promotion; the record itself is immutable.
 *
 * @param tenant              the tenant that owns the app
 * @param name                the app's name
 * @param activeDeploymentId  the promoted deployment, or null for an app with nothing live yet
 * @param updatedAt           when the app last changed, epoch millis
 * @param deploymentHistory   the deployments this app has been promoted through, oldest first; its active
 *                            deployment is the last entry, so rolling back drops that entry and re-points at the one
 *                            before it
 */
public record AppRecord(String tenant, String name, String activeDeploymentId, long updatedAt,
                        List<String> deploymentHistory) {

    public AppRecord {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(name, "name");
        deploymentHistory = deploymentHistory == null ? List.of() : List.copyOf(deploymentHistory);
    }

    public static String key(String tenant, String name) {
        return tenant + "/" + name;
    }

    public String key() {
        return key(tenant, name);
    }

    /** Points the app at {@code deploymentId}, appending it to the history. Promoting the current deployment is a no-op. */
    public AppRecord promoted(String deploymentId, long now) {
        Objects.requireNonNull(deploymentId, "deploymentId");
        if (deploymentId.equals(activeDeploymentId)) {
            return this;
        }
        List<String> history = new ArrayList<>(deploymentHistory);
        history.add(deploymentId);
        return new AppRecord(tenant, name, deploymentId, now, history);
    }

    /**
     * Points the app at the deployment promoted before the current one, dropping the current deployment from the
     * history so a further rollback keeps stepping back.
     *
     * @return the rolled-back record, or empty when there is nothing earlier to promote (fewer than two promotions)
     */
    public Optional<AppRecord> rolledBack(long now) {
        if (deploymentHistory.size() < 2) {
            return Optional.empty();
        }
        List<String> history = new ArrayList<>(deploymentHistory.subList(0, deploymentHistory.size() - 1));
        return Optional.of(new AppRecord(tenant, name, history.get(history.size() - 1), now, history));
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("tenant", tenant)
                .put("name", name)
                .put("activeDeploymentId", activeDeploymentId)
                .put("updatedAt", updatedAt)
                .put("deploymentHistory", new JsonArray(new ArrayList<>(deploymentHistory)));
    }

    public static AppRecord fromJson(JsonObject json) {
        JsonArray history = json.getJsonArray("deploymentHistory");
        List<String> ids = new ArrayList<>();
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                ids.add(history.getString(i));
            }
        }
        return new AppRecord(
                json.getString("tenant"),
                json.getString("name"),
                json.getString("activeDeploymentId"),
                json.getLong("updatedAt", 0L),
                ids);
    }
}
