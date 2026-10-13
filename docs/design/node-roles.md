# Differentiated node roles and capability-based scheduling

**Status:** normative (graduated from `ideas/node-roles.md`, 2026-10-11). The role and capability
configuration, the zero-capacity rule, and compute-node filtering in `JobScheduler` are implemented.
Capability *matching* (routing a job to a node that advertises a required tag) is specified here and
lands with the Python and Node runtimes ([node-runtime](node-runtime.md)); until then a job goes to any
compute node.

This specification defines how an operator partitions a cluster into specialised tiers so that the
public API, HTTP ingress, the durable database, and untrusted tenant code never have to share a process,
and how a job is placed on a node that can actually run it.

---

## 1. Why roles

Running every subsystem — public REST API, HTTP ingress, Apache Ignite persistence, and sandboxed guest
code — symmetrically on every node is convenient for a single machine and wrong for a large deployment:

- **Blast radius.** A guest isolate that escapes or exhausts its node would sit next to the API and the
  database. An edge node that runs no guest code has nothing to escape into.
- **Contention.** A CPU-bound tenant job competes with Ignite's replication and WAL flushes, causing
  rebalance timeouts and latency spikes on the database tier.
- **Topology.** Edge nodes need public addresses and TLS certificates; compute nodes belong on a private
  subnet and need no inbound ports at all.

## 2. Configuration

A node's roles and capabilities come from the environment, read by `ServerConfig.fromEnv`:

| Variable | Meaning | Default |
|---|---|---|
| `C93_ROLES` | Comma-separated subset of `api`, `ingress`, `worker`, `data`. | `api,ingress,worker,data` (a full development node) |
| `C93_CAPABILITIES` | Execution engines the node advertises, e.g. `js:v8,js:graaljs,node,python3`. | empty (matches any requirement) |
| `C93_CAPACITY` | Concurrent isolated runs a **worker** node accepts. | `8` |
| `C93_DATA_DIR` | Durable storage. | `$HOME/.cluster93/data` on a standalone node; unset (in memory) on a clustered node without one |

Rules:

- A role not in the list is refused at startup: `C93_ROLES must be a comma-separated subset of
  [api, ingress, worker, data]`.
- At least one role is required; an empty list is refused.
- **A node without the `worker` role advertises capacity 0**, whatever `C93_CAPACITY` says. It
  registers and heartbeats like any node (so its API or replay of control messages still works) but no
  job attempt is ever placed on it.
- A role a node lists but the deployment doesn't give it the resources for is a configuration error, not
  something the cluster works around. Naming `api` without binding the API port, for example, is on the
  operator.

### Roles

1. **`api` — REST API and MCP.** Serves `/v1/*`, `/health`, `/ready`, `/v1/openapi.json`, and `/mcp`,
   terminates client TLS, authenticates bearer tokens, enforces per-tenant rate limits and scopes, and
   serves the management console. Runs **zero** tenant code.
2. **`ingress` — public HTTP and WebSocket ingress.** Routes requests for services by host
   (`<service>--<tenant>.<ingress domain>`) to the node currently running the service, over the internal
   endpoint. Runs **zero** tenant code.
3. **`worker` — compute.** Hosts tenant execution: V8/Javet isolates, GraalJS sandboxes, and the
   out-of-process worker pool with cgroup memory limits. Runs on a private subnet with no public ports.
4. **`data` — durable storage.** Carries the Apache Ignite persistence tier: the data region, disk WAL,
   partition backups, VFS chunks, and (with `api`) the REST API that reads them. Runs **zero** tenant
   code, so compute spikes never starve replication.

Roles compose: the default `api,ingress,worker,data` is a single-node development cluster; an edge tier
is `api,ingress`; a database tier is `data`; a compute tier is `worker`. Every node, in every tier,
still runs the internal HTTP endpoint, the node heartbeat, and the cluster secret exchange — those are
how the tiers reach each other.

## 3. What each role deploys

`Cluster93.wire` starts only the pieces a node's roles name:

| Subsystem | Deployed when |
|---|---|
| `ApiVerticle` | `roles` contains `api` |
| `IngressVerticle` | `roles` contains `ingress` |
| `NodeVerticle` (compute slots, receives attempts) | always, with capacity 0 unless `roles` contains `worker` |
| `InternalHttpVerticle` | always (the ingress and services proxy through it) |
| `OrphanReaper`, `ServiceSupervisor`, `RetentionVerticle`, `FileGarbageVerticle`, `PackageGarbageVerticle` | always (leadership is decided per heartbeat, so several tiers can carry them) |
| metrics endpoint | always, on `C93_METRICS_PORT` |

