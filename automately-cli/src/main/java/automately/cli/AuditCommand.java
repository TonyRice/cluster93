package automately.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

@Command(name = "audit", description = "Who changed what, newest first (D-071). Needs audit:read; --operators shows the cluster's log.")
final class AuditCommand extends Base {

    @Option(names = "--limit", defaultValue = "50", paramLabel = "N", description = "How many (at most 1000).")
    int limit;

    @Option(names = "--before", paramLabel = "ID", description = "Start after this entry, for the next page.")
    String before;

    @Option(names = "--operators", description = "Operators: the cluster's entries, and everything operators did.")
    boolean operators;

    @Override
    int execute() {
        String path = (operators ? "/v1/admin/audit" : "/v1/audit") + "?limit=" + limit + (before == null ? "" : "&before=" + Api.segment(before));
        JsonNode page = api().get(path);
        if (printedJson(page)) {
            return 0;
        }
        List<List<String>> rows = new ArrayList<>();
        page.path("entries").forEach(e -> {
            JsonNode actor = e.path("actor");
            String who = !Json.text(actor, "user").isEmpty() ? Json.text(actor, "user") : actor.path("operator").asBoolean() ? "admin token"
                    : Json.text(actor, "token");
            rows.add(List.of(Output.time(e, "at"), who + (actor.path("operator").asBoolean() && !Json.text(actor, "user").isEmpty() ? " (operator)" : ""),
                    Json.text(e, "tenant"), Json.text(e, "action"), Json.text(e, "method") + " " + Json.text(e, "path"),
                    Json.text(e, "status"), Json.text(e, "ip")));
        });
        Output.table(out(), List.of("WHEN", "WHO", "TENANT", "ACTION", "REQUEST", "STATUS", "FROM"), rows);
        if (!Json.text(page, "next").isEmpty()) {
            err().println("More: automately audit" + (operators ? " --operators" : "") + " --before " + Json.text(page, "next"));
        }
        return 0;
    }
}
