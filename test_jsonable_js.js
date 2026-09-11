"use strict";

// Test suite for the DEAL JavaScript @jsonable runtime walkers
// ($rt.jsonFromJson/$rt.jsonToJson over the pinned field-descriptor
// shape, js-v12-jsonable-completion D2-D5). Run from the repo root:
//
//   node test_jsonable_js.js
//
// Dependency-free and self-asserting: no assertion library; a failed
// assertion throws with the failing case named, and the final throw makes
// the process exit non-zero, so run_tests.sh's node-guarded block fails
// the gate via set -e. The script writes no files.
//
// The suite drives deal/runtime.js directly under the real node binary —
// the walkers' contract, phase order, and failure needles, independent of
// the emitter (the emitter lane lands separately). Hostile JSON inputs
// (lone surrogates) are built at runtime with escapes — no raw hostile
// byte is committed literally to this file (the test_stdlib.lua
// convention).

const rt = require("./deal/runtime");

// ===== Harness (mirrors test_stdlib.lua / test_stdlib_js.js) =====

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

function assertNull(actual, msg) {
  assert(actual === null, msg + " (expected null, got " + String(actual) + ")");
}

// One DEAL error raised through the runtime spine.
function assertError(fn, code, needle, msg) {
  let caught = null;
  try {
    fn();
  } catch (e) {
    caught = e;
  }
  assert(caught !== null, msg + " (no error raised)");
  assertEqual(caught.$dealCode, code, msg + " (code)");
  if (needle !== undefined) {
    assert(String(caught.message).indexOf(needle) !== -1,
      msg + " (message '" + String(caught.message) + "' lacks needle '" + needle + "')");
  }
  return caught;
}

// ===== Descriptor builders (the pinned entry shape, declaration order) =====

// { name, jtype, element?, className?, fields?, optional, nullable, hasDefault }
function f(name, jtype, optional, nullable, extra) {
  const e = { name: name, jtype: jtype, optional: !!optional, nullable: !!nullable, hasDefault: true };
  if (extra !== undefined) {
    if (extra.element !== undefined) e.element = extra.element;
    if (extra.className !== undefined) e.className = extra.className;
    if (extra.fields !== undefined) e.fields = extra.fields;
  }
  return e;
}

function elem(jtype, nullable, extra) {
  const e = { jtype: jtype, optional: false, nullable: !!nullable };
  if (extra !== undefined) {
    if (extra.element !== undefined) e.element = extra.element;
    if (extra.className !== undefined) e.className = extra.className;
    if (extra.fields !== undefined) e.fields = extra.fields;
  }
  return e;
}

const LOC = ["probe.js", 1, 1];

// =========================================================================
// jsonFromJson / jsonToJson basics
// =========================================================================

test("jsonFromJson parses a document and tags the instance", function() {
  const fields = [f("name", "string", false, false), f("age", "int", false, false)];
  const thunk = () => ({ name: "", age: 0 });
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"Alice","age":30}', ...LOC);
  assert(u !== null, "fromJson returned null for valid JSON");
  assertEqual(u.$kind, "class", "instance kind tag");
  assertEqual(u.$classname, "@m/User", "instance identity tag");
  assertEqual(u.name, "Alice", "string field decoded");
  assertEqual(u.age, 30, "int field decoded");
});

test("jsonToJson serializes declared fields in declaration order", function() {
  const fields = [f("name", "string", false, false), f("age", "int", false, false)];
  const thunk = () => ({ name: "", age: 0 });
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"age":30,"name":"Alice"}', ...LOC);
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"Alice","age":30}',
    "document key order never leaks into the output (source order)");
});

test("jsonFromJson applies defaults for omitted fields", function() {
  const fields = [f("name", "string", false, false), f("age", "int", false, false)];
  const thunk = () => ({ name: "", age: 7 });
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"Bob"}', ...LOC);
  assert(u !== null, "fromJson returned null");
  assertEqual(u.age, 7, "omitted required field got its default");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"Bob","age":7}', "defaulted roundtrip");
});

test("jsonFromJson rejects extra keys in document order (null, never throws)", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"name":"Ada","extra":1}', ...LOC),
    "trailing extra key");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"extra":1,"name":"Ada"}', ...LOC),
    "leading extra key");
});

