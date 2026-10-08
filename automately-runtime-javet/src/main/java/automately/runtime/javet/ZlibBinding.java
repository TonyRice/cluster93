package automately.runtime.javet;

import automately.core.runtime.api.ZlibApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueDouble;
import com.caoccao.javet.values.primitive.V8ValueInteger;

/**
 * Compression in JavaScript (D-103): {@code node:zlib} calls these. Each call is synchronous and its output capped; a
 * failure comes back as an {@code Error} value for the JavaScript side to throw, as {@link CryptoBinding}'s do.
 */
public final class ZlibBinding {

    private final JsValues js;
    private final ZlibApi zlib;

    ZlibBinding(HostBridge bridge, ZlibApi zlib) {
        this.js = bridge.values();
        this.zlib = zlib;
    }

    @FunctionalInterface
    private interface Call {
        Object run() throws Exception;
    }

    private V8Value answer(Call call) throws JavetException {
        try {
            return js.toJs(call.run());
        } catch (RuntimeException e) {
            return js.error(e);
        } catch (Exception e) {
            return js.error(new IllegalStateException(e.getMessage(), e));
        }
    }

    private static int handle(V8Value value) {
        if (value instanceof V8ValueInteger i) {
            return i.getValue();
        }
        if (value instanceof V8ValueDouble d) {
            return d.getValue().intValue();
        }
        throw automately.core.runtime.HostException.invalid("Expected a stream handle");
    }

    @V8Function(name = "process")
    public V8Value process(V8Value kind, V8Value data, V8Value options) throws JavetException {
        return answer(() -> zlib.process(js.string(kind), HostBridge.bytes(data), js.options(options)));
    }

    @V8Function(name = "open")
    public V8Value open(V8Value kind, V8Value options) throws JavetException {
        return answer(() -> zlib.open(js.string(kind), js.options(options)));
    }

    @V8Function(name = "write")
    public V8Value write(V8Value handle, V8Value data) throws JavetException {
        return answer(() -> zlib.write(handle(handle), HostBridge.bytes(data)));
    }

    @V8Function(name = "finish")
    public V8Value finish(V8Value handle) throws JavetException {
        return answer(() -> zlib.finish(handle(handle)));
    }

    @V8Function(name = "close")
    public V8Value close(V8Value handle) throws JavetException {
        return answer(() -> {
            zlib.close(handle(handle));
            return null;
        });
    }
}
