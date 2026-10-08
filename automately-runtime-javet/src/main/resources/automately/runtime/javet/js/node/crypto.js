// A stand-in for Node's `crypto` (D-095), on the JVM through the same host calls as the global Web Crypto `crypto`:
// randomness, hashing, HMAC and constant-time comparison (D-097); key derivation, ciphers, keys and signatures (D-101).
// Keys stay in the job: a KeyObject holds its DER and hands it to the host with each call. Nothing reaches outside the job.
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
  if (data instanceof KeyObject && data.type === 'secret') {
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

// --- keys ---

const KEY_BYTES = Symbol('keyBytes');
const KEY_INFO = Symbol('keyInfo');
const fromBase64 = (text) => Uint8Array.from(Buffer.from(text, 'base64'));
const invalidArg = (message) => Object.assign(new TypeError(message), { code: 'ERR_INVALID_ARG_TYPE' });

export class KeyObject {
  constructor(type, material) {
    if (type !== 'secret' && type !== 'public' && type !== 'private') {
      throw invalidArg('The argument \'type\' is invalid. Received ' + type);
    }
    this.type = type;
    if (type === 'secret') {
      Object.defineProperty(this, KEY_BYTES, { value: material });
    } else {
      // material is the host's info: asymmetricKeyType, der (base64) and details.
      Object.defineProperty(this, KEY_INFO, { value: Object.freeze({ ...material, der: fromBase64(material.der) }) });
    }
  }

  get symmetricKeySize() {
    return this.type === 'secret' ? this[KEY_BYTES].length : undefined;
  }

  get asymmetricKeyType() {
    return this.type === 'secret' ? undefined : this[KEY_INFO].asymmetricKeyType;
  }

  get asymmetricKeyDetails() {
    if (this.type === 'secret') return undefined;
    const details = { ...this[KEY_INFO].details };
    if (details.publicExponent !== undefined) details.publicExponent = BigInt(details.publicExponent);
    return details;
  }

  export(options) {
    if (this.type === 'secret') {
      const buffer = Buffer.from(this[KEY_BYTES]);
      return options?.format === 'jwk' ? { kty: 'oct', k: buffer.toString('base64url') } : buffer;
    }
    if (options?.cipher !== undefined || options?.passphrase !== undefined) {
      throw Object.assign(new Error('Encrypting exported keys is not available in the isolate yet'), { code: 'ERR_FEATURE_UNAVAILABLE_ON_PLATFORM' });
    }
    const format = options?.format ?? 'pem';
    const out = internals.exportKey(this.type === 'private', this[KEY_INFO].der, format, options?.type);
    return out instanceof Uint8Array ? Buffer.from(out.buffer, out.byteOffset, out.byteLength) : out;
  }

  equals(other) {
    if (!(other instanceof KeyObject) || other.type !== this.type) return false;
    const a = this.type === 'secret' ? this[KEY_BYTES] : this[KEY_INFO].der;
    const b = this.type === 'secret' ? other[KEY_BYTES] : other[KEY_INFO].der;
    return a.length === b.length && internals.timingSafeEqual(a, b);
  }
}

export function createSecretKey(key, encoding) {
  return new KeyObject('secret', Uint8Array.from(bytesOf(key, encoding)));
}

/** A key argument's parts: a KeyObject, PEM text, or { key, format, type, encoding } with DER, PEM or a JWK. */
function keyInput(key) {
  if (key instanceof KeyObject) return { object: key };
  if (typeof key === 'string' || ArrayBuffer.isView(key) || key instanceof ArrayBuffer) return { data: key, format: 'pem' };
  if (key && typeof key === 'object' && 'key' in key) {
    if (key.key instanceof KeyObject) return { object: key.key };
    if (key.format === 'jwk') {
      if (!key.key || typeof key.key !== 'object') throw invalidArg('The "key.key" property must be of type object');
      return { data: JSON.stringify(key.key), format: 'jwk' };
    }
    return { data: key.key, format: key.format ?? 'pem', type: key.type, encoding: key.encoding };
  }
  throw invalidArg('The "key" argument must be of type string or an instance of ArrayBuffer, Buffer, TypedArray, DataView, KeyObject, or CryptoKey');
}

function readAsymmetric(key, wantPrivate) {
  const input = keyInput(key);
  if (input.object) {
    if (input.object.type === 'secret') throw invalidArg('Invalid key object type secret, expected ' + (wantPrivate ? 'private.' : 'public.'));
    return input.object;
  }
  const bytes = typeof input.data === 'string' ? Buffer.from(input.data, input.encoding ?? 'utf8') : bytesOf(input.data);
  const info = internals.readKey(wantPrivate, bytes, input.format, input.type);
  return new KeyObject(info.type, info);
}

export function createPrivateKey(key) {
  const object = readAsymmetric(key, true);
  if (object.type !== 'private') throw invalidArg('Invalid key object type ' + object.type + ', expected private.');
  return object;
}

export function createPublicKey(key) {
  const object = readAsymmetric(key, false);
  return object.type === 'private' ? new KeyObject('public', internals.publicKeyOf(object[KEY_INFO].der)) : object;
}

/** The signing options Node reads from the key argument. */
function signOptions(key) {
  const options = {};
  if (key && typeof key === 'object' && !(key instanceof KeyObject) && !ArrayBuffer.isView(key)) {
    for (const name of ['padding', 'saltLength', 'dsaEncoding']) {
      if (key[name] !== undefined) options[name] = key[name];
    }
  }
  return options;
}

function signWith(algorithm, data, key) {
  const object = createPrivateKey(key);
  const signature = internals.sign(algorithm ?? null, bytesOf(data), object[KEY_INFO].der, signOptions(key));
  return Buffer.from(signature.buffer, signature.byteOffset, signature.byteLength);
}

function verifyWith(algorithm, data, key, signature) {
  const object = readAsymmetric(key, false);
  return internals.verify(algorithm ?? null, bytesOf(data), object[KEY_INFO].der, object.type === 'private', bytesOf(signature), signOptions(key));
}

export function sign(algorithm, data, key, callback) {
  if (typeof callback === 'function') {
    later(callback, () => signWith(algorithm, data, key));
    return undefined;
  }
  return signWith(algorithm, data, key);
}

export function verify(algorithm, data, key, signature, callback) {
  if (typeof callback === 'function') {
    later(callback, () => verifyWith(algorithm, data, key, signature));
    return undefined;
  }
  return verifyWith(algorithm, data, key, signature);
}

/** Node's Sign and Verify collect their data, then sign or verify it at once (the host caps how much). */
class Collector {
  #chunks = [];

  update(data, inputEncoding) {
    this.#chunks.push(Buffer.from(bytesOf(data, inputEncoding)));
    return this;
  }

  write(data, encoding, callback) {
    this.update(data, typeof encoding === 'string' ? encoding : undefined);
    const done = typeof encoding === 'function' ? encoding : callback;
    if (typeof done === 'function') done();
    return true;
  }

  end(data, encoding, callback) {
    if (data !== undefined && typeof data !== 'function') this.update(data, typeof encoding === 'string' ? encoding : undefined);
    const done = [data, encoding, callback].find((f) => typeof f === 'function');
    if (done) done();
    return this;
  }

  collected() {
    return Buffer.concat(this.#chunks);
  }
}

class Sign extends Collector {
  #algorithm;

  constructor(algorithm) {
    super();
    this.#algorithm = algorithm;
  }

  sign(privateKey, outputEncoding) {
    const signature = signWith(this.#algorithm, this.collected(), privateKey);
    return outputEncoding === undefined || outputEncoding === 'buffer' ? signature : signature.toString(outputEncoding);
  }
}

class Verify extends Collector {
  #algorithm;

  constructor(algorithm) {
    super();
    this.#algorithm = algorithm;
  }

  verify(publicKey, signature, signatureEncoding) {
    const signed = typeof signature === 'string' ? Buffer.from(signature, signatureEncoding ?? 'utf8') : signature;
    return verifyWith(this.#algorithm, this.collected(), publicKey, signed);
  }
}

export const createSign = (algorithm) => new Sign(algorithm);
export const createVerify = (algorithm) => new Verify(algorithm);

// --- key generation ---

function keyPairResult(pair, options) {
  const publicKey = new KeyObject('public', pair.publicKey);
  const privateKey = new KeyObject('private', pair.privateKey);
  return {
    publicKey: options?.publicKeyEncoding ? publicKey.export(options.publicKeyEncoding) : publicKey,
    privateKey: options?.privateKeyEncoding ? privateKey.export(options.privateKeyEncoding) : privateKey,
  };
}

function keyPairOptions(options) {
  const out = {};
  for (const name of ['modulusLength', 'namedCurve']) {
    if (options?.[name] !== undefined) out[name] = options[name];
  }
  if (options?.publicExponent !== undefined) out.publicExponent = Number(options.publicExponent);
  return out;
}

export function generateKeyPairSync(type, options) {
  return keyPairResult(internals.generateKeyPair(type, keyPairOptions(options)), options);
}

export function generateKeyPair(type, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = undefined;
  }
  if (typeof callback !== 'function') throw invalidArg('The "callback" argument must be of type function');
  let pending;
  try {
    pending = internals.generateKeyPairAsync(type, keyPairOptions(options));
  } catch (err) {
    later(callback, () => { throw err; });
    return;
  }
  pending.then((pair) => {
    let result;
    try {
      result = keyPairResult(pair, options);
    } catch (err) {
      callback(err);
      return;
    }
    callback(null, result.publicKey, result.privateKey);
  }, (err) => callback(err));
}

/** A secret key: 'hmac' (length in bits, 8 to 2^31-1) or 'aes' (128, 192 or 256). */
export function generateKeySync(type, options) {
  const bits = options?.length;
  if (type === 'aes' ? ![128, 192, 256].includes(bits) : type !== 'hmac' || !Number.isInteger(bits) || bits < 8 || bits % 8 !== 0) {
    throw Object.assign(new RangeError('Invalid key length for ' + type + ': ' + bits), { code: 'ERR_OUT_OF_RANGE' });
  }
  return new KeyObject('secret', Uint8Array.from(randomBytes(bits / 8)));
}

export function generateKey(type, options, callback) {
  later((err, key) => callback(err, key), () => generateKeySync(type, options));
}

// --- key derivation ---

function bytesArg(value, name) {
  if (value instanceof KeyObject) return value[KEY_BYTES];
  if (typeof value === 'string' || ArrayBuffer.isView(value) || value instanceof ArrayBuffer) return bytesOf(value);
  throw invalidArg('The "' + name + '" argument must be of type string or an instance of ArrayBuffer, Buffer, TypedArray, or DataView');
}

function asyncResult(promise, callback, wrap) {
  promise.then((bytes) => {
    let out;
    try {
      out = wrap(bytes);
    } catch (err) {
      callback(err);
      return;
    }
    callback(null, out);
  }, (err) => callback(err));
}

const toBuffer = (bytes) => Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);

export function pbkdf2Sync(password, salt, iterations, keylen, digest) {
  if (typeof digest !== 'string') throw invalidArg('The "digest" argument must be of type string');
  return toBuffer(internals.pbkdf2(bytesArg(password, 'password'), bytesArg(salt, 'salt'), iterations, keylen, digest));
}

export function pbkdf2(password, salt, iterations, keylen, digest, callback) {
  if (typeof callback !== 'function') throw invalidArg('The "callback" argument must be of type function');
  if (typeof digest !== 'string') throw invalidArg('The "digest" argument must be of type string');
  asyncResult(internals.pbkdf2Async(bytesArg(password, 'password'), bytesArg(salt, 'salt'), iterations, keylen, digest), callback, toBuffer);
}

const toArrayBuffer = (bytes) => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);

export function hkdfSync(digest, ikm, salt, info, keylen) {
  return toArrayBuffer(internals.hkdf(digest, bytesArg(ikm, 'ikm'), bytesArg(salt, 'salt'), bytesArg(info, 'info'), keylen));
}

export function hkdf(digest, ikm, salt, info, keylen, callback) {
  later(callback, () => hkdfSync(digest, ikm, salt, info, keylen));
}

function scryptOptions(options) {
  const out = {};
  for (const name of ['N', 'cost', 'r', 'blockSize', 'p', 'parallelization', 'maxmem']) {
    if (options?.[name] !== undefined) out[name] = options[name];
  }
  return out;
}

export function scryptSync(password, salt, keylen, options) {
  return toBuffer(internals.scrypt(bytesArg(password, 'password'), bytesArg(salt, 'salt'), keylen, scryptOptions(options)));
}

export function scrypt(password, salt, keylen, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = undefined;
  }
  if (typeof callback !== 'function') throw invalidArg('The "callback" argument must be of type function');
  asyncResult(internals.scryptAsync(bytesArg(password, 'password'), bytesArg(salt, 'salt'), keylen, scryptOptions(options)), callback, toBuffer);
}

