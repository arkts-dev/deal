#!/bin/bash
set -e

JOBS=4
while [ "$#" -gt 0 ]; do
  case "$1" in
    --jobs)
      if [ "$#" -lt 2 ]; then
        echo "ERROR: --jobs requires a positive integer" >&2
        exit 2
      fi
      JOBS="$2"
      shift 2
      ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$JOBS" in
  ''|*[!0-9]*|0) echo "ERROR: --jobs requires a positive integer" >&2; exit 2 ;;
esac

# JaCoCo's shared append file is not a concurrent-writer protocol.
if [ -n "${DEAL_COVERAGE_AGENT:-}" ]; then JOBS=1; fi
echo "=== DEAL test process budget: jobs=$JOBS (nested jobs=1) ==="
source tools/gate-manifest.sh

# Fail-closed required-tool preflight: the selection below executes the real
# LuaJIT and Node runtime records, the production corpus and the published C11
# runtime suites (compiled and executed through clang) under these toolchains. A
# missing tool is the named TOOL_MISSING outcome and stops the gate here, before
# any build output is touched and before any suite could drop a supported case:
# never a warning or a skip.
REQUIRED_TOOLS=(javac java luajit node gcc clang)
tools/tool-preflight.sh "${REQUIRED_TOOLS[@]}"

rm -rf build
mkdir -p build/test-classes

echo "=== Compiling all DEAL sources and tests ==="
mapfile -d '' PROD_SOURCES < <(find deal -name '*.java' -print0 | sort -z)
mapfile -d '' TEST_SOURCES < <(find test -path 'test/conformance/host-fixtures' -prune -o -name '*.java' -print0 | sort -z)
javac --release 25 -proc:none -d build "${PROD_SOURCES[@]}"
javac --release 25 -proc:none -d build/test-classes \
  -cp build:/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  "${TEST_SOURCES[@]}"

# One log per isolated suite, printed whole on completion; no interleaving.
mkdir -p build/suite-logs
run_record() {
  local record="$1" record_class record_rest record_banner record_command
  local record_line suite_started
  local -a record_args
  suite_started=$SECONDS
  record_class="${record%%|*}"
  record_rest="${record#*|}"
  record_banner="${record_rest%%|*}"
  record_command="${record_rest#*|}"
  if [ -n "$record_banner" ]; then
    echo ""
    echo "$record_banner"
  fi
  case "$record_class" in
    bg|fg)
      read -r -a record_args <<< "$record_command"
      # Manifest Java commands start with java -ea -cp build[:...].
      record_args[3]="${record_args[3]}:build/test-classes"
      # --jobs is a total suite budget, not multiplicative nested parallelism.
      java -Ddeal.test.jobs=1 \
        "-Ddeal.test.native.objects=$PWD/build/native-test-objects" \
        "${record_args[@]:1}"
      ;;
    luajit|node)
      while IFS= read -r record_line; do
        case "$record_line" in
          '') continue ;;
          WARNING:*|SKIP:*)
            # The selection never omits a supported case: a skip or warning
            # line in one of these records is a manifest defect, not a
            # downgrade of the run.
            echo "INTERNAL ERROR: the '$record_class' manifest record contains" \
              "a skip/warning line: $record_line" >&2
            exit 1
            ;;
        esac
        read -r -a record_args <<< "$record_line"
        "${record_args[@]}"
      done <<< "$record_command"
      ;;
    *) echo "INTERNAL ERROR: unknown TEST_MAINS record class '$record_class'" >&2; exit 1 ;;
  esac
  echo "=== Suite elapsed: $((SECONDS - suite_started))s: $record_command ==="
}

# Start long-running independent lanes before the short suites. Selection stays
# exclusively in the manifest; bg denotes early admission, not extra workers.
records=()
for kind in bg other; do
  for record in "${TEST_MAINS[@]}"; do
    if { [ "$kind" = bg ] && [[ "$record" == bg\|* ]]; } ||
       { [ "$kind" = other ] && [[ "$record" != bg\|* ]]; }; then
      records+=("$record")
    fi
  done
done

declare -A active
cleanup_suites() {
  local pid
  for pid in "${!active[@]}"; do kill -- "-$pid" 2>/dev/null || true; done
  if [ "${#active[@]}" -gt 0 ]; then sleep 1; fi
  for pid in "${!active[@]}"; do kill -KILL -- "-$pid" 2>/dev/null || true; done
  for pid in "${!active[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup_suites EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Monitor mode gives every suite (and its children) an owned process group.
set -m
finish_suite() {
  local finished status=0
  wait -n -p finished "${!active[@]}" || status=$?
  if [ -z "${finished:-}" ]; then return 1; fi
  cat "${active[$finished]}"
  unset 'active[$finished]'
  return "$status"
}
index=0
for record in "${records[@]}"; do
  while [ "${#active[@]}" -ge "$JOBS" ]; do finish_suite; done
  log="build/suite-logs/$index.log"
  (run_record "$record") > "$log" 2>&1 &
  active[$!]="$log"
  index=$((index + 1))
done
while [ "${#active[@]}" -gt 0 ]; do finish_suite; done
set +m
trap - EXIT INT TERM

echo "=== All Tests Passed ==="
