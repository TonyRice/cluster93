# Design and plan: the cluster is the database, streaming, and files (phase 3b)

**Status:** ✅ Done: milestones 1, 2 and 3 (2026-10-06) · **Date:** 2026-10-06
**Why now:** before phase 4, close the three gaps phase 3 left open. Tony asked for them directly ("shouldn't we have a central database…", "what if someone wants to deploy a service that can serve large files or offer streaming?"), and chose a database **built into the cluster** rather than an external one.

## Decisions taken with Tony
- Ports stay as they are, configurable (`C93_HTTP_PORT`, `C93_INGRESS_PORT`).
- **Apache Ignite 2.18 replaces Hazelcast** as both the Vert.x cluster manager and the database (D-030). It was chosen over Infinispan (weaker recovery after a full-cluster crash) and a home-grown Raft store (too much to build).
- Without a data directory, Cluster93 still runs fully in memory for development and tests, and warns loudly.
- Streaming request and response bodies, plus a `files` API.

## Spike results (before committing to Ignite)
- Ignite 2.18 runs on JDK 21 with the documented `--add-opens` flags.
- Compare-and-set (`replace(k, old, new)`) across two nodes: the winner succeeds and the stale write fails.
- With 1 000 entries on disk-backed storage, **stopping both nodes and restarting them** brought every entry back, including the compare-and-set update, in 1.6 s.

## Milestone 1: the cluster is the database
- **One Ignite per node**, started by Cluster93 and handed to `vertx-ignite`'s `IgniteClusterManager`, so messaging and storage share one cluster.
  - `consistentId` is the node id, so a node finds its own data on restart.
  - Discovery is TCP with the configured members.
  - The cluster activates on start, and the set of data-holding nodes adjusts automatically after `C93_BASELINE_ADJUST_MS` (default 60 s), so a node that's permanently gone is replaced by rebalancing.
- **Two memory regions.**
  - `c93`: durable. On disk under `C93_DATA_DIR` with a write-ahead log, sized by `C93_DATA_MEMORY_MB`. Holds jobs, job state, services, domains, `kv` and files.
  - `default`: in memory. Holds Vert.x's own maps, the node registry and other ephemeral state. Event-bus subscriptions must never come back from disk after a restart.
