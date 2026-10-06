package automately.core.host;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Custom domains, keyed by domain name, in a Vert.x {@link AsyncMap}. A domain belongs to one tenant at a time. */
public final class DomainStore {

    static final String MAP_NAME = "automately.domains";
    /** An unverified claim older than this can be taken over by another tenant, so a typo can't squat a domain forever. */
    public static final Duration UNVERIFIED_CLAIM_EXPIRES = Duration.ofHours(24);

    private final Future<AsyncMap<String, JsonObject>> map;

    public DomainStore(Vertx vertx) {
        this.map = vertx.sharedData().getAsyncMap(MAP_NAME);
    }

    public Future<Domain> get(String domain) {
        return map.compose(m -> m.get(domain)).map(json -> json == null ? null : Domain.fromJson(json));
    }

    /**
     * Claims a domain. Completes with the stored claim: the new one, the tenant's existing claim, or empty if
     * another tenant holds it (verified, or claimed less than {@link #UNVERIFIED_CLAIM_EXPIRES} ago).
     */
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

    public Future<Optional<Domain>> markVerified(Domain domain, long now) {
        return map.compose(m -> m.replaceIfPresent(domain.domain(), domain.toJson(), domain.asVerified(now).toJson())
                .map(replaced -> replaced ? Optional.of(domain.asVerified(now)) : Optional.<Domain>empty()));
    }

    public Future<List<Domain>> forService(String tenant, String service) {
        return map.compose(AsyncMap::values).map(values -> values.stream().map(Domain::fromJson)
                .filter(d -> d.tenant().equals(tenant) && d.service().equals(service))
                .sorted(Comparator.comparing(Domain::domain))
                .toList());
    }

    /** Removes the claim if this tenant owns it; completes with whether it did. */
    public Future<Boolean> release(String domain, String tenant) {
        return get(domain).compose(existing -> existing == null || !existing.tenant().equals(tenant)
                ? Future.succeededFuture(false)
                : map.compose(m -> m.removeIfPresent(domain, existing.toJson())));
    }
}
