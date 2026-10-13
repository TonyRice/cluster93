# The Node runtime: npm, native addons and Node APIs, sandboxed

**Status:** accepted (Tony, 2026-10-08) and reviewed for holes the same day, twice (§11, D-085; §12, D-086). Not built yet; it comes after packages. Decisions: D-084, D-085, D-086. It brings together the earlier notes on Node services, containers, unsandboxed links and link capabilities, and sits next to [packages](packages.md) (D-083).

## 1. What it's for
Packages cover pure JavaScript in the isolate. The Node runtime covers what an isolate can't run:
- **Native addons:** `sharp`, `bcrypt`, `better-sqlite3`, `canvas`, anything with a `.node` file.
- **Node's APIs:** `child_process`, `net`/`tls` servers, `worker_threads`, `http.createServer`, `vm`.
- **Whole frameworks that need a real Node process:** Express or Fastify servers as they are, Next.js server rendering, Prisma.

The same contract as everywhere else: a default export `(input, ctx)`, the same globals (`kv`, `bus`, `files`, `secrets`, `jobs`, `ctx`), and `c93 deploy` with `"runtime": "node"` in `cluster93.json`.
- **Moving code between runtimes is one line** as long as it uses the globals, `files` or `c93:fs`.
- **`fs` itself means different things:** the tenant's files in the isolate, and the container's own disk in Node (§7). Deploy warns when code switching runtimes uses `fs` directly.

## 2. Three places code can run
| Runtime | Where | Isolation | Whose code | npm |
|---|---|---|---|---|
| **isolate** (today) | core nodes, any link | V8 isolate, limits, host APIs only | any tenant | bundled pure JS (packages) |
| **node** | nodes offering containers: core nodes or links | a container, gVisor for untrusted tenants | any tenant the operator allows | full `npm ci`, native addons |
| **node, unsandboxed** | **a link owned by the same tenant**, opted in | none: the device owner's own user | only that tenant | full, built for that device |

The scheduler enforces this: a job or service asks for a runtime, and placement only considers nodes that offer it **and** are allowed for that tenant.
- **Off by default** (Tony, D-085): the Node runtime is unavailable to a tenant until the operator enables it. The operator does that in a runtime policy (like storage policies, D-057), which also sets the isolation tier and the limits: how many containers, the memory per container, and the total memory.
- **Nothing can run it:** a job or service that no live node could run (for example gVisor required, and no node offers it) is refused at submission or deploy, with the reason. A service already deployed shows "waiting for a node offering node:gvisor" instead of retrying silently.

## 3. The engine: plain Node, not Javet's Node mode (Tony)
**Official Node**, with our runner written as a small npm package, rather than Javet's Node mode (embedded libnode), which the earlier notes assumed.

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
c93 deploy            # cluster93.json: { "runtime": "node", "node": "22" }
  └─ docker run cluster93/node-builder:22  npm ci --omit=dev   (matching Linux, CPU and libc)
  └─ pack the app into layers: node_modules, then source (D-086)
  └─ upload only the layers the cluster lacks (the package store, D-083), then commit a "node artifact" manifest
