# DEAL language guide

Practical guide, not a specification. Authority: the compiler's `docs/spec-v1.5.md`.

## Syntax

Place imports first, then functions, classes, and exports. Put executable work inside functions. An executable entry exports synchronous `main(): null`. Use `let`, braces, and semicolons. No-result helpers return `void` with bare return or fallthrough; the executable entry returns `null` explicitly.

Comments use `//` or `/* ... */`. Choose ordinary identifiers for declarations; `$` names belong to compiler-generated helpers. Literal property keys are identifiers with explicit values.

Assignments use `x = x + 1`. Write callbacks as function expressions; `=>` describes their types.

## Types

Data types include `null`, `undefined`, `unknown`, `boolean`, `number`, `string`, `Record<string, unknown>`, and builtin nominal `Error`. Compose nominal classes, homogeneous `T[]`, independent `T | null` / `T | undefined` unions, and function types. `void` and `Promise<R>` are function-result notation only. Unknown data requires checking before concrete consumption; `value as T` checks once without coercion and raises E8001 on mismatch. `typeof` and matching `instanceof` branches support bounded refinement.

Types are invariant. Every numeric literal and operation uses IEEE 754 binary64, rounded nearest/ties-even. Arithmetic preserves signed zero, infinities, and NaN; division is nontrapping. Validate finite whole values for indexes and substring bounds. Round deliberately with `math.floor` or `math.ceil`. Use a typed helper or declared host API for text conversion.

Equality uses `===`/`!==` with equal types, except comparison with null or undefined where admitted by the nullish/unknown rules. Conditions and logical operators require boolean. String concatenation and interpolation accept strings; format other values explicitly.

## Classes

Classes describe nominal records and are module-level declarations only. Declare fields by name and type; optional fields use `?`. Construct through typed object literals that supply every required field, including nullable fields. Supply separate mutable literals when independent instances need fresh storage. Same-shaped classes remain distinct.

Use nominal classes for fixed schemas, `Record<string, unknown>` for dynamic keys, and functions for behavior. Pass values explicitly to those functions.

## Functions

Annotate parameters and return types. Pass explicit arrays for variable-length inputs. Function expressions use `function(...)`. Closures capture bindings; assignments remain visible. Captured loop variables have fresh iteration bindings.

Function types match async marker and exact types. An actual function with fewer parameters may adapt to a target with more when shared parameter and return types match; extra target arguments are ignored. Direct calls follow their declared signature.

## Field access

Copy nullable fields into locals, then narrow through direct null comparison. Recheck after assignment or await; keep narrowing within its function and iteration. Closures see captured variables' declared types.

`Object.hasOwn(record, "field")` tests presence independently of value. Class keys must be literal declared-field names; Record keys may be arbitrary strings. Presence never refines the read type. Missing optional reads yield undefined. Delete `field?: T` to make it absent. `field?: T | null` distinguishes missing, present-undefined, present-null, and present-value when admitted by its annotation.

## Tables

Declare dynamic data as `Record<string, unknown>`. Reads infer unknown in every position; concrete consumption requires an assertion or refinement, not an implicit contextual conversion. Check intermediate Records explicitly and check callable signatures before invoking dynamic behavior.

Dynamic indexes are strings. Writes may store heterogeneous supported data. Null and undefined assignment preserve presence; deletion removes the key.

## Arrays

Arrays are zero-based. Annotate empty literals. Append at length and iterate with `i < values.length` or inferred `for (let value of values)` bindings. Arrays retain invariant element descriptors; a compatible current payload or an empty array does not permit retagging. Generic decoded JSON arrays carry unknown elements; check each value or use a typed generated decoder. Reads check their result type; a read outside `0..length-1` raises an index-out-of-bounds error. Strings in `for-of` yield Unicode scalars. Transform collections with explicit loops.

## JSON serialization (`@jsonable`)

Attach `// @jsonable` immediately to an exported class. Call its generated module helpers: `C$fromJson(string): C | null`, `C$toJson(C): string`.

Parsing requires every nonoptional field, rejects extra keys and duplicate keys, preserves optional presence, and returns null on invalid input. JSON numbers parse as binary64, preserving negative zero; serialization emits either zero sign as `0`. Serialization omits missing fields and preserves explicit null. Choose JSON-compatible fields and acyclic class dependencies. Stringification omits undefined/functions in objects, substitutes null in arrays and for nonfinite numbers, and rejects cycles without calling user hooks. Shared acyclic children remain valid.

## Modules

Import with `import * as name from "..."`. Namespace exports are statically typed; qualify an imported class with the namespace (`models.User`). Export functions and classes; keep module state local to function-owned storage. Any import cycle is a compile-time error; initialization occurs at most once.

The manifest supplies module roots, output, backend, languageVersion, and external declarations. Preserve existing settings and keep ancestor manifests unambiguous.

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

String positions count Unicode scalars. Extend the available surface through declared, implemented host modules.

## Error handling

Throw an Error-typed literal. `Error` is a builtin class with `code` and `message` string fields; `catch (e)` binds unknown; use a checked `e as Error` extraction or refine inside a matching `e instanceof Error` branch. Throw and rethrow still require Error. Translate expected failures where the application contract requires it; otherwise propagate.

## Async/Await

Async implementations declare `Promise<R>` results; async function types use `(args) => Promise<R>`. External declarations omit the `async` modifier and use Promise result notation. Await each async call inside an async body, as a direct call expression. Let the embedding own scheduling and operation lifetime.

## Host ABI and interoperability

A host import requires `.d.deal`, a manifest external entry, and an embedding implementation. External function declarations end with semicolons. Boundary wrappers validate arguments/results. Invoke async exports through the embedding's documented ABI.
