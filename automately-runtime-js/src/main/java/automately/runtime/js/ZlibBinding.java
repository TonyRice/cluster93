package automately.runtime.js;

import automately.core.runtime.api.ZlibApi;

/**
 * Compression in JavaScript (D-103): {@code node:zlib} calls these. Each call is synchronous and its output capped; a
 * failure comes back as an {@code Error} value for the JavaScript side to throw, as {@link CryptoBinding}'s do.
 */
public final class ZlibBinding {

    private final ZlibApi zlib;

    ZlibBinding(HostBridge bridge, ZlibApi zlib) {
        this.zlib = zlib;
    }

    @FunctionalInterface
    private interface Call {
        Object run() throws Exception;
    }

    private Object answer(Call call) {
        try {
            return call.run();
        } catch (RuntimeException e) {
            return JsError.of(e);
        } catch (Exception e) {
            return JsError.of(new IllegalStateException(e.getMessage(), e));
        }
    }

    private static int handle(JsArg value) {
        Integer small = value.smallInteger();
        if (small != null) {
            return small;
        }
        Double number = value.number();
        if (number != null) {
            return number.intValue();
        }
        throw automately.core.runtime.HostException.invalid("Expected a stream handle");
    }

    @HostFunction(name = "process")
    public Object process(JsArg kind, JsArg data, JsArg options) throws JsEngineException {
        return answer(() -> zlib.process(kind.string(), data.bytes(), options.options()));
    }

    @HostFunction(name = "open")
    public Object open(JsArg kind, JsArg options) throws JsEngineException {
        return answer(() -> zlib.open(kind.string(), options.options()));
    }

    @HostFunction(name = "write")
    public Object write(JsArg handle, JsArg data) throws JsEngineException {
        return answer(() -> zlib.write(handle(handle), data.bytes()));
    }

    @HostFunction(name = "flush")
    public Object flush(JsArg handle, JsArg kind) throws JsEngineException {
        return answer(() -> zlib.flush(handle(handle), kind.smallInteger() != null ? kind.smallInteger() : 2));
    }

    @HostFunction(name = "finish")
    public Object finish(JsArg handle) throws JsEngineException {
        return answer(() -> zlib.finish(handle(handle)));
    }

    @HostFunction(name = "close")
    public Object close(JsArg handle) throws JsEngineException {
        return answer(() -> {
            zlib.close(handle(handle));
            return null;
        });
    }
}
