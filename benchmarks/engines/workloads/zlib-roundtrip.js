// Host-heavy: node:zlib gzip and gunzip of 5 MB (the host's JVM zlib).
import zlib from 'node:zlib';
import { Buffer } from 'node:buffer';
let input;
function setup() {
  input = Buffer.from('the quick brown fox jumps over the lazy dog 0123456789 '.repeat(100000));
}
function iteration() {
  const packed = zlib.gzipSync(input);
  return zlib.gunzipSync(packed).length + packed.length;
}
