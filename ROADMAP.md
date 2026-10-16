# Cluster93 — Master Implementation Roadmap & Active Memory

**File:** `ROADMAP.md`  
**Author / Organization:** Adam & Lynn Incorporated <tony@adamnlynn.com>  
**License:** MPL-2.0  
**Repository:** `git@github.com:TonyRice/cluster93.git` (`main`)  
**Last Updated:** 2026-10-13  

---

## 1. Operating Instructions & Memory Protocol

To prevent context drift, loss of momentum, and misalignment across sessions or agents:

1. **Check `ROADMAP.md` First:**
   - Every session or new work order must inspect this document to understand the active milestone, the next immediate steps, and what is currently in flight.
2. **Update After Milestones:**
   - When a milestone, phase, or critical bug is resolved, immediately update the **Active Work** and **Recent Achievements** sections here.
3. **Record Architectural Decisions in `docs/DECISIONS.md`:**
   - Any trade-off, invariant, or architectural pattern chosen during implementation must be assigned a `D-xxx` decision record. Never rewrite or delete past decisions; supersede them.
4. **Preserve Operational Invariants:**
   - Author / Committer attribution: `Adam & Lynn Incorporated <tony@adamnlynn.com>`.
   - Architectural isolation: `cluster93-core` depends *only* on `vertx-core`; Apache Ignite is strictly confined to `cluster93-cluster-ignite`.
   - Quality gates: Always check for crash logs (`find . -name 'hs_err_pid*.log'`). Run `./mvnw verify -pl <module> -am -DskipITs` (or `./scripts/test-all.sh`). Never run `DockerClusterIT` unless Tony explicitly requests it.
   - Protected docs: Do not overwrite user edits to `README.md`, `CHANGELOG.md`, `NOTES.md`, `SECURITY.md`, `docs/DECISIONS.md`.

---

