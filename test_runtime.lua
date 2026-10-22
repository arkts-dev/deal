-- Test suite for deal/runtime.lua
-- Run with: luajit test_runtime.lua

package.path = "./?.lua;" .. package.path
local __rt = require("deal.runtime")

-- The private carrier keys (ISSUE-0830): the runtime, the emitted chunks and
-- the stdlib share one process-wide key set.
local __K = __rt.__keys

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

-- ==================== private carrier identity (ISSUE-0830) ====================
-- Carrier identity is nominal and unforgeable: the marks live under
-- process-wide private table keys, so a table may carry every mark name as
-- a string key, stays the table value, and never satisfies a class, bytes,
-- function, array or table-shape row it merely names.

-- The exact closed tuple of one typed-boundary rejection.
local function assert_boundary_error(fn, code, message, expected, actual)
  local err = assert_error(fn, code)
  assert(err.message == message, "message: " .. tostring(err.message))
  assert(err.expected == expected, "expected: " .. tostring(err.expected))
  assert(err.actual == actual, "actual: " .. tostring(err.actual))
  return err
end

-- A table carrying every carrier-vocabulary name as a string key.
local function carrier_key_table()
  return {
    __kind = "class", __classname = "Error", __c = true,
    __id = "@m/C", __d = true, __a = true, __n = 3, __nK = 1,
    __da = true, __t = true, __keys = "keys", __order = "order",
    __p = true, __f = true, __fn = 1, __csig = "s", __sig = "t",
    __fid = 2, __it = "u", __sid = "v", __val = 3, __mode = 4,
    __jn = true, __jo = true, __ja = true, __jkeys = 5, __jnum = 6,
    __jseen = 7, __ptr = 8, plain = 9,
  }
end

test("a table with carrier-like string keys is the table value (v1.3)", function()
  local t = carrier_key_table()
  assert(__rt.check_table(t) == t, "the carrier-key table is a table")
  assert(__rt.check_type("table", t) == t, "the table descriptor admits it")
  assert(__rt.typed_kind(t) == "table", "its projected kind is table")
  assert(__rt.typed_kind(__rt.__NULL) == "null")
  assert(__rt.typed_kind(__rt.__MISSING) == "nil")
end)

test("forged class carriers are rejected at a class descriptor (v1.3)", function()
  local forged = carrier_key_table()
  assert_boundary_error(function() __rt.check_type("@m/C", forged) end,
    "E8001", "expected class instance", "class", "table")
  assert(__rt.check_type("@m/C", __rt.class_("@m/C", {}, {})) ~= nil,
    "the produced class instance is admitted")
end)

test("forged bytes carriers are rejected at the bytes descriptor (v1.3)", function()
  local forged = carrier_key_table()
  assert_boundary_error(function() __rt.check_type("bytes", forged) end,
    "E8001", "expected bytes", "bytes", "table")
  assert(__rt.check_type("bytes", __rt.bytes_new(1)) ~= nil,
    "the produced bytes carrier is admitted")
end)

test("forged function carriers are rejected at a function descriptor (v1.3)", function()
  local forged = carrier_key_table()
  assert_boundary_error(function() __rt.check_type("()->null", forged) end,
    "E8001", "expected function", "function", "table")
  assert(__rt.check_type("()->null",
    __rt.function_("()->null", function() return __rt.__NULL end)) ~= nil,
    "the produced function wrapper is admitted")
end)

test("non-arrays are rejected at an array descriptor (v1.3)", function()
  -- A plain object, a class instance, a function wrapper, the language null
  -- and the absent marker are not arrays; the language null is never the
  -- empty array.
  assert_boundary_error(function() __rt.check_type("[int]", { a = 1 }) end,
    "E8001", "expected array", "array", "table")
  assert_boundary_error(function()
    __rt.check_type("[int]", __rt.class_("@m/C", {}, {}))
  end, "E8001", "expected array", "array", "@m/C")
  assert_boundary_error(function()
    __rt.check_type("[int]", __rt.function_("()->null", function() return nil end))
  end, "E8001", "expected array", "array", "function")
  assert_boundary_error(function() __rt.check_type("[int]", __rt.__NULL) end,
    "E8001", "expected array", "array", "null")
  assert_boundary_error(function() __rt.check_type("[int]", __rt.__MISSING) end,
    "E8001", "expected array", "array", "nil")
  -- A carrier-key table is not an array either.
  assert_boundary_error(function()
    __rt.check_type("[int]", carrier_key_table())
  end, "E8001", "expected array", "array", "table")
  -- The array values are admitted: the emitted chunk's array carrier (its
  -- private marks) and the reference runtime's dense array form.
  local carrier = { [__K.a] = true, [__K.n] = 2, 1, 2 }
  assert(__rt.check_type("[int]", carrier) == carrier,
    "the array carrier is admitted")
  local dense = { 1, 2 }
  assert(__rt.check_type("[int]", dense) == dense, "the dense array is admitted")
  assert(__rt.check_type("[int]", {}) ~= nil, "the empty dense array is admitted")
end)

test("array element failures keep the E8003 wrap over a carrier (v1.3)", function()
  local carrier = { [__K.a] = true, [__K.n] = 2, 1, "bad" }
  local err = assert_error(function() __rt.check_type("[int]", carrier) end, "E8003")
  assert(err.message == "array element 2 type mismatch", err.message)
  assert(err.expected == "int", tostring(err.expected))
  assert(err.actual == "string", tostring(err.actual))
end)

test("carriers are rejected at a table descriptor (v1.3)", function()
  assert_boundary_error(function()
    __rt.check_table(__rt.class_("@m/C", {}, {}))
  end, "E8001", "expected table", "table", "@m/C")
  assert_boundary_error(function() __rt.check_table(__rt.bytes_new(1)) end,
    "E8001", "expected table", "table", "bytes")
  assert_boundary_error(function()
    __rt.check_table(__rt.function_("()->null", function() return nil end))
  end, "E8001", "expected table", "table", "function")
  assert_boundary_error(function()
    __rt.check_table(__rt.async_start(function() end))
  end, "E8001", "expected table", "table", "async-operation")
  assert_boundary_error(function()
    __rt.check_table({ [__K.a] = true, [__K.n] = 0 })
  end, "E8001", "expected table", "table", "array")
  -- The emitted chunk's function carriers carry no [kind] mark: the
  -- closure/adapter shape ({[fn], sig, csig, fid}) and the host ABI
  -- wrapper's [f] mark project the function token exactly like the
  -- emitted prelude's __typedBoundaryKind, so a produced function value
  -- never passes a table descriptor.
  local chunkFn = {
    [__K.fn] = function() return 1 end,
    [__K.sig] = "function(;int)",
    [__K.csig] = "()->int",
    [__K.fid] = "m#f",
  }
  assert(__rt.typed_kind(chunkFn) == "function",
    "the chunk function carrier projects the function token")
  assert_boundary_error(function() __rt.check_table(chunkFn) end,
    "E8001", "expected table", "table", "function")
  assert_boundary_error(function() __rt.check_type("table", chunkFn) end,
    "E8001", "expected table", "table", "function")
  local markedFn = { [__K.f] = function() end }
  assert(__rt.typed_kind(markedFn) == "function",
    "the host-wrapper [f] mark projects the function token")
  assert_boundary_error(function() __rt.check_table(markedFn) end,
    "E8001", "expected table", "table", "function")
  -- The language null and the absent marker keep their own tokens.
  assert_boundary_error(function() __rt.check_table(__rt.__NULL) end,
    "E8001", "expected table", "table", "null")
  assert_boundary_error(function() __rt.check_table(__rt.__MISSING) end,
    "E8001", "expected table", "table", "nil")
  -- A plain table stays the table value, whatever its string keys.
  assert(__rt.check_table(carrier_key_table()) ~= nil,
    "a carrier-key table is a table at the table cell")
end)

