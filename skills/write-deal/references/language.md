# DEAL language guide

DEAL: a strict TypeScript syntax subset with distinct semantics. Authority: [v1.6 specification](https://github.com/arkts-dev/deal/blob/master/docs/spec-v1.6.md) (online).

## Syntax

Imports precede module-level functions, classes and exports; executable statements stay inside functions. Use let or const, braces and semicolons. Const requires an initializer and forbids binding reassignment, including captured and for-of bindings; referenced arrays, Records and class instances remain mutable. Entry exports synchronous main(): null returning null; no-result helpers use void.

Comments: // or /* ... */. `$` bindings are compiler-owned. Property keys need explicit values. Assign with x = x + 1. Function expressions use function or explicitly typed arrows, such as (x: number): number => x * 2. Arrows admit block or expression bodies; a void expression body must itself produce void. Async arrows use Promise<T> results and await normally. Keep async with its parameter opening and the result annotation with => on the same line.

Ordinary strings decode newline, carriage-return, tab, backspace (\\b), form-feed (\\f), vertical-tab (\\v), NUL (\\0, not followed by a digit), backslash and quote escapes, hexadecimal \\xHH/\\uHHHH units and \\u{H…} code points. Backslash plus LF/CR/CRLF continues a string without adding text. Strings retain UTF-16 lone surrogates across backends and host transport. Untagged templates admit multiline text, normalize physical CR/CRLF to LF and remove escaped line continuations.

## Types

Data: null, undefined, unknown, boolean, number, string, Record<string, unknown>, nominal classes, invariant arrays and function types. Nullish unions admit null/undefined independently. Void is result-only.

Unknown requires checked extraction or typeof/instanceof refinement. Unlike TypeScript's erased as, DEAL assertions check once without coercion, preserving identity; mismatch raises E8001.

Numbers are binary64, preserving signed zero, infinities and NaN; division is nontrapping. Literals admit decimal fractions/exponents, trailing decimal points, 0x/0b/0o radix prefixes and underscores between digits (for example 0xff, 0b10_01 and 1_234.5). Radix integers round once to binary64. The % operator is ECMAScript remainder, retaining the dividend's sign (including signed zero), not floor modulo. Array indexes require finite whole numbers. Round deliberately through Math.floor/ceil/round; use String(n) for ECMAScript decimal text. Conditions require boolean. String concatenation requires strings; template interpolations accept strings or numbers and format numbers with ECMAScript decimal conversion (including NaN and infinities, with either zero rendered as 0). Use ===/!== with compatible types or admitted nullish comparisons.

Use a ?? fallback to replace only null/undefined while preserving false, zero, NaN, empty strings and reference identity. The left evaluates once; the fallback runs only when selected and inherits an annotated destination's contextual type. Parenthesize combinations with &&/||. See the defaulted find result in [dynamic data](../examples/src/data.deal).

Use receiver?.field, receiver?.[key] or callback?.(argument) to skip the remaining chain when that receiver/callee is null/undefined. Receivers evaluate once; skipped keys and arguments do not run. Non-void results add undefined; coalesce or extract before concrete use. Parentheses end a chain. Optional calls are synchronous; void calls remain result-only. Delete optional class fields or Record keys with delete receiver?.field. See [dynamic data](../examples/src/data.deal).

## Classes

Classes are module-level nominal schemas, unlike TypeScript's structural compatibility: same-shaped classes remain distinct. Annotate fields; ? marks optional presence. Literals supply every required field, including nullable fields, allocating fresh storage; supplied references preserve aliases. Use Records for dynamic keys and functions for behavior.

## Functions

Annotate parameters/results; pass arrays for variable inputs. Closures capture bindings and observe assignments; loops create fresh iteration bindings. Function compatibility requires matching async marker and types. A shorter function may adapt to additional ignored target parameters when shared types/results match; direct calls retain declared arity.

## Field access

Read nullish fields into locals before narrowing. Assignment/await invalidate as specified; refinements stay within their function/iteration. Closures see declared captured types.

Object.hasOwn tests presence, not value type. Class keys name literal declared fields; Record keys are strings. Optional absence reads undefined; present null remains null. Deletion removes presence rather than storing undefined.

## Tables

Use Record<string, unknown>. Every read infers unknown; explicitly check intermediate Records, concrete leaves and callable signatures. String-keyed writes accept supported heterogeneous data. Null/undefined assignments preserve presence; deletion removes keys. See [dynamic data](../examples/src/data.deal).

## Arrays

Arrays are zero-based with runtime-enforced invariant descriptors, unlike TypeScript arrays; emptiness cannot justify retagging. Annotate empty literals. Use xs.push(value) to append one element and receive the new length, or assign at length; iterate with inferred for-of bindings or explicit while indexes. Push evaluates the receiver and value once, observes argument-driven growth before appending, and validates the stored element before mutation. Use xs.slice(start, end) for a fresh shallow range: negative bounds count from the end, bounds truncate and clamp without swapping, and referenced elements keep their identities. Both number bounds are required. Use xs.includes(value) for membership or xs.indexOf(value) for the first zero-based index (-1 when absent). An optional numeric fromIndex truncates and clamps; negative values count from the end. Includes matches NaN, while indexOf uses strict equality. References match by identity; each search stops at its first match. Use names.join(separator) on a string array to combine its elements into one string with the required string separator between positions; an empty array produces an empty string. Join reads current elements after evaluating the separator and leaves source storage unchanged. Use xs.map((value: E, index: number, array: E[]): R => ...) to build a fresh result array, with concrete E/R types and an explicit synchronous data result. Shorter callbacks use ordinary arity adaptation. Map snapshots the source length after mapper evaluation, reads current elements in ascending order and ignores later growth; missing reached positions fail. Callback parameter and return checks remain ordinary typed boundaries, including the source-array parameter. Use xs.find(predicate) for the first matching value (E | undefined), or xs.findIndex(predicate) for its index (-1 when absent). Their synchronous boolean predicates receive the same value/index/source arguments, with ordinary shorter-arity adaptation. They snapshot after predicate evaluation, read current slots and stop at the first true result; missing reached positions fail. Narrow, assert or coalesce a found optional value before concrete use. Use xs.filter(predicate) to collect selected original values in fresh shallow storage. Use xs.some(predicate) to stop at the first true result, or xs.every(predicate) to stop at the first false result. Empty arrays yield false for some and true for every. These three methods snapshot after predicate evaluation, ignore later growth and skip missing positions; stored undefined is a present value and must fit the element type. Predicates retain ordinary parameter checks, including the source-array argument even when omitted from a shorter callback. Out-of-bounds reads fail. Without a decoding target, JSON arrays carry unknown elements. Annotate a JSON.parse destination to reconstruct fresh arrays with the requested element descriptor.

## JSON

Use import-free JSON.parse(text). Any JSON root is valid; malformed syntax, duplicate decoded keys and target mismatches throw Error. An annotated initializer, assignment destination, field, element, parameter or return supplies the reconstruction target, including through parentheses and conditional branches. An assertion checks the parsed value but never supplies a decoding target.

Without a target, the result is unknown with ordinary Records and unknown-element arrays. Contextual arrays receive fresh typed storage; contextual classes receive fresh exact nominal instances. Required fields must be present, extras reject before declaration-ordered field decoding, and omitted optionals remain absent. Recursive class schemas are valid; a reachable function type rejects at compile time, even in an optional field. Unknown and Record stop schema traversal. No defaults or serialization hooks run.

JSON parses binary64, preserving negative zero; either zero sign serializes as 0. JSON.stringify returns string | undefined, omits object undefined/functions, substitutes null in arrays/nonfinite positions and rejects cycles without hooks. Shared acyclic children are valid. Record keys follow Object.keys order; class fields follow declaration order.

## Modules

Import namespaces and qualify exported classes; keep state inside function-owned storage. Imports initialize at most once; cycles reject. Preserve manifest roots, backend, output, languageVersion and external declarations. Keep ancestor manifests unambiguous.

## String operations

Use s.length (read-only UTF-16 unit count), s.substring(start) for the remaining suffix or s.substring(start, end), s.slice(start) or s.slice(start, end) for relative bounds (negative offsets count from the end), s.includes(part, position), s.startsWith(part, position), s.endsWith(part, endPosition), s.indexOf(search, position) and s.lastIndexOf(search, position) (each search position may be omitted), s.replace(search, replacement), s.split(separator), s.trim(), s.toLowerCase() and s.toUpperCase(). Casing uses locale-independent Unicode default mappings, including expansions such as "ß".toUpperCase() === "SS" and contextual final sigma. These receiver intrinsics require direct calls, not detached method values. Arguments follow ECMAScript string semantics; for-of combines valid surrogate pairs while length/slicing/splitting retain individual units.

## Builtin calls

Math.floor(x), Math.ceil(x), Math.sqrt(x), Math.abs(x), Math.round(x), Math.min(a,b) and Math.max(a,b) take numbers and follow ECMAScript, including NaN and signed zero. Round breaks ties toward positive infinity and returns a number, not a bounded integer. Number.isFinite(value) and Number.isNaN(value) accept unknown without coercion or type narrowing; nonnumbers return false. Date.now() returns epoch milliseconds. console.log(text) and console.error(text) write strings to the respective streams. These builtin namespaces require no imports and support direct calls only; builtin names cannot be shadowed.

## Standard library declarations

```deal
// std/table
export function keys(t: Record<string, unknown>): string[];
```

Use declared, implemented host APIs.

## Error handling

Construct nominal Error values with required string name/message fields, for example { name: "InvalidInput", message: "count must be nonnegative" }, then throw the value. Both fields remain mutable; JSON.parse can reconstruct Error under an explicit contextual target. Catch binds unknown: extract Error or refine inside instanceof Error before reading members. Rethrow preserves identity. Translate expected failures only when the application contract requires it.

## Async/Await

Promise<R> denotes async results, not first-class promises. External declarations omit async. Await each async call directly inside async bodies; the embedding owns scheduling/lifetime. See [client](../examples/host/client.deal).

## Host ABI and interoperability

Host imports require declarations, manifest wiring and matching implementations. Declaration functions end with semicolons; boundary wrappers validate arguments/results. Invoke async exports through the embedding ABI, not a plain executable run.
