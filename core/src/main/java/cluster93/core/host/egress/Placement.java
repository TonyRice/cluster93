package cluster93.core.host.egress;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where one tenant's outbound connections are decided and made (D-173 item 8): in the **worker**, which resolves,
 * checks against the rules the node synced to it and connects itself, or on the **node**, which makes the connection
 * on the worker's behalf.
 *
 * <p>It is a placement rather than a second implementation — the same {@link Decision} runs either way — and it is set
 * twice over: for the node with {@code C93_EGRESS_PLACEMENT} (the worker, by default), and for one tenant at a time in
 * that tenant's egress policy. A tenant that names none follows the node's, so the usual case is one setting for the
 * whole node and an override where a tenant needs the other one (a DNS-gated tenant with no resolver in its container,
 * or a workload resolving thousands of distinct hosts).
 *
 * <p>The placement travels to a worker in the policy it is handed when it is bound (D-172), so it can change when a
 * worker is re-bound to another tenant without anything restarting.
 */
public enum Placement {
    /** The worker decides and connects: the default (D-173). */
    WORKER("worker"),
    /** The node decides and connects for it: the shape every connection had before D-173. */
    NODE("node");

    public final String code;

    /** What an operator may write, for a message that lists them. */
    public static final List<String> CODES = Arrays.stream(values()).map(placement -> placement.code).toList();

    Placement(String code) {
        this.code = code;
    }

    /** The placement named, or null for {@code null}, the empty string or {@code "inherit"} — the node's own default. */
    public static Placement parseOrInherit(Object value) {
        if (value == null) {
            return null;
        }
        String code = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (code.isEmpty() || "inherit".equals(code)) {
            return null;
        }
        for (Placement placement : values()) {
            if (placement.code.equals(code)) {
                return placement;
            }
        }
        throw new IllegalArgumentException("\"placement\" is " + String.join(" or ", CODES)
                + ", or \"inherit\" for the node's");
    }

    /** The placement named, with no inherit: {@code "inherit"} is refused rather than quietly defaulted. */
    public static Placement parse(Object value) {
        Placement placement = parseOrInherit(value);
        if (placement == null) {
            throw new IllegalArgumentException("\"placement\" is " + String.join(" or ", CODES));
        }
        return placement;
    }
}
