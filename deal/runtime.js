"use strict";

// DEAL Runtime Library v1.2 — JavaScript backend (deal/runtime.js).
// The single hand-written CommonJS runtime module exporting $rt, the JS
// analog of deal/runtime.lua (js-backend-runtime D1-D9, pinned by
// js-backend-runtime-artifact), plus the shared D9 JSON conversion and
// the @jsonable runtime walkers (js-v12-jsonable-completion D2-D5), and
// the production async-export invoker (js-v12-async-export-invocation D1-D4). It
// loads standalone under node v24 from "./deal/runtime" and from any
// "<relpath>/deal/runtime" require path —
// no require call anywhere in the file (js-backend-runtime-artifact D1/D10).

// ===== Host-global capture (js-backend-runtime-artifact D10) =====
// The only module-scope bindings of the host globals the runtime owns.
// Every later runtime use of process, console, Math, JSON, Map, Array,
// Uint8Array, or undefined is confined to these captures; generated code
// spells none of them bare (js-backend-architecture D2). Object and
// String are not in the capture list and may be spelled bare inside this
// trusted file. $Uint8Array joins the list with the v1.2 bytes carrier
// (js-v12-int32-bytes D3): the constructor is captured at runtime load,
// so no generated module ever spells the host-global Uint8Array.
const $process = process;
const $console = console;
const $Math = Math;
const $JSON = JSON;
const $Map = Map;
const $Array = Array;
const $Uint8Array = Uint8Array;
const $undefined = void 0;

// ===== Error spine: the module-private $DEALError class =====
// (js-backend-runtime-artifact D8) The single constructor path every
// runtime failure is thrown through: fail() throws a $DEALError, an Error
// subclass (native Node stacks; every instance satisfies instanceof Error
// and instanceof $DEALError). Instance fields follow the spec
// DEALRuntimeError shape with plain DEAL-observable names — code, message,
// file, line, column, expected, actual — plus the $-sigiled $dealCode
// alias. file/line/column/expected/actual materialize as own properties
// only when the corresponding argument is not undefined, the _err
// absent-field convention (deal/runtime.lua:19-32). message comes from
// super(message), so err.message === message.
class $DEALError extends Error {
  constructor($code, $message, $file, $line, $column, $expected, $actual) {
    super($message);
    this.code = $code;
    this.$dealCode = $code;
    if ($file !== $undefined) this.file = $file;
    if ($line !== $undefined) this.line = $line;
    if ($column !== $undefined) this.column = $column;
    if ($expected !== $undefined) this.expected = $expected;
    if ($actual !== $undefined) this.actual = $actual;
  }
}

// ===== Sentinels (js-backend-runtime-artifact D2) =====
// $MISSING: the unique object sentinel for absent optional class fields —
// the only sentinel in the runtime (not null, not undefined, === only to
// itself). DEAL null is JS null ($rt.NULL === null).
const $MISSING = {};

// ===== Runtime-private JSON shape marks (js-backend-runtime-artifact D2) =====
// Unspoofable WeakSets consulted only by the D9 JSON conversion
// (jsonParseValue/jsonEncodeValue — the std/json module and the
// @jsonable walkers' table branches). Never exported; no $rt member
// exposes them.
const $jsonArrayTables = new WeakSet();
const $jsonNullTables = new WeakSet();

// ===== Profile-gated int32 range flag (js-v12-int32-bytes D2) =====
// $int32: the module-private signed-32 activation flag — false at load
// (the retained LEGACY_SAFE_INT ±(2^53-1) boundary). Only the idempotent
// $rt.setInt32Mode(true) selector sets it; no member ever resets it, so
// activation is one-way per runtime instance. checkInt's final range arm
// is the single consumer — one boundary gates every int helper,
// intConvert, and the RuntimeTypeMatcher int row, which all route
// through checkInt.
let $int32 = false;

// ===== Actual-kind mapping (js-backend-runtime-artifact D3) =====
// $kindOf: the diagnostic "actual" string for every check — the JS mirror
// of Lua's type() augmented with the runtime's own value forms. undefined
// (the T1 captured nil-equivalent, compared against $undefined — never the
// bare spelling) maps to "nil"; null to "null"; wrappers and class
// instances are identified by their $kind tag before the Map/Array
// branches; Maps (the T1 $Map capture) are tables; Arrays (the T1 $Array
// capture) are arrays; Uint8Arrays (the v1.2 bytes carrier,
// js-v12-int32-bytes D3) are bytes; everything else reports the JS
// typeof name.
function $kindOf($v) {
  if ($v === $undefined) return "nil";
  if ($v === null) return "null";
  if ($v.$kind === "function") return "function";
  if ($v.$kind === "class") return "class";
  if ($v instanceof $Map) return "table";
  if ($Array.isArray($v)) return "array";
  if ($v instanceof $Uint8Array) return "bytes";
  return typeof $v;
}

// ===== Canonical descriptor parser (js-v12-completion-architecture D3) =====
// $parse: the module-private canonical descriptor parser — the runtime
// realization of CanonicalRuntimeTypeDescriptor.parse (the strict
// recursive-descent grammar over Unicode scalars with complete-input
// consumption). Only canonical spellings parse: primitive names
// null|boolean|int|number|string|bytes|table; "[D]" arrays; "?D"
// nullables ("?D" and "[D]" accept ANY descriptor D, function
// descriptors included — "?(int)->int" and "[(int)->int]" parse);
// "async"? "(params)" "->" D functions; "@" class atoms with at least two
// components and an identifier-shaped final component, carried verbatim
// (byte-for-byte nominal compare). Every legacy spelling — "T[]",
// "T|null", rest-parameter sigs, bare class names, the bare "Error"
// atom, dotted class-name-position text, nested nullables ("??T"),
// nullable-of-null ("?null"), empty/malformed arrays and functions,
// invalid class atoms, trailing content — fails the parse and returns
// null, so checkType raises the pinned defensive E8001
// "internal: cannot parse type descriptor: {text}". Records stay
// $-keyed exactly like the retired parser: { $t: "nullable", $inner },
// { $t: "function", $async, $params, $ret }, { $t: "array", $element },
// { $t: "primitive", $name }, { $t: "class", $name } — the dispatch
// surface is unchanged, only the accepted grammar is. The non-string
// guard mirrors parse_descriptor's nil return (deal/runtime.lua:287-290);
// checkType routes only strict null to its nil arm before this guard
// ever runs.

// The pinned component whitespace set of the canonical grammar — the
// exact ModuleIdentityResolver.isUnicodeWhiteSpace exclusion (the
// White_Space property pin 0009-000D, 0020, 0085, 00A0, 1680,
// 2000-200A, 2028, 2029, 202F, 205F, 3000): every JS \s scalar minus
// U+FEFF, plus U+0085. The Java parser's forbiddenInComponent checks
// this same property pin explicitly — never
// Character.isWhitespace/isSpaceChar, whose isWhitespace excludes
// U+0085 NEXT LINE since JDK 5.
// U+200B ZERO WIDTH SPACE is deliberately absent: it is a legal
// component scalar in the Java parser and the identity index.
const $CANONICAL_WS = /[\t\n\v\f\r \u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]/u;

function $parse($descriptor) {
  if ($descriptor === $undefined || $descriptor === null || typeof $descriptor !== "string") {
    return null;
  }
  // Decode the string into Unicode scalars with their UTF-16 indices
  // (a codePointAt walk), so the grammar runs over scalar positions and
  // record texts slice back to byte-identical substrings.
  const $cps = [];
  for (let $i = 0; $i < $descriptor.length;) {
    const $cp = $descriptor.codePointAt($i);
    $cps.push({ $cp: $cp, $idx: $i });
    $i += $cp > 0xFFFF ? 2 : 1;
  }
  const $st = { $pos: 0 };
  const $node = $parseOne($descriptor, $cps, $st);
  if ($node === null || $st.$pos !== $cps.length) {
    // Complete-input consumption is mandatory: any failure or any
    // trailing content (the legacy T[]/T|null spellings included) is
    // unparsable text.
    return null;
  }
  return $node;
}

// The UTF-16 index of a scalar position, or the string end.
function $scalarIdx($cps, $scalarPos) {
  return $scalarPos < $cps.length ? $cps[$scalarPos].$idx : Infinity;
}

// The text slice covering scalar positions [$startScalar, $endScalar).
function $slice($d, $cps, $startScalar, $endScalar) {
  const $endIdx = $scalarIdx($cps, $endScalar);
  return $d.slice($cps[$startScalar].$idx, $endIdx === Infinity ? $d.length : $endIdx);
}

// One descriptor at the current scalar position; null on any failure.
// No partial AST is ever produced.
function $parseOne($d, $cps, $st) {
  if ($st.$pos >= $cps.length) return null;
  const $cp = $cps[$st.$pos].$cp;
  if ($cp === 0x5B) { // '['
    return $parseArray($d, $cps, $st);
  }
  if ($cp === 0x3F) { // '?'
    return $parseNullable($d, $cps, $st);
  }
  if ($cp === 0x28) { // '('
    return $parseFunction($d, $cps, $st, false);
  }
  if ($cp === 0x40) { // '@'
    return $parseClass($d, $cps, $st);
  }
  // Primitive keywords (disjoint, so order does not matter) — bytes
  // included so its dispatch reaches the Uint8Array matcher row.
  const $keywords = ["null", "boolean", "int", "number", "string", "bytes", "table"];
  for (let $k = 0; $k < $keywords.length; $k++) {
    if ($matchKeyword($cps, $st.$pos, $keywords[$k])) {
      const $name = $keywords[$k];
      $st.$pos += $name.length;
      return { $t: "primitive", $name: $name };
    }
  }
  // The exact async marker: "async" immediately followed by "(".
  if ($matchKeyword($cps, $st.$pos, "async")
      && $st.$pos + 5 < $cps.length && $cps[$st.$pos + 5].$cp === 0x28) {
    $st.$pos += 5;
    return $parseFunction($d, $cps, $st, true);
  }
  // Every other spelling — bare class names ("Error" included),
  // "T[]"/"T|null" shapes, rest sigs — is unparsable.
  return null;
}

// "[ descriptor ]".
function $parseArray($d, $cps, $st) {
  $st.$pos++; // consume '['
  const $innerStart = $st.$pos;
  const $inner = $parseOne($d, $cps, $st);
  if ($inner === null) return null;
  if ($st.$pos >= $cps.length || $cps[$st.$pos].$cp !== 0x5D) return null;
  const $element = $slice($d, $cps, $innerStart, $st.$pos);
  $st.$pos++; // consume ']'
  return { $t: "array", $element: $element };
}

// "? descriptor" with the pinned nested-nullable/null-inner rejections
// (the canonical grammar's D2 rules; "?D" accepts every other
// descriptor D, function descriptors included).
function $parseNullable($d, $cps, $st) {
  $st.$pos++; // consume '?'
  const $innerStart = $st.$pos;
  const $inner = $parseOne($d, $cps, $st);
  if ($inner === null) return null;
  if ($inner.$t === "nullable") return null;
  if ($inner.$t === "primitive" && $inner.$name === "null") return null;
  return { $t: "nullable", $inner: $slice($d, $cps, $innerStart, $st.$pos) };
}

