package automately.server.ingress;

import automately.core.apps.AppDeployment;
import automately.core.apps.AppManifest;
import automately.core.apps.AppStore;
import automately.core.files.FileStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides what an ingress request to an app's host should do, before the ordinary service routing
 * (docs/design/apps-and-console.md). It maps a request's host (or the test headers {@code X-Automately-App}/
 * {@code X-Automately-Tenant}) to a tenant, an app and one of its deployments, enforces the console host's CIDR
 * allow-list, and evaluates the deployment's manifest routes: a proxy to a supervised service, a same-origin rewrite
 * to the local API, a static asset streamed from the VFS, or the SPA fallback to {@code /index.html}.
 *
 * <p>A host that names no deployed app is {@link Decision.NotAnApp}: the ingress falls back to ordinary service
 * routing, so app hosts and service hosts can share the ingress domain.
 */
public final class AppRouter {

    /** The console is the reserved {@code console} app in the {@code system} tenant (docs/design/apps-and-console.md). */
    public static final String CONSOLE_TENANT = "system";
    public static final String CONSOLE_APP = "console";
    /** The fallback document an app serves when the exact asset isn't there (client-side routing). */
    public static final String INDEX = "index.html";

    private final AppStore apps;
    private final FileStore files;
    private final String ingressDomain;
    private final String consoleDomain;
    private final CidrMatcher consoleAllowed;
    /** The personas this node plays: only a node with {@code console} serves the console app (docs/design/node-roles.md). */
    private final Set<String> roles;

