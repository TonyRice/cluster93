package automately.core.identity;

import automately.core.auth.ApiToken;
import automately.core.auth.AuthFailure;
import automately.core.auth.AuthStore;
import automately.core.auth.Principal;
import automately.core.auth.Scopes;
import automately.core.auth.TokenFormat;
import automately.core.host.crypto.Kdf;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Users, memberships, roles, groups and invites (D-070): who a personal token belongs to, and what they may do now.
 * Every change follows one rule against escalation: a caller grants only what they hold themselves, covering both the
 * old and the new; only owners (and operators) grant or remove {@code owner}; a tenant keeps at least one owner, which
 * the tenant's access document guards atomically. Users are stored by an id that never changes, so names can.
 * Failures are {@link AuthFailure}s carrying the HTTP status and code to answer with.
 */
public final class Identity {

    /** The pseudo-tenant of operator invites and operators' own tokens. Not a valid tenant name. */
    public static final String OPERATORS = "@operators";

    public static final Records.Kind USERS = new Records.Kind("USERS", List.of());
    public static final Records.Kind USER_NAMES = new Records.Kind("USER_NAMES", List.of());
    public static final Records.Kind MEMBERS = new Records.Kind("MEMBERS", List.of("tenant", "user"));
    public static final Records.Kind ACCESS = new Records.Kind("TENANT_ACCESS", List.of());
    public static final Records.Kind INVITES = new Records.Kind("INVITES", List.of("tenant", "id"));
    /** Password credentials, by user id (D-133). */
    public static final Records.Kind CREDENTIALS = new Records.Kind("CREDENTIALS", List.of());
    /** Every collection identity keeps, for stores that create tables up front. */
    public static final List<Records.Kind> KINDS = List.of(USERS, USER_NAMES, MEMBERS, ACCESS, INVITES, CREDENTIALS);

