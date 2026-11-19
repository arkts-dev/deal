# DEAL Compiler

DEAL is a statically typed, embeddable language with a strict TypeScript syntax
subset and runtime-enforced types. Targets: JavaScript, LuaJIT, JVM and native
Linux x86-64. Authority: [v1.6 specification](docs/spec-v1.6.md).
Projects use `"languageVersion": "1.6"` and stdlib `"1.6"`.

Classes are nominal, arrays invariant and `as` assertions checked at runtime.
Numbers are binary64; null, undefined and field absence are distinct.
`Promise<R>` denotes async results, not first-class promises. Use DEAL APIs,
not JavaScript/TypeScript APIs.

See the [language guide](docs/learn-deal.ts),
[capability demo](docs/deal-capabilities.deal),
[runtime examples](test/conformance/backend-runtime/) and [agent skill](skills/README.md).

## Build and test

```bash
./run_tests.sh --jobs 4
node test/typescript/check-syntax.cjs
```

The test runner compiles with `javac --release 25`, enables assertions and runs
`tools/gate-manifest.sh`. `--jobs` bounds concurrent suites (default four);
nested work and coverage stay serial. Conformance and compositions execute
without exclusions. Missing tools, hosts, completions or runtime-requirement
evidence fail the run.

Required tools: `javac`, `java`, `luajit`, `node`, `gcc`, `clang`, `llvm-as` and
`llvm-dwarfdump`. The separate syntax command uses TypeScript's parser/grammar
checks, not its type checker.

Production compiler sources also support `javac --release 21` for embedded
packaging; test/release tooling uses Java 25.

## Native backend

LLVM output targets `x86_64-pc-linux-gnu` with typed-pointer IR. The toolchain
pins LLVM 14 and accepts majors 14–16.

```bash
deal compile --backend llvm --output build/llvm <entry>
deal native build <entry> --opt-level 0|2 [--output <artifactsDir>] [--product <productDir>] [--host <file.c>...]
deal native run <entry> --opt-level 0|2
```

`compile` publishes one deterministic LLVM module, the C11 runtime and imported
host headers without invoking external tools. `native build` links them into
`<productDir>/native/<entry-stem>` outside the publication root; a failed rebuild
preserves the previous executable. `native run` builds and executes it.

Run the native corpus after building tests:

```bash
java -ea -cp build:build/test-classes deal.test.LlvmConformanceTest
```

`DEAL_GC_STRESS=1` starts collection whenever idle and advances the normal bounded
mark/sweep slices. Native tests also use explicit `deal_gc_collect_full`.

## Distribution

```bash
./tools/build-release.sh
./tools/verify-release.sh [dist]
```

The package includes matched runtimes and stdlib. Verification runs the committed
smoke project outside the checkout on every backend, including native O0/O2,
and rejects version/resource mismatches and failed executions. Neither script
is part of the development gate.

## Coverage

Use Java-25-compatible JaCoCo agent/CLI jars (tested with 0.8.14):

```bash
JACOCO_DIR=/path/to/jacoco ./coverage.sh --jobs 1
```

Coverage uses the same test selection and instruments Java subprocesses.
Only production classes in `build/deal` count; tests and generated probes do not.
Minima: **85% lines**, **75% branches**. Reports: `build/coverage.csv`,
`build/coverage.xml`, `build/coverage-html/index.html`.

## Diagnostics

`compile` and `native build|run` accept `--diagnostics-json <path>` for JSON v2:
source diagnostics include full UTF-16 ranges, notes, producer-owned facts,
classification and available snapshot excerpts/occurrence IDs. Terminal output
uses messages and concise producer notes; structured `facts.guidance` retains
additional rule explanations and intent-dependent examples. Operational
failures and actual compilation/publication outcomes are separate fields.
Amendment uses `compiler-protocol-v3` with revision-pinned context requests;
context reads do not grant edits. Embedded callers can use
`StrictManifestParser.parseBatch` for independent manifest schema failures in
canonical order; strict syntax/duplicate/unknown-member faults and the default
`parse` entry remain fail-fast. Failed validation never publishes a manifest.
Oracle `ErrorSnapshot.diagnosticJson()` offers bounded approved metadata without
changing canonical trace text or `projectionLine()`.

Set `DEAL_DIAGNOSTICS_JSON=1` before execution to append a bounded
`DEAL_RUNTIME_DIAGNOSTIC:` JSON v2 record on uncaught runtime errors across all
four backends. It retains available Error metadata, causes/frames and original
JSON scanner byte offsets/reasons without traversing user values. Contextual
JSON failures also retain a JSON-pointer path prefix (root is empty), exact path
depth, truncation and missing-field status. Prefixes retain at most 32 segments;
keys are bounded to 128 UTF-16 units on JS/JVM or 128 bytes on Lua/native.
Selected host checks retain available signature/crossing/declaration facts without
assigning blame to propagated host Errors. Strict array reads retain the rejected
binary64 index, observed length and failed integrality/range/presence constraint.
Default terminal output and exit behavior remain unchanged when disabled.

## Embedded timing

Scope `deal.module.CompilationTiming.observe((phase, nanos) -> ...)` with
try-with-resources. Observers are thread-local and nestable; durations are
monotonic and observer `RuntimeException`s do not fail compilation.
`codegen-total` includes `lowering`; timings overlap, exclude publication and
never enter generated artifacts.
