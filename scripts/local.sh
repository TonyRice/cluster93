#!/usr/bin/env bash
# Dedicated local master node installer and onboarding orchestrator for Cluster93.
#
# Runs a single permanent node on this machine: the API, ingress, worker, data and console roles
# in one process, with durable Apache Ignite persistence, a seeded tenant, a provisioned `admin`
# operator and its saved login, so `c93` works straight afterwards. It also tracks the git
# commit it was built from and tells you when the repository has moved ahead of the running build.
#
# Usage:
#   scripts/local.sh setup [--yes|-y]    Guided onboarding; writes ~/.cluster93/local.env,
#                                        builds what's missing, starts the node, seeds the
#                                        tenant, provisions the `admin` operator, saves its
#                                        login and prints the MCP config.
#   scripts/local.sh start               Start the configured node in the background.
#   scripts/local.sh run                 Run the configured node in the foreground.
#   scripts/local.sh seed                Ensure system/dev tenants, deploy console and admin operator.
#   scripts/local.sh stop                Stop it (SIGTERM, then SIGKILL after 30s).
#   scripts/local.sh restart             stop, then start.
#   scripts/local.sh status              PID, /health, /v1/nodes, and update detection.
#   scripts/local.sh logs [-f] [-n N]    Tail the server log (`-f` follows).
#   scripts/local.sh mcp                 Print MCP configurations for common agents.
#   scripts/local.sh update              Rebuild from the current checkout and restart.
#   scripts/local.sh help                This text.
#
# Environment:
#   C93_HOME        where state lives (default: $HOME/.cluster93)
#   C93_JAVA        the `java` used to run the server jar (default: JAVA_HOME/bin/java, then java)
#   START_TIMEOUT_SECONDS  how long start/seed wait for /health (default: 60)
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

C93_HOME="${C93_HOME:-${HOME}/.cluster93}"
ENV_FILE="${C93_HOME}/local.env"
META_FILE="${C93_HOME}/node.meta"
PID_FILE="${C93_HOME}/c93.pid"
MCP_FILE="${C93_HOME}/mcp.json"
CONFIG_FILE="${C93_CONFIG_DIR:-${XDG_CONFIG_HOME:-${HOME}/.config}/cluster93}/config.json"
SERVER_JAR="${REPO_ROOT}/server/target/c93.jar"
JAVET_WORKER="${REPO_ROOT}/bin/worker-javet"
GRAALJS_WORKER="${REPO_ROOT}/bin/worker-graaljs"
CLI_BINARY="${REPO_ROOT}/bin/c93"
START_TIMEOUT_SECONDS="${START_TIMEOUT_SECONDS:-60}"

# Defaults the onboarding wizard offers.
DEFAULT_NODE_ID="local-master"
DEFAULT_ROLES="api,ingress,worker,data,console"
# The permanent master keeps its own store, separate from the ephemeral directory scripts/dev.sh uses,
# so an Ignite baseline written by development testing can never collide with the master's database.
DEFAULT_DATA_DIR="${C93_HOME}/master-data"
DEFAULT_LOG_DIR="${C93_HOME}/logs"
DEFAULT_HTTP_PORT="9393"
DEFAULT_INGRESS_PORT="9394"
DEFAULT_METRICS_PORT="9395"
DEFAULT_WORKER_MODE="process"
DEFAULT_WORKER_ENGINE="graaljs"
DEFAULT_TENANT="local"

# Apache Ignite 2.18 on JDK 21 needs these module opens (pom.xml's ignite.jvm.args, the
# Dockerfile's JDK_JAVA_OPTIONS). Without them the server fails at startup with
# InaccessibleObjectException.
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
  if [[ -n "${C93_JAVA:-}" ]]; then
    echo "$C93_JAVA"
  elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    echo "$JAVA_HOME/bin/java"
  else
    command -v java
  fi
}

# --------------------------------------------------------------------------
# configuration
# --------------------------------------------------------------------------
# The wizard's answer: the given value, or the default when running non-interactively
# or with --yes. Prompts go to stderr so command substitutions stay clean.
ask() {
  local prompt="$1" default="$2" answer
  if [[ "${ASSUME_YES:-false}" == "true" || ! -t 0 ]]; then
    printf '%s' "$default"
    return
  fi
  read -r -p "  ${prompt} [${default}]: " answer
  printf '%s' "${answer:-$default}"
}

