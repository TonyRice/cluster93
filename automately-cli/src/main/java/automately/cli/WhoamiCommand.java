package automately.cli;

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
        if (who.path("admin").asBoolean()) {
            return "the admin";
        }
        List<String> scopes = new ArrayList<>();
        who.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        return "tenant " + who.path("tenant").asText() + " (" + String.join(", ", scopes) + ")";
    }
}