// async? "(" (descriptor ("," descriptor)*)? ")" "->" descriptor.
// No rest-parameter arm exists in DEAL v1.2.
function $parseFunction($d, $cps, $st, $isAsync) {
  $st.$pos++; // consume '('
  const $params = [];
  if ($st.$pos < $cps.length && $cps[$st.$pos].$cp !== 0x29) { // ')'
    while (true) {
      const $paramStart = $st.$pos;
      const $param = $parseOne($d, $cps, $st);
      if ($param === null) return null;
      $params.push($slice($d, $cps, $paramStart, $st.$pos));
      if ($st.$pos >= $cps.length) return null;
      if ($cps[$st.$pos].$cp === 0x2C) { $st.$pos++; continue; } // ','
      if ($cps[$st.$pos].$cp === 0x29) break; // ')'
      return null;
    }
  }
  if ($st.$pos >= $cps.length) return null;
  $st.$pos++; // consume ')'
  // The exact "->" arrow.
  if ($st.$pos >= $cps.length || $cps[$st.$pos].$cp !== 0x2D) return null;
  if ($st.$pos + 1 >= $cps.length || $cps[$st.$pos + 1].$cp !== 0x3E) return null;
  $st.$pos += 2; // consume '->'
  const $retStart = $st.$pos;
  const $ret = $parseOne($d, $cps, $st);
  if ($ret === null) return null;
  // Async functions report $ret "null" so the declared return type R is
  // enforced at the await site, not by the wrapper
  // (deal/runtime.lua:369-375).
  if ($isAsync) {
    return { $t: "function", $async: true, $params: $params, $ret: "null" };
  }
  return { $t: "function", $async: false, $params: $params,
    $ret: $slice($d, $cps, $retStart, $st.$pos) };
}

// "@" component ("/" component)+ with the pinned class-atom shape rules.
// Components are maximal allowed runs; the atom ends exactly at its
// enclosing delimiter (end of input, "]", ")", ",") and is carried
// verbatim with the leading "@". At least one "/" and an
// identifier-shaped final component are mandatory; no root/path boundary
// is ever inferred and class atoms compare byte-for-byte.
function $parseClass($d, $cps, $st) {
  const $atomStart = $st.$pos;
  $st.$pos++; // consume '@'
  let $hasSeparator = false;
  while (true) {
    const $componentStart = $st.$pos;
    while ($st.$pos < $cps.length) {
      const $cp = $cps[$st.$pos].$cp;
      if ($cp === 0x5D || $cp === 0x29 || $cp === 0x2C) break; // ] ) ,
      if ($cp === 0x2F) break; // '/'
      if ($cp === 0x2D && $st.$pos + 1 < $cps.length
          && $cps[$st.$pos + 1].$cp === 0x3E) return null; // contiguous "->"
      if ($forbiddenInComponent($cp)) break; // maximal run ends here
      $st.$pos++;
    }
    if ($st.$pos === $componentStart) return null; // empty component
    const $component = $slice($d, $cps, $componentStart, $st.$pos);
    if ($component === "." || $component === "..") return null;
    if ($st.$pos < $cps.length && $cps[$st.$pos].$cp === 0x2F) {
      $hasSeparator = true;
      $st.$pos++; // consume '/' — the next component must be non-empty
      continue;
    }
    // This component terminates the atom, so it is the class name and
    // must match the source identifier shape. Dotted class-name-position
    // text fails exactly here.
    if (!$isIdentifierShape($component)) return null;
    if (!$hasSeparator) return null; // fewer than two components
    return { $t: "class", $name: $slice($d, $cps, $atomStart, $st.$pos) };
  }
}

// The pinned component alphabet: forbidden scalars end the maximal run.
function $forbiddenInComponent($cp) {
  if ($cp < 0x20 || $cp === 0x7F) return true; // U+0000, C0/DEL controls
  if ($cp === 0x40 || $cp === 0x5B || $cp === 0x5D || $cp === 0x3F
      || $cp === 0x28 || $cp === 0x29 || $cp === 0x2C) return true; // @ [ ] ? ( ) ,
  if ($CANONICAL_WS.test(String.fromCodePoint($cp))) return true;
  return $cp >= 0xD800 && $cp <= 0xDFFF; // lone surrogates
}

// The final-component class-name shape: [A-Za-z_][A-Za-z0-9_]*.
function $isIdentifierShape($s) {
  if ($s === "") return false;
  const $c0 = $s.charCodeAt(0);
  if (!($c0 >= 0x41 && $c0 <= 0x5A) && !($c0 >= 0x61 && $c0 <= 0x7A)
      && $c0 !== 0x5F) return false;
  for (let $i = 1; $i < $s.length; $i++) {
    const $c = $s.charCodeAt($i);
    const $ok = ($c >= 0x41 && $c <= 0x5A) || ($c >= 0x61 && $c <= 0x7A)
        || ($c >= 0x30 && $c <= 0x39) || $c === 0x5F;
    if (!$ok) return false;
  }
  return true;
}

// Exact keyword match against the decoded scalars at the given position.
function $matchKeyword($cps, $pos, $kw) {
  if ($pos + $kw.length > $cps.length) return false;
  for (let $i = 0; $i < $kw.length; $i++) {
    if ($cps[$pos + $i].$cp !== $kw.charCodeAt($i)) return false;
  }
  return true;
}

// ===== Array element extraction (canonical grammar, js-v12-completion-architecture D3) =====
// $arrayElementDescriptor: the canonical "[D]" prefix -> "D" extraction
// (the legacy "T[]" suffix arm retired with the dialect — the parser
// rejects "T[]" text, and checkArray only ever receives parsed
// "[D]" text). Anything else takes the E8001 "invalid array descriptor"
// error. The strict === null nil arm mirrors the reference's defensive
// guard (deal/runtime.lua:240-242); reachable only through direct misuse
// (A3). Non-string descriptors fail loudly via the invalid-arm text,
// never a raw TypeError.
function $arrayElementDescriptor($descriptor, $file, $line, $column) {
  if ($descriptor === null) {
    $rt.fail("E8001", "internal: nil array descriptor", $file, $line, $column);
  }
  if (typeof $descriptor === "string") {
    if ($descriptor.length >= 2 && $descriptor[0] === "[" && $descriptor[$descriptor.length - 1] === "]") {
      return $descriptor.slice(1, $descriptor.length - 1);
    }
  }
  $rt.fail("E8001", "invalid array descriptor: " + $descriptor, $file, $line, $column);
}

// $isPlainObject: the class-construction plain-object predicate
// (js-backend-runtime-artifact D6 step 1). A value is plain exactly when
// its prototype is Object.prototype or null — the two shapes the emitter
// produces for defaults thunk results and provided literals. Everything
// else (Array, Map, function, class instances of other prototypes,
// primitives) is rejected by the defensive arms of makeClass.
function $isPlainObject($v) {
  if ($v === null || typeof $v !== "object") {
    return false;
  }
  const $proto = Object.getPrototypeOf($v);
  return $proto === Object.prototype || $proto === null;
}

// ===== D9 JSON conversion (js-backend-runtime D9) =====
// The two conversion walks std/json and the @jsonable runtime walkers
// share (js-v12-jsonable-completion D2/D4): jsonParseValue converts a
// native $JSON.parse result into the runtime's marked-Map table shape,
// and jsonEncodeValue runs the encode-side validation/conversion walk
// with the cycle check. Both live here because the walkers depend on
// them through $rt and std/json depends on the runtime (the reverse
// dependency is forbidden — js-backend-runtime D9). The walks are pure:
// fresh Maps/Arrays/objects only, no input mutation.

// $scanSurrogates: the decode-side unpaired-surrogate scan — the same
// surrogate-pair unit walk as checkString's loop but raising the
// parse-side needle. checkString is deliberately not reused: its message
// is the boundary needle ("expected string, got invalid UTF-8
// encoding"), not the parse-side one. The scan is faithful: the entry
// checkString already rejected unpaired surrogates in the raw input
// text, so any unpaired surrogate in a converted string necessarily came
// from a \uXXXX escape — exactly the case the reference rejects
// (std/json.lua lone-low/lone-high/uncombined-high arms); valid pairs
// pass.
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

// $fromJsonConvert: recursive conversion of a native $JSON.parse result
// into the runtime's table representation. JSON null -> a fresh empty
// Map registered via $rt.markJsonNullTable at every depth (the
// reference's __NULL, std/json.lua:226); JSON array -> a fresh Map with
// the string keys "1".."n" in element order, registered via
// $rt.markJsonArrayTable (an empty array registers an empty marked Map);
// JSON object -> a fresh Map iterating own enumerable string keys
// (Object.keys — never for...in, which walks the prototype chain;
// $JSON.parse creates __proto__ as an own data property, so a __proto__
// key converts like any other); number/boolean/string scalars pass
// through. Every converted string — object keys and nested values at
// every depth — crosses $scanSurrogates first, so a lone-surrogate
// \uXXXX escape reports the parse-side needle. Maps are constructed via
// $rt.makeTable({}) plus .set calls — the only Map construction path —
// so the products pass $rt.checkTable and the mark members accept them
// by construction. Fresh Maps, independent of inputs; a parsed JSON null
// never aliases a shared sentinel.
function $fromJsonConvert($value, $file, $line, $column) {
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
      $t.set(String($i + 1), $fromJsonConvert($value[$i], $file, $line, $column));
    }
    return $rt.markJsonArrayTable($t);
  }
  if (typeof $value === "object") {
    const $t = $rt.makeTable({});
    const $keys = Object.keys($value);
    for (let $i = 0; $i < $keys.length; $i++) {
      const $key = $keys[$i];
      $scanSurrogates($key, $file, $line, $column);
      $t.set($key, $fromJsonConvert($value[$key], $file, $line, $column));
    }
    return $t;
  }
  // number / boolean scalars pass through.
  return $value;
}

// $scanStringValidity: the encode-side scalar-validity scan — the same
// surrogate-pair unit walk with the encode needle "cannot encode invalid
// UTF-8 as JSON" (the mirror of encode_value's escape-side utf8_valid
// rejection, lua-std-json-unicode-conformance D6). Defensive for string
// values — conforming DEAL programs cannot construct such a leaf (every
// string crossed a checkString boundary) — and the pinned reference
// behavior for object-form keys.
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

// $arrayFormIndex: the array-form key predicate — returns the integer
// index for a key that is an integer-string matching ^[1-9][0-9]*$ (a
// positive integer string without leading zeros) and null otherwise. A
// manual unit walk keeps the predicate free of RegExp/Number host-global
// spellings, and a non-string key is never coerced — only integer-strings
// count as array-form keys.
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

// $encodeConvert: the validation/conversion walk mirroring std/json.lua's
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
//   8. bytes (a $Uint8Array value) -> E8001 "unsupported type for
//      JSON encoding: bytes" — the explicit bytes arm (js-v12-int32-bytes
//      D3); $rt.isBytes is the single detection seam, never a bare
//      Uint8Array spelling;
//   9. wrapper ($kind === "function") -> E8001 "unsupported type for
//      JSON encoding: function";
//  10. a standalone $rt.MISSING, $undefined, or any other object ->
//      E8001 "unsupported type for JSON encoding".
function $encodeConvert($v, $path, $file, $line, $column) {
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
            $arr.push($element === $undefined ? null : $encodeConvert($element, $path, $file, $line, $column));
          }
          return $arr;
        }
      }
      const $obj = {};
      for (const $key of $v.keys()) {
        if (typeof $key === "string") {
          $scanStringValidity($key, $file, $line, $column);
        }
        $rt.setProp($obj, $key, $encodeConvert($v.get($key), $path, $file, $line, $column));
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
        $arr.push($element === $undefined ? null : $encodeConvert($element, $path, $file, $line, $column));
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
        $rt.setProp($obj, $key, $encodeConvert($value, $path, $file, $line, $column));
      }
      return $obj;
    } finally {
      $path.pop();
    }
  }
  // The explicit bytes arm (js-v12-int32-bytes D3): a bytes value
  // reaching the walk — via a table field, a nested array, or a class
  // instance field — raises the pinned unsupported-type needle with the
  // "bytes" kind text. $rt.isBytes is the single detection seam; the
  // runtime's $Uint8Array capture stays module-private.
  if ($rt.isBytes($v)) {
    $rt.fail("E8001", "unsupported type for JSON encoding: bytes", $file, $line, $column);
  }
  if ($type === "object" && $v.$kind === "function") {
    $rt.fail("E8001", "unsupported type for JSON encoding: function", $file, $line, $column);
  }
  $rt.fail("E8001", "unsupported type for JSON encoding", $file, $line, $column);
}