generate_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

generate_password() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16
  else
    head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# A password for console login: prompted for on a terminal, else generated (and shown, since nobody typed it).
ask_password() {
  local prompt="$1" answer
  if [[ "${ASSUME_YES:-false}" == "true" || ! -t 0 ]]; then
    answer=$(generate_password)
    echo "   generated an admin password: ${answer}" >&2
    printf '%s' "$answer"
    return
  fi
  IFS= read -r -s -p "  ${prompt} [empty generates one]: " answer
  echo >&2
  if [[ -z "$answer" ]]; then
    answer=$(generate_password)
    echo "   generated an admin password: ${answer}" >&2
  fi
  printf '%s' "$answer"
}

# JSON-encodes a string (with surrounding quotes), so it can be embedded in a request body.
json_escape() {
  local value="$1"
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$value" | python3 -c 'import json,sys; sys.stdout.write(json.dumps(sys.stdin.read().rstrip("\n")))'
  else
    printf '%s' "$value" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
  fi
}

load_env_file() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "Error: no configuration at $ENV_FILE. Run: scripts/local.sh setup" >&2
    exit 1
  fi
  # REPO_ROOT always follows this script's own location. The copy persisted in local.env is there so the
  # `c93 local` CLI can recover the checkout when it runs from elsewhere, so it must not override it.
  local script_repo_root="$REPO_ROOT"
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  REPO_ROOT="$script_repo_root"
  : "${NODE_ID:=$DEFAULT_NODE_ID}"
  : "${ROLES:=$DEFAULT_ROLES}"
  : "${DATA_DIR:=$DEFAULT_DATA_DIR}"
  : "${LOG_DIR:=$DEFAULT_LOG_DIR}"
  : "${HTTP_PORT:=$DEFAULT_HTTP_PORT}"
  : "${INGRESS_PORT:=$DEFAULT_INGRESS_PORT}"
  : "${METRICS_PORT:=$DEFAULT_METRICS_PORT}"
  : "${WORKER_MODE:=$DEFAULT_WORKER_MODE}"
  : "${DEFAULT_WORKER_ENGINE:=graaljs}"
  : "${WORKER_ENGINE:=$DEFAULT_WORKER_ENGINE}"
  : "${ADMIN_TOKEN:=}"
  : "${TENANT:=$DEFAULT_TENANT}"
  URL="http://localhost:${HTTP_PORT}"
  LOG_FILE="${LOG_DIR}/server.log"
}

write_env_file() {
  mkdir -p "$C93_HOME"
  umask 077
  cat >"$ENV_FILE" <<ENV
# Cluster93 local master node configuration (scripts/local.sh setup).
# Read by scripts/local.sh (start, status, mcp) and c93 local ...
REPO_ROOT="${REPO_ROOT}"
NODE_ID="${NODE_ID}"
ROLES="${ROLES}"
DATA_DIR="${DATA_DIR}"
LOG_DIR="${LOG_DIR}"
HTTP_PORT="${HTTP_PORT}"
INGRESS_PORT="${INGRESS_PORT}"
METRICS_PORT="${METRICS_PORT}"
WORKER_MODE="${WORKER_MODE}"
DEFAULT_WORKER_ENGINE="${DEFAULT_WORKER_ENGINE}"
WORKER_ENGINE="${WORKER_ENGINE}"
ADMIN_TOKEN="${ADMIN_TOKEN}"
TENANT="${TENANT}"
ENV
  chmod 600 "$ENV_FILE"
}

project_version() {
  sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$REPO_ROOT/pom.xml" | head -1
}

write_meta() {
  mkdir -p "$C93_HOME"
  local commit branch version
  commit=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo "unknown")
  branch=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
  version=$(project_version)
  [[ -n "$version" ]] || version="unknown"
  umask 077
  cat >"$META_FILE" <<META
installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
git_commit=${commit}
git_branch=${branch}
version=${version}
repo_root=${REPO_ROOT}
server_jar=${SERVER_JAR}
cli_binary=${CLI_BINARY}
worker_javet=${JAVET_WORKER}
worker_graaljs=${GRAALJS_WORKER}
META
  chmod 600 "$META_FILE"
}

