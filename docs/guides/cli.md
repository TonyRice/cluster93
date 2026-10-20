# The `c93` CLI

**Status:** Phase 5 node install & lifecycle (2026-10-13). Decisions: D-048, D-049, D-053, D-134.

## Install

Prebuilt native binaries cover Linux (x64 and arm64) and macOS (Apple silicon and Intel); where one
isn't published, the portable jar runs anywhere with **Java 21+**.

```bash
# Linux and macOS (x64 or arm64)
curl -fsSL https://github.com/TonyRice/cluster93/releases/latest/download/install.sh | sh
```

On Windows, use the PowerShell installer, which sets up the portable jar (`cluster93-cli.jar` + a
`c93.cmd` shim) and detects Java 21:

```powershell
irm https://github.com/TonyRice/cluster93/releases/latest/download/install.ps1 | iex
```

The installer:
- picks the binary for your OS and CPU (a static x64 Linux build that runs on any distro including Alpine, or arm64; macOS arm64/x64)
- **checks the release's signature** (`SHA256SUMS.sig`, made with the project's release key, which is built into the installer), then the binary's SHA-256 against the signed `SHA256SUMS`. It installs nothing unless both hold (D-069), using `openssl`.
- installs to `~/.local/bin`, or `/usr/local/bin` as root
- tells you if that directory isn't on your `PATH`

The binaries are GraalVM native images: about 50 MB, they start in about 10 ms, and they need no Java.

### Platform support

| Platform | Prebuilt binary | How |
|---|---|---|
| Linux x64 | ✅ native (static) | `install.sh` |
| Linux arm64 | ✅ native | `install.sh` |
| macOS (Apple silicon / Intel) | ✅ native | `install.sh` |
| Windows x64 | — (portable jar) | `install.ps1` (needs Java 21+) |

GraalVM `native-image` can't cross-compile, so each binary is built on its own platform.

### Run a node

The CLI installs a whole node too — `c93 node install` fetches a signed release, lays out the tree,
writes `node.env` and a service unit, and starts it. See the [node guide](node.md). The shell installer
can hand over to it directly: `… | sh -s -- --node [--system]`.

### Build from source


With a JDK 21+ and Maven, on any platform:

```bash
git clone https://github.com/TonyRice/cluster93.git && cd cluster93
./mvnw -pl cli -am package -DskipTests
java -jar cli/target/cluster93-cli.jar --version   # portable; needs a JVM
```

With GraalVM on `PATH`, add `-Pnative` for a native binary for the host platform:

```bash
./mvnw -Pnative -pl cli -am package -Dmaven.test.skip=true
# -> cli/target/c93   (c93.exe on Windows)
```

Options, as environment variables:

| Variable | |
|---|---|
| `C93_VERSION=v2.1.0` | Pin a release |
| `C93_INSTALL_DIR` | Install somewhere else |
| `C93_RELEASES=owner/repo` | Releases from another GitHub repository |
| `C93_INSTALL_URL` | Download from your own mirror. It's still checked against the release key |
| `C93_RELEASE_PUBLIC_KEY=FILE` | Check signatures with another key, for a mirror that re-signs |
| `C93_INSECURE_SKIP_SIGNATURE=1` | Don't check the signature, trusting HTTPS alone (for a machine without `openssl`) |

`c93 update` keeps an install current: `--cli` replaces the native binary (or the jar it runs from), `--node` re-fetches the node, and with neither flag it does both. `--version TAG` pins a release. It does the same checks itself, with the key built into the CLI, and never runs a downloaded script. (`c93 upgrade` and `c93 node update` are hidden aliases.)

To check a download by hand: `openssl dgst -sha256 -verify cluster93-release.pem -signature SHA256SUMS.sig SHA256SUMS`. The key is [`install/cluster93-release.pem`](../../install/cluster93-release.pem). Maintainers: see [releasing](releasing.md).

> Releases are built when a version tag is pushed and served from this repository's GitHub Releases (D-055). When GitHub Actions is unavailable, Linux binaries can be built and published locally with `scripts/release-local.sh` (see [releasing](releasing.md)).

## Log in

