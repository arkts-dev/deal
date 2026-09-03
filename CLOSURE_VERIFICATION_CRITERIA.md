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
4 must handle (review round 1; §7): the ConformanceTest window contains
interleaved background-suite lines (the two executed captures at this
HEAD show 135 and 152 `jvm-` lines inside the window), and the time
fixture's own prefix print and result print were spliced apart by concurrent output — the prefix line carries spliced
JVM text and the `STAGED-FAIL (...)` result lands on its own line.

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
4. **Time-fixture PASS under the landed branch, keyed to the
   ConformanceTest lane's executed line shape**: the fixture records
   PASS through the standard dispatch with the
   `LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit)` infix
   ConformanceTest prints for this catalogued fixture
   (ConformanceTest.java:493-495; LegacyProfileRegressionCatalog.java
   :173, self-probe :527-533) — branch 1
   `[backend-runtime/stdlib-edge/time-now-millis-positive.deal]
   LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) OK (found
   DEAL_ERROR_CODE: E8004)`; branch 2 the same prefix `OK` — and no
   `[...] LEGACY-AUTHORITY (...) FAIL` line for the fixture. The
   infix is the lane attribution: the JVM lane prints byte-identical
   `[...] OK` / `[...] OK (found ...)` suffixes without the infix
   (JvmConformanceTest.java:1572,1580) and its background-suite lines
   interleave into the shared log inside the ConformanceTest window
   (tools/gate-manifest.sh:154), so only ConformanceTest's own lines
   match — the exact-count-1 check cannot be inflated by a concurrent
   lane. Because the prefix and the result are two separate writes
   that concurrent output can splice apart (observed in the captured
   run at this HEAD), the checker also attributes the first pathless
   CT-result line after the fixture's prefix line as the fixture's
   displaced result when the unsplit line is absent (the CT is
   single-threaded — no other CT output can appear between its two
   prints; foreign suites never print these pathless result shapes).
   The PASS proves `expectation(fixture) == landed nowMillis
   behavior` (the closure adds no header-comparison assertion and no
   validity code, D3).
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
under /tmp, discarded; the fixture flip test was reverted):

| Input | Result |
|---|---|
| Executed pre-unit `./run_tests.sh` log at this HEAD (428/428; 305/306, staged 1) | exit 1 — unmet criteria named (summary non-zero counters, phase 305/306, follow-up block present, no time-fixture PASS), verdict `closure pending` |
| Synthetic post-unit branch-1 log in the real executed line format (fixture `  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) OK (found DEAL_ERROR_CODE: E8004)`; 429/429; 306/306; four zeros; pin test `Passed: 37, Failed: 0`) with the fixture header temporarily flipped to `runtime-error E8004` (reverted) | exit 0 — all seven criteria PASS |
| JVM-interleaved post-unit branch-1 log: the one infix-keyed CT PASS line plus byte-identical JVM `[...] OK (found DEAL_ERROR_CODE: E8004)` / `[...] OK` lines without the infix inside the CT window | exit 0 — lane attribution: the JVM lines cannot inflate the exact-count-1 check |
| Split-form branch-1 log: the fixture's CT prefix line carries spliced JVM text and the `OK (found DEAL_ERROR_CODE: E8004)` result lands on its own line (the captured real-run interleaving shape) | exit 0 — displaced-result attribution |
| Synthetic post-unit branch-2 log in the real executed line format (fixture `[...] LEGACY-AUTHORITY (...) OK`; 429/429; 306/306; four zeros) against the on-disk `runtime-ok` fixture — unsplit and split forms | exit 0 — all seven criteria PASS |
| Duplicate-line log: two identical infix-keyed fixture PASS lines inside the CT window | exit 1 — `found 2 such line(s)` (the count is keyed to the CT-only line shape) |
| Hypothetical zeros log carrying the superseded pinned numbers (387/387; 269/269; four zeros) | exit 0 — proves the checker compares no pre-pinned number; the binding assertions are the zeros, the fixture PASS, and exit 0 |
| Mismatched pairs: branch-2 infix-keyed bare-OK log against the flipped `runtime-error E8004` fixture; branch-1 E8004-OK log against the on-disk `runtime-ok` fixture | each exits 1 — time-fixture criterion fails (expectation != landed behavior) |
| Real-format infix-keyed fixture FAIL line (and the `ERROR:` exception line) inside the CT window | exit 1 — time-fixture criterion fails (expectation != landed behavior) |
| Split-form pre-unit shape: fixture prefix + displaced `STAGED-FAIL (...)` line inside a four-zeros log | exit 1 — no PASS line for the fixture |
| Negative variants (known-fail counter 1; Total != Passed; `GATE FAILURE` line; pin-test `Failed: 1`; recorded run exit 1; missing fixture line) | each exits 1 naming the unmet criterion |

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

