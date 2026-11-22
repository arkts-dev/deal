# C11 Runtime

## ABI and ownership

`deal_rt.h` is the public ABI authority and includes service headers in dependency
order. Generated/host code uses public headers, never private structures.
`deal_module` and `deal_export_entry` remain incomplete in runtime translation
units; only the emitted module defines their layouts and export surface.
Preserve record order, discriminants, signatures and count-zero sentinels.
Update `LlvmRuntimeAbi` and deployment/packaging lists with ABI changes.

Each service owns its implementation and header:

| Service | Ownership |
|---|---|
| platform | OS calls, checked size arithmetic, allocation, faults, clock/queue primitives |
| memory | private heap headers, layouts, roots/cells, incremental collector/barriers |
| value | datums, nullish singletons, function carriers, crossing-value tables |
| text | UTF-16-unit storage, WTF-8 views, validation/comparison/iteration |
| container | typed arrays, insertion-ordered Records, live element layouts |
| descriptor | canonical atoms, positional legality, graph checks/construction admission |
| error | closed-arm materialization, Error storage/accessors, frames/reclamation |
| json | scanner, contextual reconstruction/stringify, private-locale numeric conversion |
| std | closed algorithm dispatch and declared parameter/result crossings |
| host | crossings, live views, handles, failure tokens, registration/re-entry |
| async | operation tokens, completion delivery and pump |
| entry/main | terminal surface/product process entry |

Compile published units under `-std=c11 -Wall -Werror -Wextra -pedantic` at O0/O2.
Every allocation size, alignment and growth uses checked platform arithmetic;
overflow is a resource fault, never a wrapped short region. Protocol/invariant
faults use the shared deterministic diagnostic and exit 2, not language Errors.

## Collector

Use precise non-moving incremental mark/sweep. Allocation always advances a
safepoint; growth counts header/capacity footprint, including zero-size payloads.
`DEAL_GC_STRESS=1` starts whenever idle but advances the same bounded slices,
never a full traversal per allocation. Explicit full collection remains available.

Root every live managed referent across allocation/checks/callbacks. Datums use
tag-aware roots; scalars, code pointers, nullish singletons and crossing handles
are not heap addresses. Zero-slot frames touch no slot storage. Register resumable
walks for variable reference layouts; bound slices by reference visits, including
wide objects. Rescan root sources at cycle start and atomic fixpoint.

Preserve allocation marks and insertion barriers on all managed stores, including
container deletion rewires. Free lists retain original capacity; release empty
arenas and poison reclaimed payloads. Registered release hooks reclaim auxiliary
platform storage while payloads remain intact. Captured datum cells trace only
managed-reference members, never scalar bits. Function carriers trace their
captured cells through their own layout walker.

## Values and admission

Keep persistent descriptors independent of current contents. Present actual stored
tags, class atoms and function signatures before admission; requested descriptors
cannot manufacture identity. Class identification must work without reading fields,
including empty classes. Array reads use `deal_array_present_element`/live-layout
authority before unboxing. Function metadata defects reject catchably before
interning or publication. Void/never are result-only; normal never completion
rejects with E8001. Missing is never admitted data.

Graph checks use operation-local identity-and-descriptor guards, not lasting
certificates. An unknown/Record visit cannot discharge a stronger class/array
check. Validate current required presence and fields in declaration order; schedule
worklist defects in that order, not reverse push order. Retain source-index E8003
with the unwrapped leaf cause and owning operation origin. Validate before mutation
or publication; repair/retry on the same graph must remain possible.

Normalize/clamp binary64 bounds before integer casts. Pop validates before shrink;
splice validates its entire removed range before mutation; slice checks selected
dense slots only. Preserve layouts, aliases and barriers while copying/shifting.
Join roots source/separator and copies length-delimited UTF-16 units; search roots
source/needle and stops at the first match. Object.keys reads presence only:
a NULL field-value output must not load/classify unrelated data.

## Text, numbers and JSON

Preserve embedded NUL and lone UTF-16 units with explicit lengths; reject malformed
byte transport, not legal language text. Rejecting scanners retain the first
sequence's one-based byte position; defect-only carriers invent none. Lossless Error consumers use borrowed
length-delimited accessors, not legacy C strings. Snapshots escape controls/lone
units; failure parameters use the closed-arm renderer.

String search returns UTF-16 indices; check byte-span bounds separately before
comparison. LastIndexOf maps NaN position to length. Casing uses pinned
`unicode_case.h` mappings/context, preserving lone units and expansions without
ambient locale dependence.

StringToNumber trims ECMAScript whitespace in std.c, validates complete grammar in
json.c and converts under the private C locale. Radix integers become exact hex
tokens before one binary64 rounding. Malformed text yields NaN; preserve signed
zero/overflow. NumberToString uses shortest decimal/ties-to-even conversion;
finite JSON leaves reuse its stack-buffer formatter, nonfinite leaves become null.

JSON scanning rejects duplicate decoded UTF-16 keys with exact byte offsets.
Decode/encode/checks are stack-safe with invocation-local worklists. Contextual
decode uses exact installed plans, checks syntax then extras then declaration-
ordered fields and publishes fresh typed arrays/nominal instances only on success.
Root the parsed document and staged values; field-lookup scratch must be reclaimable
before construction callbacks.

Stringify checks declared positions when reached, not later descendants eagerly.
Required absence rejects independently of nullability. Preserve path-local cycle
identity, ordinary Record key order, class declaration order and generic omission/
substitution rules, including undefined at declared unknown fields. Class/array metadata never retags actual values. Class plans
carry precise reference maps and exact construct/read seams; no helper/default hooks.

## Errors and hosts

Install the emitted closed arm table before user code; validate its repertoire and
materialize its templates rather than composing failure prose in C. Error owns
mutable name/message and independent diagnostic code. First throw binds code,
origin and frames once; rethrow preserves identity and causes. Release all copied
text/frame storage when the Error is collected. Decoder path copies are length-
delimited platform storage, built from current frames only and released with Error.
Reprojection copies only retained validation/Unicode evidence, with independent path ownership; root the original across replacement allocation.
Host context copies belong to Error too; capture only selected rejection facts,
never attribute propagated host failure tokens from their code or message.

Host crossings preserve aliases, descriptors and rollback. Canonical function
positions use FUNCTION_SIGNATURE, other data positions TYPE_DESCRIPTOR. Validate
canonical nullish prefixes/class atoms before interning; malformed descriptors
return closed statuses. Nonstring Record keys reject before hidden values without
coercion. Live-view writes reject before mutation.

Raw host callables and borrowed opaque identities use reference-free private
layouts, not new admitted datum tags. Never trace/dereference their native payloads;
reject data, retain/apply and Record operations. Preserve their presentation through
recursive hostile storage and exactly-once async completion for await-owned checks.
DEAL-origin failure tokens are single-use relay/discard channels; re-entry uses
the original boxed apply/failure protocol.

## Async

Tokens are monotonic, nonzero and never reused. Root each live operation and its
completion until await retires it. Deliver once through a barrier; unknown/duplicate
posted tokens and reentrant pumping are protocol faults. Mutator completion entries
return their specified statuses.

Off-thread posts copy only native scalar/text/error bytes: no managed pointers,
handles or DEAL failure tokens, and no managed-state access. Completion frames use
release/acquire publication and exactly-once release by one consumer. Pump service
hooks in registration order, drain frames, then wait one bounded slice inside a
crossing-equivalent scope. Async export invocation follows the record's own markers
and checks completion at its declared result descriptor.
