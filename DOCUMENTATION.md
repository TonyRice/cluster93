# Cluster93 Documentation Standard & Engineering Guide

This document defines the structure, lifecycle, and authorship rules for all documentation across the Cluster93 project.

---

## 1. Core Principles

1. **Grounded in Verified Reality (Zero Hallucination):**
   - Documentation must describe **what is currently built and verified in code**.
   - Planned or upcoming features belong exclusively in the Roadmap section of `README.md` or in `ideas/`. They must never be described in user guides or security documentation as active capabilities.
   - Any documented performance number, capacity limit, or security boundary must link to a reproduction script (e.g. `benchmarks/run.sh`), test class, or formal Decision Record (`docs/DECISIONS.md`).

2. **Single Source of Truth (Zero Redundancy):**
   - Every architectural domain has exactly one canonical home.
   - **Do not duplicate exhaustive tables:** The complete 65-row OpenAPI route index lives in [`docs/api/endpoints.md`](docs/api/endpoints.md), not in `README.md`.
   - **Contract-first:** The OpenAPI contract [`openapi.yaml`](cluster93-server/src/main/resources/openapi.yaml) is the ultimate source of truth for REST endpoints; API docs must mirror it exactly.

3. **Immutability of Decision Records (ADRs):**
   - Entries in [`docs/DECISIONS.md`](docs/DECISIONS.md) are **append-only** and never deleted.
   - If an architectural choice changes, create a new decision entry that supersedes the prior one (e.g., *“D-127 supersedes D-126’s single-engine assumption”*).

4. **Preservation of Research Context:**
   - Deep technical investigations (such as [`ideas/native-memory-investigation.md`](ideas/native-memory-investigation.md) with low-level C++ line references and measured attack vectors) are permanent engineering assets. Do not delete them as "scratchpads."

---

## 2. Directory Taxonomy & Organization

```
cluster93/
├── README.md                      # Front door: Hook, mental model, verified status, quickstart, roadmap
├── SECURITY.md                    # Threat model, defense boundaries, and deployment security checklist
├── CHANGELOG.md                   # Keep-a-Changelog format grouped by release, referencing ADRs
├── TESTING.md                     # Verification guide: unit, contract, chaos, and integration suites
├── DOCUMENTATION.md               # This meta-standard
├── docs/
│   ├── ARCHITECTURE.md            # Subsystem navigation map, module boundaries, code flow
│   ├── DECISIONS.md               # Architectural Decision Records (ADRs D-001 through D-N)
│   ├── benchmarks.md              # Measured performance scenarios and reproduction commands
│   ├── api/
│   │   └── endpoints.md           # Exhaustive REST API & MCP endpoint documentation
│   ├── design/                    # Normative, accepted architectural specifications
│   │   ├── engine-neutral-runtime.md
│   │   ├── network-reach.md
│   │   ├── node-runtime.md
│   │   ├── packages.md
│   │   ├── vfs.md
│   │   └── worker-node-protocol.md
│   ├── guides/                    # Operator and developer operational guides
│   │   ├── cli.md                 # Native CLI installation and usage
│   │   ├── mcp.md                 # Connecting AI agents via Model Context Protocol
│   │   ├── cluster.md             # Cluster certificates, mutual TLS, and node joins
│   │   ├── storage.md             # S3 backends, local disk mounts, quotas
│   │   ├── users.md               # RBAC, invites, roles, groups, audit log
│   │   └── agent-context-and-javadocs.md # High-density token optimization & Javadoc guide
│   └── scripting/                 # Guest JavaScript runtime reference
│       ├── host-apis.md           # kv, bus, queues, files, fetch, http
│       ├── job-context.md         # ctx.step, checkpoints, attempts, orphan resume
│       ├── services.md            # Long-running supervised services, WebSockets
│       └── node-compat.md         # Node.js polyfills matrix & differences from Node
└── ideas/                         # Local-only design explorations & upcoming roadmap (git-excluded)
    ├── README.md                  # Index of active ideas and graduated features
    ├── node-roles.md              # Differentiated node roles (Gateway vs Storage vs Worker)
    ├── node-runtime.md            # Native Node & gVisor container runtime
    ├── links.md                   # External cluster93-link devices & capabilities
    ├── console.md                 # Web console & WebAuthn session architecture
    ├── python.md                  # Python serverless execution
    ├── apps.md                    # Static site hosting & API routing
    ├── databases.md               # Typed tenant databases backed by Postgres
    └── native-memory-investigation.md # Low-level V8/Javet memory attack research
```

