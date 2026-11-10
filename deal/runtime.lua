-- DEAL Runtime Library v1.6 (LuaJIT backend)
-- Provides type checks, canonical descriptor parsing and matching, integer
-- arithmetic, class construction, function wrapping,
-- async/await infrastructure and typed host-module loading.
-- Loaded by every generated Lua module via require("deal.runtime").
--
-- All check_* functions accept optional trailing (file, line, column) arguments
-- for source-location tracking in runtime errors. When omitted, these default to nil.

local __rt = {}

-- ECMAScript Number::toString: exact decimal expansion followed by the
-- shortest round-tripping, ties-to-even candidate. Shared by emitted numeric
-- conversion and deployed/emitted JSON encoders.
function __rt.number_text(v)
  if v ~= v then return "NaN" end
  if v == math.huge then return "Infinity" end
  if v == -math.huge then return "-Infinity" end
  if v == 0 then return "0" end
  local sign = v < 0 and "-" or ""
  v = math.abs(v)
  local m, e = math.frexp(v)
  local digits = string.format("%.0f", math.ldexp(m, 53))
  e = e - 53
  local scale = math.max(0, -e)
  local factor = e < 0 and 5 or 2
  for step = 1, math.abs(e) do
    local carry, out = 0, {}
    for i = #digits, 1, -1 do
      local n = (string.byte(digits, i) - 48) * factor + carry
      out[i] = string.char(48 + n % 10)
      carry = math.floor(n / 10)
    end
    digits = (carry > 0 and string.char(48 + carry) or "") .. table.concat(out)
  end
  local exponent = #digits - scale - 1
  local function increment(s)
    local i = #s
    while i > 0 and s:sub(i,i) == "9" do i = i - 1 end
    if i == 0 then return "1" .. string.rep("0", #s) end
    return s:sub(1,i-1) .. string.char(string.byte(s,i)+1) .. string.rep("0", #s-i)
  end
  local chosen, power
  for precision = 1, 17 do
    local lower = digits:sub(1,precision)
    if #lower < precision then lower = lower .. string.rep("0", precision-#lower) end
    local nextDigit = digits:sub(precision+1,precision+1)
    local tail = digits:sub(precision+2):find("[1-9]") ~= nil
    local up = nextDigit ~= "" and (nextDigit > "5" or
      (nextDigit == "5" and (tail or tonumber(lower:sub(-1)) % 2 == 1)))
    local upper = increment(lower)
    local rounded, other = lower, upper
    if up then rounded, other = upper, lower end
    power = exponent - precision + 1
    if tonumber(rounded .. "e" .. power) == v then chosen = rounded
    elseif tonumber(other .. "e" .. power) == v then chosen = other end
    if chosen then break end
  end
  if not chosen then error("number: missing roundtrip") end
  while #chosen > 1 and chosen:sub(-1) == "0" do
    chosen = chosen:sub(1,-2); power = power + 1
  end
  exponent = #chosen + power - 1
  if exponent >= -6 and exponent < 21 then
    local point = #chosen + power
    if point <= 0 then return sign .. "0." .. string.rep("0", -point) .. chosen end
    if point >= #chosen then return sign .. chosen .. string.rep("0", point-#chosen) end
    return sign .. chosen:sub(1,point) .. "." .. chosen:sub(point+1)
  end
  local fraction = #chosen > 1 and "." .. chosen:sub(2) or ""
  return sign .. chosen:sub(1,1) .. fraction .. "e" .. (exponent >= 0 and "+" or "") .. exponent
end

-- ===== Sentinels =====
-- __rt.__NULL is the one language-null representation of the deployed
-- runtime and of every emitted chunk that binds it (ISSUE-0814): the
-- generated chunk aliases its own __NULL to this object, so a shared array
-- carrier's null elements reach the loaded matcher and the raw host with
-- no projection. The __tostring spelling carries the emitted chunk's own
-- representation contract (a null atomized at a declared non-null kind
-- renders "null", never a table address), which the aliasing must keep.
__rt.__NULL = setmetatable({}, {__tostring = function() return "null" end})
__rt.__MISSING = {}
__rt.__UNDEFINED = {}

-- ===== Private carrier keys (ISSUE-0830) =====
-- Carrier identity (the class/Error/function/array/async/numeric-variant
-- mark), the array length, the table presence/insertion order and the
-- per-slot numeric-variant marks live under table-object keys, never under
-- strings: a DEAL table may carry any string key (a class instance may
-- declare a field named after any of them), so a string-keyed mark would be
-- both shadowable and forgeable. The key set is process-wide — every chunk
-- of one process and this runtime reuse the same objects — so a carrier
-- built by an emitted chunk is classifiable here and vice versa (a chunk
-- that binds no runtime creates the set first and later chunks and the
-- runtime adopt it). __rt.__keys publishes the set to the stdlib modules
-- and to the direct runtime drives; DEAL source cannot name a table key.
local __CARRIER_KEYS = rawget(_G, "__dealCarrierKeys")
if __CARRIER_KEYS == nil then
  __CARRIER_KEYS = {
    kind = {}, classname = {}, c = {}, id = {}, p = {}, f = {}, d = {},
    elementDescriptor = {}, a = {}, n = {}, nK = {}, da = {}, t = {}, keys = {}, order = {},
    fn = {}, csig = {}, sig = {}, fid = {}, it = {}, sid = {}, val = {},
    mode = {}, jn = {}, jo = {}, ja = {}, jkeys = {}, jnum = {}, jseen = {},
    ptr = {},
  }
  rawset(_G, "__dealCarrierKeys", __CARRIER_KEYS)
end
local __K = __CARRIER_KEYS
__rt.__keys = __K
__K.undefinedSentinel = __K.undefinedSentinel or __rt.__UNDEFINED
__rt.__UNDEFINED = __K.undefinedSentinel
__K.classOrder = __K.classOrder or {}
__K.classPlan = __K.classPlan or {}
__K.classOrders = __K.classOrders or {}

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

--- Create one canonical-matcher failure with its closed arm selection
-- (canonical failure-projection authority P1/P2/P4). The table keeps the
-- _err delivery shape for the runtime's own consumers (the stdlib checks
-- and the retained runtime drives), and carries the arm id and the arm's
-- named parameters under `arm`; a host-wrapper consumer renders the
-- selected arm through the artifact-owned closed-arm renderer instead of
-- publishing the locally composed text — the runtime holds no second
-- authority for a mapped host failure. A failure with no closed arm (a
-- producer defect such as an unparsable descriptor) carries no selection
-- and never becomes a host inner reason.
function __rt._armErr(arm_id, values, code, message, file, line, column, expected, actual)
  local e = __rt._err(code, message, file, line, column, expected, actual)
  e.arm = { id = arm_id, values = values }
  return e
end

--- The canonical hex-float spelling of one IEEE-754 double
-- (Double.toHexString parity): NaN, both infinities and both zeros carry
-- their Java spellings (never LuaJIT's "inf"/"-inf"), a subnormal its
-- fixed-exponent denormal spelling, every other finite nonzero value its
-- shortest exact "0x1.<digits>p<exp>" form. The one renderer of the
-- numeric runtime observations, the same
-- spelling the emitted prelude's __numHex, the JVM runtime's
-- Double.toHexString and the shared CanonicalJson#numberHex publish.
function __rt.number_hex(v)
  if v ~= v then return "NaN" end
  if v == math.huge then return "Infinity" end
  if v == -math.huge then return "-Infinity" end
  local neg = v < 0 or (v == 0 and 1 / v < 0)
  if v == 0 then return neg and "-0x0.0p0" or "0x0.0p0" end
  local magnitude = neg and -v or v
  local sign = neg and "-" or ""
  -- A subnormal (|v| < 2^-1022) keeps Java's fixed-exponent denormal
  -- spelling: the fraction as 13 hex digits with its trailing zeros
  -- stripped and the pinned -1022 exponent, never the normalized form.
  -- The two exact scalings recover the subnormal's integer significand
  -- (magnitude * 2^1074 — every intermediate is a normal power-of-two
  -- scaling).
  if magnitude < 2 ^ -1022 then
    local scaled = magnitude * 2 ^ 537 * 2 ^ 537
    local hex = string.format("%013x", scaled)
    hex = string.gsub(hex, "0+$", "")
    if hex == "" then hex = "0" end
    return sign.."0x0."..hex.."p-1022"
  end
  local h = string.format("%a", magnitude)
  h = string.gsub(h, "p(%+)(%d)", "p%2")
  if not string.find(h, ".", 1, true) then
    h = string.gsub(h, "^(0x[0-9a-f]+)p", "%1.0p")
  end
  return sign..h
end

--- Reify a thrown/caught error value as a tagged builtin-Error class
-- instance. The trailing file/line/column arguments are optional: omitted
-- arguments leave the corresponding fields absent (nil), matching _err's
-- convention.
--
-- The canonical identity (canonical nominal descriptor): the instance carries the
-- canonical builtin class atom Error (the one class atom without the
-- leading '@'), and the canonical boundary matcher matches it
-- byte-for-byte. The superseded v1.2 spellings @/Error and
-- @$builtin/Error are never emitted and never parse.
function __rt.error_value(code, message, file, line, column)
  return {
    [__K.kind] = "class",
    [__K.classname] = "Error",
    code = code,
    message = message,
    file = file,
    line = line,
    column = column
  }
end

-- ===== Primitive type checks =====

-- Every primitive row's wrong-kind actual is the closed typed-boundary
-- token (__rt.typed_kind below), never the raw Lua type(): a produced
-- carrier — a class instance (its carried canonical class atom, exactly
-- Error for the builtin), a function wrapper, an array,
-- an async handle — a language null and the absent marker project the
-- same tokens here as the emitted prelude's __typedBoundaryKind, the JVM
-- runtime's actualOf and the JS $kindOf. A raw type() projection would
-- report "table" for every one of those carriers.
function __rt.check_null(v, file, line, column)
  if v ~= __rt.__NULL then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "null" }, "E8001",
      "expected null", file, line, column, "null", __rt.typed_kind(v)))
  end
  return v
