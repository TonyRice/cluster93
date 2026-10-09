# Node compatibility in the isolate

Scripts run in a V8 isolate, not in Node. The isolate has no processes, sockets, native code or host files, and that's the sandbox. To run npm code anyway, it provides **stand-ins** for Node's modules (polyfills), backed either by pure JavaScript or by the JVM. This page is the reference for all of them: what each has, what it lacks, its limits, and where it behaves differently from Node.

For code that needs what the isolate can't give (child processes, raw sockets, native addons), there's the Node runtime ([D-084](../DECISIONS.md), designed, not built yet).

## The rules every stand-in follows

These come from the polyfill plan ([design](../design/isolate-polyfills.md), D-095):

1. **No new reach.** A stand-in is computation, or it goes through a host API the script already had: `fs` is the tenant's files, and `fetch` and `http` go through the egress gate (D-108), which refuses the cluster's own nodes and private addresses unless an operator allowed them. Nothing reads the host's files, environment, network interfaces or processes.
2. **Host-backed work is capped.** Anything done in Java (hashing, ciphers, compression, key derivation) has limits on size, count and memory, so a script can't exhaust its node through it. The limits are listed per module below.
3. **Per-job state dies with the job.** Open hashes, ciphers and compression streams belong to one execution and are dropped when it ends. Nothing is shared between jobs.
4. **Unavailable means unavailable, clearly.**
   - **At publish:** importing a module the isolate doesn't have stops the publish, with the reason. A CommonJS `require()` of one is the exception: it throws when it runs, as a missing module does in Node, so code that tries it inside `try` and falls back still works ([bundling](#bundling)).
   - **At run time:** a function that isn't implemented yet throws `ERR_FEATURE_UNAVAILABLE_ON_PLATFORM` or `ERR_NOT_SUPPORTED`, so it fails rather than half-working. Some modules exist only for their pure parts (`net.isIP`, `http2.constants`, `tty.isatty`): connecting through them throws, with a message saying so.
5. **The isolate never claims to be Node ([D-102](../DECISIONS.md)).** `process.version` is `''` and `process.versions.node` is undefined. Libraries that check them take their non-Node paths, which are the ones that work here.
   - **The cost:** a library that turns a feature on only for certain Node versions keeps it off. For example, jsonwebtoken disables PS256 (RSA-PSS itself works through `crypto.sign`).
