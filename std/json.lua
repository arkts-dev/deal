-- DEAL Standard Library: std/json
-- Provides JSON encoding and decoding (simple pure-Lua implementation).

local __rt = require("deal.runtime")

local json = {}

local function escape(s)
  return (string.gsub(s, '[%c\\"\b\f\n\r\t]', {
    ['\b'] = '\\b',
    ['\f'] = '\\f',
    ['\n'] = '\\n',
    ['\r'] = '\\r',
    ['\t'] = '\\t',
    ['"']  = '\\"',
    ['\\'] = '\\\\',
  }))
end

local function encode_value(v)
  if v == nil then
    return 'null'
  end
  local t = type(v)
  if t == 'string' then
    return '"' .. escape(v) .. '"'
  elseif t == 'number' then
    if v ~= v then return 'null' end -- NaN
    if v == math.huge or v == -math.huge then return 'null' end
    return string.format('%.17g', v)
  elseif t == 'boolean' then
    return v and 'true' or 'false'
  elseif t == 'table' then
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
        parts[#parts + 1] = encode_value(v[i])
      end
      return '[' .. table.concat(parts, ',') .. ']'
    else
      local parts = {}
      for k, val in pairs(v) do
        if type(k) == 'string' then
          parts[#parts + 1] = '"' .. escape(k) .. '":' .. encode_value(val)
        end
      end
      return '{' .. table.concat(parts, ',') .. '}'
    end
  end
  return 'null'
end

--- Encode a value to a JSON string.
json.stringify = __rt.function_("(table)->string", function(v)
  __rt.check_table(v)
  return encode_value(v)
end)

--- Decode a JSON string to a Lua table.
json.parse = __rt.function_("(string)->table", function(s)
  __rt.check_string(s)
  -- Simple recursive descent parser
  local pos = 1
  local len = #s

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
    if pos > len then return nil end
    local c = s:sub(pos, pos)

    if c == '{' then
      return parse_object()
    elseif c == '[' then
      return parse_array()
    elseif c == '"' then
      return parse_string()
    elseif c == 't' then
      pos = pos + 4
      return true
    elseif c == 'f' then
      pos = pos + 5
      return false
    elseif c == 'n' then
      pos = pos + 4
      return nil
    else
      return parse_number()
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
        if pos > len then break end
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
          local hex = s:sub(pos+1, pos+4)
          pos = pos + 4
          parts[#parts+1] = string.char(tonumber(hex, 16))
        else
          parts[#parts+1] = esc
        end
      else
        parts[#parts+1] = c
      end
      pos = pos + 1
    end
    return table.concat(parts)
  end

  function parse_number()
    local start = pos
    if s:sub(pos, pos) == '-' then pos = pos + 1 end
    while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    if pos <= len and s:sub(pos, pos) == '.' then
      pos = pos + 1
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end
    if pos <= len and (s:sub(pos, pos) == 'e' or s:sub(pos, pos) == 'E') then
      pos = pos + 1
      if pos <= len and (s:sub(pos, pos) == '+' or s:sub(pos, pos) == '-') then
        pos = pos + 1
      end
      while pos <= len and s:sub(pos, pos):match('[0-9]') do pos = pos + 1 end
    end
    return tonumber(s:sub(start, pos - 1))
  end

  function parse_object()
    pos = pos + 1 -- skip {
    local obj = {}
    skip_ws()
    if s:sub(pos, pos) == '}' then
      pos = pos + 1
      return obj
    end
    while true do
      skip_ws()
      local key = parse_string()
      skip_ws()
      if s:sub(pos, pos) == ':' then pos = pos + 1 end
      obj[key] = parse_value()
      skip_ws()
      if s:sub(pos, pos) == '}' then
        pos = pos + 1
        return obj
      end
      if s:sub(pos, pos) == ',' then pos = pos + 1 end
    end
  end

  function parse_array()
    pos = pos + 1 -- skip [
    local arr = {}
    skip_ws()
    if s:sub(pos, pos) == ']' then
      pos = pos + 1
      return arr
    end
    local idx = 1
    while true do
      arr[idx] = parse_value()
      idx = idx + 1
      skip_ws()
      if s:sub(pos, pos) == ']' then
        pos = pos + 1
        return arr
      end
      if s:sub(pos, pos) == ',' then pos = pos + 1 end
    end
  end

  return parse_value()
end)

return json
