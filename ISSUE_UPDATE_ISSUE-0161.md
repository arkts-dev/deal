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
review_cycles: 5
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
updated: 2026-09-11T23:41:56Z
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

1. **JVM recursive bytes closure (E8) landed in `JvmBackend`** — on the
   canonical MR-0370 base the landed ISSUE-0158 five-helper bytes core
   (`bytesNew`/`bytesLength`/`bytesGet`/`bytesSet` with the pinned
   E8012/E8013 texts and the receiver/index/RHS-before-validation
   write order) is retained, and the E8 closure lands on top of it:
   `bytes`/`?bytes` mapping through `javaLocalType`/`nullableJavaType`/
   `silent*`/`javaArrayElementType`, `bytes[]` and `(bytes | null)[]`
   element helpers with the per-element-shape `$checkArray` rows,
   function values (sync and async) with bytes-bearing signatures
   through the shared `Fn..._Y_...` wrappers, bytes-typed class
   fields/defaults, and the table-only remainder of the old annotation
   gate. All ten `JVM-GAP-BYTES` skip entries were removed by the
   stale-skip gate (the fixtures pass their modes through the real
   pipeline) and the `jvm-bytes-buffer-ops` known-fail marker was
   promoted.
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
  registry count re-pinned 39 → 38 in `JvmLaneTest` and
  `DifferentialGateLanesCorpusTest`, and the JVM lane counters
  re-pinned 199/102 → 200/101.

No acceptance criterion changed: the fix restores the epic's
LuaJIT/JVM bytes equivalence on the bytes-array indexing shapes the
E8 closure made compilable.

## Update: rebase reconciliation onto the canonical MR-0370/MR-0383 base

The canonical revision landed MR-0370 (ISSUE-0158, signed-int32 and
bytes core across both backends) and MR-0383 (ISSUE-0157, the strict
v1.2 feature catalog and backend matrix), whose JVM bytes core
overlapped this MR's E8 closure. The rebase reconciliation keeps the
canonical ISSUE-0158 core (the `bytesNew`/`bytesLength`/`bytesGet`/
`bytesSet` helpers and their `b.length`/`b[i]`/`b[i]=v`/`bytes(n)`
call sites, including the reserved-helper-name collision rejection)
and lands the E8 closure on top: bytes-bearing sync/async function
signatures, `bytes[]` and `(bytes | null)[]` per-element-shape
helpers with the or-null past-end nil parity (cycle-3 remediation),
and the table-only remainder of the function-signature gate. The
bytes-descriptor-boundary JVM-GAP-BYTES registry entry retired with
the closure (38 live entries; JVM lane counters 200/101), and the
HistoricalRegressionCatalog array-delete anchors re-located to the
post-merge spans (JvmBackend.java:9899; 15609-15613) with the
tree-derived baseline digest. No acceptance criterion changed; the
full gate exits 0 with all four async-export suites green.

## Update: rebase onto the canonical 52082f0c base (engine-imported revision)

The engine imported canonical revision `52082f0c` (MR-0418 release
determinism, MR-0376/ISSUE-0402 remediation, MR-0422 Lua extern-C
restore with FFIGEN fixtures, MR-0421 ClassOpsExecutor CLASS_NEW
LOCAL lowering, MR-0420 zero-skip flip, ISSUE-0361 promotions,
ISSUE-0477 gate closure, ISSUE-0512/ISSUE-0520). The MR rebased with
one conflict in `DifferentialGateLanesCorpusTest` (commit `0789e93e`):
the canonical ISSUE-0477/ISSUE-0361 line had moved the pre-flip
accounting pins (KNOWN_FAILURES_TRACKED 1→0, registry 38→39, luajit
268/33→269/32, jvm 200/101→199/102), and the E8-closure delta was
re-applied on top of the canonical state — the
bytes-descriptor-boundary registry entry retires with the closure
(registry 39→38, jvm counters 199/102→200/101), the known-fail
counter stays 0 (ISSUE-0477 promoted the last marker), and the
luajit/js counters stay at the canonical 269/32. No acceptance
criterion or source contract changed; every re-pin is tree-derived
from the real gate run on the rebased tree.

