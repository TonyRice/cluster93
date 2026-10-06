# Automately

A self-hostable, clustered runtime for sandboxed JavaScript functions, built on **Vert.x 5** and **V8 isolates** (via [Javet](https://github.com/caoccao/Javet)).

You submit a script to any node. The cluster runs it on the least-loaded node in a fresh V8 isolate, streams its console output live, and stores the result where every node can read it.

> **Status: phase 2 done** (orphaned jobs, attempts, resumable steps). This is a ground-up rewrite of Automately Core (2015–2017), which ran on a Vert.x 2 fork called jsync.io, Nashorn and raw Hazelcast. See [What changed since 2017](#what-changed-since-2017).

## Try it

```bash
docker compose up --build -d        # three nodes on :8081, :8082, :8083
curl -s localhost:8081/v1/nodes

curl -s -X POST localhost:8081/v1/jobs \
  -H 'X-Automately-Tenant: demo' \
  -d '{"script": "export default async (input) => { console.log(\"hi\", input.name); return { greeting: `hello ${input.name}` } }",
       "input": {"name": "tony"}}'

# Follow a job from any node (server-sent events)
curl -N localhost:8083/v1/jobs/<id>/events -H 'X-Automately-Tenant: demo'
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

If the node running a job dies, the job's `onOrphan` policy decides what happens: `fail` (the default) ends it as `ORPHANED`; `retry` starts again from the beginning; `resume` continues on another node and skips steps that already completed. See the **[job context reference](docs/scripting/job-context.md)**.

## API

| Method | Path | |
|---|---|---|
| `POST` | `/v1/jobs` | `{script, input?, timeoutMs?, onOrphan?, maxAttempts?}` → `202` with the job |
| `GET` | `/v1/jobs` | Jobs for the tenant, newest first |
| `GET` | `/v1/jobs/{id}` | One job, including `attempt` and the `attempts` history |
| `GET` | `/v1/jobs/{id}/steps` | The current attempt's checkpoint and completed steps |
| `GET` | `/v1/jobs/{id}/events` | SSE: `log`, `attempt` (a new attempt started), then `end`. A finished job is replayed |
| `GET` | `/v1/nodes` | Live nodes with capacity and load |
| `GET` | `/health` | Liveness |

Errors use one envelope: `{"error": {"code": "...", "message": "..."}}`.

> ⚠️ The tenant currently comes from the `X-Automately-Tenant` header. That is a placeholder until scoped API tokens are added in phase 4, so don't expose a node publicly yet.

## Architecture

```
            HTTP (any node)
                 │
          ┌──────▼──────┐   least-loaded    ┌──────────────┐
          │ ApiVerticle │──► JobScheduler ──►│ NodeVerticle │──► JavetScriptRuntime
          └──────┬──────┘    (event bus)  ▲  └──────┬───────┘     one V8 isolate per attempt
                 │                        │         │
                 │            OrphanReaper┘         │   (re-dispatches attempts whose node died)
                 │                                  │
                 └── JobStore · JobStateStore · NodeRegistry ──┘
                     Vert.x SharedData (AsyncMap), compare-and-set transitions
                     └── Hazelcast, only in automately.server.cluster
```

| Module | Contents |
|---|---|
| `automately-core` | Job model and transitions, `JobStore`, `JobStateStore`, `NodeRegistry`, the `ScriptRuntime` SPI, scheduler, node verticle and orphan reaper. Depends only on `vertx-core` |
| `automately-runtime-javet` | `ScriptRuntime` on V8 isolates |
| `automately-server` | Bootstrap, REST API, Hazelcast wiring, the shaded jar |

### Sandbox

Each job gets a **new isolate**, which is closed when the job finishes. Nothing carries over between jobs, including globals and prototype pollution.

- **No host access.** The script can reach only a frozen `console` and its frozen `ctx`. There is no `Java`, `require` or `process`, and no reflection.
- **Wall-clock timeout.** Enforced from the event loop with `terminateExecution()`. It also covers loops at module top level and loops after an `await`.
- **Heap limit** (`AUTOMATELY_MAX_HEAP_MB`). When an isolate runs out of memory, V8 normally aborts the whole process. Here a near-heap-limit callback terminates the script instead and grants a little headroom so V8 can unwind, and the node keeps serving.

The hostile-script suite (`JavetScriptRuntimeTest$HostileScripts`) covers each of these.

## Configuration

| Variable | Default | |
|---|---|---|
| `AUTOMATELY_NODE_ID` | hostname | |
| `AUTOMATELY_HTTP_PORT` | `8080` | `0` = random |
| `AUTOMATELY_CAPACITY` | `8` | Concurrent jobs per node |
| `AUTOMATELY_MAX_HEAP_MB` | `128` | Per-isolate heap. Applies process-wide |
| `AUTOMATELY_DEFAULT_TIMEOUT_MS` / `AUTOMATELY_MAX_TIMEOUT_MS` | `10000` / `60000` | |
| `AUTOMATELY_HEARTBEAT_MS` | `1000` | A node is considered gone after 3 missed heartbeats |
| `AUTOMATELY_ORPHAN_GRACE_MS` | 3 × heartbeat | Extra wait before a job's node is treated as dead |
| `AUTOMATELY_REQUEUE_AFTER_MS` | `10000` | An attempt no node picked up is dispatched again after this |
| `AUTOMATELY_CLUSTERED` | `false` | |
| `AUTOMATELY_CLUSTER_NAME` | `automately` | |
| `AUTOMATELY_CLUSTER_PORT` | `5701` | |
| `AUTOMATELY_CLUSTER_MEMBERS` | `127.0.0.1` | Hazelcast TCP/IP join list |

## Build

```bash
./mvnw verify      # JDK 21. Unit tests (2- and 3-node clusters in one JVM, chaos), then
                   # DockerClusterIT: 3 containers, SIGKILL mid-job (needs Docker; -DskipITs to skip)
```

Javet's native library is included for linux-x86_64 only for now.

## What changed since 2017

| 2017 | Now | Enforced by |
|---|---|---|
| Scripts ran in the host JVM behind a class loader | One V8 isolate per job with a timeout and heap limit | Hostile-script tests |
| Static state in `JobServer`, `UserData` and others | Services are verticles with injected interfaces | ArchUnit: static fields must be final |
| Nested callbacks and blocking map calls on the event loop | Vert.x 5 futures, with blocking work in `executeBlocking` | ArchUnit: no hand-rolled threads or `Executors` |
| Hazelcast types in 39 files | Hazelcast lives in one package behind `SharedData` | ArchUnit: no `com.hazelcast` outside `automately.server.cluster` |
| Regex routing by hand | `vertx-web` `Router` | |
| No tests | 76 unit tests (hostile scripts, orphan scenarios, 2- and 3-node clusters, chaos, architecture) plus a Docker integration test that uses real `SIGKILL`s | CI |
| A crashed node's jobs were simply lost or restarted blindly | Attempts with fencing, `onOrphan` policy, resumable steps | Chaos test (kills the node mid-step) |

## Roadmap

1. ~~**Spike**~~ ✅ Cluster, V8 runtime, API, SSE, CI
2. ~~**Orphaned jobs**~~ ✅ `onOrphan` policy (`fail` by default), attempts visible to scripts, `ctx.step` and checkpoints, fencing, and a chaos test ([design](docs/design/orphaned-jobs.md), [script API](docs/scripting/job-context.md), [plan](docs/design/phase-2-plan.md))
2b. **Long-running services.** Jobs that are meant to keep running (the old Automately "services")
3. **Host API.** `http`, `kv`, `bus` and `log` bindings with tenant checks, isolate pooling, and hostname routing
4. **API, auth, CLI and MCP.** OpenAPI contract, scoped tokens, rate limits, an MCP server for AI agents
5. **Observability and polish.** Metrics, tracing, Grafana, benchmarks, `SECURITY.md`

Parked ideas, such as remote workers offering a sandboxed browser, are in [NOTES.md](NOTES.md).

## Docs

- [CHANGELOG.md](CHANGELOG.md): what changed and why
- [docs/DECISIONS.md](docs/DECISIONS.md): the decision log
- [docs/design/revival-plan.md](docs/design/revival-plan.md): the original plan (why and what), with current status
- [docs/design/](docs/design/): designs for upcoming work
- [docs/scripting/job-context.md](docs/scripting/job-context.md): the job context API for scripts (attempts, steps, checkpoints)

## Author

**Adam & Lynn Incorporated** · tony@adamnlynn.com

## License

[MPL-2.0](LICENSE.md), the same licence as the original Automately Core.
