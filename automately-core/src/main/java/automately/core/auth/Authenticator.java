package automately.core.auth;

import io.vertx.core.Future;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns an {@code Authorization} header into a {@link Principal} (D-044, D-045). Every lookup goes to the store, so a
 * revoked token stops working on the next request, on every node. {@code lastUsedAt} is written at most once per
 * {@link #TOUCH_EVERY} per token per node, not on every request.
 */
public final class Authenticator {

    public static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    /** The shortest admin token accepted from {@code AUTOMATELY_ADMIN_TOKEN}. */
    public static final int MIN_ADMIN_TOKEN_LENGTH = 32;

    private final AuthStore store;
    private final String configuredAdminHash;
    private final java.util.function.Function<ApiToken, Future<Principal>> personal;
    private final Map<String, Long> touched = new ConcurrentHashMap<>();

    /** {@code configuredAdminToken} is {@code AUTOMATELY_ADMIN_TOKEN}, or null to use the generated one. */
    public Authenticator(AuthStore store, String configuredAdminToken) {
        this(store, configuredAdminToken, token -> Future.failedFuture(AuthFailure.unauthenticated("Personal tokens aren't enabled")));
    }

    /** {@code personal} works out who a personal token (one with a user) acts as now (D-070). */
    public Authenticator(AuthStore store, String configuredAdminToken, java.util.function.Function<ApiToken, Future<Principal>> personal) {
        this.store = store;
        this.configuredAdminHash = configuredAdminToken == null ? null : TokenFormat.hash(configuredAdminToken);
        this.personal = personal;
    }

    public boolean adminTokenConfigured() {
        return configuredAdminHash != null;
    }

    public Future<Principal> authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() <= 7) {
            return Future.failedFuture(AuthFailure.unauthenticated("Send a token: Authorization: Bearer atly_…"));
        }
        String token = authorization.substring(7).trim();
        String hash = TokenFormat.hash(token);
        long now = System.currentTimeMillis();
        Future<ApiToken> found = TokenFormat.looksValid(token) ? store.tokenByHash(hash) : Future.succeededFuture(null);
        return found.compose(apiToken -> {
            if (apiToken != null) {
                if (apiToken.expired(now)) {
                    return Future.failedFuture(AuthFailure.unauthenticated("This token expired"));
                }
                touch(apiToken, now);
                return apiToken.user() != null
                        ? personal.apply(apiToken)
                        : Future.succeededFuture(new Principal(false, apiToken.tenant(), apiToken.scopes(), apiToken.id()));
            }
            return adminHash().compose(admin -> TokenFormat.sameHash(admin, hash)
                    ? Future.succeededFuture(Principal.ADMIN)
                    : Future.failedFuture(AuthFailure.unauthenticated("Unknown or revoked token")));
        });
    }

    private Future<String> adminHash() {
        return configuredAdminHash != null ? Future.succeededFuture(configuredAdminHash) : store.adminHash();
    }

    private void touch(ApiToken token, long now) {
        Long last = touched.get(token.hash());
        if (last == null || now - last >= TOUCH_EVERY.toMillis()) {
            touched.put(token.hash(), now);
            store.touchToken(token.hash(), now);
        }
    }

    /**
     * Without {@code AUTOMATELY_ADMIN_TOKEN}, the first node of a cluster generates the admin token. Completes with it
     * if this node generated it (to be logged once), or empty if it was configured or already existed.
     */
    public Future<Optional<String>> ensureAdminToken(SecureRandom random) {
        if (configuredAdminHash != null) {
            return Future.succeededFuture(Optional.empty());
        }
        String candidate = TokenFormat.generate(random);
        String hash = TokenFormat.hash(candidate);
        return store.adminHashIfAbsent(hash).map(stored -> stored.equals(hash) ? Optional.of(candidate) : Optional.empty());
    }

    /** Replaces a generated admin token. A configured one is changed by changing the environment instead. */
    public Future<String> rotateAdminToken(SecureRandom random) {
        if (configuredAdminHash != null) {
            return Future.failedFuture(new AuthFailure(409, "conflict",
                    "The admin token comes from AUTOMATELY_ADMIN_TOKEN; change it there and restart the nodes"));
        }
        String token = TokenFormat.generate(random);
        return store.replaceAdminHash(TokenFormat.hash(token)).map(token);
    }
}
