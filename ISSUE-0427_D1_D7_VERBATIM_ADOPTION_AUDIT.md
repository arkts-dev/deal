# ISSUE-0427 — D1–D7 Verbatim-Adoption Audit Record (five gated areas)

Audit record. Purpose: verify that the five gated semantic areas are settled
by verbatim quotation of the landed D1–D7 with decision-anchor citations,
that no guessed or gated-away semantics remain anywhere in the FFI half's
design records, and that every recorded conflict resolves to the landed
D1–D7 as winner (lean epic criterion 2). The audit was performed by direct
comparison of each "Adopted semantics" Area block in
`luajit-ffi-d1-d7-adoption` against the landed page file
`../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md` at the
cited anchors — never by trusting the adoption page's own claim.

Verdict: **all checks pass; zero deviations; no correction of the adoption
page was required.** Each of the five Area blocks carries the landed
normative text byte-for-byte at its cited decision anchor; the settlement
map names all five gated areas with zero unmapped; every D1–D7 gated marker
in `luajit-ffi-runtime-realization` and `luajit-ffi-runtime-half` is named
in the adoption page's A2 supersession record; and every recorded conflict
resolves to the landed D1–D7 as winner.

## 1. Comparison inputs and the T1 dependency

T1's recorded landing evidence is `ISSUE-0426_D1_D7_LANDING_EVIDENCE_RECHECK.md`
(committed at the canonical revision `9117661`, section 1): the landed page
exists at `../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md`
(419 lines), front matter `created`/`updated` `2026-08-21T10:22:44Z`, file
mtime 2026-08-21 13:22 (+03:00) unchanged since, D1–D7 headers at
`:44`/`:86`/`:109`/`:115`/`:133`/`:146`/`:154`, D8–D12 following at
`:160`/`:168`/`:203`/`:225`/`:257`. The comparison log's anchor column is
filled from that record (and from the adoption page's "Landing verification"
item 1, which T1 re-verified). T1's record supports every anchor below;
had it been absent or wrong, this audit could not cite real anchors.

Anchor re-verification at audit time (independent of both records):
`grep -n '^### D'` on the landed page returned exactly D1 `:44`, D2 `:86`,
D3 `:109`, D4 `:115`, D5 `:133`, D6 `:146`, D7 `:154`, D8 `:160`, D9 `:168`,
D10 `:203`, D11 `:225`, D12 `:257` — identical to T1's recorded lines.

