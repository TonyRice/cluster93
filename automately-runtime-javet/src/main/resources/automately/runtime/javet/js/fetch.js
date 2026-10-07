// fetch: FetchBinding through the egress policy (D-026); the response body is a stream.
define('fetch', async (input, init = {}) => {
  const headers = {};
  for (const [k, v] of Object.entries(init.headers ?? {})) headers[String(k).toLowerCase()] = String(v);
  let body = null;
  if (init.body !== undefined && init.body !== null) {
    if (typeof init.body === 'string') body = init.body;
    else if (init.body instanceof Uint8Array) body = utf8Decode(init.body);
    else { body = JSON.stringify(init.body); headers['content-type'] ??= 'application/json'; }
  }
  const r = await host.fetch.fetch({ url: String(input), method: String(init.method ?? 'GET').toUpperCase(), headers, body, timeoutMs: init.timeoutMs ?? null });
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
