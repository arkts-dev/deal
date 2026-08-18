# deal.codegen.jvm — JVM Backend (ISSUE-0091 skeleton, ISSUE-0092 while/template slice, ISSUE-0093 functions and direct calls slice, ISSUE-0094 primitive arrays slice)

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
| `int[]` / `number[]` / `string[]` / `boolean[]` | mutable wrapper classes `__IntArray` / `__NumberArray` / `__StringArray` / `__BooleanArray` holding a primitive Java array (the spec's "specialized primitive array wrapper") — literals `new __IntArray(new long[]{…})`, index reads via the emitted `__intArrayRead`-family helpers (typed read sites raise E8001 past the end; `===`/`!==` operand positions box the read — `__intArrayReadBoxed` family, null past the end — and compute the nil comparison per the read-site contract), element writes via the `__intArrayWrite`-family helpers, `.length` via `((long) xs.data.length)`. The wrapper identity is stable across appends (a write at `i == length` grows the wrapped storage in place), so aliases observe every write exactly like LuaJIT's shared 1-based table |

Out of scope (rejected with a backend `E6000` diagnostic, never silently
miscompiled): modules (any import other than `std/console`), classes, tables,
nullables, nullable arrays, arrays of nullable elements, nested
(multi-dimensional) arrays, class arrays, function arrays, function types,
stdlib modules, async/await, for/for-of loops, try/throw, host ABI,
`@jsonable`.

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
- **Shadow chains keep every still-visible emitted name reserved.**
  Parameters and function-body top-level `let`s share ONE scope map,
  so `declareLocal`'s `put` for a body-top `let` overwrites the
  parameter's key of the same DEAL name. The parameter's emitted name
  is still in Java scope for the rest of the method (JLS §6.4: an
  inner block may not redeclare a method parameter), so it must stay
  reserved for collision purposes even though no scope map holds it
  any more — the backend keeps a per-function set of every emitted
  binding name (parameters + locals, pushed with the parameter scope
  and popped with it) that `emittedNameVisible` consults in addition
  to the current scope values (ISSUE-0093 rework). Before the fix the
  triple-deep chain `let g: int = 1; function f(g: int): int { let g:
  int = g + 10; { let g: int = g + 1; } return g; }` emitted
  `long g$1 = intAdd(g$2, 1L);` in the inner block — over the
  parameter's `long g$1` — and javac rejected the artifact after the
  CLI reported success ("variable g$1 is already defined in method
  f(long)"); the no-field variant reused the parameter's plain `g`
  the same way. Both chains now emit one distinct name per binding
  (`g$1`/`g$2`/`g$3` with the field, `g`/`g$1`/`g$2` without) and run
  to the LuaJIT values, pinned by the fixtures
  `jvm-fn-param-shadow-let-block` /
  `jvm-fn-param-shadow-let-block-nofield` and
  `JvmBackendTest.testParameterShadowing`.
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

## Review evidence: primitive arrays (ISSUE-0094)

Every supported primitive-array form and runtime check, with the test
covering it. All fixture evidence below runs through the real frontend →
real `JvmBackend` codegen → `javac` subprocess → `java` subprocess
executing the emitted artifact (`test/conformance/fixtures/jvm-arrays-slice.json`,
37 fixtures: 16 JVM-only + 21 cross-backend parity fixtures that also run
under LuaJIT as the reference; `test/BackendConformanceTest` fails a
fixture whose codegen or JVM execution is bypassed):

- **`int[]` array literal + index read + `.length`** —
  `jvm-arr-literal-read-length` (xs[0]+xs[1]+xs[2]+xs.length = 63),
  IR-pinned (`array : [int]`, `index [] : int`, `member .length : int`);
  unit emission assertions in `JvmBackendTest.testPrimitiveArrays`
  (`new __IntArray(new long[]{10L, 20L})`, `__intArrayRead(xs, 0L)`,
  `((long) xs.data.length)`).
- **`number[]` / `string[]` / `boolean[]` literals + reads + writes +
  length** — `jvm-arr-four-element-types` (all three element types in
  one module: number arithmetic on elements, string concatenation into
  an element, boolean copy, length reads).
- **Element write into an existing index** — `jvm-arr-write-readback`
  (xs[1] = 99; xs[2] = xs[0] + xs[1]); `JvmBackendTest.testPrimitiveArrays`
  asserts the `__intArrayWrite(xs, 1L, 99L);` emission.
- **Append at `i === length`** (spec §Array writes rule 4) —
  `jvm-arr-append-at-length` (empty literal grown via the
  `xs[xs.length] = v` idiom twice and a plain index-equals-length write);
  `JvmBackendTest.testPrimitiveArrays` pins the `i == (long) a.data.length`
  growth emission.
- **Alias mutation with stable wrapper identity across appends** —
  `jvm-arr-alias-mutation-append` (b = a; b[1] = 9; b[3] = 4 observed
  through a).
- **Arrays through function parameters and returns** —
  `jvm-arr-function-param-return` (fill(xs, v) mutates the caller's
  array and returns it; total(xs) sums it).
- **Module-level array fields** — `JvmBackendTest.testPrimitiveArrays`
  (load-time initialization and mutation, `g[0] = 5` at module level).
- **Negative index read → E8002** (LuaJIT's emitted negative-index
  check) — `jvm-arr-negative-read-e8002` (JVM-only) and
  `jvm-arr-negative-read-parity` (cross-backend: the same E8002 under
  real luajit); `JvmBackendTest.testArrayRuntimeErrorCodes`.
- **Read past the end → E8001 "expected int, got null" at typed
  read sites** (spec §Bounds and nil behavior: LuaJIT reads nil there
  and the read site's typed boundary fails with that shape — a
  primitive Java array cannot yield nil, so the JVM read raises the
  boundary failure directly) — `jvm-arr-oob-read-e8001`;
  `JvmBackendTest.testArrayRuntimeErrorCodes` also pins the boolean
  element spelling ("expected boolean, got null").
- **`===` / `!==` operand positions past the end → nil comparison
  semantics, never E8001** (the comparison operand's read site applies
  no typed boundary, so LuaJIT computes `nil === v` false /
  `nil !== v` true / `nil === nil` true) — boxed reads
  (`__intArrayReadBoxed` family: null past the end, E8002 for a
  negative index, which LuaJIT raises unconditionally) plus nil-aware
  comparison expressions, with every effectful operand materialized so
  a later operand always evaluates: `jvm-arr-cmp-past-end-parity`
  (cross-backend, all four element types),
  `jvm-arr-cmp-negative-index-parity`,
  `JvmBackendTest.testArrayReadComparisonNilSemantics` (four element
  types, value-operand evaluation in both directions, read-vs-read nil
  semantics, the E8002 pin, and `__intArrayReadBoxed` emission
  assertions).
- **Both-reads comparison evaluation position** (spec §Operational
  semantics rules 1+3: the left read evaluates completely — receiver,
  index, and its boxed helper call — before the right operand's first
  evaluation; a left read's E8002 always raises before any
  right-operand hoisted side effect, and in-bounds both-reads
  comparisons keep strict left-to-right order with hoisted
  right-side effects) — the left read's boxed helper pre-statement is
  anchored immediately after the left receiver/index hoisted
  statements and before the right operands are even emitted (appending
  both helper pre-statements after one four-operand
  `emitOperandsInOrder` call placed the right operand's hoisted
  println first: `ys[-1] === makeArr("made", console.log("h"))[0]`
  printed "h" before the E8002 while LuaJIT raises with no output):
  `jvm-arr-cmp-both-reads-neg-order-parity` (cross-backend: only
  `DEAL_ERROR_CODE: E8002`, no "h"/"made", exit 1),
  `jvm-arr-cmp-both-reads-index-hoist-parity` (same with the right
  read's index operand hoisting),
  `jvm-arr-cmp-both-reads-inbounds-order-parity` (cross-backend
  positive control: h, made, h2, made2, eq-ok), all pinned with
  `expectedNotOutput` negative stdout assertions +
  `JvmBackendTest.testArrayReadComparisonBothReadsOrder`
  (runtime and emission assertions: the left read's helper call lands
  before the right operand's hoisted println).
- **Plain-value-left comparison evaluation order** (spec §Operational
  semantics: the left operand evaluates completely before the right
  operand's first evaluation even when the right operand carries the
  nil semantics — a primitive array read or a nil-aware `&&`/`||`
  result): a plain effectful left operand is materialized into a
  pre-statement immediately after its emission and BEFORE the right
  operand is even emitted, so the right read's boxed helper call
  (and its operands' hoisted side effects) can never run first — a
  late materialization ran `mark("lhs", 5) === xs[-1]`'s right read
  E8002 before printing "lhs" (LuaJIT prints "lhs" first) and
  `(9007199254740991 + 1) === xs[-1]`'s read E8002 where LuaJIT
  raises the left arithmetic's E8004 first; a pure literal left
  operand is never materialized. Pinned by
  `jvm-arr-cmp-lhs-effect-before-read-error-parity` (cross-backend:
  "lhs" then E8002, exit 1, int),
  `jvm-arr-cmp-lhs-effect-boolean-parity` (boolean),
  `jvm-arr-cmp-lhs-effect-number-parity` (number),
  `jvm-arr-cmp-lhs-effect-string-parity` (string),
  `jvm-arr-cmp-lhs-effect-nilaware-right-parity` (a nil-aware
  `&&` right operand), `jvm-arr-cmp-both-raise-precedence-parity`
  (cross-backend: E8004, never E8002, exit 1),
  `jvm-arr-cmp-lhs-effect-inbounds-order-parity` (cross-backend
  positive control: lhs, h, i, eq-ok),
  `jvm-arr-cmp-lhs-effect-read-order` (JVM-only: the spec's
  receiver-before-index order lhs, made, idx against an effectful
  right receiver and index — LuaJIT emits the index first, the
  documented read divergence below) +
  `JvmBackendTest.testArrayReadComparisonPlainLeftOperandOrder`
  (runtime shapes for all four element types, the nil-aware right,
  the both-raise E8004 precedence, the in-bounds positive control,
  the JVM-only receiver/index order, and emission assertions: the
  left materialization lands before its right read's boxed helper
  call, a pure literal is never materialized, never a lambda).
- **Discarded standalone array read past the end → no E8001** (the
  spec read-site contract applies no typed boundary to a discarded
  value: LuaJIT drops the emitted read's nil, so the JVM discards a
  boxed read instead of raising) —
  `jvm-arr-discard-past-end-parity` (cross-backend, all four element
  types) + `JvmBackendTest.testArrayBoundaryLessReadPositions`
  (runtime shape, the E8002 a discarded negative-index read still
  raises — LuaJIT raises it unconditionally at the read — and the
  boxed-discard emission shape `__intArrayReadBoxed(xs, 99L)` +
  `java.lang.Long __ignored`).
- **`!` operand past the end → Lua's `not nil` coercion** (`!bs[99]`
  computes true — LuaJIT's `not` coerces the operand's nil instead of
  raising) — the JVM emits `(x == null || !x.booleanValue())` over a
  boxed read temporary: `jvm-arr-not-read-parity` (cross-backend:
  not-coerced-true, not-taken, not-niland — the same coercion over a
  nil-aware `&&` result — plus the in-bounds negative control pinned
  with expectedNotOutput) +
  `JvmBackendTest.testArrayBoundaryLessReadPositions`.
- **`&&` / `||` operands past the end → Lua's nil truthiness** (nil
  is falsy: `bs[99] || true` computes true, `true || read` and
  `false && read` skip the right operand entirely — its hoisted print
  and pick label never run, pinned with expectedNotOutput) — the JVM
  lowers the operands to boxed `java.lang.Boolean` temporaries
  (null = the Lua nil) with the same truthiness guards, and the result
  nil (`bs[99] && true` → nil, `false || read` → nil) fails at a typed
  boolean boundary (declaration initializer, if/while condition,
  return, call argument, boolean array element write — the array
  write's element check raises E8001 on both backends — and an
  identifier assignment raises the same E8001 per the spec read-site
  contract, where LuaJIT's emitted identifier assignment stores the
  nil unchecked; see Known skeleton limitations) exactly
  where LuaJIT's check_boolean(nil) fails — E8001 "expected boolean,
  got null" via the `booleanNotNull` conversion helper:
  `jvm-arr-shortcircuit-read-parity` (cross-backend),
  `jvm-arr-and-boundary-parity` (cross-backend E8001),
  `JvmBackendTest.testArrayBoundaryLessReadPositions` (runtime and
  emission assertions, never a lambda).
- **Negative index write → E8002** (spec §Array writes rule 5) —
  `jvm-arr-negative-write-e8002`; `JvmBackendTest.testArrayRuntimeErrorCodes`.
- **Gap write `i > length` → E8002** (spec §Array writes rule 5) —
  `jvm-arr-gap-write-e8002` (int), plus number[] and int[] gap-write
  pins in `JvmBackendTest.testArrayRuntimeErrorCodes`.
- **Element value check (assignment value check)** — int elements route
  through the emitted `checkInt` (`v = checkInt(v);` emission assertion),
  so an out-of-safe-range int element write raises E8004 exactly like
  LuaJIT's `check_int` at the write site:
  `JvmBackendTest.testArrayRuntimeErrorCodes` (literal and the
  write-check-after-RHS-evaluation shape `xs[4] = 9223372036854775807` —
  the RHS value check fires before the bounds check, per spec
  §Operational semantics rule 3: "Evaluate assignment RHS before LHS
  write check"). The number/string/boolean element checks are proven
  redundant by the JVM static type system (spec-v1.1 §JVM backend
  contract permits proving typed-boundary checks redundant).
- **Read evaluation order** (spec §Operational semantics rule 1:
  receiver before index) — `jvm-arr-eval-order-read` (get prints "arr",
  mark prints "idx", the read returns 300).
- **Write evaluation order** (spec §Operational semantics rule 3:
  receiver, index, RHS before the LHS write check) —
  `jvm-arr-eval-order-write` ("arr", "idx", "rhs", then the written
  value), and `jvm-arr-eval-order-hoisted` +
  `JvmBackendTest.testArrayEvaluationOrderHoisted` for hoisted
  null-typed side effects: the index operand's hoisted print runs
  before its inline call, which is materialized into `__t0` ahead of
  the RHS's hoisted print — never a lambda, never an inverted print
  order (emission assertion: `__intArrayWrite(xs, __t0, pick("value",
  null));`).
- **Side-effecting receiver + hoisting index + hoisting RHS write
  order** — each materialized operand is anchored at the earliest hoist
  start among the operands AFTER it, so a side-effecting receiver's
  inline call runs before the index operand's hoisted print
  (`getArr("a", xs)[pick("i", console.log("b"))] = pick("v",
  console.log("c"))` prints a, b, i, c, v — anchoring at the last
  hoisting operand's start printed b, a, i, c, v):
  `jvm-arr-eval-order-write-hoisted-parity` (cross-backend, LuaJIT
  agrees) + `JvmBackendTest.testArrayEvalOrderSideEffectingReceiver`
  (runtime and emission assertions: `__t1 = getArr("a", xs);` before
  the hoisted println).
- **Array-literal element evaluation order with a side-effecting first
  element and hoisting later elements** — the same earliest-hoist-start
  anchoring holds for literals (`[getA("a"), pick("i",
  console.log("b")), pick("v", console.log("c"))]` prints a, b, i, c,
  v): `jvm-arr-literal-eval-order-hoisted-parity` (cross-backend,
  LuaJIT agrees) + `JvmBackendTest.testArrayEvalOrderSideEffectingReceiver`.
- **Cross-backend parity against LuaJIT** —
  `jvm-arr-literal-read-write-length-parity` (int[] literal, writes,
  the append idiom, .length, alias mutation — identical observable
  output under real luajit and the emitted JVM artifact),
  `jvm-arr-multi-type-parity` (number[]/string[]/boolean[]),
  `jvm-arr-negative-read-parity` (E8002 on both),
  `jvm-arr-eval-order-write-hoisted-parity` and
  `jvm-arr-literal-eval-order-hoisted-parity` (evaluation order with a
  side-effecting receiver/first element plus hoisting operands),
  `jvm-arr-cmp-past-end-parity` (nil comparison semantics in all four
  element types), `jvm-arr-cmp-negative-index-parity` (E8002 in a
  comparison operand on both), the three both-reads comparison
  fixtures listed above (`jvm-arr-cmp-both-reads-neg-order-parity`,
  `jvm-arr-cmp-both-reads-index-hoist-parity`,
  `jvm-arr-cmp-both-reads-inbounds-order-parity`),
  `jvm-arr-discard-past-end-parity` (discarded standalone reads,
  no E8001 on both), `jvm-arr-not-read-parity` (Lua's `not nil`
  coercion on both), `jvm-arr-shortcircuit-read-parity` (nil
  truthiness in `&&`/`||` operands plus the skipped right operands on
  both), `jvm-arr-and-boundary-parity` (the result nil failing a
  typed boolean boundary with E8001 on both), and the seven
  plain-value-left comparison fixtures listed above
  (`jvm-arr-cmp-lhs-effect-before-read-error-parity`,
  `jvm-arr-cmp-lhs-effect-boolean-parity`,
  `jvm-arr-cmp-lhs-effect-number-parity`,
  `jvm-arr-cmp-lhs-effect-string-parity`,
  `jvm-arr-cmp-lhs-effect-nilaware-right-parity`,
  `jvm-arr-cmp-both-raise-precedence-parity`,
  `jvm-arr-cmp-lhs-effect-inbounds-order-parity`).
- **Frontend compile-error gates rejected before any backend** —
  `jvm-arr-frontend-reject-index-type` (E3007 non-int index) and
  `jvm-arr-frontend-reject-length-write` (E3017 read-only `.length`
  assignment); codegen/javac/java never invoked for either.
- **Out-of-slice array shapes → E6000, never silently miscompiled** —
  `JvmBackendTest.testUnsupportedConstructsRejected` +
  `testArrayUnsupportedElementTypesRejected`: nested arrays (`int[][]`),
  arrays of nullable elements (`(int | null)[]`), nullable arrays
  (`int[] | null`), function arrays, class arrays, table indexing.
- **Use-before-declaration guards walk array value positions** —
  `JvmBackendTest.testArrayUseBeforeDeclarationGuards`: a module-level
  call whose (transitive) body reads a later-declared field through an
  index, an array literal, or a `.length` read is E6000 (LuaJIT reads
  the global nil at load; Java would read the default wrapper); a
  function declared before a field reading/writing it through an index
  or an append is E6000 (write-dominance analysis); an assignment whose
  index expression references a later-declared local is E6000; an index
  write whose array identifier is a later-declared local is E6000 — and
  the declared-first shape stays clean with full parity.

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
  - JVM: `test/conformance/fixtures/jvm-functions-slice.json` — fifteen
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
    a parameter that itself shadows a module field; the triple-deep
    shadow chains — field → parameter → body-top let → inner-block
    let, with and without the module field — where every binding gets
    a distinct emitted Java name, pinning the JLS §6.4
    inner-block-redeclares-a-parameter regression that used to make
    javac reject the artifact after the CLI reported success), and two
    frontend compile-error gates rejected before any backend (E3009
    arity mismatch, E5001 argument-type mismatch).
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

- **Array reads past the end raise E8001 at typed read sites; the
  boundary-less positions compute Lua's nil semantics instead.** LuaJIT
  reads nil past the end and the *read site's* typed boundary raises
  the error (E8001 "expected int"); the JVM typed read helper raises
  "expected <elementType>, got null" directly because a primitive Java
  array cannot yield nil. The code (E8001) matches the boundary
  failure the spec's negative test documents (`let x: int = xs[99]; //
  runtime: nil is not int`); the message spelling ("got null" vs
  LuaJIT's plain "expected int") differs by design and is pinned
  JVM-side. The boundary-less positions are different: spec §Bounds and
  nil behavior applies no typed boundary to a discarded read, a
  `===`/`!==` operand, a `!` operand, or a `&&`/`||` operand, so
  LuaJIT computes on the nil value and the JVM must not raise E8001
  there. A discarded standalone read drops the boxed value (no E8001);
  `===`/`!==` compute the nil comparison (`xs[99] === 5` → false,
  `xs[99] !== 5` → true, `xs[99] === xs[99]` → true); `!read` coerces
  `not nil` → true (`(x == null || !x.booleanValue())`); and
  `&&`/`||` operands coerce the nil to falsy with the result following
  Lua's and/or — `nil or true` → true, `nil and true` → nil — carried
  in boxed `java.lang.Boolean` temporaries whose null then fails at a
  typed boolean boundary (E8001 "expected boolean, got null") exactly
  where LuaJIT's check_boolean(nil) fails. All these positions emit
  nullable boxed reads (`__intArrayReadBoxed` family: null past the
  end, still E8002 for a negative index — LuaJIT raises that
  unconditionally), and every effectful operand is materialized into a
  pre-statement temporary first, so a later operand always evaluates
  (LuaJIT evaluates both `===` operands strictly, and skips a
  `&&`/`||` right operand only when the left operand already decides
  the result) — never skipped by a Java short-circuit. One residual
  divergence stays spec-conformant: an identifier-assignment RHS read
  (`b = bs[99]`, `b = bs[99] && true`) is a typed position under the
  spec read-site contract (the read result is checked against the
  target type — boolean), so the JVM raises E8001 there, while
  LuaJIT's emitted identifier assignment performs no value check and
  silently stores the nil (later reads of `b` then coerce or raise
  against the nil). A boolean literal element (`[bs[99] && true]`)
  raises E8001 at the JVM construction, where LuaJIT's check_array
  reports E8003 for the nil element — both raise, with the code
  differing because the JVM wrapper cannot store a nil element. The
  JVM follows the spec; the LuaJIT difference is
  listed rather than matched because the spec is normative. Pinned by
  `jvm-arr-cmp-past-end-parity` (cross-backend, all four element
  types), `jvm-arr-cmp-negative-index-parity`,
  `jvm-arr-discard-past-end-parity`, `jvm-arr-not-read-parity`,
  `jvm-arr-shortcircuit-read-parity`, `jvm-arr-and-boundary-parity`
  (all cross-backend),
  `jvm-arr-cmp-lhs-effect-before-read-error-parity`,
  `jvm-arr-cmp-lhs-effect-boolean-parity`,
  `jvm-arr-cmp-lhs-effect-number-parity`,
  `jvm-arr-cmp-lhs-effect-string-parity`,
  `jvm-arr-cmp-lhs-effect-nilaware-right-parity`,
  `jvm-arr-cmp-both-raise-precedence-parity`,
  `jvm-arr-cmp-lhs-effect-inbounds-order-parity` (all cross-backend —
  a plain value LEFT operand evaluates completely before a right
  read's E8002 or its own error raises),
  `JvmBackendTest.testArrayReadComparisonNilSemantics`,
  `JvmBackendTest.testArrayReadComparisonPlainLeftOperandOrder`,
  `JvmBackendTest.testArrayBoundaryLessReadPositions`.
- **Read evaluation order: receiver before index (spec) — LuaJIT
  evaluates the index first.** Spec §Operational semantics rule 1
  ("Evaluate receiver expression before member/index/call arguments")
  is the only authority the issue adopts, and the JVM follows it
  (`get("arr", xs)[mark("idx", 2)]` prints arr, idx). LuaJIT's emitted
  read evaluates the index expression before the receiver (its
  check_int(index) closure statement runs before the table lookup), so
  the same program prints idx, arr under real luajit — an observable
  divergence for side-effecting receiver/index expressions. The JVM
  side is spec-conformant and pinned by `jvm-arr-eval-order-read`; the
  LuaJIT difference is listed here rather than matched because the spec
  is normative.
- **The element value check runs after the RHS evaluates.** Spec
  §Operational semantics rule 3 ("Evaluate assignment RHS before LHS
  write check") governs: the JVM emits the write as a helper call whose
  Java arguments (receiver, index, RHS) evaluate left to right before
  the helper performs the bounds check, the int element check
  (`checkInt`), and the store. LuaJIT's emission performs the bounds
  check before evaluating the RHS — an error-code divergence in the
  corner where the RHS itself raises (`xs[4] = 9223372036854775807`:
  JVM reports the RHS's E8004, LuaJIT reports the E8002 bounds error
  first). The JVM follows the normative spec order and pins it
  (`JvmBackendTest.testArrayRuntimeErrorCodes`,
  `jvm-arr-eval-order-write`, `jvm-arr-eval-order-hoisted`).
- **Array equality stays out of scope.** `xs === ys` on two arrays is
  reference identity under LuaJIT and would be `==` on the wrappers
  under JVM, but the slice scope lists only literals, indexing,
  assignment, and length — array comparisons are E6000 rather than a
  silent unlisted feature.

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
