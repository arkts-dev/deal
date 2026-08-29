"use strict";

// DEAL Standard Library: std/json — JavaScript backend (std/json.js).
// JSON decode/encode over the runtime's marked-Map table representation
// (js-stdlib-modules D1-D3, D7-D8; js-backend-runtime D9):
//
//   parse     — native $JSON.parse plus the pre-parse string-literal-
//               aware raw-control scan, then the shared
//               $rt.jsonParseValue conversion (the marked-Map shape
//               conversion with the unpaired-surrogate scan, hosted in
//               deal/runtime.js — the D9 conversion the @jsonable
//               walkers' table-field decode shares,
//               js-v12-jsonable-completion D2/D4), under the
//               "(string)->table" boundary (entry checkString, exit
//               checkTable);
//   stringify — the widened instance-aware entry gate (a Map or a class
//               instance passes; everything else fails through
//               $rt.checkTable with E8001 "expected table"), the shared
//               $rt.jsonEncodeValue validation/conversion walk (the
//               std/json.lua encode_value mirror with the cycle check),
//               and native $JSON.stringify on the converted graph,
//               under the "(table)->string" boundary (exit checkString).
//
// Hand-written and trusted: the module captures the host globals it uses
// at module top under $-names (js-stdlib-modules D3) — $JSON, $Map, and
// the nil-equivalent $undefined. Object/String may be spelled bare
// inside trusted files (the runtime's own D10 note). The module is pure
// and stateless; beyond the checks/error spine it consults the two
// shared D9 conversion members (jsonParseValue/jsonEncodeValue — the
// only consumers of the four runtime-private JSON shape marks, which the
// @jsonable walkers' table branches share) and checkTable/checkString.

// ===== Host-global capture (js-stdlib-modules D3) =====
const $JSON = JSON;
const $Map = Map;
const $undefined = void 0;

const $rt = require("../deal/runtime");

// ===== Decode-side parity scan (js-stdlib-modules D7) =====

// $scanRawControls: the string-literal-aware pre-parse walk. An in-string,
// unescaped UTF-16 unit below 0x20 raises the pinned raw-control needle
// through the E8001 spine with the forwarded call-site span. A backslash
// consumes the following unit as an escape pair (\\, \" and \uXXXX all
// stay in-string); raw DEL 0x7F is outside the rejection range; raw
// controls outside strings are left to native $JSON.parse (tab/LF/CR are
// legal JSON whitespace there). The scan can never widen acceptance: any
// in-string raw control it misses is still rejected by the native arm.
function $scanRawControls($text, $file, $line, $column) {
  let $inString = false;
  let $i = 0;
  const $len = $text.length;
  while ($i < $len) {
    const $c = $text.charCodeAt($i);
    if ($inString) {
      if ($c === 0x22) { // closing quote: end of the string literal
        $inString = false;
        $i++;
      } else if ($c === 0x5c) { // backslash: consume the escape pair
        $i += 2;
        if ($i <= $len && $text.charCodeAt($i - 1) === 0x75) {
          $i += 4; // \uXXXX: consume the four hex units
        }
      } else if ($c < 0x20) {
        $rt.fail("E8001", "JSON parse error: raw control character in string (must be escaped)", $file, $line, $column);
      } else {
        $i++;
      }
    } else {
      if ($c === 0x22) { // opening quote: enter a string literal
        $inString = true;
      }
      $i++;
    }
  }
}

// ===== Module export: exactly the .d.deal members as $rt.function
// wrappers (js-stdlib-modules D1-D2) =====
const $json = {};

// stringify: the widened instance-aware entry gate — a Map or a class
// instance passes; everything else (Array, wrapper, plain object, a
// standalone MISSING, undefined, primitives) falls through $rt.checkTable
// with E8001 "expected table" and the runtime's $kindOf actual label —
// then the shared $rt.jsonEncodeValue walk (the D9 conversion: null/array
// marks, NaN/±Infinity/unpaired-surrogate/cycle/function rejections,
// class instances under the __kind/__classname tag spellings with
// MISSING optionals omitted), native $JSON.stringify on the converted
// graph, and the declared-return checkString at exit.
$json.stringify = $rt.function("(table)->string", function(v, $file, $line, $column) {
  if (!(v instanceof $Map) && !(typeof v === "object" && v !== null && v.$kind === "class")) {
    $rt.checkTable(v, $file, $line, $column);
  }
  return $rt.checkString($JSON.stringify($rt.jsonEncodeValue(v, [], $file, $line, $column)), $file, $line, $column);
});

// parse: entry checkString, the pre-parse raw-control scan, native
// $JSON.parse with every failure converted to the E8001 "JSON parse
// error" envelope, the shared $rt.jsonParseValue conversion (the
// recursive marked-Map shape conversion with the unpaired-surrogate
// scan), and the declared-return checkTable at exit — a top-level scalar
// fails with "expected table" while the scan arms fire before the exit
// check, so a top-level lone-surrogate string reports the parse error.
// On node v24.13.0 native $JSON.parse is iterative and does NOT raise
// the deep-nesting RangeError the reference contract names — the
// recursion limit moved into the conversion itself — so a second narrow
// arm converts any non-DEAL error out of the conversion walk (the
// deep-nesting stack overflow) into the same E8001 "JSON parse error"
// envelope with the span; DEALErrors from the scan arms ($dealCode set)
// are re-thrown unchanged, so the pinned needles are never re-wrapped.
$json.parse = $rt.function("(string)->table", function(text, $file, $line, $column) {
  $rt.checkString(text, $file, $line, $column);
  $scanRawControls(text, $file, $line, $column);
  let $value;
  try {
    $value = $JSON.parse(text);
  } catch ($e) {
    $rt.fail("E8001", "JSON parse error: " + $e.message, $file, $line, $column);
  }
  let $table;
  try {
    $table = $rt.jsonParseValue($value, $file, $line, $column);
  } catch ($e) {
    if (typeof $e === "object" && $e !== null && $e.$dealCode !== $undefined) {
      throw $e;
    }
    $rt.fail("E8001", "JSON parse error: " + $e.message, $file, $line, $column);
  }
  return $rt.checkTable($table, $file, $line, $column);
});

module.exports = $json;
