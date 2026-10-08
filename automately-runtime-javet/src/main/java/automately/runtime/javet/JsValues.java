package automately.runtime.javet;

import automately.core.runtime.HostException;
import com.caoccao.javet.enums.V8ValueErrorType;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueNull;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.primitive.V8ValueUndefined;
import com.caoccao.javet.values.reference.V8ValueError;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

/**
 * Converts between JavaScript values and the JSON-compatible Java values the host APIs take, through V8's own
 * {@code JSON}. Its functions are captured before the script runs, so a script replacing {@code JSON.parse} only
 * affects itself. Isolate thread only.
 */
final class JsValues implements AutoCloseable {

    /** Resolves a promise with {@code undefined} rather than {@code null}. */
    static final Object UNDEFINED = new Object();

    private final V8Runtime v8;
    private final V8ValueObject json;
    private final V8ValueFunction parse;
    private final V8ValueFunction stringify;

    JsValues(V8Runtime v8) throws JavetException {
        this.v8 = v8;
        this.json = v8.getGlobalObject().get("JSON");
        this.parse = json.get("parse");
        this.stringify = json.get("stringify");
    }

    V8Runtime runtime() {
        return v8;
    }

    /** A JSON-compatible Java value (JsonObject, JsonArray, String, Number, Boolean) or null for undefined, null and functions. */
    Object toJava(V8Value value) {
        if (value == null || value instanceof V8ValueUndefined || value instanceof V8ValueNull) {
            return null;
        }
        if (value instanceof V8ValueString string) {
            return string.getValue();
        }
        try (V8Value encoded = stringify.call(json, value)) {
            return encoded instanceof V8ValueString string ? Json.decodeValue(string.getValue()) : null;
        } catch (JavetException e) {
            // A value JSON can't encode (a cycle, a BigInt): the script's mistake, reported as one.
            throw automately.core.runtime.HostException.invalid("The value must be JSON-serialisable: " + e.getMessage());
        }
    }

    /** The value if it's a string, else null (the API reports it as missing). */
    String string(V8Value value) {
        return value instanceof V8ValueString string ? string.getValue() : null;
    }

    /** An options object; anything else counts as no options. */
    JsonObject options(V8Value value) {
        return toJava(value) instanceof JsonObject object ? object : new JsonObject();
    }

    V8Value toJs(Object value) throws JavetException {
        if (value == UNDEFINED) {
            return v8.createV8ValueUndefined();
        }
        if (value == null) {
            return v8.createV8ValueNull();
        }
        if (value instanceof String string) {
            return v8.createV8ValueString(string);
        }
        if (value instanceof byte[] bytes) {
            com.caoccao.javet.values.reference.V8ValueTypedArray array = v8.createV8ValueTypedArray(com.caoccao.javet.enums.V8ValueType.Uint8Array, bytes.length);
            array.fromBytes(bytes);
            return array;
        }
        return parse.call(json, Json.encode(value));
    }

    /** A JavaScript {@code Error} with the failure's name, {@code code} and message (D-025). */
    V8ValueError error(Throwable failure) throws JavetException {
        String name = "Error";
        String code = "host_error";
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (failure instanceof HostException host) {
            name = host.errorName();
            code = host.code();
        }
        V8ValueErrorType type = switch (name) {
            case "TypeError" -> V8ValueErrorType.TypeError;
            case "RangeError" -> V8ValueErrorType.RangeError;
            default -> V8ValueErrorType.Error;
        };
        V8ValueError error = v8.createV8ValueError(type, message);
        if (type == V8ValueErrorType.Error && !name.equals("Error")) {
            error.set("name", name);
        }
        error.set("code", code);
        return error;
    }

    @Override
    public void close() throws JavetException {
        parse.close();
        stringify.close();
        json.close();
    }
}
