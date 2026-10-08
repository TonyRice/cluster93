# The Node runtime: npm, native addons and Node APIs, sandboxed

**Status:** accepted (Tony, 2026-10-08) and not built yet; it comes after packages. Decision: D-084. It brings together the earlier notes on Node services, containers, unsandboxed links and link capabilities, and sits next to [packages](packages.md) (D-083).

## 1. What it's for
Packages cover pure JavaScript in the isolate. The Node runtime covers what an isolate can't run:
- **Native addons:** `sharp`, `bcrypt`, `better-sqlite3`, `canvas`, anything with a `.node` file.
- **Node's APIs:** `child_process`, `net`/`tls` servers, `worker_threads`, `http.createServer`, `vm`.
- **Whole frameworks that need a real Node process:** Express or Fastify servers as they are, Next.js server rendering, Prisma.

The same contract as everywhere else: a default export `(input, ctx)`, the same globals (`kv`, `bus`, `files`, `secrets`, `jobs`, `ctx`), and `automately deploy` with `"runtime": "node"` in `automately.json`. Moving code from the isolate to Node is one line.

## 2. Three places code can run
| Runtime | Where | Isolation | Whose code | npm |
|---|---|---|---|---|
| **isolate** (today) | core nodes, any link | V8 isolate, limits, host APIs only | any tenant | bundled pure JS (packages) |
| **node** | nodes offering containers: core nodes or links | a container, gVisor for untrusted tenants | any tenant the operator allows | full `npm ci`, native addons |
| **node, unsandboxed** | **a link owned by the same tenant**, opted in | none: the device owner's own user | only that tenant | full, built for that device |

The scheduler enforces this: a job or service asks for a runtime, and placement only considers nodes that offer it **and** are allowed for that tenant.

## 3. The engine: plain Node, not Javet's Node mode (recommended)
The earlier notes assumed Javet's Node mode (embedded libnode) inside the container. I'd use **official Node** instead, with our runner written as a small npm package.

| | Plain Node + our runner | Javet Node mode |
|---|---|---|
| **Native addons** | npm's prebuilt binaries (sharp and the rest ship them for official Node) just work | built against Javet's embedded libnode, whose ABI may lag; prebuilt binaries can mismatch |
| **Node versions** | any LTS, same day as upstream | whatever Javet embeds, on Javet's schedule |
| **The container** | Node only: about 50 MB, starts in about 100 ms | a JVM plus libnode: about 200 MB, seconds to start |
| **The same globals as the isolate** | reimplemented in JavaScript over the runner protocol (§5) | the same Java binding classes |
| **What we maintain** | a small runner package, plus a conformance suite shared with the isolate | Javet Node mode's quirks |

The cost is two implementations of the globals, one in Java bindings (isolate) and one in JavaScript (runner). Both sit on the same host-API objects in the cluster (D-060), and one **conformance suite** runs the same scripts on both and must give the same answers. The link notes already chose this pattern for devices Javet doesn't build for.

## 4. Building and running are separate jobs
"Docker" or "Podman" appears in two places, doing two different things:
- **Building, on the developer's machine:**
  - **Why:** native addons are compiled machine code. `npm install` on a Mac produces Mac binaries, which can't run on the cluster's Linux nodes.
  - **What the container does:** our builder image runs `npm install` inside the exact Linux, CPU, C library and Node version the cluster runs, so the binaries match. That's all it's for.
  - **When it's skipped:** pure-JavaScript dependencies, which is most of npm, come out the same on any OS.
- **Running, on a cluster node:** the container engine (rootless Podman) starts the container and handles its filesystem, limits and network. **gVisor is the sandbox it runs the code inside**, a user-space kernel, so the code never talks to the host's kernel directly. gVisor plugs into the engine; it doesn't replace it.

They never meet: the developer's build produces the right files, and the cluster's engine plus gVisor runs them safely.

## 4b. Building: where `npm install` runs
Native addons are compiled for one Node ABI, CPU and C library, so the build must match the runtime exactly.

