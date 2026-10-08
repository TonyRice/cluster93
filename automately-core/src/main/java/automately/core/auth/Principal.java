package automately.core.auth;

import java.util.Set;

/**
 * Who a request acts as: the admin (the admin token, or an operator's token), or a tenant through one of its tokens.
 * For a personal token (D-070), {@code user} is its user and {@code scopes} what that user may do now; {@code owner}
 * says they hold the tenant's {@code owner} role.
 */
public record Principal(boolean admin, String tenant, Set<String> scopes, String tokenId, String user, boolean owner) {

    public static final Principal ADMIN = new Principal(true, null, Set.of(), null);

    public Principal(boolean admin, String tenant, Set<String> scopes, String tokenId) {
        this(admin, tenant, scopes, tokenId, null, false);
    }

    /** An operator acting through their own token: the admin's powers, as a named person. */
    public static Principal operator(String user, String tokenId) {
        return new Principal(true, null, Set.of(), tokenId, user, false);
    }

    public boolean allows(String scope) {
        return !admin && Scopes.allows(scopes, scope);
    }

    /** Whether this caller may grant {@code wanted}: the admin may grant anything, others only what they hold. */
    public boolean covers(Set<String> wanted) {
        return admin || Scopes.covers(scopes, wanted);
    }

    /** Only owners, and operators, grant or take away {@code owner}. */
    public boolean ownerPowers() {
        return admin || owner;
    }

    /** How the audit log and records name this caller: the user, else the token, else "admin". */
    public String actor() {
        return user != null ? user : tokenId != null ? tokenId : "admin";
    }
}
