# Node compatibility in the isolate

Scripts run in a V8 isolate, not in Node. The isolate has no processes, sockets, native code or host files, and that's the sandbox. To run npm code anyway, it provides **stand-ins** for Node's modules (polyfills), backed either by pure JavaScript or by the JVM. This page is the reference for all of them: what each has, what it lacks, its limits, and where it behaves differently from Node.

For code that needs what the isolate can't give (child processes, raw sockets, native addons), there's the Node runtime ([D-084](../DECISIONS.md), designed, not built yet).

## The rules every stand-in follows

These come from the polyfill plan ([design](../design/isolate-polyfills.md), D-095):

1. **No new reach.** A stand-in is computation, or it goes through a host API the script already had: `fs` is the tenant's files, and `fetch` is egress-checked. Nothing reads the host's files, environment, network interfaces or processes.
2. **Host-backed work is capped.** Anything done in Java (hashing, ciphers, compression, key derivation) has limits on size, count and memory, so a script can't exhaust its node through it. The limits are listed per module below.
3. **Per-job state dies with the job.** Open hashes, ciphers and compression streams belong to one execution and are dropped when it ends. Nothing is shared between jobs.
4. **Unavailable means unavailable, clearly.**
   - **At publish:** importing a module the isolate doesn't have stops the publish, with the reason.
   - **At run time:** a function that isn't implemented yet throws `ERR_FEATURE_UNAVAILABLE_ON_PLATFORM`, so it fails rather than half-working.
5. **The isolate never claims to be Node ([D-102](../DECISIONS.md)).** `process.version` is `''` and `process.versions.node` is undefined. Libraries that check them take their non-Node paths, which are the ones that work here.
   - **The cost:** a library that turns a feature on only for certain Node versions keeps it off. For example, jsonwebtoken disables PS256 (RSA-PSS itself works through `crypto.sign`).
