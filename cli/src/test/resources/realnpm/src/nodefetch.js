// node-fetch 3 (node:http, node:https, node:zlib, node:stream; D-109), against the test's local server.
import fetch from 'node-fetch';

const base = process.env.TEST_HTTP;

export async function run() {
  const out = {};
  const json = await fetch(base + '/json');
  out.json = { status: json.status, ok: json.ok, data: await json.json(), cookies: json.headers.raw()['set-cookie'] };
  const posted = await fetch(base + '/echo', { method: 'POST', body: JSON.stringify({ a: 1 }), headers: { 'content-type': 'application/json' } });
  out.posted = (({ method, contentType, bodyText }) => ({ method, contentType, bodyText }))(await posted.json());
  out.gzip = await (await fetch(base + '/gzip')).json();
  const followed = await fetch(base + '/redirect');
  out.redirect = { status: followed.status, redirected: followed.redirected, data: await followed.json() };
  const manual = await fetch(base + '/redirect', { redirect: 'manual' });
  out.manual = { status: manual.status, location: manual.headers.get('location')?.replace(base, '') };
  out.big = (await (await fetch(base + '/big')).arrayBuffer()).byteLength;
  try { await fetch('http://127.0.0.1:9/'); out.refused = 'answered'; } catch (e) { out.refused = [e.name, e.code]; }
  return out;
}
