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

test("string.len validates output as int", function()
  local r = strings.len.f("test")
  assert(type(r) == "number")
  assert(r % 1 == 0, "output should be an integer")
  assert(r >= 0, "output should be non-negative")
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

test("string.sub validates output as string", function()
  local r = strings.sub.f("hello", 0, 5)
  assert(type(r) == "string", "output should be a string")
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

test("string.find uses Lua pattern matching", function()
  -- %d+ matches one or more digits
  local r = strings.find.f("hello 123 world", "%d+")
  assert(r == 6, "pattern '%d+' should match digits at 0-based index 6, got " .. tostring(r))
end)

test("string.find returns null when pattern not matched", function()
  local r = strings.find.f("hello", "xyz")
  assert(r == require("deal.runtime").__NULL)
end)

test("string.concat concatenates strings", function()
  assert(strings.concat.f("hello", " world") == "hello world")
  assert(strings.concat.f("", "") == "")
  assert(strings.concat.f("a", "b") == "ab")
end)

test("string.concat validates output as string", function()
  local r = strings.concat.f("a", "b")
  assert(type(r) == "string", "output should be a string")
end)

test("string.concat with non-string raises error", function()
  local err = assert_error(function() strings.concat.f(1, "b") end)
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
-- std/io tests
-- ===========================================================================

local io_lib = require("std.io")

test("io.readText reads file contents", function()
  -- Create a temporary test file
  local path = "/tmp/deal_test_io_read.txt"
  local test_content = "Hello, DEAL IO!\nLine 2\n"
  local f = io.open(path, "w")
  f:write(test_content)
  f:close()

  local result = io_lib.readText.f(path)
  assert(type(result) == "string", "readText should return a string")
  assert(result == test_content, "readText should return correct content")

  -- Cleanup
  os.remove(path)
end)

test("io.readText on empty file", function()
  local path = "/tmp/deal_test_io_empty.txt"
  local f = io.open(path, "w")
  f:close()

  local result = io_lib.readText.f(path)
  assert(type(result) == "string", "readText should return a string")
  assert(result == "", "readText on empty file should return empty string")

  os.remove(path)
end)

test("io.readText with non-string raises error", function()
  local err = assert_error(function() io_lib.readText.f(42) end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
end)

test("io.readText on non-existent file raises error", function()
  local err = assert_error(function() io_lib.readText.f("/tmp/deal_test_nonexistent_file.txt") end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
end)

test("io.writeText writes file contents", function()
  local path = "/tmp/deal_test_io_write.txt"
  local test_content = "Write test content!"

  -- writeText returns null
  local result = io_lib.writeText.f(path, test_content)
  local __rt = require("deal.runtime")
  assert(result == __rt.__NULL, "writeText should return null")

  -- Verify by reading back
  local f = io.open(path, "r")
  local content = f:read("*a")
  f:close()
  assert(content == test_content, "written content should match")

  os.remove(path)
end)

test("io.writeText overwrites existing file", function()
  local path = "/tmp/deal_test_io_overwrite.txt"
  local first_content = "First content"
  local second_content = "Second content"

  io_lib.writeText.f(path, first_content)
  io_lib.writeText.f(path, second_content)

  local f = io.open(path, "r")
  local content = f:read("*a")
  f:close()
  assert(content == second_content, "writeText should overwrite existing file")

  os.remove(path)
end)

test("io.writeText with non-string path raises error", function()
  local err = assert_error(function() io_lib.writeText.f(42, "hello") end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
end)

test("io.writeText with non-string text raises error", function()
  local err = assert_error(function() io_lib.writeText.f("/tmp/test.txt", 42) end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code ~= nil, "error should have a code")
end)

test("io read-write round-trip", function()
  local path = "/tmp/deal_test_io_roundtrip.txt"
  local original = "Round-trip test: " .. tostring(os.time())

  io_lib.writeText.f(path, original)
  local result = io_lib.readText.f(path)
  assert(result == original, "round-trip should preserve content")

  os.remove(path)
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
