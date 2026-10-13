# In-Cluster Worker ↔ Node Protocol Specification

**Status:** Proposed (D-127), 2026-10-10.  
**Module target:** `cluster93-runner-protocol` (shared frame codec and message model)  
**Implementation target:** `RemoteHostApis` on the worker side, `WorkerServer` / `NodeHostApis` on the node side.  

---

## 1. Scope & Architectural Boundaries

This document specifies the high-throughput inter-process communication (IPC) protocol between a **Cluster93 Cluster Node** and an **In-Cluster Worker Runner**.

```
                           ┌──────────────────────────────────────────────┐
                           │           CLUSTER93 CLUSTER NODE            │
                           │   (Vert.x 5 + Apache Ignite 2.18 Core)       │
                           └──────────────────────┬───────────────────────┘
                                                  │
                                                  │ Local Worker IPC
                                                  │ • Stdio pipes (child processes)
                                                  │ • Unix Domain Sockets (gVisor/containers)
                                                  │ • vsock (Firecracker microVMs)
                                                  │
                                                  ▼
                           ┌──────────────────────────────────────────────┐
                           │         IN-CLUSTER TENANT WORKER             │
                           │    (`worker-graaljs` / `worker-javet`)       │
                           │                                              │
                           │ • Bound to one tenant for life               │
                           │ • Isolated by Linux cgroup memory caps       │
                           │ • Zero authority: RPCs node for host APIs    │
                           │ • Pure compute local (crypto, zlib, text)    │
                           └──────────────────────────────────────────────┘
```

### In Scope: In-Cluster Workers
- **Process Model:** Dedicated tenant worker processes spawned as GraalVM `native-image` binaries (`worker-javet`, `worker-graaljs`), containerized processes via Podman/gVisor (`runsc`), or Firecracker microVMs.
- **Lifetime & Tenancy:** A worker process is spawned unbound into a warm pool, bound to **one tenant for its lifetime** via `BIND`, and handles runs/services sequentially or concurrently for that tenant.
- **Resource Containment:** Enforced externally via Linux cgroups v2 (`MemoryMax`, `CPUWeight`), seccomp filters, and namespaces.
- **Zero Trust:** The worker process is treated as potentially compromised. The Node holds all state, identity, and authority.

### Out of Scope: Cluster93 Link (`cluster93-link`)
- **Remote Devices:** User-owned hardware (laptops, phones via Termux, external VMs) connecting into the cluster from outside are governed by `ideas/links.md`.
- **Transport Difference:** Links connect via **outbound TLS WebSockets** using join codes and asymmetric cryptographic handshakes, and negotiate capabilities dynamically. They do not share this local runner IPC lifecycle.

---

## 2. Transports

The protocol is transport-agnostic and runs over any full-duplex byte stream:

| Runner Mode | Transport Mechanism | Description |
|---|---|---|
| **Child Process (Default)** | Stdio pipes (`stdin` / `stdout`) | Node spawns the worker subprocess directly; `stdin` delivers node $\to$ worker frames, `stdout` delivers worker $\to$ node frames. `stderr` carries unformatted diagnostic logs. |
| **Rootless Containers (gVisor / runc)** | Unix Domain Socket (UDS) | Container connects to a node-hosted UDS via `--host-uds` or mounted volume socket. |
| **MicroVMs (Firecracker)** | vsock | High-speed hypervisor virtual socket channel. |

---

## 3. Wire Format: Binary Frames

