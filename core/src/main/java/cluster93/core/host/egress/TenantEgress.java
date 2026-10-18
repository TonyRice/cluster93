package cluster93.core.host.egress;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One tenant's egress policy, set by an operator (D-108, D-111): allow and deny rules, and optionally a posture of its
 * own instead of the cluster's. Empty unless an operator sets it. Nothing in it reaches the cluster's own nodes.
 *
 * @param posture null to follow the cluster's
 * @param allowLocal whether this tenant's scripts may reach <b>here</b> — the machine the connection would come from
 *                   (D-174). It is not the same as allowing private networks: link-local (cloud metadata), RFC1918,
 *                   CGNAT and ULA stay refused, and so do the node's own ports.
 * @param placement where this tenant's connections are decided (D-173 item 8), or null to follow the node's
 *                  {@code C93_EGRESS_PLACEMENT}. One tenant at a time can be placed the other way, which is what a
 *                  node-level default cannot express.
 */
public record TenantEgress(String tenant, Posture posture, List<EgressRule> rules, boolean allowLocal,
                           Placement placement) {

    public static final int MAX_RULES = 100;

    public TenantEgress {
        rules = List.copyOf(rules);
    }

    public static TenantEgress none(String tenant) {
        return new TenantEgress(tenant, null, List.of(), false, null);
    }

    /**
     * Whether an operator has set nothing at all here. A policy that is empty is <b>removed</b> rather than stored, so
     * a placement on its own counts: "this tenant is decided on the node" is a policy an operator meant to keep.
     */
    public boolean isEmpty() {
        return posture == null && rules.isEmpty() && !allowLocal && placement == null;
    }

    public JsonObject toJson() {
        JsonArray list = new JsonArray();
        rules.forEach(rule -> list.add(rule.toJson()));
        JsonObject json = new JsonObject().put("tenant", tenant).put("rules", list);
        if (posture != null) {
            json.put("posture", posture.code);
        }
        if (allowLocal) {
            json.put("allowLocal", true);
        }
        if (placement != null) {
            json.put("placement", placement.code);
        }
        return json;
    }

    /**
     * Parses a stored or submitted policy; throws {@link IllegalArgumentException} with a message fit for the API. The
     * older shape, an {@code "allow"} list (D-108), is read as allow rules.
     */
    public static TenantEgress fromJson(String tenant, JsonObject json) {
        List<EgressRule> rules = new ArrayList<>();
        read(json, "allow", EgressRule.Action.ALLOW, rules);
        read(json, "rules", null, rules);
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("At most " + MAX_RULES + " rules per tenant");
        }
        Object posture = json.getValue("posture");
        return new TenantEgress(tenant, posture == null || "inherit".equals(posture) ? null : Posture.parse(posture), rules,
                json.getBoolean("allowLocal", false), Placement.parseOrInherit(json.getValue("placement")));
    }

    static void read(JsonObject json, String field, EgressRule.Action forced, List<EgressRule> into) {
        Object value = json.getValue(field);
        if (value == null) {
            return;
        }
        if (!(value instanceof JsonArray list)) {
            throw new IllegalArgumentException("\"" + field + "\" must be a list of rules");
        }
        for (Object rule : list) {
            if (!(rule instanceof JsonObject object)) {
                throw new IllegalArgumentException("Each rule is an object: {\"action\": \"allow\", \"to\": \"10.0.5.20/32\", \"ports\": [5432]}");
            }
            into.add(EgressRule.fromJson(object, forced));
        }
    }
}
