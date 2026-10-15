package cluster93.runner.protocol.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Detection of this host's cgroup v2 shape (D-158): the node must use its own delegated cgroup, not the absolute
 * {@code /sys/fs/cgroup/cluster93} that only root could ever write.
 */
class CgroupSupportTest {

    @Test
    @DisplayName("Detection uses the node's own cgroup and reports the delegated controllers (D-158)")
    void detectionUsesTheNodesOwnCgroup(@TempDir Path mount) throws Exception {
        Path unit = mount.resolve("system.slice/cluster93.service");
        Files.createDirectories(unit);
        Files.writeString(unit.resolve("cgroup.controllers"), "cpuset cpu io memory hugetlb pids\n");

        CgroupSupport support = CgroupSupport.detect(mount, "/system.slice/cluster93.service", null);

        assertThat(support.available()).isTrue();
        assertThat(support.base()).isEqualTo(unit.resolve("cluster93"));
        assertThat(support.cpu()).isTrue();
        assertThat(support.memory()).isTrue();
        assertThat(support.detail()).contains(unit.toString()).contains("cpu");
    }

    @Test
    @DisplayName("An explicit base (-Dcluster93.cgroup.dir) wins over detection (D-158)")
    void anExplicitBaseWins(@TempDir Path mount) throws Exception {
        Path unit = mount.resolve("system.slice/x.service");
        Files.createDirectories(unit);
        Path override = mount.resolve("somewhere/else");

        CgroupSupport support = CgroupSupport.detect(mount, "/system.slice/x.service", override);

        assertThat(support.base()).isEqualTo(override);
    }

    @Test
    @DisplayName("No own cgroup: fall back to the mount, with no controllers (D-158)")
    void noOwnCgroupFallsBackToTheMount(@TempDir Path mount) {
        CgroupSupport support = CgroupSupport.detect(mount, null, null);

        assertThat(support.base()).isEqualTo(mount.resolve("cluster93"));
        assertThat(support.controllers()).isEmpty();
        assertThat(support.cpu()).isFalse();
        assertThat(support.memory()).isFalse();
    }

    @Test
    @DisplayName("An unwritable base is reported unavailable, rather than silently 'available' (D-158)")
    void anUnwritableBaseIsUnavailable(@TempDir Path mount) throws Exception {
        Path readOnly = Files.createDirectories(mount.resolve("readonly"));
        readOnly.toFile().setWritable(false);
        assumeThat(readOnly.toFile().canWrite()).as("not running as root").isFalse();

        try {
            CgroupSupport support = CgroupSupport.detect(mount, "/system.slice/x.service", readOnly.resolve("cluster93"));

            assertThat(support.available()).isFalse();
            assertThat(support.base()).isNull();
            assertThat(support.detail()).contains("not writable");
        } finally {
            readOnly.toFile().setWritable(true);
        }
    }
}
