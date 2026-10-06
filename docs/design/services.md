# Design and plan: long-running services (phase 2b)

**Status:** ✅ Done (2026-10-06). 115 unit tests and 3 Docker integration tests pass in three consecutive full builds, with no JVM crash reports. See "What changed during implementation" at the end.
**Script API:** [../scripting/services.md](../scripting/services.md)

## What a 2017 service was
In the old `JobServer` (`automately-core-master/.../services/job/JobServer.java`), a service was a job with `service = true`:
- a `serviceName`, unique per user ("You can only have a single service running with the same name")
- a `serviceConfig` the script could read
- **no timeout** ("can pretty much be ran until it is stopped")
- registered in a persistent map and **started automatically** when the cluster booted
- capped by a per-user `max_service_jobs`

## Goal
The same idea, made robust: **a service is a named, long-running script per tenant that the cluster keeps running.** If it crashes, its node dies, or its definition changes, the cluster starts it again (with backoff) somewhere healthy. It stops only when asked.

## Model

### Service definition (desired state)
Stored per `tenant/name`, and changed only through compare-and-set (like jobs):

| Field | Meaning |
|---|---|
| `name`, `tenant` | Identity. `name` matches `[a-z0-9][a-z0-9_-]{0,62}` |
| `script`, `config` | What runs, and the JSON it gets as `ctx.service.config` |
| `restart` | `always` (default): restart whenever a run ends. `on-failure`: a run that returns normally completes the service |
| `enabled` | Desired state: should it be running? |
| `revision` | Incremented on every change to `script`, `config` or `restart` |
| `currentJobId`, `currentRevision` | The run the cluster considers current |
| `runs`, `consecutiveFailures`, `nextStartAt`, `lastExit` | Supervision bookkeeping |

### A run is a job
Each run of a service is an ordinary job, with `service: {name, revision, run, previousJobId}`. So everything from phase 2 applies: least-loaded placement, fencing, logs, SSE and `/steps`. Service jobs have **no timeout** (the heap limit still applies) and use `onOrphan: fail`. The **supervisor**, not the job, decides about restarting.

### Supervisor (new verticle, same leader rule as the reaper)
On each heartbeat the leader reconciles every service:

1. **Run ended?** If `currentJobId` is missing or terminal, record `lastExit` and classify it:
   - `CANCELLED` because of a stop or a new revision: not a failure.
   - `SUCCEEDED`: with `restart: always`, restart (not counted as a failure); with `on-failure`, mark the service completed and disable it.
   - `FAILED`, `TIMED_OUT` or `ORPHANED`: a failure. Increment `consecutiveFailures`, but reset it to 0 if the run had been up for at least 60 s. Then `nextStartAt = now + min(60 s, 1 s × 2^(n-1))`.
2. **Should be running and isn't?** Once `nextStartAt` has passed, generate a job id and **compare-and-set it into `currentJobId` first**, then save and dispatch the job. If two supervisors overlap, only one compare-and-set wins, so there's never a second run. A crash between the two steps leaves a service pointing at a job that doesn't exist, which step 1 treats as ended.
3. **Should be stopped, or outdated?** Request a stop of the current job (step 1 restarts it on the new revision when it ends).

### Cancelling and stopping (also new for plain jobs)
- `cancelRequested` is a field on the job, set with compare-and-set. That makes it durable: if the cancel message to the node is lost, the node's heartbeat check still sees the flag, and the reaper ends an orphaned job with a pending cancel as `CANCELLED` instead of retrying it.
- A `QUEUED` job moves straight to `CANCELLED`.
- For a `RUNNING` job the node is told directly, and it does a **graceful stop**: `ctx.stopRequested` resolves, the script gets `stopGraceMs` (default 5 s) to clean up and return, and then the isolate is terminated.
- New terminal status `CANCELLED`. Cancellation is never counted as a failure.

### The runtime event loop (Javet)
Each execution gets an event loop on its isolate's thread:
- `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`, and `ctx.sleep(ms)`. Minimum 1 ms, at most 10 000 pending timers.
- External tasks (stop requested, and in phase 3 incoming requests and messages) are posted to a queue that wakes the loop.
- A **job** whose promise is pending with no timers and nothing queued still fails with "never settles". A **service** in that state simply waits idle until something is posted, such as a stop.
- Timeout and cancel also wake the loop, so a script that's idle (not running JS) still stops promptly.
- Isolates run on a dedicated Vert.x worker executor sized to the node's capacity, with no execute-time warnings, because a service can run for weeks.

