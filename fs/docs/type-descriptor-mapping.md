# Runtime Type Descriptor Mapping Table

Mapping from DEAL runtime type descriptor strings (spec format) to
LuaJIT and JVM runtime representations.

**Authority**: `docs/spec-v1.1.md` §Runtime type descriptor format,
§LuaJIT value mapping, §JVM value mapping.

**Format**: All descriptors in this table use the spec's
`RuntimeTypeDescriptor` format (`?T` for nullables, `[T]` for arrays,
`async` prefix for async functions). The legacy `LuaBackend.typeDescriptor()`
format (`T[]`, `T|null`) is accepted by `runtime.lua` for backward
compatibility but is not the canonical format.

---

## Primitive types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `null` | `__rt.__NULL` sentinel table | `DealNull.INSTANCE` singleton | Unique sentinel distinct from Lua `nil` |
| `boolean` | Lua boolean | `boolean` (primitive) | |
| `int` | Lua number in `[-(2^53-1), 2^53-1]` | `long` (primitive) | Runtime-checked for safe integer range |
| `number` | Lua number (IEEE 754 double) | `double` (primitive) | |
| `string` | Lua string (UTF-8 byte sequence) | `java.lang.String` | |
| `table` | Lua table with string keys | `DealTable` runtime object | Dynamic key-value container |

## Builtin class types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `Error` | Lua table with `code` and `message` keys | `DealError` record class | Builtin nominal class; user must not declare |

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

## Nullable types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `?null` | INVALID — Nullable(null) is forbidden by spec | — | Spec constraint: inner must not be `null` |
| `?boolean` | `__NULL` sentinel or Lua boolean | `null` reference or `Boolean` | [specified, not yet implemented] |
| `?int` | `__NULL` sentinel or Lua number | `null` reference or `Long` | [specified, not yet implemented] |
| `?number` | `__NULL` sentinel or Lua number | `null` reference or `Double` | [specified, not yet implemented] |
| `?string` | `__NULL` sentinel or Lua string | `null` reference or `String` | [specified, not yet implemented] |
| `?table` | `__NULL` sentinel or Lua table | `null` reference or `DealTable` | [specified, not yet implemented] |
| `[?int]` | 1-based array table with __NULL or numbers | `DealArray<Long|null>` | Nullable array |
| `?@mod/Cls` | `__NULL` sentinel or tagged class table | `null` reference or generated record class | Nullable class |

## Class types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `@module/ClassName` | Lua table tagged with class symbol | Generated record class | Module path resolved from source root |

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

## Rest parameter types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `(...[int])->null` | Wrapper table with rest handling | `DealFunction` with varargs | Rest-only function |
| `(string,...[int])->null` | Wrapper table with rest handling | `DealFunction` with varargs | Mixed fixed+rest |
| `(int,...[string])->null` | Wrapper table with rest handling | `DealFunction` with varargs | Fixed int + rest string |

## Async function types

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `async()->null` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |
| `async(int)->string` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |
| `async(int,int)->int` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |
| `async(...[int])->null` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |
| `async(string,...[int])->null` | Coroutine-based wrapper | `DealAsyncFunction` wrapper | [specified, not yet implemented] |

## Edge cases

| DEAL Type Descriptor (spec format) | LuaJIT Representation | JVM Representation | Notes |
|---|---|---|---|
| `?@src/models/User` | `__NULL` or tagged class table | `null` reference or generated record | Nullable class |
| `[?@src/models/User]` | 1-based array of nullable class tables | `DealArray<nullable<User>>` | Array of nullable classes |
| `@mod/User` | Tagged class table | Generated record class | Class descriptor with module path |

---

## Descriptor grammar (normative)

From `docs/spec-v1.1.md` §Runtime type descriptor format:

```
RuntimeTypeDescriptor ::=
    PrimitiveDescriptor
    | ClassDescriptor
    | ArrayDescriptor
    | NullableDescriptor
    | FunctionDescriptor

PrimitiveDescriptor ::=
    "null" | "boolean" | "int" | "number" | "string" | "table"

ClassDescriptor ::=
    "@" ModuleRoot "/" ClassName

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
    | "..." ArrayDescriptor
```

**Constraints**:
- `NullableDescriptor` inner type must not be `null` and must not be another `NullableDescriptor`.
- `ParamDescriptor` with `...` requires `ArrayDescriptor` (not a bare primitive).
- `AsyncMarker` is only valid immediately before a `FunctionDescriptor`.
