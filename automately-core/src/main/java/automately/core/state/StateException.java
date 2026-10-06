package automately.core.state;

/** A state write a script asked for that cannot be honoured. Surfaces in JavaScript as a typed error. */
public final class StateException extends RuntimeException {

    public enum Kind {
        /** This attempt is no longer the job's current attempt; its work no longer counts. */
        SUPERSEDED,
        /** A value or the job's total state is over the size limit. */
        LIMIT
    }

    private final Kind kind;

    public StateException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
