package cluster93.core.files;

import cluster93.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code disk} backend (D-056): one file per blob in a folder, {@code C93_FILES_DIR}. A single node can
 * use any folder; in a cluster, every node must mount the same one (NFS or similar), because any node may serve any
 * file. Writes go to {@code <id>.part}, are flushed to disk, then renamed into place, so a blob is complete or absent.
 */
public final class DiskBlobStore implements BlobStore {

    public static final String NAME = "disk";
    /** Free space is checked before the write and then every this many bytes. */
    static final long CHECK_EVERY = 16L * 1024 * 1024;

    private final String name;
    private final Vertx vertx;
    private final FileSystem fs;
    private final Path dir;
    private final long minFreeBytes;

    public DiskBlobStore(Vertx vertx, Path dir, long minFreeBytes) {
        this(NAME, vertx, dir, minFreeBytes);
    }

    /** {@code name} identifies this folder in file metadata (tenants' disk providers have their own, D-057). */
    public DiskBlobStore(String name, Vertx vertx, Path dir, long minFreeBytes) {
        this.name = name;
        this.vertx = vertx;
        this.fs = vertx.fileSystem();
        this.dir = dir.toAbsolutePath().normalize();
        this.minFreeBytes = minFreeBytes;
    }

    @Override
    public String name() {
        return name;
    }

    /** Blobs are spread over 256 subfolders by their id's first two characters, so no folder gets huge. */
    private Path path(String blobId) {
        return dir.resolve(blobId.substring(0, 2)).resolve(blobId);
    }

    private Path temporary(String blobId) {
        return dir.resolve(blobId.substring(0, 2)).resolve(blobId + ".part");
    }

    private Future<Void> ensureRoom() {
        return fs.fsProps(dir.toString()).compose(props -> props.usableSpace() < minFreeBytes
                ? Future.failedFuture(StorageCapacity.insufficient("less than " + (minFreeBytes >> 20) + " MB free in " + dir))
                : Future.succeededFuture());
    }

    @Override
    public Future<Void> check() {
        Path probe = dir.resolve(".cluster93-probe-" + java.util.UUID.randomUUID());
        return fs.mkdirs(dir.toString())
                .compose(v -> fs.writeFile(probe.toString(), Buffer.buffer("ok")))
                .compose(v -> fs.delete(probe.toString()))
                .recover(err -> Future.failedFuture(new IllegalStateException(
                        "C93_FILES_DIR " + dir + " isn't a writable folder: " + err.getMessage(), err)));
    }

    @Override
    public Future<BlobWriter> create(String blobId, String contentType) {
        Path temp = temporary(blobId);
        return fs.mkdirs(temp.getParent().toString())
                .compose(v -> ensureRoom())
                .compose(v -> fs.open(temp.toString(), new OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true)))
                .map(file -> new Writer(blobId, file));
    }

    @Override
    public BodyStream open(String blobId, long size, long start, long endInclusive) {
        long end = Math.min(endInclusive, size - 1);
        if (end < start) {
            return ReadStreamBody.empty();
        }
        AtomicReference<AsyncFile> opened = new AtomicReference<>();
        return new ReadStreamBody(() -> fs.open(path(blobId).toString(), new OpenOptions().setRead(true).setCreate(false))
                .recover(err -> Future.failedFuture(new IllegalStateException("File data is missing (it was replaced or deleted)", err)))
                .map(file -> {
                    opened.set(file);
                    return file.setReadBufferSize(64 * 1024).setReadPos(start).setReadLength(end - start + 1);
                }),
                () -> {
                    AsyncFile file = opened.get();
                    if (file != null) {
                        file.close();
                    }
                });
    }

    @Override
    public Future<Void> delete(String blobId) {
        return deleteIfThere(path(blobId)).compose(v -> deleteIfThere(temporary(blobId)));
    }

    private Future<Void> deleteIfThere(Path file) {
        return fs.exists(file.toString()).compose(exists -> exists ? fs.delete(file.toString()) : Future.succeededFuture());
    }

    private final class Writer implements BlobWriter {

        private final String blobId;
        private final AsyncFile file;
        private long sinceCheck;

        Writer(String blobId, AsyncFile file) {
            this.blobId = blobId;
            this.file = file;
        }

        @Override
        public Future<Void> write(Buffer data) {
            sinceCheck += data.length();
            Future<Void> room = Future.succeededFuture();
            if (sinceCheck >= CHECK_EVERY) {
                sinceCheck = 0;
                room = ensureRoom();
            }
            return room.compose(v -> file.write(data));
        }

        @Override
        public Future<Void> finish() {
            // Flushed to the disk, then renamed: a blob is complete or not there at all.
            return file.flush()
                    .compose(v -> file.close())
                    .compose(v -> fs.move(temporary(blobId).toString(), path(blobId).toString(),
                            new io.vertx.core.file.CopyOptions().setReplaceExisting(true).setAtomicMove(true)));
        }

        @Override
        public Future<Void> abort() {
            return file.close().recover(err -> Future.succeededFuture()).compose(v -> deleteIfThere(temporary(blobId)));
        }
    }
}
