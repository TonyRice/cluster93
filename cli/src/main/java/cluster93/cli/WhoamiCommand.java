package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;

@Command(name = "whoami", description = "Show which tenant and scopes the token has.")
final class WhoamiCommand extends Base {

    @Override
    int execute() {
        Api api = api();
        JsonNode who = api.get("/v1/whoami");
        if (!printedJson(who)) {
            out().println(describe(who) + " on " + api.url());
        }
        return 0;
    }

    static String describe(JsonNode who) {
        String user = Json.text(who, "user");
        if (who.path("admin").asBoolean()) {
            return user.isEmpty() ? "the admin" : user + ", an operator";
        }
        List<String> scopes = new ArrayList<>();
        who.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        return (user.isEmpty() ? "" : user + " in ") + "tenant " + who.path("tenant").asText()
                + (who.path("owner").asBoolean() ? ", an owner" : "") + " (" + String.join(", ", scopes) + ")";
    }
}
