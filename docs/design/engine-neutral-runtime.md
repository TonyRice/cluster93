# Engine-neutral runtime, GraalJS beside Javet, and tenant workers

**Status:** Proposed (D-126), 2026-10-09.
- **Approved to build (Tony):**
  - the engine-neutral layer, proven with Javet alone
  - an embedded GraalJS adapter on the same layer
  - a comparison of the two inside native-image workers
- **Still to decide:**
  - **The engine,** from the comparison's results (§8).
  - **The worker↔node protocol (§7):** designed here in full, but built only after the engine is picked.

## 1. Why

### Memory outside the isolate's limit
A security review after phase B found that `AUTOMATELY_MAX_HEAP_MB` caps only V8's heap. A script can hold or churn memory outside it, and get the node OOM-killed. These were measured in a 64 MB isolate on Javet 6.0.2 (V8 15.5.35.14); the probes are in `ideas/native-memory-investigation.md`.

| Route | Measured | Does V8 count it? |
|---|---|---|
| ArrayBuffers and typed arrays | 1.5 GB held; one `new ArrayBuffer(2**33)` (8 GB) is accepted | yes, as external memory |
| Resizable ArrayBuffer (`maxByteLength`, `resize`) | 1 GB | yes |
| Growable SharedArrayBuffer (`grow`) | 128 MB | **no** |
| WebAssembly memory, including `memory.grow` from wasm code | 1 GB | **no** |
| Intl objects held (ICU, C++) | 10,000 `DateTimeFormat`s: 161 MB | **no** |
| `toLocaleString` with options, results discarded | +500 MB per 20,000 calls, still growing | **no** |
| Bytes handed to Java (`HostBridge.bytes`) | 512 MB copied onto the JVM heap in one call | — |

