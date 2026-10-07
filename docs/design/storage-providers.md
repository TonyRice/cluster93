# Design and plan: file storage providers

**Status:** Step 1 ✅ done (2026-10-07); step 2 next · **Date:** 2026-10-07
**Why:** The original Automately Core had a pluggable `VirtualFileStore`: local disk by default, `aws` for S3, Azure Blob, or any class, chosen in config. The rewrite stored files only inside the cluster (D-040). Tony asked for providers back, configurable **per tenant with the tenant's own credentials**, plus a global default from the environment.

## Decisions taken with Tony (2026-10-07)
- **Backends now:** S3-compatible (AWS S3, MinIO, Cloudflare R2, Backblaze B2, Wasabi, GCS through its S3 keys) and disk, next to the cluster default. Azure and native GCS can come later.
- **Tenants can bring their own providers:** S3-compatible with their own bucket and keys, and disk under an admin-configured root with an allotment.
- **The admin enables or disables provider types per tenant.**
- **Only cluster-hosted bytes** count against `AUTOMATELY_TENANT_STORAGE_MB`. Disk has its own allotment, and a tenant's own bucket isn't counted.
- **Routing:** each tenant has a default provider plus path-prefix rules, and the longest prefix wins.
- **Endpoints must be public** unless the admin allows private ones, the same rule as `fetch` (D-026).

## The shape (D-056)
- **Metadata stays in the cluster database:** paths, versions, the atomic swap to a new version, garbage collection and quotas. Only the bytes move.
- **A `BlobStore`** holds bytes: `create(blobId)` returns a streaming writer, and `open(blobId, range)`, `delete(blobId)` and `check()` do the rest. A blob is one version of one file, written once.
- **Each file records its provider** (`backend` in its metadata), so it's always read from where it was written, even if the defaults change later.
- **Backends:**
  - `cluster` (default): 1 MB chunks in the replicated store (D-040)
  - `disk`: one file per blob under a folder, written to `.part`, flushed to disk, then renamed. Free space is checked as it writes.
  - `s3`: one object per blob, multipart in 8 MiB parts (one PUT for small blobs), ranged GETs for reads, and deleting aborts unfinished uploads. It uses a small SigV4 client of our own on Vert.x's HTTP client: the AWS SDK brings its own Netty, which would clash with Vert.x's.

## Step 1: the global default
- `AUTOMATELY_FILES_BACKEND` = `cluster` | `disk` | `s3`.
- **Disk:** `AUTOMATELY_FILES_DIR`. In a multi-node cluster, every node must mount the same folder.
- **S3:**
  - `AUTOMATELY_S3_BUCKET` (required)
  - `AUTOMATELY_S3_ENDPOINT` (default AWS for the region)
  - `AUTOMATELY_S3_REGION` (default `AWS_REGION`, then `us-east-1`)
  - `AUTOMATELY_S3_PREFIX` (default `automately/`)
  - `AUTOMATELY_S3_PATH_STYLE` (default on with a custom endpoint)
  - `AUTOMATELY_S3_ACCESS_KEY_ID` and `AUTOMATELY_S3_SECRET_ACCESS_KEY` (or the standard `AWS_*` variables, plus `AWS_SESSION_TOKEN`)
- A node checks its backend at start, and won't start if the bucket can't be reached or the folder isn't writable.
- **Tests:** a contract every backend passes (ranges, multipart sizes, abort, delete, missing blobs), run against MinIO in Docker and a temporary folder. The file-store contract runs on each backend, and a two-node cluster serves files from MinIO.

## Step 2: tenant providers (D-057)
- **Providers are named resources of a tenant** (`/v1/storage/providers`), managed with a new `storage:write` scope:
  - `{name, type: "s3", endpoint, region, bucket, prefix, pathStyle, accessKeyId, secretAccessKey}`, or
  - `{name, type: "disk"}`, which gets `<AUTOMATELY_DISK_ROOT>/<tenant>/<name>/`, chosen by the server so a tenant never names a host path
- **Credentials are write-only and encrypted at rest:** AES-256-GCM with a cluster key (`AUTOMATELY_SECRETS_KEY`, required to enable tenant providers). The API never returns them, and a provider's id stays the same when its keys rotate.
- **Routing:** `PUT /v1/storage/routes` `{default: "my-s3", prefixes: {"media/": "my-s3", "cache/": "cluster"}}`. Without routes, files go to the global default. Each file records the provider that holds it.
- **The admin policy per tenant:** `PUT /v1/tenants/{name}/storage-policy` `{allow: ["s3", "disk"], diskAllotmentMb: 10240, allowPrivateEndpoints: false}`. The default allows neither, so the admin opts tenants in. Turning a type off stops new writes there, but existing files stay readable.
- **Disk allotment** is enforced like the quota, from the metadata, per tenant and per provider.
- **Redundancy for disk on several nodes.** `AUTOMATELY_DISK_ROOT` must be shared storage (NFS, Ceph and the like), which is responsible for its own redundancy. At start each node writes a marker there and checks the others' markers. If they don't all see the same folder, tenant disk providers are turned off with a clear reason. A single node can use any folder.
- **Endpoint checks:** a tenant's S3 endpoint must resolve to public addresses (the same policy as `fetch`, D-026) unless its storage policy allows private ones. The connection goes to the address that was checked, so DNS rebinding can't get around it.
- **Deleting a provider that still holds files** is refused, listing how many.
- **Everywhere:** the OpenAPI contract, CLI (`automately storage providers …`, `automately storage routes …`), MCP tools, and docs.
