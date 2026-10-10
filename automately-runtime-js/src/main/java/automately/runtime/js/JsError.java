package automately.runtime.js;

import automately.core.runtime.HostException;

/**
 * A JavaScript {@code Error} to create: its {@code name}, {@code code} and message (D-025). The adapter makes a
 * {@code TypeError} or {@code RangeError} for those names, else an {@code Error} whose name is set.
 */
public record JsError(String name, String code, String message) {

    public static JsError of(Throwable failure) {
        String name = "Error";
        String code = "host_error";
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (failure instanceof HostException host) {
            name = host.errorName();
            code = host.code();
        }
        return new JsError(name, code, message);
    }
}
