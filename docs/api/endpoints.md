# Cluster93 API Reference

Cluster93 provides a contract-first REST API and an MCP (Model Context Protocol) endpoint. Every incoming request is strictly validated against the OpenAPI 3.1 contract defined in [`openapi.yaml`](../../server/src/main/resources/openapi.yaml).

## Authentication & Authorization

Every request to `/v1/*` (except public endpoints like `/v1/openapi.json` and invite redemption) requires an HTTP Bearer token:

```http
Authorization: Bearer <token>
```

- **Admin Token:** Configured via `C93_ADMIN_TOKEN` or generated at initial startup. Manages tenants, operator invites, and cluster storage policies. By design (D-044), the admin token cannot inspect or modify tenant data.
- **Tenant Tokens (`c93_*`):** Scoped to an individual tenant. Scopes include:
  `jobs:read`, `jobs:write`, `services:read`, `services:write`, `files:read`, `files:write`, `bus:publish`, `bus:request`, `queues:read`, `queues:write`, `tokens:write`, `nodes:read`, `storage:read`, `storage:write`, `secrets:read`, `secrets:write`, `secrets:use`, `members:read`, `members:write`, `audit:read`, or `*`.
- **Personal Tokens:** Assigned to invited users with roles (`owner`, `admin`, `developer`, `viewer`) or custom role groups.
- **Storage & Auditing:** Tokens are stored as SHA-256 hashes only. Token lifecycle and configuration changes are recorded in a tamper-evident audit log (`/v1/audit`).

## Rate Limits & Error Handling

- **Rate Limiting:** Managed via an IETF-compliant token bucket per tenant (`C93_RATE_LIMIT_PER_MINUTE`). Responses include `RateLimit-Policy` and `RateLimit` headers; requests exceeding limits receive `429` with `Retry-After`.
- **Validation Errors:** Requests with invalid JSON or failing schema constraints receive `400 invalid_request` with parameter-level error context.
- **Standard Error Envelope:**
  ```json
  {
    "error": {
      "code": "invalid_request",
      "message": "Detailed description of the validation or runtime failure"
    }
  }
  ```

---

## Endpoint Index

### Administration & Tenants

| Method | Path | Required Scope / Role | Description |
|---|---|---|---|
| `POST` | `/v1/tenants` | Admin | Create a new tenant `{name}` |
| `GET` | `/v1/tenants` | Admin | List all tenants in the cluster |
| `GET` | `/v1/tenants/{name}` | Admin | Retrieve tenant metadata |
| `DELETE` | `/v1/tenants/{name}` | Admin | Delete a tenant (must have no active services, files, or kv) |
| `POST` | `/v1/tenants/{name}/tokens` | Admin | Mint a new tenant token `{name, scopes, expiresInDays?}` |
| `GET` | `/v1/tenants/{name}/tokens` | Admin | List tokens for a specific tenant |
| `DELETE` | `/v1/tenants/{name}/tokens/{id}` | Admin | Revoke a tenant token |
| `POST` | `/v1/admin/rotate` | Admin | Rotate the dynamically generated admin token |
| `GET` · `PUT` | `/v1/tenants/{name}/storage-policy` | Admin | Manage allowed storage provider types, disk quotas, private endpoints |
| `GET` · `PATCH` · `DELETE` | `/v1/users/{name}` | Admin | Manage operators: rename, disable, promote/demote |
| `POST` | `/v1/admin/invites` | Admin | Invite an operator |
| `GET` | `/v1/admin/audit` | Admin | Inspect cluster-wide operator audit log |
| `GET` | `/v1/admin/audit/verify` | Admin | Verify cryptographic integrity of the audit chains |

### Authentication, Users & RBAC

| Method | Path | Required Scope / Role | Description |
|---|---|---|---|
| `GET` | `/v1/whoami` | Any valid token | Inspect authenticated identity, tenant, and effective scopes |
| `POST` | `/v1/invites/redeem` | None (Public) | Redeem an invitation code `{code, name?}` for a personal token |
| `GET` · `PATCH` | `/v1/me` | Personal token | Inspect/update user profile and tenant memberships |
| `POST` · `GET` | `/v1/tokens` | `tokens:write` | Mint/list tenant tokens inheriting caller's current scopes |
| `DELETE` | `/v1/tokens/{id}` | `tokens:write` | Revoke a tenant token immediately |
| `GET` · `PUT` · `DELETE` | `/v1/members/{user}` | `members:read` / `members:write` | View or assign tenant member roles and groups |
| `GET` · `PUT` · `DELETE` | `/v1/roles/{name}` | `members:write` | Define or update custom tenant roles `{scopes}` |
| `GET` · `PUT` · `DELETE` | `/v1/groups/{name}` | `members:write` | Define or update role groups `{roles}` |
| `GET` · `POST` · `DELETE` | `/v1/invites` | `members:write` | Create, list, or revoke member invitations |
| `GET` | `/v1/audit` | `audit:read` | Read tenant tamper-evident audit trail (`?limit&before`) |

