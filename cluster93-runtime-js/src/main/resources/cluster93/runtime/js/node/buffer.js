// A stand-in for Node's `buffer` (D-089): Buffer as a Uint8Array with Node's common methods and encodings (utf8, hex,
// base64, base64url, latin1/binary, ascii, utf16le/ucs2). `slice` shares memory, as in Node.

const ENCODINGS = ['utf8', 'utf-8', 'hex', 'base64', 'base64url', 'latin1', 'binary', 'ascii', 'utf16le', 'utf-16le', 'ucs2', 'ucs-2'];
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const B64_INDEX = new Int16Array(128).fill(-1);
for (let i = 0; i < 64; i++) {
  B64_INDEX[B64.charCodeAt(i)] = i;
}
B64_INDEX['-'.charCodeAt(0)] = 62;
B64_INDEX['_'.charCodeAt(0)] = 63;
// Hex and base64 stay in JavaScript on every engine (D-126): measured against a host codec, decoding in Java lost on
// both engines (a large string costs more to hand over than to decode), and encoding won on V8 but lost twice over in
// GraalJS's isolate. Output is built in typed arrays, then turned into a string a chunk at a time.
const HEX_PAIRS = Array.from({ length: 256 }, (_, i) => (i < 16 ? '0' : '') + i.toString(16));
const B64_CODES = new Uint8Array(64);
const B64_URL_CODES = new Uint8Array(64);
for (let i = 0; i < 64; i++) {
  B64_CODES[i] = B64.charCodeAt(i);
  B64_URL_CODES[i] = i === 62 ? 45 : i === 63 ? 95 : B64.charCodeAt(i);
}
// A hex digit's value, or -1: only 0-9, a-f and A-F, as Node.
const hexValue = (c) => (c >= 48 && c <= 57 ? c - 48 : c >= 97 && c <= 102 ? c - 87 : c >= 65 && c <= 70 ? c - 55 : -1);

function latin1String(codes) {
  let out = '';
  for (let i = 0; i < codes.length; i += 8192) {
    out += String.fromCharCode.apply(null, codes.subarray(i, i + 8192));
  }
  return out;
}

const utf8Encoder = new TextEncoder();
const utf8Decoder = new TextDecoder();

function normalize(encoding) {
  const e = String(encoding === undefined || encoding === null ? 'utf8' : encoding).toLowerCase();
  switch (e) {
    case 'utf8': case 'utf-8': return 'utf8';
    case 'hex': return 'hex';
    case 'base64': return 'base64';
    case 'base64url': return 'base64url';
    case 'latin1': case 'binary': return 'latin1';
    case 'ascii': return 'ascii';
    case 'utf16le': case 'utf-16le': case 'ucs2': case 'ucs-2': return 'utf16le';
    default: throw new TypeError('Unknown encoding: ' + encoding);
  }
}

function encode(string, encoding) {
  switch (normalize(encoding)) {
    case 'utf8':
      return utf8Encoder.encode(string);
    case 'hex': {
      // Node stops at the first pair that isn't two hex digits.
      const length = string.length >>> 1;
      const out = new Uint8Array(length);
      let i = 0;
      for (; i < length; i++) {
        const high = hexValue(string.charCodeAt(i * 2));
        const low = hexValue(string.charCodeAt(i * 2 + 1));
        if (high < 0 || low < 0) {
          break;
        }
        out[i] = (high << 4) | low;
      }
      return out.subarray(0, i);
    }
    case 'base64':
    case 'base64url': {
      const out = new Uint8Array(Math.ceil(string.length * 3 / 4));
      let bits = 0;
      let value = 0;
      let n = 0;
      for (let i = 0; i < string.length; i++) {
        const c = string.charCodeAt(i);
        const v = c < 128 ? B64_INDEX[c] : -1;
        if (v < 0) {
          if (string[i] === '=') {
            break;
          }
          continue;
        }
        value = (value << 6) | v;
        bits += 6;
        if (bits >= 8) {
          bits -= 8;
          out[n++] = (value >> bits) & 0xff;
        }
      }
      return out.subarray(0, n);
    }
    case 'latin1':
    case 'ascii': {
      const out = new Uint8Array(string.length);
      for (let i = 0; i < string.length; i++) {
        out[i] = string.charCodeAt(i) & 0xff;
      }
      return out;
    }
    case 'utf16le': {
      const out = new Uint8Array(string.length * 2);
      for (let i = 0; i < string.length; i++) {
        const c = string.charCodeAt(i);
        out[i * 2] = c & 0xff;
        out[i * 2 + 1] = c >> 8;
      }
      return out;
    }
  }
}

