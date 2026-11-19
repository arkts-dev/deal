"use strict";

// DEAL Runtime Library v1.6 — JavaScript backend (deal/runtime.js).
// The single hand-written CommonJS runtime module exporting $rt, the JS
// analog of deal/runtime.lua, plus contextual JSON reconstruction,
// builtin serialization, the production async-export invoker,
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
// trusted file. Only terminal diagnostic quoting captures JSON.stringify;
// language JSON scanning and canonical encoding remain the runtime's own
// conforming algorithms (DEAL v1.6 exact JSON).
const $process = process;
const $console = console;
const $Math = Math;
const $Map = Map;
const $Array = Array;
const $Infinity = Infinity;
const $undefined = void 0;
// Fixed at runtime load; never consult host state during diagnostic enrichment.
const $runtimeDiagnostics = $process.env.DEAL_DIAGNOSTICS_JSON === "1";
const $jsonQuote = JSON.stringify;
const $ownDescriptor = Object.getOwnPropertyDescriptor;
const $diagnosticErrors = new WeakSet();
const $diagnosticErrorHas = $diagnosticErrors.has.bind($diagnosticErrors);
const $diagnosticErrorAdd = $diagnosticErrors.add.bind($diagnosticErrors);
const $diagnosticJson = new WeakMap();
const $diagnosticJsonGet = $diagnosticJson.get.bind($diagnosticJson);
const $diagnosticJsonSet = $diagnosticJson.set.bind($diagnosticJson);
const $diagnosticDecode = new WeakMap();
const $diagnosticDecodeGet = $diagnosticDecode.get.bind($diagnosticDecode);
const $diagnosticDecodeSet = $diagnosticDecode.set.bind($diagnosticDecode);
const $diagnosticHost = new WeakMap();
const $diagnosticHostGet = $diagnosticHost.get.bind($diagnosticHost);
const $diagnosticHostSet = $diagnosticHost.set.bind($diagnosticHost);
const $diagnosticArray = new WeakMap();
const $diagnosticArrayGet = $diagnosticArray.get.bind($diagnosticArray);
const $diagnosticArraySet = $diagnosticArray.set.bind($diagnosticArray);
const $descriptorHasOwn = Object.prototype.hasOwnProperty.call.bind(Object.prototype.hasOwnProperty);
const $diagnosticFinite = Number.isFinite;
const $diagnosticSlice = String.prototype.slice.call.bind(String.prototype.slice);
const $diagnosticCharCode = String.prototype.charCodeAt.call.bind(String.prototype.charCodeAt);
const $diagnosticNumberText = Number.prototype.toString.call.bind(Number.prototype.toString);
const $diagnosticPow = Math.pow, $diagnosticFloor = Math.floor, $diagnosticLog2 = Math.log2;

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
    $diagnosticErrorAdd(this);
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
// Private WeakSets distinguish marked host array/null carriers from Records.
// Boundary checks, host projections and serialization consume the same marks;
// contextual JSON.parse itself reconstructs native arrays and language null.
// Query/mark adapters expose no WeakSet or forgeable user-string metadata.
const $jsonArrayTables = new WeakSet();
const $jsonNullTables = new WeakSet();
const $classFieldOrder = new WeakMap();
const $hostClassPlans = new Map();
const $instanceClassPlans = new WeakMap();
const $hostArrayViews = new WeakMap();
const $hostArraySources = new WeakMap();

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
// decoded-array provenance mark) — an element carrying that mark
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

function $parse($descriptor, $resultPosition = false) {
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
  const $node = $parseOne($descriptor, $cps, $st, $resultPosition);
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
function $parseOne($d, $cps, $st, $resultPosition = false) {
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
      if ($name === "void" && !$resultPosition) return null;
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
  const $ret = $parseOne($d, $cps, $st, true);
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

// ===== Exact JSON (binary64) — scanner, contextual reconstruction and encoder =====
// Scanning owns grammar, decoded-key duplicates and first-bad-byte reasons.
// Contextual reconstruction publishes fresh Records, native arrays and nominal
// instances. Encoding validates reached declared positions and detects path-local
// cycles without invoking hooks or mutating input storage.

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
const $arrayDeleted = new WeakMap();

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
//     the JSON decoded-array marked Map included;
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
//   - the JSON decoded-null carrier becomes the language null (the
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
//   - the JSON decoded-null carrier becomes the language null (the
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
      if ($nativeArrays !== true || $hostArraySources.has($value)) return $value;
      const $cached = $hostArrayViews.get($value);
      if ($cached !== $undefined) return $cached;
      // Parsed arrays and source arrays share the same live host contract.
      // The original storage is the target: native length descriptors remain
      // coherent, while rejected reflection cannot first damage that storage.
      const $index = key => typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key)
        && Number(key) < 4294967295;
      const $view = new Proxy($value, {
        get(target, key, receiver) {
          if ($index(key)) return $project(Reflect.get(target, key));
          return Reflect.get(target, key, receiver);
        },
        set(target, key, member) {
          if (key === "length") {
            if (!Number.isInteger(member) || member < 0 || member > target.length) return false;
            return Reflect.set(target, key, member);
          }
          if ($index(key)) {
            if (Number(key) > target.length) return false;
            return Reflect.set(target, key, $rt.reverseHostGraph(member));
          }
          return Reflect.set(target, key, member);
        },
        defineProperty(target, key, descriptor) {
          if (key === "length" || $index(key)) {
            if (!("value" in descriptor) || "get" in descriptor || "set" in descriptor
                || descriptor.writable === false
                || (key === "length" && (descriptor.configurable === true || descriptor.enumerable === true))
                || (key !== "length" && (descriptor.configurable === false || descriptor.enumerable === false))) return false;
            return this.set(target, key, descriptor.value);
          }
          return Reflect.defineProperty(target, key, descriptor);
        },
        deleteProperty(target, key) {
          if (key === "length" || $index(key)) return false;
          return Reflect.deleteProperty(target, key);
        },
        preventExtensions() { return false; },
        getOwnPropertyDescriptor(target, key) {
          const descriptor = Reflect.getOwnPropertyDescriptor(target, key);
          if ($index(key) && descriptor && descriptor.configurable && "value" in descriptor)
            return {...descriptor, value: $project(descriptor.value)};
          return descriptor;
        }
      });
      $hostArrayViews.set($value, $view);
      $hostArraySources.set($view, $value);
      if ($arrayDescriptors.has($value)) $arrayDescriptors.set($view, $arrayDescriptors.get($value));
      return $view;
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
          if ($hostArrayViews.has($value)) return $hostArrayViews.get($value);
          const $array = new Proxy([], {
            get(target, key, receiver) {
              if (key === "length") return $rt.arrayLength($value);
              if (typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key))
                return $project($value.get(String(Number(key) + 1)));
              return Reflect.get(target, key, receiver);
            },
            set(target, key, member) {
              if (key === "length") {
                if (!Number.isInteger(member) || member < 0 || member > $rt.arrayLength($value)) return false;
                for (const k of $value.keys()) if ($arrayFormIndex(k) !== null && Number(k) > member) $value.delete(k);
                return true;
              }
              if (typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key)) {
                if (Number(key) > $rt.arrayLength($value)) return false;
                const index = String(Number(key) + 1);
                $value.set(index, $rt.reverseHostGraph(member));
                const marks = $jsonNumberMarks.get($value);
                if (marks) marks.delete(index);
                return true;
              }
              return Reflect.set(target, key, member);
            },
            defineProperty(target, key, descriptor) {
              if (key === "length" || (typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key))) {
                if (!("value" in descriptor) || descriptor.get || descriptor.set
                    || descriptor.writable === false
                    || (key === "length" && (descriptor.configurable === true || descriptor.enumerable === true))
                    || (key !== "length" && (descriptor.configurable === false || descriptor.enumerable === false))) return false;
                return this.set(target, key, descriptor.value);
              }
              return Reflect.defineProperty(target, key, descriptor);
            },
            deleteProperty(target, key) {
              if (key === "length" || (typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key))) return false;
              return Reflect.deleteProperty(target, key);
            },
            preventExtensions() { return false; },
            has(target, key) {
              if (typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key))
                return $value.has(String(Number(key) + 1));
              return Reflect.has(target, key);
            },
            ownKeys(target) {
              return [...$value.keys()].filter(k => $arrayFormIndex(k) !== null)
                .map(k => String(Number(k) - 1)).concat(Reflect.ownKeys(target));
            },
            getOwnPropertyDescriptor(target, key) {
              if (key === "length") return {value: $rt.arrayLength($value), writable: true, enumerable: false, configurable: false};
              if (typeof key === "string" && /^(0|[1-9][0-9]*)$/.test(key)
                  && $value.has(String(Number(key) + 1)))
                return {value: $project($value.get(String(Number(key) + 1))), writable: true,
                  enumerable: true, configurable: true};
              return Reflect.getOwnPropertyDescriptor(target, key);
            }
          });
          $hostArrayViews.set($value, $array);
          $hostArraySources.set($array, $value);
          $seen.set($value, $array);
          $carryMarks($value, $array, true);
          if ($arrayDescriptors.has($value)) $arrayDescriptors.set($array, $arrayDescriptors.get($value));
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
    if ($value !== null && typeof $value === "object" && $value.$kind === "class") return $value;
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
    const error = new $DEALError("E8001", "JSON parse error at position " + $at + ": " + $reason,
      $file, $line, $column);
    $diagnosticJsonSet(error, {offset: "" + $at, reason: $reason});
    throw error;
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
          $out.push(String.fromCharCode($parseHex4()));
        } else {
          $fail("invalid escape");
        }
      } else if ($c < 0x20) {
        $fail("unexpected character");

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

// ===== ECMAScript JSON number spelling =====

// $numText: the canonical decimal text of one finite binary64 value, or
// null for a nonfinite one.
function $numText($v) {
  // A read-side variant carrier spells its own double: the canonical text
  // is a function of the value, never of the carrier (__num).
  $v = $numOf($v);
  if ($v !== $v || $v === Infinity || $v === -Infinity) {
    return null;
  }
  return String($v);
}

// ===== Builtin JSON text encoding =====

// $carrierToken: the closed stringify carrier-kind projection of a value
// (the pinned actual vocabulary).
function $carrierToken($v) {
  if ($v === $undefined || $v === $MISSING) return "nil";
  if ($v === null) return "table";
  if (typeof $v === "boolean") return "boolean";
  // A read-side variant carrier is a number carrier in stringify's
  // closed vocabulary (the shared JsonStringify projection), whichever
  // variant it records.
  if (typeof $v === "number" || $isNumCarrier($v)) return "number";
  if (typeof $v === "string") return "string";
  if (typeof $v === "function" || $v.$kind === "function") return "function";
  return "table";
}

// $jsonEscapeText: RFC-8259 escaping with lossless lone UTF-16 unit escapes.
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
    else if ($c < 0x20 || ($c >= 0xd800 && $c <= 0xdbff
        ? !($s.charCodeAt($i + 1) >= 0xdc00 && $s.charCodeAt($i + 1) <= 0xdfff)
        : $c >= 0xdc00 && $c <= 0xdfff
          && !($i > 0 && $s.charCodeAt($i - 1) >= 0xd800 && $s.charCodeAt($i - 1) <= 0xdbff))) {
      const $hex = $c.toString(16);
      $out += "\\u" + "0".repeat(4 - $hex.length) + $hex;
    } else {
      $out += $s.charAt($i);
    }
  }
  return $out + "\"";
}

