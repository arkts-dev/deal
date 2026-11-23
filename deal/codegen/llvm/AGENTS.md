# Native LLVM Code Generation

`LlvmSemanticEmitter` renders one deterministic textual module from
`ExecutableLoweredProject`, its body tables, registries and declaration facts.
`ProductionProjectEmission` stages it with `NativeRuntime.FILES`. Never consume
AST/checker objects or introduce native-only language rules.

## Admission and ABI

- `LlvmStageClassifier` covers every operation/payload/selector/boundary/failure
  and call/import mode. Unsupported facts raise origin-bearing
  `SharedEmissionFailure`, projected as E6005 `SHARED_EMITTER_COVERAGE`.
- `LlvmRuntimeAbi` is the single Java mirror of published headers, in declaration
  order. Update it with runtime entries. `LlvmTypeMapper` owns representation,
  pointer types and C ABI sret/byval rules; `LlvmDataValues` owns datum conversion.
- Emit only needed external references. The module owns module/export layouts,
  export-surface definitions and its arm table, never process entry or runtime
  symbols. Install arms/layouts/plans before unit initialization.
- Derive names/order/debug paths from IR, never timestamps, absolute paths,
  environment or map iteration. Preserve the pinned target triple.
- `LlvmSelectorArms` owns unary/binary rendering. No fast-math. Distinguish
  comparison ordering from equality booleans and singleton identity from payload
  equality; null|undefined has no non-nullish payload to unbox.

## Roots and representations

Every activation balances call/root frames on all exits. Root each live managed
value and allocating-operation temporary before another allocation. Use tag-aware
roots for datums; scalars, code pointers and nullish singletons are not heap roots.
Reference maps name `deal_data.reference`, never its tag/reserved bits.

Present actual stored tags, class atoms and callable signatures before checking
or unboxing; declared descriptors constrain admission, not actual identity.
Never dereference a singleton as a function carrier. Functions use managed
`deal_fn*`, including unknown/container positions; void has no data representation.
Failure returns use the enclosing activation's outcome shape. Fully terminating
child paths leave an unreachable join, not a synthetic fallthrough.

Resolve binding write descriptors from each declaration/assignment boundary,
not bare value IDs. NARROWED_READ may dynamically check a wider declared field
before projection; retain roots and the read's origin. Validate live graphs before
commits. Call-owned parameter checks run before dispatch, including discarded
adapter arguments; returns/completions use their own boundary cells.

## Storage and control

Arrays preserve persistent descriptors and actual storage layouts. Static admission
uses shared readonly-array compatibility recursively under nullish wrappers;
view widening preserves the original pointer and storage descriptor. Validate finite
integral bounds before offset casts; negative zero addresses zero. Class slots
retain actual datums and presence separately. Optional absence projects to
undefined, never null. Stores and shifted/deleted links use insertion barriers.
Class construction checks supplied values before publication; builtin Error uses
the runtime's own record. Presence-only readers never classify unrelated fields.

FOR_EACH retains its once-evaluated iterable, checks each live element before
unboxing and allocates fresh captured iteration cells. Array.from and other
expanded builtins consume ordinary shared IR, not native callback algorithms.
Lazy branches run only selected blocks; convert/root actual joined values.

Captured/reassigned binding generations allocate one heap cell. Parameter/catch
cells already initialized from incoming values are not reset by BINDING_ALLOC.
Recursive groups allocate all cells before publishing closures; exports include
registered group members as well as CLOSURE_NEW values. Adapters capture the
once-evaluated source in rooted immutable cells. Boxed apply/sret shims do not
add duplicate source frames. Async adapters start/await real operations.

## Modules, hosts and algorithms

Initialize dependencies once in import order; failures are sticky and publish no
partial export surface. Module/export cells are static roots. Host imports emit
generated headers, declared records and origin-carrying load calls. Empty class
metadata uses NULL/count-zero. Nominal identification must not read child fields.

Host calls carry exact canonical signatures and declared parameter checks;
unbox checked results once. Static CALLBACK_INVOKE exposes deterministic re-entry
through `deal_host_callback_invoke`. Async start/await/export invocation uses the
record's own host/async markers, token and completion descriptor; preserve checked
completion values for public consumers.

STDLIB_CALL passes the closed selector, boxed presentations and operation origin
to `deal_std_call`. Preserve existing ordinals. Contextual JSON uses the explicit
target and exact emitted plan lookup; stringify uses runtime dispatch, never
class helpers. Retain O0/O2 normal/stress/sanitized execution controls.
