package cluster93.core.host.dns;

import cluster93.core.runtime.HostException;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStream;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.NoopAuthoritativeDnsServerCache;
import io.netty.resolver.dns.NoopDnsCache;
import io.netty.resolver.dns.NoopDnsCnameCache;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one resolver for scripts (D-122): the egress gate's addresses and {@code dns}'s answers come from here, so a
 * script can't see the network differently from how it can reach it.
 *
 * <ul>
 * <li><b>Lookups</b> ({@code dns.lookup} and the gate) ask the operating system, as Node's getaddrinfo does: hosts files,
 *     search domains, {@code *.localhost}. {@code dns.lookup} keeps the system's order. With
 *     {@code C93_EGRESS_DNS} set, they ask those servers instead, so a split-horizon system resolver can't
 *     answer scripts.</li>
 * <li><b>Queries</b> ({@code resolve*}, {@code reverse}) go to DNS servers as c-ares's do: no search domains, no hosts
 *     file, each server in turn for each try, TCP when an answer is truncated. The servers are {@code
 *     C93_EGRESS_DNS}, else the system's, else ones the script set (which the gate checks first).</li>
 * </ul>
 */
public final class DnsResolver {

    /** An attempt's wait for an answer when the script set none; c-ares's default. */
    public static final long DEFAULT_ATTEMPT_MS = 2_000;
    public static final int DEFAULT_TRIES = 4;
    /** However many tries and servers, a query is answered or refused within this. */
    public static final long QUERY_LIMIT_MS = 10_000;

    private final Vertx vertx;
    private final List<InetSocketAddress> configured;
    private volatile List<InetSocketAddress> system;
    private volatile EventLoopGroup group;
    private volatile DnsNameResolver netty;

    /**
     * @param configured {@code C93_EGRESS_DNS}'s servers, or empty for the system's
     */
    public DnsResolver(Vertx vertx, List<InetSocketAddress> configured) {
        this.vertx = vertx;
        this.configured = List.copyOf(configured);
    }

