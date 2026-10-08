# Identity: users, groups, roles and the audit log

**Status:** Accepted (Tony, 2026-10-07). Security groundwork, step 0, part 4. Decisions: D-070, D-071.

**API only.** The console's sign-in screens (passkeys, OIDC) come later, on this model.

## Why now
Tokens belong to tenants (D-045), and nothing in the cluster knows who a person is. Upcoming features have rules that need people:
- **Links:** who may deploy to someone's laptop
- **Approvals:** "deploy orders@1.4 to my-laptop?"
- **Secrets:** which secrets leave the cluster, and to which device

Without people, access can't be taken away from someone who leaves either. Today, every token they made has to be found and revoked by hand.

## Tony's decisions
- **Users are global**, one account per person, joined to tenants through **memberships**. A user can also be an **operator**.
- **Sign-in before the console:** **invite codes** that a user redeems for a **personal token**. No passwords are stored. Passkeys and OIDC come with the console.
- **Roles** are built-in ones plus **custom roles** per tenant, all of them sets of the existing scopes. They're granted to members directly or through **groups**.
- **The audit log** comes with this step.

## The model

| | Scope | Holds |
|---|---|---|
| **User** | cluster | `name` (a handle, unique, never changes), `displayName`, `email`, `operator`, `disabled`, `createdAt` |
| **Membership** | tenant × user | `roles` (direct), `groups`, `joinedAt`, `invitedBy` |
| **Custom role** | tenant | `scopes`, `description` |
| **Group** | tenant | `roles`, `description`. Members list their groups on their membership |
| **Invite** | tenant, or the operators | `roles`, `groups`, optionally the `user` it's for, expiry; one use |
| **Token** | as today, plus `user` | A **personal** token has a user; a **service** token (today's kind) doesn't |

### Built-in roles

| Role | Scopes |
|---|---|
| `owner` | `*`, and owner powers: only owners grant or remove `owner`, and a tenant always keeps one |
| `admin` | `*` |
| `developer` | jobs, services, files, bus and queues (read and write), `secrets:read`, `secrets:use`, `nodes:read`, `storage:read`, `members:read` |
| `viewer` | every `:read` scope except `audit:read` |

**New scopes:** `members:read`, `members:write` (members, roles, groups and invites) and `audit:read`.

### What a request may do
- **A personal token's scopes are worked out on every request.** It's the token's own scopes (`*` meaning "all of mine") intersected with what the user's roles give them in that tenant now: direct roles, plus their groups' roles.
- **So access goes with the person:**
  - Removing someone, demoting them, taking them out of a group or disabling them takes effect on their next request, with every token they made.
  - Their personal tokens for a tenant are deleted when they leave it.
- **Service tokens** behave as before: fixed scopes, owned by the tenant, managed with `tokens:write`. Each one records who made it.
- **Operators:**
  - **What they are:** users with `operator: true`. Their operator tokens act as the admin token does (D-044), but as a named person: they manage tenants and users, not tenant data.
  - **Taking it away:** removing the flag stops those tokens at once.
  - **The admin token stays** as the break-glass credential that bootstraps the first operator.

### No escalation
A caller can only grant what they hold themselves.
- **Granting:** to grant a role, change a group's or custom role's scopes, or invite someone, the caller's own scopes must cover both the old and the new scopes. This is the rule tokens already follow (`Scopes.covers`).
- **Other members:** to change or remove one, the caller must cover everything that member has now.
- **`owner`:**
  - Only an owner, or an operator, grants or removes `owner`.
  - It's only ever a direct role, never in a group or a custom role.
  - The last owner can't be removed or demoted.
- **Custom roles:** a role in use can't be deleted. Custom role names can't shadow built-in ones.

## Getting in: invites
1. **Inviting:** an owner or admin (`members:write`) creates an invite with roles and groups, optionally naming a user. The response shows the code once, as `atly_…`; only its hash is stored. Invites expire after 7 days by default (at most 30) and work once.
2. **Redeeming:** the user runs `automately login --invite CODE https://cluster` (`POST /v1/invites/redeem`, a public route).
   - **New user:** they choose a name, or take the one the invite names. The user is created, and they get a personal token for that tenant.
   - **Existing user:** they redeem while logged in (sending their own token). The invite adds a membership, and they get a token for the new tenant.
   - **An invite naming an existing user** can only be redeemed by that user.
3. **More tenants:** a member gets a token for another of their tenants with `POST /v1/me/tokens {tenant}`, or `automately login --tenant other`.
4. **Bootstrapping:**
   - The admin token creates operator invites (`POST /v1/admin/invites`).
   - It also creates a tenant's first owner invite (`POST /v1/tenants/{t}/invites`). Operators can do both too.

## The audit log
- **What's recorded:** every request that changes something and gets past authentication, allowed or refused (403s included).
  - **Each entry:** when, the tenant, the actor (user, token, operator or admin), the operation, the path (without the query), the status, the client address and any `X-Forwarded-For`.
  - **Not recorded:** bus publishes and requests, and queue pushes. They're data traffic, can run thousands a second, and are bounded by rate limits instead.
- **Who sees what:**
  - A tenant reads its own log with `audit:read` (`GET /v1/audit`).
  - Operators read cluster-level entries plus everything operators did (`GET /v1/admin/audit`).
- **Retention:** 90 days (`AUTOMATELY_AUDIT_RETENTION_DAYS`). Deleting a tenant deletes its log.
- **Never in the way:** an entry is written after the response, and a failed write is logged, never turned into a failed request.

## API

| | Who | |
|---|---|---|
| `POST /v1/invites/redeem` | public | `{code, name?, displayName?, email?, tokenName?}` → user, tenant, personal token |
| `GET` `PATCH /v1/me` | personal token | you, and your memberships with their scopes |
| `GET` `POST /v1/me/tokens` · `DELETE /v1/me/tokens/{id}` | personal token | your tokens, for any of your tenants |
| `GET /v1/members` · `GET` `PUT` `DELETE /v1/members/{user}` | `members:read` / `members:write` | `PUT` sets `{roles, groups}` |
| `GET /v1/roles` · `PUT` `DELETE /v1/roles/{name}` | `members:read` / `members:write` | built-in and custom |
| `GET /v1/groups` · `PUT` `DELETE /v1/groups/{name}` | `members:read` / `members:write` | |
| `GET` `POST /v1/invites` · `DELETE /v1/invites/{id}` | `members:read` / `members:write` | |
| `GET /v1/audit` | `audit:read` | newest first, `?limit&before` |
| `GET /v1/users` · `GET` `PATCH` `DELETE /v1/users/{name}` | operator | `PATCH {disabled, operator}` |
| `POST /v1/admin/invites` | operator | invites an operator |
| `GET /v1/tenants/{t}/members` · `POST /v1/tenants/{t}/invites` | operator | a tenant's first owner |
| `GET /v1/admin/audit` | operator | |

**CLI:** `login --invite`, `login --tenant`, `members`, `roles`, `groups`, `invites`, `users`, `audit`. **MCP:** no identity tools; agents don't manage people.

## Storage
- **Five durable tables:** `USERS`, `MEMBERS` (indexed by tenant and user), `TENANT_ACCESS` (one document per tenant holding its custom roles and groups), `INVITES` (by code hash, indexed by tenant and id) and `AUDIT`.
- **Resolving a personal token takes four reads by key:** the token, the user, the membership and the tenant's access document. No SQL runs per request.
- **Identity logic is written once,** against a small `Records` interface (get, put, compare-and-set, list by an indexed field). That interface has an in-memory and an Ignite implementation, with one contract test for both.

## Not in this step
- **Sign-in:** passkeys, OIDC, sessions and cookies come with the console.
- **Renaming users.**
- **Audit export:** to S3, or to a SIEM.
- **Per-resource permissions:** for example, one service only. Links bring the first of these, their deployer lists.
- **A known race:** two owners removing each other at the same moment could leave none. An operator can invite a new owner.
