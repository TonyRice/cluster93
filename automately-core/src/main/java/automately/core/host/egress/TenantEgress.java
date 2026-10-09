package automately.core.host.egress;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * What an operator lets one tenant reach beyond the public internet (D-108): private or reserved addresses, by CIDR
 * or by name, on given ports. Empty unless an operator sets it. It never covers the cluster's own nodes.
 */
public record TenantEgress(String tenant, List<EgressRule> allow) {

    public static final int MAX_RULES = 100;

    public TenantEgress {
        allow = List.copyOf(allow);
    }

    public static TenantEgress none(String tenant) {
        return new TenantEgress(tenant, List.of());
    }

    public boolean allows(String requestedHost, InetAddress address, int port) {
        for (EgressRule rule : allow) {
            if (rule.allows(requestedHost, address, port)) {
                return true;
            }
        }
        return false;
    }

    public JsonObject toJson() {
        JsonArray rules = new JsonArray();
        allow.forEach(rule -> rules.add(rule.toJson()));
        return new JsonObject().put("tenant", tenant).put("allow", rules);
    }

    /** Parses a stored or submitted policy; throws {@link IllegalArgumentException} with a message fit for the API. */
    public static TenantEgress fromJson(String tenant, JsonObject json) {
        Object allow = json.getValue("allow");
        if (allow != null && !(allow instanceof JsonArray)) {
            throw new IllegalArgumentException("\"allow\" must be a list of rules");
        }
        JsonArray list = allow == null ? new JsonArray() : (JsonArray) allow;
        if (list.size() > MAX_RULES) {
            throw new IllegalArgumentException("At most " + MAX_RULES + " rules per tenant");
        }
        List<EgressRule> rules = new ArrayList<>();
        for (Object rule : list) {
            if (!(rule instanceof JsonObject object)) {
                throw new IllegalArgumentException("Each rule is an object: {\"to\": \"10.0.5.20/32\", \"ports\": [5432]}");
            }
            rules.add(EgressRule.fromJson(object));
        }
        return new TenantEgress(tenant, rules);
    }
}
