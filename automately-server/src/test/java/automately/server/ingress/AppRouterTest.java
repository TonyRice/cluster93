package automately.server.ingress;

import automately.core.apps.AppDeployment;
import automately.core.apps.AppManifest;
import automately.core.apps.AppStore;
import automately.core.apps.SharedDataAppStore;
import automately.core.files.FileInfo;
import automately.core.files.FileStore;
import automately.core.files.FileWriter;
import automately.core.files.Room;
import automately.core.runtime.BodyStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AppRouter}: console-role gating and CIDR enforcement, host resolution (preview, canonical, local-dev fallback,
 * test headers), and route evaluation (service proxy, API rewrite, static asset, SPA fallback).
 */
class AppRouterTest {

    /** A full development node: every persona, including the console. */
    private static final Set<String> ALL_ROLES = Set.of("api", "ingress", "worker", "data", "console");

    private static final String ROUTES = """
            {
              "name": "shop",
              "routes": [
                {"src": "/api/(.*)", "dest": "service:shop-api/$1"},
                {"src": "/v1/(.*)", "dest": "api:/v1/$1"}
              ],
              "headers": [
                {"source": "/assets/(.*)", "headers": {"Cache-Control": "public, max-age=31536000, immutable"}}
              ]
            }
            """;

    private Vertx vertx;
    private AppStore apps;
    private FakeFiles files;
    private AppRouter router;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        apps = new SharedDataAppStore(vertx);
        files = new FakeFiles();
        router = new AppRouter(apps, files, "apps.test", "console.internal.test",
                CidrMatcher.parse(List.of("127.0.0.1/32", "10.0.0.0/8")), ALL_ROLES);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void deploy(String tenant, String app, String id, String manifest) throws Exception {
        files.put(assetPrefix(app, id) + "/index.html");
        await(apps.saveDeployment(new AppDeployment(tenant, app, id, 100, null, AppManifest.parse(manifest), null, null)));
    }

    private static String assetPrefix(String app, String id) {
        return ".apps/" + app + "/deployments/" + id;
    }

    @Test
    @DisplayName("A client outside the console's allow-list is refused, before any app is even looked up")
    void consoleOutsideAllowedNetworkIsDenied() throws Exception {
        assertThat(await(router.route(request("console.internal.test", "/", "8.8.8.8"))))
                .isInstanceOf(AppRouter.Decision.Denied.class);
    }

    @Test
    @DisplayName("A client inside the allow-list reaches the reserved console app")
    void consoleInsideAllowedNetworkReachesTheConsoleApp() throws Exception {
        deploy("system", "console", "dpl_c", "{\"name\": \"console\"}");
        await(apps.promote("system", "console", "dpl_c"));

        AppRouter.Decision decision = await(router.route(request("console.internal.test", "/", "10.1.2.3")));
        assertThat(decision).isInstanceOf(AppRouter.Decision.Static.class);
        assertThat(((AppRouter.Decision.Static) decision).path()).isEqualTo(assetPrefix("console", "dpl_c") + "/index.html");
    }

    @Test
    @DisplayName("A node without the console role refuses the console host, falling through to service routing")
    void consoleHostWithoutTheRoleIsNotAnApp() throws Exception {
        deploy("system", "console", "dpl_c", "{\"name\": \"console\"}");
        await(apps.promote("system", "console", "dpl_c"));
        AppRouter withoutConsole = new AppRouter(apps, files, "apps.test", "console.internal.test",
                CidrMatcher.parse(List.of()), Set.of("api", "ingress", "worker", "data"));

        assertThat(await(withoutConsole.route(request("console.internal.test", "/", "10.1.2.3"))))
                .isInstanceOf(AppRouter.Decision.NotAnApp.class);
    }