### Jobs & Workflows

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `POST` | `/v1/jobs` | `jobs:write` | Submit a script `{script, input?, timeoutMs?, onOrphan?, maxAttempts?}` |
| `GET` | `/v1/jobs` | `jobs:read` | List tenant jobs, ordered newest first (`?limit&status`) |
| `GET` | `/v1/jobs/{id}` | `jobs:read` | Retrieve job details, including attempt history |
| `GET` | `/v1/jobs/{id}/steps` | `jobs:read` | Inspect durable attempt checkpoints and completed steps |
| `GET` | `/v1/jobs/{id}/events` | `jobs:read` | Server-Sent Events (SSE) stream: live `log`, `attempt`, and `end` |
| `POST` | `/v1/jobs/{id}/cancel` | `jobs:write` | Gracefully cancel a job (`ctx.stopRequested`) |
| `GET` · `POST` | `/v1/callbacks/secret` | `jobs:write` | Get or rotate the signing secret for job completion callbacks |

### Supervised Services & Ingress

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `PUT` | `/v1/services/{name}` | `services:write` | Define/update service `{script, config?, restart?, enabled?}` |
| `GET` | `/v1/services` | `services:read` | List services with supervisor status, runs, and current job ID |
| `GET` | `/v1/services/{name}` | `services:read` | Get details and status for a single service |
| `POST` | `/v1/services/{name}/start` | `services:write` | Start an enabled service |
| `POST` | `/v1/services/{name}/stop` | `services:write` | Gracefully stop a running service |
| `POST` | `/v1/services/{name}/restart` | `services:write` | Trigger rolling restart with state carryover |
| `DELETE` | `/v1/services/{name}` | `services:write` | Stop and delete service (releases custom domains) |
| `POST` · `GET` | `/v1/services/{name}/domains` | `services:write` | Claim custom domain (returns DNS TXT verification token) |
| `POST` | `/v1/services/{name}/domains/{domain}/verify` | `services:write` | Check DNS TXT record and enable domain routing |
| *Ingress* | Port `9394` (Any path) | None / Service Auth | Ingress HTTP/WebSocket traffic routed by Host header to service |

### Messaging, Bus & Durable Queues

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `POST` | `/v1/bus/{topic}` | `bus:publish` | Publish JSON payload to cluster-wide topic |
| `POST` | `/v1/bus/{topic}/request` | `bus:request` | Send request to topic handler and await reply |
| `POST` | `/v1/queues/{queue}` | `queues:write` | Push durable message onto leased queue |
| `GET` | `/v1/queues/{queue}` | `queues:read` | Inspect queue message counts (ready, in-flight, dead) |
| `GET` | `/v1/queues/{queue}/dead` | `queues:read` | List dead-letter queue entries |
| `POST` | `/v1/queues/{queue}/dead/retry` | `queues:write` | Re-enqueue dead letters |

### Virtual File System (VFS) & Storage

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `PUT` | `/v1/files/{path}` | `files:write` | Stream upload file bytes (supports up to tenant quota) |
| `GET` · `HEAD` | `/v1/files/{path}` | `files:read` | Stream download with HTTP `Range` (206) and `ETag` (304) |
| `GET` | `/v1/files` | `files:read` | List files (`?prefix=&limit=&cursor=`) |
| `DELETE` | `/v1/files/{path}` | `files:write` | Delete a file |
| `GET` · `PUT` · `DELETE` | `/v1/dirs/{path}` | `files:read` / `files:write` | View directory contents, create directories, or delete |
| `POST` | `/v1/fs/move` | `files:write` | Atomic zero-copy rename/move `{from, to, overwrite}` |
| `POST` | `/v1/fs/copy` | `files:write` | Copy file or directory sharing underlying storage blocks |
| `POST` | `/v1/fs/share` | `files:write` | Generate public time-limited share link `{path, expiresInSeconds}` |
| `GET` | `/v1/shared/{token}` | None (Public) | Download public shared file before expiry |
| `GET` | `/v1/storage` | `storage:read` | Check tenant storage usage, quotas, and active mounts |
| `GET` · `PUT` · `DELETE` | `/v1/storage/providers/{name}` | `storage:write` | Configure custom S3 bucket or local disk directory provider |
| `GET` · `PUT` · `DELETE` | `/v1/mounts/{path}` | `storage:write` | Mount storage provider to specific VFS path prefix |

### Packages & JavaScript Registry

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `POST` | `/v1/packages` | `packages:write` | Initialize package publication with manifest |
| `PUT` | `/v1/packages/blobs/{hash}` | `packages:write` | Upload missing content-addressable code blobs |
| `POST` | `/v1/packages/{name}/versions/{version}/commit` | `packages:write` | Commit version manifest once all blobs are verified |
| `GET` | `/v1/packages` | `packages:read` | List published packages and versions |
| `GET` | `/v1/packages/{name}/{version}` | `packages:read` | Inspect package metadata and manifest |

### Tenant Secrets

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `GET` | `/v1/secrets` | `secrets:read` | List secret names and versions (values are never returned) |
| `GET` | `/v1/secrets/{name}` | `secrets:read` | Inspect metadata for an individual secret |
| `PUT` | `/v1/secrets/{name}` | `secrets:write` | Set encrypted secret value `{value}` |
| `DELETE` | `/v1/secrets/{name}` | `secrets:write` | Delete a tenant secret |

### Cluster Operations & Health

| Method | Path | Required Scope | Description |
|---|---|---|---|
| `GET` | `/v1/openapi.json` | None (Public) | OpenAPI 3.1 specification contract |
| `POST` | `/mcp` | Tenant Token | Streamable HTTP MCP endpoint for AI agents |
| `GET` | `/v1/nodes` | `nodes:read` or Admin | Live cluster nodes, capacity, and current workload |
| `GET` | `/health` | None (Public) | Process liveness check |
| `GET` | `/ready` | None (Public) | Cluster readiness check (returns 200 once active) |
