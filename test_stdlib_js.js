"use strict";

// Test suite for the DEAL JavaScript standard library modules
// (js-stdlib-modules D10, Verification 2). Run from the repo root:
//
//   node test_stdlib_js.js
//
// Dependency-free and self-asserting: no assertion library; a failed
// assertion throws with the failing case named, and the final throw makes
// the process exit non-zero, so run_tests.sh's node-guarded block fails
// the gate via set -e. The script writes no files.
//
// Standard-library modules load extensionless from repo-root std/ and use
// repo-root deal/runtime.js. JSON builtins use decoding/encoding primitives
// with explicit target descriptors and spans, not stdlib callable values.
// Hostile JSON inputs (raw
// controls, lone surrogates) are built at runtime with String.fromCharCode
// and \u escapes — no raw hostile byte is committed literally to this
// file (the test_stdlib.lua convention).

const rt = require("./deal/runtime");
const consoleMod = require("./std/console");
const string = require("./std/string");
const table = require("./std/table");
// Builtins are direct operations, never DEAL callable wrappers.
function decodeRecord(text, file = "f.js", line = 1, column = 1) {
  return rt.jsonDecode(rt.checkString(text, file, line, column), "record", file, line, column);
}
function decodeUnknown(text, file = "f.js", line = 1, column = 1) {
  return rt.jsonDecode(rt.checkString(text, file, line, column), "unknown", file, line, column);
}
function encode(value, file = "f.js", line = 1, column = 1) {
  rt.checkType("unknown", value, file, line, column);
  return rt.checkType("!string", rt.jsonStringifyText(value, [], file, line, column), file, line, column);
}
const math = require("./std/math");
const time = require("./std/time");

// ===== Harness (mirrors test_stdlib.lua) =====

let passed = 0;
let failed = 0;

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log("PASS: " + name);
  } catch (e) {
    failed++;
    console.log("FAIL: " + name + " -- " + (e && e.message ? e.message : String(e)));
  }
}

function assert(cond, msg) {
  if (!cond) {
    throw new Error(msg);
  }
}

function assertEqual(actual, expected, msg) {
  if (actual !== expected) {
    throw new Error(msg + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")");
  }
}

function assertArrayEqual(actual, expected, msg) {
  assertEqual(JSON.stringify(actual), JSON.stringify(expected), msg);
}

function assertSpan(err, file, line, column) {
  assertEqual(err.file, file, "error should carry the forwarded file");
  assertEqual(err.line, line, "error should carry the forwarded line");
  assertEqual(err.column, column, "error should carry the forwarded column");
}

function assertError(fn) {
  try {
    fn();
  } catch (e) {
    return e;
  }
  throw new Error("expected an error but none was raised");
}

function assertErrorCode(fn, expectedCode) {
  const err = assertError(fn);
  if (typeof err !== "object" || err === null) {
    throw new Error("expected an error object but got " + typeof err);
  }
  if (err.$dealCode !== expectedCode || err.code !== expectedCode) {
    throw new Error("expected error code " + expectedCode + " but got " + err.$dealCode + ": " + err.message);
  }
  return err;
}

function assertErrorMessage(fn, expectedCode, needle) {
  const err = assertErrorCode(fn, expectedCode);
  if (typeof err.message !== "string" || err.message.indexOf(needle) === -1) {
    throw new Error("error message should contain '" + needle + "', got: " + err.message);
  }
  return err;
}

// ===========================================================================
// Exact stdlib member sets, signatures and wrappers; direct JSON operations.
// ===========================================================================

test("std/console loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(consoleMod)), JSON.stringify(["log", "error"]), "console member set");
});

test("std/string loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(string)), JSON.stringify(
    ["length", "substring", "contains", "startsWith", "endsWith", "replace", "split", "trim"]),
    "string member set");
});

test("std/table loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(table)), JSON.stringify(["keys"]), "table member set");
});

test("JSON builtins execute direct runtime operations without callable wrappers", function() {
  assert(typeof rt.jsonDecode === "function", "contextual decoder exists");
  assert(typeof rt.jsonStringifyText === "function", "generic encoder exists");
  assertEqual(rt.jsonDecode.$kind, undefined, "decoder has no DEAL function identity");
  assertEqual(rt.jsonStringifyText.$kind, undefined, "encoder has no DEAL function identity");
  assertEqual(encode(decodeRecord('{"n":7}')), '{"n":7}', "direct operations execute");
});

test("std/math loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(math)), JSON.stringify(
    ["floor", "ceil", "sqrt", "absNumber"]),
    "math member set");
});

test("std/time loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(time)), JSON.stringify(["nowMillis"]), "time member set");
});

test("every stdlib member is a $rt.function wrapper with the pinned signature", function() {
  const surfaces = [
    [consoleMod, "std/console", { log: "(string)->void", error: "(string)->void" }],
    [string, "std/string", {
      length: "(string)->number",
      substring: "(string,number,number)->string",
      contains: "(string,string)->boolean",
      startsWith: "(string,string)->boolean",
      endsWith: "(string,string)->boolean",
      replace: "(string,string,string)->string",
      split: "(string,string)->[string]",
      trim: "(string)->string",
    }],
    [table, "std/table", { keys: "(record)->[string]" }],
    [math, "std/math", {
      floor: "(number)->number",
      ceil: "(number)->number",
      sqrt: "(number)->number",
      absNumber: "(number)->number",
    }],
    [time, "std/time", { nowMillis: "()->number" }],
  ];
  for (const entry of surfaces) {
    const mod = entry[0];
    const modName = entry[1];
    const members = entry[2];
    for (const memberName of Object.keys(members)) {
      const wrapper = mod[memberName];
      assert(wrapper !== undefined, modName + "." + memberName + " is exported");
      assertEqual(wrapper.$kind, "function", modName + "." + memberName + " $kind");
      assert(typeof wrapper.$f === "function", modName + "." + memberName + " has a callable $f");
      assertEqual(wrapper.$sig, members[memberName], modName + "." + memberName + " signature");
    }
  }
});

