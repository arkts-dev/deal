# ISSUE-0429 — Boundary-Contract Invariant Preservation Audit Record (13-row table)

Audit record. Purpose: verify that all boundary-contract invariants are
preserved under the adopted D1–D7 semantics — the 13-row table in
`luajit-ffi-d1-d7-adoption` is complete and each row's cited adopted text
actually preserves its invariant, with every recorded contradiction
resolved to the landed D1–D7 as winner (lean epic criterion 4). The audit
was performed by direct reads of the four authoritative records — never by
trusting the adoption page's own table summary:
`../../forge/wiki/luajit-ffi-d1-d7-adoption.md`,
`../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md` (the
landed page),
`../../forge/wiki/luajit-ffi-runtime-realization.md`, and
`../../forge/wiki/luajit-ffi-runtime-half.md`.

Verdict: **all checks pass; zero deviations; no correction of the adoption
page was required.** The table has exactly 13 rows covering every invariant
the boundary contract names; each row's cited adopted text is verbatim
landed D1–D7 text at its decision anchor and preserves its invariant; every
recorded conflict resolves to the landed D1–D7 as winner; and no unresolved
contradiction remains between a boundary-contract invariant and the adopted
text.

## 1. Comparison inputs and the T2 dependency

T2's recorded adoption audit is `ISSUE-0427_D1_D7_VERBATIM_ADOPTION_AUDIT.md`
(committed at the canonical revision `3070559`): its section 2 proves, by
byte-for-byte `cmp` against the landed page, that every normative line of
the landed D1–D7 decision bodies appears verbatim in the adoption page's
Areas 1–5, and its section 5 enumerates the recorded conflict resolutions.
T1's landing evidence is `ISSUE-0426_D1_D7_LANDING_EVIDENCE_RECHECK.md`
(committed at `9117661`): it re-verified the landing locators (page
existence/stability, decision anchors, spec cross-checks). The per-row
quotes below are drawn exclusively from T2's byte-identical comparison
pairs (its section 2) or re-read directly from the landed page in this
audit; the two quotes outside T2's compared ranges — the landed page's
"Failure and operations" body (`:330`, row 1) and the D7 decision header
(`:154`, row 4; T2 pair 8 byte-compares only landed line 156 and T2's
coverage note explicitly excludes decision headers from the byte
comparison) — are each flagged at their citing rows as outside T2's
byte-verification coverage, with their provenance named (directly re-read
here; the `:154` header additionally verified by T1's header-line read at
landed `:154`). T2's record
supports every anchor used below; had it been absent or wrong, this audit
could not cite verbatim-verified anchors.

T2's comparison pairs (its section 2), reused as the anchor authority here:

| T2 pair | Landed decision | Landed lines | Adoption lines |
|---|---|---|---|
| 1 | D1 `:44` | 46–82 | 74–110 |
| 2 | D2 `:86` (generated half) | 88–95 | 116–123 |
| 3 | D2 `:86` (resolver half) | 97–105 | 127–135 |
| 4 | D3 `:109` | 111 | 137 |
| 5 | D4 `:115` | 117–129 | 141–153 |
| 6 | D5 `:133` | 135–142 | 155–162 |
| 7 | D6 `:146` | 148, 150 | 166, 168 |
| 8 | D7 `:154` | 156 | 170 |

Anchor re-verification at audit time (independent of both records):
`grep -n '^### D'` on the landed page returned D1 `:44`, D2 `:86`, D3
`:109`, D4 `:115`, D5 `:133`, D6 `:146`, D7 `:154` — identical to T2's and
T1's recorded anchors.

## 2. Completeness mapping — 13 invariants, 13 rows

The adoption page's table header is at `luajit-ffi-d1-d7-adoption.md:190`;
its 13 data rows are `:192`–`:204`. Each row is shown against the
boundary-contract source that names the invariant, quoted from the page
files read in this audit. The mapping has exactly 13 entries; a
boundary-contract invariant without a row would have failed this audit
(none did).

