# ISSUE-0428 — Settled Seam Shapes and Joint-Consumption Citations Audit Record

Audit record. Purpose: verify that the generated-content seam shapes
(`cdefBundle`/`plans`/`bindings`) are settled in
`luajit-ffi-generated-content-seam` covering every element of the
objective's seam enumeration with no invented layout, and that the joint
consumption by E2 and E4 is recorded in the authoritative records (lean
epic criterion 3). Every S-decision citation was checked against the
actual adopted text (the T2 verbatim-verified source) and the actual
directives records — never by trusting the seam page's own claim.

Verdict: **all checks pass; zero deviations; no seam-page correction was
required.** Each of the five objective seam elements is settled by its
S-decision with a citation verified against the adopted D1–D7 text and
the directives records; the field-name cross-check lists **zero**
unpinned fields beyond S7's three named items; S7 names exactly those
three and no other unpinned item appears in the settlement; and the
dependent records ISSUE-0423 and ISSUE-0425 now each list
`luajit-ffi-d1-d7-adoption` and `luajit-ffi-generated-content-seam` in
their `wiki` fields (added exactly once each; no existing entry removed;
no other content changed).

## 1. Audit inputs and the T2 combined dependency

Comparison sources:

- T2's record: `ISSUE-0427_D1_D7_VERBATIM_ADOPTION_AUDIT.md` (committed
  at `3070559`, carried into the canonical revision) — its section 2
  proves the adoption page's Areas 1–5 byte-identical to the landed page
  at the anchors: D1 `:44` (landed 46–82), D2 `:86` (88–95 and 97–105),
  D3 `:109` (111), D4 `:115` (117–129), D5 `:133` (135–142), D6 `:146`
  (148, 150), D7 `:154` (156) — 79 lines, 0 differing. T2's record
  supports the comparison: every adopted-text anchor used below is
  filled from its pair table, and the anchor re-check performed at audit
  time returned the identical header lines (D1 `:44`, D2 `:86`, D3
  `:109`, D4 `:115`, D5 `:133`, D6 `:146`, D7 `:154`).
- The landed page itself:
  `../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md`
  (read directly; quoted lines below re-checked against the file).
- The directives records:
  `../../forge/wiki/deal-v1.2-directives-and-c-ffi-declarations.md`
  (read directly; D4/D5/D7 are outside T2's adoption-page scope, so
  they are this audit's direct comparison source).
- The audit target: `../../forge/wiki/luajit-ffi-generated-content-seam.md`
  (131 lines, read directly).

## 2. Coverage mapping — objective seam element → S-decision → citation

Every element named in the lean epic's seam enumeration ("cdefBundle
entries, native-library reference, function/class metadata, plan
records/default evaluator content, binding cell shapes" —
`ISSUE-0422` Scope) is settled with a citation to the adopted D1–D7
text or the directives records. Supporting sentences quoted from the
actual sources (landed-page line numbers as T2-verified; directives
line numbers as read directly).

