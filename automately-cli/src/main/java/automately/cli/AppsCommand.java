package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code automately apps}: deploy a project's static export as an app, upload its assets into the deployment's VFS
 * prefix, and promote, roll back, inspect and remove its deployments (docs/design/apps-and-console.md). The ingress
 * serves what is promoted; these commands manage it.
 */
@Command(name = "apps", description = "Deploy and manage apps: static sites (and their backends) served from the VFS.",
        subcommands = {AppsCommand.Deploy.class, AppsCommand.Ls.class, AppsCommand.Get.class, AppsCommand.Deployments.class,
                AppsCommand.Promote.class, AppsCommand.Rollback.class, AppsCommand.Rm.class})
final class AppsCommand extends Base {

    /** An app name is a host label: lowercase letters, digits and inner hyphens, and no {@code --} (the separator). */
    private static final Pattern APP_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?");

    /** Where a build's static export may land, in the order tried when the manifest doesn't say. */
    private static final List<String> EXPORT_DIRS = List.of("out", "dist", "build");

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    // ----------------------------------------------------------------------
    // deploy
    // ----------------------------------------------------------------------

    @Command(name = "deploy", description = {
            "Publish the project in DIR as an app: upload its static export and register an immutable deployment.",
            "Reads automately.json (\"name\", \"distDir\", routes, backend, headers); the app is \"name\", --app, or the folder.",
            "The deployment is promoted (made live) unless --no-promote is passed."})
    static final class Deploy extends Base {

        @Parameters(paramLabel = "DIR", arity = "0..1", defaultValue = ".", description = "The project folder (default: the current one).")
        Path dir;

        @Option(names = "--app", paramLabel = "NAME", description = "The app's name (overrides automately.json and the folder's name).")
        String app;

        @Option(names = "--commit", paramLabel = "HASH", description = "The git commit this build is from (default: detected in DIR).")
        String commit;

        @Option(names = "--no-promote", description = "Register the deployment without making it live.")
        boolean noPromote;

        @Option(names = "--promote", description = "Make the deployment live (the default); cancels --no-promote.")
        boolean promote;

        @Override
        int execute() throws IOException {
            Path root = dir.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new CliException(root + " is not a folder", 2);
            }
            ObjectNode settings = settings(root);
            String name = firstNonBlank(app, settings == null ? null : text(settings, "name"), folderName(root));
            if (name == null || !APP_NAME.matcher(name).matches() || name.contains("--")) {
                throw new CliException("Invalid app name \"" + name + "\": use lowercase letters, digits and inner hyphens. "
                        + "Set \"name\" in automately.json or pass --app.", 2);
            }
            ObjectNode manifest = settings == null ? Json.object() : settings.deepCopy();
            manifest.put("name", name);

            Path export = exportDir(root, settings);
            Map<String, Path> files = collect(export);
            if (files.isEmpty()) {
                throw new CliException("No files to deploy under " + export, 2);
            }

            String deploymentId = "dpl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            String vfsPrefix = "/.apps/" + name + "/deployments/" + deploymentId;

            long bytes = 0;
            Api api = api();
            for (Map.Entry<String, Path> file : files.entrySet()) {
                JsonNode stored = api.upload("/v1/files" + vfsPrefix + "/" + Api.filePath(file.getKey()), file.getValue(),
                        FilesCommand.contentType(file.getValue()));
                bytes += stored == null ? Files.size(file.getValue()) : stored.path("size").asLong(0);
            }

            ObjectNode body = Json.object();
            body.put("deploymentId", deploymentId);
            body.set("manifest", manifest);
            body.put("vfsPrefix", vfsPrefix);
            String commitHash = commit != null ? commit : gitCommit(root);
            if (commitHash != null && !commitHash.isBlank()) {
                body.put("commitHash", commitHash);
            }
            JsonNode deployment = api.post("/v1/apps/" + Api.segment(name) + "/deployments", body);

            boolean live = !noPromote || promote;
            if (live) {
                api.post("/v1/apps/" + Api.segment(name) + "/promote", Json.object().put("deploymentId", deploymentId));
            }

