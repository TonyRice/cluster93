// zlib, assert, querystring and timers/promises (D-103), compared with real Node. Compressed bytes aren't compared:
// Node's zlib is Chromium's, which may compress differently (both are valid); round trips and Node-made data are.
import zlib from 'node:zlib';
import { promisify } from 'node:util';
import { pipeline } from 'node:stream/promises';
import { Readable, Writable } from 'node:stream';
import assert from 'node:assert';
import strict from 'node:assert/strict';
import querystring from 'node:querystring';
import { setTimeout as sleep, setInterval as every } from 'node:timers/promises';
import { Buffer } from 'node:buffer';

const MADE_BY_NODE = {"br":"GxMFmJwHdmwCrV9o2VNGqHQ9WapfV5JUXfhmmOnUV1te7RR2Nqz0hmEDDtgjDIjTQfUKVC2BnGq3/OZLsIloewcqw6yc/m6MMUxRbwQ=","gz":"H4sIAAAAAAACA3Mqyi/JyVRIzEtRSK/KLFDITUxJVUiqVChKTcxR8MsHch41TFEozyzJUDi8J+/w+uTD21IOr9RRKEotSE0sSU1RcBo1YNSAUQNGDRg+BgAA5zS0FBQFAAA=","df":"eJxzKsovyclUSMxLUUivyixQyE1MSVVIqlQoSk3MUfDLB3IeNUxRKM8syVA4vCfv8Prkw9tSDq/UUShKLUhNLElNUXAaNWDUgFEDRg0YPgYAADjbEr8=","dfd":"eLs1KQZrywDzM9BEAWDKCJ8=","len":1180};

const outcome = (fn) => {
  try {
    fn();
    return 'pass';
  } catch (e) {
    return [e.name, e.code, e.operator ?? null];
  }
};
const errorOf = (fn) => {
  try {
    fn();
    return null;
  } catch (e) {
    return [e.code, e.errno ?? null, e.message];
  }
};

async function zlibCases() {
  const text = 'Hello, ünïcödé! '.repeat(500);
  const out = {};
  for (const [enc, dec] of [['gzipSync', 'gunzipSync'], ['deflateSync', 'inflateSync'], ['deflateRawSync', 'inflateRawSync'], ['gzipSync', 'unzipSync'], ['deflateSync', 'unzipSync']]) {
    const packed = zlib[enc](text, { level: 9 });
    out[enc + '>' + dec] = [zlib[dec](packed).toString() === text, packed.length < text.length];
  }
  out.gzipHeader = zlib.gzipSync('x').subarray(0, 10).toString('hex');
  out.nodeGzip = zlib.gunzipSync(Buffer.from(MADE_BY_NODE.gz, 'base64')).toString().length === MADE_BY_NODE.len;
  out.nodeDeflate = zlib.inflateSync(Buffer.from(MADE_BY_NODE.df, 'base64')).toString().slice(0, 30);
  out.nodeBrotli = zlib.brotliDecompressSync(Buffer.from(MADE_BY_NODE.br, 'base64')).toString().slice(0, 40);
  out.dictionary = zlib.inflateSync(Buffer.from(MADE_BY_NODE.dfd, 'base64'), { dictionary: Buffer.from('hello dictionary') }).toString();
  out.noDictionary = errorOf(() => zlib.inflateSync(Buffer.from(MADE_BY_NODE.dfd, 'base64')));
  out.notGzip = errorOf(() => zlib.gunzipSync(Buffer.from('not gzip at all')));
  out.truncated = errorOf(() => zlib.gunzipSync(zlib.gzipSync(text).subarray(0, 40)));
  const corrupt = zlib.gzipSync(text);
  corrupt[corrupt.length - 6] ^= 0xff;
  out.badCrc = errorOf(() => zlib.gunzipSync(corrupt));
  out.capped = errorOf(() => zlib.gunzipSync(zlib.gzipSync(Buffer.alloc(100000)), { maxOutputLength: 1000 }))?.[0];
  out.concatenated = zlib.gunzipSync(Buffer.concat([zlib.gzipSync('one '), zlib.gzipSync('two')])).toString();
  out.callback = (await promisify(zlib.gunzip)(await promisify(zlib.gzip)('via callbacks'))).toString();
  out.crc32 = [zlib.crc32('hello'), zlib.crc32('world', zlib.crc32('hello ')), zlib.crc32('hello world')];

  // Streams, fed one byte at a time through the gzip header and trailer.
  const chunks = [];
  const packed = zlib.gzipSync(text);
  await pipeline(Readable.from([...packed].map((b) => Buffer.from([b]))), zlib.createGunzip(),
    new Writable({ write(chunk, enc, done) { chunks.push(chunk); done(); } }));
  out.streamedOneByteAtATime = Buffer.concat(chunks).toString() === text;
  const round = [];
  await pipeline(Readable.from([text.slice(0, 1000), text.slice(1000)]), zlib.createDeflate(), zlib.createInflate(),
    new Writable({ write(chunk, enc, done) { round.push(chunk); done(); } }));
  out.streamedRoundTrip = Buffer.concat(round).toString() === text;
  out.constants = [zlib.constants.Z_BEST_COMPRESSION, zlib.Z_DEFAULT_COMPRESSION, zlib.codes.Z_DATA_ERROR];
  return out;
}

