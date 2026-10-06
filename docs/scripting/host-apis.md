# Host APIs reference: `kv`, `bus`, `fetch`, `log` and serving HTTP

**Status:** Implemented in phase 3 (2026-10-06). Design: [../design/host-apis.md](../design/host-apis.md).

Everything here is scoped to your tenant automatically: keys, topics and hosts can't collide with another tenant's. Every call returns a promise. Failures reject with an `Error` whose `name` is `TypeError`, `RangeError`, `TimeoutError` or `Error`, and whose `code` says why (for example `egress_denied`, `limit_exceeded`, `no_handler`).

## `kv`: key-value storage

```js
await kv.set('user:42', { name: 'Ada' });                 // any JSON value
await kv.set('session:abc', token, { ttlMs: 3600_000 });   // expires exactly then
const user = await kv.get('user:42');                      // null if missing or expired
await kv.delete('user:42');                                // true if something was removed
const { keys, cursor } = await kv.list({ prefix: 'user:', limit: 100 });  // sorted; pass cursor for the next page
const visits = await kv.increment('visits');               // atomic; missing counts as 0
const orders = kv.namespace('orders');                     // same API, separate key space
```

Limits: key ≤ 512 bytes, value ≤ 256 KB of JSON, `list` ≤ 1 000 keys per page.

> Storage is the cluster's in-memory map for now. It survives node failures, but not a restart of the whole cluster.

## `bus`: messaging

```js
// Fire and forget, to every subscriber of the topic:
await bus.publish('orders', { id: 7, items: 3 });

// Listen; returns a function that stops listening:
const stop = await bus.subscribe('orders', async (order, { topic }) => { ... });

// Request/reply: one handler answers, with its return value:
await bus.handle('prices', async ({ sku }) => lookup(sku));
const price = await bus.request('prices', { sku: 'A1' }, { timeoutMs: 5000 });
```

- Topic names match `[a-zA-Z0-9._-]{1,128}`, and messages are JSON ≤ 256 KB.
- `request` rejects with `code: 'no_handler'` if nothing handles the topic, with `'timeout'` (a `TimeoutError`) if no answer arrives in time (30 s at most), and with `'handler_failed'` if the handler throws.
- An error thrown in a `subscribe` handler is logged and doesn't end the script.
- A job that is waiting on a subscription keeps running; its timeout still bounds it.

From outside the cluster:
```http
POST /v1/bus/orders                      → 202, publishes the JSON body
POST /v1/bus/prices/request?timeoutMs=5000 → 200 {"value": ...}, or 404 / 504 / 502
X-Automately-Tenant: acme
```

## Streams and text encoding

Bodies are **streams of `Uint8Array` chunks** that you read with `for await`. Reading is backpressured: while you're not reading, the sender is paused.

```js
for await (const chunk of req.body) { ... }        // request bodies in ctx.serve
for await (const chunk of res.body) { ... }        // fetch response bodies
```

A body can be read **once**: either as a stream, or with `text()`, `json()` or `bytes()`, which buffer it. Buffering is capped (32 MB for request bodies, 5 MB for fetch responses); anything larger must be read as a stream.

`TextEncoder` and `TextDecoder` (UTF-8) are available for turning chunks into text and back.

## `fetch`: outbound HTTP

```js
const res = await fetch('https://api.example.com/items?page=2', {
  method: 'POST',
  headers: { authorization: `Bearer ${token}` },
  body: { name: 'thing' },          // an object is sent as JSON; a string as is
  timeoutMs: 10_000,                // at most 30 s
});
res.status; res.ok; res.headers.get('content-type');
const data = await res.json();      // or res.text(), res.bytes(), or stream res.body
```

