"use strict";

// DEAL Runtime Library v1.3 — JavaScript backend (deal/runtime.js).
// The single hand-written CommonJS runtime module exporting $rt, the JS
// analog of deal/runtime.lua (js-backend-runtime D1-D9, pinned by
// js-backend-runtime-artifact), plus the shared D9 JSON conversion and
// the @jsonable runtime walkers (js-v12-jsonable-completion D2-D5), the
// production async-export invoker (js-v12-async-export-invocation D1-D4),
// and the implementation-module registry the JavaScript semantic-IR renderer
// links through (initModule/moduleSurface/moduleState/publishModule/
// readModule/callExport/throwValue) and the carrier-neutral table index
// members of the emitted TABLE_* arms (tableGet/tableSet/tableDelete),
// the dynamic callee dispatch's private class resolution and
// HOST_PARAMETER cell (dynClass/dynFail/hostParam):
// output-runtime-scoped private records,
// the recorded THROW transport, and representation adapters only — no literal
// semantic authority. It loads standalone under node v24 from
// "./deal/runtime" and from any
// "<relpath>/deal/runtime" require path —
// no require call anywhere in the file (js-backend-runtime-artifact D1/D10).

// ===== Host-global capture (js-backend-runtime-artifact D10) =====
// The only module-scope bindings of the host globals the runtime owns.
// Every later runtime use of process, console, Math, Map, Array,
// Uint8Array, or undefined is confined to these captures; generated code
// spells none of them bare (js-backend-architecture D2). Object and
// String are not in the capture list and may be spelled bare inside this
// trusted file. The native JSON object is not captured: the conforming
// scanner and the canonical text encoder are the runtime's own (DEAL v1.3
// exact JSON). $Uint8Array joins the list with the v1.3 bytes carrier
// (js-v12-int32-bytes D3): the constructor is captured at runtime load,
// so no generated module ever spells the host-global Uint8Array.
const $process = process;
const $console = console;
const $Math = Math;
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
// super(message), so err.message === message. frames carries the error's
// own captured stack labels (innermost first), so a catch/rethrow or an
// async reification preserves the originating call chain instead of
// losing it with the re-created tagged value.
function $stackFrames($error) {
  const $stack = $error.stack;
  if (typeof $stack !== "string") return $undefined;
  const $frames = [];
  const $lines = $stack.split("\n");
  for (let $i = 1; $i < $lines.length; $i++) {
    const $line = $lines[$i].trim();
    if ($line.slice(0, 3) !== "at ") continue;
    $frames.push($line.slice(3));
  }
  return $frames.length === 0 ? $undefined : $frames.join(",");
}
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
    const $captured = $stackFrames(this);
    if ($captured !== $undefined) this.frames = $captured;
  }
}

// ===== Sentinels (js-backend-runtime-artifact D2) =====
// $MISSING: the unique object sentinel for absent optional class fields —
// the only sentinel in the runtime (not null, not undefined, === only to
// itself). DEAL null is JS null ($rt.NULL === null).
const $MISSING = {};

// ===== Runtime-private JSON shape marks (js-backend-runtime-artifact D2) =====
// Unspoofable WeakSets: the exact JSON members
// (jsonParseText/jsonParseValue/jsonStringifyText — the std/json module
// and the @jsonable walkers' table branches) consume them, and the
// language-null / table / array boundary members (isNilEquivalent,
// checkNull, checkTable, checkArray and the diagnostic-kind projection)
// read them so a decoded JSON null is language null and a decoded JSON
// array is the array carrier — never a plain table — at every declared
// boundary and through every emitted array read/length/write/delete.
// Never exported; no $rt member exposes them.
const $jsonArrayTables = new WeakSet();
const $jsonNullTables = new WeakSet();

// ===== Actual-kind mapping (js-backend-runtime-artifact D3) =====
// $kindOf: the diagnostic "actual" string for every check — the JS mirror
// of Lua's type() augmented with the runtime's own value forms. undefined
// (the T1 captured nil-equivalent, compared against $undefined — never the
// bare spelling) maps to "nil"; null to "null"; the two JSON decode
// carriers project their reference value-model kind before the generic Map
// branch — a decoded JSON array is the array carrier ("array", never the
// plain-Map "table" label) and a decoded JSON null is language null
// ("null") — agreeing with the shared boundary executor and the
// LuaJIT/JVM realizations; wrappers and class instances are identified by
// their $kind tag before the Map/Array branches; Maps (the T1 $Map
// capture) are tables; Arrays (the T1 $Array capture) are arrays;
// Uint8Arrays (the v1.3 bytes carrier, js-v12-int32-bytes D3) are bytes;
// everything else reports the JS typeof name.
function $kindOf($v) {
  if ($v === $undefined) return "nil";
  if ($v === null) return "null";
  if ($jsonNullTables.has($v)) return "null";
  if ($jsonArrayTables.has($v)) return "array";
  if ($v.$kind === "function") return "function";
  if ($v.$kind === "class") return "class";
  if ($v instanceof $Map) return "table";
  if ($Array.isArray($v)) return "array";
  if ($v instanceof $Uint8Array) return "bytes";
  return typeof $v;
}

// $elemKindOf: the array-element arm's reference-kind projection (the
// decoded-array mark, B3) — an element carrying the std/json decode mark
// renders the reference value model's kind "table", never the "array"
// token $kindOf projects for the carrier itself; every other element keeps
// $kindOf. The LuaJIT prelude's __elemRefKind and the JVM's elementActual
// apply the same mark projection.
function $elemKindOf($v) {
  if (!$Array.isArray($v) && $jsonArrayTables.has($v)) return "table";
  return $kindOf($v);
}

// $luaKindOf: the Lua type() projection of a value — the "actual" label
// of the checks whose messages are composed from a value's Lua-side
// carrier kind rather than the runtime's own value form (the host
// boundary's parameter/return arms, deal/runtime.lua:983/1025, and the
// std/json unsupported-type arm, std/json.lua:136): every table-carried
// value is a Lua table — the DEAL-null sentinel, Maps, Arrays, the bytes
// carrier, class instances, and the function/class wrappers included —
// a raw JS function stays "function", and primitives keep their names.
function $luaKindOf($v) {
  if ($v === $undefined) return "nil";
  if (typeof $v === "object" || typeof $v === "function") {
    return typeof $v === "function" ? "function" : "table";
  }
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
// "async"? "(params)" "->" D functions; the builtin "Error" atom
// (the one class identity without the leading "@"); "@" class atoms with
// at least two components and an identifier-shaped final component,
// carried verbatim (byte-for-byte nominal compare). Every legacy spelling
// — "T[]", "T|null", rest-parameter sigs, bare class names other than
// the builtin "Error" atom, "@/Error", "@$builtin/Error", dotted
// class-name-position text, nested nullables ("??T"),
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
  // The builtin Error atom: the exact token "Error" terminating at a
  // descriptor position (end of input or a delimiter). The superseded
  // v1.2 spellings "@/Error" and "@$builtin/Error" never parse.
  if ($matchKeyword($cps, $st.$pos, "Error")
      && ($st.$pos + 5 >= $cps.length
          || !$isIdentifierByte($cps[$st.$pos + 5].$cp))) {
    $st.$pos += 5;
    return { $t: "class", $name: "Error" };
  }
  // The exact async marker: "async" immediately followed by "(".
  if ($matchKeyword($cps, $st.$pos, "async")
      && $st.$pos + 5 < $cps.length && $cps[$st.$pos + 5].$cp === 0x28) {
    $st.$pos += 5;
    return $parseFunction($d, $cps, $st, true);
  }
  // Every other spelling — bare class names ("Error" is the canonical
  // builtin atom above, every other bare name stays a rejection),
  // "T[]"/"T|null" shapes, rest sigs — is unparsable.
  return null;
}

