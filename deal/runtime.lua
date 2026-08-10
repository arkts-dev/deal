-- DEAL Runtime Library v0.6
-- Provides type checks, integer arithmetic, class construction, and function wrapping.
-- Loaded by every generated Lua module via require("deal.runtime").

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

function __rt.check_null(v)
  if v ~= __rt.__NULL then
    error(__rt._err("E8001", "expected null", nil, nil, nil, "null", type(v)))
  end
  return v
end

function __rt.check_boolean(v)
  if type(v) ~= "boolean" then
    error(__rt._err("E8001", "expected boolean", nil, nil, nil, "boolean", type(v)))
  end
  return v
end

function __rt.check_int(v)
  if type(v) ~= "number" then
    error(__rt._err("E8001", "expected int", nil, nil, nil, "int", type(v)))
  end
  if v ~= v then  -- NaN check: NaN is the only value not equal to itself
    error(__rt._err("E8001", "expected int, got NaN", nil, nil, nil, "int", "NaN"))
  end
  v = v + 0  -- normalize -0 to 0 (per spec.md lines 60-65, 2024)
  if v == math.huge or v == -math.huge then
    error(__rt._err("E8001", "expected int, got infinity", nil, nil, nil, "int", "infinity"))
  end
  if v % 1 ~= 0 then
    error(__rt._err("E8001", "expected int, got non-integer number", nil, nil, nil, "int", "number"))
  end
  if v < -9007199254740991 or v > 9007199254740991 then
    error(__rt._err("E8004", "int out of safe range", nil, nil, nil, nil, nil))
  end
  return v
end

function __rt.check_number(v)
  if type(v) ~= "number" then
    error(__rt._err("E8001", "expected number", nil, nil, nil, "number", type(v)))
  end
  return v
end

function __rt.check_string(v)
  if type(v) ~= "string" then
    error(__rt._err("E8001", "expected string", nil, nil, nil, "string", type(v)))
  end
  return v
end

function __rt.check_table(v)
  if type(v) ~= "table" then
    error(__rt._err("E8001", "expected table", nil, nil, nil, "table", type(v)))
  end
  return v
end

function __rt.check_coroutine(v)
  if type(v) ~= "thread" then
    error(__rt._err("E8001", "expected coroutine", nil, nil, nil, "coroutine", type(v)))
  end
  return v
end

-- ===== Composite type checks =====

--- Check a nullable value.
-- nil (missing optional field) and __NULL (explicit null) both return __NULL.
-- Otherwise delegates to check_type for the inner descriptor.
function __rt.check_nullable(inner_descriptor, v)
  if v == nil or v == __rt.__NULL then
    return __rt.__NULL
  end
  return __rt.check_type(inner_descriptor, v)
end

--- Extract the element descriptor from an array descriptor.
-- Supports "T[]" format (e.g., "int[]" → "int", "int[][]" → "int[]")
-- and "[T]" format (e.g., "[int]" → "int").
function __rt.array_element_descriptor(array_descriptor)
  if array_descriptor == nil then
    error(__rt._err("E8001", "internal: nil array descriptor", nil, nil, nil, nil, nil))
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
  error(__rt._err("E8001", "invalid array descriptor: " .. tostring(array_descriptor), nil, nil, nil, nil, nil))
end

--- Check that value is an array whose elements match the array descriptor.
function __rt.check_array(array_descriptor, v)
  if type(v) ~= "table" then
    error(__rt._err("E8001", "expected array", nil, nil, nil, "array", type(v)))
  end
  local element_descriptor = __rt.array_element_descriptor(array_descriptor)
  for i = 1, #v do
    local ok, err = pcall(__rt.check_type, element_descriptor, v[i])
    if not ok then
      error(__rt._err("E8003", "array element " .. i .. " type mismatch: " .. tostring(err), nil, nil, nil, element_descriptor, type(v[i])))
    end
  end
  return v
end

-- ===== Descriptor parser =====

--- Parse a type descriptor string and return a structured representation.
-- Returns a table: { kind = "primitive"|"array"|"nullable"|"function"|"class", ... }
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

  -- Function: "(params)->ret" format
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
    ["coroutine"] = true,
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
function __rt.check_type(descriptor, v)
  if descriptor == nil then
    error(__rt._err("E8001", "internal: nil type descriptor", nil, nil, nil, nil, nil))
  end

  local parsed = parse_descriptor(descriptor)
  if parsed == nil then
    error(__rt._err("E8001", "internal: cannot parse type descriptor: " .. tostring(descriptor), nil, nil, nil, nil, nil))
  end

  if parsed.kind == "primitive" then
    if parsed.name == "null" then
      return __rt.check_null(v)
    elseif parsed.name == "boolean" then
      return __rt.check_boolean(v)
    elseif parsed.name == "int" then
      return __rt.check_int(v)
    elseif parsed.name == "number" then
      return __rt.check_number(v)
    elseif parsed.name == "string" then
      return __rt.check_string(v)
    elseif parsed.name == "table" then
      return __rt.check_table(v)
    elseif parsed.name == "coroutine" then
      return __rt.check_coroutine(v)
    else
      error(__rt._err("E8001", "unknown primitive type: " .. parsed.name, nil, nil, nil, nil, nil))
    end
  elseif parsed.kind == "nullable" then
    return __rt.check_nullable(parsed.inner, v)
  elseif parsed.kind == "array" then
    return __rt.check_array(descriptor, v)
  elseif parsed.kind == "function" then
    -- Check that v is a function wrapper with matching signature
    if type(v) ~= "table" or v.__kind ~= "function" then
      error(__rt._err("E8001", "expected function", nil, nil, nil, "function", type(v)))
    end
    -- Signature comparison: the stored sig must match the expected descriptor
    if v.sig ~= descriptor then
      error(__rt._err("E8010", "function signature mismatch: expected " .. descriptor .. ", got " .. (v.sig or "nil"), nil, nil, nil, descriptor, v.sig))
    end
    return v
  elseif parsed.kind == "class" then
    -- Check that v is a class instance with matching class name
    if type(v) ~= "table" or v.__kind ~= "class" then
      error(__rt._err("E8001", "expected class instance", nil, nil, nil, "class", type(v)))
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
      error(__rt._err("E8001", "expected instance of " .. expected_class .. ", got " .. (actual_class or "unknown"), nil, nil, nil, expected_class, actual_class))
    end
    return v
  else
    error(__rt._err("E8001", "internal: unhandled descriptor kind: " .. parsed.kind, nil, nil, nil, nil, nil))
  end
