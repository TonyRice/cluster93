#!/usr/bin/env bash
# Thin wrapper over the canonical native worker builder (scripts/build-workers.sh).
#
# The build logic lives there; this file is kept so existing benchmark workflows
# (benchmarks/engines/run.sh, the README's `ENGINES=... ./build.sh`) keep working
# without a second, divergent build implementation.
#
# Usage is unchanged:
#   ./build.sh [javet|graaljs|all] [--javet-metadata]
#   ENGINES="javet graaljs" ./build.sh
set -euo pipefail
exec "$(cd "$(dirname "$0")/../.." && pwd)/scripts/build-workers.sh" "$@"
