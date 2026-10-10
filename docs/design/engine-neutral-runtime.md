# Engine-neutral runtime, GraalJS beside Javet, and tenant workers

**Status:** Accepted (D-126, D-127), 2026-10-10.
- **Completed:**
  - **Step 1 (2026-10-09):** The engine-neutral layer (`automately-runtime-js`), with Javet alone (`5d35cd2`).
  - **Step 2 (2026-10-10):** Embedded GraalJS adapter (`automately-runtime-graaljs`, `3b22e8a`). Conformance suite passes completely: **137 of 137 tests passing**.
  - **Step 3 (2026-10-10):** Native-image workers built and benchmarked (`955e273`, §8). Engine trade-offs characterized.
  - **Resolution (D-127):** Engine selection is a **deployment-level choice** (`AUTOMATELY_WORKER_ENGINE=graaljs|javet`). Deployers choose GraalJS for strict tenant sandboxing / memory safety, or Javet for maximum JIT object/JSON throughput.
- **Next up (Step 4):**
  - **Worker ↔ Node IPC Protocol:** Formalized in dedicated normative spec [worker-node-protocol.md](worker-node-protocol.md).
  - Protocol implementation in `automately-runner-protocol`.

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

### `automately-runtime-graaljs` (the GraalJS adapter; built 2026-10-10)
It embeds GraalJS through GraalVM's polyglot API in our own process, not GraalVM's Node. The dependencies are `org.graalvm.polyglot:polyglot` with `js-community` (in-process) and `js-isolate-linux-amd64-community` (isolated), at 25.4.4.1.1. The jars are Java 17 bytecode, so they run on the server's JDK 21.

**Where the guest runs:**
- **The default:** each `GraalJsEngine` spawns its own polyglot isolate (`engine.SpawnIsolate`), with an isolate-wide limit (`engine.MaxIsolateMemory`), and each run's context gets a heap limit (`sandbox.MaxHeapMemory`). Reaching it ends the run (`resourceExhausted`), never the JVM.
- **The other mode:** `IN_PROCESS`, the guest in the JVM's own heap with no per-run limit. Kept only for the comparison.

**The policy, and why it's `TRUSTED`:**
- **What the strict policies refuse:**
  - `ISOLATED` and `UNTRUSTED` refuse `js.stack-trace-api` (`Error.captureStackTrace`, allowed only under `TRUSTED`).
  - They refuse `js.unhandled-rejections` (allowed up to `CONSTRAINED`), without which D-110 can't work.
  - `ISOLATED` also requires a total CPU-time cap (`sandbox.MaxCPUTime`), which doesn't fit services that run for weeks.
- **What `TRUSTED` with a spawned isolate still gives:** the separate heap and the heap limit. It was measured to stop a heap bomb, buffers and resizable buffers at the limit, and to collect `toLocaleString` churn normally.
- **The lockdown, by hand:**
  - no host access but byte buffers and our own proxies, and no host class lookup
  - no I/O but the run's modules
  - no threads, processes, native access, environment or polyglot access
  - GraalJS's own extra globals switched off or deleted: `print`, `load`, `console` (ours replaces it), `Polyglot`, `Packages`, `Java`, `Graal`, and the global `arguments`
- **Turned on for Node's behaviour:** `js.stack-trace-api` and `js.temporal`.

**How each part works:**
- **Host functions:** `ProxyObject`s of `ProxyExecutable`s, built from the same `@HostFunction` bindings, with the same argument rules as Javet's adapter.
  - **Exceptions:** a Java exception is thrown into the script as a real `Error` (`Value.throwException`).
  - **Numbers:** GraalJS reports small integers as `Integer` and other numbers as `Double`, which matches V8's split, but a BigInt also says it's a number, so BigInts are checked for explicitly.
- **Bytes:**
  - **From scripts:** only an `ArrayBuffer` gives its bytes to the host, so a typed array's are read from its buffer at its own offset and length, as Javet's rule (D-103).
  - **To scripts, in-process:** `new Uint8Array(hostByteBuffer).slice()`, measured at no cost against 117 ms per MiB for byte-by-byte writes.
  - **To scripts, isolated:** a host `ByteBuffer` crosses the isolate's boundary one byte at a time (2.5 s per 5 MB), but a string crosses in one piece. So the bytes go across as a Latin-1 string, one character per byte, and a helper fills the `Uint8Array` inside the isolate (48 ms per 5 MB). Gzip of 1 MB went from 2.8 s to 0.14 s.