# --------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------
ensure_build() {
  if [[ ! -f "$SERVER_JAR" ]]; then
    echo "== building server/target/c93.jar" >&2
    "$REPO_ROOT/mvnw" -q package -pl server -am -DskipTests
  fi
  if [[ ! -x "$CLI_BINARY" ]]; then
    echo "== building the native c93 CLI" >&2
    if ! "$REPO_ROOT/scripts/build-cli.sh"; then
      echo "   (native CLI build skipped; 'c93 local' works from the jar too)" >&2
    fi
  fi
  if [[ ! -x "$JAVET_WORKER" || ! -x "$GRAALJS_WORKER" ]]; then
    echo "== building bin/worker-javet and bin/worker-graaljs" >&2
    if ! "$REPO_ROOT/scripts/build-workers.sh" all; then
      echo "   (worker build skipped; process mode falls back to a child JVM)" >&2
    fi
  fi
  if [[ ! -f "$SERVER_JAR" ]]; then
    echo "Error: $SERVER_JAR was not produced" >&2
    exit 1
  fi
}

# --------------------------------------------------------------------------
# setup (onboarding wizard)
# --------------------------------------------------------------------------
cmd_setup() {
  local assume_yes=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -y|--yes) assume_yes=true; shift ;;
      *) echo "Error: unknown argument '$1'" >&2; exit 2 ;;
    esac
  done
  ASSUME_YES="$assume_yes"
  export ASSUME_YES

  echo "== Cluster93 local master node setup" >&2
  if [[ "$assume_yes" == "true" || ! -t 0 ]]; then
    echo "   using defaults (non-interactive)" >&2
  fi

  NODE_ID=$(ask "Node ID" "$DEFAULT_NODE_ID")
  ROLES=$(ask "Roles (api,ingress,worker,data,console)" "$DEFAULT_ROLES")
  DATA_DIR=$(ask "Data directory" "$DEFAULT_DATA_DIR")
  LOG_DIR=$(ask "Log directory" "$DEFAULT_LOG_DIR")
  HTTP_PORT=$(ask "HTTP/API port" "$DEFAULT_HTTP_PORT")
  INGRESS_PORT=$(ask "Ingress port" "$DEFAULT_INGRESS_PORT")
  METRICS_PORT=$(ask "Metrics port" "$DEFAULT_METRICS_PORT")
  WORKER_MODE=$(ask "Worker mode (process/embedded)" "$DEFAULT_WORKER_MODE")
  WORKER_ENGINE="${WORKER_ENGINE:-$DEFAULT_WORKER_ENGINE}"
  ADMIN_TOKEN=$(ask "Admin token (empty generates one)" "")
  if [[ -z "$ADMIN_TOKEN" ]]; then
    ADMIN_TOKEN=$(generate_token)
    echo "   generated a 32-byte admin token" >&2
  fi
  TENANT=$(ask "Tenant name" "$DEFAULT_TENANT")

  write_env_file
  echo "== wrote $ENV_FILE (0600)" >&2

  ensure_build
  write_meta
  echo "== wrote $META_FILE" >&2

  cmd_start
  cmd_seed
  write_mcp_file
  echo "== setup complete: $URL" >&2
  cmd_mcp
}

write_mcp_file() {
  local token="${1:-}"
  if [[ -z "$token" ]]; then
    token=$(saved_url_and_token | sed -n 's/^TOKEN=//p')
  fi
  local url="${URL}"
  mkdir -p "$C93_HOME"
  umask 077
  cat >"$MCP_FILE" <<JSON
{
  "mcpServers": {
    "c93": {
      "type": "http",
      "url": "${url}/mcp",
      "headers": { "Authorization": "Bearer ${token}" }
    }
  }
}
JSON
  chmod 600 "$MCP_FILE"
  echo "== wrote $MCP_FILE" >&2
}

# Reads the saved login and prints URL=... and TOKEN=... on separate lines.
saved_url_and_token() {
  if [[ ! -f "$CONFIG_FILE" ]]; then
    return 0
  fi
  python3 - "$CONFIG_FILE" <<'PY' 2>/dev/null || true
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
print("URL=" + str(d.get("url", "")))
print("TOKEN=" + str(d.get("token", "")))
PY
}

