# Ideas and notes

Ideas to keep but **not act on yet**. Nothing here is scheduled. When an idea is picked up, move it into the roadmap and record the decision in [docs/DECISIONS.md](docs/DECISIONS.md).

---

## Remote workers and a sandboxed browser module

*Captured 2026-10-06.*

### Background
The original Automately had a **browser module**. It wasn't part of Automately Core. It was the separate `webdriver` module (`webdriver-module-master.zip` in the old archive): `automately.modules.pub.network.webdriver.WebDriverObject`, built on **Selenium WebDriver** with pluggable drivers. It made browser automation at scale easy, and scripts used it through the JavaScript API:

```js
var wd = require("webdriver").create({"driver": "phantomjs"});
wd.open("http://automate.ly");
wd.waitForElement('.header-20-sub h3', 5000, function () { error("never showed up"); });
print(wd.getElement('.header-20-sub h3').text());
```

PhantomJS was only one of the drivers it could use, not the module itself.

### The idea
Deploy Automately remotely, on a server or in the cloud, and **attach workers from anywhere, for example a laptop**. A worker offers capabilities the cluster doesn't have, such as a real browser, and scripts can use those capabilities through a sandboxed host API.

- **Remote workers.** A worker dials *out* to the cluster over a WebSocket or gRPC stream. It doesn't join the Hazelcast cluster, so it works behind NAT and home networks without opening ports. It advertises its capabilities (`browser`, `gpu`, `local-files:/some/dir` and so on).
- **Capability-based scheduling.** A job declares what it needs (`requires: ["browser"]`), and the scheduler only places it on a worker that offers that capability. This extends the existing least-loaded placement.
- **A sandboxed browser API.** Scripts get a narrow `browser` host object (navigate, click, type, extract, screenshot), not raw access to Chrome or Playwright. It's checked per tenant and per job: allowed domains, session limits, no access to the worker's own profile, cookies or files unless the worker explicitly shares them.
- **Your own machine as the source.** Automation runs from a real residential machine with a real browser, without depending on a hosted browser service. *(The original thought trailed off at "without having to rely on…". Finish it here.)*

### Open questions
- What's the worker trust model? Tenants should only be able to use their *own* workers unless a worker is shared deliberately.
- How are workers authenticated? Probably a worker token with scopes, tied into the phase 4 auth work.
- Which browser engine? Playwright driven from Java (`com.microsoft.playwright`) or Chrome DevTools Protocol directly.
- A browser session can't move between workers. If a worker drops out mid-job, the job has to follow the orphaned-job rules in [docs/design/orphaned-jobs.md](docs/design/orphaned-jobs.md) and continue from the last completed step, not from the middle of a page.
- What does "at scale" mean here? Many workers each running a few browsers, or one worker with a pool?

### Fit with the roadmap
This builds on phase 3 (host API bindings, `ScriptRuntime` SPI) and phase 4 (tokens, MCP). It would make a strong headline feature after phase 5: "AI agents drive real browsers on machines you own, safely."

---

## Parked from the core-features review

*Captured 2026-10-07.* [docs/design/core-features.md](docs/design/core-features.md) has the review; these were left out on purpose.

