#!/usr/bin/env bash
#
# worker-isolation-check.sh — does a worker container actually join a node over the node's worker socket (D-166)?
#
# The things that decide it differ per engine and per kernel, and no unit test can catch a mismatch:
#   * D-149's identity mapping — a worker must present the node's host identity, or the 0700 socket dir / 0600 socket
#     refuses it;
#   * the placement's own network property, probed from INSIDE a container: under the `node` placement the container
#     has NO route off the host (S10), and under the `worker` placement — the default since D-173 — it has one,
#     because a worker that decides egress makes the tenant's connections itself (A7). Both are asserted, and which
#     one applies is pinned per case rather than assumed.
# So this runs the real thing: a node as a process (and, for the deployment shape, nodes as containers), a worker
# container from a node image, and one job over the socket.
#
# It is meant to be run repeatedly on a real machine, and it is careful to be safe to run:
#   1. everything it creates carries the label `c93.idcheck=<run id>`, and cleanup removes only labelled objects;
#   2. it never touches what it did not create — not ~/.cluster93, not ~/.config/cluster93, not /var/lib/cluster93,
#      not the demo project, not any pre-existing image, container or volume;
#   3. it uses its own ports (9493-9496) and its own temp directories under mktemp -d, all removed on exit;
#   4. `trap` on EXIT/INT/TERM stops the node and removes its containers, its image (only if it built one) and its
#      directories, so it is idempotent and safe to re-run;
#   5. nothing privileged: no --privileged, no --network host, no --cap-add, no host-root mounts;
#   6. every wait is bounded, so it cannot hang;
#   7. no pulls by default: it builds an image from the checkout's jar and worker binaries. `--pull` is opt-in;
#   8. it writes only under its own temp directories; the repository is read-only to it.
#
# Usage:
#   ./scripts/build-workers.sh                    # if bin/worker-* are missing (~minutes)
#   ./mvnw -q -pl server -am package -DskipTests  # if server/target/c93.jar is missing
#   ./scripts/worker-isolation-check.sh           # every case this host can run
#   ./scripts/worker-isolation-check.sh --dry-run # print the plan and the cases, change nothing
#   ./scripts/worker-isolation-check.sh --image X # use a node image you already have
#   ./scripts/worker-isolation-check.sh --case C3 # run one case
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="idcheck$$-$(date +%s)"
LABEL="c93.idcheck=$RUN_ID"
NODE_ID="idcheck$$"
TENANT="idcheck$$"
# 64 hex characters, as `c93 node install` generates: the server refuses one shorter than 32 (D-044).
ADMIN_TOKEN="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"

HTTP_PORT=9493
INGRESS_PORT=9494
METRICS_PORT=9495
INTERNAL_PORT=9496

# C6 starts a SECOND node on this box — a compute-tier node with its own socket, secret and ports — because that is
# what a cluster across hosts is made of (plan B1: a compute host runs a worker-role node of its own). The edge node
# keeps the ports above, since every helper in this script talks to it; only the compute node needs new ones.
COMPUTE_METRICS_PORT=9498
COMPUTE_INTERNAL_PORT=9499
# The two nodes' discovery base ports. Each takes a 10-port range and a communication port of its own (+100), so these
# are deliberately NOT the default 5701: another node on this host may already hold it, and Ignite does not fail when its
# port is taken — it takes the next free port in the range, while every peer is still told to join the base address,
# which is then that other node. Override with C93_IDCHECK_CLUSTER_PORTS=edge,compute.
CLUSTER_PORTS="${C93_IDCHECK_CLUSTER_PORTS:-5751,5771}"
CLUSTER_PORT_EDGE="${CLUSTER_PORTS%%,*}"
CLUSTER_PORT_COMPUTE="${CLUSTER_PORTS##*,}"
EDGE_NODE_ID="$NODE_ID-edge"
COMPUTE_NODE_ID="$NODE_ID-compute"
C6_NODE_PID=""

# Apache Ignite on JDK 21 needs these module opens: without them a CLUSTERED node fails at startup with
# InaccessibleObjectException ("java.nio.DirectByteBuffer.address field is unavailable"). The single-node cases above
# never start Ignite — a node is clustered only when C93_CLUSTERED is set — which is why nothing here needed it before.
# The set is scripts/local.sh's IGNITE_JVM_ARGS, the node-from-a-checkout list (the same opens pom.xml's ignite.jvm.args
# and the node image's JDK_JAVA_OPTIONS carry), and it is passed the way the image passes it, through JDK_JAVA_OPTIONS.
NODE_JVM_OPENS=(
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED
  --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED
  --add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED
  --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.math=ALL-UNNAMED
  --add-opens=java.sql/java.sql=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens=java.base/java.time=ALL-UNNAMED
  --add-opens=java.base/java.text=ALL-UNNAMED
  --add-opens=java.management/sun.management=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED
)

DRY_RUN=0
PULL=0
WANT_CASE=""
NAMED_IMAGE=""
IMAGE=""
# The worker command the containers run: whichever engine this checkout actually has in bin/ (a build may hold one),
# overridable to check a specific engine (C93_IDCHECK_WORKER=worker-graaljs).
WORKER_BIN="${C93_IDCHECK_WORKER:-worker-javet}"
if [ ! -x "$ROOT/bin/${WORKER_BIN#worker-}" ] && [ ! -x "$ROOT/bin/$WORKER_BIN" ]; then
  [ -x "$ROOT/bin/worker-graaljs" ] && WORKER_BIN="worker-graaljs"
fi
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --pull) PULL=1 ;;
    --image) NAMED_IMAGE="$2"; shift ;;
    --case) WANT_CASE="$2"; shift ;;
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

# Validate the case name here, not at the end: a name no case matches would run nothing and let the summary print
# "Every case this host can run passed" — which is the one thing a check must never say about work it did not do.
case "$WANT_CASE" in
  ""|C1|C3|C4|C5|C6|C7) ;;
  *) echo "Unknown case: ${WANT_CASE} (cases: C1, C3, C4, C5, C6, C7)" >&2; exit 2 ;;
esac

say() { printf '%s\n' "$*"; }
hr() { printf '%s\n' "------------------------------------------------------------------------"; }

# ---------------------------------------------------------------------------------------
# State the trap cleans up. Everything it holds was created by this run, under its own run id.
# ---------------------------------------------------------------------------------------
TMP=""
NODE_PID=""
ENGINE=""
declare -a CONTAINERS=()
# Images this run built are listed in a FILE, not an array: `image_for` is called in a command substitution (to
# capture the tag it prints), so it runs in a subshell, and an array it appends to there never reaches the trap —
# which is how this check came to leave a 1.4 GB image behind on every run.
declare -a NETWORKS=()

