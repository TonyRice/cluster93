package cluster93.runtime.js;

/** A promise the run waits on: the module's evaluation, or the default export's result (D-126). */
public interface JsPromiseView extends AutoCloseable {

    boolean isPending() throws JsEngineException;

    boolean isRejected() throws JsEngineException;

    /** The fulfilled value, as a string (the entry's JSON envelope). */
    String resultString() throws JsEngineException;

    /** The rejection as the script would print it: its stack if it has one, else its text. */
    String rejectionText() throws JsEngineException;

    @Override
    void close();
}
