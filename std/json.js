"use strict";

// DEAL Standard Library: std/json — JavaScript backend (std/json.js).
// JSON decode/encode over the runtime's marked-Map table representation
// (js-stdlib-modules D1-D3, D7-D8; js-backend-runtime D9):
//
//   parse     — native $JSON.parse plus the two decode-side parity scans
//               (the pre-parse string-literal-aware raw-control scan and
//               the in-conversion unpaired-surrogate scan) and the
//               recursive marked-Map shape conversion, under the
//               "(string)->table" boundary (entry checkString, exit
//               checkTable);
//   stringify — the widened instance-aware entry gate (a Map or a class
//               instance passes; everything else fails through
//               $rt.checkTable with E8001 "expected table"), the $encode
//               validation/conversion walk mirroring std/json.lua's
//               encode_value with the cycle check, and native
//               $JSON.stringify on the converted graph, under the
//               "(table)->string" boundary (exit checkString).
//
// Hand-written and trusted: the module captures the host globals it uses
// at module top under $-names (js-stdlib-modules D3) — $JSON, $Array,
// $Map, and the nil-equivalent $undefined. Object/String may be spelled
// bare inside trusted files (the runtime's own D10 note; String appears
// in the pinned parse conversion, D7); the ±Infinity check avoids the
// bare Infinity spelling via the Double.MAX_VALUE magnitude bound. The
// module is pure and stateless; beyond the checks/error spine it consults
// makeTable (the only Map construction path), setProp (own-property-safe
// object writes — a __proto__ key becomes an own property and
// serializes), the four JSON shape marks (runtime-private WeakSets,
// unspoofable — consulted only through the $rt members), and MISSING
// (the absent-optional sentinel the class arm omits).

// ===== Host-global capture (js-stdlib-modules D3) =====
const $JSON = JSON;
const $Array = Array;
const $Map = Map;
const $undefined = void 0;

const $rt = require("../deal/runtime");

// ===== Decode-side parity scans (js-stdlib-modules D7) =====

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

// $scanSurrogates: the conversion-side unpaired-surrogate scan — the same
// surrogate-pair unit walk as $rt.checkString's loop (deal/runtime.js
// checkString) but raising the parse-side needle (D7). $rt.checkString is
// deliberately not reused: its message is the boundary needle ("expected
// string, got invalid UTF-8 encoding"), not the parse-side one. The scan
// is faithful: the entry checkString already rejected unpaired surrogates
// in the raw input text, so any unpaired surrogate in a converted string
// necessarily came from a \uXXXX escape — exactly the case the reference
// rejects (std/json.lua lone-low/lone-high/uncombined-high arms); valid
// pairs pass.
function $scanSurrogates($s, $file, $line, $column) {
  for (let $i = 0; $i < $s.length; $i++) {
    const $c = $s.charCodeAt($i);
    if ($c >= 0xd800 && $c <= 0xdbff) {
      const $next = $i + 1 < $s.length ? $s.charCodeAt($i + 1) : -1;
      if ($next < 0xdc00 || $next > 0xdfff) {
        $rt.fail("E8001", "JSON parse error: unpaired surrogate code unit in unicode escape", $file, $line, $column);
      }
      $i++; // skip the low surrogate of a valid pair
    } else if ($c >= 0xdc00 && $c <= 0xdfff) {
      $rt.fail("E8001", "JSON parse error: unpaired surrogate code unit in unicode escape", $file, $line, $column);
    }
  }
}

// ===== Parse shape conversion (js-stdlib-modules D7) =====

