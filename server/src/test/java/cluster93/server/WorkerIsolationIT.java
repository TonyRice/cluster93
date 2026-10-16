package cluster93.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real container round trip (D-166, D-167), opt-in: it runs {@code scripts/worker-isolation-check.sh}, which
 * builds a node image from this checkout, starts a node as a process, starts a worker container against its socket,
 * and asserts what only a real engine can decide — the identity reaches the {@code 0600} socket, a job round-trips,
 * the wrong secret is refused, and the worker container has no network reachability.
 *
 * <p>Deliberately a wrapper rather than a second implementation: the script is what an operator runs on a machine
 * whose kernel and engine we cannot see, and the assertions are the same ones. Never run automatically — it needs a
 * container engine and minutes; skip it with {@code -DskipITs}, like {@link DockerClusterIT}.
 */
class WorkerIsolationIT {

    @Test
    @DisplayName("A worker container joins a node over its worker socket, and only as the right identity (D-166, D-149)")
    void aWorkerContainerJoinsTheNodeOverTheSocket() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path script = repo.resolve("scripts").resolve("worker-isolation-check.sh");
        assumeTrue(Files.isExecutable(script), "no isolation check script at " + script);
        assumeTrue(Files.isRegularFile(repo.resolve("server").resolve("target").resolve("c93.jar")),
                "package the server first: ./mvnw -q -pl server -am package -DskipTests");

        Process check = new ProcessBuilder(script.toString())
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(check.waitFor(20, TimeUnit.MINUTES)).as("the isolation check must finish").isTrue();
        int code = check.exitValue();

        // A checkout whose native workers predate the secret (D-168) cannot present it: the check says so and skips,
        // rather than reporting a deployment failure the deployment did not cause.
        assumeTrue(!output.contains("is missing or older than the worker sources"),
                "rebuild the native workers first: ./scripts/build-workers.sh");
        assertThat(code).as(output).isZero();
        assertThat(output).as("at least one real case must have run").contains("PASS");
    }
}
