# Changelog

All notable changes to Automately are recorded here, with **what** changed and **why**.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Larger choices are explained in [docs/DECISIONS.md](docs/DECISIONS.md).

## [Unreleased]

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
