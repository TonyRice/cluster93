// axios with its Node adapter (node:http, node:zlib, node:stream; D-109), against the test's local server.
import axios from 'axios';

const base = process.env.TEST_HTTP;

export async function run() {
  const out = {};
  const json = await axios.get(base + '/json');
  out.json = { status: json.status, data: json.data, cookies: json.headers['set-cookie'] };
  const posted = await axios.post(base + '/echo', { name: 'thing', n: 1 }, { headers: { 'x-custom': 'axios' } });
  out.posted = { method: posted.data.method, type: posted.data.contentType, custom: posted.data.custom, body: posted.data.bodyText };
  const zipped = await axios.get(base + '/gzip');
  out.gzip = zipped.data;
  const followed = await axios.get(base + '/redirect');
  out.redirect = { status: followed.status, data: followed.data };
  try { await axios.get(base + '/status/404'); out.notFound = 'resolved'; } catch (e) { out.notFound = [e.response.status, e.response.data]; }
  try { await axios.get(base + '/slow', { timeout: 150 }); out.timeout = 'answered'; } catch (e) { out.timeout = e.code; }
  const stream = await axios.get(base + '/big', { responseType: 'stream' });
  let length = 0;
  for await (const chunk of stream.data) length += chunk.length;
  out.stream = length;
  const bytes = await axios.put(base + '/echo', Buffer.from([1, 2, 3, 250]), { headers: { 'content-type': 'application/octet-stream' } });
  out.bytes = { hex: bytes.data.bodyHex, length: bytes.data.contentLength };
  return out;
}
