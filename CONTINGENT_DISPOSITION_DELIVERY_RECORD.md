# ISSUE-0401 — Contingent Disposition Change-Set Delivery Record (pending-resolution determination)

Audit record. Purpose: evaluate the delivery gate condition for the locked
`std/time.nowMillis` disposition change sets at the canonical HEAD, record
the resulting determination (no delivery while the ISSUE-0237-tracked
resolution is pending), author the contingent change sets in recorded,
non-applied form, and capture the verification evidence the task pins
(design refs: `luajit-time-selector-disposition` D1/D2/D3/D4, Contracts,
Verification 1–10; `luajit-v1.2-stdlib-contracts` D6;
`luajit-v1.2-conformance-retirement-and-gate` D3).

Canonical revision: `22ad9e94f5ac34ca37efb845eab1bb9950c22761` (the
engine-imported canonical HEAD; the mandated rebase replays this
record's commit onto it).

Verdict: every authoritative repository record names the resolution as
**not yet landed** — the pending state is intact at the canonical HEAD.
Per the task's gate condition, the determination is **no delivery, no
edit, no landing**: the fixture keeps `// @expected: runtime-ok`,
`std/time.lua` stays byte-identical, and the staged registry keeps its
entry. The contingent change sets are authored below (section 3) and are
delivered only inside the single disposition-application unit (ISSUE-0372)
after the resolution lands exactly one disposition pair. The pre-delivery
verification, the combined-behavior scratch exercise, the full gate, and
the history discipline all pass at the canonical HEAD (section 5).

## 1. Gate-condition evaluation — the delivery determination

The task's gate condition: no delivery, no edit, and no landing occur
unless and until the ISSUE-0237-tracked resolution (legacy regression
authority ISSUE-0231) has landed exactly one disposition pair. The
authoritative resolution landing records were read one by one at the
canonical HEAD; **none names a landed disposition pair**:

| Record | Canonical-HEAD site | Content |
|---|---|---|
| Runner staged-registry doc | `test/ConformanceTest.java:144-161` | "the shared corpus fixture keeps its `runtime-ok` expectation and `std/time.lua` stays frozen until the delegated time-selector child (ISSUE-0237) lands its disposition pair" |
| Conformance status doc | `docs/v1.2-conformance-status.md:24-28` | "Until the ISSUE-0237 resolution lands its disposition pair, the LuaJIT backend-runtime gate additionally records one tracked staged failure (`stdlib-edge/time-now-millis-positive.deal`, locked E8004 artifact)" |
| Shared fixture header | `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal:3` | `// @expected: runtime-ok` (no `runtime-error` text; exactly one `@expected` line) |
| Direct stdlib suite | `test_stdlib.lua:1070-1076` | the two `nowMillis` cases assert the locked E8004 artifact through `assert_error_code` |

Determination: the resolution landing record does not name exactly one
landed disposition pair — the resolution is pending. **No delivery is
performed**: zero disposition edits exist in the working tree or the
canonical history (sections 2, 5.4). The "neither branch" rejection
branch of the task is also not triggered (the record is pending, not a
rejection); it is recorded as the standing rule in section 4.

## 2. T1 pre-activation state re-verified at the canonical HEAD

Every T1-pinned surface was re-read at the canonical HEAD and matches the
pinned pre-unit staged state (`PRE_UNIT_STAGED_STATE_PIN.md`):

- Fixture `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`:
  `:1` `// @spec: Standard library declarations — std/time`, `:2`
  `// @description: std/time.nowMillis returns a positive int-like timestamp`,
  `:3` `// @expected: runtime-ok`, `:4` `// @features: stdlib, time`,
  body unchanged with the `now <= 0` throw guard
  (`if (now <= 0) { throw { code: "TEST_FAIL", message: "time.nowMillis not positive" }; }`).
- Retained route `std/time.lua:9-10` byte-identical:
  `time.nowMillis = __rt.function_("()->int", function()` /
  `  return __rt.check_int(os.time() * 1000)`.
- Staged registry present: `STAGED_FAILURES` map `test/ConformanceTest.java:162-163`,
  `static {` at `:164`, the `stagedFailure(...)` registration call at
  `:165-174` pinning expectation `runtime-ok`, artifact `E8004`, owning
  issue `ISSUE-0237`, closing `}` at `:175`; the `StagedEntry` record
  (`:141-142`), the `stagedFailure` helper (`:177-181`), and the registry
  validation loop (`:226-231`) intact.
- Runner dispatch: the staged entry is consulted before any expectation
  evaluation (`:453-455`); the stale expectation-changed branch fails the
  gate with the promotion instruction naming the entry removal
  (`:456-468`, println `:461-466`); the stale passing branch is the
  analogous rule for a fixture passing its own expectation (`:625-631`,
  println `:626-629`).
- Landed precondition — int32 gate: `deal/runtime.lua` `check_int` carries
  the range check `if v < -2147483648 or v > 2147483647` at `:83` and
  raises E8004 `"int out of range"` at `:84` (ISSUE-0332, MR-0244 —
  ancestor of HEAD). The design checkout's `:78-79` drifted +5 to the
  canonical HEAD: ISSUE-0333 (`ddeed8e`) split the header comment into
  two lines (+1) and the ISSUE-0336 canonical descriptor cutover
  (`c0b708d`, MR-0270) reworked the file head (+4).
- Direct suite: the two E8004 cases sit at `test_stdlib.lua:1070-1076`
  and stay exactly as landed.
- Pin test `test/StdlibTimePreActivationPinTest.java`:
  `testRetainedLuaImplementation` `:95-109` (pins `std/time.lua:9-10`
  byte-exactly), `testRuntimeSeam` `:125-150` (pins the profile-gated JS
  seam: `setInt32Mode`, module-private `$int32`, the single `checkInt`
  range arm whose ±(2^53−1) legacy branch sits at `deal/runtime.js:1235`),
  `testFixtureHeader` `:156-180` (pins the pre-activation `runtime-ok`
  header, no `runtime-error` text, exactly one `@expected` line);
  lifecycle note `:33-45` (the fixture-header assertions are the
  unit-authorized flippable surface; the retained-implementation,
  seam-shape, skip-registry, and legacy-slice assertions are permanent at
  the canonical HEAD, where the JS int32-gate activation ISSUE-0321 has
  already landed). Executed green: `Passed: 36, Failed: 0` (section 5.3).
- Lock halves: `TIME_NOW_MILLIS` reserved
  (`deal/semantic/ir/StdlibFunctionId.java:50-52`, `"TIME_NOW_MILLIS"` at
  `:51`); `STDLIB_TIME_CONFLICT` routing marker
  (`deal/semantic/ir/SemanticCapability.java:47` "never lowered"); planner
  rule 2 `deal/semantic/MigrationPlanner.java:288`
  (`return ModuleRoute.LEGACY; // rule 2: never shared in any purpose`);
  the closed four-arm detector `deal/semantic/LoweringSupport.java:74-122`.
- Gate green pre-unit: `./run_tests.sh` exits 0 with exactly one tracked
  staged failure naming this fixture and ISSUE-0237 (section 5.3).

## 3. The contingent change sets (authored here, non-applied)

Both change sets are authored below in exact, ready-to-apply form. No
repository file is edited by this record; the change sets are delivered
only inside the disposition-application unit after the landing (D3).

### 3.1 Branch-1 change set — three items, one change (deliver only after the resolution lands the retained pair)

**Item 1 — the fixture flip.** `test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
changes its header to the canonical disposition header; the body is
unchanged (everything from the shared fixture's line 5 onward stays
byte-identical — the E8004 raises at the `nowMillis` exit check, so the
`now <= 0` throw guard is unreached):

```text
// @spec: Standard library declarations — std/time
// @description: std/time.nowMillis under the v1.2 signed-int32 gate — the retained ()->int route raises E8004 for contemporary epoch milliseconds (locked TIME_NOW_MILLIS artifact)
// @expected: runtime-error E8004
// @features: stdlib, time, runtime-errors
```

The `@expected` line is edited exactly once in exactly one landing change
(the disposition-application unit); no other change re-edits it.

**Item 2 — the staged-registry removal.** The `stagedFailure(...)`
registration for this fixture (`test/ConformanceTest.java:165-174`) is
removed in the same change — exactly this block, nothing else:

```java
        stagedFailure("backend-runtime/stdlib-edge/time-now-millis-positive.deal",
            "runtime-ok",
            "E8004",
            "ISSUE-0237",
            "the retained std/time.nowMillis ()->int route raises E8004 "
                + "for contemporary epoch milliseconds under the "
                + "signed-int32 gate (locked TIME_NOW_MILLIS artifact); "
                + "the fixture's runtime-ok expectation and std/time.lua "
                + "are frozen until the delegated time-selector child "
                + "lands its disposition pair");
```

Preserved (the runner's staged-failure machinery stays as classification
infrastructure): the `STAGED_FAILURES` map (`:162-163`), the `static {`
block and its closing `}` (`:164`, `:175`), the `StagedEntry` record
(`:141-142`), the `stagedFailure` helper (`:177-181`), and the registry
validation loop (`:226-231`). The removal is mandatory: a flipped fixture
with the entry still present fails the gate with the stale
expectation-changed promotion (`:456-468`). After the removal the runner
reports zero staged failures and the fixture executes under its flipped
expectation via the standard `runtime-error` path.

**Item 3 — the pin-test update.** `testFixtureHeader`
(`test/StdlibTimePreActivationPinTest.java:156-180`) updates to the
post-activation header in the same change. The exact replacement
(assertion block only; the method's name, structure, and the
`count(text, "@expected") == 1` check stay):

```java
        if (ls.size() >= 4) {
            check("// @spec: Standard library declarations — std/time".equals(ls.get(0)),
                "the fixture keeps the canonical @spec line");
            check("// @description: std/time.nowMillis under the v1.2 signed-int32 gate — the retained ()->int route raises E8004 for contemporary epoch milliseconds (locked TIME_NOW_MILLIS artifact)"
                    .equals(ls.get(1)),
                "the fixture carries the canonical disposition @description");
            check("// @expected: runtime-error E8004".equals(ls.get(2)),
                "the fixture declares @expected: runtime-error E8004 "
                    + "(the canonical disposition header)");
            check("// @features: stdlib, time, runtime-errors".equals(ls.get(3)),
                "the fixture carries the disposition @features line");
        }
        String text = Files.readString(fixture);
        check(!text.contains("// @expected: runtime-ok"),
            "the fixture no longer declares @expected: runtime-ok "
                + "(the disposition flip landed)");
```

(The pre-activation assertion block this replaces pins
`@description: std/time.nowMillis returns a positive int-like timestamp`,
`@expected: runtime-ok`, `@features: stdlib, time`, and
`!text.contains("runtime-error")` — `:165-175`.)

**Unchanged in this change set (zero diff required):** `testRuntimeSeam`
(`:125-150` — at the canonical HEAD the JS int32-gate activation
ISSUE-0321 has already landed, so the seam assertions pin the
profile-gated seam and are permanent per the lifecycle note; the task's
"retired by the JS activation owner" language is the pre-landing
formulation, already executed by ISSUE-0369 — see the T1 record §2.5);
`std/time.lua` (byte-identical — branch 1 changes no implementation);
`deal/runtime.lua`; `deal/runtime.js`; `test_stdlib.lua:1070-1076`; and
the lock files (`StdlibFunctionId.java`, `SemanticCapability.java`,
`MigrationPlanner.java`, `LoweringSupport.java`).

**Branch-1 post-state pins:** `std/time.lua` byte-identical; the two
`test_stdlib.lua` E8004 cases stay exactly as landed; the runner reports
zero staged failures; `TIME_NOW_MILLIS` stays reserved; `STDLIB_TIME_CONFLICT`
stays the never-lowered routing marker; `deal.semantic.MigrationPlannerTest`
(`testTimeConflictNeverShared`) and `deal.test.LoweringSupportTest` stay
green.

### 3.2 Branch-2 change set — conditional (deliver only if the authority lands an authoritative representable return contract)

1. **The implementation change lands in `std/time.lua`** (this epic's
   owned file) per the landed contract exactly: the contract's declared
   signature and representable return semantics are the resolution
   authority's landed input, consumed here and never invented (they are
   not in the design and remain the authority's pending input until
   landing). The recorded constraint for the change: the wrapper must
   implement the landed contract's declared signature and return an
   int32-representable positive value through the canonical
   `__rt.function_` surface, so the fixture's `runtime-ok` expectation
   passes. The fixture keeps `// @expected: runtime-ok` with its
   `now > 0` body.
2. **The staged-registry removal lands in the same change** — the same
   block as 3.1 item 2. A passing fixture with the entry still present
   trips the stale passing promotion (`:625-631`) — a gate failure until
   the entry is removed.
3. **The pin-test update:** `testRetainedLuaImplementation` (`:95-109`,
   pinning `std/time.lua:9-10` byte-exactly) retires/updates with the
   implementation change — its new pins follow the landed contract's
   implemented lines. The fixture-header assertions stay (the fixture
   keeps `runtime-ok`). At the canonical HEAD the seam-shape assertions
   already pin the post-activation profile-gated seam and stay (the JS
   activation landed; see 3.1).
4. **Lock preservation (identical to branch 1):** the `std/time.lua`
   change touches none of `StdlibFunctionId.java`, `SemanticCapability.java`,
   `MigrationPlanner.java`, or `LoweringSupport.java`; `TIME_NOW_MILLIS`
   stays reserved and `STDLIB_TIME_CONFLICT` stays the never-lowered
   routing marker; `deal.semantic.MigrationPlannerTest` and
   `deal.test.LoweringSupportTest` stay green.

## 4. Delivery protocol and never-standalone discipline

- When the resolution lands branch 1: the recorded 3.1 three-item change
  set is delivered as the LuaJIT-lane merge input into the single
  disposition-application unit (ISSUE-0372) — all three items in one
  change, the flip exactly one edit in exactly one landing change.
- When the resolution lands branch 2: the 3.2 change set is delivered per
  the landed contract.
- When the resolution lands neither branch: deliver nothing and record
  the rejection (the gate validity condition is unsatisfiable; the
  resolution is rejected).
- Never-standalone: no repository state lands the flip, the registry
  removal, or the pin-test update alone; no intermediate repository state
  fails the gate; the delivery is only the LuaJIT-lane merge input into
  the unit; a unit-level revert restores the pre-activation state (staged
  entry, `runtime-ok` header, pin test).

## 5. Verification evidence (captured at the canonical HEAD)

### 5.1 Pre-delivery confirmation (scripted, repeatable)

The committed script `verify_contingent_delivery_gate.sh` performs the
pre-delivery verification end to end: the T1 baseline pin, the
landing-record scan, the history-discipline checks, the combined-behavior
exercise (section 5.2), and the repository-clean check. Captured output:

```text
  T1 baseline pin OK: fixture @expected runtime-ok at :3 (body unchanged); std/time.lua :9-10 retained; staged entry present (test/ConformanceTest.java:165-174); pin-test pre-activation assertions present; lock halves present
  [runner registry doc] pending: '... stays frozen until the delegated time-selector child (ISSUE-0237) lands its disposition pair' (test/ConformanceTest.java:144-161)
  [conformance status doc] pending: 'Until the ISSUE-0237 resolution lands its disposition pair, the LuaJIT backend-runtime gate additionally records one tracked staged failure' (docs/v1.2-conformance-status.md:24-28)
  [fixture header] pending: '// @expected: runtime-ok' at :3, no runtime-error text, exactly one @expected line
  [direct suite] pending: the two nowMillis cases assert the locked E8004 artifact (test_stdlib.lua:1070-1076)
  determination: no authoritative record names a landed disposition pair — the resolution is pending; no delivery, no edit, no landing
```

### 5.2 Combined behavior — the T2-pinned branch-1 matching mechanism

The script runs the committed `verify_both_branches_scratch.sh` exercise
(exit 0, all six pinned results reproduced). The branch-1 matching pair —
the recorded 3.1 change set (flipped fixture clone + emptied registry)
plus the retained implementation, run through `deal.test.ConformanceTest`
with the scratch corpus root — passes exactly as the T2 mechanism pins:

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK (found DEAL_ERROR_CODE: E8004)

=== Conformance Summary ===
Total: 1, Passed: 1, Failed: 0, Skipped: 0, KnownFailures (tracked): 0, StagedFailures (tracked): 0
...
  LuaJIT backend-runtime conformance (v1.2): 1/1 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)
