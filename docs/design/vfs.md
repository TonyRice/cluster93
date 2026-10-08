# The virtual file system (VFS): one file tree per tenant

**Status:** accepted (Tony, 2026-10-07). Decisions: D-075 (this design), D-076 (directories). It's built in five parts, and the build status is at the end.

## Where it comes from
2017's Automately had one: `automately.core.file`.
- **A per-user Java NIO `FileSystem`:** `UserFileSystem`, `UserFilePath`, `UserFileChannel`.
- **Real directories:** `VirtualFile` with `pathAlias` and `isDirectory`.
- **Pluggable stores:** local, S3 and Azure.
- **Per-file locks,** and **public files** with expiring tokens.
- **One file system behind everything:** scripts' `fs`, SFTP, and `require()`, which looked for modules in the user's files (`modules/`, `package.json`).

## 1. What exists today, and what VFS adds
**Today's store holds tenants' files, which is its job, and does it well.** Its users: scripts (`files.*`), the REST API (`/v1/files`), the CLI (`files put · get · ls · rm · sync`), the MCP file tools, and tenants' own storage providers.
- **The model:** a file is a path mapped to an immutable version.
  - **Writing:** a write uploads a new blob with a fresh internal id (`fileId`), then swaps the path's metadata row to it in one write. Readers see the old file or the new one, never a mix (D-040).
  - **Replaced blobs:** they're deleted after a 10-minute grace, so downloads already in progress finish.
- **Bytes:** in any backend: cluster chunks, disk or S3 (D-056). A tenant's own providers can take chosen path prefixes (D-057). Each file records which backend holds it.
- **Quotas and admission control:** D-043 and D-038.

VFS keeps all of that. **It adds file-system behaviour on top of the same store:**

| | Today | With VFS |
|---|---|---|
| Folders | none: `a/b.txt` is a name with a slash | real directories: empty ones, and one-level listing |
| Rename, move, copy | download, upload again, delete | one call, and **no bytes move** |
| Writing | the whole file each time | also append and edits in place |
| Providers | invisible prefix routes | visible **mounts** in the tree |
| Scratch space | none | `/tmp` per job |
| Package files | nowhere | read-only `/packages` |
| Node's `fs` | nothing to call | a host API it can sit on, synchronous calls included |
| Locks, public links | none | advisory locks; expiring signed links |

**Why it's a thin layer:** blobs are named by `fileId`, never by path. So rename, move and copy only rewrite metadata rows, on every backend, S3 included.

## 2. The tree
One tree per tenant, rooted at `/`:

| Path | Backed by |
|---|---|
| `/` | the tenant's default storage (cluster, or their default provider) |
| `/mnt/<name>` | a mount: one of the tenant's storage providers; later, a link's `files:local:<name>` |
| `/tmp` | the running job's scratch space (§7) |
| `/packages` | read-only: published package versions (modules) |

- **Paths in the API stay as they are:** `reports/q3.csv` and `/reports/q3.csv` mean the same file, so every existing script, CLI command and URL keeps working.
- **Path rules don't change** (`FileStore.invalidPath`: at most 1 024 bytes, no `.`, `..` or empty segments, no backslashes or control characters).
- **Reserved names:** `mnt`, `tmp`, `packages` and `.packages` at the top level. Tenants can't create files there except through the mount or feature that owns them. **Migration:** a tenant who already has files under `tmp/` or `mnt/` keeps them, readable at those paths. New writes there go to the reserved meaning, and the release notes say so.

## 3. Directories (D-076)
- **Every folder is a real row** in a `DIRS` table (tenant, path, parent, name), so **empty folders exist** and listing a level is an index lookup.
- **Writing a file makes its folders:** writing `a/b/c.txt` makes `a` and `a/b` if they're missing, as `mkdir -p` would.
  - **Cost per write:** usually one extra read. A folder's ancestors always exist, because they're made first and removed last, so finding the parent ends the check.
- **Folders stay when emptied,** as on a disk; `rmdir` removes them. (The first draft had S3-style folders, implied by the files under them, which disappear when empty. Real rows made listing two index lookups instead of a skip-scan through every subtree, and match what file-system users expect.)
- **One-level listing:** the `FILES` table gains `parent` and `name` columns with an index on (tenant, parent, name). Listing a folder merges its subfolders and its files, both by name, and pages with the last name as the cursor.
- **A name is a file or a folder, never both:**
  - writing a file where a folder is: `is_a_directory`
  - writing under a file, as if it were a folder: `not_a_directory`
  - making a folder where a file is: `already_exists`
  - Each is a 409 in the API.
