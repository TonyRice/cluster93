# Secrets

Keep API keys, database URLs and other credentials out of your scripts and service config. Store them as named secrets, and read them in scripts with `secrets.get(name)` (D-068).

```bash
automately secrets set STRIPE_KEY          # typed, hidden; or: printf %s "$KEY" | automately secrets set STRIPE_KEY
automately secrets list                    # names and versions, never values
automately secrets rm STRIPE_KEY
```
```js
export default async () => {
  const key = await secrets.get('STRIPE_KEY');   // the value, or null if there's no such secret
  const res = await fetch('https://api.stripe.com/v1/balance', { headers: { Authorization: `Bearer ${key}` } });
  return res.status;
};
```

## Who can do what
| Scope | Allows |
|---|---|
| `secrets:write` | Setting and deleting secrets |
| `secrets:read` | Listing names and versions. **Nobody can read a value through the API.** |
| `secrets:use` | Scripts may call `secrets.get` |

**Which scripts can call `secrets.get`:**
- **A job's script,** if the token that submitted the job had `secrets:use`.
- **A service's runs,** if the token that last defined the service had `secrets:use`. Redefining it with a token that doesn't have it is a new revision that can't read them.
- **A job started from a script** (`jobs.run`), if the job that started it could.

Any other script gets an error with code `forbidden`. A job's `secrets` field shows whether it may read them; so does a service's.

**Why it's a separate scope:** a token that can run jobs could otherwise print every secret. Give an AI agent `jobs:write` without `secrets:use`, and it can run code but can't read your credentials. Its jobs fail with `forbidden` if they try.

## How they're kept
- **Sealed:** every value is encrypted with AES-256-GCM, using a key derived from **`AUTOMATELY_SECRETS_KEY`** (at least 32 characters, the same on every node).
- **Bound to their place:** each value is sealed with its tenant and name, so a copy under another name or tenant doesn't open.
- **Replicated:** they live in the cluster's replicated store, under a system namespace that no token can reach.
- **Without the key there are no secrets:** the API answers 409 `secrets_unavailable`, and `secrets.get` fails with `unavailable`. Values are never stored in the clear.
- **Changing the key** makes stored secrets unreadable. Set them again afterwards. Back the key up.
- **Limits:** 64 KiB per value and 500 secrets per tenant. Names are 1–128 letters, digits, `_`, `.` or `-`.
- **Deleting a tenant** deletes its secrets.

## Things to know
- **A script can print what it reads,** and job logs and output are visible with `jobs:read`. Don't log secrets.
- **Not over MCP:** there are no MCP tools for secrets, so values don't pass through an agent.
