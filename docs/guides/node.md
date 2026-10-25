# Run a node

**Status:** Phase 5 node install & lifecycle (2026-10-13). Decisions: D-049, D-069, D-134.

A Cluster93 **node** is one process that can play the `api`, `ingress`, `worker`, `data` and `console`
roles. `c93 node` is its control plane: install it from a release, run it as a service, manage its
settings, and join it to a cluster — all without a checkout of the repository.

```bash
c93 node install        # fetch a release, lay out the tree, write node.env + a service unit, and start
c93 node status         # backend, PID, /health, /v1/nodes
c93 node logs --follow
```

A node needs **Java 21+**. There is no Windows node: on Windows a node is Docker or WSL2 (see below).

### Checkout development

For work on the repository itself, `c93 dev` is the checkout stack — no service unit, no release:

```bash
c93 dev up                       # build if needed, start, seed, save the dev profile
c93 run examples/js/01-hello-job.js
c93 dev status
c93 dev logs --follow
c93 dev down                     # data is kept
c93 dev reset --yes              # stop and delete the data
```

Data lives at `~/.cluster93/dev-data`; the login is the `dev` profile, so it never overwrites a production login.
`c93 local …` remains a hidden alias for one release.

## Install

### From the CLI

```bash
c93 node install                    # per-user node (default)
c93 node install --no-start         # lay it out and configure it, but don't start it
c93 node install --dir /opt/c93b    # put the program somewhere else
c93 node install --version v2.1.0   # a specific release (default: the latest)
sudo c93 node install --system      # a system-wide node (Linux, root)
```

### Checkout, release, or container

Until releases are the default, `c93 node install` **inside a checkout builds from that checkout** (D-190). Outside
one it uses a release. The choice is explicit:

```bash
c93 node install --checkout       # build this tree, copy the jar/workers, install the unit
c93 node install --release        # a release even inside a checkout
c93 node install --version v2.1.0 # a specific release
c93 node install --docker         # one node container; builds from the checkout unless --image/--version (--node-id, --http-port … for a second node)
```

A release install **checks `SHA256SUMS` against the release's signature** (the key is built into the CLI, D-069),
then every file against `SHA256SUMS`, gunzips the native workers into `bin/`, writes `node.env` and a service unit,
and — unless `--no-start` — starts the node. Checkout installs build only what is missing and reuse the existing
`server/target/c93.jar` and `bin/worker-*`.

Assets come from `C93_RELEASES` (default `TonyRice/cluster93`) on GitHub Releases. A mirror can serve
them instead with `C93_INSTALL_URL`; `C93_INSECURE_SKIP_SIGNATURE=1` skips the signature check (HTTPS
alone), as in the shell installer.

### Joining and role shortcuts

```bash
c93 cluster invite node2 --host 10.0.0.12
c93 node install --checkout --join "$(cat cluster-tls/node2.join)" --members node1,node2   # at install
c93 node join c93_nodebundle_… --members node1,node2                                      # or later; restarts
```

`--compute` is shorthand for `--roles worker` (no API/ingress on a private compute host); `--roles` takes a
comma-separated list.

### From the shell installer

The one-line installer installs the `c93` CLI, and `--node` hands straight over to `c93 node install`
(one implementation, D-134):

```bash
curl -fsSL https://github.com/TonyRice/cluster93/releases/latest/download/install.sh | sh
curl -fsSL …/install.sh | sh -s -- --node            # then a per-user node
curl -fsSL …/install.sh | sh -s -- --node --system   # then a system-wide node
```

### Where things go

| | user (default) | system (`--system`, Linux, root) |
|---|---|---|
| Program | `~/.local/share/cluster93/` (`c93.jar`, `bin/worker-*`) | `/opt/cluster93/` (root-owned) |
| Config | `~/.config/cluster93/node.env` | `/etc/cluster93/node.env` |
| Metadata | `~/.config/cluster93/node.meta` (where the install put its pieces, so `--dir` is found again) | `/etc/cluster93/node.meta` |
| Data | `~/.local/share/cluster93/data` | `/var/lib/cluster93` (owned by the `cluster93` user) |
| Logs | `<data>/logs/server.log`, or the journal under the unit | the journal |
| Unit | `~/.config/systemd/user/cluster93.service` · `~/Library/LaunchAgents/com.cluster93.node.plist` | `/etc/systemd/system/cluster93.service` |
| Owner | you | a dedicated `cluster93` system user (created by the installer, D-136) |

