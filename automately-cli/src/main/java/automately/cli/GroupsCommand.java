package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "groups", description = "Groups: roles for everyone in them (D-070). Put members in with: members set USER --group NAME.",
        subcommands = {GroupsCommand.Ls.class, GroupsCommand.Set.class, GroupsCommand.Rm.class})
final class GroupsCommand extends Base {

    @Override
    int execute() {
        return Ls.list(this);
    }

    @Command(name = "ls", description = "Groups, with their roles and members.")
    static final class Ls extends Base {
        @Override
        int execute() {
            return list(this);
        }

        static int list(Base command) {
            JsonNode groups = command.api().get("/v1/groups");
            if (command.printedJson(groups)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            groups.path("groups").forEach(g -> rows.add(List.of(Json.text(g, "name"), MembersCommand.joined(g.path("roles")),
                    MembersCommand.joined(g.path("members")), Json.text(g, "description"))));
            Output.table(command.out(), List.of("GROUP", "ROLES", "MEMBERS", "DESCRIPTION"), rows);
            return 0;
        }
    }

    @Command(name = "set", description = "Create or change a group's roles.")
    static final class Set extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Option(names = {"-r", "--role"}, required = true, paramLabel = "ROLE", description = "Repeatable; not owner.")
        List<String> roles;

        @Option(names = "--description", paramLabel = "TEXT")
        String description;

        @Override
        int execute() {
            var body = Json.object();
            body.set("roles", Json.strings(roles));
            if (description != null) {
                body.put("description", description);
            }
            JsonNode group = api().put("/v1/groups/" + Api.segment(name), body);
            if (!printedJson(group)) {
                out().println("Group " + name + ": " + MembersCommand.joined(group.path("roles")));
            }
            return 0;
        }
    }

    @Command(name = "rm", description = "Delete a group; its members leave it.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/groups/" + Api.segment(name));
            out().println("Deleted group " + name);
            return 0;
        }
    }
}
