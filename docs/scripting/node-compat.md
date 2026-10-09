# Node compatibility in the isolate

Scripts run in a V8 isolate, not in Node. The isolate has no processes, sockets, native code or host files, and that's the sandbox. To run npm code anyway, it provides **stand-ins** for Node's modules (polyfills), backed either by pure JavaScript or by the JVM. This page is the reference for all of them: what each has, what it lacks, its limits, and where it behaves differently from Node.

For code that needs what the isolate can't give (child processes, raw sockets, native addons), there's the Node runtime ([D-084](../DECISIONS.md), designed, not built yet).

## The rules every stand-in follows

These come from the polyfill plan ([design](../design/isolate-polyfills.md), D-095):

1. **Reach only through the host's gates.** A stand-in is computation, or it goes through a host API: `fs` is the tenant's files, and the network (`fetch`, `http`, `https`, `net`, `tls`) goes through the egress rules (D-108, D-111). They always refuse the cluster's own nodes, and private addresses unless an operator allowed them. Nothing reads the host's files, environment or network interfaces, or starts a process, and nothing listens: servers are reached by hostname through the ingress (D-112).
2. **Host-backed work is capped.** Anything done in Java (hashing, ciphers, compression, key derivation) has limits on size, count and memory, so a script can't exhaust its node through it. The limits are listed per module below.
3. **Per-job state dies with the job.** Open hashes, ciphers and compression streams belong to one execution and are dropped when it ends. Nothing is shared between jobs.
4. **Unavailable means unavailable, clearly.**
   - **At publish:** importing a module the isolate doesn't have stops the publish, with the reason. A CommonJS `require()` of one is the exception: it throws when it runs, as a missing module does in Node, so code that tries it inside `try` and falls back still works ([bundling](#bundling)).
   - **At run time:** a function that isn't implemented yet throws `ERR_FEATURE_UNAVAILABLE_ON_PLATFORM` or `ERR_NOT_SUPPORTED`, so it fails rather than half-working. Some modules exist only for the parts that don't connect or start anything (`http2.constants`, `tty.isatty`, `child_process`'s names): using the rest throws, with a message saying so.
5. **The isolate reports the Node release it's checked against ([D-115](../DECISIONS.md), replacing D-102).** `process.version` is `'v26.8.1'`. got won't load without a version, and jsonwebtoken allows PS256 only on versions it knows. `RealNpmPackagesTest` runs against that release, so the two move together.
6. **Checked against real Node.** Behaviour is compared with real Node's output, not just with documentation; see [how it's verified](#how-compatibility-is-verified).

## At a glance

Every module can be imported with or without `node:` (`import fs from 'fs'` or `'node:fs'`), and CommonJS `require()` of them works in bundled code ([bundling](#bundling)).

| Module | Status | Backed by | Decision |
|---|---|---|---|
| `fs`, `fs/promises` | Most of it, with `createReadStream`/`createWriteStream` | The tenant's files and the job's `/tmp` | D-079, D-109 |
| `path`, `path/posix` | Complete (POSIX) | JavaScript | D-090 |
| `events` | Complete, with `on` and `once` (signals included) | JavaScript | D-090, D-114, D-116 |
| `buffer` | Nearly complete | JavaScript | D-090, D-116 |
| `url` | Most of it | JavaScript | D-090 |
| `util`, `util/types` | The common parts | JavaScript | D-090, D-116 |
| `process` | A job-shaped subset; also a global; reports Node 26.8.1 | JavaScript | D-093, D-096, D-115 |
| `crypto` | Nearly complete | The JVM | D-097, D-101, D-104 |
| `stream`, `stream/promises` | Complete (Node's own code) | Vendored `readable-stream` 4.8.0 | D-100 |
| `string_decoder` | Complete (Node's own code) | Vendored `string_decoder` 1.3.0 | D-100 |
| `zlib` | Everything but Brotli compression | The JVM's zlib, a Java Brotli decoder | D-103 |
| `assert`, `assert/strict` | Complete | JavaScript | D-103 |
| `querystring` | Complete | JavaScript | D-103 |
| `timers`, `timers/promises` | Complete | The isolate's timers | D-103 |
| `os` | Describes the job, not the machine | JavaScript | D-103 |
| `http`, `https` | The client, and servers reached by hostname | The host's HTTP client, through the egress gate; servers through the cluster's ingress | D-108, D-109, D-123 |
| `async_hooks` | `AsyncResource` complete; `AsyncLocalStorage` without `run` | JavaScript | D-123 |
| `perf_hooks` | `performance`, observers of marks and measures | JavaScript | D-123 |
| `worker_threads`, `module`, `v8` | Their names, for what libraries read when they load; no threads, no loading code at run time, no V8 internals | JavaScript | D-123 |
| `net` | Outbound TCP sockets, as Node's; no servers | The host's sockets, through the egress rules | D-109, D-114, D-116 |
| `tls` | Outbound TLS sockets and upgrades, as Node's; no servers | The host's sockets, through the egress rules | D-109, D-114, D-116 |
| `child_process` | Its names only: starting a process throws | JavaScript | D-116 |
| `http2` | The client, as Node's; no servers | The host's HTTP/2 connections, through the egress rules | D-109, D-117 |
| `tty` | `isatty()` is false: a job has no terminal | JavaScript | D-109 |
| `diagnostics_channel` | Complete: channels, `tracingChannel` | JavaScript | D-114 |
| `dns`, `dns/promises` | As Node's; answers show only what the tenant could reach | The host's resolver: the operating system for `lookup`, DNS servers for the rest | D-114, D-122 |
| `stream/web` | The WHATWG streams, the same classes as the globals | JavaScript (web-streams-polyfill, vendored) | D-124 |
| `automately:kv`, `:bus`, `:jobs`, `:files`, `:secrets`, `:fetch`, `:log` | The host APIs as modules | The host | D-091 |

**Refused:** `dgram`, `cluster`, `vm`, `inspector`, `readline`, `repl`, `domain`, `punycode`, `trace_events`, `wasi`, `sys`, `console` (as a module) and `constants`.
- **When:** importing one stops the publish ("Node's "dns" module isn't available in the isolate …"). A CommonJS `require()` of one throws when it runs instead, with a warning at publish.
- **What's coming** ([network reach](../design/network-reach.md)): WebSockets, through the ingress and the egress rules.
- **The rest** need the Node runtime.

## Globals

**Present:**

| Global | Notes |
|---|---|
| `globalThis` | `global` is rewritten to `globalThis` when code is bundled; at run time there's no `global`. |
| `console` | `log`, `info`, `warn`, `error`, `debug`, `dir`, `table`, `trace` (with the caller's stack), `assert`, `group`/`groupEnd` (indenting), `time`/`timeLog`/`timeEnd` and `count`/`countReset` (D-105). Lines go to the job's log, and secrets declared in `env` are masked in it (D-096). A line is cut at about 16 KiB, and an attempt keeps its last 1,000 lines up to 1 MiB (D-107; see [where a job's log goes](host-apis.md#log-structured-logging)). `table` and `dir` print JSON, not a table. |
| `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval` | The isolate's event loop; at most 10,000 pending timers; the shortest delay is 1 ms. |
| `process` | The `process` stand-in (D-096); see [process](#process). |
| `crypto`, `CryptoKey` | Web Crypto; see [Web Crypto](#web-crypto-the-global-crypto). |
| `TextEncoder`, `TextDecoder` | UTF-8 only. Decoding follows the WHATWG rules, so a broken sequence gives one U+FFFD (D-103), but a leading byte-order mark isn't removed, and there's no `{ stream: true }` or `fatal` option. |
| `fetch`, `Headers`, `Request`, `Response`, `FormData` | Node's (undici's), through the egress gate (D-124): redirects followed, gzip and deflate decoded, Node's default request headers, multipart `FormData` both ways, and Node's errors. The host's older fetch is `automately:fetch` ([host APIs](host-apis.md#fetch-outbound-http)). |
| `Blob`, `File` | Node's, and the same classes as `buffer`'s: parts, `type` normalisation, `endings`, `slice`, `text`/`arrayBuffer`/`bytes`/`stream`. |
| `ReadableStream`, `WritableStream`, `TransformStream` and the rest of the WHATWG streams | Readers (default and BYOB), controllers, `pipeTo`/`pipeThrough`, `tee`, async iteration and the queuing strategies, from web-streams-polyfill 4.3.0, vendored. Some argument errors keep the polyfill's wording; the ones scripts meet (a bad `highWaterMark`) are Node's. |
| `TextEncoderStream`, `TextDecoderStream`, `CompressionStream`, `DecompressionStream` | `gzip`, `deflate` and `deflate-raw`, on the host's zlib; Brotli decompresses but doesn't compress. |
| `MessageChannel`, `MessagePort`, `MessageEvent`, `BroadcastChannel` | Within the job: messages are `structuredClone`d and delivered on a later turn, and ports have Node's `on`/`once`. `BroadcastChannel` reaches the channels of the same job only; there are no workers or other jobs to reach. |
| `fs`, `kv`, `bus`, `jobs`, `files`, `secrets`, `log` | Automately's host APIs ([host APIs](host-apis.md)). |
| `URL`, `URLSearchParams` | The WHATWG URL for the common cases, with `URL.canParse`/`URL.parse` (D-106). Hostnames aren't converted to punycode, and IPv6 isn't normalised. |
| `AbortController`, `AbortSignal` | With `AbortSignal.abort`/`timeout`/`any`, `throwIfAborted`, and `onabort` running in the order it was set (D-106). `node:stream` and `timers/promises` use them. |
| `EventTarget`, `Event`, `CustomEvent` | Listener objects (`handleEvent`), `once`, `signal` (to remove), `stopImmediatePropagation` and `preventDefault`. There's no capture or bubbling: there's no tree. A listener's error is reported, not thrown to the dispatcher. |
| `DOMException` | With `name`, `code`, the legacy constants (`ABORT_ERR`, …) and `cause`. Web Crypto and aborts use it. |
| `structuredClone` | Plain objects and arrays, Date, RegExp, Map, Set, ArrayBuffer, typed arrays, DataView, errors (name, message, stack, cause), boxed primitives, BigInt and cycles. Class instances become plain objects. Functions, symbols, promises and weak collections throw a `DataCloneError`. `transfer` detaches ArrayBuffers where V8 can. |
| `atob`, `btoa` | With the standard's errors (`InvalidCharacterError`). |
| `queueMicrotask`, `setImmediate`, `clearImmediate` | `setImmediate` runs in the event loop's check phase, as Node's (D-121); see [the event loop](#the-event-loop). A throw in a `queueMicrotask` callback fails the job, as an uncaught exception ends Node (D-109). |
| `performance` | `now()` (whole milliseconds since the job started, deliberately coarse), `timeOrigin`, `mark`/`measure`/`getEntries…`/`clear…` (at most 10,000 entries). |
| `WebAssembly`, `SharedArrayBuffer`, `Atomics`, `WeakRef`, `FinalizationRegistry`, `Intl` | V8's own. `Intl` has full ICU data (D-093). WebAssembly runs inside the same isolate and limits; there are no threads to share memory with. |

**Absent:**
- **`WebSocket`:** network reach (phase B, step 7).
- **`Buffer`:** not a global. The bundler adds an import where bundled code uses it.
- **Never:** `require`, `module`, `exports`, `__dirname` and `__filename`. Scripts are ES modules, and the bundler converts CommonJS. `window`, `self`, `navigator` and `location` are absent too: this isn't a browser.

**What the globals cost:** nothing, net. The WHATWG ones (D-124) load on first use: each group (streams, `Blob`, fetch, messaging, the text and compression streams) is compiled from the code cache the first time a script touches one of its names, so a job that never does pays nothing. They were added after a start-up benchmark (D-106). The prelude and built-in modules are compiled once per node and then come from V8's code cache, so a trivial job starts in about 3.5 ms (p50) with them, against 4.9 ms before. Numbers are in [benchmarks](../benchmarks.md#isolate-start-up).

---

## `fs`

Your files as one tree from `/`, with the job's own `/tmp`. The full guide is in [host APIs](host-apis.md#fs-the-file-system).

- **There:**
  - **Calls:** `readFile`, `writeFile`, `appendFile`, `mkdir`, `readdir` (with `withFileTypes`), `stat`/`lstat`, `access`, `exists` (sync only), `rename`, `copyFile`, `rm`, `rmdir` and `unlink`. Each has promise (`fs.promises`), callback and `…Sync` forms.
  - **File handles:** `fs.promises.open`, `openSync`/`readSync`/`writeSync`/`closeSync`.
  - **Streams (D-109):** `createReadStream` (with `start`, `end`, `highWaterMark`, `encoding`, `flags`) and `createWriteStream` (with `flags` and `start`), and the `ReadStream`/`WriteStream` classes. They're Node streams (`node:stream`), so `pipeline` and `for await` work. A write stream publishes the file when it closes, so readers never see half of it.
  - **Locks:** advisory `fs.lock`.
- **Not there:**
  - `watch`, symlinks, `chmod`/`chown`, `mkdtemp`, `realpath` and file descriptors as numbers beyond the handle calls.
- **Differs from Node:**
  - **Links:** `lstat` is `stat`, since there are no links.
  - **Writes:** each write publishes a new version of the file at once. Readers never see a half-written file.
  - **Errors:** Node's codes (`ENOENT`, `EEXIST`, `EROFS`, …), with the cluster's own in `e.reason`.
- **Limits:**
  - `readFile` reads at most 32 MB, and `appendFile` and handles work on files up to 64 MB and 32 MB.
  - **Streams** work through file handles, so they hold the whole file and have the handles' 32 MB limit. For bigger files, use `files.stream` and `files.write`, which stream from and to storage.
  - A sync call waits at most 30 s and blocks the script meanwhile.
  - `/tmp` is 8 MB in memory, then spills to disk up to 1 GB.

## `path`

POSIX paths, following Node's own algorithms: `join`, `resolve`, `normalize`, `relative`, `dirname`, `basename`, `extname`, `parse`, `format`, `isAbsolute`, `toNamespacedPath`, `sep`, `delimiter`, and `path.posix`.

- **Differs:** there's no current directory, so `resolve` resolves against `/`, as `process.cwd()` says.
- **Not there:** `path.win32`.

## `events`

`EventEmitter`, as Node has it.
- It's a constructor function, so `EventEmitter.call(this)` with `util.inherits` works, as well as `class extends`.
- **API:** `on`/`addListener`, `once`, `off`/`removeListener`, `removeAllListeners`, `prependListener`/`prependOnceListener`, `emit`, `listeners`, `rawListeners`, `listenerCount`, `eventNames` and `setMaxListeners`/`getMaxListeners`, plus `errorMonitor`, `defaultMaxListeners` and `events.once(emitter, name)` (a promise).
- **`events.on(emitter, name, { signal, close })`** is an async iterator over the event's emissions, as in Node (D-114). It throws on an `error` event, ends on any `close` event or when the loop breaks, and rejects with an `AbortError` when the signal aborts. Emissions wait in a queue until the loop asks for them; Node's `highWaterMark` pausing isn't there.
- **`events.once`** takes `{ signal }` too.
- **`emitter.once()` and `prependOnceListener()` add through `this.on()`/`this.prependListener()`,** as Node's do, so a subclass's override sees them: a stream with only a `once('data')` listener flows (D-116; before, it stalled).
- **Not there:** `captureRejections`. (`EventTarget` is a global; see [Globals](#globals).)

## `buffer`

`Buffer`, a `Uint8Array` subclass with Node's methods.
- **Encodings:** `utf8`, `hex`, `base64`, `base64url`, `latin1`/`binary`, `ascii` and `utf16le`/`ucs2`.
- **Methods:** `from`, `alloc`, `allocUnsafe`, `concat`, `compare`, `byteLength`, `isBuffer`, `isEncoding`, `toString`, `write`, `copy`, `fill`, `indexOf`/`includes`, `equals`, `toJSON`, and every `read…`/`write…` for 8, 16 and 32-bit integers, floats, doubles and 64-bit BigInts in both byte orders. Also `atob`/`btoa`, `kMaxLength` and `constants`.
- **`slice`** shares memory, as in Node.
- **`copy`** follows Node's rules: it copies what fits, returns the count, copies nothing when a start is at or past the end, and throws Node's `ERR_OUT_OF_RANGE` for negative starts (D-116).
- **Enumerable, as Node's:** the statics (`from`, `alloc`, …) and every prototype method, so code that copies them with `for…in` works. safer-buffer and mysql2 do (D-116).
- **Differs:** there's no pool. `poolSize` exists, but `allocUnsafe` returns zeroed memory, which is safer and otherwise the same.
- **`Blob` and `File`** are the globals (D-124), and `isUtf8`/`isAscii` are there.
- **Not there:** `transcode`, `swap16/32/64`, and `readIntLE/BE` of arbitrary widths.

## `url`

`URL` and `URLSearchParams` (the same as the globals, D-106), following the WHATWG rules for the common cases:
- special schemes (http, https, ws, wss, ftp, file) with an authority
- other schemes with opaque paths
- relative references, dot segments, default ports and percent-encoding

Also `fileURLToPath`, `pathToFileURL`, `urlToHttpOptions`, and the legacy `parse`, `format` and `resolve`.
- **Differs:** hostnames are lowercased but not converted to punycode, and IPv6 addresses aren't normalised.

## `util`

The parts npm code commonly uses:
- **Formatting and functions:** `inspect`, `format`, `formatWithOptions`, `promisify`, `callbackify`, `inherits`, `deprecate`, `debuglog`, `isDeepStrictEqual` and `stripVTControlCharacters`.
- **Type checks:** `types.*` (`isDate`, `isRegExp`, `isPromise`, `isMap`, `isSet`, typed arrays, …), also as the module `util/types` (D-116; pg requires it), and the legacy `is…` functions.
- **Text:** `TextEncoder` and `TextDecoder`.
- **Differs:** `inspect` is a readable approximation of Node's output, not a byte-for-byte copy. Don't compare its strings.
- **Not there:** `parseArgs`, `styleText`, `MIMEType` and `getSystemErrorName`.

## `process`

A job-shaped `process`, also available as a global (D-096).
- **`env`:** exactly what the job declared, plus `NODE_ENV=production`. It's never the node's environment.
  - Secrets are referenced by name (`{"secret": "stripe-live"}`), read when the run starts, and only for tokens with `secrets:use` (D-068). Their values are masked in the job's log.
  - The full guide is in [host APIs](host-apis.md).
- **There:** `nextTick` (a microtask; a throw in its callback fails the job, as an uncaught exception ends Node), `hrtime` and `hrtime.bigint`, `uptime`, `cwd()` (`/`), `platform` (`linux`), `arch` (`x64`), `argv` (`[]`), `pid` (1), and `stdout.write`/`stderr.write` (to the log). Also `on`/`emit` and the other listener methods.
- **`emitWarning(warning[, type | options][, code][, ctor])`, as Node's (D-118):** it makes the warning an `Error` named after its type, with its `code` and `detail`, and emits it as `warning` on the next tick. It writes Node's line to the log, `(node:1) [DEP0194] DeprecationWarning: …`. `noDeprecation` drops deprecations, `throwDeprecation` throws them, and `noProcessWarnings` keeps the line out of the log. `util.deprecate(fn, message, code)` goes through it once.
- **Events emitted:** `warning`, `unhandledRejection` and `rejectionHandled`, as in Node (D-110). Without an `unhandledRejection` listener, a promise rejected with no handler by the end of a turn fails the job.
- **Differs:**
  - `version` is `'v26.8.1'` and `versions` is `{ node: '26.8.1' }`: the Node release the stand-ins are checked against (D-115, replacing D-102). got won't load without one, and jsonwebtoken allows PS256 only on versions it knows. `RealNpmPackagesTest` fails if it's run with another release, so the two move together. Other `versions` entries (`v8`, `uv`, …) aren't there. `memoryUsage()` is zeros.
  - `Object.prototype.toString.call(process)` is `[object process]`, as in Node (D-109). axios checks it to choose its Node adapter.
  - `exit()`, `abort()`, `chdir()` and `binding()` throw: return from the script instead of exiting.
  - `hrtime` counts from the job's start, in milliseconds' precision.

## `crypto`

Node's `crypto` on the JVM's own cryptography (D-097, D-101, D-104). The full list is in [host APIs](host-apis.md#crypto).

- **Randomness:** `randomBytes`, `randomFill(Sync)`, `randomInt`, `randomUUID` and `getRandomValues`, from `SecureRandom`.
- **Hashing:** `createHash`/`createHmac` (md5, sha1, sha224/256/384/512, sha512-224/256 and sha3-*; incremental, with `copy()`), `hash()` and `timingSafeEqual`.
- **Key derivation:** `pbkdf2`, `hkdf` and `scrypt`, each with `…Sync`.
- **Ciphers:** `createCipheriv`/`createDecipheriv` with AES-128/192/256 in CBC, CTR and GCM, and ChaCha20-Poly1305.
- **Keys:**
  - `KeyObject` (secret, public and private).
  - `createSecretKey`, and `createPrivateKey`/`createPublicKey` from PEM, DER (pkcs1, pkcs8, sec1, spki), JWK or an X.509 certificate.
  - `export` to PEM, DER or JWK.
- **Signatures:** `sign`/`verify` and `createSign`/`createVerify`. RSA (PKCS#1 v1.5 and PSS), ECDSA on P-256/384/521 (DER or IEEE P1363) and Ed25519.
- **Key generation:** `generateKeyPair(Sync)` for rsa, ec, ed25519 and x25519, and `generateKey(Sync)` for hmac and aes.
- **RSA encryption:** `publicEncrypt`, `privateDecrypt`, `privateEncrypt` and `publicDecrypt`, with OAEP (any hash, a label), PKCS#1 v1.5 or no padding.
- **Key agreement:** `createECDH` (every point encoding, plus `ECDH.convertKey`), and `diffieHellman()` for ECDH and X25519. `getPrivateKey()` drops leading zero bytes, as Node's does (D-114), so a generated key is sometimes a byte shorter than the curve's size.

**Differs from Node:**
- **`privateDecrypt` with PKCS#1 v1.5 padding is refused (D-104).**
  - **The risk:** that decryption leaks through its timing (the Marvin attack, CVE-2023-46809).
  - **Why Node allows it:** where OpenSSL does "implicit rejection".
  - **Why the isolate refuses it:** the JVM doesn't have implicit rejection.
  - **What to do instead:** use OAEP, which is the default anyway.
- **`Hash`, `Cipher`, `Sign` and the other classes aren't streams.** They have `update`/`digest`/`final`/`sign`, and `Sign`/`Verify` also take `write`/`end`, but they can't be `pipe()`d.
- **`Cipher.update` output.** A cipher's string output (`'base64'`, `'utf8'`) carries partial groups across `update` calls as Node's does. Authenticated decryption (GCM, ChaCha20-Poly1305) returns nothing from `update` and everything from `final`, once the tag is checked. Node streams it out earlier, but the concatenated result is the same.
- **GCM tags** must be 12 to 16 bytes, which is the JVM's range; Node also accepts 4 and 8.
- **`asymmetricKeyDetails.publicExponent`** is a BigInt, as in Node.

**Not there:**
- **Algorithms and keys:** classic Diffie-Hellman (`createDiffieHellman`, `getDiffieHellman`), rsa-pss/dsa/ed448/x448 keys, and secp256k1 (the JVM no longer has it).
- **Encrypted private keys:** both reading (an encrypted PEM) and writing (`export` with a `cipher`).
- **Other ciphers:** AES-ECB, DES and the legacy `createCipher`.
- **Process-level calls:** `setEngine`, `secureHeapUsed`, FIPS and `crypto.Certificate`.

**Limits:**
- **Randomness:** 64 KiB per host call (larger requests are chunked for you).
- **Open state:** 1,000 open hashes and ciphers per execution.
- **PBKDF2:** ≤ 1,000,000 iterations and ≤ 4,000,000 HMACs in all (iterations × output blocks).
- **scrypt:** ≤ 64 MB of memory whatever `maxmem` says, and parallelization ≤ 16.
- **Derived output:** ≤ 64 KiB.
- **RSA:** keys generated ≤ 4,096 bits, keys read ≤ 8,192.
- **Data:** ≤ 32 MB signed, verified or decrypted (authenticated) at once.
- **Heavy work:** PBKDF2, scrypt and key generation run on the node's small crypto pool. A `…Sync` call waits for it, for at most 60 s.

## Web Crypto: the global `crypto`

`crypto.getRandomValues` (≤ 65,536 bytes, as the standard says), `crypto.randomUUID`, `crypto.subtle` and `CryptoKey` (D-097, D-104). `require('crypto').webcrypto` is the same object.

- **`subtle`:** `digest`, `generateKey`, `importKey`/`exportKey` (raw, pkcs8, spki, jwk), `sign`/`verify`, `encrypt`/`decrypt`, `deriveBits`/`deriveKey` and `wrapKey`/`unwrapKey`.
- **Algorithms:**
  - **MAC:** HMAC
  - **AES:** AES-GCM, AES-CBC and AES-CTR
  - **RSA:** RSASSA-PKCS1-v1_5, RSA-PSS and RSA-OAEP
  - **Elliptic curves:** ECDSA and ECDH on P-256/384/521, Ed25519 and X25519
  - **Key derivation:** PBKDF2 and HKDF
- **Rules:** Web Crypto's own: usages, extractability, and its errors, which are `DOMException`s as in Node (D-106): `InvalidAccessError`, `NotSupportedError`, `DataError`, `OperationError` and `SyntaxError`.
- **Not there:** AES-KW. Also an AES-CTR counter that would wrap within a `length` under 128 bits: it's refused, because the JVM counts with all 128 bits.

## `stream`, `stream/promises` and `string_decoder`

Node's own code: `readable-stream` 4.8.0 and `string_decoder` 1.3.0 (MIT), bundled onto the isolate's `buffer`, `events` and `process` (D-100).
- **What's there:** everything Node's `stream` exports.
  - **Classes:** `Readable`, `Writable`, `Duplex`, `Transform` and `PassThrough`.
  - **Functions:** `pipeline`, `finished`, `compose`, `addAbortSignal`, `destroy`, `duplexPair`, `isReadable`/`isErrored`/`isDisturbed`, `get`/`setDefaultHighWaterMark`, `Readable.from` and async iteration.
  - **`stream/promises`:** `pipeline` and `finished`.
- **Differs:**
  - **Platform streams:** `fs.createReadStream`/`createWriteStream`, `http`'s requests and responses, and `zlib`'s streams are Node streams (D-109). So are `net`/`tls` sockets. The global `fetch`'s bodies are WHATWG streams (`Readable.fromWeb`); `automately:fetch` bodies and `files.stream` are async iterables (`Readable.from`).
  - **Web Streams:** `Readable`, `Writable` and `Duplex` have Node's `fromWeb`/`toWeb` (D-124).
- **Where it comes from:** `scripts/vendor-stream` rebuilds it from hash-checked packages, and the output is committed.

## `zlib`

Compression on the JVM's own zlib, plus a pure-Java Brotli decoder (`org.brotli:dec`, MIT) (D-103).
- **What's there:**
  - **Codecs:** deflate/inflate, gzip/gunzip, deflateRaw/inflateRaw, `unzip` (gzip or zlib, detected) and `brotliDecompress`.
  - **Three forms of each:** `…Sync`, callbacks (so `util.promisify` works) and streams (`createGzip`, …, as `stream` Transforms).
  - **Extras:** `crc32`, `constants` and the `Z_*` constants, and `codes`.
  - **Gzip details:** concatenated gzip members are read, and errors carry zlib's messages and Node's `code`/`errno` ("incorrect header check", `Z_DATA_ERROR`, -3).
  - **Options:** `level`, `strategy` (default, filtered, Huffman only), `dictionary` and `maxOutputLength`.
- **Differs:**
  - **Compressed bytes** may differ from Node's: Node uses Chromium's zlib fork, which can choose differently. Both are valid, and each side reads the other's.
  - **`flush()`** completes at once, since output is handed on as it's made.
  - **A Brotli stream** decodes when it ends, not as it goes.
- **Not there:** Brotli **compression** (no pure-Java encoder exists), `params()`, `Z_RLE`/`Z_FIXED`, and `windowBits`/`memLevel`, which are accepted and ignored.
- **Limits:**
  - **Output per call:** at most 32 MB (`ERR_BUFFER_TOO_LARGE`), or less with `maxOutputLength`. That's the guard against zip bombs.
  - **Brotli input:** 32 MB.
  - **Streams:** 1,000 open per execution.

## `assert` and `assert/strict`

All of `assert`: `ok`, `equal`/`notEqual`, `strictEqual`/`notStrictEqual`, `deepEqual`/`deepStrictEqual` and their `not…`, `throws`/`doesNotThrow`, `rejects`/`doesNotReject`, `match`/`doesNotMatch`, `ifError`, `fail`, `AssertionError` and `strict` (D-103).
- **Comparisons follow Node's rules:**
  - **Strict:** `Object.is`, prototypes and own symbols. **Loose:** `==`.
  - **Collections:** Maps and Sets compare unordered, typed arrays by their bytes, and Dates, RegExps and Errors by value. Cycles are handled.
- **Differs:** messages match Node's for simple values (`1 !== 2`), but are shorter for structures: there's no coloured diff.
- **Not there:** `partialDeepStrictEqual` and `CallTracker`.

## `querystring`

`parse`, `stringify`, `escape`, `unescape`, `decode` and `encode`, with Node's rules (D-103):
- `+` is a space
- a repeated key becomes an array
- at most 1,000 keys unless `maxKeys` says otherwise
- malformed escapes decode the way Node decodes them

## The event loop

Each turn runs as Node's does (D-121):
1. **I/O callbacks:** the host's answers, network reads and messages.
2. **The check phase:** the `setImmediate` callbacks queued before it began. Those queued during it wait for the next turn.
3. **Timers** that are due.

`process.nextTick` callbacks and microtasks run after every callback, and so does the unhandled-rejection check (D-110).

**Network reads are kept apart, as Node keeps them:**
- **What the host records:** which read each HTTP/2 frame arrived in.
- **What the script sees:** a later read reaches it on a later turn, after the immediates the earlier one queued. Frames that came in together are still handled together.

A fixture compares Node's guaranteed orderings with real Node.

## `timers` and `timers/promises`

- **`timers`:** `setTimeout`, `setInterval`, `setImmediate` and their `clear…`.
- **`timers/promises`:** `setTimeout`, `setImmediate`, `setInterval` (an async iterator) and `scheduler.wait`/`yield`. All of them accept a `signal` (D-103).
- **`setImmediate`** returns an `Immediate` (`ref`, `unref`, `hasRef`) and runs in the check phase, as Node's (D-121).
- **`setTimeout` and `setInterval` return a `Timeout`, as Node's (D-123):**
  - **Methods:** `ref`, `unref`, `hasRef`, `refresh`, `close` and `Symbol.dispose`.
  - **Its primitive is the timer's id,** so `clearTimeout(+timeout)` works too.
  - **Validation:** a callback that isn't a function throws `ERR_INVALID_ARG_TYPE`, and a delay past 2^31-1 ms becomes 1 with a `TimeoutOverflowWarning`.
- **Differs:**
  - **`unref()`** is remembered but changes nothing: a run lasts until its promise settles (or, in a service, while its servers listen), not while timers are pending.
  - **Signals:** `AbortController`'s, or any object shaped like an `AbortSignal`.

## `os`

Describes the job, never the machine (D-103):

| Call | Answers |
|---|---|
| `tmpdir()`, `homedir()` | `/tmp` |
| `cpus()`, `availableParallelism()` | one CPU: an isolate runs on one thread |
| `totalmem()`, `freemem()` | the isolate's heap limit |
| `hostname()` | `localhost` |
| `networkInterfaces()` | `{}` |
| `userInfo()` | `{ username: 'automately', uid: -1, gid: -1, homedir: '/tmp', shell: null }` |
| `platform()`, `type()`, `arch()`, `machine()`, `endianness()` | `linux`, `Linux`, `x64`, `x86_64`, `LE` |
| `release()`, `version()` | `''`: no kernel is described |
| `loadavg()` | `[0, 0, 0]` |
| `uptime()` | seconds since the module loaded |

`EOL`, `devNull` and `constants` (signals and priorities) are there too; `setPriority` throws.

## `http` and `https`

The client, as Node's, on the host's HTTP client (D-109). Every request goes through the egress gate first (D-108): public addresses are reached, private ones only by an operator's rule for your tenant, and the cluster's own nodes never.

- **There:**
  - **Functions:** `request` and `get`, taking a URL string, a `URL` or options, in any of Node's argument orders.
  - **Classes:** `ClientRequest` (a `Writable`), `IncomingMessage` (a `Readable`) and `Agent`.
  - **Values:** `METHODS`, `STATUS_CODES`, `globalAgent` and `maxHeaderSize`.
  - **Validators:** `validateHeaderName` and `validateHeaderValue`.
- **Requests:**
  - **Headers:** `setHeader`/`getHeader`/`removeHeader`/`hasHeader`, `appendHeader`, `getHeaders`, `getHeaderNames`/`getRawHeaderNames`, `flushHeaders` and `headersSent`. Values are checked as Node checks them (`ERR_INVALID_CHAR`).
  - **Options:** `method`, `headers` (an object, or Node's raw `[name, value, …]` list), `auth` and a URL's user and password, `timeout` and `setTimeout` (they emit `timeout` without aborting, as in Node), `signal`, `setHost` and `agent`. The protocol expected is the agent's, or else `_defaultAgent`'s, as in Node, so `http.request` with an https agent makes an https request (D-118).
  - **Bodies stream** with backpressure. One body given whole to `end()` is sent with its `Content-Length`, as Node does; S3 and other servers need that. Written in parts, it's sent in chunks unless you set `Content-Length` yourself.
  - **Events:** `socket`, `finish`, `response`, `timeout`, `abort`, `error` and `close`, in Node's order. `abort()` and `destroy()` work at any point. A request destroyed before its response fails with `ECONNRESET` "socket hang up", as in Node.
- **Responses:** `statusCode`, `statusMessage`, `httpVersion`, `headers` (built with Node's rules: `set-cookie` is a list, `cookie` is joined with `; `, and the single-valued headers keep their first value), `headersDistinct`, `rawHeaders`, `complete` and `aborted`. A response nobody listens for is read and dropped, as in Node. Its errors go only to an `error` listener; without one it emits `aborted`, as Node's does.
- **HTTPS:**
  - **Certificates are verified** against the JVM's trust store, with the host name checked.
  - **`ca`** adds trusted certificates, and **`cert`/`key`** (PEM) present a client certificate. A request with either gets a connection of its own, closed with it.
  - **`rejectUnauthorized: false`** is honoured (Tony's call): it only affects your own connection, and the job's log notes it once. `NODE_TLS_REJECT_UNAUTHORIZED=0` in the job's `env` does the same.
  - **Agents:** `https.Agent`'s TLS options apply to its requests.
- **Differs from Node:**
  - **No sockets of the script's own.** The host connects; `req.socket` is a stand-in with the methods libraries call (`setTimeout`, `setNoDelay`, `setKeepAlive`, `ref`/`unref`, `destroy`) and the real connection's details (D-114).
    - **While connecting:** `connecting` is true until the host has the connection.
    - **Then, in Node's order:** `lookup`, `connect`, `secureConnect` (HTTPS) and `ready`. `lookup` comes only for a host name, never an address, as in Node; it reports the address the egress gate checked. A connection reused from the host's pool sets `req.reusedSocket` and has no `lookup`.
    - **Addresses:** `remoteAddress`, `remotePort`, `remoteFamily`, `localAddress`, `localPort` and `address()` are real.
    - **TLS:** `authorized`, `authorizationError`, `getPeerCertificate(detailed)` (Node's shape: `subject`, `issuer`, `subjectaltname`, `valid_from`/`valid_to`, `serialNumber`, the fingerprints, `raw`, `pubkey`, `bits`, `modulus`/`exponent` or `asn1Curve`/`nistCurve`, `ext_key_usage`, `ca`, and the `issuerCertificate` chain), `getProtocol()` and `getCipher()`.
    - **TLS differences:** `getCipher().name` is the IANA name (`TLS_AES_128_GCM_SHA256`, or `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` where OpenSSL would say `ECDHE-RSA-AES128-GCM-SHA256`). With `rejectUnauthorized: false`, `authorized` says whether the JVM's trust store would have trusted the chain, without checking the host name, and `authorizationError` is one of `DEPTH_ZERO_SELF_SIGNED_CERT`, `SELF_SIGNED_CERT_IN_CHAIN`, `UNABLE_TO_VERIFY_LEAF_SIGNATURE` or `UNABLE_TO_GET_ISSUER_CERT_LOCALLY`. Sessions aren't reused (`isSessionReused()` is false).
  - **Agents don't pool.** `keepAlive` and `maxSockets` are accepted; the host pools connections per node.
  - **Errors carry Node's codes** where there's one (`ECONNREFUSED`, `ECONNRESET`, `ETIMEDOUT`, `ENOTFOUND`). A refused connection and an unknown host have Node's messages and fields too (`connect ECONNREFUSED 10.0.0.1:5432` with `address`, `port`, `syscall` and `errno`; `getaddrinfo ENOTFOUND db.example`) (D-114). A refusal by the egress gate has `code: 'egress_denied'` and says why.
  - **`maxHeaderSize`** is 64 KiB, the host's limit; Node's default is 16 KiB.
- **Refused, with an error that says why:** `createConnection` (it would bypass the gate), `socketPath`, `CONNECT`, upgrades to WebSocket, `pfx`, `passphrase` (encrypted keys), and a custom `checkServerIdentity` (the isolate never sees the certificate; `tls.checkServerIdentity` itself is accepted).
- **Ignored:** `lookup`, `family`, `localAddress`, `localPort` and `servername`. The gate does the resolving, and SNI is the request's host.
- **Servers:** `createServer`, `Server`, `ServerResponse` and `OutgoingMessage`; see [HTTP servers](#http-servers).
- **Limits:**
  - **Concurrency:** 16 open requests per execution, shared with `fetch`. A request counts until its response has been read or it's aborted.
  - **Timeouts:** 30 s to connect, and 5 min with no data either way.
  - **Response headers:** 64 KiB.
  - **Redirects:** none are followed, which is Node's behaviour too. Libraries that follow them (axios, node-fetch) make each hop a new request, and each goes through the gate.
  - **Cleanup:** an unfinished request is aborted when the job ends.
- **Proven with:** axios (its Node adapter: JSON, forms, gzip, redirects, timeouts, streams and binary bodies), node-fetch 3 and got 14 (D-114: JSON and form bodies, search params, auth, hooks, redirects, gzip, `HTTPError`, retries, `throwHttpErrors`, phase timeouts, refused connections, streams with progress, pagination, and which timing phases exist), all giving real Node's results.
- **got's `http2: true`** needs an HTTP/2 client, which comes in phase B's `http2` step.

## `net` and `tls`

Outbound TCP and TLS sockets, as Node's, on the host (D-116). The only restrictions are where they may go and how much they may use:
- **Where:** every connection goes through the egress rules as `tcp` (D-111). The default cluster rule refuses the mail ports (25, 465, 587), and an operator can change it.
- **How much** (the cluster policy's limits, shown by `automately egress`):
  - at most 16 sockets open per script
  - at most 256 per tenant on each node
  - at most 120 new connections a minute per tenant on each node
- **Timeouts:** 30 s to connect, and **no idle timeout**, as in Node.
- **Cleanup:** a script's sockets close when its run ends, whatever it left open. A job's result doesn't wait for them, as Node's process would.

**`net`:**
- **There:**
  - `net.connect`/`createConnection`, and `new net.Socket()` with `connect(…)` in Node's argument forms.
  - **The socket** is a real `Duplex`:
    - **Events:** `lookup` (names only), `connect`, `ready`, `data`, `end`, `close` (with `hadError`), `error`, `timeout` and `drain`, in Node's order.
    - **Before the connection exists,** writes are held, as Node holds them.
  - **Methods and state:** `end()` (a half-close: it stops sending and keeps reading), `setTimeout` (emits `timeout` without closing), `setNoDelay`, `setKeepAlive`, `address()`, `destroySoon`, `resetAndDestroy`, `readyState`, `pending`, `connecting`, and `bytesRead`/`bytesWritten`.
  - **Addresses:** `remoteAddress`/`Port`/`Family` and `localAddress`/`Port`/`Family` are read from the live connection, `undefined` before it opens and after it closes, as in Node.
  - **Errors are Node's,** codes, messages and fields alike:
    - `connect ECONNREFUSED 10.0.0.1:5432`, with `address`, `port`, `syscall` and `errno`
    - `getaddrinfo ENOTFOUND db.example`
    - `ETIMEDOUT`, `ECONNRESET`, `EPIPE`
    - `ERR_SOCKET_BAD_PORT`, in Node's wording
    - a refusal by the egress rules has `code: 'egress_denied'` and says why
  - **Address checks:** `isIP`/`isIPv4`/`isIPv6` with Node's patterns, and `get`/`setDefaultAutoSelectFamily` (plus the attempt timeout).
- **Differs:**
  - **`ref()`/`unref()`** are accepted and change nothing. A job ends when its promise settles, not when its sockets close.
  - **`allowHalfOpen: true`** keeps the socket open for writing after the peer ends only as far as the peer allows. The host doesn't keep a half-closed TLS connection: over TLS, `end()` closes.
  - **About 8 chunks wait on the host** for a script that isn't reading. After that the host stops reading, and TCP pushes back on the peer.
- **Refused:**
  - **Unix sockets** (`path`).
  - **Servers:** `net.createServer`, `net.Server`, `tls.createServer`, `tls.Server`. They never run on the cluster's nodes, where inbound traffic is HTTP by hostname (D-112); TCP and UDP servers belong on an Automately link's own host.

**`tls`:**
- **`tls.connect`** in Node's forms, and **`tls.connect({ socket })`**, which upgrades a connected `net.Socket` in place. That's how pg's SSLRequest, mysql2 and SMTP's STARTTLS work. The new `TLSSocket` takes over the connection, and the old socket closes with it, as in Node.
- **Verification, as Node does it:**
  - **The chain** is judged against `ca` if given (it replaces the defaults, as in Node), or else the JVM's trust store.
  - **A refusal** carries OpenSSL's code and message, as Node's error has them: `DEPTH_ZERO_SELF_SIGNED_CERT` "self-signed certificate; if the root CA is installed locally, …", `SELF_SIGNED_CERT_IN_CHAIN`, `UNABLE_TO_VERIFY_LEAF_SIGNATURE`, `UNABLE_TO_GET_ISSUER_CERT_LOCALLY`, `CERT_HAS_EXPIRED` and `CERT_NOT_YET_VALID`.
  - **The host name** is checked with `tls.checkServerIdentity`, Node's own algorithm (wildcards, IP addresses, the CN fallback), or with the script's own `checkServerIdentity`.
  - **`rejectUnauthorized: false`** (or `NODE_TLS_REJECT_UNAUTHORIZED=0` in the job's `env`) connects anyway, with `authorized: false` and the reason in `authorizationError`. The job's log notes it once.
  - **What the script writes waits until both checks pass.** The host completes the handshake, judges the chain, and closes the connection if it fails.
- **Options:** `ca`, `cert`/`key` (PEM), `servername` (SNI; by default the host name, never an address), `ALPNProtocols` (a list, or Node's wire format), `minVersion`/`maxVersion` (TLS 1.2 and 1.3), `secureContext`, `checkServerIdentity` and `rejectUnauthorized`.
- **`TLSSocket`:**
  - **Events and state:** `secureConnect`, `authorized`, `authorizationError`, `encrypted`, `alpnProtocol`.
  - **`servername`:** what the script asked for, else `false`, as on Node's client side.
  - **Peer and session details:** `getPeerCertificate(detailed)` (Node's keys in Node's order, `undefined` where they don't apply, with the `issuerCertificate` chain), `getProtocol()`, `getCipher()` (IANA names), `setServername`, `getEphemeralKeyInfo()`, `getFinished()`.
- **Also:** `tls.createSecureContext({ ca, cert, key })`, `tls.rootCertificates` (the JVM's trust store as PEM, read the first time it's used), `getCiphers()`, and the `DEFAULT_…` values.
- **Differs:**
  - **Sessions aren't reused** (`isSessionReused()` is false).
  - **Not offered:** `renegotiate()` fails with `ERR_TLS_RENEGOTIATION_DISABLED`, `exportKeyingMaterial()` throws, and `getPeerX509Certificate()` is `undefined`.
  - **`infoAccess`** in a peer certificate is always `undefined`.
  - **No PFX:** `pfx` and `passphrase` are refused; pass PEM.
  - **Wrapping a stream:** `new tls.TLSSocket(stream)` over a stream that isn't a `net.Socket` makes an unconnected socket with Node's internal `_handle._parentWrap` (http2-wrapper reaches for it when it loads). Running TLS over such a stream isn't supported.

## `http2`

Node's HTTP/2 client (D-117), on the host's HTTP/2 connections. A session counts as one of the script's sockets against the cluster policy's limits; each stream counts as one of its 16 concurrent requests, with `fetch` and `http`.

- **`http2.connect(authority[, options][, listener])`:**
  - **Over `https:`,** ALPN negotiates `h2`, and the session is verified as `tls` sockets are: the chain against the trust store or `ca` (Node's codes when it's refused), then the host name with `tls.checkServerIdentity` or the script's own check. `rejectUnauthorized: false` is honoured and logged once.
  - **Over `http:`,** it speaks HTTP/2 from the first byte (h2c), as Node's client does. A server that doesn't speak HTTP/2 refuses the TLS handshake (`ERR_SSL_TLSV1_ALERT_NO_APPLICATION_PROTOCOL`).
  - **Through the egress rules** as an `http` connection.
- **`ClientHttp2Session`:**
  - **Requests:** `request(headers, options)`, made before or after `connect` (streams wait for it), with `endStream` defaulting to Node's choice for `GET`, `HEAD` and `DELETE`.
  - **Control:** `ping(payload, callback)` (8 bytes, or Node's default), `settings(settings, callback)` (acknowledged before the callback), `goaway(code, lastStreamID, data)`, `close(callback)` (after open streams), `destroy`, `setTimeout`.
  - **Events:** `connect`, `remoteSettings`, `localSettings`, `goaway`, `close`, `error`, `timeout`.
  - **State:** `alpnProtocol` (`h2` or `h2c`), `encrypted`, `type`, `closed`, `destroyed`, `connecting`, `localSettings`, `remoteSettings`, `originSet`, `pendingSettingsAck`, and `socket` (the connection's addresses and TLS details).
  - **`state`,** as Node's (D-118), read from the connection, with `nextStreamID` counted as streams are submitted (D-120): `effectiveLocalWindowSize`, `effectiveRecvDataLength`, `nextStreamID`, `localWindowSize`, `lastProcStreamID`, `remoteWindowSize`, `outboundQueueSize`, and the HPACK tables' sizes (`deflateDynamicTableSize`, `inflateDynamicTableSize`). It's `{}` until connected and once destroyed.
  - **`outboundQueueSize` is nghttp2's queue, as Node reports it (D-119):** control frames (HEADERS, SETTINGS, PING, GOAWAY, RST_STREAM, trailers; not DATA) submitted and not yet written. Node writes them out on the next turn of the event loop, and the isolate keeps the same queue with the same flush:
    - **At `connect`:** 1, the client's SETTINGS.
    - **After `request()`:** 1 more, its HEADERS, until the next turn.
    - **At `remoteSettings`:** their ACK.
    - **Closing a stream whose HEADERS haven't gone:** adds nothing.
    - **During `data`:** the connection's WINDOW_UPDATE is counted once half its window has arrived, and is visible in that frame's handler and its ticks. The stream's never shows, since it's queued after them and written by the next turn (D-121).
    - **Where Node itself varies:** at `remoteSettings`, Node shows 1 or 2 depending on whether the peer's SETTINGS arrive before or after it wrote that turn's frames; the isolate shows them arriving after.
- **`ClientHttp2Stream`,** a `Duplex`:
  - **Events:** `response` (headers with `:status`, the HEADERS frame's real flags, raw headers), `trailers` (with its frame's flags), `wantTrailers`, `data`, `end`, `close` and `error`.
  - **State:** `id`, `rstCode`, `sentHeaders`, `sentTrailers`, `pending`, `closed`.
  - **`state`,** as Node's (D-118, D-120): `state` (nghttp2's numbers, as `constants.NGHTTP2_STREAM_STATE_*`), `weight`, `sumDependencyWeight` (0), `localClose`, `remoteClose` and `localWindowSize`. The isolate works it out as nghttp2 holds it when the script looks, never from the network's state at that moment:
    - **Before its HEADERS are written:** IDLE (1), with weight 0 and window 0. Node writes on the next turn of the event loop.
    - **Then:** OPEN (2) with weight 16, and half-closed local (5, `localClose` 1) once END_STREAM has been written. `end()` doesn't change it until then.
    - **The peer's END_STREAM** takes effect only after the handlers that showed it, as in Node: `remoteClose` is still 0 in `response`, `data` and `end`, and becomes 1 on the next turn (state 6 if our side is still open).
    - **The window** goes down by each DATA frame the script receives. Once half of it has arrived, nghttp2's WINDOW_UPDATE refills it after that frame's handlers and ticks, by the next turn (D-121).
  - **`session.state`'s `effectiveRecvDataLength` and `localWindowSize`** are the connection's receive window as nghttp2 keeps it. Its WINDOW_UPDATE goes out *before* the handlers of the frame that brings it to half (D-121).
    - **`{}`** before the stream is submitted and once it has closed.
  - **`ready` and `id`** come as the stream is submitted, as in Node, with the id nghttp2 would give (odd, in order). On a connected session that's inside `request()`, before a listener can be added.
  - **Answers arrive on a later turn than the write they answer,** as over a network, however quickly the host answered.
  - **Request trailers (D-118):** with `waitForTrailers: true`, a request with a body emits `wantTrailers` once the body has gone, and `sendTrailers(headers)` ends the stream with them. Node's errors apply:
    - `ERR_HTTP2_TRAILERS_NOT_READY` before `wantTrailers`
    - `ERR_HTTP2_TRAILERS_ALREADY_SENT` on a second call
    - `ERR_HTTP2_INVALID_STREAM` once closed
    - `ERR_HTTP2_INVALID_PSEUDOHEADER` and `ERR_HTTP2_INVALID_CONNECTION_HEADERS` for headers that can't be trailers

    As in Node:
    - with no `wantTrailers` listener, the stream ends with empty trailers
    - empty trailers go as an empty DATA frame that ends the stream
    - a request with no body (`GET`, or `endStream`) ignores `waitForTrailers`
  - **Priorities, as Node 26 has them:** `priority()` does nothing and returns `undefined`, and a request's `weight` isn't sent (RFC 9113 deprecated priority signalling). Either emits Node's warning once, `DEP0194` "Priority signaling has been deprecated as of RFC 9113.". `parent` and `exclusive` alone don't.
  - **Control:** `close(code)` sends RST_STREAM, and `setTimeout`.
  - **Headers follow Node's rules** (`set-cookie` a list, repeats joined, single-value headers keep the first).
  - **A reset from the peer:** `NO_ERROR` and `CANCEL` end the stream quietly. Any other code fails it with `ERR_HTTP2_STREAM_ERROR` "Stream closed with error code NGHTTP2_REFUSED_STREAM", as in Node.
- **Also:** `getDefaultSettings()`, `getPackedSettings()`/`getUnpackedSettings()`, `sensitiveHeaders`, and all 240 `constants`.
- **`createConnection`:** http2-wrapper (got's `http2: true`) opens its own TLS socket and hands it to `http2.connect`. If the socket is one of the isolate's own `net`/`tls` sockets, it already went through the egress rules, so the session closes it and opens its own connection to the same origin. Anything else is refused, since it would bypass the rules.
- **Differs:**
  - **Server push is off** (`enablePush: false`), as browsers have it now.
- **Refused:** `CONNECT` tunnels, `pfx`, and servers (`createServer`, `createSecureServer`). HTTP/2 servers never run on the cluster's nodes; the ingress serves clients.
- **Proven with:** a fixture against real Node over TLS and h2c, and got with `http2: true`. The fixture covers:
  - headers, bodies, parallel streams, response trailers, every kind of reset, ping, settings and refusals
  - request trailers with their errors and event order
  - the real flags
  - both `state` objects
  - priorities and their warning

## `dns` and `dns/promises`

Node's (D-122), on the host's resolver: the one the egress gate uses for every connection, so a script sees the network as it can reach it. Arguments, answers and errors are Node 26's, checked against real Node by the dnsclient fixture.
- **`lookup(hostname[, options], callback)`** asks the operating system, as Node's getaddrinfo does (hosts files, search domains, `*.localhost`), and keeps its order:
  - **Options:** `family` (0, 4, 6, `'IPv4'`, `'IPv6'`), `hints` (`V4MAPPED` and `ALL` included), `all`, `order` (`verbatim` by default, `ipv4first`, `ipv6first`) and `verbatim`.
  - **Answers:** an address needs no lookup. `util.promisify(dns.lookup)` gives `{ address, family }`.
  - **Errors:** a missing or empty name throws, as in Node 26. Failures are `getaddrinfo ENOTFOUND <name>` (errno -3008) or `EAI_AGAIN`.
- **The queries:** `resolve` (any type), `resolve4`/`resolve6` (with `{ ttl: true }`), `resolveAny`, `resolveCaa`, `resolveCname`, `resolveMx`, `resolveNaptr`, `resolveNs`, `resolvePtr`, `resolveSoa`, `resolveSrv`, `resolveTlsa`, `resolveTxt`, and `reverse`. They ask DNS servers as c-ares does: no search domains, no hosts file, each server in turn on each try, and TCP when an answer is truncated.
  - **Answers:** Node 26's shapes and key order, including `type` on MX, SRV and CAA. `resolveAny` groups its records as c-ares does. TTLs are the real ones, and IPv6 is written compressed.
  - **Errors:** `queryA ENOTFOUND <name>` and the rest: ENODATA, ESERVFAIL, EREFUSED, EFORMERR, ENOTIMP, ETIMEOUT, ECONNREFUSED, ECANCELLED.
- **`Resolver` and `promises.Resolver`:** `timeout` and `tries`, `cancel()`, `getServers()`, `setServers()` and `setLocalAddress()`. The module's functions share one default resolver with `dns.promises`, as in Node.
- **Servers:**
  - **By default:** the cluster's, which is `AUTOMATELY_EGRESS_DNS` when an operator set it, else the system's.
  - **A script's own (`setServers`):** each must pass the egress gate as a `dns` connection. A public resolver is fine; a server on a private network needs a rule (or the development switch), and a refused one fails the query with `ECONNREFUSED` and the reason.
- **`lookupService(address, port)`:** the system's name for the address (or the address itself), and the service from `/etc/services`.
- **What a script may see** (network-reach.md §8.2):
  - **Internal names** answer `ENOTFOUND` unless a rule names them: single labels, `.local`, `.internal`, `.lan`, `.home.arpa`, `.localhost`, `.cluster.local`, `.svc`, and the zones in `AUTOMATELY_EGRESS_INTERNAL_DOMAINS`.
  - **Addresses** are shown only if the tenant could connect to them on some port. An allow rule for one port is enough; a deny rule hides an address only if it covers every port and every protocol or `dns`.
  - **Records naming internal hosts** (MX, SRV, CNAME, NS, SOA, NAPTR) are left out; an answer left empty is `ENODATA`.
  - **`reverse` and `lookupService`** answer only for addresses the tenant could connect to.
  - **The development switch** shows private addresses and internal names, as it opens them to connections.
- **Limits:** 16 queries at once per run (more wait their turn), 10 s per query whatever the tries, answers up to a DNS message's 64 KB.

## HTTP servers

Node's `http.Server` (D-112, D-123), as `lib/_http_server.js` and `lib/_http_outgoing.js` behave. Nothing listens on the node: `listen(port)` registers the port, and the cluster's ingress routes to it by hostname. Clients reach the main server at the service's host and each other port at `<service>--<tenant>--<port>`; a service's `ports` can add a path prefix or keep a port off the network ([services](services.md#servers-and-their-ports)).
- **`http.createServer([options][, listener])` and `new http.Server`:**
  - **Options:** `IncomingMessage` and `ServerResponse` (your own classes) and the timeouts are kept.
  - **`listen`:** every form (`listen(port[, host][, backlog][, cb])`, `listen({ port, host, signal })`, `listen(cb)`). `listen(0)` picks a free virtual port. `server.address()` gives the port asked for, and the host if one was given (else `::`).
  - **Errors:** a port this run already listens on fails with Node's `EADDRINUSE`, unless both are on distinct specific addresses, as Fastify does for localhost's two. A path or a handle is refused: servers are reached by hostname.
  - **Events and methods:** `listening`, `request`, `close` and `error`; `close()` (after the requests in flight), `closeAllConnections()`, `getConnections()`, `setTimeout()` and `Symbol.asyncDispose`.
- **`req` is an `IncomingMessage`, as the client sent it:**
  - **Its fields:** `method`, `url`, `httpVersion`, `rawHeaders` (the client's own names, values and order), and `headers` and `headersDistinct` (Node's rules).
  - **Behind the proxy:** the ingress's `X-Forwarded-For`, `-Host`, `-Proto` (and `-Prefix` under a path) follow the client's headers, as for any app behind a proxy. A client's own `X-Forwarded-*` are dropped.
  - **`req.socket`:** the client's real address (`remoteAddress`, `remotePort`, `remoteFamily`) and `encrypted` when it came over TLS.
  - **The body** streams with backpressure.
- **`res` is a `ServerResponse`:**
  - **Headers:** the whole header API (`setHeader`, `appendHeader`, `getHeader(s)`, `getHeaderNames`, `getRawHeaderNames`, `hasHeader`, `removeHeader`, `flushHeaders`, `headersSent`).
  - **`writeHead`:** in all its forms, including Node's fast path and arrays.
  - **Status:** `statusCode`, and `statusMessage`, whose own reason phrase reaches the client.
  - **What Node adds:** `Date`, and `Content-Length` when the response ends in one go (else it's chunked). HEAD, 204 and 304 have no body.
  - **The rest:** `write` (with backpressure and `drain`), `end`, `addTrailers`, `finish` and `close`, and Node's errors (`ERR_HTTP_HEADERS_SENT`, `ERR_HTTP_INVALID_STATUS_CODE`, `ERR_INVALID_HTTP_TOKEN`).
  - **A client that leaves:** `res` and `req.socket` close without finishing.
- **`Expect: 100-continue`** is answered by the ingress at once, as Node's server does without a `checkContinue` listener.
- **`https.createServer`** is accepted; `key` and `cert` are set aside, with a note in the log, since the ingress serves TLS.
- **How long a service runs:** a listening server keeps its run going after the default export returns, and a service module that only calls `listen` needs no default export (a plain `app.js`).
  - **A stop emits `SIGTERM` on `process`**, so the app can close its servers; once they're closed the run ends.
  - **With no `SIGTERM` listener,** the run ends at once, as Node's default action ends the process.
- **Only services listen:** in a job, `listen` fails with `ERR_NOT_SUPPORTED`, since a job has no host.
- **Differs:**
  - **Request trailers** aren't seen (`req.trailers` is empty): the cluster's HTTP server doesn't expose them.
  - **No per-connection events:** `connection`, `checkContinue` and `clientError` aren't emitted, since connections end at the ingress.
  - **Keep-alive is the ingress's,** so `Connection` and `Keep-Alive` aren't sent from the app.
  - **The ingress adds `Server: automately`.**

## `async_hooks` and `perf_hooks`

- **`AsyncResource`,** as Node's: `runInAsyncScope`, `bind`, the static `bind`, `asyncId` and `triggerAsyncId`. That's all on-finished, raw-body and Fastify use it for.
- **`AsyncLocalStorage`:** `getStore`, `exit`, `disable`, `bind` and `snapshot` are there. `run()` and `enterWith()` throw `ERR_NOT_SUPPORTED`, because carrying a value across `await` needs a V8 hook the isolate doesn't have, and losing it silently would be worse (D-112).
- **`createHook`** returns a hook whose `enable()` throws, for the same reason. `executionAsyncId()` is 1.
- **`perf_hooks`:** the isolate's `performance` (whole milliseconds). A `PerformanceObserver` sees `mark` and `measure` entries a turn later. `monitorEventLoopDelay()` and `createHistogram()` return empty histograms.

## `worker_threads`, `module` and `v8`

Names only, so libraries that read them when they load work: pino and thread-stream (Fastify's logger), and Koa (D-123).
- **`worker_threads`:** the main thread's view (`isMainThread` true, `threadId` 0, `parentPort` and `workerData` null), and `get`/`setEnvironmentData`. `new Worker()` throws `ERR_NOT_SUPPORTED`; split work into jobs instead.
- **`module`:** `builtinModules`, `isBuiltin` and `createRequire`. The `require` it gives throws `ERR_NOT_SUPPORTED`, since code is bundled when it's published.
- **`v8`:** `startupSnapshot.isBuildingSnapshot()` is false. Heap statistics are zeros, as `process.memoryUsage()` is. `serialize`, `deserialize`, heap snapshots and `setFlagsFromString` throw `ERR_NOT_SUPPORTED`.

## `tty` and `child_process`

These have everything that doesn't start a process or need a terminal, so libraries that import them load (D-109, D-116).
- **`tty`:** `isatty()` is always false, as for Node with its output piped, so libraries such as debug don't add colours. `ReadStream` and `WriteStream` throw: a job has no terminal.
- **`child_process`:** its names (`exec`, `spawn`, `fork`, the `…Sync` forms, `ChildProcess`), so libraries that import it for an optional path load: nodemailer's sendmail transport, mongodb's encryption helper. Each function throws `ERR_NOT_SUPPORTED`, because scripts start no processes. Code that does needs the Node runtime.

## `diagnostics_channel`

Node's, complete, and pure: nothing leaves the isolate (D-114). got, mysql2, ioredis and Fastify import it.
- **Channels:** `channel(name)` (one per name), `hasSubscribers`, `subscribe`/`unsubscribe` (on the module and on a channel) and `publish`.
- **Tracing:** `tracingChannel(name or channels)`, with its `start`/`end`/`asyncStart`/`asyncEnd`/`error` channels and `traceSync`, `tracePromise` and `traceCallback`, publishing what Node publishes, in Node's order.
- **Subscribers that throw:** the publisher carries on, and the error fails the job, as an uncaught exception ends Node.
- **Stores:** `bindStore` and `runStores` run each store's `run`. `AsyncLocalStorage` can't carry a value across `await` in the isolate, though.

---

## Bundling

`automately packages publish` and `automately deploy` bundle a project with esbuild into ES modules (D-088, D-093):
- **Built-ins stay imports.** A `require('crypto')` in CommonJS code becomes an import of the isolate's module, not a bundled copy.
- **Package exports resolve as Node resolves them:** an `import` takes a package's `import` condition, and a `require()` takes its `require` condition (D-116; before, `require()` got the ES-module file too, so pg's `require('pg-pool')` got a namespace object instead of the class).
- **Globals are injected where used:** `Buffer` and `process` are imported, and `global` becomes `globalThis`.
- **`process.env.NODE_ENV`** is replaced with `"production"` at bundle time. Everything else in `process.env` is the job's own, at run time (D-096).
- **Refusals:**
  - A Node module the isolate doesn't have stops the publish when it's imported (`import`), naming the file that imported it.
  - **A CommonJS `require()` of one doesn't (D-109).** The `require()` throws when it runs, with `code: 'MODULE_NOT_FOUND'`, as for a module Node doesn't have, and the publish warns. Libraries often require optional modules inside `try` and fall back: fetch-blob's `stream/web` (it then uses its bundled polyfill), or node-domexception's `worker_threads` (only reached where `DOMException` is missing, which it isn't here).
  - A package that needs a native build is refused, pointing at `"runtime": "node"`. It's recognised by a `.node` binary, a build helper such as `node-gyp-build`, `bindings`, `prebuild-install` or `node-addon-api`, or an optional dependency that's native.
- **`import()` and `import.meta`:** V8 as embedded here (Javet 6) has neither, so the server rewrites them when a job is submitted (D-089).
  - **`import('name')`** works with a literal name, and with a computed name it's refused.
  - **`import.meta`** becomes the module's own `{ url }`.

## How compatibility is verified

- **Against real Node:** `RealNpmPackagesTest` (run with `AUTOMATELY_TEST_REAL_NPM`) installs real libraries from a pinned lockfile, bundles them, runs each in the isolate, and requires **the same output as under real Node**.
  - **Libraries:** lodash-es, date-fns, dayjs, zod, marked, js-yaml, yaml, ajv, semver, immer, rxjs, mustache, eventemitter3, validator, uuid, nanoid, papaparse, csv-parse, jsonwebtoken (HS256, RS256, ES256, PS256), axios, node-fetch and got.
  - **Drivers against real servers** (with `AUTOMATELY_TEST_REAL_SERVICES` too, since they need Docker for the servers):
    - **pg:** Postgres 18, plain and over TLS through its SSLRequest upgrade, trusted by `ca` and refused for the wrong host name
    - **mysql2:** MySQL 8.4
    - **ioredis:** Redis 7, pub/sub included
    - **MongoDB:** MongoDB 8.2
    - **nodemailer:** Mailpit, plain and STARTTLS, with an untrusted certificate refused
    - **mongodb+srv:** the same MongoDB found through SRV and TXT records (D-122)
  - **The Node release:** the test checks that it runs against Node v26.8.1, the version the isolate reports (D-115).
  - **HTTP libraries** run against a local test server whose answers are fixed, reached by both real Node and the isolate (the test node uses the development switch, since loopback is the node itself).
  - **Fixtures for the stand-ins themselves:**
    - `node:crypto`: KDFs, ciphers, keys and signatures, with Node-made keys
    - Web Crypto
    - `zlib`, including Node-made gzip, deflate and Brotli data
    - `assert`, `querystring` and `timers/promises`
    - `node:http`/`https`: methods, headers (repeated ones included), whole and streamed and fixed-length bodies, auth, redirects, a 1 MB body, an unheard response, timeouts, aborts, hang-ups, refused connections, and the validators, with events in Node's order
    - `node:http2`, against TLS and h2c servers, and got with `http2: true`
    - `node:dns`, against a local DNS server for every record type and failure, and the system's resolver for `lookup`. The mongodb driver also connects with `mongodb+srv://` through SRV and TXT records (D-122).
    - the event loop's guaranteed orderings (`setImmediate`, ticks, microtasks)
    - `node:net`/`tls`, against local echo, TLS, STARTTLS and half-close servers: event order, held writes, half-close, async iteration, idle timeouts, every error, trusted, refused, unverified and wrong-name TLS, custom identity checks, the upgrade, and `checkServerIdentity` itself
  - **Refused at publish, as expected:** bcrypt and sharp (native).
- **Against the standards:** the Java side is checked against its standards' test vectors:
  - **KDFs:** RFC 6070 (PBKDF2), 5869 (HKDF) and 7914 (scrypt)
  - **Ciphers:** the GCM spec and RFC 8439 (ChaCha20-Poly1305)
  - **Curves:** RFC 8032 (Ed25519) and 7748 (X25519)
- **Always-on tests:** `NodeStandInsTest` runs in every build, checking each module's behaviour in the isolate, the caps, and what stays unavailable. `NodeNetworkStandInsTest` runs `node:http` against a local server, including the socket's real connection details over plain HTTP and over TLS (unverified and trusted). It also covers `diagnostics_channel`, `events.on`, `dns` without the network, unconnected sockets, the pure parts of `net`, `tls`, `http2` and `tty`, and the `fs` streams. `StreamedRequestTest` checks the host side of `http`: framing, TLS (verification, `ca`, opting out), refused requests, and the shared limit and its release. `NodeHttp2Test` and `Http2ClientTest` check HTTP/2 in the isolate and on the host (streams, trailers, resets, ping, settings, refusals, the shared limits, cleanup). `NodeDnsTest`, `NodeDnsApiTest` and `DnsResolverTest` check `dns` in the isolate, what the filtering shows, and the resolver (every record type, compressed names, each failure, TCP after truncation) against a local DNS server. `NodeSocketsTest` and `SocketClientTest` check sockets in the isolate and on the host: events, half-close, errors, TLS, the upgrade, the limits, cleanup at the end of a run, and that a socket nobody reads pushes back on its peer.

## Known differences, in one list

**Behaviour that differs from Node:**
- **The Node version** is fixed at `v26.8.1`, the release the stand-ins are checked against (D-115), whatever V8 the isolate runs.
- **PKCS#1 v1.5 decryption** is refused in `privateDecrypt`; use OAEP (D-104).
- **Streams:** crypto classes aren't streams; `fetch` bodies and `files.stream` are async iterables, not Node streams. `fs` streams hold the whole file (32 MB).
- **HTTP:** `req.socket` is a stand-in reporting the host's connection (D-114); agents don't pool; `maxHeaderSize` is 64 KiB; `lookup`/`family`/`localAddress` are ignored; a custom `checkServerIdentity` is refused for `http`/`https` (`tls.connect` takes one); TLS cipher names are IANA's, not OpenSSL's.
- **Sockets:** `ref`/`unref` change nothing, a job doesn't wait for open sockets, TLS sessions aren't reused, and over TLS `end()` closes (D-116).
- **HTTP/2:** no server push, a `createConnection` socket replaced by the host's own connection (D-117), and a large body's `data` events and the windows they show. Both follow how the server cut its frames and how TCP split them into reads, which varies between runs of Node itself. Given the same frames in the same reads they match Node exactly (D-121).
- **Uncaught errors:** a throw in a `queueMicrotask` or `process.nextTick` callback, or in an `http` event listener, fails the job as it would end Node. So does a promise rejected with no handler by the end of the turn (D-110), unless `process.on('unhandledRejection')` has a listener; that one isn't a difference from Node.
- **Approximate output:** `util.inspect` output and `assert`'s messages for structures.
- **Compressed bytes** may differ from Node's (both are valid).
- **URLs:** no punycode, and IPv6 isn't normalised.
- **`TextDecoder`:** keeps a leading byte-order mark, and has no `stream`/`fatal` options.
- **Timers** (`setTimeout`, `setInterval`) return numbers.
- **`os` and `process`** describe the job: `/tmp`, one CPU, the heap limit, no host details.

**Missing for now:**
- **Globals:** `WebSocket`.
- **Crypto:** Brotli compression, AES-KW, classic Diffie-Hellman, and encrypted private keys.
- **Network:** WebSockets (network-reach.md §13).

Changes to any stand-in are recorded in the [changelog](../../CHANGELOG.md) and [decisions](../DECISIONS.md).
