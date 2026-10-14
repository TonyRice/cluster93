#!/bin/sh
# Benchmarks on the three-node demo cluster (phase 5, milestone 3). Needs Docker, JDK 21, curl and python3.
# Builds the server, starts the cluster, runs every scenario, writes docs/benchmarks.md, and tears it all down.
set -eu
cd "$(dirname "$0")/../.."
OUT=docs/benchmarks.md
PROJECT=cluster93-bench
export C93_ADMIN_TOKEN="bench-admin-token-$(date +%s)-0123456789abcdef"
API=http://localhost:9401
compose() { docker compose -p "$PROJECT" -f docker-compose.yml -f benchmarks/cluster/compose-bench.yml "$@"; }
json() { python3 -I -c "import json,sys; print(json.load(sys.stdin)$1)"; }

echo "== building"
./mvnw -q -B -pl server -am package -DskipTests -DskipITs
ctx=$(mktemp -d)
mkdir -p "$ctx/server/target"
cp Dockerfile "$ctx/" && cp server/target/c93.jar "$ctx/server/target/"
docker build -q --target local-jar -t cluster93-bench:local "$ctx" >/dev/null
rm -rf "$ctx"

echo "== starting the cluster"
compose up -d >/dev/null 2>&1
trap 'compose down -v >/dev/null 2>&1 || true' EXIT
for port in 9401 9402 9403; do
    for i in $(seq 120); do curl -sf "localhost:$port/ready" >/dev/null && break; sleep 1; done
done
admin="Authorization: Bearer $C93_ADMIN_TOKEN"
curl -sf -X POST "$API/v1/tenants" -H "$admin" -d '{"name":"bench"}' >/dev/null
TOKEN=$(curl -sf -X POST "$API/v1/tenants/bench/tokens" -H "$admin" -d '{"name":"bench","scopes":["*"]}' | json "['token']")
auth="Authorization: Bearer $TOKEN"
# The ingress scenario sets a Host header, which the JDK's client only allows with this flag.
bench() { java -Djdk.httpclient.allowRestrictedHeaders=host benchmarks/cluster/Bench.java "$@" 2>&1; }

echo "== running"
rows=$(mktemp)
bench coldstart "$API" "$TOKEN" 100 | tee -a "$rows"
bench throughput "$API" "$TOKEN" 1 15 | tee -a "$rows"
bench throughput "$API" "$TOKEN" 32 20 | tee -a "$rows"

curl -sf -X PUT "$API/v1/services/echo" -H "$auth" \
    -d '{"script":"export default async (input, ctx) => { await ctx.serve(() => \"hello\"); return ctx.stopRequested; }"}' >/dev/null
for i in $(seq 60); do
    [ "$(curl -sf "$API/v1/services/echo" -H "$auth" | json "['status']")" = "RUNNING" ] && break; sleep 1
done
bench ingress "$API" "$TOKEN" http://localhost:9411 echo--bench.localhost 64 15 | tee -a "$rows"
bench files "$API" "$TOKEN" 256 | tee -a "$rows"

# Isolate density: idle services each hold one isolate; the nodes' resident memory before and after gives the cost of one.
curl -sf -X DELETE "$API/v1/services/echo" -H "$auth" >/dev/null
sleep 3
# The java process's resident memory (not the container's, which counts page cache), summed over the nodes.
mem() {
    total=0
    for node in $(docker ps --format '{{.Names}}' | grep "$PROJECT-node"); do
        kb=$(docker exec "$node" sh -c 'grep VmRSS /proc/$(pgrep -o java)/status' | awk '{print $2}')
        total=$((total + kb * 1024))
    done
    echo "$total"
}
before=$(mem)
# Each idle service holds one isolate slot: capacity 32 x 3 nodes.
ISOLATES=96
for i in $(seq $ISOLATES); do
    curl -sf -X PUT "$API/v1/services/idle-$i" -H "$auth" \
        -d '{"script":"export default async (input, ctx) => ctx.stopRequested"}' >/dev/null
done
for i in $(seq 120); do
    running=$(curl -sf "$API/v1/services" -H "$auth" | python3 -I -c "import json,sys; print(sum(1 for s in json.load(sys.stdin)['services'] if s['status']=='RUNNING'))")
    [ "$running" -ge "$ISOLATES" ] && break; sleep 1
done
sleep 10
after=$(mem)
python3 -I -c "print(f'| Isolate density | $running idle services on 3 nodes | {($after-$before)/$running/1048576:.1f} MB per isolate (all three nodes: {$before/1048576:.0f} MB -> {$after/1048576:.0f} MB) |')" | tee -a "$rows"

echo "== writing $OUT"
cpu=$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 | sed 's/^ //' || sysctl -n machdep.cpu.brand_string)
{
    echo "# Benchmarks"
    echo
    echo "> **Indicative, not a benchmark claim.** Single-machine numbers, kept as a rough sense of the"
    echo "> platform's shape. They are not a performance guarantee, and this page is not maintained: rerun"
    echo "> \`benchmarks/run.sh cluster\` on your own hardware for numbers that mean something to you."
    echo
    echo "Measured $(date -u +%Y-%m-%d) with \`benchmarks/run.sh cluster\`: the three-node demo cluster in Docker on one machine,"
    echo "driven from the same machine through Docker's port mapping."
    echo
    echo "- **Machine:** $cpu, $(nproc 2>/dev/null || sysctl -n hw.ncpu) CPUs, $(free -g 2>/dev/null | awk '/Mem:/ {print $2 " GB RAM"}')"
    echo "- **Software:** $(docker --version | sed 's/,.*//'), Cluster93 $(grep -m1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/'), JDK 21 (Temurin) in each container"
    echo "- **Cluster:** 3 nodes, capacity 32 isolates each, one backup copy of all data"
    echo
    echo "| Scenario | How | Result |"
    echo "|---|---|---|"
    cat "$rows"
    cat <<'EOF'

## Reading these numbers

- **One laptop runs everything.** The client and all three nodes share the same CPUs, so they compete. On the same
  machine on the same day, results varied by up to 2x between runs (32-client throughput 37 to 71 jobs/s, uploads
  45 to 60 MB/s). Compare runs on one machine rather than reading much into the absolute values.
- **Cold start is the whole round trip:** the HTTP submit, placement on a node, a fresh V8 isolate (isolates are never
  reused, see SECURITY.md), running the script, recording the result, and the job's event stream delivering its end.
  The client waits on that stream, as the CLI does, rather than polling.
- **Throughput doesn't scale with clients on one machine.** Where the time goes under load hasn't been profiled yet.
- **Uploads are bound by replicated writes.** Each 1 MB chunk is written to its primary and, synchronously, its backup
  before the next is read. Storing four chunks at once was tried and measured no faster (48 vs 45 MB/s), so it
  wasn't kept (D-059).
- **Density is the resident memory of the Java processes**, with a fixed, pre-touched Java heap so that growth is
  native memory. An idle isolate costs that much; a busy one can grow to `C93_MAX_HEAP_MB` (64 MB here).
  The node's capacity setting, not memory, limits how many isolates run.
EOF
} > "$OUT"
rm -f "$rows"
cat "$OUT"