// $fromJson: recursive conversion of a native $JSON.parse result into the
// runtime's table representation. JSON null -> a fresh empty Map
// registered via $rt.markJsonNullTable at every depth (the reference's
// __NULL, std/json.lua:226); JSON array -> a fresh Map with the string
// keys "1".."n" in element order, registered via $rt.markJsonArrayTable
// (an empty array registers an empty marked Map); JSON object -> a fresh
// Map iterating own enumerable string keys (Object.keys — never for...in,
// which walks the prototype chain; $JSON.parse creates __proto__ as an
// own data property, so a __proto__ key converts like any other);
// number/boolean/string scalars pass through. Every converted string —
// object keys and nested values at every depth, including a top-level
// string before the exit check — crosses $scanSurrogates first, so a
// lone-surrogate \uXXXX escape reports the parse-side needle (D7). Maps
// are constructed via $rt.makeTable({}) plus .set calls — the only Map
// construction path — so the products pass $rt.checkTable and the mark
// members accept them by construction. Fresh Maps, independent of inputs;
// a parsed JSON null never aliases a shared sentinel.
function $fromJson($value, $file, $line, $column) {
  if ($value === null) {
    return $rt.markJsonNullTable($rt.makeTable({}));
  }
  if (typeof $value === "string") {
    $scanSurrogates($value, $file, $line, $column);
    return $value;
  }
  if ($Array.isArray($value)) {
    const $t = $rt.makeTable({});
    for (let $i = 0; $i < $value.length; $i++) {
      $t.set(String($i + 1), $fromJson($value[$i], $file, $line, $column));
    }
    return $rt.markJsonArrayTable($t);
  }
  if (typeof $value === "object") {
    const $t = $rt.makeTable({});
    const $keys = Object.keys($value);
    for (let $i = 0; $i < $keys.length; $i++) {
      const $key = $keys[$i];
      $scanSurrogates($key, $file, $line, $column);
      $t.set($key, $fromJson($value[$key], $file, $line, $column));
    }
    return $t;
  }
  // number / boolean scalars pass through.
  return $value;
}

// ===== Stringify validation and conversion (js-stdlib-modules D8) =====

// $scanStringValidity: the encode-side scalar-validity scan (D8 steps 2
// and 5) — the same surrogate-pair unit walk with the encode needle
// "cannot encode invalid UTF-8 as JSON" (the mirror of encode_value's
// escape-side utf8_valid rejection, lua-std-json-unicode-conformance D6).
// Defensive for string values — conforming DEAL programs cannot construct
// such a leaf (every string crossed a checkString boundary) — and the
// pinned reference behavior for object-form keys (test_stdlib.lua
// "json.stringify rejects invalid UTF-8 keys with E8001").
function $scanStringValidity($s, $file, $line, $column) {
  for (let $i = 0; $i < $s.length; $i++) {
    const $c = $s.charCodeAt($i);
    if ($c >= 0xd800 && $c <= 0xdbff) {
      const $next = $i + 1 < $s.length ? $s.charCodeAt($i + 1) : -1;
      if ($next < 0xdc00 || $next > 0xdfff) {
        $rt.fail("E8001", "cannot encode invalid UTF-8 as JSON", $file, $line, $column);
      }
      $i++; // skip the low surrogate of a valid pair
    } else if ($c >= 0xdc00 && $c <= 0xdfff) {
      $rt.fail("E8001", "cannot encode invalid UTF-8 as JSON", $file, $line, $column);
    }
  }
}

// $arrayFormIndex: the array-form key predicate (D8 step 5) — returns the
// integer index for a key that is an integer-string matching
// ^[1-9][0-9]*$ (a positive integer string without leading zeros) and
// null otherwise. A manual unit walk keeps the predicate free of
// RegExp/Number host-global spellings, and a non-string key is never
// coerced — only integer-strings count as array-form keys.
function $arrayFormIndex($key) {
  if (typeof $key !== "string" || $key === "") {
    return null;
  }
  const $c0 = $key.charCodeAt(0);
  if ($c0 < 0x31 || $c0 > 0x39) {
    return null;
  }
  let $n = $c0 - 0x30;
  for (let $i = 1; $i < $key.length; $i++) {
    const $c = $key.charCodeAt($i);
    if ($c < 0x30 || $c > 0x39) {
      return null;
    }
    $n = $n * 10 + ($c - 0x30);
  }
  return $n;
}

