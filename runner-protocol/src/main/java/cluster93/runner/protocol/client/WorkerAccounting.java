package cluster93.runner.protocol.client;

import cluster93.core.host.egress.AccountingSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The worker's accounting (D-173, D-176): what a gate in a worker counts instead of a node's metrics. A tally of
 * refusals by reason — the same distinction the node's {@code egress_denied} counter makes — and what the worker
 * enforces the node's allowance against (plan A6).
 *
 * <p>It matters that refusals are visible here at all: a worker that decides locally is otherwise silent about what
 * it turned away, and "the worker says no" has to be as auditable as "the node says no". The node cannot read this
 * tally, so the worker sends it ({@link #drainDenials()} is what is new since the last report).
 */
public final class WorkerAccounting implements AccountingSink {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerAccounting.class);

    private final Map<String, AtomicLong> denials = new ConcurrentHashMap<>();
    /** What the node has been told already, per reason, so a report carries only what it has not. */
    private final Map<String, Long> reported = new java.util.HashMap<>();
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong openedTls = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();

    @Override
    public void denied(String tenant, String reason) {
        denials.computeIfAbsent(reason == null ? "unknown" : reason, key -> new AtomicLong()).incrementAndGet();
        LOG.debug("Refused a connection for {}: {}", tenant == null ? "-" : tenant, reason);
    }

    @Override
    public void opened(String tenant, boolean tls) {
        opened.incrementAndGet();
        if (tls) {
            openedTls.incrementAndGet();
        }
    }

    @Override
    public void bytes(String tenant, String direction, long length) {
        ("out".equals(direction) ? bytesOut : bytesIn).addAndGet(length);
    }

    /** Bytes this worker pulled in on its own connections. */
    public long bytesIn() {
        return bytesIn.get();
    }

    /** Bytes it pushed out on them. */
    public long bytesOut() {
        return bytesOut.get();
    }

    /** How many connections this worker opened itself, of which how many were TLS. */
    public long opened() {
        return opened.get();
    }

    /** How many of the connections this worker opened were TLS. */
    public long openedTls() {
        return openedTls.get();
    }

    /** How many connections this worker refused, by reason; for its own reporting and for tests. */
    public Map<String, Long> denials() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        denials.forEach((reason, count) -> counts.put(reason, count.get()));
        return Map.copyOf(counts);
    }

    /**
     * The refusals counted since this was last called, by reason, and nothing if there were none: what the node has
     * not been told yet (D-176). The tally itself is untouched — this advances a watermark, it does not consume what
     * {@link #denials()} reports — so a report that never leaves the worker loses visibility, never the count.
     */
    public synchronized Map<String, Long> drainDenials() {
        Map<String, Long> deltas = new java.util.TreeMap<>();
        denials.forEach((reason, count) -> {
            long total = count.get();
            long seen = reported.getOrDefault(reason, 0L);
            if (total > seen) {
                deltas.put(reason, total - seen);
                reported.put(reason, total);
            }
        });
        return Map.copyOf(deltas);
    }

    /** How many connections this worker refused, however they were refused. */
    public long denied() {
        long total = 0;
        for (AtomicLong count : denials.values()) {
            total += count.get();
        }
        return total;
    }
}
