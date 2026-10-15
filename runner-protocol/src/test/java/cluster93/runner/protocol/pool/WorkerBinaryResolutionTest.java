package cluster93.runner.protocol.pool;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises worker binary auto-discovery and child-JVM classpath construction (D-129), plus the
 * {@link TenantWorkerPoolManager}'s per-mode binary routing.
 */
class WorkerBinaryResolutionTest {

    private static Vertx vertx;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @TempDir
    Path tempDir;

    // --- Binary auto-discovery ---

    @Test
    @DisplayName("An explicit absolute binary path is honoured as-is")
    void explicitAbsoluteBinaryIsHonoured() throws IOException {
        Path binary = executable(tempDir.resolve("explicit"), "worker-custom");
        assertThat(TenantWorkerPool.resolveBinary(binary.toString(), "javet")).isEqualTo(binary.toString());
    }

    @Test
    @DisplayName("An explicit relative binary resolves against the working directory")
    void explicitRelativeBinaryResolvesAgainstBaseDir() throws IOException {
        Path binary = executable(tempDir, "worker-custom");
        assertThat(TenantWorkerPool.resolveBinary("worker-custom", "javet", tempDir)).isEqualTo(binary.toString());
    }

    @Test
    @DisplayName("A missing explicit binary falls back to null")
    void missingExplicitBinaryIsNull() {
        assertNull(TenantWorkerPool.resolveBinary(tempDir.resolve("does-not-exist").toString(), "javet"));
    }

    @Test
    @DisplayName("Auto-discovers bin/worker-<mode> from the working directory")
    void autoDiscoversBinWorker() throws IOException {
        Path binary = executable(tempDir, "bin/worker-javet");
        assertThat(TenantWorkerPool.resolveBinary(null, "javet", tempDir)).isEqualTo(binary.toString());
    }

    @Test
    @DisplayName("Auto-discovers benchmarks/engines/bin/worker-<mode>")
    void autoDiscoversBenchmarksWorker() throws IOException {
        Path binary = executable(tempDir, "benchmarks/engines/bin/worker-graaljs");
        assertThat(TenantWorkerPool.resolveBinary(null, "graaljs", tempDir)).isEqualTo(binary.toString());
    }

    @Test
    @DisplayName("Auto-discovery returns null when no candidate exists")
    void autoDiscoveryReturnsNullWhenNothingFound() {
        assertNull(TenantWorkerPool.resolveBinary(null, "javet", tempDir));
    }

    @Test
    @DisplayName("A non-executable file is not treated as a binary")
    void nonExecutableFileIsIgnored() throws IOException {
        Path notExecutable = tempDir.resolve("bin/worker-javet");
        Files.createDirectories(notExecutable.getParent());
        Files.writeString(notExecutable, "not a binary");
        notExecutable.toFile().setExecutable(false);
        assertNull(TenantWorkerPool.resolveBinary(null, "javet", tempDir));
    }

    // --- Child-JVM classpath construction ---

    @Test
    @DisplayName("The worker classpath preserves the current JVM classpath")
    void workerClasspathPreservesCurrentClasspath() {
        String base = System.getProperty("java.class.path", "");
        String classpath = TenantWorkerPool.resolveWorkerClasspath();
        for (String entry : base.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                assertThat(classpath).contains(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
    }

    @Test
    @DisplayName("The worker classpath appends the worker jar when it has been built")
    void workerClasspathAppendsWorkerJar() throws IOException {
        Path jar = tempDir.resolve("worker/target/cluster93-worker-2.0.0-SNAPSHOT.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "dummy");

        String classpath = TenantWorkerPool.resolveWorkerClasspath("", tempDir);
        assertThat(classpath).contains(jar.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("The worker classpath falls back to target/classes when no jar exists")
    void workerClasspathFallsBackToClasses() throws IOException {
        Path classes = tempDir.resolve("worker/target/classes");
        Files.createDirectories(classes);

        String classpath = TenantWorkerPool.resolveWorkerClasspath("", tempDir);
        assertThat(classpath).contains(classes.toAbsolutePath().normalize().toString());
    }

    // --- TenantWorkerPoolManager per-mode routing ---

    @Test
    @DisplayName("Manager routes explicit per-mode binaries")
    void managerRoutesExplicitPerModeBinaries() {
        var manager = manager(Map.of("javet", "/opt/worker-javet", "graaljs", "/opt/worker-graaljs"), "javet");
        assertThat(manager.resolveModeBinary("javet")).isEqualTo("/opt/worker-javet");
        assertThat(manager.resolveModeBinary("graaljs")).isEqualTo("/opt/worker-graaljs");
    }

    @Test
    @DisplayName("Manager falls back to the default binary for a mode with no explicit binary")
    void managerFallsBackToDefaultBinary() {
        var manager = manager(Map.of("default", "/opt/worker-default"), "javet");
        assertThat(manager.resolveModeBinary("graaljs")).isEqualTo("/opt/worker-default");
    }

    @Test
    @DisplayName("Manager resolves a null or blank mode to the default mode")
    void managerResolvesNullModeToDefault() {
        var manager = manager(Map.of("javet", "/opt/worker-javet"), "javet");
        assertThat(manager.resolveModeBinary(null)).isEqualTo("/opt/worker-javet");
        assertThat(manager.resolveModeBinary("  ")).isEqualTo("/opt/worker-javet");
    }

    private static TenantWorkerPoolManager manager(Map<String, String> binaries, String defaultMode) {
        return new TenantWorkerPoolManager(vertx, binaries, defaultMode, 0, 8, 8, 4, 128,
                TenantWorkerPool.ReusePolicy.FRESH_PER_JOB, new WorkerCgroups(),
                cluster93.runner.protocol.server.NodeRpcAuthorizer.ALLOW_ALL);
    }

    private static Path executable(Path baseDir, String relative) throws IOException {
        Path path = baseDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        makeExecutable(path);
        return path;
    }

    private static void makeExecutable(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException e) {
            path.toFile().setExecutable(true);
        }
    }
}