```bash
c93 login https://cluster93.example.com     # asks for the token
echo "$TOKEN" | c93 login https://cluster93.example.com
c93 whoami                                    # tenant acme (jobs:read, jobs:write, …)
```

The URL and token are saved in the active profile (`~/.config/cluster93/config.json` for `default`; `~/.config/cluster93/profiles/<name>/config.json` otherwise), readable only by you. `C93_URL` and `C93_TOKEN`, or `--url` and `--token`, override them, for CI. Use one profile per cluster or dev stack:

```bash
c93 profiles create dev
c93 --profile dev login http://localhost:9393
c93 profiles use dev
c93 profiles                                   # * marks the active one
```

## Commands

| | |
|---|---|
| `run hello.js --input '{"name":"x"}'` | Run a script and follow it. Logs go to **stderr**, the output to **stdout**, and the exit code is 0 only if it succeeded. `-d` returns the job id at once |
| `run … --env NAME=value --env-secret NAME=secret` | Set the script's `process.env`; a secret is read when it runs, and masked in its log ([host APIs](../scripting/host-apis.md#processenv-configuration-you-declare)). `services deploy` takes the same flags |
| `run … --callback URL [--callback-data JSON]` | POST the signed result to `URL` when the job ends ([callbacks guide](callbacks.md)) |
| `callbacks secret [--rotate]` | The secret that signs your callbacks |
| `secrets set NAME [--value V]` · `secrets list` · `secrets rm NAME` | Your secrets ([guide](secrets.md)). `set` reads the value from standard input, or hidden at a terminal; values are never printed |
| `jobs ls · get · logs · steps · cancel` | Inspect jobs. `logs` replays a finished job, or follows a running one starting from its recent lines |
| `services deploy NAME FILE [--config JSON] [--restart on-failure]` | Create or update a service; prints its status and hosts |
| `services ls · get · logs · start · stop · restart · rm` | Manage services. `logs` follows the current run |
| `domains add · verify · ls · rm` | Your own domain for a service. `add` prints the TXT record to publish |
| `files put · get · rm` | Files, streamed both ways. `get NAME -` writes to stdout |
| `files ls [DIR] [-R] · mkdir DIR · rmdir DIR` | One level of a folder (folders end in `/`), or with `-R` every file under it; make a folder; remove an empty one |
| `files mv FROM TO · cp FROM TO [-f] · rm -r DIR` | Move or copy a file or folder (nothing is re-uploaded; copies take no extra room); delete a folder and everything in it |
| `files share PATH [--expires 1h] · files share --revoke-all` | A public link anyone can open until it expires or the file changes; revoke every link you've made |
| `files sync DIR PREFIX [--delete] [--dry-run]` | Mirror a folder, for example a static site. Only new and changed files go up, compared by SHA-256 ETag |
| `packages publish [DIR] [--dry-run] [--no-bundle] · ls · info NAME [VERSION]` | Publish a version of the project in DIR, bundled with its own esbuild; only new files are uploaded ([packages guide](packages.md)) |
| `packages yank · unyank · block · unblock · purge NAME VERSION [--reason …] [--yes]` | When a version goes wrong: skip it, refuse it everywhere, or delete its files ([packages guide](packages.md#when-a-version-goes-wrong)) |
| `deploy [DIR] [--name NAME] [--disabled] [--no-bundle]` | Publish the project (or reuse the version if it's unchanged) and run it as a service; the version is its revision ([packages guide](packages.md#deploy)) |
| `queues push QUEUE JSON [--key K] [--delay-ms MS] [--max-attempts N]` | Queue a message for your services; prints its id. The same `--key` pushes once |
| `queues stats · dead · retry · discard` | A queue's counts, its dead letters (with their last error), and putting one back or deleting it |
| `storage` · `storage providers ls · get · set-s3 · set-disk · check · rm` · `storage routes [set]` | Your own S3 bucket or disk folder, and which paths go there ([storage guide](storage.md)) |
| `storage mounts · mount FOLDER --provider NAME [--read-only] · unmount FOLDER` | Folders bound to a provider; read-only refuses changes beneath it |
| `egress` | Where your scripts may connect: each step of the policy in the order it applies, with why; the cluster's rules and yours; the posture; and the limits (D-108, D-111) |
| `egress cluster [--file POLICY.json] [--posture public\|allow-list]` | Operators only. Show the cluster's egress policy (rules for every tenant, address sets, posture), replace it from a file, or change the posture (D-111) |
| `tokens create NAME --scope jobs:read · ls · revoke` | Your tenant's service tokens. The secret is printed once, to stdout |
| `login URL --invite CODE [--name USER]` · `login --tenant NAME` | Join with an invite, as a new user or as you; switch to another of your tenants ([users guide](users.md)) |
| `invites create --role R [--group G] [--user U]` · `invites ls · rm` | Invite people. The code is printed once. Operators: `--tenant T` (a first owner) or `--operator` |
| `members · members set USER --role R --group G · members rm USER` | Who's in your tenant and what they may do |
| `roles · roles set NAME --scope S · roles rm` · `groups · groups set NAME --role R · groups rm` | Your own roles and groups |
| `cluster audit [--limit N] [--before ID] [--operators]` · `cluster audit verify` | Who changed what, newest first · operators: check the audit chains for tampering (`c93 audit …` remains a hidden alias for one release) |
| `profile [--name N] [--display-name D] [--email E]` | **Deprecated:** use `me` |
| `me [--name N] [--display-name D] [--email E]` | You and your tenants; change your name or details |
| `profiles · profiles use NAME · show · create · rm` | Named connection contexts (URL, token, tenant); `--profile NAME` or `C93_PROFILE` selects one. The default profile keeps the old `config.json` ([D-189](DECISIONS.md)) |
| `cluster users · rename OLD NEW · disable · enable · operator [--remove] · rm` | Operators: every user (`c93 users …` remains a hidden alias for one release) |
| `cluster tenants create · ls · rm · token · tokens · revoke · storage-policy` | Admin only (`c93 tenants …` remains as a hidden alias for one release) |
| `cluster tenants egress TENANT [--allow TO:PORTS …] [--deny TO[:PORTS] …] [--posture public\|allow-list\|inherit] [--none]` | Admin only. A tenant's own rules: `--allow 10.0.5.20/32:5432 --allow db.internal.example:5432,6379`, `--deny *.example.com`, or `--deny 203.0.113.0/24:25`. `TO` can also be `@set` or `*`. `--allow`/`--deny` replace its rules, and `--posture` alone keeps them (D-111) |
| `mcp` | The MCP stdio bridge for AI agents ([MCP guide](mcp.md)) |
| `cluster init --nodes a,b,c [--host a=10.0.0.11] [--api URL]` · `cluster status [--check]` · `cluster doctor` · `cluster nodes` | Operator: create the CA, joins and `cluster.json` inventory; reconcile it with the live nodes; check certificates and drift ([cluster guide](cluster.md)) |
| `node install [--system] [--dir DIR] [--version TAG] [--no-start]` · `node uninstall [--purge]` | Install a node from a signed release (program + `node.env` + a service unit) and start it, or remove it ([node guide](node.md)) |
| `node start · stop · restart · status · logs [--follow] · mcp · update` | Drive the node: its service unit (systemd/launchd), or the process directly. `c93 local …` is a deprecated alias for one release |
| `node env [--set K=V] [--edit]` | Show or change the node's `node.env` (`C93_*` settings, short names accepted) |
| `cluster invite ID [--host ADDR] [--key PUBLIC_KEY]` · `cluster revoke ID` | Operator: a one-line join for a new node (also renews), and record/revoke it in the inventory. `node invite/revoke` remain hidden aliases for one release |
| `node workers release [--worker ID] [--bound-tenant NAME] [--reason TEXT]` | Operator: ask one idle connected worker container to leave; it exits 0 (`released`, not a crash) and its supervisor decides whether to replace it ([D-187](DECISIONS.md)) |
| `version` · `update [--cli|--node] [--version TAG]` | Show the version · update the CLI and/or the node from a signed release (`--cli`, `--node`; neither = both) |

Add `--json` to any command for the API's JSON instead of a summary.