// $encode: the validation/conversion walk mirroring std/json.lua's
// encode_value. $path is the cycle-check stack: the Maps, Arrays, and
// class instances currently being walked (push before recursing, pop
// after — the finally arms), so a value already on the path raises E8001
// "circular reference in JSON encoding" while shared (non-cyclic)
// subgraphs serialize by duplication. Steps 1-9:
//   1. null -> JSON null;
//   2. string -> $scanStringValidity first, then pass through (escaped
//      later by native $JSON.stringify);
//   3. boolean -> pass through;
//   4. number -> NaN/±Infinity E8001 arms, otherwise pass through
//      (native shortest round-trip formatting);
//   5. Map -> null-marked first (the mark always wins, checked before
//      any shape walk); array-marked -> the JSON array form exactly when
//      every key is an integer-string matching ^[1-9][0-9]*$ and the
//      maximum index is > 0 — elements 1..max in order, a missing index
//      (a get that is $undefined) serializing as null — else the object
//      form with the keys as-is (the mixed-key fallback); plain Map ->
//      the object form: a fresh {} whose entries are written via
//      $rt.setProp (never a bare key: value assignment — a __proto__ key
//      becomes an own property and serializes). Object-form key validity
//      (both object-form arms): every string key crosses
//      $scanStringValidity before its entry is written; array-form keys
//      are integer strings and cannot carry surrogates, so the scan is
//      vacuous there;
//   6. Array -> element-wise recursion, a nil-equivalent element (the
//      array-delete write) serializing as null, the result a fresh
//      Array;
//   7. class instance -> a fresh object of the instance's own properties
//      with every entry written via $rt.setProp: the tags under the
//      reference key spellings, then the declared fields keyed as-is —
//      the walk enumerates Object.keys and excludes the instance's own
//      runtime tag properties "$kind"/"$classname" first, skips any
//      remaining field key equal to __kind/__classname (the tag-clobber
//      skip), omits a $rt.MISSING-valued absent optional, encodes a null
//      field as JSON null, and writes a declared __proto__ field via
//      $rt.setProp as an ordinary key; field keys are ASCII grammar
//      identifiers and the tag keys are fixed spellings, so no key scan
//      is needed in this arm;
//   8. wrapper ($kind === "function") -> E8001 "unsupported type for
//      JSON encoding: function";
//   9. a standalone $rt.MISSING, $undefined, or any other object ->
//      E8001 "unsupported type for JSON encoding".
function $encode($v, $path, $file, $line, $column) {
  if ($v === null) {
    return null;
  }
  const $type = typeof $v;
  if ($type === "string") {
    $scanStringValidity($v, $file, $line, $column);
    return $v;
  }
  if ($type === "boolean") {
    return $v;
  }
  if ($type === "number") {
    if ($v !== $v) {
      $rt.fail("E8001", "cannot encode NaN as JSON", $file, $line, $column);
    }
    // ±Infinity without the bare host-global spelling: every finite IEEE
    // double has magnitude <= 1.7976931348623157e308 (Double.MAX_VALUE),
    // so a larger magnitude is exactly ±Infinity.
    if ($v > 1.7976931348623157e308 || $v < -1.7976931348623157e308) {
      $rt.fail("E8001", "cannot encode Infinity as JSON", $file, $line, $column);
    }
    return $v;
  }
  if ($v instanceof $Map) {
    // Null-marked first: the mark always wins, checked before any shape
    // walk — a null-marked Map serializes as null and never recurses, so
    // it can never false-positive the cycle check.
    if ($rt.isJsonNullTable($v)) {
      return null;
    }
    if ($path.includes($v)) {
      $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
    }
    $path.push($v);
    try {
      if ($rt.isJsonArrayTable($v)) {
        let $max = 0;
        let $arrayForm = true;
        for (const $key of $v.keys()) {
          const $index = $arrayFormIndex($key);
          if ($index === null) {
            $arrayForm = false;
            break;
          }
          if ($index > $max) {
            $max = $index;
          }
        }
        if ($arrayForm && $max > 0) {
          const $arr = [];
          for (let $i = 1; $i <= $max; $i++) {
            const $element = $v.get(String($i));
            $arr.push($element === $undefined ? null : $encode($element, $path, $file, $line, $column));
          }
          return $arr;
        }
      }
      const $obj = {};
      for (const $key of $v.keys()) {
        if (typeof $key === "string") {
          $scanStringValidity($key, $file, $line, $column);
        }
        $rt.setProp($obj, $key, $encode($v.get($key), $path, $file, $line, $column));
      }
      return $obj;
    } finally {
      $path.pop();
    }
  }
  if ($Array.isArray($v)) {
    if ($path.includes($v)) {
      $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
    }
    $path.push($v);
    try {
      const $arr = [];
      for (let $i = 0; $i < $v.length; $i++) {
        const $element = $v[$i];
        $arr.push($element === $undefined ? null : $encode($element, $path, $file, $line, $column));
      }
      return $arr;
    } finally {
      $path.pop();
    }
  }
  if ($type === "object" && $v.$kind === "class") {
    if ($path.includes($v)) {
      $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
    }
    $path.push($v);
    try {
      const $obj = {};
      $rt.setProp($obj, "__kind", "class");
      $rt.setProp($obj, "__classname", $v.$classname);
      const $keys = Object.keys($v);
      for (let $i = 0; $i < $keys.length; $i++) {
        const $key = $keys[$i];
        if ($key === "$kind" || $key === "$classname") {
          continue; // field-walk exclusion: the instance's runtime tag properties
        }
        if ($key === "__kind" || $key === "__classname") {
          continue; // tag-clobber skip: the mapped tag value is emitted once
        }
        const $value = $v[$key];
        if ($value === $rt.MISSING) {
          continue; // absent optional omitted
        }
        $rt.setProp($obj, $key, $encode($value, $path, $file, $line, $column));
      }
      return $obj;
    } finally {
      $path.pop();
    }
  }
  if ($type === "object" && $v.$kind === "function") {
    $rt.fail("E8001", "unsupported type for JSON encoding: function", $file, $line, $column);
  }
  $rt.fail("E8001", "unsupported type for JSON encoding", $file, $line, $column);
}

