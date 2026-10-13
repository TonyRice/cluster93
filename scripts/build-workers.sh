#!/usr/bin/env bash
# Canonical native worker build script.
#
# Builds the GraalVM native-image tenant workers from cluster93-worker:
#   worker-javet    V8 through Javet   (-Pjavet,native)
#   worker-graaljs  embedded GraalJS   (-Pgraaljs,native)
#
# Usage:
#   scripts/build-workers.sh [javet|graaljs|all] [--javet-metadata]
#
#   Target defaults to "all". With --javet-metadata the Javet reflect-config
#   (reachability-metadata.json) is regenerated first; run that again after a
#   Javet upgrade.
#
# Binaries are installed to the repo-root bin/ directory and copied to
# benchmarks/engines/bin/ so the engine comparison (benchmarks/engines/run.sh)
# runs without maintaining a second build implementation.
#
# Environment:
#   GRAALVM_HOME / JAVA_HOME   GraalVM SDK; must contain bin/native-image
#   BUILD_CAP                  kernel memory cap per build (default 16G)
#   NATIVE_HEAP                native-image builder heap (default 12g)
#   NATIVE_THREADS             native-image builder threads (default 2)
#   ENGINES                    legacy space-separated engine list, e.g.
#                              ENGINES="javet graaljs" (used when no positional
#                              engine is given; kept for backward compatibility)
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

usage() {
  cat <<'USAGE'
Builds the GraalVM native-image tenant workers from cluster93-worker.

Usage:
  scripts/build-workers.sh [javet|graaljs|all] [--javet-metadata]

  Target defaults to "all". With --javet-metadata the Javet reflect-config
  (reachability-metadata.json) is regenerated first; run that again after a
  Javet upgrade.

Environment:
  GRAALVM_HOME / JAVA_HOME   GraalVM SDK; must contain bin/native-image
  BUILD_CAP                  kernel memory cap per build (default 16G)
  NATIVE_HEAP                native-image builder heap (default 12g)
  NATIVE_THREADS             native-image builder threads (default 2)
  ENGINES                    legacy space-separated engine list, e.g.
                             ENGINES="javet graaljs" (used when no positional
                             engine is given; kept for backward compatibility)
USAGE
}

# --------------------------------------------------------------------------
# Argument parsing
# --------------------------------------------------------------------------
TARGET=""
JAVET_METADATA=0
for arg in "$@"; do
  case "$arg" in
    javet|graaljs|all) TARGET="$arg" ;;
    --javet-metadata) JAVET_METADATA=1 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Error: unknown argument '$arg'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

IFS=$' \t\n'
BUILD_JAVET=0
BUILD_GRAALJS=0
if [[ -n "$TARGET" ]]; then
  case "$TARGET" in
    all) BUILD_JAVET=1; BUILD_GRAALJS=1 ;;
    javet) BUILD_JAVET=1 ;;
    graaljs) BUILD_GRAALJS=1 ;;
  esac
elif [[ -n "${ENGINES:-}" ]]; then
  # Backward compatibility with the old benchmarks/engines/build.sh interface.
  for engine in $ENGINES; do
    case "$engine" in
      javet) BUILD_JAVET=1 ;;
      graaljs) BUILD_GRAALJS=1 ;;
      all) BUILD_JAVET=1; BUILD_GRAALJS=1 ;;
      *) : ;;
    esac
  done
  if [[ $BUILD_JAVET -eq 0 && $BUILD_GRAALJS -eq 0 ]]; then
    BUILD_JAVET=1; BUILD_GRAALJS=1
  fi
else
  BUILD_JAVET=1; BUILD_GRAALJS=1
fi

# --------------------------------------------------------------------------
# GraalVM SDK discovery
# --------------------------------------------------------------------------
# Resolve GRAALVM_HOME once (and prepend its bin/ to PATH), shared with build-cli.sh.
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/detect-graalvm.sh"

# Identify GraalVM version and edition (Oracle vs Community).
NI_VERSION_RAW=$("$GRAALVM_HOME/bin/native-image" --version 2>&1 || true)
if echo "$NI_VERSION_RAW" | grep -qi "Oracle GraalVM"; then
  GRAALVM_EDITION="Oracle GraalVM"
elif echo "$NI_VERSION_RAW" | grep -qi "GraalVM CE"; then
  GRAALVM_EDITION="GraalVM Community"
else
  GRAALVM_EDITION="GraalVM"
fi
NI_VERSION_FIRST=$(echo "$NI_VERSION_RAW" | head -1)

