// SubtleCrypto (D-104): Web Crypto's keys, signatures, encryption and derivation, on the same host calls as node:crypto
// (the JVM's providers; keys stay in the job as bytes). Algorithms: HMAC, AES-GCM/CBC/CTR, RSASSA-PKCS1-v1_5, RSA-PSS,
// RSA-OAEP, ECDSA, ECDH, Ed25519, X25519, PBKDF2, HKDF. AES-KW isn't supported.
const KEY_MATERIAL = Symbol('keyMaterial');
const b64 = (bytes) => {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoaSafe(s);
};
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const btoaSafe = (binary) => {
  let out = '';
  for (let i = 0; i < binary.length; i += 3) {
    const a = binary.charCodeAt(i), b = binary.charCodeAt(i + 1), c = binary.charCodeAt(i + 2);
    const n = (a << 16) | ((b || 0) << 8) | (c || 0);
    out += B64[n >> 18] + B64[(n >> 12) & 63] + (i + 1 < binary.length ? B64[(n >> 6) & 63] : '=') + (i + 2 < binary.length ? B64[n & 63] : '=');
  }
  return out;
};
const fromB64 = (text) => {
  const clean = String(text).replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
  const out = new Uint8Array(Math.floor(clean.length * 3 / 4));
  let bits = 0, value = 0, at = 0;
  for (const ch of clean) {
    const v = B64.indexOf(ch);
    if (v < 0) throw domError('DataError', 'Invalid base64');
    value = (value << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[at++] = (value >> bits) & 0xff;
    }
  }
  return out.subarray(0, at);
};
const toB64url = (bytes) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const arrayBuffer = (bytes) => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
const bytesIn = (data) => {
  if (data instanceof ArrayBuffer) return new Uint8Array(data).slice();
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength).slice();
  throw new TypeError('The data must be an ArrayBuffer or an ArrayBufferView');
};

const NAMES = ['HMAC', 'AES-GCM', 'AES-CBC', 'AES-CTR', 'AES-KW', 'RSASSA-PKCS1-v1_5', 'RSA-PSS', 'RSA-OAEP', 'ECDSA', 'ECDH',
  'Ed25519', 'X25519', 'PBKDF2', 'HKDF'];
const HASHES = { 'SHA-1': 'sha1', 'SHA-256': 'sha256', 'SHA-384': 'sha384', 'SHA-512': 'sha512' };
const CURVES = { 'P-256': 'prime256v1', 'P-384': 'secp384r1', 'P-521': 'secp521r1' };
const USAGES = {
  HMAC: { secret: ['sign', 'verify'] },
  'AES-GCM': { secret: ['encrypt', 'decrypt', 'wrapKey', 'unwrapKey'] },
  'AES-CBC': { secret: ['encrypt', 'decrypt', 'wrapKey', 'unwrapKey'] },
  'AES-CTR': { secret: ['encrypt', 'decrypt', 'wrapKey', 'unwrapKey'] },
  'RSASSA-PKCS1-v1_5': { private: ['sign'], public: ['verify'] },
  'RSA-PSS': { private: ['sign'], public: ['verify'] },
  ECDSA: { private: ['sign'], public: ['verify'] },
  Ed25519: { private: ['sign'], public: ['verify'] },
  'RSA-OAEP': { private: ['decrypt', 'unwrapKey'], public: ['encrypt', 'wrapKey'] },
  ECDH: { private: ['deriveKey', 'deriveBits'], public: [] },
  X25519: { private: ['deriveKey', 'deriveBits'], public: [] },
  PBKDF2: { secret: ['deriveKey', 'deriveBits'] },
  HKDF: { secret: ['deriveKey', 'deriveBits'] },
};
const KEY_TYPES = { 'RSASSA-PKCS1-v1_5': 'rsa', 'RSA-PSS': 'rsa', 'RSA-OAEP': 'rsa', ECDSA: 'ec', ECDH: 'ec', Ed25519: 'ed25519', X25519: 'x25519' };

const normalize = (algorithm) => {
  const object = typeof algorithm === 'string' ? { name: algorithm } : { ...algorithm };
  const name = NAMES.find((n) => n.toLowerCase() === String(object.name ?? '').toLowerCase());
  if (!name) throw domError('NotSupportedError', 'Unrecognized algorithm name');
  object.name = name;
  if (object.hash !== undefined) object.hash = hashOf(object.hash);
  return object;
};
const hashOf = (hash) => {
  const name = Object.keys(HASHES).find((n) => n.toLowerCase() === String(typeof hash === 'string' ? hash : hash?.name ?? '').toLowerCase());
  if (!name) throw domError('NotSupportedError', 'Unrecognized hash name');
  return { name };
};
const defaultHmacLength = (hash) => (hash.name === 'SHA-384' || hash.name === 'SHA-512' ? 1024 : 512);

