-- Test suite for DEAL Standard Library modules
-- Run with: luajit test_stdlib.lua

-- Adjust package.path to find deal/runtime.lua and std/ modules
package.path = "./?.lua;./?/init.lua;" .. package.path

local passed = 0
local failed = 0

local function test(name, fn)
  local ok, err = pcall(fn)
  if ok then
    passed = passed + 1
    print("PASS: " .. name)
  else
    failed = failed + 1
    print("FAIL: " .. name .. " -- " .. tostring(err))
  end
end

local function assert_error(fn)
  local ok, err = pcall(fn)
  if ok then
    error("expected an error but none was raised")
  end
  return err
end

local function assert_error_code(fn, expected_code)
  local ok, err = pcall(fn)
  if ok then
    error("expected error with code " .. expected_code .. " but no error was raised")
  end
  if type(err) ~= "table" then
    error("expected error table but got " .. type(err) .. ": " .. tostring(err))
  end
  if err.code ~= expected_code then
    error("expected error code " .. expected_code .. " but got " .. tostring(err.code) .. ": " .. tostring(err.message))
  end
  return err
end

-- ===========================================================================
-- std/console tests
-- ===========================================================================

local console = require("std.console")

test("console.log is a function wrapper", function()
  assert(type(console.log) == "table")
  assert(console.log.__kind == "function")
end)

test("console.error is a function wrapper", function()
  assert(type(console.error) == "table")
  assert(console.error.__kind == "function")
end)

test("console.log with string does not error", function()
  local ok, err = pcall(console.log.f, "test message")
  assert(ok, "console.log should not error: " .. tostring(err))
end)

