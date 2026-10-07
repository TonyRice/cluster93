package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(name = "queues", description = "Durable queues: push messages for your services to consume, and look after dead letters.",
        subcommands = {QueuesCommand.Push.class, QueuesCommand.Stats.class, QueuesCommand.Dead.class, QueuesCommand.Retry.class,
                QueuesCommand.Discard.class})
final class QueuesCommand extends Base {

    @Override
    int execute() {
        spec.commandLine().usage(out());
        return 0;
    }

    @Command(name = "push", description = "Queue a message (any JSON); prints its id.")
    static final class Push extends Base {
        @Parameters(index = "0", paramLabel = "QUEUE")
        String queue;

        @Parameters(index = "1", paramLabel = "JSON", description = "The message, as JSON.")
        String message;

        @Option(names = "--delay-ms", paramLabel = "MS", description = "Deliver no sooner than this.")
        Long delayMs;

        @Option(names = "--key", paramLabel = "KEY", description = "Push at most once while a message with this key is queued.")
        String key;

        @Option(names = "--max-attempts", paramLabel = "N", description = "Deliveries before it's dead-lettered (default 5).")
        Integer maxAttempts;

        @Override
        int execute() {
            JsonNode parsed;
            try {
                parsed = Json.parse(message);
            } catch (RuntimeException e) {
                throw new CliException("The message must be JSON (quote a string: '\"hello\"')", 2);
            }
            ObjectNode body = Json.object();
            body.set("message", parsed);
            if (delayMs != null) {
                body.put("delayMs", delayMs);
            }
            if (key != null) {
                body.put("key", key);
            }
            if (maxAttempts != null) {
                body.put("maxAttempts", maxAttempts);
            }
            JsonNode pushed = api().post("/v1/queues/" + Api.segment(queue), body);
            if (!printedJson(pushed)) {
                out().println(Json.text(pushed, "id"));
            }
            return 0;
        }
    }

    @Command(name = "stats", description = "How many messages are ready, in flight and dead.")
    static final class Stats extends Base {
        @Parameters(paramLabel = "QUEUE")
        String queue;

        @Override
        int execute() {
            JsonNode stats = api().get("/v1/queues/" + Api.segment(queue));
            if (!printedJson(stats)) {
                Output.table(out(), List.of("QUEUE", "READY", "IN FLIGHT", "DEAD"), List.of(List.of(queue,
                        stats.path("ready").asText(), stats.path("inFlight").asText(), stats.path("dead").asText())));
            }
            return 0;
        }
    }

    @Command(name = "dead", description = "Messages that used up their attempts.")
    static final class Dead extends Base {
        @Parameters(paramLabel = "QUEUE")
        String queue;

        @Option(names = "--limit", paramLabel = "N", defaultValue = "100")
        int limit;

        @Override
        int execute() {
            JsonNode dead = api().get("/v1/queues/" + Api.segment(queue) + "/dead?limit=" + limit);
            if (printedJson(dead)) {
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            dead.path("messages").forEach(m -> rows.add(List.of(Json.text(m, "id"), m.path("attempt").asText(),
                    Output.time(m, "enqueuedAt"), String.valueOf(Json.text(m, "lastError")))));
            Output.table(out(), List.of("ID", "ATTEMPTS", "QUEUED", "LAST ERROR"), rows);
            return 0;
        }
    }

    @Command(name = "retry", description = "Put a dead-lettered message back on its queue.")
    static final class Retry extends Base {
        @Parameters(index = "0", paramLabel = "QUEUE")
        String queue;

        @Parameters(index = "1", paramLabel = "ID")
        String id;

        @Override
        int execute() {
            JsonNode retried = api().post("/v1/queues/" + Api.segment(queue) + "/dead/" + Api.segment(id) + "/retry", null);
            if (!printedJson(retried)) {
                out().println("Queued " + id + " again.");
            }
            return 0;
        }
    }

    @Command(name = "discard", description = "Delete a dead-lettered message.")
    static final class Discard extends Base {
        @Parameters(index = "0", paramLabel = "QUEUE")
        String queue;

        @Parameters(index = "1", paramLabel = "ID")
        String id;

        @Override
        int execute() {
            api().delete("/v1/queues/" + Api.segment(queue) + "/dead/" + Api.segment(id));
            out().println("Discarded " + id + ".");
            return 0;
        }
    }
}
