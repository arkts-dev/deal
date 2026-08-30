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
// The six modules are required extensionless from the repo-root std/
// directory, and each module's own "../deal/runtime" specifier resolves
// the repo-root deal/runtime.js (the dual-location loadability of
// js-stdlib-modules D1). All behavior is exercised through the wrappers'
// .$f entries with forwarded span arguments and through the committed
// $rt surface ($dealCode for thrown checks). Hostile JSON inputs (raw
// controls, lone surrogates) are built at runtime with String.fromCharCode
// and \u escapes — no raw hostile byte is committed literally to this
// file (the test_stdlib.lua convention).

const rt = require("./deal/runtime");
const consoleMod = require("./std/console");
const string = require("./std/string");
const table = require("./std/table");
const json = require("./std/json");
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
// Module surfaces (the epic's all-six-modules criterion: exact member sets,
// pinned signature strings, wrapper shapes)
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

test("std/json loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(json)), JSON.stringify(["stringify", "parse"]), "json member set");
});

test("std/math loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(math)), JSON.stringify(
    ["floor", "ceil", "sqrt", "absInt", "absNumber", "minInt", "maxInt"]),
    "math member set");
});

test("std/time loads and exports exactly the .d.deal member set", function() {
  assertEqual(JSON.stringify(Object.keys(time)), JSON.stringify(["nowMillis"]), "time member set");
});

test("every stdlib member is a $rt.function wrapper with the pinned signature", function() {
  const surfaces = [
    [consoleMod, "std/console", { log: "(string)->null", error: "(string)->null" }],
    [string, "std/string", {
      length: "(string)->int",
      substring: "(string,int,int)->string",
      contains: "(string,string)->boolean",
      startsWith: "(string,string)->boolean",
      endsWith: "(string,string)->boolean",
      replace: "(string,string,string)->string",
      split: "(string,string)->[string]",
      trim: "(string)->string",
    }],
    [table, "std/table", { keys: "(table)->[string]" }],
    [json, "std/json", { stringify: "(table)->string", parse: "(string)->table" }],
    [math, "std/math", {
      floor: "(number)->number",
      ceil: "(number)->number",
      sqrt: "(number)->number",
      absInt: "(int)->int",
      absNumber: "(number)->number",
      minInt: "(int,int)->int",
      maxInt: "(int,int)->int",
    }],
    [time, "std/time", { nowMillis: "()->int" }],
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
  assertEqual(err.actual, "number", "console.log actual kind");
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

test("string.substring rejects a non-int start with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { string.substring.$f("a", 1.5, 2, "t4.deal", 7, 8); }, "E8001");
  assertEqual(err.message, "expected int, got non-integer number", "string.substring entry message");
  assertSpan(err, "t4.deal", 7, 8);
});

test("table.keys rejects a non-table with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { table.keys.$f("x", "t5.deal", 9, 10); }, "E8001");
  assertEqual(err.message, "expected table", "table.keys entry message");
  assertSpan(err, "t5.deal", 9, 10);
});

test("json.parse rejects a non-string with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { json.parse.$f(42, "t6.deal", 11, 12); }, "E8001");
  assertEqual(err.message, "expected string", "json.parse entry message");
  assertSpan(err, "t6.deal", 11, 12);
});

test("math.floor rejects a non-number with E8001 and the forwarded span", function() {
  const err = assertErrorCode(function() { math.floor.$f("x", "t7.deal", 13, 14); }, "E8001");
  assertEqual(err.message, "expected number", "math.floor entry message");
  assertSpan(err, "t7.deal", 13, 14);
});

test("math.sqrt of a negative number raises E8001 with the expected/actual pair", function() {
  const err = assertErrorCode(function() { math.sqrt.$f(-1, "t8.deal", 15, 16); }, "E8001");
  assertEqual(err.message, "sqrt of negative number", "math.sqrt message");
  assertEqual(err.expected, "non-negative number", "math.sqrt expected");
  assertEqual(err.actual, "-1", "math.sqrt actual");
  assertSpan(err, "t8.deal", 15, 16);
});