end

function __rt.check_boolean(v, file, line, column)
  if type(v) ~= "boolean" then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "boolean" }, "E8001",
      "expected boolean", file, line, column, "boolean", __rt.typed_kind(v)))
  end
  return v
end

function __rt.check_number(v, file, line, column)
  -- Every numeric carrier is the number kind (see check_int): the value the
  -- carrier stands for is admitted whether it carries the int or the number
  -- variant, and the carrier itself is the admitted value (the emitted
  -- prelude's number cell and the JVM/JS runtimes' variant-preserving
  -- admission).
  if type(v) == "table" and v[__K.jn] then return v end
  if type(v) ~= "number" then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "number" }, "E8001",
      "expected number", file, line, column, "number", __rt.typed_kind(v)))
  end
  return v
end

-- ===== UTF-16 code-unit strings =====

--- Validate UTF-8/WTF-8 structure. Lone surrogate units are data; truncated,
-- stray-continuation, overlong and out-of-range encodings reject.
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

--- Classify structural encoding validity: nil for admitted UTF-8/WTF-8,
-- "invalid" for malformed encodings. The walk mirrors utf8_valid.
function __rt.utf8_status(s)
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
      return "invalid"
    end
    if i + len - 1 > n then
      return "invalid"
    end
    if len >= 2 then
      local b2 = string.byte(s, i + 1)
      if b2 == nil or b2 < 0x80 or b2 > 0xBF then return "invalid" end
      if len == 3 then
        local b3 = string.byte(s, i + 2)
        if b3 == nil or b3 < 0x80 or b3 > 0xBF then return "invalid" end
        if b1 == 0xE0 and b2 < 0xA0 then return "invalid" end  -- overlong
      end
      if len == 4 then
        local b3 = string.byte(s, i + 2)
        local b4 = string.byte(s, i + 3)
        if b3 == nil or b3 < 0x80 or b3 > 0xBF then return "invalid" end
        if b4 == nil or b4 < 0x80 or b4 > 0xBF then return "invalid" end
        if b1 == 0xF0 and b2 < 0x90 then return "invalid" end  -- overlong
        if b1 == 0xF4 and b2 > 0x8F then return "invalid" end  -- > U+10FFFF
      end
    end
    i = i + len
  end
  return nil
end

-- Internal strings use UTF-8 code-unit encodings; supplementary pairs are
-- canonicalized to two three-byte units. Host UTF-8 is decoded losslessly.
function __rt.string_units(s)
  local units, i = {}, 1
  while i <= #s do
    local b = s:byte(i)
    local n = b < 128 and 1 or b < 224 and 2 or b < 240 and 3 or 4
    local cp = b % (2 ^ (8 - n))
    if n == 1 then cp = b end
    for k = 1, n - 1 do cp = cp * 64 + s:byte(i + k) % 64 end
    if cp > 65535 then
      cp = cp - 65536
      units[#units + 1] = 55296 + math.floor(cp / 1024)
      units[#units + 1] = 56320 + cp % 1024
    else units[#units + 1] = cp end
    i = i + n
  end
  return units
end

function __rt.string_from_units(units, first, last)
  local out = {}
  for i = first or 1, last or #units do
    local cp = units[i]
    if cp < 128 then out[#out + 1] = string.char(cp)
    elseif cp < 2048 then out[#out + 1] = string.char(192 + math.floor(cp / 64), 128 + cp % 64)
    else out[#out + 1] = string.char(224 + math.floor(cp / 4096),
      128 + math.floor(cp / 64) % 64, 128 + cp % 64) end
  end
  return table.concat(out)
end

function __rt.string_normalize(s)
  return __rt.string_from_units(__rt.string_units(s))
end

-- UTF-8 transport combines paired units; lone units retain their three-byte
-- encoding at the Lua host surface. Console output substitutes U+FFFD.
function __rt.string_transport(s, replace_lone)
  local units, out, i = __rt.string_units(s), {}, 1
  while i <= #units do
    local cp = units[i]
    if cp >= 55296 and cp <= 56319 and units[i + 1] ~= nil
        and units[i + 1] >= 56320 and units[i + 1] <= 57343 then
      cp = 65536 + (cp - 55296) * 1024 + units[i + 1] - 56320
      out[#out + 1] = string.char(240 + math.floor(cp / 262144),
        128 + math.floor(cp / 4096) % 64, 128 + math.floor(cp / 64) % 64, 128 + cp % 64)
      i = i + 2
    else
      if replace_lone and cp >= 55296 and cp <= 57343 then cp = 65533 end
      out[#out + 1] = __rt.string_from_units({cp})
      i = i + 1
    end
  end
  return table.concat(out)
end

function __rt.string_json_body(s)
  local units, out, i = __rt.string_units(s), {}, 1
  local escapes = {[8]="b", [9]="t", [10]="n", [12]="f", [13]="r"}
  while i <= #units do
    local cp, next_unit = units[i], units[i + 1]
    if cp == 34 or cp == 92 then out[#out + 1] = string.char(92, cp)
    elseif escapes[cp] then out[#out + 1] = string.char(92) .. escapes[cp]
    elseif cp >= 55296 and cp <= 56319 and next_unit ~= nil
        and next_unit >= 56320 and next_unit <= 57343 then
      out[#out + 1] = __rt.string_from_units(units, i, i + 1)
      i = i + 1
    elseif cp < 32 or cp >= 55296 and cp <= 57343 then
      out[#out + 1] = string.char(92) .. "u" .. string.format("%04x", cp)
    else out[#out + 1] = __rt.string_from_units(units, i, i) end
    i = i + 1
  end
  return table.concat(out)
end

function __rt.string_compare(a, b)
  local x, y = __rt.string_units(a), __rt.string_units(b)
  for i = 1, math.min(#x, #y) do
    if x[i] ~= y[i] then return x[i] < y[i] and -1 or 1 end
  end
  return #x == #y and 0 or #x < #y and -1 or 1
end

function __rt.check_string(v, file, line, column)
  if type(v) ~= "string" then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "string" }, "E8001",
      "expected string", file, line, column, "string", __rt.typed_kind(v)))
  end
  -- Structural byte defects use the owning closed boundary arm.
  if __rt.utf8_status(v) == "invalid" then
    error(__rt._armErr("HOST_STRING_INVALID_UTF8", {}, "E8001",
      "expected string, got invalid UTF-8 encoding", file, line, column,
      "string", "invalid UTF-8 string"))
  end
  return v
end

--- The reference value-model kind of one value: the closed token every
-- typed boundary reports as its "actual" projection (the emitted
-- prelude's __typedBoundaryKind and this runtime agree). A DEAL table is
-- "table" whatever string keys it carries — a user table can never
-- project a carrier kind — and a produced carrier keeps its own token:
-- the emitted chunk's function carriers ([fn]/[f], no [kind]) project
-- function exactly like the prelude classifies them, and a numeric
-- carrier projects its own recorded variant ("int" or "number"), never
-- one fixed numeric token.
function __rt.typed_kind(v)
  if v == __rt.__UNDEFINED or v == __rt.__MISSING then return "undefined" end
  if v == nil then return "null" end
  if v == __rt.__NULL then return "null" end
  local t = type(v)
  if t == "boolean" then return "boolean" end
  if t == "number" then return "number" end
  if t == "string" then return "string" end
  if t ~= "table" then return t end
  local kind = v[__K.kind]
  if kind == "async" then return "async-operation" end
  if kind == "function" then return "function" end
  if kind == "class" then
    return v[__K.classname] or "class instance"
  end
  if v[__K.d] then return "Error" end
  if v[__K.c] then return v[__K.id] or "class instance" end
  if v[__K.a] then return "array" end
  -- The emitted chunk's function carriers: the closure/adapter shape
  -- ({[fn], sig, csig, fid}) and the host ABI wrapper's own [f] mark
  -- carry no [kind] mark, so they are classified by the same marks the
  -- emitted prelude's __typedBoundaryKind reads. A produced function
  -- value therefore keeps its own token and never passes a table
  -- descriptor; a DEAL table cannot name either private mark.
  if v[__K.fn] ~= nil or v[__K.f] then return "function" end
  -- The numeric-variant carrier keeps its own recorded token: an
  -- int-variant carrier failing a boundary reports "int", a number-variant
  -- carrier "number" (the emitted prelude's __typedBoundaryKind, the JVM
  -- actualOf and the JS $kindOf read the same field).
  if v[__K.jn] then return v.k end
  return "table"
end

function __rt.validate_data(root, file, line, column)
  local pending, seen = {root}, {}
  local function reject(v)
    error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind = "unknown"}, "E8001", "unsupported unknown ingress",
      file, line, column, "unknown", type(v)))
  end
  while #pending > 0 do
    local v = table.remove(pending)
    if v == __rt.__NULL or v == __rt.__UNDEFINED or v == __rt.__MISSING then
    elseif type(v) == "string" then __rt.check_string(v, file, line, column)
    elseif type(v) == "number" or type(v) == "boolean" then
    elseif type(v) ~= "table" then reject(v)
    elseif not seen[v] then
      seen[v] = true
      if getmetatable(v) ~= nil and rawget(v, __K.kind) == nil
          and rawget(v, __K.c) == nil and rawget(v, __K.d) == nil
          and rawget(v, __K.fn) == nil and rawget(v, __K.f) == nil then reject(v) end
      local kind = __rt.typed_kind(v)
      if kind == "async-operation" then reject(v) end
      if kind == "function" and not __rt.valid_function(v) then reject(v) end
      if rawget(v, __K.d) or v[__K.classname] == "Error" then
        __rt.check_string(v.code, file, line, column)
        local message = v.message
        if rawget(v, __K.d) then message = v.m end
        __rt.check_string(message, file, line, column)
      end
      if kind ~= "function" and not rawget(v, __K.jn) then
        for key, member in next, v do
          if type(key) == "string" then
            __rt.check_string(key, file, line, column)
            pending[#pending + 1] = member
          elseif type(key) == "number" then
            if not rawget(v, __K.a) and not rawget(v, __K.ja) then reject(v) end
            pending[#pending + 1] = member
          else
            local private = false
            for _, carrierKey in pairs(__K) do if key == carrierKey then private = true; break end end
            if not private then reject(v) end
          end
        end
      end
    end
  end
  return root
end

function __rt.check_table(v, file, line, column)
  -- The strict table split: the language-null sentinel is not a table, the
  -- internal absent marker is not a table, and a produced carrier of
  -- another kind (a class instance, a function wrapper,
  -- the emitted chunk's own function carrier, an async handle, an array,
  -- a numeric-variant carrier) keeps its own kind and never passes a
  -- table descriptor. A plain table — and the emitted chunk's shared
  -- table carrier — is the table value whatever string keys it carries.
  if v == __rt.__NULL then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "table" }, "E8001",
      "expected table", file, line, column, "table", "null"))
  end
  if v == __rt.__MISSING then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "table" }, "E8001",
      "expected table", file, line, column, "table", "undefined"))
  end
  if type(v) ~= "table" then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "table" }, "E8001",
      "expected table", file, line, column, "table", __rt.typed_kind(v)))
  end
  local projected = __rt.typed_kind(v)
  if projected ~= "table" then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "table" }, "E8001",
      "expected table", file, line, column, "table", projected))
  end
  return __rt.validate_data(v, file, line, column)