cleanup() {
  local status=$?
  if [ -n "$ENGINE" ]; then
    # Only containers this run labelled: `ps -aq -f label=…` cannot match another node's or the demo's.
    while IFS= read -r id; do
      [ -n "$id" ] && "$ENGINE" rm -f "$id" >/dev/null 2>&1 || true
    done < <("$ENGINE" ps -aq --filter "label=$LABEL" 2>/dev/null || true)
    # A network this run created, once its containers are gone.
    for net in ${NETWORKS[@]+"${NETWORKS[@]}"}; do
      "$ENGINE" network rm "$net" >/dev/null 2>&1 || true
    done
    if [ -n "$TMP" ] && [ -f "$TMP/prepared-images" ]; then
      while read -r built tag; do
        # Only the images this run built, each removed by the engine that built it.
        [ -n "${built:-}" ] && "$built" image rm -f "$tag" >/dev/null 2>&1 || true
      done < "$TMP/prepared-images"
    fi
  fi
  if [ -n "$ECHO_PID" ] && kill -0 "$ECHO_PID" 2>/dev/null; then
    kill "$ECHO_PID" 2>/dev/null || true
  fi
  if [ -n "$NODE_PID" ] && kill -0 "$NODE_PID" 2>/dev/null; then
    kill "$NODE_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$NODE_PID" 2>/dev/null || break; sleep 0.25; done
    kill -9 "$NODE_PID" 2>/dev/null || true
  fi
  # C6's second node, on the same footing (only ever a process this run started).
  if [ -n "$C6_NODE_PID" ] && kill -0 "$C6_NODE_PID" 2>/dev/null; then
    kill "$C6_NODE_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$C6_NODE_PID" 2>/dev/null || break; sleep 0.25; done
    kill -9 "$C6_NODE_PID" 2>/dev/null || true
  fi
  if [ -n "$TMP" ] && [ -d "$TMP" ]; then
    # The node holds the socket directory; it is gone by now. Temp dirs only — never a user or system path.
    case "$TMP" in /tmp/*|/var/folders/*|/private/var/folders/*) rm -rf "$TMP" ;; esac
  fi
  exit $status
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------------------
# What the cases are, and which this host can run
# ---------------------------------------------------------------------------------------

# A worker binary older than the worker sources cannot do what this version asks: an older one does not know
# --secret-file, so it presents no secret and the node refuses it — the right answer, for the wrong reason. Say so
# rather than report a deployment failure the deployment did not cause.
workers_are_current() {
  [ -x "$ROOT/bin/$WORKER_BIN" ] || return 1
  [ -z "$(find "$ROOT/worker/src" "$ROOT/runner-protocol/src/main" "$ROOT/runtime-js/src/main" \
      -type f -newer "$ROOT/bin/$WORKER_BIN" 2>/dev/null | head -1)" ]
}
rootless_docker() { docker info --format '{{.SecurityOptions}}' 2>/dev/null | grep -q 'name=rootless'; }
have_docker() { command -v docker >/dev/null 2>&1; }
have_podman() { command -v podman >/dev/null 2>&1; }
rootless_podman() { podman info --format '{{.Host.Security.Rootless}}' 2>/dev/null | grep -qi true; }

describe_cases() {
  say "Cases (each asserts: HELLO | a job round-trips | a job fetches the host through the node | the gate refuses"
  say "                      the cluster's own ports | a service answers through the ingress | the wrong secret is"
  say "                      refused | no network in the container):"
  say "  C1  rootless podman, --userns=keep-id        $(rootless_podman && echo AVAILABLE || echo 'SKIP (needs podman rootless: podman info)')"
  say "  C3  rootful docker, --user <uid>:<gid>       $( { have_docker && ! rootless_docker; } && echo AVAILABLE || echo 'SKIP (pose: rootful docker is not in use)')"
  say "  C4  wrong identity, refused                  rootful docker"
  say "  C5  the worker decides egress (D-173/A4)      rootful docker; gives the worker a route (A7)"
  say "      and the node counts what it refused, from the worker's own report (D-176). The same job runs under both"
  say "      placements: the node's own counters move when IT connects, and do not when the worker does. A refused"
  say "      connect the NODE makes reaches the script in the gate's own shape (D-179), not as a stream reset."
  say "      At the network's gateway — where the worker can actually reach the node — a script's fetch to the node's"
  say "      internal endpoint is refused as a cluster node, while a shell probe from the same container reaches it."
  say "  C6  a cluster across hosts is a node per host  rootful docker; two nodes on this box — an edge one"
  say "      (api,ingress, so no worker role and capacity 0 whatever C93_CAPACITY says) and a compute one (worker,"
  say "      capacity 2) — clustered, with the container attached to the COMPUTE node's socket and nothing attached to"
  say "      the edge node's (it has none). The job is submitted at the edge node's API and must be placed on the"
  say "      compute node and done by its container."
  say "  C7  the same cluster, containerized          rootful docker; both nodes as containers, the compute node's"
  say "      worker in a container beside it on a network the cluster is not on — the deployment the compose files"
  say "      install. The worker reaches the node only over the socket directory. A script's fetch to the compute"
  say "      node's own endpoint is refused by the gate (cluster node), AND the worker's network cannot reach it"
  say "      (probed from inside the worker container) — the gate's line and the deployment's, asserted separately."
}

# ---------------------------------------------------------------------------------------
# The node: a process, always (D-167). It is the only end that listens.
# ---------------------------------------------------------------------------------------
start_node() {  # start_node [worker|node] [true|false]: where the workers decide egress, and the dev switch
  local placement="${1:-node}"
  local allow_private="${2:-true}"
  local node_jar="$ROOT/server/target/c93.jar"
  [ -f "$node_jar" ] || { say "Missing $node_jar — build it: ./mvnw -q -pl server -am package -DskipTests"; exit 1; }
  install -d -m 0700 "$TMP/sockets"
  install -d -m 0700 "$TMP/secrets"
  install -d -m 0700 "$TMP/scratch"
  # Created once: the node is restarted with another placement by C5, and a 0400 file cannot be rewritten by the shell
  # that created it. Keeping them also keeps "the secret survives a restart" true, which is what a deployment does.
  if [ ! -f "$TMP/secrets/secret" ]; then
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/secrets/secret"
    chmod 0400 "$TMP/secrets/secret"
  fi
  # A secret that is NOT the node's, for the refusal check: same shape, wrong value.
  if [ ! -f "$TMP/secrets/wrong" ]; then
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/secrets/wrong"
    chmod 0400 "$TMP/secrets/wrong"
  fi
  # A parameter, not an env the caller sets: `VAR=x f` does not reliably reach the `java` f starts. The cases on the
  # `node` placement assert what the NODE does on a worker's behalf; C5 starts this same node again on the `worker`
  # placement, where the worker decides and connects — and with the development switch OFF, so what is under test is
  # the static set (A2, D-174) rather than the switch that opens it.
  # (Note for whoever edits this: no comments inside the `env \` list below. A `#` after a line continuation ends the
  # command, and the node starts without its socket and secret.)
  env \
    C93_NODE_ID="$NODE_ID" \
    C93_DATA_DIR="$TMP/data" \
    C93_WORKER_MODE=process \
    C93_WORKER_DEFAULT_MODE=javet \
    C93_CAPACITY=4 \
    C93_MAX_JOBS_PER_WORKER=4 \
    C93_MAX_HEAP_MB=64 \
    C93_HTTP_PORT="$HTTP_PORT" \
    C93_INGRESS_PORT="$INGRESS_PORT" \
    C93_METRICS_PORT="$METRICS_PORT" \
    C93_INTERNAL_PORT="$INTERNAL_PORT" \
    C93_HTTP_BIND=127.0.0.1 \
    C93_ADVERTISE_HOST=127.0.0.1 \
    C93_RATE_LIMIT_PER_MINUTE=0 \
    C93_INGRESS_DOMAIN=idcheck.test \
    C93_EGRESS_ALLOW_PRIVATE="$allow_private" \
    C93_EGRESS_PLACEMENT="$placement" \
    C93_ADMIN_TOKEN="$ADMIN_TOKEN" \
    C93_WORKER_SOCKET="$TMP/sockets/node.sock" \
    C93_WORKER_SECRET_FILE="$TMP/secrets/secret" \
    C93_WORKER_CONNECTIONS_MAX=4 \
    java -jar "$node_jar" > "$TMP/node.log" 2>&1 &
  NODE_PID=$!
  wait_for 60 "the node to answer /health" curl -sf -m 2 "http://127.0.0.1:$HTTP_PORT/health" >/dev/null
}

# An operator's way to a job token: a tenant, then a token for it (D-044, D-045) — the admin token manages tenants and
# tokens, and a job needs the tenant's own token.
mint_job_token() {  # mint_job_token <tenant>
  curl -s -m 10 -X POST "http://127.0.0.1:$HTTP_PORT/v1/tenants" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' -d "{\"name\":\"$1\"}" >/dev/null
  curl -s -m 10 -X POST "http://127.0.0.1:$HTTP_PORT/v1/tenants/$1/tokens" -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H 'Content-Type: application/json' -d '{"name":"idcheck","scopes":["*"]}' \
    | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

# Stops the node this check started, so another can take its ports (C5 restarts it with another placement).
stop_node() {
  if [ -n "$NODE_PID" ] && kill -0 "$NODE_PID" 2>/dev/null; then
    kill "$NODE_PID" 2>/dev/null || true
    for _ in $(seq 1 40); do kill -0 "$NODE_PID" 2>/dev/null || break; sleep 0.25; done
    kill -9 "$NODE_PID" 2>/dev/null || true
  fi
  NODE_PID=""
}

# A tiny HTTP server on the host, outside every container: the one thing a container worker cannot reach by itself,
# and therefore what proves the node is doing the fetching (python3 for a one-file server; without it that assertion
# is reported n/a rather than skipped silently).
ECHO_PORT=""
ECHO_PID=""
start_echo_server() {
  command -v python3 >/dev/null 2>&1 || return 0
  install -d "$TMP/echo"
  printf 'pong-from-the-node' > "$TMP/echo/ping.txt"
  ECHO_PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()' 2>/dev/null)
  [ -n "$ECHO_PORT" ] || return 0
  python3 -m http.server "$ECHO_PORT" --bind 127.0.0.1 --directory "$TMP/echo" >/dev/null 2>&1 &
  ECHO_PID=$!
  for _ in $(seq 1 40); do
    curl -sf -m 2 "http://127.0.0.1:$ECHO_PORT/ping.txt" >/dev/null 2>&1 && return 0
    sleep 0.25
  done
  ECHO_PORT=""
}

metric() { curl -sf -m 2 "http://127.0.0.1:$METRICS_PORT/metrics" 2>/dev/null | awk -v k="$1" '$1 ~ "^"k"\\{" { print $NF }' | head -1; }

# Every series of a metric, sorted: for a counter with tags, "it did not move" has to compare the whole set.
metric_all() { curl -sf -m 2 "http://127.0.0.1:$METRICS_PORT/metrics" 2>/dev/null | awk -v k="$1" '$1 ~ "^"k"\\{" { print }' | sort; }

# Waits for a metric to reach a value, **re-reading it each turn**. (Passing `test "$(metric …)" = …` to wait_for
# instead would expand the metric once, before the loop, and test that frozen value over and over.)
wait_for_metric() {  # wait_for_metric <metric> <expected value> <tries>
  local name="$1" expected="$2" tries="$3"
  for _ in $(seq 1 "$tries"); do
    [ "$(metric "$name")" = "$expected" ] && return 0
    sleep 0.5
  done
  return 1
}

wait_for() {
  local tries="$1" what="$2"; shift 2
  for _ in $(seq 1 "$tries"); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  say "  TIMED OUT waiting for $what"
  return 1
}

# Whether the node has been told what a worker refused (D-176) — and whether that is *new* since the series it had
# before. The worker batches its reports, so the caller waits; comparing against what was there before the job is what
# makes "told" mean "told about this". The node makes none of these connections, so a report is the only way it knows.
worker_reported_refusal() {  # worker_reported_refusal <the worker series as it stood before the job>
  local seen="$1" now
  now="$(metric_all c93_worker_egress_denied_total)"
  [ -n "$now" ] && [ "$now" != "$seen" ]
}

# ---------------------------------------------------------------------------------------
# The worker image: built from the checkout, or one you name. Labelled so cleanup can find it.
# ---------------------------------------------------------------------------------------
image_for() {  # image_for <engine>: the tag that engine has an image under, building it on first use
  local engine="$1"
  if [ -n "$NAMED_IMAGE" ]; then
    echo "$NAMED_IMAGE"
    return 0
  fi
  local context="$TMP/image-$engine" tag="cluster93-idcheck:$RUN_ID"
  [ "$engine" = podman ] && tag="cluster93-idcheck:$RUN_ID-podman"
  if [ ! -d "$context" ]; then
    install -d "$context"
    cp "$ROOT/server/target/c93.jar" "$context/c93.jar"
    cp "$ROOT/install/docker/Dockerfile.node" "$context/Dockerfile"
    local worker
    for worker in worker-javet worker-graaljs; do
      if [ -f "$ROOT/bin/$worker" ]; then cp "$ROOT/bin/$worker" "$context/$worker"
      elif [ -f "$ROOT/bin/$worker.gz" ]; then gunzip -c "$ROOT/bin/$worker.gz" > "$context/$worker"
      else say "  (no $worker in the checkout: the image will have one engine)" >&2
      fi
    done
    say "Building a node image with $engine (tag $tag)..." >&2
    "$engine" build -t "$tag" "$context" > "$TMP/build-$engine.log" 2>&1 \
      || { say "Image build with $engine failed: see $TMP/build-$engine.log" >&2; exit 1; }
    # Recorded for the trap; the file is read in the parent shell, where a subshell's array would not be (see above).
    printf '%s %s\n' "$engine" "$tag" >> "$TMP/prepared-images"
  fi
  echo "$tag"
}

# ---------------------------------------------------------------------------------------
# One case: start a worker container as the given identity, then the four assertions.
# ---------------------------------------------------------------------------------------
run_worker() {  # run_worker <name> <network> <identity args...>
  local name="$1"; shift
  local network="$1"; shift
  # A worker engine unpacks a native library and dlopens it: Javet under /tmp/javet/..., GraalJS under the user's home
  # ($HOME/.cache is not what decides it — a native image bakes java.io.tmpdir, and user.home comes from passwd). So the
  # deployment gives it a directory of its own AT those paths: a plain directory is executable by default (no `exec`
  # tmpfs relaxation), costs no container memory, and survives a restart. One per container, so two workers never write
  # the same file at once.
  local scratch="$SCRATCH/$RUN_ID-$name"
  install -d -m 0777 "$scratch/tmp" "$scratch/home"
  "$ENGINE" run -d \
    --name "$RUN_ID-$name" \
    --label "$LABEL" \
    --network "$network" \
    --cap-drop=ALL \
    --security-opt no-new-privileges \
    --read-only \
    --pids-limit 512 \
    "$@" \
    -v "$TMP/sockets:/run/cluster93/workers" \
    -v "$TMP/secrets/secret:/run/secrets/c93-worker-secret:ro" \
    -v "$scratch/tmp:/tmp" \
    -v "$scratch/home:/home" \
    --entrypoint "/opt/cluster93/bin/$WORKER_BIN" \
    "$IMAGE" ipc \
      --socket /run/cluster93/workers/node.sock \
      --secret-file /run/secrets/c93-worker-secret \
      --heap-mb 64 --threads 4 >/dev/null
}

submit_job() {  # submit_job [script]: the job's id
  local script="export default () => ({ ran: true })"
  [ $# -ge 1 ] && script="$1"
  local body
  body=$(jq_json 'script' "$script")
  curl -sf -m 5 -X POST "http://127.0.0.1:$HTTP_PORT/v1/jobs" \
    -H "Authorization: Bearer $JOB_TOKEN" -H "X-Cluster93-Tenant: $TENANT" \
    -H 'Content-Type: application/json' \
    -d "$body" \
    | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

# A one-field JSON body from a shell string, with the characters that matter escaped (no jq dependency).
jq_json() {  # jq_json <field> <value>
  printf '{"%s":"%s","timeoutMs":15000}' "$1" "$(printf '%s' "$2" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' ')"
}

job_json() {  # job_json <id>: the job's record
  curl -s -m 5 "http://127.0.0.1:$HTTP_PORT/v1/jobs/$1" \
    -H "Authorization: Bearer $JOB_TOKEN" -H "X-Cluster93-Tenant: $TENANT"
}

await_job() {  # await_job <job id>: SUCCEEDED or the status
  local id="$1" status=""
  for _ in $(seq 1 120); do
    status=$(curl -sf -m 5 "http://127.0.0.1:$HTTP_PORT/v1/jobs/$id" -H "Authorization: Bearer $JOB_TOKEN" \
        -H "X-Cluster93-Tenant: $TENANT" \
      | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
    case "$status" in SUCCEEDED|FAILED|CANCELLED|TIMED_OUT) echo "$status"; return 0 ;; esac
    sleep 0.5
  done
  echo "$status"
}

check_case() {  # check_case <id> <engine> <rootless> <identity flag> <identity args...>
  local case_id="$1" engine="$2" rootless="$3" flag="$4"; shift 4
  local hello=no job=no fetch=n/a gate=no inbound=no secret=no network=no result=FAIL
  # This case runs on its own engine: podman keeps a separate image store from docker, so both the image and every
  # command below have to come from the same one.
  ENGINE="$engine"
  IMAGE="$(image_for "$engine")"
  # Every case starts from whatever workers are still connected (the previous case's, if any), so its assertions are
  # about its own connection: the count has to grow by exactly one.
  local before_connected want_connected
  before_connected="$(metric c93_workers_connected)"
  want_connected="$(( ${before_connected%.*} + 1 )).0"

  run_worker "ok" none "$@" || { say "  $case_id: the worker container did not start"; return 1; }
  # A container worker's cold start is seconds, but a loaded host (a concurrent build, a cold image cache) is where the
  # margin matters: the worker retries for ~2 minutes by design, so wait for it rather than calling it a failure.
  if wait_for_metric c93_workers_connected "$want_connected" 360; then hello=yes; fi
  local id=""
  if [ "$hello" = yes ]; then
    id=$(submit_job)
    if [ -n "$id" ] && [ "$(await_job "$id")" = "SUCCEEDED" ] \
        && [ "$(metric c93_workers_running)" = "0.0" ]; then
      # The job finished **and** this node spawned no worker process: it ran on the connection (D-166).
      job=yes
    fi
  fi
  # The gate is in the path even from a container: the node refuses its own cluster ports outright ("scripts can
  # never reach the cluster"), and the container has no network of its own to bypass it with.
  local gate=no
  if [ "$job" = yes ]; then
    local gate_id
    gate_id=$(submit_job "export default async () => { try { await fetch('http://127.0.0.1:$INTERNAL_PORT/health'); return { reached: true }; } catch (e) { return { reached: false, why: e.cause ? [e.cause.code, e.cause.message].join(' ') : String(e.message) }; } }")
    if [ -n "$gate_id" ] && [ "$(await_job "$gate_id")" = "SUCCEEDED" ]; then
      local gate_json; gate_json=$(job_json "$gate_id")
      case "$gate_json" in
        *'"reached":false'*cluster*) gate=yes ;;
      esac
    fi
  fi

  # Inbound, from a container: a service served through the node's ingress, with its requests proxied **over the
  # socket** — and the node spawned nothing, so the container's worker answered them.
  # Egress, from a container: the worker has no network at all, so a job that fetches the host's endpoint proves the
  # *node* fetched it and streamed the body back over the socket (n/a without python3, which serves that endpoint).
  local fetch=n/a
  if [ -n "$ECHO_PORT" ] && [ "$job" = yes ]; then
    fetch=no
    local fetch_id
    fetch_id=$(submit_job "export default async () => { const r = await fetch('http://127.0.0.1:$ECHO_PORT/ping.txt'); return { body: await r.text() }; }")
    if [ -n "$fetch_id" ] && [ "$(await_job "$fetch_id")" = "SUCCEEDED" ]; then
      case "$(job_json "$fetch_id")" in *pong-from-the-node*) fetch=yes ;; esac
    fi
  fi

  local inbound=no
  if [ "$hello" = yes ]; then
    if curl -sf -m 5 -X PUT "http://127.0.0.1:$HTTP_PORT/v1/services/web" \
        -H "Authorization: Bearer $JOB_TOKEN" -H "X-Cluster93-Tenant: $TENANT" \
        -H 'Content-Type: application/json' \
        -d "$(jq_json 'script' "export default async (input, ctx) => {
              await ctx.serve((req) => req.path === '/ping' ? 'pong-from-the-container' : { status: 404 });
              return ctx.stopRequested;
            }")" >/dev/null; then
      for _ in $(seq 1 60); do
        curl -sf -m 5 "http://127.0.0.1:$HTTP_PORT/v1/services/web" -H "Authorization: Bearer $JOB_TOKEN" \
          -H "X-Cluster93-Tenant: $TENANT" | grep -q '"status":"RUNNING"' && break
        sleep 0.5
      done
      sleep 3   # the handler is registered after the run reports RUNNING
      local served
      served=$(curl -s -m 5 -H "Host: web--$TENANT.idcheck.test" "http://127.0.0.1:$INGRESS_PORT/ping")
      [ "$served" = "pong-from-the-container" ] && [ "$(metric c93_workers_running)" = "0.0" ] && inbound=yes
    fi
  fi

  # The wrong secret must be refused: the connection count must not grow past the one good worker.
  local bad_scratch="$SCRATCH/$RUN_ID-bad"
  install -d -m 0777 "$bad_scratch/tmp" "$bad_scratch/home"
  "$ENGINE" run -d --name "$RUN_ID-bad" --label "$LABEL" --network none --cap-drop=ALL \
    --security-opt no-new-privileges --read-only --pids-limit 512 "$@" \
    -v "$TMP/sockets:/run/cluster93/workers" \
    -v "$TMP/secrets/wrong:/run/secrets/c93-worker-secret:ro" \
    -v "$bad_scratch/tmp:/tmp" -v "$bad_scratch/home:/home" \
    --entrypoint "/opt/cluster93/bin/$WORKER_BIN" \
    "$IMAGE" ipc --socket /run/cluster93/workers/node.sock \
      --secret-file /run/secrets/c93-worker-secret --heap-mb 64 --threads 4 >/dev/null 2>&1 || true
  # The refusal lands when that worker's own cold start finishes (the engine + V8 load take seconds), so wait for it
  # rather than guessing a sleep — and require it, not merely that the count did not grow.
  local refusals=0
  for _ in $(seq 1 120); do
    refusals=$(grep -ac "wrong secret" "$TMP/node.log")
    [ "$refusals" -ge 1 ] && break
    sleep 0.5
  done
  if [ "$(metric c93_workers_connected)" = "$want_connected" ] && [ "$refusals" -ge 1 ]; then secret=yes; fi
  # No network reachability, probed from inside a container on the same footing (S10).
  if "$ENGINE" run --rm --network none --cap-drop=ALL --security-opt no-new-privileges \
      --entrypoint /bin/bash "$IMAGE" -c 'exec 3<>/dev/tcp/1.1.1.1/80' >/dev/null 2>&1; then
    network=no
  else
    network=yes
  fi
  if [ "$hello" != yes ] || [ "$job" != yes ] || [ "$fetch" = no ] || [ "$gate" != yes ] \
      || [ "$inbound" != yes ] || [ "$secret" != yes ] || [ "$network" != yes ]; then
    say "  the engine's scratch space ($SCRATCH/$RUN_ID-ok):"
    find "$SCRATCH/$RUN_ID-ok" -name '*.so' 2>/dev/null | sed 's/^/    /' | head -3
    du -sh "$SCRATCH/$RUN_ID-ok" 2>/dev/null | sed 's/^/    size: /'
    say "  --- why $case_id did not pass ---"
    say "  the worker container's own output (last 10 lines):"
    "$ENGINE" logs "$RUN_ID-ok" 2>&1 | tail -10 | sed 's/^/    /'
    say "  what the node saw:"
    grep -aE "Worker container connected|Refusing worker connection|Listening for worker|did not say hello|Job .* ended" "$TMP/node.log" | tail -5 | sed 's/^/    /'
    say "  a probe with the same mounts and identity:"
    "$ENGINE" run --rm --read-only --network none --pids-limit 512 "$@" \
      -v "$TMP/sockets:/run/cluster93/workers" \
      -v "$TMP/secrets/secret:/run/secrets/c93-worker-secret:ro" \
      --entrypoint /bin/bash "$IMAGE" -c 'id; ls -la /run/secrets/ /run/cluster93/workers/ 2>&1 | head -8; (cat /run/secrets/c93-worker-secret >/dev/null && echo SECRET-READ-OK) || echo SECRET-READ-FAIL' 2>&1 | tail -10 | sed 's/^/    /'
    if [ "$secret" != yes ]; then
      say "  the wrong-secret worker: status=$("$ENGINE" inspect -f '{{.State.Status}}' "$RUN_ID-bad" 2>/dev/null || echo 'never started'), connected=$(metric c93_workers_connected), refusals-in-node-log=$(grep -ac 'wrong secret' "$TMP/node.log")"
      "$ENGINE" logs "$RUN_ID-bad" 2>&1 | tail -6 | sed 's/^/    /'
    fi
    if [ -n "$id" ]; then
      say "  the job:"
      curl -s -m 5 "http://127.0.0.1:$HTTP_PORT/v1/jobs/$id" -H "Authorization: Bearer $JOB_TOKEN" \
        -H "X-Cluster93-Tenant: $TENANT" | head -c 600 | sed 's/^/    /'
      say ""
    fi
  fi
  [ "$hello" = yes ] && [ "$job" = yes ] && [ "$fetch" != no ] && [ "$gate" = yes ] && [ "$inbound" = yes ] \
    && [ "$secret" = yes ] && [ "$network" = yes ] && result=PASS
  printf '%s | %s | %s | %-22s | %-5s | %-3s | %-5s | %-5s | %-7s | %-6s | %-7s | %s\n' \
    "$case_id" "$engine" "$rootless" "$flag" "$hello" "$job" "$fetch" "$gate" "$inbound" "$secret" "$network" "$result"
  # Stop this case's service before its worker goes: otherwise the supervisor restarts it onto a *spawned* worker
  # and the next case's "the node spawned nothing" assertions are measuring that instead.
  curl -s -m 5 -X DELETE "http://127.0.0.1:$HTTP_PORT/v1/services/web" -H "Authorization: Bearer $JOB_TOKEN" \
    -H "X-Cluster93-Tenant: $TENANT" >/dev/null 2>&1 || true
  sleep 1
  "$ENGINE" rm -f "$RUN_ID-ok" "$RUN_ID-bad" >/dev/null 2>&1 || true
  [ "$result" = PASS ]
}

# C4: a worker container as an identity that is not the node's. It may not reach the 0700 socket directory, so the
# node never sees a HELLO -- which is the point of the two layers (D-149, S3), and the failure the wrong identity has
# to produce. Runs as any uid that is not the node's; no host user is created.
check_identity_refused() {  # check_identity_refused <engine> <foreign uid:gid>
  local engine="$1" foreign="$2"
  local before after hello=no
  before="$(metric c93_workers_connected)"
  local foreign_scratch="$SCRATCH/$RUN_ID-foreign"
  install -d -m 0777 "$foreign_scratch/tmp" "$foreign_scratch/home"
  "$ENGINE" run -d --name "$RUN_ID-foreign" --label "$LABEL" --network none --cap-drop=ALL \
    --security-opt no-new-privileges --read-only --pids-limit 512 --user "$foreign" \
    -v "$TMP/sockets:/run/cluster93/workers" \
    -v "$TMP/secrets/secret:/run/secrets/c93-worker-secret:ro" \
    -v "$foreign_scratch/tmp:/tmp" -v "$foreign_scratch/home:/home" \
    --entrypoint "/opt/cluster93/bin/$WORKER_BIN" \
    "$IMAGE" ipc --socket /run/cluster93/workers/node.sock \
      --secret-file /run/secrets/c93-worker-secret --heap-mb 64 --threads 4 >/dev/null 2>&1 || true
  sleep 12   # long enough for the retry loop to try several times and be refused each time
  after="$(metric c93_workers_connected)"
  if [ "$before" = "$after" ]; then hello=no; refused=yes; fi
  "$ENGINE" rm -f "$RUN_ID-foreign" >/dev/null 2>&1 || true
  printf '%-3s | %-6s | %-8s | %-22s | %-5s | %-3s | %-6s | %-7s | %s\n' \
    "C4" "$engine" "rootful" "--user $foreign" "no" "-" "-" "-" "$([ "${refused:-no}" = yes ] && echo PASS || echo FAIL)"
  [ "${refused:-no}" = yes ]
}

# ---------------------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------------------
if [ "$DRY_RUN" = 1 ]; then
  say "worker-isolation-check (dry run) — nothing will be created"
  hr
  say "run id / label : $RUN_ID / $LABEL"
  say "node           : java -jar server/target/c93.jar  (a process, D-167)"
  say "  ports        : API $HTTP_PORT, ingress $INGRESS_PORT, metrics $METRICS_PORT, internal $INTERNAL_PORT"
  say "  sockets      : \$TMP/sockets/node.sock  (dir 0700, socket 0600)   secret: \$TMP/secrets/secret (0400)"
  say "  C6's pair    : edge $EDGE_NODE_ID (api,ingress, cap 0) on those ports; compute $COMPUTE_NODE_ID (worker,"
  say "                 cap 2) API off, metrics $COMPUTE_METRICS_PORT, internal $COMPUTE_INTERNAL_PORT, its own socket"
  say "                 \$TMP/c6-compute/sockets/node.sock and secret; cluster ports $CLUSTER_PORT_EDGE/$CLUSTER_PORT_COMPUTE"
  say "                 with C93_CLUSTER_TLS=off (this case asserts topology and placement, not transport crypto)"
  say "worker command : $WORKER_BIN ipc --socket …  (whichever engine bin/ holds)"
  say "worker image   : ${IMAGE:-built from the checkout, tagged cluster93-idcheck:$RUN_ID}"
  say "cleanup on exit: only objects labelled $LABEL, plus the image it built and its own temp dir"
  hr
  describe_cases
  exit 0
fi

# ---------------------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------------------
if ! have_docker && ! have_podman; then
  say "No container engine on PATH (docker or podman); nothing to check."
  exit 0
fi
ENGINE=docker
have_docker || ENGINE=podman

TMP="$(mktemp -d "${TMPDIR:-/tmp}/c93-idcheck-XXXXXX")"
SCRATCH="$TMP/scratch"
say "worker-isolation-check $RUN_ID (engine: $ENGINE)"
hr
# A worker image with current binaries is only needed by the cases that reach the node and present a secret; the
# wrong-identity case (C4) is refused before any of that, so it still runs.
WORKERS_OK=1
if [ -z "$IMAGE" ] && ! workers_are_current; then
  WORKERS_OK=0
  say "NOTE: the checkout's bin/$WORKER_BIN is missing or older than the worker sources, so it cannot present the"
  say "      secret this node expects (--secret-file arrived with D-168): the cases that join the pool would fail"
  say "      for a reason that is not the deployment's, so they are skipped. Rebuild, then run this again:"
  say "        ./scripts/build-workers.sh graaljs   (or javet)     # ~minutes"
  say "      Or name an image whose workers are current:  $0 --image <ref>"
  say ""
fi
start_node
start_echo_server
JOB_TOKEN="$(mint_job_token "$TENANT")"
if [ -z "$JOB_TOKEN" ]; then
  say "Could not mint a tenant token through the API (see $TMP/node.log); there is nothing to submit a job with."
  exit 1
fi


printf '%-3s | %-6s | %-8s | %-22s | %-5s | %-3s | %-5s | %-5s | %-7s | %-6s | %-7s | %s\n' \
  case engine rootless identity hello job fetch gate inbound secret network result
FAILED=0
SKIPPED=0
run_case_if() {  # run_case_if <available> <case id> <engine> <rootless> <flag> [args...]
  local available="$1"; shift
  local case_id="$1"
  if [ "$WANT_CASE" != "" ] && [ "$WANT_CASE" != "$case_id" ]; then return 0; fi
  if [ "$WORKERS_OK" != 1 ]; then
    printf '%-3s | %-6s | %s\n' "$case_id" "$2" "SKIP (rebuild the workers — see the note above)"
    SKIPPED=$((SKIPPED + 1))
    return 0
  fi
  if [ "$available" != 1 ]; then
    printf '%-3s | %-6s | %s\n' "$case_id" "$2" "SKIP (this host cannot run it — see the case list)"
    SKIPPED=$((SKIPPED + 1))
    return 0
  fi
  check_case "$@" || FAILED=1
}

# ---------------------------------------------------------------------------------------
# A7: the worker placement's target, and what makes it evidence.
#
# The node can reach this target too, so reachability cannot say who made a connection. The node's OWN counters can:
# c93.egress.sockets/bytes are counted by the socket path wherever the socket is opened (D-116, D-173), so the same
# job is run under both placements — under `node` the node's counters move, under `worker` they do not — and the first
# run is what gives the second one its meaning.
# ---------------------------------------------------------------------------------------
TARGET_IMAGE=""
TARGET_IP=""
TARGET_PORT=18080

# The sibling's image: the node image's own base plus python3, built once and kept under a stable tag, so a run does
# not rebuild it and an operator can remove it by that name whenever. The node image has no shell server of its own,
# and python3 is the dependency this check already leans on for a local server (start_echo_server).
target_image() {  # target_image <engine>: the tag on stdout, or nothing if it cannot be built
  local engine="$1" tag="cluster93-idcheck-target:py3"
  if "$engine" image inspect "$tag" >/dev/null 2>&1; then
    echo "$tag"
    return 0
  fi
  local context="$TMP/target-image"
  install -d "$context"
  cat > "$context/Dockerfile" <<'DOCKERFILE'
FROM docker.io/library/eclipse-temurin:21-jre
LABEL c93.idcheck.target="a sibling HTTP target for worker-isolation-check.sh; safe to remove any time"
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 \
 && rm -rf /var/lib/apt/lists/*
DOCKERFILE
  say "Building the check's target image with $engine (tag $tag)..." >&2
  "$engine" build -t "$tag" "$context" > "$TMP/build-$engine-target.log" 2>&1 || return 1
  echo "$tag"
}

# A sibling container serving one file, on a network of this run's own: a target that is an allowed address and not
# the node, and that the worker cannot reach until its deployment gives it a route (A7).
start_target() {  # start_target <engine> <net>: sets TARGET_IP, or returns 1
  local engine="$1" net="$2"
  install -d "$TMP/target"
  printf 'pong-from-a-sibling' > "$TMP/target/ping.txt"
  "$engine" run -d --name "$RUN_ID-target" --label "$LABEL" --network "$net" \
    --read-only --cap-drop=ALL --security-opt no-new-privileges --pids-limit 64 \
    -v "$TMP/target:/srv:ro" \
    --entrypoint python3 "$TARGET_IMAGE" -m http.server "$TARGET_PORT" --bind 0.0.0.0 --directory /srv \
    >/dev/null 2>&1 || return 1
  TARGET_IP="$("$engine" inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$RUN_ID-target" 2>/dev/null)"
  [ -n "$TARGET_IP" ] || return 1
  for _ in $(seq 1 40); do
    "$engine" run --rm --network "$net" --entrypoint curl "$IMAGE" -sf -m 2 "http://$TARGET_IP:$TARGET_PORT/ping.txt" \
      >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  return 1
}

# The tenant's rule for the target, set before a worker is bound: a policy is built and sent at bind (D-173/A3). Loud
# on failure: a rule that silently did not store would look exactly like a worker that ignored it.
tenant_allow() {  # tenant_allow <address> <port>
  local body code
  body="$(curl -s -m 10 -X PUT "http://127.0.0.1:$HTTP_PORT/v1/tenants/$TENANT/egress-policy" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -w '\n%{http_code}' \
    -d "{\"allow\":[{\"to\":\"$1/32\",\"ports\":[$2]}]}")"
  code="${body##*$'\n'}"
  if [ "$code" != "200" ]; then
    say "  the tenant's egress rule was not stored (HTTP $code): $(printf '%s' "$body" | head -c 300)"
    return 1
  fi
}

# A job that opens a real TCP socket (D-116) and reads the target's marker: a socket, not `fetch`, because the node
# counts its own sockets and bytes and `fetch` is not counted there.
socket_job() {  # socket_job <address> <port>: the job's id
  submit_job "import net from 'node:net';
export default () => new Promise((resolve) => {
  const socket = net.connect($2, '$1');
  let text = '';
  socket.setEncoding('utf8');
  socket.on('connect', () => socket.write('GET /ping.txt HTTP/1.0\r\nHost: target\r\n\r\n'));
  socket.on('data', (chunk) => { text += chunk; });
  socket.on('end', () => { socket.destroy(); resolve({ body: text }); });
  socket.on('error', (e) => resolve({ reached: false, code: e.code, why: e.message }));
});"
}

# A job that tries an address the static set must refuse, before any connection is made.
refused_job() {  # refused_job <address> <port>: the job's id
  submit_job "import net from 'node:net';
export default () => new Promise((resolve) => {
  const socket = net.connect($2, '$1');
  socket.on('connect', () => { socket.destroy(); resolve({ reached: true }); });
  socket.on('error', (e) => resolve({ reached: false, code: e.code, why: e.message }));
});"
}

# Whether a job finished and its own record contains what was expected.
job_ran() {  # job_ran <id> <substring of the record>
  local id="$1" want="$2"
  [ -n "$id" ] || return 1
  [ "$(await_job "$id")" = "SUCCEEDED" ] || return 1
  case "$(job_json "$id")" in *"$want"*) return 0 ;; esac
  return 1
}

# C5: the worker's own placement (D-173/A4), with a route (A7), in a real container.
#
# Five things are asserted, and the first is what makes the second worth anything:
#   1. under the NODE's placement the node makes the connection, and its own socket counter moves (the baseline) —
#      and a connect the node REFUSES reaches the script in the gate's own shape, code and words (D-179), not as a
#      stream reset whose wrapper hides them;
#   2. under the WORKER's placement the same job succeeds while those counters stand still — the worker's own route;
#   3. the static set the worker holds still refuses metadata, RFC1918 and the cluster's own port — and a script's
#      fetch to the node's internal endpoint is refused as a cluster node at the address the worker can actually
#      reach (the network's gateway), while a shell probe from the same container shows that address:port IS reachable.
#      The gate is what refuses the script; the deployment's network is what refuses everything that is not a script;
#   4. the node counts what the worker refused, because the worker reported it (D-176).
#
# The evidence is behavioural on purpose: a worker container's log is a no-op — the native worker image has no SLF4J
# provider, so its logger discards every line (D-175) — and a log line would be weaker anyway, since it would say a
# worker was handed rules, not that it applied them.
check_worker_placement() {  # check_worker_placement <engine> <label>
  local engine="$1" label="$2"
  ENGINE="$engine"
  IMAGE="$(image_for "$engine")"

  local joined=no nodeRan=n/a nodeShape=n/a reached=n/a refused=no blocks=no localOnly=no reported=no
  local jobOut="" blocksOut="" refusedOut="" nodeShapeOut=""
  local gw="" gw_ports="" gw_open=n/a

  # --- The target and the tenant's rule for it: private, so the node's gate would refuse it and only the rule lets
  #     either end through, and a sibling rather than the node, so it is not one of the node's own addresses. ---
  TARGET_IMAGE="$(target_image "$engine" || true)"
  TARGET_IP=""
  local net="c93-idcheck-$RUN_ID" worker_net=none
  if [ -n "$TARGET_IMAGE" ] && "$engine" network create "$net" >/dev/null 2>&1; then
    NETWORKS+=("$net")
    worker_net="$net"
    # The address the worker reaches its host at: on Docker this is an interface of the node (so the synced policy can
    # name it), and it is where the node's internal endpoint really answers — the probe at the end of this case shows it.
    gw="$("$engine" network inspect "$net" -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null)"
    if start_target "$engine" "$net"; then
      # Present for both phases, though only the worker placement needs it: the node that runs the baseline still has
      # the development switch on (the node-placement cases need it to fetch the echo server on loopback), so there
      # the rule is redundant. Under the worker placement the switch is off and the rule is the only thing that lets
      # this address through.
      tenant_allow "$TARGET_IP" "$TARGET_PORT" || true
    else
      say "  the sibling target container did not come up: the positive column is reported as n/a"
    fi
  else
    say "  no sibling target (the check's target image or its own network could not be made): the positive column"
    say "  is reported as n/a. It wants one build with network access (apt, once) and a network of the run's own."
  fi

  # --- Under the NODE's placement, on the node start_node already brought up: the node is in the path here, so ITS
  #     counters move. The container has no network (S10), exactly as the other cases assert. ---
  if [ -n "$TARGET_IP" ]; then
    local base_before_connected base_want
    base_before_connected="$(metric c93_workers_connected)"
    base_want="$(( ${base_before_connected%.*} + 1 )).0"
    run_worker base none --user "$(id -u):$(id -g)" || say "  the baseline worker container did not start"
    local base_joined=no
    for _ in $(seq 1 120); do
      [ "$(metric c93_workers_connected)" = "$base_want" ] && base_joined=yes && break
      sleep 0.5
    done
    if [ "$base_joined" = yes ]; then
      local before after base_id
      before="$(metric_all c93_egress_sockets_total)"
      base_id="$(socket_job "$TARGET_IP" "$TARGET_PORT")"
      if job_ran "$base_id" "pong-from-a-sibling"; then
        after="$(metric_all c93_egress_sockets_total)"
        [ "$before" != "$after" ] && nodeRan=yes
      else
        say "  the baseline job did not reach the target through the node: $(job_json "${base_id:-x}")"
      fi

      # …and the other half of the node's path: a connect the NODE refuses, where the failure — not a body — crosses
      # the socket. It must reach the script in the gate's own shape (D-179): the code and the words a worker-side
      # gate gives, with no "Stream reset" wrapper standing between the two. One of the node's own ports, which every
      # placement refuses before any rule can be consulted (D-108), so this needs no route in the container.
      local shape_id shape_json shape_ok=yes
      shape_id="$(refused_job 127.0.0.1 "$INTERNAL_PORT")"
      [ -n "$shape_id" ] && await_job "$shape_id" >/dev/null
      shape_json="$(job_json "${shape_id:-x}")"
      case "$shape_json" in
        *'"reached":false'*'"code":"egress_denied"'*'resolves to one of the cluster'*) ;;
        *) shape_ok=no ;;
      esac
      # The wrapper is the whole point of D-179: it is what used to stand between the gate's words and the script.
      case "$shape_json" in *'Stream reset'*) shape_ok=no ;; esac
      nodeShape=no
      if [ "$shape_ok" = yes ]; then
        nodeShape=yes
      else
        nodeShapeOut="$shape_json"
      fi
    fi
    "$engine" rm -f "$RUN_ID-base" >/dev/null 2>&1 || true
    if [ "$nodeRan" != yes ]; then
      say "  the baseline (node placement) did not show the node's own socket counter moving, so what the next"
      say "  phase measures would mean nothing: the positive column stays n/a rather than claim the route."
    fi
  fi

  # --- Under the WORKER's placement: a node that lets the worker decide, with the development switch OFF, so what the
  #     refusals below come from is the static set (A2, D-174) and not the switch that opens private addresses. ---
  stop_node
  start_node worker false || { say "  could not restart the node for the worker placement"; return 1; }
  # A restart is a new node process: mint as an operator would rather than rely on a token from the first one.
  JOB_TOKEN="$(mint_job_token "$TENANT")"
  [ -n "$JOB_TOKEN" ] || { say "  no job token after the restart"; return 1; }
  # …and re-apply the tenant's rule, because this check's node keeps its records in MEMORY: the restart empties them,
  # the tenant list comes back empty, and minting above re-creates the tenant without its rule. (Measured, not
  # assumed: the old comment here claimed the tenant survived the restart, and nothing had ever tested it.) A
  # deployment that wants rules to outlive a node configures persistence; the check assumes neither, so it sets the
  # rule on whichever node process is about to be asked. A worker is handed its rules at bind, which is still ahead.
  if [ -n "$TARGET_IP" ]; then
    tenant_allow "$TARGET_IP" "$TARGET_PORT" || true
  fi

  run_worker w "$worker_net" --user "$(id -u):$(id -g)"
  for _ in $(seq 1 60); do
    [ "$(metric c93_workers_connected)" = "1.0" ] && joined=yes && break
    sleep 0.5
  done
  # A worker is given its rules when it is *bound*, not when it connects, so give it something to do first (D-166
  # binds a connection to the tenant that has work).
  if [ "$joined" = yes ]; then
    local warmup
    warmup=$(submit_job "export default () => ({ warmed: true })")
    [ -n "$warmup" ] && await_job "$warmup" >/dev/null
  fi

  if [ "$joined" = yes ]; then
    # 1. A route, and the worker's own connection: the same job as the baseline, succeeding while the node's socket
    #    counters — which moved when the node made it — stay exactly where they are.
    if [ -n "$TARGET_IP" ] && [ "$nodeRan" = yes ]; then
      local before after id
      before="$(metric_all c93_egress_sockets_total)"
      id="$(socket_job "$TARGET_IP" "$TARGET_PORT")"
      if job_ran "$id" "pong-from-a-sibling"; then
        after="$(metric_all c93_egress_sockets_total)"
        if [ "$before" = "$after" ]; then reached=yes; else reached=no; fi
      else
        reached=no
        jobOut="$(job_json "${id:-x}")"
      fi
    fi

    # 2. The floor is the worker's own: metadata (link-local) and an RFC1918 address are refused before any
    #    connection, on a container that has just been shown to have a route.
    local meta private
    meta="$(refused_job 169.254.169.254 80)"
    private="$(refused_job 10.0.0.1 80)"
    if job_ran "$meta" '"code":"egress_denied"' && job_ran "$private" '"code":"egress_denied"'; then
      blocks=yes
    else
      blocksOut="metadata: $(job_json "${meta:-x}")  private: $(job_json "${private:-x}")"
    fi

    # 3. A cluster address on one of the node's own ports, at the address the worker can ACTUALLY reach it on: the
    #    network's gateway. `fetch` must fail as a cluster node (the address is in the policy the node synced, because
    #    it is an interface of the node), not merely as a private address — and the same script probes 127.0.0.1,
    #    where the node's ports are refused as "here". The node makes none of these connections, so its own counter
    #    must not move either.
    local before_d after_d workerBefore did gw_ok=n/a
    [ -n "$gw" ] || say "  the worker network's gateway is unknown, so only the loopback refusal is asserted"
    before_d="$(metric_all c93_egress_denied_total)"
    workerBefore="$(metric_all c93_worker_egress_denied_total)"
    did=$(submit_job "export default async () => {
      const probe = async (url) => {
        try { await fetch(url); return { reached: true }; }
        catch (e) { return { reached: false, code: e.cause ? e.cause.code : e.code, why: e.cause ? e.cause.message : null }; }
      };
      const cluster = (r) => !!r && r.reached === false && r.code === 'egress_denied'
        && (r.why || '').includes('resolves to one of the cluster');
      const gatewayUrl = '$([ -n "$gw" ] && printf 'http://%s:%s/health' "$gw" "$INTERNAL_PORT")';
      const gateway = gatewayUrl ? await probe(gatewayUrl) : null;
      const loopback = await probe('http://127.0.0.1:$INTERNAL_PORT/health');
      return { gatewayOk: gateway ? cluster(gateway) : null, loopbackOk: cluster(loopback), gateway, loopback };
    }")
    [ -n "$did" ] || say "  the job was not accepted (no id came back)"
    if [ -n "$did" ]; then
      await_job "$did" >/dev/null
      refusedOut="$(job_json "$did")"
      case "$refusedOut" in
        *'"loopbackOk":true'*) refused=yes ;;
      esac
      if [ -n "$gw" ]; then
        gw_ok=no
        case "$refusedOut" in
          *'"gatewayOk":true'*) gw_ok=yes ;;
        esac
      fi
      after_d="$(metric_all c93_egress_denied_total)"
      [ "$before_d" = "$after_d" ] && localOnly=yes
      # The node is not in the path of these connections, so its own metrics can show this refusal only because the
      # worker said so (D-176) — and the worker batches that, so wait for it rather than assume it lands at once.
      wait_for 20 "the node to be told what the worker refused" worker_reported_refusal "$workerBefore" && reported=yes
    fi
  fi

  if [ "$reached" = no ] && [ -n "$jobOut" ]; then
    say "  the socket job's own record (why the positive column did not pass):"
    printf '%s' "$jobOut" | head -c 1200 | sed 's/^/    /'
    say ""
    say "  what the server holds for $TENANT — empty here means the rule never stored; present here means the node"
    say "  sent the worker a policy without it:"
    curl -s -m 5 "http://127.0.0.1:$HTTP_PORT/v1/tenants/$TENANT/egress-policy" -H "Authorization: Bearer $ADMIN_TOKEN" \
      | head -c 600 | sed 's/^/    /'
    say ""
    say "  what the node said about egress policies:"
    grep -ai egress "$TMP/node.log" | tail -5 | sed 's/^/    /'
    say ""
  fi
  if [ "$blocks" != yes ] && [ -n "$blocksOut" ]; then
    say "  the refusals the static set should have made:"
    printf '%s' "$blocksOut" | head -c 1200 | sed 's/^/    /'
    say ""
  fi
  if [ "$nodeShape" != yes ] && [ -n "$nodeShapeOut" ]; then
    say "  a refused connect the NODE made did not reach the script in the gate's own shape (D-179) — it wants"
    say "  \"code\":\"egress_denied\" with the gate's words, and no \"Stream reset\" wrapper:"
    printf '%s' "$nodeShapeOut" | head -c 1200 | sed 's/^/    /'
    say ""
  fi
  if [ "$refused" != yes ]; then
    say "  the job's own record (what the worker's decision produced):"
    printf '%s' "$refusedOut" | head -c 1200 | sed 's/^/    /'
    say ""
    say "  the worker container's own log (a no-op logger, so this is usually empty — D-175 records why):"
    "$engine" logs "$RUN_ID-w" 2>&1 | tail -6 | sed 's/^/    /'
  fi
  if [ "$gw_ok" = no ]; then
    say "  a script's fetch to the node's endpoint at the gateway ($gw:$INTERNAL_PORT) was refused, but not as the"
    say "  node's own address (it wants \"resolves to one of the cluster\"):"
    printf '%s' "$refusedOut" | head -c 1200 | sed 's/^/    /'
    say ""
  fi

  # What the deployment owes a worker placement (A7): the node's own ports must not be reachable from the worker's
  # network. The check's node binds the API to loopback, and the rest of its ports are reported here as they are.
  # This is the network's half of the story, and the gate's half is above: on Docker the gateway is an interface of
  # the node, so the synced policy names it and a script's fetch is refused as a cluster node — yet the container
  # itself, outside the gate, can still open the port. That is why the deployment has to keep the worker off the
  # node's network rather than rely on the gate alone. (Under rootless podman the host's address is pasta's
  # 169.254.1.2, which the node cannot name; the deployment's network is the only line there — D-177.)
  if [ -n "$gw" ]; then
    say "     the node's own ports, probed from the worker's network at the host ($gw):"
    gw_ports="$("$engine" run --rm --network "$net" -e GW="$gw" -e PORTS="$HTTP_PORT $METRICS_PORT $INTERNAL_PORT" \
      --entrypoint /bin/bash "$IMAGE" -c \
      'for p in $PORTS; do (exec 3<>/dev/tcp/$GW/$p) >/dev/null 2>&1 && echo "       $p OPEN — the deployment must not let a worker reach the node" || echo "       $p shut"; done' \
      2>&1)"
    printf '%s\n' "$gw_ports" | tail -4 | sed 's/^/    /'
    case "$gw_ports" in
      *" $INTERNAL_PORT OPEN"*) gw_open=yes ;;
      *" $INTERNAL_PORT shut"*) gw_open=no ;;
    esac
  fi

  "$engine" rm -f "$RUN_ID-w" >/dev/null 2>&1 || true
  local result=FAIL
  [ "$joined" = yes ] && [ "$reached" != no ] && [ "$refused" = yes ] && [ "$blocks" = yes ] \
    && [ "$localOnly" = yes ] && [ "$reported" = yes ] && [ "$nodeShape" != no ] && [ "$gw_ok" != no ] \
    && result=PASS
  printf '%-3s | %-6s | %-8s | %-22s | %-5s | %-8s | %-7s | %-6s | %-7s | %-10s | %-8s | %s\n' \
    case engine rootless identity joined node-ran reached refused blocked local-only reported result
  printf '%-3s | %-6s | %-8s | %-22s | %-5s | %-8s | %-7s | %-6s | %-7s | %-10s | %-8s | %s\n' \
    "C5" "$label" "rootful" "--user $(id -u):$(id -g)" \
    "$joined" "$nodeRan" "$reached" "$refused" "$blocks" "$localOnly" "$reported" "$result"
  say "     the baseline (node placement): the node made the connection and its own socket counter moved — $nodeRan"
  say "     a refused connect it made, in the gate's own shape (D-179): $nodeShape"
  say "     the worker reached an allowed target with its own route, node counters still: $reached"
  say "     refused by the worker itself=$refused  its static set still refuses metadata and RFC1918=$blocks  (the node's own egress counter unmoved: $localOnly)"
  say "     the node's own endpoint at the gateway: the container's network reaches it=$gw_open, a script's fetch is refused as a cluster node=$gw_ok"
  say "     the node counted what the worker refused because the worker reported it (D-176): $reported"
  [ "$result" = PASS ]
}

# ---------------------------------------------------------------------------------------
# C6: a cluster across hosts is a node per host (plan B1).
#
# The claim under test is a deployment claim: a worker container is per-node supply, so a compute host runs a
# worker-role node of its own. The case therefore stands up the shape such a host would have — an EDGE node
# (api,ingress: no worker role) and a COMPUTE node (worker only), clustered, with one container attached to the
# COMPUTE node's socket, and nothing attached to the edge node's, which has no socket at all.
#
# What makes the assertion sharp is that there is exactly ONE candidate. The edge node advertises capacity 0 by the
# role rule (Cluster93: a node without `worker` reports 0) — its C93_CAPACITY is deliberately 4, so what is asserted is
# the rule and not a setting — and the compute node advertises 2. JobScheduler.candidates keeps nodes with capacity > 0
# and free capacity, least loaded first, so "which node ran the job" is not a race between two eligible nodes: it is
# the only answer, and this case cannot be flaky by construction. Nothing here is new behaviour either — if it fails,
# it is the topology or a real bug, never a missing feature.
#
# The job's node comes from the job's own record (Job.toSummaryJson keeps `node` and `attempts[].node`), and the
# container is shown to have done the work the way C1/C3 do it: the compute node reports a connected worker and no
# spawned process. A worker container's log is a no-op in this image (D-175), so nothing here reads one.
# ---------------------------------------------------------------------------------------

# The capacity a live node advertises, out of GET /v1/nodes. NodeInfo.toJson is flat and ordered (id, instanceId,
# capacity, running, lastSeen, endpoint), so one record can be cut out without a JSON parser.
node_capacity() {  # node_capacity <live-nodes json> <node id>
  printf '%s' "$1" | grep -o "\"id\":\"$2\",[^}]*" | sed -n 's/.*"capacity":\([0-9]*\).*/\1/p' | head -1 || true
}

