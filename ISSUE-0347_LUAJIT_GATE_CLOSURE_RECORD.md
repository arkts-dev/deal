# ISSUE-0347 — LuaJIT Conformance Retirement and Zero-Fail Gate Closure Record

Closure verification record. Purpose: close the LuaJIT backend-runtime
gate per `luajit-v1.2-conformance-retirement-and-gate` D1–D3 — zero
failed, zero skipped, zero known-fail on the LuaJIT-owned corpus — and
pin the remaining corpus requirements: the D2 fixture families with
exact codes and v1.2 `@spec` references, the delegated record execution
(the parent feature-registry FFI records and the `async-export` records
through the production `LuaJitAsyncExportInvoker`), and the
`stdlib-edge/time-now-millis-positive.deal` validity condition
(`expectation(fixture) == landed nowMillis behavior`). Design refs:
`luajit-v1.2-conformance-retirement-and-gate` D1–D4,
`luajit-v1.2-stdlib-contracts` D6, `luajit-v1.2-emitter-and-lowering`
D8, `std-time-nowmillis-resolution-and-disposition`,
`luajit-time-selector-disposition`, `luajit-gate-closure` D1–D6.

The upstream children (RV, MATCHER, RCP, ASYNC_RT, CUTOVER, EL, ECP,
SMATH, SJSON, STABLE, FFIGEN, FFI_RT `load_ffi`, ASYNC_HOST invoker,
REGISTRY records, TIME disposition) landed on the canonical line before
this issue. This record executes and records the post-landing closure
verification on the final rebased commit: every claim below was produced
by executing the named command on this working tree and re-reading the
named files. Nothing below was weakened; no fixture expectation,
production file, or runner mechanic was changed by this task — the only
file this task adds is this record.

## 1. The two LuaJIT-owned known-fails are promoted (gate page D1)

The two LuaJIT-owned known-fail fixtures promote to exact expectations
with the `@issue` tags dropped, in the same changes that landed their
runtime behavior (retirement page D1; the promotions landed with the
RV/EL units):

- `test/conformance/backend-runtime/arithmetic/int-add-overflow.deal` —
  `// @expected: runtime-error E8004` (`2147483647 + 1` overflows the
  signed-int32 range). No `@issue` tag remains.
- `test/conformance/backend-runtime/bytes/bytes-buffer-ops.deal` —
  `// @expected: runtime-ok` (bytes allocation, zero-fill, indexed
  writes 0..255, `.length`, reference identity). No `@issue` tag
  remains.

Corpus scan executed on this tree: `grep -rln "known-fail"
test/conformance/backend-runtime/` exits 1 with zero matches and
`grep -rln "knownFail" test/conformance/backend-runtime/` exits 1 with
zero matches — no known-fail marker remains on any LuaJIT-owned
expectation. The only `knownFail` occurrences left under
`test/conformance/` are the JVM-gate-owned registry
`test/conformance/fixtures/jvm-v1.2-known-fail.json` (entries carry
`"backends": ["jvm"]`; ISSUE-0277-owned, excluded by gate page D4) and
historical documentation/records that narrate the retired markers
(`json-absorption/migration-record.md`,
`ir-pin-migration-record.md`, and one historical run-log line in
`json-absorption/gate-pass-log.txt`) — none is a LuaJIT-owned fixture
expectation.

## 2. The LuaJIT backend-runtime gate is four-zero (gate page D3)

Executed on this tree: `java -ea -cp build deal.test.ConformanceTest
test/conformance/` — exit 0. Summary (real LuaJIT execution):

```text
Total: 496, Passed: 496, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
Companions (classified support modules): 49
Profile-authority accounting: 1 legacy-authority result(s) (LEGACY_REGRESSION + LEGACY_SAFE_INT — zero v1.2/promotion credit; 1 passed, 0 failed), 544 v1.2-credit result(s) (COMMON_SHADOW + DEAL_V1_2_INT32)

=== DEAL v1.2 Promotion Gate ===
  Frontend conformance (v1.2 grammar and semantics): 189/189 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
  LuaJIT backend-runtime conformance (v1.2): 307/307 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
  Tracked v1.2 follow-up issues: none — full v1.2 conformance
```

The spec-coverage report of the same run (frontend and backend-runtime
sections together; the frontend `§Test suite basis  UNCOVERED (0
tests)` row — a zero-test section with nothing to run, not a skip — is
omitted from the quote for compactness):

