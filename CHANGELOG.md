# Changelog

All notable changes to Cluster93 are recorded here, with **what** changed and **why**.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Larger choices are explained in [docs/DECISIONS.md](docs/DECISIONS.md).
Versions follow [SemVer](https://semver.org/); see [VERSIONING.md](VERSIONING.md). Entries live under `## [Unreleased]` until a release dates them.

## [Unreleased]

- **A worker's death is attributed to the job that was running on it, not just to a pid** (D-159):
  - When a worker dies mid-run the channel could only report that the pipe ended — "Worker process connection
    closed unexpectedly (EOF)". The tenant is now told **why**: a `FAILED` run on a worker whose channel is gone is
    re-labelled from the exit code — "killed: out of memory or force-killed (SIGKILL)" (137; the cgroup OOM killer
    and a plain `kill -9` look the same), "crashed: segmentation fault (SIGSEGV)" (139), or "exited with code N". A
    script that merely threw keeps its own error.
  - The cause is read from the process **reaper** (`Process.onExit()`), not the instant the pipe EOFs: the EOF and
    the process exit are observed on different threads, so reading `exitValue()` too early loses the very code being
    reported. A worker that dies **before** its handshake is attributed the same way, so the reason reaches the
    tenant whichever side of the handshake the kill lands on.
  - The pool's crash and stderr-tail log lines now name the **tenant** as well as the pid, so a crash is recorded
    against its tenant rather than being anonymous.
  - Tests: `WorkerExitTest`, `WorkerProcessRuntimeTest`, and `ProcessModeParityTest.detectsAWorkerProcessKill`
    (a real `kill -9`).

- **A guard on the worker's classpath (D-030):**
  - `WorkerClasspathIsolationTest` (in `worker/`) asserts Apache Ignite, the Vert.x `IgniteClusterManager`,
    `cluster93-cluster-ignite` and Hazelcast are **not** on the worker's classpath — and proves it is looking at the
    real classpath by asserting the stack the worker *is* allowed (`runner-protocol → core → vertx-core`, plus
    `runtime-js`) **is** present. It would otherwise pass for the wrong reason, or not at all.
  - The server's `ArchitectureTest.igniteStaysInsideItsModule` covers Ignite on the *server* classpath but cannot
    see `cluster93.worker`; nothing guarded the worker's own classpath, so a dependency added to `core` or
    `runner-protocol` could pull Ignite into a worker unnoticed. This closes that gap.

- **Docker becomes a third install kind** (D-141–D-152), groundwork:
  - A `node` image stage ships `bin/worker-*`. **No stage did**, so no node image could run `C93_WORKER_MODE=process` — which is why the demo ran embedded.
  - `c93 node install --docker` lays out `node.env`, a generated `compose.yaml`, certificates and `node.meta`; the lifecycle verbs drive the container; a container node is clustered size 1 with its data bind-mounted, so persistence is the mount (D-142).
  - It **builds its own image** (`--image` wins; `--version` assembles from a verified release; otherwise the checkout's `node` stage). Running it for real found that `.dockerignore` excludes `server/target/`, so the build runs in a temp context, as `scripts/demo.sh` does.
  - `c93 node install` now **binds-tests the ports the node would take** and refuses, naming them, instead of failing at `up`. This catches what no install kind could see: a dev master at `~/.cluster93/local.env`.
  - `C93_HTTP_BIND` restricts a process node's control plane; the internal endpoint (9396) is deliberately excluded, because peers must reach it and it is mTLS-protected (D-151).
  - The generated compose carries the hardening set (`cap_drop: [ALL]`, `no-new-privileges`, read-only root + `/tmp` tmpfs, pids limit) and publishes 9393–9395/5701/5801 while only `expose`ing 9396 (D-148).

- **Workers are bounded and shared fairly** (D-153–D-161):
  - **Every tenant already had its own worker pool, and each pool's limit was the whole node**, so *n* tenants could spawn `n × capacity` workers — five tenants at capacity 8 is up to 40 processes and ~5 GB of heaps on a node advertising 8. A shared `WorkerBudget` now **bounds the node's worker processes**: `C93_MAX_WORKERS` (its own env, default `C93_CAPACITY`) caps them, warmth no longer pre-spawns (`minWarm=0`), and when a job needs a worker at the cap an **idle** worker is reaped to make room — work preempts warmth, so the old deadlock (an idle worker blocking another tenant's job) is gone. A job that cannot get a worker waits rather than failing. *(The node-wide bound on concurrent **jobs** is the scheduler's, unchanged: at most `C93_CAPACITY` running attempts per node.)*
  - A job submitted when no node has a free slot now **waits for capacity** by default (`C93_JOB_WHEN_FULL=queue`) instead of failing at once; set `C93_JOB_WHEN_FULL=reject` to restore the immediate failure. Service runs always wait.
  - Tenant fairness was installed in a per-tenant pool, where it could **never see a second tenant**, so it could never fire. It moves to the manager, the only place more than one tenant is visible (D-161).
  - A freed worker is never handed straight back to the tenant just served — the rule that stops a tenant's backlog starving another (D-153).
  - `C93_MAX_JOBS_PER_TENANT` (default `capacity`) caps a tenant; a multi-tenant node lowers it.
  - A spike proved **native GraalJS enforces per-context memory**, and found that **Javet's engine limit does not count external memory** (`ArrayBuffer` backing stores), so for Javet the cgroup is the tenant budget (D-157).


- **A worker now runs several jobs at once, per tenant — the switch is on** (D-155, D-162, D-163):
  - A worker process serves up to `C93_MAX_JOBS_PER_WORKER` jobs **concurrently**, each in its own fresh context (default 4), and its tenant keeps it for the next job (`REUSE_SAME_TENANT`) rather than reaping it after every job. `C93_MAX_JOBS_PER_WORKER` had been held at 1 while a shared channel was unsafe; the fix below is what let it move. A worker is still never shared between tenants.
  - **Cancelling one run no longer takes down its siblings.** `release` and `poison` now separate a *run*'s outcome from a *worker*'s health (D-162): a cancelled, failed or timed-out job frees only its own slot, and a worker is recycled **only** when it is itself bad — process gone, OOM-killed, channel broken, bind failed — and then with the reason and its stderr tail logged. Before, any non-`SUCCEEDED` job result was treated as a poisoned worker, so a parent cancelling its child reaped the very worker the parent was running on (`JobsFromScriptsTest.aParentCanStopAChildAndFollowItsEvents`, which failed deterministically with the switch on).
  - The worker's **cgroup memory is sized for the jobs it runs** (`maxJobsPerWorker × heapMb` plus overhead), not one job's (D-163). Sized for one job, a shared worker was OOM-killed (exit 137) the moment a second job ran. The engine's per-context budget is unchanged (D-157): each job still gets its own `heapMb`.
  - The worker's stderr tail is now logged whenever the pool recycles a worker, so a pool-initiated kill is no longer silent.
  - **A job queued behind a busy worker runs when one of that worker's jobs finishes**, and when a worker that was still starting binds. The pool only drained its queue when a whole worker was retired, so a job queued while a sibling kept the worker busy — or while another job's worker was spawning, which holds the tenant's only worker slot — waited for a worker that would never go idle: a hang, seen as `ProcessModeParityTest.resumesMemoizedSteps` timing out under load.



- **A failed cluster start no longer cascades through the server suite**:
  - The server module shares one JVM under `mvn verify`; when a node couldn't join, the teardown NPE'd on a null client Vertx **before** stopping the nodes, so their Ignite instances stayed registered and every later class failed with "Ignite instance already started" (the documented `FileFailoverTest` cascade).
  - Teardowns now guard the client Vertx and each node stop (73 guards across 30 suites), so a failed start still tears down what did start. `mvn verify` keeps one JVM (per-class forks cost ~3.5 min more); `scripts/test-all.sh` still forks per class.
  - `lasttest.txt` (written by `scripts/test-all.sh`) is now git-ignored.


- **The engine conformance suite runs again (it silently hadn't been)**:
  - `runtime-javet` and `runtime-graaljs` scanned `c93:cluster93-runtime-js` in `<dependenciesToScan>`, but the groupId is `cluster93` (the rebrand renamed it), so surefire matched nothing: `runtime-js`'s tests never ran on either engine and `mvn verify` was green without them.
  - With the groupId corrected the suite runs on both engines — **137 tests each, 0 failures** — so nothing had rotted. `TESTING.md` §3.3 now records where it lives, the count to expect, and the trap.


- **`c93 node install` refuses a second install of the other kind (D-140)**:
  - A user node and a system node both default to the same ports, so installing both would leave them fighting over 9393/9394. `install` now detects the other kind's `node.env` (either direction) and refuses, naming the file and how to remove it; `--force` installs both deliberately (they then need different ports).


- **Benchmarks de-emphasised (they are indicative, not a claim)**:
  - `docs/benchmarks.md` — and the harness that generates it — now open with "Indicative, not a benchmark claim … not maintained", the engine suite's README says the same about its runs, and the README index lists the page as indicative rather than as performance documentation.
  - The harness stays (`benchmarks/run.sh cluster|engines|all`): the numbers are a rough sense of the platform's shape, not a guarantee.


- **`ctx.serve` answers 500 for an invalid status instead of hanging (D-139)**:
  - `serve.js` mapped a handler's `{status, headers, body}` with `Number(value.status ?? 200)`, so a guest writing `{status: 'ok', …}` produced `NaN` and the exchange never completed — the client waited until it timed out, with nothing logged. A status that isn't an integer in `100..599` now answers **500** with the handler's body and headers still sent; only an absent status defaults to 200.
  - Found while fixing `examples/js/07-microservice.js`, which had exactly that mistake and had never been caught (the example runner skipped it). Covered by `HostBridgeTest.servedResponsesAreNormalised` (`/bad-status`).


- **One benchmark entrypoint: `benchmarks/run.sh [cluster|engines|all]`**:
  - The three-node Docker harness moves to `benchmarks/cluster/` (and follows the new host ports); `benchmarks/engines/` stays self-contained; the new dispatcher runs either or both.
  - The four committed runs under `benchmarks/engines/results/` are untracked and the directory is git-ignored (the canonical numbers live in `docs/benchmarks.md`), and the local `__pycache__/` and `bin/` residue is gone.


- **One update surface: `c93 update [--cli|--node]` (D-138)**:
  - `c93 update` is the front door: `--cli` replaces the CLI (the native binary, or — on the JVM — the `cluster93-cli.jar` it was launched from), `--node` updates the node (an installed node is re-fetched from the signed release and restarted; a checkout rebuilds from source), and **with neither flag it does both**. `--version TAG` pins a release; `--to FILE` names a CLI target.
  - `c93 upgrade` and `c93 node update` stay as **hidden aliases**, so existing scripts and docs keep working; the CLI's update hints now say `c93 update --node`.
  - `docs/guides/releasing.md` gains the platform matrix (Linux x64/arm64, macOS arm64/x64, Windows x64; the CLI ships in the same bundle as the server) and `release-local.sh`'s header points at it.
  - **Why:** installing had one path (`c93 node install`) but updating had three names and two behaviours; one command with explicit halves removes the guesswork.


- **`examples/js/` by language, with a runner that also exercises the service example**:
  - The seven examples move to `examples/js/` (their header paths updated); new `examples/README.md` indexes them by language and notes Python is planned (Phase 9).
  - `scripts/local.sh dev test-examples` is now directory-driven (`examples/js/*.js`), so adding an example needs no script edit; a file marked `// c93: service` is deployed as a service (waited for on the ingress, then removed) instead of being run as a job.
  - **Fixed:** `07-microservice.js` was broken and had never been caught because the runner skipped it — it returned `{ body, status: 'ok', … }`, so `toResponse` computed `Number('ok')` = `NaN` and the request hung. It now returns `{ status: 200, body: {…} }`, the `{status, headers, body}` shape the docs describe.


- **Docs reconciled with reality**:
  - README: the status block no longer claims "Phase 10 Complete" (there is no Phase 10); it now reads Phases 1–5 complete, Phase 6 in progress, Phase 11 active. The roadmap bullets match `ROADMAP.md`, the broken links into the git-excluded `ideas/` were repointed at tracked design docs, and the install section gained the platform matrix (Linux/macOS native, Windows portable jar, workers Linux-only).
  - `ROADMAP.md`: the diagram no longer marks Phase 5 ACTIVE (complete; Phase 6 is in progress) and a Phase 11 (Consolidation & Release Readiness) section records W1/W5 done and W2/W3/W4/W6 pending.
  - `docs/design/{revival,phase-2,phase-4,phase-5}-plan.md` moved to `docs/design/history/`, with every relative and inbound link fixed; `DOCUMENTATION.md`'s taxonomy now covers `console/`, `benchmarks/`, `examples/`, `install/`, `scripts/`, `docs/guides/node.md`, and the history location.


- **One checkout orchestrator: `scripts/local.sh [master|dev] <verb>`**:
  - `scripts/dev.sh` is gone: its verbs (`build`, `test-examples`, `start --data-dir`, `seed`, `stop`, `restart`, `status`) are the `dev` profile of `scripts/local.sh`, sharing one implementation of `cmd_start`/`cmd_stop`/`cmd_seed`/`ensure_console`/`provision_admin`/`print_setup_banner`/`json_string`/`mint_token`/`redeem_token`/`saved_operator_token`/`java_bin` with the `master` profile. A bare verb still means `master`, so `c93 node …` and the docs keep working.
  - The `master` profile keeps its `local.env` + `master-data`; the `dev` profile its constants + `dev-data`, so the two never share an Ignite baseline.
  - `scripts/compose-demo.yml` is gone: the demo's prebuilt-image pinning (`image: cluster93-demo:local`, `pull_policy: never`) moved into the root `docker-compose.yml`, so `scripts/demo.sh` uses the root file (+ `--profile observability`) and `docker compose up --build` still builds it. `scripts/demo.sh` also follows the new default ports (API `940x`, ingress `941x`).
  - **Found while verifying the demo:** the image ships no native workers and the shaded jar has no `WorkerMain`, so the default `process` mode could not start a worker in a container — the compose now sets `C93_WORKER_MODE: "embedded"` (a node installed with `c93 node install` ships `bin/worker-*` and still uses `process`).
  - **Found while verifying the profiles:** the seeded login now records its `tenant`, so `c93 run …` works straight after `seed` (it previously failed with "Specify a target tenant").
  - The `tools/` rename-refactor one-offs are deleted (git history keeps them); `scripts/migrate-home.sh` stays.
  - **Why:** two orchestrators duplicated ~13 functions and drifted; the demo needed a second compose file for one image override.


- **Module directories lose the `cluster93-` prefix**:
  - `cluster93-{tls,core,runtime-js,runtime-javet,runtime-graaljs,runner-protocol,worker,cluster-ignite,server,cli}` are now `{tls,core,runtime-js,runtime-javet,runtime-graaljs,runner-protocol,worker,cluster-ignite,server,cli}` at the repo root. **ArtifactIds, Maven coordinates and Java packages are unchanged** (`cluster93-core`, `cluster93.*`), so `c93.jar`, `cluster93-cli.jar` and every dependency declaration are untouched.
  - Every path reference was updated: the root `pom.xml` `<module>` entries, `Dockerfile`, `scripts/*.sh` (`-pl`, `*/target/…`), `.github/workflows/release.yml.disabled`, `.gitleaks.toml`, `benchmarks/`, the README module table, `TESTING.md`/`ROADMAP.md`/`CLAUDE.md`, and the docs — plus two runtime lookups in main code (`TenantWorkerPool`'s worker-target discovery and the CLI's dev-master `server/target/c93.jar`).
  - **Why:** the prefix added nothing at the repo root and made every path longer; keeping the artifactId prefix preserves the published coordinates.

- **New default ports: the `9393`–`9396` Cluster93 block (D-137)**:
  - `C93_HTTP_PORT` now defaults to **9393** (was 8080), `C93_INGRESS_PORT` to **9394** (was 8000), `C93_METRICS_PORT` to **9395** (was 9090) and `C93_INTERNAL_PORT` to **9396** (was 8090). The change runs through the server defaults, the CLI (`c93 node install` writes them into `node.env`), `scripts/local.sh`, the Docker image and `docker-compose.yml` (host ports `940x`/`941x`), the OpenAPI `servers` URL, and the Prometheus/Grafana stack.
  - **Why:** 8080 and 8000 collide with the usual local development servers, and 9090 is Prometheus's own port; one memorable block avoids all three clashes.
  - **Compatibility:** an existing node keeps its ports — a written `node.env`/`local.env` names them explicitly — so only fresh installs and unset environments pick up the new defaults. Anyone depending on the old defaults, or on the old Docker host ports (`8081`–`8083` / `8001`–`8003`), must set them explicitly or update their mappings (D-137).

## [2.0.0-alpha.2] - 2026-10-13

- **Initial Admin Setup Wizard**:
  - New public `GET /v1/auth/setup-status` reports `needsSetup` while the cluster has no operator account, and `POST /v1/auth/setup` creates the first operator user (validating the username and requiring a password), stores an scrypt password (D-133), and answers `201` with the new user and a freshly minted operator session token; a second call is refused `409` once an operator exists (`Identity.needsSetup`/`Identity.initialSetup`, `IdentityRoutes`, `RouteScopes`, `openapi.yaml`).
  - The console's sign-in card (`LoginView`) probes setup status on load and, when needed, presents an **Initial Setup** screen — username defaulting to `admin`, password and confirmation — that creates the administrator and signs in immediately (`api.getSetupStatus`/`api.setupAdmin`).
  - **Why:** a fresh cluster had no way to create its first operator from the web console; the admin token was the only path in.

- **Configurable Ingress Host & Port Preservation in Web Console (`AppsTab`)**:
  - `AppsTab` now detects and preserves non-standard origin ports (`window.location.port`) when constructing preview and canonical application URLs (e.g., preserving `:8000`), preventing connection errors to port 80.
  - Added configurable Ingress Host UI and local storage persistence (`c93_ingress_host`), allowing operators accessing via custom domains, non-standard reverse proxy ports, or tunnels to configure the ingress host directly from the Apps view.

- **CLI Web Console Version Auto-Detection & Admin Update Prompt**:
  - `c93 version` and `c93 local status` now automatically check the active web console deployment against the local source/repo HEAD commit.
  - Verification is gated strictly to users with administrator privileges (`/v1/whoami` returning `admin: true`), completely skipping reserved `system` tenant queries for regular tenant users.
  - When an admin user is connected and the deployed web console is behind the checkout HEAD, an update warning is presented offering `c93 local update` or `c93 apps deploy console`. Included in `--json` as `console.updateAvailable` (`VersionCommand`) and `consoleUpdateAvailable` (`LocalCommand`).

## [2.0.0-alpha.1] - 2026-10-13

- **Rebrand: Automately → Cluster93 (`c93`)**:
  - The platform is renamed from **Automately** to **Cluster93**, with the `c93` CLI. This repository continues the pre-2018 open-source **Automately** codebase — a clustered, distributed backend platform first published in 2014 and acquired before 2018. See [DEDICATION.md](DEDICATION.md) and the [archived original](https://web.archive.org/web/20160204012506/https://github.com/Automately).
  - Mechanical rename applied repo-wide: modules `automately-*` → `cluster93-*`; Java packages `automately.*` → `cluster93.*`; Maven groupId/artifactId `automately` → `cluster93`; env prefix `AUTOMATELY_*` → `C93_*`; home/config dirs `~/.automately` → `~/.cluster93` and `~/.config/automately` → `~/.config/cluster93`; app manifest `automately.json` → `cluster93.json`; built-in imports `automately:*` → `c93:*` (`Symbol.for('automately.*')` → `Symbol.for('cluster93.*')`); CLI/binary and server jar `automately` → `c93` (`c93.jar`, `c93.pid`); wire identifiers `X-Automately-*` / `Automately-Signature` / `Server: automately` → `X-Cluster93-*` / `Cluster93-Signature` / `Server: c93`; Ignite SQL schema `AUTOMATELY` → `CLUSTER93` and data region `automately` → `cluster93`.
  - Driven by a reviewable, dry-run-first toolset: `tools/refactor-rename.sh`, `tools/rename-map.tsv`, and `tools/rename-keep.txt` (historical/lineage text preserved via allowlist).
  - ⚠️ **Breaking, data compatibility:** the Ignite SQL schema and data region names changed, so durable data written by a pre-rename build is not read by the new build — existing clusters should start fresh or migrate.
  - ⚠️ **Breaking, scripting API:** built-in module imports now use the `c93:` scheme (`import { kv } from 'c93:kv'`).
  - ⚠️ **Breaking, token format:** the bearer-token prefix changed `atly_` → `c93_` (`TokenFormat.PREFIX`), and the node bundle/cert lines use `c93_nodebundle_` / `c93_nodecert_` (`NodeBundle`). Existing tokens are invalidated by design.
  - Repository: `git@github.com:TonyRice/cluster93.git` (release/install defaults point at `TonyRice/cluster93`).

- **Full Process-Mode Parity (`AUTOMATELY_WORKER_MODE=process`, D-132)**:
  - Streaming host APIs now work identically out-of-process: `ctx.serve`/`listen` (inbound ingress), `fetch` and `net`/`tls` egress, large-file reads (`files.open`) and `files.respond` are bridged over the worker multiplexer; bus/queue/job listeners arrive as `EVENT` frames and answer with `REPLY`.
  - The full execution context (package lock, `ctx` including carried steps, `process.env`, and the service flag) is serialized into `RUN` and restored in the worker (`Packages.toJson`/`fromJson`, `JobContext.toJson`/`fromJson`), fixing package imports, env and resumed-step memoization off-process.
  - Protocol robustness: the receiver now replenishes connection-level credit as well as stream credit, so a long-lived stream can't deadlock on the 4 MiB connection window; a worker EOF/transport error fails its in-flight run immediately (`Worker process died`); state commits run off the event loop; the worker `System.exit(0)`s on EOF (no zombies).
  - Server lifecycle: the worker pool drains each child's stderr (no 64 KiB pipe deadlock), inspects exit codes 137/139, and `RunningNode.stop()` closes the pool. The worker's own logging is pinned to stderr so it can't corrupt the stdout frame channel.
  - New verification suites: `StreamMultiplexerCreditTest`, `NodeWorkerChannelEofCrashTest`, `NodeWorkerChannelStateNonBlockingTest`, and `ProcessModeParityTest`.
- **Default Out-of-Process Worker Mode for Zero-Trust Security (`AUTOMATELY_WORKER_MODE=process`, D-131)**:
  - `ServerConfig` now defaults `AUTOMATELY_WORKER_MODE` to `process` (instead of `embedded`), isolating all tenant JavaScript execution inside dedicated, cgroups v2-bounded subprocesses.
  - Mitigates off-heap exhaustion attacks, native isolate crashes, and host memory exploitation by moving untrusted guest code completely out of the server's JVM process space.
  - `AUTOMATELY_WORKER_MODE=embedded` remains supported as an opt-in mode for low-overhead local development environments.
- **Per-Tenant Multi-Mode Worker Pool & RPC Authorization (`TenantWorkerPoolManager`, `NodeRpcAuthorizer`, D-130)**:
  - `TenantWorkerPoolManager`: per-tenant worker pool supervisor managing pools partitioned by tenant and worker mode profile (`AUTOMATELY_WORKER_BINARY_*`). Handles zero-trust scoping, warm worker pre-spawning, and cgroups v2 resource quotas.
  - `NodeRpcAuthorizer`: zero-trust security policy interface for `NodeWorkerChannel` evaluating all incoming worker RPC calls against tenant permissions and job contexts before dispatching to node stores.
  - Server bootstrap integration: `Automately` node bootstrap optionally initializes `WorkerProcessRuntime` when `AUTOMATELY_WORKER_MODE=process`.
  - Comprehensive integration test: `WorkerEngineIntegrationTest#testFullCapabilitiesOverWorkerProtocol` verifying guest JS streaming file creation (`files.write`), KV persistence (`kv.set`/`kv.get`), and RPC authorization rejection (`jobs.get` -> `FORBIDDEN`).
- **End-to-End Worker Engine Integration Suite (`WorkerEngineIntegrationTest`)**:
  - Full-stack integration test standing up Netty Unix Domain Socket server/client transports, `NodeWorkerChannel`, `WorkerDaemon`, and `RemoteHostApis` executing real guest JavaScript.
  - Verifies both **Javet** (V8 isolates) and **GraalJS** (polyglot isolates) runners invoking cluster-backed host APIs (`kv.set`, `kv.get`), emitting durable checkpoints (`ctx.saveCheckpoint`), and completing execution roundtrips.
- **Stream Multiplexing, Warm Tenant Worker Pool Supervisor, and Unix Domain Socket Transport (D-129)**:
  - `StreamMultiplexer`: bidirectional streaming channel multiplexer (`OPEN`, `DATA`, `END`, `RESET`) with chunk slicing (64 KiB), credit-based backpressure (`CreditManager`), and automatic replenish frames (`CREDIT`) upon data consumption.
  - `TenantWorkerPool`: maintains a pool of warm, pre-spawned worker processes in `UNBOUND` state, binding them on demand via `BIND` to eliminate cold-start overhead, with configurable reuse policies (`FRESH_PER_JOB` and `REUSE_SAME_TENANT`).
  - `CgroupMemoryLimiter`: applies Linux cgroups v2 memory bounds (`MemoryMax`, `MemoryHigh`) to worker subprocesses, detects OOM kills via `memory.events`, and provides graceful fallback in unprivileged environments.
  - `UdsServerTransport` & `UdsClientTransport`: non-blocking Netty Unix domain socket transport (`SocketAddress.domainSocketAddress`), removing virtual thread pipe overhead for container and local worker IPC.
  - `WorkerMain ipc --socket`: worker daemon connects directly over Unix domain sockets in addition to stdio.
- **`automately-runner-protocol` module & IPC binary infrastructure (D-128)**:
  - Length-prefixed binary frame encoder/decoder (`Frame`, `FrameType`, `FrameCodec`) with 12-byte header, split JSON metadata, and raw contiguous byte buffers up to 1 MiB.
  - HTTP/2-style flow-control credit windows (`CreditManager`, 256 KiB per stream, 4 MiB global connection limit).
  - Complete strongly-typed message models: `HelloMessage`, `WelcomeMessage`, `BindMessage`, `RunMessage`, `CallMessage`, `ResultMessage`, `ErrorMessage`, `CreditMessage`, `LogMessage`, `StateMessage`.
  - Worker-side `RemoteHostApis` proxy delegating authority operations (`kv.*`, `bus.*`, `queue.*`, `files.*`, `fs.*`, `secrets.*`, `jobs.*`, `dns.*`) over IPC while keeping pure computation (`crypto`, `zlib`) local without roundtrips.
  - Node-side `NodeWorkerChannel` and `NodeRpcDispatcher` enforcing zero-trust allowlists, automatic tenant injection, and 1,024 in-flight call quotas.
  - `StreamBridge` adapting standard Java `InputStream`/`OutputStream` pipes into Vert.x `ReadStream<Buffer>`/`WriteStream<Buffer>`.
  - `WorkerProcessRuntime` implementing `ScriptRuntime` to run jobs in out-of-process worker subprocesses over stdio.
  - `WorkerMain ipc`: added IPC daemon mode to `automately-worker`.
- **Codebase Architecture Map & Javadoc Setup**:
  - Authored [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) documenting core subsystems, file locations, `HostApis` authority partitioning, and runtime mechanics.
  - Authored [`docs/guides/agent-context-and-javadocs.md`](docs/guides/agent-context-and-javadocs.md) outlining token-density rules, anti-hallucination Javadoc tags (`@throws`, `@param`, `@return`, `@see`), skeleton/header extraction, and on-demand LSP inspection.
  - Configured `maven-javadoc-plugin` aggregate documentation (`./mvnw javadoc:aggregate`) with clean doclint rules, resolving historical broken tags in `StateSink`, `IgniteFileStore`, and `Builtins`.
- **`docs/design/worker-node-protocol.md`**: dedicated normative specification for the In-Cluster Worker ↔ Node IPC protocol (D-127). Defines length-prefixed binary framing (1 MiB max frame, split JSON and raw contiguous bytes), HTTP/2-style flow-control credit windows (256 KiB stream, 4 MiB connection), full RPC lifecycle (`HELLO`, `BIND`, `RUN`, `CALL`, `RESULT`, `CREDIT`, `STATE`, `STATS`), authority partitioning, and zero-trust security invariants. Distinguishes in-cluster workers (child processes, gVisor containers, Firecracker microVMs) from external `automately-link` devices.
- **`D-127` in `docs/DECISIONS.md`**: establishes operator-configurable engine selection (`AUTOMATELY_WORKER_ENGINE=graaljs|javet`). Both GraalJS (validated with 137/137 tests passing, stopping 7/9 memory attacks) and Javet (V8, 2–9× faster on object/JSON code) are retained as supported first-class engines behind the `JsEngine` SPI.
- **`benchmarks/engines/README.md`**: comprehensive guide documenting why the benchmark suite exists, its functional purpose, engine sandboxing and performance dimensions, and detailed usage instructions for building and running.
- **GraalVM SDK auto-detection and edition tracking in `benchmarks/engines/`**:
  - `build.sh` automatically detects GraalVM across environment variables (`GRAALVM_HOME`, `JAVA_HOME`), SDKMAN candidates, and system `PATH`. It identifies whether the compiler is **Oracle GraalVM** or **GraalVM Community (CE)** and writes `bin/build-metadata.json`.
  - `run.sh` records the detected GraalVM compiler edition and version into `machine.json`.
  - `summarize.py` surfaces the GraalVM edition in benchmark reports (`summary.md`).
  **Why:** to facilitate testing and comparison between GraalJS Community Edition and Oracle GraalVM (under GFTC) to determine if Oracle's enterprise JIT closes the object/JSON performance gap against V8.

### Removed
- GitHub Actions CI. **Why:** the repo is private and local `./mvnw verify` is the gate for now (D-017).

### Docs
- D-055: Tony approved GitHub Actions for the tag-only CLI release workflow, and public downloads come from the repository's GitHub Releases once it's public. The installers already default to that, so no code changed.
- The phase 4 plan's milestone 3 now includes the one-line install (`curl … | sh` and `irm … | iex`), native builds for Windows, Linux (x64 static, arm64) and macOS (arm64, x64), checksums, and a tag-only release workflow (D-049, confirming D-048). **Why:** Tony wants the CLI "super easy to install via curl" on all three systems. Two questions are open: GitHub Actions for the release builds, and where public downloads come from while the repo is private.
- `docs/design/phase-4-plan.md`: the phase 4 plan (tenants and scoped tokens, the OpenAPI contract and rate limits, the CLI, the MCP server), with D-044 to D-047 accepted and D-048 (the CLI as a native binary) proposed. **Why:** Tony chose the bootstrap, the contract-first approach and the MCP transport; the CLI question is still open.
- D-018: name and rights recorded. Automately was renamed Executory after the acquisition, and Tony holds the rights to the pre-acquisition OSS code.

### Added
- **`DockerClusterIT`**, a Testcontainers integration test that scripts the phase 2 Docker check. It runs three containers built from the jar `mvn package` produced, with production timings (1 s heartbeat). One scenario `SIGKILL`s the container running a resumable job mid-step and checks: attempt 2 runs on another node, steps finished before the kill aren't repeated, every invoice exists exactly once, and the SSE stream shows the `attempt` and `end` events. A second scenario `SIGKILL`s and immediately restarts a node under a `fail` job; it must end `ORPHANED` with only `charge` recorded. Run by Failsafe in `./mvnw verify` and in CI; skipped without Docker, or with `-DskipITs`. **Why:** the check was manual, and a real `SIGKILL` is the one failure the in-JVM tests can't produce.
- **Dockerfile stages:** `runtime` (shared), `local-jar` (copies the already packaged jar, used by the test), `build`, and `release` (the default). **Why:** the test exercises the exact artifact the build produced, without a second Maven build inside Docker.
- `.gitattributes`: `mvnw` keeps LF and `mvnw.cmd` keeps CRLF.
- `docs/design/revival-plan.md`: the original approved revival plan (what Automately was, its positioning, target architecture, the 8 weak spots and their fixes, the phases), copied unchanged with a status block on top. **Why:** it only existed outside the repo, and the reasoning behind the project should live with the code.
- `NOTES.md`: ideas to keep but not act on yet. The first is remote workers, such as a laptop, offering a sandboxed browser capability. It's modelled on the original `webdriver` module.
- `docs/design/orphaned-jobs.md`: accepted design for jobs whose node dies. Covers attempts, the `onOrphan` policy (`fail` by default), `ctx.step` and checkpoints for resuming without repeating work, and epoch fencing against zombie attempts. **Why:** restarting orphaned jobs blindly would repeat side effects.
- `docs/scripting/job-context.md`: the script-facing API for attempts, `ctx.step`, `ctx.saveCheckpoint`, policies and limits, with complete resumable, retry and default (`fail`) example scripts. **Why:** Tony wanted the whole resume flow laid out so it's clear end to end before it's built.
- `docs/design/phase-2-plan.md`: the phase 2 implementation plan. It adds node instance ids, per-attempt state, compare-and-set transitions and a lock-free reaper (D-009 to D-012). **Why:** planning against the real code showed that a restarted node would hide its own orphans, and that state needs fencing.
- Authorship: Adam & Lynn Incorporated (tony@adamnlynn.com) recorded in the POM (`organization`, `developers`), the README, and the repository's git config.
- This changelog and `docs/DECISIONS.md`. **Why:** every change and its reasoning should be traceable.

## V8 and GraalJS compared in native-image workers (2026-10-10)

### Added
- **`automately-worker`: the tenant worker's seed, built as a GraalVM native image** (D-126, step 3).
  - **Two builds:** `worker-javet` and `worker-graaljs`, one engine each, with in-process host APIs.
  - **Commands:** `bench`, `idle`, `hostile`, `run` and `start`.

  **Why:** tenants will run in worker processes, and the engine is chosen by measuring both where they'll run.
- **`benchmarks/engines/`: the comparison, rerunnable.**
  - **`build.sh`:** builds the workers one at a time under a kernel memory cap. GraalJS's image needs a 7 GB builder heap.
  - **`run.sh`:** runs 18 workloads (Octane's Richards and DeltaBlue among them), memory with idle services, and the hostile memory tests. Every worker runs under a cap, and the CPU's temperature and clock are logged.
  - **`summarize.py`:** writes the tables.
  - **The results** are in `results/2026-10-10-1054/` and the design doc's §8.

  **What it found:**
  - **Security:** GraalJS stopped 7 of 9 memory attacks at the run and kept 1 in bounded memory. On V8, 7 of 9 got past the heap limit, and the kernel had to kill the worker.
  - **Speed:** V8 is 2–9× faster on object-heavy and JSON-heavy code, the two are level on numeric code, and GraalJS is 3× faster on regular expressions.
  - **Cold runs:** GraalJS's first runs are slower (median 6.6×).
  - **Memory:** GraalJS uses more (5 MB against 3 MB per idle service, plus about 285 MB for its isolate).
- **`NativeImageMetadataTest`:** fails if a class with host functions is missing from the workers' native-image metadata.

### Changed
- **Buffer's hex and base64 are built in typed arrays:** buffer-codec went from about 300 ms to 73 ms on V8, and from 765+ ms to 74 ms in GraalJS's isolate. **Why:** they were built a character at a time. A Java codec was measured too, but decoding in Java was slower on every engine, and encoding in Java was slower in GraalJS's isolate (NOTES.md).
- **GraalJS, isolated: bytes reach scripts as a Latin-1 string,** filled into a `Uint8Array` inside the isolate. **Why:** a host buffer crosses the isolate's boundary one byte at a time; gzip of 1 MB went from 2.8 s to 0.14 s.
- **GraalJS's dependency is now `org.graalvm.polyglot:js`.** **Why:** `js-community` became a deprecated alias for it in 25.x; it's open source (MIT/UPL), so nothing else changes.

### Fixed
- **`Buffer.from(text, 'hex')` stops at the first pair that isn't two hex digits, as Node does.** Before, `'1g'`, `' 1'` and `'+1'` each decoded to the byte `01`, through `parseInt`'s leniency.
- **The native workers register every host function.** The tracing agent had recorded only the ones its run called (for zlib, only `process`), so streaming zlib would have failed in a worker. A hand-written metadata file now registers all of them, and all the runtime's JavaScript.

### Docs
- **`docs/design/engine-neutral-runtime.md`:**
  - **§8:** the results, and the trade-offs between V8 and GraalJS.
  - **§3:** the isolate's measured costs (the boundary, the heap limit's headroom, cold code), what building the workers takes, and the `/tmp` leak a killed worker leaves.
- **NOTES.md:**
  - Oracle GraalVM's compiler, with a review of its licence (GFTC: hosted use is fine, and paid redistribution isn't)
  - engine-specific host fast paths
  - GraalJS's cold start
  - TextEncoder/TextDecoder

## Embedded GraalJS beside Javet (2026-10-10)

### Added
- **`automately-runtime-graaljs`: embedded GraalJS as a second engine for the runtime** (D-126, step 2).
  - **It passes the same conformance suite as V8, 136 of 136.**
  - **By default** each engine runs its guests in a polyglot isolate of its own, with a heap limit per run.
  - **Measured in its spike:** the routes that escape V8's limit are bounded there (a heap bomb, buffers, resizable buffers, `Intl` churn).
  - **Modules** are served with their imports already resolved by the runtime's rules, since GraalJS's loader never says who imports.
  - **Not yet:** the server doesn't use it (Tony: several engines are for later); the comparison in native-image workers comes next.

  **Why:** Tony's concern is engine-level security, and GraalJS is a memory-safe engine. It's measured against Javet before anything is chosen.
- **`Imports.rewriteStaticSpecifiers`:** rewrites every static import's and re-export's specifier in place, keeping lines. GraalJS's adapter uses it.
- **`EngineUnderTest.reportsRejectionsHandledLater`:** the conformance suite now states this one engine difference. GraalJS doesn't report a rejection handled in a later turn, so Node's `rejectionHandled` doesn't fire there; the rest of D-110 holds on both.

## The runtime, engine-neutral; Javet as its first engine (2026-10-09)

### Changed
- **The JavaScript runtime moved to a new engine-neutral module, `automately-runtime-js`** (D-126, step 1).
  - **What moved:** the bindings, `HostBridge`, the event loop, the run's orchestration (`ScriptRunner`), the module resolution rules (`ModuleSources`), the prelude and the Node and web polyfills.
  - **The SPI they're written against:** `JsEngine`, `JsContext`, `JsArg`, `@HostFunction`.
  - **`automately-runtime-javet` is now V8's adapter:** isolates, the heap limit, V8's code caches, ICU data, and binding the host functions as direct V8 callbacks. `JavetScriptRuntime` keeps its name and constructor, so the server is unchanged.
  - **The runtime's tests became the conformance suite:** `automately-runtime-js` packages them, and each engine module runs them on its own engine, Javet through `JavetUnderTest`. `JavetScriptRuntimeTest` is now `ScriptRuntimeTest`.

  **Why:** so a second engine (embedded GraalJS) can be added and compared without writing the bindings twice. The design is in `docs/design/engine-neutral-runtime.md`.
- **No change in behaviour:**
  - **Every conformance test passes on Javet,** per class exactly as before (136), and the real-npm comparisons still match Node (41).
  - **A trivial job's start-up:** 4.26 ms through the new layer against 4.53 ms for the old code, both measured on the same machine the same day.

## Engine-neutral runtime: design, and memory outside the heap limit (2026-10-09)

### Security
- **Corrected: the heap limit doesn't bound an isolate's memory.** `AUTOMATELY_MAX_HEAP_MB` caps V8's heap only. ArrayBuffers (one 8 GB), resizable and growable buffers, WebAssembly memory, ICU objects and copies into Java are outside it, so one script can exhaust a node's memory. `SECURITY.md` and the README said otherwise and now say so. **Why:** found in a security review after phase B; the measurements are in the design below. Fixing it is the work that follows.

### Docs
- **`docs/design/engine-neutral-runtime.md` (D-126, proposed).**
  - **The plan:** an engine-neutral layer under the bindings, an embedded GraalJS adapter beside Javet, a comparison of both inside GraalVM native-image tenant workers, and the worker↔node protocol designed in full.
  - **Also recorded:**
    - Javet running in a native image (0.7 s to the first script, about 50 MB of its own memory)
    - kernel memory caps working on V8 processes without root
    - a guard on `maxByteLength`, tested against bypasses
    - WebAssembly's page cap

  **Why:** Tony's concern is engine-level security, and tenants will run in worker processes, so the engine should be replaceable and chosen by measurement.

## WebSockets, as Node's; phase B complete (2026-10-09)

### Added
- **WebSockets in services** (D-125):
  - **Servers:** Node's `http.Server` emits `upgrade` with a real `net.Socket`, and `shouldUpgradeCallback` is honoured.
  - **The ingress:** it tunnels the connection once the app answers 101; any other answer is relayed as a response.
  - **The ws library's server** answers through the ingress exactly as it does under real Node.

  **Why:** phase B's step 7.
- **WebSocket clients:**
  - **`node:http`:** a request that asks to upgrade, or brings its own `createConnection`, is written as HTTP/1.1 over a socket of the job's own, as Node's client does.
  - **The global `WebSocket`, `CloseEvent` and `ErrorEvent`:** undici's behaviour.
  - **Tested:** the ws library's client and the global both match real Node.
- **New settings:** `AUTOMATELY_SERVICE_MAX_WEBSOCKETS` (1,000 upgraded connections per run) and `AUTOMATELY_INGRESS_WEBSOCKET_IDLE_TIMEOUT_MS` (10 minutes).
- **`zlib`:** a real `flush()` (a sync flush, as permessage-deflate needs) and `windowBits`.
- **`Buffer`:** integers of 1 to 6 bytes (`readUIntBE`, `writeUIntBE` and the rest).
- **`TextDecoder`:** the `fatal` option.

### Changed
- **`URL` errors are Node's:** "Invalid URL" with `code`, `input` and `base`.
- **Optional native packages (`bufferutil`):** a `require()` of one the bundler left out throws `MODULE_NOT_FOUND`, as when it isn't installed, so code falls back.
- **`createConnection` is accepted** on `http.request`: the socket it gives is already gated.

### Fixed
- **A service stopped answering after 64 requests in its run.** The internal endpoint's count of requests in flight never went down, because the exchange replaced its end and close handlers (Vert.x keeps one of each). Every request after the 64th got a 429. Exchanges now count through a single release hook.

## The WHATWG globals, as Node's (2026-10-09)

### Added
- **The WHATWG globals, matching Node** (D-124):
  - `Blob` and `File` (also `buffer`'s)
  - `Headers`, `Request`, `Response` and `FormData`
  - the WHATWG streams, also as `node:stream/web`
  - `TextEncoderStream`, `TextDecoderStream`, `CompressionStream` and `DecompressionStream`
  - `MessageChannel`, `MessagePort`, `MessageEvent` and `BroadcastChannel`
  - Node's `fromWeb`/`toWeb` on `Readable`, `Writable` and `Duplex`, plus `buffer.isUtf8`/`isAscii`

  Each group loads on first use, from the code cache, so a job that never touches them pays nothing. **Why:** Koa 3 checks bodies against `Blob`, `ReadableStream` and `Response`, and Tony chose to build the globals fully (2026-10-09).
- **Koa 3 runs as a service and answers as it does under real Node,** request by request, alongside Express 5 and Fastify 5.

### Changed
- **The global `fetch` is Node's** (D-124): WHATWG classes, redirects followed, gzip and deflate decoded, Node's default headers and errors, through the egress gate as before. **Why:** our polyfills match Node, and code written for Node expects it. The host's convenience fetch (JSON bodies, `timeoutMs`, no redirects) is now `import { fetch } from 'automately:fetch'`. Scripts using its JSON bodies or `timeoutMs` on the global need that import.
- **The global `fetch` can be replaced** by a script, as under Node.

## Node's HTTP servers in services, by hostname and port (2026-10-09)

### Added
- **`http.createServer` and `https.createServer` in services, as Node's** (D-123). Nothing opens a port on the cluster:
  - **`listen(port)`** registers it, and the ingress routes `<service>--<tenant>--<port>` to it. The service's host goes to the first server.
  - **Requests:** the client's own headers, address and HTTP version.
  - **Responses:** headers with repeats, the reason phrase, length or chunking as Node, trailers, and a client leaving.
  - **Plain apps:** a plain `app.js` with no default export runs as a service, and a stop emits `SIGTERM`.

  **Why:** phase B's step 6; Express, Koa and Fastify run on it.
- **Express 5 and Fastify 5 run as services and answer as they do under real Node** (checked request by request in `RealNpmPackagesTest`). Koa 3 follows with the WHATWG globals. Stand-ins for `worker_threads`, `module` and `v8`, so libraries that read them at load (pino, Koa) load.
- **A service's `ports`** (path prefixes, ports kept off the network), domains pointing at a port, `routes` in the API, `automately services routes` and `domains add --port`.
- **Node's `Timeout` objects** from `setTimeout`/`setInterval`, **`async_hooks`** (`AsyncResource`) and **`perf_hooks`**.

### Fixed
- **The ingress** now passes `Trailer` headers and trailers, and the app's reason phrase. It answers `Expect: 100-continue` at once.
- **`dns.lookup('localhost')`** answers loopback even without the development switch. **Why:** Fastify looks it up to listen; it reveals nothing (RFC 6761).

## `dns` as Node's, filtered through the egress gate (2026-10-09)

### Added
- **`dns` and `dns/promises`, as Node 26's** (D-122):
  - **`lookup`** through the operating system, in its order.
  - **The queries:** every `resolve*` (CAA, NAPTR, SOA, TLSA and ANY included, with real TTLs) and `reverse`, through DNS servers as c-ares asks them.
  - **`lookupService`,** and **`Resolver`** with `cancel`.

  Answers, errors and validation match real Node. **Why:** phase B's `dns` step; `mongodb+srv://` and other libraries need it.
- **Filtering:** a script sees only what its tenant could reach. Internal names answer `ENOTFOUND`, addresses it couldn't connect to are left out, and so are records naming internal hosts. **Why:** `dns` mustn't map the cluster's private network (§8.2).
- **`setServers` works:** a script's DNS servers pass the egress gate like any connection, so a public resolver is fine and a private one needs a rule. **Why:** Tony, so scripts can use public resolvers, as in Node.
- **`AUTOMATELY_EGRESS_DNS`** (servers that answer scripts instead of the system's, for split-horizon DNS) and **`AUTOMATELY_EGRESS_INTERNAL_DOMAINS`** (more internal zones).

### Changed
- **The egress gate resolves names through the same resolver as `dns`.** **Why:** so the two can't disagree, and so `AUTOMATELY_EGRESS_DNS` covers connections too.

## The event loop runs Node's phases; HTTP/2 flow control matched exactly (2026-10-09)

### Changed
- **The isolate's event loop runs Node's phases** (D-121):
  - **The order:** I/O callbacks, then `setImmediate`'s check phase, then timers.
  - **Microtasks:** they now run after every callback, not after a batch of them.
  - **`setImmediate`:** it returns an `Immediate`, and immediates queued during the check phase wait for the next turn.

  **Why:** `setImmediate` was a 1ms timer, and orderings Node guarantees didn't hold. A new fixture checks them against Node.

### Fixed
- **HTTP/2 flow control is now exact, and the session's receive window is no longer read live** (D-121):
  - **The windows:** the connection's WINDOW_UPDATE goes before the handlers of the frame that brings it to half, and the stream's after them.
  - **Network reads:** they reach the script one turn apart, as Node handles them.

  **Why:** the session's values raced with the script's events. Under a paced server, where Node is deterministic, the isolate now matches it frame by frame.

## `http2` stream state worked out as Node's nghttp2 holds it (2026-10-09)

### Fixed
- **`stream.state` follows what the stream wrote and what the script saw, not the network's state at that moment** (D-120). The rules:
  - IDLE until its HEADERS are written on the next turn, and half-closed local once END_STREAM is
  - the peer's END_STREAM applied after the handlers that showed it
  - the window refilled as nghttp2 refills it
  - `ready` and `id` given as the stream is submitted, and answers handed over on a turn after the write they answer

  **Why:** D-118's live read could be ahead of the event being handled, and under load it showed a closed stream where Node shows half-closed local. Matches real Node at every point in a stream's life.
- **A stream's host side was let go when its response ended,** so a later `end()` failed while the request was still open. **Why:** a server may answer before the request's body ends.
- **`end()` on a TCP socket the peer had already closed failed** with an uncaught `ClosedChannelException`. Node treats that as done, and so does the host now. **Why:** pg's `end()` races postgres closing the connection, and one full run failed on it.

## `http2` session's outbound queue as Node's (2026-10-09)

### Fixed
- **`session.state.outboundQueueSize` is now nghttp2's queue, as Node reports it** (D-119): control frames submitted this turn and not yet written. The isolate keeps the queue and empties it on the next turn, as Node does. **Why:** it was Netty's unflushed writes, 0 where Node shows 1 or more. Only the WINDOW_UPDATEs during `data`, whose count depends on TCP even in Node, aren't counted.
- **`stream.state` during `response` under CPU load** could report a closed stream (7) where Node shows 5. **Why:** Netty had closed it but not let it go yet; the frame's snapshot answers now.

## HTTP/2 as Node 26 has it: trailers, state, priorities, real flags (2026-10-09)

### Added
- **Request trailers in `http2`** (D-118): `waitForTrailers`, `wantTrailers` and `sendTrailers`, with Node's errors and rules.
- **`session.state` and `stream.state`,** as Node's, from the connection's flow control and HPACK tables.
- **`process.emitWarning` as Node's:** the `warning` event, codes and details, Node's log line, `noDeprecation` and `throwDeprecation`. `util.deprecate` takes a code and uses it.
- **Why:** D-117 left these out, and Tony wants the polyfills to match Node.

### Changed
- **`http2` priorities as Node 26:** `priority()` and a request's `weight` do nothing and emit DEP0194 once. **Why:** Node 26 deprecated priority signalling (RFC 9113).

### Fixed
- **`http2` flags** on `response` and `trailers` are now the HEADERS frames' real flags. **Why:** D-117 always reported PRIORITY and guessed END_STREAM from the status, so they differed from Node against a Node server.
- **TLS sockets offered the ALPN protocols of the first TLS connection the node made, not their own.**
  - **The cause:** Vert.x keeps one TLS context per client certificate and trust options, and every connection shared its single trust-all options.
  - **The fix:** each ALPN list now has its own.
  - **Why:** in a full test run, got's `http2: true` negotiated HTTP/1.1 after an earlier socket had asked for that.
- **`http.request` takes its protocol from its agent or `_defaultAgent`, as Node's does,** so `https:` through `http.request` with an https agent works. **Why:** http2-wrapper falls back to HTTP/1.1 that way, and ours refused it.

## Node's `http2` client; got with `http2: true` (2026-10-09)

### Added
- **`http2.connect` and its sessions and streams, as Node's** (D-117), on the host's HTTP/2 connections:
  - **Connecting:** over TLS with ALPN, verified as `tls` sockets are, or h2c over `http:`.
  - **Streams:** parallel, with trailers, resets, ping, settings and goaway, and Node's headers, flags and errors.
  - **Limits:** a session counts as a socket, a stream as one of the script's 16 requests.
  - **No servers.**
  - **Why:** phase B's HTTP/2 step; got's `http2: true` and other HTTP/2 clients need it.
- **got with `http2: true`** gives real Node's results. http2-wrapper's own TLS socket is accepted, and replaced by the host's connection.

### Fixed
- **HTTP/2 calls run on the connection's event loop.** Under CPU load, a ping after a reset went unanswered.

## `net` and `tls` sockets; pg, mysql2, ioredis, MongoDB and nodemailer run (2026-10-09)

### Added
- **Outbound `net` and `tls` sockets, as Node's** (D-116):
  - **Connecting:** `net.connect`, `tls.connect`, and upgrading a connected socket with `tls.connect({ socket })`.
  - **Behaviour:** half-close, idle timeouts, and Node's events and errors.
  - **TLS checks as Node does them:** the chain against the trust store or `ca`, then the host name with `tls.checkServerIdentity` or the script's own check.
  - **`tls.createSecureContext` and `tls.rootCertificates`.**
  - **Gated and limited:** every socket goes through the egress rules. The cluster policy gains `limits`: 120 new connections a minute and 256 sockets per tenant on each node, and 16 per script.
  - **Why:** phase B's socket step, so database and mail drivers run.
- **Real drivers give real Node's results against real servers:** pg (including TLS through Postgres's SSLRequest), mysql2, ioredis, MongoDB and nodemailer (STARTTLS). They run in containers when `AUTOMATELY_TEST_REAL_SERVICES` is set.
- **`util/types` as a module,** and **`child_process`'s names** (starting a process throws), which pg, nodemailer and mongodb import.
- **`automately egress`** shows the socket limits.

### Fixed
- **`emitter.once()`** now goes through `this.on()`, as Node's does. A stream with only a `once('data')` listener used to stall.
- **The bundler** resolves `require()` with packages' `require` condition. It used to get the ES-module file, so pg's pool class came out as an object.
- **`Buffer`:**
  - **Statics and prototype methods are enumerable,** as Node's are. safer-buffer and mysql2 copy them with `for…in`.
  - **`copy()` clamps as Node's does,** instead of throwing when the target is too short.
- **The real-npm test** gives up on a fixture that never lets Node exit, after 2 minutes, instead of hanging.

## got runs in the isolate; `http`'s socket reports the real connection (2026-10-08)

### Added
- **got 14 works over HTTP/1.1** (D-114) and gives real Node's results, as axios and node-fetch do. **Why:** phase B's next step.
- **`req.socket` describes the connection the host made:**
  - **events:** `lookup`, `connect`, `secureConnect` and `ready` in Node's order
  - **addresses:** the real ones, and `reusedSocket` for pooled connections
  - **TLS:** `authorized`/`authorizationError`, `getPeerCertificate(detailed)` in Node's shape, `getProtocol()` and `getCipher()`

  got's timings come from these.
- **`node:diagnostics_channel`,** complete: channels and tracing channels.
- **`events.on`** (the async iterator), and `events.once` with `signal`.
- **`node:dns` and `dns/promises`:** Node's names and constants. Lookups fail with `ERR_NOT_SUPPORTED` until phase B's dns step.

### Changed
- **The isolate reports Node `v26.8.1`** (D-115, replacing D-102), the release its stand-ins are checked against. got needs it to load, and jsonwebtoken's PS256 now works. `RealNpmPackagesTest` fails if run with another release.
- **`new net.Socket()` and `new tls.TLSSocket(stream)` construct unconnected sockets,** as in Node, instead of throwing. Connecting still waits for phase B's socket step.
- **Refused connections and unknown hosts** have Node's messages and fields (`connect ECONNREFUSED 127.0.0.1:9`).

### Fixed
- **`ECDH.getPrivateKey()` kept leading zero bytes** that Node drops, so one generated key in 256 differed from Node's. This made the webcrypto fixture flaky.

## Operators configure the address spaces scripts may reach (2026-10-08)

### Added
- **The cluster's egress policy** (D-111, D-113), `GET`/`PUT /v1/egress/cluster-policy` and `automately egress cluster`.
  - **What it holds:** allow and deny rules for every tenant, named address sets, and the posture: `public` (the default) or `allow-list`, where nothing is reachable unless a rule allows it.
  - **Seeding:** `AUTOMATELY_EGRESS_POLICY_FILE` seeds it at the cluster's first start.
  - **Why:** Tony: "make it configurable what address spaces we allow and disallow".
- **Tenant policies gain deny rules and a posture of their own** (`automately tenants egress --deny … --posture …`).
- **What a rule can target:**
  - CIDRs, host names, `*.suffix`
  - the built-in sets `@private`, `@loopback`, `@link-local`, `@cgnat`, `@reserved` and `@public`, or the operator's own
  - anything (`*`)
  - on ports, including `@smtp`, and protocols (`http`, `tcp`, `dns`), with a note shown to tenants
- **A new cluster's policy refuses mail ports (25, 465, 587) to raw sockets,** ready for B5.

### Changed
- **The order:** cluster nodes are always refused, then cluster denies, tenant denies, tenant allows, cluster allows, and the posture.
- **Unchanged when nothing is set:** without any policy, scripts reach exactly what they did before. A test holds the built-in sets to the old check.
- **`GET /v1/egress-policy`** shows each step in the order it's applied, with why, the cluster's rules and the tenant's, and the posture. It replaces `defaults` and `allow`.
- **A refusal by a rule** names the rule and its note in the script's error.
- **Speed:** nodes cache both policies, and a change is announced on the event bus so every node drops its copy at once.

### Docs
- **The README's roadmap is current again:**
  - phase A done
  - phase B's progress and next steps
  - the Node runtime after phase B, then links
  - the stale S3 item gone (fixed in D-094)

  The build section shows `-DskipITs` as the everyday run, and the test count is 869 unit tests. **Why:** Tony asked that the README and its roadmap be kept current with every change.

## Phase B made concrete: configurable address spaces, and servers by hostname (2026-10-08)

### Docs
- **`docs/design/network-reach.md` now holds the whole remaining plan:**
  - configurable address spaces (§10, D-111)
  - servers reached by hostname only, with intercepted, remapped ports (§5, D-112)
  - got and real socket details (§11)
  - sockets (§7, now with no idle timeout)
  - the `http2` client on Vert.x (§12)
  - filtered `dns` (§6)
  - WebSockets (§13)
  - the order (§9)

  **Why:** Tony asked for a comprehensive plan, then for address spaces to be configurable and servers routed only by hostname before anything more is built. Raw TCP and UDP servers are left to untrusted nodes (links).
- **NOTES:** what comes after phase B: the shared execution layer and the gVisor Node containers (designed in D-084 to D-086, not built), then reviewing the links design.

## Unhandled promise rejections fail the job, as in Node (2026-10-08)

### Changed
- **A promise rejected with no handler fails the job** (D-110), as an unhandled rejection ends Node since v15. It's checked after every turn of the event loop, once microtasks have run, so a handler added in the same turn is in time and one added a turn later isn't, as in Node. A rejection in the turn that finishes the script still fails it. The error reads `Unhandled promise rejection: <the reason's stack>`. **Why:** a forgotten `await` or a floating promise used to vanish silently and let the job report success. Tony: nothing runs in production, so there's no grace period.
- **`process.on('unhandledRejection')` and `'rejectionHandled'` work,** as in Node: with an `unhandledRejection` listener the job goes on, and a throw in that listener fails it.
- **A promise returned by a timer callback is ignored, as in Node,** so its rejection is an ordinary unhandled rejection, which a listener can hear. Before, it was reported as an uncaught error in the timer callback. A synchronous throw in a timer callback still is.

### Tested
- `JavetScriptRuntimeTest`: rejections left alone, from an async timer callback, handled too late and in the script's last turn fail the job; one handled in the same turn or heard by a listener doesn't; a throwing listener fails it; and a module that fails to load is still reported as itself. The real-npm suite (30 fixtures) still matches Node.

## Node's `http` and `https` in the isolate; axios and node-fetch run (2026-10-08)

### Added
- **`node:http` and `node:https` as clients** (D-109): `request`, `get`, `ClientRequest`, `IncomingMessage`, `Agent`, `METHODS`, `STATUS_CODES` and the header validators, with request bodies that stream and responses built with Node's header rules. Every request goes through the egress gate (D-108) and shares `fetch`'s 16 requests per script. HTTPS verifies certificates, and accepts `ca`, client `cert`/`key`, and `rejectUnauthorized: false` (Tony's call; noted in the job's log). **Why:** phase B's step 2; most npm HTTP code is written against `http`.
- **axios and node-fetch** give real Node's results (`RealNpmPackagesTest`, against a local test server), as does a fixture for `node:http` itself: headers, streamed and fixed-length bodies, redirects, timeouts, aborts, hang-ups and events in Node's order.
- **`net`, `tls`, `http2` and `tty`, for their pure parts:** `net.isIP` and friends (Node's own patterns), `tls`'s defaults, all of `http2.constants`, and `tty.isatty()` (false). Connecting throws `ERR_NOT_SUPPORTED`. **Why:** axios and its proxy agent import them at load; sockets come in phase B5.
- **`fs.createReadStream` and `fs.createWriteStream`,** as Node streams over file handles (32 MB). fetch-blob imports them.
- **`NodeNetworkStandInsTest`** and **`StreamedRequestTest`**, in every build.

### Changed
- **A CommonJS `require()` of a Node module the isolate lacks throws when it runs,** as in Node, and the publish only warns. Before, it stopped the publish, which refused libraries that try an optional module and fall back (fetch-blob's `stream/web`, node-domexception's `worker_threads`). An `import` of one still stops the publish.
- **`process` reports itself as `[object process]`,** as Node's does. axios checks it to use `http` rather than a browser adapter. Every other real-npm fixture was rerun and still matches Node.
- **The publish refusal for Node built-ins** points at `docs/scripting/node-compat.md` instead of listing modules (its list was out of date).

### Fixed
- **A throw in a `queueMicrotask` or `process.nextTick` callback vanished** into a promise, so the job looked hung until its timeout. It now fails the job ("Uncaught error in process.nextTick callback: …"), as an uncaught exception ends Node. The same goes for a throw in an `http` event listener.

### Docs
- `docs/scripting/node-compat.md`: sections for `http`/`https` and for `net`/`tls`/`http2`/`tty`, the `fs` streams, the new rules on missing modules and uncaught errors, and the updated lists. It also corrects one stale line: Web Crypto's errors have been `DOMException`s since D-106.
- `docs/design/network-reach.md`: B2 marked built, with what building it changed.

## One egress gate; operators can open private addresses per tenant (2026-10-08)

### Added
- **Egress policies per tenant** (D-108). An operator lets one tenant's scripts reach specific private addresses or hosts, on specific ports: `PUT /v1/tenants/{tenant}/egress-policy` (audited) or `automately tenants egress acme --allow 10.0.5.20/32:5432`. A rule is a CIDR or an exact host name; ports are required; at most 100 rules. **Why:** Tony's rule, "anything trying to open network access should not be able to access private things on our cluster unless explicitly allowed to". Until now the only way was `AUTOMATELY_EGRESS_ALLOW_PRIVATE`, which opens everything for every tenant.
- **Tenants see the whole policy that applies to them:** `GET /v1/egress-policy` and `automately egress` show the defaults and why, their rules, whether the development switch is on, and the limits (Tony: tenants should see the rules against their account; the console will show the same).
- **`automately_egress_denied_total{tenant,reason}`:** refused connections, counted.
- **`docs/design/network-reach.md`:** phase B's design, with its threat model and Tony's answers on raw TCP ports, DNS, TLS verification and `createServer`.

### Changed
- **The cluster's own nodes are refused on every port,** even on public addresses: every address a live node advertises and every address of the node's own interfaces (D-108). With the development switch on, only a node's own ports are refused, so `localhost:3000` still works for development.
- **More reserved ranges are refused:** the documentation ranges, 6to4 relays, Teredo, ORCHID, discard, and IPv6 forms that carry an IPv4 address (NAT64, 6to4, IPv4-compatible), each judged by the IPv4 address it reaches.
- **Job completion callbacks** go through the gate as the job's tenant, so its rules apply to them too.
- **A node warns at every start** while `AUTOMATELY_EGRESS_ALLOW_PRIVATE` is on.

### Fixed
- **`fetch` corrupted binary request bodies:** a `Uint8Array` was decoded as UTF-8 before sending. Bytes are now sent as bytes, from any typed array or `ArrayBuffer`. `URLSearchParams` bodies are sent as forms, where before they became `{}`.

## Job logs bounded in size (2026-10-08)

### Fixed
- **A job's log had no size limit** (D-107). It is kept on the node's Java heap, outside the isolate's memory limit, and stored on the job record, so 1,000 huge lines could take gigabytes. A line is now cut at about 16 KiB (ending `… [N more characters]`), by the prelude before it's copied to the host and again by the host, and an attempt keeps its last 1,000 lines up to 1 MiB.

### Docs
- **Where a job's log goes:** live events, the job record, retention, the limits, and what a script can't do (read its own log back). In `docs/scripting/host-apis.md`, linked from `node-compat.md`. `SECURITY.md` no longer says script output appears in node logs; it doesn't.

## Web and Node globals; jobs start 30% faster (2026-10-08)

### Added
- **The globals libraries expect (D-106):** `URL`, `URLSearchParams`, `AbortController`, `AbortSignal`, `EventTarget`, `Event`, `CustomEvent`, `DOMException`, `structuredClone`, `atob`, `btoa`, `queueMicrotask`, `setImmediate`/`clearImmediate`, and a coarse `performance`. All are compared with real Node.
- **`IsolateStartupBenchmark`** (`AUTOMATELY_BENCH=1`): what an isolate costs to start, and what importing built-ins adds.

### Changed
- **The prelude and the built-in modules compile once per node, then come from V8's code cache (D-106).** A trivial job starts in 3.5 ms instead of 4.9 ms (p50), and importing `stream` or `zlib` costs about half what it did, with the new globals included.
- **Web Crypto's errors are `DOMException`s,** as in Node.

## Node compatibility reference; `console` completed (2026-10-08)

### Docs
- **`docs/scripting/node-compat.md` (D-105):** every Node module and global the isolate provides, with each one's limits, caveats and differences from Node, what's refused and why, and how it's verified. **Why:** Tony asked for the polyfills to be documented entirely.

### Fixed
- **`console` lacked `debug`, `time`, `group`, `count`, `table`, `trace`, `assert` and `dir`,** so libraries calling them threw (D-105). They all write to the job's log now.

## Web Crypto's `subtle`, RSA encryption and ECDH (2026-10-08)

### Added
- **`crypto.subtle` (D-104):**
  - **Operations:** generate, import and export, sign and verify, encrypt and decrypt, derive, and wrap and unwrap keys.
  - **Algorithms:** HMAC, AES-GCM/CBC/CTR, RSASSA-PKCS1-v1_5, RSA-PSS, RSA-OAEP, ECDSA, ECDH, Ed25519, X25519, PBKDF2 and HKDF.
  - **`CryptoKey`:** the global is defined too.
  - **Not supported:** AES-KW.
- **`node:crypto` (D-104):** RSA encryption (`publicEncrypt` and the rest, with OAEP), `createECDH`, `diffieHellman`, and X25519 keys.
- **`privateDecrypt` with PKCS#1 v1.5 padding is refused (D-104),** against the Marvin timing attack. That's stricter than Node, because the JVM lacks the "implicit rejection" that makes Node allow it.

## `zlib`, `assert`, `querystring`, `timers`, `os`; wrong bytes from JavaScript fixed (2026-10-08)

### Added
- **`zlib` (D-103):**
  - **Codecs:** gzip, deflate and raw deflate both ways, `unzip`, and Brotli decompression, as sync calls, callbacks and streams, with `crc32` and Node's constants and error codes.
  - **The zip-bomb cap:** every call outputs at most 32 MB.
  - **Brotli compression** isn't available, since there's no pure-Java encoder.
  - **New dependency:** `org.brotli:dec` (MIT).
- **`assert` and `assert/strict`, `querystring`, `timers` and `timers/promises`** (D-103), pure JavaScript, compared with real Node.
- **`os` (D-103),** describing the job rather than the machine: `/tmp`, one CPU, the heap limit as memory, no host name, network or users.
- **The isolate never reports a Node version (D-102),** Tony's call. Libraries that check it take their non-Node paths.

### Fixed
- **Host calls could receive the wrong bytes (D-103).** A `Buffer` slice, or a `Buffer` whose memory was allocated larger than it (base64 decoding does that), reached the host as the whole underlying buffer. That affected `fs` and `files` writes, `fetch` bodies, `crypto` and `kv`. The host now reads the view's own bytes.
- **UTF-8 decoding swallowed characters after a broken sequence (D-103).** It's now the WHATWG decoder Node uses: one replacement character, and nothing lost after it. That covers `TextDecoder`, `Buffer#toString`, `fetch` and `fs`.

## `crypto`: key derivation, ciphers, keys and signatures (2026-10-08)

### Added
- **The rest of `node:crypto`'s core (D-101),** on the JVM's own cryptography:
  - **Key derivation:** `pbkdf2`, `hkdf` and `scrypt`, with their `…Sync` forms.
  - **Ciphers:** AES-CBC, AES-CTR, AES-GCM and ChaCha20-Poly1305 through `createCipheriv`/`createDecipheriv`.
  - **Keys:** `KeyObject`s, with `createPrivateKey`/`createPublicKey` from PEM, DER, JWK or a certificate, and `export`.
  - **Signatures:** `sign`/`verify` and `createSign`/`createVerify` for RSA (PKCS#1, PSS), ECDSA and Ed25519.
  - **Key generation:** `generateKeyPair` and `generateKey`.
  - **Keys stay in the job,** and the costly calls run on a small crypto pool of the node's.
  - **Caps:** PBKDF2 iterations, scrypt memory (64 MB), RSA sizes and data sizes.
  - **Checked:** against the RFCs' vectors and against real Node's output.
  - **jsonwebtoken** now signs and verifies RS256 and ES256 in the isolate.
  - **Why:** step 2 of D-095.

### Changed
- A KeyObject's `equals` compares in constant time for every key type.

### Fixed
- **A queue consumer that was stopping took back the message it had just released (D-101).** For a moment, no other consumer could have it. A release no longer claims more.

## `stream` in the isolate (2026-10-08)

### Added
- **`stream`, `stream/promises` and `string_decoder` (D-100):** Node's own streams, vendored from `readable-stream` 4.8.0 and `string_decoder` 1.3.0 (MIT). They run on the isolate's `buffer`, `events` and `process`. The bundled output is committed, built by `scripts/vendor-stream` from hash-checked npm packages. **Why:** step 3 of D-095. papaparse, csv-parse (streaming) and jsonwebtoken (HS256) now run in the isolate, giving the same answers as under Node.

### Fixed
- `RealNpmPackagesTest` installs again when its fixture's lockfile changes, as its javadoc said it would.

## Fixed: a request that could hang forever under load (2026-10-08)

### Fixed
- **On a single node, an API request could go unanswered under load (D-099),** and the CLI waited forever. The in-memory stores kept a future from their constructor, so handlers continued on the event loop that built the store, not the request's own. An error answer written from there could race the request's thread and throw before it was sent. **Now:** the stores look their map up per use, so answers come back on the request's event loop. `EarlyAnswer.prepare` also no longer throws when the request's body ends mid-check. Clusters on Ignite weren't affected.

## Fixed: S3 downloads under heavy load (2026-10-08)

### Fixed
- **An S3 download could still fail under heavy load (D-098):** every retry landed on another pooled connection that was closing. Retries now go out on a fresh connection and back off briefly (50, 100, 200 ms). Only failures that make no progress count towards giving up, so a long download over a flaky link keeps going as long as it gets further. Other requests that are safe to repeat (GET, HEAD, DELETE, PUT) now retry the same way. **The root cause:** an answer cut short by the server closing the connection was being delivered as complete (0 of 287 bytes). A length that doesn't match `Content-Length` is now treated as a failure, and a blob's writer cleans up any extra upload a retried start left behind. Every PUT now carries `Content-MD5`, so S3 refuses a body that isn't exactly what was meant. That guarded against a bug in the retry itself, which the stress test caught before it shipped: a retried PUT resent an already-consumed buffer.

## `crypto` in the isolate: randomness, hashing, HMAC (2026-10-08)

### Added
- **The global `crypto` (Web Crypto) and `node:crypto` (D-097),** on the JVM's `SecureRandom`, `MessageDigest` and `Mac`.
  - **Web Crypto:** `getRandomValues`, `randomUUID` and `subtle.digest`.
  - **Node:** `randomBytes`, `randomFill`, `randomInt`, `randomUUID`; `createHash` and `createHmac` (incremental, with `copy()`), `hash()`, `timingSafeEqual` and secret `KeyObject`s.
  - **Caps:** at most 64 KiB of random bytes per host call and 1,000 open hashes per execution, and nothing is shared between jobs.
  - **Checked:** against the standards' test vectors and real Node's output.
  - **uuid and nanoid** now run in the isolate.
  - **Why:** step 1 of D-095.
- **Coming next:** ciphers, key derivation and signatures say "not available in the isolate yet" until they arrive.



### Added
- **`env` on jobs and services (D-096).** `{"API_URL": "https://…", "KEY": {"secret": "name"}}` becomes the script's `process.env`, plus `NODE_ENV`, and never the node's environment.
  - **Secrets by reference:** read when the run starts, by `secrets.get`'s rule (`secrets:use`). The job record keeps only the name, and the value is masked in the job's log as `[secret:name]`.
  - **Revisions:** for services, `env` is part of the revision.
  - **Where to set it:** the CLI's `run` and `services deploy` take `--env` and `--env-secret`, `automately deploy` reads `service.env`, and `jobs.run` takes `env` and `inheritEnv`.
  - **Why:** configuration should be explicit and per job (Tony), and secrets shouldn't leak through records or logs.
- **`process` is a global,** as in Node, and the same object as `node:process`.



### Added
- **`docs/design/isolate-polyfills.md` (D-095),** accepted.
  - **Phase A:** `crypto` backed by the JVM and a Web Crypto global, Node's own `stream`, `zlib`, `assert`, `os` and web globals, under rules that keep the sandbox as it is (computation and randomness only, capped, per job).
  - **`process.env` per job:** declared explicitly, with secrets by name and masked in logs.
  - **Network:** phase B's network modules never reach private addresses or the cluster's own nodes unless an operator allows it per tenant.
  - **Why:** real libraries (D-093) showed `crypto` and `stream` are what most npm code still lacks.

## Fixed: the rest of the S3 download gap under load (2026-10-08)

### Fixed
- **S3 downloads could still fail with "Connection was closed" under load (D-094).** It happened in about 1 of 7 runs of the stress test. D-081 covered a close partway through, but two more cases slipped past:
  - **A close reported as an error just after the last byte:** the download was already complete, but it was failed.
  - **A request (first or resumed) that failed to open,** because it went out on a pooled connection the server was closing. It wasn't retried.

  Both now resume or end correctly. Client errors (4xx) are never retried, and the reader sees the store's message only if it finally gives up. **Why:** a node serving files from S3 shouldn't drop downloads when it's busy.

## Packages: tested with real npm libraries (2026-10-08)

### Added
- **`RealNpmPackagesTest` (D-093).** 20 popular npm libraries are installed from a pinned lockfile, bundled with real esbuild, published, imported from jobs and run in the isolate. Each must give exactly the same output as under Node, and those needing what the isolate lacks must be refused with the reason. It runs when `AUTOMATELY_TEST_REAL_NPM` names a work folder (it needs node, npm and, once, the network). **Why:** the isolate's Node compatibility should be measured against real code, not guessed.
- **A `process` stand-in,** with or without `node:`. It doesn't claim to be Node (`process.versions.node` is undefined), and `env` holds only `NODE_ENV`.

### Fixed (found by that test)
- **Unicode regular expressions (`\p{L}`) and `Intl` didn't work:** the isolate ran V8 built without ICU. It now runs Javet's i18n build with ICU 78's data, which the build downloads from the official ICU release and checks against its SHA-256. This had broken marked, ajv and validator.
- **npm code using `Buffer`, `process` or `global` without importing them** failed at run time. Publishing now turns those uses into imports of the stand-ins, and defines `process.env.NODE_ENV` as `"production"`. This had broken immer.
- **CommonJS code calling `require('util')`** or another module the isolate provides failed with "Dynamic require … is not supported". Publishing now turns those calls into static imports. This had broken yaml and validator.
- **Native code that ships as prebuilt per-platform packages (sharp) wasn't detected,** so publishing refused it for its Node built-ins instead of pointing at the Node runtime. An installed optional dependency holding a `.node` file now counts as native.



### Added
- **When a version goes wrong (D-092):** `packages yank`, `block` and `purge`, with `unyank` and `unblock`, and `POST /v1/packages/{name}/versions/{version}/…`.
  - **Yank:** new resolutions skip the version, and what's locked to it still runs.
  - **Block** (for a security hole): the version refuses to load anywhere, and the services running it stop and are disabled.
  - **Purge** (for a published secret): the version's files are deleted for good, keeping any another version shares, and the services running it stop.
  - **Why:** versions never change, so something has to stop bad ones (D-086).
- **`packages:admin`:** blocking and purging need it; owners and admins have it, developers don't. Operators can block or purge any tenant's package with `?tenant=`.
- **Package garbage collection:** files no version names are deleted after an hour, on one node. This covers uploads whose publish never came, and files a purge had to keep.
- **The `automately.packages.files` counter:** cache hits against fetches.



### Added
- **`automately deploy` (D-091).** It publishes the project in a folder, or reuses the version if it's already published with the same files, and runs it as a service whose revision is that version. A changed file without a version bump is refused. **Why:** one command from code to a running service, with every revision traceable to an immutable version.
- **Running a published version:** `PUT /v1/services/{name}` and `POST /v1/jobs` take `"package": {name, version?, export?}` instead of `"script"`. The version is pinned in the lock, and a new version is a new revision.
- **`/packages` in `fs`:** a package reads its own files (JSON, templates, `.wasm`) at `/packages/<name>/<version>/…`, read-only. `fs` also accepts `file:///` URLs, so `new URL('./data.json', import.meta.url)` works.
- **`automately:kv`, `automately:bus`, `automately:jobs`, `automately:files`, `automately:secrets`, `automately:fetch`, `automately:log`:** the host APIs as modules, so packages don't depend on globals.
- **MCP tools:** `publish_package` (from the files' text), `list_packages` and `package_info`. `run_job` and `deploy_service` take a `package`.

### Changed
- **Writing under `/packages` in `fs` fails with `EROFS`** (read-only) instead of `EACCES` (reserved), now that `/packages` holds the job's packages.



### Added
- **`path`, `events`, `buffer`, `url` and `util` in the isolate (D-090),** with or without `node:`. **Why:** bundled npm code reaches for these constantly, and they were the reason many packages couldn't be published. Their results match Node's own modules on about 500 checked cases. `events` and `node:events` are one module, so `EventEmitter` is the same class everywhere.
- **Publishing keeps them as imports** instead of refusing them, and job submission no longer refuses them. The rest of Node (`crypto`, `stream`, `http`, …) is still refused, pointing at the Node runtime.

## Packages, step 2: jobs import packages (2026-10-08)

### Added
- **Scripts import packages (D-087, D-089).** `import { slug } from 'strings'`, a subpath like `'strings/report'`, `@cluster/` packages, and `import('strings')` with a literal name all work in jobs and services. **Why:** this is what publishing packages was for.
  - **Locked at submission:** the server reads the script's imports and resolves each package and its dependencies to exact versions. The lock is saved on the job (`packages`, shown in the API) and on a service's revision, so retries and restarts run the same code even after a newer version is published.
  - **Child jobs** started with `jobs.run` inherit their parent's lock.
  - **`"packages": false`** on a job refuses any package import.
- **Refused at submission with the reason** (400 `unresolved_import`): unknown packages, unmet or conflicting version ranges, `import()` without a literal name (with its line), Node's own modules, and paths or URLs. **Why:** a job should fail when it's sent, not halfway through.
- **Loaded before the script runs (D-089).** The node fetches every file in the lock and checks it against its SHA-256 before making the isolate. Checked files stay in a per-node cache, `AUTOMATELY_PACKAGE_CACHE_MB` (64 by default). A corrupt or missing file fails the attempt before any code runs. There's a new `automately.packages.load` timer.

### Fixed
- **A script that throws while loading** (at the top level, or in a module it imports) now fails with that error and its stack. Before, it failed with a confusing "Cannot access … before initialization". **Top-level `await`** is now waited for properly. **Why:** the module's evaluation promise was being ignored (found in the step 2 spike).

### Changed
- **`import()` and `import.meta.url` now work** in the isolate (Javet supports neither). Each module's source is rewritten as it loads, keeping its line numbers. **The trade-off:** a module loaded with `import('x')` is evaluated up front with the others, rather than when the call runs.

## Packages, step 1: the store and publishing (2026-10-08)

### Added
- **Publishing packages (D-083, D-088).** `automately packages publish` bundles a project with its own esbuild, uploads only the files the cluster doesn't have (checked against their SHA-256), and commits the version. Also `packages ls` and `packages info`, and the API under `/v1/packages`. **Why:** the first step towards scripts importing shared, versioned code; loading them in jobs is step 2.
  - **Checked when published:** a version never changes, so every file, the limits (1,000 files, 10 MB) and every dependency range are checked up front.
  - **Refused with the reason:** native code, naming what pulled it in; optional native extras are left out with a warning.
  - **Cluster-wide packages** under `@cluster/`, published by operators and visible to every tenant.
  - **Scopes** `packages:read` and `packages:write`, given to the built-in roles. Package files count against the tenant's quota, and deleting a tenant deletes its packages.
- `docs/guides/packages.md`.

## Design: inline scripts can import packages (2026-10-08)

### Docs
- **Inline scripts can import packages (D-087, Tony).** The server finds the imports when the job is submitted, picks the newest usable versions and saves them on the job, so retries run the same code. Imports it can't see, and native code, are refused up front, and a job can turn package imports off with `"packages": false`. **Why:** this was the one open question in the packages design.

## Second design review: packages and the Node runtime (2026-10-08)

### Docs
- **A second pass over both designs (D-086) found eight more holes, now closed:**
  - **Packages:** a version can be **blocked**, which stops it everywhere including running services (Tony), or **purged** if it was published with a secret in it.
  - **Node artifacts are layers,** not tens of thousands of files.
  - **Spawned programs** reach the network through a local proxy, and DNS SRV lookups are rerouted.
  - **The runner is explicitly untrusted;** the cluster's own checks are the security boundary.
  - **Host setup** for rootless Podman with gVisor is checked before a node offers it, and the container memory budget is explicit.
- **Limits stated plainly** (Tony): one running copy per Node service at first, with a short gap on redeploy.

## Design review: packages and the Node runtime (2026-10-08)

### Docs
- **Both designs were reviewed for holes (D-085), and the fixes are folded in.** The most important ones:
  - **Packages:** cluster-wide packages now live under a reserved `@cluster/` prefix, so a tenant's package can't impersonate one (dependency confusion). Package storage counts against the quota.
  - **Node containers have no network at all:** the runner tunnels their connections through the cluster's egress rules. Without this, database traffic couldn't have been policed.
  - **The Node runtime is off until the operator enables it,** per tenant, with limits.
  - **WebSockets** will work through the ingress, for both runtimes.
  - **The fixes needing no decision:** orphaned containers, capacity, image pinning, readiness, and how services, child jobs and packages' own files resolve.

## Design: the Node runtime (2026-10-08)

### Docs
- **`docs/design/node-runtime.md` (D-084):** how code that can't run in an isolate (native addons, Node's APIs, real servers) will run. Not built yet; it comes after packages.
  - **The engine:** official Node with our runner, in rootless Podman containers, under gVisor by default.
  - **Builds:** on the developer's machine, using our builder image only when native addons need it, with nothing running npm on the cluster.
  - **The execution layer** is shared with links.
  - **Unsandboxed Node** only on a tenant's own device.
  - **The same `(input, ctx)` contract and globals** as the isolate.

## Design: packages (2026-10-08)

### Docs
- **`docs/design/packages.md` (D-083):** the accepted plan for packages.
  - **What they are:** versioned JavaScript, bundled with esbuild on the developer's machine and published to the cluster. Stored once per file hash in the cluster's own storage.
  - **How they run:** resolved into a lock saved on each job, then fetched and checked before the script starts.
  - **Visibility:** private, or cluster-wide from operators.
  - **Native code** is detected at publish and pointed at the Node runtime.
  - **Why:** it's the next roadmap step, and its plan should be on record before the Node runtime is designed alongside it.

## Folder operations look atomic (2026-10-08)

### Changed
- **While a folder is being moved, copied or deleted, everyone sees the result straight away (D-082).**
  - **move:** the whole folder is at its new name and gone from the old.
  - **copy:** the copy is complete.
  - **delete:** the folder is gone.
  - **Writes** touching either folder get `busy` until it's done.
  - **Why:** before, a reader could briefly see some files under the old name and some under the new.
- **The prefix listing** (`GET /v1/files?prefix=`) answers `busy` while a folder it covers is changing.

## Fixed: S3 downloads cut off near the end (2026-10-08)

### Fixed
- **Downloads from S3 sometimes lost their last few chunks** ("Connection was closed"), about 2% of the time when a node served them to a slower client (D-081).
  - **Why:** the S3 server closed the connection as soon as it had sent the last byte, while the node, pausing for backpressure, still had chunks buffered. Vert.x reported that as a failure.
  - **Now:** a download that closes or ends early carries on with a range request from the byte it reached, up to 3 times. Every byte is delivered once, in order.
- **A file that fails after its headers have gone out is now logged with its cause.** Before, the client connection was cut without a word.

## The file system, part 5 — Handles, locks and public links (2026-10-07)

### Added
- **File handles (D-080):**
  - **Calls:** `fs.promises.open`, and `fs.openSync` with `readSync`, `writeSync` and `closeSync`.
  - **Behaviour:** positional reads and writes, appending, truncating. Edits are published as one new version when the handle closes.
- **Advisory locks:** `await fs.lock(path, { ttlMs })`, with `renew()` and `release()`. They're cluster-wide, expire on their own, and go when the job ends.
- **Public links:** a signed URL anyone can fetch until it expires, pinned to the file's current version. Revoke them all at once.
  - **API:** `POST /v1/fs/share`, `GET /v1/shared/{token}`, `POST /v1/fs/share/rotate`.
  - **CLI:** `automately files share PATH --expires 1h`, `files share --revoke-all`.
- **This completes the file system (D-075 to D-080).**

## The file system, part 4 — `fs` and `/tmp` (2026-10-07)

### Added
- **`fs` for scripts (D-079):** your files as one tree, Node-shaped.
  - **Importing:** `import { readFileSync } from 'fs'` (or `node:fs`, `fs/promises`, `automately:fs`); `fs` is also a global.
  - **Styles:** promises, callbacks and **synchronous calls**.
  - **Errors** have Node's codes.
  - **Why:** much npm and Node code uses `fs`, and the synchronous calls are the hard part of Node compatibility.
- **`/tmp` per job:** private, deleted when the job ends.
  - **Size:** in memory first, then on the node's disk up to 1 GB (`AUTOMATELY_TMP_MEMORY_MB`, `AUTOMATELY_TMP_MB`).
  - **At node start,** whatever a crash left behind is cleared.
- **Tests:** `TmpStoreTest`, and `FsTest`, which runs scripts on a node.

## The file system, part 3 — Mounts (2026-10-07)

### Added
- **Mounts (D-078):** bind a folder to one of your storage providers, optionally read-only.
  - **API:** `PUT /v1/mounts/{path}` with `{provider, readOnly}`, plus `GET /v1/mounts` and `DELETE`.
  - **CLI:** `automately storage mount mnt/photos --provider media`.
  - **Visible:** listings show which folders are mounts and where each file's bytes are.
- **Read-only mounts** refuse new files, deletes and moves beneath them, for scripts, the API, the CLI and agents alike.
- **Tests:** mounts and read-only in `StorageProvidersTest`; reserved names in `FileStoreContract` on all four stores; `ContractTest` and the CLI.

### Changed
- **Reserved top-level names:** `tmp/`, `packages/` and `.packages/` belong to the cluster, for jobs' scratch space and packages, and `mnt/` holds mounts only. New files there are refused (403); files already there stay readable and deletable.
- **Storage routes keep a read-only flag** for prefixes that are mounts.

## The file system, part 2 — Move and copy (2026-10-07)

### Added
- **Move and copy (D-077),** for files and whole folders.
  - **API:** `POST /v1/fs/move` and `POST /v1/fs/copy` take `{from, to, overwrite}`.
  - **CLI:** `automately files mv`, `files cp`, `files rm -r`.
  - **MCP:** `move`, `copy`.
  - **No bytes move,** even on S3, because only the paths change. Copies share their bytes, take no extra room, and count once against the quota.
- **Deleting a folder and everything in it:** `DELETE /v1/dirs/{path}?recursive=true`.
- **Folder operations are journaled:** one runs per tenant at a time, and if a node dies midway another finishes it.
- **Tests:**
  - move and copy in `FileStoreContract` on all four stores
  - `FileTreeTest`, including a node dying mid-move
  - folders copied and moved across two nodes in `FilesTest`
  - `ContractTest`, CLI and MCP

### Changed
- **Deleting or replacing a file releases its bytes** instead of deleting them outright: they're deleted once no file names them, through a reference count per blob.

## The file system, part 1 — Folders (2026-10-07)

### Added
- **A design for a file system on top of the file store (D-075, `docs/design/vfs.md`):** folders, move and copy without moving bytes, mounts, `/tmp`, `/packages`, a Node-style `fs`, locks and public links. It's built in five parts. **Why:** the store holds files well but as a flat set of paths, and modules, Node compatibility, apps and the console need a real file tree.
- **Folders (D-076).** Real folders, empty ones included.
  - **Making them:** writing a file makes its missing folders. Folders stay when emptied, and `rmdir` removes empty ones.
  - **Listing:** one level at a time, by name.
  - **API:** `GET /v1/dirs` · `GET` `PUT` `DELETE /v1/dirs/{path}`.
  - **CLI:** `files mkdir`, `files rmdir`.
  - **MCP:** `list_dir`, `make_dir`.
- **Ignite tables gain new columns when a node starts,** with their indexes, so existing data directories upgrade in place. Files from before folders existed are added to the tree automatically.
- **Tests:**
  - folders in `FileStoreContract` on all four stores
  - `TreeMigrationTest`
  - folders across two nodes in `FilesTest`
  - `ContractTest`, CLI and MCP

### Changed
- **`automately files ls DIR` lists one level,** with folders ending in `/`. `files ls -R` gives the old listing of every file under a path.
- **A name is a file or a folder, never both.** Writing a file where a folder is, or under a file, is refused with 409.

## Security groundwork, part 4b — Identity and audit follow-ups (2026-10-07)

### Added
- **The audit log is tamper-evident (D-072).**
  - **The chain:** each node chains its entries, each carrying the previous entry's hash. The hash is keyed with `AUTOMATELY_SECRETS_KEY`, so someone with database access but without the key can't forge them.
  - **Checking:** `GET /v1/admin/audit/verify` (`automately audit verify`) reports changed, missing or relinked entries.
- **The audit log is exported to S3 (D-073).** Set `AUTOMATELY_AUDIT_S3_BUCKET`, and new entries go out as NDJSON every 5 minutes, one node at a time. Nothing is pruned before it's exported. **Why:** a copy outside the cluster, for SIEMs and as the anchor tamper evidence needs.
- **Renaming users (D-074).** Users have a stable id, so `automately profile --name` (yourself) or `automately users rename` (operators) changes the name and everything follows.
- **`automately profile`:** you, your tenants and what you may do in each.

### Changed
- **Developers can set secrets** (`secrets:write`), not just use them. **Why:** their scripts could already read every secret, so withholding it protected nothing.
- **The last owner is guarded atomically.** Two owners removing each other at once can no longer leave a tenant with none.
- **Removing a member, or deleting a user,** lists the service tokens they made. Those keep working, since they belong to the tenant. Token listings show who made each and flag `makerGone`.
- **A deleted tenant's audit entries stay until pruned** instead of being deleted with it, since removing them would break the chains. A new tenant with the same name sees only its own.
- **The API now answers with names:** `whoami` and member views give the user's name, alongside `userId`.

## Security groundwork, part 4 — Users, roles and the audit log (2026-10-07)

### Added
- **Users (D-070).** People get their own users and join tenants by invite.
  - **Global users:** one account can belong to several tenants, and be an **operator**.
  - **Roles:** built in (`owner`, `admin`, `developer`, `viewer`) or the tenant's own, given directly or through **groups**.
  - **New scopes:** `members:read`, `members:write`, `audit:read`.
  - **Why:** nothing knew who a person was. Taking access away meant hunting down tokens, and the coming rules for links and approvals need people.
- **Personal tokens act as their user, with what the user's roles give them now.** Demoting, removing or disabling someone takes effect on their next request, with every token they made.
- **Invites instead of passwords.** A single-use code, expiring in 7 days by default, is redeemed for a personal token: `automately login URL --invite CODE`. Operators invite a tenant's first owner and other operators. **Why:** no password store to protect. Passkeys and OIDC come with the console.
- **No escalation:** nobody grants more than they hold, only owners grant `owner`, and a tenant keeps its last owner.
- **Operators** are named users with the admin token's powers, which still exclude tenant data. They replace sharing the admin token.
- **The audit log (D-071):** every change, and every refused attempt, with who, when and from where.
  - `GET /v1/audit` (`audit:read`) for a tenant; `GET /v1/admin/audit` for operators.
  - Kept 90 days (`AUTOMATELY_AUDIT_RETENTION_DAYS`).
  - Reads, bus messages and queue pushes aren't recorded.
- **API:** `/v1/invites/redeem`, `/v1/me`, `/v1/me/tokens`, `/v1/members`, `/v1/roles`, `/v1/groups`, `/v1/invites`, `/v1/audit`, `/v1/users`, `/v1/admin/invites`, `/v1/admin/audit`, and `/v1/tenants/{t}/members` and `/invites`. That's 28 operations in the contract.
- **CLI:** `login --invite`, `login --tenant`, `invites`, `members`, `roles`, `groups`, `audit`, `users`.
- **`docs/design/identity.md`** and **`docs/guides/users.md`**.
- **Tests:**
  - `IdentityTest` (9)
  - `RecordsContract` and `AuditLogContract`, in memory and on Ignite
  - `ContractTest` (93 operations)
  - a CLI test of the whole flow

### Changed
- **Tokens show `user` and `createdBy`.** Service tokens record who made them.
- **`whoami` shows the user** and whether they're an owner.
- **The admin-only routes** also accept operators' tokens.

## Security groundwork, part 3 — Signed releases (2026-10-07)

### Added
- **Releases are signed (D-069).** Each release's `SHA256SUMS`, which now lists the installers too, gets an ECDSA P-256 signature, `SHA256SUMS.sig`. The key that makes it **stays on the maintainer's machine**, encrypted with a passphrase.
  - **The release workflow only drafts a release.** `scripts/sign-release.sh TAG` checks the draft, signs it locally and publishes it.
  - **Why:** before this, anyone who could change a release asset (a compromised workflow, a leaked GitHub token, a mirror) could change its checksum too.
- **`scripts/release-key.sh`** creates the key and copies the public half into the installers. **`docs/guides/releasing.md`** covers releasing, checking by hand and rotating the key.
- **Tests:**
  - `InstallScriptTest`: signed, wrongly signed and unsigned releases; `install.ps1`'s check, run under `pwsh`; the key matches everywhere
  - `UpgradeTest`, including a release signed by `openssl` itself

### Changed
- **`install.sh` and `install.ps1` check the signature before trusting any checksum.** They use `openssl` and .NET's ECDSA, so nothing extra needs installing. They refuse unsigned or wrongly signed releases.
  - `AUTOMATELY_RELEASE_PUBLIC_KEY` points them at another key, for a mirror that re-signs.
  - `AUTOMATELY_INSECURE_SKIP_SIGNATURE=1` trusts HTTPS alone, with a warning.
- **`automately upgrade` no longer pipes the installer into a shell.** It downloads the release itself, checks the signature against the key built into the binary, checks the checksum, and only then replaces itself. `--to FILE` replaces another binary.

### Fixed
- **A job that ended within milliseconds could lose its log lines** on the event stream (seen as a flaky `CliTest`).
  - **The cause:** the stream saw the job running and asked its node for the lines so far. The node had already finished the job and no longer answered, so the stream went live with nothing to replay.
  - **The fix:** when the node doesn't answer, the stream reads the job again, and replays it from the store if it has ended. The node records the end before it stops answering, so the record is complete by then.

## Security groundwork, part 2 — Secrets (2026-10-07)

### Added
- **Tenants' secrets (D-068).** `PUT /v1/secrets/{name}` with `{value}`, list, get and delete. **Values go in and never come back out:** the API shows names, versions and who set them.
  - **In scripts:** `await secrets.get('STRIPE_KEY')`.
  - **Sealed:** with AES-256-GCM under `AUTOMATELY_SECRETS_KEY`, bound to the tenant and name, in the replicated store under the system tenant. Without the key the API answers 409 rather than storing values in the clear.
  - **Scopes:** `secrets:read`, `secrets:write`, and **`secrets:use`**. A script may read secrets only if its job was submitted, or its service defined, by a token with `secrets:use`; jobs it starts inherit that, and other scripts get `forbidden`. **Why:** otherwise any `jobs:write` token, such as an agent's, could print every secret. Jobs and services show it as `secrets: true|false`.
  - **CLI:** `automately secrets set NAME` reads the value from standard input, or hidden at a terminal, to keep it out of shell history; `secrets list`; `secrets rm`.
  - **Deleting a tenant** deletes its secrets.
- **`docs/guides/secrets.md`**, and `secrets` in the scripting reference.
- **Tests:**
  - `TenantSecretsTest`: sealed at rest; copies under another name, tenant or key don't open; limits
  - `SecretsTest`: never returned; `forbidden` without `secrets:use`; child jobs and services; tenant isolation; no key
  - a CLI test
  - `ContractTest`: 64 operations

### Changed
- The MCP guide suggests leaving `secrets:use` out of an agent's token.

## Security groundwork, part 1B — Node certificates between core nodes (2026-10-07)

### Added
- **Core nodes authenticate each other with certificates from the cluster's own CA (D-066).**
  - **What's covered:** Ignite discovery and communication, the clustered event bus and the internal endpoint now use TLS 1.3 with **a node certificate required on both sides**. A machine without one can't join or read traffic.
  - **On by default** for clustered nodes. A node without certificates refuses to start and says how to get them. `AUTOMATELY_CLUSTER_TLS=off` is for development only, with a warning at every start.
  - **D-036's shared secret** on the internal endpoint is retired when certificates are on.
- **One-line joins (D-067):**
  - **`automately cluster init`** creates the CA and a join line per node. **`ca.key` stays on your machine**, and the cluster never stores it.
  - **`automately node invite node4 --host … --ca ca.key`** signs node4's certificate locally and prints `AUTOMATELY_JOIN=c93_nodebundle_…`, carrying node4's own key and certificate, never the CA key.
  - **The strict variant:** `automately-server --prepare` with `node invite --key` sends no secrets.
- **`automately node revoke`:** every node refuses the node's certificate from then on, and if it's in the cluster it's forced out through discovery (`failNode`), its process stopping. Inviting it again lets it back.
- **`AUTOMATELY_CLUSTER_JOIN_TIMEOUT_MS`** (60 s): a node whose certificate is refused fails its start instead of retrying forever, which is Ignite's default.
- **A new module, `automately-tls`** (BouncyCastle for building certificates; PEM, bundles and TLS contexts with the JDK), shared by the server and the CLI.
- **Docker Compose:** a one-shot `tls-init` service makes the certificates on the first `up`, so the compose cluster, the demo and the benchmarks stay one command (development only; `ca.key` is in a volume).
- **`docs/guides/cluster.md`:** creating a cluster, adding, removing and renewing nodes.
- **Tests:**
  - TLS-module handshakes: no certificate, a foreign CA and a revoked node are refused; a re-invited node gets in
  - `ClusterSecurityTest`
  - CLI tests for `cluster` and `node`
  - every in-JVM cluster test now runs with TLS required
  - DockerClusterIT joins with bundles and revokes a live node
- **The native CLI still builds with BouncyCastle in it,** with no extra reflection config. It was checked here: a static musl binary (50 MB) ran `cluster init`, `node invite` and `node invite --key`, and the JVM decoded and verified the lines it printed.

### Changed
- `POST /v1/cluster/nodes/{id}/revoke` (admin). The OpenAPI contract now has 60 operations.
- **SQL that Ignite cancels while partitions move** (`QueryCancelledException`) is retried like the other topology errors (D-033). The Docker test caught a node failing its start that way.

### Fixed
- **Certificate dates have one-second precision,** so a node invited again within the second of its revocation looked revoked. Certificate serials now carry the exact issue time, which revocation compares.

## Security groundwork, part 1A — Closing Ignite's unused ports (2026-10-07)

### Fixed
- **Ignite's thin-client/JDBC/ODBC connector (10800) and REST command protocol (11211) no longer listen (D-065).** Both are Ignite defaults, unauthenticated, on every interface, and Automately uses neither. Anything that could reach 10800 could run SQL against every table. Found by probing a running node while designing mutual TLS for the cluster.

### Added
- **`AUTOMATELY_CLUSTER_BIND`:** the one private address Ignite listens on, defaulting to an explicitly set `AUTOMATELY_ADVERTISE_HOST`. Nodes warn when Ignite listens on every interface. The compose file and DockerClusterIT bind each node to its hostname.
- **`ListeningPortsTest`:** a clustered node may open only its known ports plus the event bus. It reads the JVM's sockets from `/proc` (Linux), and was checked to fail on the old configuration.
- **`docs/design/cluster-security.md`:** the design for the rest.
  - **The CA:** a cluster certificate authority whose key stays on the operator's machine.
  - **Mutual TLS:** on Ignite, the event bus and the internal endpoint.
  - **Joining and renewal:** one-line joins with a per-node bundle (never the CA key), and renewals done by the operator.

  Tony decided where the CA key is kept, and that the internal endpoint's shared secret is retired once mutual TLS is required.

### Docs
- SECURITY.md: the finding, the fix, and `AUTOMATELY_CLUSTER_BIND` in the checklist.

## Phase 5, milestone 4 — The demo (2026-10-07)

### Added
- **`scripts/demo.sh` (D-064):**
  - **Setup:** it builds the jar into a local image and starts three nodes with Prometheus and Grafana.
  - **The agent:** a scripted MCP client (the same JSON-RPC an agent sends to `/mcp`) deploys a service that consumes a durable `orders` queue, then pushes 120 orders.
  - **The chaos:** it `SIGKILL`s the node running the service, mid-queue.
  - **What it checks:** the service restarts on a survivor, the waiting orders are handled, and the orders that were in flight come back when their leases run out. It fails unless all 120 are handled, counted by id.
  - **Afterwards:** the cluster stays up for Grafana, and `scripts/demo.sh down` removes it.
  - **Why scripted:** it runs the same way every time, needs no API key, and doubles as a smoke test. Tony agreed a live agent session isn't needed.
- **`docs/demo.html`:** a self-contained page that plays the recording offline, with the player and the recording inlined and "jump to" buttons for the kill and the recovery.
- **`docs/demo.cast`:** a recording of a real run (asciicast v2). It was made by `scripts/record.py`, which needs no asciinema and redacts `c93_` tokens.
- **Metrics:**
  - `automately_queue_messages_total{outcome}`: pushed, claimed, acked, retried, reclaimed, released, dead.
  - `automately_callbacks_deliveries_total{outcome}`: delivered, retry, failed.
  - A "Queues and callbacks" dashboard row (now 29 panels), which `DashboardTest` covers.
- **README:** the demo with its output, the status (phase 5 done), and four new rows in "What changed since 2017": callbacks, queues, jobs from scripts, and host APIs as objects.

## Core features, part 4 — Scripts start and follow jobs (2026-10-07)

### Added
- **`jobs.run(script, input, options)` (D-063)** returns `{id, result(), events(handler), stop()}`. There are also `jobs.get(id)`, `jobs.stop(id)` and `jobs.handle(id)`.
  - **Scripts and options:** the script can be a module's source or a function. The options are those of `POST /v1/jobs`, callback included.
  - **Why:** 2017's `Automately.startJob`, `getJob` and `stopJob` let jobs drive other jobs. Its `waitForJob` blocked a thread; `result()` is a promise.
- **Started jobs** belong to the caller's tenant, count toward its rate limit, and record `parent` and `depth` (both now in the API's Job). Nesting stops at 5 levels and 100 starts per run.
- **`JobsFromScriptsTest` (5):** a parent awaiting a child, the depth limit, stop and events, other tenants' jobs, and bad runs refused.

### Changed
- **The rate limiter is created once per node and shared** by the API, MCP and scripts, so jobs started from scripts use the same per-tenant budget.

## Core features, part 3 — Completion callbacks (2026-10-07)

### Added
- **A job's result can be POSTed to a URL when the job ends, signed (D-062).** Ask with `"callback": {"url", "data"}` on `POST /v1/jobs`, `automately run --callback URL --callback-data JSON`, or `ctx.setCallback(url, data)` from the script, which also works in a service's run.
  - **What's sent:** the body is `{jobId, status, output, error, attempt, finishedAt, service, data}`, with `data` returned unchanged.
  - **Signature:** `Automately-Signature: t=…,v1=HMAC-SHA256(secret, t + "." + body)`.
  - **Retries:** up to 8 attempts with backoff, through a durable system queue (D-061), so a delivery survives the node that queued it. Each attempt is recorded on the job (`callback.status`, `attempts`, `deliveredAt`, `lastError`).
  - **Why:** 2017's `callbackUrl` was a core feature that the rewrite didn't have. Its POST was unsigned, sent once, and left out the output.
- **Every way a job ends** (finished, failed, timed out, cancelled, orphaned, rejected) queues exactly one delivery, through one `JobStore` wrapper.
- **The address passes the `fetch` checks** (D-026). A refused address fails at once instead of retrying.
- **Each tenant has a signing secret:** `GET /v1/callbacks/secret` and `POST /v1/callbacks/secret/rotate`, or `automately callbacks secret [--rotate]`. It's stored under the system tenant and sealed with `AUTOMATELY_SECRETS_KEY` when one is set.
- **`KvStore.putIfAbsent`,** so nodes racing to create a secret agree on one.
- `docs/guides/callbacks.md`: what a receiver gets, and how to check the signature in Node.js and Python.
- **Tests:**
  - `CallbacksTest` (5)
  - `CallbackDeliveryTest` (3)
  - the kv contract's `putIfAbsent`
  - CLI coverage, and the OpenAPI contract with 59 operations

## Core features, part 2 — Durable queues: `bus.queue(name)` (2026-10-07)

### Added
- **Durable queues, the bus's second mode (D-061).** `bus.queue('emails')` gives `push` (with `delayMs`, a deduplicating `key`, `maxAttempts`), `consume(handler, { concurrency })`, `stats`, `dead`, `retry` and `discard`.
  - **Delivery** is at least once to competing consumers on any node.
  - **Acknowledgement and retries:** a handler that resolves acknowledges its message; one that throws gets it retried with backoff (1 s, 4 s, 16 s, … up to 10 min), and then dead-lettered with its last error.
  - **Leases:** a consumer holds a message under a lease it keeps extending. If the consumer's node dies, the lease runs out and another node delivers the message again.
  - **Why:** in 2017, jobs and services talked over the message bus. The bus we have delivers at most once, so a message sent while nobody listens is lost, and Tony wanted a queue mode next to the default one.
- **Storage:** a replicated `QUEUE` table on Ignite, and an in-memory store for single nodes. Both pass one contract, because the rules (claim by compare-and-set, ack only by the owning delivery, backoff, dead letters, expired leases taken back by the next claim) live in `QueueService`.
- **REST:** `POST/GET /v1/queues/{queue}`, `GET /v1/queues/{queue}/dead`, `POST …/dead/{id}/retry`, `DELETE …/dead/{id}`, with new scopes `queues:read` and `queues:write`. The OpenAPI contract now has 57 operations.
- **CLI:** `automately queues push · stats · dead · retry · discard`.
- **MCP tools:** `queue_push`, `queue_stats`, `queue_dead` and `queue_retry`.
- **Tests:**
  - `QueueStoreContract` (13 tests, on both stores)
  - a three-node failover test (a consumer's node stopped mid-message, and the message delivered on a survivor)
  - `QueueScriptTest` (5 tests, real scripts)
  - an end-to-end test where a service on one node consumes what REST pushes through the other
  - contract, MCP and CLI coverage

### Changed
- **The tenant quota counts queued messages too,** with files and kv. The quota message says so.

## Core features, part 1 — Host APIs as Java objects (2026-10-07)

A review against the 2017 code found completion callbacks, jobs starting jobs, and queues missing. [docs/design/core-features.md](docs/design/core-features.md) plans all three, in four parts before the demo. This is the first part.

### Changed
- **The APIs scripts see are Java objects again, one per API (D-060).**
  - **Core:** `automately.core.runtime.api` defines `KvApi`, `BusApi`, `FilesApi`, `FetchApi` and `HttpApi` (gathered in `HostApis`), which the node implements (`NodeKvApi`, …) over one shared `HostScope` that carries the tenant. They replace `HostServices` and its generic `call(op, json)` switch. They hold no script-engine types, so a later Python runtime can bind the same objects.
  - **Javet:** one binding per API (`KvBinding`, …). Each returns promises created in Java, exposes only `@V8Function` methods, and converts values through V8's own `JSON`, captured before the script runs.
  - **The 475-line JavaScript string is gone.** It's now 13 small files in `automately-runtime-javet/src/main/resources/automately/runtime/javet/js/`, only for what has to be idiomatic JavaScript. Host errors are created in Java as real `Error`s.
  - **Why:** the API's shape lived in a string, every operation went through one `switch`, and nothing in the core described the API as types. Tony wanted the 2017 structure (APIs as objects) back, and a design another language could reuse.
- **No behaviour change.** `NodeHostApisTest` (28 tests) covers each API without an engine. `HostBridgeTest` (21) and the rest of the runtime suite, hostile scripts included, pass with their scripts unchanged.

### Docs
- `docs/design/core-features.md`: the plan (host APIs as objects, `bus.queue(name)`, signed completion callbacks, a `jobs` API for scripts), with Tony's choices.
- `docs/design/host-apis.md`: the mechanism as of D-060.
- `NOTES.md`: parked outside subscriptions, reply-to-a-reply, modules and `require`, and a Python runtime through GraalPy, with the sandboxing, cold-start and compatibility gates a spike must pass.

## Phase 5, milestones 3–4 — Benchmarks and the security model (2026-10-07)

### Added
- **`benchmarks/run.sh`** brings up the three-node cluster from the freshly built jar, runs every scenario, writes [docs/benchmarks.md](docs/benchmarks.md) and tears the cluster down. **`benchmarks/Bench.java`** is a dependency-free single-file client (`java Bench.java …`). Scenarios: cold start (submit → finished), job throughput with 1 and 32 clients, ingress → service with 64 clients, a 256 MB upload and download (median of 3), and idle-isolate density. **Why:** the README's numbers should come from a script anyone can rerun (D-059).
- **`SECURITY.md`**: who is trusted, each boundary and the decisions behind it, what isn't defended (isolates share the node's process, the cluster's ports are unauthenticated, TLS is the operator's), a deployment checklist, and how to report a vulnerability.
- README: a numbers table, links to both pages, and the roadmap updated.

### Changed
- **Methodology** (D-059):
  - Jobs are timed to the `end` event on their event stream, as the CLI waits, not by polling.
  - Density reads the Java processes' resident memory with a pre-touched fixed heap, not `docker stats`, which counts page cache and gave negative results.
  - The ingress client sends the service's `Host` header (`-Djdk.httpclient.allowRestrictedHeaders=host`).
- **Tried and not kept:** storing four upload chunks at once. A/B: 48 vs 45 MB/s, within run-to-run noise, not worth the extra complexity in the file store. The first run's 13 MB/s was an outlier.

### Fixed
- README: the sandbox's egress bullet repeated the "no `Java`" line.

## Phase 5, milestone 2 — A dashboard you can run (2026-10-07)

### Added
- **`docker compose --profile observability up`** adds Prometheus (scraping every node's management port, on `localhost:9091`) and Grafana (`localhost:3000`, anonymous viewers) to the three-node cluster. The **Automately** dashboard is provisioned as the home page. **Why:** the operator's view of a whole deployment (cluster, jobs, isolates, services, API and ingress, rate limits, storage), one command away.
- **The dashboard** (`deploy/observability/grafana/dashboards/automately.json`) has 26 panels and is generated by `deploy/observability/generate-dashboard.py`, so changes are reviewable.
- **`DashboardTest`** pulls every metric name out of the dashboard's queries, runs work that produces each one on a real clustered node, and fails if any is missing from the scrape, so a renamed metric can't leave a panel silently empty.
- **Checked by hand:** the Compose stack came up, Prometheus showed all three targets up and reported 3 live nodes, and Grafana served the provisioned dashboard.
- The Dockerfile exposes 9090.

## Phase 5, milestone 1 — Metrics, readiness and structured logs (2026-10-07)

### Added
- **Prometheus metrics** at `/metrics` on a separate management port, `AUTOMATELY_METRICS_PORT` (D-058). Vert.x's HTTP, event bus and pool metrics, the JVM's, and Automately's own: jobs (submitted, queue wait, duration by status, running), isolate cold start, service runs and restarts, 429s by tenant, files stored and refused, live nodes, leader, and Ignite region use. Labels stay low-cardinality. **Why:** weak spot #8; nothing in the 2017 code could be measured.
- **`GET /ready`:** 200 once the node has joined and the cluster is active, otherwise 503 with the reasons. It's in the contract.
- **`AUTOMATELY_LOG_FORMAT=json`:** JSON lines with key-value fields. "Job ended" lines now carry `jobId`, `tenant`, `status`, `attempt` and `durationMs`.
- **Tests:** `ObservabilityTest` covers real work in Prometheus, the absence of per-job and per-client labels, readiness, and the JSON encoder with key-values.

### Changed
- **Logback** replaces slf4j-simple in the server, with the same text format by default.
- Test nodes use random metrics ports, since several nodes share a JVM.

## File storage backends, step 2: tenants' own providers (2026-10-07)

### Added
- **Tenant storage providers** (D-057): an S3-compatible bucket with the tenant's own keys, or a disk folder under the admin's `AUTOMATELY_DISK_ROOT`. Path-prefix routes and a default choose which provider new files go to. Each file records where its bytes are, so reads always find them. **Why:** Tony wants tenants to configure their own providers, with a global default from the environment.
- **The admin's storage policy per tenant:** allowed types, disk allotment, private endpoints. Nothing is allowed by default, and turning a type off blocks new writes but not reads.
- **Secrets encrypted at rest** (AES-256-GCM, `AUTOMATELY_SECRETS_KEY`) and write-only in the API. Providers are checked against their backend before they're saved.
- **Quota:** only the cluster's own storage counts against `AUTOMATELY_TENANT_STORAGE_MB`. Disk providers count against the allotment, and tenants' buckets aren't counted. A declared upload size that won't fit is refused up front, naming the limit.
- **A shared disk root check** across nodes: disk providers only work while every node sees the same folder.
- **Tenant endpoints** are resolved and checked like `fetch`, and connections are pinned to the checked address.
- **API:** `/v1/storage`, `/v1/storage/providers[/{name}[/check]]`, `/v1/storage/routes`, `/v1/tenants/{name}/storage-policy`; scopes `storage:read` and `storage:write`. All are in the OpenAPI contract (52 operations).
- **CLI:** `automately storage` (overview), `storage providers ls|get|set-s3|set-disk|check|rm`, `storage routes [set]`, `tenants storage-policy`.
- **Tests:** `StorageProvidersTest`, `SharedDiskRootTest`, a storage-config contract on both stores, `StorageModelTest`, and the CLI.
- [`docs/guides/storage.md`](docs/guides/storage.md).

### Changed
- `FileStore.create` takes a `Room` (how much fits, by destination) instead of a byte count, and `ensureRoom` replaces the separate quota check. Garbage rows record each blob's backend and tenant, and `FILES` has a `backend` column.
- Deleting a tenant also forgets its storage policy, providers and routes, and a tenant with files on its own providers isn't considered empty.

## File storage backends, step 1: the global default (2026-10-07)

### Added
- **`AUTOMATELY_FILES_BACKEND` = `cluster` | `disk` | `s3`** (D-056). File metadata stays in the cluster database. The bytes go to the chosen backend, and each file records which one holds it. **Why:** the original Automately had pluggable stores (local, S3, Azure), and Tony asked for providers back, with tenants able to bring their own next ([plan](docs/design/storage-providers.md)).
- **The `s3` backend:** AWS S3 and S3-compatible stores (MinIO, R2, B2, Wasabi, GCS through its S3 keys), with streaming multipart uploads and ranged reads. It is configured with `AUTOMATELY_S3_BUCKET`, `_ENDPOINT`, `_REGION`, `_PREFIX`, `_PATH_STYLE`, `_ACCESS_KEY_ID` and `_SECRET_ACCESS_KEY`, falling back to the standard `AWS_*` variables.
- **The `disk` backend:** a folder (`AUTOMATELY_FILES_DIR`), local or shared over NFS, with crash-safe writes and free-space checks.
- **Startup check:** a node whose backend can't be used refuses to start and says why.
- 503 `storage_unavailable` when the backend fails mid-request.
- **Tests:** `BlobStoreContract` on all four implementations (Versity's S3 gateway in Docker for S3), the file-store contract on disk and S3, `FileBackendsTest` (a cluster on S3, a node on disk, refused starts), and a SigV4 test against AWS's example.

### Changed
- `ChunkedFileStore` is now `ClusterFileStore` (metadata and versioning). The chunks moved behind `BlobStore` (`SharedDataChunks`, `IgniteChunks`), and `FileInfo` records `backend` instead of chunk counts.

### Fixed
- A node that failed during startup could hang instead of reporting the failure: it waited, on a Vert.x thread, for Vert.x to close.

## Phase 4, milestone 4 — The MCP server (2026-10-06)

### Added
- **MCP at `/mcp` on every node** (D-047, D-054): Streamable HTTP, stateless, using the same tokens, scopes and rate limits as the API.
  - **Tools:** `run_job`, `get_job`, `job_logs`, `list_jobs`, `cancel_job`, `deploy_service`, `list_services`, `service_status`, `service_logs`, `start_service`, `stop_service`, `restart_service`, `delete_service`, `write_file`, `read_file`, `list_files`, `delete_file`, `publish`, `request`. Each is annotated read-only or destructive, and each acts through the REST API with the caller's token.
  - **Resources:** the three scripting guides (`automately://docs/…`), bundled from `docs/scripting/`. An `instructions` text on `initialize` explains the script shape.
  - **Why:** the positioning since the revival plan is that AI agents can deploy and run code on Automately.
- `/mcp` is in the OpenAPI contract, so `ContractTest` covers it.
- **Tests:** `McpTest` covers the handshake, version negotiation, notifications, errors, batches, 401/403, resources, `run_job` with logs, scopes, and an agent deploying a service, calling it through the ingress, then reading its logs, files and bus. `CliTest` drives the stdio bridge.
- [`docs/guides/mcp.md`](docs/guides/mcp.md): how to connect remote and stdio clients, and the tools.

## Phase 4, milestone 3 — The CLI, with a one-line install (2026-10-06)

### Added
- **`automately`, the CLI** (D-048, D-053):
  - `login`/`logout`/`whoami`
  - `run`, which follows a job, sends logs to stderr and the output to stdout, and exits with the outcome
  - `jobs`, `services` (`deploy`, `logs`, …), `domains`, `files` (`put`/`get`/`ls`/`rm`, and `sync` of a folder by ETag)
  - `tokens`, `tenants` (admin), `mcp` (the stdio bridge), `version`, `upgrade`
  - `--json` on any command
  - **Why:** Tony asked for a CLI that's easy to install, with no JDK needed on client machines.
- **Native binaries.** A `native` Maven profile (GraalVM). A static musl build of the Linux x64 binary was made and tested here: 50 MB, starts in 10 ms, and runs on Alpine, logs in, runs a script, and bridges MCP against a real node.
- **One-line install** (D-049): `install/install.sh` (`curl … | sh`) and `install/install.ps1` (`irm … | iex`). They detect the platform, **verify SHA-256**, install to a user directory and set up `PATH`.
- **`.github/workflows/release.yml`**, run only on a version tag. It builds Linux x64 (static) and arm64, macOS arm64 and Intel, and Windows x64, smoke-tests them, and publishes them with `SHA256SUMS` and both installers. It does nothing until a tag is pushed, and D-017 stands.
- **Tests:** `CliTest` runs every command in-process against a real node, and `InstallScriptTest` runs the real installer against a local release host, including a tampered checksum, and checks the PowerShell script parses.
- [`docs/guides/cli.md`](docs/guides/cli.md).

### Settled afterwards
- The release workflow may use GitHub Actions, and downloads come from GitHub Releases once the repo is public (D-055).

## Phase 4, milestone 2 — The OpenAPI contract, validation and rate limits (2026-10-06)

### Added
- **`openapi.yaml` (OpenAPI 3.1), the API's contract** (D-046), served at `GET /v1/openapi.json` without a token. It covers every operation, with its scope, request and response schemas, the error envelope, and the bearer scheme. **Why:** weak spot #4; the CLI and MCP tools are built from it next.
- **Request validation** (D-050). Requests are checked against their operation before any handler runs, and bad input gets 400 `invalid_request` naming where. JSON bodies are read once, after auth, with each operation's own limit.
- **Event streams of running jobs start with what was logged so far.** Log events now carry a `seq` number. A client connecting to a running job, on any node, first receives its recent lines (up to 1 000) from the node running it, then live events, each line exactly once. **Why:** `automately services logs` and the MCP tools need a running service's recent output, and the stream used to show only lines logged after connecting.
- **Rate limits per tenant** (D-051): `AUTOMATELY_RATE_LIMIT_PER_MINUTE` (default 1200, 0 = off), each node enforcing its share. Responses carry `RateLimit-Policy` and `RateLimit`, and over the limit is 429 with `Retry-After`. The admin isn't limited.
- **Tests:**
  - `ContractTest`: routes and operations must match exactly, every one of the 40 operations is called and its response validated, bad input is refused, and a form-typed JSON body (`curl -d`) still works
  - `RateLimitTest` and `RateLimiterTest`

### Changed
- `RouteScopes` has one rule per operation, naming its contract operation and body mode. The node won't start if a rule and the spec disagree.
- **Stricter input** where the spec says so: for example, `limit` above 1 000 is now 400 instead of being clamped, and unknown scopes or `onOrphan` values are refused by the contract.
- Bus messages may have no body (`null`), as before; the spec now says so.
- Tests run with rate limiting off, except `RateLimitTest`.

### Fixed
- **A 413 could arrive as a reset connection.** When a too-large upload was refused, the connection closed while bytes were still arriving. It now closes with a lingering close (D-052).
- The spec as first written said `cancelRequested` was always a boolean. `ContractTest` found that it can be `null`.

## Phase 4, milestone 1 — Tenants and scoped tokens (2026-10-06)

### Added
- **Bearer tokens on every `/v1` call** (D-045):
  - Tokens look like `c93_` + 43 base62 characters + a 6-character checksum. Only their SHA-256 is stored.
  - Each belongs to one tenant, carries scopes (`jobs:read`, `jobs:write`, `services:read`, `services:write`, `files:read`, `files:write`, `bus:publish`, `bus:request`, `tokens:write`, `nodes:read`, or `*`), may expire, and can be revoked with effect on the next request, on every node.
  - A missing token gets 401 with `WWW-Authenticate: Bearer`, and a missing scope gets 403 naming it.
  - **Why:** weak spot #7. The tenant header let anyone act as any tenant.
- **The admin token** (D-044) comes from `AUTOMATELY_ADMIN_TOKEN`, or is generated once by the first node and logged. It manages tenants and tokens only, and gets 403 on tenant data. `POST /v1/admin/rotate` replaces a generated one.
- **Tenants and tokens API:** `/v1/tenants` (admin: create, list, get, delete once empty), `/v1/tenants/{name}/tokens` (admin), `/v1/tokens` (a tenant's own; tokens can only mint narrower ones), and `/v1/whoami`.
- **`RouteScopes`**, one table saying what each route needs. It fails closed, so a route without a rule can't be reached.
- `AuthStore` with SharedData and Ignite implementations (`TENANTS`, `TOKENS`, `SETTINGS` tables) and a shared contract; `Authenticator`; `TokenFormat`.
- **Tests:**
  - `AuthTest` walks every route (each must answer 401 without a token), and covers scopes, admin separation, narrowing, revocation, tenant deletion, and bodies arriving intact after the async check
  - contract tests on both stores, `TokenFormatTest`, `AuthenticatorTest`
  - every existing test, and the Docker test, now authenticates with tokens (`TestAuth`)

### Changed
- **`X-Automately-Tenant` is gone.** The tenant comes from the token.
- `/v1/nodes` now needs `nodes:read` or the admin token.
- `docker-compose.yml` passes `AUTOMATELY_ADMIN_TOKEN` through, and the README's quick start mints a token.

### Known
- **An intermittent 500 on the first write to a freshly started three-container cluster.** It happened twice in about ten clean full runs: once uploading a file, once creating a service. It happens before any node is killed, and isn't tied to files or auth. It didn't reproduce in 8 later Docker runs, three of them from clean builds. `DockerClusterIT` now includes every container's ERROR lines, with stack traces, in its failure messages, so the next occurrence will show the cause.

## Phase 3b, milestone 3 follow-ups — kv in the quota, failover tests, the shutdown stall (2026-10-06)

### Changed
- **The tenant quota counts `kv` too** (D-043). `AUTOMATELY_TENANT_STORAGE_MB` now covers files and `kv` together. kv entries count as the bytes of their namespace, key and JSON value. Over the quota, `kv.set` and `kv.increment` fail with `limit_exceeded`, and uploads get 413. A full tenant can still update, shrink or delete its entries. **Why:** Tony asked for it; until now a tenant could fill the cluster through `kv` with no limit except cluster capacity.
- `FileStore.create` takes the most an upload may store; quotas moved out of the stores into `TenantQuota`.

### Fixed
- **SQL during rebalancing is retried.** Ignite rejects a query with "Failed to reserve partitions" or "please retry the query" while partitions are still moving, for example right after a fresh cluster activates. Such errors now count as transient (D-033) and are retried. **Why:** one full Docker run got a 500 on the first upload to a fresh cluster. That upload runs two `SUM` queries for the quota, and this is the likely cause. It didn't reproduce, so the Docker test now includes container logs in its failure message.
- The ArchUnit rule against mutable statics flagged a synthetic `$SWITCH_TABLE$` field that an IDE's Eclipse compiler had written into `target/classes`. Compiler-generated fields are now excluded.
- **A 30 s stall when a node stopped** (D-042). The stall was listed as known in milestone 3. The cause was a binary schema (Vert.x's `RemoveRegistrationProcessor`) first sent while a node was leaving. Nodes now register every schema they'll send at start, and table rows always use one field order. **Why:** it would have hit every rolling restart, not just tests.

### Added
- **`FileFailoverTest`** (3 nodes, one backup):
  - a 40 MB download outlives the death of a node holding its data, byte for byte
  - a download cut off with its serving node resumes on another node with `Range`, with the same ETag and identical bytes
  - an upload cut off with its node leaves the previous version intact and nothing else behind
- **`DockerClusterIT.fileDownloadsSurviveSigkillOfANode`:** with a real `SIGKILL`, a download served by the killed node resumes on another with `Range`, and a download from a surviving node, reading chunks the dead node held, loses nothing.
- **`BinarySchemaTest`:** after start-up, no binary schema is first seen, even when a node stops (D-042).
- `TenantQuotaTest`, a `kv` usage contract test (both stores), and quota tests for `kv` and files together in `NodeHostServicesTest` and `FilesTest`.

## Phase 3b, milestone 3 — Files (2026-10-06)

### Added
- **`files` for scripts:** `files.write(path, data, {contentType})`, where `data` is a string, bytes, JSON, an (async) iterable, a request body or a `fetch` response. Also `read`, `text`, `json`, `stream(path, {headers})`, `stat`, `list({prefix, limit, cursor})` and `delete`. A handler can return `files.stream(path)`, or `{headers, body: files.stream(path)}`. Java then serves it with `Range`, `ETag`/304, `HEAD` and `Content-Length`, and the bytes never enter the isolate (D-040). **Why:** Tony asked for services that can serve large files. Video seeking needs ranges and an exact length.
- **Files from outside:** `PUT /v1/files/{path}` (streamed upload, 201 with an `ETag`), `GET`/`HEAD /v1/files/{path}` (with ranges and 304), `GET /v1/files?prefix=&limit=&cursor=`, and `DELETE /v1/files/{path}`.
- **Storage:** 1 MB chunks in a replicated Ignite cache, metadata in an indexed `FILES` table, a new file id per write with an atomic swap, and a leader-run garbage collector (`FileGarbageVerticle`) with a 10-minute grace period for reads in progress (D-040). In non-clustered mode it uses SharedData maps.
- **Admission control (D-038):** writes that grow storage are refused with `insufficient_storage` (HTTP **507**) before the disk or memory fills, and the node keeps running. The per-tenant file quota is refused with 413. **Why:** a large upload into a small in-memory node halted the JVM; Tony: "i don't want to be able to crash the jvm with a fucking file upload".
- **Config:** `AUTOMATELY_TENANT_STORAGE_MB` (1024) and `AUTOMATELY_MIN_FREE_DISK_MB` (1024).
- **Tests:**
  - `FullRestartTest` now also uploads a 3 MB file, restarts every node, and downloads it byte for byte from another node
  - a `FileStore` contract (10 tests) run against SharedData and Ignite, including a replace while a read is in progress
  - `StorageCapacityTest`: an in-memory 128 MB node refuses an oversized upload and stays alive; a durable node refuses below the free-disk floor
  - `FilesTest` on a real two-node cluster: a 10 MB REST round trip (written on one node, read on the other), ranges/416/304/HEAD, list/delete/path validation, the quota, the script API, and a service storing a streamed 6 MB upload and serving it through the ingress with ranges

### Changed
- **Ignite:** 256 partitions instead of 1 024, a 128 MB minimum data region (validated at start), region metrics on, and page eviction as a last resort for in-memory regions (D-038).
- **Early answers drain the body** (up to 64 MB) instead of closing at once, so a refused upload reaches the client as a 413 or 507 rather than a reset (D-039, superseding part of D-037). The REST upload checks `Content-Length` against the quota before reading.
- A service's `content-length` header is kept when it's exact (files). Script-set `content-length` is still dropped.

### Fixed (found by the new tests)
- An in-memory region could run out (`IgniteOutOfMemoryException`) and halt the JVM, once with a large upload and once just from creating tables in 64 MB (D-038).
- Tests that called `Ignition.stopAll()` also stopped the shared contract-test node.
- **Stale reads across nodes:** Ignite's default `PRIMARY_SYNC` let a node read a backup copy before a write reached it, so a file just written on node A was sometimes "missing" on node B. Tables and file chunks now use `FULL_SYNC` (D-041).
- **An error replacing an unsent file response kept its `Content-Length`**, so the JSON error body overran it and broke the next response on the connection. Error answers now drop the replaced response's entity headers (REST API, ingress, and services).
- `DockerClusterIT` read a restarted service's checkpoint in the moment between the run showing `RUNNING` and the previous checkpoint being seeded. It now waits for the checkpoint first, as `ChaosTest` does.
- `FilesTest` read response bodies after the response had been handed to the test thread, so early chunks were sometimes lost and the test was flaky. Bodies are now read in the same future chain.

### Known
- Stopping a node while another is resolving a binary schema it just sent can stall the survivor's striped thread for up to 30 s (Ignite's metadata timeout). It was seen only during test teardown, and it recovers on its own.

## Phase 3b, milestone 2 — Streaming (2026-10-06)

### Added
- **Streaming HTTP for services.** The ingress now proxies each request **as a stream** to the serving node's new internal endpoint (`AUTOMATELY_INTERNAL_PORT`, protected by a cluster secret), with backpressure both ways. The 1 MB request and response limit is gone (D-036). **Why:** Tony asked for services that can serve large files and stream.
- **Script API:**
  - request bodies are async-iterable streams of `Uint8Array` chunks (`req.body`, plus `bytes()`)
  - responses can be a `Uint8Array`, an (async) iterable such as a generator for server-sent events, or `{status, headers, body}` with any of those
  - writes wait for the client
  - a client disconnecting ends the generator
- **Streaming `fetch`:** `res.body` streams, and `bytes()` was added. `text()` and `json()` buffer up to 5 MB, and the timeout applies to idle gaps rather than the whole download.
- `TextEncoder` and `TextDecoder` (UTF-8) for scripts.
- **Config:** `AUTOMATELY_INTERNAL_PORT` (8090), `AUTOMATELY_ADVERTISE_HOST` (this host's address), `AUTOMATELY_INGRESS_IDLE_TIMEOUT_MS` (60 000), `AUTOMATELY_MAX_REQUEST_BYTES` (1 GiB).
- **Core SPI:** `HttpExchange`, `BodyStream`, `FetchedResponse`, `HostServices.serve` and `HostServices.fetch`.
- **Tests:**
  - runtime: request bodies streamed in chunks, an unread body paused rather than buffered, generator responses waiting for a slow client, disconnect ending the generator, streaming and buffered fetch with its 5 MB cap, cancellation of an unread fetch body, read-once bodies, UTF-8 round trip
  - core: 20 MB streamed fetch, the concurrency limit released when bodies finish
  - `StreamingTest` through a real 2-node ingress: a 48 MB upload checksummed by the script, a 48 MB streamed download, SSE plus cleanup on disconnect, backpressure against a paused client (stops at about 22 MB of kernel socket buffers instead of 250 MB), idle timeout returning 504, 413 over the limit, and the internal endpoint refusing callers without the secret

### Fixed (found by the new tests)
- Backpressure counted chunks only once the isolate processed them, so a stalled script's queue could grow without bound (D-037).
- The ingress's idle timeout ignored request-body progress and cut off long uploads (D-037).
- An early 413 left the connection with an unread body, which hung the next request; such connections are now closed (D-037).
- An idle timeout was reported as 502 instead of 504 (D-037).

### Changed
- Removed: the event-bus HTTP forwarding (`ServeVerticle`, `Addresses.nodeHttp`). Node registry entries carry `endpoint`.

## Phase 3b, milestone 1 — The cluster is the database (2026-10-06)

### Added
- **Apache Ignite 2.18 replaces Hazelcast** as the cluster manager *and* the database (D-030). New module `automately-cluster-ignite`: `IgniteCluster` (bootstrap, durable data region, discovery, safe activation) and Ignite stores for jobs, job state, services, domains and `kv`. They use compare-and-set and indexed SQL, so a tenant's jobs, active jobs and `kv.list` no longer scan everything. **Why:** before this, a restart of every node lost all jobs, services, domains and `kv`. Tony asked for a database built into the cluster.
- **Durability:** `AUTOMATELY_DATA_DIR` (the Dockerfile sets `/var/lib/automately` as a volume, and compose has a volume per node), `AUTOMATELY_DATA_BACKUPS` (1), `AUTOMATELY_DATA_MEMORY_MB` (256), `AUTOMATELY_BASELINE_ADJUST_MS` (60 000).
- **Safe activation after restarts** (D-031), **replicated Vert.x maps** (D-032), **retries on topology changes** (D-033), and **`AUTOMATELY_CLUSTER_SIZE`**: a new durable cluster waits for its initial nodes before activating (D-035).
- **Retention** of finished jobs (`AUTOMATELY_JOB_RETENTION_DAYS`, default 7; D-034), and `GET /v1/jobs?limit=`.
- **Store contract tests** in core (`JobStoreContract`, `KvStoreContract`, `JobStateStoreContract`, `ServiceStoreContract`, `DomainStoreContract`), run against both the in-memory and the Ignite stores. Core publishes a test-jar for this.
- **Tests:**
  - `DurabilityTest`: a 2-node full restart with every store intact, and a 3-node cluster losing a node with no data lost.
  - `FullRestartTest`: every node of a real 3-node Automately cluster stopped and restarted from disk. Finished jobs, `kv` and domains survive, and the service runs again from its checkpoint.
  - `DockerClusterIT`: every container stopped and started (cold start) with the same checks.

### Changed
- `JobStore.list(tenant, limit)` and `deleteFinishedBefore`; `JobStateStore.delete`; `DomainStore` is now an interface (`SharedDataDomainStore` and `IgniteDomainStore`).
- The ArchUnit boundary now keeps **Ignite** (and any cluster-manager SPI) inside `automately.cluster.ignite`.
- Container JVM heap is 40 % (it was 50 %) to leave room for Ignite's off-heap data.
- `AUTOMATELY_CLUSTER_NAME` is gone (it was Hazelcast-specific). Separate clusters use separate ports or members.

### Removed
- Hazelcast (`vertx-hazelcast`, `Clustering`, `ClusteredCompareAndSetTest`).

### Fixed (found while switching)
- Activating on the first returning node made partitions held by other nodes "lost" (D-031).
- A restarting node racing Ignite's auto-activation made Ignite halt the JVM. First start versus restart is now decided from the data directory before Ignite starts (D-031).
- A fresh cluster activated by its first node alone had no replicas for early writes (D-035).
- A `SIGKILL` made the node registry unreadable ("partition has been lost") because Vert.x's caches had no backups (D-032).
- Failover made API reads fail with "Remote node has left topology" (D-033).
- Compare-and-set under heavy contention on one key exhausted its retries; it now backs off with jitter.

## Phase 3 — Host APIs and HTTP hosting (2026-10-06)

### Added
- **Host APIs for scripts** (D-025), each the successor of a 2017 host object (design: `docs/design/host-apis.md`; reference: `docs/scripting/host-apis.md`):
  - `kv`: get, set (with exact `ttlMs`), delete, list (prefix and cursor), atomic `increment`, and namespaces (D-027).
  - `bus`: publish, subscribe, request/handle, tenant-scoped, plus `POST /v1/bus/{topic}` and `/request` from outside.
  - `fetch`: WHATWG-style, with an SSRF-safe egress policy that refuses private and reserved addresses, connects to the checked address, and follows no redirects. Size, time and concurrency limits apply (D-026).
  - `log.{debug,info,warn,error}(message, fields)`: structured lines.
- **HTTP hosting:** `ctx.serve(handler)` in services. Every node runs an ingress (`AUTOMATELY_INGRESS_PORT`, default 8000) that routes by host to the service's current run, on any node (D-029). Every service gets an automatic host `<service>--<tenant>.<AUTOMATELY_INGRESS_DOMAIN>`, and custom domains can be added through `/v1/services/{name}/domains` with DNS TXT verification (D-028). Unknown hosts get 404; a service that's down gets 503 with `Retry-After`; slow handlers get 504; too many requests in flight get 429.
- **Mechanism:** the `HostServices` SPI in core; in the Javet runtime, `HostBridge`, asynchronous calls settled on the event loop, listeners, and cleanup when a run ends.
- **Config:** `AUTOMATELY_INGRESS_PORT`, `AUTOMATELY_INGRESS_DOMAIN`, `AUTOMATELY_EGRESS_ALLOW_PRIVATE`. Docker and compose expose the ingress.
- **Tests (115 → 167 unit, 3 → 4 Docker):**
  - `HostBridgeTest`: parallel calls, typed errors, listeners and replies, cleanup, limits, response normalisation.
  - `SharedDataKvStoreTest` and `NodeHostServicesTest`: tenant isolation, exact expiry, paging, 100 concurrent increments, bus isolation and errors, fetch, and egress refusals for 11 private or reserved targets.
  - `HostApisTest` on a real 2-node cluster: kv across nodes and tenants, a service answering a job and HTTP, pub/sub, fetch refused, ingress on both nodes and over HTTP/2, 404/503, custom domain verification, names.
  - `DockerClusterIT`: HTTP traffic moves to a survivor after the serving node is `SIGKILL`ed.

### Changed
- Tenant and service names can't contain `--`.
- `ScriptRuntime.execute` takes `HostServices`, and `NodeVerticle` takes a `HostServicesFactory`.

### Decided
- No isolate pooling (D-024): measured 6.6 ms p50 per job including isolate creation.

### Fixed (found by the new tests)
- A fetch over the size limit surfaced as a generic network error (the reset fired before the limit was recorded).
- Deleting a service left its verified custom domains routing to the next service with that name.
- The ingress read only the `Host` header; it now routes by authority, which is how HTTP/2 carries the host.

## Phase 2b — Long-running services (2026-10-06)

### Added
- **Services:** named, long-running scripts per tenant that the cluster keeps running (the 2017 "service jobs", made robust). `PUT/GET /v1/services/{name}`, `GET /v1/services`, `POST /v1/services/{name}/start|stop|restart`, `DELETE /v1/services/{name}`, with `restart: always` (default) or `on-failure`. Changing script, config or restart creates a new revision, which is rolled out by stopping the old run. **Why:** this was the main 2017 feature not yet carried over. Design: `docs/design/services.md`; script API: `docs/scripting/services.md`.
- **`ServiceSupervisor`** (leader-only, compare-and-set): records each run's exit, backs off on failures (1 s doubling to 60 s, reset after 60 s of uptime), stops disabled or outdated runs, and claims a run before submitting it so there are never two (D-021). A run whose job never appeared is given up after 10 s.
- **Service context:** `ctx.service` (`name`, `revision`, `run`, `config`, `previousRun`), and `ctx.checkpoint` carried from the previous run.
- **Runtime event loop** (D-019): `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`, `ctx.sleep()`, `ctx.stopRequested`, posted tasks, idle waiting for long-running executions, a timer error failing the run, and isolates on a dedicated executor sized to node capacity.
- **Job cancellation:** `POST /v1/jobs/{id}/cancel`, a `CANCELLED` status, and a durable `cancelRequested` honoured by the node's heartbeat check and by the reaper. Running jobs get a graceful stop with `AUTOMATELY_STOP_GRACE_MS` (D-020).
- `RunningExecution.stop(reason, grace)`, and a node control address for stopping a specific attempt.
- **Config:** `AUTOMATELY_STOP_GRACE_MS` (5000), `AUTOMATELY_MAX_SERVICES_PER_TENANT` (10).
- **Tests (76 → 115 unit, 2 → 3 Docker):**
  - runtime: timers, idle and stop, grace exceeded, cancel while idle, waiting for a stop, `ctx.service`
  - job cancel transitions
  - cancellation through the node, including a lost message and an orphan with a pending cancel
  - `ServiceSupervisorTest`: start, backoff, `on-failure`, stop and start, revision roll, one run at a time, node death including the leader, checkpoint carry-over, a lost claim
  - `ServicesTest` over HTTP on a real 2-node cluster
  - `ChaosTest` and `DockerClusterIT`: a service comes back on a survivor, with its checkpoint, after its node is stopped or `SIGKILL`ed

### Changed
- Stored logs keep the **last** 1 000 lines (D-023).
- Cancelled attempts report `CANCELLED` (updates D-016).
- `Job` transitions are built with an internal editor, and the job JSON gains `cancelRequested` and `service`.

### Fixed (found by the new tests)
- A job awaiting `ctx.stopRequested` failed instantly as "never settles"; it now waits, bounded by its timeout (D-022).

## Phase 2 — Orphaned jobs, attempts and resumable steps (2026-10-06)

### Added
- **Attempts.** A job now records `attempt`, its `attempts` history, `onOrphan` (`fail` by default, or `retry`/`resume`) and `maxAttempts`, plus a new terminal status, `ORPHANED`. **Why:** a dead node's jobs must never re-run silently (D-007).
- **Fenced, compare-and-set transitions.** `Job.started/finished/orphaned/requeued/rejected` reject any attempt or node instance that's no longer current, and `JobStore.update` applies them with `replaceIfPresent`. **Why:** a node that only *looked* dead can't overwrite its replacement (D-011). Compare-and-set on the clustered map was proven first (`ClusteredCompareAndSetTest`).
- **Node instance ids.** Every process start gets a new id; jobs and the registry use it. **Why:** a restarted node with the same name must not hide its old jobs (D-009). Verified in Docker with `docker kill` followed by `docker start`.
- **`JobStateStore`.** Checkpoints and step results, stored per attempt. A resuming attempt copies from the latest earlier attempt, skipping attempts that recorded nothing. Limits: 64 KB per value, 1 MB per job. **Why:** a zombie's writes land in a key nobody reads (D-010).
- **`OrphanReaper`.** After a grace period, it applies each job's policy: `ORPHANED`, or a new attempt dispatched through least-loaded placement. It also re-dispatches attempts no node picked up, under any policy, which is safe because a node records `RUNNING` before running any script.
- **The `ctx` API in the Javet runtime:** `attempt`, `policy`, `isRetry`, `isResume`, `previous`, `checkpoint`, `completedSteps`, `stepKey()`, `saveCheckpoint()`, and `step()` (memoised across resumes), plus the `SupersededError` and `StateLimitError` globals. Spec: `docs/scripting/job-context.md`.
- **Cancellable executions.** `ScriptRuntime.execute` returns a `RunningExecution` with `cancel()`. Nodes cancel attempts they no longer own on each heartbeat.
- **API:** `onOrphan` and `maxAttempts` on submit (validated), `GET /v1/jobs/{id}/steps`, SSE `attempt` events, and `end` sent only when the job is finished. Finished jobs replay their attempts.
- **Config:** `AUTOMATELY_HEARTBEAT_MS`, `AUTOMATELY_ORPHAN_GRACE_MS`, `AUTOMATELY_REQUEUE_AFTER_MS`.
- **Tests (34 → 76):**
  - `JobTest` (transitions)
  - `SharedDataJobStoreTest` (50 concurrent compare-and-set updates lose nothing)
  - `SharedDataJobStateStoreTest`
  - `OrphanHandlingTest`: `fail`, `retry`, `resume`, attempts exhausted, a zombie's result discarded, a zombie's state write rejected, the grace period honoured, a restarted node, a stranded queued job, events
  - `JobContextApi` in the runtime suite
  - `ChaosTest`: three real nodes, the node killed in the middle of a step

### Changed
- **Node liveness** is judged from `lastSeen` within 3 heartbeats; map TTLs are only for cleanup. **Why:** Hazelcast was measured expiring TTL entries up to about 1 s early, which made the registry flicker (D-013).
- **Reaper:** every node tracks suspects and only the leader acts. **Why:** leader changes were resetting the grace period, so orphans could wait forever (D-014).
- **Dispatch:** a failure now lists each node's refusal reason, or the registry's contents when no node had capacity.

### Fixed (found by the chaos test)
- Intermittent failures (about 1 run in 5): spurious "No node has free capacity", and orphaned jobs left `RUNNING` or `QUEUED`. The root causes were D-013 and D-014. After the fix the chaos test passed 12 of 12 runs, and the full build passed 3 of 3.
- Failed orphan and requeue updates were silently dropped. They're now logged.
- The reaper's sweep has a deadline, so a hung cluster call can't stop reaping.
- **The JVM crashed natively (`SIGSEGV`) when a node stopped while a script was running.** The isolate kept executing on a worker thread after Vert.x shut down, and the process died on exit. Tests still passed because they had already reported, but 29 `hs_err_pid*.log` files piled up. `NodeVerticle.stop()` now cancels running isolates and waits up to 10 s for them to exit. It leaves their attempts `RUNNING` so the reaper applies the `onOrphan` policy, the same as after a crash (D-015). Verified: 0 crash reports in 3 chaos runs, against 1 per run before. CI now fails if any crash report appears.

### Docs
- `docs/design/revival-plan.md`: the original plan, now in the repo with a status block.
- `docs/scripting/job-context.md` (implemented), `docs/design/orphaned-jobs.md` (accepted), `docs/design/phase-2-plan.md` (done, with what changed during implementation).

## [2.0.0-SNAPSHOT] — Phase 1 spike (2026-10-06)

A ground-up rewrite of Automately Core (2015–2017) on Vert.x 5 and V8. No 2017 code was copied. The old code was used to understand behaviour such as least-loaded placement, job lifecycle and hostname routing. See [DECISIONS.md](docs/DECISIONS.md) D-001.

### Added
- **Multi-module Maven build** (`automately-core`, `automately-runtime-javet`, `automately-server`) with a Maven wrapper (Maven 3.9.16), targeting JDK 21. **Why:** core can't depend on engines or transport, and the boundaries are enforced (D-006).
- **Job model** (`Job`, `JobStatus`): immutable records stored as JSON. **Why:** replaces Hazelcast `DataSerializable` objects, so data is portable and readable.
- **`JobStore` and `NodeRegistry` interfaces** on Vert.x `SharedData` `AsyncMap`. Nodes heartbeat with a TTL of 3× the interval. **Why:** keeps Hazelcast out of business code (D-004).
- **`JobScheduler`**: least-loaded placement, which tries the next node if one turns a job down because it's full. **Why:** keeps the original `execute_on_least_jobs` behaviour.
- **`NodeVerticle`**: one execution node with capacity limits, live log publishing, and all state confined to its event loop. **Why:** replaces the static `JobServer`, so several nodes can run in one test JVM.
- **`ScriptRuntime` SPI and `JavetScriptRuntime`**: one V8 isolate per job, ES-module scripts, a frozen `console`, wall-clock timeout, and a per-isolate heap limit. **Why:** replaces Nashorn (removed in JDK 15) and the experimental J2V8 factory with real isolation (D-002, D-003).
- **REST API** (`/v1/jobs`, `/v1/jobs/{id}`, `/v1/jobs/{id}/events` over SSE, `/v1/nodes`, `/health`) on the `vertx-web` `Router`, with one error envelope and input validation. **Why:** replaces hand-written regex routing.
- **Hazelcast clustering** in `automately.server.cluster.Clustering`, starting from Vert.x's default config, with TCP/IP join and phone-home disabled.
- **Packaging**: a shaded jar, a multi-stage Dockerfile (non-root user, glibc base), a 3-node `docker-compose.yml` with healthchecks, and GitHub Actions CI.
- **Tests (34)**: the scheduler with two nodes in one Vert.x instance, a hostile-script suite (infinite loops, a memory bomb, global and prototype leakage, host access), a 2-node Hazelcast cluster over HTTP, and ArchUnit rules.
- `LICENSE.md`: MPL-2.0, carried over from the original Automately Core.

### Fixed during the spike
- Javet "callback context not recycled" warnings. The console binding is now released explicitly after each job, because the prelude's closures keep it alive until then.
- Hazelcast rejected a port range in the member list (`127.0.0.1:15701-15705`). A bare host is now used, and Hazelcast probes upwards from `clusterPort`.
- The ArchUnit JUnit 5 engine silently discovered 0 tests. The rules now run as plain parameterised JUnit tests (D-006).
- Containers exited at startup with `Unrecognized option: --add-modules`. JVM flags moved from `JAVA_TOOL_OPTIONS` to `JDK_JAVA_OPTIONS`, which accepts launcher options.
- `POST /v1/jobs` returned 500 in Docker. `BodyHandler` tried to create `file-uploads/` in an unwritable working directory. Uploads are disabled (the API takes no files) and the image has a `WORKDIR`.
- The API's 500 errors were never logged. The failure handler now logs the method, path and cause. **Why:** this hid the bug above, and it's the "opaque operations" weak spot from 2017.
- The compose healthcheck stayed in "starting" because `/dev/tcp` is a bash feature and the check ran under `sh`. It now runs under `bash`.

### Known gaps
- ~~Jobs on a node that dies stay `RUNNING`.~~ Fixed in phase 2.
- The tenant comes from the `X-Automately-Tenant` header. It's a placeholder until phase 4 auth.
- ~~No Testcontainers yet.~~ Added: `DockerClusterIT`.
- The Javet native library is included for linux-x86_64 only.
