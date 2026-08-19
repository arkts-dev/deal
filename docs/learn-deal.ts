// Learn DEAL in 15 Minutes (DEAL v1.2)

// Two dashes start a one-line comment.

/*  Adding * and / makes it a
    multi-line comment.  */

// -----------------------------------------------------
// 1. Variables and flow control.
// -----------------------------------------------------

// All variables are declared with let.
let n: int = 42;       // int is a signed 32-bit exact integer type.
let x: number = 3.14;  // number is IEEE 754 double.
let s: string = "hello";
let b: boolean = true;
let nothing: null = null;

// Types are strict — no implicit conversion.
// let bad: int = 3.14;  // compile-time error

// int values live in [-2147483648, 2147483647]; out-of-range integer
// literals are compile-time errors, and int arithmetic raises a runtime
// error on overflow.
// let huge: int = 2147483648;  // compile-time error
// let min: int = -2147483648;  // ok: unary minus over the special token

// Inference works when the type is unambiguous.
let meaning = 42;       // int
let pi = 3.14;          // number
let greeting = "hi";    // string
let flag = true;        // boolean

// Blocks use { } — same as TypeScript.
if (n > 0) {
  n = n + 1;
} else {
  n = 0;
}

// Conditions must be boolean.
// if (n) { }  // error: int is not boolean

// Loops — C-style for, while, for-of.
let sum: int = 0;
for (let i: int = 0; i < 10; i = i + 1) {
  sum = sum + i;
}

while (sum > 0) {
  sum = sum - 1;
}

// for-of over arrays and strings (one Unicode scalar value per string
// iteration in v1.2):
let xs: int[] = [1, 2, 3];
for (let v: int of xs) {
  // v = 1, 2, 3
}

let word: string = "abc";
for (let c: string of word) {
  // c = "a", "b", "c"
}

// Template literals with string interpolation:
let msg: string = `Sum is ${sum}`;
// ${} must contain a string expression — no implicit conversion.

// null is the only null-ish value. T | null for nullable.
let maybe: int | null = 42;
maybe = null;  // ok

// null narrowing with !== null:
if (maybe !== null) {
  let safe: int = maybe;  // narrowed to int
}

// -----------------------------------------------------
// 2. Functions.
// -----------------------------------------------------

// Functions require parameter and return type annotations.
function add(a: int, b: int): int {
  return a + b;
}

// v1.2 removed rest parameters: pass an explicit array instead.
function sumAll(values: int[]): int {
  let total: int = 0;
  for (let i: int = 0; i < values.length; i = i + 1) {
    total = total + values[i];
  }
  return total;
}

// Nullable return:
function divide(a: int, b: int): int | null {
  if (b === 0) {
    return null;
  }
  return a / b;  // integer division, truncates toward zero
}

// Function values are first-class typed values.
let op: (a: int, b: int) => int = add;
let r: int = op(3, 4);  // 7

// Arity extension — fewer-param function adapts to more.
function one(x: int): int { return x; }
let two: (x: int, y: int) => int = one;  // second arg ignored

// Function expressions (with closures):
let counter = function(): () => int {
  let i: int = 0;
  return function(): int {
    i = i + 1;
    return i;
  };
};

// Async functions for non-blocking code:
async function fetch(id: int): string | null {
  return null;  // placeholder — await host calls here
}

async function fetchAndUse(id: int): string {
  let data: string | null = await fetch(id);
  if (data !== null) {
    return data;
  }
  return "";
}
// await is only valid inside async function bodies, applied to a direct
// async call; an async call without await is a compile-time error.

// -----------------------------------------------------
// 3. Tables.
// -----------------------------------------------------

// table is a dynamic string-keyed container.
let t: table = { name: "Ada", age: 36 };

// Reads require a target type (runtime-checked):
let name: string = t.name;  // ok
let age: int = t.age;       // ok, runtime check

// Untyped read is an error:
// let x = t.name;  // compile-time error

// Writes are unrestricted:
t.key = "value";
t.flag = true;
t.n = null;

// The value null is distinct from field absence.
t.n = null;     // field exists, value is null
delete t.n;     // field is removed

// Tables are data containers — calling through a table is forbidden.
// let r: int = t.f(1, 2);  // compile-time error

// Iteration via std/table:
import * as tables from "std/table";
let ks: string[] = tables.keys(t);
for (let i: int = 0; i < ks.length; i = i + 1) {
  let k: string = ks[i];
  let v: int = t[k];
}

// -----------------------------------------------------
// 4. Arrays and bytes.
// -----------------------------------------------------

