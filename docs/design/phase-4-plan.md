# Plan: API, auth, CLI and MCP (phase 4)

**Status:** Planned · **Date:** 2026-10-06
**Why:** Weak spots #4 (routing by hand, no contract) and #7 (Basic auth with a long-lived key) from the [revival plan](revival-plan.md), plus the part of the positioning that makes Automately current: AI agents can deploy and run code on it through MCP. The `X-Automately-Tenant` header was always a stand-in. Anyone who can reach the API can act as any tenant, so it has to go before anything else is exposed.

## Decisions taken with Tony
- **Bootstrap:** an admin token from `AUTOMATELY_ADMIN_TOKEN`, or generated once by the first node and printed in its log. It manages tenants and tokens only (D-044).
- **Contract-first OpenAPI:** a hand-written spec is the source of truth. It's served by the API, requests are validated against it, and tests fail when a route and the spec disagree (D-046).
- **MCP:** built into every node over Streamable HTTP at `/mcp`, using the same tokens, plus `automately mcp`, a stdio bridge in the CLI for clients that only launch local processes (D-047).
- **CLI:** *proposed, Tony to confirm:* Java and picocli, compiled with GraalVM native-image into single binaries (no JDK needed), released on GitHub per platform, with npm wrapper packages possible later.

## Milestone 1: tenants and scoped tokens
- **Tenants become explicit.** `POST /v1/tenants {name}`, `GET /v1/tenants`, `DELETE /v1/tenants/{name}` (refused while it still has services or files). They're admin only. Names follow the existing rule (they're part of hostnames).
- **Tokens.**
  - Format: `atly_` + 32 random bytes (base62) + a 6-character checksum. The prefix lets secret scanners (GitHub, gitleaks) recognise a leaked token, and the checksum lets them tell a real one from a random string without calling us.
  - Storage: only `SHA-256(token)`. The tokens are 256-bit random, so a slow hash adds nothing. GitHub stores its tokens this way too.
  - Record: `id` (`tok_…`, public), tenant, name, scopes, `createdAt`, optional `expiresAt`, `lastUsedAt` (written at most once a minute per token, not per request).
  - API: `POST /v1/tokens {name, scopes, expiresInDays?}` returns the token **once**. `GET /v1/tokens` lists without secrets. `DELETE /v1/tokens/{id}` revokes. A tenant token with `tokens:write` can mint tokens for its own tenant with a subset of its own scopes. The admin token can mint for any tenant.
- **Scopes:** `jobs:read`, `jobs:write`, `services:read`, `services:write` (includes domains), `files:read`, `files:write`, `bus:publish`, `bus:request`, `tokens:write`, `nodes:read`, and `*` for every tenant scope. A missing scope gets **403** with the scope named. A missing or bad token gets **401** with `WWW-Authenticate: Bearer`.
- **`Authorization: Bearer atly_…`** replaces `X-Automately-Tenant` everywhere. The header is no longer read. `/health` stays open. The ingress (services' public traffic) is unaffected, since services do their own auth.
- **Admin token.** If `AUTOMATELY_ADMIN_TOKEN` is set, every node compares against it in constant time. Otherwise the first node of a new cluster generates one, stores only its hash in the cluster, and logs it once ("save this; it won't be shown again"). `POST /v1/admin/rotate` replaces it.
- **Stores:** `TenantStore` and `TokenStore`, each with SharedData and Ignite implementations and shared contract tests, as for the other stores.
- **Tests:** scope enforcement on every route (a test that walks the route table, so a new route without a scope fails), revocation taking effect immediately, expiry, a tenant unable to see another's tokens or data, the admin token unable to read tenant data, and the checksum format. Every existing test moves to tokens.

## Milestone 2: the OpenAPI contract, validation and rate limits
- **Spec:** `automately-server/src/main/resources/openapi.yaml` (OpenAPI 3.1), served at `GET /v1/openapi.json`. It covers every route, the error envelope (`{"error": {"code", "message"}}`), the scopes as a security scheme, and pagination (`limit` and `cursor` everywhere).
- **Validation:** requests are validated against the spec with `vertx-openapi`, before handlers run, and bad input gets 400 with the schema path. Streamed bodies (file uploads, SSE) are validated on headers and parameters only, never buffered.
- **Contract tests:** a test fails if the router has a route the spec lacks, or the other way round. Integration tests validate real **responses** against the spec, so the docs can't drift.
- **Rate limits per tenant:** a token bucket (`AUTOMATELY_RATE_LIMIT_PER_MINUTE`, default 600, burst 2× that), applied on each node with its share of the tenant's budget (budget ÷ live nodes), so no cluster round-trip per request. Over it, **429** with `Retry-After` and the IETF `RateLimit-Policy`/`RateLimit` headers. Admin calls aren't limited.

## Milestone 3: the CLI (`automately`)
- `automately login <url>` stores the endpoint and token in `~/.config/automately/config.json` (mode 600). `AUTOMATELY_URL` and `AUTOMATELY_TOKEN` override it, for CI.
- `run script.js [--input json] [--follow]` submits a job and streams its logs until it ends; the exit code reflects the outcome.
- `jobs ls|get|logs|steps|cancel`, `services deploy <name> <file>|ls|get|logs|start|stop|restart|rm`, `domains add|verify|ls|rm`, `files put|get|ls|rm|sync <dir> <prefix>` (deploys a static site: only changed files go up, compared by ETag), `tokens create|ls|revoke`, `tenants …` (admin).
- `mcp` is the stdio bridge (milestone 4).
- Built per D-048 (pending), with the JDK HTTP client and a small JSON library so native-image works without fuss. It's tested against a real node in the build, and the native binary gets a smoke test in Docker.

## Milestone 4: the MCP server
- **Transport:** MCP Streamable HTTP at `/mcp` on the API port: JSON-RPC 2.0 over `POST`, with server-sent events for streamed results, and `Authorization: Bearer` with the same tokens and scopes. Implemented directly on Vert.x (the protocol surface is small) rather than pulling in the Reactor-based Java SDK, and checked against the official MCP inspector.
- **Tools**, each requiring the matching scope:
  - `run_job` (script and input; waits up to a timeout and returns the output, logs and status)
  - `get_job`, `job_logs`, `cancel_job`
  - `deploy_service`, `list_services`, `service_status`, `service_logs`, `restart_service`, `stop_service`
  - `write_file`, `read_file` (text, size-capped), `list_files`, `delete_file`
  - `publish`, `request` (bus)
- **Resources:** the scripting docs (`job-context.md`, `services.md`, `host-apis.md`) as MCP resources, so an agent can learn the script API before writing code.
- **Bridge:** `automately mcp` speaks stdio to the client and forwards to `/mcp` with the stored token.
- **Tests:** a protocol test (initialize, tools/list, tools/call, errors, a missing scope), and an end-to-end test where an MCP client deploys a service and calls it through the ingress.

## Order and size
Milestones 1, 2, 3, 4, in that order. Each ends green on the full `./mvnw verify` (twice) and is documented and pushed on its own. Milestone 1 changes every API call, so it goes first, and later milestones build on its scopes.
