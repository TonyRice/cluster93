package cluster93.runtime.js;

/** A promise the host settles later, on the run's thread (D-126). */
public interface JsPromiseResolver extends AutoCloseable {

    /** The promise, to hand to the script. */
    JsHandle promise() throws JsEngineException;

    /** Resolves with a Java value, converted as a {@link HostFunction}'s result is. */
    void resolve(Object value) throws JsEngineException;

    void reject(JsError error) throws JsEngineException;

    @Override
    void close();
}
