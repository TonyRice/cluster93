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
    @DisplayName("A worker decides egress by default, and the node can be asked to instead (D-173)")
    void egressPlacementIsItsOwnSetting() {
        // The default is the worker (D-173): it connects under the rules the node sends it, so the node stops being in
        // the path per connection and per byte. `node` keeps the older shape, where the node dials for every worker.
        assertThat(ServerConfig.fromEnv(Map.of()).egressPlacement()).isEqualTo("worker");
        assertThat(ServerConfig.fromEnv(Map.of("C93_EGRESS_PLACEMENT", "node")).egressPlacement()).isEqualTo("node");
        assertThat(ServerConfig.fromEnv(Map.of("C93_EGRESS_PLACEMENT", " WORKER ")).egressPlacement())
                .as("an operator writing it by hand is not punished for the case or the spaces").isEqualTo("worker");
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_EGRESS_PLACEMENT", "nowhere")))
                .hasMessageContaining("C93_EGRESS_PLACEMENT");
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
    @DisplayName("The node-wide worker memory ceiling is its own env, defaulting to what the count implies (D-163)")
    void maxWorkerMemoryIsItsOwnSetting() {
        // Default: maxWorkers × (maxJobsPerWorker × maxHeapMb + overhead) = 8 × (4 × 128 + 128) MB, so the ceiling
        // never binds before C93_MAX_WORKERS — a fresh node behaves exactly as it did.
        assertThat(ServerConfig.fromEnv(Map.of()).maxWorkerMemoryMb()).isEqualTo(8L * (4L * 128 + 128));
        // An operator lowers it to bound memory directly.
        assertThat(ServerConfig.fromEnv(Map.of("C93_MAX_WORKER_MEMORY_MB", "2048")).maxWorkerMemoryMb()).isEqualTo(2048);
        // 0 means unbounded.
        assertThat(ServerConfig.fromEnv(Map.of("C93_MAX_WORKER_MEMORY_MB", "0")).maxWorkerMemoryMb()).isZero();
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_MAX_WORKER_MEMORY_MB", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C93_MAX_WORKER_MEMORY_MB");
    }

    @Test
    @DisplayName("The per-tenant CPU ceiling and the cgroup switches are their own settings (D-158)")
    void cgroupSettings() {
        // CPU is unlimited by default — a ceiling is the operator's call.
        assertThat(ServerConfig.fromEnv(Map.of()).maxCpuPerTenant()).isZero();
        assertThat(ServerConfig.fromEnv(Map.of("C93_MAX_CPU_PER_TENANT", "150")).maxCpuPerTenant()).isEqualTo(150);
        // Warn by default; an operator can require limits and refuse to start without them.
        assertThat(ServerConfig.fromEnv(Map.of()).requireCgroupLimits()).isFalse();
        assertThat(ServerConfig.fromEnv(Map.of("C93_REQUIRE_CGROUP_LIMITS", "true")).requireCgroupLimits()).isTrue();
        // Preparing the node's own cgroup is on by default; C93_CGROUP_PREPARE=off turns it off.
        assertThat(ServerConfig.fromEnv(Map.of()).cgroupPrepare()).isTrue();
        assertThat(ServerConfig.fromEnv(Map.of("C93_CGROUP_PREPARE", "off")).cgroupPrepare()).isFalse();
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_MAX_CPU_PER_TENANT", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C93_MAX_CPU_PER_TENANT");
    }

    @Test
    @DisplayName("A job that cannot get capacity queues by default, and can be told to reject instead (D-156)")
    void jobWhenFullIsItsOwnSetting() {
        assertThat(ServerConfig.fromEnv(Map.of()).jobWhenFull())
                .isEqualTo(cluster93.core.scheduler.JobScheduler.WhenFull.QUEUE);
        assertThat(ServerConfig.fromEnv(Map.of("C93_JOB_WHEN_FULL", "reject")).jobWhenFull())
                .isEqualTo(cluster93.core.scheduler.JobScheduler.WhenFull.REJECT);
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_JOB_WHEN_FULL", "maybe")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C93_JOB_WHEN_FULL");
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

    @Test
    @DisplayName("The worker socket is its own setting, and needs the process pool it accepts workers for (D-166)")
    void workerSocketSettings() {
        ServerConfig base = ServerConfig.fromEnv(Map.of());
        assertThat(base.workerSocket()).as("no socket, no container workers, is the default").isNull();
        assertThat(base.workerSecretFile()).isNull();
        assertThat(base.workerConnectionsMax())
                .as("bounded out of the box by the same quantity the memory ceiling is derived from")
                .isEqualTo(base.maxWorkers());

        ServerConfig socket = ServerConfig.fromEnv(Map.of(
                "C93_WORKER_SOCKET", "/run/cluster93/workers/node.sock",
                "C93_WORKER_SECRET_FILE", "/run/secrets/c93-worker-secret",
                "C93_WORKER_CONNECTIONS_MAX", "12"));
        assertThat(socket.workerSocket()).isEqualTo(Path.of("/run/cluster93/workers/node.sock"));
        assertThat(socket.workerSecretFile()).isEqualTo(Path.of("/run/secrets/c93-worker-secret"));
        assertThat(socket.workerConnectionsMax()).isEqualTo(12);

        // The socket accepts workers for the process pool; embedded runs have no pool to put them in.
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of(
                "C93_WORKER_SOCKET", "/tmp/node.sock", "C93_WORKER_MODE", "embedded")))
                .hasMessageContaining("C93_WORKER_SOCKET needs C93_WORKER_MODE=process");
        assertThatThrownBy(() -> ServerConfig.fromEnv(Map.of("C93_WORKER_CONNECTIONS_MAX", "-1")))
                .hasMessageContaining("C93_WORKER_CONNECTIONS_MAX");
    }
}
