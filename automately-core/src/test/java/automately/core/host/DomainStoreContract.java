package automately.core.host;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link DomainStore} must have; each implementation's test extends this. */
public abstract class DomainStoreContract {

    /** Set by the implementation's test before each test. */
    protected DomainStore store;

    private static Domain claim(String domain, String tenant, String service, long createdAt) {
        return new Domain(domain, tenant, service, "token-" + tenant, false, createdAt, null);
    }

    @Test
    public void aDomainBelongsToOneTenant() throws Exception {
        long now = 1_000_000;
        assertThat(await(store.claim(claim("shop.example.com", "acme", "web", now), now))).isPresent();

        assertThat(await(store.claim(claim("shop.example.com", "globex", "web", now), now + 1))).as("held by acme").isEmpty();
        assertThat(await(store.claim(claim("shop.example.com", "acme", "web", now + 5), now + 5)))
                .as("the same claim again returns the existing one").get().extracting(Domain::createdAt).isEqualTo(now);
    }

    @Test
    public void staleUnverifiedClaimsCanBeTakenOver() throws Exception {
        long then = 1_000_000;
        await(store.claim(claim("shop.example.com", "acme", "web", then), then));
        long later = then + DomainStore.UNVERIFIED_CLAIM_EXPIRES.toMillis() + 1;

        Optional<Domain> taken = await(store.claim(claim("shop.example.com", "globex", "web", later), later));

        assertThat(taken).get().extracting(Domain::tenant).isEqualTo("globex");
    }

    @Test
    public void verifiedClaimsAreNeverTakenOver() throws Exception {
        long then = 1_000_000;
        Domain claimed = await(store.claim(claim("shop.example.com", "acme", "web", then), then)).orElseThrow();
        await(store.markVerified(claimed, then + 10));
        long later = then + DomainStore.UNVERIFIED_CLAIM_EXPIRES.toMillis() * 10;

        assertThat(await(store.claim(claim("shop.example.com", "globex", "web", later), later))).isEmpty();
        assertThat(await(store.get("shop.example.com")).verified()).isTrue();
    }

    @Test
    public void listsAndReleasesPerService() throws Exception {
        await(store.claim(claim("a.example.com", "acme", "web", 1), 1));
        await(store.claim(claim("b.example.com", "acme", "web", 1), 1));
        await(store.claim(claim("c.example.com", "acme", "api", 1), 1));

        assertThat(await(store.forService("acme", "web"))).extracting(Domain::domain).containsExactly("a.example.com", "b.example.com");
        assertThat(await(store.release("a.example.com", "globex"))).as("not globex's").isFalse();
        assertThat(await(store.release("a.example.com", "acme"))).isTrue();
        assertThat(await(store.get("a.example.com"))).isNull();
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
