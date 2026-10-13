package cluster93.core.host.egress;

import io.vertx.core.json.JsonObject;

/**
 * How much of the network scripts may use, set in the cluster's egress policy (D-111, D-116).
 *
 * @param connectsPerMinute       new sockets a tenant may open per minute on one node
 * @param socketsPerExecution     sockets one script may have open at once
 * @param socketsPerTenantPerNode sockets a tenant's scripts may have open at once on one node
 */
public record EgressLimits(int connectsPerMinute, int socketsPerExecution, int socketsPerTenantPerNode) {

    public static final EgressLimits DEFAULT = new EgressLimits(120, 16, 256);

    public JsonObject toJson() {
        return new JsonObject().put("connectsPerMinute", connectsPerMinute).put("socketsPerExecution", socketsPerExecution)
                .put("socketsPerTenantPerNode", socketsPerTenantPerNode);
    }

    /** Parses the policy's {@code limits}, each field optional; throws {@link IllegalArgumentException} for the API. */
    static EgressLimits fromJson(Object value) {
        if (value == null) {
            return DEFAULT;
        }
        if (!(value instanceof JsonObject json)) {
            throw new IllegalArgumentException("\"limits\" is an object: {\"connectsPerMinute\": 120, \"socketsPerExecution\": 16, "
                    + "\"socketsPerTenantPerNode\": 256}");
        }
        return new EgressLimits(
                bounded(json, "connectsPerMinute", DEFAULT.connectsPerMinute, 100_000),
                bounded(json, "socketsPerExecution", DEFAULT.socketsPerExecution, 1_024),
                bounded(json, "socketsPerTenantPerNode", DEFAULT.socketsPerTenantPerNode, 65_536));
    }

    private static int bounded(JsonObject json, String field, int fallback, int max) {
        Object value = json.getValue(field);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() < 1 || number.longValue() > max) {
            throw new IllegalArgumentException("\"limits." + field + "\" is a whole number from 1 to " + max);
        }
        return number.intValue();
    }
}
