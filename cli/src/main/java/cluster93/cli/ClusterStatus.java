package cluster93.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code c93 cluster status|nodes|doctor}: configured inventory versus live membership (D-188). */
final class ClusterStatus {

    private static final Duration EXPIRY_WARNING = Duration.ofDays(90);

    private ClusterStatus() {
    }

    /** One problem found while reconciling the inventory with the live cluster. */
    record Problem(String node, String code, String message) {
    }

    /** The inventory plus what the API said, and what does not line up. */
    record Snapshot(ClusterFile cluster, Map<String, JsonNode> live, String apiError, List<Problem> problems) {
        boolean ok() {
            return apiError == null && problems.isEmpty();
        }
    }

    static Snapshot collect(Base base, ClusterFile cluster) {
        Map<String, JsonNode> live = new LinkedHashMap<>();
        String apiError = null;
        try {
            JsonNode answer = base.api().get("/v1/nodes");
            JsonNode nodes = answer.path("nodes");
            if (nodes.isArray()) {
                for (JsonNode node : nodes) {
                    live.put(node.path("id").asText(""), node);
                }
            }
        } catch (CliException e) {
            apiError = e.getMessage();
        }

        List<Problem> problems = new ArrayList<>();
        if (apiError != null) {
            problems.add(new Problem("", "api_unreachable", apiError));
        }
        for (JsonNode node : cluster.nodes()) {
            String id = node.path("id").asText("");
            boolean revoked = node.path("revoked").asBoolean(false);
            JsonNode running = live.get(id);
            Instant expires = instant(node, "expires");
            if (expires != null && expires.isBefore(Instant.now())) {
                problems.add(new Problem(id, "certificate_expired", "certificate expired " + expires));
            }
            Path certificate = cluster.relative(node.path("certificate").asText(""));
            if (certificate != null && !Files.isRegularFile(certificate)) {
                problems.add(new Problem(id, "certificate_missing", "no certificate at " + certificate));
            }
            Path join = cluster.relative(node.path("join").asText(""));
            if (join != null && !Files.isRegularFile(join)) {
                problems.add(new Problem(id, "join_missing", "no join line at " + join));
            }
            if (running == null && !revoked) {
                problems.add(new Problem(id, "missing", "in the inventory but not in /v1/nodes"));
            }
            if (running != null && revoked) {
                problems.add(new Problem(id, "revoked_live", "revoked but still live"));
            }
        }
        for (String id : live.keySet()) {
            if (cluster.node(id) == null) {
                problems.add(new Problem(id, "unlisted", "live but not in the inventory"));
            }
        }
        return new Snapshot(cluster, live, apiError, problems);
    }