- **Outside clients subscribing** to bus topics or queues, over SSE or WebSocket. 2017 had this through the SDK's SockJS bridge. Today outsiders can only publish and request (`POST /v1/bus/...`).
- **Reply to a reply.** In 2017, an answer to a `send` could itself be answered, so two scripts could hold a conversation. Tony: nice, not needed.
- **Modules and `require`.** In 2017, admins registered "native modules" that scripts loaded with `require`. Sharing code between scripts, and per-tenant packages, needs its own review.
- **Python through GraalPy, as a later phase that starts with a spike.** The runtime would be a `GraalPyScriptRuntime` behind `ScriptRuntime`, binding the same `*Api` objects (D-060) with `@HostAccess.Export` plus a thin `automately.py`. Tony: sandboxing is critical, so the spike has to pass each gate:
  - **Sandbox (must pass):**
    - GraalVM 25.1 brought polyglot isolates, `SandboxPolicy.ISOLATED`/`UNTRUSTED` and sandbox resource limits to Community Edition. ISOLATED runs guest code on a separate heap with a CPU-time limit. UNTRUSTED adds limits on guest memory, AST depth, stack frames and output, plus Spectre mitigations.
    - **Unconfirmed:** whether GraalPy is supported under UNTRUSTED. The sandboxing guide speaks of ECMAScript, and Oracle documents running GraalPy out of process (`engine.IsolateMode=external`) to isolate its native code.
    - **Regardless of that answer:** host access only to the exported APIs; no IO, threads, native access, processes or environment; and a wall-clock timeout through `Context.close(true)`.
    - **Defence in depth:** run guest code in a runner process or container with cgroup memory and CPU limits, seccomp, no network and a read-only filesystem, talking to the node over the same narrow bridge. This is the original plan's `role=runner`, and it would also answer SECURITY.md's "isolates share the node's process" for V8.
    - **Gate:** a Python port of the hostile-script suite (infinite loops, memory bombs, deep recursion, `import os/subprocess/ctypes/socket`, file reads, reaching another tenant). Each must be stopped or refused while the node stays healthy.
  - **Cold start:**
    - one shared `Engine` per node, so code caches and the JIT stay warm
    - the precompiled isolate artifacts
    - a small pool of fresh, never-reused contexts created in the background, which hides startup without breaking "no reuse"
    - **Gate:** Bench `coldstart` for Python at p50 under 100 ms (V8 is 29 ms).
  - **Compatibility:** pure Python plus part of the standard library. Native extensions such as numpy aren't offered inside the sandbox, because native access is what the sandbox forbids.
  - Sources: [GraalVM sandboxing](https://www.graalvm.org/latest/security-guide/sandboxing/), [GraalVM 25.1 release notes](https://www.graalvm.org/release-notes/25.1/).

## Parked from packages step 2

*Captured 2026-10-08* (D-089, D-090).

- **Web and Node globals in the isolate:** `URL`, `URLSearchParams`, `atob`/`btoa`, `structuredClone`, and Web Crypto. (`Buffer` and `process` are settled by D-093: the bundler imports them where bundled code uses them.)
  - **Why it matters:** bundled npm code often uses these without importing them, and fails at run time today.
  - **What's ready:** the stand-ins (D-090) already implement most of them, so the open question is only whether every isolate should pay to load them, or whether a bundler-inserted import is better.
- **A disk tier for the package cache:** design §5 says "then from the data directory". It's memory-only for now (`AUTOMATELY_PACKAGE_CACHE_MB`). Worth it once packages are big or nodes restart often.
- **Backtracking version resolution:** D-089 picks the newest version that meets every range, re-picking until nothing changes. Revisit only if real conflicts show up.

## Parked from the request-hang fix (D-099)
- **A request timeout in the CLI:** a server that never answers leaves the CLI waiting forever. The JDK client's per-request timeout runs until the response's headers, so it would also count a large upload's sending time. Options: a timeout only for requests without a body, or one that starts once the body is sent. Worth doing before the CLI ships widely.

## Parked from phase B (network reach)
- **The console's "Network access" page** (Tony, 2026-10-08): tenants should see the rules that apply to them, meaning what's allowed by default, what's refused and why, and their operator-granted rules. `GET /v1/egress-policy` is written to be that page's data, with a reason for each item (docs/design/network-reach.md §3). Still to decide: whether a tenant can *request* a rule from the console, which an operator then approves, and whether recent refusals (`egress_denied`, with the host and reason) are shown there too.
- **The same view for other policies:** the storage policy (D-057) and quotas could be explained to tenants the same way, in one "What this tenant may do" place.
- ~~**Unhandled promise rejections**~~: done in D-110; they fail the job as in Node.
- ~~**`got`**~~: works over HTTP/1.1 (D-114); `http2: true` waits for the `http2` client step.

## Parked from the engine comparison (D-126 step 3, 2026-10-10)
- **Oracle GraalVM's compiler (measured 2026-10-10, `results/2026-10-10-1701`):**
  - **Result:** Tested with Oracle GraalVM 25.4.4.1.1. It showed **no meaningful performance difference** on the object/JSON gap vs Community Edition (DeltaBlue 5.25× vs 5.06× on CE; json-roundtrip 8.51× vs 8.58× on CE).
  - **Resolution:** Dropped. We do not need the GFTC license or its redistribution restrictions. Community Edition (CE, UPL/MIT) remains the sole GraalJS build.
- **Host fast paths that depend on the engine:** a Java codec for Buffer's hex and base64 was built and measured, then removed. Encoding in Java won on V8 (up to 2.8×) and in-process GraalJS (up to 22×), but lost 2× across GraalJS's isolate, and decoding in Java lost everywhere. If the engine is chosen, the fast paths can be chosen for it.
- **GraalJS's cold start:** first runs are about 4× slower than V8's. To look at: warming the polyfills' hot paths when a worker starts, and whether Community Edition can keep compiled code between processes.
- **TextEncoder and TextDecoder** got 4× slower on GraalJS in the first measurements; check whether they need the same treatment as Buffer's codecs.

## Next after phase B (Tony, 2026-10-08; updated 2026-10-09)
Phase B finished on 2026-10-09 with WebSockets (D-125).

**First, since 2026-10-09: the engine-neutral runtime and tenant workers** (D-126, `docs/design/engine-neutral-runtime.md`).
- **Why:** a security review found the isolate's heap limit doesn't bound its memory, and Tony's concern is engine-level security.
- **In order:**
  1. the neutral layer, with Javet only
  2. an embedded GraalJS adapter
  3. both compared inside native-image tenant workers
  4. Tony picks the engine
  5. then the worker↔node protocol (designed in the doc), the worker pool with kernel memory caps, and gVisor/Firecracker
- **It is the shared execution layer below,** with the isolate as its first runner.

**Then, as planned before:**
- **The shared execution layer and the gVisor Node containers:**
  - **Status:** designed and accepted (`docs/design/node-runtime.md`, D-084 to D-086), and nothing built yet. It was meant to follow packages, but more of Node in the isolate (D-095 onward) came first.
  - **Starts with its spikes:**
    - runner latency
    - rootless Podman with gVisor and `sharp`
    - gVisor's `--host-uds`
    - tunnelled networking with `pg`/`ioredis`
    - artifact sizes
  - **What it reuses from phase B:** the egress gate (D-111) and the route table (D-112). Tony: "finish phase b, then we'll lock down the shared parts".
- **Then the links design's review** (`ideas/links.md`; not accepted yet).
  - **Its conflict to settle:** it has to decide TCP and UDP servers on a link's own host (D-112), against its "never listening publicly" rule.

