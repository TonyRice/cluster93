package automately.runtime.js;

import automately.core.runtime.ScriptRuntime;

import automately.core.host.EgressPolicy;
import automately.core.host.FetchClient;
import automately.core.host.NodeHostApis;
import automately.core.host.ServeRegistry;
import automately.core.host.SharedDataKvStore;
import automately.core.host.dns.TestDnsServer;
import automately.core.job.OrphanPolicy;
import automately.core.runtime.Execution;
import automately.core.runtime.ExecutionResult;
import automately.core.runtime.JobContext;
import automately.core.runtime.StateSink;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code dns} in the isolate (D-122), in every build, against a local DNS server. RealNpmPackagesTest's dnsclient
 * fixture checks the same against real Node; NodeDnsApiTest checks what the filtering shows.
 */
class NodeDnsTest {

    private static Vertx vertx;
    private static ScriptRuntime runtime;
    private static TestDnsServer server;

    @BeforeAll
    static void setUp() throws Exception {
        vertx = Vertx.vertx();
        runtime = EngineUnderTest.create(vertx, 64, 2);
        server = new TestDnsServer(vertx).start()
                .a("example.test", "93.184.216.34").a("example.test", "10.0.0.5")
                .mx("example.test", 10, "mail.example.test")
                .srv("_mongodb._tcp.cluster.test", 0, 5, 27017, "mongo1.cluster.test");
    }

    @AfterAll
    static void tearDown() throws Exception {
        server.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void queriesAnswerAsNodesWithTheScriptsOwnServer() throws Exception {
        JsonArray out = run(true, """
                import dns from 'node:dns';
                import { resolveSrv } from 'node:dns/promises';
                export default async () => {
                  dns.setServers(['%s']);
                  const callback = await new Promise((r) => dns.resolve4('example.test', { ttl: true }, (err, a) => r(err ? err.code : a)));
                  return [
                    await dns.promises.resolve4('example.test'),
                    callback,
                    await dns.promises.resolveMx('example.test'),
                    await resolveSrv('_mongodb._tcp.cluster.test'),
                    await dns.promises.resolve4('nothing.test').catch((e) => [e.message, e.code, e.syscall, e.hostname]),
                    dns.getServers(),
                  ];
                };
                """.formatted(server.address()));

        // The development switch shows the private address too.
        assertThat(out.getJsonArray(0)).containsExactly("93.184.216.34", "10.0.0.5");
        assertThat(out.getJsonArray(1).getJsonObject(0).encode()).isEqualTo("{\"address\":\"93.184.216.34\",\"ttl\":300}");
        assertThat(out.getJsonArray(2).getJsonObject(0).encode()).isEqualTo("{\"exchange\":\"mail.example.test\",\"priority\":10,\"type\":\"MX\"}");
        assertThat(out.getJsonArray(3).getJsonObject(0).getString("name")).isEqualTo("mongo1.cluster.test");
        assertThat(out.getJsonArray(4)).containsExactly("queryA ENOTFOUND nothing.test", "ENOTFOUND", "queryA", "nothing.test");
        assertThat(out.getJsonArray(5)).containsExactly(server.address());
    }

    @Test
    void aServerOnAPrivateNetworkNeedsARule() throws Exception {
        JsonArray out = run(false, """
                import dns from 'node:dns';
                export default async () => {
                  const resolver = new dns.promises.Resolver();
                  resolver.setServers(['%s']);
                  return [await resolver.resolve4('example.test').catch((e) => [e.code, e.message])];
                };
                """.formatted(server.address()));

        assertThat(out.getJsonArray(0).getString(0)).isEqualTo("ECONNREFUSED");
        assertThat(out.getJsonArray(0).getString(1)).startsWith("queryA ECONNREFUSED example.test (dns to 127.0.0.1:")
                .contains("is not allowed");
    }

    private static JsonArray run(boolean allowPrivate, String script) throws Exception {
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(allowPrivate)),
                new ServeRegistry());
        Execution execution = new Execution("job-1", "tenant-a", script, new JsonObject(), Duration.ofSeconds(20),
                JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD,
                        NodeHostApis.factory(node).create("tenant-a", "job-1", false))
                .result().toCompletionStage().toCompletableFuture().get(40, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
        return (JsonArray) result.output();
    }
}
