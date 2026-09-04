# ISSUE-0401 — Contingent Disposition Change-Set Delivery Record (pending-resolution determination)

Audit record. Purpose: evaluate the delivery gate condition for the locked
`std/time.nowMillis` disposition change sets at the canonical HEAD, record
the resulting determination (no delivery while the ISSUE-0237-tracked
resolution is pending), author the contingent change sets in recorded,
non-applied form, and capture the verification evidence the task pins
(design refs: `luajit-time-selector-disposition` D1/D2/D3/D4, Contracts,
Verification 1–10; `luajit-v1.2-stdlib-contracts` D6;
`luajit-v1.2-conformance-retirement-and-gate` D3).

Canonical revision: `3886b534122159dc4b981aed8c40fcbb1788f67f` (the
engine-imported canonical HEAD; the mandated rebase replays this
record's commits onto it). The prior anchor was
`b8d4fe0a39873794698ed78ced3cccbd9fa7ec63`; the landings in between are
ISSUE-0339's LuaJIT bytes/int-unary-negation emitter slice with the
`bytes-buffer-ops` promotion, ISSUE-0342's std/json int32 number mapping
and stringify shape rejection, ISSUE-0328/0329's JS host-ABI emitter and
production async-export invoker, and ISSUE-0417's
LuaJitAsyncExportInvoker component and tests — none of them touches a
disposition surface (section 2), and every locator below is re-verified
against the new anchor (section 6).

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
| Direct stdlib suite | `test_stdlib.lua:1111-1117` | the two `nowMillis` cases assert the locked E8004 artifact through `assert_error_code` |

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
- Staged registry present: `STAGED_FAILURES` map `test/ConformanceTest.java:178-179`,
  `static {` at `:180`, the `stagedFailure(...)` registration call at
  `:181-190` pinning expectation `runtime-ok`, artifact `E8004`, owning
  issue `ISSUE-0237`, closing `}` at `:191`; the `StagedEntry` record
  (`:157-158`), the `stagedFailure` helper (`:193-197`), and the registry
  validation loop (`:243-248`) intact.
- Runner dispatch: the staged entry is consulted before any expectation
  evaluation (`:657-659`); the stale expectation-changed branch fails the
  gate with the promotion instruction naming the entry removal
  (`:660-673`, println `:665-670`); the stale passing branch is the
  analogous rule for a fixture passing its own expectation (`:843-848`,
  println `:845-847`).
- Gate-closure strict gate (ISSUE-0420): **present at this HEAD** — the
  ISSUE-0402 acceptance remediation removed the premature edit (the
  `runGateClosureCheck()` call and section), and the ISSUE-0477 cycle-2
  remediation re-landed it on the post-unit, post-promotions state per
  `luajit-gate-closure` D6 and epic Sequencing step 5: the call sits in
  the summary/exit path (`:289`) and the
  `runGateClosureCheck`/`runStrictModeGate`/`isSanctionedPreUnitPair`
  section spans `:342-445`. The +160-line insertion shifts every runner
  site below it (the dispatch and stale-rule citations above are the
  re-landed offsets). The exercise record is
  `POST_UNIT_GATE_CLOSURE_VERIFICATION.md` §3.
- Landed precondition — int32 gate: `deal/runtime.lua` `check_int` carries
  the range check `if v < -2147483648 or v > 2147483647` at `:83` and
  raises E8004 `"int out of range"` at `:84` (ISSUE-0332, MR-0244 —
  ancestor of HEAD). The design checkout's `:78-79` drifted +5 to the
  canonical HEAD: ISSUE-0333 (`ddeed8e`) split the header comment into
  two lines (+1) and the ISSUE-0336 canonical descriptor cutover
  (`c0b708d`, merged `7aff920d`, MR-0270) reworked the file head (+4).
  The landings between the prior anchor and this one change
  `deal/runtime.lua` only below the gate: ISSUE-0342 (`3c70d788`, merged
  `3886b534`) narrowed `_json_is_int` to the signed-int32 range
  (`:1430-1447`, int32 branch `:1443`) and expanded its doc comment —
  the gate stays `:83-84`.
- Direct suite: the two E8004 cases sit at `test_stdlib.lua:1111-1117`
  (ISSUE-0342 inserted the four std/json int32 cases above them, +41
  lines) and stay exactly as landed.
- Pin test `test/StdlibTimePreActivationPinTest.java`:
  `testRetainedLuaImplementation` `:95-109` (pins `std/time.lua:9-10`
  byte-exactly), `testRuntimeSeam` `:125-150` (pins the profile-gated JS
  seam: `setInt32Mode`, module-private `$int32`, the single `checkInt`
  range arm whose ±(2^53−1) legacy branch sits at `deal/runtime.js:1236`
  — ISSUE-0329's header expansion moved it +1 from the prior anchor's
  `:1235`; the profile-gated condition spans `:1234-1236`),
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
expectation-changed promotion (`:616-628`). After the removal the runner
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
`deal/runtime.lua`; `deal/runtime.js`; `test_stdlib.lua:1111-1117`; and
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
   trips the stale passing promotion (`:785-791`) — a gate failure until
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
  [direct suite] pending: the two nowMillis cases assert the locked E8004 artifact (test_stdlib.lua:1111-1117)
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
Total: 405, Passed: 405, Failed: 0, Skipped: 0, KnownFailures (tracked): 3, StagedFailures (tracked): 1
Companions (classified support modules): 32
  Frontend conformance (v1.2 grammar and semantics): 115/118 passed, 0 failed, 0 skipped, 3 known-fail (tracked), 0 staged-fail (tracked)
  LuaJIT backend-runtime conformance (v1.2): 290/291 passed, 0 failed, 0 skipped, 0 known-fail (tracked), 1 staged-fail (tracked)
```

Count note: the LuaJIT lane grew 390 → 405 across the re-anchor —
ISSUE-0339 (`8d6a78f4`) added 11 backend-runtime fixtures (the bytes
emitter family with the E8012/E8013 and source-location pins, plus
`arithmetic/int-neg-min` and its source-location pin) and promoted
`bytes-buffer-ops.deal` from known-fail to `runtime-ok` in the same slice
(LuaJIT known-fails 4 → 3; the backend-runtime known-fail 1 → 0), and
ISSUE-0342 added the three `stdlib/json` fixtures
(`int32-boundary-parse`, `json-stringify-bytes-error`,
`json-stringify-roundtrip`). The pins that matter — 0 failed, 0 skipped,
exactly one staged entry naming this fixture and ISSUE-0237 — all hold.

The ISSUE-0420 gate-closure strict gate is **not present** at this HEAD —
removed by the ISSUE-0402 acceptance remediation (section 2); the summary
above is the unchanged pre-unit gate output under the pre-ISSUE-0420
`failed > 0` exit only (the closure remains pending and is never asserted
until the strict gate re-lands post-unit).

The single staged entry is exactly the locked time fixture:

```text
  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] STAGED-FAIL (E8004 locked artifact; tracked by ISSUE-0237: the retained std/time.nowMillis ()->int route raises E8004 for contemporary epoch milliseconds under the signed-int32 gate (locked TIME_NOW_MILLIS artifact); the fixture's runtime-ok expectation and std/time.lua are frozen until the delegated time-selector child lands its disposition pair)
```

Pin test: `Passed: 36, Failed: 0`; the direct LuaJIT stdlib suite reports
`Stdlib Results: 154 passed, 0 failed` (ISSUE-0342's four std/json int32
mapping cases run in it) and the stdlib contract tests report
`Passed: 60, Failed: 0, Skipped: 0`; the async-export
driver suite (landed `e220da0`, ISSUE-0416) reports `All 20 async export
driver tests passed`; the LuaJIT async-export invoker suite (ISSUE-0417,
landed `49fca619`/`cb27bb18`) reports `OK (35 tests)`; lock-running
suites green (`LoweringSupportTest`, `MigrationPlannerTest` with
`testTimeConflictNeverShared`, and all remaining suites in the same run).

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
this record and its script; the committed diff contains
`CONTINGENT_DISPOSITION_DELIVERY_RECORD.md` and
`verify_contingent_delivery_gate.sh` (the delivery record and its gate
script), plus a two-line locator-comment correction in the T2 script
`verify_both_branches_scratch.sh` (its stale-promotion citations
re-anchored to the canonical HEAD after the ISSUE-0420 +160-line
insertion: `:461-466` → `:621-626`, `:626-629` → `:786-789`). This
re-anchor commit updates the record's sections 1/2/5/6 against
`3886b534` and the gate script's direct-suite echo site
(`test_stdlib.lua:1070-1076` → `:1111-1117`); the diff surface stays the
same (the two new files plus the T2 script's comment correction).

A later acceptance remediation (ISSUE-0402 remediation, landed after the
canonical HEAD) removed the premature ISSUE-0420 strict gate from
`test/ConformanceTest.java` (−160 lines: the `runGateClosureCheck()` call
and its section) and re-anchored this record's section 2/5/6 gate rows
plus the T2 script's two stale-promotion comment citations to the
post-removal sites — enforcing the ISSUE-0419 merge gate and
`luajit-gate-closure` D6 until the post-unit, post-promotions re-landing.

The ISSUE-0477 cycle-2 remediation then re-landed the strict gate
(+160 lines: the `runGateClosureCheck()` call at `:289` and the
`runGateClosureCheck`/`runStrictModeGate`/`isSanctionedPreUnitPair`
section at `:342-445`) on the post-unit, post-promotions state — the
sanctioned re-landing point (`luajit-gate-closure` D6, epic Sequencing
step 5) — and re-anchored this record's section 2/5/6 gate rows plus the
T2 script's two stale-promotion comment citations to the post-re-landing
sites. The three-mode registry-shape key is active: dormant under the
exact sanctioned pre-unit pair, strict under the empty registry, and a
hard failure with the removal instruction for every other shape; the
re-introduction exercise output and exit code are recorded in
`POST_UNIT_GATE_CLOSURE_VERIFICATION.md` §3.

## 6. Locator register (task citations vs the canonical HEAD)

| Site | Task citation | Canonical HEAD | Note |
|---|---|---|---|
| Staged registration | `test/ConformanceTest.java:150-159` | call `:181-190`, block `:180-191` | task window starts one line above the call at the design checkout; content identical (the entry was later removed by the disposition-application unit) |
| Stale expectation-changed rule | `:440-451` | `:665-670` (branch `:660-673`) | the ISSUE-0402 remediation removed the ISSUE-0420 strict gate (−160 lines); the ISSUE-0477 cycle-2 remediation re-landed it (+160 lines) — the post-re-landing offsets |
| Stale passing rule | `:609-615` | `:845-847` (branch `:843-848`) | same |
| Gate-closure strict gate (ISSUE-0420) | — (removed at the pre-unit anchor) | call `:289`, section `:342-445` | re-landed by the ISSUE-0477 cycle-2 remediation on the post-unit, post-promotions state (`luajit-gate-closure` D6); the three-mode registry-shape key is active |
| Pin-test `testRetainedLuaImplementation` | `:93-107` | `:95-109` | ISSUE-0321 seam-pin edits |
| Pin-test `testRuntimeSeam` | `:123-149` | `:125-150` | same |
| Pin-test `testFixtureHeader` | `:150-179` | `:156-180` | same |
| Pin-test lifecycle note | `:33-43` | `:33-45` | same |
| `deal/runtime.lua` int32 gate | `:78-79` | `:83-84` (range check `:83`, raise `:84`) | cumulative +5: ISSUE-0333 (`ddeed8e`) header-comment split (+1); ISSUE-0336 canonical descriptor cutover (`c0b708d`, MR-0270) reworked the file head (+4); ISSUE-0342 changed only the JSON section below the gate (`_json_is_int` `:1430-1447`, int32 branch `:1443`) — the gate stays `:83-84` |
| `deal/runtime.js` ±(2^53−1) arm | `:427-428` | `:1236` (legacy branch of the profile-gated condition `:1234-1236`) | ISSUE-0321 landed the profile-gated seam; ISSUE-0329 (`bfbd528c`) expanded the file header (+1) and appended the invokeAsyncExport surface at the tail |
| `test_stdlib.lua` nowMillis cases | `:1034-1042` | `:1111-1117` | `bc93e41` re-pinned both cases to `assert_error_code`; ISSUE-0342 (`3c70d788`) inserted the four std/json int32 cases above (+41) |
| `StdlibFunctionId.java` reservation | `:50-52`, `:51` | exact | — |
| `MigrationPlanner.java` rule 2 | `:287-288` | `:287-288` | — |
| `LoweringSupport.java` Arms A–D | — | `:74-122` | — |
| Pin-test launch in `run_tests.sh` | `:436` | echo `:517`, java `:518` | later suites added launch blocks above; the rebase-added async-export driver suite (`e220da0`, ISSUE-0416) inserted seven lines above it; ISSUE-0417 added the LuaJitAsyncExportInvokerTest launch block above it (+5: one compile-list line, four launch lines) |

## 7. Conclusion

The delivery gate condition holds at the canonical HEAD: the resolution
is pending in every authoritative record, the T1 pre-activation state is
intact, no disposition edit exists in the working tree or the history,
the recorded change sets are delivered only inside the disposition-
application unit after the landing, the combined-behavior verification
reproduces the branch-1 matching pass and both branches' pair results by
their specified mechanisms, and `./run_tests.sh` exits 0. The record was
re-anchored at the canonical HEAD `3886b534` after the mandated rebase;
every locator in sections 1, 2, 5, and 6 was re-read against it, the
deltas registered in section 6, and the verification evidence in
section 5 re-captured from fresh runs. This record's own lifecycle is the
pre-unit audit state: when the resolution lands, the unit delivers the
recorded branch change set and this pending-state pin (and the T1/T2
artifacts it consumes) is superseded by the unit's post-activation state.
