package automately.core.host.dns;

import automately.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The resolver scripts' DNS and the egress gate share (D-122), against a local DNS server. */
class DnsResolverTest {

    private Vertx vertx;
    private TestDnsServer server;
    private DnsResolver resolver;
    private List<InetSocketAddress> servers;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        server = new TestDnsServer(vertx).start()
                .a("example.test", "93.184.216.34").a("example.test", "93.184.216.35").ttl("example.test", 120)
                .aaaa("example.test", "2606:2800:220:1::1")
                .mx("example.test", 10, "mail.example.test").mx("example.test", 20, "backup.other.test")
                .txt("example.test", "v=spf1 -all", "second").txt("example.test", "héllo")
                .ns("example.test", "ns1.example.test")
                .soa("example.test", "ns1.example.test", "hostmaster.example.test", 2026100901, 7200, 900, 1209600, 300)
                .caa("example.test", 0, "issue", "letsencrypt.org")
                .srv("_mongodb._tcp.cluster.test", 0, 5, 27017, "mongo1.cluster.test")
                .naptr("example.test", 100, 10, "S", "SIP+D2U", "", "_sip._udp.example.test")
                .tlsa("_443._tcp.example.test", 3, 1, 1, new byte[]{1, 2, 3, 4})
                .cname("www.example.test", "example.test")
                .ptr("34.216.184.93.in-addr.arpa", "example.test")
                .txt("big.example.test", "x".repeat(250), "y".repeat(250), "z".repeat(250));
        servers = List.of(new InetSocketAddress("127.0.0.1", server.port()));
        resolver = new DnsResolver(vertx, servers);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private List<JsonObject> query(String name, String type) throws Exception {
        return await(resolver.query(name, DnsRecords.TYPES.get(type), servers, 1000, 2));
    }

    @Test
    void everyRecordTypeAsNodeShapesIt() throws Exception {
        assertThat(query("example.test", "A")).extracting(r -> r.getString("address") + "/" + r.getLong("ttl"))
                .containsExactly("93.184.216.34/300", "93.184.216.35/120");
        assertThat(query("example.test", "AAAA").get(0).getString("address")).isEqualTo("2606:2800:220:1::1");
        // Compressed names inside records: MX and SOA point back into the message.
        assertThat(query("example.test", "MX")).extracting(r -> r.getInteger("priority") + " " + r.getString("exchange"))
                .containsExactly("10 mail.example.test", "20 backup.other.test");
        JsonObject soa = query("example.test", "SOA").get(0);
        assertThat(soa.getString("nsname")).isEqualTo("ns1.example.test");
        assertThat(soa.getString("hostmaster")).isEqualTo("hostmaster.example.test");
        assertThat(soa.getLong("serial")).isEqualTo(2026100901L);
        assertThat(soa.getLong("minttl")).isEqualTo(300L);
        assertThat(query("example.test", "TXT")).extracting(r -> r.getJsonArray("entries"))
                .containsExactly(new JsonArray().add("v=spf1 -all").add("second"), new JsonArray().add("h\u00c3\u00a9llo"));
        assertThat(query("example.test", "NS").get(0).getString("value")).isEqualTo("ns1.example.test");
        assertThat(query("example.test", "CAA").get(0).encode()).isEqualTo("{\"type\":\"CAA\",\"ttl\":300,\"critical\":0,\"issue\":\"letsencrypt.org\"}");
        JsonObject srv = query("_mongodb._tcp.cluster.test", "SRV").get(0);
        assertThat(srv.getString("name")).isEqualTo("mongo1.cluster.test");
        assertThat(srv.getInteger("port")).isEqualTo(27017);
        JsonObject naptr = query("example.test", "NAPTR").get(0);
        assertThat(naptr.getString("service")).isEqualTo("SIP+D2U");
        assertThat(naptr.getString("replacement")).isEqualTo("_sip._udp.example.test");
        assertThat(query("_443._tcp.example.test", "TLSA").get(0).getString("data")).isEqualTo("AQIDBA==");
        assertThat(query("www.example.test", "CNAME").get(0).getString("value")).isEqualTo("example.test");
        assertThat(query("34.216.184.93.in-addr.arpa", "PTR").get(0).getString("value")).isEqualTo("example.test");
        assertThat(query("example.test", "ANY")).extracting(r -> r.getString("type")).contains("A", "AAAA", "MX", "TXT", "NS", "SOA", "CAA", "NAPTR");
    }

