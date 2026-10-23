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
  const fields = [f("name", "string", false, false), f("age", "number", false, false)];
  const thunk = () => ({ name: "", age: 0 });
  const u = rt.jsonFromJson("@m/User", fields, '{"name":"Alice","age":30.0}', ...LOC);
  assert(u !== null, "fromJson returned null for valid JSON");
  assertEqual(u.$kind, "class", "instance kind tag");
  assertEqual(u.$classname, "@m/User", "instance identity tag");
  assertEqual(u.name, "Alice", "string field decoded");
  assertEqual(u.age, 30, "number field decoded");
});

test("jsonToJson serializes declared fields in declaration order", function() {
  const fields = [f("name", "string", false, false), f("age", "number", false, false)];
  const thunk = () => ({ name: "", age: 0 });
  const u = rt.jsonFromJson("@m/User", fields, '{"age":30.0,"name":"Alice"}', ...LOC);
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"Alice","age":30.0}',
    "document key order never leaks into the output (source order)");
});

test("jsonFromJson rejects decoded-key duplicates and rounded fractional number tokens", function() {
  const fields = [f("age", "number", false, false)];
  const thunk = () => ({ age: 0 });
  // A decoded-key duplicate is a parse failure: fromJson collapses it to
  // the DEAL null (never a partial instance or a parser exception).
  assertNull(rt.jsonFromJson("@m/U", fields, '{"age":1.0,"age":2.0}', ...LOC),
    "duplicate key");
  assertNull(rt.jsonFromJson("@m/U", fields, '{"age":null,"\u0061ge":2.0}', ...LOC),
    "escaped-equivalent unknown key stays an extra key");
  // The exact classification admits 1.0/1e0 as number and keeps a
  // fractional token that rounds to an integral double a number.
  const ok = rt.jsonFromJson("@m/U", fields, '{"age":1.0}', ...LOC);
  assert(ok !== null && ok.age === 1, "1.0 denotes the number value");
  const okExponent = rt.jsonFromJson("@m/U", fields, '{"age":1e0}', ...LOC);
  assert(okExponent !== null && okExponent.age === 1, "1e0 denotes the number value");
  assert(rt.jsonFromJson("@m/U", fields, '{"age":2147483647.00000001}', ...LOC).age === 2147483647,
    "a rounded fractional token satisfies the binary64 field");
});

test("jsonFromJson rejects omitted required fields", function() {
  const fields = [f("name", "string", false, false), f("age", "number", false, false)];
  const thunk = () => ({ name: "", age: 7 });
  const u = rt.jsonFromJson("@m/User", fields, '{"name":"Bob"}', ...LOC);
  assertNull(u, "omitted required field rejects");
  const complete = rt.jsonFromJson("@m/User", fields, '{"name":"Bob","age":7}', ...LOC);
  assertEqual(rt.jsonToJson("@m/User", complete, fields, ...LOC), '{"name":"Bob","age":7.0}', "complete roundtrip");
});

test("jsonFromJson rejects extra keys in document order (null, never throws)", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  assertNull(rt.jsonFromJson("@m/User", fields, '{"name":"Ada","extra":1.0}', ...LOC),
    "trailing extra key");
  assertNull(rt.jsonFromJson("@m/User", fields, '{"extra":1.0,"name":"Ada"}', ...LOC),
    "leading extra key");
});

// =========================================================================
// Top-level object gate (D3)
// =========================================================================

test("top-level gate: scalar/null/array documents return null", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  assertNull(rt.jsonFromJson("@m/User", fields, "42", ...LOC), "number document");
  assertNull(rt.jsonFromJson("@m/User", fields, '"x"', ...LOC), "string document");
  assertNull(rt.jsonFromJson("@m/User", fields, "true", ...LOC), "boolean document");
  assertNull(rt.jsonFromJson("@m/User", fields, "null", ...LOC), "null document");
  assertNull(rt.jsonFromJson("@m/User", fields, "[1,2]", ...LOC), "non-empty array document");
  assertNull(rt.jsonFromJson("@m/User", fields, "[]", ...LOC), "empty array document");
  assertNull(rt.jsonFromJson("@m/User", fields, " [ ] ", ...LOC), "spaced empty array document");
});