```

Zero staged failures, exit 0. The branch-1 mismatched pair is the real
pre-unit staged state; the branch-2 pairs and both unmodified-runner
interceptions are reproduced by the same exercise. No repository state
changed; every scratch tree deleted (`git status --porcelain
--untracked-files=all` empty after the run).

### 5.3 Full gate

`./run_tests.sh` exits 0 at the canonical HEAD; final banner
`=== All Tests Passed ===`. LuaJIT conformance summary:

```text
Total: 390, Passed: 390, Failed: 0, Skipped: 0, KnownFailures (tracked): 4, StagedFailures (tracked): 1
Companions (classified support modules): 32
  Frontend conformance (v1.2 grammar and semantics): 115/118 passed, 0 failed, 0 skipped, 3 known-fail (tracked), 0 staged-fail (tracked)
  LuaJIT backend-runtime conformance (v1.2): 275/277 passed, 0 failed, 0 skipped, 1 known-fail (tracked), 1 staged-fail (tracked)
```

The single staged entry is exactly the locked time fixture:

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] STAGED-FAIL (E8004 locked artifact; tracked by ISSUE-0237: the retained std/time.nowMillis ()->int route raises E8004 for contemporary epoch milliseconds under the signed-int32 gate (locked TIME_NOW_MILLIS artifact); the fixture's runtime-ok expectation and std/time.lua are frozen until the delegated time-selector child lands its disposition pair)
```

