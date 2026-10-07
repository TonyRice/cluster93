# Changelog

All notable changes to Automately are recorded here, with **what** changed and **why**.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Larger choices are explained in [docs/DECISIONS.md](docs/DECISIONS.md).

## [Unreleased]

### Removed
- GitHub Actions CI. **Why:** the repo is private and local `./mvnw verify` is the gate for now (D-017).

### Docs
- D-055: Tony approved GitHub Actions for the tag-only CLI release workflow, and public downloads come from the repository's GitHub Releases once it's public. The installers already default to that, so no code changed.
- The phase 4 plan's milestone 3 now includes the one-line install (`curl … | sh` and `irm … | iex`), native builds for Windows, Linux (x64 static, arm64) and macOS (arm64, x64), checksums, and a tag-only release workflow (D-049, confirming D-048). **Why:** Tony wants the CLI "super easy to install via curl" on all three systems. Two questions are open: GitHub Actions for the release builds, and where public downloads come from while the repo is private.
- `docs/design/phase-4-plan.md`: the phase 4 plan (tenants and scoped tokens, the OpenAPI contract and rate limits, the CLI, the MCP server), with D-044 to D-047 accepted and D-048 (the CLI as a native binary) proposed. **Why:** Tony chose the bootstrap, the contract-first approach and the MCP transport; the CLI question is still open.
- D-018: name and rights recorded. Automately was renamed Executory after the acquisition, and Tony holds the rights to the pre-acquisition OSS code.

### Added
- **`DockerClusterIT`**, a Testcontainers integration test that scripts the phase 2 Docker check. It runs three containers built from the jar `mvn package` produced, with production timings (1 s heartbeat). One scenario `SIGKILL`s the container running a resumable job mid-step and checks: attempt 2 runs on another node, steps finished before the kill aren't repeated, every invoice exists exactly once, and the SSE stream shows the `attempt` and `end` events. A second scenario `SIGKILL`s and immediately restarts a node under a `fail` job; it must end `ORPHANED` with only `charge` recorded. Run by Failsafe in `./mvnw verify` and in CI; skipped without Docker, or with `-DskipITs`. **Why:** the check was manual, and a real `SIGKILL` is the one failure the in-JVM tests can't produce.
- **Dockerfile stages:** `runtime` (shared), `local-jar` (copies the already packaged jar, used by the test), `build`, and `release` (the default). **Why:** the test exercises the exact artifact the build produced, without a second Maven build inside Docker.
- `.gitattributes`: `mvnw` keeps LF and `mvnw.cmd` keeps CRLF.
- `docs/design/revival-plan.md`: the original approved revival plan (what Automately was, its positioning, target architecture, the 8 weak spots and their fixes, the phases), copied unchanged with a status block on top. **Why:** it only existed outside the repo, and the reasoning behind the project should live with the code.
- `NOTES.md`: ideas to keep but not act on yet. The first is remote workers, such as a laptop, offering a sandboxed browser capability. It's modelled on the original `webdriver` module.
- `docs/design/orphaned-jobs.md`: accepted design for jobs whose node dies. Covers attempts, the `onOrphan` policy (`fail` by default), `ctx.step` and checkpoints for resuming without repeating work, and epoch fencing against zombie attempts. **Why:** restarting orphaned jobs blindly would repeat side effects.
- `docs/scripting/job-context.md`: the script-facing API for attempts, `ctx.step`, `ctx.saveCheckpoint`, policies and limits, with complete resumable, retry and default (`fail`) example scripts. **Why:** Tony wanted the whole resume flow laid out so it's clear end to end before it's built.
- `docs/design/phase-2-plan.md`: the phase 2 implementation plan. It adds node instance ids, per-attempt state, compare-and-set transitions and a lock-free reaper (D-009 to D-012). **Why:** planning against the real code showed that a restarted node would hide its own orphans, and that state needs fencing.
- Authorship: Adam & Lynn Incorporated (tony@adamnlynn.com) recorded in the POM (`organization`, `developers`), the README, and the repository's git config.
- This changelog and `docs/DECISIONS.md`. **Why:** every change and its reasoning should be traceable.

## Phase 4, milestone 4 — The MCP server (2026-10-06)

### Added
- **MCP at `/mcp` on every node** (D-047, D-054): Streamable HTTP, stateless, using the same tokens, scopes and rate limits as the API.
  - **Tools:** `run_job`, `get_job`, `job_logs`, `list_jobs`, `cancel_job`, `deploy_service`, `list_services`, `service_status`, `service_logs`, `start_service`, `stop_service`, `restart_service`, `delete_service`, `write_file`, `read_file`, `list_files`, `delete_file`, `publish`, `request`. Each is annotated read-only or destructive, and each acts through the REST API with the caller's token.
  - **Resources:** the three scripting guides (`automately://docs/…`), bundled from `docs/scripting/`. An `instructions` text on `initialize` explains the script shape.
  - **Why:** the positioning since the revival plan is that AI agents can deploy and run code on Automately.
