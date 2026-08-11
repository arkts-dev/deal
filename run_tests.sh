#!/bin/bash
set -e

mkdir -p build

# =========================================================================
# Single compilation step: compile all source and test files at once
# =========================================================================
echo "=== Compiling all DEAL sources and tests ==="
javac --release 25 -d build \
  deal/ast/*.java \
  deal/types/*.java \
  deal/diagnostics/*.java \
  deal/lexer/*.java \
  deal/parser/*.java \
  deal/checker/*.java \
  deal/codegen/*.java \
  deal/codegen/lua/*.java \
  deal/ir/*.java \
  deal/module/*.java \
  deal/Main.java \
  test/StubModuleResolver.java \
  test/DiagnosticClassificationTest.java \
  test/AstAndTypesTest.java \
  test/LexerTest.java \
  test/ParserTest.java \
  test/CheckerTest.java \
  test/IrDumperTest.java \
  test/IrGoldenTest.java \
  test/TypeDescriptorTest.java \
  test/BackendConformanceTest.java \
  test/LuaBackendTest.java \
  test/LuaBackendIntegrationTest.java \
  test/ModuleSystemTest.java \
  test/StdlibDeclParseTest.java \
  test/SourceMapTest.java \
  test/RuntimeSourceLocationTest.java \
  test/StdlibContractTest.java \
  test/GenerateStdlibGoldenIr.java \
  test/ConformanceTest.java

# =========================================================================
# Run all tests
# =========================================================================

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
echo "=== Running Lua Backend Tests ==="
java -ea -cp build deal.test.LuaBackendTest

echo ""
echo "=== Running Lua Backend Integration Tests ==="
java -ea -cp build deal.test.LuaBackendIntegrationTest

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
echo "=== All Tests Passed ==="
