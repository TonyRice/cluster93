package automately.runtime.javet;

import automately.core.runtime.LogSink;
import com.caoccao.javet.annotations.V8Function;

/** The only Java object a script can reach. The prelude wraps it as {@code console} and then hides it. */
public final class HostConsole {

    private final LogSink sink;

    HostConsole(LogSink sink) {
        this.sink = sink;
    }

    @V8Function(name = "log")
    public void log(String level, String line) {
        sink.log(level, line);
    }

    /** {@code log.info(message, fields)}: fields arrive as JSON, or "null". */
    @V8Function(name = "logFields")
    public void logFields(String level, String message, String fieldsJson) {
        Object fields = io.vertx.core.json.Json.decodeValue(fieldsJson);
        sink.log(level, message, fields instanceof io.vertx.core.json.JsonObject object ? object : null);
    }
}
