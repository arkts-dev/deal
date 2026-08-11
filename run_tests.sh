#!/bin/bash
set -e

mkdir -p build

echo "=== Compiling DEAL AST, Types, Diagnostics, Lexer, Parser, Checker, Codegen, IR, Module System, and CLI ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/ir/*.java deal/module/*.java deal/Main.java

echo ""
echo "=== Compiling and Running Diagnostic Classification Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/module/*.java deal/Main.java test/StubModuleResolver.java test/DiagnosticClassificationTest.java
java -ea -cp build deal.test.DiagnosticClassificationTest

echo ""
echo "=== Compiling and Running AST/Types Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java test/AstAndTypesTest.java
java -ea -cp build deal.test.AstAndTypesTest

echo ""
echo "=== Compiling and Running Lexer Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java test/LexerTest.java
java -ea -cp build deal.test.LexerTest

echo ""
echo "=== Compiling and Running Parser Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java test/ParserTest.java
java -ea -cp build deal.test.ParserTest

echo ""
echo "=== Compiling and Running Checker Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java test/StubModuleResolver.java test/CheckerTest.java
java -ea -cp build deal.test.CheckerTest

echo ""
echo "=== Compiling and Running IR Dumper Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/ir/*.java test/StubModuleResolver.java test/IrDumperTest.java
java -ea -cp build deal.test.IrDumperTest

echo ""
echo "=== Compiling and Running Lua Backend Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java test/StubModuleResolver.java test/LuaBackendTest.java
java -ea -cp build deal.test.LuaBackendTest

echo ""
echo "=== Compiling and Running Lua Backend Integration Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java test/StubModuleResolver.java test/LuaBackendIntegrationTest.java
java -ea -cp build deal.test.LuaBackendIntegrationTest

echo ""
echo "=== Compiling and Running Module System Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/ir/*.java deal/module/*.java deal/Main.java test/ModuleSystemTest.java
java -ea -cp build deal.test.ModuleSystemTest

echo ""
echo "=== Compiling and Running Stdlib .d.deal Parse Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java test/StdlibDeclParseTest.java
java -ea -cp build deal.test.StdlibDeclParseTest

echo ""
echo "=== Running Runtime Library Tests ==="
if command -v luajit &> /dev/null; then
  luajit test_runtime.lua
else
  echo "WARNING: luajit not found, skipping runtime library tests"
fi

echo ""
echo "=== Running Standard Library Tests ==="
if command -v luajit &> /dev/null; then
  luajit test_stdlib.lua
else
  echo "WARNING: luajit not found, skipping standard library tests"
fi

echo ""
echo "=== Compiling and Running Conformance Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/ir/*.java deal/module/*.java deal/Main.java test/StubModuleResolver.java test/ConformanceTest.java
java -ea -cp build deal.test.ConformanceTest test/conformance/

echo ""
echo "=== Compiling and Running Backend Conformance Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/module/*.java deal/Main.java test/StubModuleResolver.java test/BackendConformanceTest.java
java -ea -cp build deal.test.BackendConformanceTest test/conformance/fixtures/

echo ""
echo "=== All Tests Passed ==="
