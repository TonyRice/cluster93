# Packages

A package is versioned JavaScript you publish to the cluster once, and import from your scripts and services ([design](../design/packages.md), D-083). A published version never changes; a fix is a new version.

> **Status:** publishing, listing and the package store are built (step 1). Importing packages from scripts comes with step 2 (resolving and loading); until then, published packages can be listed and inspected but not yet imported.

## Publish
```bash
cd my-lib
npm install                         # as usual
npm i -D esbuild                    # once: publishing bundles with your project's own esbuild
automately packages publish         # name and version from automately.json, else package.json
```
Publishing works in three steps, so only new files travel:
1. **Bundling:** your code and the npm packages it uses become plain ES modules, with source maps. This uses your project's esbuild, run by Node on your machine. Nothing is fetched from npm by the cluster, ever.
2. **Uploading what's new:** the CLI hashes every file (SHA-256), asks the cluster which it lacks, and uploads only those. The cluster checks each file against its hash.
3. **Committing the version:** its manifest names every file by hash. It's refused if the version exists, if a file is missing, or if a dependency has no published version that matches.

`--dry-run` shows what would be published without uploading anything. `--no-bundle` publishes the folder's files as they are, when they're already ES modules (everything except `node_modules`, hidden files, `package.json`, `package-lock.json` and `automately.json`).

### `automately.json`
Optional; `package.json`'s `name`, `version` and `main` (or `module`, or a string `exports`) are used without it.
```json
{
  "name": "shop",
  "version": "1.4.0",
  "exports": { ".": "src/index.js", "./report": "src/report.js" },
  "dependencies": { "strings": "^1.4.0", "@cluster/dates": "2.x" }
}
```
- **`name`:** lowercase letters, digits, `.`, `_` and `-`. npm scopes (`@acme/x`) aren't used; set a plain name here.
- **`entry`** (one file) or **`exports`** (`.` and `./subpath`): what other code can import.
- **`dependencies`:** other *Automately* packages this one imports, with version ranges: exact (`1.4.0`), `^1.4.0`, `~1.4`, `1.x` or `*`. They stay imports and are resolved when a job runs. Your npm dependencies aren't listed here: they're bundled in.

### What a package can't hold
- **Native code** (`sharp`, `bcrypt`, anything with a `.node` file or a native build) stops the publish, naming the package and what pulled it in. Run that code in the Node runtime instead (`"runtime": "node"`, [design](../design/node-runtime.md)).
- **Optional native extras** (`bufferutil`, `utf-8-validate`, `fsevents`) are left out with a warning; the packages that use them work without them.
- **Node built-ins** other than `fs` (`node:fs`, `fs/promises`) stop the publish for now. The isolate provides `fs` and the `automately:*` modules; stand-ins for `path`, `events`, `buffer`, `url` and `util` come with step 2.
- **WebAssembly** is fine: `.wasm` files are package files like any other.

### Limits
A version holds at most 1,000 files and 10 MB, and a package at most 100 dependencies. Package files count against your tenant's storage quota, each unique file once.

## Cluster-wide packages
Operators publish packages every tenant can import, named `@cluster/<name>` (`automately --token <operator token> packages publish`). No tenant can publish under `@cluster/`, and a bare name only ever finds your own package, so the two can't be confused. Cluster packages may only depend on other cluster packages.

## Look around
```bash
automately packages ls                       # yours and the cluster's, with each one's latest version
automately packages info strings             # its versions, newest first
automately packages info strings 1.4.0       # one version's files, exports and dependencies
```
The latest version is the highest release (not a pre-release like `2.0.0-beta.1`).

## Who can do what
| Scope | Allows |
|---|---|
| `packages:read` | Listing and reading packages (every built-in role) |
| `packages:write` | Publishing (`owner`, `admin` and `developer`) |

Publishing a version is recorded in the [audit log](users.md); uploading its files isn't, since the publish names them all. Deleting a tenant deletes its packages.

## The API
| Method | Path | |
|---|---|---|
| `POST` | `/v1/packages/blobs/missing` | `{sha256: [hashes]}` → `{missing: [...]}` |
| `PUT` | `/v1/packages/blobs/{sha256}` | One file's bytes; `201` stored, `200` already there, `400` if it doesn't match the hash |
| `POST` | `/v1/packages` | Publish a manifest `{name, version, files: {path: "sha256:…"}, exports?, dependencies?}` |
| `GET` | `/v1/packages` · `/v1/packages/{name}` · `/v1/packages/{name}/versions/{version}` | List · versions · one version. A cluster package's slash is encoded: `/v1/packages/%40cluster%2Fdates` |
