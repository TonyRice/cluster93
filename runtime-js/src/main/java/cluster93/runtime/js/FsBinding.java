package cluster93.runtime.js;

import cluster93.core.runtime.HostException;
import cluster93.core.runtime.api.FsApi;
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
    private final FsApi fs;

    FsBinding(HostBridge bridge, FsApi fs) {
        this.bridge = bridge;
        this.fs = fs;
    }

    // --- promises ---

    @HostFunction(name = "stat")
    public JsHandle stat(JsArg path) throws JsEngineException {
        return bridge.promise(() -> fs.stat(path.string()));
    }

    @HostFunction(name = "readdir")
    public JsHandle readdir(JsArg path) throws JsEngineException {
        return bridge.promise(() -> fs.readdir(path.string()));
    }

    @HostFunction(name = "mkdir")
    public JsHandle mkdir(JsArg path, JsArg recursive) throws JsEngineException {
        return bridge.promise(() -> fs.mkdir(path.string(), flag(recursive)));
    }

    @HostFunction(name = "rmdir")
    public JsHandle rmdir(JsArg path, JsArg recursive) throws JsEngineException {
        return bridge.promise(() -> fs.rmdir(path.string(), flag(recursive)));
    }

    @HostFunction(name = "unlink")
    public JsHandle unlink(JsArg path) throws JsEngineException {
        return bridge.promise(() -> fs.unlink(path.string()));
    }

    @HostFunction(name = "rename")
    public JsHandle rename(JsArg from, JsArg to) throws JsEngineException {
        return bridge.promise(() -> fs.rename(from.string(), to.string()));
    }

    @HostFunction(name = "copyFile")
    public JsHandle copyFile(JsArg from, JsArg to, JsArg overwrite) throws JsEngineException {
        return bridge.promise(() -> fs.copyFile(from.string(), to.string(), flag(overwrite)));
    }

    @HostFunction(name = "readFile")
    public JsHandle readFile(JsArg path) throws JsEngineException {
        return bridge.promise(() -> fs.readFile(path.string()), Buffer::getBytes);
    }

    @HostFunction(name = "writeFile")
    public JsHandle writeFile(JsArg path, JsArg data, JsArg contentType) throws JsEngineException {
        byte[] bytes = data.bytes();
        return bridge.promise(() -> fs.writeFile(path.string(), Buffer.buffer(bytes), contentType.string()));
    }

    @HostFunction(name = "appendFile")
    public JsHandle appendFile(JsArg path, JsArg data) throws JsEngineException {
        byte[] bytes = data.bytes();
        return bridge.promise(() -> fs.appendFile(path.string(), Buffer.buffer(bytes)));
    }

    // --- advisory locks (D-080) ---

    @HostFunction(name = "lock")
    public JsHandle lock(JsArg path, JsArg ttlMs) throws JsEngineException {
        long ttl = millis(ttlMs);
        return bridge.promise(() -> fs.lock(path.string(), ttl));
    }

    @HostFunction(name = "renewLock")
    public JsHandle renewLock(JsArg path, JsArg token, JsArg ttlMs) throws JsEngineException {
        long ttl = millis(ttlMs);
        return bridge.promise(() -> fs.renewLock(path.string(), token.string(), ttl));
    }

    @HostFunction(name = "unlock")
    public JsHandle unlock(JsArg path, JsArg token) throws JsEngineException {
        return bridge.promise(() -> fs.unlock(path.string(), token.string()));
    }

    private long millis(JsArg value) {
        return value.json() instanceof Number number ? number.longValue() : 30_000L;
    }

    // --- synchronous ---

    @HostFunction(name = "statSync")
    public Object statSync(JsArg path) throws JsEngineException {
        return sync(() -> fs.stat(path.string()));
    }

    @HostFunction(name = "readdirSync")
    public Object readdirSync(JsArg path) throws JsEngineException {
        return sync(() -> fs.readdir(path.string()));
    }

    @HostFunction(name = "mkdirSync")
    public Object mkdirSync(JsArg path, JsArg recursive) throws JsEngineException {
        return sync(() -> fs.mkdir(path.string(), flag(recursive)));
    }

    @HostFunction(name = "rmdirSync")
    public Object rmdirSync(JsArg path, JsArg recursive) throws JsEngineException {
        return sync(() -> fs.rmdir(path.string(), flag(recursive)));
    }

    @HostFunction(name = "unlinkSync")
    public Object unlinkSync(JsArg path) throws JsEngineException {
        return sync(() -> fs.unlink(path.string()));
    }

    @HostFunction(name = "renameSync")
    public Object renameSync(JsArg from, JsArg to) throws JsEngineException {
        return sync(() -> fs.rename(from.string(), to.string()));
    }

    @HostFunction(name = "copyFileSync")
    public Object copyFileSync(JsArg from, JsArg to, JsArg overwrite) throws JsEngineException {
        return sync(() -> fs.copyFile(from.string(), to.string(), flag(overwrite)));
    }

    @HostFunction(name = "readFileSync")
    public Object readFileSync(JsArg path) throws JsEngineException {
        return sync(() -> fs.readFile(path.string()).map(Buffer::getBytes));
    }

    @HostFunction(name = "writeFileSync")
    public Object writeFileSync(JsArg path, JsArg data, JsArg contentType) throws JsEngineException {
        byte[] bytes = data.bytes();
        return sync(() -> fs.writeFile(path.string(), Buffer.buffer(bytes), contentType.string()));
    }

    @HostFunction(name = "appendFileSync")
    public Object appendFileSync(JsArg path, JsArg data) throws JsEngineException {
        byte[] bytes = data.bytes();
        return sync(() -> fs.appendFile(path.string(), Buffer.buffer(bytes)));
    }

    /** Waits on this (isolate) thread for the host; the result, or the failure as an Error value to throw in JavaScript. */
    private Object sync(Supplier<Future<?>> call) {
        Object result;
        try {
            Future<?> future;
            try {
                future = call.get();
            } catch (RuntimeException e) {
                return JsError.of(e);
            }
            result = future.toCompletionStage().toCompletableFuture().get(SYNC_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return JsError.of(e.getCause());
        } catch (TimeoutException e) {
            return JsError.of(new HostException("Error", "timeout", "The file system didn't answer within " + SYNC_TIMEOUT.toSeconds() + " s"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsError.of(new HostException("Error", "cancelled", "The script is stopping"));
        }
        return result;
    }

    private static boolean flag(JsArg value) {
        return Js.flag(value);
    }
}
