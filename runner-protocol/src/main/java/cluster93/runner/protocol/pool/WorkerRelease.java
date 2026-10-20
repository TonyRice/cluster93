package cluster93.runner.protocol.pool;

import io.vertx.core.json.JsonObject;

/**
 * One connected worker the node asked to leave (D-187), as the API and the CLI report it. The reason is the text the
 * worker sees and records; the trigger is the bounded metric label ({@code manual} or {@code automatic}).
 */
public record WorkerRelease(String workerId, String tenant, String reason, String trigger) {

    public JsonObject toJson() {
        return new JsonObject()
                .put("workerId", workerId)
                .put("tenant", tenant)
                .put("reason", reason)
                .put("trigger", trigger);
    }

    /**
     * Why a release was refused. The code is part of the API contract: the same code reaches the CLI and the caller,
     * so "the worker is busy" is not flattened into a 500.
     */
    public static final class Refused extends Exception {

        private final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** The HTTP status this refusal maps to: an unknown worker is 404, every other refusal is a state conflict. */
        public int status() {
            return "worker_unknown".equals(code) ? 404 : 409;
        }
    }
}
