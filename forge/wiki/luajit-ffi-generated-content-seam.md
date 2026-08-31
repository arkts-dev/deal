---
slug: luajit-ffi-generated-content-seam
title: LuaJIT FFI Generated-Content Seam — Settled cdefBundle, Plans, and Bindings Field Shapes
source: ../../forge/wiki/luajit-ffi-runtime-realization.md
issue: ISSUE-0422
tags:
  - deal
  - v1.2
  - luajit
  - c-ffi
  - runtime
  - ffigen
  - architecture-design
  - architecture-active
created: 2026-08-30T19:42:08Z
updated: 2026-08-30T19:42:08Z
---

# LuaJIT FFI Generated-Content Seam — Settled cdefBundle, Plans, and Bindings Field Shapes

## Context and evidence

**Design mode: Greenfield.** This page settles the exact generated-content seam shapes the adopted `deal-v1.2-luajit-c-ffi-runtime-and-conformance` D1–D7 pins — the deliverable named by the objective's seam item ("`cdefBundle` entries, native-library reference, function/class metadata; plan records and default evaluator content; binding cell shapes") — for joint consumption by the runtime-half realization (E2, `../../forge/issues/ISSUE-0423-realize-load-ffi-cache-registry-resolver-convert.md`) and the FFIGEN integration child (E4, `../../forge/issues/ISSUE-0425-ffigen-boundary-integration-through-production-l.md`). The adoption and gate lift are recorded in `luajit-ffi-d1-d7-adoption`.

Authorities consumed: the adopted D1–D7 text (`luajit-ffi-d1-d7-adoption` areas 1–5; the landed page `../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md` D1 `:44`, D2 `:86`, D6 `:146`); the compile-side records `deal-v1.2-directives-and-c-ffi-declarations` D4 (`FfiModuleDescriptor` family), D5 (`NativeLibraryRef`), D7 (`CdefBundle`/`FfiForwardBindings`); the realization envelope `luajit-ffi-runtime-realization` D4 (five argument positions, canonical descriptors only, runtime never parses compiler serialization formats and never reads manifests) and the emitter page's pinned call shape (`luajit-v1.2-emitter-and-lowering` D6).

Verified in this checkout: `deal/runtime.lua` has no `load_ffi`/`ffi.cdef`/`dlopen`; `__rt.class_plan_` is still a doc-comment forward reference (`deal/runtime.lua:775-777`) — the ISSUE-0276 tree's deliverable, consumed by the realization (realization D6), not re-pinned here. `spanArgs` expands a source span to `"<file>", <startLine>, <startColumn>` (`deal/codegen/lua/LuaBackend.java:1080-1085`).

Assumption (labeled): the settlement below uses only the adopted pins plus the objective's own enumeration of seam elements; no record layout beyond those pins is invented (S7 names the deliberately unpinned items).

## Decisions

### S1. The seam is the five-position envelope; every identity content field is carried by an argument `(pinned by the realization envelope and adopted D1)`

- **Selected**: `load_ffi(moduleKey, cdefBundle, plans, bindings, file, line, column)` — `moduleKey` is the adopted `FfiModuleKey` (`"ffi:" + CanonicalExternalModuleIdentity`); `cdefBundle` carries the bundle content (native-library reference, function/class metadata, ordered cdef entries); `plans` carries plan records and default evaluator content; `bindings` carries binding cells and imported references; the trailing triplet is the import site's `spanArgs` expansion (`deal/codegen/lua/LuaBackend.java:1080-1085`). The runtime assembles `FfiInitializationIdentity` from these argument-carried contents plus its own `runtimeAbiVersion`; equality is full-content; `identityDigest` (SHA-256) is an index only (adopted D1).
- **Evidence**: objective seam enumeration (groups the native-library reference and function/class metadata with `cdefBundle`); adopted D1 identity fields; realization D4; emitter page D6.
- **Alternative**: a single serialized identity blob argument.
- **Rejected because**: the five-position envelope is pinned by the realization boundary and the emitter call shape; a blob would force the runtime to parse a compiler serialization format (forbidden by the realization D4 negative constraints).

