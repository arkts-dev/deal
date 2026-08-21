# Runtime Conformance Assertion Coverage Gate (ISSUE-0116)

Final evidence for epic ISSUE-0113: all 63 formerly weak `runtime-ok`
fixtures of the DEAL v1.2 conformance suite now own deterministic,
behavior-specific, fixture-local Runtime Conformance Assertions; the
unchanged complete project path passes with zero executable-test skips.

Design authority: `runtime-conformance-assertion-contract` (D1–D7,
Behaviors 1–4, Static coverage gate, Oracle-strength gate, Critical cases,
Release gates) refined by `synchronous-runtime-ok-oracle-migration`
(S1–S19, ISSUE-0114) and `async-runtime-ok-oracle-migration` (A1–A12,
ISSUE-0115).

Baseline (commit `bce7232`): 190 discovered `runtime-ok` fixtures, of
which 63 lacked `code: "TEST_FAIL"` — 45 synchronous, 18 async-await.
The former synchronous `functions/rest-params.deal` target was
superseded by normative `compile-error E1047` (ISSUE-0137), leaving
62 executable `runtime-ok` targets (44 synchronous + 18 async) and
1 `compile-error E1047` target = the 63-path ledger below.

## Gate results

| Gate | Result |
|---|---|
| Static weak-oracle query over `test/conformance/backend-runtime/**/*.deal` | **empty output** (no `@expected: runtime-ok` file lacks `code: "TEST_FAIL"`) |
| 63-path ledger completeness | 63 baseline weak targets → 63 completed fixtures, bijection, no extras |
| Oracle-strength negative controls | 70/70 reverted controls fail with the named fixture + `FAIL (Lua execution failed)` + exit 1; 2 A11/A12 wrong-catch controls prove escaping code is exactly `TEST_FAIL` |
| Final composition run `./run_tests.sh` | exit 0, final marker `=== All Tests Passed ===`; every skip-exposing LuaJIT-side runner reports `Skipped: 0`; LuaJIT available; no `luajit not found` warning |
| Repository diff review | epic changed exactly the 63 target files; zero production, harness, companion, or non-target fixture changes |

## Static coverage gate

Exact approved query (run at release; output captured to
`/tmp/wd0304_baseline.log` companion run and re-run at the end):

```sh
grep -RIl '^// @expected: runtime-ok' test/conformance/backend-runtime --include='*.deal' |
while read -r f; do
  grep -q 'code: "TEST_FAIL"' "$f" || echo "$f"
done
```

Result: **no paths printed** (exit 0). The 191 discovered
`@expected: runtime-ok` files all contain the canonical assertion; the
former weak set contributes 110 `TEST_FAIL` throw sites across the 62
executable targets (min 1 per fixture). `functions/rest-params.deal`
carries `@expected: compile-error E1047` and therefore never enters the
query; the parse-time E1047 rejection is itself the deterministic oracle
and requires no runtime `TEST_FAIL`.

## 63-path review ledger

Conforming results are the per-fixture lines of the final LuaJIT
`ConformanceTest` phase of `./run_tests.sh` (`[backend-runtime/<path>] OK`;
rest-params: `OK (found E1047)`). Each oracle-negative control was a
single temporary perturbation applied to a scratch copy, executed through
the real `java -ea -cp build deal.test.ConformanceTest <scratch-root>`
compiler → generated Lua → runtime/stdlib → LuaJIT route, then discarded;
observed verdict `FAIL` with the named fixture and non-zero exit (controls
were reverted, nothing committed). Control logs: `/tmp/nc_logs/<control>.log`.