- **Modules:**
  - **Why the rewrite:** GraalJS asks its file system for an import by specifier alone, never saying who imports it, but the rules depend on the importer (D-089).
  - **The rewrite:** `GraalJsModules` serves each module with its static imports already resolved by `ModuleSources` against that module (`Imports.rewriteStaticSpecifiers`).
  - **Paths:** they mirror the URLs (`/job.mjs`, `/packages/<name>/<version>/<file>`, `/node:fs`), so stack traces name modules, and a job's failure text maps them back to V8's form.
  - **Refusals:** a refused import fails its read with the reason, which GraalJS reports as the import's own error.
  - **Sharing:** built-ins rewritten once are shared by every context.
- **The job:** loaded through `import()`, which gives V8's semantics: a promise that waits for top-level `await` and rejects with the stack.
- **A built-in's namespace from inside a host function** (the WebSocket global, D-125): a module that re-exports it is evaluated in place, so it's the same instance the job's imports get.
- **Microtasks:** GraalJS drains them itself whenever a call into the context returns. Our loop already drains after every callback, so the order is the same as V8's.
- **Rejections:** `Graal.setUnhandledPromiseRejectionHandler` reports each rejection still unhandled at the end of a turn to the prelude, which is when our loop checks (D-110).
- **Termination:** `Context.close(true)` from any thread.

**Conformance: 136 of 136**, the same suite Javet passes. One difference is declared rather than hidden: **GraalJS never reports a rejection handled in a later turn**, so Node's `rejectionHandled` doesn't fire. The suite asserts the rest of D-110 on both engines, and that event only on engines that report it (`EngineUnderTest.reportsRejectionsHandledLater`). Emulating it would mean patching `Promise.prototype.then`, and that would still miss `await`.

**Measured costs of the isolate (2026-10-10, native workers):**
- **Crossing the boundary is slow for bulk data.** A host call costs about 18 µs into the isolate, against about 6 µs for V8 through Javet. Bytes and strings cross at roughly 10 ns per character. Pure computation inside the isolate runs as fast as in-process (JSON, DeltaBlue: no difference). But every host API that moves bytes pays this: crypto, zlib, kv values, request and response bodies.
- **The per-run heap limit counts garbage the isolate hasn't collected yet.** `sandbox.MaxHeapMemory` counts every byte, buffers included, which is the point. But a workload holding about 10 MB at a time (Buffer base64 and hex of 1 MB, repeatedly) fails at 128 MB, crawls at 192 MB, and runs normally from 256 MB. A limit there must leave room for the collector, so as a rule set it 2–4× above what a script actually holds.
- **Cold code is slow.** Until the JIT has compiled it, GraalJS runs a script about 4× slower than V8 (3 MB of hex and base64 both ways: 1.56 s against 0.40 s on its first run; 0.58 s against 0.32 s warm). Each worker keeps one engine, so code from shared sources (all our polyfills) compiles once per worker. But a tenant's script that runs once pays the full cold cost.

**Other known differences:**
- **Error wording:** e.g. "Cannot read property 'x' of null" against V8's "Cannot read properties of null (reading 'x')".
- **Intl output:** ICU4J against ICU4C.
- **No `WebAssembly`** unless `wasm-community` is added.
- **Script-visible stacks** show the module paths above.

None of these failed a conformance test.

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

**Building the workers (GraalVM CE 25.4, `benchmarks/engines/build.sh`):**
- **The cost:** `worker-javet` takes 2.5–5 minutes and peaks at 2.9 GB. `worker-graaljs` takes about 8 minutes and peaks at 5.9 GB; it carries the whole JS language and the isolate's library, and runs out of a 5 or 6 GB builder heap.
- **The safeguards:** each build runs one at a time under a kernel memory cap (9 GB). The builder gets 7 GB of heap and 2 threads, since fewer threads hold fewer compilation graphs at once. An uncapped GraalJS build once took down the developer's terminal, with `/tmp` (tmpfs) already full.
- **What the image may see:** a native image only reaches the classes, methods and resources it's told about. The tracing agent's record listed only the host functions its run happened to call (for zlib, just `process`), so streaming zlib would have failed in a worker. A hand-written file in `automately-worker` now registers every binding's public methods, plus all the runtime's JavaScript by glob. `NativeImageMetadataTest` fails if a class with `@HostFunction` methods is missing from it.

