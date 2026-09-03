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
4. **Time-fixture PASS under the landed branch**: the fixture records
   PASS through the standard dispatch — branch 1
   `[backend-runtime/stdlib-edge/time-now-millis-positive.deal] OK
   (found DEAL_ERROR_CODE: E8004)`; branch 2 the same path `OK` — and
   no FAIL line for the fixture. The PASS proves
   `expectation(fixture) == landed nowMillis behavior` (the closure
   adds no header-comparison assertion and no validity code, D3).
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
exercised at this HEAD with the following matrix (all scratch logs
under /tmp, discarded; the fixture flip test was reverted):

| Input | Result |
|---|---|
| Executed pre-unit `./run_tests.sh` log at this HEAD (428/428; 305/306, staged 1) | exit 1 — six unmet criteria named (summary non-zero counters, phase 305/306, follow-up block present, no time-fixture PASS), verdict `closure pending` |
| Synthetic post-unit branch-1 log (fixture `OK (found DEAL_ERROR_CODE: E8004)`; 429/429; 306/306; four zeros; pin test `Passed: 37, Failed: 0`) with the fixture header temporarily flipped to `runtime-error E8004` (reverted) | exit 0 — all seven criteria PASS |
| Synthetic post-unit branch-2 log (fixture `OK`; 429/429; 306/306; four zeros) against the on-disk `runtime-ok` fixture | exit 0 — all seven criteria PASS |
| Hypothetical zeros log carrying the superseded pinned numbers (387/387; 269/269; four zeros) | exit 0 — proves the checker compares no pre-pinned number; the binding assertions are the zeros, the fixture PASS, and exit 0 |
| Mismatched pair: branch-2 bare-OK log against the flipped `runtime-error E8004` fixture | exit 1 — time-fixture criterion fails (expectation != landed behavior) |
| Negative variants (known-fail counter 1; Total != Passed; fixture FAIL line; `GATE FAILURE` line; pin-test `Failed: 1`; recorded run exit 1) | each exits 1 naming the unmet criterion |

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
