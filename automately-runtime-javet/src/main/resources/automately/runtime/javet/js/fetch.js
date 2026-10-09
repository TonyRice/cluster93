// fetch: FetchBinding through the egress gate (D-026, D-108); the response body is a stream.
define('fetch', async (input, init = {}) => {
  const headers = {};
  for (const [k, v] of Object.entries(init.headers ?? {})) headers[String(k).toLowerCase()] = String(v);
  let body = null;
  if (init.body !== undefined && init.body !== null) {
    // Bytes go as bytes (D-108); before, they were decoded as UTF-8, which corrupted binary uploads.
    if (typeof init.body === 'string') body = init.body;
    else if (ArrayBuffer.isView(init.body)) body = new Uint8Array(init.body.buffer, init.body.byteOffset, init.body.byteLength);
    else if (init.body instanceof ArrayBuffer) body = new Uint8Array(init.body);
    else if (init.body instanceof URLSearchParams) { body = String(init.body); headers['content-type'] ??= 'application/x-www-form-urlencoded;charset=UTF-8'; }
    else { body = JSON.stringify(init.body); headers['content-type'] ??= 'application/json'; }
  }
  const r = await host.fetch.fetch({ url: String(input), method: String(init.method ?? 'GET').toUpperCase(), headers, timeoutMs: init.timeoutMs ?? null }, body);
  const stream = readable(r.streamId);
  return Object.freeze({
    status: r.status, statusText: r.statusText, ok: r.status >= 200 && r.status < 300, url: r.url,
    headers: headerBag(r.headers),
    body: stream,
    bytes: () => collect(stream, FETCH_BUFFER_LIMIT, 'The response body'),
    text: async () => utf8Decode(await collect(stream, FETCH_BUFFER_LIMIT, 'The response body')),
    json: async () => JSON.parse(utf8Decode(await collect(stream, FETCH_BUFFER_LIMIT, 'The response body'))),
  });
});

// node:http's way to the host (D-109): a request whose body streams, through the same gate and limits as fetch.
// No global, only a symbol node:http (js/node/http.js) finds, as zlib's.
const httpHost = host.fetch;
Object.defineProperty(globalThis, Symbol.for('automately.http'), {
  value: Object.freeze({
    open: (request) => httpHost.open(request),
    write: (id, bytes) => httpHost.write(id, bytes),
    end: (id) => httpHost.end(id),
    response: async (id) => {
      const r = await httpHost.response(id);
      return { meta: r, body: readable(r.streamId) };
    },
    abort: (id) => httpHost.abort(id),
  }),
});

// node:net's and node:tls's way to the host (D-116): sockets through the egress gate and the cluster's limits. A
// socket's data arrives as a stream; after a TLS upgrade, the same stream carries the decrypted data.
const socketHost = host.sockets;
Object.defineProperty(globalThis, Symbol.for('automately.sockets'), {
  value: Object.freeze({
    connect: async (request) => {
      const r = await socketHost.connect(request);
      return { id: r.id, info: r.info, body: readable(r.streamId) };
    },
    write: (id, bytes) => socketHost.write(id, bytes),
    end: (id) => socketHost.end(id),
    upgrade: (id, tls) => socketHost.upgrade(id, tls),
    setNoDelay: (id, value) => socketHost.setNoDelay(id, value),
    setKeepAlive: (id, value) => socketHost.setKeepAlive(id, value),
    destroy: (id) => socketHost.destroy(id),
    rootCertificates: () => JSON.parse(socketHost.rootCertificates()),
  }),
});

// node:http2's way to the host (D-117): sessions counted as sockets, streams as requests. A session's events (goaway,
// new settings) arrive as JSON chunks on a stream; a stream's body arrives as a stream once it has answered.
const http2Host = host.http2;
Object.defineProperty(globalThis, Symbol.for('automately.http2'), {
  value: Object.freeze({
    connect: async (request) => {
      const r = await http2Host.connect(request);
      return { id: r.id, info: r.info, settings: r.settings, events: readable(r.eventsId) };
    },
    request: (sessionId, headers, endStream) => http2Host.request(sessionId, headers, endStream),
    write: (id, bytes) => http2Host.write(id, bytes),
    end: (id) => http2Host.end(id),
    response: async (id) => {
      const r = await http2Host.response(id);
      return { rawHeaders: r.rawHeaders, status: r.status, flags: r.flags, read: r.read, body: readable(r.bodyId) };
    },
    trailers: (id) => http2Host.trailers(id),
    sendTrailers: (id, trailers) => http2Host.sendTrailers(id, trailers),
    release: (id) => http2Host.release(id),
    chunkRead: (id) => http2Host.chunkRead(id),
    sessionState: (sessionId) => http2Host.sessionState(sessionId),
    reset: (id, code) => http2Host.reset(id, code),
    ping: (sessionId, payload) => http2Host.ping(sessionId, payload),
    settings: (sessionId, settings) => http2Host.settings(sessionId, settings),
    goaway: (sessionId, code, lastStreamId, data) => http2Host.goaway(sessionId, code, lastStreamId, data),
    close: (sessionId) => http2Host.close(sessionId),
    destroy: (sessionId) => http2Host.destroy(sessionId),
  }),
});