test("valid calls return values that re-validate through the $rt checks", function() {
  rt.checkNull(consoleMod.log.$f("x", "f.js", 1, 1), "f.js", 1, 1);
  assertEqual(rt.checkInt(string.length.$f("aé中😀b", "f.js", 1, 1), "f.js", 1, 1), 5, "length result");
  assertEqual(rt.checkString(string.substring.$f("abc", 1, 3, "f.js", 1, 1), "f.js", 1, 1), "bc", "substring result");
  assertEqual(rt.checkBoolean(string.contains.$f("abc", "b", "f.js", 1, 1), "f.js", 1, 1), true, "contains result");
  assertEqual(rt.checkString(string.replace.$f("a-a", "a", "x", "f.js", 1, 1), "f.js", 1, 1), "x-x", "replace result");
  rt.checkArray("[string]", string.split.$f("a,b", ",", "f.js", 1, 1), "f.js", 1, 1);
  assertEqual(rt.checkString(string.trim.$f("  x  ", "f.js", 1, 1), "f.js", 1, 1), "x", "trim result");
  rt.checkArray("[string]", table.keys.$f(rt.makeTable({}), "f.js", 1, 1), "f.js", 1, 1);
  rt.checkTable(json.parse.$f("{}", "f.js", 1, 1), "f.js", 1, 1);
  rt.checkString(json.stringify.$f(rt.makeTable({ a: 1 }), "f.js", 1, 1), "f.js", 1, 1);
  assertEqual(rt.checkNumber(math.floor.$f(1.9, "f.js", 1, 1), "f.js", 1, 1), 1, "floor result");
  assertEqual(rt.checkInt(math.absInt.$f(-3, "f.js", 1, 1), "f.js", 1, 1), 3, "absInt result");
  assertEqual(rt.checkInt(math.minInt.$f(2, 3, "f.js", 1, 1), "f.js", 1, 1), 2, "minInt result");
  rt.checkInt(time.nowMillis.$f("f.js", 1, 1), "f.js", 1, 1);
});

// ===========================================================================
// std/string parity: scalar semantics, plain-text search/replace, %s trim
// ===========================================================================

test("string.length counts Unicode scalar values", function() {
  assertEqual(string.length.$f("aé中😀b", "f.js", 1, 1), 5, "length of aé中😀b");
  assertEqual(string.length.$f("", "f.js", 1, 1), 0, "length of empty");
  assertEqual(string.length.$f("😀", "f.js", 1, 1), 1, "a supplementary character is one scalar");
});

test("string.substring indexes scalar positions", function() {
  const s = "aé中😀b";
  assertEqual(string.substring.$f(s, 1, 4, "f.js", 1, 1), "é中😀", "scalar positions 1..3");
  assertEqual(string.substring.$f(s, 0, 5, "f.js", 1, 1), s, "the full span");
  assertEqual(string.substring.$f("😀x", 0, 1, "f.js", 1, 1), "😀", "a supplementary scalar at position 0");
});

