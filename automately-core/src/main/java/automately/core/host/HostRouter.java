package automately.core.host;

import io.vertx.core.Future;

import java.util.Locale;

/**
 * Maps a request's {@code Host} to the service that should answer it: an automatic host
 * {@code <service>--<tenant>.<ingress domain>}, or a verified custom domain.
 */
public final class HostRouter {

    public record Route(String tenant, String service) {
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

    public boolean isUnderIngressDomain(String host) {
        return normalise(host).endsWith("." + ingressDomain) || normalise(host).equals(ingressDomain);
    }

    /** Completes with the route, or null if nothing serves this host. */
    public Future<Route> route(String hostHeader) {
        String host = normalise(hostHeader);
        String suffix = "." + ingressDomain;
        if (host.endsWith(suffix)) {
            String label = host.substring(0, host.length() - suffix.length());
            int split = label.indexOf("--");
            if (label.contains(".") || split <= 0 || split != label.lastIndexOf("--") || split + 2 >= label.length()) {
                return Future.succeededFuture(null);
            }
            return Future.succeededFuture(new Route(label.substring(split + 2), label.substring(0, split)));
        }
        return domains.get(host).map(domain -> domain != null && domain.verified() ? new Route(domain.tenant(), domain.service()) : null);
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
