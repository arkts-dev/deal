# ISSUE-0400 — Both-Branch Verification via Isolated Scratch Exercise

Audit record. Purpose: exercise the four disposition-pair cases of the
locked `std/time.nowMillis` selector and pin each result to exactly the
mechanism that produces it, using isolated scratch trees only (design
refs: `luajit-time-selector-disposition` D4, Contracts both-branch
verification contract, Verification 1–5 and 10;
`luajit-v1.2-stdlib-contracts` D6 and Verification 5;
`luajit-v1.2-conformance-retirement-and-gate` D3).

Canonical revision: `673fea12558576886ce2c61a977ebabec67907db`
(the canonical HEAD after the mandated rebase; the pre-unit staged state
pinned by ISSUE-0399, `PRE_UNIT_STAGED_STATE_PIN.md`). This record was
originally captured at `ef152ada8f57894c1a96e8ed99e5f284ff00d5dc`
(MR-0274's merge); between the two revisions the canonical line landed
MR-0272 (ISSUE-0408 `StructuredBodyTable` / `ControlFlowValidator`),
ISSUE-0366 (boundary-table corpus), and ISSUE-0367 (boundary integration
tail). None of those landings touches the runner, the stdlib, the
fixture, or the conformance corpus — `test/ConformanceTest.java`, `std/`,
`deal/runtime.lua`, and the shared fixture are byte-identical across the
rebase — so every substantive pin below holds unchanged at the
re-anchored HEAD (section 8 registers the re-anchor delta).

Method: pin the pre-unit state T1 (fixture `// @expected: runtime-ok`
at `:3`, retained `std/time.lua:9-10`, staged entry present at
`test/ConformanceTest.java:164-176`), then execute six isolated scratch
configurations through the committed repeatable exercise script
`verify_both_branches_scratch.sh`. Every run rebuilds a scratch copy of
the repository tree from T1, diffs the scratch base against T1's pinned
state (a mismatch aborts), applies only scratch-local edits, compiles the
scratch runner, executes it over a scratch conformance root, asserts the
exact pinned result lines and exit code, verifies the repository is
untouched, and deletes the scratch tree. All commands were executed in
the working tree; the repository files `test/ConformanceTest.java`,
`std/time.lua` and `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
are byte-identical to HEAD before and after the exercise (section 5).

Verdict: all six pinned results were reproduced by their specified
mechanisms — branch-1 matching pair passes with zero staged failures
(run A), the branch-1 stale-entry flip without the registry removal
fails the gate with the promotion instruction naming the removal
(run B), the branch-2 matching clone passes and the mismatched clone
fails under the empty-registry scratch runner configuration (runs C, D),
and the unmodified runner intercepts both branch-2 clones before any
expectation evaluation, recording the stale passing promotion for the
matching clone and the stale expectation-changed promotion for the
mismatched clone (runs E, F). The branch-1 mismatched pair is the real
pre-unit state: `./run_tests.sh` exits 0 with exactly one
`StagedFailures (tracked)` entry naming this fixture and ISSUE-0237
(section 4). No repository state changed.

## 1. Mechanism map (the six pinned results and the mechanism that produces each)

| Run | Pair case | Configuration (scratch-local) | Pinned result |
|---|---|---|---|
| A | Branch-1 matching | fixture clone flipped to the canonical D3 disposition header (body unchanged) + scratch `test/ConformanceTest.java` compiled with the `stagedFailure(...)` registration absent + retained `std/time.lua` untouched | PASS `runtime-error E8004`, zero staged failures, exit 0 |
| B | Branch-1 stale-entry | fixture header flipped only; staged entry still present | gate failure with the promotion instruction naming the registry-entry removal, exit 1 |
| C | Branch-2 matching | empty staged-failure registry + scratch passing `std/time` stand-in at the scratch tree's `std/` + `runtime-ok` clone at the corpus-relative path | PASS, exit 0 |
| D | Branch-2 mismatched | same scratch configuration, clone expectation `runtime-error E8004` (same body) | FAIL — no E8004 is raised, exit 1 |
| E | Unmodified-runner interception (matching clone) | unmodified runner (staged entry present) + stand-in + `runtime-ok` clone | stale passing promotion (`test/ConformanceTest.java:626-629`), exit 1 |
| F | Unmodified-runner interception (mismatched clone) | unmodified runner + stand-in + `runtime-error E8004` clone | stale expectation-changed promotion (`test/ConformanceTest.java:461-466`), exit 1 |

The branch-1 mismatched pair (retained implementation + `runtime-ok`)
is exercised on the real runner as the pre-unit staged state (section 4),
combined with the run-B stale-entry demonstration against the same
pinned registry state.

## 2. T1 baseline pin (consumed by every scratch run)

Before each scratch run the script re-verifies the T1 pin and diffs the
scratch base against it; any mismatch aborts the exercise:

- Fixture `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`:
  `:1` `// @spec: Standard library declarations — std/time`, `:2`
  `// @description: std/time.nowMillis returns a positive int-like timestamp`,
  `:3` `// @expected: runtime-ok`, `:4` `// @features: stdlib, time`,
  and the unchanged `now > 0` body
  (`if (now <= 0) { throw { code: "TEST_FAIL", ... } }`).
- Retained route `std/time.lua`: `:9`
  `time.nowMillis = __rt.function_("()->int", function()`, `:10`
  `  return __rt.check_int(os.time() * 1000)`.
- Staged entry present: `test/ConformanceTest.java:162-175` — the
  `STAGED_FAILURES` map, the `static {` block at `:164`, and the
  `stagedFailure(...)` registration at `:165-174` pinning expectation
  `runtime-ok`, artifact `E8004`, owning issue `ISSUE-0237`. (The task
  cites the window `:150-159`; at the canonical HEAD the registration
  call spans `:165-174` — content identical, locator shifted by later
  landed children, see section 6.)
- Scratch-base diffs: the scratch copies of `test/ConformanceTest.java`,
  `std/time.lua` and `deal/runtime.lua` are diffed against the repository
  files before each run; a mismatch aborts.

The script printed the pin check before the exercise and before every
run:

```text
  T1 baseline pin OK: fixture @expected runtime-ok at :3; std/time.lua :9-10 retained; staged entry present (test/ConformanceTest.java:164-176, call :165-174)
```

## 3. Scratch-layout constraints honored by the exercise

The scratch runner's mechanics make three layout constraints binding
(canonical-HEAD sites in `test/ConformanceTest.java`):

1. The runner copies `deal/runtime.lua` (`:1074`) and every `std/*.lua`
   (`:1103-1116`) from its own working directory, and the generated
   chunk resolves `require` through `./std/?.lua` (`:1168`, `:1196`).
   Therefore each scratch tree is a copy of the repository tree carrying
   `deal/runtime.lua` and the full `std/` (the `.d.deal` declarations the
   compiler scans for the declared `()->int` shape), and the scratch
   passing stand-in sits at the scratch tree's `std/time.lua` — a
   stand-in inside the scratch conformance root alone is never loaded.
2. The conformance root is passed as the runner's corpus argument
   (`:194-199`); each scratch root holds exactly the scratch clone of the
   shared fixture at the corpus-relative path
   `backend-runtime/stdlib-edge/time-now-millis-positive.deal`.
3. The staged registry is validated against the discovered corpus every
   run (`:225-231`) — an entry naming no discovered fixture is a
   configuration failure — so the unmodified-runner interceptions (runs
   E, F) only reach their stale-entry dispatch (`:453`) because the
   clone sits at the registered corpus-relative path.

The stand-in keeps the current `()->int` wrapper shape and returns a
fixed int32-representable positive value:

```lua
time.nowMillis = __rt.function_("()->int", function()
  return __rt.check_int(42)
end)
```

The canonical D3 disposition header the run-A/run-B clone is flipped to
(body byte-identical to the shared fixture — verified by diff inside the
script):

```text
// @spec: Standard library declarations — std/time
// @description: std/time.nowMillis under the v1.2 signed-int32 gate — the retained ()->int route raises E8004 for contemporary epoch milliseconds (locked TIME_NOW_MILLIS artifact)
// @expected: runtime-error E8004
// @features: stdlib, time, runtime-errors
```

The run-D/run-F clone changes exactly the `@expected` line to
`// @expected: runtime-error E8004`; the body is byte-identical to the
shared fixture (verified by diff inside the script).

## 4. Captured runner evidence per configuration

Each scratch tree compiled its own runner
(`javac --release 25 -proc:none -d build ...` over the scratch `deal/`
sources and the scratch `test/ConformanceTest.java`) and ran
`java -ea -cp build deal.test.ConformanceTest <scratch-root>/coroot`.
The scratch roots are per-run temp directories (random suffixes).
Captured output below is verbatim from the per-run logs produced by
`verify_both_branches_scratch.sh` in the final rebased-HEAD execution
(evidence dir `/tmp/issue0400-evidence-rerun`).

### Run A — branch-1 matching pair (isolated mechanism)

Scratch-local edits: fixture clone flipped to the canonical header
(body unchanged); the `stagedFailure(...)` registration absent from the
scratch `test/ConformanceTest.java` before compilation; `std/time.lua`
untouched (diffed against the repository file).

```text
=== DEAL v1.2 Conformance Test Suite ===
Root: /tmp/issue0400-runA.eFWfSO/coroot
LuaJIT: available

Discovered 1 conformance test(s)

  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK (found DEAL_ERROR_CODE: E8004)

=== Conformance Summary ===
Total: 1, Passed: 1, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
Companions (classified support modules): 0
...
  LuaJIT backend-runtime conformance (v1.2): 1/1 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
```

Exit code: 0. Pinned result: PASS on `runtime-error E8004` with zero
staged failures — the mechanism Verification 1 names for the post-unit
pass.

### Run B — branch-1 stale-entry (registry entry still present)

Scratch-local edits: fixture clone flipped to the canonical header only;
the scratch runner compiled from the unmodified
`test/ConformanceTest.java` (diffed against the repository file before
compilation).

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] FAIL (STALE staged entry: 'backend-runtime/stdlib-edge/time-now-millis-positive.deal' is now classified 'runtime-error E8004' instead of the pinned 'runtime-ok' — the ISSUE-0237 child applied its disposition: remove the registry entry)

