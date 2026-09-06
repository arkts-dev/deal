---
id: ISSUE-0161
kind: task
title: Production async export invocation on LuaJIT and JVM
status: in_progress
priority: 3
labels:
  - deal-v1.2
  - host-abi
  - async
  - luajit
  - jvm
depends_on: []
parent: ISSUE-0111
workdir: WD-0900
mr: MR-0387
assignee: BOT-3227
review_cycles: 2
run_attempts: 0
integration_attempts: 0
total_runs: 6
depth: 1
interventions: 0
integration_fix: false
replan_requested: false
wiki:
  - deal-v1.2-int32-and-bytes-architecture
  - deal-v1.2-directives-and-c-ffi-declarations
sources:
  - arkestr-breakdown-proposal:BC-000005:E9
researched: true
external_researched: true
architecture_status: pending
breakdown_candidate_id: null
created: 2026-08-21T14:37:11Z
updated: 2026-09-05T11:28:33Z
---

## Update: the JVM recursive bytes closure landed; the E8 flip executed (review cycle 2 remediation)

This record updates the issue's criterion-3/4 tracking state per the
MR-0387 review cycle 2 finding (BOT-3221, 2026-09-05T11:28:09Z): the
JVM half of acceptance criterion 3 and criterion 4's E8 consumption
were the remaining Major deviation. The correction — land the JVM
recursive bytes closure (ISSUE-0160's E8 scope) and execute the
recorded flip requirements — is delivered in this MR: the JVM backend
now maps every bytes-bearing signature through the shared
`$DealRt.Bytes` carrier, the exact bytes-bearing oracle compiles and
executes on the JVM lane, the E6000 pins flipped to production
assertions, blocker 1 is removed, and criteria 3 (JVM half) and 4 (E8)
are marked met.

### Delivered in this MR

1. **JVM recursive bytes closure (E8) landed in `JvmBackend`** — the
   E6 five-helper bytes core (`__bytesNew`/`__bytesLength`/`__bytesGet`/
   `__bytesSet` with the pinned E8012/E8013 texts and the receiver/
   index/RHS-before-validation write order), `bytes`/`?bytes` mapping
   through `javaLocalType`/`nullableJavaType`/`silent*`/
   `javaArrayElementType`, `bytes[]` and `(bytes | null)[]` element
   helpers with the per-element-shape `$checkArray` rows, function
   values (sync and async) with bytes-bearing signatures through the
   shared `Fn..._Y_...` wrappers, bytes-typed class fields/defaults,
   and the table-only remainder of the old annotation gate. All ten
   `JVM-GAP-BYTES` skip entries were removed by the stale-skip gate
   (the fixtures pass their modes through the real pipeline) and the
   `jvm-bytes-buffer-ops` known-fail marker was promoted.
2. **The exact bytes-bearing oracle on the JVM lane** —
   `test/JvmAsyncExportInvokerTest.java`:
   `productionCompiledAsyncBytesOracleCompletesNull` compiles the exact
   criterion-3 oracle through the same production path and asserts
   `Result.Value("null", "null")` through the production
   `JvmAsyncExportInvoker`; the no-await (E3014), incorrect-output
   (TEST_FAIL), and no-call (TEST_FAIL) mutation controls execute with
   real bytes on the JVM lane.
3. **The D12 async-bytes record on the JVM lane** —
   `test/JvmRegistryAsyncExportBoundaryTest.java`:
   `jvmLaneAsyncBytesRecordExecutesThroughTheProductionInvokerCompletingNull`
   executes the committed `async-bytes-oracle` record through the
   production ProjectLocator/orchestrator/invoker and asserts
   `Result.Value("null", "null")`; the JVM lane outcome flips from
   `BLOCKED(ISSUE-0160)` to `SUCCESS` and the committed lane set is
   `{luajit: SUCCESS, jvm: SUCCESS}`.
4. **Staged-state and catalog consequences re-pinned truthfully** —
   `test/JvmLaneStatePinTest` (JVM lane summary `passed 266, failed 0,
   skipped 35 ... 88.4%`), `test/JvmConformanceTest` (no JVM-GAP-BYTES
   group), `test/HistoricalRegressionCatalog` (the array-delete pin
   re-anchored to the post-merge spans with its re-derived baseline
   digest), and `test/JvmBackendTest` (the for-of boundary pin now
   names the table carrier and the bytes-signature for-of compiles and
   runs).

### Recorded blockers

- **None.** Blocker 1 (ISSUE-0160, E8) is removed: the JVM recursive
  bytes closure is landed in this tree and the recorded flip
  requirements executed. The E3 record authoring, request supply,
  registry gating, and the architecture-owned `FeatureBackendMatrix`
  remain parent-owned (ISSUE-0165 family; ISSUE-0346's boundary) and
  this issue consumes the landed boundary on both lanes — no criterion
  depends on them.

### Tracking state

- Criterion 1 (both-backend init/main exactly once, single selection
  and invocation, canonical E4 completion checking, sealed three-
  outcome distinction): **met** (LuaJitAsyncExportInvokerTest,
  JvmAsyncExportInvokerTest).
- Criterion 2 (every negative selection shape with byte-identical
  pinned reasons, no false runtime-error pass): **met** on both
  backends.
- Criterion 3 (production `async()->null` oracle assigning and
  containerizing legal first-class `async(bytes)->bytes` values,
  invoking and awaiting one, checking bytes identity/content in
  source, completing null): **met on LuaJIT and JVM** — the exact
  bytes-bearing oracle executes through both production invokers with
  `Result.Value("null", "null")` (LuaJitAsyncExportInvokerTest.
  productionCompiledAsyncBytesOracleCompletesNull;
  JvmAsyncExportInvokerTest.productionCompiledAsyncBytesOracleCompletesNull).
- Criterion 4 (full production scenario consumes E3 metadata, E6
  Lua/direct bytes behavior, and E8 JVM closure; fails for no-call,
  no-await, incorrect bytes output, backend omission, or a broken
  dependency): **met** — the committed E3 record projects execute
  through the production locator/orchestrator/invoker on both lanes
  (RegistryAsyncExportBoundaryTest; JvmRegistryAsyncExportBoundaryTest),
  the bytes mutation controls (no-call/no-await/incorrect output)
  fail on both backends with the exact expected signals, the
  broken-dependency record propagates the exact DEAL error on both
  backends, backend omission fails the mirrored family gate, and the
  E8 closure executes the bytes-bearing oracle on the JVM lane.

## Update: bytes-array past-end nil parity (review cycle 3 remediation)

This update addresses the MR-0387 review cycle 3 finding (BOT-3309,
2026-09-06T04:33:39Z): the co-landed E8 bytes closure's array helpers
diverged from LuaJIT on the past-end nil — `__bytesOrNullArrayRead`
raised E8001 "expected bytes, got null" past the end where LuaJIT
reads nil into the nullable `bytes | null` element with no boundary
failure, and a `bytes[]` read at a `bytes | null` target fell through
to the throwing element-typed helper instead of yielding the DEAL
null (the reviewer's production-CLI reproduction: probe(xs[0]) runs
on luajit but dies with E8001 on JVM). The correction, delivered in
this MR:

- `deal/codegen/jvm/JvmBackend.java` — `emitBytesRefArrayHelpers` now
  emits the past-end branch conditionally: `return null` for the
  `(bytes | null)[]` helper (the `__intOrNullArrayRead` convention)
  and the pinned E8001 raise only for the non-nullable `bytes[]`
  helper; a new `__bytesArrayReadBoxed` helper (null past the end,
  unchanged E8002 negative gate and element proof) is emitted with the
  `bytes[]` helper set and wired through `boxedArrayReadHelper`/
  `arrayBoxedJavaType` (`$DealRt.Bytes`), so the `bytes[]`-into-
  `bytes | null` target route and the discarded standalone read yield
  the DEAL null exactly like the settled primitive-array convention.
- Pins: `test/conformance/fixtures/jvm-arrays-slice.json`
  `jvm-bytes-arr-past-end-null-parity` and
  `jvm-bytes-arr-negative-read-e8002` execute both shapes on real
  luajit and the emitted JVM artifact (in-bounds controls, both
  past-end target shapes, both discards, and the unchanged E8002
  negative-index gate); `test/JvmBackendTest.java`
  `testBytesArrayPastEndReadsYieldTheDealNull` pins the same shapes,
  the retained element-typed E8001 at a non-nullable `bytes` target,
  and the exact emitted helper text; the array-delete catalog anchor
  re-located to the post-merge spans with its re-derived baseline
  digest (HistoricalRegressionCatalog). The rebase onto the canonical
  revision that landed ISSUE-0357's differential-gate lane corpus test
  required its truthful post-flip re-pins: the six runtime-ok
  JVM-GAP-BYTES skip-registry entries retired from `JvmLane` (the four
  runtime-error bytes entries remain — the JVM lane still cannot
  serialize a complete DEALRuntimeError snapshot, ISSUE-0276), the
  registry count re-pinned 44 → 38 in `JvmLaneTest` and
  `DifferentialGateLanesCorpusTest`, and the JVM lane counters
  re-pinned 194/107 → 200/101.

No acceptance criterion changed: the fix restores the epic's
LuaJIT/JVM bytes equivalence on the bytes-array indexing shapes the
E8 closure made compilable.
