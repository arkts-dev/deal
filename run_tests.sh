#!/bin/bash
set -e

JOBS=1
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

# Coverage uses a silent PATH wrapper for java, including Java subprocesses.
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
    bg|fg)
      read -r -a record_args <<< "$record_command"
      # All Java suites run sequentially; --jobs controls nested suite work.
      java "-Ddeal.test.jobs=$JOBS" "${record_args[@]:1}"
      ;;
    luajit|node)
      while IFS= read -r record_line; do
        case "$record_line" in
          WARNING:*) ;;
          *) read -r -a record_args <<< "$record_line"; "${record_args[@]}" ;;
        esac
      done <<< "$record_command"
      ;;
    *) echo "INTERNAL ERROR: unknown TEST_MAINS record class '$record_class'" >&2; exit 1 ;;
  esac
done

echo "=== All Tests Passed ==="
