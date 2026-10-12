#!/usr/bin/env bash
# Local development stack orchestrator for Automately.
#
# Builds the server jar and the native CLI, starts a single-node clustered server with
# durable Ignite WAL persistence, seeds a `dev` tenant and token, prints the MCP
# configuration for agents, runs the example scripts, and stops the server again.
#
# Usage:
#   scripts/dev.sh build                  Build the server jar, native CLI and javet worker.
#   scripts/dev.sh start [--data-dir DIR] Start the server in the background (default data dir:
#                                         $HOME/.automately/dev-data). Waits for /health.
#   scripts/dev.sh stop                   Stop the server started by `start`.
#   scripts/dev.sh status                 Show /health and /v1/nodes.
#   scripts/dev.sh seed                   Create the `dev` tenant, mint an all-scoped token, save
#                                         $HOME/.config/automately/config.json and print MCP config.
#   scripts/dev.sh test-examples          Run examples/01..06 against the running server.
#   scripts/dev.sh restart                stop, then start.
#
# Environment:
#   AUTOMATELY_JAVA        the `java` used to run the server jar (default: JAVA_HOME/bin/java, then java)
#   START_TIMEOUT_SECONDS  how long `start` waits for /health (default: 60; a cold durable Ignite node
#                          takes about 30s)
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

AUTOMATELY_HOME="${HOME}/.automately"
# Development testing keeps its own store, so an ephemeral dev cluster never shares an Ignite
# baseline (or its WAL) with the permanent master node scripts/local.sh installs.
DEFAULT_DATA_DIR="${AUTOMATELY_HOME}/dev-data"
PID_FILE="${AUTOMATELY_HOME}/automately.pid"
LOG_FILE="${AUTOMATELY_HOME}/server.log"
CONFIG_FILE="${HOME}/.config/automately/config.json"
SERVER_JAR="${REPO_ROOT}/automately-server/target/automately.jar"
ADMIN_TOKEN="dev-admin-token-1234567890abcdef"
URL="http://localhost:8080"
MCP_URL="${URL}/mcp"

# Apache Ignite 2.18 on JDK 21 needs these module opens (pom.xml's ignite.jvm.args, the Dockerfile's
# JDK_JAVA_OPTIONS). Without them the server fails at startup with InaccessibleObjectException.
IGNITE_JVM_ARGS="--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED \
  --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED \
  --add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED \
  --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.sql/java.sql=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.management/sun.management=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

usage() {
  # The block comment after the shebang, without the leading "# ".
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

java_bin() {
  if [[ -n "${AUTOMATELY_JAVA:-}" ]]; then
    echo "$AUTOMATELY_JAVA"
  elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    echo "$JAVA_HOME/bin/java"
  else
    command -v java
  fi
}

cli_bin() {
  if [[ -x "$REPO_ROOT/bin/automately" ]]; then
    echo "$REPO_ROOT/bin/automately"
  elif [[ -x "${HOME}/.local/bin/automately" ]]; then
    echo "${HOME}/.local/bin/automately"
  else
    command -v automately
  fi
}

# --------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------
cmd_build() {
  echo "== building automately-server/target/automately.jar" >&2
  ./mvnw -q package -pl automately-server -am -DskipTests
  if [[ ! -f "$SERVER_JAR" ]]; then
    echo "Error: $SERVER_JAR was not produced" >&2
    exit 1
  fi

  echo "== building the native CLI" >&2
  "$REPO_ROOT/scripts/build-cli.sh"

  if [[ ! -x "$REPO_ROOT/bin/worker-javet" ]]; then
    echo "== bin/worker-javet is missing; building it" >&2
    "$REPO_ROOT/scripts/build-workers.sh" javet
  fi
  echo "== build complete" >&2
}

# --------------------------------------------------------------------------
# start
# --------------------------------------------------------------------------
cmd_start() {
  local data_dir="$DEFAULT_DATA_DIR"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --data-dir)
        if [[ -z "${2:-}" ]]; then
          echo "Error: --data-dir needs a path" >&2
          exit 2
        fi
        data_dir="$2"
        shift 2
        ;;
      *)
        echo "Error: unknown argument '$1'" >&2
        exit 2
        ;;
    esac
  done

  if [[ ! -f "$SERVER_JAR" ]]; then
    echo "Error: $SERVER_JAR is missing. Run: scripts/dev.sh build" >&2
    exit 1
  fi
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Already running (pid $(cat "$PID_FILE")). Run: scripts/dev.sh stop" >&2
    exit 1
  fi

  mkdir -p "$AUTOMATELY_HOME" "$data_dir"
  echo "== starting the server (data dir: $data_dir)" >&2
  env \
    JDK_JAVA_OPTIONS="$IGNITE_JVM_ARGS" \
    AUTOMATELY_NODE_ID=local-dev \
    AUTOMATELY_CLUSTERED=true \
    AUTOMATELY_CLUSTER_SIZE=1 \
    AUTOMATELY_CLUSTER_MEMBERS=127.0.0.1 \
    AUTOMATELY_CLUSTER_TLS=off \
    AUTOMATELY_ADMIN_TOKEN="$ADMIN_TOKEN" \
    AUTOMATELY_DATA_DIR="$data_dir" \
    AUTOMATELY_EGRESS_ALLOW_PRIVATE=true \
    AUTOMATELY_HTTP_PORT=8080 \
    AUTOMATELY_INGRESS_PORT=8000 \
    AUTOMATELY_METRICS_PORT=9090 \
    AUTOMATELY_WORKER_MODE=process \
    nohup "$(java_bin)" -jar "$SERVER_JAR" </dev/null >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  disown "$!" 2>/dev/null || true
  echo "   pid $(cat "$PID_FILE"), log $LOG_FILE" >&2

  local timeout="${START_TIMEOUT_SECONDS:-60}"
  local attempt
  for attempt in $(seq 1 "$timeout"); do
    if curl -fsS "${URL}/health" >/dev/null 2>&1; then
      echo "== up at ${URL} (health OK after ${attempt}s)" >&2
      return 0
    fi
    if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "Error: the server exited during startup; last log lines:" >&2
      tail -20 "$LOG_FILE" >&2 || true
      rm -f "$PID_FILE"
      exit 1
    fi
    sleep 1
  done
  echo "Error: the server did not answer ${URL}/health within ${timeout}s; see $LOG_FILE" >&2
  exit 1
}