**A leak to fix before workers run in production:** a worker that's killed (at its memory cap, say) leaves its unpacked V8 library in `/tmp/javet/<pid>/` and its ICU data copy (`/tmp/automately-icudt*.dat`). On a tmpfs `/tmp` that's memory: about 80 MB per dead worker (a 46–51 MB library and 33 MB of ICU data). The fix is the one above: one shared copy at a fixed path, unpacked once per node. `run.sh` cleans up after itself meanwhile.

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
> **Dedicated Specification:** The complete normative specification for the in-cluster worker IPC protocol is located at [worker-node-protocol.md](worker-node-protocol.md).
>
> **Important Distinction:** This protocol is exclusively for **in-cluster workers** (local child processes, gVisor containers, Firecracker microVMs) managed directly by cluster nodes. Remote user-owned devices connecting from outside use `automately-link` over outbound TLS WebSockets (governed separately by `ideas/links-and-capabilities.md`).

This builds on node-runtime.md §5 (D-084–D-086): one binary frame protocol for in-cluster isolate workers, Node containers, and microVMs. The runner is untrusted, and the tenant always comes from the node's records.

### Transport
Any ordered, reliable byte stream:
- **A local worker process:** the child's stdin and stdout pipes, with stderr for its own diagnostics. Only the parent holds them, so there's no socket file to find and no secret to pass, and `ProcessBuilder` gives them directly.
- **gVisor or runc (through Podman):** the container's stdio, or `--host-uds`.
- **Firecracker:** vsock.

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

## 8. The comparison (run 2026-10-10)
Both engines run in native-image workers (GraalVM CE 25.4), with the same scripts.

- **Warm-up:** at least 20 seconds or 200 iterations, whichever is longer, then 50 measured iterations. The median and p99 are reported; the first run is recorded only for information. Long-term performance is what matters (Tony).
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

### How it was run
- **Rerunnable:** `benchmarks/engines/build.sh`, then `run.sh` (about an hour). The raw results, the tables and a CPU log are in `benchmarks/engines/results/2026-10-10-1054/`.
- **The machine:** an i7-1185G7 laptop, 8 threads, 30 GB, Linux 7.0. It ran on the balanced profile with **turbo off**, so the clock held at 3.0 GHz, and the CPU stayed at 49°C median and 60°C at most.
  - **Why turbo off:** earlier attempts with turbo on reached 97°C, and the clock swung between 0.4 and 4.2 GHz. Those numbers were thrown away.
- **Three variants:**
  - V8 through Javet
  - GraalJS in its own isolate (the default, with a heap limit per run)
  - GraalJS in-process, kept only to separate the engine's speed from the isolate's cost
- **Memory limits:**
  - **Workloads:** 512 MB on every engine. GraalJS's limit counts buffers and trips on uncollected garbage (§3), so 128 MB would have failed buffer-codec there.
  - **Start-up and idle memory:** the default 128 MB.
  - **Hostile tests:** 64 MB per run, with a 512 MB kernel cap on the worker.
- **What the plan had that this didn't do:**
  - **WebAssembly on GraalJS:** `wasm-community` wasn't added, so GraalJS has none.
  - **The `ISOLATED`/`UNTRUSTED` policies:** they refuse options Node's behaviour needs (§3).
  - **The real-npm bundles through the workers:** not run.
  - **service-router:** it's a request handler's work without the serve path (route matching, query parsing, JSON in and out).

### Results
**Start-up and idle memory:**

| | V8 (Javet) | GraalJS, isolated | GraalJS, in-process |
|---|---|---|---|
| First script answered | 0.75 s | 0.70 s | 0.69 s |
| Private memory after it | 40 MB | 17 MB | 15 MB |
| Private memory with 20 / 50 idle services | 104 / 193 MB | 289 / 441 MB | 276 / 336 MB |
| Each further idle service | 3.0 MB | 5.1 MB | 2.0 MB |

GraalJS starts smaller, but its isolate's heap grows by about 285 MB once services are running. With 50 idle services it holds 2.3 times V8's memory.

**Warmed-up time per iteration** (median; the ratio is against V8, above 1 is slower):

