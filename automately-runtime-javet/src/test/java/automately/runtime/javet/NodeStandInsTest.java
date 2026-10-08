package automately.runtime.javet;

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
 * The stand-ins for Node's path, events, buffer, url, util and process (D-090, D-093), run in the isolate, and ICU. Their results were checked
 * against Node's own modules when they were written; these tests make sure they load and behave in V8 without Node.
 */
class NodeStandInsTest {

    private static Vertx vertx;
    private static JavetScriptRuntime runtime;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        runtime = new JavetScriptRuntime(vertx, 64, 2);
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void path() throws Exception {
        assertThat(output("import path from 'path'; import { join } from 'node:path'; import posix from 'path/posix';"
                + "export default () => [path.join('/a', 'b', '../c'), join('x', './y/'), path.resolve('a', '../b'), path.relative('/a/b', '/a/c/d'),"
                + " path.extname('f.tar.gz'), path.basename('/x/y.js', '.js'), path.dirname('/x/y/z'), path.parse('/home/u/f.txt').name,"
                + " posix === path];"))
                .containsExactly("/a/c", "x/y/", "/b", "../c/d", ".gz", "y", "/x/y", "f", true);
    }

    @Test
    void events() throws Exception {
        assertThat(output("import EventEmitter, { once } from 'events'; import { EventEmitter as Same } from 'node:events'; import util from 'util';"
                + "function Old() { EventEmitter.call(this); } util.inherits(Old, EventEmitter);"
                + "class New extends EventEmitter {}"
                + "export default async () => { const seen = [];"
                + " const old = new Old(); old.on('x', v => seen.push('old ' + v)); old.emit('x', 1);"
                + " const e = new New(); e.once('y', v => seen.push('once ' + v)); e.emit('y', 2); e.emit('y', 3);"
                + " const f = () => seen.push('removed'); e.on('z', f); e.off('z', f); e.emit('z');"
                + " setTimeout(() => e.emit('later', 'a', 'b'), 1); seen.push((await once(e, 'later')).join(''));"
                + " try { e.emit('error', new Error('unhandled')); } catch (err) { seen.push(err.message); }"
                + " seen.push(Same === EventEmitter, old instanceof EventEmitter, e.listenerCount('y'));"
                + " return seen; };"))
                .containsExactly("old 1", "once 2", "ab", "unhandled", true, true, 0);
    }

    @Test
    void buffer() throws Exception {
        assertThat(output("import { Buffer } from 'buffer'; import buf from 'node:buffer';"
                + "export default () => { const b = Buffer.from('héllo'); const n = Buffer.alloc(4); n.writeUInt32BE(0xdeadbeef);"
                + " return [b.toString('hex'), b.toString('base64'), Buffer.from('aGk=', 'base64').toString(), n.readUInt32LE(0),"
                + " Buffer.concat([Buffer.from('a'), Buffer.from('b')]).toString(), Buffer.isBuffer(b.slice(1)), buf.Buffer === Buffer,"
                + " b.length, Buffer.byteLength('héllo')]; };"))
                .containsExactly("68c3a96c6c6f", "aMOpbGxv", "hi", 4022250974L, "ab", true, true, 6, 6);
    }

    @Test
    void url() throws Exception {
        assertThat(output("import { URL, URLSearchParams, fileURLToPath, pathToFileURL } from 'url';"
                + "export default () => { const u = new URL('../x?a=1#h', 'https://Example.com:443/p/q/r');"
                + " u.searchParams.append('b', 'two words');"
                + " return [u.href, u.host, u.origin, new URLSearchParams('a=1&a=2').getAll('a').join(','),"
                + " fileURLToPath('file:///tmp/a%20b'), pathToFileURL('/tmp/a b').href]; };"))
                .containsExactly("https://example.com/p/x?a=1&b=two+words#h", "example.com", "https://example.com", "1,2",
                        "/tmp/a b", "file:///tmp/a%20b");
    }

