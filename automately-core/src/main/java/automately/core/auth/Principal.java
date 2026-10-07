package automately.core.auth;

import java.util.Set;

/** Who a request acts as: the admin, or a tenant through one of its tokens. */
public record Principal(boolean admin, String tenant, Set<String> scopes, String tokenId) {

    public static final Principal ADMIN = new Principal(true, null, Set.of(), null);

    public boolean allows(String scope) {
        return !admin && Scopes.allows(scopes, scope);
    }
}