# --------------------------------------------------------------------------
# start
# --------------------------------------------------------------------------
cmd_start() {
  load_env_file
  if [[ ! -f "$SERVER_JAR" ]]; then
    echo "Error: $SERVER_JAR is missing. Run: scripts/local.sh setup" >&2
    exit 1
  fi
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Already running (pid $(cat "$PID_FILE"))." >&2
    return 0
  fi

  mkdir -p "$C93_HOME" "$DATA_DIR" "$LOG_DIR"
  echo "== starting the local master node (data: $DATA_DIR)" >&2
  setsid env \
    JDK_JAVA_OPTIONS="$IGNITE_JVM_ARGS" \
    C93_NODE_ID="$NODE_ID" \
    C93_ROLES="$ROLES" \
    C93_CLUSTERED=true \
    C93_CLUSTER_SIZE=1 \
    C93_CLUSTER_MEMBERS=127.0.0.1 \
    C93_CLUSTER_TLS=off \
    C93_ADMIN_TOKEN="$ADMIN_TOKEN" \
    C93_DATA_DIR="$DATA_DIR" \
    C93_EGRESS_ALLOW_PRIVATE=true \
    C93_HTTP_PORT="$HTTP_PORT" \
    C93_INGRESS_PORT="$INGRESS_PORT" \
    C93_METRICS_PORT="$METRICS_PORT" \
    C93_WORKER_MODE="$WORKER_MODE" \
    C93_WORKER_DEFAULT_MODE="$WORKER_ENGINE" \
    "$(java_bin)" -jar "$SERVER_JAR" </dev/null >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  disown "$!" 2>/dev/null || true
  echo "   pid $(cat "$PID_FILE"), log $LOG_FILE" >&2

  local attempt
  for attempt in $(seq 1 "$START_TIMEOUT_SECONDS"); do
    if curl -fsS "${URL}/health" >/dev/null 2>&1; then
      echo "== up at ${URL} (health OK after ${attempt}s)" >&2
      ensure_console || true
      echo "== web console: http://localhost:${INGRESS_PORT}" >&2
      return 0
    fi
    if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "Error: the node exited during startup; last log lines:" >&2
      tail -20 "$LOG_FILE" >&2 || true
      rm -f "$PID_FILE"
      exit 1
    fi
    sleep 1
  done
  echo "Error: the node did not answer ${URL}/health within ${START_TIMEOUT_SECONDS}s; see $LOG_FILE" >&2
  exit 1
}

# --------------------------------------------------------------------------
# run
# --------------------------------------------------------------------------
# Runs the configured node in the foreground (for process supervisors and daemon
# task runners) instead of backgrounding it like cmd_start. `exec` replaces this
# shell with the JVM, so the PID written to $PID_FILE stays the running server.
cmd_run() {
  load_env_file
  if [[ ! -f "$SERVER_JAR" ]]; then
    echo "Error: $SERVER_JAR is missing. Run: scripts/local.sh setup" >&2
    exit 1
  fi

  mkdir -p "$C93_HOME" "$DATA_DIR" "$LOG_DIR"
  echo $$ >"$PID_FILE"
  echo "== running the local master node in the foreground (data: $DATA_DIR)" >&2
  exec env \
    JDK_JAVA_OPTIONS="$IGNITE_JVM_ARGS" \
    C93_NODE_ID="$NODE_ID" \
    C93_ROLES="$ROLES" \
    C93_CLUSTERED=true \
    C93_CLUSTER_SIZE=1 \
    C93_CLUSTER_MEMBERS=127.0.0.1 \
    C93_CLUSTER_TLS=off \
    C93_ADMIN_TOKEN="$ADMIN_TOKEN" \
    C93_DATA_DIR="$DATA_DIR" \
    C93_EGRESS_ALLOW_PRIVATE=true \
    C93_HTTP_PORT="$HTTP_PORT" \
    C93_INGRESS_PORT="$INGRESS_PORT" \
    C93_METRICS_PORT="$METRICS_PORT" \
    C93_WORKER_MODE="$WORKER_MODE" \
    C93_WORKER_DEFAULT_MODE="$WORKER_ENGINE" \
    "$(java_bin)" -jar "$SERVER_JAR"
}

# --------------------------------------------------------------------------
# seed
# --------------------------------------------------------------------------
# The token inside a mint response: the JSON "token" field, parsed by python3 when available, else with sed.
mint_token() {
  local response="$1" token
  token=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)
  if [[ -z "$token" ]]; then
    token=$(printf '%s' "$response" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  fi
  printf '%s' "$token"
}