- **Deleting:** `rmdir` refuses a folder that isn't empty (`not_empty`). Recursive delete comes with tree operations (§4).
- **Upgrading existing data:**
  - **Columns:** a node adds the new columns to an existing `FILES` table with SQL DDL when it starts. This is a general helper for tables, since Ignite keeps the old definition of a cache that already exists.
  - **Folders:** it then backfills: files written before folders existed get their `parent` and `name`, and their folders are made. That's idempotent, and a no-op once done.
- **Stat** answers a file's details plus `kind: "file"`, or `{name, path, kind: "dir", createdAt}`. The top level, `""`, is always a folder.

## 4. Rename, move and copy
- **Moving a file:** the new path's row gets the old row's `fileId`, `backend` and metadata, and the old row goes. **No bytes move,** even on S3 or between providers, because reads follow the backend recorded on the file, not the mount it now sits under.
  - **Overwriting:** if the target exists, it's replaced only with `overwrite: true`, and the replaced version goes to garbage as on any write.
  - **Atomic for one file:** the target row is written, then the source removed. A crash between leaves both, which a directory journal (below) or a retry cleans up. For one file this is the same guarantee a write already gives.
- **Copying a file:** a second row naming **the same blob**. Blobs can now be shared, so **garbage collection must change**: a blob is deleted only when no row names it.
  - **Proposed:** a reference count on the blob, in a small table (`BLOB_REFS`: blob id → count), raised on copy and lowered on delete or replace. Garbage collection deletes a blob whose count is zero once the grace period has passed.
  - **The alternative** is checking "does any row still name this blob?" at collection time. It's simpler but needs an index on `fileId` and a query per blob (open question 1).
- **Moving or copying a directory** is many row operations, made safe as a **tree operation**:
  - **One at a time:** it takes a per-tenant lease on the directory (the compare-and-set lease pattern of D-073), so two tree operations on overlapping paths don't interleave.
  - **Crash-safe:** it writes a journal record (`{op, from, to, done-so-far}`) first. If the node dies, another finishes it from the journal when the lease expires.
  - **Readers** may briefly see part of a moving directory under each name: a directory move isn't instantaneous, and the design says so. Each file is always whole at one of its two paths.
  - **Limit:** 100 000 entries per tree operation, refused with a clear error if larger (configurable).
- **Exceptions:** `/tmp` and, later, link mounts can't share blobs with cluster storage, so moves or copies into or out of them copy the bytes and then delete. Moving within `/packages` is refused, since it's read-only.

## 5. Writing beyond whole-file replace
- **Streaming writes stay the main path,** and stay atomic (§1).
- **Appending:** `fs.appendFile` and append-mode handles write a **new version** made of the old bytes followed by the new ones. It's published atomically when the handle closes, so readers never see half an append.
  - **On cluster storage,** this can reuse the old chunks instead of copying them (the chunk store is keyed `fileId:index`), so appending to a big log is cheap. That's an optimisation for later; v1 copies.
- **Writing at a position** (`fs.write(fd, buf, 0, n, position)`): the file is copied into `/tmp`, edited there, and published as a new version on close.
  - **Limit:** files edited in place are capped at 64 MB (`AUTOMATELY_FS_EDIT_MB`). Larger ones must be rewritten as streams. That covers SQLite-style and config-file editing without promising a database file system.
- **Open handles:** a handle reads a fixed version, so a file replaced while it's open keeps reading the old one, as the 10-minute grace already allows. A handle not closed by the end of the job is discarded, like an unfinished upload today.

## 6. Mounts replace prefix routes
- **A mount** is a directory bound to a provider: new writes beneath it go there. Today's D-057 routes become mounts.
  - **Compatibility:** a route prefix `photos/` → provider `my-bucket` is the mount `photos` → `my-bucket`. The default provider is the mount at `/`, and `/v1/storage/routes` keeps working as a view of the mount table.
  - **New mounts** go under `/mnt/<name>` by default, and a tenant can mount at any path that isn't reserved.
  - **Changing or removing a mount** doesn't move existing files: they stay readable where their recorded backend is, exactly as when routes change today. A "move into this mount" is a normal move (§4), which as noted doesn't move bytes either. A separate, explicit "migrate bytes to this provider" operation is out of scope (open question 5).
- **Visible:** `stat` and listings include the mount and the backend, so you can see where a file's bytes live.
- **Read-only mounts:** `/packages` always; any mount can be flagged read-only.
- **The interface (SPI)** every mount implements: stat, list a directory, open (with ranges), create, delete, and move or copy within. The cluster-backed mounts share today's store. `/tmp` is its own implementation, and link mounts will be one more over the link protocol.

