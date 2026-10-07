package automately.server.api;

import automately.core.auth.Scopes;
import io.vertx.core.http.HttpMethod;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What every {@code /v1} route needs (D-045). It fails closed: a request matching no rule is answered 404 before
 * any handler runs, so a route added without a rule can't be reached at all (and {@code AuthTest} names it).
 * A rule also says whether its route reads the request body, so the request stays paused for it.
 */
final class RouteScopes {

    /** Who may call: the admin only, any valid token, a tenant scope, or the admin or a tenant scope. */
    enum Who { ADMIN, ANY, TENANT, ADMIN_OR_TENANT }

    record Rule(Set<HttpMethod> methods, Pattern path, Who who, String scope, boolean readsBody) {

        boolean matches(HttpMethod method, String requestPath) {
            return methods.contains(method) && path.matcher(requestPath).matches();
        }
    }

    private static final String NAME = "[^/]+";

    static final List<Rule> RULES = List.of(
            rule("GET", "/v1/whoami", Who.ANY, null, false),

            rule("GET", "/v1/tenants", Who.ADMIN, null, false),
            rule("POST", "/v1/tenants", Who.ADMIN, null, true),
            rule("GET DELETE", "/v1/tenants/" + NAME, Who.ADMIN, null, false),
            rule("GET", "/v1/tenants/" + NAME + "/tokens", Who.ADMIN, null, false),
            rule("POST", "/v1/tenants/" + NAME + "/tokens", Who.ADMIN, null, true),
            rule("DELETE", "/v1/tenants/" + NAME + "/tokens/" + NAME, Who.ADMIN, null, false),
            rule("POST", "/v1/admin/rotate", Who.ADMIN, null, false),

            rule("GET", "/v1/nodes", Who.ADMIN_OR_TENANT, Scopes.NODES_READ, false),

            rule("GET", "/v1/tokens", Who.TENANT, Scopes.TOKENS_WRITE, false),
            rule("POST", "/v1/tokens", Who.TENANT, Scopes.TOKENS_WRITE, true),
            rule("DELETE", "/v1/tokens/" + NAME, Who.TENANT, Scopes.TOKENS_WRITE, false),

            rule("POST", "/v1/jobs", Who.TENANT, Scopes.JOBS_WRITE, true),
            rule("GET", "/v1/jobs(/" + NAME + "(/events|/steps)?)?", Who.TENANT, Scopes.JOBS_READ, false),
            rule("POST", "/v1/jobs/" + NAME + "/cancel", Who.TENANT, Scopes.JOBS_WRITE, false),

            rule("GET", "/v1/services(/" + NAME + "(/domains)?)?", Who.TENANT, Scopes.SERVICES_READ, false),
            rule("PUT", "/v1/services/" + NAME, Who.TENANT, Scopes.SERVICES_WRITE, true),
            rule("DELETE", "/v1/services/" + NAME, Who.TENANT, Scopes.SERVICES_WRITE, false),
            rule("POST", "/v1/services/" + NAME + "/(start|stop|restart)", Who.TENANT, Scopes.SERVICES_WRITE, false),
            rule("POST", "/v1/services/" + NAME + "/domains", Who.TENANT, Scopes.SERVICES_WRITE, true),
            rule("POST", "/v1/services/" + NAME + "/domains/" + NAME + "/verify", Who.TENANT, Scopes.SERVICES_WRITE, false),
            rule("DELETE", "/v1/services/" + NAME + "/domains/" + NAME, Who.TENANT, Scopes.SERVICES_WRITE, false),

            rule("GET", "/v1/files", Who.TENANT, Scopes.FILES_READ, false),
            rule("GET HEAD", "/v1/files/.+", Who.TENANT, Scopes.FILES_READ, false),
            rule("PUT", "/v1/files/.+", Who.TENANT, Scopes.FILES_WRITE, true),
            rule("DELETE", "/v1/files/.+", Who.TENANT, Scopes.FILES_WRITE, false),

            rule("POST", "/v1/bus/" + NAME + "/request", Who.TENANT, Scopes.BUS_REQUEST, true),
            rule("POST", "/v1/bus/" + NAME, Who.TENANT, Scopes.BUS_PUBLISH, true));

    private RouteScopes() {
    }

    private static Rule rule(String methods, String path, Who who, String scope, boolean readsBody) {
        Set<HttpMethod> parsed = Set.copyOf(java.util.Arrays.stream(methods.split(" ")).map(HttpMethod::valueOf).toList());
        return new Rule(parsed, Pattern.compile(path), who, scope, readsBody);
    }

    static Optional<Rule> find(HttpMethod method, String path) {
        String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return RULES.stream().filter(rule -> rule.matches(method, normalized)).findFirst();
    }
}