// --- ciphers ---

/** Bytes to a string in pieces, as Node's StringDecoder does: a multi-byte character or base64 group isn't split. */
class Output {
  #encoding;
  #pending = new Uint8Array(0);

  constructor(encoding) {
    this.#encoding = encoding;
  }

  write(bytes, last) {
    const encoding = this.#encoding;
    if (encoding === undefined || encoding === 'buffer') return toBuffer(bytes);
    let all = this.#pending.length ? Buffer.concat([this.#pending, bytes]) : toBuffer(bytes);
    let keep = 0;
    if (!last) {
      if (encoding === 'base64' || encoding === 'base64url') keep = all.length % 3;
      else if (encoding === 'utf8' || encoding === 'utf-8') keep = incompleteUtf8(all);
      else if (encoding === 'utf16le' || encoding === 'ucs2' || encoding === 'ucs-2') keep = all.length % 2;
    }
    this.#pending = Uint8Array.from(all.subarray(all.length - keep));
    return Buffer.from(all.subarray(0, all.length - keep)).toString(encoding);
  }
}

/** How many bytes at the end start a UTF-8 character that isn't complete yet. */
function incompleteUtf8(bytes) {
  for (let back = 1; back <= Math.min(3, bytes.length); back++) {
    const b = bytes[bytes.length - back];
    if ((b & 0xc0) === 0x80) continue;
    const needed = b >= 0xf0 ? 4 : b >= 0xe0 ? 3 : b >= 0xc0 ? 2 : 1;
    return needed > back ? back : 0;
  }
  return 0;
}