// ===== @jsonable runtime walkers (js-v12-jsonable-completion D2-D5) =====
// $rt.jsonFromJson/$rt.jsonToJson over the pinned field-descriptor
// shape — { name: string, jtype: "null"|"boolean"|"int"|"number"|
// "string"|"table"|"class"|"array", element?: entry, className?:
// string, fields?: entry[] | imported C$fields ref, optional: boolean,
// nullable: boolean, hasDefault: boolean } — entries in declaration
// order, one role per key. fromJson realizes the parent D5 phase order
// (parse -> provided-field decode in class source order -> omitted
// required defaults -> final validation -> publish) and collapses every
// failure to the DEAL null; toJson serializes the declared fields in
// declaration order and raises E8001 on failure. Both walkers mutate no
// inputs and publish nothing on failure.

// $jsonEntryDescriptor: the canonical descriptor text of one entry —
// final validation routes every present field through the canonical
// matcher rows ($rt.checkType), so the text is reconstructed exactly as
// the emitter's descriptor service would spell it: the primitive name,
// the class identity text (className), "[" + element descriptor + "]"
// for arrays, and the "?" prefix for nullable entries. An unknown jtype
// yields null and the caller fails (malformed descriptors).
function $jsonEntryDescriptor($entry) {
  let $base;
  switch ($entry.jtype) {
    case "null": $base = "null"; break;
    case "boolean": $base = "boolean"; break;
    case "int": $base = "int"; break;
    case "number": $base = "number"; break;
    case "string": $base = "string"; break;
    case "table": $base = "table"; break;
    case "class": $base = $entry.className; break;
    case "array": $base = "[" + $jsonEntryDescriptor($entry.element) + "]"; break;
    default: return null;
  }
  return $entry.nullable ? "?" + $base : $base;
}

// $jsonFromDocument: decode one class instance from an already-parsed
// JSON document (the top-level gate, the extra-key rejection, the
// provided-field decode, the omitted-required defaults, the final
// validation, and the publish — D2 steps 3-8). Never mutates the
// document. Throws on validation failure — jsonFromJson's outer catch
// collapses every throw to the DEAL null — and the defaults thunk runs
// only after every provided value decoded (parent D5: a provided-value
// failure runs no defaults). Nested class decode passes null as the
// defaults thunk: the pinned descriptor shape carries no nested defaults
// surface (className + fields only, D2), so an omitted required field of
// a nested class stays absent while absent nested optionals still
// materialize as $rt.MISSING.
function $jsonFromDocument($identity, $doc, $fields, $defaultsThunk, $file, $line, $column) {
  // Top-level gate (D3): only a JSON object may decode; a scalar, JSON
  // null, or a non-empty array returns the DEAL null. The empty object
  // and the empty array both continue — the documented parse collapse
  // ([] has no own keys, so every later step sees an empty document).
  if ($doc === null || typeof $doc !== "object") {
    return null;
  }
  if ($Array.isArray($doc) && $doc.length !== 0) {
    return null;
  }
  if (!$Array.isArray($fields)) {
    return null; // defensive: malformed descriptors never crash
  }
  // Extra-key rejection (D2 step 4): the parsed document's own keys in
  // document order; any key that is not a declared field name is a
  // rejection.
  const $names = new $Map();
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    if ($entry === null || typeof $entry !== "object" || typeof $entry.name !== "string") {
      return null;
    }
    $names.set($entry.name, true);
  }
  const $docKeys = Object.keys($doc);
  for (let $k = 0; $k < $docKeys.length; $k++) {
    if (!$names.has($docKeys[$k])) {
      return null;
    }
  }
  // Provided-field decode in class source order (the descriptor-array
  // order, not document key order — D2 step 5).
  const $provided = new $Map();
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    if (!Object.prototype.hasOwnProperty.call($doc, $entry.name)) {
      continue; // a field absent from the document is omitted
    }
    $provided.set($entry.name,
      $jsonFromValue($entry, $doc[$entry.name], $file, $line, $column));
  }
  // Omitted required defaults (D2 step 6): the thunk runs once per
  // construction, only after every provided value decoded. A thunk
  // result that is not a plain object is a defensive failure.
  let $defaults = null;
  if ($defaultsThunk !== null && $defaultsThunk !== $undefined) {
    $defaults = $defaultsThunk();
    if (!$isPlainObject($defaults)) {
      $rt.fail("E8001", "class defaults must be a table", $file, $line, $column);
    }
  }
  // Publish scaffold (D2 step 8): provided values, then the thunk's
  // defaults, then $rt.MISSING for absent optionals — all own-property
  // writes via $rt.setProp. An omitted required field without a default
  // (nested decode) stays absent.
  const $instance = {};
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    const $name = $entry.name;
    if ($provided.has($name)) {
      $rt.setProp($instance, $name, $provided.get($name));
    } else if ($defaults !== null && Object.prototype.hasOwnProperty.call($defaults, $name)) {
      $rt.setProp($instance, $name, $defaults[$name]);
    } else if ($entry.optional) {
      $rt.setProp($instance, $name, $MISSING);
    }
  }
  // Final validation (D2 step 7): every present field through the
  // canonical matcher rows — a mismatch returns null end-to-end. Absent
  // optionals (MISSING) and absent nested-required fields are skipped
  // (present-only).
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    if (!Object.prototype.hasOwnProperty.call($instance, $entry.name)) {
      continue;
    }
    const $value = $instance[$entry.name];
    if ($value === $MISSING) {
      continue;
    }
    const $descriptor = $jsonEntryDescriptor($entry);
    if ($descriptor === null) {
      $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
    }
    $rt.checkType($descriptor, $value, $file, $line, $column);
  }
  // Publish: the class tag pair on the fully validated instance.
  $rt.setProp($instance, "$kind", "class");
  $rt.setProp($instance, "$classname", $identity);
  return $instance;
}