// =========================================================================
// Top-level gate and the empty collapse (D3)
// =========================================================================

test("top-level gate: scalar/null/non-empty-array documents return null", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "42", ...LOC), "number document");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '"x"', ...LOC), "string document");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "true", ...LOC), "boolean document");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "null", ...LOC), "null document");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "[1,2]", ...LOC), "non-empty array document");
});

test("empty collapse: {} and [] decode to the defaulted instance with identical toJson text", function() {
  const fields = [f("name", "string", false, false), f("age", "int", false, false)];
  const thunk = () => ({ name: "", age: 0 });
  const fromObj = rt.jsonFromJson("@m/User", fields, thunk, "{}", ...LOC);
  const fromArr = rt.jsonFromJson("@m/User", fields, thunk, "[]", ...LOC);
  assert(fromObj !== null && fromArr !== null, "collapse decode");
  const a = rt.jsonToJson("@m/User", fromObj, fields, ...LOC);
  const b = rt.jsonToJson("@m/User", fromArr, fields, ...LOC);
  assertEqual(a, b, "identical collapse texts");
  assertEqual(a, '{"name":"","age":0}', "collapse text");
});

test("malformed JSON returns null and never throws", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "{bad json", ...LOC), "truncated object");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "not valid json", ...LOC), "garbage text");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "", ...LOC), "empty input");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, "  ", ...LOC), "whitespace input");
});

test("surrogate strings never escape as exceptions from jsonFromJson", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  // A JSON-escaped lone high surrogate inside a string field.
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"name":"\\ud800"}', ...LOC),
    "escaped lone high surrogate in a field");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"name":"\\udc00"}', ...LOC),
    "escaped lone low surrogate in a field");
  // A raw unpaired surrogate in the input text itself.
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"name":"\ud800"}', ...LOC),
    "raw lone high surrogate in the input text");
  // A valid supplementary pair round-trips.
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"\\ud83d\\ude00"}', ...LOC);
  assert(u !== null, "valid pair decoded");
  assertEqual(u.name, "\uD83D\uDE00", "supplementary pair preserved");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"\uD83D\uDE00"}', "pair roundtrip");
});

// =========================================================================
// Explicit null, optional three-state, MISSING omission (D2/D5)
// =========================================================================

test("explicit null: nullable passes as present-null, non-nullable fails end-to-end", function() {
  const fields = [f("name", "string", false, false), f("bio", "string", false, true)];
  const thunk = () => ({ name: "", bio: null });
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"A","bio":null}', ...LOC);
  assert(u !== null && u.bio === null, "nullable present-null");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"A","bio":null}', "present-null text");

  const nonNullable = [f("name", "string", false, false), f("nick", "string", true, false)];
  const thunk2 = () => ({ name: "", nick: rt.MISSING });
  assertNull(rt.jsonFromJson("@m/User", nonNullable, thunk2, '{"nick":null}', ...LOC),
    "explicit null on a non-nullable optional");
});

test("optional three-state roundtrip: missing omitted, null present, value present", function() {
  const fields = [f("name", "string", false, false), f("nick", "string", true, true)];
  const thunk = () => ({ name: "", nick: rt.MISSING });

  const missing = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"A"}', ...LOC);
  assert(missing !== null && missing.nick === rt.MISSING, "missing state decodes to MISSING");
  assertEqual(rt.jsonToJson("@m/User", missing, fields, ...LOC), '{"name":"A"}', "missing state omitted by toJson");

  const nullState = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"B","nick":null}', ...LOC);
  assert(nullState !== null && nullState.nick === null, "present-null state");
  assertEqual(rt.jsonToJson("@m/User", nullState, fields, ...LOC), '{"name":"B","nick":null}', "null state text");

  const valueState = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"C","nick":"cee"}', ...LOC);
  assertEqual(rt.jsonToJson("@m/User", valueState, fields, ...LOC), '{"name":"C","nick":"cee"}', "value state text");
});

