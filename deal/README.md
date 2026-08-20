# DEAL Runtime Library

Shared Lua module providing type checks, integer arithmetic with overflow
detection, class construction, and function wrapping for DEAL-generated Lua code.

## Location

`deal/runtime.lua` — placed in the output directory by the compiler. Loaded by
every generated Lua module via `require("deal.runtime")`.

## Requirements

- LuaJIT 2.1.0-beta3 (`/usr/bin/luajit`)

## Tests

Run the test suite:

```bash
luajit test_runtime.lua
```

All tests must pass (exit code 0).

## Module API

See `runtime-library-design` wiki page for the full API contract.

### Sentinels

- `__rt.__NULL` — unique empty table representing DEAL `null`
- `__rt.__MISSING` — unique empty table for missing optional fields in class defaults

### Type Checks

- `check_null(v)`, `check_boolean(v)`, `check_int(v)`, `check_number(v)`,
  `check_string(v)`, `check_table(v)`, `check_coroutine(v)`
- v1.2: `check_string(v)` validates that the Lua string is well-formed
  UTF-8 encoding a sequence of Unicode scalar values (malformed byte
  sequences raise E8001); `utf8_valid(s)` and `utf8_next(s, i)` expose
  the scalar-value walk used by string for-of iteration and std/string
- `check_nullable(inner_descriptor, v)` — treats `nil` and `__NULL` as null
- `check_array(array_descriptor, v)` — validates each element
- `check_type(descriptor, v)` — dispatches by type descriptor string

### Integer Arithmetic

- `int_add`, `int_sub`, `int_mul`, `int_div`, `int_mod`, `int_pow`
- All check results with `check_int` for overflow detection

### Function Infrastructure

- `function_(sig, f)` — creates wrapper `{ __kind="function", sig, f }`
- `as_lua_function(fn)` — extracts raw function from wrapper
- `from_lua_function(sig, raw_f)` — wraps plain Lua function with type checks

### Class Infrastructure

- `class_(classname, defaults, provided)` — constructs class instance
- `export_class(name)` — creates class export descriptor
- `has(obj, field)` — tests field presence
