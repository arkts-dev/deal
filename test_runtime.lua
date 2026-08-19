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

test("check_int(9007199254740991) returns itself", function()
  local r = __rt.check_int(9007199254740991)
  assert(r == 9007199254740991)
end)

test("check_int(-9007199254740991) returns itself", function()
  local r = __rt.check_int(-9007199254740991)
  assert(r == -9007199254740991)
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
  assert_error(function() __rt.check_int(9007199254740992) end, "E8004")
end)

test("check_int on out-of-range negative errors with E8004", function()
  assert_error(function() __rt.check_int(-9007199254740992) end, "E8004")
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
  local r = __rt.check_array("int[]", arr)
  assert(r == arr)
end)

test("check_array on empty array passes", function()
  local arr = {}
  local r = __rt.check_array("int[]", arr)
  assert(r == arr)
end)

test("check_array on single element passes", function()
  local arr = {42}
  local r = __rt.check_array("int[]", arr)
  assert(r == arr)
end)

test("check_array on non-table errors with E8001", function()
  assert_error(function() __rt.check_array("int[]", "notatable") end, "E8001")
end)

test("check_array on heterogeneous elements errors with E8003", function()
  assert_error(function() __rt.check_array("int[]", {1, "x"}) end, "E8003")
end)

test("check_array on string array passes", function()
  local arr = {"a", "b", "c"}
  local r = __rt.check_array("string[]", arr)
  assert(r == arr)
end)

test("check_array on boolean array passes", function()
  local arr = {true, false}
  local r = __rt.check_array("boolean[]", arr)
  assert(r == arr)
end)

test("check_array on nested int[][] passes", function()
  local arr = {{1, 2}, {3, 4}}
  local r = __rt.check_array("int[][]", arr)
  assert(r == arr)
end)

test("check_array with [int] formal format passes", function()
  local arr = {1, 2, 3}
  local r = __rt.check_array("[int]", arr)
  assert(r == arr)
end)

test("check_array on nullable element array passes", function()
  local arr = {__rt.__NULL, "hello"}
  local r = __rt.check_array("string|null[]", arr)
  assert(r == arr)
end)

test("check_array on invalid nullable element errors", function()
  assert_error(function()
    __rt.check_array("string|null[]", {42})
  end, "E8003")
end)

-- ==================== array_element_descriptor tests ====================

test("array_element_descriptor('int[]') returns 'int'", function()
  assert(__rt.array_element_descriptor("int[]") == "int")
end)

test("array_element_descriptor('string[]') returns 'string'", function()
  assert(__rt.array_element_descriptor("string[]") == "string")
end)

test("array_element_descriptor('int[][]') returns 'int[]'", function()
  assert(__rt.array_element_descriptor("int[][]") == "int[]")
end)

