-- DEAL Standard Library: std/json
-- Provides JSON encoding and decoding (simple pure-Lua implementation).
--
-- Unicode conformance (v1.3, RFC 8259): parse accepts exactly the section 7
-- escape set (" \ / b f n r t u), \uXXXX must carry exactly four hex digits
-- and decodes to UTF-8, surrogate pairs combine to one supplementary scalar,
-- lone surrogates and raw control characters (U+0000-U+001F) are rejected,
-- and stringify refuses to emit strings that are not scalar-valid UTF-8.
--
-- Exact decimal classification, decoded-key duplicates, the declared
-- (string)->table parse exit, and stringify
-- shape rejection (DEAL v1.3): parse classifies the numeric lexeme's
-- exact mathematical decimal value before any binary64 rounding — an
-- integral value inside [-2147483648, 2147483647] becomes DEAL int
-- (-0/-0.0 normalize to 0) and every other lexeme becomes number. The
-- decoded object/array graph records that variant per slot in the
-- container's private [__K.nK] marks (a number-variant leaf is marked,
-- an int-variant leaf is unmarked) at every depth, and the encoder
-- renders a marked leaf through the canonical decimal text, so the
-- shipped realization keeps exactly the classification the emitted
-- LuaJIT prelude, the JS runtime and the JVM runtime keep; a
-- member key whose decoded scalar text equals an earlier key of the same
-- object is a parse failure ("duplicate key" at the second key's opening
-- quote). stringify raises Error on non-JSON-shaped values through the
-- closed unsupported-carrier arm: the bytes carrier keeps its pinned
-- bytes token (the same explicit bytes arm the JS runtime landed,
-- js-v12-int32-bytes D3), and every other unsupported carrier — the
-- reference runtime's __kind-tagged function wrapper (function), class
-- or Error instance and decoded async handle (table), the emitted
-- chunk's own __c/__d class and Error marks (table) and __fn/__f/__csig
-- function marks (function), plus the internal absent marker and every
-- absent or deleted slot (nil) — a hole in a marked decoded array or in
-- an inferred sparse table included — renders that arm's own text with
-- its closed carrier-kind token instead of encoding private carrier
-- fields or a fabricated JSON null (only __rt.__NULL is a JSON null);
-- a path-local container re-entry keeps the closed "cyclic value cannot
-- be encoded as JSON" arm. There is no fixed nesting-depth bound. Object
-- key order follows Lua iteration order.
--
-- parse's declared (string)->table exit (ISSUE-0824) rejects the two
-- non-table decode carriers with the shared typed-boundary tuple
-- (E8001 "expected table", expected "table", actual "array"/"null") at
-- the call-site triplet: a top-level decoded array reports the array
-- carrier and a top-level language null reports null, exactly like the
-- emitted chunk's table admission and the JS runtime's checkTable — a
-- decoded array or null never masquerades as a table return. Nested
-- containers and the top-level scalar results keep their representation.
--
-- Decoded arrays keep their array shape (DEAL v1.3 JSON containers): every
-- array the parser completes carries the private array carrier pair — the
-- array mark and the array's logical length — that the emitted chunk and
-- the LuaJIT host projection write, so a nested array, empty arrays
-- included, remains a JSON array of the decoded table representation
-- instead of collapsing into an indistinguishable {}. stringify reads that
-- mark (and the chunk carrier a declared T[] host parameter crosses into
-- DEAL as) before any numeric-key inference, so a nested decoded empty
-- array encodes as [] and a chunk array carrier encodes as the array of its
-- elements, never as an object holding private carrier keys; unmarked
-- tables keep the existing numeric-key inference and object encoding.
--
-- The other two runtime carriers keep their JSON shape (ISSUE-0839): a
-- numeric-variant carrier ({[__K.jn] = true, k = "int"|"number", d = n})
-- encodes as the number it carries with its recorded variant spelling —
-- the int variant's integer text, the number variant's canonical
-- Double.toString decimal text — and a chunk table carrier ([__K.t] with
-- the presence map [__K.keys] and the insertion-order list [__K.order])
-- encodes as an object over exactly its user string keys in the recorded
-- order. A table a chunk wrote outside TABLE_NEW carries only the
-- presence/order marks [__orderAdd] creates on the first write (and the
-- per-slot [__K.nK] marks [__numKey] records); it takes the same object
-- encoding, so a host that stringifies the written table never observes
-- the private marks. The per-slot [__K.nK] marks select the number-variant
-- spelling for a marked slot. None of these private table-object keys is
-- ever an encoded member. The presence map is the present-key authority for
-- an object carrier: the chunk's JSON_PARSE graph records a present null
-- member as a presence/order key with no stored value, so it stays a JSON
-- null member; a raw nil outside such a recorded key is the missing value
-- (the array and inferred-frame rule, ISSUE-0844).

local __rt = require("deal.runtime")

-- The private carrier keys (ISSUE-0830): the emitted chunk, the deployed
-- runtime and this module share one process-wide key set, so no string key
-- of a DEAL table or of a decoded JSON member can be mistaken for a carrier
-- mark and no table can forge a carrier.
local __K = __rt.__keys

local json = {}