| Workload | V8 | GraalJS, isolated | GraalJS, in-process |
|---|---|---|---|
| regex-tokenize | 30.4 ms | 9.4 ms (0.31×) | 9.4 ms (0.31×) |
| events (EventEmitter) | 98.2 ms | 67.6 ms (0.69×) | 68.1 ms (0.69×) |
| buffer-codec | 72.7 ms | 74.0 ms (1.02×) | 99.7 ms (1.37×) |
| promise-timers | 57.1 ms | 59.6 ms (1.04×) | 55.6 ms (0.97×) |
| sha256-js | 74.7 ms | 77.7 ms (1.04×) | 77.8 ms (1.04×) |
| url-parse | 36.1 ms | 38.0 ms (1.05×) | 93.2 ms (2.58×) |
| crypto-hash | 71.3 ms | 93.9 ms (1.32×) | 66.6 ms (0.93×) |
| map-set-churn | 55.5 ms | 84.3 ms (1.52×) | 98.8 ms (1.78×) |
| string-build | 24.5 ms | 38.5 ms (1.57×) | 151.4 ms (6.17×) |
| stream-pipeline | 162.2 ms | 315.0 ms (1.94×) | 837.3 ms (5.16×) |
| kv-loop | 38.6 ms | 79.4 ms (2.05×) | 37.1 ms (0.96×) |
| richards | 29.7 ms | 60.8 ms (2.05×) | 61.5 ms (2.07×) |
| zlib-roundtrip | 45.3 ms | 120.3 ms (2.65×) | 138.3 ms (3.05×) |
| text-codec | 25.7 ms | 99.8 ms (3.88×) | 263.9 ms (10.26×) |
| sort | 60.9 ms | 249.9 ms (4.10×) | 253.3 ms (4.16×) |
| deltablue | 21.5 ms | 108.7 ms (5.06×) | 108.3 ms (5.04×) |
| service-router | 18.3 ms | 108.4 ms (5.92×) | 107.1 ms (5.84×) |
| json-roundtrip | 38.0 ms | 326.3 ms (8.58×) | 326.7 ms (8.59×) |

**What the timings show:**
- **Object-heavy code is where V8 leads:** objects, method calls and JSON (DeltaBlue, sort, the request handler, JSON round trips) run 4–9× faster on V8. Community Edition's JIT does compile them (about 145 compilations for DeltaBlue, none failed), so this is the compiler's quality, not missing warm-up.
- **Numeric and typed-array code is level:** SHA-256 in JavaScript, Buffer's codecs, URL parsing and the event loop. GraalJS is faster at regular expressions (3.2×) and EventEmitter (1.45×).
- **The isolate costs nothing for computation** (isolated and in-process match on JSON, DeltaBlue, Richards, sort). **It costs a lot for bytes and calls crossing into the host:** kv 2.05× against 0.96× in-process, crypto 1.32× against 0.93×.
- **Tail latency is worse on GraalJS:** p99 runs well above the median where V8's barely moves (buffer-codec 373 against 74 ms median; string-build 192 against 39 ms). These are the isolate's garbage-collection pauses.
- **Cold first runs are 1.4–18× slower on GraalJS,** median 6.6× (text-codec 0.8 s against 0.04 s, sha256-js 0.86 s against 0.08 s); see §3.
- **Not explained yet:** in-process GraalJS is slower than isolated on strings, streams, URLs and text codecs (up to 10×). The likely cause is the worker's own garbage collector, which the isolate doesn't use. In-process isn't a candidate either way.

