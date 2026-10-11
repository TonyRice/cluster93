# Testing Automately

This manual is the developer and operator guide to testing Automately end to end:
the unit and integration suites for the clustered runtime, the out-of-process
worker protocol, and the GraalVM native-image workers.

It is written to match how the project is actually verified locally. There is no
CI: `./mvnw verify` on a developer machine is the gate.

---

## 1. Testing Philosophy & Architectural Boundaries

### 1.1 Test the whole architecture, not just the mocks

A feature is **never** complete because unit tests with mocks pass. Every
protocol, runtime layer, and engine integration must be stood up as a real
system and exercised end to end:

- Real Vert.x event loops and worker pools, not stubbed `Future`s.
- Real worker processes over a real transport (Unix domain socket), not an
  in-memory pipe, when the layer under test is the IPC path.
- Real guest JavaScript running on **both** supported engines: **Javet (V8)** and
  **GraalJS**. An engine-neutral feature is only verified when both engines pass.
- Real cluster state (Apache Ignite) for persistence and failover behaviour.

Mock-only tests are useful for isolating a calculation (for example the credit
accountant), but they do not demonstrate that the assembled system works.

### 1.2 Respect the architectural boundaries

Tests are also the enforcement mechanism for the module layering. Keep these
invariants in view:

- `automately-core` depends **only** on `vertx-core`. It must not pull in Ignite,
  Netty internals beyond Vert.x, or any engine adapter. `ArchitectureTest` in
  `automately-server` guards the dependency rules.
- Apache Ignite is strictly isolated to `automately-cluster-ignite`. Nothing else
  may import Ignite types.
- Worker isolates are a **zero-trust, out-of-process boundary**. The node never
  trusts a frame from a worker: tenant identity is server-injected, method calls
  are checked against an allowlist, and frame sizes and credit windows are
  enforced. Tests for this path (`NodeRpcAuthorizerTest`, `NodeWorkerChannelTest`)
  should assert that violations are rejected, not merely that the happy path
  works.

### 1.3 No hand-rolled threads, no mutable static state

Production code (and therefore test harnesses that stand it up) relies on Vert.x
event loops and worker pools. If a test needs concurrency, drive it through
Vert.x futures rather than spawning raw threads.

---

## 2. Standard Workflow Cadence

Follow this order: tight iteration first, full verification before committing.

### 2.1 Iterative testing (targeted classes)

While developing, run only the classes you touched. `-Dsurefire.failIfNoSpecifiedTests=false`
prevents Surefire from failing a module that has no matching tests:

```bash
./mvnw test -pl <module> -am \
  -Dtest=FrameCodecTest,CreditManagerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

### 2.2 Pre-commit verification (touched modules, reactor-aware)

Before committing, run `verify` for every module you touched **and always pass
`-am`** so reactor dependencies are built rather than resolved from a stale
installed snapshot:

```bash
./mvnw verify -pl automately-runner-protocol,automately-server -am -DskipITs
```

- `verify` runs the full lifecycle including integration-test hooks; `-DskipITs`
  skips the docker-backed integration tests during ordinary pre-commit runs.
- Prefer touching the narrowest module set; a broad `./mvnw verify` is fine for a
  final check but is slow.

### 2.3 Docker cluster tests are opt-in

**Never** run `DockerClusterIT` unless Tony explicitly requests it. It spins up a
full docker cluster and is not part of the normal loop. When running a broad
package pattern, always exclude it:

```bash
./mvnw test -pl automately-server -am \
  -Dtest='automately.server.**,!DockerClusterIT' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

### 2.4 Crash-log audit (mandatory after native/V8 test runs)

V8 and other native runtimes can segfault **silently after** a test has already
passed, leaving a JVM crash artifact behind. After any run that touched native
code, always check for and remove crash logs:

```bash
find . -name 'hs_err_pid*.log'
```

Treat any `hs_err_pid*.log` as a failure that must be investigated before
committing, even if the Maven build reported success. Remove stale logs once
understood so the next audit is clean.

### 2.5 Pre-commit checklist

1. Touched modules pass `./mvnw verify -pl <modules> -am -DskipITs`.
2. Both engines exercised for engine-neutral changes.
3. `find . -name 'hs_err_pid*.log'` returns nothing.
4. Documentation updated in the same commit (see `GEMINI.md` — for this work
   order the target files are `scripts/build-workers.sh`,
   `benchmarks/engines/build.sh`, and this file only).

---

## 3. Runner Protocol & Engine Test Suite (`automately-runner-protocol`)