// ===== Module export: exactly the .d.deal members as $rt.function
// wrappers (js-stdlib-modules D1-D2) =====
const $json = {};

// stringify: the widened instance-aware entry gate — a Map or a class
// instance passes; everything else (Array, wrapper, plain object, a
// standalone MISSING, undefined, primitives) falls through $rt.checkTable
// with E8001 "expected table" and the runtime's $kindOf actual label —
// then the $encode walk, native $JSON.stringify on the converted graph,
// and the declared-return checkString at exit.
$json.stringify = $rt.function("(table)->string", function(v, $file, $line, $column) {
  if (!(v instanceof $Map) && !(typeof v === "object" && v !== null && v.$kind === "class")) {
    $rt.checkTable(v, $file, $line, $column);
  }
  return $rt.checkString($JSON.stringify($encode(v, [], $file, $line, $column)), $file, $line, $column);
});

// parse: entry checkString, the pre-parse raw-control scan, native
// $JSON.parse with every failure converted to the E8001 "JSON parse
// error" envelope, the recursive $fromJson conversion (with the
// unpaired-surrogate scan), and the declared-return checkTable at exit —
// a top-level scalar fails with "expected table" while the scan arms fire
// before the exit check, so a top-level lone-surrogate string reports the
// parse error. On node v24.13.0 native $JSON.parse is iterative and does
// NOT raise the deep-nesting RangeError the reference contract names —
// the recursion limit moved into $fromJson itself — so a second narrow
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
    $table = $fromJson($value, $file, $line, $column);
  } catch ($e) {
    if (typeof $e === "object" && $e !== null && $e.$dealCode !== $undefined) {
      throw $e;
    }
    $rt.fail("E8001", "JSON parse error: " + $e.message, $file, $line, $column);
  }
  return $rt.checkTable($table, $file, $line, $column);
});

module.exports = $json;
