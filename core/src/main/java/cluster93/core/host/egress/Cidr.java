package cluster93.core.host.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/** An address range: a network address and a prefix length (D-108, D-111). */
public record Cidr(InetAddress network, int prefix) {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$");

    /** Whether {@code text} (before any {@code /}) is an address literal, so parsing it never looks a name up. */
    static boolean isLiteral(String text) {
        int slash = text.indexOf('/');
        String ip = slash >= 0 ? text.substring(0, slash) : text;
        return IPV4.matcher(ip).matches() || IPV6.matcher(ip).matches();
    }

    /**
     * Parses {@code 10.0.5.20/32}, {@code fd00::/8} or a bare address (a single host). Throws
     * {@link IllegalArgumentException} with a message fit for the API.
     *
     * @param minPrefix the shortest prefix accepted, so a rule can't be wider than intended by accident
     */
    public static Cidr parse(String text, int minPrefix) {
        int slash = text.indexOf('/');
        String ip = slash >= 0 ? text.substring(0, slash) : text;
        if (!isLiteral(text)) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a CIDR");
        }
        if (IPV4.matcher(ip).matches() && Arrays.stream(ip.split("\\.")).anyMatch(octet -> Integer.parseInt(octet) > 255)) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a valid address");
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);   // a literal, so nothing is looked up
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("\"" + text + "\" isn't a CIDR");
        }
        int bits = address.getAddress().length * 8;
        int prefix;
        try {
            prefix = slash >= 0 ? Integer.parseInt(text.substring(slash + 1)) : bits;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" has no valid prefix length");
        }
        if (prefix < minPrefix || prefix > bits) {
            throw new IllegalArgumentException("\"" + text + "\": the prefix length must be from " + minPrefix + " to " + bits);
        }
        return new Cidr(network(address, prefix), prefix);
    }

    /** A built-in range, from a literal that's known to be valid. */
    static Cidr of(String text) {
        return parse(text, 0);
    }

    public boolean contains(InetAddress address) {
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

    @Override
    public String toString() {
        return network.getHostAddress() + "/" + prefix;
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
}
