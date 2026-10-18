# Benchmarks

> **Indicative, not a benchmark claim.** Single-machine numbers, kept as a rough sense of the
> platform's shape. They are not a performance guarantee, and this page is not maintained: rerun
> `benchmarks/run.sh cluster` on your own hardware for numbers that mean something to you.

Measured 2026-10-07 with `benchmarks/run.sh cluster`: the three-node demo cluster in Docker on one machine,
driven from the same machine through Docker's port mapping.

- **Machine:** 11th Gen Intel(R) Core(TM) i7-1185G7 @ 3.00GHz, 8 CPUs, 30 GB RAM
- **Software:** Docker version 29.8.2, Cluster93 2.0.0-SNAPSHOT, JDK 21 (Temurin) in each container
- **Cluster:** 3 nodes, capacity 32 isolates each, one backup copy of all data

| Scenario | How | Result |
|---|---|---|
| Cold start (submit → finished, trivial job) | 100 sequential jobs | p50 28.8 ms, p95 158.8 ms, p99 301.5 ms |
| Job throughput | 1 client, 15 s | 65.3 jobs/s (p95 22.2 ms, 0 failed) |
| Job throughput | 32 concurrent clients, 20 s | 71.1 jobs/s (p95 805.8 ms, 0 failed) |
| Ingress → service | 64 concurrent clients, 15 s | 1925 req/s, p50 23.2 ms, p99 186.0 ms (0 errors) |
| Files (cluster backend, replicated) | 256 MB, one stream each way, median of 3 | upload 60 MB/s, download 338 MB/s |
| Isolate density | 96 idle services on 3 nodes | 1.6 MB per isolate (all three nodes: 5637 MB -> 5786 MB) |

## Isolate start-up

`IsolateStartupBenchmark` (in the conformance suite in `runtime-js`, run on V8 with `C93_BENCH=1 ./mvnw -pl runtime-javet -Dtest=IsolateStartupBenchmark test`) times one job's isolate in-process, with no HTTP or cluster: a fresh isolate, the prelude and the script, on one thread after 100 warm-up runs, 500 measured. Measured 2026-10-08 on the machine above (D-106):

| Script | p50 | p90 | p99 |
|---|---|---|---|
| `export default () => 1` | 3.5 ms | 3.8 ms | 4.8 ms |
| imports `node:crypto` | 4.7 ms | 5.2 ms | 7.3 ms |
| imports `node:stream` | 6.7 ms | 7.1 ms | 7.9 ms |
| imports `node:zlib` | 7.6 ms | 7.9 ms | 8.4 ms |

Before V8's code cache was used for the prelude and built-ins, these were 4.9, 7.7, 12.0 and 13.1 ms, without the globals D-106 added.

## Reading these numbers

- **One laptop runs everything.** The client and all three nodes share the same CPUs, so they compete. On the same
  machine on the same day, results varied by up to 2x between runs (32-client throughput 37 to 71 jobs/s, uploads
  45 to 60 MB/s). Compare runs on one machine rather than reading much into the absolute values.
- **Cold start is the whole round trip:** the HTTP submit, placement on a node, a fresh V8 isolate (isolates are never
  reused, see SECURITY.md), running the script, recording the result, and the job's event stream delivering its end.
  The client waits on that stream, as the CLI does, rather than polling.
- **Throughput doesn't scale with clients on one machine.** Where the time goes under load hasn't been profiled yet.
- **Uploads are bound by replicated writes.** Each 1 MB chunk is written to its primary and, synchronously, its backup
  before the next is read. Storing four chunks at once was tried and measured no faster (48 vs 45 MB/s), so it
  wasn't kept (D-059).
- **Density is the resident memory of the Java processes**, with a fixed, pre-touched Java heap so that growth is
  native memory. An idle isolate costs that much; a busy one can grow to `C93_MAX_HEAP_MB` (64 MB here).
  The node's capacity setting, not memory, limits how many isolates run.

## What the worker boundary costs (draft, 2026-10-16 — not committed yet)

The numbers above are all `C93_WORKER_MODE=embedded`: the engine runs **in the node's own JVM**, so a host call is a
direct Java call and there is no worker process at all (`docker-compose.yml` sets exactly that). A node that runs
workers instead (`C93_WORKER_MODE=process`, or worker containers over `C93_WORKER_SOCKET`) puts a process boundary in
the path of everything that is not pure computation — every host API call, every body, every service request — and
that boundary is what these measure.

**Method.** One node as a process, one native `worker-javet`, the same workloads twice, on an 8-CPU desktop at load
~5 (browser and a `c93 node` install also running — see the caveat at the top of this page). The micro numbers are 4
repeats each; the system numbers are single samples from `benchmarks/cluster/Bench.java` against the local node.
Harness: `proxy-cost.sh` (draft, `/tmp/opencode/proxy-cost.sh` — to be committed beside these numbers).

| Workload | embedded (no hop) | worker (proxied) | cost of the boundary |
|---|---|---|---|
| Host call (`kv` get/set round trip), 20,000 calls | ~20 µs/call (19.6–21.1) | ~95 µs/call (74–124) | **+55–75 µs per call**, ~4.5× |
| 64 MB body, fetched by the node and streamed to the run | ~240 MB/s (208–275) | ~117 MB/s (111–124) | **~2× slower** |
| …the chunks the script sees | ~8,200 (8 KiB max) | ~12,300 (8 KiB max) | ~1.5× the chunks, ~5.4 KiB average |
| Cold start (submit → finished), 60 sequential | p50 17.5 ms | p50 19.3 ms | indistinguishable |
| Job throughput, 16 concurrent clients | 236 jobs/s | 302 jobs/s | indistinguishable |
| Files, 128 MB, one stream each way | 245 up / 505 down MB/s | 214 up / 493 down MB/s | noise |
| Ingress → service, 64 concurrent clients | 4,924 req/s, p50 9.5 ms, p99 49.5 ms | 3,749 req/s, p50 14.1 ms, p99 66.3 ms | **−24% req/s, +2–5 ms p50** |

**Reading it.** Crypto and zlib are *not* in that table because they don't cross: `RemoteHostApis` runs them in the
worker (§6, D-126), so hashing and compression cost nothing extra. What crosses is anything with authority or
node-owned state (`kv`, `fs`/`files`, `bus`, `secrets`, `jobs`, `packages`, `log`, `timers`) and all I/O (`fetch`,
`http`, `net`, `tls`, ingress requests, events, streams). So: a CPU-bound script pays nothing; a script that hits
state in a loop pays ~55–75 µs per call (20k `kv` calls: 0.65 s → 1.9 s); a body pays ~2×; a service pays roughly one
crossing per request. "Run a script" — the common case — doesn't move, because isolate start, HTTP and the cluster
store dominate it. The first job on a node or tenant additionally pays the worker's start (this harness warms up
first, so it is not in the table); `REUSE_SAME_TENANT` and D-172 are what amortise that.

The ingress line repeated across three runs (embedded 4.5–4.9k req/s, worker 3.5–4.0k, p50 +1.5 to +4.6 ms), and the
µs and MB/s lines were stable across four repeats. Everything else on this page is inside this machine's noise.