test("provided-value failure runs no defaults (counting thunk stays at zero)", function() {
  const fields = [f("a", "int", false, false), f("b", "int", false, false)];
  let runs = 0;
  const thunk = () => { runs++; return { a: 1, b: 2 }; };
  const bad = rt.jsonFromJson("@m/C", fields, thunk, '{"b":"not-an-int"}', ...LOC);
  assertNull(bad, "int kind failure");
  assertEqual(runs, 0, "defaults thunk never ran");

  // The same thunk runs exactly once on a successful construction.
  const good = rt.jsonFromJson("@m/C", fields, thunk, '{"b":20}', ...LOC);
  assert(good !== null && good.a === 1 && good.b === 20, "successful decode");
  assertEqual(runs, 1, "defaults thunk ran exactly once per construction");
});

test("a defaults-evaluator failure returns null and publishes nothing", function() {
  const fields = [f("a", "int", false, false)];
  const thunk = () => { throw rt.fail("E8001", "defaults blew up", ...LOC); };
  assertNull(rt.jsonFromJson("@m/C", fields, thunk, "{}", ...LOC), "evaluator failure collapses to null");
});

// =========================================================================
// Int range, final validation through the canonical matcher rows (D2 step 7)
// =========================================================================

test("int decode enforces the typed boundary (E8004 range) as null end-to-end", function() {
  const fields = [f("age", "int", false, false)];
  const thunk = () => ({ age: 0 });
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"age":9007199254740992}', ...LOC),
    "2^53 exceeds the safe int range");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"age":1e300}', ...LOC),
    "1e300 exceeds the safe int range");
  assertNull(rt.jsonFromJson("@m/User", fields, thunk, '{"age":3.5}', ...LOC),
    "non-integer for an int field");
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"age":-0}', ...LOC);
  assert(u !== null && u.age === 0, "-0 normalizes to 0");
});

test("final validation routes through checkType (class identity byte-compare)", function() {
  const childFields = [f("value", "int", false, false)];
  const fields = [f("name", "string", false, false),
    f("home", "class", false, false, { className: "@m/Address", fields: childFields })];
  // A defaults thunk whose class default carries the WRONG identity:
  // final validation must reject it through the canonical matcher rows.
  const wrongThunk = () => ({ name: "",
    home: rt.makeClass("Address", "@m/Other", () => ({ value: 0 }), null, ...LOC) });
  assertNull(rt.jsonFromJson("@m/User", fields, wrongThunk, '{"name":"D"}', ...LOC),
    "wrong-identity default fails final validation");
  // The correct identity passes.
  const rightThunk = () => ({ name: "",
    home: rt.makeClass("Address", "@m/Address", () => ({ value: 0 }), null, ...LOC) });
  const u = rt.jsonFromJson("@m/User", fields, rightThunk, '{"name":"D"}', ...LOC);
  assert(u !== null && u.home.$classname === "@m/Address", "correct-identity default passes");
  // A wrong-typed primitive default fails the matcher the same way.
  const badThunk = () => ({ name: 42 });
  assertNull(rt.jsonFromJson("@m/User", [f("name", "string", false, false)], badThunk, "{}", ...LOC),
    "wrong-typed string default fails final validation");
});

// =========================================================================
// Nested classes and arrays (D2 step 5)
// =========================================================================

test("nested class fields decode with the full phase order", function() {
  const childFields = [f("value", "int", false, false)];
  const fields = [f("name", "string", false, false),
    f("home", "class", false, false, { className: "@m/Address", fields: childFields })];
  const thunk = () => ({ name: "",
    home: rt.makeClass("Address", "@m/Address", () => ({ value: 0 }), null, ...LOC) });
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"name":"Dave","home":{"value":7}}', ...LOC);
  assert(u !== null && u.home.$kind === "class" && u.home.$classname === "@m/Address"
    && u.home.value === 7, "nested instance decoded and tagged");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"Dave","home":{"value":7}}',
    "nested roundtrip");
});

