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

