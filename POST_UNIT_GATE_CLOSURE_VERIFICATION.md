# ISSUE-0477 — Post-Unit Gate-Closure Verification Record (ISSUE-0402 acceptance remediation)

Verification record. Purpose: land the ISSUE-0237 disposition pair and the
disposition-application unit content the acceptance review found missing,
then execute and record the post-unit closure verification of the LuaJIT
lane gate — the four-zero phase line and summary, the time-fixture PASS
under the landed branch, the pin test on the post-activation header, the
pin-layer re-introduction exercise, and `./run_tests.sh` exit 0 (design
refs: `luajit-gate-closure` D1-D6 and the gate-closure assertion contract;
`luajit-time-selector-disposition` D3 branch-1 change set and Verification
1-2, 6-8; `std-time-nowmillis-resolution-and-disposition` D1/D2/D4;
`luajit-v1.2-stdlib-contracts` D6; `luajit-v1.2-conformance-retirement-and-gate`
D3).

Canonical revision: `e6c2f9ce8615db5387571695851ead3d52bbbe5b` (the
engine-imported canonical HEAD; the mandated rebase replays this record's
commits onto it). The prior acceptance review (canonical `5a6997c`) found
the unit not landed — fixture still `// @expected: runtime-ok`, the
sanctioned staged entry still registered, the pin test still pinning the
pre-activation header — so the strict gate was dormant, the summary could
not show zero staged, and the epic's post-unit zero-fail assertion had
never been executed or observed. This change set lands the unit and runs
the closure verification; every claim below was produced by executing the
named command on the final rebased commit and re-reading the named files.

Review cycle 1 (BOT-2987, commit 8175e9e) found three defects; the
remediation in this change set corrects each and re-executes the gate:
(1) the C FFI invalid-manifest-policy known-fail had been promoted with
the E2010 manufactured only by the conformance harness resolvers — the
production rejection now lands in
`deal/module/CompilationOrchestrator.ModuleResolverImpl` and a production
`deal.Main compile` of the exact fixture case fails with E2010 at the
import span (section 5); (2) the time-fixture sidecar pinned fabricated
span values (line 9 / column 18) the retained wrapper can never produce —
the sidecar now omits the whole span group and the lane/schema sanctions
exactly that span-less shape (section 4); (3) the duplicated Javadoc
fragment in `test/LegacyProfileRegressionCatalog.java` is removed.

Review cycle 2 (BOT-3045) found two defects; the remediation in this
change set corrects each at its root cause and re-executes the exercise
on the final tree: (1) critical — the in-runner strict gate was absent
from the delivered runner (removed by the ISSUE-0478 merge-gate
enforcement commit `b77fc29d` and never re-landed), so a re-added staged
entry recorded a tracked STAGED-FAIL and exited 0 — reproduced on the
delivered tree before the fix (a scratch runner with the re-added entry
run over a single-fixture corpus: `[backend-runtime/stdlib-edge/
time-now-millis-positive.deal] STAGED-FAIL (E8004 locked artifact; ...)`,
`Total: 0, Passed: 0, Failed: 0, Skipped: 0, KnownFailures (tracked): 0,
StagedFailures (tracked): 1`, no `GATE FAILURE` line, exit 0). The
strict gate (`runGateClosureCheck`/`runStrictModeGate`/
`isSanctionedPreUnitPair`) re-lands in `deal.test.ConformanceTest`'s
summary/exit path with the approved three-mode registry-shape key and
the pinned reports (luajit-gate-closure D2/D5), the re-introduction
exercise was re-executed on the rebased tree at the change-set commit
`122023c6beeb9ab5bff54f30051d97ef24c9c18c` (the record's finalization
commit that follows changes only this file) with the actually observed
output and exit code re-recorded (section 3), and a committed regression
suite (`deal.test.GateClosureStrictGateTest`, launched by
`run_tests.sh`) pins all three modes plus the strict-mode residual
report; (2) major — the record's prior §2/§3 claims of an active,
exercised shape check were unproducible on the delivered runner; they
are corrected to the re-executed output in this section 3.

## 1. The landed disposition pair (branch 1) and the unit's change set

The ISSUE-0237 resolution's selected branch 1 is applied exactly once:
the retained `()->int` implementation is unchanged on every backend and
the shared fixture carries the canonical disposition header.

