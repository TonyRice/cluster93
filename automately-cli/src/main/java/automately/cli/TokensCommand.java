package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "tokens", description = "Your tenant's tokens (needs tokens:write).",
        subcommands = {TokensCommand.Create.class, TokensCommand.Ls.class, TokensCommand.Revoke.class})
final class TokensCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    static ObjectNode request(String name, List<String> scopes, Integer days) {
        ArrayNode array = Json.array();
        scopes.forEach(array::add);
        ObjectNode body = Json.object().put("name", name);
        body.set("scopes", array);
        if (days != null) {
            body.put("expiresInDays", days);
        }
        return body;
    }

    static void printCreated(Base command, JsonNode created) {
        if (command.printedJson(created)) {
            return;
        }
        command.out().println(Json.text(created, "token"));
        command.err().println("Token " + Json.text(created, "id") + " for " + Json.text(created, "tenant") + " with "
                + Json.write(created.path("scopes")) + ". It is shown only once.");
    }

    static void list(Base command, JsonNode tokens) {
        if (command.printedJson(tokens)) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        tokens.path("tokens").forEach(t -> {
            List<String> scopes = new ArrayList<>();
            t.path("scopes").forEach(s -> scopes.add(s.asText()));
            rows.add(List.of(Json.text(t, "id"), Json.text(t, "name"), String.join(",", scopes), Output.time(t, "createdAt"),
                    Output.time(t, "expiresAt"), Output.time(t, "lastUsedAt")));
        });
        Output.table(command.out(), List.of("ID", "NAME", "SCOPES", "CREATED", "EXPIRES", "LAST USED"), rows);
    }

    @Command(name = "create", description = "Mint a token for your tenant, with scopes your token also has. Prints the secret once.")
    static final class Create extends Base {
        @Parameters(paramLabel = "NAME", description = "What it's for, e.g. ci or laptop.")
        String name;

        @Option(names = {"-s", "--scope"}, required = true, paramLabel = "SCOPE",
                description = "Repeatable: jobs:read, jobs:write, services:read, services:write, files:read, files:write, "
                        + "bus:publish, bus:request, tokens:write, nodes:read, or *.")
        List<String> scopes;

        @Option(names = "--expires-in-days", paramLabel = "DAYS")
        Integer days;

        @Override
        int execute() {
            printCreated(this, api().post("/v1/tokens", request(name, scopes, days)));
            return 0;
        }
    }

    @Command(name = "ls", description = "Your tenant's tokens (never their secrets).")
    static final class Ls extends Base {
        @Override
        int execute() {
            list(this, api().get("/v1/tokens"));
            return 0;
        }
    }

    @Command(name = "revoke", description = "Revoke a token; it stops working on the next request.")
    static final class Revoke extends Base {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        int execute() {
            api().delete("/v1/tokens/" + Api.segment(id));
            out().println("Revoked " + id + ".");
            return 0;
        }
    }
}