Method: each Area block was extracted from the adoption page by line range;
each landed decision body was extracted by line range with the decision
header, blank separators, and the decision's own Evidence/Alternative tail
excluded (the adoption page's stated quoting policy: "the landed page's own
Evidence/Alternative reasoning stays there and is not duplicated"). Each
pair was compared byte-for-byte with `cmp`; any mismatch would have been
shown with `diff`. Two extraction ranges were boundary-corrected once during
audit setup (Area 1's last line and Area 3's D3 paragraph; neither involved
any file content change), after which the final comparisons below are the
only comparisons and are all byte-identical.

## 2. Block-by-block comparison log

| # | Area block (adoption page) | Landed anchor(s) (from T1) | Landed lines | Adoption lines | Result |
|---|---|---|---|---|---|
| 1 | Area 1 — identity record block, `runtimeAbiVersion` paragraph, module-cache transitions 1–5, cdef registration rules 1–6, post-registration paragraph | D1 `:44` | 46–82 | 74–110 | **IDENTICAL (37 lines)** |
| 2 | Area 2 — generated-bundle sentence, `FfiGeneratedField` block, metadata/keyword paragraph | D2 `:86` | 88–95 | 116–123 | **IDENTICAL (8 lines)** |
| 3 | Area 3 — resolver ownership sentence, `open/resolve/close` block, dlopen/dlsym/dlerror mechanics paragraph | D2 `:86` | 97–105 | 127–135 | **IDENTICAL (9 lines)** |
| 4 | Area 3 — `NativeLibraryRef` defense/failure-mapping paragraph | D3 `:109` | 111 | 137 | **IDENTICAL (1 line)** |
| 5 | Area 4 — converter bullets INT/NUMBER/BOOLEAN/STRING param/STRING return/BYTES/NULL/C_STRUCT/C_POINTER plus canonical-descriptor paragraph | D4 `:115` | 117–129 | 141–153 | **IDENTICAL (13 lines)** |
| 6 | Area 4 — DEAL field/C member table plus source-order/unpublished-slot paragraph | D5 `:133` | 135–142 | 155–162 | **IDENTICAL (8 lines)** |
| 7 | Area 5 — cell-fill/readiness paragraph and invocation-at-READY paragraph | D6 `:146` | 148, 150 | 166, 168 | **IDENTICAL (2 lines)** |
| 8 | Area 5 — single-call/non-owning paragraph | D7 `:154` | 156 | 170 | **IDENTICAL (1 line)** |

Total compared: 79 lines across 8 pairs; **0 differing lines**. Every
comparison shows the block identical, so no correction was applied and no
re-run identity is required.

First and last line of each compared pair (both sides identical; quoted
once per pair):

1. First: `` ```text `` — Last: `After registration, \`load_ffi\` opens the library, resolves all symbols, retains plans without invoking them, builds wrappers, binds cells, marks ready, and publishes atomically. Re-entry/identity/config/cdef/open/readiness failures are \`FFI_LIBRARY_LOAD\`; missing symbols are \`FFI_SYMBOL_MISSING\`.`
2. First: `Generated bundles never declare a real target function prototype. Private names include full module/signature digest and declaration ordinal. Struct fields are source-order generator names:` — Last: `Metadata maps DEAL names to ordinals; valid DEAL names that are C keywords cannot poison cdefs.`
3. First: `A \`PosixNativeSymbolResolver\` owns one registry-protected cdef entry for \`dlopen\`, \`dlsym\`, \`dlerror\`, and \`dlclose\`:` — Last: `\`open\` calls \`dlopen(UTF8(name), RTLD_NOW | RTLD_LOCAL)\`. \`resolve\` clears \`dlerror\`, calls \`dlsym\` on the retained exact handle/symbol, then checks \`dlerror\`. It never uses \`ffi.C[target]\` or \`ffi.load(...)[target]\`. All addresses resolve before casting to module-private function-pointer types. Provider/open/lookup/error-copy/cast operations are protected/translated. Messages contain module/library/symbol but no addresses. A ready module retains its handle for runtime lifetime; failure after open closes once after discarding unpublished wrappers.`
4. First = Last: `Generated loader input is decoded/classified \`NativeLibraryRef\`. Runtime defense requires a non-empty valid UTF-8 Lua string with no NUL before \`dlopen\`. Defense/provider/open/architecture/format/permission/OS failures are cached \`FFI_LIBRARY_LOAD\`; missing target symbols after open are \`FFI_SYMBOL_MISSING\`. No raw LuaJIT/resolver exception escapes.`
5. First: `Arguments evaluate left-to-right, validate before narrowing, and remain live through result conversion:` — Last: `All descriptors come from \`CanonicalRuntimeTypeDescriptor\`; checks use \`RuntimeTypeMatcher\`. Missing C-string termination or an invalid trusted pointer is a native contract violation, not \`FFI_INVALID_UTF8\`.`
6. First: `| DEAL field | C member | DEAL→C | C→DEAL |` — Last: `Fields process in source order. A parameter converts fully to one unpublished by-value temporary before the call; failure prevents native effects. A result converts after the call into unpublished DEAL slots under original names; failure publishes no class while native effects remain. Success publishes one fresh normal class. Normal construction uses the common default plan.`
7. First: `Imported references use graph-ordered wrappers/class plans. Same-module cells fill after all symbols/wrappers exist. Transition is \`UNBOUND → BINDING → READY\`; export publication/readiness is serialized. \`load_ffi\` retains but never invokes evaluators.` — Last: `Invocation is legal only at READY and dereferences current typed resources each time. FAILED re-raises cached initialization error. UNBOUND/BINDING becomes cached \`FFI_LIBRARY_LOAD\` and invokes no wrapper. Exact ready replay follows D1.`
8. First = Last: `A wrapper performs exactly one native call. There is no language timeout, cancellation, retry, or errno. Temporary strings and borrowed bytes remain live through result conversion. Struct arguments are copies. Returned string/pointer memory remains C-owned; cleanup requires an explicit FFI function. Native effects are not rolled back.`

Coverage in both directions: every normative line of the landed D1–D7
decision bodies (lines 46–158 excluding decision headers, blank separators,
and the Evidence/Alternative tails at `:84`, `:107`, `:113`, `:131`, `:144`,
`:152`, `:158`) appears in exactly one Area block; every quoted content line
of Areas 1–5 is byte-identical landed text. The only non-quoted lines inside
the Area blocks are the adoption page's own navigational framing (Area
headings with anchor citations; Area 2's first sentence, which names the
Area-1 record lines as the certainty-state carrier and flags the D2 quote;
the "Adopted semantics" section intro), which adds no semantic content.

