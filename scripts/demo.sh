#!/usr/bin/env bash
# The Automately demo (phase 5): an AI agent's tool calls deploy a queue-consuming service on a three-node cluster,
# orders flow in, the node running the service is killed, and the cluster recovers without losing an order.
#
#   scripts/demo.sh          build, start the cluster (with Prometheus and Grafana), run the demo, leave it running
#   scripts/demo.sh down     stop the cluster and delete its data
#
# Needs Docker, JDK 21, curl and python3. The "agent" is a scripted MCP client: the same JSON-RPC calls an agent like
# Claude makes over /mcp, so the demo runs the same way every time and needs no API key.
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT=automately-demo
ORDERS=${ORDERS:-120}
compose() { docker compose -p "$PROJECT" -f docker-compose.yml -f scripts/compose-demo.yml --profile observability "$@"; }

if [[ "${1:-}" == "down" ]]; then
    compose down -v
    exit 0
fi

bold=$'\e[1m'; dim=$'\e[2m'; green=$'\e[32m'; red=$'\e[31m'; cyan=$'\e[36m'; reset=$'\e[0m'
step() { printf '\n%s== %s%s\n' "$bold$cyan" "$*" "$reset"; }
say() { printf '   %s\n' "$*"; }
agent() { printf '   %s🤖 agent →%s %s\n' "$bold" "$reset" "$*"; }
ok() { printf '   %s✔ %s%s\n' "$green" "$*" "$reset"; }
json() { python3 -I -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
started=$(date +%s)
elapsed() { echo "$(( $(date +%s) - started ))s"; }

step "Building the server and the CLI"
./mvnw -q -B -pl automately-server,automately-cli -am package -DskipTests -DskipITs
context=$(mktemp -d)
mkdir -p "$context/automately-server/target"
cp Dockerfile "$context/" && cp automately-server/target/automately.jar "$context/automately-server/target/"
docker build -q --target local-jar -t automately-demo:local "$context" >/dev/null
rm -rf "$context"
ok "built ($(elapsed))"

step "Starting three nodes, Prometheus and Grafana"
export AUTOMATELY_ADMIN_TOKEN="demo-admin-$(date +%s)-0123456789abcdef0123456789"
compose up -d >/dev/null 2>&1
for port in 8081 8082 8083; do
    for _ in $(seq 120); do curl -sf "localhost:$port/ready" >/dev/null && break; sleep 1; done
done
ok "cluster ready ($(elapsed)): $(curl -sf -H "Authorization: Bearer $AUTOMATELY_ADMIN_TOKEN" localhost:8081/v1/nodes | json "', '.join(n['id'] for n in d['nodes'])")"

step "An operator creates a tenant and gives the agent a token"
API=http://localhost:8081
admin="Authorization: Bearer $AUTOMATELY_ADMIN_TOKEN"
curl -sf -X POST "$API/v1/tenants" -H "$admin" -d '{"name":"demo"}' >/dev/null
TOKEN=$(curl -sf -X POST "$API/v1/tenants/demo/tokens" -H "$admin" -d '{"name":"agent","scopes":["*"]}' | json "d['token']")
export AUTOMATELY_TOKEN=$TOKEN
cli() { AUTOMATELY_URL=$API java -jar automately-cli/target/automately-cli.jar "$@"; }
say "$(cli whoami)"

# --- the agent: JSON-RPC over MCP (Streamable HTTP), exactly what an MCP client sends ---
rpc_id=0
mcp() {
    rpc_id=$((rpc_id + 1))
    curl -sf "$API/mcp" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":$rpc_id,\"method\":\"$1\",\"params\":$2}"
}
tool() { mcp tools/call "{\"name\":\"$1\",\"arguments\":$2}"; }

step "The agent connects over MCP and looks around"
agent "initialize"
say "$(mcp initialize '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"demo-agent","version":"1"}}' \
    | json "'server: ' + d['result']['serverInfo']['title'] + ' ' + d['result']['serverInfo']['version']")"
agent "tools/list"
say "$(mcp tools/list '{}' | json "str(len(d['result']['tools'])) + ' tools: ' + ', '.join(t['name'] for t in d['result']['tools'][:8]) + ', …'")"

step "The agent deploys a service that works through an order queue"
read -r -d '' SERVICE <<'JS' || true
export default async (input, ctx) => {
  const node = (await jobs.get(ctx.jobId)).node;
  const done = kv.namespace('orders-done');
  await bus.queue('orders').consume(async (order, { attempt }) => {
    await new Promise((resolve) => setTimeout(resolve, 400));   // the work
    await done.set(`order-${order.id}`, { node, attempt });
    await kv.namespace('stats').increment('handled');
  }, { concurrency: 2 });
  await ctx.serve(async () => ({ body: { node, handled: await kv.namespace('stats').get('handled') } }));
  console.log(`orders service running on ${node}`);
  return ctx.stopRequested;
};
JS
printf '%s\n' "$SERVICE" | sed "s/^/   ${dim}│${reset} /"
agent "deploy_service orders"
tool deploy_service "$(python3 -I -c 'import json,sys; print(json.dumps({"name":"orders","script":sys.argv[1]}))' "$SERVICE")" \
    | json "'   hosts: ' + ', '.join(d['result']['structuredContent']['hosts'])"
for _ in $(seq 60); do
    [[ "$(curl -sf "$API/v1/services/orders" -H "Authorization: Bearer $TOKEN" | json "d['status']")" == RUNNING ]] && break; sleep 0.5
done
sleep 1

# The node a service runs on, and a node that's still up for the API and the ingress.
service_node() {
    local job; job=$(curl -sf "$API/v1/services/orders" -H "Authorization: Bearer $TOKEN" | json "d.get('currentJobId') or ''")
    [[ -n "$job" ]] && curl -sf "$API/v1/jobs/$job" -H "Authorization: Bearer $TOKEN" | json "d.get('node') or ''" || true
}
ingress() {
    for port in 8001 8002 8003; do
        curl -sf -m 2 -H 'Host: orders--demo.localhost' "localhost:$port/" 2>/dev/null && return 0
    done
    echo '{"node":"(no answer)","handled":null}'
}
distinct() {
    tool run_job "{\"script\":\"export default async () => (await kv.namespace('orders-done').list({ limit: 1000 })).keys.length\"}" \
        | json "d['result']['structuredContent']['output']"
}
NODE=$(service_node)
ok "orders is RUNNING on $bold$NODE$reset; the ingress says $(ingress)"

step "The agent pushes $ORDERS orders onto the durable queue"
agent "queue_push × $ORDERS"
for i in $(seq "$ORDERS"); do
    tool queue_push "{\"queue\":\"orders\",\"message\":{\"id\":$i,\"item\":\"widget\"},\"key\":\"order-$i\"}" >/dev/null
done
say "$(cli queues stats orders | head -1)"
say "$(cli queues stats orders | tail -1)   ${dim}(handled 2 at a time, 400 ms each)${reset}"
sleep 3
say "$(cli queues stats orders | tail -1)"

step "Chaos: SIGKILL the node running the service ($NODE)"
compose kill -s SIGKILL "$NODE" >/dev/null 2>&1
killed_at=$(date +%s)
printf '   %s✘ %s killed%s\n' "$red" "$NODE" "$reset"
case "$NODE" in node1) API=http://localhost:8082 ;; *) API=http://localhost:8081 ;; esac
say "the API and the ingress keep answering on the other nodes ($API)"

