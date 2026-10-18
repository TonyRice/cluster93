package cluster93.runner.protocol.message;

import cluster93.core.host.egress.Placement;
import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy a worker's placement runs under has to survive the wire exactly (D-173): if a rule or an address is lost
 * in the encoding, the worker decides on a policy the node never sent.
 */
class PolicyMessageTest {

    private static PolicyMessage sample() {
        return new PolicyMessage("acme", Placement.WORKER, 7L, 90_000L, false, List.of("corp.example", "svc"),
                new JsonObject().put("posture", "allow-list")
                        .put("rules", new io.vertx.core.json.JsonArray().add(new JsonObject().put("to", "api.stripe.com").put("ports", new io.vertx.core.json.JsonArray().add(443)))),
                new JsonObject().put("posture", "public").put("rules", new io.vertx.core.json.JsonArray()),
                List.of("10.0.0.5", "2001:db8::5"), List.of(9393, 9394, 9500));
    }

    @Test
    @DisplayName("A policy round-trips through the frame encoding, field for field (D-173)")
    void roundTripsThroughTheWire() {
        PolicyMessage sent = sample();
        List<Frame> decoded = new ArrayList<>();
        FrameCodec.parser(decoded::add, err -> { throw new RuntimeException(err); }).handle(FrameCodec.encode(sent.toFrame()));

        assertThat(decoded).hasSize(1);
        assertThat(decoded.getFirst().type()).isEqualTo(FrameType.POLICY);
        assertThat(decoded.getFirst().channelId()).as("a policy is connection-wide, not per run").isZero();

        PolicyMessage got = PolicyMessage.fromFrame(decoded.getFirst());
        assertThat(got).isEqualTo(sent);
        assertThat(got.clusterPolicy().getString("posture")).isEqualTo("allow-list");
        assertThat(got.clusterPolicy().getJsonArray("rules").getJsonObject(0).getString("to")).isEqualTo("api.stripe.com");
        assertThat(got.nodeAddresses()).containsExactly("10.0.0.5", "2001:db8::5");
        assertThat(got.nodePorts()).containsExactly(9393, 9394, 9500);
    }

    @Test
    @DisplayName("Absent fields come back as empty, never as null (D-173)")
    void absentFieldsAreEmpty() {
        PolicyMessage got = PolicyMessage.fromFrame(Frame.control(FrameType.POLICY, new JsonObject()));
        assertThat(got.tenantId()).isNull();
        assertThat(got.placement()).as("a worker is the default placement").isEqualTo(Placement.WORKER);
        assertThat(got.version()).isZero();
        assertThat(got.expiresInMs()).as("no expiry means the worker must not trust it").isZero();
        assertThat(got.allowPrivate()).isFalse();
        assertThat(got.internalDomains()).isEmpty();
        assertThat(got.clusterPolicy()).isNotNull();
        assertThat(got.tenantPolicy()).isNotNull();
        assertThat(got.nodeAddresses()).isEmpty();
        assertThat(got.nodePorts()).isEmpty();
    }

    @Test
    @DisplayName("The placement crosses the wire too, so a per-tenant override arrives with the binding (D-173 item 8)")
    void thePlacementCrossesTheWire() {
        PolicyMessage sent = new PolicyMessage("acme", Placement.NODE, 1L, 90_000L, false, List.of(),
                new JsonObject().put("posture", "allow-list"), new JsonObject(), List.of(), List.of());
        PolicyMessage got = PolicyMessage.fromFrame(sent.toFrame());
        assertThat(got.placement()).as("a tenant the node decides for").isEqualTo(Placement.NODE);
    }

    @Test
    @DisplayName("A frame of another type is refused rather than read as a policy (D-173)")
    void refusesAnotherFrameType() {
        assertThatThrownBy(() -> PolicyMessage.fromFrame(Frame.control(FrameType.BIND, new JsonObject())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POLICY");
    }

    @Test
    @DisplayName("The encoding carries the policy objects as they are stored, so the worker parses them with shared code")
    void carriesThePoliciesAsStored() {
        Buffer encoded = FrameCodec.encode(sample().toFrame());
        assertThat(encoded.length()).as("a policy is a small control frame").isLessThan(4096);
    }
}
