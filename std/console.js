"use strict";

// DEAL Standard Library: std/console (js-stdlib-modules D1/D3/D4).
// The hand-written CommonJS console module — log/error as first-class
// $rt.function wrapper values over the runtime-captured stdout/stderr
// (js-backend-runtime D9). The fixed "../deal/runtime" specifier resolves
// deal/runtime.js from both the repo-root std/ directory and the deployed
// <output>/std/ location (dual-location loadability, js-stdlib-modules D1).

// Host-global capture (js-stdlib-modules D3): the only module-scope
// binding of a host global — the same process stdio streams the runtime
// captured. Every later use of process is confined to this capture.
const $process = process;

const $rt = require("../deal/runtime");

// The module-private export object carrying exactly the two .d.deal
// members log/error — no missing member, no extra export.
const $console = {};

// log: checkString at entry, write x + "\n" to the captured stdout, then
// checkNull(null) at exit — the std/console.lua mirror (print(x)). The
// trailing span parameters are declared and forwarded to every in-body
// check, so parameter and return errors report the DEAL call site
// (js-backend-runtime D6; the wrapper carries the exact "(string)->null"
// signature, so it crosses a function-typed boundary with no E8010).
$console.log = $rt.function("(string)->null", function(x, $file, $line, $column) {
  $rt.checkString(x, $file, $line, $column);
  $process.stdout.write(x + "\n");
  return $rt.checkNull(null, $file, $line, $column);
});

// error: identical with the captured stderr stream — the reference's
// io.stderr:write(x .. "\n").
$console.error = $rt.function("(string)->null", function(x, $file, $line, $column) {
  $rt.checkString(x, $file, $line, $column);
  $process.stderr.write(x + "\n");
  return $rt.checkNull(null, $file, $line, $column);
});

module.exports = $console;
