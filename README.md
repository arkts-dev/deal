# DEAL Compiler — AST and Type Representation

Shared data structures for the DEAL compiler: AST node hierarchy,
internal type representation, type canonicalization, type equality,
arity extension, source location tracking, sum-type helpers, and
the Visitor interface.

## Build and Test

```bash
./run_tests.sh
```

This compiles all sources with `javac --release 25` and runs the
unit tests with assertions enabled.