=== Conformance Summary ===
Total: 1, Passed: 0, Failed: 1, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
```

Exit code: 1. Pinned result: the stale-entry rule fails the gate with the
promotion instruction naming the registry-entry removal
(`test/ConformanceTest.java:461-466`) — the flip without the removal
cannot land.

### Run C — branch-2 matching pair (empty-registry scratch runner configuration)

Scratch-local edits: registration absent from the scratch runner;
scratch passing stand-in at the scratch tree's `std/time.lua`;
verbatim `runtime-ok` clone of the shared fixture at the corpus-relative
path.

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK

=== Conformance Summary ===
Total: 1, Passed: 1, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
Companions (classified support modules): 0
...
  LuaJIT backend-runtime conformance (v1.2): 1/1 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
```

Exit code: 0. Pinned result: the matching clone passes under the
empty-registry scratch configuration.

### Run D — branch-2 mismatched pair (empty-registry scratch runner configuration)

Scratch-local edits: same as run C, with the clone's `@expected` line
`runtime-error E8004` (same body).

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] FAIL (expected DEAL_ERROR_CODE: E8004, got: )

=== Conformance Summary ===
Total: 1, Passed: 0, Failed: 1, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
```

Exit code: 1. Pinned result: the mismatched clone fails — the passing
stand-in raises no E8004, so the `runtime-error E8004` expectation is
unsatisfied (the empty `got:` is the xpcall runner's clean output, i.e.
no DEAL error at all).

### Run E — unmodified runner interception of the matching clone

Scratch-local edits: unmodified runner (staged entry present, diffed
against the repository file); scratch stand-in; verbatim `runtime-ok`
clone.

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] FAIL (STALE staged entry: the fixture now passes 'runtime-ok' — the ISSUE-0237 child landed a passing disposition: remove the registry entry)

=== Conformance Summary ===
Total: 1, Passed: 0, Failed: 1, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
```

