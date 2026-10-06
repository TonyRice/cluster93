package automately.server.cluster;

import automately.server.ServerConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.spi.cluster.hazelcast.ConfigUtil;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

/**
 * The only place in the codebase that knows the cluster is Hazelcast. Everything else talks to
 * Vert.x shared data and the event bus, so swapping cluster managers touches this class alone.
 */
public final class Clustering {

    private Clustering() {
    }

    public static Future<Vertx> vertx(ServerConfig config) {
        if (!config.clustered()) {
            return Future.succeededFuture(Vertx.vertx());
        }
        return Vertx.builder()
                .withClusterManager(new HazelcastClusterManager(hazelcast(config)))
                .buildClustered();
    }

    static Config hazelcast(ServerConfig config) {
        // Start from Vert.x's default config, which declares the maps and multimaps Vert.x relies on.
        Config hazelcast = ConfigUtil.loadConfig();
        hazelcast.setClusterName(config.clusterName());
        hazelcast.setProperty("hazelcast.logging.type", "slf4j");
        hazelcast.setProperty("hazelcast.shutdownhook.enabled", "false");
        hazelcast.setProperty("hazelcast.phone.home.enabled", "false");
        hazelcast.getNetworkConfig().setPort(config.clusterPort()).setPortAutoIncrement(true);

        JoinConfig join = hazelcast.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(config.clusterMembers());
        return hazelcast;
    }
}
