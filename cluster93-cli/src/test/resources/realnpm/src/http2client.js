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
  req.on('trailers', (t, flags) => { events.push('trailers'); trailers = { checksum: t['x-checksum'], count: t['x-count'], flags }; });
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

  // D-118: request trailers, the state objects and priorities, as Node 26 has them.
  const warnings = [];
  process.on('warning', (w) => warnings.push([w.name, w.code, w.message]));
  const h2c = (await session(process.env.TEST_H2C)).s;
  const fresh = h2c.state;
  out.sessionState = { keys: Object.keys(fresh), nextStreamID: fresh.nextStreamID, lastProcStreamID: fresh.lastProcStreamID,
    effectiveRecvDataLength: fresh.effectiveRecvDataLength, outboundQueueSize: fresh.outboundQueueSize };
  out.uploadTrailers = await new Promise((resolve) => {
    const events = [];
    const req = h2c.request({ ':method': 'POST', ':path': '/request-trailers' }, { waitForTrailers: true });
    const early = (() => { try { req.sendTrailers({ a: '1' }); return 'sent'; } catch (e) { return [e.code, e.message]; } })();
    req.on('wantTrailers', () => {
      events.push('wantTrailers');
      for (const bad of [{ ':path': '/' }, { connection: 'close' }]) {
        try { req.sendTrailers(bad); } catch (e) { events.push([e.name, e.code, e.message]); }
      }
      req.sendTrailers({ 'X-Sum': 42, 'x-list': ['a', 'b'] });
      events.push(['sentTrailers', { ...req.sentTrailers }]);
      try { req.sendTrailers({}); } catch (e) { events.push([e.code, e.message]); }
    });
    req.on('finish', () => events.push('finish'));
    req.on('response', (h, flags) => {
      const state = req.state;
      events.push(['response', h[':status'], flags, Object.keys(state), state.state, state.weight, state.sumDependencyWeight, state.localClose]);
    });
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (c) => { body += c; });
    req.on('end', () => events.push('end'));
    req.on('close', () => {
      const after = (() => { try { req.sendTrailers({}); return 'sent'; } catch (e) { return [e.code, e.message]; } })();
      resolve({ early, events, received: JSON.parse(body), rstCode: req.rstCode, stateAfter: req.state, after });
    });
    req.write('part one ');
    req.end('part two');
  });
  out.noTrailerListener = await new Promise((resolve) => {
    const req = h2c.request({ ':method': 'POST', ':path': '/request-trailers' }, { waitForTrailers: true });
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (c) => { body += c; });
    req.on('close', () => resolve({ received: JSON.parse(body), sentTrailers: req.sentTrailers && { ...req.sentTrailers }, rstCode: req.rstCode }));
    req.end('x');
  });
  out.getWaitsForNothing = await new Promise((resolve) => {
    const events = [];
    const req = h2c.request({ ':path': '/request-trailers' }, { waitForTrailers: true });
    req.on('wantTrailers', () => events.push('wantTrailers'));
    req.on('response', (h, flags) => events.push(['response', flags, req.state.state]));
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (c) => { body += c; });
    req.on('close', () => resolve({ events, received: JSON.parse(body), sentTrailers: req.sentTrailers }));
  });
  out.priority = await new Promise((resolve) => {
    const req = h2c.request({ ':path': '/json' }, { weight: 200, exclusive: true });
    const returned = req.priority({ weight: 1, silent: true });
    req.resume();
    req.on('close', () => resolve({ returned, stateAfterClose: req.state }));
  });
  const used = h2c.state;
  out.sessionStateAfter = { nextStreamID: used.nextStreamID, lastProcStreamID: used.lastProcStreamID, inflate: used.inflateDynamicTableSize > 0,
    deflate: used.deflateDynamicTableSize > 0 };
  h2c.close();
  await new Promise((resolve) => h2c.on('close', resolve));
  out.closedSessionState = h2c.state;
  out.warnings = warnings;

  // stream.state through a stream's life (D-120): written on the next turn, the peer's END_STREAM applied after the
  // script saw it, the window less what arrived since the last WINDOW_UPDATE.
  out.streamState = await new Promise((resolve) => {
    const seen = [];
    const pick = (st) => st.state === undefined ? '{}' : `${st.state} ${st.localClose}${st.remoteClose} ${st.localWindowSize} ${st.weight}`;
    const s = http2.connect(process.env.TEST_H2C);
    s.on('connect', () => {
      const a = s.request({ ':method': 'POST', ':path': '/now' });
      seen.push(['request()', pick(a.state), a.id, s.state.nextStreamID]);
      a.on('ready', () => seen.push(['ready', pick(a.state), a.id]));
      setImmediate(() => seen.push(['the next turn', pick(a.state)]));
      a.on('response', () => seen.push(['response, our side open', pick(a.state)]));
      a.on('data', (c) => seen.push(['data ' + c.length, pick(a.state)]));
      a.on('end', () => {
        seen.push(['end', pick(a.state)]);
        setImmediate(() => {
          seen.push(['the turn after end', pick(a.state)]);
          a.end();
          seen.push(['end() in that turn', pick(a.state)]);
        });
      });
      a.on('close', () => {
        seen.push(['close', pick(a.state)]);
        const b = s.request({ ':path': '/big' });
        seen.push(['second request()', b.id, s.state.nextStreamID]);
        // How a large body's frames are cut depends on when the client's WINDOW_UPDATEs reach the server, and Node's
        // 'data' chunks on TCP reads: the states are compared, and that the window stays in its bounds.
        const states = new Set();
        let inBounds = true;
        b.on('data', () => {
          const st = b.state;
          states.add(`${st.state} ${st.localClose}${st.remoteClose} ${st.weight}`);
          inBounds &&= st.localWindowSize > 0 && st.localWindowSize <= 65535;
        });
        b.on('end', () => seen.push(['big body', [...states], inBounds]));
        b.on('close', () => { seen.push(['big close', pick(b.state)]); s.close(); resolve(seen); });
      });
    });
  });

  // Flow control, exactly (D-121): the paced server sends the same frames to every client, each in its own read, so
  // nghttp2's windows and queued WINDOW_UPDATEs are the same in every run. Each 'data' handler, its ticks and the next
  // turn see them.
  out.flowControl = await new Promise((resolve) => {
    const s = http2.connect(process.env.TEST_H2_PACED);
    const seen = [];
    const r = s.request({ ':path': '/' });
    const snap = (label) => {
      const st = r.state;
      const ss = s.state;
      seen.push([label, st.localWindowSize ?? '{}', ss.outboundQueueSize, ss.effectiveRecvDataLength, ss.localWindowSize]);
    };
    r.on('data', (c) => {
      snap('data ' + c.length);
      process.nextTick(() => snap(' its tick'));
      setImmediate(() => snap(' the next turn'));
    });
    r.on('end', () => setImmediate(() => setImmediate(() => { s.close(); resolve(seen); })));
  });

  // nghttp2's outbound queue: control frames submitted and not yet written, which Node does on the next turn.
  out.outboundQueue = await new Promise((resolve) => {
    const seen = [];
    const q = (label, s) => seen.push([label, s.state.outboundQueueSize]);
    const s = http2.connect(process.env.TEST_H2C);
    q('before connect', s);
    // Node's count here depends on whether the peer's SETTINGS arrive before or after it wrote this turn's frames
    // (1 or 2 in runs against the same server): what holds is that their ACK is queued.
    s.once('remoteSettings', () => seen.push(['remoteSettings: their ACK queued', s.state.outboundQueueSize >= 1]));
    s.on('connect', () => {
      q('connect', s);
      queueMicrotask(() => q('connect, then a microtask', s));
      setImmediate(() => {
        q('the next turn', s);
        const r = s.request({ ':path': '/json' });
        q('request()', s);
        // While input is handled, Node's count also holds what that input queued (an ACK, a WINDOW_UPDATE), which
        // under load varies: from there, what the script adds is compared.
        r.on('response', () => seen.push(['response', s.state.outboundQueueSize >= 0]));
        r.resume();
        r.on('close', () => {
          const base = s.state.outboundQueueSize;
          const added = (label) => seen.push([label, s.state.outboundQueueSize - base]);
          s.settings({ initialWindowSize: 70000 });
          added('settings() adds');
          s.request({ ':path': '/json' }).close(NGHTTP2_CANCEL);
          added('and a request() closed in the same turn');
          s.ping(() => {});
          added('and ping()');
          setImmediate(() => {
            q('the turn after', s);
            const slow = s.request({ ':path': '/slow' });
            slow.on('error', () => {});
            setImmediate(() => {
              slow.close(NGHTTP2_CANCEL);
              q('close() on a stream already sent', s);
              s.goaway(0);
              q('goaway()', s);
              setImmediate(() => { q('last turn', s); s.destroy(); resolve(seen); });
            });
          });
        });
      });
    });
  });

  out.untrusted = (await session(process.env.TEST_H2)).error;
  out.wrongName = (await session(process.env.TEST_H2.replace('localhost', '127.0.0.1'), { ca })).error;
  out.refused = (await session('http://127.0.0.1:9')).error;
  out.unsupported = (() => { try { http2.connect('ftp://localhost'); return 'connected'; } catch (e) { return e.code; } })();
  out.packed = [http2.getPackedSettings({ enablePush: false, initialWindowSize: 100 }).toString('hex'),
    http2.getUnpackedSettings(http2.getPackedSettings({ maxConcurrentStreams: 5, enablePush: true }))];
  out.defaults = http2.getDefaultSettings();
  return out;
}
