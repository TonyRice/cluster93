package automately.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.InputStream;
import java.util.Properties;

/** {@code automately}: the command-line client for an Automately cluster (phase 4, milestone 3). */
@Command(name = "automately", mixinStandardHelpOptions = true, versionProvider = Main.VersionProvider.class,
        description = "Run sandboxed JavaScript jobs and services on an Automately cluster.",
        subcommands = {LoginCommand.class, LogoutCommand.class, WhoamiCommand.class, RunCommand.class, JobsCommand.class,
                ServicesCommand.class, DomainsCommand.class, FilesCommand.class, QueuesCommand.class, CallbacksCommand.class, SecretsCommand.class, StorageCommand.class, ClusterCommand.class, NodeCommand.class, TokensCommand.class, TenantsCommand.class,
                McpCommand.class, VersionCommand.class, UpgradeCommand.class, CommandLine.HelpCommand.class})
public final class Main implements Runnable {

    @Option(names = "--json", scope = ScopeType.INHERIT, description = "Print the API's JSON instead of a summary.")
    boolean json;

    @Option(names = "--url", scope = ScopeType.INHERIT, description = "Cluster URL (default: AUTOMATELY_URL, then the saved login).")
    String url;

    @Option(names = "--token", scope = ScopeType.INHERIT, description = "Token (default: AUTOMATELY_TOKEN, then the saved login).")
    String token;

    final Io io;

    Main(Io io) {
        this.io = io;
    }

    @Override
    public void run() {
        new CommandLine(this).usage(io.out());
    }

    Config config() {
        return Config.resolve(io, url, token);
    }

    Api api() {
        return new Api(config().require());
    }

    public static void main(String[] args) {
        System.exit(run(Io.system(), args));
    }

    /** Runs a command line; returns the exit code. */
    public static int run(Io io, String... args) {
        CommandLine commandLine = new CommandLine(new Main(io));
        commandLine.setOut(new java.io.PrintWriter(io.out(), true));
        commandLine.setErr(new java.io.PrintWriter(io.err(), true));
        commandLine.setExecutionExceptionHandler((error, line, parsed) -> {
            if (error instanceof CliException failure) {
                io.err().println("error: " + failure.getMessage());
                return failure.exitCode();
            }
            io.err().println("error: " + error);
            return 1;
        });
        return commandLine.execute(args);
    }

    static String version() {
        Properties properties = new Properties();
        try (InputStream in = Main.class.getResourceAsStream("/automately-cli.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (java.io.IOException ignored) {
            // falls through to "dev"
        }
        return properties.getProperty("version", "dev");
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"automately " + version()};
        }
    }
}
