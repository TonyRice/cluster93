package automately.cluster.ignite.store;

import automately.core.queue.QueueStoreContract;
import org.junit.jupiter.api.BeforeEach;

/** The shared QueueStoreContract against the Ignite implementation. */
class IgniteQueueStoreTest extends QueueStoreContract {

    @BeforeEach
    void setUp() {
        store = TestIgnite.stores().queues;
        vertx = TestIgnite.vertx();
    }
}
