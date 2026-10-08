package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Command(name = "packages", description = "Versioned JavaScript you publish once and import from scripts (D-083).",
        subcommands = {PackagesCommand.Publish.class, PackagesCommand.ListPackages.class, PackagesCommand.Info.class})
final class PackagesCommand extends Base {

    /** The server's limits on one version (D-083), checked here first so a publish fails before uploading. */
    static final int MAX_FILES = 1_000;
    static final long MAX_BYTES = 10L * 1024 * 1024;

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    /** A package's path in the API: a cluster package's slash is encoded with the rest. */
    static String path(String name) {
        return "/v1/packages/" + Api.segment(name);
    }

    @Command(name = "publish", description = {
            "Publish the project in DIR as a new version. Name and version come from automately.json, else package.json.",
            "The code and the npm packages it uses are bundled with the project's own esbuild (npm i -D esbuild) into plain ES modules;"
                    + " only files the cluster doesn't have yet are uploaded."})
    static final class Publish extends Base {
        @Parameters(paramLabel = "DIR", arity = "0..1", defaultValue = ".", description = "The project folder (default: the current one).")
        Path dir;

        @Option(names = "--no-bundle", description = "Publish the folder's files as they are (already ES modules), without esbuild.")
        boolean noBundle;

        @Option(names = "--dry-run", description = "Show what would be published, without uploading anything.")
        boolean dryRun;

        @Override
        int execute() throws Exception {
            Outcome outcome = publish(this, dir.toAbsolutePath().normalize(), noBundle, dryRun, false);
            if (outcome.dryRun()) {
                if (!printedJson(outcome.manifest())) {
                    out().println("Would publish " + outcome.label() + ": " + outcome.files() + " files, " + kb(outcome.bytes()));
                    outcome.manifest().path("files").fieldNames().forEachRemaining(path -> out().println("  " + path));
                }
            } else if (!printedJson(outcome.published())) {
                out().println("Published " + outcome.label() + ": " + outcome.files() + " files, " + kb(outcome.bytes())
                        + " (" + outcome.uploadedFiles() + " uploaded, " + kb(outcome.uploadedBytes()) + ")");
            }
            return 0;
        }
    }

    /** What publishing a project did. {@code published} is the stored version (null on a dry run). */
    record Outcome(Project project, ObjectNode manifest, JsonNode published, int files, long bytes, int uploadedFiles, long uploadedBytes,
                   boolean dryRun, boolean unchanged) {

        String label() {
            return project.name() + "@" + project.version();
        }
    }

    /**
     * Bundles and publishes the project in {@code root}: hashes every file, uploads those the cluster lacks, commits the
     * version. With {@code reuse}, a version that's already published with exactly these files isn't an error
     * ({@code automately deploy} runs this every time); with different files it is, since a version never changes.
     */
    static Outcome publish(Base cmd, Path root, boolean noBundle, boolean dryRun, boolean reuse) throws Exception {
        Project project = Project.read(root);
        Built built = noBundle ? Built.asIs(root, project) : Built.bundle(root, project, cmd.err());

        Map<String, String> hashes = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, Path> file : built.files().entrySet()) {
            byte[] bytes = Files.readAllBytes(file.getValue());
            total += bytes.length;
            hashes.put(file.getKey(), sha256(bytes));
        }
        if (hashes.size() > MAX_FILES) {
            throw new CliException("A package version can have at most " + MAX_FILES + " files; this one has " + hashes.size());
        }
        if (total > MAX_BYTES) {
            throw new CliException("A package version can be at most " + MAX_BYTES / (1024 * 1024) + " MB; this one is " + kb(total));
        }
        ObjectNode files = Json.object();
        hashes.forEach((path, hash) -> files.put(path, "sha256:" + hash));
        ObjectNode exports = Json.object();
        built.exports().forEach(exports::put);
        ObjectNode dependencies = Json.object();
        project.dependencies().forEach(dependencies::put);
        ObjectNode manifest = Json.object().put("name", project.name()).put("version", project.version());
        manifest.set("files", files);
        manifest.set("exports", exports);
        manifest.set("dependencies", dependencies);

