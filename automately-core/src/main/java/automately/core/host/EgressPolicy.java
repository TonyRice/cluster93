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

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first != 0                                   // 0.0.0.0/8
                    && !(first == 100 && second >= 64 && second <= 127)  // 100.64.0.0/10 (CGNAT)
                    && !(first == 192 && second == 0 && (b[2] & 0xff) == 0)  // 192.0.0.0/24 (IETF)
                    && !(first == 198 && (second == 18 || second == 19))     // 198.18.0.0/15 (benchmarking)
                    && first < 240;                                          // 240.0.0.0/4 (reserved)
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) {
                return false;                                   // fc00::/7 (unique local)
            }
            if (isIpv4Mapped(b)) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
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
