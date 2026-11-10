"use strict";

// Direct contextual JSON reconstruction and encoding regressions. Run from the
// repository root with node test_jsonable_js.js. No compiler-generated helper
// wrappers or source-callable JSON function values participate in this drive.
const rt = require("./deal/runtime");
const LOC = ["probe.js", 1, 1];
let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log("PASS: " + name); }
  catch (e) { failed++; console.log("FAIL: " + name + " -- " + e.stack); }
}
function assert(value, message) { if (!value) throw new Error(message); }
function equal(actual, expected, message) {
  assert(Object.is(actual, expected), message + ": expected " + String(expected) + ", got " + String(actual));
}
function field(name, descriptor, optional = false) { return {name, descriptor, optional}; }
function schema(identity, fields) { rt.registerClassPlan(identity, fields); return identity; }
function decode(text, target) { return rt.jsonDecode(text, target, ...LOC); }
function encode(value) { return rt.jsonStringifyText(value, [], ...LOC); }
function error(fn, code, needle) {
  let caught;
  try { fn(); } catch (e) { caught = e; }
  assert(caught !== undefined, "expected rejection");
  equal(caught.$dealCode, code, "error code");
  if (needle !== undefined) assert(caught.message.includes(needle), "error message: " + caught.message);
  equal(caught.file, LOC[0], "error file");
  equal(caught.line, LOC[1], "error line");
  equal(caught.column, LOC[2], "error column");
  return caught;
}
function rejects(text, target, code = "E8001", needle) {
  const prior = decode('{"n":7}', schema("@rollback/Prior", [field("n", "number")]));
  let destination = prior;
  const caught = error(() => { destination = decode(text, target); }, code, needle);
  equal(destination, prior, "failed assignment preserves destination");
  equal(prior.n, 7, "failed assignment preserves fields");
  return caught;
}
const user = () => schema("@m/User", [field("name", "string"), field("age", "number")]);

