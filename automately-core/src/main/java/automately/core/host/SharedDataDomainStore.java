package automately.core.host;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@link DomainStore} in a Vert.x {@link AsyncMap}, keyed by domain name. For development and tests. */
public final class SharedDataDomainStore implements DomainStore {

    static final String MAP_NAME = "automately.domains";

    private final Future<AsyncMap<String, JsonObject>> map;

    public SharedDataDomainStore(Vertx vertx) {
        this.map = vertx.sharedData().getAsyncMap(MAP_NAME);
    }

    @Override
    public Future<Domain> get(String domain) {
        return map.compose(m -> m.get(domain)).map(json -> json == null ? null : Domain.fromJson(json));
    }

    @Override
    public Future<Optional<Domain>> claim(Domain claim, long now) {
        return map.compose(m -> m.putIfAbsent(claim.domain(), claim.toJson()).compose(existingJson -> {
            if (existingJson == null) {
                return Future.succeededFuture(Optional.of(claim));
            }
            Domain existing = Domain.fromJson(existingJson);
            if (existing.tenant().equals(claim.tenant()) && existing.service().equals(claim.service())) {
                return Future.succeededFuture(Optional.of(existing));
            }
            boolean stale = !existing.verified() && now - existing.createdAt() > UNVERIFIED_CLAIM_EXPIRES.toMillis();
            boolean sameTenantMove = existing.tenant().equals(claim.tenant());
            if (!stale && !sameTenantMove) {
                return Future.succeededFuture(Optional.empty());
            }
            return m.replaceIfPresent(claim.domain(), existingJson, claim.toJson())
                    .map(replaced -> replaced ? Optional.of(claim) : Optional.<Domain>empty());
        }));
    }

    @Override
    public Future<Optional<Domain>> markVerified(Domain domain, long now) {
        return map.compose(m -> m.replaceIfPresent(domain.domain(), domain.toJson(), domain.asVerified(now).toJson())
                .map(replaced -> replaced ? Optional.of(domain.asVerified(now)) : Optional.<Domain>empty()));
    }

    @Override
    public Future<List<Domain>> forService(String tenant, String service) {
        return map.compose(AsyncMap::values).map(values -> values.stream().map(Domain::fromJson)
                .filter(d -> d.tenant().equals(tenant) && d.service().equals(service))
                .sorted(Comparator.comparing(Domain::domain))
                .toList());
    }

    @Override
    public Future<Boolean> release(String domain, String tenant) {
        return get(domain).compose(existing -> existing == null || !existing.tenant().equals(tenant)
                ? Future.succeededFuture(false)
                : map.compose(m -> m.removeIfPresent(domain, existing.toJson())));
    }
}
