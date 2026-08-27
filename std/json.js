"use strict";

// DEAL Standard Library: std/json (JavaScript backend).
// The hand-written CommonJS stdlib module (js-stdlib-modules D1-D3, D7-D8):
// parse = native JSON.parse plus the two decode-side parity scans and the
// recursive marked-Map shape conversion under the (string)->table boundary;
// stringify = the widened instance-aware entry gate, the validation/
// conversion walk mirroring encode_value, the cycle check, and native
// JSON.stringify on the converted graph. Exports exactly the .d.deal
// members as $rt.function wrappers with the pinned sig strings, parameter
// checks inside the body, and the declared-return check at exit; the
// trailing span parameters are declared and forwarded to every in-body
// check and the exit check. Loads from the deployed location
// <output>/std/json.js (../deal/runtime -> <output>/deal/runtime.js) and
// from the repo-root std/ directory (../deal/runtime -> repo-root
// deal/runtime.js) — the dual-location loadability the direct tests rely
// on (js-stdlib-modules D1).

// ===== Host-global capture (js-stdlib-modules D3) =====
// The only module-scope bindings of the host globals this module uses;
// Object (js-backend-runtime-artifact D10: not in the capture list, may
// be spelled bare inside trusted files) and Infinity (spelled bare in the
// runtime's own trusted scope, e.g. checkInt) follow the trusted-file
// convention — nothing else is spelled bare.
const $JSON = JSON;
const $Array = Array;
const $Map = Map;
const $undefined = void 0;

const $rt = require("../deal/runtime");

// ===== Module-private helpers (js-stdlib-modules D3: $ sigil by
// convention — no user bindings exist in hand-written scope) =====

// $walkSurrogates: the shared unpaired-surrogate unit walk (the
// $rt.checkString loop, deal/runtime.js:446-460) raising the caller's
// needle. A high surrogate (U+D800..U+DBFF) not followed by a low
// surrogate (U+DC00..U+DFFF), or a lone low surrogate, raises E8001 with
// the given message at the forwarded span; valid pairs advance two units.
function $walkSurrogates($s, $message, $file, $line, $column) {
  for (let $i = 0; $i < $s.length; $i++) {
    const $c = $s.charCodeAt($i);
    if ($c >= 0xd800 && $c <= 0xdbff) {
      const $next = $i + 1 < $s.length ? $s.charCodeAt($i + 1) : -1;
      if ($next < 0xdc00 || $next > 0xdfff) {
        $rt.fail("E8001", $message, $file, $line, $column);
      }
      $i++; // skip the low surrogate of a valid pair
    } else if ($c >= 0xdc00 && $c <= 0xdfff) {
      $rt.fail("E8001", $message, $file, $line, $column);
    }
  }
}

// $scanSurrogates: the parse-side unpaired-surrogate rejection
// (js-stdlib-modules D7) — the converted-output scan, deliberately not
// $rt.checkString (whose message is the boundary needle, not the parse
// needle). Faithful: the entry checkString already rejected raw unpaired
// surrogates in the input text, so any unpaired surrogate in a converted
// string necessarily came from a \uXXXX escape — exactly the case the
// reference rejects; valid pairs pass both layers.
function $scanSurrogates($s, $file, $line, $column) {
  $walkSurrogates($s, "JSON parse error: unpaired surrogate code unit in unicode escape", $file, $line, $column);
}

// $scanEncodeString: the stringify-side string-validity rejection
// (js-stdlib-modules D8 step 2 and the object-form key scan of step 5) —
// the mirror of encode_value's escape-side utf8_valid rejection
// (lua-std-json-unicode-conformance D6).
function $scanEncodeString($s, $file, $line, $column) {
  $walkSurrogates($s, "cannot encode invalid UTF-8 as JSON", $file, $line, $column);
}

