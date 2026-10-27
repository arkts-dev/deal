# DEAL language guide

Practical guide; authority: `docs/spec-v1.5.md`.

## Syntax

Imports precede module-level functions, classes and exports. Executable statements belong inside functions. Use let, braces and semicolons. Entry exports synchronous main(): null with explicit null return; no-result helpers use void.

Comments use // or /* ... */. Choose ordinary identifiers; `$` bindings are compiler-owned. Property keys have explicit values. Assign with x = x + 1. Function expressions use function; arrows describe types.

## Types

Data includes null, undefined, unknown, boolean, number, string, Record<string, unknown>, nominal classes, invariant arrays and function types. Nullish unions admit null/undefined independently. Void and Promise<R> are result notation only.

Unknown requires checked extraction or supported typeof/instanceof refinement. An assertion checks once without coercion, preserving identity; mismatch raises E8001.

Numbers are binary64, preserving signed zero, infinities and NaN; division is nontrapping. Indexes/bounds require finite whole numbers. Round deliberately through math.floor/ceil; format through typed helpers or host APIs. Conditions require boolean; concatenation/interpolation require strings. Use ===/!== with compatible types or admitted nullish comparisons.

## Classes

Declare module-level nominal schemas. Fields use annotations and optional ?. Typed literals supply every required field, including nullable fields. Separate literals provide fresh mutable storage; supplied references preserve aliases. Same-shaped classes remain distinct. Use classes for schemas, Records for dynamic keys and functions for behavior.

## Functions

Annotate parameters/results; pass arrays for variable inputs. Closures capture bindings and observe assignments; loops create fresh iteration bindings. Function compatibility requires matching async marker and types. A shorter function may adapt to additional ignored target parameters when shared types/results match; direct calls retain declared arity.

## Field access

Read nullish fields into locals before narrowing. Assignment/await invalidate as specified; refinements stay within their function/iteration. Closures see declared captured types.

Object.hasOwn tests presence, not value type. Class keys name literal declared fields; Record keys are strings. Optional absence reads undefined; present null remains null. Deletion removes presence rather than storing undefined.

## Tables

Use Record<string, unknown>. Every read infers unknown; explicitly check intermediate Records, concrete leaves and callable signatures. String-keyed writes accept supported heterogeneous data. Null/undefined assignments preserve presence; deletion removes keys. See [dynamic data](../examples/src/data.deal).

## Arrays

Arrays are zero-based with invariant persistent descriptors; emptiness/current contents cannot justify retagging. Annotate empty literals. Append at length; iterate with inferred for-of bindings or explicit while indexes. Deleted slots read undefined; out-of-bounds reads fail. Decoded JSON arrays carry unknown elements: check values individually or use generated typed decoding. String for-of yields Unicode scalars.

## JSON serialization (`@jsonable`)

Attach // @jsonable to an exported class. Generated C$fromJson(string): C | null and C$toJson(C): string implement its schema. Decoding requires nonoptional fields, rejects extra/duplicate keys and invalid nested values, and preserves optional presence. Use acyclic class dependencies.

JSON parses binary64, preserving negative zero; either zero sign serializes as 0. Generic stringify omits object undefined/functions, substitutes null in arrays/nonfinite positions and rejects cycles without hooks. Shared acyclic children are valid. Generated dynamic Record-content walkers instead reject unsupported leaves; declared numeric fields encode nonfinite as null.

## Modules

Import namespaces and qualify exported classes; keep state inside function-owned storage. Imports initialize at most once; cycles reject. Preserve manifest roots, backend, output, languageVersion and external declarations. Keep ancestor manifests unambiguous.

## Standard library declarations

```deal
// std/console
export function log(x: string): void;
export function error(x: string): void;
// std/string
export function length(s: string): number;
export function substring(s: string, start: number, end: number): string;
export function contains(s: string, part: string): boolean;
export function startsWith(s: string, part: string): boolean;
export function endsWith(s: string, part: string): boolean;
export function replace(s: string, search: string, replacement: string): string;
export function split(s: string, sep: string): string[];
export function trim(s: string): string;
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

String positions count Unicode scalars. Extend APIs through declared, implemented host modules.

## Error handling

Throw Error values with explicit code/message fields. Catch binds unknown: extract Error or refine inside instanceof Error before reading members. Rethrow preserves identity. Translate expected failures only when the application contract requires it.

## Async/Await

Implementations and callable types use Promise<R> results. External declarations omit async. Await each async call directly inside an async body; the embedding owns scheduling and operation lifetime. See [client](../examples/host/client.deal).

## Host ABI and interoperability

Host imports require declarations, manifest wiring and matching implementations. Declaration functions end with semicolons; boundary wrappers validate arguments/results. Invoke async exports through the embedding ABI, not a plain executable run.
