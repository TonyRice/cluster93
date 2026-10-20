package cluster93.cluster.ignite;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The standalone-activation rules: a single node must activate itself instead of stalling on a baseline it never
 * wrote, and a data directory another identity wrote to must not be mistaken for this node's own store.
 */
class IgniteClusterTest {

    /** The node id the contract tests' shared node uses, which must survive this class untouched. */
    private static final String SHARED_CONTRACT_NODE = "cluster93-contract";

    @TempDir
    Path home;

    @AfterEach
    void stopEverything() {
        for (Ignite ignite : Ignition.allGrids()) {
            if (!ignite.name().equals(SHARED_CONTRACT_NODE)) {
                Ignition.stop(ignite.name(), true);
            }
        }
    }

    private static IgniteSettings settings(String nodeId, Path data, int port, int clusterSize) {
        return new IgniteSettings(nodeId, data, port, List.of("127.0.0.1"), 0, 128, Duration.ofSeconds(1), clusterSize);
    }

    @Test
    void onlyADirectoryForThisIdentityCountsAsAnExistingStore() {
        Path data = home.resolve("data");

        assertThat(IgniteCluster.nodeDatabasePresent(settings("local-dev", null, 24500, 1)))
                .as("an in-memory node has no store").isFalse();
        assertThat(IgniteCluster.nodeDatabasePresent(settings("local-master", data, 24500, 1)))
                .as("nothing has been written yet").isFalse();

        // Ignite names a node's local store after its masked consistent id (local-master -> local_master).
        createDirectories(data.resolve("db").resolve("local_master"));
        assertThat(IgniteCluster.nodeDatabasePresent(settings("local-master", data, 24500, 1))).isTrue();
        assertThat(IgniteCluster.nodeDatabasePresent(settings("local-dev", data, 24500, 1)))
                .as("the master's store is not the dev node's").isFalse();
    }

    /**
     * The bug this guards: dev testing starts (writing a {@code db/} under the shared data directory), then the
     * permanent master starts on the same directory. The master's own store is absent, but before the fix the mere
     * presence of {@code db/} cleared {@code firstStart} and it looped in {@code awaitActive} forever. It must
     * activate promptly instead.
     */
    @Test
    @Timeout(60)
    void aStandaloneNodeActivatesBesideAStoreAnotherIdentityWrote() throws Exception {
        Path data = home.resolve("shared");
        createDirectories(data.resolve("db")); // as if development testing had already started here

        CompletableFuture<IgniteCluster> starting =
                CompletableFuture.supplyAsync(() -> IgniteCluster.start(settings("local-master", data, 24600, 1)));
        IgniteCluster cluster = starting.get(45, TimeUnit.SECONDS);
        try {
            assertThat(cluster.active()).isTrue();
        } finally {
            cluster.stop();
        }
    }

    @Test
    @Timeout(30)
    void aHeldClusterPortStopsTheNodeWithAnActionableMessage() throws Exception {
        // Ignite's default is to take the next free port when the base is held, which leaves the node running where no
        // peer joins it: the peers fail with SSL errors against whatever holds the base (D-185). The node must refuse
        // to start instead, and say which setting and port are at fault. The members name their ports: that is the
        // statically addressed shape where moving is the bug (a bare host keeps the probe range, and is still found).
        int held = 24610;
        try (java.net.ServerSocket taken = new java.net.ServerSocket(held, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> IgniteCluster.start(new IgniteSettings("local-master", null, held,
                    List.of("127.0.0.1:" + held), 0, 128, Duration.ofSeconds(1), 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("C93_CLUSTER_PORT " + held)
                    .hasMessageContaining("discovery port " + held)
                    .hasMessageContaining("already in use");
        }
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
