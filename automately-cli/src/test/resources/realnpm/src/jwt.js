import jwt from 'jsonwebtoken';
import { rsaPem, ecPem } from './testkeys.js';
import { createPublicKey } from 'node:crypto';
export const run = () => {
  const token = jwt.sign({ a: 1 }, 'secret', { noTimestamp: true });
  let tampered;
  try { jwt.verify(token.slice(0, -2) + 'xx', 'secret'); } catch (e) { tampered = e.message; }
  // RS256 is deterministic, so the token must equal Node's; ES256 isn't, so it must verify. jsonwebtoken turns PS256 off
  // here: it asks semver about process.version, which the isolate leaves empty (D-093).
  const rs256 = jwt.sign({ sub: 'u1' }, rsaPem, { algorithm: 'RS256', noTimestamp: true });
  const ecPublic = createPublicKey(ecPem).export({ type: 'spki', format: 'pem' });
  const es256 = jwt.sign({ sub: 'u2' }, ecPem, { algorithm: 'ES256', noTimestamp: true });
  return {
    token, verified: jwt.verify(token, 'secret'), tampered,
    rs256, rs256Verified: jwt.verify(rs256, createPublicKey(rsaPem)),
    es256Verified: jwt.verify(es256, ecPublic), es256Header: es256.split('.')[0],
  };
};
