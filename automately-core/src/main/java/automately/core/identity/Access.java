package automately.core.identity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A tenant's custom roles and groups, kept as one document so resolving a token reads it with one lookup (D-070).
 * Built-in roles aren't stored; see {@link Roles}. {@code owners} (user ids) is the guard that keeps a tenant from
 * losing its last owner: every change of who owns it goes through this document's compare-and-set.
 */
public record Access(Map<String, Role> roles, Map<String, Group> groups, Set<String> owners) {

    public static final Access EMPTY = new Access(Map.of(), Map.of(), Set.of());

    public Access {
        roles = Map.copyOf(roles);
        groups = Map.copyOf(groups);
        owners = Set.copyOf(owners);
    }

    /** A role: a named set of scopes. */
    public record Role(String name, Set<String> scopes, String description, boolean builtIn) {

        public Role {
            scopes = Set.copyOf(scopes);
        }

        public JsonObject toJson() {
            return new JsonObject().put("name", name).put("scopes", new JsonArray(new TreeSet<>(scopes).stream().toList()))
                    .put("description", description).put("builtIn", builtIn);
        }

        static Role fromJson(String name, JsonObject json) {
            return new Role(name, Set.copyOf(Member.strings(json.getJsonArray("scopes"))), json.getString("description"), false);
        }
    }

    /** A group: the roles its members get. Members list their groups on their membership. */
    public record Group(String name, java.util.List<String> roles, String description) {

        public Group {
            roles = java.util.List.copyOf(roles);
        }

        public JsonObject toJson() {
            return new JsonObject().put("name", name).put("roles", new JsonArray(roles)).put("description", description);
        }

        static Group fromJson(String name, JsonObject json) {
            return new Group(name, Member.strings(json.getJsonArray("roles")), json.getString("description"));
        }
    }

    Access withRole(Role role) {
        Map<String, Role> next = new TreeMap<>(roles);
        next.put(role.name(), role);
        return new Access(next, groups, owners);
    }

    Access withoutRole(String name) {
        Map<String, Role> next = new TreeMap<>(roles);
        next.remove(name);
        return new Access(next, groups, owners);
    }

    Access withGroup(Group group) {
        Map<String, Group> next = new TreeMap<>(groups);
        next.put(group.name(), group);
        return new Access(roles, next, owners);
    }

    Access withoutGroup(String name) {
        Map<String, Group> next = new TreeMap<>(groups);
        next.remove(name);
        return new Access(roles, next, owners);
    }

    Access withOwner(String user) {
        Set<String> next = new TreeSet<>(owners);
        next.add(user);
        return new Access(roles, groups, next);
    }

    Access withoutOwner(String user) {
        Set<String> next = new TreeSet<>(owners);
        next.remove(user);
        return new Access(roles, groups, next);
    }

    public JsonObject toJson() {
        JsonObject r = new JsonObject();
        new TreeMap<>(roles).forEach((name, role) -> r.put(name, new JsonObject().put("scopes", new JsonArray(new TreeSet<>(role.scopes()).stream().toList()))
                .put("description", role.description())));
        JsonObject g = new JsonObject();
        new TreeMap<>(groups).forEach((name, group) -> g.put(name, new JsonObject().put("roles", new JsonArray(group.roles()))
                .put("description", group.description())));
        return new JsonObject().put("roles", r).put("groups", g).put("owners", new JsonArray(new TreeSet<>(owners).stream().toList()));
    }

    public static Access fromJson(JsonObject json) {
        if (json == null) {
            return EMPTY;
        }
        Map<String, Role> roles = new TreeMap<>();
        json.getJsonObject("roles", new JsonObject()).forEach(e -> roles.put(e.getKey(), Role.fromJson(e.getKey(), (JsonObject) e.getValue())));
        Map<String, Group> groups = new TreeMap<>();
        json.getJsonObject("groups", new JsonObject()).forEach(e -> groups.put(e.getKey(), Group.fromJson(e.getKey(), (JsonObject) e.getValue())));
        return new Access(roles, groups, Set.copyOf(Member.strings(json.getJsonArray("owners"))));
    }
}
