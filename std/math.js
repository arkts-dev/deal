"use strict";

// DEAL Standard Library: std/math (JavaScript backend).
// The seven .d.deal math members as $rt.function wrappers with native
// IEEE operations and the pinned sqrt negative-input E8001
// (js-stdlib-modules D1-D3, D9; js-backend-runtime D9). The module is
// pure and stateless: no mutation, no shared state, no I/O.

// Host-global capture (js-stdlib-modules D3): the trusted module owns the
// host global it uses in its own private scope, captured at module top
// under the $-name; nothing else is spelled bare. Object is not in the
// capture list and may be spelled bare inside trusted files.
const $Math = Math;

// The fixed stdlib require specifier (js-stdlib-modules D1): from the
// deployed location <output>/std/math.js it resolves
// <output>/deal/runtime.js; from the repo-root std/ directory it resolves
// the repo-root deal/runtime.js (js-backend-architecture D2).
const $rt = require("../deal/runtime");

// The absent-value spelling of the error spine's optional expected/actual
// pair (the _err absent-field convention): a closed arm with no expected
// member passes the absent value explicitly.
const $undefined = void 0;

// Module-private export object carrying exactly the seven .d.deal members
// — no missing member, no extra export (js-stdlib-modules D1).
const $math = {};

// Entry/exit check table (js-stdlib-modules D2): the number members check
// the parameter inside the body and the declared return at exit; the int
// members do the same through checkInt. Every wrapper declares the
// trailing span parameters and forwards them to every in-body check and
// the exit check, so parameter and return errors report the DEAL call
// site (the emitter passes literal call-site file/line/column on every
// .$f call, js-stdlib-modules D2).

$math.floor = $rt.function("(number)->number", function(x, $file, $line, $column) {
  $rt.checkNumber(x, $file, $line, $column);
  return $rt.checkNumber($Math.floor(x), $file, $line, $column);
});

$math.ceil = $rt.function("(number)->number", function(x, $file, $line, $column) {
  $rt.checkNumber(x, $file, $line, $column);
  return $rt.checkNumber($Math.ceil(x), $file, $line, $column);
});

// IEEE sqrt preserves NaN, positive infinity and signed zero.
$math.sqrt = $rt.function("(number)->number", function(x, $file, $line, $column) {
  $rt.checkNumber(x, $file, $line, $column);
  return $rt.checkNumber($Math.sqrt(x), $file, $line, $column);
});

// absInt: native Math.abs over the validated int — identity for
// non-negative values and exact negation for negatives across the whole
// safe range (the most negative safe int -9007199254740991 negates to
// +9007199254740991, both within ±(2^53-1)).

$math.absNumber = $rt.function("(number)->number", function(x, $file, $line, $column) {
  $rt.checkNumber(x, $file, $line, $column);
  return $rt.checkNumber($Math.abs(x), $file, $line, $column);
});



module.exports = $math;