class CryptoKey {
  constructor() {
    throw new TypeError('Illegal constructor');
  }
}
const makeKey = (type, extractable, algorithm, usages, material) => {
  const key = Object.create(CryptoKey.prototype);
  Object.defineProperties(key, {
    type: { value: type, enumerable: true },
    extractable: { value: Boolean(extractable), enumerable: true },
    algorithm: { value: Object.freeze(algorithm), enumerable: true },
    usages: { value: Object.freeze([...usages]), enumerable: true },
    [KEY_MATERIAL]: { value: material },
  });
  return key;
};
const checkUsages = (name, type, usages) => {
  const allowed = USAGES[name]?.[type] ?? [];
  for (const usage of usages) {
    if (!allowed.includes(usage)) throw domError('SyntaxError', 'Unsupported key usage for a ' + name + ' ' + type + ' key: ' + usage);
  }
};
const use = (key, algorithm, usage) => {
  if (!(key instanceof CryptoKey)) throw new TypeError('The key must be a CryptoKey');
  if (key.algorithm.name !== algorithm.name) throw domError('InvalidAccessError', 'The requested operation is not valid for the provided key');
  if (!key.usages.includes(usage)) throw domError('InvalidAccessError', 'The requested operation is not valid for the provided key');
  return key[KEY_MATERIAL];
};
const operationError = (e) => {
  if (e && (e.name === 'NotSupportedError' || e.name === 'InvalidAccessError' || e.name === 'DataError' || e.name === 'SyntaxError'
      || e.name === 'OperationError' || e instanceof TypeError)) return e;
  return domError('OperationError', e?.message ?? 'The operation failed for an operation-specific reason');
};

/** A key's public half as a CryptoKey, from host info. */
const asymmetricKey = (info, algorithm, extractable, usages) =>
  makeKey(info.type, extractable, algorithm, usages, { der: fromB64(info.der), info });

const algorithmOf = (name, info, extra) => {
  if (KEY_TYPES[name] === 'rsa') {
    const e = Number(info.details.publicExponent);
    const exponent = [];
    for (let v = e; v > 0; v = Math.floor(v / 256)) exponent.unshift(v % 256);
    return { name, modulusLength: info.details.modulusLength, publicExponent: new Uint8Array(exponent), hash: extra.hash };
  }
  if (KEY_TYPES[name] === 'ec') {
    const curve = Object.keys(CURVES).find((c) => CURVES[c] === info.details.namedCurve);
    if (extra.namedCurve && extra.namedCurve !== curve) throw domError('DataError', 'The key is on ' + curve + ', not ' + extra.namedCurve);
    return { name, namedCurve: curve };
  }
  return { name };
};

const checkKeyType = (name, info) => {
  if (KEY_TYPES[name] !== info.asymmetricKeyType) throw domError('DataError', 'Invalid key type for ' + name + ': ' + info.asymmetricKeyType);
};

// --- generateKey ---

