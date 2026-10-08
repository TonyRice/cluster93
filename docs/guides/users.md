# Users, roles and the audit log

**Status:** Security groundwork, step 0, part 4 (2026-10-07). Decisions: D-070, D-071. Design: [identity](../design/identity.md).

People sign in as **users**. Users join tenants by **invites**, and get what their **roles** give them. Tokens still exist, of two kinds:
- **personal tokens**, which act as their user
- **service tokens**, for CI and machines, which keep their own fixed scopes

There are no passwords. You get in by redeeming an invite, and the console will add passkeys and OIDC later.

## The first owner

As an operator, or with the admin token:

```bash
automately tenants create acme
automately invites create --tenant acme --role owner --user alice    # prints the code once
```

Alice then redeems the code:

```bash
automately login https://automately.example.com --invite atly_…
automately whoami        # alice in tenant acme, an owner (*)
```

## Inviting people

```bash
automately invites create --role developer                 # anyone with the code; they pick a name
automately invites create --role viewer --user bob         # only bob, or a new user named bob
automately invites ls · invites rm inv_…
```

What the invitee runs depends on who they are:
- **Someone new:** `automately login URL --invite CODE --name bob`
- **Someone who already has a user on this cluster:** while logged in there, `automately login URL --invite CODE`. The invite adds the new tenant to their existing user.

**Invites expire** after 7 days by default, at most 30, and **work once**.

## Roles

| Role | May |
|---|---|
| `owner` | Everything (`*`), and owner powers: only owners grant or remove `owner`, and a tenant always keeps one |
| `admin` | Everything (`*`) |
| `developer` | Jobs, services, files, bus and queues; scripts may use secrets (`secrets:use`) but developers don't set them; read nodes, storage and members |
| `viewer` | Read everything except the audit log |

Make your own from scopes, and put people in groups:

```bash
automately roles set release-manager --scope services:read --scope services:write --scope jobs:read
automately groups set backend --role developer
automately members set bob --role viewer --group backend     # replaces bob's roles and groups
automately members                                           # who may do what
```

**Nobody grants more than they hold.**
- **Changing others:** you can only change a member, role, group or invite if your own scopes cover both what it had and what it will have.
- **`owner`:** it is only ever given directly, never through a group.
- **The last owner** can't be removed.

## Access goes with the person

A personal token's scopes are worked out on **every request** from its user's current roles:
- **Demoting someone,** or taking them out of a group, applies to every token they made, on their next request.
- **Removing someone** from a tenant deletes their personal tokens for it.
- **Disabling a user** (`automately users disable bob`, for operators) stops all of their tokens.

**Service tokens** (`automately tokens create`) belong to the tenant, not a person, and keep their fixed scopes. Each records who made it. Revoke them yourself when someone leaves.

## Several tenants

One user can belong to several tenants. `automately login --tenant other-co` switches to another of yours, minting a personal token for it. `GET /v1/me` lists your tenants and what you may do in each.

## Operators

Operators run the cluster as named people, instead of sharing the admin token:

```bash
automately invites create --operator --user carol      # with the admin token, or as an operator
automately users · users operator carol --remove · users disable carol
```

An operator's own token works like the admin token: it manages tenants, users and tokens, but can't read tenant data. Taking away operator stops it at once. Keep the admin token for emergencies.

## The audit log

Every change, and every refused attempt at one, is recorded with who made it, when and from where.

```bash
automately audit                 # your tenant's (needs audit:read)
automately audit --operators     # the cluster's, and everything operators did
```

**What it records:**
- **Each entry:** the user and token, the API operation, the path, the status, the client address, and any `X-Forwarded-For` header.
- **Not recorded:** reads, bus messages and queue pushes.

**How long it lasts:** entries are kept for `AUTOMATELY_AUDIT_RETENTION_DAYS` (90 by default). Deleting a tenant deletes its log.
