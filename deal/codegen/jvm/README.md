# deal.codegen.jvm — JVM Backend Skeleton (ISSUE-0091)

A small but real end-to-end JVM backend for the DEAL compiler. It walks the
typed AST (the compiler's IR — `deal-compiler-architecture-v1`) and emits a
self-contained Java class whose static methods implement the module's
functions. The Java source is a real JVM artifact: it is compiled by
`javac` and executed by `java` in a subprocess, both in production
(`CompilationOrchestrator` phase 4) and in the conformance harness
(`test/BackendConformanceTest.java`).

## Skeleton scope

Supported (real semantics, spec JVM value mapping):

| DEAL | JVM representation |
|---|---|
| `int` | primitive `long` (checked arithmetic: E8004 overflow, E8005 div-by-zero, E8006 negative exponent) |
| `number` | primitive `double` (Lua-style floored `%` via `numMod`) |
| `boolean` | primitive `boolean` |
| `string` | `java.lang.String` (`===` via `equals`; ordering via `compareTo`) |
| `null` | `void` returns / `Void` locals and params |
| functions | `static` methods of the generated module class |
| `let` locals / module fields | locals (shadowing disambiguated `$n`) / `static` fields |
| `if`/`else if`/`else`, `return`, assignment, direct calls | plain Java control flow |
| `int()` / `number()` intrinsics | `intFromNumber` / `numberFromInt` helpers (E8001/E8004) |
| `import * as c from "std/console"` | `c.log` → `System.out.println`, `c.error` → `System.err.println` |

Out of scope (rejected with a backend `E6000` diagnostic, never silently
miscompiled): modules (imports other than `std/console`), classes, arrays,
tables, nullables, function types, stdlib modules, async/await, loops,
try/throw, host ABI, `@jsonable`, template literals, for-of.

The JVM's static type system proves typed boundaries redundant, which the
spec explicitly permits (`docs/spec-v1.1.md` §JVM backend contract: "The JVM
backend may use JVM primitive types, final classes, verifier-checked
bytecode, method signatures, and JIT optimization to prove typed-boundary
checks redundant").

## Review evidence: backend-selection seam

- **Selection seam** — `deal/codegen/Backend.java` (`LUAJIT`, `JVM`,
  `fromCliName`). `CompilationOrchestrator` takes a `Backend` in the new
  9-arg constructor; every pre-existing constructor delegates with
  `Backend.LUAJIT` (`deal/module/CompilationOrchestrator.java`). The CLI
  selects it via `--backend <lua|jvm>` (`deal/Main.java`); the project
  manifest selects it via `deal.json`'s `"backend"` field
  (`deal/module/DealConfig.java` now accepts `"jvm"`). LuaJIT remains the
  default everywhere: no flag, no manifest field, and no constructor
  argument all select it.

- **Lua use site** — `CompilationOrchestrator.codegenLuaModule()`: the
  pre-ISSUE-0091 `LuaBackend.generateToFile`/`generateWithImports` emission
  plus `copyRuntimeLibrary`/`copyStdlibModules`, byte-for-byte unchanged,
  dispatched only when `backend == Backend.LUAJIT`.

- **JVM use site** — `CompilationOrchestrator.codegenJvmModule()`: calls
  `JvmBackend.generate(...)`, merges E6000 diagnostics into the standard
  error report (no artifact written on error), and writes
  `<ClassName>.java`. The conformance use site is
  `test/BackendConformanceTest.runJvmAssertions()`: real `JvmBackend`
  codegen → `javac` subprocess (asserts `.class` artifacts exist) →
  `java` subprocess → asserts stdout/error/exit code.

- **Tests proving both** —
  - LuaJIT: the full pre-existing suite (`test/LuaBackendTest`,
    `test/LuaBackendIntegrationTest`, `test/ConformanceTest`,
    `test/conformance/fixtures/*.json` `backends: ["luajit"]`) stays green
    and unmodified.
  - JVM: `test/conformance/fixtures/jvm-skeleton.json` — five JVM-only
    fixtures (literals/output, int arithmetic, local variables with
    shadowing, if/else, and a frontend compile-error rejected before the
    backend) run end-to-end under `test/BackendConformanceTest`.
  - Seam: `test/JvmBackendTest.java` — identifier translation, emission,
    E6000 rejection, runtime error codes via `javac`+`java`, the
    orchestrator JVM path (artifact exists, compiles, no `.lua`/runtime
    copied), the orchestrator default staying LuaJIT, `DealConfig` and CLI
    backend selection.

## Tests

```bash
./run_tests.sh
```

runs the whole suite. Targeted:

```bash
javac --release 25 -d build deal/codegen/jvm/*.java deal/codegen/*.java \
  deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java \
  deal/parser/*.java deal/checker/*.java deal/ir/*.java deal/module/*.java \
  deal/codegen/lua/*.java deal/Main.java test/*.java
java -ea -cp build deal.test.JvmBackendTest            # unit + seam tests
java -ea -cp build deal.test.BackendConformanceTest    # JVM + LuaJIT fixtures
```

The JVM fixtures require `javac` and `java` on `PATH` (present in any JDK
that builds this repository); when absent, the JVM runtime fixtures are
skipped, mirroring the LuaJIT skip.

## Known skeleton limitations (documented, not silent)

- Multi-module JVM projects are rejected at the backend (E6000 on any
  import other than `std/console`); the orchestrator reports the
  diagnostics through the standard path.
- Module-level `static` field initializers may not reference later-declared
  module fields (Java forward-reference rule); the checker resolves module
  `let`s sequentially, so such programs are already E2001 errors.
- String ordering uses UTF-16 code-unit order (`String.compareTo`); LuaJIT
  orders bytewise. Identical for ASCII.
- `--source-map` sidecars are a LuaJIT feature; the JVM path ignores them.
- The generated artifact is a module class without a `main`; the
  conformance adapter compiles it together with a runner that auto-invokes
  the zero-arity exported functions in declaration order and prints
  non-null results (mirroring the Lua harness's auto-invocation).