// $scanRawControls: the pre-parse string-literal-aware raw-control scan
// (js-stdlib-modules D7) — the message-parity and determinism layer for
// the pinned raw-control needle. An in-string, unescaped UTF-16 unit
// < 0x20 raises "JSON parse error: raw control character in string (must
// be escaped)"; a backslash consumes the following unit as an escape pair
// (\\, \", \uXXXX all stay in-string); raw DEL 0x7F is outside the
// rejection range; raw controls outside strings are left to native
// JSON.parse (tab/CR/LF are legal JSON whitespace there). The scan can
// never widen acceptance: any in-string raw control it misses is still
// rejected by the native arm.
function $scanRawControls($text, $file, $line, $column) {
  let $inString = false;
  for (let $i = 0; $i < $text.length; $i++) {
    const $c = $text.charCodeAt($i);
    if ($inString) {
      if ($c === 0x22) {
        $inString = false; // closing quote
      } else if ($c === 0x5c) {
        $i++; // backslash: consume the escaped unit — the pair stays in-string
      } else if ($c < 0x20) {
        $rt.fail("E8001", "JSON parse error: raw control character in string (must be escaped)", $file, $line, $column);
      }
    } else if ($c === 0x22) {
      $inString = true; // opening quote
    }
  }
}

// $fromJson: the recursive JSON shape conversion (js-stdlib-modules D7).
// JSON null -> a fresh empty Map registered via markJsonNullTable at
// every depth; JSON array -> a fresh Map with the string keys "1".."n" in
// element order registered via markJsonArrayTable (an empty array
// registers an empty marked Map); JSON object -> a fresh Map of the own
// enumerable string keys (Object.keys — never for…in, which walks the
// prototype chain; JSON.parse creates __proto__ as an own data property,
// so a __proto__ key converts like any other and round-trips). Every
// converted string — object keys and nested values at every depth,
// including a top-level string before the exit check — passes the
// unpaired-surrogate scan. number/boolean/string scalars pass through.
// Maps are constructed via $rt.makeTable({}) plus .set calls — the only
// Map constructor path, so the products pass checkTable and the mark
// members accept them by construction. Fresh Maps, independent of inputs;
// a parsed JSON null never aliases a shared sentinel.
function $fromJson($v, $file, $line, $column) {
  if ($v === null) {
    return $rt.markJsonNullTable($rt.makeTable({}));
  }
  if ($Array.isArray($v)) {
    const $t = $rt.makeTable({});
    for (let $i = 0; $i < $v.length; $i++) {
      $t.set("" + ($i + 1), $fromJson($v[$i], $file, $line, $column));
    }
    return $rt.markJsonArrayTable($t);
  }
  if (typeof $v === "object") {
    const $t = $rt.makeTable({});
    const $keys = Object.keys($v);
    for (let $i = 0; $i < $keys.length; $i++) {
      const $key = $keys[$i];
      $scanSurrogates($key, $file, $line, $column);
      $t.set($key, $fromJson($v[$key], $file, $line, $column));
    }
    return $t;
  }
  if (typeof $v === "string") {
    $scanSurrogates($v, $file, $line, $column);
    return $v;
  }
  return $v; // number / boolean
}

// $encodeObjectForm: the object-form arm shared by the plain Map and the
// array-marked mixed-key fallback (js-stdlib-modules D8 step 5). A fresh
// {} whose entries are written via $rt.setProp — never a bare
// key: value assignment, so a __proto__ key becomes an own property and
// serializes (naive assignment invokes the inherited
// Object.prototype.__proto__ accessor; JSON.stringify(new Map(...)) is
// "{}"). Every key receives the unpaired-surrogate scan — the reference
// escapes every string key through escape(k), whose first act is the
// utf8_valid rejection (std/json.lua object branch, key_str =
// '"' .. escape(k) .. '"'), pinned by test_stdlib.lua's key rejection.
function $encodeObjectForm($m, $path, $file, $line, $column) {
  const $obj = {};
  for (const $key of $m.keys()) {
    $scanEncodeString($key, $file, $line, $column);
    $rt.setProp($obj, $key, $encode($m.get($key), $path, $file, $line, $column));
  }
  return $obj;
}

