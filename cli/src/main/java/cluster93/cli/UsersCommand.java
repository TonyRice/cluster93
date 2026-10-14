package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "users", description = "Operators: manage all cluster users. Invite operators with: invites create --operator.",
        subcommands = {UsersCommand.Ls.class, UsersCommand.Rename.class, UsersCommand.Disable.class, UsersCommand.Enable.class,
                UsersCommand.Operator.class, UsersCommand.Rm.class})
final class UsersCommand extends Base {

    @Override
    int execute() {
        return Ls.list(this);
    }

    static int update(Base command, String user, String field, Object value, String done) {
        var body = Json.object();
        if (value instanceof Boolean flag) {
            body.put(field, flag);
        } else {
            body.put(field, String.valueOf(value));
        }
        JsonNode updated = command.api().patch("/v1/users/" + Api.segment(user), body);
        if (!command.printedJson(updated)) {
            command.out().println(done);
        }
        return 0;
    }

    @Command(name = "ls", description = "Every user.")
    static final class Ls extends Base {
        @Override
        int execute() {
            return list(this);
        }

        static int list(Base command) {
            JsonNode users = command.api().get("/v1/users");
            if (command.printedJson(users)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            users.path("users").forEach(u -> rows.add(List.of(Json.text(u, "name"), Json.text(u, "displayName"), Json.text(u, "email"),
                    u.path("operator").asBoolean() ? "yes" : "", u.path("disabled").asBoolean() ? "disabled" : "", Output.time(u, "createdAt"))));
            Output.table(command.out(), List.of("USER", "NAME", "EMAIL", "OPERATOR", "STATUS", "CREATED"), rows);
            return 0;
        }
    }

    @Command(name = "rename", description = "Change a user's name. Their memberships, tokens and history follow.")
    static final class Rename extends Base {
        @Parameters(index = "0", paramLabel = "USER")
        String user;

        @Parameters(index = "1", paramLabel = "NEW_NAME")
        String name;

        @Override
        int execute() {
            return update(this, user, "name", name, "Renamed " + user + " to " + name);
        }
    }

    @Command(name = "disable", description = "Stop every token the user has, on its next use.")
    static final class Disable extends Base {
        @Parameters(paramLabel = "USER")
        String user;

        @Override
        int execute() {
            return update(this, user, "disabled", true, "Disabled " + user);
        }
    }

    @Command(name = "enable", description = "Let a disabled user back in.")
    static final class Enable extends Base {
        @Parameters(paramLabel = "USER")
        String user;

        @Override
        int execute() {
            return update(this, user, "disabled", false, "Enabled " + user);
        }
    }

    @Command(name = "operator", description = "Make a user an operator, or with --remove, not one.")
    static final class Operator extends Base {
        @Parameters(paramLabel = "USER")
        String user;

        @Option(names = "--remove", description = "Take operator away; their operator tokens stop at once.")
        boolean remove;

        @Override
        int execute() {
            return update(this, user, "operator", !remove, remove ? user + " is no longer an operator" : user + " is now an operator");
        }
    }

    @Command(name = "rm", description = "Delete a user, their memberships and their tokens (not while they're a tenant's last owner).")
    static final class Rm extends Base {
        @Parameters(paramLabel = "USER")
        String user;

        @Override
        int execute() {
            MembersCommand.printLeft(this, api().delete("/v1/users/" + Api.segment(user)));
            return 0;
        }
    }
}