// $jsonFromValue: decode one provided field/element value per its
// descriptor. Throws on every failure (jsonFromJson collapses throws to
// the DEAL null); a JSON null is present-null on a nullable entry or a
// null-typed entry and a shape violation everywhere else. Primitives
// route through the pinned typed-boundary checks (int range E8004,
// string surrogate E8001); class entries re-run the full instance
// decode with the nested fields; array entries decode element-wise via
// element; table entries accept only a JSON object and convert it to
// the D9 Map shape (nested arrays as the array-marked Maps).
function $jsonFromValue($entry, $raw, $file, $line, $column) {
  if ($raw === null) {
    if ($entry.nullable || $entry.jtype === "null") {
      return null;
    }
    $rt.fail("E8001", "expected non-null value", $file, $line, $column);
  }
  switch ($entry.jtype) {
    case "null":
      // Only JSON null decodes to null (handled above).
      $rt.fail("E8001", "expected null", $file, $line, $column, "null", $kindOf($raw));
    case "boolean":
      if (typeof $raw !== "boolean") {
        $rt.fail("E8001", "expected boolean", $file, $line, $column, "boolean", $kindOf($raw));
      }
      return $raw;
    case "string":
      return $rt.checkString($raw, $file, $line, $column);
    case "int":
      return $rt.checkInt($raw, $file, $line, $column);
    case "number":
      return $rt.checkNumber($raw, $file, $line, $column);
    case "table":
      if (!$isPlainObject($raw)) {
        $rt.fail("E8001", "expected table", $file, $line, $column, "table", $kindOf($raw));
      }
      return $rt.jsonParseValue($raw, $file, $line, $column);
    case "class":
      if (!$isPlainObject($raw)) {
        $rt.fail("E8001", "expected class instance", $file, $line, $column, "class", $kindOf($raw));
      }
      if (typeof $entry.className !== "string" || !$Array.isArray($entry.fields)) {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      return $jsonFromDocument($entry.className, $raw, $entry.fields, null, $file, $line, $column);
    case "array":
      if (!$Array.isArray($raw)) {
        $rt.fail("E8001", "expected array", $file, $line, $column, "array", $kindOf($raw));
      }
      if ($entry.element === null || typeof $entry.element !== "object") {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      const $arr = [];
      for (let $i = 0; $i < $raw.length; $i++) {
        $arr.push($jsonFromValue($entry.element, $raw[$i], $file, $line, $column));
      }
      return $arr;
    default:
      $rt.fail("E8001", "unknown jtype in field descriptor", $file, $line, $column);
  }
}

// $jsonToDocument: serialize the declared fields of a tagged class
// instance into a JSON-compatible object in declaration order. Absent
// optionals ($rt.MISSING) are omitted (the three-state roundtrip);
// present nulls serialize as JSON null; a missing required field is
// E8001. The shared $path stack keeps nested class/array/table graphs
// cycle-checked end to end.
function $jsonToDocument($identity, $v, $fields, $path, $file, $line, $column) {
  if (!$Array.isArray($fields)) {
    $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
  }
  const $out = {};
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    if ($entry === null || typeof $entry !== "object" || typeof $entry.name !== "string") {
      $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
    }
    const $name = $entry.name;
    if (!Object.prototype.hasOwnProperty.call($v, $name)) {
      if ($entry.optional) {
        continue; // missing optional: omit the key
      }
      $rt.fail("E8001", "missing required field '" + $name + "'", $file, $line, $column);
    }
    const $value = $v[$name];
    if ($value === $MISSING) {
      continue; // absent optional: omitted (three-state roundtrip)
    }
    $rt.setProp($out, $name, $jsonToValue($entry, $value, $path, $file, $line, $column));
  }
  return $out;
}

// $jsonToValue: encode one field/element value per its descriptor. Every
// failure raises E8001/E8004 through the fail spine with the forwarded
// location: explicit null on a non-nullable entry, primitive kind
// mismatches, NaN/±Infinity, unpaired surrogates, missing required
// fields, identity mismatches, cycles, non-JSON-shaped table graphs, and
// stored function values (the D9 needles).
function $jsonToValue($entry, $value, $path, $file, $line, $column) {
  if ($value === null) {
    if ($entry.nullable || $entry.jtype === "null") {
      return null;
    }
    const $what = typeof $entry.name === "string" ? "field '" + $entry.name + "'" : "array element";
    $rt.fail("E8001", "explicit null on non-nullable " + $what, $file, $line, $column);
  }
  switch ($entry.jtype) {
    case "null":
      $rt.fail("E8001", "expected null", $file, $line, $column, "null", $kindOf($value));
    case "boolean":
      if (typeof $value !== "boolean") {
        $rt.fail("E8001", "expected boolean", $file, $line, $column, "boolean", $kindOf($value));
      }
      return $value;
    case "string":
      return $rt.checkString($value, $file, $line, $column);
    case "int":
      return $rt.checkInt($value, $file, $line, $column);
    case "number":
      if (typeof $value !== "number") {
        $rt.fail("E8001", "expected number", $file, $line, $column, "number", $kindOf($value));
      }
      if ($value !== $value) {
        $rt.fail("E8001", "cannot encode NaN as JSON", $file, $line, $column);
      }
      if ($value > 1.7976931348623157e308 || $value < -1.7976931348623157e308) {
        $rt.fail("E8001", "cannot encode Infinity as JSON", $file, $line, $column);
      }
      return $value;
    case "table":
      return $rt.jsonEncodeValue($value, $path, $file, $line, $column);
    case "class":
      if (typeof $value !== "object" || $value === null || $value.$kind !== "class") {
        $rt.fail("E8001", "expected class instance", $file, $line, $column, "class", $kindOf($value));
      }
      if (typeof $entry.className !== "string" || !$Array.isArray($entry.fields)) {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      if ($value.$classname !== $entry.className) {
        $rt.fail("E8001", "expected instance of " + $entry.className + ", got " + $value.$classname, $file, $line, $column, $entry.className, $value.$classname);
      }
      if ($path.includes($value)) {
        $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
      }
      $path.push($value);
      try {
        return $jsonToDocument($entry.className, $value, $entry.fields, $path, $file, $line, $column);
      } finally {
        $path.pop();
      }
    case "array":
      if (!$Array.isArray($value)) {
        $rt.fail("E8001", "expected array", $file, $line, $column, "array", $kindOf($value));
      }
      if ($entry.element === null || typeof $entry.element !== "object") {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      if ($path.includes($value)) {
        $rt.fail("E8001", "circular reference in JSON encoding", $file, $line, $column);
      }
      $path.push($value);
      try {
        const $arr = [];
        for (let $i = 0; $i < $value.length; $i++) {
          const $element = $value[$i];
          $arr.push($element === $undefined ? null : $jsonToValue($entry.element, $element, $path, $file, $line, $column));
        }
        return $arr;
      } finally {
        $path.pop();
      }
    default:
      $rt.fail("E8001", "unknown jtype in field descriptor", $file, $line, $column);
  }
}

// ===== Host ABI (js-v12-host-abi-completion D1-D5) =====

// $asyncReturnDescriptor: the declared return text of an async function
// descriptor — the slice after the outer "->" arrow, located as the
// first arrow at parameter-paren depth 0 (a nested function descriptor
// inside the parameter list carries its arrow at depth >= 1; the
// canonical grammar forbids a contiguous "->" inside class-atom
// components, and $parse validated the full descriptor first). Returns
// the declared R text; any other input yields null (defensive — only
// emitter-rendered declared maps reach the callers, which raise
// E8010/E8011 on the parse/kind arms before this helper matters).
function $asyncReturnDescriptor($descriptor) {
  let $depth = 0;
  for (let $i = 0; $i < $descriptor.length; $i++) {
    const $c = $descriptor.charCodeAt($i);
    if ($c === 0x28) { // '('
      $depth++;
    } else if ($c === 0x29) { // ')'
      $depth--;
    } else if ($c === 0x2D && $i + 1 < $descriptor.length
        && $descriptor.charCodeAt($i + 1) === 0x3E && $depth === 0) {
      return $descriptor.slice($i + 2);
    }
  }
  return null;
}

const $rt = {
  MISSING: $MISSING,
  NULL: null,
  undefined: $undefined,

  isNilEquivalent: function $isNilEquivalent(v) {
    return v === $undefined || v === null || v === $MISSING;
  },

  // Own-property write via Object.defineProperty: never invokes the
  // inherited Object.prototype.__proto__ accessor. Returns target for
  // chaining (js-backend-runtime-artifact D2).
  setProp: function $setProp(target, key, v) {
    Object.defineProperty(target, key, {
      value: v,
      writable: true,
      enumerable: true,
      configurable: true,
    });
    return target;
  },
  // makeTable: the coordinated additive table-construction member
  // (js-backend-emitter D9) — the single addition to the ISSUE-0190
  // 41-member surface. Returns a fresh Map built through the
  // module-private $Map capture (generated code never spells the bare Map
  // global) carrying the own enumerable string keys of the plain-object
  // entries in own-key enumeration order; values are stored as-is — each
  // value already crossed its own expression-site boundary (the makeClass
  // step-2 precedent, js-backend-runtime D5). A non-plain-object entries
  // argument raises the defensive E8001; only emitter mis-emission
  // reaches it (js-backend-runtime-artifact A3). No mutation of the
  // entries object; the Map is independent; no shared state.
  makeTable: function $makeTable(entries) {
    if (!$isPlainObject(entries)) {
      $rt.fail("E8001", "table entries must be a table");
    }
    const $map = new $Map();
    const $keys = Object.keys(entries);
    for (let $i = 0; $i < $keys.length; $i++) {
      const $key = $keys[$i];
      $map.set($key, entries[$key]);
    }
    return $map;
  },

  // The mark members require a Map argument: WeakSet.add on a primitive
  // input throws the native TypeError — the documented defensive exception
  // of the JSON-mark contract, reachable only through std/json mis-emission
  // (js-backend-runtime-artifact D2). No DEALError arm exists here.
  markJsonArrayTable: function $markJsonArrayTable(map) {
    $jsonArrayTables.add(map);
    return map;
  },

  markJsonNullTable: function $markJsonNullTable(map) {
    $jsonNullTables.add(map);
    return map;
  },

  // The is-members are total: WeakSet.has never throws and returns false
  // for primitives and non-members (js-backend-runtime-artifact D2).
  isJsonArrayTable: function $isJsonArrayTable(v) {
    return $jsonArrayTables.has(v);
  },

  isJsonNullTable: function $isJsonNullTable(v) {
    return $jsonNullTables.has(v);
  },

  // ===== D9 JSON conversion members (js-backend-runtime D9) =====

  // jsonParseValue: the decode-side conversion of one native
  // $JSON.parse result into the marked-Map table shape — the shared
  // walk behind std/json.parse and the @jsonable walkers' table-field
  // decode (js-v12-jsonable-completion D2/D4). Pure: fresh Maps only,
  // no input mutation.
  jsonParseValue: function $jsonParseValue(v, file, line, column) {
    return $fromJsonConvert(v, file, line, column);
  },

  // jsonEncodeValue: the encode-side validation/conversion walk with
  // the shared cycle-check path — the walk behind std/json.stringify
  // and the @jsonable walkers' table-field encode (D2/D4). Raises
  // E8001 for NaN/±Infinity, unpaired surrogates, cyclic graphs,
  // function values, and any other non-JSON-shaped value. Pure: fresh
  // objects/arrays only, no input mutation.
  jsonEncodeValue: function $jsonEncodeValue(v, path, file, line, column) {
    return $encodeConvert(v, path, file, line, column);
  },

  // ===== Error spine members (js-backend-runtime-artifact D8) =====

  // fail: the single constructor path — every check and arithmetic error
  // is produced through it (D3/D5). Throws a $DEALError carrying code,
  // $dealCode, message (via super), and the defined location/expected/
  // actual fields (the _err absent-field convention, deal/runtime.lua:19-32).
  fail: function $fail(code, message, file, line, column, expected, actual) {
    throw new $DEALError(code, message, file, line, column, expected, actual);
  },

  // errorValue: the tagged builtin-Error class instance a catch (e)
  // binding receives — the mirror of error_value (deal/runtime.lua:36-48,
  // runtime-class-identity D3). A plain object (prototype Object.prototype)
  // whose own properties are written via $rt.setProp; only defined fields
  // materialize, so errorValue('E','x') has exactly the four own
  // properties $kind/$classname/code/message and the seven-field form adds
  // exactly file/line/column.
  errorValue: function $errorValue(code, message, file, line, column) {
    const $value = {};
    $rt.setProp($value, "$kind", "class");
    $rt.setProp($value, "$classname", "@$builtin/Error");
    $rt.setProp($value, "code", code);
    $rt.setProp($value, "message", message);
    if (file !== $undefined) $rt.setProp($value, "file", file);
    if (line !== $undefined) $rt.setProp($value, "line", line);
    if (column !== $undefined) $rt.setProp($value, "column", column);
    return $value;
  },

  // reifyError: total conversion of a thrown value into a tagged Error
  // value. (i) $DEALError -> rebuilt via errorValue (a fresh tagged Error
  // value with the same code/message); (ii) an already-tagged Error
  // instance passes through unchanged, same reference (rethrow
  // preservation, js-backend-runtime D7); (iii) anything else -> the
  // E8001 wrap errorValue("E8001", String(v)). Only fail throws; this
  // member is total.
  reifyError: function $reifyError(v) {
    if (v instanceof $DEALError) {
      return $rt.errorValue(v.code, v.message, v.file, v.line, v.column);
    }
    if (typeof v === "object" && v !== null && v.$kind === "class" && v.$classname === "@$builtin/Error") {
      return v;
    }
    return $rt.errorValue("E8001", String(v));
  },

  // reportUncaught: the entry-shim contract (js-backend-architecture D7) —
  // reify, then write exactly the line "DEAL_ERROR_CODE: <code> <message>"
  // plus a trailing newline to the captured stderr and set the captured
  // exitCode to 1. All host-global access goes through the T1 captures.
  reportUncaught: function $reportUncaught(e) {
    const $err = $rt.reifyError(e);
    $process.stderr.write("DEAL_ERROR_CODE: " + $err.code + " " + $err.message + "\n");
    $process.exitCode = 1;
  },

  // ===== Primitive typed-boundary checks (js-backend-runtime-artifact D3) =====
  // Every check accepts trailing (file, line, column) arguments and throws
  // every error through fail (the D8 spine), so the DEALError carries the
  // exact message, the expected/actual pair where the contract defines
  // one, and the passed location fields (absent when undefined, the _err
  // convention, deal/runtime.lua:19-32). Success returns the validated
  // value — identity except checkInt's -0 normalization. Checks are total
  // and mutate no input.

  // checkNull: DEAL null is only JS null — undefined and MISSING are
  // rejected (deal/runtime.lua:50-55).
  checkNull: function $checkNull(v, file, line, column) {
    if (v !== null) {
      $rt.fail("E8001", "expected null", file, line, column, "null", $kindOf(v));
    }
    return v;
  },

  checkBoolean: function $checkBoolean(v, file, line, column) {
    if (typeof v !== "boolean") {
      $rt.fail("E8001", "expected boolean", file, line, column, "boolean", $kindOf(v));
    }
    return v;
  },

  // setInt32Mode: the profile selector (js-v12-int32-bytes D2). Every
  // module emitted under the DEAL_V1_2_INT32 profile calls it with true
  // immediately after the runtime $require (the emitter's module-shape
  // step 3); the call is idempotent and one-way — an argument other than
  // true leaves the flag unchanged, and no call reverts an activated
  // flag. LEGACY_SAFE_INT emission never calls it.
  setInt32Mode: function $setInt32Mode(enabled) {
    if (enabled === true) {
      $int32 = true;
    }
  },

  // checkInt: the exact check_int order (deal/runtime.lua:64-83) — the
  // ±Infinity E8001 arm fires before the E8004 finite-range arm, so intPow
  // overflow to Infinity is E8001 and only finite values outside the
  // profile range are E8004 (js-v12-int32-bytes D1/D2: the final arm
  // consults the module-private $int32 flag — ±2147483648 under the
  // int32 profile, ±(2^53-1) under LEGACY_SAFE_INT — with the same E8004
  // code and "int out of safe range" message). -0 normalizes to 0 (the
  // v = v + 0 step, :72). NaN/±Infinity/non-integer carry expected "int"
  // exactly like the reference _err calls; the E8004 range arm carries no
  // expected/actual (nil in the reference — fields absent, the _err
  // convention).
  checkInt: function $checkInt(v, file, line, column) {
    if (typeof v !== "number") {
      $rt.fail("E8001", "expected int", file, line, column, "int", $kindOf(v));
    }
    if (Number.isNaN(v)) {
      $rt.fail("E8001", "expected int, got NaN", file, line, column, "int", "NaN");
    }
    v = v + 0; // normalize -0 to 0
    if (v === Infinity || v === -Infinity) {
      $rt.fail("E8001", "expected int, got infinity", file, line, column, "int", "infinity");
    }
    if (v % 1 !== 0) {
      $rt.fail("E8001", "expected int, got non-integer number", file, line, column, "int", "number");
    }
    if ($int32
        ? (v < -2147483648 || v > 2147483647)
        : (v < -9007199254740991 || v > 9007199254740991)) {
      $rt.fail("E8004", "int out of safe range", file, line, column);
    }
    return v;
  },

  // checkNumber: NaN and ±Infinity pass (spec §Numeric overflow).
  checkNumber: function $checkNumber(v, file, line, column) {
    if (typeof v !== "number") {
      $rt.fail("E8001", "expected number", file, line, column, "number", $kindOf(v));
    }
    return v;
  },

  // checkString: boundary validation rejects unpaired UTF-16 surrogates —
  // a high surrogate (U+D800..U+DBFF) not followed by a low surrogate
  // (U+DC00..U+DFFF), or a lone low surrogate — with the reference's
  // boundary message (deal/runtime.lua:204-215). Valid supplementary pairs
  // pass; the string is returned unchanged.
  checkString: function $checkString(v, file, line, column) {
    if (typeof v !== "string") {
      $rt.fail("E8001", "expected string", file, line, column, "string", $kindOf(v));
    }
    for (let $i = 0; $i < v.length; $i++) {
      const $c = v.charCodeAt($i);
      if ($c >= 0xd800 && $c <= 0xdbff) {
        const $next = $i + 1 < v.length ? v.charCodeAt($i + 1) : -1;
        if ($next < 0xdc00 || $next > 0xdfff) {
          $rt.fail("E8001", "expected string, got invalid UTF-8 encoding", file, line, column, "string", "invalid UTF-8 string");
        }
        $i++; // skip the low surrogate of a valid pair
      } else if ($c >= 0xdc00 && $c <= 0xdfff) {
        $rt.fail("E8001", "expected string, got invalid UTF-8 encoding", file, line, column, "string", "invalid UTF-8 string");
      }
    }
    return v;
  },

  // checkTable: the strict split (js-backend-runtime D3) — instanceof Map
  // passes (the T1-marked JSON Maps included, D2); anything else — Array,
  // plain object, class instance, wrapper, primitives — is rejected.
  checkTable: function $checkTable(v, file, line, column) {
    if (!(v instanceof $Map)) {
      $rt.fail("E8001", "expected table", file, line, column, "table", $kindOf(v));
    }
    return v;
  },

  // ===== Descriptor dispatch (js-backend-runtime-artifact D3/D4) =====

  // checkNullable: the check_nullable mirror (deal/runtime.lua:229-235,
  // extended to MISSING per js-backend-runtime D3) — any nil-equivalent
  // input (undefined, null, MISSING — the isNilEquivalent predicate)
  // returns DEAL null (JS null) with no error; everything else delegates
  // to checkType on the inner descriptor.
  checkNullable: function $checkNullable(inner, v, file, line, column) {
    if ($rt.isNilEquivalent(v)) {
      return null;
    }
    return $rt.checkType(inner, v, file, line, column);
  },

  // checkType: canonical descriptor parse ($parse) then dispatch — the
  // check_type mirror (deal/runtime.lua:417-476) realized over the
  // canonical grammar only (js-v12-completion-architecture D3). Defensive
  // arms: the strict === null predicate routes only JS null to the nil
  // arm (DEAL null is only JS null, D3); undefined and every other
  // non-string descriptor fail the parse step and take the cannot-parse
  // arm (String(descriptor) mirrors Lua's tostring(descriptor)). Legacy
  // spellings ("T[]", "T|null", bare class names, the bare "Error" atom)
  // are unparsable text and take the pinned E8001
  // "internal: cannot parse type descriptor: {text}" arm.
  checkType: function $checkType(descriptor, v, file, line, column) {
    if (descriptor === null) {
      $rt.fail("E8001", "internal: nil type descriptor", file, line, column);
    }
    const $parsed = $parse(descriptor);
    if ($parsed === null) {
      $rt.fail("E8001", "internal: cannot parse type descriptor: " + String(descriptor), file, line, column);
    }
    if ($parsed.$t === "primitive") {
      if ($parsed.$name === "null") {
        return $rt.checkNull(v, file, line, column);
      }
      if ($parsed.$name === "boolean") {
        return $rt.checkBoolean(v, file, line, column);
      }
      if ($parsed.$name === "int") {
        return $rt.checkInt(v, file, line, column);
      }
      if ($parsed.$name === "number") {
        return $rt.checkNumber(v, file, line, column);
      }
      if ($parsed.$name === "string") {
        return $rt.checkString(v, file, line, column);
      }
      if ($parsed.$name === "table") {
        return $rt.checkTable(v, file, line, column);
      }
      // bytes: the matcher table's bytes row (js-v12-int32-bytes D3) —
      // $rt.checkBytes accepts the runtime-captured $Uint8Array carrier
      // and rejects every other value with E8001 "expected bytes"
      // (expected "bytes", actual $kindOf(v)). The retired defensive
      // E6000 gate no longer exists.
      if ($parsed.$name === "bytes") {
        return $rt.checkBytes(v, file, line, column);
      }
      $rt.fail("E8001", "unknown primitive type: " + $parsed.$name, file, line, column);
    }
    if ($parsed.$t === "nullable") {
      return $rt.checkNullable($parsed.$inner, v, file, line, column);
    }
    if ($parsed.$t === "array") {
      return $rt.checkArray(descriptor, v, file, line, column);
    }
    if ($parsed.$t === "function") {
      // Function branch: the wrapper $kind tag first, then the exact
      // signature compare against the full descriptor string
      // (deal/runtime.lua:450-456). The null/undefined guard keeps the
      // $kind read total (no raw TypeError from a check, D3).
      if (v === $undefined || v === null || v.$kind !== "function") {
        $rt.fail("E8001", "expected function", file, line, column, "function", $kindOf(v));
      }
      if (v.$sig !== descriptor) {
        $rt.fail("E8010", "function signature mismatch: expected " + descriptor + ", got " + v.$sig, file, line, column, descriptor, v.$sig);
      }
      return v;
    }
    if ($parsed.$t === "class") {
      // Class branch: the $kind tag, then nominal identity — byte-for-byte
      // equality between $classname and the parsed class atom's full text
      // (deal/runtime.lua:459-468; canonical-type-system-and-runtime-descriptors
      // D4). The builtin Error atom is @$builtin/Error.
      if (v === $undefined || v === null || v.$kind !== "class") {
        $rt.fail("E8001", "expected class instance", file, line, column, "class", $kindOf(v));
      }
      if (v.$classname !== $parsed.$name) {
        $rt.fail("E8001", "expected instance of " + $parsed.$name + ", got " + v.$classname, file, line, column, $parsed.$name, v.$classname);
      }
      return v;
    }
    // Defensive: the parser is total over strings and every record kind is
    // dispatched above, so no parsed record reaches this arm (A3).
    $rt.fail("E8001", "internal: unhandled descriptor kind: " + $parsed.$t, file, line, column);
  },

  // checkArray: the strict array split (D3) — Array.isArray via the T1
  // $Array capture (never the bare spelling); a Map, class instance, or
  // primitive is rejected with "expected array". Element extraction
  // realizes the canonical "[D]" prefix form only
  // ($arrayElementDescriptor); the legacy "T[]" suffix was retired with
  // the dialect. The element walk is 0-based,
  // mirroring the reference's 1..#v walk (deal/runtime.lua:256-285) with
  // the 1-based message index; any element failure is wrapped in E8003
  // with the inner DEALError's message embedded (the deliberate
  // stabilization of the reference's tostring(err) of a Lua table —
  // non-portable address text). Success returns the array unchanged.
  checkArray: function $checkArray(descriptor, v, file, line, column) {
    if (!$Array.isArray(v)) {
      $rt.fail("E8001", "expected array", file, line, column, "array", $kindOf(v));
    }
    const $element = $arrayElementDescriptor(descriptor, file, line, column);
    for (let $i = 0; $i < v.length; $i++) {
      try {
        $rt.checkType($element, v[$i], file, line, column);
      } catch ($e) {
        const $innerMessage = $e instanceof $DEALError ? $e.message : String($e);
        $rt.fail("E8003", "array element " + ($i + 1) + " type mismatch: " + $innerMessage, file, line, column, $element, $kindOf(v[$i]));
      }
    }
    return v;
  },

  // checkFunctionSig: the named surface member the emitter calls where the
  // value's signature is read directly (D3) — a mismatch raises E8010 with
  // the two signature strings as expected/actual; a match returns v
  // (identity, no mutation).
  checkFunctionSig: function $checkFunctionSig(expectedSig, actualSig, v, file, line, column) {
    if (expectedSig !== actualSig) {
      $rt.fail("E8010", "function signature mismatch: expected " + expectedSig + ", got " + actualSig, file, line, column, expectedSig, actualSig);
    }
    return v;
  },

  // ===== Int arithmetic and the floored remainder =====
  // (js-backend-runtime-artifact D5; js-backend-runtime D4 — the verbatim
  // mirror of deal/runtime.lua:478-514) Every int result flows through
  // checkInt (the D3 contract): -0 normalizes to 0, overflow to ±Infinity
  // is E8001 "expected int, got infinity" before the E8004 finite-range
  // arm, and finite values outside the profile range are E8004
  // (±2147483648 under $int32, ±(2^53-1) under LEGACY_SAFE_INT —
  // js-v12-int32-bytes D1/D2). intDiv/intMod raise E8005 on a zero
  // divisor before any division; intPow raises
  // E8006 on a negative exponent. The truncation operations use the T1
  // $Math capture — $Math.trunc is the math.modf truncation-toward-zero
  // analog and $Math.floor is the floored-remainder divisor step — and
  // every error is produced through the T2 spine (fail) with the caller's
  // (file, line, column) forwarded. numMod is the Lua floored remainder
  // for number % number with NO check — formula-defined and total
  // (numMod(x, 0) is NaN, no throw). number / number stays native IEEE
  // division in the emitter and has no runtime member. Pure functions;
  // no mutation.

  intAdd: function $intAdd(a, b, file, line, column) {
    return $rt.checkInt(a + b, file, line, column);
  },

  intSub: function $intSub(a, b, file, line, column) {
    return $rt.checkInt(a - b, file, line, column);
  },

  intMul: function $intMul(a, b, file, line, column) {
    return $rt.checkInt(a * b, file, line, column);
  },

  intNeg: function $intNeg(a, file, line, column) {
    return $rt.checkInt(-a, file, line, column);
  },

  // intDiv: E8005 on a zero divisor first (no expected/actual, mirroring
  // the reference _err(nil, nil) pair), then checkInt of the
  // truncation-toward-zero quotient (deal/runtime.lua:490-494).
  intDiv: function $intDiv(a, b, file, line, column) {
    if (b === 0) {
      $rt.fail("E8005", "integer division by zero", file, line, column);
    }
    return $rt.checkInt($Math.trunc(a / b), file, line, column);
  },

  // intMod: E8005 on a zero divisor first, then the reference's
  // truncating-quotient gate (deal/runtime.lua:497-501): MIN_VALUE % -1
  // raises E8004 because the truncated quotient (2147483648) leaves the
  // int32 range, even though the mathematical remainder (0) is
  // representable. The gate never fires under LEGACY_SAFE_INT: |q| ≤ |a|
  // for every nonzero integer divisor, so a checked operand keeps q
  // inside the profile range. The remainder a - q * b then passes
  // through checkInt.
  intMod: function $intMod(a, b, file, line, column) {
    if (b === 0) {
      $rt.fail("E8005", "integer division by zero", file, line, column);
    }
    const $q = $Math.trunc(a / b);
    $rt.checkInt($q, file, line, column);
    return $rt.checkInt(a - $q * b, file, line, column);
  },

  // intPow: E8006 on a negative exponent, then checkInt of a ** b — so
  // overflow to ±Infinity is E8001 via the checkInt order and a finite
  // out-of-range result is E8004 (deal/runtime.lua:504-509).
  intPow: function $intPow(a, b, file, line, column) {
    if (b < 0) {
      $rt.fail("E8006", "integer exponent must be non-negative", file, line, column);
    }
    return $rt.checkInt(a ** b, file, line, column);
  },

  // numMod: a - floor(a / b) * b — the Lua floored remainder for
  // number % number (native JS % is truncated, so it diverges for mixed
  // signs; the JVM precedent deal/codegen/jvm/JvmBackend.java:3382).
  // No check: IEEE arithmetic on two numbers is total.
  numMod: function $numMod(a, b) {
    return a - $Math.floor(a / b) * b;
  },

  // ===== Bytes carrier and the five helpers (js-v12-int32-bytes D3/D4) =====
  // Bytes on JS are the runtime-captured $Uint8Array — contiguous
  // uint8_t-equivalent owned storage with unsigned reads, fresh
  // zero-filled, reference-aliased on assignment/parameters/returns/
  // fields/arrays, identity-compared, and non-jsonable (D3). The five
  // helper signatures below are the D4 contract; every error goes
  // through the fail spine with the caller's (file, line, column)
  // forwarded. E8012 denotes a negative or above-int32-max allocation
  // length (bytes) or a byte-index bounds failure (bytesGet/bytesSet —
  // bytes never append); E8013 denotes a write value outside 0..255.
  // A failed validation changes no storage; a successful write mutates
  // exactly one byte and returns the written unsigned value.

  // checkBytes: fail-closed bytes object validation — the matcher
  // table's bytes row (D3): v instanceof $Uint8Array passes, everything
  // else raises E8001 "expected bytes" (expected "bytes", actual
  // $kindOf(v)). Success returns v (identity, no mutation).
  checkBytes: function $checkBytes(v, file, line, column) {
    if (!(v instanceof $Uint8Array)) {
      $rt.fail("E8001", "expected bytes", file, line, column, "bytes", $kindOf(v));
    }
    return v;
  },

  // bytes: the intrinsic allocation site (D3). checkInt(length) runs the
  // full int contract (E8001 kind/NaN/infinity/non-integer, E8004
  // profile range); a negative length or a length above 2147483647 (the
  // signed-int32 logical-length bound) raises E8012; otherwise a fresh
  // zero-filled new $Uint8Array(length) is returned. A native allocation
  // failure (RangeError) propagates as an infrastructure failure and is
  // NEVER mislabeled E8012.
  bytes: function $bytes(length, file, line, column) {
    const $n = $rt.checkInt(length, file, line, column);
    if ($n < 0) {
      $rt.fail("E8012", "bytes length must be non-negative", file, line, column);
    }
    if ($n > 2147483647) {
      $rt.fail("E8012", "bytes length out of bounds", file, line, column);
    }
    return new $Uint8Array($n);
  },

  // bytesLength: the immutable logical allocation length — the
  // compiler-resolved b.length read (D3/D4): checkBytes then the native
  // Uint8Array length (never a member lookup, never dispatchable — the
  // emitter calls this member directly with the static int type).
  bytesLength: function $bytesLength(b, file, line, column) {
    $rt.checkBytes(b, file, line, column);
    return b.length;
  },

  // bytesGet: read the unsigned byte (int 0..255) at index i,
  // 0 <= i < b.length (D4). checkBytes, then checkInt(i), then the
  // bounds gate — i < 0 || i >= b.length raises E8012 (bytes never
  // append); the returned element is the unsigned read.
  bytesGet: function $bytesGet(b, i, file, line, column) {
    $rt.checkBytes(b, file, line, column);
    const $idx = $rt.checkInt(i, file, line, column);
    if ($idx < 0 || $idx >= b.length) {
      $rt.fail("E8012", "bytes index out of bounds", file, line, column);
    }
    return b[$idx];
  },

  // bytesSet: write the byte value v (int 0..255) at index i and return
  // the written unsigned value (D4). checkBytes, then checkInt(i) with
  // the same bounds gate E8012, then checkInt(v), then the value gate —
  // v < 0 || v > 255 raises E8013. A failed validation changes no
  // storage; a successful write mutates exactly one byte.
  bytesSet: function $bytesSet(b, i, v, file, line, column) {
    $rt.checkBytes(b, file, line, column);
    const $idx = $rt.checkInt(i, file, line, column);
    if ($idx < 0 || $idx >= b.length) {
      $rt.fail("E8012", "bytes index out of bounds", file, line, column);
    }
    const $val = $rt.checkInt(v, file, line, column);
    if ($val < 0 || $val > 255) {
      $rt.fail("E8013", "bytes value out of range", file, line, column);
    }
    b[$idx] = $val;
    return $val;
  },

  // isBytes: the boolean bytes predicate — total, pure, mutates
  // nothing. The single detection seam std/json's explicit bytes
  // rejection arm consults (js-v12-int32-bytes D3: a bytes value
  // reaching std/json.stringify raises E8001 through the unsupported
  // type arm); no other member exposes the carrier constructor.
  isBytes: function $isBytes(v) {
    return v instanceof $Uint8Array;
  },

  // ===== Class construction (js-backend-runtime-artifact D6) =====

  // makeClass: the mirror of class_ (deal/runtime.lua:859-894) in the JS
  // representation — a per-construction defaults thunk replaces the Lua
  // deep copy, and absent optional fields store $MISSING instead of being
  // removed (the DEAL-observable three-state is identical via optRead/has).
  //
  // Step 1: evaluate defaultsThunk() on every construction (fresh mutable
  // defaults, re-executed call-valued defaults, spec §Construction). The
  // defensive mirror of class_'s table checks (deal/runtime.lua:861-870):
  // a non-plain-object thunk result fails with E8001 "class defaults must
  // be a table"; a null/undefined provided argument is treated as absent
  // and never errors (Lua's provided ~= nil guard, deal/runtime.lua:868);
  // any other non-plain-object provided value fails with E8001 "class
  // field values must be a table". Both arms are defensive — only emitter
  // mis-emission reaches them (A3) — and raise through fail.
  //
  // Step 2: overlay provided own keys in source order. A provided key that
  // is not an own key of the defaults object fails with E8007
  // "extra field '<key>' in class '<className>'" — the class_ arm at
  // deal/runtime.lua:875-879, naming the className argument. Present keys
  // write via $rt.setProp.
  //
  // Step 3: for every remaining declared field (own keys of defaults,
  // declaration order) write defaults[key] via $rt.setProp — required
  // fields get their fresh default, absent optionals get $MISSING. Every
  // declared field materializes as an own property, a field named
  // __proto__ included (setProp never invokes the inherited accessor), so
  // field reads never resolve to Object.prototype. No field value
  // type-checks run here — each provided value already crossed its own
  // expression-site boundary (class_'s contract, deal/runtime.lua:859-886).
  //
  // Step 4: tag via $rt.setProp(instance, "$kind", "class") and
  // $rt.setProp(instance, "$classname", identity) — the pair class_ step 4
  // sets on every instance (deal/runtime.lua:889-890) — and return the
  // instance. Each instance is independent of the defaults template and of
  // every other instance; no user name can mutate the instance's prototype.
  makeClass: function $makeClass(className, identity, defaultsThunk, provided, file, line, column) {
    const $defaults = defaultsThunk();
    if (!$isPlainObject($defaults)) {
      $rt.fail("E8001", "class defaults must be a table", file, line, column);
    }
    const $instance = {};
    if (provided !== null && provided !== $undefined) {
      if (!$isPlainObject(provided)) {
        $rt.fail("E8001", "class field values must be a table", file, line, column);
      }
      const $providedKeys = Object.keys(provided);
      for (let $i = 0; $i < $providedKeys.length; $i++) {
        const $key = $providedKeys[$i];
        if (!Object.prototype.hasOwnProperty.call($defaults, $key)) {
          $rt.fail("E8007", "extra field '" + $key + "' in class '" + className + "'", file, line, column);
        }
        $rt.setProp($instance, $key, provided[$key]);
      }
    }
    const $declaredKeys = Object.keys($defaults);
    for (let $j = 0; $j < $declaredKeys.length; $j++) {
      const $key = $declaredKeys[$j];
      if (provided === null || provided === $undefined || !Object.prototype.hasOwnProperty.call(provided, $key)) {
        $rt.setProp($instance, $key, $defaults[$key]);
      }
    }
    $rt.setProp($instance, "$kind", "class");
    $rt.setProp($instance, "$classname", identity);
    return $instance;
  },

  // optRead: optional-field reads map MISSING -> null; every other value —
  // a present null included — passes through unchanged (D6).
  optRead: function $optRead(v) {
    return v === $MISSING ? null : v;
  },

  // has: field presence for the has() intrinsic, checker-restricted to
  // optional class fields (deal/checker/TypeChecker.java:1401-1441). An
  // own-property read on a class instance whose every declared field is
  // materialized (makeClass step 3): MISSING -> false; any present value
  // including null -> true (the spec three-state). The Map branch is
  // deliberately absent.
  has: function $has(obj, field) {
    return obj[field] !== $MISSING;
  },

  // ===== @jsonable runtime walkers (js-v12-jsonable-completion D2-D5) =====

  // jsonFromJson: decode a JSON document string into a tagged class
  // instance over the pinned field-descriptor shape (D2) with the
  // parent D5 phase order — parse -> provided-field decode in class
  // source order -> omitted required defaults (the thunk, once per
  // construction, only after every provided value decoded) -> final
  // validation through the canonical matcher rows -> publish. The
  // top-level gate (D3): a scalar, JSON null, or non-empty array
  // document returns the DEAL null; the empty object and the empty
  // array both decode to the defaulted instance with identical
  // jsonToJson text. Every failure — malformed input, unpaired
  // surrogates, extra keys, provided-value failures, evaluator
  // failures, final-validation mismatches — returns the DEAL null and
  // publishes no instance; the walker never throws (the public
  // C$fromJson contract, D5). No input is ever mutated.
  jsonFromJson: function $jsonFromJson(identity, fields, defaultsThunk, s, file, line, column) {
    try {
      $rt.checkString(s, file, line, column);
    } catch ($e) {
      return null;
    }
    let $doc;
    try {
      $doc = $JSON.parse(s);
    } catch ($e) {
      return null;
    }
    try {
      return $jsonFromDocument(identity, $doc, fields, defaultsThunk, file, line, column);
    } catch ($e) {
      return null;
    }
  },

  // jsonToJson: serialize a tagged class instance into a JSON string
  // over the pinned field-descriptor shape — declared fields in
  // declaration order, MISSING-valued absent optionals omitted,
  // present nulls as JSON null, nested classes/arrays recursive, table
  // fields through the D9 conversion with the finite-acyclic-JSON-
  // shaped validation. The defensive identity check compares the
  // $kind/$classname tag pair against the identity argument (the
  // generated C$toJson wrapper pre-validates the parameter; this is
  // the backstop — D2). Every failure raises E8001 through the fail
  // spine with the forwarded location (D5); no input is ever mutated.
  jsonToJson: function $jsonToJson(identity, v, fields, file, line, column) {
    if (v === $undefined || v === null || typeof v !== "object" || v.$kind !== "class") {
      $rt.fail("E8001", "expected class instance", file, line, column, "class", $kindOf(v));
    }
    if (v.$classname !== identity) {
      $rt.fail("E8001", "expected instance of " + identity + ", got " + v.$classname, file, line, column, identity, v.$classname);
    }
    const $path = [v];
    return $JSON.stringify($jsonToDocument(identity, v, fields, $path, file, line, column));
  },

  // ===== Wrapper factory and conversion intrinsics
  // (js-backend-runtime-artifact D7) =====

  // function: the canonical wrapper factory — a plain object (prototype
  // Object.prototype) with exactly the three $-keyed own properties
  // $kind: "function", $sig: sig (stored verbatim — signature comparison
  // happens in checkType/checkFunctionSig, which compare the string
  // exactly), and $f: f (the exact function reference — never re-wrapped,
  // identity-preserving). The wrapper is the passive shape: function
  // performs no checks itself; the parameter/return checks live in the
  // emitted wrapper bodies (js-backend-runtime D6). All $-keys, plain
  // syntax; no shared wrapper state; the factory is pure
  // (deal/runtime.lua:516-519).
  function: function $function(sig, f) {
    return { $kind: "function", $sig: sig, $f: f };
  },
  // ===== Host ABI (js-v12-host-abi-completion D1-D5) =====

  // hostFunction: the checked wrapper for one declared sync host
  // function export (D2) — the JS mirror of from_lua_function's
  // call-time enforcement (deal/runtime.lua:819-949). The returned
  // surface is a passive DEAL wrapper { $kind: "function", $sig:
  // descriptor, $f } whose $f realizes the host-call boundary
  // (common-semantic-lowering-layer D13: the host-call boundary owns
  // the parameter and return checks):
  //
  // 1. The trailing (file, line, column) span triple the emitted call
  //    shape appends is split off; a direct call without it is
  //    defensive misuse and runs the checks with an absent span.
  // 2. Exact arity — v1.2 has no rest parameters: E8010 "expected at
  //    least N arguments, got M" / "expected N arguments, got M" (the
  //    reference's arms).
  // 3. Each parameter checks against the declared parameter descriptor
  //    through the canonical matcher rows (checkType); every failure —
  //    wrong kind, array carrier/element mismatches, function $sig
  //    deltas, class nominal identity, E8004 int range, unpaired
  //    string surrogates — re-raises E8010 "parameter {i} type
  //    mismatch: {inner}" with the declared descriptor as expected and
  //    $kindOf(arg) as actual (the reference's pcall composition).
  // 4. f is invoked exactly once with the user arguments; a
  //    function-typed argument is the checked DEAL wrapper delivered
  //    unchanged (DEAL→host adaptation, D2 — the host invokes through
  //    .$f).
  // 5. The single result checks against the declared return
  //    descriptor: a zero-result return (undefined) raises E8010
  //    "return value 1 type mismatch: expected {D}, got nothing" for
  //    every non-null declared return and
  //    "… expected null, got nothing" for ()->null; every other
  //    failure re-raises E8010 "return value 1 type mismatch:
  //    {inner}" (junk/zero-result returns on the discard path
  //    included). The DEAL null passes nullable returns; a raw JS
  //    function never satisfies a function-typed return (assumption c
  //    — function-typed returns are never wrapped), only a byte-equal
  //    DEAL wrapper passes.
  hostFunction: function $hostFunction(descriptor, f) {
    if (typeof f !== "function") {
      $rt.fail("E8001", "expected function, got " + $kindOf(f), $undefined, $undefined, $undefined, "function", $kindOf(f));
    }
    const $parsed = $parse(descriptor);
    if ($parsed === null || $parsed.$t !== "function" || $parsed.$async) {
      $rt.fail("E8010", "invalid function signature: " + String(descriptor));
    }
    const $paramDescs = $parsed.$params;
    const $retDesc = $parsed.$ret;
    const $isNullRet = $retDesc === "null";
    return {
      $kind: "function",
      $sig: descriptor,
      $f: function(...args) {
        const $n = args.length;
        const $file = $n >= 3 ? args[$n - 3] : $undefined;
        const $line = $n >= 3 ? args[$n - 2] : $undefined;
        const $column = $n >= 3 ? args[$n - 1] : $undefined;
        const $nargs = $n >= 3 ? $n - 3 : $n;
        if ($nargs < $paramDescs.length) {
          $rt.fail("E8010", "expected at least " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        if ($nargs > $paramDescs.length) {
          $rt.fail("E8010", "expected " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          try {
            $rt.checkType($paramDescs[$i], args[$i], $file, $line, $column);
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            $rt.fail("E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $kindOf(args[$i]));
          }
        }
        const $r = f(...args.slice(0, $nargs));
        if ($isNullRet) {
          if ($r === $undefined) {
            $rt.fail("E8010", "return value 1 type mismatch: expected null, got nothing", $file, $line, $column, "null", "nothing");
          }
          try {
            return $rt.checkNull($r, $file, $line, $column);
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            $rt.fail("E8010", "return value 1 type mismatch: " + $e.message, $file, $line, $column, "null", $kindOf($r));
          }
        }
        if ($r === $undefined) {
          $rt.fail("E8010", "return value 1 type mismatch: expected " + $retDesc + ", got nothing", $file, $line, $column, $retDesc, "nothing");
        }
        try {
          return $rt.checkType($retDesc, $r, $file, $line, $column);
        } catch ($e) {
          if (!($e instanceof $DEALError)) {
            throw $e;
          }
          $rt.fail("E8010", "return value 1 type mismatch: " + $e.message, $file, $line, $column, $retDesc, $kindOf($r));
        }
      },
    };
  },

  // hostAsyncFunction: the checked wrapper for one declared async host
  // function export ("async (...) -> R", D2). The $f is an async
  // function: identical arity and parameter checks as hostFunction,
  // then the single host invocation — the operation must be a thenable
  // (the backend async operation the JS await lowering accepts), else
  // E8010 "host async function must return an async operation, got
  // {kind}" — then `await $op` and the completion check against the
  // declared R (E8001 on mismatch — the await-site completion
  // contract, host-async-bad; a valid completion delivers the checked
  // value, host-async-ok). A rejected operation propagates natively at
  // the await site. Source code can only observe the operation through
  // await (spec-v1.2:1765-1767).
  hostAsyncFunction: function $hostAsyncFunction(descriptor, f) {
    if (typeof f !== "function") {
      $rt.fail("E8001", "expected function, got " + $kindOf(f), $undefined, $undefined, $undefined, "function", $kindOf(f));
    }
    const $parsed = $parse(descriptor);
    if ($parsed === null || $parsed.$t !== "function" || !$parsed.$async) {
      $rt.fail("E8010", "invalid function signature: " + String(descriptor));
    }
    const $paramDescs = $parsed.$params;
    const $retDesc = $asyncReturnDescriptor(descriptor);
    return {
      $kind: "function",
      $sig: descriptor,
      $f: async function(...args) {
        const $n = args.length;
        const $file = $n >= 3 ? args[$n - 3] : $undefined;
        const $line = $n >= 3 ? args[$n - 2] : $undefined;
        const $column = $n >= 3 ? args[$n - 1] : $undefined;
        const $nargs = $n >= 3 ? $n - 3 : $n;
        if ($nargs < $paramDescs.length) {
          $rt.fail("E8010", "expected at least " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        if ($nargs > $paramDescs.length) {
          $rt.fail("E8010", "expected " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          try {
            $rt.checkType($paramDescs[$i], args[$i], $file, $line, $column);
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            $rt.fail("E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $kindOf(args[$i]));
          }
        }
        const $op = f(...args.slice(0, $nargs));
        if (!($op && typeof $op.then === "function")) {
          const $got = $op === $undefined ? "nothing" : $kindOf($op);
          $rt.fail("E8010", "host async function must return an async operation, got " + $got, $file, $line, $column, "async operation", $got);
        }
        const $v = await $op;
        return $rt.checkType($retDesc, $v, $file, $line, $column);
      },
    };
  },

  // loadHost: the single runtime host-loader entry (D1) — validates
  // and wraps one host module's raw exports against the declared map
  // and returns a fresh surface object. The declared map is the
  // emitter-rendered D1 shape:
  //
  //   "<name>": { $k: "function", $d: "<canonical declared descriptor>" }
  //           | { $k: "class", $d: "<canonical identity>",
  //               $fields: [ { name, $d, optional, nullable, hasDefault }, ... ] }
  //
  // Function entries: a raw JS function wraps through hostFunction;
  // a pre-wrapped { $kind: "function", $sig, $f } requires
  // $sig === the declared descriptor (E8011 when $sig is missing or
  // mismatched — the declared contract is the only trusted metadata,
  // D5) and a function $f (E8011 otherwise), then re-wraps $f — the
  // host's own wrapper object is discarded and enforcement is
  // identical to the raw path; anything else → E8011. Async declared
  // descriptors route to hostAsyncFunction.
  //
  // Class entries: the host META must be
  // { $kind: "class", $classname: <declared identity> } (byte-equal to
  // the declared $d, else E8011); the host-supplied defaults — a
  // plain object or a zero-arg function returning one, found at
  // "<C>$defaults" or the cross-backend "<C>_defaults" key — must be
  // present (else E8011, construction depends on it). The loader
  // synthesizes "<C>$new" (construction through $rt.makeClass in the
  // parent D5 phase order, with the per-construction defaults thunk
  // augmenting the host defaults with $MISSING for every declared
  // field the defaults omit) and "<C>$fields" (the declared map's
  // field metadata). The validated META stays under the declared name.
  //
  // Extra host exports are structurally dropped (the surface carries
  // only declared names plus the synthesized keys); the raw exports
  // object is never mutated. The loader never validates stdlib
  // modules (assumption a — stdlib imports keep their trusted raw
  // require path) and never invents a JS sig-table mechanism (D5).
  loadHost: function $loadHost(rawExports, declaredMap) {
    if (declaredMap === null || typeof declaredMap !== "object") {
      $rt.fail("E8011", "host module declarations must be a table", $undefined, $undefined, $undefined, "table", $kindOf(declaredMap));
    }
    if (rawExports === null || typeof rawExports !== "object") {
      $rt.fail("E8011", "host module did not return a module object", $undefined, $undefined, $undefined, "table", $kindOf(rawExports));
    }
    const $surface = {};
    const $names = Object.keys(declaredMap);
    for (let $i = 0; $i < $names.length; $i++) {
      const $name = $names[$i];
      const $entry = declaredMap[$name];
      const $v = rawExports[$name];
      if ($v === $undefined) {
        $rt.fail("E8011", "missing host export '" + $name + "'");
      }
      if ($entry === null || typeof $entry !== "object"
          || typeof $entry.$d !== "string") {
        $rt.fail("E8011", "host export '" + $name + "' has an unsupported declared descriptor: " + String($entry === null ? null : $entry.$d));
      }
      const $desc = $entry.$d;
      const $parsed = $parse($desc);
      if ($entry.$k === "function") {
        if ($parsed === null || $parsed.$t !== "function") {
          $rt.fail("E8011", "host export '" + $name + "' has an unsupported declared descriptor: " + $desc);
        }
        let $f;
        if (typeof $v === "function") {
          $f = $v;
        } else if ($v !== null && typeof $v === "object"
            && $v.$kind === "function") {
          if ($v.$sig !== $desc) {
            $rt.fail("E8011", "host export '" + $name + "' has signature mismatch: expected " + $desc + ", got " + String($v.$sig), $undefined, $undefined, $undefined, $desc, $v.$sig);
          }
          if (typeof $v.$f !== "function") {
            $rt.fail("E8011", "host export '" + $name + "' has non-function $f", $undefined, $undefined, $undefined, "function", $kindOf($v.$f));
          }
          $f = $v.$f;
        } else {
          $rt.fail("E8011", "host export '" + $name + "' is not a function", $undefined, $undefined, $undefined, "function", $kindOf($v));
        }
        $surface[$name] = $parsed.$async
            ? $rt.hostAsyncFunction($desc, $f)
            : $rt.hostFunction($desc, $f);
      } else if ($entry.$k === "class") {
        if ($parsed === null || $parsed.$t !== "class") {
          $rt.fail("E8011", "host export '" + $name + "' has an unsupported declared descriptor: " + $desc);
        }
        if ($v === null || typeof $v !== "object" || $v.$kind !== "class") {
          $rt.fail("E8011", "host export '" + $name + "' is not a class meta", $undefined, $undefined, $undefined, "class", $kindOf($v));
        }
        if ($v.$classname !== $desc) {
          $rt.fail("E8011", "host class export '" + $name + "' has identity mismatch: expected " + $desc + ", got " + String($v.$classname), $undefined, $undefined, $undefined, $desc, $v.$classname);
        }
        const $fields = $entry.$fields;
        if (!$Array.isArray($fields)) {
          $rt.fail("E8011", "host class '" + $name + "' has malformed field metadata");
        }
        const $declaredNames = [];
        for (let $j = 0; $j < $fields.length; $j++) {
          const $field = $fields[$j];
          if ($field === null || typeof $field !== "object"
              || typeof $field.name !== "string") {
            $rt.fail("E8011", "host class '" + $name + "' has malformed field metadata");
          }
          $declaredNames.push($field.name);
        }
        let $hostDefaults = rawExports[$name + "$defaults"];
        if ($hostDefaults === $undefined) {
          $hostDefaults = rawExports[$name + "_defaults"];
        }
        if ($hostDefaults === $undefined
            || !(typeof $hostDefaults === "function"
                || $isPlainObject($hostDefaults))) {
          $rt.fail("E8011", "host class '" + $name + "' is missing its defaults", $undefined, $undefined, $undefined, "table", $kindOf($hostDefaults));
        }
        // The per-construction defaults thunk (D3): the host defaults —
        // the plain object, or the zero-arg function re-evaluated on
        // every construction — augmented with $MISSING for every
        // declared field the host defaults omit, so absent optional
        // fields read the three-state MISSING and provided overlays
        // pass makeClass's extra-key gate. A non-plain-object host
        // defaults result fails inside makeClass with the pinned
        // E8001 "class defaults must be a table" (construction-time,
        // never load-time).
        const $defaultsThunk = function() {
          const $base = typeof $hostDefaults === "function"
              ? $hostDefaults()
              : $hostDefaults;
          if (!$isPlainObject($base)) {
            $rt.fail("E8001", "class defaults must be a table");
          }
          const $merged = {};
          for (let $j = 0; $j < $declaredNames.length; $j++) {
            const $field = $declaredNames[$j];
            $rt.setProp($merged, $field,
                Object.prototype.hasOwnProperty.call($base, $field)
                    ? $base[$field]
                    : $MISSING);
          }
          return $merged;
        };
        $surface[$name] = $v;
        $surface[$name + "$new"] = function(provided, $file, $line, $column) {
          return $rt.makeClass($name, $desc, $defaultsThunk, provided, $file, $line, $column);
        };
        $surface[$name + "$fields"] = $fields.slice();
      } else {
        $rt.fail("E8011", "host export '" + $name + "' has an unsupported declared descriptor: " + $desc);
      }
    }
    return $surface;
  },

  // invokeAsyncExport: the JS production async-export invoker
  // (js-v12-async-export-invocation D1-D4) — the single runtime
  // realization of the parent D9 BackendAsyncExportInvoker with three
  // result signals distinct by construction:
  //
  //   { $ok: true, $value }                          // checked completion
  //   { $ok: false, $error: {code, message, ...} }   // reified DEAL error
  //   { $ok: false, $failure: "<pinned reason>" }    // HostInvocationFailure
  //
  // A DEAL runtime error is never conflated with a host/selection/
  // infrastructure failure, and the invoker never throws: every input
  // resolves to exactly one of the three shapes. Order (D2-D4):
  // (1) module initialization already happened exactly once through the
  // host's require of the entry (Node's require cache — the invoker
  // never re-requires); (2) main — the export must exist as a function
  // wrapper with $sig === "()->null" (the E2010/E2011 selected-entry
  // gate) and its $f is awaited exactly once in the same runtime
  // instance; a missing or mis-shaped main is a HostInvocationFailure
  // (defensive — a selected project always has one); (3) export
  // selection — entryExports[exportName] must be a function wrapper
  // whose $sig is byte-equal to "async()->" + returnDescriptor; a
  // missing/sync/parameterized/descriptor-mismatched/non-wrapper export
  // is a HostInvocationFailure with a pinned reason, never a DEAL
  // error; (4) one $f() invocation, one native await, the completion
  // validated byte-exact through $rt.checkType(returnDescriptor, v) — a
  // rejected operation or a completion mismatch yields
  // { $ok: false, $error } with the reified code/message/file/line/
  // column; a valid completion yields { $ok: true, $value }. No retry,
  // no second invocation, no mutation. The member is $rt-only: DEAL
  // source can never name it (no identifier may contain $), no artifact
  // exports it, and no generated invocation shim exists.
  invokeAsyncExport: async function $invokeAsyncExport(entryExports, exportName, returnDescriptor) {
    const $failure = function(reason) {
      return { $ok: false, $failure: reason };
    };
    if (entryExports === $undefined || entryExports === null
        || typeof entryExports !== "object") {
      return $failure("entry exports is not a module object");
    }
    if (typeof returnDescriptor !== "string") {
      return $failure("invalid return descriptor");
    }
    const $main = entryExports.main;
    if ($main === $undefined) {
      return $failure("missing main");
    }
    if ($main === null || typeof $main !== "object"
        || $main.$kind !== "function" || $main.$sig !== "()->null"
        || typeof $main.$f !== "function") {
      return $failure("main is not a null-returning function");
    }
    try {
      await $main.$f();
    } catch ($e) {
      return { $ok: false, $error: $rt.reifyError($e) };
    }
    const $expected = "async()->" + returnDescriptor;
    const $w = entryExports[exportName];
    if ($w === $undefined) {
      return $failure("export not found: " + String(exportName));
    }
    if ($w === null || typeof $w !== "object" || $w.$kind !== "function"
        || typeof $w.$f !== "function") {
      return $failure("export is not a function: expected " + $expected);
    }
    const $sig = $w.$sig;
    if ($sig !== $expected) {
      const $parsed = typeof $sig === "string" ? $parse($sig) : null;
      if ($parsed === null || $parsed.$t !== "function") {
        return $failure("sync export: expected " + $expected + ", got " + String($sig));
      }
      if (!$parsed.$async) {
        return $failure("sync export: expected " + $expected + ", got " + $sig);
      }
      if ($parsed.$params.length > 0) {
        return $failure("parameterized export: expected " + $expected + ", got " + $sig);
      }
      return $failure("descriptor mismatch: expected " + $expected + ", got " + $sig);
    }
    let $v;
    try {
      $v = await $w.$f();
    } catch ($e) {
      return { $ok: false, $error: $rt.reifyError($e) };
    }
    try {
      return { $ok: true, $value: $rt.checkType(returnDescriptor, $v) };
    } catch ($e) {
      return { $ok: false, $error: $rt.reifyError($e) };
    }
  },

  // intConvert: the int(x) conversion intrinsic (deal/runtime.lua:917-926).
  // A nil-equivalent input (undefined, null, MISSING) raises E8001
  // "cannot convert null to int" with expected "int"/actual "null"; any
  // other value passes through the full checkInt contract — kind, NaN,
  // ±Infinity, non-integer, the profile range, -0 normalization (D3).
  // Every
  // error goes through the D8 spine with the forwarded (file, line,
  // column); success returns the validated value (pure, no mutation).
  intConvert: function $intConvert(v, file, line, column) {
    if ($rt.isNilEquivalent(v)) {
      $rt.fail("E8001", "cannot convert null to int", file, line, column, "int", "null");
    }
    return $rt.checkInt(v, file, line, column);
  },

  // numberConvert: the number(x) conversion intrinsic
  // (deal/runtime.lua:927-933). A nil-equivalent input raises E8001
  // "cannot convert null to number" with expected "number"/actual "null";
  // any other value passes through checkNumber — NaN and ±Infinity pass
  // and are returned (spec §Numeric overflow). Same spine, span
  // forwarding, and purity as intConvert.
  numberConvert: function $numberConvert(v, file, line, column) {
    if ($rt.isNilEquivalent(v)) {
      $rt.fail("E8001", "cannot convert null to number", file, line, column, "number", "null");
    }
    return $rt.checkNumber(v, file, line, column);
  },
  // ===== String scalar-value helpers (js-backend-runtime-artifact D9) =====
  // Code-point measures and ordering. Native JS string iteration walks one
  // Unicode scalar per step (spec §For-of), so these operate over code
  // points, not UTF-16 units. The helpers do not re-validate — checkString
  // above is the boundary (D9) — and assume validated strings (no unpaired
  // surrogates). Pure, deterministic, no mutation, no I/O.

  // scalarLength: the code-point count.
  scalarLength: function $scalarLength(s) {
    let $n = 0;
    for (const $c of s) {
      $n++;
    }
    return $n;
  },

  // scalarAt: the 0-based i-th scalar as a one-code-point string (the
  // std/string.substring convention, std/string.lua); the captured
  // nil-equivalent $undefined for any i outside [0, scalarLength(s)).
  scalarAt: function $scalarAt(s, i) {
    let $n = 0;
    for (const $c of s) {
      if ($n === i) return $c;
      $n++;
    }
    return $undefined;
  },

  // scalars: the array of one-code-point strings in order. Materialized
  // through the T1 $Array capture — $Array.from on a string iterates one
  // code point per element, so the result is a real native Array of
  // one-scalar strings.
  scalars: function $scalars(s) {
    return $Array.from(s);
  },

  // strCompare: scalar-value (code-point) ordering for </<=/>/>=. JS
  // relational operators compare UTF-16 code units, which diverges from
  // scalar order for supplementary characters (strCompare('\uE000',
  // '\u{10000}') < 0 while ('\uE000' < '\u{10000}') === false). Mirrors
  // the JVM backend's emitted scalarCompare
  // (deal/codegen/jvm/JvmBackend.java:3389): a codePointAt walk with
  // per-scalar advancement, returning a negative number / 0 / a positive
  // number.
  strCompare: function $strCompare(a, b) {
    let $ia = 0;
    let $ib = 0;
    const $la = a.length;
    const $lb = b.length;
    while ($ia < $la && $ib < $lb) {
      const $ca = a.codePointAt($ia);
      const $cb = b.codePointAt($ib);
      if ($ca !== $cb) {
        return $ca < $cb ? -1 : 1;
      }
      $ia += $ca > 0xffff ? 2 : 1;
      $ib += $cb > 0xffff ? 2 : 1;
    }
    if ($ia >= $la) {
      return $ib >= $lb ? 0 : -1;
    }
    return 1;
  },
};

module.exports = $rt;
