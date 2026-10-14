package cluster93.core.host.dns;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

/**
 * Hands the JVM its built-in resolver, unchanged, and keeps it for {@code dns.lookup} (D-122). The built-in resolver
 * answers in the system's order (getaddrinfo's, as Node's {@code lookup} with {@code verbatim}) when asked for both
 * families with no preference; {@link java.net.InetAddress} itself puts IPv4 first. Registered as a service, so the
 * JVM installs it when {@code InetAddress} first loads.
 */
public final class SystemResolverProvider extends InetAddressResolverProvider {

    /** Set once, when the JVM installs this provider; one per JVM, as the JVM has one resolver. */
    private static final java.util.concurrent.atomic.AtomicReference<InetAddressResolver> BUILT_IN = new java.util.concurrent.atomic.AtomicReference<>();

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver resolver = configuration.builtinResolver();
        BUILT_IN.set(resolver);
        return resolver;
    }

    @Override
    public String name() {
        return "cluster93-system-order";
    }

    /** The built-in resolver, or null if this provider wasn't installed (then lookups fall back to InetAddress). */
    static InetAddressResolver builtIn() {
        return BUILT_IN.get();
    }
}
