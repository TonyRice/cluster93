package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;

/** Submits a script and follows it: logs go to stderr, the output to stdout, and the exit code says how it ended. */
@Command(name = "run", description = "Run a script as a job and follow it to the end.")
final class RunCommand extends Base {

    @Parameters(index = "0", paramLabel = "SCRIPT", description = "An ES module whose default export receives (input, ctx).")
    Path script;

    @Option(names = {"-i", "--input"}, paramLabel = "JSON", description = "The job's input, a JSON object.")
    String input;

    @Option(names = "--input-file", paramLabel = "FILE", description = "Read the input from a JSON file.")
    Path inputFile;

    @Option(names = "--timeout", paramLabel = "MS", description = "Time limit in milliseconds.")
    Long timeoutMs;

    @Option(names = "--on-orphan", paramLabel = "POLICY", description = "fail (default), retry or resume, if the node dies.")
    String onOrphan;

    @Option(names = "--max-attempts", paramLabel = "N", description = "Attempts allowed with retry or resume.")
    Integer maxAttempts;

    @Option(names = {"-d", "--detach"}, description = "Print the job id and return without following it.")
    boolean detach;

    @Override
    int execute() throws Exception {
        ObjectNode body = Json.object().put("script", Files.readString(script));
        if (input != null || inputFile != null) {
            JsonNode parsed = Json.parse(input != null ? input : Files.readString(inputFile));
            if (!parsed.isObject()) {
                throw new CliException("The input must be a JSON object", 2);
            }
            body.set("input", parsed);
        }
        if (timeoutMs != null) {
            body.put("timeoutMs", timeoutMs);
        }
        if (onOrphan != null) {
            body.put("onOrphan", onOrphan);
        }
        if (maxAttempts != null) {
            body.put("maxAttempts", maxAttempts);
        }
        Api api = api();
        JsonNode job = api.post("/v1/jobs", body);
        String id = job.path("id").asText();
        if (detach) {
            out().println(json() ? Json.pretty(job) : id);
            return 0;
        }
        return JobsCommand.follow(this, api, id, true);
    }
}
