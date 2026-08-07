#!/bin/bash
set -e

echo "=== Compiling DEAL AST, Types, and Lexer ==="
javac --release 25 deal/ast/*.java deal/types/*.java deal/lexer/*.java

echo ""
echo "=== Compiling and Running AST/Types Tests ==="
mkdir -p build
javac --release 25 -d build deal/ast/*.java deal/types/*.java test/AstAndTypesTest.java
java -ea -cp build deal.test.AstAndTypesTest

echo ""
echo "=== Compiling and Running Lexer Tests ==="
javac --release 25 -d build deal/ast/*.java deal/types/*.java deal/lexer/*.java test/LexerTest.java
java -ea -cp build deal.test.LexerTest
