package cluster93.core.auth;

/** A request that isn't allowed: 401 (who are you?) or 403 (not with this token). */
public final class AuthFailure extends RuntimeException {

    private final int status;
    private final String code;

    public AuthFailure(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public static AuthFailure unauthenticated(String message) {
        return new AuthFailure(401, "unauthorized", message);
    }

    public static AuthFailure forbidden(String message) {
        return new AuthFailure(403, "forbidden", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
