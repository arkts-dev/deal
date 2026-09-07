# ISSUE-0361 — Zero-Skip Flip Precondition Verification Record

Record of the ISSUE-0361 sequencing-precondition verification at the
canonical revision. Purpose: the issue makes the zero-skip flip
conditional — "Sequencing preconditions (verify, do not implement):
… If any precondition fails, the flip does not land — the child
reports which one." Every precondition named by the issue is verified
below against the canonical tree with exact source evidence. All five
preconditions fail, so the flip does not land and this record is the
child's report.

Canonical revision: `29f962060547c5321ee6143634ab80652ac5e4c7`
(the engine-imported canonical HEAD; the mandated rebase replays this
record's commit onto it). All file:line citations were re-verified
after the rebase; the working tree is clean and the full gate
(`flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh --jobs 1`) exits 0
on the canonical revision with the pinned pre-flip lane state.

Verdict: **the flip does not land.** No known-fail grammar removal, no
skip-registry deletion, no JSON absorption, no FFI divergent sidecar,
no manifest rows, no gate wiring, and no production change is made in
this change. Landing any part alone would leave the gate red (the
issue's atomicity rule), and two parts are unimplementable against the
canonical production emission without the backend-epic obligations this
child is forbidden from implementing (boundary scan: "no production
file modified by this child").

## Precondition 1 — the known-fail fixtures pass their underlying modes: FAILS

The canonical corpus still carries one `known-fail` marker whose
underlying mode does not pass:

- `test/conformance/frontend/modules/ffi-manifest-missing-native-library-rejected.deal:3`
  carries `@expected: known-fail compile-error E2010` and its
  `@description` records "the rejection has no production emission site
  yet". The fixture is tracked, not stale: the pinned LuaJIT lane state
  (`test/JvmLaneStatePinTest.java`, `LUA_SUMMARY`) reads
  `Total: 495, Passed: 495, Failed: 0, Skipped: 0,
  KnownFailures (tracked): 1, StagedFailures (tracked): 0` with
  `ISSUE-0111: 1 known-fail fixture(s)` — i.e., the compile-error E2010
  probe still fails. Promoting the fixture to `compile-error E2010`
  today would make the gate red, and removing the known-fail grammar
  while the marker remains on disk is itself a classification failure
  (the issue's atomicity rule). The frontend residual owners
  (ISSUE-0273/0154/0313/0316 lineage) have not landed the E2010
  extern-C native-library emission.

## Precondition 2 — the JVM registry entries pass: FAILS

`test/JvmConformanceTest.java` still carries the 45-entry explicit skip
registry (the issue's "34 entries" baseline has grown, not shrunk):

- JVM-GAP-STDJSON: 13 entries (`:249-281`);
- JVM-GAP-BYTES: 10 entries (`:322-359`);
- JVM-GAP-JSONABLE-RESIDUAL: 4 entries (`:369-378`);
- JVM-GAP-HOST-ABI-SHAPES: 16 entries (`:389-484`);
- JVM-GAP-DEFAULTS-PLANS: 2 entries (`:460-469`).

The entries are active, not stale: the pinned JVM lane state
(`test/JvmLaneStatePinTest.java`, `JVM_SUMMARY`) reads
`Backend-runtime on JVM: denominator 301 (…), passed 256, failed 0,
skipped 45 (classified), known-fail 0 (tracked) — pass rate 85.0%`
with the gate banner `>= 80% pass rate over the unchanged 301-test
denominator`. Deleting the registry now would flip those 45 fixtures
to hard failures — the backend-completion epics (ISSUE-0276/0277:
JVM std/json boundary, bytes carrier, host-ABI shapes, defaults
plans) have not landed.

## Precondition 3 — the JS gaps are closed: FAILS

The JSON slice corpus is still on disk and still carries blocked pins:

- `test/conformance/fixtures/` holds 21 `*.json` files;
- `test/conformance/json-absorption/migration-record.md` records
  `Total remaining cases: 327` with 328 BLOCKED pin dispositions,
  including 47 references to ISSUE-0278 (JS lane failures) and the
  ISSUE-0276/ISSUE-0277 JVM destinations plus the T14 to-be-authored
  set. The residual absorption therefore cannot complete
  pin-before-delete: deleting any blocked JSON case before its
  destination passes on all three lanes would violate the
  pin-before-delete rule and leave the gate red.

## Precondition 4 — E6006 registered in DiagnosticCode.java: FAILS

`deal/diagnostics/DiagnosticCode.java` registers E6000–E6005 only
(`:277-288`); no `E6006` exists anywhere in the file. The canonical
lineage's own ISSUE-0157 landing explicitly removed the E6006
registration and re-pinned the C_FFI linked JVM rejection to "E6003
(the production emission), not E6006" (commit `87e21988`). The pinned
registration —
`E6006(Phase.BACKEND_LOWERING, "C FFI is not supported by this
backend")` — is a backend-epic obligation this child verifies and pins,
never implements; it has not landed.

## Precondition 5 — JsBackend no longer emits E6003 for @extern-c: FAILS

`deal/codegen/js/JsBackend.java:1185` still emits
`DiagnosticCode.E6003` with the message
`JavaScript backend: @extern-c imports are not supported
(FFI_UNSUPPORTED_BACKEND, ISSUE-0169 skeleton)` at the
`rejectUnsupportedImport` arm. The JVM side likewise emits E6003 at
`deal/module/CompilationOrchestrator.java:2068`
(`FFI_UNSUPPORTED_BACKEND` at the `@extern-c` directive range), and
the canonical feature catalog pins
`deal/test/conformance/FeatureBackendMatrix.java:97`
`FFI_UNSUPPORTED_BACKEND_CODE = "E6003"`.

The sanctioned-divergence seam is therefore split: the gate-side schema
validator pins `deal/test/conformance/SidecarSchemaValidator.java:122`
`FFI_UNSUPPORTED_BACKEND_CODE = "E6006"` (design C6), while both
incapable-lane production emissions are E6003. Consequence for the FFI
divergent case: authoring it with `diagnostic.code E6006` would make
the JVM and JS legs fail `COMPILE_REJECT_MISMATCH` against the real
E6003 emission, and authoring it with E6003 is rejected by schema
validation (only E6006 is the sanctioned code). The divergent case
cannot land until the backend epics perform the E6003→E6006 migration.

## Decision and boundary

Per the issue: "If any precondition fails, the flip does not land —
the child reports which one." All five preconditions fail, so nothing
from the flip lands in this change: no promotion of the remaining
known-fail fixture, no skip-registry deletion, no known-fail/forced-
promotion mechanism removal, no JSON absorption, no
`BackendConformanceTest` retirement, no FFI divergent sidecar, no
coverage-manifest rows, no run_tests.sh gate wiring, and no production
change (the E6006 registration and the JS migration belong to the
backend epics and remain unperformed). The canonical gate stays green
with its pinned pre-flip lane state (JVM lane "skipped 45", LuaJIT
lane "KnownFailures (tracked): 1") — verified by the full
`./run_tests.sh --jobs 1` run exiting 0 on this revision.
