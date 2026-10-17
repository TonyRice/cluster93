package cluster93.core.host;

import cluster93.core.host.dns.DnsResolver;
import cluster93.core.host.egress.Decision;
import cluster93.core.host.egress.Egress;
import cluster93.core.runtime.HostException;
import cluster93.core.runtime.api.DnsApi;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

/**
 * {@code dns} for one execution (D-122), as its tenant: answers come from the gate's resolver and show only what the
 * tenant could reach (§8.2). Names in internal zones answer ENOTFOUND; addresses it couldn't connect to are left out,
 * as are records pointing into internal zones; a reverse lookup needs an address it could connect to. Servers the
 * script set go through the gate as {@code dns} connections. At most 16 queries run at once; the rest wait their turn.
 */
public final class NodeDnsApi implements DnsApi {

    static final int IN_FLIGHT = 16;

    private final Egress egress;
    private final DnsResolver resolver;
    private final String tenant;
    private final Queue<Runnable> waiting = new ArrayDeque<>();
    private int running;

    public NodeDnsApi(Egress egress, String tenant) {
        this.egress = egress;
        this.resolver = egress.resolver();
        this.tenant = tenant;
    }

    @Override
    public Future<JsonArray> lookup(JsonObject request) {
        String hostname = request.getString("hostname", "");
        int family = request.getInteger("family", 0);
        // localhost and *.localhost always mean loopback (RFC 6761): answering reveals nothing, connecting there is
        // still refused, and servers' libraries look it up to listen (Fastify) (D-123).
        boolean loopbackName = isLocalhost(hostname);
        return limited(() -> egress.dnsView(tenant).compose(view -> {
            if (!loopbackName && view.hidden(hostname)) {
                return Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found"));
            }
            return resolver.lookup(hostname, family).compose(addresses -> {
                JsonArray out = new JsonArray();
                for (InetAddress address : addresses) {
                    if (loopbackName ? address.isLoopbackAddress() : view.visible(hostname, address)) {
                        out.add(new JsonObject().put("address", cluster93.core.host.dns.DnsRecords.text(address))
                                .put("family", address instanceof Inet4Address ? 4 : 6));
                    }
                }
                return out.isEmpty() ? Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found")) : Future.succeededFuture(out);
            });
        }));
    }

    @Override
    public Future<JsonArray> resolve(JsonObject request) {
        String name = request.getString("name", "");
        String typeName = request.getString("type", "A");
        Integer type = cluster93.core.host.dns.DnsRecords.TYPES.get(typeName);
        if (type == null) {
            return Future.failedFuture(new HostException("TypeError", "ERR_INVALID_ARG_VALUE", "The argument 'rrtype' is invalid. Received '" + typeName + "'"));
        }
        return limited(() -> egress.dnsView(tenant).compose(view -> {
            if (view.hidden(name)) {
                return Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found"));
            }
            InetAddress reversed = reverseOf(name);
            if (reversed != null && !view.visible(null, reversed)) {
                return Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found"));
            }
            return servers(request).compose(servers -> resolver.query(name, type, servers, attemptMs(request), tries(request)))
                    .compose(records -> {
                        JsonArray out = new JsonArray();
                        for (JsonObject record : records) {
                            if (shows(view, name, record)) {
                                out.add(record);
                            }
                        }
                        return out.isEmpty() ? Future.failedFuture(new HostException("Error", "ENODATA", "no data")) : Future.succeededFuture(out);
                    });
        }));
    }

    @Override
    public Future<JsonArray> reverse(JsonObject request) {
        InetAddress address;
        try {
            address = InetAddress.getByName(request.getString("address", ""));
        } catch (UnknownHostException e) {
            return Future.failedFuture(new HostException("Error", "EINVAL", "not an address"));
        }
        String name = reverseName(address);
        return limited(() -> egress.dnsView(tenant).compose(view -> {
            if (!view.visible(null, address)) {
                return Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found"));
            }
            return servers(request).compose(servers -> resolver.query(name, 12, servers, attemptMs(request), tries(request)))
                    .compose(records -> {
                        JsonArray out = new JsonArray();
                        for (JsonObject record : records) {
                            if (!view.hidden(record.getString("value"))) {
                                out.add(record.getString("value"));
                            }
                        }
                        return out.isEmpty() ? Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found")) : Future.succeededFuture(out);
                    });
        }));
    }

    @Override
    public Future<JsonObject> lookupService(JsonObject request) {
        InetAddress address;
        try {
            address = InetAddress.getByName(request.getString("address", ""));
        } catch (UnknownHostException e) {
            return Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found"));
        }
        int port = request.getInteger("port", 0);
        return limited(() -> egress.dnsView(tenant).compose(view -> {
            if (!view.visible(null, address)) {
                return Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found"));
            }
            return resolver.nameOf(address).map(name -> new JsonObject()
                    .put("hostname", view.hidden(name) ? cluster93.core.host.dns.DnsRecords.text(address) : name)
                    .put("service", cluster93.core.host.dns.Services.name(port)));
        }));
    }

    @Override
    public JsonArray servers() {
        JsonArray out = new JsonArray();
        resolver.defaultServers().forEach(server -> out.add(DnsResolver.describe(server)));
        return out;
    }

