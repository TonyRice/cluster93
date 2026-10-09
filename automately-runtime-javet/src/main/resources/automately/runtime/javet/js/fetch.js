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