        if (dryRun) {
            return new Outcome(project, manifest, null, hashes.size(), total, 0, 0, true, false);
        }
        if (reuse) {
            JsonNode existing = null;
            try {
                existing = cmd.api().get(path(project.name()) + "/versions/" + Api.segment(project.version()));
            } catch (ApiException e) {
                if (e.status() != 404) {
                    throw e;
                }
            }
            if (existing != null) {
                if (!existing.path("files").equals(files)) {
                    throw new CliException(project.name() + "@" + project.version() + " is already published with different files; "
                            + "a version never changes, so bump \"version\" and deploy again");
                }
                return new Outcome(project, manifest, existing, hashes.size(), total, 0, 0, false, true);
            }
        }

        JsonNode missing = cmd.api().post("/v1/packages/blobs/missing", Json.object().set("sha256", Json.strings(List.copyOf(new java.util.TreeSet<>(hashes.values())))));
        List<String> wanted = new ArrayList<>();
        missing.path("missing").forEach(hash -> wanted.add(hash.asText()));
        long uploaded = 0;
        for (String hash : wanted) {
            String path = hashes.entrySet().stream().filter(e -> e.getValue().equals(hash)).findFirst().orElseThrow().getKey();
            byte[] bytes = Files.readAllBytes(built.files().get(path));
            cmd.api().putBytes("/v1/packages/blobs/" + hash, bytes, "application/octet-stream");
            uploaded += bytes.length;
        }
        JsonNode published = cmd.api().post("/v1/packages", manifest);
        return new Outcome(project, manifest, published, hashes.size(), total, wanted.size(), uploaded, false, false);
    }

    @Command(name = "ls", description = "Packages you can import: yours, and the cluster's (@cluster/).")
    static final class ListPackages extends Base {
        @Override
        int execute() {
            JsonNode all = api().get("/v1/packages");
            if (printedJson(all)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            all.path("packages").forEach(p -> rows.add(List.of(Json.text(p, "name"), Json.text(p, "latest"), p.path("versions").asText(),
                    Output.time(p, "updatedAt"))));
            Output.table(out(), List.of("NAME", "LATEST", "VERSIONS", "UPDATED"), rows);
            return 0;
        }
    }

    @Command(name = "info", description = "A package's versions, newest first; with a version, its files.")
    static final class Info extends Base {
        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Parameters(index = "1", paramLabel = "VERSION", arity = "0..1")
        String version;

        @Override
        int execute() {
            if (version != null) {
                JsonNode found = api().get(path(name) + "/versions/" + Api.segment(version));
                if (!printedJson(found)) {
                    out().println(name + "@" + version + " · " + kb(found.path("size").asLong()) + " · published " + Output.time(found, "publishedAt")
                            + " by " + Json.text(found, "publishedByName") + (found.path("yanked").asBoolean() ? " · yanked" : ""));
                    found.path("dependencies").properties().forEach(d -> out().println("  depends on " + d.getKey() + " " + d.getValue().asText()));
                    found.path("exports").properties().forEach(e -> out().println("  export " + e.getKey() + " → " + e.getValue().asText()));
                    found.path("files").properties().forEach(f -> out().println("  " + f.getKey()));
                }
                return 0;
            }
            JsonNode found = api().get(path(name));
            if (printedJson(found)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            found.path("versions").forEach(v -> rows.add(List.of(Json.text(v, "version"), Output.time(v, "publishedAt"),
                    Json.text(v, "publishedByName"), v.path("fileCount").asText(), kb(v.path("size").asLong()),
                    v.path("yanked").asBoolean() ? "yes" : "")));
            Output.table(out(), List.of("VERSION", "PUBLISHED", "BY", "FILES", "SIZE", "YANKED"), rows);
            return 0;
        }
    }

    // --- the project ---

    /**
     * What to publish, from automately.json, else package.json: name, version, entry points by export, and the
     * Automately packages it imports (only automately.json lists those; npm dependencies are bundled in).
     */
    record Project(String name, String version, Map<String, String> entries, Map<String, String> dependencies) {

        static Project read(Path root) throws IOException {
            if (!Files.isDirectory(root)) {
                throw new CliException("No such folder: " + root);
            }
            JsonNode own = readJson(root.resolve("automately.json"));
            JsonNode npm = readJson(root.resolve("package.json"));
            if (own == null && npm == null) {
                throw new CliException("Neither automately.json nor package.json is in " + root);
            }
            String name = text(own, "name", text(npm, "name", null));
            String version = text(own, "version", text(npm, "version", null));
            if (name == null || version == null) {
                throw new CliException("Set \"name\" and \"version\" in automately.json (or package.json)");
            }
            if (name.startsWith("@") && !name.startsWith("@cluster/")) {
                throw new CliException("\"" + name + "\" is an npm scoped name; set a plain \"name\" in automately.json (scopes other than @cluster/ aren't used)");
            }
            Map<String, String> entries = entries(own != null && own.has("exports") ? own.get("exports")
                    : own != null && own.has("entry") ? own.get("entry") : null);
            if (entries.isEmpty() && npm != null) {
                entries = entries(npm.get("exports"));
                if (entries.isEmpty()) {
                    String main = text(npm, "module", text(npm, "main", null));
                    if (main != null) {
                        entries = Map.of(".", main);
                    }
                }
            }
            if (entries.isEmpty() && Files.exists(root.resolve("index.js"))) {
                entries = Map.of(".", "index.js");
            }
            if (entries.isEmpty()) {
                throw new CliException("Say what the package exports: \"entry\": \"src/index.js\" in automately.json, or \"main\" in package.json");
            }
            Map<String, String> dependencies = new TreeMap<>();
            if (own != null && own.path("dependencies").isObject()) {
                own.get("dependencies").properties().forEach(d -> dependencies.put(d.getKey(), d.getValue().asText()));
            }
            return new Project(name, version, entries, dependencies);
        }

        /** {@code "src/index.js"}, or {@code {".": "src/index.js", "./slug": "src/slug.js"}}; conditional exports are skipped. */
        private static Map<String, String> entries(JsonNode exports) {
            Map<String, String> entries = new LinkedHashMap<>();
            if (exports == null) {
                return entries;
            }
            if (exports.isTextual()) {
                entries.put(".", exports.asText());
            } else if (exports.isObject()) {
                exports.properties().forEach(e -> {
                    if ((e.getKey().equals(".") || e.getKey().startsWith("./")) && e.getValue().isTextual()) {
                        entries.put(e.getKey(), e.getValue().asText());
                    }
                });
            }
            return entries;
        }

        private static JsonNode readJson(Path file) throws IOException {
            return Files.exists(file) ? Json.parse(Files.readString(file)) : null;
        }

        private static String text(JsonNode node, String field, String fallback) {
            return node != null && node.path(field).isTextual() ? node.get(field).asText() : fallback;
        }
    }

    /** The files a version will hold, by their path in the package, and its exports. */
    record Built(Map<String, Path> files, Map<String, String> exports) {

        /** The folder as it is: every file except node_modules, hidden files and the project's own config (the manifest says it all). */
        static Built asIs(Path root, Project project) throws IOException {
            Map<String, Path> files = new TreeMap<>();
            try (Stream<Path> all = Files.walk(root)) {
                all.filter(Files::isRegularFile).forEach(file -> {
                    String path = root.relativize(file).toString().replace('\\', '/');
                    boolean skipped = path.startsWith("node_modules/") || path.contains("/node_modules/") || path.startsWith(".")
                            || path.contains("/.") || path.equals("automately.json") || path.equals("package.json")
                            || path.equals("package-lock.json");
                    if (!skipped) {
                        files.put(path, file);
                    }
                });
            }
            Map<String, String> exports = new LinkedHashMap<>();
            project.entries().forEach((subpath, source) -> {
                String path = source.startsWith("./") ? source.substring(2) : source;
                if (!files.containsKey(path)) {
                    throw new CliException("The export " + subpath + " names " + source + ", which isn't in " + root);
                }
                exports.put(subpath, path);
            });
            return new Built(files, exports);
        }

        /**
         * Runs the bundler (bundle.mjs) in the project's own Node and esbuild. Required native code, and Node built-ins
         * the isolate doesn't have, stop the publish with what pulled them in (D-083 §9); optional native extras are
         * left out with a warning.
         */
        static Built bundle(Path root, Project project, java.io.PrintStream err) throws Exception {
            if (!Files.exists(root.resolve("node_modules/esbuild/package.json"))) {
                throw new CliException("Bundling uses the project's own esbuild, which isn't installed: run npm i -D esbuild"
                        + " (or publish files that are already ES modules with --no-bundle)");
            }
            Path work = root.resolve("node_modules/.automately");
            Path outdir = work.resolve("out");
            deleteTree(outdir);
            Files.createDirectories(outdir);
            Path script = work.resolve("bundle.mjs");
            try (InputStream in = PackagesCommand.class.getResourceAsStream("/automately/bundle.mjs")) {
                if (in == null) {
                    throw new CliException("This build of the CLI is missing its bundler");
                }
                Files.write(script, in.readAllBytes());
            }
            ObjectNode options = Json.object().put("root", root.toString()).put("outdir", outdir.toString());
            ObjectNode entries = Json.object();
            project.entries().forEach(entries::put);
            options.set("entries", entries);
            options.set("packages", Json.strings(List.copyOf(project.dependencies().keySet())));

            Path errors = Files.createTempFile("automately-bundle", ".log");
            Process process;
            try {
                process = new ProcessBuilder("node", script.toString(), Json.write(options)).directory(root.toFile())
                        .redirectError(errors.toFile()).start();
            } catch (IOException e) {
                throw new CliException("Bundling needs Node.js on your PATH: " + e.getMessage());
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new CliException("Bundling took over 5 minutes, and was stopped");
            }
            String stderr = Files.readString(errors);
            Files.deleteIfExists(errors);
            if (process.exitValue() != 0 || output.isBlank()) {
                throw new CliException("Bundling failed" + (stderr.isBlank() ? "" : ":\n" + stderr.strip()));
            }
            JsonNode result = Json.parse(output);
            result.path("warnings").forEach(w -> err.println("warning: " + w.asText()));
            List<String> problems = new ArrayList<>();
            result.path("errors").forEach(e -> problems.add(e.asText()));
            if (!problems.isEmpty()) {
                throw new CliException("Bundling failed:\n  " + String.join("\n  ", problems));
            }
            List<String> natives = new ArrayList<>();
            result.path("natives").forEach(n -> natives.add(n.asText()));
            if (!natives.isEmpty()) {
                throw new CliException("This needs native code, which packages can't hold:\n  " + String.join("\n  ", natives)
                        + "\nSet \"runtime\": \"node\" in automately.json to run it as a Node service instead (the Node runtime, D-084).");
            }
            List<String> builtins = new ArrayList<>();
            result.path("builtins").forEach(b -> builtins.add(b.asText()));
            if (!builtins.isEmpty()) {
                throw new CliException("These Node built-ins aren't available to packages:\n  " + String.join("\n  ", builtins)
                        + "\nPackages run in the isolate, which provides fs, path, events, buffer, url, util and the automately:* modules; "
                        + "for the rest of Node, set \"runtime\": \"node\" (D-084).");
            }
            Map<String, Path> files = new TreeMap<>();
            result.path("files").forEach(f -> files.put(f.asText(), outdir.resolve(f.asText())));
            Map<String, String> exports = new LinkedHashMap<>();
            result.path("exports").properties().forEach(e -> exports.put(e.getKey(), e.getValue().asText()));
            return new Built(files, exports);
        }

        private static void deleteTree(Path dir) throws IOException {
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> all = Files.walk(dir)) {
                for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String kb(long bytes) {
        return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? (bytes + 512) / 1024 + " KB" : String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