### Verification on the final rebased commits (rebase tip 3f70f485 and the record-update commit; the gate was re-run on the final tree and produced the same evidence)

- Engine gate `flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh
  --jobs 1`: exit 0, `=== All Tests Passed ===`.
- Async-export suites: LuaJitAsyncExportInvokerTest OK (49),
  RegistryAsyncExportBoundaryTest OK (5), JvmAsyncExportInvokerTest
  OK (38, incl. the bytes oracle asserting Result.Value("null",
  "null")), JvmRegistryAsyncExportBoundaryTest OK (6); Lua async
  export driver 14/14 PASS lines.
- DifferentialGateLanesCorpusTest 823/0: full three-lane gate 301
  verdicts, 127 differential failures, 38 tracked non-fatal — the
  re-pinned registry set matches the live JvmLane registry exactly.
- JvmLaneStatePinTest 27/0 with the real LuaJIT and JVM lane
  subprocess runs (JVM summary pinned passed 266, failed 0, skipped
  35 ... 88.4%, denominator 301).
- Backend Conformance: Total 328, Passed 328, Failed 0, Skipped 0,
  KnownFailures 1; backend-runtime gates LuaJIT 99/99, JVM 260/260,
  JS 42/42, zero skips.
- Historical / Legacy-Profile / Legacy-Capability catalog tests pass
  (the array-delete anchors and baseline digests hold against the
  post-rebase JvmBackend spans).

`git status` is clean; the committed diff contains only the intended
backend/test/fixture/record changes.

## Update: rebase onto the canonical b1931b66b base (engine-imported revision, MR-0441 and the ISSUE-0502/0546/0547/0315 lane)

The engine imported canonical revision `b1931b66b` (MR-0441/ISSUE-0315
canonical descriptor and identity propagation through compiler
surfaces, the Lua runtime, and the JVM emitted checks; ISSUE-0502
gap-suite runtime population; ISSUE-0546/0547 JVM bytes boundary and
container closure; ISSUE-0540/0541 default-plan carriers; the
ISSUE-0305/0331/0324/0490/0530/0537 lane landings). The rebase
replayed the 12 MR-0387 commits onto it. The reconciliation is
structural, not semantic:

1. `deal/codegen/jvm/JvmBackend.java` — the canonical bytes-array
   machinery that landed while the MR was pending (ISSUE-0547: the
   fixed `__BytesArray`/`__BytesOrNullArray` carriers with
   `$DealRt.Bytes[]` storage, the five native helpers including the
   past-end nil parity — `__bytesOrNullArrayRead` and
   `__bytesArrayReadBoxed` yield the DEAL null past the end — and the
   direct `arrayReadHelper`/`boxedArrayReadHelper`/`arrayBoxedJavaType`
   bytes arms) supersedes this MR's per-element-shape Object-storage
   bytes helpers, so the canonical carriers stay exclusively; the MR's
   E8 function-signature closure lands on top as the remaining delta
   (`hasBytesOrTableCarrier` → `hasTableCarrier`, table-only
   rejection at function-type annotations with the updated messages,
   the updated class-field/README wording). The cycle-3 past-end nil
   parity is therefore delivered by the canonical helpers and pinned
   by this MR's fixtures and JvmBackendTest byte pins.
2. `test/JvmConformanceTest.java` — the JVM-GAP-BYTES skip entry for
   `bytes-descriptor-boundary.deal` retires with the closure (the
   fixture passes the real pipeline), the gap keeps no entries, and
   the ISSUE-0502/ERROR-LITERAL-DEFAULTS entry stays.
3. `test/JvmLaneStatePinTest.java` — the JVM lane summary re-pins to
   the tree-derived run: `denominator 343 ... passed 307, failed 0,
   skipped 36 ... pass rate 89.5%` (the promoted descriptor-boundary
   fixture moves 306/37 → 307/36 over the unchanged 343 denominator);
   the LuaJIT lane pins stay at the canonical `Total: 538, Passed:
   538` / `349/349` / 587 v1.2-credit results.
