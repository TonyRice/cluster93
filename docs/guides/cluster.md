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
c93 cluster init --nodes node1,node2,node3 --host node1=10.0.0.11 --host node2=10.0.0.12 --host node3=10.0.0.13
#   ./cluster-tls/ca.key       keep it safe and back it up
#   ./cluster-tls/ca.crt
#   ./cluster-tls/node1.join   one line per node
#   ./cluster-tls/cluster.json the inventory status and doctor read
```
Start each node with its line:
```bash
C93_NODE_ID=node1 C93_JOIN=$(cat cluster-tls/node1.join) java -jar c93.jar
```
- **Where the certificate goes:** the node installs it in `<C93_DATA_DIR>/tls/` (or `C93_TLS_DIR`). Its private key file is owner-only.
- **Later starts:** they don't need `C93_JOIN`.
- **Mismatches are refused:** a line for one node can't start another.

The inventory records each node's addresses, certificate, expiry and join line, plus the API URL. It holds
references only — never a private key. `c93 cluster init` records its path in the active profile, so the other
commands find it from anywhere:

```bash
c93 cluster status        # configured vs live, capacity, cert expiry, drift
c93 cluster status --check # exit non-zero on drift
c93 cluster doctor        # CA/cert files, expiry, membership
c93 cluster nodes         # just the live nodes
```

**With Docker Compose,** `docker compose up` does this for you: a one-shot `tls-init` service creates the CA and three node certificates in a volume on the first start. That's a development convenience: `ca.key` sits in that volume. For anything real, run `c93 cluster init` yourself and keep `ca.key` off the servers.

## Adding a node: one line
On the machine with `ca.key`:
```bash
c93 cluster invite node4 --host 10.0.0.14
# C93_JOIN=c93_nodebundle_H4sI…
```
Run that on the new machine: `C93_JOIN=c93_nodebundle_… java -jar c93.jar`, or `--join c93_nodebundle_…`.

On a node installed with [`c93 node install`](node.md), put the line in `node.env` and restart instead —
it never has to be on the command line (where it would land in your shell history):

```bash
c93 node env --set C93_JOIN=c93_nodebundle_…
c93 node restart
```

**What's in the line:** node4's private key, its certificate and the CA certificate, gzipped and base64-encoded. **It never contains `ca.key`.**
- **Treat it like a password for node4:** it can't create other nodes or sign anything.
- **It's bound to node4:** the certificate names node4 and its addresses.
- **If it leaks:** `c93 cluster revoke node4`.

### The strict way: nothing secret travels
```bash
cluster93-server --prepare node4      # on the new machine: makes its key, keeps it, prints the public key
c93 cluster invite node4 --host 10.0.0.14 --key <public key>   # on yours: a certificate-only line
```

## Removing a node
```bash
c93 cluster revoke node4   # with the admin token
```
The old `c93 node invite` and `c93 node revoke` spellings still work as hidden aliases for one release.

**What happens:**
- **Recorded:** the revocation is stored in the cluster and sent to every node, and marked in the inventory.
- **Refused:** from then on, every node's TLS refuses node4's certificate.
- **Forced out:** if node4 is in the cluster, it's dropped from discovery, whether it cooperates or not, and its process stops.

**To let it back in,** invite it again. The new certificate is accepted, because only certificates issued before the revocation are refused.

## Renewing
- **Lifetimes:** node certificates last 3 years and the CA 10.
- **Warnings:** a node warns in its log from 90 days before its certificate expires.
- **To renew:** run `c93 cluster invite <node>` again and restart the node with the new line.

## Settings
| Variable | Default | |
|---|---|---|
| `C93_CLUSTER_TLS` | `required` | `off` turns certificates off. **Development only**: nodes then talk without authentication or encryption, and each start logs a warning. |
| `C93_JOIN` | none | A join line to install (`c93_nodebundle_…` or `c93_nodecert_…`) |
| `C93_TLS_DIR` | `<C93_DATA_DIR>/tls` | Where the node keeps its key and certificates |
| `C93_CLUSTER_BIND` | the advertised host, if set | The private address Ignite listens on (D-065) |
| `C93_CLUSTER_JOIN_TIMEOUT_MS` | `60000` | How long a node keeps trying to join members that refuse it (a revoked or foreign certificate) before its start fails |

## What's still yours to do
- **Keep core nodes on one private network.** Ignite's synchronous replication dislikes latency, so faraway machines should be links, a later phase.
- **TLS for clients** (the API on 9393, the ingress on 9394) is still terminated at your proxy or load balancer (SECURITY.md).
