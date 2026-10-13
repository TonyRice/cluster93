#!/usr/bin/env bash
# Builds the native `automately` CLI with GraalVM Native Image and installs it.
#
# The CLI is deliberately small (picocli, Jackson's tree model, the JDK's HTTP client), so
# native-image needs no tuning; see automately-cli/pom.xml's `native` profile (D-048, D-049).
#
# Usage:
#   scripts/build-cli.sh
#
# Environment:
#   GRAALVM_HOME / JAVA_HOME   GraalVM SDK; must contain bin/native-image
#
# Installs the binary to bin/automately and ~/.local/bin/automately (both mode 0755).
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

# --------------------------------------------------------------------------
# GraalVM SDK discovery
# --------------------------------------------------------------------------
# Resolve GRAALVM_HOME once (and prepend its bin/ to PATH), shared with build-workers.sh.
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/detect-graalvm.sh"

echo "== building the native automately CLI with GraalVM at $GRAALVM_HOME" >&2
env JAVA_HOME="$GRAALVM_HOME" GRAALVM_HOME="$GRAALVM_HOME" \
  ./mvnw -q -DskipTests -DskipITs -pl automately-cli -am package -Pnative \
  -Dgit.commit="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo dev)" \
  -Dbuild.time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

SOURCE="$REPO_ROOT/automately-cli/target/automately"
if [[ ! -f "$SOURCE" ]]; then
  echo "Error: native image $SOURCE was not produced" >&2
  exit 1
fi

BIN_DIR="$REPO_ROOT/bin"
LOCAL_BIN_DIR="${HOME}/.local/bin"
mkdir -p "$BIN_DIR" "$LOCAL_BIN_DIR"
install -m 0755 "$SOURCE" "$BIN_DIR/automately"
install -m 0755 "$SOURCE" "$LOCAL_BIN_DIR/automately"
echo "== installed $BIN_DIR/automately and $LOCAL_BIN_DIR/automately" >&2

echo "== checking the installed CLI" >&2
"$LOCAL_BIN_DIR/automately" --version

echo "== native CLI build complete" >&2