`--dir DIR` overrides the program directory (and is self-contained for data under the default home).
The templates live in [`install/systemd/`](../../install/systemd) and
[`install/launchd/`](../../install/launchd); the CLI renders the resolved paths into them, so `--dir`
works.

A **`--system` install is self-sufficient**: it creates a dedicated `cluster93` system user
(`useradd --system --user-group`, no login shell), owns the data directory by it
(`/var/lib/cluster93`), and makes `node.env`/`node.meta` readable by it (`0640 root:cluster93`). The
account is left in place by `uninstall` — removing it is your call (`userdel -r cluster93`).

## Lifecycle

Every verb drives the **service unit** when there is one, and the **process directly** when there
isn't (a container, WSL, or a plain shell). `c93 node status` reports which backend it found:

| Condition | what `start`/`stop`/`status` use |
|---|---|
| a systemd unit (user) | `systemctl --user …` |
| a systemd unit (system) | `systemctl …` (root or `sudo`) |
| a macOS LaunchAgent | `launchctl kickstart -k gui/$UID/com.cluster93.node` |
| neither | `java -jar c93.jar`, with a pid file under the data directory |

```bash
c93 node start · stop · restart
c93 node status                  # backend, PID, /health, /v1/nodes (add --json)
c93 node logs [--follow] [-n N]  # server.log, or the journal under a unit
c93 node mcp                     # ready-to-paste MCP configuration for AI agents
c93 update --node               # fetch the next release and restart (a checkout rebuilds from source)
c93 node uninstall [--purge]     # stop, remove the program and unit; --purge also deletes the data
```

### Settings: `node.env`

The node's settings are `C93_*` environment variables in `node.env`, read straight by the service
unit (systemd `EnvironmentFile=`) or the CLI. `c93 node env` shows them; `--set` changes them;
`--edit` opens the file in `$EDITOR`. The CLI accepts the short names too, so `--set HTTP_PORT=9494`
is `--set C93_HTTP_PORT=9494`:

| Key | Default |
|---|---|
| `C93_NODE_ID` | this machine's host name |
| `C93_ROLES` | `api,ingress,worker,data,console` |
| `C93_DATA_DIR` | `<program>/data` (system: `/var/lib/cluster93`) |
| `C93_HTTP_PORT` / `C93_INGRESS_PORT` / `C93_METRICS_PORT` | `9393` / `9394` / `9395` |
| `C93_WORKER_MODE` | `process` on Linux with native workers, else `embedded` |
| `C93_WORKER_DEFAULT_MODE` | `graaljs` |
| `C93_ADMIN_TOKEN` | generated on install |
| `C93_TENANT` | `local` |

```bash
c93 node env --set HTTP_PORT=9494
c93 node restart
```

## Worker containers

A node can run its engine workers as **containers a deployment creates** (D-166, D-167). The node **listens** on a
Unix socket; the workers **connect** to it, and each connection becomes an **open worker** the node binds to
whichever tenant has work. The node never creates a container, holds no Docker socket, and does not know how many
workers exist until they connect.

