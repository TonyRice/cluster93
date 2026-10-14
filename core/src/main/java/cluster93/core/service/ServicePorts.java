package cluster93.core.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a service's server ports are reached (D-112, D-123): by default each port has its own host
 * ({@code <service>--<tenant>--<port>}) and the first server answers the service's host. A service's {@code ports} can
 * add a path prefix under the service's host ({@code "path": "/admin"}, stripped before the server sees it and sent as
 * {@code X-Forwarded-Prefix}), or take a port off the network ({@code "expose": false}). Custom domains point at a port
 * when they're claimed.
 */
public final class ServicePorts {

    /** @param path the prefix under the service's host, or null */
    public record Port(int port, String path, boolean expose) {
    }

    /** A request under a port's path prefix: the port, the prefix, and the URI the server sees. */
    public record Match(int port, String prefix, String uri) {
    }

    public static final int MAX_PORTS = 32;
    private static final Pattern PATH = Pattern.compile("^(/[A-Za-z0-9._~!$&'()*+,;=:@%-]+)+$");

    private ServicePorts() {
    }

    /** What's wrong with a {@code ports} value, or null if it's fine (or absent). */
    public static String invalid(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof JsonArray list)) {
            return "\"ports\" must be a list such as [{\"port\": 3001, \"path\": \"/admin\"}]";
        }
        if (list.size() > MAX_PORTS) {
            return "\"ports\" may declare at most " + MAX_PORTS + " ports";
        }
        Set<Integer> seen = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof JsonObject entry)) {
                return "Each entry of \"ports\" must be an object such as {\"port\": 3001}";
            }
            for (String key : entry.fieldNames()) {
                if (!Set.of("port", "path", "expose").contains(key)) {
                    return "A port takes \"port\", \"path\" and \"expose\", not \"" + key + "\"";
                }
            }
            if (!(entry.getValue("port") instanceof Integer port) || port < 1 || port > 65535) {
                return "Each port needs \"port\": a number from 1 to 65535";
            }
            if (!seen.add(port)) {
                return "Port " + port + " is declared twice";
            }
            Object path = entry.getValue("path");
            if (path != null && (!(path instanceof String p) || p.length() > 200 || !PATH.matcher(p).matches()
                    || p.contains("/./") || p.endsWith("/.") || p.contains("/../") || p.endsWith("/.."))) {
                return "\"path\" must be a prefix such as /admin: no trailing slash, no . or .. segments";
            }
            if (path != null && !paths.add(((String) path).toLowerCase(java.util.Locale.ROOT))) {
                return "The path " + path + " is used by two ports";
            }
            Object expose = entry.getValue("expose");
            if (expose != null && !(expose instanceof Boolean)) {
                return "\"expose\" must be true or false";
            }
        }
        return null;
    }

    public static List<Port> of(JsonArray ports) {
        List<Port> out = new ArrayList<>();
        if (ports == null) {
            return out;
        }
        for (Object item : ports) {
            JsonObject entry = (JsonObject) item;
            out.add(new Port(entry.getInteger("port"), entry.getString("path"), entry.getBoolean("expose", true)));
        }
        return out;
    }

    /** The declared port, or null if it isn't declared (it then has its default host). */
    public static Port declared(List<Port> ports, int port) {
        for (Port p : ports) {
            if (p.port() == port) {
                return p;
            }
        }
        return null;
    }

    /** For a request to the service's host: the port whose prefix it's under (the longest), or null. */
    public static Match match(List<Port> ports, String uri) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        Port best = null;
        for (Port p : ports) {
            if (p.path() != null && (path.equals(p.path()) || path.startsWith(p.path() + "/"))
                    && (best == null || p.path().length() > best.path().length())) {
                best = p;
            }
        }
        if (best == null) {
            return null;
        }
        String rest = uri.substring(best.path().length());
        return new Match(best.port(), best.path(), rest.isEmpty() || rest.startsWith("?") ? "/" + rest : rest);
    }
}