test("required omission and array documents reject independently", function() {
  const fields = [f("name", "string", false, false), f("age", "number", false, false)];
  const thunk = () => ({ name: "", age: 0 });
  assertNull(rt.jsonFromJson("@m/User", fields, "{}", ...LOC), "required omission");
  const fromObj = rt.jsonFromJson("@m/User", fields, '{"name":"","age":0}', ...LOC);
  assert(fromObj !== null, "{} decode");
  assertEqual(rt.jsonToJson("@m/User", fromObj, fields, ...LOC),
    '{"name":"","age":0}', "object text");
  assertNull(rt.jsonFromJson("@m/User", fields, "[]", ...LOC),
    "the empty array document is not an object");
});

test("a nested class value accepts only a JSON object: [] returns null", function() {
  const child = [{ name: "n", jtype: "number", optional: false, nullable: false, hasDefault: true }];
  const fields = [
    f("child", "class", false, false, { className: "@m/Child", fields: child }),
    f("items", "array", false, false, { element: { jtype: "class", optional: false, nullable: false, className: "@m/Child", fields: child } }),
  ];
  const thunk = () => ({ child: { n: 7 }, items: [] });
  assertNull(rt.jsonFromJson("@m/Parent", fields, '{"child":[]}', ...LOC),
    "a nested empty-array class value is a validation failure");
  assertNull(rt.jsonFromJson("@m/Parent", fields, '{"items":[[]]}', ...LOC),
    "a nested empty-array class element is a validation failure");
  assert(rt.jsonFromJson("@m/Parent", fields, '{"child":{"n":7.0},"items":[]}', ...LOC) !== null,
    "the object forms still decode");
});

test("malformed JSON returns null and never throws", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  assertNull(rt.jsonFromJson("@m/User", fields, "{bad json", ...LOC), "truncated object");
  assertNull(rt.jsonFromJson("@m/User", fields, "not valid json", ...LOC), "garbage text");
  assertNull(rt.jsonFromJson("@m/User", fields, "", ...LOC), "empty input");
  assertNull(rt.jsonFromJson("@m/User", fields, "  ", ...LOC), "whitespace input");
});

test("surrogate strings never escape as exceptions from jsonFromJson", function() {
  const fields = [f("name", "string", false, false)];
  const thunk = () => ({ name: "" });
  // A JSON-escaped lone high surrogate inside a string field.
  assertNull(rt.jsonFromJson("@m/User", fields, '{"name":"\\ud800"}', ...LOC),
    "escaped lone high surrogate in a field");
  assertNull(rt.jsonFromJson("@m/User", fields, '{"name":"\\udc00"}', ...LOC),
    "escaped lone low surrogate in a field");
  // A raw unpaired surrogate in the input text itself.
  assertNull(rt.jsonFromJson("@m/User", fields, '{"name":"\ud800"}', ...LOC),
    "raw lone high surrogate in the input text");
  // A valid supplementary pair round-trips.
  const u = rt.jsonFromJson("@m/User", fields, '{"name":"\\ud83d\\ude00"}', ...LOC);
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
  const u = rt.jsonFromJson("@m/User", fields, '{"name":"A","bio":null}', ...LOC);
  assert(u !== null && u.bio === null, "nullable present-null");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"A","bio":null}', "present-null text");

  const nonNullable = [f("name", "string", false, false), f("nick", "string", true, false)];
  const thunk2 = () => ({ name: "", nick: rt.MISSING });
  assertNull(rt.jsonFromJson("@m/User", nonNullable, '{"nick":null}', ...LOC),
    "explicit null on a non-nullable optional");
});