// $encodeArrayMarked: the array-marked Map arm (js-stdlib-modules D8
// step 5). The JSON array form exactly when every key is an integer-string
// matching ^[1-9][0-9]*$ and the maximum index is > 0 — elements 1..max in
// order, a missing index (a get that is $undefined) serializing as null;
// otherwise (mixed keys, a "0" key, or max index 0) the object form with
// the keys as-is. Array-form keys are integer-strings and cannot carry
// surrogates, so the key scan is vacuous there.
function $encodeArrayMarked($m, $path, $file, $line, $column) {
  let $max = 0;
  for (const $key of $m.keys()) {
    if (!/^[1-9][0-9]*$/.test($key)) {
      return $encodeObjectForm($m, $path, $file, $line, $column);
    }
    const $n = +$key;
    if ($n > $max) {
      $max = $n;
    }
  }
  if ($max === 0) {
    return $encodeObjectForm($m, $path, $file, $line, $column);
  }
  const $arr = new $Array($max);
  for (let $i = 1; $i <= $max; $i++) {
    const $elem = $m.get("" + $i);
    $arr[$i - 1] = $elem === $undefined ? null : $encode($elem, $path, $file, $line, $column);
  }
  return $arr;
}

// $encode: the stringify validation/conversion walk mirroring encode_value
// (js-stdlib-modules D8 steps 1-9). $path is the cycle-check stack — the
// Maps, Arrays, and class instances currently being walked; a value
// already on the path raises E8001 "circular reference in JSON encoding"
// (path-based tracking admits shared non-cyclic subgraphs, which serialize
// by duplication exactly as JSON requires). The converted graph is acyclic
// and contains only null/booleans/numbers/strings/Arrays/plain objects, so
// the final JSON.stringify cannot raise its own circular error and emits
// strictly conforming JSON text.
function $encode($v, $path, $file, $line, $column) {
  // 1. null -> JSON null.
  if ($v === null) {
    return null;
  }
  // 2. string -> unpaired-surrogate scan first (the mirror of
  // encode_value's escape-side validity rejection) then pass through —
  // escaped later by native JSON.stringify. Defensive for values
  // (conforming DEAL programs cannot construct such a leaf); the identical
  // scan is the pinned reference behavior for object-form keys (step 5).
  if (typeof $v === "string") {
    $scanEncodeString($v, $file, $line, $column);
    return $v;
  }
  // 3. boolean -> pass through.
  if (typeof $v === "boolean") {
    return $v;
  }
  // 4. number -> NaN / ±Infinity E8001 (std/json.lua:76-79); otherwise
  // pass through (native shortest round-trip formatting).
  if (typeof $v === "number") {
    if ($v !== $v) {
      $rt.fail("E8001", "cannot encode NaN as JSON", $file, $line, $column);
    }
    if ($v === Infinity || $v === -Infinity) {
      $rt.fail("E8001", "cannot encode Infinity as JSON", $file, $line, $column);
    }
    return $v;
  }
  // 5. Map -> null-marked first (the mark wins, checked before any shape
  // walk) -> array-marked -> plain Map object form.
  if ($v instanceof $Map) {
    if ($rt.isJsonNullTable($v)) {
      return null;
    }
    if ($path.indexOf($v) !== -1) {
      $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
    }
    $path.push($v);
    const $result = $rt.isJsonArrayTable($v)
      ? $encodeArrayMarked($v, $path, $file, $line, $column)
      : $encodeObjectForm($v, $path, $file, $line, $column);
    $path.pop();
    return $result;
  }
  // 6. Array -> recurse element-wise; a nil-equivalent element (the
  // array-delete write) serializes as null; the result is a fresh Array.
  if ($Array.isArray($v)) {
    if ($path.indexOf($v) !== -1) {
      $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
    }
    $path.push($v);
    const $result = new $Array($v.length);
    for (let $i = 0; $i < $v.length; $i++) {
      $result[$i] = $v[$i] === $undefined ? null : $encode($v[$i], $path, $file, $line, $column);
    }
    $path.pop();
    return $result;
  }
  // 7. Class instance -> a fresh object of the instance's own properties;
  // every entry of that fresh object — the mapped tags and each emitted
  // field — is written via $rt.setProp (Object.defineProperty, never the
  // inherited Object.prototype.__proto__ accessor). The tags use the
  // reference key spellings __kind/__classname; the field walk enumerates
  // Object.keys($v) and excludes the instance's own runtime tag properties
  // "$kind"/"$classname" first (makeClass step 4 wrote them as enumerable
  // own properties; emitting them would add $-keyed JSON fields the
  // reference output does not have; no user field can be named
  // $kind/$classname since user identifiers cannot contain $). Any
  // remaining field key equal to __kind/__classname is then skipped (the
  // tag-clobber skip: the reference's class_ tags every instance after the
  // field copy, so the mapped tag value is emitted once — identical JSON
  // text). A MISSING-valued absent optional is omitted; a field holding
  // null serializes as JSON null; nested values recurse. Field keys are
  // declared grammar identifiers (ASCII) and the tag keys are the fixed
  // spellings, so no key scan is needed in this arm.
  if (typeof $v === "object" && $v !== null && $v.$kind === "class") {
    if ($path.indexOf($v) !== -1) {
      $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
    }
    $path.push($v);
    const $obj = {};
    $rt.setProp($obj, "__kind", "class");
    $rt.setProp($obj, "__classname", $v.$classname);
    const $keys = Object.keys($v);
    for (let $i = 0; $i < $keys.length; $i++) {
      const $key = $keys[$i];
      if ($key === "$kind" || $key === "$classname") {
        continue; // field-walk exclusion — the instance's runtime tag properties
      }
      if ($key === "__kind" || $key === "__classname") {
        continue; // tag-clobber skip — the mapped tag value is emitted once
      }
      const $val = $v[$key];
      if ($val === $rt.MISSING) {
        continue; // absent optional omitted
      }
      $rt.setProp($obj, $key, $encode($val, $path, $file, $line, $column));
    }
    $path.pop();
    return $obj;
  }
  // 8. Wrapper -> E8001 "unsupported type for JSON encoding: function"
  // (the reference's message for function values, std/json.lua:120 — the
  // pinned json-stringify-function-e8001.deal contract, whose fixture
  // stores the wrapper as a nested value).
  if (typeof $v === "object" && $v !== null && $v.$kind === "function") {
    $rt.fail("E8001", "unsupported type for JSON encoding: function", $file, $line, $column);
  }
  // 9. A standalone MISSING, undefined, or any other object.
  $rt.fail("E8001", "unsupported type for JSON encoding", $file, $line, $column);
}

