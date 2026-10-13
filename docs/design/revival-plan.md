<!--
  The original revival plan, written and approved on 2026-10-06 before any code existed.
  It's kept as written so the reasoning stays traceable. Progress and later changes are tracked in the
  status block below, CHANGELOG.md and docs/DECISIONS.md, not by editing the plan itself.
-->

> **Status (updated 2026-10-06)**
>
> | Phase | State | Where |
> |---|---|---|
> | 1. Spike | ✅ Done | [CHANGELOG](../../CHANGELOG.md) · D-001 to D-006 |
> | 2. Scheduler port | ✅ Done, narrowed to orphaned jobs, attempts, steps and checkpoints | [phase-2-plan.md](phase-2-plan.md) · [orphaned-jobs.md](orphaned-jobs.md) · D-007, D-009 to D-014 |
> | 2b. Long-running services | ✅ Done | [services.md](services.md) · D-019 to D-023 |
> | 3. Sandboxed runtime and host API | ✅ Done | [host-apis.md](host-apis.md) · D-024 to D-029; pooling measured and declined |
> | 4. API, auth, CLI and MCP | Planned | |
> | 5. Observability and polish | Planned | |
>
> **Changes since this plan was written:**
> - Decided: `fail` is the default for orphaned jobs (D-007).
> - Decided: author is Adam & Lynn Incorporated (D-008).
> - Decided: the KV backend starts on the cluster map and the build uses the Maven wrapper.
> - Moved: the heap limit came forward from phase 3 into the spike.
> - Testcontainers: added in phase 2 as `DockerClusterIT` (3 containers, real `SIGKILL`).
> - Added: a remote-workers and browser idea parked in [NOTES.md](../../NOTES.md).

# Cluster93 Core: what it was, what it could become, and a Vert.x 5 revival plan

## Context
Tony is dropping jsync.io. Its source is already in `jsync.io-master.zip`, and it is a renamed fork of Vert.x 2, so there's no reason to revive it. Instead he wants to port **Cluster93 Core** straight to Vert.x 5 and reposition it as a modern project that strengthens a job search. This document covers three things: what Cluster93 was architecturally good at, what it could be good for today, and a scoped plan to get there.

## What Cluster93 Core was (2015–2017)
It was a self-hosted, clustered platform that ran user code as functions and services, with built-in data, messaging, files and HTTP. You could describe it as AWS Lambda, Firebase and Heroku in one JVM cluster, built before that combination was common. Each feature below lives in `cluster93-core-master/src/main/java/cluster93/core/`:

| Capability | Where | Notes |
|---|---|---|
| **Distributed job scheduler**: submit a script, and the cluster places it on the least-loaded node, tracks status, streams console and events, and reruns long-lived "services" | `services/job/JobServer.java` (1.5k lines), `JobRunner.java` | State lives in Hazelcast `IMap`/`ISet`, with `max_jobs` per node and `execute_on_least_jobs` placement |
| **Pluggable polyglot runtimes** through `ExecutionContextFactory` | `services/job/execution/factories/{js,v8,node,java,php,dart,container}` | The abstraction is clean. Nashorn was the default, and V8 (J2V8) was experimental |
| **A rich host API for scripts**: http server/client, TCP, SMTP, a queryable DataBus store, MessageBus pub/sub, filesystem, Docker containers | `factories/js/objects/**` | This is the "platform SDK" a script sees |
| **Hostname-routed HTTP hosting**: a script registers a host, and any node routes requests for it | `services/http/ClusteredHttpServer.java` | Works like Cloudflare Workers routes |
| **Virtual per-user file system** with S3, Azure and local stores, exposed as a Java NIO `FileSystem` and over SFTP/SSH | `file/**`, `services/ssh/**` | |
| **Realtime bridge**: SockJS links the cluster event bus to browsers and the SDK | `services/sdk/**` | |
| **REST API** (users, jobs, files, live console/event streams) and **SDK CLI deploy** | `services/api/routers/**`, `cluster93-sdk-master` | |
| **Role-based nodes** (`all`, `api`, `sdk`, `job`), so one binary can scale out per role | config in each service README | |

**Weak spots, which also make good material for a career write-up:**
- Isolation was a JVM classloader (`NativeExecutionClassLoader`), which isn't a real tenant sandbox.
- Static global state (`JobServer` statics).
- Callback-heavy code, routing done with regexes, Hazelcast types leaking across the codebase, and almost no tests.

## What it could be good for now
The architecture fits a problem that's very active in 2026: **running untrusted or AI-generated code safely, close to data and events.** The isolate model matches what Cloudflare Workers, Deno Deploy and the agent-sandbox products (E2B, Modal and others) do, and Cluster93 already had the surrounding platform: scheduler, storage, pub/sub, HTTP routing and live log streaming.

**Recommended positioning:** *"Cluster93: a self-hostable, clustered runtime for sandboxed JavaScript functions and long-running services on the JVM. It uses V8 isolates (Javet) on Vert.x 5 and exposes jobs, storage, messaging and HTTP routing through one API, plus an MCP server so AI agents can deploy and run code on it."*

