# DEAL language guide

Practical guide, not a specification. Authority: the compiler's `docs/spec-v1.4.md`.

## Syntax

Place imports first, then functions, classes, and exports. Put executable work inside functions. An executable entry exports synchronous `main(): null`. Use `let`, braces, semicolons, and `return null` for no-result functions.

Comments use `//` or `/* ... */`. Choose ordinary identifiers for declarations; `$` names belong to compiler-generated helpers. Literal property keys are identifiers with explicit values.

Assignments use `x = x + 1`. Write callbacks as function expressions; `=>` describes their types.

## Types

Built-ins: `null`, `boolean`, `number`, `string`, `table`, `Error`. Compose nominal classes, homogeneous `T[]`, nullable `T | null`, and function types.

Types are invariant. Every numeric literal and operation uses IEEE 754 binary64, rounded nearest/ties-even. Arithmetic preserves signed zero, infinities, and NaN; division is nontrapping. Validate finite whole values for indexes and substring bounds. Round deliberately with `math.floor` or `math.ceil`. Use a typed helper or declared host API for text conversion.

Equality uses `===`/`!==` with equal types, except nullable comparison with null. Conditions and logical operators require boolean. String concatenation and interpolation accept strings; format other values explicitly.

## Classes

Classes describe nominal records and are module-level declarations only. Required fields have defaults; optional fields use `?`. Construct through typed object literals with declared fields. Defaults apply per construction; mutable literal defaults are fresh. Same-shaped classes remain distinct.

Use records for fixed schemas, tables for dynamic keys, functions for behavior. Pass records explicitly to those functions.

## Functions

Annotate parameters and return types. Pass explicit arrays for variable-length inputs. Function expressions use `function(...)`. Closures capture bindings; assignments remain visible. Captured loop variables have fresh iteration bindings.

Function types match async marker and exact types. An actual function with fewer parameters may adapt to a target with more when shared parameter and return types match; extra target arguments are ignored. Direct calls follow their declared signature.

## Field access

Copy nullable fields into locals, then narrow through direct null comparison. Recheck after assignment or await; keep narrowing within its function and iteration. Closures see captured variables' declared types.

`has(record.field)` tests optional-field presence. Read into a local separately to check its value. Missing reads yield null. Delete `field?: T` to make it absent. `field?: T | null` distinguishes missing, present-null, and present-value.

## Tables

Supply a target type for dynamic reads through declaration, assignment, return, argument, or boolean condition. Reads validate the stored value. Read intermediate tables explicitly. Invoke behavior through typed functions or module exports.

Dynamic indexes are strings. Writes may store heterogeneous values; select each read's type accordingly. Null assignment preserves presence; deletion removes the key.

## Arrays

Arrays are zero-based. Annotate empty literals. Append at length and iterate with `i < values.length` or `for-of`. Reads check their result type; a read outside `0..length-1` raises an index-out-of-bounds error. Strings in `for-of` yield Unicode scalars. Transform collections with explicit loops.

## JSON serialization (`@jsonable`)

Attach `// @jsonable` immediately to an exported class. Call its generated module helpers: `C$fromJson(string): C | null`, `C$toJson(C): string`.

Parsing rejects extra keys and duplicate keys, applies defaults, preserves presence, and returns null on invalid input. JSON numbers parse as binary64, preserving negative zero; serialization emits either zero sign as `0`. Serialization omits missing fields and preserves explicit null. Choose JSON-compatible fields and acyclic class dependencies. Handle non-JSON data, non-finite numbers, and cycles explicitly.

## Modules

Import with `import * as name from "..."`. Namespace exports are statically typed; qualify an imported class with the namespace (`models.User`). Export functions and classes; keep module state local to function-owned storage. Any import cycle is a compile-time error; initialization occurs at most once.

The manifest supplies module roots, output, backend, languageVersion, and external declarations. Preserve existing settings and keep ancestor manifests unambiguous.

## Standard library declarations

```deal
// std/console
export function log(x: string): null;
export function error(x: string): null;
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
export function keys(t: table): string[];
// std/json
export function parse(s: string): table;
export function stringify(t: table): string;
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

Throw an Error-typed literal. `Error` is a builtin class with `code` and `message` string fields; `catch (e)` binds it. Translate expected failures where the application contract requires it; otherwise propagate.

## Async/Await

Async functions declare their completion type directly. Await each async call inside an async body, as a direct call expression. Let the embedding own scheduling and operation lifetime.

## Host ABI and interoperability

A host import requires `.d.deal`, a manifest external entry, and an embedding implementation. External function declarations end with semicolons. Boundary wrappers validate arguments/results. Invoke async exports through the embedding's documented ABI.
