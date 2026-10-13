package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "members", description = "Your tenant's members, and their roles and groups. New members come in with: invites create.",
        subcommands = {MembersCommand.Ls.class, MembersCommand.Set.class, MembersCommand.Rm.class})
final class MembersCommand extends Base {

    @Override
    int execute() {
        return new Ls().list(this, api().get("/v1/members"));
    }

    /** Who was removed, and the service tokens they made, which keep working until someone revokes them. */
    static void printLeft(Base command, JsonNode left) {
        if (command.printedJson(left)) {
            return;
        }
        command.out().println("Removed " + Json.text(left, "user"));
        JsonNode tokens = left.path("serviceTokens");
        if (!tokens.isEmpty()) {
            command.err().println("Service tokens they made still work (they belong to the tenant). Keep them, or revoke with: automately tokens revoke ID");
            tokens.forEach(t -> command.err().println("  " + Json.text(t, "id") + "  " + Json.text(t, "name") + "  " + joined(t.path("scopes"))
                    + (Json.text(t, "tenant").isEmpty() ? "" : "  (" + Json.text(t, "tenant") + ")")));
        }
    }

    static String joined(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return String.join(",", values);
    }

    @Command(name = "ls", description = "Members, with what each may do.")
    static final class Ls extends Base {
        @Override
        int execute() {
            return list(this, api().get("/v1/members"));
        }

        int list(Base command, JsonNode members) {
            if (command.printedJson(members)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            members.path("members").forEach(m -> rows.add(List.of(Json.text(m, "user"), joined(m.path("roles")), joined(m.path("groups")),
                    joined(m.path("scopes")), Output.time(m, "joinedAt"))));
            Output.table(command.out(), List.of("USER", "ROLES", "GROUPS", "SCOPES", "JOINED"), rows);
            return 0;
        }
    }

    @Command(name = "set", description = "Set a member's roles and groups (replacing them). You can only grant what you hold yourself.")
    static final class Set extends Base {
        @Parameters(paramLabel = "USER")
        String user;

        @Option(names = {"-r", "--role"}, paramLabel = "ROLE", description = "Repeatable: owner, admin, developer, viewer, or your own.")
        List<String> roles;

        @Option(names = {"-g", "--group"}, paramLabel = "GROUP", description = "Repeatable.")
        List<String> groups;

        @Override
        int execute() {
            var body = Json.object();
            body.set("roles", Json.strings(roles));
            body.set("groups", Json.strings(groups));
            JsonNode member = api().put("/v1/members/" + Api.segment(user), body);
            if (!printedJson(member)) {
                out().println(user + ": roles " + joined(member.path("roles")) + "; groups " + joined(member.path("groups"))
                        + "; may " + joined(member.path("scopes")));
            }
            return 0;
        }
    }

    @Command(name = "rm", description = "Remove a member. Their personal tokens for this tenant are deleted.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "USER")
        String user;

        @Override
        int execute() {
            printLeft(this, api().delete("/v1/members/" + Api.segment(user)));
            return 0;
        }
    }
}
