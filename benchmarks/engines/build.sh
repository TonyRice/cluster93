#!/usr/bin/env bash
# Builds the engine comparison's native-image workers (D-126), one at a time: worker-javet, then worker-graaljs, copied
# to benchmarks/engines/bin/. Needs GraalVM (25.x) in GRAALVM_HOME.
#
# Each build runs under a kernel memory cap (BUILD_CAP, default 9G), so a build that grows too big is the only thing
# that dies; leave about 9 GB free. native-image's own heap and threads are NATIVE_HEAP and NATIVE_THREADS (default 7g
# and 2): worker-graaljs carries the whole JS language and runs out of a 6 GB heap in its compile stage, and fewer
# threads hold fewer graphs at once. ENGINES picks the workers to build (default "javet graaljs"). With
# --javet-metadata, first regenerates the list of Javet's classes registered for JNI (needed again after a Javet upgrade).
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${GRAALVM_HOME:?Set GRAALVM_HOME to a GraalVM 25 installation}"
CAP=${BUILD_CAP:-9G}
HEAP=${NATIVE_HEAP:-7g}
THREADS=${NATIVE_THREADS:-2}
OUT=benchmarks/engines/bin
mkdir -p "$OUT"
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