function decode(bytes, encoding) {
  switch (normalize(encoding)) {
    case 'utf8':
      return utf8Decoder.decode(bytes);
    case 'hex': {
      let out = '';
      for (let i = 0; i < bytes.length; i++) {
        out += HEX_PAIRS[bytes[i]];
      }
      return out;
    }
    case 'base64':
    case 'base64url': {
      const url = normalize(encoding) === 'base64url';
      const alphabet = url ? B64_URL_CODES : B64_CODES;
      const out = new Uint8Array(Math.ceil(bytes.length / 3) * 4);
      let n = 0;
      let i = 0;
      for (; i + 2 < bytes.length; i += 3) {
        const v = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
        out[n++] = alphabet[v >> 18];
        out[n++] = alphabet[(v >> 12) & 63];
        out[n++] = alphabet[(v >> 6) & 63];
        out[n++] = alphabet[v & 63];
      }
      if (i < bytes.length) {
        const two = i + 1 < bytes.length;
        const v = (bytes[i] << 16) | ((two ? bytes[i + 1] : 0) << 8);
        out[n++] = alphabet[v >> 18];
        out[n++] = alphabet[(v >> 12) & 63];
        if (two) {
          out[n++] = alphabet[(v >> 6) & 63];
        } else if (!url) {
          out[n++] = 61;
        }
        if (!url) {
          out[n++] = 61;
        }
      }
      return latin1String(out.subarray(0, n));
    }
    case 'latin1': {
      let out = '';
      for (let i = 0; i < bytes.length; i += 4096) {
        out += String.fromCharCode.apply(null, bytes.subarray(i, i + 4096));
      }
      return out;
    }
    case 'ascii': {
      let out = '';
      for (let i = 0; i < bytes.length; i++) {
        out += String.fromCharCode(bytes[i] & 0x7f);
      }
      return out;
    }
    case 'utf16le': {
      let out = '';
      for (let i = 0; i + 1 < bytes.length; i += 2) {
        out += String.fromCharCode(bytes[i] | (bytes[i + 1] << 8));
      }
      return out;
    }
  }
}

function view(buf) {
  return new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
}

function checkOffset(buf, offset, size) {
  if (offset === undefined) {
    offset = 0;
  }
  if (!Number.isInteger(offset) || offset < 0 || offset + size > buf.length) {
    throw Object.assign(new RangeError('The value of "offset" is out of range. It must be >= 0 and <= ' + (buf.length - size) + '. Received ' + offset),
      { code: 'ERR_OUT_OF_RANGE' });
  }
  return offset;
}

// Integers of 1 to 6 bytes (readUIntBE and the rest), with Node's checks and messages (lib/internal/buffer.js; D-125).
function checkByteLength(byteLength) {
  if (typeof byteLength !== 'number') {
    throw Object.assign(new TypeError(`The "byteLength" argument must be of type number. Received ${byteLength === undefined ? 'undefined'
      : byteLength === null ? 'null' : `type ${typeof byteLength} (${typeof byteLength === 'string' ? `'${byteLength}'` : String(byteLength)})`}`),
    { code: 'ERR_INVALID_ARG_TYPE' });
  }
  if (!Number.isInteger(byteLength)) {
    throw Object.assign(new RangeError(`The value of "byteLength" is out of range. It must be an integer. Received ${byteLength}`), { code: 'ERR_OUT_OF_RANGE' });
  }
  if (byteLength < 1 || byteLength > 6) {
    throw Object.assign(new RangeError(`The value of "byteLength" is out of range. It must be >= 1 and <= 6. Received ${byteLength}`), { code: 'ERR_OUT_OF_RANGE' });
  }
}