## 2. High-Level Project Roadmap

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1-3: Core Cluster, Storage, Runtime & Observability (COMPLETED) │
│ • Vert.x 5 + Apache Ignite 2.18 WAL, Caches, Supervisors, Metrics     │
│ • Dual Engines: Javet (V8) & GraalJS sandboxes, node:* stand-ins       │
│ • REST API, OpenAPI, MCP server, Ingress, TLS, Egress Gate             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 4: In-Cluster Worker Protocol & Out-of-Process Isolation (DONE)  │
│ • cluster93-runner-protocol: 12-byte frames, credit flow control, UDS │
│ • cluster93-worker daemon, binary discovery, JVM fallback (D-131)     │
│ • Full process-mode parity: streaming APIs, events, cgroups (D-132)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 5: Documentation, Javadoc Aggregation & Hardening (COMPLETED)    │
│ • Architecture Guide & DOCUMENTATION.md                                │
│ • Clean JDK 21 aggregate Javadoc (./mvnw javadoc:aggregate)            │
│ • 100% test-pass stability across all modules in process mode          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 6: Web Management Console & Identity Sessions (IN PROGRESS)      │
│ • Target Spec: ideas/console.md (D-070 to D-074 identity foundation)   │
│ • WebAuthn Passkeys, OIDC PKCE flow, and __Host- session cookies       │
│ • Vite + React + TanStack Router SPA bundled in cluster93-server      │
│ • Real-time job logs (SSE), VFS file browser, service supervisor UI    │
│ • Strict typing via openapi-typescript against OpenAPI contract        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 7: Node Runtime in Containers / gVisor (UPCOMING - Item 11)      │
│ • Target Spec: docs/design/node-runtime.md                             │
│ • Official Node runner in rootless Podman + gVisor (runsc)             │
│ • Native npm addons (sharp, bcrypt, better-sqlite3), full npm ci       │
│ • Zero-network sandbox with UDS protocol socket tunneling              │
└────────────────────────────────────────────────────────────────────────┘
```

Phases 1–6 are the platform; **Phase 11 (Consolidation & Release Readiness) is delivered** — see §3.

---

## 3. Active Work & Current Focus

### Phase 11: Consolidation & Release Readiness (COMPLETE)
Umbrella: one install path, one update surface, one checkout orchestrator, one benchmark entrypoint, prefix-less module directories, and docs that match reality. Tracked as work orders in `.agents/workorders/phase-11-consolidation.md` (W1–W6), all delivered.
- **W1 — Checkout tooling consolidation:** `scripts/dev.sh` folded into `scripts/local.sh` as the `dev` profile (`master` is the default); `scripts/compose-demo.yml` folded into the root `docker-compose.yml`; the `tools/` rename one-offs deleted.
- **W2 — `examples/js/` by language:** a directory-driven `dev test-examples` that also deploys the service example; `07-microservice.js` fixed (it had a broken response shape, never caught because the runner skipped it).
- **W3 — One update surface (D-138):** `c93 update [--cli|--node]`; `c93 upgrade` and `c93 node update` are hidden aliases; the release bundle ships the CLI beside the server and `docs/guides/releasing.md` carries the platform matrix.
- **W4 — One benchmark entrypoint:** `benchmarks/run.sh [cluster|engines|all]`; committed runs untracked and git-ignored.
- **W5 — Module directories drop the `cluster93-` prefix (D-134):** `tls`, `core`, `runtime-js`, `runtime-javet`, `runtime-graaljs`, `runner-protocol`, `worker`, `cluster-ignite`, `server`, `cli`; artifactIds and Java packages unchanged.
- **W6 — Docs reconciled:** README/ROADMAP status, the four phase plans archived under `docs/design/history/`, and `DOCUMENTATION.md`'s taxonomy refreshed.
- **Next focus:** Phase 6's browser session layer (§4). **Follow-up captured in `NOTES.md`:** one unified `c93 cluster …` pathway (master/dev/Docker node setup and cluster setup).
- **In flight (2026-10-15):** the per-tenant worker pivot (D-155–D-163) has **landed** — one worker per tenant running several jobs at once; the switch is on (`C93_MAX_JOBS_PER_WORKER` 4, `REUSE_SAME_TENANT`); the shared-channel cancel bug (D-162, fixed), per-worker memory sizing (D-163), the node-wide worker cap and queue-when-full behaviour (D-156) are all in, along with **the queue-drain fix that finally healed the `resumesMemoizedSteps` hang (D-170 — an incomplete earlier drain had left every claim after the first stranded whenever a worker's siblings were long-running)**. The worker-classpath isolation guard (D-030), tenant-visible crash attribution (D-159), per-tenant observability (D-164), the node-wide memory budget (D-165) and **cgroup detection with per-tenant CPU limits (D-158)** have since landed. **Docker Stage 2 has landed too** (D-166, D-167, D-171): the node listens on `C93_WORKER_SOCKET`, worker containers a **deployment** creates connect and become open workers the node binds to tenants, the pre-shared secret (D-168) and the real authorizer (D-150) are in, memory is reserved on connect (D-169), and `scripts/worker-isolation-check.sh` verifies a real container against a real node. Remaining from that work order: joining a cluster across hosts with worker containers, and the deferred scale-up/down path (plus the per-tenant-container tier D-166 keeps open). Verified end to end on real engines (rootless podman, rootful Docker) with both worker engines, a job round trip per case, and the wrong-identity and wrong-secret refusals. **A worker is no longer bound to one tenant for life (D-172):** one tenant at a time, re-bound whenever it goes idle, so an idle worker container goes back to work for whichever tenant is waiting instead of one container sitting warm while the node starts a process (or the job waits) — proved by `WorkerRebindTest` (two tenants, one connection, `c93_workers_running` staying `0.0`, and nothing of the first tenant left in the second's context). Release-built images with signed tags are **not** on that list: they need a real signed release, which is deferred in its own right. The worker-classpath isolation guard (D-030), tenant-visible crash attribution (D-159), per-tenant observability (D-164), the node-wide memory budget (D-165) and **cgroup detection with per-tenant CPU limits (D-158)** have since landed. Remaining: Docker Stage 2 — **worker containers holding a tenant-agnostic pool of open workers that connect to the node** (D-166 supersedes D-160's container-per-tenant; D-167 retires the node-spawned engine-socket shape of D-144/D-145) — **rewritten and ready to build**.

### Phase 5: Architecture Documentation & Javadoc Aggregation (COMPLETE)
- **Task 5.1 — Master Documentation (`DOCUMENTATION.md`):**
  - Documented core architectural subsystems: `HostApis`, `NodeHostApis`, `ScriptRunner`, engine adapters, storage SPIs, network gates, and `cluster93-runner-protocol`.
  - Maintained canonical cross-references and links to `docs/ARCHITECTURE.md` and `docs/DECISIONS.md`.
- **Task 5.2 — Clean Aggregate Javadoc (`./mvnw javadoc:aggregate`):**
  - Configured aggregate Javadoc generation under JDK 21 (`maven-javadoc-plugin` 3.11.2 with `<doclint>all,-missing</doclint>` and `<release>21</release>`).
  - Verified clean aggregate build generating docs for all public modules with zero doclint errors.
- **Task 5.3 — 100% Process Mode Verification:**
  - Automated test runner `./scripts/test-all.sh` outputs to `lasttest.txt` by default and summarizes failures.
  - Resolved `ConcurrentModificationException` in `NodeVerticle.checkRunningJobs()`.
  - Resolved process-mode `STATE` durability race condition in `NodeWorkerChannel` (tracked pending state commits before completing execution result; passed `ChaosTest` and hardened `ProcessModeParityTest`).
  - Clean run across all test suites under `C93_WORKER_MODE=process`.

---

## 4. Upcoming Work

### Phase 6: Web Management Console & Browser Identity Sessions (IN PROGRESS)
- **Reference Spec:** `ideas/console.md` (builds upon D-070 to D-074 identity foundation).
- **Delivered so far:**
  - The console SPA is scaffolded in `console/` as a **Next.js 15 App Router** app with static export and deployed as a Cluster93 app (see the achievement above). It currently authenticates with a bearer token and covers operator topology plus tenant jobs/services/apps/files. The browser session layer, real-time job logs, and the audit explorer remain.
  - The `console` node role (`C93_ROLES=...,console`) gates who may serve the console app, and `scripts/local.sh`/`scripts/dev.sh` now provision the reserved `system` tenant and auto-deploy `console/` to `system/console`, served on `localhost` when no `C93_CONSOLE_DOMAIN` is set.
- **Core Objectives:**
  1. **Browser Session Layer:**
     - WebAuthn Passkeys (`java-webauthn-server` or `webauthn4j`) and OIDC PKCE flow.
     - `__Host-` session cookies (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`).
     - Session token minting for tenant interactions (`POST /v1/me/tokens` into memory).
  2. **Single-Page Application (SPA):**
     - Framework: Next.js App Router, static export (`output: 'export'`), Tailwind CSS — shipped as the `console` app via the apps primitive.
     - Strictly typed API client against `openapi.yaml` (currently hand-mirrored in `console/src/lib/types.ts`; generated types are a future hardening step).
     - Zero cold starts: pure static assets served from the tenant VFS by the ingress (or bundled inside the `cluster93-server` jar under `/console/`).
  3. **Functional Views:**
     - **Jobs & Workflows:** Real-time streaming console logs (`/v1/jobs/{id}/events` via fetch streaming), step inspection (`ctx.step`), attempt history, manual cancel.
     - **Services:** Real-time health, supervisor status, restart backoffs, route inspector.
     - **VFS File Explorer:** Directory tree navigation, chunked uploads, download test, presigned links.
     - **Cluster & Tenant Admin:** Node capacity/heartbeats, tenant quotas, team roles & invite codes, audit chain verifier.

### Phase 7: The Node Runtime in Containers / gVisor
- **Reference Spec:** `docs/design/node-runtime.md` (D-084, D-085, D-086).
- **Core Requirements:**
  1. **Runtime Type `"node"`:** Enable execution of native Node.js workloads that require `.node` C++ addons, `child_process`, `worker_threads`, and frameworks like Express/Fastify/Next.js.
  2. **Official Node + JS Runner:** Lightweight Node runner package implementing Cluster93 globals over the runner protocol IPC stream.
  3. **Container Sandboxing:** Rootless Podman containerization with gVisor (`runsc`) user-space kernel for untrusted tenants.
  4. **Zero-Trust Network Tunneling:** Container runs `--network none`; all egress (`fetch`, `net.Socket`) and ingress (`ctx.serve`) are piped through the UDS protocol channel.
  5. **Layered Artifacts:** Deployments split into content-addressed tar layers (`node_modules`, app source) without running untrusted install scripts on cluster nodes.

