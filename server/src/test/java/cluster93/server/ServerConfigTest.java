package cluster93.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node's role and capability configuration, and the default data directory (docs/design/node-roles.md).
 */
class ServerConfigTest {

    private static final Set<String> ALL_ROLES = Set.of("api", "ingress", "worker", "data", "console");

    @Test
    @DisplayName("The console role is one of the personas a node may play")
    void rolesIncludeConsole() {
        assertThat(ServerConfig.ROLES).contains("console");
    }

    @Test
    @DisplayName("The API, ingress and metrics bind all interfaces by default, as they always have")
    void httpBindDefaultsToAllInterfaces() {
        assertThat(ServerConfig.fromEnv(Map.of()).httpBind()).isEqualTo("0.0.0.0");
        assertThat(ServerConfig.fromEnv(Map.of("C93_HTTP_BIND", "   ")).httpBind()).isEqualTo("0.0.0.0");
    }

    @Test
    @DisplayName("C93_HTTP_BIND restricts the control plane to one address (D-151)")
    void httpBindIsParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of("C93_HTTP_BIND", "127.0.0.1")).httpBind()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Roles default to every persona, so an unconfigured node is a full development node")
    void rolesDefaultToAll() {
        assertThat(ServerConfig.fromEnv(Map.of()).roles()).containsExactlyInAnyOrderElementsOf(ALL_ROLES);
    }

    @Test
    @DisplayName("C93_ROLES narrows a node to the personas it lists")
    void rolesAreParsedFromEnv() {
        ServerConfig config = ServerConfig.fromEnv(Map.of("C93_ROLES", " worker , data "));
        assertThat(config.roles()).containsExactlyInAnyOrder("worker", "data");
    }

    @Test
    @DisplayName("Capabilities default to none and come from C93_CAPABILITIES otherwise")
    void capabilitiesAreParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of()).capabilities()).isEmpty();
        assertThat(ServerConfig.fromEnv(Map.of("C93_CAPABILITIES", "js:v8, js:graaljs")).capabilities())
                .containsExactlyInAnyOrder("js:v8", "js:graaljs");
    }

    @Test
    @DisplayName("An unknown role is refused")
    void unknownRoleIsRefused() {
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_ROLES", "api,gateway")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C93_ROLES");
    }

    @Test
    @DisplayName("A standalone node defaults its data directory to $HOME/.cluster93/data")
    void standaloneDataDirectoryDefaultsUnderHome() {
        Path expected = Path.of(System.getProperty("user.home"), ".cluster93", "data");
        assertThat(ServerConfig.fromEnv(Map.of()).dataDirectory()).isEqualTo(expected);
    }

    @Test
    @DisplayName("An explicit C93_DATA_DIR wins over the default")
    void explicitDataDirectoryIsHonoured() {
        assertThat(ServerConfig.fromEnv(Map.of("C93_DATA_DIR", "/var/lib/cluster93")).dataDirectory())
                .isEqualTo(Path.of("/var/lib/cluster93"));
    }

    @Test
    @DisplayName("A clustered node without an explicit directory stays in memory, so nodes on one host don't share one")
    void clusteredDataDirectoryStaysUnsetWithoutAnExplicitPath() {
        assertThat(ServerConfig.fromEnv(Map.of("C93_CLUSTERED", "true")).dataDirectory()).isNull();
    }

    @Test
    @DisplayName("The console host is off by default and parsed (lower-cased) from C93_CONSOLE_DOMAIN")
    void consoleDomainIsParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of()).consoleDomain()).isNull();
        assertThat(ServerConfig.fromEnv(Map.of("C93_CONSOLE_DOMAIN", " Console.Internal.Example "))
                .consoleDomain()).isEqualTo("console.internal.example");
    }

    @Test
    @DisplayName("C93_CONSOLE_ALLOWED_CIDRS is a comma-separated list, trimmed, empty entries dropped")
    void consoleAllowedCidrsAreParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of()).consoleAllowedCidrs()).isEmpty();
        assertThat(ServerConfig.fromEnv(Map.of("C93_CONSOLE_ALLOWED_CIDRS", " 127.0.0.1/32 , 10.0.0.0/8 ,,"))
                .consoleAllowedCidrs()).containsExactly("127.0.0.1/32", "10.0.0.0/8");
    }
}
