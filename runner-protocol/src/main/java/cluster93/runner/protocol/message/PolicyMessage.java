package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The egress policy a worker's placement runs under (D-173): what a worker needs to decide for itself instead of
 * asking the node per name. Node -> Worker, after {@link BindMessage} and again whenever the rules or the cluster's
 * own addresses change.
 *
 * <p>It carries primitives and the two policy objects as they are stored, so the protocol stays a transport contract:
 * the node serialises {@code ClusterEgress}/{@code TenantEgress}, the worker parses them with the same shared code.
 *
 * @param tenantId        the tenant the policy is for; the worker refuses one that isn't the tenant it is bound to
 * @param placement       where egress is decided for this tenant: {@code node} or {@code worker} (D-173 item 8)
 * @param version         monotonic per worker connection: an update older than the one in force is ignored
 * @param expiresInMs     how long this policy may be used without a refresh; past it a worker refuses everything
 *                        (fail closed, D-173 item 3)
 * @param allowPrivate    the development switch, {@code C93_EGRESS_ALLOW_PRIVATE}
 * @param internalDomains extra internal zones, {@code C93_EGRESS_INTERNAL_DOMAINS}
 * @param clusterPolicy   the cluster's egress policy as stored (rules, posture, sets, limits)
 * @param tenantPolicy    the tenant's egress policy as stored
 * @param nodeAddresses   the cluster's own node addresses, which a script may never reach (D-108)
 * @param nodePorts       this node's own listening ports, which the development switch still refuses
 * @param budget          this worker's share of the node's per-node limits (D-176), or null when the node allocated
 *                        none — then the cluster's own limits stand, which is what a node that predates the
 *                        allocation would mean
 */
public record PolicyMessage(String tenantId, String placement, long version, long expiresInMs, boolean allowPrivate,
                            List<String> internalDomains, JsonObject clusterPolicy, JsonObject tenantPolicy,
                            List<String> nodeAddresses, List<Integer> nodePorts, JsonObject budget) {

    /** Where egress is decided for a tenant (D-173 item 8). */
    public static final String NODE = "node";
    public static final String WORKER = "worker";

    /** A policy with no budget: the cluster's own limits stand (an older node allocates none, D-176). */
    public PolicyMessage(String tenantId, String placement, long version, long expiresInMs, boolean allowPrivate,
                         List<String> internalDomains, JsonObject clusterPolicy, JsonObject tenantPolicy,
                         List<String> nodeAddresses, List<Integer> nodePorts) {
        this(tenantId, placement, version, expiresInMs, allowPrivate, internalDomains, clusterPolicy, tenantPolicy,
                nodeAddresses, nodePorts, null);
    }

    public Frame toFrame() {
        JsonObject json = new JsonObject()
                .put("tenantId", tenantId)
                .put("placement", placement)
                .put("version", version)
                .put("expiresInMs", expiresInMs)
                .put("allowPrivate", allowPrivate)
                .put("internalDomains", new JsonArray(internalDomains == null ? List.of() : internalDomains))
                .put("clusterPolicy", clusterPolicy != null ? clusterPolicy : new JsonObject())
                .put("tenantPolicy", tenantPolicy != null ? tenantPolicy : new JsonObject())
                .put("nodeAddresses", new JsonArray(nodeAddresses == null ? List.of() : nodeAddresses))
                .put("nodePorts", new JsonArray(nodePorts == null ? List.of() : nodePorts));
        if (budget != null) {
            json.put("budget", budget);
        }
        return Frame.control(FrameType.POLICY, json);
    }

    public static PolicyMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.POLICY) {
            throw new IllegalArgumentException("Expected POLICY frame, got " + frame.type());
        }
        JsonObject json = frame.json();
        return new PolicyMessage(
                json.getString("tenantId"),
                json.getString("placement", WORKER),
                json.getLong("version", 0L),
                json.getLong("expiresInMs", 0L),
                json.getBoolean("allowPrivate", false),
                strings(json.getJsonArray("internalDomains")),
                json.getJsonObject("clusterPolicy", new JsonObject()),
                json.getJsonObject("tenantPolicy", new JsonObject()),
                strings(json.getJsonArray("nodeAddresses")),
                ints(json.getJsonArray("nodePorts")),
                json.getJsonObject("budget"));
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (Object value : array) {
                values.add(String.valueOf(value));
            }
        }
        return List.copyOf(values);
    }

    private static List<Integer> ints(JsonArray array) {
        List<Integer> values = new ArrayList<>();
        if (array != null) {
            for (Object value : array) {
                values.add(((Number) value).intValue());
            }
        }
        return List.copyOf(values);
    }
}
