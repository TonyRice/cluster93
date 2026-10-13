# Cluster93

A self-hostable, clustered, distributed backend platform for resilient background jobs and supervised HTTP/WebSocket services—bringing the execution model of **Cloudflare Workers** and **AWS Lambda** to your own infrastructure with zero external database dependencies.

You submit code to any node. The cluster schedules it on the least-loaded worker inside an isolated sandbox, streams execution live, and handles state, messaging, and storage out of the box.

> **Status: Phase 10 Complete**
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

```bash
curl -fsSL https://github.com/adamnlynn/cluster93/releases/latest/download/install.sh | sh     # Linux, macOS
irm https://github.com/adamnlynn/cluster93/releases/latest/download/install.ps1 | iex           # Windows
```

Native binaries compile via GraalVM with zero JVM requirements on the client. See the [CLI guide](docs/guides/cli.md). To connect AI agents via MCP, see the [MCP guide](docs/guides/mcp.md).

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

### Local Development (Zero Docker, Single Command)

To run a persistent local single-node cluster directly on your machine without Docker:

```bash
# 1. Build the server, GraalVM native CLI, and worker isolate
scripts/dev.sh build

# 2. Start the local cluster in the background (persists to ~/.cluster93/data)
scripts/dev.sh start

# 3. Create the 'dev' tenant, mint a full-access token, configure ~/.config/cluster93/config.json, and print MCP config
scripts/dev.sh seed

# 4. Run any JavaScript ES-module job via the native CLI
c93 run examples/01-hello-job.js

# 5. Run the full curated test suite covering jobs, steps, KV, event bus, queues, and VFS
scripts/dev.sh test-examples

# Check cluster health and status
scripts/dev.sh status

# Stop the local server
scripts/dev.sh stop
```

Data is stored persistently at `$HOME/.cluster93/data` (customizable via `scripts/dev.sh start --data-dir <path>`). The native `c93` binary is installed directly into `~/.local/bin/c93` (and `bin/c93`).

---

### Dedicated Local Master Node (Onboarding Wizard)

For a permanent single-node cluster on your own machine — the API, ingress, worker and data roles in one durable process — use the guided installer. It asks for the essentials (or takes the defaults with `--yes`), builds whatever is missing, starts the node, seeds a tenant, saves the login and prints the MCP configuration:

```bash
scripts/local.sh setup          # interactive onboarding wizard
scripts/local.sh setup --yes    # accept every default, non-interactive
```

State is written where you'd expect:

| File | What it holds |
|---|---|
| `~/.cluster93/local.env` | Node ID, roles, data/log directories, ports, worker mode, admin token, tenant (mode `0600`) |
| `~/.cluster93/node.meta` | Build provenance: install time, git commit and branch, version, server jar, CLI binary |
| `~/.config/cluster93/config.json` | The saved login (`c93 whoami` works immediately) |
| `~/.cluster93/mcp.json` | Ready-to-paste MCP configuration for AI agents |

Every operation is also available from the CLI, so you never need to remember the script's paths:

```bash
c93 local status         # PID, /health, /v1/nodes, and an update check
c93 local start
c93 local stop
c93 local restart
c93 local logs --follow
c93 local mcp            # Claude Code, Cursor, Antigravity, and the stdio bridge
```

`status` compares the commit the running build came from against the checkout's `git HEAD` and flags any drift, so a stale node is never a mystery:

```text
⚠️  Update Available! Running build: 666cefa9…, Current repo HEAD: 8d4b1c02…. Run 'scripts/local.sh update' or 'c93 local update'.
```

`scripts/local.sh update` rebuilds the server jar and worker from the current checkout, rewrites the provenance in `node.meta`, and restarts the node if it was running.

---

### Docker Quickstart (3-Node Resilient Cluster)

#### 1. Launch a Multi-Node Cluster

