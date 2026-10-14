package cluster93.cluster.ignite.store;

import cluster93.core.state.JobState;
import cluster93.core.state.JobStateStore;
import cluster93.core.state.StateException;
import cluster93.core.state.StepRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Checkpoints and step results per attempt (D-010), in a durable, replicated table. */
public final class IgniteJobStateStore implements JobStateStore {

    static final Table.Definition TABLE = new Table.Definition("JOB_STATE",
            Table.columns("jobId", String.class), List.of(Table.index("jobId")));

    private final Table table;

    public IgniteJobStateStore(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> Map.of("jobId", json.getString("jobId")));
    }

    static String key(String jobId, int attempt) {
        return jobId + "#" + attempt;
    }

    private static JsonObject row(String jobId, JobState state) {
        return state.toJson().put("jobId", jobId);
    }

    @Override
    public Future<JobState> load(String jobId, int attempt) {
        return table.get(key(jobId, attempt)).map(JobState::fromJson);
    }

    @Override
    public Future<JobState> resumeFrom(String jobId, int attempt) {
        return latestBefore(jobId, attempt - 1).compose(state -> table.put(key(jobId, attempt), row(jobId, state)).map(state));
    }

    private Future<JobState> latestBefore(String jobId, int attempt) {
        if (attempt < 1) {
            return Future.succeededFuture(JobState.EMPTY);
        }
        return table.get(key(jobId, attempt)).compose(json -> json != null
                ? Future.succeededFuture(JobState.fromJson(json))
                : latestBefore(jobId, attempt - 1));
    }

    @Override
    public Future<Void> delete(String jobId, int lastAttempt) {
        List<Future<JsonObject>> removed = new ArrayList<>();
        for (int attempt = 1; attempt <= lastAttempt; attempt++) {
            removed.add(table.remove(key(jobId, attempt)));
        }
        return Future.all(removed).mapEmpty();
    }

    @Override
    public Future<JobState> saveCheckpoint(String jobId, int attempt, Object value) {
        return checkValue("Checkpoint", value).compose(v -> modify(jobId, attempt, state -> state.withCheckpoint(value)));
    }

    @Override
    public Future<JobState> completeStep(String jobId, int attempt, String name, Object result, long now) {
        return checkValue("Result of step \"" + name + "\"", result)
                .compose(v -> modify(jobId, attempt, state -> state.withStep(new StepRecord(name, result, attempt, now))));
    }

    /** One writer per attempt key (the isolate running it), so read-modify-write is safe. */
    private Future<JobState> modify(String jobId, int attempt, UnaryOperator<JobState> change) {
        return table.get(key(jobId, attempt)).compose(json -> {
            JobState next = change.apply(JobState.fromJson(json));
            int size = next.encodedSize();
            if (size > MAX_TOTAL_BYTES) {
                return Future.failedFuture(new StateException(StateException.Kind.LIMIT,
                        "Job state would be " + size + " bytes; the limit is " + MAX_TOTAL_BYTES));
            }
            return table.put(key(jobId, attempt), row(jobId, next)).map(next);
        });
    }

    private static Future<Void> checkValue(String what, Object value) {
        int size = Json.encode(value).getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_VALUE_BYTES) {
            return Future.failedFuture(new StateException(StateException.Kind.LIMIT,
                    what + " is " + size + " bytes; the limit is " + MAX_VALUE_BYTES));
        }
        return Future.succeededFuture();
    }
}
