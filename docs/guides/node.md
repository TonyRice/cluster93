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

## Install

### From the CLI

```bash
c93 node install                    # per-user node (default)
c93 node install --no-start         # lay it out and configure it, but don't start it
c93 node install --dir /opt/c93b    # put the program somewhere else
c93 node install --version v2.1.0   # a specific release (default: the latest)
sudo c93 node install --system      # a system-wide node (Linux, root)
```

`install` fetches the release assets, **checks `SHA256SUMS` against the release's signature** (the key
is built into the CLI, D-069) and then every file against `SHA256SUMS`, gunzips the native workers into
`bin/`, writes `node.env` and a service unit, and — unless `--no-start` — starts the node.

Assets come from `C93_RELEASES` (default `TonyRice/cluster93`) on GitHub Releases. A mirror can serve
them instead with `C93_INSTALL_URL`; `C93_INSECURE_SKIP_SIGNATURE=1` skips the signature check (HTTPS
alone), as in the shell installer.

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
may share one. Guests stay separated by the engine — a fresh context per job — exactly as before.

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
   supervisor keeping N workers alive, `--network none`, the hardening set, labels for a sweep, and the identity
   flag for each engine — D-149); by hand it is one line:

   ```bash
   docker run --rm --network none \
     --cap-drop=ALL --security-opt no-new-privileges --read-only --tmpfs /tmp \
     --entrypoint /opt/cluster93/bin/worker-javet \
     --tmpfs /home:rw,exec,mode=1777,size=256m \
     -v /run/cluster93/workers:/run/cluster93/workers \
     -v /run/secrets/c93-worker-secret:/run/secrets/c93-worker-secret:ro \
     'cluster93-node@sha256:…' ipc \
       --socket /run/cluster93/workers/node.sock \
       --secret-file /run/secrets/c93-worker-secret \
       --heap-mb 128 --threads 4
   ```

   Two things the node image cannot tell you, both caught by the check below rather than by a unit test: its
   entrypoint is **the server**, so a worker container must replace it (`--entrypoint`, or `entrypoint:` in compose)
   —    — passing the worker command as arguments starts a second node — and a worker engine needs **writable and
   executable** scratch space, because it unpacks a native library and then `dlopen`s it: Javet loads its V8 from
   `/tmp/javet/...` and GraalJS its isolate from `$HOME/.cache`. A read-only root has nowhere to put either, and
   Docker's tmpfs defaults to `noexec`, so both need `exec` (`--tmpfs /tmp:rw,exec,mode=1777` and
   `--tmpfs /home:rw,exec,mode=1777`). Without it a worker joins the pool and then fails its first job.

   A worker that starts **before** the node retries the connect with backoff for about two minutes and then exits,
   so start order does not matter, and a worker that cannot connect says so in its own log.

### What it looks like from here

- `c93 node status` and `GET /metrics` report the connections: `c93_workers_connected` counts them, while
  `c93_workers_running` counts the worker **processes** the node spawned — 0 when the deployment supplies them all.
- A worker that dies fails its in-flight jobs and names the tenant and job; **why** its container died (an OOM, a
  kill) is the deployment's to read from the container's exit status, so the node says only that the connection
  ended (D-159) rather than inventing a cause.
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
- **Idle control is the deployment's.** The node cannot shed an open worker — closing its socket only makes the
  supervisor start another — so size the pool with the worker count and the container's limits.

### Verifying it on a real engine

`scripts/worker-isolation-check.sh` runs the cases only a real engine can decide — whether a container reaches the
`0600` socket as the right identity, whether a job round-trips on it, whether the wrong secret is refused, whether the
worker container genuinely has no network — and prints a matrix: **C3 (rootful Docker, `--user`) passes**, C4 (a
foreign uid) is refused, and C1/C2 print `SKIP` until podman or rootless Docker exists. It is safe to re-run: everything it creates is labelled with its run id, it uses
its own ports and temp directories under `mktemp -d`, and it never touches anything it did not create.

## Join a cluster

A node starts as a single-node cluster. To join others, sign it a certificate with the cluster's CA
(the `ca.key` stays with you) and start it with the one-line join (see the
[cluster guide](cluster.md)):

```bash
c93 cluster init --nodes node1,node2 --out ./cluster-tls          # once, on your machine
c93 node invite node2 --host 10.0.0.12 --ca ./cluster-tls/ca.key  # a one-line join
C93_JOIN=c93_nodebundle_… c93 node restart                        # on node2
c93 node revoke node2                                             # force it out for good
```

Set `C93_CLUSTER_TLS` and `C93_CLUSTER_MEMBERS` alongside the rest in `node.env`.

## Windows and macOS

- **macOS:** `c93 node install` writes a per-user **LaunchAgent** only; there is no macOS system
  install. Non-Linux nodes run **`WORKER_MODE=embedded`** (the native workers can't cross-compile).
- **Windows:** there is **no Windows node**. The `install.ps1` installer sets up the portable CLI
  (`cluster93-cli.jar` + `c93.cmd`, with Java detection); a node is **Docker** or **WSL2**.

## See also

- [The `c93` CLI](cli.md)
- [Running a cluster: node certificates](cluster.md)
- [Quickstart: a local node with Docker](../../README.md#quickstart)