Pin test: `Passed: 36, Failed: 0`; direct LuaJIT stdlib suite
`Passed: 60, Failed: 0, Skipped: 0`; the rebase-added async-export
driver suite (`e220da0`, ISSUE-0416) reports `All 20 async export
driver tests passed`; lock-running suites green (`LoweringSupportTest`,
`MigrationPlannerTest` with `testTimeConflictNeverShared`, and all
remaining suites in the same run).

### 5.4 History discipline (task's Discipline verification)

- `git log -S 'stagedFailure("backend-runtime/stdlib-edge' --oneline --
  test/ConformanceTest.java` → exactly one commit (`b22dc8e`, ISSUE-0332):
  the registration was added once and never removed.
- `git log -S 'runtime-error E8004' --oneline --
  test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal`
  → zero commits: the fixture's `@expected` line was never edited to the
  disposition expectation.
- `git log --oneline -1 -- std/time.lua` → `4eba239` (the pre-epic v1.0
  rewrite): `std/time.lua` is byte-identical through the epic.
- `git diff HEAD` over every protected surface (fixture, `std/time.lua`,
  `test/ConformanceTest.java`, `test/StdlibTimePreActivationPinTest.java`,
  `deal/runtime.lua`, `deal/runtime.js`, `test_stdlib.lua`, and the lock
  files) → empty.
