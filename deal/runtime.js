"use strict";

// DEAL Runtime Library v1.5 — JavaScript backend (deal/runtime.js).
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
// Infinity or undefined is confined to these captures;
// generated code spells none of them bare (js-backend-architecture D2). Object and
// String are not in the capture list and may be spelled bare inside this
// trusted file. The native JSON object is not captured: the conforming
// scanner and the canonical text encoder are the runtime's own (DEAL v1.5
// exact JSON).
const $process = process;
const $console = console;
const $Math = Math;
const $Map = Map;
const $Array = Array;
const $Infinity = Infinity;
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
const $classFieldOrder = new WeakMap();

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
// their $kind tag before the Map/Array branches — a class instance (the
// builtin Error carrier included) projects its carried canonical class
// atom, the shared typed-boundary token of ActualKind.CLASS, so the
// "class" spelling stays the expected token of a class descriptor and
// never becomes an actual; Maps (the T1 $Map capture) are tables; Arrays
// (the T1 $Array capture) are arrays; everything else reports the
// JS typeof name.
function $kindOf($v) {
  if ($v === $undefined || $v === $MISSING) return "undefined";
  if ($v === null) return "null";
  if ($jsonNullTables.has($v)) return "null";
  if ($jsonArrayTables.has($v)) return "array";
  if ($isNumCarrier($v)) return $v.k;
  if ($v.$kind === "function") return "function";
  if ($v.$kind === "class") {
    return typeof $v.$classname === "string" ? $v.$classname : "class instance";
  }
  if ($v instanceof $Map) return "table";
  if ($Array.isArray($v)) return "array";
  if (typeof $v === "number") return "number";
  return typeof $v;
}

// $carrierKind: the host ABI's carrier-kind projection of one failing
// subject (the Lua emitted prelude's __carrierKind and the shared host-arm
// renderer): a raw number — read-side carrier or plain JS number — projects
// the number token, a language null the table token, and every other value
// keeps $kindOf's value-derived classification. The host async-shape arm
// uses this projection rather than the typed-boundary classification, so
// an integral host handle 42 reports "number" exactly like the LuaJIT and
// JVM host wrappers, never the typed-boundary "int".
function $carrierKind($v) {
  if ($v === $undefined) return "nil";
  if ($isNumCarrier($v) || typeof $v === "number") return "number";
  if ($v === null) return "table";
  return $kindOf($v);
}

// $completionKind: the async-completion boundary's actual projection (the
// Lua emitted prelude's __completionBoundaryKind): every numeric carrier is
// the single number kind — a completion value has no container slot whose
// recorded variant the await site could read — and every other value keeps
// $kindOf's classification. The host async wrapper's await-site completion
// check uses this projection so an integral completion 42 reports "number"
// exactly like the LuaJIT and JVM completion boundaries.
function $completionKind($v) {
  if (typeof $v === "number" || $isNumCarrier($v)) return "number";
  return $kindOf($v);
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
// boundary's parameter/return arms, deal/runtime.lua:983/1025): every
// table-carried value is a Lua table — the DEAL-null sentinel (the
// canonical carrier-kind projection maps language null to the table
// token, never the JS "null" spelling), Maps, Arrays,
// class instances, and the function/class wrappers included — a raw JS
// function stays "function", and primitives keep their names.
function $luaKindOf($v) {
  if ($v === $undefined) return "nil";
  if (typeof $v === "object" || typeof $v === "function") {
    return typeof $v === "function" ? "function" : "table";
  }
  return typeof $v;
}

// $rawShapeKind: the loadHost shape guards' raw-surface projection — the
// JS-side vocabulary of a raw host module value, never the language
// carrier kind: the absent marker is "nil", the JS null is "null", a
// raw number is "number" (not the typed-boundary int token), every other
// raw object is the Lua table shape and a raw function "function". The
// guards reject a host surface the loader cannot shape, so their actual
// names the JS value the host supplied rather than a DEAL carrier it
// never produced.
function $rawShapeKind($v) {
  if ($v === $undefined) return "nil";
  if ($v === null) return "null";
  if (typeof $v === "number") return "number";
  return $luaKindOf($v);
}

// ===== Canonical descriptor parser (js-v12-completion-architecture D3) =====
// $parse: the module-private canonical descriptor parser — the runtime
// realization of CanonicalRuntimeTypeDescriptor.parse (the strict
// recursive-descent grammar over Unicode scalars with complete-input
// consumption). Only canonical spellings parse: primitive names
// null|boolean|int|number|string|table; "[D]" arrays; "?D"
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
  if ($cp === 0x3F || $cp === 0x21) {
    return $parseNullable($d, $cps, $st);
  }
  if ($matchKeyword($cps, $st.$pos, "null|undefined")) {
    $st.$pos += 14;
    return { $t: "nullable", $inner: "undefined", $null: true, $undefined: false };
  }
  if ($cp === 0x28) { // '('
    return $parseFunction($d, $cps, $st, false);
  }
  if ($cp === 0x40) { // '@'
    return $parseClass($d, $cps, $st);
  }
  // Primitive keywords (disjoint, so order does not matter).
  const $keywords = ["null", "undefined", "unknown", "void", "boolean", "number", "string", "record"];
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
  const $null = $cps[$st.$pos].$cp === 0x3F;
  let $undefinedAllowed = !$null;
  $st.$pos++;
  if ($null && $st.$pos < $cps.length && $cps[$st.$pos].$cp === 0x21) {
    $undefinedAllowed = true;
    $st.$pos++;
  }
  const $innerStart = $st.$pos;
  const $inner = $parseOne($d, $cps, $st);
  if ($inner === null) return null;
  if ($inner.$t === "nullable") return null;
  if ($inner.$t === "primitive" && ($inner.$name === "unknown" || $inner.$name === "void"
      || ($null && $inner.$name === "null") || ($undefinedAllowed && $inner.$name === "undefined"))) return null;
  return { $t: "nullable", $inner: $slice($d, $cps, $innerStart, $st.$pos), $null, $undefined: $undefinedAllowed };
}

// async? "(" (descriptor ("," descriptor)*)? ")" "->" descriptor.
// No rest-parameter arm exists.
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
// produces for supplied class-field literals. Other prototypes and
// carrier kinds reject at structural construction.
function $isPlainObject($v) {
  if ($v === null || typeof $v !== "object") {
    return false;
  }
  const $proto = Object.getPrototypeOf($v);
  return $proto === Object.prototype || $proto === null;
}

// ===== Exact JSON (binary64) — scanner, classification, marks, text
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

// RFC-8259 scanning rounds every numeric lexeme to binary64. Private
// per-slot marks preserve decoded carrier provenance across host projection;
// they never denote a second language numeric type.
const $jsonNumberMarks = new WeakMap();
const $arrayDescriptors = new WeakMap();

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
  return $marks !== $undefined && $marks.get($key) === true;
}