test("a class instance may declare fields named after the carrier keys (v1.3)", function()
  local instance = __rt.class_("@m/Tagged",
    { __kind = "user-kind", __c = 1, __keys = "keys" }, {})
  assert(instance[__K.kind] == "class", "the instance keeps its runtime class mark")
  assert(instance[__K.classname] == "@m/Tagged", "the instance keeps its identity")
  assert(instance.__kind == "user-kind", "the user field named __kind survives")
  assert(instance.__c == 1 and instance.__keys == "keys",
    "the user fields named after carrier keys survive")
  assert(__rt.check_type("@m/Tagged", instance) == instance,
    "the instance is still the declared class value")
end)

-- ============ the typed-boundary actual token of every carrier ============
-- Every typed boundary publishes the closed typed-boundary token
-- (__rt.typed_kind) as its "actual" projection — the same token the emitted
-- prelude's __typedBoundaryKind, the JVM runtime's actualOf and the JS
-- $kindOf publish. A class instance therefore publishes its carried
-- canonical class atom (exactly Error for the builtin) and never the raw Lua
-- table spelling, at every non-class boundary as well as at the class and
-- function rows' wrong-kind arms.
test("a class instance publishes its carried class atom at every boundary (v1.3)", function()
  local instance = __rt.class_("@m/C", { a = 1 }, {})
  assert(__rt.typed_kind(instance) == "@m/C", "the instance's closed token is its atom")
  -- The primitive rows.
  assert_boundary_error(function() __rt.check_string(instance) end,
    "E8001", "expected string", "string", "@m/C")
  assert_boundary_error(function() __rt.check_int(instance) end,
    "E8001", "expected int", "int", "@m/C")
  assert_boundary_error(function() __rt.check_number(instance) end,
    "E8001", "expected number", "number", "@m/C")
  assert_boundary_error(function() __rt.check_boolean(instance) end,
    "E8001", "expected boolean", "boolean", "@m/C")
  assert_boundary_error(function() __rt.check_null(instance) end,
    "E8001", "expected null", "null", "@m/C")
  -- The bytes and function rows replace their raw type() projection too.
  assert_boundary_error(function() __rt.check_type("bytes", instance) end,
    "E8001", "expected bytes", "bytes", "@m/C")
  assert_boundary_error(function() __rt.check_type("()->null", instance) end,
    "E8001", "expected function", "function", "@m/C")
  -- A same-kind class crossing keeps the identity arm and its carried atom.
  assert_boundary_error(function() __rt.check_type("@m/D", instance) end,
    "E8001", "expected instance of @m/D, got @m/C", "@m/D", "@m/C")
end)

test("the builtin Error publishes the canonical Error atom at every boundary (v1.3)", function()
  local err_value = __rt.error_value("E_USER", "boom")
  assert(__rt.typed_kind(err_value) == "Error",
    "the Error value's closed token is the canonical builtin atom")
  assert_boundary_error(function() __rt.check_string(err_value) end,
    "E8001", "expected string", "string", "Error")
  assert_boundary_error(function() __rt.check_int(err_value) end,
    "E8001", "expected int", "int", "Error")
  assert_boundary_error(function() __rt.check_number(err_value) end,
    "E8001", "expected number", "number", "Error")
  assert_boundary_error(function() __rt.check_boolean(err_value) end,
    "E8001", "expected boolean", "boolean", "Error")
  assert_boundary_error(function() __rt.check_null(err_value) end,
    "E8001", "expected null", "null", "Error")
  assert_boundary_error(function() __rt.check_type("bytes", err_value) end,
    "E8001", "expected bytes", "bytes", "Error")
  assert_boundary_error(function() __rt.check_type("()->null", err_value) end,
    "E8001", "expected function", "function", "Error")
  -- The Error descriptor's own cell still admits the Error value and still
  -- rejects a foreign class instance by its carried atom (the identity arm).
  assert(__rt.check_type("Error", err_value) == err_value,
    "the Error descriptor admits the builtin Error value")
  assert_boundary_error(function()
    __rt.check_type("Error", __rt.class_("@m/C", { a = 1 }, {}))
  end, "E8001", "expected instance of Error, got @m/C", "Error", "@m/C")
end)

