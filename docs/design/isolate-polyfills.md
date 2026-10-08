# More of Node in the isolate: a plan, reviewed for security first

**Status:** accepted (Tony, 2026-10-08, D-095), with the decisions in §4. It builds on earlier research into Node compatibility in isolates and on what packages measured (D-093).

**Why now:** `RealNpmPackagesTest` measures compatibility against real libraries.
- **Already working:** 14 popular libraries give Node's exact output in the isolate.
- **What's missing:** `crypto` (uuid, jsonwebtoken, nanoid's Web Crypto), `stream` (papaparse in Node mode, csv-parse, most "Node" parsers), and the cluster-backed modules (`http`, `net`, `dns`). These are what stand between "pure JavaScript" and "ordinary npm code".

## 1. The rule: what may be added to the isolate
The sandbox holds because a script can only do what a host API lets it, scoped to its tenant and job. A polyfill must not change that.
1. **Pure JavaScript** stand-ins can always be added. They run in the isolate, under its heap, time and host-API limits, and can't reach anything a script couldn't reach already.
2. **Host-backed computation** (hashing, ciphers, compression, secure randomness) may be added if it:
   - gives the script **no new reach**: no network, files, processes, environment or other tenants' data;
   - is **bounded**: every call has a size or cost limit, and expensive calls run off the isolate's thread, so the job's timeout and the node's capacity still apply;
   - is **stateless or per-job**: nothing outlives the job or is shared between jobs, so there's no cross-tenant channel (D-089's cache rule);
   - comes from the JVM's own, reviewed implementations. We never write our own crypto.