end

-- ===== Composite type checks (canonical boundary) =====

--- Check a nullable value.
-- nil (missing optional field) and __NULL (explicit null) both return __NULL.
-- Otherwise delegates to the canonical check_type for the inner descriptor.
function __rt.check_nullable(inner_descriptor, v, file, line, column)
  if v == nil or v == __rt.__NULL then
    return __rt.__NULL
  end
  return __rt.check_type(inner_descriptor, v, file, line, column)
end

--- Check that value is an array whose elements match the canonical array
-- descriptor ("[D]"). Delegates to the canonical check_type over the full
-- descriptor text: elements are checked in 1-based contiguous order with
-- the first failing index wrapped in E8003, and function signature
-- mismatches raise E8010 (runtime page D3 matcher table). The legacy
-- "T[]" dialect is rejected by the canonical parser.
function __rt.check_array(array_descriptor, v, file, line, column)
  return __rt.check_type(array_descriptor, v, file, line, column)
end

-- A producer tags its persistent element descriptor without copying.
function __rt.tag_array(v, element_descriptor)
  v[__K.a] = true
  v[__K.n] = #v
  v[__K.elementDescriptor] = element_descriptor
  return v
end

-- The canonical descriptor parser and matcher (parse_descriptor /
-- check_type) live in the "Canonical descriptor parser and matcher"
-- section below; this merge retired the legacy dialect parser that used
-- to live here (runtime page D3: one canonical grammar, one matcher
-- table, legacy spellings rejected and never emitted).

-- ===== Integer arithmetic =====

-- ===== Canonical descriptor parser and matcher =====
--
-- The canonical cutover (runtime page D3): the legacy dialect parser and
-- matcher are removed; parse_descriptor / check_type /
-- check_array / check_nullable below ARE the canonical matcher. Every
-- generated artifact, stdlib signature, and host declared map
-- carries canonical descriptors, and any legacy dialect spelling
-- ("T[]", "T|null", bare class names, "...T[]") is rejected at the
-- boundary. error_value tags reified Error instances with the canonical
-- identity Error (the builtin class atom), matched byte-for-byte here.
--
-- Canonical grammar (canonical-type-system-and-runtime-descriptors D2):
--
--   descriptor := primitive | error | class | array | nullable | function
--   primitive  := "null" | "boolean" | "number" | "string" | "table"
--   error      := "Error"
--   class      := "@" component ("/" component)+
--   array      := "[" descriptor "]"
--   nullable   := "?" descriptor
--   function   := "async"? "(" (descriptor ("," descriptor)*)? ")" "->" descriptor
--
-- A component is a non-empty maximal scalar run that is not "." or ".."
-- as a whole component and is free of U+0000, C0/DEL controls, Unicode
-- whitespace, "@", "[", "]", "?", "(", ")", ",", "/", and contiguous
-- "->". The final component is the class name and must match the source
-- identifier shape [A-Za-z_][A-Za-z0-9_]*. Class atoms are stored
-- byte-for-byte as the complete text including the leading "@" and are
-- never split into root/path boundaries; function atoms carry the exact
-- sync/async marker and the byte-exact descriptor text.
--
-- The parser is strict and total: it never throws and never returns a
-- partial AST. Every legacy dialect spelling ("T[]", "T|null", bare
-- class names, "...T[]", dotted class-name-position text) yields nil;
-- the canonical parser accepts no legacy spelling at any point.

local CANONICAL_PRIMITIVES = {
  "null", "undefined", "unknown", "void", "boolean", "number", "string", "record",
}