```
- **No registry,** and nothing on the cluster runs npm's install scripts.
- **Layers, not files** (D-086): a typical `node_modules` is 20 000 to 40 000 files, too many to upload or fetch one by one. A Node artifact is a few content-addressed layers (tar archives, hashed like any package file), like a container image: dependencies, then source.
  - **Deduplicated:** a redeploy that changed only source uploads only the small source layer, even with 300 MB of `node_modules`. A lockfile change rebuilds the dependency layer.
  - **Packages stay file by file,** since they're small. How big layers should be (one dependency layer, or split by size) is settled in the artifact-size spike.
- **Dependencies are vetted where they're built:** on the developer's machine, under their eyes, with their own npm audit.
- **It needs Docker locally** for native builds. Pure-JavaScript `node_modules` could skip Docker (`npm ci` on any OS gives the same files), and the CLI tells the two apart.
- **A lockfile is required:** `npm ci` builds exactly what `package-lock.json` says, and deploy refuses without one.
- **The artifact records what it was built for:** CPU architecture, C library and Node major version. Placement only uses nodes that match (x64 with glibc for now; arm64 nodes are tabled).
- **Our own packages** can be used from Node code: the CLI copies the resolved package versions (D-083) into `node_modules` at build time.

**Later options:**
- **A cluster build worker,** for people without Docker. It runs `npm ci` inside a gVisor container whose only network access is the npm registry, and stores the result the same way.
- **Bring your own image** (OCI, for anything that isn't Node, or a custom base). This is a separate later idea, and it would reuse everything below except the runner.

## 5. The execution layer (built once, used by containers and links)
This is the step the review put before containers and links. Everything below needs it.
- **Capabilities on nodes:** `NodeInfo` gains `capabilities` (`isolate`, `node`, `node:gvisor`, `node:unsandboxed`, `browser`, `files:local:<name>`…), plus `owner` (the operator, or a tenant for a tenant's link) and `tier`. Placement in `JobScheduler.candidates` filters on them. Nothing else in the scheduler changes.
- **The runner protocol:** JSON-RPC over one connection. For a container, that's a local Unix socket the node mounts into it, with a secret good for that one job attempt. For a link, it's an outbound authenticated WebSocket.
  - **Runner → cluster:** host-API calls (`kv.get`, `files.write` with streamed bodies, `secrets.get` and so on), logs and state (`ctx.step`).
  - **Cluster → runner:** "run this", "stop", and incoming HTTP requests for services, streamed.
  - **On the cluster side,** the node holding the runner's connection builds `NodeHostApis` for the job, with the tenant taken from the job record and never from the runner. Calls go through the same objects as the isolate's, so scopes, quotas, rate limits and egress rules are identical.
  - **One protocol for both:** a runner in a container on a core node, and a link on someone's laptop, speak it the same way. The only difference is how the connection is made: a local socket the node hands to the container, or an outbound authenticated WebSocket from the link.
- **The runner isn't trusted** (D-086): user code runs in the same process and can bypass or rewrite it. **Security never depends on the runner:** the container has no network, and the cluster checks every call against the job's tenant and attempt, so a script talking to the socket directly gains nothing it couldn't do through the globals.
- **Jobs, orphans and fencing are unchanged:** the runner's job is a job like any other. If the container dies, the attempt ends. If the node dies, the orphan rules apply (D-009 to D-012).
  - **Fenced:** every call carries the job's attempt number, and the node refuses calls from an attempt that has been superseded. A stale container can't write.
  - **No orphaned containers:** each container is labelled with its node's process id (`instanceId`). A node starting up removes any labelled container from an earlier life. The runner exits by itself if its socket closes and doesn't come back within a few seconds.
- **Stopping:** a stop request sets `ctx.stopRequested`, then sends SIGTERM. After the grace period (D-020) the container is killed. A job's timeout ends it the same way.
- **Logs:** the runner forwards `console` output, and the container's stdout and stderr go to the job's log too, with the same line limits as scripts.
- **Capacity:** nodes offering containers advertise container capacity separately from isolate slots, as a number of containers plus a memory budget. Placement checks each job's or service's memory request against it.
  - **The budget is set explicitly** (D-086), `C93_CONTAINER_MEMORY_MB`, because containers share the machine with the node's own Java process. A node refuses to start if the budget plus its Java heap exceeds the machine's memory.
- **Serving HTTP:** the ingress already proxies to "the node holding the job" (D-036). That node forwards each request over the runner protocol, either to a `ctx.serve` handler or to the app's own server on its `PORT`.
  - **Readiness:** the runner reports ready once the port accepts connections. Until then the ingress holds requests briefly, then answers 503. A server that stops answering its health check is restarted.
  - **One running copy per service** (Tony, D-086): no replicas or load balancing at first. If its node dies, it restarts on another node, with a cold start.
  - **Redeploys have a short gap:** the old version stops before the new one is ready. Zero-downtime switching (start the new one, wait until ready, then switch) comes with replicas.
  - **Inbound is HTTP and WebSockets only:** no raw TCP or UDP servers.
  - **WebSockets** (Tony, D-085): the ingress learns to proxy `Upgrade` requests, which it strips today. The runner protocol carries the connection as a two-way stream, so Socket.IO and `ws` servers work as they are. The isolate's `ctx.serve` gets a WebSocket handler too, so both runtimes have them.

## 6. Running containers on a core node
- **The engine** (Tony): rootless Podman, driven through its API socket from the node, with no extra agent process. Rootless, because a rootful engine's socket is root on the host. Only nodes with `C93_CONTAINERS=podman` offer `node`; everyone else is unaffected.
- **Isolation tiers:**
  - **runc** (namespaces, seccomp, cgroups): for the operator's own tenants, or trusted ones.
  - **gVisor (`runsc`):** a user-space kernel for untrusted tenants, and the default for tenant code when the node has it.
  - **Choosing per tenant:** the operator decides which tiers each tenant may use, in a runtime policy like the storage policy (D-057).
- **Every container is locked down:**
  - **Filesystem:** a read-only root; the app read-only; `/tmp` as a sized tmpfs.
  - **Privileges:** no Linux capabilities; `no-new-privileges`.
  - **Limits:** a user namespace; cgroup limits on memory, CPU and process count (the job's or service's).
  - **Network: none at all** (Tony, D-085). The container has no network interface beyond loopback.
    - **How code still connects:** the runner reroutes Node's `net`, `tls`, `dns`, `http` and `https` through its socket, and the node opens each outbound connection only if the cluster's egress rules allow it (D-026): no private addresses unless the policy allows them, connecting to the exact address that was checked, and DNS-rebinding-safe. The checks are the same as `fetch`'s.
    - **Database drivers work unchanged:** `pg`, `mysql2`, `ioredis` and most others are pure JavaScript over `net`.
    - **DNS** (D-086): `dns.lookup` and the `dns.resolve*` calls are both rerouted, so drivers that look up SRV records (`mongodb+srv://`) work, under the same egress rules.
    - **Programs started from Node code** (D-086): the tunnel lives inside Node, so `child_process.spawn('curl', …)` or a spawned `git` can't use it. The runner also offers a local proxy endpoint, through the same tunnel and egress rules, set as `HTTP_PROXY` and `HTTPS_PROXY` for spawned programs. Programs that ignore proxy settings won't connect, and the guide says so.
    - **What doesn't work:** networking done inside native code, and UDP.
    - **Why not filter a real network:** it's the only approach that needs no privileged networking on the node, which rootless Podman can't give anyway. It behaves the same under runc, under gVisor and on links.
  - **`/tmp`** is a tmpfs, and its size counts against the container's memory limit, so it's set explicitly.