subtle.generateKey = async (algorithm, extractable, usages) => {
  const alg = normalize(algorithm);
  const name = alg.name;
  if (name === 'HMAC' || name.startsWith('AES-')) {
    if (name === 'AES-KW') throw domError('NotSupportedError', 'AES-KW is not supported in the isolate');
    checkUsages(name, 'secret', usages);
    if (usages.length === 0) throw domError('SyntaxError', 'Usages cannot be empty when creating a key.');
    const length = name === 'HMAC' ? alg.length ?? defaultHmacLength(alg.hash) : alg.length;
    if (name !== 'HMAC' && ![128, 192, 256].includes(length)) throw domError('OperationError', 'AES key length must be 128, 192 or 256 bits');
    const bytes = new Uint8Array(length / 8);
    for (let at = 0; at < bytes.length; at += 65536) bytes.set(cryptoInternals.randomBytes(Math.min(65536, bytes.length - at)), at);
    return makeKey('secret', extractable, name === 'HMAC' ? { name, hash: alg.hash, length } : { name, length }, usages, { secret: bytes });
  }
  const type = KEY_TYPES[name];
  if (!type) throw domError('NotSupportedError', 'Unrecognized algorithm name');
  const options = {};
  if (type === 'rsa') {
    options.modulusLength = alg.modulusLength;
    let e = 0;
    for (const b of bytesIn(alg.publicExponent ?? new Uint8Array([1, 0, 1]))) e = e * 256 + b;
    options.publicExponent = e;
  }
  if (type === 'ec') {
    if (!CURVES[alg.namedCurve]) throw domError('NotSupportedError', 'Unsupported namedCurve: ' + alg.namedCurve);
    options.namedCurve = CURVES[alg.namedCurve];
  }
  const privateUsages = usages.filter((u) => USAGES[name].private.includes(u));
  const publicUsages = usages.filter((u) => USAGES[name].public.includes(u));
  if (privateUsages.length + publicUsages.length !== usages.length) throw domError('SyntaxError', 'Unsupported key usage for a ' + name + ' key');
  if (privateUsages.length === 0) throw domError('SyntaxError', 'Usages cannot be empty when creating a key.');
  let pair;
  try {
    pair = await cryptoHost.generateKeyPair(type, options, true);
  } catch (e) {
    throw operationError(e);
  }
  const algorithmOut = algorithmOf(name, pair.publicKey, { hash: alg.hash, namedCurve: alg.namedCurve });
  return {
    publicKey: asymmetricKey(pair.publicKey, algorithmOut, true, publicUsages),
    privateKey: asymmetricKey(pair.privateKey, algorithmOut, extractable, privateUsages),
  };
};

// --- importKey and exportKey ---

subtle.importKey = async (format, keyData, algorithm, extractable, usages) => {
  const alg = normalize(algorithm);
  const name = alg.name;
  if (name === 'AES-KW') throw domError('NotSupportedError', 'AES-KW is not supported in the isolate');
  if (name === 'HMAC' || name.startsWith('AES-') || name === 'PBKDF2' || name === 'HKDF') {
    let secret;
    if (format === 'raw') {
      secret = bytesIn(keyData);
    } else if (format === 'jwk') {
      if (keyData?.kty !== 'oct') throw domError('DataError', 'Invalid JWK: kty must be "oct"');
      secret = fromB64(keyData.k);
    } else {
      throw domError('NotSupportedError', 'Unsupported key format for ' + name + ': ' + format);
    }
    checkUsages(name, 'secret', usages);
    if ((name === 'PBKDF2' || name === 'HKDF') && extractable) throw domError('SyntaxError', name + ' keys are not extractable');
    if (name.startsWith('AES-') && ![16, 24, 32].includes(secret.length)) throw domError('DataError', 'Invalid AES key length');
    const algorithmOut = name === 'HMAC' ? { name, hash: alg.hash, length: alg.length ?? secret.length * 8 }
      : name.startsWith('AES-') ? { name, length: secret.length * 8 } : { name };
    return makeKey('secret', extractable, algorithmOut, usages, { secret });
  }
  if (!KEY_TYPES[name]) throw domError('NotSupportedError', 'Unrecognized algorithm name');
  let info;
  try {
    if (format === 'spki' || format === 'pkcs8') {
      info = cryptoInternals.readKey(format === 'pkcs8', bytesIn(keyData), 'der', format);
    } else if (format === 'jwk') {
      const jwk = { ...keyData };
      for (const field of ['alg', 'ext', 'key_ops', 'use']) delete jwk[field];
      info = cryptoInternals.readKey(keyData.d !== undefined, new TextEncoder().encode(JSON.stringify(jwk)), 'jwk', null);
    } else if (format === 'raw') {
      const raw = bytesIn(keyData);
      let jwk;
      if (KEY_TYPES[name] === 'ec') {
        if (!CURVES[alg.namedCurve]) throw domError('NotSupportedError', 'Unsupported namedCurve: ' + alg.namedCurve);
        const point = cryptoInternals.ecdhPoint(CURVES[alg.namedCurve], raw);
        const size = (point.length - 1) / 2;
        jwk = { kty: 'EC', crv: alg.namedCurve, x: toB64url(point.subarray(1, 1 + size)), y: toB64url(point.subarray(1 + size)) };
      } else if (KEY_TYPES[name] === 'ed25519' || KEY_TYPES[name] === 'x25519') {
        jwk = { kty: 'OKP', crv: name, x: toB64url(raw) };
      } else {
        throw domError('NotSupportedError', 'Unsupported key format for ' + name + ': raw');
      }
      info = cryptoInternals.readKey(false, new TextEncoder().encode(JSON.stringify(jwk)), 'jwk', null);
    } else {
      throw domError('NotSupportedError', 'Unsupported key format: ' + format);
    }
  } catch (e) {
    throw e?.name === 'NotSupportedError' ? e : domError('DataError', e?.message ?? 'Invalid key data');
  }
  checkKeyType(name, info);
  checkUsages(name, info.type, usages);
  return asymmetricKey(info, algorithmOf(name, info, { hash: alg.hash, namedCurve: alg.namedCurve }), extractable, usages);
};