    @Test
    void util() throws Exception {
        assertThat(output("import util, { format, promisify, isDeepStrictEqual } from 'node:util';"
                + "const later = (x, cb) => setTimeout(() => cb(null, x * 2), 1);"
                + "export default async () => [format('%s=%d %j', 'n', 4, { a: 1 }), util.inspect({ a: [1, { b: 'c' }] }),"
                + " await promisify(later)(21), isDeepStrictEqual(new Map([[1, { a: 2 }]]), new Map([[1, { a: 2 }]])), util.types.isDate(new Date())];"))
                .containsExactly("n=4 {\"a\":1}", "{ a: [ 1, { b: 'c' } ] }", 42, true, true);
    }

    @Test
    void processIsAGlobalWithExactlyTheDeclaredEnv() throws Exception {
        Execution execution = new Execution("job-1", "tenant-a",
                "import imported, { env } from 'node:process';"
                        + "export default () => [process.env.API_URL, process.env.NODE_ENV, Object.keys(process.env).sort().join(','),"
                        + " imported === process, env === process.env, process.versions.node === undefined, typeof process.nextTick,"
                        + " 'PATH' in process.env, 'HOME' in process.env];",
                new JsonObject(), Duration.ofSeconds(5), JobContext.firstAttempt("job-1", OrphanPolicy.FAIL), false,
                automately.core.runtime.Packages.NONE, java.util.Map.of("API_URL", "https://api.example.com", "NODE_ENV", "production",
                        "QUOTE", "it's \"quoted\" </script>\u2028"));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD)
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
        assertThat((JsonArray) result.output()).containsExactly("https://api.example.com", "production", "API_URL,NODE_ENV,QUOTE",
                true, true, true, "function", false, false);