- **The app's files:** the node materializes the artifact once per version into its data directory (`<data>/node-apps/<artifact hash>`), from the package store and verified by hash, and mounts it read-only into the container. A restart reuses it. Versions nobody has used for a while are removed first when space runs low, within `C93_MIN_FREE_DISK_MB`.
- **Images** (D-085): `cluster93/node-runtime` and `cluster93/node-builder` are pinned by digest in each signed release (D-069). The CLI and nodes pull by digest, from a registry or mirror the operator configures, or use images the operator preloaded, so clusters without internet access work. An image whose digest doesn't match is refused.
- **gVisor and the mounted socket:** gVisor reaches host Unix sockets only with its `--host-uds` setting. That's checked in the gVisor spike, with a fallback connection method if needed.
- **Rootless Podman, gVisor and limits together** (D-086) aren't a given: gVisor's rootless mode has limits, and memory and CPU limits under rootless Podman need cgroup delegation set up on the host. Both are in the gVisor spike, the operator guide lists the host setup, and a node only advertises `node:gvisor` once its own start-up check passes (a container started with limits, under `runsc`, reaching its socket).
- **Cold and warm starts:**
  - **Services** are long-running, so start time matters little.
  - **Jobs** start a container each: about 100 to 300 ms with runc, more with gVisor. A warm pool per version can come later if needed.

## 7. What scripts see in the Node runtime
- **The same globals as the isolate,** from the runner: `kv`, `bus`, `bus.queue`, `files`, `secrets`, `jobs`, `log`, `ctx` (with `ctx.step`, `ctx.serve` and `ctx.stopRequested`).
- **`fs`** (Tony): Node's real `fs` works on the container's own filesystem, its `/tmp` and the read-only app. The tenant's files are reached through `files`, and through `c93:fs`, which behaves as in the isolate.
  - **Later:** the cluster tree mounted at `/files` through FUSE, so `fs.readFileSync('/files/…')` works too. It was already noted as later in the [file system](vfs.md) design.
- **A real HTTP server:** `app.listen(process.env.PORT)` works, and the runner forwards the service's traffic to it. `ctx.serve` works too.
- **Secrets in the environment:** secret references in `cluster93.json` (`env: {DB_URL: {secret: "db-url"}}`) are resolved when the container starts. That's D-068's "not yet" item.
  - **Who may:** only deployers with `secrets:use` (the D-068 rule).
  - **Audited:** each use is audited, since the value leaves the secrets store.
  - **Visible to every dependency:** the guide says so plainly. Environment variables can be read by any npm package in the container.