live_nodes() {  # live_nodes: what this node's API reports about the cluster
  curl -s -m 5 "http://127.0.0.1:$HTTP_PORT/v1/nodes" -H "Authorization: Bearer $JOB_TOKEN" -H "X-Cluster93-Tenant: $TENANT" || true
}

# One series' value from a node's own metrics endpoint: the compute node is a second process, so it is read at its own
# port rather than through the helpers above (which all talk to the edge node).
metric_at() {  # metric_at <port> <metric>
  { curl -sf -m 2 "http://127.0.0.1:$1/metrics" 2>/dev/null | awk -v k="$2" '$1 ~ "^"k"\\{" { print $NF }' | head -1; } || true
}

# Ignite's discovery port is a RANGE, and it does not fail when the base of that range is taken: it takes the next free
# port, while every peer is still told to join the address it was given — which is the node that held it. That shows up
# as an opaque failure inside Ignite (an SSL alert, if the holder has TLS on), so it is worth saying plainly.
require_free_cluster_port() {  # require_free_cluster_port <port> <what>
  if (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then
    say "  this host already answers on cluster port $1 ($2), so C6 cannot stand its own cluster up there."
    say "  Free that port, or point C6 elsewhere with C93_IDCHECK_CLUSTER_PORTS=edge,compute."
    return 1
  fi
  return 0
}

# The edge node: the cluster's front door, on the ports every helper in this script already uses — and a node that
# cannot accept a job whatever its capacity says. No C93_WORKER_SOCKET: a node with no compute has no pool and nothing
# to listen for, which is the other half of "the pool belongs to the node that owns the socket".
start_edge_node() {
  env \
    JDK_JAVA_OPTIONS="${NODE_JVM_OPENS[*]}" \
    C93_NODE_ID="$EDGE_NODE_ID" \
    C93_ROLES=api,ingress \
    C93_CAPACITY=4 \
    C93_WORKER_MODE=process \
    C93_HTTP_PORT="$HTTP_PORT" \
    C93_INGRESS_PORT="$INGRESS_PORT" \
    C93_METRICS_PORT="$METRICS_PORT" \
    C93_INTERNAL_PORT="$INTERNAL_PORT" \
    C93_HTTP_BIND=127.0.0.1 \
    C93_ADVERTISE_HOST=127.0.0.1 \
    C93_RATE_LIMIT_PER_MINUTE=0 \
    C93_INGRESS_DOMAIN=idcheck.test \
    C93_ADMIN_TOKEN="$ADMIN_TOKEN" \
    C93_CLUSTERED=true \
    C93_CLUSTER_PORT="$CLUSTER_PORT_EDGE" \
    C93_CLUSTER_MEMBERS="127.0.0.1:$CLUSTER_PORT_EDGE,127.0.0.1:$CLUSTER_PORT_COMPUTE" \
    C93_CLUSTER_TLS=off \
    java -jar "$ROOT/server/target/c93.jar" > "$TMP/edge-node.log" 2>&1 &
  NODE_PID=$!
  wait_for 60 "the edge node to answer /health" curl -sf -m 2 "http://127.0.0.1:$HTTP_PORT/health" >/dev/null
}

# The compute node: `worker` only — no API, no ingress — with a socket, a secret and metrics and internal ports of its
# own. It is the node this case reads, and the only one in the cluster that advertises capacity.
#   * C93_EGRESS_PLACEMENT=node on purpose: what decides where a JOB runs is the subject here, and the worker
#     placement's own network needs are C5's (a worker that decides egress needs a route and a resolver). With the node
#     placement the container needs no network at all, exactly as C1/C3/C4 assert, and no second subject is smuggled in.
#   * C93_DATA_DIR is left unset: a clustered node without one stays in memory, which is what puts several nodes on one
#     host (the code says so where it decides).
start_compute_node() {
  install -d -m 0700 "$TMP/c6-compute/sockets" "$TMP/c6-compute/secrets"
  if [ ! -f "$TMP/c6-compute/secrets/secret" ]; then
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/c6-compute/secrets/secret"
    chmod 0400 "$TMP/c6-compute/secrets/secret"
  fi
  env \
    JDK_JAVA_OPTIONS="${NODE_JVM_OPENS[*]}" \
    C93_NODE_ID="$COMPUTE_NODE_ID" \
    C93_ROLES=worker \
    C93_CAPACITY=2 \
    C93_WORKER_MODE=process \
    C93_WORKER_DEFAULT_MODE=javet \
    C93_MAX_JOBS_PER_WORKER=4 \
    C93_MAX_HEAP_MB=64 \
    C93_METRICS_PORT="$COMPUTE_METRICS_PORT" \
    C93_INTERNAL_PORT="$COMPUTE_INTERNAL_PORT" \
    C93_ADVERTISE_HOST=127.0.0.1 \
    C93_ADMIN_TOKEN="$ADMIN_TOKEN" \
    C93_EGRESS_PLACEMENT=node \
    C93_WORKER_SOCKET="$TMP/c6-compute/sockets/node.sock" \
    C93_WORKER_SECRET_FILE="$TMP/c6-compute/secrets/secret" \
    C93_WORKER_CONNECTIONS_MAX=2 \
    C93_CLUSTERED=true \
    C93_CLUSTER_PORT="$CLUSTER_PORT_COMPUTE" \
    C93_CLUSTER_MEMBERS="127.0.0.1:$CLUSTER_PORT_EDGE,127.0.0.1:$CLUSTER_PORT_COMPUTE" \
    C93_CLUSTER_TLS=off \
    java -jar "$ROOT/server/target/c93.jar" > "$TMP/compute-node.log" 2>&1 &
  C6_NODE_PID=$!
  wait_for 60 "the compute node's metrics endpoint" curl -sf -m 2 "http://127.0.0.1:$COMPUTE_METRICS_PORT/metrics" >/dev/null
}

# A node that did not come up leaves its reason in its own log — and the run's temp directory is removed on exit, so the
# tail is printed here rather than pointed at.
show_node_log() {  # show_node_log <what> <log file>
  say "  $1 did not start. Its last lines:"
  tail -20 "$2" 2>/dev/null | sed 's/^/    /'
}

check_cluster_placement() {  # check_cluster_placement <engine> <label> <identity flag> <identity args...>
  local engine="$1" label="$2" flag="$3"; shift 3
  ENGINE="$engine"
  IMAGE="$(image_for "$engine")"

  local nodes_seen=no edge_cap=n/a compute_cap=n/a placed=no container=no nodes_json="" jobOut=""
  local poolConnected=n/a poolSpawned=n/a

  # C5 left one node running the worker placement of its own; C6 brings up its own pair (same box, different ports for
  # the compute node — the edge node is on the ports the helpers above use).
  require_free_cluster_port "$CLUSTER_PORT_EDGE" "the edge node's discovery port" || return 1
  require_free_cluster_port "$CLUSTER_PORT_COMPUTE" "the compute node's discovery port" || return 1
  stop_node
  start_edge_node || { show_node_log "the edge node" "$TMP/edge-node.log"; return 1; }
  JOB_TOKEN="$(mint_job_token "$TENANT")"
  [ -n "$JOB_TOKEN" ] || { say "  no job token on the edge node (see $TMP/edge-node.log)"; return 1; }
  start_compute_node || { show_node_log "the compute node" "$TMP/compute-node.log"; return 1; }

  # Convergence, and the capacity that will decide the placement. Waited for rather than assumed: a job submitted
  # before the two nodes found each other would queue (D-156) and be re-dispatched, and the case would then be
  # measuring timing instead of placement.
  for _ in $(seq 1 120); do
    nodes_json="$(live_nodes)"
    compute_cap="$(node_capacity "$nodes_json" "$COMPUTE_NODE_ID")"
    if [ -n "$compute_cap" ]; then nodes_seen=yes; break; fi
    sleep 0.5
  done
  edge_cap="$(node_capacity "$nodes_json" "$EDGE_NODE_ID")"

  # One container, and only one: attached to the COMPUTE node's socket and secret. Nothing is attached to the edge
  # node's — it has none. This is the deployment side of "a worker container is per-node supply".
  local scratch="$SCRATCH/$RUN_ID-c6"
  install -d -m 0777 "$scratch/tmp" "$scratch/home"
  "$engine" run -d --name "$RUN_ID-c6" --label "$LABEL" --network none --cap-drop=ALL \
    --security-opt no-new-privileges --read-only --pids-limit 512 "$@" \
    -v "$TMP/c6-compute/sockets:/run/cluster93/workers" \
    -v "$TMP/c6-compute/secrets/secret:/run/secrets/c93-worker-secret:ro" \
    -v "$scratch/tmp:/tmp" -v "$scratch/home:/home" \
    --entrypoint "/opt/cluster93/bin/$WORKER_BIN" \
    "$IMAGE" ipc --socket /run/cluster93/workers/node.sock \
      --secret-file /run/secrets/c93-worker-secret --heap-mb 64 --threads 4 >/dev/null \
    || { say "  the compute node's worker container did not start (the engine said so above)"; return 1; }
  # A worker container's cold start is seconds (the engine loads its native library), so wait for the compute node's
  # own count rather than guess a sleep.
  for _ in $(seq 1 240); do
    [ "$(metric_at "$COMPUTE_METRICS_PORT" c93_workers_connected)" = "1.0" ] && break
    sleep 0.5
  done

  local id=""
  id="$(submit_job)"
  if [ -n "$id" ] && [ "$(await_job "$id")" = "SUCCEEDED" ]; then
    jobOut="$(job_json "$id")"
    # `node` (and attempts[].node) is the job's own account of which node ran it. It must name the compute node, and
    # must not name the edge node.
    case "$jobOut" in
      *"\"node\":\"$COMPUTE_NODE_ID\""*)
        case "$jobOut" in
          *"\"node\":\"$EDGE_NODE_ID\""*) placed=no ;;
          *) placed=yes ;;
        esac ;;
    esac
    # The container did it: the compute node counts a connected worker and has spawned no worker process of its own —
    # C1/C3's evidence, read from the compute node's metrics rather than the edge node's. Read here and kept, because
    # the container is removed a few lines below and its own disconnect would be what a later read saw.
    poolConnected="$(metric_at "$COMPUTE_METRICS_PORT" c93_workers_connected)"
    poolSpawned="$(metric_at "$COMPUTE_METRICS_PORT" c93_workers_running)"
    if [ "$poolConnected" = "1.0" ] && [ "$poolSpawned" = "0.0" ]; then
      container=yes
    fi
  fi

  if [ "$placed" != yes ]; then
    say "  what the edge node's API reports about the cluster (one record per node, with its capacity):"
    printf '%s' "$nodes_json" | head -c 600 | sed 's/^/    /'
    say ""
    say "  the job's own record:"
    printf '%s' "${jobOut:-<no job record — the job was not accepted or did not finish>}" | head -c 800 | sed 's/^/    /'
    say ""
    say "  what the compute node said about itself and its worker:"
    grep -aE "roles:|Listening for worker|Worker container connected|Advertise" "$TMP/compute-node.log" | tail -5 | sed 's/^/    /'
    say ""
  fi

  "$engine" rm -f "$RUN_ID-c6" >/dev/null 2>&1 || true

  local result=FAIL
  [ "$nodes_seen" = yes ] && [ "$edge_cap" = "0" ] && [ "$placed" = yes ] && [ "$container" = yes ] && result=PASS
  printf '%-3s | %-6s | %-8s | %-22s | %-9s | %-8s | %-9s | %-9s | %s\n' \
    case engine rootless identity nodes edge-cap ran-there container result
  printf '%-3s | %-6s | %-8s | %-22s | %-9s | %-8s | %-9s | %-9s | %s\n' \
    "C6" "$engine" "rootful" "$flag" "$nodes_seen" "$edge_cap" "$placed" "$container" "$result"
  say "     the edge node (api,ingress, C93_CAPACITY=4) advertises capacity 0 — the role rule, not the setting: $edge_cap"
  say "     the compute node (worker) is the only candidate (it advertises ${compute_cap:-?}) and the cluster is seen from the edge node's API: $nodes_seen"
  say "     the job submitted at the edge node's API names the compute node, never the edge node: $placed"
  say "     on the compute node: connected=$poolConnected spawned=$poolSpawned — its container did the work: $container"
  [ "$result" = PASS ]
}

