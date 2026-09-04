# ISSUE-0479 — Closure-Verification Criteria: Execution-Time Totals Re-Pin

Acceptance remediation record for ISSUE-0402 (CHECK-000028 finding 3,
minor, category `verification`). This record supersedes the numeric
totals pinned in the delivered closure-verification criteria of
ISSUE-0421 (materialized from BC-000083 T2) — `Total: 387, Passed: 387
...` in the summary and the phase line `269/269 passed` — and re-pins
the criteria to the post-unit corpus **at execution time**, keeping the
four zeros, the time-fixture PASS, and `./run_tests.sh` exit 0 as the
binding assertions. The companion `verify_closure_criteria.sh` is the
executable form of the re-pinned criteria: it reads the totals from the
executed run's own output and records them as evidence, never comparing
them against any pre-pinned number.

## 1. The defect

ISSUE-0421's verification pinned the post-unit output text:
`the summary reads Total: 387, Passed: 387, Failed: 0, Skipped: 0,
KnownFailures (tracked): 0, StagedFailures (tracked): 0` and the phase
line `LuaJIT backend-runtime conformance (v1.2): 269/269 passed, 0
failed, 0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)`.
Those numbers derive from the design-time corpus (381 passing + 5
promoted known-fails + 1 staged fixture), but the task's own executed
run at its revision recorded the pre-unit `Total: 390, Passed: 390,
KnownFailures (tracked): 4, StagedFailures (tracked): 1` and phase
`275/277 passed, ... 1 known-fail (tracked), 1 staged-fail (tracked)`,
and the canonical corpus has evolved further (the five promotions
landed and additional backend-runtime fixtures exist). Post-unit totals
cannot be lower than the pre-unit passing count plus the one promoted
staged fixture, so the pinned 387/269-269 can never be observed in any
post-unit state of the corpus the task executed against — a correct
post-unit closure run would fail the criterion on the totals alone.

## 2. Executed evidence at this HEAD

`./run_tests.sh` executed at this HEAD (pre-unit pending state; exit 0):

- ConformanceTest summary:
  `Total: 428, Passed: 428, Failed: 0, Skipped: 0, KnownFailures
  (tracked): 1, StagedFailures (tracked): 1`
- Phase line: `LuaJIT backend-runtime conformance (v1.2): 305/306
  passed, 0 failed, 0 skipped, 0 known-fail (tracked), 1 staged-fail
  (tracked)`

The pre-unit passing counts (428 summary / 305 backend-runtime) already
exceed the pinned post-unit numbers (387 / 269), so the pinned totals
are unobservable at any later state of this corpus. The observed
post-unit floors at this HEAD — backend-runtime at least 306/306 and
the summary at least 429 — are recorded here as execution-time facts
only; they are not criteria targets, and a future corpus change moves
them (see the totals rule below).

