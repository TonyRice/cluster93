# Cluster93

A self-hostable, clustered, distributed backend platform for resilient background jobs and supervised HTTP/WebSocket services—bringing the execution model of **Cloudflare Workers** and **AWS Lambda** to your own infrastructure with zero external database dependencies.

You submit code to any node. The cluster schedules it on the least-loaded worker inside an isolated sandbox, streams execution live, and handles state, messaging, and storage out of the box.

> **Status: Phases 1–5 complete · Phase 6 (web console & identity) in progress · Phase 11 (consolidation & release readiness) active**
>
> - **Dual Execution Engines:** Choose between high-throughput **V8 isolates** (via Javet) and memory-safe **GraalJS**, running against a shared runtime conformance suite.
> - **Process & Kernel Isolation:** Run as lightweight in-process isolates (`embedded`) or out-of-process warm tenant worker pools with **Linux cgroups v2** memory enforcement (`process`).
> - **Resilient Compute Primitives:** Fault-tolerant background jobs with step checkpointing (`ctx.step`) and automatic orphan failover, plus supervised long-running microservices with live ingress routing.
> - **Zero External Databases:** Embedded **Apache Ignite 2.18** provides durable disk persistence (WAL), replicated tables, and Vert.x cluster coordination in a single process.
> - **Virtual File System & Storage:** Replicated VFS with directory trees, atomic moves, public share links, and pluggable storage mounts (local disk, S3-compatible backends).
> - **Node.js & Web Standards:** Native support for `node:http`, `node:net`, `node:tls`, `node:crypto`, `node:stream`, `fetch`, `WebSocket`, and real npm packages (Express 5, Fastify 5, Koa 3, `pg`, `mysql2`, `ioredis`, `mongodb`).
> - **Built-in AI & Tooling:** First-class **Model Context Protocol (MCP)** server on every node over Streamable HTTP, a contract-first OpenAPI 3.1 REST API, and single-binary native CLI.

> **Heritage:** Cluster93 is a continuation of the open-source **Automately**
> platform (founded 2014, acquired before 2018). [See the archived original →][wayback]

---

## Install the CLI

Prebuilt native binaries cover **Linux** (x64 and arm64) and **macOS** (Apple silicon and Intel):

```bash
curl -fsSL https://github.com/TonyRice/cluster93/releases/latest/download/install.sh | sh
```

On **Windows**, the PowerShell installer sets up the portable CLI jar (`cluster93-cli.jar` + a
`c93.cmd` shim, with Java 21 detection):

```powershell
irm https://github.com/TonyRice/cluster93/releases/latest/download/install.ps1 | iex
```