### Phase 8: Differentiated Node Roles & Capability-Based Scheduling
- **Reference Spec:** `ideas/node-roles.md`.
- **Core Requirements:**
  1. **Node Specialization:**
     - **API / Ingress Gateway Nodes:** Terminate public TLS, validate OpenAPI requests, handle token auth, route ingress traffic; execute zero untrusted guest code.
     - **Storage / Data Nodes:** Run durable Apache Ignite persistence, WAL, and VFS chunks without CPU contention from script workloads.
     - **Compute Worker Nodes:** Run on private subnets hosting sandboxed execution engines with kernel cgroups boundaries.
  2. **Capability Matching:** Route jobs to nodes advertising matching runtime capabilities (`capabilities: ["js:v8", "js:graaljs", "node"]`).

### Phase 9: Polyglot Serverless Compute (Python)
- **Reference Spec:** `ideas/python.md`.
- **Core Requirements:**
  1. Dedicated Python worker daemon communicating over `cluster93-runner-protocol` binary framing, bringing Python background jobs and ASGI microservices to the cluster.


---

## 5. Recent Key Achievements & Bug Fixes

- **Cgroup detection and per-tenant CPU limits (D-158):** the node now detects the host's cgroup v2 shape (its own
  cgroup from `/proc/self/cgroup`, the cgroup2 mount, and the delegated controllers) instead of assuming the
  root-only `/sys/fs/cgroup/cluster93` — which silently applied **no** limit to any non-root node. At startup it
  prepares its own cgroup (moving itself into a leaf so the delegated controllers can be enabled for children; the
  sequence `scripts/cpu-delegation-probe.sh` proved on a real unit), then writes `cpu.max` per **tenant**
  (`C93_MAX_CPU_PER_TENANT`, a percentage of one core) and `memory.max` per **worker** (D-163). Warn by default;
  `C93_REQUIRE_CGROUP_LIMITS=true` refuses to start without enforcement. Evidence: `CgroupSupportTest`,
  `WorkerCgroupsTest`, `ServerConfigTest`; the probe PASSED on a real systemd host.

- **The node-wide worker budget now includes memory (D-165):** a `WorkerMemoryBudget` reserves each worker's cgroup
  size (`maxJobsPerWorker × maxHeapMb + 128 MB`, D-163) when it spawns and returns it when reaped, so the node's
  total worker memory is bounded explicitly rather than only by the worker count (D-156). `C93_MAX_WORKER_MEMORY_MB`
  defaults to what `C93_MAX_WORKERS` already implies, so nothing changes until an operator lowers it; `0` is
  unbounded and the first worker is always admitted. Reported as `c93.workers.memory.used_bytes`/`limit_bytes` and in
  the snapshot. Evidence: `WorkerMemoryBudgetTest`, `TenantWorkerPoolTest`, `ServerConfigTest`, `ObservabilityTest`;
  `verify -pl cli,runner-protocol,server -am` green.

- **A node's worker use, and each tenant's, is now observable (D-156, D-164):** `TenantWorkerPoolManager.snapshot()`
  reports, per node, workers running / the limit / warm / tenants active, and per tenant its workers, concurrent jobs,
  waiting jobs and memory-budget enforcement layer (D-157). Prometheus gains node gauges (`c93.workers.running|limit|warm`,
  `c93.tenants.active`), per-tenant gauges (`c93.tenant.workers|jobs.running|jobs.waiting`) and a per-tenant crash
  counter (`c93.workers.crashes`, fed by D-159); `c93 node status` reads them from `/metrics` for a `workers:` line.
  The same pass hardened `ProcessModeParityTest`: it now requires the registered worker engine to be *compiled*, not
  merely named, so a worker built without its engine profile skips with the build hint instead of losing every job to a
  `ServiceConfigurationError`. Evidence: `TenantWorkerPoolTest`, `TenantWorkerPoolManagerTest`, `ObservabilityTest`,
  `NodeCommandTest.statusShowsTheNodesWorkerUseFromItsMetrics`; `verify -pl cli,runner-protocol,server -am` green.

- **A worker's crash now names the tenant and the job, not just a pid (D-159):** a job whose worker dies is re-labelled
  from the worker's exit code — "killed: out of memory or force-killed (SIGKILL)" (137), "crashed: segmentation fault
  (SIGSEGV)" (139), "exited with code N" — instead of the bare "connection closed unexpectedly (EOF)" the pipe produced.
  The cause is read from the process **reaper** (`Process.onExit()`), not the instant the pipe EOFs: the EOF and the exit
  are observed on different threads, and reading too early is exactly how the code is lost. A worker that dies *before*
  its handshake is attributed the same way, and the pool's crash and stderr-tail logs now name the tenant. Evidence:
  `WorkerExitTest`, `WorkerProcessRuntimeTest`, `ProcessModeParityTest.detectsAWorkerProcessKill` (a real `kill -9`);
  `verify -pl runner-protocol,server -am` green (server 215).

- **A guard on the worker's classpath (D-030):** the worker is a separate process with its own classpath, and nothing
  asserted Ignite could not reach it — the server's `ArchitectureTest.igniteStaysInsideItsModule` sees Ignite on *its*
  classpath but not `cluster93.worker`. `WorkerClasspathIsolationTest` (in `cluster93-worker`) now asserts Apache
  Ignite, the Vert.x `IgniteClusterManager`, `cluster93-cluster-ignite` and Hazelcast are **not** on the worker's
  classpath, and proves it is looking at the real one by asserting the allowed stack (`runner-protocol → core →
  vertx-core`, plus `runtime-js`) **is** present. A dependency added to `core` or `runner-protocol` can no longer
  smuggle Ignite into a worker unnoticed. Evidence: `./mvnw -o verify -pl worker -am -DskipITs` green.