4. `test/HistoricalRegressionCatalog.java` — the array-delete
   code-contract row re-locates to the post-rebase spans
   (`JvmBackend.java:10073`, `15591-15595`) with the tree-derived
   baseline digest `06a0e04c...` (the digest covers the locator
   strings, so the relocation re-derives it over the unchanged span
   bytes).
5. `deal/test/conformance/DifferentialGateLanesCorpusTest.java` and
   `JvmLaneTest.java` — the E8 delta re-applied over the canonical
   pins: the bytes-descriptor-boundary registry entry retires from
   the live set (SKIP_REGISTRY_ENTRIES 45 → 44, TRACKED_REGISTRY 45 →
   44), the JVM per-backend counters move 230/113 → 231/112 (the
   fixture now passes the jvm lane), and the luajit/js counters stay
   at the canonical 311/32 and 313/30.
6. `test/JvmBackendTest.java` — the for-of boundary pin names the
   table carrier with the new `table carriers` message wording, the
   bytes-signature for-of compiles and runs, and the async/table
   annotation pins keep the `table carriers` substring.

No acceptance criterion changed; criteria 1-4 stay met on both
backends. Every re-pin is tree-derived from the real gate run on the
rebased tree.
## Update: rebase onto the canonical 2538b1709 base (engine-imported revision, MR-0459 JVM bytes async closure)

The canonical line landed far past the MR's approved base (b1931b66b)
while MR-0387 was pending: MR-0459/ISSUE-0549 (JVM async bytes closure
and await completion), MR-0452/ISSUE-0239 (production semantic-IR
emission and atomic publication), ISSUE-0306 (recursive bytes-bearing
type closure on JVM), MR-0456/ISSUE-0544 (backend evaluator lowering),
MR-0455/ISSUE-0517 (class construction integration tail), MR-0454/
ISSUE-0543 (merged runtime dependency graph), MR-0446/ISSUE-0542
(DefaultSemanticSerializer), MR-0432/ISSUE-0531 (dynamic invocation IR),
MR-0431/ISSUE-0515 (JSON class walkers), MR-0442/ISSUE-0302 (std/json
boundary and @jsonable completion on JVM), MR-0380/ISSUE-0165
(production ISSUE-0111 feature/native release gate), MR-0436/ISSUE-0504
(host ABI conversion), MR-0449/ISSUE-0236 (call state machine), and the
ISSUE-0557/0561/0316 lanes. The rebase replayed all 14 MR-0387 commits
onto 2538b1709. The reconciliation is structural, not semantic:

1. `deal/codegen/jvm/JvmBackend.java` — the canonical ISSUE-0306/
   ISSUE-0549 bytes machinery (the E8 recursive bytes-bearing function
   wrapper closure, the async bytes closure, the `__BytesArray`/
   `__BytesOrNullArray` carriers with the past-end nil parity, and the
   `hasTableCarrier` table-only annotation gate) supersedes this MR's
   co-landed bytes closure entirely: the cycle-2 E8 flip commits and
   the cycle-3 past-end parity commit resolve as no-ops for the
   backend source, and the canonical helpers stay exclusively. The
   MR's remaining production delta is the async-export host invocation
   surface — the `ASYNC_EXPORT_HOST_ARG` argv marker, the reserved
   `$AsyncExportHost` launcher, the `$exports` registry,
   `$asyncExportSelect`, `$asyncExportHost`, the closed envelope
   emitters, the throwable classifier, the JSON value encoder, and the
   `$check` null row — landing on top of the canonical structure. The
   cycle-3 parity pins (`test/JvmBackendTest.
   testBytesArrayPastEndReadsYieldTheDealNull` and the
   `jvm-bytes-arr-past-end-null-parity` /
   `jvm-bytes-arr-negative-read-e8002` fixtures) are retained verbatim:
   the canonical emitted helpers match their pinned text exactly.
