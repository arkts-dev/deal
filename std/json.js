"use strict";

// DEAL Standard Library: std/json — JavaScript backend (std/json.js).
// JSON decode/encode over the runtime's marked-Map table representation:
//
//   parse     — the conforming RFC-8259 scanner hosted in the runtime
//               ($rt.jsonParseText: exact decimal classification before
//               binary64 rounding, decoded-key duplicate rejection at
//               the second key's opening quote with the shared reason
//               and one-based UTF-8 byte offset, the shared reason texts)
//               and the shared $rt.jsonParseValue conversion (the
//               marked-Map shape conversion with the per-slot
//               number-variant marks), under the "(string)->table"
//               boundary (entry checkString, exit checkTable);
//   stringify — the widened instance-aware entry gate (a Map or a class
//               instance passes; everything else fails through
//               $rt.checkTable with E8001 "expected table"), the shared
//               $rt.jsonStringifyText finite-acyclic validation/text walk
//               (the std/json.lua encode_value mirror: the closed
//               JSON_STRINGIFY_UNSUPPORTED arm for unsupported carriers,
//               invalid scalar sequences and nonfinite numbers; the
//               closed cycle arm for a path-local container re-entry),
//               under the "(table)->string" boundary (exit checkString).
//
// Hand-written and trusted: the module captures the host globals it uses
// at module top under $-names (js-stdlib-modules D3) — $Map and the
// nil-equivalent $undefined. Object/String may be spelled bare inside
// trusted files (the runtime's own D10 note). The module is pure and
// stateless; beyond the checks/error spine it consults the three shared
// runtime members (jsonParseText/jsonParseValue/jsonStringifyText) and
// checkTable/checkString.

// ===== Host-global capture (js-stdlib-modules D3) =====
const $Map = Map;
const $undefined = void 0;

const $rt = require("../deal/runtime");

// ===== Module export: exactly the .d.deal members as $rt.function
// wrappers (js-stdlib-modules D1-D2) =====
const $json = {};

// stringify: the widened instance-aware entry gate — a Map or a class
// instance passes; everything else (Array, wrapper, plain object, a
// standalone MISSING, undefined, primitives) falls through $rt.checkTable
// with E8001 "expected table" and the runtime's $kindOf actual label —
// then the shared $rt.jsonStringifyText validation/text walk and the
// declared-return checkString at exit.
$json.stringify = $rt.function("(table)->string", function(v, $file, $line, $column) {
  if (!(v instanceof $Map) && !(typeof v === "object" && v !== null && v.$kind === "class")) {
    $rt.checkTable(v, $file, $line, $column);
  }
  return $rt.checkString($rt.jsonStringifyText(v, [], $file, $line, $column), $file, $line, $column);
});

// parse: entry checkString, the conforming scanner (every syntax defect —
// a decoded-key duplicate included — already raises the shared E8001
// "JSON parse error at position N: reason" projection), the shared
// $rt.jsonParseValue conversion, and the declared-return checkTable at
// exit — a top-level scalar fails with "expected table", and the two
// JSON decode carriers are rejected by the same boundary: a decoded
// JSON array with the actual "array" token and a decoded JSON null with
// the actual "null" token, exactly the shared boundary executor's
// tuples (never admitted as Maps; the scanner arms fire before the exit
// check).
$json.parse = $rt.function("(string)->table", function(text, $file, $line, $column) {
  $rt.checkString(text, $file, $line, $column);
  const $doc = $rt.jsonParseText(text, $file, $line, $column);
  const $table = $rt.jsonParseValue($doc, $file, $line, $column);
  return $rt.checkTable($table, $file, $line, $column);
});

module.exports = $json;
