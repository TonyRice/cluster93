// Serving HTTP (ctx.serve): HttpBinding. A request body streams in; the handler's return value streams out.
const toResponse = (value) => {
  if (value === undefined || value === null) return { status: 204, headers: {}, body: null };
  if (typeof value === 'string') return { status: 200, headers: { 'content-type': 'text/plain; charset=utf-8' }, body: value };
  if (value instanceof Uint8Array || isIterable(value)) return { status: 200, headers: {}, body: value };
  const headers = {};
  for (const [k, v] of Object.entries(value.headers ?? {})) {
    // Only the host knows the exact length (files set it themselves); a wrong one would corrupt the response.
    if (String(k).toLowerCase() !== 'content-length') headers[String(k).toLowerCase()] = String(v);
  }
  let body = value.body ?? null;
  if (body !== null && typeof body !== 'string' && !(body instanceof Uint8Array) && !isIterable(body)) {
    body = JSON.stringify(body);
    headers['content-type'] ??= 'application/json';
  }
  return { status: Number(value.status ?? 200), headers, body };
};
const respond = async (exchangeId, value) => {
  const ref = value?.[FILE] ?? value?.body?.[FILE];
  if (ref) {
    // Served by the host straight from storage, with Range and ETag support.
    const headers = { ...ref.headers };
    for (const [k, v] of Object.entries(value?.[FILE] ? {} : value.headers ?? {})) {
      if (String(k).toLowerCase() !== 'content-length') headers[String(k).toLowerCase()] = String(v);
    }
    await host.files.respond(exchangeId, ref.path, headers);
    return;
  }
  const res = toResponse(value);
  host.http.respond(exchangeId, res.status, res.headers);
  const body = res.body;
  if (body === null) {
    // nothing
  } else if (typeof body === 'string' || body instanceof Uint8Array) {
    if (body.length) await host.http.write(exchangeId, toBytes(body));
  } else {
    for await (const part of body) {
      if (part === undefined || part === null) continue;
      await host.http.write(exchangeId, toBytes(part));
    }
  }
  host.http.end(exchangeId);
};
// Same id space as bus listeners: the host keeps both in one registry.
const serve = (handler) => listen((id) => host.http.serve(id), handler);

// Node's servers (D-123): http.createServer's listen registers a port, and its requests come here raw, for node:http to
// shape. A service's run lasts while one is open, after its default export returns (or without one).
const RAW = Symbol('raw request handler');
let openServers = 0;
const serversDone = [];
const serverOpened = () => { openServers++; };
const serverClosed = () => {
  if (openServers > 0) openServers--;
  if (openServers === 0) for (const done of serversDone.splice(0)) done();
};
/** Resolves once no server is open, or at once if none is. */
const whenServersClose = () => (openServers === 0 ? Promise.resolve() : new Promise((resolve) => serversDone.push(resolve)));
/** A stop with no SIGTERM listener ends the run, as Node's default action ends the process. */
const releaseServers = () => { openServers = 0; for (const done of serversDone.splice(0)) done(); };
Object.defineProperty(globalThis, Symbol.for('cluster93.server'), {
  value: Object.freeze({
    listen: (port, onRequest) => listen((id) => host.http.listen(id, port), Object.assign((...args) => onRequest(...args), { [RAW]: true })),
    opened: serverOpened,
    closed: serverClosed,
    respond: (id, status, message, rawHeaders) => host.http.respondRaw(id, status, message ?? null, rawHeaders),
    write: (id, bytes) => host.http.write(id, bytes),
    end: (id) => host.http.end(id),
    trailers: (id, rawTrailers) => host.http.trailers(id, rawTrailers),
    fail: (id, status, message) => host.http.fail(id, status, message),
    clientClosed: (id) => host.http.closed(id),
    // The request's connection as a socket, for an 'upgrade' listener (D-125); null if it can't be upgraded.
    upgrade: (id) => {
      const taken = host.http.upgrade(id);
      if (taken === null || taken === undefined) return null;
      const r = JSON.parse(taken);
      return { id: r.id, info: r.info, body: readable(r.streamId) };
    },
  }),
});

// Called by the event loop for each request to a serving listener.
const request = (listenerId, exchangeId, requestJson, bodyId) => {
  const handler = listeners.get(listenerId);
  const r = JSON.parse(requestJson);
  const body = readable(bodyId);
  if (handler?.[RAW]) {
    try {
      handler(exchangeId, r, body);
    } catch (e) {
      host.http.fail(exchangeId, 500, 'The server failed');
      reportUncaught(e, 'an http server');
    }
    return;
  }
  const req = Object.freeze({
    method: r.method, url: r.url, path: r.path, host: r.host, query: Object.freeze({ ...r.query }),
    headers: headerBag(r.headers),
    body,
    bytes: () => collect(body, BUFFER_LIMIT, 'The request body'),
    text: async () => utf8Decode(await collect(body, BUFFER_LIMIT, 'The request body')),
    json: async () => JSON.parse(utf8Decode(await collect(body, BUFFER_LIMIT, 'The request body'))),
  });
  (async () => {
    let started = false;
    try {
      if (!handler) { host.http.fail(exchangeId, 503, 'No longer serving'); return; }
      const value = await handler(req);
      started = true;
      await respond(exchangeId, value);
    } catch (e) {
      if (!started) host.log('error', `Uncaught error in request handler: ${errorText(e)}`);
      host.http.fail(exchangeId, 500, String(e && e.message ? e.message : e));
    } finally {
      body.cancel();
    }
  })();
};
