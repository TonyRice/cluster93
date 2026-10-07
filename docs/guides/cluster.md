# Running a cluster: node certificates

Core nodes talk to each other only over **TLS with node certificates on both sides** (D-066). This covers:
- Ignite discovery
- Ignite communication, which carries data and replication
- the clustered event bus
- the internal endpoint the ingress uses

A machine without a certificate from your cluster's CA can't join the cluster or read its traffic, even on the same network.

**The CA is your cluster's own.** Nothing outside the cluster trusts it, and **its private key (`ca.key`) stays on your machine**. The cluster never stores it, and it's needed only to add or renew nodes.

## A new cluster
```bash
automately cluster init --nodes node1,node2,node3 --host node1=10.0.0.11 --host node2=10.0.0.12 --host node3=10.0.0.13
#   ./cluster-tls/ca.key       keep it safe and back it up
#   ./cluster-tls/ca.crt
#   ./cluster-tls/node1.join   one line per node
```
Start each node with its line:
```bash
AUTOMATELY_NODE_ID=node1 AUTOMATELY_JOIN=$(cat cluster-tls/node1.join) java -jar automately.jar
```
- **Where the certificate goes:** the node installs it in `<AUTOMATELY_DATA_DIR>/tls/` (or `AUTOMATELY_TLS_DIR`). Its private key file is owner-only.
- **Later starts:** they don't need `AUTOMATELY_JOIN`.
- **Mismatches are refused:** a line for one node can't start another.

**With Docker Compose,** `docker compose up` does this for you: a one-shot `tls-init` service creates the CA and three node certificates in a volume on the first start. That's a development convenience: `ca.key` sits in that volume. For anything real, run `automately cluster init` yourself and keep `ca.key` off the servers.

## Adding a node: one line
On the machine with `ca.key`:
```bash
automately node invite node4 --host 10.0.0.14 --ca ./cluster-tls/ca.key
# AUTOMATELY_JOIN=atly_nodebundle_H4sI…
```
Run that on the new machine: `AUTOMATELY_JOIN=atly_nodebundle_… java -jar automately.jar`, or `--join atly_nodebundle_…`.

**What's in the line:** node4's private key, its certificate and the CA certificate, gzipped and base64-encoded. **It never contains `ca.key`.**
- **Treat it like a password for node4:** it can't create other nodes or sign anything.
- **It's bound to node4:** the certificate names node4 and its addresses.
- **If it leaks:** `automately node revoke node4`.

### The strict way: nothing secret travels
```bash
automately-server --prepare node4      # on the new machine: makes its key, keeps it, prints the public key
automately node invite node4 --host 10.0.0.14 --key <public key> --ca ./cluster-tls/ca.key   # on yours: a certificate-only line
```

## Removing a node
```bash
automately node revoke node4      # with the admin token
```
**What happens:**
- **Recorded:** the revocation is stored in the cluster and sent to every node.
- **Refused:** from then on, every node's TLS refuses node4's certificate.
- **Forced out:** if node4 is in the cluster, it's dropped from discovery, whether it cooperates or not, and its process stops.

**To let it back in,** invite it again. The new certificate is accepted, because only certificates issued before the revocation are refused.

## Renewing
- **Lifetimes:** node certificates last 3 years and the CA 10.
- **Warnings:** a node warns in its log from 90 days before its certificate expires.
- **To renew:** run `automately node invite <node>` again and restart the node with the new line.

## Settings
| Variable | Default | |
|---|---|---|
| `AUTOMATELY_CLUSTER_TLS` | `required` | `off` turns certificates off. **Development only**: nodes then talk without authentication or encryption, and each start logs a warning. |
| `AUTOMATELY_JOIN` | none | A join line to install (`atly_nodebundle_…` or `atly_nodecert_…`) |
| `AUTOMATELY_TLS_DIR` | `<AUTOMATELY_DATA_DIR>/tls` | Where the node keeps its key and certificates |
| `AUTOMATELY_CLUSTER_BIND` | the advertised host, if set | The private address Ignite listens on (D-065) |
| `AUTOMATELY_CLUSTER_JOIN_TIMEOUT_MS` | `60000` | How long a node keeps trying to join members that refuse it (a revoked or foreign certificate) before its start fails |

## What's still yours to do
- **Keep core nodes on one private network.** Ignite's synchronous replication dislikes latency, so faraway machines should be links, a later phase.
- **TLS for clients** (the API on 8080, the ingress on 8000) is still terminated at your proxy or load balancer (SECURITY.md).
