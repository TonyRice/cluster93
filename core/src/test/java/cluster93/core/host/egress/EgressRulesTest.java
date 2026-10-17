package cluster93.core.host.egress;

import cluster93.core.host.EgressPolicy;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configurable address spaces (D-111): rules at two levels, named sets, protocols, and the posture. */
class EgressRulesTest {

    private static final NodeAddresses NO_NODES = NodeAddresses.EMPTY;
    private static final String PUBLIC_IP = "93.184.216.34";

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static ClusterEgress cluster(String json) {
        return ClusterEgress.fromJson(new JsonObject(json));
    }

    private static TenantEgress tenant(String json) {
        return TenantEgress.fromJson("acme", new JsonObject(json));
    }

    private static Egress.Verdict decide(String host, String address, int port, EgressProtocol protocol, ClusterEgress cluster,
                                         TenantEgress tenant) throws Exception {
        return Egress.decide(host, List.of(ip(address)), port, protocol, NO_NODES, false, cluster, tenant);
    }

    private static Egress.Verdict decide(String address, int port, ClusterEgress cluster, TenantEgress tenant) throws Exception {
        return decide("host.example", address, port, EgressProtocol.HTTP, cluster, tenant);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "172.32.0.1", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "100.128.0.1", "0.0.0.0", "192.0.0.8", "192.0.2.10", "198.51.100.7", "203.0.113.9", "198.18.0.1", "198.20.0.1",
            "192.88.99.1", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fec0::1", "fc00::1",
            "fd12:3456::1", "::ffff:10.0.0.1", "::10.0.0.1", "64:ff9b::a00:1", "64:ff9b:1::1", "2002:a00:1::1", "2001:0:4136:e378::1",
            "2001:db8::1", "100::1", "2001:10::1", "ff02::1", PUBLIC_IP, "1.1.1.1", "8.8.8.8", "2606:4700:4700::1111",
            "::ffff:1.1.1.1", "64:ff9b::101:101", "2002:101:101::1", "2a00:1450::1", "11.0.0.1", "126.255.255.255", "128.0.0.1"})
    void theBuiltInSetsAreExactlyWhatIsntPublic(String literal) throws Exception {
        InetAddress address = ip(literal);
        assertThat(AddressSets.isPublic(Egress.checked(address))).as(literal).isEqualTo(EgressPolicy.isPublic(address));
    }

    @Test
    void aClusterDenyCantBeOverriddenByATenantAllow() throws Exception {
        ClusterEgress c = cluster("{\"rules\": [{\"action\": \"deny\", \"to\": \"10.0.5.0/24\", \"note\": \"the billing network\"}]}");
        TenantEgress t = tenant("{\"rules\": [{\"to\": \"10.0.5.20/32\", \"ports\": [5432]}]}");
        Egress.Verdict verdict = decide("10.0.5.20", 5432, c, t);
        assertThat(verdict.refused()).isEqualTo(Egress.Reason.CLUSTER_RULE);
        assertThat(verdict.rule().note()).isEqualTo("the billing network");
    }

    @Test
    void aClusterDenyRefusesPublicAddressesForEveryTenant() throws Exception {
        ClusterEgress c = cluster("{\"addressSets\": {\"partner\": [\"93.184.0.0/16\"]}, \"rules\": [{\"action\": \"deny\", \"to\": \"@partner\"}]}");
        assertThat(decide(PUBLIC_IP, 443, c, TenantEgress.none("acme")).refused()).isEqualTo(Egress.Reason.CLUSTER_RULE);
        assertThat(decide("1.1.1.1", 443, c, TenantEgress.none("acme")).address()).isNotNull();
    }

    @Test
    void aTenantDenyRefusesEvenWhatTheClusterAllows() throws Exception {
        ClusterEgress c = cluster("{\"rules\": [{\"to\": \"10.9.0.0/16\", \"ports\": [5432]}]}");
        TenantEgress t = tenant("{\"rules\": [{\"action\": \"deny\", \"to\": \"10.9.1.0/24\"}, {\"action\": \"deny\", \"to\": \"*.example.com\"}]}");
        assertThat(decide("10.9.1.5", 5432, c, t).refused()).isEqualTo(Egress.Reason.TENANT_RULE);
        assertThat(decide("10.9.2.5", 5432, c, t).address()).isNotNull();
        assertThat(decide("api.example.com", PUBLIC_IP, 443, EgressProtocol.HTTP, c, t).refused()).isEqualTo(Egress.Reason.TENANT_RULE);
        // Another tenant isn't affected: the cluster's allow opens the private range to every tenant.
        assertThat(decide("10.9.1.5", 5432, c, TenantEgress.none("other")).address()).isNotNull();
    }

    @Test
    void theAllowListPostureRefusesWhatNoRuleAllows() throws Exception {
        ClusterEgress c = cluster("{\"posture\": \"allow-list\", \"rules\": [{\"to\": \"api.partner.example\", \"ports\": [443]}]}");
        TenantEgress none = TenantEgress.none("acme");
        assertThat(decide(PUBLIC_IP, 443, c, none).refused()).isEqualTo(Egress.Reason.NOT_ALLOWED);
        assertThat(decide("api.partner.example", PUBLIC_IP, 443, EgressProtocol.HTTP, c, none).address()).isNotNull();
        assertThat(decide("api.partner.example", PUBLIC_IP, 80, EgressProtocol.HTTP, c, none).refused()).isEqualTo(Egress.Reason.NOT_ALLOWED);

        // "@public on 443" opens HTTPS to the internet, and still nothing private.
        TenantEgress https = tenant("{\"rules\": [{\"to\": \"@public\", \"ports\": [443]}]}");
        assertThat(decide(PUBLIC_IP, 443, c, https).address()).isNotNull();
        assertThat(decide("10.0.0.1", 443, c, https).refused()).isEqualTo(Egress.Reason.NOT_ALLOWED);
    }

    @Test
    void aTenantsOwnPostureReplacesTheClusters() throws Exception {
        ClusterEgress locked = cluster("{\"posture\": \"allow-list\"}");
        assertThat(decide(PUBLIC_IP, 443, locked, tenant("{\"posture\": \"public\"}")).address()).isNotNull();
        assertThat(decide(PUBLIC_IP, 443, ClusterEgress.DEFAULT, tenant("{\"posture\": \"allow-list\"}")).refused())
                .isEqualTo(Egress.Reason.NOT_ALLOWED);
        assertThat(tenant("{\"posture\": \"inherit\"}").posture()).isNull();
    }

    @Test
    void smtpIsClosedToRawSocketsByDefaultAndOnlyToThem() throws Exception {
        TenantEgress none = TenantEgress.none("acme");
        Egress.Verdict smtp = decide("mail.example", PUBLIC_IP, 25, EgressProtocol.TCP, ClusterEgress.DEFAULT, none);
        assertThat(smtp.refused()).isEqualTo(Egress.Reason.CLUSTER_RULE);
        assertThat(smtp.rule().note()).contains("blocklisted");
        assertThat(decide("mail.example", PUBLIC_IP, 587, EgressProtocol.TCP, ClusterEgress.DEFAULT, none).refused()).isEqualTo(Egress.Reason.CLUSTER_RULE);
        assertThat(decide("mail.example", PUBLIC_IP, 2525, EgressProtocol.TCP, ClusterEgress.DEFAULT, none).address()).isNotNull();
        assertThat(decide("mail.example", PUBLIC_IP, 25, EgressProtocol.HTTP, ClusterEgress.DEFAULT, none).address()).isNotNull();
        // An operator lifts it by replacing the cluster's rules with a narrower deny.
        ClusterEgress narrower = cluster("{\"rules\": [{\"action\": \"deny\", \"to\": \"*\", \"ports\": [25], \"protocols\": [\"tcp\"]}]}");
        assertThat(decide("smtp.sendgrid.net", PUBLIC_IP, 587, EgressProtocol.TCP, narrower, none).address()).isNotNull();
    }

    @Test
    void nothingReachesAClusterNodeAndTheDevelopmentSwitchStillHonoursDenies() throws Exception {
        NodeAddresses nodes = new NodeAddresses(Set.of(ip(PUBLIC_IP)), Set.of(8080));
        ClusterEgress everything = cluster("{\"rules\": [{\"to\": \"*\", \"ports\": [\"1-65535\"]}]}");
        TenantEgress all = tenant("{\"posture\": \"public\", \"rules\": [{\"to\": \"@public\", \"ports\": [\"1-65535\"]}]}");
        assertThat(Egress.decide("node", List.of(ip(PUBLIC_IP)), 3000, EgressProtocol.HTTP, nodes, false, everything, all).refused())
                .isEqualTo(Egress.Reason.CLUSTER_NODE);
        assertThat(Egress.decide("localhost", List.of(ip("127.0.0.1")), 3000, EgressProtocol.HTTP, nodes, false, everything, all).refused())
                .isEqualTo(Egress.Reason.CLUSTER_NODE);

        ClusterEgress noLoopback = cluster("{\"rules\": [{\"action\": \"deny\", \"to\": \"@loopback\"}]}");
        assertThat(Egress.decide("localhost", List.of(ip("127.0.0.1")), 3000, EgressProtocol.HTTP, NO_NODES, true, noLoopback,
                TenantEgress.none("acme")).refused()).isEqualTo(Egress.Reason.CLUSTER_RULE);
        assertThat(Egress.decide("db", List.of(ip("10.0.0.9")), 5432, EgressProtocol.HTTP, NO_NODES, true, noLoopback,
                TenantEgress.none("acme")).address()).isNotNull();
    }

    @Test
    void anAllowJudgesTheAddressReallyReachedAndADenyEitherForm() throws Exception {
        // NAT64 carrying 10.0.5.20: allowing the NAT64 prefix doesn't open the private address behind it.
        TenantEgress nat64 = tenant("{\"rules\": [{\"to\": \"64:ff9b::/96\", \"ports\": [5432]}]}");
        assertThat(decide("64:ff9b::a00:514", 5432, ClusterEgress.DEFAULT, nat64).refused()).isEqualTo(Egress.Reason.PRIVATE);
        // Denying 6to4 refuses a public address reached through it.
        TenantEgress no6to4 = tenant("{\"rules\": [{\"action\": \"deny\", \"to\": \"2002::/16\"}]}");
        assertThat(decide("2002:101:101::1", 443, ClusterEgress.DEFAULT, no6to4).refused()).isEqualTo(Egress.Reason.TENANT_RULE);
        assertThat(decide("1.1.1.1", 443, ClusterEgress.DEFAULT, no6to4).address()).isNotNull();
        // A deny of the IPv4 range catches its IPv6 forms.
        TenantEgress noCloudflare = tenant("{\"rules\": [{\"action\": \"deny\", \"to\": \"1.1.1.0/24\"}]}");
        assertThat(decide("::ffff:1.1.1.1", 443, ClusterEgress.DEFAULT, noCloudflare).refused()).isEqualTo(Egress.Reason.TENANT_RULE);
    }

    @Test
    void suffixesMatchSubdomainsOnly() throws Exception {
        TenantEgress t = tenant("{\"rules\": [{\"action\": \"deny\", \"to\": \"*.example.com\"}, {\"action\": \"deny\", \"to\": \"*.ru\"}]}");
        for (String host : List.of("a.example.com", "b.a.EXAMPLE.com.", "mail.yandex.ru")) {
            assertThat(decide(host, PUBLIC_IP, 443, EgressProtocol.HTTP, ClusterEgress.DEFAULT, t).refused()).as(host).isEqualTo(Egress.Reason.TENANT_RULE);
        }
        for (String host : List.of("example.com", "badexample.com", "example.com.au")) {
            assertThat(decide(host, PUBLIC_IP, 443, EgressProtocol.HTTP, ClusterEgress.DEFAULT, t).address()).as(host).isNotNull();
        }
    }

    @Test
    void aSetThatNoLongerExistsRefusesInADenyAndOpensNothingInAnAllow() throws Exception {
        TenantEgress deny = tenant("{\"rules\": [{\"action\": \"deny\", \"to\": \"@gone\"}]}");
        assertThat(decide(PUBLIC_IP, 443, ClusterEgress.DEFAULT, deny).refused()).isEqualTo(Egress.Reason.TENANT_RULE);
        TenantEgress allow = tenant("{\"rules\": [{\"to\": \"@gone\", \"ports\": [5432]}]}");
        assertThat(decide("10.0.0.1", 5432, ClusterEgress.DEFAULT, allow).refused()).isEqualTo(Egress.Reason.PRIVATE);
    }

    @Test
    void theOlderAllowListIsReadAsAllowRules() {
        TenantEgress t = tenant("{\"allow\": [{\"to\": \"10.0.5.20/32\", \"ports\": [5432]}], "
                + "\"rules\": [{\"action\": \"deny\", \"to\": \"203.0.113.0/24\"}]}");
        assertThat(t.rules()).extracting(EgressRule::action).containsExactly(EgressRule.Action.ALLOW, EgressRule.Action.DENY);
        assertThat(t.toJson().getJsonArray("rules").getJsonObject(0).getString("action")).isEqualTo("allow");
    }

    @Test
    void policiesRoundTrip() {
        ClusterEgress c = cluster("{\"posture\": \"allow-list\", \"addressSets\": {\"corp\": [\"198.51.100.0/24\", \"2001:db8::/48\"]},"
                + " \"rules\": [{\"action\": \"deny\", \"to\": \"@corp\", \"protocols\": [\"tcp\", \"http\"], \"note\": \"ours\"},"
                + " {\"to\": \"*.partner.example\", \"ports\": [443, \"@smtp\"]}]}");
        assertThat(ClusterEgress.fromJson(c.toJson())).usingRecursiveComparison().isEqualTo(c);
        assertThat(c.toJson().getJsonObject("addressSets").getJsonArray("corp").getString(1)).isEqualTo("2001:db8:0:0:0:0:0:0/48");
        assertThat(c.toJson().getJsonArray("rules").getJsonObject(1).getJsonArray("ports").encode()).isEqualTo("[443,25,465,587]");
        assertThat(ClusterEgress.DEFAULT.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.action()).isEqualTo(EgressRule.Action.DENY);
            assertThat(rule.protocols()).containsExactly(EgressProtocol.TCP);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rules\": [{\"to\": \"*\"}]}",
            "{\"rules\": [{\"action\": \"block\", \"to\": \"*\"}]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"*\", \"protocols\": [\"udp\"]}]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"*\", \"protocols\": []}]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"@Bad_Name\"}]}",
            "{\"rules\": [{\"to\": \"*.com\", \"ports\": [443]}]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"*.\"}]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"*\", \"ports\": [\"@nope\"]}]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"*\", \"note\": 7}]}",
            "{\"posture\": \"open\"}",
            "{\"rules\": {\"to\": \"*\"}}"})
    void refusesMalformedTenantPolicies(String json) {
        assertThatThrownBy(() -> tenant(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"addressSets\": {\"private\": [\"10.0.0.0/8\"]}}",
            "{\"addressSets\": {\"public\": [\"1.0.0.0/8\"]}}",
            "{\"addressSets\": {\"corp\": []}}",
            "{\"addressSets\": {\"corp\": [\"not a range\"]}}",
            "{\"addressSets\": [\"10.0.0.0/8\"]}",
            "{\"rules\": [{\"action\": \"deny\", \"to\": \"@undefined\"}]}",
            "{\"posture\": \"closed\"}"})
    void refusesMalformedClusterPolicies(String json) {
        assertThatThrownBy(() -> cluster(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesANoteLongerThanTwoHundredCharacters() {
        String note = "x".repeat(201);
        assertThatThrownBy(() -> tenant("{\"rules\": [{\"action\": \"deny\", \"to\": \"*\", \"note\": \"" + note + "\"}]}"))
                .hasMessageContaining("200");
    }
}
