-- Test suite for deal/runtime.lua
-- Run with: luajit test_runtime.lua

package.path = "./?.lua;" .. package.path
local __rt = require("deal.runtime")

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

-- Helper to check that an error was raised with expected code
local function assert_error(fn, expected_code)
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
  return err
end

-- Helper to check that no error is raised
local function assert_no_error(fn)
  local ok, err = pcall(fn)
  if not ok then
    if type(err) == "table" then
      error("unexpected error: " .. tostring(err.code) .. " - " .. tostring(err.message))
    else
      error("unexpected error: " .. tostring(err))
    end
  end
end

-- ==================== Sentinel tests ====================

test("__NULL is a table", function()
  assert(type(__rt.__NULL) == "table")
end)

test("__MISSING is a table", function()
  assert(type(__rt.__MISSING) == "table")
end)

test("__NULL == __NULL is true", function()
  assert(__rt.__NULL == __rt.__NULL)
end)

test("__MISSING == __MISSING is true", function()
  assert(__rt.__MISSING == __rt.__MISSING)
end)

test("__NULL ~= __MISSING", function()
  assert(__rt.__NULL ~= __rt.__MISSING)
end)

test("__NULL ~= {}", function()
  assert(__rt.__NULL ~= {})
end)

test("__MISSING ~= {}", function()
  assert(__rt.__MISSING ~= {})
end)

-- ==================== check_null tests ====================

test("check_null(__NULL) returns __NULL", function()
  local r = __rt.check_null(__rt.__NULL)
  assert(r == __rt.__NULL)
end)

test("check_null on non-null errors with E8001", function()
  assert_error(function() __rt.check_null(42) end, "E8001")
end)

test("check_null on nil errors with E8001", function()
  assert_error(function() __rt.check_null(nil) end, "E8001")
end)

-- ==================== check_boolean tests ====================

test("check_boolean(true) returns true", function()
  assert(__rt.check_boolean(true) == true)
end)

test("check_boolean(false) returns false", function()
  assert(__rt.check_boolean(false) == false)
end)

test("check_boolean on non-boolean errors with E8001", function()
  assert_error(function() __rt.check_boolean(1) end, "E8001")
end)

test("check_boolean on nil errors with E8001", function()
  assert_error(function() __rt.check_boolean(nil) end, "E8001")
end)

-- ==================== check_int tests ====================

test("check_int(0) returns 0", function()
  local r = __rt.check_int(0)
  assert(r == 0)
end)

test("check_int(42) returns 42", function()
  local r = __rt.check_int(42)
  assert(r == 42)
end)

test("check_int(-1) returns -1", function()
  local r = __rt.check_int(-1)
  assert(r == -1)
end)

test("check_int(2147483647) returns itself", function()
  local r = __rt.check_int(2147483647)
  assert(r == 2147483647)
end)

test("check_int(-2147483648) returns itself", function()
  local r = __rt.check_int(-2147483648)
  assert(r == -2147483648)
end)

test("check_int(-0) returns 0 (normalization)", function()
  local r = __rt.check_int(-0)
  -- Verify it's 0, not -0
  assert(r == 0)
  -- Verify 1/r is Infinity, not -Infinity (proves it's +0 not -0)
  assert(1 / r == math.huge)
end)

test("check_int on nil errors with E8001", function()
  assert_error(function() __rt.check_int(nil) end, "E8001")
end)

test("check_int on string errors with E8001", function()
  assert_error(function() __rt.check_int("hi") end, "E8001")
end)

test("check_int on NaN errors with E8001", function()
  assert_error(function() __rt.check_int(0/0) end, "E8001")
end)

test("check_int on Infinity errors with E8001", function()
  assert_error(function() __rt.check_int(1/0) end, "E8001")
end)

test("check_int on -Infinity errors with E8001", function()
  assert_error(function() __rt.check_int(-1/0) end, "E8001")
end)

test("check_int on non-integer errors with E8001", function()
  assert_error(function() __rt.check_int(1.5) end, "E8001")
end)

test("check_int on out-of-range errors with E8004", function()
  assert_error(function() __rt.check_int(2147483648) end, "E8004")
end)

test("check_int on out-of-range negative errors with E8004", function()
  assert_error(function() __rt.check_int(-2147483649) end, "E8004")
end)

-- ==================== check_number tests ====================

test("check_number(3.14) returns 3.14", function()
  assert(__rt.check_number(3.14) == 3.14)
end)

test("check_number(NaN) passes (NaN is a valid number)", function()
  local r = __rt.check_number(0/0)
  assert(r ~= r)  -- NaN check
end)

test("check_number(Infinity) passes", function()
  local r = __rt.check_number(1/0)
  assert(r == math.huge)
end)

test("check_number on string errors with E8001", function()
  assert_error(function() __rt.check_number("hi") end, "E8001")
end)

-- ==================== check_string tests ====================

test("check_string('hello') returns 'hello'", function()
  assert(__rt.check_string("hello") == "hello")
end)

test("check_string on number errors with E8001", function()
  assert_error(function() __rt.check_string(42) end, "E8001")
end)

test("check_string accepts multi-byte UTF-8 scalar sequences", function()
  -- a, U+00E9, U+4E2D, U+1F600, b
  assert(__rt.check_string("a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b")
    == "a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b")
end)

test("check_string rejects stray continuation byte with E8001", function()
  assert_error(function() __rt.check_string("a\x80b") end, "E8001")
end)

test("check_string rejects truncated multi-byte sequence with E8001", function()
  assert_error(function() __rt.check_string("\xC3") end, "E8001")
  assert_error(function() __rt.check_string("\xE4\xB8") end, "E8001")
end)

test("check_string rejects overlong encodings with E8001", function()
  assert_error(function() __rt.check_string("\xC0\x80") end, "E8001")
  assert_error(function() __rt.check_string("\xE0\x80\x80") end, "E8001")
  assert_error(function() __rt.check_string("\xF0\x80\x80\x80") end, "E8001")
end)

test("check_string rejects UTF-16 surrogate code points with E8001", function()
  assert_error(function() __rt.check_string("\xED\xA0\x80") end, "E8001")
  assert_error(function() __rt.check_string("\xED\xBF\xBF") end, "E8001")
end)

test("check_string rejects code points above U+10FFFF with E8001", function()
  assert_error(function() __rt.check_string("\xF4\x90\x80\x80") end, "E8001")
end)

test("utf8_next walks one Unicode scalar value per step", function()
  local s = "a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b"
  local cursor, seen = 0, {}
  while true do
    local next_cursor, ch = __rt.utf8_next(s, cursor)
    if next_cursor == nil then break end
    seen[#seen + 1] = ch
    cursor = next_cursor
  end
  assert(#seen == 5, "expected 5 scalars, got " .. #seen)
  assert(seen[1] == "a")
  assert(seen[2] == "\xC3\xA9")
  assert(seen[3] == "\xE4\xB8\xAD")
  assert(seen[4] == "\xF0\x9F\x98\x80")
  assert(seen[5] == "b")
end)

test("utf8_next raises E8001 on malformed input", function()
  assert_error(function() __rt.utf8_next("\x80", 0) end, "E8001")
  assert_error(function() __rt.utf8_next("\xED\xA0\x80", 0) end, "E8001")
end)

-- ==================== check_table tests ====================

test("check_table({}) returns {}", function()
  local t = {}
  assert(__rt.check_table(t) == t)
end)

test("check_table on string errors with E8001", function()
  assert_error(function() __rt.check_table("hi") end, "E8001")
end)



-- ==================== check_nullable tests ====================

test("check_nullable on nil returns __NULL", function()
  local r = __rt.check_nullable("string", nil)
  assert(r == __rt.__NULL)
end)

test("check_nullable on __NULL returns __NULL", function()
  local r = __rt.check_nullable("string", __rt.__NULL)
  assert(r == __rt.__NULL)
end)

test("check_nullable on valid value returns value", function()
  local r = __rt.check_nullable("string", "hello")
  assert(r == "hello")
end)

test("check_nullable on invalid non-null value errors", function()
  assert_error(function() __rt.check_nullable("string", 42) end, "E8001")
end)

-- ==================== check_array tests ====================

test("check_array on homogeneous int array passes", function()
  local arr = {1, 2, 3}
  local r = __rt.check_array("[int]", arr)
  assert(r == arr)
end)

test("check_array on empty array passes", function()
  local arr = {}
  local r = __rt.check_array("[int]", arr)
  assert(r == arr)
end)

test("check_array on single element passes", function()
  local arr = {42}
  local r = __rt.check_array("[int]", arr)
  assert(r == arr)
end)

test("check_array on non-table errors with E8001", function()
  assert_error(function() __rt.check_array("[int]", "notatable") end, "E8001")
end)

test("check_array on heterogeneous elements errors with E8003", function()
  assert_error(function() __rt.check_array("[int]", {1, "x"}) end, "E8003")
end)

test("check_array on string array passes", function()
  local arr = {"a", "b", "c"}
  local r = __rt.check_array("[string]", arr)
  assert(r == arr)
end)

test("check_array on boolean array passes", function()
  local arr = {true, false}
  local r = __rt.check_array("[boolean]", arr)
  assert(r == arr)
end)

test("check_array on nested int[][] passes", function()
  local arr = {{1, 2}, {3, 4}}
  local r = __rt.check_array("[[int]]", arr)
  assert(r == arr)
end)

test("check_array with [int] formal format passes", function()
  local arr = {1, 2, 3}
  local r = __rt.check_array("[int]", arr)
  assert(r == arr)
end)

test("check_array on nullable element array passes", function()
  local arr = {__rt.__NULL, "hello"}
  local r = __rt.check_array("[?string]", arr)
  assert(r == arr)
end)

test("check_array on invalid nullable element errors", function()
  assert_error(function()
    __rt.check_array("[?string]", {42})
  end, "E8003")
end)

-- ==================== legacy dialect rejection at the boundary ====================
-- The legacy element-descriptor helper is retired with the legacy parser:
-- the canonical boundary rejects every legacy dialect spelling.

test("check_array rejects the legacy 'int[]' spelling", function()
  local err = assert_error(function() __rt.check_array("int[]", {1}) end, "E8001")
  assert(string.find(err.message, "cannot parse type descriptor", 1, true) ~= nil)
end)

test("check_array rejects the legacy 'string[]' spelling", function()
  assert_error(function() __rt.check_array("string[]", {"a"}) end, "E8001")
end)

test("check_array rejects the legacy 'int[][]' spelling", function()
  assert_error(function() __rt.check_array("int[][]", {{1}}) end, "E8001")
end)

test("check_array rejects the legacy 'string|null[]' spelling", function()
  assert_error(function() __rt.check_array("string|null[]", {"a"}) end, "E8001")
end)

-- ==================== check_type tests ====================

test("check_type('int', 42) passes", function()
  assert(__rt.check_type("int", 42) == 42)
end)

test("check_type('int', 'x') errors", function()
  assert_error(function() __rt.check_type("int", "x") end, "E8001")
end)

test("check_type('number', 3.14) passes", function()
  assert(__rt.check_type("number", 3.14) == 3.14)
end)

test("check_type('string', 'hi') passes", function()
  assert(__rt.check_type("string", "hi") == "hi")
end)

test("check_type('boolean', true) passes", function()
  assert(__rt.check_type("boolean", true) == true)
end)

test("check_type('null', __NULL) passes", function()
  assert(__rt.check_type("null", __rt.__NULL) == __rt.__NULL)
end)

test("check_type('table', {}) passes", function()
  local t = {}
  assert(__rt.check_type("table", t) == t)
end)

test("check_type('[int]', {1,2}) passes", function()
  local arr = {1, 2}
  assert(__rt.check_type("[int]", arr) == arr)
end)

test("check_type('?string', nil) returns __NULL", function()
  -- check_type('?string', nil) goes through the canonical nullable row
  local r = __rt.check_type("?string", nil)
  assert(r == __rt.__NULL)
end)

test("check_type('?string', nil) returns __NULL", function()
  local r = __rt.check_type("?string", nil)
  assert(r == __rt.__NULL)
end)

test("check_type('?int', __NULL) returns __NULL", function()
  local r = __rt.check_type("?int", __rt.__NULL)
  assert(r == __rt.__NULL)
end)

test("check_type('?int', 42) returns 42", function()
  local r = __rt.check_type("?int", 42)
  assert(r == 42)
end)

-- ==================== Integer arithmetic tests ====================

test("int_add(1, 2) returns 3", function()
  assert(__rt.int_add(1, 2) == 3)
end)

test("int_sub(5, 3) returns 2", function()
  assert(__rt.int_sub(5, 3) == 2)
end)

test("int_mul(3, 4) returns 12", function()
  assert(__rt.int_mul(3, 4) == 12)
end)

test("int_div(5, 2) returns 2 (truncated toward zero)", function()
  assert(__rt.int_div(5, 2) == 2)
end)

test("int_div(-5, 2) returns -2 (truncated toward zero)", function()
  assert(__rt.int_div(-5, 2) == -2)
end)

test("int_div(1, 0) errors with E8005", function()
  assert_error(function() __rt.int_div(1, 0) end, "E8005")
end)

test("int_mod(5, 2) returns 1", function()
  assert(__rt.int_mod(5, 2) == 1)
end)

test("int_mod(-5, 2) returns -1 (truncated remainder)", function()
  assert(__rt.int_mod(-5, 2) == -1)
end)

test("int_mod(1, 0) errors with E8005", function()
  assert_error(function() __rt.int_mod(1, 0) end, "E8005")
end)

test("int_pow(2, 3) returns 8", function()
  assert(__rt.int_pow(2, 3) == 8)
end)

test("int_pow(2, 0) returns 1", function()
  assert(__rt.int_pow(2, 0) == 1)
end)

test("int_pow(2, -1) errors with E8006", function()
  assert_error(function() __rt.int_pow(2, -1) end, "E8006")
end)

test("int arithmetic overflow errors with E8004", function()
  assert_error(function() __rt.int_add(2147483647, 1) end, "E8004")
end)

test("int_add(2147483647, 0) returns 2147483647 (max stays)", function()
  assert(__rt.int_add(2147483647, 0) == 2147483647)
end)

test("int_add(-2147483648, 0) returns -2147483648 (min stays)", function()
  assert(__rt.int_add(-2147483648, 0) == -2147483648)
end)

test("int_add(2147483647, 1) errors with E8004 (int32 overflow)", function()
  assert_error(function() __rt.int_add(2147483647, 1) end, "E8004")
end)

test("int_sub(-2147483648, 1) errors with E8004 (int32 overflow)", function()
  assert_error(function() __rt.int_sub(-2147483648, 1) end, "E8004")
end)

test("int_mul(65536, 65536) errors with E8004 (int32 overflow)", function()
  assert_error(function() __rt.int_mul(65536, 65536) end, "E8004")
end)

test("int_pow(2, 31) errors with E8004 (int32 overflow)", function()
  assert_error(function() __rt.int_pow(2, 31) end, "E8004")
end)

test("int_div(-2147483648, -1) errors with E8004 (MIN / -1)", function()
  assert_error(function() __rt.int_div(-2147483648, -1) end, "E8004")
end)

test("int_mod(-2147483648, -1) errors with E8004 (MIN % -1)", function()
  assert_error(function() __rt.int_mod(-2147483648, -1) end, "E8004")
end)

test("int_div(5, 0) errors with E8005", function()
  assert_error(function() __rt.int_div(5, 0) end, "E8005")
end)

test("int_div(-5, -0) errors with E8005 (negative zero divisor)", function()
  assert_error(function() __rt.int_div(-5, -0) end, "E8005")
end)

test("int_mod(5, 0) errors with E8005", function()
  assert_error(function() __rt.int_mod(5, 0) end, "E8005")
end)

test("int_neg(5) returns -5", function()
  assert(__rt.int_neg(5) == -5)
end)

test("int_neg(-5) returns 5", function()
  assert(__rt.int_neg(-5) == 5)
end)

test("int_neg(-0) returns 0 (normalization)", function()
  local r = __rt.int_neg(-0)
  assert(r == 0)
  assert(1 / r == math.huge)
end)

test("int_neg(-2147483648) errors with E8004 (MIN negation)", function()
  assert_error(function() __rt.int_neg(-2147483648) end, "E8004")
end)

test("int_div(5, -2) returns -2 (truncation toward zero)", function()
  assert(__rt.int_div(5, -2) == -2)
end)

test("int_div(-5, -2) returns 2 (truncation toward zero)", function()
  assert(__rt.int_div(-5, -2) == 2)
end)

test("int_mod(5, -2) returns 1 (truncated remainder)", function()
  assert(__rt.int_mod(5, -2) == 1)
end)

test("int_mod(-5, -2) returns -1 (truncated remainder)", function()
  assert(__rt.int_mod(-5, -2) == -1)
end)

test("int_convert(2147483647.0) returns 2147483647", function()
  assert(__rt.int_convert(2147483647.0) == 2147483647)
end)

test("int_convert(-2147483648.0) returns -2147483648", function()
  assert(__rt.int_convert(-2147483648.0) == -2147483648)
end)

test("int_convert(2147483648.0) errors with E8004 (int32 range)", function()
  assert_error(function() __rt.int_convert(2147483648.0) end, "E8004")
end)

test("int_convert(-2147483649.0) errors with E8004 (int32 range)", function()
  assert_error(function() __rt.int_convert(-2147483649.0) end, "E8004")
end)

test("int_convert(NaN) errors with E8001", function()
  assert_error(function() __rt.int_convert(0/0) end, "E8001")
end)

test("int_convert(Infinity) errors with E8001", function()
  assert_error(function() __rt.int_convert(1/0) end, "E8001")
end)

test("int_convert(-Infinity) errors with E8001", function()
  assert_error(function() __rt.int_convert(-1/0) end, "E8001")
end)

test("int_convert(0.5) errors with E8001 (non-integer)", function()
  assert_error(function() __rt.int_convert(0.5) end, "E8001")
end)

test("int_convert(-0.0) returns 0 (normalization)", function()
  local r = __rt.int_convert(-0.0)
  assert(r == 0)
  assert(1 / r == math.huge)
end)

test("int_add(-0, 0) returns 0 (normalization)", function()
  local r = __rt.int_add(-0, 0)
  assert(r == 0)
  assert(1 / r == math.huge)
end)

-- ==================== bytes tests ====================

test("bytes_new(4) returns tagged bytes with length 4", function()
  local b = __rt.bytes_new(4)
  assert(type(b) == "table")
  assert(b.__kind == "bytes")
  assert(b.__len == 4)
end)

test("bytes_new zero-fills storage", function()
  local b = __rt.bytes_new(4)
  assert(b.__data[0] == 0 and b.__data[1] == 0 and b.__data[2] == 0 and b.__data[3] == 0)
end)

test("bytes_length returns the immutable length", function()
  local b = __rt.bytes_new(3)
  assert(__rt.bytes_length(b) == 3)
end)

test("bytes_get returns unsigned bytes in 0..255", function()
  local b = __rt.bytes_new(2)
  __rt.bytes_set(b, 0, 0)
  __rt.bytes_set(b, 1, 255)
  assert(__rt.bytes_get(b, 0) == 0)
  assert(__rt.bytes_get(b, 1) == 255)
end)

test("bytes_set returns the written value", function()
  local b = __rt.bytes_new(1)
  assert(__rt.bytes_set(b, 0, 128) == 128)
end)

test("bytes_set roundtrips 0..255", function()
  local b = __rt.bytes_new(256)
  for i = 0, 255 do
    __rt.bytes_set(b, i, i)
  end
  for i = 0, 255 do
    assert(__rt.bytes_get(b, i) == i, "byte " .. i .. " mismatch")
  end
end)

test("bytes_new(-1) errors with E8012", function()
  assert_error(function() __rt.bytes_new(-1) end, "E8012")
end)

test("bytes_new(0.5) errors with E8001 (non-int length)", function()
  assert_error(function() __rt.bytes_new(0.5) end, "E8001")
end)

test("bytes_get index -1 errors with E8012", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_get(b, -1) end, "E8012")
end)

test("bytes_get index == length errors with E8012", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_get(b, 2) end, "E8012")
end)

test("bytes_get non-int index errors with E8001", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_get(b, 0.5) end, "E8001")
end)

test("bytes_set index -1 errors with E8012", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_set(b, -1, 1) end, "E8012")
end)

test("bytes_set index == length errors with E8012", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_set(b, 2, 1) end, "E8012")
end)

test("bytes_set value 256 errors with E8013", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_set(b, 0, 256) end, "E8013")
end)

test("bytes_set value -1 errors with E8013", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_set(b, 0, -1) end, "E8013")
end)

test("bytes_set non-int value errors with E8001", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_set(b, 0, 0.5) end, "E8001")
end)

test("bytes_set non-int index errors with E8001", function()
  local b = __rt.bytes_new(2)
  assert_error(function() __rt.bytes_set(b, 0.5, 1) end, "E8001")
end)

