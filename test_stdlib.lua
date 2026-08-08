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
-- std/string tests
-- ===========================================================================

local strings = require("std.string")

test("string.len returns correct length", function()
  assert(strings.len.f("hello") == 5)
  assert(strings.len.f("") == 0)
  assert(strings.len.f("abc def") == 7)
end)

test("string.len with non-string raises error", function()
  local err = assert_error(function() strings.len.f(42) end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.sub returns correct substring", function()
  -- 0-based: sub("hello", 0, 5) → "hello"
  assert(strings.sub.f("hello", 0, 5) == "hello")
  -- 0-based: sub("hello", 1, 4) → "ell"
  assert(strings.sub.f("hello", 1, 4) == "ell")
  -- 0-based: sub("hello", 0, 1) → "h"
  assert(strings.sub.f("hello", 0, 1) == "h")
end)

test("string.sub with non-string raises error", function()
  local err = assert_error(function() strings.sub.f(42, 0, 1) end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.sub with non-int raises error", function()
  local err = assert_error(function() strings.sub.f("hi", "x", 1) end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.find returns index when found", function()
  local r = strings.find.f("hello world", "world")
  assert(r == 6) -- 0-based index
end)

test("string.find returns null when not found", function()
  local r = strings.find.f("hello", "xyz")
  assert(r == require("deal.runtime").__NULL)
end)

test("string.find with non-string raises error", function()
  local err = assert_error(function() strings.find.f(42, "hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.concat concatenates strings", function()
  assert(strings.concat.f("hello", " world") == "hello world")
  assert(strings.concat.f("", "") == "")
  assert(strings.concat.f("a", "b") == "ab")
end)

test("string.concat with non-string raises error", function()
  local err = assert_error(function() strings.concat.f(1, "b") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/table tests
-- ===========================================================================

local tablelib = require("std.table")

test("table.keys returns keys of a table", function()
  local t = { a = 1, b = 2, c = 3 }
  local keys = tablelib.keys.f(t)
  assert(#keys == 3)
  -- All keys should be strings
  for _, k in ipairs(keys) do
    assert(type(k) == "string")
  end
  -- Check that all expected keys are present
  local found = { a = false, b = false, c = false }
  for _, k in ipairs(keys) do
    found[k] = true
  end
  assert(found.a and found.b and found.c)
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

test("json.parse of empty object", function()
  local result = json.parse.f('{}')
  assert(type(result) == "table")
end)

test("json.parse of empty array", function()
  local result = json.parse.f('[]')
  assert(type(result) == "table")
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

test("json.stringify with non-table raises error", function()
  local err = assert_error(function() json.stringify.f("not a table") end)
  assert(type(err) == "table", "error should be a table")
end)

test("json.parse with non-string raises error", function()
  local err = assert_error(function() json.parse.f(42) end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/math tests
-- ===========================================================================

local mathlib = require("std.math")

test("math.abs(int) returns absolute value", function()
  assert(mathlib.abs.f(5) == 5)
  assert(mathlib.abs.f(-5) == 5)
  assert(mathlib.abs.f(0) == 0)
end)

test("math.abs with non-int raises error", function()
  local err = assert_error(function() mathlib.abs.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.abs with float raises error (int overload)", function()
  local err = assert_error(function() mathlib.abs.f(3.14) end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.ceil returns ceiling as int", function()
  assert(mathlib.ceil.f(3.14) == 4)
  assert(mathlib.ceil.f(3.0) == 3)
  assert(mathlib.ceil.f(-2.5) == -2)
end)

test("math.ceil with non-number raises error", function()
  local err = assert_error(function() mathlib.ceil.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.floor returns floor as int", function()
  assert(mathlib.floor.f(3.14) == 3)
  assert(mathlib.floor.f(3.0) == 3)
  assert(mathlib.floor.f(-2.5) == -3)
end)

test("math.floor with non-number raises error", function()
  local err = assert_error(function() mathlib.floor.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.max returns maximum of two ints", function()
  assert(mathlib.max.f(1, 2) == 2)
  assert(mathlib.max.f(5, 3) == 5)
  assert(mathlib.max.f(-1, -2) == -1)
  assert(mathlib.max.f(0, 0) == 0)
end)

test("math.max with non-int raises error", function()
  local err = assert_error(function() mathlib.max.f("hi", 2) end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.min returns minimum of two ints", function()
  assert(mathlib.min.f(1, 2) == 1)
  assert(mathlib.min.f(5, 3) == 3)
  assert(mathlib.min.f(-1, -2) == -2)
  assert(mathlib.min.f(0, 0) == 0)
end)

test("math.min with non-int raises error", function()
  local err = assert_error(function() mathlib.min.f("hi", 2) end)
  assert(type(err) == "table", "error should be a table")
end)

test("math.sqrt returns square root", function()
  local r = mathlib.sqrt.f(4)
  assert(r == 2)
  local r2 = mathlib.sqrt.f(2)
  assert(r2 > 1.41 and r2 < 1.42)
end)

test("math.sqrt of zero returns zero", function()
  assert(mathlib.sqrt.f(0) == 0)
end)

test("math.sqrt of negative number errors with E8001", function()
  local err = assert_error_code(function() mathlib.sqrt.f(-1) end, "E8001")
end)

test("math.sqrt with non-number raises error", function()
  local err = assert_error(function() mathlib.sqrt.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/time tests
-- ===========================================================================

local timelib = require("std.time")

test("time.now returns a reasonable timestamp", function()
  local ts = timelib.now.f()
  assert(type(ts) == "number")
  assert(ts % 1 == 0, "timestamp should be an integer")
  -- Should be sometime after 2010-01-01 (1262304000)
  assert(ts > 1262304000, "timestamp should be after 2010")
  -- Should be before 2100-01-01 (4102444800)
  assert(ts < 4102444800, "timestamp should be before 2100")
end)

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