| # | Target (under `test/conformance/backend-runtime/`) | Completed fixture (issue) | `@spec` / `@description` | Export(s) → successful return | Observable asserted | Conforming | Oracle-negative control |
|---|---|---|---|---|---|---|---|
| 1 | arrays/index-read.deal | S1 (ISSUE-0118) | Arrays — Indexing / "Array index read works" | `test_array_index(): int → 20` | fixed `[10,20,30]`; length 3; `xs[1] === 20` | OK | s01-index-read: `value !== 20` → `21` → FAIL, exit 1 |
| 2 | arrays/index-write.deal | S1 (ISSUE-0118) | Arrays — Array writes / "Array index write works" | `test_array_write(): int → 99` | one `xs[0] = 99`; length 3; post-state `xs[0] === 99` (pre-state 1) | OK | s01-index-write: `result !== 99` → `98` → FAIL, exit 1 |
| 3 | classes/construction.deal | S2 (ISSUE-0119) | Classes — Construction / "Class construction with object literal works" | `test_construct(): string → "Ada"` | one `User` construction; `name="Ada"`, `home.city="London"`, `home.zip=12345` | OK | s02-construction: `"Ada"` → `"Ade"` → FAIL, exit 1 |
| 4 | classes/has-required-field.deal | S2 (ISSUE-0119) | Classes — Field access / "has() checks optional field presence" | `test_has(): boolean → true` | `has(u.nick)` false before one assignment, true after | OK | s02-has-required-field: `missing !== false` → `!== true` → FAIL, exit 1 |
| 5 | control-flow/break-continue.deal | S3 (ISSUE-0120) | Control flow — Break / Continue / "Break exits the loop" | `test_break(): int → 4` | loop 0..9 breaks at `i===5`; terminal `found === 4` | OK | s03-break-continue: `found !== 4` → `5` → FAIL, exit 1 |
| 6 | control-flow/continue.deal | S3 (ISSUE-0120) | Control flow — Break / Continue / "Continue skips to next iteration" | `test_continue(): int → 8` | sum of 0,1,3,4 (i===2 skipped) === 8 | OK | s03-continue: `sum !== 8` → `9` → FAIL, exit 1 |
| 7 | control-flow/if-else.deal | S3 (ISSUE-0120) | Control flow — Conditional / "If/else works" | `test_ifelse(): int → 1` | `x===0` selects then-branch; post-state `x === 1` | OK | s03-if-else: `x !== 1` → `2` → FAIL, exit 1 |
| 8 | control-flow/for-c-style.deal | S3 (ISSUE-0120) | Control flow — For (C-style) / "C-style for loop works" | `test_for(): int → 10` | sum of i 0..4 === 10 | OK | s03-for-c-style: `x !== 10` → `11` → FAIL, exit 1 |
| 9 | control-flow/while.deal | S3 (ISSUE-0120) | Control flow — While / "While loop works" | `test_while(): int → 10` | terminal `x === 10` | OK | s03-while: `x !== 10` → `11` → FAIL, exit 1 |
| 10 | control-flow/for-of-array.deal | S4 (ISSUE-0121) | Control flow — For-of — Array iteration / "For-of over int[] sums the array elements" | `test_sum(): int → 15` | `[1,2,3,4,5]` sum === 15 | OK | s04-for-of-array: `total !== 15` → `16` → FAIL, exit 1 |
| 11 | control-flow/break-in-try.deal | S6 (ISSUE-0123) | Error handling — try / catch / "break inside try within for-loop exits loop" | `test_break_in_try(): int → 4` | terminal `found===4` AND catch never ran (flag outside loop) | OK | s06-break-in-try: `found !== 4` → `5` → FAIL, exit 1 |
| 12 | control-flow/continue-in-try.deal | S6 (ISSUE-0123) | Error handling — try / catch / "continue inside try within for-loop jumps to update step" | `test_continue_in_try(): int → 8` | `sum === 8` (i===2 skipped) AND catch never ran | OK | s06-continue-in-try: `sum !== 8` → `9` → FAIL, exit 1 |
| 13 | control-flow/nested-break-in-try.deal | S6 (ISSUE-0123) | Error handling — try / catch / "break inside nested try within loop propagates" | `test_nested_break(): int → 4` | `found===4` AND inner AND outer catch both never ran (distinct flags) | OK | s06-nested-break-in-try: `found !== 4` → `5` → FAIL, exit 1 |
| 14 | control-flow/nested-continue-in-try.deal | S6 (ISSUE-0123) | Error handling — try / catch / "continue inside nested try within loop propagates" | `test_nested_continue(): int → 8` | `sum === 8` AND inner AND outer catch both never ran | OK | s06-nested-continue-in-try: `sum !== 8` → `9` → FAIL, exit 1 |
| 15 | control-flow/return-inside-try.deal | S7 (ISSUE-0124) | Error handling — return through try/catch / "Return inside try exits the function without executing catch" | `test_return_inside_try(): int → 7` | private helper invoked once: result 7, catch flag false (forbidden fallback 99) | OK | s07-return-inside-try: `got !== 7` → `8` → FAIL, exit 1 |
| 16 | control-flow/return-in-try.deal | S7 (ISSUE-0124) | Error handling — try / catch / "return inside try within for-loop exits function correctly" | `test_return_in_try_loop(): int → 5` | helper returns loop index 5 through try; catch flag false | OK | s07-return-in-try: `got !== 5` → `6` → FAIL, exit 1 |
| 17 | control-flow/for-of-break-continue.deal | S4 (ISSUE-0121) | Control flow — For-of — Break and continue / "break exits for-of early, continue skips an element" | `test_break(): int → 3`; `test_continue(): int → 12` | break path sum 1+2===3; continue path sum 1+2+4+5===12 (one oracle per export) | OK | s04-…-A: `total !== 3` → `4` → FAIL, exit 1; s04-…-B: `total !== 12` → `13` → FAIL, exit 1 |
| 18 | control-flow/for-of-closure.deal | S5 (ISSUE-0122) | Control flow — For-of — Closure capture / "Each for-of iteration creates a fresh binding; closures capture distinct values" | `test_closure_capture(): int → 123` | closure array length 3; invoked once each; ordered values 1,2,3 | OK | s05-for-of-closure: `r0 !== 1` → `2` → FAIL, exit 1 |
| 19 | control-flow/for-of-count.deal | S4 (ISSUE-0121) | Control flow — For-of — Iteration count / "for-of iterates exactly the number of elements in the array/string" | `test_array_count(): int → 5`; `test_string_count(): int → 5` | array `[1..5]` → 5; ASCII string `"hello"` → 5 byte iterations | OK | s04-for-of-count-A (first check) and s04-for-of-count-B (second check): `n !== 5` → `6` → FAIL, exit 1 |
| 20 | control-flow/for-of-string.deal | S4 (ISSUE-0121) | Control flow — For-of — String iteration / "For-of over string counts characters" | `test_count(): int → 5` | ASCII `"hello"` byte count === 5 (active v1.1 byte model; no Unicode-scalar expectation) | OK | s04-for-of-string: `n !== 5` → `6` → FAIL, exit 1 |
| 21 | control-flow-errors/nested-try-runtime-error-caught.deal | S8 (ISSUE-0125) | Error handling — Runtime errors through nested try / "Runtime error propagates to the nearest matching catch through nested try blocks" | `test_nested_try_runtime_error_caught(): int → 7` | `1/0` once: inner catch ran, exact `E8005`, result 7, outer catch forbidden (post-try assertion, not inside a swallowing catch) | OK | s08-nested-try-runtime-error-caught: `"E8005"` → `"E8006"` → FAIL, exit 1 |
| 22 | error-handling/throw-error.deal | S9 (ISSUE-0126) | Error handling — throw / "throw with Error type works" | `test_throw(): string → "E_TEST"` | tested throw once; catch ran; exact code `E_TEST`; no-error path forbidden (flags + post-catch checks) | OK | s09-throw-error: `"E_TEST"` → `"E_OTHER"` → FAIL, exit 1 |
| 23 | error-handling/try-catch.deal | S9 (ISSUE-0126) | Error handling — try / catch / "try/catch works without error" | `test_trycatch(): string → "tried"` | no error; catch forbidden; post-state exactly `"tried"` | OK | s09-try-catch: `catchRan !== false` → `!== true` → FAIL, exit 1 |
| 24 | functions/rest-params.deal | S10 (ISSUE-0127) → E1047 re-proof (ISSUE-0137) | Functions — Rest parameters removed in DEAL v1.2 / "Rest parameters were removed from the language; a rest parameter declaration is rejected at parse time with E1047." | `sum(...values: int[])` (rejected; never runs) | normative compile-time oracle: parse rejects `...` with exact `E1047`; no runtime oracle exists | OK (found E1047) | s10-rest-params-A: `...values` → `values` (valid program) → `FAIL (expected E1047, got: [])`, exit 1; s10-rest-params-B: `E1047` → `E1048` → `FAIL (expected E1048, got: [E1047])`, exit 1 |
| 25 | functions/direct-recursion.deal | S11 (ISSUE-0128) | Functions — Hoisting and recursive bindings / "A function can recursively call itself through its local DEAL binding" | `test_direct_recursion(): int → 0` | `down(8)` terminal value 0 after 9 calls incl. base (not process survival) | OK | s11-direct-recursion: `downCalls !== 9` → `10` → FAIL, exit 1 |
| 26 | functions/nested-scope-recursion.deal | S11 (ISSUE-0128) | Functions — Lexical scopes and recursive bindings / "A nested block function can recursively call itself without resolving as a Lua global" | `test_nested_scope_recursion(): int → 0` | `localDown(6)` terminal value 0 after 7 calls | OK | s11-nested-scope-recursion: `calls !== 7` → `8` → FAIL, exit 1 |
| 27 | functions/closure-capture.deal | S12 (ISSUE-0129) | Functions — Closures and captured variables / "Closure captures variable by binding, sees current value" | `test_closure(): int → 2` | closure invoked once after `x=2`; result 2 | OK | s12-closure-capture: `result !== 2` → `3` → FAIL, exit 1 |
| 28 | functions/for-loop-closure.deal | S12 (ISSUE-0129) | Functions — Closures and captured variables / "For-loop creates per-iteration closure binding" | `test_for_closure(): int → 12` | `f0,f1,f2` invoked once each; ordered values 0,1,2 | OK | s12-for-loop-closure: `v1 !== 1` → `2` → FAIL, exit 1 |
| 29 | functions/for-loop-closure-array.deal | S12 (ISSUE-0129) | Functions — Closures and captured variables / "For-loop closures stored in array capture per-iteration values" | `test_for_closure_array(): int → 12` | closure array length 3; indices 0..2 invoked once; values 0,1,2 | OK | s12-for-loop-closure-array: `v2 !== 2` → `3` → FAIL, exit 1 |
| 30 | functions/arity-extension.deal | S10 (ISSUE-0127) | Functions — Function type compatibility / "Arity extension: shorter function assigned to longer target" | `test_arity(): int → 10` | extended two-arg call `takesTwo(10,20)` returns first arg 10 | OK | s10-arity-extension: `result !== 10` → `11` → FAIL, exit 1 |
| 31 | parser/precedence-arithmetic.deal | S13 (ISSUE-0130) | Syntactic grammar — Operator precedence table / "Arithmetic operators follow correct precedence" | `test_precedence(): int → 34` | both constituents: `a = 2+3*4 === 14` AND `b = (2+3)*4 === 20` (not only the ambiguous sum 34) | OK | s13-precedence-arithmetic: `b !== 20` → `21` → FAIL, exit 1 |
| 32 | parser/precedence-logic.deal | S13 (ISSUE-0130) | Syntactic grammar — Operator precedence table / "Logical operators follow correct precedence" | `test_logic_prec(): int → 1` | `a = true\|\|false&&false === true` AND `b = (true\|\|false)&&false === false` | OK | s13-precedence-logic: `b !== false` → `!== true` → FAIL, exit 1 |
| 33 | runtime/int-convert.deal | S14 (ISSUE-0131) | Runtime execution model — Runtime library / "int(3.0) returns 3 at runtime" | `test_int(): int → 3` | `int(3.0)` invoked once, exact int 3 | OK | s14-int-convert: `i !== 3` → `4` → FAIL, exit 1 |
| 34 | runtime/number-convert.deal | S14 (ISSUE-0131) | Runtime execution model — Runtime library / "number(3) returns 3.0 at runtime" | `test_number(): number → 3.0` | `number(3)` invoked once, exact number 3.0 (same-static-type `!==`) | OK | s14-number-convert: `n !== 3.0` → `4.0` → FAIL, exit 1 |
| 35 | tables/dynamic-read-contextual.deal | S15 (ISSUE-0132) | Tables — Reading table fields / "Table field read with target type is allowed" | `test_table_read(): int → 42` | contextual int read `t.x === 42` | OK | s15-dynamic-read-contextual: `v !== 42` → `43` → FAIL, exit 1 |
| 36 | tables/dynamic-write.deal | S15 (ISSUE-0132) | Tables — Writing table fields / "Table field write is allowed" | `test_table_write(): string → "val"` | each write once; post-state `t.key==="val"` and `t.num===42` (distinguishes empty pre-state) | OK | s15-dynamic-write: `num !== 42` → `43` → FAIL, exit 1 |
| 37 | templates/template-basic.deal | S16 (ISSUE-0133) | Lexical elements — Template literals — Basic interpolation / "Template literal with identifier interpolation produces correct string" | `test_greet(): string → "Hello, World!"` | exact bytes `` `Hello, ${name}!` `` === `"Hello, World!"` | OK | s16-template-basic: expected → `"Hello, World!X"` → FAIL, exit 1 |
| 38 | templates/template-plain.deal | S16 (ISSUE-0133) | Lexical elements — Template literals — Plain string / "A template literal with no interpolation is just a plain string" | `test_plain(): string → "hello world"` | exact bytes `` `hello world` `` === `"hello world"` | OK | s16-template-plain: expected → `"hello worldX"` → FAIL, exit 1 |
| 39 | templates/template-empty.deal | S16 (ISSUE-0133) | Lexical elements — Template literals — Empty template / "An empty template literal (``) produces an empty string" | `test_empty(): string → ""` | exact bytes ``` `` ``` === `""` | OK | s16-template-empty: `s !== ""` → `"x"` → FAIL, exit 1 |
| 40 | templates/template-multi.deal | S16 (ISSUE-0133) | Lexical elements — Template literals — Multiple interpolations / "Template literal with several interpolations concatenates in order" | `test_multi(): string → "Hello, Alice! You have Bob as a friend."` | exact concatenation order of `a="Alice"`, `b="Bob"` | OK | s16-template-multi: expected → `…friend.X` → FAIL, exit 1 |
| 41 | templates/template-nested.deal | S17 (ISSUE-0134) | Lexical elements — Template literals — Nested template literal / "A template literal inside ${} (nested template) compiles and runs correctly" | `test_nested(): string → "[inner]"` | exact spaced nested source form; result bytes `"[inner]"` | OK | s17-template-nested: `"[inner]"` → `"[inner]X"` → FAIL, exit 1 |
| 42 | templates/template-nested-expr.deal | S17 (ISSUE-0134) | Lexical elements — Template literals — Nested expressions / "Template literal inside a for-of loop body, and inside a function call argument" | `test_forof_template(): string → "[Alice][Bob]"`; `test_call_template(): string → "Hi, World"` | per-iteration concat `"[Alice]"+"[Bob]"`; `greet(\`World\`)` — one oracle per export | OK | s17-…-A: `"[Alice][Bob]"` → `…X` → FAIL, exit 1; s17-…-B: `"Hi, World"` → `…X` → FAIL, exit 1 |
| 43 | templates/template-rbrace.deal | S16 (ISSUE-0133) | Lexical elements — Template literals — Brace scanning / "The '}' inside a string literal within ${} must not close the interpolation" | `test_rbrace(): string → "}"` | exact bytes `${ "}" }` === `"}"` | OK | s16-template-rbrace: `"}"` → `"}X"` → FAIL, exit 1 |
| 44 | types/nullable-narrowing.deal | S18 (ISSUE-0135) | Type system — Nullable semantics / "Narrowing allows T \| null to be assigned to T after null check" | `test_narrowing(): int → 1` | null branch is a failing path; narrowed int assignment + value 1 inside the non-null branch only | OK | s18-nullable-narrowing: `i !== 1` → `2` → FAIL, exit 1 |
| 45 | stdlib/console/import-log.deal | S19 (ISSUE-0136) | Standard library declarations — std/console / "std/console can be imported and used" | `test_console_log(): null → null` | real `console.log("hello")` invoked once; declared null completion compared with `null` (stdout bytes are outside the in-program contract) | OK | s19-console-import-log: completion check inverted `!== null` → `=== null` → FAIL, exit 1 |
| 46 | async-await/async-fn-decl.deal | A1 (ISSUE-0140) | Async/Await — async function declaration / "basic async function returning a value, awaited in async function" | `test_async_decl(): int → 42` | await declared `helper` once; awaited value 42 checked before return | OK | a1-async-fn-decl: `x !== 42` → `43` → FAIL, exit 1 |
| 47 | async-await/async-simple-await.deal | A1 (ISSUE-0140) | Async/Await — simple async with await / "Simple async function with await compiles and runs correctly" | `f(): int → 42` | await `g` once; awaited value 42 | OK | a1-async-simple-await: `x !== 42` → `43` → FAIL, exit 1 |
| 48 | async-await/async-fn-expr.deal | A2 (ISSUE-0141) | Async/Await — async function expression / "async function expression assigned to variable and awaited" | `test_async_expr(): int → 42` | typed async function expression awaited once; result 42 | OK | a2-async-fn-expr: `result !== 42` → `43` → FAIL, exit 1 |
| 49 | async-await/async-with-params.deal | A2 (ISSUE-0141) | Async/Await — async function with parameters / "Async function with parameters and await compiles and runs" | `compute(): int → 3` | `await add(1,2)` once; awaited result 3 | OK | a2-async-with-params: `result !== 3` → `4` → FAIL, exit 1 |
| 50 | async-await/async-no-await.deal | A3 (ISSUE-0142) | Async/Await — async function without internal await / "async function that returns immediately without any internal await exercises the __done guard; must not crash" | `test_no_await(): int → 42` | no await; synchronous completion 42 checked before return | OK | a3-async-no-await: `result !== 42` → `43` → FAIL, exit 1 |
| 51 | async-await/async-sync-complete.deal | A3 (ISSUE-0142) | Async/Await — async function without internal await / "Async function that completes synchronously (no internal await) does not crash" | `f(): int → 5` | no await; synchronous completion 5 | OK | a3-async-sync-complete: `result !== 5` → `6` → FAIL, exit 1 |
| 52 | async-await/async-nullable-return.deal | A4 (ISSUE-0143) | Async/Await — async function with nullable return type / "Async function with nullable return type — both null and non-null return paths" | `f_null(): string \| null → null`; `f_value(): string \| null → "hello"` | f_null rejects non-null and returns null; f_value rejects null and compares `"hello"` only in the narrowed branch (exports independent) | OK | a4-…-A: f_null completion `null` → `"x"` → FAIL, exit 1; a4-…-B: `result !== "hello"` → `"helloX"` → FAIL, exit 1 |
| 53 | async-await/async-if-branching.deal | A5 (ISSUE-0144) | Async/Await — async with if branching around await / "Async function with if/else branching uses await in both branches" | `f(): int → 10` | positive input branch awaited once (10) and zero/else branch awaited once (0) in one invocation | OK | a5-async-if-branching: `positive !== 10` → `11` → FAIL, exit 1 |
| 54 | async-await/await-in-if-condition.deal | A5 (ISSUE-0144) | Async/Await — await in conditions / "Await can appear in an if condition and resume with boolean value" | `test_await_in_if_condition(): int → 1` | `await yes()` in condition selects true branch; result 1; false branch forbidden | OK | a5-await-in-if-condition: `result !== 1` → `2` → FAIL, exit 1 |
| 55 | async-await/async-multiple-await.deal | A6 (ISSUE-0145) | Async/Await — multiple sequential await calls / "multiple await calls in sequence within a single async function, each suspending and resuming correctly" | `test_multiple_await(): int → 60` | first completion 20, second 60, sequential stage ends at 2 | OK | a6-async-multiple-await: `stage !== 2` → `3` → FAIL, exit 1 |
| 56 | async-await/async-multiple-awaits.deal | A6 (ISSUE-0145) | Async/Await — multiple sequential await calls / "Multiple await calls in sequence in same async function" | `f(): int → 6` | completions 1,2,3 in order; stage 3; sum 6 | OK | a6-async-multiple-awaits: `sum !== 6` → `7` → FAIL, exit 1 |
| 57 | async-await/async-multi-sync-complete.deal | A7 (ISSUE-0146) | Async/Await — multi-level synchronous completion chain / "Multiple levels of async functions where innermost completes synchronously; __done guard prevents dead-coroutine resume at every level" | `test(): int → 3` | level3→level2→level1 chain; invocation-local stage advances once per completion/resume, final stage 3, final value 3 | OK | a7-async-multi-sync-complete: `stage.step !== 3` → `4` → FAIL, exit 1 |
| 58 | async-await/async-nested.deal | A7 (ISSUE-0146) | Async/Await — nested async calls / "Nested async calls (A awaits B awaits C) work correctly" | `a(): int → 10` | C→B→A completion/resume stages; `a` terminal stage 2; final value 10 | OK | a7-async-nested: `stage.step !== 2` → `3` → FAIL, exit 1 |
| 59 | async-await/async-await-statement.deal | A8 (ISSUE-0147) | Async/Await — await as expression statement / "await expression used as a statement (discarding result) compiles and runs" | `f(): int → 0` | `await g();` stays a discarded expression statement; awaited op records invocation-local completion state 42 exactly once; checked after await; returns unchanged 0 | OK | a8-async-await-statement: `completionState !== 42` → `43` → FAIL, exit 1 |
| 60 | async-await/async-type-propagation.deal | A9 (ISSUE-0148) | Async/Await — type propagation / "async function returning int, awaited result is int; async function returning string, awaited result is string" | `test_int_propagation(): int → 42`; `test_string_propagation(): string → "hello"` | awaited int 42 and awaited string `"hello"`, one oracle per export | OK | a9-…-A: `x !== 42` → `43` → FAIL, exit 1; a9-…-B: `"hello"` → `"helloX"` → FAIL, exit 1 |
| 61 | async-await/async-cross-module.deal | A10 (ISSUE-0149) | Async/Await — cross-module async import / "import an async function from a companion module, await it, verify runtime execution returns correct values" | `test_cross_module(): int → 42`; `test_cross_module_greet(): string → "Hello, world"` | await real `lib.getAnswer()` → 42; await real `lib.greet("world")` → `"Hello, world"` (real companion import, one oracle per export) | OK | a10-…-A: `x !== 42` → `43` → FAIL, exit 1; a10-…-B: `"Hello, world"` → `…X` → FAIL, exit 1 |
| 62 | async-await/async-throw-catch.deal | A11 (ISSUE-0150) | Async/Await — throw inside async caught by catch / "throw inside async function caught by catch in same async function" | `f(): string → "E_TEST"` | fixed `E_TEST` throw once inside intended catch nested in fallback recorder; intended catch true, fallback false, normal false, code `E_TEST`, fallback code empty; assertion after both catches | OK | a11-async-throw-catch: `"E_TEST"` → `"E_OTHER"` → FAIL, exit 1; a11-missing-catch-code: intended catch bypassed → `OK (found DEAL_ERROR_CODE: TEST_FAIL)` under temporary `runtime-error TEST_FAIL` |
| 63 | async-await/async-error-propagation.deal | A12 (ISSUE-0151) | Async/Await — error propagation through await / "Error in awaited function propagates and is caught by catch around await" | `outer(): string → "E_INNER"` | `await inner()` throwing `E_INNER` once inside caller catch nested in fallback recorder; caller catch true, fallback false, normal false, code `E_INNER`, fallback code empty; assertion after both catches | OK | a12-async-error-propagation: `"E_INNER"` → `"E_OTHER"` → FAIL, exit 1; a12-missing-catch-code: caller catch bypassed → `OK (found DEAL_ERROR_CODE: TEST_FAIL)` under temporary `runtime-error TEST_FAIL` |