test("optional three-state roundtrip: missing omitted, null present, value present", function() {
  const fields = [f("name", "string", false, false), f("nick", "string", true, true)];
  const thunk = () => ({ name: "", nick: rt.MISSING });

  const missing = rt.jsonFromJson("@m/User", fields, '{"name":"A"}', ...LOC);
  assert(missing !== null && missing.nick === rt.MISSING, "missing state decodes to MISSING");
  assertEqual(rt.jsonToJson("@m/User", missing, fields, ...LOC), '{"name":"A"}', "missing state omitted by toJson");

  const nullState = rt.jsonFromJson("@m/User", fields, '{"name":"B","nick":null}', ...LOC);
  assert(nullState !== null && nullState.nick === null, "present-null state");
  assertEqual(rt.jsonToJson("@m/User", nullState, fields, ...LOC), '{"name":"B","nick":null}', "null state text");

  const valueState = rt.jsonFromJson("@m/User", fields, '{"name":"C","nick":"cee"}', ...LOC);
  assertEqual(rt.jsonToJson("@m/User", valueState, fields, ...LOC), '{"name":"C","nick":"cee"}', "value state text");
});

test("provided-value validation never reads legacy field hooks", function() {
  const fields = [f("a", "number", false, false), f("b", "number", false, false)];
  let runs = 0;
  for (const field of fields) Object.defineProperty(field, "evaluator", {
    get() { runs++; throw new Error("retired evaluator read"); }
  });
  const bad = rt.jsonFromJson("@m/C", fields, '{"b":"not-an-number"}', ...LOC);
  assertNull(bad, "number kind failure");
  assertEqual(runs, 0, "defaults thunk never ran");

  // Successful decode must not read the poisoned metadata either.
  const good = rt.jsonFromJson("@m/C", fields, '{"a":1,"b":20.0}', ...LOC);
  assert(good !== null && good.a === 1 && good.b === 20, "successful decode");
  assertEqual(runs, 0, "no legacy hook runs even for a complete document");
});

test("required omission preserves a previously decoded instance", function() {
  const fields = [f("a", "number", false, false)];
  const prior = rt.jsonFromJson("@m/C", fields, '{"a":7}', ...LOC);
  assertNull(rt.jsonFromJson("@m/C", fields, "{}", ...LOC), "missing required field");
  assert(prior !== null && prior.a === 7, "earlier publication is unchanged");
});

// =========================================================================
// Int range, final validation through the canonical matcher rows (D2 step 7)
// =========================================================================

test("number decode admits finite binary64 values", function() {
  const fields = [f("age", "number", false, false)];
  const thunk = () => ({ age: 0 });
  for (const value of [9007199254740992, 1e300, 3.5]) {
    assert(rt.jsonFromJson("@m/User", fields, JSON.stringify({age: value}), ...LOC).age === value,
      "finite binary64 admission");
  }
  const u = rt.jsonFromJson("@m/User", fields, '{"age":-0}', ...LOC);
  assert(u !== null && u.age === 0, "-0 normalizes to 0");
});

test("final validation routes through checkType (class identity byte-compare)", function() {
  const childFields = [f("value", "number", false, false)];
  const fields = [f("name", "string", false, false),
    f("home", "class", false, false, { className: "@m/Address", fields: childFields })];
  const childPlan = [{name:"value", descriptor:"number", optional:false}];
  const plan = [{name:"name", descriptor:"string", optional:false},
    {name:"home", descriptor:"@m/Address", optional:false}];
  const wrong = rt.classPlan("@m/Other", childPlan, {value:0}, ...LOC);
  assertError(() => rt.classPlan("@m/User", plan, {name:"D",home:wrong}, ...LOC),
    "E8001", "expected instance of @m/Address", "foreign supplied identity rejects");
  const right = rt.classPlan("@m/Address", childPlan, {value:0}, ...LOC);
  assertEqual(rt.classPlan("@m/User", plan, {name:"D",home:right}, ...LOC).home,
    right, "supplied child preserves identity");
  const u = rt.jsonFromJson("@m/User", fields, '{"name":"D","home":{"value":0}}', ...LOC);
  assert(u !== null && u.home.$classname === "@m/Address", "decoded child has declared identity");
  assertNull(rt.jsonFromJson("@m/User", [f("name", "string", false, false)], '{"name":42}', ...LOC),
    "provided primitive type failure");
});

