package automately.core.host.egress;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Named address ranges that egress rules refer to as {@code @name} (D-111): the built-in ones, which together are
 * everything that isn't public, plus an operator's own from the cluster policy. {@code @public} is everything else.
 */
public final class AddressSets {

    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");

    /** The built-in sets, in the order they're explained. Together they're exactly what {@code EgressPolicy.isPublic} refuses. */
    public static final Map<String, List<Cidr>> BUILT_IN = builtIn();

    /** What each built-in set is, for the tenant's view. */
    public static final Map<String, String> DESCRIPTIONS = Map.of(
            "public", "Every address in none of the sets below: the public internet.",
            "private", "Private networks: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, and IPv6 unique-local and site-local addresses.",
            "loopback", "This machine: 127.0.0.0/8 and ::1.",
            "link-local", "Link-local addresses, which include cloud metadata services at 169.254.169.254.",
            "cgnat", "Carrier-grade NAT: 100.64.0.0/10.",
            "reserved", "Reserved, documentation, benchmarking, multicast and broadcast ranges, and IPv6 transition prefixes.");

    private static final AddressSets BUILT_IN_ONLY = new AddressSets(Map.of());

    private final Map<String, List<Cidr>> operator;

    /** @param operator the cluster's own sets, by name without the {@code @} */
    public AddressSets(Map<String, List<Cidr>> operator) {
        this.operator = Map.copyOf(operator);
    }

    public static AddressSets builtInOnly() {
        return BUILT_IN_ONLY;
    }

    public static boolean isBuiltIn(String name) {
        return name.equals("public") || BUILT_IN.containsKey(name);
    }

    public boolean knows(String name) {
        return isBuiltIn(name) || operator.containsKey(name);
    }

    /**
     * Whether {@code address} is in the set; null if there's no such set, which the caller treats as "matches" for a
     * deny and "doesn't" for an allow, so a missing set never opens anything.
     */
    public Boolean contains(String name, InetAddress address) {
        if (name.equals("public")) {
            return isPublic(address);
        }
        List<Cidr> ranges = BUILT_IN.containsKey(name) ? BUILT_IN.get(name) : operator.get(name);
        if (ranges == null) {
            return null;
        }
        return ranges.stream().anyMatch(range -> range.contains(address));
    }

    /** In none of the built-in sets. Callers pass the address a connection really reaches (its embedded IPv4, if any). */
    public static boolean isPublic(InetAddress address) {
        return BUILT_IN.values().stream().flatMap(List::stream).noneMatch(range -> range.contains(address));
    }

    private static Map<String, List<Cidr>> builtIn() {
        Map<String, List<Cidr>> sets = new LinkedHashMap<>();
        sets.put("private", ranges("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fec0::/10"));
        sets.put("loopback", ranges("127.0.0.0/8", "::1/128"));
        sets.put("link-local", ranges("169.254.0.0/16", "fe80::/10"));
        sets.put("cgnat", ranges("100.64.0.0/10"));
        sets.put("reserved", ranges(
                "0.0.0.0/8", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "198.18.0.0/15", "198.51.100.0/24",
                "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
                "::/8", "64:ff9b::/32", "100::/64", "2001::/32", "2001:db8::/32", "2001:10::/28", "2002::/16", "ff00::/8"));
        return java.util.Collections.unmodifiableMap(sets);
    }

    private static List<Cidr> ranges(String... cidrs) {
        return Stream.of(cidrs).map(Cidr::of).toList();
    }
}
