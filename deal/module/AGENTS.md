# Modules

Own resolution, dependency order, checked-project orchestration and identities.
Validate the complete graph before lowering/publication. Entry must export
synchronous zero-parameter main():void. TypeChecker alone owns definite returns.
Use active version constants in discovery and isolated constructors.

## Resolution

- Stdlib precedence: project-local, packaged resources/home, checkout fallback.
  Runtime resources never use project overrides. Callable modules are console,
  string, table, math and time; stray std/json declarations grant no builtin identity.
- Carry exact providers through imports, annotations, literals, calls and inferred
  carriers, including under namespace/parameter shadowing. Nominal identities name
  defining files; never retag across providers. Project indexes include declarations
  reached indirectly through fields/parameters/results.
- Record optional guards/completed chains separately; preserve callee providers and
  propagate result context through completed wrappers/conditional arms, not guards.
  Visit computed keys before values without giving keys the value's class provider.
- Comments grant no symbols/exports. Declaration files share identifier restrictions;
  report annotation-resolution errors. Class plans contain structural fields only,
  with no executable initializer/default dependencies. Preserve ordinary body
  dependencies, resource ranges and provider digests.

## Emission and timing

`ProductionProjectEmission` owns one lowering, closure guard and render per target.
JS stages per-unit CommonJS artifacts/maps; LuaJIT/JVM/LLVM stage one project artifact.
LLVM adds the closed runtime set without sidecars/tools. No retry, purpose flag or
AST fallback selects another renderer; returned paths belong to that invocation.

Stage artifacts/maps/dumps/runtime/stdlib in one publication transaction. Missing
resources publish nothing. Lua includes internal deal/json.lua. Source maps/comments
are default; --source-map selects no alternate behavior.

`CompilationTiming` observes monotonic current-thread phases, restores nested scopes
and contains observer RuntimeExceptions. Use shared phase names; codegen-total
includes lowering, excludes publication and never changes artifact bytes.
