// node:http2's client (D-117), against the test's HTTP/2 servers: TEST_H2 (TLS, with TEST_CA) and TEST_H2C (clear text).
// Runs the same under Node.
import http2 from 'node:http2';

const ca = process.env.TEST_CA;
const { HTTP2_HEADER_PATH, HTTP2_HEADER_METHOD, NGHTTP2_CANCEL } = http2.constants;

/** One stream: its events in order, the response, the body and the trailers. */
const call = (session, headers, body) => new Promise((resolve) => {
  const events = [];
  const req = session.request(headers);
  const chunks = [];
  let response = null;
  let trailers = null;
  req.on('response', (h, flags) => { events.push('response'); response = { status: h[':status'], type: h['content-type'], cookie: h['set-cookie'], repeat: h['x-repeat'], version: h['x-version'], flags }; });
  req.on('trailers', (t) => { events.push('trailers'); trailers = { checksum: t['x-checksum'], count: t['x-count'] }; });
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => events.push('end'));
  req.on('error', (e) => events.push('error ' + e.code + ' ' + e.message));
  req.on('close', () => {
    events.push('close');
    const text = Buffer.concat(chunks).toString('utf8');
    resolve({ events, response, trailers, body: text.length > 200 ? text.length : text, rstCode: req.rstCode, idOdd: typeof req.id === 'number' && req.id % 2 === 1 });
  });
  if (body !== undefined) req.end(body);
});

const session = (url, options) => new Promise((resolve) => {
  const s = http2.connect(url, options);
  const events = [];
  s.on('connect', (self, socket) => { events.push('connect ' + (self === s) + ' ' + (socket === s.socket)); resolve({ s, events }); });
  s.on('remoteSettings', () => events.push('remoteSettings'));
  s.on('error', (e) => resolve({ s, events, error: { code: e.code, message: e.message } }));
});

export async function run() {
  const out = {};
  const tls = await session(process.env.TEST_H2, { ca });
  out.tlsSession = { events: tls.events, alpn: tls.s.alpnProtocol, encrypted: tls.s.encrypted, type: tls.s.type,
    remote: tls.s.socket.remoteAddress, settings: Object.keys(tls.s.remoteSettings ?? {}).sort(), origin: tls.s.originSet };
  out.json = await call(tls.s, { [HTTP2_HEADER_PATH]: '/json' });
  out.post = await call(tls.s, { [HTTP2_HEADER_METHOD]: 'POST', [HTTP2_HEADER_PATH]: '/echo?x=1', 'x-custom': 'yes' }, 'héllo body');
  out.parallel = (await Promise.all(['/json', '/echo', '/json'].map((p) => call(tls.s, { ':path': p })))).map((r) => r.response.status);
  out.trailers = await call(tls.s, { ':path': '/trailers' });
  out.reset = await call(tls.s, { ':path': '/reset' });
  out.cancelled = await call(tls.s, { ':path': '/cancel' });
  out.empty = await call(tls.s, { ':path': '/empty' });
  out.big = await call(tls.s, { ':path': '/big' });
  out.notFound = await call(tls.s, { ':path': '/missing' });
  out.closedByUs = await new Promise((resolve) => {
    const req = tls.s.request({ ':path': '/slow' });
    req.on('error', (e) => resolve(['error', e.code]));
    req.on('close', () => resolve(['close', req.rstCode, req.aborted, req.destroyed]));
    setTimeout(() => req.close(NGHTTP2_CANCEL), 50);
  });
  out.ping = await new Promise((resolve) => tls.s.ping(Buffer.from('abcdefgh'), (err, duration, payload) => resolve([err, typeof duration, payload.toString()])));
  out.settings = await new Promise((resolve) => tls.s.settings({ initialWindowSize: 100000 }, (err, settings) => resolve([err, settings.initialWindowSize])));
  out.badHeader = (() => { try { tls.s.request({ ':path': '/', connection: 'keep-alive' }); return 'sent'; } catch (e) { return e.code; } })();
  out.badPing = (() => { try { tls.s.ping(Buffer.from('short'), () => {}); return 'sent'; } catch (e) { return e.code; } })();
  out.closeEvents = await new Promise((resolve) => { const ev = []; tls.s.on('close', () => resolve([...ev, 'close', tls.s.closed, tls.s.destroyed])); tls.s.close(() => ev.push('callback')); });
  out.afterClose = await new Promise((resolve) => {
    try {
      const req = tls.s.request({ ':path': '/' });
      req.on('error', (e) => resolve(['error', e.code]));
    } catch (e) {
      resolve(['thrown', e.code]);
    }
  });

  const clear = await session(process.env.TEST_H2C);
  out.h2c = { alpn: clear.s.alpnProtocol, encrypted: clear.s.encrypted, json: (await call(clear.s, { ':path': '/json' })).response };
  clear.s.close();

  out.untrusted = (await session(process.env.TEST_H2)).error;
  out.wrongName = (await session(process.env.TEST_H2.replace('localhost', '127.0.0.1'), { ca })).error;
  out.refused = (await session('http://127.0.0.1:9')).error;
  out.unsupported = (() => { try { http2.connect('ftp://localhost'); return 'connected'; } catch (e) { return e.code; } })();
  out.packed = [http2.getPackedSettings({ enablePush: false, initialWindowSize: 100 }).toString('hex'),
    http2.getUnpackedSettings(http2.getPackedSettings({ maxConcurrentStreams: 5, enablePush: true }))];
  out.defaults = http2.getDefaultSettings();
  return out;
}
