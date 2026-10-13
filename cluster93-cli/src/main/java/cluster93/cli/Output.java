package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Human output by default; with --json, the API's JSON as is. */
final class Output {

    private Output() {
    }

    static void table(PrintStream out, List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        List<List<String>> all = new ArrayList<>();
        all.add(headers);
        all.addAll(rows);
        for (List<String> row : all) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        for (List<String> row : all) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                line.append(i == row.size() - 1 ? row.get(i) : String.format("%-" + (widths[i] + 2) + "s", row.get(i)));
            }
            out.println(line.toString().stripTrailing());
        }
        if (rows.isEmpty()) {
            out.println("(none)");
        }
    }

    static void json(PrintStream out, JsonNode node) {
        out.println(node == null ? "null" : Json.pretty(node));
    }

    /** Milliseconds since the epoch as an ISO instant, or "". */
    static String time(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? "" : java.time.Instant.ofEpochMilli(value.asLong()).toString();
    }
}
