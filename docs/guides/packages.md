# Packages

A package is versioned JavaScript you publish to the cluster once, and import from your scripts and services ([design](../design/packages.md), D-083). A published version never changes; a fix is a new version.

> **Status:** built (D-088 to D-092): publishing; importing from jobs and services, with stand-ins for Node's `path`, `events`, `buffer`, `url` and `util`; deploying a version as a service; `/packages` in `fs`; the `c93:*` modules; and yanking, blocking and purging versions.

## Publish
```bash
cd my-lib
npm install                         # as usual
npm i -D esbuild                    # once: publishing bundles with your project's own esbuild
c93 packages publish         # name and version from cluster93.json, else package.json
```
Publishing works in three steps, so only new files travel:
1. **Bundling:** your code and the npm packages it uses become plain ES modules, with source maps. This uses your project's esbuild, run by Node on your machine. Nothing is fetched from npm by the cluster, ever.
2. **Uploading what's new:** the CLI hashes every file (SHA-256), asks the cluster which it lacks, and uploads only those. The cluster checks each file against its hash.
3. **Committing the version:** its manifest names every file by hash. It's refused if the version exists, if a file is missing, or if a dependency has no published version that matches.

`--dry-run` shows what would be published without uploading anything. `--no-bundle` publishes the folder's files as they are, when they're already ES modules (everything except `node_modules`, hidden files, `package.json`, `package-lock.json` and `cluster93.json`).

### `cluster93.json`
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
- **`dependencies`:** other *Cluster93* packages this one imports, with version ranges: exact (`1.4.0`), `^1.4.0`, `~1.4`, `1.x` or `*`. They stay imports and are resolved when a job runs. Your npm dependencies aren't listed here: they're bundled in.

