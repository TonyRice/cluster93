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
detect_graalvm() {
  if [[ -n "${GRAALVM_HOME:-}" && -x "$GRAALVM_HOME/bin/native-image" ]]; then
    return 0
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/native-image" ]]; then
    GRAALVM_HOME="$JAVA_HOME"
    return 0
  fi
  # SDKMAN: the pinned GraalVM this project builds with, then the active candidate,
  # then the highest installed GraalVM candidate.
  local pinned="${HOME}/.sdkman/candidates/java/25.4.4+1-graal"
  if [[ -x "$pinned/bin/native-image" ]]; then
    GRAALVM_HOME="$pinned"
    return 0
  fi
  if [[ -x "${HOME}/.sdkman/candidates/java/current/bin/native-image" ]]; then
    GRAALVM_HOME="${HOME}/.sdkman/candidates/java/current"
    return 0
  fi
  if [[ -d "${HOME}/.sdkman/candidates/java" ]]; then
    local candidate
    candidate=$(find "${HOME}/.sdkman/candidates/java" -maxdepth 1 -name '*graal*' ! -name 'current' | sort -V | tail -1)
    if [[ -n "$candidate" && -x "$candidate/bin/native-image" ]]; then
      GRAALVM_HOME="$candidate"
      return 0
    fi
  fi
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

echo "== building the native automately CLI with GraalVM at $GRAALVM_HOME" >&2
env JAVA_HOME="$GRAALVM_HOME" GRAALVM_HOME="$GRAALVM_HOME" \
  ./mvnw -q -DskipTests -DskipITs -pl automately-cli -am package -Pnative

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