### S2. cdefBundle shape `(pinned by adopted D1–D2 and directives D7)`

- **Selected**: `cdefBundle = CdefBundle(bundleDigest, fullContent, orderedEntries)` with each entry `CdefEntry(entryDigest, fullText, ownedNames)` (directives D7). Content rules per adopted D2: entries never declare a real target function prototype; private names include full module/signature digest and declaration ordinal; struct fields are `FfiGeneratedField(dealName, ordinal, cMemberName = "deal_f" + ordinal, ffiType)` in source order; metadata maps DEAL names to ordinals so C-keyword DEAL names cannot poison cdefs. `fullContent` is the complete canonical UTF-8 content that participates in identity equality as `cdefBundleContent` (adopted D1); `bundleDigest` is an index only. The registry compares per-entry `entryDigest`/`fullText`/`ownedNames` for preflight and collision (adopted D1 cdef rules 2–3, 6).
- **Evidence**: adopted D1 (`CdefModuleRecord(moduleKey,bundleDigest,fullContent,...)`; `cdefBundleContent` identity field), D2 (generated bundles, private names, ordinals); directives D7 records.
- **Alternative**: entries without fullText (digest only).
- **Rejected because**: adopted D1 requires preflight on differing text/name claims and collision checks over full content; digest-only loses the text the registry must compare.

### S3. Native-library reference `(pinned by adopted D1/D3 and directives D5)`

- **Selected**: the bundle carries the native-library reference as `(nativeLibraryKind, exactNormalizedLoaderText)` — the runtime-side identity fields (adopted D1) of the compile-side `NativeLibraryRef(kind: BARE_NAME | ABSOLUTE_PATH | MANIFEST_RELATIVE_PATH, loaderText)` (directives D5). Runtime defense before `dlopen`: non-empty valid UTF-8 Lua string with no NUL (adopted D3). The runtime never reads manifests and never classifies paths itself; classification is compile-side (directives D5).
- **Evidence**: adopted D1 identity fields `nativeLibraryKind`/`exactNormalizedLoaderText`; adopted D3; directives D5; realization D4 negative constraints.
- **Alternative**: runtime re-resolves the loader text from a manifest path.
- **Rejected because**: it would create a runtime manifest dependency (forbidden) and re-derive compile-side classification.

### S4. Function/class metadata `(pinned by adopted D1–D2 and directives D4)`

- **Selected**: the bundle carries the canonical function/class metadata content — the `canonicalFfiDescriptorContent` identity field (adopted D1) — generated from the compile-side `FfiModuleDescriptor`/`FfiFunctionDescriptor(dealName, cSymbol, privateFunctionPointerType, orderedParams, returnType)`/`FfiClassDescriptor(name, canonicalClassIdentity, qualifiedDealDescriptor, kind, orderedFields, compilerDefaultPlan)`/`FfiFieldDescriptor(dealName, fieldOrdinal, type: FfiType)`/`FfiType(kind, canonicalDescriptor, canonicalClassIdentity?)` with `kind = INT | NUMBER | BOOLEAN | STRING | BYTES | NULL | C_STRUCT | C_POINTER` (directives D4). Every descriptor inside is canonical (`CanonicalRuntimeTypeDescriptor`); checks use `RuntimeTypeMatcher` (adopted D4). Wrappers cast resolved addresses only to the metadata's private function-pointer types, after all addresses resolve (adopted D2).
- **Evidence**: adopted D1/D2/D4; directives D4; realization D4 (canonical descriptors only).
- **Alternative**: runtime-side descriptor re-derivation from serialized plans.
- **Rejected because**: forbidden by the no-compiler-dependency constraint and would fork descriptor identity.

### S5. Plan records and default evaluator content `(pinned by adopted D1/D6 and directives D4)`

