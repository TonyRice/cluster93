package automately.core.auth;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Behaviour every {@link AuthStore} must have; each implementation's test extends this. */
public abstract class AuthStoreContract {

    /** Set by the implementation's test before each test. */
    protected AuthStore store;

    private static ApiToken token(String id, String tenant, String hash, long createdAt) {
        return new ApiToken(id, tenant, "ci", Set.of(Scopes.JOBS_READ), hash, createdAt, null, null);
    }

    @Test
    public void tenantsAreCreatedOnce() throws Exception {
        assertThat(await(store.createTenant(new Tenant("acme", 1)))).isTrue();
        assertThat(await(store.createTenant(new Tenant("acme", 2)))).isFalse();
        await(store.createTenant(new Tenant("globex", 3)));

        assertThat(await(store.tenant("acme"))).isEqualTo(new Tenant("acme", 1));
        assertThat(await(store.tenant("nobody"))).isNull();
        assertThat(await(store.tenants())).extracting(Tenant::name).containsExactly("acme", "globex");
    }

    @Test
    public void tokensAreFoundByHashAndListedPerTenant() throws Exception {
        await(store.saveToken(token("tok_2", "acme", "h2", 20)));
        await(store.saveToken(token("tok_1", "acme", "h1", 10)));
        await(store.saveToken(token("tok_3", "globex", "h3", 30)));

        assertThat(await(store.tokenByHash("h1")).id()).isEqualTo("tok_1");
        assertThat(await(store.tokenByHash("nope"))).isNull();
        assertThat(await(store.tokens("acme"))).extracting(ApiToken::id).containsExactly("tok_1", "tok_2");
        assertThat(await(store.tokens("globex"))).extracting(ApiToken::id).containsExactly("tok_3");
    }

    @Test
    public void revokingIsPerTenant() throws Exception {
        await(store.saveToken(token("tok_1", "acme", "h1", 10)));

        assertThat(await(store.revokeToken("globex", "tok_1"))).as("another tenant can't").isFalse();
        assertThat(await(store.tokenByHash("h1"))).isNotNull();
        assertThat(await(store.revokeToken("acme", "tok_1"))).isTrue();
        assertThat(await(store.tokenByHash("h1"))).isNull();
        assertThat(await(store.revokeToken("acme", "tok_1"))).isFalse();
    }

    @Test
    public void deletingATenantRemovesItsTokens() throws Exception {
        await(store.createTenant(new Tenant("acme", 1)));
        await(store.saveToken(token("tok_1", "acme", "h1", 10)));
        await(store.saveToken(token("tok_2", "globex", "h2", 10)));

        assertThat(await(store.deleteTenant("acme"))).isTrue();
        assertThat(await(store.tenant("acme"))).isNull();
        assertThat(await(store.tokenByHash("h1"))).isNull();
        assertThat(await(store.tokenByHash("h2"))).isNotNull();
        assertThat(await(store.deleteTenant("acme"))).isFalse();
    }

    @Test
    public void touchingRecordsLastUse() throws Exception {
        await(store.saveToken(token("tok_1", "acme", "h1", 10)));
        await(store.touchToken("h1", 99));
        await(store.touchToken("missing", 99));

        assertThat(await(store.tokenByHash("h1")).lastUsedAt()).isEqualTo(99L);
    }

    @Test
    public void theFirstAdminHashWins() throws Exception {
        assertThat(await(store.adminHash())).isNull();
        assertThat(await(store.adminHashIfAbsent("first"))).isEqualTo("first");
        assertThat(await(store.adminHashIfAbsent("second"))).isEqualTo("first");
        await(store.replaceAdminHash("rotated"));
        assertThat(await(store.adminHash())).isEqualTo("rotated");
    }

    protected static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
