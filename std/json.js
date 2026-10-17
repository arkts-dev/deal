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
//   stringify — the declared "(table)->string" parameter check at the
//               entry (entry checkTable — the std/json.lua check_table
//               mirror, and what the shared STDLIB_PARAMETER cell of the
//               cataloged call runs on the other lanes: the JS production
//               lane emits a raw stdlib module call, so this entry check
//               is the std/json.stringify parameter boundary), the shared
//               $rt.jsonStringifyText finite-acyclic validation/text walk
//               (the std/json.lua encode_value mirror: the closed
//               JSON_STRINGIFY_UNSUPPORTED arm for unsupported carriers,
//               invalid scalar sequences and nonfinite numbers; the
//               closed cycle arm for a path-local container re-entry),
//               then the declared-return checkString at exit. The entry
//               check is the strict table split $rt.checkTable applies:
//               an unmarked table passes; the two JSON decode carriers
//               (a decoded JSON array, a decoded JSON null) and every
//               other carrier — Array, plain object, class instance,
//               wrapper, MISSING, undefined, primitives — raise the
//               shared E8001 "expected table" typed-boundary tuple with
//               the runtime's $kindOf actual label. A table argument that
//               merely CONTAINS decoded carriers, class instances, or any
//               other non-JSON-shaped value keeps the walk's own arms.
//
// Hand-written and trusted: the module is pure and stateless; beyond the
// checks/error spine it consults the three shared runtime members
// (jsonParseText/jsonParseValue/jsonStringifyText) and
// checkTable/checkString, and it reads no host global at all (the
// js-stdlib-modules D3 $-capture convention applies to the globals a
// module uses; this module uses none).

const $rt = require("../deal/runtime");

// ===== Module export: exactly the .d.deal members as $rt.function
// wrappers (js-stdlib-modules D1-D2) =====
const $json = {};

// stringify: the declared (table) parameter check at the entry — the
// strict $rt.checkTable split, exactly the boundary the cataloged call's
// STDLIB_PARAMETER cell applies on the LuaJIT/JVM lanes and in the
// shared oracle (E8001 "expected table", expected "table", actual
// $kindOf) — then the shared $rt.jsonStringifyText validation/text walk
// and the declared-return checkString at exit.
$json.stringify = $rt.function("(table)->string", function(v, $file, $line, $column) {
  $rt.checkTable(v, $file, $line, $column);
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
