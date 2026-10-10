# Security

Automately runs other people's code. This page describes who is trusted, what each boundary defends, how (with the decision that covers it), and what isn't defended, so you can deploy it knowing where the edges are.

**Reporting a vulnerability:** email **tony@adamnlynn.com** with "SECURITY" in the subject. Please don't open a public issue. You'll get an answer within a few days, and fixes are credited if you'd like.

## Who is trusted

| Party | Trusted to | Not trusted to |
|---|---|---|
| **The operator** (runs the nodes, holds the admin token and env config) | Everything | — |
| **A tenant** (holds tokens for its own tenant) | Run code, store data, serve HTTP, within its scopes, quota and rate limit | Read or affect another tenant, reach the host or the cluster's internals |
| **A tenant's script** (code in an isolate) | What its tenant may do, through the host APIs | Anything else: it is assumed hostile |
| **The public** (calls services through the ingress) | Send requests to services | Anything else |
| **An AI agent over MCP** | What its token's scopes allow | Manage credentials (storage tools aren't exposed, D-057) |

## Boundaries and what defends them

**Scripts are sandboxed in V8 isolates** (D-002, D-003, D-019, D-024):
- **No Java access.** Each job gets a fresh isolate with no Java interop and no Node.js APIs. A script reaches the outside only through the allow-listed host APIs (`kv`, `bus`, `fetch`, `files`, `log`, `ctx`), and every call is checked against the job's tenant (D-025).
- **Memory: the heap is capped, but not everything an isolate uses.** `AUTOMATELY_MAX_HEAP_MB` caps V8's heap, and hitting it ends the script, not the node.
  - **Outside the cap:**
    - ArrayBuffers and typed arrays (one `new ArrayBuffer(2**33)` is accepted)
    - resizable and growable buffers
    - WebAssembly memory
    - ICU objects behind `Intl` and `toLocaleString`
    - bytes a script hands to the host
  - **The consequence:** today a script can exhaust a node's memory and get it killed (D-126). The fix is per-isolate accounting and tenant worker processes with kernel memory caps ([engine-neutral-runtime.md](docs/design/engine-neutral-runtime.md)).
  - **Until it lands:** run only trusted tenants on shared nodes, or give each untrusted tenant its own nodes.
- **Time.** Jobs have a timeout (`AUTOMATELY_MAX_TIMEOUT_MS`), and a stop request is followed by forced termination after a grace period (D-020).
- **No reuse.** Isolates are never reused between jobs, let alone between tenants.
- **Tested.** The hostile-script tests (`JavetScriptRuntimeTest.HostileScripts`) cover infinite loops (plain, after an `await`, at module top level), memory bombs, global state and prototype pollution leaking between jobs, and reaching host internals. Each must be stopped or refused while the node keeps working. Tenant scoping of every host API is tested separately (`NodeHostServicesTest`, `HostApisTest`).

**Tenants are kept apart:**
- Every key, topic, file path, service, domain and job is scoped to the tenant that owns it, taken from the token rather than the request (D-045).
- A custom domain routes only after its owner publishes a TXT record (D-028).
- A tenant's storage provider only ever serves that tenant's files, and its sealed secret can't be moved to another provider (D-057).

**People** (D-070, D-071):
- **Users and roles:**
  - **Getting in:** users join tenants by single-use, expiring invites. No passwords are stored.
  - **What they may do:** built-in or custom roles, given directly or through groups.
  - **Personal tokens:** what a personal token may do is worked out on every request from its user's current roles. Removing, demoting or disabling someone takes effect on their next request, with every token they made.
- **No escalation:**
  - Nobody grants more than their own scopes cover, before and after the change.
  - Only owners grant `owner`, and a tenant keeps its last owner.