test("nested class extra keys and malformed elements are rejected", function() {
  const childFields = [f("value", "int", false, false)];
  const fields = [f("children", "array", false, false,
    { element: elem("class", false, { className: "@m/Child", fields: childFields }) })];
  const thunk = () => ({ children: [] });
  assertNull(rt.jsonFromJson("@m/P", fields, thunk, '{"children":[{"value":1,"extra":2}]}', ...LOC),
    "nested extra key");
  assertNull(rt.jsonFromJson("@m/P", fields, thunk, '{"children":[1]}', ...LOC),
    "malformed nested element");
  assertNull(rt.jsonFromJson("@m/P", fields, thunk, '{"children":{}}', ...LOC),
    "object where an array is declared");
  const ok = rt.jsonFromJson("@m/P", fields, thunk, '{"children":[{"value":3},{"value":4}]}', ...LOC);
  assert(ok !== null && ok.children.length === 2
    && ok.children[0].$classname === "@m/Child" && ok.children[1].value === 4,
    "array of nested classes decodes");
  assertEqual(rt.jsonToJson("@m/P", ok, fields, ...LOC),
    '{"children":[{"value":3},{"value":4}]}', "array of nested classes roundtrip");
});

test("nested class decode without defaults: absent optionals become MISSING", function() {
  const childFields = [f("value", "int", false, false), f("nick", "string", true, false)];
  const fields = [f("home", "class", false, false, { className: "@m/Address", fields: childFields })];
  const thunk = () => ({ home: {} });
  const u = rt.jsonFromJson("@m/User", fields, thunk, '{"home":{"value":5}}', ...LOC);
  assert(u !== null && u.home.value === 5 && u.home.nick === rt.MISSING,
    "nested absent optional materializes as MISSING (no nested defaults surface)");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"home":{"value":5}}',
    "nested MISSING omitted");
});

test("nullable nested class accepts null and values", function() {
  const childFields = [f("value", "int", false, false)];
  const fields = [f("child", "class", true, true, { className: "@m/Child", fields: childFields })];
  const thunk = () => ({ child: rt.MISSING });
  const explicitNull = rt.jsonFromJson("@m/Maybe", fields, thunk, '{"child":null}', ...LOC);
  assert(explicitNull !== null && explicitNull.child === null, "explicit null nested");
  const value = rt.jsonFromJson("@m/Maybe", fields, thunk, '{"child":{"value":8}}', ...LOC);
  assert(value !== null && value.child.value === 8, "nested value");
});

// =========================================================================
// Table fields: D9 Map shape, JSON-shaped end to end (D4)
// =========================================================================

test("table fields decode to the D9 Map shape and roundtrip nested arrays", function() {
  const fields = [f("data", "table", false, false)];
  const thunk = () => ({ data: rt.makeTable({}) });
  const text = '{"data":{"count":3,"values":[[1,2],[3,4]]}}';
  const u = rt.jsonFromJson("@m/W", fields, thunk, text, ...LOC);
  assert(u !== null, "table field decoded");
  const data = u.data;
  assert(data instanceof Map, "top-level table field is a Map");
  assertEqual(data.get("count"), 3, "scalar table entry");
  const values = data.get("values");
  assert(values instanceof Map && rt.isJsonArrayTable(values), "nested array is an array-marked Map");
  assert(values.get("1") instanceof Map && rt.isJsonArrayTable(values.get("1"))
    && values.get("1").get("2") === 2, "nested arrays convert as the array-marked Maps");
  assertEqual(rt.jsonToJson("@m/W", u, fields, ...LOC), text,
    "table field nested arrays roundtrip without E8001");
});

test("table fields accept only a JSON object", function() {
  const fields = [f("data", "table", false, false)];
  const thunk = () => ({ data: rt.makeTable({}) });
  assertNull(rt.jsonFromJson("@m/W", fields, thunk, '{"data":[1,2]}', ...LOC), "array for a table field");
  assertNull(rt.jsonFromJson("@m/W", fields, thunk, '{"data":5}', ...LOC), "scalar for a table field");
});

test("class instances inside table fields keep the D9 std/json encoding", function() {
  const fields = [f("data", "table", false, false)];
  const thunk = () => ({ data: rt.makeTable({}) });
  const inner = rt.makeClass("User", "@m/User", () => ({ name: "", nick: rt.MISSING }),
    { name: "Ada" }, ...LOC);
  const u = rt.jsonFromJson("@m/W", fields, thunk, '{"data":{"u":{}}}', ...LOC);
  assert(u !== null, "decode with a table field");
  u.data.set("u", inner);
  assertEqual(rt.jsonToJson("@m/W", u, fields, ...LOC),
    '{"data":{"u":{"__kind":"class","__classname":"@m/User","name":"Ada"}}}',
    "D9 tags and present fields inside table fields");
});

