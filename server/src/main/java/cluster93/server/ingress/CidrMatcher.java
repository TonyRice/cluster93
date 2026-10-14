package cluster93.server.ingress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches a client IP address against a list of CIDR blocks, IPv4 and IPv6 alike
 * ({@code C93_CONSOLE_ALLOWED_CIDRS}, docs/design/apps-and-console.md). A bare address is a host route
 * ({@code /32} or {@code /128}); an IPv4-mapped IPv6 address ({@code ::ffff:127.0.0.1}) matches an IPv4 block.
 * An empty matcher imposes no restriction, so the console host stays reachable when no CIDRs are configured.
 */
public final class CidrMatcher {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private final List<Block> blocks;

    private CidrMatcher(List<Block> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    /** Parses a list of CIDR strings; blank entries are skipped, a malformed one throws. */
    public static CidrMatcher parse(List<String> cidrs) {
        List<Block> blocks = new ArrayList<>();
        if (cidrs != null) {
            for (String cidr : cidrs) {
                if (cidr != null && !cidr.isBlank()) {
                    blocks.add(parseBlock(cidr.trim()));
                }
            }
        }
        return new CidrMatcher(blocks);
    }

    /** True when no blocks are configured, which matches every address. */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** True if {@code address} is inside any block; true when no blocks are configured; false for a null/unknown address. */
    public boolean matches(String address) {
        if (blocks.isEmpty()) {
            return true;
        }
        byte[] bytes = addressBytes(address);
        if (bytes == null) {
            return false;
        }
        for (Block block : blocks) {
            if (block.matches(bytes)) {
                return true;
            }
        }
        return false;
    }

    private static Block parseBlock(String text) {
        String address = text;
        int prefix;
        int slash = text.indexOf('/');
        if (slash >= 0) {
            address = text.substring(0, slash);
            try {
                prefix = Integer.parseInt(text.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR \"" + text + "\": the prefix length must be a number");
            }
        } else {
            prefix = -1;
        }
        byte[] bytes = literalBytes(address, text);
        int bits = bytes.length * 8;
        if (prefix < 0) {
            prefix = bits;
        }
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("Invalid CIDR \"" + text + "\": prefix /" + prefix + " is longer than " + bits + " bits");
        }
        return new Block(bytes, prefix);
    }

    /** The bytes of a literal address from configuration, or throw. */
    private static byte[] literalBytes(String address, String original) {
        if (IPV4.matcher(address).matches()) {
            return ipv4Bytes(address, original);
        }
        if (address.indexOf(':') >= 0 && IPV6.matcher(address).matches()) {
            try {
                return InetAddress.getByName(address).getAddress();
            } catch (UnknownHostException e) {
                // Fall through to the error below.
            }
        }
        throw new IllegalArgumentException("Invalid CIDR \"" + original + "\": not an IPv4 or IPv6 address");
    }

    /** A client address's bytes, normalising an IPv4-mapped IPv6 address to IPv4; null when it isn't an address. */
    private static byte[] addressBytes(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String text = address.trim();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        try {
            if (IPV4.matcher(text).matches()) {
                return ipv4Bytes(text, text);
            }
            if (text.indexOf(':') >= 0 && IPV6.matcher(text).matches()) {
                return mappedToIpv4(InetAddress.getByName(text).getAddress());
            }
        } catch (UnknownHostException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    /** {@code ::ffff:a.b.c.d} to its four IPv4 bytes; anything else unchanged. */
    private static byte[] mappedToIpv4(byte[] bytes) {
        if (bytes.length != 16) {
            return bytes;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) {
            return bytes;
        }
        return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    private static byte[] ipv4Bytes(String address, String original) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid CIDR \"" + original + "\": not an IPv4 address");
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int octet;
            try {
                octet = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR \"" + original + "\": not an IPv4 address");
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException("Invalid CIDR \"" + original + "\": each octet must be 0-255");
            }
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    private record Block(byte[] network, int prefix) {

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefix % 8;
            if (remainder != 0) {
                int mask = 0xFF << (8 - remainder);
                if ((address[full] & mask) != (network[full] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
