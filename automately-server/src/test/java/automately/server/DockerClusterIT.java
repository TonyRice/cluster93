package automately.server;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phase 2 Docker check, scripted: three containers built from the jar `mvn package` produced, a
 * {@code SIGKILL} to the container running a job mid-step, and the outcome checked through a survivor.
 * Uses production timings (1 s heartbeat). Skipped without Docker; skip explicitly with {@code -DskipITs}.
 */
@Testcontainers(disabledWithoutDocker = true)
class DockerClusterIT {

    private static final String TENANT = "it";

    static {
        // The JDK's HTTP client refuses a custom Host header unless this is set before it loads; the ingress routes by Host.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    /** Built once per JVM from the Dockerfile's {@code local-jar} stage. */
    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("automately-it", false)
            .withFileFromPath("Dockerfile", projectRoot().resolve("Dockerfile"))
            .withFileFromPath("automately-server/target/automately.jar", projectRoot().resolve("automately-server/target/automately.jar"))
            .withTarget("local-jar");

    /** Six customers, 1.5 s each, every one a ctx.step: a kill in the middle lands inside a step. */
    private static final String INVOICES = """
            export default async (input, ctx) => {
              if (ctx.isResume) console.log(`resuming: attempt ${ctx.attempt}, skipping ${ctx.completedSteps.join(', ')}`);
              for (const customer of input.customers) {
                await ctx.step(`invoice:${customer}`, ({ key }) => {
                  console.log(`invoicing ${customer} on attempt ${ctx.attempt}`);
                  const until = Date.now() + 1500;
                  while (Date.now() < until) {}
                  return { invoice: `inv-${customer}`, key };
                });
              }
              return { attempt: ctx.attempt, previous: ctx.previous && ctx.previous.outcome };
            }
            """;

    /** Charges, then works for a long time before sending the receipt: a kill lands between the two. */
    private static final String CHARGE_THEN_RECEIPT = """
            export default async (input, ctx) => {
              await ctx.step('charge', () => 'ch_1');
              const until = Date.now() + 30000;
              while (Date.now() < until) {}
              await ctx.step('receipt', () => 'sent');
            }
            """;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, GenericContainer<?>> nodes = new LinkedHashMap<>();
    private final Map<String, ToStringConsumer> logs = new LinkedHashMap<>();
    private Network network;

    private static Path projectRoot() {
        // Failsafe runs with the module directory as the working directory.
        return Path.of("").toAbsolutePath().getParent();
    }

    @BeforeEach
    void startCluster() {
        network = Network.newNetwork();
        for (String id : List.of("node1", "node2", "node3")) {
            ToStringConsumer output = new ToStringConsumer();
            GenericContainer<?> node = new GenericContainer<>(IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases(id)
                    .withEnv("AUTOMATELY_NODE_ID", id)
                    .withEnv("AUTOMATELY_CLUSTERED", "true")
                    .withEnv("AUTOMATELY_CLUSTER_MEMBERS", "node1,node2,node3")
                    .withEnv("AUTOMATELY_CAPACITY", "2")
                    .withEnv("AUTOMATELY_CLUSTER_SIZE", "3")
                    .withEnv("AUTOMATELY_MAX_HEAP_MB", "64")
                    .withExposedPorts(8080, 8000)
                    .withLogConsumer(output)
                    .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)));
            nodes.put(id, node);
            logs.put(id, output);
        }
        Startables.deepStart(nodes.values()).join();
        awaitCondition(Duration.ofSeconds(60), "3 live nodes", () -> liveNodes(anyNode()).size() == 3);
    }

    @AfterEach
    void stopCluster() {
        nodes.values().forEach(GenericContainer::stop);
        network.close();
    }

    @Test
    void resumeAfterSigkillSkipsCompletedStepsOnAnotherNode() throws Exception {
        String id = submit(INVOICES, "resume", new JsonObject().put("customers", new JsonArray(List.of("c1", "c2", "c3", "c4", "c5", "c6"))));
        awaitCondition(Duration.ofSeconds(30), "two invoices done", () -> stepNames(anyNode(), id).size() >= 2);
        String victim = job(anyNode(), id).getString("node");
        List<String> doneBeforeKill = stepNames(anyNode(), id);

        sigkill(victim);
        String survivor = survivorOtherThan(victim);
        CompletableFuture<HttpResponse<String>> events = http.sendAsync(request(survivor, "/v1/jobs/" + id + "/events").build(),
                HttpResponse.BodyHandlers.ofString());
        JsonObject done = awaitTerminal(survivor, id);

        assertThat(done.getString("status")).as(diagnostics()).isEqualTo("SUCCEEDED");
        assertThat(done.getInteger("attempt")).isEqualTo(2);
        assertThat(done.getString("node")).isNotEqualTo(victim);
        assertThat(done.getJsonObject("output")).isEqualTo(new JsonObject().put("attempt", 2).put("previous", "ORPHANED"));
        assertThat(attemptOutcomes(done)).containsExactly(victim + ":ORPHANED", done.getString("node") + ":SUCCEEDED");

        // Steps finished before the kill were not repeated; every invoice exists exactly once.
        List<String> attempt2Logs = done.getJsonArray("logs").stream().map(String::valueOf).toList();
        for (String step : doneBeforeKill) {
            String customer = step.substring("invoice:".length());
            assertThat(attempt2Logs).as("attempt 2 must not redo " + step).doesNotContain("invoicing " + customer + " on attempt 2");
        }
        JsonArray steps = steps(survivor, id).getJsonArray("steps");
        assertThat(steps.stream().map(s -> ((JsonObject) s).getString("name")))
                .containsExactly("invoice:c1", "invoice:c2", "invoice:c3", "invoice:c4", "invoice:c5", "invoice:c6");
        assertThat(steps.stream().map(s -> ((JsonObject) s).getJsonObject("result").getString("key")))
                .allMatch(key -> key.startsWith(id + ":invoice:"));

        String stream = events.get().body();
        assertThat(stream).contains("event: attempt", "\"previousOutcome\":\"ORPHANED\"", "event: end", "\"status\":\"SUCCEEDED\"");
    }

    @Test
    void failPolicyAfterSigkillAndImmediateRestartEndsOrphaned() throws Exception {
        String id = submit(CHARGE_THEN_RECEIPT, "fail", new JsonObject());
        awaitCondition(Duration.ofSeconds(30), "charge done", () -> stepNames(anyNode(), id).contains("charge"));
        String victim = job(anyNode(), id).getString("node");

        // Same name, same container, new process: the restarted node must not hide its old job (D-009).
        sigkill(victim);
        DockerClientFactory.instance().client().startContainerCmd(nodes.get(victim).getContainerId()).exec();
        String survivor = survivorOtherThan(victim);
        JsonObject done = awaitTerminal(survivor, id);

        assertThat(done.getString("status")).as(diagnostics()).isEqualTo("ORPHANED");
        assertThat(done.getInteger("attempt")).isEqualTo(1);
        assertThat(done.getString("error")).contains(victim).contains("\"fail\"");
        assertThat(stepNames(survivor, id)).containsExactly("charge");
        awaitCondition(Duration.ofSeconds(60), "restarted " + victim + " rejoins", () -> liveNodes(survivor).contains(victim));
    }

    @Test
    void aServiceSurvivesSigkillOfItsNodeAndKeepsItsCheckpoint() throws Exception {
        HttpResponse<String> created = http.send(request(anyNode(), "/v1/services/counter")
                .PUT(HttpRequest.BodyPublishers.ofString(new JsonObject().put("script", ChaosTest.COUNTING_SERVICE).encode())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String[] firstJob = new String[1];
        awaitCondition(Duration.ofSeconds(30), "service counting", () -> {
            JsonObject service = getJson(anyNode(), "/v1/services/counter");
            firstJob[0] = service.getString("currentJobId");
            return "RUNNING".equals(service.getString("status")) && checkpointCount(anyNode(), firstJob[0]) >= 5;
        });
        String victim = job(anyNode(), firstJob[0]).getString("node");

        sigkill(victim);
        String survivor = survivorOtherThan(victim);
        JsonObject[] restarted = new JsonObject[1];
        awaitCondition(Duration.ofSeconds(60), "service restarted on a survivor", () -> {
            restarted[0] = getJson(survivor, "/v1/services/counter");
            return "RUNNING".equals(restarted[0].getString("status")) && restarted[0].getInteger("runs") >= 2;
        });

        String secondJob = restarted[0].getString("currentJobId");
        assertThat(job(survivor, secondJob).getString("node")).isNotEqualTo(victim);
        assertThat(restarted[0].getJsonObject("lastExit").getString("status")).isEqualTo("ORPHANED");
        assertThat(checkpointCount(survivor, secondJob)).as("continued from the killed run's count").isGreaterThanOrEqualTo(5);
    }

    private int checkpointCount(String node, String jobId) {
        Object checkpoint = steps(node, jobId).getValue("checkpoint");
        return checkpoint == null ? 0 : ((JsonObject) checkpoint).getInteger("count");
    }

    @Test
    void httpTrafficMovesToASurvivorAfterSigkillOfTheServingNode() throws Exception {
        HttpResponse<String> created = http.send(request(anyNode(), "/v1/services/web")
                .PUT(HttpRequest.BodyPublishers.ofString(new JsonObject().put("script", HostApisTest.WEB).encode())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String[] firstJob = new String[1];
        awaitCondition(Duration.ofSeconds(30), "web running", () -> {
            JsonObject service = getJson(anyNode(), "/v1/services/web");
            firstJob[0] = service.getString("currentJobId");
            return "RUNNING".equals(service.getString("status"));
        });
        String victim = job(anyNode(), firstJob[0]).getString("node");
        String survivor = survivorOtherThan(victim);
        awaitCondition(Duration.ofSeconds(10), "served through " + survivor, () -> "hello from run 1".equals(ingressGet(survivor, "/hello").body()));

        sigkill(victim);
        awaitCondition(Duration.ofSeconds(60), "served again after the kill", () -> {
            HttpResponse<String> response = ingressGet(survivor, "/hello");
            return response.statusCode() == 200 && response.body().equals("hello from run 2");
        });
    }

    private HttpResponse<String> ingressGet(String node, String path) throws Exception {
        GenericContainer<?> container = nodes.get(node);
        return http.send(HttpRequest.newBuilder(URI.create("http://" + container.getHost() + ":" + hostPort(node, 8000) + path))
                .header("Host", "web--" + TENANT + ".localhost").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everythingSurvivesStoppingAndStartingEveryContainer() throws Exception {
        HttpResponse<String> created = http.send(request(anyNode(), "/v1/services/counter")
                .PUT(HttpRequest.BodyPublishers.ofString(new JsonObject().put("script", ChaosTest.COUNTING_SERVICE).encode())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String[] firstRun = new String[1];
        awaitCondition(Duration.ofSeconds(30), "service counting", () -> {
            JsonObject service = getJson(anyNode(), "/v1/services/counter");
            firstRun[0] = service.getString("currentJobId");
            return "RUNNING".equals(service.getString("status")) && checkpointCount(anyNode(), firstRun[0]) >= 5;
        });
        String jobId = submit("export default async () => { await kv.set('greeting', 'survives'); return 'stored'; }", "fail", new JsonObject());
        awaitCondition(Duration.ofSeconds(30), "job done", () -> "SUCCEEDED".equals(job(anyNode(), jobId).getString("status")));

        // A clean shutdown of the whole cluster, then a cold start, like a host reboot.
        var docker = DockerClientFactory.instance().client();
        for (GenericContainer<?> node : nodes.values()) {
            docker.stopContainerCmd(node.getContainerId()).withTimeout(30).exec();
        }
        for (GenericContainer<?> node : nodes.values()) {
            docker.startContainerCmd(node.getContainerId()).exec();
        }
        awaitCondition(Duration.ofSeconds(180), "3 live nodes after the restart", () -> liveNodes(anyNode()).size() == 3);

        assertThat(job(anyNode(), jobId).getString("output")).isEqualTo("stored");
        String reader = submit("export default () => kv.get('greeting')", "fail", new JsonObject());
        awaitCondition(Duration.ofSeconds(30), "kv read", () -> "survives".equals(job(anyNode(), reader).getString("output")));
        JsonObject[] service = new JsonObject[1];
        awaitCondition(Duration.ofSeconds(60), "service running again", () -> {
            service[0] = getJson(anyNode(), "/v1/services/counter");
            return "RUNNING".equals(service[0].getString("status")) && service[0].getInteger("runs") >= 2;
        });
        awaitCondition(Duration.ofSeconds(30), "count carried over", () -> checkpointCount(anyNode(), service[0].getString("currentJobId")) >= 5);
    }

    /** Looked up each time: Docker assigns new host ports when a container restarts. */
    private int hostPort(String node, int containerPort) {
        var ports = DockerClientFactory.instance().client().inspectContainerCmd(nodes.get(node).getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings();
        return Integer.parseInt(ports.get(new com.github.dockerjava.api.model.ExposedPort(containerPort))[0].getHostPortSpec());
    }

    // --- cluster helpers ---

    private void sigkill(String node) {
        DockerClientFactory.instance().client().killContainerCmd(nodes.get(node).getContainerId()).withSignal("SIGKILL").exec();
    }

    private String anyNode() {
        return nodes.keySet().stream().filter(this::isRunning).findFirst().orElseThrow();
    }

    private String survivorOtherThan(String victim) {
        return nodes.keySet().stream().filter(id -> !id.equals(victim)).findFirst().orElseThrow();
    }

    private boolean isRunning(String node) {
        return Boolean.TRUE.equals(DockerClientFactory.instance().client()
                .inspectContainerCmd(nodes.get(node).getContainerId()).exec().getState().getRunning());
    }

    private String diagnostics() {
        return logs.entrySet().stream()
                .map(e -> "--- " + e.getKey() + " (last 30 lines) ---\n" + tail(e.getValue().toUtf8String(), 30))
                .collect(Collectors.joining("\n"));
    }

    private static String tail(String text, int lines) {
        List<String> all = text.lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    // --- API helpers ---

    private HttpRequest.Builder request(String node, String path) {
        GenericContainer<?> container = nodes.get(node);
        return HttpRequest.newBuilder(URI.create("http://" + container.getHost() + ":" + hostPort(node, 8080) + path))
                .header("X-Automately-Tenant", TENANT)
                .timeout(Duration.ofSeconds(60));
    }

    private JsonObject getJson(String node, String path) {
        try {
            return new JsonObject(http.send(request(node, path).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) {
            throw new IllegalStateException("GET " + path + " on " + node + " failed", e);
        }
    }

    private String submit(String script, String onOrphan, JsonObject input) throws Exception {
        JsonObject body = new JsonObject().put("script", script).put("onOrphan", onOrphan).put("input", input).put("timeoutMs", 60_000);
        HttpResponse<String> response = http.send(request(anyNode(), "/v1/jobs")
                .POST(HttpRequest.BodyPublishers.ofString(body.encode())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return new JsonObject(response.body()).getString("id");
    }

    private JsonObject job(String node, String id) {
        return getJson(node, "/v1/jobs/" + id);
    }

    private JsonObject steps(String node, String id) {
        return getJson(node, "/v1/jobs/" + id + "/steps");
    }

    private List<String> stepNames(String node, String id) {
        return steps(node, id).getJsonArray("steps").stream().map(s -> ((JsonObject) s).getString("name")).toList();
    }

    private Set<String> liveNodes(String node) {
        return getJson(node, "/v1/nodes").getJsonArray("nodes").stream()
                .map(n -> ((JsonObject) n).getString("id")).collect(Collectors.toSet());
    }

    private static List<String> attemptOutcomes(JsonObject job) {
        List<String> outcomes = new ArrayList<>();
        job.getJsonArray("attempts").forEach(a -> outcomes.add(((JsonObject) a).getString("node") + ":" + ((JsonObject) a).getString("outcome")));
        return outcomes;
    }

    private JsonObject awaitTerminal(String node, String id) {
        JsonObject[] last = new JsonObject[1];
        awaitCondition(Duration.ofSeconds(60), "job " + id + " finished", () -> {
            last[0] = job(node, id);
            return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "ORPHANED").contains(last[0].getString("status"));
        });
        return last[0];
    }

    private void awaitCondition(Duration timeout, String what, ThrowingCondition condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.test()) {
                    return;
                }
            } catch (Exception e) {
                lastError = e;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what + "\n" + diagnostics(), lastError);
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean test() throws Exception;
    }
}