--- Strict canonical recursive-descent parser (total; never throws).
-- Returns the complete atom on full consumption, or nil for any text the
-- canonical grammar rejects. Atom shapes:
--   { kind="primitive", name=kw, text=kw }
--   { kind="class", name=fullText, text=fullText }
--   { kind="array", element=atom, text="[D]" }
--   { kind="nullable", inner=atom, text="?D" }
--   { kind="function", isAsync=bool, params={atom,...}, ret=atom, text=full }
-- Every node carries its byte-exact descriptor text so the function row
-- can compare wrapper sigs byte-for-byte at any nesting depth.
--
-- This is THE descriptor parser: the boundary path (check_type) and the
-- wrapper/loader paths (from_lua_function, load_host) all parse through
-- it, so the canonical grammar is the only accepted dialect.
local function parse_descriptor(text)
  if type(text) ~= "string" then
    return nil
  end
  local n = #text
  local pos = 1

  local function is_identifier_byte(b)
    return (b >= 65 and b <= 90) or (b >= 97 and b <= 122)
        or (b >= 48 and b <= 57) or b == 95
  end

  local function is_identifier_shape(component)
    local len = #component
    if len == 0 then
      return false
    end
    local first = string.byte(component, 1)
    if not ((first >= 65 and first <= 90) or (first >= 97 and first <= 122) or first == 95) then
      return false
    end
    for i = 2, len do
      local b = string.byte(component, i)
      if not is_identifier_byte(b) then
        return false
      end
    end
    return true
  end

  -- The pinned component alphabet, byte-exact against the canonical
  -- service's scalar-level set: C0/DEL controls, the structural scalars
  -- @ [ ] ? ( ) , , every scalar of the full pinned Unicode White_Space
  -- property (UAX #44 PropList White_Space=Yes: 0009-000D, 0020, 0085,
  -- 00A0, 1680, 2000-200A, 2028, 2029, 202F, 205F, 3000 — the single
  -- component-exclusion authority shared with
  -- ModuleIdentityResolver.isUnicodeWhiteSpace and the JS runtime's
  -- $CANONICAL_WS; never Character.isWhitespace/isSpaceChar, whose
  -- isWhitespace excludes U+0085 NEXT LINE since JDK 5), and surrogate
  -- code points — each ends the maximal run ('/' is the component
  -- separator, handled by the class parser). The checks walk UTF-8
  -- bytes, which is exact: no continuation byte collides with a
  -- forbidden ASCII byte and every forbidden non-ASCII scalar is matched
  -- by its full encoded sequence.
  local function forbidden_in_component(text, pos, n)
    local b = string.byte(text, pos)
    if b < 0x20 or b == 0x20 or b == 0x7F then
      return true
    end
    if b == 0x40 or b == 0x5B or b == 0x5D or b == 0x3F
        or b == 0x28 or b == 0x29 or b == 0x2C then
      return true
    end
    local b2 = pos + 1 <= n and string.byte(text, pos + 1) or nil
    local b3 = pos + 2 <= n and string.byte(text, pos + 2) or nil
    if b == 0xC2 and (b2 == 0x85 or b2 == 0xA0) then
      return true  -- U+0085 NEXT LINE, U+00A0 NO-BREAK SPACE (White_Space=Yes)
    end
    if b == 0xE1 and b2 == 0x9A and b3 == 0x80 then
      return true  -- U+1680 OGHAM SPACE MARK
    end
    if b == 0xE2 and b2 == 0x80 then
      if (b3 ~= nil and b3 >= 0x80 and b3 <= 0x8A)
          or b3 == 0xA8 or b3 == 0xA9 or b3 == 0xAF then
        return true  -- U+2000..U+200A, U+2028, U+2029, U+202F
      end
    end
    if b == 0xE2 and b2 == 0x81 and b3 == 0x9F then
      return true  -- U+205F MEDIUM MATHEMATICAL SPACE
    end
    if b == 0xE3 and b2 == 0x80 and b3 == 0x80 then
      return true  -- U+3000 IDEOGRAPHIC SPACE
    end
    if b == 0xED and b2 ~= nil and b2 >= 0xA0 and b2 <= 0xBF then
      return true  -- surrogate code point (never a valid Unicode scalar)
    end
    return false
  end

  local parse_descriptor

  local function parse_function(is_async, start)
    pos = pos + 1  -- consume '('
    local params = {}
    if pos <= n and string.byte(text, pos) ~= 0x29 then
      while true do
        local param = parse_descriptor()
        if param == nil then
          return nil
        end
        params[#params + 1] = param
        if pos > n then
          return nil
        end
        local b = string.byte(text, pos)
        if b == 0x2C then
          pos = pos + 1
        elseif b == 0x29 then
          break
        else
          return nil
        end
      end
    end
    if pos > n then
      return nil
    end
    pos = pos + 1  -- consume ')'
    if pos + 1 > n or string.byte(text, pos) ~= 0x2D or string.byte(text, pos + 1) ~= 0x3E then
      return nil  -- the exact "->" arrow
    end
    pos = pos + 2  -- consume "->"
    local ret = parse_descriptor()
    if ret == nil then
      return nil
    end
    return { kind = "function", isAsync = is_async, params = params, ret = ret,
             text = string.sub(text, start, pos - 1) }
  end

  local function parse_class()
    local start = pos
    pos = pos + 1  -- consume '@'
    local has_separator = false
    while true do
      local component_start = pos
      while pos <= n do
        local b = string.byte(text, pos)
        if b == 0x5D or b == 0x29 or b == 0x2C then
          break  -- enclosing delimiter: the atom ends exactly here
        end
        if b == 0x2F then
          break  -- component separator
        end
        if b == 0x2D and pos + 1 <= n and string.byte(text, pos + 1) == 0x3E then
          return nil  -- contiguous "->" inside a component
        end
        if forbidden_in_component(text, pos, n) then
          break  -- the maximal allowed run ends before the forbidden byte
        end
        pos = pos + 1
      end
      if pos == component_start then
        return nil  -- empty component ("@" alone, trailing "/", "//")
      end
      local component = string.sub(text, component_start, pos - 1)
      if component == "." or component == ".." then
        return nil  -- "." / ".." as a whole component
      end
      if not has_separator and component == "$builtin" then
        return nil  -- the superseded v1.2 builtin namespace forms no class atom
      end
      if pos <= n and string.byte(text, pos) == 0x2F then
        has_separator = true
        pos = pos + 1  -- consume '/'; the next component must be non-empty
      else
        -- This component terminates the atom, so it is the class name and
        -- must be identifier-shaped (dotted class-name text fails here).
        if not is_identifier_shape(component) then
          return nil
        end
        if not has_separator then
          return nil  -- fewer than two components
        end
        return { kind = "class", name = string.sub(text, start, pos - 1),
                 text = string.sub(text, start, pos - 1) }
      end
    end
  end

  parse_descriptor = function()
    if pos > n then
      return nil
    end
    local b = string.byte(text, pos)
    if b == 0x5B then  -- '[': array
      local start = pos
      pos = pos + 1
      local element = parse_descriptor()
      if element == nil then
        return nil
      end
      if pos > n or string.byte(text, pos) ~= 0x5D then
        return nil
      end
      pos = pos + 1
      return { kind = "array", element = element, text = string.sub(text, start, pos - 1) }
    end
    if string.sub(text, pos, pos + 13) == "null|undefined" then
      pos = pos + 14
      return {kind = "nullable", inner = {kind = "primitive", name = "undefined", text = "undefined"},
        admitsNull = true, admitsUndefined = false, text = "null|undefined"}
    end
    if b == 0x3F or b == 0x21 then
      local start = pos
      local admitsNull = b == 0x3F
      local admitsUndefined = not admitsNull
      pos = pos + 1
      if admitsNull and string.byte(text, pos) == 0x21 then
        admitsUndefined = true
        pos = pos + 1
      end
      local inner = parse_descriptor()
      if inner == nil then
        return nil
      end
      if inner.kind == "nullable" then
        return nil  -- nested nullable ("??T")
      end
      if inner.kind == "primitive" and (inner.name == "unknown" or inner.name == "void"
          or (admitsNull and inner.name == "null") or (admitsUndefined and inner.name == "undefined")) then return nil end
      return { kind = "nullable", inner = inner, admitsNull = admitsNull,
        admitsUndefined = admitsUndefined, text = string.sub(text, start, pos - 1) }
    end
    if b == 0x28 then  -- '(': function
      return parse_function(false, pos)
    end
    if b == 0x40 then  -- '@': class
      return parse_class()
    end
    -- Primitive keywords. The keyword match is a prefix match like the
    -- canonical service: any residue after a keyword match is rejected by
    -- the complete-consumption check ("int[]", "int|null", "intFoo").
    for i = 1, #CANONICAL_PRIMITIVES do
      local kw = CANONICAL_PRIMITIVES[i]
      if string.sub(text, pos, pos + #kw - 1) == kw then
        pos = pos + #kw
        return { kind = "primitive", name = kw, text = kw }
      end
    end
    -- The builtin Error atom: the exact token "Error" terminating at a
    -- descriptor position (end of input or a delimiter). The superseded
    -- v1.2 spellings @/Error and @$builtin/Error are never admitted.
    if string.sub(text, pos, pos + 4) == "Error"
        and (pos + 5 > n or not is_identifier_byte(string.byte(text, pos + 5))) then
      pos = pos + 5
      return { kind = "class", name = "Error", text = "Error" }
    end
    -- The exact async marker: "async" must be immediately followed by '('.
    if string.sub(text, pos, pos + 4) == "async"
        and (pos + 5 > n or not is_identifier_byte(string.byte(text, pos + 5))) then
      if pos + 5 > n or string.byte(text, pos + 5) ~= 0x28 then
        return nil
      end
      local start = pos
      pos = pos + 5  -- point at '('
      return parse_function(true, start)
    end
    -- Anything else — including identifier-shaped runs (bare class names)
    -- and the legacy "...T[]" leading dots — is a rejection.
    return nil
  end

  local ast = parse_descriptor()
  if ast == nil then
    return nil
  end
  if pos <= n then
    return nil  -- complete consumption is mandatory (trailing content)
  end
  return ast
end

--- Recursive canonical checker over one parsed atom (the parent matcher
-- table, canonical-type-system-and-runtime-descriptors D4). Every failure
-- raises a DEAL error through _err with the forwarded (file, line, column)
-- span; never a raw Lua error; never mutates its inputs. Array elements
-- are checked in 1-based contiguous order and the first failing index is
-- wrapped in E8003.
local function check_canonical_ast(ast, v, file, line, column, seen)
  seen = seen or {}
  if type(v) == "table" and v ~= __rt.__NULL and v ~= __rt.__UNDEFINED and v ~= __rt.__MISSING
      and getmetatable(v) ~= nil and rawget(v, __K.kind) == nil and rawget(v, __K.c) == nil
      and rawget(v, __K.d) == nil and rawget(v, __K.fn) == nil
      and rawget(v, __K.a) == nil and rawget(v, __K.t) == nil then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind = "unknown"}, "E8001",
      "unsupported unknown ingress", file, line, column, "unknown", type(v)))
  end
  local kind = ast.kind
  if kind == "primitive" then
    local name = ast.name
    if name == "null" then
      return __rt.check_null(v, file, line, column)
    elseif name == "boolean" then
      return __rt.check_boolean(v, file, line, column)
    elseif name == "number" then
      return __rt.check_number(v, file, line, column)
    elseif name == "string" then
      return __rt.check_string(v, file, line, column)
    elseif name == "record" then
      return __rt.check_table(v, file, line, column)
    elseif name == "undefined" or name == "void" then
      if v == __rt.__UNDEFINED or v == __rt.__MISSING then return __rt.__UNDEFINED end
      error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind = "undefined"}, "E8001", "expected undefined",
        file, line, column, "undefined", __rt.typed_kind(v)))
    elseif name == "unknown" then
      __rt.validate_data(v, file, line, column)
      if type(v) == "string" then return __rt.check_string(v, file, line, column) end
      local actual = __rt.typed_kind(v)
      if actual ~= "async-operation" and type(v) ~= "function" and type(v) ~= "thread" and type(v) ~= "userdata" and type(v) ~= "cdata" then
        if v == __rt.__MISSING then return __rt.__UNDEFINED end
        return __rt.validate_data(v, file, line, column)
      end
      error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind = "unknown"}, "E8001", "unsupported unknown ingress",
        file, line, column, "unknown", actual))
    end
    error(__rt._err("E8001", "unknown primitive type: " .. name, file, line, column, nil, nil))
  elseif kind == "class" then
    -- Byte-for-byte nominal identity: the runtime tag must equal the
    -- class atom's complete text (text-opaque — no path normalization,
    -- no bare-name fallback). A class-marked value without its carried
    -- canonical atom has no identity to compare and selects the closed
    -- kind arm (the JVM host check's own disposition), never a composed
    -- "unknown" atom.
    if type(v) ~= "table" or (v[__K.kind] ~= "class" and not v[__K.c] and not v[__K.d]) then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "class instance" },
        "E8001", "expected class instance", file, line, column, "class",
        __rt.typed_kind(v)))
    end
    local actual_class = v[__K.classname] or v[__K.id] or (v[__K.d] and "Error")
    if actual_class == nil then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "class instance" },
        "E8001", "expected class instance", file, line, column, "class",
        __rt.typed_kind(v)))
    end
    if actual_class ~= ast.name then
      error(__rt._armErr("CLASS_IDENTITY", { expected = ast.name, actual = actual_class },
        "E8001", "expected instance of " .. ast.name .. ", got "
          .. tostring(actual_class), file, line, column, ast.name, actual_class))
    end
    if ast.name == "Error" then
      __rt.check_string(v.code, file, line, column)
      local message = v.message
      if v[__K.d] then message = v.m end
      __rt.check_string(message, file, line, column)
      return v
    end
    local plan = __rt.__hostClassPlans and __rt.__hostClassPlans[ast.name]
    if plan ~= nil then
      local descriptors = seen[v]
      if descriptors and descriptors[ast.text] then return v end
      descriptors = descriptors or {}; seen[v] = descriptors; descriptors[ast.text] = true
      for _, field in ipairs(plan) do
        local member = rawget(v[__K.f] or v, field.name)
        if member == __rt.__MISSING then member = nil end
        if member == nil and not field.optional then
          error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind = field.descriptor}, "E8001",
            "missing required field", file, line, column, field.descriptor, "undefined"))
        end
        if member ~= nil then
          check_canonical_ast(parse_descriptor(field.descriptor), member, file, line, column, seen)
        end
      end
    end
    return v
  elseif kind == "array" then
    -- The array value only: a produced array carrier (the emitted chunk's
    -- array mark) and the reference runtime's own dense-indexed array form
    -- are arrays; the language null sentinel, the absent marker, every
    -- other carrier kind (a class instance, a table, a
    -- function wrapper, an async handle) and any non-dense table are not.
    -- The null check runs before the shape check: an empty sentinel table
    -- is never the empty array.
    if v == __rt.__NULL or v == __rt.__MISSING or type(v) ~= "table" then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "array" }, "E8001",
        "expected array", file, line, column, "array", __rt.typed_kind(v)))
    end
    local persistent = v[__K.elementDescriptor]
    if persistent ~= nil and persistent ~= ast.element.text then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "array" }, "E8001",
        "expected array", file, line, column, "array", __rt.typed_kind(v)))
    end
    local length
    if v[__K.a] ~= nil then
      length = v[__K.n] or 0
      while rawget(v, length + 1) ~= nil do length = length + 1 end
      v[__K.n] = length
    elseif __rt.typed_kind(v) ~= "table" then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "array" }, "E8001",
        "expected array", file, line, column, "array", __rt.typed_kind(v)))
    else
      local dense = true
      local max = 0
      for k in pairs(v) do
        if type(k) ~= "number" or k < 1 or k % 1 ~= 0 then
          dense = false
          break
        end
        if k > max then max = k end
      end
      if not dense then
        error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "array" }, "E8001",
          "expected array", file, line, column, "array", "table"))
      end
      length = max
    end
    for i = 1, length do
      local ok = v[i] ~= nil and pcall(check_canonical_ast, ast.element, v[i], file, line, column, seen)
      if not ok then
        error(__rt._armErr("ARRAY_ELEMENT_KIND", { oneBasedIndex = i }, "E8003",
          "array element " .. i .. " type mismatch", file, line, column,
          ast.element.text, __rt.typed_kind(v[i])))
      end
    end
    return v
  elseif kind == "nullable" then
    if ast.admitsNull and (v == nil or v == __rt.__NULL) then return __rt.__NULL end
    if ast.admitsUndefined and (v == __rt.__UNDEFINED or v == __rt.__MISSING) then return __rt.__UNDEFINED end
    return check_canonical_ast(ast.inner, v, file, line, column, seen)
  elseif kind == "function" then
    -- Function row: a wrapper whose carried descriptor equals the atom's
    -- byte-exact text (the exact sync/async marker included). A wrapper
    -- carrying no signature text selects the closed kind arm, exactly
    -- like the JVM host check's own function row, so a malformed carrier
    -- is never projected through the signature arm's two descriptor
    -- operands.
    if type(v) ~= "table" or (v[__K.kind] ~= "function" and v[__K.fn] == nil) then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "function" }, "E8001",
        "expected function", file, line, column, "function", __rt.typed_kind(v)))
    end
    local signature = v.sig or v[__K.csig] or v[__K.sig]
    if not __rt.valid_function(v) then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "function" }, "E8001",
        "expected function", file, line, column, "function", __rt.typed_kind(v)))
    end
    if signature ~= ast.text then
      error(__rt._armErr("FUNCTION_SIGNATURE_MISMATCH",
        { expected = ast.text, actual = signature }, "E8010",
        "function signature mismatch: expected " .. ast.text .. ", got "
          .. tostring(signature), file, line, column, ast.text, signature))
    end
    return v
  end
  error(__rt._err("E8001", "internal: unhandled descriptor kind: " .. tostring(kind), file, line, column, nil, nil))
