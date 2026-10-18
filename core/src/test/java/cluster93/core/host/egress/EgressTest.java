package cluster93.core.host.egress;

import cluster93.core.host.EgressPolicy;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgressTest {

    private static final NodeAddresses NO_NODES = NodeAddresses.EMPTY;

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static TenantEgress policy(String json) {
        return TenantEgress.fromJson("acme", new JsonObject(json));
    }

    private static boolean allows(TenantEgress policy, String host, InetAddress address, int port) {
        return policy.rules().stream().anyMatch(rule -> rule.action() == EgressRule.Action.ALLOW
                && rule.matches(host, address, Decision.checked(address), port, EgressProtocol.HTTP, AddressSets.builtInOnly()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
            "192.0.0.8", "192.0.2.10", "198.51.100.7", "203.0.113.9", "198.18.0.1", "192.88.99.1", "224.0.0.1", "240.0.0.1",
            "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1", "::10.0.0.1",
            "64:ff9b::a00:1", "64:ff9b:1::1", "2002:a00:1::1", "2001:0:4136:e378::1", "2001:db8::1", "100::1", "2001:10::1",
            // The first and last address of every range (D-108): where a range's bounds are wrong, it shows here.
            "10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255", "100.64.0.0",
            "100.127.255.255", "169.254.0.0", "169.254.255.255", "127.0.0.0", "127.255.255.255", "198.18.0.0", "198.19.255.255",
            "192.0.0.0", "192.0.0.255", "192.0.2.0", "192.0.2.255", "192.88.99.0", "192.88.99.255", "198.51.100.0",
            "198.51.100.255", "203.0.113.0", "203.0.113.255", "240.0.0.0", "239.255.255.255",
            // ...and the IPv6 ranges', with the IPv4 form an address carries judged as that IPv4 address.
            "fc00::", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:10::", "2001:1f:ffff::", "100::", "100::ffff:ffff:ffff:ffff", "64:ff9b::", "64:ff9b:1::", "2002::",
            "::ffff:127.0.0.1", "::127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1"})
    void privateAndReservedAddressesAreNotPublic(String literal) throws Exception {
        assertThat(EgressPolicy.isPublic(ip(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "::ffff:1.1.1.1", "64:ff9b::101:101",
            "2002:101:101::1", "2a00:1450::1",
            // Just outside each range, which must stay reachable: a range that grew by one address would show here.
            "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "100.63.255.255",
            "100.128.0.0", "169.253.255.255", "169.255.0.0", "126.255.255.255", "128.0.0.0", "198.17.255.255", "198.20.0.0",
            "223.255.255.255"})
    void publicAddressesArePublic(String literal) throws Exception {
        assertThat(EgressPolicy.isPublic(ip(literal))).as(literal).isTrue();
    }

    @Test
    void embeddedIpv4FormsAreJudgedByTheirIpv4Address() throws Exception {
        assertThat(EgressPolicy.embeddedIpv4(ip("64:ff9b::a00:514"))).isEqualTo(ip("10.0.5.20"));
        assertThat(EgressPolicy.embeddedIpv4(ip("2002:0a00:0514::1"))).isEqualTo(ip("10.0.5.20"));
        assertThat(EgressPolicy.embeddedIpv4(ip("::10.0.5.20"))).isEqualTo(ip("10.0.5.20"));
        assertThat(EgressPolicy.embeddedIpv4(ip("2606:4700::1"))).isNull();
    }

    @Test
    void parsesCidrAndHostRules() throws Exception {
        TenantEgress p = policy("""
                {"allow": [{"to": "10.0.5.20/32", "ports": [5432]},
                           {"to": "db.Internal.Example.", "ports": ["6379", "8000-8099"]},
                           {"to": "10.1.0.0/16", "ports": ["8000-8099"]},
                           {"to": "fd00::/8", "ports": [443]},
                           {"to": "192.168.7.7", "ports": [22]}]}""");
        assertThat(allows(p, null, ip("10.0.5.20"), 5432)).isTrue();
        assertThat(allows(p, null, ip("10.0.5.21"), 5432)).isFalse();
        assertThat(allows(p, null, ip("10.0.5.20"), 5433)).isFalse();
        assertThat(allows(p, "db.internal.example", ip("10.9.9.9"), 6379)).isTrue();
        assertThat(allows(p, "DB.INTERNAL.EXAMPLE", ip("10.9.9.9"), 8050)).isTrue();
        assertThat(allows(p, "other.internal.example", ip("10.9.9.9"), 6379)).isFalse();
        assertThat(allows(p, null, ip("10.1.200.3"), 8099)).isTrue();
        assertThat(allows(p, null, ip("10.2.0.1"), 8099)).isFalse();
        assertThat(allows(p, null, ip("fd12::5"), 443)).isTrue();
        assertThat(allows(p, null, ip("192.168.7.7"), 22)).isTrue();
        assertThat(p.toJson().getJsonArray("rules").getJsonObject(1).getJsonArray("ports")).isEqualTo(new JsonArray().add(6379).add("8000-8099"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"allow\": [{\"to\": \"10.0.0.0/8\"}]}",
            "{\"allow\": [{\"to\": \"10.0.0.0/8\", \"ports\": []}]}",
            "{\"allow\": [{\"to\": \"10.0.0.0/8\", \"ports\": [0]}]}",
            "{\"allow\": [{\"to\": \"10.0.0.0/8\", \"ports\": [70000]}]}",
            "{\"allow\": [{\"to\": \"10.0.0.0/8\", \"ports\": [\"9-1\"]}]}",
            "{\"allow\": [{\"to\": \"10.0.0.0/4\", \"ports\": [1]}]}",
            "{\"allow\": [{\"to\": \"0.0.0.0/0\", \"ports\": [1]}]}",
            "{\"allow\": [{\"to\": \"999.1.1.1\", \"ports\": [1]}]}",
            "{\"allow\": [{\"to\": \"dead.beef.cafe.bad/24\", \"ports\": [1]}]}",
            "{\"allow\": [{\"to\": \"1.2.3\", \"ports\": [1]}]}",
            "{\"allow\": [{\"to\": \"bad host\", \"ports\": [1]}]}",
            "{\"allow\": [{\"to\": \"\", \"ports\": [1]}]}",
            "{\"allow\": [\"10.0.0.1\"]}",
            "{\"allow\": \"10.0.0.1\"}"})
    void refusesMalformedRules(String json) {
        assertThatThrownBy(() -> policy(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHexLookingHostNameIsAHostRuleAndIsNeverLookedUp() {
        TenantEgress p = policy("{\"allow\": [{\"to\": \"dead.beef.cafe.bad\", \"ports\": [80]}]}");
        assertThat(p.rules().get(0).name()).isEqualTo("dead.beef.cafe.bad");
        assertThat(p.rules().get(0).kind()).isEqualTo(EgressRule.Kind.HOST);
    }

    @Test
    void refusesMoreThanAHundredRules() {
        JsonArray rules = new JsonArray();
        for (int i = 0; i <= TenantEgress.MAX_RULES; i++) {
            rules.add(new JsonObject().put("to", "10.0.0." + (i % 250) + "/32").put("ports", new JsonArray().add(80)));
        }
        assertThatThrownBy(() -> TenantEgress.fromJson("acme", new JsonObject().put("allow", rules))).hasMessageContaining("At most 100");
    }

    @Test
    @DisplayName("A tenant can name its own placement, and a placement on its own is a policy an operator meant to keep (D-173 item 8)")
    void aTenantCanNameItsOwnPlacement() {
        assertThat(policy("{\"placement\": \"node\"}").placement())
                .as("this tenant, decided on the node, whatever the node's own default is").isEqualTo(Placement.NODE);
        assertThat(policy("{\"placement\": \" WORKER \"}").placement())
                .as("written by hand, like every other operator value").isEqualTo(Placement.WORKER);
        assertThat(policy("{\"placement\": \"inherit\"}").placement()).as("follow the node").isNull();
        assertThat(TenantEgress.none("acme").placement()).as("nothing set means no opinion of its own").isNull();

        // It round-trips: the node stores this JSON and sends the same object to a worker.
        assertThat(policy("{\"placement\": \"node\"}").toJson().getString("placement")).isEqualTo("node");
        assertThat(TenantEgress.none("acme").toJson().containsKey("placement"))
                .as("a tenant that follows the node stores no placement of its own").isFalse();

        // A policy that reads as empty is removed rather than stored (EgressPolicies.put), so a placement alone has to
        // count: "this tenant is decided on the node" is exactly the kind of thing an operator would lose silently.
        assertThat(policy("{\"placement\": \"node\"}").isEmpty()).isFalse();
        assertThat(TenantEgress.none("acme").isEmpty()).isTrue();

        assertThatThrownBy(() -> policy("{\"placement\": \"nowhere\"}"))
                .hasMessageContaining("placement").hasMessageContaining("worker or node");
        assertThatThrownBy(() -> policy("{\"placement\": \"inherit-x\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicAddressesAreAllowedAndPrivateOnesNeedARule() throws Exception {
        TenantEgress none = TenantEgress.none("acme");
        assertThat(Decision.decide("example.com", List.of(ip("93.184.216.34")), 443, NO_NODES, false, none).address()).isEqualTo(ip("93.184.216.34"));
        Decision.Verdict refused = Decision.decide("db", List.of(ip("10.0.5.20")), 5432, NO_NODES, false, none);
        assertThat(refused.address()).isNull();
        assertThat(refused.refused()).isEqualTo(Decision.Reason.PRIVATE);
        TenantEgress rule = policy("{\"allow\": [{\"to\": \"10.0.5.20/32\", \"ports\": [5432]}]}");
        assertThat(Decision.decide("db", List.of(ip("10.0.5.20")), 5432, NO_NODES, false, rule).address()).isEqualTo(ip("10.0.5.20"));
        assertThat(Decision.decide("db", List.of(ip("10.0.5.20")), 22, NO_NODES, false, rule).address()).isNull();
    }

    @Test
    void aRuleCoversTheIpv4AddressAnIpv6FormCarries() throws Exception {
        TenantEgress rule = policy("{\"allow\": [{\"to\": \"10.0.5.20/32\", \"ports\": [5432]}]}");
        assertThat(Decision.decide("db", List.of(ip("64:ff9b::a00:514")), 5432, NO_NODES, false, rule).address()).isNotNull();
        assertThat(Decision.decide("db", List.of(ip("64:ff9b::a00:515")), 5432, NO_NODES, false, rule).address()).isNull();
    }

    @Test
    void theFirstReachableAddressIsTheOneUsed() throws Exception {
        Decision.Verdict verdict = Decision.decide("mixed", List.of(ip("10.0.0.1"), ip("93.184.216.34")), 443, NO_NODES, false, TenantEgress.none("acme"));
        assertThat(verdict.address()).isEqualTo(ip("93.184.216.34"));
    }

    @Test
    void clusterNodesAreRefusedEvenOnPublicAddressesAndByAnyRule() throws Exception {
        NodeAddresses nodes = new NodeAddresses(Set.of(ip("93.184.216.34"), ip("10.0.5.20")), Set.of(8080));
        TenantEgress everything = policy("{\"allow\": [{\"to\": \"10.0.0.0/8\", \"ports\": [\"1-65535\"]}, {\"to\": \"node.example\", \"ports\": [\"1-65535\"]}]}");
        Decision.Verdict publicNode = Decision.decide("node.example", List.of(ip("93.184.216.34")), 3000, nodes, false, everything);
        assertThat(publicNode.address()).isNull();
        assertThat(publicNode.refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
        assertThat(Decision.decide("x", List.of(ip("10.0.5.20")), 5432, nodes, false, everything).refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
        // Its NAT64 and IPv4-mapped forms are the same node.
        assertThat(Decision.decide("x", List.of(ip("64:ff9b::a00:514")), 5432, nodes, false, everything).refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
        assertThat(Decision.decide("x", List.of(ip("::ffff:10.0.5.20")), 5432, nodes, false, everything).refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
        // Loopback and the wildcard address are this node, always.
        assertThat(Decision.decide("localhost", List.of(ip("127.0.0.2")), 3000, NO_NODES, false, everything).address()).isNull();
        assertThat(Decision.decide("zero", List.of(ip("0.0.0.0")), 8080, NO_NODES, false, everything).address()).isNull();
    }

    @Test
    void theDevelopmentSwitchOpensPrivateAddressesButNeverANodesOwnPorts() throws Exception {
        NodeAddresses nodes = new NodeAddresses(Set.of(ip("10.0.5.20")), Set.of(8080, 8090));
        TenantEgress none = TenantEgress.none("acme");
        assertThat(Decision.decide("db", List.of(ip("10.9.9.9")), 5432, nodes, true, none).address()).isNotNull();
        assertThat(Decision.decide("localhost", List.of(ip("127.0.0.1")), 3000, nodes, true, none).address()).isNotNull();
        assertThat(Decision.decide("localhost", List.of(ip("127.0.0.1")), 8080, nodes, true, none).refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
        assertThat(Decision.decide("node", List.of(ip("10.0.5.20")), 8090, nodes, true, none).refused()).isEqualTo(Decision.Reason.CLUSTER_NODE);
        assertThat(Decision.decide("node", List.of(ip("10.0.5.20")), 5432, nodes, true, none).address()).isNotNull();
    }

    @Test
    void readsTheHostOfANodesEndpoint() {
        assertThat(ClusterAddresses.hostOf("10.0.5.20:8090")).isEqualTo("10.0.5.20");
        assertThat(ClusterAddresses.hostOf("node-1.cluster:8090")).isEqualTo("node-1.cluster");
        assertThat(ClusterAddresses.hostOf("[fd00::1]:8090")).isEqualTo("fd00::1");
        assertThat(ClusterAddresses.hostOf("node-1")).isEqualTo("node-1");
        assertThat(ClusterAddresses.hostOf(null)).isNull();
    }
}
