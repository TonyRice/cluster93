# Design: orphaned jobs, retries and resuming without repeating work

**Status:** Accepted. `fail` is confirmed as the default (2026-10-06). Implementation plan: [phase-2-plan.md](history/phase-2-plan.md). Script API: [../scripting/job-context.md](../scripting/job-context.md).

## Problem
When a node dies mid-job, its jobs are left `RUNNING` forever. The spike reproduced this by killing a container. The obvious fix is to restart them on another node, but that's dangerous: a job that already sent half its emails, charged a card, or got halfway through a browser session would **do that work again**.

There's a second, less obvious danger. A node can look dead (missed heartbeats) and still be running, for example during a network partition or a long GC pause. If we restart its job elsewhere, **two copies run at once**.

## Goals
1. A job never re-runs unless it chose to be retried or resumed.
2. A script can always tell whether it's a fresh start, a retry or a resume, and what happened before.
3. Work that already completed doesn't have to be repeated when a job resumes.
4. A stale ("zombie") attempt can't overwrite the results of the attempt that replaced it.

## Design

### 1. Attempts and a new terminal status
Each run of a job is an **attempt**, numbered 1, 2, 3 and so on. The job record keeps the history:

```json
{
  "status": "RUNNING",
  "attempt": 2,
  "attempts": [
    { "attempt": 1, "node": "node1", "startedAt": 1791313490705, "endedAt": 1791313499000, "outcome": "ORPHANED" },
    { "attempt": 2, "node": "node3", "startedAt": 1791313500100 }
  ]
}
```

A new terminal status, **`ORPHANED`**, means the node running the job disappeared and the job wasn't continued.

### 2. The job chooses: `onOrphan` policy
This is set at submit time (`POST /v1/jobs`):

| Policy | What happens when the node dies | Use for |
|---|---|---|
| `fail` **(default)** | The job ends as `ORPHANED` and is never re-run | Anything with side effects you can't safely repeat |
| `retry` | A new attempt starts from the beginning, up to `maxAttempts` | Pure or idempotent work, such as fetching and computing |
| `resume` | A new attempt starts and receives the last checkpoint and completed steps | Long multi-step work: browser flows, batch sends, crawls |

The default is the safe choice. Repeating work is something a job has to opt into.

### 3. What the script sees: the job context
The default export receives a second argument, `ctx`:

```js
export default async (input, ctx) => {
  ctx.attempt;        // 1 on a fresh start
  ctx.isRetry;        // true when attempt > 1 and the policy is "retry"
  ctx.isResume;       // true when attempt > 1 and the policy is "resume"
  ctx.previous;       // { attempt, node, outcome, startedAt, endedAt } or null
  ctx.checkpoint;     // last saved checkpoint, or null

  // Saved durably before it returns. A resumed attempt gets it back as ctx.checkpoint.
  await ctx.saveCheckpoint({ page: 7, processed: 140 });

  // Runs fn at most once per job. When resuming, a step that already completed returns
  // its recorded result without running again.
  const invoice = await ctx.step("create-invoice", () => billing.create(input.order));
  await ctx.step("email-invoice", () => mail.send(input.customer, invoice));
};
```

- **`ctx.step(name, fn)`** is the main tool for not repeating work. The step's result is stored when it completes. If the job resumes, completed steps return their stored result immediately and only unfinished steps run. Temporal, Inngest and Cloudflare Workflows use the same model.
- **`ctx.saveCheckpoint(state)`** is for loops where naming each step doesn't make sense (for example "processed up to row 140").
- Step results and checkpoints must be JSON and are size-limited.

**Remaining risk:** if a node dies *during* a step (after the side effect but before the result was stored), that step runs again on resume. No platform can avoid this without help from the system being called. That's why step 5 below exists.

### 4. Fencing: stopping zombie attempts
Every attempt gets an **epoch**, which is its attempt number. All writes an attempt makes (job status, checkpoints, step results, and later `kv` and `bus` calls) carry that epoch, and the store accepts a write only if the epoch matches the job's current attempt. The store uses `AsyncMap.replaceIfPresent(key, expected, new)` as a compare-and-set.

If node1 was only partitioned and comes back still running attempt 1 while attempt 2 is running on node3, all of attempt 1's writes are rejected. The node sees the rejection, stops the isolate, and records the attempt as `SUPERSEDED`.

### 5. Idempotency keys for outside side effects
Each step gets a stable key, `jobId:stepName`, available as `ctx.stepKey(name)` and as `key` inside the step function. Scripts pass it to outside APIs that support idempotency keys (Stripe, many email APIs, Cluster93's own future `kv`/`http` host APIs). Then even a step that re-runs after a crash in the middle doesn't take effect twice.

### 6. Detecting orphans
- Nodes announce themselves every heartbeat in `NodeRegistry`. A node is live while its own `lastSeen` timestamp is within 3 heartbeats (D-013). Map TTLs only clean up old entries, because Hazelcast expires entries up to about a second early.
- A **reaper** runs on every node, and every node tracks suspects, but only the live instance with the lowest id acts (D-012, D-014). There's no lock; compare-and-set makes overlapping reapers harmless. It looks for `RUNNING` jobs whose `node` is no longer live, waits a grace period (about 3 heartbeats, to ride out GC pauses), then:
  - marks the current attempt `ORPHANED`, and
  - applies the job's `onOrphan` policy: terminal `ORPHANED`, or a new attempt dispatched through the normal least-loaded placement.

### 7. API surface
- The job resource gains `attempt`, `attempts[]`, `onOrphan`, `maxAttempts`, and `checkpoint` (a summary, not the full data).
- New SSE event: `attempt` (`{"type":"attempt","attempt":2,"reason":"ORPHANED"}`), so a watcher sees the takeover happen.
- `GET /v1/jobs/{id}/steps` returns completed steps and their timing, for debugging and for the demo.

## Browser sessions (see NOTES.md)
A live browser can't be moved to another worker. For browser jobs, `resume` means "continue from the last completed step", not "restore the page". Writing browser flows as `ctx.step(...)` calls is what makes them resumable.

## Tests to write in phase 2
- Kill a node mid-job (chaos test). `fail` ends as `ORPHANED`, `retry` re-runs once, and `resume` skips completed steps.
- A zombie attempt's writes are rejected after a takeover.
- Fake a GC pause shorter than the grace period: no takeover happens.
- A step that completed is never run twice across a resume.
