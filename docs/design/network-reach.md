# Network reach in the isolate (phase B): a design, reviewed for security first

**Status:**
- **B1, the egress gate and allow-lists:** ✅ built (D-108). Tony decided its shape on 2026-10-08 (D-095 §4.5).
- **B2, the `http`/`https` client:** ✅ built (D-109), with axios and node-fetch matching real Node. Unhandled rejections fail jobs as in Node (D-110).
- **Configurable address spaces (§10):** ✅ built (D-111, D-113).
- **got and real socket details (§11):** ✅ built (D-114). The isolate reports a Node version so got loads (D-115).
- **B5, `net` and `tls` sockets (§7):** ✅ built (D-116), with pg, mysql2, ioredis, MongoDB and nodemailer matching real Node against real servers.
- **The `http2` client (§12):** ✅ built (D-117), with got's `http2: true` matching real Node. Request trailers, `state`, priorities and the frames' real flags as Node 26 has them (D-118).
- **Servers reached by hostname with intercepted ports (§5, D-112):** accepted (Tony, 2026-10-08).
- **The rest, accepted with Tony's answers in §8** (2026-10-08):
  - got and real socket details (§11)
  - sockets, B5 (§7)
  - the `http2` client (§12)
  - `dns`, B4 (§6)
  - servers, B3 (§5)
  - WebSockets (§13)

  The order is in §9.

**Where it comes from:** [isolate-polyfills.md](isolate-polyfills.md) §3. Phase A gave the isolate pure computation (D-095 to D-106). Phase B gives it the network: Node's `http`, `https`, `net`, `tls` and `dns`. Those are what database drivers (`pg`, `mysql2`, `ioredis`), HTTP clients (axios, got, node-fetch) and web frameworks (Express, Koa, Fastify) are written against.

**Tony's rule** (D-095): "anything trying to open network access should not be able to access private things on our cluster unless explicitly allowed to."