Ledger integrity: 63 rows = 45 synchronous (S1–S19, ISSUE-0118..0137) + 18
async (A1–A12, ISSUE-0140..0151); every baseline weak target appears
exactly once; no companion/declaration artifact (`@expected: companion`,
no `@expected`), already-asserted runtime-ok fixture, or other expected
mode is counted. `main(): null` entry exports are not assertion owners and
are never invoked twice (v1.2 entry contract).

## Semantic review

Each of the 110 `TEST_FAIL` sites in the 62 executable targets was
inspected against its `@description` (sources at
`test/conformance/backend-runtime/**`). Findings:

- **Reachability.** Every check is on the export's unconditional execution
  path: value/state checks run immediately after the tested operation and
  before the successful return; branch/path checks run on the path the
  fixture's description selects; error-path checks run after the tested
  catch region. Empirical confirmation: all 70 literal/state controls
  failed, which is only possible if the perturbed check executes. No
  check is dead code, unreachable after an unconditional return/break, or
  confined to a discarded return value (the runner discards return values;
  verdicts come from thrown Errors only).
- **Behavior-specificity.** Messages are static strings identifying the
  exact behavior (e.g. `"continue did not skip iteration i === 2"`), and
  each check compares a behavior-specific observable, not a surrogate:
  precedence checks both constituents (`a===14` AND `b===20`), closure
  fixtures check every ordered capture (0,1,2), recursion checks the
  terminal value plus the call count, array fixtures check length and
  relevant indices, templates compare the exact byte string.