- **Per-tenant workers run several jobs at once, and a cancel no longer kills the siblings (D-155, D-162, D-163):**
  - A worker serves up to `C93_MAX_JOBS_PER_WORKER` jobs concurrently (default 4), each in a fresh context, and its tenant keeps it for the next job (`REUSE_SAME_TENANT`). It serves **one tenant at a time**, and since D-172 it is re-bound to another tenant whenever it goes idle rather than being kept for that tenant for life. `C93_MAX_JOBS_PER_WORKER` had been held at 1 only because a shared channel was unsafe.
  - **The shared-channel cancel bug is fixed.** A run-level non-success (cancelled, failed, timed out) was being treated as a poisoned worker, so `release` reaped a worker that still had live siblings — a parent cancelling its child destroyed the worker the parent was running on. `release` and `poison` now separate the two (D-162): only a worker that is itself gone is recycled, even that with its reason and stderr tail logged.
  - The worker's **cgroup is sized for the jobs it runs** (`maxJobsPerWorker × heapMb` + overhead), not one job's (D-163) — sized for one, a shared worker was OOM-killed (exit 137) as soon as a second job ran.
  - Evidence: `TenantWorkerPoolTest` (5 new), `JobsFromScriptsTest.aParentCanStopAChildAndFollowItsEvents`, `ProcessModeParityTest`; full server suite 210/210 and `verify` across all modules.
  - **The node now caps how many worker processes it runs (D-156):** `C93_MAX_WORKERS` (default `C93_CAPACITY`) bounds them; work preempts warmth, so an idle worker is reaped to make room and a job that cannot get a worker waits rather than failing. *(The node-wide bound on concurrent **jobs** is the scheduler's, unchanged.)* Worker-in-container isolation is captured as a deferred idea in `ideas/containers.md` — the node running worker processes directly is the current shape.

- **Phase 11 — Consolidation & Release Readiness delivered (W1–W6):** one checkout orchestrator (`scripts/local.sh master|dev`, no `dev.sh`), `examples/js/` by language with a runner that also exercises the service example, one update surface (`c93 update [--cli|--node]`, D-138), one benchmark entrypoint (`benchmarks/run.sh cluster|engines|all`), prefix-less module directories (D-134), and docs reconciled with reality — the detail is in §3.

- **Default ports move to the `9393`–`9396` Cluster93 block (D-137):**
  - `C93_HTTP_PORT` 8080 → **9393**, `C93_INGRESS_PORT` 8000 → **9394**, `C93_METRICS_PORT` 9090 → **9395** (Prometheus's own port), `C93_INTERNAL_PORT` 8090 → **9396**, applied at `ServerConfig.fromEnv` and everywhere that mirrors it: the CLI (`NodeCommand`, `NodeInstall.defaultConfig`, `NodeService`), `scripts/local.sh`/`dev.sh`, the Docker image and `docker-compose.yml` (host `940x`/`941x`), the OpenAPI `servers` URL, the Prometheus scrape targets, the Grafana dashboard (and its generator), README, and the guides.
  - Existing nodes keep their ports (a written `node.env`/`local.env` names them), so only fresh installs pick up the new defaults; the old Docker host mappings (`8081-8083`/`8001-8003`) changed, so those client URLs need updating.

- **Worker startup fails fast instead of hanging (D-135, Phase 5):**
  - `NodeWorkerChannel.onError` now fails `helloPromise` when a worker dies before its handshake (WARN) instead of only failing active runs at debug; a pool `acquire()` no longer waits on a promise that can never complete.
  - `TenantWorkerPool.spawnWorker` bounds the hello wait (`cluster93.worker.helloTimeoutMs`, default 30 s), logs the failure at WARN with the worker's last stderr lines (a bounded tail is captured by the drain), and fails the acquire; `WorkerProcessRuntime`'s inline spawn gets the same timeout.
  - `ProcessModeParityTest` pins the engine: it reads the worker build's `META-INF/services/cluster93.worker.WorkerEngine` and aborts (a visible skip, reason printed first, with the rebuild command) only when it registers no engine at all — so a worker build that can't start becomes a ~6 s skip instead of a 30 s-per-job hang.
  - The child-JVM fallback now carries **both** engines: `cluster93-server` depends on `cluster93-runtime-graaljs` in test scope (production `c93.jar` keeps Javet only; nodes run the native workers). `ProcessModeParityTest` is green whether `cluster93-worker` was compiled `-Pjavet` or `-Pgraaljs`.
  - `c93 node install --system` now provisions its own `cluster93` service account (idempotent `useradd --system --user-group`), owns `/var/lib/cluster93` by it, and makes `node.env`/`node.meta` `0640 root:cluster93`, so the rendered unit can actually start (D-136). `--dir` installs record their layout in `node.meta` so the lifecycle verbs find them (found by a native-binary smoke test).
  - `TenantWorkerPool` now counts every worker exactly once (spawn takes a slot, recycle releases it idempotently), so `maxTotal` (= `C93_CAPACITY`) actually bounds warm + active + in-flight workers instead of drifting negative.
  - Verified: `NodeWorkerChannelEofCrashTest` (EOF before hello), `TenantWorkerPoolTest` (dies-before-hello and never-hello both fail the acquire, leaving `totalWorkers() == 0`); full `./mvnw verify -pl cli,server -am -DskipITs` green (server 206, cli 85, runner-protocol all green), with GraalJS and with Javet.

- **`c93 node` — node install, unified lifecycle, and the installer rewrite (D-134, Phase 5):**
  - Folded `c93 local …` into `c93 node`: `NodeCommand` now owns `install`/`uninstall`, the lifecycle verbs (`setup`, `start`, `stop`, `restart`, `status`, `logs`, `mcp`, `update`, `env`) and the operator's `invite`/`revoke`. `c93 local` is a hidden, deprecated alias for one release; no behaviour is lost, and the verbs still understand both an installed node (`node.env` + a service unit) and the development master a checkout owns (`$C93_HOME/local.env` + `scripts/local.sh`).
  - New `NodeInstall` fetches a signed release (reusing `UpgradeCommand.installBase`, `ReleaseSignature` and the CLI-built-in key, D-069), verifies `SHA256SUMS` and every asset, gunzips the native workers into `bin/`, writes `node.env` (`C93_*` settings, so a systemd `EnvironmentFile=` feeds the server directly) and a rendered service unit; user and `--system` (Linux) layouts, plus `--dir`, `--version`, `--no-start`, `--force`.
  - New `NodeService` detects the backend — a user or system systemd unit, a macOS LaunchAgent, or the process directly (`java -jar c93.jar`, pid file under the data dir) — and drives it; `c93 node status` reports which was found. `c93 node env` shows and changes `node.env` (short names accepted).
  - Reference unit templates added under `install/systemd/` (system and user) and `install/launchd/`; `install.sh` is now a CLI bootstrap whose `--node`/`--system` delegate to `c93 node install`, and `install.ps1` installs the portable CLI jar with `c93.cmd` and Java 21 detection (no Windows node).
  - Docs: new `docs/guides/node.md`; README install + run-a-node (and the macOS/Windows wording fixed); `cli.md` and `cluster.md` updated.
  - Verified: `NodeCommandTest` (28 tests) against a local release host — layout, `node.env`, the rendered unit, `--no-start`, checksum/signature refusal, unsigned releases, a stub `systemctl` driving `start`/`stop`, the direct-process fallback, uninstall with `--purge`, and the `c93 local` alias; `InstallScriptTest`, `CliTest`, `MainHelpTest`, `VersionCommandTest` all green.

- **Rebrand: Automately → Cluster93 (`c93`):**
  - Repo-wide mechanical rename (711 path renames, 712 files rewritten, 0 residue) driven by `tools/refactor-rename.sh` from a reviewable map (`tools/rename-map.tsv`) and allowlist (`tools/rename-keep.txt`); historical/lineage text preserved.
  - Modules `automately-*` → `cluster93-*`; packages `automately.*` → `cluster93.*`; Maven groupId/artifactId `cluster93`; env `AUTOMATELY_*` → `C93_*`; home `~/.automately` → `~/.cluster93`; config `~/.config/automately` → `~/.config/cluster93`; app manifest `cluster93.json`; built-in imports `c93:*`; CLI/server jar `c93` (`c93.jar`, `c93.pid`); wire IDs `X-Cluster93-*` / `Cluster93-Signature` / `Server: c93`; Ignite SQL schema `CLUSTER93` and data region `cluster93`.
  - Added `DEDICATION.md` and a README `## Heritage` memorial (lineage: first published 2014, acquired before 2018; archived original at `web.archive.org/.../github.com/Automately`).
  - Migration tooling: `scripts/migrate-home.sh` (local user state) and `tools/migrate-tool-state.sh` (agent/IDE memory, opt-in; `--move-directory` for a later tree move).
  - ⚠️ **Breaking:** the Ignite SQL schema and data region changed (durable data written by a pre-rename build is not read); built-in module imports are now `c93:` (`import { kv } from 'c93:kv'`).
  - **Verification:** all 11 modules compile; targeted suites green (`ArchitectureTest`, `ImportsTest`, `ServerConfigTest`, `UpgradeTest`, `InstallScriptTest`, `MainHelpTest`, `ClusterTest`, `BinarySchemaTest`, `FileFailoverTest`, `ObservabilityTest`, `DashboardTest`, `ProcessModeParityTest` with `-Pjavet`). One real regression found and fixed: the package-dot rule turned dotted Micrometer names into `cluster93.*` (exported `cluster93_*`) while the dashboard/tests/docs use `c93_*`; an explicit `builder("automately.` → `builder("c93.` rule (map rule 25) aligns them.
  - **Post-rename build steps:** native binaries are not source. `scripts/build-workers.sh all` was run (fresh `bin/worker-javet`, `bin/worker-graaljs`, both now embedding `c93:kv`); `scripts/build-cli.sh` produces `bin/c93`. Before the rebuild, process-mode tests failed (`PackagesTest`) because the stale `bin/worker-javet` still embedded the old `automately:` scheme; after it, `PackagesTest`, `AuthTest`, `IdentityTest`, `ContractTest`, `ObservabilityTest`, `DashboardTest` and `ProcessModeParityTest` (with `-Pjavet`) are all green.
  - Pre-existing (not rename-caused): running the whole server suite in one JVM can cascade from a `FileFailoverTest` TLS flake ("Ignite instance already started"); it reproduces on the pre-rename tag and each test passes in isolation.
  - Token prefix: `TokenFormat.PREFIX` (and the node bundle/cert prefixes in `NodeBundle`) changed `atly_` → `c93_`. This invalidates existing tokens by design (they were being retired anyway); the OpenAPI pattern, tests, docs, and the recording redactor were updated with it.

- **Dual Worker Compilation, GraalVM Helper & GraalJS Default Engine:**
  - New `scripts/detect-graalvm.sh` resolves the GraalVM SDK once (`GRAALVM_HOME`, `JAVA_HOME`, SDKMAN `current`/`*graal*` candidates, then `PATH`); sourced by `scripts/build-workers.sh` and `scripts/build-cli.sh` instead of each carrying its own discovery logic.
  - `scripts/local.sh` and `scripts/dev.sh` now build both native workers (`bin/worker-javet` and `bin/worker-graaljs`) via `build-workers.sh all` when either is missing (and on `local update`), so the engines can be switched back and forth seamlessly.
  - `ServerConfig` defaults `C93_WORKER_DEFAULT_MODE` (and the legacy `C93_WORKER_ENGINE`) to `graaljs`, auto-registers both workers from `bin/`, and selects the `"default"` binary according to the default mode. `scripts/local.sh` records both worker paths in `node.meta` and honors `DEFAULT_WORKER_ENGINE`/`WORKER_ENGINE` in `local.env`.
  - `ProcessModeDefaultTest` locks the `graaljs` default, the `"default"` binary pointing at `graaljs`, and the `javet` switch via `C93_WORKER_DEFAULT_MODE`.

- **Member Management & Password Lifecycle (Phase 6):**
  - **Self-Service Password Updates:** Added `PUT /v1/me/password` (`updateMyPassword`) across `openapi.yaml`, `RouteScopes.java`, and `IdentityRoutes.java`. A user sets a first password without a current one, or changes an existing one by proving it (`Kdf.scrypt` + `MessageDigest.isEqual`); wrong or missing current passwords answer `401`.
  - **`hasPassword` Everywhere:** `/v1/whoami`, `/v1/me`, `/v1/users`, and `/v1/users/{name}` now expose `hasPassword` (and the `User`/`Whoami` OpenAPI schemas document it), so the console can offer password management.
  - **Operator Invite Lifecycle:** Added `GET /v1/admin/invites` (`listOperatorInvites`) and `DELETE /v1/admin/invites/{id}` (`revokeOperatorInvite`) so the console can list and withdraw operator invites, complementing the existing `POST /v1/admin/invites`.
  - **CLI Password Flow:** `c93 login --invite CODE --password …` (with interactive prompt) sets a password on redemption, and `c93 profile passwd` (`--password` / `--current-password`, prompting otherwise) changes it. `scripts/local.sh` `provision_admin` now prompts for (or generates) an admin console password and passes it when redeeming the operator invite.
  - **Web Console Identity Tabs:** `LoginView` gains a "Redeem Invite" mode (code, username, password, confirm); the sidebar gains `Users & Invites` (operator) and `Members & Invites` (tenant), backed by new `UsersTab` / `MembersTab` components and `api.ts` methods (`updatePassword`, `redeemInvite`, `getMembers`, `getTenantInvites`, `createTenantInvite`, `revokeTenantInvite`, `getUsers`, `getAdminInvites`, `createAdminInvite`, `revokeAdminInvite`).
  - **Coverage:** Added `AuthTest.usersSetAndChangeTheirPassword`, `IngressAuthIntegrationTest.ingressPasswordUpdateRoundTrips`, `CliTest.redeemingAnInviteSetsTheConsolePassword`, `LoginView` redeem tests, and `UsersTab`/`MembersTab` component tests, plus `ContractTest` coverage for the new operations.

- **Pure Web Test Suite for Web Console & Ergonomic Capacity Metrics (Phase 6):**
  - **Vitest & React Testing Library Test Harness:** Established a pure web test harness in `console/` using Vitest, React Testing Library, and jsdom with path alias resolution and in-memory DOM shims.
  - **Component & API Client Tests:** Added 26 unit and component tests across `api.test.ts` (token lifecycle, tenant scoping, error handling, unauthorized interception), `OperatorView.test.tsx` (capacity and utilization metrics without jargon), `Header.test.tsx` (tenant context switcher), and `LoginView.test.tsx` (credentials exchange).
  - **Intuitive Cluster Capacity Metrics:** Replaced cryptic "Available Headroom" / "idle slots ready for work" cards in `OperatorView.tsx` with clean, professional compute metrics: Compute Nodes, Worker Concurrency, Active Executions (with percentage load and progress bar), and Available Slots.
  - **Ingress Cross-Tenant Header Forwarding:** Fixed `IngressVerticle.proxy()` to allow `X-Cluster93-Tenant` on public API proxy rewrites (`proxyApi`), allowing operators to scope API queries without encountering 403 Forbidden errors.

- **Username & Password Authentication Architecture (D-133, Phase 6):**
  - **Core Scrypt Password Hashing:** Extended `Identity` with `CREDENTIALS` record kind, random 16-byte cryptographic salts, and `Kdf.scrypt` hashing with constant-time `MessageDigest.isEqual` comparison.
  - **Public Login Endpoint:** Added `POST /v1/auth/login` to `openapi.yaml`, `RouteScopes.java`, and `IdentityRoutes.java`. Validates user credentials and mints a 30-day personal session token (`c93_...`).
  - **Web Console Dual-Mode Login:** Redesigned `LoginView.tsx` with Username & Password + optional Tenant as the primary interface, retaining an expandable "Sign in with API token" fallback for CLI and token power users.
  - **Full Pre-commit Verification:** Passed `IdentityTest`, `AuthTest`, `ContractTest`, `./mvnw verify -pl core,server -am -DskipITs`, and `npm test` (26/26 passed) with 0 regressions.

- **Admin Tenant Visibility, Worker Concurrency Metrics & VFS File Explorer (Phase 6):**
  - **Operator Cross-Tenant Context Routing:** Evolved `ApiVerticle` authorization to allow cluster operators and the admin token (`principal.admin() == true`) to inspect and administer any tenant's workloads (jobs, services, apps, files) by passing `X-Cluster93-Tenant` or `?tenant=<name>`. Replaced rigid D-044/D-070 rejection strings with helpful guidance.
  - **CLI Ergonomics & Jargon Removal:** Added an inherited `--tenant <name>` option to `c93` CLI, attaching `X-Cluster93-Tenant` on API requests. Purged internal decision tags (`(D-044)`, `(D-070)`, `(D-071)`, `(D-083)`, `(D-084)`, `(D-092)`, `(D-112)`) from command descriptions and user error messages.
  - **Web Console Tenant Context Switcher:** Added interactive dropdown to `Header.tsx` allowing operators to seamlessly toggle between Cluster Infrastructure and any tenant workspace (`local`, `system`, or user-created), dynamically updating the API client's target tenant context.
  - **Tenants Management & Workspace Inspection:** Removed `D044Notice` banner and added a dedicated Tenants tab and overview card to list all cluster tenants with creation dates, 1-click workspace inspection, and inline tenant creation.
  - **Hierarchical VFS File Explorer:** Upgraded `FilesTab.tsx` into a full directory tree browser with folder navigation, clickable breadcrumbs, file size/MIME badges, search filtering, and an in-browser File Content Preview Modal for reading text, JSON, code, HTML, and markdown files directly.

- **Local Master Node Orchestration & Web Console Live Verification (Phase 6):**
  - Added foreground `run` command to `scripts/local.sh` with PID file tracking and `exec env ...`, enabling clean supervision under daemon task runners and system process supervisors without background subshell or cgroup termination.
  - Exposed `seed` as a top-level command in `scripts/local.sh` for on-demand re-provisioning without resetting configuration.
  - Successfully seeded the running master node: ensured `system` tenant, built and deployed Next.js web console (`console/`) to `system/console` via `c93 apps deploy`, provisioned default `admin` operator (`admin: true`), and verified live serving at `http://localhost:8000` with HTTP 200 HTML and asset delivery.
  - Verified D-044 security isolation with `admin` operator personal token (`whoami.admin == true`): operator manages cluster/nodes/tenants/users while tenant data remains strictly isolated.

- **Durable App Storage & Local Start Console Auto-Provisioning (Phase 6.2):**
  - Implemented `IgniteAppStore` on Apache Ignite with durable tables `APPS` (`tenant`, `name`) and `APP_DEPLOYMENTS` (`tenant`, `app`, `deploymentId`), registered in `IgniteStores.TABLES` and wired into clustered `Cluster93` server startup. App registrations, deployment histories, promotion state, and rollbacks now survive full cluster stops and restarts.
  - Comprehensive contract test suite in `IgniteAppStoreTest` covers deployment idempotency, version conflicts, promotions, rollbacks, deletions, and concurrent compare-and-set updates.
  - Updated `scripts/local.sh` and `scripts/dev.sh` to extract idempotent `ensure_console()`: ensures `system` tenant, verifies active web console availability on the ingress port, and automatically builds/deploys the console SPA upon startup (`c93 local start` / `scripts/local.sh start`).

- **Single-Node Restart Job & Service Recovery (Phase 6.3):**
  - Added in-script execution capability declarations via `ScriptConfig` (`cluster93.core.job.ScriptConfig`): scripts can declare `export const config = { onOrphan: 'resume' | 'retry', maxAttempts: N, timeoutMs: T };` (or direct exports `export const onOrphan = ...`). `ApiVerticle` automatically adopts script-declared policies when omitted from the request body while preserving CLI/API flag overrides, safely falling back to `fail` if neither declares a policy.
  - Accelerated recovery on restart: `OrphanReaper` and `ServiceSupervisor` now execute an immediate `sweep()` upon startup instead of idling for their initial periodic timer interval.
  - Implemented fast-path orphan reaping for known-dead instances: when a job's node matches this node's `nodeId` but has an older `nodeInstance`, the job is immediately reaped and re-queued without waiting for the 15–30s orphan grace period.
  - `ServiceSupervisor` promptly resumes runs on dead node instances and provisions fresh runs carrying forward previous state.
  - Verified with 20 unit tests in `ScriptConfigTest` and full cluster crash/restart end-to-end integration test in `SingleNodeRestartRecoveryTest`.

- **Repo Path Auto-Detection & Recovery Hardening (Phase 6.4):**
  - Enabled `c93 local` commands (`c93 local update`, `start`, `setup`) to reliably find the system repository from anywhere on the machine outside the git checkout: `scripts/local.sh` persists `REPO_ROOT` to `local.env` and `node.meta`, and `LocalCommand.java` resolves it in order from `C93_REPO`, `local.env`, `node.meta`, and CWD git walks. Verified in `LocalCommandTest`.
  - Added automatic web console rebuild and redeployment to `c93 local update` via `ensure_console`, with curl exit guarding under `set -e`.
  - Partition safety in `OrphanReaper`: removed the `soleNode` shortcut, ensuring rolling restarts, temporary GC pauses, and network partitions in multi-node clusters always observe the full 3-heartbeat grace period.
  - Hardened `ScriptConfig`: replaced regexes with a string- and comment-aware balanced-brace depth scanner supporting nested structures and numeric separators (`30_000`).
  - Added end-to-end app and deployment persistence verification to `FullRestartTest`.

- **CLI Versioning, Git Metadata & Update Detection (Phase 6.5):**
  - Added `<git.commit>` and `<build.time>` build-time metadata properties in `cli/pom.xml` and resource filtering into `cluster93-cli.properties`.
  - Added accessor methods `Main.commit()`, `Main.builtAt()`, and `Main.shortCommit()`.
  - Updated `VersionCommand` to support `--json`, display git commit and build timestamp, and auto-detect the local repository root (using `LocalCommand.findRepoRoot()`). When a repo is present, it inspects `HEAD` to indicate whether the CLI is up to date or has an update available (`run 'c93 local update' or 'scripts/build-cli.sh' to recompile`).
  - Updated `LocalCommand.status` to display the active CLI commit and update status alongside the cluster status.
  - Updated `scripts/build-cli.sh` to extract the current git commit and UTC build timestamp, passing them via `-Dgit.commit` and `-Dbuild.time` during native image and jar packaging.
  - Updated `scripts/local.sh:cmd_update` to re-run `scripts/build-cli.sh` when a native CLI binary exists, ensuring the native CLI stays in sync after repo updates.
  - Verified with 7 unit tests in `VersionCommandTest` (version formatting, commit display, repo detection, update detection, and JSON schema).

- **First-Run Web Console Administrator Setup Wizard (Phase 6.6):**
  - Added public setup status probe `GET /v1/auth/setup-status` and administrator account creation endpoint `POST /v1/auth/setup`.
  - Core identity lifecycle in `Identity`: `needsSetup()` returns true while no operator account exists in the cluster; `initialSetup(name, displayName, email, password)` provisions the operator, hashes password using Scrypt, mints a full-permission operator session token (`c93_...`), and fails closed with HTTP 409 Conflict once an operator exists.
  - Web Console dynamic first-run onboarding in `LoginView.tsx`: probes setup status on load; if uninitialized, automatically renders the "Initial Setup" administrator creation form, prompting for password confirmation and seamlessly logging into the dashboard on submission.
  - Documented OpenAPI operations in `openapi.yaml` and registered public routes in `RouteScopes.java`.
  - Verified with `AuthTest` (11/11), `ContractTest` (100% operation coverage), and Vitest component suite in `LoginView.test.tsx` (36/36 passed).

- **CLI Help Grouping & Default Operator Onboarding (Developer Ergonomics):**
  - `c93 --help` no longer prints a flat ~30-command list: `Main` replaces picocli's single command-list section with five categorized sections — **Developer & Applications**, **Platform Resources**, **Identity & Access**, **Cluster & Local Node**, and **Tools & Automation** — while subcommands keep their own command lists (e.g. `c93 help local`).
  - New `MainHelpTest` locks the category headings, the command-to-category mapping, and that only the top-level list is categorized (no `Commands:` heading at the top, but subcommands still show theirs).
  - `scripts/local.sh` and `scripts/dev.sh` `cmd_seed` now always ensure the reserved `system` tenant, deploy the web console to `system/console`, mint an operator invite with `ADMIN_TOKEN` and redeem it as the `admin` user to mint an **Operator Personal Token**, ensure the developer workspace tenant (`$TENANT`, default `local` / `dev`), save that operator token as the CLI default in `~/.config/cluster93/config.json`, and print a setup-completion banner (Web Console URL, API URL, Operator User, Token). Re-runs reuse the saved operator token, so seeding is idempotent.

- **Console Node Role & `system/console` Auto-Provisioning (Phase 6):**
  - `ServerConfig.ROLES` gains `console`, designating the node that serves the management web console; the role defaults on for a development node (`api,ingress,worker,data,console`).
  - `AppRouter` now takes the node's roles and refuses the console host (returning `NotAnApp`) unless the node plays `console`; with no `C93_CONSOLE_DOMAIN` set, a console-role node additionally answers the bare `localhost`/`127.0.0.1` host with `system/console`.
  - `LocalCommand.DEFAULT_ROLES` and `scripts/local.sh`/`scripts/dev.sh` default to the console role; `cmd_seed` ensures the reserved `system` tenant, builds `console/` when `npm` is available, mints a `system` token and deploys `console/` via the CLI (native binary or shaded-jar fallback) — then continues with the developer tenant.

- **Web Console — Next.js App Deployed as a Cluster93 App (`console/`):**
  - New self-contained Next.js 15 (App Router) app built for static export (`output: 'export'`, `distDir: 'out'`, `trailingSlash: true`, `images.unoptimized`) and deployable as the `console` app through the apps primitive.
  - `cluster93.json` manifest rewrites same-origin `/v1/*` to `api:/v1/$1` (zero CORS), falls back to `/index.html` for SPA routes, and serves `/_next/static/*` immutably.
  - Dark zinc/slate Tailwind UI: shared `Header` (connection + scope badges, logout) and `Sidebar`; `LoginView` for bearer-token entry; `OperatorView` (cluster topology with per-node capacity/roles, health, storage utilization, and the D-044 tenant-isolation notice); and `TenantView` composed of `JobsTab` (status badges, duration, cancel), `ServicesTab` (status, hosts, revision, restart/stop), `AppsTab` (deployments, preview/canonical URLs, promote/rollback), and `FilesTab` (path, size, MIME type, modified).
  - Typed client (`src/lib/api.ts`) targets same-origin `/v1/*`, stores the bearer token in `localStorage`, attaches `Authorization: Bearer`, and clears the token and returns to the login prompt on any `401`; interfaces in `src/lib/types.ts` mirror the OpenAPI models.
  - Verified: `cd console && npm install && npm run build` emits a static `out/` with `index.html`, `404.html`, and `_next/static/` chunks.

- **Apps CLI — Deploy, Serve, Promote, Roll Back & Remove (Phase 6.1, Phase 3 CLI):**
  - `AppsCommand` adds `c93 apps` with `deploy` (reads `cluster93.json`, honors `distDir`, falls back to `out/`, `dist/`, `build/` or the project root; uploads the static export to `/.apps/<app>/deployments/<id>/` via `PUT /v1/files`; registers the immutable deployment with an optional git commit; auto-promotes unless `--no-promote`), `ls`/`list`, `get`, `deployments`, `promote`, `rollback`, and `rm`/`delete` (with `--yes`).
  - Deploy prints the app name, deployment id, file count and size, plus the preview (`<deploymentId>--<app>--<tenant>`) and canonical (`<app>--<tenant>`) URLs derived from `C93_INGRESS_URL` (or the cluster URL); every command supports `--json`.
  - `Main` registers `AppsCommand`; `AppsCliTest` drives the whole lifecycle against a real in-process node, including serving the promoted static site through the ingress and the SPA fallback.
- **Apps Primitive — Server, Ingress Router, Security & REST API (Phase 6.1):**
  - `apps:read`/`apps:write` scopes (`Scopes`, `openapi.yaml`) and `/v1/apps` REST operations (list/get/delete apps, list/get/create deployments, promote, rollback) in `AppsRoutes`, with `RouteScopes` rules and full OpenAPI 3.1 schemas.
  - `AppStore` instantiated in `Cluster93.Stores` and wired into `ApiVerticle` and `IngressVerticle`.
  - `AppRouter` resolves app hosts (console domain → `system`/`console`, preview `<deployId>--<app>--<tenant>`, canonical `<app>--<tenant>`, and `X-Cluster93-App`/`X-Cluster93-Tenant` test headers) and evaluates manifest routes: service proxy, same-origin `api:` rewrite, VFS static streaming via `FileResponder` (ETag/304/Range + manifest headers), and SPA fallback to `/index.html`.
  - `CidrMatcher` parses and matches IPv4/IPv6 CIDRs; the console host enforces `C93_CONSOLE_ALLOWED_CIDRS` with 403 for unauthorized clients.
  - `ServerConfig` gains `C93_CONSOLE_DOMAIN` and `C93_CONSOLE_ALLOWED_CIDRS`; `IngressVerticle` consults `AppRouter` before ordinary service routing, so app and service hosts share the ingress domain.
  - Tests: `CidrMatcherTest`, `AppRouterTest`, `AppsRoutesTest` (including an end-to-end ingress static-serving check), `ContractTest` and `ServerConfigTest` additions.
- **Standalone Node Storage Isolation & Immediate Activation:**
  - `scripts/dev.sh` now keeps its Ignite store in `${C93_HOME}/dev-data` and `scripts/local.sh` its permanent master in `${C93_HOME}/master-data`, so development testing and the master node can no longer share a `db/` folder, WAL or Ignite baseline.
  - `IgniteCluster.firstStart` is node-identity aware: it checks for this node's own `db/<masked consistent id>` store instead of any shared `db/`, so a directory another identity wrote to is no longer mistaken for a restart.
  - `IgniteCluster.awaitActive` activates a standalone (`initialClusterSize == 1`) single server immediately when there is no baseline, or when the baseline names only absent nodes of another identity, instead of stalling; multi-node clustered waiting and backups validation are unchanged.
  - `LocalCommand`'s launch fallback now matches the orchestrator (`master-data`), and the `scripts/local.sh` heredoc no longer evaluates backticked prose as commands.
- **`NodeVerticle` Thread Safety Fix:** Replaced `HashMap` with `ConcurrentHashMap` for `slots` and snapshot copies in `checkRunningJobs()`, preventing `ConcurrentModificationException` during concurrent job completion/cancellation.
- **Unified Test Script (`scripts/test-all.sh`):** Added default logging to `lasttest.txt`, real-time console streaming, and instant failure summaries with grepped error lines.
- **Full Process-Mode Parity (D-132, commit `87cc57c`):**
  - Bidirectional stream multiplexing for `ctx.serve` (ingress), `fetch` (egress), `net`/`tls` sockets, and WebSocket upgrade tunneling.
  - Credit-based connection window replenishment (`streamId=0`) eliminating 4 MiB stream deadlocks.
  - Immediate worker crash / EOF detection via `input.endHandler()`.
  - Non-blocking state commits (`vertx.executeBlocking`) keeping event loop responsive.
- **Default Process Mode (D-131, commit `8e428e8`):**
  - Set `C93_WORKER_MODE=process` by default.
  - Dynamic discovery of native worker binaries in `bin/` with seamless child-JVM fallback.