| # | Objective seam element | S-decision | Supporting citation (source, locator) | Quoted supporting sentence |
|---|---|---|---|---|
| 0 | Envelope: five-position seam with `moduleKey` and full-content identity | S1 | adopted D1, landed `:47` | `FfiModuleKey = "ffi:" + CanonicalExternalModuleIdentity` |
| | | | adopted D1, landed `:63` | `Equality compares full canonical UTF-8 content; SHA-256 is an index only.` |
| | | | realization D4 (`luajit-ffi-runtime-realization.md:63`) | "the seam is pinned at its envelope: `load_ffi(moduleKey, cdefBundle, plans, bindings, span)` — the only public entry, five argument positions, complete identity/descriptor content carried in the arguments, `moduleKey` = the parent's `FfiModuleKey` (`"ffi:" + canonical external module identity`), `span` = the import site's `spanArgs` triplet (`deal/codegen/lua/LuaBackend.java:941-945`)." plus "The runtime never re-derives metadata by parsing compiler serialization formats and never reads manifests (negative constraints)." |
| | | | emitter D6 (`luajit-v1.2-emitter-and-lowering.md:74`, `:76`) | "emits `local <alias> = __rt.load_ffi(<moduleKey>, <cdefBundle>, <plans>, <bindings>, <span>)`, where the bundle carries private cdef text with ordinal fields `deal_fN`, private function-pointer casts, plan/evaluator metadata for C-struct defaults, and binding cells." |
| 1 | cdefBundle entries | S2 | directives D7 `:188-189` | `CdefBundle(bundleDigest, fullContent, orderedEntries)` / `CdefEntry(entryDigest, fullText, ownedNames)` |
| | | | adopted D1, landed `:55-56` | `CdefModuleRecord(moduleKey,bundleDigest,fullContent,` `state: reserving | registering | registered | failed(errorValue))` |
| | | | adopted D2, landed `:88` | `Generated bundles never declare a real target function prototype. Private names include full module/signature digest and declaration ordinal.` |
| | | | adopted D2, landed `:91-92` | `FfiGeneratedField(dealName, ordinal,` `cMemberName = "deal_f" + ordinal, ffiType)` |
| | | | adopted D1 rule 2, landed `:76` | `Preflight every entry/name; differing text/name claims fail.` |
| 2 | Native-library reference | S3 | adopted D1, landed `:49` | `nativeLibraryKind, exactNormalizedLoaderText,` |
| | | | directives D5 `:132-133` | `NativeLibraryRef(kind: BARE_NAME | ABSOLUTE_PATH | MANIFEST_RELATIVE_PATH,` `loaderText, sourceRange)` |
| | | | adopted D3, landed `:111` | `Runtime defense requires a non-empty valid UTF-8 Lua string with no NUL before \`dlopen\`.` |
| | | | directives D5 `:153` (compile-side classification) | `Linux \`nativeLibrary\` classification is exact:` |
| 3 | Function/class metadata | S4 | adopted D1, landed `:50` | `canonicalFfiDescriptorContent, canonicalRuntimePlanContent,` |
| | | | directives D4 `:117-118` | `FfiFunctionDescriptor(dealName, cSymbol, privateFunctionPointerType,` `orderedParams, returnType)` |
| | | | directives D4 `:114-115` | `FfiClassDescriptor(name, canonicalClassIdentity, qualifiedDealDescriptor,` `kind, orderedFields, compilerDefaultPlan)` |
| | | | directives D4 `:116` | `FfiFieldDescriptor(dealName, fieldOrdinal, type: FfiType)` |
| | | | directives D4 `:119-120` | `FfiType(kind, canonicalDescriptor, canonicalClassIdentity?)` / `kind = INT | NUMBER | BOOLEAN | STRING | BYTES | NULL | C_STRUCT | C_POINTER` |
| | | | adopted D4, landed `:129` | `All descriptors come from \`CanonicalRuntimeTypeDescriptor\`; checks use \`RuntimeTypeMatcher\`.` |
| | | | adopted D2, landed `:105` | `All addresses resolve before casting to module-private function-pointer types.` |
| 4 | Plan records and default evaluator content | S5 | adopted D1, landed `:50-51` | `canonicalRuntimePlanContent,` / `semanticDefaultContents, evaluatorImplementationContents,` |
| | | | directives D4 `:109-115` | `FfiModuleDescriptor(moduleKey, semanticModuleIdentity, canonicalExternalModuleIdentity, nativeLibrary, ... runtimeDefaultPlans, canonicalPlanContent, planDigest)` and `compilerDefaultPlan` inside `FfiClassDescriptor` |
| | | | adopted D1, landed `:82` | `After registration, \`load_ffi\` opens the library, resolves all symbols, retains plans without invoking them, builds wrappers, binds cells, marks ready, and publishes atomically.` |
| | | | adopted D6, landed `:148` | `\`load_ffi\` retains but never invokes evaluators.` |
| 5 | Binding cell shapes | S6 | directives D7 `:182-187` | `FfiForwardBindings(moduleKey,` `state: UNBOUND | BINDING | READY | FAILED,` `sameModuleCells: Map<exportName, ForwardFunctionCell>,` `importedFunctions: immutable resolved wrapper references,` `importedClassPlans: immutable resolved class-plan references)` / `ForwardFunctionCell.get() -> typed DEAL wrapper | module initialization error` |
| | | | adopted D6, landed `:148` | `Imported references use graph-ordered wrappers/class plans. Same-module cells fill after all symbols/wrappers exist. Transition is \`UNBOUND → BINDING → READY\`; export publication/readiness is serialized.` |
| | | | adopted D6, landed `:150` | `Invocation is legal only at READY and dereferences current typed resources each time. FAILED re-raises cached initialization error. UNBOUND/BINDING becomes cached \`FFI_LIBRARY_LOAD\` and invokes no wrapper.` |
| | | | adopted D1 transition 5, landed `:71` | `Exact ready replay validates fresh binding names/descriptors before mutation, then atomically installs cached wrappers and READY. Incompatible/non-UNBOUND bindings fail before mutation.` |
| | | | directives D7 `:192` | `Failure marks cells FAILED with the cached error.` |