// $numCarrier/$isNumCarrier/$numOf: the read-side number-variant carrier
// (the Lua realization's __readVar __jn carrier, deal/runtime.lua's
// emitted prelude): a decoded graph records each numeric slot's variant
// in the container marks, and a read that leaves the container
// materializes the value's own variant as this carrier, because a JS
// number cannot carry it itself. The carrier is the runtime's private
// value form on the same contract the Lua __jn table and the shared
// JVM runtime's boxed Long/Double implement: `k` is the closed variant
// ("number") and `d` the double value. The numeric consumers
// unwrap it explicitly ($numOf) wherever they read a number; `valueOf`
// and `toString` make host-visible coercions of a runtime-owned carrier
// (the generated std modules' native Math calls included) agree with the
// wrapped value. Never exported and never produced by user source.
const $numCarrier = function $numCarrier($k, $d) {
  return {
    $jn: true,
    k: $k,
    d: $d,
    valueOf: function $numCarrierValueOf() { return $d; },
    toString: function $numCarrierToString() { return String($d); },
  };
};

function $isNumCarrier($v) {
  return $v !== null && $v !== $undefined && typeof $v === "object"
    && $v.$jn === true;
}

// $numOf: the numeric value view of one operand — a read-side carrier
// unwraps to its double, every other value passes through unchanged (the
// Lua __num tolerance, so an int/number carrier and a plain JS number
// are interchangeable at every numeric consumer).
function $numOf($v) {
  return $isNumCarrier($v) ? $v.d : $v;
}

// $numVariantOf: the recorded variant of one value being committed into a
// container slot — a carrier carries its own variant, every other value
// takes the written expression's static kind (the caller passes whether
// that kind is the number variant). Mirrors __numKey's
// carrier-overrides-static rule so a slot's mark always mirrors the
// stored value's own variant.
function $numVariantOf($v, $staticIsNumber) {
  if ($isNumCarrier($v)) {
    return $v.k === "number";
  }
  return $staticIsNumber === true;
}

// $isNumericDescriptor: true for the declared int/number positions and
// their nullable forms — the host-facing scalar number ABI positions of
// one canonical descriptor text. Every other descriptor (and a
// non-string input) is false.
function $isNumericDescriptor($desc) {
  if (typeof $desc !== "string") {
    return false;
  }
  const $inner = $desc.length > 0 && $desc.charAt(0) === "?"
    ? $desc.slice(1) : $desc;
  return $inner === "number";
}

// $hostProjectArg: the host-facing projection of one checked host-call
// parameter (the JVM __hostProjectArg declared-cell rows and the LuaJIT
// __hostParamAdmit/__hostAsyncArg carrier unwrap). The crossing hands
// the raw host the declared value, never the runtime's internal
// representation:
//
//   - a declared int/number position hands the number a read-side
//     variant carrier carries, because the public host scalar ABI is the
//     host's own number;
//   - a declared array position hands the host-visible native Array the
//     declared array ABI names (the JVM __hostArrayFromDeal carrier and
//     the LuaJIT 1..n table), whatever carrier the checked value had —
//     the std/json decoded-array marked Map included;
//   - a class position hands a class instance whose declared fields are
//     projected the same way (a decoded-array field is a native Array, a
//     decoded-null field is the language null, an unwrapped numeric
//     field keeps its recorded variant), because the host reads the
//     declared fields directly (the generated $Host$... view in the JVM
//     lane);
//   - a decoded JSON null at a nullable position is the language null;
//   - a table position keeps the DEAL table carrier (the host reads it
//     through the runtime members, exactly like the JVM/JVM-lane table
//     crossing), so effects the host performs on a table parameter stay
//     observable to its caller;
//   - every other value (scalars, null, strings,
//     function wrappers, host objects) crosses unchanged.
//
// The projection runs after the declared-cell check, so an admitted
// carrier keeps its own variant at the boundary and only the
// host-facing value is re-represented ($hostProjectValue). $memo is the
// invoking call's own projection memo: the parameters of one call form
// one value graph, so two parameters that are the same DEAL container
// reach the host as the same projected container (the caller's alias
// behaviour, the reference lane's shared table).
function $hostProjectArg($desc, $v, $memo) {
  if ($isNumericDescriptor($desc)) {
    return $numOf($v);
  }
  // Only a reference value can hold a carrier (a class instance, a
  // container, a runtime wrapper); every scalar crosses as itself.
  if ($v === null || $v === $undefined || typeof $v !== "object") {
    return $v;
  }
  return $hostProjectValue($v, true, $memo);
}

// $hostProjectCompletion: the host-facing projection of one checked
// async-export completion (the JVM host-completion carrier projection
// and the LuaJIT __asyncExportCompletion projection). The crossing hands
// the host the declared value, never the runtime's internal
// representation, for every carrier the checked completion can hold:
//
//   - a read-side numeric carrier becomes the number it carries, at the
//     top level and at every nested leaf;
//   - the std/json decoded-null carrier becomes the language null (the
//     reference's __NULL projection): JS null is DEAL null, and both the
//     shared encoder and the boundary members read it as the same null
//     leaf, so a decoded object/array keeps its null slots;
//   - a native Array or a Map table is copied with every element
//     projected and its per-slot number-variant marks carried over
//     ($jsonNumberMarks), and a decoded-array marked Map keeps the array
//     mark on the copy — so the host-visible graph preserves the decoded
//     graph's array-ness and its exact int/number classification, and
//     re-encoding it through the shared walks spells exactly what the
//     DEAL-side value spells (the Lua __a/__nK carrier copy);
//   - a class instance is copied with every own property projected (the
//     declared fields and the $kind/$classname tag pair), so no numeric
//     or decode carrier survives inside a class-typed completion;
//   - every other value (scalars, null, strings,
//     function wrappers, the $MISSING sentinel, host objects) crosses
//     unchanged.
//
// A completion has no declared array form to rebuild at the top level, so
// a decoded-array carrier keeps its array mark rather than becoming a
// native Array: the value is a table-typed DEAL value whose table
// representation is the runtime's own carrier. The host-parameter
// projection ($hostProjectArg) is the crossing whose declared type names
// the array, and it selects the native-array form of the same walk.
//
// The copy is identity-preserving per source container (one projected
// container per source container), so a shared reference stays shared
// and a cyclic completion terminates instead of recursing; the walk is
// an explicit frame stack, so a finite nested completion has no
// call-stack bound; the DEAL-side value is never mutated.
function $hostProjectCompletion($v) {
  return $hostProjectValue($v, false);
}