| # | Invariant | Boundary-contract source (quoted) | Table row (adoption page) |
|---|---|---|---|
| 1 | six codes via `_err` with forwarded spans | realization `:72`: "all six codes surface through `_err` with the DEALRuntimeError shape"; realization `:166`: "every load-phase and call-phase failure raises a DEAL error via `_err` with code, message, and the forwarded span"; half `:50`: "All six are runtime `Error.code` strings carried by the DEALRuntimeError shape built with `_err`, with the span the call site forwards." | `:192` "six codes via `_err` with forwarded spans" |
| 2 | loader codes at the import span; conversion codes only at wrapper-call spans | realization `:72`: "Loader codes (`FFI_LIBRARY_LOAD`, `FFI_SYMBOL_MISSING`) carry the `load_ffi` import span; conversion codes carry the spans the generated wrappers pass."; realization `:166`: "conversion codes surface only from wrapper-call spans"; half `:50`: "those errors carry the `load_ffi` call's span (the import site). The four conversion codes arise at wrapper-call conversion … and carry the spans the generated wrappers pass." | `:193` "loader codes at the import span; conversion codes only at wrapper-call spans" |
| 3 | no conversion code from `load_ffi` | realization `:72`: "No conversion runs during load (plans retained, evaluators never invoked), so no conversion code can originate from `load_ffi` itself."; half `:50`: "No conversion runs during load (zero default evaluation, no wrapper invocation), so no conversion code can originate from `load_ffi`." | `:194` "no conversion code from `load_ffi`" |
| 4 | serialized synchronous non-yielding initialization | realization `:58`: "Pinned invariants (boundary contract and acceptance criteria): serialized synchronous initialization, no coroutine yield, no module state observable mid-load"; realization `:129`: "serialized synchronous initialization, no coroutine yield"; half `:64`: "`load_ffi` runs synchronously to completion without any coroutine yield, so initialization is serialized within the single-threaded LuaJIT VM" | `:195` "serialized synchronous non-yielding initialization" |
| 5 | atomic export publication | realization `:126`: "exports (wrappers under DEAL export names plus the retained C-struct plan entries per class) published atomically"; realization `:118`: "cell fill and export publication happen only after every symbol/wrapper exists (atomic publication)"; half `:104`: "exports (wrappers + retained plans) published atomically" | `:196` "atomic export publication" |
| 6 | full-content replay only (SHA-256 index only) | realization `:65`: "Identity equality is full-content equality over the complete initialization identity; no partial comparison ever decides replay"; realization `:114`: "full-content identity equality decides replay, never partial comparison"; half `:64`: "Re-entry is resolved by full-content identity equality only …, never by partial comparison." | `:197` "full-content replay only" |
| 7 | no retry and no timeout | realization `:129`: "no retry; no timeout."; realization `:166`: "Recovery: no retry — the cached error is re-raised on failed replay … no language timeout exists"; half `:64`: "There is no retry and no timeout."; half `:107`: "no retry; no timeout" | `:198` "no retry, no timeout" |
| 8 | zero default evaluation during load | realization `:79`: "`load_ffi` retains the C-struct default plans and exports them per class …, never invoking them during load."; realization `:126`: "zero default evaluation"; half `:71`: "the `plans` argument carries the C-struct default plans …, which `load_ffi` retains and exports without ever invoking." | `:199` "zero default evaluation" |
| 9 | failure publishes no exports, caches the error, preserves registered cdefs, closes a failed opened handle once | realization `:58`: "failure publishes no exports and caches the error, registered cdefs are preserved on failure, a failed opened handle is closed once"; realization `:128`: "failure publishes no exports, caches the error value in the module record, marks the module failed …, preserves registered cdefs, and closes a failed opened handle once after discarding unpublished wrappers"; half `:106`: "failure publishes no exports, caches the error, fails cells, preserves registered cdefs, and closes a failed opened handle once" | `:200` "failure publishes no exports, caches the error, preserves registered cdefs, closes a failed opened handle once" |
| 10 | one registry-protected cdef entry for `dlopen`/`dlsym`/`dlerror`/`dlclose` with private casts | realization `:95`: "the resolver registers exactly one cdef entry — private names for `dlopen`/`dlsym`/`dlerror`/`dlclose` — through the cdef certainty registry's protected registration path … cast with `ffi.cast` to module-private function-pointer types"; half `:92`: "one registry-protected cdef entry for `dlopen`/`dlsym`/`dlerror`/`dlclose`; `open`/`resolve`/`close` on retained exact handles; all addresses resolve before private function-pointer casts" | `:201` "one registry-protected cdef entry for `dlopen`/`dlsym`/`dlerror`/`dlclose` with private casts" |
| 11 | never `ffi.C[target]` or `ffi.load(...)[target]` | realization `:95`: "never `ffi.C[target]` and never `ffi.load(...)[target]`."; half `:92`: "never `ffi.C[target]` or `ffi.load(...)[target]`" | `:202` "never `ffi.C[target]` or `ffi.load(...)[target]`" |
| 12 | canonical descriptors only | realization `:65`: "Every descriptor inside is canonical."; realization `:117`: "All descriptor checks go through the canonical `__rt.check_type` matcher …; no parallel check path exists."; half `:138`: "every descriptor the FFI half consumes is canonical" | `:203` "canonical descriptors only" |
| 13 | no compiler or manifest dependency | realization `:65`: "The runtime never re-derives metadata by parsing compiler serialization formats and never reads manifests (negative constraints)."; realization `:120`: "`deal.runtime` never depends on the compiler, never reads manifests, and never discovers libraries."; realization `:173`: "the FFI half adds no compiler dependency and no manifest/discovery code"; half `:57`: "The runtime never reads manifests, never discovers libraries, never depends on the compiler, and accepts no untrusted configuration" | `:204` "no compiler or manifest dependency" |