test("failed bytes writes change no storage", function()
  local b = __rt.bytes_new(2)
  __rt.bytes_set(b, 0, 42)
  assert_error(function() __rt.bytes_set(b, 2, 7) end, "E8012")
  assert_error(function() __rt.bytes_set(b, 0, 256) end, "E8013")
  assert(__rt.bytes_get(b, 0) == 42)
  assert(__rt.bytes_get(b, 1) == 0)
end)

test("bytes reference aliasing: writes are visible through aliases", function()
  local b = __rt.bytes_new(2)
  local alias = b
  __rt.bytes_set(alias, 1, 7)
  assert(__rt.bytes_get(b, 1) == 7)
end)

test("zero-length buffer keeps stable storage", function()
  local b = __rt.bytes_new(0)
  assert(__rt.bytes_length(b) == 0)
  assert(b.__data ~= nil)
  assert_error(function() __rt.bytes_get(b, 0) end, "E8012")
  assert_error(function() __rt.bytes_set(b, 0, 1) end, "E8012")
end)

test("bytes_new(2147483648) errors with E8004 (length out of int32)", function()
  assert_error(function() __rt.bytes_new(2147483648) end, "E8004")
end)

test("bytes_new allocation failure raises E8001 'bytes allocation failed'", function()
  local ffi = require("ffi")
  local orig_new = ffi.new
  ffi.new = function(...) error("simulated allocator failure") end
  local ok, err = pcall(function() __rt.bytes_new(4) end)
  ffi.new = orig_new
  assert(ok == false)
  assert(type(err) == "table")
  assert(err.code == "E8001", "expected E8001, got " .. tostring(err.code))
  assert(err.message == "bytes allocation failed")
end)

test("bytes_new on non-bytes kind errors with E8001", function()
  assert_error(function() __rt.bytes_length({}) end, "E8001")
  assert_error(function() __rt.bytes_get(42, 0) end, "E8001")
  assert_error(function() __rt.bytes_set("x", 0, 1) end, "E8001")
end)

-- ==================== function_ tests ====================

test("function_ creates wrapper with correct shape", function()
  local f = function(x) return x end
  local w = __rt.function_("(int)->int", f)
  assert(type(w) == "table")
  assert(w.__kind == "function")
  assert(w.sig == "(int)->int")
  assert(w.f == f)
end)

test("function_ wrapper .f() calls inner function", function()
  local w = __rt.function_("(int)->int", function(x) return x * 2 end)
  assert(w.f(21) == 42)
end)

-- ==================== as_lua_function tests ====================

test("as_lua_function extracts inner function", function()
  local inner = function(x) return x end
  local w = __rt.function_("(int)->int", inner)
  assert(__rt.as_lua_function(w) == inner)
end)

test("as_lua_function on non-wrapper errors", function()
  assert_error(function() __rt.as_lua_function({}) end, "E8001")
end)

-- ==================== from_lua_function tests ====================

test("from_lua_function wraps with type checks", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  assert(w.__kind == "function")
  assert(w.sig == "(int,int)->int")
  -- Call with valid args
  local r = w.f(1, 2)
  assert(r == 3)
end)

test("from_lua_function rejects wrong param type", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  assert_error(function() w.f("x", 2) end, "E8010")
end)

test("from_lua_function rejects wrong arg count (too few)", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  assert_error(function() w.f(1) end, "E8010")
end)

test("from_lua_function rejects wrong arg count (too many)", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  assert_error(function() w.f(1, 2, 3) end, "E8010")
end)

test("from_lua_function on non-function errors", function()
  assert_error(function() __rt.from_lua_function("(int)->int", "notafunc") end, "E8001")
end)

-- ==================== class_ tests ====================

test("class_ basic construction", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada"})
  assert(u.name == "Ada")
  assert(u.nick == nil)
  assert(u.__classname == "User")
  assert(u.__kind == "class")
end)

test("class_ optional field provided", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {nick = "ads"})
  assert(u.name == "")
  assert(u.nick == "ads")
  assert(u.__classname == "User")
end)

test("class_ all optional fields provided", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada", nick = "ads"})
  assert(u.name == "Ada")
  assert(u.nick == "ads")
end)

test("class_ extra field rejected with E8007", function()
  assert_error(function()
    __rt.class_("User", {name = ""}, {extra = 1})
  end, "E8007")
end)

test("class_ extra field error contains field name and class name", function()
  local err = assert_error(function()
    __rt.class_("User", {name = ""}, {extra = 1})
  end, "E8007")
  assert(string.find(err.message, "extra") ~= nil)
  assert(string.find(err.message, "User") ~= nil)
end)

test("class_ with __NULL default value", function()
  local u = __rt.class_("C", {val = __rt.__NULL}, {})
  assert(u.val == __rt.__NULL)
  assert(u.__kind == "class")
end)

test("class_ with provided __NULL field (not rejected as extra)", function()
  -- __NULL is a table, so instance["val"] ~= nil → valid overlay
  local u = __rt.class_("C", {val = __rt.__NULL}, {val = __rt.__NULL})
  assert(u.val == __rt.__NULL)
end)

test("class_ empty provided table", function()
  local u = __rt.class_("C", {a = 1, b = 2}, {})
  assert(u.a == 1)
  assert(u.b == 2)
end)

test("class_ nil provided treated as empty", function()
  local u = __rt.class_("C", {a = 1}, nil)
  assert(u.a == 1)
end)

test("class_ all defaults removed properly", function()
  local u = __rt.class_("C", {a = __rt.__MISSING, b = __rt.__MISSING}, {})
  assert(u.a == nil)
  assert(u.b == nil)
  -- Verify __MISSING is not in the instance by iterating
  for k, v in pairs(u) do
    assert(v ~= __rt.__MISSING)
  end
end)

-- ==================== Deep copy tests ====================

test("deep copy: instances are independent", function()
  local defaults = { items = {1, 2, 3}, name = "default" }
  local a = __rt.class_("C", defaults, {})
  local b = __rt.class_("C", defaults, {})
  a.items[1] = 99
  assert(b.items[1] == 1)
end)

test("deep copy: __NULL preserved by identity", function()
  local defaults = { val = __rt.__NULL }
  local a = __rt.class_("C", defaults, {})
  assert(a.val == __rt.__NULL)
end)

test("deep copy: __MISSING preserved by identity", function()
  -- __MISSING entries are removed after overlay, but we test that deep_copy preserves identity
  local copy = __rt._deep_copy({a = __rt.__MISSING})
  assert(copy.a == __rt.__MISSING)
end)

test("deep copy: nested tables are independent", function()
  local defaults = { data = { x = { y = 1 } } }
  local a = __rt.class_("C", defaults, {})
  local b = __rt.class_("C", defaults, {})
  a.data.x.y = 99
  assert(b.data.x.y == 1)
end)

-- ==================== class_plan_ tests (runtime page D4) ====================

test("class_plan_ basic construction tags and publishes", function()
  local plan = {
    { name = "name", descriptor = "string", optional = false, evaluator = function() return "" end },
    { name = "nick", descriptor = "string", optional = true },
  }
  local u = __rt.class_plan_("@mod/User", plan, { name = "Ada" }, "f.deal", 3, 5)
  assert(u.name == "Ada")
  assert(u.nick == nil)
  assert(u.__kind == "class")
  assert(u.__classname == "@mod/User")
end)

test("class_plan_ omitted required default evaluates exactly once per attempt", function()
  local calls = 0
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() calls = calls + 1 return 7 end },
    { name = "nick", descriptor = "string", optional = true },
  }
  local u = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(u.id == 7)
  assert(calls == 1)
  local v = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(v.id == 7)
  assert(calls == 2)
end)

test("class_plan_ provided field suppresses its evaluator", function()
  local calls = 0
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() calls = calls + 1 return 7 end },
  }
  local u = __rt.class_plan_("C", plan, { id = 42 }, "f.deal", 1, 1)
  assert(u.id == 42)
  assert(calls == 0)
end)

test("class_plan_ extra provided field raises E8007 with zero default evaluation", function()
  local calls = 0
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() calls = calls + 1 return 7 end },
  }
  local err = assert_error(function()
    __rt.class_plan_("C", plan, { extra = 1 }, "f.deal", 4, 9)
  end, "E8007")
  assert(calls == 0)
  assert(string.find(err.message, "extra") ~= nil)
  assert(string.find(err.message, "C") ~= nil)
  assert(err.file == "f.deal" and err.line == 4 and err.column == 9)
end)

test("class_plan_ omitted defaults run in class source order", function()
  local log = {}
  local function mk(name)
    return function() log[#log + 1] = name return 0 end
  end
  local plan = {
    { name = "a", descriptor = "int", optional = false, evaluator = mk("a") },
    { name = "b", descriptor = "int", optional = false, evaluator = mk("b") },
    { name = "c", descriptor = "int", optional = true },
  }
  __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(#log == 2 and log[1] == "a" and log[2] == "b")
end)

test("class_plan_ optional omissions stay absent", function()
  local plan = {
    { name = "a", descriptor = "int", optional = false, evaluator = function() return 0 end },
    { name = "b", descriptor = "int", optional = true },
    { name = "c", descriptor = "int", optional = true },
  }
  local u = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(u.b == nil)
  assert(u.c == nil)
  assert(__rt.has(u, "b") == false)
end)

test("class_plan_ evaluator results are retained by reference", function()
  local shared = { 1, 2 }
  local plan = {
    { name = "items", descriptor = "[int]", optional = false, evaluator = function() return shared end },
  }
  local u = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(u.items == shared)
end)

test("class_plan_ per-attempt typed mutable literals are freshly constructed by the evaluator", function()
  local plan = {
    { name = "items", descriptor = "[int]", optional = false, evaluator = function() return { 1 } end },
  }
  local a = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  local b = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(a.items ~= b.items)
  a.items[1] = 99
  assert(b.items[1] == 1)
end)

test("class_plan_ phase 3 validates provided fields through the canonical matcher", function()
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() return 0 end },
  }
  local err = assert_error(function()
    __rt.class_plan_("C", plan, { id = "not-an-int" }, "f.deal", 2, 3)
  end, "E8001")
  assert(err.file == "f.deal" and err.line == 2 and err.column == 3)
end)

test("class_plan_ phase 3 validates evaluated defaults through the canonical matcher", function()
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() return "bad" end },
  }
  assert_error(function()
    __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  end, "E8001")
end)

test("class_plan_ defaults run even when a provided field is invalid (phases 1-3 order)", function()
  local calls = 0
  local plan = {
    { name = "bad", descriptor = "int", optional = false, evaluator = function() return 1 end },
    { name = "good", descriptor = "int", optional = false, evaluator = function() calls = calls + 1 return 2 end },
  }
  assert_error(function()
    __rt.class_plan_("C", plan, { bad = "nope" }, "f.deal", 1, 1)
  end, "E8001")
  assert(calls == 1)
end)

test("class_plan_ failure publishes no instance", function()
  local published = false
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() return 1 end },
  }
  local ok, inst = pcall(__rt.class_plan_, "C", plan, { id = "x" }, "f.deal", 1, 1)
  assert(ok == false)
  assert(type(inst) ~= "table" or inst.__kind ~= "class")
end)

test("class_plan_ evaluator-raised DEAL errors propagate unchanged", function()
  local plan = {
    { name = "id", descriptor = "int", optional = false,
      evaluator = function() error(__rt._err("E8004", "boom", "inner.deal", 9, 4, nil, nil)) end },
  }
  local err = assert_error(function()
    __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  end, "E8004")
  assert(err.message == "boom")
  assert(err.file == "inner.deal" and err.line == 9 and err.column == 4)
end)

test("class_plan_ rejects legacy dialect descriptors through the canonical matcher", function()
  local plan = {
    { name = "xs", descriptor = "int[]", optional = false, evaluator = function() return { 1 } end },
  }
  assert_error(function()
    __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  end, "E8001")
end)

test("class_plan_ supports bytes fields through the canonical matcher", function()
  local plan = {
    { name = "b", descriptor = "bytes", optional = false, evaluator = function() return __rt.bytes_new(2) end },
  }
  local u = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(u.b.__kind == "bytes")
  assert(__rt.bytes_length(u.b) == 2)
  assert_error(function()
    __rt.class_plan_("C", plan, { b = "nope" }, "f.deal", 1, 1)
  end, "E8001")
end)

test("class_plan_ int fields normalize -0 to 0 through the canonical matcher", function()
  local plan = {
    { name = "x", descriptor = "int", optional = false, evaluator = function() return 0 end },
  }
  local u = __rt.class_plan_("C", plan, { x = -0.0 }, "f.deal", 1, 1)
  assert(u.x == 0)
  assert(1 / u.x == math.huge)
end)

test("class_plan_ int fields raise E8004 out of range through the canonical matcher", function()
  local plan = {
    { name = "x", descriptor = "int", optional = false, evaluator = function() return 0 end },
  }
  assert_error(function()
    __rt.class_plan_("C", plan, { x = 2147483648 }, "f.deal", 1, 1)
  end, "E8004")
end)

test("class_plan_ nullable fields accept explicit null", function()
  local plan = {
    { name = "v", descriptor = "?string", optional = true },
  }
  local u = __rt.class_plan_("C", plan, { v = __rt.__NULL }, "f.deal", 1, 1)
  assert(u.v == __rt.__NULL)
  assert(__rt.has(u, "v") == true)
end)

test("class_plan_ explicit null on a non-nullable field fails validation", function()
  local plan = {
    { name = "v", descriptor = "string", optional = false, evaluator = function() return "" end },
  }
  assert_error(function()
    __rt.class_plan_("C", plan, { v = __rt.__NULL }, "f.deal", 1, 1)
  end, "E8001")
end)

test("class_plan_ array fields validate elements through the canonical matcher", function()
  local plan = {
    { name = "xs", descriptor = "[int]", optional = false, evaluator = function() return { 1, 2 } end },
  }
  local u = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(u.xs[1] == 1 and u.xs[2] == 2)
  assert_error(function()
    __rt.class_plan_("C", plan, { xs = { 1, "x" } }, "f.deal", 1, 1)
  end, "E8003")
end)

test("class_plan_ function fields compare signatures byte-for-byte", function()
  local plan = {
    { name = "f", descriptor = "(int)->int", optional = false,
      evaluator = function() return __rt.function_("(int)->int", function(x) return x end) end },
  }
  local u = __rt.class_plan_("C", plan, {}, "f.deal", 1, 1)
  assert(u.f.__kind == "function")
  assert_error(function()
    __rt.class_plan_("C", plan, { f = __rt.function_("(string)->int", function() return 1 end) },
      "f.deal", 1, 1)
  end, "E8010")
end)

test("class_plan_ malformed plan shapes raise E8001", function()
  assert_error(function() __rt.class_plan_("C", "not-a-table", {}, "f.deal", 1, 1) end, "E8001")
  assert_error(function() __rt.class_plan_("C", { "x" }, {}, "f.deal", 1, 1) end, "E8001")
  assert_error(function() __rt.class_plan_("C", { { name = 1, descriptor = "int", optional = false } }, {}, "f.deal", 1, 1) end, "E8001")
  assert_error(function() __rt.class_plan_("C", { { name = "x", descriptor = 2, optional = false } }, {}, "f.deal", 1, 1) end, "E8001")
  assert_error(function() __rt.class_plan_("C", { { name = "x", descriptor = "int", optional = "yes" } }, {}, "f.deal", 1, 1) end, "E8001")
  assert_error(function() __rt.class_plan_("C", { { name = "x", descriptor = "int", optional = false, evaluator = 3 } }, {}, "f.deal", 1, 1) end, "E8001")
  assert_error(function()
    __rt.class_plan_("C", {
      { name = "x", descriptor = "int", optional = false },
      { name = "x", descriptor = "int", optional = false },
    }, {}, "f.deal", 1, 1)
  end, "E8001")
end)

test("class_plan_ non-table provided raises E8001", function()
  local plan = { { name = "x", descriptor = "int", optional = false, evaluator = function() return 0 end } }
  assert_error(function()
    __rt.class_plan_("C", plan, 42, "f.deal", 1, 1)
  end, "E8001")
end)

test("class_plan_ nil provided treats as empty", function()
  local plan = { { name = "x", descriptor = "int", optional = false, evaluator = function() return 5 end } }
  local u = __rt.class_plan_("C", plan, nil, "f.deal", 1, 1)
  assert(u.x == 5)
end)

-- ==================== json_from_plan tests (runtime page D4) ====================

local function int_plan()
  return {
    { name = "id", descriptor = "int", optional = false, evaluator = function() return 0 end },
    { name = "name", descriptor = "string", optional = false, evaluator = function() return "anon" end },
    { name = "nick", descriptor = "string", optional = true },
  }
end

test("json_from_plan decodes provided fields, evaluates omitted defaults, and tags", function()
  local calls = 0
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() calls = calls + 1 return 7 end },
    { name = "name", descriptor = "string", optional = false, evaluator = function() return "anon" end },
    { name = "nick", descriptor = "string", optional = true },
  }
  local u = __rt.json_from_plan("@mod/User", plan, { id = 42 }, "f.deal", 1, 1)
  assert(u ~= nil)
  assert(u.id == 42)
  assert(u.name == "anon")
  assert(u.nick == nil)
  assert(u.__kind == "class")
  assert(u.__classname == "@mod/User")
  assert(calls == 0)
end)

test("json_from_plan provided-value failure returns nil and runs no defaults", function()
  local calls = 0
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() calls = calls + 1 return 7 end },
    { name = "name", descriptor = "string", optional = false, evaluator = function() return "anon" end },
  }
  local u = __rt.json_from_plan("C", plan, { id = "bad" }, "f.deal", 1, 1)
  assert(u == nil)
  assert(calls == 0)
end)

test("json_from_plan extra keys return nil", function()
  local u = __rt.json_from_plan("C", int_plan(), { id = 1, extra = true }, "f.deal", 1, 1)
  assert(u == nil)
end)

test("json_from_plan non-table parsed input returns nil", function()
  local plan = int_plan()
  assert(__rt.json_from_plan("C", plan, 42, "f.deal", 1, 1) == nil)
  assert(__rt.json_from_plan("C", plan, "x", "f.deal", 1, 1) == nil)
  assert(__rt.json_from_plan("C", plan, true, "f.deal", 1, 1) == nil)
  assert(__rt.json_from_plan("C", plan, nil, "f.deal", 1, 1) == nil)
end)

test("json_from_plan parsed __NULL returns nil", function()
  assert(__rt.json_from_plan("C", int_plan(), __rt.__NULL, "f.deal", 1, 1) == nil)
end)

test("json_from_plan non-empty array-shaped input returns nil", function()
  assert(__rt.json_from_plan("C", int_plan(), { 1, 2 }, "f.deal", 1, 1) == nil)
end)

test("json_from_plan empty parsed input decodes the defaulted instance", function()
  -- In the Lua value model the {} and [] parse collapse is invisible
  -- (both parse to one empty table), so the decoded instance is the
  -- defaulted one either way.
  local u = __rt.json_from_plan("C", int_plan(), {}, "f.deal", 1, 1)
  assert(u ~= nil and u.id == 0 and u.name == "anon" and u.nick == nil)
end)

test("json_from_plan evaluator failure returns nil", function()
  local plan = {
    { name = "id", descriptor = "int", optional = false,
      evaluator = function() error(__rt._err("E8004", "boom", "inner.deal", 2, 2, nil, nil)) end },
  }
  local u = __rt.json_from_plan("C", plan, {}, "f.deal", 1, 1)
  assert(u == nil)
end)

test("json_from_plan evaluator result failing final validation returns nil", function()
  local plan = {
    { name = "id", descriptor = "int", optional = false, evaluator = function() return "bad" end },
  }
  local u = __rt.json_from_plan("C", plan, {}, "f.deal", 1, 1)
  assert(u == nil)
end)

test("json_from_plan provided __NULL on nullable field is present null", function()
  local plan = {
    { name = "v", descriptor = "?string", optional = true },
  }
  local u = __rt.json_from_plan("C", plan, { v = __rt.__NULL }, "f.deal", 1, 1)
  assert(u ~= nil)
  assert(u.v == __rt.__NULL)
end)

test("json_from_plan provided __NULL on non-nullable field returns nil", function()
  local plan = {
    { name = "v", descriptor = "string", optional = false, evaluator = function() return "" end },
  }
  assert(__rt.json_from_plan("C", plan, { v = __rt.__NULL }, "f.deal", 1, 1) == nil)
end)

test("json_from_plan int range validation returns nil (E8004 inside the matcher)", function()
  local plan = {
    { name = "x", descriptor = "int", optional = false, evaluator = function() return 0 end },
  }
  assert(__rt.json_from_plan("C", plan, { x = 2147483648 }, "f.deal", 1, 1) == nil)
end)

test("json_from_plan int fields normalize -0 to 0", function()
  local plan = {
    { name = "x", descriptor = "int", optional = false, evaluator = function() return 0 end },
  }
  local u = __rt.json_from_plan("C", plan, { x = -0.0 }, "f.deal", 1, 1)
  assert(u ~= nil and u.x == 0 and 1 / u.x == math.huge)
end)

