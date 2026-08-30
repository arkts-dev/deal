"use strict";

// Test suite for the DEAL JavaScript host ABI runtime loader
// ($rt.loadHost/$rt.hostFunction/$rt.hostAsyncFunction and the
// declared-host-class synthesis, js-v12-host-abi-completion D1-D5).
// Run from the repo root:
//
//   node test_host_js.js
//
// Dependency-free and self-asserting: no assertion library; a failed
// assertion throws with the failing case named, and the final throw
// makes the process exit non-zero, so run_tests.sh's node-guarded block
// fails the gate via set -e. The script writes no files.
//
// The suite drives deal/runtime.js directly under the real node binary
// with hand-built declared maps (the emitter-rendered D1 shape) and real
// JS host objects — the loader's contract, the pinned E8011/E8010/E8001/
// E8004 codes, and the boundary composition rules, independent of the
// emitter (the emitter lane lands separately). Every boundary probe
// routes through the canonical matcher rows (checkType) and the bytes
// row (checkBytes), so a broken descriptor parser or bytes row fails the
// suite. Hostile strings (lone surrogates) are built at runtime with
// String.fromCharCode — no raw hostile byte is committed literally to
// this file (the test_stdlib.lua convention).

const rt = require("./deal/runtime");

// Activate the v1.2 signed-int32 profile (the corpus profile under
// V1_2_ACTIVE): the E8004 range pins below are the signed-int32
// boundaries (±2147483648). Activation is one-way per runtime
// instance, mirroring the emitter's module-shape setInt32Mode call.
rt.setInt32Mode(true);

// ===== Harness (mirrors test_jsonable_js.js / test_stdlib_js.js) =====

const tests = [];
let passed = 0;
let failed = 0;