3. **Cluster-backed capabilities** (`http`, `net`, `dns`) are new reach. Each maps onto an existing tenant-scoped, egress-checked API (`fetch`, `ctx.serve`) or gets a new one, and has its **own review** (phase B).
4. **Never in the isolate:** processes, inbound raw sockets, native addons, the host's files or environment (→ the Node runtime, D-084).
5. **Vendored code** (for example, Node's streams from `readable-stream`) is pinned, licence-checked (MIT/BSD/ISC), built at build time from a checksummed source like ICU's data (D-093), and covered by the same tests. (For `stream`, D-100 commits the generated output instead of building it during the Maven build; the packages are still hash-checked by `npm ci`.)

**Threats each part is checked against:**
- **Escaping:** reaching host objects or Java beyond the bound `@V8Function` methods (D-060).
- **Exhaustion:** CPU, memory, or holding the isolate thread (PBKDF2 with 10⁹ iterations, `scrypt` sized to the heap, a zip bomb).
- **Leaks between tenants:** shared caches or keys, or state that outlives a job.
- **Timing side channels:** high-resolution timers. `performance.now` stays coarse, and comparisons are constant-time.
- **Supply chain:** vendored code.

## 2. Phase A: pure computation, no new reach (this proposal)
| Module | How | Notes |
|---|---|---|
| **`crypto`: randomness**: `randomBytes`, `randomInt`, `randomUUID`, `getRandomValues` | Host: `SecureRandom` | At most 64 KiB per call (Web Crypto's own limit) |
| **`crypto`: hashing**: `createHash` (md5, sha1, sha2, sha3), `createHmac`, `hash()` | Host: `MessageDigest`, `Mac` | The input is the script's own bytes, already within its heap. Incremental `update()` calls keep the digest state in the host, per job, dropped at the end. |
| **`crypto`: KDFs**: `pbkdf2`, `hkdf`, `scrypt` | Host, **off the isolate thread** | Caps (for example 10⁶ PBKDF2 iterations, `scrypt` memory ≤ 64 MB). The `*Sync` forms keep the same caps and block like `fs`'s sync calls (D-079). |
| **`crypto`: ciphers**: AES-GCM/CBC/CTR, ChaCha20-Poly1305 (`createCipheriv`, `createDecipheriv`) | Host: `Cipher` | Streaming `update()`/`final()`; per-job state |
| **`crypto`: keys and signatures**: `createSign`/`createVerify`, `sign`/`verify` (RSA, ECDSA, Ed25519), `generateKeyPair`, `createPublicKey`, `createPrivateKey`, `KeyObject` | Host: `Signature`, `KeyFactory` | `generateKeyPair` runs off the thread; RSA up to 4096 bits. Key material stays in the job (no host key store). |
| **`crypto`: `timingSafeEqual`** | Host: `MessageDigest.isEqual` | constant-time |
| **`crypto` global (Web Crypto)**: `crypto.getRandomValues`, `crypto.randomUUID`, `crypto.subtle` (digest, HMAC, AES, ECDSA, Ed25519, import/export keys) | The same host methods | Workers and Deno have it too. **nanoid and uuid look for it.** |
| **`stream`**, `stream/promises`, `string_decoder` | Node's own streams, vendored from `readable-stream` 4 (MIT), on our `buffer`, `events` and `process` | Pure JavaScript, with no reach at all |
| **`zlib`**: gzip, deflate, brotli decode | Host: `Inflater`/`Deflater`, a brotli decoder | **Output-size cap** against zip bombs; streaming through `stream` |
| **`assert`**, `assert/strict` | Pure JavaScript | |
| **`querystring`** | Pure JavaScript | |
| **`timers/promises`** | On the existing timers | |
| **`os`** | Mapped to the job: `tmpdir()` = `/tmp`, `cpus()` = the job's share, `totalmem` = the heap limit | Never the host's values |
| **Globals**: `URL`, `URLSearchParams`, `atob`/`btoa`, `structuredClone`, `AbortController`, `EventTarget`, `Event`, `performance.now` (coarse), `queueMicrotask` | Our stand-ins; some are plain V8 | Parked in NOTES. Web-standard, no reach. To check: whether a few KB more of prelude per isolate is acceptable (benchmark the cold start, as D-024 did). |

**Not in phase A:**
- `crypto.setEngine`, `secureHeap`, FIPS switches and certificate stores: they're about the host.
- `X509Certificate` parsing: later, if asked for.
- `DiffieHellman` with custom groups (fine in principle, but rarely used).

## 3. Phase B: the cluster-backed modules (a review of their own, after A)
From the earlier research:
- **`http`/`https` client** over `fetch`, with egress and limits unchanged
- **`http.createServer().listen()`** over `ctx.serve`, so Express, Fastify and Koa run in isolates
- **outbound `net`/`tls`** as a new egress-checked host API (`pg`, `mysql2`, `ioredis`)
- **`dns`** through the host, egress-checked
- **`worker_threads`** as child jobs

Each is new reach, so each gets its threat model: SSRF, egress rules, connection and byte limits, and how sockets are accounted per tenant.

## 4. Decisions (Tony, 2026-10-08)
1. **The global `crypto` (Web Crypto): yes.** It's a web standard, has no reach, and is what libraries probe for.
2. **`process.env` is declared per job, nothing implicit** (Tony: "an ENV flag for the job, with a secrets get"):
   ```json
   "env": { "API_URL": "https://api.example.com", "STRIPE_KEY": { "secret": "stripe-live" } }
   ```
   - **Contents:** exactly what's declared, plus `NODE_ENV`. Never the node's environment.
   - **`{secret: name}`** follows `secrets.get`'s rule (D-068): only if the submitting token had `secrets:use`, read on the node when the run starts. The job record keeps the **name**, never the value, so it doesn't leak through the API, job lists or MCP.
   - **Masked in logs:** a secret's value appearing in a log line is replaced by `[secret:<name>]`, since libraries print their configuration.
   - **Where it's set:** `POST /v1/jobs`; `jobs.run` (a child inherits it only if it asks); services (`env` beside `config`, and `service.env` in `automately.json`); the CLI (`run --env KEY=value --env-secret KEY=name`).
3. **Caps: as proposed.** PBKDF2 ≤ 10⁶ iterations, `scrypt` ≤ 64 MB, RSA ≤ 4096 bits, random ≤ 64 KiB per call, zlib output ≤ 32 MB.
4. **`stream`: vendor `readable-stream` 4**, Node's own streams packaged for outside Node (about 9,000 lines). It's built from hash-checked npm packages by `scripts/vendor-stream` and the output is committed (D-100), needs `buffer`, `events`, `process.nextTick`, `string_decoder` and `AbortController`, and has no reach at all. Writing our own would take weeks to match its edge cases, which are where libraries break.
5. **Network access never reaches private things unless explicitly allowed** (Tony). Phase B's `http`, `net` and `dns` go through `fetch`'s egress check (D-026: private and reserved ranges refused, connect to the checked address, no redirects followed), plus two additions:
   - **The cluster's own nodes are always refused,** by advertised address and internal port, even on public addresses.
   - **Explicit allow-lists replace the all-or-nothing switch:** an operator grants a tenant specific hosts or CIDRs and ports (for example `10.0.5.20:5432`), audited. It never covers the cluster's own nodes. `AUTOMATELY_EGRESS_ALLOW_PRIVATE` stays for local development only. A tenant's own private network is reached through a link.

## 5. Building it, in order
0. ~~**`env` on jobs and services**~~ ✅ with secrets by name and masking in logs, and `process` as a global (D-096).
1. ~~**Randomness and the `crypto` global:**~~ ✅ with `node:crypto`'s hashing, HMAC and `timingSafeEqual` from step 2 (D-097). the smallest host API, and it unblocks uuid and nanoid.
2. ~~**`node:crypto`:**~~ ✅ KDFs, ciphers, keys and signatures (D-101); RSA encryption, ECDH and `subtle` beyond digest still to come. hashing, HMAC, `timingSafeEqual`, then KDFs, then ciphers, then keys and signatures. That unblocks jsonwebtoken (HS256 first, then RS256 and ES256).
3. ~~**`stream`, `string_decoder`, `stream/promises`:**~~ ✅ vendored `readable-stream` 4.8.0 (D-100). papaparse, csv-parse and jsonwebtoken (HS256) now run. unblocks papaparse in Node mode and csv-parse.
4. **`zlib`, `assert`, `querystring`, `timers/promises`, `os`.**
5. **Globals** (`URL` and the rest), after a cold-start benchmark.

**Each step comes with:**
- **real libraries added to `RealNpmPackagesTest`,** matched against Node's output: uuid, nanoid, jsonwebtoken (HS256, RS256, ES256), bcryptjs, crypto-js, papaparse, csv-parse, pako, fflate, through2;
- **hostile scripts** that try to exhaust each cap (a huge PBKDF2, a huge `randomBytes`, a zip bomb, an endless `update()` loop) and must be stopped while the node stays healthy, as `HostileScripts` does today;
- **a check that no host object leaks:** `Object.getPrototypeOf` chains end in plain JavaScript, as D-060 requires.

## 6. Where it plugs in
- **Host:** a `CryptoApi` (and `ZlibApi`) in core next to the others (D-060), bound by a `CryptoBinding`. Expensive calls go to a worker pool, never the event loop or the isolate thread.
- **Runtime:** JavaScript stand-ins in `js/node/` as today (D-090), with `crypto.js` wrapping the host API in Node's and Web Crypto's shapes.
- **Submission and publishing:** `Imports.BUILT_INS` and the bundler's provided list gain the new names; refusals shrink to what's genuinely Node-only.
