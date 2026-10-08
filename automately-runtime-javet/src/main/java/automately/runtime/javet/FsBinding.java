package automately.runtime.javet;

import automately.core.runtime.HostException;
import automately.core.runtime.api.FsApi;
import com.caoccao.javet.annotations.V8Function;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueBoolean;
import com.caoccao.javet.values.reference.V8ValuePromise;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@code fs} in JavaScript (D-079): each call as a promise, and as a {@code …Sync} variant that blocks the isolate's
 * own thread until the host answers, as Node's do. Isolates run on worker threads, so blocking one holds up only that
 * script, never the node. A sync call that fails returns (not throws) the error, which {@code fs.js} throws as Node's.
 */
public final class FsBinding {

    /** The longest a synchronous call waits for the host. */
    static final Duration SYNC_TIMEOUT = Duration.ofSeconds(30);

    private final HostBridge bridge;
    private final JsValues js;
    private final FsApi fs;

    FsBinding(HostBridge bridge, FsApi fs) {
        this.bridge = bridge;
        this.js = bridge.values();
        this.fs = fs;
    }

    // --- promises ---

    @V8Function(name = "stat")
    public V8ValuePromise stat(V8Value path) throws JavetException {
        return bridge.promise(() -> fs.stat(js.string(path)));
    }

    @V8Function(name = "readdir")
    public V8ValuePromise readdir(V8Value path) throws JavetException {
        return bridge.promise(() -> fs.readdir(js.string(path)));
    }

    @V8Function(name = "mkdir")
    public V8ValuePromise mkdir(V8Value path, V8Value recursive) throws JavetException {
        return bridge.promise(() -> fs.mkdir(js.string(path), flag(recursive)));
    }

    @V8Function(name = "rmdir")
    public V8ValuePromise rmdir(V8Value path, V8Value recursive) throws JavetException {
        return bridge.promise(() -> fs.rmdir(js.string(path), flag(recursive)));
    }

    @V8Function(name = "unlink")
    public V8ValuePromise unlink(V8Value path) throws JavetException {
        return bridge.promise(() -> fs.unlink(js.string(path)));
    }

    @V8Function(name = "rename")
    public V8ValuePromise rename(V8Value from, V8Value to) throws JavetException {
        return bridge.promise(() -> fs.rename(js.string(from), js.string(to)));
    }

    @V8Function(name = "copyFile")
    public V8ValuePromise copyFile(V8Value from, V8Value to, V8Value overwrite) throws JavetException {
        return bridge.promise(() -> fs.copyFile(js.string(from), js.string(to), flag(overwrite)));
    }

    @V8Function(name = "readFile")
    public V8ValuePromise readFile(V8Value path) throws JavetException {
        return bridge.promise(() -> fs.readFile(js.string(path)), Buffer::getBytes);
    }

    @V8Function(name = "writeFile")
    public V8ValuePromise writeFile(V8Value path, V8Value data, V8Value contentType) throws JavetException {
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> fs.writeFile(js.string(path), Buffer.buffer(bytes), js.string(contentType)));
    }

    @V8Function(name = "appendFile")
    public V8ValuePromise appendFile(V8Value path, V8Value data) throws JavetException {
        byte[] bytes = HostBridge.bytes(data);
        return bridge.promise(() -> fs.appendFile(js.string(path), Buffer.buffer(bytes)));
    }

    // --- advisory locks (D-080) ---

    @V8Function(name = "lock")
    public V8ValuePromise lock(V8Value path, V8Value ttlMs) throws JavetException {
        long ttl = millis(ttlMs);
        return bridge.promise(() -> fs.lock(js.string(path), ttl));
    }

    @V8Function(name = "renewLock")
    public V8ValuePromise renewLock(V8Value path, V8Value token, V8Value ttlMs) throws JavetException {
        long ttl = millis(ttlMs);
        return bridge.promise(() -> fs.renewLock(js.string(path), js.string(token), ttl));
    }

    @V8Function(name = "unlock")
    public V8ValuePromise unlock(V8Value path, V8Value token) throws JavetException {
        return bridge.promise(() -> fs.unlock(js.string(path), js.string(token)));
    }

    private long millis(V8Value value) throws JavetException {
        return js.toJava(value) instanceof Number number ? number.longValue() : 30_000L;
    }

    // --- synchronous ---

    @V8Function(name = "statSync")
    public V8Value statSync(V8Value path) throws JavetException {
        return sync(() -> fs.stat(js.string(path)));
    }

    @V8Function(name = "readdirSync")
    public V8Value readdirSync(V8Value path) throws JavetException {
        return sync(() -> fs.readdir(js.string(path)));
    }

    @V8Function(name = "mkdirSync")
    public V8Value mkdirSync(V8Value path, V8Value recursive) throws JavetException {
        return sync(() -> fs.mkdir(js.string(path), flag(recursive)));
    }

    @V8Function(name = "rmdirSync")
    public V8Value rmdirSync(V8Value path, V8Value recursive) throws JavetException {
        return sync(() -> fs.rmdir(js.string(path), flag(recursive)));
    }

    @V8Function(name = "unlinkSync")
    public V8Value unlinkSync(V8Value path) throws JavetException {
        return sync(() -> fs.unlink(js.string(path)));
    }

    @V8Function(name = "renameSync")
    public V8Value renameSync(V8Value from, V8Value to) throws JavetException {
        return sync(() -> fs.rename(js.string(from), js.string(to)));
    }

    @V8Function(name = "copyFileSync")
    public V8Value copyFileSync(V8Value from, V8Value to, V8Value overwrite) throws JavetException {
        return sync(() -> fs.copyFile(js.string(from), js.string(to), flag(overwrite)));
    }

    @V8Function(name = "readFileSync")
    public V8Value readFileSync(V8Value path) throws JavetException {
        return sync(() -> fs.readFile(js.string(path)).map(Buffer::getBytes));
    }

    @V8Function(name = "writeFileSync")
    public V8Value writeFileSync(V8Value path, V8Value data, V8Value contentType) throws JavetException {
        byte[] bytes = HostBridge.bytes(data);
        return sync(() -> fs.writeFile(js.string(path), Buffer.buffer(bytes), js.string(contentType)));
    }

    @V8Function(name = "appendFileSync")
    public V8Value appendFileSync(V8Value path, V8Value data) throws JavetException {
        byte[] bytes = HostBridge.bytes(data);
        return sync(() -> fs.appendFile(js.string(path), Buffer.buffer(bytes)));
    }

    /** Waits on this (isolate) thread for the host; the result, or the failure as an Error value to throw in JavaScript. */
    private V8Value sync(Supplier<Future<?>> call) throws JavetException {
        Object result;
        try {
            Future<?> future;
            try {
                future = call.get();
            } catch (RuntimeException e) {
                return js.error(e);
            }
            result = future.toCompletionStage().toCompletableFuture().get(SYNC_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return js.error(e.getCause());
        } catch (TimeoutException e) {
            return js.error(new HostException("Error", "timeout", "The file system didn't answer within " + SYNC_TIMEOUT.toSeconds() + " s"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return js.error(new HostException("Error", "cancelled", "The script is stopping"));
        }
        return js.toJs(result);
    }

    private static boolean flag(V8Value value) {
        return value instanceof V8ValueBoolean bool && bool.getValue();
    }
}