// ===========================================================================
// Entry/exit check contract (js-stdlib-modules D2): wrong-typed arguments
// raise E8001 with the forwarded span; valid calls return validated values
// ===========================================================================

test("console.log rejects a non-string with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { consoleMod.log.$f(42, "t1.deal", 1, 2); }, "E8001");
  assertEqual(err.message, "expected string", "console.log entry message");
  assertEqual(err.expected, "string", "console.log expected kind");
  assertEqual(err.actual, "number", "console.log actual kind (the value-derived number token)");
  assertSpan(err, "t1.deal", 1, 2);
});

test("console.error rejects a non-string with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { consoleMod.error.$f(null, "t2.deal", 3, 4); }, "E8001");
  assertEqual(err.message, "expected string", "console.error entry message");
  assertSpan(err, "t2.deal", 3, 4);
});

test("string.length rejects a non-string with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { string.length.$f(42, "t3.deal", 5, 6); }, "E8001");
  assertEqual(err.message, "expected string", "string.length entry message");
  assertSpan(err, "t3.deal", 5, 6);
});

test("string.length publishes the shared typed-boundary token for a carrier", function() {
  // The shipped stdlib module's declared parameter cell is the runtime
  // matcher: a class instance and the builtin Error project their carried
  // canonical class atom as the actual kind — the identical closed tuple
  // the LuaJIT stdlib's __rt.check_string rows publish — never the generic
  // "object" spelling of the carrier.
  const instance = {};
  rt.setProp(instance, "$kind", "class");
  rt.setProp(instance, "$classname", "@m/C");
  const errorValue = rt.errorValue("E_USER", "boom");
  let err = assertErrorCode(function() { string.length.$f(instance, "t3.deal", 5, 6); }, "E8001");
  assertEqual(err.message, "expected string", "class instance at the string cell message");
  assertEqual(err.expected, "string", "class instance at the string cell expected");
  assertEqual(err.actual, "@m/C", "class instance at the string cell actual");
  err = assertErrorCode(function() { string.length.$f(errorValue, "t3.deal", 5, 6); }, "E8001");
  assertEqual(err.expected, "string", "builtin Error at the string cell expected");
  assertEqual(err.actual, "Error", "builtin Error at the string cell actual");
  // The language null keeps its own token, never the object spelling.
  err = assertErrorCode(function() { string.length.$f(null, "t3.deal", 5, 6); }, "E8001");
  assertEqual(err.actual, "null", "language null at the string cell actual");
  // The number parameter row publishes the class atom too.
  err = assertErrorCode(function() { string.substring.$f("ab", instance, 1, "t4.deal", 7, 8); }, "E8001");
  assertEqual(err.message, "expected number", "class instance at the number cell message");
  assertEqual(err.actual, "@m/C", "class instance at the number cell actual");
});

test("string.substring rejects a non-number start with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { string.substring.$f("a", "bad", 2, "t4.deal", 7, 8); }, "E8001");
  assertEqual(err.message, "expected number", "string.substring entry message");
  assertSpan(err, "t4.deal", 7, 8);
});

test("table.keys rejects a non-table with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { table.keys.$f("x", "t5.deal", 9, 10); }, "E8001");
  assertEqual(err.message, "expected table", "table.keys entry message");
  assertSpan(err, "t5.deal", 9, 10);
});

test("json.parse rejects a non-string with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { decodeRecord(42, "t6.deal", 11, 12); }, "E8001");
  assertEqual(err.message, "expected string", "json.parse entry message");
  assertSpan(err, "t6.deal", 11, 12);
});

test("math.floor rejects a non-number with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { math.floor.$f("x", "t7.deal", 13, 14); }, "E8001");
  assertEqual(err.message, "expected number", "math.floor entry message");
  assertSpan(err, "t7.deal", 13, 14);
});

test("math.sqrt negative inputs yield NaN; invalid arguments retain their source", function() {
  for (const value of [-1, -0.5, -Infinity])
    assert(Number.isNaN(math.sqrt.$f(value, "t8.deal", 15, 16)), "negative sqrt is NaN");
  assert(Object.is(math.sqrt.$f(-0), -0), "sqrt preserves negative zero");
  assertEqual(math.sqrt.$f(Infinity), Infinity, "sqrt preserves infinity");
  const err = assertErrorCode(() => math.sqrt.$f("bad", "t8.deal", 15, 16), "E8001");
  assertSpan(err, "t8.deal", 15, 16);
});

test("valid calls return values that re-validate through the $rt checks", function() {
  assertEqual(consoleMod.log.$f("x", "f.js", 1, 1), undefined, "console void result");
  assertEqual(rt.checkNumber(string.length.$f("aé中😀b", "f.js", 1, 1), "f.js", 1, 1), 6, "length result");
  assertEqual(rt.checkString(string.substring.$f("abc", 1, 3, "f.js", 1, 1), "f.js", 1, 1), "bc", "substring result");
  assertEqual(rt.checkBoolean(string.contains.$f("abc", "b", "f.js", 1, 1), "f.js", 1, 1), true, "contains result");
  assertEqual(rt.checkString(string.replace.$f("a-a", "a", "x", "f.js", 1, 1), "f.js", 1, 1), "x-a", "replace result");
  rt.checkArray("[string]", string.split.$f("a,b", ",", "f.js", 1, 1), "f.js", 1, 1);
  assertEqual(rt.checkString(string.trim.$f("  x  ", "f.js", 1, 1), "f.js", 1, 1), "x", "trim result");
  rt.checkArray("[string]", table.keys.$f(rt.makeTable({}), "f.js", 1, 1), "f.js", 1, 1);
  rt.checkTable(decodeRecord("{}", "f.js", 1, 1), "f.js", 1, 1);
  rt.checkString(encode(rt.makeTable({ a: 1 }), "f.js", 1, 1), "f.js", 1, 1);
  assertEqual(rt.checkNumber(math.floor.$f(1.9, "f.js", 1, 1), "f.js", 1, 1), 1, "floor result");
  assertEqual(rt.checkNumber(math.absNumber.$f(-3, "f.js", 1, 1), "f.js", 1, 1), 3, "absInt result");
  // The declared number return of std/time.nowMillis is the current epoch
  // millisecond count: a contemporary reading exceeds the signed-32 range
  // and crosses the binary64 boundary unchanged.
  const now = time.nowMillis.$f("f.js", 1, 1);
  assertEqual(typeof now, "number", "nowMillis result kind");
  assert(now > 2147483647, "nowMillis result exceeds the signed-32 range");
  assertEqual(rt.checkNumber(now, "f.js", 1, 1), now, "nowMillis result re-validates");
});

