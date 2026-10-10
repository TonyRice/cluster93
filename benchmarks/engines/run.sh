#!/usr/bin/env bash
# The engine comparison (D-126 §8): every workload on each engine, fully warmed, in native-image workers; then memory
# with idle services, start-up, and the hostile memory tests under a cgroup cap. Writes results/<date>/results.jsonl
# and summary.md. Run build.sh first. Takes about an hour; keep the machine otherwise idle.
#   WARMUP_MS (20000), WARMUP_MIN (200), MEASURED (50), BENCH_HEAP_MB (512), CAP (512M), RUN_CAP (4G),
#   ENGINES ("javet graaljs graaljs-in-process")
# The workloads run with a 512 MB heap limit on both engines: they measure speed, and GraalJS's limit counts every byte
# (buffers too) and trips on garbage it hasn't collected yet (buffer-codec fails at 128 MB, crawls at 192). Start-up and
# idle memory use the default 128 MB; the hostile tests 64 MB.
# Every worker runs under a kernel memory cap: CAP for the hostile tests, RUN_CAP for the rest, so a run that misbehaves
# is the only thing that dies. Start-up is timed from the worker's own process start, so the cap's wrapper isn't in it.
set -uo pipefail
cd "$(dirname "$0")"
BIN=bin
WARMUP_MS=${WARMUP_MS:-20000}
WARMUP_MIN=${WARMUP_MIN:-200}
MEASURED=${MEASURED:-50}
BENCH_HEAP_MB=${BENCH_HEAP_MB:-512}
CAP=${CAP:-512M}
RUN_CAP=${RUN_CAP:-4G}
ENGINES=${ENGINES:-"javet graaljs graaljs-in-process"}
OUT=results/$(date +%Y-%m-%d-%H%M)
mkdir -p "$OUT/workloads"
RESULTS="$OUT/results.jsonl"

# CPU temperature and clock every 5 s, so throttling shows in the results (thermal.csv; summarized at the end).
(
  echo "epoch,packageC,mhzMean,mhzMin"
  while true; do
    temp=$(sensors 2>/dev/null | awk '/^Package id 0:/{gsub(/[+°C]/,"",$4); print $4; exit}')
    mhz=$(awk '/^cpu MHz/{s+=$4; n++; if(min==""||$4<min)min=$4} END{printf "%.0f,%.0f", s/n, min}' /proc/cpuinfo)
    echo "$(date +%s),${temp:-},$mhz"
    sleep 5
  done
) > "$OUT/thermal.csv" &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT

# A worker that's killed leaves Javet's unpacked V8 library and an ICU data copy in /tmp, which may be in memory:
# remove whatever no live process holds, after every run.
tidy() {
  for d in /tmp/javet/*; do
    [[ -d "$d" ]] && ! kill -0 "$(basename "$d")" 2>/dev/null && rm -rf "$d"
  done
  for f in /tmp/automately-icudt*.dat; do
    [[ -f "$f" ]] && ! fuser -s "$f" 2>/dev/null && rm -f "$f"
  done
  return 0
}

capped() {  # cap, command... : runs the command under a kernel memory cap, with no swap
  local cap=$1; shift
  systemd-run --user --scope --quiet -p MemoryMax="$cap" -p MemorySwapMax=0 "$@"
}

worker() {  # engine name -> the binary and its options
  case "$1" in
    javet) echo "$BIN/worker-javet" ;;
    graaljs) echo "$BIN/worker-graaljs" ;;
    graaljs-in-process) echo "$BIN/worker-graaljs -Dautomately.graaljs=in-process" ;;
  esac
}

# Octane's workloads need its stub and their vendored source in front of them.
for w in workloads/*.js; do
  name=$(basename "$w")
  case "$name" in
    richards.js|deltablue.js) cat workloads/vendor/octane-stub.js "workloads/vendor/$name" "$w" > "$OUT/workloads/$name" ;;
    *) cp "$w" "$OUT/workloads/$name" ;;
  esac
done

{
  cpu=$(lscpu | sed -n 's/^Model name: *//p' | head -1)
  turbo=$(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null | sed 's/1/off/;s/0/on/')
  profile=$(cat /sys/firmware/acpi/platform_profile 2>/dev/null)
  echo "{\"machine\":\"$(uname -srm)\",\"cpu\":\"$cpu\",\"turbo\":\"${turbo:-unknown}\",\"powerProfile\":\"${profile:-unknown}\",\"cpus\":$(nproc),\"memGb\":$(free -g | awk '/Mem:/{print $2}'),\"date\":\"$(date -Is)\",\"warmupMs\":$WARMUP_MS,\"warmupMin\":$WARMUP_MIN,\"measured\":$MEASURED}"
} > "$OUT/machine.json"

for engine in $ENGINES; do
  read -r -a cmd <<< "$(worker "$engine")"
  echo "== $engine: start-up and idle memory" >&2
  for i in 1 2 3; do capped "$RUN_CAP" "${cmd[@]}" start 2>/dev/null | tail -1 >> "$RESULTS"; done
  # A run holds one of the runtime's threads for its life, so idle services need a thread each.
  for n in 0 20 50; do capped "$RUN_CAP" "${cmd[@]}" idle "$n" --threads $((n + 2)) 2>/dev/null | tail -1 >> "$RESULTS"; done
  tidy
  for w in "$OUT"/workloads/*.js; do
    echo "== $engine: $(basename "$w")" >&2
    line=$(capped "$RUN_CAP" "${cmd[@]}" bench "$w" --warmup-ms "$WARMUP_MS" --warmup-min "$WARMUP_MIN" --measured "$MEASURED" --heap-mb "$BENCH_HEAP_MB" 2>/dev/null | grep '^{' | tail -1)
    if [[ -z "$line" ]]; then
      line="{\"engine\":\"$engine\",\"workload\":\"$(basename "$w" .js)\",\"status\":\"FAILED\",\"error\":\"no result: the worker crashed or hit its $RUN_CAP cap\"}"
    fi
    echo "$line" >> "$RESULTS"
    tidy
  done
done

# The hostile memory tests, each under a kernel memory cap: what stops it, or whether the kernel has to.
for engine in javet graaljs; do
  read -r -a cmd <<< "$(worker "$engine")"
  for h in heap-bomb buffer-8gb buffers-1gb resizable-1gb growable-sab-1gb tolocale-churn intl-held wasm-1gb host-copy-1gb; do
    echo "== $engine hostile: $h (cap $CAP)" >&2
    tmp=$(mktemp)
    capped "$CAP" "${cmd[@]}" hostile "$h" --heap-mb 64 > "$tmp" 2>/dev/null
    code=$?
    line=$(grep '^{' "$tmp" | tail -1)
    rm -f "$tmp"
    if [[ -z "$line" ]]; then
      line="{\"engine\":\"$engine\",\"hostile\":\"$h\",\"status\":\"KILLED\",\"exitCode\":$code,\"error\":\"the kernel killed the worker at its $CAP cap\"}"
    fi
    echo "$line" >> "$RESULTS"
    tidy
  done
done

python3 summarize.py "$RESULTS" > "$OUT/summary.md"
cat "$OUT/summary.md"
