# Automately

A self-hostable, clustered runtime for sandboxed JavaScript functions, built on **Vert.x 5** and **V8 isolates** (via [Javet](https://github.com/caoccao/Javet)).

You submit a script to any node. The cluster runs it on the least-loaded node in a fresh V8 isolate, streams its console output live, and stores the result where every node can read it.

> **Status: phase 5 done.**
>
> - **Database.** The cluster is the database: embedded Apache Ignite, durable, replicated, and it survives full restarts.
> - **Streaming and files.** Services stream HTTP and serve files, with ranges for video. Writes are refused before memory or disk runs out.
> - **API.** The API has an OpenAPI contract that validates every request, scoped tokens and rate limits.
> - **Tools.** A native CLI installs with one line, and every node serves an **MCP server**, so AI agents can deploy and run code.
> - **The 2017 core, preserved.** Durable queues next to the message bus, signed completion callbacks, and jobs that start and await jobs, behind host APIs that are Java objects again.
> - **Operations.** Prometheus metrics, a Grafana dashboard, benchmarks, a security model, and a [demo](#the-demo) that kills a node mid-queue.
>
> This is a ground-up rewrite of Automately Core (2015–2017), which ran on a Vert.x 2 fork called jsync.io, Nashorn and raw Hazelcast. See [What changed since 2017](#what-changed-since-2017).

## Install the CLI

```bash
curl -fsSL https://github.com/adamnlynn/automately-v2/releases/latest/download/install.sh | sh     # Linux, macOS
irm https://github.com/adamnlynn/automately-v2/releases/latest/download/install.ps1 | iex           # Windows
```

These are native binaries that need no Java. The installer checks the release's signature, made with a key that never leaves the maintainer's machine, and then each binary's checksum. See the [CLI guide](docs/guides/cli.md). To let an AI agent use the cluster, see [MCP](docs/guides/mcp.md).

## The demo

```bash
scripts/demo.sh        # needs Docker, JDK 21, curl, python3; about 90 s
```

In the demo:
1. **Start.** Three nodes come up with Prometheus and Grafana.
2. **An AI agent deploys a service** over MCP. It's a scripted MCP client sending the same JSON-RPC calls Claude would. The service consumes a durable queue of orders and serves its progress over HTTP.
3. **The agent pushes 120 orders.**
4. **The node running the service is `SIGKILL`ed.**

Then:

```
== Chaos: SIGKILL the node running the service (node1)
   ✘ node1 killed
== Recovery
   +2s  queue: ready  72  in flight 2  dead 0   ingress → (no answer)
   +13s  queue: ready  66  in flight 4  dead 0   ingress → node2
   ✔ the service restarted on node2 13s after the kill
   +27s  queue: ready   0  in flight 2  dead 0   ingress → node2
   +32s  queue: ready   0  in flight 0  dead 0   ingress → node2
   ✔ every order handled: 120 of 120 distinct, 32s after the kill
```

- **The restart.** The service restarts on a survivor and works through what's left.
- **The two orders in flight on the dead node** come back when their 30 s leases run out.
- **Grafana** (http://localhost:3000) shows the restart and the queue draining.

`scripts/demo.sh down` stops it. Watch the recording by opening [docs/demo.html](docs/demo.html) in a browser (it works offline), or run `asciinema play docs/demo.cast`.

## Try it

```bash
export AUTOMATELY_ADMIN_TOKEN=$(openssl rand -hex 32)
docker compose up --build -d        # three nodes on :8081, :8082, :8083
ADMIN="Authorization: Bearer $AUTOMATELY_ADMIN_TOKEN"

# The admin creates a tenant and gives it a token (shown once)
curl -s -X POST localhost:8081/v1/tenants -H "$ADMIN" -d '{"name": "demo"}'
TOKEN=$(curl -s -X POST localhost:8081/v1/tenants/demo/tokens -H "$ADMIN" \
  -d '{"name": "laptop", "scopes": ["*"]}' | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"

curl -s -X POST localhost:8081/v1/jobs -H "$AUTH" \
  -d '{"script": "export default async (input) => { console.log(\"hi\", input.name); return { greeting: `hello ${input.name}` } }",
       "input": {"name": "tony"}}'

# Follow a job from any node (server-sent events)
curl -N localhost:8083/v1/jobs/<id>/events -H "$AUTH"
```

Without `AUTOMATELY_ADMIN_TOKEN`, the first node generates an admin token and prints it once in its log.

To watch it: `docker compose --profile observability up -d` adds Prometheus and Grafana. The **Automately** dashboard is at http://localhost:3000 and covers cluster health, jobs, isolates, services, HTTP, rate limits and storage.

The same with the CLI:
```bash
automately --url http://localhost:8081 --token "$AUTOMATELY_ADMIN_TOKEN" tenants token demo laptop --scope '*' | automately login http://localhost:8081
echo 'export default async (input) => ({ greeting: `hello ${input.name}` })' > hello.js
automately run hello.js --input '{"name": "tony"}'
```

A script is an ES module. Its default export receives the job input and a job context, and returns (or resolves to) any JSON value:

```js
export default async (input, ctx) => {
  const invoice = await ctx.step("create-invoice", ({ key }) => billing.create(input.order, key));  // never repeated on resume
  await ctx.saveCheckpoint({ invoice: invoice.id });
  return { invoice: invoice.id, attempt: ctx.attempt };
};
// `billing` stands for a host API that arrives in phase 3; `ctx` works today.
```

Scripts can store data (`kv`), start and await other jobs (`jobs`), message each other (`bus`, with durable **queues** that survive a node dying), call out (`fetch`, with SSRF protection) and log with structure (`log`); services can **serve HTTP and WebSockets** at `<service>--<tenant>.<domain>` or your own verified domain. See the **[host APIs reference](docs/scripting/host-apis.md)**.

**Services** are scripts the cluster keeps running: if one crashes or its node dies, it's restarted (with backoff) somewhere healthy, carrying its checkpoint over. See the **[services guide](docs/scripting/services.md)**.

If the node running a job dies, the job's `onOrphan` policy decides what happens: `fail` (the default) ends it as `ORPHANED`; `retry` starts again from the beginning; `resume` continues on another node and skips steps that already completed. See the **[job context reference](docs/scripting/job-context.md)**.

## API

Every `/v1` call carries `Authorization: Bearer <token>`.

- **Admin token.** It manages tenants and their tokens, and cannot read or change tenant data (D-044). Operators do the same as named users.
- **Tenant tokens** (`atly_…`) belong to one tenant and carry **scopes**: `jobs:read`, `jobs:write`, `services:read`, `services:write`, `files:read`, `files:write`, `bus:publish`, `bus:request`, `queues:read`, `queues:write`, `tokens:write`, `nodes:read`, `storage:read`, `storage:write`, `secrets:read`, `secrets:write`, `secrets:use`, `members:read`, `members:write`, `audit:read`, or `*` for all of them.
- **Users** (D-070).
  - **Joining:** people join tenants by invite and get roles: `owner`, `admin`, `developer`, `viewer`, or the tenant's own roles, given directly or through groups.
  - **Personal tokens:** a user's personal token may do what their roles give them **now**. Removing or demoting someone takes effect on their next request.
  - **The audit log** records every change (D-071). See [users](docs/guides/users.md).
- **Errors.** A missing or revoked token gets `401`, and a missing scope gets `403` naming it (D-045).
- **Storage.** Only a SHA-256 of each token is stored. The `atly_` prefix and a checksum let secret scanners recognise a leaked token.

| Method | Path | |
|---|---|---|
| `POST` · `GET` | `/v1/tenants` | *Admin.* Create a tenant `{name}` · list |
| `GET` · `DELETE` | `/v1/tenants/{name}` | *Admin.* One tenant · delete it (only once it has no services, files or kv; its tokens go with it) |
| `POST` · `GET` | `/v1/tenants/{name}/tokens` | *Admin.* Mint a token `{name, scopes, expiresInDays?}` (the secret is returned once) · list |
| `DELETE` | `/v1/tenants/{name}/tokens/{id}` | *Admin.* Revoke |
| `POST` | `/v1/admin/rotate` | *Admin.* Replace a generated admin token (a configured one is changed in the environment) |
| `POST` · `GET` | `/v1/tokens` | `tokens:write`: mint a token for your own tenant, with scopes your token also has · list |
| `DELETE` | `/v1/tokens/{id}` | `tokens:write`: revoke one of your tenant's tokens, effective on the next request |
| `GET` | `/v1/whoami` | Any token: who it is and what it may do |
| `POST` | `/v1/invites/redeem` | No token needed: redeem an invite `{code, name?}` for a personal token (as a new user, or with your token as you) |
| `GET` · `PATCH` | `/v1/me` | Your user and your tenants · `/v1/me/tokens` your personal tokens (`POST {name, tenant?}` for another of your tenants) |
| `GET` · `PUT` · `DELETE` | `/v1/members/{user}` | `members:read` / `members:write`: set `{roles, groups}`; you grant only what you hold. `GET /v1/members` lists them |
| `GET` · `PUT` · `DELETE` | `/v1/roles/{name}` · `/v1/groups/{name}` | Custom roles `{scopes}` and groups `{roles}` |
| `GET` · `POST` · `DELETE` | `/v1/invites` | Invite `{roles, groups, user?, expiresInDays?}` (the code is returned once) · list · withdraw |
| `GET` | `/v1/audit` | `audit:read`: who changed what, newest first (`?limit&before`) |
| `GET` · `PATCH` · `DELETE` | `/v1/users/{name}` | *Admin.* Users: rename, disable, make or unmake operators · `POST /v1/admin/invites` invites an operator · `GET /v1/admin/audit` · `GET /v1/admin/audit/verify` checks the audit chains |
| `POST` · `GET` | `/v1/tenants/{name}/invites` · `…/members` | *Admin.* Invite a tenant's first owner · its members |
| `GET` · `PUT` | `/v1/tenants/{name}/storage-policy` | *Admin.* Which storage provider types a tenant may use, its disk allotment, private endpoints |
| `GET` | `/v1/storage` | `storage:read`: your quota, disk use, routes |
| `GET` · `PUT` · `DELETE` | `/v1/storage/providers/{name}` | Your own S3 bucket or disk folder (`storage:write`; secrets are never returned) · `POST …/check` |
| `GET` · `PUT` | `/v1/storage/routes` | Which provider new files go to, by path prefix ([storage](docs/guides/storage.md)) |
| `GET` | `/v1/secrets` | `secrets:read`: your secrets' names and versions, never their values |
| `GET` · `PUT` · `DELETE` | `/v1/secrets/{name}` | One secret's details · set it `{value}` (`secrets:write`) · delete it. Scripts read values with `secrets.get` given `secrets:use` ([secrets](docs/guides/secrets.md)) |
| `GET` | `/v1/openapi.json` | The OpenAPI 3.1 contract; no token needed |
| `POST` | `/mcp` | MCP (Streamable HTTP) for AI agents, using the same tokens ([guide](docs/guides/mcp.md)) |
| `POST` | `/v1/jobs` | `{script, input?, timeoutMs?, onOrphan?, maxAttempts?}` → `202` with the job |
| `GET` | `/v1/jobs` | Jobs for the tenant, newest first |
| `GET` | `/v1/jobs/{id}` | One job, including `attempt` and the `attempts` history |
| `GET` | `/v1/jobs/{id}/steps` | The current attempt's checkpoint and completed steps |
| `GET` | `/v1/jobs/{id}/events` | SSE: `log`, `attempt` (a new attempt started), then `end`. A finished job is replayed |
| `POST` | `/v1/jobs/{id}/cancel` | Cancel a job; a running one gets a graceful stop (`ctx.stopRequested`) |
| `PUT` | `/v1/services/{name}` | Define or update a service: `{script, config?, restart?, enabled?}` |
| `GET` | `/v1/services` · `/v1/services/{name}` | Services with `status`, `currentJobId`, `runs`, `lastExit` |
| `POST` | `/v1/services/{name}/start` · `/stop` · `/restart` | Change the desired state |
| `DELETE` | `/v1/services/{name}` | Stop and remove (releases its domains) |
| `POST` · `GET` | `/v1/services/{name}/domains` | Claim a custom domain (returns the TXT record to publish) · list |
| `POST` | `/v1/services/{name}/domains/{domain}/verify` | Check the TXT record; the domain routes once verified |
| `POST` | `/v1/bus/{topic}` · `/v1/bus/{topic}/request` | Publish to a topic · call a `bus.handle`r and get its reply |
| `POST` `GET` | `/v1/queues/{queue}` · `/v1/queues/{queue}/dead` | Push onto a durable queue · its counts and dead letters (retry, discard) |
| `GET` `POST` | `/v1/callbacks/secret` · `/v1/callbacks/secret/rotate` | The secret that signs completion callbacks (`"callback": {url, data}` on a job) |
| *ingress* | `:8000`, any path | Services' HTTP traffic, routed by host and proxied as a stream to the serving node |
| `PUT` | `/v1/files/{path}` | Streamed upload (any size up to the quota) → `201` with `ETag`; `413` over quota, `507` when the cluster is out of room |
| `GET` · `HEAD` | `/v1/files/{path}` | Download, with `Range` (206) and `If-None-Match` (304) |
| `GET` | `/v1/files?prefix=&limit=&cursor=` | List files, sorted by path |
| `DELETE` | `/v1/files/{path}` | Delete a file |
| `GET` · `PUT` · `DELETE` | `/v1/dirs/{path}` | One level of a folder (`GET /v1/dirs` for the top) · make one, and any missing above it · remove an empty one, or everything in it with `?recursive=true` ([file system](docs/design/vfs.md)) |
| `POST` | `/v1/fs/move` · `/v1/fs/copy` | `{from, to, overwrite}`: a file or a whole folder; no bytes move, and copies share theirs |
| `POST` · `GET` | `/v1/fs/share` · `/v1/shared/{token}` | `files:write`: a public link to a file `{path, expiresInSeconds}` · anyone can download it until it expires or the file changes; `POST /v1/fs/share/rotate` revokes them all |
| `GET` · `PUT` · `DELETE` | `/v1/mounts` · `/v1/mounts/{path}` | `storage:write`: bind a folder to one of your providers `{provider, readOnly}` ([storage](docs/guides/storage.md)) |
| `POST` · `PUT` · `GET` | `/v1/packages` · `/v1/packages/blobs/…` · `/v1/packages/{name}` | Publish versioned JavaScript: upload only the files the cluster lacks, then commit the manifest · list and inspect. Scripts import them by name, locked when the job is submitted ([packages](docs/guides/packages.md)) |
| `GET` | `/v1/nodes` | Live nodes with capacity and load (`nodes:read`, or the admin) |
| `GET` | `/health` | Liveness |
| `GET` | `/ready` | Readiness: 200 once joined and the cluster is active, else 503 with reasons |

Errors use one envelope: `{"error": {"code": "...", "message": "..."}}`.

The API is **contract-first**: [`openapi.yaml`](automately-server/src/main/resources/openapi.yaml) is the source of truth. Requests are validated against it, and bad input gets `400 invalid_request`. Every tenant gets a request budget (`AUTOMATELY_RATE_LIMIT_PER_MINUTE`): responses carry `RateLimit-Policy`/`RateLimit`, and going over gets `429` with `Retry-After`.

## Architecture

```
      API :8080 · ingress :8000 (any node)
                 │
          ┌──────▼──────┐   least-loaded    ┌──────────────┐
          │ ApiVerticle │──► JobScheduler ──►│ NodeVerticle │──► JavetScriptRuntime
          └──────┬──────┘    (event bus)  ▲  └──────┬───────┘     one V8 isolate per attempt
                 │                        │         │
                 │   OrphanReaper ┘         │   (re-dispatches attempts whose node died)
                 │   ServiceSupervisor ─────┘   (keeps services running: restart, backoff, roll)
                 │                                  │
                 └── JobStore · JobStateStore · ServiceStore · NodeRegistry ──┘
                     embedded Apache Ignite: durable (disk + WAL), replicated, indexed SQL,
                     compare-and-set transitions; also the Vert.x cluster manager
                     └── only in automately-cluster-ignite
```

| Module | Contents |
|---|---|
| `automately-core` | Job model and transitions, `JobStore`, `JobStateStore`, `NodeRegistry`, the `ScriptRuntime` SPI, scheduler, node verticle, orphan reaper, and services (`ServiceDefinition`, `ServiceStore`, `ServiceSupervisor`). Depends only on `vertx-core` |
| `automately-runtime-js` | The engine-neutral runtime (D-126): the bindings, an event loop per run (timers, stop requests, async host calls), the prelude and the Node and web polyfills, plus the conformance suite every engine runs |
| `automately-runtime-javet` | V8 through Javet: the runtime's engine today, an isolate per run |
| `automately-runtime-graaljs` | Embedded GraalJS, in a polyglot isolate with a heap limit per run: a second engine passing the same conformance suite, for comparison (not used by the server yet) |
| `automately-runner-protocol` | In-cluster worker-node IPC binary protocol (D-127, D-128): length-prefixed frame codec, credit-based flow control, `RemoteHostApis` proxy, and node-side RPC dispatcher |
| `automately-worker` | The in-cluster tenant worker runner (D-126, D-127): one engine built as a GraalVM native image (`worker-javet`, `worker-graaljs`) with IPC daemon mode (`worker ipc`) and engine benchmark commands |
| `automately-cluster-ignite` | The cluster is the database: Ignite bootstrap, durable tables, the Ignite stores |
| `automately-server` | Bootstrap, REST API, HTTP ingress, the shaded jar |

### Sandbox

Each job gets a **new isolate**, which is closed when the job finishes. Nothing carries over between jobs, including globals and prototype pollution.

- **No host access.** The script can reach only frozen, tenant-scoped APIs (`console`, timers, `ctx`, `kv`, `bus`, `fetch`, `log`). There is no `Java`, `require` or `process`.
- **Egress.** Every connection a script makes (`fetch`, `http`, `net`/`tls` sockets) refuses private and reserved addresses (cloud metadata included) and the cluster's own nodes, connects to the address it checked, and sockets are capped per script, per tenant and per minute (D-116). Operators configure the address spaces: allow and deny rules for the whole cluster and per tenant, named address sets, and an allow-list-only posture (D-108, D-111).
- **Wall-clock timeout.** Enforced from the event loop with `terminateExecution()`. It also covers loops at module top level and loops after an `await`.
- **Heap limit** (`AUTOMATELY_MAX_HEAP_MB`). When an isolate runs out of heap, V8 normally aborts the whole process. Here a near-heap-limit callback terminates the script instead and grants a little headroom so V8 can unwind, and the node keeps serving.
  - **What it doesn't cover:** memory outside the heap. ArrayBuffers, WebAssembly memory, ICU objects and bytes handed to the host aren't capped yet, so a script can exhaust a node's memory.
  - **The fix in progress:** per-isolate accounting and tenant worker processes with kernel memory caps (D-126, [design](docs/design/engine-neutral-runtime.md)).

The hostile-script suite (`ScriptRuntimeTest$HostileScripts`) covers each of these. [SECURITY.md](SECURITY.md) has the full threat model: who is trusted, each boundary, and what isn't defended.

### Numbers

Three nodes in Docker on one laptop (i7-1185G7, 8 CPUs), measured by [`benchmarks/run.sh`](benchmarks/run.sh):

| | |
|---|---|
| Submit → finished, trivial job | p50 29 ms, p95 159 ms |
| Ingress → service, 64 clients | ~1 900 req/s, p50 23 ms |
| Idle isolate | ~1.6 MB resident |
| 256 MB file (replicated) | upload 60 MB/s, download 338 MB/s |

Runs on one machine vary by up to 2x; [docs/benchmarks.md](docs/benchmarks.md) has every scenario and how to read them.

## Configuration

| Variable | Default | |
|---|---|---|
| `AUTOMATELY_NODE_ID` | hostname | |
| `AUTOMATELY_ADMIN_TOKEN` | *(unset: generated once and logged)* | The admin token, at least 32 characters; the same on every node |
| `AUTOMATELY_RATE_LIMIT_PER_MINUTE` | `1200` | Requests per tenant per minute across the cluster (each node enforces its share); `0` turns it off |
| `AUTOMATELY_HTTP_PORT` | `8080` | `0` = random |
| `AUTOMATELY_CAPACITY` | `8` | Concurrent jobs per node |
| `AUTOMATELY_MAX_HEAP_MB` | `128` | Per-isolate heap. Applies process-wide |
| `AUTOMATELY_DEFAULT_TIMEOUT_MS` / `AUTOMATELY_MAX_TIMEOUT_MS` | `10000` / `60000` | |
| `AUTOMATELY_HEARTBEAT_MS` | `1000` | A node is considered gone after 3 missed heartbeats |
| `AUTOMATELY_ORPHAN_GRACE_MS` | 3 × heartbeat | Extra wait before a job's node is treated as dead |
| `AUTOMATELY_REQUEUE_AFTER_MS` | `10000` | An attempt no node picked up is dispatched again after this |
| `AUTOMATELY_STOP_GRACE_MS` | `5000` | How long a stopped job or service gets to finish before it's terminated |
| `AUTOMATELY_MAX_SERVICES_PER_TENANT` | `10` | Enabled services per tenant |
| `AUTOMATELY_INGRESS_PORT` | `8000` | Services' HTTP traffic |
| `AUTOMATELY_INGRESS_DOMAIN` | `localhost` | Automatic hosts are `<service>--<tenant>.<domain>` |
| `AUTOMATELY_INGRESS_IDLE_TIMEOUT_MS` | `60000` | A proxied request with no bytes moving either way for this long is closed |
| `AUTOMATELY_INGRESS_WEBSOCKET_IDLE_TIMEOUT_MS` | `600000` | An upgraded connection (a WebSocket) with no bytes moving either way for this long is closed (D-125) |
| `AUTOMATELY_SERVICE_MAX_WEBSOCKETS` | `1000` | Upgraded connections (WebSockets) one run of a service may hold open; past it, a 503 (D-125) |
| `AUTOMATELY_MAX_REQUEST_BYTES` | `1073741824` | Largest request body the ingress accepts |
| `AUTOMATELY_INTERNAL_PORT` | `8090` | Node-to-node endpoint for proxied service traffic (cluster-only, secret-protected) |
| `AUTOMATELY_ADVERTISE_HOST` | this host's address | How other nodes reach this node's internal endpoint |
| `AUTOMATELY_EGRESS_ALLOW_PRIVATE` | `false` | Let every tenant's `fetch` reach private addresses (local development only; a node's own ports stay refused). For production, write rules in the cluster's or a tenant's egress policy |
| `AUTOMATELY_EGRESS_DNS` | | DNS servers (`1.1.1.1, 8.8.8.8:53`) that answer scripts' lookups and connections instead of the system's resolver, for a split-horizon resolver that answers internal names (D-122) |
| `AUTOMATELY_EGRESS_INTERNAL_DOMAINS` | | More internal zones (`corp.example, office.lan`) whose names scripts can't resolve unless a rule names them; `.local`, `.internal`, `.lan`, `.home.arpa`, `.localhost`, `.cluster.local`, `.svc` and single labels always are (D-122) |
| `AUTOMATELY_EGRESS_POLICY_FILE` | | A JSON cluster egress policy (`posture`, `rules`, `addressSets`) stored at the cluster's first start only; after that, `PUT /v1/egress/cluster-policy` changes it (D-111) |
| `AUTOMATELY_CLUSTERED` | `false` | |
| `AUTOMATELY_CLUSTER_PORT` | `5701` | Ignite discovery (communication uses port + 100) |
| `AUTOMATELY_CLUSTER_MEMBERS` | `127.0.0.1` | Hosts to join (`host` or `host:port`) |
| `AUTOMATELY_CLUSTER_BIND` | `AUTOMATELY_ADVERTISE_HOST` if set, else every interface | The one private address Ignite listens on (D-065) |
| `AUTOMATELY_CLUSTER_TLS` | `required` | Node certificates between nodes (D-066); `off` for development only |
| `AUTOMATELY_JOIN` | none | A one-line join bundle from `automately cluster init` or `automately node invite` (D-067) |
| `AUTOMATELY_TLS_DIR` | `<data dir>/tls` | Where a node keeps its key and certificates |
| `AUTOMATELY_CLUSTER_JOIN_TIMEOUT_MS` | `60000` | How long a refused node keeps trying before its start fails |
| `AUTOMATELY_DATA_DIR` | *(unset: in memory)* | Durable storage for this node; the image sets `/var/lib/automately` |
| `AUTOMATELY_DATA_BACKUPS` | `1` | Extra copies of all data; survives that many simultaneous node losses |
| `AUTOMATELY_CLUSTER_SIZE` | `1` | Nodes a brand-new durable cluster waits for before activating |
| `AUTOMATELY_DATA_MEMORY_MB` | `256` | Off-heap memory for the data region (the rest stays on disk). At least `128` |
| `AUTOMATELY_TENANT_STORAGE_MB` | `1024` | Per tenant, files and `kv` together; over it, uploads get 413 and `kv` writes fail |
| `AUTOMATELY_PACKAGE_CACHE_MB` | `64` | Package files each node keeps in memory once fetched and checked, least recently used out first |
| `AUTOMATELY_FILES_BACKEND` | `cluster` | Where file bytes live: `cluster` (replicated in the cluster), `disk` or `s3` ([storage](docs/design/storage-providers.md)) |
| `AUTOMATELY_FILES_DIR` | | The folder for `disk` (shared by every node in a cluster) |
| `AUTOMATELY_S3_BUCKET` · `_ENDPOINT` · `_REGION` · `_PREFIX` · `_PATH_STYLE` | | The bucket for `s3`; any S3-compatible endpoint (MinIO, R2, B2, Wasabi) |
| `AUTOMATELY_S3_ACCESS_KEY_ID` · `_SECRET_ACCESS_KEY` | `AWS_*` | Credentials for `s3`; the standard `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_SESSION_TOKEN` also work |
| `AUTOMATELY_SECRETS_KEY` | | Encrypts tenants' secrets and storage credentials; required for secrets and tenant S3 providers (at least 32 characters, the same on every node) |
| `AUTOMATELY_DISK_ROOT` | | Where tenant disk providers live; must be shared storage in a cluster ([storage](docs/guides/storage.md)) |
| `AUTOMATELY_METRICS_PORT` | `9090` | Prometheus `/metrics`, on a private network; `off` disables it |
| `AUTOMATELY_LOG_FORMAT` | `text` | `json` for one JSON object per line, with `jobId`/`tenant`/`status` fields |
| `AUTOMATELY_MIN_FREE_DISK_MB` | `1024` | Durable nodes refuse writes (507) below this much free disk |
| `AUTOMATELY_BASELINE_ADJUST_MS` | `60000` | How long a missing data node is waited for before its data is rebalanced |
| `AUTOMATELY_JOB_RETENTION_DAYS` | `7` | Finished jobs older than this are deleted |
| `AUTOMATELY_AUDIT_RETENTION_DAYS` | `90` | Audit entries older than this are deleted (D-071), once exported if there's an export |
| `AUTOMATELY_AUDIT_S3_BUCKET` · `_ENDPOINT` · `_REGION` · `_PREFIX` · `_ACCESS_KEY_ID` · `_SECRET_ACCESS_KEY` | | Export the audit log to this bucket as NDJSON (D-073); each falls back to `AUTOMATELY_S3_*` and `AWS_*` |
| `AUTOMATELY_AUDIT_EXPORT_INTERVAL_S` | `300` | How often the audit log is exported |
| `AUTOMATELY_TMP_MEMORY_MB` · `AUTOMATELY_TMP_MB` | `8` · `1024` | A job's `/tmp`: kept in memory up to the first, then spilled to the data directory up to the second (64 MB in memory without a data directory) |

## Build

```bash
./mvnw verify -DskipITs   # JDK 21. The unit tests: 2- and 3-node clusters in one JVM, chaos, full restarts
./mvnw verify             # also DockerClusterIT: 3 containers, SIGKILL mid-job (needs Docker, about 6 minutes)
```

Javet's native library is included for linux-x86_64 only for now.

## What changed since 2017

| 2017 | Now | Enforced by |
|---|---|---|
| Scripts ran in the host JVM behind a class loader | One V8 isolate per job with a timeout and heap limit | Hostile-script tests |
| Static state in `JobServer`, `UserData` and others | Services are verticles with injected interfaces | ArchUnit: static fields must be final |
| Nested callbacks and blocking map calls on the event loop | Vert.x 5 futures, with blocking work in `executeBlocking` | ArchUnit: no hand-rolled threads or `Executors` |
| Hazelcast types in 39 files; data lost when the whole cluster stopped | Embedded Apache Ignite as cluster and database (durable, replicated), behind store interfaces | ArchUnit: Ignite only in `automately.cluster.ignite`; full-restart and store contract tests |
| Regex routing by hand, no API description | `vertx-web` `Router`, an OpenAPI 3.1 contract that validates every request, one table of what each route needs | `ContractTest` (routes = spec, every response validated), `AuthTest` |
| HTTP Basic with a long-lived username and API key on every request | Scoped, revocable, hashed bearer tokens per tenant, and an admin token that can't read tenant data | `AuthTest`, `AuthenticatorTest` |
| No tests | 1,069 unit tests, the runtime's conformance suite run on both engines among them (hostile scripts, orphan scenarios, services, host APIs, queues, callbacks, egress rules, files, packages, Node stand-ins, auth, the API contract, MCP, the CLI and its installer, file backends on S3 and disk, tenant storage providers, 2- and 3-node clusters, chaos, architecture), 7 Docker integration tests that use real `SIGKILL`s, and real npm libraries checked against Node: 41 alone, plus 5 database and mail drivers against real servers | `./mvnw verify` |
| `callbackUrl`: one unsigned POST of `{success, error}` when a job finished, never retried | A signed POST of the result and your `data` for every way a job ends, retried through a durable queue | `CallbacksTest`, `CallbackDeliveryTest` |
| `messagebus`: at most once, lost if nobody listened | The same bus, plus durable queues: at least once, leases, retries, dead letters, surviving a node's death | `QueueStoreContract` on both stores, a three-node failover test, the demo |
| `Automately.startJob(..., waitForJob)` blocked a thread until the child finished | `jobs.run()` returns a handle whose `result()` is a promise; children stay in the tenant, with depth and rate limits | `JobsFromScriptsTest` |
| Host APIs were Java objects in the script's scope, reflection included | Java objects again, one per API, bound with only their annotated methods; thin JavaScript glue in files | `NodeHostApisTest`, hostile-script tests |
| Synchronous host objects running in the host JVM (Nashorn, `--no-java`); HTTP client with no address checks | Promise-based, tenant-scoped `kv`/`bus`/`fetch`/`log`; SSRF-safe egress; verified domains | `NodeHostApisTest`, `HostApisTest` |
| Service jobs: one per name, autostarted at boot, no restart on crash | Supervised services: restart with backoff, revision rolls, graceful stop, checkpoints across runs | Supervisor, chaos and Docker tests |
| A crashed node's jobs were simply lost or restarted blindly | Attempts with fencing, `onOrphan` policy, resumable steps | Chaos test (kills the node mid-step) |

## Roadmap

1. ~~**Spike**~~ ✅ Cluster, V8 runtime, API, SSE, CI
2. ~~**Orphaned jobs**~~ ✅ `onOrphan` policy (`fail` by default), attempts visible to scripts, `ctx.step` and checkpoints, fencing, and a chaos test ([design](docs/design/orphaned-jobs.md), [script API](docs/scripting/job-context.md), [plan](docs/design/phase-2-plan.md))
2b. ~~**Long-running services**~~ ✅ Supervised services, timers and an event loop per isolate, job cancellation ([design](docs/design/services.md), [guide](docs/scripting/services.md))
3. ~~**Host APIs**~~ ✅ `kv`, `bus`, `fetch`, `log`, HTTP hosting with automatic and verified domains; isolate pooling measured and declined ([design](docs/design/host-apis.md), [reference](docs/scripting/host-apis.md))
3b. ~~**The cluster is the database, streaming, files**~~ ✅ Embedded Ignite (durable, replicated), streamed HTTP, a `files` API with ranges, storage admission control ([design](docs/design/durability-and-streaming.md))
4. ~~**API, auth, CLI and MCP**~~ ✅ Scoped tokens, an OpenAPI contract that validates every request, rate limits, a native CLI with a one-line install, and an MCP server for AI agents ([plan](docs/design/phase-4-plan.md), [CLI](docs/guides/cli.md), [MCP](docs/guides/mcp.md))
5. **Observability and polish.** ✅ Metrics, readiness, JSON logs, a Grafana dashboard ([plan](docs/design/phase-5-plan.md)), [benchmarks](docs/benchmarks.md), [SECURITY.md](SECURITY.md). ✅ The demo ([scripts/demo.sh](scripts/demo.sh))
6. ~~**Security groundwork**~~ ✅ Closed ports, mutual TLS between nodes, tenant secrets, signed releases, users, roles and a tamper-evident audit log (D-065 to D-074)
7. ~~**The file system**~~ ✅ Folders, moves and copies, mounts, Node-style `fs` with `/tmp`, handles, locks and public links ([design](docs/design/vfs.md))
8. ~~**Packages**~~ ✅ Versioned JavaScript published once and imported by name, locked per job; deploy a version as a service; yank, block, purge; stand-ins for Node's `path`, `events`, `buffer`, `url`, `util` and `process`; real npm libraries tested against Node ([design](docs/design/packages.md), [guide](docs/guides/packages.md))
9. **More of Node in the isolate** ([design](docs/design/isolate-polyfills.md), [reference](docs/scripting/node-compat.md))
   - ~~**Phase A: computation**~~ ✅ `crypto` on the JVM and Web Crypto, `stream`, `zlib`, `assert`, `os`, web globals, `process.env` per job (D-095 to D-106)
   - **Phase B: the network** (in progress; [design](docs/design/network-reach.md), order in §9). Scripts get Node's real behaviour, restricted only in where they may connect and how much they may use.
     - ✅ One egress gate for every connection, and Node's `http`/`https` clients, with axios and node-fetch matching Node (D-108, D-109). Unhandled promise rejections fail a job, as in Node (D-110).
     - ✅ Configurable address spaces: allow and deny rules for the whole cluster and per tenant, named address sets, and an allow-list-only posture (D-111, D-113)
     - ✅ `got`, with `req.socket` reporting the real connection; `diagnostics_channel`; the isolate reports Node 26.8.1 (D-114, D-115)
     - ✅ `net`/`tls` sockets, so pg, mysql2, ioredis, MongoDB and nodemailer work, each checked against real Node on a real server (D-116)
     - ✅ An `http2` client as Node 26 has it (request trailers, `state`, priorities, the real flags), and got with `http2: true` (D-117, D-118)
     - ✅ `dns` as Node's, through the gate's resolver: every record type with real TTLs, script-chosen servers checked by the gate, and only what the tenant could reach shown; `mongodb+srv://` works (D-122). The event loop runs Node's phases (D-121)
     - ✅ Node's HTTP servers in services, reached by hostname: a host per port, path prefixes, domains per port, a plain `app.js` with no default export, `SIGTERM` on stop (D-123)
     - ✅ Express 5, Koa 3 and Fastify 5 answer as under real Node
     - ✅ The WHATWG globals as Node's, loaded on first use: `Blob`/`File`, Web Streams and `stream/web`, `Headers`/`Request`/`Response`/`FormData`, the text and compression streams, `MessageChannel`/`BroadcastChannel`. The global `fetch` is Node's; the host's is `automately:fetch` (D-124)
     - ✅ WebSockets: the ingress tunnels upgraded connections, Node's server emits `upgrade`, and `node:http` upgrades over the job's own sockets. The ws library's client and server and the global `WebSocket` match real Node (D-125). Raw TCP and UDP servers never run on core nodes.
     - **Phase B is complete.** Next: the engine-neutral runtime and tenant workers (item 10), then the Node runtime (item 11).
10. **Engine-neutral runtime and tenant workers** (in progress; [design](docs/design/engine-neutral-runtime.md), D-126, D-127). The engine can be swapped, and the worker↔node protocol is designed in full now and built once the engine is chosen.
    - ✅ The bindings, event loop and polyfills on a small SPI, with V8 (Javet) as the first engine; the runtime's tests became a conformance suite every engine runs
    - ✅ Embedded GraalJS, in a polyglot isolate with a heap limit per run, passing the same suite (137/137 tests passing)
    - ✅ Both compared inside native-image tenant workers with kernel memory caps ([results and trade-offs](docs/design/engine-neutral-runtime.md#8-the-comparison-run-2026-10-10)). GraalJS is the safer engine (no memory attack reached the kernel's cap), and V8 the faster one (2–9× on object-heavy code)
    - ✅ Engine selection decided as a deployment-level choice (`AUTOMATELY_WORKER_ENGINE=graaljs|javet`, D-127). Both engines supported.
    - ✅ Dedicated normative IPC specification for in-cluster workers: [worker-node-protocol.md](docs/design/worker-node-protocol.md)
    - ✅ Step 4 implemented: `automately-runner-protocol` binary framing, credit-based flow control, zero-trust dispatcher, and RemoteHostApis proxy (D-128)
    - ✅ Step 5 implemented: stream multiplexing (`StreamMultiplexer`), warm tenant worker pool supervisor (`TenantWorkerPool`, Linux cgroups v2 memory limits), and Unix Domain Socket transport (`UdsServerTransport`, `UdsClientTransport`, D-129)
    - **Item 10 is complete.** Next: the Node runtime in gVisor containers (item 11).
11. **The Node runtime:** native code and the rest of Node, in gVisor containers ([design](docs/design/node-runtime.md); accepted, not built). It starts after phase B, with its spikes: the runner's speed, rootless Podman with gVisor, and networking passed out through the runner. It reuses phase B's egress gate and routing.
12. **Links:** your own devices and servers joining the cluster, and the only place raw TCP and UDP servers may run (design under review; [NOTES.md](NOTES.md)).
13. **Then:** apps, the console, databases and Python.

**Also queued:** `automately packages check <npm package>`, which reports whether a package runs in the isolate before anyone writes code against it; and CLI output that streams across a service's restarts and redeploys.

Parked ideas, such as remote workers offering a sandboxed browser, are in [NOTES.md](NOTES.md).

## Docs

- [CHANGELOG.md](CHANGELOG.md): what changed and why
- [SECURITY.md](SECURITY.md): the threat model, and how to report a vulnerability
- [docs/benchmarks.md](docs/benchmarks.md): measured performance, and how to rerun it
- [docs/DECISIONS.md](docs/DECISIONS.md): the decision log
- [docs/design/revival-plan.md](docs/design/revival-plan.md): the original plan (why and what), with current status
- [docs/design/](docs/design/): designs for upcoming work, including [the file system](docs/design/vfs.md), [packages](docs/design/packages.md), [network reach](docs/design/network-reach.md) and [the Node runtime](docs/design/node-runtime.md)
- [docs/guides/packages.md](docs/guides/packages.md): publishing packages and importing them from scripts
- [docs/scripting/job-context.md](docs/scripting/job-context.md): the job context API for scripts (attempts, steps, checkpoints)
- [docs/scripting/services.md](docs/scripting/services.md): services, timers and cancellation
- [docs/scripting/host-apis.md](docs/scripting/host-apis.md): `kv`, `bus` and queues, `fetch`, `log`, `files`, and serving HTTP
- [docs/scripting/node-compat.md](docs/scripting/node-compat.md): Node compatibility in the isolate: every stand-in module and global, with its limits and differences from Node
- [docs/guides/cli.md](docs/guides/cli.md): installing and using the CLI
- [docs/guides/mcp.md](docs/guides/mcp.md): connecting AI agents over MCP
- [docs/guides/callbacks.md](docs/guides/callbacks.md): receiving a job's result as a signed POST when it ends
- [docs/guides/cluster.md](docs/guides/cluster.md): node certificates: creating a cluster, adding, removing and renewing nodes
- [docs/guides/storage.md](docs/guides/storage.md): file storage, from the cluster's default to tenants' own buckets and folders
- [docs/guides/secrets.md](docs/guides/secrets.md): secrets: setting them, and which scripts may read them
- [docs/guides/users.md](docs/guides/users.md): users, invites, roles and groups, operators, and the audit log
- [docs/guides/releasing.md](docs/guides/releasing.md): for maintainers: signing and publishing a release

## Author

**Adam & Lynn Incorporated** · tony@adamnlynn.com

## License

[MPL-2.0](LICENSE.md), the same licence as the original Automately Core.
