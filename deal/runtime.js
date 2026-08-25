"use strict";

// DEAL Runtime Library v1.2 — JavaScript backend (deal/runtime.js).
// The single hand-written CommonJS runtime module exporting $rt, the JS
// analog of deal/runtime.lua (js-backend-runtime D1-D9, pinned by
// js-backend-runtime-artifact). It loads standalone under node v24 from
// "./deal/runtime" and from any "<relpath>/deal/runtime" require path —
// no require call anywhere in the file (js-backend-runtime-artifact D1/D10).

// ===== Host-global capture (js-backend-runtime-artifact D10) =====
// The only module-scope bindings of the seven host globals the runtime
// owns. Every later runtime use of process, console, Math, JSON, Map,
// Array, or undefined is confined to these captures; generated code spells
// none of them bare (js-backend-architecture D2). Object is not in the
// capture list and may be spelled bare inside this trusted file.
const $process = process;
const $console = console;
const $Math = Math;
const $JSON = JSON;
const $Map = Map;
const $Array = Array;
const $undefined = void 0;

// ===== Error spine: the module-private $DEALError class =====
// (js-backend-runtime-artifact D8) The single constructor path every
// runtime failure is thrown through: fail() throws a $DEALError, an Error
// subclass (native Node stacks; every instance satisfies instanceof Error
// and instanceof $DEALError). Instance fields follow the spec
// DEALRuntimeError shape with plain DEAL-observable names — code, message,
// file, line, column, expected, actual — plus the $-sigiled $dealCode
// alias. file/line/column/expected/actual materialize as own properties
// only when the corresponding argument is not undefined, the _err
// absent-field convention (deal/runtime.lua:19-32). message comes from
// super(message), so err.message === message.
class $DEALError extends Error {
  constructor($code, $message, $file, $line, $column, $expected, $actual) {
    super($message);
    this.code = $code;
    this.$dealCode = $code;
    if ($file !== $undefined) this.file = $file;
    if ($line !== $undefined) this.line = $line;
    if ($column !== $undefined) this.column = $column;
    if ($expected !== $undefined) this.expected = $expected;
    if ($actual !== $undefined) this.actual = $actual;
  }
}

// ===== Sentinels (js-backend-runtime-artifact D2) =====
// $MISSING: the unique object sentinel for absent optional class fields —
// the only sentinel in the runtime (not null, not undefined, === only to
// itself). DEAL null is JS null ($rt.NULL === null).
const $MISSING = {};

// ===== Runtime-private JSON shape marks (js-backend-runtime-artifact D2) =====
// Unspoofable WeakSets consulted only by std/json. Never exported; no $rt
// member exposes them.
const $jsonArrayTables = new WeakSet();
const $jsonNullTables = new WeakSet();

// ===== Actual-kind mapping (js-backend-runtime-artifact D3) =====
// $kindOf: the diagnostic "actual" string for every check — the JS mirror
// of Lua's type() augmented with the runtime's own value forms. undefined
// (the T1 captured nil-equivalent, compared against $undefined — never the
// bare spelling) maps to "nil"; null to "null"; wrappers and class
// instances are identified by their $kind tag before the Map/Array
// branches; Maps (the T1 $Map capture) are tables; Arrays (the T1 $Array
// capture) are arrays; everything else reports the JS typeof name.
function $kindOf(v) {
  if (v === $undefined) return "nil";
  if (v === null) return "null";
  if (v.$kind === "function") return "function";
  if (v.$kind === "class") return "class";
  if (v instanceof $Map) return "table";
  if ($Array.isArray(v)) return "array";
  return typeof v;
}

