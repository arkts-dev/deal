# Semantic IR

Define backend-neutral execution, never AST/checker objects. Preserve deterministic
IDs, descriptors, payloads, order and serialization. Evolve lowering, admission,
Oracle, emitters and tests together.

## Admission

- Enforce descriptor position recursively: void/never are result/completion-only,
  not field/element/parameter types. Functions returning either remain legal data.
  UNREACHABLE consumes an earlier same-block never invocation/completion; it has
  no data result. Normal never completion rejects with E8001.
- Typed/raw/text surfaces carry complete facts and identical first failures.
  Validate registry identity, body membership, captures, binding registrations,
  module/init/export links and construction. Raw materialization has no independent
  authority; undecodable recognized positions reject.
- Standalone units reject unavailable providers. Project admission checks framing,
  module keys, profiles/indexes, provider classification, implementation closure
  and dependency order. Run each owner once over owned companions in order.
- Resolve factories/fields/schemas through exact providers. Schema descendants
  stay in the defining module's import closure; global seeds, consumer-relocated
  layouts and unrelated imports grant no visibility. Ambiguous owners reject.
- Carry active profiles/capability hashes and recompute context hashes. Inconsistent
  comparison/project facts reject R-PROFILE; never substitute retired profiles.

## Operations

Operands must precede their consumers with exact descriptors, ownership and payload
agreement. Closed catalog signatures/selectors/policies apply identically on every
transport. Direct-only selectors grant no callable namespace exports; append native
IDs without renumbering existing ones.

| Operation | Required contract |
|---|---|
| NUMBER_REM | ECMAScript dividend-sign remainder and nontrapping binary64 specials |
| STRING_TO_NUMBER | Checked string, whole-string ECMAScript conversion, malformed -> NaN, NO_DEAL_FAILURE |
| NUMBER_TO_STRING | Number -> shortest ECMAScript text, NO_DEAL_FAILURE; before later template effects |
| OBJECT_KEYS | Exact class/Record -> fresh string[]; presence/order only, never field admission |
| ARRAY_POP | Exact array -> optional element; validate live tail before removal |
| ARRAY_SPLICE | Writable array/number/number -> mutable array; admit all removed positions before mutation |
| ARRAY_CONCAT | Array and compatible readonly argument -> mutable receiver-element array; fresh shallow storage, source-local failures, sparse presence preserved |
| ARRAY_SLICE | Array/number/number -> mutable element array; post-argument relative bounds, selected checks, fresh shallow storage |
| ARRAY_ELEMENT_GET | Exact array/number -> element; integrality/bounds/presence E8002 before present-element admission |
| ARRAY_HAS | Exact array/number -> boolean, NO_DEAL_FAILURE; presence only, invalid indexes absent |
| ARRAY_JOIN | String-array/string -> string; post-argument snapshot, ordered checks, holes contribute empty text |
| ARRAY_SEARCH | Array/element/number, includes mode and boolean/number result; ordered reached checks and early exit |

Array mutations/results preserve descriptors, aliases and rollback. Present-element
failures retain original source-index E8003 and unwrapped leaf cause/origin.
Rejected array bounds retain original index/observed length/failed constraint at
the executor, independently of bare registry rendering and without rereading data. Missing
strict reads fail even when elements admit undefined. Includes uses SameValueZero
and holes as undefined; indexOf uses strict equality and skips holes. Native storage
remains dense. Numeric bounds truncate/clamp before integer casts.

FOR_EACH validates iterable mode, body ownership and TYPE_DESCRIPTOR policy.
Evaluate once and traverse increasing live positions; contiguous appends are visited,
absence ends traversal and present invalid values fail. Expanded callback algorithms
use ordinary iteration/branch/call/append operations and dynamic boundaries, never
opaque backend algorithms.

IS_NULLISH is a one-data-operand boolean test without coercion/admission.
NON_NULLISH requires the identical operand/descriptor in its owning same-block guard,
which immediately precedes CONDITIONAL; projection begins the alternate block.
Reject void/singleton-nullish projections or forged order/ownership.

BRANCH/CONDITIONAL owns a preceding boolean and distinct lazy arm blocks with explicit
compatible result links, never eager arm operands. Resolve repeated IDs within their
blocks. Contextual adapters belong to the corresponding arm/CONDITIONAL_BRANCH cell;
ordinary read boundaries do not adapt.

NARROWED_READ consumes a binding load or declared field read from a binding receiver,
with refined descriptor/read origin. Consumers use its admitted identity. ASSERTION
carries explicit target and owns E8001 for every mismatch; CHECKED_ASSERTION is a
BOUNDARY, not UNARY. INSTANCEOF alone carries a nominal unary target. HAS_FIELD
requires exactly one key form and valid receiver/key producers.

ASSIGN yields original RHS identity/type while commits use storage-typed values.
Adapters consume preceding evaluated VALUE sources; completion cannot hoist later
loads. CLASS_NEW requires all nonoptional fields, including nullable/Error fields,
with declaration-order supplied-field checks and no omitted-field boundaries.
Reject initializer/default payloads rather than ignoring them. HOST reads correlate
with the unique MODULE_IMPORT alias cell; stdlib once-only surfaces invent none.

## Runtime values and failures

Canonical binary64 atoms preserve signed zero/subnormals/infinities/NaN; integer IDs
cannot substitute for scalars. StringSemantics owns UTF-16 operations. Console-only
lone-unit replacement must not change language text or lossless traces.
Malformed byte views retain scanner-owned one-based sequence positions when known;
explicit defect-only views invent none, and reprojection preserves observations.

Nominal/callable checks validate complete current plans, payloads and signatures,
including unread descendants, with operation-local cycle guards. Required fields
read declared descriptors; optionalRead adds undefined and missing never becomes
null. Unknown equality compares kinds/scalars/original identities without conversion.
Record/unknown ingress validates whole graphs while preserving supported cycles. Dynamic boundary views retain producer-observed child names separately from lazy values; path enrichment never guesses unnamed segments or changes child order.

JSON_PARSE owns one preceding string, an explicit target equal to the result and
JSON_RECONSTRUCTION policy. Validate the entire schema, including optional fields:
unknown/Record stop traversal; recursive classes are valid; function/void/unavailable
or ambiguous layouts reject. Assertions supply no target. Preserve syntax/duplicate
errors, extras-before-declaration-ordered fields and exactly one terminal per START.

Decode retains descriptors recursively, including empty arrays. Stringify uses
reached-position checks, separate unsupported-carrier and fieldless-cycle arms;
no synthetic field boundaries or null-swallowing helpers. Records follow generic
omission/substitution/numeric-key order; declared classes follow presence/order.
Finite JSON numbers share ECMAScript spelling; nonfinite leaves become null.
Preserve closed failure arms, locations and token dialects, including raw unlocated
host-result shapes. Rendered table tokens are not canonical descriptors.