const AEAD = /-gcm$|^chacha20-poly1305$/i;

class CipherBase {
  #handle;
  #encrypt;
  #aead;
  #tagLength;
  #authTag;
  #output;
  #done = false;

  constructor(algorithm, key, iv, options, encrypt) {
    this.#encrypt = encrypt;
    this.#aead = AEAD.test(String(algorithm));
    this.#tagLength = options?.authTagLength ?? 16;
    const keyBytes = key instanceof KeyObject ? key[KEY_BYTES] : bytesOf(key);
    this.#handle = internals.cipher(String(algorithm), encrypt, keyBytes, iv === null || iv === undefined ? null : bytesOf(iv),
      options?.authTagLength ?? null);
  }

  #check() {
    if (this.#done) {
      throw Object.assign(new Error('Unsupported state'), { code: 'ERR_CRYPTO_INVALID_STATE' });
    }
  }

  update(data, inputEncoding, outputEncoding) {
    this.#check();
    this.#output ??= new Output(outputEncoding);
    return this.#output.write(internals.cipherUpdate(this.#handle, bytesOf(data, inputEncoding)), false);
  }

  final(outputEncoding) {
    this.#check();
    this.#done = true;
    let out = internals.cipherFinal(this.#handle, this.#encrypt ? null : this.#authTag ?? null);
    if (this.#aead && this.#encrypt) {
      this.#authTag = Buffer.from(out.subarray(out.length - this.#tagLength));
      out = out.subarray(0, out.length - this.#tagLength);
    }
    this.#output ??= new Output(outputEncoding);
    return this.#output.write(out, true);
  }

  setAutoPadding(autoPadding = true) {
    this.#check();
    internals.cipherPadding(this.#handle, Boolean(autoPadding));
    return this;
  }

  setAAD(buffer) {
    this.#check();
    internals.cipherAad(this.#handle, bytesOf(buffer));
    return this;
  }

  getAuthTag() {
    if (!this.#encrypt || !this.#aead || !this.#done) {
      throw Object.assign(new Error('Invalid state for operation getAuthTag'), { code: 'ERR_CRYPTO_INVALID_STATE' });
    }
    return this.#authTag;
  }

  setAuthTag(tag) {
    if (this.#encrypt || !this.#aead || this.#done) {
      throw Object.assign(new Error('Invalid state for operation setAuthTag'), { code: 'ERR_CRYPTO_INVALID_STATE' });
    }
    this.#authTag = Uint8Array.from(bytesOf(tag));
    return this;
  }
}

class Cipheriv extends CipherBase {
  constructor(algorithm, key, iv, options) {
    super(algorithm, key, iv, options, true);
  }
}
Cipheriv.prototype.setAuthTag = undefined;

class Decipheriv extends CipherBase {
  constructor(algorithm, key, iv, options) {
    super(algorithm, key, iv, options, false);
  }
}
Decipheriv.prototype.getAuthTag = undefined;

export const createCipheriv = (algorithm, key, iv, options) => new Cipheriv(algorithm, key, iv, options);
export const createDecipheriv = (algorithm, key, iv, options) => new Decipheriv(algorithm, key, iv, options);
export const getCiphers = () => internals.ciphers().slice();
export const getCurves = () => internals.curves().slice();

// --- not yet ---

const notYet = (name) => () => {
  throw Object.assign(new Error('crypto.' + name + ' is not available in the isolate yet'), { code: 'ERR_FEATURE_UNAVAILABLE_ON_PLATFORM' });
};
// --- RSA encryption ---

function rsaWith(operation, key, buffer) {
  const object = operation === 'privateDecrypt' || operation === 'privateEncrypt' ? createPrivateKey(key) : readAsymmetric(key, false);
  const options = {};
  if (key && typeof key === 'object' && !(key instanceof KeyObject) && !ArrayBuffer.isView(key)) {
    if (key.padding !== undefined) options.padding = key.padding;
    if (key.oaepHash !== undefined) options.oaepHash = key.oaepHash;
    if (key.oaepLabel !== undefined) options.oaepLabel = Buffer.from(bytesOf(key.oaepLabel)).toString('base64');
  }
  return toBuffer(internals.rsa(operation, object[KEY_INFO].der, object.type === 'private', bytesOf(buffer), options));
}

export const publicEncrypt = (key, buffer) => rsaWith('publicEncrypt', key, buffer);
export const privateDecrypt = (key, buffer) => rsaWith('privateDecrypt', key, buffer);
export const privateEncrypt = (key, buffer) => rsaWith('privateEncrypt', key, buffer);
export const publicDecrypt = (key, buffer) => rsaWith('publicDecrypt', key, buffer);

// --- key agreement ---

/** Node's diffieHellman({ privateKey, publicKey }): ECDH on one curve, or X25519. */
export function diffieHellman(options) {
  const privateKey = options?.privateKey;
  const publicKey = options?.publicKey;
  if (!(privateKey instanceof KeyObject) || !(publicKey instanceof KeyObject)) {
    throw invalidArg('The "options.privateKey" and "options.publicKey" properties must be KeyObjects');
  }
  if (privateKey.type !== 'private') throw invalidArg('Invalid key object type ' + privateKey.type + ', expected private.');
  const pub = publicKey.type === 'private' ? createPublicKey(publicKey) : publicKey;
  return toBuffer(internals.agree(privateKey[KEY_INFO].der, pub[KEY_INFO].der));
}

/** SEC1's point encodings from an uncompressed point. */
function pointAs(uncompressed, format) {
  if (format === undefined || format === 'uncompressed') return Buffer.from(uncompressed);
  const size = (uncompressed.length - 1) / 2;
  const odd = uncompressed[uncompressed.length - 1] & 1;
  if (format === 'compressed') return Buffer.concat([Buffer.from([odd ? 3 : 2]), Buffer.from(uncompressed.subarray(1, 1 + size))]);
  if (format === 'hybrid') return Buffer.concat([Buffer.from([odd ? 7 : 6]), Buffer.from(uncompressed.subarray(1))]);
  throw Object.assign(new TypeError('Invalid ECDH format: ' + format), { code: 'ERR_CRYPTO_ECDH_INVALID_FORMAT' });
}

const asBytes = (value, encoding) => (typeof value === 'string' ? Buffer.from(value, encoding ?? 'utf8') : bytesOf(value));
const asOutput = (buffer, encoding) => (encoding === undefined || encoding === null || encoding === 'buffer' ? buffer : buffer.toString(encoding));

export class ECDH {
  #curve;
  #private;
  #public;

  constructor(curve) {
    this.#curve = String(curve);
    if (!['prime256v1', 'secp256r1', 'P-256', 'secp384r1', 'P-384', 'secp521r1', 'P-521'].includes(this.#curve)) {
      throw Object.assign(new TypeError('Invalid EC curve name'), { code: 'ERR_CRYPTO_INVALID_CURVE' });
    }
  }

  #set(keys) {
    this.#private = Buffer.from(keys.privateKey, 'base64');
    this.#public = Buffer.from(keys.publicKey, 'base64');
  }

  generateKeys(encoding, format) {
    this.#set(internals.ecdhKeys(this.#curve, null));
    return this.getPublicKey(encoding, format);
  }

  setPrivateKey(key, encoding) {
    this.#set(internals.ecdhKeys(this.#curve, asBytes(key, encoding)));
  }

  getPrivateKey(encoding) {
    if (!this.#private) throw Object.assign(new Error('Failed to get ECDH private key'), { code: 'ERR_CRYPTO_ECDH_INVALID_PUBLIC_KEY' });
    return asOutput(Buffer.from(this.#private), encoding);
  }

  getPublicKey(encoding, format) {
    if (!this.#public) throw Object.assign(new Error('Failed to get ECDH public key'), { code: 'ERR_CRYPTO_ECDH_INVALID_PUBLIC_KEY' });
    return asOutput(pointAs(this.#public, format), encoding);
  }

  computeSecret(otherPublicKey, inputEncoding, outputEncoding) {
    if (!this.#private) throw Object.assign(new Error('Failed to compute ECDH key'), { code: 'ERR_CRYPTO_INVALID_STATE' });
    return asOutput(toBuffer(internals.ecdhCompute(this.#curve, this.#private, asBytes(otherPublicKey, inputEncoding))), outputEncoding);
  }

  static convertKey(key, curve, inputEncoding, outputEncoding, format) {
    return asOutput(pointAs(toBuffer(internals.ecdhPoint(String(curve), asBytes(key, inputEncoding))), format), outputEncoding);
  }
}

export const createECDH = (curve) => new ECDH(curve);
export const createDiffieHellman = notYet('createDiffieHellman');
export const getDiffieHellman = notYet('getDiffieHellman');

export const constants = Object.freeze({
  RSA_PKCS1_PADDING: 1, RSA_NO_PADDING: 3, RSA_PKCS1_OAEP_PADDING: 4, RSA_PKCS1_PSS_PADDING: 6,
  RSA_PSS_SALTLEN_DIGEST: -1, RSA_PSS_SALTLEN_MAX_SIGN: -2, RSA_PSS_SALTLEN_AUTO: -2,
});
export const subtle = webcrypto.subtle;
export { webcrypto };

export default {
  randomBytes, pseudoRandomBytes, randomFillSync, randomFill, randomInt, randomUUID, getRandomValues, createHash, createHmac, hash,
  getHashes, timingSafeEqual, KeyObject, createSecretKey, createPrivateKey, createPublicKey, sign, verify, createSign, createVerify,
  generateKeyPair, generateKeyPairSync, generateKey, generateKeySync, pbkdf2, pbkdf2Sync, hkdf, hkdfSync, scrypt, scryptSync,
  createCipheriv, createDecipheriv, getCiphers, getCurves, publicEncrypt, privateDecrypt, privateEncrypt, publicDecrypt, createECDH, ECDH,
  createDiffieHellman, getDiffieHellman, diffieHellman, constants, subtle, webcrypto,
};