This module is the out-of-process worker-node IPC contract: 12-byte binary
framing, credit-based flow control, stream multiplexing, the warm worker pool
with cgroups v2 limits, zero-trust RPC authorization, and the Netty Unix domain
socket transport.

Run the whole suite:

```bash
./mvnw test -pl automately-runner-protocol -Dsurefire.failIfNoSpecifiedTests=false
```

If module dependencies are not already installed in the local repository, add
`-am`:

```bash
./mvnw test -pl automately-runner-protocol -am -Dsurefire.failIfNoSpecifiedTests=false
```

### 3.1 Coverage breakdown

| Test class | Layer | What it proves |
| --- | --- | --- |
| `frame/FrameCodecTest` | Binary framing | Encodes/decodes the **12-byte header** (type, flags, channel id, JSON length, binary length), survives chunked/split reads with reassembly, and enforces the **1 MiB `MAX_FRAME_LENGTH`** bound on both encode and decode. |
| `flow/CreditManagerTest` | Flow control | Credit accountant for the **256 KiB per-stream** initial window and the **4 MiB per-connection** cap: `openStream`, `canSend`/`tryConsume`, add-back of credits, and per-stream teardown without leaking connection credit. |
| `stream/StreamMultiplexerTest` | Stream multiplexing | Bidirectional streams over one connection: `OPEN`, `DATA`, `END`, `RESET`, and `CREDIT` frames, backpressure through the credit window, and multiple concurrent channels. |
| `pool/TenantWorkerPoolTest` | Warm worker pool | `WorkerProcessHandle` state machine **UNBOUND → BOUND → BUSY → released**, pool invariants, and `CgroupMemoryLimiter` applying **Linux cgroups v2** `MemoryMax`/`MemoryHigh` (with graceful fallback when the cgroup path is restricted or absent). Includes OOM/kill detection. |
| `server/NodeRpcAuthorizerTest` | Zero-trust RPC | Method allowlists and tenant scoping: the node rejects calls outside the policy, denies cross-tenant access, and surfaces `FORBIDDEN` rather than dispatching. |
| `server/NodeWorkerChannelTest` | Channel handshake | `HELLO`/`WELCOME` handshake, server-injected tenant binding, `BIND` sequencing, and dispatch/result routing over a duplex pipe. |
| `transport/uds/UdsTransportTest` | Transport | Non-blocking Netty **Unix domain socket** server/client: socket-file creation, connect, frame round-trip, and clean teardown. Skipped on platforms without UDS support. |
| `WorkerEngineIntegrationTest` | Full architecture | Stands up UDS transport + `NodeWorkerChannel` + `WorkerDaemon` + `RemoteHostApis` and runs **real guest JavaScript** on both engines: a Javet (V8) isolate, a GraalJS polyglot isolate, and **streaming `files.write` uploads** with kv access and an authorizer-denied `jobs.get` call. |

### 3.2 Notes for native/V8 runs

- `WorkerEngineIntegrationTest` instantiates real `JavetScriptRuntime` and
  `GraalJsScriptRuntime`; it extracts V8 and emits crash logs on failure, so the
  §2.4 crash audit applies after running it.
- The UDS tests are skipped automatically on Windows; on Linux and macOS they
  run for real.

---

## 4. Server Runtime Testing (`automately-server`)

The server can execute jobs in two worker modes, selected by
`AUTOMATELY_WORKER_MODE`. Both must be exercised for a worker-facing change.

### 4.1 Embedded mode (in-process isolate, default)

Workers run as in-process isolates inside the server JVM. This is the fastest
loop and the default (`AUTOMATELY_WORKER_MODE=embedded`):

```bash
AUTOMATELY_WORKER_MODE=embedded ./mvnw test -pl automately-server -am \
  -Dtest='automately.server.**,!DockerClusterIT' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Relevant suites include `JobsFromScriptsTest`, `ServicesTest`, `HostApisTest`,
`StreamingTest`, `FilesTest`, `ClusterTest`, and the boundary-enforcing
`ArchitectureTest`.

### 4.2 Process mode (out-of-process native worker)

Workers run as separate native processes managed by the tenant pool, talking over
UDS. Point the server at a built binary with `AUTOMATELY_WORKER_BINARY`
(see §5 to build one). Run the process-mode job suite:

```bash
AUTOMATELY_WORKER_MODE=process \
AUTOMATELY_WORKER_BINARY=bin/worker-javet \
./mvnw test -pl automately-server -am \
  -Dtest='automately.server.JobsFromScriptsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The engine is chosen with `AUTOMATELY_WORKER_ENGINE` (`javet` or `graaljs`), and