// ===== Export object: exactly the .d.deal members =====
const $json = {};

// stringify: the widened instance-aware entry gate (js-stdlib-modules D8)
// — a Map (plain, array-marked, or null-marked) or a class instance
// ($kind === "class") passes; everything else — Array, wrapper, plain
// object, a standalone MISSING, undefined, primitives — falls through
// checkTable with E8001 "expected table" and the runtime's $kindOf actual
// label (the strict table/array split; the bare checkTable would raise
// before the class-instance arm, so the gate is the pinned widening). The
// declared-return check runs at exit on the native JSON.stringify of the
// converted graph.
$json.stringify = $rt.function("(table)->string", function(v, $file, $line, $column) {
  if (!(v instanceof $Map) && !(typeof v === "object" && v !== null && v.$kind === "class")) {
    $rt.checkTable(v, $file, $line, $column);
  }
  return $rt.checkString($JSON.stringify($encode(v, [], $file, $line, $column)), $file, $line, $column);
});

// parse: native JSON.parse plus the two decode-side parity scans and the
// recursive marked-Map shape conversion (js-stdlib-modules D7). Order of
// operations: the entry checkString (the boundary needle for raw unpaired
// surrogates in the input text), the pre-parse string-literal-aware
// raw-control scan, the catch converting every native JSON.parse failure —
// unknown escapes, malformed \uXXXX, structural errors, raw controls
// outside strings, in-string raw controls as the fallback arm,
// deep-nesting RangeError — to the E8001 "JSON parse error: <native
// message>" envelope, the $fromJson conversion with the unpaired-surrogate
// scan of every converted string, and the declared-return table check at
// exit — a top-level scalar fails "expected table", and the scan arms fire
// before the exit check, so a top-level lone-surrogate string reports the
// parse error, not "expected table". On node v24.13.0 native $JSON.parse
// is iterative and does NOT raise the deep-nesting RangeError the
// reference contract names — the recursion limit moved into $fromJson
// itself — so a second narrow arm converts any non-DEAL error out of the
// conversion walk (the deep-nesting stack overflow) into the same E8001
// "JSON parse error" envelope with the span; DEALErrors from the scan
// arms ($dealCode set) are re-thrown unchanged, so the pinned needles are
// never re-wrapped.
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