- The task's history (T1 `5d0273b`, T2 `512beea`, this record) contains
  audit records and verification scripts only: no disposition edit landed
  before the resolution landing and no piece of the change set merged
  standalone.

### 5.5 Repository state

`git status --porcelain --untracked-files=all` is empty after committing
this record and its script; the committed diff contains only
`CONTINGENT_DISPOSITION_DELIVERY_RECORD.md` and
`verify_contingent_delivery_gate.sh`.

## 6. Locator register (task citations vs the canonical HEAD)

| Site | Task citation | Canonical HEAD | Note |
|---|---|---|---|
| Staged registration | `test/ConformanceTest.java:150-159` | call `:165-174`, block `:164-175` | task window starts one line above the call at the design checkout; content identical |
| Stale expectation-changed rule | `:440-451` | `:456-468` (println `:461-466`) | earlier landings grew the file head |
| Stale passing rule | `:609-615` | `:625-631` (println `:626-629`) | same |
| Pin-test `testRetainedLuaImplementation` | `:93-107` | `:95-109` | ISSUE-0321 seam-pin edits |
| Pin-test `testRuntimeSeam` | `:123-149` | `:125-150` | same |
| Pin-test `testFixtureHeader` | `:150-179` | `:156-180` | same |
| Pin-test lifecycle note | `:33-43` | `:33-45` | same |
| `deal/runtime.lua` int32 gate | `:78-79` | `:83-84` (range check `:83`, raise `:84`) | cumulative +5: ISSUE-0333 (`ddeed8e`) header-comment split (+1); ISSUE-0336 canonical descriptor cutover (`c0b708d`, MR-0270) reworked the file head (+4) |
| `deal/runtime.js` ±(2^53−1) arm | `:427-428` | `:1235` (legacy branch of the profile-gated condition `:1233-1235`) | ISSUE-0321 landed the profile-gated seam |
| `test_stdlib.lua` nowMillis cases | `:1034-1042` | `:1070-1076` | `bc93e41` re-pinned both cases to `assert_error_code` |
| `StdlibFunctionId.java` reservation | `:50-52`, `:51` | exact | — |
| `MigrationPlanner.java` rule 2 | `:287-288` | `:287-288` | — |
| `LoweringSupport.java` Arms A–D | — | `:74-122` | — |
| Pin-test launch in `run_tests.sh` | `:436` | `:513` | later suites added launch blocks above; the rebase-added async-export driver suite (`e220da0`, ISSUE-0416) inserted seven lines above it |

## 7. Conclusion

The delivery gate condition holds at the canonical HEAD: the resolution
is pending in every authoritative record, the T1 pre-activation state is
intact, no disposition edit exists in the working tree or the history,
the recorded change sets are delivered only inside the disposition-
application unit after the landing, the combined-behavior verification
reproduces the branch-1 matching pass and both branches' pair results by
their specified mechanisms, and `./run_tests.sh` exits 0. This record's
own lifecycle is the pre-unit audit state: when the resolution lands, the
unit delivers the recorded branch change set and this pending-state pin
(and the T1/T2 artifacts it consumes) is superseded by the unit's
post-activation state.
