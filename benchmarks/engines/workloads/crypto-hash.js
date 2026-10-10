// Host-heavy: node:crypto's SHA-256 over 64 KB chunks (the bytes cross into the host's JVM crypto).
import crypto from 'node:crypto';
import { Buffer } from 'node:buffer';
const chunk = Buffer.alloc(64 * 1024, 7);
function iteration() {
  let digest = '';
  for (let k = 0; k < 300; k++) digest = crypto.createHash('sha256').update(chunk).update(digest).digest('hex');
  return digest;
}