```text
  §Lexical elements                                       58/58 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Syntactic grammar                                      15/15 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Type system                                            37/37 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Classes                                                54/54 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Functions                                              28/28 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Variables                                              4/4 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Tables                                                 15/15 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Arrays                                                 18/18 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Bytes                                                  8/8 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Control flow                                           20/20 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Error handling                                         16/16 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Async/Await                                            57/57 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Modules, declarations, standard library, and host ABI  64/64 passed, 0 failed, 0 known-fail, 0 staged-fail
  §C FFI declaration files                                6/6 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Runtime execution model                                63/63 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Standard library declarations                          28/28 passed, 0 failed, 0 known-fail, 0 staged-fail
  §Diagnostics                                            5/5 passed, 0 failed, 0 known-fail, 0 staged-fail
```

In the composed gate, `run_tests.sh` substitutes the raw lane launches
with `deal.test.JvmLaneStatePinTest` (ISSUE-0378 D5): the pin test
launches the real LuaJIT lane (`deal.test.ConformanceTest
test/conformance/`) as a subprocess on every gate run and asserts its
captured output field-exactly — the four-zero summary
(`Total: 496, Passed: 496, Failed: 0, Skipped: 0, KnownFailures
(tracked): 0, StagedFailures (tracked): 0`), the
`LuaJIT backend-runtime conformance (v1.2): 307/307 passed, 0 failed,
0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)` phase
line, the time fixture `OK (found DEAL_ERROR_CODE: E8004)`, the
promoted `int-add-overflow.deal` `OK (found DEAL_ERROR_CODE: E8004)`,
the profile-authority line, the `Tracked v1.2 follow-up issues:
none — full v1.2 conformance` line, and the empty `] FAIL (` /
`GATE FAILURE` line sets. The pin test passed both inside the final
gate run (`Passed: 27, Failed: 0` in the composed-gate log) and when
executed directly on this tree (`Passed: 27, Failed: 0`, exit 0).

The full composition `flock /tmp/igelhaus-deal-tests.lock
./run_tests.sh --jobs 1` (the engine gate command) exits 0 with the
final banner `=== All Tests Passed ===` — full compile plus every
suite, including the lane pin test (which runs the real LuaJIT lane),
the delegated executions (section 4), and the source-location suites
(section 5).

## 3. The D2 fixture families are present and passing with exact codes

Every family required by gate page D2 exists on disk and passes with a
single exact `@expected` code, a v1.2 `@spec` reference, and no `any`
form (executed on this tree; expectation lines quoted verbatim):

- int32: `int-add-overflow.deal` E8004, `int-sub-overflow.deal` E8004,
  `int-mul-overflow.deal` E8004, `int-div-zero.deal` E8005,
  `int-pow-negative.deal` E8006, `int-neg-min.deal` E8004,
  `int-conversion-out-of-range.deal` E8004 (plus the pinned
  truncation/mod boundary cases `int32-mod-min-neg-one.deal`,
  `int32-pow-infinity.deal`, `int32-pow-overflow.deal`).
- bytes: `bytes-buffer-ops.deal` runtime-ok, `bytes-index-bounds.deal`
  E8012, `bytes-write-range.deal` E8013, `bytes-length.deal`
  runtime-ok, plus `bytes-write-single-evaluation.deal` and
  `bytes-write-validation-order.deal`.
- defaults: `plan-fresh-literals.deal`, `plan-reexecuted-calls.deal`,
  `plan-imported-provider-scope.deal`,
  `plan-phase-order-provided-before-defaults.deal`,
  `plan-load-time-zero-invocations.deal`, `plan-host-discriminator.deal`
  — all runtime-ok with `plan_provider_lib.deal` as companion.
- descriptors: `canonical-array-boundary.deal`,
  `canonical-nullable-boundary.deal`,
  `canonical-class-atom-error-roundtrip.deal` (the builtin `Error`
  atom roundtrips as `@$builtin/Error`),
  `canonical-async-marker-boundary.deal` — runtime-ok;
  `canonical-sig-mismatch-e8010.deal` — E8010. Recursive
  bytes-bearing function values are pinned by
  `bytes-descriptor-boundary.deal` (`(bytes)->bytes`, `?(bytes)->bytes`
  nullable, and `[?(bytes)->bytes]` arrays of nullable functions) and
  `bytes-class-field-descriptor.deal`.
- host classes: `host-class-export.deal` — runtime-ok through the
  preserved `class_` defaults-map seam (section 6);
  `host-class-extra-field.deal` — E8007;
  `host-class-default-isolation.deal` — runtime-ok.
- async: the preserved `host-async-ok.deal` (runtime-ok),
  `host-async-bad.deal` (E8001), `host-async-shape-bad.deal` (E8010),
  `host-async-shape-value.deal` (E8010) plus the new direct-await
  cases `direct-await-completion-values.deal` (runtime-ok) and
  `await-completion-check.deal` (runtime-ok) — all executable by the
  preserved standard runner (they are part of the 57/57 §Async/Await
  section above).
