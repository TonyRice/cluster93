# Plan: API, auth, CLI and MCP (phase 4)

**Status:** ✅ Done: milestones 1 to 4 (2026-10-06). CLI releases are built by GitHub Actions on tags and downloaded from GitHub Releases once the repo is public (D-055) · **Date:** 2026-10-06
**Why:** Weak spots #4 (routing by hand, no contract) and #7 (Basic auth with a long-lived key) from the [revival plan](revival-plan.md), plus the part of the positioning that makes Cluster93 current: AI agents can deploy and run code on it through MCP. The `X-Cluster93-Tenant` header was always a stand-in. Anyone who can reach the API can act as any tenant, so it has to go before anything else is exposed.

## Decisions taken with Tony
- **Bootstrap:** an admin token from `C93_ADMIN_TOKEN`, or generated once by the first node and printed in its log. It manages tenants and tokens only (D-044).
- **Contract-first OpenAPI:** a hand-written spec is the source of truth. It's served by the API, requests are validated against it, and tests fail when a route and the spec disagree (D-046).
- **MCP:** built into every node over Streamable HTTP at `/mcp`, using the same tokens, plus `c93 mcp`, a stdio bridge in the CLI for clients that only launch local processes (D-047).
- **CLI:** Java and picocli, compiled with GraalVM native-image into one binary per platform (Windows, Linux, macOS; no JDK needed), installed with a one-line `curl … | sh` or `irm … | iex` (D-048, D-049).

## Milestone 1: tenants and scoped tokens
- **Tenants become explicit.** `POST /v1/tenants {name}`, `GET /v1/tenants`, `DELETE /v1/tenants/{name}` (refused while it still has services or files). They're admin only. Names follow the existing rule (they're part of hostnames).
- **Tokens.**
  - Format: `c93_` + 32 random bytes (base62) + a 6-character checksum. The prefix lets secret scanners (GitHub, gitleaks) recognise a leaked token, and the checksum lets them tell a real one from a random string without calling us.
  - Storage: only `SHA-256(token)`. The tokens are 256-bit random, so a slow hash adds nothing. GitHub stores its tokens this way too.
  - Record: `id` (`tok_…`, public), tenant, name, scopes, `createdAt`, optional `expiresAt`, `lastUsedAt` (written at most once a minute per token, not per request).
  - API: `POST /v1/tokens {name, scopes, expiresInDays?}` returns the token **once**. `GET /v1/tokens` lists without secrets. `DELETE /v1/tokens/{id}` revokes. A tenant token with `tokens:write` can mint tokens for its own tenant with a subset of its own scopes. The admin token can mint for any tenant.
