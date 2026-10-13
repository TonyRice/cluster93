package cluster93.core.runtime.api;

import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Name lookups and DNS queries for {@code dns} (D-122), through the resolver the egress gate uses, showing only what the
 * tenant could reach. Errors carry Node's codes (ENOTFOUND, ENODATA, ETIMEOUT, ...); the isolate writes Node's messages.
 */
public interface DnsApi {

    /** No DNS: for hosts without network access. */
    DnsApi UNAVAILABLE = new DnsApi() {
    };

    /** {@code {hostname, family}} (family 0, 4 or 6): {@code [{address, family}]} in the system's order, as getaddrinfo. */
    default Future<JsonArray> lookup(JsonObject request) {
        return unavailable();
    }

    /**
     * {@code {name, type, servers?, timeout?, tries?}}: the records of {@code type} ("A", "MX", ..., "ANY"), each with its
     * {@code type} and {@code ttl}. {@code servers} are the script's own ({@code setServers}), which the gate checks.
     */
    default Future<JsonArray> resolve(JsonObject request) {
        return unavailable();
    }

    /** {@code {address, servers?, timeout?, tries?}}: the address's names (PTR), as {@code dns.reverse}. */
    default Future<JsonArray> reverse(JsonObject request) {
        return unavailable();
    }

    /** {@code {address, port}}: {@code {hostname, service}}, as getnameinfo. */
    default Future<JsonObject> lookupService(JsonObject request) {
        return unavailable();
    }

    /** The servers queries go to unless the script sets its own, as Node's {@code getServers} writes them. */
    default JsonArray servers() {
        return new JsonArray();
    }

    private static <T> Future<T> unavailable() {
        return Future.failedFuture(new HostException("Error", "unavailable", "DNS isn't available on this host"));
    }
}
