package automately.core.host.egress;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One egress rule (D-108, D-111): allow or deny connections to a CIDR, an exact host name, a {@code *.suffix}, a named
 * address set ({@code @private}) or anything ({@code *}), on some ports, for some protocols.
 *
 * <p>A name or suffix rule matches by the name a script asked for; an allow by name then admits whatever it resolves to
 * (except cluster nodes, which the gate refuses first). A range or set rule matches by address: an allow by the address
 * the connection really reaches (an IPv6 form's embedded IPv4), a deny by either form, so neither can be dodged.
 *
 * @param ports     inclusive ranges; empty only on a deny, meaning every port
 * @param protocols empty means every protocol
 * @param note      why, for the tenant's view; may be null
 */
public record EgressRule(Action action, String to, Kind kind, Cidr cidr, String name, List<int[]> ports,
                         Set<EgressProtocol> protocols, String note) {

    public enum Action { ALLOW, DENY }

    public enum Kind { CIDR, HOST, SUFFIX, SET, ANY }

    private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)(\\.[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)*$");
    static final int MAX_PORT_RANGES = 32;
    static final int MAX_NOTE = 200;

    /** Named port lists a rule can use in {@code ports}. */
    public static final Map<String, List<int[]>> PORT_SETS = Map.of("smtp", List.of(new int[]{25, 25}, new int[]{465, 465}, new int[]{587, 587}));

    public EgressRule {
        ports = List.copyOf(ports);
        protocols = protocols.isEmpty() ? Set.of() : Set.copyOf(protocols);
    }

    /**
     * Whether this rule applies to a connection to {@code address} (and {@code checked}, the address it really reaches)
     * on {@code port}, asked for as {@code requestedHost}.
     */
    public boolean matches(String requestedHost, InetAddress address, InetAddress checked, int port, EgressProtocol protocol, AddressSets sets) {
        if (!opens(port) || !(protocols.isEmpty() || protocols.contains(protocol))) {
            return false;
        }
        boolean deny = action == Action.DENY;
        return switch (kind) {
            case ANY -> true;
            case HOST -> requestedHost != null && name.equals(normalise(requestedHost));
            case SUFFIX -> requestedHost != null && normalise(requestedHost).endsWith("." + name);
            case CIDR -> cidr.contains(checked) || (deny && cidr.contains(address));
            case SET -> {
                Boolean in = sets.contains(name, checked);
                if (in == null) {
                    yield deny;   // a set that no longer exists: a deny still refuses, an allow opens nothing
                }
                yield in || (deny && !address.equals(checked) && Boolean.TRUE.equals(sets.contains(name, address)));
            }
        };
    }

    /**
     * Whether this rule names {@code address} (or {@code host}), whatever the port and protocol: for what {@code dns}
     * may show (D-122).
     */
    public boolean names(String requestedHost, InetAddress address, InetAddress checked, AddressSets sets) {
        boolean deny = action == Action.DENY;
        return switch (kind) {
            case ANY -> true;
            case HOST -> requestedHost != null && name.equals(normalise(requestedHost));
            case SUFFIX -> requestedHost != null && normalise(requestedHost).endsWith("." + name);
            case CIDR -> address != null && (cidr.contains(checked) || (deny && cidr.contains(address)));
            case SET -> {
                if (address == null) {
                    yield false;
                }
                Boolean in = sets.contains(name, checked);
                yield in == null ? deny : in || (deny && !address.equals(checked) && Boolean.TRUE.equals(sets.contains(name, address)));
            }
        };
    }

    /** Whether a deny rule hides an address from {@code dns}: it applies on every port, to every protocol or to dns. */
    public boolean hidesFromDns() {
        return action == Action.DENY && ports.isEmpty() && (protocols.isEmpty() || protocols.contains(EgressProtocol.DNS));
    }

    boolean opens(int port) {
        if (ports.isEmpty()) {
            return true;
        }
        for (int[] range : ports) {
            if (port >= range[0] && port <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject().put("action", action.name().toLowerCase(Locale.ROOT)).put("to", to);
        if (!ports.isEmpty()) {
            JsonArray list = new JsonArray();
            for (int[] range : ports) {
                list.add(range[0] == range[1] ? (Object) range[0] : range[0] + "-" + range[1]);
            }
            json.put("ports", list);
        }
        if (!protocols.isEmpty()) {
            JsonArray list = new JsonArray();
            for (EgressProtocol protocol : EgressProtocol.values()) {
                if (protocols.contains(protocol)) {
                    list.add(protocol.code());
                }
            }
            json.put("protocols", list);
        }
        if (note != null) {
            json.put("note", note);
        }
        return json;
    }

    /** Parses one rule; throws {@link IllegalArgumentException} with a message fit for the API. */
    public static EgressRule fromJson(JsonObject json) {
        return fromJson(json, null);
    }

    /**
     * @param forced the action of a rule from the older {@code "allow"} list (D-108), which has none; null to read it
     */
    static EgressRule fromJson(JsonObject json, Action forced) {
        String to = json.getValue("to") instanceof String s ? s.trim() : null;
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("Each rule needs \"to\": a CIDR such as 10.0.5.20/32, a host name, *.example.com, @private or *");
        }
        Action action = forced != null ? forced : action(json.getValue("action"), to);
        List<int[]> ports = ports(json.getValue("ports"), to, action);
        Set<EgressProtocol> protocols = protocols(json.getValue("protocols"), to);
        Object noteValue = json.getValue("note");
        if (noteValue != null && !(noteValue instanceof String)) {
            throw new IllegalArgumentException("The rule for \"" + to + "\": \"note\" must be text");
        }
        String note = noteValue == null || ((String) noteValue).isBlank() ? null : ((String) noteValue).trim();
        if (note != null && note.length() > MAX_NOTE) {
            throw new IllegalArgumentException("The rule for \"" + to + "\": \"note\" is longer than " + MAX_NOTE + " characters");
        }
        if (to.equals("*")) {
            return new EgressRule(action, to, Kind.ANY, null, null, ports, protocols, note);
        }
        if (to.startsWith("@")) {
            String set = to.substring(1);
            if (!AddressSets.NAME.matcher(set).matches()) {
                throw new IllegalArgumentException("\"" + to + "\" isn't a set name: @ then lower-case letters, digits and dashes");
            }
            return new EgressRule(action, to, Kind.SET, null, set, ports, protocols, note);
        }
        if (to.startsWith("*.")) {
            String suffix = normalise(to.substring(2));
            if (!HOST.matcher(suffix).matches() || suffix.matches("[0-9.]+")) {
                throw new IllegalArgumentException("\"" + to + "\" isn't a domain suffix such as *.example.com");
            }
            if (!suffix.contains(".") && action == Action.ALLOW) {
                // *.ru is a fair thing to refuse; allowing a whole top-level domain is too broad to be what was meant.
                throw new IllegalArgumentException("\"" + to + "\" is a whole top-level domain: an allow needs at least *.example.com");
            }
            return new EgressRule(action, to, Kind.SUFFIX, null, suffix, ports, protocols, note);
        }
        if (to.contains("/") || Cidr.isLiteral(to)) {
            return new EgressRule(action, to, Kind.CIDR, Cidr.parse(to, 8), null, ports, protocols, note);
        }
        String host = normalise(to);
        if (!HOST.matcher(host).matches() || host.matches("[0-9.]+")) {
            throw new IllegalArgumentException("\"" + to + "\" is neither a CIDR, a host name, a *.suffix nor a @set");
        }
        return new EgressRule(action, to, Kind.HOST, null, host, ports, protocols, note);
    }

    private static Action action(Object value, String to) {
        if (value == null) {
            return Action.ALLOW;
        }
        if (value instanceof String s) {
            switch (s) {
                case "allow" -> {
                    return Action.ALLOW;
                }
                case "deny" -> {
                    return Action.DENY;
                }
                default -> {
                }
            }
        }
        throw new IllegalArgumentException("The rule for \"" + to + "\": \"action\" is allow or deny");
    }

    private static Set<EgressProtocol> protocols(Object value, String to) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof JsonArray array) || array.isEmpty()) {
            throw new IllegalArgumentException("The rule for \"" + to + "\": \"protocols\" is a list such as [\"tcp\"]");
        }
        Set<EgressProtocol> protocols = EnumSet.noneOf(EgressProtocol.class);
        for (Object protocol : array) {
            protocols.add(EgressProtocol.parse(String.valueOf(protocol)));
        }
        return protocols;
    }

    private static List<int[]> ports(Object value, String to, Action action) {
        if (value == null && action == Action.DENY) {
            return List.of();
        }
        if (!(value instanceof JsonArray array) || array.isEmpty()) {
            throw new IllegalArgumentException("The rule for \"" + to + "\" needs \"ports\": a list such as [5432, \"8000-8099\"]"
                    + (action == Action.ALLOW ? " (an allow always names its ports)" : ""));
        }
        if (array.size() > MAX_PORT_RANGES) {
            throw new IllegalArgumentException("The rule for \"" + to + "\" has more than " + MAX_PORT_RANGES + " ports or ranges");
        }
        List<int[]> ranges = new ArrayList<>();
        for (Object port : array) {
            String text = String.valueOf(port).trim();
            if (text.startsWith("@")) {
                List<int[]> set = PORT_SETS.get(text.substring(1));
                if (set == null) {
                    throw new IllegalArgumentException("The rule for \"" + to + "\" names an unknown port set \"" + text + "\" (there's @smtp)");
                }
                ranges.addAll(set);
                continue;
            }
            int dash = text.indexOf('-');
            try {
                int low = Integer.parseInt(dash < 0 ? text : text.substring(0, dash).trim());
                int high = dash < 0 ? low : Integer.parseInt(text.substring(dash + 1).trim());
                if (low < 1 || high > 65535 || low > high) {
                    throw new NumberFormatException();
                }
                ranges.add(new int[]{low, high});
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("The rule for \"" + to + "\" has an invalid port \"" + text + "\" (1 to 65535, or a range such as 8000-8099)");
            }
        }
        return ranges;
    }
}