## 7. `/tmp`
- **Per job, in node memory, gone when the job ends.** It's a private view: two jobs never see each other's `/tmp`.
  - **For services,** `/tmp` lasts as long as the run, so a service can keep a cache there.
- **Memory first, then disk (Tony).**
  - **In memory:** each job keeps up to 8 MB of `/tmp` in memory (`AUTOMATELY_TMP_MEMORY_MB`), counted against the node's heap admission (D-038).
  - **Beyond that:** on nodes with a data directory, the rest spills to `<data dir>/tmp/<job id>/`, up to 1 GB per job (`AUTOMATELY_TMP_MB`). That space is checked against the node's minimum free disk, like durable writes (`AUTOMATELY_MIN_FREE_DISK_MB`).
  - **Nodes without a data directory** (in-memory development nodes) stay memory-only, with a 64 MB cap.
  - **Cleanup:** the folder is deleted when the job ends, and a node clears all of `<data dir>/tmp/` when it starts, so nothing from a crash survives.
  - **Running out:** going over the limit fails with `ENOSPC`; the node running low fails with `insufficient_storage`. Either way the node keeps running.
- **Local to the node:** `/tmp` belongs to the node running the job. A job that restarts on another node after a failure starts with an empty `/tmp`, which is normal for scratch space.
- **Never replicated, never counted against the tenant's quota,** and never listed through the REST API, since it only exists inside a running job.

