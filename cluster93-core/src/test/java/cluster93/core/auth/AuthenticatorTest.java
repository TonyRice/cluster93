package cluster93.core.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatorTest {

    private static final String CONFIGURED_ADMIN = "configured-admin-token-0123456789abcdef";

    private Vertx vertx;
    private AuthStore store;
    private final SecureRandom random = new SecureRandom();

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        store = new SharedDataAuthStore(vertx);
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    private String mint(String tenant, Set<String> scopes, Long expiresAt) throws Exception {
        String secret = TokenFormat.generate(random);
        await(store.saveToken(new ApiToken("tok_" + tenant, tenant, "test", scopes, TokenFormat.hash(secret), 1, expiresAt, null)));
        return secret;
    }

    @Test
    void aTenantTokenBecomesItsTenantAndScopes() throws Exception {
        Authenticator auth = new Authenticator(store, null);
        String token = mint("acme", Set.of(Scopes.JOBS_READ), null);

        Principal principal = await(auth.authenticate("Bearer " + token));

        assertThat(principal.admin()).isFalse();
        assertThat(principal.tenant()).isEqualTo("acme");
        assertThat(principal.allows(Scopes.JOBS_READ)).isTrue();
        assertThat(principal.allows(Scopes.JOBS_WRITE)).isFalse();
        assertThat(await(auth.authenticate("bearer " + token)).tenant()).as("the scheme is case-insensitive").isEqualTo("acme");
    }

    @Test
    void missingUnknownRevokedAndExpiredTokensAreRefused() throws Exception {
        Authenticator auth = new Authenticator(store, null);
        String expired = mint("old", Set.of(Scopes.ALL), System.currentTimeMillis() - 1);
        String revoked = mint("gone", Set.of(Scopes.ALL), null);
        await(store.revokeToken("gone", "tok_gone"));

        for (String header : new String[] {null, "", "Basic abc", "Bearer ", "Bearer " + TokenFormat.generate(random), "Bearer " + revoked,
                "Bearer " + expired}) {
            assertThatThrownBy(() -> await(auth.authenticate(header))).as(String.valueOf(header))
                    .isInstanceOf(ExecutionException.class).cause().isInstanceOf(AuthFailure.class)
                    .satisfies(e -> assertThat(((AuthFailure) e).status()).isEqualTo(401));
        }
    }

    @Test
    void aConfiguredAdminTokenIsTheAdmin() throws Exception {
        Authenticator auth = new Authenticator(store, CONFIGURED_ADMIN);

        assertThat(await(auth.authenticate("Bearer " + CONFIGURED_ADMIN)).admin()).isTrue();
        assertThat(await(auth.ensureAdminToken(random))).as("nothing generated").isEmpty();
        assertThat(await(store.adminHash())).isNull();
        assertThatThrownBy(() -> await(auth.rotateAdminToken(random))).cause().isInstanceOf(AuthFailure.class);
        assertThat(await(auth.authenticate("Bearer " + CONFIGURED_ADMIN)).allows(Scopes.JOBS_READ)).as("admin has no tenant scopes").isFalse();
    }

    @Test
    void withoutConfigurationOneNodeGeneratesTheAdminTokenAndOthersAgree() throws Exception {
        Authenticator first = new Authenticator(store, null);
        Authenticator second = new Authenticator(store, null);

        Optional<String> generated = await(first.ensureAdminToken(random));
        Optional<String> again = await(second.ensureAdminToken(random));

        assertThat(generated).isPresent();
        assertThat(TokenFormat.looksValid(generated.get())).isTrue();
        assertThat(again).as("only the first node logs it").isEmpty();
        assertThat(await(second.authenticate("Bearer " + generated.get())).admin()).isTrue();

        String rotated = await(first.rotateAdminToken(random));
        assertThat(await(second.authenticate("Bearer " + rotated)).admin()).isTrue();
        assertThatThrownBy(() -> await(second.authenticate("Bearer " + generated.get()))).isInstanceOf(ExecutionException.class);
    }

    @Test
    void lastUseIsRecordedButNotOnEveryRequest() throws Exception {
        Authenticator auth = new Authenticator(store, null);
        String token = mint("acme", Set.of(Scopes.ALL), null);

        await(auth.authenticate("Bearer " + token));
        Thread.sleep(50);
        Long firstUse = await(store.tokenByHash(TokenFormat.hash(token))).lastUsedAt();
        await(auth.authenticate("Bearer " + token));
        Thread.sleep(50);

        assertThat(firstUse).isNotNull();
        assertThat(await(store.tokenByHash(TokenFormat.hash(token))).lastUsedAt()).isEqualTo(firstUse);
    }

    @Test
    void scopesOnlyNarrow() {
        assertThat(Scopes.covers(Set.of(Scopes.ALL), Set.of(Scopes.ALL))).isTrue();
        assertThat(Scopes.covers(Set.of(Scopes.JOBS_READ, Scopes.TOKENS_WRITE), Set.of(Scopes.JOBS_READ))).isTrue();
        assertThat(Scopes.covers(Set.of(Scopes.JOBS_READ, Scopes.TOKENS_WRITE), Set.of(Scopes.JOBS_WRITE))).isFalse();
        assertThat(Scopes.covers(Set.of(Scopes.JOBS_READ, Scopes.TOKENS_WRITE), Set.of(Scopes.ALL))).isFalse();
        assertThat(Scopes.unknown(Set.of(Scopes.JOBS_READ, "root"))).isEqualTo("root");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
