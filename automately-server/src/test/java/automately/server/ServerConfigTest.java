package automately.server;

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

    private static final Set<String> ALL_ROLES = Set.of("api", "ingress", "worker", "data");

    @Test
    @DisplayName("Roles default to every persona, so an unconfigured node is a full development node")
    void rolesDefaultToAll() {
        assertThat(ServerConfig.fromEnv(Map.of()).roles()).containsExactlyInAnyOrderElementsOf(ALL_ROLES);
    }

    @Test
    @DisplayName("AUTOMATELY_ROLES narrows a node to the personas it lists")
    void rolesAreParsedFromEnv() {
        ServerConfig config = ServerConfig.fromEnv(Map.of("AUTOMATELY_ROLES", " worker , data "));
        assertThat(config.roles()).containsExactlyInAnyOrder("worker", "data");
    }

    @Test
    @DisplayName("Capabilities default to none and come from AUTOMATELY_CAPABILITIES otherwise")
    void capabilitiesAreParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of()).capabilities()).isEmpty();
        assertThat(ServerConfig.fromEnv(Map.of("AUTOMATELY_CAPABILITIES", "js:v8, js:graaljs")).capabilities())
                .containsExactlyInAnyOrder("js:v8", "js:graaljs");
    }

    @Test
    @DisplayName("An unknown role is refused")
    void unknownRoleIsRefused() {
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("AUTOMATELY_ROLES", "api,gateway")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTOMATELY_ROLES");
    }

    @Test
    @DisplayName("A standalone node defaults its data directory to $HOME/.automately/data")
    void standaloneDataDirectoryDefaultsUnderHome() {
        Path expected = Path.of(System.getProperty("user.home"), ".automately", "data");
        assertThat(ServerConfig.fromEnv(Map.of()).dataDirectory()).isEqualTo(expected);
    }

    @Test
    @DisplayName("An explicit AUTOMATELY_DATA_DIR wins over the default")
    void explicitDataDirectoryIsHonoured() {
        assertThat(ServerConfig.fromEnv(Map.of("AUTOMATELY_DATA_DIR", "/var/lib/automately")).dataDirectory())
                .isEqualTo(Path.of("/var/lib/automately"));
    }

    @Test
    @DisplayName("A clustered node without an explicit directory stays in memory, so nodes on one host don't share one")
    void clusteredDataDirectoryStaysUnsetWithoutAnExplicitPath() {
        assertThat(ServerConfig.fromEnv(Map.of("AUTOMATELY_CLUSTERED", "true")).dataDirectory()).isNull();
    }

    @Test
    @DisplayName("The console host is off by default and parsed (lower-cased) from AUTOMATELY_CONSOLE_DOMAIN")
    void consoleDomainIsParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of()).consoleDomain()).isNull();
        assertThat(ServerConfig.fromEnv(Map.of("AUTOMATELY_CONSOLE_DOMAIN", " Console.Internal.Example "))
                .consoleDomain()).isEqualTo("console.internal.example");
    }

    @Test
    @DisplayName("AUTOMATELY_CONSOLE_ALLOWED_CIDRS is a comma-separated list, trimmed, empty entries dropped")
    void consoleAllowedCidrsAreParsedFromEnv() {
        assertThat(ServerConfig.fromEnv(Map.of()).consoleAllowedCidrs()).isEmpty();
        assertThat(ServerConfig.fromEnv(Map.of("AUTOMATELY_CONSOLE_ALLOWED_CIDRS", " 127.0.0.1/32 , 10.0.0.0/8 ,,"))
                .consoleAllowedCidrs()).containsExactly("127.0.0.1/32", "10.0.0.0/8");
    }
}
