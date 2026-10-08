# Decision log

Each entry records a choice, the reasons for it, and what it costs. Entries are never deleted. If a decision changes, add a new entry that supersedes the old one.

---

### D-001 — Rewrite on Vert.x 5 instead of reviving jsync.io
**Date:** 2026-10-06 · **Status:** Accepted

jsync.io was a renamed fork of Vert.x 2 core. The only original part was the `io.jsync.app` cluster layer. Its source still exists, so it doesn't need restoring or decompiling. Upstream Vert.x 5 replaces everything else in it.

**Decision:** Start a new repo and port Automately Core's *ideas* (scheduling, runtimes, hosting) onto Vert.x 5. Don't copy 2017 code.
**Cost:** Every feature has to be rebuilt. Legacy runtimes (PHP, Dart, Docker), SFTP and Azure are dropped deliberately.

### D-002 — Javet V8, one isolate per job
**Date:** 2026-10-06 · **Status:** Accepted

Nashorn was removed in JDK 15, and J2V8 is unmaintained. Javet is maintained, embeds V8 and Node.js, and supports terminating a running isolate from another thread.

**Decision:** Each job gets a fresh isolate, which is closed afterwards. The only host object is a frozen `console`. The host binding is hidden from the global scope by a prelude.
**Cost:** A cold isolate is created per job, which costs a few milliseconds. Pooling is planned for phase 3. The native library is per platform; only linux-x86_64 is included.

### D-003 — Heap limit through V8's near-heap-limit callback
**Date:** 2026-10-06 · **Status:** Accepted

When an isolate runs out of memory, V8 aborts the **whole process**. Javet doesn't offer a per-isolate heap setting, but it does offer a process-wide `--max-heap-size` and a per-runtime near-heap-limit callback.

**Decision:** Set the max heap once at startup from `AUTOMATELY_MAX_HEAP_MB`. In the callback, terminate the script and grant 32 MB of headroom so V8 can unwind. Report the job as `FAILED: memory limit exceeded`.
**Cost:** The limit is the same for every isolate in a JVM, and the first runtime constructed sets it. Per-tenant limits would need separate runner processes.

### D-004 — Vert.x SharedData in front of Hazelcast
**Date:** 2026-10-06 · **Status:** Accepted

In 2017, 39 files used Hazelcast types directly. Newer Hazelcast versions also moved locks and latches (the CP subsystem) to the Enterprise edition.

**Decision:** Business code uses `JobStore`/`NodeRegistry` on `AsyncMap`. Hazelcast appears only in `automately.server.cluster`, and an ArchUnit rule enforces that.
**Cost:** No Hazelcast queries or predicates. Listing jobs scans the whole map, which is fine for the spike; tenant-indexed queries will need a real store such as Postgres.

### D-005 — Tenant header as a temporary stand-in for auth
**Date:** 2026-10-06 · **Status:** Temporary, to be replaced in phase 4

**Decision:** The `X-Automately-Tenant` header scopes every job request, so tenant isolation can be tested now.
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

**Decision:** The author and organisation for Automately is **Adam & Lynn Incorporated** (tony@adamnlynn.com). It's recorded in the POM, the README and the repository's git config.

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
**Follow-up:** Phase 5 should count discarded late results as a metric (`automately_attempt_results_discarded_total`), which is where this information is actually useful.

### D-017 — No CI for now
**Date:** 2026-10-06 · **Status:** Accepted (Tony)

**Decision:** The GitHub Actions workflow is removed. The repo is private and worked on locally, and `./mvnw verify` (unit tests plus `DockerClusterIT`) is the gate. The crash-report check that CI ran is now a manual step: `find . -name 'hs_err_pid*.log'`.
**Cost:** Nothing runs automatically on push. Bring the workflow back from git history (commit `31ce7c7`) when the repo gets collaborators or goes public.

### D-018 — Name and rights
**Date:** 2026-10-06 · **Status:** Recorded, as stated by Tony

The acquired product was renamed **Executory** after the acquisition. Tony was given the rights to the pre-acquisition open-source Automately code, so using the Automately name and building on that code here is fine and doesn't overlap with what the product became. This closes the question raised in the revival plan.

### D-019 — An event loop per isolate, on a dedicated executor
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Each execution runs an event loop on its isolate's thread. It pumps microtasks, fires timers (`setTimeout`/`setInterval`, at most 10 000 pending), and runs tasks posted from other threads (stop requests now, incoming requests and messages in phase 3). Kills and timeouts wake an idle loop. Isolates run on a Vert.x shared worker executor (`automately-isolates`) sized to the node's capacity, with execute-time warnings disabled.
**Why:** Services need to stay alive and react over time, and phase 3's handlers need the same loop. A long-running isolate on the default worker pool would trigger blocked-thread warnings and take threads from other blocking work.
**Cost:** One thread per running isolate. Isolates are thread-affine, so virtual threads aren't an obvious substitute.

### D-020 — Durable cancellation and a `CANCELLED` status
**Date:** 2026-10-06 · **Status:** Accepted. Updates D-016

**Decision:** A cancel is stored on the job (`cancelRequested`) with compare-and-set, then sent to the node as an optimisation. The node also checks the flag on every heartbeat, and the reaper ends an orphaned job with a pending cancel as `CANCELLED` instead of retrying it. Running jobs get a graceful stop: `ctx.stopRequested` resolves, then after `AUTOMATELY_STOP_GRACE_MS` (default 5 s) the isolate is terminated. Cancelled attempts, including zombies cancelled by their own node, report `CANCELLED` rather than `FAILED` (that part of D-016 is superseded; their results are still discarded).
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
**Cost:** Scripts must follow redirects themselves (and each hop is checked). `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true` exists for local development only.