- **Complete described observable.** S6/S7 reject a correct terminal
  value accompanied by a forbidden catch execution (distinct inner/outer
  flags); S8/S9 distinguish no-error, wrong-code, wrong-catch, and
  post-state; A5 rejects the false/else branch explicitly; A6 checks per
  await completion and the sequence stage; A8 checks the discarded
  completion state and invocation count; A11/A12 require intended-catch
  true, fallback false, normal false, exact intended code, and empty
  fallback code.
- **Single execution.** Each tested operation runs exactly once:
  conversions, console call, class construction, array/table mutation,
  closure invocations, awaited calls, and the tested throw all execute
  once before their checks. Repetition exists only where it is the
  subject: loop iterations, recursion, for-of elements, and multiple
  closure captures.
- **Exact `TEST_FAIL` shape.** Every site is
  `throw { code: "TEST_FAIL", message: "<static>" }` with only the
  declared `Error` fields; messages are static strings or string-only
  concatenations (no interpolation of non-strings).
- **Cannot be swallowed.** No `TEST_FAIL` is inside a tested catch's
  swallowing region: error fixtures place assertions after all
  try/catch regions and drive them from pre-declared flags; A11/A12 place
  the single assertion after both catch regions, so a bypassed intended
  catch produces an escaping `TEST_FAIL` (proven via the exact-code
  controls). No broad catch can convert a mismatch into success.

