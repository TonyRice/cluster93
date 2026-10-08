# File storage: the cluster's default and tenants' own providers

**Status:** Done (2026-10-07). Decisions: D-056, D-057. Design: [storage-providers](../design/storage-providers.md).

Every file's metadata (path, size, ETag, versions) lives in the cluster's database. Its **bytes** live in one of these:

| Where | Configured by | Counts against |
|---|---|---|
| The cluster's own default: `cluster` (replicated in the cluster), `disk` or `s3` | The operator: `AUTOMATELY_FILES_BACKEND` | The tenant's quota (`AUTOMATELY_TENANT_STORAGE_MB`, with `kv`) |
| A tenant's own **S3-compatible bucket**, with its own keys | The tenant, if the admin allows it | Nothing: their bucket, their bill |
| A tenant's own **disk provider**: a folder under the admin's disk root | The tenant, if the admin allows it | The tenant's disk allotment |

Each file records where its bytes are. Reads always go there, even after routes or defaults change.

## For operators

Choose the default with `AUTOMATELY_FILES_BACKEND`:
- `cluster` needs nothing else.
- `disk` needs `AUTOMATELY_FILES_DIR`, a folder every node mounts.
- `s3` needs `AUTOMATELY_S3_BUCKET` plus an endpoint and keys (see the README).

A node whose backend can't be used refuses to start.

To let tenants bring their own storage:
- **`AUTOMATELY_SECRETS_KEY`** (at least 32 characters, the same on every node) encrypts tenants' S3 secret keys at rest with AES-256-GCM. Without it, S3 providers are unavailable. Changing it makes existing secrets unreadable, so tenants would have to re-enter their keys.
- **`AUTOMATELY_DISK_ROOT`** is where tenant disk providers live (`<root>/<tenant>/<provider>/`). In a cluster it **must be shared storage** (NFS, Ceph or similar), because any node may serve any file, and the storage system is what makes it redundant. The nodes check they all see the same folder, and disk providers stay off until they do.

Then enable provider types per tenant:
```bash
automately tenants storage-policy acme --allow s3 --allow disk --disk-mb 10240
automately tenants storage-policy acme --allow-private-endpoints     # e.g. a MinIO on the LAN
automately tenants storage-policy acme --none                        # turn it all off; existing files stay readable
```
Tenants' S3 endpoints must resolve to public addresses unless their policy allows private ones (or the whole cluster runs with `AUTOMATELY_EGRESS_ALLOW_PRIVATE=true`). The connection goes to the address that was checked.

## For tenants

You need a token with `storage:write` to manage providers and routes, and `storage:read` to view them.

```bash
# Your own bucket (checked against S3 before it's saved; the secret is encrypted and never shown again):
echo "$SECRET" | automately storage providers set-s3 media --endpoint https://s3.eu-west-1.amazonaws.com \
    --region eu-west-1 --bucket my-media --access-key-id AKIA… --secret-stdin

# A folder on the cluster's disk root, within your allotment:
automately storage providers set-disk backups

# Which paths go where: the longest prefix wins; "default" is the cluster's own storage:
automately storage routes set --prefix media/=media --prefix backups/=backups
automately storage                        # quota, disk use, routes at a glance
```

Uploads, scripts (`files.write`), services (`files.stream`) and the CLI all follow the routes. Nothing else changes.

- **Rotating keys:** run `set-s3` again with the new secret. Omit the secret to keep the current one.
- **Deleting a provider:** it must hold no files and have no routes pointing at it.
- **If the admin turns a type off:** new writes there are refused (`403 storage_disabled`), and existing files stay readable.

### Mounts: a folder on a provider (D-078)

A mount binds a folder to one of your providers, so everything written beneath it goes there. It's a route on a folder, shown in listings:

```bash
automately storage mount mnt/photos --provider media          # makes the folder if needed
automately storage mount mnt/archive --provider backups --read-only
automately storage mounts
automately files ls mnt                                       # photos/  mount: media
automately files ls mnt/photos                                # each file shows where its bytes are
automately storage unmount mnt/photos                         # the files stay readable where they are
```

- **Where they go:** anywhere except the top level and the reserved names. `mnt/` is the conventional place, and holds mounts only.
- **Read-only mounts** refuse new files, deletes and moves beneath them, for scripts, the API, the CLI and agents alike. Copying a file *out* still works, since that's reading.
- **Moving a file into or out of a mount** doesn't move its bytes: they stay where they were written, and reads follow them there.
- **Routes:** `storage routes` still works, and shows mounts as prefixes ending in `/`.
- **Reserved names:** `tmp/` (jobs' scratch space), `packages/` and `.packages/` belong to the cluster. Files you already have there stay readable and can be deleted.

## Not exposed to AI agents

The MCP tools don't manage storage providers, so credentials never pass through an agent. Agents' file tools follow your routes like everything else.