end

--- Strict canonical descriptor parser (total; never throws).
-- Returns the complete atom table on full consumption, or nil for any
-- text the canonical grammar rejects — including every legacy dialect
-- spelling ("T[]", "T|null", bare class names, "...T[]").
function __rt.parse_canonical_descriptor(text)
  return parse_descriptor(text)
end

--- The canonical type checker — the one runtime boundary matcher
-- (parent matcher table D4; runtime page D3): one row per primitive
-- and [D] arrays via 1-based contiguous iteration with
-- E8003 wrapping at the first failing index, ?D nullables, function
-- descriptors with byte-for-byte signature comparison (E8010 on
-- mismatch), and class atoms matched byte-for-byte as complete text
-- (including the canonical builtin projection Error, which
-- error_value tags).
--
-- This is the boundary path every typed crossing uses: generated
-- wrapper/param/return/await checks, host declared maps, stdlib
-- signatures, and the runtime entries (class_plan_ phase-3 field
-- validation, invoke_async_export completion validation) all call this
-- entry with canonical descriptors. It accepts only the canonical
-- grammar; every legacy dialect spelling fails the parser.
--
-- @return the checked value, unchanged
-- Errors: DEAL errors through _err with the forwarded (file, line,
--         column) span — E8001 (kind/class/malformed mismatch, E8004
--         int out of range via check_int), E8003 (array element
--         mismatch), E8010 (function signature mismatch).
function __rt.check_type(descriptor, v, file, line, column)
  if descriptor == nil then
    error(__rt._err("E8001", "internal: nil type descriptor", file, line, column, nil, nil))
  end
  local parsed = parse_descriptor(descriptor)
  if parsed == nil then
    error(__rt._err("E8001", "internal: cannot parse type descriptor: " .. tostring(descriptor), file, line, column, nil, nil))
  end
  return check_canonical_ast(parsed, v, file, line, column)
