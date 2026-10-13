package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

@Command(name = "profile", description = "Your user profile and tenant memberships. With options, change your name, display name or email.")
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
}