Exit code: 1. Pinned result: the hardcoded staged entry intercepts the
matching clone before any expectation evaluation and records the stale
passing promotion (`test/ConformanceTest.java:626-629`).

### Run F — unmodified runner interception of the mismatched clone

Scratch-local edits: unmodified runner; scratch stand-in;
`runtime-error E8004` clone.

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] FAIL (STALE staged entry: 'backend-runtime/stdlib-edge/time-now-millis-positive.deal' is now classified 'runtime-error E8004' instead of the pinned 'runtime-ok' — the ISSUE-0237 child applied its disposition: remove the registry entry)

=== Conformance Summary ===
Total: 1, Passed: 0, Failed: 1, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
```

Exit code: 1. Pinned result: the staged interception fires before any
expectation evaluation and records the stale expectation-changed
promotion (`test/ConformanceTest.java:461-466`). Because the unmodified
runner can never observe the branch-2 behavioral pair — both clones fail
for the registry reason alone — the empty-registry scratch configuration
is the specified mechanism that produces the behavioral pair results
(runs C and D).

### Branch-1 mismatched pair — the real pre-unit staged state

The retained implementation plus `runtime-ok` is exercised by the real
repository runner at the rebased canonical HEAD: `./run_tests.sh` exits
0 and the LuaJIT conformance run records exactly one tracked staged
failure naming this fixture and ISSUE-0237 (captured in this work,
full-gate log `/tmp/issue0400-gate-rebased.txt`; the summary is
identical to the `ef152ad` capture because the runner and the corpus are
byte-identical across the rebase):

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] STAGED-FAIL (E8004 locked artifact; tracked by ISSUE-0237: the retained std/time.nowMillis ()->int route raises E8004 for contemporary epoch milliseconds under the signed-int32 gate (locked TIME_NOW_MILLIS artifact); the fixture's runtime-ok expectation and std/time.lua are frozen until the delegated time-selector child lands its disposition pair)
...
=== Conformance Summary ===
Total: 390, Passed: 390, Failed: 0, Skipped: 0, KnownFailures (tracked): 4, StagedFailures (tracked): 1
Companions (classified support modules): 32
...
  Frontend conformance (v1.2 grammar and semantics): 115/118 passed, 0 failed, 0 skipped, 3 known-fail (tracked), 0 staged-fail (tracked)
  LuaJIT backend-runtime conformance (v1.2): 275/277 passed, 0 failed, 0 skipped, 1 known-fail (tracked), 1 staged-fail (tracked)
...
    ISSUE-0237: 1 staged fixture(s)
```