-- Encode a Unicode code point as its UTF-8 byte sequence (1-4 bytes).
-- Inputs are Unicode scalar values only: callers never pass surrogates
-- (parse rejects them) or values above U+10FFFF (surrogate-pair math caps
-- at 0x10FFFF).
local function utf8_encode(cp)
  if cp < 0x80 then
    return string.char(cp)
  elseif cp < 0x800 then
    return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + cp % 0x40)
  elseif cp < 0x10000 then
    return string.char(
      0xE0 + math.floor(cp / 0x1000),
      0x80 + math.floor(cp / 0x40) % 0x40,
      0x80 + cp % 0x40)
  else
    return string.char(
      0xF0 + math.floor(cp / 0x40000),
      0x80 + math.floor(cp / 0x1000) % 0x40,
      0x80 + math.floor(cp / 0x40) % 0x40,
      0x80 + cp % 0x40)
  end
end

-- The closed carrier-kind projection of one non-JSON value (the shared
-- JSON_STRINGIFY_UNSUPPORTED arm's actual token, the emitted prelude's
-- __stdJsonKind vocabulary): the absent marker is nil, the language-null
-- sentinel keeps its Lua type() shape table, a function carrier — the
-- runtime's function-kind wrapper and the emitted chunk's callable
-- carriers — is function, the bytes carrier is bytes, and every other
-- produced carrier (a class instance in either the runtime's class shape
-- or the emitted chunk's class shape, the builtin Error instance, a
-- decoded async handle) is table. A plain table — the JSON object/array
-- shape, whatever string keys it carries — is table too, so the
-- projection alone never selects.
local function json_carrier_kind(v)
  if v == __rt.__MISSING or v == nil then return 'nil' end
  if v == __rt.__NULL then return 'table' end
  local t = type(v)
  if t == 'table' then
    -- The numeric-variant carrier projects its recorded numeric kind, the
    -- same token the emitted prelude's __stdJsonKind publishes.
    if v[__K.jn] then return 'number' end
    local kind = v[__K.kind]
    if kind == 'bytes' then return 'bytes' end
    -- A class or Error carrier is table even when it also carries the
    -- function markers: an emitted class instance holds both the class
    -- mark and its field map, and the emitted prelude's projection reads
    -- the class mark first.
    if v[__K.c] or v[__K.d] then return 'table' end
    if v[__K.fn] ~= nil or v[__K.f] or v[__K.csig] ~= nil
        or kind == 'function' then
      return 'function'
    end
    return 'table'
  end
  return t
end

-- The closed unsupported-carrier rejection: the JSON_STRINGIFY_UNSUPPORTED
-- arm's own text with the failing value's carrier-kind token. Every
-- non-JSON carrier — the absent marker, the bytes carrier, a function
-- wrapper, a class or Error instance, a decoded async handle, a raw
-- function, and every other non-table value — renders this one
-- projection, never a silent private-field encode.
local function unsupported(v, file, line, column)
  local actual = json_carrier_kind(v)
  error(__rt._err("E8001", "unsupported type for JSON encoding: " .. actual,
    file, line, column, "string, number, boolean, or table", actual))
end

