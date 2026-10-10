// A stand-in for Node's `zlib` (D-103): deflate, zlib, gzip and raw deflate both ways, and Brotli decompression, on the
// JVM's zlib and a pure-Java Brotli decoder. Every call's output is capped (32 MB, or less with maxOutputLength), so a
// small input can't decompress into the node's memory. Brotli compression isn't available.
import { Buffer } from 'node:buffer';
import { Transform } from 'node:stream';

const internals = globalThis[Symbol.for('automately.zlib')];

export const constants = Object.freeze({
  Z_NO_FLUSH: 0, Z_PARTIAL_FLUSH: 1, Z_SYNC_FLUSH: 2, Z_FULL_FLUSH: 3, Z_FINISH: 4, Z_BLOCK: 5,
  Z_OK: 0, Z_STREAM_END: 1, Z_NEED_DICT: 2, Z_ERRNO: -1, Z_STREAM_ERROR: -2, Z_DATA_ERROR: -3, Z_MEM_ERROR: -4, Z_BUF_ERROR: -5,
  Z_VERSION_ERROR: -6, Z_NO_COMPRESSION: 0, Z_BEST_SPEED: 1, Z_BEST_COMPRESSION: 9, Z_DEFAULT_COMPRESSION: -1, Z_FILTERED: 1,
  Z_HUFFMAN_ONLY: 2, Z_RLE: 3, Z_FIXED: 4, Z_DEFAULT_STRATEGY: 0, ZLIB_VERNUM: 4865, DEFLATE: 1, INFLATE: 2, GZIP: 3, GUNZIP: 4,
  DEFLATERAW: 5, INFLATERAW: 6, UNZIP: 7, BROTLI_DECODE: 8, BROTLI_ENCODE: 9, Z_MIN_WINDOWBITS: 8, Z_MAX_WINDOWBITS: 15,
  Z_DEFAULT_WINDOWBITS: 15, Z_MIN_CHUNK: 64, Z_MAX_CHUNK: Infinity, Z_DEFAULT_CHUNK: 16384, Z_MIN_MEMLEVEL: 1, Z_MAX_MEMLEVEL: 9,
  Z_DEFAULT_MEMLEVEL: 8, Z_MIN_LEVEL: -1, Z_MAX_LEVEL: 9, Z_DEFAULT_LEVEL: -1, BROTLI_OPERATION_PROCESS: 0,
  BROTLI_OPERATION_FLUSH: 1, BROTLI_OPERATION_FINISH: 2, BROTLI_OPERATION_EMIT_METADATA: 3, BROTLI_PARAM_MODE: 0,
  BROTLI_MODE_GENERIC: 0, BROTLI_MODE_TEXT: 1, BROTLI_MODE_FONT: 2, BROTLI_PARAM_QUALITY: 1, BROTLI_MIN_QUALITY: 0,
  BROTLI_MAX_QUALITY: 11, BROTLI_DEFAULT_QUALITY: 11, BROTLI_PARAM_LGWIN: 2, BROTLI_PARAM_SIZE_HINT: 5,
});

const ERRNO = { Z_NEED_DICT: 2, Z_ERRNO: -1, Z_STREAM_ERROR: -2, Z_DATA_ERROR: -3, Z_MEM_ERROR: -4, Z_BUF_ERROR: -5 };

/** Runs a host call, giving zlib's errors their errno as Node does. */
function call(fn) {
  try {
    return fn();
  } catch (err) {
    if (err && ERRNO[err.code] !== undefined) err.errno = ERRNO[err.code];
    throw err;
  }
}

function bytesOf(data) {
  if (typeof data === 'string') return Buffer.from(data);
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  throw Object.assign(new TypeError('The "buffer" argument must be of type string or an instance of Buffer, TypedArray, DataView, or ArrayBuffer.'),
    { code: 'ERR_INVALID_ARG_TYPE' });
}

