# Architecture & Design Specification: Cluster93 Apps & The Web Console

**Status:** Draft  
**Target:** Phase 6 (Apps Primitive & Management Console)  
**Authors:** Tony / Antigravity (Adam & Lynn Incorporated)  

---

## 1. Executive Summary

This specification unifies two major platform primitives:
1. **Cluster93 Apps:** A first-class deployment model enabling developers to deploy full-stack web applications (Next.js, Vite, Remix, static frontends) comprising:
   - **Content-addressed static frontend assets** stored and served directly from Cluster93's Virtual File System (VFS).
   - **An optional supervised backend service** running as a Cluster93 service (`cluster93.core.service.ServiceSupervisor`), sharing the same origin via Ingress without CORS.
   - **Single-origin ingress routing** (`cluster93.json`) supporting client-side SPA fallback, static asset caching, and dynamic `/api/*` or server-side routing to the backend service.
2. **The Web Management Console:** Designed and hosted **as the platform's canonical first-party App**, proving the capability of the runtime.
   - Built with **Next.js (App Router)** and **Tailwind CSS / shadcn/ui**.
   - Strictly typed against the contract-first OpenAPI specification (`openapi.yaml`).
   - Supports **strict operator lockdown** (custom subdomains, private IP allowlisting, or management node isolation).
   - Enforces a clear identity boundary between **System Users (Operators)** and **Tenant Users** via our D-070 identity foundation.

---

## 2. Cluster93 Apps: Concept & Lifecycle

An **App** in Cluster93 is an atomic, immutable bundle containing static client assets and an optional backend service.

```
                              Public Ingress (:8000)
                                        │
                      Host: shop--tenant.example.com
                                        │
           ┌────────────────────────────┴────────────────────────────┐
           │                                                         │
    Path: /api/* (or SSR routes)                              Path: /* (Assets & Pages)
           │                                                         │
           ▼                                                         ▼
┌───────────────────────────┐                             ┌───────────────────────────┐
│     Backend Service       │                             │      App File Router      │
│  Supervised Node/JS Svc   │                             │   (VFS Blob Store / ETag) │
│  (node:http / Express)    │                             │   Fallback: index.html    │
└───────────────────────────┘                             └───────────────────────────┘
```

### 2.1 Storage & The Virtual File System (VFS)
- Every app deployment is immutable and content-addressed:
  - Files are uploaded to the tenant's VFS under a dedicated system prefix: `/.apps/<app-name>/deployments/<deploy-id>/`.
  - Static files share the existing content-addressed chunk store (`FILE_CHUNKS`, D-025/D-075). Identical assets across builds (e.g. `_next/static/chunks/main-abc.js`) are uploaded once and deduplicated automatically across deployments.
- Promotion pointers:
  - `/.apps/<app-name>/active`: Stores the JSON pointer referencing the currently promoted deployment ID and its backend service revision.
  - Rolling back an app simply updates this pointer atomically (D-077).

### 2.2 App Manifest (`cluster93.json`)
Placed at the root of the project to declare routing, static outputs, and backend linkage:

```json
{
  "name": "console",
  "version": "1.0.0",
  "routes": [
    {
      "src": "/api/(.*)",
      "dest": "service:console-api/$1"
    },
    {
      "src": "/v1/(.*)",
      "dest": "api:/v1/$1"
    },
    {
      "handle": "filesystem"
    },
    {
      "src": ".*",
      "dest": "/index.html"
    }
  ],
  "backend": {
    "entry": "server.js",
    "memoryMb": 128,
    "timeoutMs": 30000
  },
  "headers": [
    {
      "source": "/_next/static/(.*)",
      "headers": {
        "Cache-Control": "public, max-age=31536000, immutable"
      }
    }
  ]
}
```

### 2.3 Deployment Workflow (`c93 apps deploy`)
1. **Developer Build:** Developer runs `next build` (with `output: 'export'` for static or hybrid backend output).
2. **Hash & Sync:** The CLI hashes local files and checks which chunk hashes already exist on the cluster (`POST /v1/apps/{name}/manifest`).
3. **Upload Missing Chunks:** Uploads only newly modified blobs.
4. **Deploy & Preview:** An immutable deployment ID is minted. The deployment is accessible immediately via preview URL:
   `https://<deploy-hash>--<app>--<tenant>.<ingress-domain>`
5. **Promotion:** `c93 apps promote <app> <deploy-hash>` shifts the canonical host (`<app>--<tenant>.<domain>` or custom domain) to the new deployment with zero downtime.

---

## 3. Server-Side Routing & Ingress Engine

The ingress proxy (`cluster93.server.ingress.IngressProxy`) is extended with an **AppRouter**:

1. **Host Matching:**
   - Matches incoming HTTP request against registered apps (system apps, tenant apps, and custom domains).
