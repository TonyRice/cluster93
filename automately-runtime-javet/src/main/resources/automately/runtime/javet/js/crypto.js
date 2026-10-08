// crypto: Web Crypto as a global (D-095), on CryptoBinding: the JVM's SecureRandom and digests, nothing that reaches out.
// node:crypto (js/node/crypto.js) is built on the same host calls, which it finds under a symbol on this object.
const cryptoHost = host.crypto;
const cryptoCall = (result) => {
  if (result instanceof Error) throw result;
  return result;
};
// Web Crypto's errors are DOMExceptions (js/globals.js, D-106), as in Node.
const domError = (name, message) => new DOMException(message, name);
// By instanceof, not by name: Node's Buffer (a Uint8Array subclass) is accepted, as in Node.
const INTEGER_ARRAYS = [Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, BigInt64Array, BigUint64Array];
const viewBytes = (data) => {
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  throw new TypeError('The data must be an ArrayBuffer or an ArrayBufferView');
};
const SUBTLE_DIGESTS = { 'SHA-1': 'sha1', 'SHA-256': 'sha256', 'SHA-384': 'sha384', 'SHA-512': 'sha512' };
const cryptoInternals = Object.freeze({
  randomBytes: (n) => cryptoCall(cryptoHost.randomBytes(n)),
  randomUUID: () => cryptoCall(cryptoHost.randomUUID()),
  digest: (algorithm, bytes) => cryptoCall(cryptoHost.digest(algorithm, bytes)),
  open: (algorithm, key) => cryptoCall(cryptoHost.open(algorithm, key ?? null)),
  update: (handle, bytes) => cryptoCall(cryptoHost.update(handle, bytes)),
  finish: (handle) => cryptoCall(cryptoHost.finish(handle)),
  copy: (handle) => cryptoCall(cryptoHost.copy(handle)),
  timingSafeEqual: (a, b) => cryptoCall(cryptoHost.timingSafeEqual(a, b)),
  hashes: () => cryptoCall(cryptoHost.hashes()),
  // D-101: key derivation, ciphers, keys and signatures. The *Async ones answer promises from the node's crypto pool.
  pbkdf2: (password, salt, iterations, length, digest) => cryptoCall(cryptoHost.pbkdf2(password, salt, iterations, length, digest, false)),
  pbkdf2Async: (password, salt, iterations, length, digest) => cryptoHost.pbkdf2(password, salt, iterations, length, digest, true),
  hkdf: (digest, key, salt, info, length) => cryptoCall(cryptoHost.hkdf(digest, key, salt, info, length)),
  scrypt: (password, salt, length, options) => cryptoCall(cryptoHost.scrypt(password, salt, length, options, false)),
  scryptAsync: (password, salt, length, options) => cryptoHost.scrypt(password, salt, length, options, true),
  cipher: (algorithm, encrypt, key, iv, tagLength) => cryptoCall(cryptoHost.cipher(algorithm, encrypt, key, iv, tagLength)),
  cipherPadding: (handle, on) => cryptoCall(cryptoHost.cipherPadding(handle, on)),
  cipherAad: (handle, aad) => cryptoCall(cryptoHost.cipherAad(handle, aad)),
  cipherUpdate: (handle, data) => cryptoCall(cryptoHost.cipherUpdate(handle, data)),
  cipherFinal: (handle, tag) => cryptoCall(cryptoHost.cipherFinal(handle, tag ?? null)),
  ciphers: () => cryptoCall(cryptoHost.ciphers()),
  readKey: (wantPrivate, data, format, type) => cryptoCall(cryptoHost.readKey(wantPrivate, data, format, type ?? null)),
  publicKeyOf: (der) => cryptoCall(cryptoHost.publicKeyOf(der)),
  exportKey: (isPrivate, der, format, type) => cryptoCall(cryptoHost.exportKey(isPrivate, der, format, type ?? null)),
  generateKeyPair: (type, options) => cryptoCall(cryptoHost.generateKeyPair(type, options, false)),
  generateKeyPairAsync: (type, options) => cryptoHost.generateKeyPair(type, options, true),
  sign: (digest, data, der, options) => cryptoCall(cryptoHost.sign(digest ?? null, data, der, options)),
  verify: (digest, data, der, isPrivate, signature, options) => cryptoCall(cryptoHost.verify(digest ?? null, data, der, isPrivate, signature, options)),
  curves: () => cryptoCall(cryptoHost.curves()),
  // D-104: RSA encryption and key agreement.
  rsa: (operation, der, isPrivate, data, options) => cryptoCall(cryptoHost.rsa(operation, der, isPrivate, data, options)),
  agree: (pkcs8, spki) => cryptoCall(cryptoHost.agree(pkcs8, spki)),
  ecdhKeys: (curve, privateValue) => cryptoCall(cryptoHost.ecdhKeys(curve, privateValue ?? null)),
  ecdhPoint: (curve, encoded) => cryptoCall(cryptoHost.ecdhPoint(curve, encoded)),
  ecdhCompute: (curve, privateValue, otherPublic) => cryptoCall(cryptoHost.ecdhCompute(curve, privateValue, otherPublic)),
});
// subtle.js, next in the prelude, adds the rest of SubtleCrypto (D-104) and freezes it.
const subtle = {
  digest: async (algorithm, data) => {
    const name = (typeof algorithm === 'string' ? algorithm : algorithm?.name ?? '').toUpperCase();
    const node = SUBTLE_DIGESTS[name];
    if (!node) throw domError('NotSupportedError', 'Unrecognized algorithm name: ' + name);
    const bytes = cryptoInternals.digest(node, viewBytes(data).slice());
    return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  },
};
const webCrypto = {
  getRandomValues(array) {
    if (!INTEGER_ARRAYS.some((type) => array instanceof type)) {
      throw domError('TypeMismatchError', 'The data argument must be an integer-type TypedArray');
    }
    if (array.byteLength > 65536) {
      throw domError('QuotaExceededError', "The ArrayBufferView's byte length (" + array.byteLength + ') exceeds the number of bytes of entropy available via this API (65536)');
    }
    new Uint8Array(array.buffer, array.byteOffset, array.byteLength).set(cryptoInternals.randomBytes(array.byteLength));
    return array;
  },
  randomUUID: () => cryptoInternals.randomUUID(),
  subtle,
};
Object.defineProperty(webCrypto, Symbol.for('automately.crypto'), { value: cryptoInternals });
define('crypto', Object.freeze(webCrypto));