# --------------------------------------------------------------------------
# stop
# --------------------------------------------------------------------------
cmd_stop() {
  if [[ ! -f "$PID_FILE" ]]; then
    echo "Not running (no $PID_FILE)" >&2
    return 0
  fi
  local pid
  pid=$(cat "$PID_FILE")
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "Not running (stale pid $pid)" >&2
    rm -f "$PID_FILE"
    return 0
  fi
  echo "== stopping pid $pid" >&2
  kill "$pid" 2>/dev/null || true
  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      echo "== stopped" >&2
      return 0
    fi
    sleep 1
  done
  echo "== still alive after 30s; sending SIGKILL" >&2
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

# --------------------------------------------------------------------------
# status
# --------------------------------------------------------------------------
cmd_status() {
  echo "-- health ($URL/health)"
  curl -sS -m 5 "${URL}/health" || echo "(unreachable)"
  echo
  echo "-- nodes ($URL/v1/nodes)"
  curl -sS -m 5 -H "Authorization: Bearer ${ADMIN_TOKEN}" "${URL}/v1/nodes" || echo "(unreachable)"
  echo
}

# --------------------------------------------------------------------------
# seed
# --------------------------------------------------------------------------
cmd_seed() {
  echo "== ensuring tenant 'dev'" >&2
  local created
  created=$(curl -sS -m 10 -X POST "${URL}/v1/tenants" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name":"dev"}')
  echo "   ${created}" >&2

  echo "== minting a token for 'dev'" >&2
  local minted token
  minted=$(curl -sS -m 10 -X POST "${URL}/v1/tenants/dev/tokens" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name":"dev-token","scopes":["*"]}')
  token=$(printf '%s' "$minted" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)
  if [[ -z "$token" ]]; then
    token=$(printf '%s' "$minted" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  fi
  if [[ -z "$token" ]]; then
    echo "Error: could not read a token from: $minted" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$CONFIG_FILE")"
  umask 077
  cat >"$CONFIG_FILE" <<JSON
{
  "url": "${URL}",
  "token": "${token}"
}
JSON
  echo "== saved the login to $CONFIG_FILE" >&2

  cat <<MCP

MCP configuration (endpoint ${MCP_URL}):

  Claude Code:
    claude mcp add --transport http automately ${MCP_URL} \\
      --header "Authorization: Bearer ${token}"

  Claude Desktop / Cursor / Antigravity (add to mcpServers):
    {
      "mcpServers": {
        "automately": {
          "type": "http",
          "url": "${MCP_URL}",
          "headers": { "Authorization": "Bearer ${token}" }
        }
      }
    }

  Clients that only launch local processes (stdio bridge):
    { "mcpServers": { "automately": { "command": "automately", "args": ["mcp"],
        "env": { "AUTOMATELY_URL": "${URL}", "AUTOMATELY_TOKEN": "${token}" } } } }

MCP
}

# --------------------------------------------------------------------------
# test-examples
# --------------------------------------------------------------------------
cmd_test_examples() {
  local cli
  cli=$(cli_bin)
  if [[ -z "$cli" ]]; then
    echo "Error: no automately CLI found. Run: scripts/dev.sh build" >&2
    exit 1
  fi
  local example
  for example in 01-hello-job 02-resilient-steps 03-kv-storage 04-event-bus 05-durable-queues 06-vfs-files; do
    echo "== running examples/${example}.js" >&2
    "$cli" run "examples/${example}.js"
    echo "== ${example}: OK" >&2
  done
  echo "== all examples passed" >&2
}

# --------------------------------------------------------------------------
# dispatch
# --------------------------------------------------------------------------
case "${1:-}" in
  build) cmd_build ;;
  start) shift; cmd_start "$@" ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  seed) cmd_seed ;;
  test-examples) cmd_test_examples ;;
  restart) cmd_stop; cmd_start ;;
  -h|--help|help|"") usage ;;
  *)
    echo "Error: unknown command '$1'" >&2
    usage >&2
    exit 2
    ;;
esac
