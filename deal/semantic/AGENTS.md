# Semantics

Own backend-neutral lowering/shared runtime meaning. Encode evaluation order,
boundaries, failures, calls, classes, modules and algorithms once; evolve IR,
validation, Oracle and every emitter together. No AST fallback. Evaluator checks
include executable body validity, not signature alone.

## Providers and admission

Resolve layouts from exact checked declarations/providers and project indexes.
Canonical identities name defining files; ambiguity fails at the offending origin,
never first/last match. Contextual schemas include private descendants only through
the defining provider's import closure. Global seeds, relocated consumer layouts
and unrelated consumer imports grant no visibility. External aliases join through
resolved import targets, not ClassId text. Keep dependency layouts out of transported
consumer-local inventories; standalone admission rejects unavailable project links.

Seeds agree with interface/module identity, fields, optional reads and annotations.
Optional absence becomes undefined, never null. Typed/raw/text surfaces consume
identical complete facts; supplied companion views must match owned records.
Carry active profiles through admission/comparisons/context hashes; update digests
and serialization together. Canonical record, diagnostic table and JSON-plan tokens
remain distinct dialects.

## Bindings and control

- Entry invokes the selected synchronous void main once through ordinary CALL and
  ENTRY_INVOKE; void completion synthesizes no null/data projection. Never call/await
  statements and returns end in UNREACHABLE, without a data conversion.
- Mutable function bindings use evaluated dynamic carriers, including await.
  Declarations, recursion and fixed adapters retain recorded static callees.
  Resolve by identity/execution class, not spelling or descriptor.
- Expression parameters with omitted annotations keep exact checked signatures;
  their parameter source spans anchor boundaries. Declaration annotations stay
  fail-closed. HOST reads retain the originating import occurrence's alias cell;
  validate exports in ascending-name order without reordering provider facts.
- Adapters capture evaluated VALUE sources once, including immediate recursive let
  initializers and awaited results. Assignment yields the original RHS identity/
  type while storage may receive an adapter; later loads cannot justify captures.
- NARROWED_READ publishes admitted operands for non-boundary consumers. Direct
  fields retain declared FIELD_READ types, followed by explicit refined reads.
  Preserve ordinary boundary projections and read origins; never narrow null tests
  or rewrite unrefined storage descriptors.
- Address-chain receiver/key/normalize/commit links use admitted operands in child
  order. Slot-threaded logical results keep preallocated identities. For-of retains
  once-evaluated live iterables and fresh captured iteration cells. Compound writes
  save address operands and the admitted old value before RHS effects, then use
  ordinary binary operations and validated assignment chains.
- Conditionals own lazy blocks and explicit result links. Selected-arm adapters
  wire to the owning join; void joins publish no data. Coalescing saves the left,
  tests IS_NULLISH and uses matching NON_NULLISH projection in the alternate arm.
  Optional chains nest these guards so skipped keys/arguments never execute.
- Computed Record literals evaluate key then value once through ordinary shared
  operations; duplicates throw Error and publish no partial destination. Traverse
  keys for captures/dependencies. Static literals retain TABLE_NEW. HAS_FIELD
  static names are payloads, not invented literal operands.
- Throw/catch preserves Error identity/current fields and original facts/causes.
  First throw binds diagnostic code from name; catch executions own fresh captured
  cells. Oracle snapshots retain only approved producer metadata through rethrow;
  bounded structured projection never changes canonical trace/human text. Host return/completion-cell rejections retain their resolved binding/import facts; completion attribution follows the selected host operation, and host-authored failures gain none. Bare assignment targets are not invented identifier reads.

## Algorithms

Direct-only builtins carry closed selectors/signatures, never fictitious source
exports or callable catalog entries. String receivers precede admitted arguments.
Omitted string end becomes +infinity; search defaults preserve indexOf/lastIndexOf
rules. String/Number conversions are checked number/string-only; interpolation
converts numbers before later fragment effects. Shared scalar rules live in
NumberSemantics/StringSemantics; preserve binary64 specials and every UTF-16 unit.

Array lowering retains exact descriptors and ordinary boundary ownership:

| Operation | Lowering contract |
|---|---|
| push | Save receiver/value, capture live append position after effects, validate/adapt before commit, return new length |
| toSorted | Admitted comparator, post-argument dense snapshot, stable shared merge sort; undefined sorts last without callbacks |
| concat | ARRAY_CONCAT with identical array descriptors; saved receiver, post-argument reads, sparse presence and fresh shallow storage |
| slice | ARRAY_SLICE(receiver,start,end); omitted bounds become 0/+infinity; post-argument length, selected-position checks, fresh shallow result |
| join/search | Saved operands, post-argument snapshot, ordered reached checks; preserve source indexes, holes and early exit |
| map | Saved/admitted mapper, length snapshot, fresh array, strict ARRAY_ELEMENT_GET, indirect calls and checked append |
| find/findIndex | Same strict reads/callback boundaries; stop with originally read value/index, else undefined/-1 |
| filter/some/every | Presence-only ARRAY_HAS guards strict reads/calls; skip holes, not stored undefined; fresh selection or boolean early exit |
| from | Saved/admitted source/mapper, fresh array, live FOR_EACH, indirect calls and checked append |

Callback boundaries include source-array checks and discarded adapter arguments
before effects. Do not bypass them to force reached-only behavior. Preserve saved
receiver identity, live rewrites, each operation's snapshot/growth rules, source/
destination rollback and E8003 leaf causes. Expanded algorithms introduce no
backend-specific callback payloads. Presence/key operations consume admitted
receivers/keys without reading unrelated field values or refining them.

## Classes and JSON

Register structural layouts/provider facts before bodies. Class construction
requires nonoptional fields and declaration-order boundaries; omitted optionals
remain absent. No initializers, defaults hooks or generated helper exports.
JSON parsing has explicit contextual schemas, syntax/duplicate errors, extras-first
then declaration-order failures, fresh exact instances/arrays and no factories.
Assertions never supply decoding targets. Preserve typed descriptors recursively,
including empty arrays; operation-local reconstruction/checking stays stack-safe.
Decode evidence comes from current walk frames, with bounded JSON-pointer prefixes,
exact path depth and missing-field status; never re-read a rejected document.

Stringify validates declared positions only when reached, preserves original
cycle identity and class order/presence, and exposes Error name/message only.
Records omit object undefined/functions, null-substitute arrays/nonfinite numbers
and sort numeric keys before other creation-ordered keys. All finite numeric leaves
share ECMAScript text. Preserve parameter/return crossings, operation origins and
one terminal event per started parse/encode. Decode failures never become nullable
successes; malformed transport and unsupported carriers retain their own arms.
