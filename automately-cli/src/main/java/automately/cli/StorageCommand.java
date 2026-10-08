package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Your own storage (D-057): providers (an S3 bucket with your keys, or a disk folder) and which paths go where. */
@Command(name = "storage", description = "Your storage: providers, routes, quota and disk use.",
        subcommands = {StorageCommand.Providers.class, StorageCommand.Routes.class, StorageCommand.Mounts.class, StorageCommand.Mount.class,
                StorageCommand.Unmount.class})
final class StorageCommand extends Base {

    @Override
    int execute() {
        JsonNode overview = api().get("/v1/storage");
        if (printedJson(overview)) {
            return 0;
        }
        JsonNode quota = overview.path("quota");
        JsonNode disk = overview.path("disk");
        out().println("Cluster storage: " + Json.text(overview, "defaultBackend") + ", " + mb(quota.path("usedBytes").asLong()) + " of "
                + mb(quota.path("bytes").asLong()) + " used (files, kv and queued messages)");
        out().println("Allowed providers: " + (overview.path("policy").path("allow").isEmpty() ? "none (ask the admin)" : Json.write(overview.path("policy").path("allow")))
                + (disk.path("allotmentBytes").asLong() > 0 ? ", disk " + mb(disk.path("usedBytes").asLong()) + " of " + mb(disk.path("allotmentBytes").asLong()) : ""));
        out().println("Routes: " + Json.write(overview.path("routes")));
        return 0;
    }

    static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    @Command(name = "providers", description = "Your storage providers.",
            subcommands = {Ls.class, Get.class, SetS3.class, SetDisk.class, Check.class, Rm.class})
    static final class Providers extends Base {
        @Override
        int execute() {
            return new Ls().list(this);
        }
    }

    @Command(name = "ls", description = "Your providers (never their secrets).")
    static final class Ls extends Base {
        @Override
        int execute() {
            return list(this);
        }

        int list(Base command) {
            JsonNode providers = command.api().get("/v1/storage/providers");
            if (command.printedJson(providers)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            providers.path("providers").forEach(p -> rows.add(List.of(Json.text(p, "name"), Json.text(p, "type"),
                    p.path("settings").has("bucket") ? Json.text(p.path("settings"), "endpoint") + " / " + Json.text(p.path("settings"), "bucket")
                            : Json.text(p.path("settings"), "folder"), Json.text(p, "id"))));
            Output.table(command.out(), List.of("NAME", "TYPE", "WHERE", "ID"), rows);
            return 0;
        }
    }