---

## 3. Document Types & Ownership Rules

### A. Root Documentation
- **[`README.md`](README.md):** Must be concise and accessible to prospective users and hiring managers. Highlights the Cloudflare Workers / AWS Lambda mental model, verified Phase status, copy-paste quickstarts, and the high-level roadmap.
- **[`SECURITY.md`](SECURITY.md):** The legal and operational contract for sandboxing. Defines who is trusted, who is hostile, and what each boundary defends. Updated whenever security boundaries, defaults (like `C93_WORKER_MODE=process`), or network gates change.
- **[`CHANGELOG.md`](CHANGELOG.md):** Strictly updated under `[Unreleased]` for every commit that touches functionality. Always format with bold change titles, bulleted technical context, and decision references (`D-xxx`).

### B. Architectural Specifications (`docs/design/`)
- A design document is created when an idea from `ideas/` is accepted for implementation.
- Must include:
  1. **Status & Decision References:** Date accepted, associated ADR numbers (`D-xxx`).
  2. **Problem Statement & Motivation:** Why this capability is needed.
  3. **Normative Wire & API Formats:** Exact byte layouts, frame types, HTTP routes, or Java SPI interfaces.
  4. **Threat Model & Error Modes:** How failures, crashes, and malicious payloads are handled.
  5. **Verification & Testing Strategy:** How correctness is proven before shipping.

### C. Developer & Operational Guides (`docs/guides/`, `docs/scripting/`)
- Written from the perspective of an operator deploying the cluster or a developer writing guest JavaScript code.
- Must include runnable code snippets, expected JSON responses, and troubleshooting hints.
- Avoid low-level internal Java mechanics here; keep focus on the developer experience (DX).

### D. Explorations & Research (`ideas/`)
- Located in `ideas/`, excluded from git tracking via `.git/info/exclude`.
- Serves as the incubator for future milestones.
- When an idea is implemented, its core specification moves to `docs/design/` and its status in `ideas/README.md` is updated to "Graduated."

---

## 4. Writing & Formatting Standards

### Markdown Standards (GFM)
- Use standard GitHub Flavored Markdown.
- **Clickable Symbol Links:** Always link file paths and symbols using markdown links (e.g. [`NodeHostApis`](cluster93-core/src/main/java/cluster93/core/host/NodeHostApis.java) or [`openapi.yaml`](cluster93-server/src/main/resources/openapi.yaml)).
- **Mermaid Diagrams:** Use `flowchart TD`, `flowchart LR`, or `sequenceDiagram`. Always quote node labels containing special characters (e.g., `id["Label (Extra Info)"]`).

### High-Density Javadocs & Agent Context Optimization
To maximize LLM context efficiency and prevent hallucinations during automated pair-programming, follow [`docs/guides/agent-context-and-javadocs.md`](docs/guides/agent-context-and-javadocs.md):
- **Document contracts on interfaces and public methods:** Provide explicit `@param`, `@return`, and `@throws` tags.
- **Document invariants:** Nullability, thread safety, and resource ownership must be stated explicitly.
- **Doclint Compliance:** All Javadocs must pass cleanly under `maven-javadoc-plugin` (`./mvnw javadoc:aggregate -DskipITs`) with zero broken tags.

---

## 5. Core Architectural Subsystems & Code References