per-profile binaries with `AUTOMATELY_WORKER_BINARY_<MODE>`. Verify a
worker-facing change in process mode for **both** engines before considering it
complete.

---

## 5. Native Worker Compilation

Native workers are built by the canonical script `scripts/build-workers.sh`,
which supersedes the old `benchmarks/engines/build.sh` logic (that file is now a
thin wrapper delegating here).

### 5.1 Prerequisites

- **GraalVM JDK 25+** (Oracle GraalVM or GraalVM Community). The script discovers
  it from `GRAALVM_HOME`, then `JAVA_HOME`, then the SDKMAN `current` candidate,
  then the highest installed SDKMAN `*graal*` candidate, then `native-image` on
  `PATH`. It prints the resolved path, edition, and version.
- **Linux with cgroup v2 and `systemd-run --user`** for memory-capped builds
  (optional: without it, Maven runs directly under a capped heap).
- Leave roughly 9 GB free; building GraalJS into an image is memory-hungry.

### 5.2 Building

```bash
# Both workers (default):
scripts/build-workers.sh

# One engine:
scripts/build-workers.sh javet
scripts/build-workers.sh graaljs

# Regenerate Javet's reflection metadata first (after a Javet upgrade):
scripts/build-workers.sh javet --javet-metadata
```

For each engine the script runs (from the repo root):

```bash
./mvnw -q -DskipTests -DskipITs -pl automately-worker -am install -P<engine>
./mvnw -q -DskipTests -DskipITs -pl automately-worker clean package \
  -P<engine>,native \
  -Dnative.builder.heap=<heap> -Dnative.builder.threads=<threads>
```

producing `automately-worker/target/worker-<engine>`.

### 5.3 Outputs

- `bin/worker-javet`, `bin/worker-graaljs` — canonical, executable.
- `benchmarks/engines/bin/worker-<engine>` — synced copies so the engine
  comparison (`benchmarks/engines/run.sh`) runs unchanged.
- `bin/build-metadata.json` and `benchmarks/engines/bin/build-metadata.json` —
  GraalVM home, edition, version, and build timestamp.

### 5.4 Tuning (environment variables)

| Variable | Default | Meaning |
| --- | --- | --- |
| `BUILD_CAP` | `16G` | Kernel memory cap applied to each build via `systemd-run --user --scope -p MemoryMax=… -p MemorySwapMax=0`. |
| `NATIVE_HEAP` | `12g` | native-image builder heap (`-Dnative.builder.heap`). GraalJS needs several GB. |
| `NATIVE_THREADS` | `2` | native-image builder parallelism (`-Dnative.builder.threads`); fewer threads hold fewer graphs at once. |
| `ENGINES` | _(unset)_ | Legacy space-separated engine list (`ENGINES="javet graaljs"`), honoured when no positional target is given. |

### 5.5 Backward compatibility

`benchmarks/engines/build.sh` delegates to `scripts/build-workers.sh`, preserving
its arguments and forwarding the environment. Existing invocations keep working:

```bash
cd benchmarks/engines
./build.sh                      # both workers
ENGINES="graaljs" ./build.sh    # GraalJS only
./build.sh javet                # positional form also accepted
```

---

## 6. Quick Reference

```bash
# Targeted class iteration
./mvnw test -pl automately-runner-protocol -Dtest=FrameCodecTest -Dsurefire.failIfNoSpecifiedTests=false

# Full protocol + engine suite
./mvnw test -pl automately-runner-protocol -Dsurefire.failIfNoSpecifiedTests=false

# Pre-commit verification of touched modules (always -am)
./mvnw verify -pl automately-runner-protocol,automately-server -am -DskipITs

# Server suite, embedded mode, docker IT excluded
AUTOMATELY_WORKER_MODE=embedded ./mvnw test -pl automately-server -am \
  -Dtest='automately.server.**,!DockerClusterIT' -Dsurefire.failIfNoSpecifiedTests=false

# Server suite, process mode against a native worker
AUTOMATELY_WORKER_MODE=process AUTOMATELY_WORKER_BINARY=bin/worker-javet \
  ./mvnw test -pl automately-server -am -Dtest='automately.server.JobsFromScriptsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Build native workers
scripts/build-workers.sh [javet|graaljs|all]

# Mandatory crash-log audit after native/V8 runs
find . -name 'hs_err_pid*.log'
```
