package cluster93.core.identity;

import io.vertx.core.json.JsonObject;

/**
 * A person (D-070). Global: one account, joined to tenants by memberships. {@code id} never changes; {@code name} is
 * their handle, unique, and can be changed. An {@code operator} manages the cluster as the admin token does; a
 * {@code disabled} user's tokens all stop working.
 */
public record User(String id, String name, String displayName, String email, boolean operator, boolean disabled, long createdAt) {

    public JsonObject toJson() {
        return new JsonObject().put("id", id).put("name", name).put("displayName", displayName).put("email", email).put("operator", operator)
                .put("disabled", disabled).put("createdAt", createdAt);
    }

    public static User fromJson(JsonObject json) {
        return new User(json.getString("id"), json.getString("name"), json.getString("displayName"), json.getString("email"),
                json.getBoolean("operator", false), json.getBoolean("disabled", false), json.getLong("createdAt", 0L));
    }

    User withOperator(boolean value) {
        return new User(id, name, displayName, email, value, disabled, createdAt);
    }

    User withDisabled(boolean value) {
        return new User(id, name, displayName, email, operator, value, createdAt);
    }

    User withProfile(String newDisplayName, String newEmail) {
        return new User(id, name, newDisplayName, newEmail, operator, disabled, createdAt);
    }

    User withName(String newName) {
        return new User(id, newName, displayName, email, operator, disabled, createdAt);
    }
}
