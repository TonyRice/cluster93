# Phase 2 plan: orphaned jobs, attempts, steps and checkpoints

**Status:** ✅ Done (2026-10-06). 76 tests pass in three consecutive full builds; the chaos test passed 12 of 12 runs; verified in Docker with `docker kill`. See "What changed during implementation" at the end.
**Specs:** [orphaned-jobs.md](orphaned-jobs.md) (design) · [../scripting/job-context.md](../scripting/job-context.md) (script API)

## Goal
When a node dies mid-job, the job is never re-run silently. By default (`fail`) it ends as `ORPHANED`. A job that opted in is retried or resumed on another node without repeating completed steps. A node that only *looked* dead can't overwrite the work of the attempt that replaced it.

Long-running "services" move to **phase 2b**, after this.

## Refinements found while planning against the code
These change or sharpen the design doc and are recorded as decisions D-009 to D-012.

1. **Node incarnations (D-009).** A crashed container restarts with the **same** node id (`node1`). Its old jobs would then look "owned by a live node" and never be reaped. Each process start therefore gets an `instanceId` (a UUID). Jobs record `nodeInstance`, and the registry is checked by instance, not by name.
2. **State stored per attempt (D-010).** Checkpoints and step results are stored under `jobId#attempt`. When attempt N starts as a resume, the node **copies** attempt N−1's state into N's key. A zombie attempt can keep writing to its own key, but nobody reads it any more, so no locking is needed for state. Only transitions of the job record need compare-and-set.
3. **Job transitions use compare-and-set (D-011).** Every change to a job record goes through `JobStore.update(id, fn)`. That reads the record, applies a pure function and writes it back with `AsyncMap.replaceIfPresent(id, old, new)`, retrying on conflict. Transitions check the attempt number, so a zombie's "finished" write is rejected because it's for an attempt that's no longer current.
4. **The reaper needs no lock (D-012).** The live node with the lowest instance id acts as reaper. If two nodes briefly both act, compare-and-set lets only one transition win. This avoids cluster locks, which may depend on Hazelcast's Enterprise CP subsystem.
5. **Queued jobs are always safe to re-dispatch.** A node saves `RUNNING` *before* it starts any script, so a job still `QUEUED` has never executed and can be re-dispatched under any policy. This invariant is enforced by a test.
6. **Host calls from the isolate block the worker thread.** `ctx.step` and `ctx.saveCheckpoint` call Java synchronously. The isolate already runs on a worker thread, so the Java side waits for the durable write and then returns. This keeps the Javet integration simple. It costs one worker thread per running job, which is noted for the virtual-thread work later.

## Work breakdown

### 1. Model (`automately-core`, `job` package)
- `JobStatus`: add `ORPHANED`, a terminal status.
- New `OrphanPolicy` enum (`FAIL`, `RETRY`, `RESUME`) and `Attempt` record (`attempt`, `node`, `nodeInstance`, `startedAt`, `endedAt`, `outcome`).
- `Job`: add `onOrphan`, `maxAttempts`, `attempt`, `nodeInstance`, and `attempts` (history). Transitions become pure functions that check the attempt: `started(attempt, node, instance)`, `finished(attempt, …)`, `orphaned(now)`, `nextAttempt(now)`. A transition for the wrong attempt returns empty, which the caller treats as "superseded".
- `JobStore`: add `update(id, UnaryOperator<Optional<Job>>) → Future<Optional<Job>>`, a compare-and-set loop. `save` is kept for creating jobs only.
- New `JobStateStore` interface plus a `SharedDataJobStateStore`: `load(jobId, attempt)`, `copy(jobId, fromAttempt, toAttempt)`, `saveCheckpoint(jobId, attempt, json)`, `completeStep(jobId, attempt, name, json)`. It enforces the 64 KB per-item and 1 MB total limits.

### 2. Node incarnations (`node` package)
- `NodeInfo` gets an `instanceId`, and registry entries are keyed by instance.
- `NodeVerticle` generates its `instanceId` at start, stamps it on jobs it starts, and supports a **supersede check**: on each heartbeat it re-reads the jobs it's running and cancels any whose current attempt or instance isn't its own.

### 3. Cancellable runtime SPI (`runtime` package)
- `ScriptRuntime.execute` returns a `RunningExecution` with `result()` (a future) and `cancel(reason)`.
- `Execution` gains a `JobContext` (`jobId`, `attempt`, `policy`, `previous`, `checkpoint`, `completedSteps`) and a `StateSink` (`saveCheckpoint` and `completeStep`, each a blocking call that returns or throws `Superseded`/`StateLimit`).
- `ExecutionResult` gains `SUPERSEDED` as an internal outcome that is never shown as a job status.

### 4. Javet runtime (`automately-runtime-javet`)
- The prelude builds `ctx` as specified in `job-context.md`: frozen, with `step`, `saveCheckpoint`, `stepKey`, `completedSteps`, and the `SupersededError`/`StateLimitError` classes.
- `HostState`, a new `@V8Function` binding next to `HostConsole`: `completeStep(name, json)` and `saveCheckpoint(json)`. These call `StateSink` and turn failures into JS errors.
- `cancel()` sets the kill reason and calls `terminateExecution()`, reusing the timeout path.