2. `test/HistoricalRegressionCatalog.java` — the array-delete
   code-contract row re-locates to the post-change spans
   (`JvmBackend.java:10515`, `16428-16432`; the async-export surface
   inserts ~595 lines before both anchors) with the tree-derived
   baseline digest `751ff2314ae38bc85e426123c08c037535c597bf7d0357430680d5a1ca8f8939`
   (the digest covers the locator strings, so the relocation re-derives
   it over the unchanged span bytes).
3. `deal/test/conformance/DifferentialGateLanesCorpusTest.java` /
   `JvmLane.java` / `JvmLaneTest.java` — no MR delta remains: the
   canonical line already retired the bytes-descriptor-boundary
   registry entry with the ISSUE-0306 closure (registry 44, luajit
   322/32, jvm 242/112, js 324/30), so the MR's pre-flip re-pin commits
   resolve against the canonical pins.
4. `test/JvmConformanceTest.java` / `test/JvmLaneStatePinTest.java` /
   `deal/codegen/jvm/README.md` — no MR delta remains: the canonical
   trees already record the ISSUE-0160 closure history, the
   `JVM-GAP-BYTES` retirement, and the post-closure lane summary, so
   the MR's reconciliation commits resolve as no-ops for these files.
5. `test/conformance/fixtures/jvm-v1.2-known-fail.json` — the
   `jvm-bytes-buffer-ops` promotion description re-states the rebased
   history truthfully (ISSUE-0158 core, ISSUE-0306/0549 closure; the
   promotion re-verified by this rebase).

No acceptance criterion changed; criteria 1-4 stay met on both
backends. Every re-pin is tree-derived from the real gate run on the
rebased tree.

### Verification on the final rebased commit

- Engine gate `flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh
  --jobs 1`: exit 0 (`=== All Tests Passed ===`).
- Async-export suites: JvmAsyncExportInvokerTest OK (38, incl.
  `productionCompiledAsyncBytesOracleCompletesNull` asserting
  Result.Value("null", "null") with the no-await E3014 /
  incorrect-output TEST_FAIL / no-call TEST_FAIL controls over real
  bytes on the JVM lane), LuaJitAsyncExportInvokerTest OK (49),
  JvmRegistryAsyncExportBoundaryTest OK (6), RegistryAsyncExportBoundaryTest
  OK (5).
- Bytes parity pins green on both backends:
  `jvm-bytes-arr-past-end-null-parity` and
  `jvm-bytes-arr-negative-read-e8002` (real luajit + emitted JVM
  artifact), and `JvmBackendTest.testBytesArrayPastEndReadsYieldTheDealNull`
  against the canonical emitted helper text.
- HistoricalRegressionCatalogTest 152/0 with the re-derived digest and
  relocated anchors.

## Update: rebase onto the canonical fd7913577 base (engine-imported revision, ISSUE-0303 JVM host ABI completion)

The canonical line moved past the MR's approved base (2538b1709) while
MR-0387 was pending review: it landed ISSUE-0303 (JVM host ABI
completion — array, function, class, and pre-wrapped export shapes,
the E2 identity-gate reconciliation, the
`deal/codegen/js/HostModuleDeclarations` → `deal/codegen/` move, and
the local review scratch-tree `.gitignore` exclusion). The rebase
replayed all 15 MR-0387 commits onto fd7913577. The reconciliation is
structural, not semantic:

1. `test/HistoricalRegressionCatalog.java` — the single conflict, at
   the final record commit: the ISSUE-0303 reconciliation commits had
   moved the `jvm-array-index-delete-e6000` anchors to
   (`JvmBackend.java:10665`, `16926-16930`) with the tree-derived
   digest `a9dddf1a...`, while the MR's record commit carried the
   2538b1709 anchors (`10515`, `16428-16432`, digest `751ff231...`).
   The MR's own async-export host surface inserts text before both
   anchors, so the rebased tree holds them at (`11260`,
   `17521-17525`) — neither side's locators survive verbatim. The
   conflict was resolved with the tree-verified post-rebase anchors
   and the re-derived baseline digest
   `b4e4a897bad48e48f7847fa30c35123f57d6011b09df38091313523e07fdb4b0`
   (derived from the real catalog run; HistoricalRegressionCatalogTest
   152/0 — the pinned locator strings participate in the digest, so
   the relocation re-derives it over the unchanged span bytes).
