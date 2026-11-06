# DEAL language guide

DEAL: a strict TypeScript syntax subset with distinct semantics. Authority: [v1.6 specification](https://github.com/arkts-dev/deal/blob/master/docs/spec-v1.6.md) (online).

## Syntax

Imports precede module-level functions, classes and exports; executable statements stay inside functions. Use let or const, braces and semicolons. Const requires an initializer and forbids binding reassignment, including captured and for-of bindings; referenced arrays, Records and class instances remain mutable. Entry exports synchronous main(): null returning null; no-result helpers use void.

Comments: // or /* ... */. `$` bindings are compiler-owned. Property keys need explicit values. Assign with x = x + 1. Function expressions use function or explicitly typed arrows, such as (x: number): number => x * 2. Arrows admit block or expression bodies; a void expression body must itself produce void. Async arrows use Promise<T> results and await normally. Keep async with its parameter opening and the result annotation with => on the same line.

Ordinary strings decode newline, carriage-return, tab, backslash and quote escapes, hexadecimal \\xHH/\\uHHHH units and \\u{H…} code points. Strings retain UTF-16 lone surrogates across backends and host transport. Untagged templates admit multiline text, normalize physical CR/CRLF to LF and remove escaped line continuations.

## Types

Data: null, undefined, unknown, boolean, number, string, Record<string, unknown>, nominal classes, invariant arrays and function types. Nullish unions admit null/undefined independently. Void is result-only.

Unknown requires checked extraction or typeof/instanceof refinement. Unlike TypeScript's erased as, DEAL assertions check once without coercion, preserving identity; mismatch raises E8001.

Numbers are binary64, preserving signed zero, infinities and NaN; division is nontrapping. The % operator is ECMAScript remainder, retaining the dividend's sign (including signed zero), not floor modulo. Array indexes require finite whole numbers. Round deliberately through math.floor/ceil; use String(n) for ECMAScript decimal text. Conditions require boolean. String concatenation requires strings; template interpolations accept strings or numbers and format numbers with ECMAScript decimal conversion (including NaN and infinities, with either zero rendered as 0). Use ===/!== with compatible types or admitted nullish comparisons.

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

Arrays are zero-based with runtime-enforced invariant descriptors, unlike TypeScript arrays; emptiness cannot justify retagging. Annotate empty literals. Append at length; iterate with inferred for-of bindings or explicit while indexes. Deleted slots read undefined; out-of-bounds reads fail. Decoded JSON arrays carry unknown elements: check values individually or use generated typed decoding.

## JSON serialization (`@jsonable`)

Attach // @jsonable to an exported class. Generated C$fromJson(string): C | null and C$toJson(C): string implement its schema. Decoding requires nonoptional fields, rejects extra/duplicate keys and invalid nested values, and preserves optional presence. Use acyclic class dependencies.

JSON parses binary64, preserving negative zero; either zero sign serializes as 0. Generic stringify omits object undefined/functions, substitutes null in arrays/nonfinite positions and rejects cycles without hooks. Shared acyclic children are valid. Generated dynamic Record-content walkers instead reject unsupported leaves; declared numeric fields encode nonfinite as null.

## Modules

Import namespaces and qualify exported classes; keep state inside function-owned storage. Imports initialize at most once; cycles reject. Preserve manifest roots, backend, output, languageVersion and external declarations. Keep ancestor manifests unambiguous.

## String operations

Use s.length (read-only UTF-16 unit count), s.substring(start, end), s.includes(part), s.startsWith(part), s.endsWith(part), s.replace(search, replacement), s.split(separator), and s.trim(). These receiver intrinsics require direct calls, not detached method values. Arguments follow ECMAScript string semantics; for-of combines valid surrogate pairs while length/slicing/splitting retain individual units.

## Standard library declarations

```deal
// std/console
export function log(x: string): void;
export function error(x: string): void;
// std/table
export function keys(t: Record<string, unknown>): string[];
// std/json
export function parse(s: string): Record<string, unknown>;
export function stringify(value: unknown): string | undefined;
// std/math
export function floor(x: number): number;
export function ceil(x: number): number;
export function sqrt(x: number): number;
export function absNumber(x: number): number;
// std/time
export function nowMillis(): number;
```

Use declared, implemented host APIs.

## Error handling

Throw Error values with explicit code/message fields. Catch binds unknown: extract Error or refine inside instanceof Error before reading members. Rethrow preserves identity. Translate expected failures only when the application contract requires it.

## Async/Await

Promise<R> denotes async results, not first-class promises. External declarations omit async. Await each async call directly inside async bodies; the embedding owns scheduling/lifetime. See [client](../examples/host/client.deal).

## Host ABI and interoperability

Host imports require declarations, manifest wiring and matching implementations. Declaration functions end with semicolons; boundary wrappers validate arguments/results. Invoke async exports through the embedding ABI, not a plain executable run.