# ---------------------------------------------------------------------------------------
# C7: the deployment the compose files install — both nodes in containers, and the compute node's
# worker in a container of its own on a network the cluster is not on.
# ---------------------------------------------------------------------------------------
# C6 stands the two roles up as processes, which is what a host deployment is. The compose files
# (install/docker/) instead run each node as a container and put a node's supply in worker containers beside it.
# That shape has failure modes of its own: the cluster has to form between container addresses, the socket and the
# secret have to cross a mount, and the worker must not be able to reach the node's ports. It is checked, not assumed.
#
# The worker is deliberately on its OWN network: it connects over the socket directory, so it needs no route to the
# node at all. Docker drops traffic between user-defined networks, so the compute node's internal endpoint (D-151:
# bound on every interface because peers must reach it) is unreachable from the worker's network. Two lines are
# asserted, and they answer different questions. A job's `fetch` to the compute node's own container address is
# refused by the gate as a cluster node — the policy the compute node synced names its own interfaces, so the gate
# does not need the network to know the node. The container's own network is then probed from inside the worker, by
# address rather than name, and must not reach the same port: that is the deployment's half of the "the gate is a
# second line" story C5 tells, and the first line is that there is no route.
check_container_cluster_placement() {  # <engine> <label> <identity flag> <identity args...>
  local engine="$1" label="$2" flag="$3"; shift 3
  ENGINE="$engine"
  IMAGE="$(image_for "$engine")"

  # C6's nodes still hold the standard host ports, so this case publishes the same numbers one hundred higher; the
  # containers keep the usual numbers inside their own nets. Every helper above reads these globals, so pointing them
  # at the published ports for the length of the case is enough (and they are put back afterwards).
  local saved_http="$HTTP_PORT" saved_ingress="$INGRESS_PORT" saved_metrics="$METRICS_PORT"
  HTTP_PORT=$((HTTP_PORT + 100))
  INGRESS_PORT=$((INGRESS_PORT + 100))
  METRICS_PORT=$((METRICS_PORT + 100))
  local compute_metrics=$((COMPUTE_METRICS_PORT + 100))
  local net_cluster="$RUN_ID-c7-cluster" net_workers="$RUN_ID-c7-workers"
  local edge="$RUN_ID-c7-edge" compute="$RUN_ID-c7-compute" worker="$RUN_ID-c7-worker"
  local state="$TMP/c7-compute"

  local nodes_seen=no edge_cap=n/a compute_cap=n/a placed=no container=no isolated=n/a
  local nodes_json="" jobOut="" pool_connected=n/a pool_spawned=n/a result=FAIL

  restore_ports() {
    HTTP_PORT="$saved_http"
    INGRESS_PORT="$saved_ingress"
    METRICS_PORT="$saved_metrics"
  }

  if [ ! -f "$state/secrets/secret" ]; then
    install -d -m 0700 "$state/sockets" "$state/secrets"
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$state/secrets/secret"
    chmod 0400 "$state/secrets/secret"
  fi

  "$engine" network create --label "$LABEL" "$net_cluster" >/dev/null \
    || { say "  could not create the cluster network"; restore_ports; return 1; }
  NETWORKS+=("$net_cluster")
  "$engine" network create --label "$LABEL" "$net_workers" >/dev/null \
    || { say "  could not create the worker network"; restore_ports; return 1; }
  NETWORKS+=("$net_workers")

  # The two nodes' shared settings. C93_DATA_DIR is set EMPTY on purpose — the image sets it to a persistent path, but
  # a clustered node without a directory stays in memory, exactly as C6's processes do — so the containers need no
  # durable volume and a failed run leaves nothing behind.
  local -a cluster_env=(
    -e C93_CLUSTERED=true
    -e "C93_CLUSTER_MEMBERS=edge:$CLUSTER_PORT_EDGE,compute:$CLUSTER_PORT_COMPUTE"
    -e C93_CLUSTER_TLS=off
    -e C93_DATA_DIR=
    -e C93_ADMIN_TOKEN="$ADMIN_TOKEN"
    -e C93_WORKER_MODE=process
  )

  # `--network-alias` is what makes the discovery members nameable: Docker's DNS on a user-defined network answers
  # `edge` and `compute`, which is what a compose deployment relies on (service names).
  "$engine" run -d --name "$edge" --label "$LABEL" --network "$net_cluster" --network-alias edge \
    --cap-drop=ALL --security-opt no-new-privileges "$@" \
    "${cluster_env[@]}" \
    -e C93_NODE_ID="$EDGE_NODE_ID" -e C93_ROLES=api,ingress -e C93_CAPACITY=4 \
    -e C93_CLUSTER_PORT="$CLUSTER_PORT_EDGE" \
    -e C93_HTTP_PORT="$HTTP_PORT" -e C93_INGRESS_PORT="$INGRESS_PORT" \
    -e C93_METRICS_PORT="$METRICS_PORT" -e C93_INTERNAL_PORT="$INTERNAL_PORT" \
    -e C93_ADVERTISE_HOST=edge -e C93_RATE_LIMIT_PER_MINUTE=0 -e C93_INGRESS_DOMAIN=idcheck.test \
    -p "127.0.0.1:$HTTP_PORT:$HTTP_PORT" \
    -p "127.0.0.1:$INGRESS_PORT:$INGRESS_PORT" \
    -p "127.0.0.1:$METRICS_PORT:$METRICS_PORT" \
    "$IMAGE" >/dev/null \
    || { say "  the edge node container did not start"; restore_ports; return 1; }

  if ! wait_for 240 "the containerized edge node to answer /health" \
      curl -sf -m 2 "http://127.0.0.1:$HTTP_PORT/health"; then
    say "  the edge node container did not answer. Its last lines:"
    "$engine" logs "$edge" 2>&1 | tail -20 | sed 's/^/    /'
    restore_ports
    return 1
  fi
  JOB_TOKEN="$(mint_job_token "$TENANT")"
  [ -n "$JOB_TOKEN" ] || { say "  no job token on the containerized edge node"; restore_ports; return 1; }

  # The compute node's worker placement is on the node (`C93_EGRESS_PLACEMENT=node`), as in C6: what is under test is
  # the containerized topology, and the worker placement's network needs are C5's. The worker therefore needs no
  # network for egress, and the one it gets is only there so the case can prove it has no route to the cluster.
  "$engine" run -d --name "$compute" --label "$LABEL" --network "$net_cluster" --network-alias compute \
    --cap-drop=ALL --security-opt no-new-privileges "$@" \
    "${cluster_env[@]}" \
    -e C93_NODE_ID="$COMPUTE_NODE_ID" -e C93_ROLES=worker -e C93_CAPACITY=2 \
    -e C93_CLUSTER_PORT="$CLUSTER_PORT_COMPUTE" \
    -e C93_METRICS_PORT="$COMPUTE_METRICS_PORT" -e C93_INTERNAL_PORT="$COMPUTE_INTERNAL_PORT" \
    -e C93_ADVERTISE_HOST=compute \
    -e C93_EGRESS_PLACEMENT=node -e C93_WORKER_DEFAULT_MODE=javet \
    -e C93_MAX_JOBS_PER_WORKER=4 -e C93_MAX_HEAP_MB=64 -e C93_WORKER_CONNECTIONS_MAX=2 \
    -e C93_WORKER_SOCKET=/run/cluster93/workers/node.sock \
    -e C93_WORKER_SECRET_FILE=/run/secrets/c93-worker-secret \
    -v "$state/sockets:/run/cluster93/workers" \
    -v "$state/secrets/secret:/run/secrets/c93-worker-secret:ro" \
    -p "127.0.0.1:$compute_metrics:$COMPUTE_METRICS_PORT" \
    "$IMAGE" >/dev/null \
    || { say "  the compute node container did not start"; restore_ports; return 1; }

  local scratch="$SCRATCH/$RUN_ID-c7"
  install -d -m 0777 "$scratch/tmp" "$scratch/home"
  "$engine" run -d --name "$worker" --label "$LABEL" --network "$net_workers" \
    --cap-drop=ALL --security-opt no-new-privileges --read-only --pids-limit 512 "$@" \
    -v "$state/sockets:/run/cluster93/workers" \
    -v "$state/secrets/secret:/run/secrets/c93-worker-secret:ro" \
    -v "$scratch/tmp:/tmp" -v "$scratch/home:/home" \
    --entrypoint "/opt/cluster93/bin/$WORKER_BIN" \
    "$IMAGE" ipc --socket /run/cluster93/workers/node.sock \
      --secret-file /run/secrets/c93-worker-secret --heap-mb 64 --threads 4 >/dev/null \
    || { say "  the compute node's worker container did not start"; restore_ports; return 1; }

  if ! wait_for 240 "the containerized compute node's metrics endpoint" \
      curl -sf -m 2 "http://127.0.0.1:$compute_metrics/metrics"; then
    say "  the compute node container did not answer. Its last lines:"
    "$engine" logs "$compute" 2>&1 | tail -20 | sed 's/^/    /'
    restore_ports
    return 1
  fi

  # Convergence: both containers have to find each other by name before a job can be placed.
  for _ in $(seq 1 120); do
    nodes_json="$(live_nodes)"
    compute_cap="$(node_capacity "$nodes_json" "$COMPUTE_NODE_ID")"
    if [ -n "$compute_cap" ]; then nodes_seen=yes; break; fi
    sleep 0.5
  done
  edge_cap="$(node_capacity "$nodes_json" "$EDGE_NODE_ID")"

  for _ in $(seq 1 240); do
    [ "$(metric_at "$compute_metrics" c93_workers_connected)" = "1.0" ] && break
    sleep 0.5
  done

  local id=""
  id="$(submit_job)"
  if [ -n "$id" ] && [ "$(await_job "$id")" = "SUCCEEDED" ]; then
    jobOut="$(job_json "$id")"
    case "$jobOut" in
      *"\"node\":\"$COMPUTE_NODE_ID\""*)
        case "$jobOut" in
          *"\"node\":\"$EDGE_NODE_ID\""*) placed=no ;;
          *) placed=yes ;;
        esac ;;
    esac
    pool_connected="$(metric_at "$compute_metrics" c93_workers_connected)"
    pool_spawned="$(metric_at "$compute_metrics" c93_workers_running)"
    if [ "$pool_connected" = "1.0" ] && [ "$pool_spawned" = "0.0" ]; then
      container=yes
    fi
  fi

  # The second line, and the one that covers a script: the compute node's own address is an interface of that node,
  # so the policy it synced names it and a job's fetch to its internal endpoint is refused as a cluster node. The
  # worker could not route there anyway (the probe below), but this is what C5 asserts on a host's gateway, asserted
  # here on a container's address — the gate does not depend on the network to know the node.
  local compute_ip="" gate_ok=n/a gate_out=""
  compute_ip="$("$engine" inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$compute" 2>/dev/null || true)"
  if [ -n "$compute_ip" ]; then
    local gid=""
    gid="$(submit_job "export default async () => {
      try { await fetch('http://$compute_ip:$COMPUTE_INTERNAL_PORT/health'); return { reached: true }; }
      catch (e) {
        const r = { reached: false, code: e.cause ? e.cause.code : e.code, why: e.cause ? e.cause.message : null };
        return { ...r, clusterNode: r.code === 'egress_denied' && (r.why || '').includes('resolves to one of the cluster') };
      }
    }")"
    [ -n "$gid" ] || say "  the gate probe job was not accepted (no id came back)"
    [ -n "$gid" ] && await_job "$gid" >/dev/null
    gate_out="$(job_json "${gid:-x}")"
    case "$gate_out" in
      *'"clusterNode":true'*) gate_ok=yes ;;
      *) gate_ok=no ;;
    esac
  else
    say "  could not read the compute node container's address; the gate assertion cannot run"
  fi

  # The first line of the deployment's defence: the worker's network cannot reach the cluster's. Probed from inside
  # the worker container itself, against the compute node's address ON the cluster network — its peer name means
  # nothing on the worker's net, so the address is what makes this an answer about routing rather than DNS. A
  # completed connection (rc 0) is the only outcome that counts as reachable: Docker isolates two user-defined
  # networks by dropping between them (a timeout), and a firewall that answers with a refusal is separation too.
  local probe_rc=n/a
  if [ -z "$compute_ip" ]; then
    say "  could not read the compute node container's address; the isolation probe cannot run"
    isolated=n/a
  elif "$engine" exec "$worker" /bin/bash -c 'command -v timeout >/dev/null' >/dev/null 2>&1; then
    probe_rc=0
    "$engine" exec "$worker" timeout 3 /bin/bash -c \
      "exec 3<>/dev/tcp/$compute_ip/$COMPUTE_INTERNAL_PORT" >/dev/null 2>&1 || probe_rc=$?
    if [ "$probe_rc" = 0 ]; then
      isolated=no
    else
      isolated=yes
    fi
  fi

  if [ "$placed" != yes ] || [ "$container" != yes ]; then
    say "  what the edge node's API reports about the cluster:"
    printf '%s' "$nodes_json" | head -c 600 | sed 's/^/    /'
    say ""
    say "  the job's own record:"
    printf '%s' "${jobOut:-<no job record — the job was not accepted or did not finish>}" | head -c 800 | sed 's/^/    /'
    say ""
    say "  the compute node container's last lines:"
    "$engine" logs "$compute" 2>&1 | tail -20 | sed 's/^/    /'
    say ""
  fi
  if [ "$gate_ok" = no ]; then
    say "  a script's fetch to the compute node's own endpoint was not refused as a cluster node:"
    printf '%s' "${gate_out:-<no job record>}" | head -c 1200 | sed 's/^/    /'
    say ""
  fi

  "$engine" rm -f "$edge" "$compute" "$worker" >/dev/null 2>&1 || true

  [ "$nodes_seen" = yes ] && [ "$edge_cap" = "0" ] && [ "$placed" = yes ] && [ "$container" = yes ] \
    && [ "$isolated" = yes ] && [ "$gate_ok" = yes ] && result=PASS
  printf '%-3s | %-6s | %-8s | %-22s | %-9s | %-8s | %-9s | %-9s | %-8s | %s\n' \
    case engine rootless identity nodes edge-cap ran-there container isolated result
  printf '%-3s | %-6s | %-8s | %-22s | %-9s | %-8s | %-9s | %-9s | %-8s | %s\n' \
    "C7" "$engine" "rootful" "$flag" "$nodes_seen" "$edge_cap" "$placed" "$container" "$isolated" "$result"
  say "     both nodes are containers on a cluster network the discovery members name (edge:$CLUSTER_PORT_EDGE, compute:$CLUSTER_PORT_COMPUTE): $nodes_seen"
  say "     the edge node (api,ingress, C93_CAPACITY=4) advertises capacity 0 — the role rule: $edge_cap"
  say "     the job submitted at the edge node's API names the compute node (which advertises ${compute_cap:-?}): $placed"
  say "     on the compute node container: connected=$pool_connected spawned=$pool_spawned — its worker container did the work: $container"
  say "     a script's fetch to the compute node's own endpoint is refused by the gate as a cluster node: $gate_ok"
  say "     the worker's own network cannot reach the compute node's internal endpoint at ${compute_ip:-?}:$COMPUTE_INTERNAL_PORT (probe rc=$probe_rc): $isolated"
  restore_ports
  [ "$result" = PASS ]
}