## 1. What a script can reach today
- **`fetch`** (D-026). It resolves the name, refuses private and reserved addresses, connects to the address it checked (so DNS rebinding can't swap one in), and follows no redirects. Request bodies are strings of at most 1 MB.
- **`ctx.serve`,** only in services. It's inbound HTTP through the cluster's ingress (D-029).
- **S3 storage providers and job callbacks.** Both use the same address check.
- **The switch:** `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true` lifts the check for every tenant at once. It exists for local development.

**What's missing:** an operator can't let one tenant reach one internal database, so a tenant with a private network has no way in short of the global switch. And nothing refuses the cluster's own nodes when they sit on public addresses.

## 2. Threats
- **SSRF into the cluster:** the internal endpoint, the API, the metrics port, the event bus, and Ignite's discovery and communication ports. Mutual TLS protects most of them (D-066), but with `AUTOMATELY_CLUSTER_TLS=off` (development) they're plain.
- **SSRF into the operator's network:** cloud metadata (169.254.169.254), databases, admin panels, and the Docker host.
- **Address tricks:** DNS rebinding; IPv6 forms that embed an IPv4 address (IPv4-mapped, IPv4-compatible, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`, Teredo `2001::/32`); decimal and octal forms of IPv4 (which the JVM's parser doesn't accept as names anyway); and redirects.
- **Abuse of the cluster's addresses:** spam (SMTP), port scans, amplification, and attacks on third parties. These matter for raw sockets far more than for HTTP.
- **Exhaustion:** sockets, file descriptors, connection floods, slowloris-style holding, and unbounded buffering on the host's Java heap.
- **Leaks between tenants:** shared connection pools or TLS sessions, and DNS answers about internal names.

## 3. B1: one egress gate for everything (accepted)
Every outbound connection a script causes (`fetch`, `http`, `net`, `tls`, and `dns` answers) goes through one gate in core, `Egress`. It gets the tenant, the requested name, its resolved addresses and the port, and answers with the one address to connect to, or a refusal that says why.

**For each resolved address, in order:**
1. **A cluster node: refused, always,** on every port. "A cluster node" means:
   - an address any live node advertises (its internal endpoint's host, resolved);
   - any address of this node's own network interfaces, including loopback.

   Every port, because the event bus listens on a random one, and nothing on a node is meant for scripts. **Development is the one exception:** with `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true`, loopback and the node's interfaces are refused only on the node's own ports (API, ingress, internal, metrics, and the cluster's discovery and communication ranges), so a developer can still reach a mock server on `localhost:3000`.
2. **Public unicast: allowed.**
3. **Private or reserved:** allowed only if
   - the tenant's egress policy has a matching rule, or
   - the development switch is on.

   The reserved list gains these forms: IPv4-compatible IPv6 (`::/96`), NAT64 (`64:ff9b::/96`), 6to4 (`2002::/16`), Teredo (`2001::/32`), the documentation ranges (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`) and broadcast. Each embedded-IPv4 form is checked by its IPv4 address.

The first allowed address is the one connected to, exactly as `fetch` does today.

**The tenant's egress policy:** set by an operator, read by the tenant.
```json
PUT /v1/tenants/acme/egress-policy        (operator only; audited like every change, D-071)
{ "allow": [
    { "to": "10.0.5.20/32",      "ports": [5432] },
    { "to": "db.internal.example", "ports": [5432, "6379"] },
    { "to": "10.1.0.0/16",       "ports": ["8000-8099"] } ] }

GET /v1/egress-policy                     (the tenant, read-only)
```
- **The tenant's view explains the whole policy, not only its rules** (Tony, 2026-10-08). `GET /v1/egress-policy` answers what applies to that tenant:
  - **The defaults:** public addresses are allowed; private and reserved ones are refused; cluster nodes are always refused; SMTP ports are refused for raw sockets.
  - **The tenant's own rules.**
  - **Whether the development switch is on.**
  - **The limits:** requests and sockets per execution, and timeouts.

  Each item carries a short sentence saying why, so a tenant can see why a connection was refused without asking an operator. **The console will show this same response** as the tenant's "Network access" page, so it's written for people as well as programs.
- **`to`:** a CIDR (IPv4 or IPv6), or a host name. A name rule matches only when the script asks for that exact name (case-insensitive). The addresses it resolves to are then allowed, whatever they are, except cluster nodes. The operator is trusting that name's DNS, which is what Tony's "hosts or CIDRs" means.
- **`ports`:** required. Single ports or ranges. There's no "any port", so a rule is always narrow.
- **At most 100 rules per tenant.** Writing the policy replaces it whole. Unless an operator sets one, a tenant's policy is empty.
- **Cluster nodes are never matched,** by any rule.
- **The CLI:** `automately tenants egress <tenant> --allow 10.0.5.20/32:5432 …`, alongside the storage policy.

**Seen and counted:** a refused connection fails with `egress_denied` and a reason (`private address`, `cluster node`, `not in the tenant's egress policy`). Refusals are counted per tenant, in `automately_egress_denied_total{tenant,reason}`.

**`AUTOMATELY_EGRESS_ALLOW_PRIVATE`:** stays for local development, and now logs a warning on every start.

**`fetch`'s request bodies become bytes.** Today a `Uint8Array` body is decoded as UTF-8, which corrupts binary uploads; this was found while designing B2. The 1 MB cap stays for `fetch` until B2's streaming.

## 4. B2: `http` and `https` as clients (accepted: no new reach)
**What it is:** Node's client API on the host's HTTP client, through the same gate:
- **Functions:** `request`, `get`
- **Classes:** `ClientRequest` (a `Writable`), `IncomingMessage` (a `Readable`) and `Agent`
- **Values:** `STATUS_CODES` and `METHODS`
- **Validators:** `validateHeaderName` and `validateHeaderValue`

**Request bodies stream.** A request body is written in chunks with backpressure, like `ctx.serve`'s responses, so an upload isn't capped at 1 MB. `fetch` gains the same: a `ReadableStream`, an async iterable or a `Uint8Array` as its body.

**Limits:**
- 16 open requests per execution, as `fetch` has now
- 30 s to connect and between chunks
- response headers at most 64 KB
- no redirects followed, which is Node's `http` behaviour anyway

**Agents:** `Agent` is accepted and its pooling options are ignored. The host pools connections per node, keyed by the checked address and the TLS name, never shared across tenants' TLS settings.

**TLS:**
- **Always verified,** against the JVM's trust store.
- **`rejectUnauthorized: false` is honoured.** Self-signed internal services and some managed databases need it, and it only affects that tenant's own connection. The job log notes it once per job.
- **`ca`:** custom CAs are accepted.
- **Client certificates (`cert`/`key`):** accepted too, held for the request only.

**Refused, with an error that says so:**
- `createConnection` (it would bypass the gate)
- `CONNECT`, and upgrades to WebSocket
- `socketPath` (Unix sockets)
- `pfx`, `passphrase`, and a custom `checkServerIdentity`

**Ignored (changed while building, D-109):** `lookup`, `family`, `localAddress` and `servername`. The host never calls a script's `lookup`, so ignoring it bypasses nothing, and libraries that pass their own (got's `cacheable-lookup`) still load. Refusing them would only break those libraries.

**Proven against real Node** by `RealNpmPackagesTest`, against a local test server: `http.get`/`request`, axios (its Node adapter) and node-fetch all match. `got` isn't tested: it needs `http2-wrapper` and socket timing events (a documented gap).

