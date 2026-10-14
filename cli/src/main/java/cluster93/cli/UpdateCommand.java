package cluster93.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * {@code c93 update}: bring the CLI and/or the node up to date from the signed release (or a pinned
 * {@code --version}). With neither {@code --cli} nor {@code --node}, both are updated.
 *
 * <p>It is the front door for updating: {@code c93 upgrade} and {@code c93 node update} stay as hidden
 * aliases so existing scripts and docs keep working (D-138).
 */
@Command(name = "update", description = "Update the CLI and/or the node from the latest release (or --version).")
final class UpdateCommand extends Base {

    @Option(names = "--cli", description = "Update this CLI: the native binary, or the jar it runs from.")
    boolean cli;

    @Option(names = "--node", description = "Update the installed node (a checkout rebuilds from source instead).")
    boolean node;

    @Option(names = "--version", paramLabel = "TAG", description = "A release tag, e.g. v2.1.0 (default: the latest).")
    String version;

    @Option(names = "--to", paramLabel = "FILE", description = "With --cli, the binary or jar to replace (default: this one).")
    Path to;

    @Override
    int execute() throws Exception {
        boolean both = !cli && !node;
        if (both || cli) {
            UpgradeCommand.updateCli(io(), version, to, json());
        }
        if (both || node) {
            NodeCommand.updateNode(io(), version, json());
        }
        return 0;
    }
}
