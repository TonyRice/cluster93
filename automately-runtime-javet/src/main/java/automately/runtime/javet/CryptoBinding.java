package automately.runtime.javet;

import automately.core.runtime.api.CryptoApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueDouble;
import com.caoccao.javet.values.primitive.V8ValueInteger;
import com.caoccao.javet.values.primitive.V8ValueLong;

/**
 * Cryptography in JavaScript (D-095): the global Web Crypto {@code crypto} and {@code node:crypto} call these. Every
 * call is synchronous and cheap; a failure comes back as an {@code Error} value for the JavaScript side to throw, as
 * {@code fs}'s synchronous calls do.
 */
public final class CryptoBinding {

    private final JsValues js;
    private final CryptoApi crypto;

    CryptoBinding(HostBridge bridge, CryptoApi crypto) {
        this.js = bridge.values();
        this.crypto = crypto;
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

    private static int integer(V8Value value) {
        if (value instanceof V8ValueInteger i) {
            return i.getValue();
        }
        if (value instanceof V8ValueLong l && l.getValue() >= Integer.MIN_VALUE && l.getValue() <= Integer.MAX_VALUE) {
            return l.getValue().intValue();
        }
        if (value instanceof V8ValueDouble d && d.getValue() == Math.rint(d.getValue()) && Math.abs(d.getValue()) <= Integer.MAX_VALUE) {
            return d.getValue().intValue();
        }
        throw automately.core.runtime.HostException.invalid("Expected an integer");
    }

    @V8Function(name = "randomBytes")
    public V8Value randomBytes(V8Value length) throws JavetException {
        return answer(() -> crypto.randomBytes(integer(length)));
    }

    @V8Function(name = "randomUUID")
    public V8Value randomUUID() throws JavetException {
        return answer(crypto::randomUUID);
    }

    @V8Function(name = "digest")
    public V8Value digest(V8Value algorithm, V8Value data) throws JavetException {
        return answer(() -> crypto.digest(js.string(algorithm), HostBridge.bytes(data)));
    }

    @V8Function(name = "open")
    public V8Value open(V8Value algorithm, V8Value hmacKey) throws JavetException {
        return answer(() -> crypto.open(js.string(algorithm), hmacKey == null || hmacKey.isNullOrUndefined() ? null : HostBridge.bytes(hmacKey)));
    }

    @V8Function(name = "update")
    public V8Value update(V8Value handle, V8Value data) throws JavetException {
        return answer(() -> {
            crypto.update(integer(handle), HostBridge.bytes(data));
            return null;
        });
    }

    @V8Function(name = "finish")
    public V8Value finish(V8Value handle) throws JavetException {
        return answer(() -> crypto.finish(integer(handle)));
    }

    @V8Function(name = "copy")
    public V8Value copy(V8Value handle) throws JavetException {
        return answer(() -> crypto.copy(integer(handle)));
    }

    @V8Function(name = "timingSafeEqual")
    public V8Value timingSafeEqual(V8Value a, V8Value b) throws JavetException {
        return answer(() -> crypto.timingSafeEqual(HostBridge.bytes(a), HostBridge.bytes(b)));
    }

    @V8Function(name = "hashes")
    public V8Value hashes() throws JavetException {
        return answer(crypto::hashes);
    }
}