## 8. Metadata
- **Kept:** `kind`, `size`, `contentType`, `etag`, `modifiedAt` (today's `createdAt`, renamed in views; the old name stays as an alias for a release), mount and backend.
- **Fixed:** `mode` is reported as 0644 for files and 0755 for directories, and `chmod` and `chown` are accepted and ignored. Node code that sets permissions keeps working, and permissions in the cluster come from scopes, not modes.
- **Never:** symlinks, hard links, device files or host paths. `fs.symlink` fails with `EPERM`.

## 9. Synchronous calls
Node code uses `fs.readFileSync`, `existsSync` and friends, so the host API has blocking variants.
- **How:** the call blocks the isolate's own thread until the host answers. Each isolate has its own thread, so nothing else on the node waits.
- **Limits:**
  - a timeout per call (`AUTOMATELY_FS_SYNC_TIMEOUT_MS`, 30 s), after which the call throws `ETIMEDOUT`
  - synchronous reads capped at 32 MB, the same as `files.read` buffers today
- **The cost, stated plainly:** while a sync call waits, that isolate does nothing else. Its timers wait, and a service handling several requests in one isolate stalls them all. Node has the same property; it's worse here because storage is a network hop away.
  - **Mitigations:** `/tmp` sync calls are memory-fast. The deploy check warns when sync `fs` calls appear inside `ctx.serve` handlers. The docs steer services to `fs/promises`.

## 10. Locks
- **Advisory, per path:** `fs.lock(path, {ttlMs})` returns a lease; `unlock` releases it, and it expires on its own. It's 2017's per-file locks, on the same compare-and-set records as the audit export lease (D-073).
- **Advisory means only cooperating code is held back:** writes don't check locks, since the store's writes are already atomic per file. Locks are for coordinating multi-step work ("only one job rebuilds the index").
- **Limits:** 10 000 locks per tenant, with a TTL of at most 1 hour (renewable). A job's locks are released when it ends.

## 11. Public links
- **What:** `POST /v1/files/{path}/link {expiresInSeconds}` (or `fs.link` in scripts) returns a URL anyone can fetch until it expires. It's 2017's public file tokens.
- **How:**
  - **The signature:** the URL carries the tenant, path, file version and expiry, signed with HMAC under a key derived from `AUTOMATELY_SECRETS_KEY` and a per-tenant link secret.
  - **Serving:** the ingress checks the signature and serves the file with Range and ETag like any other. No token or session is involved, and nothing is stored per link.
  - **Pinned to a version:** a link points at the version that existed when it was made. Replacing the file stops the old link (open question 3).
- **Revoking:** rotating the tenant's link secret revokes every link it has made (open question 4). It needs a secrets key; without one, links are unavailable, as secrets are (D-068).
- **Audit:** making a link is a write, so it's in the audit log (D-071).

## 12. How you'll use it
**Scripts:**
- **`files` stays exactly as it is.**
- **A new `fs` module**, Node-shaped: `fs/promises` (`readFile`, `writeFile`, `appendFile`, `mkdir`, `readdir`, `stat`, `rename`, `copyFile`, `rm`, `open` → handle) and the sync versions.
  - **Import it** as `import fs from 'automately:fs'` now. With Node compatibility, the bundler maps `fs` and `node:fs` to it.
- **Both sit on one new host API, `FsApi`,** so `files` becomes a thin wrapper and the two can't drift apart.

**REST:**

| | |
|---|---|
| `GET /v1/dirs` · `GET /v1/dirs/{path}` | one level: files and subdirectories, by name, paged |
| `PUT /v1/dirs/{path}` · `DELETE /v1/dirs/{path}` | make (and any missing above it) · remove an empty one; `?recursive=true` comes with tree operations |
| `POST /v1/files:move` · `POST /v1/files:copy` | `{from, to, overwrite}` for files and directories |
| `POST /v1/files/{path}/link` | a public link |
| `GET /v1/files/{path}` · `PUT` · `HEAD` · `DELETE` · `GET /v1/files?prefix=` | unchanged |

- **Scopes:** reading and listing need `files:read`; mkdir, move, copy, rm and links need `files:write`. No new scopes.

**CLI:** `files ls DIR` (one level, folders end in `/`; `-R` lists every file by path, as `ls` did before), `files mkdir`, `files rmdir`; later `files mv`, `files cp`, `files rm -r`, `files link PATH --expires 1h`.

**MCP:** `list_dir` and `make_dir` now; move and copy tools with part 2.

## 13. What it gives the steps after it
- **Modules:** `/packages` is where published versions appear, read-only; the blobs stay in the reserved `.packages` store.
- **Node compatibility:** `fs` with sync calls is the hardest part, done here.
- **Apps:** deployments use the same content-addressed blobs, and `automately apps deploy` uploads only what's new.
- **The console:** its file manager is a pure client of `/v1/dirs` and move/copy/link.
- **Links:** a device's shared folder becomes a mount implementing the same interface.

## 14. Security
- **Paths are normalized everywhere** (one validator), and nothing can escape the tree or a mount: no `..`, no symlinks, no host paths.
- **Reserved names:** only the owning feature writes under `/tmp`, `/packages`, `/mnt` and `.packages`.
- **Read-only mounts are enforced in the store,** not just the API, so no path into the store can write them.
- **Quotas are unchanged:** cluster storage counts and a tenant's own providers don't (D-057).
  - **Copies on cluster storage count once:** they share a blob, so they use no new bytes. The quota counts each blob once, by its reference count, not each row.
  - **Without that rule,** copying a file a thousand times would "use" a thousand times its size for free. With it, the accounting matches the real disk.
- **Tree operations are bounded** (entry limit, one per path at a time), so a recursive delete or move can't run without end.
- **Public links:** signed, expiring, pinned to a version, revocable by rotation, audited.

## 15. Not in VFS
- **Link mounts:** the interface is designed for them, and they come with links.
- **SFTP and WebDAV,** and a FUSE mount for containers.
- **Node's full `fs` surface:** streams (`createReadStream`), watchers and `fs.promises.FileHandle` edge cases come with node-compat. This step does the core calls.
- **Version history** beyond the 10-minute grace.
- **Moving bytes between providers** ("migrate this folder to my bucket"): later, as its own step (Tony).
- **Per-folder permissions:** scopes stay tenant-wide.

## Tony's answers (2026-10-07)
1. **Shared bytes for copies:** a reference count per blob (`BLOB_REFS`); the quota counts each blob once.
2. **`/tmp`:** memory first, spilling to the node's data directory (§7).
3. **Public links:** pinned to the version they were made for; replacing the file stops the old link.
4. **Revoking public links:** all at once, by rotating the tenant's link secret. Nothing is stored per link.
5. **Moving bytes between providers:** later, as its own step.
6. **The name:** "files" everywhere (`/v1/files`, `automately files`, `files` in scripts), plus the `fs` module. The docs call the feature "the file system".

## Is it cluster-friendly?
Yes; nothing depends on which node a request lands on.
- **Records are shared:** paths, directories, reference counts, journals, locks and mounts are in the replicated cluster database. Any node can list, move or serve any file.
- **Bytes stay where they are today:** cluster chunks (replicated), S3, or a disk folder every node mounts.
- **Tree operations take a cluster-wide lease,** and a journal lets another node finish one if its node dies.
- **Locks are cluster-wide leases** that expire, so a dead node can't hold one.
- **Public links are self-verifying,** so any node serves them, with nothing stored.
- **The one node-local piece is `/tmp`, by design** (§7).

## Build status
1. **Folders and one-level listing:** done (D-076).
2. Move and copy: shared blobs with reference counts, then tree operations.
3. Mounts replacing prefix routes.
4. `/tmp` (memory, spilling to disk), `FsApi` and `automately:fs` with sync calls.
5. Edits in place, locks and public links.