-- Escape a string for JSON output.  Handles all ASCII control characters
-- (U+0000–U+001F) as well as the required escapes for \", \\, and the
-- common whitespace escapes.
--
-- RFC 8259 section 8.1/10: generated JSON text must be UTF-8 and strictly
-- conform, so strings that are not scalar-valid UTF-8 are not encoded: an
-- invalid scalar sequence is still a string carrier and rejects through
-- the closed JSON_STRINGIFY_UNSUPPORTED arm with the string token (the
-- emitted chunk's, the JS runtime's and the JVM runtime's projection),
-- never a private text composition.
local function escape(s, file, line, column)
  if not __rt.utf8_valid(s) then
    unsupported(s, file, line, column)
  end
  return (string.gsub(s, '[%c\\"]', function(c)
    local byte = string.byte(c)
    if c == '"'  then return '\\"'
    elseif c == '\\' then return '\\\\'
    elseif c == '\b' then return '\\b'
    elseif c == '\f' then return '\\f'
    elseif c == '\n' then return '\\n'
    elseif c == '\r' then return '\\r'
    elseif c == '\t' then return '\\t'
    else
      -- Other control character: encode as \u00XX
      return string.format('\\u%04x', byte)
    end
  end))
end

-- The exact decimal classification of one grammar-valid RFC-8259
-- numeric lexeme (DEAL v1.3): a token denotes the signed32 integer value
-- exactly when its mathematical decimal value is integral and inside
-- [-2147483648, 2147483647]; every other token denotes the binary64
-- value. Coefficient digits, fraction scale, and exponent combine with
-- bounded magnitude comparisons (a very long exponent saturates), so no
-- machine overflow and no input cap can change the decision; -0/-0.0
-- normalize to the integer 0.
local function json_int_or_nil(text)
  local i = 1
  local neg = false
  if string.sub(text, 1, 1) == "-" then neg = true; i = 2 end
  local int_start = i
  while i <= #text do
    local b = string.byte(text, i)
    if b < 48 or b > 57 then break end
    i = i + 1
  end
  local int_end = i - 1
  local frac_start, frac_end = nil, nil
  if i <= #text and string.sub(text, i, i) == "." then
    i = i + 1
    frac_start = i
    while i <= #text do
      local b = string.byte(text, i)
      if b < 48 or b > 57 then break end
      i = i + 1
    end
    frac_end = i - 1
  end
  local expo = 0
  local exp_neg = false
  if i <= #text then
    local e = string.sub(text, i, i)
    if e == "e" or e == "E" then
      i = i + 1
      local c = string.sub(text, i, i)
      if c == "+" or c == "-" then
        exp_neg = (c == "-")
        i = i + 1
      end
      -- The exponent magnitude accumulates its digits with bounded
      -- saturation: leading zeroes contribute nothing, so an exponent
      -- text longer than 18 characters whose value is small stays exact
      -- (the shared JsonNumberClassifier rule), and the arithmetic can
      -- never overflow.
      local magnitude = 0
      while i <= #text do
        local d = string.byte(text, i) - 48
        if magnitude > (1000000000000 - d) / 10 then
          magnitude = 1000000000000
        else
          magnitude = magnitude * 10 + d
        end
        i = i + 1
      end
      expo = magnitude
    end
  end
  local digits = string.sub(text, int_start, int_end)
  if frac_start ~= nil then
    digits = digits .. string.sub(text, frac_start, frac_end)
  end
  local stripped = string.gsub(digits, "^0+", "")
  if stripped == "" then return 0 end
  local trailing = 0
  while string.sub(stripped, #stripped - trailing, #stripped - trailing) == "0" do
    trailing = trailing + 1
  end
  local significant = string.sub(stripped, 1, #stripped - trailing)
  local frac_len = 0
  if frac_start ~= nil then frac_len = frac_end - frac_start + 1 end
  local effective = expo - frac_len + trailing
  if exp_neg then effective = -expo - frac_len + trailing end
  if effective < 0 then return nil end
  if #significant + effective > 10 then return nil end
  local value = tonumber(significant .. string.rep("0", effective))
  if value == nil then return nil end
  -- The signed32 range check compares the unsigned magnitude before the
  -- sign is applied: negating first would leave the negative branch dead
  -- for every magnitude in [2147483649, ...] (the shared classifier
  -- rule: at most 2147483648 may carry the minus sign).
  local limit = neg and 2147483648 or 2147483647
  if value > limit then return nil end
  if neg then value = -value end
  return value
end

-- The canonical decimal spelling (Java Double.toString parity) of one
-- finite binary64 value: the shortest round-tripping digit string, the
-- closest such decimal, and the even last digit when the value is exactly
-- equidistant — the same spelling the emitted prelude (__sfNumText), the
-- JVM JvmJson.numberText and the JS $numText publish. The JSON stringify
-- walker uses it for a number-variant slot and a number-variant carrier,
-- so an int-variant value keeps its integer spelling and a number-variant
-- value keeps one fractional digit (1.0, not 1) — the recorded variant
-- survives the text. Plain unmarked numbers keep the module's own
-- spelling (the existing %.17g encoding).
local function json_num_parts(rep)
  local mant, e = string.match(rep, "^(%d+%.?%d*)e([%+%-]?%d+)$")
  if mant then
    local point = string.find(mant, ".", 1, true)
    local intLen = point and (point - 1) or #mant
    local digits = string.gsub(mant, "%.", "")
    return digits, tonumber(e) - (#digits - intLen)
  end
  local point = string.find(rep, ".", 1, true)
  local digits
  local expo
  if point then
    digits = string.gsub(rep, "%.", "")
    expo = point - #rep
  else
    digits = rep
    expo = 0
  end
  digits = string.gsub(digits, "^0+", "")
  if digits == "" then digits = "0" end
  return digits, expo
end
local function json_num_bump(digits, up)
  local n = #digits
  local d = {}
  for i = 1, n do d[i] = tonumber(string.sub(digits, i, i)) end
  if up then
    local i = n
    while i >= 1 and d[i] == 9 do d[i] = 0; i = i - 1 end
    if i == 0 then return "1" end
    d[i] = d[i] + 1
  else
    local i = n
    while i >= 1 and d[i] == 0 do d[i] = 9; i = i - 1 end
    if i == 0 then return "9" end
    d[i] = d[i] - 1
  end
  local s = ""
  for i = 1, n do s = s..tostring(d[i]) end
  return s
end
local function json_num_dec(digits, expo)
  if #digits == 1 then return digits.."e"..expo end
  return string.sub(digits, 1, 1).."."..string.sub(digits, 2)
    .."e"..(expo + #digits - 1)
end
local function json_num_exp_digits(v)
  local mant = string.match(string.format("%.40e", v), "^(%d+%.?%d*)e")
  return (string.gsub(mant or "", "%.", ""))
end
local function json_num_digits(v, N)
  local rep = string.format("%."..N.."g", v)
  local digits, expo = json_num_parts(rep)
  local pad = N - #digits
  if pad > 0 then
    digits = digits..string.rep("0", pad)
    expo = expo - pad
  end
  if tonumber(json_num_dec(digits, expo)) ~= v then
    -- The correctly rounded decimal lies outside the rounding interval:
    -- the candidate is its in-interval neighbour.
    local up = json_num_bump(digits, true)
    if tonumber(json_num_dec(up, expo)) == v then return up, expo end
    local dn = json_num_bump(digits, false)
    if tonumber(json_num_dec(dn, expo)) == v then return dn, expo end
    return nil
  end
  local exp = json_num_exp_digits(v)
  local dn = json_num_bump(digits, false)
  local dnMid = string.gsub(dn.."5", "^0+", "")
  if string.sub(exp, 1, #dnMid) == dnMid
      and string.gsub(string.sub(exp, #dnMid + 1), "0", "") == ""
      and tonumber(json_num_dec(dn, expo)) == v then
    if tonumber(string.sub(dn, #dn, #dn)) % 2 == 0 then return dn, expo end
    return digits, expo
  end
  local up = json_num_bump(digits, true)
  local upMid = digits.."5"
  if string.sub(exp, 1, #upMid) == upMid
      and string.gsub(string.sub(exp, #upMid + 1), "0", "") == ""
      and tonumber(json_num_dec(up, expo)) == v then
    if tonumber(string.sub(up, #up, #up)) % 2 == 0 then return up, expo end
    return digits, expo
  end
  return digits, expo
end
local function json_number_text(v)
  local neg = false
  if v < 0 or (v == 0 and 1 / v < 0) then neg = true; v = -v end
  if v == 0 then return neg and "-0.0" or "0.0" end
  local digits, expo = nil, nil
  for N = 2, 17 do
    digits, expo = json_num_digits(v, N)
    if digits ~= nil then break end
  end
  if digits == nil then digits, expo = json_num_parts(string.format("%.17g", v)) end
  digits = string.gsub(digits, "^0+", "")
  if digits == "" then digits = "0" end
  local trail = 0
  while #digits > 1 and string.sub(digits, #digits, #digits) == "0" do
    digits = string.sub(digits, 1, #digits - 1)
    trail = trail + 1
  end
  local k = expo + trail + #digits - 1
  local ds = digits
  local text
  if k >= -3 and k <= 6 then
    if k >= 0 then
      if #ds <= k + 1 then
        text = ds..string.rep("0", k + 1 - #ds)..".0"
      else
        text = string.sub(ds, 1, k + 1).."."..string.sub(ds, k + 2)
      end
    else
      text = "0."..string.rep("0", -k - 1)..ds
    end
  else
    local tail = string.sub(ds, 1, 1)
    if #ds > 1 then tail = tail.."."..string.sub(ds, 2) else tail = tail..".0" end
    text = tail.."E"..tostring(k)
  end
  if neg then text = "-"..text end
  return text
end

-- The decoded-array mark: every array the parser completes carries the
-- private array carrier pair the emitted chunk and the LuaJIT host
-- projection use — the array mark __K.a and the array's logical length
-- __K.n — so a decoded array keeps its array shape in the JSON-shaped
-- table representation at every depth. An empty array is therefore a
-- marked zero-length array, never the {} a mark-less table collapses
-- into, and the encoder below reads exactly the marks the chunk writes
-- (a host-projected declared T[] carrier and a chunk-constructed array
-- carry them too). The marks are table-object keys, so no user key and no
-- decoded JSON member name can shadow or forge one.
local function mark_decoded_array(arr, length)
  arr[__K.a] = true
  arr[__K.n] = length
  return arr
end

-- The stack-safe walk: an explicit frame stack replaces the recursion, so
-- a finite nested value has no Lua call-stack bound; the path set keeps
-- the path-local cycle guard (entered on open, removed on close).
--
-- The walk is carrier-complete, exactly the emitted chunk's JSON_STRINGIFY
-- walk: a numeric-variant carrier ({[__K.jn] = true, k = "int"|"number",
-- d = value}) renders the number it carries with its recorded variant
-- spelling; the chunk table carrier ([__K.t] with [__K.keys]/[__K.order])
-- renders exactly its user string keys in the recorded insertion order;
-- the array carrier ([__K.a]/[__K.n]) renders its elements; and the
-- per-slot number marks ([__K.nK]) select the number-variant spelling for
-- the marked slot. No private table-object key is ever enumerated as an
-- object member. Unmarked tables keep the numeric-key inference and the
-- existing object encoding; the unsupported/cycle behavior is unchanged.
-- Only the language-null carrier __rt.__NULL renders 'null': a raw nil is
-- the missing value in every frame and takes the closed unsupported arm.
local function encode_value(v, file, line, column, path)
  if type(path) ~= "table" then path = {} end
  local out = {}
  local frames = {}
  local emit = function(val, isNumber)
    if val == __rt.__NULL then
      out[#out + 1] = 'null'
      return
    end
    -- A raw nil has no JSON encoding: __rt.__NULL is the only null
    -- carrier. An absent value is the missing slot — the marked decoded
    -- array with a deleted element and the inferred sparse table alike —
    -- so it reaches the closed unsupported-carrier arm below with the
    -- nil token, exactly the emitted chunk's missing-slot rejection,
    -- instead of fabricating a present 'null'.
    --
    -- The internal absent marker is never JSON-shaped either (the shared
    -- walk's nil carrier token), never an empty object.
    if val == __rt.__MISSING then
      unsupported(val, file, line, column)
    end
    local t = type(val)
    if t == 'string' then
      out[#out + 1] = '"' .. escape(val, file, line, column) .. '"'
      return
    elseif t == 'number' then
      -- A nonfinite number is not a JSON value: the closed
      -- JSON_STRINGIFY_UNSUPPORTED arm's number token (the emitted
      -- chunk's, the JS runtime's and the JVM runtime's projection),
      -- never a private NaN/Infinity message.
      if val ~= val then
        unsupported(val, file, line, column)
      end
      if val == math.huge or val == -math.huge then
        unsupported(val, file, line, column)
      end
      -- A marked number-variant slot keeps the closed decimal spelling;
      -- an unmarked number keeps the module's existing %.17g encoding.
      if isNumber then
        out[#out + 1] = json_number_text(val)
      else
        out[#out + 1] = string.format('%.17g', val)
      end
      return
    elseif t == 'boolean' then
      out[#out + 1] = val and 'true' or 'false'
      return
    elseif t == 'table' then
      -- The numeric-variant carrier is the number it carries: the decoded
      -- JSON graph's numeric leaf and the read-side variant materialization
      -- hold this shape, and the int/number variant decides the spelling
      -- (the int variant is the integer text, the number variant the
      -- canonical decimal text with its fractional digit). The carrier is
      -- never enumerated as an object holding k/d.
      if val[__K.jn] then
        local carried = val.d
        if type(carried) ~= 'number' then
          unsupported(val, file, line, column)
        end
        if carried ~= carried then
          unsupported(carried, file, line, column)
        end
        if carried == math.huge or carried == -math.huge then
          unsupported(carried, file, line, column)
        end
        if val.k == 'int' then
          out[#out + 1] = tostring(carried)
        else
          out[#out + 1] = json_number_text(carried)
        end
        return
      end
      -- The closed carrier rejection (the emitted chunk's sfValue arm order):
      -- a bytes carrier, a function wrapper, a class instance, the builtin
      -- Error instance, and a decoded async handle are never JSON-shaped
      -- table contents — each renders the JSON_STRINGIFY_UNSUPPORTED arm
      -- with its own closed token instead of encoding private carrier
      -- fields. The projection reads the produced carrier marks only: a
      -- plain table is JSON-shaped whatever string keys it carries, so no
      -- user table key can select a carrier arm.
      local carrierKind = type(val) == 'table' and val[__K.kind] or nil
      if carrierKind == "bytes" or carrierKind == "function"
          or carrierKind == "class" or carrierKind == "async"
          or val[__K.c] or val[__K.d]
          or val[__K.fn] ~= nil or val[__K.f] or val[__K.csig] ~= nil then
        unsupported(val, file, line, column)
      end
      -- The path-local cycle guard (the walk family's closed cycle arm):
      -- a container re-entering its own active path is the E8001 cycle
      -- failure; a repeated acyclic reference is duplicated, never
      -- rejected. The active set is local per stringify call and every
      -- entry is removed on the way out.
      if path[val] then
        error(__rt._err("E8001", "cyclic value cannot be encoded as JSON", file, line, column, nil, nil))
      end
      path[val] = true
      local marks = type(val[__K.nK]) == 'table' and val[__K.nK] or nil
      -- The private array mark selects array mode before the numeric-key
      -- inference: a decoded JSON array (marked by parse, an empty array
      -- included) and the emitted chunk's own array carrier — the carrier
      -- a declared T[] host parameter crosses into DEAL as — are arrays
      -- even when they carry no numeric key, and their private marks are
      -- never encoded as object members. The recorded length is the
      -- logical length; a carrier without a usable length falls back to
      -- the table's own border, never to object mode.
      if val[__K.a] then
        local markedLen = nil
        local declared = val[__K.n]
        if type(declared) == 'number' and declared >= 0 and declared % 1 == 0 then
          markedLen = declared
        else
          markedLen = #val
        end
        out[#out + 1] = '['
        frames[#frames + 1] = {kind = 'array', value = val, index = 1, maxIdx = markedLen, marks = marks}
        return
      end
      -- The chunk table carrier's own marks ([__K.t], the presence map
      -- __K.keys and the insertion-order list __K.order — __orderAdd
      -- creates the latter two on a table the first time a chunk field
      -- write reaches it, a plain table the chunk did not construct
      -- through TABLE_NEW included): the carrier is an object over exactly
      -- its user string keys, in the recorded insertion order; a carrier
      -- without the order list keeps the string-key order. A table that
      -- carries only the recorded per-slot [__K.nK] marks takes the same
      -- object encoding, so no private mark is ever enumerated. The
      -- presence map is the present-key authority for an object carrier:
      -- the chunk's JSON_PARSE graph records a present null member as a
      -- presence/order key with no stored value, so that member is a JSON
      -- null while a raw nil outside a recorded present key stays the
      -- missing value (the array and inferred-frame rule). The private
      -- marks are table-object keys, so they can never be user keys, and
      -- they are never members of the encoded object.
      if val[__K.t] ~= nil or val[__K.keys] ~= nil
          or val[__K.order] ~= nil or val[__K.nK] ~= nil then
        local keys = {}
        local seen = {}
        local presence = type(val[__K.keys]) == 'table' and val[__K.keys] or nil
        local order = val[__K.order]
        if type(order) == 'table' then
          for i = 1, #order do
            local k = order[i]
            if type(k) == 'string' and not seen[k]
                and (val[k] ~= nil or (presence ~= nil and presence[k] == true)) then
              keys[#keys + 1] = k
              seen[k] = true
            end
          end
        end
        for k, _ in pairs(val) do
          if type(k) == 'string' and not seen[k] then
            keys[#keys + 1] = k
            seen[k] = true
          end
        end
        if presence ~= nil then
          for k, present in pairs(presence) do
            if type(k) == 'string' and present == true and not seen[k] then
              keys[#keys + 1] = k
              seen[k] = true
            end
          end
        end
        out[#out + 1] = '{'
        frames[#frames + 1] = {kind = 'object', value = val, keys = keys,
          presence = presence, index = 1, marks = marks}
        return
      end
      -- Check if it's an array (sequential integer keys starting at 1)
      local isArray = true
      local maxIdx = 0
      for k, _ in pairs(val) do
        if type(k) ~= 'number' or k < 1 or k ~= math.floor(k) then
          isArray = false
          break
        end
        if k > maxIdx then maxIdx = k end
      end
      if isArray and maxIdx > 0 then
        out[#out + 1] = '['
        frames[#frames + 1] = {kind = 'array', value = val, index = 1, maxIdx = maxIdx}
        return
      end
      local keys = {}
      for k, _ in pairs(val) do keys[#keys + 1] = k end
      out[#out + 1] = '{'
      frames[#frames + 1] = {kind = 'object', value = val, keys = keys, index = 1}
      return
    end
    -- Unsupported types: reject with runtime error
    unsupported(val, file, line, column)
  end
  emit(v, false)
  while #frames > 0 do
    local frame = frames[#frames]
    if frame.kind == 'array' then
      if frame.index > frame.maxIdx then
        out[#out + 1] = ']'
        path[frame.value] = nil
        frames[#frames] = nil
      else
        local i = frame.index
        frame.index = i + 1
        if i > 1 then out[#out + 1] = ',' end
        emit(frame.value[i], frame.marks ~= nil and frame.marks[i] == true)
      end
    else
      if frame.index > #frame.keys then
        out[#out + 1] = '}'
        path[frame.value] = nil
        frames[#frames] = nil
      else
        local k = frame.keys[frame.index]
        frame.index = frame.index + 1
        if frame.index > 2 then out[#out + 1] = ',' end
        -- Include both string and numeric keys; numeric keys are
        -- converted to strings via tostring / JSON-compatible formatting.
        local key_str
        if type(k) == 'string' then
          key_str = '"' .. escape(k, file, line, column) .. '"'
        elseif type(k) == 'number' then
          key_str = '"' .. string.format('%.17g', k) .. '"'
        else
          key_str = '"' .. escape(tostring(k), file, line, column) .. '"'
        end
        out[#out + 1] = key_str .. ':'
        local element = frame.value[k]
        if element == nil and frame.presence ~= nil
            and frame.presence[k] == true then
          -- A recorded present null member: the chunk's JSON_PARSE graph
          -- keeps it as a presence/order key with no stored value.
          out[#out + 1] = 'null'
        else
          emit(element, frame.marks ~= nil and frame.marks[k] == true)
        end
      end
    end
  end
  return table.concat(out)
end

--- Encode a value to a JSON string. The trailing span triplet carries
-- the call site (ISSUE-0598), so every encode-side E8001 (function,
-- bytes, NaN/Infinity, invalid UTF-8 leaves) reports it byte-exact.
json.stringify = __rt.function_("(table)->string", function(v, file, line, column)
  __rt.check_table(v, file, line, column)
  return encode_value(v, file, line, column, {})
end)

--- Decode a JSON string to a Lua table. The trailing span triplet
-- carries the call site (ISSUE-0598): the entry string gate, every
-- parse error, and the declared (string)->table exit report it.
json.parse = __rt.function_("(string)->table", function(s, file, line, column)
  __rt.check_string(s, file, line, column)
  -- Simple recursive descent parser
  local pos = 1
  local len = #s

  -- Report a parse error at the current position, or at an explicit
  -- position (the duplicate-key defect reports the second key's opening
  -- quote).
  local function parse_error_at(where, msg)
    error(__rt._err("E8001", "JSON parse error at position " .. where .. ": " .. msg,
      file, line, column, nil, nil))
  end
  local function parse_error(msg)
    parse_error_at(pos, msg)
  end

  -- Read four characters as hexadecimal digits; the first result is the
  -- value (nil when any character is not [0-9A-Fa-f]).
  local function read_hex4(hex)
    local v = 0
    for i = 1, 4 do
      local d = string.byte(hex, i)
      local dv
      if d >= 48 and d <= 57 then dv = d - 48
      elseif d >= 65 and d <= 70 then dv = d - 55
      elseif d >= 97 and d <= 102 then dv = d - 87
      else
        return nil
      end
      v = v * 16 + dv
    end
    return v
  end

  local function peek()
    if pos > len then return nil end
    return s:sub(pos, pos)
  end

  local function read_char()
    if pos > len then return nil end
    local c = s:sub(pos, pos)
    pos = pos + 1
    return c
  end

  local function expect_char(expected)
    local c = read_char()
    if c ~= expected then
      parse_error("expected '" .. expected .. "', got '" .. (c or "EOF") .. "'")
    end
    return c
  end

  local function expect_literal(word)
    for i = 1, #word do
      local c = read_char()
      if c ~= word:sub(i, i) then
        parse_error("expected '" .. word .. "', got unexpected character '" .. (c or "EOF") .. "'")
      end
    end
  end

  local skip_ws, parse_value, parse_string, parse_number

  function skip_ws()
    while pos <= len do
      local c = s:sub(pos, pos)
      if c == ' ' or c == '\n' or c == '\r' or c == '\t' then
        pos = pos + 1
      else
        break
      end
    end
  end

  -- One value at the cursor: a scalar returns directly, a container opens
  -- a frame on the stack and returns the __OPENED sentinel. The frame loop
  -- of parse_value then drives the open container.
  local __OPENED = {}
  -- One value at the cursor: a scalar returns its value and its recorded
  -- int/number variant, a container opens a frame on the stack and
  -- returns the __OPENED sentinel (a container carries no number mark of
  -- its own; its slots record their own).
  local function parse_scalar_or_open(frames)
    skip_ws()
    if pos > len then
      parse_error("unexpected end of input")
    end
    local c = s:sub(pos, pos)

    if c == '{' then
      pos = pos + 1
      frames[#frames + 1] = {kind = 'object', obj = {}, seen = {}, state = 0}
      return __OPENED, false
    elseif c == '[' then
      pos = pos + 1
      frames[#frames + 1] = {kind = 'array', arr = {}, state = 4, index = 1}
      return __OPENED, false
    elseif c == '"' then
      return parse_string(), false
    elseif c == 't' then
      expect_literal("true")
      return true, false
    elseif c == 'f' then
      expect_literal("false")
      return false, false
    elseif c == 'n' then
      expect_literal("null")
      return __rt.__NULL, false
    elseif c == '-' or (c >= '0' and c <= '9') then
      return parse_number()
    else
      parse_error("unexpected character '" .. c .. "'")
    end
  end

  -- The top-level container kind, recorded when the root frame opens:
  -- the declared (string)->table exit reads it (a top-level array is the
  -- array carrier, never a table return).
  local top_kind = nil

  -- The stack-safe parse: an explicit frame stack replaces the recursive
  -- descent, so a finite nested document has no Lua call-stack bound.
  -- Every transition and defect message is the recursive parser's.
  function parse_value()
    local frames = {}
    local completed
    local completed_number = false
    local have_completed = false
    -- The per-slot variant record: a completed number-variant leaf marks
    -- its own slot in the container's private [__K.nK] map (the same map
    -- __numKey writes and the encoder reads); an int-variant leaf records
    -- nothing. The map is a table-object key, so no decoded member name
    -- can shadow it and the encoder never enumerates it.
    local function mark_number(container, slot)
      local marks = container[__K.nK]
      if type(marks) ~= 'table' then
        marks = {}
        container[__K.nK] = marks
      end
      marks[slot] = true
    end
    while true do
      if have_completed then
        if #frames == 0 then
          return completed
        end
        local parent = frames[#frames]
        if parent.kind == 'array' then
          parent.arr[parent.index] = completed
          if completed_number then
            mark_number(parent.arr, parent.index)
          end
          parent.index = parent.index + 1
          parent.state = 5
        else
          parent.obj[parent.key] = completed
          if completed_number then
            mark_number(parent.obj, parent.key)
          end
          parent.state = 3
        end
        completed = nil
        completed_number = false
        have_completed = false
      elseif #frames == 0 then
        local parsed, is_number = parse_scalar_or_open(frames)
        if parsed ~= __OPENED then
          completed = parsed
          completed_number = is_number
          have_completed = true
        else
          -- The root frame: this branch is reached only with an empty
          -- stack, so the frame just opened is the document root and its
          -- kind is recorded exactly once (nested frames keep the stack
          -- non-empty).
          top_kind = frames[#frames].kind
        end
      else
        local frame = frames[#frames]
        if frame.kind == 'object' then
          if frame.state == 0 then
            skip_ws()
            if peek() == '}' then
              pos = pos + 1
              completed = frame.obj
              frames[#frames] = nil
              have_completed = true
            else
              frame.state = 1
            end
          elseif frame.state == 1 then
            skip_ws()
            if peek() ~= '"' then
              parse_error("missing key")
            end
            -- The duplicate-key defect's position is the second key's
            -- opening quote; decoded equality makes an escaped spelling
            -- of the same key a duplicate too.
            local key_start = pos
            local key = parse_string()
            if frame.seen[key] then
              parse_error_at(key_start, "duplicate key")
            end
            frame.seen[key] = true
            skip_ws()
            expect_char(':')
            frame.key = key
            frame.state = 2
          elseif frame.state == 2 then
            local parsed, is_number = parse_scalar_or_open(frames)
            if parsed ~= __OPENED then
              completed = parsed
              completed_number = is_number
              have_completed = true
            end
          else
            skip_ws()
            local c = peek()
            if c == '}' then
              pos = pos + 1
              completed = frame.obj
              frames[#frames] = nil
              have_completed = true
            elseif c == ',' then
              pos = pos + 1
              frame.state = 1
            else
              parse_error("expected ',' or '}' in object, got '" .. (c or "EOF") .. "'")
            end
          end
        else
          if frame.state == 4 then
            skip_ws()
            if peek() == ']' then
              pos = pos + 1
              completed = mark_decoded_array(frame.arr, 0)
              frames[#frames] = nil
              have_completed = true
            else
              frame.state = 2
            end
          elseif frame.state == 2 then
            local parsed, is_number = parse_scalar_or_open(frames)
            if parsed ~= __OPENED then
              completed = parsed
              completed_number = is_number
              have_completed = true
            end
          else
            skip_ws()
            local c = peek()
            if c == ']' then
              pos = pos + 1
              completed = mark_decoded_array(frame.arr, frame.index - 1)
              frames[#frames] = nil
              have_completed = true
            elseif c == ',' then
              pos = pos + 1
              frame.state = 2
            else
              parse_error("expected ',' or ']' in array, got '" .. (c or "EOF") .. "'")
            end
          end
        end
      end
    end
  end

  function parse_string()
    pos = pos + 1 -- skip opening quote
    local parts = {}
    while pos <= len do
      local c = s:sub(pos, pos)
      if c == '"' then
        pos = pos + 1
        return table.concat(parts)
      elseif c == '\\' then
        pos = pos + 1
        if pos > len then
          parse_error("unterminated escape sequence in string")
        end
        local esc = s:sub(pos, pos)
        if esc == 'n' then parts[#parts+1] = '\n'
        elseif esc == 'r' then parts[#parts+1] = '\r'
        elseif esc == 't' then parts[#parts+1] = '\t'
        elseif esc == 'b' then parts[#parts+1] = '\b'
        elseif esc == 'f' then parts[#parts+1] = '\f'
        elseif esc == '\\' then parts[#parts+1] = '\\'
        elseif esc == '"' then parts[#parts+1] = '"'
        elseif esc == '/' then parts[#parts+1] = '/'
        elseif esc == 'u' then
          -- RFC 8259 section 7: \u must be followed by exactly four
          -- hexadecimal digits (case-insensitive).
          local hex = s:sub(pos+1, pos+4)
          if #hex < 4 then
            parse_error("invalid unicode escape: expected 4 hex digits")
          end
          local v = read_hex4(hex)
          if v == nil then
            parse_error("invalid unicode escape: expected 4 hex digits")
          end
          pos = pos + 4
          if v >= 0xDC00 and v <= 0xDFFF then
            -- Lone low surrogate: not a Unicode scalar value.
            parse_error("unpaired surrogate code unit in unicode escape")
          elseif v >= 0xD800 and v <= 0xDBFF then
            -- High surrogate: must combine with an immediately following
            -- \uXXXX escape in the low-surrogate range; the pair decodes
            -- to one supplementary scalar (RFC 8259 section 7).
            local combined = false
            if pos + 1 <= len and s:sub(pos+1, pos+2) == '\\u' then
              local lo_hex = s:sub(pos+3, pos+6)
              if #lo_hex == 4 then
                local lo = read_hex4(lo_hex)
                if lo ~= nil and lo >= 0xDC00 and lo <= 0xDFFF then
                  pos = pos + 6
                  parts[#parts+1] = utf8_encode(0x10000 + (v - 0xD800) * 0x400 + (lo - 0xDC00))
                  combined = true
                end
              end
            end
            if not combined then
              parse_error("unpaired surrogate code unit in unicode escape")
            end
          else
            -- BMP scalar: append its 1-3 byte UTF-8 form.
            parts[#parts+1] = utf8_encode(v)
          end
        else
          parse_error("unknown escape sequence '\\" .. esc .. "'")
        end
      else
        -- Raw character: U+0000-U+001F must be escaped (RFC 8259 section 7).
        if string.byte(c) < 0x20 then
          parse_error("raw control character in string (must be escaped)")
        end
        parts[#parts+1] = c
      end
      pos = pos + 1
    end
    parse_error("unterminated string")
  end

  function parse_number()
    local start = pos
    if s:sub(pos, pos) == '-' then pos = pos + 1 end

    -- Must have at least one digit before decimal point
    if pos > len or not s:sub(pos, pos):match('[0-9]') then
      parse_error("invalid number: expected digit")
    end
    -- Leading zero check: if first digit is '0', next char must be '.' or 'e'/'E' or end of number
    if s:sub(pos, pos) == '0' then
      pos = pos + 1
      if pos <= len then
        local nc = s:sub(pos, pos)
        if nc:match('[0-9]') then
          parse_error("invalid number: leading zero not allowed")
        end
      end
    else
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end

    if pos <= len and s:sub(pos, pos) == '.' then
      pos = pos + 1
      if pos > len or not s:sub(pos, pos):match('[0-9]') then
        parse_error("invalid number: expected digit after decimal point")
      end
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end
    if pos <= len and (s:sub(pos, pos) == 'e' or s:sub(pos, pos) == 'E') then
      pos = pos + 1
      if pos <= len and (s:sub(pos, pos) == '+' or s:sub(pos, pos) == '-') then
        pos = pos + 1
      end
      if pos > len or not s:sub(pos, pos):match('[0-9]') then
        parse_error("invalid number: expected digit in exponent")
      end
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end
    local num_str = s:sub(start, pos - 1)
    if #num_str == 0 then
      parse_error("invalid number: empty")
    end
    -- The exact decimal classification (DEAL v1.3): the lexeme's
    -- mathematical value decides the int/number variant before any
    -- binary64 rounding; -0/-0.0 normalize to the integer 0.
    local exact = json_int_or_nil(num_str)
    if exact ~= nil then
      return exact, false
    end
    local n = tonumber(num_str)
    if n == nil then
      parse_error("invalid number")
    end
    return n, true
  end

  local result = parse_value()
  skip_ws()
  if pos <= len then
    parse_error("unexpected trailing characters after JSON value")
  end
  -- The declared (string)->table exit (ISSUE-0824): the scanner's own
  -- arms fired above, so a grammar-valid document whose root is the
  -- language-null sentinel, an array, or a scalar reports the shared
  -- table-admission tuple with that carrier's own actual token — the
  -- emitted chunk's table cell and the JS runtime's checkTable publish
  -- the identical fields. Only an object root is the table return.
  if result == __rt.__NULL then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "table" }, "E8001",
      "expected table", file, line, column, "table", "null"))
  end
  if top_kind == "array" then
    error(__rt._armErr("TYPED_BOUNDARY_KIND", { kind = "table" }, "E8001",
      "expected table", file, line, column, "table", "array"))
  end
  -- A top-level scalar (a string, a number, or a boolean) is not the
  -- declared table return either: the documented wrapper exit is enforced
  -- by the same admission every caller-side table cell runs.
  return __rt.check_table(result, file, line, column)
end)

return json
