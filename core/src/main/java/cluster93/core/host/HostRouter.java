package cluster93.core.host;

import io.vertx.core.Future;

import java.util.Locale;

/**
 * Maps a request's {@code Host} to the service that should answer it: an automatic host
 * {@code <service>--<tenant>.<ingress domain>}, one for a server's port {@code <service>--<tenant>--<port>.<ingress
 * domain>} (D-112), or a verified custom domain.
 */
public final class HostRouter {

    /** @param port the server port asked for, or null for the service's main server */
    public record Route(String tenant, String service, Integer port) {

        public Route(String tenant, String service) {
            this(tenant, service, null);
        }
    }

    private final String ingressDomain;
    private final DomainStore domains;

    public HostRouter(String ingressDomain, DomainStore domains) {
        this.ingressDomain = ingressDomain.toLowerCase(Locale.ROOT);
        this.domains = domains;
    }

    /** The automatic host for a service. Names can't contain "--", so the split back is unambiguous. */
    public String automaticHost(String tenant, String service) {
        return service + "--" + tenant + "." + ingressDomain;
    }

    /** The automatic host for one of a service's server ports (D-112): one label deep, so the wildcard certificate covers it. */
    public String portHost(String tenant, String service, int port) {
        return service + "--" + tenant + "--" + port + "." + ingressDomain;
    }

    public boolean isUnderIngressDomain(String host) {
        return normalise(host).endsWith("." + ingressDomain) || normalise(host).equals(ingressDomain);
    }

    /** Completes with the route, or null if nothing serves this host. */
    public Future<Route> route(String hostHeader) {
        String host = normalise(hostHeader);
        String suffix = "." + ingressDomain;
        if (host.endsWith(suffix)) {
            String label = host.substring(0, host.length() - suffix.length());
            String[] parts = label.split("--", -1);
            if (label.contains(".") || parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
                return Future.succeededFuture(null);
            }
            Integer port = null;
            if (parts.length == 3) {
                port = port(parts[2]);
                if (port == null) {
                    return Future.succeededFuture(null);
                }
            }
            return Future.succeededFuture(new Route(parts[1], parts[0], port));
        }
        return domains.get(host).map(domain -> domain != null && domain.verified() ? new Route(domain.tenant(), domain.service(), domain.port()) : null);
    }

    /** A port as a host label writes it: 1 to 65535, no leading zeros; null otherwise. */
    static Integer port(String text) {
        if (!text.matches("[1-9][0-9]{0,4}")) {
            return null;
        }
        int port = Integer.parseInt(text);
        return port <= 65535 ? port : null;
    }

    static String normalise(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return host;
        }
        int colon = host.lastIndexOf(':');
        host = colon >= 0 ? host.substring(0, colon) : host;
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