            String tenant = Json.text(deployment, "tenant");
            String preview = appUrl(this, previewLabel(deploymentId, name, tenant));
            String canonical = appUrl(this, canonicalLabel(name, tenant));
            if (json()) {
                ObjectNode result = (ObjectNode) deployment;
                result.put("files", files.size()).put("bytes", bytes).put("promoted", live)
                        .put("previewUrl", preview).put("canonicalUrl", canonical);
                Output.json(out(), result);
                return 0;
            }
            out().println("Deployed " + name + " (" + deploymentId + "), " + files.size() + " file" + (files.size() == 1 ? "" : "s")
                    + ", " + size(bytes) + ".");
            out().println("  preview:   " + preview);
            out().println("  canonical: " + canonical);
            out().println(live ? "  Promoted: it is live now." : "  Not promoted (--no-promote).");
            return 0;
        }

        /** automately.json at the project's root, or null when there is none. */
        private static ObjectNode settings(Path root) throws IOException {
            Path file = root.resolve("automately.json");
            if (!Files.exists(file)) {
                return null;
            }
            JsonNode parsed = Json.parse(Files.readString(file));
            if (!parsed.isObject()) {
                throw new CliException(file + " must be a JSON object", 2);
            }
            return (ObjectNode) parsed;
        }

        /** The static export folder: the manifest's {@code distDir}, else out/, dist/, build/, else the project root. */
        private static Path exportDir(Path root, ObjectNode settings) {
            if (settings != null) {
                String custom = text(settings, "distDir");
                if (custom != null) {
                    Path candidate = root.resolve(custom).normalize();
                    if (Files.isDirectory(candidate)) {
                        return candidate;
                    }
                }
            }
            for (String name : EXPORT_DIRS) {
                Path candidate = root.resolve(name);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
            return root;
        }
    }

    // ----------------------------------------------------------------------
    // ls / get / deployments
    // ----------------------------------------------------------------------

    @Command(name = "ls", aliases = {"list"}, description = "Your apps, and which deployment of each is live.")
    static final class Ls extends Base {

        @Override
        int execute() {
            JsonNode all = api().get("/v1/apps");
            if (printedJson(all)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            all.path("apps").forEach(a -> rows.add(List.of(Json.text(a, "name"), Json.text(a, "activeDeploymentId"),
                    Json.text(a, "updatedAt").isEmpty() ? "" : Output.time(a, "updatedAt"))));
            Output.table(out(), List.of("NAME", "ACTIVE", "UPDATED"), rows);
            return 0;
        }
    }

    @Command(name = "get", description = "One app: which deployment is live, and the deployments it has moved through.")
    static final class Get extends Base {

        @Parameters(paramLabel = "APP")
        String app;

        @Override
        int execute() {
            summarize(this, api().get("/v1/apps/" + Api.segment(app)));
            return 0;
        }
    }

    @Command(name = "deployments", description = "An app's deployments, oldest first, with the live one marked.")
    static final class Deployments extends Base {

        @Parameters(paramLabel = "APP")
        String app;

        @Override
        int execute() {
            JsonNode all = api().get("/v1/apps/" + Api.segment(app) + "/deployments");
            if (printedJson(all)) {
                return 0;
            }
            String live = "";
            try {
                live = Json.text(api().get("/v1/apps/" + Api.segment(app)), "activeDeploymentId");
            } catch (ApiException ignored) {
                // the deployments still list even if the app record is gone
            }
            final String active = live;
            List<List<String>> rows = new ArrayList<>();
            all.path("deployments").forEach(d -> rows.add(List.of(Json.text(d, "deploymentId"),
                    d.path("manifest").path("version").asText(""), shortCommit(Json.text(d, "commitHash")),
                    Output.time(d, "createdAt"), Json.text(d, "deploymentId").equals(active) ? "yes" : "")));
            Output.table(out(), List.of("DEPLOYMENT", "VERSION", "COMMIT", "CREATED", "ACTIVE"), rows);
            return 0;
        }
    }

    // ----------------------------------------------------------------------
    // promote / rollback / rm
    // ----------------------------------------------------------------------

    @Command(name = "promote", description = "Point an app at one of its deployments, making it live.")
    static final class Promote extends Base {

        @Parameters(index = "0", paramLabel = "APP")
        String app;

