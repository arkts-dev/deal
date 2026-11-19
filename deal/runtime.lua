-- DEAL Runtime Library v1.6 (LuaJIT backend)
-- Provides type checks, canonical descriptor parsing and matching, integer
-- arithmetic, class construction, function wrapping,
-- async/await infrastructure and typed host-module loading.
-- Loaded by every generated Lua module via require("deal.runtime").
--
-- All check_* functions accept optional trailing (file, line, column) arguments
-- for source-location tracking in runtime errors. When omitted, these default to nil.

local __rt = {}

-- Whole-string ECMAScript StringToNumber; validate before host conversion.
function __rt.string_to_number(text)
  local units = __rt.string_units(text)
  local function space(u)
    return u and (u >= 9 and u <= 13 or u == 32 or u == 160 or u == 5760
      or u >= 8192 and u <= 8202 or u == 8232 or u == 8233 or u == 8239
      or u == 8287 or u == 12288 or u == 65279)
  end
  local first, last = 1, #units
  while first <= last and space(units[first]) do first = first + 1 end
  while last >= first and space(units[last]) do last = last - 1 end
  local s = __rt.string_from_units(units, first, last)
  if s == "" then return 0 end
  if s == "Infinity" or s == "+Infinity" then return math.huge end
  if s == "-Infinity" then return -math.huge end
  local prefix = s:sub(1,2):lower()
  if prefix == "0x" or prefix == "0b" or prefix == "0o" then
    local bits = prefix == "0x" and 4 or prefix == "0o" and 3 or 1
    local digits, group, count = {"0x"}, 0, (4 - ((#s - 2) * bits) % 4) % 4
    if #s == 2 then return 0/0 end
    for i = 3, #s do
      local digit = ("0123456789abcdef"):find(s:sub(i,i):lower(),1,true)
      if not digit or digit - 1 >= 2^bits then return 0/0 end
      digit = digit - 1
      for b = bits - 1, 0, -1 do
        group = group * 2 + math.floor(digit / 2^b) % 2; count = count + 1
        if count == 4 then digits[#digits+1] = ("0123456789abcdef"):sub(group+1,group+1); group,count = 0,0 end
      end
    end
    return tonumber(table.concat(digits).."p0") or 0/0
  end
  local body = s:gsub("^[+-]", "")
  local mantissa, exponent = body:match("^(.-)[eE]([+-]?%d+)$")
  if not mantissa then mantissa = body end
  if not (mantissa:match("^%d+%.?%d*$") or mantissa:match("^%.%d+$")) then return 0/0 end
  local value = tonumber(s)
  if value == 0 and s:sub(1,1) == "-" then return -1/math.huge end
  return value or 0/0
end

local function exact_decimal(v)
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
  return digits, scale
end

local function decimal_increment(s)
  local i = #s
  while i > 0 and s:sub(i,i) == "9" do i = i - 1 end
  if i == 0 then return "1" .. string.rep("0", #s) end
  return s:sub(1,i-1) .. string.char(string.byte(s,i)+1) .. string.rep("0", #s-i)
end

function __rt.number_fixed(v, places)
  if v ~= v or math.abs(v) >= 1e21 then return __rt.number_text(v) end
  local sign = v < 0 and "-" or ""
  local digits, scale = exact_decimal(math.abs(v))
  local keep = #digits - scale + places
  local rounded = keep > 0 and digits:sub(1,keep) or "0"
  if keep >= #digits then rounded = digits .. string.rep("0", keep-#digits)
  elseif keep >= 0 and digits:sub(keep+1,keep+1) >= "5" then rounded = decimal_increment(rounded) end
  if places == 0 then return sign .. rounded end
  if #rounded <= places then rounded = string.rep("0", places+1-#rounded) .. rounded end
  return sign .. rounded:sub(1,#rounded-places) .. "." .. rounded:sub(#rounded-places+1)
end

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
  local digits, scale = exact_decimal(v)
  local exponent = #digits - scale - 1
  local chosen, power
  for precision = 1, 17 do
    local lower = digits:sub(1,precision)
    if #lower < precision then lower = lower .. string.rep("0", precision-#lower) end
    local nextDigit = digits:sub(precision+1,precision+1)
    local tail = digits:sub(precision+2):find("[1-9]") ~= nil
    local up = nextDigit ~= "" and (nextDigit > "5" or
      (nextDigit == "5" and (tail or tonumber(lower:sub(-1)) % 2 == 1)))
    local upper = decimal_increment(lower)
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
    name = code,
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

function __rt.string_change_case(s, upper)
  if not __rt.case_data then __rt.case_data = __rt.string_case_data() end
  local data = __rt.case_data
  local units, points, out = __rt.string_units(s), {}, {}
  local i = 1
  while i <= #units do
    local cp = units[i]
    if cp >= 55296 and cp <= 56319 and units[i + 1] and units[i + 1] >= 56320 and units[i + 1] <= 57343 then
      cp = 65536 + (cp - 55296) * 1024 + units[i + 1] - 56320
      i = i + 1
    end
    points[#points + 1] = cp
    i = i + 1
  end
  local function property(cp, ranges)
    local low, high = 1, #ranges / 2
    while low <= high do
      local mid = math.floor((low + high) / 2)
      if cp < ranges[2 * mid - 1] then high = mid - 1
      elseif cp > ranges[2 * mid] then low = mid + 1
      else return true end
    end
    return false
  end
  local function append(cp)
    if cp > 65535 then
      cp = cp - 65536
      out[#out + 1] = 55296 + math.floor(cp / 1024)
      out[#out + 1] = 56320 + cp % 1024
    else out[#out + 1] = cp end
  end
  local preceded, mappings = false, upper and data.upper or data.lower
  for at, cp in ipairs(points) do
    local final = false
    if not upper and cp == 931 and preceded then
      local next_at = at + 1
      while points[next_at] and property(points[next_at], data.ignorable) do next_at = next_at + 1 end
      final = not points[next_at] or not property(points[next_at], data.cased)
    end
    local mapped = mappings[cp]
    if final then append(962)
    elseif mapped then for _, value in ipairs(mapped) do append(value) end
    else append(cp) end
    if not property(cp, data.ignorable) then preceded = property(cp, data.cased) end
  end
  return __rt.string_from_units(out)
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
        __rt.check_string(v.name, file, line, column)
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
  "null", "undefined", "unknown", "void", "never", "boolean", "number", "string", "record",
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
local function parse_descriptor(text, result_position)
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
    local ret = parse_descriptor(true)
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

  parse_descriptor = function(allow_void)
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
      if inner.kind == "primitive" and (inner.name == "unknown" or inner.name == "void" or inner.name == "never"
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
        if (kw == "void" or kw == "never") and not allow_void then return nil end
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

  local ast = parse_descriptor(result_position)
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
    elseif name == "never" then
      error(__rt._armErr("TYPED_BOUNDARY_KIND", {kind="never"}, "E8001", "expected never",
        file, line, column, "never", __rt.typed_kind(v)), 0)
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
      __rt.check_string(v.name, file, line, column)
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
  return parse_descriptor(text, true)
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
  local parsed = parse_descriptor(descriptor, true)
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
local function host_arm_failure(id, values, file, line, column, expected, subject, signature, context)
  local render = __rt.__hostArm
  if render == nil then
    error("the host failure-arm renderer is not registered on the deployed "
      .. "runtime (the emitted chunk registers it at load; a producer "
      .. "defect)", 0)
  end
  local failure = render(id, values, file, line, column, expected, subject)
  failure.hostSignature = signature
  failure.hostBoundary = id == "HOST_PARAMETER_CELL" and "parameter"
    or (id == "ASYNC_SHAPE" and "async-shape" or "return")
  if id == "HOST_PARAMETER_CELL" then failure.hostParameter = tostring(values.index) end
  if context ~= nil then
    failure.hostModule = context.module
    failure.hostExport = context.name
    failure.hostDeclaration = context.declaration
  end
  error(failure, 0)
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
function __rt.from_lua_function(sig, raw_f, context)
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
  local render_failure = host_arm_failure
  local function host_arm_failure(id, values, file, line, column, expected, subject)
    return render_failure(id, values, file, line, column, expected, subject, sig, context)
  end

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
    elseif ret_descriptor == "never" then
      return __rt.check_type("never", results[1], file, line, column)
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
function __rt.load_host(module_path, declared, file, line, column, declaration_module)
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
        exports[name] = __rt.from_lua_function(descriptor, v,
          {module = module_path, name = name, declaration = declaration_module})
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
        exports[name] = __rt.from_lua_function(descriptor, v.f,
          {module = module_path, name = name, declaration = declaration_module})
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
      or parse_descriptor(returnDescriptor, true) == nil then
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

-- BEGIN GENERATED UNICODE CASE DATA
-- UNICODE LICENSE V3
-- Copyright © 1991-2026 Unicode, Inc.
--
-- Permission is hereby granted, free of charge, to any person obtaining a
-- copy of data files and any associated documentation (the "Data Files") or
-- software and any associated documentation (the "Software") to deal in the
-- Data Files or Software without restriction, including without limitation
-- the rights to use, copy, modify, merge, publish, distribute, and/or sell
-- copies of the Data Files or Software, and to permit persons to whom the
-- Data Files or Software are furnished to do so, provided that either (a)
-- this copyright and permission notice appear with all copies of the Data
-- Files or Software, or (b) this copyright and permission notice appear in
-- associated Documentation.
--
-- THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
-- KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
-- MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
-- THIRD PARTY RIGHTS.
--
-- IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
-- BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
-- OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
-- WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
-- ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
-- FILES OR SOFTWARE.
--
-- Except as contained in this notice, the name of a copyright holder shall
-- not be used in advertising or otherwise to promote the sale, use or other
-- dealings in these Data Files or Software without prior written
-- authorization of the copyright holder.
function __rt.string_case_data()
-- Generated Unicode 17.0 default case data; tools/generate-unicode-case.py.
-- Unicode data: https://www.unicode.org/license.txt
local data = {}
data.lower = {
  [65]={97},
  [66]={98},
  [67]={99},
  [68]={100},
  [69]={101},
  [70]={102},
  [71]={103},
  [72]={104},
  [73]={105},
  [74]={106},
  [75]={107},
  [76]={108},
  [77]={109},
  [78]={110},
  [79]={111},
  [80]={112},
  [81]={113},
  [82]={114},
  [83]={115},
  [84]={116},
  [85]={117},
  [86]={118},
  [87]={119},
  [88]={120},
  [89]={121},
  [90]={122},
  [192]={224},
  [193]={225},
  [194]={226},
  [195]={227},
  [196]={228},
  [197]={229},
  [198]={230},
  [199]={231},
  [200]={232},
  [201]={233},
  [202]={234},
  [203]={235},
  [204]={236},
  [205]={237},
  [206]={238},
  [207]={239},
  [208]={240},
  [209]={241},
  [210]={242},
  [211]={243},
  [212]={244},
  [213]={245},
  [214]={246},
  [216]={248},
  [217]={249},
  [218]={250},
  [219]={251},
  [220]={252},
  [221]={253},
  [222]={254},
  [256]={257},
  [258]={259},
  [260]={261},
  [262]={263},
  [264]={265},
  [266]={267},
  [268]={269},
  [270]={271},
  [272]={273},
  [274]={275},
  [276]={277},
  [278]={279},
  [280]={281},
  [282]={283},
  [284]={285},
  [286]={287},
  [288]={289},
  [290]={291},
  [292]={293},
  [294]={295},
  [296]={297},
  [298]={299},
  [300]={301},
  [302]={303},
  [304]={105,775},
  [306]={307},
  [308]={309},
  [310]={311},
  [313]={314},
  [315]={316},
  [317]={318},
  [319]={320},
  [321]={322},
  [323]={324},
  [325]={326},
  [327]={328},
  [330]={331},
  [332]={333},
  [334]={335},
  [336]={337},
  [338]={339},
  [340]={341},
  [342]={343},
  [344]={345},
  [346]={347},
  [348]={349},
  [350]={351},
  [352]={353},
  [354]={355},
  [356]={357},
  [358]={359},
  [360]={361},
  [362]={363},
  [364]={365},
  [366]={367},
  [368]={369},
  [370]={371},
  [372]={373},
  [374]={375},
  [376]={255},
  [377]={378},
  [379]={380},
  [381]={382},
  [385]={595},
  [386]={387},
  [388]={389},
  [390]={596},
  [391]={392},
  [393]={598},
  [394]={599},
  [395]={396},
  [398]={477},
  [399]={601},
  [400]={603},
  [401]={402},
  [403]={608},
  [404]={611},
  [406]={617},
  [407]={616},
  [408]={409},
  [412]={623},
  [413]={626},
  [415]={629},
  [416]={417},
  [418]={419},
  [420]={421},
  [422]={640},
  [423]={424},
  [425]={643},
  [428]={429},
  [430]={648},
  [431]={432},
  [433]={650},
  [434]={651},
  [435]={436},
  [437]={438},
  [439]={658},
  [440]={441},
  [444]={445},
  [452]={454},
  [453]={454},
  [455]={457},
  [456]={457},
  [458]={460},
  [459]={460},
  [461]={462},
  [463]={464},
  [465]={466},
  [467]={468},
  [469]={470},
  [471]={472},
  [473]={474},
  [475]={476},
  [478]={479},
  [480]={481},
  [482]={483},
  [484]={485},
  [486]={487},
  [488]={489},
  [490]={491},
  [492]={493},
  [494]={495},
  [497]={499},
  [498]={499},
  [500]={501},
  [502]={405},
  [503]={447},
  [504]={505},
  [506]={507},
  [508]={509},
  [510]={511},
  [512]={513},
  [514]={515},
  [516]={517},
  [518]={519},
  [520]={521},
  [522]={523},
  [524]={525},
  [526]={527},
  [528]={529},
  [530]={531},
  [532]={533},
  [534]={535},
  [536]={537},
  [538]={539},
  [540]={541},
  [542]={543},
  [544]={414},
  [546]={547},
  [548]={549},
  [550]={551},
  [552]={553},
  [554]={555},
  [556]={557},
  [558]={559},
  [560]={561},
  [562]={563},
  [570]={11365},
  [571]={572},
  [573]={410},
  [574]={11366},
  [577]={578},
  [579]={384},
  [580]={649},
  [581]={652},
  [582]={583},
  [584]={585},
  [586]={587},
  [588]={589},
  [590]={591},
  [880]={881},
  [882]={883},
  [886]={887},
  [895]={1011},
  [902]={940},
  [904]={941},
  [905]={942},
  [906]={943},
  [908]={972},
  [910]={973},
  [911]={974},
  [913]={945},
  [914]={946},
  [915]={947},
  [916]={948},
  [917]={949},
  [918]={950},
  [919]={951},
  [920]={952},
  [921]={953},
  [922]={954},
  [923]={955},
  [924]={956},
  [925]={957},
  [926]={958},
  [927]={959},
  [928]={960},
  [929]={961},
  [931]={963},
  [932]={964},
  [933]={965},
  [934]={966},
  [935]={967},
  [936]={968},
  [937]={969},
  [938]={970},
  [939]={971},
  [975]={983},
  [984]={985},
  [986]={987},
  [988]={989},
  [990]={991},
  [992]={993},
  [994]={995},
  [996]={997},
  [998]={999},
  [1000]={1001},
  [1002]={1003},
  [1004]={1005},
  [1006]={1007},
  [1012]={952},
  [1015]={1016},
  [1017]={1010},
  [1018]={1019},
  [1021]={891},
  [1022]={892},
  [1023]={893},
  [1024]={1104},
  [1025]={1105},
  [1026]={1106},
  [1027]={1107},
  [1028]={1108},
  [1029]={1109},
  [1030]={1110},
  [1031]={1111},
  [1032]={1112},
  [1033]={1113},
  [1034]={1114},
  [1035]={1115},
  [1036]={1116},
  [1037]={1117},
  [1038]={1118},
  [1039]={1119},
  [1040]={1072},
  [1041]={1073},
  [1042]={1074},
  [1043]={1075},
  [1044]={1076},
  [1045]={1077},
  [1046]={1078},
  [1047]={1079},
  [1048]={1080},
  [1049]={1081},
  [1050]={1082},
  [1051]={1083},
  [1052]={1084},
  [1053]={1085},
  [1054]={1086},
  [1055]={1087},
  [1056]={1088},
  [1057]={1089},
  [1058]={1090},
  [1059]={1091},
  [1060]={1092},
  [1061]={1093},
  [1062]={1094},
  [1063]={1095},
  [1064]={1096},
  [1065]={1097},
  [1066]={1098},
  [1067]={1099},
  [1068]={1100},
  [1069]={1101},
  [1070]={1102},
  [1071]={1103},
  [1120]={1121},
  [1122]={1123},
  [1124]={1125},
  [1126]={1127},
  [1128]={1129},
  [1130]={1131},
  [1132]={1133},
  [1134]={1135},
  [1136]={1137},
  [1138]={1139},
  [1140]={1141},
  [1142]={1143},
  [1144]={1145},
  [1146]={1147},
  [1148]={1149},
  [1150]={1151},
  [1152]={1153},
  [1162]={1163},
  [1164]={1165},
  [1166]={1167},
  [1168]={1169},
  [1170]={1171},
  [1172]={1173},
  [1174]={1175},
  [1176]={1177},
  [1178]={1179},
  [1180]={1181},
  [1182]={1183},
  [1184]={1185},
  [1186]={1187},
  [1188]={1189},
  [1190]={1191},
  [1192]={1193},
  [1194]={1195},
  [1196]={1197},
  [1198]={1199},
  [1200]={1201},
  [1202]={1203},
  [1204]={1205},
  [1206]={1207},
  [1208]={1209},
  [1210]={1211},
  [1212]={1213},
  [1214]={1215},
  [1216]={1231},
  [1217]={1218},
  [1219]={1220},
  [1221]={1222},
  [1223]={1224},
  [1225]={1226},
  [1227]={1228},
  [1229]={1230},
  [1232]={1233},
  [1234]={1235},
  [1236]={1237},
  [1238]={1239},
  [1240]={1241},
  [1242]={1243},
  [1244]={1245},
  [1246]={1247},
  [1248]={1249},
  [1250]={1251},
  [1252]={1253},
  [1254]={1255},
  [1256]={1257},
  [1258]={1259},
  [1260]={1261},
  [1262]={1263},
  [1264]={1265},
  [1266]={1267},
  [1268]={1269},
  [1270]={1271},
  [1272]={1273},
  [1274]={1275},
  [1276]={1277},
  [1278]={1279},
  [1280]={1281},
  [1282]={1283},
  [1284]={1285},
  [1286]={1287},
  [1288]={1289},
  [1290]={1291},
  [1292]={1293},
  [1294]={1295},
  [1296]={1297},
  [1298]={1299},
  [1300]={1301},
  [1302]={1303},
  [1304]={1305},
  [1306]={1307},
  [1308]={1309},
  [1310]={1311},
  [1312]={1313},
  [1314]={1315},
  [1316]={1317},
  [1318]={1319},
  [1320]={1321},
  [1322]={1323},
  [1324]={1325},
  [1326]={1327},
  [1329]={1377},
  [1330]={1378},
  [1331]={1379},
  [1332]={1380},
  [1333]={1381},
  [1334]={1382},
  [1335]={1383},
  [1336]={1384},
  [1337]={1385},
  [1338]={1386},
  [1339]={1387},
  [1340]={1388},
  [1341]={1389},
  [1342]={1390},
  [1343]={1391},
  [1344]={1392},
  [1345]={1393},
  [1346]={1394},
  [1347]={1395},
  [1348]={1396},
  [1349]={1397},
  [1350]={1398},
  [1351]={1399},
  [1352]={1400},
  [1353]={1401},
  [1354]={1402},
  [1355]={1403},
  [1356]={1404},
  [1357]={1405},
  [1358]={1406},
  [1359]={1407},
  [1360]={1408},
  [1361]={1409},
  [1362]={1410},
  [1363]={1411},
  [1364]={1412},
  [1365]={1413},
  [1366]={1414},
  [4256]={11520},
  [4257]={11521},
  [4258]={11522},
  [4259]={11523},
  [4260]={11524},
  [4261]={11525},
  [4262]={11526},
  [4263]={11527},
  [4264]={11528},
  [4265]={11529},
  [4266]={11530},
  [4267]={11531},
  [4268]={11532},
  [4269]={11533},
  [4270]={11534},
  [4271]={11535},
  [4272]={11536},
  [4273]={11537},
  [4274]={11538},
  [4275]={11539},
  [4276]={11540},
  [4277]={11541},
  [4278]={11542},
  [4279]={11543},
  [4280]={11544},
  [4281]={11545},
  [4282]={11546},
  [4283]={11547},
  [4284]={11548},
  [4285]={11549},
  [4286]={11550},
  [4287]={11551},
  [4288]={11552},
  [4289]={11553},
  [4290]={11554},
  [4291]={11555},
  [4292]={11556},
  [4293]={11557},
  [4295]={11559},
  [4301]={11565},
  [5024]={43888},
  [5025]={43889},
  [5026]={43890},
  [5027]={43891},
  [5028]={43892},
  [5029]={43893},
  [5030]={43894},
  [5031]={43895},
  [5032]={43896},
  [5033]={43897},
  [5034]={43898},
  [5035]={43899},
  [5036]={43900},
  [5037]={43901},
  [5038]={43902},
  [5039]={43903},
  [5040]={43904},
  [5041]={43905},
  [5042]={43906},
  [5043]={43907},
  [5044]={43908},
  [5045]={43909},
  [5046]={43910},
  [5047]={43911},
  [5048]={43912},
  [5049]={43913},
  [5050]={43914},
  [5051]={43915},
  [5052]={43916},
  [5053]={43917},
  [5054]={43918},
  [5055]={43919},
  [5056]={43920},
  [5057]={43921},
  [5058]={43922},
  [5059]={43923},
  [5060]={43924},
  [5061]={43925},
  [5062]={43926},
  [5063]={43927},
  [5064]={43928},
  [5065]={43929},
  [5066]={43930},
  [5067]={43931},
  [5068]={43932},
  [5069]={43933},
  [5070]={43934},
  [5071]={43935},
  [5072]={43936},
  [5073]={43937},
  [5074]={43938},
  [5075]={43939},
  [5076]={43940},
  [5077]={43941},
  [5078]={43942},
  [5079]={43943},
  [5080]={43944},
  [5081]={43945},
  [5082]={43946},
  [5083]={43947},
  [5084]={43948},
  [5085]={43949},
  [5086]={43950},
  [5087]={43951},
  [5088]={43952},
  [5089]={43953},
  [5090]={43954},
  [5091]={43955},
  [5092]={43956},
  [5093]={43957},
  [5094]={43958},
  [5095]={43959},
  [5096]={43960},
  [5097]={43961},
  [5098]={43962},
  [5099]={43963},
  [5100]={43964},
  [5101]={43965},
  [5102]={43966},
  [5103]={43967},
  [5104]={5112},
  [5105]={5113},
  [5106]={5114},
  [5107]={5115},
  [5108]={5116},
  [5109]={5117},
  [7305]={7306},
  [7312]={4304},
  [7313]={4305},
  [7314]={4306},
  [7315]={4307},
  [7316]={4308},
  [7317]={4309},
  [7318]={4310},
  [7319]={4311},
  [7320]={4312},
  [7321]={4313},
  [7322]={4314},
  [7323]={4315},
  [7324]={4316},
  [7325]={4317},
  [7326]={4318},
  [7327]={4319},
  [7328]={4320},
  [7329]={4321},
  [7330]={4322},
  [7331]={4323},
  [7332]={4324},
  [7333]={4325},
  [7334]={4326},
  [7335]={4327},
  [7336]={4328},
  [7337]={4329},
  [7338]={4330},
  [7339]={4331},
  [7340]={4332},
  [7341]={4333},
  [7342]={4334},
  [7343]={4335},
  [7344]={4336},
  [7345]={4337},
  [7346]={4338},
  [7347]={4339},
  [7348]={4340},
  [7349]={4341},
  [7350]={4342},
  [7351]={4343},
  [7352]={4344},
  [7353]={4345},
  [7354]={4346},
  [7357]={4349},
  [7358]={4350},
  [7359]={4351},
  [7680]={7681},
  [7682]={7683},
  [7684]={7685},
  [7686]={7687},
  [7688]={7689},
  [7690]={7691},
  [7692]={7693},
  [7694]={7695},
  [7696]={7697},
  [7698]={7699},
  [7700]={7701},
  [7702]={7703},
  [7704]={7705},
  [7706]={7707},
  [7708]={7709},
  [7710]={7711},
  [7712]={7713},
  [7714]={7715},
  [7716]={7717},
  [7718]={7719},
  [7720]={7721},
  [7722]={7723},
  [7724]={7725},
  [7726]={7727},
  [7728]={7729},
  [7730]={7731},
  [7732]={7733},
  [7734]={7735},
  [7736]={7737},
  [7738]={7739},
  [7740]={7741},
  [7742]={7743},
  [7744]={7745},
  [7746]={7747},
  [7748]={7749},
  [7750]={7751},
  [7752]={7753},
  [7754]={7755},
  [7756]={7757},
  [7758]={7759},
  [7760]={7761},
  [7762]={7763},
  [7764]={7765},
  [7766]={7767},
  [7768]={7769},
  [7770]={7771},
  [7772]={7773},
  [7774]={7775},
  [7776]={7777},
  [7778]={7779},
  [7780]={7781},
  [7782]={7783},
  [7784]={7785},
  [7786]={7787},
  [7788]={7789},
  [7790]={7791},
  [7792]={7793},
  [7794]={7795},
  [7796]={7797},
  [7798]={7799},
  [7800]={7801},
  [7802]={7803},
  [7804]={7805},
  [7806]={7807},
  [7808]={7809},
  [7810]={7811},
  [7812]={7813},
  [7814]={7815},
  [7816]={7817},
  [7818]={7819},
  [7820]={7821},
  [7822]={7823},
  [7824]={7825},
  [7826]={7827},
  [7828]={7829},
  [7838]={223},
  [7840]={7841},
  [7842]={7843},
  [7844]={7845},
  [7846]={7847},
  [7848]={7849},
  [7850]={7851},
  [7852]={7853},
  [7854]={7855},
  [7856]={7857},
  [7858]={7859},
  [7860]={7861},
  [7862]={7863},
  [7864]={7865},
  [7866]={7867},
  [7868]={7869},
  [7870]={7871},
  [7872]={7873},
  [7874]={7875},
  [7876]={7877},
  [7878]={7879},
  [7880]={7881},
  [7882]={7883},
  [7884]={7885},
  [7886]={7887},
  [7888]={7889},
  [7890]={7891},
  [7892]={7893},
  [7894]={7895},
  [7896]={7897},
  [7898]={7899},
  [7900]={7901},
  [7902]={7903},
  [7904]={7905},
  [7906]={7907},
  [7908]={7909},
  [7910]={7911},
  [7912]={7913},
  [7914]={7915},
  [7916]={7917},
  [7918]={7919},
  [7920]={7921},
  [7922]={7923},
  [7924]={7925},
  [7926]={7927},
  [7928]={7929},
  [7930]={7931},
  [7932]={7933},
  [7934]={7935},
  [7944]={7936},
  [7945]={7937},
  [7946]={7938},
  [7947]={7939},
  [7948]={7940},
  [7949]={7941},
  [7950]={7942},
  [7951]={7943},
  [7960]={7952},
  [7961]={7953},
  [7962]={7954},
  [7963]={7955},
  [7964]={7956},
  [7965]={7957},
  [7976]={7968},
  [7977]={7969},
  [7978]={7970},
  [7979]={7971},
  [7980]={7972},
  [7981]={7973},
  [7982]={7974},
  [7983]={7975},
  [7992]={7984},
  [7993]={7985},
  [7994]={7986},
  [7995]={7987},
  [7996]={7988},
  [7997]={7989},
  [7998]={7990},
  [7999]={7991},
  [8008]={8000},
  [8009]={8001},
  [8010]={8002},
  [8011]={8003},
  [8012]={8004},
  [8013]={8005},
  [8025]={8017},
  [8027]={8019},
  [8029]={8021},
  [8031]={8023},
  [8040]={8032},
  [8041]={8033},
  [8042]={8034},
  [8043]={8035},
  [8044]={8036},
  [8045]={8037},
  [8046]={8038},
  [8047]={8039},
  [8072]={8064},
  [8073]={8065},
  [8074]={8066},
  [8075]={8067},
  [8076]={8068},
  [8077]={8069},
  [8078]={8070},
  [8079]={8071},
  [8088]={8080},
  [8089]={8081},
  [8090]={8082},
  [8091]={8083},
  [8092]={8084},
  [8093]={8085},
  [8094]={8086},
  [8095]={8087},
  [8104]={8096},
  [8105]={8097},
  [8106]={8098},
  [8107]={8099},
  [8108]={8100},
  [8109]={8101},
  [8110]={8102},
  [8111]={8103},
  [8120]={8112},
  [8121]={8113},
  [8122]={8048},
  [8123]={8049},
  [8124]={8115},
  [8136]={8050},
  [8137]={8051},
  [8138]={8052},
  [8139]={8053},
  [8140]={8131},
  [8152]={8144},
  [8153]={8145},
  [8154]={8054},
  [8155]={8055},
  [8168]={8160},
  [8169]={8161},
  [8170]={8058},
  [8171]={8059},
  [8172]={8165},
  [8184]={8056},
  [8185]={8057},
  [8186]={8060},
  [8187]={8061},
  [8188]={8179},
  [8486]={969},
  [8490]={107},
  [8491]={229},
  [8498]={8526},
  [8544]={8560},
  [8545]={8561},
  [8546]={8562},
  [8547]={8563},
  [8548]={8564},
  [8549]={8565},
  [8550]={8566},
  [8551]={8567},
  [8552]={8568},
  [8553]={8569},
  [8554]={8570},
  [8555]={8571},
  [8556]={8572},
  [8557]={8573},
  [8558]={8574},
  [8559]={8575},
  [8579]={8580},
  [9398]={9424},
  [9399]={9425},
  [9400]={9426},
  [9401]={9427},
  [9402]={9428},
  [9403]={9429},
  [9404]={9430},
  [9405]={9431},
  [9406]={9432},
  [9407]={9433},
  [9408]={9434},
  [9409]={9435},
  [9410]={9436},
  [9411]={9437},
  [9412]={9438},
  [9413]={9439},
  [9414]={9440},
  [9415]={9441},
  [9416]={9442},
  [9417]={9443},
  [9418]={9444},
  [9419]={9445},
  [9420]={9446},
  [9421]={9447},
  [9422]={9448},
  [9423]={9449},
  [11264]={11312},
  [11265]={11313},
  [11266]={11314},
  [11267]={11315},
  [11268]={11316},
  [11269]={11317},
  [11270]={11318},
  [11271]={11319},
  [11272]={11320},
  [11273]={11321},
  [11274]={11322},
  [11275]={11323},
  [11276]={11324},
  [11277]={11325},
  [11278]={11326},
  [11279]={11327},
  [11280]={11328},
  [11281]={11329},
  [11282]={11330},
  [11283]={11331},
  [11284]={11332},
  [11285]={11333},
  [11286]={11334},
  [11287]={11335},
  [11288]={11336},
  [11289]={11337},
  [11290]={11338},
  [11291]={11339},
  [11292]={11340},
  [11293]={11341},
  [11294]={11342},
  [11295]={11343},
  [11296]={11344},
  [11297]={11345},
  [11298]={11346},
  [11299]={11347},
  [11300]={11348},
  [11301]={11349},
  [11302]={11350},
  [11303]={11351},
  [11304]={11352},
  [11305]={11353},
  [11306]={11354},
  [11307]={11355},
  [11308]={11356},
  [11309]={11357},
  [11310]={11358},
  [11311]={11359},
  [11360]={11361},
  [11362]={619},
  [11363]={7549},
  [11364]={637},
  [11367]={11368},
  [11369]={11370},
  [11371]={11372},
  [11373]={593},
  [11374]={625},
  [11375]={592},
  [11376]={594},
  [11378]={11379},
  [11381]={11382},
  [11390]={575},
  [11391]={576},
  [11392]={11393},
  [11394]={11395},
  [11396]={11397},
  [11398]={11399},
  [11400]={11401},
  [11402]={11403},
  [11404]={11405},
  [11406]={11407},
  [11408]={11409},
  [11410]={11411},
  [11412]={11413},
  [11414]={11415},
  [11416]={11417},
  [11418]={11419},
  [11420]={11421},
  [11422]={11423},
  [11424]={11425},
  [11426]={11427},
  [11428]={11429},
  [11430]={11431},
  [11432]={11433},
  [11434]={11435},
  [11436]={11437},
  [11438]={11439},
  [11440]={11441},
  [11442]={11443},
  [11444]={11445},
  [11446]={11447},
  [11448]={11449},
  [11450]={11451},
  [11452]={11453},
  [11454]={11455},
  [11456]={11457},
  [11458]={11459},
  [11460]={11461},
  [11462]={11463},
  [11464]={11465},
  [11466]={11467},
  [11468]={11469},
  [11470]={11471},
  [11472]={11473},
  [11474]={11475},
  [11476]={11477},
  [11478]={11479},
  [11480]={11481},
  [11482]={11483},
  [11484]={11485},
  [11486]={11487},
  [11488]={11489},
  [11490]={11491},
  [11499]={11500},
  [11501]={11502},
  [11506]={11507},
  [42560]={42561},
  [42562]={42563},
  [42564]={42565},
  [42566]={42567},
  [42568]={42569},
  [42570]={42571},
  [42572]={42573},
  [42574]={42575},
  [42576]={42577},
  [42578]={42579},
  [42580]={42581},
  [42582]={42583},
  [42584]={42585},
  [42586]={42587},
  [42588]={42589},
  [42590]={42591},
  [42592]={42593},
  [42594]={42595},
  [42596]={42597},
  [42598]={42599},
  [42600]={42601},
  [42602]={42603},
  [42604]={42605},
  [42624]={42625},
  [42626]={42627},
  [42628]={42629},
  [42630]={42631},
  [42632]={42633},
  [42634]={42635},
  [42636]={42637},
  [42638]={42639},
  [42640]={42641},
  [42642]={42643},
  [42644]={42645},
  [42646]={42647},
  [42648]={42649},
  [42650]={42651},
  [42786]={42787},
  [42788]={42789},
  [42790]={42791},
  [42792]={42793},
  [42794]={42795},
  [42796]={42797},
  [42798]={42799},
  [42802]={42803},
  [42804]={42805},
  [42806]={42807},
  [42808]={42809},
  [42810]={42811},
  [42812]={42813},
  [42814]={42815},
  [42816]={42817},
  [42818]={42819},
  [42820]={42821},
  [42822]={42823},
  [42824]={42825},
  [42826]={42827},
  [42828]={42829},
  [42830]={42831},
  [42832]={42833},
  [42834]={42835},
  [42836]={42837},
  [42838]={42839},
  [42840]={42841},
  [42842]={42843},
  [42844]={42845},
  [42846]={42847},
  [42848]={42849},
  [42850]={42851},
  [42852]={42853},
  [42854]={42855},
  [42856]={42857},
  [42858]={42859},
  [42860]={42861},
  [42862]={42863},
  [42873]={42874},
  [42875]={42876},
  [42877]={7545},
  [42878]={42879},
  [42880]={42881},
  [42882]={42883},
  [42884]={42885},
  [42886]={42887},
  [42891]={42892},
  [42893]={613},
  [42896]={42897},
  [42898]={42899},
  [42902]={42903},
  [42904]={42905},
  [42906]={42907},
  [42908]={42909},
  [42910]={42911},
  [42912]={42913},
  [42914]={42915},
  [42916]={42917},
  [42918]={42919},
  [42920]={42921},
  [42922]={614},
  [42923]={604},
  [42924]={609},
  [42925]={620},
  [42926]={618},
  [42928]={670},
  [42929]={647},
  [42930]={669},
  [42931]={43859},
  [42932]={42933},
  [42934]={42935},
  [42936]={42937},
  [42938]={42939},
  [42940]={42941},
  [42942]={42943},
  [42944]={42945},
  [42946]={42947},
  [42948]={42900},
  [42949]={642},
  [42950]={7566},
  [42951]={42952},
  [42953]={42954},
  [42955]={612},
  [42956]={42957},
  [42958]={42959},
  [42960]={42961},
  [42962]={42963},
  [42964]={42965},
  [42966]={42967},
  [42968]={42969},
  [42970]={42971},
  [42972]={411},
  [42997]={42998},
  [65313]={65345},
  [65314]={65346},
  [65315]={65347},
  [65316]={65348},
  [65317]={65349},
  [65318]={65350},
  [65319]={65351},
  [65320]={65352},
  [65321]={65353},
  [65322]={65354},
  [65323]={65355},
  [65324]={65356},
  [65325]={65357},
  [65326]={65358},
  [65327]={65359},
  [65328]={65360},
  [65329]={65361},
  [65330]={65362},
  [65331]={65363},
  [65332]={65364},
  [65333]={65365},
  [65334]={65366},
  [65335]={65367},
  [65336]={65368},
  [65337]={65369},
  [65338]={65370},
  [66560]={66600},
  [66561]={66601},
  [66562]={66602},
  [66563]={66603},
  [66564]={66604},
  [66565]={66605},
  [66566]={66606},
  [66567]={66607},
  [66568]={66608},
  [66569]={66609},
  [66570]={66610},
  [66571]={66611},
  [66572]={66612},
  [66573]={66613},
  [66574]={66614},
  [66575]={66615},
  [66576]={66616},
  [66577]={66617},
  [66578]={66618},
  [66579]={66619},
  [66580]={66620},
  [66581]={66621},
  [66582]={66622},
  [66583]={66623},
  [66584]={66624},
  [66585]={66625},
  [66586]={66626},
  [66587]={66627},
  [66588]={66628},
  [66589]={66629},
  [66590]={66630},
  [66591]={66631},
  [66592]={66632},
  [66593]={66633},
  [66594]={66634},
  [66595]={66635},
  [66596]={66636},
  [66597]={66637},
  [66598]={66638},
  [66599]={66639},
  [66736]={66776},
  [66737]={66777},
  [66738]={66778},
  [66739]={66779},
  [66740]={66780},
  [66741]={66781},
  [66742]={66782},
  [66743]={66783},
  [66744]={66784},
  [66745]={66785},
  [66746]={66786},
  [66747]={66787},
  [66748]={66788},
  [66749]={66789},
  [66750]={66790},
  [66751]={66791},
  [66752]={66792},
  [66753]={66793},
  [66754]={66794},
  [66755]={66795},
  [66756]={66796},
  [66757]={66797},
  [66758]={66798},
  [66759]={66799},
  [66760]={66800},
  [66761]={66801},
  [66762]={66802},
  [66763]={66803},
  [66764]={66804},
  [66765]={66805},
  [66766]={66806},
  [66767]={66807},
  [66768]={66808},
  [66769]={66809},
  [66770]={66810},
  [66771]={66811},
  [66928]={66967},
  [66929]={66968},
  [66930]={66969},
  [66931]={66970},
  [66932]={66971},
  [66933]={66972},
  [66934]={66973},
  [66935]={66974},
  [66936]={66975},
  [66937]={66976},
  [66938]={66977},
  [66940]={66979},
  [66941]={66980},
  [66942]={66981},
  [66943]={66982},
  [66944]={66983},
  [66945]={66984},
  [66946]={66985},
  [66947]={66986},
  [66948]={66987},
  [66949]={66988},
  [66950]={66989},
  [66951]={66990},
  [66952]={66991},
  [66953]={66992},
  [66954]={66993},
  [66956]={66995},
  [66957]={66996},
  [66958]={66997},
  [66959]={66998},
  [66960]={66999},
  [66961]={67000},
  [66962]={67001},
  [66964]={67003},
  [66965]={67004},
  [68736]={68800},
  [68737]={68801},
  [68738]={68802},
  [68739]={68803},
  [68740]={68804},
  [68741]={68805},
  [68742]={68806},
  [68743]={68807},
  [68744]={68808},
  [68745]={68809},
  [68746]={68810},
  [68747]={68811},
  [68748]={68812},
  [68749]={68813},
  [68750]={68814},
  [68751]={68815},
  [68752]={68816},
  [68753]={68817},
  [68754]={68818},
  [68755]={68819},
  [68756]={68820},
  [68757]={68821},
  [68758]={68822},
  [68759]={68823},
  [68760]={68824},
  [68761]={68825},
  [68762]={68826},
  [68763]={68827},
  [68764]={68828},
  [68765]={68829},
  [68766]={68830},
  [68767]={68831},
  [68768]={68832},
  [68769]={68833},
  [68770]={68834},
  [68771]={68835},
  [68772]={68836},
  [68773]={68837},
  [68774]={68838},
  [68775]={68839},
  [68776]={68840},
  [68777]={68841},
  [68778]={68842},
  [68779]={68843},
  [68780]={68844},
  [68781]={68845},
  [68782]={68846},
  [68783]={68847},
  [68784]={68848},
  [68785]={68849},
  [68786]={68850},
  [68944]={68976},
  [68945]={68977},
  [68946]={68978},
  [68947]={68979},
  [68948]={68980},
  [68949]={68981},
  [68950]={68982},
  [68951]={68983},
  [68952]={68984},
  [68953]={68985},
  [68954]={68986},
  [68955]={68987},
  [68956]={68988},
  [68957]={68989},
  [68958]={68990},
  [68959]={68991},
  [68960]={68992},
  [68961]={68993},
  [68962]={68994},
  [68963]={68995},
  [68964]={68996},
  [68965]={68997},
  [71840]={71872},
  [71841]={71873},
  [71842]={71874},
  [71843]={71875},
  [71844]={71876},
  [71845]={71877},
  [71846]={71878},
  [71847]={71879},
  [71848]={71880},
  [71849]={71881},
  [71850]={71882},
  [71851]={71883},
  [71852]={71884},
  [71853]={71885},
  [71854]={71886},
  [71855]={71887},
  [71856]={71888},
  [71857]={71889},
  [71858]={71890},
  [71859]={71891},
  [71860]={71892},
  [71861]={71893},
  [71862]={71894},
  [71863]={71895},
  [71864]={71896},
  [71865]={71897},
  [71866]={71898},
  [71867]={71899},
  [71868]={71900},
  [71869]={71901},
  [71870]={71902},
  [71871]={71903},
  [93760]={93792},
  [93761]={93793},
  [93762]={93794},
  [93763]={93795},
  [93764]={93796},
  [93765]={93797},
  [93766]={93798},
  [93767]={93799},
  [93768]={93800},
  [93769]={93801},
  [93770]={93802},
  [93771]={93803},
  [93772]={93804},
  [93773]={93805},
  [93774]={93806},
  [93775]={93807},
  [93776]={93808},
  [93777]={93809},
  [93778]={93810},
  [93779]={93811},
  [93780]={93812},
  [93781]={93813},
  [93782]={93814},
  [93783]={93815},
  [93784]={93816},
  [93785]={93817},
  [93786]={93818},
  [93787]={93819},
  [93788]={93820},
  [93789]={93821},
  [93790]={93822},
  [93791]={93823},
  [93856]={93883},
  [93857]={93884},
  [93858]={93885},
  [93859]={93886},
  [93860]={93887},
  [93861]={93888},
  [93862]={93889},
  [93863]={93890},
  [93864]={93891},
  [93865]={93892},
  [93866]={93893},
  [93867]={93894},
  [93868]={93895},
  [93869]={93896},
  [93870]={93897},
  [93871]={93898},
  [93872]={93899},
  [93873]={93900},
  [93874]={93901},
  [93875]={93902},
  [93876]={93903},
  [93877]={93904},
  [93878]={93905},
  [93879]={93906},
  [93880]={93907},
  [125184]={125218},
  [125185]={125219},
  [125186]={125220},
  [125187]={125221},
  [125188]={125222},
  [125189]={125223},
  [125190]={125224},
  [125191]={125225},
  [125192]={125226},
  [125193]={125227},
  [125194]={125228},
  [125195]={125229},
  [125196]={125230},
  [125197]={125231},
  [125198]={125232},
  [125199]={125233},
  [125200]={125234},
  [125201]={125235},
  [125202]={125236},
  [125203]={125237},
  [125204]={125238},
  [125205]={125239},
  [125206]={125240},
  [125207]={125241},
  [125208]={125242},
  [125209]={125243},
  [125210]={125244},
  [125211]={125245},
  [125212]={125246},
  [125213]={125247},
  [125214]={125248},
  [125215]={125249},
  [125216]={125250},
  [125217]={125251},
}
data.upper = {
  [97]={65},
  [98]={66},
  [99]={67},
  [100]={68},
  [101]={69},
  [102]={70},
  [103]={71},
  [104]={72},
  [105]={73},
  [106]={74},
  [107]={75},
  [108]={76},
  [109]={77},
  [110]={78},
  [111]={79},
  [112]={80},
  [113]={81},
  [114]={82},
  [115]={83},
  [116]={84},
  [117]={85},
  [118]={86},
  [119]={87},
  [120]={88},
  [121]={89},
  [122]={90},
  [181]={924},
  [223]={83,83},
  [224]={192},
  [225]={193},
  [226]={194},
  [227]={195},
  [228]={196},
  [229]={197},
  [230]={198},
  [231]={199},
  [232]={200},
  [233]={201},
  [234]={202},
  [235]={203},
  [236]={204},
  [237]={205},
  [238]={206},
  [239]={207},
  [240]={208},
  [241]={209},
  [242]={210},
  [243]={211},
  [244]={212},
  [245]={213},
  [246]={214},
  [248]={216},
  [249]={217},
  [250]={218},
  [251]={219},
  [252]={220},
  [253]={221},
  [254]={222},
  [255]={376},
  [257]={256},
  [259]={258},
  [261]={260},
  [263]={262},
  [265]={264},
  [267]={266},
  [269]={268},
  [271]={270},
  [273]={272},
  [275]={274},
  [277]={276},
  [279]={278},
  [281]={280},
  [283]={282},
  [285]={284},
  [287]={286},
  [289]={288},
  [291]={290},
  [293]={292},
  [295]={294},
  [297]={296},
  [299]={298},
  [301]={300},
  [303]={302},
  [305]={73},
  [307]={306},
  [309]={308},
  [311]={310},
  [314]={313},
  [316]={315},
  [318]={317},
  [320]={319},
  [322]={321},
  [324]={323},
  [326]={325},
  [328]={327},
  [329]={700,78},
  [331]={330},
  [333]={332},
  [335]={334},
  [337]={336},
  [339]={338},
  [341]={340},
  [343]={342},
  [345]={344},
  [347]={346},
  [349]={348},
  [351]={350},
  [353]={352},
  [355]={354},
  [357]={356},
  [359]={358},
  [361]={360},
  [363]={362},
  [365]={364},
  [367]={366},
  [369]={368},
  [371]={370},
  [373]={372},
  [375]={374},
  [378]={377},
  [380]={379},
  [382]={381},
  [383]={83},
  [384]={579},
  [387]={386},
  [389]={388},
  [392]={391},
  [396]={395},
  [402]={401},
  [405]={502},
  [409]={408},
  [410]={573},
  [411]={42972},
  [414]={544},
  [417]={416},
  [419]={418},
  [421]={420},
  [424]={423},
  [429]={428},
  [432]={431},
  [436]={435},
  [438]={437},
  [441]={440},
  [445]={444},
  [447]={503},
  [453]={452},
  [454]={452},
  [456]={455},
  [457]={455},
  [459]={458},
  [460]={458},
  [462]={461},
  [464]={463},
  [466]={465},
  [468]={467},
  [470]={469},
  [472]={471},
  [474]={473},
  [476]={475},
  [477]={398},
  [479]={478},
  [481]={480},
  [483]={482},
  [485]={484},
  [487]={486},
  [489]={488},
  [491]={490},
  [493]={492},
  [495]={494},
  [496]={74,780},
  [498]={497},
  [499]={497},
  [501]={500},
  [505]={504},
  [507]={506},
  [509]={508},
  [511]={510},
  [513]={512},
  [515]={514},
  [517]={516},
  [519]={518},
  [521]={520},
  [523]={522},
  [525]={524},
  [527]={526},
  [529]={528},
  [531]={530},
  [533]={532},
  [535]={534},
  [537]={536},
  [539]={538},
  [541]={540},
  [543]={542},
  [547]={546},
  [549]={548},
  [551]={550},
  [553]={552},
  [555]={554},
  [557]={556},
  [559]={558},
  [561]={560},
  [563]={562},
  [572]={571},
  [575]={11390},
  [576]={11391},
  [578]={577},
  [583]={582},
  [585]={584},
  [587]={586},
  [589]={588},
  [591]={590},
  [592]={11375},
  [593]={11373},
  [594]={11376},
  [595]={385},
  [596]={390},
  [598]={393},
  [599]={394},
  [601]={399},
  [603]={400},
  [604]={42923},
  [608]={403},
  [609]={42924},
  [611]={404},
  [612]={42955},
  [613]={42893},
  [614]={42922},
  [616]={407},
  [617]={406},
  [618]={42926},
  [619]={11362},
  [620]={42925},
  [623]={412},
  [625]={11374},
  [626]={413},
  [629]={415},
  [637]={11364},
  [640]={422},
  [642]={42949},
  [643]={425},
  [647]={42929},
  [648]={430},
  [649]={580},
  [650]={433},
  [651]={434},
  [652]={581},
  [658]={439},
  [669]={42930},
  [670]={42928},
  [837]={921},
  [881]={880},
  [883]={882},
  [887]={886},
  [891]={1021},
  [892]={1022},
  [893]={1023},
  [912]={921,776,769},
  [940]={902},
  [941]={904},
  [942]={905},
  [943]={906},
  [944]={933,776,769},
  [945]={913},
  [946]={914},
  [947]={915},
  [948]={916},
  [949]={917},
  [950]={918},
  [951]={919},
  [952]={920},
  [953]={921},
  [954]={922},
  [955]={923},
  [956]={924},
  [957]={925},
  [958]={926},
  [959]={927},
  [960]={928},
  [961]={929},
  [962]={931},
  [963]={931},
  [964]={932},
  [965]={933},
  [966]={934},
  [967]={935},
  [968]={936},
  [969]={937},
  [970]={938},
  [971]={939},
  [972]={908},
  [973]={910},
  [974]={911},
  [976]={914},
  [977]={920},
  [981]={934},
  [982]={928},
  [983]={975},
  [985]={984},
  [987]={986},
  [989]={988},
  [991]={990},
  [993]={992},
  [995]={994},
  [997]={996},
  [999]={998},
  [1001]={1000},
  [1003]={1002},
  [1005]={1004},
  [1007]={1006},
  [1008]={922},
  [1009]={929},
  [1010]={1017},
  [1011]={895},
  [1013]={917},
  [1016]={1015},
  [1019]={1018},
  [1072]={1040},
  [1073]={1041},
  [1074]={1042},
  [1075]={1043},
  [1076]={1044},
  [1077]={1045},
  [1078]={1046},
  [1079]={1047},
  [1080]={1048},
  [1081]={1049},
  [1082]={1050},
  [1083]={1051},
  [1084]={1052},
  [1085]={1053},
  [1086]={1054},
  [1087]={1055},
  [1088]={1056},
  [1089]={1057},
  [1090]={1058},
  [1091]={1059},
  [1092]={1060},
  [1093]={1061},
  [1094]={1062},
  [1095]={1063},
  [1096]={1064},
  [1097]={1065},
  [1098]={1066},
  [1099]={1067},
  [1100]={1068},
  [1101]={1069},
  [1102]={1070},
  [1103]={1071},
  [1104]={1024},
  [1105]={1025},
  [1106]={1026},
  [1107]={1027},
  [1108]={1028},
  [1109]={1029},
  [1110]={1030},
  [1111]={1031},
  [1112]={1032},
  [1113]={1033},
  [1114]={1034},
  [1115]={1035},
  [1116]={1036},
  [1117]={1037},
  [1118]={1038},
  [1119]={1039},
  [1121]={1120},
  [1123]={1122},
  [1125]={1124},
  [1127]={1126},
  [1129]={1128},
  [1131]={1130},
  [1133]={1132},
  [1135]={1134},
  [1137]={1136},
  [1139]={1138},
  [1141]={1140},
  [1143]={1142},
  [1145]={1144},
  [1147]={1146},
  [1149]={1148},
  [1151]={1150},
  [1153]={1152},
  [1163]={1162},
  [1165]={1164},
  [1167]={1166},
  [1169]={1168},
  [1171]={1170},
  [1173]={1172},
  [1175]={1174},
  [1177]={1176},
  [1179]={1178},
  [1181]={1180},
  [1183]={1182},
  [1185]={1184},
  [1187]={1186},
  [1189]={1188},
  [1191]={1190},
  [1193]={1192},
  [1195]={1194},
  [1197]={1196},
  [1199]={1198},
  [1201]={1200},
  [1203]={1202},
  [1205]={1204},
  [1207]={1206},
  [1209]={1208},
  [1211]={1210},
  [1213]={1212},
  [1215]={1214},
  [1218]={1217},
  [1220]={1219},
  [1222]={1221},
  [1224]={1223},
  [1226]={1225},
  [1228]={1227},
  [1230]={1229},
  [1231]={1216},
  [1233]={1232},
  [1235]={1234},
  [1237]={1236},
  [1239]={1238},
  [1241]={1240},
  [1243]={1242},
  [1245]={1244},
  [1247]={1246},
  [1249]={1248},
  [1251]={1250},
  [1253]={1252},
  [1255]={1254},
  [1257]={1256},
  [1259]={1258},
  [1261]={1260},
  [1263]={1262},
  [1265]={1264},
  [1267]={1266},
  [1269]={1268},
  [1271]={1270},
  [1273]={1272},
  [1275]={1274},
  [1277]={1276},
  [1279]={1278},
  [1281]={1280},
  [1283]={1282},
  [1285]={1284},
  [1287]={1286},
  [1289]={1288},
  [1291]={1290},
  [1293]={1292},
  [1295]={1294},
  [1297]={1296},
  [1299]={1298},
  [1301]={1300},
  [1303]={1302},
  [1305]={1304},
  [1307]={1306},
  [1309]={1308},
  [1311]={1310},
  [1313]={1312},
  [1315]={1314},
  [1317]={1316},
  [1319]={1318},
  [1321]={1320},
  [1323]={1322},
  [1325]={1324},
  [1327]={1326},
  [1377]={1329},
  [1378]={1330},
  [1379]={1331},
  [1380]={1332},
  [1381]={1333},
  [1382]={1334},
  [1383]={1335},
  [1384]={1336},
  [1385]={1337},
  [1386]={1338},
  [1387]={1339},
  [1388]={1340},
  [1389]={1341},
  [1390]={1342},
  [1391]={1343},
  [1392]={1344},
  [1393]={1345},
  [1394]={1346},
  [1395]={1347},
  [1396]={1348},
  [1397]={1349},
  [1398]={1350},
  [1399]={1351},
  [1400]={1352},
  [1401]={1353},
  [1402]={1354},
  [1403]={1355},
  [1404]={1356},
  [1405]={1357},
  [1406]={1358},
  [1407]={1359},
  [1408]={1360},
  [1409]={1361},
  [1410]={1362},
  [1411]={1363},
  [1412]={1364},
  [1413]={1365},
  [1414]={1366},
  [1415]={1333,1362},
  [4304]={7312},
  [4305]={7313},
  [4306]={7314},
  [4307]={7315},
  [4308]={7316},
  [4309]={7317},
  [4310]={7318},
  [4311]={7319},
  [4312]={7320},
  [4313]={7321},
  [4314]={7322},
  [4315]={7323},
  [4316]={7324},
  [4317]={7325},
  [4318]={7326},
  [4319]={7327},
  [4320]={7328},
  [4321]={7329},
  [4322]={7330},
  [4323]={7331},
  [4324]={7332},
  [4325]={7333},
  [4326]={7334},
  [4327]={7335},
  [4328]={7336},
  [4329]={7337},
  [4330]={7338},
  [4331]={7339},
  [4332]={7340},
  [4333]={7341},
  [4334]={7342},
  [4335]={7343},
  [4336]={7344},
  [4337]={7345},
  [4338]={7346},
  [4339]={7347},
  [4340]={7348},
  [4341]={7349},
  [4342]={7350},
  [4343]={7351},
  [4344]={7352},
  [4345]={7353},
  [4346]={7354},
  [4349]={7357},
  [4350]={7358},
  [4351]={7359},
  [5112]={5104},
  [5113]={5105},
  [5114]={5106},
  [5115]={5107},
  [5116]={5108},
  [5117]={5109},
  [7296]={1042},
  [7297]={1044},
  [7298]={1054},
  [7299]={1057},
  [7300]={1058},
  [7301]={1058},
  [7302]={1066},
  [7303]={1122},
  [7304]={42570},
  [7306]={7305},
  [7545]={42877},
  [7549]={11363},
  [7566]={42950},
  [7681]={7680},
  [7683]={7682},
  [7685]={7684},
  [7687]={7686},
  [7689]={7688},
  [7691]={7690},
  [7693]={7692},
  [7695]={7694},
  [7697]={7696},
  [7699]={7698},
  [7701]={7700},
  [7703]={7702},
  [7705]={7704},
  [7707]={7706},
  [7709]={7708},
  [7711]={7710},
  [7713]={7712},
  [7715]={7714},
  [7717]={7716},
  [7719]={7718},
  [7721]={7720},
  [7723]={7722},
  [7725]={7724},
  [7727]={7726},
  [7729]={7728},
  [7731]={7730},
  [7733]={7732},
  [7735]={7734},
  [7737]={7736},
  [7739]={7738},
  [7741]={7740},
  [7743]={7742},
  [7745]={7744},
  [7747]={7746},
  [7749]={7748},
  [7751]={7750},
  [7753]={7752},
  [7755]={7754},
  [7757]={7756},
  [7759]={7758},
  [7761]={7760},
  [7763]={7762},
  [7765]={7764},
  [7767]={7766},
  [7769]={7768},
  [7771]={7770},
  [7773]={7772},
  [7775]={7774},
  [7777]={7776},
  [7779]={7778},
  [7781]={7780},
  [7783]={7782},
  [7785]={7784},
  [7787]={7786},
  [7789]={7788},
  [7791]={7790},
  [7793]={7792},
  [7795]={7794},
  [7797]={7796},
  [7799]={7798},
  [7801]={7800},
  [7803]={7802},
  [7805]={7804},
  [7807]={7806},
  [7809]={7808},
  [7811]={7810},
  [7813]={7812},
  [7815]={7814},
  [7817]={7816},
  [7819]={7818},
  [7821]={7820},
  [7823]={7822},
  [7825]={7824},
  [7827]={7826},
  [7829]={7828},
  [7830]={72,817},
  [7831]={84,776},
  [7832]={87,778},
  [7833]={89,778},
  [7834]={65,702},
  [7835]={7776},
  [7841]={7840},
  [7843]={7842},
  [7845]={7844},
  [7847]={7846},
  [7849]={7848},
  [7851]={7850},
  [7853]={7852},
  [7855]={7854},
  [7857]={7856},
  [7859]={7858},
  [7861]={7860},
  [7863]={7862},
  [7865]={7864},
  [7867]={7866},
  [7869]={7868},
  [7871]={7870},
  [7873]={7872},
  [7875]={7874},
  [7877]={7876},
  [7879]={7878},
  [7881]={7880},
  [7883]={7882},
  [7885]={7884},
  [7887]={7886},
  [7889]={7888},
  [7891]={7890},
  [7893]={7892},
  [7895]={7894},
  [7897]={7896},
  [7899]={7898},
  [7901]={7900},
  [7903]={7902},
  [7905]={7904},
  [7907]={7906},
  [7909]={7908},
  [7911]={7910},
  [7913]={7912},
  [7915]={7914},
  [7917]={7916},
  [7919]={7918},
  [7921]={7920},
  [7923]={7922},
  [7925]={7924},
  [7927]={7926},
  [7929]={7928},
  [7931]={7930},
  [7933]={7932},
  [7935]={7934},
  [7936]={7944},
  [7937]={7945},
  [7938]={7946},
  [7939]={7947},
  [7940]={7948},
  [7941]={7949},
  [7942]={7950},
  [7943]={7951},
  [7952]={7960},
  [7953]={7961},
  [7954]={7962},
  [7955]={7963},
  [7956]={7964},
  [7957]={7965},
  [7968]={7976},
  [7969]={7977},
  [7970]={7978},
  [7971]={7979},
  [7972]={7980},
  [7973]={7981},
  [7974]={7982},
  [7975]={7983},
  [7984]={7992},
  [7985]={7993},
  [7986]={7994},
  [7987]={7995},
  [7988]={7996},
  [7989]={7997},
  [7990]={7998},
  [7991]={7999},
  [8000]={8008},
  [8001]={8009},
  [8002]={8010},
  [8003]={8011},
  [8004]={8012},
  [8005]={8013},
  [8016]={933,787},
  [8017]={8025},
  [8018]={933,787,768},
  [8019]={8027},
  [8020]={933,787,769},
  [8021]={8029},
  [8022]={933,787,834},
  [8023]={8031},
  [8032]={8040},
  [8033]={8041},
  [8034]={8042},
  [8035]={8043},
  [8036]={8044},
  [8037]={8045},
  [8038]={8046},
  [8039]={8047},
  [8048]={8122},
  [8049]={8123},
  [8050]={8136},
  [8051]={8137},
  [8052]={8138},
  [8053]={8139},
  [8054]={8154},
  [8055]={8155},
  [8056]={8184},
  [8057]={8185},
  [8058]={8170},
  [8059]={8171},
  [8060]={8186},
  [8061]={8187},
  [8064]={7944,921},
  [8065]={7945,921},
  [8066]={7946,921},
  [8067]={7947,921},
  [8068]={7948,921},
  [8069]={7949,921},
  [8070]={7950,921},
  [8071]={7951,921},
  [8072]={7944,921},
  [8073]={7945,921},
  [8074]={7946,921},
  [8075]={7947,921},
  [8076]={7948,921},
  [8077]={7949,921},
  [8078]={7950,921},
  [8079]={7951,921},
  [8080]={7976,921},
  [8081]={7977,921},
  [8082]={7978,921},
  [8083]={7979,921},
  [8084]={7980,921},
  [8085]={7981,921},
  [8086]={7982,921},
  [8087]={7983,921},
  [8088]={7976,921},
  [8089]={7977,921},
  [8090]={7978,921},
  [8091]={7979,921},
  [8092]={7980,921},
  [8093]={7981,921},
  [8094]={7982,921},
  [8095]={7983,921},
  [8096]={8040,921},
  [8097]={8041,921},
  [8098]={8042,921},
  [8099]={8043,921},
  [8100]={8044,921},
  [8101]={8045,921},
  [8102]={8046,921},
  [8103]={8047,921},
  [8104]={8040,921},
  [8105]={8041,921},
  [8106]={8042,921},
  [8107]={8043,921},
  [8108]={8044,921},
  [8109]={8045,921},
  [8110]={8046,921},
  [8111]={8047,921},
  [8112]={8120},
  [8113]={8121},
  [8114]={8122,921},
  [8115]={913,921},
  [8116]={902,921},
  [8118]={913,834},
  [8119]={913,834,921},
  [8124]={913,921},
  [8126]={921},
  [8130]={8138,921},
  [8131]={919,921},
  [8132]={905,921},
  [8134]={919,834},
  [8135]={919,834,921},
  [8140]={919,921},
  [8144]={8152},
  [8145]={8153},
  [8146]={921,776,768},
  [8147]={921,776,769},
  [8150]={921,834},
  [8151]={921,776,834},
  [8160]={8168},
  [8161]={8169},
  [8162]={933,776,768},
  [8163]={933,776,769},
  [8164]={929,787},
  [8165]={8172},
  [8166]={933,834},
  [8167]={933,776,834},
  [8178]={8186,921},
  [8179]={937,921},
  [8180]={911,921},
  [8182]={937,834},
  [8183]={937,834,921},
  [8188]={937,921},
  [8526]={8498},
  [8560]={8544},
  [8561]={8545},
  [8562]={8546},
  [8563]={8547},
  [8564]={8548},
  [8565]={8549},
  [8566]={8550},
  [8567]={8551},
  [8568]={8552},
  [8569]={8553},
  [8570]={8554},
  [8571]={8555},
  [8572]={8556},
  [8573]={8557},
  [8574]={8558},
  [8575]={8559},
  [8580]={8579},
  [9424]={9398},
  [9425]={9399},
  [9426]={9400},
  [9427]={9401},
  [9428]={9402},
  [9429]={9403},
  [9430]={9404},
  [9431]={9405},
  [9432]={9406},
  [9433]={9407},
  [9434]={9408},
  [9435]={9409},
  [9436]={9410},
  [9437]={9411},
  [9438]={9412},
  [9439]={9413},
  [9440]={9414},
  [9441]={9415},
  [9442]={9416},
  [9443]={9417},
  [9444]={9418},
  [9445]={9419},
  [9446]={9420},
  [9447]={9421},
  [9448]={9422},
  [9449]={9423},
  [11312]={11264},
  [11313]={11265},
  [11314]={11266},
  [11315]={11267},
  [11316]={11268},
  [11317]={11269},
  [11318]={11270},
  [11319]={11271},
  [11320]={11272},
  [11321]={11273},
  [11322]={11274},
  [11323]={11275},
  [11324]={11276},
  [11325]={11277},
  [11326]={11278},
  [11327]={11279},
  [11328]={11280},
  [11329]={11281},
  [11330]={11282},
  [11331]={11283},
  [11332]={11284},
  [11333]={11285},
  [11334]={11286},
  [11335]={11287},
  [11336]={11288},
  [11337]={11289},
  [11338]={11290},
  [11339]={11291},
  [11340]={11292},
  [11341]={11293},
  [11342]={11294},
  [11343]={11295},
  [11344]={11296},
  [11345]={11297},
  [11346]={11298},
  [11347]={11299},
  [11348]={11300},
  [11349]={11301},
  [11350]={11302},
  [11351]={11303},
  [11352]={11304},
  [11353]={11305},
  [11354]={11306},
  [11355]={11307},
  [11356]={11308},
  [11357]={11309},
  [11358]={11310},
  [11359]={11311},
  [11361]={11360},
  [11365]={570},
  [11366]={574},
  [11368]={11367},
  [11370]={11369},
  [11372]={11371},
  [11379]={11378},
  [11382]={11381},
  [11393]={11392},
  [11395]={11394},
  [11397]={11396},
  [11399]={11398},
  [11401]={11400},
  [11403]={11402},
  [11405]={11404},
  [11407]={11406},
  [11409]={11408},
  [11411]={11410},
  [11413]={11412},
  [11415]={11414},
  [11417]={11416},
  [11419]={11418},
  [11421]={11420},
  [11423]={11422},
  [11425]={11424},
  [11427]={11426},
  [11429]={11428},
  [11431]={11430},
  [11433]={11432},
  [11435]={11434},
  [11437]={11436},
  [11439]={11438},
  [11441]={11440},
  [11443]={11442},
  [11445]={11444},
  [11447]={11446},
  [11449]={11448},
  [11451]={11450},
  [11453]={11452},
  [11455]={11454},
  [11457]={11456},
  [11459]={11458},
  [11461]={11460},
  [11463]={11462},
  [11465]={11464},
  [11467]={11466},
  [11469]={11468},
  [11471]={11470},
  [11473]={11472},
  [11475]={11474},
  [11477]={11476},
  [11479]={11478},
  [11481]={11480},
  [11483]={11482},
  [11485]={11484},
  [11487]={11486},
  [11489]={11488},
  [11491]={11490},
  [11500]={11499},
  [11502]={11501},
  [11507]={11506},
  [11520]={4256},
  [11521]={4257},
  [11522]={4258},
  [11523]={4259},
  [11524]={4260},
  [11525]={4261},
  [11526]={4262},
  [11527]={4263},
  [11528]={4264},
  [11529]={4265},
  [11530]={4266},
  [11531]={4267},
  [11532]={4268},
  [11533]={4269},
  [11534]={4270},
  [11535]={4271},
  [11536]={4272},
  [11537]={4273},
  [11538]={4274},
  [11539]={4275},
  [11540]={4276},
  [11541]={4277},
  [11542]={4278},
  [11543]={4279},
  [11544]={4280},
  [11545]={4281},
  [11546]={4282},
  [11547]={4283},
  [11548]={4284},
  [11549]={4285},
  [11550]={4286},
  [11551]={4287},
  [11552]={4288},
  [11553]={4289},
  [11554]={4290},
  [11555]={4291},
  [11556]={4292},
  [11557]={4293},
  [11559]={4295},
  [11565]={4301},
  [42561]={42560},
  [42563]={42562},
  [42565]={42564},
  [42567]={42566},
  [42569]={42568},
  [42571]={42570},
  [42573]={42572},
  [42575]={42574},
  [42577]={42576},
  [42579]={42578},
  [42581]={42580},
  [42583]={42582},
  [42585]={42584},
  [42587]={42586},
  [42589]={42588},
  [42591]={42590},
  [42593]={42592},
  [42595]={42594},
  [42597]={42596},
  [42599]={42598},
  [42601]={42600},
  [42603]={42602},
  [42605]={42604},
  [42625]={42624},
  [42627]={42626},
  [42629]={42628},
  [42631]={42630},
  [42633]={42632},
  [42635]={42634},
  [42637]={42636},
  [42639]={42638},
  [42641]={42640},
  [42643]={42642},
  [42645]={42644},
  [42647]={42646},
  [42649]={42648},
  [42651]={42650},
  [42787]={42786},
  [42789]={42788},
  [42791]={42790},
  [42793]={42792},
  [42795]={42794},
  [42797]={42796},
  [42799]={42798},
  [42803]={42802},
  [42805]={42804},
  [42807]={42806},
  [42809]={42808},
  [42811]={42810},
  [42813]={42812},
  [42815]={42814},
  [42817]={42816},
  [42819]={42818},
  [42821]={42820},
  [42823]={42822},
  [42825]={42824},
  [42827]={42826},
  [42829]={42828},
  [42831]={42830},
  [42833]={42832},
  [42835]={42834},
  [42837]={42836},
  [42839]={42838},
  [42841]={42840},
  [42843]={42842},
  [42845]={42844},
  [42847]={42846},
  [42849]={42848},
  [42851]={42850},
  [42853]={42852},
  [42855]={42854},
  [42857]={42856},
  [42859]={42858},
  [42861]={42860},
  [42863]={42862},
  [42874]={42873},
  [42876]={42875},
  [42879]={42878},
  [42881]={42880},
  [42883]={42882},
  [42885]={42884},
  [42887]={42886},
  [42892]={42891},
  [42897]={42896},
  [42899]={42898},
  [42900]={42948},
  [42903]={42902},
  [42905]={42904},
  [42907]={42906},
  [42909]={42908},
  [42911]={42910},
  [42913]={42912},
  [42915]={42914},
  [42917]={42916},
  [42919]={42918},
  [42921]={42920},
  [42933]={42932},
  [42935]={42934},
  [42937]={42936},
  [42939]={42938},
  [42941]={42940},
  [42943]={42942},
  [42945]={42944},
  [42947]={42946},
  [42952]={42951},
  [42954]={42953},
  [42957]={42956},
  [42959]={42958},
  [42961]={42960},
  [42963]={42962},
  [42965]={42964},
  [42967]={42966},
  [42969]={42968},
  [42971]={42970},
  [42998]={42997},
  [43859]={42931},
  [43888]={5024},
  [43889]={5025},
  [43890]={5026},
  [43891]={5027},
  [43892]={5028},
  [43893]={5029},
  [43894]={5030},
  [43895]={5031},
  [43896]={5032},
  [43897]={5033},
  [43898]={5034},
  [43899]={5035},
  [43900]={5036},
  [43901]={5037},
  [43902]={5038},
  [43903]={5039},
  [43904]={5040},
  [43905]={5041},
  [43906]={5042},
  [43907]={5043},
  [43908]={5044},
  [43909]={5045},
  [43910]={5046},
  [43911]={5047},
  [43912]={5048},
  [43913]={5049},
  [43914]={5050},
  [43915]={5051},
  [43916]={5052},
  [43917]={5053},
  [43918]={5054},
  [43919]={5055},
  [43920]={5056},
  [43921]={5057},
  [43922]={5058},
  [43923]={5059},
  [43924]={5060},
  [43925]={5061},
  [43926]={5062},
  [43927]={5063},
  [43928]={5064},
  [43929]={5065},
  [43930]={5066},
  [43931]={5067},
  [43932]={5068},
  [43933]={5069},
  [43934]={5070},
  [43935]={5071},
  [43936]={5072},
  [43937]={5073},
  [43938]={5074},
  [43939]={5075},
  [43940]={5076},
  [43941]={5077},
  [43942]={5078},
  [43943]={5079},
  [43944]={5080},
  [43945]={5081},
  [43946]={5082},
  [43947]={5083},
  [43948]={5084},
  [43949]={5085},
  [43950]={5086},
  [43951]={5087},
  [43952]={5088},
  [43953]={5089},
  [43954]={5090},
  [43955]={5091},
  [43956]={5092},
  [43957]={5093},
  [43958]={5094},
  [43959]={5095},
  [43960]={5096},
  [43961]={5097},
  [43962]={5098},
  [43963]={5099},
  [43964]={5100},
  [43965]={5101},
  [43966]={5102},
  [43967]={5103},
  [64256]={70,70},
  [64257]={70,73},
  [64258]={70,76},
  [64259]={70,70,73},
  [64260]={70,70,76},
  [64261]={83,84},
  [64262]={83,84},
  [64275]={1348,1350},
  [64276]={1348,1333},
  [64277]={1348,1339},
  [64278]={1358,1350},
  [64279]={1348,1341},
  [65345]={65313},
  [65346]={65314},
  [65347]={65315},
  [65348]={65316},
  [65349]={65317},
  [65350]={65318},
  [65351]={65319},
  [65352]={65320},
  [65353]={65321},
  [65354]={65322},
  [65355]={65323},
  [65356]={65324},
  [65357]={65325},
  [65358]={65326},
  [65359]={65327},
  [65360]={65328},
  [65361]={65329},
  [65362]={65330},
  [65363]={65331},
  [65364]={65332},
  [65365]={65333},
  [65366]={65334},
  [65367]={65335},
  [65368]={65336},
  [65369]={65337},
  [65370]={65338},
  [66600]={66560},
  [66601]={66561},
  [66602]={66562},
  [66603]={66563},
  [66604]={66564},
  [66605]={66565},
  [66606]={66566},
  [66607]={66567},
  [66608]={66568},
  [66609]={66569},
  [66610]={66570},
  [66611]={66571},
  [66612]={66572},
  [66613]={66573},
  [66614]={66574},
  [66615]={66575},
  [66616]={66576},
  [66617]={66577},
  [66618]={66578},
  [66619]={66579},
  [66620]={66580},
  [66621]={66581},
  [66622]={66582},
  [66623]={66583},
  [66624]={66584},
  [66625]={66585},
  [66626]={66586},
  [66627]={66587},
  [66628]={66588},
  [66629]={66589},
  [66630]={66590},
  [66631]={66591},
  [66632]={66592},
  [66633]={66593},
  [66634]={66594},
  [66635]={66595},
  [66636]={66596},
  [66637]={66597},
  [66638]={66598},
  [66639]={66599},
  [66776]={66736},
  [66777]={66737},
  [66778]={66738},
  [66779]={66739},
  [66780]={66740},
  [66781]={66741},
  [66782]={66742},
  [66783]={66743},
  [66784]={66744},
  [66785]={66745},
  [66786]={66746},
  [66787]={66747},
  [66788]={66748},
  [66789]={66749},
  [66790]={66750},
  [66791]={66751},
  [66792]={66752},
  [66793]={66753},
  [66794]={66754},
  [66795]={66755},
  [66796]={66756},
  [66797]={66757},
  [66798]={66758},
  [66799]={66759},
  [66800]={66760},
  [66801]={66761},
  [66802]={66762},
  [66803]={66763},
  [66804]={66764},
  [66805]={66765},
  [66806]={66766},
  [66807]={66767},
  [66808]={66768},
  [66809]={66769},
  [66810]={66770},
  [66811]={66771},
  [66967]={66928},
  [66968]={66929},
  [66969]={66930},
  [66970]={66931},
  [66971]={66932},
  [66972]={66933},
  [66973]={66934},
  [66974]={66935},
  [66975]={66936},
  [66976]={66937},
  [66977]={66938},
  [66979]={66940},
  [66980]={66941},
  [66981]={66942},
  [66982]={66943},
  [66983]={66944},
  [66984]={66945},
  [66985]={66946},
  [66986]={66947},
  [66987]={66948},
  [66988]={66949},
  [66989]={66950},
  [66990]={66951},
  [66991]={66952},
  [66992]={66953},
  [66993]={66954},
  [66995]={66956},
  [66996]={66957},
  [66997]={66958},
  [66998]={66959},
  [66999]={66960},
  [67000]={66961},
  [67001]={66962},
  [67003]={66964},
  [67004]={66965},
  [68800]={68736},
  [68801]={68737},
  [68802]={68738},
  [68803]={68739},
  [68804]={68740},
  [68805]={68741},
  [68806]={68742},
  [68807]={68743},
  [68808]={68744},
  [68809]={68745},
  [68810]={68746},
  [68811]={68747},
  [68812]={68748},
  [68813]={68749},
  [68814]={68750},
  [68815]={68751},
  [68816]={68752},
  [68817]={68753},
  [68818]={68754},
  [68819]={68755},
  [68820]={68756},
  [68821]={68757},
  [68822]={68758},
  [68823]={68759},
  [68824]={68760},
  [68825]={68761},
  [68826]={68762},
  [68827]={68763},
  [68828]={68764},
  [68829]={68765},
  [68830]={68766},
  [68831]={68767},
  [68832]={68768},
  [68833]={68769},
  [68834]={68770},
  [68835]={68771},
  [68836]={68772},
  [68837]={68773},
  [68838]={68774},
  [68839]={68775},
  [68840]={68776},
  [68841]={68777},
  [68842]={68778},
  [68843]={68779},
  [68844]={68780},
  [68845]={68781},
  [68846]={68782},
  [68847]={68783},
  [68848]={68784},
  [68849]={68785},
  [68850]={68786},
  [68976]={68944},
  [68977]={68945},
  [68978]={68946},
  [68979]={68947},
  [68980]={68948},
  [68981]={68949},
  [68982]={68950},
  [68983]={68951},
  [68984]={68952},
  [68985]={68953},
  [68986]={68954},
  [68987]={68955},
  [68988]={68956},
  [68989]={68957},
  [68990]={68958},
  [68991]={68959},
  [68992]={68960},
  [68993]={68961},
  [68994]={68962},
  [68995]={68963},
  [68996]={68964},
  [68997]={68965},
  [71872]={71840},
  [71873]={71841},
  [71874]={71842},
  [71875]={71843},
  [71876]={71844},
  [71877]={71845},
  [71878]={71846},
  [71879]={71847},
  [71880]={71848},
  [71881]={71849},
  [71882]={71850},
  [71883]={71851},
  [71884]={71852},
  [71885]={71853},
  [71886]={71854},
  [71887]={71855},
  [71888]={71856},
  [71889]={71857},
  [71890]={71858},
  [71891]={71859},
  [71892]={71860},
  [71893]={71861},
  [71894]={71862},
  [71895]={71863},
  [71896]={71864},
  [71897]={71865},
  [71898]={71866},
  [71899]={71867},
  [71900]={71868},
  [71901]={71869},
  [71902]={71870},
  [71903]={71871},
  [93792]={93760},
  [93793]={93761},
  [93794]={93762},
  [93795]={93763},
  [93796]={93764},
  [93797]={93765},
  [93798]={93766},
  [93799]={93767},
  [93800]={93768},
  [93801]={93769},
  [93802]={93770},
  [93803]={93771},
  [93804]={93772},
  [93805]={93773},
  [93806]={93774},
  [93807]={93775},
  [93808]={93776},
  [93809]={93777},
  [93810]={93778},
  [93811]={93779},
  [93812]={93780},
  [93813]={93781},
  [93814]={93782},
  [93815]={93783},
  [93816]={93784},
  [93817]={93785},
  [93818]={93786},
  [93819]={93787},
  [93820]={93788},
  [93821]={93789},
  [93822]={93790},
  [93823]={93791},
  [93883]={93856},
  [93884]={93857},
  [93885]={93858},
  [93886]={93859},
  [93887]={93860},
  [93888]={93861},
  [93889]={93862},
  [93890]={93863},
  [93891]={93864},
  [93892]={93865},
  [93893]={93866},
  [93894]={93867},
  [93895]={93868},
  [93896]={93869},
  [93897]={93870},
  [93898]={93871},
  [93899]={93872},
  [93900]={93873},
  [93901]={93874},
  [93902]={93875},
  [93903]={93876},
  [93904]={93877},
  [93905]={93878},
  [93906]={93879},
  [93907]={93880},
  [125218]={125184},
  [125219]={125185},
  [125220]={125186},
  [125221]={125187},
  [125222]={125188},
  [125223]={125189},
  [125224]={125190},
  [125225]={125191},
  [125226]={125192},
  [125227]={125193},
  [125228]={125194},
  [125229]={125195},
  [125230]={125196},
  [125231]={125197},
  [125232]={125198},
  [125233]={125199},
  [125234]={125200},
  [125235]={125201},
  [125236]={125202},
  [125237]={125203},
  [125238]={125204},
  [125239]={125205},
  [125240]={125206},
  [125241]={125207},
  [125242]={125208},
  [125243]={125209},
  [125244]={125210},
  [125245]={125211},
  [125246]={125212},
  [125247]={125213},
  [125248]={125214},
  [125249]={125215},
  [125250]={125216},
  [125251]={125217},
}
data.cased = {65,90,97,122,170,170,181,181,186,186,192,214,216,246,248,442,444,447,452,659,662,687,688,696,704,705,736,740,837,837,880,883,886,887,890,890,891,893,895,895,902,902,904,906,908,908,910,929,931,1013,1015,1153,1162,1327,1329,1366,1376,1416,4256,4293,4295,4295,4301,4301,4304,4346,4348,4348,4349,4351,5024,5109,5112,5117,7296,7306,7312,7354,7357,7359,7424,7467,7468,7530,7531,7543,7544,7544,7545,7578,7579,7615,7680,7957,7960,7965,7968,8005,8008,8013,8016,8023,8025,8025,8027,8027,8029,8029,8031,8061,8064,8116,8118,8124,8126,8126,8130,8132,8134,8140,8144,8147,8150,8155,8160,8172,8178,8180,8182,8188,8305,8305,8319,8319,8336,8348,8450,8450,8455,8455,8458,8467,8469,8469,8473,8477,8484,8484,8486,8486,8488,8488,8490,8493,8495,8500,8505,8505,8508,8511,8517,8521,8526,8526,8544,8575,8579,8580,9398,9449,11264,11387,11388,11389,11390,11492,11499,11502,11506,11507,11520,11557,11559,11559,11565,11565,42560,42605,42624,42651,42652,42653,42786,42863,42864,42864,42865,42887,42891,42894,42896,42972,42993,42996,42997,42998,43000,43001,43002,43002,43824,43866,43868,43871,43872,43880,43881,43881,43888,43967,64256,64262,64275,64279,65313,65338,65345,65370,66560,66639,66736,66771,66776,66811,66928,66938,66940,66954,66956,66962,66964,66965,66967,66977,66979,66993,66995,67001,67003,67004,67456,67456,67459,67461,67463,67504,67506,67514,68736,68786,68800,68850,68944,68965,68976,68997,71840,71903,93760,93823,93856,93880,93883,93907,119808,119892,119894,119964,119966,119967,119970,119970,119973,119974,119977,119980,119982,119993,119995,119995,119997,120003,120005,120069,120071,120074,120077,120084,120086,120092,120094,120121,120123,120126,120128,120132,120134,120134,120138,120144,120146,120485,120488,120512,120514,120538,120540,120570,120572,120596,120598,120628,120630,120654,120656,120686,120688,120712,120714,120744,120746,120770,120772,120779,122624,122633,122635,122654,122661,122666,122928,122989,125184,125251,127280,127305,127312,127337,127344,127369}
data.ignorable = {39,39,46,46,58,58,94,94,96,96,168,168,173,173,175,175,180,180,183,183,184,184,688,705,706,709,710,721,722,735,736,740,741,747,748,748,749,749,750,750,751,767,768,879,884,884,885,885,890,890,900,901,903,903,1155,1159,1160,1161,1369,1369,1375,1375,1425,1469,1471,1471,1473,1474,1476,1477,1479,1479,1524,1524,1536,1541,1552,1562,1564,1564,1600,1600,1611,1631,1648,1648,1750,1756,1757,1757,1759,1764,1765,1766,1767,1768,1770,1773,1807,1807,1809,1809,1840,1866,1958,1968,2027,2035,2036,2037,2042,2042,2045,2045,2070,2073,2074,2074,2075,2083,2084,2084,2085,2087,2088,2088,2089,2093,2137,2139,2184,2184,2192,2193,2199,2207,2249,2249,2250,2273,2274,2274,2275,2306,2362,2362,2364,2364,2369,2376,2381,2381,2385,2391,2402,2403,2417,2417,2433,2433,2492,2492,2497,2500,2509,2509,2530,2531,2558,2558,2561,2562,2620,2620,2625,2626,2631,2632,2635,2637,2641,2641,2672,2673,2677,2677,2689,2690,2748,2748,2753,2757,2759,2760,2765,2765,2786,2787,2810,2815,2817,2817,2876,2876,2879,2879,2881,2884,2893,2893,2901,2902,2914,2915,2946,2946,3008,3008,3021,3021,3072,3072,3076,3076,3132,3132,3134,3136,3142,3144,3146,3149,3157,3158,3170,3171,3201,3201,3260,3260,3263,3263,3270,3270,3276,3277,3298,3299,3328,3329,3387,3388,3393,3396,3405,3405,3426,3427,3457,3457,3530,3530,3538,3540,3542,3542,3633,3633,3636,3642,3654,3654,3655,3662,3761,3761,3764,3772,3782,3782,3784,3790,3864,3865,3893,3893,3895,3895,3897,3897,3953,3966,3968,3972,3974,3975,3981,3991,3993,4028,4038,4038,4141,4144,4146,4151,4153,4154,4157,4158,4184,4185,4190,4192,4209,4212,4226,4226,4229,4230,4237,4237,4253,4253,4348,4348,4957,4959,5906,5908,5938,5939,5970,5971,6002,6003,6068,6069,6071,6077,6086,6086,6089,6099,6103,6103,6109,6109,6155,6157,6158,6158,6159,6159,6211,6211,6277,6278,6313,6313,6432,6434,6439,6440,6450,6450,6457,6459,6679,6680,6683,6683,6742,6742,6744,6750,6752,6752,6754,6754,6757,6764,6771,6780,6783,6783,6823,6823,6832,6845,6846,6846,6847,6877,6880,6891,6912,6915,6964,6964,6966,6970,6972,6972,6978,6978,7019,7027,7040,7041,7074,7077,7080,7081,7083,7085,7142,7142,7144,7145,7149,7149,7151,7153,7212,7219,7222,7223,7288,7293,7376,7378,7380,7392,7394,7400,7405,7405,7412,7412,7416,7417,7468,7530,7544,7544,7579,7615,7616,7679,8125,8125,8127,8129,8141,8143,8157,8159,8173,8175,8189,8190,8203,8207,8216,8216,8217,8217,8228,8228,8231,8231,8234,8238,8288,8292,8294,8303,8305,8305,8319,8319,8336,8348,8400,8412,8413,8416,8417,8417,8418,8420,8421,8432,11388,11389,11503,11505,11631,11631,11647,11647,11744,11775,11823,11823,12293,12293,12330,12333,12337,12341,12347,12347,12441,12442,12443,12444,12445,12446,12540,12542,40981,40981,42232,42237,42508,42508,42607,42607,42608,42610,42612,42621,42623,42623,42652,42653,42654,42655,42736,42737,42752,42774,42775,42783,42784,42785,42864,42864,42888,42888,42889,42890,42993,42996,43000,43001,43010,43010,43014,43014,43019,43019,43045,43046,43052,43052,43204,43205,43232,43249,43263,43263,43302,43309,43335,43345,43392,43394,43443,43443,43446,43449,43452,43453,43471,43471,43493,43493,43494,43494,43561,43566,43569,43570,43573,43574,43587,43587,43596,43596,43632,43632,43644,43644,43696,43696,43698,43700,43703,43704,43710,43711,43713,43713,43741,43741,43756,43757,43763,43764,43766,43766,43867,43867,43868,43871,43881,43881,43882,43883,44005,44005,44008,44008,44013,44013,64286,64286,64434,64450,65024,65039,65043,65043,65056,65071,65106,65106,65109,65109,65279,65279,65287,65287,65294,65294,65306,65306,65342,65342,65344,65344,65392,65392,65438,65439,65507,65507,65529,65531,66045,66045,66272,66272,66422,66426,67456,67461,67463,67504,67506,67514,68097,68099,68101,68102,68108,68111,68152,68154,68159,68159,68325,68326,68900,68903,68942,68942,68969,68973,68975,68975,69291,69292,69317,69317,69370,69375,69446,69456,69506,69509,69633,69633,69688,69702,69744,69744,69747,69748,69759,69761,69811,69814,69817,69818,69821,69821,69826,69826,69837,69837,69888,69890,69927,69931,69933,69940,70003,70003,70016,70017,70070,70078,70089,70092,70095,70095,70191,70193,70196,70196,70198,70199,70206,70206,70209,70209,70367,70367,70371,70378,70400,70401,70459,70460,70464,70464,70502,70508,70512,70516,70587,70592,70606,70606,70608,70608,70610,70610,70625,70626,70712,70719,70722,70724,70726,70726,70750,70750,70835,70840,70842,70842,70847,70848,70850,70851,71090,71093,71100,71101,71103,71104,71132,71133,71219,71226,71229,71229,71231,71232,71339,71339,71341,71341,71344,71349,71351,71351,71453,71453,71455,71455,71458,71461,71463,71467,71727,71735,71737,71738,71995,71996,71998,71998,72003,72003,72148,72151,72154,72155,72160,72160,72193,72202,72243,72248,72251,72254,72263,72263,72273,72278,72281,72283,72330,72342,72344,72345,72544,72544,72546,72548,72550,72550,72752,72758,72760,72765,72767,72767,72850,72871,72874,72880,72882,72883,72885,72886,73009,73014,73018,73018,73020,73021,73023,73029,73031,73031,73104,73105,73109,73109,73111,73111,73177,73177,73459,73460,73472,73473,73526,73530,73536,73536,73538,73538,73562,73562,78896,78911,78912,78912,78919,78933,90398,90409,90413,90415,92912,92916,92976,92982,92992,92995,93504,93506,93547,93548,94031,94031,94095,94098,94099,94111,94176,94177,94179,94179,94180,94180,94194,94195,110576,110579,110581,110587,110589,110590,113821,113822,113824,113827,118528,118573,118576,118598,119143,119145,119155,119162,119163,119170,119173,119179,119210,119213,119362,119364,121344,121398,121403,121452,121461,121461,121476,121476,121499,121503,121505,121519,122880,122886,122888,122904,122907,122913,122915,122916,122918,122922,122928,122989,123023,123023,123184,123190,123191,123197,123566,123566,123628,123631,124139,124139,124140,124143,124398,124399,124643,124643,124646,124646,124654,124655,124661,124661,124671,124671,125136,125142,125252,125258,125259,125259,127995,127999,917505,917505,917536,917631,917760,917999}
return data
end
-- END GENERATED UNICODE CASE DATA

return __rt
