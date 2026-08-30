# ISSUE-0426 — D1–D7 Landing Evidence Re-Check Record (authoritative record)

Audit record. Purpose: re-check the D1–D7 landing verification of
`deal-v1.2-luajit-c-ffi-runtime-and-conformance` in the authoritative
record from this checkout with cited locators (not asserted), verify the
adoption page's "Landing verification" locators against reality (correct
that section only on a locator mismatch, fidelity restoration only), and
record the landed page as an approved design source for the continuation
in the `wiki` fields of ISSUE-0423 and ISSUE-0425.

Verdict: **the landing is verified with cited locators**. Every locator
in `luajit-ffi-d1-d7-adoption.md` "Landing verification" items 1–5
matched the actual record — the section required no correction and
remains byte-identical. The approved-design-source slug was absent from
both dependent records and was added (exactly one line each; no existing
entry removed; no other content of those records changed).

## 1. Landed page read directly (forge wiki record)

File: `../../forge/wiki/deal-v1.2-luajit-c-ffi-runtime-and-conformance.md`

| Read | Locator | Result |
|---|---|---|
| front matter `created` | file line 16 | `2026-08-21T10:22:44Z` |
| front matter `updated` | file line 17 | `2026-08-21T10:22:44Z` |
| file mtime | `ls -la` | 2026-08-21 13:22 (+03:00) == 10:22:44Z; unchanged since (page created 2026-08-21, today's reads see the same mtime) |
| D1 header | line 44 | `### D1. Module cache identity and process-global cdefs have separate failure-aware owners` — full body present: identity record block (`FfiModuleKey`/`FfiInitializationIdentity`/`FfiModuleRecord`/`CdefModuleRecord`/`CdefEntryRecord`/`CdefNameRecord`), `runtimeAbiVersion` paragraph, module-cache transitions 1–5, cdef registration rules 1–6, post-registration paragraph |
| D2 header | line 86 | full body: generated-field shape block (`FfiGeneratedField`), metadata/keyword paragraph, resolver ownership + `open/resolve/close` block, `dlopen`/`dlsym`/`dlerror` mechanics, handle retention/close-once paragraph |
| D3 header | line 109 | full body: `NativeLibraryRef` classification, UTF-8/NUL defense, failure mapping, no-raw-exception rule |
| D4 header | line 115 | full body: converter bullets (INT/NUMBER/BOOLEAN/STRING param/STRING return/BYTES/NULL/C_STRUCT/C_POINTER), canonical-descriptor rule |
| D5 header | line 133 | full body: DEAL field/C member conversion table, source-order/unpublished-slot paragraphs |
| D6 header | line 146 | full body: cell/readiness transitions, invocation-at-READY paragraphs |
| D7 header | line 154 | full body: single-call/non-owning paragraph |
| D8–D12 follow | lines 160/168/203/225/257 | present; outside D1–D7 scope, not re-litigated |

Verbatim adoption check: every substantive line of the adoption page's
Areas 1–5 quoted blocks was diffed against the landed page's D1–D7
decision bodies (lines 44–159 with each decision's Evidence/Alternative
tail excluded): **0 missing lines** (automated containment check). The
quoted body text matches the adoption page's Areas 1–5 blocks exactly.

## 2. Authoritative epic records

| Read | Locator | Result |
|---|---|---|
| `../../forge/issues/ISSUE-0163-luajit-ffi-loading-cdef-certainty-and-abi-wrappe.md` | `wiki` field | lists `deal-v1.2-luajit-c-ffi-runtime-and-conformance` |
| same record | "Source wiki/contracts:" line | `deal-v1.2-luajit-c-ffi-runtime-and-conformance D1–D7; ...` — names D1–D7 as its source contracts |
| same record | `Blockers:` block | `- None.` |
| `../../forge/issues/ISSUE-0111-implement-deal-v1-2-signed-int32-bytes-and-c-ffi.md` | `wiki` field | lists `deal-v1.2-luajit-c-ffi-runtime-and-conformance` |
| same record | `architecture_status` | `approved` |

## 3. Spec cross-check (`docs/spec-v1.2.md`)

| Claim pair | Spec locator | Matching sentence read |
|---|---|---|
| D1/D6 publication + failed re-raise | `:1733` | "A module export table becomes visible only after successful initialization." |
| D1/D6 failed-module replay | `:1736` | "If module initialization raises an error, the module is marked failed; later imports re-raise the same error." |
| D4–D5 pointer/ABI rows | `:1822-1831` | "A `// @c-pointer` class is a nominal exported DEAL type representing a non-null opaque `void*` token. It has no DEAL-visible fields or storage layout." … "A C `NULL` pointer crossing into DEAL raises `FFI_NULL_POINTER`." |
| D4–D5 ABI mapping table | `:1833-1844` | rows `int`→`int32_t`, `number`→`double`, `boolean`→`_Bool` false 0/true 1/nonzero return true, `string` parameter/return borrowed `const char*`, `bytes` parameter `const uint8_t*` + `int32_t` length, `null` return `void`, `// @c-struct` by-value source-order scalar fields, `// @c-pointer` non-null `void*` |
| D1/D7 runtime semantics | `:2001` | "Importing a C FFI module loads its native library and resolves all declared symbols before the module export table becomes visible. Failure raises `FFI_LIBRARY_LOAD` or `FFI_SYMBOL_MISSING` during module initialization and marks the module failed as in module initialization semantics." |
| D7 synchronous calls | `:2003` | "Calls are synchronous. DEAL does not read or expose `errno`." |
| six-code table | `:2916, :2920-2925` | `FFI_*` codes are runtime `Error.code` string values; rows `FFI_LIBRARY_LOAD`/`FFI_SYMBOL_MISSING`/`FFI_INVALID_STRING`/`FFI_INVALID_UTF8`/`FFI_NULL_STRING`/`FFI_NULL_POINTER` with the adopted meanings |

## 4. Runtime-state cross-check (`deal/runtime.lua`, 2101 lines)

| Search | Result |
|---|---|
| `grep -n 'load_ffi' deal/runtime.lua` | 0 hits |
| `grep -n 'ffi\.cdef' deal/runtime.lua` | 0 hits |
| `grep -n 'dlopen' deal/runtime.lua` | 0 hits |
| only FFI use | bytes storage: `local ffi = require("ffi")` `:303`; `ffi.new` uint8_t storage comment `:314`, call `:323` — the existing bytes storage only |

Precedents the boundary contract consumes, read at their cited lines:

- `_err` `:20` and `error_value` `:41` — present inside `:20-49` (DEALRuntimeError shape with `code`/`message`/`file`/`line`/`column`, tagged `__classname = "@$builtin/Error"`, `__kind = "class"`).
- `check_int` `:69` — present through `:84` (E8001 type/NaN/infinity/non-integer gate, E8004 int32 range gate).
- `load_host` `:962` — `function __rt.load_host(module_path, declared)`.
- `return __rt` `:2101` — the single module return at the file's last line.

## 5. Adoption-page "Landing verification" section — final state

`../../forge/wiki/luajit-ffi-d1-d7-adoption.md`, section "Landing
verification (authoritative record, with locators)": each of items 1–5
was re-checked against the actual record.

| Item | Claim | Check result |
|---|---|---|
| 1 | page exists; mtime 2026-08-21T10:22:44Z (+03:00); D1–D7 at `:44/:86/:109/:115/:133/:146/:154`; D8–D12 follow | matches (section 1) |
| 2 | ISSUE-0163 lists page, cites D1–D7, `Blockers: None`; ISSUE-0111 lists page, `architecture_status: approved` | matches (section 2) |
| 3 | spec ranges `:1729-1736`, `:1822-1855`, `:1999-2005`, `:2916-2925` | matches (section 3) |
| 4 | no `load_ffi`/`ffi.cdef`/`dlopen` in `deal/runtime.lua`; precedents at `:20-49`, `:69-84`, `:962`, `:2101` | matches (section 4) |
| 5 | prior "not landed" record was a checkout limitation, not a fact about the authoritative record | consistent: the realization page's gate-status paragraph records `forge/wiki/` outside its checkout and defers to the epic record; the authoritative record contains the complete D1–D7 page since 2026-08-21 (sections 1–2) |

No locator mismatched the actual record, so the section required no
correction; it remains byte-identical and no new design content was
introduced. The quoted Areas 1–5 blocks were diffed against the landed
bodies (section 1) and match verbatim.

## 6. Approved-design-source recording (continuation records)

The landed page slug was absent from both continuation records and was
added exactly once to each `wiki` field; no existing entry was removed
and no other content of either record was changed (script-verified:
exactly one slug occurrence per file; every prior wiki entry preserved).

- `../../forge/issues/ISSUE-0423-realize-load-ffi-cache-registry-resolver-convert.md`
  `wiki` (final): `luajit-ffi-runtime-realization`,
  `luajit-ffi-runtime-half`,
  `luajit-v1.2-runtime-value-model-and-boundaries`,
  `deal-v1.2-luajit-c-ffi-runtime-and-conformance`.
- `../../forge/issues/ISSUE-0425-ffigen-boundary-integration-through-production-l.md`
  `wiki` (final): `luajit-ffi-runtime-realization`,
  `luajit-ffi-runtime-half`, `luajit-v1.2-emitter-and-lowering`,
  `deal-v1.2-luajit-c-ffi-runtime-and-conformance`.

## 7. Verification gate

`./run_tests.sh` — exit 0, final banner `=== All Tests Passed ===`
(the gate includes the LuaJIT runtime battery `luajit test_runtime.lua`
at `run_tests.sh:459`). Run at the final rebased commit; no source
artifact changed in this task, so the gate re-runs the same green
runtime battery and pinned fixtures as the pre-task baseline.