GraalVM `native-image` can't cross-compile, so a native binary has to be built on its own platform;
where one isn't published, the portable jar runs anywhere with **Java 21+** — see the
[CLI guide](docs/guides/cli.md#build-from-source).

| Platform | Asset | How |
|---|---|---|
| Linux x64 (static, any distro incl. Alpine) | `cluster93-linux-x64` | `install.sh` |
| Linux arm64 | `cluster93-linux-arm64` | `install.sh` |
| macOS (Apple silicon / Intel) | `cluster93-macos-arm64` / `-x64` | `install.sh` |
| Windows x64 | portable jar + `c93.cmd` | `install.ps1` (Java 21+) |
| anywhere else | portable jar | build from source, or `java -jar cluster93-cli.jar` |

The native **workers** the server spawns are Linux-only (`bin/worker-*`, x64 and arm64). A node
elsewhere runs `C93_WORKER_MODE=embedded`, and on Windows a node is Docker or WSL2 — see the
[node guide](docs/guides/node.md).

Keep it current with `c93 update`: `--cli` replaces the CLI (the native binary, or the jar it runs
from), `--node` re-fetches the node, and neither flag does both — always after checking the release's
signature and checksum.

Native binaries compile via GraalVM with zero JVM requirements on the client. See the [CLI guide](docs/guides/cli.md). To connect AI agents via MCP, see the [MCP guide](docs/guides/mcp.md).

---

## Run a node

`c93` installs and runs a node too. It fetches a signed release, lays out the tree, writes `node.env`
and a service unit, and starts it — no checkout needed (a node needs **Java 21+**):

```bash
c93 node install                 # a per-user node (systemd --user, or a LaunchAgent on macOS)
sudo c93 node install --system   # a system-wide node (Linux): /opt/cluster93, a cluster93 user, a system unit
c93 node status                  # backend, PID, /health, /v1/nodes
c93 node logs --follow
c93 node env --set HTTP_PORT=9494 && c93 node restart
```

The one-line installer can do it in one go: `… | sh -s -- --node` (add `--system` for a system node).
A node is Docker or WSL2 on Windows. Full details in the [node guide](docs/guides/node.md).

---

## The Chaos Demo

```bash
scripts/demo.sh        # Requires Docker, JDK 21, curl, python3; takes ~90s
```

The automated demo spins up a 3-node cluster and demonstrates live failover:
1. Three nodes start with Prometheus and Grafana.
2. An AI agent deploys a service over MCP that consumes a durable queue of orders and serves progress over HTTP.
3. The agent enqueues 120 orders.
4. The node executing the active service is abruptly terminated with `SIGKILL`.

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

- The service automatically restarts on a healthy survivor node with its state carried over.
- In-flight orders from the dead node return to the queue when their leases expire.
- Grafana (http://localhost:3000) visualizes cluster health, isolate metrics, and queue drainage. Run `scripts/demo.sh down` to clean up.

---

## Quickstart

### Local checkout stack

One command builds what is missing, starts a single node, seeds the `dev` tenant and an operator login, and makes
the `dev` profile active:

```bash
c93 dev up
c93 run examples/js/01-hello-job.js
c93 dev status
c93 dev logs --follow
c93 dev test-examples
c93 dev down
```

- **Data:** `~/.cluster93/dev-data` (persistent until `c93 dev reset --yes`).
- **Ports:** API `9393`, ingress/console `9394`, metrics `9395`.
- **Profile:** `c93 dev up` saves the operator login in the `dev` profile; `c93 profiles use dev` selects it later.
- **Build only:** `c93 dev build` (server, and the worker binaries if they are missing).
- **Re-seed:** `c93 dev seed` (idempotent).
- **`c93 local …`** remains a hidden alias of `c93 dev …` for one release.

No Docker, no releases, no shell orchestrator.

### A permanent node

For a machine that should keep a node as a service, install it rather than running a checkout stack:

```bash
c93 node install                 # inside a checkout: build source; otherwise: the latest release
c93 node install --release       # force a release even inside a checkout
c93 node install --docker        # one node container; --workers N adds local worker containers
c93 node status
```

A node joins an existing cluster with a line from `c93 cluster invite`:

```bash
c93 cluster init --nodes node1,node2,node3 --api http://node1:9393
c93 cluster invite node2 --host 10.0.0.12
c93 node install --checkout --join "$(cat cluster-tls/node2.join)" --members node1,node3
```

`c93 node setup` and `scripts/local.sh` are gone; a checkout is `c93 dev`, a permanent node is `c93 node install`.

### Docker Quickstart (3-Node Resilient Cluster)

#### 1. Launch a Multi-Node Cluster

```bash
export C93_ADMIN_TOKEN=$(openssl rand -hex 32)
docker compose up --build -d        # Starts three nodes on :9401, :9402, :9403
ADMIN="Authorization: Bearer $C93_ADMIN_TOKEN"

# Create a tenant and mint a scoped bearer token
curl -s -X POST localhost:9401/v1/tenants -H "$ADMIN" -d '{"name": "demo"}'
TOKEN=$(curl -s -X POST localhost:9401/v1/tenants/demo/tokens -H "$ADMIN" \
  -d '{"name": "dev", "scopes": ["*"]}' | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"
```

### 2. Submit a Resilient Job

Jobs are ES modules receiving input and a execution context (`ctx`). Steps wrapped in `ctx.step()` execute with idempotency keys and are never repeated if an attempt resumes on another node:

```bash
curl -s -X POST localhost:9401/v1/jobs -H "$AUTH" \
  -d '{
    "script": "export default async (input, ctx) => {\n  const res = await ctx.step(\"fetch-data\", () => ({ count: input.count * 2 }));\n  await ctx.saveCheckpoint({ processed: true });\n  return { ok: true, result: res };\n}",
    "input": {"count": 21}
  }'
```

Stream logs and execution events in real time from any node via Server-Sent Events (SSE) or the CLI:
```bash
curl -N localhost:9403/v1/jobs/<id>/events -H "$AUTH"
c93 jobs logs <id>                               # Live stream via CLI
```

Or run directly with live console streaming:
```bash
c93 run job.js --input '{"count": 21}'           # Streams stderr logs live, stdout returns result
```

### 3. Deploy a Supervised HTTP Microservice

Services run continuously under cluster supervision with health monitoring, automatic restart backoff, and host-based routing:

```bash
curl -s -X PUT localhost:9401/v1/services/web -H "$AUTH" \
  -d '{
    "script": "import http from \"node:http\";\nconst server = http.createServer((req, res) => res.end(JSON.stringify({ status: \"healthy\" })));\nserver.listen(8080);",
    "enabled": true
  }'
```

Once running, requests arriving at the ingress port (`:9411`) matching `<service>--<tenant>.<domain>` route directly to the serving node:
```bash
curl -H "Host: web--demo.localhost" http://localhost:9411/
```

Or deploy and stream logs live via the CLI:
```bash
c93 login http://localhost:9401 --token "$TOKEN"
c93 services deploy web server.js
c93 services logs web                           # Follow live console output
```

---

## REST API & Contract

Cluster93 uses a **contract-first architecture**: [`openapi.yaml`](server/src/main/resources/openapi.yaml) is the single source of truth. Every request is validated against the specification before reaching application handlers.

All `/v1` endpoints require an HTTP Bearer token:
- **Admin Token:** Manages tenants, operators, and storage policies. Admin tokens cannot read or manipulate tenant data (D-044).
- **Tenant Tokens (`c93_*`):** Scoped to specific permissions (`jobs:write`, `services:read`, `files:write`, etc.) and rate-limited via token buckets (`RateLimit-Policy` / `RateLimit`).
- **Audit Logging:** Every administrative and configuration event is recorded in a tamper-evident audit log (`/v1/audit`).

For complete schema details and parameter documentation, refer to the [API Endpoint Reference](docs/api/endpoints.md) or inspect `/v1/openapi.json`.

---

## Architecture & Execution Model

```
       Public API :9393 · Ingress :9394 (Any Node)
                         │
                  ┌──────▼──────┐
                  │ ApiVerticle │
                  └──────┬──────┘
                         │
             ┌───────────┴───────────┐
             ▼                       ▼
      ┌──────────────┐        ┌──────────────┐
      │ JobScheduler │        │ IngressProxy │
      └──────┬───────┘        └──────┬───────┘
             │ (Event Bus)           │ (HTTP Proxy Stream)
             ▼                       ▼
      ┌──────────────┐        ┌──────────────┐
      │ NodeVerticle │◀───────│InternalServer│
      └──────┬───────┘        └──────────────┘
             │
             ├──▶ Mode: embedded  ──▶ Isolate Thread (Javet V8 / GraalJS)
             │
             └──▶ Mode: process   ──▶ StreamMultiplexer (UDS / Stdio)
                                             │
                                             ▼
                                  ┌──────────────────────┐
                                  │   TenantWorkerPool   │
                                  │ (cgroups v2 mem caps)│
                                  └──────────┬───────────┘
                                             ▼
                                  ┌──────────────────────┐
                                  │  cluster93-worker   │
                                  │ (V8 / GraalJS native)│
                                  └──────────────────────┘
                         │
    ┌────────────────────┴────────────────────┐
    ▼                                         ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│     OrphanReaper        │         │   ServiceSupervisor     │
│ (Reassigns dead attempts│         │ (Supervises services,   │
│  with compare-and-set)  │         │  restarts with backoff) │
└─────────────────────────┘         └─────────────────────────┘
                         │
    ┌────────────────────┴────────────────────┐
    ▼                                         ▼
┌─────────────────────────────────────────────────────────────┐
│  Ignite Cluster Stores (Tables, WAL, Replicated Caches)     │
│  JobStore · JobStateStore · ServiceStore · NodeRegistry ·   │
│  KvStore · FileStore · EgressPolicyStore                    │
└─────────────────────────────────────────────────────────────┘
```

### Module Overview

| Module | Responsibility | Key Classes |
|---|---|---|
| [`cluster93-core`](core) | Job lifecycle, state transitions, scheduling, storage SPIs, services supervisor. Depends only on `vertx-core`. | `NodeVerticle`, `JobScheduler`, `ServiceSupervisor`, `NodeHostApis` |
| [`cluster93-runtime-js`](runtime-js) | Engine-neutral JS SPI, polyfills (`node:*`, web standards), event loop orchestration, and conformance test harness. | `JsEngine`, `JsContext`, `ScriptRunner`, `HostFunction` |
| [`cluster93-runtime-javet`](runtime-javet) | V8 engine adapter using Javet. Optimized for throughput and JSON manipulation. | `JavetEngine` |
| [`cluster93-runtime-graaljs`](runtime-graaljs) | GraalJS engine adapter. Memory-safe polyglot sandbox isolating host memory. | `GraalJsEngine` |
| [`cluster93-runner-protocol`](runner-protocol) | IPC binary framing codec, credit-based flow control, UDS transport, and warm tenant worker pool supervisor. | `FrameCodec`, `StreamMultiplexer`, `TenantWorkerPool`, `RemoteHostApis` |
| [`cluster93-worker`](worker) | Standalone native runner daemon for out-of-process execution. | `WorkerMain` |
| [`cluster93-cluster-ignite`](cluster-ignite) | Embedded Apache Ignite 2.18 persistence, WAL, replicated caches, and cluster manager. | `IgniteClusterManager`, `IgniteJobStore` |
| [`cluster93-server`](server) | REST API endpoints, OpenAPI validation, ingress proxy, WebSocket tunnels, and MCP server. | `Cluster93`, `ApiVerticle`, `IngressVerticle` |
| [`cluster93-cli`](cli) | Single-binary CLI client compiled with GraalVM Native Image. | `CliMain` |
| [`cluster93-tls`](tls) | Mutual TLS utilities, node join tokens, and cluster certificates. | `TlsConfig` |

For an in-depth component guide, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Isolation & Security

Cluster93 enforces isolation across multiple layers:

1. **Dual JS Engines:**
   - **V8 (Javet):** Delivers maximum raw execution speed (2–9× faster on object/JSON-heavy code).
   - **GraalJS:** Memory-safe polyglot engine that stops memory exhaustion attacks inside the isolate without aborting the host process.
2. **Kernel-Level Memory Enforcement:**
   - In `process` mode, workers run in dedicated child processes monitored by Linux **cgroups v2** (`memory.max` and `memory.high`). A misbehaving tenant process that allocates unmanaged memory is terminated by the kernel without affecting cluster nodes.
3. **Zero-Trust Host RPC Authorization:**
   - In worker mode, authority operations (`kv`, `files`, `bus`, `secrets`, network) travel over Unix Domain Sockets as length-prefixed IPC frames. Every incoming call is verified by `NodeRpcAuthorizer` against the verified tenant context before execution.
4. **SSRF-Safe Egress Gate:**
   - All outbound network calls (`fetch`, `http`, `net`/`tls`, `dns`) pass through a central egress gate that resolves hostnames before connecting, blocks loopback and private IP ranges (RFC 1918, cloud metadata endpoints), rejects DNS rebinding, and enforces per-tenant socket caps.
5. **No Host Access:**
   - Guest code runs in a sandboxed realm without access to `Java`, the host JVM classpath, or arbitrary local filesystem paths.

Threat models and defense boundaries are detailed in [SECURITY.md](SECURITY.md).

---

## Configuration

Cluster93 is configured via environment variables:

| Variable | Default | Description |
|---|---|---|
| `C93_NODE_ID` | `$(hostname)` | Unique identifier for this cluster node |
| `C93_ADMIN_TOKEN` | *(Auto-generated)* | Cluster administrative token (at least 32 characters) |
| `C93_RATE_LIMIT_PER_MINUTE` | `1200` | Allowed requests per tenant per minute across the cluster |
| `C93_HTTP_PORT` | `9393` | REST API and MCP HTTP port (`0` = random) |
| `C93_INGRESS_PORT` | `9394` | Ingress reverse proxy port for hosted services |
| `C93_INGRESS_DOMAIN` | `localhost` | Base domain for service hostnames (`<service>--<tenant>.<domain>`) |
| `C93_WORKER_MODE` | `process` | Execution mode: `process` (out-of-process worker pool with cgroups) or `embedded` (in-process isolate) |
| `C93_WORKER_ENGINE` | `javet` | Runtime engine: `javet` (V8) or `graaljs` |
| `C93_WORKER_DEFAULT_MODE` | `default` | Default worker binary profile mode |
| `C93_WORKER_BINARY_<MODE>` | | Custom binary path for worker profile `<MODE>` |
| `C93_CAPACITY` | `8` | Maximum concurrent job executions on this node |
| `C93_MAX_HEAP_MB` | `128` | Per-isolate heap memory limit |
| `C93_DEFAULT_TIMEOUT_MS` | `10000` | Default execution timeout for jobs |
| `C93_CLUSTERED` | `false` | Enable multi-node clustering |
| `C93_CLUSTER_MEMBERS` | `127.0.0.1` | Seed member addresses for cluster discovery |
| `C93_CLUSTER_PORT` | `5701` | Ignite cluster discovery port |
| `C93_CLUSTER_TLS` | `required` | Mutual TLS between cluster nodes (`required` or `off`) |
| `C93_DATA_DIR` | *(In-memory)* | Local directory for durable Ignite database and WAL storage |
| `C93_DATA_BACKUPS` | `1` | Copies of replicated partition data across nodes |
| `C93_FILES_BACKEND` | `cluster` | Storage backend for VFS: `cluster` (replicated Ignite cache), `disk`, or `s3` |
| `C93_METRICS_PORT` | `9395` | Prometheus `/metrics` endpoint |

---

## Build & Test

```bash
# Build and run fast unit & conformance test suite
./mvnw verify -DskipITs

# Run targeted engine tests
./mvnw test -pl runtime-javet -Dtest=ScriptRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false

# Full verification including multi-container Docker cluster chaos tests
./mvnw verify
```

---

## Roadmap

For the master implementation roadmap, active milestones, and upcoming work, see **[`ROADMAP.md`](ROADMAP.md)**:

- **Phases 1–4 (Complete):** Core cluster, dual engines (V8/GraalJS), VFS storage, and out-of-process isolation with Linux cgroups.
- **Phase 5 (Complete):** Architecture guide, aggregate Javadoc under JDK 21, and process-mode stability.
- **Phase 6 (In progress):** Web management console and browser identity sessions — the console app ships today ([apps & console design](docs/design/apps-and-console.md), [identity design](docs/design/identity.md)); the browser session layer remains.
- **Phase 7 (Upcoming):** Official Node runtime in rootless Podman + gVisor ([design](docs/design/node-runtime.md)).
- **Phases 8 & 9 (Future):** Differentiated node roles ([design](docs/design/node-roles.md)) and polyglot Python compute.
- **Phase 11 (Active):** Consolidation & release readiness — one install path, one update surface (`c93 update --cli|--node`), prefix-less module directories, and docs that match reality.

---

## Documentation

- [DOCUMENTATION.md](DOCUMENTATION.md): Documentation taxonomy, lifecycle, and authorship standard
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): Architectural design, module relationships, and subsystem navigation
- [docs/DECISIONS.md](docs/DECISIONS.md): Architectural decision records (ADRs D-001 through D-132)
- [docs/api/endpoints.md](docs/api/endpoints.md): Complete OpenAPI endpoint and scope reference
- [SECURITY.md](SECURITY.md): Threat model, trust boundaries, and security policies
- [VERSIONING.md](VERSIONING.md): SemVer scheme, pre-release channels, and the version line
- [docs/benchmarks.md](docs/benchmarks.md): Indicative single-machine numbers (not maintained) and how to reproduce them
- [docs/guides/cli.md](docs/guides/cli.md): CLI installation and usage guide
- [docs/guides/node.md](docs/guides/node.md): installing and running a node (`c93 node`)
- [docs/guides/mcp.md](docs/guides/mcp.md): Connecting AI agents via Model Context Protocol
- [docs/guides/storage.md](docs/guides/storage.md): Storage providers, quotas, and VFS mounts
- [docs/guides/packages.md](docs/guides/packages.md): Publishing and importing versioned packages
- [docs/scripting/host-apis.md](docs/scripting/host-apis.md): Guest JavaScript host APIs reference
- [docs/scripting/node-compat.md](docs/scripting/node-compat.md): Node.js compatibility matrix and behavior details

---

## Heritage

Cluster93 is the modern continuation of **Automately**, a clustered, distributed
backend platform first published in 2014 and acquired before 2018. The original
organization lived at [`github.com/Automately`][wayback] (`automate.ly`), where it
shipped **`automately-core`** — *"a powerful clustered backend platform powering
Automately Cloud"* — alongside `automately-sdk` (the official SDK CLI),
`sdk-module`, `sdk-bash-installer`, `automately-java`, and a wide family of modules
(`ssh-module`, `sessions`, `logify`, `sendgrid`, `slack-native`, `webdriver-module`,
`public_modules`, …).

This repository is a branch of that pre-acquisition codebase: the same idea — submit
code, get a sandboxed, distributed runtime across a cluster — rebuilt from the ground
up on Vert.x 5, embedded Apache Ignite 2.18, and dual V8/GraalJS isolates.

*In memoriam — Automately. Founded 2014; open-sourced and acquired before 2018.
The name is retired; the lineage carries on.*

[wayback]: https://web.archive.org/web/20160204012506/https://github.com/Automately

---

## Author & License

- **Author / Organization:** Adam & Lynn Incorporated · tony@adamnlynn.com
- **License:** [MPL-2.0](LICENSE.md)
