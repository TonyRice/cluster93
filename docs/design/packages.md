# Packages: versioned JavaScript code, published to the cluster

**Status:** accepted (Tony, 2026-10-08) and reviewed for holes the same day, twice (§12, D-085; §13, D-086). Step 1 (the store and publishing) is built (D-088, [guide](../guides/packages.md)); steps 2 to 4 aren't yet. Decisions: D-083, D-087 (inline scripts, §10), D-088. This is the "modules" step from the roadmap. Native code and Node APIs are a separate design, the Node runtime (§9).

## 1. What a package is
A **package** is an immutable, versioned set of JavaScript files (ES modules) with a manifest:

```json
{
  "name": "strings",
  "version": "1.4.0",
  "files": { "index.js": "sha256:9f2c…", "slug.js": "sha256:41ab…" },
  "exports": { ".": "index.js", "./slug": "slug.js" },
  "dependencies": { "dates": "^2.1.0" },
  "visibility": "private"
}
```

- **Once published, a version never changes.** A fix is a new version.
- **Scripts, services and (later) apps can all be packages.** A job or service can run an entry point from one: `{ "package": "shop@1.4.0", "entry": "jobs/report.js" }`.
- **The word users see is "packages"** (Tony): `automately packages publish`, `/v1/packages`. "Module" stays the JavaScript term for a file you import.

## 2. Where they live
Automately never fetches code from npm or the internet. Developers publish packages to it.

| What | Where | Why |
|---|---|---|
| **Manifests** | a replicated `PACKAGES` table (tenant, name, version, manifest, publishedAt, publishedBy, yanked) | every node reads them; they survive node loss and full restarts, like jobs |
| **File contents** | stored **once per unique file, by SHA-256**, in a `PACKAGE_BLOBS` table (tenant, sha256 → blob id, size), with the bytes in the **cluster's own storage** (replicated chunks, or the operator's disk or S3) | code mustn't depend on a tenant's own bucket being up; two versions sharing a file share the bytes |
| **On a node running a job** | a per-node cache keyed by hash: in memory, then the data directory | a hash's contents never change, so the cache never needs clearing |
| **In `fs`** | `/packages/<name>/<version>/…`, read-only, built from the manifests | scripts can read package files like any others, but never change them |

- **Not in the tenant's file tree:** package files aren't stored under paths (the first draft used `.packages/blobs/…`), so they never show up in listings and no naming rule is needed to hide them.
- **Apps share this:** app deployments, later, are content-addressed and immutable in the same way, so they reuse the table.
- **Visibility** (Tony): a tenant's packages are visible to that tenant only. Operators publish cluster-wide packages under the reserved **`@cluster/`** prefix (`import { slugify } from '@cluster/strings'`). Every tenant can import those, and no tenant can publish under that prefix. A bare name only ever finds the tenant's own package, so the two can't be confused (D-085). Sharing with chosen tenants can come later if there's a need.
- **Quota:** package files count against the tenant's storage quota, once per unique file. Deduplication is per tenant, never across tenants: a shared store would let anyone test whether another tenant holds a given file.

## 3. Publishing
```bash
cd my-lib && npm install          # as usual
automately packages publish       # reads automately.json, or package.json
```
1. **Bundling, on the developer's machine** (Tony): the CLI runs the project's own esbuild (`npx esbuild`, `--format=esm --platform=node`), turning the code and the npm dependencies it uses into plain ES modules.
   - **Source maps** come along, so errors point at the original files.
   - **If esbuild isn't there,** the CLI explains how to add it (`npm i -D esbuild`).
   - **Our own `fs` stays ours:** `fs`, `node:fs` and `fs/promises` are left for the isolate to provide (D-079).
   - **Other Node built-ins:** pure-JavaScript stand-ins are bundled in: `path`, `events`, `buffer`, `url`, `util`. `crypto` and `timers/promises` map to the host API.
   - **Required native code stops the publish** (§9). It names the `.node` file, or the package that needs a native build and the dependency that pulled it in.
   - **Optional native extras** (`bufferutil`, `utf-8-validate`, `fsevents`, which packages like `ws` use if present) are stubbed out with a warning. They don't block a package that works without them.
   - **WebAssembly is fine:** `.wasm` files are package files like any other. It's often how a library avoids native code.
