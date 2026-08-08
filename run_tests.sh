#!/bin/bash
set -e

mkdir -p build

echo "=== Compiling DEAL AST, Types, Lexer, Parser, Checker, Codegen, Module System, and CLI ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/module/*.java deal/Main.java

echo ""
echo "=== Compiling and Running AST/Types Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java test/AstAndTypesTest.java
java -ea -cp build deal.test.AstAndTypesTest

echo ""
echo "=== Compiling and Running Lexer Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java test/LexerTest.java
java -ea -cp build deal.test.LexerTest

echo ""
echo "=== Compiling and Running Parser Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java test/ParserTest.java
java -ea -cp build deal.test.ParserTest

echo ""
echo "=== Compiling and Running Checker Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java test/StubModuleResolver.java test/CheckerTest.java
java -ea -cp build deal.test.CheckerTest

echo ""
echo "=== Compiling and Running Lua Backend Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java test/StubModuleResolver.java test/LuaBackendTest.java
java -ea -cp build deal.test.LuaBackendTest

echo ""
echo "=== Compiling and Running Lua Backend Integration Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java test/StubModuleResolver.java test/LuaBackendIntegrationTest.java
java -ea -cp build deal.test.LuaBackendIntegrationTest

echo ""
echo "=== Compiling and Running Module System Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java deal/checker/*.java deal/codegen/lua/*.java deal/module/*.java deal/Main.java test/ModuleSystemTest.java
java -ea -cp build deal.test.ModuleSystemTest

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
echo "=== All Tests Passed ==="
