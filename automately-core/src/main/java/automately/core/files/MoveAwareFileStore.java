package automately.core.files;

import automately.core.identity.Records;
import automately.core.runtime.BodyStream;
import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The file store as everyone but {@link FileTree} sees it (D-082): while a folder operation runs, reads show its
 * finished result, so nobody sees a folder half-moved, half-copied or half-deleted.
 * <ul>
 *   <li><b>move</b>: the source is gone at once, and the target shows every file, moved yet or not</li>
 *   <li><b>copy</b>: the target shows the whole copy at once</li>
 *   <li><b>delete</b>: the folder is gone at once</li>
 * </ul>
 * Writes that touch either folder are refused with {@code busy} until the operation ends. It reads the tenant's journal
 * record (one key lookup) on each call; {@link FileTree} works on the store beneath, with the real paths.
 */
public final class MoveAwareFileStore implements FileStore {

    private record Op(String kind, String from, String to) {

        boolean hides(String path) {
            return (kind.equals("move") || kind.equals("delete")) && within(path, from);
        }

        boolean redirects(String path) {
            return to != null && (kind.equals("move") || kind.equals("copy")) && within(path, to);
        }

        boolean touches(String path) {
            return within(path, from) || (to != null && within(path, to));
        }

        /** Where a path under the target is now, if it hasn't been moved or copied yet. */
        String source(String path) {
            return from + path.substring(to.length());
        }

        String describe() {
            return kind.equals("delete") ? from + " is being deleted" : from + " is being " + (kind.equals("move") ? "moved" : "copied") + " to " + to;
        }
    }

    private final FileStore inner;
    private final Records ops;

    public MoveAwareFileStore(FileStore inner, Records ops) {
        this.inner = inner;
        this.ops = ops;
    }

