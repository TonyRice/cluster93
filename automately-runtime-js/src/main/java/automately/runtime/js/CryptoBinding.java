package automately.runtime.js;

import automately.core.runtime.api.CryptoApi;

/**
 * Cryptography in JavaScript (D-095): the global Web Crypto {@code crypto} and {@code node:crypto} call these. Every
 * call is synchronous and cheap; a failure comes back as an {@code Error} value for the JavaScript side to throw, as
 * {@code fs}'s synchronous calls do.
 */
public final class CryptoBinding {

    /** Longest a synchronous key derivation or key generation waits for the crypto pool (D-101). */
    static final java.time.Duration SYNC_TIMEOUT = java.time.Duration.ofSeconds(60);

    private final HostBridge bridge;
    private final CryptoApi crypto;

    CryptoBinding(HostBridge bridge, CryptoApi crypto) {
        this.bridge = bridge;
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

    private static byte[] optionalBytes(JsArg value) {
        return value == null || value.isNullish() ? null : value.bytes();
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

    private static int integer(JsArg value) {
        return Js.integer(value);
    }

    @HostFunction(name = "randomBytes")
    public Object randomBytes(JsArg length) throws JsEngineException {
        return answer(() -> crypto.randomBytes(integer(length)));
    }

    @HostFunction(name = "randomUUID")
    public Object randomUUID() throws JsEngineException {
        return answer(crypto::randomUUID);
    }

    @HostFunction(name = "digest")
    public Object digest(JsArg algorithm, JsArg data) throws JsEngineException {
        return answer(() -> crypto.digest(algorithm.string(), data.bytes()));
    }

    @HostFunction(name = "open")
    public Object open(JsArg algorithm, JsArg hmacKey) throws JsEngineException {
        return answer(() -> crypto.open(algorithm.string(), hmacKey == null || hmacKey.isNullish() ? null : hmacKey.bytes()));
    }

    @HostFunction(name = "update")
    public Object update(JsArg handle, JsArg data) throws JsEngineException {
        return answer(() -> {
            crypto.update(integer(handle), data.bytes());
            return null;
        });
    }

    @HostFunction(name = "finish")
    public Object finish(JsArg handle) throws JsEngineException {
        return answer(() -> crypto.finish(integer(handle)));
    }

    @HostFunction(name = "copy")
    public Object copy(JsArg handle) throws JsEngineException {
        return answer(() -> crypto.copy(integer(handle)));
    }

    @HostFunction(name = "timingSafeEqual")
    public Object timingSafeEqual(JsArg a, JsArg b) throws JsEngineException {
        return answer(() -> crypto.timingSafeEqual(a.bytes(), b.bytes()));
    }

    @HostFunction(name = "hashes")
    public Object hashes() throws JsEngineException {
        return answer(crypto::hashes);
    }

    // --- key derivation (D-101) ---

    @HostFunction(name = "pbkdf2")
    public Object pbkdf2(JsArg password, JsArg salt, JsArg iterations, JsArg length, JsArg digest, JsArg async) throws JsEngineException {
        byte[] p = password.bytes();
        byte[] s = salt.bytes();
        String d = digest.string();
        if (async != null && async.truthy()) {
            return bridge.promise(() -> crypto.pbkdf2(p, s, integer(iterations), integer(length), d));
        }
        return answer(() -> await(crypto.pbkdf2(p, s, integer(iterations), integer(length), d)));
    }

    @HostFunction(name = "hkdf")
    public Object hkdf(JsArg digest, JsArg key, JsArg salt, JsArg info, JsArg length) throws JsEngineException {
        return answer(() -> crypto.hkdf(digest.string(), key.bytes(), salt.bytes(), info.bytes(), integer(length)));
    }

    @HostFunction(name = "scrypt")
    public Object scrypt(JsArg password, JsArg salt, JsArg length, JsArg options, JsArg async) throws JsEngineException {
        byte[] p = password.bytes();
        byte[] s = salt.bytes();
        io.vertx.core.json.JsonObject o = options.options();
        if (async != null && async.truthy()) {
            return bridge.promise(() -> crypto.scrypt(p, s, integer(length), o));
        }
        return answer(() -> await(crypto.scrypt(p, s, integer(length), o)));
    }

    // --- ciphers (D-101) ---

    @HostFunction(name = "cipher")
    public Object cipher(JsArg algorithm, JsArg encrypt, JsArg key, JsArg iv, JsArg authTagLength) throws JsEngineException {
        return answer(() -> crypto.cipher(algorithm.string(), encrypt.truthy(), key.bytes(), optionalBytes(iv),
                authTagLength == null || authTagLength.isNullish() ? 0 : integer(authTagLength)));
    }

    @HostFunction(name = "cipherPadding")
    public Object cipherPadding(JsArg handle, JsArg on) throws JsEngineException {
        return answer(() -> {
            crypto.cipherPadding(integer(handle), on.truthy());
            return null;
        });
    }

    @HostFunction(name = "cipherAad")
    public Object cipherAad(JsArg handle, JsArg aad) throws JsEngineException {
        return answer(() -> {
            crypto.cipherAad(integer(handle), aad.bytes());
            return null;
        });
    }

    @HostFunction(name = "cipherUpdate")
    public Object cipherUpdate(JsArg handle, JsArg data) throws JsEngineException {
        return answer(() -> crypto.cipherUpdate(integer(handle), data.bytes()));
    }

    @HostFunction(name = "cipherFinal")
    public Object cipherFinal(JsArg handle, JsArg tag) throws JsEngineException {
        return answer(() -> crypto.cipherFinal(integer(handle), optionalBytes(tag)));
    }

    @HostFunction(name = "ciphers")
    public Object ciphers() throws JsEngineException {
        return answer(crypto::ciphers);
    }

    // --- keys and signatures (D-101) ---

    @HostFunction(name = "readKey")
    public Object readKey(JsArg wantPrivate, JsArg data, JsArg format, JsArg type) throws JsEngineException {
        return answer(() -> crypto.readKey(wantPrivate.truthy(), data.bytes(), format.string(), type.string()));
    }

    @HostFunction(name = "publicKeyOf")
    public Object publicKeyOf(JsArg pkcs8) throws JsEngineException {
        return answer(() -> crypto.publicKeyOf(pkcs8.bytes()));
    }

    @HostFunction(name = "exportKey")
    public Object exportKey(JsArg isPrivate, JsArg der, JsArg format, JsArg type) throws JsEngineException {
        return answer(() -> crypto.exportKey(isPrivate.truthy(), der.bytes(), format.string(), type.string()));
    }

    @HostFunction(name = "generateKeyPair")
    public Object generateKeyPair(JsArg type, JsArg options, JsArg async) throws JsEngineException {
        String t = type.string();
        io.vertx.core.json.JsonObject o = options.options();
        if (async != null && async.truthy()) {
            return bridge.promise(() -> crypto.generateKeyPair(t, o));
        }
        return answer(() -> await(crypto.generateKeyPair(t, o)));
    }

    @HostFunction(name = "sign")
    public Object sign(JsArg digest, JsArg data, JsArg pkcs8, JsArg options) throws JsEngineException {
        return answer(() -> crypto.sign(digest.string(), data.bytes(), pkcs8.bytes(), options.options()));
    }

    @HostFunction(name = "verify")
    public Object verify(JsArg digest, JsArg data, JsArg der, JsArg isPrivate, JsArg signature, JsArg options) throws JsEngineException {
        return answer(() -> crypto.verify(digest.string(), data.bytes(), der.bytes(), isPrivate.truthy(),
                signature.bytes(), options.options()));
    }

    @HostFunction(name = "curves")
    public Object curves() throws JsEngineException {
        return answer(crypto::curves);
    }

    // --- RSA encryption and key agreement (D-104) ---

    @HostFunction(name = "rsa")
    public Object rsa(JsArg operation, JsArg der, JsArg isPrivate, JsArg data, JsArg options) throws JsEngineException {
        return answer(() -> crypto.rsa(operation.string(), der.bytes(), isPrivate.truthy(), data.bytes(), options.options()));
    }

    @HostFunction(name = "agree")
    public Object agree(JsArg pkcs8, JsArg spki) throws JsEngineException {
        return answer(() -> crypto.agree(pkcs8.bytes(), spki.bytes()));
    }

    @HostFunction(name = "ecdhKeys")
    public Object ecdhKeys(JsArg curve, JsArg privateValue) throws JsEngineException {
        return answer(() -> crypto.ecdhKeys(curve.string(), optionalBytes(privateValue)));
    }

    @HostFunction(name = "ecdhPoint")
    public Object ecdhPoint(JsArg curve, JsArg encoded) throws JsEngineException {
        return answer(() -> crypto.ecdhPoint(curve.string(), encoded.bytes()));
    }

    @HostFunction(name = "ecdhCompute")
    public Object ecdhCompute(JsArg curve, JsArg privateValue, JsArg otherPublic) throws JsEngineException {
        return answer(() -> crypto.ecdhCompute(curve.string(), privateValue.bytes(), otherPublic.bytes()));
    }
}
