# Tests

Test observable contracts independently across frontend, validation, Oracle and
production artifacts. Compilation is not execution. Keep regression details in
executable tests rather than policy inventories.

## Execution

- `tools/gate-manifest.sh` selects tests and coverage. Common JS/LuaJIT/JVM
  conformance, native O0/O2 conformance/accounting and compositions run without
  exclusions. Missing tools, hosts, exports, completions or runtime-requirement
  evidence fail. Preserve denominators and distinct consumers.
- Retain native normal/stress and plain/ASan/UBSan matrices. Keep complete deep
  JSON success/failure workloads; do not trade cases or depth for speed/timeouts.
  Batch preparation without dropping verdicts; cache keys cover inputs/config.
- Sanitizer retries apply only to explicit shadow-memory initialization failures,
  with child-only disabled ASLR when available. Silent signals, invariant faults,
  reports or workload output are not retryable; pin exactly one such execution.
- Coverage shares selection and enforces unrounded 85% line/75% branch minima.
  Stateful tests require `test/requirements.txt`; missing Hypothesis fails.
  Shrink valid typed traces without accepting source rejection as preservation.
- Production sources compile with `--release 21`; timing tests pin real phases,
  nesting/thread isolation and observer failure containment. Publication tests
  retain atomic replacement and recovery checks.

## Assertions

Pin values, effects, identity, exact failure rules/details/origins/causes/frames
and unchanged destinations on rejection. Native accounting credits only executed,
verdict-checked facts and case-local registry-valid arm evidence. Test E6005
origin/publication preservation. Exercise evaluator adapters with actual lowered
projects, not synthetic success claims.

Mutate typed/raw/text inputs independently with coherent fresh digests and
provider facts. Pin the expected first failure, not merely agreement between
surfaces. Preserve unrelated facts so a malformed producer cannot mask the
intended obligation. Pair rejection controls with independently executed positives.

Admit valid values before closure invalidation or fixture-only hostile mutation.
Preserve aliases, descriptors, unrelated slots and callable bodies/signatures;
assert rollback, repair and retry on the same identity. Never add production
mutation hooks or let an assertion mask a later parameter/element failure.

## Semantic coverage

- Binding-identity flow: RHS read refinements versus fixed writes, exact direct
  fields, shadowing, short-circuit/terminating guards and evaluation-order
  invalidation across calls, writes, await, functions, loops and catch snapshots.
  Test contextual parameters/literal arms with exact imported providers; retain
  declaration/result annotations and insufficient-context negatives.
- Void/never are result-only. Distinguish null/undefined/presence, recursive
  data/result descriptor positions and legal arrays of functions returning either.
  Never completion rejection stays catchable across direct/dynamic/adapted calls.
  Entry is exported synchronous zero-arity main():void. Invoke async exports
  through public completion consumers, not main alone.
- Closures/adapters: mutable/aggregate/dynamic recursion, fresh escaped cells,
  captured catch/iteration incarnations, once-only source capture, original
  assignment RHS identity and all declared parameter checks before effects,
  including arguments discarded by adapters. Execute segmented methods and
  partitioned aggregate classes, not just generated text.
- Arrays: persistent invariance, saved receivers, post-argument snapshots versus
  live traversal, growth/shrink/rewrites, sparse holes versus stored undefined,
  fresh shallow results, reached-only checks and source-index failures. Preserve
  mutation atomicity, malformed callable controls and same-carrier repair.
- Scalars: binary64 specials/signed zero; executed Node references for numeric
  spelling and string conversion, including radix rounding/ties/subnormals.
  Whole-string grammar, direct-only signatures, UTF-16/NUL/lone units, pinned
  Unicode casing and display-only replacement require independent controls.
- Lazy conditionals/coalescing/optional chains: once-only evaluation, skipped
  keys/arguments, selected-arm adapters, exact joins/providers, void discard and
  abrupt rollback. Pin structural ownership/order on all IR transports.
- Records/presence/keys: computed key-before-value effects, duplicates, numeric
  key order, declaration order and presence-only enumeration of hostile fields.
  Reflection controls retain same-storage aliases and reject before mutation.
- JSON: contextual targets versus assertions, exact recursive/private providers,
  artifact-local JVM schemas, Lua runtime load orders/own plans, extras-before-
  fields order, required absence, persistent decoded descriptors and deep
  success/failure followed by ordinary checks/stringify. Preserve one terminal
  per START with full contract/error facts and operation-owned coordinates in
  direct/dynamic/adapted/imported calls. Check reached-position failures before
  later cycles, original E8003 leaf causes and same-graph repair after collection.
- Host transport: signed wrappers versus raw callables/opaque nondata, complete
  unread graph admission, nonstring keys before hidden values, malformed bytes
  versus legal lone units, closed statuses and exactly-once await rejection.
  Test generated and deployed consumers separately without dummy companions.

TypeScript parser/grammar checks are separate from DEAL typing/runtime tests.
Keep representation-specific cases in focused host suites when JVM typed storage
cannot express them. Collector scaling tests measure bounded reference work,
not elapsed time; retain barriers, allocation marks, root rescans and poison.
Scratch examples belong in ignored `scratch-review`, not source directories.
