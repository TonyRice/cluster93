# Cluster93 for AI agents (MCP)

**Status:** Phase 4, milestone 4 (2026-10-06). Decisions: D-047, D-054.

Every node serves an MCP server at `/mcp` (Streamable HTTP). An agent connected to it can run scripts, deploy and operate services, manage files, and use the message bus, all as one tenant and limited by that tenant's token scopes.

## Connect

Create a token for the agent, with only the scopes it needs:

```bash
c93 tokens create claude --scope jobs:read --scope jobs:write --scope services:read --scope services:write
```
Leave out `secrets:use` unless the agent's scripts need your [secrets](secrets.md): without it they can run, but `secrets.get` fails with `forbidden`.

There are no MCP tools for users, members or roles: agents don't manage people. Give an agent a service token (`c93 tokens create`) with only the scopes it needs, rather than a person's own token. Leave `members:write` out of it, so the agent can't invite anyone.

**Clients that support remote MCP servers**, for example Claude Code:

```bash
claude mcp add --transport http c93 https://cluster93.example.com/mcp \
  --header "Authorization: Bearer atly_…"
```

**Clients that only launch local processes** use the CLI's stdio bridge. For example, in a desktop client's MCP config:

```json
{
  "mcpServers": {
    "c93": {
      "command": "c93",
      "args": ["mcp"],
      "env": { "C93_URL": "https://cluster93.example.com", "C93_TOKEN": "atly_…" }
    }
  }
}
```

## Tools

Each tool calls the REST API with the agent's token, so a missing scope comes back as a tool error naming it.

| Tool | Scope | |
|---|---|---|
| `run_job` | `jobs:write` | Run a script and wait (by default) for its status, output, error and logs |
| `get_job`, `job_logs`, `list_jobs` | `jobs:read` | `job_logs` returns a running job's recent lines |
| `cancel_job` | `jobs:write` | |
| `deploy_service` | `services:write` | Create or update a service; the result lists its hosts |
| `list_services`, `service_status`, `service_logs` | `services:read` | |
| `start_service`, `stop_service`, `restart_service`, `delete_service` | `services:write` | |
| `write_file`, `delete_file` | `files:write` | Text files |
| `read_file`, `list_files` | `files:read` | Text, up to 256 KB |
| `publish` | `bus:publish` | |
| `request` | `bus:request` | Call a script's `bus.handle` and get its reply |
| `queue_push` | `queues:write` | Push onto a durable queue your services consume |
| `queue_stats`, `queue_dead` | `queues:read` | Counts, and dead letters with their last error |
| `queue_retry` | `queues:write` | Put a dead letter back |
| `publish_package` | `packages:write` | Publish a small package from its files' text; scripts then import it by name |
| `list_packages`, `package_info` | `packages:read` | Yours and the cluster's; one package's versions, or one version's files |

`run_job` and `deploy_service` take a `package` (`{name, version?, export?}`) instead of a `script` to run a published version.

Tools carry MCP annotations (`readOnlyHint`, `destructiveHint`), so clients can ask before destructive ones.

## Resources

The scripting guides, so an agent can learn the script API before writing one:
- `c93://docs/host-apis`
- `c93://docs/job-context`
- `c93://docs/services`

## Protocol notes
- **Versions:** `2025-11-25`, `2025-06-18` and `2025-03-26`. A client asking for another version gets the newest.
- **Transport:** stateless, one JSON-RPC message per `POST`, answers as `application/json`. There are no sessions and no server-initiated stream (`GET /mcp` is 405). Batches are refused, as MCP no longer has them.
- **Auth and limits:** missing or bad tokens get 401 with `WWW-Authenticate: Bearer`, and the admin token gets 403. A tool call counts once against the tenant's rate limit, however many API calls it makes.
