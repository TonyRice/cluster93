# Automately Architecture & Codebase Navigation Map

**Repository:** `automately-v2`  
**License:** MPL-2.0  
**Stack:** Java 21, Vert.x 5.2.0, Apache Ignite 2.18.0, Javet (V8 6.0.2) / GraalJS (25.4.4.1.1)

---

## 1. System Overview & Module Map

Automately is organized into decoupled Maven modules with strict architectural boundaries:

```
                              ┌─────────────────────────┐
                              │     automately-cli      │
                              │ (Terminal CLI & Client) │
                              └────────────┬────────────┘
                                           │ HTTP/WS & IPC
                                           ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│     automately-tls      │   │    automately-server    │
│  (Certs & TLS profiles) │──▶│ (HTTP API, Ingress, WS) │
└─────────────────────────┘   └────────────┬────────────┘
                                           │
                      ┌────────────────────┼────────────────────┐
                      ▼                                         ▼
         ┌─────────────────────────┐               ┌─────────────────────────┐
         │ automately-cluster-ignite│              │     automately-core     │
         │ (Ignite 2.18 SQL & WAL) │               │(Scheduler, Stores, APIs)│
         └─────────────────────────┘               └────────────┬────────────┘
                                                                │
                                   ┌────────────────────────────┴────────────────────────────┐
                                   ▼                                                         ▼
                      ┌─────────────────────────┐                               ┌─────────────────────────┐
                      │automately-runner-protocol│                              │  automately-runtime-js  │
                      │  (Wire Codec, Messages, │                               │ (SPI, Bindings, Prelude,│
                      │   RemoteHostApis Proxy) │                               │      ScriptRunner)      │
                      └────────────┬────────────┘                               └────────────┬────────────┘
                                   │ Stdio / UDS Framing                                     │
                                   ▼                                                         ▼
                      ┌─────────────────────────┐                       ┌────────────────────┴────────────────────┐
                      │    automately-worker    │                       ▼                                         ▼
                      │(In-Cluster Native Runner│          ┌─────────────────────────┐               ┌─────────────────────────┐
                      │   Javet & GraalJS)      │          │automately-runtime-javet │               │automately-runtime-graaljs
                      └─────────────────────────┘          │ (V8 Engine Adapter SPI) │               │ (GraalJS Engine Adapter)│
                                                           └─────────────────────────┘               └─────────────────────────┘
```

| Module | Purpose | Key Package / Entry Points |
|---|---|---|
| [`automately-core`](../automately-core) | Core domain models, scheduling, storage abstractions, host APIs | `automately.core.runtime.api.HostApis`, `automately.core.host.NodeHostApis`, `automately.core.scheduler.NodeVerticle` |
| [`automately-runtime-js`](../automately-runtime-js) | Engine-neutral JS SPI, bindings, prelude scripts | `automately.runtime.js.JsEngine`, `ScriptRunner`, `HostFunction` |
| [`automately-runtime-javet`](../automately-runtime-javet) | V8 engine adapter using Javet | `automately.runtime.javet.JavetEngine` |
| [`automately-runtime-graaljs`](../automately-runtime-graaljs) | GraalJS polyglot engine adapter | `automately.runtime.graaljs.GraalJsEngine` |
| [`automately-runner-protocol`](../automately-runner-protocol) | IPC binary frame codec & `RemoteHostApis` | `automately.runner.protocol.frame.FrameCodec`, `RemoteHostApis` |
| [`automately-worker`](../automately-worker) | Subprocess & native-image isolate runner | `automately.worker.WorkerMain` |
| [`automately-cluster-ignite`](../automately-cluster-ignite) | Apache Ignite 2.18 persistence & clustering | `automately.cluster.ignite.IgniteClusterManager` |
| [`automately-server`](../automately-server) | Ingress reverse proxy, REST APIs, WebSocket routing | `automately.server.Automately`, `IngressVerticle` |
| [`automately-cli`](../automately-cli) | Command-line client | `automately.cli.CliMain` |
| [`automately-tls`](../automately-tls) | TLS helper utilities and certificates | `automately.tls.TlsConfig` |

