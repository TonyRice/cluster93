# The `automately` CLI

**Status:** Phase 4, milestone 3 (2026-10-06). Decisions: D-048, D-049, D-053.

## Install

```bash
# Linux and macOS
curl -fsSL https://github.com/adamnlynn/automately-v2/releases/latest/download/install.sh | sh
```
```powershell
# Windows (PowerShell)
irm https://github.com/adamnlynn/automately-v2/releases/latest/download/install.ps1 | iex
```

The installer:
- picks the binary for your platform (Linux x64, a static build that runs on any distro including Alpine; Linux arm64; macOS Apple silicon and Intel; Windows x64)
- **verifies its SHA-256** against the release's `SHA256SUMS`, and installs nothing if it doesn't match
- installs to `~/.local/bin`, or `/usr/local/bin` as root, or `%LOCALAPPDATA%\Programs\automately` on Windows
- tells you if that directory isn't on your `PATH`

The binaries are GraalVM native images: about 50 MB, they start in about 10 ms, and they need no Java.

Options, as environment variables:

| Variable | |
|---|---|
| `AUTOMATELY_VERSION=v2.1.0` | Pin a release |
| `AUTOMATELY_INSTALL_DIR` | Install somewhere else |
| `AUTOMATELY_RELEASES=owner/repo` | Releases from another GitHub repository |
| `AUTOMATELY_INSTALL_URL` | Download from your own mirror |

`automately upgrade` reruns the installer.

> Releases are built by GitHub Actions when a version tag is pushed, and served from this repository's GitHub Releases (D-055). Until the repository is public, its assets need a GitHub login (`gh release download`).

From source: `./mvnw -pl automately-cli -am package -DskipTests`, then `java -jar automately-cli/target/automately-cli.jar`. Add `-Pnative` (with GraalVM) for a native binary.

## Log in

```bash
automately login https://automately.example.com     # asks for the token
echo "$TOKEN" | automately login https://automately.example.com
automately whoami                                    # tenant acme (jobs:read, jobs:write, …)
```

The URL and token are saved in `~/.config/automately/config.json` (`%APPDATA%\automately` on Windows), readable only by you. `AUTOMATELY_URL` and `AUTOMATELY_TOKEN`, or `--url` and `--token`, override them, for CI.

## Commands

| | |
|---|---|
| `run hello.js --input '{"name":"x"}'` | Run a script and follow it. Logs go to **stderr**, the output to **stdout**, and the exit code is 0 only if it succeeded. `-d` returns the job id at once |
| `jobs ls · get · logs · steps · cancel` | Inspect jobs. `logs` replays a finished job, or follows a running one starting from its recent lines |
| `services deploy NAME FILE [--config JSON] [--restart on-failure]` | Create or update a service; prints its status and hosts |
| `services ls · get · logs · start · stop · restart · rm` | Manage services. `logs` follows the current run |
| `domains add · verify · ls · rm` | Your own domain for a service. `add` prints the TXT record to publish |
| `files put · get · ls · rm` | Files, streamed both ways. `get NAME -` writes to stdout |
| `files sync DIR PREFIX [--delete] [--dry-run]` | Mirror a folder, for example a static site. Only new and changed files go up, compared by SHA-256 ETag |
| `tokens create NAME --scope jobs:read · ls · revoke` | Your tenant's tokens. The secret is printed once, to stdout |
| `tenants create · ls · rm · token · tokens · revoke` | Admin only |
| `mcp` | The MCP stdio bridge for AI agents ([MCP guide](mcp.md)) |
| `version · upgrade` | |

Add `--json` to any command for the API's JSON instead of a summary.
