package automately.runtime.js;

/**
 * The prelude's functions the event loop calls into (js/entry.js returns them). Implemented by each engine adapter; only
 * used on the run's thread (D-126).
 */
public interface EntryPoints extends AutoCloseable {

    /** Ends the job, or tells process listeners, for each rejection still unhandled after this turn of the loop. */
    void checkRejections() throws JsEngineException;

    void fireTimer(int id) throws JsEngineException;

    /** Begins a check phase: how many immediates it runs (those queued before it). */
    int startImmediates() throws JsEngineException;

    void runImmediate() throws JsEngineException;

    void requestStop(String reason) throws JsEngineException;

    /** Hands an inbound event to listener {@code listenerId}; {@code replyId} is 0 if no answer is expected. */
    void deliver(int listenerId, String payloadJson, int replyId) throws JsEngineException;

    /** An HTTP request for serve listener {@code listenerId}; its body arrives on stream {@code bodyId}. */
    void request(int listenerId, int exchangeId, String requestJson, int bodyId) throws JsEngineException;

    /** The next chunk of stream {@code streamId} as a {@code Uint8Array}, or the end of the stream if null. */
    void chunk(int streamId, byte[] bytes) throws JsEngineException;

    void streamError(int streamId, String message) throws JsEngineException;

    @Override
    void close();
}
