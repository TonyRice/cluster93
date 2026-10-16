package cluster93.server;

import cluster93.runner.protocol.server.NodeRpcAuthorizer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node's worker-call authorizer (D-150): with a shared worker socket the channel is bound to whoever connected,
 * so this is what scopes a call to the run's tenant and to the host-API surface (work order §2.8).
 */
class WorkerRpcAuthorizerTest {

    /** As the server wires it: only one run is live on this node, and it belongs to {@code acme}. */
    private static final NodeRpcAuthorizer AUTHORIZER =
            new WorkerRpcAuthorizer(runId -> "job-live".equals(runId) ? "acme" : null);

    @Test
    @DisplayName("A call for this node's own run of the tenant the worker is bound to is allowed")
    void aCallForTheBoundTenantsLiveRunIsAllowed() {
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", "kv.get", new JsonObject())).isTrue();
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", "files.stat", new JsonObject())).isTrue();
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", "jobs.run", new JsonObject())).isTrue();
    }

    @Test
    @DisplayName("A call for another tenant's run is refused")
    void aCallForAnotherTenantsRunIsRefused() {
        assertThat(AUTHORIZER.isAuthorized("globex", "job-live", "kv.get", new JsonObject())).isFalse();
    }

    @Test
    @DisplayName("A call for a run this node is not executing is refused: a superseded attempt, or another node's job")
    void aCallForAARunThisNodeDoesNotHaveIsRefused() {
        assertThat(AUTHORIZER.isAuthorized("acme", "job-superseded", "kv.get", new JsonObject())).isFalse();
        assertThat(AUTHORIZER.isAuthorized("acme", null, "kv.get", new JsonObject())).isFalse();
    }

    @Test
    @DisplayName("A call outside the documented host-API areas is refused, so an invented API reaches nothing")
    void aCallOutsideTheDocumentedAreasIsRefused() {
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", "docker.run", new JsonObject())).isFalse();
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", "host.exec", new JsonObject())).isFalse();
        // A bare area is not a method, and neither is nothing at all.
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", "kv", new JsonObject())).isFalse();
        assertThat(AUTHORIZER.isAuthorized("acme", "job-live", null, new JsonObject())).isFalse();
    }

    @Test
    @DisplayName("The run's own teardown is allowed on the binding alone: it outlives the run by one frame")
    void aRunsOwnTeardownIsAllowed() {
        for (String method : java.util.List.of("execution.close", "serve.unlisten", "bus.unsubscribe",
                "bus.unhandle", "queue.unconsume", "jobs.unevents")) {
            assertThat(AUTHORIZER.isAuthorized("acme", "job-already-gone", method, new JsonObject()))
                    .as(method + " only releases what the run held").isTrue();
        }
        // Still only for a bound worker, and still only inside a documented area.
        assertThat(AUTHORIZER.isAuthorized(null, "job-already-gone", "execution.close", new JsonObject())).isFalse();
        assertThat(AUTHORIZER.isAuthorized("acme", "job-already-gone", "docker.close", new JsonObject())).isFalse();
    }

    @Test
    @DisplayName("A worker that has not been bound to a tenant may not call anything")
    void anUnboundWorkerMayNotCall() {
        assertThat(AUTHORIZER.isAuthorized(null, "job-live", "kv.get", new JsonObject())).isFalse();
    }
}