// =========================================================================
// toJson failures (D5): identity, nulls, cycles, NaN/Infinity, functions
// =========================================================================

test("jsonToJson identity check rejects wrong-identity and non-instances", function() {
  const fields = [f("name", "string", false, false)];
  const u = rt.makeClass("User", "@m/User", () => ({ name: "" }), null, ...LOC);
  assertError(() => rt.jsonToJson("@m/Other", u, fields, ...LOC), "E8001",
    "expected instance of @m/Other, got @m/User", "wrong identity");
  assertError(() => rt.jsonToJson("@m/User", { name: "x" }, fields, ...LOC), "E8001",
    "expected class instance", "untagged plain object");
  assertError(() => rt.jsonToJson("@m/User", 42, fields, ...LOC), "E8001",
    "expected class instance", "primitive");
});

test("jsonToJson: explicit null on a non-nullable field raises E8001", function() {
  const fields = [f("name", "string", false, false)];
  const u = rt.makeClass("User", "@m/User", () => ({ name: "" }), null, ...LOC);
  rt.setProp(u, "name", null);
  assertError(() => rt.jsonToJson("@m/User", u, fields, ...LOC), "E8001",
    "explicit null on non-nullable field 'name'", "non-nullable null");
});

test("jsonToJson: a missing required field raises E8001", function() {
  const fields = [f("name", "string", false, false)];
  const u = rt.setProp(rt.setProp({}, "$kind", "class"), "$classname", "@m/User");
  assertError(() => rt.jsonToJson("@m/User", u, fields, ...LOC), "E8001",
    "missing required field 'name'", "missing required field");
});

test("jsonToJson raises E8001 for cyclic class graphs", function() {
  const childFields = [f("parent", "class", false, true, { className: "@m/Node", fields: null })];
  const selfFields = [f("next", "class", false, true, { className: "@m/Node", fields: childFields })];
  const n = rt.makeClass("Node", "@m/Node", () => ({ next: null }), null, ...LOC);
  rt.setProp(n, "next", n);
  assertError(() => rt.jsonToJson("@m/Node", n, selfFields, ...LOC), "E8001",
    "circular reference in JSON encoding", "class self-cycle");
});

test("jsonToJson raises E8001 for cyclic table fields", function() {
  const fields = [f("data", "table", false, false)];
  const u = rt.makeClass("W", "@m/W", () => ({ data: rt.makeTable({}) }), null, ...LOC);
  const data = u.data;
  data.set("self", data);
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "circular reference in JSON encoding", "table self-cycle");
});

test("jsonToJson raises E8001 for NaN/Infinity number fields", function() {
  const fields = [f("x", "number", false, false)];
  const u = rt.makeClass("N", "@m/N", () => ({ x: 0.0 }), null, ...LOC);
  rt.setProp(u, "x", NaN);
  assertError(() => rt.jsonToJson("@m/N", u, fields, ...LOC), "E8001",
    "cannot encode NaN as JSON", "NaN field");
  rt.setProp(u, "x", Infinity);
  assertError(() => rt.jsonToJson("@m/N", u, fields, ...LOC), "E8001",
    "cannot encode Infinity as JSON", "Infinity field");
});

test("jsonToJson raises E8001 for function values inside table fields", function() {
  const fields = [f("data", "table", false, false)];
  const u = rt.makeClass("W", "@m/W", () => ({ data: rt.makeTable({}) }), null, ...LOC);
  u.data.set("f", rt.function("()->null", function() { return null; }));
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "unsupported type for JSON encoding: function", "function inside table");
});

test("jsonToJson raises E8001 for a non-JSON-shaped table value", function() {
  const fields = [f("data", "table", false, false)];
  const u = rt.makeClass("W", "@m/W", () => ({ data: rt.makeTable({}) }), null, ...LOC);
  u.data.set("bad", rt.MISSING);
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "unsupported type for JSON encoding", "MISSING inside table");
});

test("jsonToJson int fields enforce the boundary (E8004 range)", function() {
  const fields = [f("age", "int", false, false)];
  const u = rt.makeClass("User", "@m/User", () => ({ age: 0 }), null, ...LOC);
  rt.setProp(u, "age", 9007199254740992);
  assertError(() => rt.jsonToJson("@m/User", u, fields, ...LOC), "E8004",
    "int out of safe range", "out-of-range int field");
});