test("string.substring clamps per the v1.2 rules", function() {
  const s = "aé中😀b";
  assertEqual(string.substring.$f(s, -1, 3, "f.js", 1, 1), "aé中", "negative start behaves as 0");
  assertEqual(string.substring.$f(s, 1, -1, "f.js", 1, 1), "", "negative end yields empty");
  assertEqual(string.substring.$f(s, 2, 100, "f.js", 1, 1), "中😀b", "out-of-range end clamps");
  assertEqual(string.substring.$f(s, 3, 3, "f.js", 1, 1), "", "start == end yields empty");
  assertEqual(string.substring.$f(s, 4, 2, "f.js", 1, 1), "", "start > end yields empty");
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

test("string.replace replaces every occurrence plain-text", function() {
  assertEqual(string.replace.$f("a-b-a", "a", "x", "f.js", 1, 1), "x-b-x", "every occurrence");
  assertEqual(string.replace.$f("abc", "z", "q", "f.js", 1, 1), "abc", "no match returns the original");
  assertEqual(string.replace.$f("abc", "", "q", "f.js", 1, 1), "abc", "empty old returns s unchanged");
  assertEqual(string.replace.$f("a.b", ".", "-", "f.js", 1, 1), "a-b", "dot is literal, never a pattern");
  assertEqual(string.replace.$f("a%b", "%", "x", "f.js", 1, 1), "axb", "% in the replacement is literal");
});

test("string.split edge cases", function() {
  assertArrayEqual(string.split.$f("", ",", "f.js", 1, 1), [], "empty s -> empty array whatever the separator");
  assertArrayEqual(string.split.$f("", "", "f.js", 1, 1), [], "empty s with empty separator");
  assertArrayEqual(string.split.$f("aé中😀", "", "f.js", 1, 1), ["a", "é", "中", "😀"],
    "empty separator -> one scalar per part");
  assertArrayEqual(string.split.$f("a,b,", ",", "f.js", 1, 1), ["a", "b", ""],
    "trailing separator -> empty last element");
  assertArrayEqual(string.split.$f("a,,b", ",", "f.js", 1, 1), ["a", "", "b"],
    "consecutive separators -> empty elements");
  assertArrayEqual(string.split.$f(",a", ",", "f.js", 1, 1), ["", "a"],
    "leading separator -> empty first element");
  assertArrayEqual(string.split.$f("abc", ",", "f.js", 1, 1), ["abc"], "missing separator -> [s]");
  assertArrayEqual(string.split.$f("a,b", ",", "f.js", 1, 1), ["a", "b"], "plain split");
});

test("string.trim strips exactly the Lua %s set at both ends", function() {
  const set = " \t\n\v\f\r";
  assertEqual(string.trim.$f(set + "x" + set, "f.js", 1, 1), "x", "all six chars at both ends");
  assertEqual(string.trim.$f(" \t\n\v\f\r", "f.js", 1, 1), "", "all-whitespace input");
  assertEqual(string.trim.$f("", "f.js", 1, 1), "", "empty input");
  assertEqual(string.trim.$f("a\tb c\nd", "f.js", 1, 1), "a\tb c\nd", "internal whitespace preserved");
  assertEqual(string.trim.$f("  x", "f.js", 1, 1), "x", "leading only");
  assertEqual(string.trim.$f("x  ", "f.js", 1, 1), "x", "trailing only");
  assertEqual(string.trim.$f("\u00A0x\u00A0", "f.js", 1, 1), "\u00A0x\u00A0",
    "NBSP is outside the %s set and preserved");
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
});

// ===========================================================================
// std/json.parse: decode-side pins and shape conversion
// ===========================================================================

test("json.parse rejects malformed JSON with the E8001 parse-error envelope", function() {
  const err = assertErrorMessage(function() { json.parse.$f("not json", "f.js", 1, 1); }, "E8001", "JSON parse error");
  assert(err.message.indexOf("JSON parse error") === 0, "the message starts with the parse-error prefix");
});

test("json.parse rejects a top-level scalar with E8001 expected table", function() {
  const errNumber = assertErrorMessage(function() { json.parse.$f("42", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errNumber.actual, "number", "scalar number actual kind");
  const errString = assertErrorMessage(function() { json.parse.$f('"x"', "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errString.actual, "string", "scalar string actual kind");
  const errBoolean = assertErrorMessage(function() { json.parse.$f("true", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errBoolean.actual, "boolean", "scalar boolean actual kind");
});

test("json.parse rejects a lone high surrogate \\uD834 with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\uD834"', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate code unit in unicode escape");
});

test("json.parse rejects a lone low surrogate \\uDD1E with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\uDD1E"', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate code unit in unicode escape");
});

test("json.parse rejects a surrogate pair mismatch \\uD834\\u0041 with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\uD834\\u0041"', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate code unit in unicode escape");
});

test("json.parse rejects lone surrogate escapes in an object key and a nested array element", function() {
  assertErrorMessage(function() { json.parse.$f('{"\\uD834":1}', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate code unit in unicode escape");
  assertErrorMessage(function() { json.parse.$f('["\\uDD1E"]', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate code unit in unicode escape");
});

test("json.parse accepts valid surrogate pairs (the combined U+1D11E pin)", function() {
  const t = json.parse.$f('{"v":"\\uD834\\uDD1E"}', "f.js", 1, 1);
  assertEqual(t.get("v"), "\uD834\uDD1E", "the pair combines to U+1D11E");
  assertEqual(rt.scalarLength(t.get("v")), 1, "the combined pair is one scalar value");
  const t2 = json.parse.$f('{"v":"\\uD800\\uDC00"}', "f.js", 1, 1);
  assertEqual(t2.get("v"), "\uD800\uDC00", "boundary pair U+10000");
  const t3 = json.parse.$f('{"v":"\\uDBFF\\uDFFF"}', "f.js", 1, 1);
  assertEqual(t3.get("v"), "\uDBFF\uDFFF", "boundary pair U+10FFFF");
  // The top-level form passes the surrogate scan and then fails the
  // declared-return table check — the pair is accepted, not scan-rejected.
  const err = assertErrorMessage(function() { json.parse.$f('"\\uD834\\uDD1E"', "f.js", 1, 1); }, "E8001",
    "expected table");
  assert(err.message.indexOf("unpaired surrogate") === -1,
    "the valid pair must not raise the unpaired-surrogate needle");
});

test("json.parse rejects in-string raw controls U+0000-U+001F with E8001", function() {
  for (let c = 0x00; c <= 0x1f; c++) {
    const text = '"' + String.fromCharCode(c) + '"';
    assertErrorMessage(function() { json.parse.$f(text, "f.js", 1, 1); }, "E8001",
      "raw control character in string (must be escaped)");
  }
  const keyText = '{"' + String.fromCharCode(0x01) + '":1}';
  assertErrorMessage(function() { json.parse.$f(keyText, "f.js", 1, 1); }, "E8001",
    "raw control character in string (must be escaped)");
});

test("json.parse accepts escaped control forms and raw DEL", function() {
  const t = json.parse.$f('{"v":"\\u0000"}', "f.js", 1, 1);
  assertEqual(t.get("v"), "\u0000", "escaped \\u0000 decodes to the one-unit NUL string");
  const t2 = json.parse.$f('{"v":"\\u007F"}', "f.js", 1, 1);
  assertEqual(t2.get("v"), "\u007F", "escaped DEL decodes");
  const t3 = json.parse.$f('{"v":"' + String.fromCharCode(0x7f) + '"}', "f.js", 1, 1);
  assertEqual(t3.get("v"), "\u007F", "raw DEL is accepted");
});

test("json.parse decodes the RFC 8259 escape table and \\uXXXX", function() {
  const text = '"' + '\\"' + '\\\\' + '\\/' + '\\b' + '\\f' + '\\n' + '\\r' + '\\t' + '"';
  const t = json.parse.$f('{"v":' + text + "}", "f.js", 1, 1);
  assertEqual(t.get("v"), '"' + "\\" + "/" + "\b" + "\f" + "\n" + "\r" + "\t",
    "the full escape table decodes");
  const t2 = json.parse.$f('{"v":"\\u00E9"}', "f.js", 1, 1);
  assertEqual(t2.get("v"), "\u00E9", "\\u00E9 decodes");
  const t3 = json.parse.$f('{"v":"\\u4E2D"}', "f.js", 1, 1);
  assertEqual(t3.get("v"), "\u4E2D", "\\u4E2D decodes");
});

test("json.parse rejects unknown escapes and malformed \\uXXXX with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\q"', "f.js", 1, 1); }, "E8001", "JSON parse error");
  assertErrorMessage(function() { json.parse.$f('"\\u12"', "f.js", 1, 1); }, "E8001", "JSON parse error");
  assertErrorMessage(function() { json.parse.$f('"\\uZZZZ"', "f.js", 1, 1); }, "E8001", "JSON parse error");
});

// ===========================================================================
// std/json.stringify: validation arms
// ===========================================================================

test("json.stringify rejects a stored wrapper with the function E8001", function() {
  const t = rt.makeTable({ a: 1, fn: json.parse });
  assertErrorMessage(function() { json.stringify.$f(t, "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: function");
});

test("json.stringify rejects NaN with E8001", function() {
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ x: NaN }), "f.js", 1, 1); }, "E8001",
    "cannot encode NaN as JSON");
  const nested = rt.makeTable({ val: NaN });
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ data: nested }), "f.js", 1, 1); }, "E8001",
    "cannot encode NaN as JSON");
});

test("json.stringify rejects Infinity with E8001", function() {
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ x: Infinity }), "f.js", 1, 1); }, "E8001",
    "cannot encode Infinity as JSON");
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ x: -Infinity }), "f.js", 1, 1); }, "E8001",
    "cannot encode Infinity as JSON");
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ data: [1, Infinity] }), "f.js", 1, 1); }, "E8001",
    "cannot encode Infinity as JSON");
});

