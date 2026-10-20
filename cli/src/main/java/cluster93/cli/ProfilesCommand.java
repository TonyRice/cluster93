package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Named connection profiles (D-189): one URL/token/tenant per cluster, staging environment or dev stack.
 *
 * <p>The active profile is chosen by {@code --profile}, {@code C93_PROFILE}, the saved marker, then {@code default}.
 * The default profile keeps the legacy {@code config.json}; every other profile has its own directory, so a dev
 * stack cannot overwrite a production login.
 */
@Command(name = "profiles", description = "Manage connection profiles: a cluster URL, token and tenant per context.",
        subcommands = {ProfilesCommand.ListProfiles.class, ProfilesCommand.Use.class, ProfilesCommand.Show.class,
                ProfilesCommand.Create.class, ProfilesCommand.Remove.class})
final class ProfilesCommand extends Base {

    @Override
    int execute() {
        return list(this);
    }

    /** The listing, shared by `c93 profiles` (the default) and `c93 profiles list`. */
    static int list(Base command) {
        String active = command.profile();
        List<String> names = ProfileStore.list(command.io());
        if (command.json()) {
            ArrayNode rows = Json.array();
            for (String name : names) {
                Config config = Config.load(command.io(), name);
                ObjectNode row = Json.object().put("name", name).put("active", name.equals(active));
                if (config != null) {
                    row.put("url", config.url()).put("tenant", config.tenant())
                            .put("token", config.token() == null ? null : "saved");
                }
                JsonNode cluster = ProfileStore.meta(command.io(), name).get("cluster");
                if (cluster != null && cluster.isTextual()) {
                    row.put("cluster", cluster.asText());
                }
                rows.add(row);
            }
            Output.json(command.out(), rows);
            return 0;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String name : names) {
            Config config = Config.load(command.io(), name);
            JsonNode cluster = ProfileStore.meta(command.io(), name).get("cluster");
            rows.add(List.of(name.equals(active) ? "* " + name : "  " + name,
                    config == null || config.url() == null ? "-" : config.url(),
                    config == null || config.tenant() == null ? "-" : config.tenant(),
                    cluster != null && cluster.isTextual() ? cluster.asText() : "-"));
        }
        Output.table(command.out(), List.of("PROFILE", "URL", "TENANT", "CLUSTER"), rows);
        return 0;
    }

    @Command(name = "list", aliases = {"ls"}, description = "List profiles; * marks the active one.")
    static final class ListProfiles extends Base {

        @Override
        int execute() {
            return list(this);
        }
    }

    @Command(name = "use", description = "Make a profile the active one.")
    static final class Use extends Base {

        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            ProfileStore.use(io(), name);
            out().println("Using profile " + ProfileStore.valid(name) + ".");
            return 0;
        }
    }

    @Command(name = "show", description = "Show one profile's connection details (the token is never printed).")
    static final class Show extends Base {

        @Parameters(index = "0", arity = "0..1", paramLabel = "NAME", description = "Defaults to the active profile.")
        String name;

        @Override
        int execute() {
            String selected = name == null || name.isBlank() ? profile() : ProfileStore.valid(name);
            Config config = Config.load(io(), selected);
            ObjectNode meta = ProfileStore.meta(io(), selected);
            if (json()) {
                ObjectNode result = Json.object().put("name", selected).put("active", selected.equals(profile()));
                if (config != null) {
                    result.put("url", config.url()).put("tenant", config.tenant())
                            .put("token", config.token() == null ? null : "saved");
                }
                if (!meta.isEmpty()) {
                    result.set("profile", meta);
                }
                Output.json(out(), result);
                return 0;
            }
            out().println("Profile:  " + selected + (selected.equals(profile()) ? " (active)" : ""));
            out().println("  URL:     " + (config == null || config.url() == null ? "-" : config.url()));
            out().println("  Tenant:  " + (config == null || config.tenant() == null ? "-" : config.tenant()));
            out().println("  Token:   " + (config == null || config.token() == null ? "-" : "saved"));
            JsonNode cluster = meta.get("cluster");
            out().println("  Cluster: " + (cluster != null && cluster.isTextual() ? cluster.asText() : "-"));
            out().println("  File:    " + Config.file(io(), selected));
            return 0;
        }
    }

    @Command(name = "create", description = "Create a profile. It is not made active; use `c93 profiles use NAME`.")
    static final class Create extends Base {

        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Option(names = "--cluster", paramLabel = "DIR", description = "A cluster bundle directory (CA, joins, cluster.json).")
        Path cluster;

        @Override
        int execute() {
            String profile = ProfileStore.valid(name);
            if (!ProfileStore.DEFAULT.equals(profile) && ProfileStore.exists(io(), profile)) {
                throw new CliException("Profile " + profile + " already exists. Remove it first, or choose another name.", 2);
            }
            ProfileStore.create(io(), profile);
            if (cluster != null) {
                Path path = cluster.toAbsolutePath().normalize();
                if (!Files.isDirectory(path)) {
                    throw new CliException("No cluster bundle at " + path, 2);
                }
                ProfileStore.writeMeta(io(), profile, ProfileStore.meta(io(), profile).put("cluster", path.toString()));
            }
            out().println("Created profile " + profile + ".");
            out().println("Log in to it with: c93 --profile " + profile + " login <url>");
            out().println("Use it with:      c93 profiles use " + profile);
            return 0;
        }
    }

    @Command(name = "rm", aliases = {"remove"}, description = "Remove a profile that is neither active nor default.")
    static final class Remove extends Base {

        @Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            ProfileStore.delete(io(), name);
            out().println("Removed profile " + ProfileStore.valid(name) + ".");
            return 0;
        }
    }
}
