# Code Generation

Render validated semantic IR only. `ProductionProjectEmission` lowers and
renders once per project/target; no AST fallback or alternate purpose route.
Renderers do not execute hosts or publish files. Unsupported facts fail with
their operation's origin, never a raw exception.

## Shared contracts

- Preserve evaluation order, lazy branches, admitted operand identities,
  boundaries, ABI and failure origins. Assignment yields the original RHS even
  when storage receives an adapter. Contextual adapters execute in their arm.
- Resolve layouts and contextual JSON schemas through exact defining providers,
  including private descendants. Same-named siblings stay distinct; ambiguous
  ownership rejects. Schema registration grants no construction/export authority.
- Construct from supplied fields with declaration-order checks; require every
  nonoptional field, including nullable/Error fields. Omitted optionals stay
  absent. No defaults hooks, generated JSON helpers or `C$fields` exports.
- Preserve persistent array descriptors, aliases and null/undefined/absence.
  Validate before mutation/publication. Array failures retain source-index
  E8003 and the unwrapped leaf cause; assertions own E8001. Rejected positions
  retain the original binary64 index, observed length and read/write constraint
  without replaying the check or reading storage.
- Pop validates before shrink; splice validates the entire removal range before
  shifting. Slice checks selected positions only and returns fresh shallow
  storage. Normalize/clamp numeric bounds before native casts.
- Strict element reads check bounds/presence before descriptor admission;
  missing yields E8002 even for undefined-admitting elements. Presence queries
  do not read/admit values. Sparse-capable carriers distinguish holes from
  stored undefined; native arrays remain dense.
- Join/search snapshot after argument effects and visit reached slots in order.
  Join preserves UTF-16; includes uses SameValueZero, indexOf strict equality.
  Expanded callback builtins render ordinary shared control/call/append IR,
  retaining all callback boundaries, including discarded adapter arguments.
- `OBJECT_KEYS` snapshots presence/order without consuming unrelated values.
  Use private declaration metadata; native presence-only readers accept NULL
  value outputs. Nullish projections preserve identity, not unchecked narrowing.

## Scalars and errors

Number conversion follows ECMAScript binary64 semantics, including signed zero,
NaN and infinities. `NUMBER_TO_STRING` uses shortest decimal spelling and the
specified exponent thresholds; finite JSON leaves reuse it. `STRING_TO_NUMBER`
validates the whole string, never ambient coercion/grammar. JVM shares
`NumberSemantics`; Lua preserves negative zero; native uses its private locale.
Numeric predicates do not coerce; rounding is binary64 with ties toward +infinity.
Direct-only selectors create no callable exports. Rejected numeric domains retain original binary64 operands and failed constraints at the producing check, independently of mutable messages.

String operations count UTF-16 units; clamp positions before casts and preserve
lone units. LastIndexOf treats NaN position as infinity. Casing uses pinned
Unicode 17.0 default mappings, expansions and Final_Sigma context, not ambient
locale/version tables. Regenerate data with `tools/generate-unicode-case.py`.

Throw/catch/rethrow preserves Error identity, origin, frames and causes. First
throw binds diagnostic code from the current name; later name writes do not
rewrite it. Each catch execution publishes a fresh captured cell. Operation
failures keep their own coordinates across direct/dynamic/adapted/imported calls;
no ambient caller-origin substitution.

## Artifacts

JS emits one CommonJS artifact and version-1 map per implementation unit;
LuaJIT/JVM/LLVM emit one project artifact. LLVM stages the closed
`NativeRuntime.FILES` set without invoking tools. Use the shared source-map
recorder; mapping must not move effects or alter runtime locations.
`--source-map` selects no alternate production behavior.

JS loop bodies and updates share the iteration's lexical cells, including
continue transfers. JVM generated temporaries use operation-specific names;
segmentation preserves captures, recursion, transfers and maps.
