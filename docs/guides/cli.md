# The `c93` CLI

**Status:** Phase 4, milestone 3 (2026-10-06). Decisions: D-048, D-049, D-053.

## Install

```bash
# Linux and macOS
curl -fsSL https://github.com/TonyRice/cluster93/releases/latest/download/install.sh | sh
```
```powershell
# Windows (PowerShell)
irm https://github.com/TonyRice/cluster93/releases/latest/download/install.ps1 | iex
```

The installer:
- picks the binary for your platform (Linux x64, a static build that runs on any distro including Alpine; Linux arm64; macOS Apple silicon and Intel; Windows x64)
- **checks the release's signature** (`SHA256SUMS.sig`, made with the project's release key, which is built into the installers), then the binary's SHA-256 against the signed `SHA256SUMS`. It installs nothing unless both hold (D-069). On Linux and macOS this uses `openssl`.
- installs to `~/.local/bin`, or `/usr/local/bin` as root, or `%LOCALAPPDATA%\Programs\cluster93` on Windows
- tells you if that directory isn't on your `PATH`

The binaries are GraalVM native images: about 50 MB, they start in about 10 ms, and they need no Java.

Options, as environment variables:

| Variable | |
|---|---|
| `C93_VERSION=v2.1.0` | Pin a release |
| `C93_INSTALL_DIR` | Install somewhere else |
| `C93_RELEASES=owner/repo` | Releases from another GitHub repository |
| `C93_INSTALL_URL` | Download from your own mirror. It's still checked against the release key |
| `C93_RELEASE_PUBLIC_KEY=FILE` | Check signatures with another key, for a mirror that re-signs |
| `C93_INSECURE_SKIP_SIGNATURE=1` | Don't check the signature, trusting HTTPS alone (for a machine without `openssl`) |

`c93 upgrade [--version TAG]` does the same checks itself, with the key built into the CLI, and then replaces its own binary. It doesn't run a downloaded script.

To check a download by hand: `openssl dgst -sha256 -verify cluster93-release.pem -signature SHA256SUMS.sig SHA256SUMS`. The key is [`install/cluster93-release.pem`](../../install/cluster93-release.pem). Maintainers: see [releasing](releasing.md).

> Releases are built by GitHub Actions when a version tag is pushed, and served from this repository's GitHub Releases (D-055). Until the repository is public, its assets need a GitHub login (`gh release download`).

From source: `./mvnw -pl cluster93-cli -am package -DskipTests`, then `java -jar cluster93-cli/target/cluster93-cli.jar`. Add `-Pnative` (with GraalVM) for a native binary.

## Log in

```bash
c93 login https://cluster93.example.com     # asks for the token
echo "$TOKEN" | c93 login https://cluster93.example.com
c93 whoami                                    # tenant acme (jobs:read, jobs:write, …)
```

The URL and token are saved in `~/.config/cluster93/config.json` (`%APPDATA%\cluster93` on Windows), readable only by you. `C93_URL` and `C93_TOKEN`, or `--url` and `--token`, override them, for CI.

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
| `audit [--limit N] [--before ID] [--operators]` · `audit verify` | Who changed what, newest first · operators: check the audit chains for tampering |
| `profile [--name N] [--display-name D] [--email E]` | You and your tenants; change your name or details |
| `users · users rename OLD NEW · disable · enable · operator [--remove] · rm` | Operators: every user |
| `tenants create · ls · rm · token · tokens · revoke · storage-policy` | Admin only |
| `tenants egress TENANT [--allow TO:PORTS …] [--deny TO[:PORTS] …] [--posture public\|allow-list\|inherit] [--none]` | Admin only. A tenant's own rules: `--allow 10.0.5.20/32:5432 --allow db.internal.example:5432,6379`, `--deny *.example.com`, or `--deny 203.0.113.0/24:25`. `TO` can also be `@set` or `*`. `--allow`/`--deny` replace its rules, and `--posture` alone keeps them (D-111) |
| `mcp` | The MCP stdio bridge for AI agents ([MCP guide](mcp.md)) |
| `cluster init --nodes a,b,c [--host a=10.0.0.11]` | Operator: create the cluster's CA and a join line per node. `ca.key` stays with you ([cluster guide](cluster.md)) |
| `node invite ID [--host ADDR] [--key PUBLIC_KEY] --ca ca.key` · `node revoke ID` | Operator: a one-line join for a new node (also renews); revoke a node |
| `version · upgrade` | |

Add `--json` to any command for the API's JSON instead of a summary.