- source-location / source-location-precision: exact file/line/column
  pins for every new check path via Structured Expectation Sidecars —
  `source-location/int32-overflow-source` (E8004 at line 9 column 18),
  `source-location/int-neg-min-source` (E8004),
  `source-location/bytes-index-bounds-source` (E8012),
  `source-location/bytes-write-range-source` (E8013),
  `source-location/async-error-source`, `source-location/json-error-source`,
  `source-location/module-error-source`,
  `source-location/nested-array-oob-source`,
  `source-location-precision/class-param-error-source`,
  `source-location-precision/closure-error-source`,
  `source-location-precision/imported-async-error-source`,
  `source-location-precision/loop-error-source`,
  `source-location-precision/stdlib-error-source`, and the E8005 span
  pin in `runtime-errors/int-div-zero-e8005.expect.json` (line 7
  column 10). Every sidecar pins the full span group; the single
  sanctioned span-less shape is the time fixture (section 6).

Classification is enforced mechanically by the preserved runner: a
missing or stale `@spec`, an unknown `@expected`, or the `any` form is
a classification failure that fails the gate
(`test/ConformanceTest.java:587-641`). Executed on this tree:
`grep -rln "any runtime" test/conformance/backend-runtime/` exits 1
(zero `@expected: any` forms); every non-companion fixture carries an
`@expected` and an `@spec` (the only fixtures without `@spec` are the
28 `@expected: companion` support modules, which the classification
contract exempts from spec-group coverage).

## 4. Delegated executions pass with the pinned outcomes

All three delegated surfaces execute through the production path on
this tree, wired into `./run_tests.sh` so the root gate asserts them
every run.

- **FFI cases through the parent page's real-library feature
  registry**: `luajit test/ffigen_integration.lua` (run_tests.sh:721,
  fail-closed — no skip path) — exit 0, `Results: 9 passed, 0 failed`.
  The eight-phase scenario matrix: phase 1 `FFI_LIBRARY_LOAD` at the
  import span with zero event lines; phase 2 exact replay re-raises the
  cached error with no retry; phase 3 `FFI_SYMBOL_MISSING` with exactly
  one open/one close; phase 4 failed-replay re-raises with no fresh
  open; phase 5 valid load yields ready typed wrappers, real
  roundtrips, one native call each, zero default evaluation; phase 6
  ready replay serves the cached record; phase 7 `FFI_INVALID_STRING`
  raises before the call with no native effect and `FFI_NULL_STRING`
  raises after the call with native effects retained; phase 8 the
  generated-artifact surface scan re-verifies one `__rt.load_ffi(` call
  per module with the pinned `ffi:@$external/...` loader text. The
  remaining FFI codes (`FFI_INVALID_UTF8`, `FFI_NULL_POINTER`) and the
  ISSUE-0163 D-series are pinned by the six-code/atomicity battery
  inside `test_runtime.lua` (`FFI six-code case 1..6`, `FFI ISSUE-0163
  D0..D19`), which runs under the gate's `luajit` record.
- **async-export records through the production
  `LuaJitAsyncExportInvoker`** (REGISTRY acceptance):
  `deal.test.RegistryAsyncExportBoundaryTest` — executed on this tree,
  exit 0, `OK (5 tests)`. The D12-shaped record projects compile
  through the production `ProjectLocator` + `CompilationOrchestrator`
  path and execute through the production invoker with
  `(entryArtifact, exportName, byte-exact returnDescriptor)`: the
  `async()->null` bytes-bearing oracle completes with the
  matcher-validated value and the artifact never reaches
  `invoke_async_export` itself; the runtime-error record propagates the
  declared E8004 with code/message/file/line/column unchanged, and the
  pinned conditional rule holds — only the exact declared DEAL code
  satisfies a runtime-error expectation, so a HostFailure, a
  hard-failure exception, or a different DEAL code never reads as a
  satisfied DEAL-error expectation.
- **Invoker matrix incl. HostInvocationFailure mapping**:
  `deal.test.LuaJitAsyncExportInvokerTest` — executed on this tree,
  exit 0, `OK (44 tests)`: missing/sync/parameterized/duplicate/
  descriptor-mismatched/non-wrapper/non-operation exports map to
  pinned HostFailure reasons (never a DEAL code), a non-canonical
  descriptor passes verbatim to the runtime half's pinned reason, and
  each invoke spawns one fresh process whose module load, `main()`
  call, and oracle invocation each run exactly once
  (`twoConsecutiveInvokesEachSpawnOneFreshProcessWithExactlyOnceInitMainOracle`,
  `productionCompiledHostProbeRunsExactlyOncePerInvoke`).

## 5. SourceMapTest and RuntimeSourceLocationTest stay green

Executed on this tree:

- `deal.test.SourceMapTest` — exit 0, `Passed: 182, Failed: 0`
  (emitter page D8 source-map suite, including the manifest-backed
  extern-C imports).
