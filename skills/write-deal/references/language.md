# DEAL language guide

Practical guide, not a specification. Authoritative source:
[DEAL v1.2](https://github.com/arkts-dev/deal/blob/master/docs/spec-v1.2.md).

## Syntax

Place imports first, then functions, classes, and exports. Put executable work inside functions. An executable entry exports synchronous `main(): null`. Use `let`, braces, semicolons, and `return null` for no-result functions.

Comments use `//` or `/* ... */`. Choose ordinary identifiers for declarations; `$` names belong to compiler-generated helpers. Literal property keys are identifiers with explicit values.

Assignments use `x = x + 1`. Write callbacks as function expressions; `=>` describes their types.

## Types

Built-ins: `null`, `boolean`, `int`, `number`, `string`, `bytes`, `table`, `Error`. Compose nominal classes, homogeneous `T[]`, nullable `T | null`, and function types.

Types are invariant. Integer literals are int; decimal/exponent literals are number. Int is signed 32-bit; number is IEEE 754 double. Integer arithmetic checks overflow; division truncates toward zero and rejects zero divisors.

Explicit `number(intValue)` converts to floating point. `int(numberValue)` requires an integral, finite, in-range value. Round deliberately before converting. Use a typed helper or declared host API for text conversion.

Equality uses `===`/`!==` with equal types, except nullable comparison with null. Conditions and logical operators require boolean. String concatenation and interpolation accept strings; format other values explicitly.

## Classes

Classes describe nominal records. Required fields have defaults; optional fields use `?`. Construct through typed object literals with declared fields. Defaults apply per construction; mutable literal defaults are fresh. Same-shaped classes remain distinct.

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

Arrays are zero-based. Annotate empty literals. Append at length and iterate with `i < values.length` or `for-of`. Reads check their result type; invalid indexes can raise errors. Strings in `for-of` yield Unicode scalars. Transform collections with explicit loops.

## Bytes

`bytes(length)` zero-fills fixed storage; use a nonnegative length. Keep indexes below length and writes within `0..255`. Reads return int. Failed writes leave storage unchanged. Assignment aliases storage; copy explicitly for independence.

## JSON serialization (`@jsonable`)

Attach `// @jsonable` immediately to an exported class. Call its generated module helpers: `C$fromJson(string): C | null`, `C$toJson(C): string`.

Parsing rejects extra keys, applies defaults, preserves presence, and returns null on invalid input. Serialization omits missing fields and preserves explicit null. Choose JSON-compatible fields and acyclic class dependencies. Handle non-JSON data, non-finite numbers, and cycles explicitly.

## Modules

Import with `import * as name from "..."`. Namespace exports are statically typed. Export functions and classes; keep module state local to function-owned storage. Arrange runtime dependencies acyclically; initialization occurs at most once.

The manifest supplies module roots, output, backend, languageVersion, and external declarations. Preserve existing settings and keep ancestor manifests unambiguous.

## Standard library declarations

```deal
// std/console
export function log(x: string): null;
export function error(x: string): null;
// std/string
export function length(s: string): int;
export function substring(s: string, start: int, end: int): string;
export function contains(s: string, part: string): boolean;
export function startsWith(s: string, part: string): boolean;
export function endsWith(s: string, part: string): boolean;
export function replace(s: string, from: string, to: string): string;
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
export function absInt(x: int): int;
export function absNumber(x: number): number;
export function minInt(a: int, b: int): int;
export function maxInt(a: int, b: int): int;
// std/time
export function nowMillis(): int;
```

String positions count Unicode scalars. Extend the available surface through declared, implemented host modules.

## Error handling

Throw an Error-typed literal. `catch (e)` binds Error with code and message. Translate expected failures where the application contract requires it; otherwise propagate.

## Async/Await

Async functions declare their completion type directly. Await each async call inside an async body, as a direct call expression. Let the embedding own scheduling and operation lifetime.

## Host ABI and interoperability

A host import requires `.d.deal`, a manifest external entry, and an embedding implementation. External function declarations end with semicolons. Boundary wrappers validate arguments/results. Invoke async exports through the embedding's documented ABI.

## C FFI declaration files

Inspect the C header and backend support before binding. `// @extern-c` follows imports and precedes declarations; its external entry includes `nativeLibrary`. Mark struct/pointer classes with `// @c-struct`/`// @c-pointer`.

Parameters admit int, number, boolean, string, bytes, and same-file struct/pointer classes. Returns admit scalar types, same-file struct/pointer classes, and null. Declare synchronous calls. Struct fields use defaulted int/number/boolean or same-file pointer classes; construct opaque pointer values through native functions.

Bytes cross as borrowed read-only pointer plus length; C must copy before retaining. Opaque pointers need explicit cleanup. Use C shims to adapt callbacks, out parameters, and ownership shapes. Verify linkage and a real call.
