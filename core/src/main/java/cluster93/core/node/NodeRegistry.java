package cluster93.core.node;

import io.vertx.core.Future;

import java.util.List;

/** Tracks which node instances are alive and how busy they are. Entries are keyed by instance id. */
public interface NodeRegistry {

    /** Publishes a node's state, stamped with {@link NodeInfo#lastSeen()}. */
    Future<Void> announce(NodeInfo node);

    Future<Void> remove(String instanceId);

    /** Nodes whose last announcement is within the liveness window. */
    Future<List<NodeInfo>> liveNodes();
}
