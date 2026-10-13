# Design: securing the cluster itself (security groundwork, part 1)

**Status:** done (2026-10-07). Part A is D-065; Parts B–D are D-066 and D-067, with the operator's guide in [../guides/cluster.md](../guides/cluster.md). Tony decided CA custody and retiring the D-036 secret.

**What changed while building it (recorded in D-066 and D-067):**
- **Revoking is forced, not cooperative.** It uses Ignite's discovery `failNode` rather than `stopNodes`, which asks the node to stop itself and, in one JVM, interrupted the other node.
- **A join timeout.** A node whose certificate is refused stops trying after `C93_CLUSTER_JOIN_TIMEOUT_MS` instead of retrying forever.
- **The event bus's trust** is the CA as PEM, because Vert.x copies event-bus options and loses wrapped trust managers. So it doesn't consult revocations; Ignite discovery does.
- **Compose's `tls-init` service** makes the certificates for development, with `ca.key` in a volume.
- **Revocation is by node, not serial.** A revocation records the node id and when; certificates issued to that node before then are refused, so inviting it again lets it back in. The exact issue time is in the serial, because certificate dates only have second precision.
- **No `cluster renew` yet.** Renewing is `node invite <node>` again; nodes warn from 90 days before expiry. A bulk `cluster renew` can come later.

## Why
Cluster93's tenant-facing surfaces are hardened: tokens, scopes, the OpenAPI contract, isolates, egress rules (SECURITY.md). **Node-to-node traffic isn't.** A probe of a running node (2026-10-07) found:

| Port | What | Authenticated | Encrypted | Used by Cluster93 |
|---|---|---|---|---|
| 5701… | Ignite discovery | **no** | **no** | yes |
| 5801… | Ignite communication (data, replication) | **no** | **no** | yes |
| (dynamic) | Vert.x clustered event bus | **no** | **no** | yes |
| 8090 | internal endpoint (ingress → serving node) | shared secret (D-036) | **no** | yes |
| **10800** | **Ignite thin client / JDBC / ODBC** | **no** | **no** | **no; an Ignite default** |
| **11211** | **Ignite binary REST command protocol** | **no** | **no** | **no; an Ignite default** |

All bind on every interface (`0.0.0.0`).
- **10800 is the worst:** anything that reaches it can run SQL against every table, every tenant's data and the tokens table included.
- **Everything else:** any machine that can reach the discovery port can **join the cluster** as a node and receive replicated data.

SECURITY.md covers the first four ("put nodes on a private network"), but not 10800 or 11211.

## Part A: close what Cluster93 doesn't use (D-065)
Small, and worth doing right away, separately from the rest:
- **Disable the connectors:** `IgniteConfiguration.setClientConnectorConfiguration(null)` and `setConnectorConfiguration(null)` turn off the thin-client/JDBC/ODBC connector and the REST command protocol. Nothing in Cluster93 uses them.
- **Bind to one interface:** Ignite discovery and communication bind to a configured address (`C93_CLUSTER_BIND`, defaulting to the advertised host) instead of every interface.
- **A test that checks the listening ports:** it starts a clustered node and asserts it listens **only** on the expected set (API, ingress, internal, metrics, discovery, communication, event bus). Any new listener fails the build until it's added on purpose.
- **SECURITY.md** gains the finding and the fix.

## Part B: the cluster's certificate authority (D-066)
- **Keys:** EC P-256, widely supported by JSSE, Ignite and Vert.x.
  - **CA certificate:** 10 years.
  - **Node certificates:** 3 years, renewed by the operator (Part D).
  - **Subject alternative names:** the node id and its advertised host or IP.
- **On each node:** its private key, its certificate and the CA certificate, in the data directory (`<data>/tls/`, private key `0600`). A node restarts with what it has, needing nothing from anyone.
- **The CA private key, which issues certificates, stays on the operator's machine (Tony, 2026-10-07).**
  - **Where:** `c93 cluster init` writes `ca.key` to the operator's machine, and **the cluster never stores it**, so a compromised node can't mint certificates.
  - **What uses it:** issuing and renewal happen in the operator's CLI.
  - **Bring your own:** trusting an existing CA (step-ca, Vault, a corporate one) is a later option.
- **Revocation:** a list of revoked certificate serials in the cluster. Every TLS check consults it, and revoking a node also stops it in Ignite.

