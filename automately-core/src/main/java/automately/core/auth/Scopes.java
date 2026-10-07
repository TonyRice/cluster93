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
    public static final String TOKENS_WRITE = "tokens:write";
    public static final String NODES_READ = "nodes:read";

    /** Every scope a tenant token can hold, besides {@link #ALL}. */
    public static final List<String> KNOWN = List.of(JOBS_READ, JOBS_WRITE, SERVICES_READ, SERVICES_WRITE, FILES_READ, FILES_WRITE,
            BUS_PUBLISH, BUS_REQUEST, TOKENS_WRITE, NODES_READ);

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
