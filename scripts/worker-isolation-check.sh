#!/usr/bin/env bash
#
# worker-isolation-check.sh — does a worker container actually join a node over the node's worker socket (D-166)?
#
# The two things that decide it differ per engine and per kernel, and no unit test can catch a mismatch:
#   * D-149's identity mapping — a worker must present the node's host identity, or the 0700 socket dir / 0600 socket
#     refuses it;
#   * S10's no-network property — a worker container must have no route off the host, probed from INSIDE it.
# So this runs the real thing: a node as a process, a worker container from a node image, and one job over the socket.
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

DRY_RUN=0
PULL=0
WANT_CASE=""
NAMED_IMAGE=""
IMAGE=""
# The worker command the containers run: whichever engine this checkout actually has in bin/ (a build may hold one).
WORKER_BIN="worker-javet"
if [ -x "$ROOT/bin/worker-graaljs" ] && [ ! -x "$ROOT/bin/worker-javet" ]; then
  WORKER_BIN="worker-graaljs"
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

say() { printf '%s\n' "$*"; }
hr() { printf '%s\n' "------------------------------------------------------------------------"; }

# ---------------------------------------------------------------------------------------
# State the trap cleans up. Everything it holds was created by this run, under its own run id.
# ---------------------------------------------------------------------------------------
TMP=""
NODE_PID=""
ENGINE=""
declare -a PREPARED_IMAGES=()
declare -a CONTAINERS=()

cleanup() {
  local status=$?
  if [ -n "$ENGINE" ]; then
    # Only containers this run labelled: `ps -aq -f label=…` cannot match another node's or the demo's.
    while IFS= read -r id; do
      [ -n "$id" ] && "$ENGINE" rm -f "$id" >/dev/null 2>&1 || true
    done < <("$ENGINE" ps -aq --filter "label=$LABEL" 2>/dev/null || true)
    for entry in ${PREPARED_IMAGES[@]+"${PREPARED_IMAGES[@]}"}; do
      # Only the images this run built, each removed by the engine that built it.
      set -- $entry
      "$1" image rm -f "$2" >/dev/null 2>&1 || true
    done
  fi
  if [ -n "$NODE_PID" ] && kill -0 "$NODE_PID" 2>/dev/null; then
    kill "$NODE_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$NODE_PID" 2>/dev/null || break; sleep 0.25; done
    kill -9 "$NODE_PID" 2>/dev/null || true
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
  say "Cases (each asserts: worker HELLO arrives | a job round-trips | the wrong secret is refused | no network):"
  say "  C1  rootless podman, --userns=keep-id        $(rootless_podman && echo AVAILABLE || echo 'SKIP (needs podman rootless: podman info)')"
  say "  C2  rootless docker, container uid 0         $( { have_docker && rootless_docker; } && echo AVAILABLE || echo 'SKIP (needs dockerd-rootless-setuptool.sh install)')"
  say "  C3  rootful docker, --user <uid>:<gid>       $( { have_docker && ! rootless_docker; } && echo AVAILABLE || echo 'SKIP (pose: rootful docker is not in use)')"
  say "  C4  wrong identity, refused                  whenever any engine is available"
}

# ---------------------------------------------------------------------------------------
# The node: a process, always (D-167). It is the only end that listens.
# ---------------------------------------------------------------------------------------
start_node() {
  local node_jar="$ROOT/server/target/c93.jar"
  [ -f "$node_jar" ] || { say "Missing $node_jar — build it: ./mvnw -q -pl server -am package -DskipTests"; exit 1; }
  install -d -m 0700 "$TMP/sockets"
  install -d -m 0700 "$TMP/secrets"
  install -d -m 0700 "$TMP/scratch"
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/secrets/secret"
  chmod 0400 "$TMP/secrets/secret"
  # A secret that is NOT the node's, for the refusal check: same shape, wrong value.
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TMP/secrets/wrong"
  chmod 0400 "$TMP/secrets/wrong"
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

metric() { curl -sf -m 2 "http://127.0.0.1:$METRICS_PORT/metrics" 2>/dev/null | awk -v k="$1" '$1 ~ "^"k"\\{" { print $NF }' | head -1; }

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
    PREPARED_IMAGES+=("$engine $tag")
  fi
  echo "$tag"
}

# ---------------------------------------------------------------------------------------
# One case: start a worker container as the given identity, then the four assertions.
# ---------------------------------------------------------------------------------------
run_worker() {  # run_worker <name> <identity args...>
  local name="$1"; shift
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
    --network none \
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

submit_job() {
  curl -sf -m 5 -X POST "http://127.0.0.1:$HTTP_PORT/v1/jobs" \
    -H "Authorization: Bearer $JOB_TOKEN" -H "X-Cluster93-Tenant: $TENANT" \
    -H 'Content-Type: application/json' \
    -d '{"script":"export default () => ({ ran: true })","timeoutMs":15000}' \
    | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
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
  local hello=no job=no secret=no network=no result=FAIL
  # This case runs on its own engine: podman keeps a separate image store from docker, so both the image and every
  # command below have to come from the same one.
  ENGINE="$engine"
  IMAGE="$(image_for "$engine")"
  # Every case starts from whatever workers are still connected (the previous case's, if any), so its assertions are
  # about its own connection: the count has to grow by exactly one.
  local before_connected want_connected
  before_connected="$(metric c93_workers_connected)"
  want_connected="$(( ${before_connected%.*} + 1 )).0"

  run_worker "ok" "$@" || { say "  $case_id: the worker container did not start"; return 1; }
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
  if [ "$hello" != yes ] || [ "$job" != yes ] || [ "$secret" != yes ] || [ "$network" != yes ]; then
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
  [ "$hello" = yes ] && [ "$job" = yes ] && [ "$secret" = yes ] && [ "$network" = yes ] && result=PASS
  printf '%s | %s | %s | %-22s | %-5s | %-3s | %-6s | %-7s | %s\n' \
    "$case_id" "$engine" "$rootless" "$flag" "$hello" "$job" "$secret" "$network" "$result"
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
JOB_TOKEN="$(mint_job_token "$TENANT")"
if [ -z "$JOB_TOKEN" ]; then
  say "Could not mint a tenant token through the API (see $TMP/node.log); there is nothing to submit a job with."
  exit 1
fi


printf '%-3s | %-6s | %-8s | %-22s | %-5s | %-3s | %-6s | %-7s | %s\n' \
  case engine rootless identity hello job secret network result
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

# Podman rootless (D-149): the image's own user (c93, 10001:999) is mapped to the invoking host uid, which is what makes
# the node's files — the 0600 socket and the 0400 secret — belong to the container's process. Plain `keep-id` does NOT
# do that: it leaves the container running as the image's user while the host files appear as the invoking uid, so the
# worker cannot read the secret at all.
run_case_if "$(rootless_podman && echo 1 || echo 0)" C1 podman rootless-podman "--userns=keep-id:uid=10001,gid=999" --userns=keep-id:uid=10001,gid=999
run_case_if "$( { have_docker && rootless_docker; } && echo 1 || echo 0)" C2 docker rootless-docker "container uid 0" --user 0:0
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

hr
if [ "$FAILED" != 0 ]; then
  say "At least one available case FAILED."
elif [ "$SKIPPED" != 0 ]; then
  say "Nothing failed; $SKIPPED case(s) were skipped. Each SKIP line says what would enable it."
else
  say "Every case this host can run passed."
fi
exit "$FAILED"