    @Test
    void errorsHaveNodesCodes() {
        assertThatThrownBy(() -> query("nothing.example.test", "A")).hasMessageContaining("not found").cause()
                .isInstanceOf(HostException.class).extracting("code").isEqualTo("ENOTFOUND");
        assertThat(code(() -> query("www.example.test", "MX"))).isEqualTo("ENODATA");
        assertThat(code(() -> query("servfail.example.test", "A"))).isEqualTo("ESERVFAIL");
        assertThat(code(() -> query("refused.example.test", "A"))).isEqualTo("EREFUSED");
        assertThat(code(() -> query("notimp.example.test", "A"))).isEqualTo("ENOTIMP");
        assertThat(code(() -> await(resolver.query("silent.example.test", 1, servers, 200, 1)))).isEqualTo("ETIMEOUT");
    }

    @Test
    void aTruncatedAnswerIsAskedAgainOverTcp() throws Exception {
        assertThat(query("big.example.test", "TXT").get(0).getJsonArray("entries")).hasSize(3);
    }

    @Test
    void eachServerIsTriedInTurn() throws Exception {
        // The first server isn't there; the second answers.
        List<InetSocketAddress> both = List.of(new InetSocketAddress("127.0.0.1", 1), servers.get(0));
        assertThat(await(resolver.query("example.test", 1, both, 300, 1))).hasSize(2);
        assertThat(code(() -> await(resolver.query("example.test", 1, List.of(both.get(0)), 300, 2)))).isIn("ECONNREFUSED", "ETIMEOUT");
    }

    @Test
    void serversAreParsedAndWrittenAsNodesAre() {
        assertThat(DnsResolver.parseServers("1.1.1.1, 8.8.8.8:5353, [::1]:53, 2001:db8::1")).extracting(DnsResolver::describe)
                .containsExactly("1.1.1.1", "8.8.8.8:5353", "::1", "2001:db8::1");
        assertThat(DnsResolver.parseServer("example.com")).isNull();
        assertThat(DnsResolver.parseServer("1.2.3.4:99999")).isNull();
        assertThatThrownBy(() -> DnsResolver.parseServers("dns.google")).hasMessageContaining("isn't a DNS server");
        assertThat(DnsResolver.isIp("10.0.0.1")).isTrue();
        assertThat(DnsResolver.isIp("10.0.0.256")).isFalse();
        assertThat(DnsResolver.isIp("localhost")).isFalse();
    }

    @Test
    void lookupsAskTheSystemInItsOrderUnlessAnOperatorSetServers() throws Exception {
        DnsResolver system = new DnsResolver(vertx, List.of());
        List<InetAddress> loopback = await(system.lookup("localhost", 0));
        assertThat(loopback).allMatch(InetAddress::isLoopbackAddress);
        assertThat(await(system.lookup("localhost", 4))).allMatch(a -> a instanceof java.net.Inet4Address);
        assertThat(code(() -> await(system.lookup("no-such-name.invalid", 0)))).isEqualTo("ENOTFOUND");
        // With servers set, lookups ask them: a split-horizon system resolver never answers scripts.
        assertThat(await(resolver.lookup("example.test", 0))).extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34", "93.184.216.35", "2606:2800:220:1:0:0:0:1");
        // As Node writes addresses: IPv6 compressed (RFC 5952).
        for (String[] pair : new String[][]{{"::1", "::1"}, {"2001:db8:0:0:1:0:0:1", "2001:db8::1:0:0:1"}, {"fe80::", "fe80::"},
                {"2001:0db8:0000:0000:0000:ff00:0042:8329", "2001:db8::ff00:42:8329"}, {"1:0:2:0:3:0:4:0", "1:0:2:0:3:0:4:0"}, {"::", "::"}}) {
            assertThat(DnsRecords.text(InetAddress.getByName(pair[0]))).as(pair[0]).isEqualTo(pair[1]);
        }
        assertThat(await(resolver.addresses("example.test"))).hasSize(3);
    }

    private static String code(ThrowingCall call) {
        try {
            call.run();
            return "no error";
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            return cause instanceof HostException host ? host.code() : cause.toString();
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
