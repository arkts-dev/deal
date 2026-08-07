-- DEAL Standard Library: std/string
-- Provides string manipulation functions.

local __rt = require("deal.runtime")

local stringlib = {}

--- Returns the length of the string.
stringlib.length = __rt.function_("(string)->int", function(s)
  __rt.check_string(s)
  return #s
end)

--- Returns the substring from start (0-based) to the end,
--- or of the given length if len is provided.
stringlib.substring = __rt.function_("(string,int,int)->string", function(s, start, len)
  __rt.check_string(s)
  __rt.check_int(start)
  if len == nil then
    return string.sub(s, start + 1)
  end
  __rt.check_int(len)
  return string.sub(s, start + 1, start + len)
end)

--- Returns true if the string contains the given substring.
stringlib.contains = __rt.function_("(string,string)->boolean", function(s, sub)
  __rt.check_string(s)
  __rt.check_string(sub)
  return string.find(s, sub, 1, true) ~= nil
end)

--- Returns true if the string starts with the given prefix.
stringlib.startsWith = __rt.function_("(string,string)->boolean", function(s, prefix)
  __rt.check_string(s)
  __rt.check_string(prefix)
  return string.sub(s, 1, #prefix) == prefix
end)

--- Returns true if the string ends with the given suffix.
stringlib.endsWith = __rt.function_("(string,string)->boolean", function(s, suffix)
  __rt.check_string(s)
  __rt.check_string(suffix)
  return #s >= #suffix and string.sub(s, -#suffix) == suffix
end)

--- Returns the 0-based index of the first occurrence of sub, or -1.
stringlib.indexOf = __rt.function_("(string,string)->int", function(s, sub)
  __rt.check_string(s)
  __rt.check_string(sub)
  local found = string.find(s, sub, 1, true)
  if found then
    return found - 1
  end
  return -1
end)

-- Escape Lua pattern metacharacters for plain-text matching with string.gsub.
local function escape_pattern(s)
  return (string.gsub(s, "[%(%)%.%%%+%-%*%?%[%]%^%$]", "%%%1"))
end

--- Returns the string with all occurrences of old replaced by new.
--- Performs plain-text matching (not Lua pattern matching).
stringlib.replace = __rt.function_("(string,string,string)->string", function(s, old, new)
  __rt.check_string(s)
  __rt.check_string(old)
  __rt.check_string(new)
  return (string.gsub(s, escape_pattern(old), new))
end)

--- Splits the string by the given separator and returns an array.
--- Performs plain-text splitting (separator is not treated as a Lua pattern).
stringlib.split = __rt.function_("(string,string)->string[]", function(s, sep)
  __rt.check_string(s)
  __rt.check_string(sep)
  local result = {}
  if sep == "" then
    -- Special case: split into individual characters
    for i = 1, #s do
      result[#result + 1] = string.sub(s, i, i)
    end
    return result
  end
  local seplen = #sep
  local start = 1
  while true do
    local pos = string.find(s, sep, start, true)
    if not pos then
      result[#result + 1] = string.sub(s, start)
      break
    end
    result[#result + 1] = string.sub(s, start, pos - 1)
    start = pos + seplen
  end
  return result
end)

--- Returns the uppercase version of the string.
stringlib.toUpperCase = __rt.function_("(string)->string", function(s)
  __rt.check_string(s)
  return string.upper(s)
end)

--- Returns the lowercase version of the string.
stringlib.toLowerCase = __rt.function_("(string)->string", function(s)
  __rt.check_string(s)
  return string.lower(s)
end)

--- Trims whitespace from both ends of the string.
stringlib.trim = __rt.function_("(string)->string", function(s)
  __rt.check_string(s)
  return (string.gsub(s, "^%s*(.-)%s*$", "%1"))
end)

return stringlib
