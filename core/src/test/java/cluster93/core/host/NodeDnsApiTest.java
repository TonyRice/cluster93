package cluster93.core.host;

import cluster93.core.host.dns.DnsResolver;
import cluster93.core.host.dns.TestDnsServer;
import cluster93.core.host.egress.ClusterAddresses;
import cluster93.core.host.egress.ClusterEgress;
import cluster93.core.host.egress.Egress;
import cluster93.core.host.egress.EgressPolicies;
import cluster93.core.host.egress.TenantEgress;
import cluster93.core.runtime.HostException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** What a script's {@code dns} may see (§8.2, D-122): only what its tenant could reach. */
class NodeDnsApiTest {

    private Vertx vertx;
    private TestDnsServer server;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        server = new TestDnsServer(vertx).start()
                .a("mixed.example.test", "10.0.0.5").a("mixed.example.test", "93.184.216.34")
                .a("private.example.test", "192.168.1.10")
                .a("db.internal", "10.0.0.6").a("intranet", "10.0.0.7").a("app.corp.example", "93.184.216.40")
                .mx("example.test", 10, "mail.example.test").mx("example.test", 20, "mx.corp.internal")
                .srv("_mongodb._tcp.example.test", 0, 5, 27017, "db.cluster.local")
                .txt("example.test", "v=spf1 -all")
                .ptr("34.216.184.93.in-addr.arpa", "example.test")
                .ptr("5.0.0.10.in-addr.arpa", "secret-db.internal");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Queries go to the test server, as with C93_EGRESS_DNS (lookups too). */
    private NodeDnsApi dns(boolean allowPrivate) {
        return api(allowPrivate, List.of(new InetSocketAddress("127.0.0.1", server.port())));
    }

    /** Lookups ask the operating system, as without C93_EGRESS_DNS. */
    private NodeDnsApi system(boolean allowPrivate) {
        return api(allowPrivate, List.of());
    }

    private NodeDnsApi api(boolean allowPrivate, List<InetSocketAddress> servers) {
        Egress egress = new Egress(vertx, allowPrivate, new ClusterAddresses(vertx, null, Set.of()), EgressPolicies.NONE,
                new DnsResolver(vertx, servers), List.of("corp.example"));
        return new NodeDnsApi(egress, "acme");
    }

    private static JsonObject query(String name, String type) {
        return new JsonObject().put("name", name).put("type", type).put("timeout", 1000).put("tries", 1);
    }

    @Test
    void onlyAddressesTheTenantCouldReachAreShown() throws Exception {
        NodeDnsApi dns = dns(false);
        assertThat(await(dns.resolve(query("mixed.example.test", "A")))).extracting(r -> ((JsonObject) r).getString("address"))
                .containsExactly("93.184.216.34");
        assertThat(code(dns.resolve(query("private.example.test", "A")))).isEqualTo("ENODATA");
        // Names in internal zones aren't resolved at all: the special-use ones, single labels, and the operator's.
        assertThat(code(dns.resolve(query("db.internal", "A")))).isEqualTo("ENOTFOUND");
        assertThat(code(dns.resolve(query("intranet", "A")))).isEqualTo("ENOTFOUND");
        assertThat(code(dns.resolve(query("app.corp.example", "A")))).isEqualTo("ENOTFOUND");
        // Records naming internal hosts are left out; the rest stay.
        assertThat(await(dns.resolve(query("example.test", "MX")))).extracting(r -> ((JsonObject) r).getString("exchange"))
                .containsExactly("mail.example.test");
        assertThat(code(dns.resolve(query("_mongodb._tcp.example.test", "SRV")))).isEqualTo("ENODATA");
        assertThat(await(dns.resolve(query("example.test", "TXT")))).hasSize(1);
        // Reverse lookups only for addresses it could reach.
        assertThat(await(dns.reverse(new JsonObject().put("address", "93.184.216.34")))).containsExactly("example.test");
        assertThat(code(dns.reverse(new JsonObject().put("address", "10.0.0.5")))).isEqualTo("ENOTFOUND");
        assertThat(code(dns.resolve(query("5.0.0.10.in-addr.arpa", "PTR")))).isEqualTo("ENOTFOUND");
        // localhost is always loopback, which reveals nothing; connecting there is still refused (D-123).
        assertThat(await(system(false).lookup(new JsonObject().put("hostname", "localhost")))).allMatch(a -> ((JsonObject) a).getString("address").matches("127\\..*|::1"));
        assertThat(code(system(false).lookupService(new JsonObject().put("address", "127.0.0.1").put("port", 22)))).isEqualTo("ENOTFOUND");
        // With servers set, lookups ask them too, and filter the same way.
        assertThat(await(dns.lookup(new JsonObject().put("hostname", "mixed.example.test")))).extracting(r -> ((JsonObject) r).getString("address"))
                .containsExactly("93.184.216.34");
    }