step "Recovery"
moved=""
while :; do
    sleep 2
    now=$(( $(date +%s) - killed_at ))
    stats=$(curl -sf "$API/v1/queues/orders" -H "Authorization: Bearer $TOKEN" | json "f\"ready {d['ready']:>3}  in flight {d['inFlight']}  dead {d['dead']}\"" || echo "…")
    serving=$(ingress | json "d['node']")
    say "+${now}s  queue: $stats   ingress → $serving"
    if [[ -z "$moved" && "$serving" != "$NODE" && "$serving" != "(no answer)" ]]; then
        moved=$now
        ok "the service restarted on $bold$serving$reset ${moved}s after the kill"
    fi
    if [[ -n "$moved" && "$stats" == "ready   0  in flight 0  dead 0" ]]; then
        break
    fi
    (( now > 120 )) && { printf '   %s✘ not recovered after 120 s%s\n' "$red" "$reset"; exit 1; }
done
handled=$(distinct)
attempts=$(ingress | json "d['handled']")
ok "every order handled: $handled of $ORDERS distinct, $(( $(date +%s) - killed_at ))s after the kill"
say "${dim}The orders in flight on $NODE came back when their 30 s leases ran out. Delivery is at least once:${reset}"
say "${dim}$attempts handler runs for $ORDERS orders, so each order is recorded by its id.${reset}"
[[ "$handled" == "$ORDERS" ]] || { printf '   %s✘ expected %s%s\n' "$red" "$ORDERS" "$reset"; exit 1; }

step "Bringing $NODE back"
compose start "$NODE" >/dev/null 2>&1
for _ in $(seq 60); do
    curl -sf "$API/v1/nodes" -H "Authorization: Bearer $TOKEN" | json "len(d['nodes'])" | grep -q 3 && break; sleep 1
done
ok "three nodes again: $(curl -sf "$API/v1/nodes" -H "Authorization: Bearer $TOKEN" | json "', '.join(n['id'] for n in d['nodes'])")"

step "Done in $(elapsed)"
say "Grafana:  http://localhost:3000   (the Automately dashboard: the kill, the restart, the queue draining)"
say "API:      $API   token: AUTOMATELY_TOKEN=$TOKEN"
say "Stop it:  scripts/demo.sh down"
