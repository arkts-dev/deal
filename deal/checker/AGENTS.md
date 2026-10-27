# Checker

Own resolution, typing, narrowing and explicit checked facts. Diagnostics are deterministic and target-neutral; lowering and emitters must not rediscover source meaning.

- Resolve classes through exact declarations and defining providers, including imported annotations, call results, arrays, fields and contextual literals. Same-spelled sibling declarations remain nominally distinct; never substitute a textual-name or first-identity match. Publish construction and per-expression declaration facts.
- Resolve every field annotation, even on unused classes. Validate supplied literal values against their exact fields and require every nonoptional field, including nullable fields and Error code/message.
- Record refined binding reads in `CheckResult.narrowedReads` with scope-correct identity. Calls preserve refinements; consuming runtime boundaries validate stale values. Assignment, await and closure boundaries retain their specified invalidation. Conditions and ordinary typed boundaries keep their own projections.
- Track null and undefined independently. Optional reads add undefined; presence does not imply value refinement. Record reads infer unknown and require checked extraction or supported identifier refinement before concrete consumption.
- Record every instanceof target's resolved nominal type. Negation reverses branch polarity for nominal, typeof and nullish tests. Never infer arbitrary object/function types from typeof.
- Void is result-only and cannot enter data storage. TypeChecker alone owns definite-return diagnostics: only void permits fallthrough, including nested recursive bodies. Null requires explicit return; no duplicate AST falloff gate.
- Catch bindings are mutable local unknown values. Error member access requires extraction or a matching nominal branch. Preserve shadowing/captures and anchor binding diagnostics on the identifier.
- Ordinary names resolve lexically; no numeric conversion seeds exist. Record keys are string data, not binding declarations; class members remain declared-field accesses.

Test accepted and rejected forms without relying on emitter behavior.
