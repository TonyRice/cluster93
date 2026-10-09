package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;

/** Where your scripts may connect (D-108): the defaults and why, the rules an operator granted, and the limits. */
@Command(name = "egress", description = "Where your scripts may connect: the defaults, your rules and the limits.")
final class EgressCommand extends Base {

    @Override
    int execute() {
        JsonNode policy = api().get("/v1/egress-policy");
        if (!printedJson(policy)) {
            print(this, policy);
        }
        return 0;
    }

    /** The policy as people read it; {@code tenants egress} prints the same. */
    static void print(Base command, JsonNode policy) {
        java.io.PrintStream out = command.out();
        out.println("Network access for " + Json.text(policy, "tenant") + ":");
        for (JsonNode item : policy.path("defaults")) {
            out.println("  " + Json.text(item, "to") + ": " + Json.text(item, "access"));
            out.println("      " + Json.text(item, "why"));
        }
        JsonNode rules = policy.path("allow");
        out.println(rules.isEmpty() ? "Rules: none" : "Rules (private addresses and hosts an operator allowed):");
        for (JsonNode rule : rules) {
            java.util.List<String> ports = new java.util.ArrayList<>();
            rule.path("ports").forEach(port -> ports.add(port.asText()));
            out.println("  " + Json.text(rule, "to") + " on port" + (ports.size() == 1 && !ports.get(0).contains("-") ? " " : "s ")
                    + String.join(", ", ports));
        }
        JsonNode limits = policy.path("limits");
        out.println("Limits: " + limits.path("concurrentRequests").asInt() + " requests at once per job, request bodies up to "
                + StorageCommand.mb(limits.path("requestBodyBytes").asLong()) + ", " + limits.path("timeoutMs").asLong() / 1000
                + " s to connect and between chunks, redirects not followed");
    }
}
