package cluster93.core.runtime;

/**
 * A host call the script made that failed in an expected way (bad arguments, a limit, a refused
 * address). Surfaces in JavaScript as an error with this {@code name} and {@code code}.
 *
 * <p>A failure the host did <em>not</em> expect may carry what it failed with (D-181): the script still reads the same
 * name, code and message, and the class behind the failure and the host frames that classified it travel as
 * {@link #hostStack()} so a job's failure says more than "it did not work". The two are separate on purpose — the
 * message is the script's, the detail is the operator's — and an expected failure stays stackless, because a bad
 * argument is routine and walking a stack for it would cost every one of them.
 */
public final class HostException extends RuntimeException {

    /** How many host frames are carried; beyond this is host detail nobody reads. */
    private static final int HOST_FRAMES = 12;

    private final String name;
    private final String code;
    private final String hostStack;

    public HostException(String name, String code, String message) {
        this(name, code, message, null);
    }

    private HostException(String name, String code, String message, Throwable cause) {
        super(message, cause, false, false);
        this.name = name;
        this.code = code;
        this.hostStack = cause == null ? null : hostStack(cause);
    }

    /** A {@code TypeError}-style failure caused by the script's arguments. */
    public static HostException invalid(String message) {
        return new HostException("TypeError", "invalid_argument", message);
    }

    /** A size, count or rate limit. */
    public static HostException limit(String message) {
        return new HostException("RangeError", "limit_exceeded", message);
    }

    /**
     * A host call that failed for a reason the host did not expect, keeping what it failed with: the message stays the
     * script's, while the failure's class and where the host classified it go to {@link #hostStack()}.
     */
    public static HostException host(String name, String code, String message, Throwable cause) {
        return new HostException(name, code, message, cause);
    }

    public String errorName() {
        return name;
    }

    public String code() {
        return code;
    }

    /** The class behind an unexpected failure and the host frames that classified it, or null when it was expected. */
    public String hostStack() {
        return hostStack;
    }

    /**
     * The host frames a failure was classified in, then the failure's own class and message — shaped like a JavaScript
     * stack (which is where it ends up), so appending it reads as the host's part of one.
     */
    private static String hostStack(Throwable cause) {
        StringBuilder detail = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (frame.getClassName().equals(HostException.class.getName())) {
                continue; // this class is the messenger; what matters is who called it
            }
            if (shown++ == HOST_FRAMES) {
                break;
            }
            detail.append("    at ").append(frame).append('\n');
        }
        detail.append("    caused by ").append(cause.getClass().getName());
        if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
            detail.append(": ").append(cause.getMessage());
        }
        return detail.toString();
    }
}