## 7. Review round 1 corrections (criterion 4 — real executed line format, lane attribution, and displaced results)

Three defects in the criterion-4 fixture-line patterns (each would
reject a correct post-unit closure log) were corrected in this round:

1. **Real line format (the LEGACY-AUTHORITY infix).** The patterns
   originally pinned `[backend-runtime/stdlib-edge/time-now-millis-
   positive.deal] OK (found DEAL_ERROR_CODE: E8004)` / `...] OK` /
   `...] FAIL`, requiring `] OK`/`] FAIL` adjacency. ConformanceTest
   prints the prefix `  [<path>] LEGACY-AUTHORITY (legacy-regression;
   zero v1.2 credit) ` before the result for catalogued fixtures
   (ConformanceTest.java:493-495), and this fixture is catalogued
   (LegacyProfileRegressionCatalog.java:173; the self-probe at
   :527-533 requires the row to stay). A real executed line therefore
   carries the infix between `]` and the result, and the old patterns
   could never match a real log — they rejected every correct
   post-unit branch-1 closure run (and were inert for FAIL). The
   patterns now require the infix: `[...] LEGACY-AUTHORITY (...) OK
   (found DEAL_ERROR_CODE: E8004)`, the end-anchored bare
   `[...] LEGACY-AUTHORITY (...) OK`, and `[...] LEGACY-AUTHORITY
   (...) FAIL`; the pinned line texts in §3 item 4 above match.
2. **Lane attribution (the exact-count-1 check).** The JVM lane
   prints byte-identical `[...] OK` / `[...] OK (found ...)` suffixes
   for the same corpus path without the infix
   (JvmConformanceTest.java:1572,1580) and runs in background into the
   same shared log (tools/gate-manifest.sh:154), so its lines
   interleave inside the ConformanceTest window — the two executed
   captures at this HEAD show 135 and 152 `jvm-` lines inside the
   window. A count over
   the window alone could hit 2 from the concurrent lane and reject a
   correct closure run nondeterministically. Requiring the infix makes
   the pattern ConformanceTest-only: the lane prints the fixture
   prefix exactly once per run, so the exact-count-1 assertion is
   both satisfiable by the real log and immune to cross-suite
   interleaving. A synthetic JVM-interleaved post-unit log (one
   infix-keyed CT PASS line plus one infix-less JVM PASS line inside
   the window) passes; a duplicate infix-keyed line fails with `found
   2 such line(s)` (§5 matrix).
3. **Displaced results (the split prefix/result writes).**
   ConformanceTest prints the fixture prefix and the result as two
   separate writes, and concurrent background-suite output can splice
   between them: the captured run at this HEAD shows the time
   fixture's prefix line carrying spliced JVM text with the
   `STAGED-FAIL (...)` result on its own line. When the unsplit line
   is absent, the checker attributes the first pathless CT-result
   line after the fixture's prefix line as the fixture's displaced
   result — sound because the CT is single-threaded (no other CT
   output can appear between its two prints) and foreign suites never
   print these pathless result shapes (the JVM lane always prefixes
   its lines with the path; the `OK (61 tests)`-style JUnit verdict
   does not match the `OK (found DEAL_ERROR_CODE: ...)` shape). The
   displaced-result attribution covers `OK (found DEAL_ERROR_CODE:
   E8004)`, bare `OK`, `FAIL (...)`, and `ERROR:`; a displaced
   `STAGED-FAIL (...)` or `SKIP (...)` line is neither and leaves the
   criterion unmet (§5 matrix: split-form branch-1 and branch-2 logs
   pass; the split pre-unit staged shape fails).
