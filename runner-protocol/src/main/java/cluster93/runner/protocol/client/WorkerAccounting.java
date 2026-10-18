package cluster93.runner.protocol.client;

import cluster93.core.host.egress.AccountingSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The worker's accounting (D-173): what a gate in a worker counts instead of a node's metrics. For now a tally of
 * refusals by reason — the same distinction the node's {@code egress_denied} counter makes — with the budget a worker
 * enforces against the node's allowance to come (plan A6).
 *
 * <p>It matters that refusals are visible here at all: a worker that decides locally is otherwise silent about what
 * it turned away, and "the worker says no" has to be as auditable as "the node says no".
 */
public final class WorkerAccounting implements AccountingSink {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerAccounting.class);

    private final Map<String, AtomicLong> denials = new ConcurrentHashMap<>();
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

    /** How many connections this worker refused, however they were refused. */
    public long denied() {
        long total = 0;
        for (AtomicLong count : denials.values()) {
            total += count.get();
        }
        return total;
    }
}
