package cluster93.runner.protocol.message;

import cluster93.runner.protocol.frame.Frame;
import cluster93.runner.protocol.frame.FrameCodec;
import cluster93.runner.protocol.frame.FrameType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a worker refused, on its way to the node (D-176). The counts are the whole content of the message, so losing
 * one in the encoding would make a worker's refusal invisible — the thing the report exists to prevent.
 */
class AccountMessageTest {

    @Test
    @DisplayName("A report round-trips through the frame encoding, count for count (D-176)")
    void roundTripsThroughTheWire() {
        Map<String, Long> denied = new LinkedHashMap<>();
        denied.put("connect_rate", 3L);
        denied.put("not_allowed", 1L);
        AccountMessage sent = new AccountMessage("acme", denied);

        List<Frame> decoded = new ArrayList<>();
        FrameCodec.parser(decoded::add, err -> { throw new RuntimeException(err); }).handle(FrameCodec.encode(sent.toFrame()));

        assertThat(decoded).hasSize(1);
        assertThat(decoded.getFirst().type()).isEqualTo(FrameType.ACCOUNT);
        assertThat(decoded.getFirst().channelId()).as("accounting is the worker's, not one run's").isZero();

        AccountMessage got = AccountMessage.fromFrame(decoded.getFirst());
        assertThat(got.tenantId()).isEqualTo("acme");
        assertThat(got.denied()).containsExactlyInAnyOrderEntriesOf(Map.of("connect_rate", 3L, "not_allowed", 1L));
    }

    @Test
    @DisplayName("A report with nothing refused, or nothing readable, is empty rather than a failure (D-176)")
    void anEmptyReportIsEmpty() {
        assertThat(AccountMessage.fromFrame(Frame.control(FrameType.ACCOUNT, new JsonObject())).denied()).isEmpty();

        // A zero or negative count says nothing happened; it is dropped rather than reported as a refusal.
        AccountMessage padded = AccountMessage.fromFrame(Frame.control(FrameType.ACCOUNT, new JsonObject()
                .put("tenantId", "acme").put("denied", new JsonObject().put("connect_rate", 0).put("not_allowed", 2))));
        assertThat(padded.denied()).containsExactlyInAnyOrderEntriesOf(Map.of("not_allowed", 2L));
    }

    @Test
    @DisplayName("Reading a frame that is not a report is refused, not guessed at (D-176)")
    void wrongFrameTypeIsRefused() {
        assertThatThrownBy(() -> AccountMessage.fromFrame(Frame.control(FrameType.POLICY, new JsonObject())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
