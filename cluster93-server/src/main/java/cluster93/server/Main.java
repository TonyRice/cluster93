package cluster93.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Chosen before anything logs: C93_LOG_FORMAT=json writes one JSON object per line (phase 5).
        if ("json".equalsIgnoreCase(System.getenv("C93_LOG_FORMAT"))) {
            System.setProperty("logback.configurationFile", "logback-json.xml");
            // Ignite's quiet mode prints straight to the console; without it, everything goes through the logger.
            System.setProperty("IGNITE_QUIET", "false");
        }
        Logger log = LoggerFactory.getLogger(Main.class);
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        if (args.length >= 1 && args[0].equals("--prepare")) {
            prepare(env, args.length >= 2 ? args[1] : env.get("C93_NODE_ID"));
            return;
        }
        if (args.length >= 2 && args[0].equals("--join")) {
            env.put("C93_JOIN", args[1]);
        }
        ServerConfig config = ServerConfig.fromEnv(env);
        Cluster93.start(config)
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

    /**
     * The strict join (D-067): makes this node's key pair, keeps the private key in its TLS directory, and prints the
     * public key for the operator, who answers with a certificate-only line. Nothing secret leaves this machine.
     */
    private static void prepare(java.util.Map<String, String> env, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            System.err.println("Usage: cluster93-server --prepare <node id>   (or set C93_NODE_ID)");
            System.exit(2);
        }
        env.put("C93_NODE_ID", nodeId);
        ServerConfig config = ServerConfig.fromEnv(env);
        if (config.tlsDirectory() == null) {
            System.err.println("Set C93_DATA_DIR or C93_TLS_DIR first: the private key is kept there.");
            System.exit(2);
        }
        String publicKey = cluster93.tls.NodeIdentity.prepareKey(config.tlsDirectory());
        System.out.println("Private key kept in " + config.tlsDirectory() + ". On the machine with ca.key, run:");
        System.out.println();
        System.out.println("  c93 node invite " + nodeId + " --host <this node's address> --key " + publicKey + " --ca ./ca.key");
        System.out.println();
        System.out.println("then start this node with C93_JOIN set to the line it prints.");
    }
}
