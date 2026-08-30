# ISSUE-0399 — Pre-Unit Staged-State Pin and Pending-Resolution Discipline

Audit record. Purpose: pin the pre-unit staged state of the locked
`std/time.nowMillis` selector at the canonical HEAD and prove that no
disposition edit has landed while the ISSUE-0237-tracked resolution is
pending (design refs: `luajit-time-selector-disposition` D1/D2, Contracts
post-state, Verification 7–9; `luajit-v1.2-stdlib-contracts` D6;
`luajit-v1.2-conformance-retirement-and-gate` D3).

Canonical revision pinned by this record: `fbe1aafc1f67ec989b5b1e58f99bc0dbc87af980`.

Method: read every named line range at the canonical HEAD; execute
`./run_tests.sh` and capture the exit code and the LuaJIT conformance
summary; inspect `git status` and `git diff HEAD` over every named file.
All commands run after the final rebase; the working tree is clean.

Verdict: every precondition of Sequencing step 1 holds at the canonical
HEAD; the repository records the pending resolution in all four
authoritative places; zero disposition edits are present in the working
tree or the canonical history; `./run_tests.sh` exits 0 with the gate
green (one tracked staged failure, non-fatal, named for this fixture and
ISSUE-0237).

Note on locators. The design-page citations in the task were verified
against checkout `9c2ab38`. The canonical HEAD contains later landed
children (MR-0244's ISSUE-0332 narrowing is already an ancestor;
ISSUE-0333, ISSUE-0321, ISSUE-0337, ISSUE-0369, and MR-0267's
ISSUE-0384 container-ops executor / E3018 checker gate / ISSUE-0406
comparison semantics also landed), so a few cited line numbers shifted
while their content is byte-identical. Each item below records the
canonical-HEAD site exactly; section 4 registers every locator delta
with its cause. No pin content failed: every substantive claim the task
makes about the staged state holds at the canonical HEAD.

## 1. Gate execution evidence (captured)

`./run_tests.sh` exit code: `0`; final banner `=== All Tests Passed ===`.

LuaJIT backend-runtime conformance summary (canonical HEAD):

```text
Total: 381, Passed: 381, Failed: 0, Skipped: 0, KnownFailures (tracked): 5, StagedFailures (tracked): 1
Companions (classified support modules): 30
```

