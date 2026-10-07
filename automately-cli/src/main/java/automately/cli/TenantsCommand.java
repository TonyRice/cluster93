package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "tenants", description = "Manage tenants and their tokens (admin token only).",
        subcommands = {TenantsCommand.Create.class, TenantsCommand.Ls.class, TenantsCommand.Rm.class, TenantsCommand.Token.class,
                TenantsCommand.Tokens.class, TenantsCommand.Revoke.class})
final class TenantsCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "create", description = "Create a tenant.")
    static final class Create extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            JsonNode tenant = api().post("/v1/tenants", Json.object().put("name", name));
            if (!printedJson(tenant)) {
                out().println("Created tenant " + name + ". Give it a token: automately tenants token " + name + " <token-name> --scope '*'");
            }
            return 0;
        }
    }

    @Command(name = "ls", description = "Every tenant.")
    static final class Ls extends Base {
        @Override
        int execute() {
            JsonNode tenants = api().get("/v1/tenants");
            if (printedJson(tenants)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            tenants.path("tenants").forEach(t -> rows.add(List.of(Json.text(t, "name"), Output.time(t, "createdAt"))));
            Output.table(out(), List.of("NAME", "CREATED"), rows);
            return 0;
        }
    }

    @Command(name = "rm", description = "Delete an empty tenant and its tokens.")
    static final class Rm extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/tenants/" + Api.segment(name));
            out().println("Deleted tenant " + name + ".");
            return 0;
        }
    }

    @Command(name = "token", description = "Mint a token for a tenant. Prints the secret once.")
    static final class Token extends Base {
        @Parameters(index = "0", paramLabel = "TENANT")
        String tenant;

        @Parameters(index = "1", paramLabel = "NAME")
        String name;

        @Option(names = {"-s", "--scope"}, required = true, paramLabel = "SCOPE", description = "Repeatable; * for every scope.")
        List<String> scopes;

        @Option(names = "--expires-in-days", paramLabel = "DAYS")
        Integer days;

        @Override
        int execute() {
            TokensCommand.printCreated(this, api().post("/v1/tenants/" + Api.segment(tenant) + "/tokens", TokensCommand.request(name, scopes, days)));
            return 0;
        }
    }

    @Command(name = "tokens", description = "A tenant's tokens.")
    static final class Tokens extends Base {
        @Parameters(paramLabel = "TENANT")
        String tenant;

        @Override
        int execute() {
            TokensCommand.list(this, api().get("/v1/tenants/" + Api.segment(tenant) + "/tokens"));
            return 0;
        }
    }

    @Command(name = "revoke", description = "Revoke one of a tenant's tokens.")
    static final class Revoke extends Base {
        @Parameters(index = "0", paramLabel = "TENANT")
        String tenant;

        @Parameters(index = "1", paramLabel = "ID")
        String id;

        @Override
        int execute() {
            api().delete("/v1/tenants/" + Api.segment(tenant) + "/tokens/" + Api.segment(id));
            out().println("Revoked " + id + ".");
            return 0;
        }
    }
}
