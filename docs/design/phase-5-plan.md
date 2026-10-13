# Plan: observability and polish (phase 5)

**Status:** Milestones 1 and 2 ✅ done (2026-10-07; tracing deferred, D-058); milestones 3 and 4 next · **Date:** 2026-10-07
**Why:** Weak spot #8 from the [revival plan](revival-plan.md): the 2017 code had its own logger and no metrics, so nothing could be measured or watched. Phase 5 also finishes the project as a portfolio piece: numbers in the README come from real measurements, the security model is written down, and a demo shows it working end to end.

## Milestone 1: metrics, health and logs
- **Prometheus metrics with Micrometer**, on a separate management port (`C93_METRICS_PORT`, default 9090; `0` turns it off). They're scraped on a private network, like the internal port, and not exposed with the API.
  - **jobs:** submitted, started and finished by status; queue depth; time from queued to started; run time
  - **isolates:** live count, cold-start time, and kills by reason (timeout, heap, cancelled)
  - **services:** running and desired counts, restarts
  - **API:** requests by operation and status, latency, and 429s by tenant
  - **ingress:** requests and latency by status, bytes in and out, streams open
  - **storage:** bytes stored per backend, uploads, 507 refusals, S3 call latency and errors
  - **cluster:** live nodes, leader, Ignite data region use, and JVM and Vert.x pools
- **Readiness besides liveness:** `/health` stays "the process is up". The new `/ready` is "joined the cluster, the file backend checks out, the leader is known", for load balancers and Kubernetes.
- **Structured logs:** `C93_LOG_FORMAT=json` writes one JSON object per line, carrying `node`, `tenant`, `jobId` and `operation` where known. The default stays human-readable text.
- **Tracing (optional):** with `C93_OTLP_ENDPOINT` set, OpenTelemetry spans for API requests, scheduling, the hop to the running node, and storage calls. Nothing is exported when it isn't set.

## Milestone 2: a dashboard you can run
- `docker compose --profile observability up` adds Prometheus and Grafana to the three-node demo cluster, with a provisioned **Cluster93** dashboard: cluster health, jobs flow, isolates, services, API and ingress, storage, and tenant hot spots.
- The dashboard JSON lives in the repo, and a test checks that every metric it uses exists.

## Milestone 3: benchmarks
- A `benchmarks/` harness run against a real cluster, with each result recorded with the machine it ran on:
  - **cold start:** submit to first line of output for a trivial script
  - **isolate density:** concurrent isolates per node before latency degrades, and memory per isolate
  - **throughput:** jobs a second on 1 and 3 nodes
  - **ingress:** requests a second and p99 latency for a service answering through the ingress
  - **files:** upload and download MB/s on the cluster backend and on S3
- The results go in `docs/benchmarks.md` and the README's numbers. The harness can be rerun with one command.

## Milestone 4: security write-up, README and demo
- **`SECURITY.md`:**
  - the threat model: tenants, isolates, egress, tokens, storage providers, the internal port, MCP
  - what's defended and how, each with the decision that covers it
  - what isn't, and how to report a vulnerability
- **README:** a short architecture diagram, the "2017 → 2026" story built around the weak-spots table, and the benchmark numbers.
- **A demo:** `scripts/demo.sh` runs the full story on the three-node cluster:
  1. install the CLI
  2. an AI agent deploys a service over MCP
  3. traffic flows to it
  4. the node running it is killed
  5. the service comes back elsewhere and the dashboard shows it

  It's recorded as an asciinema cast linked from the README.

## After phase 5: move the repository to Tony's personal GitHub
Builds on the company account fail with "recent account payments have failed or your spending limit needs to be increased".
- Transfer `TonyRice/cluster93` to the personal account. GitHub redirects the old URL afterwards.
- Point `origin` at the new location.
- Update the defaults that name the repository: `install.sh`, `install.ps1`, `c93 upgrade` (`C93_RELEASES`), and the README and guide download URLs. The release workflow needs no change.
- Check the new account's Actions and billing settings before tagging the first release.
- Record it as a decision, with a changelog entry.
