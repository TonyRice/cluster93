// Web Crypto's subtle, and node:crypto's RSA encryption and key agreement (D-104), compared with real Node. Randomised
// operations (ECDSA, RSA-PSS, RSA-OAEP, generated keys) are checked by round trip; everything else must be equal.
import { webcrypto, createPrivateKey, createPublicKey, publicEncrypt, privateDecrypt, privateEncrypt, publicDecrypt, createECDH, ECDH,
  diffieHellman, generateKeyPairSync, constants } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { rsaPem, ecPem, edPem, x25519Pem } from './testkeys.js';

const { subtle } = webcrypto;
const hex = (buffer) => Buffer.from(buffer).toString('hex');
const pkcs8 = (pem) => createPrivateKey(pem).export({ type: 'pkcs8', format: 'der' });
const sorted = (object) => Object.fromEntries(Object.entries(object).sort(([a], [b]) => a.localeCompare(b)));
const failure = async (promise) => promise.then(() => 'no error', (e) => e.name);
const text = new TextEncoder().encode('Web Crypto in the isolate');

async function subtleCases() {
  const out = {};
  const hmacKey = await subtle.importKey('raw', Buffer.alloc(32, 1), { name: 'HMAC', hash: 'SHA-256' }, true, ['sign', 'verify']);
  const mac = await subtle.sign('HMAC', hmacKey, text);
  out.hmac = [hex(mac), await subtle.verify('HMAC', hmacKey, mac, text), await subtle.verify('HMAC', hmacKey, mac, Buffer.from('other')),
    sorted(await subtle.exportKey('jwk', hmacKey)), hmacKey.algorithm.length];

  const rsa = await subtle.importKey('pkcs8', pkcs8(rsaPem), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['sign']);
  const rsaPublic = await subtle.importKey('spki', createPublicKey(rsaPem).export({ type: 'spki', format: 'der' }),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, true, ['verify']);
  const rsaSignature = await subtle.sign('RSASSA-PKCS1-v1_5', rsa, text);
  out.rsa = [hex(rsaSignature).slice(0, 64), await subtle.verify('RSASSA-PKCS1-v1_5', rsaPublic, rsaSignature, text),
    rsa.algorithm.modulusLength, hex(rsa.algorithm.publicExponent), Object.keys(sorted(await subtle.exportKey('jwk', rsa))).join(),
    (await subtle.exportKey('jwk', rsa)).alg];
  const pss = await subtle.importKey('pkcs8', pkcs8(rsaPem), { name: 'RSA-PSS', hash: 'SHA-256' }, false, ['sign']);
  const pssPublic = await subtle.importKey('spki', createPublicKey(rsaPem).export({ type: 'spki', format: 'der' }), { name: 'RSA-PSS', hash: 'SHA-256' }, false, ['verify']);
  out.pss = await subtle.verify({ name: 'RSA-PSS', saltLength: 32 }, pssPublic, await subtle.sign({ name: 'RSA-PSS', saltLength: 32 }, pss, text), text);
  const oaep = await subtle.importKey('spki', createPublicKey(rsaPem).export({ type: 'spki', format: 'der' }), { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
  const oaepPrivate = await subtle.importKey('pkcs8', pkcs8(rsaPem), { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['decrypt']);
  out.oaep = new TextDecoder().decode(await subtle.decrypt({ name: 'RSA-OAEP', label: text }, oaepPrivate,
    await subtle.encrypt({ name: 'RSA-OAEP', label: text }, oaep, Buffer.from('secret message'))));

  const ec = await subtle.importKey('pkcs8', pkcs8(ecPem), { name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign']);
  const ecPublicJwk = await subtle.exportKey('jwk', ec);
  delete ecPublicJwk.d;
  const ecPublic = await subtle.importKey('jwk', { ...ecPublicJwk, key_ops: ['verify'] }, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
  const ecSignature = await subtle.sign({ name: 'ECDSA', hash: 'SHA-384' }, ec, text);
  out.ecdsa = [new Uint8Array(ecSignature).length, await subtle.verify({ name: 'ECDSA', hash: 'SHA-384' }, ecPublic, ecSignature, text),
    hex(await subtle.exportKey('raw', ecPublic)), ec.algorithm.namedCurve];
  const raw = await subtle.importKey('raw', await subtle.exportKey('raw', ecPublic), { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
  out.ecdsaRawImport = await subtle.verify({ name: 'ECDSA', hash: 'SHA-384' }, raw, ecSignature, text);

  const ecdh = await subtle.importKey('pkcs8', pkcs8(ecPem), { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits', 'deriveKey']);
  const ecdhPublic = await subtle.importKey('raw', await subtle.exportKey('raw', ecPublic), { name: 'ECDH', namedCurve: 'P-256' }, true, []);
  out.ecdh = [hex(await subtle.deriveBits({ name: 'ECDH', public: ecdhPublic }, ecdh, 256)),
    hex(await subtle.deriveBits({ name: 'ECDH', public: ecdhPublic }, ecdh, 100))];

  const x = await subtle.importKey('pkcs8', pkcs8(x25519Pem), { name: 'X25519' }, false, ['deriveBits']);
  const xPublic = await subtle.importKey('spki', createPublicKey(x25519Pem).export({ type: 'spki', format: 'der' }), { name: 'X25519' }, true, []);
  out.x25519 = [hex(await subtle.deriveBits({ name: 'X25519', public: xPublic }, x, 256)), hex(await subtle.exportKey('raw', xPublic))];

  const ed = await subtle.importKey('pkcs8', pkcs8(edPem), { name: 'Ed25519' }, true, ['sign']);
  const edJwk = await subtle.exportKey('jwk', ed);
  out.ed25519 = [hex(await subtle.sign('Ed25519', ed, text)), Object.keys(sorted(edJwk)).join(), edJwk.crv];

  const aesKey = await subtle.importKey('raw', Buffer.alloc(32, 7), 'AES-GCM', true, ['encrypt', 'decrypt', 'wrapKey', 'unwrapKey']);
  const iv = Buffer.alloc(12, 9);
  const sealed = await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: Buffer.from('aad'), tagLength: 96 }, aesKey, text);
  const tampered = new Uint8Array(sealed).slice();
  tampered[0] ^= 1;
  out.gcm = [hex(sealed), new TextDecoder().decode(await subtle.decrypt({ name: 'AES-GCM', iv, additionalData: Buffer.from('aad'), tagLength: 96 }, aesKey, sealed)),
    await failure(subtle.decrypt({ name: 'AES-GCM', iv, additionalData: Buffer.from('aad'), tagLength: 96 }, aesKey, tampered))];
  const cbcKey = await subtle.importKey('raw', Buffer.alloc(16, 3), 'AES-CBC', false, ['encrypt']);
  out.cbc = hex(await subtle.encrypt({ name: 'AES-CBC', iv: Buffer.alloc(16, 4) }, cbcKey, text));
  const ctrKey = await subtle.importKey('raw', Buffer.alloc(24, 5), 'AES-CTR', false, ['encrypt']);
  out.ctr = hex(await subtle.encrypt({ name: 'AES-CTR', counter: Buffer.alloc(16, 0), length: 64 }, ctrKey, text));

  const password = await subtle.importKey('raw', Buffer.from('password'), 'PBKDF2', false, ['deriveBits', 'deriveKey']);
  out.pbkdf2 = hex(await subtle.deriveBits({ name: 'PBKDF2', salt: Buffer.from('salt'), iterations: 1000, hash: 'SHA-256' }, password, 256));
  const derived = await subtle.deriveKey({ name: 'PBKDF2', salt: Buffer.from('salt'), iterations: 10, hash: 'SHA-1' }, password,
    { name: 'AES-CBC', length: 128 }, true, ['encrypt']);
  out.deriveKey = [hex(await subtle.exportKey('raw', derived)), derived.algorithm.length];
  const ikm = await subtle.importKey('raw', Buffer.from('input key'), 'HKDF', false, ['deriveBits']);
  out.hkdf = hex(await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-512', salt: Buffer.from('s'), info: Buffer.from('i') }, ikm, 160));

  const wrapped = await subtle.wrapKey('raw', hmacKey, aesKey, { name: 'AES-GCM', iv });
  const unwrapped = await subtle.unwrapKey('raw', wrapped, aesKey, { name: 'AES-GCM', iv }, { name: 'HMAC', hash: 'SHA-256' }, true, ['sign']);
  const wrappedJwk = await subtle.wrapKey('jwk', hmacKey, aesKey, { name: 'AES-GCM', iv });
  out.wrap = [hex(wrapped), hex(await subtle.exportKey('raw', unwrapped)), new Uint8Array(wrappedJwk).length > 40];

  const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-384' }, false, ['sign', 'verify']);
  out.generated = [pair.publicKey.usages, pair.privateKey.usages, pair.publicKey.extractable, pair.privateKey.extractable,
    await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pair.publicKey, await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, pair.privateKey, text), text)];
  const aes = await subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  out.generatedAes = [aes.algorithm, new Uint8Array(await subtle.exportKey('raw', aes)).length];
  out.errors = [
    await failure(subtle.sign('HMAC', await subtle.importKey('raw', Buffer.alloc(8), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']), text)),
    await failure(subtle.exportKey('raw', password)),
    await failure(subtle.importKey('raw', Buffer.alloc(5), 'AES-GCM', false, ['encrypt'])),
    await failure(subtle.importKey('raw', Buffer.alloc(16), 'AES-GCM', false, ['sign'])),
    await failure(subtle.digest('MD5', text)),
  ];
  out.cryptoKey = [aes instanceof CryptoKey, Object.prototype.toString.call(aes) !== undefined];
  return out;
}

function nodeCases() {
  const out = {};
  const publicKey = createPublicKey(rsaPem);
  out.oaep = privateDecrypt(rsaPem, publicEncrypt(publicKey, Buffer.from('to the private key'))).toString();
  out.oaep256 = privateDecrypt({ key: rsaPem, oaepHash: 'sha256' }, publicEncrypt({ key: publicKey, oaepHash: 'sha256' }, Buffer.from('sha-256'))).toString();
  const signed = privateEncrypt(rsaPem, Buffer.from('from the private key'));
  out.pkcs1 = [hex(signed).slice(0, 64), publicDecrypt(publicKey, signed).toString()];
  // privateDecrypt with PKCS#1 v1.5 padding isn't compared: the isolate refuses it on purpose (D-104).
  const alice = createECDH('prime256v1');
  alice.setPrivateKey(Buffer.alloc(32, 1));
  const bob = createECDH('prime256v1');
  bob.setPrivateKey(Buffer.alloc(32, 2));
  out.ecdh = [alice.getPublicKey('hex'), alice.getPublicKey('hex', 'compressed'), alice.getPublicKey('base64', 'hybrid'),
    alice.computeSecret(bob.getPublicKey()).toString('hex'), bob.computeSecret(alice.getPublicKey(null, 'compressed')).toString('hex'),
    ECDH.convertKey(alice.getPublicKey('hex', 'compressed'), 'prime256v1', 'hex', 'hex', 'uncompressed') === alice.getPublicKey('hex')];
  const fresh = createECDH('secp384r1');
  // A generated private key's length varies (leading zero bytes are dropped); one with a leading zero shows it every time.
  const leadingZero = createECDH('prime256v1');
  leadingZero.setPrivateKey(Buffer.concat([Buffer.alloc(1), Buffer.alloc(31, 7)]));
  out.ecdhGenerated = [fresh.generateKeys().length, fresh.getPrivateKey().length >= 46, leadingZero.getPrivateKey().length];
  try {
    alice.computeSecret(Buffer.from('04' + '00'.repeat(64), 'hex'));
    out.invalidPoint = 'accepted';
  } catch (e) {
    out.invalidPoint = e.code;
  }
  const other = generateKeyPairSync('x25519');
  const mine = createPrivateKey(x25519Pem);
  out.x25519 = [diffieHellman({ privateKey: mine, publicKey: createPublicKey(x25519Pem) }).toString('hex'),
    diffieHellman({ privateKey: mine, publicKey: other.publicKey }).equals(diffieHellman({ privateKey: other.privateKey, publicKey: createPublicKey(mine) })),
    mine.asymmetricKeyType, createPublicKey(mine).export({ format: 'jwk' }).crv];
  out.ecDh = diffieHellman({ privateKey: createPrivateKey(ecPem), publicKey: createPublicKey(ecPem) }).toString('hex');
  return out;
}

export const run = async () => ({ subtle: await subtleCases(), node: nodeCases() });
