-- DEAL Standard Library: std/string — v1.4
-- Provides string manipulation functions per the v1.4 spec.
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
-- Returns the number of Unicode scalar values in the string (v1.4).
-- The trailing span triplet carries the call site (ISSUE-0598), so the
-- dynamic-nonstring E8001 reports it byte-exact.
stringlib.length = __rt.function_("(string)->number", function(s, file, line, column)
  __rt.check_string(s, file, line, column)
  local count = 0
  local cursor = 0
  while true do
    local next_cursor = __rt.utf8_next(s, cursor)
    if next_cursor == nil then break end
    count = count + 1
    cursor = next_cursor
  end
  return __rt.check_number(count, file, line, column)
end)

--- substring(s: string, start: int, end: int): string
-- Returns the substring from start (0-based, inclusive) to end (exclusive),
-- with positions measured in Unicode scalar values (v1.4).
-- start > end, start beyond the string, or end <= 0 produce the empty
-- string; out-of-range end clamps to the string end.
stringlib.substring = __rt.function_("(string,number,number)->string", function(s, start, end_, file, line, column)
  __rt.check_string(s, file, line, column)
  __rt.check_number(start, file, line, column)
  __rt.check_number(end_, file, line, column)
  if start ~= start or start == math.huge or start == -math.huge
      or start ~= math.floor(start) or end_ ~= end_
      or end_ == math.huge or end_ == -math.huge or end_ ~= math.floor(end_) then
    error(__rt._armErr("SUBSTRING_BOUNDS", {}, "E8001",
      "substring bounds must be finite integers", file, line, column), 0)
  end
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
  return __rt.check_string(string.sub(s, byte_start, byte_end), file, line, column)
end)

--- contains(s: string, part: string): boolean
-- Returns true if part is a substring of s (plain-text match, no pattern).
stringlib.contains = __rt.function_("(string,string)->boolean", function(s, part, file, line, column)
  __rt.check_string(s, file, line, column)
  __rt.check_string(part, file, line, column)
  -- Use plain-text matching (4th arg to string.find)
  local found = string.find(s, part, 1, true)
  return __rt.check_boolean(found ~= nil, file, line, column)
end)

--- startsWith(s: string, part: string): boolean
-- Returns true if s starts with part.
stringlib.startsWith = __rt.function_("(string,string)->boolean", function(s, part, file, line, column)
  __rt.check_string(s, file, line, column)
  __rt.check_string(part, file, line, column)
  -- Empty part is a prefix of any string
  if #part == 0 then
    return __rt.check_boolean(true, file, line, column)
  end
  return __rt.check_boolean(#s >= #part and s:sub(1, #part) == part, file, line, column)
end)

--- endsWith(s: string, part: string): boolean
-- Returns true if s ends with part.
stringlib.endsWith = __rt.function_("(string,string)->boolean", function(s, part, file, line, column)
  __rt.check_string(s, file, line, column)
  __rt.check_string(part, file, line, column)
  -- Empty part is a suffix of any string
  if #part == 0 then
    return __rt.check_boolean(true, file, line, column)
  end
  -- s:sub(-0) would return the whole string; guard with the length check
  if #part > #s then
    return __rt.check_boolean(false, file, line, column)
  end
  return __rt.check_boolean(s:sub(-#part) == part, file, line, column)
end)

--- replace(s: string, search: string, replacement: string): string
-- Replaces all occurrences of search with replacement (plain-text, no
-- pattern). Uses string.gsub with escaped pattern and escaped replacement
-- for plain-text semantics.
stringlib.replace = __rt.function_("(string,string,string)->string", function(s, search, replacement, file, line, column)
  __rt.check_string(s, file, line, column)
  __rt.check_string(search, file, line, column)
  __rt.check_string(replacement, file, line, column)
  if search == "" then
    return __rt.check_string(s, file, line, column)
  end
  local escaped_pattern = escape_pattern(search)
  local escaped_replacement = escape_replacement(replacement)
  return __rt.check_string((s:gsub(escaped_pattern, escaped_replacement)), file, line, column)
end)

--- split(s: string, sep: string): [string]
-- Splits s by sep (plain-text) and returns an array of substrings.
-- If sep is empty, splits into individual characters.
-- If sep is not found, returns an array containing s as the single element.
-- The wrapper signature carries the canonical descriptor grammar
-- (luajit-v1.2-stdlib-contracts D1): arrays are "[T]".
stringlib.split = __rt.function_("(string,string)->[string]", function(s, sep, file, line, column)
  __rt.check_string(s, file, line, column)
  __rt.check_string(sep, file, line, column)
  local result = {}

  -- Empty string: return empty array regardless of separator
  if #s == 0 then
    return result
  end

  if sep == "" then
    -- Split into individual Unicode scalar values (v1.4).
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
stringlib.trim = __rt.function_("(string)->string", function(s, file, line, column)
  __rt.check_string(s, file, line, column)
  -- Use Lua pattern: ^%s* captures leading whitespace, (.-) lazily captures content, %s*$ captures trailing
  local trimmed = string.match(s, "^%s*(.-)%s*$")
  if trimmed == nil then
    -- string.match returns nil when there is no match; but ^%s*(.-)%s*$ always matches
    -- (even on empty string it matches with empty capture). Fallback for safety.
    return __rt.check_string("", file, line, column)
  end
  return __rt.check_string(trimmed, file, line, column)
end)

return stringlib
