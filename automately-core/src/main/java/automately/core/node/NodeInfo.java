package automately.core.node;

import io.vertx.core.json.JsonObject;

/**
 * What a node advertises to the rest of the cluster on every heartbeat.
 *
 * @param id         the configured node name (stable across restarts)
 * @param instanceId this process's incarnation; a restarted node gets a new one (D-009)
 * @param endpoint   {@code host:port} of the node's internal HTTP endpoint, where the ingress proxies service traffic
 */
public record NodeInfo(String id, String instanceId, int capacity, int running, long lastSeen, String endpoint) {

    public NodeInfo(String id, String instanceId, int capacity, int running, long lastSeen) {
        this(id, instanceId, capacity, running, lastSeen, null);
    }

    public double load() {
        return capacity == 0 ? 1.0 : (double) running / capacity;
    }

    public boolean hasCapacity() {
        return running < capacity;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id)
                .put("instanceId", instanceId)
                .put("capacity", capacity)
                .put("running", running)
                .put("lastSeen", lastSeen)
                .put("endpoint", endpoint);
    }

    public static NodeInfo fromJson(JsonObject json) {
        return new NodeInfo(json.getString("id"), json.getString("instanceId"), json.getInteger("capacity"),
                json.getInteger("running"), json.getLong("lastSeen"), json.getString("endpoint"));
    }
}
