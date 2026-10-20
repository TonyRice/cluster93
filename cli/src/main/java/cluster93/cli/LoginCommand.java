package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Command(name = "login", description = {"Save a cluster URL and token for later commands (checked against the cluster first).",
        "  login URL                      with a token you paste",
        "  login URL --invite CODE        redeem an invite: as a new user (--name), or as you if you're logged in there",
        "  login --tenant NAME            switch to another of your tenants, with a new personal token"})
final class LoginCommand extends Base {

    @Parameters(index = "0", arity = "0..1", paramLabel = "URL", description = "The cluster, e.g. https://cluster93.example.com")
    String url;

    @Option(names = "--invite", paramLabel = "CODE", description = "An invite code.")
    String invite;

    @Option(names = "--name", paramLabel = "USER", description = "With --invite: the user name to take, for a new user.")
    String name;

    @Option(names = "--password", paramLabel = "PASSWORD", description = "With --invite: the password for console login (prompts if omitted).")
    String password;

    @Override
    int execute() throws Exception {
        Config saved = Config.load(io(), profile());
        String base = url != null ? strip(url) : saved != null && saved.url() != null ? saved.url() : root().config().url();
        if (base == null) {
            throw new CliException("Which cluster? Run: c93 login <url>", 2);
        }
        if (invite != null) {
            return redeem(base, saved);
        }
        if (root().tenant != null) {
            return switchTenant(base);
        }
        String token = root().token != null ? root().token : io().env().get("C93_TOKEN");
        if (token == null || token.isBlank()) {
            token = readToken();
        }
        return save(new Config(base, token.trim()));
    }

    /** Redeems an invite; as the saved user if there's a personal login for this cluster, else as a new user. */
    private int redeem(String base, Config saved) {
        String current = name == null && saved != null && base.equals(saved.url()) ? saved.token() : null;
        if (current != null) {
            try {
                if (Json.text(new Api(new Config(base, current)).get("/v1/whoami"), "user").isEmpty()) {
                    current = null;
                }
            } catch (CliException e) {
                current = null;
            }
        }
        ObjectNode body = Json.object().put("code", invite.trim()).put("tokenName", "cli");
        if (name != null) {
            body.put("name", name);
        }
        String pass = password;
        if (pass == null && current == null && io().interactive() && System.console() != null) {
            char[] typed = System.console().readPassword("Password (for console login): ");
            pass = typed == null || typed.length == 0 ? null : new String(typed);
        }
        if (pass != null && !pass.isBlank()) {
            body.put("password", pass);
        }
        JsonNode redeemed = new Api(new Config(base, current)).post("/v1/invites/redeem", body);
        JsonNode user = redeemed.path("user");
        err().println(redeemed.path("operator").asBoolean()
                ? Json.text(user, "name") + " is now an operator."
                : Json.text(user, "name") + " joined " + Json.text(redeemed, "tenant") + " as " + Json.write(redeemed.path("roles"))
                        + (redeemed.path("groups").isEmpty() ? "" : ", in " + Json.write(redeemed.path("groups"))) + ".");
        return save(new Config(base, Json.text(redeemed.path("token"), "token")));
    }

    private int switchTenant(String base) {
        Config current = root().config().require();
        JsonNode minted = new Api(new Config(base, current.token())).post("/v1/me/tokens",
                Json.object().put("name", "cli").put("tenant", root().tenant));
        return save(new Config(base, Json.text(minted, "token")));
    }

    private int save(Config config) {
        JsonNode who = new Api(config).get("/v1/whoami");
        config.save(io(), profile());
        out().println("Logged in to " + config.url() + " as " + WhoamiCommand.describe(who) + ".");
        out().println("Saved to " + Config.file(io(), profile()));
        return 0;
    }

    private static String strip(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String readToken() throws Exception {
        if (io().interactive() && System.console() != null) {
            char[] typed = System.console().readPassword("Token: ");
            if (typed != null && typed.length > 0) {
                return new String(typed);
            }
        } else {
            String line = new BufferedReader(new InputStreamReader(io().in(), StandardCharsets.UTF_8)).readLine();
            if (line != null && !line.isBlank()) {
                return line;
            }
        }
        throw new CliException("No token given. Pass --token, set C93_TOKEN, or type it when asked.", 2);
    }
}