/** Node's options as the host reads them. */
function hostOptions(options) {
  const out = {};
  if (options?.level !== undefined) out.level = options.level;
  if (options?.strategy !== undefined) out.strategy = options.strategy;
  if (options?.windowBits !== undefined) out.windowBits = options.windowBits;
  if (options?.maxOutputLength !== undefined) out.maxOutputLength = options.maxOutputLength;
  if (options?.dictionary !== undefined) out.dictionary = Buffer.from(bytesOf(options.dictionary)).toString('base64');
  return out;
}

const toBuffer = (bytes) => Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);

function sync(kind) {
  return (buffer, options) => toBuffer(call(() => internals.process(kind, bytesOf(buffer), hostOptions(options))));
}

function callback(kind) {
  const run = sync(kind);
  return (buffer, options, cb) => {
    if (typeof options === 'function') {
      cb = options;
      options = undefined;
    }
    if (typeof cb !== 'function') throw Object.assign(new TypeError('The "callback" argument must be of type function'), { code: 'ERR_INVALID_ARG_TYPE' });
    Promise.resolve().then(() => {
      let result;
      try {
        result = run(buffer, options);
      } catch (err) {
        cb(err);
        return;
      }
      cb(null, result);
    });
  };
}

// One empty buffer per flush kind, told apart from data by identity, as Node's kFlushBuffers.
const FLUSH_MARKS = new Map();
const MARK_BY_KIND = new Map();
function flushMark(kind) {
  let mark = MARK_BY_KIND.get(kind);
  if (!mark) {
    mark = Buffer.alloc(0);
    MARK_BY_KIND.set(kind, mark);
    FLUSH_MARKS.set(mark, kind);
  }
  return mark;
}

/** A zlib stream: a Transform whose state is a host stream, dropped when it ends or is destroyed. */
class ZlibBase extends Transform {
  #handle;
  #ended = false;

  constructor(kind, options) {
    super(options);
    this.#handle = call(() => internals.open(kind, hostOptions(options)));
    this.bytesWritten = 0;
  }

