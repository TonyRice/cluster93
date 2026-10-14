package cluster93.server;

import cluster93.tls.ClusterCa;
import cluster93.tls.NodeIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node certificates for in-JVM clustered tests (D-066): one CA per test run, and a certificate per node, so the
 * tests run with TLS required between nodes, as production does.
 */
final class TestTls {

    static final ClusterCa CA = ClusterCa.create("tests");

    private TestTls() {
    }

    /** {@code env} plus C93_TLS_DIR holding a certificate for its node, when it's a clustered node. */
    static Map<String, String> secure(Map<String, String> env) {
        if (!"true".equals(env.get("C93_CLUSTERED")) || env.containsKey("C93_TLS_DIR")
                || "off".equals(env.get("C93_CLUSTER_TLS"))) {
            return env;
        }
        Map<String, String> secured = new HashMap<>(env);
        secured.put("C93_TLS_DIR", directoryFor(env.get("C93_NODE_ID"), CA).toString());
        return secured;
    }

    /** A fresh TLS directory with a certificate for {@code nodeId} from {@code ca}. */
    static Path directoryFor(String nodeId, ClusterCa ca) {
        try {
            Path dir = Files.createTempDirectory("cluster93-tls-" + nodeId + "-");
            ca.issueNode(nodeId, List.of("127.0.0.1", "localhost")).write(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static NodeIdentity identity(String nodeId) {
        return CA.issueNode(nodeId, List.of("127.0.0.1", "localhost"));
    }
}
