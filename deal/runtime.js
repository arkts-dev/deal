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
};

module.exports = $rt;