    @Test
    @DisplayName("With no console domain, a console-role node serves the console on localhost")
    void localDevFallbackRoutesLocalhostToTheConsole() throws Exception {
        deploy("system", "console", "dpl_c", "{\"name\": \"console\"}");
        await(apps.promote("system", "console", "dpl_c"));
        AppRouter local = new AppRouter(apps, files, "localhost", null, null, ALL_ROLES);

        AppRouter.Decision decision = await(local.route(request("localhost", "/", "127.0.0.1")));
        assertThat(decision).isInstanceOf(AppRouter.Decision.Static.class);
        assertThat(((AppRouter.Decision.Static) decision).path()).isEqualTo(assetPrefix("console", "dpl_c") + "/index.html");
    }

    @Test
    @DisplayName("With no console domain, a node without the console role doesn't answer localhost as the console")
    void localDevFallbackNeedsTheConsoleRole() throws Exception {
        deploy("system", "console", "dpl_c", "{\"name\": \"console\"}");
        await(apps.promote("system", "console", "dpl_c"));
        AppRouter local = new AppRouter(apps, files, "localhost", null, null, Set.of("api", "ingress", "worker", "data"));

        assertThat(await(local.route(request("localhost", "/", "127.0.0.1"))))
                .isInstanceOf(AppRouter.Decision.NotAnApp.class);
    }

    @Test
    @DisplayName("The preview host <deploymentId>--<app>--<tenant> serves that deployment")
    void previewHostServesThatDeployment() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        deploy("acme", "shop", "dpl_b", ROUTES);
        await(apps.promote("acme", "shop", "dpl_a"));

