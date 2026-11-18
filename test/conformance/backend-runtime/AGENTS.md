# Runtime Conformance

Execute production artifacts and assert values, effects, identity, order and exact
failures/origins. Every case requires JS/LuaJIT/JVM with identical expectations and
no exclusions. Missing tools, hosts, exports or completions fail.

`CorpusConformanceTest` invokes main once, then eligible zero-arity exports in
declaration order; reconcile external receipts. Numeric async exports use public
invokers and raw binary64 comparison: 1/1.0 agree, signed zeros differ. Negative
probes use empty main and one failing probe; split independent throws.

- Pin exact nominal/provider identities with valid controls. Assertions own E8001;
  ordinary boundaries retain their projections. Contextualize literals and supply
  required fields. Fresh literals versus supplied aliases remain distinct.
- Preserve binary64 specials, null/undefined/absence and result-only void. Compare
  JSON values structurally; pin numeric spelling/order separately. Contextual decode
  failures preserve destinations; extras use E8007, syntax/type failures E8001.
- Unknown/catch reads require extraction. Use declared-result wrappers when a test
  needs unknown/nullability that initialization would refine. Reach actual consuming
  boundaries without assertions masking them; guards follow current flow rules.
- Exercise mutable/aggregate/adapted recursion and fresh escaped/catch/iteration
  cells. Async subjects must invoke public completions, not merely main.
- Host inputs preserve equivalent array intent/descriptors and malformed callable
  subjects across backends. States/reflection unavailable in JVM typed storage
  belong in focused integration suites. Do not weaken expectations during migration.