// =========================================================================
// Nested classes and arrays (D2 step 5)
// =========================================================================

test("nested class fields decode with the full phase order", function() {
  const childFields = [f("value", "number", false, false)];
  const fields = [f("name", "string", false, false),
    f("home", "class", false, false, { className: "@m/Address", fields: childFields })];
  const thunk = () => ({ name: "",
    home: rt.classPlan("@m/Address", [{name:"value", descriptor:"number", optional:false}], {value:0}, ...LOC) });
  const u = rt.jsonFromJson("@m/User", fields, '{"name":"Dave","home":{"value":7.0}}', ...LOC);
  assert(u !== null && u.home.$kind === "class" && u.home.$classname === "@m/Address"
    && u.home.value === 7, "nested instance decoded and tagged");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"name":"Dave","home":{"value":7.0}}',
    "nested roundtrip");
});

test("nested class extra keys and malformed elements are rejected", function() {
  const childFields = [f("value", "number", false, false)];
  const fields = [f("children", "array", false, false,
    { element: elem("class", false, { className: "@m/Child", fields: childFields }) })];
  const thunk = () => ({ children: [] });
  assertNull(rt.jsonFromJson("@m/P", fields, '{"children":[{"value":1.0,"extra":2.0}]}', ...LOC),
    "nested extra key");
  assertNull(rt.jsonFromJson("@m/P", fields, '{"children":[1.0]}', ...LOC),
    "malformed nested element");
  assertNull(rt.jsonFromJson("@m/P", fields, '{"children":{}}', ...LOC),
    "object where an array is declared");
  const ok = rt.jsonFromJson("@m/P", fields, '{"children":[{"value":3.0},{"value":4.0}]}', ...LOC);
  assert(ok !== null && ok.children.length === 2
    && ok.children[0].$classname === "@m/Child" && ok.children[1].value === 4,
    "array of nested classes decodes");
  assertEqual(rt.jsonToJson("@m/P", ok, fields, ...LOC),
    '{"children":[{"value":3.0},{"value":4.0}]}', "array of nested classes roundtrip");
});

test("nested class decode without defaults: absent optionals become MISSING", function() {
  const childFields = [f("value", "number", false, false), f("nick", "string", true, false)];
  const fields = [f("home", "class", false, false, { className: "@m/Address", fields: childFields })];
  const thunk = () => ({ home: {} });
  const u = rt.jsonFromJson("@m/User", fields, '{"home":{"value":5.0}}', ...LOC);
  assert(u !== null && u.home.value === 5 && u.home.nick === rt.MISSING,
    "nested absent optional materializes as MISSING (no nested defaults surface)");
  assertEqual(rt.jsonToJson("@m/User", u, fields, ...LOC), '{"home":{"value":5.0}}',
    "nested MISSING omitted");
});

test("nullable nested class accepts null and values", function() {
  const childFields = [f("value", "number", false, false)];
  const fields = [f("child", "class", true, true, { className: "@m/Child", fields: childFields })];
  const thunk = () => ({ child: rt.MISSING });
  const explicitNull = rt.jsonFromJson("@m/Maybe", fields, '{"child":null}', ...LOC);
  assert(explicitNull !== null && explicitNull.child === null, "explicit null nested");
  const value = rt.jsonFromJson("@m/Maybe", fields, '{"child":{"value":8.0}}', ...LOC);
  assert(value !== null && value.child.value === 8, "nested value");
});

// =========================================================================
// Table fields: D9 Map shape, JSON-shaped end to end (D4)
// =========================================================================

