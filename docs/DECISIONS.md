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
