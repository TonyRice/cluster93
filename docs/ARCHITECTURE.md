# Cluster93 Architecture & Codebase Navigation Map

**Repository:** `cluster93`  
**License:** MPL-2.0  
**Stack:** Java 21, Vert.x 5.2.0, Apache Ignite 2.18.0, Javet (V8 6.0.2) / GraalJS (25.4.4.1.1)

---

## 1. System Overview & Module Map

Cluster93 is organized into decoupled Maven modules with strict architectural boundaries:

```
                              ┌─────────────────────────┐
                              │     cluster93-cli      │
                              │ (Terminal CLI & Client) │
                              └────────────┬────────────┘
                                           │ HTTP/WS & IPC
                                           ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│     cluster93-tls      │   │    cluster93-server    │
│  (Certs & TLS profiles) │──▶│ (HTTP API, Ingress, WS) │
└─────────────────────────┘   └────────────┬────────────┘
                                           │
                      ┌────────────────────┼────────────────────┐
                      ▼                                         ▼
         ┌─────────────────────────┐               ┌─────────────────────────┐
         │ cluster93-cluster-ignite│              │     cluster93-core     │
         │ (Ignite 2.18 SQL & WAL) │               │(Scheduler, Stores, APIs)│
         └─────────────────────────┘               └────────────┬────────────┘
                                                                │
                                   ┌────────────────────────────┴────────────────────────────┐
                                   ▼                                                         ▼
                      ┌─────────────────────────┐                               ┌─────────────────────────┐
                      │cluster93-runner-protocol│                              │  cluster93-runtime-js  │
                      │  (Wire Codec, Messages, │                               │ (SPI, Bindings, Prelude,│
                      │   RemoteHostApis Proxy) │                               │      ScriptRunner)      │
                      └────────────┬────────────┘                               └────────────┬────────────┘
                                   │ Stdio / UDS Framing                                     │
                                   ▼                                                         ▼
                      ┌─────────────────────────┐                       ┌────────────────────┴────────────────────┐
                      │    cluster93-worker    │                       ▼                                         ▼
                      │(In-Cluster Native Runner│          ┌─────────────────────────┐               ┌─────────────────────────┐
                      │   Javet & GraalJS)      │          │cluster93-runtime-javet │               │cluster93-runtime-graaljs
                      └─────────────────────────┘          │ (V8 Engine Adapter SPI) │               │ (GraalJS Engine Adapter)│
                                                           └─────────────────────────┘               └─────────────────────────┘
```

| Module | Purpose | Key Package / Entry Points |
|---|---|---|
| [`cluster93-core`](../core) | Core domain models, scheduling, storage abstractions, host APIs | `cluster93.core.runtime.api.HostApis`, `cluster93.core.host.NodeHostApis`, `cluster93.core.scheduler.NodeVerticle` |
| [`cluster93-runtime-js`](../runtime-js) | Engine-neutral JS SPI, bindings, prelude scripts | `cluster93.runtime.js.JsEngine`, `ScriptRunner`, `HostFunction` |
| [`cluster93-runtime-javet`](../runtime-javet) | V8 engine adapter using Javet | `cluster93.runtime.javet.JavetEngine` |
| [`cluster93-runtime-graaljs`](../runtime-graaljs) | GraalJS polyglot engine adapter | `cluster93.runtime.graaljs.GraalJsEngine` |
| [`cluster93-runner-protocol`](../runner-protocol) | IPC binary frame codec & `RemoteHostApis` | `cluster93.runner.protocol.frame.FrameCodec`, `RemoteHostApis` |
| [`cluster93-worker`](../worker) | Subprocess & native-image isolate runner | `cluster93.worker.WorkerMain` |
| [`cluster93-cluster-ignite`](../cluster-ignite) | Apache Ignite 2.18 persistence & clustering | `cluster93.cluster.ignite.IgniteClusterManager` |
| [`cluster93-server`](../server) | Ingress reverse proxy, REST APIs, WebSocket routing | `cluster93.server.Cluster93`, `IngressVerticle` |
| [`cluster93-cli`](../cli) | Command-line client | `cluster93.cli.CliMain` |
| [`cluster93-tls`](../tls) | TLS helper utilities and certificates | `cluster93.tls.TlsConfig` |

---

## 2. Host APIs (`HostApis`) Location & Mechanics

The primary interface defining what sandboxed JavaScript code can invoke is [`cluster93.core.runtime.api.HostApis`](../core/src/main/java/cluster93/core/runtime/api/HostApis.java).