- Only `http` and `https`.
- **Private and reserved addresses are refused** (`code: 'egress_denied'`): loopback, 10/8, 172.16/12, 192.168/16, link-local (including the 169.254.169.254 cloud metadata endpoint), CGNAT, unique-local IPv6, and so on. The connection goes to the exact address that was checked, so DNS tricks can't get around this.
- Redirects are **not followed**: a 3xx comes back as is.
- The response body streams (`res.body`), with no size limit when read as a stream. `text()`, `json()` and `bytes()` buffer it, up to 5 MB.
- Request bodies are strings or JSON, up to 1 MB. Binary request bodies aren't supported yet.
- At most 16 fetches run at once per script, counting a fetch until its body has been read, cancelled, or the script ends. An unread body is cancelled when the script ends.
- The timeout applies to connecting and to any pause between chunks, so long downloads work.
- For local development, `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true` lifts the address check.

## `log`: structured logging

```js
log.info('order placed', { orderId: 7, total: 19.5 });   // also debug, warn, error
```

Each call is a log line (`order placed {"orderId":7,"total":19.5}`). The live event stream carries `message` and `fields` separately. `console.log` still works too.

## Serving HTTP from a service

```js
// PUT /v1/services/shop  { "script": "..." }
export default async (input, ctx) => {
  await ctx.serve(async (req) => {
    // req: method, url, path, host, query (object), headers.get(name), body (stream), text(), json(), bytes()
    if (req.path === '/health') return 'ok';                                     // 200 text/plain
    if (req.method === 'POST' && req.path === '/orders') {
      const order = await req.json();
      await kv.set(`order:${order.id}`, order);
      return { status: 201, body: { saved: order.id } };                         // an object body is JSON
    }
    return { status: 404, headers: { 'cache-control': 'no-store' }, body: 'not found' };
  });
  return ctx.stopRequested;   // keep serving until the service is stopped
};
```

- **Where it's reachable.** Every service gets `https://<service>--<tenant>.<ingress domain>`, for example `shop--acme.localhost` in development, on the ingress port (8000). Any node's ingress serves any service.
- **Your own domain.**
  ```http
  POST /v1/services/shop/domains          {"domain": "shop.example.com"}
  → 201 {"verified": false, "verification": {"type": "TXT", "name": "_automately.shop.example.com", "value": "automately-verify=…"}}
  ```
  Publish that TXT record, then call `POST /v1/services/shop/domains/shop.example.com/verify`. The domain routes only once it's verified. Unverified claims lapse after 24 h, and deleting the service releases its domains.
- **Responses.** Return a string (200), `undefined` (204), a `Uint8Array`, an (async) iterable such as a generator, or `{status, headers, body}`, where `body` can be any of those or an object (sent as JSON). If the handler throws before responding, the response is a 500. If it throws while streaming, the connection is reset.
- **Streaming responses.** Each chunk you yield waits until the client has taken it, so a slow client slows your generator down instead of filling memory. If the client disconnects, the generator is ended, so its `finally` runs.

  ```js
  await ctx.serve(async (req) => {
    if (req.path === '/events') {                     // server-sent events
      return { headers: { 'content-type': 'text/event-stream' }, body: (async function* () {
        for (let i = 0; ; i++) { yield `data: ${i}\n\n`; await ctx.sleep(1000); }
      })() };
    }
    if (req.method === 'POST' && req.path === '/upload') {   // a large upload, read as it arrives
      let bytes = 0;
      for await (const chunk of req.body) bytes += chunk.length;
      return { body: { bytes } };
    }
  });
  ```
- **Ingress errors.** An unknown host gets 404. A service that's stopped, restarting or not yet serving gets 503 with `Retry-After: 1`. A request with no bytes moving in either direction for the idle timeout (60 s) gets 504, or a closed connection if the response had already started. More than 64 requests in flight to one service gets 429.
- **Limits.** Request bodies up to `AUTOMATELY_MAX_REQUEST_BYTES` (1 GiB by default). Responses have no size or total-time limit, only the idle timeout.
- Only services can serve. In a plain job, `ctx.serve` throws.

## Naming
Tenant and service names become part of hostnames, so they're lowercase letters, digits, `_` and `-` (starting with a letter or digit, at most 63 characters) and **can't contain `--`**.
