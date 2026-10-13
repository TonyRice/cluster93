# Design and plan: preserving Cluster93's core scripting features

*Planned 2026-10-07, before the phase 5 demo. Tony chose the queue API shape and the scope (all four parts, then the demo).*

## Why
A review of the 2017 Nashorn code against the rewrite found three core features that weren't carried over:

| 2017 | Where | Now |
|---|---|---|
| **Completion callbacks.** A job's `callbackUrl` (from its config or `Cluster93.setCallbackUrl()`) got a POST when the job finished | `JobServer.java` around the `callbackUrl` POST, `Cluster93Object.setCallbackUrl` | Missing: callers poll `GET /v1/jobs/{id}` or follow the event stream |
| **Jobs starting and following jobs** from a script: `Cluster93.startJob(script, config, callback, wait)`, `getJob`, `stopJob` | `Cluster93Object.java` | Missing |
| **Messaging between jobs and services**: `messagebus` with `registerHandler`, `publish`, `send` with a reply and a timeout | `MessageBusObject.java`, `MessageObject.java` | `bus` covers publish/subscribe and request/reply, at most once. There's no queue: a message nobody listens for is lost |

The review also found that the APIs scripts see were built from one 475-line JavaScript string over a generic `call(op, json)`. In 2017 they were Java objects. Tony asked for objects again.

## The four parts, in order

### 1. Host APIs as Java objects (D-060) — done
- **Core:** one Java object per API (`KvApi`, `BusApi`, `FilesApi`, `FetchApi`, `HttpApi`, gathered in `HostApis`), implemented on the node with the tenant scoping in one shared `HostScope`. No V8 types, so another language's runtime can bind the same objects.
- **Javet:** one binding per API with `@V8Function` methods. Each returns a promise created in Java and settled on the isolate's event loop. `bind()` exposes only those methods, never reflection.
- **JavaScript:** small files, only where JavaScript has to be idiomatic (freezing, `fetch`'s Response, streams, `ctx`), instead of one string.
- **Check:** behaviour unchanged. The whole runtime and host suites pass, the hostile-script suite included.

See [host-apis.md](host-apis.md#mechanism-as-of-d-060).

### 2. Bus modes: `bus` (default) and `bus.queue(name)` (durable) (D-061) — done
```js
// default mode (unchanged, ephemeral, at most once)
await bus.publish('orders', order);

// queue mode (durable, at least once)
const q = bus.queue('emails');
await q.push({ to, subject });
await q.consume(async (msg, { attempt }) => {
  await send(msg);      // resolving acknowledges it
});                     // throwing retries it later
await q.stats();        // { ready, inFlight, dead }
```
- **Storage:** messages are stored in the cluster (Ignite), replicated like jobs.
- **Delivery:** consumers compete, so each message goes to one of them.
- **Claiming:** a compare-and-set on the message (READY → IN_FLIGHT with an owner and a deadline), like the job store's fenced transitions (D-010, D-011).
- **Retries:** a message comes back with backoff when its handler throws or when its deadline passes, for example because the consumer's node died. After `maxAttempts` it goes to a dead-letter list (`q.dead()`, `q.retry(id)`).
- **Waking consumers:** a push sends a hint on the event bus, so idle consumers claim it at once. A slow sweep covers missed hints and expired deadlines.
- **Limits:** messages are JSON of at most 256 KB, like the bus. Queued bytes count toward the tenant's quota (D-043).
- **REST and tools:** `POST /v1/queues/{name}`, `GET /v1/queues/{name}`, and the dead-letter endpoints, with scopes `queues:read` and `queues:write`, plus CLI commands and MCP tools.
- **As built:** there's no separate sweeper; the next claim takes back a message whose lease ran out. Consumers extend leases while handlers work, and a script that ends mid-message gives the message back without counting an attempt. See [the reference](../scripting/host-apis.md#busqueuename-durable-queues).

### 3. Completion callbacks (D-062) — done
- **Setting one:** `POST /v1/jobs` (and services) accept `callback: { url, data? }`, and a script can set its own with `ctx.setCallback(url, data)`, as `setCallbackUrl` did in 2017.
- **Delivery goes through an internal queue (part 2),** so retries and failover are shared. Whoever records the terminal status enqueues the delivery: the node finishing the job, the reaper orphaning it, or a cancel.
- **The POST:**
  - **Body:** `{ jobId, status, output, error, attempts, data }`. `data` comes back as it was sent.
  - **Signature:** `Cluster93-Signature: t=…,v1=HMAC-SHA256(secret, t + "." + body)`.
  - **Network:** the request passes the egress policy (D-026).
  - **Retries:** a failed POST is retried with backoff, then given up and counted.
- **Secret:** one per tenant (`GET /v1/callbacks/secret`, `POST …/rotate`), sealed like storage credentials (D-057).
- **As built:** a `JobStore` wrapper queues the delivery on any update that ends a job with a callback, which covers every way a job ends in one place. Secrets live in the replicated kv store under the system tenant, created with put-if-absent. A refused address fails at once. See the [callbacks guide](../guides/callbacks.md).
- **On the job:** `callback.deliveredAt`, `callback.attempts` and `callback.lastError` are shown.

### 4. A `jobs` API for scripts (D-063) — done
- **Calls:** `jobs.run(script, input, options)` returns `{ id, result(), events(handler), stop() }`, and there are `jobs.get(id)` and `jobs.stop(id)`. `result()` resolves when the job ends; no thread is blocked, unlike 2017's `waitForJob`.
- **Tenant and limits:** started jobs belong to the caller's tenant and count toward its rate limit and quota. They record their parent, and nesting is capped at depth 5.

## Not now (parked in [NOTES.md](../../NOTES.md))
Outside clients subscribing to topics or queues; reply-to-a-reply conversations; modules and `require`; a Python runtime through GraalPy.
