#!/bin/bash
set -e

echo "=== Compiling DEAL AST and Types ==="
javac --release 25 deal/ast/*.java deal/types/*.java

echo ""
echo "=== Compiling and Running Tests ==="
mkdir -p build
javac --release 25 -d build deal/ast/*.java deal/types/*.java test/AstAndTypesTest.java
java -ea -cp build deal.test.AstAndTypesTest
