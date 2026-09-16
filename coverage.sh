#!/bin/bash
set -e

source tools/gate-manifest.sh

JACOCO_DIR="/tmp/opencode/jacoco"
JUNIT_CP="/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar"
AGENT="-javaagent:$JACOCO_DIR/jacocoagent.jar=destfile=build/jacoco.exec,append=true,includes=deal.*,excludes=deal.test.*"

for asset in "$JACOCO_DIR/jacocoagent.jar" "$JACOCO_DIR/jacococli.jar" /usr/share/java/junit4.jar /usr/share/java/hamcrest-core.jar; do
  if [ ! -f "$asset" ]; then
    echo "ERROR: missing required file: $asset" >&2
    exit 1
  fi
done

rm -rf build
mkdir -p build

mapfile -d '' PROD_SOURCES < <(find deal -name '*.java' -print0 | sort -z)
mapfile -d '' TEST_SOURCES < <(find test -path 'test/conformance/host-fixtures' -prune -o -name '*.java' -print0 | sort -z)

echo "=== Compiling all DEAL sources and tests (--release 22) ==="
javac --release 22 -proc:none -d build -cp "$JUNIT_CP" \
  "${PROD_SOURCES[@]}" "${TEST_SOURCES[@]}"

run_java() {
  java -ea "$AGENT" -cp "build:$JUNIT_CP" "$@"
}

BACKGROUND_PIDS=""

launch_background() {
  java -ea "$AGENT" -cp "build:$JUNIT_CP" "${@:5}" &
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
      run_java "${record_args[@]:4}"
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
      TEMP_FILE="/tmp/deal-stdlib-ir-cov-$$.txt"
      run_java deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
      if [ "${DEAL_UPDATE_GOLDENS}" = "true" ]; then
        cp "$TEMP_FILE" "$GOLDEN_FILE"
        echo "  Golden IR file updated"
      elif diff -q "$GOLDEN_FILE" "$TEMP_FILE" > /dev/null 2>&1; then
        echo "  Golden IR file is current"
      else
        echo "  ERROR: Golden IR file differs from generated output!"
        echo "  Run 'DEAL_UPDATE_GOLDENS=true ./coverage.sh' to update."
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

PROD_CLASSFILES=()
while IFS= read -r cf; do
  PROD_CLASSFILES+=("--classfiles" "$cf")
done < <(find build/deal -name '*.class' | grep -v '^build/deal/test/' | grep -v 'Test\.class$')
if [ "${#PROD_CLASSFILES[@]}" -eq 0 ]; then
  echo "ERROR: no production class files found under build/deal" >&2
  exit 1
fi

echo ""
echo "=== Generating JaCoCo report ==="
java -jar "$JACOCO_DIR/jacococli.jar" report build/jacoco.exec \
  "${PROD_CLASSFILES[@]}" --sourcefiles deal \
  --csv build/coverage.csv --html build/coverage-html

COVERAGE_TOTALS="$(LC_ALL=C awk -F, '
NR==1 {
  for (i = 1; i <= NF; i++) {
    if ($i == "LINE_MISSED") lm = i;
    if ($i == "LINE_COVERED") lc = i;
    if ($i == "BRANCH_MISSED") bm = i;
    if ($i == "BRANCH_COVERED") bc = i;
  }
  next;
}
$2 == "deal.test" || $3 ~ /Test$/ { next; }
{
  lmiss += $lm;
  lcov += $lc;
  bmiss += $bm;
  bcov += $bc;
}
END {
  lp = lmiss + lcov == 0 ? 0 : lcov / (lmiss + lcov) * 100;
  bp = bmiss + bcov == 0 ? 0 : bcov / (bmiss + bcov) * 100;
  printf "%.2f %.2f\n", lp, bp;
}' build/coverage.csv)"
LINE_PCT="$(echo "$COVERAGE_TOTALS" | awk '{print $1}')"
BRANCH_PCT="$(echo "$COVERAGE_TOTALS" | awk '{print $2}')"

echo ""
echo "=== JaCoCo production coverage ==="
echo "Line coverage:   ${LINE_PCT}%  (minimum: 75%)"
echo "Branch coverage: ${BRANCH_PCT}%  (minimum: 55%)"
if ! LC_ALL=C awk -v line="$LINE_PCT" -v branch="$BRANCH_PCT" \
  'BEGIN { exit (line >= 75.0 && branch >= 55.0) ? 0 : 1 }'; then
  echo "ERROR: coverage below minimum" >&2
  exit 1
fi

echo "=== Coverage passed ==="
