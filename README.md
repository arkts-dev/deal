# DEAL Compiler — AST and Type Representation

Shared data structures for the DEAL compiler: AST node hierarchy,
internal type representation, type canonicalization, type equality,
arity extension, source location tracking, sum-type helpers, and
the Visitor interface.

## Build and Test

```bash
./run_tests.sh --jobs 1
```

Compiles all production and remaining test sources with `javac --release 25`
and executes the complete selection in `tools/gate-manifest.sh` with
assertions enabled: 80 Java suites and five Lua/JS runtime records. Java
suites run sequentially; `--jobs` controls parallel work inside suites.
There is no alternate full-suite profile.

## Coverage

Provide JaCoCo agent and CLI jars compatible with Java 25 (tested with
JaCoCo 0.8.14), then run:

```bash
JACOCO_DIR=/path/to/jacoco ./coverage.sh --jobs 1
```

Coverage executes the same test runner and instruments Java subprocesses
without Java startup-option environment variables. Production classes compile into `build/deal`; test classes compile separately
into `build/test-classes`. Only `build/deal` enters the report; test helpers
and generated probe classes are excluded. Lua/JS execution is not counted by JaCoCo.
Minimum production coverage is **80% lines** and **69% branches**.
Reports are written to `build/coverage.csv`, `build/coverage.xml`, and
`build/coverage-html/index.html`.