test("a failed nested decode fails the enclosing decode, never a present null", function() {
  const childFields = [f("value", "number", false, false)];
  const fields = [f("child", "class", true, true,
    { className: "@m/Child", fields: childFields })];
  const thunk = () => ({ child: rt.MISSING });
  // Every nested gate/validation failure collapses to the language null of
  // the whole call: a nullable class field never absorbs a failed nested
  // decode as a present null, and no partial instance publishes (only a
  // JSON null token reaches the nullable null arm).
  assertNull(rt.jsonFromJson("@m/Maybe", fields,
    '{"child":{"value":1.0,"extra":2.0}}', ...LOC),
    "nested extra key through a nullable class field");
  assertNull(rt.jsonFromJson("@m/Maybe", fields,
    '{"child":{"value":"no"}}', ...LOC),
    "nested provided-kind failure through a nullable class field");
  const arrayFields = [f("children", "array", false, false,
    { element: elem("class", true, { className: "@m/Child", fields: childFields }) })];
  const arrayThunk = () => ({ children: [] });
  assertNull(rt.jsonFromJson("@m/Maybe", arrayFields,
    '{"children":[{"value":1.0,"extra":2.0}]}', ...LOC),
    "nested failure through a nullable class-array element");
  // A genuine JSON null token in the same positions still decodes as a
  // present null: the three presence states stay distinct.
  const nil = rt.jsonFromJson("@m/Maybe", fields, '{"child":null}', ...LOC);
  assert(nil !== null && nil.child === null, "explicit null nested still decodes");
  const nilElement = rt.jsonFromJson("@m/Maybe", arrayFields,
    '{"children":[null]}', ...LOC);
  assert(nilElement !== null && nilElement.children[0] === null,
    "explicit null element still decodes");
  // Completed effects remain and nothing retries: the omitted-required
  // evaluators that already ran keep their effects when a later final
  // validation fails, and the call still returns the language null with no
  // partial instance published.
  let runs = 0;
  const effectFields = [f("a", "number", false, false),
    f("b", "string", false, false)];
  effectFields[0].evaluator = function () { runs++; return 1; };
  effectFields[1].evaluator = function () { runs++; return 42; };
  assertNull(rt.jsonFromJson("@m/Maybe", effectFields, "{}", ...LOC),
    "a final-validation failure after the defaults decodes to null");
  assertEqual(runs, 0, "nested decode never invokes initializer hooks");
});

test("table fields decode to the D9 Map shape and roundtrip nested arrays", function() {
  const fields = [f("data", "table", false, false)];
  const thunk = () => ({ data: rt.makeTable({}) });
  const text = '{"data":{"count":3.0,"values":[[1.0,2.0],[3.0,4.0]]}}';
  const u = rt.jsonFromJson("@m/W", fields, text, ...LOC);
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
  assertNull(rt.jsonFromJson("@m/W", fields, '{"data":[1.0,2.0]}', ...LOC), "array for a table field");
  assertNull(rt.jsonFromJson("@m/W", fields, '{"data":[]}', ...LOC), "empty array for a table field");
  assertNull(rt.jsonFromJson("@m/W", fields, '{"data":5.0}', ...LOC), "scalar for a table field");
  assert(rt.jsonFromJson("@m/W", fields, '{"data":{}}', ...LOC) !== null,
    "empty object for a table field");
});

test("class instances inside table fields are rejected by the walk arm", function() {
  const fields = [f("data", "table", false, false)];
  const thunk = () => ({ data: rt.makeTable({}) });
  const inner = rt.classPlan("@m/User", [{name:"name",descriptor:"string",optional:false}, {name:"nick",descriptor:"?string",optional:true}],
    { name: "Ada" }, ...LOC);
  const u = rt.jsonFromJson("@m/W", fields, '{"data":{"u":{}}}', ...LOC);
  assert(u !== null, "decode with a table field");
  u.data.set("u", inner);
  // Table-field contents stay JSON-shaped: a class instance is not a
  // JSON value and fails the walk arm at its position (never the D9 tag
  // encoding).
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "value at data.u is not JSON serializable: @m/User", "class inside table content");
});

// =========================================================================
// toJson failures (D5): identity, nulls, cycles, NaN/Infinity, functions
// =========================================================================

