-- DEAL Runtime Library v1.2 (LuaJIT backend)
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

--- Reify a thrown/caught error value as a tagged builtin-Error class instance.
-- The trailing file/line/column arguments are optional: omitted arguments
-- leave the corresponding fields absent (nil), matching _err's convention.
-- Output satisfies check_type("Error", v) via the class branch's exact
-- __classname compare.
function __rt.error_value(code, message, file, line, column)
  return {
    __kind = "class",
    __classname = "Error",
    code = code,
    message = message,
    file = file,
    line = line,
    column = column
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

-- ===== Unicode scalar-value string support (v1.2) =====

--- Validate that a Lua string is well-formed UTF-8 encoding a sequence of
-- Unicode scalar values: no truncated sequences, no stray continuation
-- bytes, no overlong encodings, no UTF-16 surrogate code points
-- (U+D800..U+DFFF), and no values above U+10FFFF.
function __rt.utf8_valid(s)
  local n = #s
  local i = 1
  while i <= n do
    local b1 = string.byte(s, i)
    local len
    if b1 < 0x80 then
      len = 1
    elseif b1 >= 0xC2 and b1 <= 0xDF then
      len = 2
    elseif b1 >= 0xE0 and b1 <= 0xEF then
      len = 3
    elseif b1 >= 0xF0 and b1 <= 0xF4 then
      len = 4
    else
      return false
    end
    if i + len - 1 > n then
      return false
    end
    if len >= 2 then
      local b2 = string.byte(s, i + 1)
      if b2 == nil or b2 < 0x80 or b2 > 0xBF then return false end
      if len == 3 then
        local b3 = string.byte(s, i + 2)
        if b3 == nil or b3 < 0x80 or b3 > 0xBF then return false end
        if b1 == 0xE0 and b2 < 0xA0 then return false end  -- overlong
        if b1 == 0xED and b2 > 0x9F then return false end  -- surrogate U+D800..U+DFFF
      end
      if len == 4 then
        local b3 = string.byte(s, i + 2)
        local b4 = string.byte(s, i + 3)
        if b3 == nil or b3 < 0x80 or b3 > 0xBF then return false end
        if b4 == nil or b4 < 0x80 or b4 > 0xBF then return false end
        if b1 == 0xF0 and b2 < 0x90 then return false end  -- overlong
        if b1 == 0xF4 and b2 > 0x8F then return false end  -- > U+10FFFF
      end
    end
    i = i + len
  end
  return true
end

--- Advance one Unicode scalar value.
-- @param s string  a valid UTF-8 string
-- @param i number  0-based byte offset of the last consumed byte
-- @return next cursor (byte offset of the last byte of this scalar) and the
--         scalar value as a one-scalar string; nil when the string is
--         exhausted. Malformed UTF-8 raises E8001 (defensive — boundaries
--         validate strings with check_string before iteration).
function __rt.utf8_next(s, i)
  local n = #s
  local p = i + 1
  if p > n then
    return nil
  end
  local b1 = string.byte(s, p)
  local len
  if b1 < 0x80 then
    len = 1
  elseif b1 >= 0xC2 and b1 <= 0xDF then
    len = 2
  elseif b1 >= 0xE0 and b1 <= 0xEF then
    len = 3
  elseif b1 >= 0xF0 and b1 <= 0xF4 then
    len = 4
  else
    error(__rt._err("E8001", "expected string, got invalid UTF-8 encoding", nil, nil, nil, "string", "invalid UTF-8 string"))
  end
  if p + len - 1 > n then
    error(__rt._err("E8001", "expected string, got truncated UTF-8 sequence", nil, nil, nil, "string", "invalid UTF-8 string"))
  end
  local b2, b3, b4
  if len >= 2 then
    b2 = string.byte(s, p + 1)
    if b2 < 0x80 or b2 > 0xBF then
      error(__rt._err("E8001", "expected string, got invalid UTF-8 continuation byte", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
  end
  if len >= 3 then
    b3 = string.byte(s, p + 2)
    if b3 < 0x80 or b3 > 0xBF then
      error(__rt._err("E8001", "expected string, got invalid UTF-8 continuation byte", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
    if b1 == 0xE0 and b2 < 0xA0 then
      error(__rt._err("E8001", "expected string, got overlong UTF-8 encoding", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
    if b1 == 0xED and b2 > 0x9F then
      error(__rt._err("E8001", "expected string, got UTF-16 surrogate code point", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
  end
  if len == 4 then
    b3 = string.byte(s, p + 2)
    b4 = string.byte(s, p + 3)
    if b3 < 0x80 or b3 > 0xBF or b4 < 0x80 or b4 > 0xBF then
      error(__rt._err("E8001", "expected string, got invalid UTF-8 continuation byte", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
    if b1 == 0xF0 and b2 < 0x90 then
      error(__rt._err("E8001", "expected string, got overlong UTF-8 encoding", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
    if b1 == 0xF4 and b2 > 0x8F then
      error(__rt._err("E8001", "expected string, got code point above U+10FFFF", nil, nil, nil, "string", "invalid UTF-8 string"))
    end
  end
  return p + len - 1, string.sub(s, p, p + len - 1)
end

function __rt.check_string(v, file, line, column)
  if type(v) ~= "string" then
    error(__rt._err("E8001", "expected string", file, line, column, "string", type(v)))
  end
  -- v1.2 boundary rule: strings accepted from untrusted or backend-native
  -- boundaries must reject invalid encodings (malformed UTF-8 byte
  -- sequences).
  if not __rt.utf8_valid(v) then
    error(__rt._err("E8001", "expected string, got invalid UTF-8 encoding", file, line, column, "string", "invalid UTF-8 string"))
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
--
-- Parse order P (function-aware): (i) "?T" prefix → recurse; (ii) async strip +
-- function branch (leading "(", depth-aware top-level "->" scan with ")"
-- immediately before the arrow); (iii) "|null" end-anchored top-level suffix;
-- (iv) "[]" suffix; (v) "[T]" prefix; (vi) "@path/Name" class; (vii) primitives;
-- (viii) bare class name.
--
-- The function branch precedes every suffix rule, so "(int)->int|null" reads
-- Func(ret=Nullable) and "(int)->int[]" reads Func(ret=Array); the "?T" prefix
-- precedes the function branch, so "?(int)->int" reads Nullable(Func) and the
-- hand-written "?T[]" family reads Nullable(Array(T)). Every descriptor
-- typeDescriptor emits parses back to the type it denotes under this order.
local function parse_descriptor(descriptor)
  if descriptor == nil or type(descriptor) ~= "string" then
    return nil
  end

  local d = descriptor

  -- (i) Nullable: "?T" prefix (spec form). Must bind before the function
  -- branch and before every suffix rule.
  if d:sub(1, 1) == "?" then
    return { kind = "nullable", inner = d:sub(2) }
  end

  -- (ii) Function: "(params)->ret" or "async(params)->ret".
  -- The "async" prefix is recognized inside the function branch, after the
  -- "?T" prefix but before any suffix stripping.
  local is_async = false
  local d_fn = d
  if d_fn:sub(1, 5) == "async" then
    is_async = true
    d_fn = d_fn:sub(6)  -- strip "async" prefix, leaving "(params)->ret"
  end

  if d_fn:sub(1, 1) == "(" then
    local arrow_pos = nil
    local depth = 0
    for i = 1, #d_fn do
      local c = d_fn:sub(i, i)
      if c == "(" or c == "[" then
        depth = depth + 1
      elseif c == ")" or c == "]" then
        depth = depth - 1
      elseif depth == 0 and i + 1 <= #d_fn and d_fn:sub(i, i + 1) == "->" then
        arrow_pos = i
        break
      end
    end
    if arrow_pos then
      local params_str = d_fn:sub(2, arrow_pos - 2)  -- content between ( and )
      -- Check if the ')' before -> is at arrow_pos-1
      if d_fn:sub(arrow_pos - 1, arrow_pos - 1) == ")" then
        local ret_type = d_fn:sub(arrow_pos + 2)
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
        -- Async functions report ret="null" so from_lua_function routes to the
        -- async-operation shape check; the declared return type R is enforced
        -- at the await site, not by the wrapper.
        if is_async then
          return { kind = "function", params = params, ret = "null", isAsync = true }
        end
        return { kind = "function", params = params, ret = ret_type }
      end
    end
  end

  -- (iii) Nullable: "T|null" legacy suffix (end-anchored, top-level only).
  -- Reached only when the string is not a function descriptor, so a "|null"
  -- inside "(...)->..." can never win over the arrow.
  local null_pos = nil
  local depth = 0
  for i = 1, #d do
    local c = d:sub(i, i)
    if c == "(" or c == "[" then
      depth = depth + 1
    elseif c == ")" or c == "]" then
      depth = depth - 1
    elseif depth == 0 and i + 4 <= #d and d:sub(i, i + 4) == "|null" then
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

  -- (iv) Array: "T[]" legacy suffix.
  if #d >= 2 and d:sub(#d - 1) == "[]" then
    return { kind = "array", element = d:sub(1, #d - 2) }
  end

  -- (v) Array: "[T]" prefix (spec form).
  if #d >= 2 and d:sub(1, 1) == "[" and d:sub(#d, #d) == "]" then
    return { kind = "array", element = d:sub(2, #d - 1) }
  end

  -- (vi) Class: "@path/ClassName" format.
  if d:sub(1, 1) == "@" then
    return { kind = "class", name = d }
  end

  -- (vii) Primitive types.
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

  -- (viii) Assume it's a class name (simple identifier).
  -- Could be "ClassName" without the "@" prefix for local classes.
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
    -- Module-qualified nominal identity: the runtime tag equals the
    -- canonical class descriptor string, so identity is exact string
    -- equality — "@mod/User" vs "@other/User" vs bare "User" are three
    -- distinct identities (no module-path stripping).
    local actual_class = v.__classname
    if actual_class ~= parsed.name then
      error(__rt._err("E8001", "expected instance of " .. parsed.name .. ", got " .. (actual_class or "unknown"), file, line, column, parsed.name, actual_class))
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

--- Returns true when the descriptor denotes a function type or a
-- nullable-of-function type (per parse order P). Used to decide which
-- arguments must be adapted with as_lua_function before a host call.
local function is_function_type(descriptor)
  local parsed = parse_descriptor(descriptor)
  if parsed == nil then
    return false
  end
  if parsed.kind == "function" then
    return true
  end
  if parsed.kind == "nullable" then
    local inner = parse_descriptor(parsed.inner)
    return inner ~= nil and inner.kind == "function"
  end
  return false
end

--- Wrap a plain Lua function with runtime parameter and return type checks.
-- Parses the signature descriptor (parse order P) to determine the expected
-- parameter types and return type.
--
-- Three-way return dispatch:
-- 1. Async descriptor (parsed.isAsync == true): the call must produce at
--    least one result and every result must be an async operation table
--    ({ __kind = "async" }); the declared return type R is enforced at the
--    await site, not here.
-- 2. Non-null return (ret_descriptor ~= "null"): the call must produce at
--    least one result — zero results and a single explicit Lua nil both pack
--    to an empty list — and every result is checked against the declared
--    return descriptor, including T|null, T[], class, function, and the
--    ?F/[...] forms.
-- 3. Sync null return (ret_descriptor == "null" and not async): the call
--    must produce at least one result and every result must be the
--    __rt.__NULL sentinel (a plain Lua nil packs to an empty result list and
--    is rejected by the presence rule).
--
-- Function-typed parameters are adapted with __rt.as_lua_function before the
-- raw call, so hosts receive plain Lua functions; __NULL (and nil) arguments
-- on nullable-function parameters pass through unadapted.
--
-- DEAL v1.2 has no rest parameters: the parameter list is exact. A legacy
-- "...T" descriptor entry is not special — it parses as an unknown
-- (class-name fallback) descriptor and fails type checks like any unknown
-- descriptor.
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
  local is_async = parsed.isAsync == true

  return __rt.function_(sig, function(...)
    local nargs = select("#", ...)
    local required_params = #param_descriptors

    -- v1.2 exact arity: no rest parameters exist.
    if nargs < required_params then
      error(__rt._err("E8010", "expected at least " .. required_params .. " arguments, got " .. nargs, nil, nil, nil, nil, nil))
    end
    if nargs > required_params then
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

    -- Adapt function-typed arguments before the raw call so hosts receive
    -- plain Lua functions. __NULL and nil arguments on nullable-function
    -- parameters pass through unadapted.
    local adapted = {}
    local needs_adapt = false
    for i = 1, required_params do
      local arg = select(i, ...)
      if is_function_type(param_descriptors[i]) then
        needs_adapt = true
        if arg ~= nil and arg ~= __rt.__NULL then
          arg = __rt.as_lua_function(arg)
        end
      end
      adapted[i] = arg
    end

    -- Call the raw function
    local results
    if needs_adapt then
      results = { raw_f(unpack(adapted, 1, nargs)) }
    else
      results = { raw_f(...) }
    end
    local nresults = #results

    -- Return validation: three-way dispatch (see the doc comment above).
    if is_async then
      -- 1. Async: require an async operation result.
      if nresults < 1 then
        error(__rt._err("E8010", "host async function must return an async operation, got nothing", nil, nil, nil, "async operation", "nothing"))
      end
      for i = 1, nresults do
        local r = results[i]
        if type(r) ~= "table" or r.__kind ~= "async" then
          error(__rt._err("E8010", "host async function must return an async operation, got " .. type(r), nil, nil, nil, "async operation", type(r)))
        end
      end
    elseif ret_descriptor ~= "null" then
      -- 2. Non-null declared return: require at least one result, then check
      -- every result against the declared descriptor.
      if nresults < 1 then
        error(__rt._err("E8010", "return value 1 type mismatch: expected " .. ret_descriptor .. ", got nothing", nil, nil, nil, ret_descriptor, "nothing"))
      end
      for i = 1, nresults do
        local ok, err = pcall(__rt.check_type, ret_descriptor, results[i])
        if not ok then
          error(__rt._err("E8010", "return value " .. i .. " type mismatch: " .. tostring(err), nil, nil, nil, ret_descriptor, type(results[i])))
        end
      end
    else
      -- 3. Sync null return: require at least one result and every result
      -- must be the __rt.__NULL sentinel.
      if nresults < 1 then
        error(__rt._err("E8010", "return value 1 type mismatch: expected null, got nothing", nil, nil, nil, "null", "nothing"))
      end
      for i = 1, nresults do
        local ok, err = pcall(__rt.check_null, results[i])
        if not ok then
          error(__rt._err("E8010", "return value " .. i .. " type mismatch: " .. tostring(err), nil, nil, nil, "null", type(results[i])))
        end
      end
    end

    return unpack(results, 1, nresults)
  end)
end

--- Load and validate a host module at import time.
--
-- @param module_path string  the raw import specifier as written (required
--                            verbatim — never a dotted typing name)
-- @param declared    table   declared export name → descriptor string
--                            (function and class descriptors only)
-- @return fresh exports table containing every declared name (wrapped or
--         validated) plus <C>_defaults for each declared class and any
--         <C>_fields the raw host table supplied. Extra host exports are
--         structurally dropped; the raw host table is never mutated.
--
-- Errors: E8011 at load — require failure, non-table module result, missing
-- declared export, invalid function/class export shape, pre-wrapped sig
-- mismatch or non-function .f, class identity mismatch, missing/non-table
-- defaults, present-but-non-table <C>_fields. E8010 never fires at load for
-- legal declared maps (every emitted Type.Func descriptor parses as a
-- function under parse order P); call-time violations raise E8010 inside the
-- wrapped functions.
function __rt.load_host(module_path, declared)
  if type(declared) ~= "table" then
    error(__rt._err("E8011", "host module declarations must be a table", nil, nil, nil, "table", type(declared)))
  end

  local ok, raw = pcall(require, module_path)
  if not ok then
    error(__rt._err("E8011", "failed to load host module '" .. tostring(module_path) .. "': " .. tostring(raw), nil, nil, nil, nil, nil))
  end
  if type(raw) ~= "table" then
    error(__rt._err("E8011", "host module '" .. tostring(module_path) .. "' did not return a table", nil, nil, nil, "table", type(raw)))
  end

  local exports = {}
  for name, descriptor in pairs(declared) do
    local v = raw[name]
    if v == nil then
      error(__rt._err("E8011", "missing host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "'", nil, nil, nil, nil, nil))
    end
    local parsed = parse_descriptor(descriptor)
    if parsed ~= nil and parsed.kind == "function" then
      if type(v) == "function" then
        -- Raw Lua function: wrap with parameter/return/async-shape checks.
        exports[name] = __rt.from_lua_function(descriptor, v)
      elseif type(v) == "table" and v.__kind == "function" then
        -- Pre-wrapped export: the declared descriptor is the only trusted
        -- metadata. Validate the identity, then re-wrap .f so raw and
        -- pre-wrapped exports get identical call-time enforcement.
        if v.sig ~= descriptor then
          error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' has signature mismatch: expected " .. descriptor .. ", got " .. tostring(v.sig), nil, nil, nil, descriptor, v.sig))
        end
        if type(v.f) ~= "function" then
          error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' has non-function .f", nil, nil, nil, "function", type(v.f)))
        end
        exports[name] = __rt.from_lua_function(descriptor, v.f)
      else
        error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' is not a function", nil, nil, nil, "function", type(v)))
      end
    elseif parsed ~= nil and parsed.kind == "class" then
      -- Class meta: the identity must equal the declared descriptor exactly
      -- (module-qualified nominal identity).
      if type(v) ~= "table" or v.__kind ~= "class" then
        error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' is not a class meta table", nil, nil, nil, "class", type(v)))
      end
      if v.__classname ~= descriptor then
        error(__rt._err("E8011", "host class export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' has identity mismatch: expected " .. descriptor .. ", got " .. tostring(v.__classname), nil, nil, nil, descriptor, v.__classname))
      end
      exports[name] = v
      -- <C>_defaults is mandatory (construction depends on it).
      local defaults_key = name .. "_defaults"
      local defaults = raw[defaults_key]
      if type(defaults) ~= "table" then
        error(__rt._err("E8011", "host class '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' is missing its defaults table", nil, nil, nil, "table", type(defaults)))
      end
      exports[defaults_key] = defaults
      -- <C>_fields is optional: copied through when the raw host table
      -- supplies it (E8011 if present but not a table); absence is tolerated.
      local fields_key = name .. "_fields"
      local fields = raw[fields_key]
      if fields ~= nil then
        if type(fields) ~= "table" then
          error(__rt._err("E8011", "host class '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' supplies a non-table _fields value", nil, nil, nil, "table", type(fields)))
        end
        exports[fields_key] = fields
      end
    else
      error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' has an unsupported declared descriptor: " .. tostring(descriptor), nil, nil, nil, nil, nil))
    end
  end

  return exports
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
-- Emitted by the backend as a function-value wrapper (sig "(number)->int");
-- direct calls forward the call-site span through the wrapper.
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
-- Emitted by the backend as a function-value wrapper (sig "(int)->number");
-- direct calls forward the call-site span through the wrapper.
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

-- Shared validation foundation for the descriptor-driven JSON walkers
-- (jsonable-runtime-validation D1-D5). Every helper below is non-throwing:
-- it returns a boolean and never raises, so the no-throw decode guarantee
-- and the DEAL-error-only encode contract stay mechanical.

-- Maximum walker recursion depth (D5; JVM parity with
-- JvmBackend.JSON_TABLE_DEPTH_LIMIT = 512: entry at depth 0, every
-- recursive descent increments, depth > 512 rejects).
__rt._JSON_MAX_DEPTH = 512

--- True iff t is a JSON-object-shaped table: every key is a string
-- (empty tables qualify). Non-table t is false — the type guard runs
-- before any iteration, so no predicate can throw.
function __rt._json_is_object(t)
  if type(t) ~= "table" then
    return false
  end
  for k in pairs(t) do
    if type(k) ~= "string" then
      return false
    end
  end
  return true
end

--- True iff t is a dense-array-shaped table: the keys are exactly the
-- integers 1..n (empty tables qualify). Non-table t is false — the type
-- guard runs before any iteration.
function __rt._json_is_array(t)
  if type(t) ~= "table" then
    return false
  end
  local n = 0
  for k in pairs(t) do
    if type(k) ~= "number" then
      return false
    end
    if k < 1 or k % 1 ~= 0 then
      return false
    end
    if k > n then
      n = k
    end
  end
  for i = 1, n do
    if t[i] == nil then
      return false
    end
  end
  local count = 0
  for _ in pairs(t) do
    count = count + 1
  end
  return count == n
end

--- Non-throwing twin of check_int (see check_int above): false for
-- non-numbers, NaN, infinities, non-integers, and values outside the
-- int safe range; true otherwise.
function __rt._json_is_int(v)
  if type(v) ~= "number" then
    return false
  end
  if v ~= v then  -- NaN check: NaN is the only value not equal to itself
    return false
  end
  if v == math.huge or v == -math.huge then
    return false
  end
  if v % 1 ~= 0 then
    return false
  end
  if v < -9007199254740991 or v > 9007199254740991 then
    return false
  end
  return true
end

--- False for non-numbers, NaN, and infinities; true for every other
-- (finite) number.
function __rt._json_is_number(v)
  if type(v) ~= "number" then
    return false
  end
  if v ~= v or v == math.huge or v == -math.huge then
    return false
  end
  return true
end

--- True iff v is a JSON-shaped table datum (Contract 4 table row):
-- JSON-object or dense-array shape whose leaves are __NULL, booleans,
-- finite numbers, or strings, and whose nested tables satisfy the same
-- shape. Functions, function wrappers (__kind == "function"), class
-- instances (__kind == "class"), async handles (__kind == "async"),
-- NaN/Infinity leaves, cycles, and depth overruns are false; empty
-- tables are true. Non-table v is false — the guard runs before any
-- iteration. seen is the path-local set: pushed before recursion,
-- popped on every return (D4). depth counts nesting levels (D5);
-- nil seen/depth default to a fresh set and 0 so the helper is total.
--
-- Second return value (encode-side refinement, D3/D4/D5): a false
-- verdict carries a reason — "cycle" (seen re-entry), "depth" (nesting
-- past __rt._JSON_MAX_DEPTH), or "shape" (any other violation). The
-- boolean first return is the helper's contract — decode-side and
-- predicate callers read only that value, so the helper stays total
-- and non-throwing; the encoder maps the reason to the pinned E8001
-- messages.
function __rt._json_table_shape(v, seen, depth)
  if type(v) ~= "table" then
    return false, "shape"
  end
  if v.__kind == "function" or v.__kind == "class" or v.__kind == "async" then
    return false, "shape"
  end
  if type(seen) ~= "table" then
    seen = {}
  end
  if type(depth) ~= "number" then
    depth = 0
  end
  if depth > __rt._JSON_MAX_DEPTH then
    return false, "depth"
  end
  if seen[v] then
    return false, "cycle"
  end
  seen[v] = true
  local ok = __rt._json_is_object(v) or __rt._json_is_array(v)
  local reason = "shape"
  if ok then
    for _, val in pairs(v) do
      if val == __rt.__NULL then
        -- JSON null leaf: fine
      elseif type(val) == "boolean" or type(val) == "string" then
        -- primitive leaf: fine
      elseif type(val) == "number" then
        if not __rt._json_is_number(val) then
          ok = false
          reason = "shape"
          break
        end
      elseif type(val) == "table" then
        local nested_ok, nested_reason = __rt._json_table_shape(val, seen, depth + 1)
        if not nested_ok then
          ok = false
          reason = nested_reason or "shape"
          break
        end
      else
        -- functions and any other non-JSON leaf
        ok = false
        reason = "shape"
        break
      end
    end
  end
  seen[v] = nil
  if ok then
    return true
  end
  return false, reason
end

--- D2 step 3 defaults identity gate, as a non-throwing predicate:
-- false when defaults is not a table, when defaults is __NULL or
-- __MISSING by identity, or when defaults.__kind is
-- "function"/"class"/"async" — exactly the condition under which
-- _deep_copy returns the table by identity (see _deep_copy above).
-- A __kind field holding any other value (a legal field default) is
-- accepted.
function __rt._json_defaults_identity_ok(defaults)
  if type(defaults) ~= "table" then
    return false
  end
  if defaults == __rt.__NULL or defaults == __rt.__MISSING then
    return false
  end
  local k = defaults.__kind
  if k == "function" or k == "class" or k == "async" then
    return false
  end
  return true
end

--- Path-local acyclicity check for the defaults table (D4): false for
-- cyclic input, true for acyclic input. Traversal mirrors _deep_copy:
-- sentinels and __kind-tagged tables (function/class/async) are treated
-- as leaves — exactly the tables _deep_copy returns by identity — so
-- this check is precisely the condition under which _deep_copy
-- terminates. Never mutates input, never raises.
function __rt._json_defaults_acyclic(t, seen)
  if type(t) ~= "table" then
    return true
  end
  if type(seen) ~= "table" then
    seen = {}
  end
  if seen[t] then
    return false
  end
  seen[t] = true
  local ok = true
  if t ~= __rt.__NULL and t ~= __rt.__MISSING then
    local k = t.__kind
    if k ~= "function" and k ~= "class" and k ~= "async" then
      for _, v in pairs(t) do
        if type(v) == "table" and not __rt._json_defaults_acyclic(v, seen) then
          ok = false
          break
        end
      end
    end
  end
  seen[t] = nil
  return ok
end

--- True iff every entry of the fields array satisfies the
-- direction-specific entry rules (D2 step 5 for decode, D3 step 2 for
-- encode). Non-table fields -> false. The walkers re-run this at every
-- descriptor descent, so validation depth always equals walker depth.
function __rt._json_validate_fields(fields, decode)
  if type(fields) ~= "table" then
    return false
  end
  for _, entry in ipairs(fields) do
    if not __rt._json_validate_entry(entry, decode) then
      return false
    end
  end
  return true
end

--- Validate one field/element descriptor entry against the direction's
-- rules (Contract 3). Never raises; returns false on any violation.
-- A field entry (carrying name) requires a string name, a known jtype,
-- and boolean optional and nullable keys (missing key or non-boolean
-- value is malformed). An element descriptor may omit optional/nullable
-- (absent means false/false); a present element flag must be boolean.
-- jtype == "class" requires a string className and a table fields, plus
-- a table defaults on the decode rule set only (defaults is decode-only;
-- encode never requires or reads it). jtype == "array" requires a table
-- element. Unknown jtype is false. The array branch checks only this
-- entry's element reference — the walker re-runs this validator on the
-- element descriptor itself when it descends, so a truncated
-- nested-array element is caught at the depth where it is consumed.
function __rt._json_validate_entry(entry, decode)
  if type(entry) ~= "table" then
    return false
  end
  if entry.name ~= nil then
    -- Field entry: name and both flags are mandatory booleans
    if type(entry.name) ~= "string" then
      return false
    end
    if type(entry.optional) ~= "boolean" then
      return false
    end
    if type(entry.nullable) ~= "boolean" then
      return false
    end
  else
    -- Element descriptor: absent flags mean false/false; a present
    -- flag must be boolean (a truthy non-boolean would silently flip
    -- element semantics).
    if entry.optional ~= nil and type(entry.optional) ~= "boolean" then
      return false
    end
    if entry.nullable ~= nil and type(entry.nullable) ~= "boolean" then
      return false
    end
  end
  local jtype = entry.jtype
  if jtype == "null" or jtype == "boolean" or jtype == "int"
      or jtype == "number" or jtype == "string" or jtype == "table" then
    return true
  elseif jtype == "class" then
    if type(entry.className) ~= "string" then
      return false
    end
    if type(entry.fields) ~= "table" then
      return false
    end
    if decode and type(entry.defaults) ~= "table" then
      return false
    end
    return true
  elseif jtype == "array" then
    if type(entry.element) ~= "table" then
      return false
    end
    return true
  end
  return false
end

--- Deserialize a parsed JSON table into a tagged class instance.
-- Operates on an already-parsed Lua table (from json.parse via pcall).
-- Does NOT call json.parse itself — the JSON I/O boundary is in generated code.
-- Never throws: every malformed input returns nil (Contract 1).
--
-- @param descriptor string  classifier for the __classname tag
-- @param parsed     table   already-decoded JSON object (from json.parse)
-- @param defaults   table   default values per field (contains __NULL/__MISSING sentinels)
-- @param fields     array   array of field descriptor tables
-- @return tagged instance table on success, or nil on validation failure
function __rt.json_from_json(descriptor, parsed, defaults, fields)
  return __rt._json_from_instance(descriptor, parsed, defaults, fields, {}, 0)
end

--- Decode one class instance (D2 gate order, steps 1-10). Never throws:
-- every failure path returns nil. seen is the shared path-local set (D4);
-- depth counts walker nesting levels (D5). Nested class decode recurses
-- here with depth + 1, re-running every gate on the nested defaults and
-- fields.
function __rt._json_from_instance(descriptor, parsed, defaults, fields, seen, depth)
  if type(seen) ~= "table" then
    seen = {}
  end
  if type(depth) ~= "number" then
    depth = 0
  end
  -- 1. Depth guard (D5): past 512 nesting levels → nil.
  if depth > __rt._JSON_MAX_DEPTH then
    return nil
  end
  -- 2. Top-level input gate (D2 step 2): only a decoded JSON object may
  -- reach the key gate; the null sentinel is itself a Lua table and is
  -- rejected by identity (the generated wrapper maps nil to __NULL, so
  -- C$fromJson("null") returns the DEAL null).
  if type(parsed) ~= "table" then
    return nil
  end
  if parsed == __rt.__NULL then
    return nil
  end
  -- 3. Defaults identity gate (D2 step 3): a sentinel or identity-
  -- preserved defaults (__kind in {"function","class","async"} — exactly
  -- the condition under which _deep_copy returns the table by identity)
  -- would be overlaid and tagged in place, corrupting the global
  -- sentinels; reject it before _deep_copy can return it as the instance
  -- scaffold. Any other __kind value is a legal field default.
  if not __rt._json_defaults_identity_ok(defaults) then
    return nil
  end
  -- 4. Fields gate (D2 step 4): a missing/non-table fields argument
  -- (including an absent host <C>_fields reference) yields nil, never a
  -- raw ipairs crash.
  if type(fields) ~= "table" then
    return nil
  end
  -- 5. Descriptor-entry validation (D2 step 5, decode rule set) before
  -- any key iteration. Re-run at every descent: array branches re-check
  -- their element descriptor and nested class decode re-runs this gate
  -- on the nested fields array, so a malformed descriptor at any depth
  -- returns nil instead of throwing a raw indexing error.
  if not __rt._json_validate_fields(fields, true) then
    return nil
  end
  -- 6. Key gate (D2 step 6): every parsed key must be a declared field
  -- name; extra/unknown keys and the integer keys of non-empty
  -- array-shaped input are rejections.
  local valid_keys = {}
  for _, f in ipairs(fields) do
    valid_keys[f.name] = true
  end
  for k in pairs(parsed) do
    if not valid_keys[k] then
      return nil
    end
  end
  -- 7. Empty parsed input accepted: decodes as the defaulted instance
  -- (D2a — the []/{} parse collapse).
  -- 8. Defaults acyclicity (D4): cyclic defaults → nil before _deep_copy.
  if not __rt._json_defaults_acyclic(defaults, {}) then
    return nil
  end
  -- 9. Seen push (D4): a cycle in the parsed data re-enters this table
  -- on the current path → nil. The set is popped on every return path.
  if seen[parsed] then
    return nil
  end
  seen[parsed] = true
  -- 10. Decode (D2 step 10): deep-copy → overlay → __MISSING-removal →
  -- tagging, order unchanged (D8). The instance is a fresh table and
  -- never enters seen.
  local instance = __rt._deep_copy(defaults)
  for _, f in ipairs(fields) do
    local raw = parsed[f.name]
    if raw ~= nil then
      local v = __rt._json_from_value(f, raw, seen, depth)
      if v == nil then
        seen[parsed] = nil
        return nil
      end
      instance[f.name] = v
    end
    -- Key absent: keep the deep-copied default.
  end
  for k, v in pairs(instance) do
    if v == __rt.__MISSING then
      instance[k] = nil
    end
  end
  instance.__classname = descriptor
  instance.__kind = "class"
  seen[parsed] = nil
  return instance
end

--- Decode one field/element value per its descriptor (Contract 4 fromJson
-- columns). Never raises: every malformed value returns nil. fdesc is an
-- entry validated by the caller (field entries by the instance's
-- _json_validate_fields gate; element descriptors by the array branch's
-- re-validation), so jtype is known and field-entry flags are booleans
-- (an absent element flag reads nil — false semantics). seen is the
-- shared path-local set; depth counts walker nesting levels.
function __rt._json_from_value(fdesc, raw, seen, depth)
  if type(seen) ~= "table" then
    seen = {}
  end
  if type(depth) ~= "number" then
    depth = 0
  end
  if raw == __rt.__NULL then
    -- Explicit JSON null: accepted only on a nullable field/element or a
    -- null-typed field; everywhere else it is a shape violation.
    if fdesc.nullable or fdesc.jtype == "null" then
      return __rt.__NULL
    end
    return nil
  end
  local jtype = fdesc.jtype
  if jtype == "null" then
    -- Only __NULL decodes to null (handled above).
    return nil
  elseif jtype == "boolean" then
    if type(raw) ~= "boolean" then
      return nil
    end
    return raw
  elseif jtype == "string" then
    if type(raw) ~= "string" then
      return nil
    end
    return raw
  elseif jtype == "int" then
    if not __rt._json_is_int(raw) then
      return nil
    end
    return raw
  elseif jtype == "number" then
    if not __rt._json_is_number(raw) then
      return nil
    end
    return raw
  elseif jtype == "class" then
    if type(raw) ~= "table" then
      return nil
    end
    -- The nested call re-runs every gate (descriptor-entry validation on
    -- fdesc.fields, the defaults identity gate on fdesc.defaults, the
    -- key gate on the nested object) and pushes raw onto seen.
    return __rt._json_from_instance(fdesc.className, raw, fdesc.defaults,
        fdesc.fields, seen, depth + 1)
  elseif jtype == "array" then
    if type(raw) ~= "table" then
      return nil
    end
    if depth > __rt._JSON_MAX_DEPTH then
      return nil
    end
    -- Dense JSON array shape (holes or mixed keys are rejections; the
    -- empty array is accepted).
    if not __rt._json_is_array(raw) then
      return nil
    end
    -- Recursive rule: re-validate the element descriptor before
    -- descending — a truncated nested-array element (an array-typed
    -- descriptor without its own element) returns nil here, never a raw
    -- attempt-to-index throw.
    if not __rt._json_validate_entry(fdesc.element, true) then
      return nil
    end
    -- Push the array container onto the path-local set (D4); a cyclic
    -- array re-entering itself through a nested-array element is nil.
    if seen[raw] then
      return nil
    end
    seen[raw] = true
    local arr = {}
    for i = 1, #raw do
      local v = __rt._json_from_value(fdesc.element, raw[i], seen, depth + 1)
      if v == nil then
        seen[raw] = nil
        return nil
      end
      arr[i] = v
    end
    seen[raw] = nil
    return arr
  elseif jtype == "table" then
    if type(raw) ~= "table" then
      return nil
    end
    -- D7 asymmetry: fromJson accepts only a JSON object for table
    -- fields (an array-shaped table value is a rejection).
    if not __rt._json_is_object(raw) then
      return nil
    end
    -- The whole datum must be JSON-shaped (finite leaves, acyclic);
    -- _json_table_shape pushes raw onto seen and pops on every return.
    if not __rt._json_table_shape(raw, seen, depth) then
      return nil
    end
    -- Store the validated parsed sub-table by reference (v1.1 aliasing
    -- behavior; the generated wrapper discards parsed after the call).
    return raw
  end
  -- Unknown jtype (defensive — the entry validators reject it first).
  return nil
end


--- Encode one class instance (Contract 2, D3): iterate the field
-- descriptors and emit each present field through _json_to_value.
-- Raises E8001/E8004 DEAL errors on invalid input. The caller marks the
-- instance on the path-local seen set before recursing (D4), so this
-- helper reads fields and never mutates value.
function __rt._json_to_instance(descriptor, value, fields, seen, depth)
  if depth > __rt._JSON_MAX_DEPTH then
    error(__rt._err("E8001", "maximum JSON nesting depth (512) exceeded", nil, nil, nil, nil, nil))
  end
  -- Recursive rule (D3 step 2): every nested fields array re-runs the
  -- encode descriptor-entry validation before iteration, so validation
  -- depth always equals walker depth and no encode path ever iterates
  -- an unvalidated descriptor.
  if not __rt._json_validate_fields(fields, false) then
    error(__rt._err("E8001", "malformed field descriptors", nil, nil, nil, nil, nil))
  end
  local result = {}
  for _, f in ipairs(fields) do
    local v = value[f.name]
    if v == nil then
      if not f.optional then
        error(__rt._err("E8001", "missing required field '" .. f.name .. "'", nil, nil, nil, nil, nil))
      end
      -- Missing optional field: omit the key from the output
    else
      result[f.name] = __rt._json_to_value(f, v, seen, depth)
    end
  end
  return result
end

--- Encode one field/element value per its descriptor (Contract 4 toJson
-- columns, D3 step 4). Every raise is a DEAL error (E8001/E8004 built
-- by __rt._err with no source-location arguments, like
-- std/json.stringify); no raw Lua error can escape for caller inputs —
-- every predicate and validator applies its type guard before any
-- iteration or dereference. The descriptor is always caller-validated:
-- fields arrays are validated before _json_to_instance iterates them
-- and the array branch re-validates its element descriptor before
-- element-wise recursion (D3 step 2 recursive rule).
function __rt._json_to_value(fdesc, v, seen, depth)
  if depth > __rt._JSON_MAX_DEPTH then
    error(__rt._err("E8001", "maximum JSON nesting depth (512) exceeded", nil, nil, nil, nil, nil))
  end
  local jtype = fdesc.jtype
  if v == __rt.__NULL then
    -- Explicit null: accepted iff the field/element is nullable or the
    -- jtype itself is null (Contract 4).
    if fdesc.nullable or jtype == "null" then
      return __rt.__NULL
    end
    local what = fdesc.name ~= nil and ("field '" .. fdesc.name .. "'") or "array element"
    error(__rt._err("E8001", "explicit null on non-nullable " .. what, nil, nil, nil, nil, nil))
  end
  if jtype == "null" then
    error(__rt._err("E8001", "expected null", nil, nil, nil, "null", type(v)))
  elseif jtype == "boolean" then
    if type(v) ~= "boolean" then
      error(__rt._err("E8001", "expected boolean", nil, nil, nil, "boolean", type(v)))
    end
    return v
  elseif jtype == "int" then
    -- check_int: E8001 for non-number/NaN/Infinity/non-integer,
    -- E8004 "int out of safe range" beyond the safe range — the int
    -- type contract (see check_int above).
    return __rt.check_int(v)
  elseif jtype == "number" then
    if type(v) ~= "number" then
      error(__rt._err("E8001", "expected number", nil, nil, nil, "number", type(v)))
    end
    if v ~= v then  -- NaN check: NaN is the only value not equal to itself
      error(__rt._err("E8001", "cannot encode NaN as JSON", nil, nil, nil, nil, nil))
    end
    if v == math.huge or v == -math.huge then
      error(__rt._err("E8001", "cannot encode Infinity as JSON", nil, nil, nil, nil, nil))
    end
    return v
  elseif jtype == "string" then
    if type(v) ~= "string" then
      error(__rt._err("E8001", "expected string", nil, nil, nil, "string", type(v)))
    end
    return v
  elseif jtype == "table" then
    if type(v) ~= "table" then
      error(__rt._err("E8001", "expected table", nil, nil, nil, "table", type(v)))
    end
    if depth + 1 > __rt._JSON_MAX_DEPTH then
      error(__rt._err("E8001", "maximum JSON nesting depth (512) exceeded", nil, nil, nil, nil, nil))
    end
    -- D7: toJson accepts string-keyed objects and dense arrays with
    -- finite primitive leaves, finite and acyclic. _json_table_shape
    -- shares the walker's path-local seen set, so a datum re-entering
    -- any table on the path (instance, array container, nested table
    -- value) is a cycle.
    local ok, reason = __rt._json_table_shape(v, seen, depth + 1)
    if not ok then
      if reason == "cycle" then
        error(__rt._err("E8001", "cyclic value cannot be encoded as JSON", nil, nil, nil, nil, nil))
      elseif reason == "depth" then
        error(__rt._err("E8001", "maximum JSON nesting depth (512) exceeded", nil, nil, nil, nil, nil))
      else
        error(__rt._err("E8001", "value is not JSON-shaped", nil, nil, nil, nil, nil))
      end
    end
    -- Emit the validated original table by reference (never mutated).
    return v
  elseif jtype == "class" then
    if type(v) ~= "table" or v.__kind ~= "class" then
      error(__rt._err("E8001", "expected class instance", nil, nil, nil, "class", type(v)))
    end
    if v.__classname ~= fdesc.className then
      error(__rt._err("E8001", "expected instance of " .. fdesc.className .. ", got " .. tostring(v.__classname or "unknown"), nil, nil, nil, fdesc.className, v.__classname))
    end
    if seen[v] then
      error(__rt._err("E8001", "cyclic value cannot be encoded as JSON", nil, nil, nil, nil, nil))
    end
    seen[v] = true
    local nested = __rt._json_to_instance(fdesc.className, v, fdesc.fields, seen, depth + 1)
    seen[v] = nil
    return nested
  elseif jtype == "array" then
    if type(v) ~= "table" then
      error(__rt._err("E8001", "expected array", nil, nil, nil, "array", type(v)))
    end
    if not __rt._json_is_array(v) then
      error(__rt._err("E8001", "expected dense array", nil, nil, nil, "array", nil))
    end
    -- Recursive rule (D3 step 2): the element descriptor is re-validated
    -- before element-wise encoding — a truncated array-typed element
    -- lacking its own element raises E8001 here, never a raw error.
    if not __rt._json_validate_entry(fdesc.element, false) then
      error(__rt._err("E8001", "malformed field descriptors", nil, nil, nil, nil, nil))
    end
    if seen[v] then
      error(__rt._err("E8001", "cyclic value cannot be encoded as JSON", nil, nil, nil, nil, nil))
    end
    seen[v] = true
    local arr = {}
    for i = 1, #v do
      arr[i] = __rt._json_to_value(fdesc.element, v[i], seen, depth + 1)
    end
    seen[v] = nil
    return arr
  end
  -- Unknown jtype: descriptor-entry validation upstream rejects unknown
  -- jtypes before any walker descent; this arm is unreachable through
  -- validated descriptors and stays as a defensive DEAL error.
  error(__rt._err("E8001", "unknown jtype in field descriptor", nil, nil, nil, nil, nil))
end

--- Serialize a class instance to a JSON-compatible Lua table.
-- Operates on an already-tagged class instance (from class_ or json_from_json).
-- Does NOT call json.stringify — the JSON I/O boundary is in generated code.
-- Raises E8001/E8004 DEAL errors (never raw Lua errors) on invalid input:
-- identity mismatch, malformed descriptors at any nesting depth, wrong
-- primitive types, explicit null on non-nullable fields/elements, missing
-- required fields, shape violations, non-JSON table values, cycles, and
-- depth overruns (Contract 2).
--
-- @param descriptor string  classifier for error messages and __classname tag
-- @param value      table   tagged class instance table
-- @param fields     array   array of field descriptor tables
-- @return table suitable for json.stringify
function __rt.json_to_json(descriptor, value, fields)
  -- 1. Top-level identity check (D3 step 1, defense in depth — the
  -- generated wrapper's check_type fires first): exact-compare like
  -- check_type's class branch.
  if type(value) ~= "table" or value.__kind ~= "class" then
    error(__rt._err("E8001", "expected class instance", nil, nil, nil, "class", type(value)))
  end
  if value.__classname ~= descriptor then
    error(__rt._err("E8001", "expected instance of " .. tostring(descriptor) .. ", got " .. tostring(value.__classname or "unknown"), nil, nil, nil, descriptor, value.__classname))
  end
  -- 2. Encode descriptor-entry validation (D3 step 2): field entries
  -- require boolean optional/nullable, present element flags must be
  -- boolean (absent means false/false), class entries and class elements
  -- require className/fields only — defaults is decode-only.
  if not __rt._json_validate_fields(fields, false) then
    error(__rt._err("E8001", "malformed field descriptors", nil, nil, nil, nil, nil))
  end
  -- 3. Path-local seen push, the recursive walker, then the pop (D3
  -- step 3, D4). A raise inside the walker unwinds the whole call and
  -- the per-call seen set is discarded with it (it is local and never
  -- escapes), so path-local semantics hold on every path; the pop line
  -- below runs for the success path.
  local seen = {}
  seen[value] = true
  local result = __rt._json_to_instance(descriptor, value, fields, seen, 0)
  seen[value] = nil
  return result
end

return __rt
