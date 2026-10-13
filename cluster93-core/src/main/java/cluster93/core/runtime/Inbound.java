package cluster93.core.runtime;

/**
 * Something delivered to a script's listener: a bus message, a bus request, or an HTTP request. If
 * the listener can answer, exactly one of {@link #reply} or {@link #fail} is called (from any thread).
 */
public interface Inbound {

    /** JSON-compatible payload handed to the script. */
    Object payload();

    /** Whether the sender waits for an answer. */
    boolean expectsReply();

    void reply(Object value);

    void fail(int code, String message);
}
