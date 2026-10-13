package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Command(name = "secrets", description = "Secrets your scripts read with secrets.get(name). Values go in and never come back out.",
        subcommands = {SecretsCommand.Set.class, SecretsCommand.ListSecrets.class, SecretsCommand.Remove.class})
final class SecretsCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "set", description = "Create or replace a secret. The value is read from standard input (or typed, hidden) unless --value is given.")
    static final class Set extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Option(names = "--value", description = "The value; prefer standard input, which keeps it out of shell history.")
        String value;

        @Override
        int execute() throws Exception {
            String secret = value != null ? value : read();
            JsonNode saved = api().put("/v1/secrets/" + Api.segment(name), Json.object().put("value", secret));
            if (!printedJson(saved)) {
                out().println("Saved secret " + name + " (version " + saved.path("version").asText() + "). Scripts read it with secrets.get('"
                        + name + "') when their token has secrets:use.");
            }
            return 0;
        }

        /** Typed and hidden at a terminal; otherwise all of standard input, without one trailing newline. */
        private String read() throws Exception {
            if (io().interactive() && System.console() != null) {
                char[] typed = System.console().readPassword("Value for %s: ", name);
                return typed == null ? "" : new String(typed);
            }
            String piped = new String(io().in().readAllBytes(), StandardCharsets.UTF_8);
            return piped.endsWith("\r\n") ? piped.substring(0, piped.length() - 2) : piped.endsWith("\n") ? piped.substring(0, piped.length() - 1) : piped;
        }
    }

    @Command(name = "list", description = "Your secrets' names and versions.")
    static final class ListSecrets extends Base {
        @Override
        int execute() {
            JsonNode all = api().get("/v1/secrets");
            if (printedJson(all)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            all.path("secrets").forEach(s -> rows.add(List.of(Json.text(s, "name"), s.path("version").asText(), Output.time(s, "updatedAt"))));
            Output.table(out(), List.of("NAME", "VERSION", "UPDATED"), rows);
            return 0;
        }
    }

    @Command(name = "rm", description = "Delete a secret.")
    static final class Remove extends Base {
        @Parameters(paramLabel = "NAME")
        String name;

        @Override
        int execute() {
            api().delete("/v1/secrets/" + Api.segment(name));
            out().println("Deleted secret " + name);
            return 0;
        }
    }
}
