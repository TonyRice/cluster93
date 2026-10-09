# Host APIs reference: `kv`, `bus`, queues, `jobs`, `fetch`, `log`, `files` and serving HTTP

**Status:** Implemented in phase 3 (2026-10-06). Design: [../design/host-apis.md](../design/host-apis.md).

For Node's modules in the isolate (`crypto`, `stream`, `zlib`, `buffer`, …), with every limit and difference from Node, see [Node compatibility](node-compat.md).

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

Limits: key ≤ 512 bytes, value ≤ 256 KB of JSON, `list` ≤ 1 000 keys per page. Entries count towards the tenant's storage quota, shared with `files`: an entry counts as the bytes of its namespace, key and JSON value. Over the quota, `set` and `increment` fail with `code: 'limit_exceeded'`, but updating an entry to the same size or smaller, and deleting, always work.

> Storage is the cluster's database: replicated, and durable across full restarts when nodes have a data directory. When the cluster is out of room, writes fail with `code: 'insufficient_storage'`.

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
Authorization: Bearer atly_…
```

## `bus.queue(name)`: durable queues

The bus has two modes. `publish`/`subscribe` and `request`/`handle` are fast and **at most once**: a message nobody listens for is gone. A queue keeps messages in the cluster until a consumer has handled them: **at least once**, even if the consumer's node dies.

```js
const emails = bus.queue('emails');

// Producers (any job, service, or the API):
const id = await emails.push({ to: 'a@example.com' });
await emails.push(reminder, { delayMs: 60_000 });              // not before a minute from now
await emails.push(order, { key: `order-${order.id}` });        // pushed once while it's queued
await emails.push(fragile, { maxAttempts: 10 });               // default 5

// A consumer, usually a service; up to `concurrency` messages at a time:
await emails.consume(async (email, { id, attempt, enqueuedAt }) => {
  await send(email);          // resolving acknowledges: the message is done
}, { concurrency: 4 });       // throwing retries it later (1 s, 4 s, 16 s, … up to 10 min)

await emails.stats();                       // { ready, inFlight, dead }
const { messages } = await emails.dead();   // used up their attempts, with lastError
await emails.retry(messages[0].id);         // back on the queue, attempts reset
await emails.discard(id);                   // delete a dead letter
```

- **Competing consumers.** Every consumer of a queue, on any node, shares the work, and each message goes to one of them at a time.
- **Leases.** A consumer holds a message for 30 s and extends that while the handler is still working. If its node dies, the lease runs out and the message is delivered again. Handlers should therefore be idempotent, or use the delivery's `id` (or your own key) to skip work already done.
- **Dead letters.** After its last attempt a message moves to the queue's dead letters instead of being retried forever.
- **The script ends mid-message.** The message goes back at once, and that delivery doesn't count as an attempt.
- **Names, size, quota.** Names match `[a-zA-Z0-9._-]{1,128}`, and messages are JSON of at most 256 KB. Queued messages count toward your storage quota, with files and `kv`.

From outside the cluster:
```http
POST   /v1/queues/emails                  {"message": {...}, "delayMs"?, "key"?, "maxAttempts"?} → 202 {"id"}
GET    /v1/queues/emails                  → {"ready", "inFlight", "dead"}
GET    /v1/queues/emails/dead             → {"messages": [...], "cursor"}
POST   /v1/queues/emails/dead/{id}/retry
DELETE /v1/queues/emails/dead/{id}
```
Scopes: `queues:write` to push and manage dead letters, `queues:read` to look.

## `jobs`: starting and following jobs

A script can start other jobs and wait for them, as 2017's `Automately.startJob` did, but without blocking a thread:

```js
// A function is sent as its source: it can't use variables from around it, only its input.
const child = await jobs.run(async (input, ctx) => ({ doubled: input.n * 2 }), { n: 21 });
const { status, output, error } = await child.result();   // when it ends

// Or an ES module's source, with the same options as POST /v1/jobs:
const report = await jobs.run(source, { month: 9 }, { timeoutMs: 60_000, onOrphan: 'retry', callback: { url } });

await report.events((event) => { if (event.type === 'log') console.log('report:', event.line); });
await report.stop('no longer needed');

