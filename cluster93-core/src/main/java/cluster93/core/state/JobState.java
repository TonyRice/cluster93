package cluster93.core.state;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What one attempt of a job has recorded: its last checkpoint and its completed steps, in the order
 * they completed. Immutable.
 */
public record JobState(Object checkpoint, List<StepRecord> steps) {

    public static final JobState EMPTY = new JobState(null, List.of());

    public JobState {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public Optional<StepRecord> step(String name) {
        return steps.stream().filter(step -> step.name().equals(name)).findFirst();
    }

    public JobState withCheckpoint(Object value) {
        return new JobState(value, steps);
    }

    public JobState withStep(StepRecord step) {
        List<StepRecord> next = new ArrayList<>(steps);
        next.removeIf(existing -> existing.name().equals(step.name()));
        next.add(step);
        return new JobState(checkpoint, next);
    }

    /** Size of the encoded state in bytes (UTF-8), which is what the limits apply to. */
    public int encodedSize() {
        return toJson().encode().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("checkpoint", checkpoint)
                .put("steps", new JsonArray(steps.stream().map(StepRecord::toJson).toList()));
    }

    public static JobState fromJson(JsonObject json) {
        if (json == null) {
            return EMPTY;
        }
        return new JobState(
                json.getValue("checkpoint"),
                json.getJsonArray("steps", new JsonArray()).stream().map(s -> StepRecord.fromJson((JsonObject) s)).toList());
    }
}
