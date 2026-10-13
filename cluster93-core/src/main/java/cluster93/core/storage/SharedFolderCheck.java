package cluster93.core.storage;

import cluster93.core.node.NodeInfo;
import cluster93.core.node.NodeRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Whether every live node sees the same {@code C93_DISK_ROOT} (D-057). Any node may serve any file, so a disk
 * provider is only safe on shared storage (NFS and the like), which is also what makes it redundant. Each node keeps a
 * marker there; the folder counts as shared while every live node's marker is visible and fresh.
 */
public final class SharedFolderCheck {

    static final Duration EVERY = Duration.ofSeconds(10);
    static final Duration FRESH = EVERY.multipliedBy(3);

    private final Vertx vertx;
    private final FileSystem fs;
    private final Path markers;
    private final String instanceId;
    private final NodeRegistry nodes;
    private final java.util.concurrent.atomic.AtomicBoolean shared;

    /** {@code shared} is kept up to date with the answer; tenant disk providers read it (TenantBlobStores). */
    public SharedFolderCheck(Vertx vertx, Path root, String instanceId, NodeRegistry nodes, java.util.concurrent.atomic.AtomicBoolean shared) {
        this.shared = shared;
        this.vertx = vertx;
        this.fs = vertx.fileSystem();
        this.markers = root.resolve(".cluster93-nodes");
        this.instanceId = instanceId;
        this.nodes = nodes;
    }

    /** Writes this node's marker and checks the others, now and every {@link #EVERY}. */
    public Future<Void> start() {
        vertx.setPeriodic(EVERY.toMillis(), id -> check());
        return check();
    }

    Future<Void> check() {
        long now = System.currentTimeMillis();
        return fs.mkdirs(markers.toString())
                .compose(v -> fs.writeFile(markers.resolve(instanceId).toString(), Buffer.buffer(String.valueOf(now))))
                .compose(v -> nodes.liveNodes())
                .compose(live -> Future.all(live.stream().map(NodeInfo::instanceId).map(this::fresh).toList())
                        .map(all -> all.list().stream().allMatch(Boolean.TRUE::equals)))
                .onSuccess(shared::set)
                .onFailure(err -> shared.set(false))
                .mapEmpty();
    }

    private Future<Boolean> fresh(String node) {
        String marker = markers.resolve(node).toString();
        return fs.exists(marker).compose(exists -> !exists ? Future.succeededFuture(false)
                : fs.readFile(marker).map(content -> {
                    try {
                        return System.currentTimeMillis() - Long.parseLong(content.toString().trim()) < FRESH.toMillis();
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }));
    }
}