Messages are encoded as length-prefixed binary frames. Large binaries (ArrayBuffers, streams) travel contiguous and unencoded without base64 overhead.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Frame Length                          |  (u32: total bytes following)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Frame Type  |     Flags     |          (Reserved)           |  (u8 type, u8 flags)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          Channel ID                           |  (u32: 0 for control; else run/call/stream ID)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          JSON Length                          |  (u32: length of JSON payload)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                     JSON Payload (UTF-8)                      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                  Contiguous Raw Binary Buffers                |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Frame Header (12 bytes)
- **`Frame Length` (u32, big-endian):** Total byte length of the frame content following this field. Maximum frame size: 1 MiB (1,048,576 bytes). Payloads larger than 1 MiB must be chunked via streaming frames (`DATA`).
- **`Frame Type` (u8):** Discriminator identifying the message semantics (see §4).
- **`Flags` (u8):** Bitmask for frame-specific options:
  - `0x01` (`END_STREAM`): Signals that this frame is the final data frame on the channel.
  - `0x02` (`COMPRESSED`): Payload body is compressed (optional).
  - `0x04` (`PRIORITY`): High-priority control signal (e.g. immediate abort).
- **`Reserved` (u16):** Set to 0.
- **`Channel ID` (u32, big-endian):**
  - `0`: Connection-wide control channel (lifecycle, ping, pool management).
  - Non-zero: Scoped to a specific execution run, RPC call ID, or stream channel.

### Frame Payload: Split JSON + Raw Bytes
- **`JSON Length` (u32, big-endian):** Length in bytes of the JSON metadata block.
- **`JSON Payload`:** UTF-8 encoded string containing typed metadata, method names, and structured arguments matching `JsValues` conversions.
- **`Raw Binary Buffers`:** Contiguous byte sequence immediately following the JSON payload.
  - When the JSON payload contains byte buffers, it references them by index: `{"$bytes": 0, "length": 65536}`.
  - Multiple binary arguments are laid out contiguously; byte offsets and lengths are defined in the JSON descriptors.

---

## 4. Frame Types & Protocol Lifecycle

### Frame Type Reference Table

| Code | Type | Direction | Channel | Description |
|---|---|---|---|---|
| `0x01` | `HELLO` | Worker $\to$ Node | 0 | Worker startup announcement with engine, version, and PID. |
| `0x02` | `WELCOME` | Node $\to$ Worker | 0 | Node acceptance with assigned worker ID and protocol config. |
| `0x03` | `BIND` | Node $\to$ Worker | 0 | Permanently binds worker to one tenant with limits and env. |
| `0x04` | `RUN` | Node $\to$ Worker | Run ID | Dispatches a script execution (bundle, input, context, timeouts). |
| `0x05` | `STOP` | Node $\to$ Worker | Run ID | Graceful stop request for a running service/job. |
| `0x06` | `CANCEL` | Node $\to$ Worker | Run ID | Forceful cancellation / interrupt of a running script. |
| `0x07` | `RESULT_RUN` | Worker $\to$ Node | Run ID | Final execution outcome (`ExecutionResult`: return value or error). |
| `0x10` | `CALL` | Worker $\to$ Node | Call ID | Host API RPC invocation (`{runId, method, params}`). |
| `0x11` | `RESULT` | Node $\to$ Worker | Call ID | Successful answer to a host API RPC. |
| `0x12` | `ERROR` | Node $\to$ Worker | Call ID | Host API failure (`HostException` with code and message). |
| `0x13` | `CANCEL_CALL`| Worker $\to$ Node | Call ID | Aborts an in-flight host API request. |
| `0x20` | `EVENT` | Node $\to$ Worker | Run ID | Delivers an asynchronous bus, queue, or ingress event. |
| `0x21` | `REPLY` | Worker $\to$ Node | Event ID | Reply payload for request-response bus events. |
| `0x30` | `OPEN` | Both | Stream ID | Establishes a new streaming channel (HTTP body, socket, file). |
| `0x31` | `DATA` | Both | Stream ID | Chunks of binary or text data on an active stream. |
| `0x32` | `END` | Both | Stream ID | Orderly half-close of a streaming channel. |
| `0x33` | `RESET` | Both | Stream ID | Abrupt stream termination (RST_STREAM). |
| `0x34` | `CREDIT` | Both | Stream / 0 | Flow-control credit grants (per-stream or connection-wide). |
| `0x40` | `LOG` | Worker $\to$ Node | Run ID | Buffered console stdout/stderr log lines. |
| `0x41` | `STATE` | Worker $\to$ Node | Run ID | Checkpoint or `ctx.step` state commit (durably acked). |
| `0x42` | `STATS` | Worker $\to$ Node | 0 / Run ID| Memory telemetry (RSS, dirty pages, GC pauses). |
| `0x50` | `PING` | Both | 0 | Heartbeat liveness probe. |
| `0x51` | `PONG` | Both | 0 | Heartbeat response. |
| `0x5F` | `GOODBYE` | Both | 0 | Orderly shutdown handshake before connection close. |