- **Jobs started from Node code** (`jobs.run`) inherit the parent's runtime and artifact, so they can use Node's APIs too.

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
1. **Execution layer:** capabilities and placement, the runner protocol, and remote host APIs. **The shared conformance suite is built here:** the same scripts must give the same answers in the isolate and through the runner. It's tested first with a "local process runner", so the protocol is proven before containers exist.
2. **The runner and images:** the `@cluster93/runner` package, plus `cluster93/node-builder` and `cluster93/node-runtime` images per LTS version. Release-signed (D-069).
3. **Containers on core nodes:** rootless Podman, the lockdown, network tunnelled through the runner, materializing artifacts, the runtime policy, gVisor, and WebSockets through the ingress.
4. **Deploy:** `c93 deploy` with `"runtime": "node"`, the builder run and artifact upload, and services and jobs running there.
5. **Links:** the same runner on a tenant's device, unsandboxed, after the link join protocol.

**Spikes first:**
- **Runner latency:** a plain Node runner answering `kv.get` over the protocol, timed per call.
- **Rootless Podman from the JVM:** container start time, with `sharp` loading under runc and under gVisor.
- **gVisor's overhead:** on requests, and on native addons; whether the mounted runner socket works under `--host-uds`; and gVisor under rootless Podman with cgroup limits (D-086).
- **Tunnelled networking:** `pg` and `ioredis` through the runner's `net` and `tls`, and their throughput.
- **Artifact sizes:** for real apps (an Express API; Next.js standalone output), how much layers save across deploys, and how big a layer should be (D-086).

## 10. Tony's answers (2026-10-08)
1. **The engine:** plain Node with our runner package.
2. **Builds:** on the developer's machine first, using our builder image only when native addons need it, with a cluster build worker later for people without Docker.
3. **`fs`:** the container's own filesystem. The tenant's files are reached through `files` and `c93:fs`, and a FUSE mount at `/files` can come later.
4. **The container engine:** rootless Podman.
5. **Isolation:** gVisor by default for tenant code, with runc only for tenants the operator marks trusted.
6. **Jobs and services** both run in Node, with the cold start documented and a warm pool later if needed.

## 11. Review for holes (2026-10-08, D-085)
Each was closed in the sections above. Tony chose the four marked.
- **Outbound network couldn't be policed as designed** (Tony): no network at all; `net`, `tls`, `dns` and `http` tunnelled through the runner, with the cluster's egress checks (§6).
- **Orphaned containers** after a node's JVM crashes: labelled and removed at start; the runner exits when its socket goes; calls are fenced by attempt (§5).
- **Capacity:** containers are counted separately, with a memory budget (§5).
- **Work no node can run:** refused with the reason, or the service shows what it's waiting for (§2).
- **Image supply chain** and clusters without internet: images pinned by digest in the signed release, from a mirror or preloaded (§6).
- **CPU, C library and Node version** recorded on artifacts and matched by placement (§4b).
- **WebSockets** (Tony): proxied by the ingress for both runtimes (§5).
- **Readiness and health checks** for app servers (§5).
- **The "one line" claim** for switching runtimes: qualified for `fs` (§1).
- **Child jobs** inherit the runtime and artifact (§7).
- **Packages usable from Node code:** copied into `node_modules` at build (§4b).
- **Off by default, with limits** (Tony): the runtime policy (§2).
- **Environment secrets:** `secrets:use`, audited, and documented as visible to dependencies (§7).
- **gVisor's host sockets:** checked in the spike (§6).
- **Smaller gaps:** the lockfile is required; `/tmp` counts against memory; artifacts on disk are evicted; stopping is SIGTERM, then grace, then kill; logs; the conformance suite built in step 1.
- **Corrected:** the runner connects over a local socket, not a WebSocket; Podman only.

## 12. Second review (2026-10-08, D-086)
Each was closed in the sections above. Tony chose the one marked.
- **Too many files per artifact:** Node artifacts are a few layers, dependencies then source (§4b).
- **Programs spawned from Node had no network:** a local proxy endpoint through the same tunnel (§6).
- **DNS SRV lookups:** the `dns.resolve*` calls are rerouted too (§6).
- **The runner isn't trusted:** stated as a rule; security never depends on it (§5).
- **Rootless Podman with gVisor and cgroup limits:** in the gVisor spike, with a start-up check before a node offers `node:gvisor` (§6).
- **Containers competing with the JVM for memory:** an explicit container memory budget, checked at start (§5).
- **Stopping a version with a security hole:** block, which stops running services too (packages §7).
- **Limits stated plainly** (Tony): one running copy per service, a short gap on redeploy, and HTTP and WebSockets only inbound (§5). Replicas and zero-downtime deploys come later.