function assertCases() {
  const circular = { a: 1 };
  circular.self = circular;
  const circular2 = { a: 1 };
  circular2.self = circular2;
  return {
    ok: [outcome(() => assert(1)), outcome(() => assert.ok(0)), outcome(() => assert(false, 'custom'))],
    equal: [outcome(() => assert.equal(1, '1')), outcome(() => assert.equal(NaN, NaN)), outcome(() => assert.notEqual(1, 2)),
      outcome(() => strict.equal(1, '1'))],
    strictMessage: errorOf(() => assert.strictEqual(1, 2)),
    deep: [
      outcome(() => assert.deepStrictEqual({ a: [1, { b: 2 }] }, { a: [1, { b: 2 }] })),
      outcome(() => assert.deepStrictEqual({ a: 1 }, { a: '1' })),
      outcome(() => assert.deepEqual({ a: 1 }, { a: '1' })),
      outcome(() => assert.deepStrictEqual(new Map([[1, { x: 1 }]]), new Map([[1, { x: 1 }]]))),
      outcome(() => assert.deepStrictEqual(new Set([{ a: 1 }, 2]), new Set([2, { a: 1 }]))),
      outcome(() => assert.deepStrictEqual(new Set([1]), new Set(['1']))),
      outcome(() => assert.deepEqual(new Set([1]), new Set(['1']))),
      outcome(() => assert.deepStrictEqual(new Date(5), new Date(5))),
      outcome(() => assert.deepStrictEqual(new Date(5), new Date(6))),
      outcome(() => assert.deepStrictEqual(/a/g, /a/g)),
      outcome(() => assert.deepStrictEqual(/a/g, /a/i)),
      outcome(() => assert.deepStrictEqual(Buffer.from('ab'), Buffer.from('ab'))),
      outcome(() => assert.deepStrictEqual(new Uint8Array([1]), Buffer.from([1]))),
      outcome(() => assert.deepStrictEqual(0, -0)),
      outcome(() => assert.deepEqual(0, -0)),
      outcome(() => assert.deepStrictEqual(NaN, NaN)),
      outcome(() => assert.deepStrictEqual(circular, circular2)),
      outcome(() => assert.deepStrictEqual(Object.create(null), {})),
      outcome(() => assert.deepEqual(Object.create(null), {})),
      outcome(() => assert.deepStrictEqual([1, , 3], [1, undefined, 3])), // eslint-disable-line no-sparse-arrays
      outcome(() => assert.deepStrictEqual({ [Symbol.for('s')]: 1 }, { [Symbol.for('s')]: 1 })),
      outcome(() => assert.deepStrictEqual({ [Symbol.for('s')]: 1 }, {})),
      outcome(() => assert.notDeepStrictEqual({ a: 1 }, { a: 2 })),
      outcome(() => assert.deepStrictEqual(new Error('x'), new Error('x'))),
      outcome(() => assert.deepStrictEqual(new Error('x'), new TypeError('x'))),
    ],
    throws: [
      outcome(() => assert.throws(() => { throw new TypeError('bad thing'); }, TypeError)),
      outcome(() => assert.throws(() => { throw new TypeError('bad thing'); }, /bad/)),
      outcome(() => assert.throws(() => { throw new TypeError('bad thing'); }, { name: 'TypeError', message: /thing/ })),
      outcome(() => assert.throws(() => { throw new TypeError('bad thing'); }, { message: 'other' })),
      outcome(() => assert.throws(() => {})),
      outcome(() => assert.throws(() => { throw new Error('e'); }, (e) => e.message === 'e')),
      outcome(() => assert.doesNotThrow(() => {})),
      outcome(() => assert.doesNotThrow(() => { throw new Error('boom'); })),
    ],
    match: [outcome(() => assert.match('abc', /b/)), outcome(() => assert.match('abc', /z/)), outcome(() => assert.doesNotMatch('abc', /z/))],
    misc: [outcome(() => assert.ifError(null)), outcome(() => assert.ifError(new Error('e'))), outcome(() => assert.fail('nope')),
      new assert.AssertionError({ actual: 1, expected: 2, operator: 'strictEqual' }).code, assert.strict === strict],
  };
}

async function asyncCases() {
  const rejects = await assert.rejects(Promise.reject(new Error('r')), /r/).then(() => 'pass', (e) => e.code);
  const missing = await assert.rejects(Promise.resolve(1)).then(() => 'pass', (e) => e.code);
  const notRejected = await assert.doesNotReject(async () => 1).then(() => 'pass', (e) => e.code);
  const value = await sleep(5, 'slept');
  const ticks = [];
  for await (const v of every(2, 'tick')) {
    ticks.push(v);
    if (ticks.length === 3) break;
  }
  // A signal of our own: the AbortController global waits for D-095's last step, and both accept any signal-shaped object.
  const listeners = [];
  const signal = { aborted: false, reason: undefined, addEventListener: (type, fn) => listeners.push(fn), removeEventListener() {} };
  const aborted = sleep(1000, 'never', { signal }).catch((e) => [e.name, e.code]);
  signal.aborted = true;
  signal.reason = new Error('stop');
  listeners.forEach((fn) => fn());
  return { rejects, missing, notRejected, value, ticks, aborted: await aborted };
}

function querystringCases() {
  return {
    parse: [
      querystring.parse('a=1&b=2&a=3&c&d=&e=x%20y+z&%E2%82%AC=%E2%82%AC'),
      querystring.parse('a:1;b:2', ';', ':'),
      Object.keys(querystring.parse('a=1&b=2&c=3', null, null, { maxKeys: 2 })),
      querystring.parse('bad=%E0%A4%A&ok=1'),
    ],
    stringify: [
      querystring.stringify({ a: [1, 2], b: 'x y', c: true, d: null, e: NaN, f: { g: 1 }, '€': '€' }),
      querystring.stringify({ a: 1, b: 2 }, ';', ':'),
      querystring.escape('a b&c=d/€'),
      querystring.unescape('a%20b%2'),
    ],
  };
}

export const run = async () => ({ zlib: await zlibCases(), assert: assertCases(), async: await asyncCases(), querystring: querystringCases() });