function test(name, fn) {
  tests.push([name, fn]);
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

function assertSpan(err, file, line, column) {
  assertEqual(err.file, file, "error should carry the forwarded file");
  assertEqual(err.line, line, "error should carry the forwarded line");
  assertEqual(err.column, column, "error should carry the forwarded column");
}

// ===== Declared-map builders (the emitter-rendered D1 shape) =====

function fnDecl(descriptor) {
  return { $k: "function", $d: descriptor };
}

function classDecl(identity, fields) {
  return { $k: "class", $d: identity, $fields: fields };
}

function field(name, descriptor, optional, nullable, hasDefault) {
  return {
    name: name,
    $d: descriptor,
    optional: !!optional,
    nullable: !!nullable,
    hasDefault: !!hasDefault,
  };
}

// =========================================================================
// loadHost: function exports
// =========================================================================

test("loadHost wraps a raw JS function export with the declared descriptor", function() {
  const surface = rt.loadHost({
    ping: function () { return "pong"; },
  }, { ping: fnDecl("()->string") });
  assertEqual(surface.ping.$kind, "function", "wrapper kind tag");
  assertEqual(surface.ping.$sig, "()->string", "wrapper carries the declared descriptor byte-exact");
  assert(typeof surface.ping.$f === "function", "wrapper $f is a function");
  assertEqual(surface.ping.$f("probe.js", 1, 1), "pong", "call delivers the host value");
});

test("loadHost invokes the raw host function exactly once per call", function() {
  let calls = 0;
  const surface = rt.loadHost({
    ping: function () { calls++; return "pong"; },
  }, { ping: fnDecl("()->string") });
  surface.ping.$f("probe.js", 1, 1);
  assertEqual(calls, 1, "one host invocation per call");
});

test("loadHost raises E8011 for a missing declared export", function() {
  assertError(function() {
    rt.loadHost({ ping: function () { return "pong"; } },
      { missing: fnDecl("()->string") });
  }, "E8011", "missing host export 'missing'", "missing export is load-time E8011");
});

test("loadHost re-wraps a pre-wrapped export with a byte-equal $sig", function() {
  const surface = rt.loadHost({
    ping: {
      $kind: "function",
      $sig: "()->string",
      $f: function () { return "pong"; },
    },
  }, { ping: fnDecl("()->string") });
  assertEqual(surface.ping.$kind, "function", "re-wrapped surface kind");
  assertEqual(surface.ping.$sig, "()->string", "re-wrapped signature is the declared descriptor");
  assertEqual(surface.ping.$f("probe.js", 1, 1), "pong", "re-wrapped call works");
});

test("loadHost raises E8011 when a pre-wrapped $sig mismatches the declared descriptor", function() {
  assertError(function() {
    rt.loadHost({
      ping: {
        $kind: "function",
        $sig: "()->int",
        $f: function () { return "pong"; },
      },
    }, { ping: fnDecl("()->string") });
  }, "E8011", "signature mismatch: expected ()->string, got ()->int",
    "pre-wrapped $sig mismatch is load-time E8011 (the declared contract is the only trusted metadata)");
});

test("loadHost raises E8011 when a pre-wrapped export lacks its $sig", function() {
  assertError(function() {
    rt.loadHost({
      ping: { $kind: "function", $f: function () { return "pong"; } },
    }, { ping: fnDecl("()->string") });
  }, "E8011", "signature mismatch: expected ()->string, got undefined",
    "missing pre-wrapped $sig is load-time E8011");
});

test("loadHost raises E8011 when a pre-wrapped export has a non-function $f", function() {
  assertError(function() {
    rt.loadHost({
      ping: { $kind: "function", $sig: "()->string", $f: 42 },
    }, { ping: fnDecl("()->string") });
  }, "E8011", "has non-function $f",
    "non-function pre-wrapped $f is load-time E8011");
});

test("loadHost raises E8011 for any other export shape on a function entry", function() {
  assertError(function() {
    rt.loadHost({ ping: 42 }, { ping: fnDecl("()->string") });
  }, "E8011", "is not a function",
    "a non-function, non-wrapper export is load-time E8011");
});

test("the re-wrapped pre-wrapped export gets identical call-time enforcement", function() {
  const surface = rt.loadHost({
    ping: {
      $kind: "function",
      $sig: "()->int",
      $f: function () { return "junk"; },
    },
  }, { ping: fnDecl("()->int") });
  assertError(function() {
    surface.ping.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected int",
    "re-wrapped $f enforces the declared return (the host's own wrapper object was discarded)");
});

test("loadHost structurally drops extra host exports", function() {
  const surface = rt.loadHost({
    ping: function () { return "pong"; },
    extra: 42,
    helper: function () { return "unreachable"; },
  }, { ping: fnDecl("()->string") });
  assertEqual(Object.keys(surface).join(","), "ping",
    "the surface carries only declared names");
  assertEqual(surface.ping.$f("probe.js", 1, 1), "pong", "declared export works");
});

test("loadHost raises E8011 for a non-object declared map", function() {
  assertError(function() {
    rt.loadHost({}, "not a map");
  }, "E8011", "host module declarations must be a table",
    "defensive declared-map shape check");
});

test("loadHost raises E8011 for a non-object raw exports value", function() {
  assertError(function() {
    rt.loadHost(42, { ping: fnDecl("()->string") });
  }, "E8011", "host module did not return a module object",
    "defensive raw-exports shape check");
});

test("loadHost raises E8011 for a function entry whose descriptor fails the canonical grammar", function() {
  assertError(function() {
    rt.loadHost({ ping: function () { return "pong"; } },
      { ping: fnDecl("string[]") });
  }, "E8011", "has an unsupported declared descriptor",
    "a legacy dialect descriptor is load-time E8011");
});

test("loadHost raises E8011 for a declared entry with an unknown $k", function() {
  assertError(function() {
    rt.loadHost({ ping: function () { return "pong"; } },
      { ping: { $k: "mystery", $d: "()->string" } });
  }, "E8011", "has an unsupported declared descriptor",
    "defensive unknown-kind arm");
});

// =========================================================================
// hostFunction: parameter checks
// =========================================================================

test("a parameter kind mismatch raises E8010 with the pinned message and the forwarded span", function() {
  const surface = rt.loadHost({
    double: function (x) { return x * 2; },
  }, { double: fnDecl("(int)->int") });
  const err = assertError(function() {
    surface.double.$f("junk", "probe.js", 7, 3);
  }, "E8010", "parameter 1 type mismatch: expected int",
    "wrong-kind parameter is E8010 at the call boundary");
  assertSpan(err, "probe.js", 7, 3);
  assertEqual(err.expected, "int", "expected carries the parameter descriptor");
  assertEqual(err.actual, "string", "actual carries the value kind");
});

test("an out-of-range int parameter raises E8010 with the inner E8004 range message", function() {
  const surface = rt.loadHost({
    take: function (x) { return x; },
  }, { take: fnDecl("(int)->int") });
  assertError(function() {
    surface.take.$f(2147483648, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: int out of safe range",
    "the int32 E8004 range check runs at the host parameter boundary");
});

test("an unpaired-surrogate string parameter raises E8010 with the inner boundary message", function() {
  const surface = rt.loadHost({
    take: function (s) { return s; },
  }, { take: fnDecl("(string)->string") });
  const hostile = "a" + String.fromCharCode(0xD800) + "b";
  assertError(function() {
    surface.take.$f(hostile, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected string, got invalid UTF-8 encoding",
    "string boundary validation runs at the host parameter boundary");
});

test("exact arity: too few arguments raise E8010", function() {
  const surface = rt.loadHost({
    add: function (a, b) { return a + b; },
  }, { add: fnDecl("(int,int)->int") });
  assertError(function() {
    surface.add.$f(1, "probe.js", 1, 1);
  }, "E8010", "expected at least 2 arguments, got 1",
    "the v1.2 fixed-arity rule rejects short calls");
});

test("exact arity: too many arguments raise E8010", function() {
  const surface = rt.loadHost({
    add: function (a, b) { return a + b; },
  }, { add: fnDecl("(int,int)->int") });
  assertError(function() {
    surface.add.$f(1, 2, 3, "probe.js", 1, 1);
  }, "E8010", "expected 2 arguments, got 3",
    "the v1.2 fixed-arity rule rejects long calls");
});

// =========================================================================
// hostFunction: return checks
// =========================================================================

test("a junk return raises E8010 with the pinned message", function() {
  const surface = rt.loadHost({
    getNumber: function () { return "not a number"; },
  }, { getNumber: fnDecl("()->int") });
  const err = assertError(function() {
    surface.getNumber.$f("probe.js", 7, 3);
  }, "E8010", "return value 1 type mismatch: expected int",
    "wrong-kind return is E8010 at the call boundary");
  assertSpan(err, "probe.js", 7, 3);
  assertEqual(err.expected, "int", "expected carries the return descriptor");
  assertEqual(err.actual, "string", "actual carries the value kind");
});

test("a zero-result return on a non-null declared return raises E8010 (got nothing)", function() {
  const surface = rt.loadHost({
    ping: function () { },
  }, { ping: fnDecl("()->int") });
  assertError(function() {
    surface.ping.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected int, got nothing",
    "the presence rule rejects zero-result returns — the discard call path included");
});

test("a junk null return on the declared ()->null raises E8010", function() {
  const surface = rt.loadHost({
    ping: function () { return "junk"; },
  }, { ping: fnDecl("()->null") });
  assertError(function() {
    surface.ping.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected null",
    "a non-null value crossing the declared ()->null boundary is E8010");
});

test("a zero-result return on the declared ()->null raises E8010 (got nothing)", function() {
  const surface = rt.loadHost({
    ping: function () { },
  }, { ping: fnDecl("()->null") });
  assertError(function() {
    surface.ping.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected null, got nothing",
    "the sync-null presence rule rejects zero-result returns");
});

test("the DEAL null satisfies the declared ()->null return", function() {
  const surface = rt.loadHost({
    ping: function () { return null; },
  }, { ping: fnDecl("()->null") });
  assertNull(surface.ping.$f("probe.js", 1, 1),
    "the JS null sentinel passes the sync-null return");
});

test("the DEAL null passes a nullable return and present values pass through", function() {
  const surface = rt.loadHost({
    find: function (s) { return s === "__NULL__" ? null : s; },
  }, { find: fnDecl("(string)->?string") });
  assertNull(surface.find.$f("__NULL__", "probe.js", 1, 1),
    "null passes the declared ?string return");
  assertEqual(surface.find.$f("value", "probe.js", 1, 1), "value",
    "a present value passes the declared ?string return");
});

test("an out-of-range int return raises E8010 with the inner E8004 range message", function() {
  const surface = rt.loadHost({
    getNumber: function () { return 2147483648; },
  }, { getNumber: fnDecl("()->int") });
  assertError(function() {
    surface.getNumber.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: int out of safe range",
    "the int32 E8004 range check runs at the host return boundary");
});

test("an unpaired-surrogate string return raises E8010 with the inner boundary message", function() {
  const surface = rt.loadHost({
    badString: function () {
      return "a" + String.fromCharCode(0xD800) + "b";
    },
  }, { badString: fnDecl("()->string") });
  assertError(function() {
    surface.badString.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected string, got invalid UTF-8 encoding",
    "string boundary validation runs at the host return boundary");
});

// =========================================================================
// hostFunction: array boundaries
// =========================================================================

test("an array parameter checks the JS Array carrier first", function() {
  const surface = rt.loadHost({
    join: function (parts) { return parts.join(","); },
  }, { join: fnDecl("([string])->string") });
  assertError(function() {
    surface.join.$f(42, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected array",
    "a non-Array argument is E8010 at the parameter position");
});

test("an array parameter checks elements against the element descriptor", function() {
  const surface = rt.loadHost({
    join: function (parts) { return parts.join(","); },
  }, { join: fnDecl("([string])->string") });
  assertError(function() {
    surface.join.$f(["a", 7], "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: array element 2 type mismatch: expected string",
    "an element mismatch reports E8010 at the parameter position with the 1-based index");
});

test("a valid array parameter and return cross the boundary", function() {
  const surface = rt.loadHost({
    split: function () { return ["a", "b", "c"]; },
    join: function (parts) { return parts.join(","); },
  }, {
    split: fnDecl("()->[string]"),
    join: fnDecl("([string])->string"),
  });
  const parts = surface.split.$f("probe.js", 1, 1);
  assertEqual(parts.join(""), "abc", "the array return is a real JS Array");
  assertEqual(surface.join.$f(parts, "probe.js", 1, 1), "a,b,c",
    "the array roundtrip passes the element checks");
});

test("an array return checks the carrier and its elements", function() {
  const badCarrier = rt.loadHost({
    split: function () { return "x"; },
  }, { split: fnDecl("()->[string]") });
  assertError(function() {
    badCarrier.split.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected array",
    "a non-Array return is E8010");

  const badElement = rt.loadHost({
    split: function () { return [1, 2]; },
  }, { split: fnDecl("()->[string]") });
  assertError(function() {
    badElement.split.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: array element 1 type mismatch: expected string",
    "an element mismatch on the return is E8010");
});

// =========================================================================
// hostFunction: function-typed boundaries
// =========================================================================

test("a byte-equal DEAL wrapper crosses a function-typed parameter unchanged", function() {
  let received = null;
  const surface = rt.loadHost({
    apply: function (f, v) {
      received = f;
      return f.$f(v) + 100;
    },
  }, { apply: fnDecl("((int)->int,int)->int") });
  const addOne = rt.function("(int)->int", function(x) { return x + 1; });
  assertEqual(surface.apply.$f(addOne, 41, "probe.js", 1, 1), 142,
    "the host invokes the wrapper through .$f");
  assert(received === addOne, "the wrapper is delivered unchanged (DEAL→host adaptation)");
});

test("any function-signature delta on a function-typed parameter raises E8010", function() {
  const surface = rt.loadHost({
    apply: function (f, v) { return f.$f(v) + 100; },
  }, { apply: fnDecl("((int)->int,int)->int") });
  const wrong = rt.function("(int)->string", function(x) { return String(x); });
  assertError(function() {
    surface.apply.$f(wrong, 41, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: function signature mismatch: expected (int)->int, got (int)->string",
    "a signature delta is E8010 with the byte-exact expected/got signatures");
});

test("the DEAL null on a non-nullable function parameter raises E8010", function() {
  const surface = rt.loadHost({
    apply: function (f, v) { return f.$f(v) + 100; },
  }, { apply: fnDecl("((int)->int,int)->int") });
  assertError(function() {
    surface.apply.$f(null, 41, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected function",
    "null crossing a non-nullable function parameter is E8010");
});

test("a raw JS function never satisfies a function-typed parameter", function() {
  const surface = rt.loadHost({
    apply: function (f, v) { return f(v) + 100; },
  }, { apply: fnDecl("((int)->int,int)->int") });
  assertError(function() {
    surface.apply.$f(function(x) { return x + 1; }, 41, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected function",
    "a raw JS function carries no DEAL wrapper signature and fails E8010");
});

test("a nullable function parameter accepts the DEAL null and a matching wrapper", function() {
  const surface = rt.loadHost({
    register: function (cb) {
      if (cb === null || cb === undefined) {
        return 0;
      }
      return cb.$f(41);
    },
  }, { register: fnDecl("(?(int)->int)->int") });
  assertEqual(surface.register.$f(null, "probe.js", 1, 1), 0,
    "the DEAL null passes the nullable function parameter");
  const inc = rt.function("(int)->int", function(x) { return x + 1; });
  assertEqual(surface.register.$f(inc, "probe.js", 1, 1), 42,
    "a byte-equal wrapper passes the nullable function parameter");
});

test("a raw host-returned JS function fails a function-typed return (never wrapped)", function() {
  const surface = rt.loadHost({
    getCallback: function () { return function(x) { return x; }; },
  }, { getCallback: fnDecl("()->(int)->int") });
  assertError(function() {
    surface.getCallback.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected function",
    "function-typed returns are never wrapped — a raw JS function fails E8010");
});

test("a byte-equal DEAL wrapper passes a function-typed return", function() {
  const cb = rt.function("(int)->int", function(x) { return x + 1; });
  const surface = rt.loadHost({
    getCallback: function () { return cb; },
  }, { getCallback: fnDecl("()->(int)->int") });
  assert(surface.getCallback.$f("probe.js", 1, 1) === cb,
    "a byte-equal DEAL wrapper passes the function-typed return");
});

test("a nullable function return accepts the DEAL null", function() {
  const surface = rt.loadHost({
    getCallback: function (mode) { return mode === "bad" ? function(x) { return x; } : null; },
  }, { getCallback: fnDecl("(string)->?(int)->int") });
  assertNull(surface.getCallback.$f("ok", "probe.js", 1, 1),
    "null passes the nullable function return");
  assertError(function() {
    surface.getCallback.$f("bad", "probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected function",
    "a raw JS function on the nullable function return is E8010");
});

// =========================================================================
// hostFunction: bytes boundaries (canonical matcher rows + bytes row)
// =========================================================================

test("a bytes-typed boundary accepts a real Uint8Array parameter and return", function() {
  const surface = rt.loadHost({
    echo: function (b) { return b; },
  }, { echo: fnDecl("(bytes)->bytes") });
  const b = new Uint8Array(3);
  b[0] = 65;
  assert(surface.echo.$f(b, "probe.js", 1, 1) === b,
    "the Uint8Array carrier roundtrips the declared bytes boundary");
});

test("a bytes-typed parameter rejects any other value with E8010", function() {
  const surface = rt.loadHost({
    echo: function (b) { return b; },
  }, { echo: fnDecl("(bytes)->bytes") });
  assertError(function() {
    surface.echo.$f(42, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected bytes",
    "the matcher bytes row rejects a non-bytes parameter");
});

test("a bytes-typed return rejects a non-bytes value with E8010", function() {
  const surface = rt.loadHost({
    echo: function () { return [1, 2, 3]; },
  }, { echo: fnDecl("()->bytes") });
  assertError(function() {
    surface.echo.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected bytes",
    "the matcher bytes row rejects a non-bytes return");
});

// =========================================================================
// hostAsyncFunction
// =========================================================================

test("a non-thenable async operation raises E8010 at the call site", function() {
  const surface = rt.loadHost({
    fetchValue: function () { return 42; },
  }, { fetchValue: fnDecl("async()->string") });
  assertEqual(surface.fetchValue.$sig, "async()->string",
    "the async wrapper carries the full declared async descriptor");
  return (async function() {
    const err = await assertErrorAsync(function() {
      return surface.fetchValue.$f("probe.js", 7, 3);
    }, "E8010", "host async function must return an async operation, got number");
    assertSpan(err, "probe.js", 7, 3);
  })();
});

test("a zero-result async operation raises E8010 with the nothing actual", function() {
  const surface = rt.loadHost({
    fetchValue: function () { },
  }, { fetchValue: fnDecl("async()->string") });
  return assertErrorAsync(function() {
    return surface.fetchValue.$f("probe.js", 1, 1);
  }, "E8010", "host async function must return an async operation, got nothing");
});

test("an async completion violating the declared R raises E8001 at the await boundary", function() {
  const surface = rt.loadHost({
    fetchValue: function () { return Promise.resolve(42); },
  }, { fetchValue: fnDecl("async()->string") });
  return assertErrorAsync(function() {
    return surface.fetchValue.$f("probe.js", 1, 1);
  }, "E8001", "expected string",
    "the completion check against the declared R is E8001 (await-site contract)");
});

test("a valid async completion delivers the checked value", function() {
  const surface = rt.loadHost({
    fetchValue: function () { return Promise.resolve("fetched"); },
  }, { fetchValue: fnDecl("async()->string") });
  return (async function() {
    assertEqual(await surface.fetchValue.$f("probe.js", 1, 1), "fetched",
      "the awaited host call delivers the completion value");
  })();
});

test("async parameter checks run as the sync wrapper's", function() {
  const surface = rt.loadHost({
    fetchValue: function (x) { return Promise.resolve(x); },
  }, { fetchValue: fnDecl("async(int)->int") });
  return assertErrorAsync(function() {
    return surface.fetchValue.$f("junk", "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected int",
    "async host functions check parameters before the invocation");
});

test("a rejected async operation propagates natively at the await site", function() {
  const surface = rt.loadHost({
    fetchValue: function () { return Promise.reject(new Error("boom")); },
  }, { fetchValue: fnDecl("async()->string") });
  return (async function() {
    let caught = null;
    try {
      await surface.fetchValue.$f("probe.js", 1, 1);
    } catch (e) {
      caught = e;
    }
    assert(caught !== null, "the rejection must reach the await site");
    assertEqual(String(caught.message), "boom", "the native rejection propagates");
  })();
});

// =========================================================================
// loadHost: declared host classes (D3)
// =========================================================================

const CFG_IDENTITY = "@$external/host.cfg/ServerConfig";
const ENDPOINT_IDENTITY = "@$external/host.cfg/Endpoint";

function cfgDeclaredMap() {
  return {
    Endpoint: classDecl(ENDPOINT_IDENTITY, [
      field("path", "string", false, false, true),
    ]),
    ServerConfig: classDecl(CFG_IDENTITY, [
      field("port", "int", false, false, true),
      field("endpoint", ENDPOINT_IDENTITY, false, false, false),
      field("tags", "[string]", true, false, false),
      field("note", "?string", true, true, false),
    ]),
    describe: fnDecl("(" + CFG_IDENTITY + ")->string"),
  };
}

function cfgRawExports() {
  return {
    Endpoint: { $kind: "class", $classname: ENDPOINT_IDENTITY },
    Endpoint_defaults: { path: "/" },
    ServerConfig: { $kind: "class", $classname: CFG_IDENTITY },
    ServerConfig_defaults: { port: 8080 },
    describe: function (s) { return s.endpoint.path + ":" + s.port; },
  };
}

test("loadHost validates the class META and synthesizes <C>$new and <C>$fields", function() {
  const surface = rt.loadHost(cfgRawExports(), cfgDeclaredMap());
  assertEqual(surface.ServerConfig.$kind, "class", "the validated META stays under the declared name");
  assertEqual(surface.ServerConfig.$classname, CFG_IDENTITY,
    "the META identity is the declared canonical externals projection");
  assert(typeof surface["ServerConfig$new"] === "function",
    "the synthesized <C>$new constructor is present");
  assert(Array.isArray(surface["ServerConfig$fields"]),
    "the synthesized <C>$fields array is present");
  assertEqual(surface["ServerConfig$fields"].length, 4,
    "the <C>$fields metadata matches the declared fields");
  assertEqual(surface["ServerConfig$fields"][1].$d, ENDPOINT_IDENTITY,
    "field metadata carries the declared descriptor");
});

test("loadHost raises E8011 for a host class META with a foreign identity", function() {
  const raw = cfgRawExports();
  raw.ServerConfig = { $kind: "class", $classname: "@host.cfg/Legacy" };
  assertError(function() {
    rt.loadHost(raw, cfgDeclaredMap());
  }, "E8011", "identity mismatch: expected " + CFG_IDENTITY + ", got @host.cfg/Legacy",
    "a foreign class identity is load-time E8011");
});

test("loadHost raises E8011 for a non-class host class META", function() {
  const raw = cfgRawExports();
  raw.ServerConfig = { path: "/" };
  assertError(function() {
    rt.loadHost(raw, cfgDeclaredMap());
  }, "E8011", "is not a class meta",
    "a mis-shaped class export is load-time E8011");
});

test("loadHost raises E8011 when the host class defaults are missing", function() {
  const raw = cfgRawExports();
  delete raw.ServerConfig_defaults;
  assertError(function() {
    rt.loadHost(raw, cfgDeclaredMap());
  }, "E8011", "is missing its defaults",
    "construction depends on the host defaults — their absence is load-time E8011");
});

test("the synthesized constructor applies provided fields, defaults, and optional absence", function() {
  const surface = rt.loadHost(cfgRawExports(), cfgDeclaredMap());
  const endpoint = surface["Endpoint$new"]({ path: "/api" }, "probe.js", 1, 1);
  assertEqual(endpoint.$kind, "class", "constructed endpoint kind tag");
  assertEqual(endpoint.$classname, ENDPOINT_IDENTITY, "constructed endpoint identity");
  assertEqual(endpoint.path, "/api", "provided field overlaid");
  const cfg = surface["ServerConfig$new"]({
    port: 9090,
    endpoint: endpoint,
    tags: ["dev"],
    note: null,
  }, "probe.js", 1, 1);
  assertEqual(cfg.port, 9090, "provided field overlaid");
  assert(cfg.endpoint === endpoint, "provided class field kept by reference");
  assertEqual(cfg.tags[0], "dev", "provided array field kept");
  assertNull(cfg.note, "provided nullable field kept");
  assertEqual(cfg.$classname, CFG_IDENTITY, "constructed instance carries the declared identity");

  const defaulted = surface["ServerConfig$new"]({ endpoint: endpoint }, "probe.js", 1, 1);
  assertEqual(defaulted.port, 8080, "omitted defaulted field takes the host default");
  assert(defaulted.tags === rt.MISSING, "omitted optional field is MISSING");
  assertNull(rt.optRead(defaulted.tags), "optRead maps the absent optional to null");
  assert(!rt.has(defaulted, "tags"), "has() is false for the absent optional");
  assert(!rt.has(defaulted, "note"), "has() is false for the absent nullable optional");
  assert(rt.has(cfg, "note"), "has() is true for the provided nullable-null field");
});

test("the synthesized constructor raises E8007 for an extra provided field", function() {
  const surface = rt.loadHost(cfgRawExports(), cfgDeclaredMap());
  assertError(function() {
    surface["ServerConfig$new"]({ zzz: 1 }, "probe.js", 1, 1);
  }, "E8007", "extra field 'zzz' in class 'ServerConfig'",
    "the ordinary class machinery rejects undeclared provided fields");
});

test("a zero-arg defaults function is accepted and re-evaluated per construction", function() {
  const raw = cfgRawExports();
  let evals = 0;
  raw.ServerConfig_defaults = function() {
    evals++;
    return { port: 7000 };
  };
  const surface = rt.loadHost(raw, cfgDeclaredMap());
  assertEqual(evals, 0, "the defaults function is not evaluated at load time");
  const a = surface["ServerConfig$new"]({}, "probe.js", 1, 1);
  const b = surface["ServerConfig$new"]({}, "probe.js", 1, 1);
  assertEqual(evals, 2, "the defaults function runs once per construction");
  assertEqual(a.port, 7000, "function-provided default applied");
  assertEqual(b.port, 7000, "function-provided default applied again");
});

test("the <C>$defaults key is accepted as the host defaults spelling", function() {
  const raw = cfgRawExports();
  delete raw.ServerConfig_defaults;
  raw["ServerConfig$defaults"] = { port: 6000 };
  const surface = rt.loadHost(raw, cfgDeclaredMap());
  const cfg = surface["ServerConfig$new"]({}, "probe.js", 1, 1);
  assertEqual(cfg.port, 6000, "the $defaults-keyed host defaults apply");
});

test("a class-typed parameter validates nominal identity and a matching instance crosses", function() {
  const surface = rt.loadHost(cfgRawExports(), cfgDeclaredMap());
  const endpoint = surface["Endpoint$new"]({ path: "/api" }, "probe.js", 1, 1);
  const cfg = surface["ServerConfig$new"]({ port: 9090, endpoint: endpoint }, "probe.js", 1, 1);
  assertEqual(surface.describe.$f(cfg, "probe.js", 1, 1), "/api:9090",
    "the constructed instance crosses the class-typed boundary roundtrip");
});

test("a foreign-identity value at a class-typed parameter raises the pinned E8010", function() {
  const surface = rt.loadHost(cfgRawExports(), cfgDeclaredMap());
  const foreign = { $kind: "class", $classname: "@other/Thing" };
  const err = assertError(function() {
    surface.describe.$f(foreign, "probe.js", 1, 1);
  }, "E8010", "parameter 1 type mismatch: expected instance of " + CFG_IDENTITY + ", got @other/Thing",
    "nominal identity mismatch at the parameter is E8010 with the pinned expected/got");
  assertEqual(err.expected, CFG_IDENTITY, "expected carries the declared identity");
  assertEqual(err.actual, "class", "actual carries the value kind (the identity texts live in the message)");
});

test("a foreign-identity value at a class-typed return raises the pinned E8010", function() {
  const foreign = { $kind: "class", $classname: "@other/Thing" };
  const surface = rt.loadHost({
    make: function () { return foreign; },
  }, { make: fnDecl("()->" + CFG_IDENTITY) });
  assertError(function() {
    surface.make.$f("probe.js", 1, 1);
  }, "E8010", "return value 1 type mismatch: expected instance of " + CFG_IDENTITY + ", got @other/Thing",
    "nominal identity mismatch at the return is E8010 with the pinned expected/got");
});

// =========================================================================
// Misc load-time / boundary defenses
// =========================================================================

test("the E8004 int range check remains reachable through the canonical matcher row", function() {
  assertError(function() {
    rt.checkType("int", 2147483648, "probe.js", 1, 1);
  }, "E8004", "int out of safe range",
    "the matcher int row raises E8004 directly at the value boundary");
});

test("the returned surface is fresh and never mutates the raw exports", function() {
  const raw = cfgRawExports();
  const surface = rt.loadHost(raw, cfgDeclaredMap());
  surface["ServerConfig$new"]({}, "probe.js", 1, 1);
  assert(!("ServerConfig$new" in raw), "the raw exports object gains no synthesized keys");
  assertEqual(Object.keys(raw).join(","),
    "Endpoint,Endpoint_defaults,ServerConfig,ServerConfig_defaults,describe",
    "the raw exports object is unchanged");
});

// ===== Async-capable assertion helper =====

function assertErrorAsync(fn, code, needle, msg) {
  return (async function() {
    let caught = null;
    try {
      await fn();
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
  })();
}

// ===== Runner =====

(async function() {
  for (let i = 0; i < tests.length; i++) {
    const name = tests[i][0];
    const fn = tests[i][1];
    try {
      await fn();
      passed++;
      console.log("PASS: " + name);
    } catch (e) {
      failed++;
      console.log("FAIL: " + name + " -- " + (e && e.message ? e.message : String(e)));
    }
  }
  console.log("Host ABI runtime JS tests: " + passed + " passed, "
    + failed + " failed");
  if (failed > 0) {
    throw new Error(failed + " host ABI runtime JS test(s) failed");
  }
})();
