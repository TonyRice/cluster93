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
**Date:** 2026-10-06 · **Status:** Accepted (Tony); release hosting open. Confirms D-048

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