**Recommended to start: build on the developer's machine, in our builder image, and upload the result.**
```
automately deploy            # automately.json: { "runtime": "node", "node": "22" }
  └─ docker run automately/node-builder:22  npm ci --omit=dev   (matching Linux, CPU and libc)
  └─ hash every file of the app (source + node_modules)
  └─ upload only what the cluster lacks (the package store, D-083), then commit a "node artifact" manifest
```
- **No registry,** and nothing on the cluster runs npm's install scripts.
- **Deduplicated:** the content-addressed store means a redeploy that changed one source file uploads one file, even with 300 MB of `node_modules`.
- **Dependencies are vetted where they're built:** on the developer's machine, under their eyes, with their own npm audit.
- **It needs Docker locally** for native builds. Pure-JavaScript `node_modules` could skip Docker (`npm ci` on any OS gives the same files), and the CLI tells the two apart.

**Later options:**
- **A cluster build worker,** for people without Docker. It runs `npm ci` inside a gVisor container whose only network access is the npm registry, and stores the result the same way.
- **Bring your own image** (OCI, for anything that isn't Node, or a custom base). This is a separate later idea, and it would reuse everything below except the runner.

## 5. The execution layer (built once, used by containers and links)
This is the step the review put before containers and links. Everything below needs it.
- **Capabilities on nodes:** `NodeInfo` gains `capabilities` (`isolate`, `node`, `node:gvisor`, `node:unsandboxed`, `browser`, `files:local:<name>`…), plus `owner` (the operator, or a tenant for a tenant's link) and `tier`. Placement in `JobScheduler.candidates` filters on them. Nothing else in the scheduler changes.
- **The runner protocol:** JSON-RPC over one connection, a WebSocket.
  - **Runner → cluster:** host-API calls (`kv.get`, `files.write` with streamed bodies, `secrets.get` and so on), logs and state (`ctx.step`).
  - **Cluster → runner:** "run this", "stop", and incoming HTTP requests for services, streamed.
  - **On the cluster side,** the node holding the runner's connection builds `NodeHostApis` for the job, with the tenant taken from the job record and never from the runner. Calls go through the same objects as the isolate's, so scopes, quotas, rate limits and egress rules are identical.
  - **One protocol for both:** a runner in a container on a core node, and a link on someone's laptop, speak it the same way. The only difference is how the connection is made: a local socket the node hands to the container, or an outbound authenticated WebSocket from the link.
- **Jobs, orphans and fencing are unchanged:** the runner's job is a job like any other. If the container dies, the attempt ends. If the node dies, the orphan rules apply (D-009 to D-012).
- **Serving HTTP:** the ingress already proxies to "the node holding the job" (D-036). That node forwards each request over the runner protocol, either to a `ctx.serve` handler or to the app's own server on its `PORT`.

## 6. Running containers on a core node
- **The engine:**
  - **Preferred:** rootless Podman, or rootless Docker, driven through its API socket from the node. There's no extra agent process.
  - **Why rootless:** the container engine's socket is root on the host with rootful Docker.
  - **Which nodes:** only nodes with `AUTOMATELY_CONTAINERS=podman|docker` offer `node`. Everyone else is unaffected.
- **Isolation tiers:**
  - **runc** (namespaces, seccomp, cgroups): for the operator's own tenants, or trusted ones.
  - **gVisor (`runsc`):** a user-space kernel for untrusted tenants, and the default for tenant code when the node has it.
  - **Choosing per tenant:** the operator decides which tiers each tenant may use, in a runtime policy like the storage policy (D-057).
- **Every container is locked down:**
  - **Filesystem:** a read-only root; the app read-only; `/tmp` as a sized tmpfs.
  - **Privileges:** no Linux capabilities; `no-new-privileges`.
  - **Limits:** a user namespace; cgroup limits on memory, CPU and process count (the job's or service's).
  - **Network:** no host network. Outbound traffic goes through the node's egress proxy, which applies the cluster's rules (D-026): no private addresses unless the policy allows them, and DNS-rebinding-safe.
- **The app's files:** the node materializes the artifact once per version into its data directory (`<data>/node-apps/<artifact hash>`), from the package store and verified by hash, and mounts it read-only into each replica. A second replica or a restart reuses it.
- **Cold and warm starts:**
  - **Services** are long-running replicas, so start time matters little.
  - **Jobs** start a container each: about 100 to 300 ms with runc, more with gVisor. A warm pool per version can come later if needed.

## 7. What scripts see in the Node runtime
- **The same globals as the isolate,** from the runner: `kv`, `bus`, `bus.queue`, `files`, `secrets`, `jobs`, `log`, `ctx` (with `ctx.step`, `ctx.serve` and `ctx.stopRequested`).
- **`fs` is the decision with a trade-off (§10):**
  - **Recommended:** Node's real `fs` works on the container's own filesystem: its `/tmp` and the read-only app. The tenant's files are reached through `files`, and through `automately:fs`, which behaves as in the isolate.
  - **Later:** the cluster tree mounted at `/files` through FUSE, so `fs.readFileSync('/files/…')` works too. It was already noted as later in the [file system](vfs.md) design.
- **A real HTTP server:** `app.listen(process.env.PORT)` works, and the runner forwards the service's traffic to it. `ctx.serve` works too.
- **Secrets in the environment:** secret references in `automately.json` (`env: {DB_URL: {secret: "db-url"}}`) are resolved when the container starts. That's D-068's "not yet" item, and each use is audited, since the value leaves the secrets store.

## 8. Unsandboxed Node on your own device
The same runner and artifact, on a link the tenant owns, with no container. It's opt-in when the link joins (`--allow-unsandboxed`), runs only that tenant's code, and has an explicit list of who may deploy to that device.
- **Native addons are built on the device itself,** since it's often a Mac or arm64. The artifact carries source and lockfile, and the link runs `npm ci` locally.
- **The link rules stand:**
  - opt-in when the link joins
  - tenant-owned links only
  - only that tenant's code
  - an explicit list of allowed deployers, plus a separate `links:deploy-unsandboxed` scope
  - agents only if the link allows them

## 9. Building it (after packages)
1. **Execution layer:** capabilities and placement, the runner protocol, and remote host APIs. Tested first with a "local process runner" running the isolate's own conformance scripts, so the protocol is proven before containers exist.
2. **The runner and images:** the `@automately/runner` package, plus `automately/node-builder` and `automately/node-runtime` images per LTS version. Release-signed (D-069).
3. **Containers on core nodes:** rootless Podman or Docker, the lockdown, the egress proxy, materializing artifacts, the runtime policy, and gVisor.
4. **Deploy:** `automately deploy` with `"runtime": "node"`, the builder run and artifact upload, and services and jobs running there.
5. **Links:** the same runner on a tenant's device, unsandboxed, after the link join protocol.

**Spikes first:**
- **Runner latency:** a plain Node runner answering `kv.get` over the protocol, timed per call.
- **Rootless Podman from the JVM:** container start time, with `sharp` loading under runc and under gVisor.
- **gVisor's overhead:** on requests, and on native addons.
- **Artifact sizes:** for real apps (an Express API; Next.js standalone output), and how much deduplication saves across deploys.

## 10. Tony's answers (2026-10-08)
1. **The engine:** plain Node with our runner package.
2. **Builds:** on the developer's machine first, using our builder image only when native addons need it, with a cluster build worker later for people without Docker.
3. **`fs`:** the container's own filesystem. The tenant's files are reached through `files` and `automately:fs`, and a FUSE mount at `/files` can come later.
4. **The container engine:** rootless Podman.
5. **Isolation:** gVisor by default for tenant code, with runc only for tenants the operator marks trusted.
6. **Jobs and services** both run in Node, with the cold start documented and a warm pool later if needed.

## Questions as asked
1. **The engine:** plain Node with our runner package (recommended), or Javet's Node mode?
2. **Where builds happen:** on the developer's machine in our builder image (recommended to start), with a cluster build worker later? Or cluster builds from the start?
3. **What `fs` means in the Node runtime:** the container's own filesystem, with the tenant's files through `files` and `automately:fs` (recommended)? Or the cluster tree as `fs` from day one, which needs FUSE?
4. **The container engine:** rootless Podman (recommended: no daemon, rootless by design), or rootless Docker? Or support both?
5. **The default isolation for tenant code:** gVisor where the node has it, falling back to runc only for tenants the operator marks trusted (recommended)? Or runc unless the operator asks for gVisor?
6. **Jobs as well as services in Node** (recommended, with the cold start documented), or services only at first?
