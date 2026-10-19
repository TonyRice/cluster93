package cluster93.runtime.js;

import cluster93.core.runtime.HostException;

/**
 * A JavaScript {@code Error} to create: its {@code name}, {@code code} and message (D-025). The adapter makes a
 * {@code TypeError} or {@code RangeError} for those names, else an {@code Error} whose name is set.
 *
 * <p>{@code hostStack} is what the host failed with and where — the class behind an unexpected failure and the host
 * frames that classified it (D-181). It is not part of the message: the adapter appends it to the error's stack, so a
 * job's failure names the host's part of it without changing what the script reads.
 */
public record JsError(String name, String code, String message, String hostStack) {

    public static JsError of(Throwable failure) {
        String name = "Error";
        String code = "host_error";
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        String hostStack = null;
        if (failure instanceof HostException host) {
            name = host.errorName();
            code = host.code();
            hostStack = host.hostStack();
        }
        return new JsError(name, code, message, hostStack);
    }
}