2. **Route Resolution:**
   - **Service Routing (`src: /api/(.*) -> dest: service:<svc>`):**
     Proxy streams the request directly to the tenant's supervised service over the cluster's internal network (`InternalHttpVerticle`).
   - **Same-Origin API Routing (`src: /v1/(.*) -> dest: api:/v1/$1`):**
     Proxies to the node's local `ApiVerticle`, eliminating browser CORS.
   - **Static Asset Serving:**
     Streams the file from VFS with chunk streaming (`FileResponder`), generating `ETag` and supporting `Range` / 304 Not Modified.
   - **SPA / App Fallback:**
     If a route does not match a file on disk (e.g. `/tenants/demo/jobs`), falls back to `/index.html` or the framework's designated error/page handler.

---

## 4. The Web Console as a Cluster93 App

The Cluster93 Web Console is deployed as an app named `console` in the reserved `system` tenant.

### 4.1 Technology Stack
- **Framework:** Next.js (App Router).
- **Styling & Components:** Tailwind CSS v4, shadcn/ui primitives, Lucide icons.
- **Data & State Management:** TanStack React Query + typed API client generated directly from `openapi.yaml` via `openapi-fetch`.
- **Live Observability:** Native browser `fetch` streaming of Server-Sent Events (`/v1/jobs/{id}/events`) with bearer auth.

### 4.2 Security & Public Lockdown
The Console must **never be inadvertently exposed** on the public web. It enforces three lines of defense:

1. **Custom Host / Subdomain Binding:**
   - Operators configure `C93_CONSOLE_DOMAIN` (e.g. `console.internal.company.com`).
   - Requests arriving on any other host or IP return `404 Not Found`.
2. **Network / CIDR Access Control:**
   - Ingress enforces `C93_CONSOLE_ALLOWED_CIDRS` (e.g. `127.0.0.1/32, 10.0.0.0/8`). Requests from unauthorized IPs are rejected with `403 Forbidden` before static assets or backend endpoints are touched.
3. **Dedicated Node Role Isolation:**
   - In a production cluster partitioned via Node Roles (`C93_ROLES`), the console app can be pinned strictly to an internal management node with no public IP.

---

## 5. Identity: System Users vs. Tenant Users

Leveraging the **D-070** identity architecture, the Console presents distinct interfaces based on the authenticated actor's permissions:

```
                            Authentication Probe (GET /v1/whoami)
                                              │
                    ┌─────────────────────────┴─────────────────────────┐
                    ▼                                                   ▼
            isOperator == true                                 isOperator == false
       (System / Platform Operator)                                (Tenant User)
                    │                                                   │
     ┌──────────────┴──────────────┐                     ┌──────────────┴──────────────┐
     │  Cluster Infrastructure View│                     │  Tenant Workspace View      │
     │  • Node topology & roles    │                     │  • Jobs & ctx.step traces   │
     │  • Ignite storage & WAL     │                     │  • Supervised Services      │
     │  • Tenant quotas & policies │                     │  • VFS File Explorer        │
     │  • Tamper-evident Audit Log │                     │  • Apps & Deployments       │
     └─────────────────────────────┘                     │  • Team Invites & Roles     │
                                                         └─────────────────────────────┘
```

### 5.1 System User (Operator) Capabilities & Guarantees
- **Cluster Topology:** View live nodes, heartbeats, CPU/memory isolate consumption, and node roles (`api`, `ingress`, `worker`, `data`).
- **Storage Infrastructure:** Monitor Apache Ignite partition health, disk WAL rebalances, and storage provider quotas.
- **Global Governance:** Configure egress firewall policies, audit retention, and tenant quotas.
- **Zero Tenant Espionage Invariant (D-044):** The operator console cannot view tenant private files, decrypt tenant secrets, or spy on tenant payload contents.

### 5.2 Tenant User Experience
- **Tenant Context Switcher:** Users belonging to multiple tenants switch between organizations in the top navigation bar without logging out.
- **Jobs & Pipelines:** Real-time log streamer, checkpoint timeline (`ctx.step`), and rerun/cancel actions.
- **Microservices:** Service inspector, ingress route mapper, health status, and live log tail.
- **Virtual File System:** Visual file explorer, drag-and-drop chunked uploads, and public share link generation.
- **Apps Manager:** Deployment list, preview links, promotion controls, and rollbacks.
- **Access Control:** Manage team memberships, assign custom roles, and generate invite codes.

---

## 6. Implementation Phasing

### Phase 6.1: The Apps Primitive (Backend & Ingress)
- Add `/v1/apps` routes to `openapi.yaml` and implement `AppVerticle`.
- Create `AppRouter` in Ingress to resolve app routes, static assets from VFS, SPA fallback, and proxying to backend services.
- Add `c93 apps deploy` and `c93 apps promote` to `cluster93-cli`.

### Phase 6.2: Console Next.js Application
- Scaffold Next.js App Router project in `console/` with Tailwind CSS, shadcn/ui, and OpenAPI TypeScript client.
- Implement `/login` (Invite redemption / API token / Passkey prototype) and identity context provider.
- Implement Tenant Workspace views (Jobs, Services, Files, Apps).
- Implement Operator Infrastructure views (Nodes, Cluster health, Audit logs).

### Phase 6.3: Packaging & Default Seeding
- Configure Maven to build the Next.js console static export (`output: 'export'`) during release builds.
- Seed the compiled console assets automatically into the `system` tenant on initial cluster startup.
- Document deployment and configuration in `README.md` and `docs/guides/console.md`.