test("json.stringify rejects a self-referencing table with the cycle E8001", function() {
  const cyc = rt.makeTable({});
  cyc.set("self", cyc);
  assertErrorMessage(function() { json.stringify.$f(cyc, "f.js", 1, 1); }, "E8001",
    "circular reference in JSON encoding");
  const outer = rt.makeTable({});
  const arr = [];
  arr.push(outer);
  outer.set("a", arr);
  assertErrorMessage(function() { json.stringify.$f(outer, "f.js", 1, 1); }, "E8001",
    "circular reference in JSON encoding");
});

test("json.stringify rejects a standalone MISSING with the unsupported-type E8001", function() {
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ m: rt.MISSING }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding");
});

test("json.stringify rejects a bytes value with the explicit bytes E8001 arm", function() {
  const b = rt.bytes(2, "f.js", 1, 1);
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ data: b }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: bytes");
  // The arm is the pinned message through every reachable nesting
  // depth (table field, array element, nested table).
  const nested = rt.makeTable({ inner: rt.makeTable({ deep: b }) });
  assertErrorMessage(function() { json.stringify.$f(nested, "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: bytes");
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ arr: [b] }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: bytes");
});

test("json.stringify rejects unpaired-surrogate string values with E8001", function() {
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ v: "\uD800" }), "f.js", 1, 1); }, "E8001",
    "cannot encode invalid UTF-8 as JSON");
});

