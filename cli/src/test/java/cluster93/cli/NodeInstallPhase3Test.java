package cluster93.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3: checkout install, node join, and the generated local worker services (D-190, D-191). */
class NodeInstallPhase3Test {

    @TempDir
    Path home;

    private Path repo;

    private Io io(String... extra) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home.toString());
        env.put("C93_REPO", repo.toString());
        env.put("C93_SKIP_PORT_CHECK", "1");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            env.put(extra[i], extra[i + 1]);
        }
        return new Io(env, home.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), false);
    }

    private void checkout() throws Exception {
        repo = Files.createDirectories(home.resolve("repo"));
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repo.resolve("server"));
        Files.createDirectories(repo.resolve("worker"));
        Files.createDirectories(repo.resolve("server/target"));
        Files.write(repo.resolve("server/target/c93.jar"), new byte[] {1, 2, 3});
        Files.createDirectories(repo.resolve("bin"));
        for (String worker : new String[] {"worker-javet", "worker-graaljs"}) {
            Path binary = repo.resolve("bin").resolve(worker);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true, false);
        }
    }

    @Test
    void checkoutInstallCopiesTheTreeAndWritesRolesAndJoin() throws Exception {
        checkout();
        Path program = home.resolve("program");
        String join = "c93_nodebundle_initial";

        var result = NodeInstall.installCheckout(io(), false, program, "worker", join, false, true);

        assertThat(result.get("checkout").asBoolean()).isTrue();
        assertThat(program.resolve("c93.jar")).exists();
        assertThat(program.resolve("bin/worker-javet")).exists();
        assertThat(program.resolve("bin/worker-javet").toFile().canExecute()).isTrue();
        NodeInstall.Layout layout = NodeInstall.userLayout(io(), program);
        String env = Files.readString(layout.envFile());
        assertThat(env).contains("C93_ROLES=worker").contains("C93_JOIN=" + join)
                .contains("C93_WORKER_MODE=process");
        assertThat(layout.unitFile()).exists();
        assertThat(Files.readString(NodeInstall.metaFile(layout))).contains("version=checkout");
    }

    @Test
    void nodeJoinRewritesTheEnvWithoutRestarting() throws Exception {
        checkout();
        Path program = home.resolve("program");
        NodeInstall.installCheckout(io(), false, program, null, "c93_nodebundle_initial", false, true);
        Io io = io();

        int code = Main.run(io, "node", "join", "c93_nodebundle_replacement", "--members", "node1,node2", "--no-restart");

        assertThat(code).isZero();
        NodeInstall.Layout layout = NodeInstall.userLayout(io, program);
        assertThat(Files.readString(layout.envFile()))
                .contains("C93_JOIN=c93_nodebundle_replacement")
                .contains("C93_CLUSTERED=true")
                .contains("C93_CLUSTER_TLS=required")
                .contains("C93_CLUSTER_MEMBERS=node1,node2");
    }

    @Test
    void composeRendersOneServicePerWorkerWithTheReleaseWrapper() throws Exception {
        checkout();
        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, home.resolve("data"));
        Map<String, String> config = new LinkedHashMap<>();
        config.put("C93_NODE_ID", "node1");
        config.put("C93_CLUSTERED", "true");
        config.put("C93_WORKER_DEFAULT_MODE", "graaljs");
        config.put("C93_WORKER_SOCKET", "/run/cluster93/workers/node.sock");

        String compose = NodeInstall.renderCompose(layout, config, "docker", "cluster93-node:test",
                "127.0.0.1", false, 2, "worker");

        assertThat(compose).contains("  worker-1:").contains("  worker-2:")
                .contains("restart: on-failure")
                .contains("/run/secrets/c93-worker-secret:ro")
                .contains("worker-graaljs ipc")
                .contains("networks: [egress]")
                .contains("egress:")
                .contains("$$left").contains("$$((left - 1))");
        assertThat(compose.split("worker-1:", -1)).hasSize(2);
    }

    @Test
    void aNodePlacementGivesWorkerContainersNoNetwork() throws Exception {
        checkout();
        NodeInstall.Layout layout = NodeInstall.containerLayout(io(), false, home.resolve("data"));
        Map<String, String> config = Map.of("C93_NODE_ID", "node1", "C93_WORKER_DEFAULT_MODE", "javet");

        String compose = NodeInstall.renderCompose(layout, config, "docker", "cluster93-node:test",
                "127.0.0.1", false, 1, "node");

        assertThat(compose).contains("network_mode: none").contains("worker-javet ipc")
                .doesNotContain("networks: [egress]");
    }
    @Test
    void aDockerInstallWithWorkersWritesTheSecretSocketAndServices() throws Exception {
        checkout();
        Path bin = Files.createDirectories(home.resolve("fake-bin"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\ncase \"$1\" in info) echo 'name=seccomp'; exit 0;; esac\nexit 0\n");
        docker.toFile().setExecutable(true, false);
        Path tls = Files.createDirectories(home.resolve("tls"));
        for (String file : new String[] {"node.crt", "node.key", "ca.crt"}) {
            Files.writeString(tls.resolve(file), file);
        }
        Path program = home.resolve("container-data");
        Io io = new Io(Map.of("HOME", home.toString(), "PATH", bin.toString(), "C93_SKIP_PORT_CHECK", "1"),
                home.resolve("config"), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), false);

        int code = Main.run(io, "node", "install", "--docker", "--image", "fake:tag", "--workers", "2",
                "--tls-dir", tls.toString(), "--no-start", "--dir", program.toString());

        assertThat(code).isZero();
        NodeInstall.Layout layout = NodeInstall.containerLayout(io, false, program);
        assertThat(layout.envFile()).exists();
        assertThat(Files.readString(layout.envFile()))
                .contains("C93_WORKER_SOCKET=/run/cluster93/workers/node.sock")
                .contains("C93_WORKER_SECRET_FILE=/run/secrets/c93-worker-secret")
                .contains("C93_WORKER_CONNECTIONS_MAX=3");
        assertThat(NodeInstall.workerSecretFile(layout)).exists();
        String compose = Files.readString(layout.composeFile());
        assertThat(compose).contains("worker-1:").contains("worker-2:")
                .contains("restart: on-failure").contains("fake:tag");
        assertThat(Files.readString(NodeInstall.metaFile(layout))).contains("workers=2");
    }

}
