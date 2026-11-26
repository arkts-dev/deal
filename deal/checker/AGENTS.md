# Checker

Own target-neutral resolution, typing, narrowing and deterministic diagnostics.
Publish exact checked facts; downstream phases must not rediscover meaning.

## Types and declarations

- Object literals require a contextual class/Record or unknown target; unknown
  constructs an unknown Record with recursive value context, never a nominal shape.
  Resolve exact declarations/providers through annotations, arrays, calls, fields
  and literals; same-spelled siblings
  never substitute. Reject duplicate decoded keys, nonstring computed Record keys
  and computed class keys. Quoted class keys use ordinary field checks.
- Resolve unused field annotations and reject duplicate declared fields before
  layout planning. Recursive classes are legal. Require all nonoptional literal
  fields, including nullable fields and Error name/message. Resolve field declarations
  for lookup/completeness, but check supplied expressions once in literal order;
  declaration order must not drive flow effects.
- Void and never are result-only; reject inferred result-only array elements before
  type construction. Functions returning either remain legal data. Never terminates
  only statement/return evaluation, not assignment compatibility or catch paths. TypeChecker alone owns definite
  returns: only void permits fallthrough; catch has no separate return obligation.
- Preserve const/declaration/import immutability without freezing referenced
  aggregates. Reject deletion of imported exports at the target, retaining the
  selected namespace import as declaration evidence. Let-bound functions remain mutable. Resolve signatures in enclosing
  scope, excluding shadowing parameters. Modules hoist; nested declarations follow
  declaration order and reject same-scope collisions.
- Reject reserved/eval/arguments/$ bindings and builtin rebinding everywhere,
  including imports, parameters, iteration/catch and declaration files. Contextual
  async/as/from/of identifiers and keyword properties remain legal. Comments grant
  no symbols/exports. Reject namespace/metadata data and array deletion.
- Null/undefined permissions are independent; reject duplicate union members before
  unknown absorption. Optional reads add undefined; presence never refines values.
  Unrefined unknown-Record reads remain unknown; string-Record reads infer string|undefined
  independently of destination context. Record variants are invariant; literal values
  and writes check the static value type, not the container type.

## Flow

Own facts by resolved binding identity, never name or structural equality. Scope
exit removes only exiting declarations. Initialization/assignment refines reads to
RHS types while preserving declared write envelopes and adapted signatures.
Record scope-correct reads in `CheckResult.narrowedReads`. Compound assignments
retain a separate checked read type from the declared storage envelope; check
arithmetic without destination context and invalidate facts as ordinary writes.

Build condition states after effects; check &&/|| RHS under the selected outcome
and reverse polarity for negation. Local const booleans retain outcome facts from
eligible guards and aliases; propagate subjects transitively and permanently remove
invalidated facts from every branch/exceptional snapshot. Join only paths reaching continuation.
Assignments invalidate binding and exceptional snapshots; await clears enclosing
function/loop/exceptional facts. Both function-entry paths isolate these states.
Catch entry uses saved entry facts with invalidations, not successful exit-type
equality. Merge only reaching try/catch exits; never reset successful assigned facts. Captured reads use declared types; calls preserve binding facts and
consuming boundaries revalidate stale values.

Direct fields and Record/array indexes use receiver-binding and declaration/literal
key identity, not aliases or nested paths. Publish storage read types separately
from refined results. Writes/deletions/calls clear property facts in evaluation order, including
exceptional snapshots. Diagnostic-only invalidation evidence uses the owning operation/range and exact subject; ambiguous joins and superseding effects drop attribution without changing flow. Write/delete context affects only the outer storage target;
receivers/keys/asserted descendants remain reads. Pre-scan loop conditions/bodies by
binding identity, excluding nested functions: assignments drop incoming binding facts,
await drops all, and properties never persist across iterations. Retain stable bindings;
while entries reapply evaluated guards without body-to-guard propagation. Merge
condition-false/break while exits; for-of merges post-iterable zero-entry, completion,
continue and break facts. Ignore unreachable tails when collecting exits, but still
check their syntax/types. For-of iterable effects precede the scan and follow ordinary invalidation.

Track exact instanceof targets and nullish/typeof polarity. Primitive typeof tests
select/remove nullable and optional members; unknown complements remain unknown. Never infer object/
function types from typeof or narrow/convert unknown equality. Catch bindings are
mutable unknowns with ordinary shadowing/capture rules; diagnostics anchor identifiers.