// ===========================================================================
// std/string parity: ECMAScript UTF-16 operations
// ===========================================================================

test("string.length counts UTF-16 units", function() {
  assertEqual(string.length.$f("aé中😀b", "f.js", 1, 1), 6, "length of aé中😀b");
  assertEqual(string.length.$f("", "f.js", 1, 1), 0, "length of empty");
  assertEqual(string.length.$f("😀", "f.js", 1, 1), 2, "a supplementary character is two units");
});

test("string.substring indexes UTF-16 units", function() {
  const s = "aé中😀b";
  assertEqual(string.substring.$f(s, 1, 5, "f.js", 1, 1), "é中😀", "unit positions 1..4");
  assertEqual(string.substring.$f(s, 0, 6, "f.js", 1, 1), s, "the full span");
  assertEqual(string.substring.$f("😀x", 0, 1, "f.js", 1, 1), "\ud83d", "exact high unit");
  assertEqual(string.substring.$f("😀x", 1, 2, "f.js", 1, 1), "\ude00", "exact low unit");
});

test("string.substring clamps, truncates and swaps bounds", function() {
  const s = "aé中😀b";
  assertEqual(string.substring.$f(s, -1, 3, "f.js", 1, 1), "aé中", "negative start behaves as 0");
  assertEqual(string.substring.$f(s, 1, -1, "f.js", 1, 1), "a", "negative end clamps then swaps");
  assertEqual(string.substring.$f(s, 2, 100, "f.js", 1, 1), "中😀b", "out-of-range end clamps");
  assertEqual(string.substring.$f(s, 3, 3, "f.js", 1, 1), "", "start == end yields empty");
  assertEqual(string.substring.$f(s, 5, 2, "f.js", 1, 1), "中😀", "start > end swaps");
  assertEqual(string.substring.$f(s, 10, 12, "f.js", 1, 1), "", "start beyond the string yields empty");
});

test("string.contains/startsWith/endsWith are plain-text", function() {
  assertEqual(string.contains.$f("hello world", "o w", "f.js", 1, 1), true, "found substring");
  assertEqual(string.contains.$f("abc", "z", "f.js", 1, 1), false, "missing substring");
  assertEqual(string.contains.$f("abc", "", "f.js", 1, 1), true, "empty part is contained");
  assertEqual(string.contains.$f("a.b", "a.", "f.js", 1, 1), true, "dot is literal, never a pattern");
  assertEqual(string.startsWith.$f("hello", "he", "f.js", 1, 1), true, "prefix matches");
  assertEqual(string.startsWith.$f("hello", "lo", "f.js", 1, 1), false, "non-prefix");
  assertEqual(string.endsWith.$f("hello", "lo", "f.js", 1, 1), true, "suffix matches");
  assertEqual(string.endsWith.$f("hello", "he", "f.js", 1, 1), false, "non-suffix");
});

test("string.replace replaces the first literal occurrence", function() {
  assertEqual(string.replace.$f("a-b-a", "a", "x", "f.js", 1, 1), "x-b-a", "first occurrence only");
  assertEqual(string.replace.$f("abc", "z", "q", "f.js", 1, 1), "abc", "no match returns the original");
  assertEqual(string.replace.$f("abc", "", "q", "f.js", 1, 1), "qabc", "empty old inserts at start");
  assertEqual(string.replace.$f("a.b", ".", "-", "f.js", 1, 1), "a-b", "dot is literal, never a pattern");
  assertEqual(string.replace.$f("a%b", "%", "x", "f.js", 1, 1), "axb", "% in the replacement is literal");
});

test("string.split edge cases", function() {
  assertArrayEqual(string.split.$f("", ",", "f.js", 1, 1), [""], "empty s with nonempty separator");
  assertArrayEqual(string.split.$f("", "", "f.js", 1, 1), [], "empty s with empty separator");
  assertArrayEqual(string.split.$f("aé中😀", "", "f.js", 1, 1), ["a", "é", "中", "\ud83d", "\ude00"],
    "empty separator -> one UTF-16 unit per part");
  assertArrayEqual(string.split.$f("a,b,", ",", "f.js", 1, 1), ["a", "b", ""],
    "trailing separator -> empty last element");
  assertArrayEqual(string.split.$f("a,,b", ",", "f.js", 1, 1), ["a", "", "b"],
    "consecutive separators -> empty elements");
  assertArrayEqual(string.split.$f(",a", ",", "f.js", 1, 1), ["", "a"],
    "leading separator -> empty first element");
  assertArrayEqual(string.split.$f("abc", ",", "f.js", 1, 1), ["abc"], "missing separator -> [s]");
  assertArrayEqual(string.split.$f("a,b", ",", "f.js", 1, 1), ["a", "b"], "plain split");
});