This is useful for a career because it demonstrates:
- distributed systems: scheduling, cluster membership, failover
- reactive JVM work with Vert.x 5 and Futures
- multi-tenant sandboxing with V8 isolates and resource limits
- API design and developer experience
- an AI-agent integration (MCP) that's current

It's also an honest story: a platform you built and ran in 2017, re-architected with current tools. Interviewers respond well to that kind of story.

**Avoid** reviving everything (PHP, Dart and Docker runtimes, SFTP, Azure, the console UI). Shipping a narrow, polished, well-documented core with a demo is worth more than a broad port that's half done.

## Target architecture (Vert.x 5)
- **Runtime:** JDK 21 (virtual threads for blocking work), Vert.x 5 (Futures only), `vertx-web` `Router` in place of `RouteMatcher`/regex routing, `vertx-web` `SockJSHandler` or plain WebSockets in place of `SdkSockJSServer`.
- **Cluster:** `vertx-hazelcast` cluster manager. Replace jsync's `Cluster` god-object with small injected interfaces (`JobStore`, `NodeRegistry`, `Locks`) backed by Vert.x `SharedData` (`AsyncMap`, locks, counters), so Hazelcast types stay out of business code. This also avoids depending on the Hazelcast CP subsystem (`ILock`/`ICountDownLatch`), which newer Hazelcast releases moved to the Enterprise edition.
- **Execution:** keep the `ExecutionContextFactory` SPI. The first-class implementation is **Javet V8** using one isolate per job, with memory and CPU limits plus a timeout or kill. Rebuild the host API in a small, explicit form (`http`, `kv`, `bus`, `fs`, `log`) as Javet interop objects. A Javet Node mode can come later.
- **Storage:** keep the `VirtualFileStore` abstraction, starting with local and S3 (AWS SDK v2). Turn the DataBus into a per-tenant KV/query store on a pluggable backend, starting with Postgres or the cluster map.
- **Edges:** a REST API under `/v1` with OpenAPI, SSE or WebSocket for console and event streams, and an **MCP server** exposing `deploy`, `run`, `logs` and `kv`.
- **Packaging:** a Maven multi-module build (`core`, `runtime-javet`, `api`, `cli`), a Docker image, docker-compose for a 3-node cluster demo, and GitHub Actions CI with Testcontainers integration tests.

## Fixing the weak spots
Each item gives the evidence in the old code, the fix in the new design, and how the fix is shown to work. The "how it's proven" column is also what makes each one a credible part of a career write-up.

