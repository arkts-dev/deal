# Runtime Type Descriptor Mapping Table

Mapping from DEAL runtime type descriptor strings (spec format) to
LuaJIT and JVM runtime representations.

**Authority**: `docs/spec-v1.1.md` §Runtime type descriptor format,
§LuaJIT value mapping, §JVM value mapping.

**Format**: All descriptors in this table use the spec's
`RuntimeTypeDescriptor` format (`?T` for nullables, `[T]` for arrays,
`async` prefix for async functions). The legacy `LuaBackend.typeDescriptor()`
format (`T[]`, `T|null`) is accepted by `runtime.lua` for backward
compatibility but is not the canonical format. ISSUE-0082 cycle 3 narrows
the legacy dialect: `typeDescriptor` emits the spec prefix forms for
function-involving nullables and arrays (`?(int)->int`, `[(int)->int]`,
`[?(int)->int]`), while every other nullable/array keeps the legacy
`T|null`/`T[]` spelling. `runtime.lua` parses with order P: `?T` prefix →
async strip + function branch → `|null` end-anchored suffix → `[]` suffix →
`[T]` prefix → class → primitives → bare class name, so no emitted
descriptor is ambiguous between `Nullable(Func)` and `Func(ret=Nullable)`
or between `Array(Func)`-family and `Func(ret=Array)`-family.

**DEAL v1.2**: rest parameters were removed from the language
(spec-v1.2 §Types and §Runtime type descriptor format), so the function
descriptor carries no rest arm: `ParamDescriptor ::= RuntimeTypeDescriptor`
only.  The legacy `...` rest rows and the `...T[]` emission dialect are
retired with v1.1.

---

## Primitive types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `null` | `__rt.__NULL` sentinel table | `DealNull.INSTANCE` singleton | Unique sentinel distinct from Lua `nil` |
| `boolean` | Lua boolean | `boolean` (primitive) | |
| `int` | Lua number in `[-(2^53-1), 2^53-1]` | `long` (primitive) | Runtime-checked for safe integer range |
| `number` | Lua number (IEEE 754 double) | `double` (primitive) | |
| `string` | Lua string containing valid UTF-8 (spec-v1.2: a DEAL `string` is a Unicode scalar-value sequence; values crossing untrusted or backend-native boundaries must reject invalid encodings) | `java.lang.String` with no unpaired surrogate code units | |
| `table` | Lua table with string keys | `DealTable` runtime object | Dynamic key-value container |

## Builtin class types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `Error` | Class-tagged Lua table (`__kind="class"`, `__classname="Error"`) with `code` and `message` keys | `DealError` record class | Builtin nominal class; user must not declare. Values are reified by `__rt.error_value` (throw/catch) or `__rt.class_` (construction); the bare `Error` tag is the only bare class identity that reaches codegen |

## Array types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `[null]` | Lua table with 1-based contiguous sentinels | `DealArray<DealNull>` | [specified, not yet implemented] |
| `[boolean]` | Lua table with 1-based contiguous booleans | `DealArray<Boolean>` | [specified, not yet implemented] |
| `[int]` | Lua table with 1-based contiguous numbers | `DealArray<Long>` | [specified, not yet implemented] |
| `[number]` | Lua table with 1-based contiguous numbers | `DealArray<Double>` | [specified, not yet implemented] |
| `[string]` | Lua table with 1-based contiguous strings | `DealArray<String>` | [specified, not yet implemented] |
| `[table]` | Lua table with 1-based contiguous tables | `DealArray<DealTable>` | [specified, not yet implemented] |
| `[[int]]` | Nested Lua table (1-based) | `DealArray<DealArray<Long>>` | Nested array descriptor |
| `[(int)->int]` | Lua table of function wrappers | `DealArray<DealFunction>` | Array of functions (cycle 3: `typeDescriptor` emits brackets whenever the element descriptor contains `->`, so `[(int)->int]` cannot be misread as `(int)->int[]`) |
| `[?(int)->int]` | Lua table of nullable-function wrappers | `DealArray<DealFunction|null>` | Array of nullable functions (cycle 3) |

## Nullable types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `?null` | INVALID — Nullable(null) is forbidden by spec | — | Spec constraint: inner must not be `null` |
| `??T` | INVALID — inner must not be another NullableDescriptor | — | Spec constraint (`docs/spec-v1.1.md` §Runtime type descriptor format); enforced in the compiler by the `Type.Nullable` constructor invariant (`deal/types/Type.java:66-68` → E3005), so no producer can emit it (cycle 6); guard fixture `frontend/types/chained-nullable-e3005.deal` |
| `?boolean` | `__NULL` sentinel or Lua boolean | `null` reference or `Boolean` | [specified, not yet implemented] |
| `?int` | `__NULL` sentinel or Lua number | `null` reference or `Long` | [specified, not yet implemented] |
| `?number` | `__NULL` sentinel or Lua number | `null` reference or `Double` | [specified, not yet implemented] |
| `?string` | `__NULL` sentinel or Lua string | `null` reference or `String` | [specified, not yet implemented] |
| `?table` | `__NULL` sentinel or Lua table | `null` reference or `DealTable` | [specified, not yet implemented] |
| `[?int]` | 1-based array table with __NULL or numbers | `DealArray<Long|null>` | Nullable array — the spec spelling of the emitted legacy `int|null[]`. Cycle 4: the hand-written `?T[]` family reads `Nullable(Array(T))` under parse order P (`?` binds first); `Array(Nullable(T))` keeps the emitted legacy `T|null[]` spelling |
| `?(int)->int` | `__NULL` or a function wrapper `{__kind="function", sig="(int)->int", f}` | `null` reference or `DealFunction` | Nullable function value (cycle 3: `typeDescriptor` emits `?` whenever the nullable inner is a function, so `?(int)->int` cannot be misread as `(int)->int|null`; order P reads the `?` before the arrow) |
| `?@mod/Cls` | `__NULL` sentinel or tagged class table | `null` reference or generated record class | Nullable class |