# Podman rootless (D-149): the image's own user (c93, 10001:999) is mapped to the invoking host uid, which is what makes
# the node's files — the 0600 socket and the 0400 secret — belong to the container's process. Plain `keep-id` does NOT
# do that: it leaves the container running as the image's user while the host files appear as the invoking uid, so the
# worker cannot read the secret at all.
#
# The two engines here are the two a deployment actually asks for: rootless Podman and rootful Docker. Rootless Docker
# has no case (D-183) — it is not an engine the guides or the compose files install, and a case that can only print
# SKIP teaches a reader to ignore the matrix. The daemon is still asked whether it is rootless, because that is what
# tells C3 (and the cases built on it) to skip instead of failing obscurely under an engine they were not written for.
run_case_if "$(rootless_podman && echo 1 || echo 0)" C1 podman rootless-podman "--userns=keep-id:uid=10001,gid=999" --userns=keep-id:uid=10001,gid=999
run_case_if "$( { have_docker && ! rootless_docker; } && echo 1 || echo 0)" C3 docker rootful "--user $(id -u):$(id -g)" --user "$(id -u):$(id -g)"
if [ "$WANT_CASE" = "" ] || [ "$WANT_CASE" = "C4" ]; then
  if [ "$WORKERS_OK" != 1 ]; then
    printf '%-3s | %-16s | %s\n' "C4" docker "SKIP (needs the worker image — see the note above)"
    SKIPPED=$((SKIPPED + 1))
  elif have_docker && ! rootless_docker; then
    check_identity_refused docker 12345:12345 || FAILED=1
  else
    printf '%-3s | %-16s | %s\n' "C4" docker "SKIP (needs a rootful engine to run a foreign uid)"
    SKIPPED=$((SKIPPED + 1))
  fi
