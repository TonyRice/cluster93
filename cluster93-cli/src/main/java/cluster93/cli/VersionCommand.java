package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code c93 version}: the running build's identity (version, embedded commit and timestamp), whether the
 * checkout it was built from has moved on since, the cluster it talks to, and — for an admin — whether the web
 * console deployed to {@code system/console} is behind the checkout.
 */
@Command(name = "version", description = "Show the CLI's version, and the cluster it talks to.")
final class VersionCommand extends Base {

    /** The reserved tenant that owns the management web console (docs/design/apps-and-console.md). */
    static final String CONSOLE_TENANT = "system";
    static final String CONSOLE_APP = "console";

    @Override
    int execute() {
        String commit = Main.commit();
        Path repoRoot = LocalCommand.repoRoot(io());
        String repoHead = LocalCommand.gitHead(io());
        boolean updateAvailable = updateAvailable(commit, repoHead);

        Config config = root().config();
        String clusterUrl = config.url();
        JsonNode health = null;
        String clusterError = null;
        if (clusterUrl != null) {
            try {
                health = new Api(new Config(clusterUrl, null)).get("/health");
            } catch (CliException e) {
                clusterError = e.getMessage();
            }
        }

        ConsoleStatus console = checkConsoleStatus(config, repoRoot, io());

        if (json()) {
            ObjectNode result = Json.object()
                    .put("version", Main.version())
                    .put("commit", commit)
                    .put("builtAt", Main.builtAt())
                    .put("repoRoot", repoRoot == null ? "" : repoRoot.toString())
                    .put("repoHead", repoHead == null ? "" : repoHead)
                    .put("updateAvailable", updateAvailable);
            if (clusterUrl != null) {
                ObjectNode cluster = Json.object().put("url", clusterUrl);
                if (health != null) {
                    cluster.put("node", Json.text(health, "node"));
                    cluster.put("status", Json.text(health, "status"));
                } else {
                    cluster.put("status", "unreachable");
                    cluster.put("error", clusterError == null ? "" : clusterError);
                }
                result.set("cluster", cluster);
            }
            result.set("console", Json.object()
                    .put("isAdmin", console.isAdmin())
                    .put("deployedVersion", console.deployedVersion())
                    .put("deployedCommit", console.deployedCommit())
                    .put("localVersion", console.localVersion())
                    .put("localCommit", console.localCommit())
                    .put("updateAvailable", console.updateAvailable()));
            Output.json(out(), result);
            return 0;
        }

        out().println("c93 " + Main.version()
                + (commit.equals("dev") ? "" : " (commit " + Main.shortCommit(commit) + ")"));
        if (clusterUrl != null) {
            if (health != null) {
                out().println("cluster " + clusterUrl + " (node " + Json.text(health, "node") + ", " + Json.text(health, "status") + ")");
            } else {
                out().println("cluster " + clusterUrl + " (unreachable: " + clusterError + ")");
            }
        }
        if (console.isAdmin() && (!console.deployedVersion().isEmpty() || !console.deployedCommit().isEmpty())) {
            out().println("console " + console.deployedVersion() + " (commit " + Main.shortCommit(console.deployedCommit()) + ")");
        }
        if (console.updateAvailable() && console.isAdmin()) {
            out().println();
            out().println("\u26a0\ufe0f  Update Available! Web Console build: " + Main.shortCommit(console.deployedCommit())
                    + ", Local repo HEAD: " + Main.shortCommit(console.localCommit())
                    + ". Run 'c93 local update' or 'c93 apps deploy console'.");
        }
        if (updateAvailable) {
            out().println();
            out().println("\u26a0\ufe0f  Update Available! Current CLI build: " + Main.shortCommit(commit)
                    + ", Current repo HEAD: " + Main.shortCommit(repoHead)
                    + ". Run 'scripts/build-cli.sh' or 'c93 local update'.");
        }
        return 0;
    }

    /** True when the checkout's HEAD is a known commit different from the one this build embedded. */
    static boolean updateAvailable(String commit, String repoHead) {
        return commit != null && repoHead != null && !commit.equals("dev") && !commit.equals(repoHead);
    }

    /** Whether the deployed console's commit differs from the checkout's HEAD. Silent when either is unknown. */
    static boolean consoleUpdateAvailable(String deployedCommit, String localCommit) {
        return deployedCommit != null && !deployedCommit.isBlank()
                && localCommit != null && !localCommit.isBlank()
                && !deployedCommit.equals(localCommit);
    }

    /**
     * The web console's deployed-vs-checkout status, as seen by the authenticated user.
     *
     * <p>It asks {@code /v1/whoami} first and returns a non-admin status for anyone without operator rights, so a
     * regular tenant's token never probes the reserved {@code system} tenant. A cluster that is unreachable, or an
     * absent console deployment (404), is a quiet non-admin/no-update answer rather than a failure.
     */
    static ConsoleStatus checkConsoleStatus(Config config, Path repoRoot, Io io) {
        String localVersion = localConsoleVersion(repoRoot);
        String localCommit = blankToEmpty(LocalCommand.gitHead(io));
        ConsoleStatus none = new ConsoleStatus(false, "", "", localVersion, localCommit, false);
        if (config == null || config.url() == null || config.url().isBlank()
                || config.token() == null || config.token().isBlank()) {
            return none;
        }
        Api api = new Api(new Config(config.url(), config.token(), CONSOLE_TENANT));
        try {
            JsonNode who = api.get("/v1/whoami");
            if (who == null || !who.path("admin").asBoolean(false)) {
                return none;
            }
        } catch (CliException e) {
            return none;
        }
        String deployedVersion = "";
        String deployedCommit = "";
        try {
            JsonNode app = api.get("/v1/apps/" + CONSOLE_APP);
            String active = Json.text(app, "activeDeploymentId");
            if (!active.isEmpty()) {
                JsonNode deployment = api.get("/v1/apps/" + CONSOLE_APP + "/deployments/" + Api.segment(active));
                if (deployment != null) {
                    deployedVersion = deployment.path("manifest").path("version").asText("");
                    deployedCommit = Json.text(deployment, "commitHash");
                }
            }
        } catch (CliException e) {
            // No console deployed to system/console yet, or the app is unavailable: report no deployment.
        }
        return new ConsoleStatus(true, deployedVersion, deployedCommit, localVersion, localCommit,
                consoleUpdateAvailable(deployedCommit, localCommit));
    }

    /** The version in the console project's {@code cluster93.json}, or "" when the checkout or manifest is absent. */
    static String localConsoleVersion(Path repoRoot) {
        if (repoRoot == null) {
            return "";
        }
        Path manifest = repoRoot.resolve("console").resolve("cluster93.json");
        if (!Files.isRegularFile(manifest)) {
            return "";
        }
        try {
            return Json.text(Json.parse(Files.readString(manifest)), "version");
        } catch (IOException | CliException e) {
            return "";
        }
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /** The web console's deployment status relative to the local checkout. */
    record ConsoleStatus(boolean isAdmin, String deployedVersion, String deployedCommit,
                         String localVersion, String localCommit, boolean updateAvailable) {
    }
}
