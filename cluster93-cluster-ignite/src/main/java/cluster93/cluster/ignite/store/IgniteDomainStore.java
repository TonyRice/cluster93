package cluster93.cluster.ignite.store;

import cluster93.core.host.Domain;
import cluster93.core.host.DomainStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.ignite.Ignite;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Custom domains in a durable, replicated table, indexed by tenant and service. */
public final class IgniteDomainStore implements DomainStore {

    static final Table.Definition TABLE = new Table.Definition("DOMAINS",
            Table.columns("tenant", String.class, "service", String.class), List.of(Table.index("tenant", "service")));

    private final Table table;

    public IgniteDomainStore(Vertx vertx, Ignite ignite) {
        this.table = new Table(vertx, ignite, TABLE, json -> Map.of("tenant", json.getString("tenant"), "service", json.getString("service")));
    }

    @Override
    public Future<Domain> get(String domain) {
        return table.get(domain).map(json -> json == null ? null : Domain.fromJson(json));
    }

    @Override
    public Future<Optional<Domain>> claim(Domain claim, long now) {
        return table.putIfAbsent(claim.domain(), claim.toJson()).compose(created -> {
            if (created) {
                return Future.succeededFuture(Optional.of(claim));
            }
            return table.update(claim.domain(), current -> {
                Domain existing = Domain.fromJson(current);
                boolean sameService = existing.tenant().equals(claim.tenant()) && existing.service().equals(claim.service());
                boolean stale = !existing.verified() && now - existing.createdAt() > UNVERIFIED_CLAIM_EXPIRES.toMillis();
                boolean sameTenantMove = existing.tenant().equals(claim.tenant());
                return sameService || (!stale && !sameTenantMove) ? Optional.empty() : Optional.of(claim.toJson());
            }).compose(updated -> updated.isPresent()
                    ? Future.succeededFuture(Optional.of(claim))
                    : get(claim.domain()).map(existing -> existing != null && existing.tenant().equals(claim.tenant())
                    && existing.service().equals(claim.service()) ? Optional.of(existing) : Optional.<Domain>empty()));
        });
    }

    @Override
    public Future<Optional<Domain>> markVerified(Domain domain, long now) {
        JsonObject expected = domain.toJson();
        return table.update(domain.domain(), current -> current.equals(expected) ? Optional.of(domain.asVerified(now).toJson()) : Optional.empty())
                .map(updated -> updated.map(Domain::fromJson));
    }

    @Override
    public Future<List<Domain>> forService(String tenant, String service) {
        return table.queryJson("SELECT json FROM DOMAINS WHERE tenant = ? AND service = ? ORDER BY _KEY", tenant, service)
                .map(rows -> rows.stream().map(Domain::fromJson).toList());
    }

    @Override
    public Future<Boolean> release(String domain, String tenant) {
        return get(domain).compose(existing -> existing == null || !existing.tenant().equals(tenant)
                ? Future.succeededFuture(false)
                : table.removeIfUnchanged(domain, existing.toJson()));
    }
}