// =========================================================================
// Shared D9 conversion members (js-backend-runtime D9 reuse)
// =========================================================================

test("jsonParseValue/jsonEncodeValue roundtrip the marked Map shapes", function() {
  const arr = rt.jsonParseValue([1, 2], ...LOC);
  assert(arr instanceof Map && rt.isJsonArrayTable(arr), "array-marked Map");
  assertEqual(arr.get("2"), 2, "array element by string key");
  const nil = rt.jsonParseValue(null, ...LOC);
  assert(nil instanceof Map && rt.isJsonNullTable(nil), "null-marked Map");
  assertEqual(rt.jsonEncodeValue(arr, [], ...LOC)[1], 2, "encode back to a JSON array");
  assertEqual(rt.jsonEncodeValue(nil, [], ...LOC), null, "null-marked Map encodes as null");
  const obj = rt.jsonParseValue({ a: [1], n: null }, ...LOC);
  assert(obj instanceof Map && !rt.isJsonArrayTable(obj) && !rt.isJsonNullTable(obj),
    "object Map");
  const encoded = rt.jsonEncodeValue(obj, [], ...LOC);
  assertEqual(JSON.stringify(encoded), '{"a":[1],"n":null}', "object form with nested marks");
});

// =========================================================================
// $rt.classPlan: the plan-driven four-phase construction entry (ISSUE-0545)
// =========================================================================

// The pinned plan-entry shape { name, descriptor, optional, evaluator? }
// in class source order; the evaluator closures are created at load and
// never invoked there — classPlan invokes exactly the omitted required
// entries once per attempt.
function planEntry(name, descriptor, optional, evaluator) {
  const e = { name: name, descriptor: descriptor, optional: !!optional };
  if (evaluator !== undefined) {
    e.evaluator = evaluator;
  }
  return e;
}

test("classPlan tags and publishes with provided values and absent optionals", function() {
  const plan = [planEntry("name", "string", false, () => "Ada"),
    planEntry("nick", "string", true)];
  const u = rt.classPlan("@m/U", plan, { name: "Bob" }, ...LOC);
  assertEqual(u.$kind, "class", "kind tag");
  assertEqual(u.$classname, "@m/U", "identity tag");
  assertEqual(u.name, "Bob", "provided value");
  assertEqual(u.nick, rt.MISSING, "absent optional materializes as MISSING");
});

test("classPlan omitted required defaults evaluate exactly once per attempt in plan order", function() {
  const order = [];
  const plan = [planEntry("a", "int", false, () => { order.push("a"); return 1; }),
    planEntry("b", "int", false, () => { order.push("b"); return 2; })];
  const u = rt.classPlan("C", plan, null, ...LOC);
  const v = rt.classPlan("C", plan, null, ...LOC);
  assertEqual(order.join(","), "a,b,a,b", "declaration-order evaluation, once per attempt");
  assertEqual(u.a + u.b + v.a + v.b, 6, "per-attempt results");
});

test("classPlan provided fields suppress their evaluators", function() {
  let runs = 0;
  const plan = [planEntry("a", "int", false, () => { runs++; return 1; }),
    planEntry("b", "int", false, () => { runs++; return 2; })];
  const u = rt.classPlan("C", plan, { a: 5, b: 6 }, ...LOC);
  assertEqual(runs, 0, "no default ran for a provided field");
  assertEqual(u.a + u.b, 11, "provided values stored");
});

test("classPlan extra provided field raises E8007 with zero default evaluation", function() {
  let runs = 0;
  const plan = [planEntry("a", "int", false, () => { runs++; return 1; })];
  assertError(() => rt.classPlan("C", plan, { extra: 1 }, ...LOC), "E8007",
    "extra field 'extra' in class 'C'", "extra-key rejection");
  assertEqual(runs, 0, "no default evaluated before the E8007");
});

