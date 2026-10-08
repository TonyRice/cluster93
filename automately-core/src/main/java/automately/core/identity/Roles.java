package automately.core.identity;

import automately.core.auth.Scopes;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The built-in roles, and what a member may do: the scopes of their direct roles and their groups' roles (D-070). */
public final class Roles {

    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String DEVELOPER = "developer";
    public static final String VIEWER = "viewer";

    public static final Map<String, Access.Role> BUILT_IN;

    static {
        Map<String, Access.Role> roles = new LinkedHashMap<>();
        roles.put(OWNER, new Access.Role(OWNER, Set.of(Scopes.ALL),
                "Everything, and owner powers: only owners grant or remove owner, and a tenant always keeps one", true));
        roles.put(ADMIN, new Access.Role(ADMIN, Set.of(Scopes.ALL), "Everything except owner powers", true));
        roles.put(DEVELOPER, new Access.Role(DEVELOPER, Set.of(Scopes.JOBS_READ, Scopes.JOBS_WRITE, Scopes.SERVICES_READ, Scopes.SERVICES_WRITE,
                Scopes.FILES_READ, Scopes.FILES_WRITE, Scopes.BUS_PUBLISH, Scopes.BUS_REQUEST, Scopes.QUEUES_READ, Scopes.QUEUES_WRITE,
                Scopes.SECRETS_READ, Scopes.SECRETS_USE, Scopes.NODES_READ, Scopes.STORAGE_READ, Scopes.MEMBERS_READ),
                "Builds and runs things: jobs, services, files, bus and queues; scripts may use secrets but not set them", true));
        roles.put(VIEWER, new Access.Role(VIEWER, Set.of(Scopes.JOBS_READ, Scopes.SERVICES_READ, Scopes.FILES_READ, Scopes.QUEUES_READ,
                Scopes.SECRETS_READ, Scopes.NODES_READ, Scopes.STORAGE_READ, Scopes.MEMBERS_READ),
                "Reads everything except the audit log; changes nothing", true));
        BUILT_IN = java.util.Collections.unmodifiableMap(roles);
    }

    private Roles() {
    }

    /** A role by name, built-in or the tenant's own; null if there's none. */
    static Access.Role find(String name, Access access) {
        Access.Role builtIn = BUILT_IN.get(name);
        return builtIn != null ? builtIn : access.roles().get(name);
    }

    /** The scopes these roles give together; just {@code *} if any gives everything. Unknown roles give nothing. */
    static Set<String> scopes(Collection<String> roles, Access access) {
        Set<String> scopes = new HashSet<>();
        for (String name : roles) {
            Access.Role role = find(name, access);
            if (role != null) {
                scopes.addAll(role.scopes());
            }
        }
        return scopes.contains(Scopes.ALL) ? Set.of(Scopes.ALL) : Set.copyOf(scopes);
    }

    /** Every role a member has: direct, and through their groups. */
    static Set<String> rolesOf(Member member, Access access) {
        Set<String> roles = new HashSet<>(member.roles());
        for (String group : member.groups()) {
            Access.Group found = access.groups().get(group);
            if (found != null) {
                roles.addAll(found.roles());
            }
        }
        return roles;
    }

    /** What a member may do now. */
    public static Set<String> scopes(Member member, Access access) {
        return scopes(rolesOf(member, access), access);
    }

    /** What a personal token may do: its own scopes ({@code *} meaning all of the user's), within the user's. */
    static Set<String> intersect(Set<String> token, Set<String> user) {
        if (token.contains(Scopes.ALL)) {
            return user;
        }
        if (user.contains(Scopes.ALL)) {
            return token;
        }
        Set<String> both = new HashSet<>(token);
        both.retainAll(user);
        return Set.copyOf(both);
    }

    /** {@code a} and {@code b} together, for "the caller must cover both the old and the new". */
    static Set<String> union(Set<String> a, Set<String> b) {
        if (a.contains(Scopes.ALL) || b.contains(Scopes.ALL)) {
            return Set.of(Scopes.ALL);
        }
        Set<String> both = new HashSet<>(a);
        both.addAll(b);
        return Set.copyOf(both);
    }
}
