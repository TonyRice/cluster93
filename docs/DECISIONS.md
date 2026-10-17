# Decision log

Each entry records a choice, the reasons for it, and what it costs. Entries are never deleted. If a decision changes, add a new entry that supersedes the old one.

---

### D-001 — Rewrite on Vert.x 5 instead of reviving jsync.io
**Date:** 2026-10-06 · **Status:** Accepted

jsync.io was a renamed fork of Vert.x 2 core. The only original part was the `io.jsync.app` cluster layer. Its source still exists, so it doesn't need restoring or decompiling. Upstream Vert.x 5 replaces everything else in it.

**Decision:** Start a new repo and port Cluster93 Core's *ideas* (scheduling, runtimes, hosting) onto Vert.x 5. Don't copy 2017 code.
**Cost:** Every feature has to be rebuilt. Legacy runtimes (PHP, Dart, Docker), SFTP and Azure are dropped deliberately.

### D-002 — Javet V8, one isolate per job
**Date:** 2026-10-06 · **Status:** Accepted

Nashorn was removed in JDK 15, and J2V8 is unmaintained. Javet is maintained, embeds V8 and Node.js, and supports terminating a running isolate from another thread.

**Decision:** Each job gets a fresh isolate, which is closed afterwards. The only host object is a frozen `console`. The host binding is hidden from the global scope by a prelude.
**Cost:** A cold isolate is created per job, which costs a few milliseconds. Pooling is planned for phase 3. The native library is per platform; only linux-x86_64 is included.

### D-003 — Heap limit through V8's near-heap-limit callback
**Date:** 2026-10-06 · **Status:** Accepted

When an isolate runs out of memory, V8 aborts the **whole process**. Javet doesn't offer a per-isolate heap setting, but it does offer a process-wide `--max-heap-size` and a per-runtime near-heap-limit callback.

**Decision:** Set the max heap once at startup from `C93_MAX_HEAP_MB`. In the callback, terminate the script and grant 32 MB of headroom so V8 can unwind. Report the job as `FAILED: memory limit exceeded`.
**Cost:** The limit is the same for every isolate in a JVM, and the first runtime constructed sets it. Per-tenant limits would need separate runner processes.

### D-004 — Vert.x SharedData in front of Hazelcast
**Date:** 2026-10-06 · **Status:** Accepted

In 2017, 39 files used Hazelcast types directly. Newer Hazelcast versions also moved locks and latches (the CP subsystem) to the Enterprise edition.

**Decision:** Business code uses `JobStore`/`NodeRegistry` on `AsyncMap`. Hazelcast appears only in `cluster93.server.cluster`, and an ArchUnit rule enforces that.
**Cost:** No Hazelcast queries or predicates. Listing jobs scans the whole map, which is fine for the spike; tenant-indexed queries will need a real store such as Postgres.

### D-005 — Tenant header as a temporary stand-in for auth
**Date:** 2026-10-06 · **Status:** Temporary, to be replaced in phase 4

**Decision:** The `X-Cluster93-Tenant` header scopes every job request, so tenant isolation can be tested now.
**Cost:** It's not authentication. Nodes must not be exposed publicly until scoped tokens arrive.

### D-006 — Architecture rules as plain JUnit tests
**Date:** 2026-10-06 · **Status:** Accepted

The `archunit-junit5` engine discovered 0 tests alongside JUnit 5.14, which Vert.x 5.2 pins, without any error.

**Decision:** Use the core `archunit` library and check the rules from a parameterised JUnit test. The rules are: no Hazelcast outside the cluster package, static fields must be final, no hand-rolled threads or `Executors` (except `Main`'s shutdown hook), and core doesn't depend on engines or the web layer.
**Cost:** None significant. Adding a rule means adding it to the list.

### D-007 — Orphaned jobs are never re-run silently
**Date:** 2026-10-06 · **Status:** Accepted. Tony confirmed `fail` as the default and asked for the full resume API to be documented ([scripting/job-context.md](scripting/job-context.md))

**Decision:** The default `onOrphan` policy is `fail` (new terminal status `ORPHANED`). `retry` and `resume` are opt-in. Scripts see `ctx.attempt` and `ctx.previous`, and can use `ctx.step()` and checkpoints to resume without repeating completed work. Epoch fencing rejects writes from zombie attempts. Full design: [design/orphaned-jobs.md](design/orphaned-jobs.md).
**Cost:** More state per job, a reaper with leader election, and an API surface for scripts to learn.

### D-008 — Authorship
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** The author and organisation for Cluster93 is **Adam & Lynn Incorporated** (tony@adamnlynn.com). It's recorded in the POM, the README and the repository's git config.

### D-009 — Node incarnations (instance ids)
**Date:** 2026-10-06 · **Status:** Accepted (phase 2)

A crashed container restarts with the same node name. Its old jobs would then look owned by a live node and never be reaped.
**Decision:** Every process start gets a random `instanceId`. Jobs record `nodeInstance`, and liveness is checked by instance.
**Cost:** One more field per job and per registry entry.

### D-010 — Job state stored per attempt and copied on resume
**Date:** 2026-10-06 · **Status:** Accepted (phase 2)

**Decision:** Checkpoints and step results are stored under `jobId#attempt`. A resuming attempt copies the previous attempt's state into its own key before running.
**Why:** A zombie attempt can only write to its own key, which nobody reads any more. Fencing state writes this way needs no locks.
**Cost:** Steps a zombie completes *after* the takeover are lost to the new attempt and will run again. Idempotency keys (`ctx.stepKey`) cover this.

### D-011 — Compare-and-set for every job transition
**Date:** 2026-10-06 · **Status:** Accepted (phase 2)

**Decision:** `JobStore.update(id, fn)` reads the record, applies a pure transition and writes it with `AsyncMap.replaceIfPresent`, retrying on conflict. Transitions check the attempt number, so a stale attempt can't finish or orphan a job.
**Cost:** It relies on `replaceIfPresent` working on the clustered map with `JsonObject` values. This is proven by a test before anything else is built on it.

### D-012 — Reaper elected deterministically, with no lock
**Date:** 2026-10-06 · **Status:** Accepted (phase 2)

**Decision:** The live node with the lowest instance id runs the orphan reaper. If two nodes overlap briefly, compare-and-set means only one transition wins.
**Why:** Cluster locks may rely on Hazelcast's CP subsystem (Enterprise only in newer releases). Correctness comes from compare-and-set, not from mutual exclusion.
**Cost:** Up to one heartbeat of duplicated scanning while the cluster membership changes.

### D-013 — Node liveness from `lastSeen`; map TTLs only for cleanup
**Date:** 2026-10-06 · **Status:** Accepted. Supersedes the TTL-based liveness in D-004 and phase 1

**Evidence:** a probe against the clustered map found Hazelcast expiring entries up to about 1 s early. A 600 ms TTL entry was gone after 2–584 ms; a 3 s entry after 2.0–2.7 s. With a 1 s heartbeat and a 3 s TTL, a node could vanish from the registry after a single late heartbeat. In tests the registry visibly flickered, causing spurious "No node has free capacity" errors and stalled reaping.
**Decision:** A node is live while `now - lastSeen <= 3 × heartbeat`, using the timestamp the node wrote itself. Entries keep a TTL of window + 1 minute, purely for cleanup.
**Cost:** Liveness now depends on node clocks being roughly in sync (NTP). Clock skew larger than the window would misjudge nodes. That's acceptable for a cluster on one network, and is noted for multi-region setups.

### D-014 — Every reaper tracks suspects; only the leader acts
**Date:** 2026-10-06 · **Status:** Accepted. Amends D-012

**Decision:** All nodes keep their own "first seen missing" timers for suspect jobs. The leader-only rule applies to *acting* (orphaning or requeueing), not to tracking.
**Why:** In D-012 as first built, a node cleared its suspects whenever it wasn't the leader. When leadership flipped, the grace period restarted on every flip and orphaned jobs could wait indefinitely (seen in the chaos test).
**Cost:** Every node scans active jobs each heartbeat. That's fine at current scale; an index of running jobs per node would remove the scan later.

### D-015 — A stopping node cancels its scripts and leaves them to the reaper
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** stopping a node while a script ran crashed the JVM on exit (`SIGSEGV` in V8). This was reproduced on every chaos run; plain cluster runs didn't crash.
**Decision:** `stop()` cancels running isolates and waits up to 10 s for them to exit. It doesn't record those attempts as failed; they stay `RUNNING` on an instance that's gone, and the reaper applies `onOrphan`.
**Why:** A graceful stop and a crash follow one code path, and a job's policy is honoured either way. Recording them as `FAILED` would bypass `retry`/`resume`.
**Cost:** Jobs on a node that's stopping take a grace period to move. A faster handover (the stopping node orphaning its own attempts) could come later.

### D-016 — No separate "superseded" status
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** The phase 2 plan had an internal `SUPERSEDED` outcome; it was left out. A cancelled zombie attempt reports `FAILED: Cancelled: …`, and its "finished" write is rejected by the attempt check, so the job never sees it. The attempt is already recorded as `ORPHANED` in the job's history, and the node logs a warning.
**Why:** A status that clients never see adds states without adding information.
**Follow-up:** Phase 5 should count discarded late results as a metric (`c93_attempt_results_discarded_total`), which is where this information is actually useful.

### D-017 — No CI for now
**Date:** 2026-10-06 · **Status:** Accepted (Tony)

**Decision:** The GitHub Actions workflow is removed. The repo is private and worked on locally, and `./mvnw verify` (unit tests plus `DockerClusterIT`) is the gate. The crash-report check that CI ran is now a manual step: `find . -name 'hs_err_pid*.log'`.
**Cost:** Nothing runs automatically on push. Bring the workflow back from git history (commit `31ce7c7`) when the repo gets collaborators or goes public.

### D-018 — Name and rights
**Date:** 2026-10-06 · **Status:** Recorded, as stated by Tony

The acquired product was renamed **Executory** after the acquisition. Tony was given the rights to the pre-acquisition open-source Cluster93 code, so using the Cluster93 name and building on that code here is fine and doesn't overlap with what the product became. This closes the question raised in the revival plan.

### D-019 — An event loop per isolate, on a dedicated executor
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Each execution runs an event loop on its isolate's thread. It pumps microtasks, fires timers (`setTimeout`/`setInterval`, at most 10 000 pending), and runs tasks posted from other threads (stop requests now, incoming requests and messages in phase 3). Kills and timeouts wake an idle loop. Isolates run on a Vert.x shared worker executor (`cluster93-isolates`) sized to the node's capacity, with execute-time warnings disabled.
**Why:** Services need to stay alive and react over time, and phase 3's handlers need the same loop. A long-running isolate on the default worker pool would trigger blocked-thread warnings and take threads from other blocking work.
**Cost:** One thread per running isolate. Isolates are thread-affine, so virtual threads aren't an obvious substitute.

### D-020 — Durable cancellation and a `CANCELLED` status
**Date:** 2026-10-06 · **Status:** Accepted. Updates D-016

**Decision:** A cancel is stored on the job (`cancelRequested`) with compare-and-set, then sent to the node as an optimisation. The node also checks the flag on every heartbeat, and the reaper ends an orphaned job with a pending cancel as `CANCELLED` instead of retrying it. Running jobs get a graceful stop: `ctx.stopRequested` resolves, then after `C93_STOP_GRACE_MS` (default 5 s) the isolate is terminated. Cancelled attempts, including zombies cancelled by their own node, report `CANCELLED` rather than `FAILED` (that part of D-016 is superseded; their results are still discarded).
**Why:** A cancel must never be lost and must never turn into a retry.

### D-021 — Services are supervised jobs, claimed before they're submitted
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** A service is a definition (desired state). Each run is an ordinary job (`onOrphan: fail`, no timeout) with a `service` reference. A leader-only `ServiceSupervisor` reconciles: it records exits (with exponential backoff on failure, reset after 60 s of uptime), stops outdated or disabled runs, and starts missing ones by **writing the new job id into the service with compare-and-set before submitting the job**. Checkpoints carry from one run to the next; steps don't.
**Why:** Reusing jobs keeps placement, fencing, logs, `/steps` and the orphan reaper in one place. Claiming first means overlapping supervisors can't start two runs.
**Cost:** Definitions live in the cluster map, so a full-cluster restart loses them (2017 autostarted from a persistent map). The `ServiceStore` interface is ready for a durable store.

### D-022 — Looking at `ctx.stopRequested` counts as waiting
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** A job whose promise is pending with nothing scheduled still fails fast ("never settles"), unless the script has accessed `ctx.stopRequested`. In that case it waits, bounded by its timeout, because a cancel can resolve it.
**Why:** Found by ServicesTest: `await ctx.stopRequested` in a job was failed instantly instead of waiting to be cancelled.

### D-023 — Stored logs keep the last 1 000 lines
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** The per-attempt log buffer is a ring buffer of the last 1 000 lines, not the first 1 000.
**Why:** For a service that runs for days and then crashes, the end of the log is what matters.

### D-024 — No isolate pooling
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** a trivial job end to end, including creating and closing its V8 isolate, measured **6.6 ms p50, 9.9 ms p90, 15.8 ms p99**. Only the first isolate in a process is slow (about 390 ms while the native library loads).
**Decision:** Keep one fresh isolate per attempt. HTTP requests are served by a service's long-lived isolate, so they never pay the cost anyway.
**Why:** Reusing isolates would trade isolation between jobs for a few milliseconds. Revisit only if phase 5 benchmarks say otherwise.

### D-025 — Asynchronous, tenant-scoped host services
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Scripts reach storage, messaging and networking through one SPI, `HostServices` (`call` and `listen`). The node builds one per execution, already bound to the job's tenant. In the isolate, every call returns a promise. The Java side runs on Vert.x and posts the result back to the event loop (D-019), so calls run in parallel. Pending calls and listeners keep a job from being declared "never settles".
**Limits:** at most 256 calls in flight and 100 listeners per isolate. `ctx.step` and `saveCheckpoint` stay synchronous because they must be durable before the script continues.

### D-026 — Egress: connect to the address you checked, follow no redirects
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** `fetch` resolves the host, refuses private and reserved addresses (loopback, RFC 1918, link-local including cloud metadata, CGNAT, unique-local IPv6, IPv4-mapped forms), and connects **to that resolved address** with the original Host header and SNI (Vert.x `RequestOptions.setServer`). Redirects are never followed.
**Why:** Checking a name and then letting the client resolve it again allows DNS rebinding, and following a redirect allows bouncing into an internal address. Both are classic SSRF routes in a multi-tenant sandbox.
**Cost:** Scripts must follow redirects themselves (and each hop is checked). `C93_EGRESS_ALLOW_PRIVATE=true` exists for local development only.

### D-027 — `kv` expiry is decided by the entry, not the map
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Each entry stores `expiresAt`, and every read checks it. The map TTL is set a few seconds longer, purely for cleanup.
**Why:** Hazelcast expires entries up to about 1 s early (D-013), which would break short TTLs.
**Cost:** `list` scans the map, and there are no queries (2017's DataBus had predicates). Both are waiting for the durable store.

### D-028 — Automatic hosts and verified custom domains
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Every service is reachable at `<service>--<tenant>.<C93_INGRESS_DOMAIN>`, so tenant and service names may no longer contain `--`. Custom domains are claimed per service and route only after a DNS TXT record `_cluster93.<domain>` = `cluster93-verify=<token>` is found (2017's anti-hijacking check). Unverified claims can be taken over after 24 h, and deleting a service releases its domains.
**Why:** A single DNS label per service works with one wildcard certificate later. Verification stops one tenant capturing another's traffic.

### D-029 — Ingress forwards over the event bus, routing by authority
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Every node runs an ingress (`C93_INGRESS_PORT`). It routes by the request authority (the Host header on HTTP/1.1, `:authority` on HTTP/2), finds the service's current run, and forwards the request to that node over the event bus. The node hands it to the isolate's `ctx.serve` handler.
**Cost:** Bodies are limited to 1 MB each way (they travel as event-bus messages) and there's no streaming. That's acceptable for APIs and webhooks; large uploads and downloads would need a direct proxy later.

### D-030 — The cluster is the database: Apache Ignite replaces Hazelcast
**Date:** 2026-10-06 · **Status:** Accepted (Tony chose a built-in distributed database over an external one, and Ignite over Infinispan or a home-grown Raft store). Supersedes the Hazelcast parts of D-004 and D-013

**Decision:** Each node runs one embedded Apache Ignite 2.18, used both as the Vert.x cluster manager (`vertx-ignite`) and as Cluster93's durable, replicated store.
- Jobs, job state, services, domains and `kv` live in tables in a durable data region, on disk under `C93_DATA_DIR` with a write-ahead log.
- Each table keeps `C93_DATA_BACKUPS` copies on other nodes, uses compare-and-set transitions, and has SQL indexes (Calcite engine) for listings.
- Ignite types stay in `cluster93-cluster-ignite`, enforced by ArchUnit; core still sees only its store interfaces, which now have shared contract tests run against both implementations.
- With no data directory, Ignite keeps everything in memory (development); a single non-clustered node still uses the in-memory SharedData stores.

**Evidence:** a spike and tests showed compare-and-set across nodes; a two-node and a three-node **full-cluster restart from disk** with everything intact (`DurabilityTest`, `FullRestartTest`, and `DockerClusterIT` stopping and starting every container); and losing a node with no data lost (1 backup).
**Why not Ignite 3:** there's no Vert.x cluster manager for it yet, and 2.18 is the mature line. The store interfaces keep a later move open.
**Cost:** a much larger jar (Ignite and Calcite), extra JVM flags (`ignite.jvm.args`, also in the Dockerfile), and memory for Ignite's off-heap regions (the container heap is now 40 %).

### D-031 — Activate only when no data can be lost
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** the first version forced the cluster active as soon as the first node restarted. Ignite then treated partitions held by not-yet-returned nodes as lost ("all partition owners have left").
**Decision:**
- Whether a node is starting for the **first time** is decided **before** Ignite starts, from whether its data directory already holds a database.
- Only a first start may activate the cluster, and a brand-new durable cluster waits for `C93_CLUSTER_SIZE` nodes first (D-035).
- A restarting node never activates the cluster itself. It waits until every baseline (data-holding) node is back, and Ignite activates by itself.
- If some never return, the cluster activates without them after `C93_BASELINE_ADJUST_MS`, but **only if no more are missing than there are backups**; otherwise it keeps waiting and logs why. Baseline auto-adjust (on since the first start) then drops the missing nodes.

**Also found:**
- A restarting node that saw no baseline yet called "activate" while Ignite was already auto-activating. The interrupted write-ahead-log restore made Ignite **halt the JVM** (its protective failure handler).
- Ignite forbids setting the baseline by hand while auto-adjust is on, and forbids changing it on an inactive cluster.

### D-032 — Vert.x's maps are replicated and ignore partition loss
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** after a `SIGKILL` in Docker, every read of the node registry failed with "partition has been lost" and scheduling stopped. Caches Vert.x creates by name had Ignite's default of **zero backups**.
**Decision:** Cluster93 supplies the cache templates `vertx-ignite` normally ships, but stricter. `__vertx.*` and every other name-created cache are REPLICATED, in memory, FULL_SYNC, with partition loss IGNORE.
**Why:** that data (event-bus subscriptions, node info, the node registry) is small and re-announced every heartbeat; staying available matters more than any single entry. Durable tables configure themselves explicitly.

### D-033 — Retry operations that fail only because the topology changed
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** during failover, Ignite queries failed with "Remote node has left topology", and the API returned 500 mid-failover.
**Decision:** Ignite table operations retry up to 10 times with growing pauses when the failure is a topology change, and the node registry retries its reads. These are reads, idempotent puts, or compare-and-set, which re-reads; anything else fails at once.

### D-034 — Retention of finished jobs
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** The leader deletes finished jobs older than `C93_JOB_RETENTION_DAYS` (default 7), together with their checkpoints and steps, in batches every 10 minutes. `GET /v1/jobs` gained `?limit` (default 100, at most 1 000).
**Why:** with durable storage, history would otherwise grow until the disk fills.

### D-035 — A new durable cluster waits for its initial size before activating
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** on a fresh three-node durable cluster, the first node to start activated alone, so the baseline was one node. Writes in the first seconds had no replicas, and stopping a node in that window lost data ("all partition owners have left").
**Decision:** `C93_CLUSTER_SIZE` (default 1), similar to Consul's `bootstrap_expect`. A **brand-new durable** cluster activates only once that many server nodes are present, so its baseline, and therefore replication, includes them from the first write. It has no effect on restarts (D-031) or on in-memory clusters. The compose file and the Docker test set it to 3.

### D-036 — The ingress proxies to the serving node over HTTP; the event bus is out of the data path
**Date:** 2026-10-06 · **Status:** Accepted. Supersedes D-029's forwarding (and its 1 MB cap)

**Decision:** Every node runs an internal HTTP endpoint (`C93_INTERNAL_PORT`, default 8090) and advertises it (`C93_ADVERTISE_HOST:port`) in the node registry. The ingress proxies each request as a stream to the node running the service: the request body is forwarded with backpressure and the response body is streamed back, with no size cap on responses. Internal requests must carry a cluster secret, generated once and agreed through shared data, so the port is useless to anyone outside the cluster.
**Why:** Large files, uploads and server-sent events can't go through single event-bus messages.
**Cost:** One more port per node, which must be reachable between nodes. HTTP/2 between ingress and node isn't used yet.

### D-037 — Streaming rules: backpressure on arrival, idle timeout in both directions, close after early answers
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence (from the new tests):**
- Counting unconsumed chunks when the isolate processed them let a stalled script's task queue grow without bound; 389 chunks arrived where 8 were allowed.
- Vert.x's request idle timeout only watches the response, so it cut off uploads the service was still reading.
- Answering 413 without reading the body left the connection broken for the next request.
- Resetting the upstream before answering turned a 504 into a 502.

**Decision:**
- Chunks are counted **when they arrive**, and the source pauses at 8 unconsumed chunks and resumes at 2.
- The ingress has its own idle watchdog (`C93_INGRESS_IDLE_TIMEOUT_MS`, default 60 s) that counts bytes moving in **either** direction.
- An early answer sends `Connection: close` and closes the connection.
- Errors are answered before the upstream is reset.
- Request bodies are capped by `C93_MAX_REQUEST_BYTES` (default 1 GiB), checked from `Content-Length` and while streaming.
- In scripts, response writes wait for the client to drain, and a client disconnecting ends the generator.

### D-038 — Storage admission control: refuse writes before memory or disk runs out
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** an in-memory node with a small data region took a large upload, hit `IgniteOutOfMemoryException`, and Ignite's failure handler **halted the JVM**. A 64 MB region ran out while just creating the tables, because each partition carries fixed metadata. Tony: "i don't want to be able to crash the jvm with a fucking file upload".
**Decision:**
- **Admission control.** A `StorageCapacity` check runs before every write that grows storage: each file creation, each 1 MB file chunk, and `kv.set`/`kv.increment`. When it fails, the write is refused with `insufficient_storage` (HTTP 507, or a script error), the partial upload is discarded, and the node keeps running.
  - Durable nodes: refused when the data directory's disk has less than `C93_MIN_FREE_DISK_MB` (default 1024) free. Ignite keeps a bounded hot set in memory and pages to disk, so disk is the real limit.
  - In-memory Ignite nodes: refused when the data region is more than 80 % full.
  - Non-clustered nodes (SharedData stores, on the heap): refused when the heap is more than 80 % full.
- **A safety net behind it.** In-memory Ignite regions use page eviction (`RANDOM_2_LRU` at 90 %), so a burst that gets past the check costs evicted entries rather than the process. This is a last resort, and it can drop data, so the node warns about it at start. Durable regions don't need it.
- **Partitions: 256** instead of Ignite's 1 024, and a **minimum data region of 128 MB**, checked at start-up with a clear error. Small regions fit the fixed per-partition overhead, and 256 is plenty below about 100 nodes.
- **Per-tenant quota.** `C93_TENANT_STORAGE_MB` (default 1024) caps a tenant's files. It is checked up front from `Content-Length` when the size is known, and while streaming otherwise.
**Why not stage uploads in a temp file?** Uploads already stream in 1 MB chunks with backpressure, so memory per upload is bounded. Durable storage is already disk-backed, so a temp file would only add a second copy and a second disk to fill. What actually crashed the node was running out of storage, and refusing before that point fixes it in every mode.
**Cost:** the check is a point-in-time estimate, so concurrent uploads can overshoot by about 1 MB each. That headroom is what the 80 % and 90 % thresholds leave. `kv` doesn't count against the tenant quota yet, only against cluster capacity.

### D-039 — Early answers drain the request body instead of closing at once
**Date:** 2026-10-06 · **Status:** Accepted. Supersedes D-037's "an early answer closes the connection"

**Evidence:** a refused upload (413 over quota) closed the connection while the client was still sending, so the client saw "Connection was closed" instead of the 413 and the reason for it.
**Decision:** When the server answers before reading the body (413, 404 for an unknown host, 507), it reads and discards the rest of the body ("lingering close") so the client receives the answer, and the connection stays usable. A body over 64 MB, by `Content-Length` or as counted, isn't worth the bandwidth, so the server sends `Connection: close` and closes. The REST upload also checks a declared `Content-Length` against the quota **before** reading anything, so a well-behaved client gets its 413 immediately.

### D-040 — Files: chunked in the cluster store, a new id per write, Java serves the bytes
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:**
- **Chunks.** A file is split into 1 MB chunks stored in a replicated cache (`FILE_CHUNKS`, key `fileId:index`), with its metadata row in `FILES` (size, content type, SHA-256 ETag, chunk count). Files are replicated like everything else (`C93_DATA_BACKUPS`), survive full restarts on durable nodes, and any node can stream any file.
- **Replacing is atomic.** Each write gets a new file id. Chunks are written first, then the metadata row is swapped in one put, so readers see the old file or the new one, never a mix.
- **Garbage collection.** A file id that is no longer current is recorded in `FILE_GARBAGE` with a deletion time: 10 minutes after it was replaced or deleted (so reads already in progress finish), or 6 hours after an upload that never finished started. The leader deletes due chunks every minute.
- **Serving.** `files.stream(path)` returned from a handler is served by Java (`FileResponder`): chunks go from the store to the socket and never enter the isolate. It supports `Range` (single ranges → 206, 416), `ETag`/`If-None-Match` → 304, `HEAD`, and an exact `Content-Length`, so video seeking works.
- **Paths.** Relative, at most 1 024 bytes, no `.`/`..`/empty segments, no backslashes or control characters. Tenant-scoped like `kv`.
**Why not a blob store such as S3 or MinIO?** The goal is "the cluster is the database", with nothing else to run. An S3-compatible `FileStore` can be added behind the same interface later.
**Cost:** Without a data directory, files live in memory and count against the data region. Large media libraries want durable nodes.

### D-041 — Durable tables and file chunks use FULL_SYNC writes
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** in one full-verify run, node A wrote a file and node B got "file data is missing" reading it straight away. Ignite's default `PRIMARY_SYNC` completes a write once the primary copy has it, and reads may be served from a backup, so a read on another node can come before the backup is updated.
**Decision:** Every Cluster93 table and the `FILE_CHUNKS` cache use `FULL_SYNC`: a write completes only when every copy has it. Vert.x's own caches already did (D-032).
**Cost:** a write waits for the slowest copy. That is the right trade for a database people expect to read their writes from on any node.
**Note:** Ignite stores a cache's configuration with its data, so data directories created before this keep the old mode. The project hasn't been released, so no migration is provided.

### D-042 — Every binary schema is registered when a node starts
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** test teardowns sometimes stalled for about 30 s with "Timed out while waiting for schema update" and a blocked striped thread. The stalled type was vertx-ignite's `SubsMapHelper$RemoveRegistrationProcessor`, the entry processor that removes an event-bus subscription. A node first sends it while **shutting down**. Ignite registers a schema with the cluster the first time a node sends it, so the stopping node started a registration, sent the update, and left before the registration completed. The receiver waited `IGNITE_WAIT_SCHEMA_UPDATE` (30 s) for a schema that never came. Every rolling restart would hit this. A probe also showed that a schema includes **field order**, and `Table` wrote columns in `HashMap` order, so rows could gain new schemas at runtime too.
**Decision:**
- While starting, before serving, a node registers the schemas of every type Vert.x's cluster manager sends (the three subscription processors, registration and node info, wrapped shared-data values) and one row of every table.
- Rows always carry `json` and then every column in declared order, nulls included, so each table has exactly one schema.
- `BinarySchemaTest` checks the invariant: after start-up, nothing a node does, including stopping, adds a schema the survivors haven't seen. Before the fix it failed (every table and the remove processor were new), and the run took 46 s. After it, the test passes in 19 s with no stall.
**Cost:** reflection on three package-private vertx-ignite classes. If an upgrade renames them, the node fails to start with a message naming D-042, rather than stalling silently.

### D-043 — The tenant quota counts files and kv together
**Date:** 2026-10-06 · **Status:** Accepted. Supersedes D-038's "kv doesn't count against the tenant quota yet"

**Decision:**
- `C93_TENANT_STORAGE_MB` covers everything a tenant stores. A kv entry counts as the UTF-8 bytes of its namespace, key and JSON value (`KvStore.entrySize`); stores record it with the entry and sum it by tenant (an SQL `SUM` on Ignite).
- One `TenantQuota` applies it, and the stores no longer know about quotas: `FileStore.create` takes the most the upload may store.
- **Uploads** ask for a fresh count once, are refused up front if `Content-Length` doesn't fit, and are cut off when they pass what was left.
- **kv writes** are checked against a count at most 2 s old, plus what this node admitted since, so a write doesn't sum the tenant's storage each time. When that quick check says no, it's redone from a fresh count against what the write really adds over the entry it replaces. A full tenant can still update, shrink or delete what it has.
**Cost:** writes made on other nodes in the last 2 s aren't in the quick count, so a tenant can overshoot by what it writes cluster-wide in that window. This is small next to a quota measured in megabytes, and it can't run a node out of storage: admission control (D-038) is separate and always checks.

### D-044 — The first credential is an admin token from the environment, or generated once
**Date:** 2026-10-06 · **Status:** Accepted (Tony)

**Decision:** `C93_ADMIN_TOKEN`, if set, is the admin token on every node. Otherwise the first node of a new cluster generates one, stores only its hash in the cluster, and logs it once. The admin token manages tenants and tokens, and nothing else: it can't read tenant data. To act as a tenant, mint that tenant a token.
**Why:** something has to exist before any token does. An environment variable suits deployments, and a generated, logged-once token suits a first `docker run`. Keeping admin out of tenant data means a leaked admin token can't silently read everything; it can only mint tokens, which is visible.

### D-045 — Scoped, revocable bearer tokens replace the tenant header
**Date:** 2026-10-06 · **Status:** Accepted. Replaces the `X-Cluster93-Tenant` stand-in

**Decision:**
- Tokens look like `c93_` + 32 random bytes (base62) + a checksum, and are sent as `Authorization: Bearer`.
- Only their SHA-256 is stored. They are 256-bit random, so a slow hash adds nothing.
- Each token belongs to one tenant and carries scopes (`jobs:read`, `files:write`, …, or `*`), an optional expiry, and can be revoked.
- A missing token gets 401, and a missing scope gets 403 naming the scope.
**Why:** the 2017 API sent a long-lived username and key with every request (weak spot #7), and the stand-in header let anyone act as any tenant. The prefix and checksum let secret scanners spot leaked tokens.

### D-046 — The API is contract-first
**Date:** 2026-10-06 · **Status:** Accepted (Tony)

**Decision:** A hand-written OpenAPI 3.1 spec in the repo is the source of truth. It's served at `/v1/openapi.json`, and requests are validated against it before handlers run. Tests fail if a route and the spec disagree, and they validate real responses against it. The CLI and the MCP tools follow the spec.
**Why:** weak spot #4. A spec that only documents the code drifts, while a validated one can't.

### D-047 — MCP is built into every node, with a stdio bridge in the CLI
**Date:** 2026-10-06 · **Status:** Accepted (Tony)

**Decision:** Every node serves MCP over Streamable HTTP at `/mcp`, with the same tokens and scopes as the REST API. `c93 mcp` bridges stdio for clients that only launch local processes. The protocol is implemented directly on Vert.x.
**Why:** agents that support remote MCP servers need nothing installed, and the rest still work through the CLI. One auth model covers both. The Java SDK's transports assume Servlet or Reactor, which this project doesn't use.

### D-048 — The CLI: Java and picocli, compiled to native binaries
**Date:** 2026-10-06 · **Status:** Accepted, with D-049 (Tony asked for native Windows, Linux and macOS builds)

**Proposal:** a new `cluster93-cli` module using picocli, the JDK HTTP client and a small JSON library. It is compiled with GraalVM native-image into one binary per platform, so no JDK is needed on machines that talk to the cluster, and published as GitHub release assets. npm packages wrapping the binaries, as esbuild does, could follow.
**Constraint:** native-image can't cross-compile. Linux x64 can be built here in a GraalVM container. macOS, Windows and arm64 need those machines, or GitHub Actions runners in a release-only workflow (not CI, so D-017 stands).

### D-049 — The CLI installs with one line, on Windows, Linux and macOS
**Date:** 2026-10-06 · **Status:** Accepted (Tony); hosting settled 2026-10-07, see the update below. Confirms D-048

**Decision:**
- One native binary per platform: Linux x64 (static, musl) and arm64, macOS arm64 and x64, Windows x64.
- They're published with `SHA256SUMS` as release assets, and installed with `curl -fsSL …/install.sh | sh` or, on Windows, `irm …/install.ps1 | iex`. The scripts detect the platform, verify the checksum, install to a user directory and set up `PATH`.
- `c93 upgrade` reruns the installer.
**Why:** Tony: "super easy to install the cli via curl", with Windows, Linux and macOS builds, and no JDK on the machines that talk to the cluster.
**Open:** native-image can't cross-compile, so building for macOS and Windows needs GitHub Actions runners in a tag-only release workflow (not CI). Separately, a private repo's release assets can't be downloaded anonymously, so the one-liner needs either a public repo or a separate public releases repo. Both are Tony's call before milestone 3.

### D-050 — How requests are validated against the contract
**Date:** 2026-10-06 · **Status:** Accepted. Implements D-046

**Decision:**
- Every rule in `RouteScopes` names its contract operation and how its body is read: no body, JSON up to a limit, or streamed. The node won't start if a rule names an operation the spec lacks.
- After authentication and rate limiting, JSON bodies are read once with the rule's limit and validated, together with the path, query and headers, by `vertx-openapi`, before the handler runs. Failures get 400 `invalid_request` with the reason and location.
- **JSON regardless of Content-Type.** JSON bodies are validated as JSON whatever Content-Type they arrive with, because `curl -d` sends a form type and the API always accepted that. An empty body counts as no body.
- **Streamed and binary bodies** (file uploads) are checked on headers and parameters only. Binary content has no schema in the spec, as OpenAPI 3.1 intends. Server-sent events are registered as a media type and checked as text.
- `ContractTest` enforces the agreement both ways: the router's routes and the spec's operations must be the same set, and one real call per operation (40 in all) must produce a response the spec allows. It already caught one discrepancy: `cancelRequested` can be `null`.
**Why not RouterBuilder** (vertx-web-openapi-router)? It owns routing and body handling, which would fight the streamed uploads and the async auth that pauses requests. Using it would mean rewriting every route for no gain.

### D-051 — Rate limits per tenant, as each node's share
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:**
- A token bucket per tenant, holding a minute's worth (`C93_RATE_LIMIT_PER_MINUTE`, default 1200; 0 turns limiting off). It refills continuously.
- Each node enforces its share: the budget divided by the live nodes, refreshed every 5 s. No request needs a cluster round-trip.
- Responses carry the IETF `RateLimit-Policy` and `RateLimit` fields. Over the limit is 429 `rate_limited` with `Retry-After`.
- The admin token and public routes aren't limited.
**Cost:** a client that only ever reaches one node gets that node's share, not the whole budget. A cluster-wide counter would have to be updated on every request.

### D-052 — Early answers close with a lingering close
**Date:** 2026-10-06 · **Status:** Accepted. Refines D-039

**Evidence:** `StreamingTest` sometimes saw "Connection was closed" instead of the 413 for a 65 MB upload. The server answered and closed straight away while bytes were still arriving, the kernel reset the connection, and the client lost the answer it had been sent.
**Decision:** When the body is too big to drain (over 64 MB, declared or counted), the server sends `Connection: close` and keeps reading and discarding. It closes once the body ends or after 5 s, whichever is first, and only after the answer is out. This is what nginx calls a lingering close. Small bodies are drained and the connection is kept, as before.

### D-053 — How the CLI is built
**Date:** 2026-10-06 · **Status:** Accepted. Implements D-048 and D-049

**Decision:**
- **Libraries.** picocli, Jackson's tree model (nothing is bound to classes by reflection) and the JDK HTTP client. That is all native-image needs; picocli's annotation processor writes the reflection config. The static Linux x64 binary is 50 MB, starts in about 10 ms, and runs on Alpine.
- **Scriptable by default.**
  - `run` sends logs to stderr and the output to stdout, and exits 0 only when the job succeeded.
  - Token secrets go to stdout and notes to stderr.
  - `--json` on any command prints the API's JSON.
  - Usage errors exit 2, other failures exit 1.
- **Credentials.** The login is saved with owner-only permissions. `C93_URL` and `C93_TOKEN` override it, for CI.
- **`files sync`** compares local SHA-256 hashes with the server's ETags (D-040), so only changed files go up.
- **Testing.** Every command runs in-process against a real node in the build (`CliTest`). The installer is tested for real against a local release host, including a tampered checksum (`InstallScriptTest`), and `install.ps1` is checked to parse.
- **Release workflow.** `.github/workflows/release.yml` builds the five binaries on a version tag only, smoke-tests them (Linux x64 against a real node), and publishes them with `SHA256SUMS` and both installers. Nothing runs until a tag is pushed.

### D-054 — How the MCP server is built
**Date:** 2026-10-06 · **Status:** Accepted. Implements D-047

**Decision:**
- **Stateless Streamable HTTP.** One JSON-RPC message per `POST`, answered as JSON. No sessions and no server-initiated stream (`GET` and `DELETE` are 405), and batches are refused. Protocol versions `2025-11-25`, `2025-06-18` and `2025-03-26` are accepted, with the newest offered otherwise.
- **Tools go through the REST API.** Each tool calls this node's own REST API over loopback with the caller's token. Scopes, validation, quotas and rate limits therefore apply exactly as for any client, and each operation has one implementation. Loopback calls carry a per-node secret so they don't count against the rate limit twice.
- **Resources.** The scripting guides are bundled into the server jar from `docs/scripting/` at build time and served as resources.
- **Admin token.** It gets 403: tools act on a tenant.
**Why not the MCP Java SDK?** Its server transports assume Servlet or Reactor, and the protocol surface used here is small. `McpTest` and the CLI bridge test exercise it end to end, including an agent deploying a service and calling it through the ingress.
**Note:** Origin checks matter for MCP servers on localhost without auth (DNS rebinding). Here every request needs a bearer token, which a rebinding page can't supply.

### D-055 — CLI releases are built by GitHub Actions and downloaded from the repo's GitHub Releases
**Date:** 2026-10-07 · **Status:** Accepted (Tony). Settles D-049's open questions

**Decision:**
- The tag-only release workflow (`.github/workflows/release.yml`) may use GitHub Actions runners for the macOS, Windows and arm64 builds. It runs on version tags only, never on pushes, so D-017 (no CI) stands.
- Public downloads come from `TonyRice/cluster93`'s own GitHub Releases, once Tony makes the repository public. The installers and `c93 upgrade` already default to `https://github.com/TonyRice/cluster93/releases/latest/download`, so nothing changes at that point.
**Until then:** a tag still builds and publishes a release, but its assets need a GitHub login to download. Use `gh release download`, or point `C93_INSTALL_URL` at a mirror.

### D-056 — File storage backends: metadata in the cluster, bytes in a pluggable BlobStore
**Date:** 2026-10-07 · **Status:** Accepted (Tony). Extends D-040

**Decision:**
- **What stays in the cluster database:** paths, versions, the atomic swap to a new version, garbage collection and quotas, whatever holds the bytes.
- **The bytes go to a `BlobStore`**, chosen with `C93_FILES_BACKEND`:
  - `cluster` (default): 1 MB chunks in the replicated store, as before
  - `disk`: one file per blob under `C93_FILES_DIR`, written to `.part`, flushed to disk and renamed, with free space checked as it writes. In a cluster, every node must mount the same folder.
  - `s3`: one object per blob in an S3-compatible bucket: 8 MiB multipart parts (one PUT if smaller), ranged GETs for reads, and deleting aborts unfinished uploads
- **Each file records its backend**, so it's always read from where it was written. Reading a file after switching backends gives a clear error rather than "missing".
- **A node checks its backend at start** (the bucket answers with these credentials, or the folder is writable) and refuses to start otherwise.
- **The S3 client is our own**: about 300 lines of SigV4 signing on Vert.x's HTTP client, checked against AWS's published example. The AWS SDK brings its own Netty, which would clash with Vert.x's, and only a dozen calls are needed. XML answers are parsed with DTDs and external entities off.
- **Tests:**
  - every backend passes one contract (multipart boundaries, uneven write sizes, ranges, empty blobs, abort, delete, missing blobs)
  - the S3 tests run against Versity's S3 gateway in Docker, which really checks signatures
  - the file-store contract runs on each backend
  - a two-node cluster serves a 20 MB file from the bucket, including ranges through a service
**Not chosen:** MinIO for tests, whose community images are no longer on Docker Hub; SeaweedFS and s3mock, which accept unsigned requests by default and so wouldn't catch signing bugs.

### D-057 — Tenants bring their own storage providers, under the admin's policy
**Date:** 2026-10-07 · **Status:** Accepted (Tony)

**Tony's decisions:**
- Tenants can configure S3-compatible providers with their own credentials, and disk providers in a configured folder with an allotted amount of space.
- The admin enables or disables provider types per tenant.
- Only bytes on the cluster's own storage count against the tenant quota.
- Each tenant has a default provider plus path-prefix routes.
- Endpoints must be public unless the admin allows private ones.

**How:**
- **Providers are named resources of a tenant** (`/v1/storage/providers`, scopes `storage:read` and `storage:write`). Files record `provider:<id>` as their backend, so keys can rotate, and routes can change, without breaking reads.
- **Secrets are write-only.** They're sealed with AES-256-GCM using a key derived from `C93_SECRETS_KEY`, with the tenant and provider id as associated data, so a sealed secret can't be moved to another provider. The API reports only `hasSecret`.
- **A provider is checked against its backend before it's saved.** A wrong key, a missing bucket or a private address is refused with the reason.
- **Routes** (`/v1/storage/routes`): the longest matching prefix wins, then the tenant's default; `default` means the cluster's own storage.
- **The admin's policy** (`/v1/tenants/{name}/storage-policy`): which types are allowed, the disk allotment, and whether endpoints may be private. Nothing is allowed by default. Turning a type off refuses new writes there (403 `storage_disabled`) and keeps reads working.
- **The room an upload gets depends on where it goes:**
  - on the cluster's own storage, the tenant quota (D-043)
  - in the tenant's bucket, unlimited
  - on a disk provider, the allotment, counted across all the tenant's disk providers
  - a declared size that won't fit is refused up front, naming the limit
- **Disk providers live at `<C93_DISK_ROOT>/<tenant>/<name>/`**, a path chosen by the server, never by the tenant. Every node writes a marker into the root and checks the other live nodes' markers. Disk providers only work while all of them are visible. Redundancy across nodes is the shared storage's job (NFS, Ceph); local disks on several nodes can't provide it, and the check turns disk providers off rather than serving files only one node can see.
- **Endpoint checks:** tenant S3 endpoints are resolved and checked like `fetch` (D-026), and the connection is pinned to the checked address.
- **Cleanup:** a provider holding files, or one that routes point to, can't be deleted. Deleting a tenant forgets its policy, providers and routes.
- **Not for agents:** storage isn't exposed as MCP tools, so credentials don't pass through an agent.

**Tests:**
- `StorageProvidersTest`, against the S3 gateway and a disk root: the policy gates, the private-endpoint refusal, a wrong key caught before saving, routing, quota exclusion, allotment enforcement, key rotation, a disabled type blocking writes but not reads, the in-use delete guard, and tenant isolation
- `SharedDiskRootTest`, on two nodes: different folders turn disk providers off; a shared folder serves files from either node
- the contract on both stores, `StorageModelTest` (routing and sealing), `ContractTest` (52 operations) and `CliTest`

### D-058 — Metrics: Micrometer and Prometheus on a management port, one registry per node
**Date:** 2026-10-07 · **Status:** Accepted

**Decision:**
- **Each node has its own Prometheus registry**, tagged `node`, and Vert.x is built with it (`MicrometerMetricsFactory`). That gives Vert.x's HTTP server and client, event bus and pool metrics, plus the JVM's.
- **Cluster93's own meters:**
  - jobs submitted, queue wait, run time by status, running and capacity
  - isolate cold start
  - service runs and restarts
  - 429s by tenant
  - files stored by backend kind, and uploads refused by reason
  - live nodes, leader, and the Ignite data region's use
- **Recorded without plumbing.** The registry sits in each node's Vert.x local map, so any component with the `Vertx` records. Several nodes in one JVM (the tests) stay apart.
- **Low-cardinality labels:** no job ids, no client addresses (`remote`), no event-bus addresses. Tenants appear only on the rate-limit counter, where the tenant is the point.
- **The `/metrics` port is separate** (`C93_METRICS_PORT`, default 9090, `off` to disable), meant for a private network like the internal port. It's kept off the API so it doesn't need a token.
- **`/ready`**, next to `/health`, answers 503 with reasons until the node is among the live nodes and the cluster is active. It's for load balancers and orchestrators.
- **Logs:** Logback replaces slf4j-simple. `C93_LOG_FORMAT=json` uses Logback's own JSON encoder: one object per line, with SLF4J key-value pairs (`jobId`, `tenant`, `status`, `durationMs` on "job ended") as fields. In JSON mode Ignite logs through the logger (`IGNITE_QUIET=false`), so its console lines don't break the format.
**Deferred:** OpenTelemetry tracing. The plan made it optional, and metrics, readiness and structured logs cover operating the cluster. It can be added with `vertx-opentelemetry` behind `C93_OTLP_ENDPOINT` later.

### D-059 — Benchmarks: a rerunnable script, honest methodology, and no change without a measured gain
**Date:** 2026-10-07 · **Status:** Accepted

**Decision:**
- **The numbers come from `benchmarks/run.sh`,** which runs the three-node Docker cluster from the freshly built jar and regenerates `docs/benchmarks.md`, machine details included. The README quotes that page and says runs on one machine vary by up to 2x.
- **Time what a user waits for.** Jobs are timed to the `end` event on their event stream, which is how the CLI waits. Polling every 5 ms put load on the API and measured the polling interval as much as the job.
- **Measure isolate memory as resident memory.** `docker stats` counts page cache and the Java heap's growth, and once gave a negative cost per isolate. The bench pins and pre-touches the heap (`-Xms1g -Xmx1g -XX:+AlwaysPreTouch`), then sums `VmRSS` of each node's Java process, so growth is native memory. Two runs both gave 1.6 MB per idle isolate.
- **Medians for one-off transfers.** The file scenario runs three rounds and reports the median.

**Rejected: storing several upload chunks at once.** Uploads looked slow (13 MB/s) in the first run, so the chunked writer was changed to keep four 1 MB chunks in flight, with a gap-tolerant delete for interrupted uploads. An A/B run measured 48 MB/s with it and 45 MB/s without it (one chunk at a time), within the noise between runs. The 13 MB/s was an outlier. It touched the file store's correctness (ordering, cleanup after failures) for no measured gain, so it was reverted.

**Not yet done:** profiling throughput under concurrent load. On one laptop, 32 clients finish about as many jobs per second as one client does.


### D-060 — Host APIs are Java objects, bound per API; the JavaScript is thin glue in files
**Date:** 2026-10-07 · **Status:** Accepted (supersedes the mechanism in D-019's host-call design and docs/design/host-apis.md)

**Context:** Scripts saw their APIs through one generic `HostServices.call(op, argsJson)` and a 475-line JavaScript string (`JavetScriptRuntime.PRELUDE`) that built `kv`, `bus`, `fetch`, `files` and `ctx` on top of it. The real work was already in Java, but the API's shape lived in the string, every operation went through one `switch`, and nothing in the core described the API as types. In 2017 the APIs were Java objects exposed to Nashorn. Tony asked for objects again, and for a design another language (Python, later) could reuse.

**Decision:**
- **Core:** one Java interface per API in `cluster93.core.runtime.api` (`KvApi`, `BusApi`, `FilesApi`, `FetchApi`, `HttpApi`, gathered in `HostApis`), with JSON, `Buffer` and `Future` in their signatures and no script-engine types. The node implements each (`NodeKvApi`, …), and they share one `HostScope` that holds the tenant (from the job, never the script) and the listeners to undo. `HostArgs` converts loosely typed arguments with the same errors in every language.
- **Javet:** one binding per API (`KvBinding`, `BusBinding`, `FilesBinding`, `FetchBinding`, `HttpBinding`), each bound with `V8ValueObject.bind()` under the hidden host object.
  - **What `bind()` exposes:** only `@V8Function` methods, never `getClass` or reflection. Javet's proxy converter, which would expose Java objects, is not used.
  - **Promises:** a binding returns a promise created in Java (`createV8ValuePromise`). `HostBridge` settles it on the isolate's event loop when the future completes; a failure becomes a real `Error` with `name` and `code`.
  - **Value conversion:** `JsValues`, through V8's own `JSON`, whose functions are captured before the script runs, so a script replacing `JSON.parse` only affects itself.
- **JavaScript:** 13 small files in `runtime-javet/src/main/resources/cluster93/runtime/javet/js/`, only for what has to be idiomatic JavaScript: freezing globals, kv namespaces, `fetch`'s Response, streams as async iterators, serving, `ctx`. `JavetScriptRuntime.PRELUDE_FILES` lists them in order, and they run in one closure.

**Why not bind Java objects with no JavaScript at all:** some things can only be built in the isolate, such as async iterators, frozen objects and a `Response` with methods. The remaining JavaScript is glue, without logic or limits.

**Kept:** the event loop, the in-flight call limit (256), the listener limit (100), stream backpressure, and console, timers and state as they were.

**Tests:**
- `NodeHostApisTest` (28) exercises each API directly, with no engine.
- `HostBridgeTest` (21) runs against fake `HostApis`, with its scripts unchanged.
- The rest of the runtime suite, hostile scripts included, is unchanged.

### D-061 — Durable queues as the bus's second mode: `bus.queue(name)`, at least once, leases and dead letters
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** In 2017, jobs and services talked over the message bus, and Tony wants a queue next to it. The bus (`publish`/`subscribe`, `request`/`handle`) is the Vert.x event bus, which delivers at most once: a message published while nobody listens is gone, and nothing is acknowledged or redelivered. Tony chose the shape `bus.queue(name)`, so the default mode stays as it is and queues read as a different guarantee.

**Decision:**
- **Stored in the cluster.** One replicated table (`QUEUE`, FULL_SYNC like every table, D-041), indexed by (tenant, queue, status, due), where "due" is `availableAt` while ready and the lease deadline while in flight. Single-node and test setups use an in-memory store. Both pass the same contract (`QueueStoreContract`), because the rules live in one `QueueService` over a small store interface.
- **Claiming:** compare-and-set from READY (and due) to IN_FLIGHT, with an owner, a 30 s lease and `attempt + 1`, the same fenced-transition pattern as jobs (D-010, D-011). Consumers try the due candidates in a random order, so racing consumers rarely collide.
- **No sweeper.** A claim may also take an IN_FLIGHT message whose lease has run out, so a dead consumer's messages come back without a background job. A message whose lease runs out on its last attempt is dead-lettered on the way.
- **Leases are extended** every third of the lease while the handler works, so slow handlers keep their message. If the node dies, the extensions stop.
- **Ack and failure belong to the delivery.** An ack removes the message only if the caller still owns that delivery (owner and attempt); a late ack after the lease ran out does nothing. A failure retries after 1 s, 4 s, 16 s, … up to 10 min, then dead-letters with the last error. A consumer that stops mid-message (its script ended) gives the message back at once, and that delivery doesn't count.
- **Waking consumers:** a push publishes a hint on the event bus, so idle consumers claim at once. Otherwise they look every second, backing off to every 5 s while the queue is empty, which also catches delayed messages and expired leases.
- **Deduplication:** a `key` becomes the message id, so pushing it again while it's queued does nothing.
- **Limits:** messages are JSON of at most 256 KB, like the bus. Queued bytes count toward the tenant's quota with files and kv (D-043). Delays are capped at 7 days, `maxAttempts` at 20 (default 5) and `concurrency` at 32.
- **Surfaces:**
  - **Scripts:** `bus.queue(name)` with `push`, `consume`, `stats`, `dead`, `retry` and `discard`, through `QueueApi` and `QueueBinding` (D-060).
  - **REST:** `POST/GET /v1/queues/{queue}`, `GET /v1/queues/{queue}/dead`, `POST …/dead/{id}/retry`, `DELETE …/dead/{id}`, with new scopes `queues:read` and `queues:write`.
  - **CLI and MCP:** `c93 queues …`, and the tools `queue_push`, `queue_stats`, `queue_dead` and `queue_retry`.
- **System queues:** the tenant `@system`, which no real tenant can be named (tenant names are `[a-z0-9][a-z0-9_-]*`), holds the system's own queues, starting with completion callbacks. These skip the tenant quota but not admission control.

**Not done:** ordering guarantees (messages come out roughly in push order, with no FIFO promise), outside subscribers (parked in NOTES.md), and per-queue configuration.

**Tests:**
- `QueueStoreContract` (13 tests, run on both stores): racing consumers, retries, dead letters, lease expiry, extension, release, deduplication, delay, tenant scoping, quota and validation.
- `DurabilityTest.aMessageInFlightOnALostNodeIsDeliveredElsewhere`: three Ignite nodes, a consumer's node stopped mid-message, and the message delivered again on a survivor.
- `QueueScriptTest` (5 tests, real scripts in V8).
- `HostApisTest`: a service on one node consumes what REST pushes through the other.
- `ContractTest`, `McpTest` and `CliTest` cover the new operations.

### D-062 — Completion callbacks: a signed POST when a job ends, delivered through a system queue
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** In 2017 a job could carry a `callbackUrl`, and the job server POSTed `{token, status, success, error}` to it once when the job finished, with no signature, no retry and no output. The rewrite had nothing similar: callers polled or held an event stream open.

**Decision:**
- **Asking for one:** `callback: {url, data}` on `POST /v1/jobs`, or `ctx.setCallback(url, data)` from the script, which also works in a service's run. `data` (JSON, at most 16 KB) comes back unchanged. The URL must be http(s) with a host, at most 2,048 characters.
- **One hook for every ending.** `CallbackJobStore` wraps the job store and queues a delivery whenever an update moves a job with a callback into a terminal status. Finishing, orphaning, cancelling and rejecting are all such updates, and compare-and-set lets only one of them end a job. The queue key is the job id, so a repeat is a no-op anyway.
- **Delivery through a durable system queue** (D-061, tenant `@system`, queue `callbacks`), consumed by every node. Deliveries therefore survive the node that queued them and share the queue's retries: 8 attempts, at 1 s, 4 s, 16 s, 64 s, 256 s and then every 10 min, about half an hour in all.
- **Each delivery reads the job as it is then,** and POSTs `{jobId, status, output, error, attempt, finishedAt, service, data}` through the `fetch` client. So the egress policy applies (D-026): private and metadata addresses are refused unless the operator allows them, and redirects aren't followed. A refused address fails at once rather than being retried.
- **Signature:** `Cluster93-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`, the scheme receivers already know from other webhook senders. Each delivery also carries its id (`Cluster93-Delivery`) and attempt number (`Cluster93-Attempt`).
- **Secrets:** one per tenant, created on first use. They're stored in the replicated kv store under the system tenant, which no token can reach. Creation uses a new atomic `KvStore.putIfAbsent`, so racing nodes agree on one secret. They're sealed with `C93_SECRETS_KEY` when it's set (D-057); if that key changes, the secret is replaced as a rotation would. Read or rotate it with `GET /v1/callbacks/secret` and `POST …/rotate` (scope `tokens:write`), or `c93 callbacks secret [--rotate]`.
- **Progress is recorded on the job:** `callback: {url, data, status: PENDING | DELIVERED | FAILED, attempts, deliveredAt, lastError}`.

**Why the cluster secret isn't used to derive tenant secrets:** it lives in the cluster manager's shared map, which isn't kept across a full restart, so the derived secrets would change under receivers.

**Tests:**
- `CallbacksTest`, a node posting to a local receiver: the signature and `data`, a retry after a 500, `ctx.setCallback`, failed jobs calling back, rotation, and bad callbacks refused at submission.
- `CallbackDeliveryTest`: every way a job ends queues exactly one delivery; a metadata address fails once and isn't retried; secrets are created once and sealed.
- The kv contract's `putIfAbsent`, and `ContractTest` (59 operations).

### D-063 — Scripts start and follow jobs: `jobs.run`, `result()`, `events()`, `stop()`
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** In 2017 a script could start a job with `Cluster93.startJob(script, config, callback, waitForJob)`, read one (`getJob`) and stop one (`stopJob`). `waitForJob` blocked the script's thread on a latch until the child finished. The rewrite had no way for a job to start another.

**Decision:**
- **The calls:**
  - `jobs.run(script, input, options)` starts a job and returns a handle `{id, result(), events(handler), stop(reason)}`.
  - `jobs.get(id)`, `jobs.stop(id, reason)` and `jobs.handle(id)` work on any of the tenant's jobs.
  - The options are those of `POST /v1/jobs` (timeout, orphan policy, attempts, callback), checked the same way.
  - The script can be an ES module's source or a function, which is sent as its source and so can't capture variables.
- **Nothing blocks.** `result()` is a promise. It listens for the job's `end` event before looking at the job, so an end in between isn't missed, and looks again every 2 s in case the event was lost.
- **Same tenant, same limits:**
  - **Owner:** started jobs always belong to the caller's tenant. They're scheduled like API submissions (least-loaded node, D-005) and count toward the tenant's rate limit. The limiter is now created once per node and shared by the API, MCP and scripts.
  - **Lineage:** each job records `parent` and `depth`.
  - **Bounds:** depth stops at 5, and one run starts at most 100 jobs, so a script can't fork-bomb the cluster.
  - **Other tenants:** their jobs look missing (`null` or `not_found`), as in the API.
- **Waiting costs a slot.** A parent waiting on `result()` keeps its isolate. If the cluster has no room for the child, the child fails at once and the parent sees that, instead of the two waiting on each other. For a result after the parent's run has ended, completion callbacks (D-062) are the answer.

**Tests:** `JobsFromScriptsTest` covers:
- a parent awaiting a child, with function scripts
- the parent link and depth
- recursion stopping at level 5
- stop and events
- other tenants' jobs looking missing
- bad runs refused

### D-064 — The demo is a script, with a scripted MCP client as the agent
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** The plan's demo was "an agent deploys and calls a function on a 3-node cluster, including a node kill". A live Claude Code session would show an AI at work, but it isn't repeatable, needs an API key and Claude Code, and can take a wrong turn in a recording. Tony agreed a live session isn't needed.

**Decision:** `scripts/demo.sh` runs the whole story the same way every time.
- **Setup:** it builds the jar into a local image (as the benchmarks do), starts the three nodes with Prometheus and Grafana, and creates a tenant and a token.
- **The agent** is a scripted MCP client: `initialize`, `tools/list`, `deploy_service` and `queue_push`, sent as JSON-RPC to `/mcp` exactly as an agent would send them. The CLI shows queue stats along the way.
- **The service** is the new APIs together: `jobs.get` to name its node, `bus.queue('orders').consume`, `kv` to record each order by id, and `ctx.serve`. Its work takes 400 ms per order with 2 at a time, so the kill lands mid-queue.
- **Each step is checked, so the demo doubles as a smoke test.** It `SIGKILL`s the node running the service, then watches the restart, the queue draining, and the in-flight orders coming back after their leases run out. It fails unless every order is handled, counted by distinct id because delivery is at least once.
- **It leaves the cluster running** for Grafana, and `scripts/demo.sh down` removes it.

**The recording** is `docs/demo.cast` (asciicast v2), made by `scripts/record.py`, which captures a real run without asciinema installed. Long pauses are shortened on playback.

**To make the dashboard tell the same story,** queues and callbacks got counters: `c93_queue_messages_total{outcome}` and `c93_callbacks_deliveries_total{outcome}`. They have a dashboard row of their own, and `DashboardTest` produces both.

### D-065 — Ignite's client and REST connectors are off, and Ignite binds to one address
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** While designing mutual TLS for the cluster (docs/design/cluster-security.md), a probe of a running node found two listeners nothing in Cluster93 uses, both Ignite defaults, unauthenticated, on every interface:
- **10800:** the thin-client/JDBC/ODBC connector. Anything that could reach it could run SQL against every table, every tenant's data and the tokens table included.
- **11211:** the binary REST command protocol.

Docker didn't publish them to the host, but every container on the network could reach them, and they weren't in SECURITY.md.

**Decision:**
- **Both off:** `IgniteConfiguration.setClientConnectorConfiguration(null)` and `setConnectorConfiguration(null)`.
- **One address:** Ignite listens on `C93_CLUSTER_BIND`, defaulting to an explicitly set `C93_ADVERTISE_HOST`. Without either, it still listens on every interface, as single-machine development does, and the node logs a warning. The compose file and DockerClusterIT bind each node to its own hostname.
- **`ListeningPortsTest`:** it reads the JVM's own listening sockets from `/proc` and asserts a clustered node opens only the API, ingress, internal, metrics, discovery and communication ports plus the event bus. Any new listener fails the build until it's added on purpose. It was checked to fail, naming 10800 and 11211, with the connectors turned back on.

**Not yet:** authentication and encryption between nodes. That's the rest of the design (D-066, D-067): a cluster CA whose key stays on the operator's machine, and node bundles for one-line joins.

### D-066 — Core nodes authenticate each other with certificates from the cluster's own CA
**Date:** 2026-10-07 · **Status:** Accepted (design: docs/design/cluster-security.md)

**Context:** Ignite discovery and communication, the clustered event bus and the internal endpoint were unauthenticated and unencrypted. Anything that could reach the discovery port could join the cluster and receive replicated data. D-065 closed Ignite's unused ports; this closes the rest.

**Decision:**
- **The CA:** each cluster has its own CA. EC P-256 throughout; the CA lasts 10 years and node certificates 3. **Its private key stays on the operator's machine** (Tony): `c93 cluster init` writes `ca.key`, and the cluster never stores it.
- **A node certificate** names the node (common name and DNS name) and its addresses, and is good for TLS server and client use.
- **TLS 1.3 with a node certificate required on both sides** for:
  - **Ignite discovery and communication:** `IgniteConfiguration.setSslContextFactory`. The context is wrapped so every server-side socket and engine requires the peer's certificate, whatever Ignite asks for.
  - **The clustered event bus:** `EventBusOptions` with PEM key and trust and `ClientAuth.REQUIRED`.
  - **The internal endpoint:** HTTPS with required client certificates; the ingress presents its node certificate.
- **D-036's shared secret is retired** when certificates are on (Tony).
- **On by default for clustered nodes** (`C93_CLUSTER_TLS=required`). A node without certificates refuses to start and says how to get them. `off` is for development and logs a warning at every start.
- **Revocation by node id:**
  - **How it works:** a certificate issued before a node's revocation is refused, and one issued after (the node was invited again) is accepted.
  - **Storage:** revocations are kept in the cluster (the system tenant's kv), broadcast to every node, and saved by each node in its TLS directory, so a node refuses revoked peers even before it has rejoined.
  - **A node in the cluster is forced out** through Ignite discovery's `failNode`, which isn't up to the node, unlike `stopNodes`; a well-behaved node also stops itself on the broadcast.
  - **The event bus** trusts the CA but doesn't check revocations: Vert.x copies event-bus options in a way that loses custom trust managers. Ignite discovery does check, so a revoked node can't rejoin, and forcing it out takes its connections with it.
- **A join timeout** (`C93_CLUSTER_JOIN_TIMEOUT_MS`, 60 s): a node whose certificate the members refuse fails its start instead of retrying forever, which is Ignite's default.
- **Exact issue times:** certificate dates have one-second precision, which made a node invited again within the second of its revocation look revoked. Found by the full test run. Our serial numbers carry the issue time in milliseconds in their high bits, and revocation compares that. Certificates from another CA fall back to their start date.
- **What a refused node does:**
  - **When Ignite reports the TLS refusal:** the node's start fails.
  - **When Ignite reads the refusal as an unreachable address:** the node may start alone, as a one-node cluster of its own.

  Either way it isn't a member: it holds no data, and members refuse its certificate.
- **Following revocations never holds up a start.** Found by the Docker test: reading the list with SQL as part of startup failed on a freshly forming cluster, because Ignite cancels queries while partitions move. The list now loads in the background, retrying, and every minute after (in case a broadcast was missed), with each node's saved copy covering the meantime. Queries cancelled that way (`QueryCancelledException`) are now retried everywhere, like the other topology errors (D-033).

**Tests:**
- **`cluster93-tls`:** real handshakes. A member gets in; no certificate, a foreign CA and a revoked node are refused; a node invited again gets in.
- **`ClusterSecurityTest`:**
  - no certificates → refused, with the fix in the message
  - a join line for another node → refused
  - the internal endpoint doesn't answer plain HTTP
  - a foreign CA and TLS off can't join
  - a revoked node's certificate is refused
  - invited again, it rejoins
- **Every in-JVM cluster test** now runs with TLS required (`TestTls`).
- **`DockerClusterIT`:** its nodes join with `C93_JOIN` bundles, and **a live node is revoked and forced out**, its process stopping.

### D-067 — Joining a node is one line: a per-node bundle signed on the operator's machine, never the CA key
**Date:** 2026-10-07 · **Status:** Accepted

**Decision:**
- **Creating a cluster:** `c93 cluster init --nodes …` writes `ca.key`, `ca.crt`, and one `<node>.join` line per node.
- **Adding a node:** `c93 node invite node4 --host … --ca ./ca.key` signs node4's certificate **locally** and prints `C93_JOIN=c93_nodebundle_…`: node4's key, its certificate and the CA certificate, gzipped PEM in base64url. The new node installs it in its TLS directory and starts. Tony asked for a one-liner with the key base64-encoded; it carries **the node's** key, never the CA's, because putting `ca.key` in a one-liner would hand the master key to every node and every shell history.
- **The bundle is a secret for one node:**
  - it can't create other nodes
  - it's bound to the node's name and addresses
  - a line for one node can't start another
- **The strict variant:** `cluster93-server --prepare node4` keeps the key on the new machine and prints its public key, and `node invite --key …` returns a certificate-only line (`c93_nodecert_…`).
- **Renewing is inviting again.** Nodes warn from 90 days before expiry.
- **Compose:** a one-shot `tls-init` service makes the CA and certificates in a volume on the first start (`cluster93.tls.ClusterInit`), so `docker compose up`, the demo and the benchmarks stay one command. That's development only, since `ca.key` sits in the volume.

**Tests:** CLI tests for `cluster init`, `node invite` (bundle and `--key`) and `node revoke`. DockerClusterIT joins every node with a bundle.

### D-068 — Tenants' secrets: write-only through the API, readable by scripts only with `secrets:use`
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** Security groundwork, step 0, part 2. A service's config was plain JSON on its record, readable by anyone with `services:read`, and links, containers and apps will need credentials too. The pieces already existed: D-057 seals tenants' storage keys with `C93_SECRETS_KEY`, and D-062 keeps per-tenant callback secrets under the system tenant.

**Decision:**
- **Named secrets per tenant:** `PUT /v1/secrets/{name}` with `{value}` (at most 64 KiB), `GET /v1/secrets`, `GET` and `DELETE /v1/secrets/{name}`. At most 500 per tenant.
  - **Values go in and never come out:** the API and the CLI show a secret's name, version, timestamps and the token that last set it.
  - **CLI:** `c93 secrets set NAME` reads the value from standard input, or hidden at a terminal, to keep it out of shell history; `secrets list`; `secrets rm`.
- **Scripts read them with `secrets.get(name)`**, which resolves to the value, or null if there's none.
- **Three scopes:** `secrets:read` (names), `secrets:write` (set and delete) and **`secrets:use`** (scripts may read values).
  - **Why `secrets:use`:** without it, any token that can submit a job (`jobs:write`) could run `secrets.get` and print every secret. That's the token an MCP agent typically has.
  - **Recorded on the job:** the job records `secrets: true` only if the token that submitted it had `secrets:use`.
  - **A service** records whether the token that last defined it had it; changing that is a new revision.
  - **Jobs started from scripts** (`jobs.run`, D-063) inherit their parent's flag, so a child never has more than its parent.
  - **Other scripts** get a `forbidden` error.
  - **Checked at the node:** it reads the flag from the job's durable record on the first `secrets.get`, rather than trusting anything the script passes.
- **Storage:** the replicated kv store under the system tenant, one namespace per tenant, like D-062. Each value is sealed with AES-256-GCM (D-057's `SecretBox`), with the tenant and name as associated data, so a sealed value copied to another name or tenant doesn't open.
- **The secrets key is required.** Without `C93_SECRETS_KEY` the API answers 409 `secrets_unavailable` and scripts get `unavailable`. A store called "secrets" that kept values in the clear would mislead. D-062 stores callback secrets in the clear without a key; this doesn't.
- **Deleting a tenant** deletes its secrets.
- **Not for agents:** there are no MCP tools for secrets, so values never pass through an agent; a script that may read them still can.

**Not yet (recorded for later):**
- **References** like `env: {DB_URL: {secret: "db-url"}}`, resolved when a link or container starts, with an audit of which secrets leave the cluster and to which device. They come with links and containers.
- **Rotating `C93_SECRETS_KEY`:** changing it makes stored secrets unreadable, as with D-057. Re-sealing under a new key, with the old one still accepted for a while, is a separate change.
- **Masking secrets in job logs:** a script can print a value it read, and logs are visible with `jobs:read`.

**Tests:**
- `TenantSecretsTest`: values are sealed at rest; a sealed value doesn't open under another name, tenant or key; versions; the per-tenant limit; forgetting.
- `SecretsTest`, a node with the API and scripts:
  - values are never returned
  - `jobs:write` without `secrets:use` gets `forbidden`, and child jobs inherit
  - services follow whoever last defined them
  - tenants see only their own secrets
  - deleting a tenant deletes its secrets
  - without a key, 409
- The CLI test, and `ContractTest` (64 operations).

### D-069 — Releases are signed on the maintainer's machine, and every installer checks the signature
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** Security groundwork, step 0, part 3. The installers checked each binary against `SHA256SUMS`, but the sums came from the same place as the binary: whoever could change one could change the other (a compromised release workflow, a GitHub token with write access, a mirror). And `c93 upgrade` piped the installer from the network straight into a shell.

**Decision:**
- **One signature per release, over `SHA256SUMS`,** which now lists the installers as well as the binaries. It's ECDSA P-256 with SHA-256, in the DER form `openssl dgst -sha256 -sign` writes, and published as `SHA256SUMS.sig`.
- **The key stays with the maintainer, like the cluster CA (D-066).**
  - **Creating it:** `scripts/release-key.sh` writes the private key to `~/.config/cluster93/release-signing.key`, owner-only and encrypted with a passphrase. It also writes the public key to `install/cluster93-release.pem` and copies it into `install.sh` and `install.ps1`.
  - **Signing:** the release workflow now only builds a **draft**. `scripts/sign-release.sh TAG` then downloads the draft and checks every asset against `SHA256SUMS`. It also checks that the installers carry this key. It then signs, uploads `SHA256SUMS.sig` and publishes the release.
  - **What that protects:** the key never reaches GitHub, so neither the workflow nor anyone with a GitHub token can publish a release the installers accept, and `releases/latest` never serves an unsigned build.
- **Everything that installs checks the signature first,** and refuses an unsigned or wrongly signed release:
  - **`install.sh` uses `openssl`.** That works with OpenSSL and with macOS's LibreSSL.
  - **`install.ps1` uses .NET's own ECDSA.** This works on Windows PowerShell 5.1 and on PowerShell 7.
  - **`c93 upgrade` does it in-process** with the key built into the binary (the same `.pem`, bundled at build time), then swaps the file. It no longer runs a downloaded script.
- **Why ECDSA P-256 and not minisign or cosign:** the stock tools on all three systems can check a P-256 signature (`openssl`, .NET, Java), so a one-line install needs nothing extra. Ed25519 would need OpenSSL 3, which macOS doesn't ship, and Windows PowerShell can't check it. Minisign and cosign need their own tool on the user's machine. Anyone can check by hand with `openssl dgst -sha256 -verify cluster93-release.pem -signature SHA256SUMS.sig SHA256SUMS`.
- **Escape hatches, both explicit:**
  - `C93_RELEASE_PUBLIC_KEY=FILE`: for a mirror that re-signs with its own key, and for the tests.
  - `C93_INSECURE_SKIP_SIGNATURE=1`: trusts HTTPS alone, with a warning. It's for a machine without `openssl`, which the installer otherwise refuses with that advice.

**Not covered:**
- **Freeze and rollback:** an attacker who can serve files could offer an older, validly signed release in place of the latest. TUF-style signed metadata with expiry would cover that; it can come when there's an update channel worth it.
- **The first `curl | sh`:** that one still trusts HTTPS and GitHub for the installer itself. To check before running, download `install.sh`, `SHA256SUMS` and `SHA256SUMS.sig` and verify them with the published key first.
- **Server images and jars:** these aren't published yet. When they are, they go in the same `SHA256SUMS`.

**Rotating the key:** move the old key away, run `scripts/release-key.sh`, and commit the new public key before the next release. Installers and CLIs from before the rotation won't accept newer releases. Install those with the new installer.

**Tests:**
- **`InstallScriptTest`:**
  - `install.sh` installs a release signed with a test key
  - it refuses one signed by another key, an unsigned one (unless skipping is asked for), and a checksum mismatch
  - `install.ps1`'s check, run under `pwsh`, accepts 20 signatures of every length and refuses another key's
  - the key in `install.sh`, `install.ps1` and the CLI matches `install/cluster93-release.pem`
- **`UpgradeTest`:**
  - `upgrade` replaces the binary only when the signature and checksum hold
  - it accepts what `openssl` itself signs, as the maintainer's script does
  - it asks for `--to` on a JVM
- **Checked by hand:** the static native binary refuses a release signed by any key but the built-in one, and with a test key it replaced itself.

### D-070 — Users, memberships, roles and groups; invites instead of passwords
**Date:** 2026-10-07 · **Status:** Accepted (Tony chose global users, invites, built-in plus custom roles, and the audit log now)

**Context:** Security groundwork, step 0, part 4. Tokens belonged to tenants and nothing knew who a person was. The rules coming next need people: who may deploy to a link, approvals, which secrets leave the cluster. And taking access away from someone meant finding every token they'd made. Design: `docs/design/identity.md`.

**Decision:**
- **Users are global:**
  - **What a user is:** one account per person, named by a handle that never changes (no renaming yet).
  - **Joining tenants:** through **memberships**, which hold the roles granted directly and the groups the member is in.
  - **Operators:** a user can be an **operator**. An operator's own token acts as the admin token does, as a named person, and still can't read tenant data (D-044 holds).
- **Roles:**
  - **Built in:** `owner` (`*` plus owner powers), `admin` (`*`), `developer` and `viewer`.
  - **Custom:** a tenant can define its own roles. Every role is a set of the existing scopes, so `RouteScopes` still enforces everything.
  - **Groups** carry roles for their members.
  - **New scopes:** `members:read`, `members:write` and `audit:read`.
- **Personal tokens act as their user:**
  - **What they may do** is computed on every request: the token's scopes (`*` meaning all of the user's) intersected with what the user's direct and group roles give in that tenant now.
  - **Taking access away** takes effect on the next request, with every token the person made. This covers demoting, removing from a group, removing from the tenant, disabling the user and taking away operator. Removing a member also deletes their personal tokens for that tenant.
  - **Cost:** four reads by key per request (token, user, membership, the tenant's access document). No SQL runs, and nothing is cached, so nothing goes stale.
- **Service tokens** (today's kind) are unchanged: fixed scopes, owned by the tenant. They now record `createdBy`.
- **No escalation:**
  - A caller grants only what their own scopes cover, both before and after the change. This covers members, groups, custom roles and invites.
  - Only owners and operators grant or remove `owner`.
  - `owner` is direct-only.
  - The last owner can't be removed, and a role in use can't be deleted.
- **Getting in: invites, not passwords.**
  - **Creating:** `members:write` holders create invites. Operators do too, including a tenant's first owner (`POST /v1/tenants/{t}/invites`) and other operators (`POST /v1/admin/invites`).
  - **The code:** an `c93_…` secret, shown once and stored only as its hash. It expires in 7 days by default (at most 30), works once, and can name the user it's for.
  - **Redeeming:** on the public `POST /v1/invites/redeem`. Without a token it creates a new user; with your personal token it adds the tenant to you. Either way you get a personal token.
  - **More tenants:** a member mints a token for another of their tenants with `POST /v1/me/tokens`.
- **Storage:** one implementation of the identity logic (`Identity`), written against a small `Records` interface (get, put, compare-and-set, lookups by indexed field). It has in-memory and Ignite versions sharing one contract test. The tables are `USERS`, `MEMBERS`, `TENANT_ACCESS` and `INVITES`.
- **Not for agents:** there are no MCP tools for identity.

**Why invites and not passwords:** there's no password database to protect, and an invite code is as strong as a token. Passkeys and OIDC need a browser, so they come with the console, as ways to get the same personal tokens.

**Known gaps:**
- **Two owners removing each other** at the same moment could leave none. An operator can invite a new owner.
- **Service tokens outlive their makers.** Only `tokens:write` holders (admins and owners by default) can make them.

**Tests:** `IdentityTest`:
- invites (once, withdrawn)
- access following the person
- every escalation path
- groups
- one user in several tenants
- operators gained and lost
- deleting a tenant

Also `RecordsContract` (in memory and on Ignite, including a field named `user`), `ContractTest` (28 more operations, 93 in all), and the CLI flow.

### D-071 — An audit log of every change
**Date:** 2026-10-07 · **Status:** Accepted (Tony)

**Decision:**
- **What's recorded:** every authenticated `/v1` request that isn't a read, whether allowed or refused, once its answer is sent. Redeeming an invite records who it became.
  - **Each entry:** time, tenant, the actor (user, token, operator or admin), the operation, the path without its query, the status, the client address and `X-Forwarded-For` as received.
  - **Ids:** time-ordered, so they double as the paging cursor.
- **Not recorded:** `publish`, `request` and `pushQueueMessage`. They're data traffic, can run thousands a second, and are bounded by rate limits instead.
- **Who reads it:**
  - **Tenants** read their own entries with `audit:read` (`GET /v1/audit`).
  - **Operators** read entries with no tenant, plus everything done by an operator or with the admin token, including inside tenants (`GET /v1/admin/audit`).
- **Retention:** 90 days (`C93_AUDIT_RETENTION_DAYS`). Every node prunes hourly, and the deletes are idempotent. Deleting a tenant deletes its entries.
- **Never in the way:** a failed write is logged, never turned into a failed request.

**Not yet:** export to S3 or a SIEM, and tamper evidence (hash-chaining entries).

**Tests:** `AuditLogContract` (in memory and on Ignite: paging, the operator view, pruning, forgetting) and `IdentityTest` (what is and isn't recorded, actors, paging, who sees what).

### D-072 — The audit log is tamper-evident; deleted tenants' entries stay until pruned
**Date:** 2026-10-07 · **Status:** Accepted (Tony asked for tamper evidence). Extends D-071, and supersedes its "deleting a tenant deletes its log"

**Decision:**
- **Each node chains its own entries.** An entry carries the node's chain id (`<node>/<process instance>`), the next number in that chain, the previous entry's hash, and its own hash over all of that.
  - **Keying:** with `C93_SECRETS_KEY` the hash is an HMAC-SHA256 under a key derived from it. Someone who can write to the database but doesn't hold the key can't forge a consistent chain. Without a key it's plain SHA-256, which still shows careless edits.
  - **Why per node:** one chain for the whole cluster would make every write wait on every other.
- **Checking:** `GET /v1/admin/audit/verify` (`c93 audit verify`) reads every entry and reports any:
  - whose contents don't match its hash
  - that doesn't link to the one before
  - number missing from the middle of a chain
  A chain starting after 1 has been pruned at its start, which is expected.
- **A deleted tenant's entries are no longer deleted with it.** They stay until normal retention prunes them, because removing them would punch holes in the chains, indistinguishable from tampering. That's also the better record when something went wrong. A new tenant that reuses the name sees only entries since it was created.

**Limits:**
- **The newest entries:** deleting entries at the very end of a chain isn't detectable from the chain alone. The export (D-073) is the outside copy that covers that.
- **Failed writes:** a write that fails after its number was given shows up as a missing number.
- **Rotating the secrets key** makes older entries fail verification.

**Tests:**
- `AuditChainTest`: an untouched log verifies; changed entries are found, as are missing ones; a pruned start isn't flagged; a forgery without the key fails.
- `IdentityTest`: the server's chain verifies after a run of real changes, tenant deletion included.

### D-073 — The audit log is exported to an S3 bucket
**Date:** 2026-10-07 · **Status:** Accepted (Tony chose S3)

**Decision:**
- **Turning it on:** set `C93_AUDIT_S3_BUCKET`, and the cluster copies new entries there every `C93_AUDIT_EXPORT_INTERVAL_S` (300). The other settings are `C93_AUDIT_S3_ENDPOINT`, `_REGION`, `_PREFIX`, `_PATH_STYLE`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` and `_SESSION_TOKEN`. Each falls back to `C93_S3_*`, then the AWS variables.
- **The files:** NDJSON, oldest first, at `<prefix>audit/YYYY/MM/DD/<first id>_<last id>.ndjson`. SIEMs ingest from S3.
- **One node at a time:** a node holds a lease, a compare-and-set record that also keeps the cursor (the last entry exported). If that node dies, the lease runs out and another node continues.
- **The settle window:** entries younger than a minute wait for the next round, so one recorded a moment late on another node isn't skipped. Node clocks must be kept in sync (NTP).
- **Pruning waits for the export:** with an export, nothing is pruned before it has been copied out.
- **It uses the S3 client already written for files (D-056).**

**Tests:** `AuditExporterTest`, against a real S3 server in Docker:
- entries go out once
- recent ones wait
- later rounds pick up new ones
- only one node exports at a time

### D-074 — Identity refinements: developers set secrets, renames, the owner guard, flagged service tokens
**Date:** 2026-10-07 · **Status:** Accepted (Tony). Amends D-070

**Decision:**
- **`developer` gets `secrets:write`.** Withholding it protected nothing, since developers' scripts can already read every secret with `secrets:use`. It only got in the way.
- **Users have a stable id** (`usr_…`) as well as a name.
  - **What holds the id:** memberships, tokens and audit entries. The audit entry also keeps the name at the time.
  - **Renaming:** `PATCH /v1/me {name}` (`c93 profile --name`) or, for operators, `PATCH /v1/users/{name} {name}` (`c93 users rename`). Everything follows.
  - **Reserving names:** a name is held in its own record with put-if-absent, so two users can't take it at once.
- **The last owner is guarded atomically.**
  - The tenant's access document keeps the set of owners. Every grant or removal of `owner` goes through that document's compare-and-set, which refuses to empty it.
  - This closes D-070's known race: two owners removing each other at once. `IdentityTest` runs that race five times and always keeps exactly one owner.
- **Service tokens outlive their makers, flagged** (Tony's choice over tying them to their maker):
  - **They keep working:** they belong to the tenant.
  - **Removing a member, or deleting a user,** answers with the service tokens they made, to keep or revoke.
  - **Token listings** show the maker's name and `makerGone` when the maker is no longer an enabled member.

**Tests:** `IdentityTest`:
- renaming, by the user and by an operator
- the owner race
- flagged tokens
- developers setting secrets

Also the CLI flow.

### D-075 — A file system on top of the file store
**Date:** 2026-10-07 · **Status:** Accepted (Tony). Design: `docs/design/vfs.md`

**Context:** The file store (D-040, D-056, D-057) holds tenants' files well, but as a flat set of paths: no folders, rename, append, scratch space or Node-style `fs`. Modules, Node compatibility, apps and the console's file manager all need those. 2017's Cluster93 had a per-user file system; this brings it back on today's store.

**Decision:** a layer on the existing store, not a new store.
- **The key fact:** blobs are named by an internal id, never by path. So rename, move and copy only rewrite metadata, on every backend, S3 included.
- **What it adds:**
  - folders (D-076)
  - move and copy, sharing blobs with a reference count; whole-folder operations under a cluster-wide lease and a journal
  - mounts replacing prefix routes
  - `/tmp` per job
  - a read-only `/packages`
  - an `fs` host API with synchronous calls
  - append and edits in place, as new versions
  - advisory locks
  - expiring signed public links
- **Tony's answers:**
  - copies share blobs, counted by a reference count, and the quota counts each blob once
  - `/tmp` uses memory first, then spills to the data directory
  - public links stay pinned to the version they were made for
  - links are revoked all at once, by rotating a per-tenant secret
  - moving bytes between providers comes later
  - the name stays "files"
- **Cluster-friendly:**
  - Every record (paths, folders, reference counts, journals, locks, mounts) lives in the replicated database.
  - Public links verify themselves.
  - Only `/tmp` is local to a node, by design.
- **Built in five parts,** each tested and committed on its own.

### D-076 — Folders are real rows, made as files are written
**Date:** 2026-10-07 · **Status:** Accepted. Part 1 of D-075; changes the first draft's "implied" folders

**Decision:**
- **A `DIRS` table** with one row per folder (tenant, path, parent, name).
  - **The `FILES` table** gains `parent` and `name` columns, indexed by (tenant, parent, name).
  - **Listing one level** merges a folder's subfolders and files by name, paged by the last name.
- **Writing a file makes its missing folders** (as `mkdir -p` does). A folder's ancestors always exist, so the check usually costs one read.
- **Folders stay when emptied,** and `rmdir` removes an empty one.
  - **Why not S3-style folders implied by their files:** listing those needs a skip-scan per subfolder, empty folders can't exist, and folders vanish when their last file is deleted, which surprises file-system users.
- **A name is a file or a folder, never both.** The codes are `is_a_directory`, `not_a_directory`, `already_exists` and `not_empty`, all 409 in the API, and `not_found` is 404.
- **Upgrading existing data:**
  - **New columns:** `Table` now adds columns (and the indexes using them) to an existing table with SQL DDL at start, since Ignite keeps an existing cache's old definition.
  - **The backfill:** every node then backfills, giving older files their `parent` and `name` and making their folders. It's idempotent.
- **API:**
  - **REST:** `GET /v1/dirs`, `GET /v1/dirs/{path}`, `PUT /v1/dirs/{path}` (201 made, 200 already there) and `DELETE /v1/dirs/{path}` (`files:read` and `files:write`). The prefix listing `GET /v1/files?prefix=` is unchanged and lists files only.
  - **CLI:** `files ls DIR` lists one level, with folders ending in `/`. That's a change: `files ls -R` gives the old every-file listing. Also `files mkdir` and `files rmdir`.
  - **MCP:** `list_dir` and `make_dir`.
- **Scripts** get folders with the `fs` module in part 4. `files.*` is unchanged.

**Tests:**
- `FileStoreContract` on the in-memory, Ignite, disk and S3 stores:
  - writing makes folders, and listing shows one level
  - empty folders, and `rmdir` only when empty
  - file/folder conflicts
  - paging
- `TreeMigrationTest`: an old table gains columns and an index; old file rows are backfilled into the tree, once.
- `FilesTest` (two nodes: made on one, listed on the other, plus the conflicts), `ContractTest` (four operations), the CLI and MCP.

### D-077 — Move and copy without moving bytes; folder operations are journaled
**Date:** 2026-10-07 · **Status:** Accepted. Part 2 of D-075

**Decision:**
- **Moving a file** writes its row at the new path, naming the same blob, and removes the old row. That happens only if the old row is unchanged. If the file was replaced meanwhile, the new version stays and the moved one counts as a copy.
  - **No bytes move,** on any backend, because reads follow the backend recorded on the file.
- **Copying a file** writes a second row naming the same blob, so copies take no room and count once against the quota. The quota sums each blob once, grouping rows by blob id.
- **Reference counts** (`BLOB_REFS`: blob id → count) decide when bytes are deleted:
  - **No count yet means one:** its own row. So existing blobs need no migration, and plain writes cost nothing extra.
  - **Changes:** a copy adds one. Deleting a file, or replacing its version, takes one away; at zero the blob is queued for deletion after the readers' grace.
  - **A zero count is kept, not removed.** The collector checks the count before deleting, so a copy made just as the last reference went keeps the bytes.
  - **Uploads that never finished** (no count, a garbage row) are deleted as before.
- **Folders are moved, copied and deleted as tree operations:**
  - **Journal and lease:** a journal record (operation, source, target) doubles as a per-tenant lease. One folder operation runs per tenant at a time, and others get 409 `busy`. Single files never wait.
  - **Order:** folders are made parent first. Files then go one by one, renewing the lease every 200. A move then removes the emptied source folders, deepest first.
  - **Crash-safe:** every step can be repeated, by skipping a file already moved (source gone, target there) or already copied (target names the same blob). If a node dies, another finds the expired lease and runs the operation again from the start.
  - **Limits:** at most 100 000 entries per operation. A folder can't go inside itself.
  - **Folder targets:** a folder target that exists is refused unless `overwrite` is set, which merges into it. A file target is always refused.
- **API:**
  - **REST:** `POST /v1/fs/move` and `POST /v1/fs/copy` take `{from, to, overwrite}`, for files and folders alike. `DELETE /v1/dirs/{path}?recursive=true` deletes a folder and everything in it.
  - **CLI:** `files mv`, `files cp` (`-f` to overwrite or merge) and `files rm -r`.
  - **MCP:** `move` and `copy`.
- **Readers during a folder move** may briefly see part of it under each name. Each file is always whole at one of them.

**Tests:**
- `FileStoreContract` on the in-memory, Ignite, disk and S3 stores:
  - a copy shares bytes, counts once and outlives its original
  - a move changes only the path
  - overwrite rules, and the replaced version is collected
- `FileTreeTest`:
  - move, copy and delete a folder
  - a folder inside itself, merging and file targets
  - one operation per tenant
  - another node finishing a move whose node died midway
- `FilesTest`, across two nodes: a folder copied counts once against the quota, and moved without moving bytes.
- `ContractTest`, CLI and MCP.


### D-078 — Mounts: folders bound to providers, optionally read-only
**Date:** 2026-10-07 · **Status:** Accepted. Part 3 of D-075

**Decision:**
- **What a mount is:** a storage route (D-057) whose prefix is a folder (`photos/`). The routes record stays the one source, and gains a set of read-only mounts.
  - **The mount API is a view of it:**
    - `GET /v1/mounts`
    - `PUT /v1/mounts/{path}` `{provider, readOnly}`, which makes the folder; 201 new, 200 changed
    - `DELETE /v1/mounts/{path}` (`storage:read` and `storage:write`)
  - **The routes API works as before,** and keeps read-only flags on mounts it leaves in place.
- **Unmounting moves nothing.** Files stay readable where their recorded backend is, as when routes change today.
- **One check for every write.**
  - **Making something** (uploads, scripts' writes, copy and move targets, `mkdir`) is refused under a reserved name: `tmp`, `packages`, `.packages` (`reserved`). The same goes for anything under `mnt/` that isn't a mount, and anything beneath a read-only mount (`read_only`). Both are 403.
  - **Removing something** (deletes, move sources, `rmdir`) is refused only beneath a read-only mount. A tenant's existing files under a now-reserved name stay readable and deletable.
- **Visible:**
  - folder entries that are mounts carry `mount: {provider, readOnly}`
  - file entries carry `storage`: `default`, or the provider's name
  - the CLI's `files ls` shows a STORAGE column
- **CLI:** `storage mounts`, `storage mount FOLDER --provider NAME [--read-only]`, `storage unmount FOLDER`.

**Tests:**
- `StorageProvidersTest`:
  - mount a disk provider; files go there and show it
  - reserved names
  - read-only refuses writes, deletes and moves, but allows copying out
  - after unmounting, the file is still readable
- `FileStoreContract` checks reserved names on all four stores.
- `ContractTest` (three operations), and the CLI.


### D-079 — `fs` for scripts, synchronous calls included, and each job's `/tmp`
**Date:** 2026-10-07 · **Status:** Accepted. Part 4 of D-075

**Decision:**
- **One tree:** a new host API, `FsApi`, presents the tenant's files as one tree from `/`, with the job's `/tmp` inside it.
  - **Backed by:** the store (folders, D-076), single-file moves and copies through the store, and folder moves through tree operations (D-077). The quota is counted as uploads finish, as `files.write` does.
  - **Moving between `/tmp` and stored files** copies the bytes, since `/tmp` can't share blobs.
- **The `fs` module** is Node-shaped:
  - **Calls:** `readFile`, `writeFile`, `appendFile`, `mkdir`, `readdir`, `stat`/`lstat`, `access`, `rename`, `copyFile`, `rm`, `rmdir`, `unlink`, `existsSync`.
  - **Styles:** promises (`fs.promises`), callbacks and `…Sync` forms.
  - **Errors** carry Node's codes, with the cluster's code in `reason`.
  - **Where it lives:** it's a global, and importable as `c93:fs`, `fs`, `node:fs`, `fs/promises` and `node:fs/promises`. A small module resolver serves just these until packages exist (modules step).
- **Synchronous calls block the isolate's own thread,** with a 30 s limit. Isolates run on dedicated worker threads, so only that script waits. A failure is returned as an Error value and thrown in JavaScript, so Javet's exception path isn't involved.
  - **The cost, documented:** timers and other requests in the same isolate wait.
  - **Not interruptible:** a stop request takes effect when the blocked call returns, at most 30 s later.
- **`/tmp` (Tony: memory, spilling to disk):**
  - **Memory first:** up to `C93_TMP_MEMORY_MB` (8 MB) per job, refused when the heap passes 85%.
  - **Then disk:** `<data dir>/tmp/<job>/`, up to `C93_TMP_MB` (1 GB), within `C93_MIN_FREE_DISK_MB`.
  - **Nodes without a data directory:** memory only, 64 MB.
  - **Cleanup:** it's deleted when the job ends, and the whole folder is cleared at node start. It's never replicated or counted against the quota.
- **Append and limits:**
  - `appendFile` writes a new version (old bytes plus new), up to 64 MB.
  - `readFile` reads up to 32 MB; bigger files stream with `files.stream`.
  - Positional writes come with part 5.

**Tests:**
- `TmpStoreTest`: folders, renames, the memory limit, spilling to disk, cleanup.
- `FsTest` (scripts on a node):
  - imports by Node's names
  - sync and promise calls, appending, folder rename
  - the file seen through the REST API
  - Node error codes, including a reserved name
  - `/tmp` private per job and gone afterwards, and never in the store


### D-080 — File handles, advisory locks and public links
**Date:** 2026-10-07 · **Status:** Accepted. Part 5 of D-075, which completes it

**Decision:**
- **File handles:**
  - **What they give:** `fs.promises.open` returns a handle, and `fs.openSync` with `readSync`, `writeSync`, `fstatSync`, `ftruncateSync` and `closeSync` returns a descriptor number. The callback forms `fs.open`, `read`, `write` and `close` work too. All of these allow positional reads and writes, appending and truncating.
  - **How:** a handle holds the file's bytes in the script, at most 32 MB. Closing it publishes one new version through the store's atomic write, so readers never see a half-made edit. A handle opened read-only refuses writes with `EBADF`.
  - **Why not edit in Java:** the bytes would cross the bridge on every small write, and publishing on close is what gives atomicity anyway.
- **Advisory locks** (2017's per-file locks):
  - **How to take one:** `fs.lock(path, {ttlMs})` returns `{expiresAt, renew(), release()}`. It's a cluster-wide lease on `Records` compare-and-set, keyed by tenant and path.
  - **Contention:** another holder gets `EBUSY` until the lease is released or expires, and an expired lease can be taken over.
  - **Limits:** at most an hour per lease, renewable; 10 000 per tenant.
  - **Cleanup:** a job's locks are released when it ends.
  - **Advisory:** writes don't check locks, since each write is atomic already. Locks coordinate multi-step work.
- **Public links** (2017's public file tokens):
  - **Making one:** `POST /v1/fs/share` with `{path, expiresInSeconds}` (default an hour, at most 30 days) returns `{url, token, expiresAt}`. The CLI is `c93 files share PATH --expires 1h`.
  - **Fetching:** anyone can `GET` or `HEAD /v1/shared/{token}`, with no token, getting Range, ETag and `Cache-Control: private, no-store`.
  - **The token carries everything:** tenant, path, file version and expiry, signed with an HMAC under a key derived from `C93_SECRETS_KEY` and a per-tenant secret. Nothing is stored per link, and any node checks it. Without a secrets key, links are unavailable (409).
  - **Pinned to the version it was made for** (Tony): replacing the file stops the link.
  - **Revoking:** `POST /v1/fs/share/rotate` (`files share --revoke-all`) changes the tenant's secret, which revokes every link at once (Tony).
  - **Who can make one:** it needs `files:write`, since publishing a file is a change, and it's audited.
  - **Not for agents:** there's no MCP tool, so agents don't publish files.

**Tests:**
- `FsTest`:
  - positional edits published only on close
  - append handles
  - read-only handles
  - locks: `EBUSY`, released, and gone with the job
  - links: fetched without a token, tampered, stopped by a new version, revoked by rotation
- `FileLocksTest`: expiry, takeover, renewal, per-tenant names, the TTL limit.
- `ContractTest` (four operations) and the CLI.

### D-081 — S3 downloads carry on where they stopped if the connection closes
**Date:** 2026-10-08 · **Status:** Accepted. Resolves the intermittent parked in NOTES since 2026-10-07

**Evidence:**
- **Seen twice** in test runs: `StorageProvidersTest` lost the end of a 6 MB download from a tenant's S3 bucket, and the client saw "Connection was closed".
- **Reproduced:** a node serving the file to 6 clients at once failed in 4 to 6 of 360 downloads, always partway and near the end (5.5 to 6.2 MB of 6.3 MB), 50 to 200 ms in.
- **Ruled out:**
  - **Idle connections:** there was no idle time.
  - **Slow reading:** the gateway tolerates pauses of 35 s.
  - **The pinned client:** it was fine.
  - **Calling resume from another thread:** still failed when resumed on the response's own thread.
- **The cause:**
  - **The server closes early.** The S3 gateway sends `Connection: close` and closes as soon as it has sent the last byte.
  - **The reader is still paused.** When that close arrives while some chunks are still buffered behind a pause, Vert.x reports the close as a failure and drops those chunks.
  - **Why the node is exposed:** a node forwarding a file pauses its S3 response after every chunk, as backpressure towards a slower client. That's why direct downloads never failed and node downloads did.
  - **Beyond the test gateway:** AWS keeps connections open, but other S3-compatible servers, or proxies in front of S3, may close like this.
- **Diagnosis was slow** because the node cut the client's connection without saying why.

**Decision:**
- **Resume instead of failing.** A ranged S3 download (`ResumingRangeBody`) that closes or ends early asks for the rest with a new range request from the byte it reached, up to 3 times. Range GETs are safe to repeat. Chunks that arrive late from the closed response are ignored, so every byte is delivered once, in order. This is what AWS's own SDKs do.
- **Log it.** `FileResponder` now logs a warning with the cause whenever a file fails after its headers have gone out.
- **Streaming stays as it is.** Forwarding chunk by chunk, with backpressure, is what keeps a node from holding whole files in memory.

**Tests:** `S3BlobStoreTest.aReaderThatPausesEveryChunkGetsTheWholeFile` reads 120 6 MB downloads that way, pausing on every chunk and resuming from another thread, and checks every byte. Before the fix it failed about 2% of reads. A throwaway node-level probe went from 4 to 6 failures in 360 to none.

### D-082 — While a folder operation runs, readers see its finished result
**Date:** 2026-10-08 · **Status:** Accepted (Tony chose this over folders by id, or blocking). Supersedes D-077's "readers may briefly see part of a moving folder under each name"

**Decision:**
- **One wrapper over the store,** `MoveAwareFileStore`. The REST API, scripts' `files` and `fs`, agents and public links all use it. Only `FileTree` works on the store beneath, with the real paths.
- **Reads consult the tenant's folder-operation journal** (D-077), one key lookup, and answer as things will be once the operation ends:
  - **move:** the source is gone at once. The target shows every file, moved yet or not. A file still at the source is read from there under its new path, since reads go by blob.
  - **copy:** the target shows the whole copy at once.
  - **delete:** the folder is gone at once.
- **Writes wait.** Anything that touches either folder gets 409 `busy` (`EBUSY` in scripts) until the operation ends: uploads, deletes, `mkdir`, `rmdir`, moves and copies. That includes a single file moved by `FileTree`.
- **The old prefix listing** (`GET /v1/files?prefix=`) also gets `busy` while a folder it covers is changing, since it can't be shown "as it will be" page by page. The one-level listing, `/v1/dirs`, can.
- **Why not folders by id** (inode-style, where moving a folder is one row change): it would be truly instant, but every path lookup would walk the folders, listings would change, and existing data would need migrating. That's a bigger change than this problem needs, so it stays an option for later.

**Cost:**
- **Reads** need one extra key lookup per file call.
- **Merged listings:** while a move runs, a target folder's listing merges two folders.
- **Overwrite merges:** during a copy with overwrite, a target file that exists already shows its old version until the copy reaches it.

**Tests:** `MoveAwareFileStoreTest` freezes a move halfway and checks:
- the source is gone, the target is complete, unmoved files are readable at their new paths
- writes touching either folder are refused, and others aren't
- the prefix listing waits
- a copy shows whole and a delete shows gone

The existing file tests all pass through the wrapper.

### D-083 — Packages: versioned JavaScript published to the cluster, resolved once per job
**Date:** 2026-10-08 · **Status:** Accepted (Tony). Design: `docs/design/packages.md`. Not built yet

**Decision:**
- **The unit:** a **package** is an immutable version of ES-module files with a manifest of SHA-256 hashes. Users call them "packages" (Tony).
- **Pushed, never pulled:**
  - **Publishing:** developers publish with `c93 packages publish`, which bundles npm dependencies with the project's own esbuild on their machine (Tony) and uploads only the files the cluster lacks.
  - **No registry access:** the cluster never runs `npm install` or reaches a registry.
- **Storage:**
  - **Manifests** go in a replicated `PACKAGES` table, recording who published.
  - **File contents** are stored once per hash (`PACKAGE_BLOBS`) in the cluster's own storage, outside tenants' file trees.
  - **In `fs`:** `/packages` is a read-only view.
  - **Shared later:** apps' deployments can share the store.
- **Visibility:** private to the tenant, or cluster-wide from operators (Tony).
- **Resolved once, at submission:** into a lock saved on the job (at most 500 packages), so retries run identical code. Services run from a package version, which is their revision.
- **Loading:** files are fetched, verified and cached per node by hash before the script starts. The isolate's resolver only maps names, and refuses URLs, absolute paths and anything not in the lock.
- **Native code:** it's detected when publishing and pointed at the Node runtime, which is designed separately. `cluster93.json` has a `runtime` field from day one.

**Open:** whether inline scripts may import packages, and how. Tony is undecided, and it can be added later.

### D-084 — The Node runtime: plain Node in gVisor containers, built where the developer is
**Date:** 2026-10-08 · **Status:** Accepted (Tony). Design: `docs/design/node-runtime.md`. Not built yet; it comes after packages (D-083)

**Context:** an isolate can't load native addons or use Node's process and network APIs. Packages detect that and send it here.

**Decision:**
- **Three runtimes,** enforced by placement:
  - `isolate` (today)
  - `node`: a container on nodes that offer it
  - `node` unsandboxed: only on a link the tenant owns, opted in, for that tenant's code only
- **The engine** (Tony): official Node with our own runner package, not Javet's Node mode.
  - **Why:** npm's prebuilt native binaries work, any LTS works the day it ships, and containers are small and start fast.
  - **The cost:** a second implementation of the globals, kept identical by a conformance suite shared with the isolate.
- **Builds** (Tony): `npm ci` runs on the developer's machine.
  - **When Docker is involved:** only when native addons need it, in our builder image matching the cluster's Linux, CPU, C library and Node.
  - **Uploading:** files go into the package store, content-addressed, so redeploys upload what changed.
  - **Nothing runs npm on the cluster.** A cluster build worker, in gVisor with network access to npm only, comes later.
  - **Building and running are separate:** the developer's container only builds matching binaries; cluster nodes run them.
- **The execution layer, built once for containers and links:**
  - **Capabilities and placement:** nodes advertise capabilities, owner and tier, and placement filters on them.
  - **One runner protocol** (JSON-RPC over a WebSocket) carries host-API calls, logs, state and HTTP.
  - **The tenant comes from the job record,** so scopes, quotas and egress rules are identical everywhere.
  - **Unchanged:** jobs, orphans and the ingress.
- **Containers** (Tony: rootless Podman):
  - **Lockdown:** a read-only root and app, a tmpfs `/tmp`, no capabilities, a user namespace, cgroup limits.
  - **Network:** outbound traffic goes through the node's egress proxy, which applies D-026.
  - **Artifacts:** materialized once per version and mounted read-only.
- **Isolation** (Tony): gVisor by default for tenant code; runc only for tenants the operator marks trusted.
- **In scripts:**
  - **Globals:** the same as the isolate.
  - **`fs`** is the container's own filesystem (Tony). The tenant's files are reached through `files` and `c93:fs`, and FUSE at `/files` comes later.
  - **HTTP:** `app.listen(PORT)` works.
  - **Secrets in the environment:** resolved at start and audited.
- **Jobs and services both run in Node** (Tony). The container start time is documented, and a warm pool comes later if needed.

**Order:**
1. The execution layer, proven with a local-process runner against the isolate's conformance scripts.
2. The runner and images.
3. Containers.
4. Deploy.
5. Links.

Spikes measure runner latency, rootless Podman with gVisor and `sharp`, and artifact sizes first.

### D-085 — Holes found reviewing the packages and Node runtime designs, and how they're closed
**Date:** 2026-10-08 · **Status:** Accepted (Tony chose the four marked). Amends D-083 and D-084; details in `docs/design/packages.md` §12 and `docs/design/node-runtime.md` §11

**Packages (D-083):**
- **Dependency confusion** (Tony): cluster-wide packages live under the reserved `@cluster/` prefix, which tenants can't publish to. A bare name only finds the tenant's own package.
- **Quota:** package files count against the tenant's quota, deduplicated per tenant only. Sharing across tenants would let anyone test whether another tenant holds a file.
- **Pinning:**
  - Child jobs from `jobs.run` inherit their parent's lock.
  - Services are pinned at deploy, on the revision.
- **Packages reading their own files:** `import.meta.url` and `/packages` reads are answered from the job's lock. WebAssembly is allowed.
- **Native detection:** optional native extras (`bufferutil`, `fsevents`) are stubbed with a warning. Only required native code blocks a publish.
- **Bundling:** for `--platform=node`, with source maps.
- **Housekeeping:**
  - The node's package cache is bounded (least-recently-used first, within the free-disk minimum).
  - Deleting a tenant removes its packages.

**Node runtime (D-084):**
- **Network** (Tony): containers get none. The runner tunnels Node's `net`, `tls`, `dns` and `http` through its socket, and the cluster opens each connection under the egress rules (D-026). Pure-JavaScript drivers (`pg`, `mysql2`, `ioredis`) work; networking inside native code and UDP don't.
  - **Why:** an HTTP proxy can't police database traffic, and filtering a real network needs privileges rootless Podman doesn't have.
- **WebSockets** (Tony): the ingress proxies `Upgrade` requests, for both runtimes, and the isolate's `ctx.serve` gains WebSocket handlers.
- **Off by default** (Tony): the operator enables the Node runtime per tenant, in a runtime policy that also sets the isolation tier and the limits on containers and memory.
- **Orphans:**
  - Containers are labelled with their node's process id, and removed when a node starts.
  - The runner exits when its socket goes.
  - Calls carry the attempt number, so a stale container can't write.
- **Placement:**
  - Container capacity is separate from isolate slots, with a memory budget.
  - Artifacts record their CPU, C library and Node major version, and placement matches them.
  - Work no node can run is refused with the reason.
- **Supply chain:** runtime and builder images are pinned by digest in the signed release (D-069), and can come from an operator's mirror or be preloaded.
- **Smaller fixes:**
  - **Serving:** readiness and health checks for app servers.
  - **Builds:** a lockfile is required; our packages can be copied into `node_modules` at build.
  - **Child jobs** inherit the runtime and artifact.
  - **Secrets:** environment secrets need `secrets:use`, are audited, and are documented as visible to every dependency.
  - **Containers:** `/tmp` counts against container memory; artifacts are evicted when disk runs low; stopping is SIGTERM, then grace, then kill.
  - **Testing:** the shared conformance suite is built with the execution layer.
  - **The docs:** the claim that switching runtimes is "one line" is qualified for `fs`.
- **Checked in spikes:** gVisor's host-socket setting (`--host-uds`), and the tunnelled networking's throughput.

### D-086 — A second pass for holes in packages and the Node runtime
**Date:** 2026-10-08 · **Status:** Accepted (Tony chose the two marked). Amends D-083, D-084 and D-085; details in `docs/design/packages.md` §13 and `docs/design/node-runtime.md` §12

**Packages (D-083):**
- **Block** (Tony): a blocked version refuses to load anywhere, even from an existing lock, and services running it stop with "version blocked". Owners, admins or operators can block; it's audited and can be lifted. **Why:** yanking only stops new resolutions, so a version with a security hole kept running.
- **Purge:** an owner or operator deletes a version's files outright, and whatever is locked to it fails loudly. Audited and final. **Why:** a version published with a secret in it could otherwise never be removed.
- Both apply to Node artifacts, which share the store.

**Node runtime (D-084):**
- **Artifacts are layers** (dependencies, then source), not individual files. **Why:** a `node_modules` of 20 000 to 40 000 files means tens of thousands of uploads and a slow first fetch on each node.
- **Spawned programs** get a local proxy endpoint (`HTTP(S)_PROXY`) through the same tunnel and egress rules; **`dns.resolve*`** is rerouted as well as `dns.lookup`.
- **The runner isn't trusted:** security never depends on it, because the container has no network and the cluster checks every call against the job's tenant and attempt.
- **Host setup:** rootless Podman with gVisor and cgroup limits is checked in the gVisor spike, and a node offers `node:gvisor` only after its own start-up check passes. `C93_CONTAINER_MEMORY_MB` sets the container budget, and a node refuses to start if it plus the Java heap exceeds the machine's memory.
- **One running copy per service** (Tony): no replicas or load balancing at first, a short gap on redeploy, and only HTTP and WebSockets inbound. Replicas and zero-downtime deploys come as their own step.

**Still open:** inline scripts importing packages (D-083).

### D-087 — Inline scripts can import packages; the server finds the imports
**Date:** 2026-10-08 · **Status:** Accepted (Tony). Settles the question D-083 left open; details in `docs/design/packages.md` §10

- **What:** a job sent as source text can `import` the tenant's packages and `@cluster/` ones. At submission the server reads the script's static imports and literal `import('…')` calls, resolves each to the newest version that isn't yanked or blocked, and saves the lock on the job.
- **Refused at submission:** imports whose name is computed at runtime, and packages that need native code.
- **Off switch:** a job with `"packages": false` can't import packages at all.
- **Not planned:** declaring version ranges on an inline job; it can be added if needed.
- **Why:** quick scripts and agents should just work without declaring anything, and the node must know every package before the script starts.

### D-088 — Packages step 1: the store and publishing, as built
**Date:** 2026-10-08 · **Status:** Accepted. Builds step 1 of D-083; details in `docs/design/packages.md` and `docs/guides/packages.md`

- **Storage:** versions and unique files are two `Records` collections (`PACKAGE_VERSIONS`, `PACKAGE_BLOBS`), so one contract test covers the in-memory and Ignite stores. The bytes go to the cluster's own blob store (chunks, disk or S3), never a tenant's provider. Cluster packages are owned by `@cluster`, which can't be a tenant id.
- **The API:** `POST /v1/packages` publishes a manifest (the design's `/v1/packages/{name}/versions` became this, since the name is in the manifest). A cluster package's name is percent-encoded in paths (`%40cluster%2Fdates`), so every route keeps one segment per name.
- **Checked at publish, not later:** every file uploaded; at most 1,000 files and 10 MB per version, and 100 dependencies; and every dependency range met by a published, non-yanked version. A cluster package may depend only on cluster packages, since every tenant must be able to load it. **Why:** a version never changes, so a mistake caught after publishing can't be fixed in place.
- **Ranges:** exact, `^`, `~` and `x` ranges, as npm reads them; a pre-release matches only a range naming it exactly. Others (`>=`, `||`, hyphen ranges) are refused with the reason.
- **Scopes:** `packages:read` (every built-in role) and `packages:write` (owner, admin, developer). Operators read and publish cluster packages only.
- **Audit:** publishing is audited; file uploads and the missing-files query aren't, since the publish names every file and they'd flood the log.
- **Quota and deletion:** a tenant's package files count against its quota, once per unique file. Packages don't stop a tenant from being deleted; deleting it deletes them and their bytes.
- **Bundling:** the CLI runs a small script with the project's own Node and esbuild (`--platform=node`, ESM, splitting, linked source maps, `.wasm` as files). It refuses required native code (a `.node` import, or a package with a native build), naming what pulled it in; stubs `bufferutil`, `utf-8-validate` and `fsevents` with a warning; and keeps `fs`, `c93:*` and declared Cluster93 packages as imports.
- **Not yet:** Node built-ins other than `fs` stop a publish until step 2 brings stand-ins for `path`, `events`, `buffer`, `url` and `util`. An upload cut off by a node crash can leave unreferenced bytes behind; collecting them is step 4's garbage collection. `--no-bundle` publishes a folder as it is, for code that's already ES modules.

### D-089 — Packages step 2: jobs import packages, locked at submission and loaded before they run
**Date:** 2026-10-08 · **Status:** Accepted. Builds most of step 2 of D-083 and D-087; details in `docs/design/packages.md` §4–5 and `docs/guides/packages.md`

- **The spike** (Javet 6.0.2), and what it changed:
  - **Cycles work** once the resolver returns the same module for the same file. Javet's own module cache doesn't do that for modules a resolver returns, so the resolver keeps its own, keyed by a canonical name: `file:///packages/<name>/<version>/<path>`.
  - **A refused import surfaces the resolver's exception message.** Javet only calls the resolver when it gets a name for the import, so a refused import is given a name no module has; given none, Javet answers its own "Cannot find package".
  - **A module that throws while it loads rejects the promise its evaluation returns.** The runtime used to ignore that promise, so a top-level `throw` showed up as a confusing "Cannot access … before initialization". It now waits for it (top-level `await` included) and fails the job with the error and its stack, which names the module and line.
  - **Neither `import()` nor `import.meta` is supported:** `import()` rejects with "Not supported", and `import.meta.url` is `undefined`. So each module's source is rewritten before it's compiled. `import('x')` with a literal name becomes `Promise.resolve(ns)`, where `ns` comes from a static `import * as` added at the end, and `import.meta` becomes the module's own `{url}`. Every line keeps its number. **The cost:** a module imported with `import()` is evaluated up front with the others, not when the call runs. **Why that's acceptable:** D-083 already requires every import to be known and fetched before the script starts. A computed `import(x)` still rejects when called.
- **Finding the imports:** a tokenizer (`Imports`), not a parser. It skips strings, comments, templates and regular expressions, and recognises every `import` and `export … from` form. Whether a `/` starts a regular expression is guessed from the token before it, as JavaScript tools commonly do. **Why not V8 itself:** an isolate per submission costs far more than reading the text. Whatever the scan misses still can't load anything outside the lock.
- **The lock:** `{name: version}` on the job (`packages`, shown in the API) and on a service's revision.
  - **One version per package:** the newest release that meets every range on it, from the script (any release) and from each chosen version's dependencies. It's re-picked until nothing changes. A conflict names each range and who asked for it.
  - **Limits:** at most 500 packages; a subpath the version doesn't export is refused at submission.
  - **Not lockfile-style backtracking.** It isn't needed while every package's ranges come from its own manifest; revisit if real conflicts show up.
- **Who gets which lock:**
  - **Jobs from the API:** locked at submission. `"packages": false` refuses any package import (D-087).
  - **Jobs started by a script** (`jobs.run`) inherit the parent's whole lock if they import anything, and are refused if they import a package the parent's lock doesn't have.
  - **Services** are locked when defined. A redeploy resolves again, and new versions mean a new revision. Services built from a package version are still step 3.
- **Refused at submission (400 `unresolved_import`), each with the reason:**
  - an `import()` without a literal name, giving its line
  - Node's own modules (`path`, `node:crypto`)
  - paths and URLs
  - names that can't be packages
  - unknown packages
  - unmet or conflicting ranges
- **Loading:** before an isolate is made, the node fetches every file in the lock, at most 1,000 files and 10 MB per job.
  - **Integrity:** each file is checked against its SHA-256; one that doesn't match is fetched again once, then fails the attempt ("doesn't match its SHA-256 … so the job didn't run").
  - **The cache:** checked files are cached per node by owner and hash, `C93_PACKAGE_CACHE_MB` (64 by default), least recently used out first. It's keyed by owner too, so whether a file is cached never tells one tenant about another's files.
  - **A failed load** fails the attempt before any script runs. The `cluster93.packages.load` timer records how long loading took and whether it worked.
- **Resolving in the isolate:**
  - **Bare names** come from the lock through the package's `exports`. A package may import only its declared dependencies (and itself).
  - **`./` and `../`** stay inside the package. The job's script has no files beside it.
  - **Only `.js`, `.mjs` and `.cjs` files** are modules. Other files (`.json`, `.wasm`) are read through `/packages` in `fs`, which is step 3.
- **Not yet:**
  - stand-ins for Node's `path`, `events`, `buffer`, `url` and `util`, so bundled npm code that uses them can be published (next)
  - a disk tier for the cache (the design's "then from the data directory")
  - services from a package version, `/packages` in `fs` and the MCP tools (step 3)
  - yank, block and purge (step 4)

### D-090 — Stand-ins for Node's path, events, buffer, url and util in the isolate
**Date:** 2026-10-08 · **Status:** Accepted. Finishes step 2 of D-083; follows D-088's "Not yet"

- **What:** scripts and packages can import `path`, `events`, `buffer`, `url` and `util`, with or without `node:` (and `path/posix`). They're small ES modules shipped with the runtime and compiled only when imported. Publishing keeps them as imports instead of refusing them, and submission doesn't count them as packages.
- **One module per built-in:** every name a built-in is imported by maps to one module, so `events` and `node:events` give the same `EventEmitter`, and `instanceof` works across packages.
- **How faithful:**
  - **Checked against Node:** `path`, `url`, `Buffer` and `util` were checked against Node 26's own modules on about 500 cases (normalisation, parsing, relative URLs, every encoding, number reads and writes, `inspect`, `format`), and all matched.
  - **`events`** is Node's constructor-function `EventEmitter`, so older `EventEmitter.call(this)` with `util.inherits` code works as well as `class … extends`.
  - **`url`** is a compact WHATWG `URL` and `URLSearchParams`, since V8 has none. Hostnames are lowercased but not converted to punycode, and IPv6 isn't normalised. The legacy `parse`, `format` and `resolve` are included.
  - **`util.inspect`** is a readable approximation of Node's output.
- **Not globals:** `Buffer`, `URL`, `process` and the rest aren't defined globally. Bundled npm code that uses them without importing them still fails. Adding web and Node globals is a separate decision (NOTES.md).
- **Everything else** of Node's (`crypto`, `stream`, `http`, `child_process`, …) is still refused at publish and at submission, pointing at `"runtime": "node"`.
- **Why stand-ins and not a Node layer:** these five are pure JavaScript, cover most of what bundled npm libraries reach for, and stay inside the sandbox. Anything needing I/O or native code belongs in the Node runtime (D-084).

### D-091 — Packages step 3: a version is a revision, /packages in fs, c93:* modules
**Date:** 2026-10-08 · **Status:** Accepted. Builds step 3 of D-083; details in `docs/guides/packages.md`

- **Running a version:** a job or service names `"package": {name, version?, export?}` instead of a `"script"`.
  - **How:** the server makes the script `export { default } from "<name>[/subpath]";` and locks the package at that exact version (the newest release if none is given; a range isn't accepted, since a revision is one version). Everything else is step 2's machinery.
  - **What the API shows:** the service's `script` shows the one line it runs, and its `packages` the versions.
  - **Rolling:** a new version is a new revision, so the service rolls.
  - **Why no separate "package" field on services:** the lock already records exactly what runs.
- **`c93 deploy`:** publish, or reuse an identical published version, then `PUT` the service.
  - **Refused:** a changed file under an unchanged version, rather than silently running old code.
  - **The service's name** is `service.name` in `cluster93.json`, else the package's name with `.` turned into `-`; `config` and `restart` come from there too.
  - **`"runtime": "node"`** is refused until the Node runtime exists.
- **`/packages` in `fs`:** answered from the files fetched before the job started, with no I/O.
  - **Read-only:** writes fail with `read_only` (`EROFS`). A move or copy out of `/packages` copies the bytes.
  - **Listing:** the root listing shows `packages` when the job has any.
  - **`file:///` URLs** are accepted as paths everywhere in `fs`, so `import.meta.url`-relative reads work.
- **`c93:*` modules:** each re-exports its global, as the default export and by name. **Why not only globals:** a package that imports what it uses says so, and the bundler keeps `c93:*` as imports.
- **MCP:** `publish_package` takes files as text and runs the same three steps as the CLI, over loopback with the caller's token, so scopes and limits apply.

### D-092 — Packages step 4: yank, block and purge, as built
**Date:** 2026-10-08 · **Status:** Accepted. Builds step 4 of D-083, with block and purge as D-086 designed them

- **Effects:**

  | | New resolutions | Existing locks | Files |
  |---|---|---|---|
  | **Yank** | skip it | still load it (Cargo's model) | kept |
  | **Block** | skip it | refuse to load it, saying why | kept |
  | **Purge** | skip it | refuse to load it, saying why | deleted |

  Yank and block can be undone; a purge can't. The `latest` shown for a package also skips all three.
- **Stopping services:** blocking or purging a version stops every enabled service whose revision runs it.
  - **How:** each one is disabled, so it doesn't restart into the same code, and its current run is cancelled with the reason ("shop@1.4.0 is blocked (CVE-…)"). For a cluster package, that's any tenant's services. The answer lists them (`stoppedServices`).
  - **Purge stops services too,** though D-086 only said "fails loudly". **Why:** a run that's still going would otherwise fail at its next restart, with nobody watching.
  - **Running jobs aren't cancelled,** as designed. They already loaded the code; retries fail at load.
- **Purge and shared files:** files are deduplicated per owner, so another version may have the same file. A purge deletes only files no unpurged version of that owner has, and reports how many it kept. **Why:** deleting a shared file would break a version nobody purged. The guide says to purge those versions too, and to rotate the secret regardless.
- **Who:**
  - **Yank** is `packages:write`.
  - **Block, unblock and purge** need the new `packages:admin`, which owners and admins have through `*` and developers don't (D-086: "the tenant's owners and admins").
  - **Operators** manage `@cluster/` packages, and any tenant's with `?tenant=<id>`.
  - **Audit:** every change is audited, like any API write.
- **Garbage:** on the leader every 10 minutes, package files that no unpurged version names are deleted once they're over an hour old. That covers uploads whose publish never came (D-088's "Not yet"). The hour leaves a slow publish time to commit.
- **Metrics:**
  - `cluster93.packages.files`, tagged `source=cache|fetched`, counts cache hits against fetches.
  - `cluster93.packages.load` (D-089) times loading.
- **Not done:**
  - **Deleting a version outright** (§7: "only for versions no lock references") would need lock reference counting across jobs and services. Purge covers the urgent case.
  - **Cached copies of a purged file** stay in nodes' memory until they're evicted. They can't be loaded through the refused version, but a node restart is the way to be sure.

### D-093 — Real npm libraries are the measure of the isolate's Node compatibility; ICU, `process`, globals and `require` fixed
**Date:** 2026-10-08 · **Status:** Accepted (Tony asked for tests with real modules)

- **The test:** `cli/src/test/resources/realnpm` pins 24 npm packages (and esbuild) in a lockfile. `RealNpmPackagesTest` installs them with `npm ci --ignore-scripts` (so nothing native is built), publishes a package exporting one small module per library, runs each as a job, and requires the output to equal what the same module prints under real Node.
  - **It also checks refusals:** libraries needing Node's `crypto` or `stream` are refused at publish with that reason, native ones with the Node runtime named, and one needing Web Crypto fails clearly at run time.
  - **Gated on `C93_TEST_REAL_NPM`** (a work folder that keeps `node_modules`), since it needs node, npm and the network once.
- **What it found and what changed:**
  - **ICU:** Javet's default V8 build has no ICU, so `\p{…}` in regular expressions failed to compile and `Intl` was missing. That broke marked, ajv and validator, and would break most text-handling code. **Now:** Javet's `-i18n` build, with ICU's data file. **How the data ships:** Maven downloads the official ICU 78.3 release zip, checks it against a pinned SHA-256, and unpacks it with its licence into the runtime jar. At start-up it's written to a temporary file for V8, before the first isolate. **Costs:** the native library grows from about 30 to 51 MB, and the jar carries 33 MB of data (about 12 compressed). The ICU major version must follow Javet's V8 when Javet is upgraded.
  - **`process`:** a stand-in for `process` / `node:process`, with only `NODE_ENV` in `env`, `nextTick`, `hrtime`, `emitWarning` and a no-op event surface. `exit`, `chdir` and `binding` throw. It deliberately doesn't claim to be Node (`versions.node` is undefined), so libraries take their non-Node paths rather than reaching for APIs that aren't there.
  - **Globals, through the bundler rather than the isolate:** esbuild's `inject` turns free uses of `Buffer` and `process` in bundled code into imports of the stand-ins, only where they're used. `global` becomes `globalThis`, and `process.env.NODE_ENV` becomes `"production"`, as bundlers do for npm code. **Why not real globals:** every isolate would pay to load them, and globals are shared surface. This settles the question NOTES.md parked for those two; `URL` and Web Crypto remain open.
  - **`require()` of provided modules:** esbuild leaves an external `require('util')` in an ES module as a call that throws. Publishing now makes it a static import, and `require()` returns the module's default export, Node's `module.exports`.
  - **Native detection:** a package whose installed optional dependency holds a `.node` file is native. That covers sharp's `@img/sharp-<platform>` prebuilt binaries, which it loads through a computed path.
- **Still not covered, by design or for later:** Node's `crypto`, `stream`, `http` and the rest; Web Crypto (`crypto.getRandomValues`, which would need the host's secure random); and native code. The Node runtime (D-084) is where those run; whether more polyfills belong in the isolate gets a security review first.

### D-094 — S3 downloads: two more ways a busy node cut them off
**Date:** 2026-10-08 · **Status:** Accepted. Completes D-081

- **Evidence:** `S3BlobStoreTest.aReaderThatPausesEveryChunkGetsTheWholeFile` still failed in about 1 of 7 runs, with a raw `HttpClosedException` ("Connection was closed") reaching the reader. A raw exception means it came from a path D-081 didn't wrap: the stream's own error, or a request that never opened.
- **Two gaps:**
  1. **A close after the last byte.** The gateway closes as soon as it has sent everything. With the reader paused, Vert.x can report that close as an error instead of the end. Every byte had been delivered, so there was nothing to resume, and `resumeOrFail` failed a finished download. **Now:** a download that has delivered every byte has ended, whatever the connection says afterwards.
  2. **A request that fails to open.** With several downloads at once, a range request (the first, or a resume) can go out on a pooled keep-alive connection the server is just closing, and fail before any byte. That failed the download outright. **Now:** an open that fails is retried like a resume, within the same 3 attempts.
- **What isn't retried:** an answer that won't change. A 4xx from S3 (no such key, access denied) fails at once. To tell, `ResumingRangeBody` now gets the raw error, and the store's wording ("File data is missing", "The file storage backend failed") is applied only when it gives up.
- **Tests:**
  - **`ResumingRangeBodyTest`** drives each case by hand with a fake stream, so it happens every time: a close after the last byte, a close halfway, a first request and a resume each failing to open, a 403 not retried, and the resumes running out. Three of them fail on D-081's code.
  - **The stress test** passed 20 runs in a row (before: about 1 failure in 7).

### D-095 — More of Node in the isolate, without weakening the sandbox
**Date:** 2026-10-08 · **Status:** Accepted (Tony). Details in `docs/design/isolate-polyfills.md`

- **The rule:**
  - **Pure-JavaScript stand-ins** can always be added.
  - **Host-backed code is computation and randomness only:** no network, files, processes, environment or other tenants' data. Every call is capped, expensive calls run off the isolate's thread, nothing outlives the job or is shared between jobs, and the primitives come from the JVM's reviewed implementations (no home-made crypto).
  - **Anything that opens network access is a capability with its own review** (phase B).
  - **Vendored code** is pinned, licence-checked and fetched by checksum at build time.
- **Phase A:**
  - **`crypto`, backed by the JVM:** randomness, hashing, HMAC, key derivation, AES and ChaCha20, RSA, ECDSA and Ed25519.
  - **The Web Crypto global `crypto`:** Tony said yes.
  - **`stream`:** Node's own, vendored from `readable-stream` 4, with `string_decoder`.
  - **`zlib`** with an output cap; **`assert`**, **`querystring`**, **`timers/promises`**; **`os`** mapped to the job.
  - **Web globals** (`URL` and the rest), after a cold-start benchmark.
- **Caps (as proposed):** PBKDF2 ≤ 10⁶ iterations, `scrypt` ≤ 64 MB, RSA ≤ 4096 bits, random ≤ 64 KiB per call, zlib output ≤ 32 MB.
- **`process.env` is declared per job** (Tony): `"env": {"K": "value", "S": {"secret": "name"}}`.
  - **Contents:** only what's declared, plus `NODE_ENV`.
  - **Secrets** follow D-068's `secrets:use` rule and are resolved on the node at run start. The job record keeps the secret's name, never its value.
  - **Logs** mask a secret's value as `[secret:<name>]`.
  - This supersedes `ideas/node-compat.md`'s implicit mapping and extends D-093's empty `env`.
- **Network access never reaches private things unless explicitly allowed** (Tony):
  - **Phase B's checks:** `http`, `net` and `dns` go through D-026's egress check.
  - **The cluster's own nodes are always refused,** by advertised address and internal port, even on public addresses.
  - **Per-tenant, operator-granted, audited allow-lists** of hosts or CIDRs and ports replace the all-or-nothing `C93_EGRESS_ALLOW_PRIVATE`, which stays for local development only.
- **Each step comes with:** real libraries added to `RealNpmPackagesTest`, matched against Node; hostile scripts that try to exhaust every cap; and a check that no host object leaks (D-060).

### D-096 — `process.env` declared per job; `process` is a global
**Date:** 2026-10-08 · **Status:** Accepted (Tony: "an ENV flag for the job, with a secrets get"). Step 0 of D-095; supersedes D-093's "no new globals" for `process` only

- **The declaration:** `env` on `POST /v1/jobs`, `PUT /v1/services/{name}` and `jobs.run`. A value is a string, or `{"secret": "name"}`.
  - **Limits:** at most 100 entries, values up to 32 KB, and names like a shell's (a letter or `_`, then letters, digits or `_`).
  - **What the script sees:** exactly that, plus `NODE_ENV=production`.
- **Secrets:**
  - **Naming one needs `secrets:use`** at submission (403 otherwise), and the run reads it on the node with the job's own right, as `secrets.get` does (D-068).
  - **Only the name is stored:** the job record, the API, job lists and the MCP tools never see the value.
  - **A missing secret** fails the run before any code runs, naming it.
  - **Masking:** values of 4 characters or more are replaced in the job's log lines and fields by `[secret:<name>]`, longest first.
  - **Not covered yet:** values read with `secrets.get` aren't masked; the script asked for them explicitly.
- **Inheritance:** a job started with `jobs.run` gets its parent's env only with `inheritEnv: true` (merged under its own `env`). It may name secrets only if its parent may read them.
- **Services:** `env` is part of the revision, so changing it rolls the service, and every run reads its secrets fresh.
- **How the isolate gets it:** the resolved values are written as a JSON literal into the `process` stand-in's source when it loads, so no host call is involved and nothing is executed from them.
- **`process` as a global:**
  - **Why:** an inline job script isn't bundled, so `process.env.X` would otherwise be a ReferenceError. Node, Deno and Workers' compatibility mode all have it.
  - **What it is:** the same object `node:process` exports, writable as Node's is (browser shims assign to it). It still doesn't claim to be Node.
  - **No new reach:** it reaches nothing. `exit`, `chdir` and `binding` throw.

### D-097 — `crypto` step 1: randomness, hashing and HMAC from the JVM
**Date:** 2026-10-08 · **Status:** Accepted. Step 1 (and the first part of step 2) of D-095

- **Shape:**
  - **The host API:** `CryptoApi` in core, one per execution (`HostApis.crypto()`), implemented by `JvmCrypto` on `SecureRandom` (one per node, thread-safe), `MessageDigest` and `Mac`.
  - **The binding:** `CryptoBinding` exposes it synchronously, and failures come back as `Error` values, as `fs`'s sync calls do.
  - **JavaScript:** the prelude's `crypto.js` defines the frozen Web Crypto global. `node:crypto` builds Node's API on the same calls, which it finds under a symbol on that object. They offer nothing a script couldn't do with the public functions.
- **What's in:**
  - **Web Crypto:** `getRandomValues` (integer typed arrays, Node's `Buffer` included; at most 65,536 bytes, as the standard says), `randomUUID`, `subtle.digest` (SHA-1/256/384/512).
  - **Node:** `randomBytes`, `randomFill(Sync)`, `randomInt` (rejection sampling, unbiased), `randomUUID`, `getRandomValues`; `createHash` and `createHmac` for md5, sha1, sha2 and sha3, incremental, with `copy()`; `hash()`, `getHashes()`, `timingSafeEqual` (`MessageDigest.isEqual`), and secret `KeyObject`s.
- **Bounds:**
  - **64 KiB per host call:** `randomBytes(n)` for bigger `n` loops in JavaScript, so a huge request is bounded by the isolate's own heap.
  - **At most 1,000 open hashes** per execution.
  - **Per execution only:** handles are dropped with it, so nothing is shared between jobs.
  - **Cheap enough to stay synchronous:** each call works on bytes the script already holds.
- **Not yet:** ciphers, KDFs, signatures and asymmetric keys throw `ERR_FEATURE_UNAVAILABLE_ON_PLATFORM` rather than half-work. `Hash` isn't a stream until `stream` arrives.
- **Checked:**
  - **The standards' vectors:** FIPS 180-4, RFC 1321 and RFC 4231, including an empty HMAC key.
  - **Real Node:** a `RealNpmPackagesTest` module's output matches Node's byte for byte.
  - **Real libraries:** uuid (v4 and v7) and nanoid now run.

### D-098 — S3 retries: a fresh connection, a backoff, only failures without progress count, and plain requests too
**Date:** 2026-10-08 · **Status:** Accepted. Completes D-081 and D-094

- **Evidence:** after D-094, `S3BlobStoreTest`'s stress case passed 20 runs in a row on a quiet machine, but failed once during a full build. This time the error was the wrapped "The file storage backend failed: Connection was closed", so all four attempts had failed. Under load the gateway's closes are processed late, and an immediate retry from the pool landed on another connection that was itself closing.
- **Now:**
  - **Retries use a fresh connection.** A range asked for again goes out on a second S3 client without keep-alive, so it can never be a pooled connection that's closing. First requests still use the pool.
  - **Retries wait a little:** 50 ms, then 100, then 200, so the close events are handled before the next try.
  - **Only failures that brought no new bytes count** towards the limit of 3. A download that gets further each time keeps going, so a long download over a flaky link isn't capped at four drops in total, while one that fails without progress still gives up quickly.
- **The other requests had no retry at all.** Under the same load, a listing came back as a 200 with an empty body. **Now:** requests that mean the same thing when repeated (GET, HEAD, DELETE, and PUT, which writes the same bytes again) get the same treatment: up to 3 retries, on a fresh connection, after the same backoff. A GET's empty answer counts as a failure. POST isn't repeated (a second "create multipart upload" would leave an orphan behind), and neither is an answer from S3 itself (4xx or 5xx).
- **The root of the empty answers, caught with a diagnostic under load:** the gateway sent `Content-Length: 287` and `Connection: close`, and the client delivered a body of 0 bytes as if it were complete. A body cut short by the close can be reported as whole. **Now:** an answer whose length doesn't match its `Content-Length` is a transport failure, retried like any other. A range download already counted its bytes (D-081).
- **Starting a multipart upload** (a POST) is asked again only when its answer can't be read. The lost answer may still have started an upload, which the stress test caught as an orphan. So a blob's writer, after it finishes and when it aborts, aborts *every* unfinished upload of its key, not only its own. That's safe because each blob's key is its own.
- **A regression the stress test caught before it shipped:** the first version of the PUT retry resent the *same* `Buffer`. Sending consumes a Buffer, so the retry sent only the bytes the failed attempt hadn't. The signature's payload hash and length were computed from what was left, so S3 accepted a part missing its first 147,392 bytes, and the blob read back shifted. **Now:**
  - the bytes are copied once and every attempt sends a new Buffer of them;
  - every PUT carries `Content-MD5` of the intended bytes, so S3 refuses any body that differs, and this class of bug can't store wrong data silently again.
- **Tests:** `ResumingRangeBodyTest` adds a download that drops after every byte (8 drops) and still completes, and checks that retries are marked as retries and back off. **The stress test** ran 40 times under CPU load from 8 busy processes and passed every time. Before, 1 in 20 failed; an intermediate version failed 3 in 40.

### D-099 — In-memory stores answer on the caller's context
**Date:** 2026-10-08 · **Status:** Accepted. Fixes a hang found by the CLI tests under load; hardens D-039

**What happened:** a full test run sat for hours on one CLI command, `services get web` right after the service was removed. The server had read the request and never answered, and the CLI, with no request timeout, waited forever.

**Why it happened:**
- Every in-memory store (the `SharedData*` stores: services, jobs, kv, auth, files and the rest, 13 in all) kept the future of `vertx.sharedData().getAsyncMap(...)` from its constructor.
- A Vert.x future completes on the context that made it. So everything composed on that map, and every API handler that continued from it, ran on the event loop that built the store, not on the request's own.
- The 404 for the removed service was therefore written from the wrong thread. `EarlyAnswer.prepare` (D-039) asked `isEnded()`, then registered a drain handler. Meanwhile the request's own event loop delivered the end of the request, which had been queued while authentication paused it. Registering then threw "Request has already been read", before `end()` was called, so no answer ever went out.
- **When it shows up:** only under load. The CLI test class failed once in five runs while 8 processes kept the CPU busy, and passed every time on an idle machine.

**Decision:**
- **The stores look the map up on every use,** through a small `SharedMap` wrapper with the same `compose` method, so none of the ~90 call sites changed. Local map operations and `getAsyncMap` bind to the caller's context, so answers now come back on the request's event loop. For a local map, the lookup is a map get.
- **The Ignite stores were already right:** they call `executeBlocking` per request, which completes on the caller's context.
- **`EarlyAnswer.prepare` no longer throws** when the body ended between the check and the registration. There's nothing left to drain, and an error answer must always be sent.

**Tests:**
- `SharedMapTest` builds the service, kv and job stores on one event loop, calls them from another, and checks the answers arrive on the caller's. It fails on the old stores and passes now.
- `CliTest` ran 5 times under the same load and passed every time.
- **Two tests had leaned on the old serialization,** and failed once calls stopped all running on one context:
  - A service helper read a run's job as soon as the service named it. The supervisor names the run before submitting its job (by design: `reconcile` treats a missing job as not there yet). The helper now waits for the job.
  - A queue test retried a dead letter while its consumer was still running, then expected the message to be waiting. The consumer could take it straight back. The test now stops the consumer first.

**Not done:** the CLI still has no request timeout. A timeout until the response's headers would also count a large upload's sending time, so choosing one is parked in NOTES.md.

### D-100 — `stream` in the isolate: vendored `readable-stream`, output committed
**Date:** 2026-10-08 · **Status:** Accepted. Step 3 of D-095

**Decision:** `stream`, `stream/promises` and `string_decoder` (with or without `node:`) are Node's own code: `readable-stream` 4.8.0 and `string_decoder` 1.3.0, both MIT. They run on the isolate's `buffer`, `events` and `process` stand-ins and on its `AbortController`.
- **How it's built:** `scripts/vendor-stream` bundles them into ES modules with esbuild.
  - Its `require()`s of `buffer`, `events`, `process` and `string_decoder` become imports of the isolate's own modules, so there's one `Buffer` and one `EventEmitter`.
  - It installs from a lockfile with `npm ci`, which checks every package's hash, and records each package and its hash in the generated file's header.
  - Licences go to `js/node/THIRD-PARTY-LICENSES.txt`.
- **The output is committed** rather than built during the Maven build, unlike ICU's data (D-093). Building Cluster93 then never needs Node or the network. The generated code is also reviewed as a diff like any other, which is the point of vendoring. D-095's rule 5 is otherwise unchanged.
- **Security:** pure JavaScript, with no `eval`, no `Function` constructor and no dynamic imports. It reads only standard globals (`AbortController`, `Blob`, `AggregateError`), so it adds no reach. Anything a stream does still goes through the isolate's own limits.
- **What it unblocks,** checked against real Node's output by `RealNpmPackagesTest`:
  - **papaparse** and **csv-parse** (its streaming parser, fed rows split across chunks, and its sync API)
  - **jsonwebtoken** with HS256: sign, verify, and a tampered token rejected, on D-097's HMAC
  - RS256 and ES256 wait for step 2's signatures.
- **Also fixed:** `RealNpmPackagesTest` reinstalled its `node_modules` only when there was none, though its javadoc said "unless the same lockfile is already installed". It now records the lockfile it installed and installs again when the fixture's lockfile changes.

### D-101 — `crypto` step 2: key derivation, ciphers, keys and signatures
**Date:** 2026-10-08 · **Status:** Accepted. Step 2 of D-095, with its caps

**Decision:** `node:crypto` adds:
- **Key derivation:** PBKDF2, HKDF and scrypt.
- **Ciphers:** AES-CBC, AES-CTR, AES-GCM and ChaCha20-Poly1305.
- **Keys:** `KeyObject`s for RSA, EC (P-256, P-384, P-521) and Ed25519 keys, read from and exported to PEM, DER and JWK, and read from X.509 certificates.
- **Signatures:** sign and verify with RSA (PKCS#1 v1.5 and PSS), ECDSA (DER or IEEE P1363) and Ed25519.
- **Key generation:** `generateKeyPair`.

Everything is on the JVM's providers.
- **Two gaps in the JVM, filled in code here:**
  - The JVM has no scrypt, so RFC 7914 is implemented here.
  - Its PBKDF2 takes the password as characters, while Node's are bytes, so it's written on `Mac`.
  - Both are checked against their RFCs' vectors.
- **Keys stay in the job.** A `KeyObject` holds the key's DER (PKCS#8 or SPKI) and hands it to the host with each call, which parses it and keeps nothing.
  - Whatever format a key was read from, it's normalised to the DER Node itself writes, so an export gives what Node gives. For EC, that's SEC1 inside PKCS#8 with the public key.
  - **Deriving public keys:** the JVM has no call to derive an EC or Ed25519 public key from a private one. EC uses k·G computed here; Ed25519 uses the JVM's own key generator, given the seed.
- **Ciphers** are per-execution state, like open hashes, counting toward the same 1,000.
  - The JVM cipher is made at the first `update`, so `setAutoPadding` works as in Node.
  - **Authenticated decryption** collects its input (at most 32 MB) and decrypts at `final`, once the tag is known. Nothing unauthenticated is handed out before.
  - **String output** (base64, utf8) carries partial groups and characters across `update` calls, as Node's StringDecoder does.
- **Costly work runs on the node's crypto pool:** a Vert.x shared worker pool named `cluster93-crypto`, sized at half the cores (at least 2), made once per node. It runs PBKDF2, scrypt and key generation. The pool completes its answers from its own thread, not through the caller's context: a waiting `…Sync` call blocks the isolate's thread, so an answer queued to that context would never arrive.
  - The `…Sync` forms wait for the pool rather than computing on the isolate's thread. That bounds the node's CPU and scrypt memory (pool size × 64 MB) however many jobs ask at once.
  - A sync call that waits more than 60 s fails.
- **Caps** (D-095's, plus what they implied):
  - **PBKDF2:** ≤ 10⁶ iterations, and ≤ 4·10⁶ HMACs in all, so a long output can't multiply the cost.
  - **scrypt:** ≤ 64 MB, and parallelization ≤ 16.
  - **RSA:** generated ≤ 4,096 bits, read ≤ 8,192.
  - **Sizes:** derived output ≤ 64 KiB, and signed or verified data ≤ 32 MB.
- **Checked against real Node:**
  - A new real-npm fixture runs every piece with keys Node generated. Its output equals real Node's:
    - KDF outputs
    - ciphertexts, fed in odd-sized pieces with string encodings
    - RSA and Ed25519 signatures, which are deterministic
    - PEM, DER and JWK exports in every encoding
    - ECDSA, which is randomised, verifies.
  - jsonwebtoken's RS256 tokens equal Node's, and ES256 verifies.
- **jsonwebtoken's PS256** stays off in the isolate. jsonwebtoken enables it only when semver accepts `process.version`, which D-093 deliberately leaves empty so that libraries don't take their Node-only paths. RSA-PSS itself works through `sign`/`verify`. Claiming a Node version is a separate decision, for Tony.
- **Not yet:** RSA encryption, ECDH and Diffie-Hellman, encrypted private keys, rsa-pss, x25519 and ed448 keys, and Web Crypto's `subtle` beyond `digest`.
- **Also fixed: a stopping queue consumer took back what it had just released.** When a script ends, its consumer releases the message it was handling, then used to claim again before it had seen its own stop. Nothing was lost (a claim after stop hands the message straight back), but for a moment no other consumer could have it, and a test caught that. A release now doesn't claim more, since releasing only happens when the consumer is stopping.

### D-102 — The isolate never reports a Node version
**Date:** 2026-10-08 · **Status:** Accepted (Tony: "the isolate should not report a node version"). Confirms D-093

**Decision:** `process.version` stays `''`, `process.versions.node` stays undefined, and `os.release()`/`os.version()` are `''`.
- **Why:** libraries read these to decide whether they're in Node, and then take paths that need what the isolate doesn't have (raw sockets, native addons, the host's files). An isolate that claimed a version would run code that fails later and less clearly.
- **The cost, accepted:** a library that gates a feature on a version check turns that feature off. jsonwebtoken disables PS256 that way (D-101), though RSA-PSS itself works through `crypto.sign`/`verify`.
- **What runs Node code:** the Node runtime (D-084), which will report its real version.

### D-103 — `zlib`, `assert`, `querystring`, `timers` and `os` in the isolate; two older bugs they exposed
**Date:** 2026-10-08 · **Status:** Accepted. Step 4 of D-095

**Decision:**
- **`zlib`:**
  - **Codecs:** deflate, zlib, gzip and raw deflate both ways, `unzip`, and Brotli decompression, as sync calls, callbacks and `stream` Transforms. Also `crc32` and Node's constants and error codes (with `errno`).
  - **Implementation:** the JVM's `Deflater`/`Inflater` are zlib itself. Gzip's header and trailer are written and read here, as zlib writes them, so a stream can be fed in any pieces and concatenated members are read.
  - **Brotli:** decompression only, through Google's pure-Java decoder `org.brotli:dec` (MIT), which reads whole inputs, so a Brotli stream collects its input (capped) and decodes at the end. There's no pure-Java encoder, so Brotli compression throws `ERR_FEATURE_UNAVAILABLE_ON_PLATFORM`.
  - **The cap against zip bombs:** each call outputs at most 32 MB (`ERR_BUFFER_TOO_LARGE`), or less if `maxOutputLength` says so. Streams are per-execution state, at most 1,000.
  - **Compressed bytes may differ from Node's:** Node uses Chromium's zlib fork, which can compress differently. Both are valid, and either side reads the other's.
- **`assert` and `assert/strict`:** pure JavaScript.
  - Node's comparison rules: strict means `Object.is`, prototypes and own symbols; loose means `==`. Maps and Sets compare unordered, typed arrays by bytes, and cycles are handled.
  - `throws`/`rejects` accept a class, a RegExp, a validation function or an object.
  - Node's messages for simple values, shorter ones for structures.
- **`querystring`:** pure JavaScript, with Node's rules, including how it decodes malformed escapes.
- **`timers` and `timers/promises`:** on the isolate's own timers, with abort signals. `setImmediate` is a zero-delay timer.
- **`os`:** describes the job, never the machine.
  - **The job's values:** `tmpdir()`/`homedir()` are `/tmp`, there's one CPU, and `totalmem()`/`freemem()` are the isolate's heap limit, which the runtime writes in like `process.env`.
  - **Fixed values:** `hostname()` is `localhost`, `networkInterfaces()` is empty, and `release()`/`version()` are empty (D-102).
- **Checked against real Node:** a real-npm fixture's output equals real Node's. It covers:
  - round trips of every codec
  - Node-made gzip, deflate (with a dictionary) and Brotli data read here
  - the error codes and messages for bad headers, truncation and bad CRCs
  - streams fed one byte at a time
  - 25 deep-equality cases, plus throws/rejects/match
  - querystring edge cases
  - the async timers

**Two older bugs these tests found, both fixed:**
- **Bytes from JavaScript could be wrong.** `HostBridge.bytes` used Javet's `toBytes()`, which copies a typed array's whole underlying buffer, not the view: from the buffer's start and for the buffer's length.
  - **Effect:** any host call given a `Buffer` slice, or a `Buffer` whose buffer was allocated larger than it (base64 decoding does that), received the wrong bytes. That covers `fs`, `files`, `fetch` bodies, `crypto` and `kv` values, for example writing a base64-decoded `Buffer` to a file.
  - **Fix:** it now reads the view's own bytes from its offset, for its length.
- **The UTF-8 decoder didn't check continuation bytes.** It's behind `TextDecoder`, `Buffer#toString`, `fetch`'s `text()` and `fs` reads.
  - **Effect:** a broken sequence swallowed the next character; for example, `E0 A4 25` became "थ" instead of "�%".
  - **Fix:** it's now the WHATWG decoder that Node and browsers use, checked against Node's on edge cases and 200 KB of random bytes. It also builds strings in chunks, not one character at a time.

### D-104 — Web Crypto's `subtle`, RSA encryption and key agreement
**Date:** 2026-10-08 · **Status:** Accepted. Completes step 2 of D-095 (D-101 left these)

**Decision:**
- **`crypto.subtle`:**
  - **Operations:** `generateKey`, `importKey`/`exportKey` (raw, pkcs8, spki, jwk), `sign`/`verify`, `encrypt`/`decrypt`, `deriveBits`/`deriveKey` and `wrapKey`/`unwrapKey`.
  - **Algorithms:** HMAC, AES-GCM/CBC/CTR, RSASSA-PKCS1-v1_5, RSA-PSS, RSA-OAEP, ECDSA, ECDH, Ed25519, X25519, PBKDF2 and HKDF.
  - **Web Crypto's rules:** usages, extractability, DOMException names, and the spec's order of checks.
  - **Same host calls as `node:crypto`,** so keys stay in the job (D-101).
  - **The `CryptoKey` global** is defined with it: it's part of the Web Crypto global that D-095 accepted, and libraries such as jose check `instanceof CryptoKey`.
  - **Not supported:** AES-KW (`NotSupportedError`), and an AES-CTR counter that would wrap within a `length` under 128 bits. The JVM counts with all 128 bits, which agrees with Web Crypto unless the counter wraps.
- **`node:crypto`** adds:
  - `publicEncrypt`, `privateDecrypt`, `privateEncrypt` and `publicDecrypt`, with OAEP (any hash, with a label), PKCS#1 v1.5 and no padding.
  - `createECDH`, with uncompressed, compressed and hybrid points, plus `ECDH.convertKey`.
  - `diffieHellman()` for ECDH and X25519.
  - X25519 as a key type: generate, import and export, and derive the public key.
  - **Point checks:** a public point is checked to be on the curve before use, and compressed points are decompressed here.
- **`privateDecrypt` with PKCS#1 v1.5 padding is refused, deliberately stricter than Node.**
  - **The risk:** that decryption leaks through its timing (the Marvin attack, CVE-2023-46809).
  - **Why Node differs:** Node 22 refused it at first, and allows it again where OpenSSL 3.2+ does "implicit rejection", which removes the leak.
  - **Why the isolate doesn't follow:** the JVM's PKCS#1 decryption has no implicit rejection and has been shown vulnerable.
  - **What still works:** OAEP (the default), and PKCS#1 v1.5 for `publicEncrypt`, `privateEncrypt` and `publicDecrypt`.
- **Classic (MODP) Diffie-Hellman** (`createDiffieHellman`) stays unavailable, as does Ed448/X448.
- **Checked:**
  - **Against real Node:** a real-npm fixture's output equals real Node's, which covers:
    - every deterministic Web Crypto result: HMAC, RSASSA-PKCS1 and Ed25519 signatures, AES-GCM/CBC/CTR ciphertexts, PBKDF2/HKDF/ECDH/X25519 bits, derived and wrapped keys, and JWK exports
    - round trips for ECDSA, RSA-PSS, RSA-OAEP (with a label) and generated keys
    - the error names
    - `createECDH` with fixed private keys in every point format
    - `diffieHellman`
  - **Standards:** RFC 7748's X25519 vectors.

### D-105 — One reference for Node compatibility, and `console` completed
**Date:** 2026-10-08 · **Status:** Accepted (Tony: "we should have documentation of all our polyfills with caveats")

**Decision:**
- **The reference:** `docs/scripting/node-compat.md` is the single reference for what the isolate provides of Node. It covers:
  - the rules every stand-in follows
  - a status table of modules
  - every global, present and absent (taken from the isolate itself, not from memory)
  - for each module, what's there, what isn't, its limits and where it differs from Node
  - what's refused and why
  - what the bundler does
  - how compatibility is verified
  - all the known differences in one list
- **Keeping it current:** a change to any stand-in updates this page, as well as the changelog and its decision.
- **Writing it found a gap:** `console` had only `log`, `info`, `warn` and `error`, so a library calling `console.debug`, `console.time` or `console.group` threw. `console` now has `debug`, `dir`, `table`, `trace`, `assert`, `group`/`groupCollapsed`/`groupEnd` (indenting what follows), `time`/`timeLog`/`timeEnd` and `count`/`countReset`, all writing to the job's log. `table` and `dir` print JSON rather than a table.

### D-106 — The Web and Node globals, paid for by V8's code cache
**Date:** 2026-10-08 · **Status:** Accepted. Step 5 of D-095 (globals "after a cold-start benchmark")

**Decision:** the isolate defines the globals libraries expect from Node and browsers, all pure JavaScript with no reach:
- **URLs and text:** `URL` and `URLSearchParams` (with `URL.canParse`/`URL.parse`; `node:url` now re-exports these rather than having its own), `atob` and `btoa`.
- **Events and cancellation:** `DOMException`, `Event`, `CustomEvent`, `EventTarget`, `AbortController` and `AbortSignal` (with `abort`, `timeout`, `any`, `throwIfAborted`, and `onabort` as an event handler attribute).
- **The rest:** `structuredClone`, `queueMicrotask`, `setImmediate`/`clearImmediate`, and `performance` (`now`, `timeOrigin`, `mark`/`measure`).
- **`performance.now()` is coarse:** whole milliseconds, no finer than `Date.now()`, so it adds nothing to timing attacks.
- **Web Crypto's errors are now real `DOMException`s,** as in Node.
- **Not added:** `Blob`, `File`, `FormData`, `Headers`/`Request`/`Response`, the Web Streams globals, `MessageChannel` and `WebSocket`. They need their own designs: `fetch` returns the host's response today, and WebSocket is network reach.

**The benchmark, and what paid for it:** a new `IsolateStartupBenchmark` (run with `C93_BENCH=1`) times a fresh isolate, its prelude and a script, on one thread after a warm-up.
- **The cost of the globals:** they alone added 0.8 ms at p50 to every job (4.9 → 5.7 ms, in three alternating runs each): parsing about 25 KB more prelude.
- **The fix: compile once.** The prelude is the same source every time, so the first isolate compiles and runs it, and its compiled code goes to every later one through V8's code cache.
  - **Built-in modules** whose source doesn't vary per job are cached the same way. `process` and `os` carry the job's values, so they aren't.
  - **V8's own checks:** V8 checks each cache against the source, its flags and its version, and compiles afresh if anything differs.
  - **Where caches come from:** only from this process and our own sources, never from a script.

**Results** (p50, one machine, same day):

| | Before (no globals) | After (globals, code cache) |
|---|---|---|
| Trivial job | 4.9 ms | 3.5 ms |
| Importing `crypto` | 7.7 ms | 4.7 ms |
| Importing `stream` | 12.0 ms | 6.7 ms |
| Importing `zlib` | 13.1 ms | 7.6 ms |

So every job starts about 30% faster than before, with the globals included.

**Checked against real Node:** a real-npm fixture's output equals real Node's. It covers URL parsing and search params, event dispatch (`once`, `handleEvent`, `stopImmediatePropagation`, `preventDefault`), and abort ordering and reasons, including `AbortSignal.timeout`/`any` and `node:stream`'s `pipeline` stopped by a signal. It also covers `structuredClone` of Maps, Sets, typed arrays, errors, cycles and class instances, its `DataCloneError`, `atob`/`btoa` errors, and the order of microtasks, promises, immediates and timers.

**Not done:** user packages' modules could be cached the same way, keyed by package version and file; that's for when packages are large enough to matter.

### D-107 — A job's log is bounded in characters, not only lines
**Date:** 2026-10-08 · **Status:** Accepted

**Context:** Tony asked how console output is piped and kept. Tracing it found the path sound but unbounded in size:
- **The path:** `console.*` and `log.*` → the host's `LogSink` → secrets masked (D-096) → the attempt's `LogBook` (its last 1,000 lines) and a live event on the job's stream → stored on the job record when the attempt ends, and deleted with the job by retention.
- **The gap:** a line had no length limit, and `LogBook` lives on the node's Java heap, outside the isolate's limit. A script logging 1,000 lines of 10 MB each held 10 GB on the node, then wrote it to the job record.

**Decision:**
- **Per line:** cut at 16,384 characters, with `… [N more characters]` appended. The prelude cuts first, so a huge string isn't copied to the host; the host cuts again as the authority, leaving room for the prelude's marker so a line isn't cut twice.
- **`log.*` fields** over 16,384 characters (as JSON) become `{"fieldsOmitted": "N characters"}`, and a cut line's live event has no separate fields.
- **Per attempt:** the last 1,000 lines and at most 1,048,576 characters, dropping the oldest. So a running job holds at most about 1 MiB of log on its node, and a job record at most that.

**Not changed, and noted for later:**
- **Durable logs beyond the last 1 MiB,** such as a long-running service's whole history, would need a log store (for example appended chunks in `files`, or shipping to an external sink). Nothing asks for it yet.
- **Levels** are on live events but not on stored lines.
- **A script can't read its own log back;** `kv` and `files` are for that.
- **Live log events have no rate limit.** A tight loop of `console.log` publishes as fast as the isolate runs; the per-line and per-attempt caps bound memory, not event-bus traffic.
- **The Node runtime** (D-084, not built) will send the container's stdout and stderr into this same log, with the same limits.

### D-108 — One egress gate for every connection a script causes, with per-tenant rules
**Date:** 2026-10-08 · **Status:** Accepted. Phase B's first step ([network-reach.md](design/network-reach.md) §3); the shape is Tony's (D-095 §4.5)

**Context:** phase B gives scripts Node's `http`, `net`, `tls` and `dns`. Before adding any reach, every connection needs one place that decides it. Tony's rule: "anything trying to open network access should not be able to access private things on our cluster unless explicitly allowed to". `fetch`'s check (D-026) refused private addresses, but it had two gaps:
- **No way to open one:** the only way to reach a private address was `C93_EGRESS_ALLOW_PRIVATE`, which opens every private address for every tenant.
- **No idea of the cluster:** a node on a public address was reachable like any website.

**Decision:** one `Egress` gate in core. It gets the tenant, the requested name, its resolved addresses and the port, and answers the one address to connect to. For each resolved address, in order:
1. **A cluster node is refused, on every port.**
   - **What counts as a node:** any address a live node advertises (from the node registry, re-resolved at most every 10 s); any address of this node's interfaces; and loopback and wildcard addresses.
   - **Why every port:** the event bus listens on a random port, and nothing on a node is meant for scripts.
   - **The development exception:** with the development switch on, only the node's own ports are refused (API, ingress, internal, metrics, and the cluster's discovery and communication ranges), so a developer's `localhost:3000` still works.
   - **No rule can allow a node.**
2. **A public address is allowed.**
3. **A private or reserved address** is allowed only by the development switch, or by a rule in the tenant's egress policy.

The first allowed address is the one connected to, as D-026 requires.

**What counts as reserved, added:**
- **IPv4:** the documentation ranges (192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24), and 192.88.99.0/24.
- **IPv6:** all of ::/8, NAT64 64:ff9b::/32, discard 100::/64, Teredo 2001::/32, documentation 2001:db8::/32, ORCHID 2001:10::/28, and 6to4 2002::/16.
- **Embedded IPv4:** IPv4-mapped, IPv4-compatible, NAT64 (/96) and 6to4 addresses are judged by the IPv4 address they carry, for the public check, the cluster check and rules alike.

**The tenant's egress policy:**
- **Who sets it:** operators only, with `PUT /v1/tenants/{tenant}/egress-policy`. It's audited like every change (D-071), and kept in a `Records` collection (`EGRESS_POLICIES`).
- **A rule** is a CIDR, or an exact host name, plus required ports or port ranges. There are at most 100 rules.
  - **CIDRs:** prefixes of /8 or longer. Rules are parsed only as literals, so writing one never looks a name up.
  - **Name rules:** they match only when the script asks for that name, and then allow what it resolves to. The operator is trusting that name's DNS; that's what Tony's "hosts or CIDRs" means.
- **It's read only when it could change the answer:** when some address is private and not a node's.
- **A deleted tenant's policy goes with it.**

**The tenant's view** (Tony asked for it while this was being built): `GET /v1/egress-policy` (`jobs:read`) and `c93 egress` show the whole policy that applies:
- the defaults, each with a sentence saying why
- the tenant's rules
- whether the development switch is on
- the limits

It's written for people, because the console will show the same response as a "Network access" page (parked in NOTES).

**Also:**
- **Through the gate:** `fetch`, and completion callbacks as the job's tenant.
- **Counted:** refusals, in `c93_egress_denied_total{tenant,reason}`.
- **A warning:** a node warns at every start while the development switch is on.
- **Binary bodies:** `fetch` sends a typed array or `ArrayBuffer` body as bytes. Before, it decoded the bytes as UTF-8, which corrupted binary uploads. `URLSearchParams` bodies are sent as forms.

**Not yet:**
- **Tenant S3 endpoints** still use the older check, governed by the storage policy's `allowPrivateEndpoints`, without the cluster-node rule. They move to the gate with phase B's next step.
- **A node's actual listening ports:** the development exception uses the configured ports, so a node started on port 0 (as tests do) has none listed. That's harmless, since outside development a node is refused on every port anyway.

**Tested:**
- **`EgressTest`** (62 cases):
  - every reserved range and embedded form
  - rule parsing, including names that look like hex, invalid octets and short prefixes
  - name and CIDR rules
  - nodes on public addresses, including their NAT64 and IPv4-mapped forms
  - the development exception
- **`HostApisTest`:** on a two-node cluster, an operator's rule turns a refusal into a connection attempt, on that port only and for that tenant only, while a node stays refused under a rule covering it, and the tenant's view shows it all.
- **`ContractTest`, `CliTest`, `NodeHostApisTest` and `HostBridgeTest`:** binary bodies, from the script to the wire.

### D-109 — Node's `http` and `https` clients, on a streaming host request through the egress gate
**Date:** 2026-10-08 · **Status:** Accepted. Phase B's step 2 ([network-reach.md](design/network-reach.md) §4); Tony said "let's do it", with `rejectUnauthorized: false` honoured (his answer in §8)

**Decision:** `node:http` and `node:https` provide Node's client.
- **The host side: a request whose body streams.**
  - **`FetchApi.open`** checks the request, passes it through the gate (D-108), and connects. The script then writes chunks with backpressure, ends the body, and gets the response with its body as a stream.
  - **It counts against `fetch`'s 16 per execution** until its response is read, it's aborted, or the job ends, when it's aborted.
  - **Framing:** a `Content-Length` the script sets is sent as is; otherwise a body written in parts is chunked.
  - **Timeouts:** connecting takes at most 30 s, and a request can sit idle (no data either way) for up to 5 min. Long polls need that, where `fetch` allows 30 s.
  - **Response headers:** up to 64 KiB, on every client, `fetch`'s included.
- **TLS:**
  - **By default,** certificates are verified against the JVM's trust store, with host-name checking.
  - **`rejectUnauthorized: false`** uses a shared client that doesn't verify. That only affects the tenant's own connection, and the job's log says so once.
  - **`ca`, or a client `cert` and `key` (PEM):** the request gets a client of its own, closed with it, so no connection carrying a tenant's certificate is ever pooled.
- **Responses carry `rawHeaders`** (also for `fetch`), so `set-cookie` and other repeated headers survive. Node's joining rules are applied in the isolate.
- **The JavaScript:** `ClientRequest` is a `Writable` and `IncomingMessage` a `Readable`, both from the vendored `readable-stream`.
  - **The request opens lazily,** on the first write or the end, so headers can change until then.
  - **One body given whole to `end()`** is sent with its length, as Node does.
  - **Events come in Node's order:** `socket`, `finish`, `response`. When the body is ending, the response is held until `finish`.
  - **`req.socket`** is a stand-in with the methods libraries call.
  - **Errors carry Node's codes:** `ECONNREFUSED`, `ECONNRESET` ("socket hang up"), `ETIMEDOUT` and `ENOTFOUND`.
  - **A response's errors go only to an `error` listener;** otherwise it emits `aborted`, as Node's `IncomingMessage` does. follow-redirects relies on that.
- **Refused:** `createConnection`, `socketPath`, `CONNECT`, `pfx`, `passphrase`, and a custom `checkServerIdentity`.
- **Ignored, not refused** (a change from the design): `lookup`, `family`, `localAddress` and `servername`. The gate does the resolving; refusing them would only break libraries that pass their own.

**What making real libraries work required** (axios 1.20 and node-fetch 3.3.2 now match real Node):
1. **Stand-ins for `net`, `tls`, `http2` and `tty`, for their pure parts.** axios imports `http2` at load for an optional HTTP/2 path. Its proxy agent requires `net` and `tls`, node-fetch imports `net.isIP`, and debug requires `tty`. Each module has what's computation (Node's IP patterns, all 240 `http2.constants`, `isatty()` false) and throws `ERR_NOT_SUPPORTED` on anything that would connect. `net` and `tls` get real sockets in B5.
2. **A CommonJS `require()` of a missing built-in throws when it runs,** instead of stopping the publish; the publish warns.
   - **Why:** bundling made every `require()` of a built-in a static import (D-093), so a `require()` inside `try` that falls back couldn't fall back.
   - **Missing ones now:** the bundler emits a module that throws `MODULE_NOT_FOUND` when required, which is what Node does. fetch-blob then uses its bundled Web Streams polyfill, and node-domexception never reaches its `worker_threads` path.
   - **An `import` of a missing module still stops the publish:** an ES import can't be caught.
3. **`fs.createReadStream` and `fs.createWriteStream`,** which fetch-blob imports. They're Node streams over file handles, so they hold the whole file and are limited to 32 MB as handles are, and a write stream publishes the file when it closes. `files.stream` and `files.write` remain the way to stream large files.
4. **`process[Symbol.toStringTag]` is `'process'`,** as in Node. axios uses its Node adapter only when `Object.prototype.toString.call(process)` says so; otherwise it picks a browser adapter that can't work here.
   - **Checked for fallout:** this could change other libraries' paths too, so all 24 earlier real-npm fixtures were rerun. All still match Node.
5. **A throw in a `queueMicrotask` or `process.nextTick` callback, or an `http` listener, fails the job,** as an uncaught exception ends Node.
   - **The bug:** both ran the callback inside a promise, so the error became a rejection nobody saw, and the job sat until "never settles" or its timeout. `fetch`, timers and listeners already reported their errors.
   - **The fix:** a hidden reporter under `Symbol.for('cluster93.uncaught')`. A script that calls it only fails its own job.
   - **Not changed:** unhandled promise rejections still don't fail a job, unlike Node 15+. That needs its own decision (NOTES).

**Testing it required:**
- **The development switch on the real-npm test node:** the test server is on loopback, which is the node itself, and no rule can reach a node (D-108). The design had planned a policy rule.
- **A second fixture package (`realworld-net`),** because one package would have been over a version's 10 MB.

**Tested:**
- **`RealNpmPackagesTest`:** a `node:http` fixture, axios and node-fetch match real Node 26.8.1, and the 24 earlier fixtures still do.
- **`NodeNetworkStandInsTest`** (in every build): requests in the isolate against a local server; error codes; the pure parts of `net`, `tls`, `http2` and `tty`; and the `fs` streams.
- **`StreamedRequestTest`:** chunking, `Content-Length`, repeated headers, TLS (refused by default, then a `ca`, then opting out), invalid requests refused before connecting, and the shared limit and its release.
- **`HostApisTest`:** `node:http` gets the same refusals as `fetch`, both for a private address and for a cluster node.
- **`JavetScriptRuntimeTest`:** throws in `queueMicrotask` and `nextTick` fail the job.

**Not done:**
- **`got`** isn't tested; it needs `http2-wrapper` and socket timing events.
- **Servers** (`createServer`) are B3.

### D-110 — Unhandled promise rejections fail the job, as in Node
**Date:** 2026-10-08 · **Status:** Accepted (Tony: "i think we should address that, there is nothing running this in production literally").

**Context:** D-109 made a throw in a microtask, `process.nextTick` or an `http` listener fail the job, but left unhandled rejections alone. Node ends the process for one (the `throw` mode, default since v15). In the isolate it vanished: `fetch(url)` without an `await`, or a library's floating promise, failed silently and the job reported success. The note proposed a grace period (log first, fail later); with nothing in production there's no one to protect.

**Decision:**
1. **V8 reports them; the prelude keeps them.** The runtime sets Javet's promise-reject callback. A rejection with no handler is recorded under its promise, and a handler added later takes it back. Javet closes both values when the callback returns, so they're kept in the prelude's `Map`, not in Java.
2. **Checked when Node checks:** after each turn of the event loop, meaning after microtasks have drained following a batch of tasks or a timer. A handler attached in the same turn is in time; one attached after a later turn isn't. A final check runs once the script's promise has settled and every queued microtask has run, so a rejection in the script's last turn still fails it.
3. **What happens to one still unhandled:**
   - If `process` has `unhandledRejection` listeners, they get `(reason, promise)` and the job goes on. A later handler then emits `rejectionHandled`.
   - Without listeners, the job fails with `Unhandled promise rejection: <reason>`, the reason's stack when it has one.
   - A throw in either listener fails the job as an uncaught error.
   - The prelude finds `process` under `Symbol.for('cluster93.process')`, so a script that replaces the global doesn't turn the check off.
4. **The script's own failure wins.** If the default export throws, that's the error, not a rejection it left behind. A module that fails to load is reported as itself: the check doesn't run once the promise being waited on has settled, and the module's own evaluation promise is the one that rejected.
5. **Promises returned by timer callbacks are ignored,** as Node's `setTimeout` ignores them, so their rejections take the path above. That replaces D-109's special case, which reported them as uncaught timer errors no listener could hear.
6. **Services too:** a floating rejection ends a service's run, as it would end a Node server. Handlers' own promises are awaited by `ctx.serve`, so they aren't floating.
7. **No grace period and no switch.** Node's `--unhandled-rejections=warn` isn't offered. A script that wants to survive them registers a listener, as in Node.

**Consequences:**
- **Scripts that relied on silent rejections now fail.** That's intended: each was a bug.
- **Library risk:** a library that leaves internal promises rejected would now fail jobs, as it would crash Node. All 30 real-npm fixtures still match Node.
- **Tested** in `JavetScriptRuntimeTest` (seven cases).

### D-111 — Configurable address spaces for the egress gate
**Date:** 2026-10-08 · **Status:** Accepted (design; to be built as phase B's next step). Tony: "make it configurable what address spaces we allow and disallow", with these answers:
- **Where:** stored in the cluster, edited through the API and CLI.
- **Precedence:** cluster deny, then tenant, then cluster allow.
- **The allow-list-only posture:** for the whole cluster and per tenant.

**Context:** D-108's gate hard-coded "public allowed, private and reserved refused" and gave operators two controls: a global development switch and per-tenant allow rules. An operator couldn't do any of this:
- deny a public range (their own public addresses, say)
- allow a shared internal service for every tenant at once
- lock a cluster or a tenant down to an allow-list
- change the SMTP block
- deny anything for one tenant

**Decision** (network-reach.md §10):
- **Rules at two levels, a cluster policy and each tenant's,** both set by operators: `allow` or `deny`, to a CIDR, a host, a `*.suffix` or a named set, optionally scoped by port and by protocol (`http`, `tcp`, `dns`), with a note.
- **Named sets:**
  - **Built in:** `@private`, `@loopback`, `@link-local`, `@cgnat`, `@reserved` and `@smtp-ports`.
  - **The operator's own,** such as `@corp-public`.
- **The order:**
  1. **Cluster nodes are always refused,** as code that no rule can override.
  2. **Cluster deny rules.**
  3. **Tenant deny rules.**
  4. **Tenant allow rules.**
  5. **Cluster allow rules.**
  6. **The posture:** the tenant's if set, else the cluster's. `public` (the default) or `allow-list`.
- **SMTP** becomes a default cluster deny rule on `@smtp-ports` over `tcp`, which an operator can edit.
- **The connect rate and socket caps** become cluster settings.
- **The development switch** acts as allow rules for `@private` and `@loopback`.
- **Where it's kept:**
  - **Storage:** `Records`. Changed through `PUT /v1/egress/cluster-policy` (operators, audited) and the CLI.
  - **Seeding:** `C93_EGRESS_POLICY_FILE` seeds it at the cluster's first start only.
  - **Compatibility:** tenant policies gain deny rules and a posture, and today's rules read as allows.
- **The tenant's view** (`GET /v1/egress-policy`) shows the effective policy in the order it's applied, with why, for the console's "Network access" page.

**Consequences:**
- `Egress.decide` becomes evaluation of these rules and stays pure, so it's tested exhaustively in `EgressTest`.
- `EgressPolicy.isPublic` becomes the built-in sets.

### D-112 — Servers are reached by hostname only; listening ports are intercepted
**Date:** 2026-10-08 · **Status:** Accepted (design; built with B3).

**Tony:** "intercept what port they are listening on… route through our cluster's http handling… an http server or websocket server should be able to strictly be handled via hostname". Also: "we have to make it so we can reserve what ports they can listen on and intercept and randomize it… each node will have to have a reserved port space", and "tcp and udp servers would be reserved for our c93 link plan, that can run on their own hosted server… plain tcp and udp ports would only be possible if we are running untrusted nodes".

**Decision** (network-reach.md §5):
- **No script opens a port on a core node.** `listen(port)` is intercepted, and the ingress routes by hostname.
- **The route table:** `(tenant, service, declared port) → (node, backend endpoint)`, shared by both runtimes.
  - **Isolates:** a random, opaque route id on the node's internal endpoint. There's no operating-system socket at all.
  - **Node containers:** a random port from the node's reserved range (`C93_SERVICE_PORTS`, default `20000-29999`), mapped to the port inside the container. The gate refuses that range to every script.
- **Reaching each port:**
  - **The default:** the first port gets the service's host; others get `<service>--<tenant>--<port>.<domain>`, one label, so the wildcard certificate covers it.
  - **Per port, if asked:** a path prefix, custom domains, or not exposed.
- **WebSocket servers** go through the ingress too, which will pass `Upgrade` through (network-reach.md §13).
- **Raw TCP and UDP servers are never on core nodes.** They're possible only on untrusted nodes, such as a link on its own hosted server, and belong to the links design.

**Recorded with it** (Tony's choices from the plan's review):
- **No socket idle timeout,** as in Node.
- **A listening server keeps a service alive,** and a stop emits `SIGTERM` on `process`.
- **A service module that calls `listen` needs no default export.**
- **`AsyncLocalStorage.run()` is refused** rather than losing its value across `await`.
- **`https.createServer` ignores `key`/`cert`,** since the ingress does TLS.
- **An `http2` client is built on Vert.x** (§12), replacing "never `http2`".

### D-113 — Configurable address spaces, as built
**Date:** 2026-10-08 · **Status:** Accepted. Builds D-111 (network-reach.md §10).

**What was built:**
- **The rules:** allow and deny rules at two levels.
  - **Targets:** CIDRs, host names, `*.suffix`, named sets (`@private`, `@loopback`, `@link-local`, `@cgnat`, `@reserved`, `@public`, and the operator's own) and `*`.
  - **Scope:** by port (including `@smtp`) and by protocol (`http`, `tcp`, `dns`), with a note.
- **The order:** cluster nodes, cluster denies, tenant denies, tenant allows, cluster allows, the development switch, then the posture (the tenant's, else the cluster's).
- **The API:**
  - **The cluster's policy:** `GET`/`PUT /v1/egress/cluster-policy`, with the built-in sets explained.
  - **Tenant policies** gain `rules` and `posture`. The older `allow` list is still read.
  - **The tenant's view** lists each step in order with why, plus the cluster's rules and the tenant's.
- **The CLI:** `c93 egress cluster`, and `tenants egress --deny`/`--posture`.
- **Seeding:** `C93_EGRESS_POLICY_FILE` seeds the cluster's policy at first start.
- **Errors:** a refusal by a rule names the rule and its note.

**Choices made while building:**
- **Addresses:**
  - an allow matches only the address really reached, and a deny matches either form of it
  - a missing set refuses as a deny and opens nothing as an allow
  - `*.tld` only in a deny
- **The built-in sets are the old public check, restated.** A test holds them to exactly `EgressPolicy.isPublic` over the reserved and public address corpus, so nothing changed for policies that set nothing.
- **The development switch keeps D-108's meaning:** every private and reserved address, now after deny rules.
- **The connect rate and socket caps aren't in the cluster policy yet.** They arrive with sockets (B5), which first uses them.
- **Caching:** a node caches both policies.
  - **On a change:** it's announced on the event bus and every node drops its copy.
  - **As a backstop:** copies expire after 5 s, and a read that raced a change isn't cached.
  - **The API reads past the cache.**

**Tested:**
- **`EgressRulesTest` (79):**
  - the sets match the old check
  - every step of the order
  - postures at both levels
  - the SMTP default only for `tcp`
  - nodes beyond any rule
  - the development switch honouring denies
  - allow and deny on embedded forms
  - suffixes
  - missing sets
  - the older shape
  - round trips
  - malformed policies
- **`HostApisTest`:** across two nodes, a cluster deny beats a tenant allow with the note in the error; a cluster allow serves every tenant; an allow-list tenant can't reach a public address; tenants are refused the cluster API; unknown sets are refused. The second node sees a change without waiting for its cache.
- **Also updated:** `ContractTest` (the new operations) and `CliTest` (both commands). The existing `EgressTest` (62) is unchanged and passing.

### D-114 — got runs in the isolate, and `http`'s socket reports the real connection
**Date:** 2026-10-08 · **Status:** Accepted. Phase B's got step (network-reach.md §11); Tony: "okay lets do it".

**Decision:**
- **`req.socket` reports the connection the host made.**
  - **The host's side:** Vert.x hands over the connection with the request. `ConnectionInfo` describes it: the addresses, whether it came from the pool, and for TLS the protocol, cipher, peer chain, and whether the peer was trusted.
  - **The socket:** it's `connecting` until then. It then emits `lookup` (names only, never addresses, as Node), `connect`, `secureConnect` and `ready`, and answers `getPeerCertificate(detailed)` in Node's shape, `getProtocol()` and `getCipher()`.
  - **Pooled connections:** a pooled connection sets `reusedSocket` and has no `lookup`.
  - **Trust without verification:** with `rejectUnauthorized: false`, `authorized` is whether the JVM's trust store accepts the chain. The host name isn't checked, and the error is one of four OpenSSL codes.
- **`node:diagnostics_channel`,** complete and pure. got, mysql2, ioredis and Fastify import it.
- **`events.on`** (the async iterator, with `close` and `signal`), and `once` with `signal`.
- **`node:dns` and `dns/promises` with every name and constant;** each lookup fails with `ERR_NOT_SUPPORTED` until phase B4. got's cacheable-lookup imports it when it loads.
- **Unconnected sockets construct:** `new net.Socket()` (a `Duplex`) and `new tls.TLSSocket(stream)`, as in Node. Connecting still throws until B5. http2-wrapper builds a `TLSSocket` over a stream when it loads, to reach Node's internal `JSStreamSocket`. Its only use is HTTP/1 over an HTTP/2 proxy, which can't apply here.
- **Node's messages** for a refused connection (`connect ECONNREFUSED addr:port`, with `address`, `port`, `syscall` and `errno`) and an unknown host (`getaddrinfo ENOTFOUND`).
- **`ECDH.getPrivateKey()` drops leading zero bytes,** as Node's does.
  - **How it was found:** the webcrypto fixture failed about one run in 256, when Node's random key started with a zero.
  - **The fixture:** it now checks a key with a leading zero directly.

**Tested:**
- **`RealNpmPackagesTest`:** got 14.6.6 matches real Node 26.8.1. It covers JSON and form bodies, search params, auth, hooks, redirects, gzip, `HTTPError`, retries, `throwHttpErrors`, phase timeouts, refused connections, streaming with progress, pagination, and which timing phases exist. So do the 30 other fixtures, jsonwebtoken now including PS256.
- **`NodeNetworkStandInsTest`:**
  - the socket's events, addresses and reuse
  - TLS against a self-signed server: unverified (`DEPTH_ZERO_SELF_SIGNED_CERT`), then trusted through `ca`
  - `diagnostics_channel` publishing and tracing order
  - `events.on` with close, errors and abort
  - `dns`'s names
  - unconnected sockets

**Not done:** got's `http2: true`, which waits for the `http2` client step.

### D-115 — The isolate reports Node 26.8.1
**Date:** 2026-10-08 · **Status:** Accepted (Tony chose "Report Node 26.8.1"). Replaces D-102.

**Context:** D-102 left `process.version` empty, so libraries wouldn't take Node-only paths into what the isolate lacked. got 14 can't load without a version: it runs `process.versions.node.split('.')` when it loads. Since D-102, the isolate has gained:
- `fs`, `http`/`https`, `crypto`, `stream` and `zlib`
- native code, which is still refused when a package is published
- sockets, which come next

**The evidence:** with a version reported, all 31 real-library fixtures matched Node, and no other library's output changed.

**Decision:**
- **What's reported:** `process.version` is `'v26.8.1'` and `process.versions` is `{ node: '26.8.1' }`. That's the Node release the stand-ins are checked against, and nothing else (no `v8`, `uv` and so on).
- **Kept in step with the tests:** `RealNpmPackagesTest` fails unless it's run with that release, so the reported version and the reference move together.
- **Unchanged:** `os.release()`/`os.version()` stay empty, since there's no kernel to describe.

**Consequences:** libraries that gate features on a version turn them on. jsonwebtoken's PS256 is one, and it works.

### D-116 — Outbound `net` and `tls` sockets; real database drivers run in the isolate
**Date:** 2026-10-09 · **Status:** Accepted. Phase B's socket step (B5, network-reach.md §7), as planned and approved (Tony: "okay let's move forward").

**Decision:**
- **Scripts get Node's `net` and `tls`,** on Vert.x's `NetClient`.
  - **Gated:** every connection goes through the egress rules as `tcp` (D-111), to the address the gate checked.
  - **Limited by the cluster policy's new `limits`:** 120 new connections a minute and 256 open sockets per tenant on each node, and 16 per script. They show in the tenant's view.
  - **No idle timeout,** as in Node. Sockets close when their run ends.
  - **No servers:** TCP and UDP servers are refused (D-112).
- **The host's side, `SocketClient`:**
  - **Data:** a paused stream the script reads through the existing chunk flow. About 8 unread chunks, then the host stops reading, and TCP pushes back.
  - **Writes** complete when the connection takes them.
  - **`end()`** half-closes plain TCP (`shutdownOutput`) once writes have gone. Over TLS, it closes.
  - **`upgradeTls`** runs `upgradeToSsl` on the live connection.
  - **TCP keepalive** is on for every socket.
  - **Bytes are counted** per tenant (`c93_egress_bytes_total`).
- **TLS verification as Node does it:**
  - **The chain:** the handshake always completes. The host then judges the chain against the JVM's trust store, or the script's `ca`, which replaces it as in Node. A refusal carries OpenSSL's code and message, Node 26's `--use-system-ca` hint included.
  - **The host name:** checked by `tls.checkServerIdentity`, Node's algorithm reimplemented, or the script's own check.
  - **Ordering:** the script's writes wait until both checks pass, and a failed chain closes the connection on the host.
  - **Why not let the JVM refuse:** its refusal says only "PKIX path building failed", for every kind of bad chain, and the error codes are what Node code checks.
  - **No reach is added:** the script's own connection is the only one affected, and `rejectUnauthorized: false` already lets it skip verification.
  - **`fetch`/`https`** still verify in the JVM.
- **`tls.connect({ socket })`** upgrades a connected `net.Socket` in place. The new `TLSSocket` takes over its stream, including a read already in flight, and the old socket closes with it, as in Node.
- **What the drivers needed besides sockets:**
  - `util/types` as a module (pg)
  - `child_process`'s names, every function throwing `ERR_NOT_SUPPORTED` (nodemailer's sendmail transport and mongodb import it)
  - `Buffer`'s statics and prototype methods enumerable, as Node assigns them: safer-buffer copies them with `for…in`, and mysql2 sizes packets on a mock built the same way
  - `Buffer.copy` with Node's clamping
- **Two older bugs, found by the drivers:**
  - **`EventEmitter.once()` didn't go through `this.on()`,** so a `Readable` with only a `once('data')` listener never flowed. Every stream was affected.
  - **The bundler named the `import` condition explicitly,** which esbuild then applied to `require()` too. pg's `require('pg-pool')` got the package's ES-module namespace instead of its class.

**Tested:**
- **`SocketClientTest` (8):** data both ways, half-close, TLS (refused with Node's code, trusted through `ca`, unverified), an upgrade, the SMTP rule, all three limits, Node's errors, and the roots.
- **`NodeSocketsTest` (8):** event order, held writes, half-close, Node's errors, TLS cases and custom identity checks, the upgrade, the per-script limit with cleanup at the run's end, Node's `checkServerIdentity`, and backpressure on a 64 MB sender. It passes repeatedly and under CPU load.
- **`RealNpmPackagesTest`, against real Node 26.8.1:**
  - **A `sockets` fixture,** matching exactly against local echo, TLS, STARTTLS and half-close servers.
  - **Five drivers against real servers in containers** (with `C93_TEST_REAL_SERVICES`): pg (Postgres 18, plain and TLS through SSLRequest), mysql2 (MySQL 8.4), ioredis (Redis 7, pub/sub), mongodb (MongoDB 8.2) and nodemailer (Mailpit, STARTTLS). All 37 fixtures match.
- **`NodeStandInsTest`:** `once` through `on`, and `Buffer.copy` and enumerability, against Node's values.

**Not done:**
- **`https-proxy-agent`:** not tested yet. It should work, since it uses `net` plus `tls.connect({ socket })`.
- **`infoAccess` in peer certificates:** always `undefined`.
- **MongoDB 8.0** doesn't start on Linux 6.19 or newer (MongoDB's SERVER-121912), so the tests use 8.2.

### D-117 — Node's `http2` client, on Vert.x's HTTP/2 connections
**Date:** 2026-10-09 · **Status:** Accepted. Phase B's `http2` step (network-reach.md §12), approved with the plan (Tony: "okay lets do step 4 now").

**Decision:**
- **`http2.connect` opens a host HTTP/2 connection** (Vert.x's `HttpClientAgent.connect`, an `HttpClientConnection`), through the egress rules as `http`.
  - **Over TLS,** it's negotiated with ALPN `h2`. Over `http:` it's h2c with prior knowledge, as Node's client does.
  - **Server push is off.**
  - **It counts as one of the script's sockets** against the cluster policy's limits.
- **Verification is the sockets' (D-116):** the host judges the chain and refuses with OpenSSL's code; the script checks the host name with `tls.checkServerIdentity`.
- **Streams are requests on the connection.**
  - **Sending:** headers go at once (`sendHead`) or with the end of an empty request.
  - **Receiving:** the response's headers, body and trailers come back as host futures and a stream.
  - **Limits:** each stream takes one of the script's 16 concurrent requests, which it shares with `fetch` and `http`.
- **The session's events** (goaway, remote settings, errors) reach the script as JSON chunks on a host stream.
- **In the isolate:** `ClientHttp2Session` and `ClientHttp2Stream` with Node's events, headers rules, flags and error codes, plus `getPackedSettings`/`getUnpackedSettings`. Servers are refused (D-112).
- **`createConnection`** is accepted only when it returns one of the isolate's own sockets, which went through the rules. The session closes it and opens its own connection to the same origin. That's how http2-wrapper, and so got's `http2: true`, connect.
- **Every session and stream operation runs on the connection's event loop.** Calling in from the isolate's thread raced with Netty under CPU load: a ping after a reset went unanswered.

**Tested:**
- **`Http2ClientTest` (4):** TLS and h2c, trailers, resets, ping, settings, refusals (untrusted, not HTTP/2, bad scheme) and the shared limits.
- **`NodeHttp2Test` (3):** sessions in the isolate, refusals, the request limit, and closing at the run's end. It passes under CPU load.
- **`RealNpmPackagesTest`:** a `http2client` fixture over TLS and h2c, and got with `http2: true`, both matching real Node 26.8.1.
- **The other suites:** the 30 other fixtures and the 5 drivers against real servers still match.

**Not done:**
- request trailers (`sendTrailers`)
- priorities
- `state`
- `END_STREAM` in response flags beyond what the status implies

### D-118 — HTTP/2 completed as Node 26 has it: request trailers, `state`, priorities, real flags
**Date:** 2026-10-09 · **Status:** Accepted. Closes what D-117 left out (Tony: "lets address all these … our polyfills should be matching node btw").

**Decision:**
- **What Vert.x doesn't expose is read from the Netty connection beneath it** (`NettyHttp2`): Vert.x's codec connection and its `Http2ConnectionHandler`. If that isn't there, the flags fall back and `state` is `{}`.
- **Flags are the frames' own.** A listener on Netty's decoder records each HEADERS frame's flags in arrival order: the response's, then the trailers'. This replaces D-117's guess (PRIORITY always, END_STREAM from the status), which was wrong against a Node server, which sends 4.
- **Request trailers, as Node's:**
  - `waitForTrailers`, `wantTrailers` after the body, then `sendTrailers`, with Node's errors and checks
  - empty trailers when nobody listens
  - an empty DATA frame for empty trailers
  - `waitForTrailers` ignored on a request with no body

  The trailers go through Vert.x's own HTTP/2 stream, reached by reflection, since its client has no public way to send them. A HEADERS frame written on the Netty encoder reached the server, but Vert.x never saw its request end, and the response never ended.
- **`session.state` and `stream.state`, as Node's** (nghttp2's meanings), from Netty's connection, flow controllers and HPACK tables. The HPACK sizes are read by reflection and are 0 if a Netty release moves them.
  - **A stream's state during an event** is a snapshot taken as its frame was read: Node reads nghttp2 while it handles the frame, and by the time the script runs Netty has often closed the stream.
  - **The snapshot is forgotten** once the script has the trailers or resets the stream.
- **Priorities as Node 26:** RFC 9113 deprecated priority signalling. `priority()` does nothing, a request's `weight` isn't sent, and either emits `DEP0194` once; `stream.state.weight` is 16.
- **`process.emitWarning` is now Node's:**
  - **The warning:** an `Error` named after its type, with its `code` and `detail`.
  - **Delivery:** `warning` on the next tick, and Node's `(node:1) [CODE] Name: message` line in the log.
  - **Switches:** `noDeprecation` and `throwDeprecation`.
  - **`util.deprecate`** goes through it with its code.

**Tested:**
- **`Http2ClientTest` (5):** request trailers reaching the server, the real flags of an empty response and of trailers, and both state objects.
- **`NodeHttp2Test` (4):** the trailer events and errors in order, `state` during and after a stream, and both warnings. Both suites pass under CPU load; the stream-state test first failed there, and that failure led to the snapshot.
- **`RealNpmPackagesTest`'s `http2client`:** extended with trailers (event order, every error, what the server received, no listener, a `GET`), the state objects, flags and priorities. It matches real Node 26.8.1. The harness now runs Node with `--no-warnings`, since warnings went into its JSON on stderr; the `warning` events are still compared.

**Found while testing, and fixed:**
- **TLS sockets offered the first connection's ALPN list.**
  - **The cause:** Vert.x caches a client's TLS context provider by client certificate, trust options and CRLs, and creates it with the ALPN list of the connection that first needed it. Every script socket used Vert.x's single trust-all options, so all of them shared one provider.
  - **How it showed:** in a full run, got's ALPN check got `http/1.1` from a server that offers `h2`.
  - **The fix:** each ALPN list gets its own trust-all options instance (at most 64 kept; past that the map starts over, which only costs new contexts). `SocketClientTest` checks `h2`, then none, then `http/1.1`, then `h2` again on one client.
- **`http.request` takes its expected protocol from its agent, or else from `_defaultAgent`, as Node's `ClientRequest` does.** http2-wrapper falls back to HTTP/1.1 that way.

**Differs:** `session.state.outboundQueueSize` counts Netty's unflushed writes, not nghttp2's frame queue. (Closed by D-119.)

### D-119 — `session.state.outboundQueueSize` is nghttp2's queue, kept in the isolate
**Date:** 2026-10-09 · **Status:** Accepted. Closes D-118's remaining difference (Tony: "lets address the remaining difference").

**What Node's number is,** found by probing Node 26.8.1:
- **It's nghttp2's outbound queue:** control frames (HEADERS, SETTINGS, PING, GOAWAY, RST_STREAM, trailers; not DATA) submitted and not yet written. Node writes them out on the next turn of its event loop, so the count goes back to 0 then.
- **What it shows at each point:**
  - 1 at `connect` (the client's SETTINGS)
  - 1 more for each `request()`, `settings()`, `ping()` and `goaway()` in the same turn
  - nothing for closing a stream whose HEADERS haven't gone (nghttp2 drops both)
  - one more for closing one that's already open
  - the peer's SETTINGS ACK at `remoteSettings`
- **Two places where it isn't deterministic, even in Node:**
  - **Inside `data` handlers:** the WINDOW_UPDATEs nghttp2 queues show (0, 1, 2, 2, 1, …), following how TCP splits the bytes into reads.
  - **At `remoteSettings`:** 1 or 2 against the same server, depending on whether the peer's SETTINGS arrive before or after Node wrote that turn's frames.

**Decision:**
- **The isolate keeps the same queue with Node's flush:** each control frame the script submits adds 1, and a `setImmediate` empties it.
  - **Why this isn't a made-up number:** those frames really are still in the isolate then, on their way to the host.
- **The peer's SETTINGS are delivered on the turn after `connect`,** as input read after that turn's frames were written. They come before anything else from the peer, as on the wire.
- **WINDOW_UPDATEs during `data` aren't modelled,** since even Node's value there depends on how reads are split. The isolate shows 0, as Node does once it has written.
- **The host still reports Netty's unflushed writes** in its own `state`; the isolate puts nghttp2's queue in its place.

**Also fixed:** under CPU load, `stream.state` could read state 7 (CLOSED) during `response`, where Node shows 5. Netty had closed the stream but not yet let it go. A stream in that state now answers with its last frame's snapshot (D-118), since a Node script never sees one.

**Tested:**
- **`NodeHttp2Test` (5):** the queue through connect, a request, the settings ACK, and several frames in one turn.
- **`RealNpmPackagesTest`'s `http2client`:** compares 14 points of the queue with real Node. At `remoteSettings` it compares only that the ACK is queued, since Node's own value there varies.
- **Under load:** both suites pass 4 times out of 4 under full CPU load.

### D-120 — `stream.state` worked out in the isolate, as nghttp2 holds it for Node
**Date:** 2026-10-09 · **Status:** Accepted. Replaces D-118's per-frame snapshot (Tony: "how can we fix this?", about state 7 showing under load).

**Why the snapshot wasn't enough:**
- **How it worked:** D-118 read Netty's live state and fell back to a snapshot when Netty had closed the stream.
- **The flaw:** a live read is the network's state *now*, which can be ahead of the event the script is handling. Under load it showed CLOSED where Node shows half-closed local.

**What Node shows,** probed on Node 26.8.1 (the same in every run):
- **Writing happens on the next turn of the event loop:**
  - A stream is IDLE, with weight 0 and window 0, until its HEADERS are written.
  - `end()` changes nothing until END_STREAM is written.
- **Handlers run inside nghttp2's frame callbacks:** in `response`, `data` and `end`, the peer's END_STREAM hasn't taken effect, so `remoteClose` is 0. It shows on the next turn.
- **`ready`** is emitted inside `request()` on a connected session.
- **The window:** it drops by each DATA frame, and refills after the handler of the frame that brought it to half.

**Decision:**
- **The isolate works the state out from the stream's history,** with no live read at all:
  - the turns the session writes in (the same queue as D-119)
  - when HEADERS and END_STREAM were submitted
  - the DATA frames the script has had (each reaches it as one chunk)
  - the peer's END_STREAM, applied the turn after the script saw it

  `nextStreamID` and the stream's `id` are counted as nghttp2 numbers streams, as they're submitted.
- **Ordering, as a network imposes it:** an answer from the host is handed over only on a turn after the write it answers, however quickly the host answered. Input arriving means what was queued has been written.
- **The host's `state()` is Netty's live state again,** for host callers. The binding the isolate used for it is gone.

**Found while testing, and fixed:**
- **A stream's host side was let go when its response ended,** though the script's side could still be open. The script's later `end()` failed with "The session has been destroyed". It's now let go when the script's stream closes.
- **`end()` on a socket the peer had already closed failed with Netty's `ClosedChannelException`.** That escaped as an uncaught error in one full run's pg fixture, since postgres closes after pg's Terminate. Node treats this as done (its shutdown takes ENOTCONN as success), and so does the host now. `SocketClientTest` reproduces it.

**Tested:**
- **`NodeHttp2Test` (6):** a stream's state turn by turn, and the queue rules from D-119.
- **`RealNpmPackagesTest`'s `http2client`:** walks a stream through its life against real Node: request, the next turn, response with our side open, data, end, the turn after, `end()`, and close. For a 300 KB download it compares the states, and that the window stays in bounds.
  - **Where comparing absolute values stopped making sense:** Node's own outbound count at input-driven points varies under load, so from there the fixture compares what the script adds.
- **Under load:** both pass 4 times out of 4 under full CPU load.

**Differs:** a large body's `localWindowSize` per `data` event. The server cuts frames according to when the client's WINDOW_UPDATEs reach it, and Node's `data` chunks follow TCP reads, so neither side's sequence is fixed.

### D-121 — HTTP/2 flow control matched exactly, and the event loop runs Node's phases
**Date:** 2026-10-09 · **Status:** Accepted. Tony asked to address what D-120 left: a large body's windows, and the outbound count while data arrives.

**Why it couldn't be compared before:** Node itself gave different answers from run to run.
- **Frame sizes:** a server cuts a body into frames as the client's WINDOW_UPDATEs reach it.
- **'data' events:** Node fires one per piece of a TCP read.

**Making it comparable:** `RealNpmPackagesTest` gains a paced h2c server, written at the frame level.
- **What it sends:** the same frames to every client (18 of 16384 bytes, then 5000), each followed by a PING whose ACK shows the client has read it.
- **What that gives:** every frame arrives in its own read, and Node 26.8.1 then gives identical output every run.
- **What it showed about nghttp2:**
  - **The connection's WINDOW_UPDATE** is queued *before* the handlers of the frame that brings it to half its window. The handler and its ticks see it queued (count 1) and the session's window refilled.
  - **The stream's WINDOW_UPDATE** is queued after the handler's ticks and written by the next turn. It's never counted, and the stream's window is full again by then.

**Decision:**
- **The isolate models both windows that way.** The session's `effectiveRecvDataLength` and `localWindowSize` are now computed in the isolate too: they were read live from Netty, which raced like the stream's state did.
- **Network reads stay apart:**
  - **On the host:** each HEADERS and DATA frame is tagged with the network read it came in (a count of `channelReadComplete`).
  - **In the isolate:** a later read is handed to the script on a later turn, among that turn's I/O callbacks and after the immediates the earlier read queued. Frames that came in together are still handled together, so bulk transfers aren't slowed.
  - **Why:** without this, the host's eagerly buffered frames reached the script in one turn, where Node handles one read per turn.
- **The event loop runs Node's phases:**
  - **The order:** I/O callbacks, then the check phase, then timers.
  - **Microtasks and the rejection check** run after every callback. Before, posted tasks ran as a batch with microtasks only after it.
  - **`setImmediate`:** it's a real check-phase queue and returns an `Immediate`. It used to be a 1ms timer that ran with the timers. Immediates queued during the check phase wait for the next turn.
  - **A next-turn queue** is the isolate's own, used for the reads above.

**Tested:**
- **`http2client`:** compares every `data` event from the paced server with real Node, plus its tick and the next turn: the stream window, the session window, the received count and the outbound queue.
- **A new `immediates` fixture:** checks the orderings Node guarantees from an I/O callback (ticks and microtasks, then immediates in order with their own microtasks, cleared ones skipped, new ones next turn) and the `Immediate` handle.
- **Under load and the full suite:** both pass 4 times out of 4 under full CPU load, and the full local suite passes.

**What still varies, by nature:** with a real server and a real network, the frames and reads, and so the windows per event, differ between runs of Node itself. Given the same frames in the same reads, the isolate now gives Node's values.

### D-122 — `dns` as Node's, on the egress gate's resolver, showing only what the tenant could reach
**Date:** 2026-10-09 · **Status:** Accepted. Phase B's `dns` step (network-reach.md §6, §8.2), with one change Tony made: `setServers` is allowed, gated ("wouldn't we just make it block trying to set servers on a private network? … why would we block a public dns server?").

**Decision:**
- **One resolver (`DnsResolver`) for the gate and for `dns`:**
  - **Lookups** ask the operating system as getaddrinfo does. `dns.lookup` keeps the system's order: a JDK `InetAddressResolverProvider` hands the JVM its built-in resolver unchanged, and `dns.lookup` asks that resolver for both families with no preference. `InetAddress` itself puts IPv4 first, so it couldn't be used.
  - **Queries** use Netty's DNS client as c-ares behaves: explicit servers, each in turn on each try, a per-try timeout, TCP after truncation, no caches.
  - **Decoding:** records are decoded to Node 26's shapes, following compressed names by absolute offset. Character-strings are read as Latin-1, as Node reads them, and IPv6 is written compressed (RFC 5952).
  - **With `C93_EGRESS_DNS` set,** lookups and the gate ask those servers too, for split-horizon resolvers.
- **Filtering (§8.2), in `NodeDnsApi` with the gate's `DnsView`:**
  - **Addresses:** visible if the tenant could connect to them on some port by some protocol. A deny rule hides one only if it covers every port and every protocol or `dns`. Cluster nodes are hidden unless the development switch is on.
  - **Internal names** (single labels, the special-use suffixes, `C93_EGRESS_INTERNAL_DOMAINS`) answer `ENOTFOUND` unless an allow rule names them or the development switch is on.
  - **Records** naming internal hosts are dropped.
  - **Reverse lookups** need a visible address.
- **`setServers`:** each server is checked by the gate as a `dns` connection to its address and port. Refused ones are left out, and if none remain the query fails with `ECONNREFUSED` and the gate's reason. The cluster's own servers aren't checked.
- **Limits:** 16 queries at once per run, the rest queued; 10 s per query.
- **The module:** all of Node 26's `dns` and `dns/promises`, rewritten to its behaviour:
  - **Calls:** `lookup` with every option and Node 26's refusal of empty names, `lookupService`, every `resolve*`, and `reverse`.
  - **`Resolver`:** `timeout`, `tries` and `cancel`, sharing the default resolver with `dns.promises`.
  - **Errors:** `DNSException`'s shape, and the validators' messages.

**Tested:**
- **`DnsResolverTest` (6):** every record type with compressed names, each failure, TCP after truncation, servers in turn, and the system's lookups.
- **`NodeDnsApiTest` (5):** the filtering, the development switch, gated servers, queueing, and visibility under rules.
- **`NodeDnsTest` (2):** the isolate end to end, and a private server refused with its reason.
- **`RealNpmPackagesTest`:**
  - **A `dnsclient` fixture** matches real Node 26.8.1: every query and its errors, `{ ttl }`, ANY, TCP, timeouts, `cancel`, the validators, lookups in every order, and `lookupService`.
  - **The mongodb fixture** connects with `mongodb+srv://` through SRV and TXT records from the test's DNS server, as under Node.
- **A shared `TestDnsServer`** (UDP and TCP, compressing names as real servers do) is in core's test jar, used by core, the runtime and the CLI's tests.

**Differs:** none known.

### D-123 — Node's HTTP servers in services, reached by hostname and port
**Date:** 2026-10-09 · **Status:** Accepted. Phase B's step 6 (network-reach.md §5), building D-112 (Tony: "let's do phase b step 6").

**Decision:**
- **Routes and ports:**
  - **On the node:** `ServeRegistry` holds a run's main server (`ctx.serve`) and its servers by port.
  - **The ingress** reads a port from the host (`<service>--<tenant>--<port>`), from a custom domain claimed with a `port`, or from a declared `path` prefix under the service's host (taken off, and sent as `X-Forwarded-Prefix`). It forwards the run and port to the run's node; the service's host goes to the main server, or else the first port that listened.
  - **A service's `ports`** (`[{port, path?, expose?}]`) change routing without a new revision. `expose: false` takes a port off the network.
  - **The API** shows `routes`, and the CLI has `services routes`, `domains add --port`, and `ports` in `cluster93.json`.
  - **Not opaque route ids:** the run and port suffice, since the internal endpoint is authenticated and nothing listens on the node. This saves a cluster write per `listen`.
- **The exchange** (`HttpExchange`, `ServerExchange`, the ingress):
  - **Requests:** the client's own headers, address and HTTP version are forwarded so a Node server sees exact `rawHeaders` and `req.socket`. The client's `X-Forwarded-*` are dropped and only the ingress's added.
  - **Responses:** headers in order with repeats, the reason phrase, a kept `Content-Length`, response trailers, a `closed` signal when the client leaves, and `Expect: 100-continue` answered by the ingress.
- **Node's server in the isolate** (`http.js`):
  - **Classes:** `Server`, `ServerResponse` and `OutgoingMessage`, as `_http_server.js` and `_http_outgoing.js`, with `IncomingMessage` shared with the client.
  - **`listen`:** every form, with Node's errors, and ports shared across distinct addresses as the OS allows (Fastify listens on localhost's two).
  - **`https.createServer`** is accepted, with `key` and `cert` set aside.
- **How a service runs:**
  - **Lasting:** a listening server keeps the run going after the default export returns; a module that only listens needs no default export.
  - **A stop** emits `SIGTERM` on `process`; with no listener, the run ends.
  - **Jobs** can't listen.
- **The other modules:**
  - **Timers:** `setTimeout`/`setInterval` return Node's `Timeout`.
  - **`async_hooks`:** `AsyncResource` complete; `AsyncLocalStorage.run` refused as D-112 recorded.
  - **`perf_hooks`:** added.
  - **`dns.lookup`** answers `localhost` and `*.localhost` as loopback (RFC 6761).

**Found while testing, and fixed:**
- **A spoofed `X-Forwarded-Prefix` reached the app** through the forwarded raw headers. The client's whole `X-Forwarded-*` family is now kept out.
- **The ingress dropped the app's reason phrase.**

**Tested:**
- **`NodeServersTest` (2), through the real ingress:**
  - a plain `app.js` with no default export, on three ports and `listen(0)`
  - raw headers and the client's address
  - `Set-Cookie` repeats, a reason phrase, the implicit length and chunking, HEAD
  - `100-continue`, trailers, and a client leaving
  - port hosts, a path prefix (with a spoofed prefix dropped), `expose: false`, an unknown port
  - `EADDRINUSE`, `SIGTERM` closing everything well inside the grace period, and a job refused
- **`ServicePortsTest` (3):** validation, prefix matching and port hosts.
- **Regressions:** every existing serving test, and the real-npm suite with the drivers.

**Frameworks against real Node** (§5's proof), in `RealNpmPackagesTest`. Each runs under Node on a port and as a service whose script only imports it (no default export). The same requests go to both, and the answers are compared: status, reason phrase, every header in order (except `Date` and the hop-by-hop headers the proxy owns) and body. The requests:
- routes and parameters, JSON and urlencoded bodies, two cookies
- a redirect, an error handler, the framework's own 404
- a streamed response, HEAD, a 2 MB upload
- a client that leaves mid-response

**Results:**
- **Express 5 and Fastify 5 match on every request.** Fastify listens on localhost's two addresses.
- **What getting there needed:**
  - stand-ins for `worker_threads`, `module` and `v8` (read at load by pino, thread-stream and Koa)
  - the bundler knowing the new modules
  - one package per framework (together over 10 MB)
  - a fix to `perf_hooks`, which tried to redefine the frozen global `performance` (observers now hear marks through an internal hook)
- **Koa 3 waits on the WHATWG globals:** it checks every body against `Blob`, `ReadableStream` and `Response`, which the isolate doesn't have. Tony chose to build them fully next (2026-10-09).


### D-124 — The WHATWG globals as Node's, loaded on first use; the global `fetch` becomes Node's
**Date:** 2026-10-09 · **Status:** Accepted. Tony chose "Build them fully now" when Koa 3 needed them (D-123), under the standing rule that our polyfills match Node.

**Decision:**
- **Which globals:**
  - `Blob` and `File`, the same classes as `buffer`'s
  - `Headers`, `Request`, `Response`, `FormData` and `fetch`
  - the WHATWG streams, and `node:stream/web`
  - `TextEncoderStream`, `TextDecoderStream`, `CompressionStream` and `DecompressionStream`
  - `MessageChannel`, `MessagePort`, `MessageEvent` and `BroadcastChannel`
  - plus Node's `fromWeb`/`toWeb` on `Readable`, `Writable` and `Duplex`
- **Loaded on first use:**
  - **Groups:** five scripts (`js/web/streams`, `blob`, `fetch`, `messaging`, `codecs`) behind getters on `globalThis`.
  - **Loading:** `WebBinding.load` compiles one from the node's code cache the first time a script reads one of its names, and the getter then settles to a plain, writable value, as Node's globals are.
  - **Why:** the streams alone are about 100 KB of JavaScript. A job that never touches them shouldn't pay for them at start-up, which D-106 measured at 3.5 ms.
- **The streams are web-streams-polyfill 4.3.0,** vendored by `scripts/vendor-stream` with the rest of the stream code:
  - **Why vendored:** it's spec-complete and tested against the WPT suite. Writing our own would mean re-deriving the same spec.
  - **One patch:** the `highWaterMark` check throws Node's `ERR_INVALID_ARG_VALUE`. The build fails if the patch stops applying.
- **The rest is written to undici's behaviour:**
  - **Covered:** messages, header order and `getSetCookie`, the multipart boundary format, method normalisation, null-body statuses, default request headers, redirect rules (303 and POST, cross-origin header stripping, 20 at most), content decoding, and `TypeError: fetch failed` with a `cause`.
  - **The transport:** the same streamed host request as `node:http` (D-109), so the egress gate, the 16-request cap and streaming bodies are shared.
- **The global `fetch` becomes Node's.**
  - **The host's fetch** (JSON bodies, `timeoutMs`, no redirects) moves to `import { fetch } from 'c93:fetch'`.
  - **Why:** code written for Node, and libraries that call `fetch`, expect Node's. A global that differs breaks them quietly: redirects not followed, and no `Response` class to check against.
  - **The cost:** scripts that relied on the host's JSON bodies or `timeoutMs` on the global need the import.
  - **Replaceable:** the global is now writable, as under Node.
- **Compression streams** use the host's zlib. Brotli decompresses but doesn't compress, as with `zlib`.
- **Messaging stays within the job:**
  - **Delivery:** messages are `structuredClone`d and delivered with `setImmediate`. Ports have Node's `on`/`once`, and closing a port emits `close` on both ends.
  - **`BroadcastChannel`:** reaches only channels in the same job, since there are no workers or other jobs to reach.

**Tested:**
- **The `webglobals` fixture against real Node** (`RealNpmPackagesTest`): every group above, its errors (codes and messages), `fetch` against a real server, and `stream/web` with `fromWeb`/`toWeb`. The output is identical to Node's.
  - **Covered:** redirects followed, manual and refused; gzip; a 1 MB body buffered and streamed; 404; aborts; a refused connection; and a bad URL.
- **Koa 3** with `@koa/router` and `@koa/bodyparser` joins Express 5 and Fastify 5 in the framework comparison, and matches Node on every request.
- **`HostBridgeTest`'s host-fetch tests** now import `c93:fetch`.
- **Regressions:** the full local suite, with the real-npm suite and the drivers.

**Found while testing, and fixed:**
- **A race in the first version:** `fetch` asked for the response only after ending the body. A server that answered quickly settled it first, and the request had already left the registry ("The request is already finished"). The response is now requested as soon as the request opens, as `http.js` does.
- **`docs/scripting/node-compat.md` still listed `worker_threads`, `module` and `v8` as refused** after D-123 added them. Corrected.

### D-125 — WebSockets as Node's: tunnelled through the ingress, servers' `upgrade`, clients over the job's own sockets
**Date:** 2026-10-09 · **Status:** Accepted. Phase B's step 7 (network-reach.md §13; Tony: "alright.. let's do it"). Phase B is complete.

**Decision:**
- **The app answers the upgrade itself, as on Node.**
  - **On the node:** a request that asks to upgrade (`Connection: upgrade` and an `Upgrade`, as llhttp decides) reaches the server's `upgrade` listener with a real `net.Socket` on the client's connection, unless `shouldUpgradeCallback` (default: is there a listener) says no.
  - **Reading the answer:** the node reads the head the app writes there. A 101 makes Vert.x switch the connection, with the app's headers. Anything else (a 401 from ws's `verifyClient`) becomes an ordinary response, its body what follows the head. Closing without an answer closes the client's connection.
  - **Why the app's own bytes:** the app chooses the subprotocol, the extensions and whether to accept, exactly as under Node. Handling the handshake in the host would have meant re-implementing those choices.
- **The ingress tunnels; it doesn't parse frames.**
  - **What it tunnels:** a GET over HTTP/1.1 with no body that asks to upgrade goes to the run's node with its `Connection` and `Upgrade`, through Vert.x's `connect()`. On a 101 both connections become one byte tunnel with backpressure; otherwise the answer is relayed and the connection let go.
  - **Why GET only:** that's what WebSocket clients send, and the only method Vert.x's client switches for. Other upgrade requests are ordinary requests.
  - **No frame-size limit at the ingress:** it never sees frames, so that limit is the app's (ws's `maxPayload`).
- **Limits:**
  - **Per run:** `C93_SERVICE_MAX_WEBSOCKETS` (1,000) upgraded connections, counted on the run's node apart from the 64 requests in flight (an upgraded connection can last hours). Past it, a 503.
  - **Idle:** `C93_INGRESS_WEBSOCKET_IDLE_TIMEOUT_MS` (10 minutes) with no byte either way.
  - **End of run:** the run's upgraded sockets close when it ends.
- **Clients write HTTP/1.1 over the job's own sockets.**
  - **When:** an `http.request` that asks to upgrade, or brings `createConnection` (no longer refused: a socket from `net`/`tls` is already gated), is written on a `net`/`tls` socket in the isolate and its response parsed there, as Node's client does.
  - **Responses:** a 101 hands the socket to `upgrade`. Other answers are parsed by length, chunked or until close, with 1xx `information`.
  - **The gate checks these as `tcp`:** the job writes the bytes, so the host can't vouch they're HTTP, and an `http`-only rule must not cover raw bytes.
- **The global `WebSocket` is undici 8.10's** (Node 26's): its source was read and its behaviour followed.
  - **What follows undici:** the handshake headers in its order, its checks of the 101, the frame parser and its failure codes, the close and fail rules, events (`CloseEvent`, `ErrorEvent`, `MessageEvent`), binary types and the messages.
  - **Compression:** incoming permessage-deflate is decompressed and nothing is compressed going out, as undici does.
  - **Where it runs:** it's a lazily loaded web script (D-124) driving `node:http`'s client. A classic script has no `import()`, so the web binding hands it a built-in's namespace through the job's resolver (`node:http`, `https`, `crypto`, `zlib` only), the same instance the job's own imports get.
- **What ws needed elsewhere, built as Node's:**
  - **`zlib`:** `flush()` writes a marker after what's pending, as Node's, and a compressor gives out what it holds. `windowBits` under 15 for compressing is honoured by storing, since the JVM's deflater has only the 15-bit window and stored blocks refer back to nothing. The zlib header states the window, with zlib's level bits.
  - **`Buffer`:** integers of 1 to 6 bytes, with Node's range errors.
  - **The bundler:** a `require()` of a left-out optional native package (`bufferutil`) throws `MODULE_NOT_FOUND`, so ws falls back; it used to return an empty module.
  - **`TextDecoder`'s `fatal` option**, for undici's invalid-UTF-8 failure.
  - **`URL`'s errors are Node's:** "Invalid URL", with `input` and `base`.

**Found while testing, and fixed:**
- **Every service stopped answering after 64 requests in a run.**
  - **The cause:** the internal endpoint counted requests in flight in the response's end and close handlers. `ServerExchange` then set its own, and Vert.x keeps one of each, so the count never went down: the 65th request, and every one after, got a 429.
  - **The fix:** exchanges count through a release hook the exchange runs once, and `write()` no longer replaces the close handler either.
  - **The test:** 80 requests in a row; it fails at the 64th on the old wiring.
- **A non-101 answer to the ingress's `connect()`** left Vert.x's request unended, and ending it threw. The connection is now let go after the answer is relayed.
- **A flaky test, not a product bug:** the tunnel test's connection sometimes closed mid-transfer.
  - **The cause:** Vert.x 5 closes a client's connections once the client object is garbage-collected, and the test made a `NetClient` per connection without keeping it. A 2 MB message's allocations triggered the collection.
  - **The fix:** the test holds one `NetClient`, and it then passed 8 runs in 8.
  - **What changed in the product meanwhile:** the tunnel's backpressure now follows each write's completion rather than drain events, which is simpler.

**Tested:**
- **Against real Node, in `RealNpmPackagesTest`:**
  - **`wsclient`:** the ws client against a local WebSocket server with compression. Covered: the handshake as the server received it, text, binary and 300 KB messages, pings both ways, closes from either side, a refused upgrade, nothing listening, and a bad URL.
  - **`wsglobal`:** the same for the global `WebSocket`, plus its constructor and close errors, binary types and Blobs, `bufferedAmount`, and closing while connecting.
  - **`wsserver`:** the ws library's server as a service, compared conversation by conversation with the same app under Node. Covered: the 101's headers, messages both ways, a ping, closes from either side, the server's own log, and `verifyClient` refusing.
  - **Integer and zlib checks** in `nodemodules`, and `fatal` in `webglobals`.
  - **The result:** all identical.
- **`NodeUpgradesTest`, through the real ingress:**
  - the tunnel both ways, with 2 MB echoed back under backpressure
  - a 426 answer, a silent close, a server without a listener, and an upgrade with a body
  - the per-run limit and the idle timeout
  - the 64-request regression
- **`NodeWebSocketTest`** (the global against a local server) and **`NodeZlibTest`** (sync flush, small windows, headers).
- **Regressions:** the full local suite, with the real-npm suite and the drivers.

**Differs from Node:** a 101's reason phrase is always "Switching Protocols". A server's `upgrade` gets an empty `head`, since early bytes come as the socket's data. A `windowBits` under 15 stores rather than compresses.

### D-126 — An engine-neutral runtime, GraalJS beside Javet, and tenant workers as native images
**Date:** 2026-10-09 · **Status:** Proposed. The neutral layer, the GraalJS adapter and the comparison are approved to build (Tony); the engine and the worker↔node protocol are decided after the comparison. Design: [engine-neutral-runtime.md](design/engine-neutral-runtime.md).

**Built so far:**
- **Step 3, the comparison in native-image workers (2026-10-10):** `cluster93-worker` and `benchmarks/engines/`; results and trade-offs in the design doc's §8.
  - **Security:** GraalJS stopped 7 of 9 memory attacks at the run and kept 1 bounded. V8 needed the kernel to kill the worker for 7.
  - **Speed:** V8 is 2–9× faster on object-heavy and JSON code, the two are level on numeric code, and GraalJS's first runs are slower.
  - **Memory:** GraalJS uses more.
  - **Direction (Tony, 2026-10-10):** GraalJS is the better engine for security; the trade-offs are documented for the engine decision.
- **Step 2, embedded GraalJS (2026-10-10):** `cluster93-runtime-graaljs` passes the conformance suite, 136 of 136.
  - **Where it runs:** a spawned polyglot isolate with a heap limit per run, under the `TRUSTED` policy, locked down by hand, because the stricter policies refuse `Error.captureStackTrace` and rejection tracking.
  - **Modules:** served with their imports pre-resolved, since GraalJS's loader never says who imports.
  - **The one difference declared:** GraalJS doesn't report rejections handled in a later turn (`rejectionHandled`).
- Step 1, the engine-neutral layer with Javet alone (2026-10-09). The conformance suite passes per class exactly as before, the real-npm comparisons still match Node, and start-up time is unchanged (4.26 ms against 4.53 ms for a trivial job, same machine and day).

**Why:**
- **Memory outside the isolate's limit.** A security review found that `C93_MAX_HEAP_MB` caps only V8's heap. ArrayBuffers (one 8 GB), resizable and growable buffers, WebAssembly memory, ICU objects (`toLocaleString` with options: +500 MB per 20,000 calls, never collected until a full GC) and copies into Java all escape it, and one line of script can get a node OOM-killed. Services and retried jobs then repeat that node after node. No data or host escape was found.
- **Engine-level security.** V8 is C++, and its bugs are the usual route out of a JavaScript sandbox. GraalJS is a memory-safe engine with sandbox policies, available on Community Edition since GraalVM 25.1. Tony's concern is the engine itself.

**Decision (proposed):**
- **Two seams.** The bindings and the run's orchestration move to a new `cluster93-runtime-js`, written against a small SPI (`JsEngine`, `JsContext`, `JsArg`, `@HostFunction`). Javet and GraalJS each implement it. The placement seam is the existing `HostApis` interfaces: served in-process today, and by a `RemoteHostApis` in a worker later.
- **One conformance suite** (today's runtime tests) runs on every engine.
- **Tenants run in worker processes,** one per tenant, each with a kernel memory cap (cgroups; measured to work without root, while per-process limits crash V8 at start-up). Workers are GraalVM native images; the node stays a full JVM.
  - **Javet runs in a native image:** a substitution for `JavetClassLoader.loadClass`, and JNI registration of its classes.
  - **The cost:** it starts in 0.7 s, with about 50 MB of its own memory plus about 3 MB per idle service.
- **The engine is chosen by measurement:** both engines in native-image workers, fully warmed up, on computation, our polyfills, host-heavy work and a service-like load; memory per context; and the hostile memory tests under a cgroup cap.
- **The worker↔node protocol is designed now and built after the engine is chosen.**
  - **The shape:** node-runtime.md's JSON-RPC (D-084–D-086), sent as length-prefixed binary frames with raw bytes attached, over the worker's stdio pipes.
  - **Streams:** credit-based flow control per stream and per connection.
  - **What runs where:** calls with authority are answered on the node; pure computation (crypto, zlib) stays in the worker.
  - **Trust:** the worker is untrusted, bound to one tenant for life, and the node keeps its identity and its limits.
- **Not yet:** the server keeps Javet in-process, and there's no engine setting (Tony: several engines are for later).

**Docs corrected now:** `SECURITY.md` and the README no longer claim the heap limit bounds an isolate's memory.

### D-127 — Deployment-configurable runtime engine and standalone in-cluster Worker ↔ Node protocol
**Date:** 2026-10-10 · **Status:** Accepted. Supersedes D-126's "single engine winner" assumption. Specification: [worker-node-protocol.md](design/worker-node-protocol.md).

**Decision:**
1. **Engine selection is a deployment-level choice (`C93_WORKER_ENGINE`):**
   - Both **GraalJS** and **Javet (V8)** are retained as supported first-class engines behind the `JsEngine` SPI.
   - GraalJS is validated with **137/137 tests passing** on the conformance suite and stops 7 of 9 memory exhaustion attacks inside the isolate without requiring kernel intervention. It is the recommended engine for untrusted multi-tenant workloads.
   - Javet (V8) is 2–9× faster on object/JSON-heavy code and cold starts, making it the recommended engine for trusted, performance-sensitive workloads.
   - Operators can choose which engine to boot based on their security vs throughput requirements.
2. **Dedicated In-Cluster Worker ↔ Node Protocol Specification:**
   - Section 7 of `engine-neutral-runtime.md` is extracted into a standalone normative specification: `docs/design/worker-node-protocol.md`.
   - Defines length-prefixed binary framing (1 MiB max frame, split JSON metadata and raw contiguous bytes), HTTP/2-style flow-control credit windows (256 KiB per stream, 4 MiB per connection), and RPC lifecycle (`HELLO`, `BIND`, `RUN`, `CALL`, `RESULT`, `CREDIT`, `STATE`, `STATS`).
   - Clearly isolates the scope: applies exclusively to **in-cluster tenant workers** (local child processes, gVisor containers, Firecracker microVMs). External devices dialing out over TLS WebSockets remain scoped to `cluster93-link` (`ideas/links-and-capabilities.md`).

**Cost:**
- Maintaining both engine adapters across future JavaScript feature polyfills (guarded by the shared 137-test conformance suite).
- Implementing the binary frame codec and RPC dispatcher in Step 4 (`cluster93-runner-protocol`).

### D-128 — Implementation of in-cluster worker-node IPC framing, RemoteHostApis proxy, and codebase architecture guide
**Date:** 2026-10-10 · **Status:** Accepted. Implements D-127 Step 4 in module `cluster93-runner-protocol`. Specification: [worker-node-protocol.md](design/worker-node-protocol.md).

**Decision:**
1. **`cluster93-runner-protocol` Module Created:**
   - Houses the wire-format binary frame encoder and streaming chunk reassembler (`FrameCodec`, `Frame`, `FrameType`).
   - Implements HTTP/2-style flow control windows (`CreditManager`) with 256 KiB initial stream windows and 4 MiB connection-wide memory caps.
   - Houses domain message models (`HelloMessage`, `WelcomeMessage`, `BindMessage`, `RunMessage`, `CallMessage`, `ResultMessage`, `ErrorMessage`, `CreditMessage`, `LogMessage`, `StateMessage`).
2. **Worker ↔ Node Role Partitioning:**
   - `RemoteHostApis` acts as a zero-overhead proxy on the worker: authority operations (`kv`, `bus`, `queue`, `files`, `fs`, `secrets`, `jobs`, `dns`, `serve`) emit correlated `CALL` frames awaiting `RESULT`/`ERROR` from the node.
   - Pure computation (`crypto`, `zlib`) runs locally in the worker isolate with 0 network or IPC round trips.
   - `NodeWorkerChannel` and `NodeRpcDispatcher` validate methods against an allowlist, enforce quotas (1,024 concurrent in-flight RPCs), and automatically stamp tenant identity without trusting worker self-reported tenants.
3. **Subprocess Execution Abstraction:**
   - `StreamBridge` transparently wraps standard Java `InputStream` and `OutputStream` into non-blocking Vert.x `ReadStream<Buffer>` and `WriteStream<Buffer>` via virtual threads.
   - `WorkerProcessRuntime` allows node schedulers to execute jobs in subprocess workers (GraalVM native images or Java child processes).
   - `WorkerMain ipc` adds the daemon runner mode to `cluster93-worker`.
4. **Codebase Architecture Navigation & Javadoc Integration:**
   - Added [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) providing a high-level subsystem and file reference map.
   - Configured `maven-javadoc-plugin` aggregate documentation (`./mvnw javadoc:aggregate`) with clean doclint rules, resolving historical broken tags in `StateSink`, `IgniteFileStore`, and `Builtins`.

**Evidence:**
- `cluster93-runner-protocol` passes all 13 unit and integration tests covering frame chunking, boundary fragmentation, 1 MiB max frame enforcement, credit depletion backpressure, and bidirectional `RemoteHostApis` ↔ `NodeHostApis` RPC execution.
- Multi-module pre-commit verification (`./mvnw verify -pl runner-protocol,worker -am -DskipITs`) builds and tests cleanly in 55 s with 0 JVM crashes.

### D-129 — Stream Multiplexing, Warm Tenant Worker Pool Supervisor, and Unix Domain Socket (UDS) Transport
**Date:** 2026-10-10 · **Status:** Accepted. Implements Step 5 in `cluster93-runner-protocol`. Specification: [worker-node-protocol.md](design/worker-node-protocol.md).

**Decision:**
1. **Stream Multiplexing (`StreamMultiplexer`):**
   - End-to-end bidirectional streaming channel multiplexer (`0x30 OPEN`, `0x31 DATA`, `0x32 END`, `0x33 RESET`, `0x34 CREDIT`).
   - Integrated into both `NodeWorkerChannel` and `WorkerClientConnection`.
   - Adopts HTTP/2-style flow control: outgoing writes chunk into 64 KiB slices, pause if credit window is depleted, and resume upon receiving `CREDIT` frames.
   - Incoming streams automatically replenish credits back to the sender once 16 KiB or more of data has been consumed.
2. **Warm Tenant Worker Pool Supervisor (`TenantWorkerPool`):**
   - Maintains a warm pool of pre-spawned worker processes in `UNBOUND` state, eliminating the 40–800 ms cold start overhead for tenant job submissions.
   - Binds workers to tenants on demand via `BIND`, transition states (`UNBOUND` -> `BOUND` -> `BUSY` -> `BOUND` -> `TERMINATED`), and supports `FRESH_PER_JOB` (100% memory isolation) and `REUSE_SAME_TENANT` policies.
   - Integrates with Linux cgroups v2 (`CgroupMemoryLimiter`): automatically writes `memory.max` and `memory.high` to `/sys/fs/cgroup/cluster93/worker-<pid>` and verifies OOM kills via `memory.events`. Gracefully falls back to heap options in unprivileged/non-Linux environments.
3. **Unix Domain Socket (UDS) Transport (`UdsServerTransport`, `UdsClientTransport`):**
   - Provides native non-blocking Netty domain socket transport over filesystem `.sock` nodes.
   - Unlike stdio pipes which require virtual-thread stream pumping, Vert.x `NetSocket` directly implements `ReadStream<Buffer>` and `WriteStream<Buffer>`, allowing zero-copy, event-loop native framed IPC.
   - Added `--socket <path>` CLI option to `WorkerMain ipc` mode.

### D-130 — Per-Tenant Multi-Mode Worker Pools, Fine-Grained RPC Authorization, and Server Bootstrap Wiring
**Date:** 2026-10-10 · **Status:** Accepted. Extends D-127 and D-129.

**Decision:**
1. **Per-Tenant Multi-Mode Worker Pools (`TenantWorkerPoolManager`):**
   - Workers are partitioned strictly by `(tenantId, mode)`. Unbound warm workers are spawned per tenant profile, guaranteeing memory isolation and zero cross-tenant contamination.
   - Operators can define custom worker binary paths per mode via `C93_WORKER_BINARY_<MODE>` (e.g. `C93_WORKER_BINARY_DEFAULT`, `C93_WORKER_BINARY_HIGHMEM`), configured through `ServerConfig`.
2. **Zero-Trust Host RPC Authorization (`NodeRpcAuthorizer`):**
   - Introduces `NodeRpcAuthorizer` interface guarding every incoming `CALL` frame on `NodeWorkerChannel`.
   - Before executing any host API RPC method, the channel verifies authorization against tenant and job context, returning `FORBIDDEN` if unauthorized.
3. **Server Bootstrap Integration:**
   - Updated `ServerConfig` with `workerMode` (`embedded` or `process`), `workerDefaultMode`, and `workerBinaries`.
   - Wired `WorkerProcessRuntime` backed by `TenantWorkerPoolManager` into `Cluster93` server bootstrap when `workerMode == process`.
4. **End-to-End Capability Verification:**
   - Verified via `WorkerEngineIntegrationTest#testFullCapabilitiesOverWorkerProtocol`: guest JavaScript executes streaming file uploads (`files.write`), KV operations (`kv.set`/`kv.get`), and unauthorized call interception (`jobs.get` -> `FORBIDDEN`).

**Evidence:**
- All 24 unit and integration tests pass in `cluster93-runner-protocol`.
- All 148 server tests pass cleanly with 0 regressions.

### D-131 — Defaulting C93_WORKER_MODE to Process Isolation for Zero-Trust Security
**Date:** 2026-10-10 · **Status:** Accepted. Extends D-126 and D-130.

**Context:**
Prior to this decision, `ServerConfig` defaulted `C93_WORKER_MODE` to `embedded`. While in-process isolates (via Javet) are lightweight and suitable for local development, they execute inside the host JVM process. A malicious native exploit or uncontrolled off-heap allocation (e.g. unconstrained ArrayBuffers or ICU allocations) could crash the server process or exhaust system memory.

**Decision:**
1. **Default to Out-of-Process Worker Pools (`C93_WORKER_MODE=process`):**
   - All tenant guest JavaScript executes inside dedicated worker child processes supervised by `TenantWorkerPoolManager`.
   - Each tenant isolate runs in a sandboxed process boundary enforced with cgroups v2 memory limits (`memory.max`, `memory.high`) and zero-trust protocol RPC authorization (`NodeRpcAuthorizer`).
   - Any worker crash, segfault, or OOM kill is fully contained within the worker process; the Cluster93 cluster node, HTTP ingress, and database remain resilient and unaffected.
2. **Embedded Mode Retained as Opt-In:**
   - Operators can explicitly opt into `C93_WORKER_MODE=embedded` for lightweight, low-overhead development environments where process spawning is unneeded or restricted.

**Evidence:**
- Verified with targeted server tests and `cluster93-runner-protocol` test suites.

### D-132 — Full Process-Mode Parity: streaming host APIs, context restoration, and lifecycle hardening
**Date:** 2026-10-11 · **Status:** Accepted. Extends D-126, D-128, D-129 and D-131.

**Context:**
D-131 made `C93_WORKER_MODE=process` the default, but out-of-process execution was only partially implemented: `RemoteHostApis` threw `UnsupportedOperationException` for `ctx.serve`, `fetch`, `net`/`tls` and `files.open`; bus/queue/job listeners had no delivery path; the worker rebuilt a stripped `ctx` (no packages, env, carried steps or service flag); and several failure modes hung instead of failing — a stream could exhaust the 4 MiB connection window (only stream credit was replenished), a worker EOF left the run promise unresolved, blocking state writes ran on the event loop, a full 64 KiB stderr pipe deadlocked workers, and a stopped node left worker processes running. With the default flipped, the whole host-API surface had to behave as it does embedded.

**Decision:**
1. **Bridge the streaming host APIs over the multiplexer.** The node side (`NodeWorkerChannel`) owns the request/response stream pairs: `http.request` → `http.response` (with `upgrade` for WebSockets), `fetch.request` → `fetch.response`, `socket.connect` → `socket.data`, and `files.open` → `files.data`; `files.create` and `files.respond` complete the file API. Response streams are opened before the peer can answer, and a fetch whose body already ended signals failure on the response stream rather than a reset that no longer has a stream to reach.
2. **Deliver listeners as events.** `serve.listen/unlisten`, `bus.subscribe/handle/unsubscribe/unhandle`, `queue.consume/unconsume` and `jobs.events/unevents` register the run's real node-side listener; each delivery travels as an `EVENT` frame carrying an `eventId` and is answered with a `REPLY` for listeners that expect one (bus requests, queue acks). This is the async half of the protocol that D-128 framed but did not implement.
3. **Restore the whole execution context.** `Packages.toJson`/`fromJson` and `JobContext.toJson`/`fromJson` serialize the lock, the previous attempt, carried steps and the service descriptor; `RunMessage` carries packages, `process.env`, `longRunning` and the serialized `ctx`. The worker rebuilds the same `Execution` an embedded run would see.
4. **Fail fast and stay off the event loop.** `NodeWorkerChannel` fails every active run with `ExecutionResult.failed("Worker process died: …")` on EOF or transport error; connection-level credit is replenished with stream credit; state commits run on a worker thread; the worker exits explicitly on EOF; the pool drains stderr and records exit codes 137/139; `RunningNode.stop()` closes the pool; and the worker's logging is pinned to stderr so it cannot corrupt the stdout frame channel (the child-JVM classpath puts the worker's own resources first).

**Alternatives considered:**
- **Keep long-polling/one-shot RPC for streaming APIs.** Rejected: `fetch` bodies, TCP sockets and HTTP request/response bodies are streaming and full-duplex; modelling them as RPC loses backpressure and can't interleave.
- **Have the node call the worker's APIs directly (node as client).** Rejected: it inverts the zero-trust direction; the worker must remain the party that requests authority, with the node injecting tenant identity and checking the allowlist.
- **Reset the request stream to signal a late fetch failure.** Rejected: once the request body has ended the stream is cleaned up on both sides, so the failure needs its own response stream.

**Consequences:**
- Process mode is now a first-class execution mode: packages, services, ingress, egress, bus/queue/job events and resumed jobs behave as they do embedded.
- More protocol surface: new `EVENT`/`REPLY` frames and stream types, and the `RUN` frame is larger (it carries the lock). CREDIT frames are doubled (stream and connection).
- The child-JVM fallback (used when no native image is present) now logs to stderr and resolves worker resources first; a stale native image must be rebuilt to match the protocol.

**Evidence:**
- `ProcessModeParityTest` (two-node cluster, real child-JVM workers) exercises package imports, `ctx.serve` through the ingress, `fetch` and `net.connect` egress, bus request/reply, worker kill detection and resumed step memoization under `C93_WORKER_MODE=process`.
- `StreamMultiplexerCreditTest` (10 MiB over the 4 MiB connection window), `NodeWorkerChannelEofCrashTest` (EOF fails the run), `NodeWorkerChannelStateNonBlockingTest` (commits off the event loop).






### D-134 — `c93 node` as the node control plane: install from a release, and one lifecycle across systemd, launchd and a bare process
**Date:** 2026-10-13 · **Status:** Accepted. Folds `c93 local` into `c93 node`; builds on D-049, D-069.

**Context:**
A node could only be built from source (`scripts/local.sh`) or run under Docker. The release already ships the whole platform (`c93.jar`, `worker-*.gz`, `cluster93-cli.jar`, `cluster93-linux-x64`), but nothing installed a node from it: the shell installer only placed the CLI. Separately, the `c93 local …` verbs and the operator's `c93 node invite`/`revoke` were two unrelated command groups for the same machine.

**Decision:**
1. **One group, `c93 node`.** The `c93 local …` verbs move into `NodeCommand` beside `invite`/`revoke`; `c93 local` stays a hidden, deprecated alias for one release. No behaviour is lost: the verbs still understand both an *installed* node (`node.env` + a service unit) and the *development master* a checkout owns (`$C93_HOME/local.env` + `scripts/local.sh`).
2. **`c93 node install` is the one install implementation.** It fetches the release assets, verifies `SHA256SUMS` against its signature with the key built into the CLI (D-069) and each file against `SHA256SUMS`, gunzips the native workers into `bin/`, writes `node.env` and a service unit, and starts the node (unless `--no-start`). The shell installer's `--node` only bootstraps the CLI and delegates, so the node logic lives in exactly one place. `--system` (Linux, root) is a distinct layout: `/opt/cluster93`, `/etc/cluster93`, `/var/lib/cluster93`, and a system unit under a dedicated `cluster93` user.
3. **One lifecycle, detected backend.** `start`/`stop`/`restart`/`status`/`logs` drive the node's systemd unit (`--user` or system), a macOS LaunchAgent, or the process directly (`java -jar c93.jar`, pid file under the data dir) when no service manager is present — a container, WSL, or a plain shell. `status` reports the backend it found.
4. **`node.env` is `C93_*`.** Settings are `C93_`-prefixed environment variables, so a systemd `EnvironmentFile=` feeds the server directly; `c93 node env` shows and changes them and accepts the short names (`HTTP_PORT` ≡ `C93_HTTP_PORT`).
5. **No Windows node.** The CLI installs on Windows as the portable jar plus a `c93.cmd` shim (Java 21 detected); a node there is Docker or WSL2. macOS gets a per-user LaunchAgent only, and non-Linux nodes run `C93_WORKER_MODE=embedded` (native workers can't cross-compile).

**Alternatives considered:**
- **Put the node logic in the shell installer.** Rejected: it can't run as a native binary, and it duplicates signing, checksums and layout next to the CLI's own `upgrade`.
- **Keep `c93 local` and add `c93 node install` separately.** Rejected: two names for one machine's node; a single group is what operators type.
- **Always run under a service unit.** Rejected: containers and WSL have none, so the direct-process path must exist and be indistinguishable in behaviour.
- **Reuse the non-prefixed `node.env` names.** Rejected: the server reads `C93_*`, and a systemd `EnvironmentFile` exports names verbatim; the short names are accepted as CLI sugar instead.

**Consequences:**
- A node is installable from a signed release with one command, on Linux and macOS, user- or system-wide, with the same verbs driving it.
- `c93 local …` is deprecated and will be removed after this release.
- `install.sh` becomes a bootstrap for the CLI; the installer tests keep exercising its CLI path, and `NodeCommandTest` covers install, verification failures, backend dispatch (a stub `systemctl`), and uninstall against a local release host.

**Evidence:**
- `NodeCommandTest` (28 tests): layout, `node.env`, rendered unit, `--no-start`, checksum and signature refusal, unsigned-release handling, a stub `systemctl` driving `start`/`stop`, the direct-process fallback, uninstall with and without `--purge`, and the deprecated `c93 local` alias.
- `InstallScriptTest`, `CliTest`, `MainHelpTest`, `VersionCommandTest` pass with the moved verbs.

### D-135 — A worker that fails before its hello fails fast, and the child-JVM fallback carries both engines
**Date:** 2026-10-13 · **Status:** Accepted. Hardens D-128/D-132 (worker lifecycle and EOF handling).

**Context:**
`ProcessModeParityTest` hung for its full 30 s poll window on every job — the worker never ran, and nothing was logged. The child-JVM worker died during startup with `NoClassDefFoundError: cluster93/runtime/graaljs/GraalJsScriptRuntime`, because the worker build registered `GraalJsWorkerEngine` while the classpath it inherits (the server's, via `TenantWorkerPool.resolveWorkerClasspath()`) carried only `cluster93-runtime-javet`. Three things made this un-diagnosable rather than a plain error:

1. `NodeWorkerChannel.onError` failed only *active runs*; when a worker died during startup (`activeRuns` empty) it merely logged at debug and never failed `helloPromise`. A pool `acquire()` then waited on a promise that could never complete.
2. `TenantWorkerPool.spawnWorker` had **no timeout** on `awaitHello()`, and `drainStderr` logged worker stderr at **debug** — so a worker that died before hello produced no visible output and an unbounded wait.
3. The child-JVM fallback's classpath only ever had the Javet engine, while `worker`'s engine is chosen by a profile (`-Pjavet`/`-Pgraaljs`); `ProcessModeParityTest` silently depended on whichever one was last built, and `-pl server -am` never rebuilds the worker (it is not a server dependency).

**Decision:**
1. **Fail the handshake on pre-hello death.** `NodeWorkerChannel.onError` fails `helloPromise` (with "Worker failed before handshake: …") when the channel breaks before hello, and logs at WARN; once hello has arrived it is a no-op, and a post-handshake connection close stays debug (or error with active runs).
2. **Bound the hello wait and surface the reason.** `TenantWorkerPool.spawnWorker` waits at most `cluster93.worker.helloTimeoutMs` (default 30 s) for hello; on failure or timeout it logs at WARN including the worker's last stderr lines (a small bounded tail is captured by the stderr drain) and fails the `acquire()`. `WorkerProcessRuntime`'s inline spawn gets the same timeout.
3. **Count every worker exactly once.** `spawnWorker` takes a `totalWorkers` slot when the spawn starts and `recycleWorker` releases it (idempotently, via the handle's `TERMINATED` state); a failed bind releases too. Previously only warm spawns were counted while every recycle decremented, so the count drifted negative and `maxTotal` stopped limiting the pool.
4. **Carry both engines in the fallback.** `cluster93-server` depends on `cluster93-runtime-graaljs` in **test scope** (production `c93.jar` still ships Javet only; real nodes run the native workers from `bin/`, where the engine is baked in). The child-JVM fallback now runs whichever engine the worker build registered. `ProcessModeParityTest` reads the worker build's `META-INF/services/cluster93.worker.WorkerEngine` and aborts (a visible skip, with the rebuild command printed first) only when it registers no engine at all.

**Alternatives considered:**
- **Add `cluster93-runtime-graaljs` to `cluster93-server` in compile scope.** Rejected: it would bloat the production shaded jar and change what the node ships, for a path (the child-JVM fallback) that production does not use — native workers are the production route. Test scope gives the fallback both engines without shipping them.
- **Fail the run when the engine doesn't match.** Rejected: `scripts/build-workers.sh all` compiles GraalJS last, so a routine `verify` would go red for a build-ordering condition; an abort with the fix is visible without breaking routine runs.
- **Have `resolveWorkerClasspath` add the sibling engine module's classes.** Rejected: for GraalJS the engine's polyglot jars would still be absent, so it would half-fix and still fail.

**Consequences:**
- A worker that crashes at startup now fails its job (and its `acquire`) immediately, with the worker's stderr attached, instead of hanging silently.
- `ProcessModeParityTest` runs whether `cluster93-worker` was compiled `-Pjavet` or `-Pgraaljs`, and skips (with instructions) only when it was compiled for neither.
- The pool's `maxTotal` (= `C93_CAPACITY`) now actually bounds warm + active + in-flight workers.
- `cluster93.worker.helloTimeoutMs` overrides the 30 s wait, for tests.

**Evidence:**
- `NodeWorkerChannelEofCrashTest` (EOF before hello fails `awaitHello`), `TenantWorkerPoolTest` (a worker that dies before hello, and one that never says hello, both fail `acquire` within the timeout, leaving `totalWorkers() == 0`).
- `ProcessModeParityTest` 7/7 with the worker built `-Pjavet` **and** 7/7 with it built `-Pgraaljs`; with the engine registration removed it aborts in ~6 s with the rebuild instruction instead of hanging.

### D-136 — A `--system` install provisions its own `cluster93` service account
**Date:** 2026-10-13 · **Status:** Accepted. Completes D-134's system layout (the `Owner` row: "a dedicated `cluster93` system user").

**Context:**
`c93 node install --system` renders `/etc/systemd/system/cluster93.service` with `User=cluster93` and `ReadWritePaths=/var/lib/cluster93`, but nothing created that account or gave it the data directory, so the rendered unit could not start. The work order called for "a dedicated `cluster93` system user", so the installer must make the system consistent rather than leaving a unit that fails.

**Decision:**
1. **The installer creates the account, idempotently.** On a `--system` install (which already requires Linux + root), `c93 node install` checks `/etc/passwd` for the exact name and, if absent, runs `useradd --system --user-group --home-dir /var/lib/cluster93 --no-create-home --shell <nologin> cluster93` (worst case: fail with that exact command if `useradd` isn't available). `--user-group` guarantees a `cluster93` group for the data directory.
2. **Ownership follows the account.** The data directory (and its `logs/`) is chowned to `cluster93:cluster93`; the program directory stays root-owned (read-only to the service, which is what `ProtectSystem=strict` wants). `node.env` and `node.meta` become `0640 root:cluster93` so the service can read its own config, while never being world-readable (they hold the admin token).
3. **Uninstall leaves the account.** Removing a system user is invasive (its UID may own other files) and not what "remove the program and unit" means; `--purge` deletes the data, not the user (D-134).

**Alternatives considered:**
- **`DynamicUser=yes` + `StateDirectory=cluster93`.** Smaller and needs no privileged account, but it *changes* the locked "dedicated `cluster93` system user" decision and the unit's `User=`, so it was not taken without that change being asked for.
- **Leave it to the admin (fail fast if the user is missing).** Rejected: it turns `--system` into a two-step manual install for something the installer can do safely as root.
- **Remove the user on uninstall/`--purge`.** Rejected: destroying an account is not symmetric with creating it, and is easy to regret.

**Consequences:**
- A real `sudo c93 node install --system` yields a startable unit: account, data ownership and unit all agree.
- The account is never removed automatically; `userdel -r cluster93` is the admin's call.
- Verified by unit tests for the parts reachable without root (the `useradd` invocation, `/etc/passwd` matching, the nologin shell, and the non-root refusal); the ownership change itself is root-only and was not run in this environment (no `sudo`).

### D-137 — Default ports move to the `9393`–`9396` Cluster93 block
**Date:** 2026-10-13 · **Status:** Accepted. Supersedes the default *values* of D-029 (ingress), the internal-endpoint decision, and the metrics-port note; those records keep their text.

**Context:**
The defaults were `C93_HTTP_PORT=8080`, `C93_INGRESS_PORT=8000`, `C93_METRICS_PORT=9090` and `C93_INTERNAL_PORT=8090`. 8080 and 8000 are the two most contended ports on a developer machine (proxies, dev servers), and 9090 is Prometheus's own listening port, so running a node next to the tools it is meant to be scraped by invited collisions.

**Decision:**
1. **One memorable block:** API **9393**, ingress **9394**, metrics **9395**, internal **9396** (the cluster/event-bus ports stay 5701/5801). Applied at the single source of truth (`ServerConfig.fromEnv`) and everywhere that mirrors it: `NodeCommand`'s development-master defaults, `NodeInstall.defaultConfig` (what `node.env` is written with), `NodeService.httpBase`, `scripts/local.sh`, `Dockerfile`/`docker-compose.yml`, `openapi.yaml`'s `servers` URL, and the Prometheus/Grafana stack.
2. **Docker host ports follow the block:** the three-node demo maps `9401-9403 → 9393` (API) and `9411-9413 → 9394` (ingress), replacing `8081-8083`/`8001-8003`. Prometheus's own `9090` and Grafana's `3000` are untouched.
3. **Existing installs do not move.** A written `node.env`/`local.env` names its ports explicitly, so only fresh installs and unset environments take the new defaults. The change is still breaking for anyone relying on the old defaults, hence the changelog note.

**Alternatives considered:**
- **Keep 8080/8000 and only move metrics.** Rejected: 8080/8000 were the actual collisions the user hit.
- **Move only the two public ports, leaving metrics/internal.** Rejected: 9090 is Prometheus's port, so the metrics default is the *more* dangerous one; a single block is also easier to remember and document.
- **A non-themed pair (e.g. 8787/8788).** Rejected: the `939x` block ties to the project name and leaves room for metrics/internal.

**Consequences:**
- A fresh node is unlikely to collide with local tooling; the whole set is one block to remember.
- Historical design docs and decision records that state the old numbers are left as written (D-029 and friends); the living docs — README, `docs/guides/*`, `docs/api`, `docs/scripting`, SECURITY, and the normative `docs/design/*` specs — were updated.
- The Docker host mappings changed, so an existing `docker compose` setup needs the new ports in its client URLs.

**Evidence:**
- `ServerConfigTest` and the full `verify -pl cli,server -am -DskipITs` suite pass with the new defaults; `NodeCommandTest` asserts `node.env` is written with `C93_HTTP_PORT=9393`; the native `c93 node install` smoke test writes and reports the new ports.

### D-138 — One update surface: `c93 update [--cli|--node]`
**Date:** 2026-10-14 · **Status:** Accepted. Completes D-049/D-069 (release fetch and signing) and D-134 (node install).

**Context:**
Installing had one path (`c93 node install`), but updating had three names and two behaviours: `c93 upgrade` (the CLI, and only from a native binary or with `--to`), `c93 node update` (release or checkout, detected), and `scripts/local.sh update` (the checkout). A portable-jar install — the Windows layout, and any JVM run — had **no** way to update itself, because `upgrade` refused to guess what to replace.

**Decision:**
1. **`c93 update` is the front door**, with `--cli` and `--node`; **neither flag does both**. `--version TAG` pins a release.
   - `--cli` replaces the CLI asset: the running **native binary** on a native image, or the **jar it was launched from** on the JVM (the portable layout). `--to FILE` names a target explicitly (needed when running from classes).
   - `--node` updates the node: an installed node is re-fetched from the signed release and restarted if it was running; a checkout rebuilds from source via `scripts/local.sh update`.
2. **`c93 upgrade` and `c93 node update` stay as hidden aliases** of the two halves, so existing scripts, docs and habits keep working. The CLI's own update hints now say `c93 update --node`.
3. **One bundle feeds both halves.** The release ships the CLI (`cluster93-linux-x64`, `cluster93-cli.jar`) beside the server (`c93.jar`) and the Linux workers, so a single signed release refreshes an install; `docs/guides/releasing.md` carries the platform matrix (native CLI per platform; workers Linux-only).

**Alternatives considered:**
- **Keep the three commands.** Rejected: one job with three names and inconsistent behaviour (a checkout vs a release) is exactly the fragmentation this phase is removing.
- **Update the CLI only from a native binary** (the status quo). Rejected: portable-jar installs could never update.
- **Make `--cli` infer a target on the JVM without `--to`.** Kept, but only when the code source is a jar; from classes it still asks for `--to`, so a development run can't clobber a directory.

**Consequences:**
- Updating an install is one command with explicit halves; `--json` reports it, and neither half touches the other's files unless asked.
- `upgrade`/`node update` disappear from `--help` (still callable), and `c93 update` takes the Cluster & Node group.
- A native install replaces its binary in place; a jar install replaces the jar file.

**Evidence:**
- `UpdateCommandTest`: `--cli` replaces a jar after the signature/checksum check; neither flag updates both; `--node` leaves the CLI alone; the `c93 node update` alias still works; a tampered jar is refused; `--help` lists `update` but not the hidden `upgrade`.
- `UpgradeTest` and the rest of the CLI suite unchanged and green; `scripts/release-local.sh --build-only` assembles a bundle with the CLI, the server, the workers and both installers.

### D-139 — A guest's invalid HTTP status answers 500 instead of hanging the request
**Date:** 2026-10-14 · **Status:** Accepted. Hardens the `ctx.serve` response contract.

**Context:**
`ctx.serve`'s handler returns a string, or `{status, headers, body}`. `serve.js` mapped that with `Number(value.status ?? 200)`, so a guest writing `{ status: 'ok', … }` — treating `status` as a payload field, as `examples/js/07-microservice.js` did — produced `NaN`. The exchange then never completed: the client waited until it timed out. Nothing failed, nothing was logged; the request simply hung.

**Decision:**
A status that is not an integer in `100..599` answers **500**, with the handler's body and headers still sent. Only an absent status defaults to 200. A guest's mistake is a server error, and the client gets an answer it can act on.

**Alternatives considered:**
- **Coerce with `Number(...)` and pass it on.** Rejected: that is the bug — `NaN` reached the host and left the exchange unanswered.
- **Throw in the isolate.** Rejected: the handler's other work (and its body) would be lost, and the failure would surface as a job error rather than a response the caller can see.
- **Clamp to the nearest valid status.** Rejected: guessing hides the bug; 500 is the honest answer for a server-side mistake.

**Consequences:**
- A bad status is a visible 500 with the body, not a hang.
- The conformance test `HostBridgeTest.servedResponsesAreNormalised` covers it (a `/bad-status` handler asserting 500 and the body).

**Evidence:**
- `HostBridgeTest.servedResponsesAreNormalised` (runtime-js conformance suite) passes on the V8 engine with the guard, and asserted 500 rather than hanging.

### D-140 — A second install of the other kind (user vs system) is refused
**Date:** 2026-10-14 · **Status:** Accepted. Extends D-134 (the node layouts) and D-136 (the system account).

**Context:**
A user node and a system node are separate installs with separate configs, but both default to the same ports (`C93_HTTP_PORT=9393`, `C93_INGRESS_PORT=9394`, …). Installing both would produce two nodes that cannot both start, and `c93 node` would silently drive whichever `installedLayout` prefers.

**Decision:**
`c93 node install` refuses when the *other* kind is already installed — a user install when `/etc/cluster93/node.env` exists, a system install when `~/.config/cluster93/node.env` does — naming the file and how to remove it. `--force` installs both deliberately; they then need different ports in `node.env`.

**Alternatives considered:**
- **Let both install and fail at start.** Rejected: the clash surfaces much later, as a bind error in a log.
- **Auto-assign different ports.** Rejected: it would move a node's ports silently, and the ports are the operator's choice.
- **Make it a warning.** Rejected: "we don't install both" needs a refusal; `--force` is the deliberate escape.

**Consequences:**
- One node per machine by default; a second kind needs `--force` and different ports.
- With both installed, `c93 node <verb>` addresses the system one (`installedLayout` prefers it); addressing the user one needs an explicit override (open — a `--user`/`--system` flag on the lifecycle verbs).

**Evidence:**
- `NodeCommandTest.aSecondInstallOfTheOtherKindIsRefused` (both directions, the message, `--force`, and the no-op cases).

### D-141 — A Docker node is a third install kind
**Date:** 2026-10-14 · **Status:** Accepted. Extends D-134 (node layouts) and D-140 (cross-install refusal).

**Context:**
Docker was only the demo (`docker-compose.yml`), never something `c93 node` installed or drove. A node could be a user process, a system process, or a container — and the third had no layout, no lifecycle and no clash detection.

**Decision:**
`c93 node install --docker` lays out `node.env`, a generated `compose.yaml`, certificates and `node.meta`, and the existing lifecycle verbs drive the container. `Layout.Kind.CONTAINER` plus `NodeService.Backend.CONTAINER`; the engine (`docker`/`podman`) and the image are recorded in `node.meta`. There is no systemd unit: Docker's `restart: unless-stopped` supervises.

**Alternatives considered:**
- **A unit that runs `docker compose up`.** Rejected: two supervisors for one node, and the unit's lifecycle then races Docker's.
- **Treat a container as a process install with a wrapper.** Rejected: the layout differs (the program is in the image, not on the host), and `status` has no pid to report.

**Consequences:**
- `programDir` is the configuration root for this kind, since the program lives in the image.
- `installedLayout` must round-trip the kind from `node.meta`, or the lifecycle verbs look for a service unit and find nothing.

**Evidence:**
- `NodeCommandTest.aContainerInstallLaysOutItsEnvComposeAndTlsBesideItsConfig`, `anInstalledContainerNodeIsFoundAgainWithItsComposeFile`.

### D-142 — A container node is clustered size 1, with its data bind-mounted
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
`C93_CLUSTERED` defaults to false, and a non-clustered node keeps its stores in memory. A container node that lost its data on recreation would not be a node.

**Decision:**
A container install is clustered size 1 from the first start (`C93_CLUSTERED=true`, `C93_CLUSTER_SIZE=1`), so durable Ignite is on immediately; it generates its own certificates (or mounts `--tls-dir`); and the host data dir is bind-mounted to `/var/lib/cluster93`. The bind mount **is** the persistence.

**Alternatives considered:**
- **Standalone (in-memory) by default.** Rejected: a node whose data vanishes on `docker compose down` is a demo, not an install.
- **A named Docker volume.** Rejected: `--purge`, backups and paths would then not match a process install, and the operator could not see the data.

**Consequences:**
- The host data dir and the in-container data dir differ, so `node.meta` records **both**: `--purge` and backups depend on the distinction.
- Clustered requires an explicit data dir, which this always sets.

**Evidence:**
- `NodeCommandTest.aContainerNodeIsClusteredSizeOneWithDurableDataAndTls`, `aContainerInstallRecordsBothDataDirsAndTheEngineInItsMeta`.

### D-143 — The node image comes from the checkout, the release, or `--image`
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
No image stage shipped `bin/worker-*`, so no node image could run `C93_WORKER_MODE=process` — which is why the demo ran `embedded`.

**Decision:**
`--image` always wins; `--version TAG` assembles an image from that release (signature and checksums verified first) using a mirrored `install/docker/Dockerfile.node`; otherwise the checkout is built on the repo's new `node` stage, tagged `cluster93-node:local` (or `:<version>-local`).

**Alternatives considered:**
- **Build from the repo root as the context.** Rejected: `.dockerignore` excludes `server/target/`, so the stage's own `COPY` cannot see the jar. The build runs in a temp context, as `scripts/demo.sh` does.
- **A published image only.** Rejected: there is no real release yet, and `--image` covers the operator who has one.

**Consequences:**
- A node image is ~500 MB larger than the demo's (the native workers), which is the point.
- `--image` skips the release checks and is operator-trusted by definition.

**Evidence:**
- `NodeCommandTest.buildingFromTheCheckoutRefusesClearlyWhenThereIsNothingToBuildFrom`, `theReleaseImageDockerfileShipsTheWorkersAndNeedsNoBuildStage`; a real build verified the workers run inside the image.

### D-144 — Engine workers can run as sibling containers over the UDS transport
**Date:** 2026-10-14 · **Status:** Accepted. Uses D-127's transport; superseded in shape by D-160/D-166, and its node-spawns aspect by D-167.

**Context:**
The worker↔node protocol is transport-agnostic and the UDS transport already existed (D-127), with `worker ipc --socket` implemented and tested — but the server always spawned stdio pipes and never constructed a UDS listener.

**Decision:**
`C93_WORKER_TRANSPORT=uds` spawns engine workers as sibling containers over a mounted socket, with container handles (inspect/`rm -f`/`-m` in place of `Process`/cgroup), engine-labelled orphans, and a container memory budget counted separately from isolate slots.

**Consequences:**
- The node needs the engine socket, which is its largest privilege (D-145, D-152).
- The unit of a worker container changes from per-job to per-tenant (D-160).

**Evidence:**
- `UdsTransportTest`, `WorkerEngineIntegrationTest` (the transport); the spawn path is unbuilt.

### D-145 — `--worker-containers` requires a rootless engine
**Date:** 2026-10-14 · **Status:** Accepted. Refined by D-152. **Superseded by D-167** (the node never holds an engine socket).

**Context:**
To spawn worker containers the node must be given an engine socket. A rootful engine's socket is a root credential: anything holding it can `docker run -v /:/host`.

**Decision:**
The guard keys on whether the socket is **root-equivalent**, not on the engine's name. Rootful docker and rootful podman both require `--force`; rootless docker and rootless podman are allowed. Rootless-ness is asked of the daemon (`docker info` SecurityOptions, `podman info` Host.Security.Rootless), with socket ownership only as a fallback — a rootful daemon's socket can be chowned to a user and is still root-equivalent.

**Consequences:**
- The daemon probe is load-bearing: it is required for `--worker-containers`, not incidental.
- An absent daemon leaves rootless-ness **unknown** rather than failing the install, since `--no-start` needs no daemon.

**Evidence:**
- `NodeCommandTest.detectEngineAsksTheDaemonWhetherItIsRootless`, `detectEngineReportsUnknownRootlessWhenTheDaemonDoesNotAnswer`.

### D-146 — A Docker node refuses a process install that would share its ports
**Date:** 2026-10-14 · **Status:** Accepted. Extends D-140 to the third kind.

**Context:**
D-140 refused a second install of the *other* kind. A container node is a third kind, and all three default to the same host ports.

**Decision:**
A container install refuses when a process install occupies the same ports, unless `--force`. It reuses `refuseIfAlsoInstalled` rather than a second checker: that message already derives the other kind from the layout, and the user-process case is already covered by the "already installed" check, since a user container install shares the config dir. For a system container install there is nothing to look up, because it runs as root, whose config dir is not the invoking user's.

**Alternatives considered:**
- **A separate `refuseContainerIfAlsoInstalled` taking both paths.** Written, then deleted: its user-env argument could never match, and it duplicated the message logic.

**Evidence:**
- `NodeCommandTest.aContainerInstallRefusesASystemProcessInstallThatWouldShareItsPorts`.

### D-147 — The worker channel carries a per-spawn secret in `HELLO`
**Date:** 2026-10-14 · **Status:** Accepted. **Refined by D-168** for deployment-provisioned workers (the secret is pre-shared, not per-spawn).

**Context:**
stdio binds a channel to a specific child process for free — the pipe *is* the worker. A socket binds it to "whoever connected to slot N", so a naive swap makes the node↔worker binding weaker, not merely different.

**Decision:**
Each spawn generates 32 random bytes, passes them to the worker container, and the worker presents them in `HELLO`; the node validates against the pending spawn and rejects a mismatch. `HelloMessage` is `(engine, version, pid)`, so this is an added optional field.

**Consequences:**
- Restores the binding stdio gave for free, and is the per-attempt secret Phase 7 needs.
- Not implemented yet (Stage 2 is unbuilt).

### D-148 — Container hardening, and published ports that match a process install
**Date:** 2026-10-14 · **Status:** Accepted. See also D-151.

**Context:**
The server binds every interface (`.listen(port)` with no host), so every install today exposes its API. A docker-only loopback default would be *stricter than the platform*, and would not protect the process node beside it.

**Decision:**
Node and worker containers run with `cap_drop: [ALL]`, `no-new-privileges`, a read-only root plus a `/tmp` tmpfs, and a pids limit. Ports publish on all interfaces by default, matching a process install; `--publish-address` restricts per install. **9396 is only `expose`d** — it is the ingress-proxy endpoint, mTLS-protected (D-066), and publishing it adds no capability.

**Consequences:**
- Publishing everything means one node per host unless ports are offset.
- `ca.key` is never mounted into the node (see D-150's neighbours in `S5`): the node needs its own cert and the CA cert, nothing more.

**Evidence:**
- `NodeCommandTest.theComposePublishesTheControlPlaneAndExposesOnlyTheInternalEndpoint`, `theComposeBindAddressAndClusterPortsAreOptional`.

### D-149 — A worker container presents the node's own host identity
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
A worker must reach a `0700`/`0600` socket. A process inside a rootless container maps to a subuid, not the host uid, so the permissions that secure the socket are also what block it.

**Decision:**
Make the worker present the node's identity: `--userns=keep-id` for rootless podman (the image's non-root uid maps to the invoking user); container uid 0 for rootless docker (in the userns, uid 0 *is* the invoking user, so it is unprivileged on the host); `--user <uid>:<gid>` for a rootful engine. The socket stays `0700`/`0600`.

**Alternatives considered:**
- **A shared group at `0750`/`0660`.** Opt-in fallback only. **`0666` is never acceptable.**
- **A permissive socket.** Rejected: it would make the boundary meaningless to avoid a mapping problem.

**Consequences:**
- "Non-root" has to mean non-root **on the host**: under a rootless engine, container root is the safe configuration.
- All three cases must be verified on real hardware; they differ per engine.

### D-150 — The `NodeRpcAuthorizer` is implemented, not deferred
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
`NodeRpcAuthorizer` is a `@FunctionalInterface` whose only implementation in the repo is `ALLOW_ALL`, and `Cluster93` passes it. With stdio that is latent; with a socket that tenant code can reach, it stops being latent.

**Decision:**
Implement it where the pool is built, so it can consult the stores: the worker's `boundTenantId` must match the tenant of the run it claims; the run must be the current attempt (the container transport's share of D-009…D-012's fencing); and the method must be in the documented allow-list. Refusals are logged with worker, tenant, run and method, and answered as an error frame rather than a silent drop.

**Consequences:**
- This is what makes handing an untrusted container a socket safe, so it is a Phase 7 precondition — but it lands with the transport, not after it.
- The pool's existing tests keep `ALLOW_ALL`; the strict authorizer is what the server passes.

### D-151 — `C93_HTTP_BIND` lets a process node restrict its control plane
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
Every listener took only a port, so no install could restrict its control plane. A container can be restricted by its published address, but a process node could not.

**Decision:**
`C93_HTTP_BIND` (default `0.0.0.0`, so behaviour is unchanged) sets the bind host for the API, ingress and metrics endpoints. **The internal endpoint (9396) is deliberately excluded**: peers must reach it, it is mTLS-protected, and letting it follow a loopback bind would silently break clustering.

**Consequences:**
- Two layers, documented separately: `C93_HTTP_BIND` is what the *server* binds; `--publish-address` is where a *container's* port is published.

**Evidence:**
- `ServerConfigTest.httpBindDefaultsToAllInterfaces`, `httpBindIsParsedFromEnv`.

### D-152 — Engine privilege ranking, and what rootless does not protect
**Date:** 2026-10-14 · **Status:** Accepted. Refines D-145.

**Decision:**
Rootless is the default and strongest against the **host**; rootful with user-namespace remapping (`--userns-remap`) is the supported middle ground; plain rootful is last and requires `--force`. The axis is privilege, not engine: rootful *podman* is as dangerous as rootful docker, and rootless *docker* is as safe as rootless podman.

**Context and consequences:**
- **Rootless protects the host, not the cluster.** A rootless escape lands as the invoking user, who owns the data dir, the TLS dir and the socket dir. The crown jewels stay the crown jewels, which is why D-149 and the `ca.key` rule matter.
- User namespaces **widen** the kernel attack surface, so rootless is not a free lunch.

### D-153 — A freed worker is never handed straight back to the tenant just served
**Date:** 2026-10-14 · **Status:** Accepted, but **inert as installed** — superseded in placement by D-161.

**Context:**
Waiters were queued in a single global FIFO, so a tenant that queued ten requests was served all ten before a tenant that queued one: starvation.

**Decision:**
Dispatch is a round-robin over the tenants waiting, and the rule that matters is skipping the tenant just served. With *n* tenants competing each gets roughly `capacity/n`; a tenant that is the only one waiting still gets everything, so a quiet cluster is not throttled.

**Alternatives considered:**
- **A hard per-tenant cap alone.** Rejected as the *ordering* mechanism: a cap reserves, it does not order.
- **"Fewest active workers wins".** Written first, then replaced: when a tenant releases the only worker its count drops to zero too, so it ties and wins again. Fairness needs to know who was **just served**.

**Consequences:**
- The rule is a small class (`TenantRotation`), testable without spawning a worker.
- **Installed in `TenantWorkerPool`, where it could never fire** — every tenant has its own pool. D-161 moves it.

**Evidence:**
- `TenantWorkerPoolTest.aTenantWithABacklogDoesNotKeepThePool`, `aLoneTenantIsNotThrottled`, `theRotationCountsTenantsNotRequests`.

### D-154 — `worker` means process, `job` means isolate
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
`worker` meant "process", and one worker ran one job, so "worker count" and "job count" happened to coincide. Once a worker runs several jobs they do not, and every config key would be ambiguous.

**Decision:**
`C93_CAPACITY` keeps its meaning — **concurrent jobs node-wide**. The per-tenant knob is `C93_MAX_JOBS_PER_TENANT`. A worker is a process; a job is one isolate/context.

**Consequences:**
- Keeping `C93_CAPACITY`'s meaning stable matters: operators have it set.

### D-155 — One worker per tenant, several jobs each
**Date:** 2026-10-14 · **Status:** Accepted. Shapes D-160. **Refined by D-172**: the binding is one tenant *at a time*, not for the worker's life — an idle worker can be handed to another tenant.

**Context:**
`ScriptRunner` already runs `isolateThreads` contexts at once and gives every job a fresh `JsContext` closed afterwards — so several jobs per process is not a new execution model. The pool never passed `--threads`, so every worker fell back to `WorkerMain`'s default of **2**.

**Decision:**
A worker is spawned per tenant and kept while it has work, running up to `C93_MAX_JOBS_PER_TENANT` jobs at once, each in a fresh context. A worker is **never shared between tenants**: the tenant binding is what the node authorises every host-API call against, and it is the crash and memory boundary.

**Consequences:**
- Nothing leaks between a tenant's jobs (fresh context); only compiled prelude/module caches persist, which is the win.
- Per-*job* memory isolation inside a tenant is lost — see D-157.

### D-156 — Two caps: one for the node, one for the tenant
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
Every tenant already has its own pool, and each pool's `maxTotal` was the node's capacity. So *n* active tenants could spawn `n × capacity` workers: five tenants at capacity 8 is up to 40 processes and ~5 GB of worker heaps on a node advertising 8. There was **no node-wide limit at all** — the opposite of the problem first assumed.

**Decision:**
A `WorkerBudget` is one counter every tenant's pool draws from, reserved exactly where `totalWorkers` is incremented and released exactly where it is decremented, so the two cannot drift. `C93_MAX_JOBS_PER_TENANT` (default `C93_CAPACITY`, never 0) re-points each tenant's own cap off the whole node; an operator lowers it for multi-tenancy, because the caps are independent rather than fractions of each other.

**Alternatives considered:**
- **Dividing capacity by the number of tenants.** Rejected: pools are created lazily per tenant, so the tenant count is unknown when a pool is made.
- **Defaulting the per-tenant cap to `capacity/2`.** Tried, then rejected: it halved a tenant's concurrency and stalled the process-mode tests; the cap is its own setting and defaults to the whole node.

**Consequences:**
- A hard cap means a quiet node idles by design: capacity 8 with cap 2 leaves 6 slots unused for a single tenant. A single-tenant node should set it to `capacity`.
- A budget leak would silently stop the node spawning workers, which is why the pairing lives in exactly two methods.
- **The node-wide cap now bounds worker processes** (`C93_MAX_WORKERS`, its own env, default `C93_CAPACITY`) — it is no longer `unbounded()`. Gating spawns on it used to deadlock: each tenant's pool held `minWarm` against the same allowance, so several tenants filled it with idle workers and a tenant that then could not spawn queued forever (`PackagesTest` hung). The fix is that **work preempts warmth**: no pool pre-warms (`minWarm = 0`), and a spawn *for work* that finds the cap reached reaps an **idle** worker node-wide (never one with jobs on it) and retries; if every worker is busy the job queues rather than failing.
- **The node-wide bound on concurrent *jobs* is the scheduler's, not the pool's** (D-154): it places at most `C93_CAPACITY` running attempts on a node, and the node re-checks it, so a node never runs more jobs at once than it advertises. This pool's cap is on **workers** — a different quantity, and the one that was actually unbounded.
- **A job submitted when no node has a free slot waits by default** (`C93_JOB_WHEN_FULL=queue`): it stays `QUEUED` and the reaper re-dispatches it when capacity frees. `C93_JOB_WHEN_FULL=reject` restores the immediate failure. Service runs always wait — a service that cannot start is restarted, not rejected.

**Evidence:**
- `TenantWorkerPoolTest.theNodeBudgetBoundsEveryTenantTogether`, `anUnboundedBudgetNeverRefuses`; `ServerConfigTest.maxJobsPerTenantIsItsOwnSetting`.

### D-157 — The tenant budget names its enforcement layer, per engine
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
A spike ran the **native** workers with `--heap-mb 128` and asked them to allocate past it:

| | Heap objects | External memory (`Uint8Array`) |
|---|---|---|
| Javet (native) | stopped ✓ | **not counted — ran on** |
| GraalJS (native) | stopped ✓ | stopped ✓ |

**Decision:**
The budget a tenant is shown names the layer that enforces it. GraalJS enforces per context in-process (`sandbox.MaxHeapMemory` under `engine.MaxIsolateMemory`, with `engine.SpawnIsolate` verified to take effect in the native worker). Javet's engine limit is **porous for external memory** — `ArrayBuffer` backing stores are not V8-heap objects — so **for Javet the cgroup is the tenant budget**, with the engine limit as a fast-fail for heap objects.

**Consequences:**
- Reporting one number for both engines would be reporting something that is not the whole truth.
- The spike becomes a test: over-limit heap allocation must be stopped, and Javet's external-memory gap is asserted as **known**, not silent.

### D-158 — CPU limits per tenant
**Date:** 2026-10-14 · **Status:** Accepted.

**Context:**
`CgroupMemoryLimiter` writes only `memory.max`/`memory.high`; there is **no CPU limiting anywhere** in the codebase.

**Decision:**
cgroup v2 `cpu.max` for process workers, `--cpus` for container workers, so one tenant's busy loop cannot starve another. A node refuses to start with a clear message when the controller is unavailable, rather than silently not limiting.

**Consequences:**
- The cpu controller is available at the root but **not enabled in the node's cgroup** on the development box; delegation has to be verified on a real systemd unit before this can be claimed to work.

**Evidence:**
- **Delegation verified on a real systemd host** (2026-10-15; Linux 7.0.0, systemd 259, cgroup v2): under a throwaway `Delegate=yes` unit shaped like `install/systemd/cluster93.service`, the unit's `cgroup.controllers` listed `cpu`, the **service user** (not root) enabled `+cpu` and wrote `cpu.max = 50000 100000` under the unit's own cgroup, and a busy loop was **throttled** (`nr_throttled = 4`). The probe is `scripts/cpu-delegation-probe.sh`; the procedure is `~/.opencode/plan/cpu-limits-tenant-test.md`. **The blocker is cleared.**
- **The base path is the real work, not the controller.** `CgroupMemoryLimiter` defaulted to the absolute `/sys/fs/cgroup/cluster93`, but under a delegated unit the only writable subtree is the unit's **own** cgroup — so both the CPU limit and the **existing memory limit** (D-127/D-163) needed the base pointed at it. **Built: `CgroupSupport` detects the host's shape** (own cgroup from `/proc/self/cgroup`, the cgroup2 mount, and the delegated controllers) and `WorkerCgroups` writes under it.
- **Built (2026-10-15).** `WorkerCgroups.prepare()` prepares the node's own cgroup at startup — it moves the node **into a leaf child** (cgroup v2 forbids enabling a controller for children while a process sits in the cgroup) and enables the delegated controllers; skipped when other processes share the cgroup, disabled by `C93_CGROUP_PREPARE=off`. Limits are then `<base>/tenant-<id>/cpu.max` and `<base>/tenant-<id>/worker-<pid>/memory.max`: **CPU per tenant** (its workers together, which a per-worker cap would not bound) and **memory per worker** (D-163). `C93_MAX_CPU_PER_TENANT` is the ceiling, a percentage of one core; 0 (unlimited) is the default.
- **The warn-vs-refuse question is resolved:** the node **warns and falls back** by default (the engine's own per-context limit is the only bound, as the memory limiter always did), and `C93_REQUIRE_CGROUP_LIMITS=true` **refuses to start** when limits cannot be enforced. `CgroupMemoryLimiter` became `WorkerCgroups` (it does CPU too). Reported as the `c93.cgroup.limits.enforced` gauge.

### D-159 — Crash attribution: tenant and job, not just a pid
**Date:** 2026-10-14 · **Status:** Accepted — built 2026-10-15.

**Context:**
D-135 already captures a worker's stderr tail and `inspectExit` already decodes 137 (OOM) and 139 (SIGSEGV) — but the result is a log line with a pid.

**Decision:**
Record a worker's exit and its stderr tail against the **tenant and job** and surface it where the tenant can see it, as a job/service failure such as "killed: memory limit exceeded".

**Consequences:**
- Mostly plumbing existing evidence into a tenant-visible place.
- Becomes more important with D-155: a crash now ends a tenant's other concurrent jobs too.

**Evidence:**
- `WorkerExit` names the cause from the exit code: 137 → "killed: out of memory or force-killed (SIGKILL)" (the cgroup OOM killer and a plain `kill -9` are indistinguishable from the code alone, so the phrase names both rather than guessing), 139 → "crashed: segmentation fault (SIGSEGV)", another non-zero code → "exited with code N", 0 → nothing (a clean exit is not a crash).
- `WorkerProcessRuntime` attributes a `FAILED` run when its worker's channel is broken, and reads the code from the process **reaper** (`Process.onExit()`), not the instant the pipe EOFs: the EOF and the process exit are observed on different threads, so reading `exitValue()` too early is exactly how the cause is lost (seen as a flake in `ProcessModeParityTest`). A bounded fallback keeps a broken channel on a process that somehow lives from hanging the job.
- The pool's `spawnWorker` attributes a worker that dies **before** its handshake too, so the same kill reaches the tenant whichever side of the handshake it lands on; `recycleWorker`/`inspectExit` now name the **tenant** in the crash and stderr-tail logs.
- Tests: `WorkerExitTest`, `WorkerProcessRuntimeTest`, `ProcessModeParityTest.detectsAWorkerProcessKill` (a real `kill -9`).

### D-160 — Stage 2 is container-per-tenant, not per-job
**Date:** 2026-10-14 · **Status:** Accepted. Supersedes D-144's shape. **Superseded in shape by D-166.**

**Context:**
A container per job is far too expensive to be the plan, and it would have to be rewritten the moment D-155 landed.

**Decision:**
When the container worker path is built, it is **one container per tenant** carrying that tenant's jobs, with `--memory` and `--cpus` per tenant and one socket each. It is not built until D-155–D-158 land, or it would be built twice.

**Consequences:**
- Fewer containers, and it lines up with Phase 7's "a container per tenant workload".
- Crash and resource boundaries become tenant-shaped, which is what D-158 and D-159 want.

### D-161 — Tenant fairness is arbitrated by the manager
**Date:** 2026-10-14 · **Status:** Accepted. Fixes D-153's placement.

**Context:**
D-153's rotation was installed in `TenantWorkerPool`, but every tenant has its own pool and `acquire` only ever passes that pool's own tenant: the rotation could never see a second one. Fairness was dead code.

**Decision:**
The arbitration moves to `TenantWorkerPoolManager`, the only place more than one tenant is visible. A pool reports that its tenant is waiting and reports when it frees capacity; the manager hands the freed capacity to the fairest tenant, skipping the one just served. A released worker is no longer served back to its own tenant's queue first — that was the starvation.

**Alternatives considered:**
- **Leave it in the pool and accept it never fires.** Rejected: fairness that cannot fire is worse than none, because it looks solved.
- **Keep the claim when a pool is missing (defensive).** Tried, then rejected: it makes the rotation a waiting list that never clears. A tenant that queued has a pool, so that branch is defensive only.

**Consequences:**
- A single tenant's pool is plain FIFO; ordering *between* tenants is the manager's job.
- `TenantRotation` is lifted out of the pool, since two classes use it.

**Evidence:**
- `TenantWorkerPoolManagerTest.theManagerRotatesBetweenWaitingTenants`, `nothingWaitingMeansNothingServed`.

### D-162 — A run's outcome is not the worker's health
**Date:** 2026-10-14 · **Status:** Accepted. Enables D-155; supersedes the boolean `release(worker, errorOrOom)`.

**Context:**
The pool's accounting unit is the **worker process**; the scheduler's and the engine's failure unit is the **job**. With one job per worker they coincided, so `release(worker, errorOrOom)` meaning "this worker is done or poisoned" was correct. D-155 split them, and `f44a1aa` implemented the split for the **normal** path only, leaving one escape hatch — *"a poisoned worker goes even with jobs still on it"* — that **every run-level non-success drove**: `WorkerProcessRuntime.cancel` hardcoded `true`, and the completion callback set `isError` for any status other than `SUCCEEDED`. So a parent cancelling its child recycled the worker the parent was running on: the process was destroyed, the pipe EOF'd, and `NodeWorkerChannel.onError` failed every run on the shared channel (`JobsFromScriptsTest.aParentCanStopAChildAndFollowItsEvents`, deterministic with more than one job per worker). `release` was also not idempotent per run — cancel released, then completion released again, driving the tenant's worker count negative.

**Decision:**
Separate the two signals. `release(worker)` is the run-level event: a job that failed, timed out or was cancelled frees **only its own slot**, and while the worker still has jobs it stays in service. `poison(worker, reason)` retires a worker that is **itself** unhealthy — process gone, OOM-killed, channel broken, bind failed — and does go even with jobs still on it, because they are already lost. Only worker-level evidence calls `poison`; a run that fails to produce a result is treated as worker-level, a run that produces any result (including `FAILED`/`CANCELLED`) is not. The cancel path no longer releases at all: the slot is freed when the run actually completes, so a sibling cannot reuse the worker before the cancelled run's context is gone. `retire` is idempotent, so a stray second release cannot double-count.

**Alternatives considered:**
- **Keep one boolean and stop passing `true` on cancel.** Rejected: the completion callback would still poison on a `FAILED`/`CANCELLED` status, so a failing sibling would still take the worker down.
- **Release on cancel and again on completion, guarded by a per-call flag.** Rejected as more state for the same result; one release point (the run completing) is simpler and is also what makes "reuse only after the context is gone" true.

**Consequences:**
- A worker is retired only on genuine worker-level faults, which is what D-159 wants to attribute.
- A cancelled run holds its slot until its context terminates. That is deliberate: for GraalJS memory is per context (D-157), and for a runaway the run's own timeout is the backstop.
- `TenantWorkerPool.release/poison` are the only lifecycle entry points; `WorkerProcessRuntime` chooses between them by whether the run produced a result.

**Evidence:**
- `TenantWorkerPoolTest.aJobEndingDoesNotKillAWorkerThatStillHasJobs`, `poisoningAWorkerReapsItEvenWithLiveJobs`, `releaseOnADeadWorkerReapsIt`, `aStraySecondReleaseIsHarmless`; `JobsFromScriptsTest.aParentCanStopAChildAndFollowItsEvents` (the reproduction); `ProcessModeParityTest`.

### D-163 — A worker's memory is sized for the jobs it runs
**Date:** 2026-10-14 · **Status:** Accepted. Follows D-155, D-157.

**Context:**
With one job per worker, the worker's cgroup was `heapMb + overhead`, because the engine gave the single context up to `heapMb`. With several jobs per worker the engine still gives **each** context up to `heapMb` (`sandbox.MaxHeapMemory`, under `engine.MaxIsolateMemory = maxHeapMb × contexts + 256 MB` for GraalJS), so a worker's real budget is the sum — but the cgroup was left sized for one job. A shared worker was OOM-killed (exit 137) the moment a second job pushed it past one job's budget. (`ProcessModeParityTest` surfaced it once the switch was on.)

**Decision:**
Size the worker's cgroup for the jobs it may run: `maxJobsPerWorker × heapMb` plus fixed native overhead for `memory.max`, and a little less for `memory.high`. The per-context engine budget is unchanged: `C93_MAX_HEAP_MB` is **per job**, not per worker.

**Consequences:**
- A worker running N jobs reserves N × `C93_MAX_HEAP_MB` of the node's memory; the node-wide budget (D-156, still unenforced) is where that has to be accounted next.
- Where the cgroup is unavailable (unprivileged, non-Linux, containers without the controller), the limit is not applied and the engine's own per-context limit is the only bound — unchanged from before.

**Evidence:**
- `ProcessModeParityTest` (a shared worker survives several concurrent jobs); the cgroup arithmetic lives in `TenantWorkerPool.spawnWorker`.

### D-164 — Per-tenant observability is a snapshot, its metrics, and the CLI
**Date:** 2026-10-15 · **Status:** Accepted.

**Context:**
The worker pools that D-155/D-156 introduced were invisible from outside. `TenantWorkerPoolManager` was reachable only by `close()` (captured in `RunningNode`), so the API, the metrics code and the CLI could not see a node's workers at all; each pool's `activeByTenant` map was documented "for reporting how the pool is shared out" but never exposed. The only worker-ish number anywhere was `/v1/nodes[].running`, which is the scheduler's *jobs* count, not the pool's *workers*.

**Decision:**
One **snapshot** is the source of truth: `TenantWorkerPoolManager.snapshot()` returns a `WorkerPoolSnapshot` — per node, workers running / the node's limit / warm / tenants active; per tenant, its workers, concurrent jobs, waiting jobs, and which layer enforces its memory budget (D-157). Two surfaces read it:
- **Prometheus**: node gauges `c93.workers.running|limit|warm` and `c93.tenants.active`, per-tenant gauges `c93.tenant.workers|jobs.running|jobs.waiting`, and a per-tenant crash counter `c93.workers.crashes` fed by D-159. Per-tenant gauges register with the tenant's pool as it is created.
- **CLI**: `c93 node status` reads the node's `/metrics` for the node-wide line, because the per-tenant worker stats are a metric and **no REST field is added** yet.

**Alternatives considered:**
- **Extend `/v1/nodes` with worker fields.** Deferred, not rejected: node objects are published by heartbeat from the cluster store (`NodeInfo`, in core) and the pool manager lives in `server`, so it would mean threading the pool into the heartbeat or a new endpoint. The metrics route already had the data and the existing scrape.
- **Register every per-tenant gauge at startup.** Rejected: tenants appear over time and are lazy, so the gauges register when a tenant's pool does.

**Consequences:**
- Per-tenant series are real cardinality: one `c93_tenant_*` set per tenant that has run a job on this node. Bounded by tenants, never by jobs.
- The CLI's worker line is best-effort — an unreachable or disabled metrics port simply omits it.
- The node-wide gauges read `WorkerBudget.used()` (in-flight spawns included), which can exceed the sum of the per-tenant "workers with a job"; the node view is the authoritative cap.

**Evidence:**
- `TenantWorkerPoolTest.thePoolReportsItsTenantUsage`, `aWorkerCrashIsCountedForItsTenant`, `reapingAHealthyWorkerIsNotACrash`; `TenantWorkerPoolManagerTest.theSnapshotReportsWorkerUse`; `ObservabilityTest` (node and per-tenant gauges); `NodeCommandTest.statusShowsTheNodesWorkerUseFromItsMetrics`.

### D-165 — The node-wide budget is memory, not only a worker count
**Date:** 2026-10-15 · **Status:** Accepted. **Refined by D-169** (the budget also reserves on connect, for workers the node did not spawn).

**Context:**
D-156 bounded how many worker *processes* a node runs (`WorkerBudget`, `C93_MAX_WORKERS`), not how much memory they hold. A worker's memory is its cgroup (D-163): `maxJobsPerWorker × C93_MAX_HEAP_MB` plus ~128 MB of native overhead. So the node's total worker memory was bounded only indirectly, and the part an operator cannot see — the per-worker overhead, multiplied by the worker count — was invisible to anyone reasoning in memory. D-163 named this as the next thing to account for.

**Decision:**
A `WorkerMemoryBudget` sits next to `WorkerBudget` (D-156): a worker reserves its **cgroup size** when it is spawned and gives it back when it is reaped, so the total cannot drift from the workers actually running. Its own env, `C93_MAX_WORKER_MEMORY_MB`, defaults to `C93_MAX_WORKERS × (maxJobsPerWorker × C93_MAX_HEAP_MB + 128)` — exactly what the worker count already implies — so it never binds before `C93_MAX_WORKERS` and a fresh node behaves as before; an operator lowers it to bound memory directly. `0` is unbounded. The count and memory caps are checked together on a spawn, and a job that fits neither queues (D-156) after an idle worker is reaped (D-156's "work preempts warmth"). Node-wide gauges `c93.workers.memory.used_bytes`/`limit_bytes` and the snapshot (D-164) report it.

**Alternatives considered:**
- **Bound memory instead of the count.** Rejected: the count is what bounds *processes* — PIDs and FDs, and the scheduler's concurrency — which memory does not. Both are real; the node needs both.
- **Refuse to start when the ceiling is below one worker.** Rejected in favour of always admitting the first worker: a ceiling set too low would otherwise let no worker run at all, and a node that runs no worker does no work.

**Consequences:**
- Per-worker memory is the cgroup *maximum*, so the reservation is a ceiling a worker may never reach. That is deliberate: the budget bounds what a node can be *committed* to, which is what stops a node oversubscribing itself, not a live measurement.
- The default makes the ceiling redundant with the count until an operator sets it — which is the point: it turns an implicit, invisible bound into an explicit, adjustable one.

**Evidence:**
- `WorkerMemoryBudgetTest`; `TenantWorkerPoolTest.theNodeMemoryCeilingRefusesASecondWorker`, `thePoolReportsItsTenantUsage`; `TenantWorkerPoolManagerTest.theSnapshotReportsWorkerUse`; `ServerConfigTest.maxWorkerMemoryIsItsOwnSetting`; `ObservabilityTest` (`c93_workers_memory_used_bytes`/`limit_bytes`).

### D-166 — The tenant boundary is the worker process, not the container
**Date:** 2026-10-15 · **Status:** Accepted. Supersedes D-160's shape. Shapes the Docker Stage 2 rewrite.

**Context:**
D-160 decided the container path would be **one container per tenant**, carrying that tenant's jobs, with `--memory`
and `--cpus` per tenant and one socket each — i.e. it made the **container** the tenant boundary. Reconsidering it
against what the worker model already is: a worker binds to exactly one tenant **for life** and that binding is the
crash and memory boundary (D-155), a container can hold several worker processes, and the per-tenant resource limits
D-160 wanted are already **per worker** (cgroup sizing, D-163; the node-wide memory budget, D-165). Making the
container the boundary bought nothing the process boundary did not already give, and cost either a container per
tenant or a multiplexing "worker host" — a second component in the path that re-implements worker lifecycle.

**Decision:**
**The tenant boundary stays the worker process.** A worker container holds a **tenant-agnostic pool of open
workers**: N `worker-*` processes, each connecting to the node **independently**, kept alive by a trivial supervisor
(an `sh`/`s6`/`tini` loop — not a protocol peer: no tenant config, no node credentials, no runner-protocol code).
The node binds each unbound worker to whichever tenant has work (D-155); the worker never declares a tenant. The
direction is settled with it: **the node listens and the worker connects** (D-127's built direction), so there is no
address discovery, no registration call and no lease in the open-worker case — a connection *is* an open worker.

**Alternatives considered:**
- **One container per tenant (D-160's shape).** Rejected: it makes the container the boundary the process already
  is, needs a container (or a multiplexing host) per tenant, and duplicates per-tenant limits that are already
  per-worker.
- **A container-hosted "worker host" that multiplexes several workers over one connection.** Rejected: it puts a
  second component between the node and the workers, against the zero-trust boundary where the node alone binds
  tenants and authorises calls.
- **The worker listens and the node dials.** Rejected: it forces an address registry, an authenticated registration
  and a lease (what happens when a holder dies), none of which the node-listens direction needs.

**Consequences:**
- **The container is *host* isolation, not *tenant* isolation.** Guest code is still separated by the engine (a
  fresh context per job, D-157); the container separates the *pool* from the machine. Two tenants' workers may share
  a container, so this tier is for **trusted tenants** — today's posture. A hostile tier still wants a per-tenant
  box or a per-worker sandbox (gVisor/Kata).
- **The node can neither spawn nor shed an open worker.** Supply — how many workers a container runs and its
  `--memory`/`--cpus` — is the **deployment's**; the node accepts what connects. So `WorkerMemoryBudget` (D-165)
  must reserve on **connect** and count idle, unbound workers (they hold memory before any job does), and the node
  cannot drop an idle worker by closing its socket (its supervisor would just restart it).
- **Fairness matters more**: many tenants contend for one shared pool, which is exactly `TenantWorkerPoolManager`'s
  job (D-161).
- **Placement simplifies**: a node advertises "I accept container workers", not a set of tenants.

**Evidence:**
- Design (not built): `~/.opencode/plan/worker-containers-design.md` — §5 the node-listens handshake, §6 the
  consequences above, §8 the settled decisions. The Stage 2 rewrite of `docker-node-pathway-workorder.md` is the
  build order; D-144/D-145's engine-socket shape falls with D-167.

### D-167 — The node never creates worker containers
**Date:** 2026-10-15 · **Status:** Accepted. Supersedes D-145, and the node-spawn part of D-144.

**Context:**
D-144 had the node run engine workers as **sibling containers** over the UDS transport, and D-145 required a
**rootless** engine for it — because that shape means handing the node a container-engine socket, and a rootful
socket is a root credential (`docker run -v /:/host`). D-160/D-166 then reshaped the container tier (the tenant
boundary is the worker process; the workers connect to the node). That leaves the question D-145 answered: does the
node hold an engine socket at all?

**Decision:**
**No — the node never creates, starts, stops or removes a container.** The **deployment** (compose, k8s) creates the
worker containers; their workers connect to the node over the UDS transport the node listens on (D-166, D-127). The
node therefore holds **no container-engine credential**, and D-145's rootless-engine requirement disappears with the
socket it guarded. `--worker-containers` and the node-side `C93_WORKER_TRANSPORT` switch are retired.

**Alternatives considered:**
- **Keep the engine socket as an opt-in** (`--worker-containers` + rootless, D-145) so a node could spawn a container
  on demand. Rejected: it puts a root-equivalent credential in the node for a capability the deployment already
  provides, and keeps two worker sources alive where one will do — the same reasoning D-166 used to drop the
  multiplexing host.

**Consequences:**
- **Dynamic container spawn is given up.** Adding a worker container is a **deployment** action, not a node action.
  Supply and idle control follow to the deployment (D-166, `worker-containers-design.md` §6).
- The engine probe (`NodeInstall.detectEngine`) remains only for the CLI's own **install-time** driving of the
  node's *own* container (D-141); it is no longer load-bearing for workers, and its rootless answer stops being a
  gate.
- §S6 ("the node's largest privilege") of `docker-node-pathway-workorder.md` is retired with the socket.
- **D-147 and D-150 remain in scope and become more load-bearing**, not less: with the node listening, the
  connection must carry a per-connection secret (D-147) and every RPC must be authorised (D-150) before any tenant
  code can reach a socket.

**Evidence:**
- `~/.opencode/plan/worker-containers-design.md` §1 (the divergence table) and §8 (settled). Not built; the Stage 2
  rewrite is the build order.

### D-168 — The worker secret is pre-shared, not per-spawn
**Date:** 2026-10-15 · **Status:** Accepted. Refines D-147 for deployment-provisioned workers.

**Context:**
D-147 introduced the secret in `HELLO` so that a socket is not a **downgrade** from a pipe: stdio binds a channel to
the child the node itself created, and a socket binds it to "whoever connected". It assumed the node **spawns** the
worker — a fresh 32 random bytes per worker, passed in the container's environment. D-166/D-167 then made the
**deployment** create the containers, with the workers connecting to the node, so there is no spawn to attach a
per-spawn secret to.

**Decision:**
For deployment-provisioned workers the secret is **pre-shared and per-node**: a file both sides read
(`C93_WORKER_SECRET_FILE`, mode `0400`, mounted read-only into workers), presented in `HELLO` and validated. It is
**weaker** than per-spawn — anyone who can read the mount can present it — and is stated as such rather than
implied, so the narrowed shape leans harder on the other layers: the filesystem boundary (dir `0700`, socket
`0600`), a **`SO_PEERCRED`** check that the connecting process is the node's own user, and the authorizer (D-150).
A per-spawn secret remains the right shape wherever the node **does** spawn (the process tier, and any future
node-spawned container tier).

**Consequences:**
- The secret file becomes a thing to protect: `0400`, owned by the node's user, mounted read-only into workers, and
  named in the guide. A loose mount defeats it.
- **`SO_PEERCRED` is promoted from an aside to a required layer** on Linux (S3, `docker-node-pathway-workorder.md`
  §S3); its non-Linux fallback is an open question (§S3, §2.4).
- The `HELLO` field D-147 added is mechanically unchanged (an added optional JSON field); what changes is how the
  value is *provisioned*, not the handshake.

**Evidence:**
- `docker-node-pathway-workorder.md` §2.4, §S3, §S5; `worker-containers-design.md` §5.

### D-169 — The memory budget reserves on connect, for workers the node did not spawn
**Date:** 2026-10-15 · **Status:** Accepted. Refines D-165 for deployment-provisioned workers.

**Context:**
D-165's `WorkerMemoryBudget` reserves a worker's cgroup size when the node **spawns** it and releases it when the
worker is recycled. With D-166/D-167 the node does not spawn container workers — they **connect** to its listening
socket — so a spawn-time reservation would never account for them, and an **idle, unbound** worker, which holds
memory before any job does, would be invisible to the node's memory ceiling.

**Decision:**
The budget also reserves **on connect** and releases **on close**, so a connected worker counts from the moment it
joins the pool, idle and unbound included. One reservation per worker whichever source it came from (spawn or
connect), and the pairing stays in exactly two places, so it cannot drift — the same rule D-165 set for the count.

**Consequences:**
- The node's memory ceiling now covers container workers as well as process workers — the only unit common to an
  isolate and a container, which is why `ideas/containers.md` asked for memory and not "workers".
- **The size to reserve for a container worker is not the node's to measure** — its cgroup is the container's
  (`--memory`), which the node never sets. So the node reserves a **declared** size (configured, or announced by
  the worker in `HELLO`, which already carries `--heap-mb`); this must be stated so the reservation is not mistaken
  for a measurement. The exact source is settled at build time.
- A `worker:container` capability advertises that a node accepts such workers, so placement does not send work to a
  node with no pool (docker work order §2.6).

**Evidence:**
- `docker-node-pathway-workorder.md` §2.6; `worker-containers-design.md` §6.

### D-170 — A claim served from a shared worker's free slot drains the pool queue
**Date:** 2026-10-15 · **Status:** Accepted. Refines the queue behaviour D-155 and D-156 introduced.

**Context:**
A tenant's pool holds few worker processes, each running up to `maxJobsPerWorker` jobs at once (D-155). A claim that
finds no worker and no room to spawn is queued and served later by `processNextPending`, which the pool ran when
capacity *freed* (`release`, `poison`) or when a spawn completed. Because a worker now carries several jobs, a claim
can also be satisfied **without anything being freed** — by taking a free job slot on a worker that is already active
(step 1 of `acquire`). That step returned its worker and stopped, so serving one claim left any claim queued behind it
unserved. Whether that bit was a race: a claim that arrived while the tenant's only worker was still starting (not yet
in `activeWorkers`) queued, and the spawn's completion then pumped the queue exactly **once**. A claim left over waited
for a *release* — and a worker whose siblings are long-running (a service, which never ends) never releases, so it
waited forever. `3ccfc99` had closed the same gap for the *first* stranded claim ("a worker that becomes bound serves
the queue too"); it was incomplete for a second and later claim.

**Decision:**
When a claim is served by taking a free job slot on an active shared worker, the pool **drains one more queued claim**,
on its own context. The drain is deferred, not inline: a claim takes its job slot only when its job actually starts
(the handle counts it then), so draining inline could hand the same free slot out twice.

**Consequences:**
- The queue advances whenever a claim is served, however it was served — capacity freed *or* a free slot taken — so no
  claim waits on a release only a long-running sibling could produce.
- The drain places one claim per turn and stops as soon as a claim cannot be placed, so it cannot spin: a claim with
  no room is queued again and no further drain is scheduled. Admission stays the caller's (D-155).
- Deferral makes the pool's view of a worker's free slots correct before the next claim is served, because the runtime
  admits the job synchronously when its handle arrives.

**Evidence:**
- **Diagnosed from thread dumps of a stuck run**, not inferred: the worker was idle with two long-running service
  isolations; the node's pool held the job as a queued `PendingAcquire` (`workers=1/1 active=0 pending=1 -> queued`)
  and never served it. Reproduced on the pre-D-158 tree, so it predated the cgroup work.
- `TenantWorkerPoolTest.servingAClaimFromASharedWorkerDrainsTheQueue` — without the drain, only the first of four
  queued claims is placed; with it, the worker's free slots carry three and the fourth keeps waiting rather than
  overrunning the worker.
- `ProcessModeParityTest`: `217/217` on three consecutive full-suite runs (it had been failing roughly half to
  three-quarters of them), and `./mvnw -o verify -pl cli,runner-protocol,server -am` is green.

### D-171 — The connected-worker build details, and what a real container taught us
**Date:** 2026-10-15 · **Status:** Accepted. Settles the build-time questions D-166/D-167/D-169 left open; refines D-150. **Refined by D-172** (a worker connection is re-bindable once idle, so item 4's "the node cannot shed an idle worker" is no longer the whole story).

**Context:**
D-166/D-167 fixed the shape (the node listens, the deployment creates the containers, a connection is an open worker)
and D-168/D-169 fixed the secret and the memory reservation, but three things were explicitly left to build time: the
connect-retry numbers, the capability name, and where a connected worker's reserved size comes from. Two more surfaced
once a **real** worker container was run against a real node (`scripts/worker-isolation-check.sh`, case C3 on rootful
Docker, 2026-10-15) — neither is visible to a unit test.

**Decision:**
1. **The retry is bounded and visible:** `ipc --socket` tries **30** times, backing off **100 ms doubling to a 5 s
   cap** (≈2 minutes), then exits non-zero naming the socket. It is what makes "listen first, connect second" safe
   with no start ordering, and failing visibly is what lets the supervisor's restart and the node's log both show a
   worker that cannot reach the node.
2. **The capability is `worker:container`**, advertised by a node with `C93_WORKER_SOCKET` set. Placement itself
   stays the scheduler's — the `worker` role decides whether a node takes tenant compute and capacity decides how
   much — because `NodeInfo` carries no capabilities yet; the advertisement is what says "this node accepts a pool a
   deployment supplied", and the pool is tenant-agnostic, so no per-tenant routing exists to gate.
3. **A connected worker's reserved size is declared, from `HELLO`:** the worker announces its `--heap-mb` and
   `--threads`, and the node reserves `heapMb × maxJobs + 128 MB`, falling back to its own defaults when a worker
   announces nothing. Its container's cgroup is the deployment's, so this is the one size the node can know — and it
   is why `HELLO` grew those two fields.
4. **Connections are bounded by `C93_WORKER_CONNECTIONS_MAX`** (defaulting to `C93_MAX_WORKERS`), **not** by the
   worker-count allowance: the node cannot refuse a connection without the worker's supervisor immediately starting
   another, so a refused connection is a reconnect loop rather than a decision. Memory is the unit both sources share
   (D-169).
5. **The authorizer checks a run's liveness, and exempts the run's own teardown** (D-150): a call must be for a run
   this node is executing and for the tenant the worker is bound to, and must name a documented host-API area. The
   teardown calls — `execution.close`, `serve.unlisten`, the `*.unsubscribe`/`unconsume`/`unevents` family — are sent
   one frame after `RESULT_RUN`, when the node has already let the run go, so they are allowed on the binding alone;
   they only release what the run held, and the channel answers them only for a run it still carries.
6. **The socket is forced to `0600`** after the bind. The kernel creates it with the process umask — on the test host
   that was `0775`, i.e. connectable by every user, which is precisely the boundary a worker connection crosses (S3).
7. **Two facts a deployment must know**, both found by the isolation check rather than by reasoning: the node image's
   **entrypoint is the server**, so a worker container must replace it (`--entrypoint`, or `entrypoint:` in compose) —
   passing the worker command as arguments starts a *second node*; and a **GraalJS** worker needs a home that is both
   **writable and executable** (`--tmpfs /home:rw,exec,mode=1777`), because it unpacks its native isolate library into
   `$HOME/.cache` and then `dlopen`s it, so a read-only root (S4) makes it die at startup and a `noexec` home cannot
   load it.

8. **`SO_PEERCRED` (S3's layer 2) is NOT implemented, and the tree says so rather than implying otherwise.**
   The node's UDS listener is the **JDK NIO** implementation (`io.vertx.core.impl.transports.UnixDomainSocketNioTransport`):
   no native transport is on the classpath (the platform `netty-transport-native-epoll` artifact is absent; only the
   shared `netty-transport-native-unix-common` is), and Netty exposes **no accessor** for a peer's credentials — its
   `io.netty.channel.unix.PeerCredentials` type exists, but nothing in 4.2.18 constructs or returns one through a
   public API (`Socket`, `DomainSocketChannel` and `FileDescriptor` offer no such method, and `FileDescriptor.fd` is
   package-private). `java.nio` has no `SO_PEERCRED` support at all, and Vert.x's `NetSocket` exposes only addresses
   (plus `peerCertificates()` for TLS). Getting it would mean either reaching into `sun.nio.ch` by reflection for the
   fd and calling `getsockopt` through FFM, or adding the native epoll transport and depending on an API Netty does
   not promise — both are deliberate additions with fragile edges, not build details, so neither was taken here.
   **The binding therefore rests on the other three layers:** the `0700` directory and `0600` socket (which the
   kernel enforces *before* a connection exists, so a peer that is not the node's user is refused outright rather
   than rejected after the fact), the pre-shared secret (which only a reader of a `0400` file can present), and the
   authorizer (which scopes every call to a run's tenant). **The shared-group fallback is consequently not offered
   anywhere** — a `0750`/`0660` socket would be the one configuration where the filesystem stops telling peers apart
   and `SO_PEERCRED` would carry the layer, so if an operator ever wants that fallback, layer 2 has to land first.
9. **Layer 1 is enforced at bind time, not assumed.** `UdsServerTransport.listen` sets the socket to `0600`, reads
   the mode back, and **fails the bind** (closing the socket) when it is anything else; it also refuses a directory
   in which another user could write — that is what would let someone remove the socket and bind their own over it —
   except a **sticky** one like `/tmp`, where the kernel refuses to remove a file the writer does not own. A host
   that cannot report Unix permissions at all is refused too, for the same reason. So the node either holds the
   socket to its own user or serves no worker containers: it never accepts peers it cannot tell apart. This is what
   makes layer 2's absence inconsequential rather than merely recorded, and it is why the choice above was made.
10. **A worker engine needs writable *and executable* scratch space** — found by the isolation check, not by reasoning.
    A worker unpacks a native library and then `dlopen`s it: Javet loads its V8 from `/tmp/javet/...`, GraalJS its
    isolate from `$HOME/.cache`. A read-only root (S4) has nowhere to put either, and Docker's `--tmpfs` defaults to
    `noexec`, so the worker *joins the pool* and then fails its first job with
    `UnsatisfiedLinkError: Can't load library: /tmp/javet/1/libjavet-v8-linux-x86_64-i18n.v.6.0.2.so`. **A native image
    bakes those paths in** — `java.io.tmpdir` is fixed at build time and `user.home` comes from the passwd entry, not
    `$HOME` — so they cannot be pointed at a nicer directory: the deployment gives each container a directory of its
    own mounted **at** them (`…/w1/tmp:/tmp`, `…/w1/home:/home`). A plain directory is executable by default, so
    **nothing is relaxed** (no `noexec` tmpfs, no `--tmpfs …:exec`), it costs no container memory, and it holds a few
    tens of MB rewritten at each start and never accumulating — one directory per container, since two workers must
    not write the same file at once. Measured: the library is rewritten even when present, so "extract once, ever"
    would need the library baked into the image at build time; that remains an option, not a requirement.
11. **Rootless podman needs the image's ids spelled out** (D-149's mapping, made explicit): `--userns=keep-id:uid=10001,gid=999`,
    not plain `--userns=keep-id`. Plain `keep-id` maps the *invoking* uid to itself and leaves the container running
    as the image's user (`c93`, 10001), while the node's socket and secret belong to the invoking uid — so the worker
    cannot read the secret and never joins (found by case C1, which is exactly why the check exists). With the ids,
    C1 passes: the container's process owns the node's files, so the `0600` socket and the `0400` secret are its own.
    Relatedly, a **registry-qualified base image** (`docker.io/library/eclipse-temurin:21-jre`) is required for
    `podman build` on a host with no configured search registries — podman refuses a bare short name, where Docker
    resolves one.

**Consequences:**
- The connection cap is a bound on what the node *accepts*; the pool's size is still the deployment's, and the node
  still cannot shed an idle worker (§2.7).
- The teardown exemption is a small, named hole in the liveness check, chosen over weakening it for every call: the
  calls that touch tenant state are still scoped to a run the node is executing.
- The GraalJS cache requirement means a worker container is not *quite* "no writable filesystem": it needs a scratch
  home, which `compose.workers.tmpl` supplies as a tmpfs and documents.

**Evidence:**
- `WorkerSocketSourceTest` (connect → HELLO → secret → open worker → bind → a real job), `WorkerSocketTest` (a real
  node, a real worker over the socket, a job through the API, `c93_workers_running 0`), `UdsConnectRetryTest` (the
  retry succeeds late and fails cleanly), `WorkerRpcAuthorizerTest`.
- `scripts/worker-isolation-check.sh`, against real engines on the build host: **C1 (rootless podman), C3 (rootful
  Docker) and C4 (a foreign uid refused) all pass** — a worker container reaches the `0600` socket as the
  deployment's identity, presents the secret, joins the pool as an open worker and **runs a tenant's job over it**
  (`Job … ended SUCCEEDED after 393 ms`) while `c93_workers_running` stays `0.0`, so the node genuinely spawned
  nothing; the wrong secret is refused; the container has no network reachability; C2 prints `SKIP` until rootless
  Docker exists. It also **found** four of the facts above: the `0775` socket (the kernel's umask, not `0600`), the
  entrypoint trap (a worker that passes its command as arguments starts a *second node*), the engine scratch space
  (`FileSystemException: …/.cache: Read-only file system`, then `UnsatisfiedLinkError` from a `noexec` mount — item
  10), and the two traps in one worker container's own startup.
  Getting that pass needed `bin/worker-*` rebuilt (the checkout's predated `--secret-file`, so they presented no
  secret and the node refused them — correctly, but for a reason that was not the deployment's; the check now
  **skips** with the rebuild command rather than reporting a false failure) and three fixes to the check itself, each
  of which had made it lie: a metric wait whose `$(…)` was expanded once instead of re-read (a worker that had
  connected was reported as never having said hello), a **tenant token** for the job (an admin-capable token is
  refused without `X-Cluster93-Tenant`), and a missing socket mount in the wrong-secret case (which made that
  assertion pass vacuously, before it actually asserted the refusal).

### D-172 — A worker serves one tenant at a time, and is re-bound when idle
**Date:** 2026-10-16 · **Status:** Accepted (Tony: "I do think BIND should be rebindable"). Refines D-155; made necessary by the caps in D-156 and the connection source in D-166.

**Context:**
D-155 made the binding permanent: "a worker is **never shared between tenants**", and the node enforced it with a one-shot
`bind()` (`UNBOUND → BOUND`, and a second call failed). Every cap added afterwards made that stickiness more expensive. A
node-wide worker cap (D-156) and an accept cap on connections (D-171 item 4) mean a bound worker that has gone **idle**
cannot be shed: it sits in tenant A's warm queue while tenant B — with work waiting — either starts another process or
waits for the cap. With a deployment-supplied container (D-166, D-167) that is the deployment's one container idling while
the node spawns a process of its own. Tony's question ("does that mean a worker that has no more running jobs or services
can just go back into the pool?") had an obvious answer, and the honest one was not the answer the code gave: yes, an idle
worker is a reuse opportunity, and the reason it was not reused was a rule about *processes*, not about isolation.

**Decision:**
1. **A worker serves one tenant at a time, not one tenant for life.** The binding is a state the worker and the channel
   carry, and it can move — but only while the worker has **nothing running on it** (no job, no service): the drain
   condition is exactly "in flight == 0", which the pool's books already guarantee, because a worker only reaches a warm
   queue when its last run has been released. `WorkerHandle.bind` therefore re-binds an idle worker (and refuses one with
   runs in flight, naming "idle" so the refusal says what it is waiting for). "Sharing" here means *re-binding in turn*;
   two tenants never run on one worker at once.
2. **Reuse comes before a new process.** `TenantWorkerPool.acquire` asks the node for an idle worker another tenant is
   holding (path 2c) before it spawns, after its own tenant's workers and the node's open workers. The arbiter
   (`NodeArbiter.takeIdleWorkerFor`) is the only thing that sees more than one tenant, so the choice belongs there —
   alongside the rotation (D-161) and the reap (D-156), of which this is the twin: work preempts warmth, but it now
   **reuses** the idle worker instead of retrying the spawn.
3. **The accounting moves with the worker, the allowances do not.** `adopt`/`offerIdleWorker` transfer the worker between
   the two pools' books (`totalWorkers`), because the node-wide worker count and memory are taken when the worker is
   *created* and released when it *dies* — moving pools changes neither, so a transfer cannot leak a slot or double-count
   memory. A worker's cgroup moves with it (`WorkerCgroups.applyWorker` writes `cgroup.procs`, which *is* the move; the
   old tenant's directory is cleaned up after), since the tenant is the CPU boundary (D-158). A connected worker's cgroup
   belongs to its container, so nothing moves there.
4. **The protocol needed no new message.** Both ends already accept a repeated `BIND` — `WorkerDaemon.onBind` sets the
   bound tenant, `NodeWorkerChannel.bind` sets its own and sends the frame — and `NodeWorkerChannel.run` already refuses a
   run whose tenant does not match the channel's current binding. That last check is what makes re-binding safe rather
   than merely convenient: a run dispatched under the old tenant cannot execute on a worker that has moved.

**Consequences:**
- Warmth is now shared: a tenant's idle worker can be taken by another tenant that has work, so a burst of sequential jobs
  pays a cold start when a second tenant interleaves. That is the same trade D-156 already made for the caps (work
  preempts warmth) and the same one D-153 makes between tenants; the alternative — the idle worker blocking a job — is
  worse, and it was demonstrably happening with the deployment's one container.
- A worker **with runs in flight is never moved** (nothing to gain: its tenant needs it, and its calls must stay
  authorised against the tenant they started under). So a busy tenant is not disturbed by a waiting one.
- The run-level boundary is unchanged, and it is what the isolation claim rests on: a fresh context per run, host-API calls
  authorised per run (D-150), per-tenant cgroups, and the secret/socket boundary on the connection (D-171). What changed is
  the lifetime of a *process*, not the lifetime of a *boundary*.
- Unbound pre-warmed workers (`minWarm > 0`, 0 by default) are still this pool's own spares and are still reaped for
  warmth rather than offered; if warmth is ever turned on again, offering those too is the obvious next step.

**Evidence:**
- `TenantWorkerPoolTest.anIdleWorkerIsReboundToAnotherTenant` (two tenants, one worker, one node slot: tenant B is served
  by tenant A's worker, same worker id, no new process, the counts transfer and the node's slot count does not move),
  `aBusyWorkerIsNeverMoved` (a worker with a run in flight is not offered; the waiting claim queues rather than take it)
  and `aDeadIdleWorkerIsNotHandedOver` (one that died while it sat idle is recycled where its books are, not adopted).
- `WorkerRebindTest` end-to-end: one worker container over the socket, tenant A runs a job, goes idle and warm, then tenant
  B's job is served **on the same connection** — `c93_workers_running` stays `0.0`, `c93_workers_connected` stays `1.0`, the
  worker reports itself re-bound, and B's run sees nothing of A's (a marker A set in its engine's globals reads back
  `undefined`, because the run's context is fresh).
- `scripts/worker-isolation-check.sh` re-run against **both** rebuilt engines (`worker-javet`, `worker-graaljs`): **C1
  (rootless podman), C3 (rootful Docker) and C4 (a foreign uid refused) pass**, with a job round trip, the host fetched
  through the node, the gate refusing the cluster's own port, a service answered through the ingress, the wrong secret
  refused and no network in the container — so the container path is unchanged by the re-bind.
- The touched-module gate: `./mvnw -o verify -pl cli,runner-protocol,server -am -DskipITs` (server 230, runner-protocol 96,
  cli 114/2 skipped, plus core/runtime).

### D-173 — Egress runs in the worker; the node keeps the rules
**Date:** 2026-10-16 · **Status:** Accepted (the design; **nothing is built**). Running the policy **in the worker is a requirement**, and **the worker is the default placement** — the node stays the authority (it is where the rules, cluster membership and budgets come from) without being in the path per connection or per byte. Extends D-108/D-111; relates to D-126, D-166, D-167, D-172.

**Context:**
Every outbound connection tenant code makes is made by the node: a worker's `fetch`/`http`/`https`/`net`/`tls` are
host calls (D-108), so the node resolves, applies the egress gate, dials and streams the body back to the worker.
Measured on the build host (2026-10-16, means over four repeats — see the draft in `docs/benchmarks.md`) that
boundary costs **+55–75 µs per host-call round trip** (~4.5× the in-process path) and about **half the throughput**
of a streamed 64 MB body (240 → 117 MB/s, and ~1.5× the stream chunks). Crypto and zlib are *not* in that cost: they
run in the worker (D-126). Two different arguments follow, and they are not the same one. **Latency:** the hop is
~0.1% of the RTT to a public endpoint, and the 2× was measured against a *loopback* server — the best case for the
other side — so "hit public HTTP without the hop" is not what this is about. **Node capacity** (Tony's argument):
the node relays every tenant's outbound bytes, so its CPU, event loops and bandwidth are the cluster's egress
ceiling, on the same process that schedules, serves the ingress, stores data and answers the API. Fanning the bytes
out to the workers is the point.

**Decision:**
1. **The egress policy runs in the worker, and that is the default placement.** One module holds the policy *and* the
   code that governs outbound connections, and it can run on **both** sides: an `egress` core that is pure (normalize
   the address → static deny blocks → tenant rules → allow/deny) plus a **policy source** (the rules synced to a
   worker, or the node's own configuration) and an **accounting sink** (a budget the worker enforces, or the node's
   counters). Placement is configuration rather than a second implementation, so the same checks run in both places, a
   node or tenant can be placed either way, and the two can be parity-tested against each other. The node stays the
   **authority** — it is where the rules, cluster membership and budgets come from — it is simply not in the path per
   connection or per byte. Javet and GraalJS are the *same* worker binary, so the worker side is one artifact today;
   the core stays language-neutral (or hosted on the worker's Java side) so a future Python worker does not fork the
   rules.
2. **Deny is CIDR-shaped, static, and needs no DNS:** loopback, `169.254.0.0/16` (cloud metadata), `10/8`,
   `172.16/12`, `192.168/16`, CGNAT `100.64/10`, ULA `fc00::/7`, the documentation ranges, and the IPv4-in-IPv6
   forms (IPv4-mapped, NAT64, 6to4). Ports appear only on the **allow** side. Static means it cannot go stale, so it
   can live in the core — and be mirrored into nftables for free if the extra floor is wanted.
3. **Rules are synced over the existing worker↔node connection** (the connection already exists), **versioned** and
   with a **TTL: a worker that cannot refresh fails closed.** The cluster's own addresses and ports ride in the same
   payload as deny entries and are refreshed when membership changes, so holding that rule does not require the worker
   to derive cluster knowledge. This is load-bearing in the default placement: it is what lets the worker decide
   without asking.
4. **TLS comes from the image** (`ca-certificates` or whatever provides them): whoever connects must verify the peer.
   That is a dependency, not a secret and not an authority handed over.
5. **Resolve → check → connect to the address that was checked.** The node does exactly this today; a gap between the
   check and the connect is DNS rebinding.
6. **The cluster's edge refuses worker-source connections** as a second layer, so "a worker cannot reach the cluster"
   holds even if the worker's own core is wrong. Container mode only: a *spawned* worker's connections come from the
   host itself, so no source-IP rule can tell them apart.
7. **Per-tenant connect accounting moves with the policy** — a budget the node allocates and the worker enforces, or
   a report per connection (one hop per connection, nothing next to a body). The D-156/D-165 idiom: the node
   allocates, the pool enforces.
8. **Placement is per node and per tenant, delivered with the binding, and the worker is the default.** Per-tenant
   placement is consistent with D-172: a worker serves one tenant at a time and is re-bound when idle, so the mode can
   arrive in the `BIND` payload and change when the worker is re-bound to another tenant. The default decides what the
   isolation check asserts: today `node` keeps a container's *"no route"* as the verified property; with `worker` as
   the default, every container gets a route and the *static deny blocks* become the property we verify instead. So
   the check grows a variant, and "no route" becomes the property of the placements that still want it.
9. **Resolution is the worker's, in the default placement — the node does not approve per name.** The worker owns
   the decision end to end: it resolves, checks the resolved address against the static blocks and the synced rules
   (cluster addresses included) and connects to *that* address with the SNI of the name. Nobody asks the node per
   name, per connection or per byte; the node's part is the rules, membership and budgets. The **node-approves-per-
   name** lookaside stays available in the same module — resolution and the check on the node, the approved address
   returned to the worker for a TTL — which is the placement to reach for when a workload resolves thousands of
   distinct hosts (where the node would otherwise be a DNS proxy) or when a tenant wants DNS gated and will not have
   a resolver in its container.

**Alternatives considered:**
- **The node dials and then hands the worker the connection** (fd passing over the Unix socket, or a kernel splice).
  Removes the byte relay but keeps the node's per-connection work — resolve, gate, dial, TLS — and puts fd passing
  into a Java runtime. Rejected as still too expensive for the node-capacity goal; kept as the smallest change if
  bytes are the only concern.
- **The node approves, per name or per connection** (the worker asks "may I reach `api.stripe.com:443`?", the node
  applies the rules, resolves, and answers with the approved address for a TTL; the worker connects and does TLS).
  One source of truth for the check, cluster knowledge applied at approval time so nothing has to be synced, DNS
  never in the container, accounting observed rather than reported. Rejected as the default: it keeps a round trip in
  the name path for a decision the worker can make from synced rules, and the point is that the worker owns it. It
  stays available in the same module for high name cardinality (where the node would otherwise be a DNS proxy) and for
  a tenant that wants DNS gated without a resolver in its container — a configuration change, not a rewrite.
- **Worker-side *ingress*.** Rejected as a category error: ingress is clients → the node → a service, where the node
  is the door and the switchboard (Host → tenant + service out of the service store, `C93_MAX_REQUEST_BYTES`, idle
  timeouts) and a worker has no route to be reached at.
- **Kernel default-deny in the container's namespace** (nftables/BPF). The right belt for the static set, but the
  deployment owns it (the node creates no containers, D-167) — the same trade as cgroups (D-171 item 10). Optional,
  not required.

**Consequences:**
- **Nothing is built.** This decides the shape and the default placement, not that it is switched on today.
- **The default placement is the worker**, so a container is expected to have a route and the *static deny set* is
  what carries the catastrophic cases for every tenant. "No route" does not disappear — it becomes the property of
  the placements (per node, per tenant) that still want it, and the isolation check asserts whichever property the
  placement in force implies.
- **One new residual channel, in a restrictive posture:** with a route and a resolver, a worker whose policy allows
  only a few hosts can still put data in DNS query names. With an allow-public posture that changes nothing (a route
  already permits an HTTP exfiltration); for a deny-most tenant, point the container's resolver at the node or accept
  the hole — the node resolves today, so this is new when the worker owns it.
- **Revocation latency is the rules TTL.** A worker that cannot refresh must deny rather than serve stale rules,
  which means egress is coupled to node connectivity — the same coupling the node-approval placement has, so it is
  not a reason to prefer one over the other.
- **What the one measurement decides is urgency, not shape:** how many MB/s one node can relay with many workers
  fetching concurrently before its CPU saturates. If a node relays far more than a site's link, this is architecture
  to build when convenient; if it caps in the low hundreds of MB/s, it is the next thing after Stage 3.
- **An engine escape in a worker with a route is a network client** bounded only by the kernel floor, where today it
  can at most make calls the node authorises. That is the standing cost of the default placement — and it is why the
  static deny set (item 2) and the cluster edge (item 6) have to carry the catastrophic cases on their own, without
  relying on the policy core being correct.
- Corrections from the discussion, recorded so they are not re-argued: a check inside the worker is **not** reachable
  by the guest (the engine is the boundary, and the Java beside it is not guest-reachable) — the difference is what a
  checker bug or an engine escape *reaches*, not who can edit the rules; the node's gate is authoritative because of
  *cluster* facts and accounting, not reachability; and crypto/zlib already run in the worker, so they were never part
  of the hop.

**Evidence:**
- The measurement: `docs/benchmarks.md`'s "What the worker boundary costs" (draft, 2026-10-16) with the harness
  (`proxy-cost.sh`, to be committed beside it).
- The full discussion and its reasoning: `~/.opencode/plan/worker-egress-design.md`.
- The code the policy core would come from: `core/src/main/java/cluster93/core/host/egress/Egress.java` and
  `EgressPolicy`, used by `FetchClient`/`SocketClient`. The worker binary already links them — `WorkerMain` builds a
  `FetchClient` for its standalone/benchmark modes — so the missing part is the policy plumbing and the posture, not
  the code.
