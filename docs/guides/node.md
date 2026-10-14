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
c93 node update                  # fetch the next release and restart (a checkout rebuilds from source)
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