**What building it added (D-109):**
- **Stand-ins for `net`, `tls`, `http2` and `tty`, for their pure parts,** because axios and its proxy agent import them at load. Connecting through them throws until B5 (`net`, `tls`) or always (`http2`, `tty`).
- **A missing built-in that CommonJS `require()`s throws when required,** instead of stopping the publish. Libraries try optional modules and fall back (fetch-blob's `stream/web`, node-domexception's `worker_threads`).
- **`fs.createReadStream`/`createWriteStream`,** which fetch-blob imports.
- **A throw in a `queueMicrotask` or `process.nextTick` callback, or in an `http` listener, fails the job,** as in Node. Before, it vanished into a promise, and the job looked hung.
- **`process` reports itself as `[object process]`,** which is how axios picks its Node adapter.
- **Tests use the development switch,** not a policy rule as planned: the test server is on loopback, which is the node itself, and no rule can reach a node.

## 5. B3: servers, reached by hostname only (accepted, D-112)
**No script ever opens a port on the cluster.** Inbound traffic arrives only through the cluster's HTTP ingress, which picks the service by hostname. A script's `listen(port)` is intercepted, and the script never learns how its traffic reaches it (Tony, 2026-10-08).

**The route table, one for both runtimes:** a route is `(tenant, service, declared port) → (node, backend endpoint)`. It's kept with the service's records and cached by every node's ingress, as `HostRouter` and `ServiceStore` route today.
- **Isolates have no operating-system socket at all.**
  - **Registering:** `listen(3001)` registers the server under `(job, 3001)`. `ServeRegistry` becomes keyed by job and port.
  - **The backend endpoint:** the node's internal endpoint plus a random, opaque route id that the ingress forwards on.
  - **Why no port:** nothing is open on the node to scan or reach directly, which is stronger than a random port.
  - **What the script sees:** `server.address()` gives the port it asked for. `listen(0)` gets a random virtual port from 1024 to 65535.
- **Node containers (node-runtime.md) bind for real,** inside their own network namespace.
  - **The reserved range:** each node keeps one, `AUTOMATELY_SERVICE_PORTS` (default `20000-29999`), and picks a random free port in it for each container port. The container sees 3001; the node uses, say, 24517.
  - **Unreachable from scripts:** the range is added to the node's ports in `ClusterAddresses`, so the gate refuses it to every script, even with the development switch on.

**How each port is reached:**
- **The default is one hostname per port:**
  - **The first server** gets the service's host, `<service>--<tenant>.<domain>`.
  - **Each other port** gets `<service>--<tenant>--<port>.<domain>`.
  - **No ambiguity:** names can't contain `--`, so splitting on it is unambiguous.
  - **Certificates:** it's one label deep, so the wildcard certificate covers it.
- **The service definition can change that per port,** with `ports: [{ "port": 3001, … }]` and these options:
  - **`"path": "/admin"`:** served under the main host's path prefix. The ingress strips the prefix and sends `X-Forwarded-Prefix`.
  - **`"domains": [...]`:** custom domains, verified as domains are today and pointing at a service and port.
  - **`"expose": false`:** not reachable from outside.
- **An undeclared port** gets the default hostname.
- **Seeing the routes:** the API, the CLI and later the console list each service's routes (host, path, domain and port).

**HTTP servers in the isolate:**
- **The server:**
  - `http.createServer` with `listen(...)` in any of Node's forms. `ctx.serve` is the same thing without a port, which means the main host.
  - A second `listen` on the same port fails with `EADDRINUSE`.
  - Outside a service, `listen` throws.
