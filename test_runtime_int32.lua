-- Test suite for deal/runtime.lua under the DEAL_V1_2_INT32 process-wide
-- gate (signed-int32 foundation I4): sets __rt.__INT32 = true after require
-- and pins the signed32 matrix — the gate [-2147483648, 2147483647] with
-- the pinned retained template E8004 "int out of safe range", the E8001
-- variants, E8005/E8006, the int_neg gate, the pow bands (2 ** 62 -> E8004,
-- 2 ** 1024 -> E8001 infinity), int conversion, -0 normalization, and the
-- truncated remainder -2147483648 % -1 -> 0. Executed at the gate by
-- run_tests.sh (the Runtime Library Tests block, the same
-- luajit-availability guard as test_runtime.lua).
-- Run with: luajit test_runtime_int32.lua

package.path = "./?.lua;" .. package.path
local __rt = require("deal.runtime")

-- The pinned activation seam (I4): the emitter writes this flag in the
-- module preamble under DEAL_V1_2_INT32; this suite sets it directly,
-- exactly like a generated v1.2 module does before any check runs.
__rt.__INT32 = true

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

-- Helper to check that an error was raised with expected code and,
-- when pinned, the exact retained template fields.
local function assert_error(fn, expected_code, expected_message)
  local ok, err = pcall(fn)
  if ok then
    error("expected error with code " .. tostring(expected_code) .. " but no error was raised")
  end
  if type(err) ~= "table" then
    error("expected error table but got " .. type(err) .. ": " .. tostring(err))
  end
  if err.code ~= expected_code then
    error("expected error code " .. tostring(expected_code) .. " but got " .. tostring(err.code) .. ": " .. tostring(err.message))
  end
  if expected_message ~= nil and err.message ~= expected_message then
    error("expected message '" .. tostring(expected_message) .. "' but got '" .. tostring(err.message) .. "'")
  end
  return err
end

-- ==================== Gate state ====================

test("the int32 flag is set after require", function()
  assert(__rt.__INT32 == true)
end)

-- ==================== check_int: the signed32 gate ====================

test("check_int(2147483647) returns the maximum signed32 int", function()
  local r = __rt.check_int(2147483647)
  assert(r == 2147483647)
end)

test("check_int(-2147483648) returns the minimum signed32 int", function()
  local r = __rt.check_int(-2147483648)
  assert(r == -2147483648)
end)

test("check_int(2147483648) raises E8004 'int out of safe range'", function()
  assert_error(function() __rt.check_int(2147483648) end, "E8004", "int out of safe range")
end)

test("check_int(-2147483649) raises E8004 'int out of safe range'", function()
  assert_error(function() __rt.check_int(-2147483649) end, "E8004", "int out of safe range")
end)

test("check_int(-0) normalizes to 0", function()
  local r = __rt.check_int(-0)
  assert(r == 0)
  assert(1 / r == math.huge) -- proves +0, not -0
end)

test("check_int on NaN raises E8001 'expected int, got NaN'", function()
  assert_error(function() __rt.check_int(0 / 0) end, "E8001", "expected int, got NaN")
end)

test("check_int on Infinity raises E8001 'expected int, got infinity'", function()
  assert_error(function() __rt.check_int(1 / 0) end, "E8001", "expected int, got infinity")
end)

test("check_int on a fractional number raises E8001 'expected int, got non-integer number'", function()
  assert_error(function() __rt.check_int(1.5) end, "E8001", "expected int, got non-integer number")
end)

test("check_int on a string raises E8001 'expected int'", function()
  assert_error(function() __rt.check_int("hi") end, "E8001", "expected int")
end)

-- ==================== int arithmetic: the signed32 gate ====================

test("int_add(2147483647, 1) raises E8004", function()
  assert_error(function() __rt.int_add(2147483647, 1) end, "E8004", "int out of safe range")
end)

test("int_sub(-2147483648, 1) raises E8004", function()
  assert_error(function() __rt.int_sub(-2147483648, 1) end, "E8004", "int out of safe range")
end)

test("int_mul(65536, 65536) raises E8004", function()
  assert_error(function() __rt.int_mul(65536, 65536) end, "E8004", "int out of safe range")
end)

test("int_neg(-2147483648) raises E8004", function()
  assert_error(function() __rt.int_neg(-2147483648) end, "E8004", "int out of safe range")
end)

test("int_neg(-0) normalizes to 0", function()
  local r = __rt.int_neg(-0)
  assert(r == 0)
  assert(1 / r == math.huge)
end)

test("int_div(-2147483648, -1) raises E8004 (MIN / -1)", function()
  assert_error(function() __rt.int_div(-2147483648, -1) end, "E8004", "int out of safe range")
end)

test("int_div(1, 0) raises E8005", function()
  assert_error(function() __rt.int_div(1, 0) end, "E8005", "integer division by zero")
end)

test("int_mod(1, 0) raises E8005", function()
  assert_error(function() __rt.int_mod(1, 0) end, "E8005", "integer division by zero")
end)

test("int_mod(-2147483648, -1) returns 0 (truncated remainder)", function()
  local r = __rt.int_mod(-2147483648, -1)
  assert(r == 0)
end)

test("int_pow(2, -1) raises E8006", function()
  assert_error(function() __rt.int_pow(2, -1) end, "E8006", "integer exponent must be non-negative")
end)

test("int_pow(2, 62) raises E8004 (finite pow band)", function()
  assert_error(function() __rt.int_pow(2, 62) end, "E8004", "int out of safe range")
end)

test("int_pow(2, 1024) raises E8001 infinity (NaN/infinity-first band)", function()
  assert_error(function() __rt.int_pow(2, 1024) end, "E8001", "expected int, got infinity")
end)

test("int_pow(2, 30) returns 1073741824 (in-range pow)", function()
  assert(__rt.int_pow(2, 30) == 1073741824)
end)

-- ==================== int conversion: the signed32 gate ====================

test("int_convert(2147483648.0) raises E8004", function()
  assert_error(function() __rt.int_convert(2147483648.0) end, "E8004", "int out of safe range")
end)

test("int_convert(-2147483649.0) raises E8004", function()
  assert_error(function() __rt.int_convert(-2147483649.0) end, "E8004", "int out of safe range")
end)

test("int_convert(2147483647.0) returns the maximum signed32 int", function()
  assert(__rt.int_convert(2147483647.0) == 2147483647)
end)

test("int_convert(-2147483648.0) returns the minimum signed32 int", function()
  assert(__rt.int_convert(-2147483648.0) == -2147483648)
end)

test("int_convert(NaN) raises E8001 'expected int, got NaN'", function()
  assert_error(function() __rt.int_convert(0 / 0) end, "E8001", "expected int, got NaN")
end)

test("int_convert(Infinity) raises E8001 'expected int, got infinity'", function()
  assert_error(function() __rt.int_convert(1 / 0) end, "E8001", "expected int, got infinity")
end)

test("int_convert(1.5) raises E8001 'expected int, got non-integer number'", function()
  assert_error(function() __rt.int_convert(1.5) end, "E8001", "expected int, got non-integer number")
end)

-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
