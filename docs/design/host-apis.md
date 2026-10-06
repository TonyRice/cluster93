# Design and plan: host APIs and HTTP hosting (phase 3)

**Status:** ✅ Done (2026-10-06). See "What changed during implementation" at the end.
**Script API:** [../scripting/host-apis.md](../scripting/host-apis.md)

## What 2017 scripts could do (from `factories/js/objects/**`)
| 2017 object | What it did | Phase 3 equivalent |
|---|---|---|
| `DataBusObject` | Named per-user maps, password-protected private maps, caches with expiry, predicate queries | `kv`: namespaced, tenant-scoped, TTLs, atomic increment. Queries wait for the durable store |
| `MessageBusObject` | `registerHandler`, `publish`, `send` with reply and timeout | `bus`: `subscribe`, `publish`, `request`, `handle`; also reachable over HTTP |
| `HttpClientObject` | `get`/`post`/`put`/`delete`/`head` | `fetch` (a WHATWG subset) with an egress policy |
| `HttpServerObject` + `ClusteredHttpServer` | A script registers a handler for a host: a generated host, or your own domain proven by a DNS TXT token | `ctx.serve(handler)` in a service; automatic host per service; custom domains verified by DNS TXT |
| `console` / logger | Print lines | `console` (unchanged) plus `log.info(msg, fields)` structured logging |

`FileSystemObject`, `SmtpClientObject`, `TcpSocketObject` and the container objects aren't carried over now.

## Principles
1. **Every call is tenant-scoped on the Java side.** A script never names a tenant; the node builds each execution's host services for the job's tenant, and every key, topic and route is prefixed with it.
2. **Host calls are asynchronous.** The script gets a promise. The Java side runs the operation on Vert.x and posts the result back to the isolate's event loop (D-019), so `Promise.all([fetch(a), fetch(b)])` really runs in parallel. Phase 2's `ctx.step`/`saveCheckpoint` stay synchronous because they must be durable before the script continues.
3. **Pending host calls keep a job alive.** A job waiting on a fetch, a bus request or a subscription isn't "never settles".
4. **Limits are explicit and tested.** Sizes, counts, timeouts and egress rules each have a hostile-script test.
5. **Engine-neutral core.** The operations live in `automately-core` (`HostServices` SPI). The Javet runtime only bridges them into JavaScript.

## Mechanism
- `HostServices` (core SPI): `Future<Object> call(String operation, JsonObject args)` plus `subscribe` and `close`. The node creates one per execution (tenant, job, attempt).
- **JavaScript side.** The prelude exposes `host.call(op, argsJson, callId)` and keeps a map from `callId` to the promise's resolve/reject.
- **Java side.** It runs the future and, on completion, posts a task to the event loop that calls `settle(callId, ok, resultJson)`. The loop counts in-flight calls; while any are pending, it waits instead of failing.
- **Subscriptions** (bus, HTTP handlers) are delivered the same way, as posted tasks that call back into JavaScript. Replies go back through a reply id.
- **Cleanup.** When the run ends, all subscriptions are unregistered and in-flight calls are abandoned. Their results go nowhere, because the isolate is closed.
- **Fencing.** The node's heartbeat already cancels superseded attempts (D-020). Host writes from a zombie are therefore possible for at most about one heartbeat; that's documented, the same as for outside side effects.

## `kv`
- The store is `KvStore` (core SPI) with a `SharedDataKvStore` implementation (cluster map). Its key is `tenant`, namespace and key joined with a separator that can't appear in them.
- Operations: `get`, `set(value, {ttlMs})`, `delete`, `list({prefix, limit, cursor})`, `increment(by)` (a compare-and-set loop).
- Limits: key ≤ 512 bytes, value ≤ 256 KB of JSON, `list` ≤ 1 000 per page.
- `list` scans the whole map, which is fine at current scale and listed as a gap until the durable store arrives.