    @Command(name = "get", description = "One provider.")
    static final class Get extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            Output.json(out(), api().get("/v1/storage/providers/" + Api.segment(name)));
            return 0;
        }
    }

    @Command(name = "set-s3", description = "Create or update an S3-compatible provider (checked before it's saved).")
    static final class SetS3 extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Option(names = "--endpoint", required = true, description = "e.g. https://s3.eu-west-1.amazonaws.com or https://ACCOUNT.r2.cloudflarestorage.com")
        String endpoint;

        @Option(names = "--bucket", required = true)
        String bucket;

        @Option(names = "--access-key-id", required = true)
        String accessKeyId;

        @Option(names = "--secret-stdin", description = "Read the secret access key from standard input (keeps it out of shell history).")
        boolean secretStdin;

        @Option(names = "--secret-access-key", description = "The secret access key; prefer --secret-stdin. Omit both to keep the current one.")
        String secret;

        @Option(names = "--region", defaultValue = "us-east-1")
        String region;

        @Option(names = "--prefix", defaultValue = "automately/")
        String prefix;

        @Option(names = "--virtual-hosted", description = "Bucket in the host name (AWS's default) rather than the path.")
        boolean virtualHosted;

        @Override
        int execute() throws Exception {
            ObjectNode body = Json.object().put("type", "s3").put("endpoint", endpoint).put("bucket", bucket).put("accessKeyId", accessKeyId)
                    .put("region", region).put("prefix", prefix).put("pathStyle", !virtualHosted);
            String key = secretStdin ? new BufferedReader(new InputStreamReader(io().in(), StandardCharsets.UTF_8)).readLine() : secret;
            if (key != null && !key.isBlank()) {
                body.put("secretAccessKey", key.trim());
            }
            JsonNode saved = api().put("/v1/storage/providers/" + Api.segment(name), body);
            if (!printedJson(saved)) {
                out().println("Saved S3 provider " + name + " (" + Json.text(saved, "id") + "); route paths to it with: automately storage routes set --prefix media/=" + name);
            }
            return 0;
        }
    }

    @Command(name = "set-disk", description = "Create a disk provider: a folder the cluster picks under the admin's disk root.")
    static final class SetDisk extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            JsonNode saved = api().put("/v1/storage/providers/" + Api.segment(name), Json.object().put("type", "disk"));
            if (!printedJson(saved)) {
                out().println("Saved disk provider " + name + " (" + Json.text(saved, "id") + ")");
            }
            return 0;
        }
    }

    @Command(name = "check", description = "Check a provider can be reached with its credentials.")
    static final class Check extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            JsonNode result = api().post("/v1/storage/providers/" + Api.segment(name) + "/check", null);
            if (printedJson(result)) {
                return result.path("ok").asBoolean() ? 0 : 1;
            }
            if (result.path("ok").asBoolean()) {
                out().println(name + " is reachable.");
                return 0;
            }
            err().println(name + " can't be used: " + Json.text(result, "error"));
            return 1;
        }
    }

    @Command(name = "rm", description = "Delete a provider that holds no files and no route points at.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/storage/providers/" + Api.segment(name));
            out().println("Deleted provider " + name + ".");
            return 0;
        }
    }

    @Command(name = "mounts", description = "Your mounts: folders bound to one of your providers (D-078).")
    static final class Mounts extends Base {
        @Override
        int execute() {
            JsonNode all = api().get("/v1/mounts");
            if (printedJson(all)) {
                return 0;
            }
            out().println("Everything else: " + Json.text(all, "default"));
            List<List<String>> rows = new ArrayList<>();
            all.path("mounts").forEach(m -> rows.add(List.of(Json.text(m, "path") + "/", Json.text(m, "provider"),
                    m.path("readOnly").asBoolean() ? "read-only" : "")));
            Output.table(out(), List.of("FOLDER", "PROVIDER", ""), rows);
            return 0;
        }
    }

    @Command(name = "mount", description = "Bind a folder to one of your providers (e.g. mnt/photos), so new files beneath it go there.")
    static final class Mount extends Base {
        @Parameters(paramLabel = "FOLDER")
        String folder;

        @Option(names = "--provider", required = true, paramLabel = "NAME")
        String provider;

        @Option(names = "--read-only", description = "Refuse new files, deletes and moves beneath it.")
        boolean readOnly;

        @Override
        int execute() {
            JsonNode mount = api().put("/v1/mounts/" + Api.filePath(folder.replaceAll("^/+|/+$", "")),
                    Json.object().put("provider", provider).put("readOnly", readOnly));
            if (!printedJson(mount)) {
                out().println("Mounted " + provider + " at " + Json.text(mount, "path") + "/" + (readOnly ? " (read-only)" : ""));
            }
            return 0;
        }
    }

    @Command(name = "unmount", description = "Unbind a folder. Its files stay readable; new ones go by your other mounts and routes.")
    static final class Unmount extends Base {
        @Parameters(paramLabel = "FOLDER")
        String folder;

        @Override
        int execute() {
            api().delete("/v1/mounts/" + Api.filePath(folder.replaceAll("^/+|/+$", "")));
            out().println("Unmounted " + folder);
            return 0;
        }
    }

    @Command(name = "routes", description = "Which provider new files go to (the longest prefix wins; \"default\" is the cluster's own).",
            subcommands = {RoutesSet.class})
    static final class Routes extends Base {
        @Override
        int execute() {
            JsonNode routes = api().get("/v1/storage/routes");
            if (!printedJson(routes)) {
                out().println("default -> " + Json.text(routes, "default"));
                routes.path("prefixes").fields().forEachRemaining(e -> out().println(e.getKey() + " -> " + e.getValue().asText()));
            }
            return 0;
        }
    }

    @Command(name = "set", description = "Replace the routes, e.g. --default default --prefix media/=bucket --prefix backups/=local")
    static final class RoutesSet extends Base {
        @Option(names = "--default", defaultValue = "default", description = "A provider name, or default (the cluster's own storage).")
        String defaultProvider;

        @Option(names = "--prefix", paramLabel = "PREFIX=PROVIDER", description = "Repeatable.")
        Map<String, String> prefixes;

        @Override
        int execute() {
            ObjectNode map = Json.object();
            if (prefixes != null) {
                prefixes.forEach(map::put);
            }
            ObjectNode body = Json.object().put("default", defaultProvider);
            body.set("prefixes", map);
            JsonNode saved = api().put("/v1/storage/routes", body);
            if (!printedJson(saved)) {
                out().println("Routes saved: " + Json.write(saved));
            }
            return 0;
        }
    }
}
