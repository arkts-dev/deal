"use strict";

// DEAL Standard Library: std/string — v1.2 (JavaScript backend).
// The hand-written CommonJS string module (js-stdlib-modules D1/D5,
// js-backend-runtime D9): the eight .d.deal members exported as
// $rt.function wrappers with the exact spec signature strings, parameter
// checks inside each body, and the declared-return check at exit — every
// check receiving the forwarded trailing span parameters ($file, $line,
// $column), so parameter and return errors report the DEAL call site.
// String lengths and positions are measured in Unicode scalar values
// (code points, v1.2); search/replace/split are plain-text (never Lua
// patterns); trim strips the Lua %s set (space, tab, newline, vertical
// tab, form feed, carriage return) exactly.
//
// Host-global capture: none (js-stdlib-modules D3 — the module uses
// String prototype methods and $rt scalar helpers only; no bare global
// is spelled). The runtime is required with the fixed ../deal/runtime
// specifier, which resolves <output>/deal/runtime.js from the deployed
// <output>/std/ location and the repo-root deal/runtime.js from the
// repo-root std/ directory (the dual-location loadability of
// js-stdlib-modules D1). The module is pure and stateless: inputs are
// never mutated, no shared state, no I/O.

const $rt = require("../deal/runtime");

// $isTrimChar: the Lua %s character set — space, tab, newline, vertical
// tab, form feed, carriage return (the six C-locale isspace characters
// Lua 5.1 pattern %s matches). Never String.prototype.trim, whose set is
// wider (js-stdlib-modules D5). All six characters are single-unit
// ASCII, so a UTF-16-unit test is exact and never cuts a multi-unit
// scalar.
function $isTrimChar($c) {
  return $c === 0x20 || $c === 0x09 || $c === 0x0a || $c === 0x0b || $c === 0x0c || $c === 0x0d;
}

const $string = {};

// length(s: string): int — the number of Unicode scalar values in s
// (the code-point count; scalarLength walks one code point per step).
$string.length = $rt.function("(string)->int", function(s, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  return $rt.checkInt($rt.scalarLength(s), $file, $line, $column);
});

// substring(s: string, start: int, end: int): string — the substring at
// 0-based scalar positions [start, end) with the v1.2 clamping
// (js-stdlib-modules D5): negative start behaves as 0; negative end
// behaves as 0 (yields ""); end beyond the scalar length clamps to the
// length; start >= end yields ""; start beyond the string yields "".
// Observably identical to the reference's two scalar-position walks
// feeding string.sub (std/string.lua substring), whose i > j /
// negative-j / beyond-length corrections produce exactly these
// outcomes. The collection is one code-point walk over [start, end)
// after the clamping.
$string.substring = $rt.function("(string,int,int)->string", function(s, start, end, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  $rt.checkInt(start, $file, $line, $column);
  $rt.checkInt(end, $file, $line, $column);
  const $len = $rt.scalarLength(s);
  let $st = start < 0 ? 0 : start;
  let $en = end < 0 ? 0 : end;
  if ($en > $len) $en = $len;
  if ($st >= $en) {
    return $rt.checkString("", $file, $line, $column);
  }
  let $result = "";
  let $pos = 0;
  for (const $c of s) {
    if ($pos >= $en) break;
    if ($pos >= $st) $result += $c;
    $pos++;
  }
  return $rt.checkString($result, $file, $line, $column);
});

// contains(s: string, part: string): boolean — plain-text substring
// presence via s.includes(part): a string argument is literal, never a
// pattern; the empty part is contained in every string (the reference's
// plain-text string.find). Equality is exact because equal scalar
// sequences have equal UTF-16 encodings (js-backend-runtime D8) — no
// helper needed.
$string.contains = $rt.function("(string,string)->boolean", function(s, part, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  $rt.checkString(part, $file, $line, $column);
  return $rt.checkBoolean(s.includes(part), $file, $line, $column);
});

// startsWith(s: string, part: string): boolean — plain-text prefix test
// via s.startsWith(part); the empty part is a prefix of every string.
$string.startsWith = $rt.function("(string,string)->boolean", function(s, part, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  $rt.checkString(part, $file, $line, $column);
  return $rt.checkBoolean(s.startsWith(part), $file, $line, $column);
});

// endsWith(s: string, part: string): boolean — plain-text suffix test
// via s.endsWith(part); the empty part is a suffix of every string and a
// longer part yields false.
$string.endsWith = $rt.function("(string,string)->boolean", function(s, part, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  $rt.checkString(part, $file, $line, $column);
  return $rt.checkBoolean(s.endsWith(part), $file, $line, $column);
});

// replace(s: string, old: string, to: string): string — plain-text
// replacement of every occurrence via s.split(old).join(to): a string
// separator to split is literal, never a pattern, and a % in to is
// literal (no gsub capture references). The old === "" guard returns s
// unchanged — the reference's explicit return-s-unchanged branch
// (std/string.lua replace).
$string.replace = $rt.function("(string,string,string)->string", function(s, old, to, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  $rt.checkString(old, $file, $line, $column);
  $rt.checkString(to, $file, $line, $column);
  if (old === "") {
    return $rt.checkString(s, $file, $line, $column);
  }
  return $rt.checkString(s.split(old).join(to), $file, $line, $column);
});

// split(s: string, sep: string): string[] — plain-text split
// (js-stdlib-modules D5): empty s → [] whatever the separator (the
// reference's #s == 0 first branch); empty sep → $rt.scalars(s), one
// scalar per part (a supplementary character is one element); otherwise
// s.split(sep) — not-found → [s], and leading/trailing/consecutive
// separators produce empty elements exactly like the reference's
// plain-text string.find loop (a trailing separator yields the empty
// last element). The exit check validates the array and each string
// element.
$string.split = $rt.function("(string,string)->[string]", function(s, sep, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  $rt.checkString(sep, $file, $line, $column);
  if (s === "") {
    return $rt.checkArray("[string]", [], $file, $line, $column);
  }
  if (sep === "") {
    return $rt.checkArray("[string]", $rt.scalars(s), $file, $line, $column);
  }
  return $rt.checkArray("[string]", s.split(sep), $file, $line, $column);
});

// trim(s: string): string — strips the leading and trailing Lua %s set
// (the six characters of $isTrimChar) via two UTF-16-unit index walks,
// then slices. Internal whitespace is preserved; an all-whitespace
// input and the empty input yield "".
$string.trim = $rt.function("(string)->string", function(s, $file, $line, $column) {
  $rt.checkString(s, $file, $line, $column);
  let $a = 0;
  const $n = s.length;
  while ($a < $n && $isTrimChar(s.charCodeAt($a))) {
    $a++;
  }
  let $b = $n;
  while ($b > $a && $isTrimChar(s.charCodeAt($b - 1))) {
    $b--;
  }
  return $rt.checkString(s.slice($a, $b), $file, $line, $column);
});

module.exports = $string;