await jobs.get(id);              // a job's summary, or null
await jobs.handle(id).result();  // follow a job you know the id of
```

- **Who owns them.** Started jobs belong to your tenant and count toward its rate limit. Each records its `parent` and its `depth`.
- **Packages.** A started job can import the [packages](../guides/packages.md) its parent could, at the same versions; importing any other package refuses the `jobs.run`.
- **Limits.** Nesting stops at 5 levels, and one run can start at most 100 jobs. Both fail with `limit_exceeded`.
- **Capacity.** A parent waiting on `result()` keeps its isolate. If no node has room for the child, the child fails at once, rather than the parent waiting for ever.
- **Other tenants.** Another tenant's job ids look like missing ones: `get` gives `null`, and `result` and `stop` fail with `not_found`.
- **Results after the run.** To receive a job's result after your own run has ended, use a [completion callback](../guides/callbacks.md) instead of `result()`.

## `secrets`: credentials

```js
const key = await secrets.get('STRIPE_KEY');   // the value, or null if there's no such secret
```

- **Read-only.** Set secrets with the API or `automately secrets set` ([guide](../guides/secrets.md)).
- **Only scripts allowed to read them:** jobs submitted with a token that has `secrets:use`, services last defined with one, and jobs those start. Other scripts get `forbidden`.
- **Needs `AUTOMATELY_SECRETS_KEY`** on the cluster; without it, calls fail with `unavailable`.

## `process.env`: configuration you declare

A job or service declares its environment; the script sees exactly that, plus `NODE_ENV` (`"production"`), and never the node's own environment:
```json
POST /v1/jobs
{ "script": "...", "env": { "API_URL": "https://api.example.com", "STRIPE_KEY": { "secret": "stripe-live" } } }
```
```js
export default async () => fetch(process.env.API_URL, { headers: { authorization: `Bearer ${process.env.STRIPE_KEY}` } });
```
- **Secrets by name.** `{"secret": "name"}` is read when the run starts, by the same rule as `secrets.get`: the submitting token needs `secrets:use`. The job record keeps the name, never the value, and **the value is masked in the job's log** as `[secret:name]`, since libraries print their configuration.
- **Where to set it:**
  - `POST /v1/jobs` and `PUT /v1/services/{name}`, where it's part of the revision, so changing it rolls the service
  - `service.env` in `automately.json` for `automately deploy`
  - `automately run … --env NAME=value --env-secret NAME=secret`
  - `jobs.run(script, input, { env: {...}, inheritEnv: true })`: a started job gets its parent's env only with `inheritEnv`.
- **`process`** is a global, as in Node, and `node:process` gives the same object. It doesn't claim to be Node (`process.versions.node` is undefined); `exit`, `chdir` and `binding` throw.

## `crypto`

The global `crypto` is Web Crypto, and `node:crypto` is Node's; both run on the JVM's own cryptography (D-097, D-101):
```js
crypto.getRandomValues(new Uint8Array(16));        // secure random bytes (at most 65,536 per call)
crypto.randomUUID();
await crypto.subtle.digest('SHA-256', bytes);      // and sign, encrypt, deriveBits, importKey, … (D-104)

import { createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
createHash('sha256').update('abc').digest('hex');  // md5, sha1, sha2, sha3
createHmac('sha256', key).update(body).digest('base64');

const key = scryptSync(password, salt, 32);                     // or pbkdf2, hkdf
const cipher = createCipheriv('aes-256-gcm', key, iv);           // aes-*-cbc, -ctr, -gcm; chacha20-poly1305
const sealed = Buffer.concat([cipher.update(text), cipher.final()]), tag = cipher.getAuthTag();

const privateKey = createPrivateKey(pem);                        // PEM, DER or JWK
const signature = sign('sha256', data, privateKey);              // RSA (PKCS#1, PSS), ECDSA, Ed25519 (algorithm null)
verify('sha256', data, createPublicKey(privateKey), signature);
generateKeyPair('ec', { namedCurve: 'P-256' }, (err, publicKey, privateKey) => { ... });
```
- **What's there:**
  - **Randomness:** `randomBytes`, `randomFill`, `randomInt` and `randomUUID`.
  - **Hashing:** hashing and HMAC (incremental, with `copy()`), one-shot `hash()` and `timingSafeEqual`.
  - **Key derivation:** `pbkdf2`, `hkdf` and `scrypt`, each with a `…Sync` form.
  - **Ciphers:** `createCipheriv`/`createDecipheriv` with AES-CBC, AES-CTR, AES-GCM and ChaCha20-Poly1305.
  - **Keys:** `KeyObject`s (secret, public and private), `createPrivateKey`/`createPublicKey` from PEM, DER, JWK or an X.509 certificate, and `export` to any of them.
  - **Signatures:** `sign`/`verify` and `createSign`/`createVerify`.
  - **Key generation:** `generateKeyPair` for rsa, ec (P-256, P-384, P-521) and ed25519, and `generateKey` for hmac and aes keys.
- **Also there (D-104):**
  - **RSA encryption:** `publicEncrypt`/`privateDecrypt`/`privateEncrypt`/`publicDecrypt`, OAEP by default.
  - **Key agreement:** `createECDH`, and `diffieHellman` for ec and x25519 keys.
  - **Web Crypto:** `crypto.subtle` with HMAC, AES-GCM/CBC/CTR, RSASSA-PKCS1-v1_5, RSA-PSS, RSA-OAEP, ECDSA, ECDH, Ed25519, X25519, PBKDF2 and HKDF, plus `CryptoKey`.
  - **Refused:** `privateDecrypt` with PKCS#1 v1.5 padding, since the JVM's is open to the Marvin timing attack. Use OAEP.
- **Not yet:** classic Diffie-Hellman (`createDiffieHellman`), encrypted private keys, rsa-pss and Ed448/X448 keys, and AES-KW. These throw `ERR_FEATURE_UNAVAILABLE_ON_PLATFORM` or say what is supported.
- **Limits:**
  - **PBKDF2:** at most 1,000,000 iterations, and 4,000,000 HMACs in all (iterations × output blocks).
  - **scrypt:** at most 64 MB of memory, whatever `maxmem` says.
  - **RSA:** keys of up to 4,096 bits generated and 8,192 bits read.
  - **Data sizes:** 32 MB signed or decrypted (authenticated) at once, and 64 KiB derived.
- **Heavy work:** PBKDF2, scrypt and key generation run on the node's small crypto pool. The `…Sync` forms wait for it too, so a job can't tie up more than its share of the node.
- **Nothing reaches outside the job:** it's computation only. A key lives in the job as its bytes, is handed to the host with each call, and is never kept. An execution can have at most 1,000 hashes and ciphers open, and nothing is shared between jobs.

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
  body: { name: 'thing' },          // an object is sent as JSON; a string as is; bytes as bytes
  timeoutMs: 10_000,                // at most 30 s
});
res.status; res.ok; res.headers.get('content-type');
const data = await res.json();      // or res.text(), res.bytes(), or stream res.body
```

- Only `http` and `https`.
- **Every connection goes through the egress gate** (D-108), and a refusal fails with `code: 'egress_denied'` and a message that says why:
  - **Public addresses** are allowed.
  - **Private and reserved addresses are refused:** loopback, 10/8, 172.16/12, 192.168/16, link-local (including the 169.254.169.254 cloud metadata endpoint), CGNAT, unique-local IPv6, the documentation ranges, and IPv6 forms that carry one of these (NAT64, 6to4, IPv4-mapped and IPv4-compatible). An operator can allow specific ones for your tenant: a CIDR or an exact host name, on specific ports.
  - **The cluster's own nodes are always refused,** on every port and whatever their address, and no rule can allow them.
  - **See your tenant's policy** with `GET /v1/egress-policy` or `automately egress`: the defaults and why, your rules, and the limits.
- The connection goes to the exact address that was checked, so DNS tricks can't get around this.
- Redirects are **not followed**: a 3xx comes back as is.
- The response body streams (`res.body`), with no size limit when read as a stream. `text()`, `json()` and `bytes()` buffer it, up to 5 MB.
- Request bodies are strings, bytes (a `Uint8Array` or any typed array, or an `ArrayBuffer`), `URLSearchParams` (sent as a form) or JSON, up to 1 MB.
- At most 16 fetches run at once per script, counting a fetch until its body has been read, cancelled, or the script ends. An unread body is cancelled when the script ends.
- The timeout applies to connecting and to any pause between chunks, so long downloads work.
- For local development, `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true` lifts the private-address check for every tenant. Even then, a node's own ports (the API, the ingress, the internal endpoint, metrics and the cluster's ports) stay refused.
- Job completion callbacks go through the same gate, as the job's tenant.

