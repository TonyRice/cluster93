import { createHash, createHmac, hash, timingSafeEqual, randomBytes, randomUUID } from 'node:crypto';
import { Buffer } from 'node:buffer';
export const run = async () => ({
  sha256: createHash('sha256').update('hello').update(Buffer.from(' world')).digest('hex'),
  sha512: createHash('sha512').update('x', 'utf8').digest('base64'),
  md5: hash('md5', 'abc'),
  sha3: createHash('sha3-256').update('').digest('hex'),
  hmac: createHmac('sha1', Buffer.from('key')).update('The quick brown fox jumps over the lazy dog').digest('hex'),
  hmac512: createHmac('sha512', 'k').update(new Uint8Array([1, 2, 3])).digest('base64url'),
  equal: timingSafeEqual(Buffer.from('abc'), Buffer.from('abc')),
  random: randomBytes(32).length,
  uuid: /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab]/.test(randomUUID()),
  subtle: Buffer.from(await crypto.subtle.digest('SHA-384', new TextEncoder().encode('abc'))).toString('hex'),
});