    static int status(Base base, Path file, boolean check) {
        ClusterFile cluster = ClusterFile.load(file);
        if (cluster == null) {
            throw new CliException("No cluster inventory at " + ClusterFile.resolve(file)
                    + ". Run `c93 cluster init`, or pass --file.", 2);
        }
        Snapshot snapshot = collect(base, cluster);
        if (base.json()) {
            Output.json(base.out(), json(snapshot));
        } else {
            base.out().println("Cluster:   " + (cluster.name().isBlank() ? "-" : cluster.name()));
            base.out().println("API:       " + (snapshot.apiError() == null ? base.api().url() : "unreachable (" + snapshot.apiError() + ")"));
            base.out().println("Inventory: " + cluster.file());
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode node : cluster.nodes()) {
                String id = node.path("id").asText("");
                JsonNode live = snapshot.live().get(id);
                rows.add(List.of(id,
                        state(node, live),
                        join(node.path("addresses")),
                        expiry(node),
                        live == null ? "-" : live.path("capacity").asText("-"),
                        live == null ? "-" : live.path("running").asText("-"),
                        live == null ? "-" : live.path("endpoint").asText("-")));
            }
            Output.table(base.out(), List.of("NODE", "STATE", "ADDRESSES", "CERT EXPIRES", "CAPACITY", "RUNNING", "ENDPOINT"), rows);
            List<String> extra = liveNotListed(snapshot);
            if (!extra.isEmpty()) {
                base.out().println("Live but not in the inventory: " + String.join(", ", extra));
            }
            if (!snapshot.ok()) {
                base.out().println("Problems:");
                for (Problem problem : snapshot.problems()) {
                    base.out().println("  " + (problem.node().isBlank() ? "" : problem.node() + ": ") + problem.message());
                }
            }
        }
        if (check && !snapshot.ok()) {
            return 1;
        }
        return 0;
    }

    static int nodes(Base base) {
        JsonNode answer = base.api().get("/v1/nodes");
        JsonNode nodes = answer.path("nodes");
        if (base.json()) {
            Output.json(base.out(), answer);
            return 0;
        }
        List<List<String>> rows = new ArrayList<>();
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                rows.add(List.of(node.path("id").asText("-"), node.path("capacity").asText("-"),
                        node.path("running").asText("-"), node.path("endpoint").asText("-"),
                        node.path("lastSeen").asText("-")));
            }
        }
        Output.table(base.out(), List.of("NODE", "CAPACITY", "RUNNING", "ENDPOINT", "LAST SEEN"), rows);
        return 0;
    }

    static int doctor(Base base, Path file) {
        ClusterFile cluster = ClusterFile.load(file);
        if (cluster == null) {
            throw new CliException("No cluster inventory at " + ClusterFile.resolve(file)
                    + ". Run `c93 cluster init`, or pass --file.", 2);
        }
        Snapshot snapshot = collect(base, cluster);
        boolean failed = false;
        List<List<String>> rows = new ArrayList<>();
        Path caCert = cluster.dir().resolve("ca.crt");
        Path caKey = cluster.dir().resolve("ca.key");
        failed |= check(rows, Files.isRegularFile(caCert), "ca.crt", "at " + caCert);
        failed |= check(rows, Files.isRegularFile(caKey), "ca.key", "at " + caKey);
        failed |= check(rows, snapshot.apiError() == null, "API reachable",
                snapshot.apiError() == null ? "read /v1/nodes" : snapshot.apiError());
        for (JsonNode node : cluster.nodes()) {
            String id = node.path("id").asText("");
            Instant expires = instant(node, "expires");
            if (expires == null) {
                rows.add(List.of("WARN", id, "certificate expiry", "not recorded"));
            } else if (expires.isBefore(Instant.now())) {
                rows.add(List.of("FAIL", id, "certificate expiry", "expired " + expires));
                failed = true;
            } else if (expires.isBefore(Instant.now().plus(EXPIRY_WARNING))) {
                rows.add(List.of("WARN", id, "certificate expiry", "expires " + expires));
            } else {
                rows.add(List.of("ok", id, "certificate expiry", expires.toString()));
            }
        }
        for (Problem problem : snapshot.problems()) {
            if ("api_unreachable".equals(problem.code())) {
                continue;
            }
            rows.add(List.of("FAIL", problem.node().isBlank() ? "-" : problem.node(), problem.code(), problem.message()));
            failed = true;
        }
        if (base.json()) {
            ObjectNode result = json(snapshot).put("ok", !failed);
            ArrayNode checks = Json.array();
            for (List<String> row : rows) {
                checks.add(Json.object().put("status", row.get(0)).put("node", row.get(1))
                        .put("check", row.get(2)).put("detail", row.get(3)));
            }
            result.set("checks", checks);
            Output.json(base.out(), result);
        } else {
            Output.table(base.out(), List.of("STATUS", "NODE", "CHECK", "DETAIL"), rows);
        }
        return failed ? 1 : 0;
    }

    private static boolean check(List<List<String>> rows, boolean ok, String name, String detail) {
        rows.add(List.of(ok ? "ok" : "FAIL", "-", name, detail));
        return !ok;
    }

    private static ObjectNode json(Snapshot snapshot) {
        ClusterFile cluster = snapshot.cluster();
        ObjectNode result = Json.object()
                .put("file", cluster.file().toString())
                .put("name", cluster.name())
                .put("api", cluster.api())
                .put("ok", snapshot.ok());
        if (snapshot.apiError() != null) {
            result.put("apiError", snapshot.apiError());
        }
        ArrayNode nodes = Json.array();
        for (JsonNode node : cluster.nodes()) {
            ObjectNode row = ((ObjectNode) node).deepCopy();
            JsonNode live = snapshot.live().get(node.path("id").asText(""));
            row.put("state", state(node, live));
            if (live != null) {
                row.set("live", live);
            }
            nodes.add(row);
        }
        result.set("nodes", nodes);
        ArrayNode live = Json.array();
        for (JsonNode node : snapshot.live().values()) {
            if (cluster.node(node.path("id").asText("")) == null) {
                live.add(node);
            }
        }
        result.set("liveNotListed", live);
        ArrayNode problems = Json.array();
        for (Problem problem : snapshot.problems()) {
            problems.add(Json.object().put("node", problem.node()).put("code", problem.code())
                    .put("message", problem.message()));
        }
        result.set("problems", problems);
        return result;
    }

    private static String state(JsonNode node, JsonNode live) {
        if (node.path("revoked").asBoolean(false)) {
            return live == null ? "revoked" : "revoked, live";
        }
        return live == null ? "missing" : "live";
    }

    private static String expiry(JsonNode node) {
        Instant expires = instant(node, "expires");
        return expires == null ? "-" : expires.toString();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String join(JsonNode addresses) {
        if (!addresses.isArray()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        addresses.forEach(value -> values.add(value.asText()));
        return values.isEmpty() ? "-" : String.join(",", values);
    }

    private static List<String> liveNotListed(Snapshot snapshot) {
        List<String> extra = new ArrayList<>();
        for (String id : snapshot.live().keySet()) {
            if (snapshot.cluster().node(id) == null) {
                extra.add(id);
            }
        }
        return extra;
    }
}
