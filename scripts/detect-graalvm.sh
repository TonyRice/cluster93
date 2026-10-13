#!/usr/bin/env bash
# Resolve the GraalVM SDK (`native-image`) once, so build scripts never re-discover it.
#
# Resolution order:
#   1. $GRAALVM_HOME         (bin/native-image must exist and be executable)
#   2. $JAVA_HOME            (bin/native-image must exist and be executable)
#   3. SDKMAN: the active `~/.sdkman/candidates/java/current`, then the highest
#      installed candidate matching `*graal*`
#   4. `native-image` on the current $PATH (via `command -v native-image`)
#
# When sourced:
#   export GRAALVM_HOME and prepend $GRAALVM_HOME/bin to $PATH.
# When executed directly:
#   print the resolved GRAALVM_HOME path, then `native-image --version`.
#
# Usage:
#   source scripts/detect-graalvm.sh   # inside a build script
#   scripts/detect-graalvm.sh          # from the shell
set -euo pipefail

detect_graalvm() {
  # 1. An explicit GRAALVM_HOME wins.
  if [[ -n "${GRAALVM_HOME:-}" && -x "$GRAALVM_HOME/bin/native-image" ]]; then
    return 0
  fi
  # 2. A GraalVM JDK masquerading as JAVA_HOME.
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/native-image" ]]; then
    GRAALVM_HOME="$JAVA_HOME"
    return 0
  fi
  # 3. SDKMAN: the active candidate, then the highest installed GraalVM candidate.
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
  # 4. Fall back to whatever `native-image` resolves to on PATH.
  if command -v native-image >/dev/null 2>&1; then
    local ni_path
    ni_path=$(command -v native-image)
    GRAALVM_HOME=$(dirname "$(dirname "$(realpath "$ni_path")")")
    return 0
  fi
  echo "Error: GraalVM not found. Set GRAALVM_HOME or install via SDKMAN (e.g. sdk install java 25.4.4+1-graal)." >&2
  return 1
}

detect_graalvm

if [[ "${BASH_SOURCE[0]}" != "${0}" ]]; then
  # Sourced by a build script: export the SDK and put its bin/ first on PATH.
  export GRAALVM_HOME
  export PATH="$GRAALVM_HOME/bin:$PATH"
else
  # Executed directly: report what was resolved.
  echo "$GRAALVM_HOME"
  "$GRAALVM_HOME/bin/native-image" --version
fi
