package automately.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.IHelpSectionRenderer;
import picocli.CommandLine.Model.UsageMessageSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** {@code automately}: the command-line client for an Automately cluster (phase 4, milestone 3). */
@Command(name = "automately", mixinStandardHelpOptions = true, versionProvider = Main.VersionProvider.class,
        description = "Run sandboxed JavaScript jobs and services on an Automately cluster.",
        subcommands = {LoginCommand.class, LogoutCommand.class, WhoamiCommand.class, RunCommand.class, JobsCommand.class,
                ServicesCommand.class, DomainsCommand.class, FilesCommand.class, QueuesCommand.class, CallbacksCommand.class, SecretsCommand.class, PackagesCommand.class, DeployCommand.class, AppsCommand.class, StorageCommand.class, EgressCommand.class, ClusterCommand.class, NodeCommand.class, TokensCommand.class, TenantsCommand.class,
                MembersCommand.class, RolesCommand.class, GroupsCommand.class, InvitesCommand.class, UsersCommand.class, AuditCommand.class, ProfileCommand.class,
                McpCommand.class, LocalCommand.class, VersionCommand.class, UpgradeCommand.class, CommandLine.HelpCommand.class})
public final class Main implements Runnable {

    /**
     * One usage-help category: a heading, and the commands shown under it, in the order they appear. Every
     * subcommand belongs to exactly one group; {@code MainHelpTest} repeats the list so the two can't drift.
     */
    private record Group(String heading, List<String> commands) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("Developer & Applications", List.of("apps", "deploy", "run", "jobs", "services")),
            new Group("Platform Resources",
                    List.of("files", "secrets", "queues", "packages", "storage", "domains", "egress", "callbacks")),
            new Group("Identity & Access",
                    List.of("login", "logout", "whoami", "profile", "tokens", "members", "roles", "groups", "invites")),
            new Group("Cluster & Local Node", List.of("local", "cluster", "node", "tenants", "users", "audit")),
            new Group("Tools & Automation", List.of("mcp", "version", "upgrade", "help")));

    @Option(names = "--json", scope = ScopeType.INHERIT, description = "Print the API's JSON instead of a summary.")
    boolean json;

    @Option(names = "--url", scope = ScopeType.INHERIT, description = "Cluster URL (default: AUTOMATELY_URL, then the saved login).")
    String url;

    @Option(names = "--token", scope = ScopeType.INHERIT, description = "Token (default: AUTOMATELY_TOKEN, then the saved login).")
    String token;

    @Option(names = "--tenant", scope = ScopeType.INHERIT, description = "Tenant name (default: AUTOMATELY_TENANT, then the saved login).")
    String tenant;

    final Io io;

    Main(Io io) {
        this.io = io;
    }

    @Override
    public void run() {
        commandLine(this).usage(io.out());
    }

    Config config() {
        return Config.resolve(io, url, token, tenant);
    }

    Api api() {
        return new Api(config().require());
    }

    public static void main(String[] args) {
        System.exit(run(Io.system(), args));
    }

    /** Runs a command line; returns the exit code. */
    public static int run(Io io, String... args) {
        CommandLine commandLine = commandLine(new Main(io));
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

    /** The {@code automately} command line, with its subcommands shown in categories. */
    private static CommandLine commandLine(Main main) {
        CommandLine commandLine = new CommandLine(main);
        categorizeSubcommands(commandLine);
        return commandLine;
    }

    /**
     * Replaces picocli's single flat command list with one section per {@link Group}. Only the top-level command is
     * re-rendered: every subcommand keeps the default command list, so {@code automately help local} and
     * {@code automately local} still list their own subcommands.
     */
    private static void categorizeSubcommands(CommandLine commandLine) {
        Map<String, IHelpSectionRenderer> sections = commandLine.getHelpSectionMap();
        sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING,
                help -> help.commandSpec().parent() == null ? "" : help.commandListHeading());
        sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
                help -> help.commandSpec().parent() == null ? categorizedCommands(help) : help.commandList());
    }

    /** Every group that has a present command, as a heading and picocli's own command table beneath it. */
    private static String categorizedCommands(Help help) {
        StringBuilder out = new StringBuilder();
        Map<String, Help> subcommands = help.subcommands();
        for (Group group : GROUPS) {
            Map<String, Help> selected = new LinkedHashMap<>();
            for (String name : group.commands()) {
                Help command = subcommands.get(name);
                if (command != null) {
                    selected.put(name, command);
                }
            }
            if (!selected.isEmpty()) {
                out.append(help.createHeading("%n" + group.heading() + ":%n%n"));
                out.append(help.commandList(selected));
            }
        }
        return out.toString();
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