6. **Checked against real Node.** Behaviour is compared with real Node's output, not just with documentation; see [how it's verified](#how-compatibility-is-verified).

## At a glance

Every module can be imported with or without `node:` (`import fs from 'fs'` or `'node:fs'`), and CommonJS `require()` of them works in bundled code ([bundling](#bundling)).

| Module | Status | Backed by | Decision |
|---|---|---|---|
| `fs`, `fs/promises` | Most of it | The tenant's files and the job's `/tmp` | D-079 |
| `path`, `path/posix` | Complete (POSIX) | JavaScript | D-090 |
| `events` | Complete | JavaScript | D-090 |
| `buffer` | Nearly complete | JavaScript | D-090 |
| `url` | Most of it | JavaScript | D-090 |
| `util` | The common parts | JavaScript | D-090 |
| `process` | A job-shaped subset; also a global | JavaScript | D-093, D-096 |
| `crypto` | Nearly complete | The JVM | D-097, D-101, D-104 |
| `stream`, `stream/promises` | Complete (Node's own code) | Vendored `readable-stream` 4.8.0 | D-100 |
| `string_decoder` | Complete (Node's own code) | Vendored `string_decoder` 1.3.0 | D-100 |
| `zlib` | Everything but Brotli compression | The JVM's zlib, a Java Brotli decoder | D-103 |
| `assert`, `assert/strict` | Complete | JavaScript | D-103 |
| `querystring` | Complete | JavaScript | D-103 |
| `timers`, `timers/promises` | Complete | The isolate's timers | D-103 |
| `os` | Describes the job, not the machine | JavaScript | D-103 |
| `automately:kv`, `:bus`, `:jobs`, `:files`, `:secrets`, `:fetch`, `:log` | The host APIs as modules | The host | D-091 |

**Refused:** `http`, `https`, `http2`, `net`, `tls`, `dns`, `dgram`, `child_process`, `cluster`, `worker_threads`, `vm`, `v8`, `inspector`, `module`, `readline`, `repl`, `tty`, `perf_hooks`, `async_hooks`, `diagnostics_channel`, `domain`, `punycode`, `trace_events`, `wasi`, `sys`, `console` (as a module) and `constants`.
- **When:** importing one stops the publish ("Node's "net" module isn't available in the isolate …").
- **What's coming:** `http`, `https`, `net` and `dns` are phase B of the plan. They will go through the egress check and explicit per-tenant allow-lists, and never reach the cluster's own nodes (D-095).
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
| `queueMicrotask`, `setImmediate`, `clearImmediate` | `setImmediate` is a zero-delay timer, so it runs after microtasks, as in Node. |
| `performance` | `now()` (whole milliseconds since the job started, deliberately coarse), `timeOrigin`, `mark`/`measure`/`getEntries…`/`clear…` (at most 10,000 entries). |
| `WebAssembly`, `SharedArrayBuffer`, `Atomics`, `WeakRef`, `FinalizationRegistry`, `Intl` | V8's own. `Intl` has full ICU data (D-093). WebAssembly runs inside the same isolate and limits; there are no threads to share memory with. |

**Absent:**
- **Waiting for their own designs:** `Blob`, `File`, `FormData`, `Headers`, `Request`, `Response`, `ReadableStream`/`WritableStream`/`TransformStream`, `MessageChannel` and `WebSocket`. `fetch` returns the host's response today, and WebSocket is network reach (phase B).
- **`Buffer`:** not a global. The bundler adds an import where bundled code uses it.
- **Never:** `require`, `module`, `exports`, `__dirname` and `__filename`. Scripts are ES modules, and the bundler converts CommonJS. `window`, `self`, `navigator` and `location` are absent too: this isn't a browser.

**What the globals cost:** nothing, net. They were added after a start-up benchmark (D-106). The prelude and built-in modules are compiled once per node and then come from V8's code cache, so a trivial job starts in about 3.5 ms (p50) with them, against 4.9 ms before. Numbers are in [benchmarks](../benchmarks.md#isolate-start-up).

---

## `fs`

Your files as one tree from `/`, with the job's own `/tmp`. The full guide is in [host APIs](host-apis.md#fs-the-file-system).

- **There:**
  - **Calls:** `readFile`, `writeFile`, `appendFile`, `mkdir`, `readdir` (with `withFileTypes`), `stat`/`lstat`, `access`, `exists` (sync only), `rename`, `copyFile`, `rm`, `rmdir` and `unlink`. Each has promise (`fs.promises`), callback and `…Sync` forms.
  - **File handles:** `fs.promises.open`, `openSync`/`readSync`/`writeSync`/`closeSync`.
  - **Locks:** advisory `fs.lock`.
- **Not there:**
  - **Streams:** `createReadStream`/`createWriteStream`; use `files.stream` and `files.write`.
  - **The rest:** `watch`, symlinks, `chmod`/`chown`, `mkdtemp`, `realpath` and file descriptors as numbers beyond the handle calls.
- **Differs from Node:**
  - **Links:** `lstat` is `stat`, since there are no links.
  - **Writes:** each write publishes a new version of the file at once. Readers never see a half-written file.
  - **Errors:** Node's codes (`ENOENT`, `EEXIST`, `EROFS`, …), with the cluster's own in `e.reason`.
- **Limits:**
  - `readFile` reads at most 32 MB, and `appendFile` and handles work on files up to 64 MB and 32 MB.
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
- **Not there:** `EventTarget`, `on(emitter, name)` as an async iterator, and `captureRejections`.

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
- **There:** `nextTick` (a microtask), `hrtime` and `hrtime.bigint`, `uptime`, `cwd()` (`/`), `platform` (`linux`), `arch` (`x64`), `argv` (`[]`), `pid` (1), `emitWarning`, and `stdout.write`/`stderr.write` (to the log). Also `on`/`emit` and the other listener methods; nothing emits events, though.
- **Differs:**
  - `version` is `''` and `versions` is `{}` (D-102). `memoryUsage()` is zeros.
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
- **Key agreement:** `createECDH` (every point encoding, plus `ECDH.convertKey`), and `diffieHellman()` for ECDH and X25519.

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
- **Rules:** Web Crypto's own: usages, extractability, and DOMException-style errors (`InvalidAccessError`, `NotSupportedError`, `DataError`, `OperationError`, `SyntaxError`). The errors are `Error`s with that `name`; there's no `DOMException` class.
- **Not there:** AES-KW. Also an AES-CTR counter that would wrap within a `length` under 128 bits: it's refused, because the JVM counts with all 128 bits.

## `stream`, `stream/promises` and `string_decoder`

Node's own code: `readable-stream` 4.8.0 and `string_decoder` 1.3.0 (MIT), bundled onto the isolate's `buffer`, `events` and `process` (D-100).
- **What's there:** everything Node's `stream` exports.
  - **Classes:** `Readable`, `Writable`, `Duplex`, `Transform` and `PassThrough`.
  - **Functions:** `pipeline`, `finished`, `compose`, `addAbortSignal`, `destroy`, `duplexPair`, `isReadable`/`isErrored`/`isDisturbed`, `get`/`setDefaultHighWaterMark`, `Readable.from` and async iteration.
  - **`stream/promises`:** `pipeline` and `finished`.
- **Differs:**
  - **No platform streams:** nothing in the isolate produces Node streams. `fs.createReadStream` and sockets don't exist, and `fetch` bodies and `files.stream` are async iterables. Wrap those with `Readable.from(...)`.
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
| `release()`, `version()` | `''` (D-102) |
| `loadavg()` | `[0, 0, 0]` |
| `uptime()` | seconds since the module loaded |

`EOL`, `devNull` and `constants` (signals and priorities) are there too; `setPriority` throws.

---

## Bundling

`automately packages publish` and `automately deploy` bundle a project with esbuild into ES modules (D-088, D-093):
- **Built-ins stay imports.** A `require('crypto')` in CommonJS code becomes an import of the isolate's module, not a bundled copy.
- **Globals are injected where used:** `Buffer` and `process` are imported, and `global` becomes `globalThis`.
- **`process.env.NODE_ENV`** is replaced with `"production"` at bundle time. Everything else in `process.env` is the job's own, at run time (D-096).
- **Refusals:**
  - A Node module the isolate doesn't have stops the publish, naming the file that imported it.
  - A package that needs a native build is refused, pointing at `"runtime": "node"`. It's recognised by a `.node` binary, a build helper such as `node-gyp-build`, `bindings`, `prebuild-install` or `node-addon-api`, or an optional dependency that's native.
- **`import()` and `import.meta`:** V8 as embedded here (Javet 6) has neither, so the server rewrites them when a job is submitted (D-089).
  - **`import('name')`** works with a literal name, and with a computed name it's refused.
  - **`import.meta`** becomes the module's own `{ url }`.

## How compatibility is verified

- **Against real Node:** `RealNpmPackagesTest` (run with `AUTOMATELY_TEST_REAL_NPM`) installs real libraries from a pinned lockfile, bundles them, runs each in the isolate, and requires **the same output as under real Node**.
  - **Libraries:** lodash-es, date-fns, dayjs, zod, marked, js-yaml, yaml, ajv, semver, immer, rxjs, mustache, eventemitter3, validator, uuid, nanoid, papaparse, csv-parse and jsonwebtoken (HS256, RS256, ES256).
  - **Fixtures for the stand-ins themselves:**
    - `node:crypto`: KDFs, ciphers, keys and signatures, with Node-made keys
    - Web Crypto
    - `zlib`, including Node-made gzip, deflate and Brotli data
    - `assert`, `querystring` and `timers/promises`
  - **Refused at publish, as expected:** bcrypt and sharp (native).
- **Against the standards:** the Java side is checked against its standards' test vectors:
  - **KDFs:** RFC 6070 (PBKDF2), 5869 (HKDF) and 7914 (scrypt)
  - **Ciphers:** the GCM spec and RFC 8439 (ChaCha20-Poly1305)
  - **Curves:** RFC 8032 (Ed25519) and 7748 (X25519)
- **Always-on tests:** `NodeStandInsTest` runs in every build, checking each module's behaviour in the isolate, the caps, and what stays unavailable.

## Known differences, in one list

**Behaviour that differs from Node:**
- **No Node version:** `process.version` is empty (D-102), so version-gated features in libraries stay off (jsonwebtoken's PS256).
- **PKCS#1 v1.5 decryption** is refused in `privateDecrypt`; use OAEP (D-104).
- **No platform streams:** crypto classes aren't streams, and no API returns a Node stream; `fs.createReadStream` is absent.
- **Approximate output:** `util.inspect` output and `assert`'s messages for structures.
- **Compressed bytes** may differ from Node's (both are valid).
- **URLs:** no punycode, and IPv6 isn't normalised.
- **`TextDecoder`:** keeps a leading byte-order mark, and has no `stream`/`fatal` options.
- **`setImmediate`** is a zero-delay timer; timers are numbers.
- **`os` and `process`** describe the job: `/tmp`, one CPU, the heap limit, no host details.

**Missing for now:**
- **Globals:** `Blob`, `FormData`, `Headers`/`Request`/`Response`, Web Streams, `MessageChannel` and `WebSocket`.
- **Crypto:** Brotli compression, AES-KW, classic Diffie-Hellman, and encrypted private keys.

Changes to any stand-in are recorded in the [changelog](../../CHANGELOG.md) and [decisions](../DECISIONS.md).