end

-- ===== Integer arithmetic =====

function __rt.int_add(a, b)
  return __rt.check_int(a + b)
end

function __rt.int_sub(a, b)
  return __rt.check_int(a - b)
end

function __rt.int_mul(a, b)
  return __rt.check_int(a * b)
end

function __rt.int_div(a, b)
  if b == 0 then
    error(__rt._err("E8005", "integer division by zero", nil, nil, nil, nil, nil))
  end
  return __rt.check_int(math.modf(a / b))
end

function __rt.int_mod(a, b)
  if b == 0 then
    error(__rt._err("E8005", "integer division by zero", nil, nil, nil, nil, nil))
  end
  return __rt.check_int(a - math.modf(a / b) * b)
end

function __rt.int_pow(a, b)
  if b < 0 then
    error(__rt._err("E8006", "integer exponent must be non-negative", nil, nil, nil, nil, nil))
  end
  return __rt.check_int(a ^ b)
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
    if ret_descriptor ~= "null" and ret_descriptor ~= "void" then
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

-- ===== Class infrastructure =====

--- Construct a class instance.
-- @param classname string - the class name for tagging and error messages
-- @param defaults table - all declared fields: required fields with default values,
--        optional fields with __MISSING sentinel
-- @param provided table - user-provided field values
-- @return table - the constructed class instance
function __rt.class_(classname, defaults, provided)
  if type(defaults) ~= "table" then
    error(__rt._err("E8001", "class defaults must be a table", nil, nil, nil, "table", type(defaults)))
  end

  -- 1. Deep-copy defaults (__MISSING and __NULL sentinels preserved by identity)
  local instance = __rt._deep_copy(defaults)

  -- 2. Overlay provided fields, rejecting extras
  if provided ~= nil then
    if type(provided) ~= "table" then
      error(__rt._err("E8001", "class field values must be a table", nil, nil, nil, "table", type(provided)))
    end
    for k, v in pairs(provided) do
      if instance[k] == nil then
        -- Key is not in the defaults table → extra field
        error(__rt._err("E8007", "extra field '" .. tostring(k) .. "' in class '" .. classname .. "'", nil, nil, nil, nil, nil))
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
function __rt.int_convert(v)
  if v == nil or v == __rt.__NULL then
    error(__rt._err("E8001", "cannot convert null to int", nil, nil, nil, "int", "null"))
  end
  -- Reuse check_int which validates range, NaN, Infinity, and integer-ness
  return __rt.check_int(v)
end

--- Convert a value to number. Handles both overloads:
---   (int) => number           — converts int to number
---   (number | null) => number — unwraps nullable number, rejecting null
--- Delegates to check_number for type validation.
--- KNOWN LIMIT (v1.0): Same indirect-use limitation as int_convert.
function __rt.number_convert(v)
  if v == nil or v == __rt.__NULL then
    error(__rt._err("E8001", "cannot convert null to number", nil, nil, nil, "number", "null"))
  end
  return __rt.check_number(v)
end

-- ===== Internal helpers =====

--- Deep-copy a table recursively.
-- Preserves sentinel identity: __NULL and __MISSING are returned as-is.
-- Functions are not copied (returned as-is).
-- Handles cycles gracefully by... actually, doesn't handle cycles (class defaults shouldn't be cyclic).
function __rt._deep_copy(t)
  if type(t) ~= "table" then
    return t
  end
  -- Preserve sentinel identity
  if t == __rt.__NULL or t == __rt.__MISSING then
    return t
  end
  -- Check if this is a function wrapper (has __kind = "function")
  if t.__kind == "function" then
    -- Function wrappers are returned as-is (identity preserved)
    return t
  end
  -- Check if this is a class instance (has __kind = "class")
  if t.__kind == "class" then
    -- Class instances are returned as-is (identity preserved)
    return t
  end
  local copy = {}
  for k, v in pairs(t) do
    copy[k] = __rt._deep_copy(v)
  end
  return copy
end

return __rt