test("string.trim strips ECMAScript whitespace at both ends", function() {
  const set = " \t\n\v\f\r";
  assertEqual(string.trim.$f(set + "x" + set, "f.js", 1, 1), "x", "all six chars at both ends");
  assertEqual(string.trim.$f(" \t\n\v\f\r", "f.js", 1, 1), "", "all-whitespace input");
  assertEqual(string.trim.$f("", "f.js", 1, 1), "", "empty input");
  assertEqual(string.trim.$f("a\tb c\nd", "f.js", 1, 1), "a\tb c\nd", "internal whitespace preserved");
  assertEqual(string.trim.$f("  x", "f.js", 1, 1), "x", "leading only");
  assertEqual(string.trim.$f("x  ", "f.js", 1, 1), "x", "trailing only");
  assertEqual(string.trim.$f("\u00A0x\u00A0", "f.js", 1, 1), "x",
    "NBSP is ECMAScript whitespace");
});

// ===========================================================================
// std/table parity
// ===========================================================================

test("table.keys returns the insertion-ordered string keys of a validated Map", function() {
  assertArrayEqual(table.keys.$f(rt.makeTable({}), "f.js", 1, 1), [], "empty table");
  const t = rt.makeTable({ a: 1, b: 2 });
  assertArrayEqual(table.keys.$f(t, "f.js", 1, 1), ["a", "b"], "insertion order");
  const t2 = rt.makeTable({});
  t2.set("2", "two");
  t2.set("1", "one");
  assertArrayEqual(table.keys.$f(t2, "f.js", 1, 1), ["2", "1"], "set order is insertion order");
  // A present null-valued key remains present independently of value.
  const t3 = rt.makeTable({ a: 1 });
  t3.set("n", null);
  assertArrayEqual(table.keys.$f(t3, "f.js", 1, 1), ["a", "n"],
    "a present null-valued key stays a key");
});

// ===========================================================================
// JSON.parse: scanning, contextual reconstruction and boundary checks
// ===========================================================================

test("JSON.parse rejects malformed JSON with the E8001 parse-error envelope", function() {
  const err = assertErrorMessage(function() { decodeUnknown("not json"); }, "E8001", "JSON parse error");
  assert(err.message.indexOf("JSON parse error") === 0, "the message starts with the parse-error prefix");
});

test("JSON.parse accepts any root while contextual Record rejects non-objects", function() {
  assertEqual(decodeUnknown("42"), 42, "uncontextualized numeric root");
  assertEqual(decodeUnknown('"x"'), "x", "uncontextualized string root");
  assertEqual(decodeUnknown("true"), true, "uncontextualized boolean root");
  assertEqual(decodeUnknown("null"), null, "uncontextualized null root");
  assertArrayEqual(decodeUnknown("[1,2]"), [1,2], "uncontextualized array root");
  assertArrayEqual(decodeUnknown("[]"), [], "uncontextualized empty array");
  const errNumber = assertErrorMessage(function() { decodeRecord("42"); }, "E8001", "expected table");
  assertEqual(errNumber.actual, "number", "scalar number actual kind (the value-derived number token)");
  const errString = assertErrorMessage(function() { decodeRecord('"x"'); }, "E8001", "expected table");
  assertEqual(errString.actual, "string", "scalar string actual kind");
  const errBoolean = assertErrorMessage(function() { decodeRecord("true"); }, "E8001", "expected table");
  assertEqual(errBoolean.actual, "boolean", "scalar boolean actual kind");
  // Record context reconstructs only object roots. Arrays and language null
  // retain their actual kinds at the parse expression, not a wrapper exit.
  const errArray = assertErrorMessage(function() { decodeRecord("[1,2]"); }, "E8001", "expected table");
  assertEqual(errArray.expected, "table", "top-level array expected token");
  assertEqual(errArray.actual, "array", "top-level array actual kind");
  const errNull = assertErrorMessage(function() { decodeRecord("null"); }, "E8001", "expected table");
  assertEqual(errNull.expected, "table", "top-level null expected token");
  assertEqual(errNull.actual, "null", "top-level null actual kind");
  const errEmpty = assertErrorMessage(function() { decodeRecord("[]"); }, "E8001", "expected table");
  assertEqual(errEmpty.actual, "array", "empty top-level array actual kind");
});

