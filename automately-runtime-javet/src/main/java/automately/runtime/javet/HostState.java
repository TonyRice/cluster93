package automately.runtime.javet;

import automately.core.runtime.StateSink;
import automately.core.state.StateException;
import com.caoccao.javet.annotations.V8Function;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

/**
 * Backs {@code ctx.step} and {@code ctx.saveCheckpoint}. Values cross as JSON strings. Each call returns a
 * small JSON envelope rather than throwing, so the prelude can raise a typed JavaScript error
 * ({@code SupersededError}, {@code StateLimitError}).
 */
public final class HostState {

    private static final String OK = "{\"ok\":true}";

    private final StateSink sink;

    HostState(StateSink sink) {
        this.sink = sink;
    }

    @V8Function(name = "saveCheckpoint")
    public String saveCheckpoint(String json) {
        return call(() -> sink.saveCheckpoint(Json.decodeValue(json)));
    }

    @V8Function(name = "completeStep")
    public String completeStep(String name, String json) {
        return call(() -> sink.completeStep(name, Json.decodeValue(json)));
    }

    private static String call(Runnable write) {
        try {
            write.run();
            return OK;
        } catch (StateException e) {
            return error(e.kind().name(), e.getMessage());
        } catch (RuntimeException e) {
            return error("ERROR", e.getMessage());
        }
    }

    private static String error(String kind, String message) {
        return new JsonObject().put("ok", false).put("kind", kind).put("message", message).encode();
    }
}
