package automately.core.runtime;

import automately.core.state.StateException;

/**
 * Durable writes a script makes through {@code ctx}. Calls block until the write is stored, and are only
 * made from the thread running the script, never from an event loop.
 *
 * @throws StateException when the attempt was superseded or a size limit is exceeded
 */
public interface StateSink {

    void saveCheckpoint(Object value);

    void completeStep(String name, Object result);

    /** A sink that accepts everything and stores nothing, for tests and one-off runs. */
    StateSink DISCARD = new StateSink() {
        @Override
        public void saveCheckpoint(Object value) {
        }

        @Override
        public void completeStep(String name, Object result) {
        }
    };
}
