package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "invites", description = "Invite people to a tenant or cluster. They redeem the code with: automately login URL --invite CODE.",
        subcommands = {InvitesCommand.Create.class, InvitesCommand.Ls.class, InvitesCommand.Rm.class})
final class InvitesCommand extends Base {

    @Override
    int execute() {
        return Ls.list(this);
    }

    @Command(name = "create", description = {"Invite someone, with roles and groups you hold yourself. Prints the code once, to stdout.",
            "Operators: --tenant NAME invites into any tenant (e.g. its first owner); --operator invites an operator."})
    static final class Create extends Base {
        @Option(names = {"-r", "--role"}, paramLabel = "ROLE", description = "Repeatable: owner, admin, developer, viewer, or your own.")
        List<String> roles;

        @Option(names = {"-g", "--group"}, paramLabel = "GROUP", description = "Repeatable.")
        List<String> groups;

        @Option(names = "--user", paramLabel = "USER", description = "Only this user may redeem it (or a new user takes this name).")
        String user;

        @Option(names = "--expires-in-days", paramLabel = "DAYS", description = "1 to 30 (default 7).")
        Integer days;

        @Option(names = "--note", paramLabel = "TEXT")
        String note;

        @Option(names = "--operator", description = "Operators: invite another operator.")
        boolean operator;

        @Override
        int execute() {
            var body = Json.object();
            if (!operator) {
                body.set("roles", Json.strings(roles));
                body.set("groups", Json.strings(groups));
            }
            if (user != null) {
                body.put("user", user);
            }
            if (days != null) {
                body.put("expiresInDays", days);
            }
            if (note != null) {
                body.put("note", note);
            }
            String path = operator ? "/v1/admin/invites" : root().tenant != null ? "/v1/tenants/" + Api.segment(root().tenant) + "/invites" : "/v1/invites";
            JsonNode created = api().post(path, body);
            if (!printedJson(created)) {
                out().println(Json.text(created, "code"));
                err().println("Invite " + Json.text(created, "id") + (operator ? " to become an operator" : " to " + Json.text(created, "tenant"))
                        + ", until " + Output.time(created, "expiresAt") + ". The code is shown only once; they run:");
                err().println("  automately login " + api().url() + " --invite <code>" + (user == null ? " --name <their user name>" : ""));
            }
            return 0;
        }
    }

    @Command(name = "ls", description = "Open invites (never their codes).")
    static final class Ls extends Base {
        @Override
        int execute() {
            return list(this);
        }

        static int list(Base command) {
            JsonNode invites = command.api().get("/v1/invites");
            if (command.printedJson(invites)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            invites.path("invites").forEach(i -> rows.add(List.of(Json.text(i, "id"), MembersCommand.joined(i.path("roles")),
                    MembersCommand.joined(i.path("groups")), Json.text(i, "user"), Json.text(i, "createdBy"), Output.time(i, "expiresAt"))));
            Output.table(command.out(), List.of("ID", "ROLES", "GROUPS", "FOR", "BY", "EXPIRES"), rows);
            return 0;
        }
    }

    @Command(name = "rm", description = "Withdraw an invite.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        int execute() {
            api().delete("/v1/invites/" + Api.segment(id));
            out().println("Withdrew " + id);
            return 0;
        }
    }
}
