# Packages

A package is versioned JavaScript you publish to the cluster once, and import from your scripts and services ([design](../design/packages.md), D-083). A published version never changes; a fix is a new version.

> **Status:** publishing (step 1) and importing from jobs and services (step 2) are built. Stand-ins for Node's `path`, `events`, `buffer`, `url` and `util` come next; services built from a package version, and reading a package's own files through `/packages`, come with step 3.

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

## Import
A job's script imports packages by name, with no setup:
```js
import { slug } from 'strings';             // the package's main export
import { report } from 'strings/report';    // a subpath it exports
import { iso } from '@cluster/dates';      // a cluster-wide package

export default async (input) => {
  const { parse } = await import('csv');    // works too, with a literal name
  return report(slug(input.title));
};
```
- **Versions are locked when the job is submitted.** The server reads the script's imports and picks the newest release of each package (pre-releases only when named exactly). It then adds the packages they depend on, one version each, meeting every range. The job's `packages` field shows the result, for example `{"strings": "1.4.0", "csv": "2.0.1"}`. **Retries and resumes run exactly those versions,** even if a newer one is published meanwhile.
- **Refused when it's sent, not halfway through** (400 `unresolved_import`, saying why):
  - a package that doesn't exist
  - versions whose ranges conflict (naming who asked for each)
  - an export the package doesn't have
  - `import(x)` whose name isn't a string literal (with its line)
  - Node's own modules (`path`, `node:crypto`)
  - paths and URLs
- **`"packages": false`** on `POST /v1/jobs` refuses any package import, for jobs that must run only their own code.
- **Jobs a script starts** (`jobs.run`) can import what their parent could, at the same versions.
- **Services** are locked when they're defined. Redeploying (`PUT /v1/services/{name}` again) picks up newer versions, and new versions mean a new revision.
- **Inside a package,** `./` and `../` import its own files (never outside it), and bare names import only the packages it lists in `dependencies`.
- **`import()` loads up front.** Everything a job imports is fetched and checked before it starts, so a module loaded with `import('x')` is evaluated with the others, not when the call runs. `import.meta.url` is `file:///packages/<name>/<version>/<file>`.
- **Limits:** a job can use at most 500 packages, 1,000 files and 10 MB in all.

Before the script runs, its node fetches every file in the lock and checks each against its SHA-256. A file that doesn't match fails the attempt before any code runs. Nodes keep checked files in memory, `AUTOMATELY_PACKAGE_CACHE_MB` (64 MB by default), so the same versions load quickly next time.

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
| `POST` | `/v1/jobs` | With `"packages": false` to refuse imports; the answer's `packages` is the lock |
