# Benchmarks

Measured 2026-10-07 with `benchmarks/run.sh`: the three-node demo cluster in Docker on one machine,
driven from the same machine through Docker's port mapping. Rerun it on your hardware: numbers depend on it.

- **Machine:** 11th Gen Intel(R) Core(TM) i7-1185G7 @ 3.00GHz, 8 CPUs, 30 GB RAM
- **Software:** Docker version 29.8.2, Automately 2.0.0-SNAPSHOT, JDK 21 (Temurin) in each container
- **Cluster:** 3 nodes, capacity 32 isolates each, one backup copy of all data

| Scenario | How | Result |
|---|---|---|
| Cold start (submit → finished, trivial job) | 100 sequential jobs | p50 28.8 ms, p95 158.8 ms, p99 301.5 ms |
| Job throughput | 1 client, 15 s | 65.3 jobs/s (p95 22.2 ms, 0 failed) |
| Job throughput | 32 concurrent clients, 20 s | 71.1 jobs/s (p95 805.8 ms, 0 failed) |
| Ingress → service | 64 concurrent clients, 15 s | 1925 req/s, p50 23.2 ms, p99 186.0 ms (0 errors) |
| Files (cluster backend, replicated) | 256 MB, one stream each way, median of 3 | upload 60 MB/s, download 338 MB/s |
| Isolate density | 96 idle services on 3 nodes | 1.6 MB per isolate (all three nodes: 5637 MB -> 5786 MB) |

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
  native memory. An idle isolate costs that much; a busy one can grow to `AUTOMATELY_MAX_HEAP_MB` (64 MB here).
  The node's capacity setting, not memory, limits how many isolates run.