---

## 5. Flow Control & Backpressure (Credit Windows)

Streaming data (`DATA` frames) is governed by an **HTTP/2-style credit-based flow control window**:

### Stream-Level Credit Window
- Every newly opened stream (`OPEN`) starts with a default initial window of **256 KiB** (262,144 bytes).
- Senders may only emit `DATA` bytes up to the remaining credit balance.
- When the window reaches 0, the sender pauses reading from the source (e.g. pauses socket read or file stream).
- The receiver sends `CREDIT` frames with `bytesToAdd` as memory is consumed and freed, releasing backpressure.

### Connection-Level Credit Window
- The entire connection has a global maximum buffer cap of **4 MiB** (4,194,304 bytes).
- Even if multiple streams have available stream-level credit, the total unacknowledged in-flight data cannot exceed 4 MiB.
- **Security Invariant:** Prevents a compromised or slow worker from inducing unbounded memory allocation on the Node.

---

## 6. Host API RPC Mapping (`HostApis`)

All host operations map directly to the existing `cluster93.core` service interfaces:

### Authority Partitioning

```
┌──────────────────────────────────────┐     ┌──────────────────────────────────────┐
│        NODE (AUTHORITY DOMAIN)       │     │       WORKER (COMPUTE DOMAIN)        │
├──────────────────────────────────────┤     ├──────────────────────────────────────┤
│ • Key-Value Store (`kv.*`)           │     │ • Polyfills & Event Loop             │
│ • Virtual File System (`files.*`)    │     │ • Pure JS Crypto (`crypto.*`)        │
│ • Message Bus & Queues (`bus.*`)     │     │ • Zlib compression (`zlib.*`)        │
│ • Outbound Fetch & Sockets           │     │ • Text encoders / decoders           │
│ • Tenant Secrets & Auth tokens       │     │ • User Script Execution              │
│ • HTTP Ingress & Serving             │     │ • Local JSON parsing & object graphs │
└──────────────────────────────────────┘     └──────────────────────────────────────┘
```

- **Pure Computation Runs in Worker:** `node:crypto`, `node:zlib`, and `node:buffer` execute locally inside the worker process so megabyte payloads do not cross the IPC bridge for hashing or compression.
- **Operations Requiring Authority Run on Node:** Sockets, file storage, network calls, KV state, and secrets require an explicit `CALL` frame to the Node.

### RPC Method Reference (`CALL.method`)

Every host API call maps to a namespaced method string:

| API | Method | Arguments (`params`) | Return (`RESULT`) | Description |
|---|---|---|---|---|
| **`kv`** | `kv.get` | `{key: string}` | `{value: any}` | Retrieve a key from tenant KV store |
| | `kv.set` | `{key: string, value: any, ttlMs?: number}` | `null` | Store a key with optional TTL |
| | `kv.delete`| `{key: string}` | `{deleted: boolean}` | Delete a key |
| | `kv.clear` | `{}` | `{cleared: number}` | Clear tenant KV partition |
| **`bus`** | `bus.send` | `{topic: string, body: any}` | `{reply: any}` | Request-reply bus message |
| | `bus.publish`| `{topic: string, body: any}` | `null` | Pub-sub broadcast to topic |
| | `bus.subscribe`| `{topic: string}` | `{subscriptionId: u64}` | Subscribe to topic (receives `EVENT`) |
| **`queue`**| `queue.push` | `{queue: string, item: any, delayMs?: number}`| `{id: string}` | Durable queue enqueue |
| | `queue.pop` | `{queue: string, timeoutMs?: number}` | `{item: any, ackId: string}` | Durable queue dequeue |
| | `queue.ack` | `{ackId: string}` | `null` | Acknowledge completed queue item |
| **`files`**| `files.get` | `{path: string}` | `{size: number, streamId: u32}` | Read file metadata and stream |
| | `files.put` | `{path: string, size?: number}` | `{streamId: u32}` | Open write stream for file |
| | `files.delete`| `{path: string}` | `{deleted: boolean}` | Delete file |
| | `files.list`| `{prefix?: string}` | `{files: Array<FileInfo>}` | List files under prefix |
| **`fs`** | `fs.stat` | `{path: string}` | `{stat: FileStat}` | Virtual file system stat |
| | `fs.readFile`| `{path: string}` | Raw bytes attached | Small file read (sync/async) |
| | `fs.writeFile`| `{path: string}` + raw bytes | `null` | Small file write |
| | `fs.mkdir` | `{path: string, recursive?: boolean}` | `null` | Create directory in VFS |
| | `fs.readdir`| `{path: string}` | `{entries: string[]}` | List directory entries |
| **`fetch`**| `fetch.open` | `{url: string, method: string, headers: object}` | `{handle: u64, status: number, headers: object}` | Egress-checked HTTP request |
| **`sockets`**| `socket.connect`| `{host: string, port: number, tls?: boolean}` | `{handle: u64, localPort: number}` | Outbound TCP/TLS connection |
| | `socket.write` | `{handle: u64}` + raw bytes | `null` | Write data to socket handle |
| | `socket.close` | `{handle: u64}` | `null` | Close socket |
| **`dns`** | `dns.resolve` | `{name: string, type: string}` | `{records: any[]}` | Egress-checked DNS query |
| **`secrets`**| `secrets.get` | `{name: string}` | `{value: string}` | Read authorized tenant secret |
| **`serving`**| `serve.listen` | `{port: number, prefix?: string}` | `{listenerId: u64}` | Bind service HTTP route |
| | `serve.respond`| `{exchangeId: u64, status: number, headers: object}` | `{streamId: u32}` | Send HTTP response for exchange |

### Long-Lived Objects & Handles
Objects with lifecycles spanning multiple calls (TCP sockets, HTTP/2 streams, file writers, HTTP exchanges) are assigned a **Handle ID** (`u64`):
- Handles are strictly scoped to the `runId` that created them.
- When a run completes or is cancelled, the Node automatically closes and reaps all handles associated with that `runId`.

---

## 7. Security Invariants & Zero-Trust Enforcement

Because the worker executes untrusted guest code, the Node enforces strict defense-in-depth:

1. **Identity Isolation:**
   - The worker is bound to a tenant ID **once** via `BIND`.
   - The worker **never** sends `tenantId` in `CALL` or `RUN` frames. The Node supplies `tenantId` from its internal run registry table.
2. **Method Allowlisting:**
   - The Node validates incoming `CALL.method` against a strict allowlist. Any unknown or unauthorized method immediately terminates the call with `INVALID_METHOD`.
3. **In-Flight Quotas:**
   - Maximum in-flight RPC calls per worker: 1,024.
   - Maximum active stream channels: 256.
   - Maximum log output rate: 10,000 lines/sec (excess lines throttled and flagged with `LOG_DROPPED`).
4. **Protocol Violation Termination:**
   - Any malformed binary frame, unauthorized handle access, or window overflow triggers immediate process termination via `SIGKILL`.
   - The Node's orphan job reaper automatically flags affected jobs for recovery under the tenant's `onOrphan` policy.