## Critical-case representation

| Critical case | Ledger rows | Evidence |
|---|---|---|
| Array length/index and mutation | 1, 2 | length + index checks; write checked against post-state differing from pre-state |
| Nested class / optional state | 3, 4 | nested `home.city`/`home.zip`; `has()` before/after one assignment |
| Branch, loops, break/continue, try-return, nested catch | 5–16, 21 | terminal state/sum checks; S6 flags for simple, inner, and outer catches; S7 helper-return + no-catch flags; S8 nearest-catch E8005 with outer forbidden |
| Ordered closure captures | 18, 28, 29 | per-index invocation, ordered 0..3 / 1..3 values |
| Direct/nested recursion | 25, 26 | terminal value 0 plus call counts 9/7 (not process survival) |
| Rest / arity | 24, 30 | normative E1047 compile oracle (no runtime oracle needed); extended-call result 10 |
| Unambiguous precedence constituents | 31, 32 | both constituents checked, not only the aggregate |
| No-error / wrong-code / wrong-catch | 21, 22, 23, 62, 63 | flag-driven post-region assertions; A11/A12 exact-code escape proven |
| Async shapes | 46–63 | declarations, expressions, parameters, no-await completion, multiple awaits, nested/sync-complete chains, discarded await, nullable paths, conditional awaits, cross-module awaits, propagated error code |
| Exact template forms | 37–43 | basic, plain, empty, multi, nested, for-of/call-argument context, literal `}` |
| Nullable null + narrowed paths | 44, 52 | null is failure for sync; A4 checks both the exact-null and the narrowed `"hello"` paths |
| Console real call + null completion | 45 | real `console.log("hello")` invoked once; declared null result compared with `null`; stdout bytes are not claimed (outside the in-program contract) |
| ASCII string for-of byte count/order | 19, 20 | `"hello"` → 5 byte iterations under the active v1.1 byte model; no v1.2 Unicode-scalar expectation introduced |

