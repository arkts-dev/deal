#!/bin/bash
set -e

mkdir -p build

echo "=== Compiling DEAL AST, Types, Lexer, and Parser ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java deal/parser/*.java

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