## 3. Settlement-map completeness

The five gated areas named by `luajit-ffi-runtime-realization` D1
(`:44`, "**gated on ISSUE-0163 D1–D7 — not decided here**" enumeration):

| # | Realization D1 gated area | Settlement-map row (adoption page `:176-180`) | Carried at | Anchor citation |
|---|---|---|---|---|
| 1 | module-cache identity fields and replay/ready/failed transition mechanics | row 1 — exact name match | Area 1 | `(landed D1, \`:44\`)` |
| 2 | cdef certainty registry states and transitions | row 2 — exact name match | Areas 1–2 | `(landed D1–D2, \`:44\`, \`:86\`)` |
| 3 | handle-scoped resolver open/resolve/close detailed behavior and error mapping | row 3 — same area (realization adds "detailed behavior") | Area 3 | `(landed D2–D3, \`:86\`, \`:109\`)` |
| 4 | the per-kind ABI converter tables (INT/NUMBER/BOOLEAN/STRING/BYTES/NULL/C_STRUCT/C_POINTER) | row 4 — exact name match | Area 4 | `(landed D4–D5, \`:115\`, \`:133\`)` |
| 5 | deferred-default, readiness, and wrapper-call transition mechanics (cell states, deferred-default callability) | row 5 — exact name match | Area 5 | `(landed D6–D7, \`:146\`, \`:154\`)` |

Zero unmapped areas. No Area is claimed to settle an area it does not
quote: Area 1 quotes D1 only; Area 2 quotes D2's generated-content half and
resolves the D1 record lines by cross-reference to the byte-identical Area 1
quote (the adoption page's framing names this, and section 2 proves the
cross-referenced lines are verbatim); Area 3 quotes D2's resolver half and
D3's full body; Area 4 quotes D4 and D5; Area 5 quotes D6 and D7. The
realization Context "Gate status" paragraph's five-area list (`:19`) uses
the same five names with the same D1–D7 citations and is therefore also
covered by the map.

## 4. Supersession completeness — marker inventory

Every "gated on D1–D7" / "not decided here" / "has not landed" /
"non-normative summary" marker in the two consumed boundary pages, with its
recorded supersession:

### Realization page (`luajit-ffi-runtime-realization.md`)