        @Parameters(index = "1", paramLabel = "DEPLOYMENT")
        String deploymentId;

        @Override
        int execute() {
            summarize(this, api().post("/v1/apps/" + Api.segment(app) + "/promote", Json.object().put("deploymentId", deploymentId)));
            return 0;
        }
    }

    @Command(name = "rollback", description = "Point an app back at the deployment it was live on before the current one.")
    static final class Rollback extends Base {

        @Parameters(paramLabel = "APP")
        String app;

        @Override
        int execute() {
            summarize(this, api().post("/v1/apps/" + Api.segment(app) + "/rollback", null));
            return 0;
        }
    }

    @Command(name = "rm", aliases = {"delete"}, description = "Delete an app and every deployment it has.")
    static final class Rm extends Base {

        @Parameters(paramLabel = "APP")
        String app;

        @Option(names = "--yes", description = "Confirm: deleting an app and its deployments can't be undone.")
        boolean yes;

        @Override
        int execute() {
            if (!yes) {
                throw new CliException("Deleting " + app + " removes it and its deployments for good; add --yes to confirm");
            }
            api().delete("/v1/apps/" + Api.segment(app));
            if (json()) {
                Output.json(out(), Json.object().put("app", app).put("deleted", true));
            } else {
                out().println("Removed app " + app + ".");
            }
            return 0;
        }
    }

    // ----------------------------------------------------------------------
    // shared output and helpers
    // ----------------------------------------------------------------------

    /** An app record as a person reads it: its name, its live deployment, and the promotions it has been through. */
    static void summarize(Base command, JsonNode record) {
        if (command.printedJson(record)) {
            return;
        }
        String active = Json.text(record, "activeDeploymentId");
        command.out().println(Json.text(record, "name") + ": "
                + (active.isEmpty() ? "nothing promoted yet" : "active " + active)
                + (Json.text(record, "updatedAt").isEmpty() ? "" : ", updated " + Output.time(record, "updatedAt")));
        List<String> history = new ArrayList<>();
        record.path("deploymentHistory").forEach(id -> history.add(id.asText()));
        if (!history.isEmpty()) {
            command.out().println("  history: " + String.join(" -> ", history));
        }
    }

    /** The ingress base URL: {@code AUTOMATELY_INGRESS_URL}, else the cluster URL (they share a host in the common case). */
    static String ingressBase(Base command) {
        String explicit = command.io().env().get("AUTOMATELY_INGRESS_URL");
        String base = explicit != null && !explicit.isBlank() ? explicit.trim() : command.api().url();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The preview host label {@code <deploymentId>--<app>--<tenant>} (docs/design/apps-and-console.md). */
    static String previewLabel(String deploymentId, String app, String tenant) {
        return deploymentId + "--" + app + (tenant.isEmpty() ? "" : "--" + tenant);
    }

    /** The canonical host label {@code <app>--<tenant>}. */
    static String canonicalLabel(String app, String tenant) {
        return tenant.isEmpty() ? app : app + "--" + tenant;
    }

    /** {@code <label>.<ingressHost>}: the label inserted into the ingress base URL's host. */
    static String appUrl(Base command, String label) {
        String base = ingressBase(command);
        int scheme = base.indexOf("://");
        return scheme < 0 ? label + "." + base : base.substring(0, scheme + 3) + label + "." + base.substring(scheme + 3);
    }

    /** Shortens a git commit to twelve characters for a table. */
    static String shortCommit(String commit) {
        return commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    /** A byte count as a person reads it. */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /** The first non-blank of the values, or null. */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** The named JSON field as text, or null when it is absent, null or blank. */
    private static String text(ObjectNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    /** The folder's file name, or null for a filesystem root. */
    private static String folderName(Path root) {
        Path name = root.getFileName();
        return name == null ? null : name.toString();
    }

    /** Every regular file under {@code dir}, as path-relative-to-dir (forward slashes) to its local path. */
    private static Map<String, Path> collect(Path dir) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(file -> files.put(dir.relativize(file).toString().replace('\\', '/'), file));
        }
        return files;
    }

    /** The git commit of the repository containing {@code root}, or null when there is none. */
    private static String gitCommit(Path root) {
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 && !output.isBlank() ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
