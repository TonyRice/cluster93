// Our polyfills: Node's streams, 5,000 chunks of 1 KB through a Transform (node:stream).
import { Readable, Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
const chunk = 'abcdefghij'.repeat(102);
async function iteration() {
  let total = 0;
  let sent = 0;
  const source = new Readable({ read() { this.push(sent++ < 5000 ? chunk : null); } });
  const upper = new Transform({ transform(data, encoding, done) { done(null, data.toString().toUpperCase()); } });
  const sink = new Writable({ write(data, encoding, done) { total += data.length; done(); } });
  await pipeline(source, upper, sink);
  return total;
}