- `/mcp` is in the OpenAPI contract, so `ContractTest` covers it.
- **Tests:** `McpTest` covers the handshake, version negotiation, notifications, errors, batches, 401/403, resources, `run_job` with logs, scopes, and an agent deploying a service, calling it through the ingress, then reading its logs, files and bus. `CliTest` drives the stdio bridge.
- [`docs/guides/mcp.md`](docs/guides/mcp.md): how to connect remote and stdio clients, and the tools.

## Phase 4, milestone 3 — The CLI, with a one-line install (2026-10-06)

### Added
- **`automately`, the CLI** (D-048, D-053):
  - `login`/`logout`/`whoami`
  - `run`, which follows a job, sends logs to stderr and the output to stdout, and exits with the outcome
  - `jobs`, `services` (`deploy`, `logs`, …), `domains`, `files` (`put`/`get`/`ls`/`rm`, and `sync` of a folder by ETag)
  - `tokens`, `tenants` (admin), `mcp` (the stdio bridge), `version`, `upgrade`
  - `--json` on any command
  - **Why:** Tony asked for a CLI that's easy to install, with no JDK needed on client machines.
- **Native binaries.** A `native` Maven profile (GraalVM). A static musl build of the Linux x64 binary was made and tested here: 50 MB, starts in 10 ms, and runs on Alpine, logs in, runs a script, and bridges MCP against a real node.
- **One-line install** (D-049): `install/install.sh` (`curl … | sh`) and `install/install.ps1` (`irm … | iex`). They detect the platform, **verify SHA-256**, install to a user directory and set up `PATH`.
- **`.github/workflows/release.yml`**, run only on a version tag. It builds Linux x64 (static) and arm64, macOS arm64 and Intel, and Windows x64, smoke-tests them, and publishes them with `SHA256SUMS` and both installers. It does nothing until a tag is pushed, and D-017 stands.
- **Tests:** `CliTest` runs every command in-process against a real node, and `InstallScriptTest` runs the real installer against a local release host, including a tampered checksum, and checks the PowerShell script parses.
- [`docs/guides/cli.md`](docs/guides/cli.md).

### Settled afterwards
- The release workflow may use GitHub Actions, and downloads come from GitHub Releases once the repo is public (D-055).

## Phase 4, milestone 2 — The OpenAPI contract, validation and rate limits (2026-10-06)

### Added
- **`openapi.yaml` (OpenAPI 3.1), the API's contract** (D-046), served at `GET /v1/openapi.json` without a token. It covers every operation, with its scope, request and response schemas, the error envelope, and the bearer scheme. **Why:** weak spot #4; the CLI and MCP tools are built from it next.
- **Request validation** (D-050). Requests are checked against their operation before any handler runs, and bad input gets 400 `invalid_request` naming where. JSON bodies are read once, after auth, with each operation's own limit.
- **Event streams of running jobs start with what was logged so far.** Log events now carry a `seq` number. A client connecting to a running job, on any node, first receives its recent lines (up to 1 000) from the node running it, then live events, each line exactly once. **Why:** `automately services logs` and the MCP tools need a running service's recent output, and the stream used to show only lines logged after connecting.
- **Rate limits per tenant** (D-051): `AUTOMATELY_RATE_LIMIT_PER_MINUTE` (default 1200, 0 = off), each node enforcing its share. Responses carry `RateLimit-Policy` and `RateLimit`, and over the limit is 429 with `Retry-After`. The admin isn't limited.
- **Tests:**
  - `ContractTest`: routes and operations must match exactly, every one of the 40 operations is called and its response validated, bad input is refused, and a form-typed JSON body (`curl -d`) still works
  - `RateLimitTest` and `RateLimiterTest`

### Changed
- `RouteScopes` has one rule per operation, naming its contract operation and body mode. The node won't start if a rule and the spec disagree.
- **Stricter input** where the spec says so: for example, `limit` above 1 000 is now 400 instead of being clamped, and unknown scopes or `onOrphan` values are refused by the contract.
- Bus messages may have no body (`null`), as before; the spec now says so.
- Tests run with rate limiting off, except `RateLimitTest`.

### Fixed
- **A 413 could arrive as a reset connection.** When a too-large upload was refused, the connection closed while bytes were still arriving. It now closes with a lingering close (D-052).
- The spec as first written said `cancelRequested` was always a boolean. `ContractTest` found that it can be `null`.

## Phase 4, milestone 1 — Tenants and scoped tokens (2026-10-06)

### Added
- **Bearer tokens on every `/v1` call** (D-045):
  - Tokens look like `atly_` + 43 base62 characters + a 6-character checksum. Only their SHA-256 is stored.
  - Each belongs to one tenant, carries scopes (`jobs:read`, `jobs:write`, `services:read`, `services:write`, `files:read`, `files:write`, `bus:publish`, `bus:request`, `tokens:write`, `nodes:read`, or `*`), may expire, and can be revoked with effect on the next request, on every node.
  - A missing token gets 401 with `WWW-Authenticate: Bearer`, and a missing scope gets 403 naming it.
  - **Why:** weak spot #7. The tenant header let anyone act as any tenant.