## Determinism review

All 110 assertion sites use fixed literals and bounded operations. No
assertion reads a clock, random source, locale, filesystem order, hash
iteration order, environment variable, or prior fixture state; none
sleeps, polls, retries, or assumes source-level concurrency. Cross-export
`pairs` order is never an oracle input: multi-export fixtures
(`for-of-break-continue`, `for-of-count`, `template-nested-expr`,
`async-nullable-return`, `async-type-propagation`,
`async-cross-module`) own one independent, self-contained oracle per
export, and each export's oracle-negative control was run separately.
Ordering assertions live inside one export invocation (A6/A7 stage
carriers). Loop bounds are small fixed constants; recursion inputs are
fixed (`down(8)`, `localDown(6)`); array/closure inputs are fixed
literals.

## Repository diff review

The epic's implementation commits (S1–S19: ISSUE-0118..0137; A1–A12:
ISSUE-0140..0151; 80 commits from `b5d2894^..HEAD` filtered by issue tag)
changed exactly 63 files, set-equal to the ledger above:

- all 62 executable targets retain `@expected: runtime-ok`, their
  `@spec`/`@description`/`@features` classifications, exported names,
  parameter lists, async markers, result types, and successful return
  values; `functions/rest-params.deal` retains its `@features` header and
  carries the authorized `@expected: compile-error E1047` (sole
  reclassification, per epic scope);