    static boolean isLocalhost(String hostname) {
        String lower = hostname.toLowerCase(java.util.Locale.ROOT);
        lower = lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
        return lower.equals("localhost") || lower.endsWith(".localhost");
    }

    /** Whether a record may be shown: addresses the tenant could reach, and no name pointing into an internal zone. */
    private static boolean shows(Decision.DnsView view, String name, JsonObject record) {
        String type = record.getString("type");
        try {
            return switch (type) {
                case "A", "AAAA" -> view.visible(name, InetAddress.getByName(record.getString("address")));
                case "CNAME", "NS", "PTR" -> !view.hidden(record.getString("value"));
                case "MX" -> !view.hidden(record.getString("exchange"));
                case "SRV" -> !view.hidden(record.getString("name"));
                case "SOA" -> !view.hidden(record.getString("nsname"));
                case "NAPTR" -> record.getString("replacement", "").isEmpty() || !view.hidden(record.getString("replacement"));
                default -> true;
            };
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The default servers, or the script's own, each checked by the gate; refused ones are left out. */
    private Future<List<InetSocketAddress>> servers(JsonObject request) {
        JsonArray chosen = request.getJsonArray("servers");
        if (chosen == null) {
            return Future.succeededFuture(resolver.defaultServers());
        }
        List<Future<InetSocketAddress>> checks = new ArrayList<>();
        for (Object text : chosen) {
            InetSocketAddress server = DnsResolver.parseServer(String.valueOf(text));
            if (server != null) {
                checks.add(egress.dnsServer(tenant, server));
            }
        }
        return Future.join(checks).transform(done -> {
            List<InetSocketAddress> allowed = new ArrayList<>();
            Throwable refused = null;
            for (Future<InetSocketAddress> check : checks) {
                if (check.succeeded()) {
                    allowed.add(check.result());
                } else if (refused == null) {
                    refused = check.cause();
                }
            }
            if (allowed.isEmpty()) {
                return Future.failedFuture(new HostException("Error", "ECONNREFUSED",
                        refused == null ? "no DNS servers" : String.valueOf(refused.getMessage())));
            }
            return Future.succeededFuture(allowed);
        });
    }

    private static long attemptMs(JsonObject request) {
        long timeout = request.getLong("timeout", -1L);
        return timeout > 0 ? Math.min(timeout, DnsResolver.QUERY_LIMIT_MS) : DnsResolver.DEFAULT_ATTEMPT_MS;
    }

    private static int tries(JsonObject request) {
        int tries = request.getInteger("tries", DnsResolver.DEFAULT_TRIES);
        return Math.max(1, Math.min(tries, 16));
    }

    /** The address a PTR query's name stands for (4.3.2.1.in-addr.arpa, nibbles under ip6.arpa), or null. */
    static InetAddress reverseOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        lower = lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
        try {
            if (lower.endsWith(".in-addr.arpa")) {
                String[] parts = lower.substring(0, lower.length() - ".in-addr.arpa".length()).split("\\.");
                if (parts.length != 4) {
                    return null;
                }
                return InetAddress.getByName(parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0]);
            }
            if (lower.endsWith(".ip6.arpa")) {
                String[] nibbles = lower.substring(0, lower.length() - ".ip6.arpa".length()).split("\\.");
                if (nibbles.length != 32) {
                    return null;
                }
                StringBuilder hex = new StringBuilder();
                for (int i = 31; i >= 0; i--) {
                    hex.append(nibbles[i]);
                    if (i % 4 == 0 && i > 0) {
                        hex.append(':');
                    }
                }
                return InetAddress.getByName(hex.toString());
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** The PTR query's name for an address. */
    static String reverseName(InetAddress address) {
        byte[] bytes = address.getAddress();
        StringBuilder out = new StringBuilder();
        if (bytes.length == 4) {
            for (int i = 3; i >= 0; i--) {
                out.append(bytes[i] & 0xff).append('.');
            }
            return out.append("in-addr.arpa").toString();
        }
        for (int i = bytes.length - 1; i >= 0; i--) {
            out.append(Character.forDigit(bytes[i] & 0xf, 16)).append('.').append(Character.forDigit((bytes[i] >> 4) & 0xf, 16)).append('.');
        }
        return out.append("ip6.arpa").toString();
    }

    /** Runs {@code work} now if fewer than 16 of this script's queries are running, else when one finishes. */
    private <T> Future<T> limited(Supplier<Future<T>> work) {
        Promise<T> done = Promise.promise();
        Runnable start = () -> {
            Future<T> started;
            try {
                started = work.get();
            } catch (RuntimeException e) {
                started = Future.failedFuture(e);
            }
            started.onComplete(result -> {
                finished();
                done.handle(result);
            });
        };
        boolean now;
        synchronized (this) {
            now = running < IN_FLIGHT;
            if (now) {
                running++;
            } else {
                waiting.add(start);
            }
        }
        if (now) {
            start.run();
        }
        return done.future();
    }

    private void finished() {
        Runnable next;
        synchronized (this) {
            next = waiting.poll();
            if (next == null) {
                running--;
            }
        }
        if (next != null) {
            next.run();
        }
    }
}