- **`req`** is an `IncomingMessage`:
  - **Fields:** `method`, `url` (the path and query), `headers` (Node's rules), `rawHeaders` and `httpVersion`.
  - **The socket stand-in:** carries the client's address, from the ingress's `X-Forwarded-For`, and `encrypted`.
  - **The body** streams with backpressure.
- **`res`** is a `ServerResponse` with Node's whole header and body API: `writeHead` in all its forms, repeated headers, `write` returning false until the host takes the chunk, then `drain`, `addTrailers`, and the `finish` and `close` events.
- **Real prototypes,** so Express's `Object.setPrototypeOf(res, app.response)` works.
- **`https.createServer` is accepted.** `key`/`cert` are ignored with a log note, since the ingress already handles TLS.
- **How the run lasts:**
  - **A listening server keeps a service's run alive** after its default export returns, until it's stopped or `server.close()` is called.
  - **A stop request** emits `SIGTERM` on `process`, then the usual 5 s grace period.
  - **A service module that calls `listen`** needs no default export, so a plain Express `app.js` runs unchanged.
- **`node:async_hooks`:**
  - **`AsyncResource`** is provided (`bind`, `runInAsyncScope`), which is what on-finished, raw-body and Fastify use.
  - **`AsyncLocalStorage.run()` throws "not supported":** carrying a value across `await` needs a V8 hook Javet 6 doesn't expose, and losing the value silently would be worse.

**What `ctx.serve` needs first** (found reading `ServerExchange`):
- **Repeated response headers:** a header has one value today, so several `Set-Cookie`s can't be sent.
- **A script's `Content-Length`** is kept, and HEAD is answered without a body.
- **The request's raw headers** and **the client's address.**
- **An event when the client disconnects.**
- **Trailers.**
- **`Expect: 100-continue`,** answered automatically.

**No new reach:** inbound traffic still comes only through the ingress, with its limits, its domains and its tenant routing.

**Raw TCP and UDP servers: never on core nodes** (Tony, 2026-10-08).
- **What scripts get:** `net.createServer`, `tls.createServer` and `dgram` throw an error saying so.
- **Where they belong:** plain TCP and UDP ports exist only on untrusted nodes, such as an Automately Link running on its own hosted server. They belong to the links design, not phase B.
- **Node containers on core nodes are the same:** they accept only HTTP and WebSockets (node-runtime.md §12).

**Proven against:** Express 5, Koa (with @koa/router) and Fastify in `RealNpmPackagesTest`.
- **The comparison:** each runs under real Node, listening on a port, and in a service through the ingress. The same requests go to both, and status, headers (except `Date`) and bodies must match.
- **The requests cover:**
  - routes and parameters
  - JSON and urlencoded bodies
  - several cookies
  - redirects, 404s and error handlers
  - streaming responses and HEAD
  - a large upload
  - a client that leaves mid-response

## 6. B4: `dns`, filtered (accepted)
- **One resolver in core, shared by the gate and `dns`,** so `dns` can never answer differently from what a connection gets.
  - **What it is:** Vert.x's `DnsClient`, which covers A, AAAA, CNAME, MX, TXT, SRV, NS and PTR.
  - **Where it asks:** the system's resolver, or `AUTOMATELY_EGRESS_DNS` when that's set.
- **Filtering,** as proposed in §8.2:
  - **Addresses:** only those the tenant could connect to, by the rules of §10.
  - **Internal names** answer `ENOTFOUND`.
  - **Reverse lookups:** only for addresses the tenant could connect to.
  - **Record answers:** MX, SRV, CNAME and NS targets in internal zones are dropped.
- **The JavaScript modules, `dns` and `dns/promises`:**
  - **`lookup`,** with `family`, `all`, `order` and `verbatim`.
  - **The `resolve*` functions:** `resolve`, `resolve4`, `resolve6`, `resolveCname`, `resolveMx`, `resolveTxt`, `resolveSrv`, `resolveNs`, `resolvePtr` and `reverse`.
  - **`Resolver`:** `getServers` answers. `setServers` is refused, since a script can't pick its resolver: that would bypass the filtering.
  - **Errors:** Node's codes.
- **Limits:** 16 queries in flight per execution, answers capped at 64 KB, and a 10 s timeout.
- **Known differences:**
  - **No TTLs:** `{ ttl: true }` gets no real TTLs, which Vert.x doesn't expose.
  - **Not offered:** `resolveCaa`, `resolveNaptr`, `resolveSoa` and `resolveAny`, which are refused with `ENOTIMP`.
- **What it unlocks:** `mongodb+srv://` (SRV and TXT records), tested with a Netty DNS test server against a MongoDB container.

## 7. B5: outbound `net` and `tls` (built, D-116)
**What it is:** real sockets on Vert.x's `NetClient`. The functionality is all of Node's; the only restrictions are where a socket may go (the gate) and how much it may use (limits).

**The host:**
- **Connecting:** `connect` goes through the gate, which answers with the address to use, and connects to exactly that address.
- **Writing:** `write` completes when Vert.x has taken the bytes, which is the backpressure.
- **Reading:** data is a paused stream the script acknowledges. At most 64 KB is held on the host per socket before the Vert.x socket is paused.
- **Closing:** `end` (half-close) and `destroy`.
- **Upgrading:** `upgradeTls` turns a live plain socket into TLS (`NetSocket.upgradeToSsl`), which is how pg, mysql2 and SMTP's STARTTLS work.
- **TLS:**
  - **Name and trust:** SNI, the JVM's trust store, or `ca`. Client `cert`/`key` are accepted.
  - **Identity:** the host name is checked against the certificate.
  - **Opting out:** `rejectUnauthorized: false` is noted once in the job's log.
  - **Also passed through:** ALPN, and `minVersion`/`maxVersion`.

**Node's shapes:**
- **`net.Socket`,** a real `Duplex`:
  - **Connecting:** `net.connect` and `createConnection`. `path` (Unix sockets) is refused.
  - **Events:** `lookup`, `connect`, `ready`, `data`, `end`, `close`, `error`, `timeout` and `drain`.
  - **Behaviour:** `allowHalfOpen`, `setTimeout`, `setNoDelay`, `setKeepAlive`, `ref`/`unref`, and the address and byte counters.
  - **Errors:** Node's codes.
- **`tls.connect` / `TLSSocket`:**
  - **Upgrading:** `tls.connect({ socket })` upgrades an existing socket.
  - **Events and state:** `secureConnect`, `authorized`, `getPeerCertificate`, `getProtocol`, `getCipher` and `alpnProtocol`.
  - **Helpers:** `createSecureContext`, `checkServerIdentity` (Node's algorithm) and `rootCertificates` (from the JVM's trust store).
  - **Refused:** session reuse, `renegotiate`, `exportKeyingMaterial` and PSK.
- **Proxy agents work unchanged:** they `net.connect` to the proxy, write `CONNECT` themselves, then call `tls.connect({ socket })`. Both connections go through the gate.

**Limits:**
- **Sockets:** 16 per execution, and 256 per tenant per node.
- **Timeouts:** 30 s to connect, and **no idle timeout,** as in Node (changed from the 5 min first proposed). A database or Redis connection in a service sits idle for long stretches. Every socket is closed when its run ends, and TCP keepalive finds dead peers.
- **Connect rate:** 120 new connections a minute per tenant per node.
- **The SMTP block:** a default cluster deny rule (§10).
- **Counted** per tenant, in `automately_egress_bytes_total{tenant,direction}`.

**What it unlocks:** `pg`, `mysql2`, `ioredis`, `mongodb` (when its optional native parts are absent), `nodemailer`, and proxy agents. Each is tested against Testcontainers Postgres (also over TLS), MySQL, Redis and MongoDB, plus a mock SMTP server with STARTTLS, and must give real Node's results.

**Never, on core nodes:**
- `net.createServer`, `tls.createServer` and `dgram` (§5)
- Unix sockets
- `http`'s `createConnection` option

- 64 KB of host-side buffering per direction, with the script's reads paused beyond that
- bytes counted per tenant: `automately_egress_bytes_total{tenant,direction}`

**Ports, and connect rate:** the open question in §8.

**Never:**
- `net.createServer` / `listen` (inbound is HTTP through the ingress)
- `dgram` (UDP)
- Unix sockets
- `tls.createServer`
- `http2`

**What building it settled (D-116):**
- **The host judges the chain; the script checks the name.**
  - **The handshake always completes.** The host then judges the chain against the JVM's trust store, or `ca`, and refuses with OpenSSL's code and message, as Node's error has them. The JVM's own refusal names no reason ("PKIX path building failed").
  - **The host name** is checked by `tls.checkServerIdentity`, Node's algorithm, or the script's own check.
  - **Nothing the script writes goes until both pass.**
  - **No reach is added:** the script's own connection is the only thing that could be affected, and `rejectUnauthorized: false` already lets a script skip verification for it.
- **The limits live in the cluster policy** (`limits`: 120 connections a minute and 256 sockets per tenant on each node, 16 per script), as §10 planned, and show in the tenant's view.
- **Half-close:** `end()` shuts the TCP output (Netty's `shutdownOutput`) once what was written has gone, and keeps reading. Over TLS, `end()` closes.
- **Backpressure:** the host holds about 8 unread chunks per socket, then stops reading, and TCP pushes back on the peer. Tested with a 64 MB sender and a script that doesn't read.
- **An upgrade hands over the connection:** `tls.connect({ socket })` takes over the plain socket's stream, including a read already in flight. The plain socket closes with the TLS one, so pg, which listens for `'close'` on it, sees the end.
- **What real drivers needed beyond sockets:**
  - `util/types`, as a module (pg)
  - `child_process`'s names, each function throwing (nodemailer, mongodb)
  - `Buffer` statics and prototype methods enumerable, as Node's (safer-buffer and mysql2 copy them with `for…in`)
  - `Buffer.copy` clamping as Node's does
- **Two older bugs found and fixed:**
  - **`emitter.once()` bypassed `this.on()`,** so a stream with only a `once('data')` listener never flowed.
  - **The bundler applied the `import` condition to `require()`,** so pg's `require('pg-pool')` got the ES module's namespace object.
- **Testing against real servers:** pg (Postgres 18, plain and TLS), mysql2 (MySQL 8.4), ioredis (Redis 7), mongodb (MongoDB 8.2; MongoDB 8.0 refuses Linux 6.19 and newer) and nodemailer (Mailpit, STARTTLS). They run in containers, with `AUTOMATELY_TEST_REAL_SERVICES`, and every result matches real Node 26.8.1.

## 8. Tony's answers (2026-10-08)
1. **Raw TCP to the public internet (B5): every port except SMTP.**
   - **SMTP:** 25, 465 and 587 are refused unless an operator allows them per tenant, since open SMTP from the cluster's addresses gets them blocklisted.
   - **Connect rate:** new connections are capped at 120 a minute per tenant per node, which makes scanning slow and visible.
2. **DNS records beyond addresses (B4): filtered.** Tony asked how; this is the proposal, to be built with B4:
   - **Addresses** (`lookup`, `resolve4`, `resolve6`): only those the tenant could connect to.
   - **Internal names:** single-label names, special-use suffixes (`.local`, `.internal`, `.lan`, `.home.arpa`, `.localhost`, `.cluster.local`, `.svc`) and suffixes an operator lists in `AUTOMATELY_EGRESS_INTERNAL_DOMAINS` answer `ENOTFOUND` for every record type, unless a tenant rule names them.
   - **Reverse lookups:** only for addresses the tenant could connect to, so `dns` can't map internal addresses to names.
   - **`MX`, `SRV`, `CNAME` and `NS` answers:** targets in internal zones are dropped. `TXT` answers are returned as they are, since they're only reachable for names that aren't internal.
   - **Split-horizon DNS:** an operator whose resolver answers internal records for public-looking names sets `AUTOMATELY_EGRESS_DNS` to a public resolver, which then answers every script query.
3. **`rejectUnauthorized: false` is honoured** (B2, B5). It only affects the tenant's own connection, and the job log notes it once per job.
4. **B3 (`createServer` over `ctx.serve`): yes.**
5. **Later and separately:** `worker_threads` as child jobs. (The WebSocket client and `http2`, also listed here at first, are now in this plan: §12 and §13.)

6. **More from Tony, on reviewing the plan (2026-10-08):**
   - **Address spaces must be configurable** before the rest is built (§10).
   - **Servers are reached by hostname only:** the ports a script listens on are intercepted and remapped, with one hostname per port by default, and path prefixes or custom domains per port when asked (§5).
   - **WebSocket servers too** (§13).
   - **Raw TCP and UDP servers only on untrusted nodes,** such as a link on its own hosted server, never on core nodes.
   - **`got`** is planned on Vert.x, including its HTTP/2 path (§11, §12).
   - **The containers and links come after phase B:** the shared execution layer and the gVisor Node containers next, then the links review (NOTES).

## 9. Order and tests
1. **B1:** ✅ (D-108).
2. **B2:** ✅ (D-109, D-110).
3. **Configurable address spaces (§10):** ✅ (D-111, D-113).
4. **got over HTTP/1.1** (§11): ✅ (D-114, D-115).
5. **B5: sockets** (§7): ✅ (D-116).
6. **The `http2` client** (§12): ✅ (D-117).
7. **B4: `dns`** (§6).
8. **B3: routes and ports, then `createServer`** (§5).
9. **WebSockets** (§13).

**Every step follows the same routine:**
- **Probe:** bundle and publish the target libraries first, and list every refusal.
- **Build.**
- **Prove:** each library gives exactly real Node's results in `RealNpmPackagesTest`. From step 5 on, that runs against Testcontainers services.
- **Test the limits:** hostile scripts hit every limit while the node stays healthy.
- **Run the full build** with Docker.
- **Document:** node-compat.md, this file, SECURITY.md where it applies, CHANGELOG, and a D-entry.

**What the libraries need,** measured by bundling each as a publish does:

| Library | Needs that the isolate lacks today |
|---|---|
| got 14 | `diagnostics_channel`, real socket details; `http2` only with `http2: true` |
| pg, mysql2, ioredis, nodemailer | sockets, including upgrading to TLS; `diagnostics_channel` (mysql2, ioredis) |
| mongodb | sockets; `dns` SRV and TXT for `mongodb+srv://` |
| Express 5, Koa, Fastify | `createServer`; `async_hooks` (`AsyncResource`); also `diagnostics_channel` and `perf_hooks` (Fastify) |
| ws | sockets plus HTTP `upgrade` (§13) |

## 10. Configurable address spaces (built, D-111, D-113)
**The problem:** the gate's ranges were hard-coded, and an operator could do only two things: flip one global switch, and give tenants allow rules. Tony: "make it configurable what address spaces we allow and disallow."

**One rule model, at two levels:**
- **The cluster policy** is set by an operator and applies to every tenant.
- **The tenant policy** is set by an operator too, and is today's policy extended.
- **What a rule holds:** `{ "action": "allow" | "deny", "to": CIDR | host | "*.suffix" | "@set", "ports": [...], "protocols": ["http", "tcp", "dns"], "note": "why" }`.
  - **`protocols`:** `http` covers `fetch`, `http`, `https` and `http2`; `tcp` covers `net` and `tls`; `dns` covers lookups. Omitted means all.
  - **`ports`:** optional on deny rules, where omitted means every port. Still required on allow rules, so an allow is always narrow.

**Named sets:**
- **Built in:** `@private` (RFC 1918 and ULA), `@loopback`, `@link-local` (including cloud metadata), `@cgnat`, `@reserved` (documentation, benchmarking, broadcast, multicast and the rest) and `@smtp-ports`.
- **The operator's own,** such as `@corp-public`.
- **Embedded IPv4:** IPv6 forms that carry an IPv4 address are judged by it, as now.

**How a connection is decided** (Tony's order):
1. **A cluster node → refused,** always, on every port. This floor is code, not a rule, and nothing overrides it.
2. **A cluster deny rule → refused.** A tenant can't override it.
3. **A tenant deny rule → refused.**
4. **A tenant allow rule → allowed.**
5. **A cluster allow rule → allowed.**
6. **Otherwise the posture decides:** the tenant's own if an operator set one, otherwise the cluster's.
   - **`public`** (the default): public addresses are allowed, and the private and reserved sets refused.
   - **`allow-list`:** everything is refused unless a rule allows it.

**Defaults:**
- **A new cluster's policy is one cluster deny rule:** `@smtp-ports` over `tcp`, with a note saying why. An operator can edit or remove it, and a tenant allow can't override it.
  - **Lifting it for one tenant:** the operator adds a narrower cluster allow, or moves the deny into specific tenants' policies.
- **The development switch** (`AUTOMATELY_EGRESS_ALLOW_PRIVATE`) still works as before: it opens every private and reserved address after the deny rules. The node's own ports are still refused.

**Settings in the cluster policy, from B5:** the connect rate (120 a minute per tenant per node) and the socket caps (16 per execution, 256 per tenant per node). They come with sockets, the first thing that uses them.

**Where it lives:**
- **Storage:** `Records`, edited through `GET`/`PUT /v1/egress/cluster-policy` (operators, audited) and `automately egress cluster …`.
- **Seeding:** `AUTOMATELY_EGRESS_POLICY_FILE` seeds it on the cluster's first start only.
- **Caching:** nodes cache it as they cache tenant policies.
- **Tenant policies** gain deny rules and a posture. Today's rules read as allows.

**The tenant's view (`GET /v1/egress-policy`)** shows the effective policy in the order it's applied: the floor, the cluster's rules with their notes, the tenant's rules, the posture, and the limits. Each item says why, which is what the console's "Network access" page shows.

**What building it settled (D-113):**
- **Matching an address:**
  - **An allow matches only the address a connection really reaches** (an IPv6 form's embedded IPv4). Allowing `64:ff9b::/96` doesn't open the private addresses behind NAT64.
  - **A deny matches either form.** Denying `2002::/16` refuses public addresses reached through 6to4.
- **Missing sets:** a rule naming a set that no longer exists refuses everything as a deny and opens nothing as an allow. A tenant's rules may only name sets that exist when they're written.
- **Built-in names:**
  - **Sets:** `@public` and `*` are built in. `@public` is everything in none of the other sets, so "allow `@public` on 443" is how an allow-list tenant gets HTTPS to the internet without anything private.
  - **Ports:** `@smtp` names 25, 465 and 587.
- **Suffixes:** `*.ru` (a whole top-level domain) is accepted in a deny but not an allow, which would be too broad to be what was meant.
- **Caching:** every connection reads both policies, so nodes cache them.
  - **On a change:** it's announced on the event bus and every node drops its copy.
  - **As a backstop:** the cache also expires after 5 s, and a read that raced a change isn't cached.
  - **The API reads past the cache.**
- **Request sizes:** a tenant's policy can be up to 64 KB, the cluster's up to 4 MB.
- **A tenant's posture** can be set back to the cluster's with `"posture": "inherit"`.

## 11. got, and real socket details on `http` (built, D-114)
- **`node:diagnostics_channel`,** which doesn't touch the network: channels, subscribing, publishing, and `tracingChannel`. got, mysql2, ioredis and Fastify import it.
- **Real connection details on `req.socket`,** from Vert.x's connection:
  - **What it reports:** remote and local addresses, the family, and for TLS whether the certificate was trusted (and why not), the protocol, the cipher, ALPN and the peer certificate.
  - **The socket stand-in:** `connecting` stays true until the host has the connection. Then it emits `lookup` (the address the gate checked), `connect`, then `secureConnect`, in Node's order. A connection reused from the pool is already connected, as a keep-alive socket in Node is.
- **What it gives got:** real timings.
- **Tested:** a got fixture against real Node covering JSON, retries, timeouts, hooks, streams, `HTTPError`, redirects and pagination.

**What building it took (D-114, D-115):**
- **A Node version.** got reads `process.versions.node` when it loads, which D-102 had left undefined. Tony chose to report the release the stand-ins are checked against, `v26.8.1` (D-115). With it, all 31 library fixtures still match Node, and jsonwebtoken's PS256 works.
- **`events.on`,** the async iterator, which got imports.
- **`dns` with its names and constants, lookups refused until §6.** got's cacheable-lookup imports it when it loads (and uses it only if got's DNS cache is turned on).
- **Unconnected sockets:** `new net.Socket()` and `new tls.TLSSocket(stream)` construct, as in Node. http2-wrapper builds a `TLSSocket` over a stream when it loads to reach a Node internal.
- **Node's messages for refused connections and unknown hosts** (`connect ECONNREFUSED 127.0.0.1:9`), which got passes on.
- **Found along the way:** the isolate's `ECDH.getPrivateKey()` kept leading zero bytes, which Node drops. One generated key in 256 came out a byte longer than Node's, which made the webcrypto fixture flaky. Fixed, and the fixture now checks the case directly.

## 12. The `http2` client (built, D-117; this replaces "never `http2`")
- **A session is a Vert.x connection:** `http2.connect` goes through the gate, then `HttpClientAgent.connect`, which answers with an `HttpClientConnection`. That's exactly a Node session. Over TLS it negotiates `h2` with ALPN; for `http://` it uses h2c with prior knowledge.
- **Session calls:** `request` (a stream), `settings`, `ping`, `goaway`, `close`/`destroy` and the remote settings, mapped onto Vert.x's.
- **A stream is a `Duplex`,** with `response`, `trailers`, `close(code)` and `rstCode`.
- **Server push is off.**
- **Limits:** a session counts as a socket, and a stream as a request. Headers are capped at 64 KB. TLS options as in §7.
- **Not built:** HTTP/2 servers. The ingress can offer h2 to clients separately.
- **Tested** against a Vert.x h2 test server, with a fixture against real Node and got with `http2: true`.

**What building it settled (D-117):**
- **Verification as for sockets:** the host judges the chain against the trust store or `ca`, refusing with Node's codes, and the session checks the name with `tls.checkServerIdentity` before `connect`.
- **Limits:** a session is reserved through the socket client's limits (`reserve`), and a stream takes one of the script's 16 request places, released when its body and trailers are done or it's reset.
- **Events reach the script as a stream:** the session's goaway, settings and errors arrive as small JSON chunks on a host stream, the same flow control as bodies.
- **got (`http2: true`):** http2-wrapper opens its own TLS socket (ALPN `h2`) and passes it as `createConnection`. When it's one of the isolate's own sockets, already through the rules, the session closes it and opens its own connection to the same origin.
- **Node's details, matched against real Node:** `CANCEL` and `NO_ERROR` resets ending a stream quietly, `request()` on a destroyed session failing the stream rather than throwing, and the `connect` event's socket being distinct from `session.socket`.
- **One race, found under CPU load:** calls into a connection from the isolate's thread raced with Netty, and a ping after a reset went unanswered. Every session and stream operation now runs on the connection's event loop.

**Completing Node's client (D-118):** what Vert.x doesn't expose is read from the Netty connection beneath it (`NettyHttp2`).
- **Flags:** the response's and the trailers' flags are their HEADERS frames' own, recorded by a listener on Netty's decoder. Node reports nghttp2's flags as they are: a Node server sends 4 (END_HEADERS), and Vert.x's server adds PRIORITY.
- **Request trailers** go through Vert.x's own HTTP/2 stream (`writeHeaders(trailers, end)`), so Vert.x knows the request ended. A frame written past it on the Netty encoder left the response never ending.
- **`state`:**
  - **The session's** comes from Netty's connection, flow controllers and HPACK tables.
  - **A stream's** is worked out in the isolate (D-120), from what it has written and what the script has seen, as nghttp2 holds it for Node. Reading it from the network raced with the script's events.
- **Priorities, as Node 26:** RFC 9113 deprecated them, so nothing is sent and Node's DEP0194 warning is emitted once.
- **`outboundQueueSize` (D-119)** is nghttp2's queue of unwritten control frames. The isolate keeps it and empties it on the next turn, as Node does.
- **Flow control, exactly (D-121):**
  - **Both receive windows are modelled:** the connection's refills before the handlers of the frame that brings it to half, the stream's after them.
  - **Network reads stay apart:** the host records which read each frame came in, and the isolate hands a later read to the script on a later turn.
  - **Proved against Node:** a paced test server (fixed frames, each in its own read) makes Node deterministic, and the isolate matches it frame by frame.

## 13. WebSockets (accepted)
- **The ingress** passes `Upgrade: websocket` through to the service's node, then its internal endpoint. Frames are bridged both ways with backpressure. Today it strips `Upgrade`, so WebSockets don't pass.
  - **Limits:** connections per service, frame size and idle time.
- **In the isolate:**
  - **Servers:** `http.Server` emits `upgrade` with a socket stand-in, so the `ws` library's server works.
  - **Clients:** `http.request` with `Upgrade` works over §7's sockets, so its client works too.
- **Node containers use the same ingress path** (node-runtime.md §5).