- `deal.test.RuntimeSourceLocationTest` — exit 0,
  `Passed: 24, Failed: 0` (runtime location plumbing, including the
  E8005 span pin).

Both suites are also registered in `tools/gate-manifest.sh` and ran
green inside the full gate run of section 2.

## 6. Time-fixture validity condition (gate page D3, stdlib page D6)

The landed branch is the ISSUE-0237 resolution's branch 1 —
the retained `()->int` implementation — and the fixture carries exactly
that disposition, so `expectation(fixture) == landed nowMillis
behavior` holds:

- `std/time.lua` keeps the retained route verbatim:
  `time.nowMillis = __rt.function_("()->int", function() return
  __rt.check_int(os.time() * 1000) end)` — contemporary epoch
  milliseconds (≈1.7e12) deterministically raise E8004 under the
  signed-int32 gate.
- `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
  declares `// @expected: runtime-error E8004` with the locked-artifact
  description; body unchanged (`now > 0` assertion never reached — the
  call raises first). The fixture passes through the standard
  runtime-error dispatch on the LuaJIT lane (part of the 307/307
  backend-runtime count above, and pinned field-exactly by
  `JvmLaneStatePinTest`'s `LUA_TIME_OK` line inside the gate).
- The fixture's Structured Expectation Sidecar pins exactly the
  producible shape — code E8004, message `int out of safe range`, no
  sourceFile/line/column — because the retained wrapper raises E8004
  with no span at all; the lane/schema sanctions exactly this one
  span-less shape and a fabricated span pin there is a classification
  failure (`SidecarSchemaValidator.SANCTIONED_SPANLESS_FIXTURE`).
- The staged-failure registry is empty and the gate-closure strict gate
  is active under the empty registry (`deal.test.ConformanceTest`
  `runGateClosureCheck`/`runStrictModeGate`/`isSanctionedPreUnitPair`),
  pinned by `deal.test.GateClosureStrictGateTest` and
  `deal.test.StdlibTimePreActivationPinTest`, both registered in
  `tools/gate-manifest.sh` and green inside the full gate run.

## 7. Promotion irreversibility and classification failures

The preserved runner enforces both directions without any change by
this task:

- A stale known-fail marker whose underlying mode passes fails the gate
  with the promotion instruction naming the fixture
  (`test/ConformanceTest.java:489` — `promotion instruction: set
  '@expected: ...'`); a re-introduced known-fail on a LuaJIT-owned
  expectation is a gate failure via the strict-gate residual reports
  (`GATE FAILURE` lines at `test/ConformanceTest.java:466-495`) and the
  summary-level known-fail zero assertion.
- Missing/stale `@spec`, unknown `@expected`, and the `any` form are
  classification failures (`test/ConformanceTest.java:587-641`).
- Host-module ABI preservation: `test/conformance/host-fixtures/cfg.lua`
  keeps the preserved `class_` defaults-map seam unchanged
  (`Endpoint_defaults`, `ServerConfig_defaults` with the `__MISSING`
  sentinels, `__kind`/`__classname` identity rows), and
  `host-class-export.deal` passes `runtime-ok` through that seam —
  host-module-abi D1/D2 and `cfg.lua` untouched.

## 8. Combined dependency check

`./run_tests.sh` is the full composition (combined dependency step):
it fails if any upstream child (RV, MATCHER, RCP, ASYNC_RT, CUTOVER,
EL, ECP, SMATH, SJSON, STABLE, FFIGEN) or any epic outcome (FFI_RT
`load_ffi`, ASYNC_HOST invoker over ASYNC_RT's runtime entry, REGISTRY
records, TIME disposition) is broken or missing. Executed on this tree:
exit 0 with `=== All Tests Passed ===`; the lane pin test (which runs
the real LuaJIT lane with its field-exact four-zero pins) and the
delegated suites of sections 2/4/5 all ran inside it.

## 9. Anti-hollow statement and final state

Every claim in this record was produced by executing the named command
on the final rebased tree (HEAD `23a30b4`), not inferred from other
passing suites: the ConformanceTest summary was executed directly
(section 2), the two source-location suites directly (section 5), the
delegated executions directly (section 4), the lane pin test directly
(section 2), and the corpus scans directly (sections 1 and 3). This
task changes no production, test, or fixture file — the only
repository addition is this record — so the engine gate
`flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh --jobs 1` runs the
same green battery as the pre-task baseline: exit 0,
`=== All Tests Passed ===`, zero `GATE FAILURE` lines, the lane pin
test asserting `Total: 496, Passed: 496, Failed: 0, Skipped: 0,
KnownFailures (tracked): 0, StagedFailures (tracked): 0` and
`LuaJIT backend-runtime conformance (v1.2): 307/307 passed, 0 failed,
0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)` on the
real LuaJIT lane. Working tree clean after commit
(`git status --porcelain --untracked-files=all` empty).
