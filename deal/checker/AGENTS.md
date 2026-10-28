# Checker

Own resolution, typing, narrowing and checked facts. Diagnostics remain deterministic/target-neutral; downstream phases cannot rediscover meaning.

- Resolve classes by exact declarations/providers across imported annotations, calls, arrays, fields and contextual literals. Same-spelled siblings remain nominally distinct; no textual/first-identity substitution. Publish construction/per-expression facts.
- Resolve even unused field annotations. Validate supplied literals against exact fields; require nonoptional fields, including nullable fields and Error code/message.
- Record scope-correct refined reads in `CheckResult.narrowedReads`. Calls preserve refinements; consuming boundaries validate stale values. Assignment/await/closures retain specified invalidation; conditions/typed boundaries retain their projections.
- Track null and undefined independently. Optional reads add undefined; presence does not imply value refinement. Record reads infer unknown and require checked extraction or supported identifier refinement before concrete consumption.
- Record every instanceof target's resolved nominal type. Negation reverses branch polarity for nominal, typeof and nullish tests. Never infer arbitrary object/function types from typeof.
- Void is result-only and cannot enter data storage. TypeChecker alone owns definite-return diagnostics: only void permits fallthrough, including nested recursive bodies. Null requires explicit return; no duplicate AST falloff gate.
- Catch bindings are mutable local unknowns. Error access requires extraction/matching nominal branches. Preserve shadowing/captures; anchor binding diagnostics on identifiers.
- Each while-body entry receives fresh true-guard refinement with ordinary invalidation; body refinements cannot cross back-edges. Unknown equality permits neither narrowing nor conversion.
- Bindings reject eval/arguments and reserved words; async/as/from/of are contextual identifiers. Keyword properties remain legal. Reject duplicate surface nullish union members before unknown absorption.
- Nested functions follow declaration order; modules hoist. Reject namespace/metadata data, duplicate properties and array deletion. Record keys remain string data; class members are declared-field accesses.

Test acceptance/rejection independently of emitters.
