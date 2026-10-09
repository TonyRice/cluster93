package automately.core.host.egress;

import automately.core.identity.Records;
import io.vertx.core.Future;

import java.util.List;

/** Each tenant's egress policy (D-108), kept durably by tenant. */
public final class EgressPolicies {

    public static final Records.Kind KIND = new Records.Kind("EGRESS_POLICIES", List.of());

    /** No tenant has rules: for nodes and tests without durable records. */
    public static final EgressPolicies NONE = new EgressPolicies(null);

    private final Records records;

    public EgressPolicies(Records records) {
        this.records = records;
    }

    /** The tenant's policy, or {@link TenantEgress#none} if an operator never set one. */
    public Future<TenantEgress> of(String tenant) {
        if (records == null || tenant == null) {
            return Future.succeededFuture(TenantEgress.none(tenant));
        }
        return records.get(tenant).map(json -> json == null ? TenantEgress.none(tenant) : TenantEgress.fromJson(tenant, json));
    }

    public Future<Void> put(TenantEgress policy) {
        if (records == null) {
            return Future.failedFuture(new IllegalStateException("This node keeps no egress policies"));
        }
        return policy.allow().isEmpty() ? records.remove(policy.tenant()).mapEmpty() : records.put(policy.tenant(), policy.toJson());
    }
}