### 5. Scheduler and reaper (`scheduler` package)
- `JobScheduler.submit` accepts `onOrphan` and `maxAttempts`. Dispatch is pulled out into `dispatch(Job)` so the reaper can reuse it.
- `NodeVerticle.onRun`:
  1. Move the job to `started` with compare-and-set; if that's rejected, exit (it was superseded).
  2. If resuming, copy state from N−1.
  3. Load the context and run.
  4. Write `finished` with compare-and-set, checked against the attempt. If it's rejected, log `SUPERSEDED` and publish nothing.
- New `OrphanReaper` verticle, deployed on every node and acting only on the elected one. Every heartbeat it:
  1. Lists `RUNNING` jobs whose `nodeInstance` isn't live, and `QUEUED` jobs older than the dispatch timeout.
  2. Waits a **grace period** (3 heartbeats after the TTL expires) per job, tracked in memory.
  3. Applies the policy:
     - `QUEUED` jobs: re-dispatch.
     - `FAIL`, or attempts used up: `ORPHANED` (terminal) and publish `end`.
     - `RETRY`/`RESUME`: `nextAttempt`, publish an `attempt` event, then dispatch.
- Event bus: the `attempt` event, and `end` published only when the job reaches a terminal status.

### 6. API (`automately-server`)
- `POST /v1/jobs`: validate `onOrphan` and `maxAttempts`.
- The job JSON includes the new fields.
- `GET /v1/jobs/{id}/steps` returns the current attempt's state.
- SSE: forward `attempt` events, and replay attempt history for jobs that have already finished.

### 7. Docs
Update `CHANGELOG.md`, `DECISIONS.md` (D-009 to D-012), the README API table, and `job-context.md` if anything shifts during implementation.

## Tests

| Level | Test |
|---|---|
| Unit (`Job`) | Transitions are rejected for a stale attempt; `nextAttempt` respects `maxAttempts` |
| Unit (`JobStore`) | Concurrent `update` calls lose no transition (compare-and-set) |
| Core, one Vert.x instance | Undeploying a node mid-job simulates a crash: `fail` → `ORPHANED`, `retry` → runs again from the start, `resume` → skips completed steps; attempts used up → `ORPHANED` |
| Core | **Zombie:** the old node's fake runtime finishes after the takeover, its write is rejected, and the result from the newer attempt stands |
| Core | **No false positive:** a heartbeat gap shorter than the grace period causes no takeover |
| Core | A `QUEUED` job whose node vanished before starting is re-dispatched even under `fail` |
| Core | Restarting a node with the same name doesn't hide its orphans (instance ids) |
| Runtime (Javet) | `step` memoises across a resumed context; duplicate names throw; a failing `fn` isn't recorded; checkpoints round-trip; size limits; `SupersededError` |
| Cluster (3 nodes in one JVM) | A resumable script runs on node A; A's Vert.x is closed mid-job; the job finishes on B/C with the earlier steps skipped (checked through logs and `/steps`) |
| Docker (manual + scripted) | `docker kill` while the README's invoice example is running, then `/steps` and the SSE `attempt` event are checked |

## Order of work
1. Prove compare-and-set first: a small test that `replaceIfPresent` works on a **clustered** map with `JsonObject` values. Everything else depends on it.
2. Model, `JobStore.update`, and state store, with unit tests.
3. Node instance ids and the cancellable SPI, keeping the current tests green.
4. `NodeVerticle` changes and the reaper, with the core crash and zombie tests.
5. The `ctx` API in Javet, with runtime tests.
6. API, SSE and `/steps`, with the cluster chaos test.
7. A Docker check and the docs pass.

## Out of scope for phase 2
Long-running services (phase 2b). Persisting jobs outside the cluster: if **every** node dies, the in-memory cluster maps are lost, so durable storage such as Postgres is needed. That is noted and tracked separately. Isolate pooling and host APIs (phase 3).

## What changed during implementation
- **No internal `SUPERSEDED` outcome (D-016).** A cancelled attempt simply reports `FAILED: Cancelled: …`. The node's "finished" write is then rejected by the attempt check, so the job never sees it. That's simpler and gives the same guarantee. A metric for discarded results is planned for phase 5.
- **Graceful stop (D-015).** A stopping node cancels its isolates and waits for them to exit; this fixed a native JVM crash on exit.
- **Liveness is judged from `lastSeen`, not map TTLs (D-013).** The first chaos runs failed about one time in five. A measurement showed Hazelcast expiring TTL entries up to ~1 s early (a 600 ms entry gone after 2 ms; a 3 s entry after ~2.0 s). Node entries flickered in and out of the registry, which made jobs fail with "No node has free capacity" and stalled reaping.
- **Every node tracks suspects (D-014).** Because leadership flipped while liveness flickered, the old design reset the grace timer on every flip and orphaned jobs were never reaped. Now each node keeps its own suspect timers and only the current leader acts.
- **Dispatch failures name each node's reason**, and failed orphan or requeue updates are logged. Before this, they failed silently, which hid the bug above.
- **The Docker check was done by hand at first.** It's now scripted as a Testcontainers integration test (`DockerClusterIT`), which `./mvnw verify` and CI run.