```
HostApis (Interface in cluster93-core)
 ├── kv()       ──▶ KvApi         (Namespaced key-value storage with TTL)
 ├── bus()      ──▶ BusApi        (Pub/sub & request-reply messaging)
 ├── queue()    ──▶ QueueApi      (Durable background job queues)
 ├── jobs()     ──▶ JobsApi       (Parent/child job execution triggers)
 ├── files()    ──▶ FilesApi      (Blob storage & file streams)
 ├── fs()       ──▶ FsApi         (Virtual File System with POSIX-like operations)
 ├── fetch()    ──▶ FetchApi      (Outbound HTTP requests governed by egress gate)
 ├── sockets()  ──▶ SocketApi     (Outbound TCP/TLS socket connections)
 ├── dns()      ──▶ DnsApi        (Egress-checked DNS query resolution)
 ├── secrets()  ──▶ SecretsApi    (Authorized tenant secrets retrieval)
 ├── http()     ──▶ HttpApi       (Service HTTP listening & response streaming)
 ├── crypto()   ──▶ CryptoApi     (SHA, HMAC, AES, randomBytes)
 └── zlib()     ──▶ ZlibApi       (Gzip, Deflate, Brotli compression)
```

### Implementations:
1. **Node Authority Implementation:**
   - [`cluster93.core.host.NodeHostApis`](../core/src/main/java/cluster93/core/host/NodeHostApis.java)
   - Backed by the cluster stores (`KvStore`, `FileStore`, `ServeRegistry`, `FetchClient`, `FileTree`).
   - Tenant-scoped: the Node resolves the tenant from internal records.
2. **Worker Remote Proxy:**
   - [`cluster93.runner.protocol.client.RemoteHostApis`](../runner-protocol)
   - Dispatches RPC `CALL` frames over binary IPC pipes for authority-requiring operations.
   - Executes `crypto()` and `zlib()` locally in-process without network overhead.
3. **No-op / Test Fallback:**
   - [`cluster93.core.runtime.api.Unavailable`](../core/src/main/java/cluster93/core/runtime/api/Unavailable.java) (`HostApis.NONE`).

---

## 3. JavaScript Runtime & Binding Layer

Guest code executed inside Javet or GraalJS interacts with the host via [`cluster93-runtime-js`](../runtime-js):

1. **Engine SPI:**
   - [`JsEngine`](../runtime-js/src/main/java/cluster93/runtime/js/JsEngine.java): Lifecycle factory for creating contexts.
   - [`JsContext`](../runtime-js/src/main/java/cluster93/runtime/js/JsContext.java): Represents one sandboxed isolate.
   - [`ScriptRunner`](../runtime-js/src/main/java/cluster93/runtime/js/ScriptRunner.java): Coordinates the event loop, execution timeouts, cancellation tokens, and log/state sinks.
2. **Host Bindings:**
   - Methods annotated with `@HostFunction` exposed directly to the isolate:
     - `KvBinding`, `BusBinding`, `QueueBinding`, `JobsBinding`, `FilesBinding`, `FsBinding`
     - `FetchBinding`, `SocketBinding`, `HttpBinding`, `Http2Binding`, `DnsBinding`, `SecretsBinding`
     - `CryptoBinding`, `ZlibBinding`
3. **Prelude & Built-in Modules:**
   - Standard JS prelude scripts in `src/main/resources/cluster93/runtime/js/`:
     - `globals.js`, `console.js`, `context.js`, `timers.js`, `entry.js`
     - `node/*`: Node.js compatibility layer (`node:crypto`, `node:buffer`, `node:events`, `node:stream`, `node:fs`, `node:net`, `node:http`, etc.)
     - `web/*`: Web standards (`fetch`, `ReadableStream`, `Blob`, `WebSocket`)

---

## 4. In-Cluster Worker ↔ Node Protocol

When executing jobs in out-of-process workers (native binaries `worker-javet` / `worker-graaljs` or gVisor containers):

