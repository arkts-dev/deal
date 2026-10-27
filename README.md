# DEAL Compiler v1.5

Compiler, semantic IR, runtimes and standard library targeting JavaScript,
LuaJIT and JVM. The sole language authority is
[`docs/spec-v1.5.md`](docs/spec-v1.5.md). Projects require
`"languageVersion": "1.5"`; the standard-library version is also `"1.5"`.

DEAL uses binary64 `number`, nominal classes with explicitly supplied
required fields, safe `unknown`, `Record<string, unknown>`, checked `as`
assertions and independent null/undefined/presence semantics. Async results
use `Promise<R>` notation; promises are not first-class data. Source is a
bounded TypeScript-syntax subset, not TypeScript's structural type system.

Removed source version directives, source `table`/`has`, C-style loops,
remainder, C FFI, bytes, integer/conversion intrinsics and class field
initializers are not compatibility features. There is no LLVM backend.

Executable v1.5 examples are in [`test/conformance/backend-runtime/language15/`](test/conformance/backend-runtime/language15/),
with a language guide in [`docs/learn-deal.ts`](docs/learn-deal.ts) and
an executable [capability demo](docs/deal-v1.5-capabilities.deal).

## Build and Test

```bash
./run_tests.sh --jobs 1
```

Compiles all production and test sources with `javac --release 25`
and executes the complete selection in `tools/gate-manifest.sh` with
assertions enabled, including the TypeScript compiler's syntactic/grammar
diagnostics and real JS/LuaJIT/JVM runtime records. Java suites run
sequentially; `--jobs` controls parallel work inside suites.
There is no alternate full-suite profile.

`run_tests.sh` first preflights its required tools (`javac`, `java`, `luajit`,
`node`, `gcc`). A missing required tool fails the run with the named
`TOOL_MISSING` outcome before any build work, and the LuaJIT/Node records and
JS production suites have no skip path: a run that omits a supported lane is
a failure, never a pass.

## Distribution

Build the packaged compiler with its matched v1.5 runtime and standard library:

```bash
./tools/build-release.sh
```

Verify the packaged compiler outside the checkout on JS, LuaJIT and JVM:

```bash
./tools/verify-release.sh [dist]
```

Verification executes the committed smoke project and rejects old versions,
missing or mismatched resources, broken deployment copies and failing executions.
Required tools must be available; missing tools fail rather than skip.

Run the independent TypeScript syntax/grammar check with:

```bash
node test/typescript/check-syntax.cjs
```

This uses the TypeScript compiler API on unmodified source text, not TypeScript
semantic type-checking. DEAL's nominal types and checked runtime assertions have
their own tests.

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
Minimum production coverage is **85% lines** and **75% branches**.
Reports are written to `build/coverage.csv`, `build/coverage.xml`, and
`build/coverage-html/index.html`.