test("json.stringify rejects unpaired-surrogate Map keys with E8001 (plain and mixed-key array-marked)", function() {
  const plain = rt.makeTable({ ["\uD800"]: 1 });
  assertErrorMessage(function() { json.stringify.$f(plain, "f.js", 1, 1); }, "E8001",
    "cannot encode invalid UTF-8 as JSON");
  const marked = json.parse.$f("[1,2]", "f.js", 1, 1);
  marked.set("\uD800", 3);
  assertErrorMessage(function() { json.stringify.$f(marked, "f.js", 1, 1); }, "E8001",
    "cannot encode invalid UTF-8 as JSON");
});

// ===========================================================================
// JSON-shape round-trips (Verification 2)
// ===========================================================================

test("json round-trip: stringify(parse(\"null\")) === \"null\" and keys []", function() {
  const parsed = json.parse.$f("null", "f.js", 1, 1);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), "null", "null round-trip");
  assertArrayEqual(table.keys.$f(parsed, "f.js", 1, 1), [], "keys of a parsed null");
});

test("json round-trip: stringify(parse(\"[1,2]\")) === \"[1,2]\" and keys [\"1\",\"2\"]", function() {
  const parsed = json.parse.$f("[1,2]", "f.js", 1, 1);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), "[1,2]", "array round-trip");
  assertArrayEqual(table.keys.$f(parsed, "f.js", 1, 1), ["1", "2"], "keys of a parsed array");
});

test("json round-trip: a mixed-key mutation falls back to the object form", function() {
  const parsed = json.parse.$f("[1,2]", "f.js", 1, 1);
  parsed.set("x", 9);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), '{"1":1,"2":2,"x":9}', "mixed-key object form");
});