## Context

Function/arrow expression parameters may omit annotations only under an existing
positional signature. Resolve their bindings and exact providers before body checks;
never infer from bodies. Declaration parameters and all results stay explicit.
Unresolved-call alternatives consume already checked argument types; never recheck arguments or supply guessed contextual types for ranking. Lookup observations distinguish known later/out-of-scope declarations, imported members and metadata namespaces; unavailable imports are not inferred from a miss.
Array callback contexts derive from receiver/source; ordinary shorter-arity
adaptation remains. Void expression-bodied arrows require void or terminating never expressions.

Array literals retain the complete contextual array base, including readonly,
for empty and nonempty literals; nullish target members do not enter the literal's
type. Element contexts recurse independently, preserving shallow mutability. Under
unknown context, preserve homogeneous inference; empty/heterogeneous literals establish
unknown element storage after checking every element as data.
Assignment expressions keep the actual RHS type, not the storage envelope.
Schema-free writes are properties inside a Record, never Record-valued class fields
or array slots; every storage write requires data, excluding void/never.

Conditional arms receive result context and their own guard states. Without an
outer target, a literal arm uses its independently typed sibling's exact array/
class/Record target/provider. Static checking order must not change branch facts.
Ordinary joins, even under a destination context, allow identical/nullish types or
an actual unknown absorbing a non-void alternative, not arbitrary unions. Only
readonly-array targets select a contextual result base. Void joins require two void arms and
cannot become data. Contextual adapters belong to selected arms.

Coalescing checks the left without outer context and contextualizes only fallback.
Without an outer target, literal fallback uses the left's non-nullish array/class/
Record target/provider. Nullish-only left uses the right type; void rejects.
Optional chains check receivers/callees as reads, preserve exact declarations,
add undefined to non-void results and merge skipped-path invalidations. Reject async
optional calls; preserve direct-only/deletion restrictions.

## Builtins

Record, Promise, Object, Array, String, JSON, Number, Math, Date, console and undefined
are unshadowable. Resolve receiver intrinsics by checked type, not spelling;
class/Record callable fields and namespace exports retain ordinary resolution.
Call arity and parameter compatibility use E5xxx function-signature diagnostics;
preserve call facts, ranges and rejection order when categorizing failures.
Builtin operations are direct-call-only with exact signatures, no callable exports
or implicit extraction from unknown. Follow the specification/catalog for arities.

- String(number) and Number(string) lower to NUMBER_TO_STRING/STRING_TO_NUMBER;
  neither performs general coercion. Numeric predicates accept unknown without
  narrowing/coercion. Array.isArray accepts unknown and returns boolean without
  narrowing or supplying an array element context. Template interpolation admits checked string/number only.
- String length is read-only. Omitted numeric positions/bounds differ from explicit
  undefined; preserve each selector's result type and exact argument checks.
- Array push checks/adapts one element; pop returns optional element; splice takes
  two numbers; slice takes zero–two numbers; concat takes one array assignable to
  readonly E[]. Copy results are mutable with the receiver's element type.
- Map/from infer output from the mapper's explicit synchronous data result, never
  the destination/body. Predicates require synchronous boolean results with normal
  `(E, number, A)` adaptation with the receiver's writable/readonly A; from uses `(E, number)`. Find adds undefined;
  findIndex returns number, filter E[], some/every boolean. ToSorted requires one
  synchronous `(E, E) => number` comparator and preserves E[].
- Object.hasOwn tests presence without value refinement; class keys are literal
  declared fields. Object.keys takes one checked class/Record and returns string[].
- JSON.stringify accepts unknown without extraction context and returns
  string|undefined. JSON.parse takes one checked string and uses genuine enclosing
  context through parentheses/conditional arms, never assertions. No target means
  unknown. Composite operands (including templates, nominal tests, callees, receivers
and keys) do not inherit destination schemas. Function bodies reset outer expression context.

Validate the complete contextual JSON schema, including unread optional fields.
Follow exact defining declarations with identity-and-provider cycle guards; stop at
unknown/Record and reject function/void leaves. Fresh symbol wrappers cannot control
termination. No same-spelled sibling, private-layout relocation or exported factory
may replace the owning declaration. Test acceptance/rejection independently of emitters.