echo "== GraalVM detected:" >&2
echo "   Path:    $GRAALVM_HOME" >&2
echo "   Edition: $GRAALVM_EDITION" >&2
echo "   Version: $NI_VERSION_FIRST" >&2

# --------------------------------------------------------------------------
# Output locations, sandboxing, and metadata
# --------------------------------------------------------------------------
BIN_DIR="$REPO_ROOT/bin"
BENCH_BIN_DIR="$REPO_ROOT/benchmarks/engines/bin"
mkdir -p "$BIN_DIR" "$BENCH_BIN_DIR"

CAP=${BUILD_CAP:-16G}
HEAP=${NATIVE_HEAP:-12g}
THREADS=${NATIVE_THREADS:-2}

write_metadata() {
  local dir=$1
  cat <<EOF > "$dir/build-metadata.json"
{
  "graalvmHome": "$GRAALVM_HOME",
  "edition": "$GRAALVM_EDITION",
  "version": "$NI_VERSION_FIRST",
  "builtAt": "$(date -Is)"
}
EOF
}
write_metadata "$BIN_DIR"
write_metadata "$BENCH_BIN_DIR"

if command -v systemd-run >/dev/null 2>&1; then
  HAVE_SYSTEMD_RUN=1
else
  HAVE_SYSTEMD_RUN=0
fi

# Runs a command under a kernel memory cap when systemd-run is available;
# otherwise runs it directly with a capped Maven heap.
sandboxed() {
  if [[ $HAVE_SYSTEMD_RUN -eq 1 ]]; then
    systemd-run --user --scope --quiet -p MemoryMax="$CAP" -p MemorySwapMax=0 \
      env JAVA_HOME="$GRAALVM_HOME" GRAALVM_HOME="$GRAALVM_HOME" MAVEN_OPTS="-Xmx1g" "$@"
  else
    env JAVA_HOME="$GRAALVM_HOME" GRAALVM_HOME="$GRAALVM_HOME" MAVEN_OPTS="-Xmx1g" "$@"
  fi
}

# --------------------------------------------------------------------------
# Optional: regenerate Javet reachability reflection metadata
# --------------------------------------------------------------------------
regenerate_javet_metadata() {
  local jar
  jar=$(find "$HOME/.m2/repository/com/caoccao/javet/javet" -name 'javet-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)
  if [[ -z "$jar" ]]; then
    echo "Error: no Javet jar found under ~/.m2; run an install build first." >&2
    exit 1
  fi
  echo "== regenerating Javet reflection metadata from $(basename "$jar")" >&2
  unzip -Z1 "$jar" | grep -E '^com/caoccao/javet/.*\.class$' | sed -e 's#/#.#g' -e 's#\.class$##' | python3 -c '
import sys, json
names = sorted(l.strip() for l in sys.stdin if l.strip())
entries = [{"type": n, "jniAccessible": True, "allDeclaredConstructors": True, "allPublicConstructors": True,
            "allDeclaredMethods": True, "allPublicMethods": True, "allDeclaredFields": True, "allPublicFields": True}
           for n in names]
json.dump({"reflection": entries}, open(sys.argv[1], "w"), indent=1)' \
    cluster93-worker/src/javet/resources/META-INF/native-image/cluster93/javet/reachability-metadata.json
}

# --------------------------------------------------------------------------
# Build one engine and install its artifact
# --------------------------------------------------------------------------
build_engine() {
  local engine=$1
  echo "== building worker-$engine (memory cap $CAP, heap $HEAP, threads $THREADS)" >&2
  sandboxed ./mvnw -q -DskipTests -DskipITs -pl cluster93-worker -am install "-P$engine"
  sandboxed ./mvnw -q -DskipTests -DskipITs -pl cluster93-worker clean package "-P$engine,native" \
    -Dnative.builder.heap="$HEAP" -Dnative.builder.threads="$THREADS"

  local src="cluster93-worker/target/worker-$engine"
  if [[ ! -f "$src" ]]; then
    echo "Error: native image $src was not produced" >&2
    exit 1
  fi
  install -m 0755 "$src" "$BIN_DIR/worker-$engine"
  install -m 0755 "$src" "$BENCH_BIN_DIR/worker-$engine"
  echo "== installed bin/worker-$engine and benchmarks/engines/bin/worker-$engine" >&2
}

if [[ $JAVET_METADATA -eq 1 ]]; then
  regenerate_javet_metadata
fi

if [[ $BUILD_JAVET -eq 1 ]]; then
  build_engine javet
fi
if [[ $BUILD_GRAALJS -eq 1 ]]; then
  build_engine graaljs
fi

echo "== build complete:" >&2
ls -la "$BIN_DIR"
