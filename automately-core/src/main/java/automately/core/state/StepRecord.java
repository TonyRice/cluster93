package automately.core.state;

import io.vertx.core.json.JsonObject;

/** A completed {@code ctx.step}: its stored result and which attempt completed it. */
public record StepRecord(String name, Object result, int attempt, long completedAt) {

    public JsonObject toJson() {
        return new JsonObject().put("name", name).put("result", result).put("attempt", attempt).put("completedAt", completedAt);
    }

    public static StepRecord fromJson(JsonObject json) {
        return new StepRecord(json.getString("name"), json.getValue("result"), json.getInteger("attempt"), json.getLong("completedAt"));
    }
}
