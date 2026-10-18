package cluster93.runner.protocol.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's tally of what it refused (D-173, D-176), and the watermark the report uses to send only what the node
 * has not been told. The tally itself must survive a report: losing visibility must never lose the count.
 */
class WorkerAccountingTest {

    @Test
    @DisplayName("A report carries only what is new, and reporting does not clear the tally (D-176)")
    void drainsOnlyWhatIsNew() {
        WorkerAccounting accounting = new WorkerAccounting();
        accounting.denied("acme", "connect_rate");
        accounting.denied("acme", "connect_rate");
        accounting.denied("acme", "not_allowed");

        assertThat(accounting.drainDenials()).as("what the node has not been told")
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("connect_rate", 2L, "not_allowed", 1L));
        assertThat(accounting.denials()).as("the tally is the worker's own record, and a report is not a read")
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("connect_rate", 2L, "not_allowed", 1L));
        assertThat(accounting.drainDenials()).as("nothing new to say").isEmpty();

        accounting.denied("acme", "connect_rate");
        assertThat(accounting.drainDenials()).as("only the increment since the last report")
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("connect_rate", 1L));
        assertThat(accounting.denials()).containsEntry("connect_rate", 3L);
    }

    @Test
    @DisplayName("A refusal with no reason is still counted, under its own name (D-173)")
    void anUnnamedReasonIsStillCounted() {
        WorkerAccounting accounting = new WorkerAccounting();

        accounting.denied("acme", null);

        assertThat(accounting.denials()).containsEntry("unknown", 1L);
        assertThat(accounting.denied()).as("however they were refused").isEqualTo(1L);
    }

    @Test
    @DisplayName("Connections and bytes are counted as the gate opens and moves them (D-173)")
    void countsConnectionsAndBytes() {
        WorkerAccounting accounting = new WorkerAccounting();

        accounting.opened("acme", false);
        accounting.opened("acme", true);
        accounting.bytes("acme", "out", 100);
        accounting.bytes("acme", "in", 250);

        assertThat(accounting.opened()).isEqualTo(2L);
        assertThat(accounting.openedTls()).isEqualTo(1L);
        assertThat(accounting.bytesOut()).isEqualTo(100L);
        assertThat(accounting.bytesIn()).isEqualTo(250L);
    }
}
