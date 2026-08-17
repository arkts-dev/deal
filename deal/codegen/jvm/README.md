# deal.codegen.jvm — JVM Backend (ISSUE-0091 skeleton, ISSUE-0092 while/template slice, ISSUE-0093 functions and direct calls slice)

A small but real end-to-end JVM backend for the DEAL compiler. It walks the
typed AST (the compiler's IR — `deal-compiler-architecture-v1`) and emits a
self-contained Java class whose static methods implement the module's
functions. The Java source is a real JVM artifact: `CompilationOrchestrator`
phase 4 (backend `jvm`) writes the `<ClassName>.java` source, and the
conformance harness (`test/BackendConformanceTest.java`) plus
`test/JvmBackendTest.java` compile it with `javac` and execute it with
`java` in subprocesses — every JVM fixture in the suite runs against a
real compiled artifact. Production phase 4 itself only writes the source
and does not run `javac`/`java`; therefore the CLI cannot observe a
javac-rejected artifact, which is exactly why the backend guarantees that
every artifact it emits is valid, compilable Java (see
"Observable-behavior guarantees" below).

## Skeleton scope

Supported (real semantics, spec JVM value mapping):

| DEAL | JVM representation |
|---|---|
| `int` | primitive `long` (checked arithmetic over the DEAL safe range ±(2^53-1) = ±9007199254740991: every int-producing operation checks its result with the emitted `checkInt` helper — E8004 out of safe range, E8005 div-by-zero, E8006 negative exponent, non-finite powers → E8001, all matching LuaJIT's `check_int`; int literals outside the safe range are checked at their point of use) |
| `number` | primitive `double` (Lua-style floored `%` via `numMod`; literals that overflow to ±Infinity — e.g. checker-accepted `1e999` — render as `Double.POSITIVE_INFINITY`/`Double.NEGATIVE_INFINITY`, mirroring the Lua backend's `(1/0)`, so the artifact stays valid Java) |
| `boolean` | primitive `boolean` |
| `string` | `java.lang.String` (`===` via `equals`; ordering via the emitted `scalarCompare` helper — Unicode scalar-value order, matching LuaJIT's UTF-8 bytewise order including supplementary characters) |
| template literals | plain Java string concatenation in source order (`("a" + expr + "b")`) — checker-typed `string` interpolations, empty literal parts elided, an empty interpolation kept, a template with no interpolations emitted as its literal |
| `null` | `void` returns / `Void` locals and params; `null === null` is `true`, `z === null`/`!==` emit Java `==`/`!=` (spec §Value equality) |
| functions | `static` methods of the generated module class: parameters, return values, direct calls, nested calls, and direct self-recursion (a function body calling itself — the module-level use-before-declaration guard applies only to load-time call sites, never to function-body call sites) |
| `let` locals / module fields | locals (shadowing disambiguated `$n`) / `static` fields |
| `if`/`else if`/`else`, `return`, assignment, direct calls | plain Java control flow |
| `while` loops | plain Java `while` with the condition routed through the emitted `loopCond` identity helper (javac never sees a constant-expression condition — JLS §14.21 keeps `while (false)` bodies and statements after `while (true)` reachable); the condition re-evaluates on every iteration, with hoisted null-typed side effects running inside the loop before each condition test; module-level while loops run in the load-time `static` initializer in source order and reject any `return` in their body with E6000 |
| standalone expression statements (`x + 1;`) | lowered to a dummy-local declaration (`long __ignored = intAdd(x, 1L);`) so they are genuinely evaluated — an int overflow there is an observable E8004, as under LuaJIT |
| `int()` / `number()` intrinsics | `intFromNumber` / `numberFromInt` helpers (E8001/E8004) |
| `import * as c from "std/console"` | `c.log` → `java.lang.System.out.println`, `c.error` → `java.lang.System.err.println` |

Out of scope (rejected with a backend `E6000` diagnostic, never silently
miscompiled): modules (any import other than `std/console`), classes, arrays,
tables, nullables, function types, stdlib modules, async/await, for/for-of loops,
try/throw, host ABI, `@jsonable`.

The JVM's static type system proves typed boundaries redundant, which the
current normative spec explicitly permits (`docs/spec-v1.1.md` §JVM backend
contract: "The JVM backend may use JVM primitive types, final classes,
verifier-checked bytecode, method signatures, and JIT optimization to prove
typed-boundary checks redundant"; `docs/spec-v1.2.md` is a future draft
whose grammar is not normative for this backend — see "Known skeleton
limitations"). This is why `let z: null = console.log("x")` runs the
print and stores null under JVM while LuaJIT's `check_null` rejects the raw
nil — the JVM backend proves the boundary statically, exactly as the spec
allows.

## Observable-behavior guarantees (rework round)

Every checker-accepted program in scope either compiles to a valid,
behavior-preserving Java artifact or is rejected with E6000 — the CLI never
reports success for an artifact `javac` would reject.

- **Null-typed side effects are never discarded.** `return console.log("x")`,
  `return helper()` (helper: `() => null`), `let z: null = console.log(...)`,
  `x = console.log(...)` (x: null), and null-typed call arguments evaluate
  their expression for its observable behavior: the void call is *hoisted*
  into a pre-statement emitted right before the containing statement, and
  the value position yields `null`
  (`System.out.println("assign-log"); Void z = null;`). LuaJIT evaluates
  the same expressions before returning/assigning, and the hoisting
  preserves DEAL's left-to-right evaluation order: when a hoisted call
  has an earlier inline side-effecting sibling in the same statement
  (`f(g(), console.log("x"))` — LuaJIT runs g() first), that sibling is
  materialized into a fresh `__t<n>` temporary assigned immediately
  before the hoisted statement (`long __t0 = g();
  java.lang.System.out.println("x"); return f(__t0, null);`). The
  materialization also covers operands that can *raise*: an earlier
  `a ** 400` reports its E8004 before the hoisted `console.log` ever
  runs, exactly like LuaJIT's left-to-right evaluation that stops at the
  first runtime error. This applies in every combination position —
  call arguments (user calls and console calls), binary operands,
  assignment values, return expressions, if conditions, and
  short-circuit operands (where the guard keeps the order inside the
  `if (LEFT) { … }` block). Materialized temporaries are declared inside
  the same pre-statement sequence, so a module-level field initializer
  referencing one is routed through a static-block assignment (a
  class-body initializer cannot see a block-local declaration).
- **`&&` / `||` short-circuit is preserved.** A null-typed side-effecting
  call in a *non-leading* operand (`false && helper() === null`,
  `true || helper() === null`) is hoisted, but the hoisted statements are
  guarded by the left operand and the result is carried in a fresh
  `__sc<n>` temporary — `helper()` is never called when LuaJIT's `and`/`or`
  would skip the operand. Reachable operands (`true && …`,
  `false || …`) still run, and left-operand hoists stay unconditional, in
  evaluation order. At module level, a field initializer that references
  the temporary is emitted as a plain field declaration plus an assignment
  inside the same static block, so the temporary stays in scope and the
  artifact stays valid Java.
- **No lambdas are ever emitted.** The previous `nullAnd(() -> …)` lambda
  lowering could make `javac` reject an artifact when the call captured a
  local or parameter reassigned anywhere in its enclosing scope ("local
  variables referenced from a lambda expression must be final or
  effectively final") — `let z: null = console.log(a); a = "y";` is a
  checker-accepted, in-scope program. The statement-hoisting lowering
  contains no captures, so reassigned locals and parameters in
  initializer/argument/assignment positions always compile.
- **Module-level statements run at load time.** Non-declaration module-level
  statements (calls, assignments, `if`s, blocks) are emitted into
  `static { … }` initializer blocks interleaved in source order with field
  initializers, so `console.log("a"); let x = f();` runs `a`, then `f()`
  — exactly where LuaJIT executes module-level statements (require time).
  Hoisted side effects of a module field initializer are wrapped in their
  own `static { … }` block emitted before the field declaration.
  Module-level `return` is rejected with E6000 (Java initializers cannot
  return).
- **Shadowed initializers bind to the enclosing scope.** The initializer is
  emitted *before* the new local is registered, mirroring Lua's
  `local x = x + 1` (RHS reads the outer `x`): `let x: int = 5; { let x: int
  = x + 1; return x; }` computes 6.
- **Parameter shadowing emits one Java name per binding.** A parameter
  (or local) that shadows a module field — or any other visible
  binding — gets a collision-free `$n` suffix used in BOTH the method
  signature and the body (ISSUE-0093 fix). The previous backend
  declared the parameter through the disambiguating `declareLocal`
  (`x → x$1` when a module field `x` exists) but wrote the signature
  with the raw `javaName` translation (`static long f(long x)`), so
  the body read `x$1` and javac rejected the artifact after the CLI
  reported success ("cannot find symbol x$1"). The shadowed-`let`
  initializer case was broken by the same machinery: `declareLocal`
  compared DEAL names (scope keys), not emitted names (scope values),
  so `let g: int = g + 10` over a disambiguated parameter silently
  reused the parameter's emitted name and its initializer's enclosing
  read referred to the uninitialized new binding (`long g$1 =
  intAdd(g$1, 10L);` — "might not have been initialized"). Both
  shapes now emit valid Java and compute the LuaJIT values: `let g:
  int = 1; function f(g: int): int { let g: int = g + 10; return g; }
  export function test(): int { return f(5) + g; }` returns 16 (the
  let's RHS reads the parameter → 15; the module field keeps 1),
  pinned by the fixtures `jvm-fn-param-shadow-module-field` /
  `jvm-fn-param-shadow-let` and `JvmBackendTest.testParameterShadowing`.
- **While conditions are never constant expressions to javac.** Every
  emitted loop routes its condition through the `loopCond` identity
  helper: `while (loopCond(cond)) { … }`. Per JLS §14.21 javac then
  treats the body as reachable (`while (false)` compiles and simply never
  runs, exactly like LuaJIT) and statements after the loop as reachable
  (`while (true)` followed by code compiles; LuaJIT never executes that
  code, and neither does the artifact). Without the wrapper, `while
  (false)` bodies and statements after `while (true)`/`while (1 === 1)`/
  `while (null === null)` would be javac-unreachable — artifacts the CLI
  would have reported as successful. A DEAL function whose mapped
  signature duplicates `loopCond(boolean)` is E6000 (the
  helper-collision rule).
- **While conditions re-evaluate on every iteration, hoisted side
  effects included.** A condition whose evaluation hoists a side-effecting
  null-typed call (e.g. `i < 3 && tick() === null`) emits
  `while (true) { …pre-statements…; if (!loopCond(cond)) break; …body… }`:
  the hoisted statements run inside the loop before the condition test,
  so `tick()` runs once per iteration — never once before the loop, and
  never in an order that would diverge from LuaJIT's per-iteration
  condition evaluation. The `if (!loopCond(cond)) break;` break is
  reachable (a non-constant condition), so the statement completes
  normally per JLS §14.21 and statements after it stay reachable.
- **Module-level while loops run at load time and cannot contain
  `return`.** A module-level while is emitted into the load-time
  `static` initializer in source order; a `return` anywhere inside its
  body (including nested blocks/ifs) is E6000 — Java initializers cannot
  return, and the guard walks the whole body. While-condition
  use-before-declaration follows the same guard as if conditions: a
  later-declared variable in a while condition (module level or function
  body) is E6000, never an artifact javac rejects.
- **Template literals concatenate their parts in source order.** The
  checker types every interpolation as `string` (E3016 otherwise) and
  the whole template as `string`, so the emitted Java is plain
  `("a" + expr + "b")` concatenation with no runtime conversion; empty
  literal parts are elided (they contribute nothing under LuaJIT's
  `"" .. x` lowering either), an empty *interpolation* is kept (it is an
  expression), and a template with no interpolations emits its literal.

- **Dead code after a non-completing statement is skipped, never
  emitted.** A `return` cannot complete normally; an `if`/`else` whose
  branches all cannot complete normally cannot complete normally; a block
  ending in such a statement cannot complete normally (JLS §14.21).
  LuaJIT never executes statements after such a statement, and javac
  rejects them as unreachable — emitting them produced exactly the broken
  artifact class the guarantees above forbid (`return 3;` after a
  complete all-returning if/else, reported as success by the CLI).
  Completion tracking now skips every dead statement in the rest of the
  same block/function (including dead `let`s, dead standalone expression
  statements, and dead else-if chains with hoisted null-typed conditions),
  so every checker-accepted program in scope emits valid Java:
  `export function test(): int { if (true) { return 1; } else { return
  2; } return 3; }` emits only the if/else and runs 1, exactly like
  LuaJIT.
- **Use-before-declaration is rejected, not miscompiled.** A value use of a
  variable before its own declaration with no enclosing binding (a
  self-referential initializer, `console.log(x); let x = …` inside a
  function) reads the not-yet-declared global value under LuaJIT (nil
  unless a prior write established it) and Java would reject the forward
  reference after the CLI reported success; a module-level (load-time)
  forward reference to a later module field is the same shape plus
  Java's illegal-forward-reference rule. The backend emits E6000 for
  these instead. The detection is transitive through the module-level
  call graph: a module-level call of a function whose body (directly or
  through other module functions) reads a module field declared at or
  after the call site is E6000 (a field's own initializer calling a
  function that reads the field being initialized is included) — LuaJIT
  fails at load with a nil read, and Java would silently read the
  field's default value (verified: `f(); let x: int = 5; function
  f(): int { return x; }` fails at load under LuaJIT). Reads through
  function-local shadows of a module field are correctly not flagged.
  **Function-body access to a module field is governed by the field's
  declaration order relative to the function.** A function declared
  AFTER the field reads/writes the module-local upvalue — emitted as a
  plain static-field access, full parity (`let x: int = 1; function
  f(): int { x = 5; return x; } export function test(): null { if
  (f() === 5 && x === 5) { console.log("field-write-parity"); } }`
  prints on both backends, pinned by the cross-backend fixture
  `jvm-function-field-write-parity`). A function declared BEFORE the
  field does not capture the module-local under LuaJIT (the local does
  not exist when the function value is created), so every access in its
  body binds to the GLOBAL of the same name at call time — and both
  shapes are E6000 rather than silently diverging:
  - **a WRITE is rejected in every position** (statement, block,
    if/else branch, return value, call argument) — LuaJIT writes the
    global, leaving the module-local untouched, so later readers
    (functions declared after the field, exported functions) observe
    the initializer value, while Java would write the static field and
    pollute every later reader. The write-then-read shape
    (`x = 5; return x;`) is included: the function's own read observes
    the global write under LuaJIT, but the polluted Java field remains
    observable by later readers (the reviewer's probe
    `function f(): int { x = 5; return x; } let x: int = 1; export
    function g(): int { return x; }` prints "parity" under real luajit
    — f() == 5, g() == 1 — while the JVM static field would make
    g() == 5);
  - **a READ without a dominating write inside the function is
    rejected** — LuaJIT reads the global nil at call time and fails
    (E8001) while Java would silently read the initialized static
    field. Dominance is tracked along every execution path: a write in
    *both* branches of an if/else dominates the read after it, while a
    write in a taken-only branch (no else) and a write inside a called
    function do not establish dominance (conservative rejection: the
    callee's or branch's writes may never execute, in which case
    LuaJIT would read the global nil).
  Function-local shadows of the field are local uses, never flagged.
- **Assignment targets get the same guard.** A write to a
  later-declared function-local with no enclosing binding
  (`x = 5; let x: int = 1` inside a function — in statement, block, `if`,
  `return x = 5;`, and `f(x = 5)` positions) is E6000, never a Java
  forward-reference artifact javac rejects after the CLI reported success
  (the checker resolves such targets contextually, so the module-scope
  symbol table reports null). Module-level writes to later-declared
  fields stay allowed and keep using the static field name: LuaJIT's
  global write is overwritten by the later initializer, Java's
  static-field write is overwritten by the field initializer, and every
  observer of the intermediate value (module-level pre-declaration
  reads, pre-declaration function accesses) is already E6000, so the
  final value is identical on both backends (verified with real luajit
  runs).
- **Module-level calls never reach not-yet-declared functions.** LuaJIT
  assigns each function value at its declaration point in source order,
  so a module-level call that reaches a function declared at or after the
  call site — the callee itself, a transitively-called module function,
  or a field initializer calling a later function — fails at load with a
  nil read, while Java's hoisted methods would silently run. These are
  E6000 (detection is a fixpoint closure over the module-level call
  graph); calls whose callee and transitive callees are all declared
  before the call site run at load with LuaJIT parity (the declaration-
  first interleaving shape is a cross-backend fixture). The guard covers
  only LOAD-TIME (module-level) call sites: calls inside function bodies
  happen at call time, when every module function value is already
  assigned under LuaJIT and every static method is hoisted under Java,
  so direct self-recursion (`function fact(n: int): int { … return n *
  fact(n - 1); }` — fixtures `jvm-fn-self-recursion-factorial` /
  `jvm-fn-self-recursion-fibonacci` / `jvm-fn-void-recursion`) and
  forward calls between functions run with full parity.
- **Imports are rejected at the import statement.** Any `import` other than
  `std/console` is an E6000 at the import itself — even when unused —
  because the imported module's require-time side effects cannot be
  reproduced by the skeleton (a never-initialized JVM class would silently
  drop them) and multi-module JVM projects must fail loudly.
- **Class names are collision-safe.** The class name derives from the full
  module path (`app/main` → `AppMain`, `sub/main` → `SubMain`), and the
  orchestrator reports E6000 if two modules still map to the same class
  name (e.g. a case-only difference) — an artifact is never silently
  overwritten.
- **Runtime-helper collisions are rejected.** A DEAL function whose name and
  mapped signature duplicate an emitted helper (`intAdd(a: int, b: int)`)
  is an E6000, not a duplicate-method javac error.
- **The DEAL int safe range ±(2^53-1) is enforced everywhere.** Every
  int-producing operation — `intAdd`/`intSub`/`intMul`/`intDiv`/
  `intMod`/`intNeg`/`intPow`/`intFromNumber` — checks its result with the
  emitted `checkInt` helper against ±9007199254740991, exactly like
  LuaJIT's `__rt.check_int` wraps `__rt.int_add(a, b)` etc.
  (`9007199254740991 + 1` raises E8004 on both backends; `2 ** 62` raises
  E8004; `int(9007199254740992.0)` raises E8004; `return
  9223372036854775807;` raises E8004 at the boundary on both). Int
  literals outside the safe range are checked at their point of use
  (wrapped in `checkInt`), so a checker-accepted program can never
  silently compute with a value that is not a valid DEAL int. One
  deliberate divergence is documented below: inside arithmetic LuaJIT
  silently rounds an out-of-range literal to a double first
  (`9223372036854775807 % 2` computes 0 there), which the JVM backend
  refuses to reproduce — it raises E8004 for the invalid int value
  instead of silently diverging.
- **All `java.lang` references in generated code are fully qualified.**
  DEAL identifiers may be named `System`, `Math`, `Double`, `String`,
  `Void`, `Integer`, `Character`, `RuntimeException`, or
  `ArithmeticException` (`javaName` passes non-reserved names through
  unchanged), and an unqualified generated reference (`System.out`,
  `Math.addExact`, `Double.isNaN`, the `String`/`Void` type names) would
  bind to the user's field or local instead of `java.lang`, producing an
  artifact javac rejects while the CLI reports success. The emitted code
  uses `java.lang.System.out/err`, `java.lang.Math.*`,
  `java.lang.Double.*`, `java.lang.String`, `java.lang.Void`,
  `java.lang.Integer.compare`, `java.lang.Character.charCount`,
  `java.lang.RuntimeException`, and `java.lang.ArithmeticException`
  (pinned by the `jvm-javalang-name-collisions` fixture and
  `JvmBackendTest.testJavaLangNameCollisions`, which bind all ten names
  as module fields/locals/parameters alongside console output, int
  arithmetic, string ordering, non-finite literals, and a runtime error).
- **The use-before-declaration guard walks every condition of an
  `if`/`else if` chain.** `emitIf`/`emitIfContinuation` emit the follow-on
  conditions directly (no per-statement guard runs for them), so a
  later-declared variable in an `else if` condition — module-level
  (`if (true) { } else if (z === 2) { } let z: int = 2;` — Java's
  illegal-forward-reference rule) or function-body (`if (true) {} else
  if (x === 2) {} let x: int = 2;` — cannot-find-symbol) — previously
  emitted an artifact javac rejected after the CLI reported success.
  These are now E6000; chain conditions over already-declared variables
  stay clean (pinned by `JvmBackendTest.testElseIfChainUseBeforeDeclaration`
  and the `jvm-elseif-chain-declared-first` fixture).

## Review evidence: backend-selection seam

- **Selection seam** — `deal/codegen/Backend.java` (`LUAJIT`, `JVM`,
  `fromCliName`). `CompilationOrchestrator` takes a `Backend` in the new
  9-arg constructor; every pre-existing constructor delegates with
  `Backend.LUAJIT` (`deal/module/CompilationOrchestrator.java`). The CLI
  selects it via `--backend <lua|jvm>` (`deal/Main.java`); the project
  manifest selects it via `deal.json`'s `"backend"` field
  (`deal/module/DealConfig.java` accepts `"jvm"` and — for parity with the
  CLI alias — `"lua"`/`"luajit"`, case-insensitively exactly like
  `Backend.fromCliName`, so `--backend JVM` and `"backend": "JVM"` are
  the same spelling). LuaJIT remains the default everywhere: no flag, no
  manifest field, and no constructor argument all select it.

- **Lua use site** — `CompilationOrchestrator.codegenLuaModule()`: the
  pre-ISSUE-0091 `LuaBackend.generateToFile`/`generateWithImports` emission
  plus `copyRuntimeLibrary`/`copyStdlibModules`, byte-for-byte unchanged,
  dispatched only when `backend == Backend.LUAJIT`.

- **JVM use site** — `CompilationOrchestrator.codegenAllJvm()`: calls
  `JvmBackend.generate(...)` for every module, merges E6000 diagnostics into
  the standard error report (no artifact for a rejected module), writes
  `<ClassName>.java` with collision detection, fails the compile on any
  E6000, and prints a warning when `--source-map` is requested (the JVM
  path produces no sidecars). The conformance use site is
  `test/BackendConformanceTest.runJvmAssertions()`: real `JvmBackend`
  codegen → `javac` subprocess (asserts `.class` artifacts exist) →
  `java` subprocess → asserts stdout/error/exit code.

- **Tests proving both** —
  - LuaJIT: the full pre-existing suite (`test/LuaBackendTest`,
    `test/LuaBackendIntegrationTest`, `test/ConformanceTest`,
    `test/conformance/fixtures/*.json` `backends: ["luajit"]`) stays green
    and unmodified.
  - JVM: `test/conformance/fixtures/jvm-functions-slice.json` — thirteen
    ISSUE-0093 fixtures, all JVM-only, every runtime fixture passing
    through the real frontend → real `JvmBackend` codegen → `javac`
    subprocess → `java` subprocess executing the emitted artifact:
    direct function calls, multiple parameters of mixed types,
    return values flowing through a call chain, nested calls
    (callee-of-callee argument sub-expressions), direct self-recursion
    (factorial, the double-self-call fibonacci, and a side-effecting
    void recursion whose hoisted recursive call runs per activation),
    left-to-right argument evaluation order with side effects
    (all-inline argument positions, and an inline argument materialized
    into a temporary ahead of a hoisted null-typed argument), parameter
    shadowing (a parameter shadowing a module field; a `let` shadowing
    a parameter that itself shadows a module field), and two frontend
    compile-error gates rejected before any backend (E3009 arity
    mismatch, E5001 argument-type mismatch).
  - JVM: `test/conformance/fixtures/jvm-semantic-slice.json` — fourteen
    ISSUE-0092 fixtures, all JVM-only, every runtime fixture passing
    through the real frontend → real `JvmBackend` codegen → `javac`
    subprocess → `java` subprocess executing the emitted artifact (the
    harness fails a fixture whose codegen or JVM execution is bypassed):
    int arithmetic (+ - * / % ** chained through locals), number
    arithmetic with mixed int/number widening and floored `%`
    (-7.0 % 3.0 = 2.0), string concatenation, template literals
    (string-typed interpolations; plain templates; empty literal parts
    elided; empty interpolation parts kept), boolean comparisons
    (< <= > >= === !== ! && || over int/string/boolean), local mutation
    with inner-block shadowing, if/else-if/else, a counting while loop
    (1..10 = 55), nested while loops with body-scoped shadowing
    (triangular sums 1+3+6+10 = 20), `while (false)` whose body never
    runs, a while condition with a side-effecting null-typed call
    re-evaluated per iteration (tick prints, the loop counts to 3), and
    two frontend compile-error gates rejected before any backend
    (E3007 non-boolean while condition, E3016 non-string template
    interpolation).
  - JVM: `test/conformance/fixtures/jvm-skeleton.json` — fifty-three
    fixtures (JVM-only, plus cross-backend parity fixtures that also run
    under LuaJIT as the reference behavior): literals/output, int arithmetic,
    local variables with
    shadowing, if/else, a frontend compile-error rejected before the
    backend, null-typed return side effects ×2, null-typed initializers,
    module-level load-time statements and assignment, a shadowed
    initializer computing 6, null-typed captures of reassigned locals and
    parameters, floored `%` on negative operands, string equality/ordering
    (scalar order incl. supplementary characters), `console.error` → stderr,
    null equality, standalone expression statements, module-load-only and
    module-load-error fixtures, non-finite literals ×2, `&&`/`||`
    short-circuit preservation ×3, a module-level error with a
    zero-arity export, the module-field shadow write, the module-level
    later-field write, the declaration-first interleaved load-time
    ordering, dead-code skipping after non-completing statements ×3
    (complete if/else chain, block-ending return with dead let/expression,
    dead else-if chain with a hoisted null-typed condition), the
    function-body module-field write parity shape (field declared before
    the function: `x = 5; return x;` plus a later reader observing the
    written local on both backends), the
    int safe range ±(2^53-1) pinned at the boundary (the inclusive
    boundary itself, and cross-backend E8004 pins for add, sub, `**`
    ×2, `int()`, and an out-of-range literal — plus the exact JVM value
    fixture and the JVM-only out-of-range-operand rejection),
    fully-qualified `java.lang` references (all ten colliding field names
    in one module; a field named `Double` next to a `1e999` literal), and
    a positive module-level else-if chain over already-declared fields, and
    left-to-right evaluation-order parity for hoisted null-typed side
    effects in call-argument, binary-operand, short-circuit, module-level,
    and nested-combination positions ×5 (twenty-one fixtures run under
    both backends as cross-backend parity)) run end-to-end under
    `test/BackendConformanceTest`.
  - Seam: `test/JvmBackendTest.java` — ISSUE-0092 while/template coverage
    (counting/nested/shadowed loops, `while (false)` bodies skipped,
    per-iteration condition re-evaluation with a hoisted null-typed call
    pinned at tick ×3, function-body module-field dominance guards
    through while bodies — a body write dominates nothing after the loop,
    a condition read of a later field is E6000, and the declared-first
    shape runs with parity — module-level returns inside while bodies
    E6000, while-condition use-before-declaration E6000, the `loopCond`
    helper-signature collision E6000, template lowering with emission
    assertions for interpolated/plain/empty-part shapes), plus the
    pre-existing identifier translation, collision-safe
    class-name derivation, emission, E6000 rejection (including unused
    imports, module-level returns, use-before-declaration, helper-name
    collisions), observable-behavior preservation for null-typed side
    effects (incl. reassigned-local captures in initializer/argument/
    assignment positions and via parameters, with a no-lambda emission
    assertion), module-level statements/shadowed initializers via
    `javac`+`java` subprocesses, runtime error codes (E8004/E8005/E8006 and
    the E8001 infinity alignment), non-finite number literals, `&&`/`||`
    short-circuit preservation (function-local and module-level, with
    reachable and skipped operands), scalar string ordering, module-level
    calls reading later-declared fields (E6000, incl. the transitive
    path), module-level calls reaching later-declared functions (E6000:
    direct, transitive, deep-chain, field-initializer, and later-export
    shapes — plus the declaration-first interleaving parity case),
    assignment to later-declared locals (E6000 in statement/block/if/
    return/argument positions, with the allowed module-field shadow write
    and module-level later-field write), function-body module-field
    access governed by the field's declaration order — a field declared
    BEFORE the function is the upvalue/static-field write with full
    parity (the canonical write-then-read shape, writes in both if/else
    branches, and condition/argument positions allowed and executed via
    `javac`+`java`, pinned by the cross-backend fixture
    `jvm-function-field-write-parity`), while a field declared AFTER the
    function is E6000 in every shape — the reviewer's round-8
    write-then-read-plus-later-reader probe (real luajit prints "parity"
    with f() == 5, g() == 1; the JVM static field would make g() == 5),
    the write-then-read without a later reader, the write-only shape,
    block/both-branch/return-value/call-argument-position writes, the
    no-prior-write read (plain read, initializer position, taken-only
    branch write, write-via-callee), and the untouched module-level
    load-time guard), left-to-right evaluation-order preservation for
    hoisted null-typed side effects (call-argument/initializer/
    statement/if-condition/short-circuit/binary positions with order
    assertions, the reverse shape, a three-operand hoisted-inline-hoisted
    mix, an earlier raising operand that must run before the hoisted
    call, and emission assertions pinning the materialized temporary
    before the hoisted println — never a lambda),
    dead-code skipping after non-completing statements (complete if/else,
    return-after-return, block-level dead let/expression, dead else-if
    chain with a hoisted null-typed condition, the no-over-skip open-if
    shape, and emission-level skip assertions), the int safe range
    ±(2^53-1) (boundary success, E8004 pins for add/sub/mul/neg/`**`/`int()`/
    literals/operand literals via `javac`+`java`, and emission assertions
    that helpers route through `checkInt` with the safe-range bound),
    `java.lang` name collisions (locals/parameters/fields named
    System/Math/Double/String/Void/Integer/Character/RuntimeException/
    ArithmeticException alongside console output, int arithmetic, number
    `**`, non-finite literals, string ordering, and runtime errors —
    all compiled and executed, with qualified-name emission assertions),
    else-if chain use-before-declaration (module-level, function-body,
    and deep-chain E6000s, plus clean-chain positives), fixture-schema
    validation
    (expectedCompileError combined with runtime/IR assertions fails with a
    clear message per conformance-test-architecture D6), the runner's
    `DEAL_ERROR_CODE`
    contract for module-level errors
    with and without a zero-arity export, the orchestrator JVM path (artifact
    exists, compiles, no `.lua`/runtime copied, import rejection,
    class-name collision detection, the `--source-map` warning), the
    orchestrator default staying LuaJIT, `DealConfig` and CLI backend
    selection.

## Tests

```bash
./run_tests.sh
```

runs the whole suite (warning-free build — the `E9999` NameResolver-exception
diagnostics in the test harnesses carry the project's
`@SuppressWarnings("deprecation")` precedent). Targeted:

```bash
javac --release 25 -d build deal/codegen/jvm/*.java deal/codegen/*.java \
  deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java \
  deal/parser/*.java deal/checker/*.java deal/ir/*.java deal/module/*.java \
  deal/codegen/lua/*.java deal/Main.java test/*.java
java -ea -cp build deal.test.JvmBackendTest            # unit + seam tests
java -ea -cp build deal.test.BackendConformanceTest    # JVM + LuaJIT fixtures
```

The JVM fixtures require `javac` and `java` on `PATH` (present in any JDK
that builds this repository); when absent or broken (non-zero `-version`
probes), the JVM runtime fixtures are skipped, mirroring the LuaJIT skip.

## Known skeleton limitations (documented, not silent)

- The normative spec for this backend is `docs/spec-v1.1.md` (§JVM value
  mapping / §JVM backend contract); `docs/spec-v1.2.md` is a future draft
  whose grammar is not normative here (e.g. its grammar forbids
  module-level statements while the current checker accepts them and the
  backend implements their load-time semantics). The backend cites
  spec-v1.1 only.
- Multi-module JVM projects are rejected at the backend: any import other
  than `std/console` is E6000 at the import statement (the imported
  module's require-time side effects have no skeleton JVM equivalent), and
  class-name collisions between modules are E6000 — never a silent
  artifact overwrite.
- Module-level forward references are rejected: Java's
  illegal-forward-reference rule forbids `static { …x… }` /
  `static long b = c + 1L;` before `static long c;` is declared, and
  LuaJIT fails at runtime for the same programs (nil read), so these are
  E6000. Module-level calls that reach a function declared at or after
  the call site — directly (`f(); function f…`), transitively
  (`function f(): null { g(); } f(); function g…`), or from a field
  initializer (`let a: int = f()` before `function f`) — are also E6000:
  LuaJIT assigns each function value at its declaration point in source
  order and fails at load with a nil read, while Java hoists methods and
  would silently run them. Calls whose callee and transitive callees are
  all declared before the call site run at load with LuaJIT parity.
  Assignments behave the same way: a write to a later-declared
  function-local with no enclosing binding (`x = 5; let x: int = 1`
  inside a function) is E6000 (LuaJIT writes the enclosing scope; Java
  rejects the forward reference), while module-level writes to
  later-declared fields (JLS §8.3.3 forward-reference LHS exception) stay
  allowed — verified with real luajit runs. Function-body WRITES to a
  module field declared later than the function are also E6000 (in every
  position: statement, block, if/else branch, return value, call
  argument), not emitted as a static-field write: LuaJIT binds the
  pre-declaration write to the GLOBAL of the same name (the module-local
  does not exist when the function value is created), leaving the
  module-local untouched so later readers observe the initializer value,
  while a Java static-field write would pollute every later reader. The
  write-then-read shape is included — the function's own read observes
  the global write under LuaJIT, but the polluted Java field remains
  observable by later readers.
- Function-body access to a module field declared later is therefore
  rejected in every shape: a READ without a dominating write inside the
  function is E6000 (LuaJIT reads the global nil at call time and fails
  with E8001 while JVM would silently read the initialized static
  field), and a WRITE is E6000 outright (the divergence above). A
  function declared AFTER the field reads/writes the module-local
  upvalue with full parity — pinned by the cross-backend fixture
  `jvm-function-field-write-parity` (`let x: int = 1;` first, then
  `function f(): int { x = 5; return x; }` — both backends observe
  f() == 5 and x == 5). Dominance for reads is tracked along every
  execution path: a write in both branches of an if/else dominates; a
  write in a taken-only branch or inside a called function does not
  (conservative). Load-time (module-level) value uses
  of later-declared fields remain E6000 — Java's illegal-forward-reference
  rule rejects them and LuaJIT reads the not-yet-declared global value at
  load.
- Dead code after a statement that cannot complete normally (a `return`,
  or an `if`/`else` whose branches all cannot complete normally) is
  skipped, never emitted: LuaJIT never executes it and javac rejects it
  as unreachable (JLS §14.21), so skipping keeps the artifact valid Java
  (fixtures `jvm-dead-code-function`, `jvm-dead-code-block`,
  `jvm-dead-code-elseif-hoisted`).
- The typed boundary checks LuaJIT performs on null-typed values
  (`check_null` rejects the raw nil that `console.log` returns) are proven
  redundant by the JVM's static types and skipped, as the spec's JVM
  backend contract permits — see "Observable-behavior guarantees" above.
- An int literal outside the DEAL safe range raises E8004 at its point
  of use on the JVM backend (the literal is not a valid DEAL int):
  `9223372036854775807 % 2` raises E8004, while LuaJIT silently rounds
  the literal to a double first and computes 0, and
  `9007199254740992 === 9007199254740992` raises E8004 while LuaJIT
  compares the rounded doubles — LuaJIT number-representation artifacts
  the JVM backend refuses to reproduce rather than silently diverging.
  The boundary E8004 shapes (add/sub/pow/int()/literal at the return)
  match LuaJIT exactly and are pinned by cross-backend fixtures.
- String ordering follows Unicode scalar values (LuaJIT's UTF-8 bytewise
  order), implemented by the emitted `scalarCompare` code-point loop; the
  old UTF-16 `String.compareTo` ordering diverged for supplementary
  characters and was replaced.
- `--source-map` sidecars are a LuaJIT feature; the JVM path prints a
  warning when `--source-map` is explicitly requested instead of
  silently producing no sidecars. A `--dump-ir`-derived source-map flag
  (IR hardening enables source maps with dumps) does not print the
  warning — only the explicit request does.
- Production phase 4 (the `jvm` backend) writes the `.java` source but does
  not run `javac`/`java` — only the test harness compiles and executes the
  artifact. The generated artifact is a module class without a `main`; the
  conformance adapter compiles it together with a runner that auto-invokes
  the zero-arity exported functions in declaration order and prints
  non-null results, and initializes the module class via
  `Class.forName` when no function is auto-invoked so module-load-only
  fixtures still observe their load-time output. Module-level DEAL errors
  surface with the same `DEAL_ERROR_CODE: <code>` contract whether or not a
  zero-arity export exists: the runner unwraps
  `ExceptionInInitializerError` (a `LinkageError`, not a
  `RuntimeException`, raised when the first auto-invocation triggers class
  initialization) into the same handler.
  The cross-backend invocation contract is deliberately ASYMMETRIC: the
  JVM runner invokes zero-arity exports in declaration order and PRINTS
  each non-null result with Java's default formatting
  (`System.out.println(double)` prints `1.0`/`1.0E300`, not LuaJIT's
  `tostring` `1`/`1e+300`), while the Lua harness iterates `pairs()`
  (an unspecified order) and DISCARDS the results. Therefore fixtures
  with multiple zero-arity exports must not depend on cross-backend
  invocation order, result-value assertions (`expectedOutput` equal to
  an export's printed return value) are JVM-only, and cross-backend
  fixtures must route every observable output through `std/console` —
  never through an export's return value. Every current fixture has at
  most one zero-arity export, and every cross-backend observable output
  goes through `console.log`/`console.error`.