### What a package can't hold
- **Native code** (`sharp`, `bcrypt`, anything with a `.node` file or a native build) stops the publish, naming the package and what pulled it in. Run that code in the Node runtime instead (`"runtime": "node"`, [design](../design/node-runtime.md)).
- **Optional native extras** (`bufferutil`, `utf-8-validate`, `fsevents`) are left out with a warning; the packages that use them work without them. A `require()` of one throws `MODULE_NOT_FOUND`, as when it isn't installed under Node, so code that tries it and falls back (ws) takes its fallback (D-125).
- **Node built-ins** (each one's limits and differences from Node are in [Node compatibility](../scripting/node-compat.md)): the isolate provides `fs` (and `fs/promises`) plus stand-ins for `path`, `events`, `buffer`, `url`, `util`, `process`, `crypto` (hashing, HMAC, key derivation, ciphers, keys and signatures), `stream` (with `stream/promises`), `string_decoder`, `zlib` (no Brotli compression), `assert` (and `assert/strict`), `querystring`, `timers` (and `timers/promises`), `os` (describing the job, not the machine), and the `http` and `https` clients (through the egress gate), with or without `node:`. `net`, `tls`, `http2` and `tty` are there for their pure parts (`net.isIP`, `http2.constants`, `tty.isatty`); connecting through them throws. These stay imports, including when CommonJS code `require()`s them.
- **Other Node modules** (`dns`, `child_process`, `worker_threads`, …): importing one stops the publish; run that code in the Node runtime. A CommonJS `require()` of one is only a warning: the `require()` throws when it runs, as for a module Node doesn't have, so libraries that try an optional module and fall back still work (D-109).
- **Node's globals:** `process` is a global in the isolate, with the job's [declared env](../scripting/host-apis.md#processenv-configuration-you-declare). Where bundled code uses `Buffer` or `global` without importing them, publishing turns each use into an import of the stand-in, and `process.env.NODE_ENV` is `"production"`.
- **Tested against real libraries:** lodash-es, date-fns, dayjs, zod, marked, js-yaml, yaml, ajv, semver, immer, rxjs, mustache, handlebars, eventemitter3, validator, change-case, fast-xml-parser and qs give the same results in the isolate as under Node. So do uuid and nanoid, on the JVM-backed `crypto`; papaparse, csv-parse (streaming) and jsonwebtoken (HS256, RS256 and ES256), on `stream` and `crypto`; and axios and node-fetch, on `http`. bcrypt and sharp need native code and are refused.
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

Before the script runs, its node fetches every file in the lock and checks each against its SHA-256. A file that doesn't match fails the attempt before any code runs. Nodes keep checked files in memory, `C93_PACKAGE_CACHE_MB` (64 MB by default), so the same versions load quickly next time.

## Deploy
A project folder becomes a running service in one command:
```bash
c93 deploy                     # publish (or reuse) the version, then run it as a service
c93 deploy --name shop-eu      # another service from the same code
```
`cluster93.json` says what to run:
```json
{
  "name": "shop",
  "version": "1.4.0",
  "entry": "src/service.js",
  "service": { "name": "shop", "config": { "region": "eu" }, "restart": "always" }
}
```
- **The version is the service's revision.** Deploying publishes the version, or reuses it if it's already published with exactly these files. A changed file without a new `version` is refused, since a version never changes. Bump the version, deploy, and the service rolls to the new revision.
- **The service runs the package's main export's default export,** `(config, ctx)`, like any service script. `"runtime": "node"` is for the Node runtime, which isn't built yet.
- **The API does the same:** `PUT /v1/services/{name}` and `POST /v1/jobs` take `"package": {"name": "shop", "version": "1.4.0", "export": "./report"}` instead of `"script"`. Without a version, the newest release is used and pinned. The service's `script` then shows what it runs (`export { default } from "shop";`), and its `packages` the versions.

## A package's own files
Every file in a job's packages is readable, read-only, at `/packages/<name>/<version>/<path>`, the same path as its `import.meta.url`:
```js
import { readFileSync } from 'fs';
import { URL } from 'url';

const config = JSON.parse(readFileSync(new URL('./data/config.json', import.meta.url), 'utf8'));
const wasm = readFileSync(new URL('./engine.wasm', import.meta.url));
```
`fs` takes `file:///` URLs as paths. Writing under `/packages` fails with `EROFS`. `readdirSync('/packages')` lists the job's packages.

## The host APIs as modules
Packages shouldn't depend on globals, so the host APIs are modules too: `c93:kv`, `c93:bus`, `c93:jobs`, `c93:files`, `c93:secrets`, `c93:fetch` and `c93:log` (with `c93:fs`). Each exports the same object as the global, as its default and by name:
```js
import kv from 'c93:kv';
import { bus } from 'c93:bus';
```

## Cluster-wide packages
Operators publish packages every tenant can import, named `@cluster/<name>` (`c93 --token <operator token> packages publish`). No tenant can publish under `@cluster/`, and a bare name only ever finds your own package, so the two can't be confused. Cluster packages may only depend on other cluster packages.

## Look around
```bash
c93 packages ls                       # yours and the cluster's, with each one's latest version
c93 packages info strings             # its versions, newest first
c93 packages info strings 1.4.0       # one version's files, exports and dependencies
```
The latest version is the highest release (not a pre-release like `2.0.0-beta.1`).

## Who can do what
| Scope | Allows |
|---|---|
| `packages:read` | Listing and reading packages (every built-in role) |
| `packages:write` | Publishing, yanking and unyanking (`owner`, `admin` and `developer`) |
| `packages:admin` | Blocking, unblocking and purging (`owner` and `admin`) |

Publishing, yanking, blocking and purging are recorded in the [audit log](users.md); uploading files isn't, since the publish names them all. Deleting a tenant deletes its packages.

## When a version goes wrong
| | New jobs and deploys | Jobs and services already locked to it | Its files | Undo |
|---|---|---|---|---|
| **Yank** (`packages yank NAME VERSION`) | skip it | still load it | kept | `unyank` |
| **Block** (`packages block … --reason`) | skip it | **refuse to load it**; services running it stop and are disabled | kept | `unblock` |
| **Purge** (`packages purge … --reason --yes`) | skip it | refuse to load it; services running it stop and are disabled | **deleted** | never |

- **Yank** a version that's merely broken: nothing already running changes.
- **Block** a version with a security hole. The reason is shown wherever it's refused ("shop@1.4.0 is blocked (CVE-2026-1234)"). The answer lists the services that were stopped; deploy a fixed version and start them again.
- **Purge** a version published with a secret in it: its files are deleted for good. A file another version also has is kept, since deleting it would break that version; the answer counts both, so purge those versions too if they share the secret. Rotate the secret either way: it was published.
- **Operators** manage `@cluster/` packages, and can block or purge any tenant's with `--tenant <id>` (`?tenant=` in the API).
- **Garbage:** files no version names, such as from a publish that never finished, are deleted after an hour.

## The API
| Method | Path | |
|---|---|---|
| `POST` | `/v1/packages/blobs/missing` | `{sha256: [hashes]}` → `{missing: [...]}` |
| `PUT` | `/v1/packages/blobs/{sha256}` | One file's bytes; `201` stored, `200` already there, `400` if it doesn't match the hash |
| `POST` | `/v1/packages` | Publish a manifest `{name, version, files: {path: "sha256:…"}, exports?, dependencies?}` |
| `GET` | `/v1/packages` · `/v1/packages/{name}` · `/v1/packages/{name}/versions/{version}` | List · versions · one version. A cluster package's slash is encoded: `/v1/packages/%40cluster%2Fdates` |
| `POST` | `/v1/packages/{name}/versions/{version}/yank` · `unyank` · `block` · `unblock` · `purge` | Block and purge take `{reason}` and answer with `stoppedServices` |
| `POST` | `/v1/jobs` | With `"packages": false` to refuse imports; the answer's `packages` is the lock |
