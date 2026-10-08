package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

@Command(name = "files", description = "Your files: upload, download, list, delete, and sync a folder (a static site).",
        subcommands = {FilesCommand.Put.class, FilesCommand.Get.class, FilesCommand.Ls.class, FilesCommand.Mkdir.class, FilesCommand.Rmdir.class,
                FilesCommand.Rm.class, FilesCommand.Sync.class})
final class FilesCommand extends Base {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"), Map.entry("xml", "application/xml"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"),
            Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"), Map.entry("wasm", "application/wasm"));

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "application/octet-stream" : TYPES.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    /** The server's ETag for these bytes: their quoted SHA-256 (D-040). */
    static String etag(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha256())) {
            in.transferTo(OutputStream.nullOutputStream());
            return "\"" + HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest()) + "\"";
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every file under a prefix, following cursors: path to its JSON. */
    static Map<String, JsonNode> listAll(Api api, String prefix) {
        Map<String, JsonNode> all = new LinkedHashMap<>();
        String cursor = null;
        do {
            JsonNode page = api.get("/v1/files?limit=1000&prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8)
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            page.path("files").forEach(file -> all.put(file.path("path").asText(), file));
            cursor = page.hasNonNull("cursor") ? page.get("cursor").asText() : null;
        } while (cursor != null);
        return all;
    }

    @Command(name = "put", description = "Upload a file (streamed; any size up to your quota).")
    static final class Put extends Base {
        @Parameters(index = "0", paramLabel = "LOCAL")
        Path local;

        @Parameters(index = "1", paramLabel = "REMOTE", arity = "0..1", description = "Defaults to the file's name.")
        String remote;

        @Override
        int execute() {
            String path = remote != null ? remote : local.getFileName().toString();
            JsonNode stored = api().upload("/v1/files/" + Api.filePath(path), local, contentType(local));
            if (!printedJson(stored)) {
                out().println("Uploaded " + path + " (" + Json.text(stored, "size") + " bytes, " + Json.text(stored, "contentType") + ")");
            }
            return 0;
        }
    }

    @Command(name = "get", description = "Download a file (to stdout with -).")
    static final class Get extends Base {
        @Parameters(index = "0", paramLabel = "REMOTE")
        String remote;

        @Parameters(index = "1", paramLabel = "LOCAL", arity = "0..1", description = "Defaults to the file's name; - for stdout.")
        String local;

        @Override
        int execute() throws IOException {
            try (InputStream in = api().download("/v1/files/" + Api.filePath(remote))) {
                if ("-".equals(local)) {
                    in.transferTo(out());
                    out().flush();
                    return 0;
                }
                Path target = Path.of(local != null ? local : remote.substring(remote.lastIndexOf('/') + 1));
                long bytes = Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                err().println("Saved " + target + " (" + bytes + " bytes)");
            }
            return 0;
        }
    }

    @Command(name = "ls", description = "A folder, one level (folders end in /); -R lists every file under it, by path.")
    static final class Ls extends Base {
        @Parameters(paramLabel = "DIR", arity = "0..1", defaultValue = "")
        String prefix;

        @Option(names = {"-R", "--recursive"}, description = "Every file under DIR (or under a path prefix), at any depth.")
        boolean recursive;

        @Override
        int execute() {
            if (!recursive) {
                return listLevel();
            }
            Map<String, JsonNode> files = listAll(api(), prefix);
            if (json()) {
                Output.json(out(), Json.array().addAll(files.values()));
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            files.values().forEach(f -> rows.add(List.of(Json.text(f, "path"), Json.text(f, "size"), Json.text(f, "contentType"),
                    Output.time(f, "createdAt"))));
            Output.table(out(), List.of("PATH", "SIZE", "TYPE", "UPDATED"), rows);
            return 0;
        }

        private int listLevel() {
            String dir = prefix.replaceAll("^/+|/+$", "");
            List<JsonNode> entries = new ArrayList<>();
            String cursor = null;
            do {
                JsonNode page = api().get((dir.isEmpty() ? "/v1/dirs" : "/v1/dirs/" + Api.filePath(dir)) + "?limit=1000"
                        + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
                page.path("entries").forEach(entries::add);
                cursor = Json.text(page, "cursor").isEmpty() ? null : Json.text(page, "cursor");
            } while (cursor != null);
            if (json()) {
                Output.json(out(), Json.array().addAll(entries));
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            entries.forEach(e -> {
                boolean folder = Json.text(e, "kind").equals("dir");
                rows.add(List.of(Json.text(e, "name") + (folder ? "/" : ""), folder ? "" : Json.text(e, "size"), Json.text(e, "contentType"),
                        Output.time(e, "createdAt")));
            });
            Output.table(out(), List.of("NAME", "SIZE", "TYPE", "UPDATED"), rows);
            return 0;
        }
    }

    @Command(name = "mkdir", description = "Make a folder, and any missing above it.")
    static final class Mkdir extends Base {
        @Parameters(paramLabel = "DIR")
        String dir;

        @Override
        int execute() {
            api().put("/v1/dirs/" + Api.filePath(dir.replaceAll("^/+|/+$", "")), Json.object());
            out().println("Made " + dir + "/");
            return 0;
        }
    }

    @Command(name = "rmdir", description = "Remove an empty folder.")
    static final class Rmdir extends Base {
        @Parameters(paramLabel = "DIR")
        String dir;

        @Override
        int execute() {
            api().delete("/v1/dirs/" + Api.filePath(dir.replaceAll("^/+|/+$", "")));
            out().println("Removed " + dir + "/");
            return 0;
        }
    }

    @Command(name = "rm", description = "Delete a file.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "REMOTE")
        String remote;

        @Override
        int execute() {
            api().delete("/v1/files/" + Api.filePath(remote));
            out().println("Deleted " + remote + ".");
            return 0;
        }
    }

    /** Makes a prefix mirror a folder: new and changed files go up, compared by ETag; --delete removes the rest. */
    @Command(name = "sync", description = "Upload a folder's new and changed files under a prefix (e.g. a static site).")
    static final class Sync extends Base {
        @Parameters(index = "0", paramLabel = "DIR")
        Path dir;

        @Parameters(index = "1", paramLabel = "PREFIX", arity = "0..1", defaultValue = "", description = "e.g. site/")
        String prefix;

        @Option(names = "--delete", description = "Also delete remote files under the prefix that aren't in the folder.")
        boolean delete;

        @Option(names = "--dry-run", description = "Show what would change without changing anything.")
        boolean dryRun;

        @Override
        int execute() throws IOException {
            if (!Files.isDirectory(dir)) {
                throw new CliException(dir + " is not a folder", 2);
            }
            String base = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
            Map<String, Path> local = new TreeMap<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(file -> local.put(base + dir.relativize(file).toString().replace('\\', '/'), file));
            }
            Api api = api();
            Map<String, JsonNode> remote = listAll(api, base);
            int uploaded = 0;
            int unchanged = 0;
            int deleted = 0;
            for (Map.Entry<String, Path> entry : local.entrySet()) {
                JsonNode existing = remote.get(entry.getKey());
                if (existing != null && Json.text(existing, "etag").equals(etag(entry.getValue()))) {
                    unchanged++;
                    continue;
                }
                out().println((dryRun ? "would upload " : "upload ") + entry.getKey());
                if (!dryRun) {
                    api.upload("/v1/files/" + Api.filePath(entry.getKey()), entry.getValue(), contentType(entry.getValue()));
                }
                uploaded++;
            }
            if (delete) {
                for (String path : remote.keySet()) {
                    if (!local.containsKey(path)) {
                        out().println((dryRun ? "would delete " : "delete ") + path);
                        if (!dryRun) {
                            api.delete("/v1/files/" + Api.filePath(path));
                        }
                        deleted++;
                    }
                }
            }
            out().println((dryRun ? "Would upload " : "Uploaded ") + uploaded + ", unchanged " + unchanged
                    + (delete ? ", " + (dryRun ? "would delete " : "deleted ") + deleted : "") + ".");
            return 0;
        }
    }
}