    @Test
    void theDevelopmentSwitchShowsPrivateAddressesAndInternalNames() throws Exception {
        NodeDnsApi dns = dns(true);
        assertThat(await(dns.resolve(query("mixed.example.test", "A")))).hasSize(2);
        assertThat(await(dns.resolve(query("db.internal", "A")))).hasSize(1);
        assertThat(await(dns.resolve(query("_mongodb._tcp.example.test", "SRV")))).hasSize(1);
        assertThat(await(dns.reverse(new JsonObject().put("address", "10.0.0.5")))).containsExactly("secret-db.internal");
        assertThat(await(system(true).lookup(new JsonObject().put("hostname", "localhost")))).isNotEmpty();
        JsonObject service = await(system(true).lookupService(new JsonObject().put("address", "127.0.0.1").put("port", 22)));
        assertThat(service.getString("hostname")).isEqualTo("localhost");
        assertThat(service.getString("service")).isIn("ssh", "22");
    }

    @Test
    void serversAScriptChoosesGoThroughTheGate() throws Exception {
        JsonObject own = query("mixed.example.test", "A").put("servers", new JsonArray().add(server.address()));
        // A private server needs a rule, or the development switch; the message says why.
        Throwable refused = failure(dns(false).resolve(own));
        assertThat(((HostException) refused).code()).isEqualTo("ECONNREFUSED");
        assertThat(refused.getMessage()).contains("is not allowed");
        assertThat(await(dns(true).resolve(own))).hasSize(2);
    }

    @Test
    void manyQueriesWaitTheirTurnRatherThanFail() throws Exception {
        NodeDnsApi dns = dns(false);
        List<Future<JsonArray>> all = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            all.add(dns.resolve(query("mixed.example.test", "A")));
        }
        await(Future.all(all));
        assertThat(all).allMatch(Future::succeeded);
    }

    @Test
    void visibilityFollowsTheRulesWhateverTheirPortsAndProtocols() throws Exception {
        InetAddress db = InetAddress.getByName("10.0.0.5");
        InetAddress site = InetAddress.getByName("93.184.216.34");
        ClusterAddresses.Snapshot nodes = ClusterAddresses.Snapshot.EMPTY;
        // An allow rule for one port and protocol is enough to see the address.
        TenantEgress postgres = TenantEgress.fromJson("acme", new JsonObject("{\"rules\":[{\"action\":\"allow\",\"to\":\"10.0.0.5/32\",\"ports\":[5432],\"protocols\":[\"tcp\"]}]}"));
        assertThat(Egress.visible("db.example.test", db, nodes, false, ClusterEgress.DEFAULT, postgres)).isTrue();
        assertThat(Egress.visible("db.example.test", db, nodes, false, ClusterEgress.DEFAULT, TenantEgress.none("acme"))).isFalse();
        // A deny rule hides an address only if it covers every port and dns.
        TenantEgress denyHttps = TenantEgress.fromJson("acme", new JsonObject("{\"rules\":[{\"action\":\"deny\",\"to\":\"93.184.216.34/32\",\"ports\":[443]}]}"));
        assertThat(Egress.visible("example.test", site, nodes, false, ClusterEgress.DEFAULT, denyHttps)).isTrue();
        TenantEgress denyDns = TenantEgress.fromJson("acme", new JsonObject("{\"rules\":[{\"action\":\"deny\",\"to\":\"93.184.216.34/32\",\"protocols\":[\"dns\"]}]}"));
        assertThat(Egress.visible("example.test", site, nodes, false, ClusterEgress.DEFAULT, denyDns)).isFalse();
        // Under allow-list, only what a rule allows.
        TenantEgress allowList = TenantEgress.fromJson("acme", new JsonObject("{\"posture\":\"allow-list\",\"rules\":[]}"));
        assertThat(Egress.visible("example.test", site, nodes, false, ClusterEgress.DEFAULT, allowList)).isFalse();
        // A rule naming an internal name lets it resolve.
        TenantEgress named = TenantEgress.fromJson("acme", new JsonObject("{\"rules\":[{\"action\":\"allow\",\"to\":\"*.corp.internal\",\"ports\":[443]}]}"));
        Egress.DnsView view = new Egress.DnsView(nodes, false, ClusterEgress.DEFAULT, named, Egress.INTERNAL_SUFFIXES);
        assertThat(view.hidden("db.corp.internal")).isFalse();
        assertThat(view.hidden("db.other.internal")).isTrue();
        assertThat(view.hidden("example.com")).isFalse();
    }

    private static String code(Future<?> future) {
        Throwable failure = failure(future);
        return failure instanceof HostException host ? host.code() : String.valueOf(failure);
    }

    private static Throwable failure(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
