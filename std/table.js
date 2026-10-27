"use strict";

// DEAL Standard Library: std/table — JavaScript backend (std/table.js).
// The hand-written CommonJS mirror of std/table.lua over the committed
// $rt surface (js-stdlib-modules D1/D2/D3/D6, js-backend-runtime D9).
// Exports exactly the .d.deal members (std/table.d.deal: keys only) as
// $rt.function wrappers with the byte-exact spec signatures; parameter
// checks run inside the body and the declared-return check at exit, with
// the trailing span parameters forwarded to every check so failures
// report the DEAL call site.

// Host-global capture (js-stdlib-modules D3): the only module-scope
// binding of a host global this module uses, under a $-name.
const $Array = Array;

// The fixed runtime require: from the deployed location
// <output>/std/table.js it resolves <output>/deal/runtime.js, and from
// the repo-root std/ directory it resolves the repo-root deal/runtime.js
// (js-stdlib-modules D1 — dual-location loadability).
const $rt = require("../deal/runtime");

// The module-private export object (js-stdlib-modules D1).
const $table = {};

// keys(t): the array of the table's string keys in Map insertion order
// (js-stdlib-modules D6). checkTable is the strict instanceof-Map split
// (deal/runtime.js checkTable): an unmarked Map passes, while the two
// JSON decode carriers — a decoded array (the array-marked Map) and a
// decoded null (the null-marked Map) — are rejected with E8001
// expected table and the shared expected/actual tokens (actual array /
// actual null), exactly the boundary every other table descriptor
// applies (the emitted chunk's refined table admission and the shared
// boundary executor). Array.from(t.keys()) over the validated Map
// yields the string keys in insertion order — a stricter order than the
// reference's unspecified pairs order, which it refines. The exit
// checkArray validates the fresh array and every string element.
$table.keys = $rt.function("(record)->[string]", function(t, $file, $line, $column) {
  $rt.checkTable(t, $file, $line, $column);
  return $rt.checkArray("[string]", $rt.tagArray($Array.from(t.keys()), "string"), $file, $line, $column);
});

module.exports = $table;