- **Selected**: `plans` carries per-class plan records and the default evaluator content: the identity fields `canonicalRuntimePlanContent`, `semanticDefaultContents`, `evaluatorImplementationContents` (adopted D1), generated from the compile-side `runtimeDefaultPlans`/`canonicalPlanContent`/`planDigest` and `compilerDefaultPlan` (directives D4). The runtime retains the plans per class, exports them under the plan-entry key convention, and never invokes any evaluator during load (adopted D1 "retains plans without invoking them"; adopted D6). DEAL-side C-struct construction and inbound struct results consume `__rt.class_plan_` per realization D6; the entry is the ISSUE-0276 tree's deliverable and is neither realized nor re-pinned by this epic.
- **Evidence**: adopted D1/D6; directives D4/D7; realization D6; `deal/runtime.lua:775-777` (entry still a forward reference).
- **Alternative**: runtime invokes evaluators during load to precompute defaults.
- **Rejected because**: adopted D6 pins zero load-time evaluation; eager evaluation violates per-construction semantics.

### S6. Binding cell shapes `(pinned by adopted D1/D6 and directives D7)`

- **Selected**: `bindings = FfiForwardBindings(moduleKey, state, sameModuleCells, importedFunctions, importedClassPlans)` with `sameModuleCells: Map<exportName, ForwardFunctionCell>` and `ForwardFunctionCell.get() -> typed DEAL wrapper | module initialization error`; imported references are graph-ordered wrappers/class plans (directives D7; adopted D6 "Imported references use graph-ordered wrappers/class plans"). Cell states: `UNBOUND → BINDING → READY` (adopted D6); `FAILED` carries the cached initialization error (directives D7; adopted D6 "FAILED re-raises cached initialization error"). Cells fill only after all symbols/wrappers exist; export publication/readiness is serialized (adopted D6). Ready replay validates fresh binding names/descriptors before mutation and atomically installs cached wrappers and READY; incompatible or non-UNBOUND bindings fail before mutation (adopted D1 transition 5). Invocation at READY dereferences current typed resources each time; UNBOUND/BINDING invocation yields the cached `FFI_LIBRARY_LOAD` and invokes no wrapper (adopted D6).
- **Evidence**: adopted D1/D6; directives D7.
- **Alternative**: cells store raw addresses.
- **Rejected because**: adopted D6 pins typed resources and wrapper indirection; raw addresses bypass wrappers/plans.

### S7. What the settled seam deliberately does not pin `(architect-decided)`

- **Selected**: three items remain realization/generator mechanics, not seam semantics: (a) literal LuaJIT cdef ctype spellings — the adopted authority pins ABI types (`int32_t`, `double`, `_Bool`, `void*`, `const uint8_t*` plus `int32_t` length), not literal ctype text; the generator/E2 may emit any LuaJIT-legal spelling of the pinned ABI types; (b) the internal storage layout of pointer tokens — adopted D4 pins observable behavior (outbound exact nominal non-null token to `void*`; inbound NULL → `FFI_NULL_POINTER`, otherwise a fresh declared-identity token; DEAL-opaque per `docs/spec-v1.2.md:1822-1831`); where the raw address lives inside the token is E2's implementation detail; (c) compile-side serializer formats — FFIGEN-owned, excluded by the objective boundary.
- **Evidence**: adopted D4–D5 text (no ctype spelling, no token layout); objective Boundary (no FFIGEN authoring).
- **Alternative**: pin literal ctype strings and token layout here.
- **Rejected because**: the objective requires settlement "with the landed content only"; inventing layout would re-derive semantics the authority did not pin.

## Contracts

### Joint-consumption contract (E2 runtime half and E4 FFIGEN integration)

