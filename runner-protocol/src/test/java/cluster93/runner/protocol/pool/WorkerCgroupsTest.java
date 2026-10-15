package cluster93.runner.protocol.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-tenant cgroup layout (D-158, D-163): a tenant's {@code cpu.max} bounds its aggregate CPU, and each worker —
 * a child of the tenant — carries the memory bound.
 */
class WorkerCgroupsTest {

    @Test
    @DisplayName("A tenant's cpu.max and a worker's memory live under the tenant (D-158, D-163)")
    void aTenantsCpuAndAWorkersMemoryAreWrittenUnderTheTenant(@TempDir Path base) throws Exception {
        WorkerCgroups cgroups = new WorkerCgroups(base);
        cgroups.tenantCpuPercent(50); // half a core

        assertThat(cgroups.applyTenantCpu("acme")).isTrue();
        assertThat(base.resolve("tenant-acme/cpu.max")).hasContent("50000 100000");

        assertThat(cgroups.applyWorker("acme", 4242, 100_000_000L, 80_000_000L)).isTrue();
        Path worker = base.resolve("tenant-acme/worker-4242");
        assertThat(worker.resolve("memory.max")).hasContent("100000000");
        assertThat(worker.resolve("memory.high")).hasContent("80000000");
        assertThat(worker.resolve("cgroup.procs")).hasContent("4242");

        cgroups.cleanup("acme", 4242);
        assertThat(worker).as("a non-empty dir cannot be rmdir'd").exists();
        // On a real cgroup fs the kernel empties the directory when the process exits; a plain tmpdir keeps the
        // files, so emulate that before cleanup removes it.
        Files.delete(worker.resolve("memory.max"));
        Files.delete(worker.resolve("memory.high"));
        Files.delete(worker.resolve("cgroup.procs"));
        cgroups.cleanup("acme", 4242);
        assertThat(worker).doesNotExist();
    }

    @Test
    @DisplayName("A tenant name is sanitised into a cgroup directory name (D-158)")
    void aTenantNameIsSanitised(@TempDir Path base) throws Exception {
        WorkerCgroups cgroups = new WorkerCgroups(base);

        assertThat(cgroups.applyWorker("acme/../globex corp", 7, 1_000L, 500L)).isTrue();
        assertThat(base.resolve("tenant-acme_.._globex_corp/worker-7")).isDirectory();
    }

    @Test
    @DisplayName("No CPU ceiling configured means no cpu.max is written (D-158)")
    void unlimitedTenantCpuWritesNothing(@TempDir Path base) {
        WorkerCgroups cgroups = new WorkerCgroups(base);
        cgroups.tenantCpuPercent(0);

        assertThat(cgroups.applyTenantCpu("acme")).isFalse();
        assertThat(base.resolve("tenant-acme/cpu.max")).doesNotExist();
    }

    @Test
    @DisplayName("With no usable cgroup, nothing is applied and nothing throws (D-158)")
    void anUnavailableBaseAppliesNothing(@TempDir Path base) {
        WorkerCgroups cgroups = new WorkerCgroups(
                new CgroupSupport(null, null, false, Set.of(), "test: unavailable"));

        assertThat(cgroups.isAvailable()).isFalse();
        assertThat(cgroups.applyTenantCpu("acme")).isFalse();
        assertThat(cgroups.applyWorker("acme", 1, 100L, 50L)).isFalse();
        assertThat(cgroups.checkOomKilled("acme", 1)).isFalse();
        cgroups.cleanup("acme", 1); // no-op, must not throw
        assertThat(base).isEmptyDirectory();
    }
}
