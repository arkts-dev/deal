-- DEAL Standard Library: std/string — v1.2
-- Provides string manipulation functions per the v1.2 spec.
-- String lengths and positions are measured in Unicode scalar values.

local __rt = require("deal.runtime")

local stringlib = {}

--- Escape Lua pattern magic characters so the string can be used
-- as a literal in string.gsub (which lacks a plain-text flag).
local function escape_pattern(s)
  return (s:gsub("([%^%$%(%)%%%.%[%]%*%+%-%?])", "%%%1"))
end

--- Escape Lua gsub replacement magic characters (%0–%9, %%).
-- gsub interprets % sequences in the replacement string,
-- so a caller-supplied "to" parameter must be escaped to
-- prevent unintended substitutions.
local function escape_replacement(s)
  return (s:gsub("%%", "%%%%"))
end

--- length(s: string): int
-- Returns the number of Unicode scalar values in the string (v1.2).
stringlib.length = __rt.function_("(string)->int", function(s)
  __rt.check_string(s)
  local count = 0
  local cursor = 0
  while true do
    local next_cursor = __rt.utf8_next(s, cursor)
    if next_cursor == nil then break end
    count = count + 1
    cursor = next_cursor
  end
  return __rt.check_int(count)
end)

--- substring(s: string, start: int, end: int): string
-- Returns the substring from start (0-based, inclusive) to end (exclusive),
-- with positions measured in Unicode scalar values (v1.2).
-- start > end, start beyond the string, or end <= 0 produce the empty
-- string; out-of-range end clamps to the string end.
stringlib.substring = __rt.function_("(string,int,int)->string", function(s, start, end_)
  __rt.check_string(s)
  __rt.check_int(start)
  __rt.check_int(end_)
  -- Walk scalar values to translate scalar positions to byte offsets.
  local cursor = 0
  local pos = 0
  while pos < start do
    local next_cursor = __rt.utf8_next(s, cursor)
    if next_cursor == nil then break end
    cursor = next_cursor
    pos = pos + 1
  end
  local byte_start = cursor + 1
  while pos < end_ do
    local next_cursor = __rt.utf8_next(s, cursor)
    if next_cursor == nil then break end
    cursor = next_cursor
    pos = pos + 1
  end
  local byte_end = cursor
  return __rt.check_string(string.sub(s, byte_start, byte_end))
end)

--- contains(s: string, part: string): boolean
-- Returns true if part is a substring of s (plain-text match, no pattern).
stringlib.contains = __rt.function_("(string,string)->boolean", function(s, part)
  __rt.check_string(s)
  __rt.check_string(part)
  -- Use plain-text matching (4th arg to string.find)
  local found = string.find(s, part, 1, true)
  return __rt.check_boolean(found ~= nil)
end)

--- startsWith(s: string, part: string): boolean
-- Returns true if s starts with part.
stringlib.startsWith = __rt.function_("(string,string)->boolean", function(s, part)
  __rt.check_string(s)
  __rt.check_string(part)
  -- Empty part is a prefix of any string
  if #part == 0 then
    return __rt.check_boolean(true)
  end
  return __rt.check_boolean(#s >= #part and s:sub(1, #part) == part)
end)

--- endsWith(s: string, part: string): boolean
-- Returns true if s ends with part.
stringlib.endsWith = __rt.function_("(string,string)->boolean", function(s, part)
  __rt.check_string(s)
  __rt.check_string(part)
  -- Empty part is a suffix of any string
  if #part == 0 then
    return __rt.check_boolean(true)
  end
  -- s:sub(-0) would return the whole string; guard with the length check
  if #part > #s then
    return __rt.check_boolean(false)
  end
  return __rt.check_boolean(s:sub(-#part) == part)
end)

--- replace(s: string, old: string, to: string): string
-- Replaces all occurrences of old with to (plain-text, no pattern).
-- Uses string.gsub with escaped pattern and escaped replacement for
-- plain-text semantics.
stringlib.replace = __rt.function_("(string,string,string)->string", function(s, old, to)
  __rt.check_string(s)
  __rt.check_string(old)
  __rt.check_string(to)
  if old == "" then
    return __rt.check_string(s)
  end
  local escaped_pattern = escape_pattern(old)
  local escaped_to = escape_replacement(to)
  return __rt.check_string((s:gsub(escaped_pattern, escaped_to)))
end)

--- split(s: string, sep: string): [string]
-- Splits s by sep (plain-text) and returns an array of substrings.
-- If sep is empty, splits into individual characters.
-- If sep is not found, returns an array containing s as the single element.
-- The wrapper signature carries the canonical descriptor grammar
-- (luajit-v1.2-stdlib-contracts D1): arrays are "[T]".
stringlib.split = __rt.function_("(string,string)->[string]", function(s, sep)
  __rt.check_string(s)
  __rt.check_string(sep)
  local result = {}

  -- Empty string: return empty array regardless of separator
  if #s == 0 then
    return result
  end

  if sep == "" then
    -- Split into individual Unicode scalar values (v1.2).
    local cursor = 0
    while true do
      local next_cursor, ch = __rt.utf8_next(s, cursor)
      if next_cursor == nil then break end
      result[#result + 1] = ch
      cursor = next_cursor
    end
    return result
  end

  local start_pos = 1
  while true do
    local found_start, found_end = string.find(s, sep, start_pos, true) -- plain-text
    if not found_start then
      result[#result + 1] = s:sub(start_pos)
      break
    end
    result[#result + 1] = s:sub(start_pos, found_start - 1)
    start_pos = found_end + 1
  end

  return result
end)

--- trim(s: string): string
-- Removes leading and trailing whitespace from s.
-- Whitespace includes space, tab, newline, carriage return, vertical tab, form feed.
stringlib.trim = __rt.function_("(string)->string", function(s)
  __rt.check_string(s)
  -- Use Lua pattern: ^%s* captures leading whitespace, (.-) lazily captures content, %s*$ captures trailing
  local trimmed = string.match(s, "^%s*(.-)%s*$")
  if trimmed == nil then
    -- string.match returns nil when there is no match; but ^%s*(.-)%s*$ always matches
    -- (even on empty string it matches with empty capture). Fallback for safety.
    return __rt.check_string("")
  end
  return __rt.check_string(trimmed)
end)

return stringlib