`RunningNode.httpPort()` and `ingressPort()` are `-1` when the API or ingress isn't deployed, and
`apiRoutes()` is empty.

## 4. Capability-based scheduling

A `NodeInfo` advertises a node's capacity and load; a node with capacity 0 is never a candidate.

`JobScheduler.candidates` selects, from the live nodes:

1. Nodes with **capacity > 0** (a node playing no `worker` role reports 0 and is dropped).
2. Nodes with **free capacity** (`running < capacity`).
3. Sorted by **relative load** (`running / capacity`), ties broken by node id — the historical
   `execute_on_least_jobs` policy.

The first candidate is offered the attempt; a node that refuses (it filled up since its last heartbeat)
is skipped and the next is tried. If no node qualifies, the attempt fails with a message naming every
live node and its load, and the reaper re-dispatches it later.

When the Python ([python](../ideas/python.md)) and Node ([node-runtime](node-runtime.md)) runtimes land,
a job or service will carry required capability tags, and step 1 gains a third filter: a candidate's
`capabilities` must contain every required tag. A node that advertises no capabilities matches
everything, so an operator only has to tag the nodes that are special. A job whose requirement no live
node satisfies is rejected, naming the requirement, rather than dispatched to a node that can't run it.

## 5. Deployment topologies

```
                          Internet / public clients / AI agents
                                            │
                                            ▼
                      ┌──────────────────────────────────────────┐
                      │              Edge tier                    │
                      │        C93_ROLES=api,ingress       │
                      │   :8080 REST+MCP, :8000 ingress, :9090    │
                      │        Capacity 0 — no guest code         │
                      └────────────────────┬─────────────────────┘
                                            │ private VPC
                       ┌────────────────────┴────────────────────┐
                       ▼                                         ▼
        ┌─────────────────────────────┐           ┌─────────────────────────────┐
        │         Data tier           │           │        Compute tier         │
        │    C93_ROLES=data    │           │   C93_ROLES=worker   │
        │  Ignite WAL, data region,   │           │  Sandboxed isolates, cgroup │
        │  VFS chunks, partition bkp  │           │  memory limits, no inbound  │
        │  Capacity 0                 │           │  public ports               │
        └─────────────────────────────┘           └─────────────────────────────┘
```

Every node sets `C93_CLUSTER_MEMBERS` to the same discovery address (or addresses) and
`C93_CLUSTER_TLS=required` with node certificates ([cluster-security](cluster-security.md)) so
that joining the cluster is itself an authenticated act.

### Configuration examples

```bash
# Edge node: REST API + ingress, no guest code.
C93_ROLES=api,ingress
C93_CAPACITY=0
C93_HTTP_PORT=8080
C93_INGRESS_PORT=8000
C93_METRICS_PORT=9090

# Compute node: sandboxed execution only, private subnet.
C93_ROLES=worker
C93_CAPACITY=32
C93_CAPABILITIES=js:v8,js:graaljs
C93_HTTP_PORT=off
C93_INGRESS_PORT=off

# Database node: Ignite persistence, no guest code.
C93_ROLES=data
C93_CAPACITY=0
C93_DATA_DIR=/var/lib/cluster93/data
C93_DATA_MEMORY_MB=4096
C93_HTTP_PORT=off
C93_INGRESS_PORT=off
```

## 6. Migration

- **Existing deployments need no change:** with no `C93_ROLES` every node is a full
  `api,ingress,worker,data` node, exactly as before.
- **Splitting an existing cluster** is a rolling change: bring up the new tier first (for example a
  `data` node that joins and takes partitions), then redeploy the old nodes with narrowed roles. A node
  that stops being a worker simply advertises capacity 0; in-flight jobs on it finish (or are reaped by
  the orphan policy, `docs/scripting/job-context.md`).
- **Local development** is unchanged: `scripts/dev.sh start` runs one `api,ingress,worker,data` node
  with an explicit `C93_DATA_DIR` under `$HOME/.cluster93/data`, so the single node is durable
  and exercises the same code paths as a tiered production cluster.
