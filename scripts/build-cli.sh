#!/usr/bin/env bash
# Builds the native `c93` CLI with GraalVM Native Image and installs it.
#
# The CLI is deliberately small (picocli, Jackson's tree model, the JDK's HTTP client), so
# native-image needs no tuning; see cli/pom.xml's `native` profile (D-048, D-049).
#
# Usage:
#   scripts/build-cli.sh
#
# Environment:
#   GRAALVM_HOME / JAVA_HOME   GraalVM SDK; must contain bin/native-image
#
# Installs the binary to bin/c93 and ~/.local/bin/c93 (both mode 0755).
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

# --------------------------------------------------------------------------
# GraalVM SDK discovery
# --------------------------------------------------------------------------
# Resolve GRAALVM_HOME once (and prepend its bin/ to PATH), shared with build-workers.sh.
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/detect-graalvm.sh"

echo "== building the native c93 CLI with GraalVM at $GRAALVM_HOME" >&2
env JAVA_HOME="$GRAALVM_HOME" GRAALVM_HOME="$GRAALVM_HOME" \
  ./mvnw -q -DskipTests -DskipITs -pl cli -am package -Pnative \
  -Dgit.commit="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo dev)" \
  -Dbuild.time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

SOURCE="$REPO_ROOT/cli/target/c93"
if [[ ! -f "$SOURCE" ]]; then
  echo "Error: native image $SOURCE was not produced" >&2
  exit 1
fi

BIN_DIR="$REPO_ROOT/bin"
LOCAL_BIN_DIR="${HOME}/.local/bin"
mkdir -p "$BIN_DIR" "$LOCAL_BIN_DIR"
install -m 0755 "$SOURCE" "$BIN_DIR/c93"
install -m 0755 "$SOURCE" "$LOCAL_BIN_DIR/c93"
echo "== installed $BIN_DIR/c93 and $LOCAL_BIN_DIR/c93" >&2

echo "== checking the installed CLI" >&2
"$LOCAL_BIN_DIR/c93" --version

echo "== native CLI build complete" >&2