`./run_tests.sh` exit code: 0; final banner `=== All Tests Passed ===`;
pin test green (`Passed: 36, Failed: 0`). The rebased gate additionally
runs the three canonical suites landed between `ef152ad` and `673fea1`
in the same execution — `Boundary Table Corpus Tests` (ISSUE-0366),
`Boundary Integration Tests` (ISSUE-0367), and `Control Flow Validator
Tests` (ISSUE-0408) — all green. The fixture fails its own `runtime-ok`
expectation (the E8004 artifact) and the registry tracks that mismatch
non-fatally — the exact pre-unit demonstration the verification contract
names for this pair.

## 5. Repository untouched; scratch trees discarded

After every scratch run the script captured
`git status --porcelain --untracked-files=all` over the repository and
deleted the scratch tree. In the original capture (before the exercise
artifacts were committed) each capture showed only the untracked
exercise script; at the rebased canonical HEAD both artifacts are
committed, so every per-run capture of the two fresh full executions
(evidence dirs `/tmp/issue0400-evidence-final` and
`/tmp/issue0400-evidence-rerun`) is empty — never
`test/ConformanceTest.java`, `std/time.lua`, or the fixture.

`git diff HEAD -- test/ConformanceTest.java std/time.lua
test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
is empty after the exercise. The runner's own temp dirs
(`deal_conf_*`) are deleted by the runner itself (`test/ConformanceTest.java:1124-1129`).

## 6. Neither-branch rejection (Verification 5)

A resolution landing neither disposition pair leaves the gate validity
condition `expectation(fixture) == landed nowMillis behavior`
unsatisfiable: the fixture stays `runtime-ok` against the retained
int32-raising implementation, so the staged registry must keep tracking
the mismatch forever, and any flip without a landed pair fails the
stale-entry rule (run B). The gate can only close when exactly one pair
lands and this epic's contingent change set is applied inside the
disposition-application unit (`luajit-time-selector-disposition` D1/D2).
No repository state is involved in this record.

## 7. Repeatability

`verify_both_branches_scratch.sh` rebuilds every scratch tree from
repository state and re-verifies the T1 pin before every run; a fresh
execution must reproduce all six pinned results or the script exits 1.
Two full executions at the rebased canonical HEAD (evidence dirs
`/tmp/issue0400-evidence-final` and `/tmp/issue0400-evidence-rerun`) both
exited 0 with all assertions satisfied:

```text
ALL SIX PINNED RESULTS REPRODUCED BY THEIR SPECIFIED MECHANISMS
  (branch-1 matching pass / branch-1 stale-entry failure / branch-2 matching pass / branch-2 mismatched failure / two unmodified-runner interceptions)