end

--- Alias of the canonical checker under the MATCHER delivery name; both
-- entries are the same canonical matcher.
function __rt.check_canonical_type(descriptor, v, file, line, column)
  return __rt.check_type(descriptor, v, file, line, column)
end

-- ===== Function infrastructure =====

--- Create a function wrapper.
-- The wrapper is a table { [__K.kind] = "function", sig = sig, f = f }.
-- No __call metamethod is set; callers use wrapper.f(...).
function __rt.valid_function(v)
  if type(v) ~= "table" then return false end
  local payload = rawget(v, __K.fn) or rawget(v, "f")
  local signature = rawget(v, "sig") or rawget(v, __K.csig) or rawget(v, __K.sig)
  local parsed = type(signature) == "string" and parse_descriptor(signature) or nil
  return type(payload) == "function" and parsed ~= nil and parsed.kind == "function"
end

function __rt.function_(sig, f)
  return { [__K.kind] = "function", sig = sig, f = f }
end

--- Extract the raw Lua function from a wrapper for host interop.
local function_origins = setmetatable({}, {__mode = "k"})
function __rt.as_lua_function(fn)
  if type(fn) ~= "table" or fn[__K.kind] ~= "function" then
    error(__rt._err("E8001", "expected function wrapper", nil, nil, nil, "function", type(fn)))
  end
  function_origins[fn.f] = fn
  return fn.f
end

--- Returns true when the descriptor denotes a function type or a
-- nullable-of-function type. Used to decide which arguments must be
-- adapted with as_lua_function before a host call. Parses through the
-- canonical parser; parsed.inner is the canonical inner atom.
local function is_function_type(descriptor)
  local parsed = parse_descriptor(descriptor)
  if parsed == nil then
    return false
  end
  if parsed.kind == "function" then
    return true
  end
  if parsed.kind == "nullable" then
    return parsed.inner.kind == "function"
  end
  return false
end

