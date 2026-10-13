package cluster93.server;

import cluster93.server.Cluster93.RunningNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Users, members, roles, groups and invites (D-070), and the audit log (D-071), through the API of a real node. */
class IdentityTest {

    private static RunningNode node;
    private static Vertx clientVertx;
    private static WebClient client;

    @BeforeAll
    static void start() throws Exception {
        node = await(Cluster93.start(ServerConfig.fromEnv(Map.ofEntries(
                Map.entry("C93_NODE_ID", "identity"),
                Map.entry("C93_ADMIN_TOKEN", TestAuth.ADMIN_TOKEN),
                Map.entry("C93_RATE_LIMIT_PER_MINUTE", "0"),
                Map.entry("C93_METRICS_PORT", "0"),
                Map.entry("C93_HTTP_PORT", "0"),
                Map.entry("C93_INGRESS_PORT", "0"),
                Map.entry("C93_INTERNAL_PORT", "0"),
                Map.entry("C93_ADVERTISE_HOST", "127.0.0.1"),
                Map.entry("C93_CAPACITY", "4"),
                Map.entry("C93_MAX_HEAP_MB", "64"),
                Map.entry("C93_SECRETS_KEY", "identity-test-key-0123456789abcdef-0123")))));
        TestAuth.use(node);
        clientVertx = Vertx.vertx();
        client = WebClient.create(clientVertx);
    }

    @AfterAll
    static void stop() throws Exception {
        await(clientVertx.close());
        await(node.stop());
    }

    @Test
    void aTenantsFirstOwnerComesInThroughAnInviteAndActsAsThemselves() throws Exception {
        String alice = owner("acme", "alice");

        JsonObject whoami = call(HttpMethod.GET, alice, "/v1/whoami", null).bodyAsJsonObject();
        assertThat(whoami.getString("user")).isEqualTo("alice");
        assertThat(whoami.getString("tenant")).isEqualTo("acme");
        assertThat(whoami.getBoolean("owner")).isTrue();
        assertThat(whoami.getJsonArray("scopes")).containsExactly("*");
        assertThat(call(HttpMethod.PUT, alice, "/v1/secrets/k", new JsonObject().put("value", "v")).statusCode()).isEqualTo(201);

        JsonObject me = call(HttpMethod.GET, alice, "/v1/me", null).bodyAsJsonObject();
        assertThat(me.getString("name")).isEqualTo("alice");
        assertThat(me.getJsonArray("memberships")).hasSize(1);
        assertThat(me.getJsonArray("memberships").getJsonObject(0).getJsonArray("roles")).containsExactly("owner");
    }

    @Test
    void anInviteWorksOnceAndNotAfterItIsWithdrawn() throws Exception {
        String alice = owner("once", "olive");
        String code = invite(alice, new JsonObject().put("roles", new JsonArray().add("viewer"))).getString("code");

        assertThat(redeem(code, "first", null).statusCode()).isEqualTo(201);
        HttpResponse<Buffer> again = redeem(code, "second", null);
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(redeem("c93_made-up", "third", null).statusCode()).isEqualTo(404);

        JsonObject withdrawn = invite(alice, new JsonObject().put("roles", new JsonArray().add("viewer")));
        assertThat(call(HttpMethod.GET, alice, "/v1/invites", null).bodyAsString()).contains(withdrawn.getString("id")).doesNotContain(withdrawn.getString("code"));
        assertThat(call(HttpMethod.DELETE, alice, "/v1/invites/" + withdrawn.getString("id"), null).statusCode()).isEqualTo(204);
        assertThat(redeem(withdrawn.getString("code"), "fourth", null).statusCode()).isEqualTo(404);
    }

    @Test
    void accessFollowsThePersonOnTheirNextRequest() throws Exception {
        String alice = owner("follow", "fran");
        String bob = join(alice, "bert", List.of("developer"), List.of());

        assertThat(call(HttpMethod.POST, bob, "/v1/jobs", job()).statusCode()).isEqualTo(202);
        assertThat(call(HttpMethod.PUT, bob, "/v1/secrets/x", new JsonObject().put("value", "v")).statusCode())
                .as("developers set secrets as well as use them").isEqualTo(201);
        assertThat(call(HttpMethod.POST, bob, "/v1/tokens", new JsonObject().put("name", "t").put("scopes", new JsonArray().add("jobs:read")))
                .statusCode()).isEqualTo(403);

        // Demoted: the same token loses what the role gave.
        assertThat(call(HttpMethod.PUT, alice, "/v1/members/bert", new JsonObject().put("roles", new JsonArray().add("viewer"))).statusCode())
                .isEqualTo(200);
        assertThat(call(HttpMethod.POST, bob, "/v1/jobs", job()).statusCode()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, bob, "/v1/jobs", null).statusCode()).isEqualTo(200);

