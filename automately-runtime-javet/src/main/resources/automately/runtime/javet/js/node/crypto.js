// A stand-in for Node's `crypto` (D-095), on the JVM through the same host calls as the global Web Crypto `crypto`:
// randomness, hashing, HMAC and constant-time comparison. Every call is cheap and capped; nothing reaches outside the job.
// Ciphers, key derivation and signatures come next; until then they say so rather than half-work.
import { Buffer } from 'node:buffer';

const internals = globalThis.crypto[Symbol.for('automately.crypto')];
const webcrypto = globalThis.crypto;
const MAX_RANDOM = 65536;

/** The bytes of a Buffer, typed array, DataView, ArrayBuffer or string, as a Uint8Array that starts at 0. */
function bytesOf(data, encoding) {
  if (typeof data === 'string') {
    return Buffer.from(data, encoding ?? 'utf8');
  }
  if (data instanceof ArrayBuffer) {
    return new Uint8Array(data);
  }
  if (ArrayBuffer.isView(data)) {
    const view = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    return data.byteOffset === 0 && data.byteLength === data.buffer.byteLength ? view : view.slice();
  }
  if (data && typeof data === 'object' && data.type === 'secret' && data[KEY_BYTES]) {
    return data[KEY_BYTES];
  }
  throw new TypeError('The "data" argument must be of type string or an instance of Buffer, TypedArray, or DataView. Received ' + typeof data);
}

function encoded(bytes, encoding) {
  const buffer = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return encoding === undefined || encoding === 'buffer' ? buffer : buffer.toString(encoding);
}

function later(callback, fn) {
  Promise.resolve().then(() => {
    let result;
    try {
      result = fn();
    } catch (err) {
      callback(err);
      return;
    }
    callback(null, result);
  });
}

// --- randomness ---

export function randomBytes(size, callback) {
  if (!Number.isInteger(size) || size < 0 || size > 2 ** 31 - 1) {
    throw new RangeError('The value of "size" is out of range. It must be >= 0 && <= 2147483647. Received ' + size);
  }
  const run = () => {
    const out = Buffer.alloc(size);
    for (let at = 0; at < size; at += MAX_RANDOM) {
      out.set(internals.randomBytes(Math.min(MAX_RANDOM, size - at)), at);
    }
    return out;
  };
  if (typeof callback === 'function') {
    later(callback, run);
    return undefined;
  }
  return run();
}
export const pseudoRandomBytes = randomBytes;

export function randomFillSync(buffer, offset = 0, size) {
  const view = new Uint8Array(buffer.buffer ?? buffer, (buffer.byteOffset ?? 0) + offset,
    size ?? (buffer.byteLength - offset));
  for (let at = 0; at < view.length; at += MAX_RANDOM) {
    view.set(internals.randomBytes(Math.min(MAX_RANDOM, view.length - at)), at);
  }
  return buffer;
}

export function randomFill(buffer, offset, size, callback) {
  if (typeof offset === 'function') {
    callback = offset;
    offset = 0;
    size = undefined;
  } else if (typeof size === 'function') {
    callback = size;
    size = undefined;
  }
  later(callback, () => randomFillSync(buffer, offset, size));
}

export function randomInt(min, max, callback) {
  if (max === undefined || typeof max === 'function') {
    callback = max;
    max = min;
    min = 0;
  }
  if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max) || max <= min || max - min > 2 ** 48 - 1) {
    throw new RangeError('randomInt needs safe integers with min < max and a range below 2^48');
  }
  const range = max - min;
  const run = () => {
    // Rejection sampling over 48 bits, so every value in the range is equally likely.
    const limit = 2 ** 48 - (2 ** 48 % range);
    for (;;) {
      const b = internals.randomBytes(6);
      const n = b[0] * 2 ** 40 + b[1] * 2 ** 32 + b[2] * 2 ** 24 + b[3] * 2 ** 16 + b[4] * 2 ** 8 + b[5];
      if (n < limit) {
        return min + (n % range);
      }
    }
  };
  if (typeof callback === 'function') {
    later(callback, run);
    return undefined;
  }
  return run();
}

export const randomUUID = () => internals.randomUUID();
export const getRandomValues = (array) => webcrypto.getRandomValues(array);

// --- hashing ---

class Hash {
  #handle;
  #done = false;

  constructor(algorithm, key, handle) {
    this.#handle = handle ?? internals.open(algorithm, key === undefined ? null : key);
  }