- **Initiator/input**: E4 — a generated `// @extern-c` import emission `local <alias> = __rt.load_ffi(<moduleKey>, <cdefBundle>, <plans>, <bindings>, "<file>", <line>, <column>)` with generated wrapper call sites `wrapper(v1, ..., vN, file, line, column)`; E2 — the `test_runtime.lua` battery with generated-shape inputs in these exact shapes.
- **Success**: both consumers use the identical shapes S1–S6; a ready module yields typed wrappers and retained plans published atomically; zero default evaluation.
- **Visible errors**: shape divergence manifests through the adopted error surface — identity mismatch fails `FFI_LIBRARY_LOAD` before mutation (adopted D1 transition 2) — never through a seam-specific code. E4's acceptance pins "a divergent sibling shape fails the integration at load" (ISSUE-0425 record).
- **Post-state**: on failure no exports, cached error, preserved cdefs, single close of a failed opened handle (adopted D1–D2); on success the adopted ready state.
- **Retries/concurrency/timeout**: none at the seam; replay follows adopted D1 transitions.

## Failure and operations

Applicable concerns: identity-content change must fail replay before mutation (adopted D1 transition 2) — the `runtimeAbiVersion` field plus the full canonical contents guard this, so any runtime-ABI-incompatible change must bump `runtimeAbiVersion` or replay fails at the identity gate (adopted D1). Observability: no new signals — the six adopted codes are the only runtime signals. Deployment/migration: the seam migrates as one destructive v1.2 unit with `deal/runtime.lua` and generated artifacts (realization "Failure and operations"); no separate rollout exists.

## Verification

1. Shape-settlement check: every element named in the objective's seam list is settled by S1–S6 with a citation to the adopted text or the directives records; S7 names the only deliberately unpinned items.
2. Joint-consumption check: E2 and E4 cite this page as the settled seam; their divergence tests use the shapes here.
3. No-invention check: no record shape beyond adopted D1–D2/D6 pins and directives D4/D5/D7 is introduced.

## Requirement traceability

| Objective requirement | Resolution |
|---|---|
| exact seam shapes of `cdefBundle`/`plans`/`bindings` settled | S1–S6 |
| settled and recorded for joint consumption by E2 and E4 | Joint-consumption contract; ISSUE-0423/ISSUE-0425 named |
| settled with the landed content only | S7 and the per-decision evidence (adopted pins + directives records cited) |
| no compiler or manifest dependency | S1/S3/S4 (rejections) |
| canonical descriptors only | S4 |
| no FFIGEN/REGISTRY authoring | S7(c); objective Boundary |

## Production-domain decomposition

| Domain | State |
|---|---|
| Generated-content seam shapes (`cdefBundle`/`plans`/`bindings`) | **Resolved here:** S1–S6 settle the shapes; S7 names the unpinned mechanics |
| Compile-side serializer formats and `LuaFfiBindingGenerator` emission | excluded — FFIGEN child, objective Boundary |
| Runtime realization consuming the seam | excluded — E2 (ISSUE-0423), objective Boundary |

## Wiki audit

| Relevant page | Status | Result |
|---|---|---|
| `deal-v1.2-luajit-c-ffi-runtime-and-conformance` | verified | D1 (`:44`) identity fields, D2 (`:86`) generated-field shape, D6 (`:146`) cell states exist as quoted and match `docs/spec-v1.2.md:1729-1736,1822-1855,1999-2005,2916-2925`; stable since 2026-08-21 in `../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md` |
| `luajit-ffi-runtime-realization` | disputed | Its D4 marked the seam shapes "gated on D1–D7" pending the landing; the landing is now verified and this page settles the shapes. Preserved read-only; its five-position envelope is consumed here as pinned |
| `luajit-ffi-runtime-half` | verified | Its `load_ffi` boundary contract and sibling FFIGEN contract match the settled shapes; the adopted D1–D7 govern its non-normative summaries |
| `deal-v1.2-directives-and-c-ffi-declarations` | verified | D4/D5/D7 compile-side records cited here match the page's text; the E6003 identifier dispute is outside D1–D7 and unaffected |
| `luajit-v1.2-emitter-and-lowering` | verified | D6 pins the identical `load_ffi` call shape and the no-`ffi.C[target]` rule the settled seam consumes |
| `luajit-ffi-d1-d7-adoption` | verified | Its Areas 1–6 and A2 carry the adopted semantics and supersession this page applies |
| `luajit-ffi-generated-content-seam` | new | This page |
