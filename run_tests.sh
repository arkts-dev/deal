#!/bin/bash
set -e

mkdir -p build

# =========================================================================
# Single compilation step: compile all source and test files at once.
# Incremental: when every .java source under deal/ and test/ is older
# than the recorded build stamp (and this script itself has not changed
# since the stamp), reuse the build/ classes. The stamp is updated after
# every successful full compile, so repeated gate runs on an unchanged
# tree skip the recompilation while a fresh checkout or any touched
# source still compiles everything.
# =========================================================================
STAMP="build/.deal-build-stamp"
NEEDS_BUILD=0
if [ ! -f "$STAMP" ]; then
  NEEDS_BUILD=1
elif [ run_tests.sh -nt "$STAMP" ]; then
  NEEDS_BUILD=1
elif [ -n "$(find deal test -name '*.java' -newer "$STAMP" -print -quit)" ]; then
  NEEDS_BUILD=1
fi

if [ "$NEEDS_BUILD" = "1" ]; then
echo "=== Compiling all DEAL sources and tests ==="
# -proc:none: no DEAL/test source uses an annotation processor, so javac's
# default processor-discovery pass is pure per-task startup cost (the gate
# budget is shared with the JVM artifact suites; measured ~40% faster
# compile under load).
javac --release 25 -proc:none -d build \
  -cp /usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  deal/source/*.java \
  deal/ast/*.java \
  deal/types/*.java \
  deal/diagnostics/*.java \
  deal/lexer/*.java \
  deal/parser/*.java \
  deal/checker/*.java \
  deal/codegen/*.java \
  deal/codegen/lua/*.java \
  deal/codegen/jvm/*.java \
  deal/codegen/js/*.java \
  deal/ir/*.java \
  deal/module/*.java \
  deal/Main.java \
  test/StubModuleResolver.java \
  test/DiagnosticRangeTest.java \
  test/DiagnosticClassificationTest.java \
  test/AstAndTypesTest.java \
  test/LexerTest.java \
  test/ParserTest.java \
  test/CheckerTest.java \
  test/IrDumperTest.java \
  test/IrGoldenTest.java \
  test/TypeDescriptorTest.java \
  test/BackendConformanceTest.java \
  test/JvmBackendTest.java \
  test/LuaBackendTest.java \
  test/LuaBackendIntegrationTest.java \
  test/ModuleSystemTest.java \
  test/StdlibDeclParseTest.java \
  test/SourceMapTest.java \
  test/RuntimeSourceLocationTest.java \
  test/StdlibContractTest.java \
  test/GenerateStdlibGoldenIr.java \
  test/ConformanceTest.java \
  test/JvmConformanceTest.java \
  test/LuaAbiTest.java \
  test/LuaAbiBackendTest.java \
  test/CrossModuleTypingTest.java
  touch "$STAMP"
else
  echo "=== DEAL sources and tests unchanged since the last build; reusing build/ ==="
fi

# =========================================================================
# Migration gate (verification 7): the legacy start-only record is gone
# (compile-time enforced: deal/lexer/Diagnostic.java is deleted, so any
# leftover start-only call site fails compilation), and two source scans
# assert that (1) no reference to deal.lexer.Diagnostic remains anywhere
# in deal/ or test/ and (2) no production source creates an
# internal-defect note outside the D9 normalization carrier in
# deal/diagnostics/CompilerDiagnostic.java. Either scan tripping fails
# the gate.
# =========================================================================
echo ""
echo "=== Migration Gate: legacy diagnostic surface scans ==="
if [ -e deal/lexer/Diagnostic.java ]; then
  echo "  ERROR: deal/lexer/Diagnostic.java still exists; the legacy start-only record must be deleted."
  exit 1
fi
LEGACY_REFS="$(grep -rn 'deal\.lexer\.Diagnostic' deal test --include='*.java' 2>/dev/null || true)"
if [ -n "$LEGACY_REFS" ]; then
  echo "  ERROR: references to the legacy deal.lexer.Diagnostic record remain:"
  echo "$LEGACY_REFS"
  exit 1
fi
DEFECT_NOTES="$(grep -rn '"internal range defect' deal --include='*.java' 2>/dev/null | grep -v '^deal/diagnostics/CompilerDiagnostic.java:' || true)"
if [ -n "$DEFECT_NOTES" ]; then
  echo "  ERROR: production sources create internal-defect notes outside the D9 normalization carrier:"
  echo "$DEFECT_NOTES"
  exit 1
fi
echo "  Migration gate scans pass (no legacy record, no legacy references, no production defect-note creation)."

# =========================================================================
# Run all tests
# =========================================================================

echo ""
echo "=== Running Diagnostic Range Tests ==="
java -ea -cp build deal.test.DiagnosticRangeTest

echo ""
echo "=== Running Diagnostic Classification Tests ==="
java -ea -cp build deal.test.DiagnosticClassificationTest

echo ""
echo "=== Running AST/Types Tests ==="
java -ea -cp build deal.test.AstAndTypesTest

echo ""
echo "=== Running Lexer Tests ==="
java -ea -cp build deal.test.LexerTest

echo ""
echo "=== Running Parser Tests ==="
java -ea -cp build deal.test.ParserTest

echo ""
echo "=== Running Checker Tests ==="
java -ea -cp build deal.test.CheckerTest

echo ""
echo "=== Running IR Dumper Tests ==="
java -ea -cp build deal.test.IrDumperTest

echo ""
echo "=== Running IR Golden Tests ==="
java -ea -cp build deal.test.IrGoldenTest

echo ""
echo "=== Running Type Descriptor Tests ==="
java -ea -cp build deal.test.TypeDescriptorTest

echo ""
echo "=== Running Backend Conformance Tests ==="
java -ea -cp build deal.test.BackendConformanceTest

echo ""
echo "=== Running JVM Backend Tests ==="
java -ea -cp build deal.test.JvmBackendTest

echo ""
echo "=== Running Lua Backend Tests ==="
java -ea -cp build deal.test.LuaBackendTest

echo ""
echo "=== Running Lua Backend Integration Tests ==="
java -ea -cp build deal.test.LuaBackendIntegrationTest

echo ""
echo "=== Running Lua ABI Unit Tests (JUnit4 + Hamcrest) ==="
java -ea -cp build:/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar org.junit.runner.JUnitCore deal.test.LuaAbiTest deal.test.LuaAbiBackendTest deal.test.CrossModuleTypingTest

echo ""
echo "=== Running Module System Tests ==="
java -ea -cp build deal.test.ModuleSystemTest

echo ""
echo "=== Running Stdlib .d.deal Parse Tests ==="
java -ea -cp build deal.test.StdlibDeclParseTest

echo ""
echo "=== Running Source Map Tests ==="
java -ea -cp build deal.test.SourceMapTest

echo ""
echo "=== Running Runtime Source Location Tests ==="
java -ea -cp build deal.test.RuntimeSourceLocationTest

echo ""
echo "=== Running Runtime Library Tests ==="
if command -v luajit &> /dev/null; then
  luajit test_runtime.lua
else
  echo "WARNING: luajit not found, skipping runtime library tests"
fi

echo ""
echo "=== Running Jsonable Runtime Tests ==="
if command -v luajit &> /dev/null; then
  luajit test_runtime_jsonable.lua
else
  echo "WARNING: luajit not found, skipping jsonable runtime tests"
fi

echo ""
echo "=== Running Standard Library Tests ==="
if command -v luajit &> /dev/null; then
  luajit test_stdlib.lua
else
  echo "WARNING: luajit not found, skipping standard library tests"
fi

echo ""
echo ""
echo "=== Running Async Nesting Stress Tests ==="
if command -v luajit &> /dev/null; then
  luajit test_async_nesting.lua
else
  echo "WARNING: luajit not found, skipping async nesting stress tests"
fi
java -ea -cp build deal.test.StdlibContractTest

echo ""
echo "=== Stdlib Golden IR Check ==="
GOLDEN_FILE="test/goldens/stdlib-declarations.ir.txt"
TEMP_FILE="/tmp/deal-stdlib-ir-$$.txt"
java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
if [ "${DEAL_UPDATE_GOLDENS}" = "true" ]; then
  cp "$TEMP_FILE" "$GOLDEN_FILE"
  echo "  Golden IR file updated"
else
  if diff -q "$GOLDEN_FILE" "$TEMP_FILE" > /dev/null 2>&1; then
    echo "  Golden IR file is current"
  else
    echo "  ERROR: Golden IR file differs from generated output!"
    echo "  Run 'DEAL_UPDATE_GOLDENS=true ./run_tests.sh' to update."
    diff "$GOLDEN_FILE" "$TEMP_FILE" || true
    rm -f "$TEMP_FILE"
    exit 1
  fi
fi
rm -f "$TEMP_FILE"

echo ""
echo "=== Running Conformance Tests ==="
java -ea -cp build deal.test.ConformanceTest test/conformance/

echo ""
echo ""
echo "=== Running JVM Conformance Tests (ISSUE-0102 origin — ISSUE-0168 capability accounting) ==="
java -ea -cp build deal.test.JvmConformanceTest test/conformance/

echo "=== All Tests Passed ==="