const JWK_ALG = {
  HMAC: (a) => 'HS' + a.hash.name.slice(4),
  'AES-GCM': (a) => 'A' + a.length + 'GCM',
  'AES-CBC': (a) => 'A' + a.length + 'CBC',
  'AES-CTR': (a) => 'A' + a.length + 'CTR',
  'RSASSA-PKCS1-v1_5': (a) => 'RS' + a.hash.name.slice(4),
  'RSA-PSS': (a) => 'PS' + a.hash.name.slice(4),
  'RSA-OAEP': (a) => (a.hash.name === 'SHA-1' ? 'RSA-OAEP' : 'RSA-OAEP-' + a.hash.name.slice(4)),
};

subtle.exportKey = async (format, key) => {
  if (!(key instanceof CryptoKey)) throw new TypeError('The key must be a CryptoKey');
  // As the spec orders it: an algorithm whose keys can't be exported says so before extractability is looked at.
  if (key.algorithm.name === 'PBKDF2' || key.algorithm.name === 'HKDF') throw domError('NotSupportedError', key.algorithm.name + ' keys cannot be exported');
  if (!key.extractable) throw domError('InvalidAccessError', 'key is not extractable');
  const material = key[KEY_MATERIAL];
  if (key.type === 'secret') {
    if (format === 'raw') return arrayBuffer(material.secret.slice());
    if (format === 'jwk') {
      const jwk = { key_ops: [...key.usages], ext: key.extractable, kty: 'oct', k: toB64url(material.secret) };
      if (JWK_ALG[key.algorithm.name]) jwk.alg = JWK_ALG[key.algorithm.name](key.algorithm);
      return jwk;
    }
    throw domError('NotSupportedError', 'Unsupported export format for a secret key: ' + format);
  }
  const isPrivate = key.type === 'private';
  if (format === 'pkcs8' || format === 'spki') {
    if ((format === 'pkcs8') !== isPrivate) throw domError('InvalidAccessError', 'Unable to export a ' + key.type + ' key as ' + format);
    return arrayBuffer(material.der.slice());
  }
  if (format === 'jwk') {
    const jwk = cryptoInternals.exportKey(isPrivate, material.der, 'jwk', null);
    jwk.key_ops = [...key.usages];
    jwk.ext = key.extractable;
    if (JWK_ALG[key.algorithm.name]) jwk.alg = JWK_ALG[key.algorithm.name](key.algorithm);
    if (key.algorithm.name === 'Ed25519') jwk.alg = 'Ed25519';
    return jwk;
  }
  if (format === 'raw' && !isPrivate) {
    const jwk = cryptoInternals.exportKey(false, material.der, 'jwk', null);
    if (jwk.kty === 'EC') return arrayBuffer(new Uint8Array([4, ...fromB64(jwk.x), ...fromB64(jwk.y)]));
    if (jwk.kty === 'OKP') return arrayBuffer(fromB64(jwk.x));
  }
  throw domError('NotSupportedError', 'Unsupported export format for a ' + key.type + ' ' + key.algorithm.name + ' key: ' + format);
};

// --- sign and verify ---

const hmac = (hash, secret, data) => {
  const handle = cryptoInternals.open(HASHES[hash.name], secret);
  cryptoInternals.update(handle, data);
  return cryptoInternals.finish(handle);
};

const signOptions = (alg, key) => {
  switch (alg.name) {
    case 'RSASSA-PKCS1-v1_5':
      return [HASHES[key.algorithm.hash.name], {}];
    case 'RSA-PSS':
      return [HASHES[key.algorithm.hash.name], { padding: 6, saltLength: alg.saltLength ?? 0 }];
    case 'ECDSA':
      return [HASHES[hashOf(alg.hash).name], { dsaEncoding: 'ieee-p1363' }];
    case 'Ed25519':
      return [null, {}];
    default:
      throw domError('NotSupportedError', 'Unrecognized algorithm name');
  }
};