## Part C: TLS on every node-to-node connection (D-066)
| Connection | How |
|---|---|
| Ignite discovery and communication | `IgniteConfiguration.setSslContextFactory(...)`: a context built from the node's key and certificate, trusting only the cluster CA, **client authentication required**, TLS 1.3 |
| Vert.x clustered event bus | `EventBusOptions.setSsl(true)`, the node's PEM key and certificate, CA trust, `ClientAuth.REQUIRED` |
| Internal endpoint (8090) | HTTPS with mutual TLS. The ingress client presents its node certificate. **The D-036 shared secret is retired** (Tony). |
| Metrics (9090) | optional TLS; stays on the private network for Prometheus |

**Modes:**
- **`C93_CLUSTER_TLS=required`** is the default when clustered. A node without certificates **refuses to start**, with an error naming `c93 cluster init` and `node join`.
- **`off`** is for development and in-JVM tests only. It must be set explicitly and is logged as a warning at every start.

## Part D: creating a cluster, and joining one (D-067)
**A new cluster:**
```bash
c93 cluster init --nodes node1,node2,node3 --out ./cluster-tls
```
This writes the CA and a certificate for each listed node, in the style of CockroachDB's `cert create-ca` and `create-node`:
- `ca.key`: keep it safe and back it up; the cluster never stores it
- `ca.crt`: public
- one `nodeN.join` file per node (its bundle)

The compose file reads those `.join` files, so `docker compose up` (and the demo) still starts a secure cluster in one command, with `cluster init` run first.

**Adding a node later: one line, signed on the operator's machine.**
```bash
# on the operator's machine (ca.key is here)
c93 node invite node4 --host 10.0.0.14 --ca ./ca.key
# prints a line to run on the new machine:
C93_JOIN=c93_nodebundle_H4sIAAAA…  docker compose up -d node4     # or: cluster93-server --join c93_nodebundle_…
```
**What the CLI does, locally:** it generates node4's key pair, signs node4's certificate (subject alternative names: `node4`, `10.0.0.14`) with `ca.key`, and packs **node4's key, node4's certificate and the CA certificate** into one base64 string (gzipped, about 1–2 KB). The new node unpacks it into `<data>/tls/` and starts with TLS.

**The bundle never contains `ca.key`.** Putting the CA key in a one-liner would hand the master key to every new node and to every shell history it passes through.

**The bundle is a secret for node4 only:**
- it can't create other nodes or sign anything
- the certificate is bound to node4's name and address
- if it leaks, `c93 node revoke node4` ends it

**The strict variant: nothing secret travels.**
```bash
cluster93-server --prepare node4                              # new machine: prints its public key; the private key never leaves
c93 node invite node4 --key <public key> --ca ./ca.key  # operator: prints a certificate-only line
```

**Renewal needs the operator** (only `ca.key` signs):
- **Lifetime:** node certificates last **3 years**.
- **Renewing:** `c93 cluster renew --ca ./ca.key` prints renewal lines for every node expiring within 90 days.
- **Warnings:** nodes and the CLI warn from 90 days before expiry.

**Removing a node:** `c93 node revoke node4` adds its serial to the revoked list and stops it in Ignite.

## What this doesn't cover
- **Links** (a later phase) reuse this CA as their trust root.
- **TLS for clients:** the API and ingress still terminate at a proxy (SECURITY.md). Built-in TLS, for example ACME, is a separate decision.
- **Encryption at rest** (Ignite's transparent data encryption) is a separate decision.
- **Core nodes across regions:** they still belong on one network, because Ignite's synchronous replication dislikes latency.

## Tests
- **Listening ports** (Part A): only the expected set.
- **TLS required:** a node without certificates refuses to start, with the message; a node with a certificate from **another** CA can't join discovery; plain-TCP connections to discovery, communication and the event bus are refused.
- **Joining:** a fresh node joins with its bundle and no other setup; a node with a certificate from another CA is refused by discovery.
- **Revocation:** a revoked node is stopped and can't rejoin.
- **Bundles:** a bundle for node4 can't start a node claiming another name or address; a tampered bundle is refused; the strict variant keeps the private key on the new machine.
- **Renewal:** `cluster renew` reissues certificates that are about to expire, and nodes warn ahead of expiry.
- **The existing suites:** in-JVM cluster tests run with TLS on, using an ephemeral CA per test class from a helper; DockerClusterIT with TLS through the compose `init` service; the benchmarks rerun to measure TLS's cost on replication.

## Open questions for Tony
1. ~~CA key custody~~ **Decided:** `ca.key` stays on the operator's machine. The one-liner carries the node's own bundle, never the CA key.
2. ~~D-036's shared secret~~ **Decided:** retired once mutual TLS is required.
3. ~~Part A now?~~ **Done** (D-065).