```
Frame Header (12 Bytes)
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Frame Length                          |  (u32: total bytes following, max 1 MiB)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Frame Type  |     Flags     |          (Reserved)           |  (u8 type, u8 flags)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Channel ID                           |  (u32: 0 for control; else run/call/stream ID)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          JSON Length                          |  (u32: length of JSON payload)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     JSON Payload (UTF-8)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Contiguous Raw Binary Buffers                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Authority Partitioning
- **Worker (Compute Domain):** Polyfills, JS event loop, user code execution, pure crypto, zlib, text encoding, and the **egress gate** — the decision is the worker's in the default placement (D-173).
- **Node (Authority Domain):** KV persistence, VFS file storage, tenant secrets, HTTP ingress, durable job queues, and the **egress rules and budgets** the worker enforces.

### Multiplexing & Flow Control (`StreamMultiplexer`)
- Streaming channels (file downloads/uploads, HTTP streaming request/response bodies, TCP/TLS sockets) multiplex over the shared framed connection via `0x30 OPEN`, `0x31 DATA`, `0x32 END`, `0x33 RESET`, and `0x34 CREDIT`.
- Governed by HTTP/2-style flow control windows via [`CreditManager`](../runner-protocol/src/main/java/cluster93/runner/protocol/flow/CreditManager.java): 256 KiB initial stream window, 4 MiB global connection limit.
- Senders slice writes into 64 KiB chunks and pause on window depletion. Receivers automatically emit `CREDIT` replenishment frames once 16 KiB or more of data has been consumed.

### Warm Worker Pool & Memory Isolation (`TenantWorkerPool`)
- [`TenantWorkerPool`](../runner-protocol/src/main/java/cluster93/runner/protocol/pool/TenantWorkerPool.java) maintains a pool of pre-spawned worker processes in `UNBOUND` state, eliminating the 40–800 ms process startup latency for incoming jobs.
- Binds workers to tenants on demand via `BIND` (`UNBOUND` -> `BOUND` -> `BUSY` -> `BOUND` -> `TERMINATED`).
- Enforces Linux cgroups v2 memory bounds ([`CgroupMemoryLimiter`](../runner-protocol/src/main/java/cluster93/runner/protocol/pool/CgroupMemoryLimiter.java)) by assigning worker PIDs to `/sys/fs/cgroup/cluster93/worker-<pid>` with `memory.max` and `memory.high` caps, and detecting kernel OOM kills via `memory.events`. Gracefully falls back to heap options in unprivileged environments.

### Non-Blocking Domain Socket Transport (`UdsServerTransport`, `UdsClientTransport`)
- Supports high-throughput Unix Domain Sockets over filesystem `.sock` nodes via Netty domain socket transport (`SocketAddress.domainSocketAddress`).
- Vert.x `NetSocket` implements both `ReadStream<Buffer>` and `WriteStream<Buffer>` natively, avoiding virtual thread standard I/O bridging overhead for container and local worker IPC.

---

## 5. Storage & Cluster Architecture

- **Database Engine:** Embedded Apache Ignite 2.18 (`cluster93-cluster-ignite`).
- **Data Region:** Off-heap pages with WAL (write-ahead log) persisted under `C93_DATA_DIR`.
- **Tables:** Replicated across cluster nodes with Calcite SQL querying.
- **Blob Storage:** [`BlobStore`](../core/src/main/java/cluster93/core/files/BlobStore.java) implementations for chunked disk storage, S3 object storage, or in-memory testing.

---

## 6. How to Build & Generate Documentation

- **Build touched modules:**
  ```bash
  ./mvnw clean test-compile -DskipITs
  ```
- **Run targeted tests:**
  ```bash
  ./mvnw test -pl runtime-javet -Dtest=ScriptRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Generate full aggregate Javadocs:**
  ```bash
  ./mvnw javadoc:aggregate -DskipITs
  ```
  *(Output available at `target/reports/apidocs/index.html`)*

---

## 7. Agent Context Optimization & Javadoc Rules

When reading or modifying Cluster93 code, follow the guidelines detailed in [Agent Context Optimization & Javadoc Engineering Guide](guides/agent-context-and-javadocs.md):

1. **High Information Density at a Fraction of the Token Cost:**
   - Reading full `.java` implementation files costs 4,000–10,000 tokens for boilerplate, stream pipelines, and logging.
   - Reading clean interface headers/skeletons with Javadocs delivers exact contracts, invariants, and error modes in under 300 tokens, allowing an agent to hold ~20 class contracts in context for the cost of two implementation files.
2. **Tags That Prevent Agent Hallucinations:**
   - `@throws` / `@exception`: Explicit checked/unchecked error modes prevent incorrect exception handling or wrapping.
   - `@param` & `@return`: Nullability and range contracts eliminate null-handling hallucinations.
   - `@see` & `@link`: Semantic trails that guide symbol navigation and LSP queries.
3. **Context-Friendly Javadoc Usage:**
   - **Never ingest raw HTML Javadoc builds:** Avoid dumping `target/reports/apidocs/*.html` into LLM contexts.
   - **Extract skeletons/headers:** Pass interface definitions and method signatures with docstrings rather than complete class bodies.
   - **Leverage LSP On Demand:** In harnesses using `jdtls`, rely on symbol hover and signature help to fetch docstrings dynamically in real time.

