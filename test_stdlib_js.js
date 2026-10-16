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
  // The declared number return of std/time.nowMillis is the current epoch
  // millisecond count: a contemporary reading exceeds the signed-32 range
  // and crosses the number boundary unchanged (no int terminal exists).
  const now = time.nowMillis.$f("f.js", 1, 1);
  assertEqual(typeof now, "number", "nowMillis result kind");
  assert(now > 2147483647, "nowMillis result exceeds the signed-32 range");
  assertEqual(rt.checkNumber(now, "f.js", 1, 1), now, "nowMillis result re-validates");
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

test("json.parse rejects a top-level scalar, array, or null with E8001 expected table", function() {
  const errNumber = assertErrorMessage(function() { json.parse.$f("42", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errNumber.actual, "number", "scalar number actual kind");
  const errString = assertErrorMessage(function() { json.parse.$f('"x"', "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errString.actual, "string", "scalar string actual kind");
  const errBoolean = assertErrorMessage(function() { json.parse.$f("true", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errBoolean.actual, "boolean", "scalar boolean actual kind");
  // The two JSON decode carriers are rejected by the same declared
  // (string)->table exit with the shared boundary executor's own tokens:
  // a decoded JSON array is the array carrier, a decoded JSON null is
  // language null — never admitted as plain Maps (the cross-lane
  // uniform v1.3 outcome).
  const errArray = assertErrorMessage(function() { json.parse.$f("[1,2]", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errArray.expected, "table", "top-level array expected token");
  assertEqual(errArray.actual, "array", "top-level array actual kind");
  const errNull = assertErrorMessage(function() { json.parse.$f("null", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errNull.expected, "table", "top-level null expected token");
  assertEqual(errNull.actual, "null", "top-level null actual kind");
  const errEmpty = assertErrorMessage(function() { json.parse.$f("[]", "f.js", 1, 1); }, "E8001", "expected table");
  assertEqual(errEmpty.actual, "array", "empty top-level array actual kind");
});

test("the decoded JSON null is language null at every null-admitting boundary", function() {
  // The decoded null carrier is the language-null representation on the
  // decode path: the nullable table descriptor admits it as the JS null,
  // the null descriptor admits it, the conversion intrinsic reports the
  // null conversion, and nullable equality treats it as null — while a
  // plain table descriptor rejects it with the actual null token (the
  // uniform boundary outcome).
  const decodedNull = rt.jsonParseValue(null, "f.js", 1, 1);
  assertEqual(rt.checkNullable("table", decodedNull, "f.js", 1, 1), null,
    "?table admits the decoded null as language null");
  assertEqual(rt.checkNull(decodedNull, "f.js", 1, 1), null,
    "the null descriptor admits the decoded null");
  assert(rt.isNilEquivalent(decodedNull), "the decoded null is nil-equivalent");
  const convert = assertErrorMessage(function() { rt.intConvert(decodedNull, "f.js", 1, 1); },
    "E8001", "cannot convert null to int");
  assertEqual(convert.actual, "null", "the conversion actual token");
  const table = assertErrorMessage(function() { rt.checkTable(decodedNull, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(table.expected, "table", "the table descriptor expected token");
  assertEqual(table.actual, "null", "the table descriptor actual token");
  const decodedArray = rt.jsonParseValue([1], "f.js", 1, 1);
  assert(!rt.isNilEquivalent(decodedArray), "the decoded array is never nil-equivalent");
  const arrayTable = assertErrorMessage(function() { rt.checkTable(decodedArray, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(arrayTable.actual, "array", "the array carrier's table-descriptor token");
});

test("json.parse rejects a lone high surrogate \\uD834 with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\uD834"', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate escape");
});

test("json.parse rejects a lone low surrogate \\uDD1E with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\uDD1E"', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate escape");
});

test("json.parse rejects a surrogate pair mismatch \\uD834\\u0041 with E8001", function() {
  assertErrorMessage(function() { json.parse.$f('"\\uD834\\u0041"', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate escape");
});

test("json.parse rejects lone surrogate escapes in an object key and a nested array element", function() {
  assertErrorMessage(function() { json.parse.$f('{"\\uD834":1}', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate escape");
  assertErrorMessage(function() { json.parse.$f('["\\uDD1E"]', "f.js", 1, 1); }, "E8001",
    "unpaired surrogate escape");
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
      "unexpected character");
  }
  const keyText = '{"' + String.fromCharCode(0x01) + '":1}';
  assertErrorMessage(function() { json.parse.$f(keyText, "f.js", 1, 1); }, "E8001",
    "unexpected character");
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
    "unsupported type for JSON encoding: number");
  const nested = rt.makeTable({ val: NaN });
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ data: nested }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: number");
});

test("json.stringify rejects Infinity with E8001", function() {
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ x: Infinity }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: number");
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ x: -Infinity }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: number");
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ data: [1, Infinity] }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: number");
});

test("json.stringify rejects a self-referencing table with the cycle E8001", function() {
  const cyc = rt.makeTable({});
  cyc.set("self", cyc);
  assertErrorMessage(function() { json.stringify.$f(cyc, "f.js", 1, 1); }, "E8001",
    "cyclic value cannot be encoded as JSON");
  const outer = rt.makeTable({});
  const arr = [];
  arr.push(outer);
  outer.set("a", arr);
  assertErrorMessage(function() { json.stringify.$f(outer, "f.js", 1, 1); }, "E8001",
    "cyclic value cannot be encoded as JSON");
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
    "unsupported type for JSON encoding: string");
});

test("json.stringify rejects unpaired-surrogate Map keys with E8001 (plain and mixed-key array-marked)", function() {
  const plain = rt.makeTable({ ["\uD800"]: 1 });
  assertErrorMessage(function() { json.stringify.$f(plain, "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: string");
  // The array-marked Map comes from the shared decode conversion (the
  // declared parse exit rejects a top-level array, so the decode
  // producer is the runtime member).
  const marked = rt.jsonParseValue([1, 2], "f.js", 1, 1);
  marked.set("\uD800", 3);
  assertErrorMessage(function() { json.stringify.$f(marked, "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: string");
});

// ===========================================================================
// JSON-shape round-trips (Verification 2)
// ===========================================================================

test("json round-trip: stringify of the null-marked decode carrier === \"null\"", function() {
  const parsed = rt.jsonParseValue(null, "f.js", 1, 1);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), "null", "null round-trip");
  // The decoded null is language null, not a table: a table-parameter
  // boundary (std/table.keys) rejects it with the shared token.
  const err = assertErrorMessage(function() { table.keys.$f(parsed, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(err.expected, "table", "decoded null at a table parameter expected token");
  assertEqual(err.actual, "null", "decoded null at a table parameter actual kind");
});

test("json round-trip: stringify of the array-marked decode carrier === \"[1,2]\"", function() {
  const parsed = rt.jsonParseValue([1, 2], "f.js", 1, 1);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), "[1,2]", "array round-trip");
  // The decoded array is the array carrier, not a table: a
  // table-parameter boundary rejects it with the shared token, while the
  // array boundary admits it.
  const err = assertErrorMessage(function() { table.keys.$f(parsed, "f.js", 1, 1); },
    "E8001", "expected table");
  assertEqual(err.expected, "table", "decoded array at a table parameter expected token");
  assertEqual(err.actual, "array", "decoded array at a table parameter actual kind");
  // The array boundary admits the same carrier unchanged (the decode
  // mark is inert outside the table descriptor family).
  const strings = rt.jsonParseValue(["1", "2"], "f.js", 1, 1);
  assertEqual(rt.checkArray("[string]", strings, "f.js", 1, 1), strings,
    "the array boundary admits the decoded array unchanged");
});

test("json round-trip: an empty decoded array stays the JSON array", function() {
  const parsed = rt.jsonParseValue([], "f.js", 1, 1);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), "[]", "empty array round-trip");
});

test("json round-trip: a mixed-key mutation falls back to the object form", function() {
  const parsed = rt.jsonParseValue([1, 2], "f.js", 1, 1);
  parsed.set("x", 9);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), '{"1":1,"2":2,"x":9}', "mixed-key object form");
});

test("json round-trip: a missing middle index emits null", function() {
  const parsed = rt.jsonParseValue([1, 2, 3], "f.js", 1, 1);
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

test("json.stringify rejects a root class value (no implicit table conversion)", function() {
  const defaults = function() {
    return { ["name"]: "default-name", ["opt"]: rt.MISSING, ["nick"]: null };
  };
  const inst = rt.makeClass("User", "@app.main/User", defaults, { ["name"]: "x" }, "f.js", 9, 4);
  // A class instance is a table-carried value on the carrier-kind
  // projection and never its canonical identity: the widened entry gate
  // admits the carrier, and the walk rejects it with the closed
  // unsupported-type projection (the reference's __c needle). Root class
  // values are not implicitly table arguments to std/json.stringify.
  const err = assertErrorMessage(function() { json.stringify.$f(inst, "f.js", 9, 4); }, "E8001",
    "unsupported type for JSON encoding: table");
  assertEqual(err.expected, "string, number, boolean, or table", "the arm's pinned expected text");
  assertEqual(err.actual, "table", "the carried table token");
});

test("json.stringify rejects class instances inside table content", function() {
  const defaults = function() { return { ["name"]: "n" }; };
  const inst = rt.makeClass("User", "@app.main/User", defaults, { ["name"]: "x" }, "f.js", 1, 1);
  const nested = rt.makeTable({ u: inst });
  assertErrorMessage(function() { json.stringify.$f(nested, "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: table");
  // The nested-array position rejects through the same closed arm.
  assertErrorMessage(function() { json.stringify.$f(rt.makeTable({ u: [inst] }), "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: table");
});

// ===========================================================================
// Exact JSON number classification and decoded-key duplicates (v1.3)
// ===========================================================================

test("json.parse classifies exact decimal lexemes: 1.0/1e0/-0.0 are int", function() {
  assertEqual(json.stringify.$f(json.parse.$f('{"v":1}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1}', "integer token");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":1.0}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1}', "1.0 denotes the int value");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":1e0}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1}', "1e0 denotes the int value");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":100e-2}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1}', "100e-2 denotes the int value");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":-0.0}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":0}', "-0.0 normalizes to int 0");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":0e999999999999999999999}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":0}', "an all-zero coefficient with a huge exponent is int 0");
  // A fractional token that merely rounds to an integral double keeps
  // the number variant and spells through the canonical decimal text.
  assertEqual(json.stringify.$f(json.parse.$f('{"v":1.5}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1.5}', "a fractional token stays a number");
  assertEqual(json.stringify.$f(
    json.parse.$f('{"v":2147483647.00000001}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":2.147483647E9}',
    "a rounded fractional token stays a number (canonical decimal text)");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":2147483648}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":2.147483648E9}', "an out-of-range integer token is a number");
  // The signed32 check compares the magnitude before the sign is applied:
  // a negative magnitude above 2147483648 is the number variant, never an
  // out-of-range int (the canonical text proves the variant).
  assertEqual(json.stringify.$f(json.parse.$f('{"v":-2147483648}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":-2147483648}', "the negative signed32 edge is an int");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":-2147483649}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":-2.147483649E9}', "-2147483649 is the number variant");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":-9999999999}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":-9.999999999E9}', "-9999999999 is the number variant");
  // The exponent magnitude accumulates its digits with bounded
  // saturation: leading zeroes contribute nothing, so a zero-padded
  // exponent text longer than 18 characters whose value is small stays
  // exactly classified (1e0000000000000000001 is the integral 10).
  assertEqual(json.stringify.$f(json.parse.$f('{"v":1e0000000000000000001}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":10}', "a zero-padded long exponent stays the int 10");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":1e00000000000000000000}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1}', "a zero-valued zero-padded exponent stays the int 1");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":100e-000000000000000000002}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":1}', "a zero-padded negative exponent stays the int 1");
  assertEqual(json.stringify.$f(json.parse.$f('{"v":-1e0000000000000000001}', "f.js", 1, 1), "f.js", 1, 1),
    '{"v":-10}', "a negative zero-padded long exponent stays the int -10");
  // A huge exponent converts to binary64 infinity: the classification
  // itself never overflows, and the nonfinite value then fails the
  // finite-only encode arm.
  const huge = json.parse.$f('{"v":1e999999999999999999999}', "f.js", 1, 1);
  assertEqual(huge.get("v"), Infinity, "a huge exponent converts to binary64 infinity");
  assertErrorMessage(function() { json.stringify.$f(huge, "f.js", 1, 1); }, "E8001",
    "unsupported type for JSON encoding: number");
});

test("json.parse rejects decoded-key duplicates with the shared E8001 projection", function() {
  const err = assertErrorMessage(function() { json.parse.$f('{"a":1,"a":2}', "f.js", 5, 7); }, "E8001",
    "JSON parse error at position 8: duplicate key");
  assertEqual(err.expected, undefined, "no expected field");
  assertEqual(err.actual, undefined, "no actual field");
  assertEqual(err.file, "f.js", "the forwarded file");
  assertEqual(err.line, 5, "the forwarded line");
  assertEqual(err.column, 7, "the forwarded column");
  // An escaped spelling of the same decoded key is a duplicate, and a
  // null first value does not exempt the second key.
  assertErrorMessage(function() { json.parse.$f('{"a":null,"\u0061":1}', "f.js", 1, 1); }, "E8001",
    "duplicate key");
  assertErrorMessage(function() { json.parse.$f('{"\u0061":1,"a":2}', "f.js", 1, 1); }, "E8001",
    "duplicate key");
  // Nested objects track their own decoded keys.
  assertErrorMessage(function() { json.parse.$f('{"o":{"k":1,"k":2}}', "f.js", 1, 1); }, "E8001",
    "duplicate key");
  // Distinct decoded keys stay accepted.
  const ok = json.parse.$f('{"a":1,"b":2}', "f.js", 1, 1);
  assertEqual(ok.get("a"), 1, "distinct key a");
  assertEqual(ok.get("b"), 2, "distinct key b");
});

test("json.parse/json.stringify handle deep finite nesting beyond 512 levels", function() {
  let text = "1";
  for (let i = 0; i < 600; i++) {
    text = '{"d":' + text + "}";
  }
  const parsed = json.parse.$f(text, "f.js", 1, 1);
  assertEqual(json.stringify.$f(parsed, "f.js", 1, 1), text,
    "a 600-deep finite object round-trips");
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
