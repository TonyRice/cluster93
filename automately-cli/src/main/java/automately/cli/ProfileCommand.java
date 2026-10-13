package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

@Command(name = "profile", description = "Your user profile and tenant memberships. With options, change your name, display name or email.",
        subcommands = {ProfileCommand.Passwd.class})
final class ProfileCommand extends Base {

    @Option(names = "--name", paramLabel = "NAME", description = "A new user name; your memberships and tokens follow.")
    String name;

    @Option(names = "--display-name", paramLabel = "TEXT")
    String displayName;

    @Option(names = "--email", paramLabel = "EMAIL")
    String email;

    @Override
    int execute() {
        if (name != null || displayName != null || email != null) {
            JsonNode current = api().get("/v1/me");
            var body = Json.object();
            if (name != null) {
                body.put("name", name);
            }
            body.put("displayName", displayName != null ? displayName : Json.text(current, "displayName").isEmpty() ? null : Json.text(current, "displayName"));
            body.put("email", email != null ? email : Json.text(current, "email").isEmpty() ? null : Json.text(current, "email"));
            api().patch("/v1/me", body);
        }
        JsonNode me = api().get("/v1/me");
        if (printedJson(me)) {
            return 0;
        }
        out().println(Json.text(me, "name") + (Json.text(me, "displayName").isEmpty() ? "" : " (" + Json.text(me, "displayName") + ")")
                + (Json.text(me, "email").isEmpty() ? "" : " <" + Json.text(me, "email") + ">") + (me.path("operator").asBoolean() ? ", an operator" : ""));
        List<List<String>> rows = new ArrayList<>();
        me.path("memberships").forEach(m -> rows.add(List.of(Json.text(m, "tenant"), MembersCommand.joined(m.path("roles")),
                MembersCommand.joined(m.path("groups")), MembersCommand.joined(m.path("scopes")))));
        Output.table(out(), List.of("TENANT", "ROLES", "GROUPS", "SCOPES"), rows);
        return 0;
    }

    /** Sets or changes the signed-in user's password (D-133). */
    @Command(name = "passwd", description = "Set or change your password (PUT /v1/me/password).")
    static final class Passwd extends Base {

        @Option(names = "--password", paramLabel = "PASSWORD", description = "The new password (prompts if omitted).")
        String password;

        @Option(names = "--current-password", paramLabel = "PASSWORD", description = "Your current password, when changing one.")
        String currentPassword;

        @Override
        int execute() {
            String pass = password;
            if (pass == null && io().interactive() && System.console() != null) {
                char[] typed = System.console().readPassword("New password: ");
                pass = typed == null || typed.length == 0 ? null : new String(typed);
            }
            if (pass == null || pass.isBlank()) {
                throw new CliException("No password given. Pass --password, or type it when asked.", 2);
            }
            String current = currentPassword;
            boolean has = api().get("/v1/me").path("hasPassword").asBoolean(false);
            if (has && (current == null || current.isBlank()) && io().interactive() && System.console() != null) {
                char[] typed = System.console().readPassword("Current password: ");
                current = typed == null || typed.length == 0 ? null : new String(typed);
            }
            var body = Json.object().put("newPassword", pass);
            if (current != null && !current.isBlank()) {
                body.put("currentPassword", current);
            }
            api().put("/v1/me/password", body);
            out().println("Password updated.");
            return 0;
        }
    }
}
