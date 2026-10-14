package cluster93.cli;

import picocli.CommandLine.Command;

/**
 * {@code c93 local}: the deprecated alias of {@code c93 node}, kept for one release so existing scripts and the docs
 * that mention {@code c93 local ...} keep working (D-134). It shares {@link NodeCommand}'s subcommands, so
 * {@code c93 local status} and {@code c93 node status} are the same command.
 */
@Command(name = "local", hidden = true, description = "Deprecated: use `c93 node`.",
        subcommands = {NodeCommand.Install.class, NodeCommand.Uninstall.class, NodeCommand.Setup.class,
                NodeCommand.Start.class, NodeCommand.Stop.class, NodeCommand.Restart.class, NodeCommand.Status.class,
                NodeCommand.Logs.class, NodeCommand.Mcp.class, NodeCommand.Update.class, NodeCommand.Env.class,
                NodeCommand.Invite.class, NodeCommand.Revoke.class})
final class LocalCommand extends Base {

    @Override
    int execute() {
        err().println("`c93 local` is deprecated; use `c93 node`.");
        spec.commandLine().usage(out());
        return 0;
    }
}
