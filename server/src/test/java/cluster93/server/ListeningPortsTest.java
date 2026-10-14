package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A clustered node listens only where it should (D-065). Ignite's thin-client/JDBC/ODBC connector (10800) and REST
 * command protocol (11211) are on by default, unauthenticated, and were found listening on every interface. Any new
 * listener fails this test until it's added here on purpose. Reads this JVM's own sockets from /proc, so Linux only.
 */
@EnabledOnOs(OS.LINUX)
class ListeningPortsTest {

    @Test
    void aClusteredNodeListensOnlyOnItsOwnPorts() throws Exception {
        Set<Listener> before = listeners();
        RunningNode node = await(Cluster93.start(ServerConfig.fromEnv(TestTls.secure(Map.ofEntries(
                Map.entry("C93_NODE_ID", "ports"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "2"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_HEARTBEAT_MS", "200"),
                Map.entry("C93_CLUSTERED", "true"),
                Map.entry("C93_CLUSTER_PORT", "33701"),
                Map.entry("C93_CLUSTER_MEMBERS", "127.0.0.1"))))));
        try {
            Set<Listener> opened = new TreeSet<>(listeners());
            opened.removeAll(before);
            Set<Integer> ports = new TreeSet<>();
            opened.forEach(listener -> ports.add(listener.port()));

            assertThat(ports).as("Ignite's client connector and REST protocol").doesNotContain(10800, 10801, 11211, 11212);
            Set<Integer> expected = Set.of(node.httpPort(), node.ingressPort(), node.metricsPort(), node.internalPort(), 33701, 33801);
            assertThat(ports).as("the node's own listeners, seen through /proc: " + opened).containsAll(expected);
            Set<Integer> unexpected = new TreeSet<>(ports);
            unexpected.removeAll(expected);
            // Besides those, only the clustered event bus, whose port is chosen at random.
            assertThat(unexpected).as("listeners besides the known ports: " + opened).hasSizeLessThanOrEqualTo(1);
            assertThat(opened.stream().filter(l -> ports.contains(l.port()) && !unexpected.contains(l.port())
                    && (l.port() == 33701 || l.port() == 33801)))
                    .as("Ignite binds to the advertised host, not every interface: " + opened)
                    .allMatch(l -> l.address().equals("127.0.0.1"));
        } finally {
            await(node.stop());
        }
    }

    /** One listening TCP socket of this process: its local address and port. */
    record Listener(String address, int port) implements Comparable<Listener> {
        @Override
        public int compareTo(Listener other) {
            return port != other.port ? Integer.compare(port, other.port) : address.compareTo(other.address);
        }
    }

    /** This process's listening sockets: socket inodes from /proc/self/fd, matched against /proc/net/tcp and tcp6. */
    static Set<Listener> listeners() throws IOException {
        Set<String> inodes = new HashSet<>();
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            fds.forEach(fd -> {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith("socket:[")) {
                        inodes.add(target.substring(8, target.length() - 1));
                    }
                } catch (IOException ignored) {
                    // The descriptor closed while we looked.
                }
            });
        }
        Set<Listener> listening = new TreeSet<>();
        for (String table : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
            for (String line : Files.readAllLines(Path.of(table)).stream().skip(1).toList()) {
                String[] f = line.trim().split("\\s+");
                // f[1] local address:port (hex), f[3] state (0A = LISTEN), f[9] inode
                if (f[3].equals("0A") && inodes.contains(f[9])) {
                    String[] local = f[1].split(":");
                    listening.add(new Listener(address(local[0]), Integer.parseInt(local[1], 16)));
                }
            }
        }
        return listening;
    }

    /** /proc's little-endian hex IPv4 (or IPv6) address as text; any-address becomes "*". */
    private static String address(String hex) {
        if (hex.matches("0+")) {
            return "*";
        }
        if (hex.length() == 8) {
            return Integer.parseInt(hex.substring(6, 8), 16) + "." + Integer.parseInt(hex.substring(4, 6), 16) + "."
                    + Integer.parseInt(hex.substring(2, 4), 16) + "." + Integer.parseInt(hex.substring(0, 2), 16);
        }
        // IPv4-mapped IPv6 (::ffff:a.b.c.d) keeps its last 32 bits in the same little-endian order.
        if (hex.startsWith("0000000000000000FFFF0000")) {
            return address(hex.substring(24));
        }
        return "[" + hex + "]";
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
