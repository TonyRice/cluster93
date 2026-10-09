package automately.server.api;

import automately.core.auth.AuthStore;
import automately.core.host.FetchClient;
import automately.core.host.egress.EgressPolicies;
import automately.core.host.egress.TenantEgress;
import automately.core.runtime.api.FetchApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import static automately.server.api.ApiVerticle.error;

/**
 * Where scripts may connect (D-108). Operators set each tenant's rules; a tenant reads the whole policy that applies to
 * it, defaults included, each with why, so it can see why a connection was refused (and the console can show it).
 */
final class EgressRoutes {

    private final EgressPolicies policies;
    private final AuthStore auth;
    private final boolean allowPrivate;

    EgressRoutes(EgressPolicies policies, AuthStore auth, boolean allowPrivate) {
        this.policies = policies;
        this.auth = auth;
        this.allowPrivate = allowPrivate;
    }

    void mount(Router router) {
        router.get("/v1/egress-policy").handler(ctx -> view(ctx, ctx.get("tenant")));
        router.get("/v1/tenants/:tenant/egress-policy").handler(ctx -> withTenant(ctx, () -> view(ctx, ctx.pathParam("tenant"))));
        router.put("/v1/tenants/:tenant/egress-policy").handler(this::put);
    }

    private void put(RoutingContext ctx) {
        String tenant = ctx.pathParam("tenant");
        TenantEgress policy;
        try {
            JsonObject body = ctx.body().asJsonObject();
            policy = TenantEgress.fromJson(tenant, body == null ? new JsonObject() : body);
        } catch (IllegalArgumentException | io.vertx.core.json.DecodeException | ClassCastException e) {
            error(ctx, 400, "invalid_argument", e.getMessage());
            return;
        }
        withTenant(ctx, () -> policies.put(policy).onFailure(ctx::fail).onSuccess(v -> view(ctx, tenant)));
    }

    private void withTenant(RoutingContext ctx, Runnable then) {
        auth.tenant(ctx.pathParam("tenant")).onFailure(ctx::fail).onSuccess(found -> {
            if (found == null) {
                error(ctx, 404, "not_found", "No such tenant");
            } else {
                then.run();
            }
        });
    }

    private void view(RoutingContext ctx, String tenant) {
        policies.of(tenant).onFailure(ctx::fail).onSuccess(policy -> ctx.json(view(policy, allowPrivate)));
    }

    /** The policy as the tenant sees it: the defaults, its own rules, the development switch, and the limits. */
    static JsonObject view(TenantEgress policy, boolean allowPrivate) {
        JsonArray defaults = new JsonArray()
                .add(new JsonObject().put("to", "public addresses").put("access", "allowed")
                        .put("why", "Scripts may reach the public internet, connecting to the address that was checked and following no redirects."))
                .add(new JsonObject().put("to", "private and reserved addresses").put("access", allowPrivate ? "allowed" : "refused unless a rule allows them")
                        .put("why", allowPrivate
                                ? "This cluster's development switch (AUTOMATELY_EGRESS_ALLOW_PRIVATE) is on, so every tenant may reach them."
                                : "Private networks, loopback, link-local (including cloud metadata) and reserved ranges belong to the operator. An operator can allow specific ones for this tenant."))
                .add(new JsonObject().put("to", "the cluster's own nodes").put("access", "always refused")
                        .put("why", "Nothing on a node is meant for scripts, and no rule can allow it."));
        JsonArray rules = new JsonArray();
        policy.allow().forEach(rule -> rules.add(rule.toJson()));
        return new JsonObject()
                .put("tenant", policy.tenant())
                .put("allow", rules)
                .put("defaults", defaults)
                .put("developmentSwitch", allowPrivate)
                .put("limits", new JsonObject()
                        .put("concurrentRequests", FetchApi.MAX_CONCURRENT)
                        .put("requestBodyBytes", FetchClient.MAX_REQUEST_BYTES)
                        .put("timeoutMs", FetchClient.DEFAULT_TIMEOUT_MS)
                        .put("followsRedirects", false));
    }
}