subtle.sign = async (algorithm, key, data) => {
  const alg = normalize(algorithm);
  const material = use(key, alg, 'sign');
  const bytes = bytesIn(data);
  try {
    if (alg.name === 'HMAC') return arrayBuffer(hmac(key.algorithm.hash, material.secret, bytes));
    const [digest, options] = signOptions(alg, key);
    return arrayBuffer(cryptoInternals.sign(digest, bytes, material.der, options));
  } catch (e) {
    throw operationError(e);
  }
};

subtle.verify = async (algorithm, key, signature, data) => {
  const alg = normalize(algorithm);
  const material = use(key, alg, 'verify');
  const bytes = bytesIn(data);
  const signed = bytesIn(signature);
  try {
    if (alg.name === 'HMAC') {
      const expected = hmac(key.algorithm.hash, material.secret, bytes);
      return expected.length === signed.length && cryptoInternals.timingSafeEqual(expected, signed);
    }
    const [digest, options] = signOptions(alg, key);
    return cryptoInternals.verify(digest, bytes, material.der, false, signed, options);
  } catch (e) {
    throw operationError(e);
  }
};

// --- encrypt and decrypt ---

function aes(alg, key, data, encrypt) {
  const secret = key[KEY_MATERIAL].secret;
  const bits = secret.length * 8;
  if (alg.name === 'AES-GCM') {
    const tagLength = (alg.tagLength ?? 128) / 8;
    const iv = bytesIn(alg.iv);
    const handle = cryptoInternals.cipher('aes-' + bits + '-gcm', encrypt, secret, iv, tagLength);
    if (alg.additionalData !== undefined) cryptoInternals.cipherAad(handle, bytesIn(alg.additionalData));
    if (encrypt) {
      const body = cryptoInternals.cipherUpdate(handle, data);
      const rest = cryptoInternals.cipherFinal(handle, null);
      const out = new Uint8Array(body.length + rest.length);
      out.set(body);
      out.set(rest, body.length);
      return out;
    }
    if (data.length < tagLength) throw domError('OperationError', 'The provided data is too small.');
    cryptoInternals.cipherUpdate(handle, data.subarray(0, data.length - tagLength));
    return cryptoInternals.cipherFinal(handle, data.slice(data.length - tagLength));
  }
  let iv;
  if (alg.name === 'AES-CBC') {
    iv = bytesIn(alg.iv);
  } else {
    iv = bytesIn(alg.counter);
    const length = alg.length;
    if (!(length >= 1 && length <= 128)) throw domError('OperationError', 'AES-CTR length must be from 1 to 128');
    if (length < 128) {
      // The JVM counts with all 128 bits; Web Crypto wraps within the last `length`. They agree unless the counter would wrap.
      let counter = 0n;
      for (const b of iv) counter = (counter << 8n) | BigInt(b);
      const low = counter & ((1n << BigInt(length)) - 1n);
      if (low + BigInt(Math.ceil(data.length / 16)) > (1n << BigInt(length))) {
        throw domError('NotSupportedError', 'An AES-CTR counter that wraps within its length is not supported in the isolate');
      }
    }
  }
  const handle = cryptoInternals.cipher('aes-' + bits + (alg.name === 'AES-CBC' ? '-cbc' : '-ctr'), encrypt, secret, iv, null);
  const body = cryptoInternals.cipherUpdate(handle, data);
  const rest = cryptoInternals.cipherFinal(handle, null);
  const out = new Uint8Array(body.length + rest.length);
  out.set(body);
  out.set(rest, body.length);
  return out;
}

function crypt(alg, key, data, encrypt) {
  if (alg.name === 'RSA-OAEP') {
    const options = { padding: 4, oaepHash: HASHES[key.algorithm.hash.name] };
    if (alg.label !== undefined) options.oaepLabel = b64(bytesIn(alg.label));
    return cryptoInternals.rsa(encrypt ? 'publicEncrypt' : 'privateDecrypt', key[KEY_MATERIAL].der, key.type === 'private', data, options);
  }
  if (alg.name === 'AES-GCM' || alg.name === 'AES-CBC' || alg.name === 'AES-CTR') return aes(alg, key, data, encrypt);
  throw domError('NotSupportedError', 'Unrecognized algorithm name');
}

subtle.encrypt = async (algorithm, key, data) => {
  const alg = normalize(algorithm);
  use(key, alg, 'encrypt');
  try {
    return arrayBuffer(crypt(alg, key, bytesIn(data), true));
  } catch (e) {
    throw operationError(e);
  }
};

