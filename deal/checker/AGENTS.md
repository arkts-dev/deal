# Checker

Own target-neutral resolution, typing, narrowing and deterministic diagnostics.
Publish exact checked facts; downstream phases must not rediscover meaning.

## Types and declarations

- Object literals require a real contextual class/Record target; nonempty fields
  do not authorize structural inference. Resolve exact declarations/providers
  through annotations, arrays, calls, fields and literals; same-spelled siblings
  never substitute. Reject duplicate decoded keys, nonstring computed Record keys
  and computed class keys. Quoted class keys use ordinary field checks.
- Resolve unused field annotations and reject duplicate declared fields before
  layout planning. Recursive classes are legal. Require all nonoptional literal
  fields, including nullable fields and Error name/message.
- Void and never are result-only; reject inferred result-only array elements before
  type construction. Functions returning either remain legal data. Never terminates
  only statement/return evaluation, not assignment compatibility or catch paths. TypeChecker alone owns definite
  returns: only void permits fallthrough; catch has no separate return obligation.
- Preserve const/declaration/import immutability without freezing referenced
  aggregates. Let-bound functions remain mutable. Resolve signatures in enclosing
  scope, excluding shadowing parameters. Modules hoist; nested declarations follow
  declaration order and reject same-scope collisions.
- Reject reserved/eval/arguments/$ bindings and builtin rebinding everywhere,
  including imports, parameters, iteration/catch and declaration files. Contextual
  async/as/from/of identifiers and keyword properties remain legal. Comments grant
  no symbols/exports. Reject namespace/metadata data and array deletion.
- Null/undefined permissions are independent; reject duplicate union members before
  unknown absorption. Optional reads add undefined; presence never refines values.
  Record reads remain unknown until checked extraction/supported refinement.

## Flow

Own facts by resolved binding identity, never name or structural equality. Scope
exit removes only exiting declarations. Initialization/assignment refines reads to
RHS types while preserving declared write envelopes and adapted signatures.
Record scope-correct reads in `CheckResult.narrowedReads`. Compound assignments
retain a separate checked read type from the declared storage envelope; check
arithmetic without destination context and invalidate facts as ordinary writes.

Build condition states after effects; check &&/|| RHS under the selected outcome
and reverse polarity for negation. Join only paths reaching continuation.
Assignments invalidate binding and exceptional snapshots; await clears enclosing
function/loop/exceptional facts. Both function-entry paths isolate these states.
Catch entry uses saved entry facts with invalidations, not successful exit-type
equality. Captured reads use declared types; calls preserve binding facts and
consuming boundaries revalidate stale values.

Direct declared fields use receiver-binding/field identity, not aliases or nested
paths. Writes/deletions/calls clear property facts in evaluation order, including
exceptional snapshots. Diagnostic-only invalidation evidence uses the owning operation/range and exact subject; ambiguous joins and superseding effects drop attribution without changing flow. Write/delete context affects only the outer storage target;
receivers/keys/asserted descendants remain reads. While entries reapply fresh guards;
body facts cannot cross back-edges or force stale first-entry admission.

Track exact instanceof targets and nullish/typeof polarity. Never infer object/
function types from typeof or narrow/convert unknown equality. Catch bindings are
mutable unknowns with ordinary shadowing/capture rules; diagnostics anchor identifiers.

## Context

Function/arrow expression parameters may omit annotations only under an existing
positional signature. Resolve their bindings and exact providers before body checks;
never infer from bodies. Declaration parameters and all results stay explicit.
Unresolved-call alternatives consume already checked argument types; never recheck arguments or supply guessed contextual types for ranking.
Array callback contexts derive from receiver/source; ordinary shorter-arity
adaptation remains. Void expression-bodied arrows require void or terminating never expressions.

Conditional arms receive result context and their own guard states. Without an
outer target, a literal arm uses its independently typed sibling's exact array/
class/Record target/provider. Static checking order must not change branch facts.
Context-free joins allow identical/nullish types or an actual unknown absorbing a
non-void alternative, not arbitrary unions. Void joins require two void arms and
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
Builtin operations are direct-call-only with exact signatures, no callable exports
or implicit extraction from unknown. Follow the specification/catalog for arities.

- String(number) and Number(string) lower to NUMBER_TO_STRING/STRING_TO_NUMBER;
  neither performs general coercion. Numeric predicates accept unknown without
  narrowing/coercion. Template interpolation admits checked string/number only.
- String length is read-only. Omitted numeric positions/bounds differ from explicit
  undefined; preserve each selector's result type and exact argument checks.
- Array push checks/adapts one element; pop returns optional element; splice takes
  two numbers; slice takes zero–two numbers; concat takes one identically typed
  array. Results preserve exact element types.
- Map/from infer output from the mapper's explicit synchronous data result, never
  the destination/body. Predicates require synchronous boolean results with normal
  `(E, number, E[])` adaptation; from uses `(E, number)`. Find adds undefined;
  findIndex returns number, filter E[], some/every boolean. ToSorted requires one
  synchronous `(E, E) => number` comparator and preserves E[].
- Object.hasOwn tests presence without value refinement; class keys are literal
  declared fields. Object.keys takes one checked class/Record and returns string[].
- JSON.stringify accepts unknown without extraction context and returns
  string|undefined. JSON.parse takes one checked string and uses genuine enclosing
  context through parentheses/conditional arms, never assertions. No target means
  unknown. Function bodies reset outer expression context.

Validate the complete contextual JSON schema, including unread optional fields.
Follow exact defining declarations with identity-and-provider cycle guards; stop at
unknown/Record and reject function/void leaves. Fresh symbol wrappers cannot control
termination. No same-spelled sibling, private-layout relocation or exported factory
may replace the owning declaration. Test acceptance/rejection independently of emitters.