test("classPlan phase 3 validates provided fields and evaluated defaults in plan order", function() {
  const plan = [planEntry("a", "int", false, () => 1),
    planEntry("nick", "?string", true)];
  assertError(() => rt.classPlan("C", plan, { a: "not-an-int" }, ...LOC), "E8001",
    "expected int", "provided-value validation");
  assertError(() => rt.classPlan("C", plan, { a: 1, nick: 42 }, ...LOC), "E8001",
    "expected string", "nullable optional validation");
  const u = rt.classPlan("C", plan, { a: 1, nick: null }, ...LOC);
  assertEqual(u.nick, null, "explicit null on a nullable optional stays present-null");
});

test("classPlan evaluator-raised DEAL errors propagate unchanged", function() {
  const plan = [planEntry("a", "int", false, () => rt.fail("E8002", "evaluator boom", ...LOC))];
  const err = assertError(() => rt.classPlan("C", plan, null, ...LOC), "E8002",
    "evaluator boom", "evaluator failure propagates");
  assertEqual(err.$dealCode, "E8002", "error code preserved");
});

test("classPlan malformed plan shapes fail closed with E8001", function() {
  assertError(() => rt.classPlan("C", { length: 1 }, null, ...LOC), "E8001",
    "class default plan must be a table", "non-array plan");
  assertError(() => rt.classPlan("C", ["x"], null, ...LOC), "E8001",
    "malformed class default plan entry", "non-object entry");
  assertError(() => rt.classPlan("C", [{ name: 1, descriptor: "int", optional: false }], null, ...LOC),
    "E8001", "malformed class default plan entry", "non-string name");
  assertError(() => rt.classPlan("C", [{ name: "x", descriptor: 2, optional: false }], null, ...LOC),
    "E8001", "malformed class default plan entry", "non-string descriptor");
  assertError(() => rt.classPlan("C", [{ name: "x", descriptor: "int", optional: "yes" }], null, ...LOC),
    "E8001", "malformed class default plan entry", "non-boolean optional");
  assertError(() => rt.classPlan("C", [{ name: "x", descriptor: "int", optional: false, evaluator: 3 }],
    null, ...LOC), "E8001", "malformed class default plan entry", "non-function evaluator");
  assertError(() => rt.classPlan("C", [planEntry("x", "int", false, () => 1),
    planEntry("x", "int", false, () => 2)], null, ...LOC), "E8001",
    "duplicate field in class default plan: 'x'", "duplicate names");
  assertError(() => rt.classPlan("C", [planEntry("a", "int", false, () => 1)], 42, ...LOC),
    "E8001", "class field values must be a table", "non-table provided");
});

test("jsonFromDocument consumes plan-carrying per-entry evaluators (omitted required only)", function() {
  let runs = 0;
  const fields = [f("a", "int", false, false), f("b", "int", false, false)];
  fields[0].evaluator = () => { runs++; return 1; };
  fields[1].evaluator = () => { runs++; return 2; };
  // Extra keys and provided-value failures still precede every default.
  assertNull(rt.jsonFromJson("@m/C", fields, null, '{"extra":1}', ...LOC), "extra-key gate");
  assertEqual(runs, 0, "no default ran after the extra-key rejection");
  assertNull(rt.jsonFromJson("@m/C", fields, null, '{"b":"x"}', ...LOC), "decode gate");
  assertEqual(runs, 0, "no default ran after the provided-value failure");
  // Omitted required defaults evaluate exactly once per attempt.
  const u = rt.jsonFromJson("@m/C", fields, null, '{"b":20}', ...LOC);
  assert(u !== null, "valid decode");
  assertEqual(runs, 1, "only the omitted a default ran");
  assertEqual(u.a, 1, "evaluated default");
  assertEqual(u.b, 20, "provided value");
  // Fully provided documents run no defaults at all.
  const v = rt.jsonFromJson("@m/C", fields, null, '{"a":5,"b":6}', ...LOC);
  assert(v !== null && v.a === 5 && v.b === 6, "fully provided decode");
  assertEqual(runs, 1, "no default ran for provided fields");
});

// =========================================================================

if (failed > 0) {
  console.log("");
  console.log("========================================");
  console.log("Jsonable JS Results: " + passed + " passed, " + failed + " failed");
  console.log("========================================");
  throw new Error(failed + " jsonable JS test(s) failed");
}

console.log("");
console.log("========================================");
console.log("Jsonable JS Results: " + passed + " passed, 0 failed");
console.log("========================================");