subtle.decrypt = async (algorithm, key, data) => {
  const alg = normalize(algorithm);
  use(key, alg, 'decrypt');
  try {
    return arrayBuffer(crypt(alg, key, bytesIn(data), false));
  } catch (e) {
    throw operationError(e);
  }
};

// --- deriveBits and deriveKey ---

async function derive(alg, key, length, usage) {
  const material = use(key, alg, usage);
  try {
    switch (alg.name) {
      case 'PBKDF2': {
        if (length === null || length === undefined || length % 8 !== 0) throw domError('OperationError', 'PBKDF2 needs a length that is a multiple of 8');
        return await cryptoHost.pbkdf2(material.secret, bytesIn(alg.salt), alg.iterations, length / 8, HASHES[alg.hash.name], true);
      }
      case 'HKDF': {
        if (length === null || length === undefined || length % 8 !== 0) throw domError('OperationError', 'HKDF needs a length that is a multiple of 8');
        return cryptoInternals.hkdf(HASHES[alg.hash.name], material.secret, bytesIn(alg.salt), bytesIn(alg.info), length / 8);
      }
      case 'ECDH':
      case 'X25519': {
        const other = alg.public;
        if (!(other instanceof CryptoKey) || other.type !== 'public' || other.algorithm.name !== alg.name) {
          throw domError('InvalidAccessError', 'algorithm.public must be a ' + alg.name + ' public key');
        }
        const secret = cryptoInternals.agree(material.der, other[KEY_MATERIAL].der);
        if (length === null || length === undefined) return secret;
        if (length > secret.length * 8) throw domError('OperationError', 'The length is longer than the derived secret');
        const out = secret.slice(0, Math.ceil(length / 8));
        if (length % 8) out[out.length - 1] &= 0xff << (8 - (length % 8));
        return out;
      }
      default:
        throw domError('NotSupportedError', 'Unrecognized algorithm name');
    }
  } catch (e) {
    throw operationError(e);
  }
}

subtle.deriveBits = async (algorithm, baseKey, length) => arrayBuffer(await derive(normalize(algorithm), baseKey, length, 'deriveBits'));

subtle.deriveKey = async (algorithm, baseKey, derivedKeyAlgorithm, extractable, usages) => {
  const target = normalize(derivedKeyAlgorithm);
  let length;
  if (target.name === 'HMAC') length = target.length ?? defaultHmacLength(target.hash);
  else if (target.name.startsWith('AES-')) length = target.length;
  else if (target.name === 'HKDF' || target.name === 'PBKDF2') length = null;
  else throw domError('NotSupportedError', 'Cannot derive a ' + target.name + ' key');
  const bits = await derive(normalize(algorithm), baseKey, length, 'deriveKey');
  return subtle.importKey('raw', bits, derivedKeyAlgorithm, extractable, usages);
};

// --- wrapKey and unwrapKey ---

subtle.wrapKey = async (format, key, wrappingKey, wrapAlgorithm) => {
  const alg = normalize(wrapAlgorithm);
  if (alg.name === 'AES-KW') throw domError('NotSupportedError', 'AES-KW is not supported in the isolate');
  use(wrappingKey, alg, 'wrapKey');
  const exported = await subtle.exportKey(format, key);
  const bytes = format === 'jwk' ? new TextEncoder().encode(JSON.stringify(exported)) : new Uint8Array(exported);
  try {
    return arrayBuffer(crypt(alg, wrappingKey, bytes, true));
  } catch (e) {
    throw operationError(e);
  }
};

subtle.unwrapKey = async (format, wrappedKey, unwrappingKey, unwrapAlgorithm, unwrappedKeyAlgorithm, extractable, usages) => {
  const alg = normalize(unwrapAlgorithm);
  if (alg.name === 'AES-KW') throw domError('NotSupportedError', 'AES-KW is not supported in the isolate');
  use(unwrappingKey, alg, 'unwrapKey');
  let bytes;
  try {
    bytes = crypt(alg, unwrappingKey, bytesIn(wrappedKey), false);
  } catch (e) {
    throw operationError(e);
  }
  const keyData = format === 'jwk' ? JSON.parse(new TextDecoder().decode(bytes)) : bytes;
  return subtle.importKey(format, keyData, unwrappedKeyAlgorithm, extractable, usages);
};

Object.freeze(subtle);
define('CryptoKey', CryptoKey);