// $hostProjectValue: the one carrier-complete DEAL-to-host projection
// walk behind both host-boundary crossings — the checked host-call
// parameters ($hostProjectArg) and the async-export completions
// ($hostProjectCompletion). Every carrier the runtime can hold is
// re-represented:
//
//   - a read-side numeric carrier becomes the number it carries, at the
//     top level and at every nested leaf (a carrier's own recorded kind
//     stays on the copied container's slot marks below);
//   - the std/json decoded-null carrier becomes the language null (the
//     reference's __NULL projection) — JS null is DEAL null, and both
//     the shared encoder and the boundary members read it as the same
//     null leaf, so a decoded object/array keeps its null slots;
//   - a native Array is copied with every element projected and its
//     per-slot number-variant marks carried over ($jsonNumberMarks), and
//     a decoded-array marked Map is either copied with its array mark
//     ($nativeArrays false) or rebuilt as the host-visible native Array
//     ($nativeArrays true) — the declared array's own identity/alias
//     behaviour: one copy per source container, the same copy for every
//     alias of it, and every copy keeps the exact int/number
//     classification, so re-encoding it through the shared walks spells
//     what the DEAL-side value spells (the Lua __a/__nK carrier copy);
//   - a class instance is copied with every own property projected (the
//     declared fields and the $kind/$classname tag pair), so no numeric
//     or decode carrier survives inside a class-typed crossing and a
//     host that reads a declared field sees the declared value;
//   - a plain table keeps the runtime's table carrier in the parameter
//     mode ($nativeArrays true — the host reads it through the runtime
//     members and its effects stay on the DEAL value) and is copied in
//     the completion mode (a completion is published to the host, so the
//     host-visible graph holds no DEAL carrier and no shared mutable
//     table is handed out);
//   - every other value (scalars, null, strings,
//     function wrappers, the $MISSING sentinel, host objects) crosses
//     unchanged.
//
// The copy is identity-preserving per source container (one projected
// container per source container), so a shared reference stays shared,
// a caller-supplied $memo extends that to every value of one crossing
// (the parameters of one host call), and a cyclic value terminates
// instead of recursing; the walk is an explicit frame stack, so a finite
// nested value has no call-stack bound; the DEAL-side value is never
// mutated.
function $hostProjectValue($v, $nativeArrays, $memo) {
  const $seen = $memo === $undefined ? new $Map() : $memo;
  const $stack = [];
  // The per-slot variant marks of one copied container: a container's
  // marks are its own stored classification, so the copy starts from the
  // same marks and the recorded kind of a stored read carrier (below)
  // overrides the position it occupies. $shift re-keys a decoded-array
  // Map's 1-based "1".."n" marks onto the 0-based "0".."n-1"
  // positions of the native Array the same slots become.
  const $carryMarks = function ($source, $copy, $shift) {
    const $marks = $jsonNumberMarks.get($source);
    if ($marks === $undefined) {
      return;
    }
    const $copied = new $Map();
    for (const $mark of $marks) {
      const $key = $mark[0];
      const $index = $shift === true ? $arrayFormIndex($key) : null;
      $copied.set($index === null ? $key : String($index - 1), $mark[1]);
    }
    $jsonNumberMarks.set($copy, $copied);
  };
  const $project = function ($value) {
    if ($isNumCarrier($value)) {
      return $value.d;
    }
    if ($Array.isArray($value)) {
      const $mapped = $seen.get($value);
      if ($mapped !== $undefined) {
        return $mapped;
      }
      const $copy = [];
      $seen.set($value, $copy);
      $carryMarks($value, $copy, false);
      if ($arrayDescriptors.has($value)) $arrayDescriptors.set($copy, $arrayDescriptors.get($value));
      $stack.push({ kind: 1, source: $value, copy: $copy, index: 0, keys: null });
      return $copy;
    }
    if ($value instanceof $Map) {
      // The decoded JSON null is the language null, not a table: it has
      // no content to copy, so it projects to the host null directly.
      if ($rt.isJsonNullTable($value)) {
        return null;
      }
      const $mapped = $seen.get($value);
      if ($mapped !== $undefined) {
        return $mapped;
      }
      if ($rt.isJsonArrayTable($value)) {
        if ($nativeArrays === true) {
          // The declared array ABI's host-facing carrier: the decoded
          // array's 1..n slots become the native Array's 0..n-1
          // positions in element order.
          const $array = [];
          $seen.set($value, $array);
          $carryMarks($value, $array, true);
          if ($arrayDescriptors.has($value)) $arrayDescriptors.set($array, $arrayDescriptors.get($value));
          let $max = 0;
          for (const $key of $value.keys()) {
            const $index = $arrayFormIndex($key);
            if ($index !== null && $index > $max) {
              $max = $index;
            }
          }
          $stack.push({ kind: 4, source: $value, copy: $array, index: 1,
            max: $max });
          return $array;
        }
        const $copy = new $Map();
        $seen.set($value, $copy);
        $rt.markJsonArrayTable($copy);
        $carryMarks($value, $copy, false);
        if ($arrayDescriptors.has($value)) $arrayDescriptors.set($copy, $arrayDescriptors.get($value));
        const $keys = [];
        for (const $key of $value.keys()) {
          $keys.push($key);
        }
        $stack.push({ kind: 2, source: $value, copy: $copy, index: 0, keys: $keys });
        return $copy;
      }
      if ($nativeArrays === true) {
        // A table position keeps the DEAL table carrier: the host reads
        // it through the runtime members and a host effect on it is
        // observable to its caller, exactly like the JVM/LuaJIT table
        // crossing (a table is passed by reference identity).
        return $value;
      }
      const $copy = new $Map();
      $seen.set($value, $copy);
      $carryMarks($value, $copy, false);
      const $keys = [];
      for (const $key of $value.keys()) {
        $keys.push($key);
      }
      $stack.push({ kind: 2, source: $value, copy: $copy, index: 0, keys: $keys });
      return $copy;
    }
    if ($value !== null && typeof $value === "object"
        && $value.$kind === "class") {
      const $mapped = $seen.get($value);
      if ($mapped !== $undefined) {
        return $mapped;
      }
      const $copy = {};
      $seen.set($value, $copy);
      $carryMarks($value, $copy, false);
      if ($classFieldOrder.has($value)) $classFieldOrder.set($copy, $classFieldOrder.get($value));
      $stack.push({ kind: 3, source: $value, copy: $copy, index: 0,
        keys: Object.keys($value) });
      return $copy;
    }
    return $value;
  };
  const $root = $project($v);
  while ($stack.length > 0) {
    const $frame = $stack[$stack.length - 1];
    if ($frame.kind === 3) {
      // A class instance: every own property (a declared field, the
      // $kind/$classname tag pair) copies through setProp, so a field
      // named __proto__ stays an own property of the copy.
      if ($frame.index >= $frame.keys.length) {
        $stack.pop();
        continue;
      }
      const $key = $frame.keys[$frame.index++];
      const $element = $frame.source[$key];
      $rt.setProp($frame.copy, $key, $project($element));
      if ($isNumCarrier($element)) {
        // The unwrapped field keeps its own variant on the copy, so the
        // class walk's declared int/number rows spell the projected
        // instance exactly like the DEAL-side instance it stands for
        // (the JVM Long/Double carrier row).
        $markNumberVariant($frame.copy, $key);
      }
      continue;
    }
    if ($frame.kind === 1) {
      if ($frame.index >= $frame.source.length) {
        $stack.pop();
        continue;
      }
      const $i = $frame.index++;
      const $element = $frame.source[$i];
      $frame.copy.push($project($element));
      if ($isNumCarrier($element)) {
        $setNumberVariant($frame.copy, String($i), $element.k === "number");
      }
      continue;
    }
    if ($frame.kind === 4) {
      // A decoded array becoming the host-visible native Array: the
      // marked Map's 1..max slots in element order, each projected, with
      // its recorded number variant re-keyed onto the native position.
      if ($frame.index > $frame.max) {
        $stack.pop();
        continue;
      }
      const $i = $frame.index++;
      const $element = $frame.source.get(String($i));
      $frame.copy.push($project($element));
      if ($isNumCarrier($element)) {
        $setNumberVariant($frame.copy, String($i - 1), $element.k === "number");
      }
      continue;
    }
    if ($frame.index >= $frame.keys.length) {
      $stack.pop();
      continue;
    }
    const $key = $frame.keys[$frame.index++];
    const $element = $frame.source.get($key);
    $frame.copy.set($key, $project($element));
    if ($isNumCarrier($element)) {
      $setNumberVariant($frame.copy, $key, $element.k === "number");
    }
  }
  return $root;
}