    public static final String USER_RULE = "lowercase letters, digits, '_' and '-', up to 39 characters, starting with a letter or digit";
    public static final String NAME_RULE = "lowercase letters, digits, '_' and '-', up to 63 characters, starting with a letter or digit";
    private static final Pattern USER_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,38}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

    public static final int MAX_CUSTOM_ROLES = 100;
    public static final int MAX_GROUPS = 200;
    public static final int MAX_ROLES_EACH = 20;
    public static final int MAX_GROUPS_EACH = 50;
    public static final int MAX_OPEN_INVITES = 100;
    public static final int DEFAULT_INVITE_DAYS = 7;
    public static final int MAX_INVITE_DAYS = 30;
    public static final int MAX_TOKEN_DAYS = 3650;
    private static final String INVITE_GONE = "No such invite: it was used, withdrawn or expired";

    private final Records users;
    private final Records names;
    private final Records members;
    private final Records access;
    private final Records invites;
    private final Records credentials;
    private final AuthStore tokens;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();

    public Identity(Records.Factory records, AuthStore tokens) {
        this(records, tokens, System::currentTimeMillis);
    }

    public Identity(Records.Factory records, AuthStore tokens, LongSupplier clock) {
        this.users = records.open(USERS);
        this.names = records.open(USER_NAMES);
        this.members = records.open(MEMBERS);
        this.access = records.open(ACCESS);
        this.invites = records.open(INVITES);
        this.credentials = records.open(CREDENTIALS);
        this.tokens = tokens;
        this.clock = clock;
    }

    // ---- Resolving personal tokens ----

    /** Who a personal token acts as now: an operator, or a member with their current roles' scopes. Fails with 401. */
    public Future<Principal> resolve(ApiToken token) {
        return userById(token.user()).compose(user -> {
            if (user == null) {
                return Future.failedFuture(AuthFailure.unauthenticated("This token's user no longer exists"));
            }
            if (user.disabled()) {
                return Future.failedFuture(AuthFailure.unauthenticated("The user " + user.name() + " is disabled"));
            }
            if (OPERATORS.equals(token.tenant())) {
                return user.operator()
                        ? Future.succeededFuture(Principal.operator(user.id(), user.name(), token.id()))
                        : Future.failedFuture(AuthFailure.unauthenticated(user.name() + " is no longer an operator"));
            }
            return membership(token.tenant(), user.id()).compose(member -> {
                if (member == null) {
                    return Future.failedFuture(AuthFailure.unauthenticated(user.name() + " is no longer a member of " + token.tenant()));
                }
                return access(token.tenant()).map(acc -> new Principal(false, token.tenant(),
                        Roles.intersect(token.scopes(), Roles.scopes(member, acc)), token.id(), user.id(), user.name(), member.owner()));
            });
        });
    }

    // ---- Passwords (D-133) ----

    /** scrypt cost for stored passwords: N=16,384, r=8, p=1, a 16-byte salt and a 64-byte hash. */
    private static final int SCRYPT_N = 16_384;
    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 64;
    private static final String INVALID_LOGIN = "Invalid username or password";

    /** Sets (or replaces) a user's password, storing a salt and a scrypt hash, never the password itself. */
    public Future<Void> setPassword(String userId, String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = Kdf.scrypt(password.getBytes(StandardCharsets.UTF_8), salt, HASH_BYTES, SCRYPT_N, SCRYPT_R, SCRYPT_P,
                Kdf.MAX_SCRYPT_MEMORY);
        JsonObject credential = new JsonObject()
                .put("userId", userId)
                .put("saltHex", HexFormat.of().formatHex(salt))
                .put("hashHex", HexFormat.of().formatHex(hash))
                .put("updatedAt", clock.getAsLong());
        return credentials.put(userId, credential);
    }

    /** Whether a user has password credentials configured (D-133). */
    public Future<Boolean> hasPassword(String userId) {
        if (userId == null) {
            return Future.succeededFuture(false);
        }
        return credentials.get(userId).map(c -> c != null);
    }

    /**
     * Sets or changes a user's password (D-133). If a password is already configured, currentPassword
     * must match; if no password was previously set, currentPassword is not required.
     */
    public Future<Void> updatePassword(String userId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            return failed(invalid("A new password is required"));
        }
        return credentials.get(userId).compose(credential -> {
            if (credential != null) {
                if (currentPassword == null || currentPassword.isBlank()) {
                    return failed(AuthFailure.unauthenticated("Current password is required"));
                }
                byte[] salt = HexFormat.of().parseHex(credential.getString("saltHex"));
                byte[] expected = HexFormat.of().parseHex(credential.getString("hashHex"));
                byte[] candidate = Kdf.scrypt(currentPassword.getBytes(StandardCharsets.UTF_8), salt, expected.length, SCRYPT_N,
                        SCRYPT_R, SCRYPT_P, Kdf.MAX_SCRYPT_MEMORY);
                if (!MessageDigest.isEqual(candidate, expected)) {
                    return failed(AuthFailure.unauthenticated("Current password is incorrect"));
                }
            }
            return setPassword(userId, newPassword);
        });
    }

    /**
     * Exchanges a username and password for the user (D-133). Answers 401, with one message for every reason, whenever
     * the user doesn't exist, is disabled, has no password, or the password doesn't match; the comparison is constant
     * time.
     */
    public Future<User> authenticate(String username, String password) {
        return user(username).compose(user -> user == null || user.disabled() || password == null
                ? failed(AuthFailure.unauthenticated(INVALID_LOGIN))
                : credentials.get(user.id()).compose(credential -> {
                    if (credential == null) {
                        return failed(AuthFailure.unauthenticated(INVALID_LOGIN));
                    }
                    byte[] salt = HexFormat.of().parseHex(credential.getString("saltHex"));
                    byte[] expected = HexFormat.of().parseHex(credential.getString("hashHex"));
                    byte[] candidate = Kdf.scrypt(password.getBytes(StandardCharsets.UTF_8), salt, expected.length, SCRYPT_N,
                            SCRYPT_R, SCRYPT_P, Kdf.MAX_SCRYPT_MEMORY);
                    return MessageDigest.isEqual(candidate, expected)
                            ? Future.succeededFuture(user)
                            : failed(AuthFailure.unauthenticated(INVALID_LOGIN));
                }));
    }

    // ---- Users ----

    /** By name; null if there's none. */
    public Future<User> user(String name) {
        if (name == null) {
            return Future.succeededFuture();
        }
        return names.get(name).compose(json -> json == null ? Future.succeededFuture() : userById(json.getString("id")))
                .map(user -> user != null && user.name().equals(name) ? user : null);
    }

    /** By id; null if there's none. */
    public Future<User> userById(String id) {
        return id == null ? Future.succeededFuture() : users.get(id).map(json -> json == null ? null : User.fromJson(json));
    }

    public Future<List<User>> users() {
        return users.all().map(rows -> rows.stream().map(User::fromJson).sorted(Comparator.comparing(User::name)).toList());
    }

    /**
     * An operator renames, enables or disables a user, or makes or unmakes an operator. Losing either takes effect on
     * the next request; a rename keeps everything, since memberships and tokens hold the user's id.
     */
    public Future<User> updateUser(String name, String newName, Boolean disabled, Boolean operator) {
        return existing(name).compose(user -> (newName == null ? Future.succeededFuture(user) : rename(user, newName)).compose(renamed ->
                users.update(renamed.id(), json -> {
                            User current = User.fromJson(json);
                            if (disabled != null) {
                                current = current.withDisabled(disabled);
                            }
                            if (operator != null) {
                                current = current.withOperator(operator);
                            }
                            return Optional.of(current.toJson());
                        })
                        .map(updated -> User.fromJson(updated.orElseThrow()))
                        .compose(updated -> updated.operator() ? Future.succeededFuture(updated) : revokeTokens(OPERATORS, updated.id()).map(updated))));
    }

    /**
     * Deletes a user, their memberships and their personal tokens; refused while they're a tenant's last owner.
     * Completes with the service tokens they made, which keep working, for the caller to decide about.
     */
    public Future<List<JsonObject>> deleteUser(String name) {
        return existing(name).compose(user -> memberships(user.id()).compose(owned -> {
            List<String> ownedTenants = owned.stream().filter(Member::owner).map(Member::tenant).toList();
            return releaseOwnerships(ownedTenants, user.id()).compose(v -> {
                Future<Void> removed = revokeTokens(OPERATORS, user.id());
                for (Member member : owned) {
                    removed = removed.compose(v2 -> members.remove(member.key()).compose(x -> revokeTokens(member.tenant(), user.id())));
                }
                return removed.compose(v2 -> users.remove(user.id()))
                        .compose(v2 -> names.remove(user.name()))
                        .compose(v2 -> madeBy(owned.stream().map(Member::tenant).toList(), user.id()));
            });
        }));
    }

    // ---- You ----

    /** A user, with each tenant they belong to, whether they have a password, and what they may do there. */
    public Future<JsonObject> me(String id) {
        return userById(id).compose(user -> {
            if (user == null) {
                return failed(notFound("No such user"));
            }
            return hasPassword(id).compose(hasPass -> memberships(id).compose(owned -> {
                List<Future<JsonObject>> views = owned.stream().map(m -> access(m.tenant()).map(acc -> view(m, user.name(), acc))).toList();
                return Future.all(views).map(all -> user.toJson()
                        .put("hasPassword", hasPass)
                        .put("memberships",
                                new JsonArray(views.stream().map(Future::result).toList())));
            }));
        });
    }

    /** A user changes their own name, display name or email. */
    public Future<User> updateProfile(String id, String newName, String displayName, String email) {
        try {
            checkProfile(displayName, email);
        } catch (AuthFailure e) {
            return Future.failedFuture(e);
        }
        return userById(id).compose(user -> {
            if (user == null) {
                return failed(notFound("No such user"));
            }
            return (newName == null ? Future.succeededFuture(user) : rename(user, newName)).compose(renamed ->
                    users.update(id, json -> Optional.of(User.fromJson(json).withProfile(displayName, email).toJson()))
                            .map(updated -> User.fromJson(updated.orElseThrow())));
        });
    }

    /** A user's personal tokens, in every tenant (and as an operator). */
    public Future<List<ApiToken>> tokensOf(String user) {
        return memberships(user).compose(owned -> {
            List<String> tenants = new ArrayList<>(owned.stream().map(Member::tenant).toList());
            tenants.add(OPERATORS);
            List<Future<List<ApiToken>>> lists = tenants.stream().map(tokens::tokens).toList();
            return Future.all(lists).map(all -> lists.stream().flatMap(f -> f.result().stream()).filter(t -> user.equals(t.user()))
                    .sorted(Comparator.comparingLong(ApiToken::createdAt).thenComparing(ApiToken::id)).toList());
        });
    }

    /**
     * A new personal token for one of the caller's tenants (or, for an operator, {@link #OPERATORS}). Its scopes must be
     * within what the caller's roles give them there; {@code *} means "whatever my roles give me", now and later.
     */
    public Future<Minted> createPersonalToken(Principal caller, String tenant, String name, Set<String> scopes, Long days) {
        if (caller.user() == null) {
            return failed(forbidden("Only a user's own token can make personal tokens"));
        }
        String unknown = Scopes.unknown(scopes);
        if (scopes.isEmpty() || unknown != null) {
            return failed(invalid(unknown != null ? "Unknown scope \"" + unknown + "\"" : "\"scopes\" can't be empty"));
        }
        Future<Void> allowed;
        if (OPERATORS.equals(tenant)) {
            allowed = userById(caller.user()).compose(user -> user != null && user.operator() ? Future.succeededFuture()
                    : failed(forbidden("Only operators can make operator tokens")));
        } else {
            allowed = membership(tenant, caller.user()).compose(member -> {
                if (member == null) {
                    return failed(forbidden("You're not a member of " + tenant));
                }
                return access(tenant).compose(acc -> scopes.contains(Scopes.ALL) || Scopes.covers(Roles.scopes(member, acc), scopes)
                        ? Future.succeededFuture()
                        : failed(forbidden("Your roles in " + tenant + " don't give all of " + scopes)));
            });
        }
        return allowed.compose(v -> {
            Minted minted = mint(tenant, name, scopes, days, caller.user(), caller.user());
            return tokens.saveToken(minted.token()).map(minted);
        });
    }

    /** Revokes one of a user's own personal tokens; completes with whether there was one. */
    public Future<Boolean> revokePersonalToken(String user, String id) {
        return tokensOf(user).compose(owned -> owned.stream().filter(t -> t.id().equals(id)).findFirst()
                .map(t -> tokens.revokeToken(t.tenant(), id))
                .orElse(Future.succeededFuture(false)));
    }

    /** A token and its secret, which is shown once. */
    public record Minted(ApiToken token, String secret) {
        public JsonObject toJson() {
            return token.toViewJson().put("token", secret);
        }
    }

    /** A new token (not yet saved). {@code user} is null for a service token; {@code createdBy} is the maker's id. */
    public Minted mint(String tenant, String name, Set<String> scopes, Long days, String user, String createdBy) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw invalid("\"name\" must be 1 to 100 characters, saying what the token is for");
        }
        if (days != null && (days < 1 || days > MAX_TOKEN_DAYS)) {
            throw invalid("\"expiresInDays\" must be between 1 and " + MAX_TOKEN_DAYS);
        }
        long now = clock.getAsLong();
        String secret = TokenFormat.generate(random);
        ApiToken token = new ApiToken("tok_" + hex(8), tenant, trimmed, Set.copyOf(scopes), TokenFormat.hash(secret), now,
                days == null ? null : now + Duration.ofDays(days).toMillis(), null, user, createdBy);
        return new Minted(token, secret);
    }

    /**
     * Tokens as the API shows them, with their maker's name, and {@code makerGone} for a service token whose maker is
     * no longer an enabled member (or operator): it keeps working, but someone should decide about it.
     */
    public Future<List<JsonObject>> tokenViews(String tenant, List<ApiToken> list) {
        Set<String> makers = new HashSet<>();
        list.forEach(t -> {
            if (isUserId(t.createdBy())) {
                makers.add(t.createdBy());
            }
        });
        Map<String, Future<JsonObject>> standing = new java.util.HashMap<>();
        makers.forEach(id -> standing.put(id, standing(tenant, id)));
        return Future.all(List.copyOf(standing.values())).map(all -> list.stream().map(token -> {
            JsonObject view = token.toViewJson();
            JsonObject maker = isUserId(token.createdBy()) ? standing.get(token.createdBy()).result() : null;
            if (maker != null) {
                view.put("createdByName", maker.getString("name"));
                if (token.user() == null) {
                    view.put("makerGone", !maker.getBoolean("active"));
                }
            }
            return view;
        }).toList());
    }

    /** {name, active}: whether a user still stands behind tokens they made in this tenant. */
    private Future<JsonObject> standing(String tenant, String id) {
        return userById(id).compose(user -> {
            if (user == null) {
                return Future.succeededFuture(new JsonObject().put("name", (String) null).put("active", false));
            }
            if (user.disabled()) {
                return Future.succeededFuture(new JsonObject().put("name", user.name()).put("active", false));
            }
            return membership(tenant, id).map(member -> new JsonObject().put("name", user.name()).put("active", member != null || user.operator()));
        });
    }

    /** The service tokens a user made in these tenants that still work. */
    private Future<List<JsonObject>> madeBy(List<String> tenants, String user) {
        List<Future<List<ApiToken>>> lists = tenants.stream().map(tokens::tokens).toList();
        return Future.all(lists).map(all -> lists.stream().flatMap(f -> f.result().stream())
                .filter(t -> t.user() == null && user.equals(t.createdBy()))
                .map(ApiToken::toViewJson).toList());
    }

    // ---- Members ----

    public Future<List<JsonObject>> members(String tenant) {
        return members.where("tenant", tenant).compose(rows -> access(tenant).compose(acc -> {
            List<Member> all = rows.stream().map(Member::fromJson).toList();
            List<Future<User>> found = all.stream().map(m -> userById(m.user())).toList();
            return Future.all(found).map(v -> {
                List<JsonObject> views = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    User user = found.get(i).result();
                    views.add(view(all.get(i), user == null ? null : user.name(), acc));
                }
                views.sort(Comparator.comparing(json -> String.valueOf(json.getString("user"))));
                return views;
            });
        }));
    }

    /** One member, with what they may do now; fails 404 if they aren't one. */
    public Future<JsonObject> member(String tenant, String name) {
        return memberNamed(tenant, name).compose(found -> access(tenant).map(acc -> view(found.member(), name, acc)));
    }

    /** Sets a member's direct roles and groups. New members come in through invites. */
    public Future<JsonObject> setMember(Principal caller, String tenant, String name, List<String> roles, List<String> groups) {
        return memberNamed(tenant, name).compose(found -> access(tenant).compose(acc -> {
            Member old = found.member();
            checkGrants(roles, groups, acc);
            Member next = old.with(distinct(roles), distinct(groups));
            checkCovers(caller, Roles.union(Roles.scopes(old, acc), Roles.scopes(next, acc)));
            if (old.owner() != next.owner() && !caller.ownerPowers()) {
                throw forbidden("Only an owner can grant or remove " + Roles.OWNER);
            }
            Future<Void> guard = old.owner() == next.owner() ? Future.succeededFuture()
                    : next.owner() ? claimOwner(tenant, old.user()) : releaseOwner(tenant, old.user());
            return guard.compose(v -> members.update(old.key(), json -> Optional.of(Member.fromJson(json).with(next.roles(), next.groups()).toJson()))
                            .recover(err -> undoOwnerChange(tenant, old, next).compose(x -> Future.failedFuture(err))))
                    .compose(updated -> updated.isEmpty()
                            ? undoOwnerChange(tenant, old, next).compose(x -> failed(notFound(name + " isn't a member of " + tenant)))
                            : access(tenant).map(now -> view(Member.fromJson(updated.get()), name, now)));
        }));
    }

    /**
     * Removes a member, and deletes their personal tokens for this tenant. Completes with the service tokens they made
     * here, which keep working, for the caller to decide about.
     */
    public Future<List<JsonObject>> removeMember(Principal caller, String tenant, String name) {
        return memberNamed(tenant, name).compose(found -> access(tenant).compose(acc -> {
            Member old = found.member();
            checkCovers(caller, Roles.scopes(old, acc));
            if (old.owner() && !caller.ownerPowers()) {
                throw forbidden("Only an owner can remove an owner");
            }
            Future<Void> guard = old.owner() ? releaseOwner(tenant, old.user()) : Future.succeededFuture();
            return guard.compose(v -> members.remove(old.key()))
                    .compose(v -> revokeTokens(tenant, old.user()))
                    .compose(v -> madeBy(List.of(tenant), old.user()));
        }));
    }

    // ---- Roles ----

    /** Built-in roles first, then the tenant's own. */
    public Future<List<Access.Role>> roles(String tenant) {
        return access(tenant).map(acc -> {
            List<Access.Role> all = new ArrayList<>(Roles.BUILT_IN.values());
            acc.roles().values().stream().sorted(Comparator.comparing(Access.Role::name)).forEach(all::add);
            return all;
        });
    }

    /** Creates or changes a custom role. Changing it changes what everyone holding it may do. */
    public Future<Access.Role> putRole(Principal caller, String tenant, String name, Set<String> scopes, String description) {
        try {
            checkName(name, "role");
            if (Roles.BUILT_IN.containsKey(name)) {
                throw invalid(name + " is a built-in role and can't be changed");
            }
            String unknown = Scopes.unknown(scopes);
            if (scopes.isEmpty() || unknown != null) {
                throw invalid(unknown != null ? "Unknown scope \"" + unknown + "\"" : "\"scopes\" can't be empty");
            }
            checkDescription(description);
        } catch (AuthFailure e) {
            return Future.failedFuture(e);
        }
        return access(tenant).compose(acc -> {
            Access.Role old = acc.roles().get(name);
            checkCovers(caller, Roles.union(old == null ? Set.of() : old.scopes(), scopes));
            Access.Role role = new Access.Role(name, scopes, description, false);
            return editAccess(tenant, current -> {
                if (!current.roles().containsKey(name) && current.roles().size() >= MAX_CUSTOM_ROLES) {
                    throw conflict("A tenant can have at most " + MAX_CUSTOM_ROLES + " custom roles");
                }
                return current.withRole(role);
            }).map(role);
        });
    }

    /** Deletes a custom role no member or group holds. */
    public Future<Void> deleteRole(Principal caller, String tenant, String name) {
        if (Roles.BUILT_IN.containsKey(name)) {
            return failed(invalid(name + " is a built-in role and can't be deleted"));
        }
        return access(tenant).compose(acc -> {
            Access.Role role = acc.roles().get(name);
            if (role == null) {
                return failed(notFound("No role named " + name));
            }
            checkCovers(caller, role.scopes());
            return members(tenant).compose(all -> {
                List<String> holders = all.stream().filter(m -> m.getJsonArray("roles").contains(name)).map(m -> m.getString("user")).sorted().toList();
                List<String> inGroups = acc.groups().values().stream().filter(g -> g.roles().contains(name)).map(Access.Group::name).sorted().toList();
                if (!holders.isEmpty() || !inGroups.isEmpty()) {
                    return failed(conflict("The role " + name + " is still granted" + (holders.isEmpty() ? "" : " to " + holders)
                            + (inGroups.isEmpty() ? "" : " in the groups " + inGroups) + "; take it away first"));
                }
                return editAccess(tenant, current -> current.withoutRole(name)).mapEmpty();
            });
        });
    }

    // ---- Groups ----

    /** Each group with its roles and members (by name). */
    public Future<List<JsonObject>> groups(String tenant) {
        return access(tenant).compose(acc -> members(tenant).map(all -> acc.groups().values().stream()
                .sorted(Comparator.comparing(Access.Group::name))
                .map(group -> group.toJson().put("members", new JsonArray(all.stream()
                        .filter(m -> m.getJsonArray("groups").contains(group.name())).map(m -> m.getString("user")).sorted().toList())))
                .toList()));
    }

    /** Creates or changes a group's roles. Members join a group through their membership's {@code groups}. */
    public Future<Access.Group> putGroup(Principal caller, String tenant, String name, List<String> roles, String description) {
        try {
            checkName(name, "group");
            checkDescription(description);
            if (roles.contains(Roles.OWNER)) {
                throw invalid(Roles.OWNER + " is only ever granted directly, not through a group");
            }
            if (roles.size() > MAX_ROLES_EACH) {
                throw invalid("A group can have at most " + MAX_ROLES_EACH + " roles");
            }
        } catch (AuthFailure e) {
            return Future.failedFuture(e);
        }
        return access(tenant).compose(acc -> {
            for (String role : roles) {
                if (Roles.find(role, acc) == null) {
                    throw invalid("No role named " + role);
                }
            }
            Access.Group old = acc.groups().get(name);
            checkCovers(caller, Roles.union(old == null ? Set.of() : Roles.scopes(old.roles(), acc), Roles.scopes(roles, acc)));
            Access.Group group = new Access.Group(name, distinct(roles), description);
            return editAccess(tenant, current -> {
                if (!current.groups().containsKey(name) && current.groups().size() >= MAX_GROUPS) {
                    throw conflict("A tenant can have at most " + MAX_GROUPS + " groups");
                }
                return current.withGroup(group);
            }).map(group);
        });
    }

    /** Deletes a group; its members leave it. */
    public Future<Void> deleteGroup(Principal caller, String tenant, String name) {
        return access(tenant).compose(acc -> {
            Access.Group group = acc.groups().get(name);
            if (group == null) {
                return failed(notFound("No group named " + name));
            }
            checkCovers(caller, Roles.scopes(group.roles(), acc));
            return editAccess(tenant, current -> current.withoutGroup(name))
                    .compose(v -> members.where("tenant", tenant))
                    .compose(rows -> {
                        Future<Void> all = Future.succeededFuture();
                        for (Member member : rows.stream().map(Member::fromJson).filter(m -> m.groups().contains(name)).toList()) {
                            all = all.compose(x -> members.update(member.key(), json -> {
                                Member current = Member.fromJson(json);
                                return Optional.of(current.with(current.roles(), current.groups().stream().filter(g -> !g.equals(name)).toList()).toJson());
                            }).mapEmpty());
                        }
                        return all;
                    });
        });
    }

    // ---- Invites ----

    /** Open invites, oldest first. */
    public Future<List<Invite>> invites(String tenant) {
        long now = clock.getAsLong();
        return invites.where("tenant", tenant).map(rows -> rows.stream().map(Invite::fromJson).filter(i -> !i.expired(now))
                .sorted(Comparator.comparingLong(Invite::createdAt).thenComparing(Invite::id)).toList());
    }

    /** A new invite and its code, which is shown once. */
    public record Created(Invite invite, String code) {
        public JsonObject toJson() {
            return invite.toJson().put("code", code);
        }
    }

    /**
     * Invites someone to a tenant with these roles and groups, or, for {@link #OPERATORS} (operators only), to become
     * an operator. {@code user}, if given, is who may redeem it: an existing user's name, or the name a new one will take.
     */
    public Future<Created> invite(Principal caller, String tenant, List<String> roles, List<String> groups, String user, Integer days,
                                  String note) {
        try {
            if (user != null) {
                checkUserName(user);
            }
            if (note != null && note.length() > 200) {
                throw invalid("\"note\" is at most 200 characters");
            }
            if (days != null && (days < 1 || days > MAX_INVITE_DAYS)) {
                throw invalid("\"expiresInDays\" must be between 1 and " + MAX_INVITE_DAYS);
            }
        } catch (AuthFailure e) {
            return Future.failedFuture(e);
        }
        Future<Void> allowed;
        if (OPERATORS.equals(tenant)) {
            allowed = !caller.admin() ? failed(forbidden("Only operators invite operators"))
                    : !roles.isEmpty() || !groups.isEmpty() ? failed(invalid("Operator invites have no roles or groups"))
                    : Future.succeededFuture();
        } else {
            allowed = access(tenant).compose(acc -> {
                if (roles.isEmpty() && groups.isEmpty()) {
                    throw invalid("An invite needs at least one role or group");
                }
                checkGrants(roles, groups, acc);
                Member would = new Member(tenant, "?", roles, groups, 0, null);
                checkCovers(caller, Roles.scopes(would, acc));
                if (roles.contains(Roles.OWNER) && !caller.ownerPowers()) {
                    throw forbidden("Only an owner can invite an owner");
                }
                return user == null ? Future.succeededFuture() : user(user).compose(existing -> existing == null
                        ? Future.succeededFuture()
                        : membership(tenant, existing.id()).compose(m -> m != null
                                ? failed(conflict(user + " is already a member of " + tenant)) : Future.succeededFuture()));
            });
        }
        return allowed.compose(v -> invites.where("tenant", tenant)).compose(open -> {
            long now = clock.getAsLong();
            if (open.stream().map(Invite::fromJson).filter(i -> !i.expired(now)).count() >= MAX_OPEN_INVITES) {
                return failed(conflict("At most " + MAX_OPEN_INVITES + " open invites; withdraw some first"));
            }
            String code = TokenFormat.generate(random);
            Invite invite = new Invite("inv_" + hex(8), tenant, distinct(roles), distinct(groups), user, note, caller.actor(), now,
                    now + Duration.ofDays(days == null ? DEFAULT_INVITE_DAYS : days).toMillis());
            return invites.put(TokenFormat.hash(code), invite.toJson().put("hash", TokenFormat.hash(code))).map(new Created(invite, code));
        });
    }

    /** Withdraws an invite; completes with whether there was one. */
    public Future<Boolean> deleteInvite(String tenant, String id) {
        return invites.where("id", id).compose(rows -> rows.stream().filter(json -> tenant.equals(json.getString("tenant"))).findFirst()
                .map(json -> invites.remove(json.getString("hash")).map(removed -> removed != null))
                .orElse(Future.succeededFuture(false)));
    }

    /**
     * Redeems an invite. Without a caller, a new user is created, named by the invite or by {@code name}. With one (a
     * personal token), the invite is added to that existing user. Either way the invite is used up, and the answer
     * carries a new personal token for the tenant: {@code {user, tenant, operator, roles, groups, token}}. A non-blank
     * {@code password} sets the password the user signs in with (D-133).
     */
    public Future<JsonObject> redeem(String code, Principal caller, String name, String displayName, String email, String tokenName,
                                     String password) {
        if (code == null || !TokenFormat.looksValid(code)) {
            return failed(notFound(INVITE_GONE));
        }
        String hash = TokenFormat.hash(code);
        long now = clock.getAsLong();
        return invites.get(hash).compose(json -> {
            Invite invite = json == null ? null : Invite.fromJson(json);
            if (invite == null || invite.expired(now)) {
                return failed(notFound(INVITE_GONE));
            }
            if (caller != null && caller.user() == null) {
                throw invalid("Redeem an invite with your own personal token, or with none to become a new user");
            }
            if (caller == null) {
                if (name != null && invite.user() != null && !name.equals(invite.user())) {
                    throw invalid("This invite is for the user " + invite.user());
                }
                String target = invite.user() != null ? invite.user() : name;
                if (target == null) {
                    throw invalid("Choose a user name: \"name\"");
                }
                checkUserName(target);
                checkProfile(displayName, email);
                return user(target).compose(existing -> {
                    if (existing != null) {
                        throw conflict("The user " + target + " already exists. If that's you, log in and redeem the invite with your token");
                    }
                    return claim(hash, json, invite, null, target, displayName, email, now, tokenName, password);
                });
            }
            return userById(caller.user()).compose(user -> {
                if (invite.user() != null && !invite.user().equals(user.name())) {
                    throw forbidden("This invite is for the user " + invite.user());
                }
                return claim(hash, json, invite, user, user.name(), null, null, now, tokenName, password);
            });
        });
    }

    /** Uses up the invite and joins {@code existing} (or a new user named {@code name}) to its tenant or the operators. */
    private Future<JsonObject> claim(String hash, JsonObject stored, Invite invite, User existing, String name, String displayName, String email,
                                     long now, String tokenName, String password) {
        boolean operators = OPERATORS.equals(invite.tenant());
        if (operators && existing != null && existing.operator()) {
            return failed(conflict(name + " is already an operator"));
        }
        Future<Member> already = operators || existing == null ? Future.succeededFuture() : membership(invite.tenant(), existing.id());
        return already.compose(member -> {
            if (member != null) {
                throw conflict(name + " is already a member of " + invite.tenant());
            }
            return invites.remove(hash);
        }).compose(claimed -> {
            if (claimed == null) {
                return failed(notFound(INVITE_GONE));
            }
            Future<User> user = existing != null ? Future.succeededFuture(existing)
                    : createUser(name, displayName, email, now).recover(err -> restore(hash, stored).compose(v -> Future.failedFuture(err)));
            return user.compose(joining -> {
                Future<Void> joined;
                if (operators) {
                    joined = users.update(joining.id(), u -> Optional.of(User.fromJson(u).withOperator(true).toJson())).mapEmpty();
                } else {
                    Member member = new Member(invite.tenant(), joining.id(), invite.roles(), invite.groups(), now, invite.createdBy());
                    joined = members.putIfAbsent(member.key(), member.toJson()).compose(stored2 -> stored2
                            ? (member.owner() ? claimOwner(invite.tenant(), joining.id()) : Future.<Void>succeededFuture())
                            : restore(hash, stored).compose(v -> failed(conflict(name + " is already a member of " + invite.tenant()))));
                }
                return joined.compose(v -> (password == null || password.isBlank() ? Future.<Void>succeededFuture() : setPassword(joining.id(), password)))
                        .compose(v -> {
                            Minted minted = mint(invite.tenant(), tokenName == null || tokenName.isBlank() ? "login" : tokenName, Set.of(Scopes.ALL),
                                    null, joining.id(), joining.id());
                            return tokens.saveToken(minted.token()).compose(x -> userById(joining.id())).map(saved -> new JsonObject()
                                    .put("user", saved.toJson())
                                    .put("tenant", operators ? null : invite.tenant())
                                    .put("operator", operators)
                                    .put("roles", new JsonArray(invite.roles()))
                                    .put("groups", new JsonArray(invite.groups()))
                                    .put("token", minted.toJson()));
                        });
            });
        });
    }

    // ---- Tenants ----

    /** Forgets a deleted tenant's members, roles, groups and invites. Its tokens go with the tenant (AuthStore). */
    public Future<Void> forgetTenant(String tenant) {
        return members.where("tenant", tenant)
                .compose(rows -> Future.all(rows.stream().map(json -> members.remove(Member.fromJson(json).key())).toList()))
                .compose(v -> invites.where("tenant", tenant))
                .compose(rows -> Future.all(rows.stream().map(json -> invites.remove(json.getString("hash"))).toList()))
                .compose(v -> access.remove(tenant))
                .mapEmpty();
    }

    // ---- Helpers ----

    private record Found(User user, Member member) {
    }

    /** The named user's membership of the tenant; fails 404 if they aren't a member. */
    private Future<Found> memberNamed(String tenant, String name) {
        return user(name).compose(user -> {
            if (user == null) {
                return failed(notFound(name + " isn't a member of " + tenant));
            }
            return membership(tenant, user.id()).compose(member -> member == null
                    ? failed(notFound(name + " isn't a member of " + tenant))
                    : Future.succeededFuture(new Found(user, member)));
        });
    }

    private Future<User> existing(String name) {
        return user(name).compose(user -> user == null ? failed(notFound("No user named " + name)) : Future.succeededFuture(user));
    }

    /** A new user with a fresh id, reserving the name first so two can't take it. */
    private Future<User> createUser(String name, String displayName, String email, long now) {
        User user = new User("usr_" + hex(8), name, displayName, email, false, false, now);
        return names.putIfAbsent(name, new JsonObject().put("id", user.id()).put("name", name)).compose(reserved -> reserved
                ? users.put(user.id(), user.toJson()).map(user)
                : failed(conflict("The user " + name + " already exists")));
    }

    /** Takes the new name (it must be free), then gives up the old one. Memberships and tokens hold the id, so they follow. */
    private Future<User> rename(User user, String newName) {
        if (newName.equals(user.name())) {
            return Future.succeededFuture(user);
        }
        try {
            checkUserName(newName);
        } catch (AuthFailure e) {
            return Future.failedFuture(e);
        }
        return names.putIfAbsent(newName, new JsonObject().put("id", user.id()).put("name", newName)).compose(reserved -> {
            if (!reserved) {
                return failed(conflict("The name " + newName + " is taken"));
            }
            return users.update(user.id(), json -> Optional.of(User.fromJson(json).withName(newName).toJson()))
                    .compose(updated -> names.remove(user.name()).map(User.fromJson(updated.orElseThrow())));
        });
    }

    private Future<Member> membership(String tenant, String userId) {
        return members.get(Member.key(tenant, userId)).map(json -> json == null ? null : Member.fromJson(json));
    }

    private Future<List<Member>> memberships(String userId) {
        return members.where("user", userId).map(rows -> rows.stream().map(Member::fromJson).sorted(Comparator.comparing(Member::tenant)).toList());
    }

    private Future<Access> access(String tenant) {
        return access.get(tenant).map(Access::fromJson);
    }

    /** Applies {@code change} to the tenant's access document atomically, creating it first if needed. */
    private Future<Access> editAccess(String tenant, Function<Access, Access> change) {
        return access.putIfAbsent(tenant, Access.EMPTY.toJson())
                .compose(v -> access.update(tenant, json -> Optional.of(change.apply(Access.fromJson(json)).toJson())))
                .map(updated -> Access.fromJson(updated.orElse(null)));
    }

    private Future<Void> claimOwner(String tenant, String user) {
        return editAccess(tenant, acc -> acc.withOwner(user)).mapEmpty();
    }

    /** Atomically gives up ownership, refusing if it would leave the tenant without an owner. */
    private Future<Void> releaseOwner(String tenant, String user) {
        return editAccess(tenant, acc -> {
            if (acc.owners().contains(user) && acc.owners().size() <= 1) {
                throw conflict("This is the last owner of " + tenant + "; make someone else an owner first");
            }
            return acc.withoutOwner(user);
        }).mapEmpty();
    }

    /** Releases ownership of several tenants, putting back the ones already released if any can't be. */
    private Future<Void> releaseOwnerships(List<String> tenants, String user) {
        Future<List<String>> released = Future.succeededFuture(List.of());
        for (String tenant : tenants) {
            released = released.compose(done -> releaseOwner(tenant, user).map(v -> {
                List<String> next = new ArrayList<>(done);
                next.add(tenant);
                return next;
            }).recover(err -> {
                Future<Void> undo = Future.succeededFuture();
                for (String back : done) {
                    undo = undo.compose(v -> claimOwner(back, user));
                }
                return undo.compose(v -> Future.failedFuture(err));
            }));
        }
        return released.mapEmpty();
    }

    private Future<Void> undoOwnerChange(String tenant, Member old, Member next) {
        if (old.owner() == next.owner()) {
            return Future.succeededFuture();
        }
        return (old.owner() ? claimOwner(tenant, old.user()) : editAccess(tenant, acc -> acc.withoutOwner(old.user())).<Void>mapEmpty())
                .recover(err -> Future.succeededFuture());
    }

    private static JsonObject view(Member member, String name, Access access) {
        return member.toJson().put("userId", member.user()).put("user", name)
                .put("scopes", new JsonArray(Roles.scopes(member, access).stream().sorted().toList())).put("owner", member.owner());
    }

    private Future<Void> revokeTokens(String tenant, String user) {
        return tokens.tokens(tenant).compose(all -> Future.all(all.stream().filter(t -> user.equals(t.user()))
                .map(t -> tokens.revokeToken(tenant, t.id())).toList())).mapEmpty();
    }

    private Future<Void> restore(String hash, JsonObject invite) {
        return invites.putIfAbsent(hash, invite).mapEmpty();
    }

    static boolean isUserId(String value) {
        return value != null && value.startsWith("usr_");
    }

    private static void checkGrants(List<String> roles, List<String> groups, Access acc) {
        if (roles.size() > MAX_ROLES_EACH || groups.size() > MAX_GROUPS_EACH) {
            throw invalid("At most " + MAX_ROLES_EACH + " roles and " + MAX_GROUPS_EACH + " groups each");
        }
        for (String role : roles) {
            if (Roles.find(role, acc) == null) {
                throw invalid("No role named " + role);
            }
        }
        for (String group : groups) {
            if (!acc.groups().containsKey(group)) {
                throw invalid("No group named " + group);
            }
        }
    }

    private static void checkCovers(Principal caller, Set<String> scopes) {
        if (!caller.covers(scopes)) {
            Set<String> missing = new HashSet<>(scopes);
            missing.removeAll(caller.scopes());
            throw forbidden("You can only grant access you hold yourself; you don't have " + new java.util.TreeSet<>(missing));
        }
    }

    private static void checkName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw invalid("A " + what + "'s name is " + NAME_RULE);
        }
    }

    public static boolean validUserName(String name) {
        return name != null && USER_NAME.matcher(name).matches();
    }

    private static void checkUserName(String name) {
        if (!validUserName(name)) {
            throw invalid("A user name is " + USER_RULE);
        }
    }

    private static void checkDescription(String description) {
        if (description != null && description.length() > 200) {
            throw invalid("\"description\" is at most 200 characters");
        }
    }

    private static void checkProfile(String displayName, String email) {
        if (displayName != null && displayName.length() > 100) {
            throw invalid("\"displayName\" is at most 100 characters");
        }
        if (email != null && (email.length() > 254 || !email.contains("@"))) {
            throw invalid("\"email\" must be an email address");
        }
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private String hex(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static <T> Future<T> failed(AuthFailure failure) {
        return Future.failedFuture(failure);
    }

    static AuthFailure invalid(String message) {
        return new AuthFailure(400, "invalid_request", message);
    }

    static AuthFailure forbidden(String message) {
        return AuthFailure.forbidden(message);
    }

    static AuthFailure notFound(String message) {
        return new AuthFailure(404, "not_found", message);
    }

    static AuthFailure conflict(String message) {
        return new AuthFailure(409, "conflict", message);
    }
}
