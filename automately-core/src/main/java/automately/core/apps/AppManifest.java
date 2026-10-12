package automately.core.apps;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The parsed {@code automately.json} of an app: how its static assets are served, which requests reach a backend
 * service, and the headers each source is answered with. See docs/design/apps-and-console.md.
 *
 * @param name    the app's name: lowercase letters, digits and hyphens (e.g. {@code my-app})
 * @param version the app's version, or null when it declares none
 * @param routes  the rewrite/proxy rules in order; empty for an app with no routing
 * @param backend the backend service to run, or null for a pure static app
 * @param headers response-header rules, e.g. long-lived caching for hashed assets; empty when none are declared
 */
public record AppManifest(String name, String version, List<RouteRule> routes, BackendConfig backend,
                          List<HeaderRule> headers) {

    /** An app name is lowercase alphanumeric with inner hyphens, at most 64 characters. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?");
    /** A service name follows the same shape as a service definition's name. */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");
    private static final String SERVICE_SCHEME = "service:";
    private static final String API_SCHEME = "api:";
    private static final int DEFAULT_MEMORY_MB = 128;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    public AppManifest {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid app name \"" + name
                    + "\": use lowercase letters, digits and inner hyphens, at most 64 characters");
        }
        version = version == null || version.isBlank() ? null : version;
        routes = routes == null ? List.of() : List.copyOf(routes);
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    /**
     * Parses and validates an {@code automately.json} document.
     *
     * @throws IllegalArgumentException when the JSON is malformed, the name is invalid, or a route or backend is
     *                                  inconsistent
     */
    public static AppManifest parse(String jsonString) {
        Objects.requireNonNull(jsonString, "jsonString");
        JsonObject json;
        try {
            json = new JsonObject(jsonString);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("automately.json is not valid JSON: " + e.getMessage(), e);
        }
        return parse(json);
    }

    /**
     * Parses and validates an {@code automately.json} document.
     *
     * @throws IllegalArgumentException as {@link #parse(String)}
     */
    public static AppManifest parse(JsonObject json) {
        Objects.requireNonNull(json, "json");
        String name = string(json, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("automately.json needs a \"name\"");
        }
        return new AppManifest(name, string(json, "version"), routes(arrayOf(json, "routes")),
                backend(nestedObject(json, "backend", "Backend")), headerRules(arrayOf(json, "headers")));
    }

    /** Reads a manifest previously written by {@link #toJson()}. */
    public static AppManifest fromJson(JsonObject json) {
        return parse(json);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject().put("name", name);
        if (version != null) {
            json.put("version", version);
        }
        if (!routes.isEmpty()) {
            json.put("routes", new JsonArray(routes.stream().map(RouteRule::toJson).toList()));
        }
        if (backend != null) {
            json.put("backend", backend.toJson());
        }
        if (!headers.isEmpty()) {
            json.put("headers", new JsonArray(headers.stream().map(HeaderRule::toJson).toList()));
        }
        return json;
    }

    private static List<RouteRule> routes(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<RouteRule> routes = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.getValue(i);
            if (!(value instanceof JsonObject route)) {
                throw new IllegalArgumentException("Route " + i + " is not an object");
            }
            try {
                routes.add(new RouteRule(string(route, "src"), string(route, "dest"),
                        integer(route, "status", null), stringMap(nestedObject(route, "headers", "Route headers"))));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Route " + i + ": " + e.getMessage(), e);
            }
        }
        return routes;
    }

    private static BackendConfig backend(JsonObject json) {
        if (json == null) {
            return null;
        }
        try {
            return new BackendConfig(string(json, "entry"), integer(json, "memoryMb", DEFAULT_MEMORY_MB),
                    integer(json, "timeoutMs", DEFAULT_TIMEOUT_MS),
                    stringMap(nestedObject(json, "env", "Backend env")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Backend: " + e.getMessage(), e);
        }
    }

    private static List<HeaderRule> headerRules(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<HeaderRule> rules = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.getValue(i);
            if (!(value instanceof JsonObject rule)) {
                throw new IllegalArgumentException("Header rule " + i + " is not an object");
            }
            try {
                rules.add(new HeaderRule(string(rule, "source"),
                        stringMap(nestedObject(rule, "headers", "Header rule headers"))));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Header rule " + i + ": " + e.getMessage(), e);
            }
        }
        return rules;
    }

    /** The named field as a string, or null when it is absent or not a string. */
    private static String string(JsonObject json, String field) {
        Object value = json.getValue(field);
        return value instanceof String text ? text : null;
    }

    /** The named field as an integer, or {@code fallback} when it is absent. */
    private static Integer integer(JsonObject json, String field, Integer fallback) {
        Object value = json.getValue(field);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("\"" + field + "\" must be a number");
    }

    /** The named field as an array, or null when absent. */
    private static JsonArray arrayOf(JsonObject json, String field) {
        Object value = json.getValue(field);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new IllegalArgumentException("\"" + field + "\" must be an array");
    }

    /** The named field as an object, or null when absent. */
    private static JsonObject nestedObject(JsonObject json, String field, String what) {
        Object value = json.getValue(field);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonObject nested) {
            return nested;
        }
        throw new IllegalArgumentException(what + " must be an object");
    }

    private static Map<String, String> stringMap(JsonObject json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : json.getMap().entrySet()) {
            map.put(entry.getKey(), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * A rewrite rule: requests matching {@code src} are served from {@code dest}.
     *
     * @param src     the request path or pattern to match, e.g. {@code /api/(.*)} or {@code .*}
     * @param dest    where matched requests go: {@code service:<name>/<path>} to a supervised service,
     *                {@code api:/v1/<path>} to the platform's own REST API, or a VFS path such as
     *                {@code /index.html} for the static SPA fallback
     * @param status  an HTTP status to answer with (such as a 3xx redirect), or null to serve the destination directly
     * @param headers extra response headers for matched requests; empty when none
     */
    public record RouteRule(String src, String dest, Integer status, Map<String, String> headers) {

        public RouteRule {
            if (src == null || src.isBlank()) {
                throw new IllegalArgumentException("needs a \"src\" pattern");
            }
            if (dest == null || dest.isBlank()) {
                throw new IllegalArgumentException("needs a \"dest\"");
            }
            String problem = destinationProblem(dest);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
            if (status != null && (status < 100 || status > 599)) {
                throw new IllegalArgumentException("\"status\" must be an HTTP status between 100 and 599");
            }
            headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        /** Why {@code dest} is not a destination, or null when it is one. */
        private static String destinationProblem(String dest) {
            if (dest.startsWith(SERVICE_SCHEME)) {
                String rest = dest.substring(SERVICE_SCHEME.length());
                int slash = rest.indexOf('/');
                if (slash <= 0 || slash == rest.length() - 1) {
                    return "\"dest\" must be service:<service-name>/<path>, such as service:shop-api/$1";
                }
                if (!SERVICE_NAME.matcher(rest.substring(0, slash)).matches()) {
                    return "\"dest\" names an invalid service: " + rest.substring(0, slash);
                }
                return null;
            }
            if (dest.startsWith(API_SCHEME)) {
                String rest = dest.substring(API_SCHEME.length());
                if (rest.length() < 2 || !rest.startsWith("/")) {
                    return "\"dest\" must be api:/v1/<path>, such as api:/v1/$1";
                }
                return null;
            }
            if (dest.startsWith("/")) {
                return null;
            }
            return "\"dest\" must be service:<name>/<path>, api:/v1/<path> or a path such as /index.html";
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject().put("src", src).put("dest", dest);
            if (status != null) {
                json.put("status", status);
            }
            if (!headers.isEmpty()) {
                json.put("headers", new JsonObject(new LinkedHashMap<>(headers)));
            }
            return json;
        }
    }

    /**
     * The backend service an app declares: a supervised Automately service running its {@code entry} script.
     *
     * @param entry     the script the service runs, e.g. {@code server.js}
     * @param memoryMb  the service's memory limit in megabytes
     * @param timeoutMs how long a run may idle before it is timed out
     * @param env       the {@code process.env} declaration its runs share; empty when none
     */
    public record BackendConfig(String entry, int memoryMb, int timeoutMs, Map<String, String> env) {

        public BackendConfig {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("needs an \"entry\" script");
            }
            if (memoryMb <= 0) {
                throw new IllegalArgumentException("\"memoryMb\" must be positive");
            }
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("\"timeoutMs\" must be positive");
            }
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject().put("entry", entry).put("memoryMb", memoryMb).put("timeoutMs", timeoutMs);
            if (!env.isEmpty()) {
                json.put("env", new JsonObject(new LinkedHashMap<>(env)));
            }
            return json;
        }
    }

    /**
     * A response-header rule: requests matching {@code source} are answered with {@code headers}, such as
     * {@code Cache-Control: public, max-age=31536000, immutable} for hashed build assets.
     *
     * @param source  the request path or pattern to match
     * @param headers the headers to set; empty when none
     */
    public record HeaderRule(String source, Map<String, String> headers) {

        public HeaderRule {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("needs a \"source\" pattern");
            }
            headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject().put("source", source);
            if (!headers.isEmpty()) {
                json.put("headers", new JsonObject(new LinkedHashMap<>(headers)));
            }
            return json;
        }
    }
}
