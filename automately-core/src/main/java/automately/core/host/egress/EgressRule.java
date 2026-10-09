package automately.core.host.egress;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One entry of a tenant's egress policy (D-108): a CIDR or an exact host name, and the ports it opens. A name rule
 * matches only when a script asks for that name; the addresses it resolves to are then allowed.
 *
 * @param to      as written: {@code 10.0.5.20/32}, {@code fd00::/8} or {@code db.internal.example}
 * @param network the CIDR's network address, or null for a name rule
 * @param prefix  the CIDR's prefix length
 * @param host    the lower-cased name, or null for a CIDR rule
 * @param ports   inclusive ranges; never empty
 */
public record EgressRule(String to, InetAddress network, int prefix, String host, List<int[]> ports) {

    private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)(\\.[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)*$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$");
    static final int MAX_PORT_RANGES = 32;

    public EgressRule {
        ports = List.copyOf(ports);
    }

    /** Whether this rule opens {@code port} on {@code address}, reached by asking for {@code requestedHost}. */
    public boolean allows(String requestedHost, InetAddress address, int port) {
        if (!opens(port)) {
            return false;
        }
        if (host != null) {
            return requestedHost != null && host.equals(requestedHost.toLowerCase(Locale.ROOT));
        }
        return contains(address);
    }

    boolean opens(int port) {
        for (int[] range : ports) {
            if (port >= range[0] && port <= range[1]) {
                return true;
            }
        }
        return false;
    }

    boolean contains(InetAddress address) {
        byte[] want = network.getAddress();
        byte[] have = address.getAddress();
        if (want.length != have.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (want[i] != have[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xff << (8 - rest)) & 0xff;
        return (want[full] & mask) == (have[full] & mask);
    }

    public JsonObject toJson() {
        JsonArray list = new JsonArray();
        for (int[] range : ports) {
            list.add(range[0] == range[1] ? (Object) range[0] : range[0] + "-" + range[1]);
        }
        return new JsonObject().put("to", to).put("ports", list);
    }

    /** Parses one rule; throws {@link IllegalArgumentException} with a message fit for the API. */
    public static EgressRule fromJson(JsonObject json) {
        String to = json.getValue("to") instanceof String s ? s.trim() : null;
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("Each rule needs \"to\": a CIDR such as 10.0.5.20/32, or a host name");
        }
        List<int[]> ports = ports(json.getValue("ports"), to);
        int slash = to.indexOf('/');
        String ip = slash >= 0 ? to.substring(0, slash) : to;
        // Only a literal is parsed as an address, so parsing never looks a name up.
        boolean literal = IPV4.matcher(ip).matches() || IPV6.matcher(ip).matches();
        if (slash >= 0 && !literal) {
            throw new IllegalArgumentException("\"" + to + "\" isn't a CIDR");
        }
        if (literal) {
            if (IPV4.matcher(ip).matches() && java.util.Arrays.stream(ip.split("\\.")).anyMatch(octet -> Integer.parseInt(octet) > 255)) {
                throw new IllegalArgumentException("\"" + to + "\" isn't a valid address");
            }
            InetAddress address;
            try {
                address = InetAddress.getByName(ip);   // a literal, so nothing is looked up
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("\"" + to + "\" isn't a CIDR");
            }
            int bits = address.getAddress().length * 8;
            int prefix;
            try {
                prefix = slash >= 0 ? Integer.parseInt(to.substring(slash + 1)) : bits;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + to + "\" has no valid prefix length");
            }
            if (prefix < 8 || prefix > bits) {
                throw new IllegalArgumentException("\"" + to + "\": the prefix length must be from 8 to " + bits);
            }
            return new EgressRule(to, network(address, prefix), prefix, null, ports);
        }
        String host = to.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (!HOST.matcher(host).matches() || host.matches("[0-9.]+")) {
            throw new IllegalArgumentException("\"" + to + "\" is neither a CIDR nor a host name");
        }
        return new EgressRule(to, null, 0, host, ports);
    }

    private static InetAddress network(InetAddress address, int prefix) {
        byte[] b = address.getAddress();
        for (int i = 0; i < b.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            b[i] = (byte) (b[i] & ((0xff << (8 - keep)) & 0xff));
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<int[]> ports(Object value, String to) {
        if (!(value instanceof JsonArray array) || array.isEmpty()) {
            throw new IllegalArgumentException("The rule for \"" + to + "\" needs \"ports\": a list such as [5432, \"8000-8099\"]");
        }
        if (array.size() > MAX_PORT_RANGES) {
            throw new IllegalArgumentException("The rule for \"" + to + "\" has more than " + MAX_PORT_RANGES + " ports or ranges");
        }
        List<int[]> ranges = new ArrayList<>();
        for (Object port : array) {
            String text = String.valueOf(port).trim();
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