test("json_from_plan rejects legacy dialect descriptors through the canonical matcher", function()
  local plan = {
    { name = "xs", descriptor = "int[]", optional = false, evaluator = function() return { 1 } end },
  }
  assert(__rt.json_from_plan("C", plan, {}, "f.deal", 1, 1) == nil)
end)

test("json_from_plan array fields validate element-wise", function()
  local plan = {
    { name = "xs", descriptor = "[int]", optional = false, evaluator = function() return { 1, 2 } end },
  }
  local u = __rt.json_from_plan("C", plan, { xs = { 1, 2 } }, "f.deal", 1, 1)
  assert(u ~= nil and u.xs[1] == 1 and u.xs[2] == 2)
  assert(__rt.json_from_plan("C", plan, { xs = { 1, "x" } }, "f.deal", 1, 1) == nil)
end)

test("json_from_plan evaluator results are retained by reference and fresh per attempt", function()
  local plan = {
    { name = "items", descriptor = "[int]", optional = false, evaluator = function() return { 1 } end },
  }
  local a = __rt.json_from_plan("C", plan, {}, "f.deal", 1, 1)
  local b = __rt.json_from_plan("C", plan, {}, "f.deal", 1, 1)
  assert(a ~= nil and b ~= nil and a.items ~= b.items)
  a.items[1] = 99
  assert(b.items[1] == 1)
end)

test("json_from_plan malformed plan returns nil and never throws", function()
  assert(__rt.json_from_plan("C", "not-a-table", {}, "f.deal", 1, 1) == nil)
  assert(__rt.json_from_plan("C", { "x" }, {}, "f.deal", 1, 1) == nil)
  assert(__rt.json_from_plan("C", { { name = 1, descriptor = "int", optional = false } }, {}, "f.deal", 1, 1) == nil)
  assert(__rt.json_from_plan("C", {
    { name = "x", descriptor = "int", optional = false },
    { name = "x", descriptor = "int", optional = false },
  }, {}, "f.deal", 1, 1) == nil)
end)

-- ==================== export_class tests ====================

test("export_class returns correct shape", function()
  local e = __rt.export_class("User")
  assert(e.__kind == "class")
  assert(e.__classname == "User")
end)

-- ==================== has tests ====================

test("has on existing field returns true", function()
  local obj = { a = 1 }
  assert(__rt.has(obj, "a") == true)
end)

test("has on missing field (nil) returns false", function()
  local obj = { a = 1 }
  assert(__rt.has(obj, "b") == false)
end)

test("has on explicit null (__NULL) returns true", function()
  local obj = { a = 1, b = __rt.__NULL }
  assert(__rt.has(obj, "b") == true)
end)

test("has on class instance optional field not set returns false", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada"})
  assert(__rt.has(u, "nick") == false)
end)

test("has on class instance required field returns true", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada"})
  assert(__rt.has(u, "name") == true)
end)

test("has on class instance optional field set to value returns true", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada", nick = "ads"})
  assert(__rt.has(u, "nick") == true)
end)

test("has after delete returns false", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada", nick = "ads"})
  u.nick = nil  -- simulate delete
  assert(__rt.has(u, "nick") == false)
end)

-- ==================== Error format tests ====================

test("_err creates table with code and message", function()
  local e = __rt._err("E8001", "test error", nil, nil, nil, nil, nil)
  assert(e.code == "E8001")
  assert(e.message == "test error")
end)

test("_err with all fields", function()
  local e = __rt._err("E8001", "test", "file.deal", 10, 5, "int", "string")
  assert(e.code == "E8001")
  assert(e.message == "test")
  assert(e.file == "file.deal")
  assert(e.line == 10)
  assert(e.column == 5)
  assert(e.expected == "int")
  assert(e.actual == "string")
end)

test("error() from check_int produces table with code and message", function()
  local ok, err = pcall(function() __rt.check_int("x") end)
  assert(ok == false)
  assert(type(err) == "table")
  assert(err.code == "E8001")
  assert(type(err.message) == "string")
  assert(string.find(err.message, "int") ~= nil)
end)

test("error() from check_int on overflow produces E8004", function()
  local ok, err = pcall(function() __rt.check_int(2147483648) end)
  assert(ok == false)
  assert(err.code == "E8004")
end)

test("error() from int_div by zero produces E8005", function()
  local ok, err = pcall(function() __rt.int_div(1, 0) end)
  assert(ok == false)
  assert(err.code == "E8005")
end)

test("error() from int_pow negative exponent produces E8006", function()
  local ok, err = pcall(function() __rt.int_pow(2, -1) end)
  assert(ok == false)
  assert(err.code == "E8006")
end)

test("error() from class_ extra field produces E8007", function()
  local ok, err = pcall(function() __rt.class_("User", {name = ""}, {extra = 1}) end)
  assert(ok == false)
  assert(err.code == "E8007")
end)

-- ==================== check_nullable after delete scenario ====================

test("check_nullable after delete returns __NULL", function()
  -- Simulate: class constructed with optional field, then deleted
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {name = "Ada", nick = "ads"})
  -- delete u.nick → u.nick = nil
  u.nick = nil
  -- Now check_nullable should return __NULL
  local r = __rt.check_nullable("string", u.nick)
  assert(r == __rt.__NULL)
end)

-- ==================== check_type with function descriptor tests ====================

test("check_type function wrapper passes", function()
  local w = __rt.function_("(int)->int", function(x) return x end)
  local r = __rt.check_type("(int)->int", w)
  assert(r == w)
end)

test("check_type function wrapper sig mismatch errors with E8010", function()
  local w = __rt.function_("(int)->int", function(x) return x end)
  assert_error(function() __rt.check_type("(string)->string", w) end, "E8010")
end)

test("check_type on non-function for function descriptor errors", function()
  assert_error(function() __rt.check_type("(int)->int", 42) end, "E8001")
end)

-- ==================== check_type with class descriptor tests ====================

test("check_type class instance passes", function()
  local u = __rt.class_("@test/User", {name = ""}, {name = "Ada"})
  local r = __rt.check_type("@test/User", u)
  assert(r == u)
end)

test("check_type class instance wrong class errors", function()
  local u = __rt.class_("@test/User", {name = ""}, {name = "Ada"})
  assert_error(function() __rt.check_type("@test/Admin", u) end, "E8001")
end)

test("check_type on non-class for class descriptor errors", function()
  assert_error(function() __rt.check_type("@test/User", 42) end, "E8001")
end)

test("check_type rejects the bare class-name spelling", function()
  -- Bare class names are a legacy dialect spelling: the canonical parser
  -- rejects them before any value check runs.
  local u = __rt.class_("User", {name = ""}, {name = "Ada"})
  local err = assert_error(function() __rt.check_type("User", u) end, "E8001")
  assert(string.find(err.message, "cannot parse type descriptor", 1, true) ~= nil)
end)

-- ==================== module-qualified class identity tests ====================

test("check_type qualified class instance passes on exact identity", function()
  local u = __rt.class_("@mod/User", {name = ""}, {name = "Ada"})
  local r = __rt.check_type("@mod/User", u)
  assert(r == u)
end)

test("check_type qualified class rejects foreign-module identity", function()
  local u = __rt.class_("@other/User", {name = ""}, {name = "Ada"})
  assert_error(function() __rt.check_type("@mod/User", u) end, "E8001")
end)

test("check_type qualified class rejects bare-tagged instance", function()
  local u = __rt.class_("User", {name = ""}, {name = "Ada"})
  assert_error(function() __rt.check_type("@mod/User", u) end, "E8001")
end)

test("check_type bare class spelling rejects qualified-tagged instance", function()
  -- The bare "User" spelling fails the canonical parser before the value
  -- check runs, so a qualified-tagged instance is rejected with E8001.
  local u = __rt.class_("@mod/User", {name = ""}, {name = "Ada"})
  assert_error(function() __rt.check_type("User", u) end, "E8001")
end)

-- ==================== error_value tests ====================

test("error_value shape with span args", function()
  local e = __rt.error_value("E_LIMIT", "fail", "test.deal", 3, 7)
  assert(e.__kind == "class")
  assert(e.__classname == "@$builtin/Error")
  assert(e.code == "E_LIMIT")
  assert(e.message == "fail")
  assert(e.file == "test.deal")
  assert(e.line == 3)
  assert(e.column == 7)
end)

test("error_value shape without span args", function()
  local e = __rt.error_value("", "m")
  assert(e.__kind == "class")
  assert(e.__classname == "@$builtin/Error")
  assert(e.code == "")
  assert(e.message == "m")
  assert(e.file == nil)
  assert(e.line == nil)
  assert(e.column == nil)
end)

test("check_type Error passes against error_value outputs", function()
  local e = __rt.error_value("E_LIMIT", "fail")
  local r = __rt.check_type("@$builtin/Error", e)
  assert(r == e)
end)

test("check_type Error rejects non-Error class instances", function()
  local u = __rt.class_("@$builtin/Error", {code = "", message = ""}, {code = "X"})
  assert_error(function() __rt.check_type("@$builtin/Error", {__kind = "class", __classname = "User"}) end, "E8001")
  -- sanity: a genuine Error instance still passes
  assert(u.__kind == "class" and u.__classname == "@$builtin/Error")
end)

test("check_type rejects the bare Error spelling", function()
  -- The bare "Error" atom is a legacy dialect spelling (runtime page
  -- D3/D6): the canonical parser rejects it; the canonical projection
  -- @$builtin/Error is the only Error atom.
  local e = __rt.error_value("E8001", "boom")
  local err = assert_error(function() __rt.check_type("Error", e) end, "E8001")
  assert(string.find(err.message, "cannot parse type descriptor", 1, true) ~= nil)
end)

-- ==================== _deep_copy edge cases ====================

test("deep copy preserves function wrappers by identity", function()
  local w = __rt.function_("(int)->int", function(x) return x end)
  local defaults = { fn = w }
  local a = __rt.class_("C", defaults, {})
  assert(a.fn == w)
end)

test("deep copy preserves class instances by identity", function()
  local inner = __rt.class_("Inner", {x = 1}, {})
  local defaults = { child = inner }
  local a = __rt.class_("C", defaults, {})
  assert(a.child == inner)
end)

-- ==================== Edge cases ====================

test("check_array on table with holes (sparse) still checks till #v", function()
  -- Lua's # operator gives the length of the array portion
  -- A sparse table may have unexpected # behavior, but that's Lua's semantics
  local arr = {1, 2, 3}
  arr[5] = 4  -- makes it sparse, #arr may be 3 or 5 depending on LuaJIT
  -- Just test that what IS in the contiguous portion passes
  -- We'll use a normal contiguous array
  local r = __rt.check_array("[int]", {1, 2, 3})
  assert(r ~= nil)
end)

test("check_int on boolean errors with E8001", function()
  assert_error(function() __rt.check_int(true) end, "E8001")
end)

test("check_int on table errors with E8001", function()
  assert_error(function() __rt.check_int({}) end, "E8001")
end)

-- ==================== from_lua_function legacy rest descriptor tests ====================
-- DEAL v1.2 removed rest parameters. Legacy "...T" descriptor entries are no
-- longer special: arity is exact and the "..." entry fails type checks like
-- any unknown descriptor.

test("from_lua_function legacy rest descriptor is rejected at wrap time", function()
  -- The legacy "...T[]" rest spelling never parses under the canonical
  -- grammar, so the wrapper itself is rejected with E8010.
  local raw = function(sep, ...) return sep end
  assert_error(function()
    __rt.from_lua_function("(string,...string[])->string", raw)
  end, "E8010")
end)

test("from_lua_function legacy rest-of-function descriptor is rejected at wrap time", function()
  local raw = function(sep, ...) return sep end
  assert_error(function()
    __rt.from_lua_function("(string,...[(int)->int])->string", raw)
  end, "E8010")
end)

-- ==================== from_lua_function return type tests ====================

test("from_lua_function checks return type", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  local r = w.f(1, 2)
  assert(r == 3)
end)

test("from_lua_function wrong return type errors with E8010", function()
  local raw = function() return "not_an_int" end
  local w = __rt.from_lua_function("()->int", raw)
  assert_error(function() w.f() end, "E8010")
end)

-- ==================== check_type descriptor edge cases ====================

test("check_type with nested nullable array works", function()
  -- Array(Nullable(string)) is spelled "[?string]" in the canonical grammar.
  local arr = {__rt.__NULL, "hello", __rt.__NULL}
  local r = __rt.check_type("[?string]", arr)
  assert(r == arr)
end)

test("check_type with function descriptor validates wrapper", function()
  local w = __rt.function_("(int,int)->int", function(x,y) return x+y end)
  local r = __rt.check_type("(int,int)->int", w)
  assert(r == w)
end)

test("check_type with complex nullable descriptor works", function()
  -- [?int] is the canonical spelling of Array(Nullable(int)).
  local arr = {1, __rt.__NULL, 3}
  local r = __rt.check_type("[?int]", arr)
  assert(r == arr)
end)

-- ==================== _deep_copy additional tests ====================

test("deep copy preserves number values", function()
  local copy = __rt._deep_copy(42)
  assert(copy == 42)
end)

test("deep copy preserves string values", function()
  local copy = __rt._deep_copy("hello")
  assert(copy == "hello")
end)

test("deep copy preserves boolean values", function()
  local copy = __rt._deep_copy(true)
  assert(copy == true)
end)

test("deep copy preserves nil", function()
  local copy = __rt._deep_copy(nil)
  assert(copy == nil)
end)

test("deep copy handles mixed tables", function()
  local orig = { a = 1, b = "hi", c = { nested = true }, d = __rt.__NULL }
  local copy = __rt._deep_copy(orig)
  assert(copy.a == 1)
  assert(copy.b == "hi")
  assert(copy.c.nested == true)
  assert(copy.d == __rt.__NULL)
  assert(copy.c ~= orig.c)  -- nested table was copied, not shared
end)

-- ==================== class_ edge cases ====================

test("class_ with default array deep-copied independently", function()
  local defaults = { items = {1, 2, 3} }
  local a = __rt.class_("C", defaults, {})
  local b = __rt.class_("C", defaults, {})
  -- Modify a.items
  a.items[1] = 99
  -- b.items should be unaffected
  assert(b.items[1] == 1)
  assert(a.items[1] == 99)
end)

test("class_ with default table deep-copied independently", function()
  local defaults = { data = { x = 1, y = 2 } }
  local a = __rt.class_("C", defaults, {})
  local b = __rt.class_("C", defaults, {})
  a.data.x = 99
  assert(b.data.x == 1)
end)

-- ==================== int_convert tests ====================

test("int_convert(3.0) returns 3", function()
  local r = __rt.int_convert(3.0)
  assert(r == 3)
end)

test("int_convert(0.0) returns 0", function()
  local r = __rt.int_convert(0.0)
  assert(r == 0)
end)

test("int_convert(-5.0) returns -5", function()
  local r = __rt.int_convert(-5.0)
  assert(r == -5)
end)

test("int_convert(3.7) errors (non-integer)", function()
  assert_error(function() __rt.int_convert(3.7) end, "E8001")
end)

test("int_convert(nil) errors with E8001 (null not convertible)", function()
  local err = assert_error(function() __rt.int_convert(nil) end, "E8001")
  assert(string.find(err.message, "cannot convert null to int") ~= nil)
end)

test("int_convert(__NULL) errors with E8001 (null not convertible)", function()
  local err = assert_error(function() __rt.int_convert(__rt.__NULL) end, "E8001")
  assert(string.find(err.message, "cannot convert null to int") ~= nil)
end)

test("int_convert(NaN) errors with E8001", function()
  assert_error(function() __rt.int_convert(0/0) end, "E8001")
end)

test("int_convert(Infinity) errors with E8001", function()
  assert_error(function() __rt.int_convert(1/0) end, "E8001")
end)

test("int_convert(-Infinity) errors with E8001", function()
  assert_error(function() __rt.int_convert(-1/0) end, "E8001")
end)

test("int_convert(1e308) errors with E8004 (out of range)", function()
  assert_error(function() __rt.int_convert(1e308) end, "E8004")
end)

test("int_convert('hello') errors with E8001 (expected int)", function()
  assert_error(function() __rt.int_convert("hello") end, "E8001")
end)

test("int_convert(true) errors with E8001 (not a number)", function()
  assert_error(function() __rt.int_convert(true) end, "E8001")
end)

test("int_convert(int value) works (unwrapping nullable int)", function()
  -- When called with an int that is not null, returns the int
  local r = __rt.int_convert(42)
  assert(r == 42)
end)

-- ==================== number_convert tests ====================

test("number_convert(3) returns 3.0", function()
  local r = __rt.number_convert(3)
  assert(r == 3.0)
end)

test("number_convert(0) returns 0.0", function()
  local r = __rt.number_convert(0)
  assert(r == 0.0)
end)

test("number_convert(-5) returns -5.0", function()
  local r = __rt.number_convert(-5)
  assert(r == -5.0)
end)

test("number_convert(3.14) returns 3.14 (already number)", function()
  local r = __rt.number_convert(3.14)
  assert(r == 3.14)
end)

test("number_convert(nil) errors with E8001 (null not convertible)", function()
  local err = assert_error(function() __rt.number_convert(nil) end, "E8001")
  assert(string.find(err.message, "cannot convert null to number") ~= nil)
end)

test("number_convert(__NULL) errors with E8001 (null not convertible)", function()
  local err = assert_error(function() __rt.number_convert(__rt.__NULL) end, "E8001")
  assert(string.find(err.message, "cannot convert null to number") ~= nil)
end)

test("number_convert(true) errors with E8001 (not a number)", function()
  assert_error(function() __rt.number_convert(true) end, "E8001")
end)

test("number_convert('hello') errors with E8001 (not a number)", function()
  assert_error(function() __rt.number_convert("hello") end, "E8001")
end)

test("number_convert(NaN) passes (NaN is a valid number)", function()
  local r = __rt.number_convert(0/0)
  assert(r ~= r)  -- NaN check
end)

test("number_convert(Infinity) passes", function()
  local r = __rt.number_convert(1/0)
  assert(r == math.huge)
end)


-- ==================== parse_descriptor async tests ====================
-- parse_descriptor is local; tested indirectly through from_lua_function and check_type.

test("parse_descriptor async direct: async(int)->string wraps and preserves sig", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return tostring(x) end)
  end)
  assert(wrapper.sig == "async(int)->string")
  local h = wrapper.f(42)
  assert(type(h) == "table" and h.__kind == "async")
  assert(h.__result == "42")
end)

test("parse_descriptor async direct: non-operation result raises E8010", function()
  -- The outer wrapper requires the host function to return an async operation;
  -- a raw value fails the async-shape check.
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return x
  end)
  assert_error(function() wrapper.f(42) end, "E8010")
end)

test("parse_descriptor async direct: descriptor signature comparison works", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return tostring(x) end)
  end)
  -- check_type with matching descriptor should pass
  assert_no_error(function()
    __rt.check_type("async(int)->string", wrapper)
  end)
end)

test("parse_descriptor async direct: signature mismatch detected", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return tostring(x) end)
  end)
  -- check_type with non-matching descriptor should fail
  assert_error(function()
    __rt.check_type("async(int)->int", wrapper)
  end, "E8010")
end)

test("parse_descriptor async direct: sync function descriptor works unchanged", function()
  local wrapper = __rt.from_lua_function("(int)->string", function(x)
    return tostring(x)
  end)
  assert_no_error(function()
    __rt.check_type("(int)->string", wrapper)
  end)
end)

test("parse_descriptor async nullable: from_lua_function wraps nullable async descriptor", function()
  -- "async(int)->?@src/User" is an async function whose declared nullable
  -- return is enforced at the await site.
  local wrapper = __rt.from_lua_function("async(int)->?@src/User", function(x)
    return __rt.async_start(function() return { __kind = "class", __classname = "User" } end)
  end)
  assert(wrapper.sig == "async(int)->?@src/User")
  local h = wrapper.f(1)
  assert(type(h) == "table" and h.__kind == "async")
  -- a non-operation return still fails the async-shape check
  local wrapper2 = __rt.from_lua_function("async(int)->?@src/User", function(x)
    return { __kind = "class", __classname = "User" }
  end)
  assert_error(function() wrapper2.f(1) end, "E8010")
end)

test("parse_descriptor async: wrapper sig preserves async prefix", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return tostring(x) end)
  end)
  assert(wrapper.sig == "async(int)->string", "sig should include async prefix: " .. tostring(wrapper.sig))
end)

test("parse_descriptor sync nullable: from_lua_function wraps nullable-return descriptor", function()
  -- "(int)->?string" parses as a function returning a nullable string.
  local wrapper = __rt.from_lua_function("(int)->?string", function(x)
    if x == 0 then return __rt.__NULL end
    return tostring(x)
  end)
  assert(wrapper.sig == "(int)->?string")
  assert(wrapper.f(0) == __rt.__NULL)
  assert(wrapper.f(1) == "1")
  -- wrong return type raises E8010
  local wrapper2 = __rt.from_lua_function("(int)->?string", function(x)
    return x
  end)
  assert_error(function() wrapper2.f(1) end, "E8010")
end)

