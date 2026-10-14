# Ideas and notes

Ideas to keep but **not act on yet**. Nothing here is scheduled. When an idea is picked up, move it into the roadmap and record the decision in [docs/DECISIONS.md](docs/DECISIONS.md).

---

## Remote workers and a sandboxed browser module

*Captured 2026-10-06.*

### Background
The original Cluster93 had a **browser module**. It wasn't part of Cluster93 Core. It was the separate `webdriver` module (`webdriver-module-master.zip` in the old archive): `cluster93.modules.pub.network.webdriver.WebDriverObject`, built on **Selenium WebDriver** with pluggable drivers. It made browser automation at scale easy, and scripts used it through the JavaScript API:

```js
var wd = require("webdriver").create({"driver": "phantomjs"});
wd.open("http://automate.ly");
wd.waitForElement('.header-20-sub h3', 5000, function () { error("never showed up"); });
print(wd.getElement('.header-20-sub h3').text());
```

PhantomJS was only one of the drivers it could use, not the module itself.

### The idea
Deploy Cluster93 remotely, on a server or in the cloud, and **attach workers from anywhere, for example a laptop**. A worker offers capabilities the cluster doesn't have, such as a real browser, and scripts can use those capabilities through a sandboxed host API.

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
- **Python through GraalPy, as a later phase that starts with a spike.** The runtime would be a `GraalPyScriptRuntime` behind `ScriptRuntime`, binding the same `*Api` objects (D-060) with `@HostAccess.Export` plus a thin `cluster93.py`. Tony: sandboxing is critical, so the spike has to pass each gate:
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
- **A disk tier for the package cache:** design §5 says "then from the data directory". It's memory-only for now (`C93_PACKAGE_CACHE_MB`). Worth it once packages are big or nodes restart often.
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


---

## One unified `c93 cluster` pathway (install, dev, Docker, cluster setup)

*Captured 2026-10-13.*

### Background
The CLI's cluster-facing surface is fragmented across three commands that overlap, so `c93 --help` shows four names for a node and no single front door:

- `c93 cluster` — the cluster's own CA and join lines (`cluster init`).
- `c93 local` — the local master node; a deprecated alias of `c93 node` as of D-134, kept for one release.
- `c93 node` — install/run/manage a node, plus the operator's `invite`/`revoke`.

Standing up a cluster means knowing which of the three to reach for, and the *ways to get a node* are spread across the CLI (systemd/launchd/direct, `c93 node install`), the shell orchestrator (`scripts/local.sh`), and Docker (`docker-compose.yml`, `scripts/demo.sh`) with no shared story.

### The idea
One unified **`c93 cluster …`** pathway for standing up and managing Cluster93, with explicit, first-class routes:

- **Master node** — the permanent node a machine (or a checkout) owns. Today: `c93 node install` / `scripts/local.sh setup`.
- **Dev node** — a throwaway local stack for development. Today: `scripts/local.sh dev` (W1 folded `dev.sh` into the profile-aware orchestrator).
- **Docker node** — run a node as a container, driven from the CLI rather than hand-edited Compose. Today: `docker-compose.yml` + `scripts/demo.sh` only.
- **Cluster** — the multi-node story: `cluster init` (CA + one-line joins), then each node joins; `invite`/`revoke` belong to the same group instead of a separate `node` group.

The goal is a `--help` that reads as one story — set up a master, set up a dev node, run a node in Docker, set up a cluster — instead of four names for a node and three places to configure one. It should audit the whole pathway (CLI surface, node deploy for a real cluster, the setup wizard, docs) rather than patch it.

### Open questions (settle when this is picked up)
- Does `c93 cluster` absorb `c93 node`, or does `node` stay the per-machine command while `cluster` orchestrates several?
- Is the Docker route a CLI verb (`c93 cluster docker …`) that drives Compose, or generated compose/env a user runs?
- What happens to `c93 local` — drop the alias, or keep it as the explicit "dev node" verb?
- How does it interact with W1's profile split (`scripts/local.sh dev|master`): does the CLI shell out to a profile, or take over the orchestration?
- Where do the three "install pathways" share code — `c93 node install` (release fetch + layout) is the one implementation today; does the Docker route reuse the same artifacts?

**Related:** Phase 11 consolidation (W1, W5) already removes some of the duplication; this is the bigger follow-up.

---

## Open items after Phase 11 (plan)

*Captured 2026-10-14. Ordered by value, not effort.*

### 1. The engine conformance suite doesn't run — **FIXED**

`runtime-javet/pom.xml` and `runtime-graaljs/pom.xml` listed `c93:cluster93-runtime-js` in
`<dependenciesToScan>`, but the groupId is `cluster93` (the rebrand renamed it). Surefire therefore matched
nothing: `runtime-js`'s tests never ran on either engine, and `mvn verify` was green without them. Found
while verifying D-139, whose test only ran because it was invoked by hand.

**Fixed:** `c93:` → `cluster93:` in both poms. The suite runs on V8 and on GraalJS — **137 tests each, 0
failures**, so nothing had rotted; it simply wasn't running. `TESTING.md` §3.3 now records where it lives,
the count to expect, and the trap (a wrong groupId in those two poms silently runs nothing).