**Why each escapes:**
- **ArrayBuffers** come from the embedder's allocator, which Javet doesn't limit.
- **Resizable buffers and wasm memory** are committed through V8's page allocator (`backing-store.cc`), bypassing the embedder entirely.
- **ICU objects** are registered with an estimated size of 0 (`CppGCManaged<…>::Create(isolate, 0, …)` in V8's Intl code). A full GC reclaims them, but nothing makes V8 run one: a churn loop only causes young-generation scavenges, which don't sweep them.

**The consequence:**
- **One node:** a node OOM-killed by one line of script.
- **Then the next:** a service is restarted elsewhere indefinitely, and a job with `on_orphan: retry` up to 10 times, so the damage repeats node after node.
- **No escape found:** no data or host escape turned up.

`SECURITY.md` is corrected to say so (D-126).

### Engine-level security
**V8 is written in C++:** its bugs can become memory-corruption exploits, the usual route out of a JavaScript sandbox, and Javet gets V8's fixes only when Javet releases. **GraalJS is written in Java,** a memory-safe language, and ships sandbox policies built for untrusted code (`CONSTRAINED`, `ISOLATED`, `UNTRUSTED`). Since GraalVM 25.1 that includes Community Edition with the isolate artifacts (`js-isolate-community`).

Tony's concern is the engine itself. The answer is in two parts:
1. **Make the engine replaceable,** and measure GraalJS against Javet.
2. **Never rely on the engine alone:**
   - **A worker process per tenant,** with a kernel memory cap.
   - **Later, gVisor or Firecracker around it.** Someone who breaks the engine then lands in a sandbox whose only way out is a socket to the node, which checks every call.

## 2. Two seams
1. **The engine seam (built now):** the bindings and the run's orchestration are written once against a small SPI (§3). Javet and GraalJS each implement it.
2. **The placement seam (exists, used later):** the bindings call the `HostApis` interfaces (`automately-core`).
   - **Today:** `NodeHostApis` serves them in-process.
   - **In a worker:** a `RemoteHostApis` speaks the protocol (§7).

The two are independent, so any engine can run in any placement:

| | in-process (today) | worker process | gVisor | Firecracker |
|---|---|---|---|---|
| Javet | ✓ | ✓ | ✓ | ✓ |
| GraalJS | ✓ | ✓ | ✓ | ✓ |

### What's engine-specific today
| Part | Size | Tied to Javet? |
|---|---|---|
| Host APIs in `automately-core` | 11,400 lines | no; zero Javet imports |
| JavaScript polyfills (prelude, `node/*`, `web/*`) | 20,600 lines | almost not: they reach Java only through `host.*` (92 call sites), and the one V8-only feature they use is `Error.captureStackTrace` (5 uses) |
| Bindings, `HostBridge`, `JsValues`, `ScriptEntryPoints`, `ModuleResolver`, `JavetScriptRuntime` | about 3,300 lines | yes, throughout |

Only `Automately.java` constructs the runtime, through the engine-neutral `ScriptRuntime` interface.

## 3. Modules and the SPI

### `automately-runtime-js` (new, engine-neutral)
**The SPI:**
- **`JsEngine`:** one per process. It holds the shared caches and creates contexts.
- **`JsContext`:** one per run.
  - **Setting up:** bind host objects, evaluate the prelude, then compile and evaluate the job's module through a `ModuleSources` provider.
  - **Running:** call functions and drain microtasks.
  - **Values:** make promises (a `PromiseHandle` with `resolve` and `reject`), make errors, convert values.
  - **Housekeeping:** track rejected promises, terminate, close, and report memory figures.

**`JsArg`** replaces `V8Value` in the bindings' signatures. Its methods carry today's rules exactly:
- `string()`: the value if it's a string, else null
- `bytes()`: the view's own bytes, as `HostBridge.bytes`
- `options()`, `list()`, `json()`: through JSON, as `JsValues`
- `integer()`: strict, as `CryptoBinding.integer`
- `flag()`, `isNullish()`

Simple parameters (`int`, `double`, `boolean`, `String`) stay as they are.

**`@HostFunction(name)`** replaces `@V8Function`. Bindings return plain Java values, which the adapter converts:
- `String`, `Number`, `Boolean`, `null`, `UNDEFINED`, `byte[]`
- `JsonObject`/`JsonArray`
- a `PromiseHandle`
- an error

**Moved here, logic unchanged:**
- **every binding:** `Kv`, `Bus`, `Queue`, `Jobs`, `Files`, `Fetch`, `Socket`, `Http2`, `Dns`, `Http`, `Secrets`, `Fs`, `Crypto`, `Zlib`, `Web`, and the console, state and timers
- **`HostBridge`,** with promises made through `JsContext`
- **`EventLoop`,** already engine-neutral
- **`ScriptEntryPoints`,** as an interface the adapters implement
- **`ModuleResolver`'s resolution logic,** as `ModuleSources`: a name and its referrer give a URL and a source
- **the run's orchestration** from `JavetScriptRuntime` (stop, cancel, timeout, the loop's phases, rejection checks, the result), as `ScriptRunner implements ScriptRuntime`

**Also here:**
- **The JavaScript resources,** under `automately/runtime/js/…`.
- **The conformance suite:** today's runtime tests, made engine-neutral. Each engine module runs it (surefire `dependenciesToScan`), choosing its engine through a ServiceLoader `EngineUnderTest`. The same scripts must give the same answers on every engine, as node-runtime.md already requires between the isolate and the Node runner.

### `automately-runtime-javet` (the Javet adapter)
**Kept here:**
- the V8 flags and heap limit, and the near-heap-limit callback
- the ICU data file
- the V8 code caches for the prelude, built-ins and web scripts (D-106)
- the bridge to Javet's module resolver
- binding `@HostFunction` methods through Javet's `JavetCallbackContext`

**`JavetScriptRuntime`** keeps its name, now a `ScriptRunner` over the Javet engine, so the server is untouched.

### `automately-runtime-graaljs` (the GraalJS adapter)
It embeds GraalJS through GraalVM's polyglot API in our own process, not GraalVM's Node. The dependencies are `org.graalvm.polyglot:polyglot` and `js-community` (25.4.4.1.1).
- **Engine:** one shared polyglot `Engine`, so parsed and compiled code is reused, as V8's code cache is.
- **Host objects:** `ProxyObject`/`ProxyExecutable`, built from the `@HostFunction` methods.
- **Promises:** made through a prelude helper.
- **Bytes:** read from the guest's typed arrays with `Value.readBuffer`.
- **Modules:**
  - **Serving them:** a polyglot `FileSystem` serving our virtual module paths.
  - **Specifiers:** rewritten to those paths where GraalJS doesn't resolve `node:` and bare names itself.
  - **Dynamic `import()`:** stays off, matching Javet, through the existing source rewrite.
