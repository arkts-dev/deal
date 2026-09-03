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
  corpus validation loop, the stale-entry rules, and the three-mode
  shape check — is preserved as the runner's classification
  infrastructure; the sanctioned pre-unit pair remains the dormant-mode
  reference of the shape check.
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
  backends emit), the fixture's call-site span, exit code 1, and the
  exact G4.6 framing transcript.
- The remaining summary-level known-fail — the C FFI
  invalid-manifest-policy pin — is promoted in the same change so the
  summary-level known-fail zero (the strict gate's D5 assertion) can
  hold: the checker now rejects an import of a C FFI declaration file
  (`// @extern-c`) that no externals entry declares with
  `nativeLibrary` — E2010 at the import span
  (`deal/checker/ModuleResolver` +
  `deal/checker/NameResolver`, docs/spec-v1.2.md:1891) — and the
  fixture promotes to `// @expected: compile-error E2010` with the
  `@issue` tag dropped, exactly the runner's promotion instruction.
  The companion `ffi_math.d.deal` keeps compiling standalone
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

The strict gate is active (empty registry): no `GATE FAILURE` line is
printed, the four counters are zero on the phase line and in the
summary, and the process exits 0.

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
- **Re-added staged registry entry**: a staged entry for the time
  fixture pinned to the flipped header (`runtime-error E8004`, artifact
  `E8004`, issue `ISSUE-0237`) was temporarily re-added. The dispatch
  recorded the tracked non-fatal `STAGED-FAIL` first, then the shape
  check hard-failed regardless of the counters:
  `GATE FAILURE: staged-failure registry is neither the sanctioned
  pre-unit ISSUE-0237 pair nor empty — backend-runtime/stdlib-edge/
  time-now-millis-positive.deal (tracked by ISSUE-0237)` plus
  `promotion instruction: remove the registry entry (or entries)`; exit
  1 — the vacuousness hole (a re-added matching entry recording a
  tracked STAGED-FAIL with exit 0) is closed (`luajit-gate-closure`
  D2). Reverted; the registry is empty again.

## 4. Closure checklist (epic objective criteria)

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
- The strict gate changes no pre-unit outcome: under the sanctioned
  pre-unit pair the shape check stays dormant (the sanctioned pair
  remains its dormant-mode reference); the strict mode activates only
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
