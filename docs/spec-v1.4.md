# DEAL — Language Specification v1.4

DEAL (Directly Embeddable Agentic Language) is a statically typed language with TypeScript syntax and runtime-enforced types. The primary target is safe, embeddable AI scripting with explicit interoperability boundaries. This document defines the feature set, type system, and runtime execution model.

Features not described in this specification do not exist in the language.

---

## Lexical elements

- Identifiers: `[a-zA-Z_$][a-zA-Z0-9_$]*`
  - User-declared identifiers must not contain `$`.
  - Compiler-generated identifiers may contain `$`.
  - Generated identifiers are source-visible only when this specification defines them, such as `C$fromJson` and `C$toJson`.
  - A user declaration, import alias, parameter, field, or local variable containing `$` is a compile-time error.
- Keywords are defined by `Keyword` in the [lexical grammar](#lexical-grammar-normative).
  - `async` and `await` are keywords. They cannot be used as identifiers, type names, field names, import aliases, or parameter names.
  - `async` is a function/type marker and does not participate in name resolution.
  - `await` is expression syntax and does not participate in name resolution.
- Literals:
 - `null`
 - boolean: `true`, `false`
 - number: decimal digits, optionally with a fraction or exponent (e.g., `123`, `3.14`, `0.0`, `1e308`, `1.0e-3`)
  - string: `"..."` and `'...'` with `\n`, `\t`, `\\`, `\"`, `\'`
  - template literal: `` `...${expr}...` ``
- Operators:
 - `+ - * /`
 - `=== !== < <= > >=`
 - `&& || !`
 - `=` (assignment)
 - `.` (member access)
 - `[]` (index access)
 - `,` (separator)
 - `|` (nullable union)
 - `?` (optional field modifier)
 - `;` (statement terminator)
- Punctuation: `{ } ( ) [ ] : ; , =>`
- Comments: `// ... end-of-line`, `/* ... */`

### Comment semantics

Line terminators are LF (`\n`), CRLF (`\r\n`), and CR (`\r`).

- `//` comments extend to end-of-line (line-ending character consumed, not emitted).
- `/* */` comments do **not** nest. `/* /* */` ends at the first `*/`.
- Comments are equivalent to whitespace except recognized compiler directive comments.
- A compiler directive comment is a line comment matching `CompilerDirectiveComment` in the lexical grammar.
- A compiler directive comment with an unrecognized directive name is a compile-time error.
- `// @jsonable` is a declaration directive. A declaration directive block is the maximal contiguous sequence of declaration directive comments and whitespace immediately before a declaration, with no intervening non-comment token or non-directive comment. The block attaches to that declaration.
- Attaching a declaration directive to an incompatible declaration is a compile-time error.
- `// @jsonable` must have no argument other than horizontal whitespace.

### String escapes and Unicode

| Escape | Meaning |
|---|---|
| `\n` | newline (U+000A) |
| `\t` | tab (U+0009) |
| `\\` | backslash (U+005C) |
| `\"` | double quote (U+0022) |
| `\'` | single quote (U+0027) |

No octal, hexadecimal, or Unicode escape sequences. A DEAL `string` is a sequence of Unicode scalar values. Source string literals decode to scalar values. Backends may store strings in any representation, but observable string behavior is defined over scalar values. String values accepted from untrusted or backend-native boundaries must reject invalid encodings, including malformed UTF-8 byte sequences and unpaired UTF-16 surrogate code units.

### Template literals

Template literals use backtick syntax with `${expression}` interpolation:

```ts
let name: string = "Ada";
let msg: string = `Hello ${name}!`; // "Hello Ada!"
```

The expression inside `${}` must have type `string`. No implicit string conversion.

```ts
let n: number = 42;
let bad: string = `Value: ${n}`; // error: number is not string
```

```ts
let xs: string[] = ["a", "b", "c"];
let s: string = `Items: ${xs[0]}, ${xs[1]}`;
```

### Numbers

`number` is the sole numeric type. Numeric literals and arithmetic follow IEEE 754 binary64 with round-to-nearest, ties-to-even and non-trapping arithmetic.

An **integral number** is a finite `number` with no fractional part. Where required, integrality is checked at runtime without rounding or truncation.

### Lexical grammar (normative)

```antlr
SourceCharacter ::= /* any Unicode scalar value */

Token ::= Keyword | Identifier | Literal | Operator | Punctuator

Keyword ::= 'let' | 'class' | 'function' | 'async' | 'await' | 'return' | 'if' | 'else'
          | 'while' | 'for' | 'break' | 'continue'
 | 'null' | 'true' | 'false'
          | 'import' | 'export' | 'from' | 'as' | 'delete' | 'has'
          | 'try' | 'catch' | 'throw' | 'of'

Identifier ::= [a-zA-Z_$] [a-zA-Z0-9_$]*

Literal ::= NullLiteral | BooleanLiteral | NumberLiteral | StringLiteral | TemplateLiteral

NullLiteral ::= 'null'
BooleanLiteral ::= 'true' | 'false'
NumberLiteral ::= [0-9]+
 | ([0-9]* '.' [0-9]+ ([eE] [+-]? [0-9]+)?)
 | ([0-9]+ [eE] [+-]? [0-9]+)
StringLiteral ::= '"' StringCharacter* '"' | '\'' StringCharacter* '\''

TemplateLiteral ::= '`' (TemplateText | '${' Expression '}')* '`'
TemplateText ::= /* one or more characters containing no line terminator, '`', or '${' */

StringCharacter ::= /* any SourceCharacter except line terminators and unescaped quote */
 | '\n' | '\t' | '\\' | '\"' | '\''

CompilerDirectiveComment ::= '//' [ \t]* '@' DirectiveName DirectiveArgument? LineTerminator?

DirectiveName ::= 'jsonable'

DirectiveArgument ::= [^\r\n]*

LineTerminator ::= '\n' | '\r\n' | '\r'

Operator ::= '+' | '-' | '*' | '/'
 | '===' | '!==' | '<' | '<=' | '>' | '>='
 | '&&' | '||' | '!' | '=' | '.' | '=>' | '|'

Punctuator ::= '{' | '}' | '(' | ')' | '[' | ']' | ':' | ';' | ',' | '?'
```

### Syntactic grammar (normative)

This grammar defines the statement/expression separation, block semantics, assignment targets, table key syntax, function-call expression form, and operator precedence.

```antlr
Program ::= ImportDeclaration* TopLevelDeclaration*

TopLevelDeclaration ::= FunctionDeclaration
                      | ClassDeclaration
                      | ExportDeclaration

Statement       ::= FunctionDeclaration
                   | VariableDeclaration
                   | ReturnStatement
                   | IfStatement
                   | WhileStatement
                   | ForOfStatement
                   | BreakStatement
                   | ContinueStatement
                   | ExpressionStatement
                   | DeleteStatement
                   | TryStatement
                   | ThrowStatement
                   | Block

Block ::= '{' Statement* '}'

ClassDeclaration ::= 'class' Identifier '{' ClassField* '}'

ClassField ::= Identifier '?'? ':' Type ';'

FunctionDeclaration ::= 'async'? 'function' Identifier '(' ParameterList? ')' ':' Type Block

ExternalFunctionDeclaration ::= 'async'? 'function' Identifier '(' ParameterList? ')' ':' Type ';' /* declaration files only */

ParameterList ::= Parameter (',' Parameter)*

Parameter ::= Identifier ':' Type

ReturnType ::= ':' Type

VariableDeclaration ::= 'let' Identifier TypeAnnotation? '=' Expression ';'

TypeAnnotation ::= ':' Type

ExpressionStatement ::= Expression ';'

ReturnStatement ::= 'return' Expression? ';'

IfStatement ::= 'if' '(' Expression ')' Block ('else' (IfStatement | Block))?

WhileStatement ::= 'while' '(' Expression ')' Block

ForOfStatement  ::= 'for' '(' 'let' Identifier ':' Type 'of' Expression ')' Block

BreakStatement ::= 'break' ';'

ContinueStatement ::= 'continue' ';'

DeleteStatement ::= 'delete' DeleteTarget ';'

DeleteTarget    ::= PostfixExpression '.' Identifier
                  | PostfixExpression '[' Expression ']'

TryStatement    ::= 'try' Block 'catch' '(' Identifier ')' Block

ThrowStatement  ::= 'throw' Expression ';'

ImportDeclaration ::= 'import' '*' 'as' Identifier 'from' StringLiteral ';'

ExportDeclaration ::= ImplementationExport

ImplementationExport ::= 'export' (FunctionDeclaration | ClassDeclaration)

DeclarationExport ::= 'export' (ExternalFunctionDeclaration | ClassDeclaration)

/* Expressions — precedence groups (highest to lowest):
 * 1. Primary (literal, identifier, parenthesized expression)
 * 2. Member / Index / Call
 * 3. Unary
 * 4. Multiplicative
 * 5. Additive
 * 6. Relational
 * 7. Equality
 * 8. Logical AND
 * 9. Logical OR
 */

Expression ::= AssignmentExpression

AssignmentExpression ::= LogicalOrExpression
 | LeftHandSideExpression '=' AssignmentExpression

LogicalOrExpression ::= LogicalAndExpression ('||' LogicalAndExpression)*

LogicalAndExpression ::= EqualityExpression ('&&' EqualityExpression)*

EqualityExpression ::= RelationalExpression (('===' | '!==') RelationalExpression)?

RelationalExpression ::= AdditiveExpression (('<' | '<=' | '>' | '>=') AdditiveExpression)?

AdditiveExpression ::= MultiplicativeExpression (('+' | '-') MultiplicativeExpression)*

MultiplicativeExpression ::= UnaryExpression (('*' | '/') UnaryExpression)*

UnaryExpression ::= ('!' | '-') UnaryExpression
                  | AwaitExpression
                  | PostfixExpression

AwaitExpression ::= 'await' PostfixExpression

PostfixExpression ::= PrimaryExpression PostfixPart*

PostfixPart ::= '.' Identifier
 | '[' Expression ']'
 | Arguments

Arguments ::= '(' ArgumentList? ')'

ArgumentList ::= Expression (',' Expression)*

PrimaryExpression ::= NullLiteral
 | BooleanLiteral
 | NumberLiteral
  | StringLiteral
  | TemplateLiteral
  | Identifier
 | '(' Expression ')'
 | ArrayLiteral
 | ObjectLiteral
 | FunctionExpression
 | HasExpression

HasExpression ::= 'has' '(' PostfixExpression '.' Identifier ')'

ArrayLiteral ::= '[' ElementList? ']'

ElementList ::= Expression (',' Expression)* ','?

ObjectLiteral ::= '{' PropertyList? '}'

PropertyList ::= Property (',' Property)* ','?

Property ::= Identifier ':' Expression

FunctionExpression ::= 'async'? 'function' '(' ParameterList? ')' ':' Type Block

LeftHandSideExpression ::= Identifier
 | PostfixExpression
```

### Operator precedence table

| Precedence | Operator | Associativity |
|---|---|---|
| 1 | `.` `[]` `()` | left |
| 2 | `!` `-` (unary) | right |
| 3 | `*` `/` | left |
| 4 | `+` `-` (binary) | left |
| 5 | `<` `<=` `>` `>=` | none |
| 6 | `===` `!==` | none |
| 7 | `&&` | left |
| 8 | `\|\|` | left |

### Semicolon rules

Semicolons are required only where shown in the grammar.

### Statement/expression separation

- A module consists of zero or more `ImportDeclaration`s followed by zero or more `TopLevelDeclaration`s. `ImportDeclaration` is not allowed after any non-import top-level declaration.
- At module top level, only `ImportDeclaration`, `FunctionDeclaration`, `ClassDeclaration`, and `ExportDeclaration` are allowed. Everything else is a compile-time error.
- `Statement` remains the grammar for block/function bodies; imports, exports, and class declarations are not statements.
- `if`, `while`, `for` bodies must be blocks `{ ... }`. No statement-without-brace forms.
- `ExpressionStatement` is a bare expression (including function calls) followed by a required semicolon.
- `Block` is a statement-list and is not an expression. Blocks do not produce values.

Semantics:

- An `if` without `else` evaluates the condition; if true the branch executes.
- A `while` loop evaluates the condition before each iteration.
- `break` exits the nearest enclosing loop.
- `continue` proceeds to the next iteration of the nearest enclosing loop.
- `return` in function body immediately transfers control to the caller. `return` with no expression is valid only for `null` return type and returns language `null`.

### Assignment targets

Only these are valid LHS targets for `=`:

```ts
identifier // simple variable
a.b // member access
a[b] // index into array
```

No destructuring, no property shorthand mutation for class destructuring.

Assignment to member/index of an array and table respectively:

```ts
xs[0] = 5; // array element write — runtime element-type check
t.key = "value"; // table field write — no runtime type check
```

---

## Type system

### Type algebra (canonical forms)

```txt
Type :=
 null
 boolean
 number
  string
  table
  Error
  ClassName // nominal record type
 Array<Type> // surface: T[]
 Nullable<Type> // surface: T | null
 Function(sync, params, return)
 Function(async, params, return)
```

No other union types exist. `Nullable` is the sole union constructor.

### Type grammar (normative)

```antlr
Type ::= FunctionType | NullableType

NullableType ::= ArrayType ('|' 'null')?
 | 'null' '|' ArrayType

ArrayType ::= PrimaryType ('[' ']')*

PrimaryType ::= BuiltinType
 | Identifier
 | Identifier '.' Identifier
 | '(' Type ')'

BuiltinType ::= 'null' | 'boolean' | 'number' | 'string' | 'table' | 'Error'

FunctionType ::= 'async'? '(' FunctionTypeParams? ')' '=>' Type

FunctionTypeParams ::= FunctionTypeParam (',' FunctionTypeParam)*

FunctionTypeParam ::= Identifier ':' Type
```

Constraints:

- `T | null` and `null | T` canonicalize to `Nullable<T>`.
- General unions such as `A | B` are invalid.
- `null | null`, `(T | null) | null`, and `T | null | null` are invalid.
- Identifier in a type context must not be a keyword.
- `Error` is a builtin nominal class type. User code must not declare a class named `Error`.

### Surface syntax

```ts
null
boolean
number
string
table
Error

T[] // array of T
T[][] // array of array of T

T | null // nullable wrapper; T must not be null or Nullable<U>
null | T // equivalent

(p1: T1, ..., pN: TN) => R
async (p1: T1, ..., pN: TN) => R

ClassName // nominal record
```

### Nullable semantics

`Nullable<T>` is a distinct type constructor with restricted assignment.

Assignment rules:

```txt
T -> T OK
null -> null OK
T -> T | null OK (wrapping)
null -> T | null OK
T | null -> T error (must narrow)
T | null -> T | null OK (same T)
T | null -> U | null error (different T)
```

Supports basic null flow narrowing:

```ts
let n: number | null = 1;

if (n !== null) {
 let i: number = n; // OK: narrowed to number in this block
}

if (n === null) {
 // n is null here
} else {
 let i: number = n; // OK: narrowed to number in else block
}
```

Narrowing supports only locals and parameters compared directly with `null` using `!==` or `===`, not property paths:

```ts
class Profile { nick: string | null; }
let p: Profile = { nick: "Ada" };
if (p.nick !== null) {
 let nick: string = p.nick; // error: properties are not narrowed
}
```

Outside a narrowing block:

```ts
let i: number = n; // compile-time Error
```

Narrowing invalidation:

- Direct assignment invalidates the binding's refinement.
- Calls do not invalidate refinements. Indirect captured-binding mutation can therefore cause a later runtime type check to fail.
- Narrowing does not cross function boundaries or loop back-edges.
- `await` invalidates all enclosing-function refinements.

```ts
let x: string | null = "a";
if (x !== null) {
 let a: string = x; // OK
 x = null;
 let b: string = x; // Error
}
```

```ts
let x: number | null = 1;
let clear: () => null = function(): null {
 x = null;
 return null;
};
if (x !== null) {
 clear();
 let y: number = x; // compiles; raises a runtime type error
}
```

```ts
async function tick(): null { return null; }
async function use(x: string | null): null {
 if (x !== null) {
  let before: string = x; // OK
  await tick();
  let after: string = x; // error: narrowing was invalidated
 }
 return null;
}
```

### Type compatibility

Types must match exactly unless an explicit compatibility rule permits otherwise.

### Equality and comparison operand rules

Type equality:

Two types are equal iff their canonical forms are identical. Class types are equal by declared class symbol only. Function types are equal by exact async marker, parameter list, and return type.

Value equality: `===` and `!==` are valid only when operand types are equal. Exception: `T | null` may be compared with `null`.

`==` and `!=` are not valid operators.

```ts
let a: number = 1;
let b: number = 2;
let ok: boolean = a === b;

let n: number | null = null;
let isNull: boolean = n === null;

let same: boolean = 1 === 1.0; // true: both are number
```

Relational operators `< <= > >=` are valid only for:

```txt
number vs number
string vs string
```

Strings compare lexicographically by Unicode scalar value; a proper prefix sorts first. No normalization or locale rules apply.

### Type-of-expression rules

String concatenation uses `+`. Both operands must be `string`. No implicit string conversions exist.

| Expression | Type |
|---|---|
| `null` | `null` |
| `true`, `false` | `boolean` |
| `123`, `1.5`, `1e3` | `number` |
| `"abc"` | `string` |
| `` `a${x}b` `` | `string` |
| `(e)` | same type as `e` |
| variable declared `let x: T` | `T` |
| `a + b` where both `number` | `number` |
| `a + b` where both `string` | `string` (concatenation; no implicit conversions) |
| any other `+` | error |
| `a - b`, `a * b` | both operands must be `number`; result is `number` |
| `a / b` where both `number` | `number`, IEEE division |
| `=== !==` | result is `boolean` |
| `< <= > >=` | result is `boolean` |
| `&&` | both must be `boolean`, result `boolean` |
| `\|\|` | both must be `boolean`, result `boolean` |
| `!a` | `a` must be `boolean`, result `boolean` |
| `-a` where `a: number` | `number` |
| any other unary `-` | error |
| array literal `[e1, ..., eN]` | `T[]` where all `e_i` have the same `T` |
| table literal `{ p: e, ... }` | `table` |
| class body `{ ... }` with target `ClassName` | `ClassName` (contextual) |
| index `a[b]` where `a: T[]`, `b: number` | `T` |
| member `a.p` where `a: table` | **error** unless the surrounding context provides a target type |
| member `a.p` where `a: ClassName` | required field: declared field type; optional field `p?: T`: `T \| null`; optional nullable field `p?: T \| null`: `T \| null` |
| call `f(e1, ..., eN)` where `f: (p1: T1, ..., pN: TN) => R` | `R` |
| `await f(e1, ..., eN)` where `f: async (p1: T1, ..., pN: TN) => R` | `R` |
| `e.code` where `e: Error` | `string` |
| `e.message` where `e: Error` | `string` |

### Assignment type checking

```txt
let x: T_target = expr;
```

1. Infer type of `expr` → `T_expr`
2. If `T_expr` equals `T_target` exactly → OK
3. If `T_target` is `Nullable<T>` and `T_expr` equals `T` → OK
4. If `T_target` is `Nullable<T>` and `T_expr` equals `null` → OK
5. If both are function types satisfying [Function type compatibility](#function-type-compatibility) → OK
6. Otherwise → compile-time Error

---

## Classes

### Class declaration

`class` declares a module-level **nominal record type**: a sealed shape without inheritance, methods or constructors. Local class declarations are compile-time errors.

```ts
class User {
 id: number;
 name: string;
 nick?: string;
 bio: string | null;
 active: boolean;
}
```

Field forms:

```txt
f: T; required-present
f?: T; optional
f?: T | null; optional, nullable
f: T | null; required-present, nullable
```

Field initializers are compile-time errors.

### JSON serialization (`@jsonable`)

The `@jsonable` directive generates serialization functions for an `export class`:

```ts
// @jsonable
export class User {
  name: string;
  nick?: string;
  bio: string | null;
}
```

**Jsonable field types.** A `// @jsonable` class may contain fields of:

- `null`
- `boolean`
- `number`
- `string`
- `table`
- `T[]` where `T` is jsonable
- `T | null` where `T` is jsonable
- class type `C` with visible generated or declared `C$fromJson` and `C$toJson` of the signatures below

Non-jsonable fields produce compile-time errors at their declarations.

Circular `@jsonable` dependencies, including through arrays and nullables, are compile-time errors.

In declaration files, `// @jsonable` has no effect; compatible `C$fromJson` and `C$toJson` functions may be declared explicitly.

**Generated functions.** For a `@jsonable` class `C`, the compiler emits two module-level export functions:

```ts
C$fromJson(s: string): C | null
C$toJson(v: C): string
```

**fromJson.** Parses an object; rejects extra keys, missing required-present keys, and duplicate decoded property names at every object nesting level. Decodes fields recursively in declaration order; missing optionals stay absent. Failure returns `null`, never throws or publishes partial instances.

```ts
let invalid: User | null = User$fromJson("{\"name\":\"Ada\",\"naem\":\"Bob\"}"); // null: extra key
```

**toJson.** Serializes declared fields, omitting absent optionals and preserving nullable `null`. Raises `Error` (`E8001`) if any serialized number is non-finite.

For a `table` field, `fromJson` accepts only a JSON object and maps it to a DEAL table as untyped JSON-shaped data. `toJson` accepts only acyclic JSON-shaped table values: string-keyed objects, arrays, and `null`/`boolean`/`number`/`string` leaves. Otherwise, it raises `Error`.

**Optional-nullable roundtrip.** The three states of `field?: T | null` are preserved exactly:

| State | fromJson input | toJson output |
|---|---|---|
| missing | key absent | key omitted |
| present, null | `"field": null` | `"field": null` |
| present, value | `"field": value` | `"field": value` |

**Usage:**

```ts
import * as models from "./models/user";

let u: models.User | null = models.User$fromJson("{\"name\":\"Ada\",\"bio\":null}");

if (u !== null) {
  let s: string = models.User$toJson(u);
}
```

### Nominal typing

Classes are nominal. Two identically-shaped classes are distinct types:

```ts
class A { x: number; }
class B { x: number; }

let a: A = { x: 1 }; // OK
let b: B = a; // Error
let c: B = { x: 1 }; // OK
```

### Construction

Classes are initialized with **object literals** in a typed context. There is no `new` operator.

```ts
let u: User = { id: 1, name: "Ada", bio: null, active: true };
let v: User = { id: 2, name: "Bob", nick: "b", bio: null, active: false };
let missing: User = { name: "Ada" }; // compile-time error: required fields omitted
```

- Evaluate supplied field expressions exactly once, left-to-right in literal order.
- Every required-present field must occur exactly once. Optional fields may be omitted. Extra fields are rejected.
- Validate the supplied field values; tag and publish the instance only after successful validation. Failure retains earlier expression effects but publishes no partial instance.

Missing required fields in literals are compile-time errors. At untyped boundaries, invalid class shapes or field values raise the applicable boundary error.

### Field access

Reading a required-present field yields the field type:

```ts
let n: string = u.name; // OK
```

Reading an optional field yields `T | null`. If the field is missing, the direct read produces language `null`. This keeps optional-field use null-safe while still allowing presence checks when the caller must distinguish missing from explicit JSON null.

```ts
let nick: string | null = u.nick; // OK
let nick2: string = u.nick; // error: string | null is not string
```

Optional nullable corner case:

```ts
class Profile {
 nick?: string | null;
}
```

Semantics for `nick?: string | null`:

```txt
missing -> direct read returns null, has(obj.nick) is false
present null -> direct read returns null, has(obj.nick) is true
present str -> direct read returns string, has(obj.nick) is true
```

The language provides a compiler intrinsic:

```ts
has(obj.field): boolean
```

The argument must syntactically be a class field access. The field must be a declared optional field. This avoids source-level literal-string types while keeping the field name compile-time checked.

`has(obj.field)` tests field presence only. It does not narrow the static type of `obj.field`.

```ts
class Profile {
 nick?: string | null;
}

let p: Profile = { nick: null };

if (has(p.nick)) {
 let a: string | null = p.nick; // OK
 let b: string = p.nick; // error: present field may still be null
}
```

### Class-valued fields and `class[]`

```ts
class Address {
 city: string;
 zip: number;
}
class User {
 name: string;
 address: Address;
 tags: string[];
 friends: User[];
}
let u: User = {
 name: "Ada",
 address: { city: "London", zip: 12345 },
 tags: ["admin", "beta"],
 friends: [],
};
let users: User[] = [
 { name: "Ada", address: { city: "A", zip: 1 }, tags: [], friends: [] },
 { name: "Bob", address: { city: "B", zip: 2 }, tags: [], friends: [] },
];
let incomplete: User[] = [{ name: "Ada" }]; // error: missing required fields
let untyped = [{ name: "Ada" }]; // error: no class target type
let empty: User[] = [];
```

Nested object literals receive their field's class target type; elements of a contextually typed `C[]` receive class type `C`.

### Class vs table

`class` and `table` are **invariant and distinct**. No implicit conversion.

```ts
let u: User = { name: "Ada", address: { city: "A", zip: 1 }, tags: [], friends: [] };
let t: table = {}; 
t = u; // Error
u = t; // Error
```

### Class assignment semantics

Assignment to a class field is allowed only for declared fields:

```ts
u.name = "Bob"; // OK
u.unknown = 1; // Error
```

Required-present fields cannot be deleted:

```ts
delete u.name; // Error
```

Optional fields can be deleted, which makes them missing:

```ts
delete u.nick; // OK if nick is optional
```

Assignment to optional fields:

```ts
u.nick = "Ada"; // OK for nick?: string
u.nick = null; // error for nick?: string
```

Assignment to optional nullable fields:

```ts
u.nick = "Ada"; // OK for nick?: string | null
u.nick = null; // OK for nick?: string | null
```

After deleting an optional field, a subsequent assignment makes it present again:

```ts
class User {
 nick?: string;
}

let u: User = {};
let a: boolean = has(u.nick); // false

u.nick = "Ada";
let b: boolean = has(u.nick); // true

delete u.nick;
let c: boolean = has(u.nick); // false

u.nick = "Bob";
let d: boolean = has(u.nick); // true
```

Extra fields are rejected in object literals, nested object literals, arrays of class literals, and runtime typed boundaries.

---

## Functions

### Function declaration

```ts
function name(p1: T1, ..., pN: TN): R {
 body
 return expr;
}
```

- Parameter and return types are required.
- Return type may be `T`, `T | null`, or `null`.
- Parameters cannot be optional; nullable parameters use explicit `T | null`.

### Return type requirements

- Every `return` expression must have type `R`.
- The function body must not “fall off” without a return.

```ts
function f(): null { // ok
 return null;
}

function findName(): string | null { // ok: returns null or string
 if (true) {
 return "Ada";
 }
 return null;
}
```

### Function type compatibility

An `M`-parameter function is assignable to an `N`-parameter function target **iff**:

- Async markers match: both absent or both `async`.
- Return types are identical, including nullable wrappers.
- `M <= N`.
- Corresponding first `M` parameter types are identical.

Extra target arguments are silently ignored. More actual parameters than target parameters are always rejected.

### Nullable return types

```ts
function find(id: number): User | null {
 if (id === 0) {
 return null;
 }
 return { name: "Ada", address: { city: "X", zip: 0 }, tags: [], friends: [] };
}
```

### Function values, calls, and wrappers

Declarations produce first-class typed function values; calls use TypeScript syntax:

```ts
let r: number = add(1, 2);
```

Function runtime types include the async marker, observable through checking and host interop, not source-level inspection.

Compile-time call checking:

- Number of arguments must match the function type, except when calling a target produced by arity extension.
- Argument expression types must equal parameter types exactly, except assignment into `T | null` parameters accepts `T` or `null`.
- Return value has the declared return type.
- Direct calls with wrong argument types are compile-time errors.
- A direct call to an async function without `await` is a compile-time error.
- `await` may be applied only to a direct async function call.

Runtime call checking:

- Function wrappers check parameter and return values.
- Calls through imported host values are wrapped before being used as typed functions.

### Function expressions

A function expression has the same type rules as a function declaration:

```ts
let f: (x: number) => number = function(x: number): number {
 return x;
};
```

Rules:

- Parameter types are required.
- Return type annotation is required.
- Return analysis is the same as for function declarations.
- The expression produces a typed function value with the declared signature.
- Function expressions may appear wherever expressions are allowed.

### Closures and captured variables

Function expressions and nested function declarations may reference variables from enclosing scopes.

Captured variables are captured by binding, not by value. If the captured variable is assigned later, reads through the closure observe the current value of that binding.

```ts
let x: number = 1;

let f: () => number = function(): number {
 return x;
};

x = 2;
let y: number = f(); // 2
```

Captured variables keep their storage alive as long as any closure that captures them may be called.

For a `for-of` loop, each iteration creates a fresh iteration binding for variables captured by closures.

```ts
let fs: (() => number)[] = [];

for (let i: number of [0, 1, 2]) {
 fs[fs.length] = function(): number {
 return i;
 };
}

let a: number = fs[0](); // 0
let b: number = fs[1](); // 1
let c: number = fs[2](); // 2
```

Narrowed flow types are not captured. A closure sees the declared type of captured variables, not a temporary narrowed type.

```ts
let n: number | null = 1;

if (n !== null) {
 let f: () => number = function(): number {
 return n; // error: captured type is number | null
 };
}
```

---

## Variables

```ts
let x: number = 1;
let y = true; // boolean inferred
let z = 1.0; // number inferred
```

### Inference rules

Inference is allowed when the initializer has a complete static type without dynamic table lookup or unknown external boundary:

```ts
let a = null; // null
let b = true; // boolean
let c = 1; // number
let d = 2.0; // number
let e = "hi"; // string
let f = [1, 2, 3]; // number[]
let g = [1.0, 2.0]; // number[]
let h = add(1, 2); // number, if add has a statically known function type
```

Object literals and empty arrays require context.

Disallowed:

```ts
let h = t.someField; // error — dynamic table field must annotate
let i = unknown.f(1, 2); // error — dynamic table calls are forbidden
let j = []; // error — no element type
let l = [1, "1"]; // error — mixed types
let m = { }; // error — must annotate
```

### Redeclaration

Not allowed within the same block scope.

### Scope

- Module-level: visible from declaration to end of module.
- Block `{}`: scoped to block.

### Name resolution

Resolution order for an identifier expression:

1. Local variables and parameters in the innermost block scope.
2. Enclosing block scopes.
3. Module-level declarations.
4. Imported module bindings.
5. Compiler intrinsic `has` and prelude symbols.

Shadowing:

- Inner block variables may shadow outer block variables.
- Parameters may not shadow other parameters.
- Module-level declarations may not shadow imports.
- Imports may not shadow module-level declarations.
- Redeclaration in the same scope is an error.

Declaration order:

- `class` and `function` declarations are hoisted within the module for name resolution.
- `let` declarations are not hoisted and are visible only after their declaration.
- Function bodies may reference functions/classes declared later in the same module.
- Class fields may reference classes declared later in the same module.

### Definite return analysis

A function with return type `R` must return on all reachable paths.

Definitely-returning statements:

- `ReturnStatement`
- `if (cond) { A } else { B }` where both `A` and `B` definitely return
- block whose final reachable statement definitely returns

Loops are not assumed to return unless they contain an unconditional `return` before any possible `break`. 

---

## Tables

### Table type and semantics

`table` is a dynamic key-value container supporting `string` keys. It is distinct from `class` types.

The value `null` is distinct from field absence. Storing `null` in a table field makes the field present. Deleting a field makes it absent. The two are distinguishable.

```ts
t.p = null; // field exists, value is null
delete t.p; // field is removed entirely
```

Tables are data containers. Calling through a table is not allowed.

```ts
let r: number = t.f(1, 2); // compile-time Error
```

### Reading table fields

```ts
let t: table = { x: 1 };

let y = t.x; // compile-time Error
let y: number = t.x; // ok — runtime check inserted
```

Contextual type checking algorithm for table reads:

Given expression `E = t.key` or `E = t[idx]` where `t: table`:

Allowed contexts:

1. `let x: T = E`
 - runtime-check `E` against `T`

2. `x = E`, where `x: T`
 - runtime-check `E` against `T`

3. `return E` inside function returning `R`
 - runtime-check `E` against `R`

4. `f(E)`, where corresponding parameter type is `T`
 - runtime-check `E` against `T`

5. `if (E)`
 - runtime-check `E` against `boolean`

Otherwise:
- compile-time error `E3003`

### Writing table fields

For `t.key = value` or `t[idx] = value` where `t: table`:

- `key` must be an identifier; `idx` must have static type `string`.
- `value` may be any type; no field-level schema is enforced at compile time or runtime.
- The stored value is written directly to the underlying container; no runtime type check is performed.

```ts
t.f1 = 1;
t.f2 = 1.0;
t.f3 = "abc";
t.f4 = null;
t.f5 = [1, 2, 3];
t.f6 = t;
```

```ts
t.key = null; // field exists, value is null
delete t.key; // field is removed
```

Index keys must be `string`.

### Table literals and key syntax

```ts
let t: table = { a: 1, b: "x" };
```

Table/object literal property names are restricted to identifiers:

```ts
{ name: "Ada", age: 36 } // OK
{ "name": "Ada" } // Error
{ [k]: 1 } // Error
```

Dynamic keys use assignment after construction:

```ts
let t: table = {};
t[key] = 1;
```

### Deletion

```ts
delete t.key;
```

Backend: removes the key from the container

## Arrays

### Array type and semantics

`T[]` is a 0-based array.

Supported element types: all types, including `class` types, nullable types, arrays, functions.

### Indexing

Arrays are 0-based.

```ts
let xs: number[] = [10, 20, 30];
let x: number = xs[0]; // ok
```

### Bounds behavior

Array indexes must have type `number`. Reads require an integral number in `0..length-1`; otherwise raise `E8002`. Negative zero indexes zero.

Optional nullable arrays follow target typing:

```ts
let xs: (number | null)[] = [1, null, 3];
let x: number | null = xs[1];
```

### Array writes

For `xs[i] = value` where `xs: T[]`:

1. `i` must have type `number`.
2. `value` must be assignable to `T` by normal assignment rules.
3. After evaluating receiver, index and RHS once in order, require an integral index in `0..length`; otherwise raise `E8002` without writing. Earlier effects remain. Index `length` appends.
4. The stored value is runtime-checked against `T`.

```ts
let xs: number[] = [];
xs[0] = 1; // OK
xs[1] = "x"; // compile-time Error
xs[2] = 2; // runtime error if length is 1
```

### Length and iteration

Array `length` is a read-only intrinsic of type `number`: the exact element count. Assignment to `.length` is a compile-time error.

```ts
for (let value: number of xs) {
 // consume each value
}
let i: number = 0;
while (i < xs.length) {
 // use xs[i]
 i = i + 1;
}
```

## Control flow

### Truthiness

DEAL has no implicit truthiness. Conditions must be statically `boolean`.

```ts
if (x) { } // OK only if x: boolean
if (t.value) { } // error unless t.value is contextually checked as boolean
```

Dynamic table values require explicit boolean target/context:

```ts
let b: boolean = t.flag;
if (b) { }
```

### Conditional

```ts
if (boolean_expr) {
 ...
} else {
 ...
}
```

### While

```ts
while (boolean_expr) {
 ...
}
```


### For-of

```ts
let xs: number[] = [1, 2, 3];
for (let x: number of xs) {
  // x: number, each element in order
}

let s: string = "abc";
for (let c: string of s) {
  // c: string, one Unicode scalar value
}
```

Evaluate the `T[]` or `string` iterable once. Arrays visit successive indexes from zero using live length/contents: appends and unvisited replacements are observed; variable reassignment cannot redirect traversal. Strings yield successive scalars. Loop-variable types are `T` or `string`; see [Closures and captured variables](#closures-and-captured-variables) for binding freshness. Supports `break` and `continue`.

### Break / Continue

Supported inside loops.

---

## Error handling

### Error type

`Error` is a builtin class. Its shape is equivalent to this descriptive pseudo-source; user code must not declare it:

```ts
class Error {
  code: string;
  message: string;
}
```

`Error` values are produced by runtime exceptions or by class literal construction.

### throw

```ts
throw { code: "", message: "capacity exceeded" };
throw { code: "E_LIMIT", message: "index out of range" };
```

`throw` is a statement. The expression must have type `Error`. Execution transfers to the nearest enclosing `catch` block. If no `catch` encloses the `throw`, the error escapes to the host as a `DEALRuntimeError`.

### try / catch

```ts
try {
  let x: number = xs[99];
} catch (e) {
  // e: Error
}
```

`try`/`catch` is a statement, not an expression. The bound variable `e` has type `Error`. If no error occurs, the `catch` block is skipped. If an error occurs in the `try` block (via `throw` or a runtime error), execution jumps to `catch` and continues after the `catch` block.

If the `try` block has a definite `return`, the `catch` block must also have a definite `return` or `throw`. Both return expressions must satisfy the enclosing function's return type.

### Narrowing across try/catch

After a `try`/`catch`, all variables assigned inside the `try` block revert to their declared type.

```ts
let x: number | null = 1;

try {
  x = 2;
} catch (e) {
  // x: number | null
}

// x: number | null
```

## Async/Await

### async function

An `async` function returns its declared type directly. The `async` marker is part of the function type. Callers use `await` to suspend until the function completes.

```ts
import * as http from "host/http";

async function fetchUser(path: string): User | null {
  let json: string = await http.get(path);
  return User$fromJson(json);
}
```

`async` function expressions are supported:

```ts
let f: async (path: string) => User | null = async function(path: string): User | null {
  return await fetchUser(path);
};
```

### await

`await` suspends execution of the enclosing `async` function until the awaited `async` call completes, then evaluates to the call's return value. `await` is only valid inside an `async` function body.

```ts
let u: User | null = await fetchUser("/user/42");
```

Compile-time rules:
- `await E` is valid only inside an `async` function body.
- `E` must be a direct call expression.
- The callee of `E` must have async function type `async (...) => R`.
- The type of `await E` is `R`.
- `await` applied to a non-`async` call is a compile-time error.
- An async function call without `await` is a compile-time error.

### Async operation semantics

An async function call creates an internal async operation. The operation is not a source-language value and cannot be stored, passed, returned, compared, or inspected.

Each async operation completes exactly once, either with a value of the function's declared return type or by raising `Error`.

`await` waits for that operation to complete. If it completes with a value, `await` evaluates to that value. If it raises `Error`, `await` raises that `Error`.

An `Error` raised by an awaited operation is caught by the nearest enclosing `catch` around the `await`.

Within one async function invocation, evaluation before an `await` happens before evaluation resumed after that `await`.

DEAL defines no source-level concurrent start, scheduling, cancellation, polling, task, future, promise, or shared-memory concurrency primitive.

Mutable DEAL values are not concurrently shared by source-language semantics. Host values crossing untyped boundaries are validated as DEAL values or remain backend-defined opaque host values.

### Backend lowering

Async/await is lowered backend-specifically.

A backend must preserve these source-level rules:

- async functions have async function type `async (...) => R`
- async calls must appear as the operand of `await`
- `await` may appear only inside an async function body
- `await` evaluates to the declared return type `R`
- no source-level coroutine, promise, future, or task type exists

Backend strategies are not source-language observable.

All async function completion values cross the typed boundary defined in the backend conformance contract.

---

## Modules, declarations, standard library, and host ABI

### No user-defined globals

Each file compiles to an export-table module. Top-level declarations are local; user-defined globals are forbidden.

When a compiler invocation selects an entry module, that module must export `main` with non-async signature `(): null`; the backend invokes `main()` from that module.

```ts
export function main(): null {
 return null;
}

export function add(a: number, b: number): number {
 return a + b;
}

export class Point {
 x: number;
 y: number;
}
```

### Export forms

```ts
export function name(p1: T1, p2: T2): R { ... }
export class Name { ... }
```

Declaration files additionally support external function declarations:

```ts
export function name(p1: T1, p2: T2): R;
```

Declaration files may export only functions and classes. `export let` is forbidden.

No named export lists, no default export, no re-export, no namespace export.

Exporting a class exports class metadata, not a constructor or callable value. The metadata enables import-side typing and runtime class checks.

### Import forms and import typing

```ts
import * as mod from "./mod";
```

Every `import` binding has a statically known module type.

A module type is a mapping from exported names to their declared types. Accessing an export not present in the module type is a compile-time error. If the compiler cannot assign a module type to an import, compilation fails.

Example:

```ts
// mathlib.deal
export function add(a: number, b: number): number {
 return a + b;
}

// main.deal
import * as mathlib from "./mathlib";

export function main(): null {
 let x: number = mathlib.add(1, 2); // OK, add: (number, number) => number
 let y = mathlib.add(1, 2); // OK, inferred number
 let z: number = mathlib.missing(1); // error: export 'missing' is not declared
 return null;
}
```

Imported module members are not dynamic table reads. They are statically typed member accesses over the module type.

### Module resolution

For import specifier `S` from source file `A`:

1. If `S` starts with `./` or `../`, resolve relative to directory of `A`.
2. Otherwise resolve as package module using compiler search path.
3. Candidate source files, in order:
 - `S.deal`
 - `S/index.deal`
 - `S.d.deal`
 - `S/index.d.deal`
4. Resolution failure is compile-time error unless the module is explicitly marked external.

Loading and linking are backend-defined and not source-visible; use `import`.

### Module initialization and cycles

- Modules initialize at most once per runtime instance.
- Module initialization is depth-first in import order.
- A module export table becomes visible only after successful initialization.
- Import cycles are compile-time errors.
- If module initialization raises an error, the module is marked failed; later imports re-raise the same error.

### Declaration-file format

Declaration files use `.d.deal`. They contain only leading imports, exports, and class declarations. No executable statements.

```ts
export function print(x: string): null;
export class Point {
 x: number;
 y: number;
}
```

Rules:

- Function declarations end with `;` and have no body.
- `export class` describes the runtime class shape.
- `export let` is forbidden.
- No top-level ambient declarations in implementation `.deal` files.
- Declaration files may describe host modules.

Host ABI:

- Host modules are loaded only if listed in `deal.json.externals`.
- The declaration path is resolved relative to the manifest directory unless absolute paths are permitted by host policy.
- Host module runtime object must expose exported names from its `.d.deal` declaration.
- Extra host exports are ignored; missing declared exports are load-time errors.
- Every call/value crossing the host boundary is runtime-checked.
- External declaration files may declare async functions. A host function declared as `async (...) => R` must return a backend async operation accepted by the backend's `await` lowering. Source code cannot observe that operation except through `await`.

### Package manifest

A project may define `deal.json` at its root:

```json
{
 "languageVersion": "1.4",
 "moduleRoots": ["src"],
 "output": "build/lua",
 "backend": "luajit",
 "stdlib": "1.4",
 "dependencies": {},
 "externals": {
  "host/log": {
   "declaration": "bindings/host-log.d.deal"
  }
 }
}
```

Rules:

- `languageVersion` must be `"1.4"` and governs all source and declaration files in the project.
- `moduleRoots` participate in import resolution.
- DEAL has no package registry. `dependencies` is reserved for local/package-manager integration later.
- Standard library modules may be described by bundled `.d.deal` files, but are resolved as part of the language distribution rather than as project external host modules.
- `externals` declares host modules available to the compiler; importing an unlisted external is a compile-time error. External entries describe host declarations only; `nativeLibrary` is not a valid manifest option.

### Standard library declarations

The compiler ships practical minimal standard modules. All are imported explicitly.

#### `std/console.d.deal`

```ts
export function log(x: string): null;
export function error(x: string): null;
```

#### `std/string.d.deal`

```ts
export function length(s: string): number;
export function substring(s: string, start: number, end: number): string;
export function contains(s: string, part: string): boolean;
export function startsWith(s: string, part: string): boolean;
export function endsWith(s: string, part: string): boolean;
export function replace(s: string, search: string, replacement: string): string;
export function split(s: string, sep: string): string[];
export function trim(s: string): string;
```

Lengths and positions count Unicode scalars; lengths are exact. Searches are literal, not patterns.

- `substring`: `start` and `end` require integral numbers; otherwise raise `E8001` (`substring bounds must be finite integers`). Clamp bounds to `0..length`; start is inclusive, end exclusive; reversed ranges yield `""`.
- `replace`: all non-overlapping matches, left-to-right; empty search returns input; literal replacement.
- `split`: empty input yields `[]`; empty separator splits scalars; otherwise preserves empty parts.
- `trim`: removes only U+0009–U+000D and U+0020 from both ends.

#### `std/table.d.deal`

```ts
export function keys(t: table): string[];
```

Returns each present string key once, including null-valued keys; order is unspecified.

#### `std/json.d.deal`

```ts
export function parse(s: string): table;
export function stringify(t: table): string;
```

`parse` requires an object root; malformed JSON, duplicate keys, and other roots raise `Error`. JSON numeric tokens follow [Numbers](#numbers) in both `parse` and generated class decoding. Finite numbers stringify as valid JSON numbers that roundtrip to the same binary64 value, except negative zero serializes as `0`.

`stringify` raises `Error` for non-JSON values, non-finite numbers, or cycles.

#### `std/math.d.deal`

```ts
export function floor(x: number): number;
export function ceil(x: number): number;
export function sqrt(x: number): number;
export function absNumber(x: number): number;
```

#### `std/time.d.deal`

```ts
export function nowMillis(): number;
```

Returns Unix-epoch wall-clock milliseconds with backend-dependent resolution; readings need not increase.

Usage:

```ts
import * as strings from "std/string";
import * as json from "std/json";

let parts: string[] = strings.split("a,b", ",");
let t: table = json.parse("{\"name\":\"Ada\"}");
```

### Host APIs

Backend globals and APIs are unavailable to source code unless exposed through an imported declaration module.

### Host ABI and interoperability

#### Value mapping

See [Backend contracts](#backend-contracts) for backend-specific representations.

#### Boundary checks

Any value crossing from an untyped host context into DEAL is checked:

```ts
let f: (x: number) => number = lib.f;
```

Target-typed table member reads and declared function-signature checks are supported. Dynamic table calls are forbidden. Explicit class/table conversion syntax is deferred.


#### Function ABI decision

Function representation and invocation are backend-defined, preserving signature checks and identity.

### Host error and debugging

Errors propagate through wrappers, modules, and calls. `catch` reifies them as `Error`; uncaught errors escape as `DEALRuntimeError`:

```txt
code
message
source file
line
column
expected type (if applicable)
actual runtime kind (if applicable)
stack frames
cause (if applicable)
```

### Source maps and debugging

- Source-emitting backends provide statement-group source comments and source-map sidecars; native backends provide debug metadata. Mappings preserve original files, lines and columns across modules.
- Runtime errors report original `.deal` locations: one-based lines and Unicode-scalar columns; tabs count one column; CRLF counts one line break.
- Stack traces include generated and original function names.

---

## Runtime execution model

### Runtime sentinels

| Semantic value | Runtime role |
|---|---|
| `null` | JSON null / language null |
| missing optional field | field absence state |

### Backend conformance contract

A conforming backend preserves the observable behavior defined by this specification.

Observable behavior includes:

- compile-time diagnostics
- successful runtime values
- runtime error codes and source locations
- module import/export behavior
- host boundary behavior
- `null` and missing-field semantics
- class nominal identity
- function type identity, including the async marker
- evaluation order
- binary64 arithmetic, signed-zero behavior, and discrete-position validation
- `@jsonable` serialization behavior

A backend may choose any internal representation, including erased, partially erased, statically typed, boxed, unboxed, interpreted, source-generated, or bytecode-generated representation, if observable behavior is unchanged.

A backend may omit a runtime check when static backend representation proves that the value already satisfies the DEAL type and no untyped boundary can have produced it.

A backend is non-conforming if it erases DEAL types in a way that allows a runtime value to cross a typed boundary without equivalent validation.

#### Typed boundaries

A typed boundary is a point where a runtime value is accepted as, stored as, returned as, exported as, or reconstructed as a declared DEAL type.

Backends must validate values at every typed boundary unless the validation is statically proven redundant.

Typed boundaries are exactly:

- variable declaration with annotation: `let x: T = e`
- assignment to a variable with declared or inferred type `T`
- assignment to a declared class field of type `T`
- assignment to an array element of `T[]`
- function parameter entry
- function return
- async function completion value
- class object literal construction
- class value received from an untyped boundary
- optional field read that converts missing to `null`
- table read in contextual target type `T`
- value read from imported module member
- value exposed through module export table
- value crossing from host into DEAL through `.d.deal`
- value crossing from DEAL into host through `.d.deal`
- standard-library function parameter and return
- external declaration function parameter and return
- `@jsonable` `fromJson` field validation
- `@jsonable` `toJson` field serialization input validation

### Runtime type descriptor format

```txt
RuntimeTypeDescriptor :=
 PrimitiveDescriptor
 | ClassDescriptor
 | ArrayDescriptor
 | NullableDescriptor
 | FunctionDescriptor

PrimitiveDescriptor :=
 "null" | "boolean" | "number" | "string" | "table" | "Error"

ClassDescriptor :=
  "@" ModuleRoot "/" ModuleRelativePath "/" ClassName

ModuleRoot := /* the configured module root from deal.json, e.g. "src", "lib/utils" */
ModuleRelativePath := /* path components from module root to defining file, e.g. "models" */

ArrayDescriptor :=
 "[" RuntimeTypeDescriptor "]"

NullableDescriptor :=
 "?" RuntimeTypeDescriptor

FunctionDescriptor :=
 AsyncMarker? "(" ParamDescriptorList? ")" "->" RuntimeTypeDescriptor

AsyncMarker :=
 "async"

ParamDescriptorList :=
 ParamDescriptor ("," ParamDescriptor)*

ParamDescriptor :=
 RuntimeTypeDescriptor
```

Examples:

```txt
number // number
?string // string | null
[number] // number[]
[[number]] // number[][]
?[@src/app/User] // User[] | null
[?@src/app/User] // (User | null)[]
(number,string)->boolean // (x:number,y:string)=>boolean
()->null // () => null
async(number)->string // async (x:number)=>string
```

Constraint: `NullableDescriptor` inner type must not be `null` and must not be another `NullableDescriptor`.

Equality semantics for `===`:

- `null === null` is true by sentinel identity.
- Booleans compare by value.
- `number` compares by IEEE 754 equality: `NaN === NaN` is `false`; `NaN !== NaN` is `true`.
- Strings compare by Unicode scalar-value sequence.
- Arrays, tables, classes, and functions compare by reference identity.
- Values of different static types cannot be compared, except `T | null` vs `null`.

### Class runtime

See [Construction](#construction) and [Field access](#field-access).

## Backend contracts

The following sections define required value mappings for supported backends. These mappings are representation contracts, not additional source-language semantics.

When a backend mapping conflicts with the source-language semantics or the backend conformance contract, the source-language semantics and backend conformance contract are authoritative.

### LuaJIT backend contract

#### LuaJIT value mapping

| Type | LuaJIT representation |
|---|---|
| `null` | unique sentinel `__NULL` |
| missing optional field | absent key or `__MISSING` internally |
| `boolean` | Lua boolean |
| `number` | Lua number |
| `string` | Lua string containing valid UTF-8 |
| `table` | Lua table |
| `ClassName` | Lua table tagged with class symbol |
| `T[]` | Lua table with 1-based contiguous storage |
| `T | null` | either representation of `T` or `__NULL` |
| `(params) => R` | wrapper table `{ __kind = "function", sig, f }` |
| module type | Lua export table |

#### Module loading

Runtime candidates: emitted path for `S.deal`, then host `require(S)`. Default conversion under the configured module root replaces separators with dots and strips `.deal`: `./math/vector.deal` becomes `require("math.vector")`.

#### Function ABI

Function values are wrappers with explicit `.f(...)` calls, never metatable `__call`. Runtime adapters `as_lua_function` and `from_lua_function` expose DEAL functions to hosts and wrap incoming host functions with signature checks, respectively.

#### Async lowering

A LuaJIT backend may implement async lowering with internal coroutines, continuation-passing style, or explicit state-machine tables.

#### Runtime library

Generated into every module or available as a shared runtime module:

```lua
local __NULL = {} -- JSON null
local __MISSING = {} -- missing optional field
```

```lua
local __rt = require("deal.runtime")

function __rt.check_null(v)
 if v ~= __NULL then error("runtime: expected null") end
 return v
end

function __rt.check_nullable(inner_check, v)
 if v == __NULL then return v end
 return inner_check(v)
end

function __rt.check_boolean(v)
 if type(v) ~= "boolean" then error("runtime: expected boolean") end
 return v
end


function __rt.check_number(v)
 if type(v) ~= "number" then error("runtime: expected number") end
 return v
end

function __rt.check_string(v)
 if type(v) ~= "string" then error("runtime: expected string") end
 -- validate boundary encoding
 return v
end

function __rt.check_table(v)
 if type(v) ~= "table" then error("runtime: expected table") end
 return v
end

function __rt.check_array(array_descriptor, value)
 if type(value) ~= "table" then error("runtime: expected array") end
 local element_descriptor = __rt.array_element_descriptor(array_descriptor)
 local i = 1
 while i <= #value do
 __rt.check_type(element_descriptor, value[i])
 i = i + 1
 end
 return value
end

function __rt.check_type(expected, value)
 -- dispatched check by runtime type descriptor, including nullable, class shapes, arrays
end

function __rt.check_function_sig(expected_sig, sig, f)
 if expected_sig ~= sig then
 error("runtime: mismatched function signature")
 end
 return f
end

function __rt.class_(class_name, fields)
 -- see Construction
end

function __rt.export_class(name)
 return {__kind = "class", __classname = name}
end

function __rt.function_(sig, f)
 return {__kind = "function", sig = sig, f = f}
end
```

#### Function wrapper

Every declared function is wrapped so that argument and return checks are inlined:

```lua
local add = __rt.function_("(number,number)->number", function(x, y)
 __rt.check_number(x)
 __rt.check_number(y)
 local __ret = x + y
 return __rt.check_number(__ret)
end)

local result = __rt.check_number(add.f(1, 2))
```

#### Arity extension adapter

When a shorter function is assigned to a longer target signature, the compiler emits an adapter:

```lua
local one = __rt.function_("(number)->number", function(x)
 return __rt.check_number(x)
end)
local takesTwo = __rt.function_("(number,number)->number", function(x, y)
 __rt.check_number(x)
 __rt.check_number(y)
 return __rt.check_number(one.f(x))
end)
```

#### Class representation

```lua
__rt.class_("User",
 { id = 1, name = "Ada", active = true, bio = __NULL }
)
```

Optional fields not provided are represented internally via key absence (`nil`) or side-table metadata. Direct source-level field reads translate missing to `__NULL`; `has(obj.field)` distinguishes missing from explicit `null`.

### JVM backend contract

#### JVM value mapping

| Type | JVM representation |
|---|---|
| `null` | Java `null` reference |
| missing optional field | per-field presence bit or `Missing` sentinel |
| `boolean` | primitive `boolean` |
| `number` | primitive `double` |
| `string` | `java.lang.String` with no unpaired surrogate code units |
| `table` | `TTable` runtime object, e.g. ordered map |
| `ClassName` | generated record class or `TClassObject` runtime shape |
| `T[]` | `TArray<T>` runtime object or specialized primitive array |
| `T | null` | nullable JVM reference or tagged nullable wrapper for primitives |
| `(params) => R` | generated function wrapper with descriptor |
| module type | generated module object/export map |

#### JVM implementation

A conforming JVM backend emits Java source, JVM bytecode, or an interpreter over checked DEAL IR.

The JVM backend must preserve the backend conformance contract.

The JVM backend may use JVM primitive types, final classes, verifier-checked bytecode, method signatures, and JIT optimization to prove typed-boundary checks redundant.

Recommended JVM representation:

| DEAL type | JVM representation |
|---|---|
| `boolean` | `boolean` where statically known; boxed `Boolean` at dynamic boundaries |
| `number` | `double` where statically known; boxed `Double` at dynamic boundaries |
| `string` | non-null `java.lang.String` with no unpaired surrogate code units |
| `null` | as defined in [JVM value mapping](#jvm-value-mapping) |
| required class field `T` | Java field of representation of `T` |
| required nullable field `T | null` | nullable representation or tagged wrapper |
| optional field `p?: T` | presence bit plus field storage |
| optional nullable field `p?: T | null` | presence bit plus nullable storage |
| `table` | `DealTable` |
| `T[]` | `DealArray<T>` or specialized primitive array wrapper |
| `(…) => R` | `DealFunction` wrapper with runtime descriptor |
| `async (…) => R` | `DealAsyncFunction` wrapper with runtime descriptor |
| module type | generated module class or export object |

The JVM backend must not expose Java `null` as DEAL `null` across untyped boundaries without validation.

A JVM backend may implement async lowering with virtual threads, blocking calls, futures, or explicit state machines.

---

## Diagnostics

### Compile-time diagnostic format

Every compiler diagnostic must contain:

```txt
code: stable diagnostic code, e.g. E1001
severity: error | warning
message: human-readable message
file
line
column
span length
optional notes
```

### Required diagnostic classes

| Code range | Category |
|---|---|
| `E1xxx` | lexical / parse errors |
| `E2xxx` | name resolution and module resolution |
| `E3xxx` | type checking |
| `E4xxx` | class shape validation |
| `E5xxx` | function signature and call validation |
| `E6xxx` | backend lowering and ABI errors |
| `E7xxx` | declaration-file errors |
| `E8xxx` | runtime errors |

Examples:

```txt
E3001: cannot assign string to number
E3002: cannot infer type of empty array literal
E3003: table field read requires contextual target type
E4001: required class field is missing
E5001: function argument count mismatch
E7001: declaration file cannot contain executable statement
```

### Runtime diagnostic format

Runtime errors must preserve source location when emitted from generated checks:

```txt
DEALRuntimeError {
  code: "E8001",
 message: "expected number, got null",
 file: "src/main.deal",
 line: 12,
 column: 9,
 expected: "number",
 actual: "null"
}
```

Array runtime diagnostics:

| Code | Meaning |
|---|---|
| `E8002` | array index is not an integral number or is out of bounds |
| `E8003` | array element type check failed |


### Operational semantics summary

Evaluation is strict and left-to-right:

1. Evaluate receiver expression before member/index/call arguments.
2. Evaluate call arguments left-to-right.
3. Assignment evaluates receiver, index (if present), then RHS once, before validation and write. Variables evaluate only RHS. Its static type is the RHS type; after successful storage, it yields the original RHS value. Any arity adapter is stored, not yielded. Failed validation leaves the target unwritten; earlier effects remain.
4. Short-circuit `&&` and `||`.
5. Function calls create a new local scope.
6. Class object literals follow [Construction](#construction).
7. Table object literal construction evaluates properties left-to-right and stores values as dynamic fields.