Coverage result: all five enumerated elements plus the S1 envelope are
settled with at least one citation to the adopted text or the directives
records; every quoted sentence above was re-read from the named file at
the named locator during this audit. T2's pair table covers every
adopted-text quote (pairs 1, 2, 3, 4, 5, 7; see section 5).

## 3. Field-name cross-check (no-invention)

Every field name that appears in S1–S6, with its supporting pin. Pins are
the adopted D1 identity fields (landed `:47-60`), the D2 generated-field
shape (landed `:88-95`), the D6 cell states (landed `:148-150`), and the
directives D4/D5/D7 records.

| Field(s) in S1–S6 | Supporting pin (source, locator) |
|---|---|
| S1 `moduleKey` | adopted D1 `FfiModuleKey = "ffi:" + CanonicalExternalModuleIdentity` (landed `:47`); realization D4 |
| S1 `cdefBundle`/`plans`/`bindings` argument positions | realization D4 five positions (`luajit-ffi-runtime-realization.md:63`); emitter D6 call shape (`luajit-v1.2-emitter-and-lowering.md:76`); objective seam enumeration |
| S1 `file, line, column` triplet | `spanArgs` expansion (`deal/codegen/lua/LuaBackend.java:941-945`, verified: `return "\"" + escapeLuaStringNoQuotes(span.file()) + "\", " + span.startLine() + ", " + span.startColumn();`) |
| S1 `FfiInitializationIdentity` / `runtimeAbiVersion` / `identityDigest` | adopted D1 identity record (landed `:48-52`) and `:63` ("SHA-256 is an index only") |
| S1 full-content equality | adopted D1 `:63` ("Equality compares full canonical UTF-8 content") |
| S2 `CdefBundle(bundleDigest, fullContent, orderedEntries)` | directives D7 `:188` |
| S2 `CdefEntry(entryDigest, fullText, ownedNames)` | directives D7 `:189` |
| S2 `CdefModuleRecord(moduleKey,bundleDigest,fullContent,...)` | adopted D1 (landed `:55-56`) |
| S2 `FfiGeneratedField(dealName, ordinal, cMemberName = "deal_f" + ordinal, ffiType)` | adopted D2 (landed `:91-92`) |
| S2 private names "full module/signature digest and declaration ordinal" | adopted D2 (landed `:88`) |
| S2 metadata maps DEAL names to ordinals; C-keyword rule | adopted D2 (landed `:95`); D5 table ordinals (`deal_fN`) |
| S2 `cdefBundleContent` identity field | adopted D1 (landed `:52`) |
| S2 per-entry preflight/collision comparison (`entryDigest`/`fullText`/`ownedNames`) | adopted D1 cdef rules 2–3, 6 (landed `:76`, `:77`, `:80`) and `CdefEntryRecord(ownerModuleKey,entryDigest,fullText,ownedNames,...)` (landed `:57-59`) |
| S3 `nativeLibraryKind` / `exactNormalizedLoaderText` | adopted D1 (landed `:49`) |
| S3 `NativeLibraryRef(kind: BARE_NAME | ABSOLUTE_PATH | MANIFEST_RELATIVE_PATH, loaderText)` | directives D5 `:132-133` |
| S3 defense rule (non-empty valid UTF-8 Lua string, no NUL) | adopted D3 (landed `:111`) |
| S3 compile-side classification / runtime never reads manifests | directives D5 `:153`; realization D4 negative constraints |
| S4 `canonicalFfiDescriptorContent` | adopted D1 (landed `:50`) |
| S4 `FfiModuleDescriptor` | directives D4 `:109-113` |
| S4 `FfiFunctionDescriptor(dealName, cSymbol, privateFunctionPointerType, orderedParams, returnType)` | directives D4 `:117-118` |
| S4 `FfiClassDescriptor(name, canonicalClassIdentity, qualifiedDealDescriptor, kind, orderedFields, compilerDefaultPlan)` | directives D4 `:114-115` |
| S4 `FfiFieldDescriptor(dealName, fieldOrdinal, type: FfiType)` | directives D4 `:116` |
| S4 `FfiType(kind, canonicalDescriptor, canonicalClassIdentity?)` | directives D4 `:119` |
| S4 `kind = INT | NUMBER | BOOLEAN | STRING | BYTES | NULL | C_STRUCT | C_POINTER` | directives D4 `:120` |
| S4 `CanonicalRuntimeTypeDescriptor` / `RuntimeTypeMatcher` | adopted D4 (landed `:129`) |
| S4 private function-pointer casts after all addresses resolve | adopted D2 (landed `:105`) |
| S5 `canonicalRuntimePlanContent` / `semanticDefaultContents` / `evaluatorImplementationContents` | adopted D1 (landed `:50-51`) |
| S5 `runtimeDefaultPlans` / `canonicalPlanContent` / `planDigest` | directives D4 `:109-113` |
| S5 `compilerDefaultPlan` | directives D4 `:115` |
| S5 retains plans without invoking; never invokes evaluators | adopted D1 (landed `:82`); adopted D6 (landed `:148`) |
| S5 `__rt.class_plan_` plan-entry convention | realization D6; `deal/runtime.lua:744-745` (verified: the doc-comment forward reference "the v1.2 runtime entries (class_plan_ phase-3 field validation, ...)"); ISSUE-0276 tree's deliverable (recorded as not re-pinned) |
| S6 `FfiForwardBindings(moduleKey, state, sameModuleCells, importedFunctions, importedClassPlans)` | directives D7 `:182-186` |
| S6 `sameModuleCells: Map<exportName, ForwardFunctionCell>` | directives D7 `:184` |
| S6 `ForwardFunctionCell.get() -> typed DEAL wrapper | module initialization error` | directives D7 `:187` |
| S6 states `UNBOUND | BINDING | READY | FAILED` | directives D7 `:183`; adopted D6 (landed `:148`, `:150`) |
| S6 graph-ordered imported wrappers/class plans | adopted D6 (landed `:148`); directives D7 `:192` ("Evaluators close over cells or graph-ordered imported wrappers/class plans.") |
| S6 cells fill after all symbols/wrappers exist; serialized publication | adopted D6 (landed `:148`) |
| S6 ready replay validation/atomic install/fail-before-mutation | adopted D1 transition 5 (landed `:71`) |
| S6 invocation at READY; UNBOUND/BINDING cached error; FAILED re-raise | adopted D6 (landed `:150`); directives D7 `:192` |

