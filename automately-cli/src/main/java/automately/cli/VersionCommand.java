package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;

@Command(name = "version", description = "Show the CLI's version, and the cluster it talks to.")
final class VersionCommand extends Base {

    @Override
    int execute() {
        out().println("automately " + Main.version());
        Config config = root().config();
        if (config.url() != null) {
            try {
                JsonNode health = new Api(new Config(config.url(), null)).get("/health");
                out().println("cluster " + config.url() + " (node " + Json.text(health, "node") + ", " + Json.text(health, "status") + ")");
            } catch (CliException e) {
                out().println("cluster " + config.url() + " (unreachable: " + e.getMessage() + ")");
            }
        }
        return 0;
    }
}