// The identifier-scalar predicate of the parser's identifier-run rule.
function $isIdentifierByte($cp) {
  return ($cp >= 0x41 && $cp <= 0x5A) || ($cp >= 0x61 && $cp <= 0x7A)
      || ($cp >= 0x30 && $cp <= 0x39) || $cp === 0x5F;
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
// No rest-parameter arm exists in DEAL v1.3.
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
// identifier-shaped final component are mandatory; the reserved
// "$builtin" namespace forms no class atom (the builtin Error class is
// the bare "Error" atom); no root/path boundary is ever inferred and
// class atoms compare byte-for-byte.
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
    if (!$hasSeparator && $component === "$builtin") return null;
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

// $stringBoundaryMessage: the reason split of the string boundary
// message — the JS analog of the reference's byte-walk reason split
// (__rt.utf8_status, deal/runtime.lua:239-296, ISSUE-0598). JS strings
// are UTF-16, so an unpaired surrogate code unit is the only malformed
// content the walk can see: a string whose entire content is one lone
// surrogate code unit is the analog of the reference's CESU-style
// surrogate-code-point encoding ("expected string, got UTF-16 surrogate
// code point", the host-surrogate-utf8-e8010 sidecar); an unpaired
// surrogate embedded in a longer string is the analog of a malformed
// encoding and keeps the general message ("expected string, got invalid
// UTF-8 encoding", the host-invalid-utf8-e8010 sidecar).
function $stringBoundaryMessage($v) {
  if ($v.length === 1) {
    return "expected string, got UTF-16 surrogate code point";
  }
  return "expected string, got invalid UTF-8 encoding";
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

// ===== Exact JSON (DEAL v1.3) — scanner, classification, marks, text
// encoder, and shape conversion =====
// The members std/json and the @jsonable runtime walkers share:
// jsonParseText is the conforming RFC-8259 scanner (exact decimal
// classification, decoded-key duplicates, the shared reason texts and
// the 1-based UTF-8 byte offset), jsonParseValue converts a scanned
// native document into the runtime's marked-Map table shape, and
// jsonStringifyText is the finite-acyclic text encoder with the closed
// unsupported/cycle arms. They live here because the walkers depend on
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

// $fromJsonConvert: stack-safe conversion of a native $JSON.parse result
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
// so the products are the runtime's own Map shapes and the mark members
// accept them by construction; the boundary members read the marks — a
// decoded array is the array carrier and a decoded null is language
// null, so neither passes $rt.checkTable. Fresh Maps, independent of
// inputs; a parsed JSON null never aliases a shared sentinel.
function $fromJsonConvert($value, $file, $line, $column) {
  const $stack = [];
  let $completed = null;
  let $haveCompleted = false;
  const $start = function ($input) {
    if ($input === null) {
      $completed = $rt.markJsonNullTable($rt.makeTable({}));
      $haveCompleted = true;
      return;
    }
    if (typeof $input === "string") {
      $scanSurrogates($input, $file, $line, $column);
      $completed = $input;
      $haveCompleted = true;
      return;
    }
    if ($Array.isArray($input)) {
      $stack.push({ array: true, source: $input, index: 0,
        table: $rt.makeTable({}), keys: null });
      return;
    }
    if (typeof $input === "object") {
      // JSON object: fresh Map iterating own enumerable string keys
      // (Object.keys — never for...in, which walks the prototype chain;
      // $JSON.parse creates __proto__ as an own data property, so a
      // __proto__ key converts like any other).
      $stack.push({ array: false, source: $input, index: 0,
        table: $rt.makeTable({}), keys: Object.keys($input) });
      return;
    }
    // number / boolean scalars pass through.
    $completed = $input;
    $haveCompleted = true;
  };
  $start($value);
  while (true) {
    if ($haveCompleted) {
      if ($stack.length === 0) {
        return $completed;
      }
      const $frame = $stack[$stack.length - 1];
      if ($frame.array) {
        const $key = String($frame.index + 1);
        $frame.table.set($key, $completed);
        if ($isNumberVariant($frame.source, String($frame.index))) {
          $markNumberVariant($frame.table, $key);
        }
        $frame.index++;
      } else {
        const $key = $frame.keys[$frame.index];
        $frame.table.set($key, $completed);
        if ($isNumberVariant($frame.source, $key)) {
          $markNumberVariant($frame.table, $key);
        }
        $frame.index++;
      }
      $completed = null;
      $haveCompleted = false;
      continue;
    }
    const $frame = $stack[$stack.length - 1];
    if ($frame.array) {
      if ($frame.index >= $frame.source.length) {
        $stack.pop();
        $completed = $rt.markJsonArrayTable($frame.table);
        $haveCompleted = true;
        continue;
      }
      $start($frame.source[$frame.index]);
      continue;
    }
    if ($frame.index >= $frame.keys.length) {
      $stack.pop();
      $completed = $frame.table;
      $haveCompleted = true;
      continue;
    }
    const $key = $frame.keys[$frame.index];
    $scanSurrogates($key, $file, $line, $column);
    $start($frame.source[$key]);
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

// ===== Exact JSON number classification, the RFC-8259 scanner, and the
// container number-variant marks (DEAL v1.3 JSON number classification).
// The scanner is the JS realization of the shared rule: a numeric lexeme
// denotes the signed32 integer exactly when its exact mathematical
// decimal value is integral and inside [-2147483648, 2147483647]; every
// other lexeme denotes the binary64 value of the standard conversion.
// Decoded documents keep the per-slot variant in the module-private
// container marks, because JS numbers cannot carry it themselves; the
// encode walks and the class decode consult the marks, so an integral
// token written in fraction/exponent form is the int the specification
// names and a fractional token that rounds to an integral double is
// never admitted at an int position.

// $jsonNumberMarks: container -> $Map(key -> true) for every slot that
// holds the number variant. Keyed by native parsed containers and by the
// converted marked-Map tables; never exported (like the shape marks).
const $jsonNumberMarks = new WeakMap();

function $markNumberVariant($container, $key) {
  let $marks = $jsonNumberMarks.get($container);
  if ($marks === $undefined) {
    $marks = new $Map();
    $jsonNumberMarks.set($container, $marks);
  }
  $marks.set($key, true);
}

function $isNumberVariant($container, $key) {
  const $marks = $jsonNumberMarks.get($container);
  return $marks !== $undefined && $marks.has($key);
}

// $isValidScalars: true when $s contains no unpaired surrogate unit (every
// JS string the parser produces is scalar-valid by construction, so this
// is the boundary predicate of hand-supplied strings).
function $isValidScalars($s) {
  for (let $i = 0; $i < $s.length; $i++) {
    const $c = $s.charCodeAt($i);
    if ($c >= 0xd800 && $c <= 0xdbff) {
      const $next = $i + 1 < $s.length ? $s.charCodeAt($i + 1) : -1;
      if ($next < 0xdc00 || $next > 0xdfff) {
        return false;
      }
      $i++;
    } else if ($c >= 0xdc00 && $c <= 0xdfff) {
      return false;
    }
  }
  return true;
}

// $jsonUtf8Length: the UTF-8 byte length of one Unicode scalar value.
function $jsonUtf8Length($cp) {
  return $cp < 0x80 ? 1 : $cp < 0x800 ? 2 : $cp < 0x10000 ? 3 : 4;
}

// $jsonIntOrNil: the exact signed32 value of one grammar-valid RFC-8259
// numeric lexeme, or null when the mathematical value is fractional or
// outside signed32. Zero classifies immediately; the fractional scale is
// cancelled by the coefficient's trailing zeroes; the exponent text is
// processed with bounded magnitude comparisons (a long exponent
// saturates), so no machine overflow and no input cap can change the
// decision, and -0/-0.0 normalize to the int 0.
function $jsonIntOrNil($text) {
  let $i = 0;
  let $neg = false;
  if ($text.charCodeAt(0) === 0x2d) {
    $neg = true;
    $i = 1;
  }
  const $intStart = $i;
  while ($i < $text.length) {
    const $c = $text.charCodeAt($i);
    if ($c < 0x30 || $c > 0x39) break;
    $i++;
  }
  const $intEnd = $i;
  let $fracStart = -1;
  let $fracEnd = -1;
  if ($i < $text.length && $text.charCodeAt($i) === 0x2e) {
    $i++;
    $fracStart = $i;
    while ($i < $text.length) {
      const $c = $text.charCodeAt($i);
      if ($c < 0x30 || $c > 0x39) break;
      $i++;
    }
    $fracEnd = $i;
  }
  let $expo = 0;
  let $expNeg = false;
  if ($i < $text.length) {
    const $e = $text.charCodeAt($i);
    if ($e === 0x65 || $e === 0x45) {
      $i++;
      const $c = $text.charCodeAt($i);
      if ($c === 0x2b || $c === 0x2d) {
        $expNeg = $c === 0x2d;
        $i++;
      }
      // The exponent magnitude accumulates its digits with bounded
      // saturation: leading zeroes contribute nothing, so an exponent
      // text longer than 18 characters whose value is small stays exact
      // (the shared JsonNumberClassifier rule), and the arithmetic can
      // never overflow.
      let $magnitude = 0;
      while ($i < $text.length) {
        const $digit = $text.charCodeAt($i) - 0x30;
        if ($magnitude > (1000000000000 - $digit) / 10) {
          $magnitude = 1000000000000;
        } else {
          $magnitude = $magnitude * 10 + $digit;
        }
        $i++;
      }
      $expo = $magnitude;
    }
  }
  let $digits = $text.slice($intStart, $intEnd);
  if ($fracStart >= 0) {
    $digits = $digits + $text.slice($fracStart, $fracEnd);
  }
  let $first = 0;
  while ($first < $digits.length && $digits.charCodeAt($first) === 0x30) {
    $first++;
  }
  if ($first === $digits.length) {
    return 0;
  }
  let $trailing = 0;
  while ($digits.charCodeAt($digits.length - 1 - $trailing) === 0x30) {
    $trailing++;
  }
  const $significant = $digits.slice($first, $digits.length - $trailing);
  const $fracLen = $fracStart >= 0 ? $fracEnd - $fracStart : 0;
  const $effective = ($expNeg ? -$expo : $expo) - $fracLen + $trailing;
  if ($effective < 0) {
    return null;
  }
  if ($significant.length + $effective > 10) {
    return null;
  }
  const $value = Number($significant + "0".repeat($effective));
  // The signed32 range check compares the unsigned magnitude before the
  // sign is applied: negating first would leave the negative branch dead
  // for every magnitude in [2147483649, ...] (the shared classifier
  // rule: at most 2147483648 may carry the minus sign).
  const $limit = $neg ? 2147483648 : 2147483647;
  if ($value > $limit) {
    return null;
  }
  return $neg ? -$value : $value;
}

// $parseJsonText: the conforming RFC-8259 scanner over the code points of
// one scalar-valid string (an explicit container stack, so a finite
// nested document has no call-stack bound). It builds native
// null/boolean/number/string +
// null-prototype-object + Array documents and records every number-variant
// slot in the container marks; a syntax defect — decoded-key duplicates
// included, positioned at the second key's opening quote — raises the
// E8001 parse failure with the shared reason texts and the 1-based UTF-8
// byte offset. There is no fixed nesting bound.
function $parseJsonText($text, $file, $line, $column) {
  let $pos = 0;
  let $bytes = 0;
  const $fail = function ($reason, $offset) {
    const $at = $offset === $undefined ? $bytes + 1 : $offset;
    $rt.fail("E8001", "JSON parse error at position " + $at + ": " + $reason,
      $file, $line, $column);
  };
  const $atEnd = function () {
    return $pos >= $text.length;
  };
  const $peek = function () {
    return $pos < $text.length ? $text.codePointAt($pos) : -1;
  };
  const $advance = function () {
    const $cp = $text.codePointAt($pos);
    $pos += $cp > 0xffff ? 2 : 1;
    $bytes += $jsonUtf8Length($cp);
    return $cp;
  };
  const $skipWs = function () {
    while ($pos < $text.length) {
      const $c = $text.charCodeAt($pos);
      if ($c === 0x20 || $c === 0x09 || $c === 0x0a || $c === 0x0d) {
        $pos++;
        $bytes++;
      } else {
        break;
      }
    }
  };
  const $parseHex4 = function () {
    let $value = 0;
    for (let $k = 0; $k < 4; $k++) {
      if ($atEnd()) $fail("invalid escape");
      const $c = $peek();
      let $d;
      if ($c >= 0x30 && $c <= 0x39) $d = $c - 0x30;
      else if ($c >= 0x61 && $c <= 0x66) $d = $c - 0x57;
      else if ($c >= 0x41 && $c <= 0x46) $d = $c - 0x37;
      else $d = -1;
      if ($d < 0) $fail("invalid escape");
      $advance();
      $value = $value * 16 + $d;
    }
    return $value;
  };
  const $parseString = function () {
    $advance();
    const $out = [];
    while (true) {
      if ($atEnd()) $fail("unterminated string");
      const $c = $peek();
      if ($c === 0x22) {
        $advance();
        return $out.join("");
      }
      if ($c === 0x5c) {
        $advance();
        if ($atEnd()) $fail("unterminated string");
        const $e = $peek();
        if ($e === 0x22) {
          $advance();
          $out.push("\"");
        } else if ($e === 0x5c) {
          $advance();
          $out.push("\\");
        } else if ($e === 0x2f) {
          $advance();
          $out.push("/");
        } else if ($e === 0x62) {
          $advance();
          $out.push("\b");
        } else if ($e === 0x66) {
          $advance();
          $out.push("\f");
        } else if ($e === 0x6e) {
          $advance();
          $out.push("\n");
        } else if ($e === 0x72) {
          $advance();
          $out.push("\r");
        } else if ($e === 0x74) {
          $advance();
          $out.push("\t");
        } else if ($e === 0x75) {
          $advance();
          let $cp = $parseHex4();
          if ($cp >= 0xd800 && $cp <= 0xdbff) {
            if ($atEnd() || $peek() !== 0x5c) $fail("unpaired surrogate escape");
            $advance();
            if ($atEnd() || $peek() !== 0x75) $fail("unpaired surrogate escape");
            $advance();
            const $lo = $parseHex4();
            if ($lo < 0xdc00 || $lo > 0xdfff) $fail("unpaired surrogate escape");
            $cp = 0x10000 + ($cp - 0xd800) * 0x400 + ($lo - 0xdc00);
          } else if ($cp >= 0xdc00 && $cp <= 0xdfff) {
            $fail("unpaired surrogate escape");
          }
          $out.push(String.fromCodePoint($cp));
        } else {
          $fail("invalid escape");
        }
      } else if ($c < 0x20) {
        $fail("unexpected character");
      } else if ($c >= 0xd800 && $c <= 0xdfff) {
        if ($c <= 0xdbff && $pos + 1 < $text.length) {
          const $next = $text.charCodeAt($pos + 1);
          if ($next >= 0xdc00 && $next <= 0xdfff) {
            $out.push($text.slice($pos, $pos + 2));
            $pos += 2;
            $bytes += 4;
            continue;
          }
        }
        $fail("unpaired surrogate escape");
      } else {
        $advance();
        $out.push(String.fromCodePoint($c));
      }
    }
  };
  const $parseLiteral = function ($word, $value) {
    for (let $k = 0; $k < $word.length; $k++) {
      if ($atEnd()) $fail("unexpected end of input");
      if ($peek() !== $word.charCodeAt($k)) $fail("unexpected character");
      $advance();
    }
    return $value;
  };
  const $parseNumber = function () {
    const $start = $pos;
    if (!$atEnd() && $peek() === 0x2d) $advance();
    if ($atEnd()) $fail("invalid number");
    let $c = $peek();
    if ($c < 0x30 || $c > 0x39) $fail("invalid number");
    if ($c === 0x30) {
      $advance();
      if (!$atEnd() && $peek() >= 0x30 && $peek() <= 0x39) $fail("leading zero");
    } else {
      $advance();
      while (!$atEnd() && $peek() >= 0x30 && $peek() <= 0x39) $advance();
    }
    if (!$atEnd() && $peek() === 0x2e) {
      $advance();
      if ($atEnd() || $peek() < 0x30 || $peek() > 0x39) $fail("invalid number");
      while (!$atEnd() && $peek() >= 0x30 && $peek() <= 0x39) $advance();
    }
    if (!$atEnd() && ($peek() === 0x65 || $peek() === 0x45)) {
      $advance();
      if (!$atEnd() && ($peek() === 0x2b || $peek() === 0x2d)) $advance();
      if ($atEnd() || $peek() < 0x30 || $peek() > 0x39) $fail("invalid number");
      while (!$atEnd() && $peek() >= 0x30 && $peek() <= 0x39) $advance();
    }
    const $lexeme = $text.slice($start, $pos);
    const $exact = $jsonIntOrNil($lexeme);
    if ($exact !== null) {
      return { value: $exact, variant: false };
    }
    return { value: Number($lexeme), variant: true };
  };
  // $parseValue: one value plus its own scalar number variant (false for
  // every container and non-number leaf), so a member mark never observes
  // a nested member's variant. The explicit container frames make the
  // traversal stack-safe: a finite nested document has no call-stack
  // bound.
  const $OPENED = { opened: true };
  const $parseScalarOrOpen = function ($stack) {
    if ($atEnd()) $fail("unexpected end of input");
    const $c = $peek();
    if ($c === 0x7b) {
      $advance();
      $stack.push({ arr: null, obj: Object.create(null), seen: new $Map(),
        state: 0, key: null });
      return $OPENED;
    }
    if ($c === 0x5b) {
      $advance();
      $stack.push({ arr: [], obj: null, seen: null, state: 4, key: null });
      return $OPENED;
    }
    if ($c === 0x22) return { value: $parseString(), variant: false };
    if ($c === 0x74) return { value: $parseLiteral("true", true), variant: false };
    if ($c === 0x66) return { value: $parseLiteral("false", false), variant: false };
    if ($c === 0x6e) return { value: $parseLiteral("null", null), variant: false };
    if ($c === 0x2d || ($c >= 0x30 && $c <= 0x39)) return $parseNumber();
    $fail("unexpected character");
  };
  const $parseValue = function () {
    const $stack = [];
    let $completed = null;
    let $haveCompleted = false;
    while (true) {
      if ($haveCompleted) {
        if ($stack.length === 0) return $completed;
        const $parent = $stack[$stack.length - 1];
        if ($parent.arr !== null) {
          const $index = $parent.arr.length;
          $parent.arr.push($completed.value);
          if ($completed.variant) $markNumberVariant($parent.arr, String($index));
          $parent.state = 5;
        } else {
          $parent.obj[$parent.key] = $completed.value;
          if ($completed.variant) $markNumberVariant($parent.obj, $parent.key);
          $parent.state = 3;
        }
        $completed = null;
        $haveCompleted = false;
        continue;
      }
      if ($stack.length === 0) {
        const $parsed = $parseScalarOrOpen($stack);
        if ($parsed !== $OPENED) {
          $completed = $parsed;
          $haveCompleted = true;
        }
        continue;
      }
      const $frame = $stack[$stack.length - 1];
      if ($frame.state === 0) {
        // The object open: an immediate '}' finishes the empty object.
        $skipWs();
        if (!$atEnd() && $peek() === 0x7d) {
          $advance();
          $completed = { value: $frame.obj, variant: false };
          $stack.pop();
          $haveCompleted = true;
        } else {
          $frame.state = 1;
        }
        continue;
      }
      if ($frame.state === 1) {
        $skipWs();
        if ($atEnd()) $fail("unexpected end of input");
        if ($peek() !== 0x22) $fail("missing key");
        const $keyOffset = $bytes + 1;
        const $key = $parseString();
        if ($frame.seen.has($key)) $fail("duplicate key", $keyOffset);
        $frame.seen.set($key, true);
        $skipWs();
        if ($atEnd() || $peek() !== 0x3a) $fail("missing colon");
        $advance();
        $frame.key = $key;
        $frame.state = 2;
        continue;
      }
      if ($frame.state === 2) {
        $skipWs();
        if ($atEnd()) $fail("unexpected end of input");
        const $member = $parseScalarOrOpen($stack);
        if ($member !== $OPENED) {
          $completed = $member;
          $haveCompleted = true;
        }
        continue;
      }
      if ($frame.state === 3) {
        $skipWs();
        if ($atEnd()) $fail("unterminated object");
        const $after = $peek();
        if ($after === 0x2c) {
          $advance();
          $frame.state = 1;
        } else if ($after === 0x7d) {
          $advance();
          $completed = { value: $frame.obj, variant: false };
          $stack.pop();
          $haveCompleted = true;
        } else {
          $fail("missing comma");
        }
        continue;
      }
      if ($frame.state === 4) {
        // The array open: an immediate ']' finishes the empty array.
        $skipWs();
        if (!$atEnd() && $peek() === 0x5d) {
          $advance();
          $completed = { value: $frame.arr, variant: false };
          $stack.pop();
          $haveCompleted = true;
        } else {
          $frame.state = 2;
        }
        continue;
      }
      // state 5: after an array member.
      $skipWs();
      if ($atEnd()) $fail("unterminated array");
      const $after = $peek();
      if ($after === 0x2c) {
        $advance();
        $frame.state = 2;
      } else if ($after === 0x5d) {
        $advance();
        $completed = { value: $frame.arr, variant: false };
        $stack.pop();
        $haveCompleted = true;
      } else {
        $fail("missing comma");
      }
    }
  };
  $skipWs();
  const $parsed = $parseValue();
  $skipWs();
  if (!$atEnd()) $fail("trailing content");
  return $parsed.value;
}

// ===== The canonical decimal number text (the same spelling the Lua and
// JVM realizations produce: shortest round-trippable digits, always one
// fractional digit, E-notation outside [1e-3, 1e7)) =====

function $decimalParts($rep) {
  const $eIndex = $rep.indexOf("e");
  let $mant;
  let $exp;
  if ($eIndex >= 0) {
    $mant = $rep.slice(0, $eIndex);
    $exp = Number($rep.slice($eIndex + 1));
  } else {
    $mant = $rep;
    $exp = 0;
  }
  const $point = $mant.indexOf(".");
  const $intLen = $point >= 0 ? $point : $mant.length;
  let $digits = $mant.split(".").join("");
  $exp = $exp - ($digits.length - $intLen);
  let $first = 0;
  while ($first < $digits.length && $digits.charCodeAt($first) === 0x30) {
    $first++;
  }
  $digits = $digits.slice($first);
  if ($digits === "") $digits = "0";
  return { digits: $digits, expo: $exp };
}

function $sfBump($digits, $up) {
  const $n = $digits.length;
  const $d = [];
  for (let $i = 0; $i < $n; $i++) {
    $d.push(Number($digits.charAt($i)));
  }
  if ($up) {
    let $i = $n - 1;
    while ($i >= 0 && $d[$i] === 9) {
      $d[$i] = 0;
      $i--;
    }
    if ($i < 0) return "1";
    $d[$i] = $d[$i] + 1;
  } else {
    let $i = $n - 1;
    while ($i >= 0 && $d[$i] === 0) {
      $d[$i] = 9;
      $i--;
    }
    if ($i < 0) return "9";
    $d[$i] = $d[$i] - 1;
  }
  return $d.join("");
}

function $sfDec($digits, $expo) {
  if ($digits.length === 1) return $digits + "e" + $expo;
  return $digits.slice(0, 1) + "." + $digits.slice(1)
    + "e" + ($expo + $digits.length - 1);
}

function $sfExpDigits($v) {
  const $eIndex = $v.toExponential(40).indexOf("e");
  return $v.toExponential(40).slice(0, $eIndex).split(".").join("");
}

function $sfDigits($v, $n) {
  const $rep = $v.toPrecision($n);
  const $parts = $decimalParts($rep);
  let $digits = $parts.digits;
  let $expo = $parts.expo;
  const $pad = $n - $digits.length;
  if ($pad > 0) {
    $digits = $digits + "0".repeat($pad);
    $expo = $expo - $pad;
  }
  if (Number($sfDec($digits, $expo)) !== $v) {
    const $upDigits = $sfBump($digits, true);
    if (Number($sfDec($upDigits, $expo)) === $v) {
      return { digits: $upDigits, expo: $expo };
    }
    const $downDigits = $sfBump($digits, false);
    if (Number($sfDec($downDigits, $expo)) === $v) {
      return { digits: $downDigits, expo: $expo };
    }
    return null;
  }
  const $exp = $sfExpDigits($v);
  const $downDigits = $sfBump($digits, false);
  let $downMid = $downDigits + "5";
  let $lead = 0;
  while ($lead < $downMid.length && $downMid.charCodeAt($lead) === 0x30) $lead++;
  $downMid = $downMid.slice($lead);
  if ($exp.slice(0, $downMid.length) === $downMid
      && $exp.slice($downMid.length).split("0").join("") === ""
      && Number($sfDec($downDigits, $expo)) === $v) {
    if (Number($downDigits.charAt($downDigits.length - 1)) % 2 === 0) {
      return { digits: $downDigits, expo: $expo };
    }
    return { digits: $digits, expo: $expo };
  }
  const $upDigits = $sfBump($digits, true);
  const $upMid = $digits + "5";
  if ($exp.slice(0, $upMid.length) === $upMid
      && $exp.slice($upMid.length).split("0").join("") === ""
      && Number($sfDec($upDigits, $expo)) === $v) {
    if (Number($upDigits.charAt($upDigits.length - 1)) % 2 === 0) {
      return { digits: $upDigits, expo: $expo };
    }
    return { digits: $digits, expo: $expo };
  }
  return { digits: $digits, expo: $expo };
}

// $numText: the canonical decimal text of one finite binary64 value, or
// null for a nonfinite one.
function $numText($v) {
  if ($v !== $v || $v === Infinity || $v === -Infinity) {
    return null;
  }
  let $neg = false;
  let $a = $v;
  if ($a < 0 || ($a === 0 && 1 / $a < 0)) {
    $neg = true;
    $a = -$a;
  }
  if ($a === 0) {
    return $neg ? "-0.0" : "0.0";
  }
  let $digits = null;
  let $expo = 0;
  for (let $n = 1; $n <= 17; $n++) {
    const $candidate = $sfDigits($a, $n);
    if ($candidate !== null) {
      $digits = $candidate.digits;
      $expo = $candidate.expo;
      break;
    }
  }
  if ($digits === null) {
    const $parts = $decimalParts($a.toExponential(16));
    $digits = $parts.digits;
    $expo = $parts.expo;
  }
  let $first = 0;
  while ($first < $digits.length && $digits.charCodeAt($first) === 0x30) $first++;
  $digits = $digits.slice($first);
  if ($digits === "") $digits = "0";
  const $k = $expo + $digits.length - 1;
  while ($digits.length > 1 && $digits.charCodeAt($digits.length - 1) === 0x30) {
    $digits = $digits.slice(0, $digits.length - 1);
  }
  let $text;
  if ($k >= -3 && $k <= 6) {
    if ($k >= 0) {
      if ($digits.length <= $k + 1) {
        $text = $digits + "0".repeat($k + 1 - $digits.length) + ".0";
      } else {
        $text = $digits.slice(0, $k + 1) + "." + $digits.slice($k + 1);
      }
    } else {
      $text = "0." + "0".repeat(-$k - 1) + $digits;
    }
  } else {
    const $tail = $digits.slice(0, 1) + "."
      + ($digits.length > 1 ? $digits.slice(1) : "0");
    $text = $tail + "E" + $k;
  }
  return $neg ? "-" + $text : $text;
}

// ===== The shared JSON text encoder (the std/json and class-walk rows) =====

// $carrierToken: the closed std/json carrier-kind projection of a value
// (the pinned actual vocabulary).
function $carrierToken($v) {
  if ($v === $undefined || $v === $MISSING) return "nil";
  if ($v === null) return "table";
  if (typeof $v === "boolean") return "boolean";
  if (typeof $v === "number") return "number";
  if (typeof $v === "string") return "string";
  if ($rt.isBytes($v)) return "bytes";
  if (typeof $v === "function" || $v.$kind === "function") return "function";
  return "table";
}

// $walkToken: the typed-boundary projection of one failing walk value
// (the walk arm's actual): an integral finite number is the int carrier,
// every other number the number carrier, an invalid scalar sequence
// invalid-unicode, and a class instance its carried canonical atom.
function $walkToken($v) {
  if ($v === $undefined || $v === $MISSING) return "nil";
  if ($v === null) return "null";
  if (typeof $v === "boolean") return "boolean";
  if (typeof $v === "number") {
    if ($v === $v && $v !== Infinity && $v !== -Infinity && $v % 1 === 0) {
      return "int";
    }
    return "number";
  }
  if (typeof $v === "string") {
    return $isValidScalars($v) ? "string" : "invalid-unicode";
  }
  if ($rt.isBytes($v)) return "bytes";
  if (typeof $v === "function" || $v.$kind === "function") return "function";
  if ($Array.isArray($v)) return "array";
  if ($v.$kind === "class") {
    return typeof $v.$classname === "string" ? $v.$classname : "table";
  }
  return "table";
}

// $jsonEscapeText: RFC-8259 string escaping (quote and backslash escaped,
// the named short escapes, every other control scalar as a lowercase
// four-hex-digit escape, every other scalar raw).
function $jsonEscapeText($s) {
  let $out = "\"";
  for (let $i = 0; $i < $s.length; $i++) {
    const $c = $s.charCodeAt($i);
    if ($c === 0x22) $out += "\\\"";
    else if ($c === 0x5c) $out += "\\\\";
    else if ($c === 0x08) $out += "\\b";
    else if ($c === 0x0c) $out += "\\f";
    else if ($c === 0x0a) $out += "\\n";
    else if ($c === 0x0d) $out += "\\r";
    else if ($c === 0x09) $out += "\\t";
    else if ($c < 0x20) {
      const $hex = $c.toString(16);
      $out += "\\u" + "0".repeat(4 - $hex.length) + $hex;
    } else {
      $out += $s.charAt($i);
    }
  }
  return $out + "\"";
}

// $encodeJsonText: the shared finite-acyclic JSON text walk (an explicit
// frame stack, so a finite nested value has no call-stack bound). $typed is
// false for the std/json row (carrier-kind failures through
// JSON_STRINGIFY_UNSUPPORTED) and true for the class-walk row
// (typed-boundary failures through JSON_TO_WALK at the pinned position).
// $active is the path-local container stack; a re-entry is the closed
// cycle arm. $variant marks one scalar number slot whose token carried
// the number variant (the container marks), which therefore spells
// through the canonical decimal text; an int-variant or unmarked slot
// keeps the integer/default spelling.
function $encodeJsonText($v, $active, $file, $line, $column, $typed, $path, $variant) {
  const $unsupported = function ($value, $valuePath) {
    if ($typed) {
      const $token = $walkToken($value);
      $rt.fail("E8001", "value at " + $valuePath + " is not JSON serializable: "
        + $token, $file, $line, $column, $undefined, $token);
    }
    const $token = $carrierToken($value);
    $rt.fail("E8001", "unsupported type for JSON encoding: " + $token,
      $file, $line, $column, "string, number, boolean, or table", $token);
  };
  const $out = [];
  const $stack = [];
  const $emit = function ($value, $valuePath, $valueVariant) {
    if ($value === null) {
      $out.push("null");
      return;
    }
    const $type = typeof $value;
    if ($type === "boolean") {
      $out.push($value ? "true" : "false");
      return;
    }
    if ($type === "number") {
      if ($value !== $value || $value === Infinity || $value === -Infinity) {
        $unsupported($value, $valuePath);
      }
      if ($valueVariant === true) {
        const $text = $numText($value);
        if ($text === null) {
          $unsupported($value, $valuePath);
        }
        $out.push($text);
        return;
      }
      $out.push(String($value));
      return;
    }
    if ($type === "string") {
      if (!$isValidScalars($value)) $unsupported($value, $valuePath);
      $out.push($jsonEscapeText($value));
      return;
    }
    if ($rt.isBytes($value) || $type === "function"
        || ($type === "object" && $value.$kind === "function")) {
      $unsupported($value, $valuePath);
    }
    if ($value instanceof $Map && $rt.isJsonNullTable($value)) {
      $out.push("null");
      return;
    }
    if ($value instanceof $Map) {
      if ($active.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $active.push($value);
      if ($rt.isJsonArrayTable($value)) {
        let $max = 0;
        let $arrayForm = true;
        for (const $key of $value.keys()) {
          const $index = $arrayFormIndex($key);
          if ($index === null) {
            $arrayForm = false;
            break;
          }
          if ($index > $max) $max = $index;
        }
        if ($arrayForm) {
          $out.push("[");
          $stack.push({ map: $value, path: $valuePath, kind: 1, index: 1, max: $max });
          return;
        }
      }
      const $keys = [];
      for (const $key of $value.keys()) {
        $keys.push($key);
      }
      $out.push("{");
      $stack.push({ map: $value, path: $valuePath, kind: 2, index: 0, keys: $keys });
      return;
    }
    if ($Array.isArray($value)) {
      if ($active.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $active.push($value);
      $out.push("[");
      $stack.push({ array: $value, path: $valuePath, kind: 3, index: 0 });
      return;
    }
    $unsupported($value, $valuePath);
  };
  const $base = $active.length;
  try {
    $emit($v, $path, $variant);
    while ($stack.length > 0) {
      const $frame = $stack[$stack.length - 1];
      if ($frame.kind === 1) {
        // A marked array-shaped Map: dense indices 1..max, an absent
        // element spelling the JSON null.
        if ($frame.index > $frame.max) {
          $out.push("]");
          $active.pop();
          $stack.pop();
          continue;
        }
        const $i = $frame.index++;
        if ($i > 1) $out.push(",");
        const $key = String($i);
        const $element = $frame.map.get($key);
        const $elementPath = $frame.path === "" ? "[" + ($i - 1) + "]"
          : $frame.path + "[" + ($i - 1) + "]";
        if ($element === $undefined) {
          $out.push("null");
          continue;
        }
        $emit($element, $elementPath, $isNumberVariant($frame.map, $key));
        continue;
      }
      if ($frame.kind === 2) {
        if ($frame.index >= $frame.keys.length) {
          $out.push("}");
          $active.pop();
          $stack.pop();
          continue;
        }
        const $i = $frame.index++;
        const $key = $frame.keys[$i];
        if (typeof $key !== "string" || !$isValidScalars($key)) {
          // An invalid scalar sequence is still a string carrier on the
          // carrier-kind projection (and invalid-unicode on the typed
          // one).
          if ($typed) {
            $rt.fail("E8001", "value at " + $frame.path
              + " is not JSON serializable: invalid-unicode",
              $file, $line, $column, $undefined, "invalid-unicode");
          }
          $rt.fail("E8001", "unsupported type for JSON encoding: string",
            $file, $line, $column, "string, number, boolean, or table", "string");
        }
        if ($i > 0) $out.push(",");
        const $element = $frame.map.get($key);
        const $elementPath = $frame.path === "" ? $key : $frame.path + "." + $key;
        $out.push($jsonEscapeText($key) + ":");
        $emit($element, $elementPath, $isNumberVariant($frame.map, $key));
        continue;
      }
      // A native Array: elements in index order, an absent element
      // spelling the JSON null.
      if ($frame.index >= $frame.array.length) {
        $out.push("]");
        $active.pop();
        $stack.pop();
        continue;
      }
      const $i = $frame.index++;
      if ($i > 0) $out.push(",");
      const $element = $frame.array[$i];
      const $elementPath = $frame.path === "" ? "[" + $i + "]" : $frame.path + "[" + $i + "]";
      if ($element === $undefined) {
        $out.push("null");
        continue;
      }
      $emit($element, $elementPath, $isNumberVariant($frame.array, String($i)));
    }
    return $out.join("");
  } finally {
    $active.length = $base;
  }
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
// collapses every throw to the DEAL null — and defaults run only after
// every provided value decoded (parent D5: a provided-value failure
// runs no defaults). Omitted required defaults come from the plan-
// carrying entry shape first (an entry with an `evaluator` function
// key invokes its labelled zero-argument evaluator exactly once per
// attempt, in descriptor-array order — ISSUE-0545, the
// runtime-default-evaluators-and-construction-phases D1 plan
// projection); the legacy defaults thunk stays the fallback for host
// declarations and the pinned probe surface. Nested class decode
// passes null as the defaults thunk: the pinned descriptor shape
// carries no nested defaults surface (className + fields only, D2), so
// an omitted required field of a nested class stays absent while
// absent nested optionals still materialize as $rt.MISSING.
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
      $jsonFromValue($entry, $doc[$entry.name], $file, $line, $column,
        $isNumberVariant($doc, $entry.name)));
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
  // Publish scaffold (D2 step 8): provided values, then the omitted
  // required defaults — the plan-carrying per-entry evaluator first
  // (ISSUE-0545: exactly one invocation per omitted required entry per
  // attempt, in descriptor-array order; the emitter's plan projection
  // carries the labelled zero-argument evaluator closures created at
  // load and never invoked there), then the thunk fallback (host
  // declarations and the probe surface), then $rt.MISSING for absent
  // optionals — all own-property writes via $rt.setProp. An omitted
  // required field without a default (nested decode) stays absent.
  const $instance = {};
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    const $name = $entry.name;
    if ($provided.has($name)) {
      $rt.setProp($instance, $name, $provided.get($name));
    } else if (typeof $entry.evaluator === "function" && !$entry.optional) {
      $rt.setProp($instance, $name, $entry.evaluator());
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
function $jsonFromValue($entry, $raw, $file, $line, $column, $numberVariant) {
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
      // The exact decimal classification admits the int position only
      // for an int-variant token: a fractional token that rounds to an
      // integral double keeps the number variant and rejects here.
      if ($numberVariant === true) {
        $rt.fail("E8001", "expected int, got number", $file, $line, $column,
          "int", "number");
      }
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
        $arr.push($jsonFromValue($entry.element, $raw[$i], $file, $line, $column,
          $isNumberVariant($raw, String($i))));
      }
      return $arr;
    default:
      $rt.fail("E8001", "unknown jtype in field descriptor", $file, $line, $column);
  }
}

// $jsonToDocument: serialize the declared fields of a tagged class
// instance into its JSON text in declaration order. Absent optionals
// ($rt.MISSING) are omitted (the three-state roundtrip); present nulls
// serialize as JSON null; a missing required field fails through the
// walk arm. The shared $path stack keeps nested class/array/table graphs
// cycle-checked end to end. $fieldPath is the pinned walk position
// spelling (the root has the empty path).
function $jsonToDocument($identity, $v, $fields, $path, $fieldPath, $file, $line, $column) {
  if (!$Array.isArray($fields)) {
    $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
  }
  const $parts = [];
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
      const $token = "nil";
      $rt.fail("E8001", "value at " + $fieldPathFor($fieldPath, $name)
        + " is not JSON serializable: " + $token, $file, $line, $column,
        $undefined, $token);
    }
    const $value = $v[$name];
    if ($value === $MISSING) {
      continue; // absent optional: omitted (three-state roundtrip)
    }
    $parts.push($jsonEscapeText($name) + ":"
      + $jsonToValue($entry, $value, $path, $fieldPathFor($fieldPath, $name),
        $file, $line, $column));
  }
  return "{" + $parts.join(",") + "}";
}

// $fieldPathFor: the pinned walk position spelling (a dot-joined path).
function $fieldPathFor($base, $name) {
  return $base === "" ? $name : $base + "." + $name;
}

// $jsonToValue: encode one declared field/element value into its JSON
// text. Every failure is the closed walk arm (E8001 "value at
// {fieldPath} is not JSON serializable: {actual}") through the fail
// spine with the forwarded location, except the int32 range gate
// (E8004). Table fields walk the shared finite-acyclic JSON text
// encoder with the typed-boundary projection.
function $jsonToValue($entry, $value, $path, $fieldPath, $file, $line, $column) {
  const $walkFail = function () {
    const $token = $walkToken($value);
    $rt.fail("E8001", "value at " + $fieldPath + " is not JSON serializable: "
      + $token, $file, $line, $column, $undefined, $token);
  };
  // Language null has the two representations the boundary knows: the
  // JS null and the std/json decoded-null marked Map. A decoded JSON
  // array legally stores the second in a nullable-element slot (the
  // carrier-aware element check of checkArray admits it and returns the
  // carrier unchanged), so both encode as JSON null here — the same
  // null leaf the native path stores.
  if ($value === null || $rt.isJsonNullTable($value)) {
    if ($entry.nullable || $entry.jtype === "null") {
      return "null";
    }
    $walkFail();
  }
  switch ($entry.jtype) {
    case "null":
      // Only language null encodes (handled above).
      $walkFail();
    case "boolean":
      if (typeof $value !== "boolean") {
        $walkFail();
      }
      return $value ? "true" : "false";
    case "string":
      if (typeof $value !== "string" || !$isValidScalars($value)) {
        $walkFail();
      }
      return $jsonEscapeText($value);
    case "int":
      if (typeof $value !== "number" || $value !== $value
          || $value > 1.7976931348623157e308 || $value < -1.7976931348623157e308
          || $value % 1 !== 0) {
        $walkFail();
      }
      // The signed32 range gate keeps its own E8004 projection.
      return String($rt.checkInt($value, $file, $line, $column));
    case "number":
      if (typeof $value !== "number" || $value !== $value
          || $value > 1.7976931348623157e308 || $value < -1.7976931348623157e308) {
        $walkFail();
      }
      return $numText($value);
    case "table":
      // The declared table row walks the JSON-shaped content only: a
      // class instance, a stored function, bytes, an absent marker, an
      // invalid scalar, or a nonfinite leaf fails the walk arm at its
      // position.
      return $encodeJsonText($value, $path, $file, $line, $column, true,
        $fieldPath, false);
    case "class":
      if (typeof $value !== "object" || $value === null || $value.$kind !== "class") {
        $walkFail();
      }
      if (typeof $entry.className !== "string" || !$Array.isArray($entry.fields)) {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      if ($value.$classname !== $entry.className) {
        $walkFail();
      }
      if ($path.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $path.push($value);
      try {
        return $jsonToDocument($entry.className, $value, $entry.fields, $path,
          $fieldPath, $file, $line, $column);
      } finally {
        $path.pop();
      }
    case "array":
      // The declared array reads through the carrier-agnostic members,
      // exactly like every emitted array read and the boundary's own
      // checkArray: a native Array and the std/json decoded-array
      // marked Map both encode, so a decoded JSON array stored in a
      // declared array field serializes as its declared DEAL array and
      // performs identically on the JS lane and the LuaJIT/JVM lanes.
      // The length is read once (the reference's 1..#v bound) and each
      // position through $rt.arrayAt, so the walk never reads an
      // invisible native property off the carrier.
      if (!$Array.isArray($value) && !$rt.isJsonArrayTable($value)) {
        $walkFail();
      }
      if ($entry.element === null || typeof $entry.element !== "object") {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      if ($path.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $path.push($value);
      try {
        const $parts = [];
        const $length = $rt.arrayLength($value);
        for (let $i = 0; $i < $length; $i++) {
          const $element = $rt.arrayAt($value, $i);
          const $elementPath = $fieldPath + "[" + $i + "]";
          if ($element === $undefined) {
            $parts.push("null");
            continue;
          }
          $parts.push($jsonToValue($entry.element, $element, $path, $elementPath,
            $file, $line, $column));
        }
        return "[" + $parts.join(",") + "]";
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

// ===== Implementation-module registry records (semantic-IR renderer) =====
// The output-runtime-scoped private records keyed by the recorded module
// id: one record per rendered implementation module, created on first
// reference (the artifact registers its record before requiring any
// implementation dependency). Different outputs load different runtime
// copies, so records are never shared across outputs; a module's record is
// never a HOST typed surface and never an extra enumerable CommonJS key.
const $executions = new $Map();

function $executionRecord($id) {
  let $record = $executions.get($id);
  if ($record === $undefined) {
    $record = {
      $id: $id,
      $state: "UNINITIALIZED",
      $surface: {},
      $failure: $undefined,
      // The private class-factory linkage of this implementation module
      // (the semantic-IR renderer's factoryEntries map): recorded
      // ClassFactoryId -> defaults-only transfer callable. The entries
      // carry no enumerable public key and register no public value.
      $factories: new $Map(),
    };
    $executions.set($id, $record);
  }
  return $record;
}

const $rt = {
  MISSING: $MISSING,
  NULL: null,
  undefined: $undefined,

  // isNilEquivalent: the nil-equivalent predicate of the null-admitting
  // boundaries and the nullable equality forms. DEAL null is the JS null
  // and the JSON-decoded null is the marked null table (the D2 decode
  // mark), so both are nil-equivalent; undefined and MISSING stay the
  // absent markers (the three-state optional fields keep their own
  // distinction through the presence map).
  isNilEquivalent: function $isNilEquivalent(v) {
    return v === $undefined || v === null || v === $MISSING
      || (v instanceof $Map && $rt.isJsonNullTable(v));
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
  // tableGet/tableSet/tableDelete: the carrier-neutral table index members
  // of the emitted TABLE_* index arms. A declared table value is the
  // insertion-ordered Map $rt.makeTable built; a module namespace value is
  // the module's published CommonJS surface — a plain object that is also
  // the object the PUBLISH/READ rows write and read, so a dynamic write and
  // a declared export name observe one another exactly like the shared
  // namespace value. The plain-object arms are own-property-safe (a legal
  // __proto__ key creates, reads and removes an own property, never the
  // inherited accessor), a missing key reads the absent marker exactly like
  // Map.get, and a write follows the publishModule setProp write. Pure
  // carrier dispatch: no checking, no allocation, no shared state.
  tableGet: function $tableGet(carrier, key) {
    if (carrier instanceof $Map) {
      return carrier.get(key);
    }
    return Object.prototype.hasOwnProperty.call(carrier, key)
      ? carrier[key] : $undefined;
  },
  tableSet: function $tableSet(carrier, key, value) {
    if (carrier instanceof $Map) {
      carrier.set(key, value);
      return carrier;
    }
    return $rt.setProp(carrier, key, value);
  },
  tableDelete: function $tableDelete(carrier, key) {
    if (carrier instanceof $Map) {
      return carrier.delete(key);
    }
    if (Object.prototype.hasOwnProperty.call(carrier, key)) {
      delete carrier[key];
      return true;
    }
    return false;
  },
  // makeTable: the coordinated additive table-construction member
  // (js-backend-emitter D9) — an addition to the ISSUE-0190
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

  // ===== Exact JSON members (DEAL v1.3 JSON number classification) =====

  // jsonParseText: the conforming RFC-8259 scanner over one string — the
  // decode open of std/json.parse and the @jsonable fromJson walkers. It
  // rejects every syntax defect (a decoded-key duplicate at the second
  // key's opening quote included) with the shared E8001 "JSON parse error
  // at position N: reason" projection, classifies every numeric lexeme
  // exactly before binary64 rounding, and records the per-slot variant
  // marks. No fixed nesting bound.
  jsonParseText: function $jsonParseText(text, file, line, column) {
    return $parseJsonText(text, file, line, column);
  },

  // jsonParseValue: the decode-side conversion of one scanned native
  // document into the marked-Map table shape — the shared walk behind
  // std/json.parse and the @jsonable walkers' table-field decode
  // (js-v12-jsonable-completion D2/D4). Pure: fresh Maps only, no input
  // mutation; the container number-variant marks are copied.
  jsonParseValue: function $jsonParseValue(v, file, line, column) {
    return $fromJsonConvert(v, file, line, column);
  },

  // jsonStringifyText: the std/json encode row — finite acyclic
  // JSON-shaped data only; unsupported carriers and nonfinite numbers
  // raise the closed JSON_STRINGIFY_UNSUPPORTED E8001 and a path-local
  // container re-entry raises the closed cycle arm. Pure and
  // non-mutating.
  jsonStringifyText: function $jsonStringifyText(v, path, file, line, column) {
    return $encodeJsonText(v, $Array.isArray(path) ? path : [], file, line, column,
      false, "", false);
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
  // exactly file/line/column. The optional expected/actual pair and frames
  // text are the complete ErrorDetail projection: each materializes only
  // when defined.
  errorValue: function $errorValue(code, message, file, line, column,
      expected, actual, frames, cause) {
    const $value = {};
    $rt.setProp($value, "$kind", "class");
    $rt.setProp($value, "$classname", "Error");
    $rt.setProp($value, "code", code);
    $rt.setProp($value, "message", message);
    if (file !== $undefined) $rt.setProp($value, "file", file);
    if (line !== $undefined) $rt.setProp($value, "line", line);
    if (column !== $undefined) $rt.setProp($value, "column", column);
    if (expected !== $undefined) $rt.setProp($value, "expected", expected);
    if (actual !== $undefined) $rt.setProp($value, "actual", actual);
    if (frames !== $undefined) $rt.setProp($value, "frames", frames);
    if (cause !== $undefined) $rt.setProp($value, "cause", cause);
    return $value;
  },

  // reifyError: total conversion of a thrown value into a tagged Error
  // value. (i) $DEALError -> rebuilt via errorValue (a fresh tagged Error
  // value with the same code/message/location plus the expected/actual
  // pair and the captured frames — the originating call chain survives
  // catch/rethrow and async reification); (ii) an already-tagged Error
  // instance passes through unchanged, same reference (rethrow
  // preservation, js-backend-runtime D7); (iii) anything else -> the
  // E8001 wrap errorValue("E8001", String(v)). Only fail throws; this
  // member is total.
  reifyError: function $reifyError(v) {
    if (v instanceof $DEALError) {
      return $rt.errorValue(v.code, v.message, v.file, v.line, v.column,
        v.expected, v.actual, v.frames, v.cause);
    }
    if (typeof v === "object" && v !== null && v.$kind === "class" && v.$classname === "Error") {
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

  // checkNull: DEAL null is the JS null and the marked JSON-decoded null
  // table (the D2 decode mark — the one language-null representation on
  // the decode path; the marked carrier is admitted as the JS null the
  // language model uses). Undefined and MISSING stay rejected
  // (deal/runtime.lua:50-55; D3).
  checkNull: function $checkNull(v, file, line, column) {
    if (v instanceof $Map && $rt.isJsonNullTable(v)) {
      return null;
    }
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

  // checkInt: the exact check_int order (deal/runtime.lua:64-83) — the
  // ±Infinity E8001 arm fires before the E8004 finite-range arm, so an
  // overflow to Infinity is E8001 and only finite values outside the
  // signed-32 range are E8004. The signed-32 gate is active from runtime
  // load: every int helper, intConvert and the RuntimeTypeMatcher int row
  // route through checkInt, direct runtime/stdlib callers included. -0
  // normalizes to 0 (the v = v + 0 step, :72). NaN/±Infinity/non-integer
  // carry expected "int" exactly like the reference _err calls; the E8004
  // range arm carries no expected/actual (nil in the reference — fields
  // absent, the _err convention).
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
    if (v < -2147483648 || v > 2147483647) {
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
  // boundary message split (deal/runtime.lua:288-296, ISSUE-0598): a
  // string that is exactly one lone surrogate code unit is the JS analog
  // of the reference's CESU-style surrogate-code-point encoding ("got
  // UTF-16 surrogate code point"); every other lone-surrogate defect is
  // the JS analog of a malformed encoding and keeps the general message.
  // Valid supplementary pairs pass; the string is returned unchanged.
  checkString: function $checkString(v, file, line, column) {
    if (typeof v !== "string") {
      $rt.fail("E8001", "expected string", file, line, column, "string", $kindOf(v));
    }
    for (let $i = 0; $i < v.length; $i++) {
      const $c = v.charCodeAt($i);
      if ($c >= 0xd800 && $c <= 0xdbff) {
        const $next = $i + 1 < v.length ? v.charCodeAt($i + 1) : -1;
        if ($next < 0xdc00 || $next > 0xdfff) {
          $rt.fail("E8001", $stringBoundaryMessage(v), file, line, column, "string", "invalid UTF-8 string");
        }
        $i++; // skip the low surrogate of a valid pair
      } else if ($c >= 0xdc00 && $c <= 0xdfff) {
        $rt.fail("E8001", $stringBoundaryMessage(v), file, line, column, "string", "invalid UTF-8 string");
      }
    }
    return v;
  },

  // checkTable: the strict split (js-backend-runtime D3) — an unmarked
  // instanceof-Map table passes; the two D2 JSON decode carriers do not:
  // a decoded JSON array is the array carrier (the std/json decode mark)
  // and a decoded JSON null is language null, so both are rejected at a
  // table boundary with the shared boundary executor's own closed tuple
  // (E8001 "expected table", expected "table", actual "array"/"null" —
  // never the plain-Map "table" label). Anything else — Array, plain
  // object, class instance, wrapper, primitives — keeps its own $kindOf
  // actual label.
  checkTable: function $checkTable(v, file, line, column) {
    if (!(v instanceof $Map)) {
      $rt.fail("E8001", "expected table", file, line, column, "table", $kindOf(v));
    }
    if ($rt.isJsonNullTable(v)) {
      $rt.fail("E8001", "expected table", file, line, column, "table", "null");
    }
    if ($rt.isJsonArrayTable(v)) {
      $rt.fail("E8001", "expected table", file, line, column, "table", "array");
    }
    return v;
  },

  // ===== Descriptor dispatch (js-backend-runtime-artifact D3/D4) =====

  // checkNullable: the check_nullable mirror (deal/runtime.lua:229-235,
  // extended to MISSING per js-backend-runtime D3) — any nil-equivalent
  // input (undefined, null, MISSING, and the JSON-decoded null table —
  // the isNilEquivalent predicate) returns DEAL null (JS null) with no
  // error; everything else delegates to checkType on the inner
  // descriptor.
  checkNullable: function $checkNullable(inner, v, file, line, column) {
    if ($rt.isNilEquivalent(v)) {
      return null;
    }
    return $rt.checkType(inner, v, file, line, column);
  },

  // checkType: canonical descriptor parse ($parse) then dispatch — the
  // check_type mirror (deal/runtime.lua:417-476) realized over the
  // canonical grammar only (js-v12-completion-architecture D3). The
  // primitive null row routes through checkNull (JS null and the
  // JSON-decoded null carrier — the two language-null representations);
  // a null descriptor and every non-string descriptor take the pinned
  // internal/parse arms (String(descriptor) mirrors Lua's
  // tostring(descriptor)). Legacy spellings ("T[]", "T|null", bare
  // class names other than the builtin "Error" atom, "@/Error",
  // "@$builtin/Error") are unparsable text and take the pinned E8001
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
      // D4). The builtin Error atom is the bare "Error".
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
  // $Array capture (never the bare spelling), plus the json-array-marked
  // Map table (the std/json decode of a JSON array, the D2 array mark —
  // ISSUE-0331 gate closure): a plain Map, class instance, or primitive
  // is rejected with "expected array". Element extraction realizes the
  // canonical "[D]" prefix form only ($arrayElementDescriptor); the
  // legacy "T[]" suffix was retired with the dialect. The marked-Map arm
  // walks the table's array-form keys 1..n in order — the encode-side
  // $arrayFormIndex walk mirrored, the reference's 1..#v walk over a
  // decoded JSON array table (integer keys 1..n on the Lua side, the
  // string keys "1".."n" of the marked Map here) — so a JSON array
  // crossing an array boundary checks its elements exactly like a real
  // Array. The real-Array walk is 0-based, mirroring the reference's
  // 1..#v walk (deal/runtime.lua:256-285) with the 1-based message
  // index; any element failure is wrapped in E8003 with the per-element
  // descriptor as expected and the element kind as actual ($elemKindOf:
  // a nested decoded array renders the reference kind "table" at this
  // projection), carrying the reference's exact message form ("array
  // element N type mismatch", deal/runtime.lua:788 — no inner-message
  // text is embedded). Success returns the value unchanged.
  checkArray: function $checkArray(descriptor, v, file, line, column) {
    const $isJsonArrayMap = !$Array.isArray(v) && $rt.isJsonArrayTable(v);
    if (!$Array.isArray(v) && !$isJsonArrayMap) {
      $rt.fail("E8001", "expected array", file, line, column, "array", $kindOf(v));
    }
    const $element = $arrayElementDescriptor(descriptor, file, line, column);
    if ($isJsonArrayMap) {
      let $max = 0;
      for (const $key of v.keys()) {
        const $index = $arrayFormIndex($key);
        if ($index !== null && $index > $max) {
          $max = $index;
        }
      }
      for (let $i = 1; $i <= $max; $i++) {
        const $value = v.get(String($i));
        try {
          $rt.checkType($element, $value, file, line, column);
        } catch ($e) {
          $rt.fail("E8003", "array element " + $i + " type mismatch", file, line, column, $element, $elemKindOf($value));
        }
      }
      return v;
    }
    for (let $i = 0; $i < v.length; $i++) {
      try {
        $rt.checkType($element, v[$i], file, line, column);
      } catch ($e) {
        $rt.fail("E8003", "array element " + ($i + 1) + " type mismatch", file, line, column, $element, $elemKindOf(v[$i]));
      }
    }
    return v;
  },

  // arrayAt: the position read of an array value checkArray admitted —
  // the carrier split is checkArray's own (a native Array first, the
  // json-array-marked Map second), so an emitted consumer sees exactly
  // the elements the boundary checked. A native Array reads v[i] (the
  // real-Array walk's 0-based position); a marked Map — the std/json
  // decode of a JSON array, whose elements are the string keys "1".."n"
  // in element order — reads the key "i+1", the same positions the
  // marked-Map check walk validates (1..max). A position outside the
  // carrier's element range yields the nil-equivalent $undefined, never
  // an error: that is the stop cell of the emitted array for-of (the
  // LuaJIT ipairs stop-at-first-nil semantics), so a deleted element or
  // a past-the-end position ends the iteration exactly like a native
  // Array's out-of-range read. Pure read: no mutation, no allocation,
  // and never a materialized copy — the carrier identity is untouched,
  // so writes and reads through the same table stay aliased.
  arrayAt: function $arrayAt(v, i) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      return v.get(String(i + 1));
    }
    return v[i];
  },

  // arrayLength: the length read of an array value checkArray admitted —
  // the carrier split is checkArray's own. A native Array reads its
  // native length; a marked Map — the std/json decode of a JSON array,
  // whose elements are the string keys "1".."n" in element order —
  // reads the highest array-form key, exactly the 1..max range the
  // marked-Map check walk validates, so the count an empty decoded array
  // reports is 0. The JVM carries the same count on Array.length and the
  // LuaJIT chunk on the decoded table's __n field. Pure read: no
  // mutation, no allocation, no materialized copy.
  arrayLength: function $arrayLength(v) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      let $max = 0;
      for (const $key of v.keys()) {
        const $index = $arrayFormIndex($key);
        if ($index !== null && $index > $max) {
          $max = $index;
        }
      }
      return $max;
    }
    return v.length;
  },

  // arraySet: the commit step of an emitted array-element write whose
  // E8002 bounds gate and element check already ran — a native Array
  // assigns the slot and a marked Map (the std/json decode of a JSON
  // array) sets the array-form key "i+1", so a write through one
  // reference is observed by every alias and by the shared JSON encode
  // walk. Returns the written value (the assignment's expression value).
  // The carrier identity is preserved; no copy, no extra mutation.
  arraySet: function $arraySet(v, i, value) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      v.set(String(i + 1), value);
      return value;
    }
    v[i] = value;
    return value;
  },

  // arrayDelete: the committed step of an emitted array-element delete
  // whose E8002 bounds gate already ran and whose append-at-length guard
  // already suppressed the write at i === length. A native Array stores
  // the T1 nil-equivalent in the slot and a marked Map sets its
  // array-form key to the same value, the nil-write of the reference
  // semantics: reads at the slot yield the nil-equivalent and the length
  // is unchanged. No mutation of any other slot; the carrier identity is
  // preserved.
  arrayDelete: function $arrayDelete(v, i) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      v.set(String(i + 1), $undefined);
      return;
    }
    v[i] = $undefined;
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

  // ===== Int arithmetic =====
  // (js-backend-runtime-artifact D5; js-backend-runtime D4 — the verbatim
  // mirror of deal/runtime.lua:478-514) Every int result flows through
  // checkInt (the D3 contract): -0 normalizes to 0, overflow to ±Infinity
  // is E8001 "expected int, got infinity" before the E8004 finite-range
  // arm, and finite values outside the signed-32 range are E8004.
  // intDiv/intMod raise E8005 on a zero divisor before any division. The
  // truncation operations use the T1 $Math capture — $Math.trunc is the
  // math.modf truncation-toward-zero analog — and every error is produced
  // through the T2 spine (fail) with the caller's (file, line, column)
  // forwarded. Number arithmetic is native IEEE in the emitter and has no
  // runtime member. Pure functions; no mutation.

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

  // intMod: E8005 on a zero divisor first, then the truncated remainder
  // rule (deal/runtime.lua:301-327): the truncated remainder alone is
  // gated (spec remainder rule — only / overflows on MIN_VALUE / -1), so
  // -2147483648 % -1 computes 0 (signed-int32 foundation I4;
  // SharedValueSemantics.int32Mod agrees). The remainder a - q * b then
  // passes through checkInt.
  intMod: function $intMod(a, b, file, line, column) {
    if (b === 0) {
      $rt.fail("E8005", "integer division by zero", file, line, column);
    }
    const $q = $Math.trunc(a / b);
    return $rt.checkInt(a - $q * b, file, line, column);
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

  // classPlan: the plan-driven class construction entry (ISSUE-0545,
  // runtime-default-evaluators-and-construction-phases D4 — the
  // __rt.class_plan_ mirror, deal/runtime.lua:1388-1434) over the
  // published runtime plan list
  //   [ { name, descriptor, optional, evaluator? }, ... ]
  // in class source order. The emitter creates the labelled
  // zero-argument evaluator closures at module load and never invokes
  // them there; the provided object literal already evaluated its field
  // expressions left-to-right at the construction call site.
  //
  // The four construction phases:
  //  1. Provided fields copy into unpublished slots. An extra provided
  //     name raises E8007 immediately — no default evaluation and no
  //     field validation has run.
  //  2. Omitted required-present defaults invoke their evaluator()
  //     exactly once per attempt, in plan order. Optional omissions
  //     stay absent ($rt.MISSING). Evaluator results are retained by
  //     reference — no generic deep copy (typed mutable literals are
  //     freshly constructed inside the generated evaluator).
  //  3. Every present field validates against its canonical descriptor
  //     in plan order through the canonical matcher rows ($rt.checkType).
  //  4. Tag $kind = "class", $classname = identity, publish.
  //
  // Failure publishes no instance (the slots are local and discarded);
  // completed evaluator or nested-construction side effects are not
  // rolled back; one attempt per construction with independent
  // unpublished slots.
  //
  // Fail-closed plan-shape validation mirrors plan_declared_names
  // (deal/runtime.lua:1337-1365): the plan must be an array of entries
  // with a string name, a string canonical descriptor, a boolean
  // optional flag, and a null-or-function evaluator; duplicate names
  // reject.
  classPlan: function $classPlan(identity, plan, provided, file, line, column) {
    if (!$Array.isArray(plan)) {
      $rt.fail("E8001", "class default plan must be a table", file, line, column);
    }
    const $declared = new $Map();
    for (let $i = 0; $i < plan.length; $i++) {
      const $entry = plan[$i];
      if ($entry === null || typeof $entry !== "object"
          || typeof $entry.name !== "string"
          || typeof $entry.descriptor !== "string"
          || typeof $entry.optional !== "boolean"
          || ($entry.evaluator !== null && $entry.evaluator !== $undefined
              && typeof $entry.evaluator !== "function")) {
        $rt.fail("E8001", "malformed class default plan entry", file, line, column);
      }
      if ($declared.has($entry.name)) {
        $rt.fail("E8001", "duplicate field in class default plan: '" + $entry.name + "'", file, line, column);
      }
      $declared.set($entry.name, true);
    }
    // Phase 1: provided fields into unpublished slots; an extra name
    // raises E8007 before any default evaluation or field validation.
    const $slots = {};
    const $providedNames = new $Map();
    if (provided !== null && provided !== $undefined) {
      if (!$isPlainObject(provided)) {
        $rt.fail("E8001", "class field values must be a table", file, line, column);
      }
      const $providedKeys = Object.keys(provided);
      for (let $i = 0; $i < $providedKeys.length; $i++) {
        const $key = $providedKeys[$i];
        if (!$declared.has($key)) {
          $rt.fail("E8007", "extra field '" + $key + "' in class '" + identity + "'", file, line, column);
        }
        $rt.setProp($slots, $key, provided[$key]);
        $providedNames.set($key, true);
      }
    }
    // Phase 2: omitted required defaults invoke their evaluator()
    // exactly once per attempt, in plan order; optional omissions stay
    // absent.
    for (let $i = 0; $i < plan.length; $i++) {
      const $entry = plan[$i];
      if ($providedNames.has($entry.name)) {
        continue;
      }
      if (typeof $entry.evaluator === "function" && !$entry.optional) {
        $rt.setProp($slots, $entry.name, $entry.evaluator());
      } else if ($entry.optional) {
        $rt.setProp($slots, $entry.name, $MISSING);
      }
    }
    // Phase 3: validate every present field against its canonical
    // descriptor in plan order through the canonical matcher rows.
    for (let $i = 0; $i < plan.length; $i++) {
      const $entry = plan[$i];
      if (!Object.prototype.hasOwnProperty.call($slots, $entry.name)) {
        continue;
      }
      if ($slots[$entry.name] === $MISSING) {
        continue;
      }
      $rt.setProp($slots, $entry.name,
        $rt.checkType($entry.descriptor, $slots[$entry.name], file, line, column));
    }
    // Phase 4: tag and publish.
    $rt.setProp($slots, "$kind", "class");
    $rt.setProp($slots, "$classname", identity);
    return $slots;
  },

  // optRead: optional-field reads map MISSING -> null; every other value —
  // a present null included — passes through unchanged (D6). Under the
  // verbatim host defaults-map seam (ISSUE-0331 gate closure) an absent
  // own property — an optional the host defaults table does not mark
  // with $rt.MISSING — reads the nil-equivalent undefined, which maps
  // to DEAL null exactly like MISSING (undefined is never a valid
  // present field value: checkNull rejects it).
  optRead: function $optRead(v) {
    return v === $MISSING || v === $undefined ? null : v;
  },

  // has: field presence for the has() intrinsic, checker-restricted to
  // optional class fields (deal/checker/TypeChecker.java:1401-1441). An
  // own-property read on a class instance: MISSING -> false; an absent
  // own property (a host-defaults table without a MISSING mark for the
  // declared optional, the verbatim defaults-map seam) reads the
  // nil-equivalent undefined -> false; any present value including
  // null -> true (the spec three-state). The Map branch is deliberately
  // absent.
  has: function $has(obj, field) {
    return obj[field] !== $MISSING && obj[field] !== $undefined;
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
      $doc = $parseJsonText(s, file, line, column);
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
    return $jsonToDocument(identity, v, fields, $path, "", file, line, column);
  },

  // ===== Wrapper factory and conversion intrinsics
  // (js-backend-runtime-artifact D7) =====

  // function: the canonical wrapper factory — a plain object (prototype
  // Object.prototype) with the $-keyed own properties $kind: "function",
  // $sig: sig (stored verbatim — signature comparison happens in
  // checkType/checkFunctionSig, which compare the string exactly), and
  // $f: f (the exact function reference — never re-wrapped,
  // identity-preserving). The optional cls argument is the private
  // execution-class tag of the semantic-IR renderer's dynamic callee
  // dispatch ("DEAL_BODY" for a lowered closure, "ADAPTER" for an
  // arity-adaptation wrapper, "HOST" for a host surface entry, a
  // host-materialized value or a conversion intrinsic); it is absent for
  // the wrappers of producers that carry no class tag. The wrapper is the
  // passive shape: function performs no checks itself; the
  // parameter/return checks live in the emitted wrapper bodies
  // (js-backend-runtime D6). All $-keys, plain syntax; no shared wrapper
  // state; the factory is pure (deal/runtime.lua:516-519).
  function: function $function(sig, f, cls) {
    const $wrapper = { $kind: "function", $sig: sig, $f: f };
    if (cls !== $undefined) {
      $wrapper.$cls = cls;
    }
    return $wrapper;
  },

  // ===== Dynamic callee dispatch (semantic-IR renderer) =====
  // The private execution-class resolution the emitted dynamic CALL/
  // ASYNC_START arms read (wiki:javascript-semantic-ir-emission,
  // js_carriers; the shared DynamicReturnBoundaryProtocol selects the
  // class path and the recorded return cell by this class). The class is
  // read from the carrier's own tag — never from a checked descriptor, a
  // callee spelling or an argument type. A lowered closure and a
  // recursive-group member carry "DEAL_BODY" (their $f is the body's own
  // invoker, so the body runs under the lexical module state it was
  // created in); an adaptation wrapper carries "ADAPTER" (the source it
  // resolved for the invocation is recorded on the carrier, and the
  // source's own tag selects the class path); every other DEAL function
  // wrapper — a host surface entry, a host-materialized value or a
  // conversion-intrinsic carrier — is the "HOST" class. A value that is
  // not a DEAL function wrapper carries no resolvable class (null), and
  // the emitted arm fails closed at the call origin.
  dynClass: function $dynClass(v) {
    if (v === $undefined || v === null || v.$kind !== "function") {
      return null;
    }
    if (v.$cls === "DEAL_BODY" || v.$cls === "ADAPTER") {
      return v.$cls;
    }
    return "HOST";
  },

  // dynFail: the dynamic dispatch's fail-closed residue — a carrier whose
  // class is not resolvable raises the pinned E8001 "expected function"
  // projection (the same row and actual-kind token the canonical
  // function-boundary check produces) at the call origin, never a guessed
  // class path and never a silent no-op.
  dynFail: function $dynFail(v, file, line, column) {
    $rt.fail("E8001", "expected function", file, line, column, "function",
      $kindOf(v));
  },
  // ===== Host ABI (js-v12-host-abi-completion D1-D5) =====

  // hostParam: one emitted HOST_PARAMETER cell — the recorded
  // DEAL_TO_HOST parameter boundary of a host-family callee (a host
  // function, a host-materialized value or a conversion intrinsic). The
  // composition is the typed wrapper's own parameter arm: the declared
  // parameter descriptor checked at the one-based position, and the
  // single E8010 "parameter {index} type mismatch: {inner}" projection
  // with the declared descriptor as expected and the value's Lua-side
  // carrier kind as actual at the call origin. The admitted value is
  // returned; a non-DEAL error propagates unchanged.
  hostParam: function $hostParam(index, descriptor, v, file, line, column) {
    try {
      $rt.checkType(descriptor, v, file, line, column);
    } catch ($e) {
      if (!($e instanceof $DEALError)) {
        throw $e;
      }
      $rt.fail("E8010", "parameter " + index + " type mismatch: " + $e.message,
        file, line, column, descriptor, $luaKindOf(v));
    }
    return v;
  },
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
  // 2. Exact arity — v1.3 has no rest parameters: E8010 "expected at
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
      $cls: "HOST",
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
            $rt.fail("E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $luaKindOf(args[$i]));
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
            $rt.fail("E8010", "return value 1 type mismatch: " + $e.message, $file, $line, $column, "null", $luaKindOf($r));
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
          $rt.fail("E8010", "return value 1 type mismatch: " + $e.message, $file, $line, $column, $retDesc, $luaKindOf($r));
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
  // await (spec-v1.3:1704).
  //
  // Converged awaited-call span (ISSUE-0599): an awaited emitted call
  // appends a second trailing span triplet — the await expression's
  // own span — after the call-site triplet, so the shape/arity/parameter
  // checks report the call site (host-async-shape-bad/value) while the
  // completion check reports the await site (host-async-bad), exactly
  // the reference's split (deal/runtime.lua host wrapper shape checks
  // with the call span; the caller's awaited-completion check with the
  // await span). The two forms are told apart by the declared arity:
  // six trailing values are the call span plus the completion span,
  // three are a lone span used for both.
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
      $cls: "HOST",
      $sig: descriptor,
      $f: async function(...args) {
        const $n = args.length;
        // The declared arity decides the trailing span group: a call
        // carrying exactly arity+6 values is the awaited form (call
        // span then completion span); every other call carrying at
        // least three trailing values is the lone call span.
        const $trailing = $n - $paramDescs.length === 6 ? 6
            : ($n >= 3 ? 3 : 0);
        const $file = $trailing > 0 ? args[$n - $trailing] : $undefined;
        const $line = $trailing > 0 ? args[$n - $trailing + 1] : $undefined;
        const $column = $trailing > 0 ? args[$n - $trailing + 2] : $undefined;
        const $completionFile = $trailing === 6 ? args[$n - 3] : $file;
        const $completionLine = $trailing === 6 ? args[$n - 2] : $line;
        const $completionColumn = $trailing === 6 ? args[$n - 1] : $column;
        const $nargs = $n - $trailing;
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
            $rt.fail("E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $luaKindOf(args[$i]));
          }
        }
        const $op = f(...args.slice(0, $nargs));
        if (!($op && typeof $op.then === "function")) {
          const $got = $op === $undefined ? "nothing" : $kindOf($op);
          $rt.fail("E8010", "host async function must return an async operation, got " + $got, $file, $line, $column, "async operation", $got);
        }
        const $v = await $op;
        return $rt.checkType($retDesc, $v, $completionFile, $completionLine, $completionColumn);
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
  // parent D5 phase order, over the host defaults passed through
  // verbatim — the preserved defaults-map seam, host-module-abi D2:
  // makeClass's extra-key gate runs against the raw host defaults and
  // raises E8007 with the canonical identity in the message, so the
  // host owns the $rt.MISSING marks of its absent optionals exactly
  // like the Lua triplet's __MISSING marks) and "<C>$fields" (the
  // declared map's field metadata). The validated META stays under the
  // declared name.
  //
  // Extra host exports are structurally dropped (the surface carries
  // only declared names plus the synthesized keys); the raw exports
  // object is never mutated. The loader never validates stdlib
  // modules (assumption a — stdlib imports keep their trusted raw
  // require path) and never invents a JS sig-table mechanism (D5).
  //
  // Converged lane contract (ISSUE-0599): the loader receives the raw
  // import specifier and the import statement's span after the declared
  // map (the emitter's call shape), so every E8011 load-time rejection
  // reports the import site and names the module — the pinned corpus
  // sidecar shape (host-missing-export), the reference's load_host
  // signature and message forms (deal/runtime.lua:1064-1135, the
  // ISSUE-0598 convergence). A caller that passes no module path keeps
  // the landed message forms without the module clause (the direct
  // runtime suite).
  loadHost: function $loadHost(rawExports, declaredMap, $modulePath, file, line, column) {
    const $inModule = typeof $modulePath === "string"
      ? " in module '" + $modulePath + "'" : "";
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
        $rt.fail("E8011", "missing host export '" + $name + "'" + $inModule, file, line, column);
      }
      if ($entry === null || typeof $entry !== "object"
          || typeof $entry.$d !== "string") {
        $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has an unsupported declared descriptor: " + String($entry === null ? null : $entry.$d), file, line, column);
      }
      const $desc = $entry.$d;
      const $parsed = $parse($desc);
      if ($entry.$k === "function") {
        if ($parsed === null || $parsed.$t !== "function") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has an unsupported declared descriptor: " + $desc, file, line, column);
        }
        let $f;
        if (typeof $v === "function") {
          $f = $v;
        } else if ($v !== null && typeof $v === "object"
            && $v.$kind === "function") {
          if ($v.$sig !== $desc) {
            $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has signature mismatch: expected " + $desc + ", got " + String($v.$sig), file, line, column, $desc, $v.$sig);
          }
          if (typeof $v.$f !== "function") {
            $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has non-function $f", file, line, column, "function", $kindOf($v.$f));
          }
          $f = $v.$f;
        } else {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " is not a function", file, line, column, "function", $kindOf($v));
        }
        $surface[$name] = $parsed.$async
            ? $rt.hostAsyncFunction($desc, $f)
            : $rt.hostFunction($desc, $f);
      } else if ($entry.$k === "class") {
        if ($parsed === null || $parsed.$t !== "class") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has an unsupported declared descriptor: " + $desc, file, line, column);
        }
        if ($v === null || typeof $v !== "object" || $v.$kind !== "class") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " is not a class meta", file, line, column, "class", $kindOf($v));
        }
        if ($v.$classname !== $desc) {
          $rt.fail("E8011", "host class export '" + $name + "'" + $inModule + " has identity mismatch: expected " + $desc + ", got " + String($v.$classname), file, line, column, $desc, $v.$classname);
        }
        const $fields = $entry.$fields;
        if (!$Array.isArray($fields)) {
          $rt.fail("E8011", "host class '" + $name + "'" + $inModule + " has malformed field metadata", file, line, column);
        }
        for (let $j = 0; $j < $fields.length; $j++) {
          const $field = $fields[$j];
          if ($field === null || typeof $field !== "object"
              || typeof $field.name !== "string") {
            $rt.fail("E8011", "host class '" + $name + "'" + $inModule + " has malformed field metadata", file, line, column);
          }
        }
        let $hostDefaults = rawExports[$name + "$defaults"];
        if ($hostDefaults === $undefined) {
          $hostDefaults = rawExports[$name + "_defaults"];
        }
        if ($hostDefaults === $undefined
            || !(typeof $hostDefaults === "function"
                || $isPlainObject($hostDefaults))) {
          $rt.fail("E8011", "host class '" + $name + "'" + $inModule + " is missing its defaults", file, line, column, "table", $kindOf($hostDefaults));
        }
        // The preserved defaults-map seam (host-module-abi D2, ISSUE-0331
        // gate closure): the host defaults — the plain object, or the
        // zero-arg function evaluated exactly once per construction —
        // pass through VERBATIM as the construction defaults. makeClass's
        // extra-key gate compares provided names against the raw host
        // defaults, so a provided name the host defaults table does not
        // carry raises E8007 with the canonical identity in the message
        // (the reference's class_ first argument, deal/runtime.lua:1293+;
        // the host owns which absent optionals its defaults table marks
        // with $rt.MISSING, exactly like the Lua triplet's __MISSING
        // marks). The former declared-optional $MISSING augmentation is
        // retired: it widened the gate and let a provided declared
        // optional absent from <C>_defaults pass where the shared
        // fixture pins E8007. A non-plain-object host defaults result
        // fails inside makeClass with the pinned E8001 "class defaults
        // must be a table" (construction-time, never load-time).
        $surface[$name] = $v;
        $surface[$name + "$new"] = function(provided, $file, $line, $column) {
          const $raw = typeof $hostDefaults === "function"
              ? $hostDefaults()
              : $hostDefaults;
          return $rt.makeClass($desc, $desc, function() {
            return $raw;
          }, provided, $file, $line, $column);
        };
        $surface[$name + "$fields"] = $fields.slice();
      } else {
        $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has an unsupported declared descriptor: " + $desc, file, line, column);
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
  // the JVM emitter's emitted scalarCompare: a codePointAt walk with
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

  // throwValue: the recorded THROW transport. A tagged Error value that
  // carries no location is a constructed Error being thrown: the failure is
  // synthesized at the THROW site's own origin (code/message preserved,
  // expected/actual/cause absent — absence stays absence, never null). An
  // already-transported value — a caught error, or one that crossed a
  // return/parameter boundary — is rethrown unchanged, so its origin,
  // expected/actual, frames and recursive cause survive catch/rethrow.
  throwValue: function $throwValue(v, file, line, column) {
    if (v !== null && typeof v === "object" && v.$kind === "class"
        && v.$classname === "Error" && v.file === $undefined
        && v.line === $undefined && v.column === $undefined) {
      return $rt.errorValue(v.code, v.message, file, line, column,
        v.expected, v.actual, v.frames, v.cause);
    }
    return v;
  },

  // ===== Implementation-module registry (semantic-IR renderer) =====
  // The private per-output runtime records of the semantic-IR renderer
  // (wiki:javascript-semantic-ir-emission, "module_linkage"): one
  // output-runtime-scoped record per recorded module identity, keyed by the
  // recorded module id, carrying the UNINITIALIZED | INITIALIZING |
  // INITIALIZED | FAILED state, the published export surface, and the saved
  // failure. The records replace no public CommonJS surface: the artifact's
  // module.exports is the identical object the record publishes, and the
  // records carry no enumerable linkage key. A successful initialization
  // returns the identical published surface; a failed one stays sticky —
  // later loads rethrow the saved failure without executing again, and the
  // unpublished exports are discarded while completed source effects remain.
  // Module init registers the record before any implementation dependency is
  // required, so the state machine is observable from the first import.
  initModule: function $initModule(id, body) {
    const $record = $executionRecord(id);
    if ($record.$state === "INITIALIZED") {
      return $record.$surface;
    }
    if ($record.$state === "FAILED") {
      throw $record.$failure;
    }
    $record.$state = "INITIALIZING";
    try {
      body();
    } catch ($e) {
      $record.$state = "FAILED";
      $record.$failure = $e;
      // The unpublished exports are discarded; completed source and host
      // effects stay exactly as they happened.
      $record.$surface = {};
      throw $e;
    }
    $record.$state = "INITIALIZED";
    return $record.$surface;
  },

  // moduleSurface: the published CommonJS surface of one module record. A
  // module that has not initialized reads the present-empty surface (never
  // a fabricated export); the importer's require initialized the owner
  // first.
  moduleSurface: function $moduleSurface(id) {
    return $executionRecord(id).$surface;
  },

  // moduleState: the record's closed state name (the sticky-failure
  // observation seam).
  moduleState: function $moduleState(id) {
    return $executionRecord(id).$state;
  },

  // publishModule: one EXPORT_PUBLISH write of one module's init walk, via
  // the own-property-safe setProp (a legal __proto__ export name creates an
  // own property).
  publishModule: function $publishModule(id, name, value) {
    return $rt.setProp($executionRecord(id).$surface, name, value);
  },

  // callExport: the one cross-module SHARED_BODY invocation (the recorded
  // export name is the callee's published function value). The caller-side
  // EXTERNAL_PARAMETER boundaries already ran; the callee's source RETURN
  // executes the single EXTERNAL_RETURN cell, so the CALL re-checks nothing.
  callExport: function $callExport(id, name, args, file, line, column) {
    const $fn = $rt.readModule(id, name);
    if ($fn === $undefined || $fn === null || $fn.$kind !== "function") {
      $rt.fail("E8001", "expected function", file, line, column,
        "function", $kindOf($fn));
    }
    return $fn.$f(...args, file, line, column);
  },

  // registerFactory: the private implementation-module linkage of one
  // exported class's CLASS_FACTORY entry (the emission design's
  // factoryEntries record, keyed by the recorded ClassFactoryId). The
  // entry is a callable taking the caller's provided field names and
  // returning the defaults-only transfer value; registration executes no
  // default and publishes no enumerable surface key, so an internal
  // transfer value is never confused with a public class value. The
  // recording module registers its entries during its own init walk,
  // before any cross-module construction can reach them.
  registerFactory: function $registerFactory(id, factoryId, fn) {
    $executionRecord(id).$factories.set(factoryId, fn);
  },

  // callFactory: the one cross-module CLASS_FACTORY invocation of a
  // SHARED_FACTORY construction. The owner module required during the
  // caller's init walk registered its entry first; an absent entry is a
  // fail-closed linkage defect carrying the caller's own call site, never a
  // silently fabricated transfer.
  callFactory: function $callFactory(id, factoryId, providedNames, file, line, column) {
    const $fn = $executionRecord(id).$factories.get(factoryId);
    if (typeof $fn !== "function") {
      $rt.fail("E8001", "expected class factory", file, line, column,
        "class factory", $kindOf($fn));
    }
    return $fn(providedNames);
  },

  // readModule: the checked read of one published export of one module
  // (the EXPORT_READ row). The reading module required the owner during its
  // own init walk, so the owner's surface is complete; an absent name reads
  // the nil-equivalent undefined, exactly like a missing table entry.
  readModule: function $readModule(id, name) {
    return $executionRecord(id).$surface[name];
  },
};

module.exports = $rt;
