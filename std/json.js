"use strict";

// DEAL Standard Library: std/json — JavaScript backend (std/json.js).
// parse uses the runtime's RFC-8259 scanner, binary64 conversion and
// decoded-key duplicate rejection, then requires an object root (record).
// stringify accepts supported unknown values and applies generic JSON rules:
// omit undefined/function object members, substitute null in arrays and for
// nonfinite numbers, return undefined for unrepresentable roots, and reject
// cycles and invalid Unicode. Declared @jsonable walkers are separate.
// This stateless wrapper delegates value semantics and boundary checks to
// the deployed runtime.

const $rt = require("../deal/runtime");

// ===== Module export: exactly the .d.deal members as $rt.function
// wrappers (js-stdlib-modules D1-D2) =====
const $json = {};

// Preserve the independent unknown-input and optional-string result checks.
$json.stringify = $rt.function("(unknown)->!string", function(v, $file, $line, $column) {
  $rt.checkType("unknown", v, $file, $line, $column);
  return $rt.checkType("!string", $rt.jsonStringifyText(v, [], $file, $line, $column), $file, $line, $column);
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
$json.parse = $rt.function("(string)->record", function(text, $file, $line, $column) {
  $rt.checkString(text, $file, $line, $column);
  const $doc = $rt.jsonParseText(text, $file, $line, $column);
  const $table = $rt.jsonParseValue($doc, $file, $line, $column);
  return $rt.checkTable($table, $file, $line, $column);
});

module.exports = $json;
