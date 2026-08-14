# deal.codegen.jvm — JVM Backend Skeleton (ISSUE-0091)

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
| `int` | primitive `long` (checked arithmetic: E8004 overflow, E8005 div-by-zero, E8006 negative exponent; non-finite powers → E8001, matching LuaJIT's `check_int`) |
| `number` | primitive `double` (Lua-style floored `%` via `numMod`; literals that overflow to ±Infinity — e.g. checker-accepted `1e999` — render as `Double.POSITIVE_INFINITY`/`Double.NEGATIVE_INFINITY`, mirroring the Lua backend's `(1/0)`, so the artifact stays valid Java) |
| `boolean` | primitive `boolean` |
| `string` | `java.lang.String` (`===` via `equals`; ordering via the emitted `scalarCompare` helper — Unicode scalar-value order, matching LuaJIT's UTF-8 bytewise order including supplementary characters) |
| `null` | `void` returns / `Void` locals and params; `null === null` is `true`, `z === null`/`!==` emit Java `==`/`!=` (spec §Value equality) |
| functions | `static` methods of the generated module class |
| `let` locals / module fields | locals (shadowing disambiguated `$n`) / `static` fields |
| `if`/`else if`/`else`, `return`, assignment, direct calls | plain Java control flow |
| standalone expression statements (`x + 1;`) | lowered to a dummy-local declaration (`long __ignored = intAdd(x, 1L);`) so they are genuinely evaluated — an int overflow there is an observable E8004, as under LuaJIT |
| `int()` / `number()` intrinsics | `intFromNumber` / `numberFromInt` helpers (E8001/E8004) |
| `import * as c from "std/console"` | `c.log` → `System.out.println`, `c.error` → `System.err.println` |

Out of scope (rejected with a backend `E6000` diagnostic, never silently
miscompiled): modules (any import other than `std/console`), classes, arrays,
tables, nullables, function types, stdlib modules, async/await, loops,
try/throw, host ABI, `@jsonable`, template literals, for-of.

The JVM's static type system proves typed boundaries redundant, which the
spec explicitly permits (`docs/spec-v1.2.md` §JVM backend contract: "The JVM
backend may use JVM primitive types, final classes, verifier-checked
bytecode, method signatures, and JIT optimization to prove typed-boundary
checks redundant"). This is why `let z: null = console.log("x")` runs the
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
  preserves DEAL's left-to-right evaluation order.
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
  **Function-body reads of *declared* module fields are allowed even when
  the field is declared later** — the write/read asymmetry is
  intentional and documented: Java method bodies may legally reference
  later-declared static fields (JLS §8.3.3 covers only initializers),
  and the post-load semantics match LuaJIT — a function declared after
  the field reads the module-local upvalue (the initialized value,
  exactly what the static field holds after class init), and a function
  declared before the field reads the global, which a prior write
  established in the canonical `x = 5; return x;` shape (`function
  f(): int { x = 5; return x; } let x: int = 1;` — both backends
  observe 5, pinned by the cross-backend fixture
  `jvm-function-field-forward-read`; the write itself is the same
  upvalue/static-field write in both). The residual call-time
  divergence — a function declared before the field that reads it
  *without* a prior write: LuaJIT fails reading the global nil at call
  time while JVM reads the initialized field — is a documented
  limitation, not a silent miscompile of the supported write/read
  pattern.
- **Assignment targets get the same guard.** A write to a
  later-declared function-local with no enclosing binding
  (`x = 5; let x: int = 1` inside a function — in statement, block, `if`,
  `return x = 5;`, and `f(x = 5)` positions) is E6000, never a Java
  forward-reference artifact javac rejects after the CLI reported success
  (the checker resolves such targets contextually, so the module-scope
  symbol table reports null). Module-level writes to later-declared
  fields and function-body writes to a module field stay allowed and keep
  using the static field name (LuaJIT parity, verified with real luajit
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
  first interleaving shape is a cross-backend fixture).
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

## Review evidence: backend-selection seam

- **Selection seam** — `deal/codegen/Backend.java` (`LUAJIT`, `JVM`,
  `fromCliName`). `CompilationOrchestrator` takes a `Backend` in the new
  9-arg constructor; every pre-existing constructor delegates with
  `Backend.LUAJIT` (`deal/module/CompilationOrchestrator.java`). The CLI
  selects it via `--backend <lua|jvm>` (`deal/Main.java`); the project
  manifest selects it via `deal.json`'s `"backend"` field
  (`deal/module/DealConfig.java` accepts `"jvm"` and — for parity with the
  CLI alias — `"lua"`/`"luajit"`). LuaJIT remains the default everywhere:
  no flag, no manifest field, and no constructor argument all select it.

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
  - JVM: `test/conformance/fixtures/jvm-skeleton.json` — thirty-six
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
    dead else-if chain with a hoisted null-typed condition), and the
    function-body forward read of a later-declared module field
    (nine fixtures run under both backends as cross-backend parity))
    run end-to-end under `test/BackendConformanceTest`.
  - Seam: `test/JvmBackendTest.java` — identifier translation, collision-safe
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
    and module-level later-field write), function-body reads of
    later-declared module fields (allowed and executed via `javac`+`java`,
    incl. the reviewer's `x = 5; return x;` repro, initializer/condition/
    argument positions, and the untouched module-level load-time guard),
    dead-code skipping after non-completing statements (complete if/else,
    return-after-return, block-level dead let/expression, dead else-if
    chain with a hoisted null-typed condition, the no-over-skip open-if
    shape, and emission-level skip assertions), fixture-schema validation
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
  later-declared fields (JLS §8.3.3 forward-reference LHS exception) and
  function-body writes to a module field (LuaJIT's upvalue write) stay
  allowed — both verified with real luajit runs.
- Function-body reads of module fields declared later are allowed (legal
  Java forward references from method bodies; post-load parity with
  LuaJIT's upvalue read for functions declared after the field, and with
  the global read for the canonical write-then-read shape — both pinned by
  the cross-backend fixture `jvm-function-field-forward-read`). The
  residual call-time divergence is documented: a function declared before
  the field that reads it without a prior write reads the global nil under
  LuaJIT (call-time failure) but the initialized static field under JVM.
  Load-time (module-level) value uses of later-declared fields remain
  E6000 — Java's illegal-forward-reference rule rejects them and LuaJIT
  reads the not-yet-declared global value at load.
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
- String ordering follows Unicode scalar values (LuaJIT's UTF-8 bytewise
  order), implemented by the emitted `scalarCompare` code-point loop; the
  old UTF-16 `String.compareTo` ordering diverged for supplementary
  characters and was replaced.
- `--source-map` sidecars are a LuaJIT feature; the JVM path prints a
  warning when `--source-map` is requested instead of silently producing
  no sidecars.
- Production phase 4 (the `jvm` backend) writes the `.java` source but does
  not run `javac`/`java` — only the test harness compiles and executes the
  artifact. The generated artifact is a module class without a `main`; the
  conformance adapter compiles it together with a runner that auto-invokes
  the zero-arity exported functions in declaration order and prints
  non-null results (the Lua harness iterates `pairs()` — an unspecified
  order — so fixtures with multiple zero-arity exports must not depend on
  cross-backend invocation order), and initializes the module class via
  `Class.forName` when no function is auto-invoked so module-load-only
  fixtures still observe their load-time output. Module-level DEAL errors
  surface with the same `DEAL_ERROR_CODE: <code>` contract whether or not a
  zero-arity export exists: the runner unwraps
  `ExceptionInInitializerError` (a `LinkageError`, not a
  `RuntimeException`, raised when the first auto-invocation triggers class
  initialization) into the same handler.
