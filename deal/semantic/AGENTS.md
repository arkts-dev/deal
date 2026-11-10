# Semantics

Construct coverage follows emitted operands: HAS_FIELD embeds a checked literal class field name, so it contributes no scalar-literal operand; computed Record keys retain their expression coverage.

Own backend-neutral checked-project lowering/shared runtime meaning. Encode evaluation order, boundaries, failures, calls, classes, modules and stdlib once; complete changes through validated IR, Oracle and every emitter. No AST fallback. Evaluator boundary views include executable-body validity, not signature alone.

## Providers and admission

Resolve construction/field layouts from checked declarations and defining providers, then project indexes. Never select a same-spelled local or first identity match. Valid nominal identities name defining files; defensive shared-identity ambiguity fails closed at the offending origin. Contextual JSON schemas include private descendants from the owned defining dependency units, not just exported construction entries. Keep dependency layouts out of transported consumer-local layouts; standalone admission rejects unavailable project links. Each contextual schema position retains its defining module: descendants resolve only within that provider's import closure, never through an unrelated consumer import or a relocated consumer-local declaration. Carry owned layouts and import visibility together in the temporary schema context. Declaration-seed layouts retain their interface owner; join external descriptor aliases through resolved import specifiers and targets, never infer ModuleId from ClassId text. A global registration seed does not grant unrelated providers schema visibility. Ambiguous declaring identities fail closed regardless of comment spelling; never choose a first/last schema owner.

Implementation seeds agree with exact interface/module identity; declaration seeds agree with layout fields, optional-read descriptors and canonical annotations. Optional reads add undefined, including null|undefined for optional null; absence never fabricates null. Typed/raw/text admission consumes the same complete facts. Supplied companion views must match the unit's owned records; content-equal detached views resolve to those owned records.

Carry DEAL_V1_6_BINARY64/V1_6_ACTIVE through constructors/comparisons/admission/context hashes. Reject retired profiles/aliases. Evolve serialization, digests and independent pins together.

## Bindings, calls and adapters

Each HOST import read records its source occurrence's alias cell; never reconstruct occurrence ownership from module/name. Declared-export validation uses shared ascending-name order without changing declaration-ordered provider facts.

Function-valued variables assigned anywhere in their declaring body's nested traversal lack static allocation identity: reads materialize current values; calls/await use Dynamic dispatch. Declarations/imported exports are immutable; resolve signatures in their exact owner scope, excluding shadowing parameters. Declarations, recursion and fixed adapters retain recorded static callees. Classify runtime sources by recorded execution class, never spelling/descriptor. If conservative assignment analysis materializes a declared-name read as DynamicFunctionValue, its await invokes that evaluated carrier through dynamic dispatch just like its synchronous twin; sibling-scope spelling collisions must not produce a lowering rejection.

A direct function-expression initializer captures its own mutable let cell, including through its immediate VALUE adapter and declaration boundary; unrelated later writes never justify an earlier load. Every adapter captures its source function value once at the source evaluation point, including awaited completion. Preserve genuine closure bindings and recorded sync/async completion cells.

Assignment evaluates and yields the original RHS once. Storage may receive an adapter without changing the expression's type/identity. All adapters capture VALUE; validators require a preceding source producer. Address-chain validation preserves RHS parent/type/order and original publication. Later loads or enclosing assignments cannot justify earlier captures.

## Consumption and control flow

Lower checked refined binding reads through NARROWED_READ where no owning typed boundary already checks consumption: operators, conditions, index receiver/key, Record receivers, length, for-of and presence. Record the refined descriptor and read origin; consumers use the admitted result. Never rewrite declared-nullable reads, null tests or function-boundary reads. Preserve each owning boundary's projection, including E8003 array elements.

Address-chain receiver/key/normalize/commit facts reference the admitted operands without changing child order. Slot-threaded logical operands republish the caller's preallocated condition identity. For-of evaluates once and preserves live traversal and fresh mutable captured iteration cells. String receiver calls lower to the existing closed string algorithm selectors, with the admitted receiver before left-to-right admitted arguments. Import-free algorithms carry no invented source export-read or function binding. Internal JSON algorithm signatures never populate module namespaces or callable catalog lookup; only ordinary stdlib exports do.

Conditional BRANCH operations own two lazy arm blocks and explicit arm result links. Evaluate/admit the boolean condition once before entering only the selected block; the join publishes that block's checked value. Contextual function adapters capture once within their selected arm and wire through its CONDITIONAL_BRANCH cell to the owning join, never through a runtime-read boundary. Joined function values register dynamic dispatch facts; discarded void joins publish no data representation.

Array.from expands into existing validated binding, ARRAY_NEW, live FOR_EACH, indirect CALL and checked append-chain operations. Evaluate/admit source then mapper once; create shorter-arity adapters at mapper evaluation and validate the saved function signature even for empty input. Preserve normal parameter/return boundaries and fresh iteration cells; do not add backend snapshot/map algorithms or publish partial results after failure.

Object.hasOwn consumes its receiver and dynamic string key through operand admission in receiver/key order, tests presence without reading values, and never narrows fields. Error owns code/message presence independently of stored values.

Error throw/catch/rethrow keeps one reference identity, current code/message, original diagnostic facts and linked causes. Catch bindings obey ordinary lexical shadowing and fresh per-execution cells; closures retain the captured incarnation. A bare assignment target owns ASSIGNMENT, not an invented identifier read.

## Classes and JSON

Register explicit structural class layouts and provider facts before body lowering. Comments synthesize no functions, signatures, exports or helper-specific call shapes; JSON provider functions are ordinary user declarations. Nonoptional fields, including nullable/Error, require presence. Provided fields own declaration-order boundaries; omitted optionals remain absent. No initializer operation, default publisher or host defaults hook exists. Decode rejects missing/extra keys and failed nested values without factories; parsed-tree conversions/validation are stack-safe. Direct raw reconstruction rejects path-local container cycles as producer defects; shared acyclic descendants remain valid and reconstruct fresh values per occurrence.

Preserve persistent descriptors on decoded typed arrays recursively, including empty arrays. Function assertions register execution materializations while preserving identity; assertion failures always use E8001 without replacing ordinary signature/element diagnostics.

Render canonical interfaces with Record<string, unknown>, Promise results and independent nullish flags ordered null then undefined. Do not reconstruct retired source/async spellings or conflate canonical record with diagnostic/JSON-plan table.

Class stringify follows private presence/declaration order and original cycle identity; Error exposes code/message only. Generic/generated Records omit object undefined/missing/functions, null-substitute arrays/nonfinite numbers, order numeric keys first, encode nested classes. Declared nonfinite numbers likewise encode null. Every finite JSON numeric leaf shares NumberSemantics ECMAScript spelling, independent of container kind or numeric metadata. Reject malformed transport/cycles; preserve every UTF-16 unit. Shared string algorithms use StringSemantics; retain no scalar-only search/trim implementation or lone-unit JSON rejection. Trace strings escape surrogate units losslessly across process transport; console sinks encode display-only U+FFFD for lone units, preserving valid pairs and language values. Parsed Record arrays retain unknown[] descriptors; declared fields retain declared descriptors.
