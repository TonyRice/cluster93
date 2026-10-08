import {
  pbkdf2, pbkdf2Sync, scryptSync, scrypt, hkdfSync, createCipheriv, createDecipheriv, createPrivateKey, createPublicKey, createSign,
  createVerify, sign, verify, generateKeyPairSync, generateKeyPair, constants, getCiphers, getCurves, createSecretKey,
} from 'node:crypto';
import { Buffer } from 'node:buffer';
import { rsaPem, ecPem, edPem } from './testkeys.js';

const key32 = Buffer.alloc(32, 7);
const iv12 = Buffer.alloc(12, 9);
const iv16 = Buffer.alloc(16, 3);
const text = 'Grüße, ünïcödé and some more text that spans blocks';

// Fed in odd-sized pieces with string output, so encodings must carry partial groups and characters across calls.
function encrypt(algorithm, iv, outEnc, options, aad) {
  const cipher = createCipheriv(algorithm, key32.subarray(0, algorithm.includes('128') ? 16 : 32), iv, options);
  if (aad) cipher.setAAD(aad);
  let out = '';
  for (let i = 0; i < text.length; i += 7) out += cipher.update(text.slice(i, i + 7), 'utf8', outEnc);
  out += cipher.final(outEnc);
  return { out, tag: aad !== undefined || /gcm|poly/.test(algorithm) ? cipher.getAuthTag().toString('hex') : undefined };
}

function decrypt(algorithm, iv, inEnc, sealed, options, aad) {
  const decipher = createDecipheriv(algorithm, key32.subarray(0, algorithm.includes('128') ? 16 : 32), iv, options);
  if (aad) decipher.setAAD(aad);
  if (sealed.tag) decipher.setAuthTag(Buffer.from(sealed.tag, 'hex'));
  let out = '';
  const bytes = Buffer.from(sealed.out, inEnc);
  for (let i = 0; i < bytes.length; i += 5) out += decipher.update(bytes.subarray(i, i + 5), undefined, 'utf8');
  return out + decipher.final('utf8');
}

const details = (k) => JSON.parse(JSON.stringify(k.asymmetricKeyDetails, (n, v) => (typeof v === 'bigint' ? String(v) : v)));