## `log`: structured logging

```js
log.info('order placed', { orderId: 7, total: 19.5 });   // also debug, warn, error
```

Each call is a log line (`order placed {"orderId":7,"total":19.5}`). The live event stream carries `message` and `fields` separately. `console.log` still works too.

**Where a job's log goes** (`console.*` and `log.*` alike):
- **Live:** each line is an event on the job's stream (`GET /v1/jobs/{id}/events`, `automately jobs logs`, `services logs`, MCP `job_logs`), with its level and a sequence number. A client that connects mid-run first gets the recent lines, then the live ones, with none repeated.
- **Kept:** when the attempt ends, its log is stored on the job record (`GET /v1/jobs/{id}` → `logs`) until the job is deleted by retention (`AUTOMATELY_JOB_RETENTION_DAYS`, 7 by default).
- **Limits (D-107):**
  - **Per line:** about 16 KiB. A longer line is cut, and ends with `… [N more characters]`. A `log.*` call whose fields are over 16 KiB keeps the message and replaces the fields with `{"fieldsOmitted": "N characters"}`.
  - **Per attempt:** the last 1,000 lines, and at most 1 MiB of them. Older lines are dropped as new ones arrive.
- **Each attempt starts a new log.** A retried or resumed job keeps only its last attempt's lines.
- **Stored lines lose their level;** the live events have it.
- **Secrets** declared in `env` are masked as `[secret:name]` before a line is kept or sent (D-096).
- **A script can't read its own log back.** Keep what you need in `kv` or `files`.

## `files`: file storage

```js
await files.write('reports/q3.csv', csvText, { contentType: 'text/csv' });   // string, bytes, JSON, or a stream
await files.write('uploads/movie.mp4', req.body);                           // a request body, streamed in
await files.write('cache/logo.png', await fetch(url));                      // a fetch response, streamed in
const bytes = await files.read('cache/logo.png');                           // Uint8Array (buffers, up to 32 MB)
const text  = await files.text('reports/q3.csv');                           // also files.json(path)
for await (const chunk of files.stream('uploads/movie.mp4')) { ... }        // any size
const info  = await files.stat('uploads/movie.mp4');  // {path, size, contentType, etag, createdAt}, or null
const { files: list, cursor } = await files.list({ prefix: 'uploads/', limit: 100 });
await files.delete('uploads/movie.mp4');                                    // true if it existed
```

- **Serving a file.** Return `files.stream(path)` from a `ctx.serve` handler, or `{ headers, body: files.stream(path) }` to add headers such as `cache-control`. Java serves it directly, so the bytes never pass through your script. You get `Content-Length`, `ETag`, `Range` requests (206, so video seeking works), `If-None-Match` (304), `HEAD`, and a 404 if the file doesn't exist.
  ```js
  await ctx.serve(async (req) => {
    if (req.method === 'PUT') return { status: 201, body: await files.write('media' + req.path, req.body) };
    return { headers: { 'cache-control': 'max-age=3600' }, body: files.stream('media' + req.path) };
  });
  ```
- **Writes are atomic.** A reader sees the old file or the new one, never a mix, and a read already in progress finishes even if the file is replaced or deleted meanwhile. A write that fails or is abandoned leaves nothing behind.
- **Paths** are relative, at most 1 024 bytes, with no `.`, `..` or empty segments, backslashes or control characters.
- **Limits.**
  - Each tenant has `AUTOMATELY_TENANT_STORAGE_MB` (1 GiB by default) for files and `kv` together. Going over fails with `code: 'limit_exceeded'`.
  - If the cluster itself is running out of disk (or memory, without a data directory), writes fail with `code: 'insufficient_storage'`.
  - Either way, the node keeps running.