- `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
  — header flipped to the canonical branch-1 form
  (`// @expected: runtime-error E8004`, the locked-artifact
  `@description`, `@features: stdlib, time, runtime-errors`); body
  unchanged. Exactly one `@expected` edit in the unit's history.
- `std/time.lua` byte-identical (the retained `check_int(os.time() *
  1000)` route); `deal/runtime.lua` and the two `test_stdlib.lua`
  nowMillis E8004 cases untouched — branch 1 changes no implementation.
- `test/ConformanceTest.java` — the staged-failure registry entry for
  this fixture is removed in the same change; the registry is empty (the
  strict-mode activation key, `luajit-gate-closure` D2). The registry
  machinery — the `StagedEntry` record, the `stagedFailure` helper, the
  corpus validation loop, and the stale-entry rules — is preserved as
  the runner's classification infrastructure; the sanctioned pre-unit
  pair remains the dormant-mode reference of the shape check.
- `test/ConformanceTest.java` — the gate-closure strict gate re-lands in
  this change (cycle-2 remediation; it had been removed by the ISSUE-0478
  merge-gate enforcement and was never re-landed before): the
  `runGateClosureCheck()` call in the summary/exit path plus the
  `runGateClosureCheck`/`runStrictModeGate`/`isSanctionedPreUnitPair`
  section. The activation key is the staged-failure registry shape,
  compared field-exact on path, pinned expectation, artifact code, and
  issue: dormant under the exact sanctioned pre-unit ISSUE-0237 pair
  (no assertion, no extra output — the closure is pending and never
  asserted), strict under the empty registry (zero residuals on the
  backend-runtime phase counters and the global known-fail counter,
  enumerated from `specGroups` with the pinned `GATE FAILURE` reports),
  and a hard failure naming each entry with the removal instruction for
  every other registry shape — regardless of the counters.
- `test/GateClosureStrictGateTest.java` (new) — the committed regression
  suite pinning the three-mode key end to end through compiled scratch
  copies of the runner over single-fixture scratch corpus roots
  (section 3); launched by `run_tests.sh`.