# A top-level JSON string field, parsed by python3 when available, else with sed.
json_string() {
  local response="$1" field="$2" value
  value=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1],""))' "$field" 2>/dev/null || true)
  if [[ -z "$value" ]]; then
    value=$(printf '%s' "$response" | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p")
  fi
  printf '%s' "$value"
}

# The personal token inside a redeem response: the mint nests the secret at token.token.
redeem_token() {
  local response="$1" token
  token=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",{}).get("token",""))' 2>/dev/null || true)
  if [[ -z "$token" ]]; then
    token=$(printf '%s' "$response" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*{[^}]*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  fi
  printf '%s' "$token"
}

# Provisions the default 'admin' operator: an operator invite minted with the admin token, redeemed as 'admin'
# with a console password. Prints the operator personal token; reuses the saved one if 'admin' already exists.
provision_admin() {
  echo "== provisioning the 'admin' operator" >&2
  local invited code redeemed token admin_password body
  invited=$(curl -sS -m 10 -X POST "${URL}/v1/admin/invites" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"note":"local master operator"}')
  code=$(json_string "$invited" code)
  if [[ -z "$code" ]]; then
    echo "   (no operator invite: ${invited})" >&2
    saved_operator_token
    return 0
  fi
  admin_password=$(ask_password "Admin password for console")
  body=$(printf '{"code":%s,"name":"admin","tokenName":"operator","password":%s}' \
    "$(json_escape "$code")" "$(json_escape "$admin_password")")
  redeemed=$(curl -sS -m 10 -X POST "${URL}/v1/invites/redeem" \
    -H "Content-Type: application/json" \
    -d "$body")
  token=$(redeem_token "$redeemed")
  if [[ -z "$token" ]]; then
    echo "   ('admin' already exists; reusing the saved operator token)" >&2
    saved_operator_token
    return 0
  fi
  printf '%s' "$token"
}

# The operator token in the saved login, when it still identifies as the 'admin' operator.
saved_operator_token() {
  local saved token who user
  saved=$(saved_url_and_token || true)
  token=$(printf '%s\n' "$saved" | sed -n 's/^TOKEN=//p')
  [[ -n "$token" ]] || return 0
  who=$(curl -sS -m 5 -H "Authorization: Bearer ${token}" "${URL}/v1/whoami" 2>/dev/null || true)
  user=$(json_string "$who" user)
  if [[ "$user" == "admin" ]]; then
    printf '%s' "$token"
  fi
}

# The setup completion banner: where the console and API are, and who the CLI now is.
print_setup_banner() {
  local token="$1"
  cat >&2 <<BANNER

================================================================
  Cluster93 local master is ready
================================================================
  Web Console URL: http://localhost:${INGRESS_PORT}
  API URL:         http://localhost:${HTTP_PORT}
  Operator User:   admin
  Token:           ${token}
================================================================
  Saved to ${CONFIG_FILE}; 'c93' now uses the operator login.
BANNER
}

# Publishes the console SPA to system/console with the installed CLI, falling back to the shaded jar.
deploy_console() {
  local token="$1"
  if [[ -x "$CLI_BINARY" ]]; then
    "$CLI_BINARY" --url "$URL" --token "$token" apps deploy "$REPO_ROOT/console"
  elif [[ -f "$REPO_ROOT/cli/target/cluster93-cli.jar" ]]; then
    "$(java_bin)" -jar "$REPO_ROOT/cli/target/cluster93-cli.jar" \
      --url "$URL" --token "$token" apps deploy "$REPO_ROOT/console"
  else
    echo "   (no c93 CLI built; skipping the console deploy)" >&2
    return 1
  fi
}

# Ensures the system tenant exists and the console SPA is deployed to system/console, idempotently. Safe to call
# after every start: it rebuilds only when needed and skips redeploying while the console already answers.
ensure_console() {
  echo "== ensuring system tenant 'system'" >&2
  curl -sS -m 10 -X POST "${URL}/v1/tenants" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name":"system"}' >/dev/null || true

  # A console-role node serves the web console at system/console; deploy it when the SPA is built and a CLI exists.
  if [[ ",${ROLES}," == *",console,"* ]] && [[ -d "$REPO_ROOT/console" ]]; then
    if [[ ! -f "$REPO_ROOT/console/out/index.html" ]] && command -v npm >/dev/null 2>&1; then
      echo "== building the console SPA (npm run build)" >&2
      (cd "$REPO_ROOT/console" && npm run build) || echo "   (console build failed; skipping the console deploy)" >&2
    fi
    if [[ -f "$REPO_ROOT/console/out/index.html" ]]; then
      # Skip the redeploy when the console is already being served.
      if curl -fsS -m 2 "http://localhost:${INGRESS_PORT}" 2>/dev/null | grep -q "Cluster93" 2>/dev/null; then
        echo "== console already active at http://localhost:${INGRESS_PORT}" >&2
        return 0
      fi
      echo "== minting a token for 'system'" >&2
      local system_minted system_token
      system_minted=$(curl -sS -m 10 -X POST "${URL}/v1/tenants/system/tokens" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"name":"console-deploy","scopes":["*"]}' || true)
      system_token=$(mint_token "$system_minted")
      if [[ -n "$system_token" ]] && deploy_console "$system_token"; then
        echo "== console app deployed to system/console: http://localhost:${INGRESS_PORT}" >&2
      fi
    fi
  fi
}

cmd_seed() {
  load_env_file

  ensure_console

  # The CLI's default identity is the operator personal token; it is what the console and MCP also use.
  local operator_token
  operator_token=$(provision_admin)
  if [[ -z "$operator_token" ]]; then
    echo "Error: couldn't provision the 'admin' operator (is the admin token right?)" >&2
    exit 1
  fi

  echo "== ensuring tenant '${TENANT}'" >&2
  curl -sS -m 10 -X POST "${URL}/v1/tenants" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${TENANT}\"}" >/dev/null || true

  mkdir -p "$(dirname "$CONFIG_FILE")"
  umask 077
  cat >"$CONFIG_FILE" <<JSON
{
  "url": "${URL}",
  "token": "${operator_token}"
}
JSON
  chmod 600 "$CONFIG_FILE"
  echo "== saved the operator login to $CONFIG_FILE" >&2

  print_setup_banner "$operator_token"
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
update_notice() {
  [[ -f "$META_FILE" ]] || return 0
  local installed head branch
  installed=$(sed -n 's/^git_commit=//p' "$META_FILE" | head -1)
  [[ -n "$installed" ]] || return 0
  head=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || true)
  [[ -n "$head" ]] || return 0
  if [[ "$installed" != "$head" ]]; then
    branch=$(sed -n 's/^git_branch=//p' "$META_FILE" | head -1)
    printf '\n\342\232\240\357\270\217  Update Available! Running build: %s, Current repo HEAD: %s. Run '"'"'scripts/local.sh update'"'"' or '"'"'c93 local update'"'"'.\n' \
      "$installed" "$head"
    [[ -n "$branch" ]] && printf '   (installed from %s; the checkout has moved on)\n' "$branch"
  fi
}

cmd_status() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "No local master node is set up."
    echo "Run: scripts/local.sh setup"
    return 0
  fi
  load_env_file

  local pid="" running="no" health="unreachable" nodes="(unreachable)"
  if [[ -f "$PID_FILE" ]]; then
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
      running="yes"
    fi
  fi

  if curl -fsS -m 5 "${URL}/health" >/dev/null 2>&1; then
    health="ok"
  fi
  if [[ -n "$ADMIN_TOKEN" ]]; then
    local answer
    answer=$(curl -sS -m 5 -H "Authorization: Bearer ${ADMIN_TOKEN}" "${URL}/v1/nodes" 2>/dev/null || true)
    if [[ -n "$answer" ]]; then
      nodes=$(printf '%s' "$answer" | python3 -c 'import json,sys; print(", ".join(n.get("id","?") for n in json.load(sys.stdin).get("nodes",[])))' 2>/dev/null || echo "ok")
    fi
  fi

  printf 'Local master node\n'
  printf '  Node ID:        %s\n' "$NODE_ID"
  printf '  Roles:          %s\n' "$ROLES"
  printf '  Data directory: %s\n' "$DATA_DIR"
  printf '  Log directory:  %s\n' "$LOG_DIR"
  printf '  API:            %s\n' "$URL"
  printf '  Ingress:        http://localhost:%s\n' "$INGRESS_PORT"
  printf '  Worker mode:    %s\n' "$WORKER_MODE"
  if [[ -n "$pid" ]]; then
    printf '  PID:            %s (%s)\n' "$pid" "$running"
  else
    printf '  PID:            (not running)\n'
  fi
  printf '  health:         %s\n' "$health"
  printf '  nodes:          %s\n' "$nodes"
  update_notice
}

