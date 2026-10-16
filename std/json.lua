-- DEAL Standard Library: std/json
-- Provides JSON encoding and decoding (simple pure-Lua implementation).
--
-- Unicode conformance (v1.3, RFC 8259): parse accepts exactly the section 7
-- escape set (" \ / b f n r t u), \uXXXX must carry exactly four hex digits
-- and decodes to UTF-8, surrogate pairs combine to one supplementary scalar,
-- lone surrogates and raw control characters (U+0000-U+001F) are rejected,
-- and stringify refuses to emit strings that are not scalar-valid UTF-8.
--
-- Exact decimal classification, decoded-key duplicates, and stringify
-- shape rejection (DEAL v1.3): parse classifies the numeric lexeme's
-- exact mathematical decimal value before any binary64 rounding — an
-- integral value inside [-2147483648, 2147483647] becomes DEAL int
-- (-0/-0.0 normalize to 0) and every other lexeme becomes number; a
-- member key whose decoded scalar text equals an earlier key of the same
-- object is a parse failure ("duplicate key" at the second key's opening
-- quote). stringify raises Error on non-JSON-shaped values, including
-- bytes-kind objects (the same explicit bytes arm the JS runtime landed,
-- js-v12-int32-bytes D3) and a path-local container re-entry (the closed
-- "cyclic value cannot be encoded as JSON" arm); there is no fixed
-- nesting-depth bound. Object key order follows Lua iteration order.

local __rt = require("deal.runtime")

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

-- Escape a string for JSON output.  Handles all ASCII control characters
-- (U+0000–U+001F) as well as the required escapes for \", \\, and the
-- common whitespace escapes.
--
-- RFC 8259 section 8.1/10: generated JSON text must be UTF-8 and strictly
-- conform, so strings that are not scalar-valid UTF-8 are rejected with
-- E8001 instead of being emitted as invalid bytes.
local function escape(s, file, line, column)
  if not __rt.utf8_valid(s) then
    error(__rt._err("E8001", "cannot encode invalid UTF-8 as JSON", file, line, column, nil, nil))
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

local function encode_value(v, file, line, column, path)
  if v == __rt.__NULL then
    return 'null'
  end
  if v == nil then
    return 'null'
  end
  local t = type(v)
  if t == 'string' then
    return '"' .. escape(v, file, line, column) .. '"'
  elseif t == 'number' then
    if v ~= v then
      error(__rt._err("E8001", "cannot encode NaN as JSON", file, line, column, nil, nil))
    end
    if v == math.huge or v == -math.huge then
      error(__rt._err("E8001", "cannot encode Infinity as JSON", file, line, column, nil, nil))
    end
    return string.format('%.17g', v)
  elseif t == 'boolean' then
    return v and 'true' or 'false'
  elseif t == 'table' then
    -- v1.2 D5 shape rejection: a bytes-kind object is not jsonable —
    -- stringify raises the same unsupported-type Error every other
    -- non-JSON-shaped value raises (spec §JSON serialization "Otherwise,
    -- it raises Error"; the explicit bytes arm mirrors the JS runtime's
    -- landed arm, js-v12-int32-bytes D3, message byte-identical).
    if v.__kind == "bytes" then
      error(__rt._err("E8001", "unsupported type for JSON encoding: bytes", file, line, column, "string, number, boolean, or table", "bytes"))
    end
    -- The path-local cycle guard (the walk family's closed cycle arm): a
    -- container re-entering its own active path is the E8001 cycle
    -- failure; a repeated acyclic reference is duplicated, never
    -- rejected. The active set is local per stringify call and every
    -- entry is removed on the way out.
    if type(path) ~= "table" then path = {} end
    if path[v] then
      error(__rt._err("E8001", "cyclic value cannot be encoded as JSON", file, line, column, nil, nil))
    end
    path[v] = true
    -- Check if it's an array (sequential integer keys starting at 1)
    local isArray = true
    local maxIdx = 0
    for k, _ in pairs(v) do
      if type(k) ~= 'number' or k < 1 or k ~= math.floor(k) then
        isArray = false
        break
      end
      if k > maxIdx then maxIdx = k end
    end
    if isArray and maxIdx > 0 then
      local parts = {}
      for i = 1, maxIdx do
        parts[#parts + 1] = encode_value(v[i], file, line, column, path)
      end
      path[v] = nil
      return '[' .. table.concat(parts, ',') .. ']'
    else
      local parts = {}
      for k, val in pairs(v) do
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
        parts[#parts + 1] = key_str .. ':' .. encode_value(val, file, line, column, path)
      end
      path[v] = nil
      return '{' .. table.concat(parts, ',') .. '}'
    end
  end
  -- Unsupported types: reject with runtime error
  error(__rt._err("E8001", "unsupported type for JSON encoding: " .. t, file, line, column, "string, number, boolean, or table", t))
end

--- Encode a value to a JSON string. The trailing span triplet carries
-- the call site (ISSUE-0598), so every encode-side E8001 (function,
-- bytes, NaN/Infinity, invalid UTF-8 leaves) reports it byte-exact.
json.stringify = __rt.function_("(table)->string", function(v, file, line, column)
  __rt.check_table(v, file, line, column)
  return encode_value(v, file, line, column, {})
end)

--- Decode a JSON string to a Lua table. The trailing span triplet
-- carries the call site (ISSUE-0598): the entry string gate and every
-- parse error report it.
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

  local skip_ws, parse_value, parse_string, parse_number, parse_object, parse_array

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

  function parse_value()
    skip_ws()
    if pos > len then
      parse_error("unexpected end of input")
    end
    local c = s:sub(pos, pos)

    if c == '{' then
      return parse_object()
    elseif c == '[' then
      return parse_array()
    elseif c == '"' then
      return parse_string()
    elseif c == 't' then
      expect_literal("true")
      return true
    elseif c == 'f' then
      expect_literal("false")
      return false
    elseif c == 'n' then
      expect_literal("null")
      return __rt.__NULL
    elseif c == '-' or (c >= '0' and c <= '9') then
      return parse_number()
    else
      parse_error("unexpected character '" .. c .. "'")
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
      return exact
    end
    local n = tonumber(num_str)
    if n == nil then
      parse_error("invalid number")
    end
    return n
  end

  function parse_object()
    pos = pos + 1 -- skip {
    local obj = {}
    local seen = {}
    skip_ws()
    if peek() == '}' then
      pos = pos + 1
      return obj
    end
    while true do
      skip_ws()
      if peek() ~= '"' then
        parse_error("missing key")
      end
      -- The duplicate-key defect's position is the second key's opening
      -- quote; decoded equality makes an escaped spelling of the same key
      -- a duplicate too.
      local key_start = pos
      local key = parse_string()
      if seen[key] then
        parse_error_at(key_start, "duplicate key")
      end
      seen[key] = true
      skip_ws()
      expect_char(':')
      obj[key] = parse_value()
      skip_ws()
      local c = peek()
      if c == '}' then
        pos = pos + 1
        return obj
      elseif c == ',' then
        pos = pos + 1
      else
        parse_error("expected ',' or '}' in object, got '" .. (c or "EOF") .. "'")
      end
    end
  end

  function parse_array()
    pos = pos + 1 -- skip [
    local arr = {}
    skip_ws()
    if peek() == ']' then
      pos = pos + 1
      return arr
    end
    local idx = 1
    while true do
      arr[idx] = parse_value()
      idx = idx + 1
      skip_ws()
      local c = peek()
      if c == ']' then
        pos = pos + 1
        return arr
      elseif c == ',' then
        pos = pos + 1
      else
        parse_error("expected ',' or ']' in array, got '" .. (c or "EOF") .. "'")
      end
    end
  end

  local result = parse_value()
  skip_ws()
  if pos <= len then
    parse_error("unexpected trailing characters after JSON value")
  end
  return result
end)

return json