The single staged entry is exactly the locked time fixture — the runner
prints the fixture path and the non-fatal record in the same run:

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal]
STAGED-FAIL (E8004 locked artifact; tracked by ISSUE-0237: the retained std/time.nowMillis ()->int route raises E8004 for contemporary epoch milliseconds under the signed-int32 gate (locked TIME_NOW_MILLIS artifact); the fixture's runtime-ok expectation and std/time.lua are frozen until the delegated time-selector child lands its disposition pair)
```

(The two lines are interleaved in the captured log with concurrent
background suites; both are verbatim from the same run.)

Count note: the task text cites `Total: 379` "verified at HEAD". At the
canonical HEAD the total is 381: ISSUE-0337 (commit `a6033b5`) added the
two `stdlib-edge` int32 fixtures after the `9c2ab38` checkout. The pins
that matter — 0 failed, 0 skipped, exactly one `StagedFailures (tracked)`
entry naming this fixture and ISSUE-0237, 5 tracked known-failures — all
hold.

Pin test (launched at `run_tests.sh:467-468`):

```text
=== std/time.nowMillis Pre-Activation Pin (ISSUE-0369) ===
=== std/time.nowMillis Pre-Activation Pin Tests (ISSUE-0369) ===
-- std/time.js retained ()->int wrapper --
-- std/time.lua retained check_int exit check --
-- JvmBackend emitStdlibTimeMemberCall retained emission --
-- deal/runtime.js seam shape --
-- shared corpus fixture header --
-- JVM skip registry --
-- legacy slice pins --
Passed: 36, Failed: 0
```

Lock-running suites (same run; the running pins of rule 2 and Arms A–D):

```text
=== Running Lowering Support / Requirement Manifest Tests (ISSUE-0289) ===
-- Arm A: direct module-object call claims --
...
Passed: 131, Failed: 0
=== Running Migration Planner / Route Plan Tests (ISSUE-0290) ===
-- STDLIB_TIME_CONFLICT (direct + propagated): LEGACY in every purpose --
...
Passed: 230, Failed: 0
```

Direct LuaJIT stdlib suite (same run): `Passed: 60, Failed: 0, Skipped: 0`.

New canonical-HEAD suites (MR-0267, same run, both green):

```text
=== Running Comparison Operand View and Executor Tests (ISSUE-0406, ISSUE-0234 B-D1/B-D2/B-D4) ===
-- ComparisonOperandView: closed shape --
-- INT32_EQ/NE/LT/LE/GT/GE --
...
Passed: 458, Failed: 0
=== Running Container Ops Executor Tests (ISSUE-0384 C3) ===
-- the closed value view renders the canonical actual kinds --
-- TABLE_NEW: source-order stores, duplicate keys, resolution order --
...
Passed: 130, Failed: 0
```

## 2. Precondition pins verified at the canonical HEAD

### 2.1 Landed precondition — int32 gate (`deal/runtime.lua`)

Pinned content: `check_int` narrows to `[-2147483648, 2147483647]` and
raises E8004 `"int out of range"` (landed by ISSUE-0332; merge MR-0244,
commit `6b005b5` — ancestor of the canonical HEAD).

Canonical-HEAD site: the function occupies `deal/runtime.lua:65-83`; the
range gate is `:79` and the E8004 raise is `:80`.

```lua
 79    if v < -2147483648 or v > 2147483647 then
 80      error(__rt._err("E8004", "int out of range", file, line, column, nil, nil))
```

Locator delta: the task cites the range gate at `:78` and the E8004 at
`:79` (verified against checkout `9c2ab38`). ISSUE-0333 (commit `ddeed8e`)
split the file header comment into two lines, shifting everything below
by exactly one line; the `check_int` body is byte-identical to
`9c2ab38:64-82` (verified by diff).

### 2.2 Landed precondition — retained route (`std/time.lua`)

Pinned content: `std/time.lua:1-13`, wrapper at `:9`, retained return at
`:10`. Exact match at the canonical HEAD (byte-identical to `9c2ab38`,
verified by `git diff`):

```lua
  9  time.nowMillis = __rt.function_("()->int", function()
 10    return __rt.check_int(os.time() * 1000)
```

### 2.3 Fixture unchanged (`time-now-millis-positive.deal`)

Pinned content: canonical `@spec`, the positive-timestamp `@description`,
`// @expected: runtime-ok` at `:3`, `// @features: stdlib, time`, and the
unchanged body with its `now <= 0` throw guard. Exact match (byte-identical
to `9c2ab38`):

```deal
  1  // @spec: Standard library declarations — std/time
  2  // @description: std/time.nowMillis returns a positive int-like timestamp
  3  // @expected: runtime-ok
  4  // @features: stdlib, time
...
  8  export function test_time_now_millis_positive(): int {
  9    let now: int = time.nowMillis();
 10    if (now <= 0) { throw { code: "TEST_FAIL", message: "time.nowMillis not positive" }; }
 11    return now;
 12  }
```

### 2.4 Staged registry present (`test/ConformanceTest.java`)

Pinned content: the registry doc pins the frozen state, and the
`stagedFailure(...)` registration pins expectation `runtime-ok`, artifact
`E8004`, owning issue `ISSUE-0237`, as a non-fatal `STAGED_FAIL`. Exact
match (file byte-identical to `9c2ab38`).

Registry doc at `:130-146` (verbatim core):

```java
 * <p>The single entry is the locked {@code TIME_NOW_MILLIS} artifact
 * ... the retained {@code std/time.nowMillis ()->int} route computes
 * {@code os.time() * 1000} (≈1.7e12 for contemporary epoch
 * milliseconds), which deterministically raises E8004 under the
 * signed-int32 gate landed by ISSUE-0332, while the shared corpus
 * fixture keeps its {@code runtime-ok} expectation and {@code
 * std/time.lua} stays frozen until the delegated time-selector child
 * (ISSUE-0237) lands its disposition pair.
```

Registration at `:148-161` (the call spans `:151-160`; the task's
`:150-159` window starts one line above the call):

```java
148    private static final Map<String, StagedEntry> STAGED_FAILURES =
149        new LinkedHashMap<>();
150    static {
151        stagedFailure("backend-runtime/stdlib-edge/time-now-millis-positive.deal",
152            "runtime-ok",
153            "E8004",
154            "ISSUE-0237",
155            "the retained std/time.nowMillis ()->int route raises E8004 "
156                + "for contemporary epoch milliseconds under the "
157                + "signed-int32 gate (locked TIME_NOW_MILLIS artifact); "
158                + "the fixture's runtime-ok expectation and std/time.lua "
159                + "are frozen until the delegated time-selector child "
160                + "lands its disposition pair");
161    }
```

Non-fatal classification: `private enum State { PASS, FAIL, SKIP, KNOWN_FAIL, COMPANION, STAGED_FAIL }` (`:110`); the runner records the locked artifact as `STAGED_FAIL` with the reason (`:601-606`) and the stale-entry rule fails the gate on any drift (`:440-452` expectation-changed branch, `:608-616` passing branch). Captured execution confirms the record (section 1).

### 2.5 Pin test green (`test/StdlibTimePreActivationPinTest.java`)

The pin test passes at the canonical HEAD: 36/36 (section 1). It is
launched at `run_tests.sh:467-468`:

```sh
467  echo "=== std/time.nowMillis Pre-Activation Pin (ISSUE-0369) ==="
468  java -ea -cp build deal.test.StdlibTimePreActivationPinTest
```

Pinned assertions (canonical-HEAD method sites; the task-cited ranges
`:93-107` / `:123-149` / `:150-179` sit at `:95-109` / `:125-150` /
`:156-180` after the ISSUE-0321 seam-pin edit):

- `testRetainedLuaImplementation` (`:95-109`) pins `std/time.lua:9-10`
  byte-exactly: the `()->int` wrapper line at index 8 and the retained
  `return __rt.check_int(os.time() * 1000)` at index 9, appearing exactly
  once.
- `testRuntimeSeam` (`:125-150`) pins the JS seam shape: no `nowMillis`,
  `Date`, or `currentTimeMillis` member; `setInt32Mode` present; the
  module-private `$int32` flag false at load; `checkInt`'s final range arm
  consults the flag; each signed-32 bound and each ±(2^53−1) bound appears
  exactly once.
- `testFixtureHeader` (`:156-180`) pins the fixture's canonical `@spec`,
  the positive-timestamp `@description`, `@expected: runtime-ok`, the
  `@features` line, no `runtime-error` text, exactly one `@expected` line.

Seam note (canonical state): at the canonical HEAD the JS int32-gate
activation has already landed — `deal/runtime.js` carries the
profile-gated seam (ISSUE-0321, commit `28e1d94`, ancestor of HEAD):
`let $int32 = false;` at `deal/runtime.js:73`, the `setInt32Mode` selector
at `:1151-1157`, and `checkInt`'s final arm at `:1188-1191` with the
±(2^53−1) legacy branch at `:1190`:

```js
1188    if ($int32
1189        ? (v < -2147483648 || v > 2147483647)
1190        : (v < -9007199254740991 || v > 9007199254740991)) {
1191      $rt.fail("E8004", "int out of safe range", file, line, column);
```

The ±(2^53−1) arm is therefore still present at the canonical HEAD, as the
task pins; it sits at `:1190`, not `:427-428` (that window is now
`$fromJsonConvert`'s surrogate scan — runtime growth after the design
checkout). The pin test's lifecycle note (`:33-45`) states the current
discipline: the fixture-header assertions retire in the disposition unit;
the retained-implementation, seam-shape, skip-registry, and legacy-slice
assertions are permanent.

### 2.6 Direct suite state (`test_stdlib.lua`)

Pinned content: the two `nowMillis` E8004 cases exist and assert the
locked E8004 artifact through `assert_error_code` (`:29-40`). Verified at
the canonical HEAD — the cases sit at `test_stdlib.lua:1070-1076`:

```lua
1070  test("time.nowMillis raises E8004 under the signed-int32 gate", function()
1071    assert_error_code(function() timelib.nowMillis.f() end, "E8004")
1072  end)
1073
1074  test("time.nowMillis ratio case raises E8004 under the signed-int32 gate", function()
1075    assert_error_code(function() timelib.nowMillis.f() end, "E8004")
1076  end)
```

Canonical-state notes: the design checkout's `:1028-1042` window held the
unsatisfied-gate comment (`:1027-1033` at `9c2ab38`) and two cases, the
second of which asserted the canonical `"int out of range"` message.
Commit `bc93e41` removed the comment and replaced that message case with
the ratio case, re-pinning both `nowMillis` cases to the E8004 code
through `assert_error_code` — the wiki's own post-state pin is "the two
`test_stdlib.lua` E8004 cases stay as landed", and these two are the
canonical-HEAD cases. The unsatisfied-gate record at the canonical HEAD
lives in the two authoritative places: `test/ConformanceTest.java:130-146`
(section 2.4) and `docs/v1.2-conformance-status.md:24-28`:

```text
Until the ISSUE-0237 resolution lands its disposition pair, the LuaJIT
backend-runtime gate additionally records one tracked staged failure
(`stdlib-edge/time-now-millis-positive.deal`, locked E8004 artifact) — a
non-fatal interim state the runner verifies every run; any drift fails the
gate (see Classification contract).
```

### 2.7 Lock preserved (both halves)

- `TIME_NOW_MILLIS` reserved: `deal/semantic/ir/StdlibFunctionId.java:50-52`
  — `RESERVED_NAMES` holds exactly `"TIME_NOW_MILLIS"` at `:51`.
- Validator dispatch: `deal/semantic/ir/SemanticIrValidator.java:139`
  defines `R_RESERVED_NAME = "R-RESERVED-NAME"`; it is member 12 of the
  closed 14-rule list at `:148-151`; `checkReservedName` (`:2341-2353`)
  dispatches a `StdlibFunctionId` selector or call function named
  `TIME_NOW_MILLIS` to `R_RESERVED_NAME` (`:2344-2346`, `:2350-2352`).
- E8004 registered: `deal/diagnostics/DiagnosticCode.java:302` —
  `E8004(Phase.RUNTIME, "Integer out of safe range")`.
- `STDLIB_TIME_CONFLICT` unpromotable: `deal/semantic/ir/SemanticCapability.java:47-48`
  — "Routing marker: the module references std/time.nowMillis (never
  lowered)"; class doc `:13-16`.
- Planner rule 2: `deal/semantic/MigrationPlanner.java:287-288` —
  `return ModuleRoute.LEGACY; // rule 2: never shared in any purpose`,
  reached before rule 4 in every purpose and before any shadow entry.
- Closed four-arm detector: `deal/semantic/LoweringSupport.java:74-122`
  — "Closed capability claims" contract with Arms A–D (A: direct
  module-object access plus the alias→module-path join `:82-91`; B:
  Table-typed access in a direct `std/time` importer `:92-99`; C:
  Table-typed access in the transitive import closure `:100-113`; D:
  claim propagation `:114-121`).
- Running pins: `deal.test.LoweringSupportTest` exercises the four arms
  (Arm A direct call/value-position `test/LoweringSupportTest.java:220-269`,
  Arm B `:279-330`, Arm C and Arm D sections) and passed 131/0;
  `deal.semantic.MigrationPlannerTest.testTimeConflictNeverShared`
  (`test/MigrationPlannerTest.java:440-475`) pins LEGACY in every purpose,
  empty `shadowModules`, zero diagnostics, and passed as part of 230/0
  (both suites launched by `run_tests.sh:244` and `:248`).

### 2.8 Gate green pre-unit

`./run_tests.sh` exits 0 at the canonical HEAD with the summary in
section 1: 0 failed, 0 skipped, exactly one tracked staged failure naming
`backend-runtime/stdlib-edge/time-now-millis-positive.deal` and
ISSUE-0237, five tracked known-failures. The gate validity condition
remains unsatisfied by design (D3's operationalized interim state) — the
gate stays green through the staged registry, never through the fixture.

## 3. Pending-discipline evidence

- `git status --porcelain --untracked-files=all` over the working tree:
  empty output (this audit document is the only addition and edits none of
  the pinned surfaces).
- `git diff HEAD -- <named files>` over the fixture, `std/time.lua`,
  `test/ConformanceTest.java`, `test/StdlibTimePreActivationPinTest.java`,
  `deal/runtime.lua`, `deal/runtime.js`, `StdlibFunctionId.java`,
  `SemanticCapability.java`, `MigrationPlanner.java`, `LoweringSupport.java`,
  `DiagnosticCode.java`, `SemanticIrValidator.java`,
  `MigrationPlannerTest.java`, `test_stdlib.lua`: empty output.
- `std/time.lua` is byte-identical to HEAD (and to `9c2ab38`).
- No disposition edit — fixture flip, staged-registry removal, pin-test
  update, or `std/time.lua` change — is authored into any repository path.
- Canonical history: the landed state is the design-sanctioned interim
  state (MR-0244's narrowing + staged registry + pin test, gate green);
  the disposition-application unit has not landed, matching the pending
  resolution records in `test/ConformanceTest.java:130-146`,
  `docs/v1.2-conformance-status.md:24-28`, the fixture's `runtime-ok`
  header, and the executed `StagedFailures (tracked): 1` summary.

## 4. Locator drift register (design checkout `9c2ab38` → canonical HEAD)

| Site | Cited (task / design page) | Canonical HEAD | Cause |
|---|---|---|---|
| `deal/runtime.lua` `check_int` | `:64-82`, range gate `:78`, E8004 `:79` | `:65-83`, gate `:79`, E8004 `:80` | ISSUE-0333 (`ddeed8e`) split the header comment into two lines; body byte-identical |
| `deal/runtime.js` ±(2^53−1) arm | `:427-428`, unparameterized pre-activation arm | `:1190`, legacy branch of the profile-gated arm (`:1188-1191`) | ISSUE-0321 (`28e1d94`) landed the profile-gated seam; pin test updated by ISSUE-0369 (`f00f132`) |
| `test_stdlib.lua` nowMillis cases | `:1028-1042`, incl. canonical-message case + unsatisfied record | `:1070-1076`, two code-only E8004 cases; record lives in `ConformanceTest.java:130-146` + `docs/v1.2-conformance-status.md:24-28` | `bc93e41` re-pinned both cases to `assert_error_code`; ISSUE-0337 added cases above |
| `run_tests.sh` pin-test launch | `:436` (task) / `:457-458` (design checkout) | `:467-468` | MR-0267 added the ComparisonExecutor (`a11fc02`) and ContainerOpsExecutor (`88cdb45`) launch blocks above it |
| `DiagnosticCode.java` E8004 | `:300` (task) | `:302` | E3018 gate (`ea561a8`) inserted the new code at `:224-225`, shifting E8004 down two lines; registration content unchanged |
| Pin-test method ranges | `:93-107` / `:123-149` / `:150-179` | `:95-109` / `:125-150` / `:156-180` | ISSUE-0321 seam-pin edits |
| `ConformanceTest.java` registration | `:150-159` (task) / `:148-161` (wiki) | call `:151-160`, block `:148-161` | task window starts one line above the call |
| LuaJIT conformance total | 379 | 381 | ISSUE-0337 (`a6033b5`) added two `stdlib-edge` fixtures |

Exact matches (no delta): `std/time.lua:9-10`; the fixture `:1-4` and
body; `ConformanceTest.java:130-146` doc; `StdlibFunctionId.java:50-52`;
`MigrationPlanner.java:287-288`; `MigrationPlannerTest.java:440-475`;
`SemanticCapability.java` member and class doc; `LoweringSupport.java:74-122`
Arms A–D; `SemanticIrValidator.java` `R_RESERVED_NAME` and the closed
14-rule list (MR-0267 did not touch the validator).

## 5. Conclusion

Every precondition named in Sequencing step 1 is verified at the
canonical HEAD with captured execution evidence; every named line range
was read and its pinned content confirmed (section 2 lists the
canonical-HEAD site where a cited locator drifted). The repository
records the resolution as pending in all authoritative places; the
working tree contains no disposition edit; `std/time.lua` is
byte-identical to HEAD; the gate is green with exactly one non-fatal
staged failure naming this fixture and ISSUE-0237. The pending-resolution
discipline (D1/D2) holds: this record lands no disposition edit and
consumes no branch selection.