// $setNumberVariant/$clearNumberVariant: the container-slot mark
// maintenance of every committed write (the Lua __numKey mirror). A
// committed write records the written value's own variant, so the
// encode walks' spelling decision always mirrors the stored value's
// variant — a later non-number rewrite of the same slot clears the
// mark.
function $setNumberVariant($container, $key, $isNumber) {
  if ($isNumber === true) {
    $markNumberVariant($container, $key);
    return;
  }
  $clearNumberVariant($container, $key);
}

function $clearNumberVariant($container, $key) {
  const $marks = $jsonNumberMarks.get($container);
  if ($marks !== $undefined) {
    $marks.delete($key);
  }
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

// Binary64 conversion of a grammar-validated numeric lexeme.
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
  // A read-side variant carrier spells its own double: the canonical text
  // is a function of the value, never of the carrier (__num).
  $v = $numOf($v);
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
    return "0";
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
  // A read-side variant carrier is a number carrier on the std/json row's
  // closed vocabulary (the shared JsonStringify projection), whichever
  // variant it records.
  if (typeof $v === "number" || $isNumCarrier($v)) return "number";
  if (typeof $v === "string") return "string";
  if (typeof $v === "function" || $v.$kind === "function") return "function";
  return "table";
}

// $walkToken: the typed-boundary projection of one failing walk value
// (the walk arm's actual): an integral finite number is the int carrier,
// every other number the number carrier, an invalid scalar sequence
// invalid-unicode, and a class instance its carried canonical atom.
function $walkToken($v) {
  if ($v === $undefined || $v === $MISSING) return "undefined";
  if ($v === null) return "null";
  if (typeof $v === "boolean") return "boolean";
  // A read-side variant carrier projects its own variant (the shared
  // Value.Int/Value.Number typed-boundary tokens).
  if ($isNumCarrier($v)) return $v.k;
  if (typeof $v === "number") return "number";
  if (typeof $v === "string") {
    return $isValidScalars($v) ? "string" : "invalid-unicode";
  }
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
  const $omit = $value => $value === $undefined || $value === $MISSING
    || typeof $value === "function" || ($value !== null && typeof $value === "object" && $value.$kind === "function");
  if ($omit($v)) {
    if ($typed) $unsupported($v, $path);
    return $undefined;
  }
  const $out = [];
  const $stack = [];
  const $emit = function ($value, $valuePath, $valueVariant) {
    if ($omit($value)) {
      if ($typed) $unsupported($value, $valuePath);
      $out.push("null"); return;
    }
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
        if ($typed) $unsupported($value, $valuePath);
        $out.push("null"); return;
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
    // A read-side variant carrier (a decoded slot's materialized
    // variant) spells by its own kind, exactly like the shared model's
    // Value.Number (canonical decimal text) and Value.Int (integer
    // text): the recorded variant, not the declared position, decides.
    if ($isNumCarrier($value)) {
      const $d = $value.d;
      if ($d !== $d || $d === Infinity || $d === -Infinity) {
        if ($typed) $unsupported($value, $valuePath);
        $out.push("null"); return;
      }
      if ($value.k === "number") {
        $out.push($numText($d));
        return;
      }
      $out.push(String($d));
      return;
    }
    if ($type === "string") {
      if (!$isValidScalars($value)) $unsupported($value, $valuePath);
      $out.push($jsonEscapeText($value));
      return;
    }
    if ($type === "function"
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
      $keys.sort(($a, $b) => {
        const $ai = /^(0|[1-9][0-9]*)$/.test($a) && Number($a) < 4294967295 ? Number($a) : -1;
        const $bi = /^(0|[1-9][0-9]*)$/.test($b) && Number($b) < 4294967295 ? Number($b) : -1;
        return $ai < 0 ? ($bi < 0 ? 0 : 1) : ($bi < 0 ? -1 : $ai - $bi);
      });
      $out.push("{");
      $stack.push({ map: $value, path: $valuePath, kind: 2, index: 0, emitted: 0, keys: $keys });
      return;
    }
    if (!$typed && $type === "object" && $value.$kind === "class") {
      const $keys = $classFieldOrder.get($value);
      if (!$keys) $unsupported($value, $valuePath);
      if ($active.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $active.push($value);
      $out.push("{");
      $stack.push({ map: { get: key => $value[key] }, path: $valuePath,
        kind: 2, index: 0, emitted: 0, keys: $keys });
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
        // A marked array-shaped Map: dense indices 1..max; an absent
        // element is the internal missing value (the general emit arm
        // reports the closed nil failure).
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
        // An absent (deleted) slot is the internal missing value, never a
        // fabricated present JSON null: the shared encoder rejects it as
        // a value-representation failure at this position (the general
        // emit arm projects the pinned nil token for both rows).
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
        const $element = $frame.map.get($key);
        if (!$typed && $omit($element)) continue;
        if ($frame.emitted++ > 0) $out.push(",");
        const $elementPath = $frame.path === "" ? $key : $frame.path + "." + $key;
        $out.push($jsonEscapeText($key) + ":");
        $emit($element, $elementPath, $isNumberVariant($frame.map, $key));
        continue;
      }
      // A native Array: elements in index order; an absent (deleted)
      // element is the internal missing value and takes the same closed
      // representation failure as a decoded array's absent slot.
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
      $emit($element, $elementPath, $isNumberVariant($frame.array, String($i)));
    }
    return $out.join("");
  } finally {
    $active.length = $base;
  }
}

