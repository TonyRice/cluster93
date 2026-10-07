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

These are native binaries that need no Java, and the installer checks each one's checksum. See the [CLI guide](docs/guides/cli.md). To let an AI agent use the cluster, see [MCP](docs/guides/mcp.md).

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

Scripts can store data (`kv`), start and await other jobs (`jobs`), message each other (`bus`, with durable **queues** that survive a node dying), call out (`fetch`, with SSRF protection) and log with structure (`log`); services can **serve HTTP** at `<service>--<tenant>.<domain>` or your own verified domain. See the **[host APIs reference](docs/scripting/host-apis.md)**.

**Services** are scripts the cluster keeps running: if one crashes or its node dies, it's restarted (with backoff) somewhere healthy, carrying its checkpoint over. See the **[services guide](docs/scripting/services.md)**.

If the node running a job dies, the job's `onOrphan` policy decides what happens: `fail` (the default) ends it as `ORPHANED`; `retry` starts again from the beginning; `resume` continues on another node and skips steps that already completed. See the **[job context reference](docs/scripting/job-context.md)**.

## API

Every `/v1` call carries `Authorization: Bearer <token>`.

- **Admin token.** It manages tenants and their tokens, and cannot read or change tenant data (D-044).
- **Tenant tokens** (`atly_…`) belong to one tenant and carry **scopes**: `jobs:read`, `jobs:write`, `services:read`, `services:write`, `files:read`, `files:write`, `bus:publish`, `bus:request`, `queues:read`, `queues:write`, `tokens:write`, `nodes:read`, `storage:read`, `storage:write`, `secrets:read`, `secrets:write`, `secrets:use`, or `*` for all of them.
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
| `automately-runtime-javet` | `ScriptRuntime` on V8 isolates, with an event loop per isolate (timers, stop requests, async host calls) |
| `automately-cluster-ignite` | The cluster is the database: Ignite bootstrap, durable tables, the Ignite stores |
| `automately-server` | Bootstrap, REST API, HTTP ingress, the shaded jar |

### Sandbox

Each job gets a **new isolate**, which is closed when the job finishes. Nothing carries over between jobs, including globals and prototype pollution.

- **No host access.** The script can reach only frozen, tenant-scoped APIs (`console`, timers, `ctx`, `kv`, `bus`, `fetch`, `log`). There is no `Java`, `require` or `process`.
- **Egress.** `fetch` refuses private and reserved addresses (cloud metadata included), connects to the address it checked, and follows no redirects.
- **Wall-clock timeout.** Enforced from the event loop with `terminateExecution()`. It also covers loops at module top level and loops after an `await`.
- **Heap limit** (`AUTOMATELY_MAX_HEAP_MB`). When an isolate runs out of memory, V8 normally aborts the whole process. Here a near-heap-limit callback terminates the script instead and grants a little headroom so V8 can unwind, and the node keeps serving.

The hostile-script suite (`JavetScriptRuntimeTest$HostileScripts`) covers each of these. [SECURITY.md](SECURITY.md) has the full threat model: who is trusted, each boundary, and what isn't defended.

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
| `AUTOMATELY_MAX_REQUEST_BYTES` | `1073741824` | Largest request body the ingress accepts |
| `AUTOMATELY_INTERNAL_PORT` | `8090` | Node-to-node endpoint for proxied service traffic (cluster-only, secret-protected) |
| `AUTOMATELY_ADVERTISE_HOST` | this host's address | How other nodes reach this node's internal endpoint |
| `AUTOMATELY_EGRESS_ALLOW_PRIVATE` | `false` | Let `fetch` reach private addresses (local development only) |
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

## Build

```bash
./mvnw verify      # JDK 21. Unit tests (2- and 3-node clusters in one JVM, chaos, full restarts), then
                   # DockerClusterIT: 3 containers, SIGKILL mid-job (needs Docker; -DskipITs to skip)
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
| No tests | 437 unit tests (hostile scripts, orphan scenarios, services, host APIs, queues, callbacks, egress, files, auth, the API contract, MCP, the CLI and its installer, file backends on S3 and disk, tenant storage providers, 2- and 3-node clusters, chaos, architecture) plus 6 Docker integration tests that use real `SIGKILL`s | `./mvnw verify` |
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

Parked ideas, such as remote workers offering a sandboxed browser, are in [NOTES.md](NOTES.md).

## Docs

- [CHANGELOG.md](CHANGELOG.md): what changed and why
- [SECURITY.md](SECURITY.md): the threat model, and how to report a vulnerability
- [docs/benchmarks.md](docs/benchmarks.md): measured performance, and how to rerun it
- [docs/DECISIONS.md](docs/DECISIONS.md): the decision log
- [docs/design/revival-plan.md](docs/design/revival-plan.md): the original plan (why and what), with current status
- [docs/design/](docs/design/): designs for upcoming work
- [docs/scripting/job-context.md](docs/scripting/job-context.md): the job context API for scripts (attempts, steps, checkpoints)
- [docs/scripting/services.md](docs/scripting/services.md): services, timers and cancellation
- [docs/scripting/host-apis.md](docs/scripting/host-apis.md): `kv`, `bus` and queues, `fetch`, `log`, `files`, and serving HTTP
- [docs/guides/cli.md](docs/guides/cli.md): installing and using the CLI
- [docs/guides/mcp.md](docs/guides/mcp.md): connecting AI agents over MCP
- [docs/guides/callbacks.md](docs/guides/callbacks.md): receiving a job's result as a signed POST when it ends
- [docs/guides/cluster.md](docs/guides/cluster.md): node certificates: creating a cluster, adding, removing and renewing nodes
- [docs/guides/storage.md](docs/guides/storage.md): file storage, from the cluster's default to tenants' own buckets and folders
- [docs/guides/secrets.md](docs/guides/secrets.md): secrets: setting them, and which scripts may read them

## Author

**Adam & Lynn Incorporated** · tony@adamnlynn.com

## License

[MPL-2.0](LICENSE.md), the same licence as the original Automately Core.
