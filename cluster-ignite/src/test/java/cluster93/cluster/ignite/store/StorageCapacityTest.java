package cluster93.cluster.ignite.store;

import cluster93.cluster.ignite.IgniteCapacity;
import cluster93.cluster.ignite.IgniteCluster;
import cluster93.cluster.ignite.IgniteSettings;
import cluster93.core.files.FileInfo;
import cluster93.core.files.FileStore;
import cluster93.core.files.FileWriter;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.apache.ignite.Ignition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uploads are refused with "insufficient storage" before memory or disk fills, and the node keeps working (D-038). */
class StorageCapacityTest {

    @TempDir
    Path data;

    private Vertx vertx;

    @AfterEach
    void stop() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        TestIgnite.stopAllExceptShared();
    }

    @Test
    void anInMemoryNodeRefusesUploadsBeforeItsRegionFillsAndStaysUp() throws Exception {
        IgniteCluster cluster = IgniteCluster.start(new IgniteSettings("small", null, 25500, List.of("127.0.0.1"), 0, 128, Duration.ofSeconds(1), 1));
        IgniteStores.createTables(cluster);
        vertx = Vertx.vertx();
        IgniteStores stores = IgniteStores.open(vertx, cluster);
        FileStore files = stores.files(stores.chunks(IgniteCapacity.of(cluster, 0)));
        byte[] megabyte = new byte[1024 * 1024];

        // Keep uploading 4 MB files into a 128 MB region until the node says no.
        HostException refused = null;
        int stored = 0;
        for (int i = 0; i < 100 && refused == null; i++) {
            try {
                FileWriter writer = await(files.create("acme", "fill/" + i, null, cluster93.core.files.Room.fixed(Long.MAX_VALUE)));
                for (int mb = 0; mb < 4; mb++) {
                    await(writer.write(Buffer.buffer(megabyte)));
                }
                await(writer.finish());
                stored++;
            } catch (ExecutionException e) {
                refused = (HostException) e.getCause();
            }
        }

        assertThat(refused).as("refused before the region filled").isNotNull();
        assertThat(refused.code()).isEqualTo("insufficient_storage");
        assertThat(stored).isBetween(5, 30);
        // Still alive and serving: earlier files read back, and small writes elsewhere work.
        FileInfo first = await(files.stat("acme", "fill/0"));
        assertThat(first.size()).isEqualTo(4L * 1024 * 1024);
        await(stores.kv.set("acme", "default", "still", "alive", null));
        assertThat(await(stores.kv.get("acme", "default", "still"))).isEqualTo("alive");
    }

    @Test
    void aPersistentNodeRefusesUploadsWhenTheDiskIsLow() throws Exception {
        IgniteCluster cluster = IgniteCluster.start(new IgniteSettings("disk", data.resolve("disk"), 26500, List.of("127.0.0.1"), 0, 128,
                Duration.ofSeconds(1), 1));
        IgniteStores.createTables(cluster);
        vertx = Vertx.vertx();
        // Pretend the disk is nearly full by demanding more free space than any disk has.
        FileStore files = IgniteStores.open(vertx, cluster).files(IgniteStores.open(vertx, cluster).chunks(IgniteCapacity.of(cluster, Long.MAX_VALUE / 2)));

        assertThatThrownBy(() -> await(files.create("acme", "big.bin", null, cluster93.core.files.Room.fixed(Long.MAX_VALUE))))
                .cause().isInstanceOf(HostException.class).hasMessageContaining("disk left");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
