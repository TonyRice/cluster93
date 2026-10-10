package automately.runtime.graaljs;

import automately.runtime.js.JsArg;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;

/** A script's argument as GraalJS holds it; null when the script passed fewer arguments (D-126). */
final class GraalJsArg implements JsArg {

    private final Value value;
    private final GraalJsValues values;

    GraalJsArg(Value value, GraalJsValues values) {
        this.value = value;
        this.values = values;
    }

    @Override
    public boolean isNullish() {
        return value == null || value.isNull();
    }

    @Override
    public String string() {
        return value != null && value.isString() ? value.asString() : null;
    }

    @Override
    public Boolean bool() {
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    @Override
    public boolean truthy() {
        return value != null && values.truthy(value);
    }

    private boolean isPlainNumber() {
        return value != null && value.isNumber() && !values.isBigInt(value);
    }

    @Override
    public Integer smallInteger() {
        return isPlainNumber() && value.fitsInInt() ? value.asInt() : null;
    }

    @Override
    public Double number() {
        return isPlainNumber() ? value.asDouble() : null;
    }

    @Override
    public Long bigint() {
        return value != null && value.isNumber() && values.isBigInt(value) && value.fitsInLong() ? value.asLong() : null;
    }

    /** A typed array's own view (its offset and length), a string as UTF-8, or anything else as its string form. */
    @Override
    public byte[] bytes() {
        if (value != null && value.isString()) {
            return value.asString().getBytes(StandardCharsets.UTF_8);
        }
        byte[] view = value == null ? null : values.viewBytes(value);
        if (view != null) {
            return view;
        }
        return (value == null ? "null" : values.string(value)).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Object json() {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return value.asString();
        }
        try {
            return values.toJava(value);
        } catch (PolyglotException e) {
            // A value JSON can't encode (a cycle, a BigInt): the script's mistake, reported as one.
            throw automately.core.runtime.HostException.invalid("The value must be JSON-serialisable: " + e.getMessage());
        }
    }
}
