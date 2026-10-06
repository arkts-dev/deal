#!/bin/bash
set -e

JACOCO_DIR="${JACOCO_DIR:-/tmp/opencode/jacoco}"
JACOCO_DIR="$(cd "$JACOCO_DIR" && pwd)"
for asset in "$JACOCO_DIR/jacocoagent.jar" "$JACOCO_DIR/jacococli.jar"; do
  if [ ! -f "$asset" ]; then
    echo "ERROR: missing required file: $asset" >&2
    exit 1
  fi
done

# Avoid JAVA_TOOL_OPTIONS startup text in exact-output subprocess protocols.
JAVA_BIN="$(command -v java)"
COVERAGE_WRAPPER="$(mktemp -d)"
trap 'rm -rf "$COVERAGE_WRAPPER"' EXIT
export DEAL_COVERAGE_JAVA="$JAVA_BIN"
export DEAL_COVERAGE_AGENT="$JACOCO_DIR/jacocoagent.jar"
export DEAL_COVERAGE_EXEC="$PWD/build/jacoco.exec"
printf '%s\n' '#!/bin/bash' \
  'exec "$DEAL_COVERAGE_JAVA" "-javaagent:$DEAL_COVERAGE_AGENT=destfile=$DEAL_COVERAGE_EXEC,append=true,includes=deal.*" "$@"' \
  > "$COVERAGE_WRAPPER/java"
chmod +x "$COVERAGE_WRAPPER/java"
PATH="$COVERAGE_WRAPPER:$PATH" ./run_tests.sh "$@"

"$JAVA_BIN" -jar "$JACOCO_DIR/jacococli.jar" version
python3 tools/coverage-report.py "$JAVA_BIN" "$JACOCO_DIR/jacococli.jar"