// ===== @jsonable runtime walkers (js-v12-jsonable-completion D2-D5) =====
// $rt.jsonFromJson/$rt.jsonToJson over the pinned field-descriptor
// shape — { name: string, jtype: "null"|"boolean"|"number"|
// "string"|"table"|"class"|"array", element?: entry, className?:
// string, fields?: entry[] | imported C$fields ref, optional: boolean,
// nullable: boolean } — entries in declaration order. fromJson decodes
// supplied fields, requires every nonoptional key, validates and publishes.
// Every failure returns DEAL null; toJson serializes declared fields in
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
    case "undefined": $base = "undefined"; break;
    case "boolean": $base = "boolean"; break;
    case "number": $base = "number"; break;
    case "string": $base = "string"; break;
    case "table": $base = "record"; break;
    case "class": $base = $entry.className; break;
    case "array": $base = "[" + $jsonEntryDescriptor($entry.element) + "]"; break;
    default: return null;
  }
  if ($base === "undefined" && $entry.nullable) return "null|undefined";
  return ($entry.nullable ? "?" : "") + ($entry.undefinedable && $base !== "undefined" ? "!" : "") + $base;
}

// Decode a structural class without hooks or input mutation. Required
// omissions, extras and invalid nested values reject the entire document.
// The public caller converts every thrown validation failure to DEAL null.
function $jsonFromDocument($identity, $doc, $fields, $file, $line, $column) {
  // Top-level gate (D3): only a JSON object may decode; a scalar, JSON
  // null, or an array document — the empty array included — returns the
  // DEAL null. The class helper accepts exactly its specified object
  // shape, so no root or nested class value is an empty-array collapse.
  if ($doc === null || typeof $doc !== "object" || $Array.isArray($doc)) {
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
  // Required fields must be provided. Missing optionals remain absent;
  // no evaluator or host hook runs during decoding.
  for (const $entry of $fields) {
    if (!$entry.optional && !$provided.has($entry.name)) return null;
  }
  const $instance = {};
  for (let $i = 0; $i < $fields.length; $i++) {
    const $entry = $fields[$i];
    const $name = $entry.name;
    if ($provided.has($name)) {
      $rt.setProp($instance, $name, $provided.get($name));
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
  $classFieldOrder.set($instance, $fields.map($field => $field.name));
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
    case "number":
      return $rt.checkNumber($raw, $file, $line, $column);
    case "table":
      if (!$isPlainObject($raw)) {
        $rt.fail("E8001", "expected table", $file, $line, $column, "table", $kindOf($raw));
      }
      return $rt.jsonParseValue($raw, $file, $line, $column);
    case "class":
      // The nested class value is a JSON object, exactly the root gate's
      // own shape: an array value of any length, the empty array
      // included, is a validation failure (no empty-object collapse).
      if (!$isPlainObject($raw)) {
        $rt.fail("E8001", "expected class instance", $file, $line, $column, "class", $kindOf($raw));
      }
      if (typeof $entry.className !== "string" || !$Array.isArray($entry.fields)) {
        $rt.fail("E8001", "malformed field descriptors", $file, $line, $column);
      }
      const $nested = $jsonFromDocument($entry.className, $raw,
        $entry.fields, $file, $line, $column);
      if ($nested === null) {
        // A nested decode has no successful-null outcome: every gate and
        // every validation failure collapses to the language null of the
        // whole C$fromJson call (the public entry's own catch), so a
        // nullable class field or nullable class-array element never
        // absorbs a failed nested decode as a present null — only a JSON
        // null token reaches the null arm above, and no partial instance
        // publishes.
        $rt.fail("E8001", "nested @jsonable decode failed", $file, $line,
          $column);
      }
      return $nested;
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
      if (typeof $entry.elementDescriptor === "string") {
        $arrayDescriptors.set($arr, $entry.elementDescriptor);
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
    // The field's recorded numeric variant: a projected class-instance
    // completion records the variant of every numeric field it unwrapped
    // ($hostProjectCompletion), so the declared int/number rows spell the
    // copy exactly like the DEAL-side instance. A DEAL-side instance
    // records nothing and keeps the declared-type spelling.
    const $fieldVariant = $isNumberVariant($v, $name) ? true : $undefined;
    const $encoded = $jsonToValue($entry, $value, $path, $fieldPathFor($fieldPath, $name),
      $file, $line, $column, $fieldVariant);
    if ($encoded !== $undefined) $parts.push($jsonEscapeText($name) + ":" + $encoded);
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
// encoder with the typed-boundary projection. $variant is the recorded
// variant of the walked position: $true for the number classification
// and $false for the int classification of a decoded-array slot or of a
// host-projected class-instance field ($hostProjectCompletion records
// the variant of every field it unwrapped), $undefined outside such a
// record. The "int" and "number" rows consult it for a plain number. A
// read-side variant carrier is judged by its own recorded kind first
// (the shared model's Value.Int/Value.Number rows, never the declared
// position).
function $jsonToValue($entry, $value, $path, $fieldPath, $file, $line, $column,
    $variant) {
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
  if ($value === $undefined && $entry.undefinedable) return $undefined;
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
    case "number":
      if ($isNumCarrier($value)) {
        // The value's own variant decides the spelling: an int-variant
        // carrier keeps the integer spelling and a number-variant carrier
        // the canonical decimal text, exactly the shared
        // Value.Int/Value.Number rows.
        const $carried = $value.d;
        if ($carried !== $carried
            || $carried > 1.7976931348623157e308
            || $carried < -1.7976931348623157e308) return "null";
        return $carried === 0 ? "0" : $numText($carried);
      }
      if (typeof $value !== "number") $walkFail();
      if ($value !== $value || $value > 1.7976931348623157e308 || $value < -1.7976931348623157e308) return "null";
      // A decoded-array slot keeps the decoded graph's own
      // classification: the number-variant slot (a fractional token, or
      // an integral token written in fraction/exponent form) spells the
      // canonical decimal text, while the int-classified slot keeps the
      // integer spelling — exactly the $encodeJsonText rule and exactly
      // the shared model's Value.Number rows. A native Array
      // or a declared scalar position records no variant and keeps the
      // declared number spelling.
      return $value === 0 ? "0" : $numText($value);
    case "table":
      // The declared table row walks the JSON-shaped content only: a
      // class instance, a stored function, an absent marker, an
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
        const $isDecoded = $rt.isJsonArrayTable($value);
        const $wantsNumber = $entry.element.jtype === "number";
        const $length = $rt.arrayLength($value);
        for (let $i = 0; $i < $length; $i++) {
          const $element = $rt.arrayAt($value, $i, $wantsNumber);
          const $elementPath = $fieldPath + "[" + $i + "]";
          if ($element === $undefined && !$entry.element.undefinedable) {
            // A deleted slot is language undefined, not present null.
            $rt.fail("E8001", "value at " + $elementPath
              + " is not JSON serializable: undefined", $file, $line, $column,
              $undefined, "undefined");
          }
          // A decoded carrier records its per-slot number variant under
          // the same 1-based key $rt.arrayAt reads (only the number
          // variant is marked) and materializes a variant carrier for the
          // positions whose variant the read's declared kind cannot
          // express, so the element row reaches the same spelling the
          // shared model's Value.Int/Value.Number carry; a native Array
          // records no decoded variant and keeps the declared-type
          // spelling.
          const $elementVariant = $isDecoded && typeof $element === "number"
            ? $isNumberVariant($value, String($i + 1))
            : $undefined;
          const $encoded = $jsonToValue($entry.element, $element, $path, $elementPath,
            $file, $line, $column, $elementVariant);
          $parts.push($encoded === $undefined ? "null" : $encoded);
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

// $numberHex: the canonical hex-float spelling of one IEEE-754 double —
// the JS mirror of deal/semantic's CanonicalJson#numberHex
// (Double.toHexString) that the LuaJIT prelude's __numHex and the JVM
// runtime's Double.toHexString also realize: NaN, both infinities and
// both zeros carry their Java spellings, a subnormal its fixed-exponent
// denormal form (the 13-digit fraction with its trailing zeros stripped
// and the pinned p-1022 exponent), every other finite nonzero value its
// shortest exact "0x1.<digits>p<exp>" form. The significand is recovered
// by exact power-of-two scaling (no rounding: every intermediate keeps
// the value's own 52 fraction bits), so the digits never drift from the
// value. The single renderer of the stdlib closed-arm actuals that spell
// a number (SQRT_NEGATIVE).
function $numberHex($v) {
  if ($v !== $v) return "NaN";
  if ($v === $Infinity) return "Infinity";
  if ($v === -$Infinity) return "-Infinity";
  const $sign = ($v < 0 || ($v === 0 && 1 / $v < 0)) ? "-" : "";
  const $magnitude = $v < 0 ? -$v : $v;
  if ($magnitude === 0) return $sign + "0x0.0p0";
  if ($magnitude < $Math.pow(2, -1022)) {
    // A subnormal keeps Java's fixed-exponent denormal spelling: two
    // exact power-of-two scalings recover its integer significand
    // (magnitude * 2^1074), never the normalized 2^52-scale form.
    const $scaled = $magnitude * $Math.pow(2, 537) * $Math.pow(2, 537);
    return $sign + "0x0." + $hexDigits($scaled) + "p-1022";
  }
  let $exponent = $Math.floor($Math.log2($magnitude));
  if ($magnitude >= $Math.pow(2, $exponent + 1)) {
    $exponent += 1;
  } else if ($magnitude < $Math.pow(2, $exponent)) {
    $exponent -= 1;
  }
  const $fraction =
    ($magnitude / $Math.pow(2, $exponent) - 1) * 4503599627370496;
  return $sign + "0x1." + $hexDigits($fraction) + "p" + $exponent;
}

// $hexDigits: the 13 hex digits of one exact integer fraction of a
// double's significand, trailing zeros stripped, never empty ("0" for a
// zero fraction — Java's "0x1.0p<exp>").
function $hexDigits($fraction) {
  let $text = $fraction.toString(16);
  while ($text.length < 13) $text = "0" + $text;
  while ($text.length > 1 && $text.charAt($text.length - 1) === "0") {
    $text = $text.slice(0, $text.length - 1);
  }
  return $text;
}

const $rt = {
  MISSING: $MISSING,
  NULL: null,
  undefined: $undefined,

  // numberHex: the canonical hex-float spelling (the member the shipped
  // std/math realization's SQRT_NEGATIVE actual goes through).
  numberHex: function $numberHexPublic(v) {
    return $numberHex(v);
  },

  // numOf: the emitted numeric value view (the same private unwrap the
  // numeric runtime members use) — a read-side number-variant carrier
  // reduces to its double and every other value passes through, so an
  // emitted strict equality of two numeric operands compares by value
  // while the carrier keeps its variant everywhere else (the Lua __num
  // tolerance on the JS lane). A total, pure function.
  numOf: function $numOfPublic(v) {
    return $numOf(v);
  },

  // isNilEquivalent: the nil-equivalent predicate of the null-admitting
  // boundaries and the nullable equality forms. DEAL null is the JS null
  // and the JSON-decoded null is the marked null table (the D2 decode
  // mark), so both are nil-equivalent; undefined and MISSING stay the
  // absent markers (the three-state optional fields keep their own
  // distinction through the presence map).
  isNilEquivalent: function $isNilEquivalent(v) {
    return v === null || (v instanceof $Map && $rt.isJsonNullTable(v));
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

  // ===== Exact JSON members (binary64 numbers) =====

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
    $classFieldOrder.set($value, ["code", "message"]);
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

  // checkNumber: NaN and ±Infinity pass (spec §Numeric overflow). A
  // read-side variant carrier is the number value itself (the shared
  // number row admits an int-variant Value.Int unchanged), so it passes
  // and keeps its variant.
  checkNumber: function $checkNumber(v, file, line, column) {
    if ($isNumCarrier(v)) {
      return v;
    }
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
  typeOf: function(v) {
    if (v === $undefined || v === $MISSING) return "undefined";
    if (v === null || $jsonNullTables.has(v)) return "object";
    if ($isNumCarrier(v)) return "number";
    if (v.$kind === "function") return "function";
    return typeof v;
  },

  isInstance: function(v, descriptor) {
    return v !== null && v !== $undefined && v.$kind === "class" && v.$classname === descriptor;
  },

  assertType: function $assertType(descriptor, v, file, line, column) {
    try {
      return $rt.checkType(descriptor, v, file, line, column);
    } catch (error) {
      if (!(error instanceof $DEALError)) throw error;
      let parsed = $parse(descriptor);
      while (parsed.$t === "nullable") parsed = $parse(parsed.$inner);
      let kind = parsed.$t === "primitive" ? parsed.$name : parsed.$t;
      if (kind === "record") kind = "table";
      const text = kind === "class" ? "class instance" : kind;
      $rt.fail("E8001", "expected " + text, file, line, column, kind, $kindOf(v));
    }
  },

  checkType: function $checkType(descriptor, v, file, line, column) {
    if (v === $MISSING) v = $undefined;
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
      if ($parsed.$name === "number") {
        return $rt.checkNumber(v, file, line, column);
      }
      if ($parsed.$name === "string") {
        return $rt.checkString(v, file, line, column);
      }
      if ($parsed.$name === "undefined" || $parsed.$name === "void") {
        if (v === $undefined) return v;
        $rt.fail("E8001", "expected undefined", file, line, column, "undefined", $kindOf(v));
      }
      if ($parsed.$name === "unknown") {
        const kind = $kindOf(v);
        if (typeof v === "string") return $rt.checkString(v, file, line, column);
        if (["null", "undefined", "boolean", "number", "table", "array"].includes(kind)
            || (v !== null && v !== $undefined && (v.$kind === "class"
                || (kind === "function" && typeof v !== "function")))) return v;
        $rt.fail("E8001", "unsupported unknown ingress", file, line, column, "unknown", kind);
      }
      if ($parsed.$name === "record") {
        return $rt.checkTable(v, file, line, column);
      }
      $rt.fail("E8001", "unknown primitive type: " + $parsed.$name, file, line, column);
    }
    if ($parsed.$t === "nullable") {
      if ($parsed.$null && (v === null || $jsonNullTables.has(v))) return null;
      if ($parsed.$undefined && v === $undefined) return v;
      return $rt.checkType($parsed.$inner, v, file, line, column);
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
  tagArray: function(v, elementDescriptor) { $arrayDescriptors.set(v, elementDescriptor); return v; },

  checkArray: function $checkArray(descriptor, v, file, line, column) {
    const $isJsonArrayMap = !$Array.isArray(v) && $rt.isJsonArrayTable(v);
    if (!$Array.isArray(v) && !$isJsonArrayMap) {
      $rt.fail("E8001", "expected array", file, line, column, "array", $kindOf(v));
    }
    const $element = $arrayElementDescriptor(descriptor, file, line, column);
    const $persistent = $arrayDescriptors.get(v);
    if ($persistent !== $undefined && $persistent !== $element)
      $rt.fail("E8001", "array element descriptor mismatch", file, line, column, descriptor, "array");
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

  // arrayElementCheck: the ARRAY_ELEMENT_DESCRIPTOR cell of one array
  // literal element (docs/spec-v1.5.md Runtime diagnostics: E8003 is
  // "array element type check failed"). The element's descriptor check
  // runs first and its admitted value is the element the literal stores;
  // a failing element is reported as the E8003 row with the 1-based
  // element index, the element descriptor as expected and the element's
  // reference kind as actual ($elemKindOf) — exactly the projection the
  // whole-array checkArray walk and the LuaJIT/JVM element arms carry, so
  // one corpus case observes one code, message and field pair on every
  // lane. Pure: no mutation of the element or the container.
  arrayElementCheck: function $arrayElementCheck(descriptor, oneBasedIndex, v, file, line, column) {
    try {
      return $rt.checkType(descriptor, v, file, line, column);
    } catch ($e) {
      $rt.fail("E8003", "array element " + oneBasedIndex + " type mismatch", file, line, column, descriptor, $elemKindOf(v));
    }
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
  // Array's out-of-range read. Pure read: no mutation, no allocation
  // beyond the read-side variant carrier, and never a materialized copy —
  // the carrier identity is untouched, so writes and reads through the
  // same table stay aliased.
  //
  // The decoded carrier's scalar slot materializes the value's own
  // variant as a read-side carrier (the Lua emitted prelude's __readVar
  // materialization, the same rule the reference value model applies): a
  // number-marked slot yields the number carrier and an int-marked slot
  // yields the int carrier exactly when the read's declared element kind
  // is the number variant ($wantsNumber, the isNumberKind(staticKind)
  // flag of the emitted lanes) — the two positions whose variant the JS
  // number cannot express by itself. Every other position returns the
  // stored value, so a real Array keeps the representation its declared
  // element type gave it and a decoded slot whose variant matches its
  // declared kind stays a plain number.
  arrayAt: function $arrayAt(v, i, wantsNumber) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      const $key = String(i + 1);
      const $element = v.get($key);
      return $element;
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
  // The committed write also records the written value's own variant on
  // the slot (the Lua __numKey mirror): the written expression's static
  // element kind decides for a plain number ($isNumber), a read-side
  // variant carrier's own kind otherwise, and a non-number write clears
  // the mark — so a decoded slot never keeps its decode-time variant
  // after a rewrite and the encode walks spell by the stored value. The
  // carrier identity is preserved; no copy, no extra mutation.
  arraySet: function $arraySet(v, i, value, isNumber) {
    const $variant = $numVariantOf(value, isNumber);
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      const $key = String(i + 1);
      v.set($key, value);
      $setNumberVariant(v, $key, $variant);
      return value;
    }
    v[i] = value;
    $setNumberVariant(v, String(i), $variant);
    return value;
  },

  // arrayDelete: the committed step of an emitted array-element delete
  // whose E8002 bounds gate already ran and whose append-at-length guard
  // already suppressed the write at i === length. A native Array stores
  // the T1 nil-equivalent in the slot and a marked Map sets its
  // array-form key to the same value, the nil-write of the reference
  // semantics: reads at the slot yield the nil-equivalent and the length
  // is unchanged. The slot's number-variant mark is cleared — the slot
  // now holds the internal missing value, never a classified number. No
  // mutation of any other slot; the carrier identity is preserved.
  arrayDelete: function $arrayDelete(v, i) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      const $key = String(i + 1);
      v.set($key, $undefined);
      $clearNumberVariant(v, $key);
      return;
    }
    v[i] = $undefined;
    $clearNumberVariant(v, String(i));
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

  // ===== Class construction (js-backend-runtime-artifact D6) =====

  // Structural construction over declaration-order {name, descriptor, optional}
  // entries. Provided expressions have already evaluated in source order.
  // Reject extras and required omissions, validate unpublished slots, then tag.
  // Supplied references retain identity; failures publish nothing and do not
  // roll back completed effects. Absent optionals store MISSING.
  registerClassFields: function(value, fields) { $classFieldOrder.set(value, fields); return value; },

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
          || typeof $entry.optional !== "boolean") {
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
    // Required presence is independent of nullability.
    for (let $i = 0; $i < plan.length; $i++) {
      const $entry = plan[$i];
      if ($providedNames.has($entry.name)) {
        continue;
      }
      if (!$entry.optional) {
        $rt.fail("E8001", "missing required field '" + $entry.name + "' in class '" + identity + "'", file, line, column);
      }
      $rt.setProp($slots, $entry.name, $MISSING);
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
    $classFieldOrder.set($slots, plan.map(entry => entry.name));
    return $slots;
  },

  // Optional reads map absent values to null and retain every present value.
  optRead: function $optRead(v) {
    return v === $MISSING ? $undefined : v;
  },

  // Presence is independent of nullability: absent is false, present null true.
  // The checker restricts has to optional class fields.
  has: function $has(obj, field) {
    if (obj instanceof $Map) return obj.has(field);
    return Object.prototype.hasOwnProperty.call(obj, field) && obj[field] !== $MISSING;
  },

  // ===== @jsonable runtime walkers (js-v12-jsonable-completion D2-D5) =====

  // Decode only object documents. Malformed text, extra keys, required
  // omissions and invalid supplied/nested values return null without mutation
  // or partial publication. Legacy hooks are never read.
  jsonFromJson: function $jsonFromJson(identity, fields, s, file, line, column) {
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
      return $jsonFromDocument(identity, $doc, fields, file, line, column);
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
  // 2. Exact arity — no rest parameters: E8010 "expected at
  //    least N arguments, got M" / "expected N arguments, got M" (the
  //    reference's arms).
  // 3. Each parameter checks against the declared parameter descriptor
  //    through the canonical matcher rows (checkType); every failure —
  //    wrong kind, array carrier/element mismatches, function $sig
  //    deltas, class nominal identity, E8004 int range, unpaired
  //    string surrogates — re-raises E8010 "parameter {i} type
  //    mismatch: {inner}" with the declared descriptor as expected and
  //    $kindOf(arg) as actual (the reference's pcall composition).
  // 4. f is invoked exactly once with the user arguments; a declared
  //    int/number position is projected to the number its read-side
  //    variant carrier carries (the host-facing scalar ABI — the JVM
  //    __hostProjectArg numeric row and the LuaJIT __hostParamAdmit
  //    unwrap), so the raw host never observes the internal carrier
  //    object; a function-typed argument is the checked DEAL wrapper
  //    delivered unchanged (DEAL→host adaptation, D2 — the host invokes
  //    through .$f).
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
        const $checkedArgs = [];
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          try {
            $checkedArgs.push($rt.checkType($paramDescs[$i], args[$i], $file, $line, $column));
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            $rt.fail("E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $luaKindOf(args[$i]));
          }
        }
        // The raw host observes the declared host-facing value: each
        // declared position is projected to the value its declared ABI
        // names (an int/number to the number its read-side variant
        // carrier carries, an array to the host-visible native Array, a
        // class instance to a copy whose declared fields are projected
        // the same way), never the runtime's internal carrier object. The
        // projection reads the checked value, exactly like the JVM
        // wrapper's __hostParamCheck-then-__hostProjectArg sequence, so a
        // nullable position's admitted language-null representation
        // reaches the raw host as its declared value; one memo covers the
        // whole argument list, so aliased parameters stay aliased.
        const $hostArgs = [];
        const $argMemo = new $Map();
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          $hostArgs.push($hostProjectArg($paramDescs[$i], $checkedArgs[$i],
            $argMemo));
        }
        const $r = f(...$hostArgs);
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
        if ($r === $undefined && $retDesc === "void") return $undefined;
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
  // await (spec-v1.5.md, async export invocation).
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
        const $checkedArgs = [];
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          try {
            $checkedArgs.push($rt.checkType($paramDescs[$i], args[$i], $file, $line, $column));
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            $rt.fail("E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $luaKindOf(args[$i]));
          }
        }
        // The raw async host observes the declared host-facing value at
        // every declared parameter (the sync crossing's same checked-value
        // projection, with one memo for the whole argument list).
        const $hostArgs = [];
        const $argMemo = new $Map();
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          $hostArgs.push($hostProjectArg($paramDescs[$i], $checkedArgs[$i],
            $argMemo));
        }
        const $op = f(...$hostArgs);
        if (!($op && typeof $op.then === "function")) {
          const $got = $op === $undefined ? "nothing" : $carrierKind($op);
          $rt.fail("E8010", "host async function must return an async operation, got " + $got, $file, $line, $column, "async operation", $got);
        }
        const $v = await $op;
        try {
          return $rt.checkType($retDesc, $v, $completionFile,
            $completionLine, $completionColumn);
        } catch ($completionError) {
          if ($completionError instanceof $DEALError
              && $completionError.actual === $kindOf($v)
              && $completionError.actual !== $completionKind($v)) {
            throw new $DEALError($completionError.code,
              $completionError.message, $completionError.file,
              $completionError.line, $completionError.column,
              $completionError.expected, $completionKind($v));
          }
          throw $completionError;
        }
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
  //               $fields: [ { name, $d, optional, nullable }, ... ] }
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
  // the declared $d, else E8011). The loader synthesizes "<C>$new"
  // through structural classPlan and "<C>$fields" from declaration metadata.
  // No defaults export or legacy hook is read. The validated META stays
  // under the declared name.
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
      $rt.fail("E8011", "host module declarations must be a table", $undefined, $undefined, $undefined, "table", $rawShapeKind(declaredMap));
    }
    if (rawExports === null || typeof rawExports !== "object") {
      $rt.fail("E8011", "host module did not return a module object", $undefined, $undefined, $undefined, "table", $rawShapeKind(rawExports));
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
            $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has non-function $f", file, line, column, "function", $rawShapeKind($v.$f));
          }
          $f = $v.$f;
        } else {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " is not a function", file, line, column, "function", $rawShapeKind($v));
        }
        $surface[$name] = $parsed.$async
            ? $rt.hostAsyncFunction($desc, $f)
            : $rt.hostFunction($desc, $f);
      } else if ($entry.$k === "class") {
        if ($parsed === null || $parsed.$t !== "class") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has an unsupported declared descriptor: " + $desc, file, line, column);
        }
        if ($v === null || typeof $v !== "object" || $v.$kind !== "class") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " is not a class meta", file, line, column, "class", $rawShapeKind($v));
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
        const $classPlan = $fields.map($field => ({name: $field.name,
          descriptor: $field.$d, optional: $field.optional}));
        // Construct against declared structural fields, never raw host hooks.
        $surface[$name] = $v;
        $surface[$name + "$new"] = function(provided, $file, $line, $column) {
          return $rt.classPlan($desc, $classPlan, provided, $file, $line, $column);
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
  // column; a valid completion yields { $ok: true, $value } with the
  // checked value projected to the host-facing representation (a
  // read-side numeric carrier becomes the number it carries at the top
  // level and at every nested container leaf; the projection copies
  // containers identity-preservingly and mutates nothing). No retry, no
  // second invocation, no mutation. The member is $rt-only: DEAL
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
      // The checked completion crosses to the host in its declared
      // host-facing representation ($hostProjectCompletion): a read-side
      // numeric carrier becomes the number it carries at the top level
      // and at every nested leaf, a decoded JSON null becomes the
      // language null, the decoded-array carrier and every container
      // copy keep their number-variant marks, and a class instance is
      // copied field by field — so the invoker never exposes the
      // runtime's internal carrier objects to its host caller and the
      // host-visible graph keeps the decoded value's own classification
      // (the JVM/LuaJIT completion crossing's projection). The checked
      // value is never mutated.
      return { $ok: true,
        $value: $hostProjectCompletion($rt.checkType(returnDescriptor, $v)) };
    } catch ($e) {
      return { $ok: false, $error: $rt.reifyError($e) };
    }
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
  // carries no location is a constructed Error being thrown: the THROW
  // site's own origin materializes ON THE IDENTICAL ERROR VALUE (the spec's
  // reference-identical class instance, so the catch binding and every
  // alias are one object and a later field mutation is visible through all
  // of them), with code/message preserved and expected/actual/cause left
  // absent — absence stays absence, never null. An already-transported
  // value — a caught error, or one that crossed a return/parameter boundary
  // — is rethrown unchanged, so its origin, expected/actual, frames and
  // recursive cause survive catch/rethrow.
  throwValue: function $throwValue(v, file, line, column) {
    if (v !== null && typeof v === "object" && v.$kind === "class"
        && v.$classname === "Error" && v.file === $undefined
        && v.line === $undefined && v.column === $undefined) {
      $rt.setProp(v, "file", file);
      $rt.setProp(v, "line", line);
      $rt.setProp(v, "column", column);
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
