package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a worker's own egress gate refused, by reason, since its last report (D-176). Worker -> Node, Channel 0.
 *
 * <p>The counts are **deltas**, not totals: the worker keeps the tally, and what the node adds to its metrics is
 * whatever it has not been told yet. A report lost with a connection loses only its own delta — acceptable for
 * refusals, which are reported for visibility rather than for enforcement.
 *
 * @param tenantId the tenant the refusals were for
 * @param denied   how many connections were refused per {@code Decision.Reason#code}, since the last report
 */
public record AccountMessage(String tenantId, Map<String, Long> denied) {

    public Frame toFrame() {
        JsonObject counts = new JsonObject();
        Map<String, Long> refused = denied == null ? Map.of() : denied;
        refused.forEach(counts::put);
        return Frame.control(FrameType.ACCOUNT, new JsonObject()
                .put("tenantId", tenantId == null ? "" : tenantId)
                .put("denied", counts));
    }

    public static AccountMessage fromFrame(Frame frame) {
        if (frame.type() != FrameType.ACCOUNT) {
            throw new IllegalArgumentException("Not an ACCOUNT frame: " + frame.type());
        }
        return fromJson(frame.json());
    }

    static AccountMessage fromJson(JsonObject json) {
        JsonObject counts = json.getJsonObject("denied");
        Map<String, Long> denied = new LinkedHashMap<>();
        if (counts != null) {
            for (String reason : counts.fieldNames()) {
                Long count = counts.getLong(reason);
                if (count != null && count > 0) {
                    denied.put(reason, count);
                }
            }
        }
        return new AccountMessage(json.getString("tenantId"), Map.copyOf(denied));
    }
}