- the existing `main(): null` entry exports (v1.2 entry contract) are
  preserved and are not assertion owners;
- real execution is unchanged: fixtures still flow through lexer →
  parser → resolver → checker → `LuaBackend` → generated Lua +
  `deal/runtime.lua` + `std/*.lua` → LuaJIT subprocess, with the runner's
  exit-code verdict;
- no shared assertion module, new metadata/configuration tag, manifest,
  stdout capture, timeout change, or `TEST_FAIL` rendering change was
  introduced; companion modules (`async_lib.deal`, `async_module_lib.deal`,
  `async_batch2_lib.deal`) and the runner were not modified;
- no production compiler, runtime, stdlib, harness, or non-target active
  fixture change exists in the epic range; no strengthened fixture
  revealed a backend defect against the active v1.1 semantics, so D6
  corrective action was never triggered (conforming oracles all passed;
  the only failing probe historically was multibyte string iteration,
  which is pinned v1.1 byte behavior and outside this epic).

## Final composition run (`./run_tests.sh`)

Command: `./run_tests.sh` — exit code **0**, final marker
`=== All Tests Passed ===`. Full log retained at
`/tmp/wd0304_final.log`. Observed counters:

| Runner | Result |
|---|---|
| DiagnosticClassificationTest / AstAndTypesTest / LexerTest / ParserTest / CheckerTest / IrDumperTest / IrGoldenTest / TypeDescriptorTest / LuaBackendTest / LuaBackendIntegrationTest / JUnit LuaAbi suites / ModuleSystemTest / StdlibDeclParseTest / SourceMapTest / RuntimeSourceLocationTest | all pass, `Failed: 0` |
| BackendConformanceTest | `Total: 434, Passed: 434, Failed: 0, Skipped: 0` (3 tracked known-fails for ISSUE-0111) |
| JvmBackendTest | `Passed: 1324, Failed: 0` |
| StdlibContractTest | `Passed: 60, Failed: 0, Skipped: 0` |
| LuaJIT suites `test_runtime.lua` / `test_runtime_jsonable.lua` / `test_stdlib.lua` / `test_async_nesting.lua` | run (LuaJIT 2.1.0-beta3 present), all pass; no `luajit not found` warning anywhere in the log |
| ConformanceTest (LuaJIT) | `Discovered 409`, `Total: 373, Passed: 373, Failed: 0, Skipped: 0` (6 tracked known-fails for ISSUE-0111); all 63 ledger targets print `OK` (rest-params: `OK (found E1047)`); `Companions (classified support modules): 30` |
| Stdlib Golden IR check | `Golden IR file is current` |
| JvmConformanceTest (ISSUE-0102) | Gates PASSED: frontend 100%; backend-runtime pass rate 66.3% over the unchanged 255-test denominator; zero unclassified skips |

Skip reconciliation (per `async-runtime-ok-oracle-migration`): every
executable LuaJIT-side runner that exposes a skip counter reports
`Skipped: 0`. The JVM conformance phase reports its separately governed,
pre-existing *classified* skip groups (ISSUE-0099 async slice 35,
ISSUE-0100 host ABI 12, ISSUE-0101 @jsonable 34, ISSUE-0110 cross-module
function values 3 — each with a reason and tracked follow-up issue, and
zero unclassified skips). Those groups are outside this epic's scope and
unchanged by it; they are not executable-test skips.

## Evidence boundaries

- Exact stdout bytes of the console fixture are **not** claimed: the S19
  oracle verifies real invocation and the declared null completion, per
  D3/D5.
- Unicode-scalar string iteration is **not** claimed: string for-of
  oracles assert the active v1.1 byte model with ASCII inputs, per D5.
- No evidence from mocks, isolated helper-only tests, unreachable
  assertions, or committed perturbations is used: every oracle-negative
  result came from the real `ConformanceTest` + LuaJIT route on a
  scratch copy and every control was reverted (repository status verified
  clean after all controls).
