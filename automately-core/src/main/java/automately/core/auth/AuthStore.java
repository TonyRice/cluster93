package automately.core.auth;

import io.vertx.core.Future;

import java.util.List;

/** Tenants, their tokens, and the admin token's hash, kept in the cluster's database (D-044, D-045). */
public interface AuthStore {

    /** Completes with false if the tenant already exists. */
    Future<Boolean> createTenant(Tenant tenant);

    /** Null if there's no such tenant. */
    Future<Tenant> tenant(String name);

    /** Every tenant, by name. */
    Future<List<Tenant>> tenants();

    /** Removes the tenant and every token it has; completes with whether it existed. */
    Future<Boolean> deleteTenant(String name);

    Future<Void> saveToken(ApiToken token);

    /** Null if no token has this hash. */
    Future<ApiToken> tokenByHash(String hash);

    /** A tenant's tokens, oldest first. */
    Future<List<ApiToken>> tokens(String tenant);

    /** Revokes (deletes) one of the tenant's tokens; completes with whether it existed. */
    Future<Boolean> revokeToken(String tenant, String id);

    /** Records when a token was last used. */
    Future<Void> touchToken(String hash, long now);

    /** The admin token's hash, or null if none was ever generated. */
    Future<String> adminHash();

    /** Stores {@code hash} unless one exists; completes with the stored hash, so racing nodes agree. */
    Future<String> adminHashIfAbsent(String hash);

    Future<Void> replaceAdminHash(String hash);
}
