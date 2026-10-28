# JavaScript Backend

Render validated semantic IR through `JsSemanticEmitter`, preserving CommonJS ABI, evaluation order, runtime checks and original locations. `ProductionProjectEmission` invokes one project rendering and stages one artifact/map per implementation unit with runtime/stdlib copies. Never lower, execute hosts, write files or select an AST fallback inside the renderer. Unsupported operations fail closed at their own origin.

## Values and calls

- Consume structural declaration facts from `RuntimeDeclarationSurface`/`RuntimeDeclarationProjection`. Resolve class layouts per declaring unit; same-named sibling files keep distinct nominal atoms. JSON links resolve the exact plan-bearing declaration, never a flattened first match.
- Construct classes from supplied values in declaration order. Bind each source instance's complete boundary field plan to its declaring layout; identity-wide host plans must not replace it. Revalidate presence and fields after untyped mutation with operation-local cycle guards. Required omissions reject; omitted optionals stay absent. Exported class metadata is not a source-callable constructor. Never read defaults hooks or invoke initializer evaluators.
- Resolve static calls through recorded closure creation/group cells, not mutable aliases. Dynamic dispatch consumes the wrapper instance's recorded execution class and adapter source. Repeated adaptations retain separate source records.
- Each HOST import occurrence owns a validating surface over one raw require per resolved file. Validate/read exports in shared ascending-name order; failure leaves sticky FAILED state without a partial surface. Keep relative specifiers independent of ambient Node resolution.
- Preserve E8010 host-parameter composition and ordinary element/signature errors. ASSERTION alone projects all mismatches as E8001 at its own origin while preserving admitted identity. Unknown ingress rejects foreign carriers/raw functions and validates Unicode.
- Preserve null/undefined permissions, sentinels, original identities, descriptors, live aliases and class presence/order across hosts. Validate Record/unknown graphs before publication without copying; accept supported cycles/sharing. Publish unknown-array descriptors only after graph validation; never infer them in assertions. Reverse projected host views recursively for sync/async results. Parsed-array reflection must report live length and write through, or explicitly reject unsupported changes. JavaScript undefined follows declared permissions, without a separate no-result carrier.

## Control flow and storage

For-of evaluates its iterable once. Array traversal observes live contiguous positions and appends; source array deletion rejects. Each iteration owns a mutable captured let cell. String traversal uses Unicode scalars.

Array operations use carrier-neutral runtime accessors, including decoded arrays. Persistent descriptors cannot be retagged by empty/current contents. Stored undefined retains identity independently of nullability. Refined receiver/key/length/iterable/presence operations consume admitted boundary results; slot-threaded conditions preserve their preallocated identity. Assignment stores may adapt without changing the original RHS result.

Throw/catch preserves Error identity and causes; captured catch bindings receive fresh cells per execution. Generated function names retain source identity. Async start/await uses native Promises with the recorded completion cell and public async-export ABI.

## JSON and maps

Generated Records share generic JSON omission/substitution, own-key ordering and nested-class rules; declared fields retain declaration order. Read private class presence/order metadata without hooks; detect cycles by original identity. Error exposes code/message only. Decoding tags typed arrays recursively, including empty arrays; nested decoder failure cannot become a successful nullable field.

Record source mappings during emission through the shared recorder. Map local groups at recorded local origins and foreign boundaries through their initiating caller; never borrow coordinates or move effects. Each module owns its version-1 sidecar. Map-off rendering changes only mapping comments, not runtime locations or executable bytes.
