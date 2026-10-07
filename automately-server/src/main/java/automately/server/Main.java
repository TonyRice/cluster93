package automately.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Chosen before anything logs: AUTOMATELY_LOG_FORMAT=json writes one JSON object per line (phase 5).
        if ("json".equalsIgnoreCase(System.getenv("AUTOMATELY_LOG_FORMAT"))) {
            System.setProperty("logback.configurationFile", "logback-json.xml");
            // Ignite's quiet mode prints straight to the console; without it, everything goes through the logger.
            System.setProperty("IGNITE_QUIET", "false");
        }
        Logger log = LoggerFactory.getLogger(Main.class);
        ServerConfig config = ServerConfig.fromEnv(System.getenv());
        Automately.start(config)
                .onSuccess(node -> {
                    log.info("Node {} serving the API on port {} and services on port {} (clustered: {})", node.nodeId(), node.httpPort(),
                            node.ingressPort(), config.clustered());
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> node.stop().await()));
                })
                .onFailure(err -> {
                    log.error("Node failed to start", err);
                    System.exit(1);
                });
    }
}
