package cluster93.runner.protocol.server;

import cluster93.core.runtime.HostException;
import cluster93.core.runtime.api.HostApis;
import cluster93.runner.protocol.message.ResultMessage;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.util.Objects;
import java.util.Set;

/**
 * Dispatches verified incoming {@code CALL} methods from workers to {@link HostApis} (worker-node-protocol.md §6).
 * Zero-trust: method allowlist checked before execution; unknown methods return INVALID_METHOD.
 *
 * <p>Methods whose semantics are streaming or listener-based (serving HTTP, bus/queue/job listeners, file responses)
 * are delegated to the owning {@link NodeWorkerChannel}, which owns the multiplexer they need (D-128).
 */
public final class NodeRpcDispatcher {

    private static final Set<String> ALLOWED_METHODS = Set.of(
            "kv.get", "kv.set", "kv.delete", "kv.list", "kv.increment",
            "secrets.get",
            "bus.publish", "bus.request", "bus.subscribe", "bus.handle", "bus.unsubscribe", "bus.unhandle",
            "queue.push", "queue.consume", "queue.unconsume", "queue.stats", "queue.dead", "queue.retry", "queue.discard",
            "fs.stat", "fs.readdir", "fs.mkdir", "fs.rmdir", "fs.unlink", "fs.rename", "fs.copyFile", "fs.readFile", "fs.writeFile", "fs.appendFile",
            "fs.lock", "fs.renewLock", "fs.unlock",
            "files.stat", "files.list", "files.delete", "files.respond",
            "dns.lookup", "dns.resolve",
            "jobs.get", "jobs.run", "jobs.result", "jobs.stop", "jobs.setCallback", "jobs.events", "jobs.unevents",
            "serve.listen", "serve.unlisten", "serve.trailers",
            "execution.close"
    );

    private NodeRpcDispatcher() {
    }

    /**
     * Dispatches an RPC method to the target execution's {@link HostApis}.
     */
    public static Future<ResultMessage> dispatch(int callId, HostApis hostApis, String method, JsonObject params, Buffer binary) {
        return dispatch(callId, hostApis, method, params, binary, null, null);
    }