        // Removed: every token of theirs stops.
        assertThat(call(HttpMethod.DELETE, alice, "/v1/members/bert", null).statusCode()).isEqualTo(200);
        HttpResponse<Buffer> gone = call(HttpMethod.GET, bob, "/v1/jobs", null);
        assertThat(gone.statusCode()).isEqualTo(401);
    }

    @Test
    void nobodyGrantsMoreThanTheyHold() throws Exception {
        String alice = owner("ladder", "lee");
        HttpResponse<Buffer> role = call(HttpMethod.PUT, alice, "/v1/roles/lead", new JsonObject()
                .put("scopes", new JsonArray().add("members:read").add("members:write").add("jobs:read").add("jobs:write")));
        assertThat(role.statusCode()).as(role.bodyAsString()).isEqualTo(200);
        String lead = join(alice, "lin", List.of("lead"), List.of());

        assertThat(call(HttpMethod.POST, lead, "/v1/invites", new JsonObject().put("roles", new JsonArray().add("admin"))).statusCode())
                .as("inviting an admin").isEqualTo(403);
        assertThat(call(HttpMethod.POST, lead, "/v1/invites", new JsonObject().put("roles", new JsonArray().add("lead"))).statusCode())
                .as("inviting with what they hold").isEqualTo(201);
        assertThat(call(HttpMethod.PUT, lead, "/v1/members/lin", new JsonObject().put("roles", new JsonArray().add("admin"))).statusCode())
                .as("promoting themselves").isEqualTo(403);
        assertThat(call(HttpMethod.PUT, lead, "/v1/members/lee", new JsonObject().put("roles", new JsonArray().add("viewer"))).statusCode())
                .as("demoting the owner").isEqualTo(403);
        assertThat(call(HttpMethod.PUT, lead, "/v1/roles/lead", new JsonObject().put("scopes", new JsonArray().add("*"))).statusCode())
                .as("widening their own role").isEqualTo(403);
        assertThat(call(HttpMethod.PUT, lead, "/v1/groups/sneaky", new JsonObject().put("roles", new JsonArray().add("admin"))).statusCode())
                .as("a group with more than they hold").isEqualTo(403);

        // Admins have every scope, but not owner powers; and a tenant keeps its last owner.
        String admin = join(alice, "ari", List.of("admin"), List.of());
        assertThat(call(HttpMethod.PUT, admin, "/v1/members/lee", new JsonObject().put("roles", new JsonArray().add("admin"))).statusCode())
                .isEqualTo(403);
        assertThat(call(HttpMethod.POST, admin, "/v1/invites", new JsonObject().put("roles", new JsonArray().add("owner"))).statusCode())
                .isEqualTo(403);
        HttpResponse<Buffer> last = call(HttpMethod.DELETE, alice, "/v1/members/lee", null);
        assertThat(last.statusCode()).isEqualTo(409);
        assertThat(last.bodyAsString()).contains("last owner");

        // A role in use can't be deleted; built-in ones never.
        assertThat(call(HttpMethod.DELETE, alice, "/v1/roles/lead", null).statusCode()).isEqualTo(409);
        assertThat(call(HttpMethod.DELETE, alice, "/v1/roles/admin", null).statusCode()).isEqualTo(400);
    }

    @Test
    void groupsGrantTheirRolesToTheirMembers() throws Exception {
        String alice = owner("grouped", "gwen");
        assertThat(call(HttpMethod.PUT, alice, "/v1/groups/builders", new JsonObject().put("roles", new JsonArray().add("developer")))
                .statusCode()).isEqualTo(200);
        String carol = join(alice, "cora", List.of("viewer"), List.of("builders"));
        assertThat(call(HttpMethod.POST, carol, "/v1/jobs", job()).statusCode()).isEqualTo(202);

        JsonObject groups = call(HttpMethod.GET, alice, "/v1/groups", null).bodyAsJsonObject();
        assertThat(groups.getJsonArray("groups").getJsonObject(0).getJsonArray("members")).containsExactly("cora");
        assertThat(call(HttpMethod.PUT, alice, "/v1/groups/sneaky", new JsonObject().put("roles", new JsonArray().add("owner"))).statusCode())
                .as("owner is only granted directly").isEqualTo(400);

        assertThat(call(HttpMethod.DELETE, alice, "/v1/groups/builders", null).statusCode()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, carol, "/v1/jobs", job()).statusCode()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, alice, "/v1/members/cora", null).bodyAsJsonObject().getJsonArray("groups")).isEmpty();
    }

    @Test
    void oneUserBelongsToSeveralTenants() throws Exception {
        String alice = owner("first-co", "multi");
        String other = owner("second-co", "someone-else");
        String code = invite(other, new JsonObject().put("roles", new JsonArray().add("viewer")).put("user", "multi")).getString("code");

        HttpResponse<Buffer> stranger = redeem(code, "multi", null);
        assertThat(stranger.statusCode()).as("the name is taken: log in instead").isEqualTo(409);
        HttpResponse<Buffer> joined = redeem(code, null, alice);
        assertThat(joined.statusCode()).as(joined.bodyAsString()).isEqualTo(201);
        assertThat(joined.bodyAsJsonObject().getString("tenant")).isEqualTo("second-co");

        HttpResponse<Buffer> minted = call(HttpMethod.POST, alice, "/v1/me/tokens", new JsonObject().put("name", "laptop").put("tenant", "second-co"));
        assertThat(minted.statusCode()).as(minted.bodyAsString()).isEqualTo(201);
        String second = "Bearer " + minted.bodyAsJsonObject().getString("token");
        assertThat(call(HttpMethod.GET, second, "/v1/whoami", null).bodyAsJsonObject().getString("tenant")).isEqualTo("second-co");
        assertThat(call(HttpMethod.POST, alice, "/v1/me/tokens", new JsonObject().put("name", "x").put("tenant", "second-co")
                .put("scopes", new JsonArray().add("jobs:write"))).statusCode()).as("beyond their roles there").isEqualTo(403);
        assertThat(call(HttpMethod.POST, alice, "/v1/me/tokens", new JsonObject().put("name", "x").put("tenant", "nowhere")).statusCode())
                .isEqualTo(403);

        JsonObject me = call(HttpMethod.GET, second, "/v1/me", null).bodyAsJsonObject();
        assertThat(me.getJsonArray("memberships").stream().map(m -> ((JsonObject) m).getString("tenant")).toList())
                .containsExactly("first-co", "second-co");
        JsonArray mine = call(HttpMethod.GET, alice, "/v1/me/tokens", null).bodyAsJsonObject().getJsonArray("tokens");
        assertThat(mine).hasSize(3);
        String laptop = minted.bodyAsJsonObject().getString("id");
        assertThat(call(HttpMethod.DELETE, alice, "/v1/me/tokens/" + laptop, null).statusCode()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, second, "/v1/whoami", null).statusCode()).isEqualTo(401);

        // Service tokens aren't anyone's: they can't use the "me" routes.
        assertThat(call(HttpMethod.GET, TestAuth.bearer("first-co"), "/v1/me", null).statusCode()).isEqualTo(403);
    }

    @Test
    void operatorsManageTheClusterAsThemselvesAndLoseItAtOnce() throws Exception {
        HttpResponse<Buffer> invited = call(HttpMethod.POST, TestAuth.ADMIN, "/v1/admin/invites", new JsonObject().put("user", "opal"));
        assertThat(invited.statusCode()).as(invited.bodyAsString()).isEqualTo(201);
        HttpResponse<Buffer> redeemed = redeem(invited.bodyAsJsonObject().getString("code"), null, null);
        assertThat(redeemed.statusCode()).as(redeemed.bodyAsString()).isEqualTo(201);
        assertThat(redeemed.bodyAsJsonObject().getBoolean("operator")).isTrue();
        String opal = "Bearer " + redeemed.bodyAsJsonObject().getJsonObject("token").getString("token");

        assertThat(call(HttpMethod.POST, opal, "/v1/tenants", new JsonObject().put("name", "made-by-opal")).statusCode()).isEqualTo(201);
        assertThat(call(HttpMethod.GET, opal, "/v1/users", null).bodyAsString()).contains("opal");
        assertThat(call(HttpMethod.GET, opal, "/v1/jobs", null).statusCode()).as("operators don't read tenant data").isEqualTo(403);
        assertThat(call(HttpMethod.GET, opal, "/v1/whoami", null).bodyAsJsonObject().getString("user")).isEqualTo("opal");

        // Only operators invite operators.
        assertThat(call(HttpMethod.POST, TestAuth.bearer("made-by-opal"), "/v1/admin/invites", new JsonObject()).statusCode()).isEqualTo(403);

        assertThat(call(HttpMethod.PATCH, TestAuth.ADMIN, "/v1/users/opal", new JsonObject().put("operator", false)).statusCode()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, opal, "/v1/users", null).statusCode()).isEqualTo(401);

        String dave = owner("disabled-co", "dave");
        assertThat(call(HttpMethod.PATCH, TestAuth.ADMIN, "/v1/users/dave", new JsonObject().put("disabled", true)).statusCode()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, dave, "/v1/whoami", null).statusCode()).isEqualTo(401);
        assertThat(call(HttpMethod.DELETE, TestAuth.ADMIN, "/v1/users/dave", null).statusCode()).as("the last owner").isEqualTo(409);
    }

    @Test
    void changesAreAuditedWithWhoMadeThem() throws Exception {
        String alice = owner("audited", "audrey");
        join(alice, "aldo", List.of("viewer"), List.of());
        String viewer = "Bearer " + call(HttpMethod.POST, alice, "/v1/tokens", new JsonObject().put("name", "ro")
                .put("scopes", new JsonArray().add("jobs:read"))).bodyAsJsonObject().getString("token");
        assertThat(call(HttpMethod.POST, viewer, "/v1/jobs", job()).statusCode()).isEqualTo(403);
        call(HttpMethod.GET, alice, "/v1/jobs", null);

        JsonArray entries = call(HttpMethod.GET, alice, "/v1/audit", null).bodyAsJsonObject().getJsonArray("entries");
        List<String> actions = entries.stream().map(e -> ((JsonObject) e).getString("action")).toList();
        assertThat(actions).contains("createInvite", "redeemInvite", "createToken", "submitJob", "createTenantInvite").doesNotContain("listJobs");
        JsonObject refused = entries.stream().map(JsonObject.class::cast).filter(e -> e.getString("action").equals("submitJob")).findFirst().orElseThrow();
        assertThat(refused.getInteger("status")).isEqualTo(403);
        JsonObject invited = entries.stream().map(JsonObject.class::cast).filter(e -> e.getString("action").equals("createInvite")).findFirst().orElseThrow();
        assertThat(invited.getJsonObject("actor").getString("user")).isEqualTo("audrey");
        assertThat(invited.getString("ip")).isNotBlank();

        // Newest first, a page at a time.
        JsonObject page = call(HttpMethod.GET, alice, "/v1/audit?limit=2", null).bodyAsJsonObject();
        assertThat(page.getJsonArray("entries")).hasSize(2);
        JsonObject next = call(HttpMethod.GET, alice, "/v1/audit?limit=2&before=" + page.getString("next"), null).bodyAsJsonObject();
        assertThat(next.getJsonArray("entries").getJsonObject(0).getString("id")).isLessThan(page.getString("next"));

        // Operators see what was done with the admin token, tenants only their own.
        String operatorLog = call(HttpMethod.GET, TestAuth.ADMIN, "/v1/admin/audit?limit=1000", null).bodyAsString();
        assertThat(operatorLog).contains("createTenantInvite").contains("\"tenant\":\"audited\"");
        assertThat(call(HttpMethod.GET, TestAuth.bearer("someone-else-entirely"), "/v1/audit", null).bodyAsString()).doesNotContain("audrey");
    }

    @Test
    void aRenamedUserKeepsEverything() throws Exception {
        String alice = owner("renamed-co", "old-name");
        owner("taken-co", "taken");

        assertThat(call(HttpMethod.PATCH, alice, "/v1/me", new JsonObject().put("name", "taken")).statusCode()).isEqualTo(409);
        HttpResponse<Buffer> renamed = call(HttpMethod.PATCH, alice, "/v1/me", new JsonObject().put("name", "new-name"));
        assertThat(renamed.statusCode()).as(renamed.bodyAsString()).isEqualTo(200);

        JsonObject whoami = call(HttpMethod.GET, alice, "/v1/whoami", null).bodyAsJsonObject();
        assertThat(whoami.getString("user")).as("the same token, under the new name").isEqualTo("new-name");
        assertThat(whoami.getBoolean("owner")).isTrue();
        assertThat(call(HttpMethod.GET, alice, "/v1/members/new-name", null).statusCode()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, alice, "/v1/members/old-name", null).statusCode()).isEqualTo(404);

        // The old name is free again; an operator can rename too.
        assertThat(call(HttpMethod.PATCH, TestAuth.ADMIN, "/v1/users/new-name", new JsonObject().put("name", "old-name")).statusCode()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, alice, "/v1/whoami", null).bodyAsJsonObject().getString("user")).isEqualTo("old-name");
    }

    @Test
    void twoOwnersRemovingEachOtherAtOnceLeaveOne() throws Exception {
        String first = owner("raced", "racer-one");
        String code = invite(first, new JsonObject().put("roles", new JsonArray().add("owner"))).getString("code");
        String second = "Bearer " + redeem(code, "racer-two", null).bodyAsJsonObject().getJsonObject("token").getString("token");

        for (int round = 0; round < 5; round++) {
            Future<HttpResponse<Buffer>> a = client.request(HttpMethod.PUT, node.httpPort(), "localhost", "/v1/members/racer-two")
                    .putHeader("Authorization", first).sendJsonObject(new JsonObject().put("roles", new JsonArray().add("viewer")));
            Future<HttpResponse<Buffer>> b = client.request(HttpMethod.PUT, node.httpPort(), "localhost", "/v1/members/racer-one")
                    .putHeader("Authorization", second).sendJsonObject(new JsonObject().put("roles", new JsonArray().add("viewer")));
            await(Future.join(a, b));
            JsonArray members = call(HttpMethod.GET, TestAuth.ADMIN, "/v1/tenants/raced/members", null).bodyAsJsonObject().getJsonArray("members");
            long owners = members.stream().filter(m -> ((JsonObject) m).getBoolean("owner")).count();
            assertThat(owners).as("round " + round + ": " + members.encode()).isEqualTo(1);
            // Make both owners again for the next round, through whichever one still is.
            String still = members.stream().map(JsonObject.class::cast).filter(m -> m.getBoolean("owner")).findFirst().orElseThrow().getString("user");
            String other = still.equals("racer-one") ? "racer-two" : "racer-one";
            assertThat(call(HttpMethod.PUT, still.equals("racer-one") ? first : second, "/v1/members/" + other,
                    new JsonObject().put("roles", new JsonArray().add("owner"))).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void serviceTokensOutliveTheirMakerButAreFlagged() throws Exception {
        String alice = owner("flagged", "fiona");
        String admin = join(alice, "adam", List.of("admin"), List.of());
        JsonObject ci = call(HttpMethod.POST, admin, "/v1/tokens", new JsonObject().put("name", "ci").put("scopes", new JsonArray().add("jobs:write")))
                .bodyAsJsonObject();
        JsonObject listed = tokenNamed(alice, "ci");
        assertThat(listed.getString("createdByName")).isEqualTo("adam");
        assertThat(listed.getBoolean("makerGone")).isFalse();

        HttpResponse<Buffer> removed = call(HttpMethod.DELETE, alice, "/v1/members/adam", null);
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(removed.bodyAsJsonObject().getJsonArray("serviceTokens").getJsonObject(0).getString("id")).isEqualTo(ci.getString("id"));

        assertThat(call(HttpMethod.POST, "Bearer " + ci.getString("token"), "/v1/jobs", job()).statusCode()).as("still works").isEqualTo(202);
        assertThat(tokenNamed(alice, "ci").getBoolean("makerGone")).isTrue();
    }

    @Test
    void theAuditChainVerifies() throws Exception {
        String alice = owner("chained", "chen");
        call(HttpMethod.POST, alice, "/v1/jobs", job());
        JsonObject entry = call(HttpMethod.GET, alice, "/v1/audit?limit=1", null).bodyAsJsonObject().getJsonArray("entries").getJsonObject(0);
        assertThat(entry.getString("chain")).startsWith("identity/");
        assertThat(entry.getString("hash")).hasSize(64);

        JsonObject verified = call(HttpMethod.GET, TestAuth.ADMIN, "/v1/admin/audit/verify", null).bodyAsJsonObject();
        assertThat(verified.getBoolean("ok")).as(verified.encodePrettily()).isTrue();
        assertThat(verified.getBoolean("keyed")).isTrue();
        assertThat(verified.getLong("checked")).isPositive();
        assertThat(call(HttpMethod.GET, alice, "/v1/admin/audit/verify", null).statusCode()).isEqualTo(403);
    }

    private static JsonObject tokenNamed(String bearer, String name) throws Exception {
        return call(HttpMethod.GET, bearer, "/v1/tokens", null).bodyAsJsonObject().getJsonArray("tokens").stream().map(JsonObject.class::cast)
                .filter(t -> t.getString("name").equals(name)).findFirst().orElseThrow();
    }

    @Test
    void deletingATenantForgetsItsMembers() throws Exception {
        String alice = owner("brief", "bree");
        assertThat(call(HttpMethod.DELETE, TestAuth.ADMIN, "/v1/tenants/brief", null).statusCode()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, alice, "/v1/whoami", null).statusCode()).isEqualTo(401);
        JsonObject user = call(HttpMethod.GET, TestAuth.ADMIN, "/v1/users/bree", null).bodyAsJsonObject();
        assertThat(user.getString("name")).isEqualTo("bree");
    }

    // ---- Helpers ----

    /** Creates the tenant and invites its first owner, who redeems the invite; their personal token. */
    private static String owner(String tenant, String user) throws Exception {
        assertThat(call(HttpMethod.POST, TestAuth.ADMIN, "/v1/tenants", new JsonObject().put("name", tenant)).statusCode()).isIn(201, 409);
        HttpResponse<Buffer> invited = call(HttpMethod.POST, TestAuth.ADMIN, "/v1/tenants/" + tenant + "/invites",
                new JsonObject().put("roles", new JsonArray().add("owner")).put("user", user));
        assertThat(invited.statusCode()).as(invited.bodyAsString()).isEqualTo(201);
        HttpResponse<Buffer> redeemed = redeem(invited.bodyAsJsonObject().getString("code"), null, null);
        assertThat(redeemed.statusCode()).as(redeemed.bodyAsString()).isEqualTo(201);
        return "Bearer " + redeemed.bodyAsJsonObject().getJsonObject("token").getString("token");
    }

    /** Invites a new user with these roles and groups, and redeems it as them; their personal token. */
    private static String join(String inviter, String user, List<String> roles, List<String> groups) throws Exception {
        String code = invite(inviter, new JsonObject().put("roles", new JsonArray(roles)).put("groups", new JsonArray(groups))).getString("code");
        HttpResponse<Buffer> redeemed = redeem(code, user, null);
        assertThat(redeemed.statusCode()).as(redeemed.bodyAsString()).isEqualTo(201);
        return "Bearer " + redeemed.bodyAsJsonObject().getJsonObject("token").getString("token");
    }

    private static JsonObject invite(String bearer, JsonObject body) throws Exception {
        HttpResponse<Buffer> invited = call(HttpMethod.POST, bearer, "/v1/invites", body);
        assertThat(invited.statusCode()).as(invited.bodyAsString()).isEqualTo(201);
        return invited.bodyAsJsonObject();
    }

    private static HttpResponse<Buffer> redeem(String code, String name, String bearer) throws Exception {
        JsonObject body = new JsonObject().put("code", code);
        if (name != null) {
            body.put("name", name);
        }
        return call(HttpMethod.POST, bearer, "/v1/invites/redeem", body);
    }

    private static JsonObject job() {
        return new JsonObject().put("script", "export default () => 1");
    }

    private static HttpResponse<Buffer> call(HttpMethod method, String bearer, String path, JsonObject body) throws Exception {
        HttpRequest<Buffer> request = client.request(method, node.httpPort(), "localhost", path);
        if (bearer != null) {
            request.putHeader("Authorization", bearer);
        }
        return await(body == null ? request.send() : request.sendJsonObject(body));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }
}
