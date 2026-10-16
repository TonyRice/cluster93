package cluster93.server;

import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Function;

/**
 * The node's worker-call authorizer (D-150): every RPC a worker sends is checked before the node answers it.
 *
 * <p>With a pipe, the channel is bound to a child the node itself started, so "who is calling" needs no check. With
 * the worker socket (D-166) the connection is bound to <b>whoever connected</b>, and the socket names no tenant, so
 * this is what scopes a call to the tenant and the run that own it. It asks two things:
 *
 * <ol>
 *   <li><b>The run is this node's, and the worker is bound to its tenant.</b> A call for a run this node is not
 *       executing — a superseded attempt, another tenant's job — is refused. Answered from the node's own books
 *       ({@link cluster93.core.scheduler.NodeVerticle#runningTenant}), so it costs no store read on the event loop;
 *       the durable fencing stays the compare-and-set every state write does (D-009…D-012).</li>
 *   <li><b>The method is in a documented host-API area</b> (worker-node-protocol.md §6). This bounds the surface a
 *       worker can reach; the dispatcher's own switch is what actually answers a verb, so a new verb in an existing
 *       area needs nothing here, and an invented area is refused.</li>
 * </ol>
 *
 * <p>The run's own teardown ({@code execution.close}) is allowed on the binding alone: it arrives just after
 * {@code RESULT_RUN}, when the node has already let the run go, and it exposes nothing.
 *
 * <p>Refusals are logged with the run, tenant and method and answered as an error frame — never a silent drop — so a
 * misconfigured deployment is diagnosable (D-150).
 */
final class WorkerRpcAuthorizer implements NodeRpcAuthorizer {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerRpcAuthorizer.class);

    /**
     * The host-API areas a worker may call into (worker-node-protocol.md §6). An allow-list by area, not by verb: it
     * does not have to be updated when a verb is added to an area, so it cannot silently widen with the dispatcher.
     */
    static final Set<String> AREAS = Set.of(
            "serve", "bus", "queue", "jobs", "kv", "secrets", "fs", "files", "dns", "execution", "http2", "sockets");

    /** The tenant of the run, or null when this node is not executing it. */
    private final Function<String, String> runningTenant;

    WorkerRpcAuthorizer(Function<String, String> runningTenant) {
        this.runningTenant = runningTenant;
    }

    @Override
    public boolean isAuthorized(String boundTenantId, String runId, String method, JsonObject params) {
        if (boundTenantId == null) {
            return refuse("the worker is not bound to a tenant", boundTenantId, runId, method);
        }
        if (!inAKnownArea(method)) {
            return refuse("no such host API area", boundTenantId, runId, method);
        }
        if (TEARDOWN.contains(method)) {
            // A run's teardown arrives just after RESULT_RUN — it closes its host resources and unregisters the
            // servers and listeners it opened — when the node has already let the run go, so it is not "live" any
            // more. These calls only release what the run had, and the channel answers them only for a run it is
            // still carrying, so they are allowed on the binding alone.
            return true;
        }
        String runTenant = runId == null ? null : runningTenant.apply(runId);
        if (runTenant == null) {
            return refuse("this node is not running that run", boundTenantId, runId, method);
        }
        if (!runTenant.equals(boundTenantId)) {
            return refuse("the run belongs to another tenant", boundTenantId, runId, method);
        }
        return true;
    }

    /** The run's own teardown {@code CALL}s (worker-node-protocol.md §6); they outlive the run by one frame. */
    private static final Set<String> TEARDOWN = Set.of(
            "execution.close",
            "serve.unlisten",
            "bus.unsubscribe", "bus.unhandle",
            "queue.unconsume",
            "jobs.unevents");

    /** Whether {@code method} names a documented area, e.g. {@code kv.get} or {@code files.stat}. */
    private static boolean inAKnownArea(String method) {
        if (method == null) {
            return false;
        }
        int dot = method.indexOf('.');
        return dot > 0 && AREAS.contains(method.substring(0, dot));
    }

    private static boolean refuse(String why, String tenant, String runId, String method) {
        LOG.warn("Refused a worker call: method {}, run {}, tenant {}: {} (D-150)", method, runId, tenant, why);
        return false;
    }
}