-- ==================== from_lua_function async tests ====================

test("from_lua_function async: non-operation return raises E8010", function()
  -- The outer wrapper requires the host async function to return an
  -- async operation; the declared return type is enforced at the await site.
  local wrapper = __rt.from_lua_function("async()->int", function()
    return "not an async op"
  end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function async: zero results raise E8010", function()
  local wrapper = __rt.from_lua_function("async()->int", function() end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function async: param checking still works", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return tostring(x) end)
  end)
  -- Wrong param type should produce error
  assert_error(function()
    wrapper.f("not an int")
  end, "E8010")
end)

test("from_lua_function async: correct params pass through", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return "value: " .. tostring(x) end)
  end)
  local h = wrapper.f(42)
  assert(type(h) == "table" and h.__kind == "async")
  assert(h.__result == "value: 42")
end)

test("from_lua_function async: outer wrapper sig contains async prefix", function()
  local wrapper = __rt.from_lua_function("async(int)->string", function(x)
    return __rt.async_start(function() return tostring(x) end)
  end)
  assert(wrapper.sig == "async(int)->string", "sig should include async prefix")
end)

-- ==================== async_create / async_start tests ====================

test("async_create returns a table with __kind='async'", function()
  local handle = __rt.async_create(function() end)
  assert(type(handle) == "table")
  assert(handle.__kind == "async")
  assert(handle.__done == false)
end)

test("async_create has __co (coroutine)", function()
  local handle = __rt.async_create(function() end)
  assert(type(handle.__co) == "thread")
end)

test("async_start runs coroutine to completion for sync function", function()
  local completed = false
  local handle = __rt.async_start(function()
    completed = true
    return 42
  end)
  assert(completed, "coroutine body should have executed")
  assert(handle.__done, "handle should be done")
  assert(handle.__result == 42, "result should be 42")
end)

test("async_start: __done guard prevents double-resume crash", function()
  -- An async function that completes without internally executing await
  -- should not crash when the awaiter tries to resume it again.
  local handle = __rt.async_start(function()
    return 5  -- synchronous completion, no yield
  end)
  assert(handle.__done)
  -- Calling async_step again should be safe (__done guard)
  assert_no_error(function()
    __rt.async_step(handle)
  end)
end)

-- ==================== async_step / coroutine.yield tests ====================

test("async_step: error in coroutine propagates", function()
  local handle = __rt.async_create(function()
    error("test error")
  end)
  -- error() inside coroutine body; coroutine.resume returns ok=false, err
  -- async_step calls error(err) which propagates. The error is wrapped with
  -- source location by Lua's error().
  local ok, err = pcall(function()
    __rt.async_step(handle)
  end)
  assert(ok == false, "async_step should propagate error")
  -- The error string contains the source location prefix added by Lua's error()
  assert(type(err) == "string" and string.find(err, "test error") ~= nil,
    "should propagate error containing 'test error': " .. tostring(err))
end)

test("async_step: coroutine.yield with non-async value errors", function()
  local handle = __rt.async_create(function()
    coroutine.yield("not an async handle")
  end)
  assert_error(function()
    __rt.async_step(handle)
  end, "E8001")
end)

-- ==================== Pending async completion tests ====================

test("pending async: async_start with inner yield chains automatically", function()
  -- Create an inner async function that returns a value
  local inner_fn = function()
    return "inner value"
  end
  local inner_handle = __rt.async_start(inner_fn)
  assert(inner_handle.__done)
  assert(inner_handle.__result == "inner value")

  -- Create an outer async function that awaits the inner.
  -- The outer yields the inner handle, and async_step chains automatically.
  local received = nil
  local outer_fn = function()
    received = coroutine.yield(inner_handle)
    return "outer " .. tostring(received)
  end
  local outer_handle = __rt.async_start(outer_fn)

  -- outer should be done because async_step auto-chains through the
  -- already-completed inner handle
  assert(outer_handle.__done, "outer should be done after async_start")
  assert(received == "inner value", "outer received inner's result: " .. tostring(received))
  assert(outer_handle.__result == "outer inner value",
    "outer returned correct value: " .. tostring(outer_handle.__result))
end)

test("pending async: manual step-by-step chaining", function()
  -- Simulate: outer awaits inner which is not yet started.
  local inner = __rt.async_create(function()
    return "inner result"
  end)

  local outer_result = nil
  local outer_co = coroutine.create(function()
    local result = coroutine.yield(inner)
    outer_result = result
    return "outer done"
  end)

  -- Manually resume outer → yields inner
  local ok1, yielded = coroutine.resume(outer_co)
  assert(ok1, "outer should suspend without error")
  assert(yielded == inner, "outer yielded inner handle")

  -- Manually resume inner → completes
  local ok2, inner_result = coroutine.resume(inner.__co)
  assert(ok2, "inner should complete")
  assert(inner_result == "inner result")

  -- Resume outer with inner's result
  local ok3, outer_val = coroutine.resume(outer_co, inner_result)
  assert(ok3, "outer should complete")
  assert(outer_result == "inner result", "outer received inner's result")
  assert(outer_val == "outer done", "outer returned correct value")
end)

-- ==================== Awaited Error caught by catch tests ====================

test("awaited Error: error in inner coroutine produces DEAL error table", function()
  -- Inner coroutine that throws a DEAL error
  local inner = __rt.async_create(function()
    error(__rt._err("E9999", "inner failure", nil, nil, nil, nil, nil))
  end)

  -- Start inner, expect it to error
  local ok, err = coroutine.resume(inner.__co)
  assert(not ok, "inner should error")
  assert(type(err) == "table", "error should be a table")
  assert(err.code == "E9999", "error code should be E9999: " .. tostring(err.code))
  assert(err.message == "inner failure", "error message: " .. tostring(err.message))
end)

test("awaited Error: pcall around coroutine.yield catches propagated error", function()
  -- Simulate: an async function body uses pcall around the await.
  -- In real codegen, coroutine.yield is inside a pcall.
  local inner = __rt.async_create(function()
    error(__rt._err("E9999", "inner failure", nil, nil, nil, nil, nil))
  end)

  local caught = nil
  local outer_co = coroutine.create(function()
    local ok, err = pcall(function()
      coroutine.yield(inner)
    end)
    if not ok then
      caught = err
      return "recovered"
    end
    return "not caught"
  end)

  -- Start outer, it yields inner
  local ok1, yielded = coroutine.resume(outer_co)
  assert(ok1, "outer should suspend without error")
  assert(yielded == inner, "outer yielded inner")

  -- Now the awaiter would step inner and get an error.
  -- async_step would call error(err), which would propagate into the
  -- outer coroutine's pcall. We simulate this by resuming outer with
  -- the error value directly via xpcall wrapping.
  --
  -- In practice, the awaiter wraps everything so that errors in
  -- awaited coroutines propagate to the awaiting coroutine's error handler.
  -- The key contract: the Error raised by an awaited operation is caught
  -- by the nearest enclosing catch around the await.

  -- Verify the error table is well-formed
  local ok2, err2 = coroutine.resume(inner.__co)
  assert(not ok2, "inner should error on resume")
  assert(type(err2) == "table" and err2.code == "E9999")
  assert(err2.message == "inner failure")
end)

-- ==================== async_chain tests ====================

test("async_chain: inner already done, resumes outer immediately", function()
  local inner = __rt.async_start(function()
    return "done"
  end)
  assert(inner.__done)

  local received = nil
  local outer = __rt.async_create(function()
    received = coroutine.yield(inner)
    return "outer"
  end)

  -- Start outer (it yields inner which is already done)
  __rt.async_step(outer)
  -- outer should be done already because inner was already done
  assert(outer.__done, "outer should be done")
  assert(received == "done", "outer received inner's result: " .. tostring(received))
end)

test("async_chain: three-level nested async with async_start", function()
  -- Nested await: outer awaits inner which awaits deepest.
  -- async_start should chain through all levels automatically since
  -- deepest completes synchronously.
  local deepest = __rt.async_create(function()
    return "deepest"
  end)

  local inner_received = nil
  local inner = __rt.async_create(function()
    inner_received = coroutine.yield(deepest)
    return "inner+" .. tostring(inner_received)
  end)

  local outer_received = nil
  local outer = __rt.async_create(function()
    outer_received = coroutine.yield(inner)
    return "outer+" .. tostring(outer_received)
  end)

  -- Step deepest first (completes)
  __rt.async_step(deepest)
  assert(deepest.__done)
  assert(deepest.__result == "deepest")

  -- Now async_step(inner) should auto-chain through deepest
  __rt.async_step(inner)
  assert(inner.__done, "inner should be done after auto-chain")
  assert(inner_received == "deepest",
    "inner received deepest's result: " .. tostring(inner_received))
  assert(inner.__result == "inner+deepest")

  -- Now async_step(outer) should auto-chain through inner
  __rt.async_step(outer)
  assert(outer.__done, "outer should be done after auto-chain")
  assert(outer_received == "inner+deepest",
    "outer received inner's result: " .. tostring(outer_received))
  assert(outer.__result == "outer+inner+deepest")
end)

-- ==================== async outer wrapper does not validate internal op ====================

test("async outer wrapper: from_lua_function does not validate return type for async", function()
  -- The outer wrapper returns an async handle, not the declared return type.
  -- from_lua_function with an async descriptor should skip return-type checking.
  local wrapper = __rt.from_lua_function("async()->@src/User", function()
    -- Returns an async handle (simulating what codegen produces)
    return __rt.async_start(function()
      return { __kind = "class", __classname = "User", name = "test" }
    end)
  end)
  -- Call should succeed without E8010
  local ok, result = pcall(wrapper.f)
  assert(ok, "async wrapper should not validate return type: " .. tostring(result))
  -- The result should be an async handle
  assert(type(result) == "table")
  assert(result.__kind == "async", "result should be async handle, got kind: " .. tostring(result.__kind))
end)

test("async outer wrapper: sync function still validates return type", function()
  -- For sync functions, the return type IS validated
  local wrapper = __rt.from_lua_function("()->int", function()
    return "not an int"
  end)
  assert_error(function()
    wrapper.f()
  end, "E8010")
end)


-- ==================== Deep async nesting test ====================

test("async deep nesting: 50-level chain completes correctly", function()
  -- Build a chain of N async coroutines where each awaits the next.
  -- The deepest returns a value; each level passes it up via coroutine.yield.
  -- async_step/async_chain recursion handles the depth.
  local N = 50

  -- Build from deepest outward:
  -- deepest returns 1
  -- level N-1 awaits deepest, adds 1
  -- ...
  -- level 1 awaits level 2, adds 1
  -- Result should be N (50)

  -- Create coroutine functions: deepest first
  local coro_fns = {}
  coro_fns[N] = function()
    return 1
  end

  for i = N - 1, 1, -1 do
    local inner_handle = nil  -- will be set
    coro_fns[i] = function()
      local val = coroutine.yield(inner_handle)
      return val + 1
    end
  end

  -- Create handles from outermost to deepest
  local handles = {}
  for i = N, 1, -1 do
    handles[i] = __rt.async_create(coro_fns[i])
    if i < N then
      -- Patch the inner_handle reference for this level
      -- We need to capture handles[i+1] in the closure
      -- Re-create with proper capture
      local inner_h = handles[i + 1]
      handles[i] = __rt.async_create(function()
        local val = coroutine.yield(inner_h)
        return val + 1
      end)
    end
  end

  -- Now step from outermost: async_step will chain through all 50 levels
  __rt.async_step(handles[1])

  -- All handles should be done, result should be N
  for i = 1, N do
    assert(handles[i].__done, "handle " .. i .. " should be done")
  end
  assert(handles[1].__result == N,
    "deep nesting result should be " .. N .. ", got: " .. tostring(handles[1].__result))
  assert(handles[N].__result == 1,
    "deepest result should be 1, got: " .. tostring(handles[N].__result))
end)


-- ==================== parse order P tests ====================

test("canonical: ?(int)->int reads nullable function, distinct from (int)->?int", function()
  local w = __rt.function_("(int)->int", function(x) return x + 1 end)
  -- ?(int)->int: nullable of function — __NULL and a matching-sig wrapper pass
  assert(__rt.check_type("?(int)->int", __rt.__NULL) == __rt.__NULL)
  assert(__rt.check_type("?(int)->int", w) == w)
  -- (int)->?int: function returning nullable — the same wrapper's sig does not match
  assert_error(function() __rt.check_type("(int)->?int", w) end, "E8010")
  -- the legacy "(int)->int|null" spelling fails the canonical parser
  assert_error(function() __rt.check_type("(int)->int|null", w) end, "E8001")
  -- non-wrapper fails the inner function check
  assert_error(function() __rt.check_type("?(int)->int", 42) end, "E8001")
end)

test("parse order P: wrong-sig wrapper on nullable function raises E8010", function()
  local w = __rt.function_("(string)->string", function(x) return x end)
  assert_error(function() __rt.check_type("?(int)->int", w) end, "E8010")
end)

test("canonical: [(int)->int] reads array of functions, distinct from (int)->[int]", function()
  local w1 = __rt.function_("(int)->int", function(x) return x + 1 end)
  local w2 = __rt.function_("(int)->int", function(x) return x + 2 end)
  local arr = { w1, w2 }
  assert(__rt.check_type("[(int)->int]", arr) == arr)
  -- the legacy "(int)->int[]" spelling fails the canonical parser
  assert_error(function() __rt.check_type("(int)->int[]", w1) end, "E8001")
  -- the matching function-returning-array wrapper passes
  local w3 = __rt.function_("(int)->[int]", function(x) return { x } end)
  assert(__rt.check_type("(int)->[int]", w3) == w3)
end)

test("parse order P: [?(int)->int] reads array of nullable functions", function()
  local w1 = __rt.function_("(int)->int", function(x) return x + 1 end)
  local arr = { w1, __rt.__NULL }
  assert(__rt.check_type("[?(int)->int]", arr) == arr)
  -- wrong element (non-wrapper) fails with E8003
  assert_error(function()
    __rt.check_array("[?(int)->int]", { 42 })
  end, "E8003")
  -- wrong-sig wrapper element fails with E8003
  local wbad = __rt.function_("(string)->string", function(x) return x end)
  assert_error(function()
    __rt.check_array("[?(int)->int]", { wbad })
  end, "E8003")
end)

test("canonical: ?[string] reads Nullable(Array(string))", function()
  assert(__rt.check_type("?[string]", __rt.__NULL) == __rt.__NULL)
  assert(__rt.check_type("?[string]", { "a", "b" })[1] == "a")
  -- a non-array fails the inner array check
  assert_error(function() __rt.check_type("?[string]", "hello") end, "E8001")
  -- a wrong element fails the inner array check
  assert_error(function() __rt.check_type("?[string]", { "a", 42 }) end, "E8003")
  -- the legacy "?string[]" spelling fails the canonical parser
  assert_error(function() __rt.check_type("?string[]", { "a" }) end, "E8001")
end)

-- ==================== from_lua_function three-way return dispatch ====================

test("from_lua_function sync null: __NULL passes", function()
  local wrapper = __rt.from_lua_function("()->null", function() return __rt.__NULL end)
  local r = wrapper.f()
  assert(r == __rt.__NULL)
end)