# --------------------------------------------------------------------------
# logs
# --------------------------------------------------------------------------
cmd_logs() {
  load_env_file
  local lines=50 follow=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -f|--follow) follow=true; shift ;;
      -n|--lines)
        [[ -n "${2:-}" ]] || { echo "Error: --lines needs a number" >&2; exit 2; }
        lines="$2"; shift 2 ;;
      *) echo "Error: unknown argument '$1'" >&2; exit 2 ;;
    esac
  done
  if [[ ! -f "$LOG_FILE" ]]; then
    echo "No log file at $LOG_FILE (is the node started?)" >&2
    exit 1
  fi
  if [[ "$follow" == "true" ]]; then
    tail -n "$lines" -f "$LOG_FILE"
  else
    tail -n "$lines" "$LOG_FILE"
  fi
}

# --------------------------------------------------------------------------
# mcp
# --------------------------------------------------------------------------
cmd_mcp() {
  if [[ -f "$MCP_FILE" ]]; then
    cat "$MCP_FILE"
    return 0
  fi
  load_env_file
  local token="" url="$URL"
  local saved
  saved=$(saved_url_and_token || true)
  if [[ -n "$saved" ]]; then
    token=$(printf '%s\n' "$saved" | sed -n 's/^TOKEN=//p')
    [[ -n "$token" ]] && url=$(printf '%s\n' "$saved" | sed -n 's/^URL=//p')
  fi
  cat <<MCP
MCP configuration (endpoint ${url}/mcp):

  Claude Code:
    claude mcp add --transport http c93 ${url}/mcp \\
      --header "Authorization: Bearer ${token}"

  Claude Desktop / Cursor / Antigravity (add to mcpServers):
    {
      "mcpServers": {
        "c93": {
          "type": "http",
          "url": "${url}/mcp",
          "headers": { "Authorization": "Bearer ${token}" }
        }
      }
    }

  Clients that only launch local processes (stdio bridge):
    { "mcpServers": { "c93": { "command": "c93", "args": ["mcp"],
        "env": { "C93_URL": "${url}", "C93_TOKEN": "${token}" } } } }

MCP
}