| Line | Marker | Recorded at |
|---|---|---|
| `:19` | Context "Gate status (authoritative)": "**has not landed**"; "marks the five D1–D7 semantic areas as **gated on ISSUE-0163 D1–D7 — not decided here**" | A2(a) — "the realization page's 'Gate status' paragraph … superseded" |
| `:42`, `:44` | D1 heading "the five semantic areas are gated" and body "**gated on ISSUE-0163 D1–D7 — not decided here**: (1)–(5)" | A2(a) — "D1's gate-open status … superseded" |
| `:56`, `:58` | D3 heading "transition mechanics gated"; body "The exact cache transitions, certainty states, readiness advancement, and handle-retention-on-success mechanics are gated on D1–D7 (D1)." | A2(b) — names D3 |
| `:63`, `:65` | D4 heading "exact field shapes gated"; body "The exact field shapes of `cdefBundle`/`plans`/`bindings` … are gated on D1–D7 and are settled when the authority lands" | A2(b) — names D4 |
| `:86`, `:88` | D7 heading "runtime token representation gated"; body "The runtime-side token representation … is gated on D1–D7 (D1)" | A2(b) — names D7 |
| `:93`, `:95` | D8 heading "detailed behavior gated"; body "The detailed open/resolve/close behavior … is gated on D1–D7 (D1)." | A2(b) — names D8 |
| `:111` | Architecture intro: "Where a component's mechanics are gated on D1–D7, the gated area is named explicitly" | A2(b) — names Architecture items 2–6 (the intro's named areas) |
| `:114` | Architecture item 2 "Gated (D1): record shape and identity fields, ready/failed transition mechanics." | A2(b) — names Architecture items 2–6 |
| `:115` | Architecture item 3 "Gated (D1): certainty states and transition mechanics." | A2(b) — names Architecture items 2–6 |
| `:116` | Architecture item 4 "Gated (D1): open/resolve/close detailed behavior and error mapping." | A2(b) — names Architecture items 2–6 |
| `:117` | Architecture item 5 "gated (D1): the per-kind conversion tables' mechanics (ctype strings, temporary lifetimes beyond the spec-pinned rules, struct ordinal layout, pointer-token representation)." | A2(b) — names Architecture items 2–6 |
| `:118` | Architecture item 6 "Gated (D1): cell state machine and readiness transition mechanics." | A2(b) — names Architecture items 2–6 |
| `:128` | Contract "`load_ffi` boundary" Post-state: "explicit retention mechanics gated on D1–D7" | A2(b) — names Contracts |
| `:132` | Contract "Generated-content seam": "The exact field shapes of `cdefBundle` … `plans` … `bindings` (cell shapes) are gated on D1–D7 and settled when the authority lands (D4)." | A2(b) — names Contracts |
| `:139` | Contract "Wrapper call": "Pre-READY cell invocation behavior is gated on D1–D7 (readiness transitions)." | A2(b) — names Contracts |
| `:148` | Contracts "Converters" heading "(module-private; pinned observable behavior, mechanics gated)" | A2(b) — names Contracts |
| `:156` | Converters C_POINTER bullet "(spec token rules; representation gated)" | A2(b) — names Contracts |
| `:166` | Failure and operations: "ready-module handle retention mechanics are gated on D1–D7"; "its defense details are gated on D1–D7"; "the identity field list itself is D1's, gated" | A2(b) — names Failure and operations |
| `:181` | Requirement traceability: "D1 — the gate is open; adoption is sequencing step 1 (blocker)…" | A2(a) — restatement of D1's gate-open status |
| `:192` | Explicit exclusions: "the D1–D7 semantic decisions themselves (ISSUE-0163 — consumed only, once landed)" | A2(a) — restatement of D1's status; now adopted (Areas 1–5) |
| `:198` | Production-domain decomposition: "The five D1–D7-gated semantic areas (D1) are adopted verbatim at sequencing step 1 once ISSUE-0163 lands; they are not re-decided or guessed here (open blocker, not a delegation)" | A2(a) — restatement of D1's status; the five areas are now carried in Areas 1–5 |
| `:207` | Wiki audit row (landed page) "disputed … 'has not landed' … consumed only as the future semantic authority — adopted verbatim at sequencing step 1 once the landing is verifiable" | A2(a) + adoption-page Wiki audit row for the realization page + Landing verification item 5; winner: the verified authoritative record (section 5, conflict 4) |
| `:208` | Wiki audit row (half page): "Its delegation to D1–D7 remains open (ISSUE-0163 has not landed)" | A2(a) for the "has not landed" claim; the delegation is discharged by Areas 1–5 (section 5, conflict 4) |
| `:211` | Wiki audit row (directives page): "Its detailed metadata seam shapes are not adopted as decided here (gated on D1–D7)" | A2(b) — its universal phrase "every 'gated on D1–D7' marker in the realization design" covers this literal phrase; the named D4/Contracts markers carry the same seam-shape gating, and the adoption page's own directives audit row ("match the adopted identity/content pins") plus the seam page resolve its substance |

### Half page (`luajit-ffi-runtime-half.md`)

| Line | Marker | Recorded at |
|---|---|---|
| `:96` | Architecture closing after items 2–6: "Any state name, transition, or conversion-row detail beyond those behaviors (cache states, cdef certainty states, readiness transitions, per-kind ABI rows) is a non-normative summary of `deal-v1.2-luajit-c-ffi-runtime-and-conformance` D1–D7; that page is the semantic authority and wins on any conflict." | A2(c) — "the half page's 'non-normative summary' qualification of its Architecture items 2–6 is superseded — the adopted D1–D7 text now governs those behaviors" |
| `:57` | D3: "FFI loading is gated by the parent's identity/cache contracts" | Not a D1–D7 semantic marker: it means access-gating by the identity/cache contracts, which is consistent with adopted D1 (module-cache gate). Not a supersession target |
| `:172` | Wiki audit row (landed page) "disputed" — "Dispute limited to its E6003 incapable-backend identifier" | A2: "The E6003/E6006 numeric diagnostic identifier dispute recorded in those pages' audits concerns the landed page's D8/D12 and Verification 11 — outside D1–D7 — and is recorded out of scope (A4), not re-litigated." |
| `:175` | Wiki audit row (directives page) "disputed" — "Dispute limited to the E6003 identifier in D6/D8/D9" | same A2 out-of-scope record |
| `:162-166` | Production-domain decomposition rows "**Delegated:** … D1 / D1–D2 / D2–D3 / D4–D5 / D6–D7, tracked as ISSUE-0163" (five rows) | Not "gated"/"not decided here" markers; the delegation is discharged by the adoption page's Areas 1–5, which now carry the delegated authority verbatim |

Distinct gates that remain open and are NOT D1–D7 markers (verified as
correctly out of this audit's supersession scope): the ISSUE-0276
`__rt.class_plan_` landing gates at realization `:102` and `:172` (tracked
by ISSUE-0424/E3); the deep-matrix verification-ownership statement at
realization `:102` ("the deep D1–D7 semantic matrix remains ISSUE-0163's
verification"); and the E6003/E6006 identifier dispute (outside D1–D7).

**Zero unrecorded D1–D7 markers.** After A2, no design record leaves a
D1–D7 gated marker active: every marker maps to A2(a), A2(b), or A2(c), and
the two audit "disputed" rows that do not concern E6003 (realization `:207`,
`:208`) are resolved by A2(a) plus the adoption page's own audit row and
Landing verification item 5.

## 5. Conflict-direction enumeration

Every recorded conflict, with the sentence that makes the landed D1–D7 the
winner (quoted from the adoption page, A2 Selected unless noted):

1. General rule (covers every conflict between the adopted text and a
   statement in the two boundary pages): "On any conflict between the
   adopted text and a statement in `luajit-ffi-runtime-realization` or
   `luajit-ffi-runtime-half`, the adopted text wins (objective constraint)."
   — winner: adopted (landed) D1–D7.
2. Gate-status conflict (realization "Gate status" paragraph + D1's
   gate-open status): "the realization page's 'Gate status' paragraph and
   D1's gate-open status are superseded — the gate is satisfied" —
   winner: the verified authoritative record (landed D1–D7); the gate is
   satisfied by the landing this adoption verifies.
3. Gated-marker conflict: "every 'gated on D1–D7' marker in the realization
   design (D3, D4, D7, D8, Architecture items 2–6, Contracts, Failure and
   operations) is superseded by the adopted text here" — winner: adopted
   text.
4. Half-page qualification conflict: "the half page's 'non-normative
   summary' qualification of its Architecture items 2–6 is superseded —
   the adopted D1–D7 text now governs those behaviors" — winner: adopted
   D1–D7 text.
5. Realization and half pages' "disputed"/"not landed" audit rows: the
   adoption page's own Wiki audit row for the realization page records
   "Its gate-status claim ('has not landed'; five areas 'gated … not
   decided here') contradicts the now-verified authoritative record.
   Preserved read-only; this page supersedes its gated markers (A2)." —
   winner: the now-verified authoritative record (the landed D1–D7 page);
   combined with Landing verification item 5, which explains the prior
   "not landed" record as a checkout-limitation artifact.
6. E6003/E6006 numeric-identifier disputes (the two half-page "disputed"
   audit rows and the realization directives audit row's E6003 tail):
   "The E6003/E6006 numeric diagnostic identifier dispute recorded in those
   pages' audits concerns the landed page's D8/D12 and Verification 11 —
   outside D1–D7 — and is recorded out of scope (A4), not re-litigated." —
   the adopted D1–D7 pins no numeric code and is not contradicted; the
   dispute is not awarded to either boundary page.

No resolution awards a boundary-contract statement over the adopted text:
in the 13-row invariant table, the realization-envelope statements (span
carrier, non-yield rule, runtime-parses-nothing rule) are kept only where
the adopted text does not contradict them, and every row's semantic core is
cited to the adopted D1–D7 text ("adopted D1…", "adopted D2 (exact text)",
"adopted D4…"). The A2 closing check — "No other conflict was found: the
remaining boundary-contract statements in both pages match the adopted
text" — was spot-verified in this audit: the realization D3 pinned
invariants, the Converters contract rows, and the half page's Architecture
items 2–6 boundary statements all match the byte-identical adopted text in
section 2 (e.g. full-content replay and no retry match D1 transitions;
INT/STRING/NULL rows match D4 bullets; cell states match D6–D7).

## 6. Resulting state

After this audit, no semantic area of the FFI half's design remains answered
by guess: the five gated areas are carried verbatim with decision-anchor
citations in `luajit-ffi-d1-d7-adoption` Areas 1–5 (proven byte-identical
in section 2); the settlement map covers all five; every gated marker is
superseded by A2; and every recorded conflict resolves to the landed D1–D7
as winner. The only items that remain open are different gates outside
D1–D7 (ISSUE-0276 `__rt.class_plan_`, E6003/E6006), correctly recorded as
such.

## 7. Verification gate

`./run_tests.sh` — exit 0 at the final rebased commit. Only this record
file is added; no production or test artifact changes, so the gate runs the
same green battery as the pre-task baseline.