- `test/StdlibTimePreActivationPinTest.java` — the fixture-header
  assertions (`testFixtureHeader`) updated to the post-activation
  header in the same unit (the pin test's lifecycle note); the seam
  assertions already pin the landed profile-gated JS seam, the retained
  implementation texts, the skip-registry absence, and the legacy slice
  pins stay unchanged.
- `test/LegacyProfileRegressionCatalog.java` — the
  `time-now-millis-positive.deal` legacy row is removed and the
  self-probe now requires the fixture to resolve
  `COMMON_SHADOW + DEAL_V1_2_INT32`: the flipped expectation depends on
  the v1.2 signed-int32 gate, not the legacy range, so the fixture runs
  the v1.2 invocation on every lane (the JVM lane surfaces E8004 at the
  declared int boundary).
- The time fixture's Structured Expectation Sidecar
  (`time-now-millis-positive.expect.json`) is re-authored to the
  runtime-error expectation: code E8004, message
  `int out of safe range` (the pinned v1.2 int32 template all three
  backends emit), exit code 1, and the exact G4.6 framing transcript.
  The sidecar pins no sourceFile/line/column: the retained
  `()->int` wrapper raises E8004 with no span at all (the E8004
  carries the route's existing shape —
  `luajit-time-selector-disposition`, Failure and operations), so any
  span pin would be a fabricated value the runtime can never produce.
  The lane/schema sanctions exactly this one span-less shape
  (`SidecarSchemaValidator.SANCTIONED_SPANLESS_FIXTURE`): the error
  object omits the whole span group, pinning any of the three there is
  a classification failure, the lane emits the span-less snapshot
  exactly as captured, and the comparator accepts it — exercised by
  `deal.test.conformance.LuaLaneTest` on the real fixture plus
  `StructuredExpectationComparatorTest.spanGroupAuthority()`.
- The remaining summary-level known-fail — the C FFI
  invalid-manifest-policy pin — is promoted in the same change so the
  summary-level known-fail zero (the strict gate's D5 assertion) can
  hold: the promotion contract requires the production behavior to land
  with the marker drop, and it does — the production module resolver
  (`deal/module/CompilationOrchestrator.ModuleResolverImpl`) rejects an
  import of a C FFI declaration file (`// @extern-c`) that no
  externals entry declares with `nativeLibrary`, and the checker maps
  the rejection to E2010 at the import span
  (`deal/checker/NameResolver`, docs/spec-v1.2.md:1891). A production
  `deal.Main compile` of the exact fixture case fails with exactly one
  E2010 at the import span; the same import through an externals entry
  carrying `nativeLibrary` compiles; an entry that omits
  `nativeLibrary` is the rejection again (pinned by
  `deal.test.DirectiveTest.testProductionCffiManifestPolicy`). The
  conformance harness resolvers mirror the rejection for the
  manifest-less harness pipeline. The fixture promotes to
  `// @expected: compile-error E2010` with the `@issue` tag dropped,
  exactly the runner's promotion instruction. The companion
  `ffi_math.d.deal` keeps compiling standalone
  (`@expected: companion`).
- `docs/v1.2-conformance-status.md` — the pending-resolution paragraph
  and the staged-failures bullet are replaced with the closed state;
  the FFI pin bullets record the promotion.

## 2. Post-unit gate execution (final rebased commit)

Command: `./run_tests.sh` — exit code `0`; final banner
`=== All Tests Passed ===`.

`deal.test.ConformanceTest` summary (LuaJIT lane, real LuaJIT execution):

```text
Total: 430, Passed: 430, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
```

Promotion-gate phase lines:

```text
  Frontend conformance (v1.2 grammar and semantics): 124/124 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
  LuaJIT backend-runtime conformance (v1.2): 306/306 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
  Tracked v1.2 follow-up issues: none — full v1.2 conformance
```

The time fixture records PASS under the landed branch on both executing
lanes — the standard dispatch evaluates the flipped expectation against
the retained implementation (gate validity: `expectation(fixture) ==
landed nowMillis behavior`):

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK (found DEAL_ERROR_CODE: E8004)   (deal.test.ConformanceTest)
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK (found DEAL_ERROR_CODE: E8004)   (deal.test.JvmConformanceTest)
```

The promoted FFI pin passes its exact code on the frontend lane:

```text
  [frontend/modules/ffi-manifest-missing-native-library-rejected.deal] OK (found E2010)
```

Pin test (launched at `run_tests.sh`; post-activation header pin):

```text
=== std/time.nowMillis Disposition Pin Tests (ISSUE-0369) ===
Passed: 37, Failed: 0
```

The strict gate is active under the empty registry (re-landed in this
change — cycle-2 remediation; the re-landed `runGateClosureCheck()` call
sits in the summary/exit path after the coverage report): the full gate
run prints no `GATE FAILURE` line, the four counters are zero on the
phase line and in the summary, and the process exits 0.

## 3. Pin-layer re-introduction exercise (executed, then reverted)

The post-unit registry states are closed; each re-introduction was
produced on the final working tree, observed, and reverted:

- **Re-introduced pre-unit pair (branch 1)**: the fixture header was
  temporarily re-flipped to the pre-activation `runtime-ok` form.
  `deal.test.StdlibTimePreActivationPinTest` failed 5 assertions naming
  the post-activation header (description, `runtime-error E8004`
  expectation, features line, and the two no-`runtime-ok`/has-`runtime-error`
  checks) and exited 1 — the post-unit header pin closes the one
  registry state the shape check cannot distinguish
  (`luajit-gate-closure` D4). Reverted to the canonical header.
- **Re-added staged registry entry (re-executed on the final tree,
  observed output re-recorded)**: a staged entry for the time fixture
  pinned to the flipped header (`runtime-error E8004`, artifact `E8004`,
  issue `ISSUE-0237`) was re-added to a scratch copy of the runner,
  compiled against the delivered classes, and run over the delivered
  corpus. The dispatch recorded the tracked non-fatal `STAGED-FAIL`
  first, then the shape check hard-failed regardless of the counters —
  the actually observed output and exit code (final rebased commit):

  ```text
    [backend-runtime/stdlib-edge/time-now-millis-positive.deal] STAGED-FAIL (E8004 locked artifact; tracked by ISSUE-0237: re-introduction exercise: locked E8004 artifact)
  Total: 429, Passed: 429, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 1
  GATE FAILURE: staged-failure registry is neither the sanctioned pre-unit ISSUE-0237 pair nor empty — backend-runtime/stdlib-edge/time-now-millis-positive.deal (tracked by ISSUE-0237)
  promotion instruction: remove the registry entry (or entries)
  ```

  exit 1 — the vacuousness hole (a re-added matching entry recording a
  tracked STAGED-FAIL with exit 0) is closed (`luajit-gate-closure`
  D2). The same scratch trigger on the pre-fix tree (the gate absent)
  exited 0 with no `GATE FAILURE` line — the reproduced defect this
  re-landing closes. Reverted; the registry is empty again.

  The committed regression suite `deal.test.GateClosureStrictGateTest`
  pins the same trigger plus the other two modes without touching the
  repository — `Passed: 18, Failed: 0` on the final rebased commit:
  (a) empty registry + the flipped fixture → PASS
  (`OK (found DEAL_ERROR_CODE: E8004)`), the four zeros, no
  `GATE FAILURE` line, exit 0; (b) the exact sanctioned pre-unit pair +
  a `runtime-ok` clone → dormant — the tracked STAGED-FAIL recorded,
  no `GATE FAILURE` line, exit 0; (c) the re-added entry pinned to the
  flipped header → the two shape reports above, exit 1; (d) empty
  registry + a fixture failing its own expectation →
  `GATE FAILURE: 1 failed — backend-runtime/stdlib-edge/
  time-now-millis-positive.deal — expected DEAL_ERROR_CODE: E9999`,
  exit 1 (the strict-mode residual report; the environmental skip
  report is pinned for the luajit-absent probe branch). Every probe
  runs a compiled scratch copy of the runner over a single-fixture
  scratch corpus root; every scratch tree is deleted and the runner
  source, the fixture, and the repository are never modified.

## 4. The producible span-less time-fixture oracle (finding-2 remediation)

The retained `()->int` wrapper (`std/time.lua:9-10`,
`check_int(os.time() * 1000)`) raises E8004 before the emitter's
call-site exit check ever runs, so the runtime error carries no
file/line/column at all — the E8004 carries the route's existing shape
(`luajit-time-selector-disposition`, Failure and operations). Direct
execution of the emitted fixture module under the v1.2 int32 profile
confirms: code `E8004`, message `int out of safe range`,
file=nil, line=nil, column=nil. The sidecar therefore pins exactly the
producible fields and omits the whole span group:

```text
DEAL_ERROR_CODE: E8004
DEAL_ERROR_SNAPSHOT: {"code":"E8004","message":"int out of safe range"}
```

The model/lane/schema close around this shape without weakening the
corpus contract for any other fixture:

- `SidecarExpectations.ErrorExpectation` — the span group
  (sourceFile/line/column) is pinned together or null together
  (`pinsSpan()`); `ErrorSnapshot` emits the group exactly when pinned
  and its canonical validation requires the group complete-or-absent.
- `SidecarSchemaValidator` — `SANCTIONED_SPANLESS_FIXTURE`
  (`backend-runtime/stdlib-edge/time-now-millis-positive.deal`): the
  span group is optional exactly for that fixture, and pinning any of
  the three there is a classification failure (a sidecar never pins
  values the runtime cannot produce). Every other runtime-error sidecar
  keeps the five mandatory fields.
- `LuaLane.assembleOutcome` — a span-pinning sidecar against the
  span-less captured error is an honest PROCESS_FAILURE (never
  fabricated); the sanctioned span-less pair emits the snapshot exactly
  as captured and passes the comparison.
- `StructuredExpectationComparator` — the span group is compared
  exactly when pinned; a lane emitting an unpinned group or suppressing
  a pinned group mismatches naming the group.

Exercised by execution (final rebased commit):

- `deal.test.conformance.LuaLaneTest` — `Passed: 47, Failed: 0`,
  including the new real-fixture probe: the lane executes
  `time-now-millis-positive.deal` against its span-less sidecar, emits
  the code/message-only snapshot, and the verdict passes; the
  span-pinned honest-failure probe returns PROCESS_FAILURE.
- `deal.test.conformance.StructuredExpectationComparatorTest` —
  `Passed: 139, Failed: 0`, including `spanGroupAuthority()` (span-less
  match, unpinned-group emission mismatch, pinned-group suppression
  mismatch, partial-group canonical violation).
- `deal.test.conformance.SidecarCorpusValidationTest` —
  `Passed: 2044, Failed: 0` — the sanctioned span-less sidecar
  validates clean, its transcript byte-equals the code/message-only
  canonical snapshot, and its span-group absence is the closed shape.

## 5. The production C FFI manifest-policy rejection (finding-1 remediation)

The promotion contract (retirement page D1) requires the production
behavior to land with the marker drop; it now does.
`deal/module/CompilationOrchestrator.ModuleResolverImpl.resolveModule`
rejects an import whose resolved target is a C FFI declaration file
(`.d.deal` with effective `FileDirectives.externC`) that no externals
entry declares with `nativeLibrary` — file-keyed via the target's
`ExternalModule` classification, so a nativeLibrary-less entry or no
entry at all throws
`ModuleResolver.CffiImportWithoutNativeLibraryException` and
`deal/checker/NameResolver` maps it to E2010 at the import span
(docs/spec-v1.2.md:1891). Production executions:

- The exact fixture case (`deal.json` `{"languageVersion": "1.2"}`,
  entry importing `./ffi_math`, `ffi_math.d.deal` carrying
  `// @extern-c`, no externals entry):
  `ERROR E2010: C FFI declaration file './ffi_math' is imported without
  an externals entry specifying nativeLibrary (a C FFI entry must
  include nativeLibrary)` at the import span; exit 1 — previously
  `Compilation successful: 2 module(s)`.
- The same declaration through an externals entry carrying
  `nativeLibrary` compiles (`Compilation successful: 2 module(s)`,
  no E2010); the same entry omitting `nativeLibrary` is the rejection
  again.
- Pinned by `deal.test.DirectiveTest.testProductionCffiManifestPolicy`
  (three cases; `Passed: 145, Failed: 0`). The two JS-backend E6003
  tests (`JsBackendTest`, `SourceMapTest`) now use manifest-backed
  extern-C imports, so the still-live E6003 arm keeps covering the
  valid-manifest rejection while the unbacked case is the frontend
  E2010 (`JsBackendTest` 454/0, `SourceMapTest` 182/0).
- The promoted conformance pin passes on the frontend lane:
  `[frontend/modules/ffi-manifest-missing-native-library-rejected.deal]
  OK (found E2010)` — the harness resolvers mirror the production
  rejection for the manifest-less harness pipeline.

## 6. Closure checklist (epic objective criteria)

- Phase line and summary show zero failed, zero skipped, zero
  known-fail, zero staged — section 2.
- The time fixture records PASS under the landed branch on the LuaJIT
  lane — section 2; `expectation(fixture) == landed nowMillis
  behavior` holds (retained `()->int` route deterministically raises
  E8004 under the v1.2 signed-int32 gate; the flipped expectation pins
  exactly that artifact).
- The pin test passes on the post-activation header — section 2; a
  re-flipped pre-unit pair fails it — section 3.
- `./run_tests.sh` exits 0 — section 2.
- The strict gate is present, active under the empty registry, and
  exercised: the re-introduction exercise reproduces the pinned shape
  reports and exit 1, and `deal.test.GateClosureStrictGateTest` pins
  all three modes plus the strict-mode residual report (18/0) —
  section 3.
- The strict gate changes no pre-unit outcome: under the sanctioned
  pre-unit pair the shape check stays dormant (the sanctioned pair
  remains its dormant-mode reference — pinned by the regression
  suite's dormant probe, section 3); the strict mode activates only
  with the unit's landing artifact (the empty registry).
- One-edit discipline: the fixture's `@expected` line is edited exactly
  once in this change; `std/time.lua`, `deal/runtime.lua`, and the two
  `test_stdlib.lua` E8004 cases are byte-identical.
- Non-weakening: the zero-staged requirement and the behavioral-equality
  validity condition are asserted exactly as pinned; no branch,
  fixture, or `std/time.lua` product decision is made here — branch 1
  is applied from the resolution's landed selection
  (`std-time-nowmillis-resolution-and-disposition` D1/D2).

Superseded records: `PRE_UNIT_STAGED_STATE_PIN.md`,
`CONTINGENT_DISPOSITION_DELIVERY_RECORD.md`, and
`BOTH_BRANCH_VERIFICATION_PIN.md` remain as historical audit records of
their own canonical revisions, as do their companion exercise scripts
`verify_both_branches_scratch.sh` and
`verify_contingent_delivery_gate.sh` (pre-unit gate-condition checks,
not wired into `run_tests.sh`); the post-unit state this record pins
supersedes their pending-resolution claims.
