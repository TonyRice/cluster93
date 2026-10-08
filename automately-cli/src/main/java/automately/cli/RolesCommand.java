package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "roles", description = "Roles: the built-in ones, and your tenant's own, as sets of scopes (D-070).",
        subcommands = {RolesCommand.Ls.class, RolesCommand.Set.class, RolesCommand.Rm.class})
final class RolesCommand extends Base {

    @Override
    int execute() {
        return Ls.list(this);
    }

    @Command(name = "ls", description = "Every role and its scopes.")
    static final class Ls extends Base {
        @Override
        int execute() {
            return list(this);
        }

        static int list(Base command) {
            JsonNode roles = command.api().get("/v1/roles");
            if (command.printedJson(roles)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            roles.path("roles").forEach(r -> rows.add(List.of(Json.text(r, "name"), r.path("builtIn").asBoolean() ? "built-in" : "custom",
                    MembersCommand.joined(r.path("scopes")), Json.text(r, "description"))));
            Output.table(command.out(), List.of("ROLE", "KIND", "SCOPES", "DESCRIPTION"), rows);
            return 0;
        }
    }

    @Command(name = "set", description = "Create or change one of your roles. Changing it changes what everyone holding it may do.")
    static final class Set extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Option(names = {"-s", "--scope"}, required = true, paramLabel = "SCOPE", description = "Repeatable, e.g. jobs:read.")
        List<String> scopes;

        @Option(names = "--description", paramLabel = "TEXT")
        String description;

        @Override
        int execute() {
            var body = Json.object();
            body.set("scopes", Json.strings(scopes));
            if (description != null) {
                body.put("description", description);
            }
            JsonNode role = api().put("/v1/roles/" + Api.segment(name), body);
            if (!printedJson(role)) {
                out().println("Role " + name + ": " + MembersCommand.joined(role.path("scopes")));
            }
            return 0;
        }
    }

    @Command(name = "rm", description = "Delete one of your roles, once nobody holds it.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/roles/" + Api.segment(name));
            out().println("Deleted role " + name);
            return 0;
        }
    }
}
