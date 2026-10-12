# Automately — Master Implementation Roadmap & Active Memory

**File:** `ROADMAP.md`  
**Author / Organization:** Adam & Lynn Incorporated <tony@adamnlynn.com>  
**License:** MPL-2.0  
**Repository:** `git@github.com:adamnlynn/automately-v2.git` (`main`)  
**Last Updated:** 2026-10-11  

---

## 1. Operating Instructions & Memory Protocol

To prevent context drift, loss of momentum, and misalignment across sessions or agents:

1. **Check `ROADMAP.md` First:**
   - Every session or new work order must inspect this document to understand the active milestone, the next immediate steps, and what is currently in flight.
2. **Update After Milestones:**
   - When a milestone, phase, or critical bug is resolved, immediately update the **Active Work** and **Recent Achievements** sections here.
3. **Record Architectural Decisions in `docs/DECISIONS.md`:**
   - Any trade-off, invariant, or architectural pattern chosen during implementation must be assigned a `D-xxx` decision record. Never rewrite or delete past decisions; supersede them.
4. **Preserve Operational Invariants:**
   - Author / Committer attribution: `Adam & Lynn Incorporated <tony@adamnlynn.com>`.
   - Architectural isolation: `automately-core` depends *only* on `vertx-core`; Apache Ignite is strictly confined to `automately-cluster-ignite`.
   - Quality gates: Always check for crash logs (`find . -name 'hs_err_pid*.log'`). Run `./mvnw verify -pl <module> -am -DskipITs` (or `./scripts/test-all.sh`). Never run `DockerClusterIT` unless Tony explicitly requests it.
   - Protected docs: Do not overwrite user edits to `README.md`, `CHANGELOG.md`, `NOTES.md`, `SECURITY.md`, `docs/DECISIONS.md`.

---

## 2. High-Level Project Roadmap

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1-3: Core Cluster, Storage, Runtime & Observability (COMPLETED) │
│ • Vert.x 5 + Apache Ignite 2.18 WAL, Caches, Supervisors, Metrics     │
│ • Dual Engines: Javet (V8) & GraalJS sandboxes, node:* stand-ins       │
│ • REST API, OpenAPI, MCP server, Ingress, TLS, Egress Gate             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 4: In-Cluster Worker Protocol & Out-of-Process Isolation (DONE)  │
│ • automately-runner-protocol: 12-byte frames, credit flow control, UDS │
│ • automately-worker daemon, binary discovery, JVM fallback (D-131)     │
│ • Full process-mode parity: streaming APIs, events, cgroups (D-132)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 5: Documentation, Javadoc Aggregation & Hardening (ACTIVE)       │
│ • Architecture Guide & DOCUMENTATION.md                                │
│ • Clean JDK 21 aggregate Javadoc (./mvnw javadoc:aggregate)            │
│ • 100% test-pass stability across all modules in process mode          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 6: Web Management Console & Identity Sessions (NEXT)             │
│ • Target Spec: ideas/console.md (D-070 to D-074 identity foundation)   │
│ • WebAuthn Passkeys, OIDC PKCE flow, and __Host- session cookies       │
│ • Vite + React + TanStack Router SPA bundled in automately-server      │
│ • Real-time job logs (SSE), VFS file browser, service supervisor UI    │
│ • Strict typing via openapi-typescript against OpenAPI contract        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ Phase 7: Node Runtime in Containers / gVisor (UPCOMING - Item 11)      │
│ • Target Spec: docs/design/node-runtime.md                             │
│ • Official Node runner in rootless Podman + gVisor (runsc)             │
│ • Native npm addons (sharp, bcrypt, better-sqlite3), full npm ci       │
│ • Zero-network sandbox with UDS protocol socket tunneling              │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Active Work & Current Focus

### Phase 5: Architecture Documentation & Maven Javadoc Aggregation
- **Task 5.1 — Master Documentation (`DOCUMENTATION.md`):**
  - Documented core architectural subsystems: `HostApis`, `NodeHostApis`, `ScriptRunner`, engine adapters, storage SPIs, network gates, and `automately-runner-protocol`.
  - Maintained canonical cross-references and links to `docs/ARCHITECTURE.md` and `docs/DECISIONS.md`.
- **Task 5.2 — Clean Aggregate Javadoc (`./mvnw javadoc:aggregate`):**
  - Configured aggregate Javadoc generation under JDK 21 (`maven-javadoc-plugin` 3.11.2 with `<doclint>all,-missing</doclint>` and `<release>21</release>`).
  - Verified clean aggregate build generating docs for all public modules with zero doclint errors.
- **Task 5.3 — 100% Process Mode Verification:**
  - Automated test runner `./scripts/test-all.sh` outputs to `lasttest.txt` by default and summarizes failures.
  - Resolved `ConcurrentModificationException` in `NodeVerticle.checkRunningJobs()`.
  - Resolved process-mode `STATE` durability race condition in `NodeWorkerChannel` (tracked pending state commits before completing execution result; passed `ChaosTest` and hardened `ProcessModeParityTest`).
  - Clean run across all test suites under `AUTOMATELY_WORKER_MODE=process`.