// T[] is a 0-based array.
let nums: int[] = [10, 20, 30];
let first: int = nums[0];   // 10
let last: int = nums[2];    // 30

// length is a compiler-resolved property:
let count: int = nums.length;  // 3

// Append at length:
nums[nums.length] = 40;  // [10, 20, 30, 40]

// Writes are compile-time and runtime checked:
nums[0] = 99;           // ok
// nums[0] = "hi";           // compile-time error: string ≠ int
// nums[99] = 1;             // runtime error: out of bounds

// Nullable arrays:
let mixed: (int | null)[] = [1, null, 3];
let m: int | null = mixed[1];  // null

// bytes is a DEAL-owned mutable byte buffer (v1.2):
// let buf: bytes = bytes(16);   // zero-filled, length 16
// buf[0] = 255;                 // writes require int values in 0..255
// let hi: int = buf[0];         // reads yield int values in 0..255
// let nbytes: int = buf.length; // compiler-resolved length intrinsic

// -----------------------------------------------------
// 5. Classes.
// -----------------------------------------------------

// class declares a nominal record type — data, not behavior.
class User {
  name: string = "";
  nick?: string;                // optional field, may be missing
  bio: string | null = null;    // nullable field
  active: boolean = true;
}

// Construction via object literal:
let u: User = { name: "Ada", nick: "beeb" };

// Fields receive defaults when omitted:
let v: User = {};  // name: "", active: true, bio: null

// Field access:
let n2: string = u.name;              // required-present → string
let maybeNick: string | null = u.nick; // optional → string | null

// has() checks optional field presence:
let present: boolean = has(u.nick);  // true
// has() does not narrow — field remains T | null

// Extra fields are rejected:
// let bad: User = { name: "Bob", unknown: 1 };  // error

// Nominal typing — different classes are incompatible:
class Point { x: number = 0.0; y: number = 0.0; }
class Vector { x: number = 0.0; y: number = 0.0; }
// let p: Point = { x: 1.0, y: 2.0 };
// let w: Vector = p;  // error: Point is not Vector

// JSON serialization with the @jsonable directive (v1.2):
// @jsonable
// export class User { ... }
// Compiler generates User$fromJson(s): User | null
// and User$toJson(u): string

// -----------------------------------------------------
// 6. Modules.
// -----------------------------------------------------

// Export functions and classes:
export function greet(name: string): string {
  return `Hello ${name}`;
}

export class Person {
  name: string = "";
}

// Imports come first, before all other top-level declarations; at module
// top level only imports, functions, classes, and exports are allowed.
import * as math from "std/math";
let f: number = math.floor(3.14);

// Relative imports:
// import * as lib from "./lib";

// No named imports, no default export, no re-export.

// When a compiler invocation selects an entry module, that module must
// export main with the non-async signature (): null.
// export function main(): null { return null; }

// C FFI declaration files (v1.2) start with // @extern-c and may mark
// exported classes with // @c-struct or // @c-pointer:
// // @extern-c
// // @c-struct
// export class Vec2 { x: number = 0.0; y: number = 0.0; }

// -----------------------------------------------------
// 7. Error handling.
// -----------------------------------------------------

// Error is a builtin class:
// throw an error:
// throw { message: "something went wrong" };

// try/catch:
// try {
//   let x: int = nums[99];
// } catch (e) {
//   // e: Error
//   console.log(e.message);
// }

// Errors propagate through function wrappers.
// Uncaught errors escape to the host.

// -----------------------------------------------------
// 8. Standard library (all under std/).
// -----------------------------------------------------

// console — logging
import * as console from "std/console";
console.log("hello");
console.error("oops");

// json — parse and stringify to/from table
import * as json from "std/json";
let t2: table = json.parse("{\"name\":\"Ada\"}");
let str: string = json.stringify(t2);

// string — string utilities (Unicode scalar-value positions in v1.2)
import * as strings from "std/string";
let parts: string[] = strings.split("a,b,c", ",");

// table — iteration
import * as tables2 from "std/table";
let keys: string[] = tables.keys(t2);

// math — number math
import * as m from "std/math";
let ceil: number = m.ceil(3.2);

// time
import * as time from "std/time";
let ms: int = time.nowMillis();

// conversion intrinsics — built-in, no import:
let i: int = int(3.0);    // number → int
let d: number = number(5); // int → number
// let buf: bytes = bytes(8); // int → bytes (v1.2)

// -----------------------------------------------------
// This file demonstrates DEAL v1.2 syntax.
// Save as learn.deal.
// -----------------------------------------------------