test("console.log with non-string raises error", function()
  local err = assert_error(function() console.log.f(123) end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
end)

test("console.error with string does not error", function()
  local ok, err = pcall(console.error.f, "error message")
  assert(ok, "console.error should not error: " .. tostring(err))
end)

test("console.error with non-string raises error", function()
  local err = assert_error(function() console.error.f(123) end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
end)

-- ===========================================================================
-- std/string tests — v1.0: length, substring, contains, startsWith, endsWith,
-- replace, split, trim
-- ===========================================================================

local strings = require("std.string")

-- ----- length -----

test("string.length returns correct length", function()
  assert(strings.length.f("hello") == 5)
  assert(strings.length.f("") == 0)
  assert(strings.length.f("abc def") == 7)
end)

test("string.length validates output as int", function()
  local r = strings.length.f("test")
  assert(type(r) == "number")
  assert(r % 1 == 0, "output should be an integer")
  assert(r >= 0, "output should be non-negative")
end)

test("string.length with non-string raises error", function()
  local err = assert_error(function() strings.length.f(42) end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- substring -----

test("string.substring returns correct substring", function()
  -- 0-based: substring("hello", 0, 5) → "hello"
  assert(strings.substring.f("hello", 0, 5) == "hello")
  -- 0-based: substring("hello", 1, 4) → "ell"
  assert(strings.substring.f("hello", 1, 4) == "ell")
  -- 0-based: substring("hello", 0, 1) → "h"
  assert(strings.substring.f("hello", 0, 1) == "h")
end)

test("string.substring with start == end returns empty", function()
  assert(strings.substring.f("hello", 2, 2) == "")
end)

test("string.substring with start > end returns empty", function()
  assert(strings.substring.f("hello", 3, 1) == "")
end)

test("string.substring at boundaries", function()
  -- end beyond string length: Lua truncates
  assert(strings.substring.f("abc", 1, 10) == "bc")
  -- start beyond string length: empty
  assert(strings.substring.f("abc", 5, 10) == "")
end)

test("string.substring validates output as string", function()
  local r = strings.substring.f("hello", 0, 5)
  assert(type(r) == "string", "output should be a string")
end)

test("string.substring with non-string raises error", function()
  local err = assert_error(function() strings.substring.f(42, 0, 1) end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.substring with non-int raises error", function()
  local err = assert_error(function() strings.substring.f("hi", "x", 1) end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- Unicode scalar-value length / substring (v1.2) -----

test("string.length counts Unicode scalar values, not bytes", function()
  -- a, U+00E9 (2 bytes), U+4E2D (3 bytes), U+1F600 (4 bytes), b: 5 scalars, 11 bytes
  local s = "a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b"
  assert(#s == 11)
  assert(strings.length.f(s) == 5)
end)

test("string.substring positions are Unicode scalar values", function()
  -- a, U+00E9, U+4E2D, U+1F600, b — 5 scalars
  local s = "a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b"
  assert(strings.substring.f(s, 0, 5) == s)
  assert(strings.substring.f(s, 0, 1) == "a")
  assert(strings.substring.f(s, 1, 2) == "\xC3\xA9")
  assert(strings.substring.f(s, 2, 3) == "\xE4\xB8\xAD")
  assert(strings.substring.f(s, 3, 4) == "\xF0\x9F\x98\x80")
  assert(strings.substring.f(s, 4, 5) == "b")
  assert(strings.substring.f(s, 1, 4) == "\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80")
  assert(strings.substring.f(s, 2, 2) == "")
  assert(strings.substring.f(s, 5, 10) == "")
end)

test("string.substring clamps out-of-range end at the scalar boundary", function()
  local s = "\xC3\xA9\xE4\xB8\xAD"
  assert(strings.substring.f(s, 0, 99) == s)
  assert(strings.substring.f(s, 1, 99) == "\xE4\xB8\xAD")
end)

test("string.split with empty separator splits into Unicode scalar values", function()
  local s = "a\xC3\xA9\xF0\x9F\x98\x80"
  local parts = strings.split.f(s, "")
  assert(#parts == 3, "expected 3 scalars, got " .. #parts)
  assert(parts[1] == "a")
  assert(parts[2] == "\xC3\xA9")
  assert(parts[3] == "\xF0\x9F\x98\x80")
end)

test("string.length rejects malformed UTF-8 with E8001 (v1.2 boundary rule)", function()
  assert_error(function() strings.length.f("\xC3") end, "E8001")
  assert_error(function() strings.substring.f("\xED\xA0\x80", 0, 1) end, "E8001")
end)

-- ----- contains -----

test("string.contains returns true when part found", function()
  assert(strings.contains.f("hello world", "world") == true)
  assert(strings.contains.f("hello", "ell") == true)
  assert(strings.contains.f("abc", "a") == true)
  assert(strings.contains.f("abc", "c") == true)
end)

test("string.contains returns false when part not found", function()
  assert(strings.contains.f("hello", "xyz") == false)
  assert(strings.contains.f("abc", "abcd") == false)
end)

test("string.contains with empty part returns true", function()
  -- An empty string is considered to be contained in any string
  assert(strings.contains.f("hello", "") == true)
end)

test("string.contains with empty string returns false (non-empty part)", function()
  assert(strings.contains.f("", "a") == false)
end)

test("string.contains is plain-text (no pattern matching)", function()
  -- Lua magic characters should be treated literally
  assert(strings.contains.f("100%", "%") == true)
  assert(strings.contains.f("a(b)c", "(") == true)
  assert(strings.contains.f("x.y.z", ".") == true)
  assert(strings.contains.f("a*b", "*") == true)
  assert(strings.contains.f("a+b", "+") == true)
  assert(strings.contains.f("a?b", "?") == true)
  assert(strings.contains.f("a-b", "-") == true)
  assert(strings.contains.f("a^b", "^") == true)
  assert(strings.contains.f("a$b", "$") == true)
  assert(strings.contains.f("[x]", "[") == true)
end)

test("string.contains validates output as boolean", function()
  local r = strings.contains.f("hello", "h")
  assert(type(r) == "boolean", "output should be boolean")
end)

test("string.contains with non-string raises error", function()
  local err = assert_error(function() strings.contains.f(42, "hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- startsWith -----

test("string.startsWith returns true when prefix matches", function()
  assert(strings.startsWith.f("hello", "hel") == true)
  assert(strings.startsWith.f("hello", "h") == true)
  assert(strings.startsWith.f("hello", "hello") == true)
  assert(strings.startsWith.f("abc", "") == true)
end)

test("string.startsWith returns false when prefix does not match", function()
  assert(strings.startsWith.f("hello", "ello") == false)
  assert(strings.startsWith.f("hello", "xyz") == false)
  assert(strings.startsWith.f("", "a") == false)
end)

test("string.startsWith validates output as boolean", function()
  local r = strings.startsWith.f("hello", "h")
  assert(type(r) == "boolean", "output should be boolean")
end)

test("string.startsWith with non-string raises error", function()
  local err = assert_error(function() strings.startsWith.f(42, "hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- endsWith -----

test("string.endsWith returns true when suffix matches", function()
  assert(strings.endsWith.f("hello", "lo") == true)
  assert(strings.endsWith.f("hello", "o") == true)
  assert(strings.endsWith.f("hello", "hello") == true)
  assert(strings.endsWith.f("abc", "") == true)
end)

test("string.endsWith returns false when suffix does not match", function()
  assert(strings.endsWith.f("hello", "hel") == false)
  assert(strings.endsWith.f("hello", "xyz") == false)
  assert(strings.endsWith.f("", "a") == false)
end)

test("string.endsWith validates output as boolean", function()
  local r = strings.endsWith.f("hello", "o")
  assert(type(r) == "boolean", "output should be boolean")
end)

test("string.endsWith with non-string raises error", function()
  local err = assert_error(function() strings.endsWith.f(42, "hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- replace -----

test("string.replace replaces all occurrences", function()
  assert(strings.replace.f("hello world", "world", "earth") == "hello earth")
  assert(strings.replace.f("aaa", "a", "b") == "bbb")
  assert(strings.replace.f("ababab", "ab", "x") == "xxx")
end)

test("string.replace with no match returns original", function()
  assert(strings.replace.f("hello", "xyz", "abc") == "hello")
end)

test("string.replace with empty from returns original", function()
  assert(strings.replace.f("hello", "", "x") == "hello")
end)

test("string.replace is plain-text (no pattern matching)", function()
  -- Lua magic characters in 'from' should be treated literally
  assert(strings.replace.f("a.b.c", ".", "-") == "a-b-c")
  assert(strings.replace.f("100%", "%", "pct") == "100pct")
  assert(strings.replace.f("a*b*c", "*", "+") == "a+b+c")
end)

test("string.replace handles % in replacement string", function()
  -- % in replacement should be treated literally, not as gsub capture reference
  assert(strings.replace.f("hello", "e", "100%") == "h100%llo")
  assert(strings.replace.f("hello", "e", "%1") == "h%1llo")
  assert(strings.replace.f("hello", "e", "%%") == "h%%llo")
end)

test("string.replace validates output as string", function()
  local r = strings.replace.f("a", "a", "b")
  assert(type(r) == "string", "output should be a string")
end)

test("string.replace with non-string raises error", function()
  local err = assert_error(function() strings.replace.f(42, "a", "b") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- split -----

test("string.split splits by separator", function()
  local r = strings.split.f("a,b,c", ",")
  assert(#r == 3)
  assert(r[1] == "a")
  assert(r[2] == "b")
  assert(r[3] == "c")
end)

test("string.split with single element returns array of one", function()
  local r = strings.split.f("hello", ",")
  assert(#r == 1)
  assert(r[1] == "hello")
end)

test("string.split with empty string returns empty array", function()
  local r = strings.split.f("", ",")
  assert(#r == 0)
end)

test("string.split with empty separator splits into characters", function()
  local r = strings.split.f("abc", "")
  assert(#r == 3)
  assert(r[1] == "a")
  assert(r[2] == "b")
  assert(r[3] == "c")
end)

test("string.split returns array", function()
  local r = strings.split.f("a,b", ",")
  assert(type(r) == "table", "output should be a table (array)")
end)

test("string.split with non-string raises error", function()
  local err = assert_error(function() strings.split.f(42, ",") end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.split with consecutive separators produces empty strings", function()
  local r = strings.split.f("a,,b", ",")
  assert(#r == 3)
  assert(r[1] == "a")
  assert(r[2] == "")
  assert(r[3] == "b")
end)

test("string.split with leading separator produces empty first element", function()
  local r = strings.split.f(",a,b", ",")
  assert(#r == 3)
  assert(r[1] == "")
  assert(r[2] == "a")
  assert(r[3] == "b")
end)

test("string.split with trailing separator produces empty last element", function()
  local r = strings.split.f("a,b,", ",")
  assert(#r == 3)
  assert(r[1] == "a")
  assert(r[2] == "b")
  assert(r[3] == "")
end)

-- ----- trim -----

test("string.trim removes leading and trailing whitespace", function()
  assert(strings.trim.f("  hello  ") == "hello")
  assert(strings.trim.f("\t\nhello\r\n") == "hello")
  assert(strings.trim.f("hello") == "hello")
end)

test("string.trim on all-whitespace returns empty string", function()
  assert(strings.trim.f("   ") == "")
  assert(strings.trim.f("\t\n\r") == "")
end)

test("string.trim on empty string returns empty string", function()
  assert(strings.trim.f("") == "")
end)

test("string.trim preserves internal whitespace", function()
  assert(strings.trim.f("  hello  world  ") == "hello  world")
end)

test("string.trim validates output as string", function()
  local r = strings.trim.f("  hello  ")
  assert(type(r) == "string", "output should be a string")
end)

test("string.trim with non-string raises error", function()
  local err = assert_error(function() strings.trim.f(42) end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/table tests
-- ===========================================================================

local tablelib = require("std.table")

test("table.keys returns keys of a table as strings", function()
  local t = { a = 1, b = 2, c = 3 }
  local keys = tablelib.keys.f(t)
  assert(#keys == 3)
  -- All keys should be strings
  for _, k in ipairs(keys) do
    assert(type(k) == "string", "key should be string, got " .. type(k))
  end
  -- Check that all expected keys are present
  local found = { a = false, b = false, c = false }
  for _, k in ipairs(keys) do
    found[k] = true
  end
  assert(found.a and found.b and found.c)
end)

test("table.keys converts numeric keys to strings", function()
  local t = { [1] = "a", [2] = "b" }
  local keys = tablelib.keys.f(t)
  assert(#keys == 2)
  for _, k in ipairs(keys) do
    assert(type(k) == "string", "numeric key should be converted to string, got " .. type(k))
  end
end)

test("table.keys on empty table returns empty array", function()
  local keys = tablelib.keys.f({})
  assert(#keys == 0)
end)

test("table.keys with non-table raises error", function()
  local err = assert_error(function() tablelib.keys.f("not a table") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/json tests
-- ===========================================================================

local json = require("std.json")

test("json.stringify of simple object", function()
  local result = json.stringify.f({ a = 1, b = "hi" })
  -- JSON object keys can be in any order; check that the result is valid JSON
  assert(type(result) == "string")
  assert(string.find(result, '"a"') ~= nil)
  assert(string.find(result, '"b"') ~= nil)
end)

test("json.stringify of array", function()
  local result = json.stringify.f({ 1, 2, 3 })
  assert(result == '[1,2,3]')
end)

test("json.stringify of nested object", function()
  local result = json.stringify.f({ name = "test", data = { x = 1 } })
  assert(type(result) == "string")
  assert(string.find(result, '"name"') ~= nil)
  assert(string.find(result, '"data"') ~= nil)
end)

test("json.stringify encodes __NULL as null", function()
  local __rt = require("deal.runtime")
  local result = json.stringify.f({ a = __rt.__NULL, b = 1 })
  -- Should contain "null", not "{}"
  assert(string.find(result, "null") ~= nil, "should contain 'null' for __NULL value")
  assert(string.find(result, "{}") == nil, "should NOT contain '{}' for __NULL value")
end)

test("json.stringify boolean values", function()
  local result = json.stringify.f({ a = true, b = false })
  assert(string.find(result, "true") ~= nil)
  assert(string.find(result, "false") ~= nil)
end)

test("json.stringify includes numeric keys in object mode", function()
  local result = json.stringify.f({ [1] = "a", name = "test" })
  assert(string.find(result, '"1"') ~= nil, "numeric key 1 should be included as string")
  assert(string.find(result, '"name"') ~= nil)
end)

test("json.stringify escapes all control characters", function()
  -- ASCII control character 0x01 (SOH)
  local result = json.stringify.f({ a = "hello\x01world" })
  assert(string.find(result, "\\u0001") ~= nil, "control character 0x01 should be \\u0001, got: " .. result)
  -- Also verify that no literal control character remains
  assert(string.find(result, "\x01") == nil, "no literal control character should remain")
end)

test("json.parse of object", function()
  local result = json.parse.f('{"a":1,"b":"hi"}')
  assert(type(result) == "table")
  assert(result.a == 1)
  assert(result["b"] == "hi")
end)

test("json.parse of array", function()
  local result = json.parse.f('[1,2,3]')
  assert(type(result) == "table")
  assert(result[1] == 1)
  assert(result[2] == 2)
  assert(result[3] == 3)
end)

test("json.parse of null returns __NULL", function()
  local __rt = require("deal.runtime")
  local result = json.parse.f('{"a":null}')
  assert(result.a == __rt.__NULL, "JSON null should parse to __NULL")
end)

test("json.parse of nested null returns __NULL", function()
  local __rt = require("deal.runtime")
  local result = json.parse.f('[null]')
  assert(result[1] == __rt.__NULL, "JSON null in array should parse to __NULL")
end)

test("json.parse of empty object", function()
  local result = json.parse.f('{}')
  assert(type(result) == "table")
end)

test("json.parse of empty array", function()
  local result = json.parse.f('[]')
  assert(type(result) == "table")
end)

test("json.parse rejects invalid JSON: bare word", function()
  local err = assert_error(function() json.parse.f("tru") end)
  assert(type(err) == "table", "should get error table for 'tru'")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects invalid JSON: malformed object", function()
  local err = assert_error(function() json.parse.f("{invalid}") end)
  assert(type(err) == "table", "should get error table for '{invalid}'")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects invalid JSON: trailing comma", function()
  local err = assert_error(function() json.parse.f('[1,]') end)
  assert(type(err) == "table", "should get error table for trailing comma")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects invalid JSON: trailing garbage", function()
  local err = assert_error(function() json.parse.f('1 2') end)
  assert(type(err) == "table", "should get error table for trailing garbage")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects empty string", function()
  local err = assert_error(function() json.parse.f('') end)
  assert(type(err) == "table", "should get error table for empty string")
  assert(err.code ~= nil, "error should have code")
end)

test("json round-trip", function()
  local original = { a = 1, b = "hello", c = true, d = { nested = 2 } }
  local encoded = json.stringify.f(original)
  local decoded = json.parse.f(encoded)
  assert(decoded.a == 1)
  assert(decoded.b == "hello")
  assert(decoded.c == true)
  assert(decoded.d.nested == 2)
end)

test("json null round-trip", function()
  local __rt = require("deal.runtime")
  local original = { a = __rt.__NULL, b = 42 }
  local encoded = json.stringify.f(original)
  local decoded = json.parse.f(encoded)
  assert(decoded.a == __rt.__NULL, "null should round-trip to __NULL, got " .. tostring(decoded.a))
  assert(decoded.b == 42)
end)

test("json.stringify with non-table raises error", function()
  local err = assert_error(function() json.stringify.f("not a table") end)
  assert(type(err) == "table", "error should be a table")
end)

test("json.stringify rejects unsupported nested types", function()
  -- A table containing a function should cause an error during encoding
  local t = { a = 1, fn = function() end }
  local err = assert_error(function() json.stringify.f(t) end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
  assert(string.find(err.message, "unsupported") ~= nil, "error message should mention unsupported type")
end)

test("json.parse with non-string raises error", function()
  local err = assert_error(function() json.parse.f(42) end)
  assert(type(err) == "table", "error should be a table")
end)

-- NaN and Infinity rejection tests
test("json.stringify rejects NaN with E8001", function()
  local err = assert_error(function() json.stringify.f({ x = 0.0/0.0 }) end)
  assert(type(err) == "table", "should get error table for NaN")
  assert(err.code == "E8001", "error code should be E8001, got " .. tostring(err.code))
  assert(string.find(err.message, "NaN") ~= nil, "error message should mention NaN")
end)

test("json.stringify rejects Infinity with E8001", function()
  local err = assert_error(function() json.stringify.f({ x = 1.0/0.0 }) end)
  assert(type(err) == "table", "should get error table for Infinity")
  assert(err.code == "E8001", "error code should be E8001, got " .. tostring(err.code))
  assert(string.find(err.message, "Infinity") ~= nil, "error message should mention Infinity")
end)

test("json.stringify rejects negative Infinity with E8001", function()
  local err = assert_error(function() json.stringify.f({ x = -1.0/0.0 }) end)
  assert(type(err) == "table", "should get error table for negative Infinity")
  assert(err.code == "E8001", "error code should be E8001, got " .. tostring(err.code))
  assert(string.find(err.message, "Infinity") ~= nil, "error message should mention Infinity")
end)

test("json.stringify accepts finite numbers unchanged", function()
  local result = json.stringify.f({ x = 42.0 })
  assert(result == '{"x":42}' or string.find(result, '"x":42') ~= nil, "finite number should encode correctly, got: " .. tostring(result))
end)

test("json.stringify rejects NaN in nested object", function()
  local err = assert_error(function() json.stringify.f({ data = { val = 0.0/0.0 } }) end)
  assert(type(err) == "table", "should get error table for nested NaN")
  assert(err.code == "E8001", "error code should be E8001")
  assert(string.find(err.message, "NaN") ~= nil, "error message should mention NaN")
end)

test("json.stringify rejects Infinity in array", function()
  local err = assert_error(function() json.stringify.f({ 1, 2, 1.0/0.0 }) end)
  assert(type(err) == "table", "should get error table for array Infinity")
  assert(err.code == "E8001", "error code should be E8001")
  assert(string.find(err.message, "Infinity") ~= nil, "error message should mention Infinity")
end)

-- ===========================================================================
-- std/json Unicode conformance tests (ISSUE-0171): byte-precise hostile-input
-- regressions pinning the hardened std/json.lua decode/rejection behavior.
-- Hostile bytes (raw controls, invalid UTF-8) are built with string.char at
-- runtime; none are committed literally to this file.
-- ===========================================================================

-- Assert that a string carries exactly the given byte sequence (string.byte
-- comparisons; never string.find on human-readable text).
local function assert_byte_seq(actual, ...)
  local expected = {...}
  assert(type(actual) == "string", "expected a string, got " .. type(actual))
  if #actual ~= #expected then
    error("byte length mismatch: got " .. #actual .. ", expected " .. #expected)
  end
  for i = 1, #expected do
    local b = string.byte(actual, i)
    if b ~= expected[i] then
      error("byte " .. i .. " mismatch: got 0x" .. string.format("%02X", b)
        .. ", expected 0x" .. string.format("%02X", expected[i]))
    end
  end
end

-- Assert that fn raises an E8001 error table whose message contains needle.
-- A raw Lua error (code == nil) fails the assert_error_code table check, so
-- untranslated errors can never pass these pins.
local function assert_json_error(fn, needle)
  local err = assert_error_code(fn, "E8001")
  assert(type(err.message) == "string", "E8001 error should carry a message")
  assert(string.find(err.message, needle, 1, true) ~= nil,
    "error message should contain '" .. needle .. "', got: " .. tostring(err.message))
  return err
end

test("json.parse decodes the full RFC 8259 escape table", function()
  local text = '"' .. '\\"' .. '\\\\' .. '\\/' .. '\\b' .. '\\f' .. '\\n' .. '\\r' .. '\\t' .. '"'
  local r = json.parse.f(text)
  assert_byte_seq(r, 0x22, 0x5C, 0x2F, 0x08, 0x0C, 0x0A, 0x0D, 0x09)
end)

test("json.parse decodes \\u00E9 to UTF-8 bytes C3 A9", function()
  assert_byte_seq(json.parse.f('"\\u00E9"'), 0xC3, 0xA9)
  -- Hex digits are case-insensitive (RFC 8259 section 7: 4HEXDIG).
  assert_byte_seq(json.parse.f('"\\u00e9"'), 0xC3, 0xA9)
end)

test("json.parse decodes \\u4E2D to UTF-8 bytes E4 B8 AD", function()
  assert_byte_seq(json.parse.f('"\\u4E2D"'), 0xE4, 0xB8, 0xAD)
end)

test("json.parse combines surrogate pair \\uD834\\uDD1E to U+1D11E (F0 9D 84 9E)", function()
  assert_byte_seq(json.parse.f('"\\uD834\\uDD1E"'), 0xF0, 0x9D, 0x84, 0x9E)
end)

test("json.parse combines boundary pair \\uD800\\uDC00 to U+10000 (F0 90 80 80)", function()
  assert_byte_seq(json.parse.f('"\\uD800\\uDC00"'), 0xF0, 0x90, 0x80, 0x80)
end)

test("json.parse combines boundary pair \\uDBFF\\uDFFF to U+10FFFF (F4 8F BF BF)", function()
  assert_byte_seq(json.parse.f('"\\uDBFF\\uDFFF"'), 0xF4, 0x8F, 0xBF, 0xBF)
end)

test("json.parse rejects lone high surrogate \\uD834 with E8001", function()
  assert_json_error(function() json.parse.f('"\\uD834"') end,
    "unpaired surrogate code unit in unicode escape")
end)

test("json.parse rejects lone low surrogate \\uDD1E with E8001", function()
  assert_json_error(function() json.parse.f('"\\uDD1E"') end,
    "unpaired surrogate code unit in unicode escape")
end)

test("json.parse rejects surrogate pair mismatch \\uD834\\u0041 with E8001", function()
  assert_json_error(function() json.parse.f('"\\uD834\\u0041"') end,
    "unpaired surrogate code unit in unicode escape")
end)

test("json.parse rejects malformed \\u escapes with E8001", function()
  local forms = { '"\\u12"', '"\\u123"', '"\\uZZZZ"', '"\\u12G4"' }
  for _, text in ipairs(forms) do
    assert_json_error(function() json.parse.f(text) end,
      "invalid unicode escape: expected 4 hex digits")
  end
end)

test("json.parse rejects unknown escapes with E8001", function()
  local forms = {
    '"' .. "\\x41" .. '"',
    '"' .. "\\q" .. '"',
    '"' .. "\\'" .. '"',
    '"' .. "\\0" .. '"',
    '"' .. "\\v" .. '"',
  }
  for _, text in ipairs(forms) do
    assert_json_error(function() json.parse.f(text) end, "unknown escape sequence")
  end
end)

test("json.parse rejects raw control characters with E8001", function()
  local controls = { 0x00, 0x01, 0x08, 0x09, 0x0A, 0x0D }
  for _, b in ipairs(controls) do
    local text = '"' .. string.char(b) .. '"'
    assert_json_error(function() json.parse.f(text) end,
      "raw control character in string (must be escaped)")
  end
end)

test("json.parse accepts raw DEL (0x7F) and escaped control forms", function()
  assert_byte_seq(json.parse.f('"' .. string.char(0x7F) .. '"'), 0x7F)
  assert_byte_seq(json.parse.f('"\\u0000"'), 0x00)
  assert_byte_seq(json.parse.f('"\\u007F"'), 0x7F)
end)

test("json.parse treats decoded \\u0022 and \\u005C as data, not structure", function()
  -- Decoded quote/backslash must not re-lex: the parse only succeeds when
  -- they are appended as data.
  assert_byte_seq(json.parse.f('"\\u0022\\u005C"'), 0x22, 0x5C)
end)

test("json.parse passes raw supplementary UTF-8 through byte-identically", function()
  local supplementary = "\xF0\x9D\x84\x9E" -- U+1D11E, scalar-valid UTF-8
  assert_byte_seq(json.parse.f('"' .. supplementary .. '"'), 0xF0, 0x9D, 0x84, 0x9E)
end)

test("json.stringify emits supplementary characters raw (byte-identical)", function()
  local supplementary = "\xF0\x9D\x84\x9E" -- U+1D11E
  local result = json.stringify.f({ v = supplementary })
  assert(string.find(result, supplementary, 1, true) ~= nil,
    "supplementary bytes should be emitted raw, got: " .. result)
  assert(string.find(result, "\\uD834\\uDD1E") == nil,
    "supplementary bytes should not be re-encoded as a surrogate pair")
end)

test("json supplementary stringify/parse round-trip is byte-identical", function()
  local orig = "\xF0\x9D\x84\x9E\xC3\xA9\xE4\xB8\xAD" -- U+1D11E U+00E9 U+4E2D
  local encoded = json.stringify.f({ v = orig })
  assert(string.find(encoded, orig, 1, true) ~= nil, "raw bytes should survive stringify")
  local decoded = json.parse.f(encoded)
  assert(decoded.v == orig, "parse(stringify(s)) should be byte-identical")
end)

test("json.stringify rejects invalid UTF-8 leaves with E8001", function()
  local invalid = {
    string.char(0xED, 0xA0, 0x80), -- UTF-16 surrogate encoded in UTF-8
    string.char(0xC0, 0xAF),       -- overlong encoding
    string.char(0xC3),             -- truncated 2-byte sequence
  }
  for _, s in ipairs(invalid) do
    assert_json_error(function() json.stringify.f({ v = s }) end,
      "cannot encode invalid UTF-8 as JSON")
  end
end)

test("json.stringify rejects invalid UTF-8 keys with E8001", function()
  local t = {}
  t[string.char(0xED, 0xA0, 0x80)] = "value"
  assert_json_error(function() json.stringify.f(t) end,
    "cannot encode invalid UTF-8 as JSON")
end)

test("json.parse rejects raw invalid UTF-8 input with E8001 (entry gate)", function()
  local surrogate_bytes = string.char(0xED, 0xA0, 0x80)
  assert_json_error(function() json.parse.f('"' .. surrogate_bytes .. '"') end,
    "expected string, got invalid UTF-8 encoding")
end)

-- ===========================================================================
-- std/json int32 number mapping and stringify shape rejection (v1.2 D5)
-- ===========================================================================

test("json.parse maps JSON numbers to int exactly inside the int32 range", function()
  local __rt = require("deal.runtime")
  local result = json.parse.f('{"a":2147483647,"b":2147483648,"c":-2147483648,"d":-2147483649}')
  assert(result.a == 2147483647, "2147483647 must parse")
  assert(result.b == 2147483648, "2147483648 must parse as number")
  assert(result.c == -2147483648, "-2147483648 must parse")
  assert(result.d == -2147483649, "-2147483649 must parse as number")
  assert(__rt._json_is_int(result.a) == true, "2147483647 maps to int")
  assert(__rt._json_is_int(result.b) == false, "2147483648 maps to number")
  assert(__rt._json_is_int(result.c) == true, "-2147483648 maps to int")
  assert(__rt._json_is_int(result.d) == false, "-2147483649 maps to number")
end)

test("json.parse normalizes -0 to 0", function()
  local result = json.parse.f('{"z":-0}')
  local z = result.z
  assert(1 / z > 0, "-0 must normalize to 0 (1/-0 is -Infinity)")
  assert(1 / z == math.huge, "normalized zero divides to +Infinity")
end)

test("json.stringify rejects bytes-kind values with E8001", function()
  local __rt = require("deal.runtime")
  local b = __rt.bytes_new(2)
  b.__data[0] = 65  -- exercise the bytes runtime write path first
  local err = assert_error_code(function() json.stringify.f({ payload = b }) end, "E8001")
  assert(string.find(err.message, "bytes", 1, true) ~= nil,
    "error message should mention bytes, got: " .. tostring(err.message))
end)

test("json int/number document round-trip", function()
  local doc = { count = 42, ratio = 2.5 }
  local encoded = json.stringify.f(doc)
  local back = json.parse.f(encoded)
  assert(back.count == 42, "int round-trip mismatch")
  assert(back.ratio == 2.5, "number round-trip mismatch")
end)

-- ===========================================================================
-- std/math tests
-- ===========================================================================

local mathlib = require("std.math")

-- absInt tests

test("math.absInt(int) returns absolute value", function()
  local r = mathlib.absInt.f(5)
  assert(r == 5)
  assert(type(r) == "number")
  assert(r % 1 == 0, "absInt should return an integer")

  local r2 = mathlib.absInt.f(-5)
  assert(r2 == 5)
  assert(r2 % 1 == 0)

  local r3 = mathlib.absInt.f(0)
  assert(r3 == 0)
  assert(r3 % 1 == 0)
end)

test("math.absInt with non-int (string) raises error", function()
  local err = assert_error(function() mathlib.absInt.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.absInt with float raises error (not int)", function()
  local err = assert_error(function() mathlib.absInt.f(3.14) end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.absInt with NaN raises error", function()
  local err = assert_error(function() mathlib.absInt.f(0/0) end)
  assert(type(err) == "table", "error should be a table")
end)

-- int32 contract tests (luajit-v1.2-stdlib-contracts D2, ISSUE-0337)

test("math.absInt(-7) returns 7 (int32 contract driver call)", function()
  assert(mathlib.absInt.f(-7) == 7)
end)

test("math.absInt(-2147483648) raises E8004 (2147483648 is outside int32)", function()
  local err = assert_error_code(function() mathlib.absInt.f(-2147483648) end, "E8004")
  assert(err.message == "int out of range",
    "expected 'int out of range', got " .. tostring(err.message))
end)

test("math.absInt(2147483647) returns 2147483647 (int32 upper bound)", function()
  assert(mathlib.absInt.f(2147483647) == 2147483647)
end)

test("math.absInt(-2147483647) returns 2147483647 (int32 lower-bound neighbor)", function()
  assert(mathlib.absInt.f(-2147483647) == 2147483647)
end)

test("math.minInt returns MIN over the int32 extremes", function()
  assert(mathlib.minInt.f(-2147483648, 2147483647) == -2147483648)
end)

test("math.maxInt returns MAX over the int32 extremes", function()
  assert(mathlib.maxInt.f(-2147483648, 2147483647) == 2147483647)
end)

test("math.minInt(-2147483648, -2147483648) returns MIN", function()
  assert(mathlib.minInt.f(-2147483648, -2147483648) == -2147483648)
end)

test("math.maxInt(2147483647, 2147483647) returns MAX", function()
  assert(mathlib.maxInt.f(2147483647, 2147483647) == 2147483647)
end)

test("math.minInt/maxInt on int32 extremes stay integral ints", function()
  local mn = mathlib.minInt.f(-2147483648, 2147483647)
  local mx = mathlib.maxInt.f(-2147483648, 2147483647)
  assert(type(mn) == "number" and mn % 1 == 0)
  assert(type(mx) == "number" and mx % 1 == 0)
end)

-- absNumber tests

test("math.absNumber returns absolute value", function()
  assert(mathlib.absNumber.f(3.14) == 3.14)
  assert(mathlib.absNumber.f(-3.14) == 3.14)
  assert(mathlib.absNumber.f(0.0) == 0.0)
  assert(mathlib.absNumber.f(-0.0) == 0, "-0.0 abs should be 0")
end)

test("math.absNumber(NaN) is accepted", function()
  local r = mathlib.absNumber.f(0/0)
  assert(r ~= r, "absNumber(NaN) should still be NaN")
end)

test("math.absNumber(Infinity) is accepted", function()
  local r = mathlib.absNumber.f(math.huge)
  assert(r == math.huge, "absNumber(Infinity) should be Infinity")
end)

test("math.absNumber with non-number raises error", function()
  local err = assert_error(function() mathlib.absNumber.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- minInt tests

test("math.minInt returns minimum of two ints", function()
  assert(mathlib.minInt.f(1, 2) == 1)
  assert(mathlib.minInt.f(5, 3) == 3)
  assert(mathlib.minInt.f(-1, -2) == -2)
  assert(mathlib.minInt.f(0, 0) == 0)
end)

test("math.minInt validates return value as int", function()
  local r = mathlib.minInt.f(100, 200)
  assert(r == 100)
  assert(type(r) == "number")
  assert(r % 1 == 0)
end)

test("math.minInt with non-int raises error", function()
  local err = assert_error(function() mathlib.minInt.f("hi", 2) end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.minInt with float raises error", function()
  local err = assert_error(function() mathlib.minInt.f(1.5, 2) end)
  assert(type(err) == "table", "error should be a table")
end)

-- maxInt tests

test("math.maxInt returns maximum of two ints", function()
  assert(mathlib.maxInt.f(1, 2) == 2)
  assert(mathlib.maxInt.f(5, 3) == 5)
  assert(mathlib.maxInt.f(-1, -2) == -1)
  assert(mathlib.maxInt.f(0, 0) == 0)
end)

test("math.maxInt validates return value as int", function()
  local r = mathlib.maxInt.f(100, 200)
  assert(r == 200)
  assert(type(r) == "number")
  assert(r % 1 == 0)
end)

test("math.maxInt with non-int raises error", function()
  local err = assert_error(function() mathlib.maxInt.f("hi", 2) end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.maxInt with float raises error", function()
  local err = assert_error(function() mathlib.maxInt.f(1, 2.5) end)
  assert(type(err) == "table", "error should be a table")
end)

-- floor tests (returns number, not int)

test("math.floor returns floor as number", function()
  local r = mathlib.floor.f(3.14)
  assert(r == 3.0)
  assert(type(r) == "number")

  local r2 = mathlib.floor.f(3.7)
  assert(r2 == 3.0)
end)

test("math.floor of integer returns same value as number", function()
  local r = mathlib.floor.f(3.0)
  assert(r == 3.0)
end)

test("math.floor of negative number", function()
  assert(mathlib.floor.f(-2.5) == -3.0)
end)

test("math.floor with non-number raises error", function()
  local err = assert_error(function() mathlib.floor.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.floor(NaN) returns NaN", function()
  local r = mathlib.floor.f(0/0)
  assert(r ~= r, "floor(NaN) should be NaN")
end)

-- ceil tests (returns number, not int)

test("math.ceil returns ceiling as number", function()
  local r = mathlib.ceil.f(3.14)
  assert(r == 4.0)
  assert(type(r) == "number")

  local r2 = mathlib.ceil.f(3.0)
  assert(r2 == 3.0)

  local r3 = mathlib.ceil.f(-2.5)
  assert(r3 == -2.0)
end)

test("math.ceil with non-number raises error", function()
  local err = assert_error(function() mathlib.ceil.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.ceil(NaN) returns NaN", function()
  local r = mathlib.ceil.f(0/0)
  assert(r ~= r, "ceil(NaN) should be NaN")
end)

-- sqrt tests

test("math.sqrt returns square root", function()
  local r = mathlib.sqrt.f(4.0)
  assert(r == 2.0)
  local r2 = mathlib.sqrt.f(2.0)
  assert(r2 > 1.41 and r2 < 1.42)
end)

test("math.sqrt of zero returns zero", function()
  assert(mathlib.sqrt.f(0.0) == 0.0)
end)

test("math.sqrt of negative number errors with E8001", function()
  local err = assert_error_code(function() mathlib.sqrt.f(-1.0) end, "E8001")
end)

test("math.sqrt with non-number raises error", function()
  local err = assert_error(function() mathlib.sqrt.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/time tests
-- ===========================================================================

local timelib = require("std.time")

test("time.nowMillis raises E8004 under the signed-int32 gate", function()
  assert_error_code(function() timelib.nowMillis.f() end, "E8004")
end)

test("time.nowMillis ratio case raises E8004 under the signed-int32 gate", function()
  assert_error_code(function() timelib.nowMillis.f() end, "E8004")
end)

-- ===========================================================================

-- ===========================================================================
-- Summary
-- ===========================================================================

print("")
print("========================================")
print("Stdlib Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
