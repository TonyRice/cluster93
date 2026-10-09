# Network reach in the isolate (phase B): a design, reviewed for security first

**Status:**
- **B1, the egress gate and allow-lists:** ✅ built (D-108). Tony decided its shape on 2026-10-08 (D-095 §4.5).
- **B2, the `http`/`https` client:** accepted, since it adds no reach beyond `fetch`. Next.
- **B3 to B5:** accepted with Tony's answers in §8 (2026-10-08).

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
- `createConnection`, `lookup` and `localAddress` options (they'd bypass the gate)
- `CONNECT`, and upgrades to WebSocket
- `socketPath` (Unix sockets)
- `insecureHTTPParser`

**Proven against real Node** by `RealNpmPackagesTest`, against a local test server:
- `http.get`/`request`
- axios (its Node adapter)
- node-fetch
- `got`, if its HTTP/2 wrapper loads; otherwise a documented gap

## 5. B3: `http.createServer` over `ctx.serve` (proposed)
**What it is:** `createServer(handler)` and `server.listen(port?, cb)` in a service bind the handler to `ctx.serve`. The port is ignored: a service is reached at its ingress host (D-028).
- **`req`:** an `IncomingMessage`.
- **`res`:** a `ServerResponse` with `writeHead`, `setHeader`, `getHeader`, `statusCode`, `write`, `end` and `headersSent`, written through the existing streaming response.
- **Prototypes:** all of these are real prototypes, so Express's `Object.setPrototypeOf(res, app.response)` works.

**No new reach:** inbound traffic still comes only through the ingress, with its limits, its domains and its tenant routing.

**Outside a service:** `listen` throws, saying that HTTP servers run in services.

**Proven against:** Express, Koa and Fastify in `RealNpmPackagesTest`, each answering the same requests as under real Node.

## 6. B4: `dns` (proposed)
- **`lookup`:** the same resolution the gate uses, answering only addresses the tenant could connect to. A name that resolves only to refused addresses is `ENOTFOUND`, so `dns` can't map out internal names.
- **`resolve4`/`resolve6`:** filtered the same way.
- **`resolveTxt`, `resolveMx`, `resolveSrv`, `resolveCname`, `resolveNs`, `reverse`:** the open question in §8.
- **Limits:** 16 queries in flight per execution; answers capped at 64 KB.
- **`dns/promises`** has the same functions.

## 7. B5: outbound `net` and `tls` (proposed)
**What it is:** a new host API for sockets.
- **Calls:** `connect(host, port, {tls, servername, ca, rejectUnauthorized, alpn})`, `write` with backpressure, data events with flow control, `end` and `destroy`.
- **Node's shapes:** the stand-ins build `net.Socket` (a `Duplex`), `net.connect`/`createConnection` and `tls.connect`, plus `isIP`, `isIPv4` and `isIPv6`.
- **What it unlocks:** `pg`, `mysql2`, `ioredis`, `mongodb` (when its optional native parts are absent), `nodemailer` (see ports) and anything else on TCP.

**Every socket goes through the gate,** as above.

**Limits:**
- 16 sockets per execution, and 256 per tenant per node
- 30 s to connect, and an idle timeout of 5 min, extendable by `setTimeout`
- every socket closed when the job ends
- 64 KB of host-side buffering per direction, with the script's reads paused beyond that
- bytes counted per tenant: `automately_egress_bytes_total{tenant,direction}`

**Ports, and connect rate:** the open question in §8.

**Never:**
- `net.createServer` / `listen` (inbound is HTTP through the ingress)
- `dgram` (UDP)
- Unix sockets
- `tls.createServer`
- `http2`

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
5. **Later and separately:** `worker_threads` as child jobs, a WebSocket client (on B5's sockets, or a host API of its own), and `http2`.

## 9. Order and tests
1. **B1:** the gate, tenant policies, the reserved-range additions, and binary `fetch` bodies. Tests:
   - every range, embedded forms and rebinding
   - cluster nodes on public addresses
   - name and CIDR rules
   - the API, the contract, the CLI and audit
2. **B2:** `http`/`https` clients and streaming request bodies. Real-npm fixtures against a local server (allowed in tests through a policy rule, not the global switch).
3. **B3, B4 and B5,** after §8, each with hostile scripts that exhaust each limit while the node stays healthy (`HostileScripts`). B5's tests use Testcontainers (Postgres, Redis) and run in `./mvnw verify`.
