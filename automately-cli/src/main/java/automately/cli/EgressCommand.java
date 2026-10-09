package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where your scripts may connect (D-108, D-111): each step of the policy in order, with why, and the limits. */
@Command(name = "egress", description = "Where your scripts may connect: each step of the policy in order, with why, and the limits.",
        subcommands = EgressCommand.Cluster.class)
final class EgressCommand extends Base {

    @Override
    int execute() {
        JsonNode policy = api().get("/v1/egress-policy");
        if (!printedJson(policy)) {
            print(this, policy);
        }
        return 0;
    }

    @Command(name = "cluster", description = "Operators: show the cluster's egress policy, or replace it with --file or set its --posture.")
    static final class Cluster extends Base {
        @Option(names = "--file", paramLabel = "POLICY.json", description = "Replace the whole policy: {\"posture\", \"rules\", \"addressSets\"}.")
        Path file;

        @Option(names = "--posture", paramLabel = "POSTURE", description = "public (public addresses allowed) or allow-list (only what rules allow).")
        String posture;

        @Override
        int execute() throws Exception {
            String path = "/v1/egress/cluster-policy";
            JsonNode policy;
            if (file == null && posture == null) {
                policy = api().get(path);
            } else {
                com.fasterxml.jackson.databind.node.ObjectNode body = file != null
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) Json.parse(Files.readString(file))
                        : (com.fasterxml.jackson.databind.node.ObjectNode) api().get(path).deepCopy();
                body.remove("builtInSets");
                if (posture != null) {
                    body.put("posture", posture);
                }
                policy = api().put(path, body);
            }
            if (printedJson(policy)) {
                return 0;
            }
            out().println("Cluster egress policy:");
            out().println("  Posture: " + Json.text(policy, "posture"));
            printRules(out(), policy.path("rules"), "  Rules (for every tenant):");
            JsonNode sets = policy.path("addressSets");
            if (!sets.isEmpty()) {
                out().println("  Address sets:");
                sets.fieldNames().forEachRemaining(name -> {
                    java.util.List<String> ranges = new java.util.ArrayList<>();
                    sets.path(name).forEach(range -> ranges.add(range.asText()));
                    out().println("    @" + name + ": " + String.join(", ", ranges));
                });
            }
            return 0;
        }
    }

    /** The policy as people read it; {@code tenants egress} prints the same. */
    static void print(Base command, JsonNode policy) {
        java.io.PrintStream out = command.out();
        JsonNode posture = policy.path("posture");
        out.println("Network access for " + Json.text(policy, "tenant") + " (posture: " + Json.text(posture, "value")
                + (Json.text(posture, "from").equals("tenant") ? ", set for this tenant" : ", the cluster's") + "):");
        out.println("Applied in this order:");
        int n = 1;
        for (JsonNode step : policy.path("steps")) {
            out.println("  " + n++ + ". " + Json.text(step, "to") + ": " + Json.text(step, "access"));
            out.println("       " + Json.text(step, "why"));
        }
        printRules(out, policy.path("clusterRules"), "Cluster rules:");
        printRules(out, policy.path("rules"), "This tenant's rules:");
        JsonNode limits = policy.path("limits");
        out.println("Limits: " + limits.path("concurrentRequests").asInt() + " requests at once per job, request bodies up to "
                + StorageCommand.mb(limits.path("requestBodyBytes").asLong()) + ", " + limits.path("timeoutMs").asLong() / 1000
                + " s to connect and between chunks, redirects not followed");
    }

    static void printRules(java.io.PrintStream out, JsonNode rules, String title) {
        if (rules.isEmpty()) {
            out.println(title + " none");
            return;
        }
        out.println(title);
        for (JsonNode rule : rules) {
            out.println("  " + describe(rule));
        }
    }

    static String describe(JsonNode rule) {
        java.util.List<String> ports = new java.util.ArrayList<>();
        rule.path("ports").forEach(port -> ports.add(port.asText()));
        java.util.List<String> protocols = new java.util.ArrayList<>();
        rule.path("protocols").forEach(protocol -> protocols.add(protocol.asText()));
        String text = (rule.path("action").asText("allow")) + " " + Json.text(rule, "to")
                + (ports.isEmpty() ? " on every port" : " on port" + (ports.size() == 1 && !ports.get(0).contains("-") ? " " : "s ") + String.join(", ", ports))
                + (protocols.isEmpty() ? "" : " (" + String.join(", ", protocols) + ")");
        return rule.hasNonNull("note") ? text + ": " + Json.text(rule, "note") : text;
    }
}