function checkIntValue(value, byteLength, signed) {
  if (typeof value !== 'number') {
    throw Object.assign(new TypeError(`The "value" argument must be of type number. Received type ${typeof value} (${String(value)})`),
      { code: 'ERR_INVALID_ARG_TYPE' });
  }
  const bits = byteLength * 8;
  const min = signed ? -(2 ** (bits - 1)) : 0;
  const max = signed ? 2 ** (bits - 1) - 1 : 2 ** bits - 1;
  if (value > max || value < min) {
    const range = byteLength > 4
      ? (signed ? `>= -(2 ** ${bits - 1}) and < 2 ** ${bits - 1}` : `>= 0 and < 2 ** ${bits}`)
      : `>= ${min} and <= ${max}`;
    throw Object.assign(new RangeError(`The value of "value" is out of range. It must be ${range}. Received ${Math.abs(value) > 2 ** 32 ? String(value).replace(/\B(?=(\d{3})+(?!\d))/g, '_') : value}`),
      { code: 'ERR_OUT_OF_RANGE' });
  }
}

function readVariable(buf, offset, byteLength, littleEndian, signed) {
  checkByteLength(byteLength);
  offset = checkOffset(buf, offset, byteLength);
  let value = 0;
  for (let i = 0; i < byteLength; i++) value = value * 256 + buf[offset + (littleEndian ? byteLength - 1 - i : i)];
  return signed && value >= 2 ** (byteLength * 8 - 1) ? value - 2 ** (byteLength * 8) : value;
}

function writeVariable(buf, value, offset, byteLength, littleEndian, signed) {
  checkByteLength(byteLength);
  checkIntValue(value, byteLength, signed);
  offset = checkOffset(buf, offset, byteLength);
  let rest = Math.trunc(value < 0 ? value + 2 ** (byteLength * 8) : value);
  for (let i = byteLength - 1; i >= 0; i--) {
    buf[offset + (littleEndian ? byteLength - 1 - i : i)] = rest % 256;
    rest = Math.floor(rest / 256);
  }
  return offset + byteLength;
}

