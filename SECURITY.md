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
- **Memory.** The isolate's heap is capped (`AUTOMATELY_MAX_HEAP_MB`). Hitting the cap ends the script, not the node.
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

**Outbound requests (`fetch`, completion callbacks, tenant S3 endpoints)** (D-026, D-062):
- Private, loopback, link-local (including cloud metadata at 169.254.169.254), CGNAT and other reserved addresses are refused.
- The connection goes to the exact address that was checked, so DNS rebinding can't swap one in.
- Redirects aren't followed.
- Only the operator (`AUTOMATELY_EGRESS_ALLOW_PRIVATE`) or a tenant's storage policy can allow private addresses.

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

- **Isolates share the node's process.** V8 isolates are memory-safe sandboxes, but they aren't separate processes or VMs. A V8 vulnerability that escapes the isolate would compromise the node, so keep the JVM and Javet up to date. For hostile multi-tenant use at scale, run nodes as unprivileged containers on dedicated hosts.
- **CPU is bounded by time, not quota.** A job's timeout and the node's capacity bound how much CPU a tenant uses, but a running service has no CPU cap beyond holding one of the node's isolate slots. The per-tenant service limit (`AUTOMATELY_MAX_SERVICES_PER_TENANT`) bounds how many it can hold.
- **Nodes authenticate each other with certificates (D-066, D-067).** Ignite discovery and communication, the clustered event bus and the internal endpoint use TLS 1.3 with **a node certificate required on both sides**, signed by the cluster's own CA, whose private key stays on the operator's machine. A machine without such a certificate can't join or read traffic. Nodes are added with one-line join bundles, and removed with `automately node revoke`, which every node enforces and which forces the node out ([docs/guides/cluster.md](docs/guides/cluster.md)).
  - **The event bus:** it trusts the CA but doesn't consult the revocation list. Vert.x copies its TLS options in a way that loses custom trust managers. A revoked node can't rejoin Ignite discovery, which does check, and is forced out when revoked, so its event bus connections go with it.
  - **`AUTOMATELY_CLUSTER_TLS=off`** turns all of this off. It's for development only and logs a warning at every start.
  - **Still keep node ports private** (5701, 5801, the event bus, 8090, 9090), and bind Ignite to the private address (`AUTOMATELY_CLUSTER_BIND`). Certificates stop strangers joining; a private network stops them trying.
- **Ignite's own client and REST ports are off (D-065).** Ignite opens a thin-client/JDBC/ODBC connector (10800) and a REST command protocol (11211) by default, unauthenticated, on every interface. Automately uses neither, so both are disabled, and a test fails if a node listens anywhere unexpected. Before this fix (2026-10-07), anything that could reach 10800 could query every table.
- **TLS is yours to add.** Nodes speak plain HTTP. Terminate TLS in front of the API (8080) and the ingress (8000) with a reverse proxy or load balancer, and keep the internal (8090) and metrics (9090) ports private.
- **Operators can read logs.** Scripts' console output is stored with their jobs (the last 1 000 lines) and appears in node logs, so tenants shouldn't log secrets.
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
- [ ] Tenant tokens are scoped to what each client needs, with expiry for CI tokens.
- [ ] People use their own users (invites), and operators their own operator users; the admin token is kept for emergencies.
- [ ] The audit log is exported (`AUTOMATELY_AUDIT_S3_BUCKET`) to a bucket the cluster's operators can't delete from (object lock or a separate account), and node clocks are kept in sync.
- [ ] `AUTOMATELY_DATA_DIR` is on durable storage and backed up (the cluster replicates, but a backup protects against operator error).
