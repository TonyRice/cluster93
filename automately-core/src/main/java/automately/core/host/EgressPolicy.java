package automately.core.host;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Which addresses a script's {@code fetch} may connect to. By default only public unicast addresses:
 * no loopback, private ranges, link-local (which includes cloud metadata at 169.254.169.254), CGNAT,
 * multicast, unique-local IPv6, or the unspecified address. Checked against the resolved address that
 * the connection then actually uses, so DNS rebinding can't slip an internal address in afterwards.
 */
public record EgressPolicy(boolean allowPrivate) {

    public static final EgressPolicy PUBLIC_ONLY = new EgressPolicy(false);

    public boolean allows(InetAddress address) {
        return allowPrivate || isPublic(address);
    }

    /**
     * Whether an address is public unicast. An IPv6 form that carries an IPv4 address (IPv4-mapped, IPv4-compatible,
     * NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}) is judged by that IPv4 address, since that's what it reaches
     * (D-108).
     */
    public static boolean isPublic(InetAddress address) {
        InetAddress embedded = embeddedIpv4(address);
        if (embedded != null) {
            return isPublic(embedded);
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            int third = b[2] & 0xff;
            return first != 0                                   // 0.0.0.0/8
                    && !(first == 100 && second >= 64 && second <= 127)  // 100.64.0.0/10 (CGNAT)
                    && !(first == 192 && second == 0 && third == 0)      // 192.0.0.0/24 (IETF)
                    && !(first == 192 && second == 0 && third == 2)      // 192.0.2.0/24 (documentation)
                    && !(first == 192 && second == 88 && third == 99)    // 192.88.99.0/24 (6to4 relays)
                    && !(first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15 (benchmarking)
                    && !(first == 198 && second == 51 && third == 100)   // 198.51.100.0/24 (documentation)
                    && !(first == 203 && second == 0 && third == 113)    // 203.0.113.0/24 (documentation)
                    && first < 240;                                      // 240.0.0.0/4 (reserved, broadcast)
        }
        if (address instanceof Inet6Address) {
            int w0 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
            int w1 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            return (b[0] & 0xfe) != 0xfc                                 // fc00::/7 (unique local)
                    && (b[0] & 0xff) != 0                                // ::/8 (unspecified, compatible, discard-only forms)
                    && !(w0 == 0x0064 && w1 == 0xff9b)                   // 64:ff9b::/32 (NAT64, including local-use 64:ff9b:1::/48)
                    && !(w0 == 0x0100 && w1 == 0)                        // 100::/64 (discard)
                    && !(w0 == 0x2001 && w1 == 0)                        // 2001::/32 (Teredo)
                    && !(w0 == 0x2001 && w1 == 0x0db8)                   // 2001:db8::/32 (documentation)
                    && !(w0 == 0x2001 && (w1 & 0xfff0) == 0x0010)        // 2001:10::/28 (ORCHID)
                    && !(w0 == 0x2002);                                  // 2002::/16 (6to4) whose IPv4 wasn't usable
        }
        return true;
    }

    /** The IPv4 address an IPv6 form carries, or null if it isn't one of those forms. */
    public static InetAddress embeddedIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return null;
        }
        byte[] b = address.getAddress();
        byte[] v4;
        if (isIpv4Mapped(b) || isIpv4Compatible(b) || isNat64(b)) {
            v4 = new byte[]{b[12], b[13], b[14], b[15]};
        } else if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
            v4 = new byte[]{b[2], b[3], b[4], b[5]};             // 2002:AABB:CCDD::/48
        } else {
            return null;
        }
        try {
            return InetAddress.getByAddress(v4);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    /** {@code ::a.b.c.d}, but not {@code ::} or {@code ::1}. */
    private static boolean isIpv4Compatible(byte[] b) {
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[12] | b[13] | b[14]) != 0 || (b[15] & 0xff) > 1;
    }

    /** {@code 64:ff9b::/96}, the well-known NAT64 prefix. */
    private static boolean isNat64(byte[] b) {
        if (!(b[0] == 0 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b)) {
            return false;
        }
        for (int i = 4; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