--- The closed inner reason of one canonical-matcher failure: the arm the
-- matcher selected (the failure table's `arm` selection), rendered by the
-- artifact-owned inner renderer (`__rt.__hostInnerArm`) from the
-- artifact's serialized closed arm table. A missing renderer or a
-- matcher failure carrying no selection is a fail-closed producer defect
-- — the wrapper never publishes a locally composed reason, and never
-- recognizes an arm by its text.
local function host_inner_reason(err)
  local render = __rt.__hostInnerArm
  if render == nil then
    error("the host inner-arm renderer is not registered on the deployed "
      .. "runtime (the emitted chunk registers it at load; a producer "
      .. "defect)", 0)
  end
  local selection = type(err) == "table" and err.arm or nil
  if type(selection) ~= "table" then
    error("the boundary matcher raised a failure with no closed arm "
      .. "selection (the host cell renders the selected arm's own template; "
      .. "a producer defect)", 0)
  end
  return render(selection.id, selection.values)
end

--- One mapped host-wrapper failure (canonical failure-projection
-- authority P1/P4): the emitted chunk registers its own closed-arm
-- renderer on the deployed runtime at load (`__rt.__hostArm`), so the
-- declared-parameter cell, the declared sync return cells, and the
-- async-shape cell of `from_lua_function`'s wrappers render through the
-- authority — the arm's own template instantiated with the closed named
-- parameters, its declared expected/actual projections (the renderer
-- projects the failing subject through the arm's own declaration), at the
-- supplied call origin — and compose no failure text of their own. An
-- unregistered renderer (the runtime driven outside a production
-- artifact) is a fail-closed producer defect, never a textual fallback.
local function host_arm_failure(id, values, file, line, column, expected, subject)
  local render = __rt.__hostArm
  if render == nil then
    error("the host failure-arm renderer is not registered on the deployed "
      .. "runtime (the emitted chunk registers it at load; a producer "
      .. "defect)", 0)
  end
  error(render(id, values, file, line, column, expected, subject), 0)
end

--- Wrap a plain Lua function with runtime parameter and return type checks.
-- Parses the signature descriptor through the canonical parser (the only
-- accepted dialect) to determine the expected parameter types and return
-- type.
--
-- The mapped failures (the declared parameter cell, the sync return
-- cells, and the async-shape cell) render through the artifact-owned
-- closed-arm renderer (`__rt.__hostArm`) with their mapped inner reasons
-- (the matcher-selected closed arms) rendered by the artifact's own arm
-- table (`__rt.__hostInnerArm`), never through a local text composition.
--
-- Three-way return dispatch:
-- 1. Async descriptor (parsed.isAsync == true): the call must produce at
--    least one result and every result must be an async operation table
--    ({ [__K.kind] = "async" }); the declared return type R is enforced at the
--    await site, not here.
-- 2. Non-null return (the return atom is not the "null" primitive): the
--    call must produce at least one result — zero results and a single
--    explicit Lua nil both pack to an empty list — and every result is
--    checked against the declared return descriptor, including ?T, [T],
--    class, and function forms.
-- 3. Sync null return (the return atom is the "null" primitive and not
--    async): the call must produce at least one result and every result
--    must be the __rt.__NULL sentinel (a plain Lua nil packs to an empty
--    result list and is rejected by the presence rule).
--
-- Function-typed parameters are adapted with __rt.as_lua_function before the
-- raw call, so hosts receive plain Lua functions; __NULL (and nil) arguments
-- on nullable-function parameters pass through unadapted.
--
-- DEAL has no rest parameters: the parameter list is exact. A legacy
-- "...T" descriptor entry never parses under the canonical grammar, so a
-- signature carrying one is rejected here at wrap time (E8010).
function __rt.from_lua_function(sig, raw_f)
  if type(raw_f) ~= "function" then
    error(__rt._err("E8001", "expected function, got " .. type(raw_f), nil, nil, nil, "function", type(raw_f)))
  end

  local parsed = parse_descriptor(sig)
  if parsed == nil or parsed.kind ~= "function" then
    error(__rt._err("E8010", "invalid function signature: " .. tostring(sig), nil, nil, nil, nil, nil))
  end

  -- Canonical atom shape: params and ret are atoms; the descriptor text of
  -- each is its byte-exact .text field.
  local param_descriptors = {}
  for i = 1, #parsed.params do
    param_descriptors[i] = parsed.params[i].text
  end
  local ret_atom = parsed.ret
  local ret_descriptor = ret_atom.text
  local is_null_ret = ret_atom.kind == "primitive" and ret_atom.name == "null"
  local is_async = parsed.isAsync == true

  return __rt.function_(sig, function(...)
    local nargs_full = select("#", ...)
    local required_params = #param_descriptors

    -- Converged host-boundary call shape (ISSUE-0598): the emitted call
    -- site appends the literal span triplet f(v1, ..., vN, file, line,
    -- column); a call carrying three or more arguments splits the
    -- trailing triple off before the exact-arity gate (the JS
    -- hostFunction mirror), so boundary errors report the DEAL call
    -- site byte-exact.
    local file, line, column
    local nargs = nargs_full
    if nargs_full >= 3 then
      file = select(nargs_full - 2, ...)
      line = select(nargs_full - 1, ...)
      column = select(nargs_full, ...)
      nargs = nargs_full - 3
    end

    -- Exact arity: no rest parameters exist.
    if nargs < required_params then
      error(__rt._err("E8010", "expected at least " .. required_params .. " arguments, got " .. nargs, file, line, column, nil, nil))
    end
    if nargs > required_params then
      error(__rt._err("E8010", "expected " .. required_params .. " arguments, got " .. nargs, file, line, column, nil, nil))
    end

    -- The user arguments in one pack (the JS mirror's
    -- args.slice(0, nargs)); the raw host function never sees the
    -- trailing span triplet.
    local user_args = {}
    for i = 1, nargs do
      user_args[i] = select(i, ...)
    end

    -- Check required parameters (the closed HOST_PARAMETER_CELL arm with
    -- the closed inner arm the matcher selected as its inner reason).
    for i = 1, required_params do
      local arg = user_args[i]
      local param_desc = param_descriptors[i]
      local ok, err = pcall(__rt.check_type, param_desc, arg, file, line, column)
      if not ok then
        host_arm_failure("HOST_PARAMETER_CELL",
          {index = i, inner = host_inner_reason(err)},
          file, line, column, param_desc, arg)
      end
    end

    -- Adapt function-typed arguments before the raw call so hosts receive
    -- plain Lua functions. __NULL and nil arguments on nullable-function
    -- parameters pass through unadapted.
    for i = 1, required_params do
      local arg = user_args[i]
      if is_function_type(param_descriptors[i]) then
        if arg ~= nil and arg ~= __rt.__NULL then
          arg = __rt.as_lua_function(arg)
        end
      end
      user_args[i] = arg
    end

    -- Call the raw function
    local results = { raw_f(unpack(user_args, 1, nargs)) }
    local nresults = #results
    for i = 1, nresults do
      if type(results[i]) == "function" and function_origins[results[i]] ~= nil then
        results[i] = function_origins[results[i]]
      end
    end

    -- Return validation: three-way dispatch (see the doc comment above).
    -- Every mapped branch renders the closed arm of its cell (the async
    -- shape's ASYNC_SHAPE, the sync non-null return's
    -- HOST_SYNC_RETURN_NOTHING / HOST_SYNC_RETURN_CELL, and the sync-null
    -- return's own pair of the same two arms) through the artifact-owned
    -- renderer; a failure site holds no text of its own.
    if is_async then
      -- 1. Async: require an async operation result.
      if nresults < 1 then
        host_arm_failure("ASYNC_SHAPE", {}, file, line, column,
          "async operation", nil)
      end
      for i = 1, nresults do
        local r = results[i]
        if type(r) ~= "table" or r[__K.kind] ~= "async" then
          host_arm_failure("ASYNC_SHAPE", {}, file, line, column,
            "async operation", r)
        end
      end
    elseif ret_descriptor == "void" and nresults == 0 then
      return __rt.__UNDEFINED
    elseif not is_null_ret then
      -- 2. Non-null declared return: require at least one result, then check
      -- every result against the declared descriptor.
      if nresults < 1 then
        host_arm_failure("HOST_SYNC_RETURN_NOTHING",
          {expected = ret_descriptor}, file, line, column, ret_descriptor,
          nil)
      end
      for i = 1, nresults do
        local ok, err = pcall(__rt.check_type, ret_descriptor, results[i], file, line, column)
        if not ok then
          host_arm_failure("HOST_SYNC_RETURN_CELL",
            {inner = host_inner_reason(err)},
            file, line, column, ret_descriptor, results[i])
        end
      end
    else
      -- 3. Sync null return: require at least one result and every result
      -- must be the __rt.__NULL sentinel.
      if nresults < 1 then
        host_arm_failure("HOST_SYNC_RETURN_NOTHING", {expected = ret_descriptor},
          file, line, column, ret_descriptor, nil)
      end
      for i = 1, nresults do
        local ok, err = pcall(__rt.check_null, results[i], file, line, column)
        if not ok then
          host_arm_failure("HOST_SYNC_RETURN_CELL",
            {inner = host_inner_reason(err)},
            file, line, column, ret_descriptor, results[i])
        end
      end
    end

    return unpack(results, 1, nresults)
  end)
end

--- One mapped host-load failure (canonical failure-projection
-- authority P1/P4): the emitted chunk registers its own closed-arm
-- renderer on the deployed runtime at load (`__rt.__hostLoadArm`), so
-- `load_host` renders every mapped HOST_LOAD arm through the authority —
-- the arm's own template instantiated with the closed named parameters,
-- its declared expected/actual fields (an arm that declares none
-- publishes none), at the importing statement's origin — and composes
-- no failure text of its own. An unregistered renderer (the runtime
-- driven outside a production artifact) is a fail-closed producer
-- defect, never a textual fallback.
local function host_load_failure(id, values, file, line, column)
  local render = __rt.__hostLoadArm
  if render == nil then
    error("the host-load arm renderer is not registered on the "
      .. "deployed runtime (the emitted chunk registers it at load; a "
      .. "producer defect)", 0)
  end
  error(render(id, values, file, line, column), 0)
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
-- Errors: E8011 at load — the mapped failures render their closed
-- HOST_LOAD arm through the artifact-registered arm renderer at the
-- importing statement's origin: the require failure (HOST_LOAD_FAILED),
-- a non-table module result (HOST_LOAD_NOT_A_MODULE), a missing declared
-- export (HOST_LOAD_MISSING_EXPORT), a pre-wrapped signature mismatch
-- (HOST_LOAD_SIGNATURE_MISMATCH), a class identity mismatch
-- (HOST_LOAD_IDENTITY_MISMATCH), and missing/non-table defaults or
-- present-but-non-table <C>_fields (HOST_LOAD_INVALID_METADATA); an arm
-- declaring no expected/actual field publishes none. The remaining
-- runtime shape defects (a non-function export, a non-function
-- pre-wrapped .f, a non-class-meta class export, a non-table declared
-- map, an unsupported declared descriptor) are runtime-side E8011
-- producer/shape defects with no declared arm. E8010 never fires at load
-- for legal declared maps (every emitted Type.Func descriptor is a
-- canonical function atom); call-time violations raise E8010 inside the
-- wrapped functions.
function __rt.load_host(module_path, declared, file, line, column)
  if type(declared) ~= "table" then
    error(__rt._err("E8011", "host module declarations must be a table", file, line, column, "table", type(declared)))
  end

  local ok, raw = pcall(require, module_path)
  if not ok then
    host_load_failure("HOST_LOAD_FAILED",
      { module = module_path, reason = tostring(raw) }, file, line, column)
  end
  if type(raw) ~= "table" then
    host_load_failure("HOST_LOAD_NOT_A_MODULE",
      { module = module_path }, file, line, column)
  end

  local exports = {}
  for name, descriptor in pairs(declared) do
    local v = raw[name]
    if v == nil then
      host_load_failure("HOST_LOAD_MISSING_EXPORT",
        { name = name, module = module_path }, file, line, column)
    end
    local parsed = parse_descriptor(descriptor)
    if parsed ~= nil and parsed.kind == "function" then
      if type(v) == "function" then
        -- Raw Lua function: wrap with parameter/return/async-shape checks.
        exports[name] = __rt.from_lua_function(descriptor, v)
      elseif type(v) == "table" and (v[__K.kind] == "function"
          or v.__kind == "function") then
        -- Pre-wrapped export: the declared descriptor is the only trusted
        -- metadata. Validate the identity, then re-wrap .f so raw and
        -- pre-wrapped exports get identical call-time enforcement. The
        -- documented host-authoring shape ({__kind = "function", sig, f})
        -- is a host-module input, accepted here and normalized by the
        -- re-wrap; the marks a DEAL value carries are the private ones.
        if v.sig ~= descriptor then
          host_load_failure("HOST_LOAD_SIGNATURE_MISMATCH",
            { name = name, module = module_path, expected = descriptor,
              actual = tostring(v.sig) }, file, line, column)
        end
        if type(v.f) ~= "function" then
          error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' has non-function .f", file, line, column, "function", type(v.f)))
        end
        exports[name] = __rt.from_lua_function(descriptor, v.f)
      else
        error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' is not a function", file, line, column, "function", type(v)))
      end
    elseif parsed ~= nil and parsed.kind == "class" then
      -- Class meta: the identity must equal the declared descriptor exactly
      -- (module-qualified nominal identity). The documented host-authoring
      -- shape ({__kind = "class", __classname = <atom>}) is a host-module
      -- input; it is normalized into the runtime's private-marked carrier
      -- so every later consumer reads one representation, and a DEAL table
      -- with the same string keys can never be projected as a class.
      if type(v) ~= "table" or (v[__K.kind] ~= "class"
          and v.__kind ~= "class") then
        error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' is not a class meta table", file, line, column, "class", type(v)))
      end
      local carried = (v[__K.kind] == "class") and v[__K.classname] or v.__classname
      if carried ~= descriptor then
        host_load_failure("HOST_LOAD_IDENTITY_MISMATCH",
          { name = name, module = module_path, expected = descriptor,
            actual = tostring(carried) }, file, line, column)
      end
      exports[name] = { [__K.kind] = "class", [__K.classname] = descriptor }
      -- <C>_fields is optional: copied through when the raw host table
      -- supplies it (an arm-rendered metadata failure when present but
      -- not a table); absence is tolerated.
      local fields_key = name .. "_fields"
      local fields = raw[fields_key]
      if fields ~= nil then
        if type(fields) ~= "table" then
          host_load_failure("HOST_LOAD_INVALID_METADATA",
            { name = name, module = module_path, ["defaults|fields"] = "fields" },
            file, line, column)
        end
        exports[fields_key] = fields
      end
    else
      error(__rt._err("E8011", "host export '" .. tostring(name) .. "' in module '" .. tostring(module_path) .. "' has an unsupported declared descriptor: " .. tostring(descriptor), file, line, column, nil, nil))
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
  return { [__K.kind] = "async", __co = co, __done = false, __result = nil }
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
  if type(yielded) == "table" and yielded[__K.kind] == "async" then
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

-- ===== Async export invocation (production host ABI, runtime half) =====
--
-- Runtime page D5 / parent D9: the production host half
-- (LuaJitAsyncExportInvoker, owned by the emitter epic) executes the
-- compiled entry artifact under real LuaJIT, runs module initialization
-- and main() exactly once in the same runtime instance, and then calls
-- this entry with the published exports table, the requested export
-- name, and the byte-exact canonical return descriptor. This entry is
-- production, never source-visible and never test-only: generated code
-- never calls it and no artifact exports it.
--
-- Selection: exports must be a table containing exactly one wrapper for
-- exportName whose carried sig is byte-equal to
-- "async()->" .. returnDescriptor (canonical grammar, exact async
-- marker). A missing, sync, parameterized, duplicate,
-- descriptor-mismatched, or non-production export — and any malformed
-- protocol input (non-table exports, non-string export name,
-- non-canonical return descriptor) — raises the host-invocation failure
-- signal below, never a DEAL error code and never a silent wrong result.
-- The host half maps exactly this signal to HostInvocationFailure.
--
-- Invocation: the selected wrapper is called exactly once; the returned
-- async handle is driven through the preserved async_step machinery to
-- completion; the completion value is validated through the canonical
-- matcher (check_canonical_type) against returnDescriptor and returned.
-- A DEAL Error raised by the operation propagates unchanged (code and
-- location intact). Exactly one operation is invoked and completed per
-- call; no retry; the runtime instance is otherwise unchanged.

--- Build the host-invocation failure signal (runtime page D5).
-- Raised for every selection/protocol failure of invoke_async_export.
-- The signal is a table carrying no `code` field and identified by the
-- __hostInvocationFailure marker, so the host half can never confuse it
-- with a DEAL error (which always carries `code`) or with a raw Lua
-- error. The message is a pinned reason string.
function __rt._host_invocation_failure(reason)
  return { __hostInvocationFailure = true, message = reason }
end

--- Invoke one exact async export (production host ABI, runtime half).
-- Contract: "Async export invocation" on the runtime value-model page.
function __rt.invoke_async_export(exports, exportName, returnDescriptor)
  -- Protocol validation: every input is translated to the
  -- host-invocation failure signal; no raw Lua error escapes.
  if type(exports) ~= "table" then
    error(__rt._host_invocation_failure(
        "exports must be a table, got " .. type(exports)))
  end
  if type(exportName) ~= "string" then
    error(__rt._host_invocation_failure(
        "exportName must be a string, got " .. type(exportName)))
  end
  if type(returnDescriptor) ~= "string"
      or parse_descriptor(returnDescriptor) == nil then
    error(__rt._host_invocation_failure(
        "return descriptor is not a canonical descriptor: "
        .. tostring(returnDescriptor)))
  end
  local expected_sig = "async()->" .. returnDescriptor

  -- The raw published entry only: the exports surface is the module's
  -- own table, so no metatable __index participates in selection.
  local entry = rawget(exports, exportName)
  if entry == nil then
    error(__rt._host_invocation_failure(
        "missing export '" .. exportName .. "'"))
  end
  if type(entry) ~= "table" or entry[__K.kind] ~= "function" then
    error(__rt._host_invocation_failure(
        "export '" .. exportName .. "' is not a function wrapper"))
  end

  -- Duplicate: the table must contain the selected wrapper exactly once
  -- (the entry under exportName). The same wrapper published under any
  -- second key is a duplicated export surface and is refused. Generated
  -- modules give every export key its own wrapper table, so this can
  -- never fire for a legitimate compiled artifact.
  local occurrences = 0
  for _, v in pairs(exports) do
    if v == entry then
      occurrences = occurrences + 1
    end
  end
  if occurrences ~= 1 then
    error(__rt._host_invocation_failure(
        "duplicate export '" .. exportName
        .. "': the same wrapper appears under multiple export keys"))
  end

  -- Exact signature selection: sync, parameterized, and
  -- descriptor-mismatched wrappers are each a distinct pinned reason.
  if entry.sig ~= expected_sig then
    local got = tostring(entry.sig or "nil")
    local parsed = parse_descriptor(entry.sig)
    if parsed ~= nil and parsed.kind == "function" and not parsed.isAsync then
      error(__rt._host_invocation_failure(
          "export '" .. exportName .. "' is sync: expected '"
          .. expected_sig .. "', got '" .. got .. "'"))
    elseif parsed ~= nil and parsed.kind == "function"
        and #parsed.params > 0 then
      error(__rt._host_invocation_failure(
          "export '" .. exportName .. "' is parameterized: expected '"
          .. expected_sig .. "', got '" .. got .. "'"))
    else
      error(__rt._host_invocation_failure(
          "export '" .. exportName .. "' signature mismatch: expected '"
          .. expected_sig .. "', got '" .. got .. "'"))
    end
  end

  -- Call the wrapper exactly once. The sig above promised the exact
  -- async()->R; a non-operation result is a non-production wrapper.
  local handle = entry.f()
  if type(handle) ~= "table" or handle[__K.kind] ~= "async" then
    error(__rt._host_invocation_failure(
        "export '" .. exportName .. "' did not produce an async operation"))
  end

  -- Drive the preserved async machinery to completion. DEAL errors
  -- raised inside the operation propagate from async_step unchanged
  -- (code and location intact).
  __rt.async_step(handle)
  if handle.__done ~= true then
    error(__rt._host_invocation_failure(
        "export '" .. exportName
        .. "' async operation did not complete"))
  end

  -- Matcher-validated completion (runtime page D3). A mismatched
  -- completion raises a DEAL error through the canonical checker with
  -- no source span (the host boundary carries no location).
  return __rt.check_canonical_type(returnDescriptor, handle.__result)
end

-- ===== Class infrastructure =====

--- Fail-closed plan-shape validation for class_plan_: the plan is the
-- ordered field-entry list { {name=..., descriptor=..., optional=...}, ... } in
-- class source order. Returns the declared-name set on success, or nil
-- plus a reason on failure. Never throws, never mutates. An entry must be
-- a table with a string name, a string canonical descriptor, a boolean
-- optional flag; duplicate names reject. Retired hooks are never read.
local function plan_declared_names(plan)
  if type(plan) ~= "table" then
    return nil, "class default plan must be a table"
  end
  local count = 0
  for key in next, plan do
    if type(key) ~= "number" or key < 1 or key % 1 ~= 0 then
      return nil, "malformed class default plan entry"
    end
    count = count + 1
  end
  local declared, fields = {}, {}
  for i = 1, count do
    local entry = rawget(plan, i)
    if type(entry) ~= "table" then return nil, "malformed class default plan entry" end
    local name, descriptor, optional = rawget(entry, "name"),
      rawget(entry, "descriptor"), rawget(entry, "optional")
    if type(name) ~= "string" or not __rt.utf8_valid(name)
        or type(descriptor) ~= "string" or not __rt.parse_canonical_descriptor(descriptor)
        or type(optional) ~= "boolean" then
      return nil, "malformed class default plan entry"
    end
    if declared[name] then
      return nil, "duplicate field in class default plan: '" .. name .. "'"
    end
    declared[name] = true
    fields[i] = {name=name, descriptor=descriptor, optional=optional}
  end
  return declared, nil, fields
end

--- Construct from a structural declaration-order field plan.
-- Provided values are already evaluated. Validate unpublished slots before tagging.
function __rt.class_plan_(identity, plan, provided, file, line, column)
  local declared, reason, fields = plan_declared_names(plan)
  if declared == nil then
    error(__rt._err("E8001", reason, file, line, column))
  end
  if provided == nil then provided = {} end
  if type(provided) ~= "table" then
    error(__rt._err("E8001", "class field values must be a table", file, line, column))
  end
  for name in pairs(provided) do
    if not declared[name] then
      error(__rt._err("E8007", "extra field '" .. tostring(name)
        .. "' in class '" .. identity .. "'", file, line, column))
    end
  end
  local instance = {}
  for _, field in ipairs(fields) do
    local value = provided[field.name]
    if value == nil or value == __rt.__MISSING then
      if not field.optional then
        error(__rt._err("E8001", "missing required field '" .. field.name
          .. "' in class '" .. identity .. "'", file, line, column))
      end
    else
      instance[field.name] = __rt.check_canonical_type(field.descriptor,
        value, file, line, column)
    end
  end
  instance[__K.kind] = "class"
  instance[__K.classname] = identity
  local order = {}
  for _, field in ipairs(fields) do order[#order + 1] = field.name end
  instance[__K.classOrder] = order
  instance[__K.classPlan] = fields
  return instance
end

--- Create a class export descriptor for module exports.
function __rt.export_class(name)
  return { [__K.kind] = "class", [__K.classname] = name }
end

-- ===== Intrinsic support =====

--- Test field presence for the has() intrinsic.
-- Returns true if the field value is not nil.
-- Missing optional fields (nil) → false; explicit null (__NULL table) → true.
function __rt.has(obj, field)
  return obj[field] ~= nil
end

return __rt
