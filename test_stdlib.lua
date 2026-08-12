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

test("time.nowMillis returns a reasonable timestamp in milliseconds", function()
  local ts = timelib.nowMillis.f()
  assert(type(ts) == "number")
  assert(ts % 1 == 0, "timestamp should be an integer")
  -- Should be sometime after 2010-01-01 in milliseconds (1262304000000)
  assert(ts > 1262304000000, "timestamp should be after 2010, got " .. tostring(ts))
  -- Should be before 2100-01-01 in milliseconds (4102444800000)
  assert(ts < 4102444800000, "timestamp should be before 2100, got " .. tostring(ts))
end)

test("time.nowMillis returns value ~1000x os.time()", function()
  local ts = timelib.nowMillis.f()
  local raw = os.time()
  -- The millisecond value should be roughly 1000x the seconds value
  local ratio = ts / math.max(raw, 1)
  assert(ratio >= 990 and ratio <= 1010,
    "nowMillis should be ~1000x os.time(), got ratio " .. tostring(ratio))
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