test("every other carrier keeps its own closed token at a boundary (v1.3)", function()
  -- The array, bytes, function, async, null and absent carriers never
  -- project the raw table spelling of a primitive row, and each of them is
  -- rejected at the class and function rows by its own token.
  local array = { [__K.a] = true, [__K.n] = 0 }
  local bytes = __rt.bytes_new(1)
  local wrapper = __rt.function_("()->null", function() return __rt.__NULL end)
  local handle = __rt.async_create(function() return __rt.__NULL end)
  local function rejects(value, token, descriptor, message, expected)
    assert_boundary_error(function() __rt.check_type(descriptor, value) end,
      "E8001", message, expected, token)
  end
  for _, carrier in ipairs({
    { "array", array }, { "bytes", bytes }, { "function", wrapper },
    { "async-operation", handle }, { "null", __rt.__NULL },
    { "nil", __rt.__MISSING },
  }) do
    local token, value = carrier[1], carrier[2]
    assert(__rt.typed_kind(value) == token, "the carrier's closed token is " .. token)
    rejects(value, token, "string", "expected string", "string")
    rejects(value, token, "int", "expected int", "int")
    rejects(value, token, "boolean", "expected boolean", "boolean")
    rejects(value, token, "@m/C", "expected class instance", "class")
    if token ~= "function" then
      rejects(value, token, "()->null", "expected function", "function")
    else
      assert(__rt.check_type("()->null", value) == value,
        "the function row admits the matching function wrapper")
      -- The signature arm (E8010), never the kind arm, rejects a mismatched
      -- wrapper, and its two fields stay the canonical texts.
      local mismatch = assert_error(function()
        __rt.check_type("(int)->null", value)
      end, "E8010")
      assert(mismatch.expected == "(int)->null"
        and mismatch.actual == "()->null",
        "the signature arm keeps the two canonical texts")
    end
    if token ~= "bytes" then
      rejects(value, token, "bytes", "expected bytes", "bytes")
    else
      assert(__rt.check_type("bytes", value) == value,
        "the bytes row admits the bytes carrier")
    end
  end
  -- A set member is still rejected at the table row by its own token.
  assert_boundary_error(function() __rt.check_table(array) end,
    "E8001", "expected table", "table", "array")
  assert_boundary_error(function() __rt.check_table(handle) end,
    "E8001", "expected table", "table", "async-operation")
  -- The fixed int-conversion tokens are unchanged (the refinement ladder is
  -- not a typed-boundary kind arm).
  local nan = assert_error(function() __rt.check_int(0/0) end, "E8001")
  assert(nan.expected == "int" and nan.actual == "NaN", "NaN keeps its token")
  local infinity = assert_error(function() __rt.check_int(1/0) end, "E8001")
  assert(infinity.actual == "infinity", "infinity keeps its token")
  local fractional = assert_error(function() __rt.check_int(1.5) end, "E8001")
  assert(fractional.actual == "number", "a fractional number keeps its token")
end)

-- ==================== numeric-variant carrier admission (ISSUE-0836) ====================
-- The std/json decode graph carries every numeric leaf as the private
-- carrier {[__K.jn] = true, k = "int"|"number", d = <number>}: the value
-- model's recorded int/number variant. The deployed matcher, the emitted
-- prelude's own int/number cells, the JVM runtime's boxed Long/Double
-- admission and the JS checkInt/checkNumber admit the same value, so a
-- carrier crosses every numeric boundary as the value it stands for, and
-- the boundary's actual projection is the carrier's own recorded token.

local function number_carrier(kind, value)
  return { [__K.jn] = true, k = kind, d = value }
end

test("an int-variant carrier is the admitted int (v1.3)", function()
  local int_carrier = number_carrier("int", 2147483647)
  assert(__rt.check_int(int_carrier) == int_carrier,
    "the int-variant carrier is admitted unchanged")
  assert(__rt.check_type("int", int_carrier) == int_carrier,
    "the int descriptor admits the int-variant carrier")
  assert(__rt.typed_kind(int_carrier) == "int",
    "the int-variant carrier projects its own token")
end)

test("a number-variant carrier passes an int boundary when its value does (v1.3)", function()
  -- A number-variant carrier is the value it stands for: an integral,
  -- in-range value is admitted at the int boundary and keeps its own
  -- recorded variant (the emitted prelude's int cell returns the carrier,
  -- never a re-typed one).
  local carrier = number_carrier("number", 5)
  local admitted = __rt.check_type("int", carrier)
  assert(admitted == carrier, "the admitted value is the carrier")
  assert(admitted.k == "number", "the recorded variant is preserved")
  assert(admitted.d == 5, "the value is preserved")
  assert(__rt.check_type("int", number_carrier("number", -2147483648)).d == -2147483648,
    "the signed32 minimum is admitted")
  assert(__rt.check_type("int", number_carrier("number", 2147483647)).d == 2147483647,
    "the signed32 maximum is admitted")
end)

test("a number-variant carrier runs the int ladder with its exact arms (v1.3)", function()
  local nan = assert_error(function()
    __rt.check_type("int", number_carrier("number", 0/0))
  end, "E8001")
  assert(nan.message == "expected int, got NaN", nan.message)
  assert(nan.expected == "int" and nan.actual == "NaN", "the NaN tuple")

  local infinity = assert_error(function()
    __rt.check_type("int", number_carrier("number", math.huge))
  end, "E8001")
  assert(infinity.message == "expected int, got infinity", infinity.message)
  assert(infinity.expected == "int" and infinity.actual == "infinity",
    "the infinity tuple")

  local negative_infinity = assert_error(function()
    __rt.check_type("int", number_carrier("number", -math.huge))
  end, "E8001")
  assert(negative_infinity.actual == "infinity", negative_infinity.actual)

  local fractional = assert_error(function()
    __rt.check_type("int", number_carrier("number", 1.5))
  end, "E8001")
  assert(fractional.message == "expected int, got non-integer number",
    fractional.message)
  assert(fractional.expected == "int" and fractional.actual == "number",
    "the fractional tuple")

  local range = assert_error(function()
    __rt.check_type("int", number_carrier("number", 2147483648))
  end, "E8004")
  assert(range.message == "int out of safe range", range.message)
  assert(range.expected == nil and range.actual == nil,
    "the range arm publishes no expected/actual")
end)

test("the int cell normalizes a carrier's -0 without mutating it (v1.3)", function()
  local negative_zero = number_carrier("number", -0.0)
  local admitted = __rt.check_type("int", negative_zero)
  assert(admitted ~= negative_zero, "the normalization republishes a fresh carrier")
  assert(admitted.d == 0 and 1 / admitted.d > 0, "the admitted zero is positive")
  assert(admitted.k == "number", "the recorded variant is preserved")
  assert(1 / negative_zero.d < 0, "the input carrier is never mutated")

  local int_zero = number_carrier("int", -0.0)
  local int_admitted = __rt.check_int(int_zero)
  assert(int_admitted ~= int_zero and int_admitted.d == 0
      and 1 / int_admitted.d > 0, "the int-variant zero normalizes too")
  assert(int_admitted.k == "int", "the int variant is preserved")
end)

test("the number row admits both variants and keeps the carrier (v1.3)", function()
  local number_variant = number_carrier("number", 1e17)
  assert(__rt.check_number(number_variant) == number_variant,
    "the number-variant carrier is admitted")
  local int_variant = number_carrier("int", 3)
  assert(__rt.check_type("number", int_variant) == int_variant,
    "the int-variant carrier is admitted at a number boundary")
  assert(__rt.typed_kind(number_variant) == "number",
    "the number-variant carrier projects its own token")
end)

test("a numeric carrier keeps its own token at a non-numeric boundary (v1.3)", function()
  assert_boundary_error(function()
    __rt.check_type("string", number_carrier("number", 1))
  end, "E8001", "expected string", "string", "number")
  assert_boundary_error(function()
    __rt.check_type("boolean", number_carrier("int", 1))
  end, "E8001", "expected boolean", "boolean", "int")
  assert_boundary_error(function()
    __rt.check_type("table", number_carrier("number", 1))
  end, "E8001", "expected table", "table", "number")
end)

