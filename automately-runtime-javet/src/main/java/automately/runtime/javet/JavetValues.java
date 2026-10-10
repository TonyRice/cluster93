package automately.runtime.javet;

import automately.runtime.js.Js;
import automately.runtime.js.JsError;
import automately.runtime.js.JsHandle;
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

/**
 * Converts between JavaScript values and the JSON-compatible Java values the host APIs take, through V8's own
 * {@code JSON}. Its functions are captured before the script runs, so a script replacing {@code JSON.parse} only
 * affects itself. Isolate thread only.
 */
final class JavetValues implements AutoCloseable {

    private final V8Runtime v8;
    private final V8ValueObject json;
    private final V8ValueFunction parse;
    private final V8ValueFunction stringify;

    JavetValues(V8Runtime v8) throws JavetException {
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

    /** A host function's result, or a promise's value, as JavaScript (D-126). */
    V8Value toJs(Object value) throws JavetException {
        if (value == Js.UNDEFINED) {
            return v8.createV8ValueUndefined();
        }
        if (value == null) {
            return v8.createV8ValueNull();
        }
        if (value instanceof JsHandle handle) {
            return (V8Value) handle.value();
        }
        if (value instanceof JsError error) {
            return error(error);
        }
        if (value instanceof String string) {
            return v8.createV8ValueString(string);
        }
        if (value instanceof Boolean bool) {
            return v8.createV8ValueBoolean(bool);
        }
        if (value instanceof Integer number) {
            return v8.createV8ValueInteger(number);
        }
        if (value instanceof byte[] bytes) {
            com.caoccao.javet.values.reference.V8ValueTypedArray array = v8.createV8ValueTypedArray(com.caoccao.javet.enums.V8ValueType.Uint8Array, bytes.length);
            array.fromBytes(bytes);
            return array;
        }
        return parse.call(json, Json.encode(value));
    }

    /** A JavaScript {@code Error} with the failure's name, {@code code} and message (D-025). */
    V8ValueError error(JsError failure) throws JavetException {
        V8ValueErrorType type = switch (failure.name()) {
            case "TypeError" -> V8ValueErrorType.TypeError;
            case "RangeError" -> V8ValueErrorType.RangeError;
            default -> V8ValueErrorType.Error;
        };
        V8ValueError error = v8.createV8ValueError(type, failure.message());
        if (type == V8ValueErrorType.Error && !failure.name().equals("Error")) {
            error.set("name", failure.name());
        }
        error.set("code", failure.code());
        return error;
    }

    @Override
    public void close() throws JavetException {
        parse.close();
        stringify.close();
        json.close();
    }
}