export const run = async () => {
  const gcm = encrypt('aes-256-gcm', iv12, 'base64', undefined, Buffer.from('header'));
  let tampered;
  try {
    const d = createDecipheriv('aes-256-gcm', key32, iv12);
    d.setAuthTag(Buffer.from(gcm.tag, 'hex'));
    d.update(Buffer.from(gcm.out, 'base64').map((b, i) => (i === 0 ? b ^ 1 : b)));
    d.final();
  } catch (e) {
    tampered = e.message;
  }
  const cbc = encrypt('aes-128-cbc', iv16, 'hex');
  const ctr = encrypt('aes-256-ctr', iv16, 'base64url');
  const chacha = encrypt('chacha20-poly1305', iv12, 'hex', { authTagLength: 16 }, Buffer.from('aad'));

  const rsa = createPrivateKey(rsaPem);
  const rsaPublic = createPublicKey(rsaPem);
  const ec = createPrivateKey({ key: ecPem, format: 'pem' });
  const ecPublic = createPublicKey(ec);
  const ed = createPrivateKey(edPem);
  const data = Buffer.from('signed data');

  const ecDer = sign('sha256', data, ec);
  const ecRaw = sign('sha256', data, { key: ec, dsaEncoding: 'ieee-p1363' });
  const pss = { key: rsa, padding: constants.RSA_PKCS1_PSS_PADDING, saltLength: constants.RSA_PSS_SALTLEN_DIGEST };
  const pssSignature = sign('sha256', data, pss);
  const signer = createSign('RSA-SHA256');
  signer.update('signed ');
  signer.write('data');
  signer.end();
  const streamed = signer.sign(rsaPem, 'base64');
  const verifier = createVerify('RSA-SHA256');
  verifier.update(data);

  const generated = generateKeyPairSync('ec', { namedCurve: 'secp384r1', publicKeyEncoding: { type: 'spki', format: 'pem' },
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' } });
  const asyncPair = await new Promise((resolve, reject) => generateKeyPair('ed25519', (err, publicKey, privateKey) =>
    (err ? reject(err) : resolve([publicKey.type, privateKey.type, privateKey.asymmetricKeyType]))));

  return {
    pbkdf2: pbkdf2Sync('secret', 'salt', 1000, 48, 'sha512').toString('hex'),
    pbkdf2Async: await new Promise((resolve, reject) => pbkdf2('secret', 'salt', 10, 20, 'sha1', (e, k) => (e ? reject(e) : resolve(k.toString('base64'))))),
    scrypt: scryptSync('secret', 'salt', 32, { N: 1024 }).toString('hex'),
    scryptAsync: await new Promise((resolve, reject) => scrypt('secret', 'salt', 16, (e, k) => (e ? reject(e) : resolve(k.toString('hex'))))),
    hkdf: Buffer.from(hkdfSync('sha256', createSecretKey(Buffer.from('ikm')), 'salt', 'info', 42)).toString('hex'),
    gcm, gcmBack: decrypt('aes-256-gcm', iv12, 'base64', gcm, undefined, Buffer.from('header')), tampered,
    cbc, cbcBack: decrypt('aes-128-cbc', iv16, 'hex', cbc), ctr, ctrBack: decrypt('aes-256-ctr', iv16, 'base64url', ctr),
    chacha, chachaBack: decrypt('chacha20-poly1305', iv12, 'hex', chacha, { authTagLength: 16 }, Buffer.from('aad')),
    rsa: {
      type: [rsa.type, rsa.asymmetricKeyType, details(rsa)],
      signature: sign('sha256', data, rsa).toString('base64'),
      streamed,
      verified: verify('sha256', data, rsaPublic, Buffer.from(streamed, 'base64')),
      verifier: verifier.verify(rsaPublic, streamed, 'base64'),
      pss: verify('sha256', data, { key: rsaPublic, padding: constants.RSA_PKCS1_PSS_PADDING }, pssSignature),
      wrong: verify('sha256', Buffer.from('other'), rsaPublic, Buffer.from(streamed, 'base64')),
      pkcs8: rsa.export({ type: 'pkcs8', format: 'pem' }) === rsaPem,
      pkcs1: rsa.export({ type: 'pkcs1', format: 'pem' }),
      spki: rsaPublic.export({ type: 'spki', format: 'pem' }),
      publicPkcs1: rsaPublic.export({ type: 'pkcs1', format: 'der' }).toString('base64'),
      jwk: rsa.export({ format: 'jwk' }),
      fromJwk: createPrivateKey({ key: rsa.export({ format: 'jwk' }), format: 'jwk' }).equals(rsa),
    },
    ec: {
      type: [ec.type, ec.asymmetricKeyType, details(ec)],
      verified: [verify('sha256', data, ecPublic, ecDer), verify('sha256', data, { key: ecPublic, dsaEncoding: 'ieee-p1363' }, ecRaw), ecRaw.length],
      pkcs8: ec.export({ type: 'pkcs8', format: 'pem' }) === ecPem,
      sec1: ec.export({ type: 'sec1', format: 'pem' }),
      spki: ecPublic.export({ type: 'spki', format: 'pem' }),
      jwk: ec.export({ format: 'jwk' }),
      fromSec1: createPrivateKey(ec.export({ type: 'sec1', format: 'pem' })).equals(ec),
      generated: [generated.publicKey.split('\n')[0], generated.privateKey.split('\n')[0],
        verify('sha384', data, generated.publicKey, sign('sha384', data, generated.privateKey))],
    },
    ed: {
      type: [ed.type, ed.asymmetricKeyType, details(ed)],
      signature: sign(null, data, ed).toString('hex'),
      public: createPublicKey(ed).export({ type: 'spki', format: 'pem' }),
      jwk: ed.export({ format: 'jwk' }),
      pkcs8: ed.export({ type: 'pkcs8', format: 'pem' }) === edPem,
    },
    asyncPair,
    lists: [getCiphers().includes('aes-256-gcm'), getCurves().includes('prime256v1')],
  };
};
