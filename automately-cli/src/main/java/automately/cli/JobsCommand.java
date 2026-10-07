package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "jobs", description = "List and inspect jobs.",
        subcommands = {JobsCommand.Ls.class, JobsCommand.Get.class, JobsCommand.Logs.class, JobsCommand.Steps.class, JobsCommand.Cancel.class})
final class JobsCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    /**
     * Streams a job's events until it ends: logs to stderr when {@code outputToStdout}, else to stdout. Returns 0 if it
     * succeeded and 1 otherwise.
     */
    static int follow(Base command, Api api, String id, boolean outputToStdout) {
        java.io.PrintStream logs = outputToStdout ? command.err() : command.out();
        api.events(id, event -> {
            switch (Json.text(event, "type")) {
                case "log" -> logs.println(Json.text(event, "line"));
                case "attempt" -> logs.println("-- attempt " + Json.text(event, "attempt") + " on " + Json.text(event, "node")
                        + " (the previous one ended " + Json.text(event, "previousOutcome") + ")");
                default -> {
                }
            }
            return !"end".equals(Json.text(event, "type"));
        });
        JsonNode job = api.get("/v1/jobs/" + Api.segment(id));
        String status = Json.text(job, "status");
        if (outputToStdout) {
            if (command.json()) {
                Output.json(command.out(), job);
            } else if (job.hasNonNull("output")) {
                JsonNode output = job.get("output");
                command.out().println(output.isTextual() ? output.asText() : Json.pretty(output));
            }
        }
        if (!"SUCCEEDED".equals(status)) {
            command.err().println("Job " + id + " ended " + status + (job.hasNonNull("error") ? ": " + Json.text(job, "error") : ""));
            return 1;
        }
        return 0;
    }

    @Command(name = "ls", description = "Your jobs, newest first.")
    static final class Ls extends Base {
        @Option(names = "--limit", defaultValue = "20", description = "How many (at most 1000).")
        int limit;

        @Override
        int execute() {
            JsonNode jobs = api().get("/v1/jobs?limit=" + limit);
            if (printedJson(jobs)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            jobs.path("jobs").forEach(job -> rows.add(List.of(Json.text(job, "id"), Json.text(job, "status"), Json.text(job, "attempt"),
                    Json.text(job, "node"), job.path("service").isObject() ? Json.text(job.get("service"), "name") : "",
                    Output.time(job, "createdAt"))));
            Output.table(out(), List.of("ID", "STATUS", "ATTEMPT", "NODE", "SERVICE", "CREATED"), rows);
            return 0;
        }
    }

    @Command(name = "get", description = "One job, with its attempts and output.")
    static final class Get extends Base {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        int execute() {
            Output.json(out(), api().get("/v1/jobs/" + Api.segment(id)));
            return 0;
        }
    }

    @Command(name = "logs", description = "A job's logs: replayed if it ended, followed live if it's running.")
    static final class Logs extends Base {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        int execute() {
            return follow(this, api(), id, false);
        }
    }

    @Command(name = "steps", description = "The current attempt's checkpoint and completed steps.")
    static final class Steps extends Base {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        int execute() {
            Output.json(out(), api().get("/v1/jobs/" + Api.segment(id) + "/steps"));
            return 0;
        }
    }

    @Command(name = "cancel", description = "Cancel a job; a running one gets a graceful stop.")
    static final class Cancel extends Base {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        int execute() {
            JsonNode job = api().post("/v1/jobs/" + Api.segment(id) + "/cancel", null);
            if (!printedJson(job)) {
                out().println("Cancellation requested for " + id + " (" + Json.text(job, "status") + ").");
            }
            return 0;
        }
    }
}
