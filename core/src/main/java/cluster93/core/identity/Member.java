package cluster93.core.identity;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/** A user's membership of a tenant (D-070): the roles granted to them directly, and the groups they're in. {@code user} is the user's id. */
public record Member(String tenant, String user, List<String> roles, List<String> groups, long joinedAt, String invitedBy) {

    public Member {
        roles = List.copyOf(roles);
        groups = List.copyOf(groups);
    }

    static String key(String tenant, String user) {
        return tenant + "/" + user;
    }

    String key() {
        return key(tenant, user);
    }

    boolean owner() {
        return roles.contains(Roles.OWNER);
    }

    Member with(List<String> newRoles, List<String> newGroups) {
        return new Member(tenant, user, newRoles, newGroups, joinedAt, invitedBy);
    }

    public JsonObject toJson() {
        return new JsonObject().put("tenant", tenant).put("user", user).put("roles", new JsonArray(roles)).put("groups", new JsonArray(groups))
                .put("joinedAt", joinedAt).put("invitedBy", invitedBy);
    }

    public static Member fromJson(JsonObject json) {
        return new Member(json.getString("tenant"), json.getString("user"), strings(json.getJsonArray("roles")),
                strings(json.getJsonArray("groups")), json.getLong("joinedAt", 0L), json.getString("invitedBy"));
    }

    static List<String> strings(JsonArray array) {
        return array == null ? List.of() : array.stream().map(String::valueOf).toList();
    }
}
