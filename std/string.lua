-- DEAL Standard Library: std/string
-- Provides string manipulation functions.

local __rt = require("deal.runtime")

local stringlib = {}

--- Returns the length of the string.
function stringlib.length(s)
  __rt.check_string(s)
  return #s
end

--- Returns the substring from start (1-based) to the end.
function stringlib.substring(s, start)
  __rt.check_string(s)
  __rt.check_int(start)
  return string.sub(s, start + 1)
end

--- Returns the substring from start (1-based) with given length.
function stringlib.substring(s, start, len)
  __rt.check_string(s)
  __rt.check_int(start)
  __rt.check_int(len)
  return string.sub(s, start + 1, start + len)
end

--- Returns true if the string contains the given substring.
function stringlib.contains(s, sub)
  __rt.check_string(s)
  __rt.check_string(sub)
  return string.find(s, sub, 1, true) ~= nil
end

--- Returns true if the string starts with the given prefix.
function stringlib.startsWith(s, prefix)
  __rt.check_string(s)
  __rt.check_string(prefix)
  return string.sub(s, 1, #prefix) == prefix
end

--- Returns true if the string ends with the given suffix.
function stringlib.endsWith(s, suffix)
  __rt.check_string(s)
  __rt.check_string(suffix)
  return #s >= #suffix and string.sub(s, -#suffix) == suffix
end

--- Returns the 0-based index of the first occurrence of sub, or -1.
function stringlib.indexOf(s, sub)
  __rt.check_string(s)
  __rt.check_string(sub)
  local found = string.find(s, sub, 1, true)
  if found then
    return found - 1
  end
  return -1
end

--- Returns the string with all occurrences of old replaced by new.
function stringlib.replace(s, old, new)
  __rt.check_string(s)
  __rt.check_string(old)
  __rt.check_string(new)
  return (string.gsub(s, old, new))
end

--- Splits the string by the given separator and returns an array.
function stringlib.split(s, sep)
  __rt.check_string(s)
  __rt.check_string(sep)
  local result = {}
  local pattern = "(.-)" .. sep
  local last = 1
  for match in string.gmatch(s .. sep, pattern) do
    result[#result + 1] = match
  end
  return result
end

--- Returns the uppercase version of the string.
function stringlib.toUpperCase(s)
  __rt.check_string(s)
  return string.upper(s)
end

--- Returns the lowercase version of the string.
function stringlib.toLowerCase(s)
  __rt.check_string(s)
  return string.lower(s)
end

--- Trims whitespace from both ends of the string.
function stringlib.trim(s)
  __rt.check_string(s)
  return (string.gsub(s, "^%s*(.-)%s*$", "%1"))
end

return stringlib