## Class types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `@module/ClassName` | Lua table tagged with the module-qualified class identity — `__classname` equals the exact descriptor string (`@module/ClassName`) | Generated record class | Identity by exact descriptor-string equality: construction, META export, and @jsonable deserialization all tag with the same string; module path resolved from source root |

## Function types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `()->null` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | No-arg function returning null |
| `(int)->int` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | Single-arg |
| `(int,int)->int` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | Multi-arg |
| `(int,string)->boolean` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | Mixed params |
| `(int)->?string` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | Nullable return |
| `([int])->int` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | Array param |
| `(?int)->int` | Wrapper table `{__kind="function", sig="...", f}` | `DealFunction` wrapper | Nullable param |

## Async function types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `async()->null` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |
| `async(int)->string` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |
| `async(int,int)->int` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |

## Edge cases

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `?@src/models/User` | `__NULL` or tagged class table | `null` reference or generated record | Nullable class |
| `[?@src/models/User]` | 1-based array of nullable class tables | `DealArray<nullable<User>>` | Array of nullable classes |
| `@mod/User` | Lua table tagged with the descriptor-equal class identity (`__classname` = `@mod/User`) | Generated record class | Class descriptor with module path |

---

## Descriptor grammar (normative)

From `docs/spec-v1.1.md` §Runtime type descriptor format:

```
RuntimeTypeDescriptor ::=
    PrimitiveDescriptor
    | BuiltinClassDescriptor
    | ClassDescriptor
    | ArrayDescriptor
    | NullableDescriptor
    | FunctionDescriptor

PrimitiveDescriptor ::=
    "null" | "boolean" | "int" | "number" | "string" | "table"

BuiltinClassDescriptor ::=
    "Error"

ClassDescriptor ::=
    "@" ModuleRoot "/" ModuleRelativePath "/" ClassName

ModuleRoot ::= /* the configured module root from deal.json, e.g. "src", "lib/utils" */
ModuleRelativePath ::= /* path components from module root to defining file, e.g. "models"; may be empty for root-level classes */

ArrayDescriptor ::=
    "[" RuntimeTypeDescriptor "]"

NullableDescriptor ::=
    "?" RuntimeTypeDescriptor

FunctionDescriptor ::=
    AsyncMarker? "(" ParamDescriptorList? ")" "->" RuntimeTypeDescriptor

AsyncMarker ::=
    "async"

ParamDescriptorList ::=
    ParamDescriptor ("," ParamDescriptor)*

ParamDescriptor ::=
    RuntimeTypeDescriptor
```

**Constraints**:
- `NullableDescriptor` inner type must not be `null` and must not be another `NullableDescriptor`.
- `AsyncMarker` is only valid immediately before a `FunctionDescriptor`.

**Notes**:
- Parsing drift (ISSUE-0082, cycle 3): `runtime.lua`'s `parse_descriptor` adopts order P — `?T` prefix → async strip + function branch → `|null` end-anchored suffix → `[]` suffix → `[T]` prefix → class → primitives → bare class name. Any future descriptor form must keep (a) the top-level arrow recognized before suffix stripping and (b) the `?`/`[T]` prefixes recognized before the function branch, or function descriptors with nullable/array returns and function-involving arrays regress. Emission must never conflate `Nullable(Func)` with `Func(ret=Nullable)` or `Array(Func)`-family with `Func(ret=Array)`-family — the `?F`/`[...]` emission invariants are the guard.
- Rest-arm removal (DEAL v1.2): function descriptors have no rest arm.  A descriptor containing `...` fails to parse; the v1.1 `...T[]` / `...[T]` dialect is retired with the language feature it described.
- Hand-written `?T[]` family (cycle 4): under order P `?` binds first, so `?string[]`/`?int[]` read `Nullable(Array(T))`; `Array(Nullable(T))` is spelled `T|null[]` (legacy dialect, emitted) / `[?T]` (spec). No producer emits `?T[]`.
- Nested nullables (cycle 6): the `??T` family has no parsing rule and needs none — `Type.Nullable` construction rejects Nullable inners (`deal/types/Type.java:66-68`) and the checker reports E3005, so no producer can emit it; `frontend/types/chained-nullable-e3005.deal` guards the constructor invariant.
- The `Error` builtin class is a nominal type with special runtime representation; it is not a primitive but is handled as a standalone builtin in the descriptor grammar.
- The `ClassDescriptor` production uses two path components (`ModuleRoot` and `ModuleRelativePath`) as defined in the spec. In practice, `ModuleRelativePath` may be empty when the class is defined at the module root level, yielding a descriptor like `@src//User`; the compiler's IR dumper may collapse consecutive slashes for readability.