Completeness scan beyond the 13 rows: every other pinned behavior of the
boundary contract is either (a) pinned identically by the adopted text
itself — exactly one synchronous native call (realization `:136`; half
`:111`) vs landed D7 `:156` "A wrapper performs exactly one native call.";
left-to-right evaluation, validate before narrowing (realization `:136`;
half `:111`) vs D4 `:117` "Arguments evaluate left-to-right, validate
before narrowing"; temporaries/buffers live through result conversion
(realization `:136`; half `:113`) vs D7 `:156` "Temporary strings and
borrowed bytes remain live through result conversion."; C-owned string/
pointer returns never freed (realization `:138`; half `:113`) vs D4 `:123`
"never free it" and D7 `:156` "Returned string/pointer memory remains
C-owned"; bytes borrowed read-only during the call (realization `:138`;
half `:113`) vs D4 `:124`; failure prevents native effects / native effects
remain (realization `:137`; half `:112`) vs D5 `:142` — so those are
adopted verbatim rather than preservation candidates; or (b) a
compile-time-only pin outside D1–D7 that the adopted text does not touch
(`FFI_UNSUPPORTED_BACKEND` never a runtime code — realization `:120`, half
`:98`, spec `docs/spec-v1.2.md:2005` — verified uncontradicted). No
boundary-contract invariant is absent from the table.

## 3. Per-row preservation evidence

For each row: the boundary invariant is quoted in section 2; here the cited
adopted sentence is quoted from the landed page at its anchor (verbatim, as
byte-identical in the T2 pair named), followed by the one-sentence
preservation argument. No row's check relies on the table's own summary.

**Row 1 — six codes via `_err` with forwarded spans.** Adopted sentences:
D1 `:82` "Re-entry/identity/config/cdef/open/readiness failures are
`FFI_LIBRARY_LOAD`; missing symbols are `FFI_SYMBOL_MISSING`." (T2 pair 1);
D3 `:111` "Defense/provider/open/architecture/format/permission/OS
failures are cached `FFI_LIBRARY_LOAD`; missing target symbols after open
are `FFI_SYMBOL_MISSING`." (T2 pair 4); D4 `:122` "U+0000 is
`FFI_INVALID_STRING`", `:123` "NULL is `FFI_NULL_STRING` … malformed UTF-8
is `FFI_INVALID_UTF8`", `:127` "inbound NULL is `FFI_NULL_POINTER`" (T2
pair 5); landed "Failure and operations" `:330` "DEAL-visible FFI failures
are builtin Error values and flow through `DEAL_ERROR_CODE`; raw
LuaJIT/resolver errors are translated." (outside T2's pairs — directly
re-read and verified in this audit; T1's spec cross-check verifies the
six-code table at `docs/spec-v1.2.md:2916-2925`: "`FFI_*` codes are runtime
`Error.code` string values"). Argument: the four quoted decisions pin
exactly the six codes, and the `:330` sentence pins failures as Error
values carrying codes, which is exactly the `_err` code-string carrier —
no adopted rule contradicts the carrier, so the invariant is preserved.