```bash
export C93_ADMIN_TOKEN=$(openssl rand -hex 32)
docker compose up --build -d        # Starts three nodes on :8081, :8082, :8083
ADMIN="Authorization: Bearer $C93_ADMIN_TOKEN"

# Create a tenant and mint a scoped bearer token
curl -s -X POST localhost:8081/v1/tenants -H "$ADMIN" -d '{"name": "demo"}'
TOKEN=$(curl -s -X POST localhost:8081/v1/tenants/demo/tokens -H "$ADMIN" \
  -d '{"name": "dev", "scopes": ["*"]}' | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"
```

### 2. Submit a Resilient Job

Jobs are ES modules receiving input and a execution context (`ctx`). Steps wrapped in `ctx.step()` execute with idempotency keys and are never repeated if an attempt resumes on another node:

```bash
curl -s -X POST localhost:8081/v1/jobs -H "$AUTH" \
  -d '{
    "script": "export default async (input, ctx) => {\n  const res = await ctx.step(\"fetch-data\", () => ({ count: input.count * 2 }));\n  await ctx.saveCheckpoint({ processed: true });\n  return { ok: true, result: res };\n}",
    "input": {"count": 21}
  }'
```

Stream logs and execution events in real time from any node via Server-Sent Events (SSE) or the CLI:
```bash
curl -N localhost:8083/v1/jobs/<id>/events -H "$AUTH"
c93 jobs logs <id>                               # Live stream via CLI
```

Or run directly with live console streaming:
```bash
c93 run job.js --input '{"count": 21}'           # Streams stderr logs live, stdout returns result
```

### 3. Deploy a Supervised HTTP Microservice

Services run continuously under cluster supervision with health monitoring, automatic restart backoff, and host-based routing:

```bash
curl -s -X PUT localhost:8081/v1/services/web -H "$AUTH" \
  -d '{
    "script": "import http from \"node:http\";\nconst server = http.createServer((req, res) => res.end(JSON.stringify({ status: \"healthy\" })));\nserver.listen(8080);",
    "enabled": true
  }'
```

Once running, requests arriving at the ingress port (`:8000`) matching `<service>--<tenant>.<domain>` route directly to the serving node:
```bash
curl -H "Host: web--demo.localhost" http://localhost:8000/
```

Or deploy and stream logs live via the CLI:
```bash
c93 login http://localhost:8081 --token "$TOKEN"
c93 services deploy web server.js
c93 services logs web                           # Follow live console output
```

---

## REST API & Contract

Cluster93 uses a **contract-first architecture**: [`openapi.yaml`](cluster93-server/src/main/resources/openapi.yaml) is the single source of truth. Every request is validated against the specification before reaching application handlers.

All `/v1` endpoints require an HTTP Bearer token:
- **Admin Token:** Manages tenants, operators, and storage policies. Admin tokens cannot read or manipulate tenant data (D-044).
- **Tenant Tokens (`atly_*`):** Scoped to specific permissions (`jobs:write`, `services:read`, `files:write`, etc.) and rate-limited via token buckets (`RateLimit-Policy` / `RateLimit`).
- **Audit Logging:** Every administrative and configuration event is recorded in a tamper-evident audit log (`/v1/audit`).

For complete schema details and parameter documentation, refer to the [API Endpoint Reference](docs/api/endpoints.md) or inspect `/v1/openapi.json`.

---

## Architecture & Execution Model

