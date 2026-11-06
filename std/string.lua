local rt = require("deal.runtime")
local lib = {}

lib.length = rt.function_("(string)->number", function(s, file, line, column)
  return #rt.string_units(rt.check_string(s, file, line, column))
end)
lib.substring = rt.function_("(string,number,number)->string", function(s, a, b, file, line, column)
  rt.check_string(s, file, line, column)
  rt.check_number(a, file, line, column)
  rt.check_number(b, file, line, column)
  local units = rt.string_units(s)
  local function bound(x)
    if x ~= x or x <= 0 then return 0 end
    return math.min(#units, math.floor(x))
  end
  a, b = bound(a), bound(b)
  return rt.string_from_units(units, math.min(a, b) + 1, math.max(a, b))
end)
for name, mode in pairs({contains = 0, startsWith = 1, endsWith = 2}) do
  local selected = mode
  lib[name] = rt.function_("(string,string)->boolean", function(s, part, file, line, column)
    s = rt.string_normalize(rt.check_string(s, file, line, column))
    part = rt.string_normalize(rt.check_string(part, file, line, column))
    if selected == 0 then return s:find(part, 1, true) ~= nil end
    if selected == 1 then return s:sub(1, #part) == part end
    return part == "" or s:sub(-#part) == part
  end)
end
lib.replace = rt.function_("(string,string,string)->string", function(s, search, replacement, file, line, column)
  s = rt.string_normalize(rt.check_string(s, file, line, column))
  search = rt.string_normalize(rt.check_string(search, file, line, column))
  replacement = rt.string_normalize(rt.check_string(replacement, file, line, column))
  local at = s:find(search, 1, true)
  if not at then return s end
  local prefix, suffix = s:sub(1, at - 1), s:sub(at + #search)
  local out, i = {prefix}, 1
  while i <= #replacement do
    local c, n = replacement:sub(i, i), replacement:sub(i + 1, i + 1)
    if c == "$" and (n == "$" or n == "&" or n == "`" or n == "'") then
      if n == "$" then c = "$" elseif n == "&" then c = search
      elseif n == "`" then c = prefix else c = suffix end
      i = i + 1
    end
    out[#out + 1], i = c, i + 1
  end
  out[#out + 1] = suffix
  return table.concat(out)
end)
lib.split = rt.function_("(string,string)->[string]", function(s, sep, file, line, column)
  s = rt.string_normalize(rt.check_string(s, file, line, column))
  sep = rt.string_normalize(rt.check_string(sep, file, line, column))
  local out = {}
  if sep == "" then
    local units = rt.string_units(s)
    for i = 1, #units do out[#out + 1] = rt.string_from_units(units, i, i) end
  else
    local cursor = 1
    while true do
      local at = s:find(sep, cursor, true)
      if not at then break end
      out[#out + 1], cursor = s:sub(cursor, at - 1), at + #sep
    end
    out[#out + 1] = s:sub(cursor)
  end
  return rt.tag_array(out, "string")
end)
lib.trim = rt.function_("(string)->string", function(s, file, line, column)
  local units = rt.string_units(rt.check_string(s, file, line, column))
  local function whitespace(c)
    return c >= 9 and c <= 13 or c == 32 or c == 160 or c == 5760
      or c >= 8192 and c <= 8202 or c == 8232 or c == 8233 or c == 8239
      or c == 8287 or c == 12288 or c == 65279
  end
  local first, last = 1, #units
  while first <= last and whitespace(units[first]) do first = first + 1 end
  while last >= first and whitespace(units[last]) do last = last - 1 end
  return rt.string_from_units(units, first, last)
end)
return lib
