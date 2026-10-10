// Our polyfills: Buffer's base64 and hex encoding and decoding of 1 MB (node:buffer).
import { Buffer } from 'node:buffer';
let bytes;
function setup() {
  bytes = Buffer.alloc(1024 * 1024);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 13) & 255;
}
function iteration() {
  const b64 = bytes.toString('base64');
  const hex = bytes.toString('hex');
  return Buffer.from(b64, 'base64').length + Buffer.from(hex, 'hex').length;
}