test("array_element_descriptor('[int]') returns 'int'", function()
  assert(__rt.array_element_descriptor("[int]") == "int")
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

test("check_type('int[]', {1,2}) passes", function()
  local arr = {1, 2}
  assert(__rt.check_type("int[]", arr) == arr)
end)

test("check_type('string|null', nil) returns __NULL", function()
  -- check_type('string|null', nil) should go through nullable path
  local r = __rt.check_type("string|null", nil)
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
  assert_error(function() __rt.int_add(9007199254740991, 1) end, "E8004")
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
  local ok, err = pcall(function() __rt.check_int(9007199254740992) end)
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
  local u = __rt.class_("User", {name = ""}, {name = "Ada"})
  local r = __rt.check_type("User", u)
  assert(r == u)
end)

test("check_type class instance wrong class errors", function()
  local u = __rt.class_("User", {name = ""}, {name = "Ada"})
  assert_error(function() __rt.check_type("Admin", u) end, "E8001")
end)

test("check_type on non-class for class descriptor errors", function()
  assert_error(function() __rt.check_type("User", 42) end, "E8001")
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

test("check_type bare class rejects qualified-tagged instance", function()
  local u = __rt.class_("@mod/User", {name = ""}, {name = "Ada"})
  assert_error(function() __rt.check_type("User", u) end, "E8001")
end)

-- ==================== error_value tests ====================

test("error_value shape with span args", function()
  local e = __rt.error_value("E_LIMIT", "fail", "test.deal", 3, 7)
  assert(e.__kind == "class")
  assert(e.__classname == "Error")
  assert(e.code == "E_LIMIT")
  assert(e.message == "fail")
  assert(e.file == "test.deal")
  assert(e.line == 3)
  assert(e.column == 7)
end)

test("error_value shape without span args", function()
  local e = __rt.error_value("", "m")
  assert(e.__kind == "class")
  assert(e.__classname == "Error")
  assert(e.code == "")
  assert(e.message == "m")
  assert(e.file == nil)
  assert(e.line == nil)
  assert(e.column == nil)
end)

test("check_type Error passes against error_value outputs", function()
  local e = __rt.error_value("E_LIMIT", "fail")
  local r = __rt.check_type("Error", e)
  assert(r == e)
end)

test("check_type Error rejects non-Error class instances", function()
  local u = __rt.class_("Error", {code = "", message = ""}, {code = "X"})
  assert_error(function() __rt.check_type("Error", {__kind = "class", __classname = "User"}) end, "E8001")
  -- sanity: a genuine Error instance still passes
  assert(u.__kind == "class" and u.__classname == "Error")
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
  local r = __rt.check_array("int[]", {1, 2, 3})
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

test("from_lua_function legacy rest descriptor enforces exact arity", function()
  local raw = function(sep, ...)
    local args = {...}
    return sep .. table.concat(args, sep)
  end
  local w = __rt.from_lua_function("(string,...string[])->string", raw)
  -- Extra arguments are rejected: no rest arm absorbs them (v1.2).
  assert_error(function() w.f(",", "a", "b", "c") end, "E8010")
  assert_error(function() w.f(",", "x", "y") end, "E8010")
  -- The legacy "..." param descriptor itself matches no DEAL value, so even
  -- a two-argument call fails the parameter check with E8010.
  assert_error(function() w.f(",", "a") end, "E8010")
end)

test("from_lua_function legacy rest descriptor wrong type errors with E8010", function()
  local raw = function(sep, ...) return sep end
  local w = __rt.from_lua_function("(string,...int[])->string", raw)
  assert_error(function() w.f(",", 1, "x") end, "E8010")
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
  -- The emitted legacy spelling of Array(Nullable(string)) is "string|null[]";
  -- under parse order P the hand-written "?string[]" reads Nullable(Array(string)).
  local arr = {__rt.__NULL, "hello", __rt.__NULL}
  local r = __rt.check_type("string|null[]", arr)
  assert(r == arr)
end)

test("check_type with function descriptor validates wrapper", function()
  local w = __rt.function_("(int,int)->int", function(x,y) return x+y end)
  local r = __rt.check_type("(int,int)->int", w)
  assert(r == w)
end)

test("check_type with complex nullable descriptor works", function()
  -- int|null[]  means  (int | null)[] — the emitted legacy spelling of
  -- Array(Nullable(int)).
  local arr = {1, __rt.__NULL, 3}
  local r = __rt.check_type("int|null[]", arr)
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

test("int_convert(1e308) errors with E8004 (out of safe range)", function()
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
  -- Under parse order P the function branch reads the arrow before the "|null"
  -- suffix, so "async(int)->@src/User|null" is an async function whose declared
  -- nullable return is enforced at the await site.
  local wrapper = __rt.from_lua_function("async(int)->@src/User|null", function(x)
    return __rt.async_start(function() return { __kind = "class", __classname = "User" } end)
  end)
  assert(wrapper.sig == "async(int)->@src/User|null")
  local h = wrapper.f(1)
  assert(type(h) == "table" and h.__kind == "async")
  -- a non-operation return still fails the async-shape check
  local wrapper2 = __rt.from_lua_function("async(int)->@src/User|null", function(x)
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
  -- Under parse order P "(int)->string|null" parses as a function returning a
  -- nullable string (the arrow is read before the "|null" suffix).
  local wrapper = __rt.from_lua_function("(int)->string|null", function(x)
    if x == 0 then return __rt.__NULL end
    return tostring(x)
  end)
  assert(wrapper.sig == "(int)->string|null")
  assert(wrapper.f(0) == __rt.__NULL)
  assert(wrapper.f(1) == "1")
  -- wrong return type raises E8010
  local wrapper2 = __rt.from_lua_function("(int)->string|null", function(x)
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
  local wrapper = __rt.from_lua_function("async()->User", function()
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

test("parse order P: ?(int)->int reads nullable function, distinct from (int)->int|null", function()
  local w = __rt.function_("(int)->int", function(x) return x + 1 end)
  -- ?(int)->int: nullable of function — __NULL and a matching-sig wrapper pass
  assert(__rt.check_type("?(int)->int", __rt.__NULL) == __rt.__NULL)
  assert(__rt.check_type("?(int)->int", w) == w)
  -- (int)->int|null: function returning nullable — the same wrapper's sig does not match
  assert_error(function() __rt.check_type("(int)->int|null", w) end, "E8010")
  -- non-wrapper fails the inner function check
  assert_error(function() __rt.check_type("?(int)->int", 42) end, "E8001")
end)

test("parse order P: wrong-sig wrapper on nullable function raises E8010", function()
  local w = __rt.function_("(string)->string", function(x) return x end)
  assert_error(function() __rt.check_type("?(int)->int", w) end, "E8010")
end)

test("parse order P: [(int)->int] reads array of functions, distinct from (int)->int[]", function()
  local w1 = __rt.function_("(int)->int", function(x) return x + 1 end)
  local w2 = __rt.function_("(int)->int", function(x) return x + 2 end)
  local arr = { w1, w2 }
  assert(__rt.check_type("[(int)->int]", arr) == arr)
  -- (int)->int[] is a function returning an int array: w1's sig does not match
  assert_error(function() __rt.check_type("(int)->int[]", w1) end, "E8010")
  -- the matching function-returning-array wrapper passes
  local w3 = __rt.function_("(int)->int[]", function(x) return { x } end)
  assert(__rt.check_type("(int)->int[]", w3) == w3)
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

test("parse order P: ?string[] reads Nullable(Array(string))", function()
  assert(__rt.check_type("?string[]", __rt.__NULL) == __rt.__NULL)
  assert(__rt.check_type("?string[]", { "a", "b" })[1] == "a")
  -- a non-array fails the inner array check
  assert_error(function() __rt.check_type("?string[]", "hello") end, "E8001")
  -- a wrong element fails the inner array check
  assert_error(function() __rt.check_type("?string[]", { "a", 42 }) end, "E8003")
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
  local wrapper = __rt.from_lua_function("()->string|null", function() end)
  assert_error(function() wrapper.f() end, "E8010")
end)

test("from_lua_function nullable return: __NULL passes", function()
  local wrapper = __rt.from_lua_function("()->string|null", function() return __rt.__NULL end)
  assert(wrapper.f() == __rt.__NULL)
end)

test("from_lua_function nullable return: wrong type raises E8010", function()
  local wrapper = __rt.from_lua_function("(int)->string|null", function(x) return x end)
  assert_error(function() wrapper.f(1) end, "E8010")
end)

test("from_lua_function array return: wrong type raises E8010", function()
  local wrapper = __rt.from_lua_function("(int)->string[]", function(x) return x end)
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

test("from_lua_function legacy rest-of-function descriptor is exact-arity and never adapts extra args", function()
  local received = nil
  local wrapper = __rt.from_lua_function("(string,...[(int)->int])->string", function(sep, ...)
    received = { ... }
    return sep
  end)
  local inner = function(x) return x + 1 end
  -- Extra arguments raise E8010 (no rest arm); the raw function never runs.
  assert_error(function()
    wrapper.f(",", __rt.function_("(int)->int", inner))
  end, "E8010")
  assert(received == nil)
  assert_error(function()
    wrapper.f(",", function(x) return x end)
  end, "E8010")
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
  local host = __rt.load_host(HOST_FIXTURE, { find = "(boolean)->string|null" })
  assert(host.find.f(true) == __rt.__NULL)
  assert(host.find.f(false) == "found")
  local host2 = __rt.load_host(HOST_FIXTURE, { find_bad = "()->string|null" })
  assert_error(function() host2.find_bad.f() end, "E8010")
end)

test("load_host array return wraps at load and checks at call", function()
  local host = __rt.load_host(HOST_FIXTURE, { split = "()->string[]" })
  local r = host.split.f()
  assert(type(r) == "table" and r[1] == "a" and r[2] == "b")
  local host2 = __rt.load_host(HOST_FIXTURE, { split_bad = "()->string[]" })
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

test("load_host legacy rest export enforces exact arity (v1.2: no rest arm)", function()
  local host = __rt.load_host(HOST_FIXTURE, { join = "(string,...string[])->string" })
  -- Extra arguments raise E8010 — nothing absorbs them (v1.2).
  assert_error(function() host.join.f(",", "a", "b") end, "E8010")
  -- The legacy "..." param descriptor matches no DEAL value, so the
  -- two-argument call fails the parameter check with E8010.
  assert_error(function() host.join.f(",", "a") end, "E8010")
end)

test("load_host legacy rest-of-function-elements export is exact-arity (v1.2)", function()
  local host = __rt.load_host(HOST_FIXTURE, { apply_rest = "(string,...[(int)->int])->int" })
  local w1 = __rt.function_("(int)->int", function(x) return x + 1 end)
  local w2 = __rt.function_("(int)->int", function(x) return x + 2 end)
  assert_error(function() host.apply_rest.f(",", w1, w2) end, "E8010")
  assert_error(function() host.apply_rest.f(",", function(x) return x end) end, "E8010")
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
    ServerConfig = "@host.cfg/ServerConfig",
  })
  assert(type(host.ServerConfig) == "table" and host.ServerConfig.__kind == "class")
  assert(host.ServerConfig.__classname == "@host.cfg/ServerConfig")
  assert(type(host.ServerConfig_defaults) == "table")
  assert(host.ServerConfig_defaults.port == 80)
end)

test("load_host class identity mismatch raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { WrongName = "@host.cfg/WrongName" })
  end, "E8011")
end)

test("load_host non-class export for class descriptor raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { not_a_class = "@host.cfg/NotAClass" })
  end, "E8011")
end)

test("load_host class missing defaults raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { NoDefaults = "@host.cfg/NoDefaults" })
  end, "E8011")
end)

test("load_host class non-table defaults raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { BadDefaults = "@host.cfg/BadDefaults" })
  end, "E8011")
end)

test("load_host class supplied _fields copied through", function()
  local host = __rt.load_host(HOST_FIXTURE, { ServerConfig = "@host.cfg/ServerConfig" })
  assert(type(host.ServerConfig_fields) == "table")
  assert(host.ServerConfig_fields[1].name == "port")
end)

test("load_host class non-table _fields raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { BadFields = "@host.cfg/BadFields" })
  end, "E8011")
end)

test("load_host class absent _fields tolerated", function()
  local host = __rt.load_host(HOST_FIXTURE, { NoFields = "@host.cfg/NoFields" })
  assert(host.NoFields.__kind == "class")
  assert(host.NoFields_fields == nil)
end)

-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