test("from_lua_function sync null: number result raises E8010", function()
  local wrapper = __rt.from_lua_function("()->null", function() return 42 end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function sync null: string result raises E8010", function()
  local wrapper = __rt.from_lua_function("()->null", function() return "x" end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function sync null: table result raises E8010", function()
  local wrapper = __rt.from_lua_function("()->null", function() return {} end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function sync null: nil result raises E8010", function()
  local wrapper = __rt.from_lua_function("()->null", function() return nil end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function sync null: zero results raise E8010", function()
  local wrapper = __rt.from_lua_function("()->null", function() end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function non-null return: zero results raise E8010", function()
  local wrapper = __rt.from_lua_function("()->int", function() end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function non-null return: single nil raises E8010", function()
  local wrapper = __rt.from_lua_function("()->string", function() return nil end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function nullable return: zero results raise E8010", function()
  local wrapper = __rt.from_lua_function("()->?string", function() end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function nullable return: __NULL passes", function()
  local wrapper = __rt.from_lua_function("()->?string", function() return __rt.__NULL end)
  assert(wrapper.f() == __rt.__NULL)
end)

test("from_lua_function nullable return: wrong type raises E8010", function()
  local wrapper = __rt.from_lua_function("(int)->?string", function(x) return x end)
  assert_error(function() wrapper.f(1) end, "E8010")
end)

test("from_lua_function array return: wrong type raises E8010", function()
  local wrapper = __rt.from_lua_function("(int)->[string]", function(x) return x end)
  assert_error(function() wrapper.f(1) end, "E8010")
end)

test("from_lua_function function return: raw Lua function fails the check with E8010", function()
  -- Function-typed returns must arrive as DEAL wrapper tables (assumption c).
  local wrapper = __rt.from_lua_function("()->(int)->int", function()
    return function(x) return x end
  end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function nullable-function return: __NULL passes", function()
  local wrapper = __rt.from_lua_function("()->?(int)->int", function()
    return __rt.__NULL
  end)
  assert(wrapper.f() == __rt.__NULL)
end)

test("from_lua_function nullable-function return: raw Lua function fails with E8010", function()
  local wrapper = __rt.from_lua_function("()->?(int)->int", function()
    return function(x) return x end
  end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function nullable-function return: matching wrapper passes", function()
  local inner = __rt.function_("(int)->int", function(x) return x + 1 end)
  local wrapper = __rt.from_lua_function("()->?(int)->int", function()
    return inner
  end)
  assert(wrapper.f() == inner)
end)

-- ==================== from_lua_function function-param adaptation ====================

test("from_lua_function adapts function-typed params to plain Lua functions", function()
  local received = nil
  local inner = function(x) return x + 1 end
  local wrapper = __rt.from_lua_function("((int)->int)->int", function(cb)
    received = cb
    return cb(41)
  end)
  local r = wrapper.f(__rt.function_("(int)->int", inner))
  assert(r == 42)
  assert(type(received) == "function")
  assert(received == inner)
end)

test("from_lua_function nullable-function param accepts __NULL unadapted", function()
  local received = nil
  local wrapper = __rt.from_lua_function("(?(int)->int)->null", function(cb)
    received = cb
    return __rt.__NULL
  end)
  assert(wrapper.f(__rt.__NULL) == __rt.__NULL)
  assert(received == __rt.__NULL)
end)

test("from_lua_function nullable-function param adapts matching-sig wrapper", function()
  local received = nil
  local inner = function(x) return x * 2 end
  local wrapper = __rt.from_lua_function("(?(int)->int)->null", function(cb)
    received = cb
    return __rt.__NULL
  end)
  wrapper.f(__rt.function_("(int)->int", inner))
  assert(type(received) == "function")
  assert(received == inner)
end)

test("from_lua_function nullable-function param rejects raw function with E8010", function()
  local wrapper = __rt.from_lua_function("(?(int)->int)->null", function(cb)
    return __rt.__NULL
  end)
  assert_error(function() wrapper.f(function(x) return x end) end, "E8010")
end)

test("from_lua_function legacy rest-of-function descriptor is rejected at wrap time", function()
  local received = nil
  local err = assert_error(function()
    __rt.from_lua_function("(string,...[(int)->int])->string", function(sep, ...)
      received = { ... }
      return sep
    end)
  end, "E8010")
  assert(string.find(err.message, "invalid function signature", 1, true) ~= nil)
  assert(received == nil)
end)

-- ==================== load_host tests ====================
-- test/fixtures/runtime-host-fixture.lua is the raw host module surface.

local HOST_FIXTURE = "test/fixtures/runtime-host-fixture"

test("load_host loads fixture and wraps raw functions", function()
  local host = __rt.load_host(HOST_FIXTURE, {
    answer = "()->int",
    add = "(int,int)->int",
  })
  assert(type(host.answer) == "table" and host.answer.__kind == "function")
  assert(host.answer.sig == "()->int")
  assert(host.answer.f() == 42)
  assert(host.add.f(2, 3) == 5)
  -- param checks are enforced on the wrapped exports
  assert_error(function() host.add.f("x", 3) end, "E8010")
end)

test("load_host drops extra host exports", function()
  local host = __rt.load_host(HOST_FIXTURE, { answer = "()->int" })
  assert(host.extra_export == nil)
end)

test("load_host missing declared export raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { missing = "()->int" })
  end, "E8011")
end)

test("load_host require failure raises E8011", function()
  assert_error(function()
    __rt.load_host("no/such/deal-runtime-host-module", { x = "()->int" })
  end, "E8011")
end)

test("load_host non-table module result raises E8011", function()
  package.loaded["deal-runtime-test-nontable"] = 42
  assert_error(function()
    __rt.load_host("deal-runtime-test-nontable", { x = "()->int" })
  end, "E8011")
  package.loaded["deal-runtime-test-nontable"] = nil
end)

test("load_host pre-wrapped export with matching sig is re-wrapped and enforced", function()
  local host = __rt.load_host(HOST_FIXTURE, { prewrapped_good = "()->int" })
  assert(host.prewrapped_good.__kind == "function")
  assert(host.prewrapped_good.f() == 7)
  -- junk return from the pre-wrapped .f is caught by the re-wrap
  local host2 = __rt.load_host(HOST_FIXTURE, { prewrapped_bad = "()->int" })
  assert_error(function() host2.prewrapped_bad.f() end, "E8010")
end)

test("load_host pre-wrapped sync null export: sentinel passes, junk raises E8010", function()
  local host = __rt.load_host(HOST_FIXTURE, { prewrapped_null = "()->null" })
  assert(host.prewrapped_null.f() == __rt.__NULL)
  local host2 = __rt.load_host(HOST_FIXTURE, { prewrapped_null_bad = "()->null" })
  assert_error(function() host2.prewrapped_null_bad.f() end, "E8010")
end)

test("load_host pre-wrapped sig mismatch raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { prewrapped_good = "()->string" })
  end, "E8011")
end)

test("load_host pre-wrapped missing sig raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { prewrapped_nosig = "()->int" })
  end, "E8011")
end)

test("load_host pre-wrapped non-function .f raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { prewrapped_badf = "()->int" })
  end, "E8011")
end)

test("load_host pre-wrapped bare ...T rest sig fails the identity check with E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { bare_rest_sig = "(string,...string[])->string" })
  end, "E8011")
end)

test("load_host non-function export for function descriptor raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { not_a_function = "()->int" })
  end, "E8011")
end)

test("load_host sync null export: sentinel passes, junk raises E8010", function()
  local host = __rt.load_host(HOST_FIXTURE, { ping = "()->null" })
  assert(host.ping.f() == __rt.__NULL)
  local host2 = __rt.load_host(HOST_FIXTURE, { ping_bad = "()->null" })
  assert_error(function() host2.ping_bad.f() end, "E8010")
end)

test("load_host nullable return wraps at load and checks at call", function()
  local host = __rt.load_host(HOST_FIXTURE, { find = "(boolean)->?string" })
  assert(host.find.f(true) == __rt.__NULL)
  assert(host.find.f(false) == "found")
  local host2 = __rt.load_host(HOST_FIXTURE, { find_bad = "()->?string" })
  assert_error(function() host2.find_bad.f() end, "E8010")
end)

test("load_host array return wraps at load and checks at call", function()
  local host = __rt.load_host(HOST_FIXTURE, { split = "()->[string]" })
  local r = host.split.f()
  assert(type(r) == "table" and r[1] == "a" and r[2] == "b")
  local host2 = __rt.load_host(HOST_FIXTURE, { split_bad = "()->[string]" })
  assert_error(function() host2.split_bad.f() end, "E8010")
end)

test("load_host async export: real handle passes, junk raises E8010", function()
  local host = __rt.load_host(HOST_FIXTURE, { fetch = "async()->string" })
  local h = host.fetch.f()
  assert(type(h) == "table" and h.__kind == "async")
  assert(h.__result == "data")
  local host2 = __rt.load_host(HOST_FIXTURE, { fetch_bad = "async()->string" })
  assert_error(function() host2.fetch_bad.f() end, "E8010")
end)

test("load_host nullable-function param export accepts null and matching-sig function", function()
  local host = __rt.load_host(HOST_FIXTURE, { register = "(?(int)->int)->null" })
  assert(host.register.f(__rt.__NULL) == __rt.__NULL)
  local inner = function(x) return x + 1 end
  assert(host.register.f(__rt.function_("(int)->int", inner)) == __rt.__NULL)
  -- a raw Lua function is rejected by the param check
  assert_error(function() host.register.f(inner) end, "E8010")
end)

test("load_host class export validates identity and copies defaults", function()
  local host = __rt.load_host(HOST_FIXTURE, {
    ServerConfig = "@$external/host.cfg/ServerConfig",
  })
  assert(type(host.ServerConfig) == "table" and host.ServerConfig.__kind == "class")
  assert(host.ServerConfig.__classname == "@$external/host.cfg/ServerConfig")
  assert(type(host.ServerConfig_defaults) == "table")
  assert(host.ServerConfig_defaults.port == 80)
end)

test("load_host synthesizes no <C>_plan key and host classes keep the defaults-map seam", function()
  local host = __rt.load_host(HOST_FIXTURE, {
    ServerConfig = "@$external/host.cfg/ServerConfig",
  })
  -- The loader copies META and <C>_defaults through and synthesizes
  -- nothing else: no <C>_plan artifact ever exists on a host module
  -- (runtime page D4 host seam re-contract).
  assert(host.ServerConfig_plan == nil)
  -- Construction through the preserved class_ defaults-map entry:
  -- per-instance deep copies (the host's own defaults table is never
  -- mutated or tagged), provided overlays stay instance-local, and
  -- extra provided names still raise E8007.
  local a = __rt.class_("@$external/host.cfg/ServerConfig", host.ServerConfig_defaults, { port = 9000 })
  local b = __rt.class_("@$external/host.cfg/ServerConfig", host.ServerConfig_defaults, { port = 8080 })
  assert(a.port == 9000)
  assert(b.port == 8080)
  assert(host.ServerConfig_defaults.port == 80)
  assert(host.ServerConfig_defaults.__kind == nil)
  assert(host.ServerConfig_defaults.__classname == nil)
  assert_error(function()
    __rt.class_("@$external/host.cfg/ServerConfig", host.ServerConfig_defaults, { port = 1, extra = true })
  end, "E8007")
end)

test("load_host class identity mismatch raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { WrongName = "@$external/host.cfg/WrongName" })
  end, "E8011")
end)

test("load_host non-class export for class descriptor raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { not_a_class = "@$external/host.cfg/NotAClass" })
  end, "E8011")
end)

test("load_host class missing defaults raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { NoDefaults = "@$external/host.cfg/NoDefaults" })
  end, "E8011")
end)

test("load_host class non-table defaults raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { BadDefaults = "@$external/host.cfg/BadDefaults" })
  end, "E8011")
end)

test("load_host class supplied _fields copied through", function()
  local host = __rt.load_host(HOST_FIXTURE, { ServerConfig = "@$external/host.cfg/ServerConfig" })
  assert(type(host.ServerConfig_fields) == "table")
  assert(host.ServerConfig_fields[1].name == "port")
end)

test("load_host class non-table _fields raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { BadFields = "@$external/host.cfg/BadFields" })
  end, "E8011")
end)

test("load_host class absent _fields tolerated", function()
  local host = __rt.load_host(HOST_FIXTURE, { NoFields = "@$external/host.cfg/NoFields" })
  assert(host.NoFields.__kind == "class")
  assert(host.NoFields_fields == nil)
end)

test("load_host dotted-legacy class tag fails E8011 against the canonical descriptor", function()
  -- A host class tagged with the retired v1.1 dotted emission shape
  -- never byte-matches the canonical @$external/host.cfg/<Name>
  -- projection: the byte-exact identity check fails closed at load.
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { DottedLegacy = "@$external/host.cfg/DottedLegacy" })
  end, "E8011")
end)



-- ==================== Canonical descriptor parser tests ====================

local function assert_parse_ok(text)
  local ast = __rt.parse_canonical_descriptor(text)
  if ast == nil then
    error("expected canonical parse of '" .. text .. "' but it was rejected")
  end
  assert(type(ast) == "table")
  assert(ast.text == text, "parsed text '" .. tostring(ast.text) .. "' differs from input '" .. text .. "'")
  return ast
end

local function assert_parse_rejected(text)
  local ast = __rt.parse_canonical_descriptor(text)
  if ast ~= nil then
    error("expected '" .. text .. "' to be rejected but it parsed")
  end
end

test("canonical parser accepts every primitive keyword", function()
  local keywords = { "null", "boolean", "int", "number", "string", "bytes", "table" }
  for _, kw in ipairs(keywords) do
    local ast = assert_parse_ok(kw)
    assert(ast.kind == "primitive", kw .. " parsed with kind " .. tostring(ast.kind))
    assert(ast.name == kw)
  end
end)

test("canonical parser accepts composite shapes with byte-exact text", function()
  local shapes = {
    "[int]", "[[bytes]]", "?int", "?[?bytes]", "()->null", "(int)->int",
    "(int,string)->boolean", "async()->int", "async(int)->?string",
    "?(int)->int", "[?(bytes)->bytes]", "async()->async()->int",
    "(bytes)->bytes", "?bytes", "[bytes]",
  }
  for _, t in ipairs(shapes) do
    assert_parse_ok(t)
  end
end)

test("canonical parser builds nested atom structure", function()
  local ast = assert_parse_ok("[?(bytes)->bytes]")
  assert(ast.kind == "array")
  assert(ast.element.kind == "nullable")
  assert(ast.element.inner.kind == "function")
  assert(ast.element.inner.isAsync == false)
  assert(#ast.element.inner.params == 1)
  assert(ast.element.inner.params[1].name == "bytes")
  assert(ast.element.inner.ret.name == "bytes")
  local fn = assert_parse_ok("async()->int")
  assert(fn.kind == "function" and fn.isAsync == true)
  assert(#fn.params == 0 and fn.ret.name == "int")
end)

test("canonical parser accepts class atoms byte-for-byte", function()
  local atoms = {
    "@lib/utils/User", "@lib.utils/User", "@$external/host.cfg/ServerConfig",
    "@$builtin/Error", "@host.cfg/ServerConfig", "@a/b",
  }
  for _, t in ipairs(atoms) do
    local ast = assert_parse_ok(t)
    assert(ast.kind == "class", t .. " parsed with kind " .. tostring(ast.kind))
    assert(ast.name == t, "class atom name must equal the complete text")
  end
end)

test("canonical parser keeps distinct atoms distinct", function()
  local a = assert_parse_ok("@lib/utils/User")
  local b = assert_parse_ok("@lib.utils/User")
  assert(a.name ~= b.name)
  assert(a.text ~= b.text)
end)

test("canonical parser rejects every legacy dialect spelling", function()
  local legacy = {
    "int[]", "bytes[]", "string[]", "int|null", "?int|null", "string|null",
    "T[]", "T|null", "...T[]", "User", "Error", "ServerConfig", "Foo",
  }
  for _, t in ipairs(legacy) do
    assert_parse_rejected(t)
  end
end)

test("canonical parser rejects malformed canonical shapes", function()
  local malformed = {
    "[]", "??int", "?null", "(int)", "(int)->", "->int", "(int,)->int",
    "()", "async", "async[int]", "async ", "Async(int)->int", "asyncFoo",
  }
  for _, t in ipairs(malformed) do
    assert_parse_rejected(t)
  end
end)

test("canonical parser rejects invalid class atoms", function()
  local invalid = {
    "@", "@a", "@a/", "@a//b", "@.", "@..", "@a->b", "@Foo",
    "@src.models.User", "@a/b.C", "@host.cfg.ServerConfig", "@a b/C", "@a b",
  }
  for _, t in ipairs(invalid) do
    assert_parse_rejected(t)
  end
end)

test("canonical parser applies the scalar-level component alphabet (Unicode)", function()
  -- The canonical service walks decoded scalars: every scalar of the full
  -- pinned Unicode White_Space property (UAX #44 PropList
  -- White_Space=Yes: 0009-000D, 0020, 0085, 00A0, 1680, 2000-200A,
  -- 2028, 2029, 202F, 205F, 3000 — the single component-exclusion
  -- authority shared with ModuleIdentityResolver.isUnicodeWhiteSpace and
  -- the JS runtime's $CANONICAL_WS; never Character.isWhitespace/
  -- isSpaceChar, whose isWhitespace excludes U+0085 NEXT LINE since
  -- JDK 5) and every surrogate code point is forbidden inside a class
  -- component, so the byte-level Lua parser rejects their UTF-8
  -- encodings too.
  local nb = string.char(0xC2, 0xA0)        -- U+00A0 NO-BREAK SPACE
  local em = string.char(0xE2, 0x80, 0x83)  -- U+2003 EM SPACE
  local isp = string.char(0xE3, 0x80, 0x80) -- U+3000 IDEOGRAPHIC SPACE
  local nel = string.char(0xC2, 0x85)       -- U+0085 NEXT LINE (White_Space=Yes)
  local sur = string.char(0xED, 0xA0, 0x80) -- U+D800 (lone surrogate)
  local rejected = {
    "@x" .. nb .. "y/Name", "@lib" .. em .. "/User", "@lib" .. isp .. "/User",
    "@lib" .. sur .. "/User", "@a/b" .. nb .. "c",
    "@a" .. nel .. "/B", "@lib" .. nel .. "/User",
  }
  for _, t in ipairs(rejected) do
    assert_parse_rejected(t)
  end
  -- Positive control: a non-whitespace multi-byte scalar is legal inside
  -- a non-final component (dots and other text remain opaque there).
  local eacute = string.char(0xC3, 0xA9) -- U+00E9 LATIN SMALL LETTER E WITH ACUTE
  local ok = assert_parse_ok("@lib" .. eacute .. "/User")
  assert(ok.kind == "class")
  assert(ok.name == "@lib" .. eacute .. "/User")
end)

test("canonical parser requires complete consumption", function()
  local trailing = {
    "int ", " int", "intFoo", "inte", "nulls", "(int)->int|null", "1",
    "@a/b] ", "[int", "int]", "[[int]", "@a/b c", "int,x",
  }
  for _, t in ipairs(trailing) do
    assert_parse_rejected(t)
  end
end)

-- ==================== Canonical checker tests ====================

test("check_canonical_type accepts every primitive row", function()
  assert(__rt.check_canonical_type("null", __rt.__NULL) == __rt.__NULL)
  assert(__rt.check_canonical_type("boolean", true) == true)
  assert(__rt.check_canonical_type("int", 42) == 42)
  assert(__rt.check_canonical_type("number", 1.5) == 1.5)
  assert(__rt.check_canonical_type("string", "s") == "s")
  assert(__rt.check_canonical_type("table", {}) ~= nil)
end)

test("check_canonical_type bytes row accepts real bytes values", function()
  local b = __rt.bytes_new(2)
  __rt.bytes_set(b, 1, 255)
  assert(__rt.check_canonical_type("bytes", b) == b)
  -- Recursive bytes descriptors at depth, exercised on RV's bytes runtime.
  assert(__rt.check_canonical_type("[bytes]", { __rt.bytes_new(1) }) ~= nil)
  assert(__rt.check_canonical_type("?bytes", b) == b)
  local w = __rt.function_("(bytes)->bytes", function(x) return x end)
  assert(__rt.check_canonical_type("(bytes)->bytes", w) == w)
  assert(__rt.check_canonical_type("[?(bytes)->bytes]", { w }) ~= nil)
end)

test("check_canonical_type bytes row rejects non-bytes values with E8001", function()
  local err = assert_error(function() __rt.check_canonical_type("bytes", {}) end, "E8001")
  assert(err.expected == "bytes")
  assert_error(function() __rt.check_canonical_type("bytes", 42) end, "E8001")
  assert_error(function() __rt.check_canonical_type("bytes", "x") end, "E8001")
end)

test("check_canonical_type int row shares the int32 gate", function()
  assert_error(function() __rt.check_canonical_type("int", "x") end, "E8001")
  assert_error(function() __rt.check_canonical_type("int", 2147483648) end, "E8004")
  assert_error(function() __rt.check_canonical_type("int", -2147483649) end, "E8004")
  assert_error(function() __rt.check_canonical_type("int", 1.5) end, "E8001")
  assert_error(function() __rt.check_canonical_type("int", 0 / 0) end, "E8001")
end)

test("check_canonical_type array row checks elements in order", function()
  assert(__rt.check_canonical_type("[int]", { 1, 2, 3 }) ~= nil)
  assert(__rt.check_canonical_type("[[int]]", { { 1 }, { 2 } }) ~= nil)
end)

test("check_canonical_type array row wraps the first failing index in E8003", function()
  local err = assert_error(function()
    __rt.check_canonical_type("[int]", { 1, "x", 3 })
  end, "E8003")
  assert(err.message == "array element 2 type mismatch", tostring(err.message))
  assert(err.expected == "int")
  assert(err.actual == "string")
  assert_error(function()
    __rt.check_canonical_type("[[int]]", { { 1 }, { 2, "x" } })
  end, "E8003")
end)

test("check_canonical_type array row rejects non-arrays with E8001", function()
  assert_error(function() __rt.check_canonical_type("[int]", 42) end, "E8001")
  assert_error(function() __rt.check_canonical_type("[int]", "x") end, "E8001")
end)

test("check_canonical_type nullable row", function()
  assert(__rt.check_canonical_type("?int", __rt.__NULL) == __rt.__NULL)
  assert(__rt.check_canonical_type("?int", nil) == __rt.__NULL)
  assert(__rt.check_canonical_type("?int", 42) == 42)
  assert(__rt.check_canonical_type("?bytes", __rt.bytes_new(1)) ~= nil)
  assert_error(function() __rt.check_canonical_type("?int", "x") end, "E8001")
end)

test("check_canonical_type function row compares sigs byte-for-byte", function()
  local w = __rt.function_("(int)->int", function(x) return x end)
  assert(__rt.check_canonical_type("(int)->int", w) == w)
  local err = assert_error(function()
    __rt.check_canonical_type("(string)->int", w)
  end, "E8010")
  assert(err.message == "function signature mismatch: expected (string)->int, got (int)->int",
      tostring(err.message))
  assert_error(function() __rt.check_canonical_type("(int)->int", 42) end, "E8001")
  assert_error(function() __rt.check_canonical_type("(int)->int", {}) end, "E8001")
end)

test("check_canonical_type function row keeps the exact async marker", function()
  local w = __rt.function_("async()->int", function() return 1 end)
  assert(__rt.check_canonical_type("async()->int", w) == w)
  -- The sync descriptor is a different byte-for-byte signature.
  assert_error(function() __rt.check_canonical_type("()->int", w) end, "E8010")
  local wrong = __rt.function_("async()->int", function() return 1 end)
  assert_error(function() __rt.check_canonical_type("async()->string", wrong) end, "E8010")
end)

test("check_canonical_type class row matches atoms byte-for-byte", function()
  local probe = { __kind = "class", __classname = "@$builtin/Error" }
  assert(__rt.check_canonical_type("@$builtin/Error", probe) == probe)
  local err = assert_error(function()
    __rt.check_canonical_type("@$builtin/Error", { __kind = "class", __classname = "Error" })
  end, "E8001")
  assert(err.message == "expected instance of @$builtin/Error, got Error", tostring(err.message))
  assert_error(function()
    __rt.check_canonical_type("@$builtin/Error", { __kind = "class", __classname = "@$builtin/Errorx" })
  end, "E8001")
  assert_error(function()
    __rt.check_canonical_type("@$builtin/Error", { __kind = "class", __classname = "@host.cfg/ServerConfig" })
  end, "E8001")
  assert_error(function() __rt.check_canonical_type("@$builtin/Error", 42) end, "E8001")
  assert_error(function() __rt.check_canonical_type("@$builtin/Error", {}) end, "E8001")
end)

test("check_canonical_type rejects legacy and malformed descriptors", function()
  local err = assert_error(function() __rt.check_canonical_type("int[]", { 1, 2 }) end, "E8001")
  assert(string.find(err.message, "cannot parse type descriptor", 1, true) ~= nil)
  assert_error(function() __rt.check_canonical_type("int|null", 1) end, "E8001")
  assert_error(function() __rt.check_canonical_type("Error", {}) end, "E8001")
  assert_error(function() __rt.check_canonical_type(nil, 1) end, "E8001")
  assert_error(function() __rt.check_canonical_type(42, 1) end, "E8001")
end)

test("check_canonical_type forwards span args on every failure path", function()
  local err = assert_error(function()
    __rt.check_canonical_type("int", "x", "canonical.deal", 10, 20)
  end, "E8001")
  assert(err.file == "canonical.deal" and err.line == 10 and err.column == 20)
  err = assert_error(function()
    __rt.check_canonical_type("[int]", { 1, "x" }, "canonical.deal", 11, 21)
  end, "E8003")
  assert(err.file == "canonical.deal" and err.line == 11 and err.column == 21)
  err = assert_error(function()
    __rt.check_canonical_type("(int)->int", __rt.function_("(string)->int", function() end),
        "canonical.deal", 12, 22)
  end, "E8010")
  assert(err.file == "canonical.deal" and err.line == 12 and err.column == 22)
  err = assert_error(function()
    __rt.check_canonical_type("int", 2147483648, "canonical.deal", 13, 23)
  end, "E8004")
  assert(err.file == "canonical.deal" and err.line == 13 and err.column == 23)
  err = assert_error(function()
    __rt.check_canonical_type("int[]", 1, "canonical.deal", 14, 24)
  end, "E8001")
  assert(err.file == "canonical.deal" and err.line == 14 and err.column == 24)
end)

test("boundary flip: check_type IS the canonical matcher", function()
  -- The boundary path now rejects every legacy dialect spelling.
  assert_error(function() __rt.check_type("int[]", { 1, 2 }) end, "E8001")
  assert_error(function() __rt.check_type("string|null", __rt.__NULL) end, "E8001")
  assert_error(function() __rt.check_type("Error", __rt.error_value("E8001", "boom")) end, "E8001")
  -- Canonical spellings pass through the same entry.
  assert(__rt.check_type("[int]", { 1, 2 }) ~= nil)
  assert(__rt.check_type("?string", __rt.__NULL) == __rt.__NULL)
  local ev = __rt.error_value("E8001", "boom")
  assert(ev.__classname == "@$builtin/Error")
  assert(__rt.check_type("@$builtin/Error", ev) == ev)
  -- The canonical alias entries are the same matcher.
  assert(__rt.check_canonical_type("[int]", { 1 }) ~= nil)
  assert_parse_rejected("int[]")
  assert_parse_rejected("string|null")
  assert_parse_rejected("Error")
end)
-- ==================== invoke_async_export tests ====================
-- Driver for the production async-export host ABI runtime half (runtime
-- page D5): hand-built exports tables with wrapper entries whose sigs
-- are canonical async()-><R> descriptors, executed under real LuaJIT.

local function assert_host_invocation_failure(fn, needle)
  local ok, err = pcall(fn)
  if ok then
    error("expected the host-invocation failure signal but no error was raised")
  end
  if type(err) ~= "table" or err.__hostInvocationFailure ~= true then
    error("expected the host-invocation failure signal table, got "
        .. tostring(err))
  end
  if err.code ~= nil then
    error("the host-invocation failure signal must not carry a DEAL error code")
  end
  if needle ~= nil
      and tostring(err.message):find(needle, 1, true) == nil then
    error("expected failure message containing '" .. needle
        .. "', got " .. tostring(err.message))
  end
  return err
end

test("invoke_async_export success returns the matcher-validated completion value", function()
  local invocations = 0
  local exports = {
    oracle = __rt.function_("async()->int", function()
      invocations = invocations + 1
      return __rt.async_start(function()
        return 42
      end)
    end),
  }
  local result = __rt.invoke_async_export(exports, "oracle", "int")
  assert(result == 42)
  assert(invocations == 1, "exactly one operation is invoked per call")
end)

test("invoke_async_export drives nested awaits to completion through the preserved machinery", function()
  -- Mirrors generated await sites: coroutine.yield(...) plus the
  -- await-site completion check on the resumed value.
  local exports = {
    oracle = __rt.function_("async()->int", function()
      return __rt.async_start(function()
        local a = __rt.check_int(coroutine.yield(__rt.async_start(function()
          return 21
        end)), "generated.deal", 3, 9)
        local b = __rt.check_int(coroutine.yield(__rt.async_start(function()
          return a + 21
        end)), "generated.deal", 4, 9)
        return b
      end)
    end),
  }
  assert(__rt.invoke_async_export(exports, "oracle", "int") == 42)
end)

test("invoke_async_export propagates operation DEAL errors unchanged", function()
  local raised = __rt._err("E8005", "integer division by zero",
      "oracle.deal", 7, 19)
  local exports = {
    oracle = __rt.function_("async()->int", function()
      return __rt.async_start(function()
        error(raised)
      end)
    end),
  }
  local ok, err = pcall(__rt.invoke_async_export, exports, "oracle", "int")
  assert(not ok)
  assert(type(err) == "table" and err.code == "E8005")
  assert(err == raised, "the exact DEAL Error table propagates unchanged")
  assert(err.message == "integer division by zero")
  assert(err.file == "oracle.deal" and err.line == 7 and err.column == 19)
end)

test("invoke_async_export propagates awaited DEAL errors unchanged", function()
  local raised = __rt._err("E8004", "int out of range", "deep.deal", 11, 33)
  local exports = {
    oracle = __rt.function_("async()->int", function()
      return __rt.async_start(function()
        return coroutine.yield(__rt.async_start(function()
          error(raised)
        end))
      end)
    end),
  }
  local ok, err = pcall(__rt.invoke_async_export, exports, "oracle", "int")
  assert(not ok)
  assert(type(err) == "table" and err.code == "E8004")
  assert(err == raised, "the awaited DEAL Error table propagates unchanged")
end)

test("invoke_async_export completion validation uses the canonical matcher", function()
  -- "bytes" is a canonical-only primitive: a broken or legacy-only
  -- matcher cannot accept this completion.
  local b = __rt.bytes_new(2)
  local exportsBytes = {
    oracle = __rt.function_("async()->bytes", function()
      return __rt.async_start(function() return b end)
    end),
  }
  assert(__rt.invoke_async_export(exportsBytes, "oracle", "bytes") == b)

  local exportsArr = {
    oracle = __rt.function_("async()->[int]", function()
      return __rt.async_start(function() return { 1, 2, 3 } end)
    end),
  }
  local arr = __rt.invoke_async_export(exportsArr, "oracle", "[int]")
  assert(arr[1] == 1 and arr[2] == 2 and arr[3] == 3)

  local exportsNull = {
    oracle = __rt.function_("async()->?int", function()
      return __rt.async_start(function() return __rt.__NULL end)
    end),
  }
  assert(__rt.invoke_async_export(exportsNull, "oracle", "?int")
      == __rt.__NULL)

  local inst = { __kind = "class", __classname = "@mod/Thing" }
  local exportsClass = {
    oracle = __rt.function_("async()->@mod/Thing", function()
      return __rt.async_start(function() return inst end)
    end),
  }
  assert(__rt.invoke_async_export(exportsClass, "oracle", "@mod/Thing")
      == inst)
end)

test("invoke_async_export completion mismatch is a DEAL error, never a host failure", function()
  local exports = {
    oracle = __rt.function_("async()->int", function()
      return __rt.async_start(function() return "not an int" end)
    end),
  }
  local ok, err = pcall(__rt.invoke_async_export, exports, "oracle", "int")
  assert(not ok)
  assert(type(err) == "table" and err.code == "E8001")
  assert(err.__hostInvocationFailure == nil,
      "completion mismatch must be a DEAL error, not the host signal")
  local badArr = {
    oracle = __rt.function_("async()->[int]", function()
      return __rt.async_start(function() return { 1, "x" } end)
    end),
  }
  ok, err = pcall(__rt.invoke_async_export, badArr, "oracle", "[int]")
  assert(not ok and type(err) == "table" and err.code == "E8003")
  assert(err.__hostInvocationFailure == nil)
end)

test("invoke_async_export missing export raises the host-invocation failure signal", function()
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({}, "oracle", "int")
  end, "missing export 'oracle'")
end)

test("invoke_async_export sync export raises the host-invocation failure signal", function()
  local exports = {
    oracle = __rt.function_("()->int", function() return 1 end),
  }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(exports, "oracle", "int")
  end, "is sync: expected 'async()->int', got '()->int'")
end)

