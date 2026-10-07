package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Command(name = "login", description = "Save a cluster URL and token for later commands (checked against the cluster first).")
final class LoginCommand extends Base {

    @Parameters(index = "0", paramLabel = "URL", description = "The cluster, e.g. https://automately.example.com")
    String url;

    @Override
    int execute() throws Exception {
        String token = root().token != null ? root().token : io().env().get("AUTOMATELY_TOKEN");
        if (token == null || token.isBlank()) {
            token = readToken();
        }
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        Config config = new Config(base, token.trim());
        JsonNode who = new Api(config).get("/v1/whoami");
        config.save(io());
        out().println("Logged in to " + base + " as " + WhoamiCommand.describe(who) + ".");
        out().println("Saved to " + io().configDir().resolve(Config.FILE));
        return 0;
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
        throw new CliException("No token given. Pass --token, set AUTOMATELY_TOKEN, or type it when asked.", 2);
    }
}