- **Scopes:** `jobs:read`, `jobs:write`, `services:read`, `services:write` (includes domains), `files:read`, `files:write`, `bus:publish`, `bus:request`, `tokens:write`, `nodes:read`, and `*` for every tenant scope. A missing scope gets **403** with the scope named. A missing or bad token gets **401** with `WWW-Authenticate: Bearer`.
- **`Authorization: Bearer c93_…`** replaces `X-Cluster93-Tenant` everywhere. The header is no longer read. `/health` stays open. The ingress (services' public traffic) is unaffected, since services do their own auth.
- **Admin token.** If `C93_ADMIN_TOKEN` is set, every node compares against it in constant time. Otherwise the first node of a new cluster generates one, stores only its hash in the cluster, and logs it once ("save this; it won't be shown again"). `POST /v1/admin/rotate` replaces it.
- **Stores:** `TenantStore` and `TokenStore`, each with SharedData and Ignite implementations and shared contract tests, as for the other stores.
- **Tests:** scope enforcement on every route (a test that walks the route table, so a new route without a scope fails), revocation taking effect immediately, expiry, a tenant unable to see another's tokens or data, the admin token unable to read tenant data, and the checksum format. Every existing test moves to tokens.

## Milestone 2: the OpenAPI contract, validation and rate limits
- **Spec:** `server/src/main/resources/openapi.yaml` (OpenAPI 3.1), served at `GET /v1/openapi.json`. It covers every route, the error envelope (`{"error": {"code", "message"}}`), the scopes as a security scheme, and pagination (`limit` and `cursor` everywhere).
- **Validation:** requests are validated against the spec with `vertx-openapi`, before handlers run, and bad input gets 400 with the schema path. Streamed bodies (file uploads, SSE) are validated on headers and parameters only, never buffered.
- **Contract tests:** a test fails if the router has a route the spec lacks, or the other way round. Integration tests validate real **responses** against the spec, so the docs can't drift.
- **Rate limits per tenant:** a token bucket (`C93_RATE_LIMIT_PER_MINUTE`, default 600, burst 2× that), applied on each node with its share of the tenant's budget (budget ÷ live nodes), so no cluster round-trip per request. Over it, **429** with `Retry-After` and the IETF `RateLimit-Policy`/`RateLimit` headers. Admin calls aren't limited.

## Milestone 3: the CLI (`c93`), installable with one line
**Tony's requirements:** "super easy to install the cli via curl", with builds for **Windows, Linux and macOS**, and a one-line install.

### Installing
```bash
# Linux and macOS
curl -fsSL https://<release host>/install.sh | sh
```
```powershell
# Windows (PowerShell)
irm https://<release host>/install.ps1 | iex
```
- **`install.sh`** (POSIX `sh`, so it runs anywhere):
  - detects the OS (`uname -s`) and architecture (`uname -m`)
  - downloads the matching binary and `SHA256SUMS` from the latest release, and **verifies the checksum** (stopping if it doesn't match)
  - installs to `~/.local/bin`, or `/usr/local/bin` when run as root or with `C93_INSTALL_DIR`, and says how to add that to `PATH` if it isn't already
  - `C93_VERSION=v2.1.0` pins a version
  - is written so a cut-off download can't run half a script: everything is inside a function called on the last line
- **`install.ps1`**: the same steps for Windows. It installs to `%LOCALAPPDATA%\Programs\cluster93` and adds that directory to the user's `PATH`.
- **Afterwards:** `c93 version` shows the CLI's version and, once logged in, the cluster's. `c93 upgrade` reruns the installer for the latest version.
- **Not in the first cut:** Homebrew, Scoop and winget packages, and npm wrappers. All of them can point at the same release assets later.

### Builds: one native binary per platform (D-048)
| Target | Built on | Asset |
|---|---|---|
| Linux x64 | `ubuntu-latest` (static against musl, so it runs on any distro, Alpine included) | `cluster93-linux-x64` |
| Linux arm64 | `ubuntu-24.04-arm` | `cluster93-linux-arm64` |
| macOS Apple silicon | `macos-14` | `cluster93-macos-arm64` |
| macOS Intel | `macos-13` | `cluster93-macos-x64` |
| Windows x64 | `windows-latest` | `cluster93-windows-x64.exe` |

- **Why native.** Each binary is GraalVM native-image output, about 20–40 MB, starts in milliseconds, and needs no JDK.
- **Why build on each platform.** native-image can't cross-compile, so each platform needs a machine of its own. Linux x64 can also be built here, in a GraalVM container, for local testing.
- **The release workflow** runs on a version tag (`v*`) only: it builds the five binaries in parallel, smoke-tests each (`c93 version`, then `c93 run` against a real node on Linux), and publishes a GitHub release with the binaries, `SHA256SUMS`, `install.sh` and `install.ps1`.
- **macOS and Windows warnings.** Binaries installed with `curl` or `irm` aren't marked as downloaded, so Gatekeeper and SmartScreen don't block them. Signing and notarization can come later, for downloads through a browser.

### Open questions for Tony
1. **The release workflow needs GitHub Actions** for the macOS and Windows runners. It would run only on version tags, not on every push, so it isn't CI and D-017 stands. Is that OK?
2. **Where the one-liner downloads from.** Assets in a **private** repo need a GitHub token to download, so `curl … | sh` can't fetch them anonymously. The options:
   - (a) make `cluster93` public when releasing
   - (b) publish releases to a separate public repo (for example `TonyRice/cluster93-releases`), keeping the source private
   - (c) serve `install.sh` from a short domain you own, pointing at (a) or (b)

### Commands
- `c93 login <url>` stores the endpoint and token in `~/.config/cluster93/config.json` (`%APPDATA%\cluster93` on Windows), readable only by the user. `C93_URL` and `C93_TOKEN` override it, for scripts and CI.
- `run script.js [--input json] [--follow]` submits a job and streams its logs until it ends; the exit code reflects the outcome.
- `jobs ls|get|logs|steps|cancel`, `services deploy <name> <file>|ls|get|logs|start|stop|restart|rm`, `domains add|verify|ls|rm`, `files put|get|ls|rm|sync <dir> <prefix>` (deploys a static site: only changed files go up, compared by ETag), `tokens create|ls|revoke`, `tenants …` (admin), `version`, `upgrade`.
- `mcp` is the stdio bridge (milestone 4).
- **Built with** picocli, the JDK HTTP client and a small JSON library, so native-image needs no special configuration. It's tested against a real node in the normal build, and the Linux native binary gets a smoke test in Docker.

## Milestone 4: the MCP server
- **Transport:** MCP Streamable HTTP at `/mcp` on the API port: JSON-RPC 2.0 over `POST`, with server-sent events for streamed results, and `Authorization: Bearer` with the same tokens and scopes. Implemented directly on Vert.x (the protocol surface is small) rather than pulling in the Reactor-based Java SDK, and checked against the official MCP inspector.
- **Tools**, each requiring the matching scope:
  - `run_job` (script and input; waits up to a timeout and returns the output, logs and status)
  - `get_job`, `job_logs`, `cancel_job`
  - `deploy_service`, `list_services`, `service_status`, `service_logs`, `restart_service`, `stop_service`
  - `write_file`, `read_file` (text, size-capped), `list_files`, `delete_file`
  - `publish`, `request` (bus)
- **Resources:** the scripting docs (`job-context.md`, `services.md`, `host-apis.md`) as MCP resources, so an agent can learn the script API before writing code.
- **Bridge:** `c93 mcp` speaks stdio to the client and forwards to `/mcp` with the stored token.
- **Tests:** a protocol test (initialize, tools/list, tools/call, errors, a missing scope), and an end-to-end test where an MCP client deploys a service and calls it through the ingress.

## Order and size
Milestones 1, 2, 3, 4, in that order. Each ends green on the full `./mvnw verify` (twice) and is documented and pushed on its own. Milestone 1 changes every API call, so it goes first, and later milestones build on its scopes.

## What changed during milestone 1
- **Auth runs before the body is read.** The token lookup is asynchronous, and vertx-web doesn't pause requests for that. The auth handler pauses the request, and each route rule says whether that route reads its body. Body-reading routes get the request still paused, for their body handler or the streamed upload. The rest are resumed, so keep-alive connections never stall. `AuthTest` sends a 200 KB script and 90 mixed requests on pooled connections to check this.
- **Route rules fail closed.** A `/v1` request that matches no rule gets 404 before any handler runs. `AuthTest` walks the live route table, and every route must answer 401 without a token.
- **Deleting a tenant requires it to be empty** (no services, files or kv). Its finished jobs are kept until retention removes them.
- **`/v1/nodes`** needs `nodes:read` or the admin token. It was open before.
- **Tenant names are checked when a tenant is created**, by the admin, instead of on every request.

## What changed during milestone 2
- **Validation is wired into the existing routes, not RouterBuilder** (D-050). Each route rule names its contract operation and body mode; auth, rate limiting, body reading and validation run in one chain before the handler.
- **JSON bodies are validated as JSON whatever their Content-Type**, so `curl -d` keeps working.
- **Binary bodies have no schema**, and server-sent events are registered as text, because `vertx-openapi` can't run a JSON schema over raw bytes.
- **Rate limits use each node's share** of the tenant's budget (D-051). The default is 1 200 a minute rather than the planned 600, since a CLI following a job polls.
- **Early answers close with a lingering close** (D-052), found when a 413 for an oversized upload sometimes arrived as a reset.

## What changed during milestones 3 and 4
- **The CLI as built:** picocli with Jackson's tree model and the JDK HTTP client (D-053). The static Linux x64 binary is 50 MB rather than the estimated 20–40 MB, and starts in about 10 ms.
- **Recent log lines on connect.** `services logs` and the MCP `service_logs` tool needed a running service's recent output. Event streams of running jobs now begin with their recent lines, fetched from the node running them and numbered so live events aren't repeated. This shipped in milestone 2's commit.
- **MCP tools act through the REST API** with the caller's token over loopback (D-054), instead of duplicating each operation. Loopback calls are exempt from the rate limit, so a tool call costs one request.
- **MCP is stateless:** no sessions and no server-initiated stream. That fits tools that answer within one request.
- **The release workflow exists but is inert** until a version tag is pushed. Where public downloads come from is still Tony's call (D-049).

