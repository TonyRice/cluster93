package automately.core.audit;

import automately.core.files.s3.S3Client;
import automately.core.identity.Records;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Copies the audit log to an S3-compatible bucket (D-073), outside the cluster: new entries, oldest first, as NDJSON
 * objects under {@code <prefix>audit/YYYY/MM/DD/<first id>_<last id>.ndjson}. One node at a time does it, holding a
 * lease in a shared record that also keeps the cursor (the last entry exported). Entries newer than {@code settle} wait
 * for the next round, so one recorded a moment late on another node isn't skipped. Pruning never deletes what hasn't
 * been exported ({@link #exportedBefore}).
 */
public final class AuditExporter {

    public static final Records.Kind STATE = new Records.Kind("AUDIT_EXPORT", List.of());
    private static final String KEY = "state";
    private static final Logger LOG = LoggerFactory.getLogger(AuditExporter.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final Vertx vertx;
    private final AuditLog log;
    private final Records state;
    private final S3Client s3;
    private final String holder;
    private final Duration interval;
    private final Duration settle;

    public AuditExporter(Vertx vertx, AuditLog log, Records state, S3Client s3, String holder, Duration interval, Duration settle) {
        this.vertx = vertx;
        this.log = log;
        this.state = state;
        this.s3 = s3;
        this.holder = holder;
        this.interval = interval;
        this.settle = settle;
    }

    public void start() {
        vertx.setPeriodic(interval.toMillis(), id -> run().onFailure(err -> LOG.warn("Exporting the audit log failed: {}", err.getMessage())));
    }

    /** One round: claims the lease, then exports until it's caught up. Completes with how many entries went out. */
    public Future<Integer> run() {
        long now = System.currentTimeMillis();
        long lease = interval.multipliedBy(3).toMillis();
        return state.putIfAbsent(KEY, new JsonObject().put("cursor", (String) null).put("cursorAt", 0L).put("holder", (String) null).put("until", 0L))
                .compose(v -> state.update(KEY, json -> json.getLong("until", 0L) < now || holder.equals(json.getString("holder"))
                        ? Optional.of(json.put("holder", holder).put("until", now + lease))
                        : Optional.empty()))
                .compose(claimed -> claimed.isEmpty()
                        ? Future.succeededFuture(0)
                        : export(claimed.get().getString("cursor"), now - settle.toMillis(), lease, 0));
    }

    private Future<Integer> export(String after, long cutoff, long lease, int sent) {
        return log.scan(after, AuditLog.MAX_PAGE).compose(page -> {
            List<AuditEntry> ready = new ArrayList<>();
            for (AuditEntry entry : page) {
                if (entry.at() >= cutoff) {
                    break;
                }
                ready.add(entry);
            }
            if (ready.isEmpty()) {
                return Future.succeededFuture(sent);
            }
            Buffer body = Buffer.buffer();
            ready.forEach(entry -> body.appendString(entry.toJson().encode()).appendString("\n"));
            AuditEntry first = ready.getFirst();
            AuditEntry last = ready.getLast();
            String key = s3.settings().prefix() + "audit/" + DAY.format(Instant.ofEpochMilli(first.at())) + "/" + first.id() + "_" + last.id() + ".ndjson";
            return s3.putObject(key, body, "application/x-ndjson")
                    .compose(v -> state.update(KEY, json -> holder.equals(json.getString("holder"))
                            ? Optional.of(json.put("cursor", last.id()).put("cursorAt", last.at()).put("until", System.currentTimeMillis() + lease))
                            : Optional.empty()))
                    .compose(updated -> {
                        if (updated.isEmpty()) {
                            return Future.failedFuture("Another node took over exporting the audit log");
                        }
                        int total = sent + ready.size();
                        return ready.size() == page.size() && page.size() == AuditLog.MAX_PAGE
                                ? export(last.id(), cutoff, lease, total)
                                : Future.succeededFuture(total);
                    });
        });
    }

    /** Entries before this time have been exported, so pruning them loses nothing. */
    public Future<Long> exportedBefore() {
        return state.get(KEY).map(json -> json == null ? 0L : json.getLong("cursorAt", 0L));
    }
}
