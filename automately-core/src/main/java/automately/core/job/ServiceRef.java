package automately.core.job;

import io.vertx.core.json.JsonObject;

/**
 * Marks a job as one run of a service.
 *
 * @param run           1 for the service's first run, then 2, 3, … across restarts
 * @param previousJobId the run before this one, whose checkpoint this run starts from; null for the first
 */
public record ServiceRef(String name, int revision, int run, String previousJobId) {

    public JsonObject toJson() {
        return new JsonObject().put("name", name).put("revision", revision).put("run", run).put("previousJobId", previousJobId);
    }

    public static ServiceRef fromJson(JsonObject json) {
        return json == null ? null : new ServiceRef(json.getString("name"), json.getInteger("revision"), json.getInteger("run"),
                json.getString("previousJobId"));
    }
}
