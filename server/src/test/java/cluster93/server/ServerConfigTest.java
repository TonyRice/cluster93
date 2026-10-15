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
    @DisplayName("The per-tenant job cap is its own env, defaulting to the whole node (D-156)")
    void maxJobsPerTenantIsItsOwnSetting() {
        // Not a fraction of capacity: one tenant is not throttled by default, and an operator lowers it for
        // multi-tenancy. Deriving it as capacity/2 halved a tenant's concurrency and stalled the process-mode tests.
        assertThat(ServerConfig.fromEnv(Map.of()).maxJobsPerTenant()).isEqualTo(8);
        assertThat(ServerConfig.fromEnv(Map.of("C93_CAPACITY", "16")).maxJobsPerTenant()).isEqualTo(16);
        // Lowered for multi-tenancy, which is how a node serves several tenants concurrently.
        assertThat(ServerConfig.fromEnv(Map.of("C93_MAX_JOBS_PER_TENANT", "2")).maxJobsPerTenant()).isEqualTo(2);
    }

    @Test
    @DisplayName("Jobs per worker is its own env, and one worker per job is not the model (D-155)")
    void maxJobsPerWorkerIsItsOwnSetting() {
        assertThat(ServerConfig.fromEnv(Map.of("C93_MAX_JOBS_PER_WORKER", "4")).maxJobsPerWorker()).isEqualTo(4);
        // The switch is on: a worker runs several jobs at once (D-155). Held at 1 only while a cancel still took
        // down the shared channel; release()/poison() now separate a job's outcome from the worker's health.
        assertThat(ServerConfig.fromEnv(Map.of()).maxJobsPerWorker()).isEqualTo(4);
    }

    @Test
    @DisplayName("The node-wide worker cap is its own env, defaulting to the node's concurrency (D-156)")
    void maxWorkersIsItsOwnSetting() {
        // A live worker always holds at least one job, so the node never needs more workers than concurrent jobs.
        assertThat(ServerConfig.fromEnv(Map.of("C93_CAPACITY", "16")).maxWorkers()).isEqualTo(16);
        // An operator lowers it to bound process memory on a small host.
        assertThat(ServerConfig.fromEnv(Map.of("C93_CAPACITY", "16", "C93_MAX_WORKERS", "3")).maxWorkers()).isEqualTo(3);
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_MAX_WORKERS", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C93_MAX_WORKERS");
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
