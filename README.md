# Cluster93

Cluster93 is a self-hostable, clustered JavaScript runtime for durable background jobs and supervised
HTTP/WebSocket services. Submit code to any node; the cluster schedules it onto a worker, isolates it, streams
logs, and persists state. State lives in an embedded Apache Ignite database, so there is no external database
service to run.

**Status:** `2.0.0-alpha.2`. The cluster, both JavaScript engines, worker isolation, and the `c93`
install/deploy path are complete. The console currently uses bearer-token auth; its browser session layer is in
progress. Signed container images are not published yet.

## What it gives you

- **Durable jobs.** Checkpointed `ctx.step()` work, retries, orphan failover, and live execution events.
- **Supervised services.** HTTP/WebSocket services with health checks, restart backoff, and ingress routing.
- **Two engines, two isolation modes.** Javet V8 or GraalJS, running embedded or in out-of-process workers with
  Linux cgroups v2 limits where available.
- **Cluster state included.** Replicated Ignite tables and WAL, a VFS with disk/S3 mounts, queues, and node
  membership.
- **Built for tools.** OpenAPI 3.1 REST, an MCP server over Streamable HTTP, and a native `c93` CLI.

## Install the CLI

Linux and macOS have native binaries; Windows uses a portable jar with Java 21+.

```bash
curl -fsSL https://github.com/TonyRice/cluster93/releases/latest/download/install.sh | sh
```

On Windows:

```powershell
irm https://github.com/TonyRice/cluster93/releases/latest/download/install.ps1 | iex
```

`c93 update --cli` replaces the CLI. See the [CLI guide](docs/guides/cli.md) for platform details and building
from source.

## Quickstart

Three ways to run it: a checkout stack, a permanent node, or a local three-node cluster.

### Checkout stack

From a clone, one command builds what is missing, starts a node, seeds a development tenant and operator, and
saves the `dev` profile. This path needs JDK 21+; it uses no Docker and downloads no release.

```bash
git clone https://github.com/TonyRice/cluster93.git
cd cluster93
c93 dev up

c93 run examples/js/01-hello-job.js
c93 dev status
c93 dev logs --follow
c93 dev down
```

Data lives at `~/.cluster93/dev-data` until `c93 dev reset --yes`. A fresh stack uses ports 9393–9396 and
5701/5801; if those are taken, `dev up` moves to 9493–9496 and 5751/5851 and says so. `c93 cluster dev …` is a
convenience alias; `c93 local …` remains a hidden alias for one release.

### Permanent node

For a machine that should run a node as a service:

```bash
c93 node install
c93 node status
c93 node logs --follow
```

Inside a checkout this builds the current tree; outside one it fetches the latest signed release. Add
`--system` for a system-wide Linux service, `--docker` for a node container, or `--workers N` for local worker
containers. A node needs Java 21+; native workers are Linux-only, and a Windows node is Docker or WSL2. See the
[node guide](docs/guides/node.md) for joins, roles, settings, and upgrades.

### Three-node Docker cluster

```bash
scripts/demo.sh        # needs Docker, JDK 21, curl, and python3; about 90 seconds
scripts/demo.sh down
```

The demo starts a three-node cluster, deploys a service that drains a durable queue, submits 120 orders, and
`SIGKILL`s the node serving the requests. The service restarts on a survivor, in-flight orders return to the
queue, and every order completes. Prometheus and Grafana are included; the agents' tool calls are scripted MCP
requests, so no API key is needed.

To run the same three-node stack without the demo:

```bash
export C93_ADMIN_TOKEN=$(openssl rand -hex 32)
docker compose up --build -d
docker compose down -v
```

Nodes are on 9401–9403 and the ingress ports are 9411–9413.

## How it works

Every node runs the same server binary. The API validates requests against
[`openapi.yaml`](server/src/main/resources/openapi.yaml) before they reach a handler. A scheduler places jobs on the least-loaded worker; workers execute JavaScript
behind the host API boundary; embedded Ignite holds jobs, state, services, files, and cluster membership. The
ingress proxy routes public HTTP and WebSocket traffic to the node currently serving a service. Nodes join over
mutual TLS.

For the module map and execution paths, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Isolation and security

- Guest code runs without host JVM, classpath, or arbitrary filesystem access.
- Process-mode workers run in their own processes with Linux cgroups v2 memory limits where available.
- The egress gate resolves names before connecting, blocks private and loopback targets, resists DNS rebinding,
  and enforces per-tenant socket limits.
- Tenant tokens are scoped; administrative actions are recorded in a tamper-evident audit log.

The threat model and trust boundaries are in [SECURITY.md](SECURITY.md).

## Build and test

```bash
./mvnw verify -DskipITs     # unit and conformance tests
./mvnw verify               # also runs the multi-container Docker cluster IT
```

The Docker cluster test is optional and requires Docker. `./scripts/test-all.sh` runs the suite and writes a
summary to `lasttest.txt`.

## Roadmap

- **Complete:** core cluster, storage, both JavaScript engines, worker process isolation, and the unified `c93`
  CLI/deployment path.
- **In progress:** the console's browser session layer (passkeys, OIDC, session cookies), live job logs, and the
  audit explorer.
- **Next:** the native Node runtime in rootless Podman + gVisor, then differentiated node roles and Python.

See [ROADMAP.md](ROADMAP.md) for details and [docs/DECISIONS.md](docs/DECISIONS.md) for the decision history.

## Documentation

- [docs/guides/cli.md](docs/guides/cli.md) — install and use `c93`
- [docs/guides/node.md](docs/guides/node.md) — install, run, join, and scale a node
- [docs/guides/cluster.md](docs/guides/cluster.md) — certificates, membership, and cluster operations
- [docs/scripting/host-apis.md](docs/scripting/host-apis.md) — the JavaScript host API
- [docs/scripting/node-compat.md](docs/scripting/node-compat.md) — Node.js compatibility
- [docs/guides/mcp.md](docs/guides/mcp.md) — connect AI agents over MCP
- [docs/api/endpoints.md](docs/api/endpoints.md) — REST endpoints and scopes
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — architecture and codebase map
- [docs/benchmarks.md](docs/benchmarks.md) — performance notes and how to reproduce them

## Heritage

Cluster93 continues **Automately**, a clustered backend platform first published in 2014 and acquired before
2018. This repository continues that idea, rebuilt from the ground up on Vert.x 5, Apache Ignite 2.18, and
embedded V8/GraalJS engines. The [original organization](https://web.archive.org/web/20160204012506/https://github.com/Automately)
is archived.

## Author and license

- **Author / Organization:** Adam & Lynn Incorporated · tony@adamnlynn.com
- **License:** [MPL-2.0](LICENSE.md)
