# Checker

Own resolution, typing, narrowing and facts. Diagnostics are deterministic/target-neutral; downstream phases cannot rediscover meaning.

- Resolve classes through exact declarations/providers in annotations, calls, arrays, fields and contextual literals. Same-spelled siblings remain nominally distinct; reject textual/first-identity substitution. Publish construction/per-expression facts.
- Resolve even unused field annotations. Validate supplied literals against exact fields; require nonoptional fields, including nullable fields and Error code/message.
- Record scope-correct refined reads in `CheckResult.narrowedReads`. Calls preserve refinements; consuming boundaries validate stale values. Assignment/await/closures retain specified invalidation; conditions/typed boundaries retain their projections.
- Track null/undefined independently. Optional reads add undefined; presence never refines values. Record reads infer unknown; concrete consumption requires checked extraction or supported identifier refinement.
- Record instanceof targets' resolved nominal types. Negation reverses nominal, typeof and nullish branch polarity. Never infer object/function types from typeof.
- Void is result-only, never stored. TypeChecker alone diagnoses definite returns: only void permits fallthrough, including nested recursion. Null requires explicit return; no duplicate AST gate.
- Catch bindings are mutable local unknowns. Error access requires extraction/matching nominal branches. Preserve shadowing/captures; anchor binding diagnostics on identifiers.
- Each while-body entry receives fresh true-guard refinement with ordinary invalidation; body refinements cannot cross back-edges. Unknown equality permits neither narrowing nor conversion.
- Bindings reject eval/arguments and reserved words; async/as/from/of are contextual identifiers. Keyword properties remain legal. Reject duplicate surface nullish union members before unknown absorption.
- Declarations/imports are immutable; function-valued variables remain mutable. Resolve signatures in their enclosing scope, excluding shadowing parameters.
- Nested functions follow declaration order; modules hoist. Reject namespace/metadata data, duplicate properties and array deletion. Record keys remain string data; class members are declared-field accesses.

Test acceptance/rejection independently of emitters.