    /** "1.1.1.1, 8.8.8.8:53, [2606:4700::1111]:53" as servers; blank is none. */
    public static List<InetSocketAddress> parseServers(String text) {
        List<InetSocketAddress> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(",")) {
            InetSocketAddress server = parseServer(part.trim());
            if (server == null) {
                throw new IllegalArgumentException("\"" + part.trim() + "\" isn't a DNS server: use an IP address, optionally with :port");
            }
            out.add(server);
        }
        return out;
    }

    /** A server as Node's {@code setServers} takes it ("1.2.3.4", "1.2.3.4:53", "::1", "[::1]:53"), or null. */
    public static InetSocketAddress parseServer(String text) {
        String host = text;
        int port = 53;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return null;
                }
                port = port(rest.substring(1));
            }
        } else if (text.indexOf(':') == text.lastIndexOf(':') && text.contains(":")) {
            host = text.substring(0, text.indexOf(':'));
            port = port(text.substring(text.indexOf(':') + 1));
        }
        if (port < 0 || !isIp(host)) {
            return null;
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static int port(String text) {
        try {
            int port = Integer.parseInt(text);
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Whether {@code text} is an IPv4 or IPv6 literal (never a name: nothing is resolved). */
    public static boolean isIp(String text) {
        return text.matches("\\d{1,3}(\\.\\d{1,3}){3}") && java.util.Arrays.stream(text.split("\\.")).allMatch(p -> Integer.parseInt(p) <= 255)
                || text.contains(":") && text.matches("[0-9a-fA-F:.]+(%[\\w.-]+)?");
    }

    /** The server as Node's {@code getServers} writes it: the address alone on port 53, else with its port. */
    public static String describe(InetSocketAddress server) {
        String host = DnsRecords.text(server.getAddress());
        boolean v6 = host.contains(":");
        if (server.getPort() == 53) {
            return host;
        }
        return v6 ? "[" + host + "]:" + server.getPort() : host + ":" + server.getPort();
    }

    /** The servers queries go to unless a script sets its own: the operator's, else the system's. */
    public List<InetSocketAddress> defaultServers() {
        if (!configured.isEmpty()) {
            return configured;
        }
        List<InetSocketAddress> known = system;
        if (known == null) {
            List<InetSocketAddress> found = new ArrayList<>();
            DnsServerAddressStream stream = DnsServerAddressStreamProviders.platformDefault().nameServerAddressStream("");
            for (int i = 0; i < stream.size(); i++) {
                InetSocketAddress server = stream.next();
                if (!found.contains(server)) {
                    found.add(server);
                }
            }
            known = system = List.copyOf(found);
        }
        return known;
    }

    /** Whether an operator set the servers ({@code C93_EGRESS_DNS}), so lookups don't ask the system. */
    public boolean configured() {
        return !configured.isEmpty();
    }

    /** The gate's addresses for {@code host}, in the JVM's order (IPv4 first). */
    public Future<List<InetAddress>> addresses(String host) {
        if (configured() && !isIp(host)) {
            return byDns(host, 0);
        }
        return vertx.executeBlocking(() -> {
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw lookupError(e);
            }
        }, false);
    }

    /**
     * {@code dns.lookup}'s addresses for {@code host}, of {@code family} (0 for both), in the system's order. Fails with
     * ENOTFOUND or EAI_AGAIN, as getaddrinfo does.
     */
    public Future<List<InetAddress>> lookup(String host, int family) {
        if (configured()) {
            return byDns(host, family);
        }
        return vertx.executeBlocking(() -> {
            InetAddressResolver builtIn = SystemResolverProvider.builtIn();
            List<InetAddress> found;
            try {
                if (builtIn == null) {
                    found = List.of(InetAddress.getAllByName(host));
                } else {
                    int characteristics = family == 4 ? InetAddressResolver.LookupPolicy.IPV4
                            : family == 6 ? InetAddressResolver.LookupPolicy.IPV6
                            : InetAddressResolver.LookupPolicy.IPV4 | InetAddressResolver.LookupPolicy.IPV6;
                    found = builtIn.lookupByName(host, InetAddressResolver.LookupPolicy.of(characteristics)).toList();
                }
            } catch (UnknownHostException e) {
                throw lookupError(e);
            }
            List<InetAddress> out = new ArrayList<>();
            for (InetAddress address : found) {
                if (family == 0 || (family == 4) == (address instanceof java.net.Inet4Address)) {
                    out.add(address);
                }
            }
            if (out.isEmpty()) {
                throw new HostException("Error", "ENOTFOUND", "no addresses");
            }
            return out;
        }, false);
    }

    /** The system's name for {@code address}, as getnameinfo gives it: the address itself if it has none. */
    public Future<String> nameOf(InetAddress address) {
        return vertx.executeBlocking(() -> {
            InetAddressResolver builtIn = SystemResolverProvider.builtIn();
            try {
                return builtIn != null ? builtIn.lookupByAddress(address.getAddress()) : address.getCanonicalHostName();
            } catch (UnknownHostException e) {
                return DnsRecords.text(address);
            }
        }, false);
    }

    private static HostException lookupError(UnknownHostException e) {
        String message = String.valueOf(e.getMessage());
        return message.contains("Temporary failure")
                ? new HostException("Error", "EAI_AGAIN", "temporary failure")
                : new HostException("Error", "ENOTFOUND", "not found");
    }

    /** A and AAAA by DNS, for lookups when an operator set the servers. */
    private Future<List<InetAddress>> byDns(String host, int family) {
        Future<List<JsonObject>> v4 = family == 6 ? Future.succeededFuture(List.of()) : answersOrNone(host, 1);
        Future<List<JsonObject>> v6 = family == 4 ? Future.succeededFuture(List.of()) : answersOrNone(host, 28);
        return Future.all(v4, v6).compose(both -> {
            List<InetAddress> out = new ArrayList<>();
            for (List<JsonObject> records : List.of(v4.result(), v6.result())) {
                for (JsonObject record : records) {
                    try {
                        out.add(InetAddress.getByName(record.getString("address")));
                    } catch (UnknownHostException e) {
                        // not an address after all
                    }
                }
            }
            return out.isEmpty() ? Future.failedFuture(new HostException("Error", "ENOTFOUND", "not found")) : Future.succeededFuture(out);
        });
    }

    private Future<List<JsonObject>> answersOrNone(String host, int type) {
        return query(host, type, defaultServers(), DEFAULT_ATTEMPT_MS, DEFAULT_TRIES)
                .recover(err -> Future.succeededFuture(List.of()));
    }

    /**
     * Records of {@code type} (255 for every type) for {@code name}, from {@code servers}: each in turn, {@code tries}
     * times over, waiting {@code attemptMs} for each, as c-ares does; a truncated answer is asked again over TCP. Fails
     * with Node's code: ENOTFOUND (no such name), ENODATA (no such records), ESERVFAIL, EREFUSED, EFORMERR, ENOTIMP,
     * ETIMEOUT or ECONNREFUSED.
     */
    public Future<List<JsonObject>> query(String name, int type, List<InetSocketAddress> servers, long attemptMs, int tries) {
        if (servers.isEmpty()) {
            return Future.failedFuture(new HostException("Error", "ECONNREFUSED", "no DNS servers"));
        }
        DnsNameResolver resolver = netty();
        Promise<List<JsonObject>> done = Promise.promise();
        long limit = vertx.setTimer(QUERY_LIMIT_MS, id -> done.tryFail(new HostException("Error", "ETIMEOUT", "timed out")));
        DefaultDnsQuestion question = new DefaultDnsQuestion(name.endsWith(".") ? name : name + ".", DnsRecordType.valueOf(type));
        attempt(resolver, question, type, servers, 0, Math.max(1, tries) * servers.size(), Math.max(1, attemptMs), "ETIMEOUT", done);
        return done.future().onComplete(r -> vertx.cancelTimer(limit));
    }

    private void attempt(DnsNameResolver resolver, DefaultDnsQuestion question, int type, List<InetSocketAddress> servers, int n, int attempts,
                         long attemptMs, String lastError, Promise<List<JsonObject>> done) {
        if (done.future().isComplete()) {
            return;
        }
        if (n >= attempts) {
            done.tryFail(new HostException("Error", lastError, lastError.toLowerCase(Locale.ROOT)));
            return;
        }
        InetSocketAddress server = servers.get(n % servers.size());
        Promise<String> outcome = Promise.promise();   // null: answered; else the error, to try the next server
        long timer = vertx.setTimer(attemptMs, id -> outcome.tryComplete("ETIMEOUT"));
        resolver.query(server, question).addListener(f -> {
            if (!f.isSuccess()) {
                Throwable cause = f.cause();
                outcome.tryComplete(cause instanceof io.netty.resolver.dns.DnsNameResolverTimeoutException ? "ETIMEOUT" : "ECONNREFUSED");
                return;
            }
            @SuppressWarnings("unchecked")
            AddressedEnvelope<DnsResponse, InetSocketAddress> envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) f.getNow();
            try {
                DnsResponse response = envelope.content();
                DnsResponseCode code = response.code();
                if (code.equals(DnsResponseCode.NOERROR)) {
                    List<JsonObject> records = new ArrayList<>();
                    for (int i = 0; i < response.count(DnsSection.ANSWER); i++) {
                        DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
                        if (type == 255 || record.type().intValue() == type) {
                            JsonObject decoded = DnsRecords.decode(record);
                            if (decoded != null) {
                                records.add(decoded);
                            }
                        }
                    }
                    if (records.isEmpty()) {
                        done.tryFail(new HostException("Error", "ENODATA", "no data"));
                    } else {
                        done.tryComplete(records);
                    }
                    outcome.tryComplete(null);
                } else if (code.equals(DnsResponseCode.NXDOMAIN)) {
                    done.tryFail(new HostException("Error", "ENOTFOUND", "not found"));
                    outcome.tryComplete(null);
                } else if (code.equals(DnsResponseCode.FORMERR)) {
                    done.tryFail(new HostException("Error", "EFORMERR", "format error"));
                    outcome.tryComplete(null);
                } else if (code.equals(DnsResponseCode.NOTIMP)) {
                    done.tryFail(new HostException("Error", "ENOTIMP", "not implemented"));
                    outcome.tryComplete(null);
                } else {
                    outcome.tryComplete(code.equals(DnsResponseCode.REFUSED) ? "EREFUSED" : "ESERVFAIL");
                }
            } finally {
                envelope.release();
            }
        });
        outcome.future().onSuccess(error -> {
            vertx.cancelTimer(timer);
            if (error != null) {
                attempt(resolver, question, type, servers, n + 1, attempts, attemptMs, error, done);
            }
        });
    }

    private DnsNameResolver netty() {
        DnsNameResolver made = netty;
        if (made == null) {
            synchronized (this) {
                if (netty == null) {
                    group = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("cluster93-dns", true), NioIoHandler.newFactory());
                    InetSocketAddress any = defaultServers().isEmpty() ? new InetSocketAddress(InetAddress.getLoopbackAddress(), 53)
                            : defaultServers().get(0);
                    netty = new DnsNameResolverBuilder(group.next())
                            .datagramChannelType(NioDatagramChannel.class)
                            .socketChannelType(NioSocketChannel.class)
                            .queryTimeoutMillis(QUERY_LIMIT_MS)
                            .resolveCache(NoopDnsCache.INSTANCE)
                            .cnameCache(NoopDnsCnameCache.INSTANCE)
                            .authoritativeDnsServerCache(NoopAuthoritativeDnsServerCache.INSTANCE)
                            .nameServerProvider(new SingletonDnsServerAddressStreamProvider(any))
                            .recursionDesired(true)
                            .optResourceEnabled(true)
                            .maxPayloadSize(4096)
                            .build();
                    if (vertx instanceof io.vertx.core.internal.VertxInternal internal) {
                        internal.addCloseHook(completion -> {
                            close();
                            completion.succeed();
                        });
                    }
                }
                made = netty;
            }
        }
        return made;
    }

    void close() {
        DnsNameResolver resolver = netty;
        if (resolver != null) {
            resolver.close();
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