---

## 2. Host APIs (`HostApis`) Location & Mechanics

The primary interface defining what sandboxed JavaScript code can invoke is [`automately.core.runtime.api.HostApis`](../automately-core/src/main/java/automately/core/runtime/api/HostApis.java).

```
HostApis (Interface in automately-core)
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
   - [`automately.core.host.NodeHostApis`](../automately-core/src/main/java/automately/core/host/NodeHostApis.java)
   - Backed by the cluster stores (`KvStore`, `FileStore`, `ServeRegistry`, `FetchClient`, `FileTree`).
   - Tenant-scoped: the Node resolves the tenant from internal records.
2. **Worker Remote Proxy:**
   - [`automately.runner.protocol.client.RemoteHostApis`](../automately-runner-protocol)
   - Dispatches RPC `CALL` frames over binary IPC pipes for authority-requiring operations.
   - Executes `crypto()` and `zlib()` locally in-process without network overhead.
3. **No-op / Test Fallback:**
   - [`automately.core.runtime.api.Unavailable`](../automately-core/src/main/java/automately/core/runtime/api/Unavailable.java) (`HostApis.NONE`).

---

## 3. JavaScript Runtime & Binding Layer

Guest code executed inside Javet or GraalJS interacts with the host via [`automately-runtime-js`](../automately-runtime-js):

1. **Engine SPI:**
   - [`JsEngine`](../automately-runtime-js/src/main/java/automately/runtime/js/JsEngine.java): Lifecycle factory for creating contexts.
   - [`JsContext`](../automately-runtime-js/src/main/java/automately/runtime/js/JsContext.java): Represents one sandboxed isolate.
   - [`ScriptRunner`](../automately-runtime-js/src/main/java/automately/runtime/js/ScriptRunner.java): Coordinates the event loop, execution timeouts, cancellation tokens, and log/state sinks.
2. **Host Bindings:**
   - Methods annotated with `@HostFunction` exposed directly to the isolate:
     - `KvBinding`, `BusBinding`, `QueueBinding`, `JobsBinding`, `FilesBinding`, `FsBinding`
     - `FetchBinding`, `SocketBinding`, `HttpBinding`, `Http2Binding`, `DnsBinding`, `SecretsBinding`
     - `CryptoBinding`, `ZlibBinding`
3. **Prelude & Built-in Modules:**
   - Standard JS prelude scripts in `src/main/resources/automately/runtime/js/`:
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
- **Worker (Compute Domain):** Polyfills, JS event loop, user code execution, pure crypto, zlib, text encoding.
- **Node (Authority Domain):** KV persistence, VFS file storage, egress network gate, tenant secrets, HTTP ingress, durable job queues.

---

## 5. Storage & Cluster Architecture

- **Database Engine:** Embedded Apache Ignite 2.18 (`automately-cluster-ignite`).
- **Data Region:** Off-heap pages with WAL (write-ahead log) persisted under `AUTOMATELY_DATA_DIR`.
- **Tables:** Replicated across cluster nodes with Calcite SQL querying.
- **Blob Storage:** [`BlobStore`](../automately-core/src/main/java/automately/core/files/BlobStore.java) implementations for chunked disk storage, S3 object storage, or in-memory testing.

---

## 6. How to Build & Generate Documentation

- **Build touched modules:**
  ```bash
  ./mvnw clean test-compile -DskipITs
  ```
- **Run targeted tests:**
  ```bash
  ./mvnw test -pl automately-runtime-javet -Dtest=ScriptRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Generate full aggregate Javadocs:**
  ```bash
  ./mvnw javadoc:aggregate -DskipITs
  ```
  *(Output available at `target/reports/apidocs/index.html`)*