    /**
     * Dispatches an RPC method, giving the {@link NodeWorkerChannel} and run id to the methods that need to open
     * streams or register listeners.
     *
     * @param channel the owning channel, or null when the caller has no streams to bridge
     * @param runId   the active run's id, or null
     */
    public static Future<ResultMessage> dispatch(int callId, HostApis hostApis, String method, JsonObject params, Buffer binary,
                                                 NodeWorkerChannel channel, String runId) {
        Objects.requireNonNull(hostApis, "hostApis");
        if (method == null || !ALLOWED_METHODS.contains(method)) {
            return Future.failedFuture(new HostException("Error", "INVALID_METHOD", "Unauthorized or unknown RPC method: " + method));
        }

        try {
            switch (method) {
                // --- channel-bridged methods (streams and listeners) ---
                case "serve.listen" -> {
                    return requireChannel(channel).serveListen(runId, params).map(ignored -> new ResultMessage(callId, null));
                }
                case "serve.unlisten" -> {
                    return requireChannel(channel).serveUnlisten(runId, params).map(ignored -> new ResultMessage(callId, null));
                }
                case "serve.trailers" -> {
                    return requireChannel(channel).serveTrailers(runId, params).map(ignored -> new ResultMessage(callId, null));
                }
                case "bus.subscribe", "bus.handle", "queue.consume", "jobs.events" -> {
                    return requireChannel(channel).registerListener(runId, method, params).map(ignored -> new ResultMessage(callId, null));
                }
                case "bus.unsubscribe", "bus.unhandle", "queue.unconsume", "jobs.unevents" -> {
                    return requireChannel(channel).unregisterListener(runId, method, params).map(ignored -> new ResultMessage(callId, null));
                }
                case "files.respond" -> {
                    return requireChannel(channel).filesRespond(runId, params).map(ignored -> new ResultMessage(callId, null));
                }
                default -> {
                    // fall through to the stateless switch below
                }
            }

            return switch (method) {
                // --- KV ---
                case "kv.get" -> hostApis.kv().get(params.getString("namespace"), params.getString("key"))
                        .map(val -> new ResultMessage(callId, val));
                case "kv.set" -> hostApis.kv().set(params.getString("namespace"), params.getString("key"),
                                params.getValue("value"), params.getLong("ttlMs"))
                        .map(ignored -> new ResultMessage(callId, null));
                case "kv.delete" -> hostApis.kv().delete(params.getString("namespace"), params.getString("key"))
                        .map(deleted -> new ResultMessage(callId, deleted));
                case "kv.list" -> hostApis.kv().list(params.getString("namespace"), params.getString("prefix"),
                                params.getInteger("limit", 100), params.getString("cursor"))
                        .map(res -> new ResultMessage(callId, res));
                case "kv.increment" -> hostApis.kv().increment(params.getString("namespace"), params.getString("key"),
                                params.getLong("by", 1L))
                        .map(n -> new ResultMessage(callId, n));

                // --- Secrets ---
                case "secrets.get" -> hostApis.secrets().get(params.getString("name"))
                        .map(sec -> new ResultMessage(callId, sec));

                // --- Bus ---
                case "bus.publish" -> hostApis.bus().publish(params.getString("topic"), params.getValue("message"))
                        .map(ignored -> new ResultMessage(callId, null));
                case "bus.request" -> hostApis.bus().request(params.getString("topic"), params.getValue("message"),
                                params.getLong("timeoutMs", 30_000L))
                        .map(reply -> new ResultMessage(callId, reply));

                // --- Queue ---
                case "queue.push" -> hostApis.queue().push(params.getString("queue"), params.getValue("message"),
                                params.getLong("delayMs"), params.getString("key"), params.getInteger("maxAttempts"))
                        .map(id -> new ResultMessage(callId, id));
                case "queue.stats" -> hostApis.queue().stats(params.getString("queue"))
                        .map(st -> new ResultMessage(callId, st));
                case "queue.dead" -> hostApis.queue().dead(params.getString("queue"), params.getInteger("limit", 100), params.getString("cursor"))
                        .map(d -> new ResultMessage(callId, d));
                case "queue.retry" -> hostApis.queue().retry(params.getString("queue"), params.getString("id"))
                        .map(ok -> new ResultMessage(callId, ok));
                case "queue.discard" -> hostApis.queue().discard(params.getString("queue"), params.getString("id"))
                        .map(ok -> new ResultMessage(callId, ok));

                // --- FS ---
                case "fs.stat" -> hostApis.fs().stat(params.getString("path"))
                        .map(st -> new ResultMessage(callId, st));
                case "fs.readdir" -> hostApis.fs().readdir(params.getString("path"))
                        .map(arr -> new ResultMessage(callId, arr));
                case "fs.mkdir" -> hostApis.fs().mkdir(params.getString("path"), params.getBoolean("recursive", false))
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.rmdir" -> hostApis.fs().rmdir(params.getString("path"), params.getBoolean("recursive", false))
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.unlink" -> hostApis.fs().unlink(params.getString("path"))
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.rename" -> hostApis.fs().rename(params.getString("from"), params.getString("to"))
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.copyFile" -> hostApis.fs().copyFile(params.getString("from"), params.getString("to"),
                                params.getBoolean("overwrite", false))
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.readFile" -> hostApis.fs().readFile(params.getString("path"))
                        .map(buf -> new ResultMessage(callId, null, buf != null ? buf : Buffer.buffer()));
                case "fs.writeFile" -> hostApis.fs().writeFile(params.getString("path"), binary, params.getString("contentType"))
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.appendFile" -> hostApis.fs().appendFile(params.getString("path"), binary)
                        .map(ignored -> new ResultMessage(callId, null));
                case "fs.lock" -> hostApis.fs().lock(params.getString("path"), params.getLong("ttlMs", 30_000L))
                        .map(res -> new ResultMessage(callId, res));
                case "fs.renewLock" -> hostApis.fs().renewLock(params.getString("path"), params.getString("token"), params.getLong("ttlMs", 30_000L))
                        .map(res -> new ResultMessage(callId, res));
                case "fs.unlock" -> hostApis.fs().unlock(params.getString("path"), params.getString("token"))
                        .map(unlocked -> new ResultMessage(callId, unlocked));

                // --- Files ---
                case "files.stat" -> hostApis.files().stat(params.getString("path"))
                        .map(st -> new ResultMessage(callId, st));
                case "files.list" -> hostApis.files().list(params.getString("prefix"), params.getInteger("limit", 100), params.getString("cursor"))
                        .map(list -> new ResultMessage(callId, list));
                case "files.delete" -> hostApis.files().delete(params.getString("path"))
                        .map(del -> new ResultMessage(callId, del));

                // --- DNS ---
                case "dns.lookup" -> hostApis.dns().lookup(params).map(arr -> new ResultMessage(callId, arr));
                case "dns.resolve" -> hostApis.dns().resolve(params).map(arr -> new ResultMessage(callId, arr));

                // --- Jobs ---
                case "jobs.get" -> hostApis.jobs().get(params.getString("id")).map(j -> new ResultMessage(callId, j));
                case "jobs.run" -> hostApis.jobs().run(params.getString("script"), params.getJsonObject("input"), params.getJsonObject("options"))
                        .map(j -> new ResultMessage(callId, j));
                case "jobs.result" -> hostApis.jobs().result(params.getString("id")).map(res -> new ResultMessage(callId, res));
                case "jobs.stop" -> hostApis.jobs().stop(params.getString("id"), params.getString("reason")).map(j -> new ResultMessage(callId, j));
                case "jobs.setCallback" -> hostApis.jobs().setCallback(params.getString("url"), params.getValue("data"))
                        .map(ignored -> new ResultMessage(callId, null));

                // --- Execution lifecycle ---
                case "execution.close" -> hostApis.close().map(ignored -> new ResultMessage(callId, null));

                default -> Future.failedFuture(new HostException("Error", "NOT_IMPLEMENTED", "Method not implemented: " + method));
            };
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    private static NodeWorkerChannel requireChannel(NodeWorkerChannel channel) {
        if (channel == null) {
            throw new HostException("Error", "UNAVAILABLE", "This method needs a worker connection");
        }
        return channel;
    }
}
