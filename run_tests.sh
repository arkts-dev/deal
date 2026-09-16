#!/bin/bash
set -e

DEFAULT_JOBS=1
JOBS="$DEFAULT_JOBS"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --jobs)
      JOBS="${2:--}"
      shift 2
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done
case "$JOBS" in
  ''|-|*[!0-9]*|0)
    echo "ERROR: --jobs requires a positive integer" >&2
    exit 2
    ;;
esac

echo "=== DEAL test parallelism: jobs=$JOBS ==="

source tools/gate-manifest.sh

rm -rf build
mkdir -p build

echo "=== Compiling all DEAL sources and tests ==="
mapfile -d '' PROD_SOURCES < <(find deal -name '*.java' -print0 | sort -z)
mapfile -d '' TEST_SOURCES < <(find test -path 'test/conformance/host-fixtures' -prune -o -name '*.java' -print0 | sort -z)
javac --release 25 -proc:none -d build \
  -cp /usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  "${PROD_SOURCES[@]}" "${TEST_SOURCES[@]}"

BACKGROUND_PIDS=""

launch_background() {
  java "-Ddeal.test.jobs=$JOBS" "${@:2}" &
  BACKGROUND_PIDS="$BACKGROUND_PIDS $!"
}

cleanup_background() {
  if [ -n "$BACKGROUND_PIDS" ]; then
    kill $BACKGROUND_PIDS 2>/dev/null || true
  fi
}
trap cleanup_background EXIT

for record in "${TEST_MAINS[@]}"; do
  record_class="${record%%|*}"
  record_rest="${record#*|}"
  record_banner="${record_rest%%|*}"
  record_command="${record_rest#*|}"
  if [ -n "$record_banner" ]; then
    echo ""
    echo "$record_banner"
  fi
  case "$record_class" in
    bg)
      read -r -a record_args <<< "$record_command"
      launch_background "${record_args[@]}"
      ;;
    fg)
      read -r -a record_args <<< "$record_command"
      java "-Ddeal.test.jobs=$JOBS" "${record_args[@]:1}"
      ;;
    luajit|node)
      while IFS= read -r record_line; do
        case "$record_line" in
          WARNING:*) ;;
          *)
            read -r -a record_args <<< "$record_line"
            "${record_args[@]}"
            ;;
        esac
      done <<< "$record_command"
      ;;
    golden-ir)
      GOLDEN_FILE="test/goldens/stdlib-declarations.ir.txt"
      TEMP_FILE="/tmp/deal-stdlib-ir-$$.txt"
      java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
      if [ "${DEAL_UPDATE_GOLDENS}" = "true" ]; then
        cp "$TEMP_FILE" "$GOLDEN_FILE"
        echo "  Golden IR file updated"
      elif diff -q "$GOLDEN_FILE" "$TEMP_FILE" > /dev/null 2>&1; then
        echo "  Golden IR file is current"
      else
        echo "  ERROR: Golden IR file differs from generated output!"
        echo "  Run 'DEAL_UPDATE_GOLDENS=true ./run_tests.sh' to update."
        diff "$GOLDEN_FILE" "$TEMP_FILE" || true
        rm -f "$TEMP_FILE"
        exit 1
      fi
      rm -f "$TEMP_FILE"
      ;;
    *)
      echo "INTERNAL ERROR: unknown TEST_MAINS record class '${record_class}'" >&2
      exit 1
      ;;
  esac
done

echo ""
echo "=== Running FFI Integration Driver ==="
luajit test/ffigen_integration.lua

echo ""
echo "=== Waiting for background suites ==="
BACKGROUND_FAILED=0
for pid in $BACKGROUND_PIDS; do
  if ! wait "$pid"; then
    BACKGROUND_FAILED=1
  fi
done
if [ "$BACKGROUND_FAILED" -eq 1 ]; then
  echo "=== A background test suite failed ===" >&2
  exit 1
fi
trap - EXIT

echo "=== All Tests Passed ==="
