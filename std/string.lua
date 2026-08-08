-- DEAL Standard Library: std/string
-- Provides string manipulation functions.

local __rt = require("deal.runtime")

local stringlib = {}

--- Returns the length of the string.
stringlib.len = __rt.function_("(string)->int", function(s)
  __rt.check_string(s)
  return __rt.check_int(#s)
end)

--- Returns the substring from start to end (both 0-based, inclusive on the
-- left, exclusive on the right per Lua convention).  Delegates to Lua's
-- string.sub with 1-based translation.
stringlib.sub = __rt.function_("(string,int,int)->string", function(s, start, end_)
  __rt.check_string(s)
  __rt.check_int(start)
  __rt.check_int(end_)
  -- Lua string.sub uses 1-based indexing and inclusive end.
  -- DEAL uses 0-based indexing.  The 'end' parameter in DEAL is the
  -- position of the last character + 1 (i.e. exclusive), matching the
  -- typical substring convention where end is exclusive.
  -- Convert: Lua sub(s, start+1, end)
  return __rt.check_string(string.sub(s, start + 1, end_))
end)

--- Finds the first occurrence of 'pattern' in 's' using Lua pattern matching.
-- Returns the 0-based start index, or null if not found.
stringlib.find = __rt.function_("(string,string)->int|null", function(s, pattern)
  __rt.check_string(s)
  __rt.check_string(pattern)
  local found = string.find(s, pattern)
  if found then
    return found - 1  -- convert to 0-based
  end
  return __rt.__NULL
end)

--- Concatenates two strings.
stringlib.concat = __rt.function_("(string,string)->string", function(a, b)
  __rt.check_string(a)
  __rt.check_string(b)
  return __rt.check_string(a .. b)
end)

return stringlib