**Row 2 — loader codes at the import span; conversion codes only at
wrapper-call spans.** Adopted sentences: D1 `:82` and D3 `:111` (quoted in
row 1 — every load-phase failure maps to the two loader codes; T2 pairs
1, 4); D4 `:122`/`:123`/`:127` (the four conversion codes arise only from
STRING parameter/return and C_POINTER inbound conversion; T2 pair 5); D5
`:142` "A parameter converts fully to one unpublished by-value temporary
before the call; failure prevents native effects. A result converts after
the call into unpublished DEAL slots under original names; failure
publishes no class while native effects remain." (T2 pair 6). Argument:
the adopted text assigns every load-phase failure to the two loader codes
and every one of the four conversion codes to wrapper-call-time conversion
events, so the phase split and the envelope's span routing (import span for
loader codes, wrapper-passed spans for conversion codes) are preserved.

**Row 3 — no conversion code from `load_ffi`.** Adopted sentences: D1 `:82`
"retains plans without invoking them" (T2 pair 1); D6 `:148` "`load_ffi`
retains but never invokes evaluators." (T2 pair 7). Argument: with zero
plan invocation and zero evaluator invocation during load, zero conversion
executes during load, so no conversion code can originate from `load_ffi`
itself.

**Row 4 — serialized synchronous non-yielding initialization.** Adopted
sentences: D7 `:154` header "Calls are synchronous, single-attempt, and
non-owning" (outside T2's compared ranges — T2 pair 8 byte-compares only
landed `:156`, and T2's coverage note excludes decision headers from the
byte comparison; verified by T1's header-line read at landed `:154` and
by direct re-read in this audit) plus `:156` "A wrapper performs exactly
one native call. There is no language timeout, cancellation, retry, or
errno." (T2 pair 8); D1
`:82` "After registration, `load_ffi` opens the library, resolves all
symbols, retains plans without invoking them, builds wrappers, binds cells,
marks ready, and publishes atomically." (the single serialized pipeline;
T2 pair 1); D6 `:148` "export publication/readiness is serialized." (T2
pair 7). Argument: the adopted text pins one synchronous call, a single
non-interleaved load pipeline, and serialized publication, and nothing in
it introduces yielding — the objective-pinned non-yield rule (recorded in
the realization page's own objective-constraint cite `:59` "design D4 —
serialized non-yielding, atomic publication, full-content replay only, no
retry/timeout") stays in force, so the invariant is preserved.

**Row 5 — atomic export publication.** Adopted sentences: D1 `:82` "marks
ready, and publishes atomically." (T2 pair 1); D6 `:148` "Same-module cells
fill after all symbols/wrappers exist. Transition is `UNBOUND → BINDING →
READY`; export publication/readiness is serialized." (T2 pair 7). Argument:
publication occurs only at READY, after every symbol/wrapper exists, and is
itself serialized, which preserves (and strengthens to adopted text) the
atomic-publication invariant.

**Row 6 — full-content replay only (SHA-256 index only).** Adopted
sentences: D1 `:63` "Equality compares full canonical UTF-8 content;
SHA-256 is an index only." (T2 pair 1); D1 transitions 2–5, `:68` "Same key
with changed identity fails `FFI_LIBRARY_LOAD` before cdef/cell/handle/
export mutation." and `:71` "Exact ready replay validates fresh binding
names/descriptors before mutation, then atomically installs cached wrappers
and READY." (T2 pair 1). Argument: equality is pinned to full canonical
content with the digest demoted to an index, and the replay transitions
decide reuse on that full-content equality alone, so replay-by-partial-
comparison is excluded exactly as the boundary contract requires.

**Row 7 — no retry and no timeout.** Adopted sentences: D1 transition 4
`:70` "Exact failed replay fails fresh compatible bindings with the same
error; no retry." (T2 pair 1); D7 `:156` "There is no language timeout,
cancellation, retry, or errno." (T2 pair 8). Argument: the adopted text
pins no-retry in the replay transition and no timeout/cancellation/retry at
the language level, which preserves both halves of the invariant.

**Row 8 — zero default evaluation during load.** Adopted sentences: D1
`:82` "retains plans without invoking them" (T2 pair 1); D6 `:148`
"`load_ffi` retains but never invokes evaluators." (T2 pair 7). Argument:
plans are retained without invocation and evaluators are never invoked by
`load_ffi`, so no default evaluation executes during load.

**Row 9 — failure publishes no exports, caches the error, preserves
registered cdefs, closes a failed opened handle once.** Adopted sentences:
D1 `:54` "`FfiModuleRecord(moduleKey, fullInitializationIdentity, state:
loading | ready(exports,handle,typedWrappers) | failed(errorValue))`" — the
failed state carries only `errorValue`; exports exist only in ready (T2
pair 1); D1 transitions 3–4, `:69` "Exact loading replay is re-entry:
record/cells fail with cached `FFI_LIBRARY_LOAD`." and `:70` "Exact failed
replay fails fresh compatible bindings with the same error; no retry." (T2
pair 1); D1 cdef rule 5 `:79` "A failing call marks the current entry/name
indeterminate, unattempted reservations blocked, module failed, and removes
nothing. Prior registered entries remain usable." (T2 pair 1); D2 `:105`
"A ready module retains its handle for runtime lifetime; failure after open
closes once after discarding unpublished wrappers." (T2 pair 3). Argument:
the failed record shape (no exports field), the cached-error replay rules,
the removes-nothing cdef rule, and the close-once sentence jointly pin each
of the invariant's four clauses — no exports published, error cached,
registered cdefs preserved, failed opened handle closed once.

**Row 10 — one registry-protected cdef entry for
`dlopen`/`dlsym`/`dlerror`/`dlclose` with private casts.** Adopted
sentences: D2 `:97` "A `PosixNativeSymbolResolver` owns one
registry-protected cdef entry for `dlopen`, `dlsym`, `dlerror`, and
`dlclose`" (T2 pair 3); D2 `:105` "All addresses resolve before casting to
module-private function-pointer types." (T2 pair 3). Argument: the adopted
text pins exactly one registry-protected entry and module-private casts,
verbatim, so the resolver-shape invariant is preserved.

**Row 11 — never `ffi.C[target]` or `ffi.load(...)[target]`.** Adopted
sentence: D2 `:105` "It never uses `ffi.C[target]` or
`ffi.load(...)[target]`." (T2 pair 3). Argument: the prohibition is adopted
verbatim, so the negative constraint is preserved.

**Row 12 — canonical descriptors only.** Adopted sentence: D4 `:129` "All
descriptors come from `CanonicalRuntimeTypeDescriptor`; checks use
`RuntimeTypeMatcher`." (T2 pair 5). Argument: the adopted text pins the
single descriptor source and the single matcher, so no non-canonical
descriptor path exists — the invariant is preserved.

**Row 13 — no compiler or manifest dependency.** Adopted sentences: D1
`:48-52` "`FfiInitializationIdentity(runtimeAbiVersion, moduleKey,
nativeLibraryKind, exactNormalizedLoaderText, canonicalFfiDescriptorContent,
canonicalRuntimePlanContent, semanticDefaultContents,
evaluatorImplementationContents, cdefBundleContent, identityDigest)`" —
the loader text and all descriptor/plan/evaluator/cdef content ride inside
the initialization identity as generated content (T2 pair 1); D3 `:111`
"Generated loader input is decoded/classified `NativeLibraryRef`." (T2
pair 4). Argument: the runtime consumes only identity-carried generated
content plus the decoded/classified library reference, and no adopted rule
asks it to parse compiler serialization formats or read manifests, so the
envelope's runtime-parses-nothing/never-reads-manifests rules stand
uncontradicted — the invariant is preserved.

Result of the per-row check: all 13 rows' cited text exists verbatim at the
cited anchors (byte-identical inside T2's pairs, or directly re-read for
`:330` and the D7 `:154` header), and each cited text actually preserves
its invariant. The table's
two envelope-side references resolve as follows: row 2's "(D5)" is the
realization page's D5 span-routing decision (`:72`, quoted in section 2);
row 4's "(D4)" is the objective's recorded design constraint D4
(realization `:59`); row 13's "(D4)" is the realization page's D4 negative
constraints (`:65`). No row relies on a summary in place of the adopted
sentence, and no row cites a statement that contradicts its invariant.

## 4. Conflict-direction confirmation

The adoption page's A2 (`luajit-ffi-d1-d7-adoption.md:47-49`) records the
conflict resolutions; each names the landed D1–D7 as winner:

1. General rule: "On any conflict between the adopted text and a statement
   in `luajit-ffi-runtime-realization` or `luajit-ffi-runtime-half`, the
   adopted text wins (objective constraint)." — winner: adopted (landed)
   D1–D7.
2. Realization gate status: "the realization page's 'Gate status' paragraph
   and D1's gate-open status are superseded — the gate is satisfied" —
   winner: the verified authoritative record (the landed D1–D7 page).
3. Gated markers: "every 'gated on D1–D7' marker in the realization design
   (D3, D4, D7, D8, Architecture items 2–6, Contracts, Failure and
   operations) is superseded by the adopted text here" — winner: adopted
   text.
4. Half-page qualification: "the half page's 'non-normative summary'
   qualification of its Architecture items 2–6 is superseded — the adopted
   D1–D7 text now governs those behaviors" — winner: adopted D1–D7 text.
5. Prior "disputed"/"not landed" audit rows: the adoption page's Wiki audit
   row for the realization page records "Its gate-status claim … contradicts
   the now-verified authoritative record. Preserved read-only; this page
   supersedes its gated markers (A2)." — winner: the now-verified
   authoritative record (landed D1–D7).
6. E6003/E6006 identifier dispute: "The E6003/E6006 numeric diagnostic
   identifier dispute recorded in those pages' audits concerns the landed
   page's D8/D12 and Verification 11 — outside D1–D7 — and is recorded out
   of scope (A4), not re-litigated." — the adopted D1–D7 pins no numeric
   code, so no D1–D7 rule is contradicted and no award goes to either
   boundary page.

This enumeration matches T2's recorded resolutions (its section 5,
conflicts 1–6), which is confirmed consistent. No resolution awards a
boundary-contract statement over the adopted text: in the 13-row table the
semantic core of every row is cited to adopted D1–D7 text, and the
envelope-side references (rows 1, 2, 4, 13) are retained only where the
adopted text does not contradict them — proven row-by-row in section 3,
where zero adopted sentence contradicts its invariant. The A2 closing
check "No other conflict was found: the remaining boundary-contract
statements in both pages match the adopted text (invariant table below)"
was re-verified by this audit: the section 2 completeness scan shows every
remaining boundary-contract pin is either identical to adopted text or a
compile-time-only pin outside D1–D7 that the adopted text does not touch.
**No unresolved contradiction remains between an invariant and the adopted
text.**

## 5. Anti-hollow statement

Every check in this record quotes both sides from the source files read in
this audit: the invariant from `luajit-ffi-runtime-realization` or
`luajit-ffi-runtime-half` (section 2) and the supporting adopted sentence
from the landed page at its decision anchor (section 3), with the
preservation argument stated in one sentence per row. The adoption page's
own table summary was used only to locate the rows; it was not accepted as
evidence for any row.

## 6. Resulting state

The 13-row table in `luajit-ffi-d1-d7-adoption` is complete and correct as
written: exactly 13 rows, all boundary-contract invariants covered, every
cited adopted sentence verbatim and supportive, every conflict resolved to
the landed D1–D7 as winner. Zero corrections were required, so the adoption
page remains byte-identical and no correction re-run applies. This record
itself was revised once on review to correct two citation defects in its
own quoting (row 13's `FfiInitializationIdentity` anchor corrected to D1
`:48-52`; the row-4 D7 `:154` header quote flagged outside T2's compared
ranges, and the outside-pair count corrected from one to two); the
preservation conclusions of rows 4 and 13 are unchanged. Epic criterion
4 is satisfied by this audit: all boundary-contract invariants are preserved
under the adopted D1–D7 semantics.

## 7. Verification gate

`./run_tests.sh` — exit 0 at the final rebased commit. Only this record
file is added; no production or test artifact changes, so the gate runs the
same green battery as the pre-task baseline (T1/T2: `=== All Tests Passed ===`,
LuaJIT `test_runtime.lua` at `run_tests.sh:459`).
