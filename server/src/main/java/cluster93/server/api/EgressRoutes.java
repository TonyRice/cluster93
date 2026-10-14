package cluster93.server.api;

import cluster93.core.auth.AuthStore;
import cluster93.core.host.FetchClient;
import cluster93.core.host.egress.AddressSets;
import cluster93.core.host.egress.ClusterEgress;
import cluster93.core.host.egress.EgressPolicies;
import cluster93.core.host.egress.EgressRule;
import cluster93.core.host.egress.Posture;
import cluster93.core.host.egress.TenantEgress;
import cluster93.core.runtime.api.FetchApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import static cluster93.server.api.ApiVerticle.error;

/**
 * Where scripts may connect (D-108, D-111). Operators set the cluster's policy and each tenant's; a tenant reads the
 * whole policy that applies to it, in the order it's applied and each part with why, so it can see why a connection was
 * refused (and the console can show it).
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
        router.get("/v1/egress/cluster-policy").handler(ctx -> policies.loadCluster().onFailure(ctx::fail)
                .onSuccess(policy -> ctx.json(cluster(policy))));
        router.put("/v1/egress/cluster-policy").handler(this::putCluster);
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
        withTenant(ctx, () -> policies.loadCluster().onFailure(ctx::fail).onSuccess(cluster -> {
            for (EgressRule rule : policy.rules()) {
                if (rule.kind() == EgressRule.Kind.SET && !cluster.setNames().contains(rule.name())) {
                    error(ctx, 400, "invalid_argument", "The rule for \"" + rule.to() + "\" names a set that isn't defined; the sets are "
                            + String.join(", ", cluster.setNames().stream().map(name -> "@" + name).toList()));
                    return;
                }
            }
            policies.put(policy).onFailure(ctx::fail).onSuccess(v -> view(ctx, tenant));
        }));
    }

    private void putCluster(RoutingContext ctx) {
        ClusterEgress policy;
        try {
            JsonObject body = ctx.body().asJsonObject();
            policy = ClusterEgress.fromJson(body == null ? new JsonObject() : body);
        } catch (IllegalArgumentException | io.vertx.core.json.DecodeException | ClassCastException e) {
            error(ctx, 400, "invalid_argument", e.getMessage());
            return;
        }
        policies.putCluster(policy).onFailure(ctx::fail).onSuccess(v -> ctx.json(cluster(policy)));
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
        policies.loadCluster().compose(cluster -> policies.load(tenant).map(policy -> view(cluster, policy, allowPrivate)))
                .onFailure(ctx::fail).onSuccess(ctx::json);
    }

    /** The cluster's policy as an operator edits it, with the built-in sets explained. */
    static JsonObject cluster(ClusterEgress policy) {
        return policy.toJson().put("builtInSets", builtInSets());
    }

    private static JsonObject builtInSets() {
        JsonObject sets = new JsonObject();
        sets.put("public", AddressSets.DESCRIPTIONS.get("public"));
        AddressSets.BUILT_IN.keySet().forEach(name -> sets.put(name, AddressSets.DESCRIPTIONS.get(name)));
        return sets;
    }

    /**
     * The policy as the tenant sees it (D-111): each step in the order the gate applies it, with why, then the sets its
     * rules can name and the limits.
     */
    static JsonObject view(ClusterEgress cluster, TenantEgress policy, boolean allowPrivate) {
        boolean own = policy.posture() != null;
        Posture posture = own ? policy.posture() : cluster.posture();
        JsonArray clusterRules = new JsonArray();
        cluster.rules().forEach(rule -> clusterRules.add(rule.toJson()));
        JsonArray rules = new JsonArray();
        policy.rules().forEach(rule -> rules.add(rule.toJson()));
        JsonArray steps = new JsonArray()
                .add(step("the cluster's own nodes", "always refused",
                        "Nothing on a node is meant for scripts, and no rule can allow it."))
                .add(step("cluster deny rules", "refused",
                        "An operator's rules for every tenant. A tenant's own rules can't override them."))
                .add(step("this tenant's deny rules", "refused", "Rules an operator set for this tenant."))
                .add(step("this tenant's allow rules", "allowed", "Rules an operator set for this tenant."))
                .add(step("cluster allow rules", "allowed", "An operator's rules for every tenant."));
        if (allowPrivate) {
            steps.add(step("private and reserved addresses", "allowed",
                    "This cluster's development switch (C93_EGRESS_ALLOW_PRIVATE) is on, so every tenant may reach them."));
        }
        steps.add(posture == Posture.PUBLIC
                ? step("anything else", "public addresses allowed; private and reserved ones refused",
                        "The " + (own ? "tenant's" : "cluster's") + " posture is \"public\": scripts may reach the public internet. Private "
                                + "networks, loopback, link-local (including cloud metadata) and reserved ranges belong to the operator.")
                : step("anything else", "refused",
                        "The " + (own ? "tenant's" : "cluster's") + " posture is \"allow-list\": only what a rule allows can be reached."));
        JsonObject sets = builtInSets();
        cluster.addressSets().forEach((name, ranges) -> sets.put(name, new JsonArray(ranges.stream().map(Object::toString).toList())));
        return new JsonObject()
                .put("tenant", policy.tenant())
                .put("posture", new JsonObject().put("value", posture.code).put("from", own ? "tenant" : "cluster"))
                .put("steps", steps)
                .put("clusterRules", clusterRules)
                .put("rules", rules)
                .put("addressSets", sets)
                .put("developmentSwitch", allowPrivate)
                .put("limits", new JsonObject()
                        .put("concurrentRequests", FetchApi.MAX_CONCURRENT)
                        .put("requestBodyBytes", FetchClient.MAX_REQUEST_BYTES)
                        .put("timeoutMs", FetchClient.DEFAULT_TIMEOUT_MS)
                        .put("followsRedirects", false)
                        // Sockets (D-116): the cluster policy's limits; no idle timeout, as in Node.
                        .put("socketsPerExecution", cluster.limits().socketsPerExecution())
                        .put("socketsPerTenantPerNode", cluster.limits().socketsPerTenantPerNode())
                        .put("connectsPerMinute", cluster.limits().connectsPerMinute()));
    }

    private static JsonObject step(String what, String access, String why) {
        return new JsonObject().put("to", what).put("access", access).put("why", why);
    }
}