For detailed module layouts, wire framing layouts, and flow control mechanisms, see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/DECISIONS.md`](docs/DECISIONS.md). The canonical reference implementation for each subsystem is indexed below:

| Subsystem | Canonical Contracts & Implementation Classes | Architectural Specification / ADR |
|---|---|---|
| **Host APIs (Guest Facing)** | [`HostApis`](cluster93-core/src/main/java/cluster93/core/runtime/api/HostApis.java), [`HostFunction`](cluster93-runtime-js/src/main/java/cluster93/runtime/js/HostFunction.java), [`StateSink`](cluster93-core/src/main/java/cluster93/core/runtime/StateSink.java) | [`docs/scripting/host-apis.md`](docs/scripting/host-apis.md), `D-025` |
| **Node Authority APIs** | [`NodeHostApis`](cluster93-core/src/main/java/cluster93/core/host/NodeHostApis.java), [`SharedDataKvStore`](cluster93-core/src/main/java/cluster93/core/host/SharedDataKvStore.java) | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §4, `D-030` |
| **Script Runner & Conformance** | [`ScriptRunner`](cluster93-runtime-js/src/main/java/cluster93/runtime/js/ScriptRunner.java), [`JsEngine`](cluster93-runtime-js/src/main/java/cluster93/runtime/js/JsEngine.java) | [`docs/design/engine-neutral-runtime.md`](docs/design/engine-neutral-runtime.md), `D-126`, `D-127` |
| **Engine Adapters** | [`JavetEngine`](cluster93-runtime-javet/src/main/java/cluster93/runtime/javet/JavetEngine.java) (V8), [`GraalJsEngine`](cluster93-runtime-graaljs/src/main/java/cluster93/runtime/graaljs/GraalJsEngine.java) | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §3, `D-126` |
| **Storage & Blob SPIs** | [`FileStore`](cluster93-core/src/main/java/cluster93/core/files/FileStore.java), [`BlobStore`](cluster93-core/src/main/java/cluster93/core/files/BlobStore.java), [`IgniteFileStore`](cluster93-cluster-ignite/src/main/java/cluster93/cluster/ignite/store/IgniteFileStore.java) | [`docs/design/vfs.md`](docs/design/vfs.md), [`docs/guides/storage.md`](docs/guides/storage.md) |
| **Egress & Network Gates** | [`EgressGate`](cluster93-core/src/main/java/cluster93/core/host/egress/EgressGate.java), [`AllowlistEgressGate`](cluster93-core/src/main/java/cluster93/core/host/egress/AllowlistEgressGate.java) | [`docs/design/network-reach.md`](docs/design/network-reach.md), [`SECURITY.md`](SECURITY.md) |
| **Runner Protocol & Channels** | [`FrameCodec`](cluster93-runner-protocol/src/main/java/cluster93/runner/protocol/frame/FrameCodec.java), [`NodeWorkerChannel`](cluster93-runner-protocol/src/main/java/cluster93/runner/protocol/server/NodeWorkerChannel.java), [`StreamMultiplexer`](cluster93-runner-protocol/src/main/java/cluster93/runner/protocol/stream/StreamMultiplexer.java) | [`docs/design/worker-node-protocol.md`](docs/design/worker-node-protocol.md), `D-128`, `D-131`, `D-132` |
| **Worker Pools & Isolation** | [`TenantWorkerPool`](cluster93-runner-protocol/src/main/java/cluster93/runner/protocol/pool/TenantWorkerPool.java), [`WorkerProcessRuntime`](cluster93-runner-protocol/src/main/java/cluster93/runner/protocol/runtime/WorkerProcessRuntime.java) | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §4, `D-128`, `D-131` |
| **Job Scheduling & Supervisors** | [`NodeVerticle`](cluster93-core/src/main/java/cluster93/core/scheduler/NodeVerticle.java), [`ServiceSupervisor`](cluster93-core/src/main/java/cluster93/core/service/ServiceSupervisor.java) | [`docs/scripting/job-context.md`](docs/scripting/job-context.md), [`docs/scripting/services.md`](docs/scripting/services.md) |

---

## 6. Maintenance Checklist for Feature Changes

Whenever modifying or adding an architectural feature:

1. [ ] **Code & Javadocs:** Update class and method docstrings. Ensure aggregate Javadoc builds without doclint errors.
2. [ ] **Decision Record:** If a design choice or trade-off was made, record it in [`docs/DECISIONS.md`](docs/DECISIONS.md) (`D-xxx`).
3. [ ] **Changelog:** Add an entry under `[Unreleased]` in [`CHANGELOG.md`](CHANGELOG.md).
4. [ ] **User Documentation:** Update the corresponding guide in `docs/guides/` or `docs/scripting/`.
5. [ ] **API Reference:** If routes changed, update [`openapi.yaml`](cluster93-server/src/main/resources/openapi.yaml) and [`docs/api/endpoints.md`](docs/api/endpoints.md).
6. [ ] **README / SECURITY:** If defaults, execution modes, or security boundaries changed, update [`README.md`](README.md) and [`SECURITY.md`](SECURITY.md).
7. [ ] **Verification:** Verify that all referenced markdown links exist and resolve.

