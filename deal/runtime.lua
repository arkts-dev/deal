-- DEAL Runtime Library v0.8
-- Provides type checks, integer arithmetic, class construction, function wrapping,
-- and async/await infrastructure.
-- Loaded by every generated Lua module via require("deal.runtime").
--
-- All check_* functions accept optional trailing (file, line, column) arguments
-- for source-location tracking in runtime errors. When omitted, these default to nil.

local __rt = {}

-- ===== Sentinels =====
__rt.__NULL = {}
__rt.__MISSING = {}

-- ===== Error formatting =====

--- Create an error table in DEALRuntimeError format.
-- nil fields are naturally absent from the returned table.
function __rt._err(code, message, file, line, column, expected, actual)
  return {
    code = code,
    message = message,
    file = file,
    line = line,
    column = column,
    expected = expected,
    actual = actual
  }
end

-- ===== Primitive type checks =====

function __rt.check_null(v, file, line, column)
  if v ~= __rt.__NULL then
    error(__rt._err("E8001", "expected null", file, line, column, "null", type(v)))
  end
  return v
end

function __rt.check_boolean(v, file, line, column)
  if type(v) ~= "boolean" then
    error(__rt._err("E8001", "expected boolean", file, line, column, "boolean", type(v)))
  end
  return v
end

function __rt.check_int(v, file, line, column)
  if type(v) ~= "number" then
    error(__rt._err("E8001", "expected int", file, line, column, "int", type(v)))
  end
  if v ~= v then  -- NaN check: NaN is the only value not equal to itself
    error(__rt._err("E8001", "expected int, got NaN", file, line, column, "int", "NaN"))
  end
  v = v + 0  -- normalize -0 to 0 (per spec.md lines 60-65, 2024)
  if v == math.huge or v == -math.huge then
    error(__rt._err("E8001", "expected int, got infinity", file, line, column, "int", "infinity"))
  end
  if v % 1 ~= 0 then
    error(__rt._err("E8001", "expected int, got non-integer number", file, line, column, "int", "number"))
  end
  if v < -9007199254740991 or v > 9007199254740991 then
    error(__rt._err("E8004", "int out of safe range", file, line, column, nil, nil))
  end
  return v
end

function __rt.check_number(v, file, line, column)
  if type(v) ~= "number" then
    error(__rt._err("E8001", "expected number", file, line, column, "number", type(v)))
  end
  return v
end

function __rt.check_string(v, file, line, column)
  if type(v) ~= "string" then
    error(__rt._err("E8001", "expected string", file, line, column, "string", type(v)))
  end
  return v
end

function __rt.check_table(v, file, line, column)
  if type(v) ~= "table" then
    error(__rt._err("E8001", "expected table", file, line, column, "table", type(v)))
  end
  return v
end

-- ===== Composite type checks =====

--- Check a nullable value.
-- nil (missing optional field) and __NULL (explicit null) both return __NULL.
-- Otherwise delegates to check_type for the inner descriptor.
function __rt.check_nullable(inner_descriptor, v, file, line, column)
  if v == nil or v == __rt.__NULL then
    return __rt.__NULL
  end
  return __rt.check_type(inner_descriptor, v, file, line, column)
end

--- Extract the element descriptor from an array descriptor.
-- Supports "T[]" format (e.g., "int[]" → "int", "int[][]" → "int[]")
-- and "[T]" format (e.g., "[int]" → "int").
function __rt.array_element_descriptor(array_descriptor, file, line, column)
  if array_descriptor == nil then
    error(__rt._err("E8001", "internal: nil array descriptor", file, line, column, nil, nil))
  end
  -- Format: "T[]" → element descriptor is "T"
  local len = #array_descriptor
  if len >= 2 and array_descriptor:sub(len - 1) == "[]" then
    return array_descriptor:sub(1, len - 2)
  end
  -- Format: "[T]" → element descriptor is "T"
  if len >= 2 and array_descriptor:sub(1, 1) == "[" and array_descriptor:sub(len, len) == "]" then
    return array_descriptor:sub(2, len - 1)
  end
  error(__rt._err("E8001", "invalid array descriptor: " .. tostring(array_descriptor), file, line, column, nil, nil))
end