2. `deal/codegen/jvm/JvmBackend.java` — auto-merged without conflict:
   the canonical ISSUE-0303 host-ABI completion (host function/array/
   class/pre-wrapped export shapes and boundary validation) and this
   MR's async-export host surface (the `ASYNC_EXPORT_HOST_ARG` argv
   marker, the reserved `$AsyncExportHost` launcher, `$exports`,
   `$asyncExportSelect`, `$asyncExportHost`, the closed envelope
   emitters, the throwable classifier, the JSON value encoder, and the
   `$check` null row) are disjoint emission surfaces; the async-export
   surface lands on top of the canonical structure unchanged.
3. `test/JvmBackendTest.java` — merged additively: this MR's
   `testBytesArrayPastEndReadsYieldTheDealNull` and its registration
   join the ISSUE-0303 host-ABI test additions; the pinned emitted
   helper text holds verbatim against the canonical helpers.
4. `deal/test/conformance/DifferentialGateLanesCorpusTest.java` /
   `JvmLane.java` / `JvmLaneTest.java` / `test/JvmConformanceTest.java`
   / `test/JvmLaneStatePinTest.java` — no MR delta remains: the
   canonical pins already carried the post-closure state, so the MR's
   pre-flip re-pin commits resolve against the canonical pins.
5. `test/BackendConformanceTest.java` / `test/ProjectIntegrationGatesTest.java` /
   `test/conformance/fixtures/jvm-arrays-slice.json` /
   `test/conformance/fixtures/jvm-v1.2-known-fail.json` — the MR's
   additions (the bytes-array parity fixture rows, the javadoc
   re-pins, the possessive-quantifier regex, the promoted
   `jvm-bytes-buffer-ops` description) merge cleanly over the
   canonical ISSUE-0303 changes.

No acceptance criterion changed; criteria 1-4 stay met on both
backends. Every re-pin is tree-derived from the real gate run on the
rebased tree.

### Verification on the final rebased commit (bd0e55a95)

- Engine gate `flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh
  --jobs 1`: exit 0, `=== All Tests Passed ===` — verified both on a
  fresh full compile (build stamp removed) and on the reused build.
- Async-export suites: LuaJitAsyncExportInvokerTest OK (49),
  RegistryAsyncExportBoundaryTest OK (5), JvmAsyncExportInvokerTest
  OK (38, incl. `productionCompiledAsyncBytesOracleCompletesNull`
  asserting Result.Value("null", "null") with the no-await E3014 /
  incorrect-output TEST_FAIL / no-call TEST_FAIL controls over real
  bytes on the JVM lane), JvmRegistryAsyncExportBoundaryTest OK (6).
- Bytes parity pins green on both backends:
  `jvm-bytes-arr-past-end-null-parity` and
  `jvm-bytes-arr-negative-read-e8002` (real luajit + emitted JVM
  artifact), and `JvmBackendTest.testBytesArrayPastEndReadsYieldTheDealNull`
  against the canonical emitted helper text.
- HistoricalRegressionCatalogTest 152/0 with the re-derived digest
  `b4e4a897...` and the relocated anchors (`JvmBackend.java:11260`,
  `17521-17525`).
- `git status` clean and `git diff --check` clean; the committed diff
  contains only the intended backend/test/fixture/record changes.

## Update: rebase onto the canonical 229efbcd base (engine-imported revision, ISSUE-0307 + ISSUE-0570)

The engine imported canonical revision `229efbcd4` (two further
canonical landings past the MR's approved base fd7913577:
`587f4a435` ISSUE-0307, the JVM completion gate closure — zero skips,
100% denominator, C FFI regression; `229efbcd4` ISSUE-0570, lifting
class-element host arrays onto the shared per-class carrier and
removing the remaining import-site E6000). The rebase replayed all 17
MR-0387 commits onto 229efbcd. The reconciliation is structural, not
semantic:

