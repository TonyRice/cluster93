package automately.core.runtime;

/**
 * A host call the script made that failed in an expected way (bad arguments, a limit, a refused
 * address). Surfaces in JavaScript as an error with this {@code name} and {@code code}.
 */
public final class HostException extends RuntimeException {

    private final String name;
    private final String code;

    public HostException(String name, String code, String message) {
        super(message, null, false, false);
        this.name = name;
        this.code = code;
    }

    /** A {@code TypeError}-style failure caused by the script's arguments. */
    public static HostException invalid(String message) {
        return new HostException("TypeError", "invalid_argument", message);
    }

    /** A size, count or rate limit. */
    public static HostException limit(String message) {
        return new HostException("RangeError", "limit_exceeded", message);
    }

    public String errorName() {
        return name;
    }

    public String code() {
        return code;
    }
}
