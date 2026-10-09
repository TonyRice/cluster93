import jwt from 'jsonwebtoken';
import { rsaPem, ecPem } from './testkeys.js';
import { createPublicKey } from 'node:crypto';
export const run = () => {
  const token = jwt.sign({ a: 1 }, 'secret', { noTimestamp: true });
  let tampered;
  try { jwt.verify(token.slice(0, -2) + 'xx', 'secret'); } catch (e) { tampered = e.message; }
  // RS256 is deterministic, so the token must equal Node's; ES256 and PS256 aren't, so they must verify. jsonwebtoken
  // allows PS256 only on Node versions it knows support it: it asks semver about process.version (D-115).
  const rs256 = jwt.sign({ sub: 'u1' }, rsaPem, { algorithm: 'RS256', noTimestamp: true });
  const ecPublic = createPublicKey(ecPem).export({ type: 'spki', format: 'pem' });
  const es256 = jwt.sign({ sub: 'u2' }, ecPem, { algorithm: 'ES256', noTimestamp: true });
  const ps256 = jwt.sign({ sub: 'u3' }, rsaPem, { algorithm: 'PS256', noTimestamp: true });
  return {
    token, verified: jwt.verify(token, 'secret'), tampered,
    rs256, rs256Verified: jwt.verify(rs256, createPublicKey(rsaPem)),
    es256Verified: jwt.verify(es256, ecPublic), es256Header: es256.split('.')[0],
    ps256Verified: jwt.verify(ps256, createPublicKey(rsaPem), { algorithms: ['PS256'] }), ps256Header: ps256.split('.')[0],
  };
};