The same executed capture also proves the interleaving facts criterion
4 must handle (§7, §8): the ConformanceTest window contains interleaved
background-suite lines (the captured run shows more than a hundred
`jvm-` lines inside the window), and the time fixture's own prefix
print and result print were spliced apart by concurrent output — the
prefix line carries spliced JVM text and the `STAGED-FAIL (...)` result
lands on its own line. The capture additionally proves the lane
attribution fact used by §8: the JVM lane prints the same fixture path
with the same `LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)`
infix as its own whole line (JvmConformanceTest.java:1357-1364), while
the ConformanceTest prefix write is the only print that follows the
infix with a space (ConformanceTest.java:493-497) — byte-verified on
the executed capture (the CT line carries `credit) ` and continues;
the JVM lane's line ends at `credit)`).

## 3. Re-pinned criteria (binding surface — unchanged, not weakened)

The post-unit closure asserts, and the closure record must show, each of
the following (design refs: `luajit-gate-closure` D1/D3/D4/D5, the
Gate-closure assertion contract; `luajit-time-selector-disposition`
delegated-row boundary; epic objective ISSUE-0402):

1. **Summary four zeros**: the executed run's summary line reads
   `Total: N, Passed: N, Failed: 0, Skipped: 0, KnownFailures
   (tracked): 0, StagedFailures (tracked): 0` — zero failed, zero
   skipped, zero known-fail, zero staged, with Total == Passed.
2. **Phase four zeros**: the phase line reads
   `LuaJIT backend-runtime conformance (v1.2): M/M passed, 0 failed,
   0 skipped, 0 known-fail (tracked), 0 staged-fail (tracked)`.
3. **Follow-up line**: `Tracked v1.2 follow-up issues: none — full
   v1.2 conformance`; no tracked known-fail block and no tracked
   staged-failures block prints.
4. **Time-fixture PASS under the landed branch, proven by prefix
   presence plus the absence of any failure result for the fixture —
   no dependence on where concurrent output spliced the result text**:
   inside the `=== DEAL v1.2 Conformance Test Suite ===` …
   `=== Conformance Summary ===` window, (a) the ConformanceTest
   lane's prefix write `  [backend-runtime/stdlib-edge/time-now-
   millis-positive.deal] LEGACY-AUTHORITY (legacy-regression; zero
   v1.2 credit) ` — the write **with its trailing space** that
   ConformanceTest.java:493-497 prints before the result — appears
   exactly once; (b) no infix-keyed
   `FAIL (`/`ERROR:`/`STAGED-FAIL (`/`SKIP (`/`KNOWN-FAIL (` line for
   the fixture prints, and when the prefix line carries no result
   (the prefix and the result are two separate writes that concurrent
   background-suite output can splice apart), the line immediately
   following it is not a failure-result line; (c) the fixture's
   on-disk `@expected` is exactly one of the two dispositions —
   `runtime-error E8004` (branch 1) or `runtime-ok` (branch 2) — and
   when the result text is visible on the prefix line it must match
   the on-disk expectation (branch 1
   `OK (found DEAL_ERROR_CODE: E8004)`, branch 2 bare `OK`). The
   trailing space is the lane attribution: only ConformanceTest's
   prefix write follows this path+infix with a space;
   JvmConformanceTest.java:1357-1364 prints the same path+infix as
   its own whole line without the trailing space, and
   BackendConformanceTest.java:1069 prints infix lines only for JSON
   case names — so the JVM lane's identical infix line can never
   inflate the exact-once count wherever its concurrent line lands.
   Combined with the four zeros (criteria 1–2: a FAIL increments
   `failed`, a SKIP increments `skipped`, a staged result prints and
   increments `stagedFailures`, a known-fail increments the known-fail
   counter) and the no-`GATE FAILURE` criterion, prefix presence plus
   the absence of failure results proves the fixture recorded PASS
   through the standard dispatch —
   `expectation(fixture) == landed nowMillis behavior` holds (D3; the
   closure adds no header-comparison assertion and no validity code).
5. **No `GATE FAILURE` line** anywhere in the run (strict mode under
   the empty registry).
6. **Pin test on the post-activation header**: the
   `=== std/time.nowMillis Pre-Activation Pin (ISSUE-0369) ===` section
   reports `Passed: K, Failed: 0`.
7. **`./run_tests.sh` exit 0** (`=== All Tests Passed ===`).

## 4. The totals rule (the correction)

1. **The numeric pins are dropped.** No pre-pinned `Total`/`Passed`
   value (387, or any other number) and no pre-pinned phase
   denominator (269/269, or any other fraction) is part of the
   criterion. A criterion text that pins a specific N is invalid: the
   corpus size is not fixed across landings, and the pinned
   design-time derivation was already unreachable at the task's own
   execution revision.
2. **Execution-time recording.** At closure, the run's own summary and
   phase totals are recorded as evidence (`Total: N, Passed: N` and
   `M/M passed`) exactly as the executed post-unit corpus reports
   them. The binding assertions are the four zeros on both surfaces,
   the time-fixture PASS, and exit 0 — the N and M values are the
   executed corpus' counts at execution time, never targets.
3. **Observed floors are not targets.** The post-unit totals recorded
   in section 2 are observation floors for the corpus at this HEAD;
   a corpus change moves N and M and the criteria follow the executed
   run unchanged.

## 5. Verification of the correction

`verify_closure_criteria.sh` implements the re-pinned criteria; it was
exercised at this HEAD with the following matrix (all synthetic logs
under /tmp, discarded; every temporary fixture-header flip reverted):

| Input | Result |
|---|---|
| Executed pre-unit `./run_tests.sh` logs at this HEAD (428/428; 305/306, staged 1) — two captures, one with the displaced `STAGED-FAIL (...)` on the line after the fixture's prefix line, one with it eight lines after | exit 1 — criteria named: summary/phase non-zero counters, both follow-up blocks, and the time-fixture criterion (the window-wide `STAGED-FAIL` scan; the one-line capture additionally via the next-line check); verdict `closure pending`; no criterion-4 PASS is reported on either capture |
| Post-unit branch-1 unsplit log in the real executed line format (fixture line `  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) OK (found DEAL_ERROR_CODE: E8004)`; 429/429; 306/306; four zeros; pin `Passed: 37, Failed: 0`; exit marker) with the fixture header temporarily flipped to `runtime-error E8004` (reverted) | exit 0 — all seven criteria PASS (direct: the prefix line records the branch-1 result) |
| Post-unit branch-1 spliced log — the review's false-rejection shape: the fixture's prefix write alone on its line, one interleaved foreign displaced result line `OK (found DEAL_ERROR_CODE: E8001)`, then the fixture's own displaced `OK (found DEAL_ERROR_CODE: E8004)`; four zeros; flipped fixture | exit 0 — the indirect proof (prefix presence + no failure result + four zeros) accepts the correct closure |
| Post-unit branch-2 unsplit log (fixture line `... credit) OK`; 429/429; 306/306; four zeros) against the on-disk `runtime-ok` fixture — unsplit and spliced (foreign displaced line + own bare `OK`) forms | exit 0 — all seven criteria PASS |
| JVM-interleaved post-unit branch-1 log: the one CT-lane PASS line plus the JVM lane's infix-less `[...] OK (found DEAL_ERROR_CODE: E8004)` line, and the JVM lane's own identical infix line without the trailing space, inside the CT window | exit 0 — the trailing-space signature keys the exact-once count to the ConformanceTest write only; the JVM lane's identical infix line cannot inflate it |
| Duplicate log: two identical CT-lane fixture PASS lines inside the CT window | exit 1 — `found 2 such line(s)` for the prefix write |
| Failure-result shapes on the fixture's prefix line: `FAIL (...)`, `STAGED-FAIL (...)`, `ERROR: ...`, `SKIP (...)`, `KNOWN-FAIL (...)` | each exits 1 naming the failure-result line |
| Split pre-unit shape inside a four-zeros log: fixture prefix alone + displaced `STAGED-FAIL (...)` on the immediately following line | exit 1 — displaced failure result named (the pre-unit closure shape is rejected) |
| Split pre-unit shape inside a four-zeros log: fixture prefix alone + foreign lines + displaced `STAGED-FAIL (...)` three lines after the prefix | exit 1 — the window-wide `STAGED-FAIL` scan names the failure (the next-line check cannot see it; the scan is sound because no other fixture prints STAGED-FAIL) |
| Four-zeros log without the four-zero premise: prefix present, no failure line, but summary/phase counters non-zero | exit 1 — criterion 4 cannot be asserted without the four-zero premise, naming it (the proof-premise gate) |
| Mismatched visible pairs: bare `OK` against the flipped `runtime-error E8004` fixture; `OK (found DEAL_ERROR_CODE: E8004)` against the on-disk `runtime-ok` fixture; `OK (found DEAL_ERROR_CODE: E8001)` against the flipped fixture | each exits 1 — mismatched pair named (expectation != landed behavior) |
| Invalid on-disk expectation: fixture temporarily set to `@expected: compile-ok` (reverted) with an otherwise four-zeros log | exit 1 — expectation is neither disposition pair |
| Missing prefix line: four-zeros log without any fixture line | exit 1 — `found 0 such line(s)` |
| Hypothetical zeros log carrying the superseded pinned numbers (387/387; 269/269; four zeros) | exit 0 — proves the checker compares no pre-pinned number; the binding assertions are the zeros, the fixture PASS, and exit 0 |
| Negative variants (known-fail counter 1; Total != Passed; `GATE FAILURE` line; pin-test `Failed: 1`; recorded run exit 1) | each exits 1 naming the unmet criterion |

The script is not wired into `run_tests.sh` and asserts nothing
pre-unit: on the pre-unit pending state it exits 1 by design (the
closure is pending and never asserted pre-unit — the Pending-state
contract), while on a post-unit closure log it exits 0.

## 6. Non-weakening and exclusions

The zero-staged requirement and the behavioral-equality validity
condition (`expectation(fixture) == landed nowMillis behavior`) remain
asserted exactly as pinned by `luajit-gate-closure` D1–D5 — only the
numeric totals were dropped. No branch, product-semantics, fixture, or
`std/time.lua` decision is made here; the fixture, `std/time.lua`, the
staged registry, the pin test, and the runner are untouched by this
remediation. The disposition-application unit (ISSUE-0372) and the
five known-fail promotions remain the sequenced preconditions of the
post-unit closure (epic Sequencing steps 1–4), and the strict gate
lands only with or after the unit and the promotions (step 5).

## 7. Review round 1 corrections (criterion 4 — real executed line format and lane attribution)

Three defects in the criterion-4 fixture-line patterns (each would
reject a correct post-unit closure log) were corrected in round 1:

1. **Real line format (the LEGACY-AUTHORITY infix).** The patterns
   originally pinned `[backend-runtime/stdlib-edge/time-now-millis-
   positive.deal] OK (found DEAL_ERROR_CODE: E8004)` / `...] OK` /
   `...] FAIL`, requiring `] OK`/`] FAIL` adjacency. ConformanceTest
   prints the prefix `  [<path>] LEGACY-AUTHORITY (legacy-regression;
   zero v1.2 credit) ` before the result for catalogued fixtures
   (ConformanceTest.java:493-497), and this fixture is catalogued
   (LegacyProfileRegressionCatalog.java:173; the self-probe at
   :527-533 requires the row to stay). A real executed line therefore
   carries the infix between `]` and the result, and the old patterns
   could never match a real log — they rejected every correct
   post-unit branch-1 closure run (and were inert for FAIL). The
   patterns now require the infix.
2. **Lane attribution by write shape.** The JVM lane prints
   byte-identical `[...] OK` / `[...] OK (found ...)` suffixes for the
   same corpus path without the infix (JvmConformanceTest.java:1572,
   1580) and runs in background into the same shared log
   (tools/gate-manifest.sh), so its lines interleave inside the
   ConformanceTest window. Round 2 refined the attribution further:
   JvmConformanceTest.java:1357-1364 also prints the fixture path with
   the full `LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)`
   infix as its own whole line (byte-verified on the executed
   capture), so the infix alone is not ConformanceTest-only. The
   exact-once count is therefore keyed to the ConformanceTest prefix
   **write**: the infix followed by the trailing space that only
   ConformanceTest.java:493-497 prints (§3 item 4, §8 item 2).
3. **Displaced results (the split prefix/result writes) — superseded
   by round 2.** Round 1 attributed the first pathless CT-result line
   after the fixture's prefix line to the fixture when the unsplit
   line was absent. Round 2 review proved that attribution unsound in
   both directions and it is removed entirely; the replacement proof
   is §8.

## 8. Review round 2 corrections (criterion 4 — the unsound displaced-result attribution removed)

Round 2 review found the first-pathless-result attribution of round 1
unsound in both directions, vacating the binding behavioral-equality
assertion and re-introducing the remediated defect class:

1. **False positive on the real pre-unit log.** The fixture's own line
   records `STAGED-FAIL` (no PASS exists), yet the attribution marked
   the fixture's line and then accepted the first pathless
   result-shaped line anywhere after it — another fixture's spliced
   `OK` in the captured run — as the time fixture's PASS. The binding
   assertion was satisfiable without the fixture passing.
2. **False rejection of a correct post-unit closure run.** A post-unit
   branch-1 log in the real spliced format (prefix write alone on its
   line; one interleaved foreign displaced result line; then the
   fixture's own displaced `OK (found DEAL_ERROR_CODE: E8004)`) exited
   1 with `found 0 such line(s)`: the attribution stopped at the first
   pathless result line regardless of which fixture emitted it, so a
   correct closure was rejected whenever concurrent output landed
   between ConformanceTest's two separate prefix/result writes.

The corrections, implemented in `verify_closure_criteria.sh` and
recorded in §3 item 4:

1. **The first-pathless-result attribution is dropped entirely.** No
   pathless result line anywhere is attributed to the fixture.
2. **Prefix presence, exactly once, keyed to the ConformanceTest
   prefix write.** The assertion requires the prefix line
   `  [backend-runtime/stdlib-edge/time-now-millis-positive.deal]
   LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) ` — infix
   followed by the trailing space ConformanceTest.java:493-497 prints
   before the result — exactly once inside the CT window. Only that
   write produces the path+infix+space shape: JvmConformanceTest.java
   :1357-1364 prints the same path+infix as its own whole line with
   no trailing space (verified on the executed capture), and
   BackendConformanceTest.java:1069 prints infix lines only for JSON
   case names. The JVM lane's identical infix line therefore cannot
   inflate the count whether it lands inside or outside the window;
   a duplicate ConformanceTest prefix line still fails with `found 2
   such line(s)`.
3. **Absence of failure results for the fixture.** No infix-keyed
   `FAIL (`/`ERROR:`/`STAGED-FAIL (`/`SKIP (`/`KNOWN-FAIL (` line for
   the fixture prints inside the window, and when the prefix line
   carries no result — the prefix and result are two separate writes
   that concurrent output can splice apart — the line immediately
   following the prefix line must not be a failure-result line. The
   one-line check covers the single-splice shape; the executed
   captures at this HEAD also show the displaced result landing eight
   lines after the prefix (several background-suite lines print
   between the CT's two writes), which two sound mechanisms cover:
   a window-wide scan for pathless `STAGED-FAIL (` lines — the time
   fixture is the CT's only staged fixture in every sanctioned
   registry state and the registry is empty post-unit, so no other
   fixture can print that line at any displacement distance — and the
   four-zero premise gate (item 5): a displaced `FAIL (`/`SKIP (`/
   `ERROR:`/`KNOWN-FAIL (` line for ANY fixture implies its counter is
   non-zero, so the PASS cannot be asserted while criteria 1–2 are
   unmet. Results displaced for other fixtures beyond the next line
   are never attributed to the time fixture.
4. **On-disk @expected cross-check retained and tightened.** The
   fixture's on-disk expectation must be exactly one of the two
   dispositions (`runtime-error E8004` or `runtime-ok`); anything else
   fails the criterion. When the result text is visible on the prefix
   line it must match the on-disk expectation (branch 1
   `OK (found DEAL_ERROR_CODE: E8004)`, branch 2 bare `OK`) — the
   mismatched-pair detection is preserved.
5. **The completed proof, with the four zeros as an explicit
   premise.** The PASS is asserted only when the summary and phase
   four zeros hold (every non-PASS outcome increments a counter those
   criteria pin to zero: FAIL → `failed`, SKIP → `skipped`, staged →
   `stagedFailures`, known-fail → the known-fail counter), together
   with the no-`GATE FAILURE` criterion. Under that premise, prefix
   presence plus the absence of failure results proves the fixture
   recorded PASS through the standard dispatch —
   `expectation(fixture) == landed nowMillis behavior` — without
   depending on where concurrent output spliced the result text, and
   the criterion cannot report PASS when the premise is unmet.

The round-2 matrix rows in §5 exercise both review scenarios plus the
duplicate, failure-shape, mismatched-pair, invalid-expectation,
missing-prefix, JVM-infix-interleaved, far-displaced-staged, and
missing-premise variants; both real executed pre-unit captures now fail
the criterion by naming the displaced `STAGED-FAIL (...)` line (at one
line and at eight lines of displacement), and both correct spliced
closure shapes exit 0.
