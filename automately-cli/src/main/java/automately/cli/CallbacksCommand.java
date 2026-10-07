package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "callbacks", description = "Completion callbacks: the secret that signs them.",
        subcommands = {CallbacksCommand.Secret.class})
final class CallbacksCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "secret", description = "Print the secret that signs your callbacks (Automately-Signature).")
    static final class Secret extends Base {
        @Option(names = "--rotate", description = "Replace it first; receivers must switch to the new one.")
        boolean rotate;

        @Override
        int execute() {
            JsonNode secret = rotate ? api().post("/v1/callbacks/secret/rotate", null) : api().get("/v1/callbacks/secret");
            if (!printedJson(secret)) {
                out().println(Json.text(secret, "secret"));
            }
            return 0;
        }
    }
}
