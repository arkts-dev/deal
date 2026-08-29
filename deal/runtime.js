"use strict";

// DEAL Runtime Library v1.2 — JavaScript backend (deal/runtime.js).
// The single hand-written CommonJS runtime module exporting $rt, the JS
// analog of deal/runtime.lua (js-backend-runtime D1-D9, pinned by
// js-backend-runtime-artifact). It loads standalone under node v24 from
// "./deal/runtime" and from any "<relpath>/deal/runtime" require path —
// no require call anywhere in the file (js-backend-runtime-artifact D1/D10).

// ===== Host-global capture (js-backend-runtime-artifact D10) =====
// The only module-scope bindings of the seven host globals the runtime
// owns. Every later runtime use of process, console, Math, JSON, Map,
// Array, or undefined is confined to these captures; generated code spells
// none of them bare (js-backend-architecture D2). Object is not in the
// capture list and may be spelled bare inside this trusted file.
const $process = process;
const $console = console;
const $Math = Math;
const $JSON = JSON;
const $Map = Map;
const $Array = Array;
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
// Unspoofable WeakSets consulted only by std/json. Never exported; no $rt
// member exposes them.
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
// capture) are arrays; everything else reports the JS typeof name.
function $kindOf($v) {
  if ($v === $undefined) return "nil";
  if ($v === null) return "null";
  if ($v.$kind === "function") return "function";
  if ($v.$kind === "class") return "class";
  if ($v instanceof $Map) return "table";
  if ($Array.isArray($v)) return "array";
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
  // included so its dispatch reaches the defensive E6000 gate.
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
      // bytes: recognized by $parse so its dispatch reaches this
      // defensive E6000 gate (D3/D4) — no silent acceptance, no int32
      // pre-implementation (A1; ISSUE-0111). Reached before any check of
      // v, so it fires for every value.
      if ($parsed.$name === "bytes") {
        $rt.fail("E6000", "JS backend: bytes descriptors are not supported (ISSUE-0111)", file, line, column);
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
