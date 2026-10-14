#!/bin/sh
# One entrypoint for the Cluster93 benchmarks.
#   benchmarks/run.sh cluster   the three-node cluster in Docker; regenerates docs/benchmarks.md
#   benchmarks/run.sh engines   Javet vs GraalJS in native workers; writes benchmarks/engines/results/
#   benchmarks/run.sh all       both (cluster first)
# `cluster` needs Docker, JDK 21, curl and python3; `engines` needs bin/worker-* and takes about an hour.
set -eu
cd "$(dirname "$0")"

usage() {
    cat <<'USAGE'
Usage: benchmarks/run.sh [cluster|engines|all]

  cluster   the three-node cluster in Docker: every scenario, then regenerate docs/benchmarks.md
  engines   Javet vs GraalJS in native workers: the engine comparison, into benchmarks/engines/results/
  all       both (cluster first)

`cluster` needs Docker, JDK 21, curl and python3. `engines` needs the GraalVM-built bin/worker-*
(scripts/build-workers.sh) and takes about an hour.
USAGE
}

case "${1:-all}" in
    cluster) exec ./cluster/run.sh ;;
    engines) exec ./engines/run.sh ;;
    all)
        ./cluster/run.sh
        ./engines/run.sh
        ;;
    -h|--help|help) usage ;;
    *)
        echo "benchmarks/run.sh: unknown target '$1'" >&2
        usage >&2
        exit 2
        ;;
esac