export class Buffer extends Uint8Array {
  static from(value, encodingOrOffset, length) {
    if (typeof value === 'string') {
      const bytes = encode(value, encodingOrOffset);
      return new Buffer(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    }
    if (value instanceof ArrayBuffer || (typeof SharedArrayBuffer !== 'undefined' && value instanceof SharedArrayBuffer)) {
      const offset = encodingOrOffset === undefined ? 0 : encodingOrOffset;
      return new Buffer(value, offset, length === undefined ? value.byteLength - offset : length);
    }
    if (ArrayBuffer.isView(value)) {
      const copy = new Buffer(value.byteLength);
      copy.set(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
      return copy;
    }
    if (Array.isArray(value)) {
      const copy = new Buffer(value.length);
      for (let i = 0; i < value.length; i++) {
        copy[i] = value[i] & 0xff;
      }
      return copy;
    }
    if (value && typeof value === 'object') {
      if (value.type === 'Buffer' && Array.isArray(value.data)) {
        return Buffer.from(value.data);
      }
      if (typeof value.length === 'number') {
        return Buffer.from(Array.from(value));
      }
      if (typeof value.valueOf === 'function' && value.valueOf() !== value) {
        return Buffer.from(value.valueOf(), encodingOrOffset, length);
      }
    }
    throw new TypeError('The first argument must be a string, Buffer, ArrayBuffer, Array, or array-like object. Received ' + typeof value);
  }

  static alloc(size, fill, encoding) {
    const buf = new Buffer(size);
    if (fill !== undefined && fill !== 0) {
      buf.fill(fill, 0, size, encoding);
    }
    return buf;
  }

  static allocUnsafe(size) {
    return new Buffer(size);
  }

  static allocUnsafeSlow(size) {
    return new Buffer(size);
  }

  static isBuffer(value) {
    return value instanceof Buffer;
  }

  static isEncoding(encoding) {
    return typeof encoding === 'string' && ENCODINGS.includes(encoding.toLowerCase());
  }

  static byteLength(value, encoding) {
    if (typeof value !== 'string') {
      return value.byteLength;
    }
    return encode(value, encoding).length;
  }

  static concat(list, totalLength) {
    if (totalLength === undefined) {
      totalLength = list.reduce((sum, b) => sum + b.length, 0);
    }
    const out = Buffer.alloc(totalLength);
    let at = 0;
    for (const b of list) {
      if (at >= totalLength) {
        break;
      }
      const part = b.length > totalLength - at ? b.subarray(0, totalLength - at) : b;
      out.set(part, at);
      at += part.length;
    }
    return out;
  }

  static compare(a, b) {
    const n = Math.min(a.length, b.length);
    for (let i = 0; i < n; i++) {
      if (a[i] !== b[i]) {
        return a[i] < b[i] ? -1 : 1;
      }
    }
    return a.length === b.length ? 0 : a.length < b.length ? -1 : 1;
  }

  get parent() {
    return this.buffer;
  }

  get offset() {
    return this.byteOffset;
  }

  toString(encoding, start = 0, end = this.length) {
    start = Math.max(0, start | 0);
    end = Math.min(this.length, end === undefined ? this.length : end | 0);
    return end <= start ? '' : decode(this.subarray(start, end), encoding);
  }

  toLocaleString(encoding, start, end) {
    return this.toString(encoding, start, end);
  }

  toJSON() {
    return { type: 'Buffer', data: Array.from(this) };
  }

  equals(other) {
    return Buffer.compare(this, other) === 0;
  }

  compare(target, targetStart = 0, targetEnd = target.length, sourceStart = 0, sourceEnd = this.length) {
    return Buffer.compare(this.subarray(sourceStart, sourceEnd), target.subarray(targetStart, targetEnd));
  }

  slice(start, end) {
    return this.subarray(start, end);
  }

  write(string, offset, length, encoding) {
    if (typeof offset === 'string') {
      encoding = offset;
      offset = 0;
      length = undefined;
    } else if (typeof length === 'string') {
      encoding = length;
      length = undefined;
    }
    offset = offset === undefined ? 0 : offset;
    const bytes = encode(string, encoding);
    const n = Math.min(bytes.length, this.length - offset, length === undefined ? Infinity : length);
    this.set(bytes.subarray(0, n), offset);
    return n;
  }

  // Node's rules: what fits is copied and the count returned; a start at or past the end copies nothing (D-116: before,
  // that computed a negative length, which subarray() read from the end, and set() threw).
  copy(target, targetStart, sourceStart, sourceEnd) {
    const integer = (value, fallback) => (value === undefined ? fallback : Math.trunc(Number(value)) || 0);
    targetStart = integer(targetStart, 0);
    sourceStart = integer(sourceStart, 0);
    sourceEnd = integer(sourceEnd, this.length);
    if (targetStart < 0) {
      throw Object.assign(new RangeError(`The value of "targetStart" is out of range. It must be >= 0. Received ${targetStart}`), { code: 'ERR_OUT_OF_RANGE' });
    }
    if (sourceStart < 0 || sourceStart > this.length) {
      throw Object.assign(new RangeError(`The value of "sourceStart" is out of range. It must be >= 0 && <= ${this.length}. Received ${sourceStart}`),
        { code: 'ERR_OUT_OF_RANGE' });
    }
    if (sourceEnd < 0) {
      throw Object.assign(new RangeError(`The value of "sourceEnd" is out of range. It must be >= 0. Received ${sourceEnd}`), { code: 'ERR_OUT_OF_RANGE' });
    }
    sourceEnd = Math.min(sourceEnd, this.length);
    if (targetStart >= target.length || sourceStart >= sourceEnd) return 0;
    const count = Math.min(sourceEnd - sourceStart, target.length - targetStart);
    target.set(this.subarray(sourceStart, sourceStart + count), targetStart);
    return count;
  }

  fill(value, offset = 0, end = this.length, encoding) {
    if (typeof offset === 'string') {
      encoding = offset;
      offset = 0;
      end = this.length;
    }
    if (typeof value === 'string') {
      const bytes = encode(value, encoding);
      if (bytes.length === 0) {
        return super.fill(0, offset, end);
      }
      for (let i = offset; i < end; i++) {
        this[i] = bytes[(i - offset) % bytes.length];
      }
      return this;
    }
    if (ArrayBuffer.isView(value)) {
      for (let i = offset; i < end; i++) {
        this[i] = value[(i - offset) % value.length];
      }
      return this;
    }
    return super.fill(value & 0xff, offset, end);
  }

  indexOf(value, byteOffset = 0, encoding) {
    if (typeof value === 'number') {
      return super.indexOf(value & 0xff, byteOffset);
    }
    const needle = typeof value === 'string' ? encode(value, encoding) : value;
    if (byteOffset < 0) {
      byteOffset = Math.max(0, this.length + byteOffset);
    }
    outer: for (let i = byteOffset; i <= this.length - needle.length; i++) {
      for (let j = 0; j < needle.length; j++) {
        if (this[i + j] !== needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  includes(value, byteOffset, encoding) {
    return this.indexOf(value, byteOffset, encoding) !== -1;
  }

  readUInt8(offset) { return this[checkOffset(this, offset, 1)]; }
  readInt8(offset) { return view(this).getInt8(checkOffset(this, offset, 1)); }
  readUInt16LE(offset) { return view(this).getUint16(checkOffset(this, offset, 2), true); }
  readUInt16BE(offset) { return view(this).getUint16(checkOffset(this, offset, 2), false); }
  readInt16LE(offset) { return view(this).getInt16(checkOffset(this, offset, 2), true); }
  readInt16BE(offset) { return view(this).getInt16(checkOffset(this, offset, 2), false); }
  readUInt32LE(offset) { return view(this).getUint32(checkOffset(this, offset, 4), true); }
  readUInt32BE(offset) { return view(this).getUint32(checkOffset(this, offset, 4), false); }
  readInt32LE(offset) { return view(this).getInt32(checkOffset(this, offset, 4), true); }
  readInt32BE(offset) { return view(this).getInt32(checkOffset(this, offset, 4), false); }
  readBigUInt64LE(offset) { return view(this).getBigUint64(checkOffset(this, offset, 8), true); }
  readBigUInt64BE(offset) { return view(this).getBigUint64(checkOffset(this, offset, 8), false); }
  readBigInt64LE(offset) { return view(this).getBigInt64(checkOffset(this, offset, 8), true); }
  readBigInt64BE(offset) { return view(this).getBigInt64(checkOffset(this, offset, 8), false); }
  readFloatLE(offset) { return view(this).getFloat32(checkOffset(this, offset, 4), true); }
  readFloatBE(offset) { return view(this).getFloat32(checkOffset(this, offset, 4), false); }
  readDoubleLE(offset) { return view(this).getFloat64(checkOffset(this, offset, 8), true); }
  readDoubleBE(offset) { return view(this).getFloat64(checkOffset(this, offset, 8), false); }

  writeUInt8(value, offset) { this[checkOffset(this, offset, 1)] = value; return (offset || 0) + 1; }
  writeInt8(value, offset) { view(this).setInt8(checkOffset(this, offset, 1), value); return (offset || 0) + 1; }
  writeUInt16LE(value, offset) { view(this).setUint16(checkOffset(this, offset, 2), value, true); return (offset || 0) + 2; }
  writeUInt16BE(value, offset) { view(this).setUint16(checkOffset(this, offset, 2), value, false); return (offset || 0) + 2; }
  writeInt16LE(value, offset) { view(this).setInt16(checkOffset(this, offset, 2), value, true); return (offset || 0) + 2; }
  writeInt16BE(value, offset) { view(this).setInt16(checkOffset(this, offset, 2), value, false); return (offset || 0) + 2; }
  writeUInt32LE(value, offset) { view(this).setUint32(checkOffset(this, offset, 4), value, true); return (offset || 0) + 4; }
  writeUInt32BE(value, offset) { view(this).setUint32(checkOffset(this, offset, 4), value, false); return (offset || 0) + 4; }
  writeInt32LE(value, offset) { view(this).setInt32(checkOffset(this, offset, 4), value, true); return (offset || 0) + 4; }
  writeInt32BE(value, offset) { view(this).setInt32(checkOffset(this, offset, 4), value, false); return (offset || 0) + 4; }
  writeBigUInt64LE(value, offset) { view(this).setBigUint64(checkOffset(this, offset, 8), value, true); return (offset || 0) + 8; }
  writeBigUInt64BE(value, offset) { view(this).setBigUint64(checkOffset(this, offset, 8), value, false); return (offset || 0) + 8; }
  writeBigInt64LE(value, offset) { view(this).setBigInt64(checkOffset(this, offset, 8), value, true); return (offset || 0) + 8; }
  writeBigInt64BE(value, offset) { view(this).setBigInt64(checkOffset(this, offset, 8), value, false); return (offset || 0) + 8; }
  writeFloatLE(value, offset) { view(this).setFloat32(checkOffset(this, offset, 4), value, true); return (offset || 0) + 4; }
  writeFloatBE(value, offset) { view(this).setFloat32(checkOffset(this, offset, 4), value, false); return (offset || 0) + 4; }
  writeDoubleLE(value, offset) { view(this).setFloat64(checkOffset(this, offset, 8), value, true); return (offset || 0) + 8; }
  writeDoubleBE(value, offset) { view(this).setFloat64(checkOffset(this, offset, 8), value, false); return (offset || 0) + 8; }

  readUIntLE(offset, byteLength) { return readVariable(this, offset, byteLength, true, false); }
  readUIntBE(offset, byteLength) { return readVariable(this, offset, byteLength, false, false); }
  readIntLE(offset, byteLength) { return readVariable(this, offset, byteLength, true, true); }
  readIntBE(offset, byteLength) { return readVariable(this, offset, byteLength, false, true); }
  writeUIntLE(value, offset, byteLength) { return writeVariable(this, value, offset, byteLength, true, false); }
  writeUIntBE(value, offset, byteLength) { return writeVariable(this, value, offset, byteLength, false, false); }
  writeIntLE(value, offset, byteLength) { return writeVariable(this, value, offset, byteLength, true, true); }
  writeIntBE(value, offset, byteLength) { return writeVariable(this, value, offset, byteLength, false, true); }
}

// Node's lowercase spellings.
for (const name of Object.getOwnPropertyNames(Buffer.prototype)) {
  if (/^(read|write)(UInt|BigUInt)/.test(name)) {
    Buffer.prototype[name.replace('UInt', 'Uint')] = Buffer.prototype[name];
  }
}
Buffer.poolSize = 8192;

export const kMaxLength = 2 ** 31 - 1;
export const kStringMaxLength = (1 << 29) - 24;
export const constants = { MAX_LENGTH: kMaxLength, MAX_STRING_LENGTH: kStringMaxLength };
export const INSPECT_MAX_BYTES = 50;

export function atob(data) {
  return decode(encode(String(data), 'base64'), 'latin1');
}

export function btoa(data) {
  const s = String(data);
  for (let i = 0; i < s.length; i++) {
    if (s.charCodeAt(i) > 255) {
      throw new Error('Invalid character');
    }
  }
  return decode(encode(s, 'latin1'), 'base64');
}

// Node's Buffer statics are enumerable own properties (it assigns them, where a class defines them hidden), and code
// copies them with for...in: safer-buffer (mysql2's) does, and fell back to calling Buffer() without new (D-116).
for (const name of ['poolSize', 'from', 'copyBytesFrom', 'of', 'alloc', 'allocUnsafe', 'allocUnsafeSlow', 'isBuffer', 'compare',
  'isEncoding', 'concat', 'byteLength']) {
  const descriptor = Object.getOwnPropertyDescriptor(Buffer, name);
  if (descriptor) Object.defineProperty(Buffer, name, { ...descriptor, enumerable: true });
}
// So are its prototype's methods: mysql2 sizes a packet by writing it to a zero-length Buffer whose methods it swaps for
// no-ops with for...in over Buffer.prototype (D-116).
for (const name of Object.getOwnPropertyNames(Buffer.prototype)) {
  const descriptor = Object.getOwnPropertyDescriptor(Buffer.prototype, name);
  if (name !== 'constructor' && typeof descriptor.value === 'function') {
    Object.defineProperty(Buffer.prototype, name, { ...descriptor, enumerable: true });
  }
}

// Node's Blob and File are the globals (D-124): a small script, loaded here so the named exports are the classes.
export const Blob = globalThis.Blob;
export const File = globalThis.File;
export function isUtf8(input) {
  try { new TextDecoder('utf-8', { fatal: true }).decode(input); return true; } catch { return false; }
}
export function isAscii(input) {
  const bytes = input instanceof ArrayBuffer ? new Uint8Array(input) : new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  for (const b of bytes) if (b > 0x7f) return false;
  return true;
}
export default { Buffer, kMaxLength, kStringMaxLength, constants, INSPECT_MAX_BYTES, atob, btoa, Blob, File, isUtf8, isAscii };
