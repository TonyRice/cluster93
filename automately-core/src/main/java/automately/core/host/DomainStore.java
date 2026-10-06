package automately.core.host;

import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Custom domains claimed by services. A domain belongs to one tenant at a time. */
public interface DomainStore {

    /** An unverified claim older than this can be taken over by another tenant, so a typo can't squat a domain forever. */
    Duration UNVERIFIED_CLAIM_EXPIRES = Duration.ofHours(24);

    /** Completes with null if nobody has claimed it. */
    Future<Domain> get(String domain);

    /**
     * Claims a domain. Completes with the stored claim: the new one, the tenant's existing claim, or empty if
     * another tenant holds it (verified, or claimed less than {@link #UNVERIFIED_CLAIM_EXPIRES} ago).
     */
    Future<Optional<Domain>> claim(Domain claim, long now);

    Future<Optional<Domain>> markVerified(Domain domain, long now);

    Future<List<Domain>> forService(String tenant, String service);

    /** Removes the claim if this tenant owns it; completes with whether it did. */
    Future<Boolean> release(String domain, String tenant);
}
