# Checker

Own resolution, typing, narrowing and facts. Diagnostics are deterministic/target-neutral; downstream phases cannot rediscover meaning.

- Resolve classes through exact declarations/providers in annotations, calls, arrays, fields and contextual literals. Same-spelled siblings remain nominally distinct; reject textual/first-identity substitution. Publish construction/per-expression facts.
- Resolve even unused field annotations. Validate supplied literals against exact fields; require nonoptional fields, including nullable fields and Error code/message.
- Record scope-correct refined reads in `CheckResult.narrowedReads`. Calls preserve refinements; consuming boundaries validate stale values. Assignment/await/closures retain specified invalidation; conditions/typed boundaries retain their projections.
- Track null/undefined independently. Optional reads add undefined; presence never refines values. Record reads infer unknown; concrete consumption requires checked extraction or supported identifier refinement.
- Record instanceof targets' resolved nominal types. Negation reverses nominal, typeof and nullish branch polarity. Never infer object/function types from typeof.
- Void is result-only, never stored. TypeChecker alone diagnoses definite returns: only void permits fallthrough, including nested recursion. Null requires explicit return; no duplicate AST gate. A catch may fall through to a later return, and void bodies may fall through entirely; no independent try/catch return obligation exists.
- Catch bindings are mutable local unknowns. Error access requires extraction/matching nominal branches. Preserve shadowing/captures; anchor binding diagnostics on identifiers.
- Each while-body entry receives fresh true-guard refinement with ordinary invalidation; body refinements cannot cross back-edges. Unknown equality permits neither narrowing nor conversion.
- Bindings reject eval/arguments and reserved words; async/as/from/of are contextual identifiers. Keyword properties remain legal. Reject duplicate surface nullish union members before unknown absorption.
- Declarations/imports and const bindings are immutable; let-bound function values remain mutable. Preserve const through inference, closure resolution and for-of binding replacement; aggregates referenced by const remain mutable. Resolve signatures in their enclosing scope, excluding shadowing parameters.
- Nested functions follow declaration order; modules hoist. Validate nested declaration names (reserved/builtin/$) and reject same-scope redeclaration without forbidding ordinary outer shadowing. Reject namespace/metadata data, duplicate properties and array deletion. Record keys remain string data; class members are declared-field accesses.

Record, Promise, Object, Array, String, JSON, Math, Date, console and undefined cannot be rebound by user declarations/imports/parameters/iteration/catch names. Fixed Math, Date, console and JSON.stringify namespace operations require direct calls and exact signatures; JSON.stringify accepts unknown without supplying an extraction context and returns string|undefined; ordinary class/Record callable fields remain distinct.

String is an unshadowable direct-call-only builtin with exactly one checked number argument; it supplies no contextual unknown extraction. Lower calls through the same NUMBER_TO_STRING contract as numeric interpolation.

Template interpolations accept checked string or number values only; they grant no implicit contextual extraction from unknown or nullish values.

Expression-bodied arrows check their expression against the declared result, including requiring a void expression for void-result arrows. Their blocks, captures, recursion and async checks otherwise follow ordinary function expressions.

String receiver length is read-only and number-typed. Resolve receiver methods from the checked string type, not member spelling alone; require direct calls and exact declared argument types/arity. Record/class function fields remain ordinary callable values.

Array pop and splice are direct-call-only receiver intrinsics selected by checked array type. Pop takes no arguments and returns the optional element type, including independent null/undefined flags. Splice takes exactly two numbers and returns the same array element type in fresh storage. Class/Record callable fields with these names remain ordinary values.

Object.hasOwn accepts Record/class receivers and string keys without field narrowing; class keys must be literal declared-field names, including Error code/message. Reject detached or wrong-arity builtin calls.

Object.keys is direct-call-only with exactly one checked Record/class argument and a string[] result; unknown, arrays, functions and nullish receivers require appropriate extraction/narrowing. It grants no contextual extraction and never narrows fields.

Conditional conditions require boolean and receive no outer result context. Both arms inherit the result context and their guard refinements; merge invalidations at continuation. Context-free branches require identical types or permitted nullish joins. Contextual function arity extension requires selected-arm adapters; void joins require two void results and cannot be stored as data.

Test acceptance/rejection independently of emitters.
