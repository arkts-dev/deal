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
nested work stays serial, including under coverage. Conformance and compositions execute
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
JACOCO_DIR=/path/to/jacoco ./coverage.sh --jobs 4
```

Coverage uses the same selection and suite budget, including Java subprocesses.
Each Java process writes its own file in `build/jacoco-processes`; after all suites
succeed, bounded JaCoCo merges produce `build/jacoco.exec`. Empty files from killed
processes contain no dump; malformed nonempty dumps fail the merge.
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
`compilation.sourceBatch` identifies the exact consumed source revisions even
when diagnostics are empty; it covers observed files, not undiscovered inputs
or a claim that every analysis phase ran.
Bounded name/export/search/cycle summaries advertise retained detail inventories.
Embedded callers page `facts.<context>.details().page(new DiagnosticDetails.Request(inventoryId, offset, limit))`
(up to 32 observations per page); lookup observations use `observationDetails`.
Inventory IDs pin immutable producer observations; stale IDs reject, unavailable
inventories are null, and retrieval never repeats checks, searches or tool runs.
Amendment uses `compiler-protocol-v3` with revision-pinned context requests;
context reads do not grant edits. Missing-semicolon diagnostics can carry exact,
source-revision-pinned insertion offers; explicitly chosen offers still require
rechecking and do not expand amendment authority. Embedded callers can use
`StrictManifestParser.parseBatch` for independent manifest schema failures in
canonical order; strict syntax/duplicate/unknown-member faults and the default
`parse` entry remain fail-fast. Failed validation never publishes a manifest.
Oracle `ErrorSnapshot.diagnosticJson()` offers bounded approved metadata without
changing canonical trace text or `projectionLine()`.

General descriptor checks retain `validationPath` (JSON-pointer escaping), exact
`validationPathDepth`, `validationPathTruncated` and `validationMissingField`
from the current traversal. Prefixes retain at most 32 segments, with keys capped
at 128 UTF-16 units on JS/JVM or 128 bytes on Lua/native. They are not JSON-decoder
paths and do not reread rejected values.

Rejected `toFixed` digit domains retain the original binary64 `numericOperand`,
`numericParameter` and `numericConstraint`, including through message mutation/rethrow.
Malformed byte transport retains available `unicodeByteOffset` (one-based start
of the first rejected sequence) and `unicodeConstraint`, from the rejecting scanner.
Explicit defect-only carriers have no invented position; legal lone UTF-16 units
remain admitted. These byte positions are not language string indices.

Set `DEAL_DIAGNOSTICS_JSON=1` before execution to append a bounded
`DEAL_RUNTIME_DIAGNOSTIC:` JSON v2 record on uncaught runtime errors across all
four backends. It retains available Error metadata, causes/frames and original
JSON scanner byte offsets/reasons without traversing user values. Contextual
JSON failures also retain a JSON-pointer path prefix (root is empty), exact path
depth, truncation and missing-field status. Prefixes retain at most 32 segments;
keys are bounded to 128 UTF-16 units on JS/JVM or 128 bytes on Lua/native.
Selected host checks retain available signature/crossing/declaration facts and
known `deal-to-host`/`host-to-deal` direction with invocation/completion stage,
without assigning blame to propagated host Errors. Strict array reads and rejected writes retain the original binary64 index,
observed length and failed integrality/range constraint; strict reads also
identify rejected presence checks.
Default terminal output and exit behavior remain unchanged when disabled.

## Embedded timing

Scope `deal.module.CompilationTiming.observe((phase, nanos) -> ...)` with
try-with-resources. Observers are thread-local and nestable; durations are
monotonic and observer `RuntimeException`s do not fail compilation.
`codegen-total` includes `lowering`; timings overlap, exclude publication and
never enter generated artifacts.