- **Where bytes are stored:** in the cluster by default, replicated and surviving node loss like the rest of its data. Your tenant can route paths to its own S3 bucket or disk folder ([storage guide](../guides/storage.md)); scripts don't change, since `files` follows the routes.

From outside the cluster:
```http
PUT    /v1/files/reports/q3.csv          (body streamed) → 201 {"path", "size", "contentType", "etag", "createdAt"}
GET    /v1/files/reports/q3.csv          → 200, or 206 with Range, 304 with If-None-Match
GET    /v1/files?prefix=reports/&limit=100 → {"files": [...], "cursor": ...}
DELETE /v1/files/reports/q3.csv          → 204
Authorization: Bearer atly_…
```
Uploads over the quota get **413** (straight away when `Content-Length` is sent), and uploads when the cluster is out of room get **507**.

## `fs`: the file system

Your files as one tree from `/`, Node-shaped, with your job's own `/tmp` inside it (D-079). It's the same files as `files` and the REST API, so a script, the CLI and the console all see the same folders.

```js
import { readFileSync, writeFileSync, existsSync } from 'fs';     // or 'node:fs', or 'automately:fs'; fs is also a global
import { mkdir, readdir, rename } from 'fs/promises';

await mkdir('/reports/2026', { recursive: true });
writeFileSync('/reports/2026/q1.csv', 'a,b\n1,2');
fs.appendFileSync('/reports/2026/q1.csv', '\n3,4');
const text = readFileSync('/reports/2026/q1.csv', 'utf8');
for (const d of await readdir('/reports', { withFileTypes: true })) console.log(d.name, d.isDirectory());
await rename('/reports/2026', '/archive/2026');                   // a whole folder; no bytes move
fs.writeFileSync('/tmp/scratch.bin', bytes);                        // this job only, gone when it ends
```

- **Calls:** `readFile`, `writeFile`, `appendFile`, `mkdir`, `readdir`, `stat` (and `lstat`), `access`, `rename`, `copyFile`, `rm`, `rmdir`, `unlink`.
  - **Three styles each:** `fs.promises.x`, callback-style `fs.x(…, cb)`, and the synchronous `fs.xSync`.
  - **Also:** `fs.existsSync`.
- **Errors:** they have Node's codes, so code written for Node handles them. The codes are `ENOENT`, `EEXIST`, `ENOTDIR`, `EISDIR`, `ENOTEMPTY`, `EROFS` (a read-only mount), `EACCES` (a reserved name), `ENOSPC` (quota) and `ETIMEDOUT`. The cluster's own code is in `e.reason`.
- **Synchronous calls block your script** until the host answers, which takes at most 30 s. Other work in the same script waits meanwhile: its timers, and in a service the other requests it's handling. Use the promise forms in services, and the sync ones where Node code needs them.
- **`/tmp`:** private to the job, and deleted when it ends.
  - **Size:** 8 MB is held in memory. Beyond that it spills to the node's disk, up to 1 GB (`AUTOMATELY_TMP_MB`), on nodes with a data directory, or 64 MB in memory on nodes without one.
  - **Elsewhere:** it isn't part of your stored files. The REST API never sees it, and it doesn't count against your quota.
- **Limits:**
  - `readFile` reads at most 32 MB into memory; stream bigger files with `files.stream`.
  - `appendFile` edits files up to 64 MB. Each append is a new version, published at once, so readers never see half of one.
- **Paths:** they may start with `/`, and follow the `files` rules. `tmp/`, `packages/` and `.packages/` are reserved, and `mnt/` holds mounts ([storage](../guides/storage.md)).
- **File handles** (`fs.promises.open`, `fs.openSync` / `readSync` / `writeSync` / `closeSync`) do positional reads and writes on files up to 32 MB. Changes are published as one new version when you close the handle; until then, readers see the old one.
- **Locks:** `const lock = await fs.lock('/index/rebuild', { ttlMs: 60000 })`, then `lock.renew()` and `lock.release()`.
  - **Contention:** another holder gets `EBUSY`.
  - **Release:** a lock expires on its own, and is released when your job ends.
  - **Advisory:** only code that takes the lock is held back; writes don't check it.

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
