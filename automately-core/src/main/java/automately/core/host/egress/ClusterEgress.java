package automately.core.host.egress;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cluster's egress policy, set by an operator (D-111): rules for every tenant, its own named address sets, and
 * the default posture. A cluster deny can't be overridden by a tenant; a cluster allow applies after the tenant's rules.
 */
public record ClusterEgress(Posture posture, List<EgressRule> rules, Map<String, List<Cidr>> addressSets) {

    public static final int MAX_RULES = 500;
    public static final int MAX_SETS = 50;
    public static final int MAX_RANGES_PER_SET = 1000;

    /** A new cluster's policy: public posture, and SMTP refused to raw sockets. */
    public static final ClusterEgress DEFAULT = new ClusterEgress(Posture.PUBLIC, List.of(EgressRule.fromJson(new JsonObject()
            .put("action", "deny").put("to", "*").put("ports", new JsonArray().add("@smtp")).put("protocols", new JsonArray().add("tcp"))
            .put("note", "Mail ports (25, 465, 587) are closed to raw sockets: open SMTP from the cluster's addresses gets them blocklisted. "
                    + "An operator can allow a mail service with a narrower rule."))), Map.of());

    public ClusterEgress {
        rules = List.copyOf(rules);
        addressSets = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(addressSets));
    }

    public AddressSets sets() {
        return new AddressSets(addressSets);
    }

    public JsonObject toJson() {
        JsonArray list = new JsonArray();
        rules.forEach(rule -> list.add(rule.toJson()));
        JsonObject sets = new JsonObject();
        addressSets.forEach((name, ranges) -> sets.put(name, new JsonArray(ranges.stream().map(Cidr::toString).toList())));
        return new JsonObject().put("posture", posture.code).put("rules", list).put("addressSets", sets);
    }

    /** Parses a stored or submitted policy; throws {@link IllegalArgumentException} with a message fit for the API. */
    public static ClusterEgress fromJson(JsonObject json) {
        Map<String, List<Cidr>> sets = new LinkedHashMap<>();
        Object setsValue = json.getValue("addressSets");
        if (setsValue != null && !(setsValue instanceof JsonObject)) {
            throw new IllegalArgumentException("\"addressSets\" maps names to lists of CIDRs: {\"corp-public\": [\"198.51.100.0/24\"]}");
        }
        JsonObject setsJson = setsValue == null ? new JsonObject() : (JsonObject) setsValue;
        if (setsJson.size() > MAX_SETS) {
            throw new IllegalArgumentException("At most " + MAX_SETS + " address sets");
        }
        for (String name : setsJson.fieldNames()) {
            if (!AddressSets.NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("\"" + name + "\" isn't a set name: lower-case letters, digits and dashes");
            }
            if (AddressSets.isBuiltIn(name)) {
                throw new IllegalArgumentException("\"" + name + "\" is a built-in set and can't be redefined");
            }
            if (!(setsJson.getValue(name) instanceof JsonArray ranges) || ranges.isEmpty()) {
                throw new IllegalArgumentException("The set \"" + name + "\" needs a list of CIDRs");
            }
            if (ranges.size() > MAX_RANGES_PER_SET) {
                throw new IllegalArgumentException("The set \"" + name + "\" has more than " + MAX_RANGES_PER_SET + " ranges");
            }
            List<Cidr> parsed = new ArrayList<>();
            for (Object range : ranges) {
                parsed.add(Cidr.parse(String.valueOf(range).trim(), 0));
            }
            sets.put(name, parsed);
        }
        List<EgressRule> rules = new ArrayList<>();
        TenantEgress.read(json, "rules", null, rules);
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("At most " + MAX_RULES + " cluster rules");
        }
        AddressSets known = new AddressSets(sets);
        for (EgressRule rule : rules) {
            if (rule.kind() == EgressRule.Kind.SET && !known.knows(rule.name())) {
                throw new IllegalArgumentException("The rule for \"" + rule.to() + "\" names a set that isn't defined");
            }
        }
        Object posture = json.getValue("posture");
        return new ClusterEgress(posture == null ? Posture.PUBLIC : Posture.parse(posture), rules, sets);
    }

    /** The set names a tenant's rules may use: the built-in ones and the cluster's. */
    public Set<String> setNames() {
        Set<String> names = new java.util.LinkedHashSet<>();
        names.add("public");
        names.addAll(AddressSets.BUILT_IN.keySet());
        names.addAll(addressSets.keySet());
        return names;
    }
}
