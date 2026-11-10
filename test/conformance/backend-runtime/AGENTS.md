# Runtime Conformance

Execute production artifacts and assert values, effects, exact failures/origins, nominal identity and evaluation order. Every case requires JS/LuaJIT/JVM with identical expectations and zero exclusions; missing tools, exports, host companions or completions fail. Do not bless AST-only behavior.

CorpusConformanceTest discovers cases and parses invocation plans: main once, then eligible zero-arity exports in declaration order. Reconcile transcript-external receipts. Sidecar-pinned numeric async exports use public invokers and raw binary64 comparison: JSON 1/1.0 agree; signed zeros differ. Negatives with probes require empty main and one failing probe; split independent throws. Assert computed results locally; preserve focused consumers.

- Import standard modules under nonreserved aliases when the imported surface is the subject; retain imported callable identity and keep exact failure coordinates stable during alias migrations.
- Pin exact sibling declaration atoms with independently executed valid controls. Assertions use E8001; ordinary identity boundaries keep their own projections.
- Supply all required class fields, including nullable/callable fields. Separate literals establish freshness; supplied references establish aliases. Failed construction preserves expression effects without publishing partial targets.
- Preserve binary64, signed zero and nontrapping arithmetic. Compare JSON structures/decoded numbers for value assertions; separately pin ECMAScript numeric spelling, including signed-zero normalization and exponent thresholds.
- JSON.stringify omits object undefined/functions, null-substitutes arrays/nonfinite numbers, and returns undefined for unrepresentable roots. Independently cover cycle rejection, declared-field ordering, any-root parse success and contextual Record target rejection of nonobject roots. Contextual JSON.parse throws on invalid reconstruction; pin E8007 for extra class fields separately from E8001 syntax/type failures and verify unchanged destinations/descendant aliases. Preserve imported callable subjects using ordinary declared wrappers rather than treating direct-only builtins as values; no declaration marker or generated helper is required.
- Catch/Record reads remain unknown until checked extraction or supported refinement. Preserve shadowing, async invalidation and actual consuming-boundary negatives; do not assume unsupported post-termination narrowing.
- Presence uses Object.hasOwn without refinement. Distinguish absence, null and stored undefined. Delete Record keys/optional fields, never array elements. Void completion is not null.

Dynamic-recursion fixtures exercise mutable bindings, arrays, Records, assertions, adapters, caught throws and escaped per-activation local cells; named/nonrecursive controls remain distinct. Async fixtures pin public completion invocations rather than merely calling main. Syntax controls preserve parenthesized expressions, separated minus tokens and literal valid template escape spellings.

Host setup must supply equivalent array intent/descriptors and malformed-callable inputs across backends. Native reflection and field states unrepresentable in JVM typed storage belong to focused host-integration suites. Synchronize migrated source coordinates without weakening expectations.
