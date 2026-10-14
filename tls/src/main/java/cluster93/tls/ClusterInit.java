package cluster93.tls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creating a cluster's certificates and inviting nodes (D-067), shared by the CLI ({@code c93 cluster init},
 * {@code c93 node invite}) and the server image (the compose file's one-shot {@code init} service).
 */
public final class ClusterInit {

    private ClusterInit() {
    }

    /** What {@link #init} wrote. */
    public record Result(ClusterCa ca, Map<String, String> joinLines) {
    }

    /**
     * Creates a CA and a certificate for each node in {@code out}: {@code ca.key} (keep it; the cluster never stores
     * it), {@code ca.crt}, and {@code <node>.join}, one line per node to start it with. A node's hosts are its id plus
     * any {@code extraHosts} given for it.
     */
    public static Result init(String name, List<String> nodeIds, Map<String, List<String>> extraHosts, Path out) {
        try {
            if (Files.exists(out.resolve(ClusterCa.KEY_FILE))) {
                throw new IllegalStateException(out.resolve(ClusterCa.KEY_FILE) + " exists already; a cluster has one CA. Use `c93 node invite` "
                        + "to add nodes, or choose another directory");
            }
            Files.createDirectories(out);
            ClusterCa ca = ClusterCa.create(name);
            ca.write(out);
            Map<String, String> lines = new java.util.LinkedHashMap<>();
            for (String id : nodeIds) {
                List<String> hosts = new ArrayList<>(List.of(id));
                hosts.addAll(extraHosts.getOrDefault(id, List.of()));
                String line = NodeBundle.bundle(ca.issueNode(id, hosts));
                Secrets.writePrivate(out.resolve(id + ".join"), line + "\n");
                lines.put(id, line);
            }
            return new Result(ca, lines);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A join line for {@code nodeId}: a bundle with a new key, or a certificate only for a key the node made itself. */
    public static String invite(ClusterCa ca, String nodeId, List<String> hosts, String publicKeyLine) {
        List<String> all = new ArrayList<>(List.of(nodeId));
        all.addAll(hosts);
        if (publicKeyLine == null) {
            return NodeBundle.bundle(ca.issueNode(nodeId, all));
        }
        return NodeBundle.certificateOnly(ca.issue(nodeId, all, NodeBundle.publicKeyFromLine(publicKeyLine), ClusterCa.NODE_VALIDITY), ca.certificate());
    }

    /**
     * For images without the CLI: {@code java -cp c93.jar cluster93.tls.ClusterInit <out> <node>...}. Writes
     * {@code <out>/<node>/} as each node's TLS directory too, so a container can mount it, and exits quietly if the
     * CA exists already (re-running compose).
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ClusterInit <out dir> <node id>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        if (Files.exists(out.resolve(ClusterCa.KEY_FILE))) {
            System.out.println("The cluster CA exists in " + out + "; nothing to do.");
            return;
        }
        List<String> ids = List.of(args).subList(1, args.length);
        Result result = init("compose", ids, Map.of(), out);
        for (String id : ids) {
            NodeBundle.decode(result.joinLines().get(id)).identity(null).write(out.resolve(id));
        }
        System.out.println("Created the cluster CA (" + result.ca().fingerprint() + ") and certificates for " + ids + " in " + out);
    }
}