const $rt = {
  MISSING: $MISSING,
  NULL: null,
  undefined: $undefined,

  isNilEquivalent: function $isNilEquivalent(v) {
    return v === $undefined || v === null || v === $MISSING;
  },

  // Own-property write via Object.defineProperty: never invokes the
  // inherited Object.prototype.__proto__ accessor. Returns target for
  // chaining (js-backend-runtime-artifact D2).
  setProp: function $setProp(target, key, v) {
    Object.defineProperty(target, key, {
      value: v,
      writable: true,
      enumerable: true,
      configurable: true,
    });
    return target;
  },

  // The mark members require a Map argument: WeakSet.add on a primitive
  // input throws the native TypeError — the documented defensive exception
  // of the JSON-mark contract, reachable only through std/json mis-emission
  // (js-backend-runtime-artifact D2). No DEALError arm exists here.
  markJsonArrayTable: function $markJsonArrayTable(map) {
    $jsonArrayTables.add(map);
    return map;
  },

  markJsonNullTable: function $markJsonNullTable(map) {
    $jsonNullTables.add(map);
    return map;
  },

  // The is-members are total: WeakSet.has never throws and returns false
  // for primitives and non-members (js-backend-runtime-artifact D2).
  isJsonArrayTable: function $isJsonArrayTable(v) {
    return $jsonArrayTables.has(v);
  },

  isJsonNullTable: function $isJsonNullTable(v) {
    return $jsonNullTables.has(v);
  },

  // ===== Error spine members (js-backend-runtime-artifact D8) =====

  // fail: the single constructor path — every check and arithmetic error
  // is produced through it (D3/D5). Throws a $DEALError carrying code,
  // $dealCode, message (via super), and the defined location/expected/
  // actual fields (the _err absent-field convention, deal/runtime.lua:19-32).
  fail: function $fail(code, message, file, line, column, expected, actual) {
    throw new $DEALError(code, message, file, line, column, expected, actual);
  },

  // errorValue: the tagged builtin-Error class instance a catch (e)
  // binding receives — the mirror of error_value (deal/runtime.lua:36-48,
  // runtime-class-identity D3). A plain object (prototype Object.prototype)
  // whose own properties are written via $rt.setProp; only defined fields
  // materialize, so errorValue('E','x') has exactly the four own
  // properties $kind/$classname/code/message and the seven-field form adds
  // exactly file/line/column.
  errorValue: function $errorValue(code, message, file, line, column) {
    const $value = {};
    $rt.setProp($value, "$kind", "class");
    $rt.setProp($value, "$classname", "Error");
    $rt.setProp($value, "code", code);
    $rt.setProp($value, "message", message);
    if (file !== $undefined) $rt.setProp($value, "file", file);
    if (line !== $undefined) $rt.setProp($value, "line", line);
    if (column !== $undefined) $rt.setProp($value, "column", column);
    return $value;
  },

  // reifyError: total conversion of a thrown value into a tagged Error
  // value. (i) $DEALError -> rebuilt via errorValue (a fresh tagged Error
  // value with the same code/message); (ii) an already-tagged Error
  // instance passes through unchanged, same reference (rethrow
  // preservation, js-backend-runtime D7); (iii) anything else -> the
  // E8001 wrap errorValue("E8001", String(v)). Only fail throws; this
  // member is total.
  reifyError: function $reifyError(v) {
    if (v instanceof $DEALError) {
      return $rt.errorValue(v.code, v.message, v.file, v.line, v.column);
    }
    if (typeof v === "object" && v !== null && v.$kind === "class" && v.$classname === "Error") {
      return v;
    }
    return $rt.errorValue("E8001", String(v));
  },

  // reportUncaught: the entry-shim contract (js-backend-architecture D7) —
  // reify, then write exactly the line "DEAL_ERROR_CODE: <code> <message>"
  // plus a trailing newline to the captured stderr and set the captured
  // exitCode to 1. All host-global access goes through the T1 captures.
  reportUncaught: function $reportUncaught(e) {
    const err = $rt.reifyError(e);
    $process.stderr.write("DEAL_ERROR_CODE: " + err.code + " " + err.message + "\n");
    $process.exitCode = 1;
  },

  // ===== Primitive typed-boundary checks (js-backend-runtime-artifact D3) =====
  // Every check accepts trailing (file, line, column) arguments and throws
  // every error through fail (the D8 spine), so the DEALError carries the
  // exact message, the expected/actual pair where the contract defines
  // one, and the passed location fields (absent when undefined, the _err
  // convention, deal/runtime.lua:19-32). Success returns the validated
  // value — identity except checkInt's -0 normalization. Checks are total
  // and mutate no input.

  // checkNull: DEAL null is only JS null — undefined and MISSING are
  // rejected (deal/runtime.lua:50-55).
  checkNull: function $checkNull(v, file, line, column) {
    if (v !== null) {
      $rt.fail("E8001", "expected null", file, line, column, "null", $kindOf(v));
    }
    return v;
  },

  checkBoolean: function $checkBoolean(v, file, line, column) {
    if (typeof v !== "boolean") {
      $rt.fail("E8001", "expected boolean", file, line, column, "boolean", $kindOf(v));
    }
    return v;
  },

  // checkInt: the exact check_int order (deal/runtime.lua:64-83) — the
  // ±Infinity E8001 arm fires before the E8004 finite-range arm, so intPow
  // overflow to Infinity is E8001 and only finite values outside
  // ±(2^53-1) are E8004. -0 normalizes to 0 (the v = v + 0 step, :72).
  // NaN/±Infinity/non-integer carry expected "int" exactly like the
  // reference _err calls; the E8004 range arm carries no expected/actual
  // (nil in the reference — fields absent, the _err convention).
  checkInt: function $checkInt(v, file, line, column) {
    if (typeof v !== "number") {
      $rt.fail("E8001", "expected int", file, line, column, "int", $kindOf(v));
    }
    if (Number.isNaN(v)) {
      $rt.fail("E8001", "expected int, got NaN", file, line, column, "int", "NaN");
    }
    v = v + 0; // normalize -0 to 0
    if (v === Infinity || v === -Infinity) {
      $rt.fail("E8001", "expected int, got infinity", file, line, column, "int", "infinity");
    }
    if (v % 1 !== 0) {
      $rt.fail("E8001", "expected int, got non-integer number", file, line, column, "int", "number");
    }
    if (v < -9007199254740991 || v > 9007199254740991) {
      $rt.fail("E8004", "int out of safe range", file, line, column);
    }
    return v;
  },

  // checkNumber: NaN and ±Infinity pass (spec §Numeric overflow).
  checkNumber: function $checkNumber(v, file, line, column) {
    if (typeof v !== "number") {
      $rt.fail("E8001", "expected number", file, line, column, "number", $kindOf(v));
    }
    return v;
  },

  // checkString: boundary validation rejects unpaired UTF-16 surrogates —
  // a high surrogate (U+D800..U+DBFF) not followed by a low surrogate
  // (U+DC00..U+DFFF), or a lone low surrogate — with the reference's
  // boundary message (deal/runtime.lua:204-215). Valid supplementary pairs
  // pass; the string is returned unchanged.
  checkString: function $checkString(v, file, line, column) {
    if (typeof v !== "string") {
      $rt.fail("E8001", "expected string", file, line, column, "string", $kindOf(v));
    }
    for (let $i = 0; $i < v.length; $i++) {
      const $c = v.charCodeAt($i);
      if ($c >= 0xd800 && $c <= 0xdbff) {
        const $next = $i + 1 < v.length ? v.charCodeAt($i + 1) : -1;
        if ($next < 0xdc00 || $next > 0xdfff) {
          $rt.fail("E8001", "expected string, got invalid UTF-8 encoding", file, line, column, "string", "invalid UTF-8 string");
        }
        $i++; // skip the low surrogate of a valid pair
      } else if ($c >= 0xdc00 && $c <= 0xdfff) {
        $rt.fail("E8001", "expected string, got invalid UTF-8 encoding", file, line, column, "string", "invalid UTF-8 string");
      }
    }
    return v;
  },

  // checkTable: the strict split (js-backend-runtime D3) — instanceof Map
  // passes (the T1-marked JSON Maps included, D2); anything else — Array,
  // plain object, class instance, wrapper, primitives — is rejected.
  checkTable: function $checkTable(v, file, line, column) {
    if (!(v instanceof $Map)) {
      $rt.fail("E8001", "expected table", file, line, column, "table", $kindOf(v));
    }
    return v;
  },

  // ===== Int arithmetic and the floored remainder =====
  // (js-backend-runtime-artifact D5; js-backend-runtime D4 — the verbatim
  // mirror of deal/runtime.lua:478-514) Every int result flows through
  // checkInt (the D3 contract): -0 normalizes to 0, overflow to ±Infinity
  // is E8001 "expected int, got infinity" before the E8004 finite-range
  // arm, and finite values outside ±(2^53-1) are E8004. intDiv/intMod
  // raise E8005 on a zero divisor before any division; intPow raises
  // E8006 on a negative exponent. The truncation operations use the T1
  // $Math capture — $Math.trunc is the math.modf truncation-toward-zero
  // analog and $Math.floor is the floored-remainder divisor step — and
  // every error is produced through the T2 spine (fail) with the caller's
  // (file, line, column) forwarded. numMod is the Lua floored remainder
  // for number % number with NO check — formula-defined and total
  // (numMod(x, 0) is NaN, no throw). number / number stays native IEEE
  // division in the emitter and has no runtime member. Pure functions;
  // no mutation.

  intAdd: function $intAdd(a, b, file, line, column) {
    return $rt.checkInt(a + b, file, line, column);
  },

  intSub: function $intSub(a, b, file, line, column) {
    return $rt.checkInt(a - b, file, line, column);
  },

  intMul: function $intMul(a, b, file, line, column) {
    return $rt.checkInt(a * b, file, line, column);
  },

  intNeg: function $intNeg(a, file, line, column) {
    return $rt.checkInt(-a, file, line, column);
  },

  // intDiv: E8005 on a zero divisor first (no expected/actual, mirroring
  // the reference _err(nil, nil) pair), then checkInt of the
  // truncation-toward-zero quotient (deal/runtime.lua:490-494).
  intDiv: function $intDiv(a, b, file, line, column) {
    if (b === 0) {
      $rt.fail("E8005", "integer division by zero", file, line, column);
    }
    return $rt.checkInt($Math.trunc(a / b), file, line, column);
  },

  // intMod: E8005 on a zero divisor first, then checkInt of the truncated
  // remainder a - trunc(a / b) * b (deal/runtime.lua:497-501).
  intMod: function $intMod(a, b, file, line, column) {
    if (b === 0) {
      $rt.fail("E8005", "integer division by zero", file, line, column);
    }
    return $rt.checkInt(a - $Math.trunc(a / b) * b, file, line, column);
  },

  // intPow: E8006 on a negative exponent, then checkInt of a ** b — so
  // overflow to ±Infinity is E8001 via the checkInt order and a finite
  // out-of-range result is E8004 (deal/runtime.lua:504-509).
  intPow: function $intPow(a, b, file, line, column) {
    if (b < 0) {
      $rt.fail("E8006", "integer exponent must be non-negative", file, line, column);
    }
    return $rt.checkInt(a ** b, file, line, column);
  },

  // numMod: a - floor(a / b) * b — the Lua floored remainder for
  // number % number (native JS % is truncated, so it diverges for mixed
  // signs; the JVM precedent deal/codegen/jvm/JvmBackend.java:3382).
  // No check: IEEE arithmetic on two numbers is total.
  numMod: function $numMod(a, b) {
    return a - $Math.floor(a / b) * b;
  },

  // ===== Wrapper factory and conversion intrinsics
  // (js-backend-runtime-artifact D7) =====

  // function: the canonical wrapper factory — a plain object (prototype
  // Object.prototype) with exactly the three $-keyed own properties
  // $kind: "function", $sig: sig (stored verbatim — signature comparison
  // happens in checkType/checkFunctionSig, which compare the string
  // exactly), and $f: f (the exact function reference — never re-wrapped,
  // identity-preserving). The wrapper is the passive shape: function
  // performs no checks itself; the parameter/return checks live in the
  // emitted wrapper bodies (js-backend-runtime D6). All $-keys, plain
  // syntax; no shared wrapper state; the factory is pure
  // (deal/runtime.lua:516-519).
  function: function $function(sig, f) {
    return { $kind: "function", $sig: sig, $f: f };
  },

  // intConvert: the int(x) conversion intrinsic (deal/runtime.lua:917-926).
  // A nil-equivalent input (undefined, null, MISSING) raises E8001
  // "cannot convert null to int" with expected "int"/actual "null"; any
  // other value passes through the full checkInt contract — kind, NaN,
  // ±Infinity, non-integer, safe range, -0 normalization (D3). Every
  // error goes through the D8 spine with the forwarded (file, line,
  // column); success returns the validated value (pure, no mutation).
  intConvert: function $intConvert(v, file, line, column) {
    if ($rt.isNilEquivalent(v)) {
      $rt.fail("E8001", "cannot convert null to int", file, line, column, "int", "null");
    }
    return $rt.checkInt(v, file, line, column);
  },

  // numberConvert: the number(x) conversion intrinsic
  // (deal/runtime.lua:927-933). A nil-equivalent input raises E8001
  // "cannot convert null to number" with expected "number"/actual "null";
  // any other value passes through checkNumber — NaN and ±Infinity pass
  // and are returned (spec §Numeric overflow). Same spine, span
  // forwarding, and purity as intConvert.
  numberConvert: function $numberConvert(v, file, line, column) {
    if ($rt.isNilEquivalent(v)) {
      $rt.fail("E8001", "cannot convert null to number", file, line, column, "number", "null");
    }
    return $rt.checkNumber(v, file, line, column);
  },
};

module.exports = $rt;