### 2. Benchmarks: de-emphasised (option b) — DONE

The harness is consolidated (`benchmarks/run.sh cluster|engines|all`), but its numbers are from one
laptop on 2026-10-07 and are no longer a headline claim. Options:

- **(a) Remove:** delete `benchmarks/` + `docs/benchmarks.md` + the links (loses D-126's engine-comparison evidence and D-059's upload numbers).
- **(b) De-emphasise (recommended):** keep the harness, stop quoting the numbers in README/ROADMAP, and mark `docs/benchmarks.md` as indicative and dated.
- **(c) Keep only `engines`:** the engine comparison is the evidence for the engine choice (D-126); drop the cluster harness (Docker, ~10 min, and it regenerates the doc).
- **(d) Freeze:** leave as is, stop maintaining it.

**Decision needed.**

### 3. A real signed release (so install and update are proven end to end) — DEFERRED (no real release for now)

`install.sh --node` and `c93 update` are proven against local release hosts only: the published release
predates the platform assets (`c93.jar`, `worker-*.gz`, `cluster93-cli.jar`) and the workflow is
`.disabled`. Plan: run `scripts/release-local.sh <version>` with the signing key
(`C93_RELEASE_PASSPHRASE`) and `C93_GITHUB_TOKEN` to publish a signed release, then prove
`install.sh --node` and `c93 update --cli|--node` against GitHub.

### 4. `--system` install (root-only, unverified)

`c93 node install --system` creates the `cluster93` user, owns `/var/lib/cluster93`, and writes
`/etc/cluster93/node.env` as `0640 root:cluster93` — verified only by unit tests, because it needs root.
Plan: a throwaway container or VM where `sudo c93 node install --system` runs, checking the ownership and
`systemctl start cluster93` (and that `uninstall` leaves the user in place).

### 5. Flakes — the cascade is FIXED; the rest is watched

- **Ignite suite cascade — FIXED.** The server module shares one JVM under `mvn verify`, and a failed
  `@BeforeAll` (a node that couldn't join) left `clientVertx`/a node field null, so the `@AfterAll` NPE'd
  **before** stopping the nodes: their Ignite instances stayed registered and every later class failed with
  "Ignite instance already started". Teardowns now guard the client Vertx and each node stop (73 guards
  across 30 suites), so a failed start still tears down what did start. `mvn verify` keeps one JVM (fast —
  per-class forks cost ~3.5 min more); `scripts/test-all.sh` still forks per class.
- **Transient jar/manifest read:** `CliTest.clusterInitAndNodeInviteMakeOneLineJoins` failed once with
  `IOException: invalid header field`; it passes in isolation and on re-run. Watch for recurrence.
- **`FileFailoverTest` TLS keystore flake:** under load, `Cluster93.start` occasionally fails with
  `UnrecoverableKeyException: Get Key failed: Given final block not properly padded` (a keystore read with the
  wrong key/password); it passes on re-run, and it reproduces on the pre-rename tag. Pre-existing and now
  *contained*: the guards above mean it costs one test instead of the whole suite.

### 6. `lasttest.txt` — DONE (git-ignored)

`scripts/test-all.sh` writes it at the repo root and it is untracked. Add it to `.gitignore`.

### 7. Cross-install detection (done, D-140)

A user node and a system node both default to the same ports (9393/9394), so installing both would leave
them fighting over them. `c93 node install` now refuses when the *other* kind is already installed —
either direction — naming the file and how to remove it, with `--force` to install both deliberately
(and then different ports in `node.env`).

- **Left open:** when both *are* installed (via `--force`), `c93 node <verb>` drives the system one,
  because `installedLayout` prefers `/etc/cluster93/node.env`; the user one needs an explicit override.
  A `--user`/`--system` flag on the lifecycle verbs (or on `node` itself) would let both be addressed.

### 8. Docker as an install pathway (NEXT UP — outline agreed, needs a deeper plan)

*"local or system install should be able to support docker too if they wanted, which would require file
persistence."* Today Docker is only the demo (`docker-compose.yml`), not something `c93 node` installs or
drives.

Agreed in outline:
- **The image ships the native workers** (`bin/worker-*`), so a Docker node can run `C93_WORKER_MODE=process`.
- **Workers in their own container too.** The runner protocol already carries worker-node IPC, so a worker
  can be a sibling container rather than a process inside the node's container. This is the piece that needs
  the most design (the transport across a container boundary).
- **Image source:** build locally for now (from the fetched release); signed releases later; `--image` so a
  user can supply their own.
- **Compose**, driven by `c93 node` (`docker compose up -d` / `down` / `logs`).
- **Persistence:** bind-mount the container's `C93_DATA_DIR`, so `--purge`, backups and paths match a process
  install.
- **Engines:** try both `docker` and `podman`.
- **Clash check:** a Docker node is a third kind, so D-140's detection must cover it (process-user,
  process-system, docker).

Shape: `c93 node install --docker [--system] [--dir DIR] [--version TAG] [--image IMAGE] [--no-start]`,
laying out the config/env and a compose file, with the lifecycle verbs driving the container. **Plan this
properly before building it.**
