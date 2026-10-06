package automately.core.node;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.time.Duration;
import java.util.List;

/**
 * Node heartbeats in a Vert.x {@link AsyncMap}. A node is live while its last announcement is within the
 * liveness window, judged from the {@code lastSeen} timestamp it wrote (D-013).
 *
 * <p>Map TTLs are only used to clean up entries of nodes that are long gone: Hazelcast expires entries up
 * to about a second early (measured), so TTLs can't decide liveness at heartbeat timescales.
 */
public final class SharedDataNodeRegistry implements NodeRegistry {

    static final String MAP_NAME = "automately.nodes";
    static final Duration CLEANUP_AFTER = Duration.ofMinutes(1);

    private final Future<AsyncMap<String, JsonObject>> map;
    private final Duration livenessWindow;

    public SharedDataNodeRegistry(Vertx vertx, Duration livenessWindow) {
        this.map = vertx.sharedData().getAsyncMap(MAP_NAME);
        this.livenessWindow = livenessWindow;
    }

    @Override
    public Future<Void> announce(NodeInfo node) {
        return map.compose(m -> m.put(node.instanceId(), node.toJson(), livenessWindow.plus(CLEANUP_AFTER).toMillis()));
    }

    @Override
    public Future<Void> remove(String instanceId) {
        return map.compose(m -> m.remove(instanceId)).mapEmpty();
    }

    @Override
    public Future<List<NodeInfo>> liveNodes() {
        return map.compose(AsyncMap::values).map(values -> {
            long cutoff = System.currentTimeMillis() - livenessWindow.toMillis();
            return values.stream().map(NodeInfo::fromJson).filter(node -> node.lastSeen() >= cutoff).toList();
        });
    }
}