fi

if [ "$WANT_CASE" = "" ] || [ "$WANT_CASE" = "C5" ]; then
  if [ "$WORKERS_OK" != 1 ]; then
    printf '%-3s | %-16s | %s\n' "C5" docker "SKIP (needs the worker image — see the note above)"
    SKIPPED=$((SKIPPED + 1))
  elif have_docker && ! rootless_docker; then
    check_worker_placement docker rootful || FAILED=1
  else
    printf '%-3s | %-16s | %s\n' "C5" docker "SKIP (needs a rootful engine to run a container as this user)"
    SKIPPED=$((SKIPPED + 1))
  fi
fi

if [ "$WANT_CASE" = "" ] || [ "$WANT_CASE" = "C6" ]; then
  if [ "$WORKERS_OK" != 1 ]; then
    printf '%-3s | %-16s | %s\n' "C6" docker "SKIP (needs the worker image — see the note above)"
    SKIPPED=$((SKIPPED + 1))
  elif have_docker && ! rootless_docker; then
    check_cluster_placement docker rootful "--user $(id -u):$(id -g)" --user "$(id -u):$(id -g)" || FAILED=1
  else
    printf '%-3s | %-16s | %s\n' "C6" docker "SKIP (needs a rootful engine to run a container as this user)"
    SKIPPED=$((SKIPPED + 1))
  fi
fi

if [ "$WANT_CASE" = "" ] || [ "$WANT_CASE" = "C7" ]; then
  if [ "$WORKERS_OK" != 1 ]; then
    printf '%-3s | %-16s | %s\n' "C7" docker "SKIP (needs the worker image — see the note above)"
    SKIPPED=$((SKIPPED + 1))
  elif have_docker && ! rootless_docker; then
    check_container_cluster_placement docker rootful "--user $(id -u):$(id -g)" --user "$(id -u):$(id -g)" || FAILED=1
  else
    printf '%-3s | %-16s | %s\n' "C7" docker "SKIP (needs a rootful engine to run a container as this user)"
    SKIPPED=$((SKIPPED + 1))
  fi
fi

hr
if [ "$FAILED" != 0 ]; then
  say "At least one available case FAILED."
elif [ "$SKIPPED" != 0 ]; then
  say "Nothing failed; $SKIPPED case(s) were skipped. Each SKIP line says what would enable it."
else
  say "Every case this host can run passed."
fi
exit "$FAILED"
