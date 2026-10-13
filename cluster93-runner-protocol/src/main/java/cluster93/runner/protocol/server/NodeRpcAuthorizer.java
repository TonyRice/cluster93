package cluster93.runner.protocol.server;

import io.vertx.core.json.JsonObject;

/**
 * Validates whether an incoming worker RPC call is authorized for the given tenant and execution context.
 */
@FunctionalInterface
public interface NodeRpcAuthorizer {

    /**
     * Authorize an incoming method call.
     *
     * @param boundTenantId the verified tenant bound to this worker
     * @param runId the execution run ID
     * @param method the RPC method name (e.g. "kv.set", "secrets.get")
     * @param params JSON parameters passed by the worker
     * @return true if authorized; false if forbidden
     */
    boolean isAuthorized(String boundTenantId, String runId, String method, JsonObject params);

    /**
     * Default authorizer allowing standard permitted methods.
     */
    NodeRpcAuthorizer ALLOW_ALL = (tenant, run, method, params) -> true;
}