- **Termination:** `Context.close(true)`, or `interrupt`.
- **Unhandled rejections:** `js.unhandled-rejections=handler`.
- **Checked first, in a spike:**
  - module loading
  - the order of microtasks and macrotasks against D-110 and D-121
  - rejection tracking
  - bytes across the boundary
  - `Error.captureStackTrace`
  - termination

**Known differences to record, not hide:**
- **Error wording:** GraalJS's messages aren't V8's.
- **Intl output:** formatted by ICU4J, while Node uses ICU4C.

### `automately-worker` (the tenant worker's seed)
- **What it is:** a `main` hosting one engine, built as a GraalVM native image: `worker-javet` and `worker-graaljs` (`-Pnative`, GraalVM CE 25.4).
- **For the comparison:** it serves host APIs **in-process**, with local stores; the protocol comes after the engine is picked.
- **Commands:** `bench`, `idle`, `hostile`, `run`.

**Javet in a native image works** (measured 2026-10-09, on GraalVM CE 21). It needs:
- **A native-image substitution for `JavetClassLoader.loadClass`,** returning the classes compiled into the image. Javet defines its native bindings from bytecode at run time so its library can be reloaded, and native images forbid that. The substitution lives in our code; Javet's source is untouched.
- **JNI and reflection registration** of Javet's 319 classes, which its native code looks up at start-up.
- **Netty's TLS packages initialised at run time,** and slf4j at build time.
- **One shared copy of the V8 library.** Javet unpacks it per process under `/tmp/javet/<pid>/`. Its loading listener (`isDeploy`/`getLibPath`) can point every worker at one copy, so they share its pages.

| Worker | First script answers | Private memory, no services | RSS with 0 / 20 / 50 idle services |
|---|---|---|---|
| JVM (tuned flags) | 1.5 s | 116 MB | 164 / 225 / 310 MB |
| native image | 0.7 s | 50 MB | 182 / 245 / 339 MB |

The native image's RSS includes 136 MB of shared memory (the V8 library and ICU data in `/tmp`), paid once per node with a shared path. **Its own cost is about 50 MB, plus about 3 MB per idle service,** which is V8's cost per isolate, the same on the JVM.

## 4. Kernel memory caps on workers
- **Cgroups work, without root.** cgroup v2 has the memory controller delegated to the user session here. `systemd-run --user --scope -p MemoryMax=256M` around a V8 process filling 1 GB: the kernel killed it at the cap (exit 137), and everything outside carried on.
- **Per-process limits don't.** Under `prlimit --data`, V8 aborts at start-up ("Failed to reserve virtual memory for CodeRange"), because it reserves large virtual ranges up front.
- **On real nodes:** delegation comes from a systemd unit with `Delegate=yes`, or a container with its own cgroup namespace. Deciding which is part of the worker pool's plan.

## 5. Inside a worker: per-service limits
A tenant hurting its own services is the tenant's problem (Tony). The kernel cap protects the node and every other tenant. Per-service limits inside a worker are still worth having, for monitoring and for tenants' own peace of mind, and none needs a V8 patch:

| Memory | Counted by | Enforced by |
|---|---|---|
| V8 heap | V8 | the heap limit (exists) |
| fixed ArrayBuffers, typed arrays, SharedArrayBuffers | a counting allocator in Javet (a small Javet change) | refusal, with a `RangeError` |
| ICU (Intl, `toLocaleString`) | ICU's memory hooks in Javet, reported to V8's `ExternalMemoryAccounter` (a small Javet change) | full GCs, then the run ends over budget |
| resizable ArrayBuffers | V8's external figure | a cap on `maxByteLength` at construction (the only way in), then the run ends over budget |
| growable SharedArrayBuffers | — | refused at construction |
| WebAssembly | a guard that reads each module's memory section before compiling | `--wasm-max-mem-pages` (verified: `memory.grow` returns -1, and the constructors throw `RangeError`) plus a per-run quota |
| host copies into Java | `HostBridge` | caps per call and in flight |

**The construction guard has already been tested:** it replaces both buffer constructors with a `Proxy` and repoints `prototype.constructor`. It refused every bypass tried:
- the original constructor recovered through instances and prototypes
- a subclass, a bound constructor, and `Reflect.construct` with a foreign `newTarget`
- `transfer` past the maximum
- a getter answering small then large (it reads once)

