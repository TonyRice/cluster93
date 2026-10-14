package cluster93.runtime.graaljs;

import cluster93.runtime.js.Js;
import cluster93.runtime.js.JsError;
import cluster93.runtime.js.JsHandle;
import io.vertx.core.json.Json;
import org.graalvm.polyglot.Value;

import java.nio.ByteBuffer;

/**
 * Converts between GraalJS values and the Java values the host APIs take, through the context's helpers
 * (helpers.js), which are captured before the script runs (D-126). Run thread only.
 */
final class GraalJsValues {

    private final Value helpers;
    private final Value undefined;
    private final Value promise;
    private final Value error;
    private final Value parse;
    private final Value stringify;
    private final Value view;
    private final Value bytes;
    private final boolean isolated;
    private final Value truthy;
    private final Value string;
    private final Value isBigInt;

    /** @param isolated whether the guest is in its own isolate, so bytes go across as Latin-1 strings */
    GraalJsValues(Value helpers, boolean isolated) {
        this.helpers = helpers;
        this.isolated = isolated;
        this.undefined = helpers.getMember("undefined");
        this.promise = helpers.getMember("promise");
        this.error = helpers.getMember("error");
        this.parse = helpers.getMember("parse");
        this.stringify = helpers.getMember("stringify");
        this.view = helpers.getMember("view");
        this.bytes = helpers.getMember(isolated ? "bytesFromLatin1" : "bytes");
        this.truthy = helpers.getMember("truthy");
        this.string = helpers.getMember("string");
        this.isBigInt = helpers.getMember("isBigInt");
    }

    Value helper(String name) {
        return helpers.getMember(name);
    }

    Value undefined() {
        return undefined;
    }

    /** {@code [promise, resolve, reject]}. */
    Value newPromise() {
        return promise.execute();
    }

    boolean truthy(Value value) {
        return truthy.execute(value).asBoolean();
    }

    boolean isBigInt(Value value) {
        return isBigInt.execute(value).asBoolean();
    }

    String string(Value value) {
        return string.execute(value).asString();
    }

    /** A typed array's own bytes, or null if it isn't one. */
    byte[] viewBytes(Value value) {
        if (value.isNull() || value.isString() || value.isNumber() || value.isBoolean()) {
            return null;
        }
        Value seen = view.execute(value);
        if (seen.isNull()) {
            return null;
        }
        Value buffer = seen.getArrayElement(0);
        long offset = seen.getArrayElement(1).asLong();
        int length = seen.getArrayElement(2).asInt();
        byte[] out = new byte[length];
        if (length > 0) {
            buffer.readBuffer(offset, out, 0, length);
        }
        return out;
    }

    /** A JSON-compatible Java value, or null for what JSON leaves out (functions, undefined). */
    Object toJava(Value value) {
        Value encoded = stringify.execute(value);
        return encoded.isString() ? Json.decodeValue(encoded.asString()) : null;
    }

    /** A host function's result, or a promise's value, as JavaScript (D-126). */
    Object toJs(Object value) {
        if (value == Js.UNDEFINED) {
            return undefined;
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Value) {
            return value;
        }
        if (value instanceof JsHandle handle) {
            return handle.value();
        }
        if (value instanceof JsError failure) {
            return error(failure);
        }
        if (value instanceof byte[] data) {
            return toUint8Array(data);
        }
        if (value instanceof Double number) {
            return number;
        }
        return parse.execute(Json.encode(value));
    }

    /** Bytes as the script's own Uint8Array. */
    Value toUint8Array(byte[] data) {
        return bytes.execute(isolated ? new String(data, java.nio.charset.StandardCharsets.ISO_8859_1) : ByteBuffer.wrap(data));
    }

    Value error(JsError failure) {
        return error.execute(failure.name(), failure.code(), failure.message());
    }
}