--- Check that value is an array whose elements match the array descriptor.
function __rt.check_array(array_descriptor, v, file, line, column)
  if type(v) ~= "table" then
    error(__rt._err("E8001", "expected array", file, line, column, "array", type(v)))
  end
  local element_descriptor = __rt.array_element_descriptor(array_descriptor, file, line, column)
  for i = 1, #v do
    local ok, err = pcall(__rt.check_type, element_descriptor, v[i], file, line, column)
    if not ok then
      error(__rt._err("E8003", "array element " .. i .. " type mismatch: " .. tostring(err), file, line, column, element_descriptor, type(v[i])))
    end
  end
  return v
end

-- ===== Descriptor parser =====

--- Parse a type descriptor string and return a structured representation.
-- Returns a table: { kind = "primitive"|"array"|"nullable"|"function"|"class", ... }
-- For async functions, the returned function table has isAsync = true and ret = "null".
local function parse_descriptor(descriptor)
  if descriptor == nil or type(descriptor) ~= "string" then
    return nil
  end

  local d = descriptor

  -- Nullable: "T|null" format (check for "|null" suffix but NOT for function "->" which also contains "|" in different contexts)
  -- The "|null" suffix marks nullable. We need to be careful: "(int)->int|null" has both.
  -- Strategy: find "|null" suffix that is not inside brackets or parentheses.
  local null_pos = nil
  local depth = 0
  for i = 1, #d do
    local c = d:sub(i, i)
    if c == "(" or c == "[" then
      depth = depth + 1
    elseif c == ")" or c == "]" then
      depth = depth - 1
    elseif depth == 0 and i + 4 <= #d and d:sub(i, i + 4) == "|null" then
      -- Check that this is a suffix (followed by end of string or nothing relevant)
      local rest = d:sub(i + 5)
      if rest == "" then
        null_pos = i
        break
      end
    end
  end
  if null_pos then
    return { kind = "nullable", inner = d:sub(1, null_pos - 1) }
  end

  -- Array: "T[]" format (suffix "[]")
  if #d >= 2 and d:sub(#d - 1) == "[]" then
    return { kind = "array", element = d:sub(1, #d - 2) }
  end

  -- Array: "[T]" format (prefix "[" suffix "]")
  if #d >= 2 and d:sub(1, 1) == "[" and d:sub(#d, #d) == "]" then
    return { kind = "array", element = d:sub(2, #d - 1) }
  end

  -- Nullable: "?T" format
  if d:sub(1, 1) == "?" then
    return { kind = "nullable", inner = d:sub(2) }
  end

  -- Function: "(params)->ret" or "async(params)->ret" format
  -- The "async" prefix is recognized inside the function branch, after
  -- nullable/array wrappers have been peeled.
  local is_async = false
  if d:sub(1, 5) == "async" then
    is_async = true
    d = d:sub(6)  -- strip "async" prefix, leaving "(params)->ret"
  end

  if d:sub(1, 1) == "(" then
    local arrow_pos = nil
    depth = 0
    for i = 1, #d do
      local c = d:sub(i, i)
      if c == "(" or c == "[" then
        depth = depth + 1
      elseif c == ")" or c == "]" then
        depth = depth - 1
      elseif depth == 0 and i + 1 <= #d and d:sub(i, i + 1) == "->" then
        arrow_pos = i
        break
      end
    end
    if arrow_pos then
      local params_str = d:sub(2, arrow_pos - 2)  -- content between ( and )
      -- Check if the ')' before -> is at arrow_pos-1
      if d:sub(arrow_pos - 1, arrow_pos - 1) == ")" then
        local ret_type = d:sub(arrow_pos + 2)
        -- Parse params: comma-separated, but need to respect nesting
        local params = {}
        if params_str ~= "" then
          depth = 0
          local start = 1
          for i = 1, #params_str do
            local c = params_str:sub(i, i)
            if c == "(" or c == "[" then
              depth = depth + 1
            elseif c == ")" or c == "]" then
              depth = depth - 1
            elseif depth == 0 and c == "," then
              params[#params + 1] = params_str:sub(start, i - 1)
              start = i + 1
            end
          end
          params[#params + 1] = params_str:sub(start)
        end
        -- Async functions return "null" for ret so from_lua_function skips
        -- return-type checking on the outer wrapper. The actual return-type
        -- validation is done inside the coroutine body via codegen-emitted checks.
        if is_async then
          return { kind = "function", params = params, ret = "null", isAsync = true }
        end
        return { kind = "function", params = params, ret = ret_type }
      end
    end
  end

  -- Class: "@path/ClassName" format
  if d:sub(1, 1) == "@" then
    return { kind = "class", name = d }
  end

  -- Primitive types
  local primitives = {
    ["null"] = true,
    ["boolean"] = true,
    ["int"] = true,
    ["number"] = true,
    ["string"] = true,
    ["table"] = true,
  }
  if primitives[d] then
    return { kind = "primitive", name = d }
  end

  -- Assume it's a class name (simple identifier)
  -- Could be "ClassName" without the "@" prefix for local classes
  return { kind = "class", name = d }
end

-- ===== Type dispatch =====

--- Dispatch type check by descriptor string.
function __rt.check_type(descriptor, v, file, line, column)
  if descriptor == nil then
    error(__rt._err("E8001", "internal: nil type descriptor", file, line, column, nil, nil))
  end

  local parsed = parse_descriptor(descriptor)
  if parsed == nil then
    error(__rt._err("E8001", "internal: cannot parse type descriptor: " .. tostring(descriptor), file, line, column, nil, nil))
  end

  if parsed.kind == "primitive" then
    if parsed.name == "null" then
      return __rt.check_null(v, file, line, column)
    elseif parsed.name == "boolean" then
      return __rt.check_boolean(v, file, line, column)
    elseif parsed.name == "int" then
      return __rt.check_int(v, file, line, column)
    elseif parsed.name == "number" then
      return __rt.check_number(v, file, line, column)
    elseif parsed.name == "string" then
      return __rt.check_string(v, file, line, column)
    elseif parsed.name == "table" then
      return __rt.check_table(v, file, line, column)
    else
      error(__rt._err("E8001", "unknown primitive type: " .. parsed.name, file, line, column, nil, nil))
    end
  elseif parsed.kind == "nullable" then
    return __rt.check_nullable(parsed.inner, v, file, line, column)
  elseif parsed.kind == "array" then
    return __rt.check_array(descriptor, v, file, line, column)
  elseif parsed.kind == "function" then
    -- Check that v is a function wrapper with matching signature
    if type(v) ~= "table" or v.__kind ~= "function" then
      error(__rt._err("E8001", "expected function", file, line, column, "function", type(v)))
    end
    -- Signature comparison: the stored sig must match the expected descriptor
    if v.sig ~= descriptor then
      error(__rt._err("E8010", "function signature mismatch: expected " .. descriptor .. ", got " .. (v.sig or "nil"), file, line, column, descriptor, v.sig))
    end
    return v
  elseif parsed.kind == "class" then
    -- Check that v is a class instance with matching class name
    if type(v) ~= "table" or v.__kind ~= "class" then
      error(__rt._err("E8001", "expected class instance", file, line, column, "class", type(v)))
    end
    -- For class checking, we compare class names
    -- The descriptor may be "@path/ClassName" or just "ClassName"
    local expected_class = parsed.name
    if expected_class:sub(1, 1) == "@" then
      -- Extract just the class name from the path
      local last_slash = nil
      for i = #expected_class, 1, -1 do
        if expected_class:sub(i, i) == "/" then
          last_slash = i
          break
        end
      end
      if last_slash then
        expected_class = expected_class:sub(last_slash + 1)
      else
        expected_class = expected_class:sub(2)  -- remove "@"
      end
    end
    local actual_class = v.__classname
    if actual_class ~= expected_class then
      error(__rt._err("E8001", "expected instance of " .. expected_class .. ", got " .. (actual_class or "unknown"), file, line, column, expected_class, actual_class))
    end
    return v
  else
    error(__rt._err("E8001", "internal: unhandled descriptor kind: " .. parsed.kind, file, line, column, nil, nil))
  end
end

-- ===== Integer arithmetic =====

function __rt.int_add(a, b, file, line, column)
  return __rt.check_int(a + b, file, line, column)
end

function __rt.int_sub(a, b, file, line, column)
  return __rt.check_int(a - b, file, line, column)
end

function __rt.int_mul(a, b, file, line, column)
  return __rt.check_int(a * b, file, line, column)
end

function __rt.int_div(a, b, file, line, column)
  if b == 0 then
    error(__rt._err("E8005", "integer division by zero", file, line, column, nil, nil))
  end
  return __rt.check_int(math.modf(a / b), file, line, column)
end

function __rt.int_mod(a, b, file, line, column)
  if b == 0 then
    error(__rt._err("E8005", "integer division by zero", file, line, column, nil, nil))
  end
  return __rt.check_int(a - math.modf(a / b) * b, file, line, column)
end

function __rt.int_pow(a, b, file, line, column)
  if b < 0 then
    error(__rt._err("E8006", "integer exponent must be non-negative", file, line, column, nil, nil))
  end
  return __rt.check_int(a ^ b, file, line, column)
end

-- ===== Function infrastructure =====

--- Create a function wrapper.
-- The wrapper is a table { __kind = "function", sig = sig, f = f }.
-- No __call metamethod is set; callers use wrapper.f(...).
function __rt.function_(sig, f)
  return { __kind = "function", sig = sig, f = f }
end

--- Extract the raw Lua function from a wrapper for host interop.
function __rt.as_lua_function(fn)
  if type(fn) ~= "table" or fn.__kind ~= "function" then
    error(__rt._err("E8001", "expected function wrapper", nil, nil, nil, "function", type(fn)))
  end
  return fn.f
end

--- Wrap a plain Lua function with runtime parameter and return type checks.
-- Parses the signature descriptor to determine expected parameter types and return type.
-- For async functions (parsed.isAsync == true), return-type checking is skipped
-- because the outer wrapper returns an async handle, not the declared return type.
function __rt.from_lua_function(sig, raw_f)
  if type(raw_f) ~= "function" then
    error(__rt._err("E8001", "expected function, got " .. type(raw_f), nil, nil, nil, "function", type(raw_f)))
  end

  local parsed = parse_descriptor(sig)
  if parsed == nil or parsed.kind ~= "function" then
    error(__rt._err("E8010", "invalid function signature: " .. tostring(sig), nil, nil, nil, nil, nil))
  end

  local param_descriptors = parsed.params
  local ret_descriptor = parsed.ret

  return __rt.function_(sig, function(...)
    local nargs = select("#", ...)
    local has_rest = false
    local rest_descriptor = nil

    -- Check if last param is rest parameter ("...T[]" format)
    local required_params = #param_descriptors
    if required_params > 0 then
      local last = param_descriptors[required_params]
      if last:sub(1, 3) == "..." then
        has_rest = true
        rest_descriptor = __rt.array_element_descriptor(last:sub(4))  -- extract element type from "...T[]" rest param
        required_params = required_params - 1
      end
    end

    -- Check minimum arg count
    if nargs < required_params then
      error(__rt._err("E8010", "expected at least " .. required_params .. " arguments, got " .. nargs, nil, nil, nil, nil, nil))
    end
    -- Check maximum arg count (no rest means exact match)
    if not has_rest and nargs > required_params then
      error(__rt._err("E8010", "expected " .. required_params .. " arguments, got " .. nargs, nil, nil, nil, nil, nil))
    end

    -- Check required parameters
    for i = 1, required_params do
      local arg = select(i, ...)
      local param_desc = param_descriptors[i]
      local ok, err = pcall(__rt.check_type, param_desc, arg)
      if not ok then
        error(__rt._err("E8010", "parameter " .. i .. " type mismatch: " .. tostring(err), nil, nil, nil, param_desc, type(arg)))
      end
    end

    -- Check rest parameters (if any)
    if has_rest and rest_descriptor then
      for i = required_params + 1, nargs do
        local arg = select(i, ...)
        local ok, err = pcall(__rt.check_type, rest_descriptor, arg)
        if not ok then
          error(__rt._err("E8010", "rest parameter " .. i .. " type mismatch: " .. tostring(err), nil, nil, nil, rest_descriptor, type(arg)))
        end
      end
    end

    -- Call the raw function
    local results = { raw_f(...) }
    local nresults = #results

    -- Check return type
    -- Skip for async functions (ret_descriptor = "null") and void functions.
    if ret_descriptor ~= "null" then
      for i = 1, nresults do
        local ok, err = pcall(__rt.check_type, ret_descriptor, results[i])
        if not ok then
          error(__rt._err("E8010", "return value " .. i .. " type mismatch: " .. tostring(err), nil, nil, nil, ret_descriptor, type(results[i])))
        end
      end
    end

    return unpack(results, 1, nresults)
  end)
end

-- ===== Async infrastructure =====

--- Create an async handle wrapping a coroutine.
-- The handle is an opaque table that the awaiter manages.
-- @param fn function — the coroutine body (a Lua function)
-- @return table — async handle
function __rt.async_create(fn)
  local co = coroutine.create(fn)
  return { __kind = "async", __co = co, __done = false, __result = nil }
end

--- Start an async operation: create a handle and step it.
-- Returns the async handle immediately. The coroutine runs until
-- it yields or completes.
-- @param fn function — the coroutine body
-- @return table — async handle (opaque to user code)
function __rt.async_start(fn)
  local handle = __rt.async_create(fn)
  __rt.async_step(handle)
  return handle
end

--- Step an async handle: resume its coroutine and chain if it yields.
-- If the handle is already done (__done guard), return immediately.
-- If the coroutine is dead after resume, store the result and mark done.
-- If the coroutine yielded an async handle, chain: wait for the inner
-- handle to complete, then resume this one with the inner result.
-- @param handle table — the async handle to step
-- @param value  any    — value to pass to coroutine.resume (result of awaited call)
function __rt.async_step(handle, value)
  -- Guard: if already done, return immediately.
  -- This prevents coroutine.resume on a dead coroutine when
  -- an async function completes without internally executing await.
  if handle.__done then
    return
  end
  local ok, yielded = coroutine.resume(handle.__co, value)
  if not ok then
    -- Error propagates: error() unwinds to the nearest pcall/xpcall.
    error(yielded)
  end
  if coroutine.status(handle.__co) == "dead" then
    -- Coroutine completed: store result, mark done.
    handle.__done = true
    handle.__result = yielded
    return
  end
  -- Coroutine yielded. Expect an async handle.
  if type(yielded) == "table" and yielded.__kind == "async" then
    local inner_handle = yielded
    local outer_handle = handle
    if inner_handle.__done then
      -- Inner already done: directly resume outer with inner's result.
      __rt.async_step(outer_handle, inner_handle.__result)
    else
      -- Chain: wait for inner to complete, then resume outer.
      __rt.async_chain(inner_handle, outer_handle)
    end
  else
    error(__rt._err("E8001", "await expression must call an async function", nil, nil, nil, "async function", type(yielded)))
  end
end

--- Chain an inner async handle to an outer one.
-- When the inner handle completes, resume the outer with the result.
-- @param inner table — the inner async handle being awaited
-- @param outer table — the outer async handle that should resume
function __rt.async_chain(inner, outer)
  -- Guard: if inner is already done, directly resume outer.
  if inner.__done then
    __rt.async_step(outer, inner.__result)
    return
  end
  -- Step the inner handle. It may itself yield to deeper async calls.
  __rt.async_step(inner)
  -- After stepping, check if inner completed.
  if inner.__done then
    __rt.async_step(outer, inner.__result)
  end
end

-- ===== Class infrastructure =====

--- Construct a class instance.
-- @param classname string - the class name for tagging and error messages
-- @param defaults table - all declared fields: required fields with default values,
--        optional fields with __MISSING sentinel
-- @param provided table - user-provided field values
-- @param file string    - optional source file for error reporting
-- @param line number    - optional source line for error reporting
-- @param column number  - optional source column for error reporting
-- @return table - the constructed class instance
function __rt.class_(classname, defaults, provided, file, line, column)
  if type(defaults) ~= "table" then
    error(__rt._err("E8001", "class defaults must be a table", file, line, column, "table", type(defaults)))
  end

  -- 1. Deep-copy defaults (__MISSING and __NULL sentinels preserved by identity)
  local instance = __rt._deep_copy(defaults)

  -- 2. Overlay provided fields, rejecting extras
  if provided ~= nil then
    if type(provided) ~= "table" then
      error(__rt._err("E8001", "class field values must be a table", file, line, column, "table", type(provided)))
    end
    for k, v in pairs(provided) do
      if instance[k] == nil then
        -- Key is not in the defaults table → extra field
        error(__rt._err("E8007", "extra field '" .. tostring(k) .. "' in class '" .. classname .. "'", file, line, column, nil, nil))
      end
      instance[k] = v
    end
  end

  -- 3. Remove __MISSING entries: optional fields not provided stay missing (nil)
  for k, v in pairs(instance) do
    if v == __rt.__MISSING then
      instance[k] = nil
    end
  end

  -- 4. Tag with class name and kind
  instance.__classname = classname
  instance.__kind = "class"

  return instance
end

--- Create a class export descriptor for module exports.
function __rt.export_class(name)
  return { __kind = "class", __classname = name }
end

-- ===== Intrinsic support =====

--- Test field presence for the has() intrinsic.
-- Returns true if the field value is not nil.
-- Missing optional fields (nil) → false; explicit null (__NULL table) → true.
function __rt.has(obj, field)
  return obj[field] ~= nil
end

-- ===== Conversion intrinsics =====

--- Convert a value to int. Handles both overloads:
---   (number) => int      — converts number to int, validating range/integer-ness
---   (int | null) => int  — unwraps nullable int, rejecting null
--- Delegates to check_int for range, NaN, Infinity, and non-integer validation.
--- KNOWN LIMIT (v1.0): When used indirectly (assigned to a variable or passed
--- as a callback), the codegen emits `.f()` which fails at runtime. This is
--- because int/number are emitted as plain Lua local aliases, not function
--- wrappers. Direct calls like int(3.0) work correctly.
function __rt.int_convert(v, file, line, column)
  if v == nil or v == __rt.__NULL then
    error(__rt._err("E8001", "cannot convert null to int", file, line, column, "int", "null"))
  end
  -- Reuse check_int which validates range, NaN, Infinity, and integer-ness
  return __rt.check_int(v, file, line, column)
end

--- Convert a value to number. Handles both overloads:
---   (int) => number           — converts int to number
---   (number | null) => number — unwraps nullable number, rejecting null
--- Delegates to check_number for type validation.
--- KNOWN LIMIT (v1.0): Same indirect-use limitation as int_convert.
function __rt.number_convert(v, file, line, column)
  if v == nil or v == __rt.__NULL then
    error(__rt._err("E8001", "cannot convert null to number", file, line, column, "number", "null"))
  end
  return __rt.check_number(v, file, line, column)
end

-- ===== Internal helpers =====

--- Deep-copy a table recursively.
-- Preserves sentinel identity: __NULL and __MISSING are returned as-is.
-- Functions, function wrappers, class instances, and async handles are
-- not copied (returned as-is).
-- Handles cycles gracefully by... actually, doesn't handle cycles (class defaults shouldn't be cyclic).
function __rt._deep_copy(t)
  if type(t) ~= "table" then
    return t
  end
  -- Preserve sentinel identity
  if t == __rt.__NULL or t == __rt.__MISSING then
    return t
  end
  -- Preserve special __kind objects by identity
  if t.__kind == "function" or t.__kind == "class" or t.__kind == "async" then
    return t
  end
  local copy = {}
  for k, v in pairs(t) do
    copy[k] = __rt._deep_copy(v)
  end
  return copy
end

-- ===== JSON serialization helpers (v1.1 @jsonable) =====

--- Deserialize a parsed JSON table into a tagged class instance.
-- Operates on an already-parsed Lua table (from json.parse via pcall).
-- Does NOT call json.parse itself — the JSON I/O boundary is in generated code.
--
-- @param descriptor string  classifier for error messages and __classname tag
-- @param parsed     table   already-decoded JSON object (from json.parse)
-- @param defaults   table   default values per field (contains __NULL/__MISSING sentinels)
-- @param fields     array   array of field descriptor tables
-- @return tagged instance table on success, or nil on validation failure
function __rt.json_from_json(descriptor, parsed, defaults, fields)
  -- 1. Validate that all keys in parsed are declared in fields
  local valid_keys = {}
  for _, f in ipairs(fields) do
    valid_keys[f.name] = true
  end
  for k, _ in pairs(parsed) do
    if not valid_keys[k] then
      return nil
    end
  end

  -- 2. Start with deep-copied defaults (preserves __NULL/__MISSING sentinels by identity)
  local instance = __rt._deep_copy(defaults)

  -- 3. Overlay parsed values with type validation
  for _, f in ipairs(fields) do
    local raw = parsed[f.name]
    if raw ~= nil then
      if raw == __rt.__NULL and f.nullable then
        -- Explicit null on a nullable field
        instance[f.name] = __rt.__NULL
      elseif f.jtype == "class" then
        -- Nested class: recursive deserialization
        if type(raw) ~= "table" then
          return nil
        end
        local nested = __rt.json_from_json(f.className, raw, f.defaults, f.fields)
        if nested == nil then
          return nil
        end
        instance[f.name] = nested
      elseif f.jtype == "array" then
        -- Array: iterate elements, deserialize each recursively
        if type(raw) ~= "table" then
          return nil
        end
        local arr = {}
        for i = 1, #raw do
          local ev = raw[i]
          if f.element.jtype == "class" then
            if type(ev) ~= "table" then
              return nil
            end
            local nested = __rt.json_from_json(f.element.className, ev, f.element.defaults, f.element.fields)
            if nested == nil then
              return nil
            end
            arr[i] = nested
          elseif f.element.jtype == "array" then
            -- Nested array: recursively process via json_from_json with synthetic descriptor
            if type(ev) ~= "table" then
              return nil
            end
            local nested_arr = {}
            for j = 1, #ev do
              local eev = ev[j]
              if f.element.element.jtype == "class" then
                if type(eev) ~= "table" then return nil end
                local nested = __rt.json_from_json(f.element.element.className, eev, f.element.element.defaults, f.element.element.fields)
                if nested == nil then return nil end
                nested_arr[j] = nested
              else
                nested_arr[j] = eev
              end
            end
            arr[i] = nested_arr
          else
            -- Primitive element: store directly
            arr[i] = ev
          end
        end
        instance[f.name] = arr
      else
        -- Primitive type: store raw value directly
        instance[f.name] = raw
      end
    end
    -- Key absent: keep default from step 2
  end

  -- 4. Remove __MISSING entries: optional fields not provided stay missing (nil)
  for k, v in pairs(instance) do
    if v == __rt.__MISSING then
      instance[k] = nil
    end
  end

  -- 5. Tag with class name and kind
  instance.__classname = descriptor
  instance.__kind = "class"

  return instance
end

--- Serialize a class instance to a JSON-compatible Lua table.
-- Operates on an already-tagged class instance (from class_ or json_from_json).
-- Does NOT call json.stringify — the JSON I/O boundary is in generated code.
--
-- @param descriptor string  classifier for error messages
-- @param value      table   class instance table
-- @param fields     array   array of field descriptor tables
-- @return table suitable for json.stringify
function __rt.json_to_json(descriptor, value, fields)
  local result = {}
  for _, f in ipairs(fields) do
    local v = value[f.name]
    if v == nil and f.optional then
      -- Missing optional: omit key from output
    elseif v == __rt.__NULL and f.nullable then
      -- Explicit null on nullable field: emit __NULL (json.stringify encodes as null)
      result[f.name] = __rt.__NULL
    elseif f.jtype == "class" then
      -- Nested class: recursive serialization
      result[f.name] = __rt.json_to_json(f.className, v, f.fields)
    elseif f.jtype == "array" then
      -- Array: iterate elements, serialize each recursively
      local arr = {}
      for i = 1, #v do
        local ev = v[i]
        if f.element.jtype == "class" then
          arr[i] = __rt.json_to_json(f.element.className, ev, f.element.fields)
        elseif f.element.jtype == "array" then
          -- Nested array: recursively process
          local nested_arr = {}
          for j = 1, #ev do
            local eev = ev[j]
            if f.element.element.jtype == "class" then
              nested_arr[j] = __rt.json_to_json(f.element.element.className, eev, f.element.element.fields)
            else
              nested_arr[j] = eev
            end
          end
          arr[i] = nested_arr
        else
          arr[i] = ev
        end
      end
      result[f.name] = arr
    else
      -- Primitive type: assign directly
      result[f.name] = v
    end
  end
  return result
end

return __rt