        AppRouter.Decision decision = await(router.route(request("dpl_b--shop--acme.apps.test", "/", "127.0.0.1")));
        assertThat(decision).isInstanceOf(AppRouter.Decision.Static.class);
        assertThat(((AppRouter.Decision.Static) decision).path()).isEqualTo(assetPrefix("shop", "dpl_b") + "/index.html");
    }

    @Test
    @DisplayName("The canonical host <app>--<tenant> serves the active deployment")
    void canonicalHostServesTheActiveDeployment() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        await(apps.promote("acme", "shop", "dpl_a"));

        AppRouter.Decision decision = await(router.route(request("shop--acme.apps.test", "/", "127.0.0.1")));
        assertThat(decision).isInstanceOf(AppRouter.Decision.Static.class);
        assertThat(((AppRouter.Decision.Static) decision).path()).isEqualTo(assetPrefix("shop", "dpl_a") + "/index.html");
    }

    @Test
    @DisplayName("The test headers name the app and tenant directly")
    void testHeadersResolveAnApp() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        await(apps.promote("acme", "shop", "dpl_a"));

        JsonObject headers = new JsonObject().put("x-automately-app", "shop").put("x-automately-tenant", "acme");
        AppRouter.Request request = new AppRouter.Request("whatever.example", "GET", "/", null, "127.0.0.1", headers);
        assertThat(await(router.route(request))).isInstanceOf(AppRouter.Decision.Static.class);
    }

    @Test
    @DisplayName("A service route proxies to the named service in the app's tenant, keeping the query")
    void serviceRouteProxiesToTheNamedService() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        await(apps.promote("acme", "shop", "dpl_a"));

        AppRouter.Decision decision = await(router.route(request("shop--acme.apps.test", "/api/orders", "q=1", "127.0.0.1")));
        assertThat(decision).isEqualTo(new AppRouter.Decision.Proxy("acme", "shop-api", "/orders?q=1"));
    }

    @Test
    @DisplayName("An api route rewrites to the local API, keeping the query")
    void apiRouteRewritesToTheLocalApi() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        await(apps.promote("acme", "shop", "dpl_a"));

        AppRouter.Decision decision = await(router.route(request("shop--acme.apps.test", "/v1/jobs", "limit=5", "127.0.0.1")));
        assertThat(decision).isEqualTo(new AppRouter.Decision.Api("/v1/jobs?limit=5"));
    }

    @Test
    @DisplayName("A static asset is streamed from the deployment's VFS prefix with the manifest's headers")
    void staticAssetCarriesManifestHeaders() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        files.put(assetPrefix("shop", "dpl_a") + "/assets/app.js");
        await(apps.promote("acme", "shop", "dpl_a"));

        AppRouter.Decision decision = await(router.route(request("shop--acme.apps.test", "/assets/app.js", "127.0.0.1")));
        assertThat(decision).isInstanceOf(AppRouter.Decision.Static.class);
        AppRouter.Decision.Static asset = (AppRouter.Decision.Static) decision;
        assertThat(asset.path()).isEqualTo(assetPrefix("shop", "dpl_a") + "/assets/app.js");
        assertThat(asset.headers().getString("cache-control")).isEqualTo("public, max-age=31536000, immutable");
    }

    @Test
    @DisplayName("A path with no asset falls back to /index.html for client-side routing")
    void spaFallbackServesIndexHtml() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        await(apps.promote("acme", "shop", "dpl_a"));

        AppRouter.Decision decision = await(router.route(request("shop--acme.apps.test", "/tenants/demo/jobs", "127.0.0.1")));
        assertThat(decision).isInstanceOf(AppRouter.Decision.Static.class);
        assertThat(((AppRouter.Decision.Static) decision).path()).isEqualTo(assetPrefix("shop", "dpl_a") + "/index.html");
    }

    @Test
    @DisplayName("A host that names no app falls back to ordinary service routing")
    void unknownHostIsNotAnApp() throws Exception {
        assertThat(await(router.route(request("other.example.com", "/", "127.0.0.1"))))
                .isInstanceOf(AppRouter.Decision.NotAnApp.class);
        assertThat(await(router.route(request("nothing--acme.apps.test", "/", "127.0.0.1"))))
                .isInstanceOf(AppRouter.Decision.NotAnApp.class);
    }

    @Test
    @DisplayName("An app that has nothing deployed is not an app host yet")
    void appWithNothingDeployedIsNotAnApp() throws Exception {
        deploy("acme", "shop", "dpl_a", ROUTES);
        // Not promoted: the canonical host has no active deployment.
        assertThat(await(router.route(request("shop--acme.apps.test", "/", "127.0.0.1"))))
                .isInstanceOf(AppRouter.Decision.NotAnApp.class);
    }

    // --- helpers ---

    private static AppRouter.Request request(String host, String path, String remote) {
        return request(host, path, null, remote);
    }

    private static AppRouter.Request request(String host, String path, String query, String remote) {
        return new AppRouter.Request(host, "GET", path, query, remote, new JsonObject());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** A {@link FileStore} whose {@code stat} knows only the files a test registers. */
    private static final class FakeFiles implements FileStore {

        private final Map<String, FileInfo> present = new HashMap<>();

        void put(String path) {
            present.put(path, new FileInfo("system", path, "blob", 1, "text/plain", "etag-" + path, "cluster", 0));
        }

        @Override
        public Future<FileInfo> stat(String tenant, String path) {
            return Future.succeededFuture(present.get(path));
        }

        @Override
        public Future<FileWriter> create(String tenant, String path, String contentType, Room room) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> ensureRoom(String tenant, String path, Room room, long bytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BodyStream open(FileInfo file, long start, long endInclusive) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<JsonObject> list(String tenant, String prefix, int limit, String cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Boolean> delete(String tenant, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<JsonObject> statEntry(String tenant, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Boolean> mkdir(String tenant, String path, boolean parents) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Boolean> rmdir(String tenant, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<JsonObject> listDir(String tenant, String dir, int limit, String cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<FileInfo> copy(String tenant, String from, String to, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<FileInfo> move(String tenant, String from, String to, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Long> usage(String tenant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Long> usageIn(String tenant, List<String> backends) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Long> countIn(String backend) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Integer> collectGarbage(long now) {
            throw new UnsupportedOperationException();
        }
    }
}