    static boolean within(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private Future<Op> op(String tenant) {
        return ops.get(tenant).map(json -> json == null ? null : new Op(json.getString("op"), json.getString("from"), json.getString("to")));
    }

    /** Refuses a write that touches a folder being moved, copied or deleted. */
    private Future<Void> guard(String tenant, String... paths) {
        return op(tenant).compose(op -> {
            if (op != null) {
                for (String path : paths) {
                    if (op.touches(path)) {
                        return Future.failedFuture(new HostException("Error", "busy", op.describe() + "; try again when it finishes"));
                    }
                }
            }
            return Future.succeededFuture();
        });
    }

    // --- reads, as they'll be once the operation ends ---

    @Override
    public Future<FileInfo> stat(String tenant, String path) {
        return op(tenant).compose(op -> {
            if (op == null) {
                return inner.stat(tenant, path);
            }
            if (op.hides(path)) {
                return Future.succeededFuture();
            }
            if (op.redirects(path)) {
                return inner.stat(tenant, path).compose(there -> there != null ? Future.succeededFuture(there)
                        : inner.stat(tenant, op.source(path)).map(source -> source == null ? null : new FileInfo(tenant, path, source.fileId(),
                        source.size(), source.contentType(), source.etag(), source.backend(), source.createdAt())));
            }
            return inner.stat(tenant, path);
        });
    }

    @Override
    public Future<JsonObject> statEntry(String tenant, String path) {
        return op(tenant).compose(op -> {
            if (op == null) {
                return inner.statEntry(tenant, path);
            }
            if (op.hides(path)) {
                return Future.succeededFuture();
            }
            if (op.redirects(path)) {
                return inner.statEntry(tenant, path).compose(there -> there != null ? Future.succeededFuture(there)
                        : inner.statEntry(tenant, op.source(path)).map(source -> source == null ? null : source.copy().put("path", path)
                        .put("name", Paths.name(path))));
            }
            return inner.statEntry(tenant, path);
        });
    }

    @Override
    public Future<JsonObject> listDir(String tenant, String dir, int limit, String cursor) {
        return op(tenant).compose(op -> {
            if (op == null) {
                return inner.listDir(tenant, dir, limit, cursor);
            }
            if (op.hides(dir)) {
                return Future.failedFuture(new HostException("Error", "not_found", "No such directory: " + dir));
            }
            if (op.redirects(dir)) {
                return merged(tenant, dir, op.source(dir), limit, cursor);
            }
            String hiddenName = !op.kind().equals("copy") && Paths.parent(op.from()).equals(dir) ? Paths.name(op.from()) : null;
            return inner.listDir(tenant, dir, limit, cursor).map(page -> {
                if (hiddenName == null) {
                    return page;
                }
                JsonArray kept = new JsonArray();
                page.getJsonArray("entries").forEach(e -> {
                    if (!hiddenName.equals(((JsonObject) e).getString("name"))) {
                        kept.add(e);
                    }
                });
                return page.copy().put("entries", kept);
            });
        });
    }

    /** A target folder as it will be: what's there already, plus what hasn't arrived from the source yet. */
    private Future<JsonObject> merged(String tenant, String dir, String source, int limit, String cursor) {
        return pageOrEmpty(tenant, dir, limit + 1, cursor).compose(here -> pageOrEmpty(tenant, source, limit + 1, cursor).map(there -> {
            Map<String, JsonObject> byName = new LinkedHashMap<>();
            there.forEach(e -> byName.put(e.getString("name"), e.copy().put("path", dir.isEmpty() ? e.getString("name") : dir + "/" + e.getString("name"))));
            here.forEach(e -> byName.put(e.getString("name"), e));
            List<JsonObject> entries = new ArrayList<>(byName.values());
            entries.sort(Comparator.comparing(e -> e.getString("name")));
            boolean more = entries.size() > limit;
            List<JsonObject> page = more ? entries.subList(0, limit) : entries;
            return new JsonObject().put("path", dir).put("entries", new JsonArray(List.copyOf(page)))
                    .put("cursor", more ? page.getLast().getString("name") : null);
        }));
    }

    private Future<List<JsonObject>> pageOrEmpty(String tenant, String dir, int limit, String cursor) {
        return inner.listDir(tenant, dir, limit, cursor)
                .map(page -> page.getJsonArray("entries").stream().map(JsonObject.class::cast).toList())
                .recover(err -> err instanceof HostException host && host.code().equals("not_found")
                        ? Future.succeededFuture(List.of())
                        : Future.failedFuture(err));
    }

    /** The prefix listing can't be shown as it will be page by page, so it waits while a folder it covers is changing. */
    @Override
    public Future<JsonObject> list(String tenant, String prefix, int limit, String cursor) {
        return op(tenant).compose(op -> {
            if (op != null && (overlaps(prefix, op.from()) || (op.to() != null && overlaps(prefix, op.to())))) {
                return Future.failedFuture(new HostException("Error", "busy", op.describe() + "; list again when it finishes"));
            }
            return inner.list(tenant, prefix, limit, cursor);
        });
    }

    private static boolean overlaps(String prefix, String folder) {
        return folder.startsWith(prefix) || prefix.startsWith(folder + "/") || prefix.equals(folder);
    }

    @Override
    public BodyStream open(FileInfo file, long start, long endInclusive) {
        // Versions are read by their blob, wherever their row is now.
        return inner.open(file, start, endInclusive);
    }

    // --- writes wait for a folder operation they'd touch ---

    @Override
    public Future<FileWriter> create(String tenant, String path, String contentType, Room room) {
        return guard(tenant, path).compose(v -> inner.create(tenant, path, contentType, room));
    }

    @Override
    public Future<Void> ensureRoom(String tenant, String path, Room room, long bytes) {
        return inner.ensureRoom(tenant, path, room, bytes);
    }

    @Override
    public Future<Boolean> delete(String tenant, String path) {
        return guard(tenant, path).compose(v -> inner.delete(tenant, path));
    }

    @Override
    public Future<Boolean> mkdir(String tenant, String path, boolean parents) {
        return guard(tenant, path).compose(v -> inner.mkdir(tenant, path, parents));
    }

    @Override
    public Future<Boolean> rmdir(String tenant, String path) {
        return guard(tenant, path).compose(v -> inner.rmdir(tenant, path));
    }

    @Override
    public Future<FileInfo> copy(String tenant, String from, String to, boolean overwrite) {
        return guard(tenant, from, to).compose(v -> inner.copy(tenant, from, to, overwrite));
    }

    @Override
    public Future<FileInfo> move(String tenant, String from, String to, boolean overwrite) {
        return guard(tenant, from, to).compose(v -> inner.move(tenant, from, to, overwrite));
    }

    // --- accounting, which doesn't depend on names ---

    @Override
    public Future<Long> usage(String tenant) {
        return inner.usage(tenant);
    }

    @Override
    public Future<Long> usageIn(String tenant, List<String> backends) {
        return inner.usageIn(tenant, backends);
    }

    @Override
    public Future<Long> countIn(String backend) {
        return inner.countIn(backend);
    }

    @Override
    public Future<Integer> collectGarbage(long now) {
        return inner.collectGarbage(now);
    }
}