test("jsonToJson identity check rejects wrong-identity and non-instances", function() {
  const fields = [f("name", "string", false, false)];
  const u = rt.classPlan("@m/User", [{name:"name",descriptor:"string",optional:false}], {name:""}, ...LOC);
  assertError(() => rt.jsonToJson("@m/Other", u, fields, ...LOC), "E8001",
    "expected instance of @m/Other, got @m/User", "wrong identity");
  assertError(() => rt.jsonToJson("@m/User", { name: "x" }, fields, ...LOC), "E8001",
    "expected class instance", "untagged plain object");
  assertError(() => rt.jsonToJson("@m/User", 42, fields, ...LOC), "E8001",
    "expected class instance", "primitive");
});

test("jsonToJson: explicit null on a non-nullable field raises E8001", function() {
  const fields = [f("name", "string", false, false)];
  const u = rt.classPlan("@m/User", [{name:"name",descriptor:"string",optional:false}], {name:""}, ...LOC);
  rt.setProp(u, "name", null);
  assertError(() => rt.jsonToJson("@m/User", u, fields, ...LOC), "E8001",
    "value at name is not JSON serializable: null", "non-nullable null");
});

test("jsonToJson: a missing required field raises E8001", function() {
  const fields = [f("name", "string", false, false)];
  const u = rt.setProp(rt.setProp({}, "$kind", "class"), "$classname", "@m/User");
  assertError(() => rt.jsonToJson("@m/User", u, fields, ...LOC), "E8001",
    "value at name is not JSON serializable: nil", "missing required field");
});

test("jsonToJson raises E8001 for cyclic class graphs", function() {
  const childFields = [f("parent", "class", false, true, { className: "@m/Node", fields: null })];
  const selfFields = [f("next", "class", false, true, { className: "@m/Node", fields: childFields })];
  const n = rt.classPlan("@m/Node", [{name:"next",descriptor:"?@m/Node",optional:false}], {next:null}, ...LOC);
  rt.setProp(n, "next", n);
  assertError(() => rt.jsonToJson("@m/Node", n, selfFields, ...LOC), "E8001",
    "cyclic value cannot be encoded as JSON", "class self-cycle");
});

test("jsonToJson raises E8001 for cyclic table fields", function() {
  const fields = [f("data", "table", false, false)];
  const u = rt.classPlan("@m/W", [{name:"data",descriptor:"table",optional:false}], {data:rt.makeTable({})}, ...LOC);
  const data = u.data;
  data.set("self", data);
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "cyclic value cannot be encoded as JSON", "table self-cycle");
});

test("jsonToJson raises E8001 for NaN/Infinity number fields", function() {
  const fields = [f("x", "number", false, false)];
  const u = rt.classPlan("@m/N", [{name:"x",descriptor:"number",optional:false}], {x:0}, ...LOC);
  rt.setProp(u, "x", NaN);
  assertError(() => rt.jsonToJson("@m/N", u, fields, ...LOC), "E8001",
    "value at x is not JSON serializable: number", "NaN field");
  rt.setProp(u, "x", Infinity);
  assertError(() => rt.jsonToJson("@m/N", u, fields, ...LOC), "E8001",
    "value at x is not JSON serializable: number", "Infinity field");
});

test("jsonToJson raises E8001 for function values inside table fields", function() {
  const fields = [f("data", "table", false, false)];
  const u = rt.classPlan("@m/W", [{name:"data",descriptor:"table",optional:false}], {data:rt.makeTable({})}, ...LOC);
  u.data.set("f", rt.function("()->null", function() { return null; }));
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "value at data.f is not JSON serializable: function", "function inside table");
});

test("jsonToJson raises E8001 for a non-JSON-shaped table value", function() {
  const fields = [f("data", "table", false, false)];
  const u = rt.classPlan("@m/W", [{name:"data",descriptor:"table",optional:false}], {data:rt.makeTable({})}, ...LOC);
  u.data.set("bad", rt.MISSING);
  assertError(() => rt.jsonToJson("@m/W", u, fields, ...LOC), "E8001",
    "value at data.bad is not JSON serializable: nil", "MISSING inside table");
});