2. **Uploading only what's new:** the CLI hashes every file and asks which hashes the cluster lacks (`POST /v1/packages/blobs/missing`), then uploads only those (`PUT /v1/packages/blobs/{sha256}`). The server re-hashes what arrives and refuses a mismatch.
3. **Committing the version** (`POST /v1/packages`, D-088): it's refused if that version exists, a file is missing, or a dependency has no published version that matches.
   - **Who and when:** it records who published (the user, D-070) and when.
   - **Audited:** the publish is in the audit log (D-071).

**Also:** the same API for CI; an MCP tool for agents to publish small packages inline; later, a link publishing a watched folder.

**Scopes:** `packages:read` and `packages:write`. Only operators publish under `@cluster/`. Agents can publish their tenant's packages with `packages:write`, never cluster-wide ones.

## 4. Resolving: once, when a job is submitted
- **The lock:** the server resolves the job's package and every dependency range to exact versions (`^`, `~`, exact and `x` ranges; no npm-style peer dependencies), giving a **lock**: name → version → manifest hash.
- **The lock is saved on the job:** retries and resumes (D-009 to D-012) run identical code, even if a newer version is published meanwhile. "What code ran for job X" always has an answer.
- **Services run from a package version,** which is their revision (D-021). **The lock is made when the service is deployed and stored on the revision**, so every run and restart of that revision uses identical code. Publishing and redeploying rolls a service.
- **Jobs started from scripts** (`jobs.run`, D-063) inherit their parent's lock, and can import exactly what the parent could.
- **At most 500 packages in one job's lock,** to keep job records small.

## 5. Loading: everything before the script runs
Javet's module resolver runs synchronously on the isolate's thread, so the node fetches everything first:
1. **Prefetch:** the node fetches every file in the lock, verifies each against its hash (dropping and refetching on a mismatch), and caches it by hash. Limits are 10 MB and 1 000 files per job. A fetch failure fails the attempt before an isolate is made.
   - **The cache is bounded:** least-recently-used files go first, from memory and then from the data directory, within `AUTOMATELY_MIN_FREE_DISK_MB`. The Node runtime's artifacts share it.
2. **Resolve:** inside the isolate, the resolver only maps names, with no I/O:
   - **Relative paths** stay inside the same package. Escaping its root is refused.
   - **Bare names** come from the lock and the package's `exports`.
   - **Built-ins:** `fs`, `node:fs`, `fs/promises` (D-079), and `automately:*` names for the host APIs (`automately:kv` and so on).
   - **A package's own files:** `import.meta.url` is `file:///packages/<name>/<version>/<file>`. Reading a path under `/packages` with `fs` or `fetch(new URL('./data.json', import.meta.url))` is answered from the job's lock, read-only. So packages that ship templates, JSON or `.wasm` work.
   - **Refused with a clear error:** URLs, absolute paths, and names not in the lock.
3. **Later:** V8's code cache, keyed by file hash, measured before it's adopted (as isolate pooling was, D-024).

## 6. Security
- **The same sandbox:** a package is just more of the job's code, in the same isolate, under the same heap, time and host-API limits.
- **Integrity:** every file is checked on publish and on load.
- **Provenance:** the job records exactly which versions ran, and each version records who published it.
- **No native code,** no network imports, and no fetching from npm on the cluster.

## 7. Lifecycle
- **Yank:** a yanked version is skipped by new resolutions, while existing locks still load it (Cargo's model). Deleting is only for versions no lock references.
- **Block** (D-086), for a version with a security hole: a blocked version **refuses to load anywhere**, even from an existing lock.
  - **Running services on it stop** (Tony), with the reason "version blocked"; new and retried jobs fail at load with the same reason. The owner deploys a fixed version.
  - **Who:** the tenant's owners and admins, or an operator (for any tenant's package, and for `@cluster/` ones). Audited, and it can be lifted.