## `bus`
- Topics are tenant-scoped event-bus addresses (`automately.t.<tenant>.bus.<topic>`). Topic names match `[a-zA-Z0-9._-]{1,128}`.
- `publish(topic, message)`, `subscribe(topic, handler)` (returns an unsubscribe function), `request(topic, message, {timeoutMs})` (the first `handle`r answers), and `handle(topic, handler)` (the handler's return value is the reply).
- Messages are JSON, ≤ 256 KB.
- From outside: `POST /v1/bus/{topic}` publishes, and `POST /v1/bus/{topic}/request` waits for one reply (≤ 30 s). Services can be called over HTTP this way.

## `fetch`
- Built on the Vert.x core `HttpClient`, so core stays free of `vertx-web`. Supports `method`, `headers` and `body` (a string, or an object as JSON). The response has `status`, `statusText`, `ok`, `headers`, `text()` and `json()`.
- **Egress policy (SSRF protection).**
  - Only `http` and `https`.
  - The host is resolved first, and private, loopback, link-local, multicast and metadata addresses (for example `169.254.169.254`) are refused.
  - The connection goes **to the resolved address** (with the original `Host` header and TLS SNI), so DNS rebinding can't swap in an internal address after the check.
  - `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true` turns this off for local development.
- Limits: 30 s timeout, response ≤ 5 MB, ≤ 16 concurrent fetches per isolate, no redirects followed. A 3xx is returned as is, so a redirect can't be used to bounce into an internal address.

## HTTP hosting
- A service calls `ctx.serve(async (request) => response)` once. Requests then reach that run's isolate.
- **Ingress.** Every node also listens on `AUTOMATELY_INGRESS_PORT` (default 8000).
  1. A request's `Host` is looked up in the route table, which gives the tenant and service.
  2. The service's current run gives the node instance.
  3. The request is forwarded over the event bus to that node, which posts it to the isolate.
  4. The handler's response travels back the same way.
- **Hosts.**
  - Every service gets an automatic host, `<service>--<tenant>.<AUTOMATELY_INGRESS_DOMAIN>` (default domain `localhost`).
  - Custom domains are added with `POST /v1/services/{name}/domains`. They're served only after **verification**: a DNS TXT record `_automately.<domain>` containing the token the API returned (the 2017 anti-hijack check).
  - A domain belongs to one tenant at a time.
- **Request and response.**
  - The request has `method`, `url`, `path`, `query`, `headers`, `text()` and `json()`.
  - The handler returns a string (200, `text/plain`), an object `{status, headers, body}` (an object `body` becomes JSON), or throws (500).
  - Limits: request and response bodies ≤ 1 MB (event-bus messages), 30 s per request, ≤ 64 concurrent requests per isolate. No streaming yet.
- **Errors.** Unknown host: 404. A service that's stopped or restarting, or hasn't called `serve`: 503 with `Retry-After`.

## Isolate pooling: not doing it (D-024)
Measured on the Javet runtime: a trivial job end to end, including creating and closing its isolate, is **6.6 ms p50, 9.9 ms p90 and 15.8 ms p99**. The first isolate in a process takes about 390 ms while the native library loads. HTTP requests go to a service's long-lived isolate, so they never pay even that. Reusing isolates across jobs would put isolation between jobs at risk to save milliseconds. Revisit only if phase 5 benchmarks show otherwise.

## Work breakdown
1. **Mechanism.** `HostServices` SPI; async host calls, settlement and in-flight counting in the event loop; subscriptions; cleanup.
2. **`log` and `kv`.** `KvStore` and its SharedData implementation, the host operations, JS bindings, and limit tests.
3. **`bus`.** Host operations, JS bindings, the HTTP publish/request endpoints, and tests across nodes.
4. **`fetch`.** HTTP client, egress policy (including the rebinding defence) and limits. Tests against a local server, with private ranges refused by default.
5. **HTTP hosting.** Route table, ingress verticle, node-side request delivery, `ctx.serve`, automatic hosts, domains with verification (a pluggable TXT resolver), 404/503 behaviour, and tests across nodes. `DockerClusterIT`: traffic keeps flowing after the serving node is `SIGKILL`ed.
6. **Docs.** CHANGELOG, DECISIONS, README and the scripting guide.

## What changed during implementation
- **Automatic hosts are `<service>--<tenant>.<domain>`, so names can't contain `--` (D-028).** That makes the split unambiguous without storing routes. Tenant and service validation now enforce it.
- **Deleting a service releases its custom domains.** HostApisTest caught a re-created service inheriting a deleted one's verified domain.
- **The ingress routes by the request authority (D-029).** On HTTP/2 the host arrives as `:authority`, not as a `Host` header. A cleartext HTTP/2 request is now part of HostApisTest.
- **The fetch size limit is reported as `RangeError`/`limit_exceeded`.** The first version reset the connection before recording the limit, so the script saw a generic network error. A test caught it.
- **Stored `log` fields:** the job's stored log keeps the line with its fields as JSON; the live SSE event carries `message` and `fields` separately.
- **Docker.** Containers expose the ingress (8000), and the integration test waits on the API port explicitly. `DockerClusterIT` gained "HTTP traffic moves to a survivor after the serving node is `SIGKILL`ed".
