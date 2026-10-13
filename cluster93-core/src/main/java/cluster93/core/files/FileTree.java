package cluster93.core.files;

import cluster93.core.identity.Records;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Moving, copying and deleting folders (D-077): many file operations, made safe for a cluster. A tree operation first
 * writes a journal record (what it's doing) that also holds a lease, one per tenant, so two never interleave. Every
 * step is idempotent, so if the node doing it dies, another node finds the record once the lease runs out and runs the
 * operation again from the start, skipping what's already done. Files are still moved or copied one at a time, so a
 * reader may briefly see part of a moving folder under each name; each file is always whole at one of them.
 */
public final class FileTree {

    public static final Records.Kind OPS = new Records.Kind("FILE_TREE_OPS", List.of());
    /** The most entries (files and folders) one tree operation takes on. */
    public static final int MAX_ENTRIES = 100_000;
    static final Duration LEASE = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(FileTree.class);

    private final Vertx vertx;
    private final FileStore files;
    private final Records ops;
    private final String holder;

    public FileTree(Vertx vertx, FileStore files, Records ops, String holder) {
        this.vertx = vertx;
        this.files = files;
        this.ops = ops;
        this.holder = holder;
    }

    /** Resumes, every few seconds, any operation whose node stopped renewing its lease. */
    public void start() {
        vertx.setPeriodic(LEASE.toMillis() / 3, id -> resumeAbandoned().onFailure(err -> LOG.warn("Resuming folder operations failed: {}", err.getMessage())));
    }

    /** Moves a file or a folder. Completes with {@code {op, from, to, files, dirs}}. */
    public Future<JsonObject> move(String tenant, String from, String to, boolean overwrite) {
        return start(tenant, "move", from, to, overwrite);
    }

    public Future<JsonObject> copy(String tenant, String from, String to, boolean overwrite) {
        return start(tenant, "copy", from, to, overwrite);
    }

    /** Deletes a folder and everything in it. */
    public Future<JsonObject> delete(String tenant, String path) {
        return start(tenant, "delete", path, null, false);
    }

    private Future<JsonObject> start(String tenant, String op, String from, String to, boolean overwrite) {
        return files.statEntry(tenant, from).compose(source -> {
            if (source == null) {
                return Future.failedFuture(new HostException("Error", "not_found", "No such file or folder: " + from));
            }
            if (!"dir".equals(source.getString("kind"))) {
                // A single file needs no journal: the store's own move and copy are safe on their own. It mustn't
                // touch a folder that's being moved, copied or deleted, though (D-082).
                return clear(tenant, from, to).compose(v -> switch (op) {
                    case "move" -> files.move(tenant, from, to, overwrite).map(f -> result(op, from, to, 1, 0));
                    case "copy" -> files.copy(tenant, from, to, overwrite).map(f -> result(op, from, to, 1, 0));
                    default -> files.delete(tenant, from).map(f -> result(op, from, null, 1, 0));
                });
            }
            if (from.isEmpty()) {
                return Future.failedFuture(HostException.invalid("The top level can't be moved, copied or deleted as a whole"));
            }
            if (to != null && (to.equals(from) || to.startsWith(from + "/"))) {
                return Future.failedFuture(HostException.invalid("A folder can't go inside itself: " + from + " → " + to));
            }
            Future<Void> free = to == null ? Future.succeededFuture() : files.statEntry(tenant, to).compose(target -> target == null
                    ? Future.succeededFuture()
                    : "file".equals(target.getString("kind"))
                    ? Future.failedFuture(new HostException("Error", "not_a_directory", to + " is a file"))
                    : overwrite ? Future.succeededFuture()
                    : Future.failedFuture(new HostException("Error", "already_exists", to + " already exists; overwrite to merge into it")));
            return free.compose(ok -> journal(tenant, op, from, to, overwrite));
        });
    }

    /** Fails {@code busy} if a folder operation in progress covers either path. */
    private Future<Void> clear(String tenant, String from, String to) {
        return ops.get(tenant).compose(running -> {
            if (running == null) {
                return Future.succeededFuture();
            }
            String busyFrom = running.getString("from");
            String busyTo = running.getString("to");
            for (String path : new String[]{from, to}) {
                if (path != null && (MoveAwareFileStore.within(path, busyFrom) || (busyTo != null && MoveAwareFileStore.within(path, busyTo)))) {
                    return Future.failedFuture(new HostException("Error", "busy", "A folder operation on " + busyFrom
                            + " is running; try again when it finishes"));
                }
            }
            return Future.succeededFuture();
        });
    }

    /** Writes the journal, which is also the tenant's lease, and runs the operation. */
    private Future<JsonObject> journal(String tenant, String op, String from, String to, boolean overwrite) {
        long now = System.currentTimeMillis();
        JsonObject journal = new JsonObject().put("tenant", tenant).put("op", op).put("from", from).put("to", to).put("overwrite", overwrite)
                .put("holder", holder).put("until", now + LEASE.toMillis()).put("startedAt", now);
        return ops.putIfAbsent(tenant, journal).compose(claimed -> claimed
                ? run(journal)
                : Future.failedFuture(new HostException("Error", "busy",
                        "Another folder operation is running for this tenant; try again when it finishes")));
    }

    /** Runs (or reruns) a journaled operation to the end, then removes its journal. */
    private Future<JsonObject> run(JsonObject journal) {
        String tenant = journal.getString("tenant");
        String op = journal.getString("op");
        String from = journal.getString("from");
        String to = journal.getString("to");
        boolean overwrite = journal.getBoolean("overwrite", false);
        List<String> dirs = new ArrayList<>();
        List<String> filePaths = new ArrayList<>();
        return collect(tenant, from, dirs, filePaths)
                .compose(v -> dirs.size() + filePaths.size() > MAX_ENTRIES
                        ? Future.failedFuture(HostException.limit(from + " holds more than " + MAX_ENTRIES + " files and folders; do it in parts"))
                        : Future.succeededFuture())
                .compose(v -> switch (op) {
                    case "delete" -> deleteAll(tenant, dirs, filePaths);
                    default -> transfer(tenant, op, from, to, overwrite, dirs, filePaths);
                })
                .compose(v -> ops.remove(tenant))
                .map(v -> result(op, from, to, filePaths.size(), dirs.size()))
                .recover(err -> ops.remove(tenant).compose(v -> Future.failedFuture(err)));
    }

    /** Every folder (parents before children) and file under {@code dir}, {@code dir} included. */
    private Future<Void> collect(String tenant, String dir, List<String> dirs, List<String> filePaths) {
        dirs.add(dir);
        return collectPage(tenant, dir, null, dirs, filePaths);
    }

    private Future<Void> collectPage(String tenant, String dir, String cursor, List<String> dirs, List<String> filePaths) {
        if (dirs.size() + filePaths.size() > MAX_ENTRIES) {
            return Future.succeededFuture();
        }
        return files.listDir(tenant, dir, 1000, cursor).compose(page -> {
            Future<Void> deeper = Future.succeededFuture();
            for (Object item : page.getJsonArray("entries")) {
                JsonObject entry = (JsonObject) item;
                if ("dir".equals(entry.getString("kind"))) {
                    deeper = deeper.compose(v -> collect(tenant, entry.getString("path"), dirs, filePaths));
                } else {
                    filePaths.add(entry.getString("path"));
                }
            }
            String next = page.getString("cursor");
            return deeper.compose(v -> next == null ? Future.succeededFuture() : collectPage(tenant, dir, next, dirs, filePaths));
        }).recover(err -> err instanceof HostException host && host.code().equals("not_found")
                ? Future.succeededFuture() // gone already: a rerun after the source was removed
                : Future.failedFuture(err));
    }

    private Future<Void> transfer(String tenant, String op, String from, String to, boolean overwrite, List<String> dirs, List<String> filePaths) {
        Future<Void> all = files.statEntry(tenant, to).compose(target -> target != null && "file".equals(target.getString("kind"))
                ? Future.failedFuture(new HostException("Error", "not_a_directory", to + " is a file"))
                : Future.succeededFuture());
        for (String dir : dirs) {
            all = all.compose(v -> files.mkdir(tenant, rebase(dir, from, to), true)).mapEmpty();
        }
        int[] done = {0};
        for (String path : filePaths) {
            String target = rebase(path, from, to);
            all = all.compose(v -> ("move".equals(op) ? files.move(tenant, path, target, overwrite) : files.copy(tenant, path, target, overwrite))
                    .<Void>mapEmpty()
                    .recover(err -> alreadyDone(tenant, op, path, target, err)))
                    .compose(v -> ++done[0] % 200 == 0 ? renew(tenant) : Future.succeededFuture());
        }
        if ("move".equals(op)) {
            // Emptied folders go last, deepest first.
            for (int i = dirs.size() - 1; i >= 0; i--) {
                String dir = dirs.get(i);
                all = all.compose(v -> files.rmdir(tenant, dir).<Void>mapEmpty().recover(err -> {
                    LOG.info("Left {} in place after moving it: {}", dir, err.getMessage());
                    return Future.succeededFuture();
                }));
            }
        }
        return all;
    }

    /**
     * On a rerun, a step may have happened already: the source is gone and the target is there (move), or the target
     * already names the same blob (copy). Anything else is a real failure.
     */
    private Future<Void> alreadyDone(String tenant, String op, String path, String target, Throwable err) {
        if (!(err instanceof HostException host)) {
            return Future.failedFuture(err);
        }
        if ("move".equals(op) && host.code().equals("not_found")) {
            return files.stat(tenant, target).compose(there -> there != null ? Future.succeededFuture() : Future.failedFuture(err));
        }
        if ("copy".equals(op) && host.code().equals("already_exists")) {
            return files.stat(tenant, path).compose(source -> files.stat(tenant, target).compose(there ->
                    source != null && there != null && source.fileId().equals(there.fileId()) ? Future.succeededFuture() : Future.failedFuture(err)));
        }
        return Future.failedFuture(err);
    }

    private Future<Void> deleteAll(String tenant, List<String> dirs, List<String> filePaths) {
        Future<Void> all = Future.succeededFuture();
        int[] done = {0};
        for (String path : filePaths) {
            all = all.compose(v -> files.delete(tenant, path)).compose(v -> ++done[0] % 200 == 0 ? renew(tenant) : Future.succeededFuture());
        }
        for (int i = dirs.size() - 1; i >= 0; i--) {
            String dir = dirs.get(i);
            all = all.compose(v -> files.rmdir(tenant, dir)).mapEmpty();
        }
        return all;
    }

    private Future<Void> renew(String tenant) {
        return ops.update(tenant, json -> holder.equals(json.getString("holder"))
                        ? Optional.of(json.put("until", System.currentTimeMillis() + LEASE.toMillis()))
                        : Optional.empty())
                .compose(updated -> updated.isPresent() ? Future.succeededFuture()
                        : Future.failedFuture(new HostException("Error", "busy", "Another node took over this folder operation")));
    }

    /** Claims and reruns operations whose holder stopped renewing. */
    Future<Integer> resumeAbandoned() {
        long now = System.currentTimeMillis();
        return ops.all().compose(rows -> {
            Future<Integer> resumed = Future.succeededFuture(0);
            for (JsonObject row : rows) {
                if (row.getLong("until", 0L) >= now) {
                    continue;
                }
                String tenant = row.getString("tenant");
                resumed = resumed.compose(count -> ops.update(tenant, json -> json.getLong("until", 0L) < now
                                ? Optional.of(json.put("holder", holder).put("until", now + LEASE.toMillis()))
                                : Optional.empty())
                        .compose(claimed -> claimed.isEmpty() ? Future.succeededFuture(count) : run(claimed.get()).map(r -> {
                            LOG.info("Finished a {} of {} that another node started", r.getString("op"), r.getString("from"));
                            return count + 1;
                        }).recover(err -> {
                            LOG.warn("Couldn't finish a folder operation for {}: {}", tenant, err.getMessage());
                            return Future.succeededFuture(count);
                        })));
            }
            return resumed;
        });
    }

    static String rebase(String path, String from, String to) {
        return path.equals(from) ? to : to + path.substring(from.length());
    }

    private static JsonObject result(String op, String from, String to, int fileCount, int dirCount) {
        return new JsonObject().put("op", op).put("from", from).put("to", to).put("files", fileCount).put("dirs", dirCount);
    }
}
