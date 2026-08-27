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

// sqrt: the pinned negative-input arm raises E8001 "sqrt of negative
// number" with the reference's expected/actual pair (std/math.lua:50);
// NaN passes through (NaN < 0 is false, Math.sqrt(NaN) is NaN, and
// checkNumber accepts NaN per spec §Numeric overflow).
$math.sqrt = $rt.function("(number)->number", function(x, $file, $line, $column) {
  $rt.checkNumber(x, $file, $line, $column);
  if (x < 0) {
    $rt.fail("E8001", "sqrt of negative number", $file, $line, $column, "non-negative number", String(x));
  }
  return $rt.checkNumber($Math.sqrt(x), $file, $line, $column);
});

// absInt: native Math.abs over the validated int — identity for
// non-negative values and exact negation for negatives across the whole
// safe range (the most negative safe int -9007199254740991 negates to
// +9007199254740991, both within ±(2^53-1)).
$math.absInt = $rt.function("(int)->int", function(x, $file, $line, $column) {
  $rt.checkInt(x, $file, $line, $column);
  return $rt.checkInt($Math.abs(x), $file, $line, $column);
});

$math.absNumber = $rt.function("(number)->number", function(x, $file, $line, $column) {
  $rt.checkNumber(x, $file, $line, $column);
  return $rt.checkNumber($Math.abs(x), $file, $line, $column);
});

$math.minInt = $rt.function("(int,int)->int", function(a, b, $file, $line, $column) {
  $rt.checkInt(a, $file, $line, $column);
  $rt.checkInt(b, $file, $line, $column);
  return $rt.checkInt($Math.min(a, b), $file, $line, $column);
});

$math.maxInt = $rt.function("(int,int)->int", function(a, b, $file, $line, $column) {
  $rt.checkInt(a, $file, $line, $column);
  $rt.checkInt(b, $file, $line, $column);
  return $rt.checkInt($Math.max(a, b), $file, $line, $column);
});

module.exports = $math;