  _transform(chunk, encoding, done) {
    let out;
    const flushKind = FLUSH_MARKS.get(chunk);
    if (flushKind !== undefined) {
      try {
        out = call(() => internals.flush(this.#handle, flushKind));
      } catch (err) {
        this.#ended = true;
        done(err);
        return;
      }
      if (out.length) this.push(toBuffer(out));
      done();
      return;
    }
    try {
      const bytes = typeof chunk === 'string' ? Buffer.from(chunk, encoding) : bytesOf(chunk);
      this.bytesWritten += bytes.length;
      out = call(() => internals.write(this.#handle, bytes));
    } catch (err) {
      this.#ended = true;
      done(err);
      return;
    }
    if (out.length) this.push(toBuffer(out));
    done();
  }

  _flush(done) {
    let out;
    try {
      this.#ended = true;
      out = call(() => internals.finish(this.#handle));
    } catch (err) {
      done(err);
      return;
    }
    if (out.length) this.push(toBuffer(out));
    done();
  }

  _destroy(err, done) {
    if (!this.#ended) {
      this.#ended = true;
      try {
        internals.close(this.#handle);
      } catch {
        // already gone
      }
    }
    done(err);
  }

  /**
   * As Node's (D-125): a marker written after what's pending, so the flush comes in order; a compressor gives out what it
   * holds (a sync flush ends it on a byte boundary, as permessage-deflate needs), then the callback runs.
   */
  flush(kind, done) {
    if (typeof kind === 'function' || (kind === undefined && !done)) { done = kind; kind = constants.Z_FULL_FLUSH; }
    if (this.writableFinished) { if (done) Promise.resolve().then(done); }
    else if (this.writableEnded) { if (done) this.once('end', done); }
    else this.write(flushMark(kind), '', done);
  }

  params(level, strategy, done) {
    throw Object.assign(new Error('zlib params() is not available in the isolate'), { code: 'ERR_FEATURE_UNAVAILABLE_ON_PLATFORM' });
  }

  close(done) {
    this.destroy();
    if (typeof done === 'function') this.once('close', done);
  }
}

const streamClass = (kind) => class extends ZlibBase {
  constructor(options) {
    super(kind, options);
  }
};

export const Deflate = streamClass('deflate');
export const Inflate = streamClass('inflate');
export const Gzip = streamClass('gzip');
export const Gunzip = streamClass('gunzip');
export const DeflateRaw = streamClass('deflateRaw');
export const InflateRaw = streamClass('inflateRaw');
export const Unzip = streamClass('unzip');
export const BrotliDecompress = streamClass('brotliDecompress');
export class BrotliCompress extends ZlibBase {
  constructor(options) {
    super('brotliCompress', options);
  }
}

export const createDeflate = (options) => new Deflate(options);
export const createInflate = (options) => new Inflate(options);
export const createGzip = (options) => new Gzip(options);
export const createGunzip = (options) => new Gunzip(options);
export const createDeflateRaw = (options) => new DeflateRaw(options);
export const createInflateRaw = (options) => new InflateRaw(options);
export const createUnzip = (options) => new Unzip(options);
export const createBrotliDecompress = (options) => new BrotliDecompress(options);
export const createBrotliCompress = (options) => new BrotliCompress(options);

export const deflateSync = sync('deflate');
export const inflateSync = sync('inflate');
export const gzipSync = sync('gzip');
export const gunzipSync = sync('gunzip');
export const deflateRawSync = sync('deflateRaw');
export const inflateRawSync = sync('inflateRaw');
export const unzipSync = sync('unzip');
export const brotliDecompressSync = sync('brotliDecompress');
export const brotliCompressSync = sync('brotliCompress');

export const deflate = callback('deflate');
export const inflate = callback('inflate');
export const gzip = callback('gzip');
export const gunzip = callback('gunzip');
export const deflateRaw = callback('deflateRaw');
export const inflateRaw = callback('inflateRaw');
export const unzip = callback('unzip');
export const brotliDecompress = callback('brotliDecompress');
export const brotliCompress = callback('brotliCompress');

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

/** CRC-32 of the data, continuing from {@code value} (Node 22). */
export function crc32(data, value = 0) {
  const bytes = bytesOf(data);
  let crc = (value ^ -1) >>> 0;
  for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ -1) >>> 0;
}

export const codes = Object.freeze({
  Z_OK: 0, Z_STREAM_END: 1, Z_NEED_DICT: 2, Z_ERRNO: -1, Z_STREAM_ERROR: -2, Z_DATA_ERROR: -3, Z_MEM_ERROR: -4, Z_BUF_ERROR: -5,
  Z_VERSION_ERROR: -6, '0': 'Z_OK', '1': 'Z_STREAM_END', '2': 'Z_NEED_DICT', '-1': 'Z_ERRNO', '-2': 'Z_STREAM_ERROR',
  '-3': 'Z_DATA_ERROR', '-4': 'Z_MEM_ERROR', '-5': 'Z_BUF_ERROR', '-6': 'Z_VERSION_ERROR',
});

export default {
  constants, codes, crc32, Deflate, Inflate, Gzip, Gunzip, DeflateRaw, InflateRaw, Unzip, BrotliDecompress, BrotliCompress,
  createDeflate, createInflate, createGzip, createGunzip, createDeflateRaw, createInflateRaw, createUnzip, createBrotliDecompress,
  createBrotliCompress, deflateSync, inflateSync, gzipSync, gunzipSync, deflateRawSync, inflateRawSync, unzipSync,
  brotliDecompressSync, brotliCompressSync, deflate, inflate, gzip, gunzip, deflateRaw, inflateRaw, unzip, brotliDecompress,
  brotliCompress, ...constants,
};