test("the decoded JSON null is language null at every null-admitting boundary", function() {
  // The decoded null carrier is the language-null representation on the
  // decode path: the nullable table descriptor admits it as the JS null,
  // the null descriptor admits it, the conversion intrinsic reports the
  // null conversion, and nullable equality treats it as null — while a
  // plain table descriptor rejects it with the actual null token (the
  // uniform boundary outcome).
  const decodedNull = decodeUnknown("null");
  assertEqual(rt.checkNullable("record", decodedNull, "f.js", 1, 1), null,
    "?record admits the decoded null as language null");
  assertEqual(rt.checkNull(decodedNull, "f.js", 1, 1), null,
    "the null descriptor admits the decoded null");
  assert(rt.isNilEquivalent(decodedNull), "the decoded null is nil-equivalent");
  const convert = assertErrorMessage(function() { rt.checkNumber(decodedNull, "f.js", 1, 1); },
    "E8001", "expected number");
  assertEqual(convert.actual, "null", "the conversion actual token");
  const table = assertErrorMessage(function() { rt.checkTable(decodedNull, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(table.expected, "table", "the table descriptor expected token");
  assertEqual(table.actual, "null", "the table descriptor actual token");
  const decodedArray = decodeUnknown("[1]");
  assert(!rt.isNilEquivalent(decodedArray), "the decoded array is never nil-equivalent");
  const arrayTable = assertErrorMessage(function() { rt.checkTable(decodedArray, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(arrayTable.actual, "array", "the array carrier's table-descriptor token");
});

test("json.parse admits a lone high surrogate in a Record", function() {
  const t = decodeRecord('{"v":"\\uD834"}');
  assertEqual(t.get("v"), "\ud834", "exact high unit");
  assertEqual(decodeUnknown('"\\uD834"'), "\ud834", "unknown high unit root");
  assertErrorMessage(() => decodeRecord('"\\uD834"'), "E8001", "expected table");
});

test("json.parse admits a lone low surrogate in a Record", function() {
  const t = decodeRecord('{"v":"\\uDD1E"}');
  assertEqual(t.get("v"), "\udd1e", "exact low unit");
  assertEqual(decodeUnknown('"\\uDD1E"'), "\udd1e", "unknown low unit root");
  assertErrorMessage(() => decodeRecord('"\\uDD1E"'), "E8001", "expected table");
});

test("json.parse decodes each Unicode escape independently", function() {
  const t = decodeRecord('{"v":"\\uD834\\u0041"}');
  assertEqual(t.get("v"), "\ud834A", "high unit followed by ordinary unit");
  assertEqual(decodeUnknown('"\\uD834\\u0041"'), "\ud834A", "independent root units");
  assertErrorMessage(() => decodeRecord('"\\uD834\\u0041"'), "E8001", "expected table");
});

test("json.parse preserves lone units in keys and nested array elements", function() {
  const keyed = decodeRecord('{"\\uD834":1}');
  assertEqual(keyed.get("\ud834"), 1, "exact lone key");
  const nested = decodeRecord('{"a":["\\uDD1E"]}');
  assertEqual(nested.get("a")[0], "\udd1e", "exact nested unit");
  assertArrayEqual(decodeUnknown('["\\uDD1E"]'), ["\udd1e"], "unknown lone-unit array root");
  assertErrorMessage(() => decodeRecord('["\\uDD1E"]'), "E8001", "expected table");
});

test("json.parse accepts valid surrogate pairs (the combined U+1D11E pin)", function() {
  const t = decodeRecord('{"v":"\\uD834\\uDD1E"}');
  assertEqual(t.get("v"), "\uD834\uDD1E", "the pair combines to U+1D11E");
  assertEqual(t.get("v").length, 2, "the pair retains two UTF-16 units");
  assertEqual(rt.scalars(t.get("v")).length, 1, "iteration combines the pair");
  const t2 = decodeRecord('{"v":"\\uD800\\uDC00"}');
  assertEqual(t2.get("v"), "\uD800\uDC00", "boundary pair U+10000");
  const t3 = decodeRecord('{"v":"\\uDBFF\\uDFFF"}');
  assertEqual(t3.get("v"), "\uDBFF\uDFFF", "boundary pair U+10FFFF");
  // The pair is legal at any root; only a Record context rejects its shape.
  assertEqual(decodeUnknown('"\\uD834\\uDD1E"'), "\ud834\udd1e", "unknown paired root");
  const err = assertErrorMessage(function() { decodeRecord('"\\uD834\\uDD1E"'); }, "E8001",
    "expected table");
  assert(err.message.indexOf("unpaired surrogate") === -1,
    "the valid pair must not raise the unpaired-surrogate needle");
});

test("json.parse rejects in-string raw controls U+0000-U+001F with E8001", function() {
  for (let c = 0x00; c <= 0x1f; c++) {
    const text = '"' + String.fromCharCode(c) + '"';
    assertErrorMessage(function() { decodeUnknown(text); }, "E8001",
      "unexpected character");
  }
  const keyText = '{"' + String.fromCharCode(0x01) + '":1}';
  assertErrorMessage(function() { decodeRecord(keyText); }, "E8001",
    "unexpected character");
});

test("json.parse accepts escaped control forms and raw DEL", function() {
  const t = decodeRecord('{"v":"\\u0000"}');
  assertEqual(t.get("v"), "\u0000", "escaped \\u0000 decodes to the one-unit NUL string");
  const t2 = decodeRecord('{"v":"\\u007F"}');
  assertEqual(t2.get("v"), "\u007F", "escaped DEL decodes");
  const t3 = decodeRecord('{"v":"' + String.fromCharCode(0x7f) + '"}');
  assertEqual(t3.get("v"), "\u007F", "raw DEL is accepted");
});

test("json.parse decodes the RFC 8259 escape table and \\uXXXX", function() {
  const text = '"' + '\\"' + '\\\\' + '\\/' + '\\b' + '\\f' + '\\n' + '\\r' + '\\t' + '"';
  const t = decodeRecord('{"v":' + text + "}");
  assertEqual(t.get("v"), '"' + "\\" + "/" + "\b" + "\f" + "\n" + "\r" + "\t",
    "the full escape table decodes");
  const t2 = decodeRecord('{"v":"\\u00E9"}');
  assertEqual(t2.get("v"), "\u00E9", "\\u00E9 decodes");
  const t3 = decodeRecord('{"v":"\\u4E2D"}');
  assertEqual(t3.get("v"), "\u4E2D", "\\u4E2D decodes");
});

test("json.parse rejects unknown escapes and malformed \\uXXXX with E8001", function() {
  assertErrorMessage(function() { decodeUnknown('"\\q"'); }, "E8001", "JSON parse error");
  assertErrorMessage(function() { decodeUnknown('"\\u12"'); }, "E8001", "JSON parse error");
  assertErrorMessage(function() { decodeUnknown('"\\uZZZZ"'); }, "E8001", "JSON parse error");
});

// ===========================================================================
// JSON.stringify: omission, substitution, cycles and nominal traversal
// ===========================================================================

test("JSON.stringify omits stored ordinary functions without changing the Record", function() {
  const t = rt.makeTable({ a: 1, fn: string.length });
  assertEqual(encode(t), '{"a":1}', "function member omitted");
  assertEqual(t.get("fn"), string.length, "original callable identity retained");
});

test("JSON.stringify substitutes null for NaN at root, member and nested member", function() {
  assertEqual(encode(NaN), "null", "NaN root");
  assertEqual(encode(rt.makeTable({ x: NaN })), '{"x":null}', "NaN member");
  const nested = rt.makeTable({ val: NaN });
  assertEqual(encode(rt.makeTable({ data: nested })), '{"data":{"val":null}}', "nested NaN");
});

test("JSON.stringify substitutes null for both infinities in members and arrays", function() {
  for (const value of [Infinity, -Infinity]) {
    assertEqual(encode(value), "null", "infinite root");
    assertEqual(encode(rt.makeTable({ x: value })), '{"x":null}', "infinite member");
    assertEqual(encode(rt.makeTable({ data: [1, value] })), '{"data":[1,null]}', "array infinity");
  }
});

test("json.stringify rejects a self-referencing table with the cycle E8001", function() {
  const cyc = rt.makeTable({});
  cyc.set("self", cyc);
  assertErrorMessage(function() { encode(cyc); }, "E8001",
    "cyclic value cannot be encoded as JSON");
  const outer = rt.makeTable({});
  const arr = [];
  arr.push(outer);
  outer.set("a", arr);
  assertErrorMessage(function() { encode(outer); }, "E8001",
    "cyclic value cannot be encoded as JSON");
});

test("json.stringify omits undefined members and yields undefined for the root", function() {
  assertEqual(encode(rt.MISSING), undefined, "undefined root");
  assertEqual(encode(rt.makeTable({ m: rt.MISSING })), "{}", "undefined member");
});

test("json.stringify preserves lone-surrogate string values", function() {
  const encoded = encode(rt.makeTable({ v: "\uD800" }));
  assertEqual(JSON.parse(encoded).v, "\ud800", "exact lone unit roundtrip");
});

test("JSON.stringify preserves lone-surrogate Record keys and ignores array non-index keys", function() {
  const plain = rt.makeTable({ ["\uD800"]: 1 });
  assertEqual(JSON.parse(encode(plain))["\ud800"], 1, "exact lone key");
  const array = decodeUnknown("[1,2]");
  array["\uD800"] = 3;
  const decoded = JSON.parse(encode(array));
  assertEqual(Object.hasOwn(decoded, "\ud800"), false, "array non-index key omitted");
  assertEqual(array["\ud800"], 3, "unserialized property unchanged");
  assertArrayEqual(decoded, [1,2], "indexed elements retained");
});

// ===========================================================================
// JSON-shape round-trips (Verification 2)
// ===========================================================================

test("json round-trip: decoded null stringifies but remains invalid at Record parameters", function() {
  const parsed = decodeUnknown("null");
  assertEqual(encode(parsed), "null", "unknown stringify admits null");
  // Direct encoding agrees with the test-local builtin admission.
  assertEqual(rt.jsonStringifyText(parsed, [], "f.js", 1, 1), "null", "null round-trip");
  // The same carrier is rejected by every table parameter.
  const err = assertErrorMessage(function() { table.keys.$f(parsed, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(err.expected, "table", "decoded null at a table parameter expected token");
  assertEqual(err.actual, "null", "decoded null at a table parameter actual kind");
});

test("json round-trip: decoded arrays stringify but remain invalid at Record parameters", function() {
  const parsed = decodeUnknown("[1,2]");
  assertEqual(encode(parsed), "[1,2]", "unknown stringify admits arrays");
  // The decoder's native array retains its shape through direct encoding.
  assertEqual(rt.jsonStringifyText(parsed, [], "f.js", 1, 1), "[1,2]", "array round-trip");
  // A table-parameter boundary rejects it with the same token, while the
  // array boundary admits it.
  const err = assertErrorMessage(function() { table.keys.$f(parsed, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(err.expected, "table", "decoded array at a table parameter expected token");
  assertEqual(err.actual, "array", "decoded array at a table parameter actual kind");
  const strings = decodeUnknown('["1","2"]');
  assertEqual(rt.checkArray("[unknown]", strings, "f.js", 1, 1), strings,
    "the persistent unknown array boundary admits the decoded array unchanged");
  assertErrorMessage(() => rt.checkArray("[string]", strings, "f.js", 1, 1),
    "E8001", "array element descriptor mismatch");
});

// Reconstructed nested arrays retain their array kind at element boundaries.
test("an array-element failure reports a parsed nested array as array", function() {
  const doc = decodeRecord('{"values":[["x"],["y"]]}');
  const err = assertErrorMessage(function() {
    rt.checkType("[[record]]", [doc.get("values")[0]], "f.js", 10, 11);
  }, "E8003", "array element 1 type mismatch");
  assertEqual(err.expected, "[record]", "the failing element's descriptor");
  assertEqual(err.actual, "array", "the parsed array element's reference kind");
  assertSpan(err, "f.js", 10, 11);
});

test("the element-arm projection also covers a parsed-array element of a native array", function() {
  const doc = decodeRecord('{"a":["x"]}');
  const err = assertErrorMessage(function() {
    rt.checkType("[[record]]", [doc.get("a")], "f.js", 2, 5);
  }, "E8003", "array element 1 type mismatch");
  assertEqual(err.expected, "[record]", "the failing element's descriptor");
  assertEqual(err.actual, "array", "the parsed-array element's reference kind");
  assertSpan(err, "f.js", 2, 5);
});

test("a real nested array element keeps the array reference kind", function() {
  const err = assertErrorMessage(function() {
    rt.checkType("[[number]]", [[3], ["wrong-kind"]], "f.js", 1, 1);
  }, "E8003", "array element 2 type mismatch");
  assertEqual(err.expected, "[number]", "the failing element's descriptor");
  assertEqual(err.actual, "array", "a real array carrier keeps the refined array token");
});

test("json round-trip: an empty decoded array stays the JSON array", function() {
  const parsed = decodeUnknown("[]");
  assertEqual(rt.jsonStringifyText(parsed, [], "f.js", 1, 1), "[]", "empty array round-trip");
});

test("JSON round-trip: array non-index properties do not change its JSON kind", function() {
  const parsed = decodeUnknown("[1,2]");
  parsed.x = 9;
  assertEqual(encode(parsed), "[1,2]", "non-index array property omitted");
  assertEqual(parsed.x, 9, "encoding leaves the property unchanged");
  assertEqual(parsed.length, 2, "encoding leaves the length unchanged");
});

// Array absence becomes JSON null without mutating presence. This generic
// generic encoder never repairs the original array.
test("json round-trip: a missing middle index becomes null without filling the slot", function() {
  const parsed = decodeUnknown("[1,2,3]");
  delete parsed[1];
  assertEqual(JSON.stringify(JSON.parse(rt.jsonStringifyText(parsed, [], "f.js", 1, 1))), "[1,null,3]", "array hole substitution");
  assertEqual(Object.hasOwn(parsed, 1), false, "serialization never repairs the deleted slot");
});

test("json round-trip: a __proto__-keyed table round-trips through stringify and parse", function() {
  const t = rt.makeTable({ ["__proto__"]: "v", ok: true });
  const text = encode(t);
  const decoded = JSON.parse(text);
  assertEqual(decoded["__proto__"], "v", "the __proto__ key serializes as an own property");
  assertEqual(decoded.ok, true, "the sibling key serializes");
  assert(Object.keys(decoded).indexOf("__proto__") !== -1, "the output key set contains __proto__");
  const reparsed = decodeRecord(text);
  assertEqual(reparsed.get("__proto__"), "v", "the parse round-trip keeps the __proto__ entry");
  assertEqual(reparsed.get("ok"), true, "the parse round-trip keeps the sibling entry");
});

test("json.stringify traverses a root class while Record parameters retain nominal rejection", function() {
  const inst = rt.classPlan("@app.main/User", [{name:"name", descriptor:"string", optional:false}], {name:"x"}, "f.js", 9, 4);
  assertEqual(encode(inst, "f.js", 9, 4), '{"name":"x"}', "class root");
  const err = assertErrorMessage(function() { table.keys.$f(inst, "f.js", 9, 4); }, "E8001", "expected table");
  assertEqual(err.expected, "table", "Record parameter expected kind");
  assertEqual(err.actual, "@app.main/User", "nominal actual atom retained");
  assertSpan(err, "f.js", 9, 4);
  assertEqual(rt.jsonStringifyText(rt.makeTable({ u: inst }), [], "f.js", 1, 1),
    '{"u":{"name":"x"}}', "same class nested in Record");
});

test("json.stringify exposes only code and message for builtin Error", function() {
  const value = rt.errorValue("E_X", "boom");
  assertEqual(encode(value, "f.js", 3, 5), '{"code":"E_X","message":"boom"}', "Error public fields");
  const err = assertErrorMessage(function() { table.keys.$f(value, "f.js", 3, 5); }, "E8001", "expected table");
  assertEqual(err.expected, "table", "Record parameter expected kind");
  assertEqual(err.actual, "Error", "Error remains nominal");
  assertSpan(err, "f.js", 3, 5);
});

test("json.stringify traverses class instances in Record and array positions", function() {
  const inst = rt.classPlan("@app.main/User", [{name:"name", descriptor:"string", optional:false}], {name:"x"}, "f.js", 1, 1);
  const nested = rt.makeTable({ u: inst });
  assertEqual(encode(nested), '{"u":{"name":"x"}}', "Record member");
  assertEqual(encode(rt.makeTable({ u: [inst] })), '{"u":[{"name":"x"}]}', "array member");
});

// ===========================================================================
// Binary64 numeric decoding and decoded-key duplicates
// ===========================================================================

test("JSON.parse converts decimal lexemes to binary64, retaining signed zero", function() {
  const cases = [
    ["1", 1, "1"], ["1.0", 1, "1"], ["1e0", 1, "1"],
    ["100e-2", 1, "1"], ["-0.0", -0, "0"],
    ["0e999999999999999999999", 0, "0"], ["1.5", 1.5, "1.5"],
    ["2147483647.00000001", 2147483647, "2147483647"],
    ["2147483648", 2147483648, "2147483648"],
    ["-2147483648", -2147483648, "-2147483648"],
    ["-2147483649", -2147483649, "-2147483649"],
    ["-9999999999", -9999999999, "-9999999999"],
    ["1e0000000000000000001", 10, "10"],
    ["1e00000000000000000000", 1, "1"],
    ["100e-000000000000000000002", 1, "1"],
    ["-1e0000000000000000001", -10, "-10"],
    ["1e999999999999999999999", Infinity, "null"]
  ];
  for (const [lexeme, value, encoded] of cases) {
    const parsed = decodeRecord('{"v":' + lexeme + '}');
    assert(Object.is(parsed.get("v"), value), "exact binary64 value: " + lexeme);
    assertEqual(encode(parsed), '{"v":' + encoded + '}', "numeric spelling: " + lexeme);
  }
});

test("json.parse rejects decoded-key duplicates with the shared E8001 projection", function() {
  const err = assertErrorMessage(function() { decodeRecord('{"a":1,"a":2}', "f.js", 5, 7); }, "E8001",
    "JSON parse error at position 8: duplicate key");
  assertEqual(err.expected, undefined, "no expected field");
  assertEqual(err.actual, undefined, "no actual field");
  assertEqual(err.file, "f.js", "the forwarded file");
  assertEqual(err.line, 5, "the forwarded line");
  assertEqual(err.column, 7, "the forwarded column");
  // An escaped spelling of the same decoded key is a duplicate, and a
  // null first value does not exempt the second key.
  assertErrorMessage(function() { decodeRecord('{"a":null,"\\u0061":1}'); }, "E8001",
    "duplicate key");
  assertErrorMessage(function() { decodeRecord('{"\\u0061":1,"a":2}'); }, "E8001",
    "duplicate key");
  // Nested objects track their own decoded keys.
  assertErrorMessage(function() { decodeRecord('{"o":{"k":1,"k":2}}'); }, "E8001",
    "duplicate key");
  // Distinct decoded keys stay accepted.
  const ok = decodeRecord('{"a":1,"b":2}');
  assertEqual(ok.get("a"), 1, "distinct key a");
  assertEqual(ok.get("b"), 2, "distinct key b");
});

test("json.parse/json.stringify handle deep finite nesting beyond 512 levels", function() {
  let text = "1";
  for (let i = 0; i < 600; i++) {
    text = '{"d":' + text + "}";
  }
  const parsed = decodeRecord(text);
  assertEqual(encode(parsed), text,
    "a 600-deep finite object round-trips");
});

test("json.stringify admits a top-level array", function() {
  assertEqual(encode([1, 2]), "[1,2]", "array root");
});

test("JSON.stringify yields undefined for a top-level ordinary wrapper", function() {
  assertEqual(encode(string.length), undefined, "ordinary function root");
});

test("json.stringify rejects a foreign top-level plain object at unknown ingress", function() {
  const err = assertErrorMessage(function() { encode({ a: 1 }); }, "E8001", "unsupported unknown ingress");
  assertEqual(err.actual, "object", "the actual kind label");
});

test("json stringify/parse round-trip of a plain table", function() {
  const t = rt.makeTable({ a: 1, b: "hello", c: true });
  const text = encode(t);
  const decoded = JSON.parse(text);
  assertEqual(decoded.a, 1, "number value");
  assertEqual(decoded.b, "hello", "string value");
  assertEqual(decoded.c, true, "boolean value");
  const reparsed = decodeRecord(text);
  assertEqual(reparsed.get("a"), 1, "parsed number value");
  assertEqual(reparsed.get("b"), "hello", "parsed string value");
  assertEqual(reparsed.get("c"), true, "parsed boolean value");
});

// ===========================================================================
// std/math parity: native IEEE ops
// ===========================================================================

test("math.floor/ceil/sqrt/absNumber run native IEEE ops", function() {
  assertEqual(math.floor.$f(1.9, "f.js", 1, 1), 1, "floor");
  assertEqual(math.floor.$f(-1.9, "f.js", 1, 1), -2, "floor negative");
  assertEqual(math.ceil.$f(1.1, "f.js", 1, 1), 2, "ceil");
  assertEqual(math.ceil.$f(-1.1, "f.js", 1, 1), -1, "ceil negative");
  assertEqual(math.sqrt.$f(4, "f.js", 1, 1), 2, "sqrt");
  assertEqual(math.sqrt.$f(0, "f.js", 1, 1), 0, "sqrt of zero");
  assertEqual(math.absNumber.$f(-2.5, "f.js", 1, 1), 2.5, "absNumber");
  assert(Number.isNaN(math.sqrt.$f(NaN, "f.js", 1, 1)), "sqrt(NaN) passes through as NaN");
});


// ===========================================================================
// std/time parity: epoch-millisecond number return
// ===========================================================================

test("time.nowMillis returns a number above the signed-32 range", function() {
  // The declared return is number; the current epoch-millisecond value is
  // outside the signed-32 range and crosses the boundary unchanged.
  const now = time.nowMillis.$f("f.js", 1, 1);
  assertEqual(typeof now, "number", "nowMillis result kind");
  assert(now > 2147483647, "nowMillis result exceeds the signed-32 range");
});

test("time.nowMillis tracks the backend clock within its resolution", function() {
  // The reading keeps the existing second resolution, so it stays within
  // one second of Date.now() and lands on a whole second.
  const now = time.nowMillis.$f("f.js", 1, 1);
  assert(now % 1000 === 0, "nowMillis lands on a whole second");
  assert(Math.abs(now - Date.now()) <= 2000, "nowMillis is within the clock's resolution");
});

// ===========================================================================
// std/console parity: wrapper values over the captured streams
// ===========================================================================

test("console.log/error are first-class wrapper values writing to the captured streams", function() {
  assertEqual(consoleMod.log.$kind, "function", "log $kind");
  assertEqual(consoleMod.log.$sig, "(string)->void", "log signature");
  assert(typeof consoleMod.log.$f === "function", "log $f is callable");
  assertEqual(consoleMod.log.$f("x", "f.js", 1, 1), undefined, "log returns void");
  assertEqual(consoleMod.error.$kind, "function", "error $kind");
  assertEqual(consoleMod.error.$sig, "(string)->void", "error signature");
  assert(typeof consoleMod.error.$f === "function", "error $f is callable");
  assertEqual(consoleMod.error.$f("e", "f.js", 1, 1), undefined, "error returns void");
});

// ===========================================================================
// The canonical hex-float spelling (Double.toHexString parity)
// ===========================================================================

test("numberHex publishes the canonical hex-float spellings", function() {
  assertEqual(rt.numberHex(1.0), "0x1.0p0", "1.0");
  assertEqual(rt.numberHex(-1.0), "-0x1.0p0", "-1.0");
  assertEqual(rt.numberHex(0.5), "0x1.0p-1", "0.5");
  assertEqual(rt.numberHex(-0.5), "-0x1.0p-1", "-0.5");
  assertEqual(rt.numberHex(0.0), "0x0.0p0", "0.0");
  assertEqual(rt.numberHex(-0.0), "-0x0.0p0", "-0.0");
  assertEqual(rt.numberHex(3.0), "0x1.8p1", "3.0");
  assertEqual(rt.numberHex(0.1), "0x1.999999999999ap-4", "0.1");
  assertEqual(rt.numberHex(0.0 / 0.0), "NaN", "NaN");
  assertEqual(rt.numberHex(1.0 / 0.0), "Infinity", "Infinity");
  assertEqual(rt.numberHex(-1.0 / 0.0), "-Infinity", "-Infinity");
  assertEqual(rt.numberHex(5e-324), "0x0.0000000000001p-1022", "MIN_VALUE");
  assertEqual(rt.numberHex(-5e-324), "-0x0.0000000000001p-1022", "-MIN_VALUE");
});

// ===========================================================================
// Summary
// ===========================================================================

console.log("");
console.log("========================================");
console.log("Stdlib JS Results: " + passed + " passed, " + failed + " failed");
console.log("========================================");

if (failed > 0) {
  throw new Error("standard library JS tests failed: " + failed + " of " + (passed + failed) + " cases");
}
