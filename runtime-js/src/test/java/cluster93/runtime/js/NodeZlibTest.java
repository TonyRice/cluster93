package cluster93.runtime.js;

import cluster93.core.runtime.ScriptRuntime;

import cluster93.core.host.EgressPolicy;
import cluster93.core.host.FetchClient;
import cluster93.core.host.NodeHostApis;
import cluster93.core.host.ServeRegistry;
import cluster93.core.host.SharedDataKvStore;
import cluster93.core.job.OrphanPolicy;
import cluster93.core.runtime.Execution;
import cluster93.core.runtime.ExecutionResult;
import cluster93.core.runtime.JobContext;
import cluster93.core.runtime.StateSink;
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
 * {@code zlib} streams' flush and window size (D-125), as permessage-deflate uses them: a sync flush gives out what was
 * written, ending on the empty stored block, and a smaller window still reads back. RealNpmPackagesTest's fixtures check
 * the rest of zlib against real Node.
 */
class NodeZlibTest {

    private static Vertx vertx;
    private static ScriptRuntime runtime;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        runtime = EngineUnderTest.create(vertx, 64, 2);
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aSyncFlushGivesOutWhatWasWrittenAndItInflatesBack() throws Exception {
        JsonArray out = run("""
                import zlib from 'node:zlib';
                import { Buffer } from 'node:buffer';
                const flushed = (stream, data) => new Promise((resolve) => {
                  const chunks = [];
                  const onData = (c) => chunks.push(c);
                  stream.on('data', onData);
                  stream.write(data);
                  stream.flush(zlib.Z_SYNC_FLUSH, (err) => { stream.off('data', onData); resolve([err ?? null, Buffer.concat(chunks)]); });
                });
                export default async () => {
                  const deflate = zlib.createDeflateRaw({ windowBits: 15 });
                  const [err, first] = await flushed(deflate, Buffer.from('hello hello hello'));
                  const [, second] = await flushed(deflate, Buffer.from('again'));
                  const inflate = zlib.createInflateRaw();
                  const [, back] = await flushed(inflate, Buffer.concat([first, second]));
                  const small = zlib.createDeflateRaw({ windowBits: 9 });
                  const [, stored] = await flushed(small, Buffer.from('small window small window'));
                  const [, smallBack] = await flushed(zlib.createInflateRaw({ windowBits: 9 }), stored);
                  const header = zlib.deflateSync(Buffer.from('x'), { windowBits: 10 }).subarray(0, 2).toString('hex');
                  return [err, first.subarray(-4).toString('hex'), second.length > 0, back.toString(), smallBack.toString(), header,
                    zlib.inflateSync(zlib.deflateSync(Buffer.from('round'), { windowBits: 10 })).toString()];
                };
                """);

        assertThat(out.getValue(0)).isNull();
        assertThat(out.getString(1)).isEqualTo("0000ffff");
        assertThat(out.getBoolean(2)).isTrue();
        assertThat(out.getString(3)).isEqualTo("hello hello helloagain");
        assertThat(out.getString(4)).isEqualTo("small window small window");
        // As zlib writes a 10-bit window's header at the default level: CMF 0x28, FLG 0x91.
        assertThat(out.getString(5)).isEqualTo("2891");
        assertThat(out.getString(6)).isEqualTo("round");
    }

    private static JsonArray run(String script) throws Exception {
        NodeHostApis.Node node = new NodeHostApis.Node(vertx, new SharedDataKvStore(vertx), new FetchClient(vertx, new EgressPolicy(false)),
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