This is **host isolation, not tenant isolation**: a container holds a tenant-agnostic pool, so two tenants' workers
may share one. Guests stay separated by the engine — a fresh context per job — exactly as before. Supply is **per
node** rather than per cluster, which is what a cluster across hosts turns into a topology question — see
[Worker containers in a cluster](#worker-containers-in-a-cluster).

### Local worker containers from the CLI

On a Docker install, the CLI can create the worker supply for one node (D-191):

```bash
c93 node install --docker --compute --workers 4 --join "$(cat cluster-tls/node2.join)"
c93 node workers scale 6      # or add/rm
```

The node and its workers share the socket directory and the generated `worker.secret`; each worker has its own
scratch mounts. Worker services use the D-187 shrink-on-0 loop and `restart: on-failure`, so a release leaves a
worker down instead of restarting it. Choose `--egress-placement node` to give the workers no network at all
(`network_mode: none`); the default `worker` placement gives them an egress network.

### Enabling it

1. Uncomment the two settings in `node.env` (an install writes them commented, so they are discoverable):

   ```
   C93_WORKER_SOCKET=/run/cluster93/workers/node.sock
   C93_WORKER_SECRET_FILE=/run/secrets/c93-worker-secret
   ```

   `C93_WORKER_CONNECTIONS_MAX` bounds how many connections the node accepts (default: its worker count). A
   **container** node already mounts its worker socket directory — `<config dir>/workers` — at
   `/run/cluster93/workers`, at `0700`, so a co-located worker container can share it (a `--system` install gives
   that directory to the container's user, which is who creates the socket in it). A **process** node
   (`c93 node install`) has no such mount: create the directory yourself
   (`install -d -m 0700 -o <node-user> -g <node-group> /run/cluster93/workers`) and mount the same path into the
   worker container. The node creates `node.sock` in it at runtime, so share the **directory**, never the file.

2. Create the secret once, readable by the node's user and by nobody else:

   ```bash
   sudo install -m 0400 -o <node-user> -g <node-group> /dev/null /run/secrets/c93-worker-secret
   head -c 32 /dev/urandom | base64 | sudo tee /run/secrets/c93-worker-secret >/dev/null
   ```

   It is **pre-shared**, not per-worker: the node cannot hand a fresh secret to a container it did not create
   (D-168). Whoever can read that file can present it, which is why the socket directory is `0700`, the socket
   `0600`, and the file's mode matters. The node warns when it finds the file looser than `0400`.

3. Restart the node, then start worker containers. `install/docker/compose.workers.tmpl` is a complete sketch (a
   supervisor keeping N workers alive, the network the placement needs, the hardening set, labels for a sweep, and
   the identity flag for each engine — D-149); by hand it is one line — here with the default `worker` placement,
   which is the one that needs a route (see [The worker's network](#the-workers-network)):

   ```bash
   docker network create c93-egress       # once: what the workers may reach (skip it for the `node` placement)
   mkdir -p /var/lib/cluster93-worker-scratch/w1/{tmp,home}     # this worker's own scratch, see below
   docker run --rm --network c93-egress \                       # `--network none` for the `node` placement
     --cap-drop=ALL --security-opt no-new-privileges --read-only \
     --entrypoint /opt/cluster93/bin/worker-javet \
     -v /var/lib/cluster93-worker-scratch/w1/tmp:/tmp \
     -v /var/lib/cluster93-worker-scratch/w1/home:/home \
     -v /run/cluster93/workers:/run/cluster93/workers \
     -v /run/secrets/c93-worker-secret:/run/secrets/c93-worker-secret:ro \
     'cluster93-node@sha256:…' ipc \
       --socket /run/cluster93/workers/node.sock \
       --secret-file /run/secrets/c93-worker-secret \
       --heap-mb 128 --threads 4
   ```

   Three things the node image cannot tell you, all caught by the check below rather than by a unit test:

   - its entrypoint is **the server**, so a worker container must replace it (`--entrypoint`, or `entrypoint:` in
     compose) — passing the worker command as arguments starts a second node;
   - a worker engine **unpacks a native library and `dlopen`s it** — Javet's V8 under `/tmp/javet/...`, GraalJS's
     isolate under the user's home — and, being a native image, it cannot be *pointed* elsewhere: those paths are
     baked in. So give each container a directory of its own mounted **at** them, as above. A plain directory is
     executable by default (nothing is relaxed: no `noexec` tmpfs, no `--tmpfs …:exec`), costs no container memory,
     and holds a few tens of MB that are rewritten at each start but never accumulate. A worker without it joins the
     pool and then fails its first job with `UnsatisfiedLinkError`;
   - on **rootless podman** the identity flag needs the image's own ids: `--userns=keep-id:uid=10001,gid=999`, i.e.
     "map the image's user (c93) to my uid". Plain `keep-id` leaves the container running as `c93` while the node's
     socket and secret belong to *your* uid, so the worker cannot even read the secret (D-149 describes the mapping
     the ids make explicit).

   A worker that starts **before** the node retries the connect with backoff for about two minutes and then exits,
   so start order does not matter, and a worker that cannot connect says so in its own log.

### The worker's network

Where egress is decided decides what the container needs (D-173), and `C93_EGRESS_PLACEMENT` on the node says which.
**`worker` is the default**:

- **`worker`** — the worker holds the rules the node synced to it and makes the tenant's connections **itself**. It
  needs a **route** (a network with egress; not `--network none`) and a **resolver** (its network's, or `dns:` in
  compose). What bounds it is those rules: the static set refuses loopback, link-local (cloud metadata), RFC1918,
  CGNAT, ULA and the cluster's own addresses whatever the route allows (D-174). Give it a network that reaches what
  tenants need and nothing else — a route is reachability, the rules are permission.
- **`node`** — the node makes every connection on the worker's behalf over the socket, so give the container **no
  network at all**. `--network none` is strictly better there, and it is what the node-placement cases in
  `scripts/worker-isolation-check.sh` assert.

`C93_EGRESS_PLACEMENT` is the node's **default**, and **one tenant can be placed the other way in its own egress policy**
(D-173 item 8, D-178) — `c93 tenants egress <tenant> --placement node`, or `--placement inherit` to follow the node
again. That is how a tenant with no resolver in its container, or one resolving thousands of distinct names, is moved
to the node without moving every other tenant; `c93 tenants egress <tenant>` prints which side decides and whether the
tenant asked for it.

**The cluster edge is yours to arrange.** Whatever rules a worker holds, its host must not be reachable from its
network:

- Publish the node's ports on **loopback** only — `127.0.0.1:9493:9493`, as `c93 node install --docker` does — so the
  worker's network has no route to the API, the ingress or metrics. Or put the workers on a network the node is not
  on, and firewall the rest.
- **Do not rely on the node's own gate for this.** The rules a worker holds *do* know the node's own interface
  addresses and ports, so a script's `fetch`/`net`/`tls` to the API, the ingress, the internal endpoint or metrics is
  refused as `cluster_node`, whatever the tenant is allowed. But the gate is a policy on the script's host APIs, not a
  filter on the container's network: a shell, a native process or anything else in that container can open the same
  connection directly, and an address that exists only inside the container's namespace (pasta's peer under rootless
  podman) is not one the node can name. The network is the boundary for that traffic; the gate is the boundary for
  the script.
- Watch the **internal endpoint** and the **metrics port** in particular: neither is meant for anyone. `--case C5`
  asserts both halves where a script can really reach the node — at the worker network's gateway, a script's `fetch`
  to the internal endpoint is refused as `cluster_node`, while a shell probe from the same container reaches the port.
  `--case C7` runs the shape that keeps the internal endpoint unreachable — the nodes on one network, the worker on
  another, the socket directory the only thing between them — and asserts the gate's refusal there too, against the
  compute node's container address.

### What it looks like from here

- `c93 node status` and `GET /metrics` report the connections: `c93_workers_connected` counts them, while
  `c93_workers_running` counts the worker **processes** the node spawned — 0 when the deployment supplies them all.
- A worker that dies fails its in-flight jobs and names the tenant and job; **why** its container died (an OOM, a
  kill) is the deployment's to read from the container's exit status, so the node says only that the connection
  ended (D-159) rather than inventing a cause. A connection the node **released** (D-187) is the exception: its death
  cause is `released by the node: <reason>`, and `c93_workers_released_total{trigger}` counts it.
- **The node checks its own socket at startup and refuses to run if it cannot hold it.** The socket must be `0600`
  in a directory no other user can write to (a sticky one like `/tmp` is fine). If it cannot be — an odd mount, a
  filesystem that hides Unix permissions, a directory someone loosened — the node fails to start with the path and
  the mode in the message, because the socket's permissions are what bind a connection to its owner: this node has
  no peer-credential check to fall back on. Fix the mode or the mount, or run a process node (don't set
  `C93_WORKER_SOCKET`).
- **Memory is reserved from the moment a worker connects**, idle and unbound included: the node reserves what the
  worker declares (`--heap-mb` × `--threads` plus overhead), because the container's own `--memory` is the
  deployment's (D-169). A connection that would exceed `C93_MAX_WORKER_MEMORY_MB` is refused, with the reason in the
  node's log.
- **Idle control is the deployment's, but the node can ask.** A worker with nothing running on it is first put back
  to work: it is re-bound to whichever tenant has work, so one container serves tenants in turn instead of one of
  them sitting warm while another starts a process (D-172). When the node really has more supply than it needs, it
  **releases** one idle worker (D-187): it marks the worker unplaceable, sends `GOODBYE` with a reason, and the
  worker reports what it refused and exits **0**. Exit 0 means *released, do not restart*; a non-zero exit is still
  a crash. A worker that loses the node (EOF, not GOODBYE) exits **1**, so the supervisor starts another and it
  reconnects when the node is back. The node never kills a connected worker — after a grace period it closes the
  socket of one that ignores the request, and that worker may reconnect (it is not punished); if it then restarts,
  it exits non-zero like any disconnect. Ask for one with
  `c93 node workers release [--worker ID] [--bound-tenant NAME] [--reason TEXT]`; automatic release is opt-in and off by
  default (`C93_WORKER_RELEASE_ABOVE`, one per `C93_WORKER_RELEASE_COOLDOWN_MS`, never below `C93_WORKER_MIN`).
  **The supervisor decides.** The shipped compose template starts four workers, counts a 0 as a shrink, keeps the
  container while any remain, and exits 0 when the last one is released; `restart: on-failure` leaves it down, which
  is how a container is given back. A supervisor that restarts a released worker is answering with a new connection
  — legal, but automatic release plus a restart-always supervisor is a loop, so keep automatic off unless the
  supervisor honours exit 0. For a systemd unit, `Restart=on-failure` has the same meaning (it does not restart on
  0); for s6, the `finish` script must not restart on 0.

### Verifying it on a real engine

`scripts/worker-isolation-check.sh` runs the cases only a real engine can decide — whether a container reaches the
`0600` socket as the right identity, whether a job round-trips on it, whether the wrong secret is refused, and which
network property the placement's own container has — and prints a matrix. On a machine with both engines available:
**C1 (rootless podman, `--userns=keep-id`) and C3 (rootful Docker, `--user`) pass**, C4 (a foreign uid) is refused,
C5 (the `worker` placement) passes, C6 and C7 (the cluster shapes) pass, C8 (release: a container exits 0 on a
release, does not reconnect, and a busy worker is refused while its job finishes) passes, and C9 (one node plus two
worker containers, a job, then `node workers scale 1`) passes; every `SKIP` line says what would enable it. **Which JS engine runs makes no difference** — the matrix is the same with
`worker-javet` and with `worker-graaljs` (`C93_IDCHECK_WORKER=worker-graaljs`), because what the cases decide is the
socket, the identity and the network rather than the engine.

C5 is the one that decides the `worker` placement, in a real container with a real route: it starts a sibling target
and runs the same job twice. Under `node` the node makes the connection and **its own socket counter moves**; under
`worker` the same job succeeds with that counter **standing still**, so the worker connected over the route its
deployment gave it and the node was never in the path. It also shows the static set still refusing metadata, RFC1918
and the cluster's own port, that the node's own refusal counter does not move, and that the node learns about the
worker's refusals only from the worker's report (D-176). Its sharpest line is the internal endpoint: at the worker
network's **gateway** — the address the worker can actually reach the node on, an interface of the node under rootful
Docker — a script's `fetch` must be refused as a **cluster node**, not merely as a private address, while a shell
probe from the same container reaches the port. The gate refuses the script; the deployment owes the network.

It is safe to re-run: everything it creates is labelled with its run id, it uses its own ports and temp directories
under `mktemp -d`, it removes the images it builds, and it never touches anything it did not create. (It keeps one
cached helper image, `cluster93-idcheck-target:py3`, for the sibling target above — safe to remove any time.)

C6 is the one that decides the shape a cluster across hosts is made of
([above](#worker-containers-in-a-cluster)): it stands up **two** nodes on the machine — an edge node
(`api,ingress`, deliberately with `C93_CAPACITY=4`) and a compute node (`worker`, capacity 2), clustered and
discovering each other — attaches **one** container to the **compute** node's socket, and submits a job at the edge
node's API. It passes when the edge node advertises capacity **0** by the role rule, the compute node is the only
candidate, the job's own record names the **compute** node, and the compute node reports a connected worker with none
of its own spawned (the container did the work). C6 deliberately avoids the cluster's default discovery ports and
checks that its own are free first. Its members name both ports, so each node binds exactly its base and the
communication port 100 above it and refuses to start when either is taken — what a node used to do was take the next
free port while its peers kept joining the base, which reads as opaque TLS errors instead of a bind failure (D-185).

C7 is the deployment shape with **both nodes in containers**: an edge node and a compute node on a `cluster` network
of their own, and the worker on a `workers` network of its own, with the compute node's socket directory the only
thing between them. It submits a job at the edge and requires the same answers as C6 (the edge advertises capacity 0,
the job names the compute node, the container's work is what the compute node reports). It asks the gate's question
first: a job's `fetch` to the compute node's **container address** — its internal endpoint — must be refused as a
**cluster node**, the policy naming the node's own interfaces. Then it asks the deployment's: from inside the worker
container it opens a connection to the same address — the address, not the name, because a name means nothing on the
worker's network. Only a completed connection fails the case; a timeout (what Docker's drop between two user-defined
networks looks like) or a refusal both mean the networks are separated, and the check reports `isolated=yes`. That is
why the networks are the deployment's to arrange: the gate governs what a *script* asks for through `fetch`, `net`
and `tls` — and refuses the node's own addresses and ports for it — while whatever else runs in that container is raw
traffic the gate never sees.

## Join a cluster

A node starts as a single-node cluster. To join others, sign it a certificate with the cluster's CA
(the `ca.key` stays with you) and start it with the one-line join (see the
[cluster guide](cluster.md)):

```bash
c93 cluster init --nodes node1,node2 --out ./cluster-tls   # once, on your machine
c93 cluster invite node2 --host 10.0.0.12                  # a one-line join
C93_JOIN=c93_nodebundle_… c93 node restart                 # on node2
c93 cluster revoke node2                                   # force it out for good
```

Set `C93_CLUSTER_TLS` and `C93_CLUSTER_MEMBERS` alongside the rest in `node.env`.

### Worker containers in a cluster

A worker container joins the socket of the node it is mounted to, and becomes **that node's** pool — supply is per node,
not per cluster. So every node that should accept containers needs its own `C93_WORKER_SOCKET`, its own socket
directory and secret, and its own containers; nothing joins a socket on another node's behalf.

That makes a cluster across hosts a topology question rather than a transport one. A **compute host** is an ordinary
node of its own — `C93_CLUSTERED=true`, the cluster's members, `C93_ROLES=worker` — with the socket and the containers
on that host, while the API and the ingress are a separate **edge** node (`C93_ROLES=api,ingress`). The edge node
advertises **capacity 0** because it has no `worker` role, whatever its `C93_CAPACITY` says, and capacity — not
reachability — is what makes a node a candidate, so nothing is ever placed on a node that cannot run it. Roles compose
(`worker,data` on one host is legal), and [node roles](../design/node-roles.md) lays out the tiers.
`install/docker/compose.workers.tmpl` is **one** node's supply: run it on each compute host, pointed at that host's own
node socket.

## Windows and macOS

- **macOS:** `c93 node install` writes a per-user **LaunchAgent** only; there is no macOS system
  install. Non-Linux nodes run **`WORKER_MODE=embedded`** (the native workers can't cross-compile).
- **Windows:** there is **no Windows node**. The `install.ps1` installer sets up the portable CLI
  (`cluster93-cli.jar` + `c93.cmd`, with Java detection); a node is **Docker** or **WSL2**.

## See also

- [The `c93` CLI](cli.md)
- [Running a cluster: node certificates](cluster.md)
- [Quickstart: a local node with Docker](../../README.md#quickstart)
