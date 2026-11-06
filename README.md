# DEAL Compiler

DEAL: a statically typed, embeddable language using a strict subset of
TypeScript syntax with runtime-enforced types. Targets: JavaScript, LuaJIT, JVM
and native Linux x86-64 executables.
Sole authority: [`docs/spec-v1.6.md`](docs/spec-v1.6.md).
Projects require `"languageVersion": "1.6"` and stdlib `"1.6"`.

Semantics differ from TypeScript: nominal classes, runtime-checked `as`
assertions and invariant arrays. DEAL uses
binary64 `number`, safe `unknown`, `Record<string, unknown>` and independent
null/undefined/presence semantics. Supply required fields explicitly.
`Promise<R>` denotes async results, not promises. Use DEAL APIs.

Removed source version directives, source `table`/`has`, C-style loops,
remainder, C FFI, bytes, integer/conversion intrinsics and class field
initializers are not compatibility features.

## Native backend (Linux x86-64)

`Backend.LLVM` compiles the one validated project lowering to a deterministic
textual LLVM module and publishes it with the complete C11 runtime; a
product-owned toolchain seam links the externally linked Linux x86-64
executable. The toolchain is pinned to `clang`/LLVM major 14, with the accepted
window LLVM 14-16 and typed-pointer textual IR; the emitted module declares the
target triple `x86_64-pc-linux-gnu`.

The reproducible command set for this backend, unchanged by checkout layout:

```bash
./tools/build-release.sh
./run_tests.sh --jobs 1
JACOCO_DIR=... ./coverage.sh --jobs 1
./tools/verify-release.sh [dist]
deal compile --backend llvm --output build/llvm <entry>
deal native build <entry> --opt-level 0|2 [--output <artifactsDir>] [--product <productDir>] [--host <file.c>...]
deal native run <entry> --opt-level 0|2
```

`deal compile --backend llvm` only publishes the deterministic textual set (the
project module, the complete C11 runtime and one generated header per imported
host declaration module) and never executes an external tool. `deal native
build` compiles with `Backend.LLVM` and then links the published set into
`<productDir>/native/<entry-stem>`; `deal native run` builds and executes it.
Products are durable and caller-owned, live outside the publication root, and a
failed rebuild preserves the previous executables byte-identical. The native
suites print per-fixture results with the optimization level and a final
pass/fail count; a missing required tool or artifact fails the run instead of
skipping a case.

Executable examples are in [`test/conformance/backend-runtime/`](test/conformance/backend-runtime/),
with a language guide in [`docs/learn-deal.ts`](docs/learn-deal.ts) and
an executable [capability demo](docs/deal-capabilities.deal).

## Build and Test

```bash
./run_tests.sh --jobs 4
```

Compiles all production and test sources with `javac --release 25`
and executes the complete selection in `tools/gate-manifest.sh` with
assertions enabled, including the TypeScript compiler's syntactic/grammar
diagnostics and real JS/LuaJIT/JVM runtime records. `--jobs` bounds suites
(default four); nested work/coverage stay serial.
No alternate full-suite profile exists. The native corpus alone uses the exact
LLVM-only omissions in [`test/llvm-known-skips.tsv`](test/llvm-known-skips.tsv):
the list is currently empty because upstream native completion covers the former
omissions. Any future skip is printed with its reason; case totals and requirement
denominators remain visible and skipped work earns no execution credit. Unknown
cases, stale requirement entries and unlisted failures fail. JS/LuaJIT/JVM
conformance is unchanged and has no exclusions. A successful selected gate is
not full LLVM certification.

Run the strict native corpus without this list (after building tests):

```bash
java -ea -cp build:build/test-classes deal.test.LlvmConformanceTest
```

`run_tests.sh` first preflights its required tools (`javac`, `java`, `luajit`,
`node`, `gcc`, `clang`, `llvm-as`, `llvm-dwarfdump`). A missing required tool fails the run with the named
`TOOL_MISSING` outcome before any build work, and the LuaJIT/Node records and
JS production suites have no skip path: a run that omits a supported lane is
a failure, never a pass.

## Distribution

Build the packaged compiler with its matched runtime and standard library:

```bash
./tools/build-release.sh
```

Verify the packaged compiler outside the checkout on JS, LuaJIT, JVM and the
native backend (compiled with `Backend.LLVM`, byte-equal published runtime
copies, product built outside the publication root and executed at `-O0` and
`-O2`, packaged debug information):

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