  update(data, inputEncoding) {
    if (this.#done) {
      throw Object.assign(new Error('Digest already called'), { code: 'ERR_CRYPTO_HASH_FINALIZED' });
    }
    internals.update(this.#handle, bytesOf(data, inputEncoding));
    return this;
  }

  digest(encoding) {
    if (this.#done) {
      throw Object.assign(new Error('Digest already called'), { code: 'ERR_CRYPTO_HASH_FINALIZED' });
    }
    this.#done = true;
    return encoded(internals.finish(this.#handle), encoding);
  }

  copy() {
    if (this.#done) {
      throw Object.assign(new Error('Digest already called'), { code: 'ERR_CRYPTO_HASH_FINALIZED' });
    }
    return new Hash(null, undefined, internals.copy(this.#handle));
  }
}

export function createHash(algorithm) {
  return new Hash(algorithm);
}

export function createHmac(algorithm, key) {
  if (key === null || key === undefined) {
    throw new TypeError('The "key" argument must be of type string or an instance of Buffer, TypedArray, DataView, or KeyObject');
  }
  const hmac = new Hash(algorithm, bytesOf(key));
  hmac.copy = undefined;
  return hmac;
}

/** One-shot hashing (Node 21.7): hex unless asked otherwise. */
export function hash(algorithm, data, outputEncoding = 'hex') {
  return encoded(internals.digest(algorithm, bytesOf(data)), outputEncoding);
}

export function getHashes() {
  return internals.hashes().slice();
}

export function timingSafeEqual(a, b) {
  return internals.timingSafeEqual(bytesOf(a), bytesOf(b));
}

// --- keys: secret keys only so far ---

const KEY_BYTES = Symbol('keyBytes');

export class KeyObject {
  constructor(type, bytes) {
    this.type = type;
    Object.defineProperty(this, KEY_BYTES, { value: bytes });
  }

  get symmetricKeySize() {
    return this.type === 'secret' ? this[KEY_BYTES].length : undefined;
  }

  export(options) {
    const buffer = Buffer.from(this[KEY_BYTES]);
    return options?.format === 'jwk' ? { kty: 'oct', k: buffer.toString('base64url') } : buffer;
  }

  equals(other) {
    return other instanceof KeyObject && other.type === this.type && this[KEY_BYTES].length === other[KEY_BYTES].length
      && internals.timingSafeEqual(this[KEY_BYTES], other[KEY_BYTES]);
  }
}

export function createSecretKey(key, encoding) {
  return new KeyObject('secret', Uint8Array.from(bytesOf(key, encoding)));
}

// --- not yet (D-095 step 2) ---

const notYet = (name) => () => {
  throw Object.assign(new Error('crypto.' + name + ' is not available in the isolate yet'), { code: 'ERR_FEATURE_UNAVAILABLE_ON_PLATFORM' });
};
export const createCipheriv = notYet('createCipheriv');
export const createDecipheriv = notYet('createDecipheriv');
export const createSign = notYet('createSign');
export const createVerify = notYet('createVerify');
export const sign = notYet('sign');
export const verify = notYet('verify');
export const pbkdf2 = notYet('pbkdf2');
export const pbkdf2Sync = notYet('pbkdf2Sync');
export const scrypt = notYet('scrypt');
export const scryptSync = notYet('scryptSync');
export const hkdf = notYet('hkdf');
export const hkdfSync = notYet('hkdfSync');
export const generateKeyPair = notYet('generateKeyPair');
export const generateKeyPairSync = notYet('generateKeyPairSync');
export const createPublicKey = notYet('createPublicKey');
export const createPrivateKey = notYet('createPrivateKey');
export const getCiphers = () => [];
export const getCurves = () => [];
export const constants = Object.freeze({});
export const subtle = webcrypto.subtle;
export { webcrypto };

export default {
  randomBytes, pseudoRandomBytes, randomFillSync, randomFill, randomInt, randomUUID, getRandomValues, createHash, createHmac, hash,
  getHashes, timingSafeEqual, KeyObject, createSecretKey, createCipheriv, createDecipheriv, createSign, createVerify, sign, verify,
  pbkdf2, pbkdf2Sync, scrypt, scryptSync, hkdf, hkdfSync, generateKeyPair, generateKeyPairSync, createPublicKey, createPrivateKey,
  getCiphers, getCurves, constants, subtle, webcrypto,
};
