# Testing Cluster93

This manual is the developer and operator guide to testing Cluster93 end to end:
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

- `cluster93-core` depends **only** on `vertx-core`. It must not pull in Ignite,
  Netty internals beyond Vert.x, or any engine adapter. `ArchitectureTest` in
  `cluster93-server` guards the dependency rules, and `WorkerClasspathIsolationTest`
  in `cluster93-worker` guards the one classpath that test cannot see: no Ignite
  (nor Hazelcast, nor the Vert.x Ignite cluster manager) may reach a worker through
  `core`, `runner-protocol` or `runtime-js`.
- Apache Ignite is strictly isolated to `cluster93-cluster-ignite`. Nothing else
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
./mvnw verify -pl runner-protocol,server -am -DskipITs
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
./mvnw test -pl server -am \
  -Dtest='cluster93.server.**,!DockerClusterIT' \
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

## 3. Runner Protocol & Engine Test Suite (`cluster93-runner-protocol`)

This module is the out-of-process worker-node IPC contract: 12-byte binary
framing, credit-based flow control, stream multiplexing, the warm worker pool
with cgroups v2 limits, zero-trust RPC authorization, and the Netty Unix domain
socket transport.

Run the whole suite:

```bash
./mvnw test -pl runner-protocol -Dsurefire.failIfNoSpecifiedTests=false
```

If module dependencies are not already installed in the local repository, add
`-am`:

```bash
./mvnw test -pl runner-protocol -am -Dsurefire.failIfNoSpecifiedTests=false
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

### 3.3 The engine conformance suite (`runtime-js` on V8 and GraalJS)

`cluster93-runtime-js` holds the engine-neutral tests — the scripting surface, host APIs, `node:*` stand-ins,
`ctx.serve`, packages, queues — and **skips its own surefire** (`<skip>true</skip>`): they are packaged as a
test-jar and run by the engine modules, which scan it.

```bash
./mvnw -o -pl runtime-javet test      # the suite on V8 (expect 137 tests)
./mvnw -o -pl runtime-graaljs test    # the suite on GraalJS (expect 137 tests)
```

`EngineUnderTest` (in the test-jar) is provided by each engine module through `META-INF/services`, and the
engine module's `<dependenciesToScan>` names that test-jar — so **the scan must use the current groupId**
(`cluster93:cluster93-runtime-js`, not the pre-rebrand `c93:`). With the wrong groupId the scan matches
nothing: surefire reports `Tests run: 0` and the build stays green without the suite, which is exactly what
happened for a while. Check the count when touching those two poms.

---

## 4. Server Runtime Testing (`cluster93-server`)

The server executes guest workloads in two worker modes, configured by
`C93_WORKER_MODE` (`process` or `embedded`). Out-of-process execution
is the production default (`C93_WORKER_MODE=process`), with embedded JVM
isolates retained as a development and regression fallback. Both must be exercised
for worker-facing changes.

### 4.1 Process mode (out-of-process worker, production default)

In process mode, guest JavaScript runs inside separate OS worker processes supervised
by `TenantWorkerPoolManager` and isolated by Linux cgroups v2. Workers communicate
with the node over a framed duplex IPC channel with 256 KiB stream flow control and
4 MiB connection windows.

Run the full server suite in process mode (excluding `DockerClusterIT`):

```bash
C93_WORKER_MODE=process ./mvnw test -pl server -am \
  -Dtest='cluster93.server.**,!DockerClusterIT' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

When no native worker binary is built, the server automatically discovers and spawns
a worker JVM process with `WorkerMain` on the current classpath. To test against
a pre-compiled GraalVM native-image worker (see §5):

```bash
C93_WORKER_MODE=process \
C93_WORKER_BINARY=bin/worker-javet \
./mvnw test -pl server -am \
  -Dtest='cluster93.server.**,!DockerClusterIT' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The guest engine is configured with `C93_WORKER_ENGINE` (`javet` or `graaljs`),
or per-profile binaries with `C93_WORKER_BINARY_<MODE>`.

### 4.2 Multi-node cluster end-to-end parity (`ProcessModeParityTest`)

To verify end-to-end cluster parity across separate machines where Node A proxies
requests to a worker running on Node B:

```bash
./mvnw test -pl server -am \
  -Dtest='ProcessModeParityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

This suite spins up a real two-node Apache Ignite cluster (`nodeA` and `nodeB`) in
process mode and verifies:
1. **Cross-Node Ingress Routing (`servesHttp`):** A long-running service (`ctx.serve`)
   hosted on one node is reachable through the public HTTP ingress of **both** Node A
   and Node B via cluster forwarding (`InternalHttpVerticle`).
2. **Outbound Egress & Sockets:** Child processes successfully `fetch()` and open raw
   TCP sockets (`net.connect`) through the node's egress security gate.
3. **Dynamic Packages:** A job imports a dynamically published package (`parity-strings`).
4. **Inter-Service Bus RPC:** A worker process answers `bus.request()` via `bus.handle()`.
5. **Worker Crash Resilience:** Segfaults / SIGKILL are caught immediately, and in-flight
   promises fail without hanging.

### 4.3 Embedded mode (in-process isolate regression testing)

In embedded mode, workers run as in-process Javet or GraalJS isolates inside the server
JVM. Run the embedded suite to ensure zero regressions:

```bash
C93_WORKER_MODE=embedded ./mvnw test -pl server -am \
  -Dtest='cluster93.server.**,!DockerClusterIT' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

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
./mvnw -q -DskipTests -DskipITs -pl worker -am install -P<engine>
./mvnw -q -DskipTests -DskipITs -pl worker clean package \
  -P<engine>,native \
  -Dnative.builder.heap=<heap> -Dnative.builder.threads=<threads>
```

producing `worker/target/worker-<engine>`.

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
./mvnw test -pl runner-protocol -Dtest=FrameCodecTest -Dsurefire.failIfNoSpecifiedTests=false

# Full protocol + engine suite
./mvnw test -pl runner-protocol -Dsurefire.failIfNoSpecifiedTests=false

# Pre-commit verification of touched modules (always -am)
./mvnw verify -pl runner-protocol,server -am -DskipITs

# Server suite, process mode (production default, docker IT excluded)
C93_WORKER_MODE=process ./mvnw test -pl server -am \
  -Dtest='cluster93.server.**,!DockerClusterIT' -Dsurefire.failIfNoSpecifiedTests=false

# Full process-mode parity & cluster ingress suite
C93_WORKER_MODE=process ./mvnw test -pl server -am \
  -Dtest='ProcessModeParityTest,McpTest,JobsFromScriptsTest,ServicesTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Server suite, embedded mode (regression check, docker IT excluded)
C93_WORKER_MODE=embedded ./mvnw test -pl server -am \
  -Dtest='cluster93.server.**,!DockerClusterIT' -Dsurefire.failIfNoSpecifiedTests=false

# Server suite, process mode against a native worker binary
C93_WORKER_MODE=process C93_WORKER_BINARY=bin/worker-javet \
  ./mvnw test -pl server -am -Dtest='cluster93.server.**,!DockerClusterIT' \
  -Dsurefire.failIfNoSpecifiedTests=false

# Build native workers
scripts/build-workers.sh [javet|graaljs|all]

# Mandatory crash-log audit after native/V8 runs
find . -name 'hs_err_pid*.log'
```
