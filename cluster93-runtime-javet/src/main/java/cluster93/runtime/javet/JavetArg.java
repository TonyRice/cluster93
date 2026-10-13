package cluster93.runtime.javet;

import cluster93.runtime.js.JsArg;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueBoolean;
import com.caoccao.javet.values.primitive.V8ValueDouble;
import com.caoccao.javet.values.primitive.V8ValueInteger;
import com.caoccao.javet.values.primitive.V8ValueLong;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.reference.V8ValueArrayBuffer;
import com.caoccao.javet.values.reference.V8ValueTypedArray;

import java.nio.charset.StandardCharsets;

/** A script's argument as V8 holds it; null when the script passed fewer arguments (D-126). */
final class JavetArg implements JsArg {

    private final V8Value value;
    private final JavetValues values;

    JavetArg(V8Value value, JavetValues values) {
        this.value = value;
        this.values = values;
    }

    @Override
    public boolean isNullish() {
        return value == null || value.isNullOrUndefined();
    }

    @Override
    public String string() {
        return value instanceof V8ValueString string ? string.getValue() : null;
    }

    @Override
    public Boolean bool() {
        return value instanceof V8ValueBoolean bool ? bool.getValue() : null;
    }

    @Override
    public boolean truthy() {
        if (value == null) {
            return false;
        }
        try {
            return value.asBoolean();
        } catch (JavetException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public Integer smallInteger() {
        return value instanceof V8ValueInteger integer ? integer.getValue() : null;
    }

    @Override
    public Double number() {
        if (value instanceof V8ValueInteger integer) {
            return integer.getValue().doubleValue();
        }
        return value instanceof V8ValueDouble number ? number.getValue() : null;
    }

    @Override
    public Long bigint() {
        return value instanceof V8ValueLong big ? big.getValue() : null;
    }

    /**
     * Javet's {@code toBytes()} copies the array's whole buffer, not the view: a {@code Buffer} slice, or one whose buffer
     * was allocated bigger than it, came out with the wrong bytes. So the view's own bytes are read, from its offset for
     * its length (D-103).
     */
    @Override
    public byte[] bytes() {
        try {
            if (value instanceof V8ValueTypedArray array) {
                int offset = array.getByteOffset();
                int length = array.getByteLength();
                try (V8ValueArrayBuffer buffer = array.getBuffer()) {
                    byte[] bytes = new byte[length];
                    buffer.getByteBuffer().get(offset, bytes, 0, length);
                    return bytes;
                }
            }
        } catch (JavetException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (value instanceof V8ValueString string) {
            return string.getValue().getBytes(StandardCharsets.UTF_8);
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Object json() {
        return values.toJava(value);
    }
}