- **Operators** are named users with the admin token's powers, and no more: they still can't read tenant data. Use them instead of sharing the admin token.
- **The audit log:** every change and every refused attempt is recorded with who, when and from where. Tenants see their own; operators see the cluster's and everything operators did. It's kept 90 days.
  - **Tamper evidence (D-072):** each node chains its entries with an HMAC keyed by `AUTOMATELY_SECRETS_KEY`, and `automately audit verify` finds changed, removed or relinked entries.
  - **Export (D-073):** set `AUTOMATELY_AUDIT_S3_BUCKET` to copy the log out of the cluster. That's the record someone with database access can't rewrite, including its newest entries.

**API access** (D-044 to D-046, D-050, D-051):
- **Tokens.** Every `/v1` call needs a bearer token. Tokens are 256-bit random with a checksum and a scanner-friendly `atly_` prefix. Only their SHA-256 is stored, and revocation takes effect on the next request.
- **Scopes and the admin.** Scopes limit each token. The admin token manages tenants and tokens but can't read tenant data.
- **Validation.** Requests are validated against the OpenAPI contract before any handler runs.
- **Limits.** Per-tenant rate limits and storage quotas (D-043, D-051) bound what one tenant can take. Admission control refuses writes before disk or memory runs out, so uploads can't crash a node (D-038).

