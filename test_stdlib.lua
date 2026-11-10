-- Test suite for DEAL Standard Library modules
-- Run with: luajit test_stdlib.lua

-- Adjust package.path to find deal/runtime.lua and std/ modules
package.path = "./?.lua;./?/init.lua;" .. package.path

-- The private carrier keys (ISSUE-0830): the runtime, the emitted chunks and
-- the stdlib share one process-wide key set.
local __rt = require("deal.runtime")
local __K = __rt.__keys
local jsonAlgorithms = require("deal.json")
local function decodeRecord(text, file, line, column)
  return jsonAlgorithms.decode(text, "record", {}, file, line, column)
end
local function decodeUnknown(text, file, line, column)
  return jsonAlgorithms.decode(text, "unknown", {}, file, line, column)
end
local encode = jsonAlgorithms.encode

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
  assert(console.log[__K.kind] == "function")
end)

test("console.error is a function wrapper", function()
  assert(type(console.error) == "table")
  assert(console.error[__K.kind] == "function")
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

test("string.length validates output as number", function()
  local r = strings.length.f("test")
  assert(type(r) == "number")
  assert(r % 1 == 0, "output should be an integer")
  assert(r >= 0, "output should be non-negative")
end)

test("string.length with non-string raises error", function()
  local err = assert_error(function() strings.length.f(42) end)
  assert(type(err) == "table", "error should be a table")
end)

test("string.length publishes the shared typed-boundary token for a carrier", function()
  -- The shipped stdlib module's declared parameter cell is the runtime
  -- matcher: a class instance and the builtin Error project their carried
  -- canonical class atom as the actual kind — the same closed token the JS
  -- stdlib's $rt.checkString ($kindOf) and the compiled artifact lanes
  -- publish — never the raw Lua table spelling of the carrier.
  local rt = require("deal.runtime")
  local instance = rt.class_plan_("@m/C", {{name="a",descriptor="number",optional=false}}, {a=1})
  local err_value = rt.error_value("E_USER", "boom")
  local err = assert_error_code(function() strings.length.f(instance) end, "E8001")
  assert(err.message == "expected string", tostring(err.message))
  assert(err.expected == "string", tostring(err.expected))
  assert(err.actual == "@m/C", tostring(err.actual))
  err = assert_error_code(function() strings.length.f(err_value) end, "E8001")
  assert(err.message == "expected string", tostring(err.message))
  assert(err.expected == "string", tostring(err.expected))
  assert(err.actual == "Error", tostring(err.actual))
  -- Language null keeps its own closed token, not the table spelling.
  err = assert_error_code(function() strings.length.f(rt.__NULL) end, "E8001")
  assert(err.actual == "null", tostring(err.actual))
  -- The same class atom crosses a second shipped check (substring's own
  -- parameter row) and keeps the fixed expected token.
  err = assert_error_code(function() strings.substring.f(instance, 0, 1) end, "E8001")
  assert(err.expected == "string" and err.actual == "@m/C",
    tostring(err.expected) .. " / " .. tostring(err.actual))
  -- An number parameter row publishes the class atom too (the number kind arm).
  err = assert_error_code(function() strings.substring.f("ab", instance, 1) end, "E8001")
  assert(err.message == "expected number", tostring(err.message))
  assert(err.expected == "number" and err.actual == "@m/C",
    tostring(err.expected) .. " / " .. tostring(err.actual))
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

test("string.substring with start > end swaps bounds", function()
  assert(strings.substring.f("hello", 3, 1) == "el")
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

test("string.substring with non-number raises error", function()
  local err = assert_error(function() strings.substring.f("hi", "x", 1) end)
  assert(type(err) == "table", "error should be a table")
end)

-- ----- Unicode scalar-value length / substring (v1.2) -----

test("string.length counts UTF-16 units, not bytes", function()
  -- a, U+00E9 (2 bytes), U+4E2D (3 bytes), U+1F600 (4 bytes), b: 5 scalars, 11 bytes
  local s = "a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b"
  assert(#s == 11)
  assert(strings.length.f(s) == 6)
end)

test("string.substring positions are UTF-16 units", function()
  local rt = require("deal.runtime")
  local s = "a\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80b"
  assert(strings.substring.f(s, 0, 6) == rt.string_normalize(s))
  assert(strings.substring.f(s, 0, 1) == "a")
  assert(strings.substring.f(s, 1, 2) == "\xC3\xA9")
  assert(strings.substring.f(s, 2, 3) == "\xE4\xB8\xAD")
  assert(strings.substring.f(s, 3, 4) == "\xED\xA0\xBD")
  assert(strings.substring.f(s, 4, 5) == "\xED\xB8\x80")
  assert(strings.substring.f(s, 5, 6) == "b")
  assert(rt.string_transport(strings.substring.f(s, 1, 5)) == "\xC3\xA9\xE4\xB8\xAD\xF0\x9F\x98\x80")
  assert(strings.substring.f(s, 2, 2) == "")
  assert(strings.substring.f(s, 6, 10) == "")
end)

test("string.substring clamps out-of-range end at the scalar boundary", function()
  local s = "\xC3\xA9\xE4\xB8\xAD"
  assert(strings.substring.f(s, 0, 99) == s)
  assert(strings.substring.f(s, 1, 99) == "\xE4\xB8\xAD")
end)

test("string.split with empty separator splits UTF-16 units", function()
  local parts = strings.split.f("a\xC3\xA9\xF0\x9F\x98\x80", "")
  assert(#parts == 4)
  assert(parts[1] == "a" and parts[2] == "\xC3\xA9")
  assert(parts[3] == "\xED\xA0\xBD" and parts[4] == "\xED\xB8\x80")
end)

test("string.length rejects malformed UTF-8 with E8001 (v1.2 boundary rule)", function()
  assert_error(function() strings.length.f("\xC3") end, "E8001")
  assert(strings.substring.f("\xED\xA0\x80", 0, 1) == "\xED\xA0\x80")
  assert_error_code(function() strings.substring.f("\xE0\x80\x80", 0, 1) end, "E8001")
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

test("string.replace replaces the first occurrence", function()
  assert(strings.replace.f("hello world", "world", "earth") == "hello earth")
  assert(strings.replace.f("aaa", "a", "b") == "baa")
  assert(strings.replace.f("ababab", "ab", "x") == "xabab")
end)

test("string.replace with no match returns original", function()
  assert(strings.replace.f("hello", "xyz", "abc") == "hello")
end)

test("string.replace with empty search prepends", function()
  assert(strings.replace.f("hello", "", "x") == "xhello")
end)

test("string.replace is plain-text (no pattern matching)", function()
  -- Lua magic characters in 'from' should be treated literally
  assert(strings.replace.f("a.b.c", ".", "-") == "a-b.c")
  assert(strings.replace.f("100%", "%", "pct") == "100pct")
  assert(strings.replace.f("a*b*c", "*", "+") == "a+b*c")
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

test("string.split empty text with nonempty separator preserves one part", function()
  local r = strings.split.f("", ",")
  assert(#r == 1 and r[1] == "")
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

test("table.keys rejects non-string Record keys", function()
  local t = { [1] = "a", [2] = "b", keep = "c" }
  assert_error_code(function() tablelib.keys.f(t) end, "E8001")
  t[1], t[2] = nil, nil
  local keys = tablelib.keys.f(t)
  assert(#keys == 1 and keys[1] == "keep")
end)

test("table.keys preserves the deterministic iteration order of the current state", function()
  -- luajit-v1.2-stdlib-contracts D4: pairs order for the fixed
  -- construction/deletion history below is zero,one,two in LuaJIT; no
  -- shadow order channel exists, so the pin is the table's own state.
  local t = { zero = 0, one = 1, two = 2, filler = 3 }
  t.filler = nil
  local keys = tablelib.keys.f(t)
  assert(#keys == 3, "expected three survivors, got " .. #keys)
  assert(keys[1] == "zero" and keys[2] == "one" and keys[3] == "two",
    "order mismatch: " .. table.concat(keys, ","))
end)

test("table.keys returns a chunk table carrier's present-null key", function()
  -- std/table keys: each present string key once,
  -- null-valued keys included. The chunk table carrier records a present
  -- null as an ordered presence key whose slot stores no value, so the
  -- key list is the carrier's recorded order, never a walk of the stored
  -- values.
  local carrier = {[__K.t] = true, [__K.keys] = { a = true, b = true, c = true },
    [__K.order] = { "a", "b", "c" }, a = 1, c = 3}
  local keys = tablelib.keys.f(carrier)
  assert(#keys == 3, "expected the present-null key too, got " .. #keys)
  assert(keys[1] == "a" and keys[2] == "b" and keys[3] == "c",
    "order mismatch: " .. table.concat(keys, ","))
  -- The __NULL-sentinel member of the same carrier is a present key too.
  local __rt = require("deal.runtime")
  local sentinel = {[__K.t] = true, [__K.keys] = { n = true, x = true },
    [__K.order] = { "n", "x" }, n = __rt.__NULL, x = 1}
  local sentinelKeys = tablelib.keys.f(sentinel)
  assert(#sentinelKeys == 2 and sentinelKeys[1] == "n" and sentinelKeys[2] == "x",
    "the sentinel member is a present key: " .. table.concat(sentinelKeys, ","))
end)

test("table.keys keeps a decoded object carrier's present-null member (v1.4)", function()
  -- A reconstructed Record stores a present null as the runtime's null
  -- sentinel, so the member is a present key of the table (the
  -- specification pins no order for keys).
  local parsed = decodeRecord('{"n":null,"x":1}')
  local keys = tablelib.keys.f(parsed)
  assert(#keys == 2, "expected two keys, got " .. #keys)
  local found = {}
  for _, k in ipairs(keys) do found[k] = true end
  assert(found.n and found.x, "the present-null member is a key: "
    .. table.concat(keys, ","))
end)

test("table.keys on empty table returns empty array", function()
  local keys = tablelib.keys.f({})
  assert(#keys == 0)
end)

test("table.keys with non-table raises error", function()
  local err = assert_error(function() tablelib.keys.f("not a table") end)
  assert(type(err) == "table", "error should be a table")
end)

test("table.keys rejects the emitted chunk's function carrier (ISSUE-0830)", function()
  -- The emitted chunk's closure/adapter carrier carries the private
  -- [fn]/sig/csig/fid marks and no [kind] mark; it is a function value,
  -- never the table a keys walk could read.
  local __rt = require("deal.runtime")
  local chunkFn = {
    [__rt.__keys.fn] = function() return 1 end,
    [__rt.__keys.sig] = "function(;number)",
    [__rt.__keys.csig] = "()->number",
    [__rt.__keys.fid] = "m#f",
  }
  assert(__rt.typed_kind(chunkFn) == "function",
    "the chunk function carrier projects the function token")
  local err = assert_error(function() tablelib.keys.f(chunkFn) end)
  assert(type(err) == "table", "error should be a table")
  assert(err.code == "E8001", tostring(err.code))
  assert(err.message == "expected table", tostring(err.message))
  assert(err.expected == "table", tostring(err.expected))
  assert(err.actual == "function", tostring(err.actual))
end)

-- ===========================================================================
-- Builtin JSON algorithms (not callable stdlib exports)
-- ===========================================================================

test("builtin JSON algorithms are not language callable wrappers and accept any root", function()
  assert(type(jsonAlgorithms.scan)=="function" and type(encode)=="function"
    and type(jsonAlgorithms.decode)=="function")
  assert(jsonAlgorithms.parse==nil and jsonAlgorithms.stringify==nil)
  assert(decodeUnknown('null')==__rt.__NULL and decodeUnknown('true')==true
    and decodeUnknown('false')==false and decodeUnknown('"x"')=='x'
    and decodeUnknown('7')==7 and 1/decodeUnknown('-0')==-math.huge)
  local a=decodeUnknown('[1,null]')
  assert(a[__K.a] and a[__K.n]==2 and a[__K.elementDescriptor]=='unknown'
    and a[1]==1 and a[2]==__rt.__NULL)
end)

test("contextual declared schemas preserve freshness, recursion, presence and declaration order", function()
  local plans={['@m/Node']={
    {name='value',descriptor='number',optional=false},
    {name='next',descriptor='?@m/Node',optional=true},
    {name='label',descriptor='?string',optional=false}}}
  local text='{"label":null,"next":{"label":"leaf","value":2},"value":1}'
  local a=jsonAlgorithms.decode(text,'@m/Node',plans)
  local b=jsonAlgorithms.decode(text,'@m/Node',plans)
  assert(a~=b and a.next~=b.next and a[__K.classname]=='@m/Node'
    and a.next[__K.classname]=='@m/Node' and a.next.next==nil)
  assert(encode(a)=='{"value":1,"next":{"value":2,"label":"leaf"},"label":null}')
  assert(__rt.check_type('@m/Node',a)==a)
  local err=assert_error_code(function() __rt.check_type('@other/Node',a) end,'E8001')
  assert(err.actual=='@m/Node')
end)

test("contextual failures scan first, check extras first, and never replace destination aliases", function()
  local plans={['@m/Box']={
    {name='value',descriptor='number',optional=false},
    {name='child',descriptor='?@m/Box',optional=true}}}
  local prior=jsonAlgorithms.decode('{"value":7,"child":{"value":8}}','@m/Box',plans)
  local destination,alias=prior,prior.child
  local cases={
    {'{"extra":0,"value":"bad"}','E8007',nil},
    {'{"extra":0,"value":{"x":1,"x":2}}','E8001','duplicate key'},
    {'{"value":1,"child":{"value":"bad"}}','E8001','expected number'},
    {'{}','E8001','expected number'}}
  for _,case in ipairs(cases) do
    local e=assert_error_code(function()
      destination=jsonAlgorithms.decode(case[1],'@m/Box',plans,'parse.deal',7,13)
    end,case[2])
    assert(e.file=='parse.deal' and e.line==7 and e.column==13)
    if case[3] then assert(string.find(e.message,case[3],1,true)) end
    assert(destination==prior and destination.child==alias and prior.value==7 and alias.value==8)
  end
  local missing=assert_error_code(function()
    jsonAlgorithms.decode('{}','@m/Box',plans)
  end,'E8001')
  assert(missing.actual=='undefined')
end)

test("contextual extra keys are lossless opaque arm parameters", function()
  local plans={['@m/Box']={{name='value',descriptor='number',optional=false}}}
  local keys={
    {'{actual}','{actual}'},
    {'x\\u0000y','x'..string.char(0)..'y'},
    {'\\uD800',string.char(0xed,0xa0,0x80)},
    {"a'b","a'b"},
    {'',''}}
  for _,key in ipairs(keys) do
    local e=assert_error_code(function()
      jsonAlgorithms.decode('{"'..key[1]..'":1}','@m/Box',plans,'keys.deal',9,17)
    end,'E8007')
    assert(e.message=="extra field '"..key[2].."' in class '@m/Box'")
    assert(e.arm.id=='CLASS_EXTRA_FIELD' and e.arm.values.field==key[2]
      and e.arm.values.classId=='@m/Box' and e.expected==nil and e.actual==nil)
    assert(e.file=='keys.deal' and e.line==9 and e.column==17)
  end
end)

test("contextual schemas reject omitted function and void descendants without hooks", function()
  for _,bad in ipairs({'()->number','void'}) do
    local plans={['@m/Bad']={{name='unused',descriptor=bad,optional=true}}}
    assert_error(function() jsonAlgorithms.decode('{}','@m/Bad',plans) end)
  end
  local fields={{name='value',descriptor='number',optional=false}}
  local a=jsonAlgorithms.decode('{"value":1}','@m/Item',{['@m/Item']=fields})
  local b=jsonAlgorithms.decode('{"value":"s"}','@m/Item',{
    ['@m/Item']={{name='value',descriptor='string',optional=false}}})
  assert(a.value==1 and b.value=='s')
  assert_error(function() jsonAlgorithms.decode('{}','@m/Missing',{}) end)
  for _,malformed in ipairs({
      {[2]=fields[1]},
      {fields[1],fields[1]},
      {{name='value',descriptor='number'}},
      {{name='value',descriptor='not-a-descriptor',optional=false}},
      {fields[1],unexpected=true}}) do
    assert_error(function() jsonAlgorithms.decode('{}','@m/Item',{['@m/Item']=malformed}) end)
  end
  local poisoned=setmetatable({}, {__index=function() error('schema hook invoked') end})
  local ok,e=pcall(jsonAlgorithms.decode,'{}','@m/Item',poisoned)
  assert(not ok and string.find(tostring(e),'no exact class plan',1,true))
  local optional=setmetatable({name='value',descriptor='number'},
    {__index=function() error('field hook invoked') end})
  ok,e=pcall(jsonAlgorithms.decode,'{}','@m/Item',{['@m/Item']={optional}})
  assert(not ok and string.find(tostring(e),'malformed class plan',1,true))
  local inert=setmetatable(fields,{__index=function() error('plan hook invoked') end})
  assert(jsonAlgorithms.decode('{"value":3}','@m/Item',{['@m/Item']=inert}).value==3)
end)

test("contextual typed arrays retain canonical descriptors, nullish flags and Error public fields", function()
  local a=jsonAlgorithms.decode('[null,1]','[?number]')
  assert(a[__K.elementDescriptor]=='?number' and a[1]==__rt.__NULL and a[2]==1)
  assert(__rt.check_type('[?number]',a)==a)
  local empty=jsonAlgorithms.decode('[]','[number]')
  assert(empty[__K.n]==0 and empty[__K.elementDescriptor]=='number')
  local err=jsonAlgorithms.decode('{"message":"m","code":"C"}','Error')
  assert(encode(err)=='{"code":"C","message":"m"}')
  assert_error_code(function() jsonAlgorithms.decode('[null]','[!number]') end,'E8001')
  assert_error_code(function() jsonAlgorithms.decode('{}','?number') end,'E8001')
end)

test("contextual deep reconstruction supports ordinary validation and encoding", function()
  local plans={['@m/Node']={{name='value',descriptor='number',optional=false},
    {name='next',descriptor='?@m/Node',optional=false}}}
  local text=string.rep('{"value":1,"next":',5000)..'null'..string.rep('}',5000)
  local node=jsonAlgorithms.decode(text,'?@m/Node',plans)
  assert(__rt.check_type('?@m/Node',node)==node)
  assert(encode(node)==text)
  local input=string.rep('[',20000)..'-0'..string.rep(']',20000)
  local nested=decodeUnknown(input)
  assert(__rt.check_type('unknown',nested)==nested)
  assert(encode(nested)==string.rep('[',20000)..'0'..string.rep(']',20000))
end)

test("json.stringify of simple object", function()
  local result = encode({ a = 1, b = "hi" })
  -- JSON object keys can be in any order; check that the result is valid JSON
  assert(type(result) == "string")
  assert(string.find(result, '"a"') ~= nil)
  assert(string.find(result, '"b"') ~= nil)
end)

test("json.stringify of array", function()
  local result = encode({ [__K.a]=true, [__K.n]=3, 1, 2, 3 })
  assert(result == '[1,2,3]')
end)

test("json.stringify of nested object", function()
  local result = encode({ name = "test", data = { x = 1 } })
  assert(type(result) == "string")
  assert(string.find(result, '"name"') ~= nil)
  assert(string.find(result, '"data"') ~= nil)
end)

test("json.stringify encodes __NULL as null", function()
  local __rt = require("deal.runtime")
  local result = encode({ a = __rt.__NULL, b = 1 })
  -- Should contain "null", not "{}"
  assert(string.find(result, "null") ~= nil, "should contain 'null' for __NULL value")
  assert(string.find(result, "{}") == nil, "should NOT contain '{}' for __NULL value")
end)

test("json.stringify boolean values", function()
  local result = encode({ a = true, b = false })
  assert(string.find(result, "true") ~= nil)
  assert(string.find(result, "false") ~= nil)
end)

-- Unknown ingress rejects foreign Record keys before encoding. Marked arrays
-- remain distinct from string-keyed Records; numeric keys are never coerced.
local function assert_table_form_stringify_error(fn, label, file, line, column)
  local err = assert_error_code(fn, "E8001")
  assert(err.message == "unsupported unknown ingress",
    label .. ": message " .. tostring(err.message))
  assert(err.expected == "unknown",
    label .. ": expected " .. tostring(err.expected))
  assert(err.actual == "table", label .. ": actual " .. tostring(err.actual))
  if file ~= nil then
    assert(err.file == file and err.line == line and err.column == column,
      label .. ": the tuple carries the call-site triplet, got "
        .. tostring(err.file) .. ":" .. tostring(err.line) .. ":" .. tostring(err.column))
  end
  return err
end

test("json.stringify rejects a mixed-key table instead of coercing member names", function()
  assert_table_form_stringify_error(function()
    encode({ [1] = "a", name = "test" })
  end, "numeric key beside a string key")
  -- The collision hazard: [1] and "1" are distinct Lua keys but one JSON
  -- member name, so the coercion would emit the duplicate name twice.
  assert_table_form_stringify_error(function()
    encode({ [1] = "a", ["1"] = "b" })
  end, "colliding numeric and string key")
  assert_table_form_stringify_error(function()
    encode({ data = { [2] = "a", name = "test" } })
  end, "nested mixed-key table")
end)

test("json.stringify rejects every key form outside the two JSON table forms", function()
  assert_table_form_stringify_error(function()
    encode({ [0] = "a" })
  end, "zero key")
  assert_table_form_stringify_error(function()
    encode({ [-1] = "a" })
  end, "negative key")
  assert_table_form_stringify_error(function()
    encode({ [1.5] = "a" })
  end, "fractional key")
  assert_table_form_stringify_error(function()
    encode({ [true] = "a" })
  end, "boolean key")
end)

test("json.stringify keeps marked arrays and string-keyed objects distinct", function()
  local array = encode({ [__K.a]=true, [__K.n]=2, "a", "b" })
  assert(array == '["a","b"]', "the pure integer-keyed table is the array: " .. tostring(array))
  assert(encode({ [__K.a]=true, [__K.n]=2, [2] = "b" }) == '[null,"b"]',
    "a hole in an admitted array substitutes null")
  local object = encode({ name = "test" })
  assert(object == '{"name":"test"}', "the pure string-keyed table is the object: " .. tostring(object))
  local numericString = encode({ ["1"] = "a" })
  assert(numericString == '{"1":"a"}', "a numeric string key is a string key: " .. tostring(numericString))
end)

test("json.stringify escapes all control characters", function()
  -- ASCII control character 0x01 (SOH)
  local result = encode({ a = "hello\x01world" })
  assert(string.find(result, "\\u0001") ~= nil, "control character 0x01 should be \\u0001, got: " .. result)
  -- Also verify that no literal control character remains
  assert(string.find(result, "\x01") == nil, "no literal control character should remain")
end)

test("json.parse of object", function()
  local result = decodeRecord('{"a":1,"b":"hi"}')
  assert(type(result) == "table")
  assert(result.a == 1)
  assert(result["b"] == "hi")
end)

test("json.parse rejects a top-level array with E8001 expected table (actual array)", function()
  -- Record is an explicit contextual target. Scanning accepts this root;
  -- reconstruction rejects its shape at the parse expression, not a return gate.
  local err = assert_error_code(function()
    decodeRecord('[1,2,3]', "caller.deal", 9, 18)
  end, "E8001")
  assert(err.message == "expected table", "message, got: " .. tostring(err.message))
  assert(err.expected == "table", "expected token, got: " .. tostring(err.expected))
  assert(err.actual == "array", "actual token, got: " .. tostring(err.actual))
  assert(err.file == "caller.deal" and err.line == 9 and err.column == 18,
    "the tuple carries the call-site triplet")
end)

test("json.parse of null returns __NULL", function()
  local __rt = require("deal.runtime")
  local result = decodeRecord('{"a":null}')
  assert(result.a == __rt.__NULL, "JSON null should parse to __NULL")
end)

test("json.parse of nested null returns __NULL", function()
  local __rt = require("deal.runtime")
  local result = decodeRecord('{"a":[null]}')
  assert(result.a[1] == __rt.__NULL, "JSON null in array should parse to __NULL")
end)

test("json.parse of empty object", function()
  local result = decodeRecord('{}')
  assert(type(result) == "table")
end)

test("json.parse rejects an empty top-level array with the array token", function()
  local err = assert_error_code(function() decodeRecord('[]') end, "E8001")
  assert(err.message == "expected table", "message, got: " .. tostring(err.message))
  assert(err.expected == "table", "expected token, got: " .. tostring(err.expected))
  assert(err.actual == "array", "actual token, got: " .. tostring(err.actual))
end)

test("json.parse of top-level null is rejected as language null (E8001)", function()
  local err = assert_error_code(function() decodeRecord('null') end, "E8001")
  assert(err.message == "expected table", "message, got: " .. tostring(err.message))
  assert(err.expected == "table", "expected token, got: " .. tostring(err.expected))
  assert(err.actual == "null", "actual token, got: " .. tostring(err.actual))
end)

test("json.parse rejects invalid JSON: bare word", function()
  local err = assert_error(function() decodeRecord("tru") end)
  assert(type(err) == "table", "should get error table for 'tru'")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects invalid JSON: malformed object", function()
  local err = assert_error(function() decodeRecord("{invalid}") end)
  assert(type(err) == "table", "should get error table for '{invalid}'")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects invalid JSON: trailing comma", function()
  local err = assert_error(function() decodeRecord('[1,]') end)
  assert(type(err) == "table", "should get error table for trailing comma")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects invalid JSON: trailing garbage", function()
  local err = assert_error(function() decodeRecord('1 2') end)
  assert(type(err) == "table", "should get error table for trailing garbage")
  assert(err.code ~= nil, "error should have code")
end)

test("json.parse rejects empty string", function()
  local err = assert_error(function() decodeRecord('') end)
  assert(type(err) == "table", "should get error table for empty string")
  assert(err.code ~= nil, "error should have code")
end)

test("json round-trip", function()
  local original = { a = 1, b = "hello", c = true, d = { nested = 2 } }
  local encoded = encode(original)
  local decoded = decodeRecord(encoded)
  assert(decoded.a == 1)
  assert(decoded.b == "hello")
  assert(decoded.c == true)
  assert(decoded.d.nested == 2)
end)

test("json null round-trip", function()
  local __rt = require("deal.runtime")
  local original = { a = __rt.__NULL, b = 42 }
  local encoded = encode(original)
  local decoded = decodeRecord(encoded)
  assert(decoded.a == __rt.__NULL, "null should round-trip to __NULL, got " .. tostring(decoded.a))
  assert(decoded.b == 42)
end)

test("json.stringify admits scalar strings", function()
  assert(encode("not a table") == '"not a table"', "scalar string root")
end)

test("json.stringify rejects raw foreign function roots at unknown ingress", function()
  local err = assert_error_code(function() encode(function() end) end, "E8001")
  assert(err.message == "unsupported unknown ingress", "foreign function rejects at unknown boundary")
end)

test("json.parse with non-string raises error", function()
  local err = assert_error(function() decodeRecord(42) end)
  assert(type(err) == "table", "error should be a table")
end)

-- Generic unknown-input stringify substitutes null for nonfinite leaves;
-- descriptor-directed @jsonable rejection is exercised independently.
local function assert_nonfinite_substitution(fn, expected, label)
  local text = fn()
  assert(text == expected, label .. ": expected " .. expected .. ", got " .. tostring(text))
end

test("json.stringify substitutes null for NaN", function()
  assert_nonfinite_substitution(function() return encode({ x = 0.0/0.0 }) end,
    '{"x":null}', "NaN member")
end)

test("json.stringify substitutes null for Infinity", function()
  assert_nonfinite_substitution(function() return encode({ x = 1.0/0.0 }) end,
    '{"x":null}', "Infinity member")
end)

test("json.stringify substitutes null for negative Infinity", function()
  assert_nonfinite_substitution(function() return encode({ x = -1.0/0.0 }) end,
    '{"x":null}', "negative Infinity member")
end)

test("json.stringify substitutes null through a forwarded call site", function()
  assert_nonfinite_substitution(function()
    return encode({ x = 1.0/0.0 }, "caller.deal", 7, 9)
  end, '{"x":null}', "forwarded nonfinite")
end)

test("json.stringify accepts finite numbers unchanged", function()
  local result = encode({ x = 42.0 })
  assert(result == '{"x":42}' or string.find(result, '"x":42') ~= nil, "finite number should encode correctly, got: " .. tostring(result))
end)

test("json.stringify substitutes null for NaN in nested object", function()
  assert_nonfinite_substitution(
    function() return encode({ data = { val = 0.0/0.0 } }) end,
    '{"data":{"val":null}}', "nested NaN")
end)

test("json.stringify substitutes null for Infinity in array", function()
  assert_nonfinite_substitution(function() return encode({ [__K.a]=true, [__K.n]=3, 1, 2, 1.0/0.0 }) end,
    '[1,2,null]', "array Infinity")
end)

-- ===========================================================================
-- Internal JSON Unicode scanning: byte-precise hostile-input controls.
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

test("json.parse rounds decimal lexemes to binary64 without losing negative zero", function()
  -- Independent value assertions cover integral/fractional tokens, former
  -- signed32 edges and exponent saturation inside explicit Record targets.
  local function lexeme(text)
    return decodeRecord('{"v":' .. text .. '}').v
  end
  local neg_zero = lexeme("-0.0")
  assert(neg_zero == 0, "negative zero compares equal to zero")
  assert(1 / neg_zero == -math.huge, "negative zero retains its sign")
  assert(lexeme("1.0") == 1, "1.0 denotes the integer value 1")
  assert(lexeme("1e0") == 1, "1e0 denotes the integer value 1")
  assert(lexeme("100e-2") == 1, "100e-2 denotes the integer value 1")
  assert(lexeme("2147483647e0") == 2147483647, "the positive signed32 edge")
  assert(lexeme("-2147483648.0") == -2147483648, "the negative signed32 edge")
  assert(lexeme("1.5") == 1.5, "a fractional token stays a number")
  -- The signed32 check compares the magnitude before the sign is applied:
  -- the negative neighbours stay in range-consistent values.
  assert(lexeme("-2147483649") == -2147483649, "-2147483649 stays a number")
  assert(lexeme("-9999999999") == -9999999999, "-9999999999 stays a number")
  -- The exponent magnitude accumulates digits with bounded saturation:
  -- leading zeroes contribute nothing, so a zero-padded exponent text
  -- longer than 18 characters whose value is small stays exactly
  -- classified.
  assert(lexeme("1e0000000000000000001") == 10, "a zero-padded long exponent denotes 10")
  assert(lexeme("1e00000000000000000000") == 1, "a zero-valued zero-padded exponent denotes 1")
  assert(lexeme("100e-000000000000000000002") == 1, "a zero-padded negative exponent denotes 1")
  assert(lexeme("-1e0000000000000000001") == -10, "a negative zero-padded long exponent denotes -10")
  local huge = lexeme("1e999999999999999999999")
  assert(huge == math.huge, "a huge exponent converts to binary64 infinity")
  -- A long digit run past the signed32 range stays a number and never
  -- crashes the bounded classification.
  assert(lexeme("123456789012345678901234567890") > 1e29,
    "an out-of-range integer token converts to binary64")
end)

-- Decoded numeric metadata survives at every depth. All binary64 leaves
-- use ECMAScript decimal spelling regardless of metadata or integrality.
test("json.parse records the exact number/number variant in the decoded graph (v1.4)", function()
  local function roundtrip(text)
    return encode(decodeRecord(text))
  end
  assert(roundtrip('{"v":-2147483649}') == '{"v":-2147483649}',
    "-2147483649 stays the number variant, got: " .. roundtrip('{"v":-2147483649}'))
  assert(roundtrip('{"v":0.1}') == '{"v":0.1}',
    "0.1 keeps the canonical decimal text, got: " .. roundtrip('{"v":0.1}'))
  assert(roundtrip('{"v":1e20}') == '{"v":100000000000000000000}',
    "1e20 keeps the pinned exponent form, got: " .. roundtrip('{"v":1e20}'))
  assert(roundtrip('{"v":2147483647.00000001}') == '{"v":2147483647}',
    "a fractional token rounding to an integral value stays the number variant, got: "
      .. roundtrip('{"v":2147483647.00000001}'))
  -- The marks are recorded at every depth, in objects and in arrays.
  assert(roundtrip('{"v":{"o":{"n":0.1}}}') == '{"v":{"o":{"n":0.1}}}',
    "a nested object slot keeps its recorded variant, got: "
      .. roundtrip('{"v":{"o":{"n":0.1}}}'))
  assert(roundtrip('{"v":{"xs":[0.1,-2147483649,1]}}')
      == '{"v":{"xs":[0.1,-2147483649,1]}}',
    "an array slot keeps its recorded variant, got: "
      .. roundtrip('{"v":{"xs":[0.1,-2147483649,1]}}'))
  -- Contextual reconstruction publishes binary64 leaves directly, not
  -- scanner-private numeric marks. Signed zero remains independently visible.
  local ints = decodeRecord('{"a":1.0,"b":1e0,"c":-0.0,"d":2147483647}')
  assert(type(ints.a)=="number" and ints.a==1 and ints.b==1
    and 1/ints.c == -math.huge and ints.d==2147483647,
    "all leaves are binary64 values, including negative zero")
  assert(roundtrip('{"v":{"xs":[1.0,1e0,-0.0,2147483648]}}')
      == '{"v":{"xs":[1,1,0,2147483648]}}',
    "number leaves keep the integer text and the out-of-range integral token is the number variant, got: "
      .. roundtrip('{"v":{"xs":[1.0,1e0,-0.0,2147483648]}}'))
  local parsed = decodeRecord('{"v":0.1}')
  local keys, order = parsed[__K.keys], parsed[__K.order]
  assert(type(parsed.v)=="number" and parsed.v==0.1 and keys.v and order[1]=="v",
    "the reconstructed graph retains binary64 and own presence/order")
  assert(encode(parsed) == '{"v":0.1}', "the encode keeps the text")
  assert(parsed.v==0.1 and parsed[__K.keys]==keys and parsed[__K.order]==order,
    "encoding leaves the graph and private identities unchanged")
end)

-- The chunk table carrier's present-null member (DEAL v1.4): the emitted
-- JSON_PARSE graph records a present null object member as a presence/order
-- key with no stored value, and the shared table carrier may also hold the
-- __NULL sentinel. Both forms are a JSON null member; a raw nil slot the
-- presence map does not record is the missing value and never becomes null.
test("json.stringify keeps a present-null member of a chunk table carrier (v1.4)", function()
  local __rt = require("deal.runtime")
  local carrier = {[__K.t] = true, [__K.keys] = { n = true, x = true },
    [__K.order] = { "n", "x" }, x = 1}
  assert(encode(carrier) == '{"n":null,"x":1}',
    "the presence-recorded null member keeps its JSON null, got: "
      .. encode(carrier))
  local sentinel = {[__K.t] = true, [__K.keys] = { n = true, x = true },
    [__K.order] = { "n", "x" }, n = __rt.__NULL, x = 1}
  assert(encode(sentinel) == '{"n":null,"x":1}',
    "the __NULL member keeps its JSON null, got: "
      .. encode(sentinel))
  local nested = {[__K.t] = true, [__K.keys] = { o = true },
    [__K.order] = { "o" },
    o = {[__K.t] = true, [__K.keys] = { n = true },
      [__K.order] = { "n" }}}
  assert(encode({ v = nested }) == '{"v":{"o":{"n":null}}}',
    "a nested present-null member stays JSON null, got: "
      .. encode({ v = nested }))
end)

test("json.parse rejects decoded-key duplicates at the second key (v1.4)", function()
  local err = assert_json_error(function() decodeRecord('{"a":1,"a":2}') end,
    "JSON parse error at position 8: duplicate key")
  assert(err.expected == nil and err.actual == nil,
    "the duplicate tuple carries no expected/actual")
  assert_json_error(function() decodeRecord('{"a":null,"\\u0061":1}') end, "duplicate key")
  assert_json_error(function() decodeRecord('{"\\u0061":1,"a":2}') end, "duplicate key")
  assert_json_error(function() decodeRecord('{"o":{"k":1,"k":2}}') end, "duplicate key")
  local ok = decodeRecord('{"a":1,"b":2}')
  assert(ok.a == 1 and ok.b == 2, "distinct decoded keys stay accepted")
end)

test("json.stringify rejects a path-local cycle with the closed arm (v1.4)", function()
  local cyc = {}
  cyc.self = cyc
  assert_json_error(function() encode(cyc) end,
    "cyclic value cannot be encoded as JSON")
  -- A shared acyclic child is duplicated, never rejected.
  local shared = { v = 1 }
  local ok = decodeRecord(encode({ a = shared, b = shared }))
  assert(ok.a.v == 1 and ok.b.v == 1, "a shared acyclic child serializes twice")
end)

-- The decoded-array mark (ISSUE-0837): the shipped Lua realization of the
-- JSON-shaped table representation keeps array shape at every depth. The
-- mark pair is the emitted chunk's own array carrier pair (the array mark
-- and the logical length), which is also what the LuaJIT host projection
-- writes for a declared T[] host parameter.
test("json.parse marks every decoded array, empty arrays included (v1.4)", function()
  local parsed = decodeRecord('{"empty":[],"one":[1],"deep":{"nested":[[],{"k":1}]}}')
  assert(parsed.empty[__K.a] == true, "an empty decoded array carries the array mark")
  assert(parsed.empty[__K.n] == 0, "an empty decoded array records the zero length")
  assert(parsed.one[__K.a] == true, "a decoded array carries the array mark")
  assert(parsed.one[__K.n] == 1, "a decoded array records its length")
  assert(parsed.one[1] == 1, "the decoded array keeps its element")
  assert(parsed[__K.a] == nil, "the decoded object root is not the array carrier")
  assert(parsed.deep[__K.a] == nil, "a decoded nested object stays a plain table")
  assert(parsed.deep.nested[__K.a] == true and parsed.deep.nested[__K.n] == 2,
    "an array nested in an object records its own marks")
  assert(parsed.deep.nested[1][__K.a] == true and parsed.deep.nested[1][__K.n] == 0,
    "an empty array nested in an array records the zero length")
  assert(parsed.deep.nested[2][__K.a] == nil,
    "an object nested in an array stays a plain table")
end)

test("json.stringify encodes nested decoded arrays, empty arrays included (v1.4)", function()
  -- The defect: a decoded nested empty array was indistinguishable from {},
  -- so the encoder produced {"items":{}} where every other lane and the
  -- compiled LuaJIT lane produce {"items":[]}.
  assert(encode(decodeRecord('{"items":[]}')) == '{"items":[]}',
    "a nested empty decoded array encodes as []")
  assert(encode(decodeRecord('{"items":[1,2]}')) == '{"items":[1,2]}',
    "a nested decoded array encodes as its elements")
  assert(encode(decodeRecord('{"a":[[],[]]}')) == '{"a":[[],[]]}',
    "arrays nested in arrays keep every empty boundary")
  assert(encode(decodeRecord('{"a":[1,[2],{"b":[]}]}')) == '{"a":[1,[2],{"b":[]}]}',
    "mixed decoded nesting keeps every array boundary")
  -- The encoder does not mutate the decoded carriers it reads.
  local parsed = decodeRecord('{"items":[]}')
  assert(encode(parsed) == '{"items":[]}', "the first encode keeps the array")
  assert(parsed.items[__K.a] == true and parsed.items[__K.n] == 0,
    "encoding leaves the decoded array marks in place")
  -- A round-trip keeps the marks: the re-decoded document is still arrays.
  local again = decodeRecord(encode(parsed))
  assert(again.items[__K.a] == true and again.items[__K.n] == 0,
    "the round-trip keeps the nested empty array mark")
end)

test("json.stringify encodes the emitted chunk array carrier as an array (v1.4)", function()
  -- The carrier a declared T[] host parameter crosses into DEAL as, and the
  -- carrier an emitted chunk constructs: the private array mark, the logical
  -- length, the per-slot variant marks, and the elements. The encoder reads
  -- the mark before the numeric-key inference, so the carrier is a JSON
  -- array and none of its private table-object keys reach the output.
  local carrier = {[__K.a] = true, [__K.n] = 2, [__K.nK] = {}, 1, 2}
  assert(encode({ items = carrier }) == '{"items":[1,2]}',
    "a nested chunk array carrier encodes as an array")
  assert(encode({ items = {[__K.a] = true, [__K.n] = 0, [__K.nK] = {}} }) == '{"items":[]}',
    "an empty chunk array carrier encodes as []")
  local nested = {[__K.a] = true, [__K.n] = 2, [__K.nK] = {},
    "a", {[__K.a] = true, [__K.n] = 0, [__K.nK] = {}}}
  assert(encode({ items = nested }) == '{"items":["a",[]]}',
    "a chunk array carrier's nested array element encodes as []")
  local text = encode({ items = carrier })
  assert(string.find(text, "table:", 1, true) == nil,
    "no private carrier key is encoded, got: " .. text)
  assert(carrier[__K.a] == true and carrier[__K.n] == 2 and carrier[1] == 1,
    "the carrier is not mutated")
end)


-- The numeric-variant carrier (ISSUE-0839): the std/json decode graph's
-- numeric leaf and the read-side variant materialization carry the value
-- model's recorded number/number variant as the private
-- {[__K.jn] = true, k = ..., d = ...} shape. The encoder projects the
-- carried binary64 value with ECMAScript spelling, instead of enumerating
-- its private k/d representation as an object.
test("json.stringify projects a numeric-variant carrier to its number (v1.4)", function()
  local function carrier(kind, value)
    return { [__K.jn] = true, k = kind, d = value }
  end
  assert(encode({ v = carrier("number", 7) }) == '{"v":7}',
    "an number-variant carrier encodes as its integer spelling")
  assert(encode({ v = carrier("number", 1.0) }) == '{"v":1}',
    "a number-variant integral value keeps its recorded number spelling")
  assert(encode({ v = carrier("number", 1.5) }) == '{"v":1.5}',
    "a number-variant fractional value keeps the decimal spelling")
  assert(encode({ v = carrier("number", 0.1) }) == '{"v":0.1}',
    "the canonical decimal spelling is the shortest round-tripping text")
  assert(encode({ v = carrier("number", -0.0) }) == '{"v":0}',
    "a negative-zero number variant keeps its sign")
  assert(encode({ v = carrier("number", 0) }) == '{"v":0}',
    "an number-variant zero stays the integer spelling")
  assert(encode({ v = carrier("number", 1e20) }) == '{"v":100000000000000000000}',
    "a number variant outside the plain range keeps the pinned exponent form")
  -- The carriers occur inside the decoded/array shapes at every depth.
  local chunkArray = { [__K.a] = true, [__K.n] = 2, [__K.nK] = {},
    carrier("number", 1), carrier("number", 2.0) }
  assert(encode({ v = chunkArray }) == '{"v":[1,2]}',
    "carriers inside a chunk array carrier keep their own variants")
  local text = encode({ v = carrier("number", 5) })
  assert(string.find(text, '"k"', 1, true) == nil
      and string.find(text, '"d"', 1, true) == nil,
    "the carrier's private k/d fields never surface, got: " .. text)
  assert(encode({ v = carrier("number", 0.0 / 0.0) }) == '{"v":null}',
    "carried NaN uses generic null substitution")
end)

-- The chunk table carrier (ISSUE-0839): a DEAL table crossing from an
-- emitted chunk carries the shared table carrier's private marker set —
-- the table mark __K.t, the presence map __K.keys, the first-insertion
-- order list __K.order and the per-slot number marks __K.nK. The encoder
-- reads the user string keys in the recorded order (skipping the
-- table-object marks entirely) and uses the per-slot variant marks, so a
-- chunk table serializes as the JSON object it stands for rather than an
-- object of private carrier fields.
test("json.stringify encodes the chunk table carrier over its user keys (v1.4)", function()
  local carrier = { [__K.t] = true,
    [__K.keys] = { b = true, a = true },
    [__K.order] = { "b", "a" }, b = 2, a = 1 }
  assert(encode(carrier) == '{"b":2,"a":1}',
    "the recorded insertion order selects and orders the user keys")
  assert(encode({ nested = carrier }) == '{"nested":{"b":2,"a":1}}',
    "a nested chunk table carrier encodes as an object")
  local empty = { [__K.t] = true, [__K.keys] = {}, [__K.order] = {} }
  assert(encode(empty) == '{}',
    "an empty chunk table carrier encodes as an empty object")
  -- The presence/order marks can exist on a chunk table written outside
  -- TABLE_NEW (the first write creates them); the carrier is still an
  -- object over its string keys.
  local written = { [__K.keys] = { only = true }, [__K.order] = { "only" },
    only = 1 }
  assert(encode(written) == '{"only":1}',
    "the presence/order marks alone still select the object carrier")
  local noOrder = { [__K.t] = true, only = 1 }
  assert(encode(noOrder) == '{"only":1}',
    "a table carrier without an order list keeps the string-key order")
  -- The per-slot number marks select the number-variant spelling.
  local marked = { [__K.t] = true,
    [__K.keys] = { n = true, i = true },
    [__K.order] = { "n", "i" },
    [__K.nK] = { n = true }, n = 1.0, i = 2.0 }
  assert(encode(marked) == '{"n":1,"i":2}',
    "a number-marked slot keeps the number spelling, an unmarked slot the number spelling")
  -- The marks are table-object keys: no private key text is ever emitted.
  local text = encode(carrier)
  assert(string.find(text, "table:", 1, true) == nil,
    "no private carrier key is encoded, got: " .. text)
  assert(carrier[__K.t] == true and carrier[__K.order][1] == "b"
      and carrier.b == 2,
    "the carrier is not mutated")
  -- The existing cycle and unsupported-carrier arms stay in force through
  -- the table carrier walk.
  local cyc = { [__K.t] = true, [__K.keys] = { self = true },
    [__K.order] = { "self" } }
  cyc.self = cyc
  assert_json_error(function() encode(cyc) end,
    "cyclic value cannot be encoded as JSON")
  local callable = require("deal.runtime").function_("()->void", function() end)
  local omitted = { [__K.t] = true, [__K.keys] = { fn = true },
    [__K.order] = { "fn" }, fn = callable }
  assert(encode(omitted) == '{}', "tagged function member is omitted")
  assert(omitted.fn == callable, "serialization retains callable identity")
end)

-- A plain table the emitted chunk wrote through MEMBER_WRITE: __orderAdd
-- gives it __K.keys/__K.order on the first write and __numKey records the
-- written slot's variant in __K.nK, but the table itself carries no __K.t
-- because it was not constructed through TABLE_NEW. A host that receives
-- the table and calls the shipped stringify must see the JSON object it
-- stands for — the recorded insertion order, the number-variant spelling
-- of a marked slot — never the private carrier keys as members (the
-- defect encoded the key/order maps as "table: 0x..." members and the
-- number-variant slot through the number spelling).
test("json.stringify encodes a chunk-written plain table with variant marks (v1.4)", function()
  local written = { [__K.keys] = { x = true, y = true, z = true },
    [__K.order] = { "x", "y", "z" },
    [__K.nK] = { x = true, z = true },
    x = 1.0, y = 7, z = -0.0 }
  assert(written[__K.t] == nil,
    "the written plain table carries no TABLE_NEW mark")
  local text = encode(written)
  assert(text == '{"x":1,"y":7,"z":0}',
    "a written plain table keeps its insertion order and per-slot variants, got: "
      .. text)
  assert(string.find(text, "table:", 1, true) == nil,
    "no private carrier key is encoded, got: " .. text)
  assert(encode({ nested = written })
      == '{"nested":{"x":1,"y":7,"z":0}}',
    "a nested written plain table encodes as an object")
  -- A table that records only the per-slot variant marks (no presence or
  -- order list) takes the same object encoding, never the mark as a
  -- member.
  local marksOnly = { [__K.nK] = { amount = true }, amount = 1.0 }
  assert(encode(marksOnly) == '{"amount":1}',
    "per-slot variant marks alone still select the object carrier, got: "
      .. encode(marksOnly))
  -- The encoder reads the marks; it never mutates the written table.
  assert(written[__K.keys].x == true and written[__K.order][1] == "x"
      and written[__K.nK].x == true and written.x == 1.0,
    "the written plain table is not mutated")
end)

test("json.parse decodes the full RFC 8259 escape table", function()
  local text = '"' .. '\\"' .. '\\\\' .. '\\/' .. '\\b' .. '\\f' .. '\\n' .. '\\r' .. '\\t' .. '"'
  local r = decodeRecord('{"v":' .. text .. '}').v
  assert_byte_seq(r, 0x22, 0x5C, 0x2F, 0x08, 0x0C, 0x0A, 0x0D, 0x09)
end)

test("json.parse decodes \\u00E9 to UTF-8 bytes C3 A9", function()
  assert_byte_seq(decodeRecord('{"v":"\\u00E9"}').v, 0xC3, 0xA9)
  -- Hex digits are case-insensitive (RFC 8259 section 7: 4HEXDIG).
  assert_byte_seq(decodeRecord('{"v":"\\u00e9"}').v, 0xC3, 0xA9)
end)

test("json.parse decodes \\u4E2D to UTF-8 bytes E4 B8 AD", function()
  assert_byte_seq(decodeRecord('{"v":"\\u4E2D"}').v, 0xE4, 0xB8, 0xAD)
end)

test("json.parse combines surrogate pair \\uD834\\uDD1E to U+1D11E (F0 9D 84 9E)", function()
  assert_byte_seq(decodeRecord('{"v":"\\uD834\\uDD1E"}').v, 0xED, 0xA0, 0xB4, 0xED, 0xB4, 0x9E)
end)

test("json.parse combines boundary pair \\uD800\\uDC00 to U+10000 (F0 90 80 80)", function()
  assert_byte_seq(decodeRecord('{"v":"\\uD800\\uDC00"}').v, 0xED, 0xA0, 0x80, 0xED, 0xB0, 0x80)
end)

test("json.parse combines boundary pair \\uDBFF\\uDFFF to U+10FFFF (F4 8F BF BF)", function()
  assert_byte_seq(decodeRecord('{"v":"\\uDBFF\\uDFFF"}').v, 0xED, 0xAF, 0xBF, 0xED, 0xBF, 0xBF)
end)

test("json.parse preserves a lone high surrogate", function()
  assert_byte_seq(decodeRecord('{"v":"\\uD834"}').v, 0xED, 0xA0, 0xB4)
end)

test("json.parse preserves a lone low surrogate", function()
  assert_byte_seq(decodeRecord('{"v":"\\uDD1E"}').v, 0xED, 0xB4, 0x9E)
end)

test("json.parse preserves a high unit followed by a non-low unit", function()
  assert_byte_seq(decodeRecord('{"v":"\\uD834\\u0041"}').v, 0xED, 0xA0, 0xB4, 0x41)
end)

test("internal JSON scanner pins closed reasons and first bad byte offsets", function()
  local cases = {
    {'tru', 4, 'unexpected end of input'},
    {'trX', 3, 'unexpected character'},
    {'{"a" 1}', 6, 'missing colon'},
    {'{', 2, 'unexpected end of input'},
    {'{"a":1', 7, 'unterminated object'},
    {'[1', 3, 'unterminated array'},
    {'[1 2]', 4, 'missing comma'},
    {'[1,]', 4, 'unexpected character'},
    {'{"a":1,}', 8, 'missing key'},
    {'01', 2, 'leading zero'},
    {'1.', 3, 'invalid number'},
    {'1e+', 4, 'invalid number'},
    {'"\\u12G4"', 6, 'invalid escape'},
    {'"\\u12', 6, 'invalid escape'},
    {'"\\q"', 3, 'invalid escape'},
    {'"abc\\', 6, 'unterminated string'},
    {'"a' .. string.char(10) .. '"', 3, 'unexpected character'},
    {'true false', 6, 'trailing content'},
    {'{"é":1,"é":2}', 9, 'duplicate key'},
  }
  for _, case in ipairs(cases) do
    local err = assert_error_code(function()
      decodeUnknown(case[1], 'scan.deal', 7, 13)
    end, 'E8001')
    assert(err.arm.id == 'JSON_PARSE_ERROR')
    assert(err.arm.values.oneBasedByteOffset == tostring(case[2]), case[1] .. ': offset ' .. err.message)
    assert(err.arm.values.reason == case[3], case[1] .. ': reason ' .. err.message)
    assert(err.message == 'JSON parse error at position ' .. case[2] .. ': ' .. case[3])
    assert(err.file == 'scan.deal' and err.line == 7 and err.column == 13)
  end
end)

test("json.parse rejects malformed \\u escapes with E8001", function()
  local forms = { '"\\u12"', '"\\u123"', '"\\uZZZZ"', '"\\u12G4"' }
  for _, text in ipairs(forms) do
    assert_json_error(function() decodeRecord(text) end,
      "invalid escape")
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
    assert_json_error(function() decodeRecord(text) end, "invalid escape")
  end
end)

test("json.parse rejects raw control characters with E8001", function()
  local controls = { 0x00, 0x01, 0x08, 0x09, 0x0A, 0x0D }
  for _, b in ipairs(controls) do
    local text = '"' .. string.char(b) .. '"'
    assert_json_error(function() decodeRecord(text) end,
      "unexpected character")
  end
end)

test("json.parse accepts raw DEL (0x7F) and escaped control forms", function()
  assert_byte_seq(decodeRecord('{"v":"' .. string.char(0x7F) .. '"}').v, 0x7F)
  assert_byte_seq(decodeRecord('{"v":"\\u0000"}').v, 0x00)
  assert_byte_seq(decodeRecord('{"v":"\\u007F"}').v, 0x7F)
end)

test("json.parse treats decoded \\u0022 and \\u005C as data, not structure", function()
  -- Decoded quote/backslash must not re-lex: the parse only succeeds when
  -- they are appended as data.
  assert_byte_seq(decodeRecord('{"v":"\\u0022\\u005C"}').v, 0x22, 0x5C)
end)

test("json.parse passes raw supplementary UTF-8 through byte-identically", function()
  local supplementary = "\xF0\x9D\x84\x9E" -- U+1D11E, scalar-valid UTF-8
  assert_byte_seq(decodeRecord('{"v":"' .. supplementary .. '"}').v, 0xED, 0xA0, 0xB4, 0xED, 0xB4, 0x9E)
end)

test("json.stringify emits supplementary characters raw (byte-identical)", function()
  local supplementary = "\xF0\x9D\x84\x9E" -- U+1D11E
  local result = require("deal.runtime").string_transport(encode({ v = supplementary }))
  assert(string.find(result, supplementary, 1, true) ~= nil,
    "supplementary bytes should be emitted raw, got: " .. result)
  assert(string.find(result, "\\uD834\\uDD1E") == nil,
    "supplementary bytes should not be re-encoded as a surrogate pair")
end)

test("json supplementary stringify/parse round-trip is byte-identical", function()
  local orig = "\xF0\x9D\x84\x9E\xC3\xA9\xE4\xB8\xAD" -- U+1D11E U+00E9 U+4E2D
  local rt = require("deal.runtime")
  local encoded = encode({ v = orig })
  assert(string.find(rt.string_transport(encoded), orig, 1, true) ~= nil, "transport retains scalar bytes")
  local decoded = decodeRecord(encoded)
  assert(decoded.v == rt.string_normalize(orig), "parse(stringify(s)) preserves code units")
end)

-- An invalid scalar sequence is still a string carrier: the closed
-- JSON_STRINGIFY_UNSUPPORTED arm rejects it with the string token on the
-- shipped LuaJIT realization exactly as the emitted chunk, the JS runtime and
-- the JVM runtime do (the carrier-kind projection, never a private
-- invalid-UTF-8 message).
local function assert_invalid_scalar_stringify_error(fn, label, file, line, column)
  local err = assert_error_code(fn, "E8001")
  assert(err.message == "expected string, got UTF-16 surrogate code point"
      or err.message == "expected string, got invalid UTF-8 encoding",
    label .. ": message " .. tostring(err.message))
  assert(err.expected == "string", label .. ": expected " .. tostring(err.expected))
  assert(err.actual == "invalid UTF-8 string", label .. ": actual " .. tostring(err.actual))
  if file ~= nil then
    assert(err.file == file and err.line == line and err.column == column,
      label .. ": the tuple carries the call-site triplet, got "
        .. tostring(err.file) .. ":" .. tostring(err.line) .. ":" .. tostring(err.column))
  end
  return err
end

test("json.stringify rejects invalid UTF-8 leaves with E8001", function()
  local invalid = {
    string.char(0xF4, 0x90, 0x80, 0x80), -- above Unicode range
    string.char(0xC0, 0xAF),       -- overlong encoding
    string.char(0xC3),             -- truncated 2-byte sequence
  }
  for _, s in ipairs(invalid) do
    assert_invalid_scalar_stringify_error(function() encode({ v = s }) end,
      "invalid scalar leaf")
  end
end)

test("json.stringify preserves lone-unit keys and rejects malformed keys", function()
  local t = {}
  t[string.char(0xED, 0xA0, 0x80)] = "value"
  assert(encode(t) == '{"\\ud800":"value"}')
  t = {[string.char(0xED, 0xA0)] = "value"}
  assert_invalid_scalar_stringify_error(function() encode(t) end,
    "malformed key")
end)

-- The invalid-scalar key arm through a forwarded call site: the shipped
-- realization publishes the closed string token and the caller's span, the
-- same tuple the JS runtime and compiled lanes publish for the same subject.
test("json.stringify invalid-scalar tuple matches the compiled-lane pin", function()
  local t = {}
  t[string.char(0xC0, 0xAF)] = 1
  assert_invalid_scalar_stringify_error(function()
    encode(t, "caller.deal", 4, 2)
  end, "forwarded invalid scalar key", "caller.deal", 4, 2)
end)

test("json.parse rejects raw invalid UTF-8 input with E8001 (entry gate)", function()
  local surrogate_bytes = string.char(0xED, 0xA0, 0x80)
  assert(decodeRecord('{"v":"' .. surrogate_bytes .. '"}').v == surrogate_bytes)
  local stray_bytes = string.char(0x61, 0x80, 0x62)
  assert_json_error(function() decodeRecord('"' .. stray_bytes .. '"') end,
    "expected string, got invalid UTF-8 encoding")
end)

-- ===========================================================================
-- Binary64 edge values and stringify shape rejection
-- ===========================================================================

test("json.parse maps JSON numbers to number exactly inside the int32 range", function()
  local __rt = require("deal.runtime")
  local result = decodeRecord('{"a":2147483647,"b":2147483648,"c":-2147483648,"d":-2147483649}')
  assert(result.a == 2147483647, "2147483647 must parse")
  assert(result.b == 2147483648, "2147483648 must parse as number")
  assert(result.c == -2147483648, "-2147483648 must parse")
  assert(result.d == -2147483649, "-2147483649 must parse as number")
  assert(__rt.check_type('number', result.a) == result.a, "2147483647 maps to number")
  assert(__rt.check_type('number', result.b) == result.b, "2147483648 maps to number")
  assert(__rt.check_type('number', result.c) == result.c, "-2147483648 maps to number")
  assert(__rt.check_type('number', result.d) == result.d, "-2147483649 maps to number")
end)

test("json.parse normalizes -0 to 0", function()
  local result = decodeRecord('{"z":-0}')
  local z = result.z
  assert(1 / z < 0, "-0 must normalize to 0 (1/-0 is -Infinity)")
  assert(1 / z == -math.huge, "normalized zero divides to +Infinity")
end)

-- The closed unsupported-carrier rejection (v1.4): every produced carrier
-- renders the JSON_STRINGIFY_UNSUPPORTED arm's own text with the closed
-- carrier-kind token instead of encoding private carrier fields. A carrier
-- is rejected at the declared (table) parameter cell first; a carrier
-- nested in a table is the walk's own arm.
test("json.stringify traverses a class instance without carrier keys", function()
  local __rt = require("deal.runtime")
  local inst = __rt.class_plan_("@m/C", {{name="a",descriptor="number",optional=false}}, {a=1})
  local decoded = decodeRecord(encode({ c = inst }))
  assert(decoded.c.a == 1 and decoded.c[__K.kind] == nil, "class public field only")
end)

test("Record parameters reject nominal carriers independently of generic stringify", function()
  -- table.keys retains its Record parameter; generic stringify accepts unknown.
  local __rt = require("deal.runtime")
  local cases = {
    { name = "class instance", value = __rt.class_plan_("@m/C", {{name="a",descriptor="number",optional=false}}, {a=1}),
      actual = "@m/C" },
    { name = "function wrapper",
      value = __rt.function_("()->null", function() return __rt.__NULL end),
      actual = "function" },
    { name = "emitted chunk function carrier",
      value = { [__rt.__keys.fn] = function() end,
        [__rt.__keys.sig] = "function(;null)",
        [__rt.__keys.csig] = "()->null",
        [__rt.__keys.fid] = "m#f" },
      actual = "function" },
    { name = "async handle", value = __rt.async_start(function() end),
      actual = "async-operation" },
    { name = "language null", value = __rt.__NULL, actual = "null" },
    { name = "marked array", value = { [__K.a]=true, [__K.n]=2, 1, 2 }, actual = "array" },
  }
  for _, case in ipairs(cases) do
    if case.actual == "array" then
      assert(encode(case.value) == "[1,2]", case.name .. ": array encodes")
    end
    do
      local err = assert_error_code(function() require("std.table").keys.f(case.value) end, "E8001")
      assert(err.message == "expected table", case.name .. ": message, got: "
        .. tostring(err.message))
      assert(err.expected == "table", case.name .. ": expected token")
      assert(err.actual == case.actual, case.name .. ": actual token, got: "
        .. tostring(err.actual))
    end
  end
end)

test("json.stringify exposes only code/message of an Error instance", function()
  local __rt = require("deal.runtime")
  local ev = __rt.error_value("E1", "boom", "secret.deal", 9, 3)
  assert(encode({ e = ev }) == '{"e":{"code":"E1","message":"boom"}}', "Error public fields only")
end)

test("json.stringify omits tagged function members and yields undefined for their root", function()
  local __rt = require("deal.runtime")
  local wrapper = __rt.function_("()->null", function() return __rt.__NULL end)
  assert(encode({ f = wrapper }) == '{}', "function member omission")
  assert(encode(wrapper) == __rt.__UNDEFINED, "function root")
end)

test("json.stringify rejects an unread async handle at recursive ingress", function()
  local __rt = require("deal.runtime")
  local handle = __rt.async_start(function() return "done" end)
  local err = assert_error_code(function() encode({ op = handle }) end, "E8001")
  assert(err.message == "unsupported unknown ingress",
    "async handle message, got: " .. tostring(err.message))
  assert(err.actual == "table", "async handle actual token, got: " .. tostring(err.actual))
end)

test("json.stringify omits undefined members and yields undefined for their root", function()
  local __rt = require("deal.runtime")
  assert(encode({ m = __rt.__MISSING }) == '{}', "undefined member omission")
  assert(encode(__rt.__MISSING) == __rt.__UNDEFINED, "undefined root")
end)

-- Generic stringify substitutes null for array absence without repairing it.
test("json.stringify substitutes null for a deleted decoded-array slot", function()
  local doc = decodeRecord('{"xs":[1,2]}')
  doc.xs[1] = nil
  assert(encode(doc, "caller.deal", 12, 9) == '{"xs":[null,2]}', "hole substitution")
  assert(doc.xs[1] == nil, "deleted slot remains absent")
  assert(encode(decodeRecord('{"xs":[null,2]}')) == '{"xs":[null,2]}',
    "a decoded present null still encodes as null")
end)

-- A marked array retains logical length; absent slots encode as JSON null.
test("json.stringify substitutes null in sparse marked arrays", function()
  assert(encode({ [__K.a]=true, [__K.n]=2, [2] = 2 }, "caller.deal", 3, 1) == '[null,2]', "sparse array")
  assert(encode({ [__K.a]=true, [__K.n]=2, 1, 2 }) == '[1,2]', "the dense array still encodes")
end)

-- Chunk-shaped carriers use shared private metadata, never their transport keys.
test("json.stringify projects emitted class/Error carriers and omits callable members", function()
  local __rt = require("deal.runtime")
  local carriers = {
    { name = "emitted class instance", actual = "table",
      value = { [__K.c] = true, [__K.id] = "@m/C", [__K.f] = {}, [__K.p] = {}, [__K.classOrder] = {}, [__K.classPlan] = {} },
      expected = '{"payload":{}}' },
    { name = "emitted Error value", actual = "table",
      value = { [__K.d] = true, code = "E1", m = "boom", o = nil, e = nil, a = nil },
      expected = '{"payload":{"code":"E1","message":"boom"}}' },
    { name = "emitted function carrier", actual = "function",
      value = { [__K.fn] = function() end, [__K.csig] = "()->null", [__K.fid] = nil }, expected = '{}' },
    { name = "emitted function instance", actual = "function",
      value = { [__K.fn] = function() end, [__K.f] = {}, [__K.sig] = "()->null" }, expected = '{}' },
  }
  for _, carrier in ipairs(carriers) do
    local text = encode({ payload = carrier.value })
    assert(text == carrier.expected, carrier.name .. ": unexpected projection " .. tostring(text))
  end
  -- The valid JSON-shaped carriers are unchanged by the marker arm.
  assert(encode({ ok = 1 }) == '{"ok":1}', "a plain table still serializes")
  assert(encode({ [__K.a]=true, [__K.n]=2, 1, 2 }) == '[1,2]', "an array still serializes")
  assert(encode({ v = __rt.__NULL }) == '{"v":null}',
    "a nested null mark still serializes")
end)

-- ISSUE-0830: a DEAL table may carry any string key. A user table whose
-- keys spell the internal carrier vocabulary is a plain JSON-shaped table on
-- every consumer — it neither forges a carrier nor loses its own keys.
test("a user table with carrier-like string keys encodes as data (v1.4)", function()
  local __rt = require("deal.runtime")
  local forged = {
    __kind = "class", __classname = "Error", __c = true, __id = "@m/C",
    __d = true, __a = true, __n = 3, __nK = 1, __da = true, __t = true,
    __keys = "keys", __order = "order", __p = true, __f = true,
    __fn = 1, __csig = "s", __sig = "t", __fid = 2, __it = "u",
    __sid = "v", __val = 3, __mode = 4, __jn = true, __jo = true,
    __ja = true, __jkeys = 5, __jnum = 6, __jseen = 7, __ptr = 8,
    data = 9,
  }
  local encoded = encode(forged)
  local back = decodeRecord(encoded)
  assert(back.__kind == "class" and back.__c == true and back.__t == true,
    "the carrier-like keys survive the round-trip as data")
  assert(back.__keys == "keys" and back.__order == "order", "the map keys survive")
  assert(back.data == 9, "the ordinary key survives")
  -- The same table is the table value at the table boundary (its string keys
  -- never select a carrier arm).
  assert(__rt.check_type("record", forged) == forged,
    "a table with carrier-like keys is the table value")
end)

test("json number/number document round-trip", function()
  local doc = { count = 42, ratio = 2.5 }
  local encoded = encode(doc)
  local back = decodeRecord(encoded)
  assert(back.count == 42, "number round-trip mismatch")
  assert(back.ratio == 2.5, "number round-trip mismatch")
end)

-- ===========================================================================
-- std/math tests
-- ===========================================================================

test("json.stringify validates live declared positions in traversal order", function()
  local plan = {
    {name='first',descriptor='unknown',optional=false},
    {name='name',descriptor='string',optional=false},
    {name='grid',descriptor='[[number]]',optional=false},
    {name='note',descriptor='!string',optional=true},
    {name='required',descriptor='!string',optional=false},
  }
  local row = {[__K.a]=true,[__K.n]=1,[__K.elementDescriptor]='number',1}
  local grid = {[__K.a]=true,[__K.n]=1,[__K.elementDescriptor]='[number]',row}
  local value = __rt.class_plan_('@serialization/Owner',plan,
    {first=__rt.__NULL,name='kept',grid=grid,required=__rt.__UNDEFINED})
  local expected='{"first":null,"name":"kept","grid":[[1]]}'
  assert(encode(value)==expected)
  value.first=value; value.name=42
  local e=assert_error_code(function() encode(value,'audit.deal',9,3) end,'E8001')
  assert(e.message=='cyclic value cannot be encoded as JSON' and e.cause==nil)
  value.first=__rt.__NULL
  e=assert_error_code(function() encode(value,'audit.deal',9,3) end,'E8001')
  assert(e.message=='expected string' and e.expected=='string' and e.actual=='number')
  assert(e.file=='audit.deal' and e.line==9 and e.column==3)
  value.name='kept'; row[1]=false
  e=assert_error_code(function() encode(value,'audit.deal',9,3) end,'E8003')
  assert(e.expected=='[number]' and e.actual=='array' and e.message=='array element 1 type mismatch')
  assert(e.cause.code=='E8001' and e.cause.message=='expected number' and e.cause.cause==nil)
  assert(e.cause.file=='audit.deal' and e.cause.line==9 and e.cause.column==3)
  row[1]=1; value.required=nil
  e=assert_error_code(function() encode(value) end,'E8001')
  assert(e.message=='expected class instance' and e.actual=='@serialization/Owner')
  value.required=__rt.__UNDEFINED; value.note=false
  e=assert_error_code(function() encode(value) end,'E8001')
  assert(e.expected=='string' and e.actual=='boolean')
  value.note=nil
  assert(encode(value)==expected and value.grid==grid and grid[1]==row)
  local shared={x=1}
  assert(encode({a=shared,b=shared})=='{"a":{"x":1},"b":{"x":1}}'
      or encode({a=shared,b=shared})=='{"b":{"x":1},"a":{"x":1}}')
  local parsed=jsonAlgorithms.decode('{"name":"ok"}','@serialization/Decoded',{
    ['@serialization/Decoded']={{name='name',descriptor='string',optional=false}}})
  parsed.name=42
  e=assert_error_code(function() encode(parsed) end,'E8001')
  assert(e.expected=='string' and e.actual=='number')
  parsed.name='ok'; assert(encode(parsed)=='{"name":"ok"}')
end)

local mathlib = require("std.math")

-- absInt tests





-- int32 contract tests (luajit-v1.2-stdlib-contracts D2, ISSUE-0337)










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





-- maxInt tests





-- floor tests (returns number, not number)

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

-- ceil tests (returns number, not number)

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

test("math.sqrt negative inputs yield NaN", function()
  for _, value in ipairs({-1, -0.5, -math.huge}) do
    local result = mathlib.sqrt.f(value)
    assert(result ~= result, "negative sqrt is NaN")
  end
end)

test("math.sqrt preserves signed zero and positive infinity", function()
  assert(1 / mathlib.sqrt.f(-0.0) == -math.huge)
  assert(mathlib.sqrt.f(math.huge) == math.huge)
end)

test("math.sqrt with non-number raises error", function()
  local err = assert_error(function() mathlib.sqrt.f("hi") end)
  assert(type(err) == "table", "error should be a table")
end)

-- ===========================================================================
-- std/time tests
-- ===========================================================================

local timelib = require("std.time")

test("time.nowMillis returns the epoch-millisecond reading as a number", function()
  local ts = timelib.nowMillis.f()
  assert(type(ts) == "number", "expected a number, got " .. type(ts))
  assert(ts > 2147483647, "a contemporary reading exceeds the signed-32 range")
  assert(ts % 1000 == 0, "the reading keeps the existing second resolution")
end)

test("time.nowMillis tracks the backend clock within its resolution", function()
  local ts = timelib.nowMillis.f()
  local now = os.time() * 1000
  assert(math.abs(ts - now) <= 1000, "the reading is within one second of os.time()")
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
