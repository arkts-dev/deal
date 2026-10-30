# DEAL — Language Specification v1.5

DEAL (Directly Embeddable Agentic Language) uses a strict subset of TypeScript syntax with runtime-enforced static types and distinct semantics. It targets safe, embeddable AI scripting with explicit interoperability boundaries. This specification defines its features, types and execution.

Features not described in this specification do not exist in the language.

---

## Lexical elements

- Tokens and property-name rules are defined by the [lexical grammar](#lexical-grammar-normative). Follow TypeScript module identifier rules by position: `switch`/`enum` cannot name bindings.
- User-declared identifiers must not contain `$`. Compiler-generated names containing `$` are source-visible only when specified, such as `C$fromJson` and `C$toJson`.
- `Record`, `Promise`, `Object`, `undefined` are unshadowable builtins.

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

Keyword ::= 'let' | 'class' | 'function' | 'await' | 'return' | 'if' | 'else'
          | 'while' | 'for' | 'break' | 'continue'
          | 'null' | 'true' | 'false' | 'typeof' | 'instanceof'
          | 'import' | 'export' | 'delete'
          | 'try' | 'catch' | 'throw'

Identifier ::= [a-zA-Z_$] [a-zA-Z0-9_$]*
/* async/as/from/of are contextual identifiers; grammar determines keyword uses.
   Property names also admit TypeScript keywords. */

Literal ::= NullLiteral | UndefinedLiteral | BooleanLiteral | NumberLiteral | StringLiteral | TemplateLiteral

NullLiteral ::= 'null'
UndefinedLiteral ::= 'undefined'
BooleanLiteral ::= 'true' | 'false'
DecimalInteger ::= '0' | [1-9] [0-9]*
NumberLiteral ::= DecimalInteger
 | (DecimalInteger? '.' [0-9]+ ([eE] [+-]? [0-9]+)?)
 | (DecimalInteger [eE] [+-]? [0-9]+)
/* No leading-zero integer part except the single digit 0. */
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

This grammar defines the statement/expression separation, block semantics, assignment targets, record key syntax, function-call expression form, and operator precedence.

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

FunctionDeclaration ::= AsyncPrefix? 'function' Identifier '(' ParameterList? ')' ':' ReturnType Block

AsyncPrefix ::= 'async' /* line-break restrictions: Semicolon rules */

ExternalFunctionDeclaration ::= 'function' Identifier '(' ParameterList? ')' ':' ReturnType ';' /* declaration files only */

ParameterList ::= Parameter (',' Parameter)*

Parameter ::= Identifier ':' Type

ReturnType ::= Type | 'void' | 'Promise' '<' (Type | 'void') '>'

VariableDeclaration ::= 'let' Identifier TypeAnnotation? '=' Expression ';'

TypeAnnotation ::= ':' Type

ExpressionStatement ::= Expression ';'

ReturnStatement ::= 'return' Expression? ';'

IfStatement ::= 'if' '(' Expression ')' Block ('else' (IfStatement | Block))?

WhileStatement ::= 'while' '(' Expression ')' Block

ForOfStatement  ::= 'for' '(' 'let' Identifier 'of' Expression ')' Block

BreakStatement ::= 'break' ';'

ContinueStatement ::= 'continue' ';'

DeleteStatement ::= 'delete' DeleteTarget ';'

DeleteTarget    ::= PostfixExpression '.' Identifier
                  | PostfixExpression '[' Expression ']'
/* Array-element deletion is a compile-time error. */

TryStatement    ::= 'try' Block 'catch' '(' Identifier ')' Block

ThrowStatement  ::= 'throw' Expression ';'

ImportDeclaration ::= 'import' '*' 'as' Identifier 'from' StringLiteral ';'

ExportDeclaration ::= ImplementationExport

ImplementationExport ::= 'export' (FunctionDeclaration | ClassDeclaration)

DeclarationExport ::= 'export' (ExternalFunctionDeclaration | ClassDeclaration)

Expression ::= AssignmentExpression

AssignmentExpression ::= LogicalOrExpression
 | LeftHandSideExpression '=' AssignmentExpression

LogicalOrExpression ::= LogicalAndExpression ('||' LogicalAndExpression)*

LogicalAndExpression ::= EqualityExpression ('&&' EqualityExpression)*

EqualityExpression ::= RelationalExpression (('===' | '!==') RelationalExpression)?

RelationalExpression ::= AdditiveExpression (('<' | '<=' | '>' | '>=') AdditiveExpression
                         | 'instanceof' ClassReference | 'as' Type)?

ClassReference ::= Identifier ('.' Identifier)? | '(' ClassReference ')'

AdditiveExpression ::= MultiplicativeExpression (('+' | '-') MultiplicativeExpression)*

MultiplicativeExpression ::= UnaryExpression (('*' | '/') UnaryExpression)*

UnaryExpression ::= ('!' | '-' | 'typeof') UnaryExpression
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
 | UndefinedLiteral
 | BooleanLiteral
 | NumberLiteral
  | StringLiteral
  | TemplateLiteral
  | Identifier
 | '(' Expression ')'
 | ArrayLiteral
 | ObjectLiteral
 | FunctionExpression

ArrayLiteral ::= '[' ElementList? ']'

ElementList ::= Expression (',' Expression)* ','?

ObjectLiteral ::= '{' PropertyList? '}'

PropertyList ::= Property (',' Property)* ','?

Property ::= Identifier ':' Expression

FunctionExpression ::= AsyncPrefix? 'function' '(' ParameterList? ')' ':' ReturnType Block

LeftHandSideExpression ::= Identifier
 | PostfixExpression
```

### Semicolon rules

Require grammatical semicolons; no automatic insertion. Forbid line terminators, including in comments, between throw/return and their operands or async and function. Bare `return;` remains valid.

### Statement/expression separation

`if`, `while` and `for` bodies require `{ ... }`.
- `Block` is a statement-list and is not an expression. Blocks do not produce values.

Semantics:

- An `if` without `else` evaluates the condition; if true the branch executes.
- A `while` loop evaluates the condition before each iteration.
- `break` exits the nearest enclosing loop.
- `continue` proceeds to the next iteration of the nearest enclosing loop.
- `return` transfers control to the caller; result rules are defined in [Return type requirements](#return-type-requirements).

### Assignment targets

Only variables, declared class fields, Record keys and array elements are assignable; otherwise compile-time error.

```ts
class User { name: string; }
export function main(): null {
 let x = 1;
 let xs: number[] = [0];
 let t: Record<string, unknown> = {};
 let u: User = { name: "Bob" };
 x = 2;
 xs[0] = 5;
 t.key = "value";
 u.name = "Ada";
 return null;
}
```

---

## Type system

### Type algebra (canonical forms)

```txt
Type := null | undefined | boolean | number | string | unknown
      | RecordValue | Error | ClassName | Array<Type>
      | Nullish<Base, flags> | Function(sync, params, result)
      | Function(async, params, result)
Result := Type | void
```

`RecordValue` spells `Record<string, unknown>`. Nullish flags add null/undefined to a base. Unknown accepts all data but authorizes no unchecked operations. `void` and `Promise<R>` are allowed only in function result positions.

### Type grammar (normative)

```antlr
Type ::= FunctionType | NullishType

NullishType ::= (NullishMember '|')* ArrayType ('|' NullishMember)*
NullishMember ::= 'null' | 'undefined'

ArrayType ::= PrimaryType ('[' ']')*

PrimaryType ::= BuiltinType | Identifier | Identifier '.' Identifier | '(' Type ')'
 | 'Record' '<' 'string' ',' 'unknown' '>'

BuiltinType ::= 'null' | 'undefined' | 'boolean' | 'number' | 'string' | 'unknown' | 'Error'

FunctionType ::= '(' FunctionTypeParams? ')' '=>' ReturnType

FunctionTypeParams ::= FunctionTypeParam (',' FunctionTypeParam)*
FunctionTypeParam ::= Identifier ':' Type
```

Constraints:

- Permit `T | null`, `T | undefined`, `T | null | undefined` and `null | undefined`. Flatten parentheses; reject duplicates, canonicalize null before undefined. Unknown absorbs nullish members. Reject other unions.
- Record/Promise applications are closed builtins, not general generics. `Promise<R>` denotes an async signature with result R, requiring async on implementation bodies but not external declarations. Reject promise variables, nesting and unions.
- Void is result-only, including `Promise<void>`; discard void calls as statements, never data.
- Error is builtin nominal and cannot be redeclared.

### Narrowing and checked assertions

Basic narrowing:

```ts
let n: number | null | undefined = 1;
if (n !== null) {
 if (n !== undefined) { let i: number = n; }
}
```

Narrow locals/parameters only. Null/undefined `===`/`!==` tests select/remove that member; unknown complements stay unknown.

```ts
function use(value: string | undefined): void {
 if (value === undefined) {
  return;
 } else {
  let text: string = value; // narrowed
 }
 let text: string = value; // error: outside narrowing block
}
```

```ts
class Profile { nick: string | null; }
let p: Profile = { nick: "Ada" };
if (p.nick !== null) {
 let nick: string = p.nick; // error: properties are not narrowed
}
```

Narrowing invalidation:

- Direct assignment invalidates the binding's refinement.
- Calls preserve refinements; indirect captured-binding mutation may cause subsequent runtime-check failure.
- Narrowing cannot cross functions/back-edges. Each while-body entry, including after continue, receives fresh true-guard refinement; assignment/await invalidate normally, without body-to-guard propagation.
- `await` invalidates all enclosing-function refinements.

`typeof x` returns `undefined`, `boolean`, `number`, `string`, `function`, or `object` (null/arrays/records/classes). Direct tests `typeof x === "kind"` narrow unknown in the matching branch for the first four kinds; `!==` narrows the else branch. Other branches stay unknown; no object/function structural inference. `instanceof C` tests nominal tags (including Error) and narrows the true branch to C; other values yield false, never retagging records.

`e as T` evaluates once, descriptor-checks data type T, and raises E8001 on mismatch; no coercion/construction. Allow unknown sources or compatibility in either direction. Array checks require a compatible persistent element descriptor, not merely currently matching elements.

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
async function tick(): Promise<void> { return; }
async function use(x: string | null): Promise<void> {
 if (x !== null) {
  let before: string = x; // OK
  await tick();
  let after: string = x; // error: narrowing was invalidated
 }
 return;
}
```

### Type compatibility

Types must match exactly unless an explicit compatibility rule permits otherwise.

### Equality and comparison operand rules

Type equality: identical canonical forms; classes by declaration symbol, functions by async marker/parameters/result.

`===` requires equal types, except nullish types admit singleton comparisons and unknown admits null/undefined. Unknown pairs compare data without coercion, retagging or structural comparison. Different runtime kinds differ; null/undefined equal themselves only. Numbers use binary64 equality (NaN differs from itself; signed zeros equal), booleans compare by value, strings by Unicode scalar sequence, arrays/Records/classes/functions by reference identity. `!==` negates `===`.

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
| `undefined` | `undefined` |
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
| record literal `{ p: e, ... }` | `Record<string, unknown>` with target context |
| class body `{ ... }` with target `ClassName` | `ClassName` (contextual) |
| index `a[b]` where `a: T[]`, `b: number` | `T` |
| record member/index read | `unknown`; absent key yields undefined |
| class member `a.p` | declared type; optional adds undefined |
| sync call `f(...)` | declared result, including void |
| `await f(...)` for `f: (...) => Promise<R>` | `R`, including void |
| `typeof e` / `e instanceof C` | `string` / `boolean` |
| `e as T` | `T` |
| `e.code` where `e: Error` | `string` |
| `e.message` where `e: Error` | `string` |

### Assignment type checking

```txt
let x: T_target = expr;
```

1. Infer the RHS type, using target context for literals.
2. Accept equal types, nullish widening with an unchanged base, singleton null/undefined into a target admitting that member, or any data value into unknown.
3. Accept direct function compatibility under [Function type compatibility](#function-type-compatibility), not lifted through arrays/nullish wrappers.
4. Otherwise reject; unknown-to-concrete assignment needs explicit validation.

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

- `null`, `undefined`, `boolean`, `number`, `string`
- `Record<string, unknown>`
- `T[]` and nullish unions where the base is jsonable
- class type `C` with visible generated or declared `C$fromJson` and `C$toJson` of the signatures below

Non-jsonable fields produce compile-time errors at their declarations.

Circular `@jsonable` dependencies, including through arrays and nullish wrappers, are compile-time errors.

In declaration files, `// @jsonable` has no effect; compatible `C$fromJson` and `C$toJson` functions may be declared explicitly.

**Generated functions.** For a `@jsonable` class `C`, the compiler emits two module-level export functions:

```ts
export function C$fromJson(s: string): C | null;
export function C$toJson(v: C): string;
```

**fromJson.** Parses an object; rejects extra keys, missing required-present keys, and duplicate decoded property names at every object nesting level. Decodes fields recursively in declaration order; missing optionals stay absent. Failure returns `null`, never throws or publishes partial instances.

```ts
let invalid: User | null = User$fromJson("{\"name\":\"Ada\",\"naem\":\"Bob\"}"); // null: extra key
```

**toJson.** Applies [JSON encoding](#json-encoding) to declared fields; class input guarantees a string. Required undefined-valued fields are omitted, so encoded JSON need not decode successfully.

Record fields decode object roots to dynamic data. JSON decoding never creates stored undefined. Required fields must be present even when their type admits undefined.

**Optional-field encoding.** For JSON-representable values:

| State | fromJson input | toJson output |
|---|---|---|
| missing | key absent | key omitted |
| present, null | `"field": null` | `"field": null` |
| present, value | `"field": value` | `"field": value` |
| present, undefined | no JSON representation | key omitted |

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

Missing required literal fields are compile-time errors; untyped-boundary violations raise the applicable boundary error.

### Field access

Reading a required-present field yields the field type:

```ts
let n: string = u.name; // OK
```

Optional reads add undefined to the declared type; absence yields undefined. Writes use the declared type without adding undefined. Thus optionality and stored value type remain independent.

```ts
class Profile {
 nick?: string | null | undefined;
}
let p: Profile = {};
let absent = p.nick;             // string | null | undefined; value undefined
p.nick = undefined;              // now present
let present = Object.hasOwn(p, "nick"); // true
p.nick = null;                   // present-null
```

`Object.hasOwn(obj, key)` returns boolean: own presence, without narrowing. Evaluate receiver then key once. Classes require a literal declared-field name; records accept string expressions. No general Object API or literal-string types.

```ts
class Profile { nick?: string | null; }
let p: Profile = { nick: null };
if (Object.hasOwn(p, "nick")) {
 let value: string | null | undefined = p.nick; // valid
 let text: string = p.nick; // error: presence test does not narrow
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

### Class vs record

Class types and `Record<string, unknown>` are distinct; no implicit conversion.

```ts
let u: User = { name: "Ada", address: { city: "A", zip: 1 }, tags: [], friends: [] };
let t: Record<string, unknown> = {};
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

Assignment after deletion restores presence. Storing undefined requires a declared undefined member (or unknown), including at host boundaries:

```ts
class Options { label?: string; raw?: string | undefined; }
let options: Options = {};
options.label = undefined; // error
options.raw = undefined;   // present
Object.hasOwn(options, "raw"); // true
delete options.raw;        // absent
```

---

## Functions

### Function declaration

```ts
function name(p1: T1, p2: T2): R {
 return expr;
}
```

- Parameter and result annotations are required; parameters cannot be optional.
- Result annotations follow [Type grammar](#type-grammar-normative); body checking follows the rules below.

### Return type requirements

- Value returns satisfy normal assignment compatibility with the result type, unwrapping Promise for async implementations.
- Only void-result bodies may fall through or use bare return; both produce undefined. Reject value returns from them. This applies equally to `Promise<void>`. `return null;` is an explicit null result.

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

- Async status matches, as determined by the signature's Promise result notation.
- Result types are identical, including void and nullish members.
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

- Argument count must match the callable's target signature, including after arity extension.
- Arguments satisfy normal assignment compatibility, including nullish widening and unknown targets.
- Wrong argument types are compile-time errors; results follow the signature.
- Async call placement follows [await](#await).

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

The expression produces a value with its declared signature and may occur wherever expressions are allowed.

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

for (let i of [0, 1, 2]) {
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

Infer complete initializer types, including unknown from record reads; unresolved external signatures remain errors:

```ts
let a = null; // null
let b = true; // boolean
let c = 1; // number
let d = 2.0; // number
let e = "hi"; // string
let f = [1, 2, 3]; // number[]
let g = [1.0, 2.0]; // number[]
let h = add(1, 2); // number, if add has a statically known function type
let field = t.someField; // unknown; validate before typed use
```

Object literals and empty arrays require context.

Disallowed:

```ts
let i = t.f(1, 2); // error — unknown is not callable
let j = []; // error — no element type
let l = [1, "1"]; // error — mixed types
let m = { }; // error — must annotate
```

### Redeclaration

Not allowed within the same block scope.

### Scope

Blocks delimit lexical scopes; declaration order governs visibility.

### Name resolution

Resolution order for an identifier expression:

1. Locals/parameters in the innermost block, then enclosing scopes.
2. Module declarations, then imported module bindings.
3. Prelude symbols.

Shadowing follows [Lexical elements](#lexical-elements) builtin-name restrictions and these scope rules:

- Inner block variables may shadow outer block variables.
- Parameters may not shadow other parameters.
- Module-level declarations may not shadow imports.
- Imports may not shadow module-level declarations.

Declaration order:

- Module-level functions/classes are hoisted for name resolution.
- Nested functions are block-scoped, initialized upon declaration execution, and visible in their bodies and following statements/bodies. Earlier references resolve outward; unresolved names are compile-time name-resolution errors.
- `let` declarations are visible only after declaration.

### Definite return analysis

For bodies requiring a value under [Return type requirements](#return-type-requirements), definitely-returning statements are:

- `return` or `throw`
- `if`/`else` or `try`/`catch` where both arms definitely return
- block whose final reachable statement definitely returns
- `while (true)` whose body definitely returns, with no reachable `break` targeting that loop

Other statements do not definitely return.

---

## Records

### Record type and semantics

`Record<string, unknown>` is a string-keyed container with observable reference identity and absent/present-undefined/present-null states.

```ts
let data: Record<string, unknown> = { name: "Ada" };
let value = data.name;             // unknown
let name = value as string;        // checked, no coercion
let missing = data.missing;        // unknown holding undefined
```

### Reading and writing fields

Member/string-index reads infer unknown regardless of context. Reject `let name: string = data.name;`; assert or narrow first. Calls require a checked function signature, preserving identity.

Writes accept all data, including undefined/unknown, without a field schema or retagging stored values.

```ts
data.name = undefined;             // present-undefined
Object.hasOwn(data, "name");       // true
delete data.name;
Object.hasOwn(data, "name");       // false
data.name = null;                  // present-null
```

### Literal keys and deletion

Record literal keys must be distinct.

```ts
let data: Record<string, unknown> = { name: "Ada", age: 36 };
// Quoted/computed literal keys are unsupported; use indexed writes:
let key: string = "extra";
data[key] = 1;
delete data[key];
```

Deletion removes an own key. Reads of absent keys yield undefined; present keys may hold any data value. Stored classes/arrays/functions retain their tags, element descriptors and identities.

## Arrays

### Array type and semantics

`T[]` is invariant in T, admits any data element type, and preserves element descriptors governing writes through unknown/host aliases. json.parse arrays, including nested/empty/Record descendants, retain unknown descriptors. C$fromJson fields and fresh host arrays admitted as T[] receive T descriptors after validation. Host-crossing arrays retain descriptors/identity. Assertions never infer/retag descriptors: parsed [1] cannot assert number[].

### Indexing

Arrays are 0-based.

```ts
let xs: number[] = [10, 20, 30];
let x: number = xs[0]; // ok
```

### Bounds behavior

Array indexes must have type `number`. Reads require an integral number in `0..length-1`; otherwise raise `E8002`. Negative zero indexes zero.

Nullish array elements follow target typing:

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
for (let value of xs) {
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
if (t.value) { } // error: unknown is not boolean
```

Dynamic record values need narrowing/assertion:

```ts
let b = t.flag as boolean;
if (b) { }
```

### Conditional

```ts
if (boolean_expr) {
 consume();
} else {
 recover();
}
```

### While

```ts
while (boolean_expr) {
 consume();
}
```


### For-of

```ts
let xs: number[] = [1, 2, 3];
for (let x of xs) {
  // x: number, each element in order
}

let s: string = "abc";
for (let c of s) {
  // c: string, one Unicode scalar value
}
```

Evaluate the `T[]` or `string` iterable once. Arrays visit successive indexes from zero using live length/contents: appends and unvisited replacements are observed; variable reassignment cannot redirect traversal. Strings yield successive scalars. Infer `T` or `string`; reject binding annotations. See [Closures](#closures-and-captured-variables) for binding freshness. Supports `break` and `continue`.

---

## Error handling

### Error type

The builtin Error shape is equivalent to this descriptive pseudo-source:

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
  if (e instanceof Error) {
    let message: string = e.message;
  }
}
```

Catch binds the identical Error as unknown; narrow before field access or rethrow. Host failures normalize to Error without replacing DEAL Errors; native code/message/cause are backend-defined. Rethrow preserves fields, origin, frames and cause. Catch is skipped on success; after handling, execution continues after the statement.

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

Async implementations use Promise result annotations under [Type grammar](#type-grammar-normative) and [Return type requirements](#return-type-requirements):

```ts
import * as http from "host/http";

async function fetchUser(path: string): Promise<User | null> {
  let json: string = await http.get(path);
  return User$fromJson(json);
}
```

Async function expressions use the same result notation:

```ts
let f: (path: string) => Promise<User | null> = async function(path: string): Promise<User | null> {
  return await fetchUser(path);
};
```

### await

```ts
async function loadUser(): Promise<User | null> {
  let u: User | null = await fetchUser("/user/42");
  return u;
}
```

Require `await E` inside async bodies, where E is a call with signature `(...) => Promise<R>`. Reject async calls outside immediate await and await of sync calls. Callees may be statically or dynamically resolved.

### Async operation semantics

Calls create internal operations, not data: no storage, passing, returning, comparison or inspection, including through unknown.

Each operation completes once with the Promise result (undefined for void), or an Error.

Await yields the completion value or raises the identical completion Error at the await expression, handled by ordinary catch rules.

Pre-await effects precede resumed effects. There are no source-level concurrent starts, scheduling, cancellation, polling or shared-memory primitives.

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
export function name(p1: T1, p2: T2): R { return result; }
export class Name { field: T; }
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

Imported module members are not dynamic record reads. They are statically typed member accesses over the module type.

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
- No top-level ambient declarations in implementation `.deal` files.
- Declaration files may describe host modules.

Host ABI:

- Host modules are loaded only if listed in `deal.json.externals`.
- The declaration path is resolved relative to the manifest directory unless absolute paths are permitted by host policy.
- Host module runtime object must expose exported names from its `.d.deal` declaration.
- Extra host exports are ignored; missing declared exports are load-time errors.
- Crossings follow [Typed boundaries](#typed-boundaries).
- External `function f(...): Promise<R>;` has no async modifier; the host must supply an operation compatible with [Async operation semantics](#async-operation-semantics).

### Package manifest

A project may define `deal.json` at its root:

```json
{
 "languageVersion": "1.5",
 "moduleRoots": ["src"],
 "output": "build/lua",
 "backend": "luajit",
 "stdlib": "1.5",
 "dependencies": {},
 "externals": {
  "host/log": {
   "declaration": "bindings/host-log.d.deal"
  }
 }
}
```

Rules:

- `languageVersion` must be `"1.5"` and governs all source and declaration files in the project.
- `moduleRoots` participate in import resolution.
- DEAL has no package registry. `dependencies` is reserved for local/package-manager integration later.
- Standard library modules may be described by bundled `.d.deal` files, but are resolved as part of the language distribution rather than as project external host modules.
- `externals` declares host modules available to the compiler; importing an unlisted external is a compile-time error. External entries describe host declarations only; `nativeLibrary` is not a valid manifest option.

### Standard library declarations

The compiler ships practical minimal standard modules. All are imported explicitly.

#### `std/console.d.deal`

```ts
export function log(x: string): void;
export function error(x: string): void;
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
export function keys(t: Record<string, unknown>): string[];
```

Returns present string keys, including null/undefined-valued keys; order is unspecified.

#### `std/json.d.deal`

```ts
export function parse(s: string): Record<string, unknown>;
export function stringify(value: unknown): string | undefined;
```

`parse` requires an object root; malformed JSON, duplicate decoded keys and other roots raise Error. Numeric tokens follow [Numbers](#numbers), also in class decoding.

##### JSON encoding

Use JavaScript JSON.stringify value rules: omit undefined/function-valued properties; encode such array elements as null; top-level undefined/function yields undefined. Non-finite numbers become null; finite numbers roundtrip except negative zero becomes `0`. Preserve null; reject cycles, not shared acyclic children. Own-key order: array-index keys ascending, then creation order (declared fields use declaration order). No serialization hooks.

#### `std/math.d.deal`

```ts
export function floor(x: number): number;
export function ceil(x: number): number;
export function sqrt(x: number): number;
export function absNumber(x: number): number;
```

`sqrt` preserves NaN, +∞ and signed zero. Negative inputs yield NaN.

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
let t: Record<string, unknown> = json.parse("{\"name\":\"Ada\"}");
```

### Host APIs

Backend globals and APIs are unavailable to source code unless exposed through an imported declaration module.

### Host ABI and interoperability

#### Value mapping

See [Backend contracts](#backend-contracts) for backend-specific representations.

#### Boundary checks

Check every untyped host-to-DEAL crossing:

```ts
let f: (x: number) => number = lib.f;
```

Unknown preserves supported data identity, not opaque hosts. Cycle-safe validation rejects ingress before publication for malformed Unicode/unsupported leaves, including unread Record/container descendants; cycles/sharing retain original identities. See [assertions](#narrowing-and-checked-assertions) and [validation](#backend-conformance-contract).


#### Function ABI

Function representation and invocation are backend-defined, preserving signature checks and identity.

### Host error and debugging

Uncaught Errors escape as `DEALRuntimeError` with:

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
| `null` | JSON/language null |
| `undefined` | distinct language value; missing reads and void completion |
| missing field | presence state, distinct from stored undefined |

### Backend conformance contract

Accepted implementation/declaration sources must parse as TypeScript. Conformance tests cover the following observable behavior across backends, including host ingress and egress:

Observable behavior includes:

- compile-time diagnostics, including reserved identifiers, leading-zero literals and forbidden line breaks
- successful runtime values
- runtime error codes and source locations
- module import/export behavior
- host boundary behavior
- null, undefined, missing-field and own-presence semantics
- unknown extraction, checked assertions, narrowing and void results
- class nominal identity
- function type identity, including the async marker
- evaluation order
- binary64 arithmetic, signed-zero behavior, and discrete-position validation
- JSON encoding/decoding, including generated class helpers

A backend may choose any internal representation, including erased, partially erased, statically typed, boxed, unboxed, interpreted, source-generated, or bytecode-generated representation, if observable behavior is unchanged.

#### Typed boundaries

A typed boundary is a point where a runtime value is accepted as, stored as, returned as, exported as, or reconstructed as a declared DEAL type.

Validate every typed boundary unless the backend proves the value satisfies the type despite any untyped ingress; type erasure cannot weaken validation.

Typed boundaries are:

- variable initialization with declared or inferred type T
- assignment to a variable with declared or inferred type `T`
- assignment to a declared class field of type `T`
- assignment to an array element of `T[]`
- parameter entry and return/completion of source, external and standard-library functions
- class object literal construction
- class value received from an untyped boundary
- optional field read adding undefined for absence
- checked `as T` extraction; raw record reads retain unknown
- value read from imported module member
- value exposed through module export table
- value crossing from host into DEAL through `.d.deal`
- value crossing from DEAL into host through `.d.deal`
- `@jsonable` `fromJson` field validation
- `@jsonable` `toJson` field serialization input validation

### Runtime type descriptor format

```txt
RuntimeTypeDescriptor := PrimitiveDescriptor | ClassDescriptor
                       | ArrayDescriptor | NullishDescriptor | FunctionDescriptor

PrimitiveDescriptor :=
 "null" | "undefined" | "boolean" | "number" | "string" | "record" | "unknown" | "Error"

ClassDescriptor :=
  "@" ModuleRoot "/" ModuleRelativePath "/" ClassName
 | "@$external/" ExternalSpecifier "/" ClassName

ModuleRoot := /* configured module root from deal.json */
ModuleRelativePath := /* path components from module root to defining file */
ExternalSpecifier := /* raw import specifier: a key in deal.json.externals */

ArrayDescriptor :=
 "[" RuntimeTypeDescriptor "]"

NullishDescriptor :=
 ("?" | "!" | "?!") RuntimeTypeDescriptor /* null, undefined, both */
 | "null|undefined"

FunctionDescriptor :=
 AsyncMarker? "(" ParamDescriptorList? ")" "->" ResultDescriptor

ResultDescriptor := RuntimeTypeDescriptor | "void"

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
async(number)->string // (x:number)=>Promise<string>
?!number // number | null | undefined
!string // string | undefined
()->void // () => void
async()->void // () => Promise<void>
```

Nullish prefixes require a non-nullish/non-unknown base; no nesting. Descriptors encode canonical types from [Type grammar](#type-grammar-normative), including result-only void. They are internal ABI text, not source syntax.

## Backend contracts

These representation mappings preserve, and cannot override, the source semantics and [conformance contract](#backend-conformance-contract).

### LuaJIT backend contract

#### LuaJIT value mapping

| Type | LuaJIT representation |
|---|---|
| `null` / `undefined` | distinct sentinels `__NULL` / `__UNDEFINED` |
| missing optional field | absent key or `__MISSING` internally |
| `boolean` | Lua boolean |
| `number` | Lua number |
| `string` | Lua string containing valid UTF-8 |
| `Record<string, unknown>` | Lua table |
| `ClassName` | Lua table tagged with class symbol |
| `T[]` | Lua table with 1-based contiguous storage |
| nullish / unknown | base value or applicable sentinel / validated tagged data |
| `(params) => R` | wrapper table `{ __kind = "function", sig, f }` |
| module type | Lua export table |

#### Module loading

Runtime candidates: emitted path for `S.deal`, then host `require(S)`. Default conversion under the configured module root replaces separators with dots and strips `.deal`: `./math/vector.deal` becomes `require("math.vector")`.

#### Function ABI

Function values are wrappers with explicit `.f(...)` calls, never metatable `__call`. Runtime adapters `as_lua_function` and `from_lua_function` expose DEAL functions to hosts and wrap incoming host functions with signature checks, respectively.

#### Async lowering

A LuaJIT backend may implement async lowering with internal coroutines, continuation-passing style, or explicit state-machine tables.

#### Class representation

Missing fields use nil/metadata; translate missing reads and void completion to `__UNDEFINED`. Stored undefined uses that sentinel, never nil, preserving presence and contiguous arrays.

### JVM backend contract

#### JVM value mapping

| Type | JVM representation |
|---|---|
| `null` / `undefined` | Java null / unique `Undefined` singleton |
| missing optional field | presence bit or distinct `Missing` sentinel |
| `boolean` | primitive `boolean` |
| `number` | primitive `double` |
| `string` | non-null `java.lang.String` with no unpaired surrogate code units |
| `Record<string, unknown>` | `DealTable` runtime object |
| `ClassName` | generated record class or `TClassObject` runtime shape |
| `T[]` | `DealArray<T>` runtime object or specialized primitive array |
| nullish / unknown | nullable/tagged payload with distinct undefined / boxed validated data |
| `(params) => R` | generated `DealFunction` wrapper with descriptor |
| `(params) => Promise<R>` | `DealAsyncFunction` wrapper with async descriptor |
| module type | generated module object/export map |

#### JVM implementation

A conforming JVM backend emits Java source, JVM bytecode, or an interpreter over checked DEAL IR.

JVM primitives, final classes, verifier-checked bytecode, signatures and JIT optimization may establish the proofs required by [Typed boundaries](#typed-boundaries).

At dynamic boundaries box booleans/numbers as Boolean/Double; statically typed storage may stay primitive. Required fields use their type's representation; optional fields add a presence bit, independently of nullish payload tags.

Validate Java null at ingress under [Typed boundaries](#typed-boundaries). Void completion uses the Undefined singleton; absence retains separate presence metadata.

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
E3003: unknown value requires narrowing or a checked assertion
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
7. Record object literal construction evaluates properties left-to-right and stores values as dynamic fields.