**Outbound connections (`fetch`, `http`/`https`, `net`/`tls` sockets, completion callbacks, tenant S3 endpoints)** (D-026, D-062, D-108, D-116):
- Private, loopback, link-local (including cloud metadata at 169.254.169.254), CGNAT and other reserved addresses are refused, including IPv6 forms that carry one (NAT64, 6to4, IPv4-mapped and IPv4-compatible).
- **The cluster's own nodes are refused on every port,** even on public addresses: every address a live node advertises, and every address of the node's own interfaces (D-108). No rule can allow them.
- The connection goes to the exact address that was checked, so DNS rebinding can't swap one in.
- Redirects aren't followed.
- **Operators configure the address spaces** (D-111), at two levels, and every change is audited:
  - **The cluster's policy** (`PUT /v1/egress/cluster-policy`) applies to every tenant. Its deny rules can't be overridden by a tenant.
  - **Each tenant's policy** (`PUT /v1/tenants/{tenant}/egress-policy`).
  - **Rules:** allow or deny, to a CIDR, a host name, a `*.suffix`, a named address set (built in, such as `@private` or `@link-local`, or the operator's own) or anything, by port and by protocol.
  - **The order:**
    1. cluster nodes (always refused)
    2. cluster denies
    3. tenant denies
    4. tenant allows
    5. cluster allows
    6. the posture: `public` (public addresses allowed) or `allow-list` (nothing unless allowed), set for the cluster and optionally per tenant
  - **Hard to get around:** a deny matches an address in any of its forms, an allow only the address actually reached, and a rule naming a set that no longer exists refuses rather than opens.
  - **The default cluster policy refuses mail ports (25, 465, 587) to raw sockets.**
  - **The development switch:** `AUTOMATELY_EGRESS_ALLOW_PRIVATE` opens private and reserved addresses for every tenant (still after the deny rules) and is for local development only; a node warns at every start while it's on.
  - **Tenant S3 endpoints:** allowed by the tenant's storage policy.
- Refusals are counted per tenant and reason (`automately_egress_denied_total`).
- **Scripts' sockets** (D-116), and HTTP/2 sessions (D-117) counted as sockets, go through the same rules, as `tcp` and `http` respectively, and the cluster policy's limits apply:
  - at most 120 new connections a minute and 256 open sockets per tenant on each node
  - 16 open sockets per script
  - every socket is closed when its run ends

  The host buffers only a few chunks a script hasn't read before pushing back on the peer, so a script can't make it hold a stream. TLS chains are judged on the host, and nothing the script writes is sent before the chain and host name check pass. Bytes are counted per tenant (`automately_egress_bytes_total`).
- **Scripts' `dns`** (D-122) uses the gate's own resolver, so it can't answer differently from what a connection gets, and it shows only what the tenant could reach:
  - **Internal names:** single labels, `.local`, `.internal`, `.lan`, `.home.arpa`, `.localhost`, `.cluster.local`, `.svc` and `AUTOMATELY_EGRESS_INTERNAL_DOMAINS` answer `ENOTFOUND` unless a rule names them.
  - **Addresses** the tenant couldn't connect to are left out, as are records naming internal hosts.
  - **Reverse lookups** only work for addresses it could reach.
  - **A DNS server a script chooses** must pass the gate as a `dns` connection, so a private one needs a rule.
  - **Split-horizon resolvers:** an operator whose system resolver answers internal records for public names sets `AUTOMATELY_EGRESS_DNS` to a public resolver, which then answers every script lookup and every connection's name.
- **Nothing a script runs listens on a node:** TCP and UDP servers are refused, and HTTP servers are reached by hostname through the ingress (D-112). A WebSocket is an HTTP request the app chose to answer with 101: the ingress then tunnels that one connection to that run, capped per run and closed when idle (D-125).
- **A script's WebSocket client is a socket of its own** (D-125), so the egress gate checks it as `tcp`, under the same rules and socket limits as `net`.
- Tenant S3 endpoints are still checked by the older address check, without the cluster-node rule; they move to the gate with phase B's next step.

**Secrets at rest:**
- Tenants' storage credentials are encrypted with AES-256-GCM, using a key derived from `AUTOMATELY_SECRETS_KEY`, with the tenant and provider bound in as associated data. They're write-only in the API (D-057).
- Token secrets are never stored, only their hashes.
- **Tenants' secrets** (D-068) are sealed the same way, bound to their tenant and name, and never returned by the API. Only scripts of jobs submitted, or services defined, by a token with `secrets:use` can read them, and jobs those start inherit that. So a `jobs:write` token, for an agent say, can run code without seeing credentials. Without `AUTOMATELY_SECRETS_KEY` the secrets API is unavailable rather than storing values in the clear.
- Each tenant's callback signing secret is kept in the cluster under the system tenant, which no token can reach. It's sealed with `AUTOMATELY_SECRETS_KEY` when one is set, so set one in production (D-062).

**Between nodes** (D-036, D-066):
- Every connection between core nodes uses TLS with node certificates on both sides. The internal endpoint that carries service traffic accepts only other nodes. D-036's shared secret is retired when certificates are on, and still used with `AUTOMATELY_CLUSTER_TLS=off`.

**Releases** (D-069):
- Each release's `SHA256SUMS` is signed with an ECDSA P-256 key that stays on the maintainer's machine, encrypted. The release workflow only drafts releases, and they're signed and published by hand.
- `install.sh`, `install.ps1` and `automately upgrade` check that signature with the built-in public key before trusting any checksum. They refuse unsigned or wrongly signed releases, so neither GitHub nor a mirror can serve a changed binary.
- Not covered:
  - an older, validly signed release served in place of the latest
  - the first `curl | sh`, which trusts HTTPS for the installer itself. To avoid that, download and check it first (see the [CLI guide](docs/guides/cli.md)).

## What isn't defended, so deploy accordingly

- **A script can exhaust a node's memory** outside the isolate's heap limit (see Memory above, D-126).
- **Isolates share the node's process.** V8 isolates are memory-safe sandboxes, but they aren't separate processes or VMs. A V8 vulnerability that escapes the isolate would compromise the node, so keep the JVM and Javet up to date. For hostile multi-tenant use at scale, run nodes as unprivileged containers on dedicated hosts.
- **CPU is bounded by time, not quota.** A job's timeout and the node's capacity bound how much CPU a tenant uses, but a running service has no CPU cap beyond holding one of the node's isolate slots. The per-tenant service limit (`AUTOMATELY_MAX_SERVICES_PER_TENANT`) bounds how many it can hold.
- **Nodes authenticate each other with certificates (D-066, D-067).** Ignite discovery and communication, the clustered event bus and the internal endpoint use TLS 1.3 with **a node certificate required on both sides**, signed by the cluster's own CA, whose private key stays on the operator's machine. A machine without such a certificate can't join or read traffic. Nodes are added with one-line join bundles, and removed with `automately node revoke`, which every node enforces and which forces the node out ([docs/guides/cluster.md](docs/guides/cluster.md)).
  - **The event bus:** it trusts the CA but doesn't consult the revocation list. Vert.x copies its TLS options in a way that loses custom trust managers. A revoked node can't rejoin Ignite discovery, which does check, and is forced out when revoked, so its event bus connections go with it.
  - **`AUTOMATELY_CLUSTER_TLS=off`** turns all of this off. It's for development only and logs a warning at every start.
  - **Still keep node ports private** (5701, 5801, the event bus, 8090, 9090), and bind Ignite to the private address (`AUTOMATELY_CLUSTER_BIND`). Certificates stop strangers joining; a private network stops them trying.
- **Ignite's own client and REST ports are off (D-065).** Ignite opens a thin-client/JDBC/ODBC connector (10800) and a REST command protocol (11211) by default, unauthenticated, on every interface. Automately uses neither, so both are disabled, and a test fails if a node listens anywhere unexpected. Before this fix (2026-10-07), anything that could reach 10800 could query every table.
- **TLS is yours to add.** Nodes speak plain HTTP. Terminate TLS in front of the API (8080) and the ingress (8000) with a reverse proxy or load balancer, and keep the internal (8090) and metrics (9090) ports private.
- **Operators can read logs.** Scripts' console output is stored with their jobs (the last 1,000 lines, at most 1 MiB, each line cut at about 16 KiB; D-107), so tenants shouldn't log secrets. Secrets declared in a job's `env` are masked (D-096); values a script fetches some other way aren't.
- **Services serve the public.** Anything a service exposes through the ingress is public; services authenticate their own users.
- **Disk providers rely on the shared storage's own security and redundancy** (NFS, Ceph) for the folder under `AUTOMATELY_DISK_ROOT`.
- **Rotating keys:** changing `AUTOMATELY_SECRETS_KEY` makes stored tenant credentials and secrets unreadable (tenants re-enter them). Rotate the admin token with `POST /v1/admin/rotate`, or by changing `AUTOMATELY_ADMIN_TOKEN` and restarting.

## Deployment checklist

- [ ] `AUTOMATELY_ADMIN_TOKEN` is set and stored securely, or the generated one was saved and rotated.
- [ ] Ignite ports (5701, 5801), the internal port (8090) and the metrics port (9090) are reachable only between nodes, and by Prometheus. `AUTOMATELY_CLUSTER_BIND` is the node's private address.
- [ ] The cluster's certificates come from `automately cluster init` on your own machine, not the compose file's development `tls-init`. `ca.key` is kept off the servers and backed up. `AUTOMATELY_CLUSTER_TLS` isn't `off`.
- [ ] TLS terminates in front of 8080 and 8000.
- [ ] `AUTOMATELY_SECRETS_KEY` is set (at least 32 random characters) if tenants will use secrets or bring their own storage, and backed up.
- [ ] `AUTOMATELY_EGRESS_ALLOW_PRIVATE` is left off in production.
- [ ] If the nodes' resolver answers internal names (split-horizon DNS), `AUTOMATELY_EGRESS_DNS` points scripts at a public resolver, and `AUTOMATELY_EGRESS_INTERNAL_DOMAINS` lists your internal zones.
- [ ] Tenant tokens are scoped to what each client needs, with expiry for CI tokens.
- [ ] People use their own users (invites), and operators their own operator users; the admin token is kept for emergencies.
- [ ] The audit log is exported (`AUTOMATELY_AUDIT_S3_BUCKET`) to a bucket the cluster's operators can't delete from (object lock or a separate account), and node clocks are kept in sync.
- [ ] `AUTOMATELY_DATA_DIR` is on durable storage and backed up (the cluster replicates, but a backup protects against operator error).