### D-027 — `kv` expiry is decided by the entry, not the map
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Each entry stores `expiresAt`, and every read checks it. The map TTL is set a few seconds longer, purely for cleanup.
**Why:** Hazelcast expires entries up to about 1 s early (D-013), which would break short TTLs.
**Cost:** `list` scans the map, and there are no queries (2017's DataBus had predicates). Both are waiting for the durable store.

### D-028 — Automatic hosts and verified custom domains
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Every service is reachable at `<service>--<tenant>.<AUTOMATELY_INGRESS_DOMAIN>`, so tenant and service names may no longer contain `--`. Custom domains are claimed per service and route only after a DNS TXT record `_automately.<domain>` = `automately-verify=<token>` is found (2017's anti-hijacking check). Unverified claims can be taken over after 24 h, and deleting a service releases its domains.
**Why:** A single DNS label per service works with one wildcard certificate later. Verification stops one tenant capturing another's traffic.

### D-029 — Ingress forwards over the event bus, routing by authority
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** Every node runs an ingress (`AUTOMATELY_INGRESS_PORT`). It routes by the request authority (the Host header on HTTP/1.1, `:authority` on HTTP/2), finds the service's current run, and forwards the request to that node over the event bus. The node hands it to the isolate's `ctx.serve` handler.
**Cost:** Bodies are limited to 1 MB each way (they travel as event-bus messages) and there's no streaming. That's acceptable for APIs and webhooks; large uploads and downloads would need a direct proxy later.

### D-030 — The cluster is the database: Apache Ignite replaces Hazelcast
**Date:** 2026-10-06 · **Status:** Accepted (Tony chose a built-in distributed database over an external one, and Ignite over Infinispan or a home-grown Raft store). Supersedes the Hazelcast parts of D-004 and D-013

**Decision:** Each node runs one embedded Apache Ignite 2.18, used both as the Vert.x cluster manager (`vertx-ignite`) and as Automately's durable, replicated store.
- Jobs, job state, services, domains and `kv` live in tables in a durable data region, on disk under `AUTOMATELY_DATA_DIR` with a write-ahead log.
- Each table keeps `AUTOMATELY_DATA_BACKUPS` copies on other nodes, uses compare-and-set transitions, and has SQL indexes (Calcite engine) for listings.
- Ignite types stay in `automately-cluster-ignite`, enforced by ArchUnit; core still sees only its store interfaces, which now have shared contract tests run against both implementations.
- With no data directory, Ignite keeps everything in memory (development); a single non-clustered node still uses the in-memory SharedData stores.

**Evidence:** a spike and tests showed compare-and-set across nodes; a two-node and a three-node **full-cluster restart from disk** with everything intact (`DurabilityTest`, `FullRestartTest`, and `DockerClusterIT` stopping and starting every container); and losing a node with no data lost (1 backup).
**Why not Ignite 3:** there's no Vert.x cluster manager for it yet, and 2.18 is the mature line. The store interfaces keep a later move open.
**Cost:** a much larger jar (Ignite and Calcite), extra JVM flags (`ignite.jvm.args`, also in the Dockerfile), and memory for Ignite's off-heap regions (the container heap is now 40 %).

### D-031 — Activate only when no data can be lost
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** the first version forced the cluster active as soon as the first node restarted. Ignite then treated partitions held by not-yet-returned nodes as lost ("all partition owners have left").
**Decision:**
- Whether a node is starting for the **first time** is decided **before** Ignite starts, from whether its data directory already holds a database.
- Only a first start may activate the cluster, and a brand-new durable cluster waits for `AUTOMATELY_CLUSTER_SIZE` nodes first (D-035).
- A restarting node never activates the cluster itself. It waits until every baseline (data-holding) node is back, and Ignite activates by itself.
- If some never return, the cluster activates without them after `AUTOMATELY_BASELINE_ADJUST_MS`, but **only if no more are missing than there are backups**; otherwise it keeps waiting and logs why. Baseline auto-adjust (on since the first start) then drops the missing nodes.

**Also found:**
- A restarting node that saw no baseline yet called "activate" while Ignite was already auto-activating. The interrupted write-ahead-log restore made Ignite **halt the JVM** (its protective failure handler).
- Ignite forbids setting the baseline by hand while auto-adjust is on, and forbids changing it on an inactive cluster.

### D-032 — Vert.x's maps are replicated and ignore partition loss
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** after a `SIGKILL` in Docker, every read of the node registry failed with "partition has been lost" and scheduling stopped. Caches Vert.x creates by name had Ignite's default of **zero backups**.
**Decision:** Automately supplies the cache templates `vertx-ignite` normally ships, but stricter. `__vertx.*` and every other name-created cache are REPLICATED, in memory, FULL_SYNC, with partition loss IGNORE.
**Why:** that data (event-bus subscriptions, node info, the node registry) is small and re-announced every heartbeat; staying available matters more than any single entry. Durable tables configure themselves explicitly.

### D-033 — Retry operations that fail only because the topology changed
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** during failover, Ignite queries failed with "Remote node has left topology", and the API returned 500 mid-failover.
**Decision:** Ignite table operations retry up to 10 times with growing pauses when the failure is a topology change, and the node registry retries its reads. These are reads, idempotent puts, or compare-and-set, which re-reads; anything else fails at once.

### D-034 — Retention of finished jobs
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:** The leader deletes finished jobs older than `AUTOMATELY_JOB_RETENTION_DAYS` (default 7), together with their checkpoints and steps, in batches every 10 minutes. `GET /v1/jobs` gained `?limit` (default 100, at most 1 000).
**Why:** with durable storage, history would otherwise grow until the disk fills.

### D-035 — A new durable cluster waits for its initial size before activating
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** on a fresh three-node durable cluster, the first node to start activated alone, so the baseline was one node. Writes in the first seconds had no replicas, and stopping a node in that window lost data ("all partition owners have left").
**Decision:** `AUTOMATELY_CLUSTER_SIZE` (default 1), similar to Consul's `bootstrap_expect`. A **brand-new durable** cluster activates only once that many server nodes are present, so its baseline, and therefore replication, includes them from the first write. It has no effect on restarts (D-031) or on in-memory clusters. The compose file and the Docker test set it to 3.

### D-036 — The ingress proxies to the serving node over HTTP; the event bus is out of the data path
**Date:** 2026-10-06 · **Status:** Accepted. Supersedes D-029's forwarding (and its 1 MB cap)

**Decision:** Every node runs an internal HTTP endpoint (`AUTOMATELY_INTERNAL_PORT`, default 8090) and advertises it (`AUTOMATELY_ADVERTISE_HOST:port`) in the node registry. The ingress proxies each request as a stream to the node running the service: the request body is forwarded with backpressure and the response body is streamed back, with no size cap on responses. Internal requests must carry a cluster secret, generated once and agreed through shared data, so the port is useless to anyone outside the cluster.
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
- The ingress has its own idle watchdog (`AUTOMATELY_INGRESS_IDLE_TIMEOUT_MS`, default 60 s) that counts bytes moving in **either** direction.
- An early answer sends `Connection: close` and closes the connection.
- Errors are answered before the upstream is reset.
- Request bodies are capped by `AUTOMATELY_MAX_REQUEST_BYTES` (default 1 GiB), checked from `Content-Length` and while streaming.
- In scripts, response writes wait for the client to drain, and a client disconnecting ends the generator.

### D-038 — Storage admission control: refuse writes before memory or disk runs out
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** an in-memory node with a small data region took a large upload, hit `IgniteOutOfMemoryException`, and Ignite's failure handler **halted the JVM**. A 64 MB region ran out while just creating the tables, because each partition carries fixed metadata. Tony: "i don't want to be able to crash the jvm with a fucking file upload".
**Decision:**
- **Admission control.** A `StorageCapacity` check runs before every write that grows storage: each file creation, each 1 MB file chunk, and `kv.set`/`kv.increment`. When it fails, the write is refused with `insufficient_storage` (HTTP 507, or a script error), the partial upload is discarded, and the node keeps running.
  - Durable nodes: refused when the data directory's disk has less than `AUTOMATELY_MIN_FREE_DISK_MB` (default 1024) free. Ignite keeps a bounded hot set in memory and pages to disk, so disk is the real limit.
  - In-memory Ignite nodes: refused when the data region is more than 80 % full.
  - Non-clustered nodes (SharedData stores, on the heap): refused when the heap is more than 80 % full.
- **A safety net behind it.** In-memory Ignite regions use page eviction (`RANDOM_2_LRU` at 90 %), so a burst that gets past the check costs evicted entries rather than the process. This is a last resort, and it can drop data, so the node warns about it at start. Durable regions don't need it.
- **Partitions: 256** instead of Ignite's 1 024, and a **minimum data region of 128 MB**, checked at start-up with a clear error. Small regions fit the fixed per-partition overhead, and 256 is plenty below about 100 nodes.
- **Per-tenant quota.** `AUTOMATELY_TENANT_STORAGE_MB` (default 1024) caps a tenant's files. It is checked up front from `Content-Length` when the size is known, and while streaming otherwise.
**Why not stage uploads in a temp file?** Uploads already stream in 1 MB chunks with backpressure, so memory per upload is bounded. Durable storage is already disk-backed, so a temp file would only add a second copy and a second disk to fill. What actually crashed the node was running out of storage, and refusing before that point fixes it in every mode.
**Cost:** the check is a point-in-time estimate, so concurrent uploads can overshoot by about 1 MB each. That headroom is what the 80 % and 90 % thresholds leave. `kv` doesn't count against the tenant quota yet, only against cluster capacity.

### D-039 — Early answers drain the request body instead of closing at once
**Date:** 2026-10-06 · **Status:** Accepted. Supersedes D-037's "an early answer closes the connection"

**Evidence:** a refused upload (413 over quota) closed the connection while the client was still sending, so the client saw "Connection was closed" instead of the 413 and the reason for it.
**Decision:** When the server answers before reading the body (413, 404 for an unknown host, 507), it reads and discards the rest of the body ("lingering close") so the client receives the answer, and the connection stays usable. A body over 64 MB, by `Content-Length` or as counted, isn't worth the bandwidth, so the server sends `Connection: close` and closes. The REST upload also checks a declared `Content-Length` against the quota **before** reading anything, so a well-behaved client gets its 413 immediately.

### D-040 — Files: chunked in the cluster store, a new id per write, Java serves the bytes
**Date:** 2026-10-06 · **Status:** Accepted

**Decision:**
- **Chunks.** A file is split into 1 MB chunks stored in a replicated cache (`FILE_CHUNKS`, key `fileId:index`), with its metadata row in `FILES` (size, content type, SHA-256 ETag, chunk count). Files are replicated like everything else (`AUTOMATELY_DATA_BACKUPS`), survive full restarts on durable nodes, and any node can stream any file.
- **Replacing is atomic.** Each write gets a new file id. Chunks are written first, then the metadata row is swapped in one put, so readers see the old file or the new one, never a mix.
- **Garbage collection.** A file id that is no longer current is recorded in `FILE_GARBAGE` with a deletion time: 10 minutes after it was replaced or deleted (so reads already in progress finish), or 6 hours after an upload that never finished started. The leader deletes due chunks every minute.
- **Serving.** `files.stream(path)` returned from a handler is served by Java (`FileResponder`): chunks go from the store to the socket and never enter the isolate. It supports `Range` (single ranges → 206, 416), `ETag`/`If-None-Match` → 304, `HEAD`, and an exact `Content-Length`, so video seeking works.
- **Paths.** Relative, at most 1 024 bytes, no `.`/`..`/empty segments, no backslashes or control characters. Tenant-scoped like `kv`.
**Why not a blob store such as S3 or MinIO?** The goal is "the cluster is the database", with nothing else to run. An S3-compatible `FileStore` can be added behind the same interface later.
**Cost:** Without a data directory, files live in memory and count against the data region. Large media libraries want durable nodes.

### D-041 — Durable tables and file chunks use FULL_SYNC writes
**Date:** 2026-10-06 · **Status:** Accepted

**Evidence:** in one full-verify run, node A wrote a file and node B got "file data is missing" reading it straight away. Ignite's default `PRIMARY_SYNC` completes a write once the primary copy has it, and reads may be served from a backup, so a read on another node can come before the backup is updated.
**Decision:** Every Automately table and the `FILE_CHUNKS` cache use `FULL_SYNC`: a write completes only when every copy has it. Vert.x's own caches already did (D-032).
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
- `AUTOMATELY_TENANT_STORAGE_MB` covers everything a tenant stores. A kv entry counts as the UTF-8 bytes of its namespace, key and JSON value (`KvStore.entrySize`); stores record it with the entry and sum it by tenant (an SQL `SUM` on Ignite).
- One `TenantQuota` applies it, and the stores no longer know about quotas: `FileStore.create` takes the most the upload may store.
- **Uploads** ask for a fresh count once, are refused up front if `Content-Length` doesn't fit, and are cut off when they pass what was left.
- **kv writes** are checked against a count at most 2 s old, plus what this node admitted since, so a write doesn't sum the tenant's storage each time. When that quick check says no, it's redone from a fresh count against what the write really adds over the entry it replaces. A full tenant can still update, shrink or delete what it has.
**Cost:** writes made on other nodes in the last 2 s aren't in the quick count, so a tenant can overshoot by what it writes cluster-wide in that window. This is small next to a quota measured in megabytes, and it can't run a node out of storage: admission control (D-038) is separate and always checks.

### D-044 — The first credential is an admin token from the environment, or generated once
**Date:** 2026-10-06 · **Status:** Accepted (Tony)

**Decision:** `AUTOMATELY_ADMIN_TOKEN`, if set, is the admin token on every node. Otherwise the first node of a new cluster generates one, stores only its hash in the cluster, and logs it once. The admin token manages tenants and tokens, and nothing else: it can't read tenant data. To act as a tenant, mint that tenant a token.
**Why:** something has to exist before any token does. An environment variable suits deployments, and a generated, logged-once token suits a first `docker run`. Keeping admin out of tenant data means a leaked admin token can't silently read everything; it can only mint tokens, which is visible.

### D-045 — Scoped, revocable bearer tokens replace the tenant header
**Date:** 2026-10-06 · **Status:** Accepted. Replaces the `X-Automately-Tenant` stand-in

**Decision:**
- Tokens look like `atly_` + 32 random bytes (base62) + a checksum, and are sent as `Authorization: Bearer`.
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

**Decision:** Every node serves MCP over Streamable HTTP at `/mcp`, with the same tokens and scopes as the REST API. `automately mcp` bridges stdio for clients that only launch local processes. The protocol is implemented directly on Vert.x.
**Why:** agents that support remote MCP servers need nothing installed, and the rest still work through the CLI. One auth model covers both. The Java SDK's transports assume Servlet or Reactor, which this project doesn't use.

### D-048 — The CLI: Java and picocli, compiled to native binaries
**Date:** 2026-10-06 · **Status:** Accepted, with D-049 (Tony asked for native Windows, Linux and macOS builds)

**Proposal:** a new `automately-cli` module using picocli, the JDK HTTP client and a small JSON library. It is compiled with GraalVM native-image into one binary per platform, so no JDK is needed on machines that talk to the cluster, and published as GitHub release assets. npm packages wrapping the binaries, as esbuild does, could follow.
**Constraint:** native-image can't cross-compile. Linux x64 can be built here in a GraalVM container. macOS, Windows and arm64 need those machines, or GitHub Actions runners in a release-only workflow (not CI, so D-017 stands).

### D-049 — The CLI installs with one line, on Windows, Linux and macOS
**Date:** 2026-10-06 · **Status:** Accepted (Tony); hosting settled 2026-10-07, see the update below. Confirms D-048

**Decision:**
- One native binary per platform: Linux x64 (static, musl) and arm64, macOS arm64 and x64, Windows x64.
- They're published with `SHA256SUMS` as release assets, and installed with `curl -fsSL …/install.sh | sh` or, on Windows, `irm …/install.ps1 | iex`. The scripts detect the platform, verify the checksum, install to a user directory and set up `PATH`.
- `automately upgrade` reruns the installer.
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
- A token bucket per tenant, holding a minute's worth (`AUTOMATELY_RATE_LIMIT_PER_MINUTE`, default 1200; 0 turns limiting off). It refills continuously.
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
- **Credentials.** The login is saved with owner-only permissions. `AUTOMATELY_URL` and `AUTOMATELY_TOKEN` override it, for CI.
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
- Public downloads come from `adamnlynn/automately-v2`'s own GitHub Releases, once Tony makes the repository public. The installers and `automately upgrade` already default to `https://github.com/adamnlynn/automately-v2/releases/latest/download`, so nothing changes at that point.
**Until then:** a tag still builds and publishes a release, but its assets need a GitHub login to download. Use `gh release download`, or point `AUTOMATELY_INSTALL_URL` at a mirror.

### D-056 — File storage backends: metadata in the cluster, bytes in a pluggable BlobStore
**Date:** 2026-10-07 · **Status:** Accepted (Tony). Extends D-040

**Decision:**
- **What stays in the cluster database:** paths, versions, the atomic swap to a new version, garbage collection and quotas, whatever holds the bytes.
- **The bytes go to a `BlobStore`**, chosen with `AUTOMATELY_FILES_BACKEND`:
  - `cluster` (default): 1 MB chunks in the replicated store, as before
  - `disk`: one file per blob under `AUTOMATELY_FILES_DIR`, written to `.part`, flushed to disk and renamed, with free space checked as it writes. In a cluster, every node must mount the same folder.
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
- **Secrets are write-only.** They're sealed with AES-256-GCM using a key derived from `AUTOMATELY_SECRETS_KEY`, with the tenant and provider id as associated data, so a sealed secret can't be moved to another provider. The API reports only `hasSecret`.
- **A provider is checked against its backend before it's saved.** A wrong key, a missing bucket or a private address is refused with the reason.
- **Routes** (`/v1/storage/routes`): the longest matching prefix wins, then the tenant's default; `default` means the cluster's own storage.
- **The admin's policy** (`/v1/tenants/{name}/storage-policy`): which types are allowed, the disk allotment, and whether endpoints may be private. Nothing is allowed by default. Turning a type off refuses new writes there (403 `storage_disabled`) and keeps reads working.
- **The room an upload gets depends on where it goes:**
  - on the cluster's own storage, the tenant quota (D-043)
  - in the tenant's bucket, unlimited
  - on a disk provider, the allotment, counted across all the tenant's disk providers
  - a declared size that won't fit is refused up front, naming the limit
- **Disk providers live at `<AUTOMATELY_DISK_ROOT>/<tenant>/<name>/`**, a path chosen by the server, never by the tenant. Every node writes a marker into the root and checks the other live nodes' markers. Disk providers only work while all of them are visible. Redundancy across nodes is the shared storage's job (NFS, Ceph); local disks on several nodes can't provide it, and the check turns disk providers off rather than serving files only one node can see.
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
- **Automately's own meters:**
  - jobs submitted, queue wait, run time by status, running and capacity
  - isolate cold start
  - service runs and restarts
  - 429s by tenant
  - files stored by backend kind, and uploads refused by reason
  - live nodes, leader, and the Ignite data region's use
- **Recorded without plumbing.** The registry sits in each node's Vert.x local map, so any component with the `Vertx` records. Several nodes in one JVM (the tests) stay apart.
- **Low-cardinality labels:** no job ids, no client addresses (`remote`), no event-bus addresses. Tenants appear only on the rate-limit counter, where the tenant is the point.
- **The `/metrics` port is separate** (`AUTOMATELY_METRICS_PORT`, default 9090, `off` to disable), meant for a private network like the internal port. It's kept off the API so it doesn't need a token.
- **`/ready`**, next to `/health`, answers 503 with reasons until the node is among the live nodes and the cluster is active. It's for load balancers and orchestrators.
- **Logs:** Logback replaces slf4j-simple. `AUTOMATELY_LOG_FORMAT=json` uses Logback's own JSON encoder: one object per line, with SLF4J key-value pairs (`jobId`, `tenant`, `status`, `durationMs` on "job ended") as fields. In JSON mode Ignite logs through the logger (`IGNITE_QUIET=false`), so its console lines don't break the format.
**Deferred:** OpenTelemetry tracing. The plan made it optional, and metrics, readiness and structured logs cover operating the cluster. It can be added with `vertx-opentelemetry` behind `AUTOMATELY_OTLP_ENDPOINT` later.

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
- **Core:** one Java interface per API in `automately.core.runtime.api` (`KvApi`, `BusApi`, `FilesApi`, `FetchApi`, `HttpApi`, gathered in `HostApis`), with JSON, `Buffer` and `Future` in their signatures and no script-engine types. The node implements each (`NodeKvApi`, …), and they share one `HostScope` that holds the tenant (from the job, never the script) and the listeners to undo. `HostArgs` converts loosely typed arguments with the same errors in every language.
- **Javet:** one binding per API (`KvBinding`, `BusBinding`, `FilesBinding`, `FetchBinding`, `HttpBinding`), each bound with `V8ValueObject.bind()` under the hidden host object.
  - **What `bind()` exposes:** only `@V8Function` methods, never `getClass` or reflection. Javet's proxy converter, which would expose Java objects, is not used.
  - **Promises:** a binding returns a promise created in Java (`createV8ValuePromise`). `HostBridge` settles it on the isolate's event loop when the future completes; a failure becomes a real `Error` with `name` and `code`.
  - **Value conversion:** `JsValues`, through V8's own `JSON`, whose functions are captured before the script runs, so a script replacing `JSON.parse` only affects itself.
- **JavaScript:** 13 small files in `automately-runtime-javet/src/main/resources/automately/runtime/javet/js/`, only for what has to be idiomatic JavaScript: freezing globals, kv namespaces, `fetch`'s Response, streams as async iterators, serving, `ctx`. `JavetScriptRuntime.PRELUDE_FILES` lists them in order, and they run in one closure.

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
  - **CLI and MCP:** `automately queues …`, and the tools `queue_push`, `queue_stats`, `queue_dead` and `queue_retry`.
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
- **Signature:** `Automately-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`, the scheme receivers already know from other webhook senders. Each delivery also carries its id (`Automately-Delivery`) and attempt number (`Automately-Attempt`).
- **Secrets:** one per tenant, created on first use. They're stored in the replicated kv store under the system tenant, which no token can reach. Creation uses a new atomic `KvStore.putIfAbsent`, so racing nodes agree on one secret. They're sealed with `AUTOMATELY_SECRETS_KEY` when it's set (D-057); if that key changes, the secret is replaced as a rotation would. Read or rotate it with `GET /v1/callbacks/secret` and `POST …/rotate` (scope `tokens:write`), or `automately callbacks secret [--rotate]`.
- **Progress is recorded on the job:** `callback: {url, data, status: PENDING | DELIVERED | FAILED, attempts, deliveredAt, lastError}`.

**Why the cluster secret isn't used to derive tenant secrets:** it lives in the cluster manager's shared map, which isn't kept across a full restart, so the derived secrets would change under receivers.

**Tests:**
- `CallbacksTest`, a node posting to a local receiver: the signature and `data`, a retry after a 500, `ctx.setCallback`, failed jobs calling back, rotation, and bad callbacks refused at submission.
- `CallbackDeliveryTest`: every way a job ends queues exactly one delivery; a metadata address fails once and isn't retried; secrets are created once and sealed.
- The kv contract's `putIfAbsent`, and `ContractTest` (59 operations).

### D-063 — Scripts start and follow jobs: `jobs.run`, `result()`, `events()`, `stop()`
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** In 2017 a script could start a job with `Automately.startJob(script, config, callback, waitForJob)`, read one (`getJob`) and stop one (`stopJob`). `waitForJob` blocked the script's thread on a latch until the child finished. The rewrite had no way for a job to start another.

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

**To make the dashboard tell the same story,** queues and callbacks got counters: `automately_queue_messages_total{outcome}` and `automately_callbacks_deliveries_total{outcome}`. They have a dashboard row of their own, and `DashboardTest` produces both.

### D-065 — Ignite's client and REST connectors are off, and Ignite binds to one address
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** While designing mutual TLS for the cluster (docs/design/cluster-security.md), a probe of a running node found two listeners nothing in Automately uses, both Ignite defaults, unauthenticated, on every interface:
- **10800:** the thin-client/JDBC/ODBC connector. Anything that could reach it could run SQL against every table, every tenant's data and the tokens table included.
- **11211:** the binary REST command protocol.

Docker didn't publish them to the host, but every container on the network could reach them, and they weren't in SECURITY.md.

**Decision:**
- **Both off:** `IgniteConfiguration.setClientConnectorConfiguration(null)` and `setConnectorConfiguration(null)`.
- **One address:** Ignite listens on `AUTOMATELY_CLUSTER_BIND`, defaulting to an explicitly set `AUTOMATELY_ADVERTISE_HOST`. Without either, it still listens on every interface, as single-machine development does, and the node logs a warning. The compose file and DockerClusterIT bind each node to its own hostname.
- **`ListeningPortsTest`:** it reads the JVM's own listening sockets from `/proc` and asserts a clustered node opens only the API, ingress, internal, metrics, discovery and communication ports plus the event bus. Any new listener fails the build until it's added on purpose. It was checked to fail, naming 10800 and 11211, with the connectors turned back on.

**Not yet:** authentication and encryption between nodes. That's the rest of the design (D-066, D-067): a cluster CA whose key stays on the operator's machine, and node bundles for one-line joins.

### D-066 — Core nodes authenticate each other with certificates from the cluster's own CA
**Date:** 2026-10-07 · **Status:** Accepted (design: docs/design/cluster-security.md)

**Context:** Ignite discovery and communication, the clustered event bus and the internal endpoint were unauthenticated and unencrypted. Anything that could reach the discovery port could join the cluster and receive replicated data. D-065 closed Ignite's unused ports; this closes the rest.

**Decision:**
- **The CA:** each cluster has its own CA. EC P-256 throughout; the CA lasts 10 years and node certificates 3. **Its private key stays on the operator's machine** (Tony): `automately cluster init` writes `ca.key`, and the cluster never stores it.
- **A node certificate** names the node (common name and DNS name) and its addresses, and is good for TLS server and client use.
- **TLS 1.3 with a node certificate required on both sides** for:
  - **Ignite discovery and communication:** `IgniteConfiguration.setSslContextFactory`. The context is wrapped so every server-side socket and engine requires the peer's certificate, whatever Ignite asks for.
  - **The clustered event bus:** `EventBusOptions` with PEM key and trust and `ClientAuth.REQUIRED`.
  - **The internal endpoint:** HTTPS with required client certificates; the ingress presents its node certificate.
- **D-036's shared secret is retired** when certificates are on (Tony).
- **On by default for clustered nodes** (`AUTOMATELY_CLUSTER_TLS=required`). A node without certificates refuses to start and says how to get them. `off` is for development and logs a warning at every start.
- **Revocation by node id:**
  - **How it works:** a certificate issued before a node's revocation is refused, and one issued after (the node was invited again) is accepted.
  - **Storage:** revocations are kept in the cluster (the system tenant's kv), broadcast to every node, and saved by each node in its TLS directory, so a node refuses revoked peers even before it has rejoined.
  - **A node in the cluster is forced out** through Ignite discovery's `failNode`, which isn't up to the node, unlike `stopNodes`; a well-behaved node also stops itself on the broadcast.
  - **The event bus** trusts the CA but doesn't check revocations: Vert.x copies event-bus options in a way that loses custom trust managers. Ignite discovery does check, so a revoked node can't rejoin, and forcing it out takes its connections with it.
- **A join timeout** (`AUTOMATELY_CLUSTER_JOIN_TIMEOUT_MS`, 60 s): a node whose certificate the members refuse fails its start instead of retrying forever, which is Ignite's default.
- **Exact issue times:** certificate dates have one-second precision, which made a node invited again within the second of its revocation look revoked. Found by the full test run. Our serial numbers carry the issue time in milliseconds in their high bits, and revocation compares that. Certificates from another CA fall back to their start date.
- **What a refused node does:**
  - **When Ignite reports the TLS refusal:** the node's start fails.
  - **When Ignite reads the refusal as an unreachable address:** the node may start alone, as a one-node cluster of its own.

  Either way it isn't a member: it holds no data, and members refuse its certificate.
- **Following revocations never holds up a start.** Found by the Docker test: reading the list with SQL as part of startup failed on a freshly forming cluster, because Ignite cancels queries while partitions move. The list now loads in the background, retrying, and every minute after (in case a broadcast was missed), with each node's saved copy covering the meantime. Queries cancelled that way (`QueryCancelledException`) are now retried everywhere, like the other topology errors (D-033).

**Tests:**
- **`automately-tls`:** real handshakes. A member gets in; no certificate, a foreign CA and a revoked node are refused; a node invited again gets in.
- **`ClusterSecurityTest`:**
  - no certificates → refused, with the fix in the message
  - a join line for another node → refused
  - the internal endpoint doesn't answer plain HTTP
  - a foreign CA and TLS off can't join
  - a revoked node's certificate is refused
  - invited again, it rejoins
- **Every in-JVM cluster test** now runs with TLS required (`TestTls`).
- **`DockerClusterIT`:** its nodes join with `AUTOMATELY_JOIN` bundles, and **a live node is revoked and forced out**, its process stopping.

### D-067 — Joining a node is one line: a per-node bundle signed on the operator's machine, never the CA key
**Date:** 2026-10-07 · **Status:** Accepted

**Decision:**
- **Creating a cluster:** `automately cluster init --nodes …` writes `ca.key`, `ca.crt`, and one `<node>.join` line per node.
- **Adding a node:** `automately node invite node4 --host … --ca ./ca.key` signs node4's certificate **locally** and prints `AUTOMATELY_JOIN=atly_nodebundle_…`: node4's key, its certificate and the CA certificate, gzipped PEM in base64url. The new node installs it in its TLS directory and starts. Tony asked for a one-liner with the key base64-encoded; it carries **the node's** key, never the CA's, because putting `ca.key` in a one-liner would hand the master key to every node and every shell history.
- **The bundle is a secret for one node:**
  - it can't create other nodes
  - it's bound to the node's name and addresses
  - a line for one node can't start another
- **The strict variant:** `automately-server --prepare node4` keeps the key on the new machine and prints its public key, and `node invite --key …` returns a certificate-only line (`atly_nodecert_…`).
- **Renewing is inviting again.** Nodes warn from 90 days before expiry.
- **Compose:** a one-shot `tls-init` service makes the CA and certificates in a volume on the first start (`automately.tls.ClusterInit`), so `docker compose up`, the demo and the benchmarks stay one command. That's development only, since `ca.key` sits in the volume.

**Tests:** CLI tests for `cluster init`, `node invite` (bundle and `--key`) and `node revoke`. DockerClusterIT joins every node with a bundle.

### D-068 — Tenants' secrets: write-only through the API, readable by scripts only with `secrets:use`
**Date:** 2026-10-07 · **Status:** Accepted

**Context:** Security groundwork, step 0, part 2. A service's config was plain JSON on its record, readable by anyone with `services:read`, and links, containers and apps will need credentials too. The pieces already existed: D-057 seals tenants' storage keys with `AUTOMATELY_SECRETS_KEY`, and D-062 keeps per-tenant callback secrets under the system tenant.

**Decision:**
- **Named secrets per tenant:** `PUT /v1/secrets/{name}` with `{value}` (at most 64 KiB), `GET /v1/secrets`, `GET` and `DELETE /v1/secrets/{name}`. At most 500 per tenant.
  - **Values go in and never come out:** the API and the CLI show a secret's name, version, timestamps and the token that last set it.
  - **CLI:** `automately secrets set NAME` reads the value from standard input, or hidden at a terminal, to keep it out of shell history; `secrets list`; `secrets rm`.
- **Scripts read them with `secrets.get(name)`**, which resolves to the value, or null if there's none.
- **Three scopes:** `secrets:read` (names), `secrets:write` (set and delete) and **`secrets:use`** (scripts may read values).
  - **Why `secrets:use`:** without it, any token that can submit a job (`jobs:write`) could run `secrets.get` and print every secret. That's the token an MCP agent typically has.
  - **Recorded on the job:** the job records `secrets: true` only if the token that submitted it had `secrets:use`.
  - **A service** records whether the token that last defined it had it; changing that is a new revision.
  - **Jobs started from scripts** (`jobs.run`, D-063) inherit their parent's flag, so a child never has more than its parent.
  - **Other scripts** get a `forbidden` error.
  - **Checked at the node:** it reads the flag from the job's durable record on the first `secrets.get`, rather than trusting anything the script passes.
- **Storage:** the replicated kv store under the system tenant, one namespace per tenant, like D-062. Each value is sealed with AES-256-GCM (D-057's `SecretBox`), with the tenant and name as associated data, so a sealed value copied to another name or tenant doesn't open.
- **The secrets key is required.** Without `AUTOMATELY_SECRETS_KEY` the API answers 409 `secrets_unavailable` and scripts get `unavailable`. A store called "secrets" that kept values in the clear would mislead. D-062 stores callback secrets in the clear without a key; this doesn't.
- **Deleting a tenant** deletes its secrets.
- **Not for agents:** there are no MCP tools for secrets, so values never pass through an agent; a script that may read them still can.

**Not yet (recorded for later):**
- **References** like `env: {DB_URL: {secret: "db-url"}}`, resolved when a link or container starts, with an audit of which secrets leave the cluster and to which device. They come with links and containers.
- **Rotating `AUTOMATELY_SECRETS_KEY`:** changing it makes stored secrets unreadable, as with D-057. Re-sealing under a new key, with the old one still accepted for a while, is a separate change.
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

**Context:** Security groundwork, step 0, part 3. The installers checked each binary against `SHA256SUMS`, but the sums came from the same place as the binary: whoever could change one could change the other (a compromised release workflow, a GitHub token with write access, a mirror). And `automately upgrade` piped the installer from the network straight into a shell.

**Decision:**
- **One signature per release, over `SHA256SUMS`,** which now lists the installers as well as the binaries. It's ECDSA P-256 with SHA-256, in the DER form `openssl dgst -sha256 -sign` writes, and published as `SHA256SUMS.sig`.
- **The key stays with the maintainer, like the cluster CA (D-066).**
  - **Creating it:** `scripts/release-key.sh` writes the private key to `~/.config/automately/release-signing.key`, owner-only and encrypted with a passphrase. It also writes the public key to `install/automately-release.pem` and copies it into `install.sh` and `install.ps1`.
  - **Signing:** the release workflow now only builds a **draft**. `scripts/sign-release.sh TAG` then downloads the draft and checks every asset against `SHA256SUMS`. It also checks that the installers carry this key. It then signs, uploads `SHA256SUMS.sig` and publishes the release.
  - **What that protects:** the key never reaches GitHub, so neither the workflow nor anyone with a GitHub token can publish a release the installers accept, and `releases/latest` never serves an unsigned build.
- **Everything that installs checks the signature first,** and refuses an unsigned or wrongly signed release:
  - **`install.sh` uses `openssl`.** That works with OpenSSL and with macOS's LibreSSL.
  - **`install.ps1` uses .NET's own ECDSA.** This works on Windows PowerShell 5.1 and on PowerShell 7.
  - **`automately upgrade` does it in-process** with the key built into the binary (the same `.pem`, bundled at build time), then swaps the file. It no longer runs a downloaded script.
- **Why ECDSA P-256 and not minisign or cosign:** the stock tools on all three systems can check a P-256 signature (`openssl`, .NET, Java), so a one-line install needs nothing extra. Ed25519 would need OpenSSL 3, which macOS doesn't ship, and Windows PowerShell can't check it. Minisign and cosign need their own tool on the user's machine. Anyone can check by hand with `openssl dgst -sha256 -verify automately-release.pem -signature SHA256SUMS.sig SHA256SUMS`.
- **Escape hatches, both explicit:**
  - `AUTOMATELY_RELEASE_PUBLIC_KEY=FILE`: for a mirror that re-signs with its own key, and for the tests.
  - `AUTOMATELY_INSECURE_SKIP_SIGNATURE=1`: trusts HTTPS alone, with a warning. It's for a machine without `openssl`, which the installer otherwise refuses with that advice.

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
  - the key in `install.sh`, `install.ps1` and the CLI matches `install/automately-release.pem`
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
  - **The code:** an `atly_…` secret, shown once and stored only as its hash. It expires in 7 days by default (at most 30), works once, and can name the user it's for.
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
- **Retention:** 90 days (`AUTOMATELY_AUDIT_RETENTION_DAYS`). Every node prunes hourly, and the deletes are idempotent. Deleting a tenant deletes its entries.
- **Never in the way:** a failed write is logged, never turned into a failed request.

**Not yet:** export to S3 or a SIEM, and tamper evidence (hash-chaining entries).

**Tests:** `AuditLogContract` (in memory and on Ignite: paging, the operator view, pruning, forgetting) and `IdentityTest` (what is and isn't recorded, actors, paging, who sees what).

### D-072 — The audit log is tamper-evident; deleted tenants' entries stay until pruned
**Date:** 2026-10-07 · **Status:** Accepted (Tony asked for tamper evidence). Extends D-071, and supersedes its "deleting a tenant deletes its log"

**Decision:**
- **Each node chains its own entries.** An entry carries the node's chain id (`<node>/<process instance>`), the next number in that chain, the previous entry's hash, and its own hash over all of that.
  - **Keying:** with `AUTOMATELY_SECRETS_KEY` the hash is an HMAC-SHA256 under a key derived from it. Someone who can write to the database but doesn't hold the key can't forge a consistent chain. Without a key it's plain SHA-256, which still shows careless edits.
  - **Why per node:** one chain for the whole cluster would make every write wait on every other.
- **Checking:** `GET /v1/admin/audit/verify` (`automately audit verify`) reads every entry and reports any:
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
- **Turning it on:** set `AUTOMATELY_AUDIT_S3_BUCKET`, and the cluster copies new entries there every `AUTOMATELY_AUDIT_EXPORT_INTERVAL_S` (300). The other settings are `AUTOMATELY_AUDIT_S3_ENDPOINT`, `_REGION`, `_PREFIX`, `_PATH_STYLE`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` and `_SESSION_TOKEN`. Each falls back to `AUTOMATELY_S3_*`, then the AWS variables.
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
  - **Renaming:** `PATCH /v1/me {name}` (`automately profile --name`) or, for operators, `PATCH /v1/users/{name} {name}` (`automately users rename`). Everything follows.
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

**Context:** The file store (D-040, D-056, D-057) holds tenants' files well, but as a flat set of paths: no folders, rename, append, scratch space or Node-style `fs`. Modules, Node compatibility, apps and the console's file manager all need those. 2017's Automately had a per-user file system; this brings it back on today's store.

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