- **The admin token** (D-044) comes from `AUTOMATELY_ADMIN_TOKEN`, or is generated once by the first node and logged. It manages tenants and tokens only, and gets 403 on tenant data. `POST /v1/admin/rotate` replaces a generated one.
- **Tenants and tokens API:** `/v1/tenants` (admin: create, list, get, delete once empty), `/v1/tenants/{name}/tokens` (admin), `/v1/tokens` (a tenant's own; tokens can only mint narrower ones), and `/v1/whoami`.
- **`RouteScopes`**, one table saying what each route needs. It fails closed, so a route without a rule can't be reached.
- `AuthStore` with SharedData and Ignite implementations (`TENANTS`, `TOKENS`, `SETTINGS` tables) and a shared contract; `Authenticator`; `TokenFormat`.
- **Tests:**
  - `AuthTest` walks every route (each must answer 401 without a token), and covers scopes, admin separation, narrowing, revocation, tenant deletion, and bodies arriving intact after the async check
  - contract tests on both stores, `TokenFormatTest`, `AuthenticatorTest`
  - every existing test, and the Docker test, now authenticates with tokens (`TestAuth`)

### Changed
- **`X-Automately-Tenant` is gone.** The tenant comes from the token.
- `/v1/nodes` now needs `nodes:read` or the admin token.
- `docker-compose.yml` passes `AUTOMATELY_ADMIN_TOKEN` through, and the README's quick start mints a token.

### Known
- **An intermittent 500 on the first write to a freshly started three-container cluster.** It happened twice in about ten clean full runs: once uploading a file, once creating a service. It happens before any node is killed, and isn't tied to files or auth. It didn't reproduce in 8 later Docker runs, three of them from clean builds. `DockerClusterIT` now includes every container's ERROR lines, with stack traces, in its failure messages, so the next occurrence will show the cause.

## Phase 3b, milestone 3 follow-ups — kv in the quota, failover tests, the shutdown stall (2026-10-06)

### Changed
- **The tenant quota counts `kv` too** (D-043). `AUTOMATELY_TENANT_STORAGE_MB` now covers files and `kv` together. kv entries count as the bytes of their namespace, key and JSON value. Over the quota, `kv.set` and `kv.increment` fail with `limit_exceeded`, and uploads get 413. A full tenant can still update, shrink or delete its entries. **Why:** Tony asked for it; until now a tenant could fill the cluster through `kv` with no limit except cluster capacity.
- `FileStore.create` takes the most an upload may store; quotas moved out of the stores into `TenantQuota`.

### Fixed
- **SQL during rebalancing is retried.** Ignite rejects a query with "Failed to reserve partitions" or "please retry the query" while partitions are still moving, for example right after a fresh cluster activates. Such errors now count as transient (D-033) and are retried. **Why:** one full Docker run got a 500 on the first upload to a fresh cluster. That upload runs two `SUM` queries for the quota, and this is the likely cause. It didn't reproduce, so the Docker test now includes container logs in its failure message.
- The ArchUnit rule against mutable statics flagged a synthetic `$SWITCH_TABLE$` field that an IDE's Eclipse compiler had written into `target/classes`. Compiler-generated fields are now excluded.
- **A 30 s stall when a node stopped** (D-042). The stall was listed as known in milestone 3. The cause was a binary schema (Vert.x's `RemoveRegistrationProcessor`) first sent while a node was leaving. Nodes now register every schema they'll send at start, and table rows always use one field order. **Why:** it would have hit every rolling restart, not just tests.

### Added
- **`FileFailoverTest`** (3 nodes, one backup):
  - a 40 MB download outlives the death of a node holding its data, byte for byte
  - a download cut off with its serving node resumes on another node with `Range`, with the same ETag and identical bytes
  - an upload cut off with its node leaves the previous version intact and nothing else behind
- **`DockerClusterIT.fileDownloadsSurviveSigkillOfANode`:** with a real `SIGKILL`, a download served by the killed node resumes on another with `Range`, and a download from a surviving node, reading chunks the dead node held, loses nothing.
- **`BinarySchemaTest`:** after start-up, no binary schema is first seen, even when a node stops (D-042).
- `TenantQuotaTest`, a `kv` usage contract test (both stores), and quota tests for `kv` and files together in `NodeHostServicesTest` and `FilesTest`.

## Phase 3b, milestone 3 — Files (2026-10-06)

### Added
- **`files` for scripts:** `files.write(path, data, {contentType})`, where `data` is a string, bytes, JSON, an (async) iterable, a request body or a `fetch` response. Also `read`, `text`, `json`, `stream(path, {headers})`, `stat`, `list({prefix, limit, cursor})` and `delete`. A handler can return `files.stream(path)`, or `{headers, body: files.stream(path)}`. Java then serves it with `Range`, `ETag`/304, `HEAD` and `Content-Length`, and the bytes never enter the isolate (D-040). **Why:** Tony asked for services that can serve large files. Video seeking needs ranges and an exact length.
- **Files from outside:** `PUT /v1/files/{path}` (streamed upload, 201 with an `ETag`), `GET`/`HEAD /v1/files/{path}` (with ranges and 304), `GET /v1/files?prefix=&limit=&cursor=`, and `DELETE /v1/files/{path}`.
- **Storage:** 1 MB chunks in a replicated Ignite cache, metadata in an indexed `FILES` table, a new file id per write with an atomic swap, and a leader-run garbage collector (`FileGarbageVerticle`) with a 10-minute grace period for reads in progress (D-040). In non-clustered mode it uses SharedData maps.
- **Admission control (D-038):** writes that grow storage are refused with `insufficient_storage` (HTTP **507**) before the disk or memory fills, and the node keeps running. The per-tenant file quota is refused with 413. **Why:** a large upload into a small in-memory node halted the JVM; Tony: "i don't want to be able to crash the jvm with a fucking file upload".
- **Config:** `AUTOMATELY_TENANT_STORAGE_MB` (1024) and `AUTOMATELY_MIN_FREE_DISK_MB` (1024).
- **Tests:**
  - `FullRestartTest` now also uploads a 3 MB file, restarts every node, and downloads it byte for byte from another node
  - a `FileStore` contract (10 tests) run against SharedData and Ignite, including a replace while a read is in progress
  - `StorageCapacityTest`: an in-memory 128 MB node refuses an oversized upload and stays alive; a durable node refuses below the free-disk floor
  - `FilesTest` on a real two-node cluster: a 10 MB REST round trip (written on one node, read on the other), ranges/416/304/HEAD, list/delete/path validation, the quota, the script API, and a service storing a streamed 6 MB upload and serving it through the ingress with ranges

### Changed
- **Ignite:** 256 partitions instead of 1 024, a 128 MB minimum data region (validated at start), region metrics on, and page eviction as a last resort for in-memory regions (D-038).
- **Early answers drain the body** (up to 64 MB) instead of closing at once, so a refused upload reaches the client as a 413 or 507 rather than a reset (D-039, superseding part of D-037). The REST upload checks `Content-Length` against the quota before reading.
- A service's `content-length` header is kept when it's exact (files). Script-set `content-length` is still dropped.

### Fixed (found by the new tests)
- An in-memory region could run out (`IgniteOutOfMemoryException`) and halt the JVM, once with a large upload and once just from creating tables in 64 MB (D-038).
- Tests that called `Ignition.stopAll()` also stopped the shared contract-test node.
- **Stale reads across nodes:** Ignite's default `PRIMARY_SYNC` let a node read a backup copy before a write reached it, so a file just written on node A was sometimes "missing" on node B. Tables and file chunks now use `FULL_SYNC` (D-041).
- **An error replacing an unsent file response kept its `Content-Length`**, so the JSON error body overran it and broke the next response on the connection. Error answers now drop the replaced response's entity headers (REST API, ingress, and services).
- `DockerClusterIT` read a restarted service's checkpoint in the moment between the run showing `RUNNING` and the previous checkpoint being seeded. It now waits for the checkpoint first, as `ChaosTest` does.
- `FilesTest` read response bodies after the response had been handed to the test thread, so early chunks were sometimes lost and the test was flaky. Bodies are now read in the same future chain.

### Known
- Stopping a node while another is resolving a binary schema it just sent can stall the survivor's striped thread for up to 30 s (Ignite's metadata timeout). It was seen only during test teardown, and it recovers on its own.

## Phase 3b, milestone 2 — Streaming (2026-10-06)

### Added
- **Streaming HTTP for services.** The ingress now proxies each request **as a stream** to the serving node's new internal endpoint (`AUTOMATELY_INTERNAL_PORT`, protected by a cluster secret), with backpressure both ways. The 1 MB request and response limit is gone (D-036). **Why:** Tony asked for services that can serve large files and stream.
- **Script API:**
  - request bodies are async-iterable streams of `Uint8Array` chunks (`req.body`, plus `bytes()`)
  - responses can be a `Uint8Array`, an (async) iterable such as a generator for server-sent events, or `{status, headers, body}` with any of those
  - writes wait for the client
  - a client disconnecting ends the generator
- **Streaming `fetch`:** `res.body` streams, and `bytes()` was added. `text()` and `json()` buffer up to 5 MB, and the timeout applies to idle gaps rather than the whole download.
- `TextEncoder` and `TextDecoder` (UTF-8) for scripts.
- **Config:** `AUTOMATELY_INTERNAL_PORT` (8090), `AUTOMATELY_ADVERTISE_HOST` (this host's address), `AUTOMATELY_INGRESS_IDLE_TIMEOUT_MS` (60 000), `AUTOMATELY_MAX_REQUEST_BYTES` (1 GiB).
- **Core SPI:** `HttpExchange`, `BodyStream`, `FetchedResponse`, `HostServices.serve` and `HostServices.fetch`.
- **Tests:**
  - runtime: request bodies streamed in chunks, an unread body paused rather than buffered, generator responses waiting for a slow client, disconnect ending the generator, streaming and buffered fetch with its 5 MB cap, cancellation of an unread fetch body, read-once bodies, UTF-8 round trip
  - core: 20 MB streamed fetch, the concurrency limit released when bodies finish
  - `StreamingTest` through a real 2-node ingress: a 48 MB upload checksummed by the script, a 48 MB streamed download, SSE plus cleanup on disconnect, backpressure against a paused client (stops at about 22 MB of kernel socket buffers instead of 250 MB), idle timeout returning 504, 413 over the limit, and the internal endpoint refusing callers without the secret

### Fixed (found by the new tests)
- Backpressure counted chunks only once the isolate processed them, so a stalled script's queue could grow without bound (D-037).
- The ingress's idle timeout ignored request-body progress and cut off long uploads (D-037).
- An early 413 left the connection with an unread body, which hung the next request; such connections are now closed (D-037).
- An idle timeout was reported as 502 instead of 504 (D-037).

### Changed
- Removed: the event-bus HTTP forwarding (`ServeVerticle`, `Addresses.nodeHttp`). Node registry entries carry `endpoint`.

## Phase 3b, milestone 1 — The cluster is the database (2026-10-06)

### Added
- **Apache Ignite 2.18 replaces Hazelcast** as the cluster manager *and* the database (D-030). New module `automately-cluster-ignite`: `IgniteCluster` (bootstrap, durable data region, discovery, safe activation) and Ignite stores for jobs, job state, services, domains and `kv`. They use compare-and-set and indexed SQL, so a tenant's jobs, active jobs and `kv.list` no longer scan everything. **Why:** before this, a restart of every node lost all jobs, services, domains and `kv`. Tony asked for a database built into the cluster.
- **Durability:** `AUTOMATELY_DATA_DIR` (the Dockerfile sets `/var/lib/automately` as a volume, and compose has a volume per node), `AUTOMATELY_DATA_BACKUPS` (1), `AUTOMATELY_DATA_MEMORY_MB` (256), `AUTOMATELY_BASELINE_ADJUST_MS` (60 000).
- **Safe activation after restarts** (D-031), **replicated Vert.x maps** (D-032), **retries on topology changes** (D-033), and **`AUTOMATELY_CLUSTER_SIZE`**: a new durable cluster waits for its initial nodes before activating (D-035).
- **Retention** of finished jobs (`AUTOMATELY_JOB_RETENTION_DAYS`, default 7; D-034), and `GET /v1/jobs?limit=`.
- **Store contract tests** in core (`JobStoreContract`, `KvStoreContract`, `JobStateStoreContract`, `ServiceStoreContract`, `DomainStoreContract`), run against both the in-memory and the Ignite stores. Core publishes a test-jar for this.
- **Tests:**
  - `DurabilityTest`: a 2-node full restart with every store intact, and a 3-node cluster losing a node with no data lost.
  - `FullRestartTest`: every node of a real 3-node Automately cluster stopped and restarted from disk. Finished jobs, `kv` and domains survive, and the service runs again from its checkpoint.
  - `DockerClusterIT`: every container stopped and started (cold start) with the same checks.

### Changed
- `JobStore.list(tenant, limit)` and `deleteFinishedBefore`; `JobStateStore.delete`; `DomainStore` is now an interface (`SharedDataDomainStore` and `IgniteDomainStore`).
- The ArchUnit boundary now keeps **Ignite** (and any cluster-manager SPI) inside `automately.cluster.ignite`.
- Container JVM heap is 40 % (it was 50 %) to leave room for Ignite's off-heap data.
- `AUTOMATELY_CLUSTER_NAME` is gone (it was Hazelcast-specific). Separate clusters use separate ports or members.

### Removed
- Hazelcast (`vertx-hazelcast`, `Clustering`, `ClusteredCompareAndSetTest`).

### Fixed (found while switching)
- Activating on the first returning node made partitions held by other nodes "lost" (D-031).
- A restarting node racing Ignite's auto-activation made Ignite halt the JVM. First start versus restart is now decided from the data directory before Ignite starts (D-031).
- A fresh cluster activated by its first node alone had no replicas for early writes (D-035).
- A `SIGKILL` made the node registry unreadable ("partition has been lost") because Vert.x's caches had no backups (D-032).
- Failover made API reads fail with "Remote node has left topology" (D-033).
- Compare-and-set under heavy contention on one key exhausted its retries; it now backs off with jitter.

## Phase 3 — Host APIs and HTTP hosting (2026-10-06)

### Added
- **Host APIs for scripts** (D-025), each the successor of a 2017 host object (design: `docs/design/host-apis.md`; reference: `docs/scripting/host-apis.md`):
  - `kv`: get, set (with exact `ttlMs`), delete, list (prefix and cursor), atomic `increment`, and namespaces (D-027).
  - `bus`: publish, subscribe, request/handle, tenant-scoped, plus `POST /v1/bus/{topic}` and `/request` from outside.
  - `fetch`: WHATWG-style, with an SSRF-safe egress policy that refuses private and reserved addresses, connects to the checked address, and follows no redirects. Size, time and concurrency limits apply (D-026).
  - `log.{debug,info,warn,error}(message, fields)`: structured lines.
- **HTTP hosting:** `ctx.serve(handler)` in services. Every node runs an ingress (`AUTOMATELY_INGRESS_PORT`, default 8000) that routes by host to the service's current run, on any node (D-029). Every service gets an automatic host `<service>--<tenant>.<AUTOMATELY_INGRESS_DOMAIN>`, and custom domains can be added through `/v1/services/{name}/domains` with DNS TXT verification (D-028). Unknown hosts get 404; a service that's down gets 503 with `Retry-After`; slow handlers get 504; too many requests in flight get 429.
- **Mechanism:** the `HostServices` SPI in core; in the Javet runtime, `HostBridge`, asynchronous calls settled on the event loop, listeners, and cleanup when a run ends.
- **Config:** `AUTOMATELY_INGRESS_PORT`, `AUTOMATELY_INGRESS_DOMAIN`, `AUTOMATELY_EGRESS_ALLOW_PRIVATE`. Docker and compose expose the ingress.
- **Tests (115 → 167 unit, 3 → 4 Docker):**
  - `HostBridgeTest`: parallel calls, typed errors, listeners and replies, cleanup, limits, response normalisation.
  - `SharedDataKvStoreTest` and `NodeHostServicesTest`: tenant isolation, exact expiry, paging, 100 concurrent increments, bus isolation and errors, fetch, and egress refusals for 11 private or reserved targets.
  - `HostApisTest` on a real 2-node cluster: kv across nodes and tenants, a service answering a job and HTTP, pub/sub, fetch refused, ingress on both nodes and over HTTP/2, 404/503, custom domain verification, names.
  - `DockerClusterIT`: HTTP traffic moves to a survivor after the serving node is `SIGKILL`ed.

### Changed
- Tenant and service names can't contain `--`.
- `ScriptRuntime.execute` takes `HostServices`, and `NodeVerticle` takes a `HostServicesFactory`.

### Decided
- No isolate pooling (D-024): measured 6.6 ms p50 per job including isolate creation.

### Fixed (found by the new tests)
- A fetch over the size limit surfaced as a generic network error (the reset fired before the limit was recorded).
- Deleting a service left its verified custom domains routing to the next service with that name.
- The ingress read only the `Host` header; it now routes by authority, which is how HTTP/2 carries the host.

## Phase 2b — Long-running services (2026-10-06)

### Added
- **Services:** named, long-running scripts per tenant that the cluster keeps running (the 2017 "service jobs", made robust). `PUT/GET /v1/services/{name}`, `GET /v1/services`, `POST /v1/services/{name}/start|stop|restart`, `DELETE /v1/services/{name}`, with `restart: always` (default) or `on-failure`. Changing script, config or restart creates a new revision, which is rolled out by stopping the old run. **Why:** this was the main 2017 feature not yet carried over. Design: `docs/design/services.md`; script API: `docs/scripting/services.md`.
- **`ServiceSupervisor`** (leader-only, compare-and-set): records each run's exit, backs off on failures (1 s doubling to 60 s, reset after 60 s of uptime), stops disabled or outdated runs, and claims a run before submitting it so there are never two (D-021). A run whose job never appeared is given up after 10 s.
- **Service context:** `ctx.service` (`name`, `revision`, `run`, `config`, `previousRun`), and `ctx.checkpoint` carried from the previous run.
- **Runtime event loop** (D-019): `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`, `ctx.sleep()`, `ctx.stopRequested`, posted tasks, idle waiting for long-running executions, a timer error failing the run, and isolates on a dedicated executor sized to node capacity.
- **Job cancellation:** `POST /v1/jobs/{id}/cancel`, a `CANCELLED` status, and a durable `cancelRequested` honoured by the node's heartbeat check and by the reaper. Running jobs get a graceful stop with `AUTOMATELY_STOP_GRACE_MS` (D-020).
- `RunningExecution.stop(reason, grace)`, and a node control address for stopping a specific attempt.
- **Config:** `AUTOMATELY_STOP_GRACE_MS` (5000), `AUTOMATELY_MAX_SERVICES_PER_TENANT` (10).
- **Tests (76 → 115 unit, 2 → 3 Docker):**
  - runtime: timers, idle and stop, grace exceeded, cancel while idle, waiting for a stop, `ctx.service`
  - job cancel transitions
  - cancellation through the node, including a lost message and an orphan with a pending cancel
  - `ServiceSupervisorTest`: start, backoff, `on-failure`, stop and start, revision roll, one run at a time, node death including the leader, checkpoint carry-over, a lost claim
  - `ServicesTest` over HTTP on a real 2-node cluster
  - `ChaosTest` and `DockerClusterIT`: a service comes back on a survivor, with its checkpoint, after its node is stopped or `SIGKILL`ed

### Changed
- Stored logs keep the **last** 1 000 lines (D-023).
- Cancelled attempts report `CANCELLED` (updates D-016).
- `Job` transitions are built with an internal editor, and the job JSON gains `cancelRequested` and `service`.

### Fixed (found by the new tests)
- A job awaiting `ctx.stopRequested` failed instantly as "never settles"; it now waits, bounded by its timeout (D-022).

## Phase 2 — Orphaned jobs, attempts and resumable steps (2026-10-06)

### Added
- **Attempts.** A job now records `attempt`, its `attempts` history, `onOrphan` (`fail` by default, or `retry`/`resume`) and `maxAttempts`, plus a new terminal status, `ORPHANED`. **Why:** a dead node's jobs must never re-run silently (D-007).
- **Fenced, compare-and-set transitions.** `Job.started/finished/orphaned/requeued/rejected` reject any attempt or node instance that's no longer current, and `JobStore.update` applies them with `replaceIfPresent`. **Why:** a node that only *looked* dead can't overwrite its replacement (D-011). Compare-and-set on the clustered map was proven first (`ClusteredCompareAndSetTest`).
- **Node instance ids.** Every process start gets a new id; jobs and the registry use it. **Why:** a restarted node with the same name must not hide its old jobs (D-009). Verified in Docker with `docker kill` followed by `docker start`.
- **`JobStateStore`.** Checkpoints and step results, stored per attempt. A resuming attempt copies from the latest earlier attempt, skipping attempts that recorded nothing. Limits: 64 KB per value, 1 MB per job. **Why:** a zombie's writes land in a key nobody reads (D-010).
- **`OrphanReaper`.** After a grace period, it applies each job's policy: `ORPHANED`, or a new attempt dispatched through least-loaded placement. It also re-dispatches attempts no node picked up, under any policy, which is safe because a node records `RUNNING` before running any script.
- **The `ctx` API in the Javet runtime:** `attempt`, `policy`, `isRetry`, `isResume`, `previous`, `checkpoint`, `completedSteps`, `stepKey()`, `saveCheckpoint()`, and `step()` (memoised across resumes), plus the `SupersededError` and `StateLimitError` globals. Spec: `docs/scripting/job-context.md`.
- **Cancellable executions.** `ScriptRuntime.execute` returns a `RunningExecution` with `cancel()`. Nodes cancel attempts they no longer own on each heartbeat.
- **API:** `onOrphan` and `maxAttempts` on submit (validated), `GET /v1/jobs/{id}/steps`, SSE `attempt` events, and `end` sent only when the job is finished. Finished jobs replay their attempts.
- **Config:** `AUTOMATELY_HEARTBEAT_MS`, `AUTOMATELY_ORPHAN_GRACE_MS`, `AUTOMATELY_REQUEUE_AFTER_MS`.
- **Tests (34 → 76):**
  - `JobTest` (transitions)
  - `SharedDataJobStoreTest` (50 concurrent compare-and-set updates lose nothing)
  - `SharedDataJobStateStoreTest`
  - `OrphanHandlingTest`: `fail`, `retry`, `resume`, attempts exhausted, a zombie's result discarded, a zombie's state write rejected, the grace period honoured, a restarted node, a stranded queued job, events
  - `JobContextApi` in the runtime suite
  - `ChaosTest`: three real nodes, the node killed in the middle of a step

### Changed
- **Node liveness** is judged from `lastSeen` within 3 heartbeats; map TTLs are only for cleanup. **Why:** Hazelcast was measured expiring TTL entries up to about 1 s early, which made the registry flicker (D-013).
- **Reaper:** every node tracks suspects and only the leader acts. **Why:** leader changes were resetting the grace period, so orphans could wait forever (D-014).
- **Dispatch:** a failure now lists each node's refusal reason, or the registry's contents when no node had capacity.

### Fixed (found by the chaos test)
- Intermittent failures (about 1 run in 5): spurious "No node has free capacity", and orphaned jobs left `RUNNING` or `QUEUED`. The root causes were D-013 and D-014. After the fix the chaos test passed 12 of 12 runs, and the full build passed 3 of 3.
- Failed orphan and requeue updates were silently dropped. They're now logged.
- The reaper's sweep has a deadline, so a hung cluster call can't stop reaping.
- **The JVM crashed natively (`SIGSEGV`) when a node stopped while a script was running.** The isolate kept executing on a worker thread after Vert.x shut down, and the process died on exit. Tests still passed because they had already reported, but 29 `hs_err_pid*.log` files piled up. `NodeVerticle.stop()` now cancels running isolates and waits up to 10 s for them to exit. It leaves their attempts `RUNNING` so the reaper applies the `onOrphan` policy, the same as after a crash (D-015). Verified: 0 crash reports in 3 chaos runs, against 1 per run before. CI now fails if any crash report appears.

### Docs
- `docs/design/revival-plan.md`: the original plan, now in the repo with a status block.
- `docs/scripting/job-context.md` (implemented), `docs/design/orphaned-jobs.md` (accepted), `docs/design/phase-2-plan.md` (done, with what changed during implementation).

## [2.0.0-SNAPSHOT] — Phase 1 spike (2026-10-06)

A ground-up rewrite of Automately Core (2015–2017) on Vert.x 5 and V8. No 2017 code was copied. The old code was used to understand behaviour such as least-loaded placement, job lifecycle and hostname routing. See [DECISIONS.md](docs/DECISIONS.md) D-001.

### Added
- **Multi-module Maven build** (`automately-core`, `automately-runtime-javet`, `automately-server`) with a Maven wrapper (Maven 3.9.16), targeting JDK 21. **Why:** core can't depend on engines or transport, and the boundaries are enforced (D-006).
- **Job model** (`Job`, `JobStatus`): immutable records stored as JSON. **Why:** replaces Hazelcast `DataSerializable` objects, so data is portable and readable.
- **`JobStore` and `NodeRegistry` interfaces** on Vert.x `SharedData` `AsyncMap`. Nodes heartbeat with a TTL of 3× the interval. **Why:** keeps Hazelcast out of business code (D-004).
- **`JobScheduler`**: least-loaded placement, which tries the next node if one turns a job down because it's full. **Why:** keeps the original `execute_on_least_jobs` behaviour.
- **`NodeVerticle`**: one execution node with capacity limits, live log publishing, and all state confined to its event loop. **Why:** replaces the static `JobServer`, so several nodes can run in one test JVM.
- **`ScriptRuntime` SPI and `JavetScriptRuntime`**: one V8 isolate per job, ES-module scripts, a frozen `console`, wall-clock timeout, and a per-isolate heap limit. **Why:** replaces Nashorn (removed in JDK 15) and the experimental J2V8 factory with real isolation (D-002, D-003).
- **REST API** (`/v1/jobs`, `/v1/jobs/{id}`, `/v1/jobs/{id}/events` over SSE, `/v1/nodes`, `/health`) on the `vertx-web` `Router`, with one error envelope and input validation. **Why:** replaces hand-written regex routing.
- **Hazelcast clustering** in `automately.server.cluster.Clustering`, starting from Vert.x's default config, with TCP/IP join and phone-home disabled.
- **Packaging**: a shaded jar, a multi-stage Dockerfile (non-root user, glibc base), a 3-node `docker-compose.yml` with healthchecks, and GitHub Actions CI.
- **Tests (34)**: the scheduler with two nodes in one Vert.x instance, a hostile-script suite (infinite loops, a memory bomb, global and prototype leakage, host access), a 2-node Hazelcast cluster over HTTP, and ArchUnit rules.
- `LICENSE.md`: MPL-2.0, carried over from the original Automately Core.

### Fixed during the spike
- Javet "callback context not recycled" warnings. The console binding is now released explicitly after each job, because the prelude's closures keep it alive until then.
- Hazelcast rejected a port range in the member list (`127.0.0.1:15701-15705`). A bare host is now used, and Hazelcast probes upwards from `clusterPort`.
- The ArchUnit JUnit 5 engine silently discovered 0 tests. The rules now run as plain parameterised JUnit tests (D-006).
- Containers exited at startup with `Unrecognized option: --add-modules`. JVM flags moved from `JAVA_TOOL_OPTIONS` to `JDK_JAVA_OPTIONS`, which accepts launcher options.
- `POST /v1/jobs` returned 500 in Docker. `BodyHandler` tried to create `file-uploads/` in an unwritable working directory. Uploads are disabled (the API takes no files) and the image has a `WORKDIR`.
- The API's 500 errors were never logged. The failure handler now logs the method, path and cause. **Why:** this hid the bug above, and it's the "opaque operations" weak spot from 2017.
- The compose healthcheck stayed in "starting" because `/dev/tcp` is a bash feature and the check ran under `sh`. It now runs under `bash`.

### Known gaps
- ~~Jobs on a node that dies stay `RUNNING`.~~ Fixed in phase 2.
- The tenant comes from the `X-Automately-Tenant` header. It's a placeholder until phase 4 auth.
- ~~No Testcontainers yet.~~ Added: `DockerClusterIT`.
- The Javet native library is included for linux-x86_64 only.
