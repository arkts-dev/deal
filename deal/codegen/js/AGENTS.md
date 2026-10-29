# JavaScript Backend

`JsSemanticEmitter` renders validated IR, preserving CommonJS ABI, evaluation order, checks and origins. `ProductionProjectEmission` renders once per project, staging each implementation unit's artifact/map plus runtime/stdlib. Renderers never lower, execute hosts, write files or fall back to ASTs. Unsupported operations fail closed at their origin.

## Values and calls

- Consume structural facts from `RuntimeDeclarationSurface`/`RuntimeDeclarationProjection`. Resolve layouts per declaring unit; same-named siblings retain distinct nominal atoms. JSON links resolve exact plan-bearing declarations, never first matches.
- Construct classes from supplied values in declaration order. Bind complete boundary plans per source instance/declaration, never replacing them with host plans. Revalidate presence/fields after untyped mutation using operation-local cycle guards. Required omissions reject; omitted optionals remain absent. Exported metadata is not source-callable. Never consult defaults hooks or initializer evaluators.
- Static calls use recorded closure creation/group cells, not mutable aliases. Dynamic dispatch uses wrapper-recorded execution class and adapter source. Repeated adaptations retain separate sources.
- Each HOST import occurrence owns a validating surface over one raw require per resolved file. Validate/read exports in shared ascending-name order; failure leaves sticky FAILED state without a partial surface. Keep relative specifiers independent of ambient Node resolution.
- Preserve E8010 host-parameter composition and ordinary element/signature errors. ASSERTION alone projects all mismatches as E8001 at its own origin while preserving admitted identity. Unknown ingress rejects foreign carriers/raw functions and validates Unicode.
- Preserve null/undefined permissions, sentinels, identities, descriptors, live aliases and class presence/order across hosts. Validate Record/unknown graphs without copying, preserving cycles/sharing; publish unknown-array descriptors only after whole-graph success, never during assertions. Recursively reverse sync/async host projections. Parsed-array reflection reports live length and writes through; unsupported changes reject. Undefined follows declared permissions, without another no-result carrier.

## Control flow and storage

For-of evaluates once. Arrays traverse live contiguous positions/appends; source array deletion rejects. Iterations own mutable captured let cells; strings traverse Unicode scalars.

Array operations use carrier-neutral runtime accessors, including decoded arrays. Persistent descriptors cannot be retagged by empty/current contents. Stored undefined retains identity independently of nullability. Refined receiver/key/length/iterable/presence operations consume admitted boundary results; slot-threaded conditions preserve their preallocated identity. Assignment stores may adapt without changing the original RHS result.

Throw/catch preserves Error identity/causes; captured catch bindings get fresh execution cells. Function names retain source identity. Async start/await uses native Promises, recorded completion cells and public async-export ABI.

## JSON and maps

Generated Records share generic JSON omission/substitution, own-key ordering and nested-class rules; declared fields retain declaration order. Read private class presence/order metadata without hooks; detect cycles by original identity. Error exposes code/message only. Decoding tags typed arrays recursively, including empty arrays; nested decoder failure cannot become a successful nullable field.

Record source mappings through the shared recorder during emission. Map local groups at recorded origins, foreign boundaries through initiating callers; never borrow coordinates or move effects. Modules own version-1 sidecars. Map-off rendering changes only mapping comments, never runtime locations or executable bytes.