test("jsonToJson number fields enforce the boundary (E8004 range)", function() {
  const fields = [f("age", "number", false, false)];
  const u = rt.classPlan("@m/User", [{name:"age",descriptor:"number",optional:false}], {age:0}, ...LOC);
  rt.setProp(u, "age", 9007199254740992);
  assert(rt.jsonToJson("@m/User", u, fields, ...LOC).includes("9.007199254740992E15"),
    "large finite number encodes");
});

// =========================================================================
// Shared D9 conversion members (js-backend-runtime D9 reuse)
// =========================================================================

test("jsonParseText/jsonParseValue/jsonStringifyText roundtrip the marked Map shapes", function() {
  const arr = rt.jsonParseValue([1, 2], ...LOC);
  assert(arr instanceof Map && rt.isJsonArrayTable(arr), "array-marked Map");
  assertEqual(arr.get("2"), 2, "array element by string key");
  const nil = rt.jsonParseValue(null, ...LOC);
  assert(nil instanceof Map && rt.isJsonNullTable(nil), "null-marked Map");
  assertEqual(rt.jsonStringifyText(arr, [], ...LOC), "[1,2]", "encode back to a JSON array");
  assertEqual(rt.jsonStringifyText(nil, [], ...LOC), "null", "null-marked Map encodes as null");
  const obj = rt.jsonParseValue({ a: [1], n: null }, ...LOC);
  assert(obj instanceof Map && !rt.isJsonArrayTable(obj) && !rt.isJsonNullTable(obj),
    "object Map");
  const encoded = rt.jsonStringifyText(obj, [], ...LOC);
  assertEqual(encoded, '{"a":[1],"n":null}', "object form with nested marks");
  // The conforming scanner (jsonParseText) classifies exactly and
  // records the per-slot variant marks consulted by the encode walk.
  const parsed = rt.jsonParseText('{"i":1.0,"n":1.5}', ...LOC);
  assertEqual(parsed.i, 1, "1.0 denotes the number value");
  assertEqual(parsed.n, 1.5, "1.5 stays a number");
  const parsedMap = rt.jsonParseValue(parsed, ...LOC);
  assertEqual(rt.jsonStringifyText(parsedMap, [], ...LOC), '{"i":1.0,"n":1.5}',
    "the number-variant marks survive the conversion");
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

test("classPlan rejects required omissions and never runs legacy hooks", function() {
  const order = [];
  const plan = [planEntry("a", "number", false, () => { order.push("a"); return 1; }),
    planEntry("b", "number", false, () => { order.push("b"); return 2; })];
  assertError(() => rt.classPlan("C", plan, null, ...LOC), "E8001", "missing required field 'a'", "required omission");
  const u = rt.classPlan("C", plan, {a:1,b:2}, ...LOC);
  const v = rt.classPlan("C", plan, {a:1,b:2}, ...LOC);
  assertEqual(order.join(","), "", "retired hooks never execute");
  assertEqual(u.a + u.b + v.a + v.b, 6, "per-attempt results");
});

test("classPlan provided fields suppress their evaluators", function() {
  let runs = 0;
  const plan = [planEntry("a", "number", false, () => { runs++; return 1; }),
    planEntry("b", "number", false, () => { runs++; return 2; })];
  const u = rt.classPlan("C", plan, { a: 5, b: 6 }, ...LOC);
  assertEqual(runs, 0, "no default ran for a provided field");
  assertEqual(u.a + u.b, 11, "provided values stored");
});

test("classPlan extra provided field raises E8007 with zero default evaluation", function() {
  let runs = 0;
  const plan = [planEntry("a", "number", false, () => { runs++; return 1; })];
  assertError(() => rt.classPlan("C", plan, { extra: 1 }, ...LOC), "E8007",
    "extra field 'extra' in class 'C'", "extra-key rejection");
  assertEqual(runs, 0, "no default evaluated before the E8007");
});

test("classPlan validates supplied values and preserves explicit nullable presence", function() {
  const plan = [planEntry("a", "number", false, () => 1),
    planEntry("nick", "?string", true)];
  assertError(() => rt.classPlan("C", plan, { a: "not-an-number" }, ...LOC), "E8001",
    "expected number", "provided-value validation");
  assertError(() => rt.classPlan("C", plan, { a: 1, nick: 42 }, ...LOC), "E8001",
    "expected string", "nullable optional validation");
  const u = rt.classPlan("C", plan, { a: 1, nick: null }, ...LOC);
  assertEqual(u.nick, null, "explicit null on a nullable optional stays present-null");
});

test("classPlan required omission precedes any retired evaluator", function() {
  const plan = [planEntry("a", "number", false, () => rt.fail("E8002", "evaluator boom", ...LOC))];
  const err = assertError(() => rt.classPlan("C", plan, null, ...LOC), "E8001",
    "missing required field 'a'", "omission rejects without invoking a legacy hook");
  assertEqual(err.$dealCode, "E8001", "presence failure preserved");
});

test("classPlan malformed plan shapes fail closed with E8001", function() {
  assertError(() => rt.classPlan("C", { length: 1 }, null, ...LOC), "E8001",
    "class default plan must be a table", "non-array plan");
  assertError(() => rt.classPlan("C", ["x"], null, ...LOC), "E8001",
    "malformed class default plan entry", "non-object entry");
  assertError(() => rt.classPlan("C", [{ name: 1, descriptor: "number", optional: false }], null, ...LOC),
    "E8001", "malformed class default plan entry", "non-string name");
  assertError(() => rt.classPlan("C", [{ name: "x", descriptor: 2, optional: false }], null, ...LOC),
    "E8001", "malformed class default plan entry", "non-string descriptor");
  assertError(() => rt.classPlan("C", [{ name: "x", descriptor: "number", optional: "yes" }], null, ...LOC),
    "E8001", "malformed class default plan entry", "non-boolean optional");
  assertEqual(rt.classPlan("C", [{ name: "x", descriptor: "number", optional: false, evaluator: 3 }],
    {x:7}, ...LOC).x, 7, "retired evaluator metadata is not authority");
  assertError(() => rt.classPlan("C", [planEntry("x", "number", false, () => 1),
    planEntry("x", "number", false, () => 2)], null, ...LOC), "E8001",
    "duplicate field in class default plan: 'x'", "duplicate names");
  assertError(() => rt.classPlan("C", [planEntry("a", "number", false, () => 1)], 42, ...LOC),
    "E8001", "class field values must be a table", "non-table provided");
});

test("jsonFromDocument ignores legacy per-entry evaluators for every outcome", function() {
  let runs = 0;
  const fields = [f("a", "number", false, false), f("b", "number", false, false)];
  fields[0].evaluator = () => { runs++; return 1; };
  fields[1].evaluator = () => { runs++; return 2; };
  // Extra keys and provided-value failures still precede every default.
  assertNull(rt.jsonFromJson("@m/C", fields, '{"extra":1.0}', ...LOC), "extra-key gate");
  assertEqual(runs, 0, "no default ran after the extra-key rejection");
  assertNull(rt.jsonFromJson("@m/C", fields, '{"b":"x"}', ...LOC), "decode gate");
  assertEqual(runs, 0, "no default ran after the provided-value failure");
  // Omitted required defaults evaluate exactly once per attempt.
  assertNull(rt.jsonFromJson("@m/C", fields, '{"b":20.0}', ...LOC), "required omission");
  const u = rt.jsonFromJson("@m/C", fields, '{"a":1,"b":20.0}', ...LOC);
  assert(u !== null, "valid decode");
  assertEqual(runs, 0, "legacy field hooks never execute");
  assertEqual(u.a, 1, "evaluated default");
  assertEqual(u.b, 20, "provided value");
  // Fully provided documents run no defaults at all.
  const v = rt.jsonFromJson("@m/C", fields, '{"a":5.0,"b":6.0}', ...LOC);
  assert(v !== null && v.a === 5 && v.b === 6, "fully provided decode");
  assertEqual(runs, 0, "no default ran for provided fields");
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