test("json round-trip: a missing middle index emits null", function() {
  const parsed = json.parse.$f("[1,2,3]", "f.js", 1, 1);
  parsed.delete("2");
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), "[1,null,3]", "missing index as null");
});

test("json round-trip: a __proto__-keyed table round-trips through stringify and parse", function() {
  const t = rt.makeTable({ ["__proto__"]: "v", ok: true });
  const text = json.stringify.$f(t, "f.js", 1, 1);
  const decoded = JSON.parse(text);
  assertEqual(decoded["__proto__"], "v", "the __proto__ key serializes as an own property");
  assertEqual(decoded.ok, true, "the sibling key serializes");
  assert(Object.keys(decoded).indexOf("__proto__") !== -1, "the output key set contains __proto__");
  const reparsed = json.parse.$f(text, "f.js", 1, 1);
  assertEqual(reparsed.get("__proto__"), "v", "the parse round-trip keeps the __proto__ entry");
  assertEqual(reparsed.get("ok"), true, "the parse round-trip keeps the sibling entry");
});

test("json.stringify encodes a class instance through the widened entry gate", function() {
  const defaults = function() {
    return { ["name"]: "default-name", ["__proto__"]: "proto-v", ["opt"]: rt.MISSING, ["nick"]: null };
  };
  const inst = rt.makeClass("User", "@app.main/User", defaults, { ["name"]: "x" }, "f.js", 9, 4);
  // The instance carries its own runtime tag properties — the field walk
  // must exclude exactly these from the output.
  assert(Object.keys(inst).indexOf("$kind") !== -1, "the instance carries the $kind tag");
  assert(Object.keys(inst).indexOf("$classname") !== -1, "the instance carries the $classname tag");

  const text = json.stringify.$f(inst, "f.js", 9, 4);
  const decoded = JSON.parse(text);
  assertEqual(JSON.stringify(Object.keys(decoded).sort()),
    JSON.stringify(["__classname", "__kind", "__proto__", "name", "nick"].sort()),
    "the output key set is exactly the tags plus present fields");
  assert(decoded.__kind !== undefined && decoded.__kind === "class", "the __kind tag");
  assertEqual(decoded.__classname, "@app.main/User", "the __classname tag");
  assertEqual(decoded.name, "x", "the provided field");
  assertEqual(decoded["__proto__"], "proto-v", "the __proto__-named declared field emits as an ordinary key");
  assertEqual(decoded.nick, null, "a null field serializes as JSON null");
  assert(!Object.prototype.hasOwnProperty.call(decoded, "opt"), "the MISSING optional is omitted");
  assert(!Object.prototype.hasOwnProperty.call(decoded, "$kind"), "no $kind key in the output");
  assert(!Object.prototype.hasOwnProperty.call(decoded, "$classname"), "no $classname key in the output");

  const reparsed = json.parse.$f(text, "f.js", 9, 4);
  assertEqual(reparsed.get("__kind"), "class", "the tag round-trips as a Map entry");
  assertEqual(reparsed.get("__classname"), "@app.main/User", "the classname round-trips");
  assertEqual(reparsed.get("__proto__"), "proto-v", "the __proto__ entry round-trips as a Map entry");
  assertEqual(reparsed.get("name"), "x", "the field round-trips");
  assert(rt.isJsonNullTable(reparsed.get("nick")), "the null field round-trips as a null-marked Map");
  assertEqual(json.stringify.$f(reparsed, "f.js", 9, 4).indexOf('"nick":null') !== -1, true,
    "the null field re-emits as JSON null");
  assert(!reparsed.has("opt"), "the omitted optional stays absent");
});

test("json.stringify of a table holding a class instance emits tags and fields nested", function() {
  const defaults = function() { return { ["name"]: "n" }; };
  const inst = rt.makeClass("User", "@app.main/User", defaults, { ["name"]: "x" }, "f.js", 1, 1);
  const t = rt.makeTable({ u: inst });
  const text = json.stringify.$f(t, "f.js", 1, 1);
  const decoded = JSON.parse(text);
  assertEqual(decoded.u.__kind, "class", "the nested instance's __kind tag");
  assertEqual(decoded.u.__classname, "@app.main/User", "the nested instance's __classname tag");
  assertEqual(decoded.u.name, "x", "the nested instance's field");
  assert(!Object.prototype.hasOwnProperty.call(decoded.u, "$kind"), "no $kind key nested");
  assert(!Object.prototype.hasOwnProperty.call(decoded.u, "$classname"), "no $classname key nested");
});

