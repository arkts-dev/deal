"use strict";

// DEAL Standard Library: std/time (JavaScript backend).
// Provides time-related functions. The hand-written CommonJS analog of
// std/time.lua (js-stdlib-modules D1/D2/D3/D9; js-backend-runtime D9).

// Host-global captures (js-stdlib-modules D3): std/time owns Math and Date
// in its private trusted scope; nothing else is spelled bare. The runtime
// has no date member (the epic boundary forbids deal/runtime.js changes),
// so the module captures them locally under $-names.
const $Math = Math;
const $Date = Date;

// The fixed require specifier (js-stdlib-modules D1): from the deployed
// location <output>/std/time.js it resolves <output>/deal/runtime.js, and
// from the repo-root std/ directory it resolves the repo-root
// deal/runtime.js — the dual-location loadability the direct tests use.
const $rt = require("../deal/runtime");

// Module-private export object carrying exactly the declared .d.deal
// members — no missing member, no extra export.
const $time = {};

// nowMillis: ()->int — the epoch-millisecond second truncation pinned by
// the objective (std/time.lua's os.time() * 1000; the JVM backend's
// (System.currentTimeMillis() / 1000L) * 1000L). The result is always an
// integer with % 1000 === 0, in the current-second window of Date.now().
// There are no value parameters, so $f declares only the trailing span
// parameters ($file, $line, $column) — the emitter passes literal
// call-site spans on every .$f call — and forwards them to the exit check:
// checkInt is the declared-return check at exit and is total over the
// computed value (js-stdlib-modules D2/D9). Stateless; no caching.
$time.nowMillis = $rt.function("()->int", function($file, $line, $column) {
  return $rt.checkInt(($Math.trunc($Date.now() / 1000)) * 1000, $file, $line, $column);
});

module.exports = $time;