repository files never modified; every scratch tree deleted
```

## 8. Count and locator drift register (canonical HEAD vs the ISSUE-0399 pin and design checkout)

| Site | Cited | Canonical HEAD | Cause |
|---|---|---|---|
| Runner corpus-argument parse | `test/ConformanceTest.java:180-184` | `:194-199` | earlier landings grew the file head |
| Registry validation loop | `:204-217` | `:225-231` | same |
| Stale expectation-changed branch | `:440-451` | `:456-468` (println `:461-466`) | same |
| Stale passing branch | `:609-615` | `:622-632` (println `:626-629`) | same |
| `deal/runtime.lua` copy | `:1057-1058` | `:1073-1074` | same |
| `std/*.lua` copy | `:1086-1097` | `:1102-1116` | same |
| Staged registration window | `:150-159` | call `:165-174`, block `:164-175` | task window starts one line above the call at the design checkout |
| LuaJIT conformance totals | 384 passed, 5 known-fail (ISSUE-0399 pin's captured run) | 390 passed, 4 known-fail | MR-0270 (ISSUE-0336 canonical descriptor cutover, commit `c0b708d`) added the five `backend-runtime/descriptors/canonical-*.deal` fixtures (+5), and MR-0263 (ISSUE-0322, commit `892aa70`) promoted `frontend/types/bytes-type-reference.deal` from known-fail to OK (−1 known-fail) — both landed between the pin's captured revision `a2cdf27` and HEAD `ef152ad` |

Re-anchor delta (`ef152ad` → `673fea1`): the mandated rebase replayed
this record's two artifacts onto the canonical revision that carries
MR-0272 (ISSUE-0408 `StructuredBodyTable` / `ControlFlowValidator`),
ISSUE-0366, and ISSUE-0367. Zero locator movement on every surface this
record pins — `test/ConformanceTest.java`, `std/`, `deal/runtime.lua`,
and the shared fixture are byte-identical across the rebase. The only
tree delta is `run_tests.sh` gaining the three new suite blocks (+15
lines: javac entries `:72-74`, launches `:308`/`:312`/`:316`), which
shifts the pin-test launch from `:477-478` to `:492-493`; the LuaJIT
conformance summary is unchanged (390 passed, 4 known-fail, 1 staged).

The substantive pins all hold at the re-anchored HEAD: 0 failed,
0 skipped, exactly one `StagedFailures (tracked)` entry naming this
fixture and ISSUE-0237, fixture `runtime-ok` at `:3`,
`std/time.lua:9-10` retained, staged registration present at `:165-174`,
and `./run_tests.sh` exit 0.

## 9. Conclusion

Every pinned pair result was produced by exactly the mechanism the
design specifies for it, captured from isolated scratch trees built from
the pinned T1 state, with the repository untouched throughout. The
branch-1 mismatched pair is demonstrated by the real pre-unit staged
state (gate green, one tracked staged failure), and the stale-entry
demonstration proves a flip without the registry removal fails the gate.
The branch-2 pairs are observable only through the empty-registry
scratch runner configuration — the unmodified runner's hardcoded staged
entry intercepts both clones before any expectation evaluation, which
runs E and F demonstrate directly. The exercise is repeatable through
the committed script (two fresh full executions at the rebased canonical
HEAD `673fea1`, both exit 0), changes no repository state, and the
engine gate `./run_tests.sh` exits 0 at the same HEAD.