```
       Public API :8080 · Ingress :8000 (Any Node)
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
| [`cluster93-core`](cluster93-core) | Job lifecycle, state transitions, scheduling, storage SPIs, services supervisor. Depends only on `vertx-core`. | `NodeVerticle`, `JobScheduler`, `ServiceSupervisor`, `NodeHostApis` |
| [`cluster93-runtime-js`](cluster93-runtime-js) | Engine-neutral JS SPI, polyfills (`node:*`, web standards), event loop orchestration, and conformance test harness. | `JsEngine`, `JsContext`, `ScriptRunner`, `HostFunction` |
| [`cluster93-runtime-javet`](cluster93-runtime-javet) | V8 engine adapter using Javet. Optimized for throughput and JSON manipulation. | `JavetEngine` |
| [`cluster93-runtime-graaljs`](cluster93-runtime-graaljs) | GraalJS engine adapter. Memory-safe polyglot sandbox isolating host memory. | `GraalJsEngine` |
| [`cluster93-runner-protocol`](cluster93-runner-protocol) | IPC binary framing codec, credit-based flow control, UDS transport, and warm tenant worker pool supervisor. | `FrameCodec`, `StreamMultiplexer`, `TenantWorkerPool`, `RemoteHostApis` |
| [`cluster93-worker`](cluster93-worker) | Standalone native runner daemon for out-of-process execution. | `WorkerMain` |
| [`cluster93-cluster-ignite`](cluster93-cluster-ignite) | Embedded Apache Ignite 2.18 persistence, WAL, replicated caches, and cluster manager. | `IgniteClusterManager`, `IgniteJobStore` |
| [`cluster93-server`](cluster93-server) | REST API endpoints, OpenAPI validation, ingress proxy, WebSocket tunnels, and MCP server. | `Cluster93`, `ApiVerticle`, `IngressVerticle` |
| [`cluster93-cli`](cluster93-cli) | Single-binary CLI client compiled with GraalVM Native Image. | `CliMain` |
| [`cluster93-tls`](cluster93-tls) | Mutual TLS utilities, node join tokens, and cluster certificates. | `TlsConfig` |

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
| `C93_HTTP_PORT` | `8080` | REST API and MCP HTTP port (`0` = random) |
| `C93_INGRESS_PORT` | `8000` | Ingress reverse proxy port for hosted services |
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
| `C93_METRICS_PORT` | `9090` | Prometheus `/metrics` endpoint |

---

## Build & Test

```bash
# Build and run fast unit & conformance test suite
./mvnw verify -DskipITs

# Run targeted engine tests
./mvnw test -pl cluster93-runtime-javet -Dtest=ScriptRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false

# Full verification including multi-container Docker cluster chaos tests
./mvnw verify
```

---

## Roadmap

For the master implementation roadmap, active milestones, and upcoming work, see **[`ROADMAP.md`](ROADMAP.md)**:

- **Phases 1–4 (Complete):** Core cluster, dual engines (V8/GraalJS), VFS storage, and out-of-process isolation with Linux cgroups.
- **Phase 5 (Complete):** Architecture guide, aggregate Javadoc verification under JDK 21, and process-mode stability.
- **Phase 6 (Active / Next):** Web Management Console with WebAuthn Passkeys & browser identity sessions ([`ideas/console.md`](ideas/console.md)).
- **Phase 7 (Upcoming):** Official Node runtime in rootless Podman + gVisor ([`docs/design/node-runtime.md`](docs/design/node-runtime.md)).
- **Phases 8 & 9 (Future):** Differentiated node roles ([`ideas/node-roles.md`](ideas/node-roles.md)) & polyglot Python compute ([`ideas/python.md`](ideas/python.md)).

---

## Documentation

- [DOCUMENTATION.md](DOCUMENTATION.md): Documentation taxonomy, lifecycle, and authorship standard
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): Architectural design, module relationships, and subsystem navigation
- [docs/DECISIONS.md](docs/DECISIONS.md): Architectural decision records (ADRs D-001 through D-132)
- [docs/api/endpoints.md](docs/api/endpoints.md): Complete OpenAPI endpoint and scope reference
- [SECURITY.md](SECURITY.md): Threat model, trust boundaries, and security policies
- [docs/benchmarks.md](docs/benchmarks.md): Performance benchmarks and reproduction instructions
- [docs/guides/cli.md](docs/guides/cli.md): CLI installation and usage guide
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
