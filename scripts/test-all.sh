#!/usr/bin/env bash
# ==============================================================================
# Cluster93 Test Suite Runner
#
# Usage:
#   ./scripts/test-all.sh                  # Run all tests in process mode (default)
#   ./scripts/test-all.sh server           # Run cluster93-server tests
#   ./scripts/test-all.sh protocol         # Run cluster93-runner-protocol tests
#   ./scripts/test-all.sh runtime          # Run runtime tests (Javet & GraalJS)
#   ./scripts/test-all.sh ClusterTest      # Run a specific test class
#   ./scripts/test-all.sh docker           # Run DockerClusterIT (requires Docker)
#   ./scripts/test-all.sh --embedded [target]  # Run in embedded worker mode
# ==============================================================================

set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

MODE="process"
TARGET="all"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --embedded)
      MODE="embedded"
      shift
      ;;
    --process)
      MODE="process"
      shift
      ;;
    -h|--help)
      echo "Usage: ./scripts/test-all.sh [--embedded|--process] [all|server|protocol|runtime|docker|<TestClassName>]"
      exit 0
      ;;
    *)
      TARGET="$1"
      shift
      ;;
  esac
done

export C93_WORKER_MODE="$MODE"

echo "=============================================================================="
echo " Cluster93 Test Runner"
echo " Mode:   C93_WORKER_MODE=${C93_WORKER_MODE}"
echo " Target: ${TARGET}"
LOG_FILE="${REPO_ROOT}/lasttest.txt"

echo " Output: ${LOG_FILE}"
echo "=============================================================================="

# Pre-run housekeeping: clean up stale temp files in /tmp except the cached ICU binary
rm -f /tmp/cluster93-icudt78l-*.dat 2>/dev/null || true
rm -f "${LOG_FILE}" 2>/dev/null || true

MAVEN_FLAGS="-am -fae -Dsurefire.failIfNoSpecifiedTests=false -DreuseForks=false"

run_mvn() {
  local cmd=("$@")
  echo "Executing: ./mvnw ${cmd[*]}"
  # Use pipefail to capture failure while teeing to LOG_FILE and terminal
  ./mvnw "${cmd[@]}" 2>&1 | tee "${LOG_FILE}"
}

TEST_STATUS=0
case "$TARGET" in
  all)
    echo "Running all unit and integration test suites (excluding DockerClusterIT)..."
    run_mvn test $MAVEN_FLAGS -Dtest='!DockerClusterIT' || TEST_STATUS=$?
    ;;
  server)
    echo "Running cluster93-server tests (excluding DockerClusterIT)..."
    run_mvn test -pl server $MAVEN_FLAGS -Dtest='cluster93.server.**,!DockerClusterIT' || TEST_STATUS=$?
    ;;
  protocol)
    echo "Running cluster93-runner-protocol tests..."
    run_mvn test -pl runner-protocol $MAVEN_FLAGS || TEST_STATUS=$?
    ;;
  runtime)
    echo "Running cluster93-runtime tests (Javet & GraalJS)..."
    run_mvn test -pl runtime-javet,runtime-graaljs $MAVEN_FLAGS || TEST_STATUS=$?
    ;;
  docker)
    echo "Running DockerClusterIT (Docker e2e multi-node cluster)..."
    run_mvn test -pl server $MAVEN_FLAGS -Dtest='DockerClusterIT' || TEST_STATUS=$?
    ;;
  *)
    echo "Running targeted test: ${TARGET}..."
    run_mvn test -pl server $MAVEN_FLAGS -Dtest="${TARGET}" || TEST_STATUS=$?
    ;;
esac

echo ""
echo "Checking for native crash logs (hs_err_pid*.log)..."
CRASH_LOGS=$(find . -maxdepth 3 -name 'hs_err_pid*.log' 2>/dev/null || true)
if [[ -n "$CRASH_LOGS" ]]; then
  echo "WARNING: Found native crash logs:"
  echo "$CRASH_LOGS"
  exit 1
fi

if [[ $TEST_STATUS -ne 0 ]]; then
  echo ""
  echo "=============================================================================="
  echo " TEST FAILURES DETECTED (Exit code: ${TEST_STATUS})"
  echo " Log captured at: ${LOG_FILE}"
  echo "=============================================================================="
  if grep -E '<<< FAILURE!|<<< ERROR!' "${LOG_FILE}" >/dev/null 2>&1; then
    echo "Failed tests summary:"
    grep -E '<<< FAILURE!|<<< ERROR!' "${LOG_FILE}" | sed 's/^/  /'
  fi
  echo ""
  exit $TEST_STATUS
fi

echo "=============================================================================="
echo " All tests finished successfully!"
echo " Log captured at: ${LOG_FILE}"
echo "=============================================================================="
