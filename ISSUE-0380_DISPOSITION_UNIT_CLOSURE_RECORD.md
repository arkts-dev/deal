# ISSUE-0380 — Disposition-Application Unit Closure Record (JVM activation tree head consumed)

Audit record. Purpose: close the disposition-application unit — the
single landing change that consumes the JVM activation tree head,
applies the canonical fixture header exactly once, carries the
sanctioned `int-add-overflow` promotion, closes the tree's staged lane
states, and restores `expectation(fixture) == landed behavior` on the
JVM lane with `./run_tests.sh` exiting 0 (design refs:
`jvm-int32-gate-activation-tree` D6/D7 and the "tree as a merge input"
contract; `std-time-nowmillis-resolution-and-disposition` D2/D4/D5;
`jvm-v12-int32-bytes` D6's sanctioned promotions boundary).

Canonical revision consumed as the tree head: `31b5f9937c5f159e871069f5f0f19f50b2fd3413`
(`Merge MR-0388: Tree acceptance: diff discipline, staged-state
confirmation, and merge-input report` — the tree head this unit merges;
the tree is an ancestor of this unit's landing change, merged verbatim:
the four activation files are byte-identical to the tree head).

Method: every acceptance line below is backed by captured command or
suite output recorded verbatim in this record. All commands were
executed after the change; the working tree is clean.

Verdict: **the unit closed.** The JVM lane passes the flipped time
fixture as `runtime-error E8004` with the exact code under the
activated profile; the promoted `int-add-overflow` passes and the
stale-known-fail gate no longer names it; the LuaJIT lane exits 0 with
zero staged failures (the staged registry entry removed in the same
landing change); `jvm-std-time-nowmillis` stays green on the untouched
legacy path; `./run_tests.sh` exits 0.

## 1. The merge input (tree head) — consumed verbatim

- **Tree head SHA:** `31b5f9937c5f159e871069f5f0f19f50b2fd3413` (an
  ancestor of this unit's head; `git merge-base --is-ancestor` holds).
- **Pre-tree base:** `a5f683ab829796cd5b2d42eade7c47ecdee86321`
  (the ISSUE-0379 record's pinned derivation).
- **Diff stat against the pre-tree base (the four activation files,
  captured at this head):**

```text
 deal/codegen/jvm/JvmBackend.java         | 3349 +++++++++++++++++++-----
 deal/module/CompilationOrchestrator.java | 2349 ++++++++++++-----
 test/JvmBackendTest.java                 | 4052 ++++++++++++++++++++++++++----
 test/JvmConformanceTest.java             |  845 +++++--
 4 files changed, 8602 insertions(+), 1993 deletions(-)
```

- **The unit alters nothing else from the tree:** the unit's own diff
  touches zero of the four activation files (byte-identical to the
  tree head); `emitStdlibTimeMemberCall` is untouched; no JS/Lua
  activation or `test_stdlib.lua` edit; the bytes-core-gated sibling
  promotions stay gated (`jvm-v1.2-known-fail.json` untouched).

## 2. The canonical header — applied exactly once

`test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
— exactly the four pinned header lines changed, body byte-identical:

```text
 // @spec: Standard library declarations — std/time
 // @description: std/time.nowMillis under the v1.2 signed-int32 gate — the retained ()->int route raises E8004 for contemporary epoch milliseconds (locked TIME_NOW_MILLIS artifact)
 // @expected: runtime-error E8004
 // @features: stdlib, time, runtime-errors
```

Its sidecar was re-authored in the same change as the uniform
three-backend E8004 sidecar (error object `code E8004`, message
`int out of safe range`, `sourceFile
backend-runtime/stdlib-edge/time-now-millis-positive.deal`, `line 9`,
`column 18` — the call-site span convention of the sibling
stdlib-edge sidecars; the canonical-serialization consistency check
passes).

## 3. The sanctioned promotion (unit's landing change)

`test/conformance/backend-runtime/arithmetic/int-add-overflow.deal`:
`@expected: known-fail runtime-error E8004` → `@expected:
runtime-error E8004`, the `@issue: ISSUE-0111` tag dropped, body and
sidecar unchanged (jvm-v12-int32-bytes D6's corpus promotion — the
promotion this tree's stale gate forces). The mirror
`jvm-int32-add-overflow` and the bytes entries in
`test/conformance/fixtures/jvm-v1.2-known-fail.json` stay tracked
(ISSUE-0381's later sanctioned edit; the bytes promotions remain gated
on the bytes core).

## 4. Post-unit lane evidence (captured)

JVM lane (`java -ea -cp build deal.test.JvmConformanceTest
test/conformance/`), exit 0:

```text
  [backend-runtime/arithmetic/int-add-overflow.deal] OK (found DEAL_ERROR_CODE: E8004)
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK (found DEAL_ERROR_CODE: E8004)
Backend-runtime on JVM: denominator 301 (every on-disk runtime test, unchanged), passed 256, failed 0, skipped 45 (classified), known-fail 0 (tracked) — pass rate 85.0%
Profile-authority accounting: 0 legacy-authority fixture(s) (LEGACY_REGRESSION + LEGACY_SAFE_INT — zero v1.2/promotion credit; 0 passed, 0 failed)
Gates PASSED: frontend 100%; backend-runtime zero applicable failures AND >= 80% pass rate over the unchanged 301-test denominator; zero unclassified skips; zero stale skips; zero stale known-fail markers; zero probe runner exceptions.
```

The only `] FAIL (` lines remain the two pre-existing host-prewrapped
skip-probe exception lines; no GATE FAILURE line prints.

LuaJIT lane (`java -ea -cp build deal.test.ConformanceTest
test/conformance/`), exit 0:

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) OK (found DEAL_ERROR_CODE: E8004)
Total: 430, Passed: 430, Failed: 0, Skipped: 0, KnownFailures (tracked): 1, StagedFailures (tracked): 0
Profile-authority accounting: 2 legacy-authority result(s) (LEGACY_REGRESSION + LEGACY_SAFE_INT — zero v1.2/promotion credit; 2 passed, 0 failed), 463 v1.2-credit result(s) (COMMON_SHADOW + DEAL_V1_2_INT32)
  LuaJIT backend-runtime conformance (v1.2): 307/307 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
```

The single tracked known-fail is the frontend FFI-manifest fixture
(compile-error E2010, ISSUE-0111) — untouched by this unit. No
STAGED-FAIL line prints: the staged registry entry was removed in this
landing change (std-time-nowmillis-resolution-and-disposition D4;
BOTH_BRANCH_VERIFICATION_PIN run A's branch-1 matching pair).

Legacy regression: `deal.test.BackendConformanceTest` passes on the
untouched legacy invocation; `jvm-std-time-nowmillis` stays green with
unchanged source and expected result (`[jvm-std-time-nowmillis]
LEGACY-AUTHORITY ... OK`), and the `jvm-int32-add-overflow` /
`jvm-int32-literal-out-of-range` / `jvm-bytes-buffer-ops` mirrors stay
tracked known-fails there (zero stale gates).

## 5. Pin updates in the unit's landing change

- `test/JvmLaneStatePinTest.java`: re-pinned to the post-unit green
  lane state (both real lanes run inside the pin test with
  field-exact output assertions; `Passed: 35, Failed: 0`).
- `test/StdlibTimePreActivationPinTest.java`: the fixture-header pins
  now pin the canonical landed header (the pre-activation `runtime-ok`
  pins retired in this unit, per the test's lifecycle note).
- `deal/test/conformance/SidecarCorpusValidationTest.java`:
  `RUNTIME_OK_COUNT` 219 → 218; `RUNTIME_ERROR_COUNT` 81 → 83; the
  backend-runtime known-fail population pin is now the empty set.
- `deal/test/conformance/DifferentialGateCorpusTest.java`:
  `RUNTIME_OK` 219 → 218, `RUNTIME_ERROR` 81 → 83,
  `RUNTIME_ERROR_SIDECARS` 82 → 83, `KNOWN_FAIL` 2 → 1 (only the FFI
  manifest pin remains tracked).
- `docs/v1.2-conformance-status.md`: the staged-failure paragraphs and
  the int-add-overflow status reflect the landed disposition pair and
  the promotion.

## 6. Full-gate evidence

`flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh --jobs 1` exits 0
with the final banner `=== All Tests Passed ===` (captured run after
the change; every foreground and background suite green, including
`deal.test.JvmLaneStatePinTest` `Passed: 35, Failed: 0`,
`deal.test.StdlibTimePreActivationPinTest`, the sidecar corpus
validation, the differential gate corpus tests, the coverage manifest
corpus tests, `deal.test.BackendConformanceTest`, and
`deal.test.JvmBackendTest`).

## 7. Boundary discipline

- The fixture flip is exactly one edit to exactly the four pinned
  header lines; no body edit, no other fixture edit.
- No edit to `deal/codegen/jvm/JvmBackend.java`,
  `deal/module/CompilationOrchestrator.java`,
  `test/JvmConformanceTest.java`, or `test/JvmBackendTest.java`
  (byte-identical to the tree head).
- No JS/LuaJIT activation, no `test_stdlib.lua` edit, no
  `deal/runtime.js`/`deal/runtime.lua`/`std/*` edit.
- The bytes-core-gated sibling promotions are not landed;
  `deal/Main.java` stays `PRE_ACTIVATION` (release-owned cutover).