- **Replication.** `C93_DATA_BACKUPS` (default 1) extra copies of every piece of data on other nodes. With 1, any single node can fail. With N−1, every node holds everything.
- **Stores on Ignite.** `JobStore`, `JobStateStore`, `ServiceStore`, `DomainStore` and `KvStore` get Ignite implementations, using atomic compare-and-set for transitions and **indexed SQL** for listing, such as a tenant's jobs, active jobs, and `kv.list` by prefix and cursor. The old full-map scans go away. Ignite types stay in `cluster93-cluster-ignite`, enforced by ArchUnit.
- **Retention.** Finished jobs and their state are deleted after `C93_JOB_RETENTION_DAYS` (default 7) by the reaper leader, so the disk doesn't fill.
- **Dev mode.** With `C93_DATA_DIR` unset, the region isn't persisted; everything else is identical. Non-clustered mode (one plain Vert.x, used by core's unit tests) keeps the SharedData stores.
- Hazelcast is removed (`Clustering`, D-004 and D-013 are superseded where they're specific to Hazelcast).

## Milestone 2: streaming
- **Direct proxying.**
  - Every node runs an internal HTTP endpoint (`C93_INTERNAL_PORT`, default 8090) and advertises its address in the node registry.
  - The ingress forwards a request **as a stream** to the serving node: the request body is piped with backpressure and the response is streamed back. The event bus is no longer in the data path.
  - Internal requests carry a cluster secret that's generated once and stored in the cluster, so the internal port can't be called from outside even if it's reachable.
- **Streaming in scripts.**
  - `req.body` is an async-iterable stream of `Uint8Array` chunks. `req.text()`, `req.json()` and `req.bytes()` still work.
  - A handler can return `{status, headers, body}` where `body` is a string, an object (JSON), a `Uint8Array`, an **async iterable** (for example an async generator), or a file from `files.stream(path)`.
  - Chunks cross into the isolate as event-loop tasks. Java pauses the socket while the script falls behind, and the script's writes wait for the socket to drain, so memory stays bounded both ways.
- **Server-sent events and long responses.** Instead of a fixed 30 s timeout, there's an **idle** timeout (`C93_INGRESS_IDLE_TIMEOUT_MS`, default 60 s) between chunks.
- **Limits.** The request body limit becomes `C93_MAX_REQUEST_BYTES` (default 1 GiB), enforced while streaming. No limit on streamed responses.
- **`fetch` streaming.** `res.body` is an async-iterable stream; `text()` and `json()` stay (still capped at 5 MB when fully buffered).

## Milestone 3: files
- **`files` API.**
  - `files.write(path, data, {contentType})`, where `data` is a string, `Uint8Array`, async iterable, or a `fetch` response body
  - `files.read(path)` → `Uint8Array`
  - `files.text(path)`
  - `files.stream(path)`, returnable as a response body
  - `files.stat(path)`, `files.list({prefix, limit, cursor})`, `files.delete(path)`
- **Storage.** Files are split into 1 MB chunks in the replicated, durable store, so any node can stream any file and files survive node loss and full restarts. Each file has metadata (size, content type, SHA-256 ETag, chunk count) and a new id per write. The metadata swap is atomic (compare-and-set), so readers never see a half-written file, and old chunks are deleted afterwards.
- **Serving.** When a handler returns `files.stream(path)`, Java pipes chunks straight from the store to the socket; the bytes never pass through JavaScript. It supports `Range`/`206` (video seeking), `ETag`/`If-None-Match` (304), and `Content-Length`.
- **From outside.** `PUT /v1/files/{path}` (streamed upload), `GET /v1/files/{path}`, `GET /v1/files?prefix=`, `DELETE /v1/files/{path}`. The CLI in phase 4 will use these to deploy static assets.
- **Quota.** `C93_TENANT_STORAGE_MB` (default 1024) per tenant, for files and `kv` together (D-043).

## Tests to add
- **Milestone 1:**
  - store contract tests run against both SharedData and Ignite
  - indexed queries
  - retention
  - a **full-cluster restart** test in one JVM: three nodes stopped, restarted, and jobs, services, `kv` and domains intact, with services running again
  - a Docker version that stops and restarts every container (volumes for the data directories)
  - all existing cluster, chaos and Docker tests on Ignite
- **Milestone 2:**
  - 50 MB uploaded and echoed with bounded memory
  - SSE from a service through the ingress
  - backpressure with a slow consumer
  - idle timeout
  - client disconnect cleanup
  - streaming `fetch`
- **Milestone 3:**
  - write, read, stream and range
  - ETag/304
  - a large file
  - atomic replace while a read is in progress
  - quota
  - a node killed while a file is streaming
  - the files API from outside

## What changed during milestone 1
- **Activation** had to wait for every data node, or activate without some only when no more are missing than there are backups (D-031). The first version made partitions "lost" on restart, and a later one raced Ignite's own auto-activation, which halted the JVM. First start versus restart is now decided from the data directory.
- **Initial cluster size** (D-035): a fresh durable cluster waits for `C93_CLUSTER_SIZE` nodes, so early writes are replicated.
- **Vert.x's own caches** needed explicit replicated, loss-ignoring templates (D-032). Ignite's default of zero backups made the node registry unreadable after a `SIGKILL`.
- **Transient topology errors** during failover are retried (D-033).
- **Compare-and-set backoff:** jittered pauses between retries. Immediate retries kept colliding with 50 to 100 writers on one key.
- **Store contracts:** the existing in-memory tests became shared contracts, run against both implementations.

## What changed during milestone 2
- **Backpressure is counted on arrival**, not when the isolate processes a chunk (D-037). The first version let a stalled script's queue grow.
- **The ingress has its own two-way idle watchdog.** Vert.x's request idle timeout ignores upload progress.
- **Early answers close the connection.** A 413 sent without reading the body broke the next request on that connection.
- **`fetch` binary request bodies** aren't supported yet (request bodies are strings or JSON). Response bodies stream as binary.
- **Measured:** a 48 MB upload through the single-node ingress took 292 ms. With a paused client, about 22 MB ended up in kernel socket buffers on loopback (two hops), against 250 MB without backpressure.

## What changed during milestone 3
- **Admission control** (D-038). A large upload into a small in-memory node halted the JVM. Writes that grow storage are now checked first (free disk on durable nodes, region fill in memory) and refused with 507. In-memory regions also evict as a last resort, partitions went from 1 024 to 256, and the data region must be at least 128 MB. A temp-file staging area was considered and rejected: uploads already stream, and durable storage is already disk.
- **The quota first covered files only.** `kv` was added in a follow-up (D-043): one `TenantQuota` counts both, uploads check a fresh count, and kv writes check a count at most 2 s old, rechecked fresh before refusing.
- **Early answers drain** the body up to 64 MB rather than closing straight away (D-039), so clients actually see a 413 or 507.
- **Metadata swap.** It's a plain put that returns the previous row, not a compare-and-set: the new file id makes the write itself unambiguous, and last writer wins, as with `kv.set`.
- **`FULL_SYNC` writes** (D-041): the default `PRIMARY_SYNC` allowed a read on another node to miss a write that had just completed.
- **Files survive a full restart**, tested in `FullRestartTest`.
- **Nodes dying mid-stream are tested** (`FileFailoverTest`, and `DockerClusterIT` with a real `SIGKILL`): downloads outlive a data node, a cut-off download resumes elsewhere with `Range`, and a cut-off upload leaves the previous version intact.
- **A 30 s stall when a node stopped** came from a binary schema first sent on the way out. Every schema is now registered at start (D-042).

