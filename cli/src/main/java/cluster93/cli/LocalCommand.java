package cluster93.cli;

import picocli.CommandLine.Command;

/**
 * {@code c93 local}: the deprecated one-release alias of {@code c93 dev} (D-190). It shares the dev subcommands, so
 * existing scripts keep working while the docs move to {@code c93 dev}.
 */
@Command(name = "local", hidden = true, description = "Deprecated: use `c93 dev`.",
        subcommands = {DevCommand.Up.class, DevCommand.Status.class, DevCommand.Logs.class, DevCommand.Down.class,
                DevCommand.Reset.class, DevCommand.Build.class, DevCommand.Seed.class, DevCommand.TestExamples.class})
final class LocalCommand extends Base {

    @Override
    int execute() {
        err().println("`c93 local` is deprecated; use `c93 dev`.");
        spec.commandLine().usage(out());
        return 0;
    }
}
