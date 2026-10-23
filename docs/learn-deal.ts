// Learn DEAL — companion to docs/spec-v1.4.md, not a specification.
// The sections below are independent examples, not one source module.
// Imports precede declarations. Executable statements belong in functions.

import * as math from "std/math";
import * as strings from "std/string";
import * as json from "std/json";
import * as tables from "std/table";

// Values and control flow: strict types; boolean conditions; semicolons.
function values(): number {
  let count: number = 42;
  let fraction: number = 5 / 2; // 2.5; every number is IEEE 754 binary64.
  let label: string = "Ada";
  let message: string = `Hello ${label}`; // interpolation requires string.
  let enabled: boolean = true;
  let nothing: null = null;
  // Arithmetic rounds nearest/ties-even and does not trap on overflow
  // or division by zero. NaN, infinities, and signed zero are numbers.
  let wide: number = 2147483648;
  let rounded: number = math.floor(fraction);
  while (count > 0) {
    count = count - 1;
  }
  if (enabled) { return rounded; }
  return wide;
}

// Functions require parameter and return annotations. A caller may choose
// to reject a zero divisor; ordinary division itself is nontrapping.
function divide(a: number, b: number): number | null {
  if (b === 0) { return null; }
  return a / b;
}

function add(a: number, b: number): number { return a + b; }
function one(x: number): number { return x; }
function callbacks(): number {
  let op: (a: number, b: number) => number = add;
  let extended: (x: number, y: number) => number = one;
  return op(3, 4) + extended(2, 9); // extra adapted argument is ignored.
}

// Closures capture bindings. Loop iterations have fresh bindings.
function counter(start: number): () => number {
  let current: number = start;
  return function(): number {
    current = current + 1;
    return current;
  };
}

// Arrays are homogeneous and zero-based. Indexes must be finite whole
// numbers. Reads require index < length; writes may append at length.
function sumAll(values: number[]): number {
  let total: number = 0;
  for (let value: number of values) { total = total + value; }
  return total;
}
function arrays(): number {
  let values: number[] = [10, 20, 30];
  values[values.length] = 40;
  values[0] = 99;
  let maybe: (number | null)[] = [1, null, 3];
  let selected: number | null = maybe[1];
  if (selected !== null) { return selected; }
  return sumAll(values);
}

// Tables have dynamic string keys. Reads need a contextual target type
// and validate the stored value; writes preserve null versus absence.
function tableData(): number {
  let data: table = { name: "Ada", age: 36 };
  let age: number = data.age;
  data.note = null;
  delete data.note;
  let keys: string[] = tables.keys(data);
  for (let key: string of keys) {
    let name: string = key;
  }
  return age;
}

// Classes are module-level nominal records, not behavior containers.
// Supply required fields explicitly. Optional and nullable are distinct.
class User {
  name: string;
  nick?: string;
  bio: string | null;
  active: boolean;
}
function records(): User {
  let user: User = { name: "Ada", nick: "beeb", bio: null, active: true };
  let present: boolean = has(user.nick);
  let nick: string | null = user.nick;
  if (nick !== null) { user.name = nick; }
  delete user.nick;
  return user;
}
// Same-shaped classes remain distinct. Functions operate on records.
class Point { x: number; y: number; }
class Vector { x: number; y: number; }

// @jsonable directly precedes an exported class. Generated helpers are
// Settings$fromJson(string): Settings | null and Settings$toJson(Settings): string.
// @jsonable
export class Settings { name: string; note?: string | null; }
// Parsing rejects duplicate/extra keys and missing required fields.
// Missing optionals remain absent. Serialization omits absent fields,
// preserves null, rejects cycles/nonfinite numbers, and emits either zero
// sign as 0. JSON numeric tokens round to binary64; parsing preserves -0.

// Standard library string positions count Unicode scalars. Substring
// bounds must be finite whole numbers; valid bounds are then clamped.
function text(): string {
  let size: number = strings.length("a😀b"); // 3
  let part: string = strings.substring("a😀b", 1, 2); // 😀
  let pieces: string[] = strings.split("a,b", ",");
  return strings.trim(part);
}

// Imports use namespaces: import * as models from "./models";
// Imported classes use models.User. Any import cycle is an error.
// A manifest specifies roots, languageVersion, output, backend, externals.
// Async calls are awaited directly inside async bodies; the embedding
// owns scheduling and host implementations for declared external modules.

// Error is a builtin nominal class with string code/message fields.
function checkedRead(values: number[]): number | null {
  try {
    return values[99];
  } catch (e) {
    return null;
  }
}

// An executable entry exports a synchronous main(): null.
export function main(): null {
  let result: number = arrays();
  return null;
}