// Builtin stringify uses an explicit frame stack and path-local active
// identities: shared acyclic children are legal, active re-entry is a cycle.
// Unsupported carriers render at the stringify operation's own origin.
function $encodeJsonText($v, $active, $file, $line, $column, $variant) {
  const $unsupported = function ($value) {
    const $token = $carrierToken($value);
    $rt.fail("E8001", "unsupported type for JSON encoding: " + $token,
      $file, $line, $column, "string, number, boolean, or table", $token);
  };
  const $omit = $value => $value === $undefined || $value === $MISSING
    || typeof $value === "function" || ($value !== null && typeof $value === "object" && $value.$kind === "function");
  if ($omit($v)) return $undefined;
  const $out = [];
  const $stack = [];
  // Validate declared positions as the encoder reaches them, rather than
  // validating a second graph walk ahead of serialization. This preserves
  // declaration/index order when an earlier member contains a cycle.
  const $checkPosition = function ($descriptor, $value) {
    if ($descriptor === null) return;
    let $parsed = $parse($descriptor);
    while ($parsed && $parsed.$t === "nullable") {
      if ($parsed.$null && ($value === null || $jsonNullTables.has($value))
          || $parsed.$undefined && ($value === $undefined || $value === $MISSING)) return;
      $descriptor = $parsed.$inner;
      $parsed = $parse($descriptor);
    }
    if ($parsed && $parsed.$t === "class") {
      if ($value === null || $value === $undefined || $value === $MISSING || $value.$kind !== "class")
        $rt.fail("E8001", "expected class instance", $file, $line, $column, "class", $kindOf($value));
      if ($value.$classname !== $parsed.$name)
        $rt.fail("E8001", "expected instance of " + $parsed.$name + ", got " + $value.$classname,
          $file, $line, $column, $parsed.$name, $value.$classname);
    } else if ($parsed && $parsed.$t === "array") {
      if (!$Array.isArray($value) && !$rt.isJsonArrayTable($value))
        $rt.fail("E8001", "expected array", $file, $line, $column, "array", $kindOf($value));
      const $persistent = $arrayDescriptors.get($value);
      if ($persistent !== $undefined && $persistent !== $parsed.$element)
        $rt.fail("E8001", "expected array", $file, $line, $column, "array", "array");
    } else if ($parsed && $parsed.$t === "primitive" && $parsed.$name === "record") {
      if (!($value instanceof $Map) || $rt.isJsonArrayTable($value))
        $rt.fail("E8001", "expected table", $file, $line, $column, "table", $kindOf($value));
    } else if (!($parsed && $parsed.$t === "primitive" && $parsed.$name === "unknown")) {
      $rt.checkTypeLeaf($descriptor, $value, $file, $line, $column);
    }
  };
  const $elementDescriptor = function ($descriptor) {
    if ($descriptor === null) return null;
    let $parsed = $parse($descriptor);
    while ($parsed && $parsed.$t === "nullable") $parsed = $parse($parsed.$inner);
    return $parsed && $parsed.$t === "array" ? $parsed.$element : null;
  };
  let $arrays = null;
  const $boundary = function ($action) {
    try { return $action(); } catch ($error) {
      for (let $array = $arrays; $array; $array = $array.parent) {
        const $failure = new $DEALError("E8003", "array element " + $array.index + " type mismatch",
          $file, $line, $column, $array.descriptor, $elemKindOf($array.value));
        $failure.cause = $error.code === "E8003" && $error.cause ? $error.cause : $error;
        $error = $failure;
      }
      throw $error;
    }
  };
  const $check = ($descriptor, $value) => $boundary(() => $checkPosition($descriptor, $value));
  const $emit = function ($value, $valueVariant, $descriptor = null) {
    $check($descriptor, $value);
    if ($omit($value)) {
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
        $out.push("null"); return;
      }
      if ($valueVariant === true) {
        const $text = $numText($value);
        if ($text === null) {
          $unsupported($value);
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
      $out.push($jsonEscapeText($value));
      return;
    }
    if ($type === "function"
        || ($type === "object" && $value.$kind === "function")) {
      $unsupported($value);
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
          $stack.push({ map: $value, kind: 1, index: 1, max: $max,
            descriptor: $elementDescriptor($descriptor), arrays: $arrays });
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
      $stack.push({ map: $value, kind: 2, index: 0, emitted: 0, keys: $keys, arrays: $arrays });
      return;
    }
    if ($type === "object" && $value.$kind === "class") {
      const $keys = $classFieldOrder.get($value);
      if (!$keys) $unsupported($value);
      if ($active.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $active.push($value);
      $out.push("{");
      const $plan = $instanceClassPlans.get($value) || $hostClassPlans.get($value.$classname)
        || ($value.$classname === "Error" ? [
          {name: "name", descriptor: "string", optional: false},
          {name: "message", descriptor: "string", optional: false}] : null);
      if (!$plan) throw new Error("JSON class value has no exact field plan");
      $stack.push({ map: { get: key => $value[key] }, owner: $value, plan: $plan,
        kind: 2, index: 0, emitted: 0, keys: $keys, arrays: $arrays });
      return;
    }
    if ($Array.isArray($value)) {
      if ($active.includes($value)) {
        $rt.fail("E8001", "cyclic value cannot be encoded as JSON", $file, $line, $column);
      }
      $active.push($value);
      $out.push("[");
      $stack.push({ array: $value, kind: 3, index: 0,
        descriptor: $elementDescriptor($descriptor), arrays: $arrays });
      return;
    }
    $unsupported($value);
  };
  const $base = $active.length;
  try {
    $emit($v, $variant);
    while ($stack.length > 0) {
      const $frame = $stack[$stack.length - 1];
      $arrays = $frame.arrays;
      if ($frame.kind === 1) {
        // A marked array-shaped Map: dense indices 1..max; omitted
        // positions substitute null without mutating the source.
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
        if ($frame.descriptor !== null) $arrays = {index: $i, descriptor: $frame.descriptor,
          value: $element, parent: $arrays};
        $emit($element, $isNumberVariant($frame.map, $key), $frame.descriptor);
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
        if (typeof $key !== "string") {
          // Record keys must be strings; every UTF-16 string is legal.
          $rt.fail("E8001", "unsupported type for JSON encoding: string",
            $file, $line, $column, "string, number, boolean, or table", "string");
        }
        const $element = $frame.map.get($key);
        let $descriptor = null;
        if ($frame.plan) {
          const $field = $frame.plan[$i];
          if (!$field || $field.name !== $key) throw new Error("JSON class field plan/order mismatch");
          if (!Object.prototype.hasOwnProperty.call($frame.owner, $key) || $element === $MISSING) {
            if (!$field.optional) $boundary(() => $rt.fail("E8001", "expected class instance",
              $file, $line, $column, "class", $frame.owner.$classname));
            continue;
          }
          $descriptor = $field.descriptor;
          $check($descriptor, $element);
        }
        if ($omit($element)) continue;
        if ($frame.emitted++ > 0) $out.push(",");
        $out.push($jsonEscapeText($key) + ":");
        $emit($element, $isNumberVariant($frame.map, $key), $descriptor);
        continue;
      }
      // A native Array: elements in index order; omitted positions
      // substitute null, just as in a decoded array.
      if ($frame.index >= $frame.array.length) {
        $out.push("]");
        $active.pop();
        $stack.pop();
        continue;
      }
      const $i = $frame.index++;
      if ($i > 0) $out.push(",");
      const $element = $frame.array[$i];
      if ($frame.descriptor !== null) $arrays = {index: $i + 1, descriptor: $frame.descriptor,
        value: $element, parent: $arrays};
      $emit($element, $isNumberVariant($frame.array, String($i)), $frame.descriptor);
    }
    return $out.join("");
  } finally {
    $active.length = $base;
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
// a number.
function $numberHex($v) {
  if ($v !== $v) return "NaN";
  if ($v === $Infinity) return "Infinity";
  if ($v === -$Infinity) return "-Infinity";
  const $sign = ($v < 0 || ($v === 0 && 1 / $v < 0)) ? "-" : "";
  const $magnitude = $v < 0 ? -$v : $v;
  if ($magnitude === 0) return $sign + "0x0.0p0";
  if ($magnitude < $diagnosticPow(2, -1022)) {
    // A subnormal keeps Java's fixed-exponent denormal spelling: two
    // exact power-of-two scalings recover its integer significand
    // (magnitude * 2^1074), never the normalized 2^52-scale form.
    const $scaled = $magnitude * $diagnosticPow(2, 537) * $diagnosticPow(2, 537);
    return $sign + "0x0." + $hexDigits($scaled) + "p-1022";
  }
  let $exponent = $diagnosticFloor($diagnosticLog2($magnitude));
  if ($magnitude >= $diagnosticPow(2, $exponent + 1)) {
    $exponent += 1;
  } else if ($magnitude < $diagnosticPow(2, $exponent)) {
    $exponent -= 1;
  }
  const $fraction =
    ($magnitude / $diagnosticPow(2, $exponent) - 1) * 4503599627370496;
  return $sign + "0x1." + $hexDigits($fraction) + "p" + $exponent;
}

// $hexDigits: the 13 hex digits of one exact integer fraction of a
// double's significand, trailing zeros stripped, never empty ("0" for a
// zero fraction — Java's "0x1.0p<exp>").
function $hexDigits($fraction) {
  let $text = $diagnosticNumberText($fraction, 16);
  while ($text.length < 13) $text = "0" + $text;
  while ($text.length > 1 && $diagnosticCharCode($text, $text.length - 1) === 48) {
    $text = $diagnosticSlice($text, 0, $text.length - 1);
  }
  return $text;
}

const $rt = {
  MISSING: $MISSING,
  NULL: null,
  undefined: $undefined,

  // numberHex: the canonical hex-float spelling (the member the shipped
  // numeric runtime observations go through).
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

  scalarOf: function(v) {
    return $rt.isNilEquivalent(v) ? null : $numOf($hostArraySources.get(v) || v);
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
  // of the JSON-mark contract, reachable only through runtime mis-emission
  // (js-backend-runtime-artifact D2). No DEALError arm exists here.
  markJsonArrayTable: function $markJsonArrayTable(map) {
    $jsonArrayTables.add(map);
    if (!$arrayDescriptors.has(map)) $arrayDescriptors.set(map, "unknown");
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

  // jsonParseText: the stack-safe RFC-8259 scanner used by contextual
  // decoding. Syntax defects and decoded-key duplicates report E8001
  // "JSON parse error at position N: reason" at the operation origin;
  // numeric lexemes round to binary64 and preserve signed zero.
  jsonParseText: function $jsonParseText(text, file, line, column) {
    return $parseJsonText(text, file, line, column);
  },

  // jsonStringifyText: builtin encoding with omission/substitution,
  // nonfinite-to-null spelling, reached-position validation and path-local
  // cycle rejection. Shared acyclic children encode independently; no input
  // storage or private descriptors are changed.
  jsonStringifyText: function $jsonStringifyText(v, path, file, line, column) {
    return $encodeJsonText(v, $Array.isArray(path) ? path : [], file, line, column, false);
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
    $diagnosticErrorAdd($value);
    $rt.setProp($value, "$kind", "class");
    $rt.setProp($value, "$classname", "Error");
    $rt.setProp($value, "name", code);
    $rt.setProp($value, "message", message);
    Object.defineProperty($value, "code", {value: code, writable: true, configurable: true});
    $classFieldOrder.set($value, ["name", "message"]);
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
      const value = $rt.errorValue(v.code, v.message, v.file, v.line, v.column,
        v.expected, v.actual, v.frames, v.cause);
      const json = $diagnosticJsonGet(v);
      if (json !== $undefined) $diagnosticJsonSet(value, json);
      const decode = $diagnosticDecodeGet(v);
      if (decode !== $undefined) $diagnosticDecodeSet(value, decode);
      const host = $diagnosticHostGet(v);
      if (host !== $undefined) $diagnosticHostSet(value, host);
      const array = $diagnosticArrayGet(v);
      if (array !== $undefined) $diagnosticArraySet(value, array);
      return value;
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
  reportUncaught: function $reportUncaught(e, diagnosticError) {
    const $err = $rt.reifyError(e);
    $process.stderr.write("DEAL_ERROR_CODE: " + ($err.code === $undefined ? $err.name : $err.code) + " " + $err.message + "\n");
    if ($runtimeDiagnostics) $process.stderr.write("DEAL_RUNTIME_DIAGNOSTIC: " + $rt.runtimeDiagnostic(diagnosticError === $undefined ? $err : diagnosticError) + "\n");
    $process.exitCode = 1;
  },

  // Opt-in terminal projection of existing Error data only. Accessors and arbitrary
  // host values are unavailable evidence, never invoked or stringified.
  runtimeDiagnostic: function $runtimeDiagnostic(error) {
    const own = (value, key) => {
      const descriptor = $ownDescriptor(value, key);
      return descriptor && $descriptorHasOwn(descriptor, "value") ? descriptor.value : $undefined;
    };
    // Eight retained metadata records at most; no mutable host collection hooks.
    let seen = null;
    const visited = value => {
      for (let node = seen; node !== null; node = node.next) if (node.value === value) return true;
      return false;
    };
    const render = (value, depth) => {
      if (value === null || typeof value !== "object" || !$diagnosticErrorHas(value) || visited(value)) return "null";
      seen = {value, next: seen};
      let fields = "";
      const append = field => { fields += (fields === "" ? "" : ",") + field; };
      const textKeys = ["code", "message", "file", "expected", "actual", "frames"];
      for (let index = 0; index < textKeys.length; index++) {
        const key = textKeys[index];
        const text = own(value, key);
        if (typeof text !== "string") continue;
        let take = text.length > 8192 ? 8192 : text.length;
        if (take < text.length && $diagnosticCharCode(text, take - 1) >= 0xD800 && $diagnosticCharCode(text, take - 1) <= 0xDBFF
            && $diagnosticCharCode(text, take) >= 0xDC00 && $diagnosticCharCode(text, take) <= 0xDFFF) take--;
        append($jsonQuote(key) + ":" + $jsonQuote($diagnosticSlice(text, 0, take)));
        if (take < text.length) {
          append($jsonQuote(key + "Length") + ":" + text.length);
          append($jsonQuote(key + "Truncated") + ":true");
        }
      }
      const json = $diagnosticJsonGet(value);
      if (json !== $undefined) {
        append('"jsonByteOffset":' + $jsonQuote(json.offset));
        append('"jsonReason":' + $jsonQuote(json.reason));
      }
      const decode = $diagnosticDecodeGet(value);
      if (decode !== $undefined) {
        append('"jsonPath":' + $jsonQuote(decode.path));
        append('"jsonPathDepth":' + $jsonQuote(decode.depth));
        append('"jsonPathTruncated":' + $jsonQuote(decode.truncated));
        append('"jsonMissingField":' + $jsonQuote(decode.missing));
      }
      const host = $diagnosticHostGet(value);
      if (host !== $undefined) {
        const keys = ["hostModule", "hostExport", "hostSignature", "hostBoundary", "hostParameter", "hostDeclaration"];
        for (let i = 0; i < keys.length; i++) {
          const text = own(host, keys[i]);
          if (typeof text !== "string") continue;
          let take = text.length > 8192 ? 8192 : text.length;
          if (take < text.length && $diagnosticCharCode(text, take - 1) >= 0xD800 && $diagnosticCharCode(text, take - 1) <= 0xDBFF
              && $diagnosticCharCode(text, take) >= 0xDC00 && $diagnosticCharCode(text, take) <= 0xDFFF) take--;
          append($jsonQuote(keys[i]) + ":" + $jsonQuote($diagnosticSlice(text, 0, take)));
          if (take < text.length) { append($jsonQuote(keys[i] + "Length") + ":" + text.length); append($jsonQuote(keys[i] + "Truncated") + ":true"); }
        }
      }
      const array = $diagnosticArrayGet(value);
      if (array !== $undefined) {
        append('"arrayIndex":' + $jsonQuote(own(array, "index")));
        append('"arrayLength":' + $jsonQuote(own(array, "length")));
        append('"arrayConstraint":' + $jsonQuote(own(array, "constraint")));
      }
      const numberKeys = ["line", "column"];
      for (let index = 0; index < numberKeys.length; index++) {
        const key = numberKeys[index];
        const number = own(value, key);
        if (typeof number === "number" && $diagnosticFinite(number)) append($jsonQuote(key) + ":" + number);
      }
      const cause = own(value, "cause");
      if (cause !== null && typeof cause === "object") {
        if (depth >= 7 || visited(cause) || !$diagnosticErrorHas(cause)) append('"causeTruncated":true');
        else append('"cause":' + render(cause, depth + 1));
      }
      return "{" + fields + "}";
    };
    return '{"version":2,"runtime":' + render(error, 0) + "}";
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

  // Every UTF-16 string passes unchanged, including lone surrogate units.
  checkString: function $checkString(v, file, line, column) {
    if (typeof v !== "string") {
      $rt.fail("E8001", "expected string", file, line, column, "string", $kindOf(v));
    }
    return v;
  },

  // checkTable: the strict split (js-backend-runtime D3) — an unmarked
  // instanceof-Map table passes; the two D2 JSON decode carriers do not:
  // a decoded JSON array is the array carrier (the JSON provenance mark)
  // and a decoded JSON null is language null, so both are rejected at a
  // table boundary with the shared boundary executor's own closed tuple
  // (E8001 "expected table", expected "table", actual "array"/"null" —
  // never the plain-Map "table" label). Anything else — Array, plain
  // object, class instance, wrapper, primitives — keeps its own $kindOf
  // actual label.
  validateData: function $validateData(value, file, line, column) {
    const seen = new WeakSet(), pending = [value], untaggedArrays = [];
    while (pending.length) {
      const v = pending.pop();
      if (v === null || v === $undefined || v === $MISSING) continue;
      if (typeof v === "string") { $rt.checkString(v, file, line, column); continue; }
      if (typeof v === "number" || typeof v === "boolean" || $isNumCarrier(v)) continue;
      if (typeof v !== "object") $rt.fail("E8001", "unsupported unknown ingress", file, line, column, "unknown", $kindOf(v));
      if (seen.has(v)) continue;
      seen.add(v);
      if (v instanceof $Map) {
        for (const [key, member] of v) { $rt.checkString(key, file, line, column); pending.push(member); }
      } else if ($Array.isArray(v)) {
        if (!$arrayDescriptors.has(v)) untaggedArrays.push(v);
        for (let i = 0; i < v.length; i++) {
          if (!Object.prototype.hasOwnProperty.call(v, i)) $rt.fail("E8001", "sparse array ingress", file, line, column, "array", "array");
          pending.push(v[i]);
        }
      } else if (v.$kind === "class") {
        if (v.$classname === "Error") {
          $rt.checkString(v.name, file, line, column);
          $rt.checkString(v.message, file, line, column);
        }
        const fields = $classFieldOrder.get(v) || Object.keys(v).filter(key => key !== "$kind" && key !== "$classname");
        for (const key of fields) if (Object.prototype.hasOwnProperty.call(v, key)) pending.push(v[key]);
      } else if (v.$kind === "function") {
        const signature = typeof v.$sig === "string" ? $parse(v.$sig) : null;
        if (typeof v.$f !== "function" || !signature || signature.$t !== "function")
          $rt.fail("E8001", "unsupported unknown ingress", file, line, column, "unknown", "function");
      } else {
        $rt.fail("E8001", "unsupported unknown ingress", file, line, column, "unknown", $kindOf(v));
      }
    }
    // Publish persistent descriptors only after the whole ingress graph is valid.
    for (const array of untaggedArrays) $arrayDescriptors.set(array, "unknown");
    return value;
  },

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
    return $rt.validateData(v, file, line, column);
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
    const pending = [{descriptor, value: v, arrays: null}], seen = new WeakMap();
    while (pending.length) {
      const task = pending.pop();
      let value = task.field ? task.owner[task.field.name] : task.value;
      let current = task.descriptor;
      try {
        if (task.field && (!Object.prototype.hasOwnProperty.call(task.owner, task.field.name)
            || value === $MISSING)) {
          if (!task.field.optional) $rt.fail("E8001", "missing required field: " + task.field.name, file, line, column);
          continue;
        }
        if (value === $MISSING) value = $undefined;
        let parsed = $parse(current, true), acceptedNullish = false;
        while (parsed && parsed.$t === "nullable") {
          if (parsed.$null && (value === null || $jsonNullTables.has(value))
              || parsed.$undefined && value === $undefined) { acceptedNullish = true; break; }
          current = parsed.$inner;
          parsed = $parse(current);
        }
        if (acceptedNullish) continue;
        if (!parsed || parsed.$t !== "class" && parsed.$t !== "array") {
          $rt.checkTypeLeaf(current, value, file, line, column);
          continue;
        }
        if (parsed.$t === "class") {
          if (value === $undefined || value === null || value.$kind !== "class")
            $rt.fail("E8001", "expected class instance", file, line, column, "class", $kindOf(value));
          if (value.$classname !== parsed.$name)
            $rt.fail("E8001", "expected instance of " + parsed.$name + ", got " + value.$classname,
              file, line, column, parsed.$name, value.$classname);
        } else {
          if (!$Array.isArray(value) && !$rt.isJsonArrayTable(value))
            $rt.fail("E8001", "expected array", file, line, column, "array", $kindOf(value));
          const persistent = $arrayDescriptors.get(value);
          if (persistent !== $undefined && persistent !== parsed.$element)
            $rt.fail("E8001", "array element descriptor mismatch", file, line, column, current, "array");
        }
        let checked = seen.get(value);
        if (!checked) { checked = new Set(); seen.set(value, checked); }
        if (checked.has(current)) continue;
        checked.add(current);
        if (parsed.$t === "class") {
          const plan = $instanceClassPlans.get(value) || $hostClassPlans.get(parsed.$name);
          if (plan) {
            if (!$classFieldOrder.has(value)) $classFieldOrder.set(value, plan.map(field => field.name));
            for (let i = plan.length - 1; i >= 0; --i)
              pending.push({descriptor: plan[i].descriptor, owner: value, field: plan[i], arrays: task.arrays});
          }
        } else {
          const mapped = !$Array.isArray(value);
          let count = value.length;
          if (mapped) {
            count = 0;
            for (const key of value.keys()) { const index = $arrayFormIndex(key); if (index !== null && index > count) count = index; }
          }
          for (let i = count - 1; i >= 0; --i) {
            const member = mapped ? value.get(String(i + 1)) : value[i];
            pending.push({descriptor: parsed.$element, value: member,
              arrays: {index: i + 1, descriptor: parsed.$element, value: member, parent: task.arrays}});
          }
        }
      } catch (error) {
        for (let array = task.arrays; array; array = array.parent) {
          const failure = new $DEALError("E8003", "array element " + array.index + " type mismatch",
            file, line, column, array.descriptor, $elemKindOf(array.value));
          failure.cause = error.code === "E8003" && error.cause ? error.cause : error;
          error = failure;
        }
        throw error;
      }
    }
    return $jsonNullTables.has(v) ? null : v === $MISSING ? $undefined : v;
  },

  // Scalar/data/function leaves of the stack-safe descriptor walk. Composite
  // positions are always consumed by checkType's operation-local work stack.
  checkTypeLeaf: function $checkTypeLeaf(descriptor, v, file, line, column) {
    if (v === $MISSING) v = $undefined;
    if (descriptor === null) {
      $rt.fail("E8001", "internal: nil type descriptor", file, line, column);
    }
    const $parsed = $parse(descriptor, true);
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
                || (kind === "function" && typeof v !== "function")))) return $rt.validateData(v, file, line, column);
        $rt.fail("E8001", "unsupported unknown ingress", file, line, column, "unknown", kind);
      }
      if ($parsed.$name === "record") {
        return $rt.checkTable(v, file, line, column);
      }
      $rt.fail("E8001", "unknown primitive type: " + $parsed.$name, file, line, column);
    }
    if ($parsed.$t === "function") {
      // Function branch: the wrapper $kind tag first, then the exact
      // signature compare against the full descriptor string
      // (deal/runtime.lua:450-456). The null/undefined guard keeps the
      // $kind read total (no raw TypeError from a check, D3).
      if (v === $undefined || v === null || v.$kind !== "function") {
        $rt.fail("E8001", "expected function", file, line, column, "function", $kindOf(v));
      }
      $rt.validateData(v, file, line, column);
      if (v.$sig !== descriptor) {
        $rt.fail("E8010", "function signature mismatch: expected " + descriptor + ", got " + v.$sig, file, line, column, descriptor, v.$sig);
      }
      return v;
    }
    // Defensive: the parser is total over strings and every record kind is
    // dispatched above, so no parsed record reaches this arm (A3).
    $rt.fail("E8001", "internal: unhandled descriptor kind: " + $parsed.$t, file, line, column);
  },

  // checkArray: the strict array split (D3) — Array.isArray via the T1
  // $Array capture (never the bare spelling), plus the json-array-marked
  // Map table (the decoded JSON array provenance mark —
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
  reverseHostGraph: function(root) {
    const seen = new WeakSet();
    function reverse(value) {
      if (value === null || typeof value !== "object") return value;
      if ($hostArraySources.has(value)) return $hostArraySources.get(value);
      if (seen.has(value)) return value;
      seen.add(value);
      if (value instanceof $Map) {
        for (const [key, member] of value) {
          const admitted = reverse(member);
          if (admitted !== member) value.set(key, admitted);
        }
      } else if ($Array.isArray(value)) {
        for (let i = 0; i < value.length; i++) if (Object.prototype.hasOwnProperty.call(value, i)) {
          const member = reverse(value[i]);
          if (member !== value[i]) value[i] = member;
        }
      } else if (value.$kind === "class") {
        for (const key of $classFieldOrder.get(value) || Object.keys(value)) {
          const member = reverse(value[key]);
          if (member !== value[key]) value[key] = member;
        }
      }
      return value;
    }
    return reverse(root);
  },
  tagArray: function(v, elementDescriptor) { $arrayDescriptors.set(v, elementDescriptor); return v; },

  // Only declared host admission may install a fresh array's descriptor.
  // Existing arrays (including parsed unknown[]) are never retagged.
  admitHostArray: function(descriptor, value) {
    let parsed = $parse(descriptor);
    if (value === null || value === $undefined) return value;
    while (parsed && parsed.$t === "nullable") {
      descriptor = parsed.$inner;
      parsed = $parse(descriptor);
    }
    if (!parsed || parsed.$t !== "array") return value;
    if (!$Array.isArray(value) || $arrayDescriptors.has(value)) return value;
    const element = $arrayElementDescriptor(descriptor);
    for (const member of value) $rt.admitHostArray(element, member);
    $arrayDescriptors.set(value, element);
    return value;
  },

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
          const failure = new $DEALError("E8003", "array element " + $i + " type mismatch",
            file, line, column, $element, $elemKindOf($value));
          failure.cause = $e.code === "E8003" && $e.cause ? $e.cause : $e;
          throw failure;
        }
      }
      return v;
    }
    for (let $i = 0; $i < v.length; $i++) {
      try {
        $rt.checkType($element, v[$i], file, line, column);
      } catch ($e) {
        const failure = new $DEALError("E8003", "array element " + ($i + 1) + " type mismatch",
          file, line, column, $element, $elemKindOf(v[$i]));
        failure.cause = $e.code === "E8003" && $e.cause ? $e.cause : $e;
        throw failure;
      }
    }
    return v;
  },

  // arrayElementCheck: the ARRAY_ELEMENT_DESCRIPTOR cell of one array
  // literal element (docs/spec-v1.6.md Runtime diagnostics: E8003 is
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
  // real-Array walk's 0-based position); a marked Map — the JSON
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
  arrayPositionPresent: function $arrayPositionPresent(v, i) {
    return Number.isInteger(i) && i >= 0 && i < $rt.arrayLength(v) && $rt.arrayHas(v, i);
  },

  arrayHas: function $arrayHas(v, i) {
    if ($rt.isJsonArrayTable(v)) {
      if ($arrayDeleted.get(v)?.has(i) && v.get(String(i + 1)) === $undefined) return false;
      return v.has(String(i + 1));
    }
    return Object.prototype.hasOwnProperty.call(v, i);
  },

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
  // native length; a marked Map — the decoded representation of a JSON array,
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
  // assigns the slot and a marked Map (the decoded representation of a JSON
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
    $arrayDeleted.get(v)?.delete(i);
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

  arrayReadFailure: function(index, length, file, line, column) {
    const error = new $DEALError("E8002", "array index out of bounds", file, line, column);
    $diagnosticArraySet(error, {index: $numberHex(index), length: "" + length,
      constraint: !Number.isInteger(index) ? "finite-integer" : "read-range"});
    throw error;
  },

  arrayElementGet: function(v, index, descriptor, file, line, column) {
    const length = $rt.arrayLength(v);
    const constraint = !Number.isInteger(index) ? "finite-integer" : index < 0 || index >= length ? "read-range" : null;
    if (constraint !== null || !$rt.arrayHas(v, index)) {
      const error = new $DEALError("E8002", "array index out of bounds", file, line, column);
      $diagnosticArraySet(error, {index: $numberHex(index), length: "" + length, constraint: constraint || "present-element"});
      throw error;
    }
    const value = $rt.arrayAt(v, index);
    try { return $rt.checkType(descriptor, value, file, line, column); }
    catch (cause) {
      const failure = new $DEALError("E8003", "array element " + (index + 1) + " type mismatch",
        file, line, column, descriptor, $elemKindOf(value));
      failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
      throw failure;
    }
  },

  arrayJoin: function(v, separator, file, line, column) {
    const length = $rt.arrayLength(v), parts = [];
    for (let i = 0; i < length; i++) {
      if (!$rt.arrayHas(v, i)) { parts.push(""); continue; }
      const value = $rt.arrayAt(v, i);
      try { parts.push($rt.checkType("string", value, file, line, column)); }
      catch (cause) {
        const failure = new $DEALError("E8003", "array element " + (i + 1) + " type mismatch",
          file, line, column, "string", $elemKindOf(value));
        failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
        throw failure;
      }
    }
    return parts.join(separator);
  },

  arraySearch: function(v, needle, from, includes, descriptor, file, line, column) {
    const length = $rt.arrayLength(v);
    const integer = Number.isNaN(from) ? 0 : Math.trunc(from);
    const start = Math.max(0, Math.min(length, integer < 0 ? length + integer : integer));
    needle = $rt.scalarOf(needle);
    for (let i = start; i < length; i++) {
      const present = $rt.arrayHas(v, i);
      if (!present && !includes) continue;
      let value = present ? $rt.arrayAt(v, i) : undefined;
      if (present) {
        try { value = $rt.checkType(descriptor, value, file, line, column); }
        catch (cause) {
          const failure = new $DEALError("E8003", "array element " + (i + 1) + " type mismatch",
            file, line, column, descriptor, $elemKindOf(value));
          failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
          throw failure;
        }
      }
      value = $rt.scalarOf(value);
      if (value === needle || includes && typeof value === "number" && typeof needle === "number"
          && Number.isNaN(value) && Number.isNaN(needle)) return includes ? true : i;
    }
    return includes ? false : -1;
  },

  arrayConcat: function(left, right, descriptor, file, line, column) {
    const result = $rt.tagArray([], descriptor);
    let offset = 0;
    for (const source of [left, right]) {
      const length = $rt.arrayLength(source);
      result.length = offset + length;
      for (let i = 0; i < length; i++) {
        if (!$rt.arrayHas(source, i)) continue;
        const value = $rt.arrayAt(source, i);
        try { result[offset + i] = $rt.checkType(descriptor, value, file, line, column); }
        catch (cause) {
          const failure = new $DEALError("E8003", "array element " + (i + 1) + " type mismatch",
            file, line, column, descriptor, $elemKindOf(value));
          failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
          throw failure;
        }
      }
      offset += length;
    }
    return result;
  },

  // Shallow copy; relative bounds observe length only after argument effects.
  arraySlice: function(v, start, end, descriptor, file, line, column) {
    const length = $rt.arrayLength(v);
    const bound = value => {
      const integer = Number.isNaN(value) ? 0 : Math.trunc(value);
      return Math.max(0, Math.min(length, integer < 0 ? length + integer : integer));
    };
    const first = bound(start), last = bound(end);
    const result = $rt.tagArray(new $Array(Math.max(0, last - first)), descriptor);
    for (let i = first; i < last; i++) {
      if (!$rt.arrayHas(v, i)) continue;
      const value = $rt.arrayAt(v, i);
      try { result[i - first] = $rt.checkType(descriptor, value, file, line, column); }
      catch (cause) {
        const failure = new $DEALError("E8003", "array element " + (i + 1) + " type mismatch",
          file, line, column, descriptor, $elemKindOf(value));
        failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
        throw failure;
      }
    }
    return result;
  },

  // ECMAScript deletion-only splice. Admission completes before any mutation.
  arraySplice: function(v, start, deleteCount, descriptor, file, line, column) {
    const length = $rt.arrayLength(v);
    const integer = Number.isNaN(start) ? 0 : Math.trunc(start);
    const first = Math.max(0, Math.min(length, integer < 0 ? length + integer : integer));
    const count = Number.isNaN(deleteCount) ? 0 : Math.max(0, Math.min(length - first, Math.trunc(deleteCount)));
    const removed = $rt.tagArray([], descriptor);
    for (let i = 0; i < count; i++) {
      const value = $rt.arrayAt(v, first + i);
      try { removed.push($rt.checkType(descriptor, value, file, line, column)); }
      catch (cause) {
        const failure = new $DEALError("E8003", "array element " + (first + i + 1) + " type mismatch",
          file, line, column, descriptor, $elemKindOf(value));
        failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
        throw failure;
      }
    }
    if (count === 0) return removed;
    const decoded = !$Array.isArray(v) && $rt.isJsonArrayTable(v);
    const key = i => String(decoded ? i + 1 : i);
    const deleted = $arrayDeleted.get(v);
    for (let i = first; i < length - count; i++) {
      const source = i + count;
      const value = decoded ? v.get(key(source)) : v[source];
      if (decoded) v.set(key(i), value); else v[i] = value;
      $setNumberVariant(v, key(i), $isNumberVariant(v, key(source)));
      if (deleted) { if (deleted.has(source)) deleted.add(i); else deleted.delete(i); }
    }
    for (let i = length - count; i < length; i++) {
      if (decoded) v.delete(key(i));
      $clearNumberVariant(v, key(i));
      deleted?.delete(i);
    }
    if (!decoded) v.length = length - count;
    return removed;
  },

  // Snapshot presence/order only; never validate or traverse member values.
  objectKeys: function $objectKeys(value) {
    let keys;
    const fields = $classFieldOrder.get(value);
    if (fields) keys = fields.filter(key => $rt.has(value, key));
    else if (value instanceof Map) {
      keys = [...value.keys()];
      const index = key => /^(0|[1-9][0-9]*)$/.test(key) && Number(key) < 4294967295 ? Number(key) : Infinity;
      keys.sort((a, b) => index(a) === index(b) ? 0 : index(a) < index(b) ? -1 : 1);
    } else throw new Error("OBJECT_KEYS unsupported receiver");
    return $rt.tagArray(keys, "string");
  },

  // Pop validates the live tail before changing storage or private metadata.
  arrayPop: function $arrayPop(v, descriptor, file, line, column) {
    const n = $rt.arrayLength(v);
    if (n === 0) return $undefined;
    const value = $rt.arrayAt(v, n - 1);
    let admitted;
    try {
      admitted = $rt.checkType(descriptor, value, file, line, column);
    } catch (cause) {
      const failure = new $DEALError("E8003", "array element " + n + " type mismatch",
        file, line, column, descriptor, $elemKindOf(value));
      failure.cause = cause.code === "E8003" && cause.cause ? cause.cause : cause;
      throw failure;
    }
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      v.delete(String(n));
      $clearNumberVariant(v, String(n));
    } else {
      v.pop();
      $clearNumberVariant(v, String(n - 1));
    }
    $arrayDeleted.get(v)?.delete(n - 1);
    return admitted;
  },

  // arrayDelete: the committed step of an emitted array-element delete
  // whose E8002 bounds gate already ran and whose append-at-length guard
  // already suppressed the write at i === length. A native Array removes
  // its own slot; a decoded Map retains its length key with a private
  // absence mark. Both distinguish deletion from stored undefined.
  // Reads at the slot yield undefined and the length
  // is unchanged. The slot's number-variant mark is cleared — the slot
  // now holds the internal missing value, never a classified number. No
  // mutation of any other slot; the carrier identity is preserved.
  arrayDelete: function $arrayDelete(v, i) {
    if (!$Array.isArray(v) && $rt.isJsonArrayTable(v)) {
      let deleted = $arrayDeleted.get(v);
      if (!deleted) { deleted = new Set(); $arrayDeleted.set(v, deleted); }
      deleted.add(i);
      const $key = String(i + 1);
      v.set($key, $undefined);
      $clearNumberVariant(v, $key);
      return;
    }
    delete v[i];
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
  // Explicit target reconstruction; descriptor assertions never call this.
  jsonDecode: function(text, descriptor, file, line, column) {
    const layouts = name => name === "Error" ? [
      {name: "name", descriptor: "string", optional: false},
      {name: "message", descriptor: "string", optional: false}
    ] : $hostClassPlans.get(name);
    const pending = [descriptor], visited = new Set();
    while (pending.length) {
      const d = $parse(pending.pop());
      if (!d || d.$t === "function" || d.$name === "void") throw new Error("invalid JSON decoding schema");
      if (d.$t === "nullable") pending.push(d.$inner);
      else if (d.$t === "array") pending.push(d.$element);
      else if (d.$t === "class" && !visited.has(d.$name)) {
        visited.add(d.$name);
        const plan = layouts(d.$name);
        if (!plan) throw new Error("JSON decoding target has no exact class plan: " + d.$name);
        for (let i = plan.length - 1; i >= 0; --i) pending.push(plan[i].descriptor);
      }
    }
    const stack = [{raw: $rt.jsonParseText(text, file, line, column), descriptor, opened: false}];
    const fail = (code, message, expected, actual, rejectedKey) => {
      let path = "", depth = 0, truncated = false;
      const segment = key => {
        depth++;
        if (depth > 32) { truncated = true; return; }
        let take = key.length > 128 ? 128 : key.length;
        if (take < key.length && $diagnosticCharCode(key, take - 1) >= 0xD800
            && $diagnosticCharCode(key, take - 1) <= 0xDBFF
            && $diagnosticCharCode(key, take) >= 0xDC00 && $diagnosticCharCode(key, take) <= 0xDFFF) take--;
        if (take < key.length) truncated = true;
        path += "/";
        for (let i = 0; i < take; i++) path += key[i] === "~" ? "~0" : key[i] === "/" ? "~1" : key[i];
      };
      for (let i = 1; i < stack.length; i++) segment("" + stack[i].key);
      if (rejectedKey !== $undefined) segment(rejectedKey);
      try { $rt.fail(code, message, file, line, column, expected, actual); }
      catch (error) {
        $diagnosticDecodeSet(error, {path, depth: "" + depth, truncated: "" + truncated,
          missing: stack[stack.length - 1].missing ? "true" : "false"});
        throw error;
      }
    };
    const mismatch = (d, value) => {
      const kind = d.$t === "class" ? "class instance" : d.$t === "array" ? "array"
        : d.$name === "record" ? "table" : d.$name;
      fail("E8001", "expected " + kind, d.$t === "class" ? "class" : kind, $kindOf(value));
    };
    let completed;
    while (stack.length) {
      const f = stack[stack.length - 1];
      if (!f.opened) {
        f.opened = true;
        let d = $parse(f.descriptor);
        let acceptsNull = d.$t === "nullable" && d.$null;
        if (d.$t === "nullable") d = $parse(d.$inner);
        f.d = d;
        if (f.missing) mismatch(d, $undefined);
        if (f.raw === null && (acceptsNull || d.$name === "null" || d.$name === "unknown")) {
          f.output = null;
        } else if (d.$t === "class") {
          if (!$isPlainObject(f.raw)) mismatch(d, f.raw);
          f.plan = layouts(d.$name);
          const names = new Set(f.plan.map(field => field.name));
          for (const key of Object.keys(f.raw)) if (!names.has(key))
            fail("E8007", "extra field '" + key + "' in class '" + d.$name + "'", $undefined, $undefined, key);
          f.children = [];
          for (const field of f.plan) {
            const present = Object.prototype.hasOwnProperty.call(f.raw, field.name);
            if (present || !field.optional) f.children.push({key: field.name, raw: f.raw[field.name], descriptor: field.descriptor, missing: !present});
          }
          f.output = {};
        } else if ($Array.isArray(f.raw) && (d.$t === "array" || d.$name === "unknown")) {
          f.children = f.raw.map((raw, key) => ({raw, key, descriptor: d.$t === "array" ? d.$element : "unknown"}));
          f.output = [];
          $arrayDescriptors.set(f.output, d.$t === "array" ? d.$element : "unknown");
        } else if ($isPlainObject(f.raw) && (d.$name === "record" || d.$name === "unknown")) {
          f.children = Object.keys(f.raw).map(key => ({raw: f.raw[key], key, descriptor: "unknown"}));
          f.output = $rt.makeTable({});
        } else if (d.$name === "unknown" || (d.$name === typeof f.raw && f.raw !== null)) f.output = f.raw;
        else mismatch(d, f.raw);
        f.index = 0;
      }
      if (f.children && f.index < f.children.length) {
        const child = f.children[f.index++];
        stack.push({...child, opened: false});
        continue;
      }
      completed = f.output;
      if (f.plan) {
        $rt.setProp(completed, "$kind", "class");
        $rt.setProp(completed, "$classname", f.d.$name);
        $classFieldOrder.set(completed, f.plan.map(field => field.name));
        $instanceClassPlans.set(completed, f.plan);
      }
      stack.pop();
      if (stack.length) {
        const parent = stack[stack.length - 1];
        if (parent.output instanceof $Map) parent.output.set(f.key, completed);
        else $rt.setProp(parent.output, f.key, completed);
      }
    }
    return completed;
  },

  registerClassPlan: function(identity, fields) { $hostClassPlans.set(identity, fields); },
  registerInstancePlan: function(value, fields) { $instanceClassPlans.set(value, fields); },
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
    $instanceClassPlans.set($slots, plan);
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
    const fields = $classFieldOrder.get(obj);
    if (fields && !fields.includes(field)) return false;
    return Object.prototype.hasOwnProperty.call(obj, field) && obj[field] !== $MISSING;
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
  //    deltas and class nominal identity — re-raises E8010 "parameter {i} type
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
  hostFunction: function $hostFunction(descriptor, f, context) {
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
    const fail = (boundary, parameter, code, message, file, line, column, expected, actual) => {
      try { $rt.fail(code, message, file, line, column, expected, actual); }
      catch (error) {
        $diagnosticHostSet(error, {hostSignature: descriptor, hostBoundary: boundary, hostParameter: parameter,
          hostModule: context && context.module, hostExport: context && context.name, hostDeclaration: context && context.declaration});
        throw error;
      }
    };
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
          fail("arity", $undefined, "E8010", "expected at least " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        if ($nargs > $paramDescs.length) {
          fail("arity", $undefined, "E8010", "expected " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        const $checkedArgs = [];
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          try {
            $checkedArgs.push($rt.checkType($paramDescs[$i], args[$i], $file, $line, $column));
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            fail("parameter", "" + ($i + 1), "E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $luaKindOf(args[$i]));
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
        const $rawResult = f(...$hostArgs);
        const $r = $rt.reverseHostGraph($rawResult);
        if ($isNullRet) {
          if ($r === $undefined) {
            fail("return", $undefined, "E8010", "return value 1 type mismatch: expected null, got nothing", $file, $line, $column, "null", "nothing");
          }
          try {
            return $rt.checkNull($r, $file, $line, $column);
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            fail("return", $undefined, "E8010", "return value 1 type mismatch: " + $e.message, $file, $line, $column, "null", $luaKindOf($r));
          }
        }
        if ($r === $undefined && $retDesc === "void") return $undefined;
        if ($r === $undefined && !($retDesc === "unknown" || $retDesc === "undefined" || $retDesc === "null|undefined" || $retDesc.startsWith("!undefined") || $retDesc.startsWith("!") || $retDesc.startsWith("?!"))) {
          fail("return", $undefined, "E8010", "return value 1 type mismatch: expected " + $retDesc + ", got nothing", $file, $line, $column, $retDesc, "nothing");
        }
        try {
          const checked = $rt.checkType($retDesc, $r, $file, $line, $column);
          return $rt.admitHostArray($retDesc, checked);
        } catch ($e) {
          if (!($e instanceof $DEALError)) {
            throw $e;
          }
          fail("return", $undefined, "E8010", "return value 1 type mismatch: " + $e.message, $file, $line, $column, $retDesc, $luaKindOf($r));
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
  // await (spec-v1.6.md, async export invocation).
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
  hostAsyncFunction: function $hostAsyncFunction(descriptor, f, context) {
    if (typeof f !== "function") {
      $rt.fail("E8001", "expected function, got " + $kindOf(f), $undefined, $undefined, $undefined, "function", $kindOf(f));
    }
    const $parsed = $parse(descriptor);
    if ($parsed === null || $parsed.$t !== "function" || !$parsed.$async) {
      $rt.fail("E8010", "invalid function signature: " + String(descriptor));
    }
    const $paramDescs = $parsed.$params;
    const $retDesc = $asyncReturnDescriptor(descriptor);
    const fail = (boundary, parameter, code, message, file, line, column, expected, actual) => {
      try { $rt.fail(code, message, file, line, column, expected, actual); }
      catch (error) {
        $diagnosticHostSet(error, {hostSignature: descriptor, hostBoundary: boundary, hostParameter: parameter,
          hostModule: context && context.module, hostExport: context && context.name, hostDeclaration: context && context.declaration});
        throw error;
      }
    };
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
          fail("arity", $undefined, "E8010", "expected at least " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        if ($nargs > $paramDescs.length) {
          fail("arity", $undefined, "E8010", "expected " + $paramDescs.length + " arguments, got " + $nargs, $file, $line, $column);
        }
        const $checkedArgs = [];
        for (let $i = 0; $i < $paramDescs.length; $i++) {
          try {
            $checkedArgs.push($rt.checkType($paramDescs[$i], args[$i], $file, $line, $column));
          } catch ($e) {
            if (!($e instanceof $DEALError)) {
              throw $e;
            }
            fail("parameter", "" + ($i + 1), "E8010", "parameter " + ($i + 1) + " type mismatch: " + $e.message, $file, $line, $column, $paramDescs[$i], $luaKindOf(args[$i]));
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
          fail("async-shape", $undefined, "E8010", "host async function must return an async operation, got " + $got, $file, $line, $column, "async operation", $got);
        }
        const $v = $rt.reverseHostGraph(await $op);
        try {
          const checked = $rt.checkType($retDesc, $v, $completionFile,
            $completionLine, $completionColumn);
          return $rt.admitHostArray($retDesc, checked);
        } catch ($completionError) {
          let failure = $completionError;
          if ($completionError instanceof $DEALError
              && $completionError.actual === $kindOf($v)
              && $completionError.actual !== $completionKind($v)) {
            failure = new $DEALError($completionError.code,
              $completionError.message, $completionError.file,
              $completionError.line, $completionError.column,
              $completionError.expected, $completionKind($v));
          }
          if (failure instanceof $DEALError) $diagnosticHostSet(failure, {
            hostSignature: descriptor, hostBoundary: "completion", hostModule: context && context.module,
            hostExport: context && context.name, hostDeclaration: context && context.declaration});
          throw failure;
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
  loadHost: function $loadHost(rawExports, declaredMap, $modulePath, file, line, column, declarationModule) {
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
            ? $rt.hostAsyncFunction($desc, $f, {module: $modulePath, name: $name, declaration: declarationModule})
            : $rt.hostFunction($desc, $f, {module: $modulePath, name: $name, declaration: declarationModule});
      } else if ($entry.$k === "class") {
        if ($parsed === null || $parsed.$t !== "class") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " has an unsupported declared descriptor: " + $desc, file, line, column);
        }
        if ($v === null || typeof $v !== "object" || $v.$kind !== "class") {
          $rt.fail("E8011", "host export '" + $name + "'" + $inModule + " is not a class meta", file, line, column, "class", $rawShapeKind($v));
        }
        if ($v.$classname !== $desc) {
          $rt.fail("E8011", "host class '" + $name + "'" + $inModule + " has identity mismatch: expected " + $desc + ", got " + String($v.$classname), file, line, column);
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
        $hostClassPlans.set($desc, $classPlan);
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
        || $main.$kind !== "function" || $main.$sig !== "()->void"
        || typeof $main.$f !== "function") {
      return $failure("main is not a void-returning function");
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

  // ===== String iteration helpers =====
  // Iteration combines valid pairs and yields lone units individually;
  // string length, slicing and ordering independently use UTF-16 units.

  // Materialize iteration elements through the captured Array.from:
  // one pair or lone unit per element, independently of string length.
  scalars: function $scalars(s) {
    return $Array.from(s);
  },

  // Relational ordering compares UTF-16 units, independently of iteration.
  strCompare: function $strCompare(a, b) {
    return a === b ? 0 : a < b ? -1 : 1;
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
      $diagnosticErrors.add(v);
      if (v.code === $undefined) Object.defineProperty(v, "code",
        {value: v.name, writable: true, configurable: true});
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

$rt.stringChangeCase = function(text, upper) {
  const points = Array.from(text, c => c.codePointAt(0));
  const data = $unicodeCaseData;
  const property = (cp, ranges) => {
    let low = 0, high = ranges.length / 2 - 1;
    while (low <= high) {
      const mid = (low + high) >>> 1;
      if (cp < ranges[2 * mid]) high = mid - 1;
      else if (cp > ranges[2 * mid + 1]) low = mid + 1;
      else return true;
    }
    return false;
  };
  let result = "", preceded = false;
  const mappings = upper ? data.upper : data.lower;
  for (let i = 0; i < points.length; i++) {
    const cp = points[i];
    let final = false;
    if (!upper && cp === 0x03a3 && preceded) {
      let next = i + 1;
      while (next < points.length && property(points[next], data.ignorable)) next++;
      final = next === points.length || !property(points[next], data.cased);
    }
    if (final) result += "\u03c2";
    else if (Object.hasOwn(mappings, cp)) result += String.fromCodePoint(...mappings[cp]);
    else result += String.fromCodePoint(cp);
    if (!property(cp, data.ignorable)) preceded = property(cp, data.cased);
  }
  return result;
};

module.exports = $rt;

// BEGIN GENERATED UNICODE CASE DATA
// UNICODE LICENSE V3
// Copyright © 1991-2026 Unicode, Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of data files and any associated documentation (the "Data Files") or
// software and any associated documentation (the "Software") to deal in the
// Data Files or Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, and/or sell
// copies of the Data Files or Software, and to permit persons to whom the
// Data Files or Software are furnished to do so, provided that either (a)
// this copyright and permission notice appear with all copies of the Data
// Files or Software, or (b) this copyright and permission notice appear in
// associated Documentation.
//
// THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
// KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
// THIRD PARTY RIGHTS.
//
// IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
// BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
// OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
// WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
// ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
// FILES OR SOFTWARE.
//
// Except as contained in this notice, the name of a copyright holder shall
// not be used in advertising or otherwise to promote the sale, use or other
// dealings in these Data Files or Software without prior written
// authorization of the copyright holder.
const $unicodeCaseData = {"lower":{"65":[97],"66":[98],"67":[99],"68":[100],"69":[101],"70":[102],"71":[103],"72":[104],"73":[105],"74":[106],"75":[107],"76":[108],"77":[109],"78":[110],"79":[111],"80":[112],"81":[113],"82":[114],"83":[115],"84":[116],"85":[117],"86":[118],"87":[119],"88":[120],"89":[121],"90":[122],"192":[224],"193":[225],"194":[226],"195":[227],"196":[228],"197":[229],"198":[230],"199":[231],"200":[232],"201":[233],"202":[234],"203":[235],"204":[236],"205":[237],"206":[238],"207":[239],"208":[240],"209":[241],"210":[242],"211":[243],"212":[244],"213":[245],"214":[246],"216":[248],"217":[249],"218":[250],"219":[251],"220":[252],"221":[253],"222":[254],"256":[257],"258":[259],"260":[261],"262":[263],"264":[265],"266":[267],"268":[269],"270":[271],"272":[273],"274":[275],"276":[277],"278":[279],"280":[281],"282":[283],"284":[285],"286":[287],"288":[289],"290":[291],"292":[293],"294":[295],"296":[297],"298":[299],"300":[301],"302":[303],"304":[105,775],"306":[307],"308":[309],"310":[311],"313":[314],"315":[316],"317":[318],"319":[320],"321":[322],"323":[324],"325":[326],"327":[328],"330":[331],"332":[333],"334":[335],"336":[337],"338":[339],"340":[341],"342":[343],"344":[345],"346":[347],"348":[349],"350":[351],"352":[353],"354":[355],"356":[357],"358":[359],"360":[361],"362":[363],"364":[365],"366":[367],"368":[369],"370":[371],"372":[373],"374":[375],"376":[255],"377":[378],"379":[380],"381":[382],"385":[595],"386":[387],"388":[389],"390":[596],"391":[392],"393":[598],"394":[599],"395":[396],"398":[477],"399":[601],"400":[603],"401":[402],"403":[608],"404":[611],"406":[617],"407":[616],"408":[409],"412":[623],"413":[626],"415":[629],"416":[417],"418":[419],"420":[421],"422":[640],"423":[424],"425":[643],"428":[429],"430":[648],"431":[432],"433":[650],"434":[651],"435":[436],"437":[438],"439":[658],"440":[441],"444":[445],"452":[454],"453":[454],"455":[457],"456":[457],"458":[460],"459":[460],"461":[462],"463":[464],"465":[466],"467":[468],"469":[470],"471":[472],"473":[474],"475":[476],"478":[479],"480":[481],"482":[483],"484":[485],"486":[487],"488":[489],"490":[491],"492":[493],"494":[495],"497":[499],"498":[499],"500":[501],"502":[405],"503":[447],"504":[505],"506":[507],"508":[509],"510":[511],"512":[513],"514":[515],"516":[517],"518":[519],"520":[521],"522":[523],"524":[525],"526":[527],"528":[529],"530":[531],"532":[533],"534":[535],"536":[537],"538":[539],"540":[541],"542":[543],"544":[414],"546":[547],"548":[549],"550":[551],"552":[553],"554":[555],"556":[557],"558":[559],"560":[561],"562":[563],"570":[11365],"571":[572],"573":[410],"574":[11366],"577":[578],"579":[384],"580":[649],"581":[652],"582":[583],"584":[585],"586":[587],"588":[589],"590":[591],"880":[881],"882":[883],"886":[887],"895":[1011],"902":[940],"904":[941],"905":[942],"906":[943],"908":[972],"910":[973],"911":[974],"913":[945],"914":[946],"915":[947],"916":[948],"917":[949],"918":[950],"919":[951],"920":[952],"921":[953],"922":[954],"923":[955],"924":[956],"925":[957],"926":[958],"927":[959],"928":[960],"929":[961],"931":[963],"932":[964],"933":[965],"934":[966],"935":[967],"936":[968],"937":[969],"938":[970],"939":[971],"975":[983],"984":[985],"986":[987],"988":[989],"990":[991],"992":[993],"994":[995],"996":[997],"998":[999],"1000":[1001],"1002":[1003],"1004":[1005],"1006":[1007],"1012":[952],"1015":[1016],"1017":[1010],"1018":[1019],"1021":[891],"1022":[892],"1023":[893],"1024":[1104],"1025":[1105],"1026":[1106],"1027":[1107],"1028":[1108],"1029":[1109],"1030":[1110],"1031":[1111],"1032":[1112],"1033":[1113],"1034":[1114],"1035":[1115],"1036":[1116],"1037":[1117],"1038":[1118],"1039":[1119],"1040":[1072],"1041":[1073],"1042":[1074],"1043":[1075],"1044":[1076],"1045":[1077],"1046":[1078],"1047":[1079],"1048":[1080],"1049":[1081],"1050":[1082],"1051":[1083],"1052":[1084],"1053":[1085],"1054":[1086],"1055":[1087],"1056":[1088],"1057":[1089],"1058":[1090],"1059":[1091],"1060":[1092],"1061":[1093],"1062":[1094],"1063":[1095],"1064":[1096],"1065":[1097],"1066":[1098],"1067":[1099],"1068":[1100],"1069":[1101],"1070":[1102],"1071":[1103],"1120":[1121],"1122":[1123],"1124":[1125],"1126":[1127],"1128":[1129],"1130":[1131],"1132":[1133],"1134":[1135],"1136":[1137],"1138":[1139],"1140":[1141],"1142":[1143],"1144":[1145],"1146":[1147],"1148":[1149],"1150":[1151],"1152":[1153],"1162":[1163],"1164":[1165],"1166":[1167],"1168":[1169],"1170":[1171],"1172":[1173],"1174":[1175],"1176":[1177],"1178":[1179],"1180":[1181],"1182":[1183],"1184":[1185],"1186":[1187],"1188":[1189],"1190":[1191],"1192":[1193],"1194":[1195],"1196":[1197],"1198":[1199],"1200":[1201],"1202":[1203],"1204":[1205],"1206":[1207],"1208":[1209],"1210":[1211],"1212":[1213],"1214":[1215],"1216":[1231],"1217":[1218],"1219":[1220],"1221":[1222],"1223":[1224],"1225":[1226],"1227":[1228],"1229":[1230],"1232":[1233],"1234":[1235],"1236":[1237],"1238":[1239],"1240":[1241],"1242":[1243],"1244":[1245],"1246":[1247],"1248":[1249],"1250":[1251],"1252":[1253],"1254":[1255],"1256":[1257],"1258":[1259],"1260":[1261],"1262":[1263],"1264":[1265],"1266":[1267],"1268":[1269],"1270":[1271],"1272":[1273],"1274":[1275],"1276":[1277],"1278":[1279],"1280":[1281],"1282":[1283],"1284":[1285],"1286":[1287],"1288":[1289],"1290":[1291],"1292":[1293],"1294":[1295],"1296":[1297],"1298":[1299],"1300":[1301],"1302":[1303],"1304":[1305],"1306":[1307],"1308":[1309],"1310":[1311],"1312":[1313],"1314":[1315],"1316":[1317],"1318":[1319],"1320":[1321],"1322":[1323],"1324":[1325],"1326":[1327],"1329":[1377],"1330":[1378],"1331":[1379],"1332":[1380],"1333":[1381],"1334":[1382],"1335":[1383],"1336":[1384],"1337":[1385],"1338":[1386],"1339":[1387],"1340":[1388],"1341":[1389],"1342":[1390],"1343":[1391],"1344":[1392],"1345":[1393],"1346":[1394],"1347":[1395],"1348":[1396],"1349":[1397],"1350":[1398],"1351":[1399],"1352":[1400],"1353":[1401],"1354":[1402],"1355":[1403],"1356":[1404],"1357":[1405],"1358":[1406],"1359":[1407],"1360":[1408],"1361":[1409],"1362":[1410],"1363":[1411],"1364":[1412],"1365":[1413],"1366":[1414],"4256":[11520],"4257":[11521],"4258":[11522],"4259":[11523],"4260":[11524],"4261":[11525],"4262":[11526],"4263":[11527],"4264":[11528],"4265":[11529],"4266":[11530],"4267":[11531],"4268":[11532],"4269":[11533],"4270":[11534],"4271":[11535],"4272":[11536],"4273":[11537],"4274":[11538],"4275":[11539],"4276":[11540],"4277":[11541],"4278":[11542],"4279":[11543],"4280":[11544],"4281":[11545],"4282":[11546],"4283":[11547],"4284":[11548],"4285":[11549],"4286":[11550],"4287":[11551],"4288":[11552],"4289":[11553],"4290":[11554],"4291":[11555],"4292":[11556],"4293":[11557],"4295":[11559],"4301":[11565],"5024":[43888],"5025":[43889],"5026":[43890],"5027":[43891],"5028":[43892],"5029":[43893],"5030":[43894],"5031":[43895],"5032":[43896],"5033":[43897],"5034":[43898],"5035":[43899],"5036":[43900],"5037":[43901],"5038":[43902],"5039":[43903],"5040":[43904],"5041":[43905],"5042":[43906],"5043":[43907],"5044":[43908],"5045":[43909],"5046":[43910],"5047":[43911],"5048":[43912],"5049":[43913],"5050":[43914],"5051":[43915],"5052":[43916],"5053":[43917],"5054":[43918],"5055":[43919],"5056":[43920],"5057":[43921],"5058":[43922],"5059":[43923],"5060":[43924],"5061":[43925],"5062":[43926],"5063":[43927],"5064":[43928],"5065":[43929],"5066":[43930],"5067":[43931],"5068":[43932],"5069":[43933],"5070":[43934],"5071":[43935],"5072":[43936],"5073":[43937],"5074":[43938],"5075":[43939],"5076":[43940],"5077":[43941],"5078":[43942],"5079":[43943],"5080":[43944],"5081":[43945],"5082":[43946],"5083":[43947],"5084":[43948],"5085":[43949],"5086":[43950],"5087":[43951],"5088":[43952],"5089":[43953],"5090":[43954],"5091":[43955],"5092":[43956],"5093":[43957],"5094":[43958],"5095":[43959],"5096":[43960],"5097":[43961],"5098":[43962],"5099":[43963],"5100":[43964],"5101":[43965],"5102":[43966],"5103":[43967],"5104":[5112],"5105":[5113],"5106":[5114],"5107":[5115],"5108":[5116],"5109":[5117],"7305":[7306],"7312":[4304],"7313":[4305],"7314":[4306],"7315":[4307],"7316":[4308],"7317":[4309],"7318":[4310],"7319":[4311],"7320":[4312],"7321":[4313],"7322":[4314],"7323":[4315],"7324":[4316],"7325":[4317],"7326":[4318],"7327":[4319],"7328":[4320],"7329":[4321],"7330":[4322],"7331":[4323],"7332":[4324],"7333":[4325],"7334":[4326],"7335":[4327],"7336":[4328],"7337":[4329],"7338":[4330],"7339":[4331],"7340":[4332],"7341":[4333],"7342":[4334],"7343":[4335],"7344":[4336],"7345":[4337],"7346":[4338],"7347":[4339],"7348":[4340],"7349":[4341],"7350":[4342],"7351":[4343],"7352":[4344],"7353":[4345],"7354":[4346],"7357":[4349],"7358":[4350],"7359":[4351],"7680":[7681],"7682":[7683],"7684":[7685],"7686":[7687],"7688":[7689],"7690":[7691],"7692":[7693],"7694":[7695],"7696":[7697],"7698":[7699],"7700":[7701],"7702":[7703],"7704":[7705],"7706":[7707],"7708":[7709],"7710":[7711],"7712":[7713],"7714":[7715],"7716":[7717],"7718":[7719],"7720":[7721],"7722":[7723],"7724":[7725],"7726":[7727],"7728":[7729],"7730":[7731],"7732":[7733],"7734":[7735],"7736":[7737],"7738":[7739],"7740":[7741],"7742":[7743],"7744":[7745],"7746":[7747],"7748":[7749],"7750":[7751],"7752":[7753],"7754":[7755],"7756":[7757],"7758":[7759],"7760":[7761],"7762":[7763],"7764":[7765],"7766":[7767],"7768":[7769],"7770":[7771],"7772":[7773],"7774":[7775],"7776":[7777],"7778":[7779],"7780":[7781],"7782":[7783],"7784":[7785],"7786":[7787],"7788":[7789],"7790":[7791],"7792":[7793],"7794":[7795],"7796":[7797],"7798":[7799],"7800":[7801],"7802":[7803],"7804":[7805],"7806":[7807],"7808":[7809],"7810":[7811],"7812":[7813],"7814":[7815],"7816":[7817],"7818":[7819],"7820":[7821],"7822":[7823],"7824":[7825],"7826":[7827],"7828":[7829],"7838":[223],"7840":[7841],"7842":[7843],"7844":[7845],"7846":[7847],"7848":[7849],"7850":[7851],"7852":[7853],"7854":[7855],"7856":[7857],"7858":[7859],"7860":[7861],"7862":[7863],"7864":[7865],"7866":[7867],"7868":[7869],"7870":[7871],"7872":[7873],"7874":[7875],"7876":[7877],"7878":[7879],"7880":[7881],"7882":[7883],"7884":[7885],"7886":[7887],"7888":[7889],"7890":[7891],"7892":[7893],"7894":[7895],"7896":[7897],"7898":[7899],"7900":[7901],"7902":[7903],"7904":[7905],"7906":[7907],"7908":[7909],"7910":[7911],"7912":[7913],"7914":[7915],"7916":[7917],"7918":[7919],"7920":[7921],"7922":[7923],"7924":[7925],"7926":[7927],"7928":[7929],"7930":[7931],"7932":[7933],"7934":[7935],"7944":[7936],"7945":[7937],"7946":[7938],"7947":[7939],"7948":[7940],"7949":[7941],"7950":[7942],"7951":[7943],"7960":[7952],"7961":[7953],"7962":[7954],"7963":[7955],"7964":[7956],"7965":[7957],"7976":[7968],"7977":[7969],"7978":[7970],"7979":[7971],"7980":[7972],"7981":[7973],"7982":[7974],"7983":[7975],"7992":[7984],"7993":[7985],"7994":[7986],"7995":[7987],"7996":[7988],"7997":[7989],"7998":[7990],"7999":[7991],"8008":[8000],"8009":[8001],"8010":[8002],"8011":[8003],"8012":[8004],"8013":[8005],"8025":[8017],"8027":[8019],"8029":[8021],"8031":[8023],"8040":[8032],"8041":[8033],"8042":[8034],"8043":[8035],"8044":[8036],"8045":[8037],"8046":[8038],"8047":[8039],"8072":[8064],"8073":[8065],"8074":[8066],"8075":[8067],"8076":[8068],"8077":[8069],"8078":[8070],"8079":[8071],"8088":[8080],"8089":[8081],"8090":[8082],"8091":[8083],"8092":[8084],"8093":[8085],"8094":[8086],"8095":[8087],"8104":[8096],"8105":[8097],"8106":[8098],"8107":[8099],"8108":[8100],"8109":[8101],"8110":[8102],"8111":[8103],"8120":[8112],"8121":[8113],"8122":[8048],"8123":[8049],"8124":[8115],"8136":[8050],"8137":[8051],"8138":[8052],"8139":[8053],"8140":[8131],"8152":[8144],"8153":[8145],"8154":[8054],"8155":[8055],"8168":[8160],"8169":[8161],"8170":[8058],"8171":[8059],"8172":[8165],"8184":[8056],"8185":[8057],"8186":[8060],"8187":[8061],"8188":[8179],"8486":[969],"8490":[107],"8491":[229],"8498":[8526],"8544":[8560],"8545":[8561],"8546":[8562],"8547":[8563],"8548":[8564],"8549":[8565],"8550":[8566],"8551":[8567],"8552":[8568],"8553":[8569],"8554":[8570],"8555":[8571],"8556":[8572],"8557":[8573],"8558":[8574],"8559":[8575],"8579":[8580],"9398":[9424],"9399":[9425],"9400":[9426],"9401":[9427],"9402":[9428],"9403":[9429],"9404":[9430],"9405":[9431],"9406":[9432],"9407":[9433],"9408":[9434],"9409":[9435],"9410":[9436],"9411":[9437],"9412":[9438],"9413":[9439],"9414":[9440],"9415":[9441],"9416":[9442],"9417":[9443],"9418":[9444],"9419":[9445],"9420":[9446],"9421":[9447],"9422":[9448],"9423":[9449],"11264":[11312],"11265":[11313],"11266":[11314],"11267":[11315],"11268":[11316],"11269":[11317],"11270":[11318],"11271":[11319],"11272":[11320],"11273":[11321],"11274":[11322],"11275":[11323],"11276":[11324],"11277":[11325],"11278":[11326],"11279":[11327],"11280":[11328],"11281":[11329],"11282":[11330],"11283":[11331],"11284":[11332],"11285":[11333],"11286":[11334],"11287":[11335],"11288":[11336],"11289":[11337],"11290":[11338],"11291":[11339],"11292":[11340],"11293":[11341],"11294":[11342],"11295":[11343],"11296":[11344],"11297":[11345],"11298":[11346],"11299":[11347],"11300":[11348],"11301":[11349],"11302":[11350],"11303":[11351],"11304":[11352],"11305":[11353],"11306":[11354],"11307":[11355],"11308":[11356],"11309":[11357],"11310":[11358],"11311":[11359],"11360":[11361],"11362":[619],"11363":[7549],"11364":[637],"11367":[11368],"11369":[11370],"11371":[11372],"11373":[593],"11374":[625],"11375":[592],"11376":[594],"11378":[11379],"11381":[11382],"11390":[575],"11391":[576],"11392":[11393],"11394":[11395],"11396":[11397],"11398":[11399],"11400":[11401],"11402":[11403],"11404":[11405],"11406":[11407],"11408":[11409],"11410":[11411],"11412":[11413],"11414":[11415],"11416":[11417],"11418":[11419],"11420":[11421],"11422":[11423],"11424":[11425],"11426":[11427],"11428":[11429],"11430":[11431],"11432":[11433],"11434":[11435],"11436":[11437],"11438":[11439],"11440":[11441],"11442":[11443],"11444":[11445],"11446":[11447],"11448":[11449],"11450":[11451],"11452":[11453],"11454":[11455],"11456":[11457],"11458":[11459],"11460":[11461],"11462":[11463],"11464":[11465],"11466":[11467],"11468":[11469],"11470":[11471],"11472":[11473],"11474":[11475],"11476":[11477],"11478":[11479],"11480":[11481],"11482":[11483],"11484":[11485],"11486":[11487],"11488":[11489],"11490":[11491],"11499":[11500],"11501":[11502],"11506":[11507],"42560":[42561],"42562":[42563],"42564":[42565],"42566":[42567],"42568":[42569],"42570":[42571],"42572":[42573],"42574":[42575],"42576":[42577],"42578":[42579],"42580":[42581],"42582":[42583],"42584":[42585],"42586":[42587],"42588":[42589],"42590":[42591],"42592":[42593],"42594":[42595],"42596":[42597],"42598":[42599],"42600":[42601],"42602":[42603],"42604":[42605],"42624":[42625],"42626":[42627],"42628":[42629],"42630":[42631],"42632":[42633],"42634":[42635],"42636":[42637],"42638":[42639],"42640":[42641],"42642":[42643],"42644":[42645],"42646":[42647],"42648":[42649],"42650":[42651],"42786":[42787],"42788":[42789],"42790":[42791],"42792":[42793],"42794":[42795],"42796":[42797],"42798":[42799],"42802":[42803],"42804":[42805],"42806":[42807],"42808":[42809],"42810":[42811],"42812":[42813],"42814":[42815],"42816":[42817],"42818":[42819],"42820":[42821],"42822":[42823],"42824":[42825],"42826":[42827],"42828":[42829],"42830":[42831],"42832":[42833],"42834":[42835],"42836":[42837],"42838":[42839],"42840":[42841],"42842":[42843],"42844":[42845],"42846":[42847],"42848":[42849],"42850":[42851],"42852":[42853],"42854":[42855],"42856":[42857],"42858":[42859],"42860":[42861],"42862":[42863],"42873":[42874],"42875":[42876],"42877":[7545],"42878":[42879],"42880":[42881],"42882":[42883],"42884":[42885],"42886":[42887],"42891":[42892],"42893":[613],"42896":[42897],"42898":[42899],"42902":[42903],"42904":[42905],"42906":[42907],"42908":[42909],"42910":[42911],"42912":[42913],"42914":[42915],"42916":[42917],"42918":[42919],"42920":[42921],"42922":[614],"42923":[604],"42924":[609],"42925":[620],"42926":[618],"42928":[670],"42929":[647],"42930":[669],"42931":[43859],"42932":[42933],"42934":[42935],"42936":[42937],"42938":[42939],"42940":[42941],"42942":[42943],"42944":[42945],"42946":[42947],"42948":[42900],"42949":[642],"42950":[7566],"42951":[42952],"42953":[42954],"42955":[612],"42956":[42957],"42958":[42959],"42960":[42961],"42962":[42963],"42964":[42965],"42966":[42967],"42968":[42969],"42970":[42971],"42972":[411],"42997":[42998],"65313":[65345],"65314":[65346],"65315":[65347],"65316":[65348],"65317":[65349],"65318":[65350],"65319":[65351],"65320":[65352],"65321":[65353],"65322":[65354],"65323":[65355],"65324":[65356],"65325":[65357],"65326":[65358],"65327":[65359],"65328":[65360],"65329":[65361],"65330":[65362],"65331":[65363],"65332":[65364],"65333":[65365],"65334":[65366],"65335":[65367],"65336":[65368],"65337":[65369],"65338":[65370],"66560":[66600],"66561":[66601],"66562":[66602],"66563":[66603],"66564":[66604],"66565":[66605],"66566":[66606],"66567":[66607],"66568":[66608],"66569":[66609],"66570":[66610],"66571":[66611],"66572":[66612],"66573":[66613],"66574":[66614],"66575":[66615],"66576":[66616],"66577":[66617],"66578":[66618],"66579":[66619],"66580":[66620],"66581":[66621],"66582":[66622],"66583":[66623],"66584":[66624],"66585":[66625],"66586":[66626],"66587":[66627],"66588":[66628],"66589":[66629],"66590":[66630],"66591":[66631],"66592":[66632],"66593":[66633],"66594":[66634],"66595":[66635],"66596":[66636],"66597":[66637],"66598":[66638],"66599":[66639],"66736":[66776],"66737":[66777],"66738":[66778],"66739":[66779],"66740":[66780],"66741":[66781],"66742":[66782],"66743":[66783],"66744":[66784],"66745":[66785],"66746":[66786],"66747":[66787],"66748":[66788],"66749":[66789],"66750":[66790],"66751":[66791],"66752":[66792],"66753":[66793],"66754":[66794],"66755":[66795],"66756":[66796],"66757":[66797],"66758":[66798],"66759":[66799],"66760":[66800],"66761":[66801],"66762":[66802],"66763":[66803],"66764":[66804],"66765":[66805],"66766":[66806],"66767":[66807],"66768":[66808],"66769":[66809],"66770":[66810],"66771":[66811],"66928":[66967],"66929":[66968],"66930":[66969],"66931":[66970],"66932":[66971],"66933":[66972],"66934":[66973],"66935":[66974],"66936":[66975],"66937":[66976],"66938":[66977],"66940":[66979],"66941":[66980],"66942":[66981],"66943":[66982],"66944":[66983],"66945":[66984],"66946":[66985],"66947":[66986],"66948":[66987],"66949":[66988],"66950":[66989],"66951":[66990],"66952":[66991],"66953":[66992],"66954":[66993],"66956":[66995],"66957":[66996],"66958":[66997],"66959":[66998],"66960":[66999],"66961":[67000],"66962":[67001],"66964":[67003],"66965":[67004],"68736":[68800],"68737":[68801],"68738":[68802],"68739":[68803],"68740":[68804],"68741":[68805],"68742":[68806],"68743":[68807],"68744":[68808],"68745":[68809],"68746":[68810],"68747":[68811],"68748":[68812],"68749":[68813],"68750":[68814],"68751":[68815],"68752":[68816],"68753":[68817],"68754":[68818],"68755":[68819],"68756":[68820],"68757":[68821],"68758":[68822],"68759":[68823],"68760":[68824],"68761":[68825],"68762":[68826],"68763":[68827],"68764":[68828],"68765":[68829],"68766":[68830],"68767":[68831],"68768":[68832],"68769":[68833],"68770":[68834],"68771":[68835],"68772":[68836],"68773":[68837],"68774":[68838],"68775":[68839],"68776":[68840],"68777":[68841],"68778":[68842],"68779":[68843],"68780":[68844],"68781":[68845],"68782":[68846],"68783":[68847],"68784":[68848],"68785":[68849],"68786":[68850],"68944":[68976],"68945":[68977],"68946":[68978],"68947":[68979],"68948":[68980],"68949":[68981],"68950":[68982],"68951":[68983],"68952":[68984],"68953":[68985],"68954":[68986],"68955":[68987],"68956":[68988],"68957":[68989],"68958":[68990],"68959":[68991],"68960":[68992],"68961":[68993],"68962":[68994],"68963":[68995],"68964":[68996],"68965":[68997],"71840":[71872],"71841":[71873],"71842":[71874],"71843":[71875],"71844":[71876],"71845":[71877],"71846":[71878],"71847":[71879],"71848":[71880],"71849":[71881],"71850":[71882],"71851":[71883],"71852":[71884],"71853":[71885],"71854":[71886],"71855":[71887],"71856":[71888],"71857":[71889],"71858":[71890],"71859":[71891],"71860":[71892],"71861":[71893],"71862":[71894],"71863":[71895],"71864":[71896],"71865":[71897],"71866":[71898],"71867":[71899],"71868":[71900],"71869":[71901],"71870":[71902],"71871":[71903],"93760":[93792],"93761":[93793],"93762":[93794],"93763":[93795],"93764":[93796],"93765":[93797],"93766":[93798],"93767":[93799],"93768":[93800],"93769":[93801],"93770":[93802],"93771":[93803],"93772":[93804],"93773":[93805],"93774":[93806],"93775":[93807],"93776":[93808],"93777":[93809],"93778":[93810],"93779":[93811],"93780":[93812],"93781":[93813],"93782":[93814],"93783":[93815],"93784":[93816],"93785":[93817],"93786":[93818],"93787":[93819],"93788":[93820],"93789":[93821],"93790":[93822],"93791":[93823],"93856":[93883],"93857":[93884],"93858":[93885],"93859":[93886],"93860":[93887],"93861":[93888],"93862":[93889],"93863":[93890],"93864":[93891],"93865":[93892],"93866":[93893],"93867":[93894],"93868":[93895],"93869":[93896],"93870":[93897],"93871":[93898],"93872":[93899],"93873":[93900],"93874":[93901],"93875":[93902],"93876":[93903],"93877":[93904],"93878":[93905],"93879":[93906],"93880":[93907],"125184":[125218],"125185":[125219],"125186":[125220],"125187":[125221],"125188":[125222],"125189":[125223],"125190":[125224],"125191":[125225],"125192":[125226],"125193":[125227],"125194":[125228],"125195":[125229],"125196":[125230],"125197":[125231],"125198":[125232],"125199":[125233],"125200":[125234],"125201":[125235],"125202":[125236],"125203":[125237],"125204":[125238],"125205":[125239],"125206":[125240],"125207":[125241],"125208":[125242],"125209":[125243],"125210":[125244],"125211":[125245],"125212":[125246],"125213":[125247],"125214":[125248],"125215":[125249],"125216":[125250],"125217":[125251]},"upper":{"97":[65],"98":[66],"99":[67],"100":[68],"101":[69],"102":[70],"103":[71],"104":[72],"105":[73],"106":[74],"107":[75],"108":[76],"109":[77],"110":[78],"111":[79],"112":[80],"113":[81],"114":[82],"115":[83],"116":[84],"117":[85],"118":[86],"119":[87],"120":[88],"121":[89],"122":[90],"181":[924],"224":[192],"225":[193],"226":[194],"227":[195],"228":[196],"229":[197],"230":[198],"231":[199],"232":[200],"233":[201],"234":[202],"235":[203],"236":[204],"237":[205],"238":[206],"239":[207],"240":[208],"241":[209],"242":[210],"243":[211],"244":[212],"245":[213],"246":[214],"248":[216],"249":[217],"250":[218],"251":[219],"252":[220],"253":[221],"254":[222],"255":[376],"257":[256],"259":[258],"261":[260],"263":[262],"265":[264],"267":[266],"269":[268],"271":[270],"273":[272],"275":[274],"277":[276],"279":[278],"281":[280],"283":[282],"285":[284],"287":[286],"289":[288],"291":[290],"293":[292],"295":[294],"297":[296],"299":[298],"301":[300],"303":[302],"305":[73],"307":[306],"309":[308],"311":[310],"314":[313],"316":[315],"318":[317],"320":[319],"322":[321],"324":[323],"326":[325],"328":[327],"331":[330],"333":[332],"335":[334],"337":[336],"339":[338],"341":[340],"343":[342],"345":[344],"347":[346],"349":[348],"351":[350],"353":[352],"355":[354],"357":[356],"359":[358],"361":[360],"363":[362],"365":[364],"367":[366],"369":[368],"371":[370],"373":[372],"375":[374],"378":[377],"380":[379],"382":[381],"383":[83],"384":[579],"387":[386],"389":[388],"392":[391],"396":[395],"402":[401],"405":[502],"409":[408],"410":[573],"411":[42972],"414":[544],"417":[416],"419":[418],"421":[420],"424":[423],"429":[428],"432":[431],"436":[435],"438":[437],"441":[440],"445":[444],"447":[503],"453":[452],"454":[452],"456":[455],"457":[455],"459":[458],"460":[458],"462":[461],"464":[463],"466":[465],"468":[467],"470":[469],"472":[471],"474":[473],"476":[475],"477":[398],"479":[478],"481":[480],"483":[482],"485":[484],"487":[486],"489":[488],"491":[490],"493":[492],"495":[494],"498":[497],"499":[497],"501":[500],"505":[504],"507":[506],"509":[508],"511":[510],"513":[512],"515":[514],"517":[516],"519":[518],"521":[520],"523":[522],"525":[524],"527":[526],"529":[528],"531":[530],"533":[532],"535":[534],"537":[536],"539":[538],"541":[540],"543":[542],"547":[546],"549":[548],"551":[550],"553":[552],"555":[554],"557":[556],"559":[558],"561":[560],"563":[562],"572":[571],"575":[11390],"576":[11391],"578":[577],"583":[582],"585":[584],"587":[586],"589":[588],"591":[590],"592":[11375],"593":[11373],"594":[11376],"595":[385],"596":[390],"598":[393],"599":[394],"601":[399],"603":[400],"604":[42923],"608":[403],"609":[42924],"611":[404],"612":[42955],"613":[42893],"614":[42922],"616":[407],"617":[406],"618":[42926],"619":[11362],"620":[42925],"623":[412],"625":[11374],"626":[413],"629":[415],"637":[11364],"640":[422],"642":[42949],"643":[425],"647":[42929],"648":[430],"649":[580],"650":[433],"651":[434],"652":[581],"658":[439],"669":[42930],"670":[42928],"837":[921],"881":[880],"883":[882],"887":[886],"891":[1021],"892":[1022],"893":[1023],"940":[902],"941":[904],"942":[905],"943":[906],"945":[913],"946":[914],"947":[915],"948":[916],"949":[917],"950":[918],"951":[919],"952":[920],"953":[921],"954":[922],"955":[923],"956":[924],"957":[925],"958":[926],"959":[927],"960":[928],"961":[929],"962":[931],"963":[931],"964":[932],"965":[933],"966":[934],"967":[935],"968":[936],"969":[937],"970":[938],"971":[939],"972":[908],"973":[910],"974":[911],"976":[914],"977":[920],"981":[934],"982":[928],"983":[975],"985":[984],"987":[986],"989":[988],"991":[990],"993":[992],"995":[994],"997":[996],"999":[998],"1001":[1000],"1003":[1002],"1005":[1004],"1007":[1006],"1008":[922],"1009":[929],"1010":[1017],"1011":[895],"1013":[917],"1016":[1015],"1019":[1018],"1072":[1040],"1073":[1041],"1074":[1042],"1075":[1043],"1076":[1044],"1077":[1045],"1078":[1046],"1079":[1047],"1080":[1048],"1081":[1049],"1082":[1050],"1083":[1051],"1084":[1052],"1085":[1053],"1086":[1054],"1087":[1055],"1088":[1056],"1089":[1057],"1090":[1058],"1091":[1059],"1092":[1060],"1093":[1061],"1094":[1062],"1095":[1063],"1096":[1064],"1097":[1065],"1098":[1066],"1099":[1067],"1100":[1068],"1101":[1069],"1102":[1070],"1103":[1071],"1104":[1024],"1105":[1025],"1106":[1026],"1107":[1027],"1108":[1028],"1109":[1029],"1110":[1030],"1111":[1031],"1112":[1032],"1113":[1033],"1114":[1034],"1115":[1035],"1116":[1036],"1117":[1037],"1118":[1038],"1119":[1039],"1121":[1120],"1123":[1122],"1125":[1124],"1127":[1126],"1129":[1128],"1131":[1130],"1133":[1132],"1135":[1134],"1137":[1136],"1139":[1138],"1141":[1140],"1143":[1142],"1145":[1144],"1147":[1146],"1149":[1148],"1151":[1150],"1153":[1152],"1163":[1162],"1165":[1164],"1167":[1166],"1169":[1168],"1171":[1170],"1173":[1172],"1175":[1174],"1177":[1176],"1179":[1178],"1181":[1180],"1183":[1182],"1185":[1184],"1187":[1186],"1189":[1188],"1191":[1190],"1193":[1192],"1195":[1194],"1197":[1196],"1199":[1198],"1201":[1200],"1203":[1202],"1205":[1204],"1207":[1206],"1209":[1208],"1211":[1210],"1213":[1212],"1215":[1214],"1218":[1217],"1220":[1219],"1222":[1221],"1224":[1223],"1226":[1225],"1228":[1227],"1230":[1229],"1231":[1216],"1233":[1232],"1235":[1234],"1237":[1236],"1239":[1238],"1241":[1240],"1243":[1242],"1245":[1244],"1247":[1246],"1249":[1248],"1251":[1250],"1253":[1252],"1255":[1254],"1257":[1256],"1259":[1258],"1261":[1260],"1263":[1262],"1265":[1264],"1267":[1266],"1269":[1268],"1271":[1270],"1273":[1272],"1275":[1274],"1277":[1276],"1279":[1278],"1281":[1280],"1283":[1282],"1285":[1284],"1287":[1286],"1289":[1288],"1291":[1290],"1293":[1292],"1295":[1294],"1297":[1296],"1299":[1298],"1301":[1300],"1303":[1302],"1305":[1304],"1307":[1306],"1309":[1308],"1311":[1310],"1313":[1312],"1315":[1314],"1317":[1316],"1319":[1318],"1321":[1320],"1323":[1322],"1325":[1324],"1327":[1326],"1377":[1329],"1378":[1330],"1379":[1331],"1380":[1332],"1381":[1333],"1382":[1334],"1383":[1335],"1384":[1336],"1385":[1337],"1386":[1338],"1387":[1339],"1388":[1340],"1389":[1341],"1390":[1342],"1391":[1343],"1392":[1344],"1393":[1345],"1394":[1346],"1395":[1347],"1396":[1348],"1397":[1349],"1398":[1350],"1399":[1351],"1400":[1352],"1401":[1353],"1402":[1354],"1403":[1355],"1404":[1356],"1405":[1357],"1406":[1358],"1407":[1359],"1408":[1360],"1409":[1361],"1410":[1362],"1411":[1363],"1412":[1364],"1413":[1365],"1414":[1366],"4304":[7312],"4305":[7313],"4306":[7314],"4307":[7315],"4308":[7316],"4309":[7317],"4310":[7318],"4311":[7319],"4312":[7320],"4313":[7321],"4314":[7322],"4315":[7323],"4316":[7324],"4317":[7325],"4318":[7326],"4319":[7327],"4320":[7328],"4321":[7329],"4322":[7330],"4323":[7331],"4324":[7332],"4325":[7333],"4326":[7334],"4327":[7335],"4328":[7336],"4329":[7337],"4330":[7338],"4331":[7339],"4332":[7340],"4333":[7341],"4334":[7342],"4335":[7343],"4336":[7344],"4337":[7345],"4338":[7346],"4339":[7347],"4340":[7348],"4341":[7349],"4342":[7350],"4343":[7351],"4344":[7352],"4345":[7353],"4346":[7354],"4349":[7357],"4350":[7358],"4351":[7359],"5112":[5104],"5113":[5105],"5114":[5106],"5115":[5107],"5116":[5108],"5117":[5109],"7296":[1042],"7297":[1044],"7298":[1054],"7299":[1057],"7300":[1058],"7301":[1058],"7302":[1066],"7303":[1122],"7304":[42570],"7306":[7305],"7545":[42877],"7549":[11363],"7566":[42950],"7681":[7680],"7683":[7682],"7685":[7684],"7687":[7686],"7689":[7688],"7691":[7690],"7693":[7692],"7695":[7694],"7697":[7696],"7699":[7698],"7701":[7700],"7703":[7702],"7705":[7704],"7707":[7706],"7709":[7708],"7711":[7710],"7713":[7712],"7715":[7714],"7717":[7716],"7719":[7718],"7721":[7720],"7723":[7722],"7725":[7724],"7727":[7726],"7729":[7728],"7731":[7730],"7733":[7732],"7735":[7734],"7737":[7736],"7739":[7738],"7741":[7740],"7743":[7742],"7745":[7744],"7747":[7746],"7749":[7748],"7751":[7750],"7753":[7752],"7755":[7754],"7757":[7756],"7759":[7758],"7761":[7760],"7763":[7762],"7765":[7764],"7767":[7766],"7769":[7768],"7771":[7770],"7773":[7772],"7775":[7774],"7777":[7776],"7779":[7778],"7781":[7780],"7783":[7782],"7785":[7784],"7787":[7786],"7789":[7788],"7791":[7790],"7793":[7792],"7795":[7794],"7797":[7796],"7799":[7798],"7801":[7800],"7803":[7802],"7805":[7804],"7807":[7806],"7809":[7808],"7811":[7810],"7813":[7812],"7815":[7814],"7817":[7816],"7819":[7818],"7821":[7820],"7823":[7822],"7825":[7824],"7827":[7826],"7829":[7828],"7835":[7776],"7841":[7840],"7843":[7842],"7845":[7844],"7847":[7846],"7849":[7848],"7851":[7850],"7853":[7852],"7855":[7854],"7857":[7856],"7859":[7858],"7861":[7860],"7863":[7862],"7865":[7864],"7867":[7866],"7869":[7868],"7871":[7870],"7873":[7872],"7875":[7874],"7877":[7876],"7879":[7878],"7881":[7880],"7883":[7882],"7885":[7884],"7887":[7886],"7889":[7888],"7891":[7890],"7893":[7892],"7895":[7894],"7897":[7896],"7899":[7898],"7901":[7900],"7903":[7902],"7905":[7904],"7907":[7906],"7909":[7908],"7911":[7910],"7913":[7912],"7915":[7914],"7917":[7916],"7919":[7918],"7921":[7920],"7923":[7922],"7925":[7924],"7927":[7926],"7929":[7928],"7931":[7930],"7933":[7932],"7935":[7934],"7936":[7944],"7937":[7945],"7938":[7946],"7939":[7947],"7940":[7948],"7941":[7949],"7942":[7950],"7943":[7951],"7952":[7960],"7953":[7961],"7954":[7962],"7955":[7963],"7956":[7964],"7957":[7965],"7968":[7976],"7969":[7977],"7970":[7978],"7971":[7979],"7972":[7980],"7973":[7981],"7974":[7982],"7975":[7983],"7984":[7992],"7985":[7993],"7986":[7994],"7987":[7995],"7988":[7996],"7989":[7997],"7990":[7998],"7991":[7999],"8000":[8008],"8001":[8009],"8002":[8010],"8003":[8011],"8004":[8012],"8005":[8013],"8017":[8025],"8019":[8027],"8021":[8029],"8023":[8031],"8032":[8040],"8033":[8041],"8034":[8042],"8035":[8043],"8036":[8044],"8037":[8045],"8038":[8046],"8039":[8047],"8048":[8122],"8049":[8123],"8050":[8136],"8051":[8137],"8052":[8138],"8053":[8139],"8054":[8154],"8055":[8155],"8056":[8184],"8057":[8185],"8058":[8170],"8059":[8171],"8060":[8186],"8061":[8187],"8064":[7944,921],"8065":[7945,921],"8066":[7946,921],"8067":[7947,921],"8068":[7948,921],"8069":[7949,921],"8070":[7950,921],"8071":[7951,921],"8080":[7976,921],"8081":[7977,921],"8082":[7978,921],"8083":[7979,921],"8084":[7980,921],"8085":[7981,921],"8086":[7982,921],"8087":[7983,921],"8096":[8040,921],"8097":[8041,921],"8098":[8042,921],"8099":[8043,921],"8100":[8044,921],"8101":[8045,921],"8102":[8046,921],"8103":[8047,921],"8112":[8120],"8113":[8121],"8115":[913,921],"8126":[921],"8131":[919,921],"8144":[8152],"8145":[8153],"8160":[8168],"8161":[8169],"8165":[8172],"8179":[937,921],"8526":[8498],"8560":[8544],"8561":[8545],"8562":[8546],"8563":[8547],"8564":[8548],"8565":[8549],"8566":[8550],"8567":[8551],"8568":[8552],"8569":[8553],"8570":[8554],"8571":[8555],"8572":[8556],"8573":[8557],"8574":[8558],"8575":[8559],"8580":[8579],"9424":[9398],"9425":[9399],"9426":[9400],"9427":[9401],"9428":[9402],"9429":[9403],"9430":[9404],"9431":[9405],"9432":[9406],"9433":[9407],"9434":[9408],"9435":[9409],"9436":[9410],"9437":[9411],"9438":[9412],"9439":[9413],"9440":[9414],"9441":[9415],"9442":[9416],"9443":[9417],"9444":[9418],"9445":[9419],"9446":[9420],"9447":[9421],"9448":[9422],"9449":[9423],"11312":[11264],"11313":[11265],"11314":[11266],"11315":[11267],"11316":[11268],"11317":[11269],"11318":[11270],"11319":[11271],"11320":[11272],"11321":[11273],"11322":[11274],"11323":[11275],"11324":[11276],"11325":[11277],"11326":[11278],"11327":[11279],"11328":[11280],"11329":[11281],"11330":[11282],"11331":[11283],"11332":[11284],"11333":[11285],"11334":[11286],"11335":[11287],"11336":[11288],"11337":[11289],"11338":[11290],"11339":[11291],"11340":[11292],"11341":[11293],"11342":[11294],"11343":[11295],"11344":[11296],"11345":[11297],"11346":[11298],"11347":[11299],"11348":[11300],"11349":[11301],"11350":[11302],"11351":[11303],"11352":[11304],"11353":[11305],"11354":[11306],"11355":[11307],"11356":[11308],"11357":[11309],"11358":[11310],"11359":[11311],"11361":[11360],"11365":[570],"11366":[574],"11368":[11367],"11370":[11369],"11372":[11371],"11379":[11378],"11382":[11381],"11393":[11392],"11395":[11394],"11397":[11396],"11399":[11398],"11401":[11400],"11403":[11402],"11405":[11404],"11407":[11406],"11409":[11408],"11411":[11410],"11413":[11412],"11415":[11414],"11417":[11416],"11419":[11418],"11421":[11420],"11423":[11422],"11425":[11424],"11427":[11426],"11429":[11428],"11431":[11430],"11433":[11432],"11435":[11434],"11437":[11436],"11439":[11438],"11441":[11440],"11443":[11442],"11445":[11444],"11447":[11446],"11449":[11448],"11451":[11450],"11453":[11452],"11455":[11454],"11457":[11456],"11459":[11458],"11461":[11460],"11463":[11462],"11465":[11464],"11467":[11466],"11469":[11468],"11471":[11470],"11473":[11472],"11475":[11474],"11477":[11476],"11479":[11478],"11481":[11480],"11483":[11482],"11485":[11484],"11487":[11486],"11489":[11488],"11491":[11490],"11500":[11499],"11502":[11501],"11507":[11506],"11520":[4256],"11521":[4257],"11522":[4258],"11523":[4259],"11524":[4260],"11525":[4261],"11526":[4262],"11527":[4263],"11528":[4264],"11529":[4265],"11530":[4266],"11531":[4267],"11532":[4268],"11533":[4269],"11534":[4270],"11535":[4271],"11536":[4272],"11537":[4273],"11538":[4274],"11539":[4275],"11540":[4276],"11541":[4277],"11542":[4278],"11543":[4279],"11544":[4280],"11545":[4281],"11546":[4282],"11547":[4283],"11548":[4284],"11549":[4285],"11550":[4286],"11551":[4287],"11552":[4288],"11553":[4289],"11554":[4290],"11555":[4291],"11556":[4292],"11557":[4293],"11559":[4295],"11565":[4301],"42561":[42560],"42563":[42562],"42565":[42564],"42567":[42566],"42569":[42568],"42571":[42570],"42573":[42572],"42575":[42574],"42577":[42576],"42579":[42578],"42581":[42580],"42583":[42582],"42585":[42584],"42587":[42586],"42589":[42588],"42591":[42590],"42593":[42592],"42595":[42594],"42597":[42596],"42599":[42598],"42601":[42600],"42603":[42602],"42605":[42604],"42625":[42624],"42627":[42626],"42629":[42628],"42631":[42630],"42633":[42632],"42635":[42634],"42637":[42636],"42639":[42638],"42641":[42640],"42643":[42642],"42645":[42644],"42647":[42646],"42649":[42648],"42651":[42650],"42787":[42786],"42789":[42788],"42791":[42790],"42793":[42792],"42795":[42794],"42797":[42796],"42799":[42798],"42803":[42802],"42805":[42804],"42807":[42806],"42809":[42808],"42811":[42810],"42813":[42812],"42815":[42814],"42817":[42816],"42819":[42818],"42821":[42820],"42823":[42822],"42825":[42824],"42827":[42826],"42829":[42828],"42831":[42830],"42833":[42832],"42835":[42834],"42837":[42836],"42839":[42838],"42841":[42840],"42843":[42842],"42845":[42844],"42847":[42846],"42849":[42848],"42851":[42850],"42853":[42852],"42855":[42854],"42857":[42856],"42859":[42858],"42861":[42860],"42863":[42862],"42874":[42873],"42876":[42875],"42879":[42878],"42881":[42880],"42883":[42882],"42885":[42884],"42887":[42886],"42892":[42891],"42897":[42896],"42899":[42898],"42900":[42948],"42903":[42902],"42905":[42904],"42907":[42906],"42909":[42908],"42911":[42910],"42913":[42912],"42915":[42914],"42917":[42916],"42919":[42918],"42921":[42920],"42933":[42932],"42935":[42934],"42937":[42936],"42939":[42938],"42941":[42940],"42943":[42942],"42945":[42944],"42947":[42946],"42952":[42951],"42954":[42953],"42957":[42956],"42959":[42958],"42961":[42960],"42963":[42962],"42965":[42964],"42967":[42966],"42969":[42968],"42971":[42970],"42998":[42997],"43859":[42931],"43888":[5024],"43889":[5025],"43890":[5026],"43891":[5027],"43892":[5028],"43893":[5029],"43894":[5030],"43895":[5031],"43896":[5032],"43897":[5033],"43898":[5034],"43899":[5035],"43900":[5036],"43901":[5037],"43902":[5038],"43903":[5039],"43904":[5040],"43905":[5041],"43906":[5042],"43907":[5043],"43908":[5044],"43909":[5045],"43910":[5046],"43911":[5047],"43912":[5048],"43913":[5049],"43914":[5050],"43915":[5051],"43916":[5052],"43917":[5053],"43918":[5054],"43919":[5055],"43920":[5056],"43921":[5057],"43922":[5058],"43923":[5059],"43924":[5060],"43925":[5061],"43926":[5062],"43927":[5063],"43928":[5064],"43929":[5065],"43930":[5066],"43931":[5067],"43932":[5068],"43933":[5069],"43934":[5070],"43935":[5071],"43936":[5072],"43937":[5073],"43938":[5074],"43939":[5075],"43940":[5076],"43941":[5077],"43942":[5078],"43943":[5079],"43944":[5080],"43945":[5081],"43946":[5082],"43947":[5083],"43948":[5084],"43949":[5085],"43950":[5086],"43951":[5087],"43952":[5088],"43953":[5089],"43954":[5090],"43955":[5091],"43956":[5092],"43957":[5093],"43958":[5094],"43959":[5095],"43960":[5096],"43961":[5097],"43962":[5098],"43963":[5099],"43964":[5100],"43965":[5101],"43966":[5102],"43967":[5103],"65345":[65313],"65346":[65314],"65347":[65315],"65348":[65316],"65349":[65317],"65350":[65318],"65351":[65319],"65352":[65320],"65353":[65321],"65354":[65322],"65355":[65323],"65356":[65324],"65357":[65325],"65358":[65326],"65359":[65327],"65360":[65328],"65361":[65329],"65362":[65330],"65363":[65331],"65364":[65332],"65365":[65333],"65366":[65334],"65367":[65335],"65368":[65336],"65369":[65337],"65370":[65338],"66600":[66560],"66601":[66561],"66602":[66562],"66603":[66563],"66604":[66564],"66605":[66565],"66606":[66566],"66607":[66567],"66608":[66568],"66609":[66569],"66610":[66570],"66611":[66571],"66612":[66572],"66613":[66573],"66614":[66574],"66615":[66575],"66616":[66576],"66617":[66577],"66618":[66578],"66619":[66579],"66620":[66580],"66621":[66581],"66622":[66582],"66623":[66583],"66624":[66584],"66625":[66585],"66626":[66586],"66627":[66587],"66628":[66588],"66629":[66589],"66630":[66590],"66631":[66591],"66632":[66592],"66633":[66593],"66634":[66594],"66635":[66595],"66636":[66596],"66637":[66597],"66638":[66598],"66639":[66599],"66776":[66736],"66777":[66737],"66778":[66738],"66779":[66739],"66780":[66740],"66781":[66741],"66782":[66742],"66783":[66743],"66784":[66744],"66785":[66745],"66786":[66746],"66787":[66747],"66788":[66748],"66789":[66749],"66790":[66750],"66791":[66751],"66792":[66752],"66793":[66753],"66794":[66754],"66795":[66755],"66796":[66756],"66797":[66757],"66798":[66758],"66799":[66759],"66800":[66760],"66801":[66761],"66802":[66762],"66803":[66763],"66804":[66764],"66805":[66765],"66806":[66766],"66807":[66767],"66808":[66768],"66809":[66769],"66810":[66770],"66811":[66771],"66967":[66928],"66968":[66929],"66969":[66930],"66970":[66931],"66971":[66932],"66972":[66933],"66973":[66934],"66974":[66935],"66975":[66936],"66976":[66937],"66977":[66938],"66979":[66940],"66980":[66941],"66981":[66942],"66982":[66943],"66983":[66944],"66984":[66945],"66985":[66946],"66986":[66947],"66987":[66948],"66988":[66949],"66989":[66950],"66990":[66951],"66991":[66952],"66992":[66953],"66993":[66954],"66995":[66956],"66996":[66957],"66997":[66958],"66998":[66959],"66999":[66960],"67000":[66961],"67001":[66962],"67003":[66964],"67004":[66965],"68800":[68736],"68801":[68737],"68802":[68738],"68803":[68739],"68804":[68740],"68805":[68741],"68806":[68742],"68807":[68743],"68808":[68744],"68809":[68745],"68810":[68746],"68811":[68747],"68812":[68748],"68813":[68749],"68814":[68750],"68815":[68751],"68816":[68752],"68817":[68753],"68818":[68754],"68819":[68755],"68820":[68756],"68821":[68757],"68822":[68758],"68823":[68759],"68824":[68760],"68825":[68761],"68826":[68762],"68827":[68763],"68828":[68764],"68829":[68765],"68830":[68766],"68831":[68767],"68832":[68768],"68833":[68769],"68834":[68770],"68835":[68771],"68836":[68772],"68837":[68773],"68838":[68774],"68839":[68775],"68840":[68776],"68841":[68777],"68842":[68778],"68843":[68779],"68844":[68780],"68845":[68781],"68846":[68782],"68847":[68783],"68848":[68784],"68849":[68785],"68850":[68786],"68976":[68944],"68977":[68945],"68978":[68946],"68979":[68947],"68980":[68948],"68981":[68949],"68982":[68950],"68983":[68951],"68984":[68952],"68985":[68953],"68986":[68954],"68987":[68955],"68988":[68956],"68989":[68957],"68990":[68958],"68991":[68959],"68992":[68960],"68993":[68961],"68994":[68962],"68995":[68963],"68996":[68964],"68997":[68965],"71872":[71840],"71873":[71841],"71874":[71842],"71875":[71843],"71876":[71844],"71877":[71845],"71878":[71846],"71879":[71847],"71880":[71848],"71881":[71849],"71882":[71850],"71883":[71851],"71884":[71852],"71885":[71853],"71886":[71854],"71887":[71855],"71888":[71856],"71889":[71857],"71890":[71858],"71891":[71859],"71892":[71860],"71893":[71861],"71894":[71862],"71895":[71863],"71896":[71864],"71897":[71865],"71898":[71866],"71899":[71867],"71900":[71868],"71901":[71869],"71902":[71870],"71903":[71871],"93792":[93760],"93793":[93761],"93794":[93762],"93795":[93763],"93796":[93764],"93797":[93765],"93798":[93766],"93799":[93767],"93800":[93768],"93801":[93769],"93802":[93770],"93803":[93771],"93804":[93772],"93805":[93773],"93806":[93774],"93807":[93775],"93808":[93776],"93809":[93777],"93810":[93778],"93811":[93779],"93812":[93780],"93813":[93781],"93814":[93782],"93815":[93783],"93816":[93784],"93817":[93785],"93818":[93786],"93819":[93787],"93820":[93788],"93821":[93789],"93822":[93790],"93823":[93791],"93883":[93856],"93884":[93857],"93885":[93858],"93886":[93859],"93887":[93860],"93888":[93861],"93889":[93862],"93890":[93863],"93891":[93864],"93892":[93865],"93893":[93866],"93894":[93867],"93895":[93868],"93896":[93869],"93897":[93870],"93898":[93871],"93899":[93872],"93900":[93873],"93901":[93874],"93902":[93875],"93903":[93876],"93904":[93877],"93905":[93878],"93906":[93879],"93907":[93880],"125218":[125184],"125219":[125185],"125220":[125186],"125221":[125187],"125222":[125188],"125223":[125189],"125224":[125190],"125225":[125191],"125226":[125192],"125227":[125193],"125228":[125194],"125229":[125195],"125230":[125196],"125231":[125197],"125232":[125198],"125233":[125199],"125234":[125200],"125235":[125201],"125236":[125202],"125237":[125203],"125238":[125204],"125239":[125205],"125240":[125206],"125241":[125207],"125242":[125208],"125243":[125209],"125244":[125210],"125245":[125211],"125246":[125212],"125247":[125213],"125248":[125214],"125249":[125215],"125250":[125216],"125251":[125217],"223":[83,83],"64256":[70,70],"64257":[70,73],"64258":[70,76],"64259":[70,70,73],"64260":[70,70,76],"64261":[83,84],"64262":[83,84],"1415":[1333,1362],"64275":[1348,1350],"64276":[1348,1333],"64277":[1348,1339],"64278":[1358,1350],"64279":[1348,1341],"329":[700,78],"912":[921,776,769],"944":[933,776,769],"496":[74,780],"7830":[72,817],"7831":[84,776],"7832":[87,778],"7833":[89,778],"7834":[65,702],"8016":[933,787],"8018":[933,787,768],"8020":[933,787,769],"8022":[933,787,834],"8118":[913,834],"8134":[919,834],"8146":[921,776,768],"8147":[921,776,769],"8150":[921,834],"8151":[921,776,834],"8162":[933,776,768],"8163":[933,776,769],"8164":[929,787],"8166":[933,834],"8167":[933,776,834],"8182":[937,834],"8072":[7944,921],"8073":[7945,921],"8074":[7946,921],"8075":[7947,921],"8076":[7948,921],"8077":[7949,921],"8078":[7950,921],"8079":[7951,921],"8088":[7976,921],"8089":[7977,921],"8090":[7978,921],"8091":[7979,921],"8092":[7980,921],"8093":[7981,921],"8094":[7982,921],"8095":[7983,921],"8104":[8040,921],"8105":[8041,921],"8106":[8042,921],"8107":[8043,921],"8108":[8044,921],"8109":[8045,921],"8110":[8046,921],"8111":[8047,921],"8124":[913,921],"8140":[919,921],"8188":[937,921],"8114":[8122,921],"8116":[902,921],"8130":[8138,921],"8132":[905,921],"8178":[8186,921],"8180":[911,921],"8119":[913,834,921],"8135":[919,834,921],"8183":[937,834,921]},"cased":[65,90,97,122,170,170,181,181,186,186,192,214,216,246,248,442,444,447,452,659,662,687,688,696,704,705,736,740,837,837,880,883,886,887,890,890,891,893,895,895,902,902,904,906,908,908,910,929,931,1013,1015,1153,1162,1327,1329,1366,1376,1416,4256,4293,4295,4295,4301,4301,4304,4346,4348,4348,4349,4351,5024,5109,5112,5117,7296,7306,7312,7354,7357,7359,7424,7467,7468,7530,7531,7543,7544,7544,7545,7578,7579,7615,7680,7957,7960,7965,7968,8005,8008,8013,8016,8023,8025,8025,8027,8027,8029,8029,8031,8061,8064,8116,8118,8124,8126,8126,8130,8132,8134,8140,8144,8147,8150,8155,8160,8172,8178,8180,8182,8188,8305,8305,8319,8319,8336,8348,8450,8450,8455,8455,8458,8467,8469,8469,8473,8477,8484,8484,8486,8486,8488,8488,8490,8493,8495,8500,8505,8505,8508,8511,8517,8521,8526,8526,8544,8575,8579,8580,9398,9449,11264,11387,11388,11389,11390,11492,11499,11502,11506,11507,11520,11557,11559,11559,11565,11565,42560,42605,42624,42651,42652,42653,42786,42863,42864,42864,42865,42887,42891,42894,42896,42972,42993,42996,42997,42998,43000,43001,43002,43002,43824,43866,43868,43871,43872,43880,43881,43881,43888,43967,64256,64262,64275,64279,65313,65338,65345,65370,66560,66639,66736,66771,66776,66811,66928,66938,66940,66954,66956,66962,66964,66965,66967,66977,66979,66993,66995,67001,67003,67004,67456,67456,67459,67461,67463,67504,67506,67514,68736,68786,68800,68850,68944,68965,68976,68997,71840,71903,93760,93823,93856,93880,93883,93907,119808,119892,119894,119964,119966,119967,119970,119970,119973,119974,119977,119980,119982,119993,119995,119995,119997,120003,120005,120069,120071,120074,120077,120084,120086,120092,120094,120121,120123,120126,120128,120132,120134,120134,120138,120144,120146,120485,120488,120512,120514,120538,120540,120570,120572,120596,120598,120628,120630,120654,120656,120686,120688,120712,120714,120744,120746,120770,120772,120779,122624,122633,122635,122654,122661,122666,122928,122989,125184,125251,127280,127305,127312,127337,127344,127369],"ignorable":[39,39,46,46,58,58,94,94,96,96,168,168,173,173,175,175,180,180,183,183,184,184,688,705,706,709,710,721,722,735,736,740,741,747,748,748,749,749,750,750,751,767,768,879,884,884,885,885,890,890,900,901,903,903,1155,1159,1160,1161,1369,1369,1375,1375,1425,1469,1471,1471,1473,1474,1476,1477,1479,1479,1524,1524,1536,1541,1552,1562,1564,1564,1600,1600,1611,1631,1648,1648,1750,1756,1757,1757,1759,1764,1765,1766,1767,1768,1770,1773,1807,1807,1809,1809,1840,1866,1958,1968,2027,2035,2036,2037,2042,2042,2045,2045,2070,2073,2074,2074,2075,2083,2084,2084,2085,2087,2088,2088,2089,2093,2137,2139,2184,2184,2192,2193,2199,2207,2249,2249,2250,2273,2274,2274,2275,2306,2362,2362,2364,2364,2369,2376,2381,2381,2385,2391,2402,2403,2417,2417,2433,2433,2492,2492,2497,2500,2509,2509,2530,2531,2558,2558,2561,2562,2620,2620,2625,2626,2631,2632,2635,2637,2641,2641,2672,2673,2677,2677,2689,2690,2748,2748,2753,2757,2759,2760,2765,2765,2786,2787,2810,2815,2817,2817,2876,2876,2879,2879,2881,2884,2893,2893,2901,2902,2914,2915,2946,2946,3008,3008,3021,3021,3072,3072,3076,3076,3132,3132,3134,3136,3142,3144,3146,3149,3157,3158,3170,3171,3201,3201,3260,3260,3263,3263,3270,3270,3276,3277,3298,3299,3328,3329,3387,3388,3393,3396,3405,3405,3426,3427,3457,3457,3530,3530,3538,3540,3542,3542,3633,3633,3636,3642,3654,3654,3655,3662,3761,3761,3764,3772,3782,3782,3784,3790,3864,3865,3893,3893,3895,3895,3897,3897,3953,3966,3968,3972,3974,3975,3981,3991,3993,4028,4038,4038,4141,4144,4146,4151,4153,4154,4157,4158,4184,4185,4190,4192,4209,4212,4226,4226,4229,4230,4237,4237,4253,4253,4348,4348,4957,4959,5906,5908,5938,5939,5970,5971,6002,6003,6068,6069,6071,6077,6086,6086,6089,6099,6103,6103,6109,6109,6155,6157,6158,6158,6159,6159,6211,6211,6277,6278,6313,6313,6432,6434,6439,6440,6450,6450,6457,6459,6679,6680,6683,6683,6742,6742,6744,6750,6752,6752,6754,6754,6757,6764,6771,6780,6783,6783,6823,6823,6832,6845,6846,6846,6847,6877,6880,6891,6912,6915,6964,6964,6966,6970,6972,6972,6978,6978,7019,7027,7040,7041,7074,7077,7080,7081,7083,7085,7142,7142,7144,7145,7149,7149,7151,7153,7212,7219,7222,7223,7288,7293,7376,7378,7380,7392,7394,7400,7405,7405,7412,7412,7416,7417,7468,7530,7544,7544,7579,7615,7616,7679,8125,8125,8127,8129,8141,8143,8157,8159,8173,8175,8189,8190,8203,8207,8216,8216,8217,8217,8228,8228,8231,8231,8234,8238,8288,8292,8294,8303,8305,8305,8319,8319,8336,8348,8400,8412,8413,8416,8417,8417,8418,8420,8421,8432,11388,11389,11503,11505,11631,11631,11647,11647,11744,11775,11823,11823,12293,12293,12330,12333,12337,12341,12347,12347,12441,12442,12443,12444,12445,12446,12540,12542,40981,40981,42232,42237,42508,42508,42607,42607,42608,42610,42612,42621,42623,42623,42652,42653,42654,42655,42736,42737,42752,42774,42775,42783,42784,42785,42864,42864,42888,42888,42889,42890,42993,42996,43000,43001,43010,43010,43014,43014,43019,43019,43045,43046,43052,43052,43204,43205,43232,43249,43263,43263,43302,43309,43335,43345,43392,43394,43443,43443,43446,43449,43452,43453,43471,43471,43493,43493,43494,43494,43561,43566,43569,43570,43573,43574,43587,43587,43596,43596,43632,43632,43644,43644,43696,43696,43698,43700,43703,43704,43710,43711,43713,43713,43741,43741,43756,43757,43763,43764,43766,43766,43867,43867,43868,43871,43881,43881,43882,43883,44005,44005,44008,44008,44013,44013,64286,64286,64434,64450,65024,65039,65043,65043,65056,65071,65106,65106,65109,65109,65279,65279,65287,65287,65294,65294,65306,65306,65342,65342,65344,65344,65392,65392,65438,65439,65507,65507,65529,65531,66045,66045,66272,66272,66422,66426,67456,67461,67463,67504,67506,67514,68097,68099,68101,68102,68108,68111,68152,68154,68159,68159,68325,68326,68900,68903,68942,68942,68969,68973,68975,68975,69291,69292,69317,69317,69370,69375,69446,69456,69506,69509,69633,69633,69688,69702,69744,69744,69747,69748,69759,69761,69811,69814,69817,69818,69821,69821,69826,69826,69837,69837,69888,69890,69927,69931,69933,69940,70003,70003,70016,70017,70070,70078,70089,70092,70095,70095,70191,70193,70196,70196,70198,70199,70206,70206,70209,70209,70367,70367,70371,70378,70400,70401,70459,70460,70464,70464,70502,70508,70512,70516,70587,70592,70606,70606,70608,70608,70610,70610,70625,70626,70712,70719,70722,70724,70726,70726,70750,70750,70835,70840,70842,70842,70847,70848,70850,70851,71090,71093,71100,71101,71103,71104,71132,71133,71219,71226,71229,71229,71231,71232,71339,71339,71341,71341,71344,71349,71351,71351,71453,71453,71455,71455,71458,71461,71463,71467,71727,71735,71737,71738,71995,71996,71998,71998,72003,72003,72148,72151,72154,72155,72160,72160,72193,72202,72243,72248,72251,72254,72263,72263,72273,72278,72281,72283,72330,72342,72344,72345,72544,72544,72546,72548,72550,72550,72752,72758,72760,72765,72767,72767,72850,72871,72874,72880,72882,72883,72885,72886,73009,73014,73018,73018,73020,73021,73023,73029,73031,73031,73104,73105,73109,73109,73111,73111,73177,73177,73459,73460,73472,73473,73526,73530,73536,73536,73538,73538,73562,73562,78896,78911,78912,78912,78919,78933,90398,90409,90413,90415,92912,92916,92976,92982,92992,92995,93504,93506,93547,93548,94031,94031,94095,94098,94099,94111,94176,94177,94179,94179,94180,94180,94194,94195,110576,110579,110581,110587,110589,110590,113821,113822,113824,113827,118528,118573,118576,118598,119143,119145,119155,119162,119163,119170,119173,119179,119210,119213,119362,119364,121344,121398,121403,121452,121461,121461,121476,121476,121499,121503,121505,121519,122880,122886,122888,122904,122907,122913,122915,122916,122918,122922,122928,122989,123023,123023,123184,123190,123191,123197,123566,123566,123628,123631,124139,124139,124140,124143,124398,124399,124643,124643,124646,124646,124654,124655,124661,124661,124671,124671,125136,125142,125252,125258,125259,125259,127995,127999,917505,917505,917536,917631,917760,917999]};
// END GENERATED UNICODE CASE DATA
