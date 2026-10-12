package automately.core.apps;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing and validation of {@code automately.json}. */
class AppManifestTest {

    private static final String CONSOLE = """
            {
              "name": "console",
              "version": "1.0.0",
              "routes": [
                {"src": "/api/(.*)", "dest": "service:console-api/$1"},
                {"src": "/v1/(.*)", "dest": "api:/v1/$1"},
                {"src": ".*", "dest": "/index.html", "status": 200}
              ],
              "backend": {"entry": "server.js", "memoryMb": 256, "timeoutMs": 15000, "env": {"NODE_ENV": "production"}},
              "headers": [
                {"source": "/_next/static/(.*)", "headers": {"Cache-Control": "public, max-age=31536000, immutable"}}
              ]
            }
            """;

    @Test
    void parsesRoutesBackendAndHeaders() {
        AppManifest manifest = AppManifest.parse(CONSOLE);

        assertThat(manifest.name()).isEqualTo("console");
        assertThat(manifest.version()).isEqualTo("1.0.0");

        assertThat(manifest.routes()).hasSize(3);
        assertThat(manifest.routes().get(0)).isEqualTo(new AppManifest.RouteRule(
                "/api/(.*)", "service:console-api/$1", null, java.util.Map.of()));
        assertThat(manifest.routes().get(1).dest()).isEqualTo("api:/v1/$1");
        assertThat(manifest.routes().get(2).status()).isEqualTo(200);
        assertThat(manifest.routes().get(2).dest()).isEqualTo("/index.html");

        assertThat(manifest.backend()).isEqualTo(new AppManifest.BackendConfig(
                "server.js", 256, 15000, java.util.Map.of("NODE_ENV", "production")));

        assertThat(manifest.headers()).containsExactly(new AppManifest.HeaderRule(
                "/_next/static/(.*)",
                java.util.Map.of("Cache-Control", "public, max-age=31536000, immutable")));
    }

    @Test
    void pureStaticAppHasDefaults() {
        AppManifest manifest = AppManifest.parse("{\"name\": \"site\"}");

        assertThat(manifest.version()).isNull();
        assertThat(manifest.routes()).isEmpty();
        assertThat(manifest.backend()).isNull();
        assertThat(manifest.headers()).isEmpty();
    }

    @Test
    void backendFallsBackToDefaultLimits() {
        AppManifest manifest = AppManifest.parse("{\"name\": \"site\", \"backend\": {\"entry\": \"main.js\"}}");

        assertThat(manifest.backend()).isEqualTo(new AppManifest.BackendConfig("main.js", 128, 30000, java.util.Map.of()));
    }

    @Test
    void acceptsRoutesWithNoRoutes() {
        assertThat(AppManifest.parse(new JsonObject().put("name", "site").put("routes", (Object) null)).routes()).isEmpty();
    }

    @Test
    void rejectsInvalidNames() {
        for (String json : List.of(
                "{}",
                "{\"name\": \"\"}",
                "{\"name\": \"Console\"}",
                "{\"name\": \"-shop\"}",
                "{\"name\": \"shop-\"}",
                "{\"name\": \"shop_app\"}",
                "{\"name\": \"" + "a".repeat(65) + "\"}")) {
            assertThatThrownBy(() -> AppManifest.parse(json))
                    .as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(AppManifest.parse("{\"name\": \"a\"}").name()).isEqualTo("a");
    }

    @Test
    void rejectsMalformedRoutes() {
        for (String json : List.of(
                "{\"name\": \"site\", \"routes\": [{\"src\": \"/x\"}]}",
                "{\"name\": \"site\", \"routes\": [{\"dest\": \"/index.html\"}]}",
                "{\"name\": \"site\", \"routes\": [{\"src\": \"/x\", \"dest\": \"ftp://host/x\"}]}",
                "{\"name\": \"site\", \"routes\": [{\"src\": \"/x\", \"dest\": \"service:/$1\"}]}",
                "{\"name\": \"site\", \"routes\": [{\"src\": \"/x\", \"dest\": \"service:api\"}]}",
                "{\"name\": \"site\", \"routes\": [{\"src\": \"/x\", \"dest\": \"api:v1\"}]}",
                "{\"name\": \"site\", \"routes\": [{\"src\": \"/x\", \"dest\": \"/index.html\", \"status\": 99}]}",
                "{\"name\": \"site\", \"routes\": [\"/x\"]}")) {
            assertThatThrownBy(() -> AppManifest.parse(json))
                    .as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMalformedBackendAndHeaderRules() {
        for (String json : List.of(
                "{\"name\": \"site\", \"backend\": {}}",
                "{\"name\": \"site\", \"backend\": {\"entry\": \"main.js\", \"memoryMb\": 0}}",
                "{\"name\": \"site\", \"backend\": {\"entry\": \"main.js\", \"timeoutMs\": -1}}",
                "{\"name\": \"site\", \"headers\": [{\"headers\": {\"X\": \"y\"}}]}")) {
            assertThatThrownBy(() -> AppManifest.parse(json))
                    .as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> AppManifest.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void roundTripsThroughJson() {
        AppManifest manifest = AppManifest.parse(CONSOLE);
        assertThat(AppManifest.fromJson(manifest.toJson())).isEqualTo(manifest);
    }
}
