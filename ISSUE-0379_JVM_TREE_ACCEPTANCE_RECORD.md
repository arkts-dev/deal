# ISSUE-0379 — JVM Int32 Gate Activation Tree Acceptance Record (merge-input report for ISSUE-0372)

Audit record. Purpose: close the tree's acceptance evidence — the
diff discipline, the documented staged lane state, and the merge-input
report (head SHA, diff stat) handed to the disposition-application unit
(design refs: `jvm-int32-gate-activation-tree` D6/D7,
Verification 4/5/6; the epic's tree criteria and exclusions).

Canonical revision: `8212f23417e0df03e0b90d78db973af51c39cf6d`
(the engine-imported canonical HEAD; the mandated rebase replays this
record's commit onto it). The pre-tree base is
`a5f683ab829796cd5b2d42eade7c47ecdee86321` (derivation in section 2.1).

Method: every acceptance line below is backed by captured command or
suite output recorded verbatim in this record (anti-hollow). The
repeatable script `verify_jvm_tree_acceptance.sh` (committed in this
change) performs the file-set, forbidden-surface, fixture-discipline,
byte-identity, profile-immutability, history, and staged lane-re-run
checks end to end; its captured run is section 10. All commands were
executed after the final rebase; the working tree is clean.

Verdict: **the tree is merge-ready and its acceptance evidence is
closed.** The diff against the pre-tree base scoped to the activation
surface lists exactly the four JVM activation files; the whole-tree
change set (union of the pinned tree commits) is exactly the pinned
12-file set — the four activation files plus the lane's sanctioned gate
wiring and the restored known-fail fixture trio — with zero JS/Lua/
runtime-file changes and zero fixture-expectation flips.
`emitStdlibTimeMemberCall` is byte-identical to the pre-tree base. The
profile surface stays closed (`PRE_ACTIVATION → LEGACY_SAFE_INT`
defaults intact; no source/CLI/environment profile surface). The
staged lane state is re-confirmed by a real run: `deal.test.JvmConformanceTest`
exits 1 with exactly the pinned failure set (the unflipped time fixture
raising E8004 against `runtime-ok`; the stale-known-fail gate naming
`test/conformance/backend-runtime/arithmetic/int-add-overflow.deal`
with the promotion instruction — never papered over), while
`deal.test.BackendConformanceTest` (with `jvm-std-time-nowmillis`
green) and `deal.test.JvmBackendTest` pass, and the full gate exits 0.
The tree head SHA and the diff stat are recorded below as the merge
input for the disposition-application unit.

## 1. The merge-input report (hand-off block for ISSUE-0372)

- **Tree head SHA:** `8212f23417e0df03e0b90d78db973af51c39cf6d`
  (`git rev-parse HEAD` at acceptance; the acceptance commit itself
  adds only this record and the verification script — no activation
  content).
- **Pre-tree base SHA:** `a5f683ab829796cd5b2d42eade7c47ecdee86321`
  (the main-line revision immediately before the first JVM-tree
  commit; section 2.1).
- **Diff stat against the pre-tree base (activation files):**

```text
 deal/codegen/jvm/JvmBackend.java         | 3270 ++++++++++++++++++++-----
 deal/module/CompilationOrchestrator.java | 2349 +++++++++++++-----
 test/JvmBackendTest.java                 | 3926 ++++++++++++++++++++++++++----
 test/JvmConformanceTest.java             |  817 +++++--
 4 files changed, 8415 insertions(+), 1947 deletions(-)
```

  (This scoped stat is the cumulative change over the four activation
  files from the pre-tree base to the tree head; the interleaved
  ISSUE-0301 identity-carriage landings contribute to `JvmBackend.java`
  and the two test files inside this range — the tree's own per-commit
  stats are the exact tree contribution in section 2.3.)
- **Four activation files (the design D6 surface):**
  `deal/codegen/jvm/JvmBackend.java` (profile-gated carrier/range
  switch + declared-int-boundary seam), `deal/module/CompilationOrchestrator.java`
  (the single `codegenAllJvm` profile plumb), `test/JvmConformanceTest.java`
  (the lane's explicit `DEAL_V1_2_INT32` invocation), `test/JvmBackendTest.java`
  (the new int32/time-boundary/legacy byte-compat pins).
- **Whole-tree change set:** the union of the ten pinned tree commits
  equals exactly the 12-file set in section 2.3 — the four activation
  files plus the lane's sanctioned gate wiring (`test/JvmLaneStatePinTest.java`,
  `test/LegacyProfileRegressionCatalog.java`, `run_tests.sh`,
  `deal/test/conformance/SidecarCorpusValidationTest.java`,
  `deal/test/conformance/DifferentialGateCorpusTest.java`) and the
  restored known-fail fixture trio
  (`test/conformance/backend-runtime/arithmetic/int-add-overflow.deal`,
  `int-add-overflow.expect.json`,
  `test/conformance/fixtures/jvm-v1.2-known-fail.json`) whose
  `@expected: known-fail runtime-error E8004` + `@issue` markers are
  the stale gate's pinned trigger. No `std/*.js`, no `std/*.lua`, no
  `deal/runtime.js`, no `deal/runtime.lua`, no `std/time.*` file is in
  the tree change set; no fixture expectation is flipped (section 2.4/2.5).
- **History discipline:** the delivered change set (canonical import →
  tree head) contains **zero merge commits** — no merge into the
  repository main line is created by this tree (section 3).
- **Staged lane state (documented, never papered over):**
  `deal.test.JvmConformanceTest` fails only in the two pinned ways on
  the unmerged tree (section 6); the mirror `jvm-int32-add-overflow`
  stays a tracked known-fail under the untouched legacy
  `deal.test.BackendConformanceTest` (section 7). These close only
  inside the ISSUE-0372 unit merge plus the sanctioned promotions
  (`jvm-v12-int32-bytes` D6 — not this tree).
- **Unit contract (recorded, not executed here):** the unit merges
  this tree head, applies the canonical fixture header exactly once,
  alters nothing else from this tree, and post-unit the JVM lane passes
  the fixture as `runtime-error E8004` at the declared int boundary
  with `./run_tests.sh` exiting 0.

## 2. Diff discipline evidence

### 2.1 The pre-tree base (derivation)

The first JVM-tree commit on the main line is `85a9e1f` ("Plumb the
invocation SemanticProfile into JvmBackend with a LEGACY_SAFE_INT
default" — the D2 plumb, T2 of the activation series). Its parent is
the pre-tree base:

```text
$ git rev-parse 85a9e1f~1
a5f683ab829796cd5b2d42eade7c47ecdee86321
$ git show -s --format="%h %s" a5f683a
a5f683a Merge MR-0250: LuaJIT activation tree: check_int signed32 narrowing and test_stdlib.lua E8004 case updates
```

At this revision the four activation files are in their pre-tree state:
`JvmBackend.java` carries only the legacy ±(2^53−1) `long` helpers
(the `emitStdlibTimeMemberCall` body sits at `:10419-10424`, the
design page's cited pre-tree locator), `CompilationOrchestrator.java`
has no profile plumb, `JvmConformanceTest.java` runs the legacy lane,
and `JvmBackendTest.java` has no int32 pins.

### 2.2 The four-file scoped diff (captured commands and outputs)

```text
$ git diff a5f683a..HEAD --name-only -- deal/codegen/jvm/JvmBackend.java deal/module/CompilationOrchestrator.java test/JvmConformanceTest.java test/JvmBackendTest.java
deal/codegen/jvm/JvmBackend.java
deal/module/CompilationOrchestrator.java
test/JvmBackendTest.java
test/JvmConformanceTest.java
```

Exactly the four activation files — none missing, none extra. The stat
of the same scoped diff is the 8415/1947 block in section 1.

Engine-reality note (the main-line range): between the pre-tree base
and the tree head the engine's canonical line landed other issues
(measured at the canonical revision before this record's commit: 471
files changed across the full un-scoped range, 68 MR merge commits —
ISSUE-0301's identity carriage, the FFI/lane/JS suites, ISSUE-0415,
etc.; this record and its script add two more files to the un-scoped
count and no activation content). The tree's own change set is therefore verified at
the granularity the acceptance criteria demand: the union of the
pinned tree commits (section 2.3), whose only overlap with the
interleaved work is additive coexistence in the four activation files.
The scoped four-file diff above is the diff-discipline command the
criteria name, and it lists exactly the four activation files.

### 2.3 The whole-tree combined file set (per-commit, captured)

The ten pinned tree commits and their exact per-commit stats
(`git show --stat`, verbatim):

```text
== 85a9e1f Plumb the invocation SemanticProfile into JvmBackend with a LEGACY_SAFE_INT default
 deal/codegen/jvm/JvmBackend.java         | 107 +++++++++++++++++++++--
 deal/module/CompilationOrchestrator.java |  35 +++++++-
 test/JvmBackendTest.java                 | 143 +++++++++++++++++++++++++++++++
== 6302649 Implement the DEAL_V1_2_INT32 carrier/range switch in JvmBackend (ISSUE-0375)
 deal/codegen/jvm/JvmBackend.java | 516 ++++++++++++++++++++++++++++++++-------
 test/JvmBackendTest.java         | 424 +++++++++++++++++++++++++++++++-
== cc81dc1 Fix int32 minimum literal -2147483648: fold NEG(IntLiteral 2147483648) into the Java int literal (ISSUE-0375 review)
 deal/codegen/jvm/JvmBackend.java | 28 +++++++++++++--
 test/JvmBackendTest.java         | 75 +++++++++++++++++++++++++++++++++-------
== 46a9828 Fix int32 boundary seam at number() argument and jsonable default-expression sites (ISSUE-0375 review)
 deal/codegen/jvm/JvmBackend.java |  31 +++++-
 test/JvmBackendTest.java         | 197 +++++++++++++++++++++++++++++++++++++++
== 05f3974 Complete the declared-int-boundary seam: signed32 checkInt at every int boundary, no silent narrowing (ISSUE-0376)
 deal/codegen/jvm/JvmBackend.java | 162 ++++++----
 test/JvmBackendTest.java         | 651 +++++++++++++++++++++++++++++++++++++++
== a9aa359 ISSUE-0377: pin the retained time boundary — exact-once E8004 at the declared int boundary and byte-identity of emitStdlibTimeMemberCall
 test/JvmBackendTest.java | 97 +++++++++++++++++++++++++++++++++++++++++-------
== 4f0e57d ISSUE-0269: migrate canonical ISSUE-0377 int32 host helpers off the retired DealConfig reader
 test/JvmBackendTest.java | 16 ++++++++++------
== 11c35e9 Implement ISSUE-0476: activated JVM corpus lane, restored known-fail trigger, re-pinned mirrors, and the anti-hollow lane-state pin test
 .../conformance/SidecarCorpusValidationTest.java   |  57 ++--
 run_tests.sh                                       |  26 ++
 test/JvmConformanceTest.java                       | 158 +++++++---
 test/JvmLaneStatePinTest.java                      | 339 +++++++++++++++++++++
 test/LegacyProfileRegressionCatalog.java           |  12 +
 .../arithmetic/int-add-overflow.deal               |  13 +
 test/conformance/fixtures/jvm-v1.2-known-fail.json |   8 +-
== 3aa3727 ISSUE-0476: re-pin the LuaJIT lane summary to the current tree (frontend ffi-manifest known-fail fixture landed via MR-0362)
 test/JvmLaneStatePinTest.java | 10 +++++++---
== 3afa871 ISSUE-0476: reconcile the pinned lane state with the rebased canonical tree (differential gate sidecar, population pins, JVM summary re-pin)
 .../conformance/DifferentialGateCorpusTest.java    | 52 +++++++-----
 .../conformance/SidecarCorpusValidationTest.java   | 94 ++++++++++++++--------
 test/JvmLaneStatePinTest.java                      |  4 +-
 .../arithmetic/int-add-overflow.expect.json        | 19 +++++
```

The union of the ten commits' changed files (captured via
`git diff --name-only c~1..c | sort -u`) is exactly:

```text
deal/codegen/jvm/JvmBackend.java
deal/module/CompilationOrchestrator.java
deal/test/conformance/DifferentialGateCorpusTest.java
deal/test/conformance/SidecarCorpusValidationTest.java
run_tests.sh
test/conformance/backend-runtime/arithmetic/int-add-overflow.deal
test/conformance/backend-runtime/arithmetic/int-add-overflow.expect.json
test/conformance/fixtures/jvm-v1.2-known-fail.json
test/JvmBackendTest.java
test/JvmConformanceTest.java
test/JvmLaneStatePinTest.java
test/LegacyProfileRegressionCatalog.java
```

This is the whole-tree combined check: the file-set assertion fails if
any dependency's files are missing or extra (asserted by the committed
script, section 10). The eight non-activation members are the lane
realization's sanctioned support files: the anti-hollow pin test
(`JvmLaneStatePinTest.java`, ISSUE-0378 D5) that owns both real lane
runs on every gate run, the profile-authority catalog, the gate wiring
(`run_tests.sh` substitution + the two sidecar re-pins), and the
restored known-fail fixture trio that the design's D7 pinned staged
state presupposes on disk at the tree head.

### 2.4 Forbidden-surface negatives (captured)

Over the tree change set (the 12-file union):

```text
$ (tree-change-set) | grep -E '^(std/.*\.(js|lua)|deal/runtime\.(js|lua)|std/time\..*)$'
(empty)
```

Zero `std/*.js`, zero `std/*.lua`, zero `deal/runtime.js`, zero
`deal/runtime.lua`, zero `std/time.*` changes. The only
`test/conformance/**` members of the tree change set are the three
sanctioned known-fail lane fixtures named above — the restored
`int-add-overflow.deal` + `.expect.json` pair and the
`jvm-v1.2-known-fail.json` mirror re-pin. No other fixture appears.

### 2.5 Fixture discipline (captured)

```text
$ head -5 test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal
// @spec: Standard library declarations — std/time
// @description: std/time.nowMillis returns a positive int-like timestamp
// @expected: runtime-ok
// @features: stdlib, time
$ git diff a5f683a..HEAD --name-only -- test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal std/time.lua std/time.js
(empty)
```

The shared time fixture keeps its `runtime-ok` expectation (the flip
is exactly one edit in exactly one landing change, owned by the
ISSUE-0372 unit), and it, `std/time.lua`, and `std/time.js` are
byte-identical to the pre-tree base. The delegated promotion is NOT
applied on this tree:

```text
$ head -4 test/conformance/backend-runtime/arithmetic/int-add-overflow.deal
// @spec: Runtime execution model — Integer arithmetic overflow
// @description: int addition outside the signed 32-bit range raises E8004 (v1.2: int is [-2147483648, 2147483647])
// @expected: known-fail runtime-error E8004
// @issue: ISSUE-0111
```

and the mirror `jvm-v1.2-known-fail.json` still tracks
`jvm-int32-add-overflow`, `jvm-int32-literal-out-of-range` (frontend
E1036), and `jvm-bytes-buffer-ops` under `"knownFail": "ISSUE-0111"`
(the ISSUE-0381 promotions — dropping the markers — are the later
sanctioned edit, not this tree's).

## 3. History discipline (no merge into the main line; head SHA)

The delivered change set of this tree (canonical import → HEAD)
contains no merge commit:

```text
$ git merge-base --is-ancestor 8212f23417e0df03e0b90d78db973af51c39cf6d HEAD && echo ancestor
ancestor
$ git log --merges --format=%h 8212f23417e0df03e0b90d78db973af51c39cf6d..HEAD
(empty)
$ git rev-parse HEAD
8212f23417e0df03e0b90d78db973af51c39cf6d
```

The tree is a merge input for the disposition-application unit, never
a standalone landing; the engine reality is recorded for the reviewer:
the activation series reached the canonical line through the
dependencies' own MRs (MR-0254 for the ISSUE-0375 switch, the
ISSUE-0374/0376/0377 landings, MR-0363 for the activated lane), and
the un-scoped pre-tree-base..HEAD range therefore also carries the 68
interleaved main-line merges of unrelated issues. None of those is
part of the tree's change set (section 2.3), and this tree adds no
merge of its own.

## 4. Byte identity of `emitStdlibTimeMemberCall` (captured)

Pre-tree base (`git show a5f683a:deal/codegen/jvm/JvmBackend.java`,
the function at `:10419`) and tree head (`deal/codegen/jvm/JvmBackend.java:12113`)
— both bodies byte-for-byte:

```java
    private String emitStdlibTimeMemberCall(MemberAccessExpr mae, CallExpr call) {
        if (!"nowMillis".equals(mae.field())) {
            unsupported("export '" + mae.field() + "' of std/time", mae.span());
            return "null";
        }
        return "(java.lang.System.currentTimeMillis() / 1000L) * 1000L";
    }
```

`diff -u` between the two extracted bodies is empty. No time algorithm
is added or changed; the E8004 surfaces only through the activated
declared-int-boundary seam, never from a time change. (The committed
script extracts and diffs both bodies on every run, section 10.)

## 5. Profile immutability (captured pins)

- `deal/semantic/ReleaseConfiguration.java:36-37`:
  `public static final ReleaseState CURRENT_RELEASE_STATE = ReleaseState.PRE_ACTIVATION;`
  — the public activation flip is release-owned (E12), not this tree's.
  (The design page's cited `deal/Main.java:247` locator drifted: the
  CLI's PRE_ACTIVATION resolution now lives at `deal/Main.java:209-211`
  through `ReleaseConfiguration.CURRENT_RELEASE_STATE`; the substantive
  pin is unchanged.)
- `deal/Main.java:209-211`:
  `CompilerInvocation invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE, ReleaseConfiguration.releaseCapabilityRegistry());`
- `deal/module/CompilationOrchestrator.java:650-654`
  (`defaultInvocation()`): resolves through `CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE, ReleaseConfiguration.releaseCapabilityRegistry())` —
  the provider is the only invocation constructor.
- `deal/semantic/ir/SemanticProfile.java`: the closed
  `{LEGACY_SAFE_INT, DEAL_V1_2_INT32}` set; source and the public CLI
  cannot select the profile.
- `grep -n 'System.getProperty\|System.getenv' deal/codegen/jvm/JvmBackend.java deal/module/CompilationOrchestrator.java deal/Main.java`
  → empty: no system-property or environment profile surface.
- Backend mode derivation (`JvmBackend.java:1256`):
  `this.int32Mode = semanticProfile == SemanticProfile.DEAL_V1_2_INT32;`
- Lane invocation (`test/JvmConformanceTest.java:222-224`):
  `CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry());`
- `test/BackendConformanceTest.java` keeps the untouched legacy
  invocation (`SemanticProfile.LEGACY_SAFE_INT` at `:1365`, `:1740`,
  `:1902`, `:2703`) — the legacy regression authority stays green.

## 6. Staged lane state re-confirmed (real run, captured)

```text
$ java -ea -cp build deal.test.JvmConformanceTest test/conformance/
<exit code 1>
```

The captured failure set (verbatim lines from the run):

```text
  [backend-runtime/arithmetic/int-add-overflow.deal] OK (found DEAL_ERROR_CODE: E8004)
  [backend-runtime/arithmetic/int-add-overflow.deal] FAIL (STALE known-fail: the v1.2 requirement tracked by ISSUE-0111 now passes on JVM — promote the fixture: set '@expected: runtime-error E8004' and drop the @issue tag)
  ...
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] FAIL (runtime-ok test exited 1): DEAL_ERROR_CODE: E8004 int out of safe range
```

and the gate summary block:

```text
=== JVM Conformance Summary (ISSUE-0102 origin — ISSUE-0168 capability accounting) ===
Frontend (backend-neutral compile-ok/compile-error): total 6, passed 6, failed 0
Backend-runtime on JVM: denominator 301 (every on-disk runtime test, unchanged), passed 252, failed 1, skipped 47 (classified), known-fail 0 (tracked) — pass rate 83.7%
Profile-authority accounting: 0 legacy-authority fixture(s) (LEGACY_REGRESSION + LEGACY_SAFE_INT — zero v1.2/promotion credit; 0 passed, 0 failed)
...
GATE FAILURE: 1 stale known-fail marker(s) — promote the fixture(s)
GATE FAILURE: 1 applicable backend-runtime test(s) failed — zero applicable failures required
```

Exactly the pinned failure set — the unflipped time fixture raising
E8004 at the declared int boundary against its `runtime-ok`
expectation, and the stale-known-fail gate naming
`arithmetic/int-add-overflow.deal` with the promotion instruction
(set `@expected: runtime-error E8004`, drop the `@issue` tag). No
STAGED-FAIL line appears on the JVM lane. This is the same field-exact
set that `test/JvmLaneStatePinTest.java` (ISSUE-0378 D5) asserts on
every gate run — the lane state is pinned by a test, never papered
over with skip entries or legacy fallbacks. The remaining activated
int32 arithmetic corpus (E8001/E8004/E8005/E8006 conversions,
overflow, div-zero, neg-min, pow) passes on this same run.

## 7. Legacy suites (captured)

```text
$ java -ea -cp build deal.test.BackendConformanceTest
<exit code 0>
  [jvm-std-time-nowmillis] LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)
  [jvm-std-time-nowmillis] OK — std/time.nowMillis() returns a positive, recent, second-truncated epoch milliseconds value (LuaJIT's os.time() * 1000 granularity — the % 1000 === 0 pin)
  ...
  [jvm-int32-add-overflow] KNOWN-FAIL (v1.2 not yet implemented; tracked by ISSUE-0111)
        [jvm-int32-add-overflow] LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)
        [jvm-int32-add-overflow] FAIL: expected error 'E8004', got: 2147483648
=== Backend Conformance Summary (DEAL v1.2) ===
Total: 523, Passed: 523, Failed: 0, Skipped: 0, KnownFailures (tracked): 3
```

`jvm-std-time-nowmillis` stays green on the legacy regression path with
unchanged source and expected result; the mirror `jvm-int32-add-overflow`
keeps failing under the legacy ±(2^53−1) carriers (2147483648 stays in
range, its expected E8004 never fires) and stays a tracked known-fail —
no stale gate fires for it here.

```text
$ java -ea -cp build deal.test.JvmBackendTest
<exit code 0>
=== JVM Backend Test Summary ===
Passed: 2005, Failed: 0
```

The pin families include the profile-plumb derivation, the int32 time
boundary (`E8004 at the declared int boundary`), the declared-boundary
matrix, the signed32 helper edge matrix, and the legacy byte-compat
pins — all green.

## 8. Full gate (captured)

```text
$ flock /tmp/igelhaus-deal-tests.lock ./run_tests.sh --jobs 1
<exit code 0>
=== Compiling all DEAL sources and tests ===
...
=== Launching JVM Lane State Pin Tests (ISSUE-0378: both real lanes run inside the pin test with field-exact failure-set assertions) ===
...
=== JVM Lane State Pin Test (ISSUE-0378 D5 anti-hollow evidence) ===
-- Invocation pin --
.-- JVM lane exact-output pin (real subprocess run) --
........................-- JVM emission smoke --
...
=== Waiting for background suites ===
=== All Tests Passed ===
```

The compile step exits 0; the gate is green with the staged lane state
pinned by `JvmLaneStatePinTest` (the substitution that runs both real
lanes and asserts their failure sets field-exactly on every run).
`./run_tests.sh` green as the *unit's* post-condition (the unflipped
fixture passing as `runtime-error E8004`) remains the disposition
unit's acceptance — not this tree's.

## 9. Engine-reality note

The epic's "the tree is a merge input, never a standalone landing" is
realized in this repository as: the activation series' commits reached
the canonical line through the dependencies' MRs; the acceptance
deliverable of this issue is the evidence record plus the repeatable
verification script, and the merge-input report above (head SHA, diff
stat, file set, staged state) is the hand-off the ISSUE-0372 unit
consumes. The canonical-import → HEAD change set of this MR contains
zero merges and no activation or fixture content (section 3) — the
tree's capability state at the canonical revision is exactly what the
unit merges.

## 10. Repeatable verification (committed script, captured run)

The committed `verify_jvm_tree_acceptance.sh` performs sections 2–8's
checks end to end (four-file diff, whole-tree file set, forbidden
surfaces, fixture discipline, byte identity, profile immutability,
no-merge/history, and the staged lane re-run against the compiled
`build/`). Captured run at the canonical HEAD:

```text
$ bash verify_jvm_tree_acceptance.sh /tmp/issue0379-evidence-run1
<exit code 0>
== 1. Four-file diff discipline ==
  OK: the scoped diff against the pre-tree base lists exactly the four activation files
== 2. Whole-tree combined file set ==
  OK: the tree change set equals the pinned whole-tree file set exactly
== 3. Forbidden surfaces ==
  OK: no std/*.js, std/*.lua, deal/runtime.js, deal/runtime.lua, or std/time.* file appears in the tree change set
  OK: the only test/conformance/** members are the sanctioned known-fail lane fixtures (restored int-add-overflow.deal + expect.json, mirror re-pin)
== 4. Fixture discipline ==
  OK: the shared time fixture keeps its runtime-ok expectation (the flip is the unit's edit)
  OK: the time fixture has exactly one @expected line
  OK: the time fixture, std/time.lua, and std/time.js are byte-identical to the pre-tree base
  OK: the delegated promotion is not applied: int-add-overflow.deal still carries '@expected: known-fail runtime-error E8004' and '@issue'
  OK: the mirror jvm-int32-add-overflow stays a tracked known-fail in jvm-v1.2-known-fail.json
== 5. Byte identity: emitStdlibTimeMemberCall ==
  OK: emitStdlibTimeMemberCall is byte-identical to the pre-tree base; no time algorithm added or changed
== 6. Profile immutability ==
  OK: ReleaseConfiguration.CURRENT_RELEASE_STATE stays pinned to PRE_ACTIVATION
  OK: the public CLI derives the public profile from CURRENT_RELEASE_STATE (PRE_ACTIVATION → LEGACY_SAFE_INT)
  OK: the orchestrator default invocation derives from CURRENT_RELEASE_STATE via the provider (never constructed directly)
  OK: SemanticProfile stays the closed {LEGACY_SAFE_INT, DEAL_V1_2_INT32} set
  OK: no system-property/environment profile surface exists in the backend, orchestrator, or CLI
  OK: the backend derives the int32 mode from the invocation's SemanticProfile
  OK: the JVM corpus lane carries the explicit V1_2_ACTIVE invocation
  OK: BackendConformanceTest keeps the untouched legacy invocation (the legacy regression authority)
== 7. History discipline ==
  OK: the delivered change set contains no merge commit (no merge into the repository main line)
  OK: tree head SHA recorded: 8212f23417e0df03e0b90d78db973af51c39cf6d
== 8. Staged lane re-run ==
  OK: the real JVM lane exits 1 (the sanctioned pinned staged state)
  OK: the unflipped time fixture raises E8004 against runtime-ok
  OK: the stale-known-fail gate names arithmetic/int-add-overflow.deal with the promotion instruction
  OK: the stale-known-fail GATE FAILURE line
  OK: the applicable-failures GATE FAILURE line
  OK: the pinned lane summary
  OK: no STAGED-FAIL line on the JVM lane (the pinned failure set is exact)
=== ISSUE-0379 tree acceptance verification passed ===
```

The script fails closed: any missing or extra file in the pinned set,
any forbidden-surface change, any fixture-expectation flip, any time-
expression divergence, any profile-surface leak, any merge commit, or
any divergence in the real lane's pinned failure set aborts it with a
named failure.