### Logs
Stored logs keep the **last** 1 000 lines (a ring buffer) instead of the first 1 000, so a crashed service shows how it ended. This applies to jobs too.

### Checkpoints carry across runs
A service run starts with `ctx.checkpoint` set to the last checkpoint of the previous run. Steps don't carry over, because each run is a fresh pass. This lets a service such as a poller keep its cursor across crashes and restarts.

## API

| Method | Path | |
|---|---|---|
| `PUT` | `/v1/services/{name}` | Create or update `{script, config?, restart?, enabled?}`. A change to script, config or restart bumps `revision` and rolls the running instance |
| `GET` | `/v1/services` | The tenant's services with their status |
| `GET` | `/v1/services/{name}` | Definition, `status` (`STARTING`, `RUNNING`, `BACKOFF`, `STOPPING`, `STOPPED`, `COMPLETED`), `currentJobId`, `runs`, `lastExit` |
| `POST` | `/v1/services/{name}/start` · `/stop` · `/restart` | Change the desired state. Restart = stop the current run without counting it as a failure |
| `DELETE` | `/v1/services/{name}` | Stop and remove |
| `POST` | `/v1/jobs/{id}/cancel` | Cancel any job (graceful for running jobs) |

Live logs: `GET /v1/jobs/{currentJobId}/events`.

Limit: `AUTOMATELY_MAX_SERVICES_PER_TENANT` (default 10) enabled services per tenant. Each running service takes a node slot.

## Known gaps (deliberate)
- **A full-cluster restart loses service definitions**, like everything else in the in-memory cluster maps. The old platform autostarted services from a persistent map; that comes back with the durable store (`ServiceStore` is an interface so a Postgres implementation can plug in).
- **A partitioned node can briefly run a second copy** of a service until its heartbeat check sees it lost the job. That's the same guarantee as jobs: state writes are fenced, outside side effects need idempotency keys.

## Work breakdown
1. **Runtime event loop.** Timers, posted tasks, idle waiting for long-running executions, wake on kill, dedicated executor, and `stop(grace)` on `RunningExecution`.
2. **Job model.** `CANCELLED`, `cancelRequested`, optional `service` reference, timeout 0 meaning none, and a ring buffer for logs.
3. **Cancellation.** The scheduler's `cancel`, a node control address, the node's heartbeat check for `cancelRequested`, and the reaper honouring a pending cancel.
4. **Services.** `ServiceDefinition`, `ServiceStore` and its SharedData implementation, `ServiceSupervisor`, and carrying checkpoints between runs.
5. **API.** The `/v1/services` endpoints, job cancel, and the per-tenant limit.
6. **Tests:**
   - runtime: timers, idle and stop, grace exceeded, cancel while idle
   - core: supervisor start, uniqueness, backoff, `on-failure`, stop, revision roll, node death, checkpoint carry-over, cancel semantics
   - chaos: the service survives its node being stopped
   - `DockerClusterIT`: the service survives a `SIGKILL`
7. **Docs.** CHANGELOG, DECISIONS, README and the scripting guide.

## What changed during implementation
- **A job waiting on `ctx.stopRequested` isn't "never settles" (D-022).** The first design failed any job with nothing scheduled. ServicesTest showed that a job waiting to be cancelled is a legitimate wait, so looking at `ctx.stopRequested` now counts as waiting for something; the job's timeout still bounds it.
- **Cancelled runs report `CANCELLED`, not `FAILED` (D-020).** This includes zombies cancelled by their own node, which updates D-016. Their results are still discarded by the attempt check.
- **The leader can be the node that dies.** The service chaos tests kill whichever node runs the service, including the supervisor leader. Leadership moves to the next instance and the service comes back.
- **`STOPPED` can show before `lastExit` is filled in.** Status is derived from the run's job, which can end a sweep before the supervisor records the exit. Clients that need `lastExit` should wait for `currentJobId` to be null.
- **`PUT` accepts `enabled`**, so a service can be defined without starting it (and a disabled service doesn't count toward the tenant limit).