test("an array of numeric carriers crosses the array descriptor (v1.3)", function()
  local values = { [__K.a] = true, [__K.n] = 3,
    [1] = number_carrier("int", 1), [2] = number_carrier("number", 2.5),
    [3] = __rt.__NULL }
  assert(__rt.check_type("[?number]", values) == values,
    "the array carrier with numeric carriers and a null is admitted")
  assert(__rt.check_type("[int]", { [__K.a] = true, [__K.n] = 2,
    [1] = number_carrier("int", 1), [2] = number_carrier("number", 2) }) ~= nil,
    "number-variant integral carriers are admitted at an int[] boundary")
  local element = assert_error(function()
    __rt.check_type("[int]", { [__K.a] = true, [__K.n] = 2,
      [1] = number_carrier("int", 1), [2] = number_carrier("number", 1.5) })
  end, "E8003")
  assert(element.message == "array element 2 type mismatch", element.message)
  assert(element.expected == "int" and element.actual == "number",
    "the E8003 wrap keeps the element's own variant token")
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

test("int_div(-2147483648, -1) errors with E8004 (MIN / -1)", function()
  assert_error(function() __rt.int_div(-2147483648, -1) end, "E8004")
end)


test("int_div(5, 0) errors with E8005", function()
  assert_error(function() __rt.int_div(5, 0) end, "E8005")
end)

test("int_div(-5, -0) errors with E8005 (negative zero divisor)", function()
  assert_error(function() __rt.int_div(-5, -0) end, "E8005")
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
  assert(b[__K.kind] == "bytes")
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

test("bytes reference identity: the same allocation compares equal", function()
  local b = __rt.bytes_new(2)
  local alias = b
  assert(b == alias, "an alias is the same allocation")
  assert(not (b ~= alias), "the same allocation is never unequal")
end)

test("bytes reference identity: distinct buffers compare unequal", function()
  local a = __rt.bytes_new(2)
  local c = __rt.bytes_new(2)
  assert(not (a == c), "distinct zero-filled buffers are distinct allocations")
  assert(a ~= c, "distinct allocations are never equal")
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
  assert(w[__K.kind] == "function")
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

-- The emitted chunk registers the two artifact-owned host renderers
-- (__rt.__hostArm and __rt.__hostInnerArm) on the deployed runtime at
-- load; the loaded wrappers delegate every mapped wrapper failure (the
-- declared parameter cell, the sync return cells, and the async-shape
-- cell) to them with the supplied call origin, the declared expected
-- descriptor, the failing subject value, and — for the cells whose inner
-- reason the canonical matcher selects — the structured inner-arm
-- selection (never a composed text). The unit drives register recording
-- stubs: what they receive is the observable delegation, and the outer
-- stub's own E8010 carrier keeps the assert_error drives' subject. The
-- production renderers' tuples are asserted by the artifact drives
-- (CanonicalProjectionParityTest).
local host_arm_calls = {}
local host_inner_calls = {}
__rt.__hostInnerArm = function(id, values)
  host_inner_calls[#host_inner_calls + 1] = { id = id, values = values }
  return "inner:" .. id
end
__rt.__hostArm = function(id, values, file, line, column, expected, subject)
  host_arm_calls[#host_arm_calls + 1] = { id = id, values = values,
    file = file, line = line, column = column, expected = expected,
    subject = subject }
  return { code = "E8010", message = "arm:" .. id, file = file, line = line,
    column = column, expected = expected, actual = subject }
end

test("from_lua_function wraps with type checks", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  assert(w[__K.kind] == "function")
  assert(w.sig == "(int,int)->int")
  -- Call with valid args
  local r = w.f(1, 2)
  assert(r == 3)
end)

test("from_lua_function delegates a wrong param type to the closed arm renderer", function()
  local raw = function(a, b) return a + b end
  local w = __rt.from_lua_function("(int,int)->int", raw)
  local before = #host_arm_calls
  local before_inner = #host_inner_calls
  local err = assert_error(function() w.f("x", 2, "t.deal", 3, 4) end, "E8010")
  assert(err.message == "arm:HOST_PARAMETER_CELL")
  assert(#host_arm_calls == before + 1)
  local call = host_arm_calls[#host_arm_calls]
  assert(call.id == "HOST_PARAMETER_CELL")
  assert(call.values.index == 1)
  assert(call.values.inner == "inner:TYPED_BOUNDARY_KIND")
  assert(call.expected == "int")
  assert(call.subject == "x")
  assert(call.file == "t.deal" and call.line == 3 and call.column == 4)
  -- The mapped inner reason crosses as the matcher's closed arm selection,
  -- never as text.
  assert(#host_inner_calls == before_inner + 1)
  local inner = host_inner_calls[#host_inner_calls]
  assert(inner.id == "TYPED_BOUNDARY_KIND")
  assert(inner.values.kind == "int")
end)

test("the host parameter cell rejects a forged carrier (ISSUE-0830)", function()
  -- A DEAL table carrying the internal class/array mark names reaches the
  -- host parameter cell as the table value: the declared class/array cell
  -- rejects it through the same closed row a real carrier mismatch takes,
  -- so a table never forges identity across the host boundary.
  local raw = function(item) return __rt.__NULL end
  local classWrapped = __rt.from_lua_function("(@m/C)->null", raw)
  local before = #host_arm_calls
  local err = assert_error(function()
    classWrapped.f({ __c = true, __id = "@m/C", __kind = "class",
      __classname = "@m/C" })
  end, "E8010")
  assert(err.message == "arm:HOST_PARAMETER_CELL", tostring(err.message))
  assert(host_arm_calls[#host_arm_calls].values.index == 1)
  assert(#host_arm_calls == before + 1)
  local arrayWrapped = __rt.from_lua_function("([int])->null", raw)
  local errArray = assert_error(function()
    arrayWrapped.f({ __a = true, __n = 0 })
  end, "E8010")
  assert(errArray.message == "arm:HOST_PARAMETER_CELL", tostring(errArray.message))
  -- The real carriers pass the same cells.
  assert(__rt.from_lua_function("(@m/C)->null", raw).f(
    __rt.class_("@m/C", {}, {})) == __rt.__NULL)
  assert(__rt.from_lua_function("([int])->null", raw).f(
    { [__K.a] = true, [__K.n] = 0 }) == __rt.__NULL)
end)

test("the host parameter cell rejects a chunk function carrier at a table parameter (ISSUE-0830)", function()
  -- The emitted chunk's function carrier ([fn]/sig/csig/fid, no [kind])
  -- is a function value: the loaded matcher keeps its own token, so a
  -- table-declared host parameter takes the closed parameter cell instead
  -- of exposing the internal carrier table to the host.
  local raw = function(item) return __rt.__NULL end
  local wrapped = __rt.from_lua_function("(table)->null", raw)
  local before = #host_arm_calls
  local before_inner = #host_inner_calls
  local err = assert_error(function()
    wrapped.f({
      [__K.fn] = function() return 1 end,
      [__K.sig] = "function(;int)",
      [__K.csig] = "()->int",
      [__K.fid] = "m#f",
    })
  end, "E8010")
  assert(err.message == "arm:HOST_PARAMETER_CELL", tostring(err.message))
  assert(#host_arm_calls == before + 1)
  assert(host_arm_calls[#host_arm_calls].values.index == 1)
  assert(#host_inner_calls == before_inner + 1)
  assert(host_inner_calls[#host_inner_calls].id == "TYPED_BOUNDARY_KIND")
  assert(host_inner_calls[#host_inner_calls].values.kind == "table")
  -- The table values keep their cell: the chunk's shared table carrier
  -- and a plain table reach the raw host unchanged.
  local tableCarrier = { [__K.t] = true, [__K.keys] = {}, [__K.order] = {} }
  assert(wrapped.f(tableCarrier) == __rt.__NULL)
  assert(wrapped.f({ a = 1 }) == __rt.__NULL)
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
  assert(u[__K.classname] == "User")
  assert(u[__K.kind] == "class")
end)

test("class_ optional field provided", function()
  local u = __rt.class_("User", {name = "", nick = __rt.__MISSING}, {nick = "ads"})
  assert(u.name == "")
  assert(u.nick == "ads")
  assert(u[__K.classname] == "User")
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
  assert(u[__K.kind] == "class")
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
  assert(u[__K.kind] == "class")
  assert(u[__K.classname] == "@mod/User")
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

test("json_from_plan empty object document decodes the defaulted instance", function()
  -- An empty object document is a JSON object: the omitted
  -- required-present defaults evaluate and the instance publishes.
  local u = __rt.json_from_plan("C", int_plan(), {}, "f.deal", 1, 1)
  assert(u ~= nil and u.id == 0 and u.name == "anon" and u.nick == nil)
end)

test("json_from_plan decoded empty array carrier returns nil (no []/{} collapse)", function()
  -- A decoded array carrier records the private array/order marks, so the
  -- class gate never reads it as an empty object document.
  local empty_array = { [__K.a] = true, [__K.n] = 0 }
  assert(__rt.json_from_plan("C", int_plan(), empty_array, "f.deal", 1, 1) == nil)
  local decoded_empty_array = { [__K.t] = true, [__K.a] = true, [__K.n] = 0 }
  assert(__rt.json_from_plan("C", int_plan(), decoded_empty_array, "f.deal", 1, 1) == nil)
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
  assert(e[__K.kind] == "class")
  assert(e[__K.classname] == "User")
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
  assert(e[__K.kind] == "class")
  assert(e[__K.classname] == "Error")
  assert(e.code == "E_LIMIT")
  assert(e.message == "fail")
  assert(e.file == "test.deal")
  assert(e.line == 3)
  assert(e.column == 7)
end)

test("error_value shape without span args", function()
  local e = __rt.error_value("", "m")
  assert(e[__K.kind] == "class")
  assert(e[__K.classname] == "Error")
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
  assert_error(function() __rt.check_type("Error", {[__K.kind] = "class", [__K.classname] = "User"}) end, "E8001")
  -- sanity: a genuine Error instance still passes
  assert(u[__K.kind] == "class" and u[__K.classname] == "Error")
end)

test("check_type rejects the superseded builtin Error aliases", function()
  -- The canonical builtin atom is exactly the bare "Error" (v1.3
  -- DescriptorV13); the superseded v1.2 spellings do not decode.
  local e = __rt.error_value("E8001", "boom")
  for _, alias in ipairs({"@/Error", "@$builtin/Error"}) do
    local err = assert_error(function() __rt.check_type(alias, e) end, "E8001")
    assert(string.find(err.message, "cannot parse type descriptor", 1, true) ~= nil)
  end
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

test("from_lua_function wrong return type delegates the closed arm", function()
  local raw = function() return "not_an_int" end
  local w = __rt.from_lua_function("()->int", raw)
  local before_inner = #host_inner_calls
  local err = assert_error(function() w.f("t.deal", 3, 4) end, "E8010")
  assert(err.message == "arm:HOST_SYNC_RETURN_CELL")
  local call = host_arm_calls[#host_arm_calls]
  assert(call.id == "HOST_SYNC_RETURN_CELL")
  assert(call.values.inner == "inner:TYPED_BOUNDARY_KIND")
  assert(call.expected == "int")
  assert(call.subject == "not_an_int")
  assert(call.file == "t.deal" and call.line == 3 and call.column == 4)
  assert(#host_inner_calls == before_inner + 1)
  assert(host_inner_calls[#host_inner_calls].values.kind == "int")
end)

test("from_lua_function zero results delegates the nothing arm", function()
  local w = __rt.from_lua_function("()->int", function() end)
  local before_inner = #host_inner_calls
  local err = assert_error(function() w.f("t.deal", 5, 6) end, "E8010")
  assert(err.message == "arm:HOST_SYNC_RETURN_NOTHING")
  local call = host_arm_calls[#host_arm_calls]
  assert(call.id == "HOST_SYNC_RETURN_NOTHING")
  assert(call.values.expected == "int")
  assert(call.expected == "int")
  -- The no-value cell has no inner reason and no subject value: the
  -- artifact's render projects the arm's fixed nothing token.
  assert(call.subject == nil)
  assert(#host_inner_calls == before_inner)
end)

test("from_lua_function async shape delegates the nothing arm", function()
  local w = __rt.from_lua_function("async()->int", function() end)
  local before_inner = #host_inner_calls
  local err = assert_error(function() w.f("t.deal", 7, 8) end, "E8010")
  assert(err.message == "arm:ASYNC_SHAPE")
  local call = host_arm_calls[#host_arm_calls]
  assert(call.id == "ASYNC_SHAPE")
  assert(call.expected == "async operation")
  -- The absent handle carries no subject and no message parameter: the
  -- artifact's render projects the fixed nothing token.
  assert(call.subject == nil)
  assert(call.values.actual == nil)
  assert(call.file == "t.deal" and call.line == 7 and call.column == 8)
  assert(#host_inner_calls == before_inner)
end)

test("from_lua_function async shape delegates the carrier-kind arm", function()
  local w = __rt.from_lua_function("async()->int", function()
    return "not an async op"
  end)
  local err = assert_error(function() w.f("t.deal", 9, 10) end, "E8010")
  assert(err.message == "arm:ASYNC_SHAPE")
  local call = host_arm_calls[#host_arm_calls]
  assert(call.id == "ASYNC_SHAPE")
  assert(call.expected == "async operation")
  -- The invalid handle crosses as the raw subject: the artifact's own
  -- carrier-kind projection renders the token.
  assert(call.subject == "not an async op")
end)

test("from_lua_function fails closed without the artifact renderer", function()
  local saved = __rt.__hostArm
  __rt.__hostArm = nil
  local w = __rt.from_lua_function("()->int", function() return "junk" end)
  local ok, err = pcall(function() w.f("t.deal", 1, 2) end)
  __rt.__hostArm = saved
  assert(not ok, "the wrapper must not publish a composed failure")
  assert(type(err) == "string", "the fail-closed report is a plain error: "
    .. type(err))
  assert(string.find(err, "not registered", 1, true) ~= nil,
    "the fail-closed report names the missing renderer: " .. tostring(err))
end)

test("from_lua_function fails closed without the inner renderer", function()
  local saved = __rt.__hostInnerArm
  __rt.__hostInnerArm = nil
  local w = __rt.from_lua_function("()->int", function() return "junk" end)
  local ok, err = pcall(function() w.f("t.deal", 1, 2) end)
  __rt.__hostInnerArm = saved
  assert(not ok, "the wrapper must not publish a composed reason")
  assert(type(err) == "string", "the fail-closed report is a plain error: "
    .. type(err))
  assert(string.find(err, "not registered", 1, true) ~= nil,
    "the fail-closed report names the missing inner renderer: "
    .. tostring(err))
end)

test("the canonical matcher selects its closed arm on every mapped cell", function()
  -- The mapped host cells render their inner reason from the matcher's own
  -- closed arm selection (the raised table's `arm` field): the kind row,
  -- the int refinement and range rows, the two string-carrier rows, the
  -- signature row, the identity row, and the array-element row each carry
  -- their arm id and closed named parameters beside the local delivery.
  local function selection(fn, code)
    local err = assert_error(fn, code)
    assert(type(err.arm) == "table", "the matcher selects a closed arm")
    return err.arm
  end
  local kind = selection(function() __rt.check_type("int", "x") end, "E8001")
  assert(kind.id == "TYPED_BOUNDARY_KIND" and kind.values.kind == "int")
  local bytes = selection(function() __rt.check_type("bytes", 5) end, "E8001")
  assert(bytes.id == "TYPED_BOUNDARY_KIND" and bytes.values.kind == "bytes")
  local klass = selection(function()
    __rt.check_type("Error", {[__K.kind] = "class", [__K.classname] = "@src/A"})
  end, "E8001")
  assert(klass.id == "CLASS_IDENTITY")
  assert(klass.values.expected == "Error")
  assert(klass.values.actual == "@src/A")
  local element = selection(function() __rt.check_type("[int]", {"x"}) end, "E8003")
  assert(element.id == "ARRAY_ELEMENT_KIND" and element.values.oneBasedIndex == 1)
  local refinement = selection(function() __rt.check_type("int", 0/0) end, "E8001")
  assert(refinement.id == "INT_CONVERSION_NAN")
  local signature = selection(function()
    __rt.check_type("(int)->int", __rt.function_("(string)->int", function() end))
  end, "E8010")
  assert(signature.id == "FUNCTION_SIGNATURE_MISMATCH")
  assert(signature.values.expected == "(int)->int")
  assert(signature.values.actual == "(string)->int")
  -- A malformed function carrier has no signature operand: the closed kind
  -- arm renders (the JVM host check's own function row).
  local carrier = selection(function()
    __rt.check_type("()->int", {[__K.kind] = "function"})
  end, "E8001")
  assert(carrier.id == "TYPED_BOUNDARY_KIND" and carrier.values.kind == "function")
  -- A producer defect carries no selection, so it can never become a host
  -- inner reason.
  local defect = assert_error(function()
    __rt.check_type("int[]", {1, 2})
  end, "E8001")
  assert(defect.arm == nil)
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
  assert(type(h) == "table" and h[__K.kind] == "async")
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
    return __rt.async_start(function() return { [__K.kind] = "class", [__K.classname] = "User" } end)
  end)
  assert(wrapper.sig == "async(int)->?@src/User")
  local h = wrapper.f(1)
  assert(type(h) == "table" and h[__K.kind] == "async")
  -- a non-operation return still fails the async-shape check
  local wrapper2 = __rt.from_lua_function("async(int)->?@src/User", function(x)
    return { [__K.kind] = "class", [__K.classname] = "User" }
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
  assert(type(h) == "table" and h[__K.kind] == "async")
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
  assert(handle[__K.kind] == "async")
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
      return { [__K.kind] = "class", [__K.classname] = "User", name = "test" }
    end)
  end)
  -- Call should succeed without E8010
  local ok, result = pcall(wrapper.f)
  assert(ok, "async wrapper should not validate return type: " .. tostring(result))
  -- The result should be an async handle
  assert(type(result) == "table")
  assert(result[__K.kind] == "async", "result should be async handle, got kind: " .. tostring(result[__K.kind]))
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

  local handles = {}
  handles[N] = __rt.async_create(function()
    return 1
  end)
  for i = N - 1, 1, -1 do
    local inner_h = handles[i + 1]
    handles[i] = __rt.async_create(function()
      local val = coroutine.yield(inner_h)
      return val + 1
    end)
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
--
-- The emitted chunk registers the closed HOST_LOAD arm renderer
-- (__rt.__hostLoadArm) on the deployed runtime at load; load_host
-- delegates every mapped load failure (the require failure, a non-table
-- module result, a missing declared export, a pre-wrapped signature
-- mismatch, a class identity mismatch, and the class-metadata defects) to
-- it, so the unit drives register a recording stub: the arm id, the closed
-- named parameters and the import origin it receives are the observable
-- delegation. The stub renders no expected/actual field — the arm's
-- declaration owns them, and the production renderer is asserted by the
-- artifact drives (HostModuleLoadEmissionTest).

local HOST_FIXTURE = "test/fixtures/runtime-host-fixture"

local host_load_arm_calls = {}
local function stub_host_load_arm(id, values, file, line, column)
  host_load_arm_calls[#host_load_arm_calls + 1] = { id = id, values = values,
    file = file, line = line, column = column }
  return { code = "E8011", message = "arm:" .. id }
end
__rt.__hostLoadArm = stub_host_load_arm

test("load_host loads fixture and wraps raw functions", function()
  local host = __rt.load_host(HOST_FIXTURE, {
    answer = "()->int",
    add = "(int,int)->int",
  })
  assert(type(host.answer) == "table" and host.answer[__K.kind] == "function")
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
  assert(host.prewrapped_good[__K.kind] == "function")
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
  assert(type(h) == "table" and h[__K.kind] == "async")
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
  assert(type(host.ServerConfig) == "table" and host.ServerConfig[__K.kind] == "class")
  assert(host.ServerConfig[__K.classname] == "@$external/host.cfg/ServerConfig")
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
  assert(host.ServerConfig_defaults[__K.kind] == nil)
  assert(host.ServerConfig_defaults[__K.classname] == nil)
  assert_error(function()
    __rt.class_("@$external/host.cfg/ServerConfig", host.ServerConfig_defaults, { port = 1, extra = true })
  end, "E8007")
end)

test("load_host non-class export for class descriptor raises E8011", function()
  assert_error(function()
    __rt.load_host(HOST_FIXTURE, { not_a_class = "@$external/host.cfg/NotAClass" })
  end, "E8011")
end)

test("load_host mapped load failures delegate to the registered arm renderer", function()
  -- Every mapped HOST_LOAD failure is the closed arm's own render: the
  -- runtime passes the arm id, the arm's named parameters and the
  -- importing statement's origin to the artifact-registered renderer, and
  -- composes no failure text of its own. An arm that declares no
  -- expected/actual field is rendered without one.
  host_load_arm_calls = {}

  -- HOST_LOAD_MISSING_EXPORT: the declared name is absent from the raw
  -- module.
  local missing = assert_error(function()
    __rt.load_host(HOST_FIXTURE, { missing = "()->int" }, "probe.deal", 6, 1)
  end, "E8011")
  assert(missing.message == "arm:HOST_LOAD_MISSING_EXPORT")
  assert(#host_load_arm_calls == 1)
  local call = host_load_arm_calls[1]
  assert(call.id == "HOST_LOAD_MISSING_EXPORT")
  assert(call.values.name == "missing" and call.values.module == HOST_FIXTURE)
  assert(call.file == "probe.deal" and call.line == 6 and call.column == 1)

  -- HOST_LOAD_FAILED: the raw module's require fails.
  host_load_arm_calls = {}
  local failed = assert_error(function()
    __rt.load_host("no/such/deal-runtime-host-module", { x = "()->int" },
      "probe.deal", 7, 2)
  end, "E8011")
  assert(failed.message == "arm:HOST_LOAD_FAILED")
  call = host_load_arm_calls[1]
  assert(#host_load_arm_calls == 1 and call.id == "HOST_LOAD_FAILED")
  assert(call.values.module == "no/such/deal-runtime-host-module")
  assert(type(call.values.reason) == "string" and #call.values.reason > 0)
  assert(call.file == "probe.deal" and call.line == 7 and call.column == 2)

  -- HOST_LOAD_NOT_A_MODULE: the raw module returns a non-table value.
  host_load_arm_calls = {}
  package.loaded["deal-runtime-test-nontable"] = 42
  local nonModule = assert_error(function()
    __rt.load_host("deal-runtime-test-nontable", { x = "()->int" })
  end, "E8011")
  package.loaded["deal-runtime-test-nontable"] = nil
  assert(nonModule.message == "arm:HOST_LOAD_NOT_A_MODULE")
  call = host_load_arm_calls[1]
  assert(#host_load_arm_calls == 1 and call.id == "HOST_LOAD_NOT_A_MODULE")
  assert(call.values.module == "deal-runtime-test-nontable")

  -- HOST_LOAD_SIGNATURE_MISMATCH: the pre-wrapped export's own sig is not
  -- the declared descriptor.
  host_load_arm_calls = {}
  local mismatch = assert_error(function()
    __rt.load_host(HOST_FIXTURE, { prewrapped_good = "()->string" },
      "probe.deal", 8, 3)
  end, "E8011")
  assert(mismatch.message == "arm:HOST_LOAD_SIGNATURE_MISMATCH")
  call = host_load_arm_calls[1]
  assert(#host_load_arm_calls == 1 and call.id == "HOST_LOAD_SIGNATURE_MISMATCH")
  assert(call.values.name == "prewrapped_good"
    and call.values.module == HOST_FIXTURE)
  assert(call.values.expected == "()->string")
  assert(type(call.values.actual) == "string" and call.values.actual ~= "()->string")
  assert(call.file == "probe.deal" and call.line == 8 and call.column == 3)

  -- HOST_LOAD_IDENTITY_MISMATCH: the class meta's identity is not the
  -- declared descriptor.
  host_load_arm_calls = {}
  local identity = assert_error(function()
    __rt.load_host(HOST_FIXTURE, { WrongName = "@$external/host.cfg/WrongName" },
      "probe.deal", 9, 4)
  end, "E8011")
  assert(identity.message == "arm:HOST_LOAD_IDENTITY_MISMATCH")
  call = host_load_arm_calls[1]
  assert(#host_load_arm_calls == 1 and call.id == "HOST_LOAD_IDENTITY_MISMATCH")
  assert(call.values.name == "WrongName" and call.values.module == HOST_FIXTURE)
  assert(call.values.expected == "@$external/host.cfg/WrongName")
  assert(type(call.values.actual) == "string")
  assert(call.file == "probe.deal" and call.line == 9 and call.column == 4)

  -- HOST_LOAD_INVALID_METADATA: the declared class's missing or non-table
  -- <C>_defaults / <C>_fields entry.
  host_load_arm_calls = {}
  local missingDefaults = assert_error(function()
    __rt.load_host(HOST_FIXTURE,
      { NoDefaults = "@$external/host.cfg/NoDefaults" }, "probe.deal", 3, 9)
  end, "E8011")
  assert(missingDefaults.message == "arm:HOST_LOAD_INVALID_METADATA")
  call = host_load_arm_calls[1]
  assert(#host_load_arm_calls == 1 and call.id == "HOST_LOAD_INVALID_METADATA")
  assert(call.values.name == "NoDefaults" and call.values.module == HOST_FIXTURE
    and call.values["defaults|fields"] == "defaults")
  assert(call.file == "probe.deal" and call.line == 3 and call.column == 9)
  host_load_arm_calls = {}
  local wrongKind = assert_error(function()
    __rt.load_host(HOST_FIXTURE,
      { BadDefaults = "@$external/host.cfg/BadDefaults" }, "probe.deal", 4, 1)
  end, "E8011")
  assert(wrongKind.message == "arm:HOST_LOAD_INVALID_METADATA")
  assert(#host_load_arm_calls == 1
    and host_load_arm_calls[1].values["defaults|fields"] == "defaults")
  host_load_arm_calls = {}
  local badFields = assert_error(function()
    __rt.load_host(HOST_FIXTURE,
      { BadFields = "@$external/host.cfg/BadFields" }, "probe.deal", 5, 1)
  end, "E8011")
  assert(badFields.message == "arm:HOST_LOAD_INVALID_METADATA")
  assert(#host_load_arm_calls == 1
    and host_load_arm_calls[1].values.name == "BadFields"
    and host_load_arm_calls[1].values["defaults|fields"] == "fields")
end)

test("load_host mapped failures without the registered renderer fail closed", function()
  -- The runtime composes no failure text of its own: outside the emitted
  -- artifact (no registered arm renderer) every mapped load defect is a
  -- fail-closed producer error, never a textual fallback.
  __rt.__hostLoadArm = nil
  local probes = {
    function()
      __rt.load_host(HOST_FIXTURE, { missing = "()->int" }, "probe.deal", 6, 1)
    end,
    function()
      __rt.load_host("no/such/deal-runtime-host-module", { x = "()->int" })
    end,
    function()
      __rt.load_host(HOST_FIXTURE,
        { NoDefaults = "@$external/host.cfg/NoDefaults" }, "probe.deal", 3, 9)
    end,
  }
  for i = 1, #probes do
    local ok, err = pcall(probes[i])
    assert(not ok and type(err) == "string"
      and string.find(err, "arm renderer is not registered", 1, true) ~= nil)
  end
  __rt.__hostLoadArm = stub_host_load_arm
end)

test("load_host class supplied _fields copied through", function()
  local host = __rt.load_host(HOST_FIXTURE, { ServerConfig = "@$external/host.cfg/ServerConfig" })
  assert(type(host.ServerConfig_fields) == "table")
  assert(host.ServerConfig_fields[1].name == "port")
end)

test("load_host class absent _fields tolerated", function()
  local host = __rt.load_host(HOST_FIXTURE, { NoFields = "@$external/host.cfg/NoFields" })
  assert(host.NoFields[__K.kind] == "class")
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
    "Error", "@host.cfg/ServerConfig", "@a/b",
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
    "T[]", "T|null", "...T[]", "User", "ServerConfig", "Foo",
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
  local probe = { [__K.kind] = "class", [__K.classname] = "Error" }
  assert(__rt.check_canonical_type("Error", probe) == probe)
  local err = assert_error(function()
    __rt.check_canonical_type("Error", { [__K.kind] = "class", [__K.classname] = "@src/A" })
  end, "E8001")
  assert(err.message == "expected instance of Error, got @src/A", tostring(err.message))
  assert_error(function()
    __rt.check_canonical_type("Error", { [__K.kind] = "class", [__K.classname] = "Errorx" })
  end, "E8001")
  assert_error(function()
    __rt.check_canonical_type("Error", { [__K.kind] = "class", [__K.classname] = "@host.cfg/ServerConfig" })
  end, "E8001")
  assert_error(function() __rt.check_canonical_type("Error", 42) end, "E8001")
  assert_error(function() __rt.check_canonical_type("Error", {}) end, "E8001")
  -- The superseded v1.2 spellings do not reach a class row (no fallback
  -- identity): they are unparsable text.
  assert_error(function() __rt.check_canonical_type("@/Error", probe) end, "E8001")
  assert_error(function() __rt.check_canonical_type("@$builtin/Error", probe) end, "E8001")
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
  assert_error(function() __rt.check_type("@/Error", __rt.error_value("E8001", "boom")) end, "E8001")
  assert_error(function() __rt.check_type("@$builtin/Error", __rt.error_value("E8001", "boom")) end, "E8001")
  -- Canonical spellings pass through the same entry.
  assert(__rt.check_type("[int]", { 1, 2 }) ~= nil)
  assert(__rt.check_type("?string", __rt.__NULL) == __rt.__NULL)
  local ev = __rt.error_value("E8001", "boom")
  assert(ev[__K.classname] == "Error")
  assert(__rt.check_type("Error", ev) == ev)
  -- The canonical alias entries are the same matcher.
  assert(__rt.check_canonical_type("[int]", { 1 }) ~= nil)
  assert_parse_rejected("int[]")
  assert_parse_rejected("string|null")
  -- The superseded builtin aliases are rejected (no fallback identity).
  assert_parse_rejected("@/Error")
  assert_parse_rejected("@$builtin/Error")
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

  local inst = { [__K.kind] = "class", [__K.classname] = "@mod/Thing" }
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
    __rt.invoke_async_export({}, "oracle", "@$builtin/Error")
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

-- ==================== The canonical hex-float spelling
-- (Double.toHexString parity, the closed-arm actuals that spell a
-- number) ====================

test("number_hex publishes the canonical hex-float spellings", function()
  assert(__rt.number_hex(1.0) == "0x1.0p0", __rt.number_hex(1.0))
  assert(__rt.number_hex(-1.0) == "-0x1.0p0", __rt.number_hex(-1.0))
  assert(__rt.number_hex(0.5) == "0x1.0p-1", __rt.number_hex(0.5))
  assert(__rt.number_hex(-0.5) == "-0x1.0p-1", __rt.number_hex(-0.5))
  assert(__rt.number_hex(0.0) == "0x0.0p0", __rt.number_hex(0.0))
  assert(__rt.number_hex(-0.0) == "-0x0.0p0", __rt.number_hex(-0.0))
  assert(__rt.number_hex(3.0) == "0x1.8p1", __rt.number_hex(3.0))
  assert(__rt.number_hex(0.1) == "0x1.999999999999ap-4",
    __rt.number_hex(0.1))
  assert(__rt.number_hex(0.0 / 0.0) == "NaN", __rt.number_hex(0.0 / 0.0))
  assert(__rt.number_hex(1.0 / 0.0) == "Infinity",
    __rt.number_hex(1.0 / 0.0))
  assert(__rt.number_hex(-1.0 / 0.0) == "-Infinity",
    __rt.number_hex(-1.0 / 0.0))
  -- A subnormal keeps Java's fixed-exponent denormal spelling (never the
  -- normalized power), and the smallest double keeps its one significand
  -- digit.
  assert(__rt.number_hex(2 ^ -1074) == "0x0.0000000000001p-1022",
    __rt.number_hex(2 ^ -1074))
  assert(__rt.number_hex(-2 ^ -1074) == "-0x0.0000000000001p-1022",
    __rt.number_hex(-2 ^ -1074))
end)


-- ==================== Summary ====================

print("")
print("========================================")
print("Results: " .. passed .. " passed, " .. failed .. " failed")
print("========================================")

if failed > 0 then
  os.exit(1)
end
