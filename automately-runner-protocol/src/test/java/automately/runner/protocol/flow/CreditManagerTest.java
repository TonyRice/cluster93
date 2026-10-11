package automately.runner.protocol.flow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditManagerTest {

    @Test
    void managesStreamAndConnectionCreditWindows() {
        CreditManager flow = new CreditManager(256 * 1024, 1024 * 1024);
        int streamId = 10;
        flow.openStream(streamId);

        assertThat(flow.hasStream(streamId)).isTrue();
        assertThat(flow.streamCredits(streamId)).isEqualTo(256 * 1024);
        assertThat(flow.connectionCredits()).isEqualTo(1024 * 1024);

        // Can send 64 KiB
        assertThat(flow.canSend(streamId, 64 * 1024)).isTrue();
        assertThat(flow.tryConsume(streamId, 64 * 1024)).isTrue();

        assertThat(flow.streamCredits(streamId)).isEqualTo(192 * 1024);
        assertThat(flow.connectionCredits()).isEqualTo(960 * 1024);

        // Adding credits back
        flow.addStreamCredits(streamId, 64 * 1024);
        flow.addConnectionCredits(64 * 1024);

        assertThat(flow.streamCredits(streamId)).isEqualTo(256 * 1024);
        assertThat(flow.connectionCredits()).isEqualTo(1024 * 1024);
    }

    @Test
    void pausesWhenStreamWindowDepleted() {
        CreditManager flow = new CreditManager(100, 1000);
        int streamId = 1;
        flow.openStream(streamId);

        assertThat(flow.tryConsume(streamId, 80)).isTrue();
        assertThat(flow.canSend(streamId, 30)).isFalse();
        assertThat(flow.tryConsume(streamId, 30)).isFalse();

        // Replenish stream
        flow.addStreamCredits(streamId, 50);
        assertThat(flow.canSend(streamId, 30)).isTrue();
        assertThat(flow.tryConsume(streamId, 30)).isTrue();
    }

    @Test
    void pausesWhenConnectionWindowDepleted() {
        CreditManager flow = new CreditManager(1000, 100);
        int streamId = 1;
        flow.openStream(streamId);

        assertThat(flow.tryConsume(streamId, 80)).isTrue();
        assertThat(flow.canSend(streamId, 50)).isFalse();

        // Replenish connection
        flow.addConnectionCredits(50);
        assertThat(flow.canSend(streamId, 50)).isTrue();
        assertThat(flow.tryConsume(streamId, 50)).isTrue();
    }
}