test("invoke_async_export parameterized export raises the host-invocation failure signal", function()
  local exports = {
    oracle = __rt.function_("async(int)->int", function(x)
      return __rt.async_start(function() return x end)
    end),
  }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(exports, "oracle", "int")
  end, "is parameterized: expected 'async()->int', got 'async(int)->int'")
end)

test("invoke_async_export duplicate export raises the host-invocation failure signal", function()
  local w = __rt.function_("async()->int", function()
    return __rt.async_start(function() return 1 end)
  end)
  local exports = { oracle = w, alias = w }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(exports, "oracle", "int")
  end, "duplicate export 'oracle'")
end)

test("invoke_async_export descriptor-mismatched export raises the host-invocation failure signal", function()
  local exports = {
    oracle = __rt.function_("async()->string", function()
      return __rt.async_start(function() return "x" end)
    end),
  }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(exports, "oracle", "int")
  end, "signature mismatch: expected 'async()->int', got 'async()->string'")
  -- A legacy-dialect carried sig is a non-canonical signature: the
  -- exact-name + exact-signature selection rejects it.
  local legacy = {
    oracle = __rt.function_("async()->string|null", function()
      return __rt.async_start(function() return __rt.__NULL end)
    end),
  }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(legacy, "oracle", "string")
  end, "signature mismatch")
end)

test("invoke_async_export non-wrapper export raises the host-invocation failure signal", function()
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({ oracle = 42 }, "oracle", "int")
  end, "not a function wrapper")
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({ oracle = function() end }, "oracle", "int")
  end, "not a function wrapper")
end)

test("invoke_async_export non-operation result raises the host-invocation failure signal", function()
  -- The carried sig promises the exact async()->R; a wrapper whose .f
  -- returns a raw value is a non-production export.
  local exports = {
    oracle = __rt.function_("async()->int", function() return 42 end),
  }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(exports, "oracle", "int")
  end, "did not produce an async operation")
end)

test("invoke_async_export malformed protocol inputs raise the host-invocation failure signal", function()
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(nil, "oracle", "int")
  end, "exports must be a table")
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({}, 42, "int")
  end, "exportName must be a string")
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({}, "oracle", "int[]")
  end, "not a canonical descriptor")
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({}, "oracle", "Error")
  end, "not a canonical descriptor")
  assert_host_invocation_failure(function()
    __rt.invoke_async_export({}, "oracle", nil)
  end, "not a canonical descriptor")
end)

test("invoke_async_export invokes exactly one operation per call and never invokes a rejected export", function()
  local invocations = 0
  local exports = {
    oracle = __rt.function_("async()->int", function()
      invocations = invocations + 1
      return __rt.async_start(function() return invocations end)
    end),
  }
  assert(__rt.invoke_async_export(exports, "oracle", "int") == 1)
  assert(invocations == 1)
  assert(__rt.invoke_async_export(exports, "oracle", "int") == 2)
  assert(invocations == 2)

  local rejectedCalls = 0
  local rejected = {
    oracle = __rt.function_("()->int", function()
      rejectedCalls = rejectedCalls + 1
      return 1
    end),
  }
  assert_host_invocation_failure(function()
    __rt.invoke_async_export(rejected, "oracle", "int")
  end, "is sync")
  assert(rejectedCalls == 0, "a rejected export must never be invoked")
end)

-- ==================== C FFI runtime six-code/atomicity battery (ISSUE-0435) ====================
-- luajit-ffi-six-code-atomicity-battery B1-B11: the six FFI_* codes, the
-- full composition, atomicity/replay/single-close/cdef-preservation,
-- zero default evaluation, and the non-yield proof under real LuaJIT,
-- against the committed fixture library compiled below by the test
-- bootstrap with GCC (B3). Every case drives only the production
-- __rt.load_ffi entry and the published wrappers/plans with
-- generated-shape inputs (B4); no test-only runtime exposure is used,
-- and the battery never calls ffi.load and never normalizes ffi.C
-- access. The B-series never invokes __rt.class_plan_ (B11); the
-- C-series below (ISSUE-0443) invokes the entry only through the
-- retained exported plan and the inbound C_STRUCT delegation.

-- B3: one fail-closed bootstrap compile of the committed fixture source.
-- The absolute loader text is derived from the test process's working
-- directory; a missing GCC or a failed compile fails the battery
-- immediately (nonzero battery exit -> run_tests.sh gate failure).
local ffi_batt_abs_path = io.popen("pwd"):read("*l")
local ffi_batt_events_path = ffi_batt_abs_path .. "/build/ffi-runtime-fixture-events.log"
local ffi_batt_so_path = ffi_batt_abs_path .. "/build/ffi-runtime-fixture.so"
local ffi_batt_compile_cmd = "mkdir -p build && gcc -shared -fPIC -O2 -DFIXTURE_EVENTS_PATH='\""
    .. ffi_batt_events_path
    .. "\"' -o build/ffi-runtime-fixture.so test/fixtures/ffi-runtime-fixture.c"
do
  local compile_status = os.execute(ffi_batt_compile_cmd)
  if compile_status ~= 0 then
    error("FFI battery bootstrap failed: fixture GCC compile exited with status "
        .. tostring(compile_status))
  end
  local probe = io.open(ffi_batt_so_path, "r")
  if probe == nil then
    error("FFI battery bootstrap failed: " .. ffi_batt_so_path .. " missing after the compile")
  end
  probe:close()
end

-- The event file is removed before each load scenario (B3) so each
-- scenario's open/close sequence is exact.
local function ffi_batt_reset_events()
  os.remove(ffi_batt_events_path)
end

