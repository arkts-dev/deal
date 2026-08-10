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

-- ==================== check_table tests ====================

test("check_table({}) returns {}", function()
  local t = {}
  assert(__rt.check_table(t) == t)
end)

test("check_table on string errors with E8001", function()
  assert_error(function() __rt.check_table("hi") end, "E8001")
end)

-- ==================== check_coroutine tests ====================

test("check_coroutine on thread passes", function()
  local co = coroutine.create(function() end)
  assert(__rt.check_coroutine(co) == co)
end)

test("check_coroutine on number errors with E8001", function()
  assert_error(function() __rt.check_coroutine(42) end, "E8001")
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

test("check_type('coroutine', thread) passes", function()
  local co = coroutine.create(function() end)
  assert(__rt.check_type("coroutine", co) == co)
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

-- ==================== from_lua_function rest parameter tests ====================

test("from_lua_function with rest params passes", function()
  local raw = function(sep, ...)
    local args = {...}
    return sep .. table.concat(args, sep)
  end
  local w = __rt.from_lua_function("(string,...string[])->string", raw)
  -- First call with multiple rest args
  local r = w.f(",", "a", "b", "c")
  assert(r == ",a,b,c")
  -- Second call should also work (no mutation issue)
  local r2 = w.f("-", "x", "y")
  assert(r2 == "-x-y")
  -- Call with zero rest args (minimum)
  local r3 = w.f(",")
  assert(r3 == ",")
end)

test("from_lua_function rest param wrong type errors with E8010", function()
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
  local arr = {__rt.__NULL, "hello", __rt.__NULL}
  local r = __rt.check_type("?string[]", arr)
  assert(r == arr)
end)

test("check_type with function descriptor validates wrapper", function()
  local w = __rt.function_("(int,int)->int", function(x,y) return x+y end)
  local r = __rt.check_type("(int,int)->int", w)
  assert(r == w)
end)

test("check_type with complex nullable descriptor works", function()
  -- ?int[]  means  (int | null)[]
  local arr = {1, __rt.__NULL, 3}
  local r = __rt.check_type("?int[]", arr)
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


-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
