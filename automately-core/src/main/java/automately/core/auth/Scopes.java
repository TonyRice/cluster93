package automately.core.auth;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What a tenant token may do (D-045). {@code *} grants every tenant scope. */
public final class Scopes {

    public static final String ALL = "*";
    public static final String JOBS_READ = "jobs:read";
    public static final String JOBS_WRITE = "jobs:write";
    public static final String SERVICES_READ = "services:read";
    public static final String SERVICES_WRITE = "services:write";
    public static final String FILES_READ = "files:read";
    public static final String FILES_WRITE = "files:write";
    public static final String BUS_PUBLISH = "bus:publish";
    public static final String BUS_REQUEST = "bus:request";
    public static final String QUEUES_READ = "queues:read";
    public static final String QUEUES_WRITE = "queues:write";
    public static final String TOKENS_WRITE = "tokens:write";
    public static final String NODES_READ = "nodes:read";
    public static final String STORAGE_READ = "storage:read";
    public static final String STORAGE_WRITE = "storage:write";
    public static final String SECRETS_READ = "secrets:read";
    public static final String SECRETS_WRITE = "secrets:write";
    /** Scripts of jobs this token submits, and of services it defines, may read the tenant's secrets (D-068). */
    public static final String SECRETS_USE = "secrets:use";
    /** Members, custom roles, groups and invites (D-070). */
    public static final String MEMBERS_READ = "members:read";
    public static final String MEMBERS_WRITE = "members:write";
    /** The tenant's audit log (D-071). */
    public static final String AUDIT_READ = "audit:read";
    /** Packages: seeing and importing them, and publishing them (D-083). */
    public static final String PACKAGES_READ = "packages:read";
    public static final String PACKAGES_WRITE = "packages:write";

    /** Every scope a tenant token can hold, besides {@link #ALL}. */
    public static final List<String> KNOWN = List.of(JOBS_READ, JOBS_WRITE, SERVICES_READ, SERVICES_WRITE, FILES_READ, FILES_WRITE,
            BUS_PUBLISH, BUS_REQUEST, QUEUES_READ, QUEUES_WRITE, TOKENS_WRITE, NODES_READ, STORAGE_READ, STORAGE_WRITE,
            SECRETS_READ, SECRETS_WRITE, SECRETS_USE, MEMBERS_READ, MEMBERS_WRITE, AUDIT_READ, PACKAGES_READ, PACKAGES_WRITE);

    private Scopes() {
    }

    public static boolean allows(Set<String> granted, String needed) {
        return granted.contains(ALL) || granted.contains(needed);
    }

    /** Whether every scope in {@code requested} is also allowed by {@code granted}: a token can only mint narrower tokens. */
    public static boolean covers(Set<String> granted, Set<String> requested) {
        if (granted.contains(ALL)) {
            return true;
        }
        return !requested.contains(ALL) && granted.containsAll(requested);
    }

    /** Null if every scope is known, otherwise the first unknown one. */
    public static String unknown(Collection<String> scopes) {
        for (String scope : scopes) {
            if (!scope.equals(ALL) && !KNOWN.contains(scope)) {
                return scope;
            }
        }
        return null;
    }

    public static Set<String> of(Collection<String> scopes) {
        return Set.copyOf(new LinkedHashSet<>(scopes));
    }
}