There's no `ShadowRealm` to escape into.

**Under GraalJS,** guest memory lives in Java's heap, so the worker's heap limit bounds it by construction. Per-context limits come from the sandbox policies.

## 6. What this doesn't change yet
- **The server** still runs Javet in-process. Supporting several engines is for later (Tony), so there's no engine setting yet.
- **Until workers exist,** the in-process mitigations in §5 are the only defence for today's nodes. They're tracked separately.

## 7. The worker↔node protocol (proposed; built after the engine is picked)
This builds on node-runtime.md §5 (D-084–D-086): one JSON-RPC protocol for isolate workers, Node containers and Links. The runner is untrusted, and the tenant always comes from the node's records.

### Transport
Any ordered, reliable byte stream, so the framing doesn't care what carries it.
- **A local worker process:** the child's stdin and stdout pipes, with stderr for its own diagnostics. Only the parent holds them, so there's no socket file to find and no secret to pass, and `ProcessBuilder` gives them directly.
- **gVisor or runc (through Podman):** the container's stdio, or `--host-uds`.
- **Firecracker:** vsock.
- **Links:** the same frames, carried as binary WebSocket messages.

### Frames
```
u32 length   (of what follows; at most 1 MiB, larger data is chunked)
u8  type
u8  flags
u32 channel  (0 for control; otherwise a call, run or stream)
payload:     u32 jsonLength, the JSON, then raw bytes
```
- **Bytes travel raw,** never as base64, referenced from the JSON as `{"$bytes": i}`.
- **The JSON** follows today's `JsValues` conversion, so the bindings' arguments carry over as they are.

### Frame types
| Type | Direction | What it is |
|---|---|---|
| `HELLO` / `WELCOME` | worker → node / node → worker | protocol version, engine, capabilities |
| `BIND` | node → worker | the tenant, limits and env for a warm worker; once, for its whole life |
| `RUN`, `STOP`, `CANCEL` | node → worker | start a run (the script, input, context, timeout, package list), stop it with a grace period, cancel it |
| `RESULT_RUN` | worker → node | the run's outcome (`ExecutionResult`) |
| `CALL` | worker → node | `{id, run, method, params}`: a host-API call |
| `RESULT` / `ERROR` | node → worker | the answer: a value, or `{name, code, message}` as `HostException` (D-025) |
| `CANCEL_CALL` | worker → node | the run no longer needs the answer |
| `EVENT` / `REPLY` | node → worker / worker → node | a listener's delivery (bus, queue, job events) with a reply id, and the reply |
| `OPEN`, `DATA`, `END`, `RESET` | both | a stream's life |
| `CREDIT` | both | flow-control credit for a stream or for the connection |
| `LOG` | worker → node | console output, by run |
| `STATE` | worker → node | `ctx.step` and checkpoints, acknowledged |
| `STATS` | worker → node | each run's memory figures, for per-service monitoring |
| `PING` / `PONG`, `GOODBYE` | both | liveness, and an orderly close |

### Calls and objects
- **Method names** are the `HostApis` methods: `kv.get`, `fetch.open`, `sockets.connect`, `files.create`, and so on.
- **Every call carries its run id.** The node checks the run belongs to this worker and is still live (fencing by attempt), then applies that run's `NodeHostApis`.
- **Synchronous calls** (`readFileSync`, `ctx.step`) block the isolate's thread until the `RESULT` arrives. The worker's other runs carry on.
- **Long-lived objects get a handle id:** sockets, outgoing requests, HTTP/2 sessions and streams, file writers, HTTP exchanges and listener registrations.
  - **Ownership:** each id is owned by the side that made the object and scoped to its run, so everything closes when the run ends.
  - **Operations** are calls naming the handle: `socket.write`, `exchange.respond`, `registration.cancel`.

### Streams and backpressure
Every flow of bytes is a channel with credits, as HTTP/2's flow-control windows:
- fetch and HTTP bodies
- socket data
- HTTP/2 events
- request bodies to services
- file reads and writes

**How credit works:**
- **Per channel:** each starts with a 256 KiB window. A sender stops at zero, and the receiver grants more as it consumes. This maps directly onto `BodyStream.pause`/`resume`, today's in-process backpressure.
- **For the whole connection:** a 4 MiB window, so a worker can never make the node buffer without bound.