    /**
     * @param consoleDomain the host bound to the console, or null to disable console-host handling
     * @param consoleAllowed the networks allowed to reach the console host, or null for no restriction
     * @param roles the personas this node plays; the console app is only served when this contains {@code console}
     */
    public AppRouter(AppStore apps, FileStore files, String ingressDomain, String consoleDomain, CidrMatcher consoleAllowed,
                     Set<String> roles) {
        this.apps = apps;
        this.files = files;
        this.ingressDomain = ingressDomain == null ? "" : ingressDomain.toLowerCase(Locale.ROOT);
        this.consoleDomain = consoleDomain == null || consoleDomain.isBlank() ? null : consoleDomain.trim().toLowerCase(Locale.ROOT);
        this.consoleAllowed = consoleAllowed == null ? CidrMatcher.parse(List.of()) : consoleAllowed;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** One request's host, method, path and query, the client address, and lower-cased headers. */
    public record Request(String host, String method, String path, String query, String remoteAddress, JsonObject headers) {

        public Request {
            headers = headers == null ? new JsonObject() : headers;
        }
    }

    /** What the ingress should do with the request. */
    public sealed interface Decision {

        /** No app serves this host; fall back to ordinary service routing. */
        record NotAnApp() implements Decision {
        }

        /** Refused (the console host and a client outside its allow-list). */
        record Denied(String message) implements Decision {
        }

        /** Refused for another reason, with the status to answer. */
        record Error(int status, String message) implements Decision {
        }

        /** Proxy to a supervised service in the app's tenant. */
        record Proxy(String tenant, String service, String uri) implements Decision {
        }

        /** Same-origin rewrite to this node's own API. */
        record Api(String uri) implements Decision {
        }

        /** Stream a VFS file (path is relative to the store), with the manifest's headers. */
        record Static(String tenant, String path, JsonObject headers) implements Decision {
        }

        /** Answer with a redirect (a manifest route with a 3xx status). */
        record Redirect(int status, String location, JsonObject headers) implements Decision {
        }
    }

    /** Resolves the host, then the deployment, then the manifest routes. */
    public Future<Decision> route(Request request) {
        String host = normalise(request.host());
        if (consoleDomain != null && host.equals(consoleDomain)) {
            return console(request);
        }
        // Test headers name the app directly, so a test can exercise app routing without DNS.
        String headerApp = header(request, "x-automately-app");
        String headerTenant = header(request, "x-automately-tenant");
        if (headerApp != null && headerTenant != null) {
            return evaluate(resolve(headerTenant, headerApp, null), request);
        }
        // Local development: with no console domain, a console-role node answers on the machine's own bare host.
        if (consoleDomain == null && isLocalConsoleHost(host)) {
            return console(request);
        }
        Resolved fromHost = fromHost(host);
        if (fromHost == null) {
            return Future.succeededFuture(new Decision.NotAnApp());
        }
        return evaluate(resolve(fromHost.tenant(), fromHost.app(), fromHost.deploymentId()), request);
    }

    /**
     * The reserved console app, but only on a node that plays the {@code console} role (docs/design/node-roles.md);
     * a node without it refuses the console host, so the request falls through to ordinary service routing.
     */
    private Future<Decision> console(Request request) {
        if (!roles.contains("console")) {
            return Future.succeededFuture(new Decision.NotAnApp());
        }
        if (!consoleAllowed.matches(request.remoteAddress())) {
            return Future.succeededFuture(new Decision.Denied("The console is restricted to its allowed networks"));
        }
        return evaluate(resolve(CONSOLE_TENANT, CONSOLE_APP, null), request);
    }

    /** A bare host that names no tenant or app: {@code localhost} or the loopback IPv4 address, as in local development. */
    private static boolean isLocalConsoleHost(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    private Future<Decision> evaluate(Future<AppDeployment> resolved, Request request) {
        return resolved.compose(deployment -> deployment == null
                ? Future.succeededFuture(new Decision.NotAnApp())
                : evaluate(deployment, request));
    }

    private Future<Decision> evaluate(AppDeployment deployment, Request request) {
        AppManifest manifest = deployment.manifest();
        for (AppManifest.RouteRule route : manifest.routes()) {
            Matcher matcher = matcher(route.src(), request.path());
            if (matcher == null) {
                continue;
            }
            String dest = matcher.replaceAll(route.dest());
            if (dest.startsWith("service:")) {
                String rest = dest.substring("service:".length());
                int slash = rest.indexOf('/');
                String service = rest.substring(0, slash);
                return Future.succeededFuture(new Decision.Proxy(deployment.tenant(), service,
                        withQuery(rest.substring(slash), request.query())));
            }
            if (dest.startsWith("api:")) {
                return Future.succeededFuture(new Decision.Api(withQuery(dest.substring("api:".length()), request.query())));
            }
            JsonObject headers = staticHeaders(manifest, route, request.path());
            if (route.status() != null && route.status() >= 300 && route.status() < 400) {
                return Future.succeededFuture(new Decision.Redirect(route.status(), dest, headers));
            }
            return staticAsset(deployment, dest, request, headers);
        }
        // Nothing matched: serve the request path as an asset, with the SPA fallback when it isn't there.
        return staticAsset(deployment, request.path(), request, staticHeaders(manifest, null, request.path()));
    }

    /** The deployment for an app: a named one (a preview), or the app's active one. */
    private Future<AppDeployment> resolve(String tenant, String app, String deploymentId) {
        if (deploymentId != null) {
            return apps.getDeployment(tenant, app, deploymentId);
        }
        return apps.getApp(tenant, app).compose(record -> record == null || record.activeDeploymentId() == null
                ? Future.succeededFuture(null)
                : apps.getDeployment(tenant, app, record.activeDeploymentId()));
    }

    /** A static asset from the deployment's VFS prefix, falling back to {@code /index.html} when it isn't there. */
    private Future<Decision> staticAsset(AppDeployment deployment, String path, Request request, JsonObject headers) {
        String relative = relative(path);
        String invalid = FileStore.invalidPath(relative);
        if (invalid != null) {
            return Future.succeededFuture(new Decision.Error(400, invalid));
        }
        String full = vfsPath(deployment, relative);
        return files.stat(deployment.tenant(), full).compose(file -> {
            if (file != null) {
                return Future.succeededFuture(new Decision.Static(deployment.tenant(), full, headers));
            }
            if (relative.equals(INDEX)) {
                return Future.succeededFuture(new Decision.Error(404, "No such app asset: " + request.path()));
            }
            String index = vfsPath(deployment, INDEX);
            return files.stat(deployment.tenant(), index).map(fallback -> fallback != null
                    ? (Decision) new Decision.Static(deployment.tenant(), index, headers)
                    : (Decision) new Decision.Error(404, "No such app asset: " + request.path()));
        });
    }

    /** The manifest's header rules matching {@code path}, plus the route's own, lower-cased for the file responder. */
    private static JsonObject staticHeaders(AppManifest manifest, AppManifest.RouteRule route, String path) {
        JsonObject headers = new JsonObject();
        for (AppManifest.HeaderRule rule : manifest.headers()) {
            if (matcher(rule.source(), path) != null) {
                rule.headers().forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value));
            }
        }
        if (route != null) {
            route.headers().forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value));
        }
        return headers;
    }

    private record Resolved(String tenant, String app, String deploymentId) {
    }

    /**
     * The app a host names: the preview domain {@code <deploymentId>--<app>--<tenant>.<ingressDomain>}, or the
     * canonical {@code <app>--<tenant>.<ingressDomain>}; null for any other host.
     */
    private Resolved fromHost(String host) {
        String suffix = "." + ingressDomain;
        if (ingressDomain.isEmpty() || !host.endsWith(suffix)) {
            return null;
        }
        String label = host.substring(0, host.length() - suffix.length());
        if (label.isEmpty() || label.contains(".")) {
            return null;
        }
        String[] parts = label.split("--", -1);
        if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()) {
            return new Resolved(parts[2], parts[1], parts[0]);
        }
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return new Resolved(parts[1], parts[0], null);
        }
        return null;
    }

    private static Matcher matcher(String pattern, String path) {
        try {
            Matcher matcher = Pattern.compile(pattern).matcher(path);
            return matcher.matches() ? matcher : null;
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** A request or manifest path as a store path: no leading slash, no query, {@code index.html} for the root. */
    private static String relative(String path) {
        String value = path == null ? "" : path;
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.isEmpty() ? INDEX : value;
    }

    /** The store path for an asset under a deployment's prefix (which is written with a leading slash). */
    private static String vfsPath(AppDeployment deployment, String relative) {
        String prefix = deployment.vfsPrefix() == null ? "" : deployment.vfsPrefix();
        String joined = prefix.endsWith("/") ? prefix + relative : prefix + "/" + relative;
        return joined.replaceFirst("^/+", "");
    }

    private static String withQuery(String path, String query) {
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    private static String header(Request request, String name) {
        String value = request.headers().getString(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A host as it is compared: lower case, without a port or a trailing dot; IPv6 literals unwrapped. */
    static String normalise(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close >= 0 ? value.substring(1, close) : value;
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') == colon) {
            value = value.substring(0, colon);
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
