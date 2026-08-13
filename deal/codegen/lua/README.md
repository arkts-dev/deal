# deal.codegen.lua — Lua ABI Emission Layer

This package contains the DEAL LuaJIT backend:

- **`LuaAbi`** — the explicit, stateless ABI emission layer (ISSUE-0074).
  It is the sole production surface for the mandated name/key policies:
  generated `$`-identifier references and class artifacts (namespace table),
  reserved-word/safe-identifier table-field key forms, helper-name
  derivation, and the frozen export-key rules.
- **`LuaBackend`** — the only caller of `LuaAbi`. All in-scope emission sites
  are rerouted through the layer; every other emission category is
  byte-for-byte unchanged ("do not refactor unrelated Lua emission").

## Layer contract

`LuaAbi` is `final`, has a private constructor, and depends only on the JDK
(no `deal.ast`/`deal.checker`/`deal.module` imports), so it is unit-testable
in isolation.

### Backend-neutral decision surface (reusable for future backends)

Keyword data is caller-supplied — there is no silent binding of the Lua set
for non-Lua targets:

| Function | Contract |
|---|---|
| `isReserved(Set<String> reserved, String name)` | `name` in the caller's reserved set |
| `isSafeIdentifier(Set<String> reserved, String name)` | ASCII `[A-Za-z_][A-Za-z0-9_]*` shape AND not in `reserved` |
| `fieldKeyForm(Set<String> reserved, String name)` | `KeyForm.DOT` iff safe, else `KeyForm.BRACKET` |
| `helperKey(String className, HelperKind)` | raw DEAL artifact names: `User_defaults` / `User_meta` / `User_fields` / `User$fromJson` / `User$toJson` |

`RESERVED` is the immutable 24-word LuaJIT constant (Lua 5.1's 21 reserved
words plus `goto`/`const`/`continue`). The Lua composers evaluate the
predicates with `RESERVED`; a future JVM backend supplies its own keyword
set through the same functions.

### Lua emission composers

`stringKeyLiteral`, `memberAccess`, `hasCheck`, `tableField`,
`exportAssignment`, `generatedRef`, `helperRef`, `namespaceAssignment`.
Dot form iff the key is a safe Lua identifier; otherwise a bracket-string
key escaped with the Lua 5.1 escape set (`\\ \" \n \r \t \a \b \f \v`,
plus `\ddd` — exactly three digits when followed by a digit — for other
control bytes). The emitted runtime string key equals the DEAL field name
byte-for-byte (`$` preserved raw).

### Owned emission sites (`LuaBackend`)

- Header: `local __deal = {}` + `__deal["Error_defaults"] = { code = "", message = "" }`
  (the legacy `local Error_defaults` statements are retired).
- Module-level class declarations: `__deal["<C>_defaults"]` /
  `__deal["<C>_meta"]`. A module-scope flag is saved, cleared, and restored
  while walking every nesting container — including bare blocks, whose
  classes are block-scoped (legal DEAL) — so only module-level declarations
  write namespace keys; nested classes keep scope-local artifact locals
  (`local <C>_defaults` / `local <C>_meta`) and never write namespace keys.
- Class construction (`__rt.class_`) references the defaults artifact via
  `__deal["<C>_defaults"]` for module-level classes — except when a
  non-module-level declaration of the same name is lexically visible at
  the construction site (nested shadowing, D2.6): then the bare `C_defaults`
  local is referenced, and Lua lexical scoping resolves it to the
  scope-local artifact exactly as the pre-namespace backend did ("keeps
  today's behavior"). The backend tracks non-module-level declarations per
  emitted Lua scope boundary — function bodies, each then/elseif/else
  branch of an if chain, while/for/do loop blocks, the try pcall closure,
  and the catch if-block — so declaration visibility mirrors Lua lexical
  scope exactly (a then-branch class is invisible in the else branch; a
  catch-block class is invisible after the try). Bare blocks emit no Lua
  scope of their own, so their declarations register in the enclosing
  scope.
- `@jsonable` deferred pass: module-level classes emit
  `__deal["<C>_fields"]`, `__deal["<C>$fromJson"]`, `__deal["<C>$toJson"]`;
  the jsonable forward declarations are removed (table fields need no
  lexical capture). Non-module-level `@jsonable` export classes keep the
  legacy `$`→`_` scope-local form (`local C_fields` / `local C_fromJson` /
  `local C_toJson`) and reference their scope-local `C_defaults` /
  `C_fields` artifacts — they never write `__deal` namespace keys
  (D2.6 ownership invariant).
- Export registration is scope-consistent with declaration emission:
  module-level classes export namespace references
  (`exports.C = __deal["C_meta"]`); classes exported from non-module-level
  positions export the bare scope-local artifacts
  (`exports.C = C_meta`, `exports["C$fromJson"] = C_fromJson`), matching
  the pre-namespace backend.
- `$`-identifier references: `__deal["<name>"]` (only module-level
  @jsonable classes register `$` symbols, so every reachable `$` reference
  is a namespace field).
- Field keys at member access, `has()`, table literals, class defaults,
  class construction, and export emission.

Export keys are frozen per kind: META under the bare class name
(`exports.User = __deal["User_meta"]`), the four helpers under their raw
DEAL names (`exports["User$fromJson"] = __deal["User$fromJson"]`). The
DEAL-level export key set is byte-identical to the pre-layer ABI.

## Documented residuals (out of mandate)

- A user binding named `__deal` can shadow the namespace local (no fixture
  binds it; capture-proofing requires the excluded Scope-registry machinery).
- Binding-identifier positions with Lua-keyword names (`export function end()`)
  still emit invalid Lua — outside the "table field emission" mandate.
- Pre-existing capture hazards of `__rt`/`__NULL`/`__MISSING`/`int`/`number`
  and the json-loader locals `__json`/`__json_parse`/`__json_stringify` are
  unchanged.

## Tests

Root-level command (runs the whole suite, including the ABI unit tests and
the 320-test conformance suite):

```bash
./run_tests.sh
```

ABI unit tests alone (JUnit4 + Hamcrest; `LuaAbi` is covered 100%
line/branch by `LuaAbiTest` + `LuaAbiBackendTest`):

```bash
javac --release 25 -d build -cp /usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  deal/ast/*.java deal/types/*.java deal/diagnostics/*.java deal/lexer/*.java \
  deal/parser/*.java deal/checker/*.java deal/codegen/*.java deal/codegen/lua/*.java \
  deal/ir/*.java deal/module/*.java deal/Main.java \
  test/StubModuleResolver.java test/LuaAbiTest.java test/LuaAbiBackendTest.java \
  test/CrossModuleTypingTest.java
java -ea -cp build:/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  org.junit.runner.JUnitCore deal.test.LuaAbiTest deal.test.LuaAbiBackendTest \
  deal.test.CrossModuleTypingTest
```

Coverage gate (JaCoCo CSV totals — production `deal.*`, excluding
`deal.test.*`, line ≥ 75% and branch ≥ 55%, compiled with
`javac --release 22`):

```bash
./coverage.sh
```

Conformance fixtures for the two fixed defects:
`test/conformance/backend-runtime/lua-abi/reserved-word-table-fields.deal`
(RC-3) and
`test/conformance/backend-runtime/lua-abi/jsonable-helper-name-near-collision.deal`
(RC-2). Both must be `runtime-ok` under LuaJIT with zero skips.