# --------------------------------------------------------------------------
# update
# --------------------------------------------------------------------------
# True when a native c93 CLI was built before (installed in the checkout, left in target/, or in ~/.local/bin).
native_cli_was_built() {
  [[ -x "$CLI_BINARY" ]] || [[ -e "$REPO_ROOT/cli/target/c93" ]] || [[ -e "${HOME}/.local/bin/c93" ]]
}

cmd_update() {
  echo "== rebuilding from $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)" >&2
  "$REPO_ROOT/mvnw" -q package -pl server -am -DskipTests
  "$REPO_ROOT/scripts/build-workers.sh" all || true
  # Refresh the native CLI binary when it was built before, so its embedded commit tracks the checkout.
  if native_cli_was_built; then
    echo "== rebuilding the native c93 CLI" >&2
    "$REPO_ROOT/scripts/build-cli.sh" || echo "   (native CLI rebuild skipped)" >&2
  fi
  # Rebuild and redeploy the web console too, so an update is complete for the browser UI as well.
  if [[ -f "$ENV_FILE" ]]; then
    load_env_file
    ensure_console || true
  fi
  write_meta
  echo "== updated $META_FILE" >&2
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    cmd_stop
    cmd_start
  else
    echo "== the node is not running; start it with: scripts/local.sh start" >&2
  fi
}

# --------------------------------------------------------------------------
# dispatch
# --------------------------------------------------------------------------
case "${1:-}" in
  setup) shift; cmd_setup "$@" ;;
  start) cmd_start ;;
  run) cmd_run ;;
  seed) cmd_seed ;;
  stop) cmd_stop ;;
  restart) cmd_stop; cmd_start ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  mcp) cmd_mcp ;;
  update) cmd_update ;;
  -h|--help|help|"") usage ;;
  *)
    echo "Error: unknown command '$1'" >&2
    usage >&2
    exit 2
    ;;
esac