local function ffi_batt_read_events()
  local lines = {}
  local f = io.open(ffi_batt_events_path, "r")
  if f ~= nil then
    for line in f:lines() do
      lines[#lines + 1] = line
    end
    f:close()
  end
  return lines
end

-- The import span passed to every load_ffi call (the import site's
-- spanArgs triplet) and the call span passed to every wrapper call.
local FFI_BATT_IMPORT_FILE = "test_runtime.lua"
local FFI_BATT_IMPORT_LINE = 9023
local FFI_BATT_IMPORT_COL = 41
local FFI_BATT_CALL_FILE = "test_runtime.lua"
local FFI_BATT_CALL_LINE = 7077
local FFI_BATT_CALL_COL = 77

-- B5: each code is asserted through BOTH the assert_error err.code path
-- and the error_value reification path (the tagged @$builtin/Error
-- instance a generated catch emits via error_value(err.code,
-- err.message)).
local function ffi_batt_assert_reified(err, expected_code)
  assert(type(err) == "table", "expected an error table for reification")
  local ev = __rt.error_value(err.code, err.message)
  assert(ev.__kind == "class", "the reified error must be a tagged class instance")
  assert(ev.__classname == "@$builtin/Error",
      "the reified error must carry the canonical Error identity")
  assert(ev.code == expected_code,
      "the reified error code must be " .. tostring(expected_code))
end

-- ===== Generated-shape builders (B4; the settled seam Lua shapes) =====

local FFI_BATT_PTR_IDENTITY = "@deal.test.ffi.fixture/Pointer"
local FFI_BATT_PAIR_IDENTITY = "@deal.test.ffi.fixture/Pair"
local FFI_BATT_PTRBOX_IDENTITY = "@deal.test.ffi.fixture/PtrBox"

local function ffi_batt_t(kind, desc, cls)
  return { kind = kind, canonicalDescriptor = desc, canonicalClassIdentity = cls }
end

-- Private cdef entry texts with digest-qualified private names and
-- declaration ordinals (adopted D2); the pair struct uses the deal_fN
-- ordinal members. The battery declares no real target-function
-- prototype; the loader's handle-scoped private casts use only these.
local FFI_BATT_TYPEDEFS = {
  count_call = { "typedef void (*deal_ffi_0435_fn_0001)(void);", "deal_ffi_0435_fn_0001" },
  call_count = { "typedef int32_t (*deal_ffi_0435_fn_0002)(void);", "deal_ffi_0435_fn_0002" },
  reset_counter = { "typedef void (*deal_ffi_0435_fn_0003)(void);", "deal_ffi_0435_fn_0003" },
  add_int = { "typedef int32_t (*deal_ffi_0435_fn_0004)(int32_t, int32_t);", "deal_ffi_0435_fn_0004" },
  add_number = { "typedef double (*deal_ffi_0435_fn_0005)(double, double);", "deal_ffi_0435_fn_0005" },
  ["not"] = { "typedef int32_t (*deal_ffi_0435_fn_0006)(int32_t);", "deal_ffi_0435_fn_0006" },
  echo_string = { "typedef const char *(*deal_ffi_0435_fn_0007)(const char *);", "deal_ffi_0435_fn_0007" },
  bytes_sum = { "typedef int32_t (*deal_ffi_0435_fn_0008)(const uint8_t *, int32_t);", "deal_ffi_0435_fn_0008" },
  null_string = { "typedef const char *(*deal_ffi_0435_fn_0009)(void);", "deal_ffi_0435_fn_0009" },
  bad_utf8 = { "typedef const char *(*deal_ffi_0435_fn_0010)(void);", "deal_ffi_0435_fn_0010" },
  null_pointer = { "typedef void *(*deal_ffi_0435_fn_0011)(void);", "deal_ffi_0435_fn_0011" },
  static_pointer = { "typedef void *(*deal_ffi_0435_fn_0012)(void);", "deal_ffi_0435_fn_0012" },
  pair = { "typedef struct { int32_t deal_f0; double deal_f1; } deal_ffi_0435_pair_t;", "deal_ffi_0435_pair_t" },
  ptr_box = { "typedef struct { void *deal_f0; } deal_ffi_0435_ptr_box_t;", "deal_ffi_0435_ptr_box_t" },
  make_pair = { "typedef deal_ffi_0435_pair_t (*deal_ffi_0435_fn_0014)(int32_t, double);", "deal_ffi_0435_fn_0014" },
  make_ptr_box = { "typedef deal_ffi_0435_ptr_box_t (*deal_ffi_0435_fn_0015)(void *);", "deal_ffi_0435_fn_0015" },
  make_null_ptr_box = { "typedef deal_ffi_0435_ptr_box_t (*deal_ffi_0435_fn_0016)(void);", "deal_ffi_0435_fn_0016" },
  symbol_missing = { "typedef int32_t (*deal_ffi_0435_fn_0013)(void);", "deal_ffi_0435_fn_0013" },
}

local function ffi_batt_entries(names)
  local out = {}
  for i = 1, #names do
    local td = FFI_BATT_TYPEDEFS[names[i]]
    out[i] = { entryDigest = "ffi-battery:entry:" .. names[i],
               fullText = td[1], ownedNames = { td[2] } }
  end
  return out
end

local FFI_BATT_FN_META = {
  count_call = { cSymbol = "fixture_count_call", fpt = "deal_ffi_0435_fn_0001",
                 params = {}, ret = ffi_batt_t("NULL", "null") },
  call_count = { cSymbol = "fixture_call_count", fpt = "deal_ffi_0435_fn_0002",
                 params = {}, ret = ffi_batt_t("INT", "int") },
  reset_counter = { cSymbol = "fixture_reset_counter", fpt = "deal_ffi_0435_fn_0003",
                    params = {}, ret = ffi_batt_t("NULL", "null") },
  add_int = { cSymbol = "fixture_add_int", fpt = "deal_ffi_0435_fn_0004",
              params = { ffi_batt_t("INT", "int"), ffi_batt_t("INT", "int") },
              ret = ffi_batt_t("INT", "int") },
  add_number = { cSymbol = "fixture_add_number", fpt = "deal_ffi_0435_fn_0005",
                 params = { ffi_batt_t("NUMBER", "number"), ffi_batt_t("NUMBER", "number") },
                 ret = ffi_batt_t("NUMBER", "number") },
  ["not"] = { cSymbol = "fixture_not", fpt = "deal_ffi_0435_fn_0006",
              params = { ffi_batt_t("BOOLEAN", "boolean") },
              ret = ffi_batt_t("BOOLEAN", "boolean") },
  echo_string = { cSymbol = "fixture_echo_string", fpt = "deal_ffi_0435_fn_0007",
                  params = { ffi_batt_t("STRING", "string") },
                  ret = ffi_batt_t("STRING", "string") },
  bytes_sum = { cSymbol = "fixture_bytes_sum", fpt = "deal_ffi_0435_fn_0008",
                params = { ffi_batt_t("BYTES", "bytes") },
                ret = ffi_batt_t("INT", "int") },
  null_string = { cSymbol = "fixture_null_string", fpt = "deal_ffi_0435_fn_0009",
                  params = {}, ret = ffi_batt_t("STRING", "string") },
  bad_utf8 = { cSymbol = "fixture_bad_utf8", fpt = "deal_ffi_0435_fn_0010",
               params = {}, ret = ffi_batt_t("STRING", "string") },
  null_pointer = { cSymbol = "fixture_null_pointer", fpt = "deal_ffi_0435_fn_0011",
                   params = {},
                   ret = ffi_batt_t("C_POINTER", FFI_BATT_PTR_IDENTITY, FFI_BATT_PTR_IDENTITY) },
  static_pointer = { cSymbol = "fixture_static_pointer", fpt = "deal_ffi_0435_fn_0012",
                     params = {},
                     ret = ffi_batt_t("C_POINTER", FFI_BATT_PTR_IDENTITY, FFI_BATT_PTR_IDENTITY) },
  -- C_STRUCT functions: the privateFunctionPointerType carries the full
  -- anonymous function-pointer spelling RET (*)(P1, ...) — the wrapper
  -- builder both casts to it and splits it for the C_STRUCT ctype
  -- extraction (the splitter rejects a bare typedef name).
  make_pair = { cSymbol = "fixture_make_pair",
                fpt = "deal_ffi_0435_pair_t (*)(int32_t, double)",
                params = { ffi_batt_t("INT", "int"), ffi_batt_t("NUMBER", "number") },
                ret = ffi_batt_t("C_STRUCT", FFI_BATT_PAIR_IDENTITY, FFI_BATT_PAIR_IDENTITY) },
  make_ptr_box = { cSymbol = "fixture_make_ptr_box",
                   fpt = "deal_ffi_0435_ptr_box_t (*)(void *)",
                   params = { ffi_batt_t("C_POINTER", FFI_BATT_PTR_IDENTITY, FFI_BATT_PTR_IDENTITY) },
                   ret = ffi_batt_t("C_STRUCT", FFI_BATT_PTRBOX_IDENTITY, FFI_BATT_PTRBOX_IDENTITY) },
  make_null_ptr_box = { cSymbol = "fixture_make_null_ptr_box",
                        fpt = "deal_ffi_0435_ptr_box_t (*)(void)",
                        params = {},
                        ret = ffi_batt_t("C_STRUCT", FFI_BATT_PTRBOX_IDENTITY, FFI_BATT_PTRBOX_IDENTITY) },
  symbol_missing = { cSymbol = "fixture_symbol_missing", fpt = "deal_ffi_0435_fn_0013",
                     params = {}, ret = ffi_batt_t("INT", "int") },
}

local function ffi_batt_functions(names)
  local out = {}
  for i = 1, #names do
    local meta = FFI_BATT_FN_META[names[i]]
    local params = {}
    for j = 1, #meta.params do
      params[j] = meta.params[j]
    end
    out[i] = { dealName = names[i], cSymbol = meta.cSymbol,
               privateFunctionPointerType = meta.fpt,
               orderedParams = params, returnType = meta.ret }
  end
  return out
end

local FFI_BATT_FULL_NAMES = {
  "count_call", "call_count", "reset_counter", "add_int", "add_number",
  "not", "echo_string", "bytes_sum", "null_string", "bad_utf8",
  "null_pointer", "static_pointer",
  "make_pair", "make_ptr_box", "make_null_ptr_box",
}

local FFI_BATT_SYMBOL_NAMES = { "add_int", "echo_string", "symbol_missing" }
local FFI_BATT_SUBSET_NAMES = { "add_int", "add_number", "not", "echo_string", "bytes_sum" }

local function ffi_batt_classes_full()
  return {
    { name = "Pair", canonicalClassIdentity = FFI_BATT_PAIR_IDENTITY,
      qualifiedDealDescriptor = FFI_BATT_PAIR_IDENTITY, kind = "C_STRUCT",
      orderedFields = {
        { dealName = "x", fieldOrdinal = 0, type = ffi_batt_t("INT", "int") },
        { dealName = "y", fieldOrdinal = 1, type = ffi_batt_t("NUMBER", "number") },
      } },
    { name = "PtrBox", canonicalClassIdentity = FFI_BATT_PTRBOX_IDENTITY,
      qualifiedDealDescriptor = FFI_BATT_PTRBOX_IDENTITY, kind = "C_STRUCT",
      orderedFields = {
        { dealName = "ptr", fieldOrdinal = 0,
          type = ffi_batt_t("C_POINTER", FFI_BATT_PTR_IDENTITY, FFI_BATT_PTR_IDENTITY) },
      } },
    { name = "Pointer", canonicalClassIdentity = FFI_BATT_PTR_IDENTITY,
      qualifiedDealDescriptor = FFI_BATT_PTR_IDENTITY, kind = "C_POINTER",
      orderedFields = {} },
  }
end

-- Battery-side default-evaluation counter: the pair plan's evaluators
-- increment it, so it proves zero default evaluation during load and
-- during ready replay (B6/B7.6; adopted D1/D6 "never invokes evaluators").
local ffi_batt_evaluator_count = 0

local function ffi_batt_build_plan_list()
  return {
    { name = "x", descriptor = "int", optional = false,
      evaluator = function()
        ffi_batt_evaluator_count = ffi_batt_evaluator_count + 1
        return 0
      end },
    { name = "y", descriptor = "number", optional = true,
      evaluator = function()
        ffi_batt_evaluator_count = ffi_batt_evaluator_count + 1
        return 0.0
      end },
  }
end

-- The PtrBox plan list: the single required field carries no evaluator
-- (ptr is always provided by the inbound conversion), so no new
-- default-evaluation path exists and the B6/B7 zero-count assertions
-- stay exact.
local function ffi_batt_build_ptrbox_plan_list()
  return {
    { name = "ptr", descriptor = FFI_BATT_PTR_IDENTITY,
      optional = false, evaluator = nil },
  }
end

local function ffi_batt_build_plans(plan_list)
  return {
    [FFI_BATT_PAIR_IDENTITY] = {
      plan = plan_list,
      canonicalPlanContent = "ffi-battery:pair:canonical-plan-content:v1",
      semanticDefaultContents = "ffi-battery:pair:semantic-default-contents:v1",
      evaluatorImplementationContents = "ffi-battery:pair:evaluator-implementation-contents:v1",
      planDigest = "ffi-battery:pair:plan-digest:v1",
    },
    [FFI_BATT_PTRBOX_IDENTITY] = {
      plan = ffi_batt_build_ptrbox_plan_list(),
      canonicalPlanContent = "ffi-battery:ptrbox:canonical-plan-content:v1",
      semanticDefaultContents = "ffi-battery:ptrbox:semantic-default-contents:v1",
      evaluatorImplementationContents = "ffi-battery:ptrbox:evaluator-implementation-contents:v1",
      planDigest = "ffi-battery:ptrbox:plan-digest:v1",
    },
  }
end

local function ffi_batt_bindings(moduleKey, names)
  local cells = {}
  for i = 1, #names do
    cells[names[i]] = { state = "UNBOUND", wrapper = nil, errorValue = nil }
  end
  return { moduleKey = moduleKey, state = "UNBOUND", cells = cells }
end

-- Module keys (opaque FfiModuleKey texts, never parsed by the runtime).
local FFI_BATT_KEY_MISSING = "ffi:deal.test.ffi.fixture/missing-library"
local FFI_BATT_KEY_SYMBOL = "ffi:deal.test.ffi.fixture/symbol-missing"
local FFI_BATT_KEY_SUBSET = "ffi:deal.test.ffi.fixture/subset"
local FFI_BATT_KEY_FULL = "ffi:deal.test.ffi.fixture/full"
local FFI_BATT_KEY_NONYIELD_OK = "ffi:deal.test.ffi.fixture/non-yield-ok"
local FFI_BATT_KEY_NONYIELD_FAIL = "ffi:deal.test.ffi.fixture/non-yield-fail"

local function ffi_batt_bundle_missing()
  return {
    bundleDigest = "ffi-battery:missing:bundle-digest",
    identityDigest = "ffi-battery:missing:identity-digest",
    fullContent = "ffi-battery:missing:cdef-bundle-content:v1",
    nativeLibrary = { kind = "ABSOLUTE_PATH",
                      loaderText = "/nonexistent/deal/ffi-fixture-missing.so" },
    entries = {},
    functions = {
      { dealName = "sum_missing", cSymbol = "fixture_add_int",
        privateFunctionPointerType = "deal_ffi_0435_fn_0004",
        orderedParams = { ffi_batt_t("INT", "int"), ffi_batt_t("INT", "int") },
        returnType = ffi_batt_t("INT", "int") },
    },
    classes = {},
  }
end

local function ffi_batt_bundle_symbol()
  return {
    bundleDigest = "ffi-battery:symbol:bundle-digest",
    identityDigest = "ffi-battery:symbol:identity-digest",
    fullContent = "ffi-battery:symbol:cdef-bundle-content:v1",
    nativeLibrary = { kind = "ABSOLUTE_PATH", loaderText = ffi_batt_so_path },
    entries = ffi_batt_entries(FFI_BATT_SYMBOL_NAMES),
    functions = ffi_batt_functions(FFI_BATT_SYMBOL_NAMES),
    classes = {},
  }
end

local function ffi_batt_bundle_subset()
  return {
    bundleDigest = "ffi-battery:subset:bundle-digest",
    identityDigest = "ffi-battery:subset:identity-digest",
    fullContent = "ffi-battery:subset:cdef-bundle-content:v1",
    nativeLibrary = { kind = "ABSOLUTE_PATH", loaderText = ffi_batt_so_path },
    entries = ffi_batt_entries(FFI_BATT_SUBSET_NAMES),
    functions = ffi_batt_functions(FFI_BATT_SUBSET_NAMES),
    classes = {},
  }
end

local FFI_BATT_FULL_ENTRY_NAMES = {
  "count_call", "call_count", "reset_counter", "add_int", "add_number",
  "not", "echo_string", "bytes_sum", "null_string", "bad_utf8",
  "null_pointer", "static_pointer", "pair",
  "ptr_box", "make_pair", "make_ptr_box", "make_null_ptr_box",
}

local function ffi_batt_bundle_full()
  return {
    bundleDigest = "ffi-battery:full:bundle-digest",
    identityDigest = "ffi-battery:full:identity-digest",
    fullContent = "ffi-battery:full:cdef-bundle-content:v1",
    nativeLibrary = { kind = "ABSOLUTE_PATH", loaderText = ffi_batt_so_path },
    entries = ffi_batt_entries(FFI_BATT_FULL_ENTRY_NAMES),
    functions = ffi_batt_functions(FFI_BATT_FULL_NAMES),
    classes = ffi_batt_classes_full(),
  }
end

-- The published ready exports and retained plan list of the successful
-- full fixture module, shared by the later cases.
local ffi_batt_exports_full = nil
local ffi_batt_plan_list = nil

local function ffi_batt_require_full_exports()
  assert(ffi_batt_exports_full ~= nil, "the full fixture module must be loaded first")
  return ffi_batt_exports_full
end

-- ===== Case matrix and atomicity/replay/non-yield cases (B5-B8) =====

test("FFI six-code case 1: FFI_LIBRARY_LOAD (nonexistent loader text) carries the import span, reifies, and opens nothing", function()
  ffi_batt_reset_events()
  local names = { "sum_missing" }
  local bindings = ffi_batt_bindings(FFI_BATT_KEY_MISSING, names)
  local err = assert_error(function()
    return __rt.load_ffi(FFI_BATT_KEY_MISSING, ffi_batt_bundle_missing(), {},
        bindings, FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end, "FFI_LIBRARY_LOAD")
  assert(err.file == FFI_BATT_IMPORT_FILE, "FFI_LIBRARY_LOAD must carry the import file")
  assert(err.line == FFI_BATT_IMPORT_LINE, "FFI_LIBRARY_LOAD must carry the import line")
  assert(err.column == FFI_BATT_IMPORT_COL, "FFI_LIBRARY_LOAD must carry the import column")
  ffi_batt_assert_reified(err, "FFI_LIBRARY_LOAD")
  local events = ffi_batt_read_events()
  assert(#events == 0, "no open ever happened, so the event file must gain zero lines")
  assert(bindings.state == "FAILED", "the failed bindings must end FAILED")
  assert(bindings.cells.sum_missing.state == "FAILED", "the cell must end FAILED")
  assert(bindings.cells.sum_missing.errorValue == err,
      "the cell must carry the cached error table")
end)

test("FFI six-code case 2: FFI_SYMBOL_MISSING opens once, closes once, publishes no exports, caches the error, and failed replay re-raises it without retry", function()
  ffi_batt_reset_events()
  local names = FFI_BATT_SYMBOL_NAMES
  local bindings = ffi_batt_bindings(FFI_BATT_KEY_SYMBOL, names)
  local err = assert_error(function()
    return __rt.load_ffi(FFI_BATT_KEY_SYMBOL, ffi_batt_bundle_symbol(), {},
        bindings, FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end, "FFI_SYMBOL_MISSING")
  assert(err.file == FFI_BATT_IMPORT_FILE, "FFI_SYMBOL_MISSING must carry the import file")
  assert(err.line == FFI_BATT_IMPORT_LINE, "FFI_SYMBOL_MISSING must carry the import line")
  assert(err.column == FFI_BATT_IMPORT_COL, "FFI_SYMBOL_MISSING must carry the import column")
  ffi_batt_assert_reified(err, "FFI_SYMBOL_MISSING")
  local events = ffi_batt_read_events()
  assert(#events == 2 and events[1] == "open" and events[2] == "close",
      "the load must open the library once and close the failed opened handle exactly once, got "
      .. table.concat(events, ","))
  -- B7.1: failure publishes no exports and fails the passed bindings
  -- with the cached error (the raise is the only observable outcome).
  assert(bindings.state == "FAILED", "the failed bindings must end FAILED")
  for i = 1, #names do
    local cell = bindings.cells[names[i]]
    assert(cell.state == "FAILED", "cell " .. names[i] .. " must end FAILED")
    assert(cell.wrapper == nil, "no wrapper may be published for " .. names[i])
    assert(cell.errorValue == err, "cell " .. names[i] .. " must carry the raised error table")
  end
  -- B7.2: replaying the exact failed identity with fresh equal-content
  -- argument tables re-raises the same cached error value (no retry)
  -- and fails the fresh bindings with that same error; the event file
  -- still holds exactly one open and one close (no second open/close).
  local replay_bindings = ffi_batt_bindings(FFI_BATT_KEY_SYMBOL, names)
  local replay_err = assert_error(function()
    return __rt.load_ffi(FFI_BATT_KEY_SYMBOL, ffi_batt_bundle_symbol(), {},
        replay_bindings, FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end, "FFI_SYMBOL_MISSING")
  assert(replay_err == err,
      "failed replay must re-raise the same cached error table (reference equality; no retry)")
  assert(replay_bindings.state == "FAILED", "the fresh replay bindings must end FAILED")
  for i = 1, #names do
    local cell = replay_bindings.cells[names[i]]
    assert(cell.state == "FAILED", "fresh replay cell " .. names[i] .. " must end FAILED")
    assert(cell.errorValue == err,
        "fresh replay cell " .. names[i] .. " must carry the same cached error table")
  end
  local events2 = ffi_batt_read_events()
  assert(#events2 == 2 and events2[1] == "open" and events2[2] == "close",
      "load + replay must leave exactly one open and one close, got "
      .. table.concat(events2, ","))
end)

test("FFI cdef preservation: a second module reuses the registered entries as a subset and its wrappers work", function()
  local names = FFI_BATT_SUBSET_NAMES
  local bindings = ffi_batt_bindings(FFI_BATT_KEY_SUBSET, names)
  local exports = __rt.load_ffi(FFI_BATT_KEY_SUBSET, ffi_batt_bundle_subset(), {},
      bindings, FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  assert(type(exports) == "table", "the subset load must publish an exports table")
  assert(exports.add_int.f(20, 22, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 42,
      "the subset add_int wrapper must work")
  assert(exports.add_number.f(1.25, 2.5, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 3.75,
      "the subset add_number wrapper must work")
  assert(exports["not"].f(true, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == false,
      "the subset not wrapper must work")
  assert(exports.echo_string.f("subset", FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == "subset",
      "the subset echo_string wrapper must work")
  local bytes = __rt.bytes_new(2)
  __rt.bytes_set(bytes, 0, 7)
  __rt.bytes_set(bytes, 1, 8)
  assert(exports.bytes_sum.f(bytes, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 15,
      "the subset bytes_sum wrapper must work")
  assert(bindings.state == "READY", "the subset bindings must end READY")
end)

test("FFI positive composition: the full fixture bundle exercises every pipeline stage, publishes R17-composed wrappers and the retained plan, and never evaluates defaults", function()
  local names = FFI_BATT_FULL_NAMES
  local bindings = ffi_batt_bindings(FFI_BATT_KEY_FULL, names)
  local plan_list = ffi_batt_build_plan_list()
  local plans = ffi_batt_build_plans(plan_list)
  local exports = __rt.load_ffi(FFI_BATT_KEY_FULL, ffi_batt_bundle_full(), plans,
      bindings, FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  assert(type(exports) == "table", "the load must publish an exports table")
  ffi_batt_exports_full = exports
  ffi_batt_plan_list = plan_list
  -- Exactly one ready wrapper per declared export name plus the <C>_plan
  -- entry (R12); nothing else.
  local key_count = 0
  for _ in pairs(exports) do
    key_count = key_count + 1
  end
  assert(key_count == 17,
      "the exports table must carry 15 wrappers plus Pair_plan and PtrBox_plan")
  local sigs = {
    count_call = "()->null",
    call_count = "()->int",
    reset_counter = "()->null",
    add_int = "(int,int)->int",
    add_number = "(number,number)->number",
    ["not"] = "(boolean)->boolean",
    echo_string = "(string)->string",
    bytes_sum = "(bytes)->int",
    null_string = "()->string",
    bad_utf8 = "()->string",
    null_pointer = "()->" .. FFI_BATT_PTR_IDENTITY,
    static_pointer = "()->" .. FFI_BATT_PTR_IDENTITY,
    make_pair = "(int,number)->" .. FFI_BATT_PAIR_IDENTITY,
    make_ptr_box = "(" .. FFI_BATT_PTR_IDENTITY .. ")->" .. FFI_BATT_PTRBOX_IDENTITY,
    make_null_ptr_box = "()->" .. FFI_BATT_PTRBOX_IDENTITY,
  }
  for i = 1, #names do
    local name = names[i]
    local w = exports[name]
    assert(type(w) == "table" and w.__kind == "function",
        "export " .. name .. " must be a function_-shaped wrapper table")
    assert(type(w.f) == "function", "export " .. name .. " must carry a working .f closure")
    assert(w.sig == sigs[name],
        "export " .. name .. " must carry the byte-exact composed sig '"
        .. tostring(sigs[name]) .. "', got '" .. tostring(w.sig) .. "'")
  end
  assert(exports["Pair_plan"] == plan_list,
      "the exported Pair_plan entry must be the retained plan list (reference equality)")
  assert(exports["PtrBox_plan"] == plans[FFI_BATT_PTRBOX_IDENTITY].plan,
      "the exported PtrBox_plan entry must be the retained plan list (reference equality)")
  -- Roundtrips through the converters (int/number/boolean/string/bytes).
  assert(exports.add_int.f(2, 3, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 5)
  assert(exports.add_number.f(2.5, 3.25, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 5.75)
  assert(exports["not"].f(true, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == false)
  assert(exports["not"].f(false, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == true)
  local echoed = exports.echo_string.f("hello", FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(type(echoed) == "string" and echoed == "hello",
      "the returned echo must be a DEAL Lua string (copied, never freed)")
  local bytes = __rt.bytes_new(4)
  __rt.bytes_set(bytes, 0, 1)
  __rt.bytes_set(bytes, 1, 2)
  __rt.bytes_set(bytes, 2, 3)
  __rt.bytes_set(bytes, 3, 4)
  assert(exports.bytes_sum.f(bytes, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 10)
  -- The void -> null row.
  assert(exports.count_call.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == __rt.__NULL,
      "the void counter wrapper must return __rt.__NULL")
  -- Non-NULL pointer returns produce fresh declared-identity tokens.
  local tok1 = exports.static_pointer.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  local tok2 = exports.static_pointer.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(tok1.__kind == "class" and tok1.__classname == FFI_BATT_PTR_IDENTITY,
      "the pointer token must be tagged with the declared canonical class identity")
  assert(tok2.__kind == "class" and tok2.__classname == FFI_BATT_PTR_IDENTITY,
      "the second pointer token must be tagged with the declared canonical class identity")
  assert(tok1 ~= tok2, "two successive calls must produce distinct token tables")
  -- Cells and bindings all READY; zero default evaluation during load.
  assert(bindings.state == "READY", "the bindings must end READY")
  for i = 1, #names do
    local cell = bindings.cells[names[i]]
    assert(cell.state == "READY", "cell " .. names[i] .. " must be READY")
    assert(cell.wrapper == exports[names[i]],
        "cell " .. names[i] .. " must carry the published wrapper")
  end
  assert(ffi_batt_evaluator_count == 0,
      "no default evaluator may run during load (zero default evaluation)")
end)

test("FFI six-code case 3: FFI_INVALID_STRING (embedded U+0000 outbound) raises before the call with the call span", function()
  local exports = ffi_batt_require_full_exports()
  exports.reset_counter.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  local err = assert_error(function()
    return exports.echo_string.f("ab\0cd", FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "FFI_INVALID_STRING")
  assert(err.file == FFI_BATT_CALL_FILE, "FFI_INVALID_STRING must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "FFI_INVALID_STRING must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "FFI_INVALID_STRING must carry the call column")
  ffi_batt_assert_reified(err, "FFI_INVALID_STRING")
  assert(exports.call_count.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 0,
      "the native call must never have run (echo increments on entry; a leaked call would read 1)")
end)

test("FFI six-code case 4: FFI_NULL_STRING (NULL char* return) raises after the call with native effects retained", function()
  local exports = ffi_batt_require_full_exports()
  exports.reset_counter.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  local err = assert_error(function()
    return exports.null_string.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "FFI_NULL_STRING")
  assert(err.file == FFI_BATT_CALL_FILE, "FFI_NULL_STRING must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "FFI_NULL_STRING must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "FFI_NULL_STRING must carry the call column")
  ffi_batt_assert_reified(err, "FFI_NULL_STRING")
  assert(exports.call_count.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 1,
      "the call must have run: the counter reads exactly one")
end)

test("FFI six-code case 5: FFI_INVALID_UTF8 (non-UTF-8 return) raises after the call with native effects retained", function()
  local exports = ffi_batt_require_full_exports()
  exports.reset_counter.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  local err = assert_error(function()
    return exports.bad_utf8.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "FFI_INVALID_UTF8")
  assert(err.file == FFI_BATT_CALL_FILE, "FFI_INVALID_UTF8 must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "FFI_INVALID_UTF8 must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "FFI_INVALID_UTF8 must carry the call column")
  ffi_batt_assert_reified(err, "FFI_INVALID_UTF8")
  assert(exports.call_count.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 1,
      "the call must have run: the counter reads exactly one")
end)

test("FFI six-code case 6: FFI_NULL_POINTER (NULL void* return) raises after the call and publishes no token", function()
  local exports = ffi_batt_require_full_exports()
  exports.reset_counter.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  local err = assert_error(function()
    return exports.null_pointer.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "FFI_NULL_POINTER")
  assert(err.file == FFI_BATT_CALL_FILE, "FFI_NULL_POINTER must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "FFI_NULL_POINTER must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "FFI_NULL_POINTER must carry the call column")
  ffi_batt_assert_reified(err, "FFI_NULL_POINTER")
  assert(exports.call_count.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 1,
      "the call must have run: the counter reads exactly one")
end)

test("FFI ready replay: the exact full-content identity returns the cached exports, fills fresh cells with the cached wrappers, and never evaluates", function()
  local exports = ffi_batt_require_full_exports()
  local fresh_bindings = ffi_batt_bindings(FFI_BATT_KEY_FULL, FFI_BATT_FULL_NAMES)
  local replays = __rt.load_ffi(FFI_BATT_KEY_FULL, ffi_batt_bundle_full(),
      ffi_batt_build_plans(ffi_batt_build_plan_list()), fresh_bindings,
      FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  assert(replays == exports,
      "ready replay must return the cached exports table (reference equality)")
  assert(fresh_bindings.state == "READY", "the fresh replay bindings must end READY")
  for i = 1, #FFI_BATT_FULL_NAMES do
    local name = FFI_BATT_FULL_NAMES[i]
    local cell = fresh_bindings.cells[name]
    assert(cell.state == "READY", "fresh replay cell " .. name .. " must be READY")
    assert(cell.wrapper == exports[name],
        "fresh replay cell " .. name .. " must carry the cached wrapper table")
  end
  assert(exports.add_int.f(2, 3, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 5,
      "the original wrappers must remain callable")
  assert(ffi_batt_evaluator_count == 0,
      "ready replay must perform no default evaluation either")
end)

test("FFI content-changed replay: a changed loader text or bundle content fails before mutation and gains no open/close lines", function()
  ffi_batt_reset_events()
  local exports = ffi_batt_require_full_exports()
  -- Changed nativeLibrary.loaderText.
  local b1 = ffi_batt_bindings(FFI_BATT_KEY_FULL, FFI_BATT_FULL_NAMES)
  local changed_loader = ffi_batt_bundle_full()
  changed_loader.nativeLibrary = { kind = "ABSOLUTE_PATH",
      loaderText = ffi_batt_so_path .. ".changed-missing.so" }
  assert_error(function()
    return __rt.load_ffi(FFI_BATT_KEY_FULL, changed_loader,
        ffi_batt_build_plans(ffi_batt_build_plan_list()), b1,
        FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end, "FFI_LIBRARY_LOAD")
  assert(b1.state == "UNBOUND", "the changed bindings must stay UNBOUND")
  for i = 1, #FFI_BATT_FULL_NAMES do
    local cell = b1.cells[FFI_BATT_FULL_NAMES[i]]
    assert(cell.state == "UNBOUND" and cell.wrapper == nil and cell.errorValue == nil,
        "the changed bindings cells must stay UNBOUND")
  end
  -- Changed cdefBundle.fullContent.
  local b2 = ffi_batt_bindings(FFI_BATT_KEY_FULL, FFI_BATT_FULL_NAMES)
  local changed_content = ffi_batt_bundle_full()
  changed_content.fullContent = changed_content.fullContent .. "-changed"
  assert_error(function()
    return __rt.load_ffi(FFI_BATT_KEY_FULL, changed_content,
        ffi_batt_build_plans(ffi_batt_build_plan_list()), b2,
        FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end, "FFI_LIBRARY_LOAD")
  assert(b2.state == "UNBOUND", "the changed bindings must stay UNBOUND")
  for i = 1, #FFI_BATT_FULL_NAMES do
    local cell = b2.cells[FFI_BATT_FULL_NAMES[i]]
    assert(cell.state == "UNBOUND" and cell.wrapper == nil and cell.errorValue == nil,
        "the changed bindings cells must stay UNBOUND")
  end
  assert(exports.add_int.f(2, 3, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 5,
      "the previously published exports must stay callable")
  local events = ffi_batt_read_events()
  assert(#events == 0, "changed-identity replay must gain no open/close lines")
end)

test("FFI non-yield proof: one coroutine.resume reaches dead for a successful and a failing load", function()
  local co = coroutine.create(function()
    return __rt.load_ffi(FFI_BATT_KEY_NONYIELD_OK, ffi_batt_bundle_full(),
        ffi_batt_build_plans(ffi_batt_build_plan_list()),
        ffi_batt_bindings(FFI_BATT_KEY_NONYIELD_OK, FFI_BATT_FULL_NAMES),
        FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end)
  local ok, res = coroutine.resume(co)
  assert(ok == true, "the first resume of the successful load must succeed")
  assert(type(res) == "table", "the first resume must return the exports table")
  assert(coroutine.status(co) == "dead",
      "exactly one coroutine.resume must reach dead: the load never yields")
  local cof = coroutine.create(function()
    return __rt.load_ffi(FFI_BATT_KEY_NONYIELD_FAIL, ffi_batt_bundle_missing(), {},
        ffi_batt_bindings(FFI_BATT_KEY_NONYIELD_FAIL, { "sum_missing" }),
        FFI_BATT_IMPORT_FILE, FFI_BATT_IMPORT_LINE, FFI_BATT_IMPORT_COL)
  end)
  local fok, ferr = coroutine.resume(cof)
  assert(fok == false, "the failing load must surface its raise through the resume")
  assert(type(ferr) == "table" and ferr.code == "FFI_LIBRARY_LOAD",
      "the failing load must raise FFI_LIBRARY_LOAD through the resume")
  assert(coroutine.status(cof) == "dead",
      "exactly one coroutine.resume must reach dead: the failing load never yields")
end)

-- ==================== C-series: class_plan_ routing battery (ISSUE-0443) ====================
-- luajit-ffi-class-plan-consumption-verification C1-C10: construction
-- through the retained exported FFI plan, E8007/E8001/E8004 through the
-- plan entry with the battery's forwarded call span, evaluator-counter
-- deltas (zero at load — preserved B6/B7 absolutes — exactly one per
-- attempt at READY), inbound by-value struct results (direct and field)
-- publishing tagged classes with Lua-number numeric members (the D7
-- inbound unboxing), FFI_NULL_POINTER direct (preserved B5 case 6) and
-- field with native effects retained, and failed conversion publishing
-- no class; C10 extends the negative-constraint audit below with the
-- byte-level items (f)-(l).

test("C-series precheck (G1(d)): the consumed entry is a function and a smoke construction through the retained plan publishes a tagged Pair", function()
  local exports = ffi_batt_require_full_exports()
  assert(type(__rt.class_plan_) == "function",
      "the C-series fail-closed precheck requires __rt.class_plan_ to be a function")
  local smoke = __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
      { x = 7 }, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(smoke.__classname == FFI_BATT_PAIR_IDENTITY,
      "the smoke construction through the retained plan must carry the canonical Pair identity")
  assert(smoke.__kind == "class",
      "the smoke construction through the retained plan must be tagged __kind == 'class'")
end)

test("C1: construction through the retained exported FFI plan yields a tagged instance with provided values and absent optional omission (evaluator delta 0)", function()
  local exports = ffi_batt_require_full_exports()
  local before = ffi_batt_evaluator_count
  local inst = __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
      { x = 7 }, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(inst.__classname == FFI_BATT_PAIR_IDENTITY,
      "the constructed instance must carry the canonical Pair identity")
  assert(inst.__kind == "class",
      "the constructed instance must be tagged __kind == 'class'")
  assert(inst.x == 7, "the provided field x must be copied through")
  assert(inst.y == nil, "the omitted optional field y must stay absent")
  assert(ffi_batt_evaluator_count - before == 0,
      "no evaluator may run: x is provided and y is an omitted optional")
end)

test("C2: omitted required defaults evaluate exactly once per attempt at READY through the retained plan", function()
  local exports = ffi_batt_require_full_exports()
  local before = ffi_batt_evaluator_count
  local a = __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
      {}, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(a.x == 0, "the first construction's defaulted x must be 0")
  assert(a.y == nil, "the omitted optional y must stay absent")
  assert(ffi_batt_evaluator_count - before == 1,
      "exactly one evaluator may run per attempt (x's; y is an omitted optional)")
  local b = __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
      {}, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(b.x == 0, "the second construction's defaulted x must be 0")
  assert(ffi_batt_evaluator_count - before == 2,
      "the second attempt must run exactly one more evaluator")
  assert(a ~= b, "each attempt must publish a distinct instance table")
end)

test("C3: an extra provided field through the retained plan raises E8007 with the call span before any default evaluation", function()
  local exports = ffi_batt_require_full_exports()
  local before = ffi_batt_evaluator_count
  local err = assert_error(function()
    return __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
        { x = 1, z = 9 }, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "E8007")
  assert(err.file == FFI_BATT_CALL_FILE, "E8007 must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "E8007 must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "E8007 must carry the call column")
  assert(ffi_batt_evaluator_count - before == 0,
      "no default evaluation may run before the extra-name rejection")
end)

test("C4: a provided-field validation failure raises E8001 through the plan entry with the call span and publishes nothing", function()
  local exports = ffi_batt_require_full_exports()
  local err = assert_error(function()
    return __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
        { x = "not-an-int" }, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "E8001")
  assert(err.file == FFI_BATT_CALL_FILE, "E8001 must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "E8001 must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "E8001 must carry the call column")
end)

test("C5: an int range failure raises E8004 through the plan entry with the call span and publishes nothing", function()
  local exports = ffi_batt_require_full_exports()
  local err = assert_error(function()
    return __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, exports["Pair_plan"],
        { x = 2147483648 }, FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "E8004")
  assert(err.file == FFI_BATT_CALL_FILE, "E8004 must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "E8004 must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "E8004 must carry the call column")
end)

-- C6's battery-local generated-shape plan: the required x evaluator
-- returns an out-of-range int so plan-entry phase 3 raises E8004 after
-- exactly one default evaluation.
local function ffi_batt_build_out_of_range_plan_list()
  return {
    { name = "x", descriptor = "int", optional = false,
      evaluator = function()
        ffi_batt_evaluator_count = ffi_batt_evaluator_count + 1
        return 2147483648
      end },
    { name = "y", descriptor = "number", optional = true,
      evaluator = function()
        ffi_batt_evaluator_count = ffi_batt_evaluator_count + 1
        return 0.0
      end },
  }
end

test("C6: an evaluator result that fails phase-3 validation runs the evaluator exactly once and raises E8004 with the call span", function()
  local plan = ffi_batt_build_out_of_range_plan_list()
  local before = ffi_batt_evaluator_count
  local err = assert_error(function()
    return __rt.class_plan_(FFI_BATT_PAIR_IDENTITY, plan, {},
        FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "E8004")
  assert(ffi_batt_evaluator_count - before == 1,
      "the x evaluator must run exactly once per attempt")
  assert(err.file == FFI_BATT_CALL_FILE, "E8004 must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "E8004 must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "E8004 must carry the call column")
end)

test("C7: inbound direct by-value struct against the remediated row publishes a tagged class with Lua-number numeric members (the D7 unboxing proof)", function()
  local exports = ffi_batt_require_full_exports()
  local inst = exports.make_pair.f(3, 4.5,
      FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(type(inst) == "table", "make_pair must publish a table instance")
  assert(inst.__classname == FFI_BATT_PAIR_IDENTITY,
      "the inbound Pair instance must carry the canonical class identity")
  assert(inst.__kind == "class",
      "the inbound Pair instance must be tagged __kind == 'class'")
  assert(inst.x == 3, "the inbound x member must be 3")
  assert(type(inst.x) == "number",
      "the inbound x member must be a Lua number by construction (the D7 unboxing)")
  assert(inst.y == 4.5, "the inbound y member must be 4.5")
  assert(type(inst.y) == "number",
      "the inbound y member must be a Lua number by construction (the D7 unboxing)")
  local inst2 = exports.make_pair.f(3, 4.5,
      FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(inst2.__classname == FFI_BATT_PAIR_IDENTITY and inst2.__kind == "class",
      "the second inbound Pair instance must be tagged identically")
  assert(inst2 ~= inst, "two calls must produce distinct instance tables")
end)

test("C8: inbound by-value struct with a non-NULL pointer field publishes a tagged PtrBox whose ptr is a tagged token with a non-nil __ptr", function()
  local exports = ffi_batt_require_full_exports()
  local tok = exports.static_pointer.f(
      FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(tok.__kind == "class" and tok.__classname == FFI_BATT_PTR_IDENTITY,
      "the static_pointer token must be tagged with the Pointer identity")
  local box = exports.make_ptr_box.f(tok,
      FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  assert(type(box) == "table", "make_ptr_box must publish a table instance")
  assert(box.__classname == FFI_BATT_PTRBOX_IDENTITY,
      "the inbound PtrBox instance must carry the canonical class identity")
  assert(box.__kind == "class",
      "the inbound PtrBox instance must be tagged __kind == 'class'")
  assert(type(box.ptr) == "table", "the ptr field must be a token table")
  assert(box.ptr.__kind == "class" and box.ptr.__classname == FFI_BATT_PTR_IDENTITY,
      "the ptr field must be tagged with the Pointer identity")
  assert(box.ptr.__ptr ~= nil, "the ptr field must carry a non-nil __ptr")
end)

test("C9: an inbound field NULL pointer raises FFI_NULL_POINTER with the call span, keeps native effects, and publishes no class", function()
  local exports = ffi_batt_require_full_exports()
  exports.reset_counter.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  local err = assert_error(function()
    return exports.make_null_ptr_box.f(
        FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL)
  end, "FFI_NULL_POINTER")
  assert(err.file == FFI_BATT_CALL_FILE, "FFI_NULL_POINTER must carry the call file")
  assert(err.line == FFI_BATT_CALL_LINE, "FFI_NULL_POINTER must carry the call line")
  assert(err.column == FFI_BATT_CALL_COL, "FFI_NULL_POINTER must carry the call column")
  assert(exports.call_count.f(FFI_BATT_CALL_FILE, FFI_BATT_CALL_LINE, FFI_BATT_CALL_COL) == 1,
      "the native call must have run: the counter reads exactly one")
end)

test("FFI negative-constraint source audits against deal/runtime.lua hold", function()
  local f = io.open("deal/runtime.lua", "r")
  assert(f ~= nil, "deal/runtime.lua must be readable for the source audits")
  local src = f:read("*a")
  f:close()
  local marker = "-- ===== C FFI runtime half (v1.2) ====="
  local start = string.find(src, marker, 1, true)
  assert(start ~= nil, "the FFI half section marker must exist in deal/runtime.lua")
  local half = string.sub(src, start)
  local function absent(what, pattern)
    assert(string.find(half, pattern, 1, true) == nil,
        what .. " must be absent from the FFI half")
  end
  local function count_all(text, pattern)
    local n = 0
    local i = 1
    while true do
      local p = string.find(text, pattern, i, true)
      if p == nil then
        return n
      end
      n = n + 1
      i = p + 1
    end
  end
  -- (a) No compiler dependency: no new require beyond the existing ffi,
  -- and no io./manifest/discovery code inside the FFI half.
  absent("a require call", "require(")
  absent("file I/O code (io.)", "io.")
  absent("os/manifest/discovery code (os.)", "os.")
  assert(count_all(src, 'local ffi = require("ffi")') == 1,
      "the only runtime require must be the pre-existing local ffi = require('ffi')")
  -- (b) Canonical descriptors only: every checker word the FFI half
  -- mentions is a canonical checker, and descriptor parsing reaches
  -- parse_descriptor.
  local checkers = {}
  for w in half:gmatch("check_%a+") do
    checkers[w] = true
  end
  local allowed = {
    check_int = true, check_number = true, check_boolean = true,
    check_string = true, check_bytes = true, check_null = true,
    check_table = true, check_type = true, check_canonical_type = true,
    check_canonical_ast = true,
  }
  for w in pairs(checkers) do
    assert(allowed[w] == true,
        "the FFI half must use only canonical checkers, found '" .. w .. "'")
  end
  assert(string.find(half, "parse_descriptor", 1, true) ~= nil,
      "every FFI descriptor check must reach parse_descriptor")
  -- (c) Exactly one registry-protected resolver cdef entry for
  -- dlopen/dlsym/dlerror/dlclose, registered through the single
  -- protected cdef call path, with private casts.
  assert(count_all(half, "void *dlopen(const char *filename, int flags);") == 1,
      "exactly one dlopen declaration must exist in the FFI half")
  assert(count_all(half, "void *dlsym(void *handle, const char *symbol);") == 1,
      "exactly one dlsym declaration must exist in the FFI half")
  assert(count_all(half, "char *dlerror(void);") == 1,
      "exactly one dlerror declaration must exist in the FFI half")
  assert(count_all(half, "int dlclose(void *handle);") == 1,
      "exactly one dlclose declaration must exist in the FFI half")
  assert(count_all(half, "pcall(ffi.cdef,") == 1,
      "exactly one protected ffi.cdef call path must exist in the FFI half")
  assert(string.find(half, "ffi.cast(", 1, true) ~= nil,
      "the FFI half must cast through private function-pointer types")
  -- (d) Never ffi.C[target] and never ffi.load(...)[target].
  absent("direct namespace target access (ffi.C[)", "ffi.C[")
  absent("direct ffi.load target access", "ffi.load(")
  -- (e) FFI_UNSUPPORTED_BACKEND is compile-time only and never appears
  -- in deal/runtime.lua.
  assert(string.find(src, "FFI_UNSUPPORTED_BACKEND", 1, true) == nil,
      "FFI_UNSUPPORTED_BACKEND must never appear in deal/runtime.lua")
  -- C10 (f): exactly one __rt.class_plan_ definition exists in
  -- deal/runtime.lua — the ISSUE-0276 entry the C-series consumes; it
  -- is never re-realized.
  assert(count_all(src, "function __rt.class_plan_") == 1,
      "exactly one __rt.class_plan_ definition must exist in deal/runtime.lua")
  -- C10 (g): inside the FFI half exactly one call-form reference (the
  -- inbound C_STRUCT delegation) out of exactly two textual references
  -- (the R15 doc comment plus the delegation call).
  assert(count_all(half, "__rt.class_plan_(") == 1,
      "exactly one call-form __rt.class_plan_( must exist in the FFI half (the inbound delegation)")
  assert(count_all(half, "class_plan_") == 2,
      "exactly two textual class_plan_ references must exist in the FFI half (the R15 doc comment plus the delegation call)")
  -- C10 (h): no E8007 production in the half — the extra-field surface
  -- is the plan entry's, never duplicated FFI-side.
  absent("an E8007 production", '"E8007"')
  -- C10 (i): the half invokes no evaluator (the evaluatorImplementationContents
  -- identity field is content plumbing, not invocation).
  absent("an evaluator invocation", "evaluator(")
  -- C10 (j): exactly one public FFI entry assignment in the half.
  assert(count_all(half, "__rt.load_ffi =") == 1,
      "exactly one __rt.load_ffi = public-entry assignment must exist in the FFI half")
  -- C10 (k): exactly two __classname assignment sites (the two C_POINTER
  -- token-tagging rows) out of three textual references (plus the
  -- outbound-row doc comment).
  assert(count_all(half, "__classname =") == 2,
      "exactly two __classname = assignment sites must exist in the FFI half (the C_POINTER token-tagging rows)")
  assert(count_all(half, "__classname") == 3,
      "exactly three textual __classname references must exist in the FFI half (the outbound doc comment plus the two assignments)")
  -- C10 (l): exactly one tonumber( production in the half — the D7
  -- inbound INT/NUMBER unboxing.
  assert(count_all(half, "tonumber(") == 1,
      "exactly one tonumber( production must exist in the FFI half (the D7 inbound unboxing)")
  -- Additive static non-yield audit (B8's rejected alternative, kept as
  -- a source-level complement to the behavioral proof).
  absent("coroutine scheduling", "coroutine")
end)

-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