### What runs where
- **On the node, everything with authority:** kv, bus, queues, jobs, files, fs, fetch, http and http2, sockets, DNS, secrets, serving, logs and state.
- **In the worker, pure computation with no authority:** crypto, zlib and text codecs, so a large buffer doesn't cross the boundary for a hash.
- **Module sources** are read lazily with `packages.read`, which serves only the run's own locked packages.

### Node-side rules (the worker may be compromised)
- **Identity:** the tenant comes only from `BIND`, and the job only from the node's own run table. Neither is ever taken from a message.
- **Validation:** method names are checked against an allowlist, and every argument is validated as the bindings validate it today.
- **Limits per worker:**
  - calls in flight, open handles and channels
  - frame size and credit windows
  - log rate and line limits
- **A protocol violation ends the worker.**

### Lifecycle
- **The warm pool:**
  - A worker starts, says `HELLO`, and waits unbound in the pool.
  - `BIND` assigns it to one tenant, for good.
- **Running:** `RUN` and `STOP` drive runs, and `STATS` streams each run's memory figures.
- **Closing:**
  - **The worker** exits if its connection closes.
  - **If the worker dies,** its runs end and the orphan rules apply (D-009 to D-012).
  - **A hung worker** is found by `PING` timeouts.

### Considered and rejected
- **JSON-RPC as plain text:** bytes in base64, and no backpressure.
- **gRPC:** code generation and heavy dependencies on both sides.
- **HTTP/2 over a Unix socket:** it does give flow control, but costs a header block per call. This design keeps HTTP/2's flow-control model without the weight.

### Code, when it's built
- **The shared protocol:** a frame codec and message types (an `automately-runner-protocol` module).
- **On the node:** a server answering calls with `NodeHostApis`.
- **In the worker:** `RemoteHostApis`, implementing the `HostApis` interfaces.

The engine-neutral SPI needs no change for any of it.

## 8. The comparison (method; results to come)
Both engines run in native-image workers (GraalVM CE 25.4), with the same scripts.

- **Warm-up:** at least 20 seconds or 200 iterations, whichever is longer, then 50 measured iterations. The median, p99 and throughput are reported; the first run is recorded only for information. Long-term performance is what matters (Tony).
- **Workloads:**
  - **Pure computation:** JSON round trips on 1 MB, a regex tokenizer, string building, Map/Set churn, a sort, SHA-256 in JavaScript, Richards and DeltaBlue.
  - **Our polyfills:** Buffer base64/hex, a stream pipeline, URL parsing, EventEmitter, TextEncoder/Decoder.
  - **Host-heavy:** kv in a loop, `crypto.createHash` over 64 KB, gzip and gunzip of 1 MB, timer and promise churn.
  - **Service-like:** a request handler driven through the serve path with test exchanges.
- **Memory:** RSS and private memory, with 0, 20 and 50 idle contexts.
- **The hostile memory tests** (§1), each under a cgroup cap (`systemd-run --user --scope -p MemoryMax=512M`):
  - **The routes:** a single 8 GB buffer, cumulative buffers, a resizable buffer, a growable SharedArrayBuffer, Intl churn, `toLocaleString` churn, wasm (with `wasm-community` added for GraalJS), host copies.
  - **Recorded for each:** a script error, the worker killed at its cap, or memory held within bounds.
- **GraalJS sandboxed:** also with `js-isolate-community`, the `ISOLATED`/`UNTRUSTED` policies and `sandbox.MaxHeapMemory`, if its isolates work inside a native-image host.
- **Conformance:** the suite's pass and fail lists for GraalJS. Where practical, the real-npm bundles are run through both workers and compared with Node.
- **Rerunnable:** the benchmark scripts live in `benchmarks/engines/`.

**Results:** to come.

## 9. Order of work
1. The engine-neutral layer, with Javet only: no change in behaviour, the same test counts.
2. The GraalJS adapter: the spike first, then the conformance suite run on it.
3. The native-image workers and the comparison; the results go into §8.
4. Tony picks the engine. Then the protocol (§7), the remote `HostApis`, the worker pool with kernel caps, and gVisor/Firecracker, each a plan of its own.