**Memory under load:** GraalJS's private memory after a workload is 2–8× V8's (isolated: 80–355 MB, against V8's 42–71 MB).

**The hostile memory tests** (64 MB per run, the worker capped at 512 MB):

| Attack | V8 (Javet) | GraalJS, isolated |
|---|---|---|
| Heap bomb | stopped at the limit | stopped at the limit |
| One 8 GB buffer | **worker killed** at the cap | refused: `RangeError` |
| 64 buffers of 16 MB | **worker killed** | stopped at the limit |
| Resizable buffer to 1 GB | **worker killed** | refused: `RangeError` |
| Growable SharedArrayBuffer to 1 GB | **worker killed** | refused: `RangeError` |
| `toLocaleString` churn | **worker killed** | ran to the end in bounded memory (220 MB peak) |
| 100,000 `Intl.DateTimeFormat`s held | **worker killed** | stopped at the limit |
| WebAssembly `memory.grow` to 1 GB | allowed, uncounted (the pages weren't touched, so not yet resident) | no WebAssembly |
| 1 GB handed to the host's crypto | **worker killed** | stopped at the limit |

The kernel's cap held every time, so the node was never at risk. But on V8, a single script took down its whole worker, and with it every service the tenant had on that worker. On GraalJS, the run that misbehaved ended with an error, and its neighbours carried on.

**Buffer's codecs, rewritten during the comparison:**
- **The change:** hex and base64 are now built in typed arrays, and hex decoding follows Node exactly (`'1g'`, `' 1'` and `'+1'` decoded to `01` before, and give nothing now).
- **The gain:** buffer-codec went from about 300 ms to 73 ms on V8, and from 765–2,500 ms to 74 ms in GraalJS's isolate. The earlier figures were taken with turbo on, so the real gain is if anything larger.
- **The road not taken:** a Java codec was measured and dropped (NOTES.md).

### The trade-offs
GraalJS is good, and it's better for security; V8 is faster.

| | V8 through Javet | GraalJS (Community Edition), isolated |
|---|---|---|
| **The engine itself** | C++. Memory-corruption bugs are the usual route out of a JavaScript sandbox, and V8's fixes reach us only through Javet's releases | Java, a memory-safe language, so that class of bug doesn't arise in the engine. Fixes come with GraalVM's releases |
| **Memory a script can take** | Only the heap is bounded. 7 of 9 attacks got past it, and only the kernel stopped them by killing the worker | Every byte is counted. 7 of 9 attacks stopped at the run, 1 ran in bounded memory, and none reached the kernel's cap |
| **One script's failure** | Takes down every service on its worker | Ends that run only |
| **Per-run memory limits** | Need Javet patches (§5) | Built in, but they need 2–4× headroom over what a script holds |
| **Warm speed** | The reference | Level on numeric code and the event loop. 2–9× slower on object-heavy and JSON-heavy code. 1.3–2.7× slower when moving bytes through host APIs |
| **Cold speed** | Fast from the first run | 1.4–18× slower until compiled (median 6.6×) |
| **Tail latency** | Steady | Longer garbage-collection pauses |
| **Memory** | 3 MB per idle service | 5 MB per idle service plus about 285 MB for the isolate. 2–8× V8's under load |
| **Node compatibility** | The reference; conformance 136/136 | Conformance 136/136. No `rejectionHandled`, different error wording, ICU4J's `Intl`, no WebAssembly unless added |
| **Licence** | Javet: Apache 2.0. V8: BSD | UPL/MIT (Community Edition). Oracle GraalVM measured (below) |

**Oracle GraalVM (Enterprise Compiler) Measured (2026-10-10, `results/2026-10-10-1701`):**
We measured `worker-graaljs` built with Oracle GraalVM 25.4.4.1.1 under GFTC against GraalJS Community Edition (CE):
- **Object / JSON gap remains identical:** `deltablue` was 5.25× slower than V8 (vs 5.06× on CE), `json-roundtrip` was 8.51× slower (vs 8.58× on CE), and `service-router` was 6.17× slower (vs 5.92× on CE).
- **Security & Sandboxing:** Identical 100% containment; stopped 7 of 9 memory attacks inside the isolate without reaching the kernel cap.
- **Where GraalJS is faster:** GraalJS decisively beats V8 on regular expressions (`regex-tokenize` 3.5× faster), URL parsing (2.6× faster), pure-JS bitwise crypto (`sha256-js` 2.5× faster), event listener dispatch (`events` 1.4× faster), and string building (1.4× faster).
- **Conclusion:** Because Oracle GraalVM does not close the Truffle guest language object/JSON gap, there is no technical justification to take on Oracle's GFTC redistribution and licensing restrictions. **Community Edition (CE) is the permanent default for GraalJS.**

**What this points to (the choice is Tony's, resolved in D-127):**
- **GraalJS CE as the multi-tenant default:** where scripts are untrusted and the engine is the attack surface.
- **V8 kept for trusted or speed-critical work:** available via `AUTOMATELY_WORKER_ENGINE=javet` when raw JSON throughput and immediate cold starts are prioritized.

## 9. Order of work
1. **The engine-neutral layer, with Javet only (Step 1, complete):** no change in behaviour, the same test counts (`5d35cd2`).
2. **The GraalJS adapter (Step 2, complete):** `automately-runtime-graaljs` passing 137/137 conformance tests (`3b22e8a`).
3. **The native-image workers and comparison (Step 3, complete):** `automately-worker` benchmarked and results characterized (§8, `955e273`).
4. **Engine selection and protocol specification (D-127, complete):** Engine choice is deployment-configurable (`AUTOMATELY_WORKER_ENGINE`). Standalone protocol specified in [worker-node-protocol.md](worker-node-protocol.md).
5. **Next: Step 4 implementation:**
   - Protocol codec and models (`automately-runner-protocol`).
   - Remote `HostApis` proxy for the worker and node-side dispatcher.
   - Worker pool supervisor with Linux cgroup memory caps.
   - gVisor / Firecracker runner integrations.
