# DEAL language guide

DEAL uses a strict TypeScript syntax subset with distinct semantics.
Authority: [v1.6 specification](https://github.com/arkts-dev/deal/blob/master/docs/spec-v1.6.md) (online).

## Syntax

Imports precede module-level functions/classes/exports; executable statements stay
inside functions. Use let/const, braces and semicolons. Const requires initialization
and forbids binding reassignment, not aggregate mutation. Entry exports synchronous
main(): void; no-result functions use bare return or fallthrough.

Comments use // or /* ... */. `$` bindings are compiler-owned. Property keys need
explicit values. Numeric `+=`, `-=`, `*=`, `/=`, `%=` and string `+=` evaluate targets once.
Numeric prefix `++`/`--` yield the new value; postfix yields the old value.
No line break before postfix updates; write `- -x` for double negation.
Arrows have parenthesized parameters and
explicit results: `(x: number): number => x * 2`. Block/expression bodies are legal;
a void expression body must produce void or terminate with never. Keep async with its parameter opening
and the result annotation with => on the same line.

Strings support character, hexadecimal-unit and Unicode escapes, preserving lone
UTF-16 units. \0 cannot precede a digit. Backslash plus LF/CR/CRLF contributes no
text. Untagged templates allow multiline text and string/number interpolation;
physical CR/CRLF normalizes to LF.

## Types

Data types: null, undefined, unknown, boolean, number, string,
Record<string, unknown>, nominal classes, invariant arrays and functions.
Null/undefined permissions are independent; void and never are result-only.
Use `never`/`Promise<never>` for always-throwing helpers; calls/awaits terminate
statement or return evaluation, without changing exact function-result matching. Extract unknown
with checked `as` or typeof/instanceof refinement. Assertions preserve identity,
do not coerce and throw E8001 on mismatch.

Numbers are binary64 with signed zero, infinities, NaN and nontrapping division.
Literals accept decimal fractions/exponents/trailing points, 0x/0b/0o prefixes and
underscores between digits; radix integers round once. `%` is dividend-sign
ECMAScript remainder. Indexes require finite whole numbers. Use Math.floor/ceil/
round deliberately. Conditions require boolean; ===/!== require compatible types
or admitted nullish comparisons. Concatenation requires strings; templates format
numbers with ECMAScript text, rendering either zero as 0.

`a ?? b` evaluates a once and b only when a is null/undefined, preserving other
falsy values. Fallback inherits destination context; otherwise literal fallbacks
use a's non-nullish exact array/class/Record type (`optionalArray ?? []`). Ternary
literal arms may likewise use an independently typed sibling's exact target.
Parenthesize mixing with &&/||.

`receiver?.field`, `receiver?.[key]` and `callback?.(arg)` skip the remaining chain
on null/undefined, including keys/arguments. Non-void results add undefined;
coalesce/extract before concrete use. Parentheses end chains. Optional calls are
synchronous; void remains result-only. Optional deletion removes class fields or
Record keys. See [dynamic data](../examples/src/data.deal).

## Classes

Module-level classes are nominal schemas: same-shaped declarations stay distinct.
Annotate fields; ? marks optional presence. Contextual literals allocate fresh
instances and supply every required field, including nullable fields. Supplied
references retain aliases. `readonly` fields prohibit replacement/deletion, not
mutation of referenced data. Like const, these restrictions are compile-time only.
Use Records for dynamic keys and functions for behavior.

## Functions

Annotate declaration parameters and every result. Function/arrow expression
parameters may omit annotations when an existing positional context supplies their
exact types, including array callbacks. No body-based inference. Pass arrays for
variable inputs. Closures capture live bindings; iterations create fresh cells.
Compatibility preserves async marker/types; shorter functions may adapt to ignored
target parameters when shared types/results match. Direct calls keep declared arity.

## Field access

Initialization/assignment refines local reads while annotated write types stay
fixed. Guard direct declared fields through local/parameter receivers; writes,
deletion and calls clear field facts. Await clears refinements; closures see declared
captured types. Facts remain within their function/iteration.

Object.hasOwn tests presence, not value type. Class keys must name literal declared
fields; Record keys are strings. Absent optionals read undefined, present null stays
null, and deletion removes presence rather than storing undefined.

## Tables

Use Record<string, unknown>. Reads infer unknown: check intermediate Records,
leaves and callable signatures. String-keyed writes accept supported heterogeneous
data; null/undefined preserve presence. See [dynamic data](../examples/src/data.deal).

## Arrays

Arrays are zero-based with persistent invariant descriptors; emptiness never
justifies retagging. Contextualize empty literals. Out-of-bounds reads fail.
Iterate with inferred for-of bindings or explicit while indexes. `readonly T[]`
accepts compatible element types without granting writes; mutable aliases stay live.
Decode an unknown array through `readonly unknown[]`, then use Array.from with
checked element assertions to produce a mutable copy.

| Call | Result/behavior |
|---|---|
| xs.push(value) | Append one checked element after argument effects; return new length |
| xs.pop() | Remove checked tail; empty returns undefined |
| xs.splice(start, count) | Remove range atomically into a fresh typed array |
| xs.toSorted(compare) | Fresh stable shallow sort; required synchronous `(a: E, b: E) => number` comparator |
| xs.concat(other) | Fresh shallow concatenation; other must assign to readonly E[] |
| xs.slice(), xs.slice(start), xs.slice(start,end) | Fresh shallow copy/range; negative relative bounds truncate/clamp without swapping; explicit undefined rejects |
| xs.includes(value, fromIndex), xs.indexOf(value, fromIndex) | Optional relative start; first match, SameValueZero versus strict equality; indexOf returns -1 if absent |
| names.join(separator) | Required string separator; string elements join without source mutation |
| xs.map(mapper) | Fresh result array; explicit synchronous data result |
| xs.find(predicate), xs.findIndex(predicate) | First original matching value or undefined; index or -1 |
| xs.filter(predicate) | Fresh shallow selection of original values |
| xs.some(predicate), xs.every(predicate) | Early boolean result; empty defaults false/true |
| Array.from(source, mapper) | Fresh mapped array using live array-source traversal |

Map/predicates receive `(value: E, index: number, source: A)` with A matching
the receiver's writable/readonly type; from receives
`(value: E, index: number)`. Context supplies omitted parameter annotations;
results stay explicit. Shorter callbacks adapt normally. All declared parameter/
return checks remain, including source-array arguments ignored by shorter callbacks.

Map/find/findIndex snapshot length after callback evaluation, read live slots and
ignore later growth; missing reached slots fail. Filter/some/every use that snapshot
but skip holes, not stored undefined. From and ordinary for-of use live traversal.
Join/search/slice observe post-argument length. References keep identity; returned
arrays have fresh storage. Narrow/assert/coalesce optional found values before use.

## JSON

Import-free JSON.parse(text) accepts any JSON root. Syntax, duplicate decoded keys
and target mismatches throw Error. Annotated initializers, assignments, fields,
elements, parameters and returns supply reconstruction targets through parentheses/
conditional arms. Assertions check completed values, never supply decoding targets.

Without context, parse returns unknown containing Records and unknown[] arrays.
Context reconstructs fresh exact typed arrays/nominal classes. Required fields must
exist; extras reject before declaration-ordered fields; omitted optionals stay
absent. Recursive schemas work; reachable function types reject even in optional
fields. Unknown/Record stop schema traversal. No defaults/hooks run.

JSON preserves parsed negative zero but serializes either zero as 0.
JSON.stringify returns string|undefined, omits object undefined/functions,
substitutes null for array undefined/functions and nonfinite numbers, and rejects
cycles. Shared acyclic children work. Records follow Object.keys order; class fields
follow declaration order.

## Modules

Import namespaces and qualify exported classes; keep state in function-owned
storage. Imports initialize once; cycles reject. Preserve manifest roots/backend/
output/languageVersion/externals and keep ancestor manifests unambiguous.

## String operations

Use read-only s.length, substring(start[,end]), slice(start[,end]),
includes/startsWith/endsWith/indexOf/lastIndexOf(search[,position]),
replace(search,replacement), split(separator), trim(), toLowerCase(), toUpperCase().
Methods require direct calls. Indices/length/slices count UTF-16 units; slice has
relative bounds. For-of combines valid surrogate pairs and retains lone units.
Casing uses locale-independent Unicode default mappings, expansions and final sigma.

## Builtin calls

Math.floor/ceil/sqrt/abs/round take one number; Math.min/max/pow take two. Preserve NaN
and signed zero; round ties toward +infinity and returns binary64, not a bounded
integer. `n.toFixed(digits)` formats with ECMAScript decimal rounding; supply an
integral digits value in 0–100 (otherwise E8001). String(number) uses ECMAScript decimal text. Number(string) uses whole-
string conversion: malformed -> NaN, empty/whitespace -> zero; exponents, radix
prefixes and Infinity work. Number.isFinite/isNaN accept unknown without coercion/
narrowing. Date.now() returns epoch milliseconds. console.log/error take strings.
Builtins need no imports, require direct calls and cannot be shadowed.

## Standard library declarations

```deal
// std/table
export function keys(t: Record<string, unknown>): string[];
```

Use declared, implemented APIs; do not invent host methods.

## Error handling

Contextually construct Error with required string name/message fields, then throw.
Fields remain mutable; contextual JSON.parse can reconstruct Error. Catch binds
unknown: extract Error or use instanceof Error before member reads. Rethrow preserves
identity. Translate failures only when the application contract requires it.

## Async/Await

Promise<R> denotes async results, not first-class promises. External declarations
omit async. Await calls directly inside async bodies; embedding owns scheduling/
lifetime. See [client](../examples/host/client.deal).

## Host ABI and interoperability

Host imports require declarations, manifest wiring and matching implementations.
Declaration functions end with semicolons; wrappers validate arguments/results.
Invoke async exports through the embedding ABI, not plain executable runs.