test("json.stringify rejects a top-level Array with E8001 expected table (actual array)", function() {
  const err = assertErrorMessage(function() { json.stringify.$f([1, 2], "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(err.actual, "array", "the actual kind label");
});

test("json.stringify rejects a top-level wrapper with E8001 expected table (actual function)", function() {
  const err = assertErrorMessage(function() { json.stringify.$f(json.parse, "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(err.actual, "function", "the actual kind label");
});

test("json.stringify rejects a top-level plain object through the checkTable fall-through", function() {
  const err = assertErrorMessage(function() { json.stringify.$f({ a: 1 }, "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(err.actual, "object", "the actual kind label");
});

test("json stringify/parse round-trip of a plain table", function() {
  const t = rt.makeTable({ a: 1, b: "hello", c: true });
  const text = json.stringify.$f(t, "f.js", 1, 1);
  const decoded = JSON.parse(text);
  assertEqual(decoded.a, 1, "number value");
  assertEqual(decoded.b, "hello", "string value");
  assertEqual(decoded.c, true, "boolean value");
  const reparsed = json.parse.$f(text, "f.js", 1, 1);
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

test("math.absInt/minInt/maxInt validate ints at entry and exit", function() {
  assertEqual(math.absInt.$f(-3, "f.js", 1, 1), 3, "absInt");
  assertEqual(math.absInt.$f(3, "f.js", 1, 1), 3, "absInt identity");
  assertEqual(math.minInt.$f(2, 3, "f.js", 1, 1), 2, "minInt");
  assertEqual(math.minInt.$f(-2, -3, "f.js", 1, 1), -3, "minInt negatives");
  assertEqual(math.maxInt.$f(2, 3, "f.js", 1, 1), 3, "maxInt");
  assertEqual(math.maxInt.$f(-2, -3, "f.js", 1, 1), -2, "maxInt negatives");
  assertErrorMessage(function() { math.absInt.$f("x", "f.js", 1, 1); }, "E8001", "expected int");
  assertErrorMessage(function() { math.minInt.$f(1.5, 2, "f.js", 1, 1); }, "E8001", "expected int");
  assertErrorMessage(function() { math.maxInt.$f(1, 2.5, "f.js", 1, 1); }, "E8001", "expected int");
});

// ===========================================================================
// std/time parity: second-truncated nowMillis
// ===========================================================================

test("time.nowMillis returns the second-truncated epoch milliseconds", function() {
  const before = Date.now();
  const result = time.nowMillis.$f("f.js", 1, 1);
  const after = Date.now();
  assert(typeof result === "number" && result % 1 === 0, "the result is an integer");
  assert(result % 1000 === 0, "the result is second-truncated (a multiple of 1000)");
  assert(result >= before - 1000 && result <= after,
    "the result is in the current-second window (got " + result + ", window " + before + ".." + after + ")");
});

// ===========================================================================
// std/console parity: wrapper values over the captured streams
// ===========================================================================

test("console.log/error are first-class wrapper values writing to the captured streams", function() {
  assertEqual(consoleMod.log.$kind, "function", "log $kind");
  assertEqual(consoleMod.log.$sig, "(string)->null", "log signature");
  assert(typeof consoleMod.log.$f === "function", "log $f is callable");
  assertEqual(consoleMod.log.$f("x", "f.js", 1, 1), null, "log returns DEAL null");
  assertEqual(consoleMod.error.$kind, "function", "error $kind");
  assertEqual(consoleMod.error.$sig, "(string)->null", "error signature");
  assert(typeof consoleMod.error.$f === "function", "error $f is callable");
  assertEqual(consoleMod.error.$f("e", "f.js", 1, 1), null, "error returns DEAL null");
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