test("contextual parsing tags a fresh exact nominal instance", () => {
  assert(!Object.hasOwn(rt, "jsonFromJson") && !Object.hasOwn(rt, "jsonToJson")
      && !Object.hasOwn(rt, "jsonParseValue"),
    "retired helper runtime APIs are absent");
  const id = user(), a = decode('{"name":"Alice","age":30.0}', id);
  const b = decode('{"name":"Alice","age":30.0}', id);
  equal(a.$kind, "class", "class tag"); equal(a.$classname, id, "nominal identity");
  equal(a.name, "Alice", "name"); equal(a.age, 30, "age"); assert(a !== b, "fresh instances");
});
test("encoding follows declaration order rather than document order", () => {
  equal(encode(decode('{"age":30,"name":"Alice"}', user())), '{"name":"Alice","age":30}', "field order");
});
test("duplicate decoded keys reject while rounded decimal tokens remain binary64", () => {
  const id = schema("@m/Number", [field("age", "number")]);
  rejects('{"age":1,"age":2}', id, "E8001", "duplicate key");
  rejects('{"age":null,"\\u0061ge":2}', id, "E8001", "duplicate key");
  for (const text of ["1.0", "1e0", "2147483647.00000001"])
    equal(decode('{"age":' + text + '}', id).age, Number(text), "rounded value");
});
test("omitted required fields reject without defaults", () => {
  const e = rejects('{"name":"Bob"}', user(), "E8001", "expected number");
  equal(e.actual, "undefined", "missing actual");
  equal(encode(decode('{"name":"Bob","age":7}', user())), '{"name":"Bob","age":7}', "complete control");
});
test("extra keys reject before declared mismatches in either input order", () => {
  const id = schema("@m/Extra", [field("name", "string")]);
  for (const text of ['{"name":42,"extra":1}', '{"extra":1,"name":42}'])
    rejects(text, id, "E8007", "extra field 'extra'");
});
test("class root rejects scalars null and arrays while unknown admits them", () => {
  const id = schema("@m/Root", [field("name", "string")]);
  for (const text of ["42", '"x"', "true", "null", "[1,2]", "[]", " [ ] "]) {
    rejects(text, id);
    equal(encode(decode(text, "unknown")), JSON.stringify(JSON.parse(text)), "unknown root");
  }
});
test("required omission and array shape reject independently", () => {
  const id = user(); rejects("{}", id); rejects("[]", id);
  equal(encode(decode('{"name":"","age":0}', id)), '{"name":"","age":0}', "object control");
});
test("nested class and class-array values require JSON objects", () => {
  schema("@m/Child", [field("n", "number")]);
  const id = schema("@m/Parent", [field("child", "@m/Child"), field("items", "[@m/Child]")]);
  rejects('{"child":[],"items":[]}', id);
  rejects('{"child":{"n":7},"items":[[]]}', id);
  equal(decode('{"child":{"n":7},"items":[]}', id).child.n, 7, "object control");
});
test("malformed JSON throws rather than returning null", () => {
  for (const text of ["{bad json", "not valid json", "", "  "])
    rejects(text, user(), "E8001", "JSON parse error");
});
test("class fields preserve lone UTF-16 units and supplementary pairs", () => {
  const id = schema("@m/Text", [field("name", "string")]);
  for (const value of ["\ud800", "\udc00", "\ud83d\ude00"]) {
    const a = decode(JSON.stringify({name:value}), id);
    equal(a.name, value, "decoded units"); equal(JSON.parse(encode(a)).name, value, "encoded units");
  }
  equal(decode('{"name":"\ud800"}', id).name, "\ud800", "literal lone unit");
});
test("explicit null needs a nullable descriptor even for optional fields", () => {
  const id = schema("@m/Null", [field("name", "string"), field("bio", "?string")]);
  equal(encode(decode('{"name":"A","bio":null}', id)), '{"name":"A","bio":null}', "present null");
  rejects('{"name":"A","nick":null}', schema("@m/NonNull", [field("name", "string"), field("nick", "string", true)]));
});
test("optional missing null and value remain three distinct states", () => {
  const id = schema("@m/Optional", [field("name", "string"), field("nick", "?string", true)]);
  for (const text of ['{"name":"A"}', '{"name":"B","nick":null}', '{"name":"C","nick":"cee"}']) {
    const a = decode(text, id); equal(encode(a), text, "optional roundtrip");
    equal(Object.hasOwn(a, "nick"), Object.hasOwn(JSON.parse(text), "nick"), "presence");
  }
});
test("schema evaluators are never read on success or rejection", () => {
  let runs = 0; const fields = [field("a", "number"), field("b", "number")];
  for (const f of fields) Object.defineProperty(f, "evaluator", {get() { runs++; throw new Error("hook read"); }});
  const id = schema("@m/Hooks", fields);
  rejects('{"b":"bad"}', id); equal(decode('{"a":1,"b":20}', id).b, 20, "valid control");
  equal(runs, 0, "hook reads");
});
test("required omission leaves previously decoded instances untouched", () => {
  const id = schema("@m/Prior", [field("a", "number")]), prior = decode('{"a":7}', id);
  let destination = prior; error(() => { destination = decode("{}", id); }, "E8001");
  equal(destination, prior, "destination identity"); equal(prior.a, 7, "prior field");
});
test("number reconstruction admits wide binary64 and preserves negative zero", () => {
  const id = schema("@m/Wide", [field("age", "number")]);
  for (const value of [9007199254740992, 1e300, 3.5]) equal(decode(JSON.stringify({age:value}), id).age, value, "wide number");
  equal(decode('{"age":-0}', id).age, -0, "negative zero");
});
test("ordinary identity checks reject siblings but parsing creates the exact child", () => {
  const child = [field("value", "number")]; schema("@m/Address", child);
  const fields = [field("name", "string"), field("home", "@m/Address")];
  const id = schema("@m/Identity", fields), wrong = rt.classPlan("@m/Other", child, {value:0}, ...LOC);
  error(() => rt.classPlan(id, fields, {name:"D",home:wrong}, ...LOC), "E8001", "expected instance of @m/Address");
  const right = rt.classPlan("@m/Address", child, {value:0}, ...LOC);
  equal(rt.classPlan(id, fields, {name:"D",home:right}, ...LOC).home, right, "ordinary supplied identity");
  equal(decode('{"name":"D","home":{"value":0}}', id).home.$classname, "@m/Address", "decoded identity");
  rejects('{"name":42}', schema("@m/Primitive", [field("name", "string")]));
});
test("nested class reconstruction tags fields and encodes in declaration order", () => {
  schema("@m/Address", [field("value", "number")]);
  const id = schema("@m/Nested", [field("name", "string"), field("home", "@m/Address")]);
  const a = decode('{"home":{"value":7},"name":"Dave"}', id);
  equal(a.home.$classname, "@m/Address", "child identity");
  equal(encode(a), '{"name":"Dave","home":{"value":7}}', "nested encoding");
});
test("nested class arrays reject extra keys malformed elements and object shape", () => {
  schema("@m/Child", [field("value", "number")]);
  const id = schema("@m/Children", [field("children", "[@m/Child]")]);
  rejects('{"children":[{"value":1,"extra":2}]}', id, "E8007");
  rejects('{"children":[1]}', id); rejects('{"children":{}}', id);
  const a = decode('{"children":[{"value":3},{"value":4}]}', id);
  equal(a.children.length, 2, "array length"); equal(a.children[0].$classname, "@m/Child", "element identity");
  equal(encode(a), '{"children":[{"value":3},{"value":4}]}', "array encoding");
});
test("absent nested optionals stay absent without defaults", () => {
  schema("@m/Address", [field("value", "number"), field("nick", "string", true)]);
  const a = decode('{"home":{"value":5}}', schema("@m/Absent", [field("home", "@m/Address")]));
  assert(!Object.hasOwn(a.home, "nick"), "optional absent");
  equal(encode(a), '{"home":{"value":5}}', "absence encoding");
});
test("nullable nested classes admit both null and reconstructed values", () => {
  schema("@m/Child", [field("value", "number")]);
  const id = schema("@m/Maybe", [field("child", "?@m/Child", true)]);
  equal(decode('{"child":null}', id).child, null, "explicit null");
  equal(decode('{"child":{"value":8}}', id).child.value, 8, "child value");
});
test("nullable descendants never absorb failed reconstruction or run hooks", () => {
  schema("@m/Child", [field("value", "number")]);
  const id = schema("@m/Maybe", [field("child", "?@m/Child", true)]);
  rejects('{"child":{"value":1,"extra":2}}', id, "E8007"); rejects('{"child":{"value":"no"}}', id);
  const arrayId = schema("@m/MaybeArray", [field("children", "[?@m/Child]")]);
  rejects('{"children":[{"value":1,"extra":2}]}', arrayId, "E8007");
  equal(decode('{"child":null}', id).child, null, "real null child");
  equal(decode('{"children":[null]}', arrayId).children[0], null, "real null element");
  let runs = 0; const fields = [field("a", "number"), field("b", "string")];
  for (const f of fields) f.evaluator = () => { runs++; return 42; };
  rejects("{}", schema("@m/NoEffects", fields)); equal(runs, 0, "no hook effects");
});
test("Record fields reconstruct Maps with native nested arrays", () => {
  const id = schema("@m/Record", [field("data", "record")]);
  const a = decode('{"data":{"count":3,"values":[[1,2],[3,4]]}}', id), data = a.data;
  assert(data instanceof Map, "Record Map"); equal(data.get("count"), 3, "scalar entry");
  const values = data.get("values"); assert(Array.isArray(values) && Array.isArray(values[0]), "native nested arrays");
  equal(values[0][1], 2, "nested element");
  equal(encode(a), '{"data":{"count":3,"values":[[1,2],[3,4]]}}', "Record encoding");
});
test("Record fields reject arrays and scalars but admit empty objects", () => {
  const id = schema("@m/Record", [field("data", "record")]);
  for (const text of ['{"data":[1,2]}', '{"data":[]}', '{"data":5}']) rejects(text, id);
  equal(encode(decode('{"data":{}}', id)), '{"data":{}}', "empty Record");
});
test("class instances nested in Records encode public fields without identity changes", () => {
  const id = schema("@m/Record", [field("data", "record")]);
  const inner = rt.classPlan("@m/Public", [field("name", "string"), field("nick", "?string", true)], {name:"Ada"}, ...LOC);
  const a = decode('{"data":{"u":{}}}', id); a.data.set("u", inner);
  equal(encode(a), '{"data":{"u":{"name":"Ada"}}}', "public fields"); equal(a.data.get("u"), inner, "nested identity");
});
test("ordinary nominal admission remains separate from generic encoding", () => {
  const a = rt.classPlan("@m/User", [field("name", "string")], {name:"x"}, ...LOC);
  error(() => rt.checkType("@m/Other", a, ...LOC), "E8001", "expected instance of @m/Other");
  error(() => rt.checkType("@m/User", {name:"x"}, ...LOC), "E8001");
  error(() => rt.checkType("@m/User", 42, ...LOC), "E8001");
  equal(encode(a), '{"name":"x"}', "generic nominal encoding");
});
test("checked class writes reject nonnullable null before mutation", () => {
  const a = rt.classPlan("@m/Checked", [field("name", "string")], {name:"kept"}, ...LOC);
  const alias = a;
  const store = value => {
    const admitted = rt.checkType("string", value, ...LOC);
    rt.setProp(a, "name", admitted);
  };
  error(() => store(null), "E8001", "expected string");
  equal(alias.name, "kept", "rejected checked write preserves the aliased field");
  store("repaired");
  equal(alias.name, "repaired", "valid retry reaches the same field storage");
  rejects('{"name":null}', schema("@m/Checked", [field("name", "string")]));
});
test("required missing fields reject in both construction and reconstruction", () => {
  const fields = [field("name", "string")], id = schema("@m/Required", fields);
  error(() => rt.classPlan(id, fields, {}, ...LOC), "E8001", "missing required field 'name'");
  rejects("{}", id, "E8001", "expected string");
});
test("stringify revalidates mutated declared fields on the same graph", () => {
  const leafFields = [field("value", "number")];
  const leaf = rt.classPlan("@serialization/Leaf", leafFields, {value:7}, ...LOC);
  const fields = [field("name", "string"), field("child", "@serialization/Leaf"),
    field("items", "[number]"), field("note", "!string", true), field("required", "!string")];
  const value = rt.classPlan("@serialization/Owner", fields,
    {name:"kept", child:leaf, items:[1,2], required:undefined}, ...LOC);
  const items = value.items;
  const expected = '{"name":"kept","child":{"value":7},"items":[1,2]}';
  equal(encode(value), expected, "valid serialization");
  rt.setProp(value, "name", 42);
  error(() => encode(value), "E8001");
  equal(value.child, leaf, "failed serialization preserves descendant identity");
  rt.setProp(value, "name", "kept");
  rt.setProp(value, "note", false);
  error(() => encode(value), "E8001");
  rt.setProp(value, "note", undefined);
  equal(encode(value), expected, "present admitted undefined omits");
  rt.setProp(value, "required", rt.MISSING);
  error(() => encode(value), "E8001");
  rt.setProp(value, "required", undefined);
  rt.setProp(leaf, "value", "bad");
  error(() => encode(value), "E8001");
  rt.setProp(leaf, "value", 7);
  const sibling = rt.classPlan("@serialization/Sibling", leafFields, {value:7}, ...LOC);
  rt.setProp(value, "child", sibling);
  error(() => encode(value), "E8001");
  rt.setProp(value, "child", leaf);
  items[1] = "bad";
  error(() => encode(value), "E8003");
  items[1] = 2;
  equal(value.items, items, "array identity survives rejection and repair");
  equal(encode(value), expected, "same-graph retry succeeds");
});
test("stringify preserves reached-field failure order and nested element causes", () => {
  const fields = [field("first", "unknown"), field("later", "string"), field("grid", "[[number]]")];
  const value = rt.classPlan("@serialization/Order", fields, {first:null,later:"valid",grid:[[1]]}, ...LOC);
  value.first = value;
  value.later = 42;
  error(() => encode(value), "E8001", "cyclic value cannot be encoded as JSON");
  value.first = null;
  error(() => encode(value), "E8001", "expected string");
  value.later = "valid";
  value.grid[0][0] = false;
  const failure = error(() => encode(value), "E8003");
  equal(failure.expected, "[number]", "outer element descriptor");
  equal(failure.cause.code, "E8001", "unwrapped leaf cause");
  equal(failure.cause.message, "expected number", "leaf descriptor failure");
  value.grid[0][0] = 1;
  const child = rt.makeTable({x:1}); value.first = rt.makeTable({a:child,b:child});
  equal(encode(value), '{"first":{"a":{"x":1},"b":{"x":1}},"later":"valid","grid":[[1]]}', "shared children serialize independently");
});
test("encoding rejects cyclic class graphs", () => {
  const a = rt.classPlan("@m/Node", [field("next", "?@m/Node")], {next:null}, ...LOC);
  rt.setProp(a, "next", a); error(() => encode(a), "E8001", "cyclic value cannot be encoded as JSON");
});
test("encoding rejects cyclic Record fields", () => {
  const a = rt.classPlan("@m/Record", [field("data", "record")], {data:rt.makeTable({})}, ...LOC);
  a.data.set("self", a.data); error(() => encode(a), "E8001", "cyclic value cannot be encoded as JSON");
});
test("nonfinite class fields substitute null without changing storage", () => {
  const a = rt.classPlan("@m/N", [field("x", "number")], {x:0}, ...LOC);
  for (const value of [NaN, Infinity, -Infinity]) {
    rt.setProp(a, "x", value); equal(encode(a), '{"x":null}', "nonfinite substitution"); equal(a.x, value, "unchanged number");
  }
});
test("function-valued Record members omit without changing identity", () => {
  const a = rt.classPlan("@m/Record", [field("data", "record")], {data:rt.makeTable({})}, ...LOC);
  const fn = rt.function("()->null", () => null); a.data.set("f", fn);
  equal(encode(a), '{"data":{}}', "function omission"); equal(a.data.get("f"), fn, "function identity");
});
test("missing Record members omit without modifying presence", () => {
  const a = rt.classPlan("@m/Record", [field("data", "record")], {data:rt.makeTable({})}, ...LOC);
  a.data.set("bad", rt.MISSING); equal(encode(a), '{"data":{}}', "missing omission");
  equal(a.data.get("bad"), rt.MISSING, "missing storage"); assert(a.data.has("bad"), "presence unchanged");
});
test("large number class fields encode as binary64", () => {
  const a = rt.classPlan("@m/Wide", [field("age", "number")], {age:0}, ...LOC);
  rt.setProp(a, "age", 9007199254740992); equal(encode(a), '{"age":9007199254740992}', "wide encoding");
});
test("scanner conversion and contextual reconstruction preserve graph shapes", () => {
  const a = decode("[1,2]", "unknown"); assert(Array.isArray(a), "native array"); equal(a[1], 2, "element");
  equal(decode("null", "unknown"), null, "language null"); equal(encode(a), "[1,2]", "array encoding");
  equal(encode(decode('{"a":[1],"n":null}', "record")), '{"a":[1],"n":null}', "nested shapes");
  const scanned = rt.jsonParseText('{"i":1.0,"n":1.5}', ...LOC);
  equal(scanned.i, 1, "scanner integral value"); equal(scanned.n, 1.5, "scanner fractional value");
  equal(encode(decode('{"i":1.0,"n":1.5}', "record")), '{"i":1,"n":1.5}', "canonical text");
});
test("classPlan tags provided values and preserves optional absence", () => {
  const a = rt.classPlan("@m/U", [field("name", "string"), field("nick", "string", true)], {name:"Bob"}, ...LOC);
  equal(a.$kind, "class", "tag"); equal(a.$classname, "@m/U", "identity"); equal(a.name, "Bob", "provided");
  equal(a.nick, rt.MISSING, "missing optional");
});
test("classPlan rejects omissions and never invokes per-entry hooks", () => {
  let runs = 0; const fields = [field("a", "number"), field("b", "number")];
  for (const f of fields) f.evaluator = () => { runs++; return 1; };
  error(() => rt.classPlan("C", fields, null, ...LOC), "E8001", "missing required field 'a'");
  const a = rt.classPlan("C", fields, {a:1,b:2}, ...LOC), b = rt.classPlan("C", fields, {a:1,b:2}, ...LOC);
  equal(a.a + a.b + b.a + b.b, 6, "per-attempt fields"); equal(runs, 0, "hook count");
});
test("provided fields suppress arbitrary evaluator metadata", () => {
  let runs = 0; const fields = [field("a", "number"), field("b", "number")];
  for (const f of fields) f.evaluator = () => { runs++; return 1; };
  const a = rt.classPlan("C", fields, {a:5,b:6}, ...LOC); equal(a.a + a.b, 11, "provided values"); equal(runs, 0, "no hooks");
});
test("construction extra fields reject before evaluator metadata", () => {
  let runs = 0; const fields = [field("a", "number")]; fields[0].evaluator = () => { runs++; return 1; };
  error(() => rt.classPlan("C", fields, {extra:1}, ...LOC), "E8007", "extra field 'extra'"); equal(runs, 0, "no hooks");
});
test("construction validates supplied fields and preserves nullable presence", () => {
  const fields = [field("a", "number"), field("nick", "?string", true)];
  error(() => rt.classPlan("C", fields, {a:"bad"}, ...LOC), "E8001", "expected number");
  error(() => rt.classPlan("C", fields, {a:1,nick:42}, ...LOC), "E8001", "expected string");
  equal(rt.classPlan("C", fields, {a:1,nick:null}, ...LOC).nick, null, "present null");
});
test("construction omissions precede throwing evaluator metadata", () => {
  const fields = [field("a", "number")]; fields[0].evaluator = () => { throw new Error("hook invoked"); };
  error(() => rt.classPlan("C", fields, null, ...LOC), "E8001", "missing required field 'a'");
});
test("malformed construction plans reject without invoking metadata", () => {
  for (const plan of [{length:1}, ["x"], [{name:1,descriptor:"number",optional:false}],
      [{name:"x",descriptor:2,optional:false}], [{name:"x",descriptor:"number",optional:"yes"}],
      [field("x", "number"),field("x", "number")]])
    error(() => rt.classPlan("C", plan, null, ...LOC), "E8001");
  equal(rt.classPlan("C", [{...field("x", "number"),evaluator:3}], {x:7}, ...LOC).x, 7, "non-authoritative metadata");
  error(() => rt.classPlan("C", [field("a", "number")], 42, ...LOC), "E8001", "class field values must be a table");
});
test("reconstruction ignores evaluator metadata through every outcome", () => {
  let runs = 0; const fields = [field("a", "number"), field("b", "number")];
  for (const f of fields) f.evaluator = () => { runs++; return 1; };
  const id = schema("@m/Every", fields);
  rejects('{"extra":1}', id, "E8007"); rejects('{"b":"x"}', id); rejects('{"b":20}', id);
  equal(decode('{"a":1,"b":20}', id).b, 20, "complete control");
  equal(decode('{"a":5,"b":6}', id).a, 5, "second complete control"); equal(runs, 0, "hook count");
});
console.log("Contextual JSON JS Results: " + passed + " passed, " + failed + " failed");
if (failed) throw new Error(failed + " contextual JSON JS test(s) failed");