- **Purge** (D-086), for a version published with a secret in it: an owner or operator deletes the version's files outright. Anything still locked to it fails loudly ("version purged"), never runs other code in its place. Audited, and final.
- **The same rules cover Node artifacts,** which share this store.
- **Garbage:** package files that no manifest references are collected, as file garbage is.
- **Deleting a tenant** deletes its packages and their files.
- **Listing:** `GET /v1/packages`, `GET /v1/packages/{name}`, and `automately packages ls · info · yank · block · purge`.

## 8. One deploy command, two runtimes
`automately deploy` reads `automately.json`, whose **`runtime` field is there from day one**:
- **`"runtime": "isolate"`** (the default): bundle → package → service revision, as above.
- **`"runtime": "node"`:** the Node runtime (§9).

Both have the same `(input, ctx)` contract and globals, so switching is one line.

## 9. Native code and Node APIs: not here
An isolate has no libuv and can't load `.node` addons, so `sharp`, `bcrypt`, `child_process` or a real `http` server can't run in it.
- **The packages job is to detect that and say where it can run.** The bundle shows exactly which native files and packages are pulled in. Publish stops with, for example, "`sharp` (pulled in by `image-tools`) needs native code; set `"runtime": "node"` to run this as a Node service".
- **Where native code runs:** the Node runtime, as a sandboxed container on the cluster or as unsandboxed Node on the tenant's own device. It's designed separately, because it needs the execution layer: capabilities, placement and the runner protocol.

## 10. Inline scripts, and what's open
- **Inline scripts can import packages** (Tony, D-087). An inline script is a job sent as source text (`automately run report.js`, `POST /v1/jobs` with a script, an agent over MCP) rather than an entry point of a published package.
  - **The server finds the imports:** at submission it reads the script's static `import` statements and `import('literal')` calls, resolves each to the newest version that isn't yanked or blocked, and saves the lock on the job (§4). Retries run the same versions.
  - **Imports it can't see are refused:** `import(someVariable)` fails with a clear error, since everything must be fetched before the script starts (§5).
  - **Native code is refused at submission,** with the same message as publishing (§9), never halfway through a run.
  - **A job can turn this off** with `"packages": false`; then any package import is refused at submission.
  - **Pinning versions** for an inline script (`"packages": {"strings": "^1.4"}`) isn't planned; it can be added if it's needed.
- **Python packages** sharing this store, with `.py` files and an import hook resolving from the same lock: proposed yes, decided with Python.

## 11. Building it
1. ~~**Store and publish:**~~ ✅ `PACKAGE_VERSIONS` and `PACKAGE_BLOBS` (a contract test on both stores), the publish API, and the CLI's `publish`, `ls` and `info`, with esbuild and native detection (D-088).
2. **Resolve and load:** a short spike first (import cycles, and how a failed import surfaces in Javet). Then the lock on jobs, prefetch and the node cache, and the resolver. Tests cover imports, refused names, an integrity failure, and the lock surviving a retry.
3. **Services from packages:** a version is a revision; `automately:*` built-ins; `/packages` in `fs`; MCP tools; docs.
4. **Lifecycle:** yank, block and purge, garbage collection, metrics (cache hits, prefetch time).

## 12. Review for holes (2026-10-08, D-085)
Each was closed in the sections above:
- **Dependency confusion** between tenant and cluster packages: the reserved `@cluster/` prefix (§2).
- **Storage without a quota:** package files count, deduplicated per tenant only (§2).
- **Child jobs** inherit their parent's lock (§4).
- **Packages reading their own files** (templates, JSON, `.wasm`): `import.meta.url` and `/packages` reads answered from the lock (§5).
- **Optional native extras** blocking a publish: they're stubbed with a warning (§3).
- **When services are pinned:** at deploy, on the revision (§4).
- **An unbounded node cache:** it's bounded and shared with Node artifacts (§5).
- **Tenant deletion** removes packages (§7).
- **`--platform=neutral`** breaking npm packages: `node`, with source maps (§3).

## 13. Second review (2026-10-08, D-086)
- **A version with a security hole kept running,** since yanking only affects new resolutions: **block** stops it everywhere, including running services (Tony) (§7).
- **A version published with a secret couldn't be removed,** since versions never change and locks reference them: **purge** deletes its files, and whatever was locked to it fails loudly (§7).
- **Inline scripts importing packages:** decided afterwards (§10, D-087).
