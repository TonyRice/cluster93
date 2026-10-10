#!/usr/bin/env bash
# Builds the engine comparison's native-image workers (D-126), one at a time: worker-javet, then worker-graaljs, copied
# to benchmarks/engines/bin/. Needs GraalVM (25.x) in GRAALVM_HOME.
#
# Each build runs under a kernel memory cap (BUILD_CAP, default 12G), so a build that grows too big is the only thing
# that dies; leave about 9 GB free. native-image's own heap and threads are NATIVE_HEAP and NATIVE_THREADS (default 7g
# and 2): worker-graaljs carries the whole JS language and runs out of a 6 GB heap in its compile stage, and fewer
# threads hold fewer graphs at once. ENGINES picks the workers to build (default "javet graaljs"). With
# --javet-metadata, first regenerates the list of Javet's classes registered for JNI (needed again after a Javet upgrade).
set -euo pipefail
cd "$(dirname "$0")/../.."

# Locate GraalVM SDK: check GRAALVM_HOME, JAVA_HOME, SDKMAN, or PATH
detect_graalvm() {
  if [[ -n "${GRAALVM_HOME:-}" && -x "$GRAALVM_HOME/bin/native-image" ]]; then
    return 0
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/native-image" ]]; then
    GRAALVM_HOME="$JAVA_HOME"
    return 0
  fi
  # Check SDKMAN current or installed GraalVM candidates
  if [[ -d "${HOME}/.sdkman/candidates/java" ]]; then
    if [[ -x "${HOME}/.sdkman/candidates/java/current/bin/native-image" ]]; then
      GRAALVM_HOME="${HOME}/.sdkman/candidates/java/current"
      return 0
    fi
    local candidate
    candidate=$(find "${HOME}/.sdkman/candidates/java" -maxdepth 1 -name '*graal*' ! -name 'current' | sort -V | tail -1)
    if [[ -n "$candidate" && -x "$candidate/bin/native-image" ]]; then
      GRAALVM_HOME="$candidate"
      return 0
    fi
  fi
  # Fall back to PATH
  if command -v native-image >/dev/null 2>&1; then
    local ni_path
    ni_path=$(command -v native-image)
    GRAALVM_HOME=$(dirname "$(dirname "$(realpath "$ni_path")")")
    return 0
  fi
  echo "Error: GraalVM not found. Set GRAALVM_HOME or install via SDKMAN (e.g. sdk install java 25.4.4+1-graal)." >&2
  exit 1
}

detect_graalvm
export GRAALVM_HOME

# Identify GraalVM version and edition (Oracle vs Community Edition)
NI_VERSION_RAW=$("$GRAALVM_HOME/bin/native-image" --version 2>&1 || true)
if echo "$NI_VERSION_RAW" | grep -qi "Oracle GraalVM"; then
  GRAALVM_EDITION="Oracle GraalVM"
else
  GRAALVM_EDITION="GraalVM Community"
fi
NI_VERSION_FIRST=$(echo "$NI_VERSION_RAW" | head -1)

echo "== GraalVM detected:" >&2
echo "   Path:    $GRAALVM_HOME" >&2
echo "   Edition: $GRAALVM_EDITION" >&2
echo "   Version: $NI_VERSION_FIRST" >&2

CAP=${BUILD_CAP:-16G}
HEAP=${NATIVE_HEAP:-12g}
THREADS=${NATIVE_THREADS:-2}
OUT=benchmarks/engines/bin
mkdir -p "$OUT"

# Record build metadata into bin/
cat <<EOF > "$OUT/build-metadata.json"
{
  "graalvmHome": "$GRAALVM_HOME",
  "edition": "$GRAALVM_EDITION",
  "version": "$NI_VERSION_FIRST",
  "builtAt": "$(date -Is)"
}
EOF

capped() {
  systemd-run --user --scope --quiet -p MemoryMax="$CAP" -p MemorySwapMax=0 \
    env JAVA_HOME="$GRAALVM_HOME" GRAALVM_HOME="$GRAALVM_HOME" MAVEN_OPTS="-Xmx1g" "$@"
}
if [[ "${1:-}" == "--javet-metadata" ]]; then
  JAR=$(find ~/.m2/repository/com/caoccao/javet/javet -name 'javet-*.jar' ! -name '*sources*' | sort | tail -1)
  unzip -Z1 "$JAR" | grep -E '^com/caoccao/javet/.*\.class$' | sed -e 's#/#.#g' -e 's#\.class$##' | python3 -c '
import sys, json
names = sorted(l.strip() for l in sys.stdin if l.strip())
entries = [{"type": n, "jniAccessible": True, "allDeclaredConstructors": True, "allPublicConstructors": True, "allDeclaredMethods": True,
            "allPublicMethods": True, "allDeclaredFields": True, "allPublicFields": True} for n in names]
json.dump({"reflection": entries}, open(sys.argv[1], "w"), indent=1)' \
    automately-worker/src/javet/resources/META-INF/native-image/automately/javet/reachability-metadata.json
fi
for engine in ${ENGINES:-javet graaljs}; do
  echo "== building worker-$engine (memory cap $CAP)" >&2
  capped ./mvnw -q -DskipTests -DskipITs -pl automately-worker -am install "-P$engine"
  capped ./mvnw -q -DskipTests -DskipITs -pl automately-worker clean package "-P$engine,native" \
    -Dnative.builder.heap="$HEAP" -Dnative.builder.threads="$THREADS"
  cp "automately-worker/target/worker-$engine" "$OUT/"
done
ls -la "$OUT"