| # | Weak spot (evidence) | Fix in the Vert.x 5 design | How it's proven |
|---|---|---|---|
| 1 | **No tenant isolation.** Scripts ran in the host JVM with a caching `NativeExecutionClassLoader` (`factories/js/NativeExecutionClassLoader.java`), so one script could exhaust the heap or call into the host | One **Javet V8 isolate per job**, with no Java reflection exposed. The script can only reach a small allow-listed host API (`http`, `kv`, `bus`, `log`, `fs`), and every call is checked against the job's tenant. Each job gets a heap cap, a CPU/wall-clock timeout enforced by `V8Runtime.terminateExecution()`, a pending-promise limit, and a per-tenant egress allow-list on `http`. Isolates are pooled per tenant and never shared across tenants. Optionally, runner nodes run in a separate process or container (`role=runner`) so a native crash can't take down the scheduler | A "hostile scripts" test suite (infinite loop, memory bomb, `while(1) fetch`, prototype tricks, reaching another tenant's KV) asserts that the job is killed or denied and the node stays healthy. A `SECURITY.md` documents the threat model |
| 2 | **Static global state.** `JobServer`, `UserData`, `VirtualFileSystem` and `SdkDeploymentService` hold static maps and a static `Cluster`, which made them untestable and singleton-bound | No statics. Each service is a **Verticle** with constructor-injected interfaces (`JobStore`, `NodeRegistry`, `TenantStore`, `FileStore`, `Runtime`). Wiring happens in one `Main`/`Bootstrap`, by hand or with a light DI library. Config is a typed record loaded through `vertx-config` | Several nodes can run inside a single test JVM. Unit tests use in-memory fakes of each interface |
| 3 | **Callback pyramids and blocking on the event loop.** There are nested `Handler<AsyncResult>` callbacks, synchronous Hazelcast `IMap.get` calls inside HTTP handlers (for example `JobsHandler`: `jobs().get(token)`), and 14 raw `new Thread`/`Executors` sites | **Futures only** (Vert.x 5 forces this): use `compose`/`recover`, and use `Future.await` on virtual-thread verticles wherever sequential code reads better. All cluster and storage access goes through the async interfaces. Blocking work (S3, Postgres JDBC) runs on virtual threads or `executeBlocking`. No raw threads anywhere | Vert.x blocked-thread checker set to strict and failing tests in CI. An ArchUnit rule bans `new Thread`, `Executors` and Hazelcast imports outside `cluster-hazelcast` |
| 4 | **Regex routing done by hand.** `finalPath.matches(...)` and `split("/")[2]` in `services/api/routers/*` | Use `vertx-web` `Router` with path params, generated from an **OpenAPI 3** contract (`vertx-openapi` validates requests). Add consistent JSON error envelopes, pagination, and `/v1` versioning | Contract tests run against the OpenAPI spec. The spec is published in the README and doubles as the CLI/MCP schema |
| 5 | **Hazelcast types everywhere.** 39 files import `IMap`, `Predicate`, `ILock`, `ICountDownLatch` and `DataSerializable` | Hazelcast lives only in one `cluster-hazelcast` module behind `JobStore`/`NodeRegistry`/`Locks`, implemented on Vert.x `SharedData` (`AsyncMap`, `Lock`, `Counter`). Queries that needed Hazelcast predicates (job lists filtered by user or status) move to a proper index or to Postgres. Objects travel as JSON or records instead of `DataSerializable` | ArchUnit rule (from #3). Swapping in the in-memory cluster implementation for tests takes no code changes |
| 6 | **No tests** (0 test files) and silently swallowed exceptions (`catch (Exception e) {}`) | Build a test pyramid from day one: JUnit 5 + `vertx-junit5` unit tests, Testcontainers integration tests (multi-node cluster, Postgres, MinIO for S3), the hostile-scripts suite from #1, and a chaos test that kills a node and checks that its services reschedule. Error-prone or SpotBugs fail the build on empty catches | GitHub Actions badge, a coverage report, and the chaos test recorded for the demo |
| 7 | **Weak auth model.** HTTP Basic with a long-lived username and API key on every request (`ApiHandler.java:96`), PBKDF2-HMAC-SHA1, `SHA1PRNG`, and users held in a cluster map | Scoped, revocable **API tokens** (hashed with Argon2id or SHA-256 + pepper, prefixed for secret scanning), short-lived JWTs for the browser/WebSocket bridge, per-token scopes (`jobs:run`, `kv:read`, and so on), and per-tenant rate limits. The MCP server uses the same scoped tokens | Auth tests for scope denial and revocation. Rate-limit test |
| 8 | **Opaque operations.** Logging through a custom `Logger`, no metrics | Structured JSON logs carrying job and tenant IDs, **Micrometer/Prometheus** metrics (queue depth, isolate count, cold-start ms, job latency, kills by reason), OpenTelemetry tracing across event-bus hops, and health/readiness endpoints | A Grafana dashboard ships in docker-compose, and benchmark numbers in the README come from it |

These fixes are built into the phases below rather than added at the end: #2, #3, #5 and #6 shape the spike, #1 and #7 belong to the runtime phase, and #4 and #8 belong to the API and polish phases.

## Phased plan
1. **Spike (2–3 days).** In a new repo, build a Vert.x 5 + vertx-hazelcast two-node cluster, a `JobStore` interface on `AsyncMap` plus an in-memory fake, and a Javet runner executing `export default (req) => ...` with a timeout. Add an HTTP endpoint that submits a job and streams logs. Set up CI, ArchUnit rules and the first Testcontainers test from day one. *(Weak spots #2, #3, #5, #6.)* Go or no-go is based on how this feels.
2. **Scheduler port (about 1 week).** Port `JobServer`/`JobRunner` logic (least-loaded placement, status lifecycle, long-running services, restarting jobs when a node dies) onto the new interfaces, with no statics. Add the chaos test for node death.
3. **Sandboxed runtime and host API (about 1–2 weeks).** Add Javet isolate pooling, limits and termination, the allow-listed `http`/`kv`/`bus`/`log` bindings with tenant checks, and the hostile-scripts suite. Port `ClusteredHttpServer`'s hostname routing to a `vertx-web` router. *(#1.)*
4. **API, auth, CLI and MCP (about 1 week).** Write the OpenAPI-first REST API, scoped tokens and rate limits, a thin CLI (using the old SDK's deploy flow as the reference), and the MCP server. *(#4, #7.)*
5. **Observability and portfolio polish (about 1 week).** Add metrics, tracing and the Grafana dashboard. Write a README with an architecture diagram, a "2017 → 2026: what I'd change and why" write-up built around the weak-spots table, `SECURITY.md`, benchmarks (cold start, isolate density, throughput), and a recorded demo of an agent deploying and calling a function on a 3-node cluster, including a node kill. *(#8.)*

Port code where it's logic, such as scheduling, routing and the VFS. Rewrite code where it's API surface. Don't port the legacy runtimes, Nashorn, SSH/SFTP, Azure, or the `org.apache.sshd` fork.

## Decisions to confirm before phase 1
- Repo, name and licence: keep "Cluster93" under your own GitHub account, with the original LICENSE.md carried over.
- KV backend: cluster map vs Postgres.
- Whether to put MCP/agent integration front and centre, which is what the positioning above assumes.

## Verification
- Phase 1: `docker compose up` brings up 2–3 nodes. `POST /v1/jobs` runs a Javet script on the least-loaded node. Logs stream over SSE. Killing that node causes a long-running service to restart elsewhere.
- Each later phase has Testcontainers integration tests in CI, plus the demo script in the README, which should work from a clean checkout.