**Unpinned field list: empty** — every field name in S1–S6 traces to an
adopted D1/D2/D6 pin or a directives D4/D5/D7 record, exactly as the
no-invention criterion requires.

## 4. S7 — exactly three deliberately unpinned items

S7 names exactly three items and no other unpinned item appears anywhere
in the settlement (the section-3 table above pins every other field):

- (a) literal LuaJIT cdef ctype spellings — supported: the adopted
  authority pins ABI types, not literal ctype text (D4/D5 bullets use
  `int32_t`, `double`, `_Bool`, `void*` and the `const uint8_t*` +
  signed-int32 length pair per the spec ABI rows; no literal cdef string
  appears in D4–D5, landed `:117-142`);
- (b) the internal storage layout of pointer tokens — supported: adopted
  D4 pins observable behavior only ("`C_POINTER`: outbound exact nominal
  non-null token to `void*`; inbound NULL is `FFI_NULL_POINTER`,
  otherwise a fresh declared-identity token.", landed `:127-128`) and the
  DEAL-opacity rule is `docs/spec-v1.2.md:1822-1831` (verified: "A
  `// @c-pointer` class is a nominal exported DEAL type representing a
  non-null opaque `void*` token. It has no DEAL-visible fields or storage
  layout."); no token-storage layout appears in D4–D5;
- (c) compile-side serializer formats — supported: the objective Boundary
  excludes FFIGEN/REGISTRY content authoring from the epic.

No other deliberately unpinned item is named by S7 and none is required:
the settlement contains no field that lacks a pin.

## 5. Anti-hollow — citation check log (S-decision → actual source)

| S-decision | Cited sources | Check result (against T2-verified text / actual directives records) |
|---|---|---|
| S1 | adopted D1; realization D4; emitter D6; `spanArgs` | D1 quotes byte-match landed `:47-52`, `:63` (T2 pair 1 covers 46–82 — covers `FfiModuleKey`, identity block, equality paragraph); realization D4 read at `luajit-ffi-runtime-realization.md:63-65` carries the five-position envelope, canonical-descriptors rule, and the never-parses-serializer/never-reads-manifests negatives verbatim; emitter D6 read at `luajit-v1.2-emitter-and-lowering.md:74-76` pins the identical `__rt.load_ffi(<moduleKey>, <cdefBundle>, <plans>, <bindings>, <span>)` shape; `spanArgs` verified at `deal/codegen/lua/LuaBackend.java:941-945` |
| S2 | adopted D1–D2; directives D7 | D2 quotes byte-match landed `:88`, `:91-92`, `:95` (T2 pair 2 covers 88–95); `CdefModuleRecord` byte-match landed `:55-56` and cdef rules 2–3, 6 at `:76-77`, `:80` (T2 pair 1); `CdefBundle`/`CdefEntry` read directly at directives `:188-189` |
| S3 | adopted D1/D3; directives D5 | `nativeLibraryKind, exactNormalizedLoaderText` byte-match landed `:49` (T2 pair 1); D3 defense sentence byte-match landed `:111` (T2 pair 4); `NativeLibraryRef` read directly at directives `:132-133`; compile-side classification read at directives `:153` |
| S4 | adopted D1–D2/D4; directives D4 | `canonicalFfiDescriptorContent` byte-match landed `:50` (T2 pair 1); canonical-descriptor rule byte-match landed `:129` (T2 pair 5 covers 117–129); private-cast rule byte-match landed `:105` (T2 pair 3 covers 97–105); the descriptor family and eight kinds read directly at directives `:109-120` |
| S5 | adopted D1/D6; directives D4/D7 | identity fields byte-match landed `:50-51` (T2 pair 1); "retains plans without invoking them" byte-match landed `:82` (T2 pair 1); "retains but never invokes evaluators" byte-match landed `:148` (T2 pair 7); `runtimeDefaultPlans`/`canonicalPlanContent`/`planDigest`/`compilerDefaultPlan` read directly at directives `:109-115`; `__rt.class_plan_` verified at `deal/runtime.lua:744-745` |
| S6 | adopted D1/D6; directives D7 | D6 sentences byte-match landed `:148`, `:150` (T2 pair 7); D1 transition 5 byte-match landed `:71` (T2 pair 1); `FfiForwardBindings`/`ForwardFunctionCell.get()` read directly at directives `:182-187`; "Failure marks cells FAILED with the cached error." read at directives `:192` |
| S7 | adopted D4–D5 text; objective Boundary | D4 C_POINTER bullet byte-match landed `:127-128` (T2 pair 5); no literal ctype spelling and no token storage layout anywhere in landed `:117-142` (read directly; T2 pairs 5–6 cover the same ranges); spec opacity rule verified at `docs/spec-v1.2.md:1822-1831`; objective Boundary excludes FFIGEN/REGISTRY authoring |

Result: every S-decision citation is supported by the actual adopted
text or the actual directives records; **no objective seam element is
settled by an unsupported claim**. The check log references T2's
verified anchors throughout (pairs 1–7), so the combined-dependency
step is satisfied.

## 6. Joint consumption — dependent records before and after

Both records were read before and after the edit. Before the edit,
neither record listed the adoption or seam slug (grep count 0 in each),
and the wiki fields were:

- ISSUE-0423 (`../../forge/issues/ISSUE-0423-realize-load-ffi-cache-registry-resolver-convert.md`)
  before: `luajit-ffi-runtime-realization`,
  `luajit-ffi-runtime-half`,
  `luajit-v1.2-runtime-value-model-and-boundaries`,
  `deal-v1.2-luajit-c-ffi-runtime-and-conformance`.
- ISSUE-0425 (`../../forge/issues/ISSUE-0425-ffigen-boundary-integration-through-production-l.md`)
  before: `luajit-ffi-runtime-realization`,
  `luajit-ffi-runtime-half`, `luajit-v1.2-emitter-and-lowering`,
  `deal-v1.2-luajit-c-ffi-runtime-and-conformance`.

Action: each `wiki` field gained exactly the two missing slugs appended
after the last existing entry — `luajit-ffi-d1-d7-adoption` and
`luajit-ffi-generated-content-seam` (one occurrence each; grep count 2
per file after the edit). No existing entry was removed and no other
content of either record was changed: front matter (`status`, `created`,
`updated`) is unchanged, file line counts changed by exactly +2
(ISSUE-0423 77→79, ISSUE-0425 74→76), and the body sections are
byte-identical except the two inserted lines.

After:

- ISSUE-0423 `wiki` (final): `luajit-ffi-runtime-realization`,
  `luajit-ffi-runtime-half`,
  `luajit-v1.2-runtime-value-model-and-boundaries`,
  `deal-v1.2-luajit-c-ffi-runtime-and-conformance`,
  `luajit-ffi-d1-d7-adoption`, `luajit-ffi-generated-content-seam`.
- ISSUE-0425 `wiki` (final): `luajit-ffi-runtime-realization`,
  `luajit-ffi-runtime-half`, `luajit-v1.2-emitter-and-lowering`,
  `deal-v1.2-luajit-c-ffi-runtime-and-conformance`,
  `luajit-ffi-d1-d7-adoption`, `luajit-ffi-generated-content-seam`.

ISSUE-0425's divergent-shape pin (quoted from the record after the
edit):

- Acceptance criteria (line 63): "The consumed generated binding matches
  the seam shapes settled at E1 and the pinned `load_ffi` and wrapper
  call shapes; any divergence fails the integration at load."
- Interface contract (line 53) carries the exact sentence the seam
  page's joint-consumption contract quotes ("E4's acceptance pins ..."),
  confirming the citation to the ISSUE-0425 record: "The seam shapes of
  `cdefBundle`/`plans`/`bindings` are the ones settled at D1–D7 adoption
  (E1) and are consumed jointly with the runtime half (E2); a divergent
  sibling shape fails the integration at load."

The pin is carried in ISSUE-0425's acceptance (the second acceptance
criterion pins that any divergence of the consumed sibling-generated
shape fails the integration at load), so no acceptance edit was needed
and none was made — consistent with the "no other content is changed"
constraint. The seam page's joint-consumption contract names both
records, and its Verification item 2 ("E2 and E4 cite this page as the
settled seam") is now true in the authoritative records via the two
`wiki` fields above.

## 7. Verification gate

`./run_tests.sh` — exit 0 at the final rebased commit. Only this record
file is added; no production or test artifact changes, so the gate runs
the same green battery as the pre-task baseline.
