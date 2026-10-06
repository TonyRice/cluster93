package automately.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromEnv(System.getenv());
        Automately.start(config)
                .onSuccess(node -> {
                    LOG.info("Node {} serving HTTP on port {} (clustered: {})", node.nodeId(), node.httpPort(), config.clustered());
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> node.stop().await()));
                })
                .onFailure(err -> {
                    LOG.error("Node failed to start", err);
                    System.exit(1);
                });
    }
}