1. `test/HistoricalRegressionCatalog.java` — the only conflict, in the
   `jvm-array-index-delete-e6000` code-contract row, at the 2538b1709
   record commit: the canonical line had moved the anchors to
   (`JvmBackend.java:10853`, `17196-17200`) with digest `955fe742...`,
   while the MR's record commit carried the fd7913577 anchors
   (`11260`, `17521-17525`). ISSUE-0307/ISSUE-0570 and this MR's
   async-export host surface both insert text before the anchors, so
   neither side's locators survive verbatim in the rebased tree. The
   conflict was resolved with the tree-verified post-rebase anchors
   (`JvmBackend.java:11448`, `17791-17795`) and the re-derived
   baseline digest
   `fab8a70fa9dd9fab6083a22620b05d386999d78cdecb7ee99de0e819389506a3`
   (recomputed by the real catalog validation run over the rebased
   tree; the pinned locator strings participate in the digest, so the
   relocation re-derives it over the unchanged span bytes).
   HistoricalRegressionCatalogTest 152/0.
2. `deal/codegen/jvm/JvmBackend.java` — auto-merged without conflict:
   the canonical ISSUE-0307/ISSUE-0570 host-array and completion-gate
   machinery and this MR's async-export host surface are disjoint
   emission surfaces; the async-export surface lands on top of the
   canonical structure unchanged.
3. `test/JvmBackendTest.java` — merged additively: this MR's
   `testBytesArrayPastEndReadsYieldTheDealNull` and its registration
   join the canonical additions; the pinned emitted helper text holds
   verbatim against the canonical helpers.
4. `deal/test/conformance/DifferentialGateLanesCorpusTest.java` /
   `JvmLane.java` / `JvmLaneTest.java` / `test/JvmConformanceTest.java`
   / `test/JvmLaneStatePinTest.java` — no MR delta remains: the
   canonical pins already carried the post-closure state, so the MR's
   pre-flip re-pin commits resolve against the canonical pins.
5. `test/BackendConformanceTest.java` / `test/ProjectIntegrationGatesTest.java` /
   `test/conformance/fixtures/jvm-arrays-slice.json` /
   `test/conformance/fixtures/jvm-v1.2-known-fail.json` — the MR's
   additions (the bytes-array parity fixture rows, the javadoc
   re-pins, the possessive-quantifier regex, the promoted
   `jvm-bytes-buffer-ops` description) merge cleanly over the
   canonical ISSUE-0307/ISSUE-0570 changes.

No acceptance criterion changed; criteria 1-4 stay met on both
backends. Every re-pin is tree-derived from the real gate run on the
rebased tree.

### Verification on the final rebased commit (229efbcd base)

- Engine gate `flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh
  --jobs 1`: exit 0, `=== All Tests Passed ===` (full compile + every
  gate record; captured GATE-EXIT=0).
- Async-export suites: LuaJitAsyncExportInvokerTest OK (49),
  RegistryAsyncExportBoundaryTest OK (5), JvmAsyncExportInvokerTest
  OK (38, incl. `productionCompiledAsyncBytesOracleCompletesNull`
  asserting Result.Value("null", "null") with the no-await E3014 /
  incorrect-output TEST_FAIL / no-call TEST_FAIL controls over real
  bytes on the JVM lane), JvmRegistryAsyncExportBoundaryTest OK (6).
- Bytes parity pins green on both backends:
  `jvm-bytes-arr-past-end-null-parity` and
  `jvm-bytes-arr-negative-read-e8002` (real luajit + emitted JVM
  artifact) and `JvmBackendTest.testBytesArrayPastEndReadsYieldTheDealNull`
  against the canonical emitted helper text.
- HistoricalRegressionCatalogTest 152/0 with the re-derived digest
  `fab8a70f...` and the relocated anchors (`JvmBackend.java:11448`,
  `17791-17795`); JvmBackendTest 2335/0; Backend Conformance
  Total 386, Passed 386, Failed 0 with 1 tracked known-fail;
  backend-runtime gates LuaJIT 102/102, JVM 318/318, JS 42/42,
  zero skips.
- `git status` clean and `git diff --check` clean; the committed diff
  contains only the intended backend/test/fixture/record changes.
