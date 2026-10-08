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

    /** Longest a synchronous key derivation or key generation waits for the crypto pool (D-101). */
    static final java.time.Duration SYNC_TIMEOUT = java.time.Duration.ofSeconds(60);

    private final HostBridge bridge;
    private final JsValues js;
    private final CryptoApi crypto;

    CryptoBinding(HostBridge bridge, CryptoApi crypto) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.crypto = crypto;
    }

    /** Blocks the isolate's thread for a pool job, as fs's sync calls block for the host (D-079). */
    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        try {
            return future.toCompletionStage().toCompletableFuture().get(SYNC_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw automately.core.runtime.HostException.limit("The node's crypto pool didn't get to this within " + SYNC_TIMEOUT.toSeconds() + " s");
        }
    }

    private static byte[] optionalBytes(V8Value value) throws JavetException {
        return value == null || value.isNullOrUndefined() ? null : HostBridge.bytes(value);
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

    // --- key derivation (D-101) ---

    @V8Function(name = "pbkdf2")
    public V8Value pbkdf2(V8Value password, V8Value salt, V8Value iterations, V8Value length, V8Value digest, V8Value async) throws JavetException {
        byte[] p = HostBridge.bytes(password);
        byte[] s = HostBridge.bytes(salt);
        String d = js.string(digest);
        if (async != null && async.asBoolean()) {
            return bridge.promise(() -> crypto.pbkdf2(p, s, integer(iterations), integer(length), d));
        }
        return answer(() -> await(crypto.pbkdf2(p, s, integer(iterations), integer(length), d)));
    }

    @V8Function(name = "hkdf")
    public V8Value hkdf(V8Value digest, V8Value key, V8Value salt, V8Value info, V8Value length) throws JavetException {
        return answer(() -> crypto.hkdf(js.string(digest), HostBridge.bytes(key), HostBridge.bytes(salt), HostBridge.bytes(info), integer(length)));
    }

    @V8Function(name = "scrypt")
    public V8Value scrypt(V8Value password, V8Value salt, V8Value length, V8Value options, V8Value async) throws JavetException {
        byte[] p = HostBridge.bytes(password);
        byte[] s = HostBridge.bytes(salt);
        io.vertx.core.json.JsonObject o = js.options(options);
        if (async != null && async.asBoolean()) {
            return bridge.promise(() -> crypto.scrypt(p, s, integer(length), o));
        }
        return answer(() -> await(crypto.scrypt(p, s, integer(length), o)));
    }

    // --- ciphers (D-101) ---

    @V8Function(name = "cipher")
    public V8Value cipher(V8Value algorithm, V8Value encrypt, V8Value key, V8Value iv, V8Value authTagLength) throws JavetException {
        return answer(() -> crypto.cipher(js.string(algorithm), encrypt.asBoolean(), HostBridge.bytes(key), optionalBytes(iv),
                authTagLength == null || authTagLength.isNullOrUndefined() ? 0 : integer(authTagLength)));
    }

    @V8Function(name = "cipherPadding")
    public V8Value cipherPadding(V8Value handle, V8Value on) throws JavetException {
        return answer(() -> {
            crypto.cipherPadding(integer(handle), on.asBoolean());
            return null;
        });
    }

    @V8Function(name = "cipherAad")
    public V8Value cipherAad(V8Value handle, V8Value aad) throws JavetException {
        return answer(() -> {
            crypto.cipherAad(integer(handle), HostBridge.bytes(aad));
            return null;
        });
    }

    @V8Function(name = "cipherUpdate")
    public V8Value cipherUpdate(V8Value handle, V8Value data) throws JavetException {
        return answer(() -> crypto.cipherUpdate(integer(handle), HostBridge.bytes(data)));
    }

    @V8Function(name = "cipherFinal")
    public V8Value cipherFinal(V8Value handle, V8Value tag) throws JavetException {
        return answer(() -> crypto.cipherFinal(integer(handle), optionalBytes(tag)));
    }

    @V8Function(name = "ciphers")
    public V8Value ciphers() throws JavetException {
        return answer(crypto::ciphers);
    }

    // --- keys and signatures (D-101) ---

    @V8Function(name = "readKey")
    public V8Value readKey(V8Value wantPrivate, V8Value data, V8Value format, V8Value type) throws JavetException {
        return answer(() -> crypto.readKey(wantPrivate.asBoolean(), HostBridge.bytes(data), js.string(format), js.string(type)));
    }

    @V8Function(name = "publicKeyOf")
    public V8Value publicKeyOf(V8Value pkcs8) throws JavetException {
        return answer(() -> crypto.publicKeyOf(HostBridge.bytes(pkcs8)));
    }

    @V8Function(name = "exportKey")
    public V8Value exportKey(V8Value isPrivate, V8Value der, V8Value format, V8Value type) throws JavetException {
        return answer(() -> crypto.exportKey(isPrivate.asBoolean(), HostBridge.bytes(der), js.string(format), js.string(type)));
    }

    @V8Function(name = "generateKeyPair")
    public V8Value generateKeyPair(V8Value type, V8Value options, V8Value async) throws JavetException {
        String t = js.string(type);
        io.vertx.core.json.JsonObject o = js.options(options);
        if (async != null && async.asBoolean()) {
            return bridge.promise(() -> crypto.generateKeyPair(t, o));
        }
        return answer(() -> await(crypto.generateKeyPair(t, o)));
    }

    @V8Function(name = "sign")
    public V8Value sign(V8Value digest, V8Value data, V8Value pkcs8, V8Value options) throws JavetException {
        return answer(() -> crypto.sign(js.string(digest), HostBridge.bytes(data), HostBridge.bytes(pkcs8), js.options(options)));
    }

    @V8Function(name = "verify")
    public V8Value verify(V8Value digest, V8Value data, V8Value der, V8Value isPrivate, V8Value signature, V8Value options) throws JavetException {
        return answer(() -> crypto.verify(js.string(digest), HostBridge.bytes(data), HostBridge.bytes(der), isPrivate.asBoolean(),
                HostBridge.bytes(signature), js.options(options)));
    }

    @V8Function(name = "curves")
    public V8Value curves() throws JavetException {
        return answer(crypto::curves);
    }
}
