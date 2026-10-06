package automately.core.state;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/**
 * Job state in a Vert.x {@link AsyncMap}, keyed {@code jobId#attempt}. Each key has a single writer (the
 * isolate running that attempt, one call at a time), so a read-modify-write is safe here.
 */
public final class SharedDataJobStateStore implements JobStateStore {

    static final String MAP_NAME = "automately.job-state";

    private final Future<AsyncMap<String, JsonObject>> map;

    public SharedDataJobStateStore(Vertx vertx) {
        this.map = vertx.sharedData().getAsyncMap(MAP_NAME);
    }

    static String key(String jobId, int attempt) {
        return jobId + "#" + attempt;
    }

    @Override
    public Future<JobState> load(String jobId, int attempt) {
        return map.compose(m -> m.get(key(jobId, attempt))).map(JobState::fromJson);
    }

    @Override
    public Future<JobState> resumeFrom(String jobId, int attempt) {
        return map.compose(m -> latestBefore(m, jobId, attempt - 1)
                .compose(state -> m.put(key(jobId, attempt), state.toJson()).map(state)));
    }

    private Future<JobState> latestBefore(AsyncMap<String, JsonObject> m, String jobId, int attempt) {
        if (attempt < 1) {
            return Future.succeededFuture(JobState.EMPTY);
        }
        return m.get(key(jobId, attempt)).compose(json -> json != null
                ? Future.succeededFuture(JobState.fromJson(json))
                : latestBefore(m, jobId, attempt - 1));
    }

    @Override
    public Future<JobState> saveCheckpoint(String jobId, int attempt, Object value) {
        return checkValue("Checkpoint", value)
                .compose(v -> modify(jobId, attempt, state -> state.withCheckpoint(value)));
    }

    @Override
    public Future<JobState> completeStep(String jobId, int attempt, String name, Object result, long now) {
        return checkValue("Result of step \"" + name + "\"", result)
                .compose(v -> modify(jobId, attempt, state -> state.withStep(new StepRecord(name, result, attempt, now))));
    }

    private Future<JobState> modify(String jobId, int attempt, UnaryOperator<JobState> change) {
        String key = key(jobId, attempt);
        return map.compose(m -> m.get(key).compose(json -> {
            JobState next = change.apply(JobState.fromJson(json));
            int size = next.encodedSize();
            if (size > MAX_TOTAL_BYTES) {
                return Future.failedFuture(new StateException(StateException.Kind.LIMIT,
                        "Job state would be " + size + " bytes; the limit is " + MAX_TOTAL_BYTES));
            }
            return m.put(key, next.toJson()).map(next);
        }));
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