---

## 4. Upcoming Work

### Phase 6: Web Management Console & Browser Identity Sessions (NEXT)
- **Reference Spec:** `ideas/console.md` (builds upon D-070 to D-074 identity foundation).
- **Core Objectives:**
  1. **Browser Session Layer:**
     - WebAuthn Passkeys (`java-webauthn-server` or `webauthn4j`) and OIDC PKCE flow.
     - `__Host-` session cookies (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`).
     - Session token minting for tenant interactions (`POST /v1/me/tokens` into memory).
  2. **Single-Page Application (SPA):**
     - Framework: Vite + React + TanStack Router + TanStack Query + Tailwind CSS / shadcn/ui.
     - Strictly typed API client generated from `openapi.yaml` via `openapi-typescript`.
     - Zero cold starts: pure static assets bundled inside `automately-server` jar under `/console/` (or via ingress).
  3. **Functional Views:**
     - **Jobs & Workflows:** Real-time streaming console logs (`/v1/jobs/{id}/events` via fetch streaming), step inspection (`ctx.step`), attempt history, manual cancel.
     - **Services:** Real-time health, supervisor status, restart backoffs, route inspector.
     - **VFS File Explorer:** Directory tree navigation, chunked uploads, download test, presigned links.
     - **Cluster & Tenant Admin:** Node capacity/heartbeats, tenant quotas, team roles & invite codes, audit chain verifier.

### Phase 7: The Node Runtime in Containers / gVisor
- **Reference Spec:** `docs/design/node-runtime.md` (D-084, D-085, D-086).
- **Core Requirements:**
  1. **Runtime Type `"node"`:** Enable execution of native Node.js workloads that require `.node` C++ addons, `child_process`, `worker_threads`, and frameworks like Express/Fastify/Next.js.
  2. **Official Node + JS Runner:** Lightweight Node runner package implementing Automately globals over the runner protocol IPC stream.
  3. **Container Sandboxing:** Rootless Podman containerization with gVisor (`runsc`) user-space kernel for untrusted tenants.
  4. **Zero-Trust Network Tunneling:** Container runs `--network none`; all egress (`fetch`, `net.Socket`) and ingress (`ctx.serve`) are piped through the UDS protocol channel.
  5. **Layered Artifacts:** Deployments split into content-addressed tar layers (`node_modules`, app source) without running untrusted install scripts on cluster nodes.

### Phase 8: Differentiated Node Roles & Capability-Based Scheduling
- **Reference Spec:** `ideas/node-roles.md`.
- **Core Requirements:**
  1. **Node Specialization:**
     - **API / Ingress Gateway Nodes:** Terminate public TLS, validate OpenAPI requests, handle token auth, route ingress traffic; execute zero untrusted guest code.
     - **Storage / Data Nodes:** Run durable Apache Ignite persistence, WAL, and VFS chunks without CPU contention from script workloads.
     - **Compute Worker Nodes:** Run on private subnets hosting sandboxed execution engines with kernel cgroups boundaries.
  2. **Capability Matching:** Route jobs to nodes advertising matching runtime capabilities (`capabilities: ["js:v8", "js:graaljs", "node"]`).

### Phase 9: Polyglot Serverless Compute (Python)
- **Reference Spec:** `ideas/python.md`.
- **Core Requirements:**
  1. Dedicated Python worker daemon communicating over `automately-runner-protocol` binary framing, bringing Python background jobs and ASGI microservices to the cluster.


---

## 5. Recent Key Achievements & Bug Fixes

- **`NodeVerticle` Thread Safety Fix:** Replaced `HashMap` with `ConcurrentHashMap` for `slots` and snapshot copies in `checkRunningJobs()`, preventing `ConcurrentModificationException` during concurrent job completion/cancellation.
- **Unified Test Script (`scripts/test-all.sh`):** Added default logging to `lasttest.txt`, real-time console streaming, and instant failure summaries with grepped error lines.
- **Full Process-Mode Parity (D-132, commit `87cc57c`):**
  - Bidirectional stream multiplexing for `ctx.serve` (ingress), `fetch` (egress), `net`/`tls` sockets, and WebSocket upgrade tunneling.
  - Credit-based connection window replenishment (`streamId=0`) eliminating 4 MiB stream deadlocks.
  - Immediate worker crash / EOF detection via `input.endHandler()`.
  - Non-blocking state commits (`vertx.executeBlocking`) keeping event loop responsive.
- **Default Process Mode (D-131, commit `8e428e8`):**
  - Set `AUTOMATELY_WORKER_MODE=process` by default.
  - Dynamic discovery of native worker binaries in `bin/` with seamless child-JVM fallback.
