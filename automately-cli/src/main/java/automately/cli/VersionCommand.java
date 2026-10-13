package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;

import java.nio.file.Path;

/**
 * {@code automately version}: the running build's identity (version, embedded commit and timestamp), whether the
 * checkout it was built from has moved on since, and the cluster it talks to.
 */
@Command(name = "version", description = "Show the CLI's version, and the cluster it talks to.")
final class VersionCommand extends Base {

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
            Output.json(out(), result);
            return 0;
        }

        out().println("automately " + Main.version()
                + (commit.equals("dev") ? "" : " (commit " + Main.shortCommit(commit) + ")"));
        if (clusterUrl != null) {
            if (health != null) {
                out().println("cluster " + clusterUrl + " (node " + Json.text(health, "node") + ", " + Json.text(health, "status") + ")");
            } else {
                out().println("cluster " + clusterUrl + " (unreachable: " + clusterError + ")");
            }
        }
        if (updateAvailable) {
            out().println();
            out().println("\u26a0\ufe0f  Update Available! Current CLI build: " + Main.shortCommit(commit)
                    + ", Current repo HEAD: " + Main.shortCommit(repoHead)
                    + ". Run 'scripts/build-cli.sh' or 'automately local update'.");
        }
        return 0;
    }

    /** True when the checkout's HEAD is a known commit different from the one this build embedded. */
    static boolean updateAvailable(String commit, String repoHead) {
        return commit != null && repoHead != null && !commit.equals("dev") && !commit.equals(repoHead);
    }
}