6. **Checked against real Node.** Behaviour is compared with real Node's output, not just with documentation; see [how it's verified](#how-compatibility-is-verified).

## At a glance

Every module can be imported with or without `node:` (`import fs from 'fs'` or `'node:fs'`), and CommonJS `require()` of them works in bundled code ([bundling](#bundling)).

| Module | Status | Backed by | Decision |
|---|---|---|---|
| `fs`, `fs/promises` | Most of it, with `createReadStream`/`createWriteStream` | The tenant's files and the job's `/tmp` | D-079, D-109 |
| `path`, `path/posix` | Complete (POSIX) | JavaScript | D-090 |
| `events` | Complete, with `on` and `once` (signals included) | JavaScript | D-090, D-114 |
| `buffer` | Nearly complete | JavaScript | D-090 |
| `url` | Most of it | JavaScript | D-090 |
| `util` | The common parts | JavaScript | D-090 |
| `process` | A job-shaped subset; also a global; reports Node 26.8.1 | JavaScript | D-093, D-096, D-115 |
| `crypto` | Nearly complete | The JVM | D-097, D-101, D-104 |
| `stream`, `stream/promises` | Complete (Node's own code) | Vendored `readable-stream` 4.8.0 | D-100 |
| `string_decoder` | Complete (Node's own code) | Vendored `string_decoder` 1.3.0 | D-100 |
| `zlib` | Everything but Brotli compression | The JVM's zlib, a Java Brotli decoder | D-103 |
| `assert`, `assert/strict` | Complete | JavaScript | D-103 |
| `querystring` | Complete | JavaScript | D-103 |
| `timers`, `timers/promises` | Complete | The isolate's timers | D-103 |
| `os` | Describes the job, not the machine | JavaScript | D-103 |
| `http`, `https` | The client; `createServer` is phase B3 | The host's HTTP client, through the egress gate | D-108, D-109 |
| `net` | Address checks (`isIP`, …) and unconnected sockets; connecting is phase B5 | JavaScript | D-109, D-114 |
| `tls` | Its defaults and names, unconnected `TLSSocket`s; connecting is phase B5 | JavaScript | D-109, D-114 |
| `http2` | Its constants only; HTTP/2 isn't supported | JavaScript | D-109 |
| `tty` | `isatty()` is false: a job has no terminal | JavaScript | D-109 |
| `diagnostics_channel` | Complete: channels, `tracingChannel` | JavaScript | D-114 |
| `dns`, `dns/promises` | Its names and constants; lookups are phase B4 | JavaScript | D-114 |
| `automately:kv`, `:bus`, `:jobs`, `:files`, `:secrets`, `:fetch`, `:log` | The host APIs as modules | The host | D-091 |

**Refused:** `dgram`, `child_process`, `cluster`, `worker_threads`, `vm`, `v8`, `inspector`, `module`, `readline`, `repl`, `perf_hooks`, `async_hooks`, `domain`, `punycode`, `trace_events`, `wasi`, `sys`, `console` (as a module), `constants` and `stream/web`.
- **When:** importing one stops the publish ("Node's "dns" module isn't available in the isolate …"). A CommonJS `require()` of one throws when it runs instead, with a warning at publish.
- **What's coming** ([network reach](../design/network-reach.md)): outbound `net`/`tls` sockets (B5), an `http2` client, `dns` lookups (B4), `http.createServer` by hostname (B3), and WebSockets, all through the egress rules. `async_hooks` and `perf_hooks` come with B3, since the web frameworks need them.
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
| `fetch` | The host's egress-checked fetch, not the WHATWG one: it returns the host's response object, and there are no `Request`/`Response`/`Headers` classes ([host APIs](host-apis.md)). |
| `fs`, `kv`, `bus`, `jobs`, `files`, `secrets`, `log` | Automately's host APIs ([host APIs](host-apis.md)). |
| `URL`, `URLSearchParams` | The WHATWG URL for the common cases, with `URL.canParse`/`URL.parse` (D-106). Hostnames aren't converted to punycode, and IPv6 isn't normalised. |
| `AbortController`, `AbortSignal` | With `AbortSignal.abort`/`timeout`/`any`, `throwIfAborted`, and `onabort` running in the order it was set (D-106). `node:stream` and `timers/promises` use them. |
| `EventTarget`, `Event`, `CustomEvent` | Listener objects (`handleEvent`), `once`, `signal` (to remove), `stopImmediatePropagation` and `preventDefault`. There's no capture or bubbling: there's no tree. A listener's error is reported, not thrown to the dispatcher. |
| `DOMException` | With `name`, `code`, the legacy constants (`ABORT_ERR`, …) and `cause`. Web Crypto and aborts use it. |
| `structuredClone` | Plain objects and arrays, Date, RegExp, Map, Set, ArrayBuffer, typed arrays, DataView, errors (name, message, stack, cause), boxed primitives, BigInt and cycles. Class instances become plain objects. Functions, symbols, promises and weak collections throw a `DataCloneError`. `transfer` detaches ArrayBuffers where V8 can. |
| `atob`, `btoa` | With the standard's errors (`InvalidCharacterError`). |
| `queueMicrotask`, `setImmediate`, `clearImmediate` | `setImmediate` is a zero-delay timer, so it runs after microtasks, as in Node. A throw in a `queueMicrotask` callback fails the job, as an uncaught exception ends Node (D-109). |
| `performance` | `now()` (whole milliseconds since the job started, deliberately coarse), `timeOrigin`, `mark`/`measure`/`getEntries…`/`clear…` (at most 10,000 entries). |
| `WebAssembly`, `SharedArrayBuffer`, `Atomics`, `WeakRef`, `FinalizationRegistry`, `Intl` | V8's own. `Intl` has full ICU data (D-093). WebAssembly runs inside the same isolate and limits; there are no threads to share memory with. |

**Absent:**
- **Waiting for their own designs:** `Blob`, `File`, `FormData`, `Headers`, `Request`, `Response`, `ReadableStream`/`WritableStream`/`TransformStream`, `MessageChannel` and `WebSocket`. `fetch` returns the host's response today, and WebSocket is network reach (phase B). Libraries that bring their own (node-fetch brings `fetch-blob` and a Web Streams polyfill) work.
- **`Buffer`:** not a global. The bundler adds an import where bundled code uses it.
- **Never:** `require`, `module`, `exports`, `__dirname` and `__filename`. Scripts are ES modules, and the bundler converts CommonJS. `window`, `self`, `navigator` and `location` are absent too: this isn't a browser.

**What the globals cost:** nothing, net. They were added after a start-up benchmark (D-106). The prelude and built-in modules are compiled once per node and then come from V8's code cache, so a trivial job starts in about 3.5 ms (p50) with them, against 4.9 ms before. Numbers are in [benchmarks](../benchmarks.md#isolate-start-up).

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
- **Not there:** `captureRejections`. (`EventTarget` is a global; see [Globals](#globals).)

## `buffer`

`Buffer`, a `Uint8Array` subclass with Node's methods.
- **Encodings:** `utf8`, `hex`, `base64`, `base64url`, `latin1`/`binary`, `ascii` and `utf16le`/`ucs2`.
- **Methods:** `from`, `alloc`, `allocUnsafe`, `concat`, `compare`, `byteLength`, `isBuffer`, `isEncoding`, `toString`, `write`, `copy`, `fill`, `indexOf`/`includes`, `equals`, `toJSON`, and every `read…`/`write…` for 8, 16 and 32-bit integers, floats, doubles and 64-bit BigInts in both byte orders. Also `atob`/`btoa`, `kMaxLength` and `constants`.
- **`slice`** shares memory, as in Node.
- **Differs:** there's no pool. `poolSize` exists, but `allocUnsafe` returns zeroed memory, which is safer and otherwise the same.
- **Not there:** `Blob`, `File`, `transcode`, `swap16/32/64`, and `readIntLE/BE` of arbitrary widths.

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
- **Type checks:** `types.*` (`isDate`, `isRegExp`, `isPromise`, `isMap`, `isSet`, typed arrays, …) and the legacy `is…` functions.
- **Text:** `TextEncoder` and `TextDecoder`.
- **Differs:** `inspect` is a readable approximation of Node's output, not a byte-for-byte copy. Don't compare its strings.
- **Not there:** `parseArgs`, `styleText`, `MIMEType` and `getSystemErrorName`.

## `process`

A job-shaped `process`, also available as a global (D-096).
- **`env`:** exactly what the job declared, plus `NODE_ENV=production`. It's never the node's environment.
  - Secrets are referenced by name (`{"secret": "stripe-live"}`), read when the run starts, and only for tokens with `secrets:use` (D-068). Their values are masked in the job's log.
  - The full guide is in [host APIs](host-apis.md).
- **There:** `nextTick` (a microtask; a throw in its callback fails the job, as an uncaught exception ends Node), `hrtime` and `hrtime.bigint`, `uptime`, `cwd()` (`/`), `platform` (`linux`), `arch` (`x64`), `argv` (`[]`), `pid` (1), `emitWarning`, and `stdout.write`/`stderr.write` (to the log). Also `on`/`emit` and the other listener methods. The only events emitted are `unhandledRejection` and `rejectionHandled`, as in Node (D-110): without an `unhandledRejection` listener, a promise rejected with no handler by the end of a turn fails the job.
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
  - **Platform streams:** `fs.createReadStream`/`createWriteStream`, `http`'s requests and responses, and `zlib`'s streams are Node streams (D-109). `fetch` bodies and `files.stream` are async iterables; wrap them with `Readable.from(...)`. There are no sockets yet.
  - **Web Streams:** there are no global Web Streams (`ReadableStream`), so `Readable.toWeb`/`fromWeb` have nothing to convert.
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

## `timers` and `timers/promises`

- **`timers`:** `setTimeout`, `setInterval`, `setImmediate` and their `clear…`.
- **`timers/promises`:** `setTimeout`, `setImmediate`, `setInterval` (an async iterator) and `scheduler.wait`/`yield`. All of them accept a `signal` (D-103).
- **Differs:**
  - **`setImmediate`** is a zero-delay timer; there's no separate check phase.
  - **Timers return numbers,** not `Timeout` objects, so `ref`/`unref`/`refresh` don't exist.
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
  - **Options:** `method`, `headers` (an object, or Node's raw `[name, value, …]` list), `auth` and a URL's user and password, `timeout` and `setTimeout` (they emit `timeout` without aborting, as in Node), `signal`, `setHost` and `agent`.
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
- **Not there yet:** `createServer`, `Server` and `ServerResponse` (phase B3, on `ctx.serve`). `https.createServer` never: a service serves HTTPS through the cluster's ingress.
- **Limits:**
  - **Concurrency:** 16 open requests per execution, shared with `fetch`. A request counts until its response has been read or it's aborted.
  - **Timeouts:** 30 s to connect, and 5 min with no data either way.
  - **Response headers:** 64 KiB.
  - **Redirects:** none are followed, which is Node's behaviour too. Libraries that follow them (axios, node-fetch) make each hop a new request, and each goes through the gate.
  - **Cleanup:** an unfinished request is aborted when the job ends.
- **Proven with:** axios (its Node adapter: JSON, forms, gzip, redirects, timeouts, streams and binary bodies), node-fetch 3 and got 14 (D-114: JSON and form bodies, search params, auth, hooks, redirects, gzip, `HTTPError`, retries, `throwHttpErrors`, phase timeouts, refused connections, streams with progress, pagination, and which timing phases exist), all giving real Node's results.
- **got's `http2: true`** needs an HTTP/2 client, which comes in phase B's `http2` step.

## `net`, `tls`, `http2`, `tty` and `dns`

These have everything that doesn't open a connection, so libraries that import them load (D-109, D-114). **Opening a connection isn't a restriction, it isn't built yet:** sockets come in phase B5 and lookups in B4, through the egress rules, and until then they throw `ERR_NOT_SUPPORTED` with a message that says so.
- **`net`:**
  - **Addresses:** `isIP`, `isIPv4` and `isIPv6`, with Node's own patterns, and `get`/`setDefaultAutoSelectFamily`.
  - **Sockets can be made:** `new net.Socket()` is an unconnected `Duplex` with a socket's properties, as in Node, so libraries can build one and `instanceof` works. `Stream` is the same class.
  - **Not yet:** `connect()` on it, `net.connect`/`createConnection`, and writing to an unconnected socket throw `ERR_NOT_SUPPORTED`. **Outbound sockets come in phase B5,** through the egress rules: SMTP's ports are refused by a default cluster rule, and at most 120 new connections a minute per tenant per node.
  - **Never on the cluster:** `createServer` and `Server`. TCP servers run only on untrusted nodes, such as a link (D-112).
- **`tls`:**
  - **Values and functions:** `DEFAULT_MIN_VERSION`/`MAX_VERSION`/`ECDH_CURVE`/`CIPHERS`, `getCiphers()`, and `checkServerIdentity` (a marker `https` accepts).
  - **`rootCertificates` is empty:** the host verifies against the JVM's trust store, which the isolate can't list.
  - **`new tls.TLSSocket(stream)`** makes an unconnected TLS socket. Over a stream that isn't a socket, it has Node's internal `_handle._parentWrap`, which http2-wrapper (got's) reaches for when it loads.
  - **Not yet:** `connect` and `createSecureContext` throw until phase B5.
  - **Never on the cluster:** `createServer`.
- **`http2`:** `constants` (all 240 of Node 26's) and `sensitiveHeaders`, so axios, which imports it for an optional HTTP/2 path, loads. `connect`, `createServer` and `createSecureServer` throw: HTTP/2 isn't supported.
- **`tty`:** `isatty()` is always false, as for Node with its output piped, so libraries such as debug don't add colours. `ReadStream` and `WriteStream` throw: a job has no terminal.
- **`dns` and `dns/promises`:**
  - **There:** every name and constant Node has (`ADDRCONFIG`, `V4MAPPED`, `ALL`, the error codes), `Resolver` and `promises.Resolver`, and `get`/`setDefaultResultOrder`. So libraries that import it when they load, such as got's cacheable-lookup, load.
  - **Not yet:** every lookup (`lookup`, `resolve*`, `reverse`, `lookupService`) fails with `ERR_NOT_SUPPORTED`, through its callback or promise. `getServers()` is empty, and `setServers()` throws.
  - **Coming in phase B4:** lookups answered through the egress rules (network-reach.md §6).
  - **Never needed by HTTP:** `fetch`, `http` and `https` don't use it; the host resolves names through the gate.

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
  - **The Node release:** the test checks that it runs against Node v26.8.1, the version the isolate reports (D-115).
  - **HTTP libraries** run against a local test server whose answers are fixed, reached by both real Node and the isolate (the test node uses the development switch, since loopback is the node itself).
  - **Fixtures for the stand-ins themselves:**
    - `node:crypto`: KDFs, ciphers, keys and signatures, with Node-made keys
    - Web Crypto
    - `zlib`, including Node-made gzip, deflate and Brotli data
    - `assert`, `querystring` and `timers/promises`
    - `node:http`/`https`: methods, headers (repeated ones included), whole and streamed and fixed-length bodies, auth, redirects, a 1 MB body, an unheard response, timeouts, aborts, hang-ups, refused connections, and the validators, with events in Node's order
  - **Refused at publish, as expected:** bcrypt and sharp (native).
- **Against the standards:** the Java side is checked against its standards' test vectors:
  - **KDFs:** RFC 6070 (PBKDF2), 5869 (HKDF) and 7914 (scrypt)
  - **Ciphers:** the GCM spec and RFC 8439 (ChaCha20-Poly1305)
  - **Curves:** RFC 8032 (Ed25519) and 7748 (X25519)
- **Always-on tests:** `NodeStandInsTest` runs in every build, checking each module's behaviour in the isolate, the caps, and what stays unavailable. `NodeNetworkStandInsTest` runs `node:http` against a local server, including the socket's real connection details over plain HTTP and over TLS (unverified and trusted). It also covers `diagnostics_channel`, `events.on`, `dns`'s names, unconnected sockets, the pure parts of `net`, `tls`, `http2` and `tty`, and the `fs` streams. `StreamedRequestTest` checks the host side of `http`: framing, TLS (verification, `ca`, opting out), refused requests, and the shared limit and its release.

## Known differences, in one list

**Behaviour that differs from Node:**
- **The Node version** is fixed at `v26.8.1`, the release the stand-ins are checked against (D-115), whatever V8 the isolate runs.
- **PKCS#1 v1.5 decryption** is refused in `privateDecrypt`; use OAEP (D-104).
- **Streams:** crypto classes aren't streams; `fetch` bodies and `files.stream` are async iterables, not Node streams. `fs` streams hold the whole file (32 MB).
- **HTTP:** `req.socket` is a stand-in reporting the host's connection (D-114); agents don't pool; `maxHeaderSize` is 64 KiB; `lookup`/`family`/`localAddress` are ignored; a custom `checkServerIdentity` is refused; TLS cipher names are IANA's, not OpenSSL's.
- **Uncaught errors:** a throw in a `queueMicrotask` or `process.nextTick` callback, or in an `http` event listener, fails the job as it would end Node. So does a promise rejected with no handler by the end of the turn (D-110), unless `process.on('unhandledRejection')` has a listener; that one isn't a difference from Node.
- **Approximate output:** `util.inspect` output and `assert`'s messages for structures.
- **Compressed bytes** may differ from Node's (both are valid).
- **URLs:** no punycode, and IPv6 isn't normalised.
- **`TextDecoder`:** keeps a leading byte-order mark, and has no `stream`/`fatal` options.
- **`setImmediate`** is a zero-delay timer; timers are numbers.
- **`os` and `process`** describe the job: `/tmp`, one CPU, the heap limit, no host details.

**Missing for now:**
- **Globals:** `Blob`, `FormData`, `Headers`/`Request`/`Response`, Web Streams, `MessageChannel` and `WebSocket`.
- **Crypto:** Brotli compression, AES-KW, classic Diffie-Hellman, and encrypted private keys.
- **Network:** `net`/`tls` connections (B5), the `http2` client, `dns` lookups (B4), `http.createServer` (B3), and WebSockets, in that order (network-reach.md §9).

Changes to any stand-in are recorded in the [changelog](../../CHANGELOG.md) and [decisions](../DECISIONS.md).