        assertThat(output("export default () => [Object.keys(process.env), typeof globalThis.process];"))
                .as("nothing declared: only NODE_ENV").containsExactly(new JsonArray().add("NODE_ENV"), "object");
        assertThat(output("export default () => { globalThis.process = { shim: true }; return [process.shim]; };"))
                .as("writable, as browser shims expect").containsExactly(true);
    }

    @Test
    void webCryptoIsAGlobal() throws Exception {
        assertThat(output("export default async () => { const a = crypto.getRandomValues(new Uint32Array(4));"
                + " const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode('abc')));"
                + " let quota; try { crypto.getRandomValues(new Uint8Array(65537)); } catch (e) { quota = e.name; }"
                + " let mismatch; try { crypto.getRandomValues(new Float32Array(2)); } catch (e) { mismatch = e.name; }"
                + " return [a.length, a.some(n => n !== 0), /^[0-9a-f-]{36}$/.test(crypto.randomUUID()),"
                + " Array.from(digest.slice(0, 4)).map(b => b.toString(16).padStart(2, '0')).join(''), quota, mismatch,"
                + " Object.isFrozen(crypto)]; };"))
                .containsExactly(4, true, true, "ba7816bf", "QuotaExceededError", "TypeMismatchError", true);
    }

    @Test
    void nodeCryptoHashesHmacsAndRandomness() throws Exception {
        assertThat(output("import crypto, { createHash, createHmac, hash, randomBytes, randomInt, timingSafeEqual, createSecretKey } from 'node:crypto';"
                + " import { Buffer } from 'node:buffer';"
                + " export default () => {"
                + "   const h = createHash('sha256').update('ab'); const copy = h.copy(); h.update('c');"
                + "   let finished; try { h.digest(); h.digest(); } catch (e) { finished = e.code; }"
                + "   const ints = Array.from({ length: 200 }, () => randomInt(3, 6));"
                + "   return [createHash('sha256').update('abc').digest('hex').slice(0, 8), copy.digest('base64'),"
                + "     createHmac('sha256', 'Jefe').update('what do ya want for nothing?').digest('hex').slice(0, 8),"
                + "     createHmac('sha256', createSecretKey(Buffer.from('Jefe'))).update('what do ya want for nothing?').digest('hex').slice(0, 8),"
                + "     hash('md5', 'abc'), randomBytes(100000).length, Math.min(...ints), Math.max(...ints),"
                + "     timingSafeEqual(Buffer.from('ab'), Buffer.from('ab')), finished, crypto.webcrypto === globalThis.crypto,"
                + "     crypto.getHashes().includes('sha3-256')];"
                + " };"))
                .containsExactly("ba7816bf", "+44g/C5MPySMYMOb1lLzwTRymLuXe4tNWQO4UFViBgM=", "5bdcc146", "5bdcc146",
                        "900150983cd24fb0d6963f7d28e17f72", 100000, 3, 5, true, "ERR_CRYPTO_HASH_FINALIZED", true, true);
    }

    @Test
    void streamsPipeTransformAndDecode() throws Exception {
        assertThat(output("import { Readable, Transform, Writable, PassThrough } from 'node:stream';"
                + " import { pipeline } from 'stream/promises'; import { StringDecoder } from 'string_decoder'; import { Buffer } from 'buffer';"
                + " export default async () => {"
                + "   const seen = [];"
                + "   const upper = new Transform({ transform(chunk, enc, done) { done(null, String(chunk).toUpperCase()); } });"
                + "   const sink = new Writable({ write(chunk, enc, done) { seen.push(String(chunk)); done(); } });"
                + "   await pipeline(Readable.from(['a', 'b', 'c']), upper, sink);"
                + "   let collected = ''; for await (const c of Readable.from(['x', 'y']).pipe(new PassThrough())) collected += c;"
                + "   const failing = new Readable({ read() { this.destroy(new Error('boom')); } });"
                + "   let error; try { await pipeline(failing, new PassThrough()); } catch (e) { error = e.message; }"
                + "   const euro = Buffer.from('€'); const d = new StringDecoder('utf8');"
                + "   const decoded = d.write(euro.subarray(0, 1)) + '|' + d.write(euro.subarray(1)) + d.end();"
                + "   return [seen.join(''), collected, error, decoded];"
                + " };"))
                .containsExactly("ABC", "xy", "boom", "|€");
    }

    @Test
    void whatCryptoCantDoYetSaysSo() throws Exception {
        assertThat(output("import { createCipheriv } from 'crypto'; import { Buffer } from 'buffer';"
                + " export default () => { try { createCipheriv('aes-256-gcm', Buffer.alloc(32), Buffer.alloc(12)); } catch (e) { return [e.code]; } };"))
                .containsExactly("ERR_FEATURE_UNAVAILABLE_ON_PLATFORM");
    }

    @Test
    void unicodeRegularExpressionsAndIntlWork() throws Exception {
        // V8's i18n build with ICU's data (D-093): without it, \\p{…} doesn't compile and Intl is missing.
        assertThat(output("export default () => [/^\\p{Lu}\\p{Ll}+$/u.test('Élan'), 'ﬁ'.normalize('NFKC'),"
                + " new Intl.NumberFormat('de-DE').format(1234567.891), new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'long' })"
                + ".format(new Date(Date.UTC(2026, 9, 8))), ['b', 'ä', 'a'].sort(new Intl.Collator('de').compare).join(''),"
                + " (1234.5).toLocaleString('en-US', { style: 'currency', currency: 'USD' })];"))
                .containsExactly(true, "fi", "1.234.567,891", "October", "aäb", "$1,234.50");
    }

    @Test
    void aBundledPackageUsingThemLoads() throws Exception {
        // What esbuild leaves behind for npm code using Node's modules: plain imports, now answered by the stand-ins.
        assertThat(output("import { EventEmitter } from 'node:events'; import { Buffer } from 'node:buffer'; import { join } from 'node:path';"
                + "class Bus extends EventEmitter {} export default () => { const b = new Bus(); let got;"
                + " b.on('m', (x) => { got = Buffer.from(join('a', x)).toString('base64'); }); b.emit('m', 'b'); return [got]; };"))
                .containsExactly("YS9i");
    }

    private static JsonArray output(String script) throws Exception {
        Execution execution = new Execution("job-1", "tenant-a", script, new JsonObject(), Duration.ofSeconds(5),
                JobContext.firstAttempt("job-1", OrphanPolicy.FAIL));
        ExecutionResult result = runtime.execute(execution, (level, line) -> { }, StateSink.DISCARD)
                .result().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertThat(result.error()).isNull();
        return (JsonArray) result.output();
    }
}
