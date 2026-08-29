# IR-Containment Pin Migration Record — ISSUE-0358

Every `irContains`/`irNotContains` pin carried by the 17 JSON slice files under
`test/conformance/fixtures/` is recorded here with its source case and its one named
destination. Pins are migrated pin-before-delete; the JSON files themselves are
unchanged in this change (the still-running JSON path in `BackendConformanceTest`
stays green until the corpus-absorption tasks retire it).

Destinations:

- `golden: test/ir-goldens/<slug>.ir.txt` — the case source became the fixture
  `test/ir-goldens/fixtures/<slug>.deal`; `IrGoldenTest` compares the full IR dump
  against the golden file byte-for-byte (full golden diff — the established exact
  assertion style). Removing the golden or perturbing any dumped line fails the
  suite (verified negative controls below).
- `JvmBackendTest.testIrDumpExactMigration — <file> :: <name> block` — the case runs
  the real multi-module orchestrator with `--dump-ir`; the concatenated module dumps
  are compared against an embedded expected block with full-text equality
  (`=== IR: <file> ===` framing identical to `BackendConformanceTest.collectIrDumps`,
  the per-test temp project root normalized to `<PROJECT>`). Never a substring check.
- `dropped — rationale: …` — not used in this change: every pin has a retained
  exact assertion destination. Zero drops.

Span-path note: pins whose literal text embeds a `fixture-<name>` span fragment (e.g.
`if @fixture-jvm-if-else.deal`) pin a dump line whose span path is the compiling
file name. The golden fixture compiles under its own path, so the golden pins the
same dump line — same node kind, type, and position — with the golden fixture path
in its span. The assertion strength is unchanged (full-dump equality).

## async-await.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `async-simple-await` | contains | `await : int` | `golden: test/ir-goldens/async-await-async-simple-await.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-simple-await.deal`) |
| `async-simple-await` | contains | `async function f: int` | `golden: test/ir-goldens/async-await-async-simple-await.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-simple-await.deal`) |
| `async-sync-complete` | contains | `async function f: int` | `golden: test/ir-goldens/async-await-async-sync-complete.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-sync-complete.deal`) |
| `async-sync-complete` | contains | `async-completion` | `golden: test/ir-goldens/async-await-async-sync-complete.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-sync-complete.deal`) |
| `async-error-propagation` | contains | `await : int` | `golden: test/ir-goldens/async-await-async-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-error-propagation.deal`) |
| `async-error-propagation` | contains | `try` | `golden: test/ir-goldens/async-await-async-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-error-propagation.deal`) |
| `async-nested-await` | contains | `await : int` | `golden: test/ir-goldens/async-await-async-nested-await.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-nested-await.deal`) |
| `async-nullable-return` | contains | `async function f: ?string` | `golden: test/ir-goldens/async-await-async-nullable-return.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-nullable-return.deal`) |
| `async-with-params` | contains | `await : int` | `golden: test/ir-goldens/async-await-async-with-params.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-with-params.deal`) |
| `async-with-params` | contains | `async(int,int)->int` | `golden: test/ir-goldens/async-await-async-with-params.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-with-params.deal`) |
| `async-throw-catch` | contains | `async function f: string` | `golden: test/ir-goldens/async-await-async-throw-catch.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-throw-catch.deal`) |
| `async-throw-catch` | contains | `try` | `golden: test/ir-goldens/async-await-async-throw-catch.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-throw-catch.deal`) |
| `async-throw-catch` | contains | `catch` | `golden: test/ir-goldens/async-await-async-throw-catch.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-throw-catch.deal`) |
| `async-multiple-awaits` | contains | `await : int` | `golden: test/ir-goldens/async-await-async-multiple-awaits.ir.txt` (fixture: test/ir-goldens/fixtures/async-await-async-multiple-awaits.deal`) |

File totals: 14 `irContains`, 0 `irNotContains`, 14 pins — all retained (0 dropped).

## error-shape.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## js-skeleton.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## json-helpers.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## jvm-arrays-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-arr-literal-read-length` | contains | `array : [int]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-literal-read-length.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-literal-read-length.deal`) |
| `jvm-arr-literal-read-length` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-literal-read-length.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-literal-read-length.deal`) |
| `jvm-arr-literal-read-length` | contains | `member .length : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-literal-read-length.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-literal-read-length.deal`) |
| `jvm-arr-write-readback` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-write-readback.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-write-readback.deal`) |
| `jvm-arr-write-readback` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-write-readback.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-write-readback.deal`) |
| `jvm-arr-append-at-length` | contains | `array : [int]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-append-at-length.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-append-at-length.deal`) |
| `jvm-arr-append-at-length` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-append-at-length.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-append-at-length.deal`) |
| `jvm-arr-append-at-length` | contains | `member .length : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-append-at-length.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-append-at-length.deal`) |
| `jvm-arr-four-element-types` | contains | `array : [number]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-four-element-types.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-four-element-types.deal`) |
| `jvm-arr-four-element-types` | contains | `array : [string]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-four-element-types.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-four-element-types.deal`) |
| `jvm-arr-four-element-types` | contains | `array : [boolean]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-four-element-types.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-four-element-types.deal`) |
| `jvm-arr-four-element-types` | contains | `index [] : number` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-four-element-types.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-four-element-types.deal`) |
| `jvm-arr-four-element-types` | contains | `index [] : string` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-four-element-types.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-four-element-types.deal`) |
| `jvm-arr-function-param-return` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-function-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-function-param-return.deal`) |
| `jvm-arr-alias-mutation-append` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-alias-mutation-append.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-alias-mutation-append.deal`) |
| `jvm-arr-negative-read-e8002` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-negative-read-e8002.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-negative-read-e8002.deal`) |
| `jvm-arr-oob-read-e8001` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-oob-read-e8001.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-oob-read-e8001.deal`) |
| `jvm-arr-negative-write-e8002` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-negative-write-e8002.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-negative-write-e8002.deal`) |
| `jvm-arr-gap-write-e8002` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-gap-write-e8002.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-gap-write-e8002.deal`) |
| `jvm-arr-eval-order-read` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-eval-order-read.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-eval-order-read.deal`) |
| `jvm-arr-eval-order-write` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-eval-order-write.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-eval-order-write.deal`) |
| `jvm-arr-eval-order-hoisted` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-eval-order-hoisted.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-eval-order-hoisted.deal`) |
| `jvm-arr-eval-order-write-hoisted-parity` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-eval-order-write-hoisted-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-eval-order-write-hoisted-parity.deal`) |
| `jvm-arr-literal-eval-order-hoisted-parity` | contains | `array : [int]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-literal-eval-order-hoisted-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-literal-eval-order-hoisted-parity.deal`) |
| `jvm-arr-cmp-past-end-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-past-end-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-past-end-parity.deal`) |
| `jvm-arr-cmp-negative-index-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-negative-index-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-negative-index-parity.deal`) |
| `jvm-arr-cmp-both-reads-neg-order-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-both-reads-neg-order-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-both-reads-neg-order-parity.deal`) |
| `jvm-arr-cmp-both-reads-index-hoist-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-both-reads-index-hoist-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-both-reads-index-hoist-parity.deal`) |
| `jvm-arr-cmp-both-reads-inbounds-order-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-both-reads-inbounds-order-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-both-reads-inbounds-order-parity.deal`) |
| `jvm-arr-literal-read-write-length-parity` | contains | `array-write` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-literal-read-write-length-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-literal-read-write-length-parity.deal`) |
| `jvm-arr-multi-type-parity` | contains | `array : [number]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-multi-type-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-multi-type-parity.deal`) |
| `jvm-arr-multi-type-parity` | contains | `array : [string]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-multi-type-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-multi-type-parity.deal`) |
| `jvm-arr-multi-type-parity` | contains | `array : [boolean]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-multi-type-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-multi-type-parity.deal`) |
| `jvm-arr-negative-read-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-negative-read-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-negative-read-parity.deal`) |
| `jvm-arr-discard-past-end-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-discard-past-end-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-discard-past-end-parity.deal`) |
| `jvm-arr-not-read-parity` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-not-read-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-not-read-parity.deal`) |
| `jvm-arr-shortcircuit-read-parity` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-shortcircuit-read-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-shortcircuit-read-parity.deal`) |
| `jvm-arr-and-boundary-parity` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-and-boundary-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-and-boundary-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-before-read-error-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-before-read-error-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-before-read-error-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-boolean-parity` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-boolean-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-boolean-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-nilaware-right-parity` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-nilaware-right-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-nilaware-right-parity.deal`) |
| `jvm-arr-cmp-both-raise-precedence-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-both-raise-precedence-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-both-raise-precedence-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-inbounds-order-parity` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-inbounds-order-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-inbounds-order-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-number-parity` | contains | `index [] : number` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-number-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-number-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-string-parity` | contains | `index [] : string` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-string-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-string-parity.deal`) |
| `jvm-arr-cmp-lhs-effect-read-order` | contains | `index [] : int` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-read-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-cmp-lhs-effect-read-order.deal`) |
| `jvm-arr-lit-nil-element-luajit-collapse` | contains | `array : [boolean]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-lit-nil-element-luajit-collapse.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-lit-nil-element-luajit-collapse.deal`) |
| `jvm-arr-lit-nil-element-luajit-collapse` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-lit-nil-element-luajit-collapse.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-lit-nil-element-luajit-collapse.deal`) |
| `jvm-arr-lit-nil-element-jvm-e8001` | contains | `array : [boolean]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-lit-nil-element-jvm-e8001.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-lit-nil-element-jvm-e8001.deal`) |
| `jvm-arr-lit-nil-element-jvm-e8001` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-lit-nil-element-jvm-e8001.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-lit-nil-element-jvm-e8001.deal`) |
| `jvm-arr-lit-nil-element-mixed-jvm-e8001` | contains | `array : [boolean]` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-lit-nil-element-mixed-jvm-e8001.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-lit-nil-element-mixed-jvm-e8001.deal`) |
| `jvm-arr-lit-nil-element-mixed-jvm-e8001` | contains | `index [] : boolean` | `golden: test/ir-goldens/jvm-arrays-slice-jvm-arr-lit-nil-element-mixed-jvm-e8001.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-arrays-slice-jvm-arr-lit-nil-element-mixed-jvm-e8001.deal`) |

File totals: 52 `irContains`, 0 `irNotContains`, 52 pins — all retained (0 dropped).

## jvm-async-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-async-direct-await` | contains | `async function g: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-direct-await.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-direct-await.deal`) |
| `jvm-async-direct-await` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-direct-await.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-direct-await.deal`) |
| `jvm-async-direct-await` | contains | `[boundary: async-completion]` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-direct-await.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-direct-await.deal`) |
| `jvm-async-value-positions` | contains | `async function add: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-value-positions.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-value-positions.deal`) |
| `jvm-async-value-positions` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-value-positions.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-value-positions.deal`) |
| `jvm-async-value-positions` | contains | `await : string` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-value-positions.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-value-positions.deal`) |
| `jvm-async-value-positions` | contains | `await : boolean` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-value-positions.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-value-positions.deal`) |
| `jvm-async-value-positions` | contains | `await : number` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-value-positions.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-value-positions.deal`) |
| `jvm-async-discard-position` | contains | `async function tick: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-discard-position.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-discard-position.deal`) |
| `jvm-async-discard-position` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-discard-position.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-discard-position.deal`) |
| `jvm-async-completion-success` | contains | `async function noop: null` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-completion-success.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-completion-success.deal`) |
| `jvm-async-completion-success` | contains | `await : null` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-completion-success.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-completion-success.deal`) |
| `jvm-async-completion-success` | contains | `await : boolean` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-completion-success.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-completion-success.deal`) |
| `jvm-async-completion-int-boundary` | contains | `async function big: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-completion-int-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-completion-int-boundary.deal`) |
| `jvm-async-completion-int-boundary` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-completion-int-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-completion-int-boundary.deal`) |
| `jvm-async-completion-int-boundary` | contains | `[boundary: async-completion]` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-completion-int-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-completion-int-boundary.deal`) |
| `jvm-async-error-propagation` | contains | `async function bomb: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-error-propagation.deal`) |
| `jvm-async-error-propagation` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-error-propagation.deal`) |
| `jvm-async-nested-error-propagation` | contains | `async function innermost: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-nested-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-nested-error-propagation.deal`) |
| `jvm-async-nested-error-propagation` | contains | `async function mid: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-nested-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-nested-error-propagation.deal`) |
| `jvm-async-nested-error-propagation` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-nested-error-propagation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-nested-error-propagation.deal`) |
| `jvm-async-nested-await-args` | contains | `async function inner: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-nested-await-args.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-nested-await-args.deal`) |
| `jvm-async-nested-await-args` | contains | `async function outer: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-nested-await-args.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-nested-await-args.deal`) |
| `jvm-async-nested-await-args` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-nested-await-args.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-nested-await-args.deal`) |
| `jvm-async-await-in-condition` | contains | `async function flag: boolean` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-await-in-condition.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-await-in-condition.deal`) |
| `jvm-async-await-in-condition` | contains | `await : boolean` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-await-in-condition.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-await-in-condition.deal`) |
| `jvm-async-function-value` | contains | `async function value: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value.deal`) |
| `jvm-async-function-value` | contains | `let f: async()->int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value.deal`) |
| `jvm-async-function-value` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value.deal`) |
| `jvm-async-function-value-callback` | contains | `async function plus1: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value-callback.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value-callback.deal`) |
| `jvm-async-function-value-callback` | contains | `ident cb : async(int)->int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value-callback.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value-callback.deal`) |
| `jvm-async-function-value-callback` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value-callback.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value-callback.deal`) |
| `jvm-async-function-value-local-reassign` | contains | `async function a: string` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value-local-reassign.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value-local-reassign.deal`) |
| `jvm-async-function-value-local-reassign` | contains | `async function b: string` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value-local-reassign.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value-local-reassign.deal`) |
| `jvm-async-function-value-local-reassign` | contains | `await : string` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-function-value-local-reassign.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-function-value-local-reassign.deal`) |
| `jvm-async-multi-module` | contains | `await : string` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-async-slice.json :: jvm-async-multi-module (full-dump equality) |
| `jvm-async-multi-module` | contains | `await : int` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-async-slice.json :: jvm-async-multi-module (full-dump equality) |
| `jvm-async-lua-ref-reassigned-adapter` | contains | `async function one: int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-lua-ref-reassigned-adapter.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-lua-ref-reassigned-adapter.deal`) |
| `jvm-async-lua-ref-reassigned-adapter` | contains | `await : int` | `golden: test/ir-goldens/jvm-async-slice-jvm-async-lua-ref-reassigned-adapter.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-async-slice-jvm-async-lua-ref-reassigned-adapter.deal`) |

File totals: 39 `irContains`, 0 `irNotContains`, 39 pins — all retained (0 dropped).

## jvm-classes-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-class-construction-defaults` | contains | `class Point` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-defaults.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-defaults.deal`) |
| `jvm-class-construction-defaults` | contains | `field x` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-defaults.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-defaults.deal`) |
| `jvm-class-construction-defaults` | contains | `field y` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-defaults.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-defaults.deal`) |
| `jvm-class-construction-defaults` | contains | `[boundary: class-construct]` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-defaults.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-defaults.deal`) |
| `jvm-class-construction-provided-fields` | contains | `class Point` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-provided-fields.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-provided-fields.deal`) |
| `jvm-class-construction-provided-fields` | contains | `[boundary: class-construct]` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-provided-fields.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-provided-fields.deal`) |
| `jvm-class-field-read-write` | contains | `class Point` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-field-read-write.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-field-read-write.deal`) |
| `jvm-class-field-read-write` | contains | `field x` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-field-read-write.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-field-read-write.deal`) |
| `jvm-class-primitive-field-types` | contains | `class Mixed` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-primitive-field-types.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-primitive-field-types.deal`) |
| `jvm-class-fresh-instances-per-construction` | contains | `class Bag` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-fresh-instances-per-construction.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-fresh-instances-per-construction.deal`) |
| `jvm-class-construction-evaluation-order` | contains | `class Pair` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-construction-evaluation-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-construction-evaluation-order.deal`) |
| `jvm-class-nominal-check-success` | contains | `class Box` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-nominal-check-success.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-nominal-check-success.deal`) |
| `jvm-class-nominal-check-success` | contains | `/Box` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-nominal-check-success.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-nominal-check-success.deal`) |
| `jvm-class-nominal-check-failure-table` | contains | `class Box` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-nominal-check-failure-table.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-nominal-check-failure-table.deal`) |
| `jvm-class-nominal-check-failure-null` | contains | `class Box` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-nominal-check-failure-null.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-nominal-check-failure-null.deal`) |
| `jvm-class-same-shape-nominal-failure` | contains | `class A` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-same-shape-nominal-failure.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-same-shape-nominal-failure.deal`) |
| `jvm-class-same-shape-nominal-failure` | contains | `class B` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-same-shape-nominal-failure.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-same-shape-nominal-failure.deal`) |
| `jvm-class-bool-construction-value-boundary` | contains | `class P` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-bool-construction-value-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-bool-construction-value-boundary.deal`) |
| `jvm-class-bool-construction-value-boundary` | contains | `[boundary: class-construct]` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-bool-construction-value-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-bool-construction-value-boundary.deal`) |
| `jvm-class-bool-construction-value-in-bounds` | contains | `class P` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-bool-construction-value-in-bounds.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-bool-construction-value-in-bounds.deal`) |
| `jvm-class-bool-construction-value-in-bounds` | contains | `[boundary: class-construct]` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-bool-construction-value-in-bounds.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-bool-construction-value-in-bounds.deal`) |
| `jvm-class-default-expression-arithmetic` | contains | `class Point` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-default-expression-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-default-expression-arithmetic.deal`) |
| `jvm-class-default-expression-arithmetic` | contains | `[boundary: class-construct]` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-default-expression-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-default-expression-arithmetic.deal`) |
| `jvm-class-default-null-typed-call` | contains | `class P` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-default-null-typed-call.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-default-null-typed-call.deal`) |
| `jvm-class-default-null-typed-call` | contains | `[boundary: class-construct]` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-default-null-typed-call.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-default-null-typed-call.deal`) |
| `jvm-class-field-write-eval-order-parity` | contains | `class Box` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-field-write-eval-order-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-field-write-eval-order-parity.deal`) |
| `jvm-class-field-write-eval-order-parity` | contains | `field x` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-field-write-eval-order-parity.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-field-write-eval-order-parity.deal`) |
| `jvm-class-field-write-error-precedence` | contains | `class P` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-field-write-error-precedence.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-field-write-error-precedence.deal`) |
| `jvm-class-field-write-error-precedence` | contains | `field b` | `golden: test/ir-goldens/jvm-classes-slice-jvm-class-field-write-error-precedence.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-classes-slice-jvm-class-field-write-error-precedence.deal`) |

File totals: 29 `irContains`, 0 `irNotContains`, 29 pins — all retained (0 dropped).

## jvm-function-values-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-fv-typed-variable-indirect-call` | contains | `(int,int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-typed-variable-indirect-call.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-typed-variable-indirect-call.deal`) |
| `jvm-fv-typed-variable-indirect-call` | contains | `ident add : (int,int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-typed-variable-indirect-call.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-typed-variable-indirect-call.deal`) |
| `jvm-fv-inferred-variable` | contains | `ident dbl : (int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-inferred-variable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-inferred-variable.deal`) |
| `jvm-fv-callback-param` | contains | `function apply: int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-callback-param.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-callback-param.deal`) |
| `jvm-fv-callback-param` | contains | `ident inc : (int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-callback-param.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-callback-param.deal`) |
| `jvm-fv-callback-mixed-signatures` | contains | `(boolean,string,string)->string` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-callback-mixed-signatures.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-callback-mixed-signatures.deal`) |
| `jvm-fv-return-function-value` | contains | `function picker: (int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-return-function-value.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-return-function-value.deal`) |
| `jvm-fv-arity-extension-assign` | contains | `(int,int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-arity-extension-assign.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-arity-extension-assign.deal`) |
| `jvm-fv-intrinsic-value-int` | contains | `(number)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-intrinsic-value-int.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-intrinsic-value-int.deal`) |
| `jvm-fv-intrinsic-value-int` | contains | `ident int : (number)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-intrinsic-value-int.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-intrinsic-value-int.deal`) |
| `jvm-fv-intrinsic-value-number` | contains | `(int)->number` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-intrinsic-value-number.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-intrinsic-value-number.deal`) |
| `jvm-fv-intrinsic-value-number` | contains | `ident number : (int)->number` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-intrinsic-value-number.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-intrinsic-value-number.deal`) |
| `jvm-fv-null-signatures` | contains | `()->null` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-null-signatures.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-null-signatures.deal`) |
| `jvm-fv-null-signatures` | contains | `(null)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-null-signatures.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-null-signatures.deal`) |
| `jvm-fv-invoke-name-collision` | contains | `(int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-invoke-name-collision.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-invoke-name-collision.deal`) |
| `jvm-fv-invoke-name-collision` | contains | `(int,int)->int` | `golden: test/ir-goldens/jvm-function-values-slice-jvm-fv-invoke-name-collision.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-function-values-slice-jvm-fv-invoke-name-collision.deal`) |

File totals: 16 `irContains`, 0 `irNotContains`, 16 pins — all retained (0 dropped).

## jvm-functions-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-fn-direct-call` | contains | `function greet: string` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-direct-call.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-direct-call.deal`) |
| `jvm-fn-direct-call` | contains | `call : string` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-direct-call.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-direct-call.deal`) |
| `jvm-fn-multiple-params` | contains | `function classify: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-multiple-params.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-multiple-params.deal`) |
| `jvm-fn-multiple-params` | contains | `function pick: string` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-multiple-params.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-multiple-params.deal`) |
| `jvm-fn-return-values` | contains | `function double: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-return-values.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-return-values.deal`) |
| `jvm-fn-return-values` | contains | `function add: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-return-values.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-return-values.deal`) |
| `jvm-fn-nested-calls` | contains | `function inc: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-nested-calls.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-nested-calls.deal`) |
| `jvm-fn-nested-calls` | contains | `call : int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-nested-calls.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-nested-calls.deal`) |
| `jvm-fn-self-recursion-factorial` | contains | `function fact: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-self-recursion-factorial.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-self-recursion-factorial.deal`) |
| `jvm-fn-self-recursion-factorial` | contains | `call : int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-self-recursion-factorial.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-self-recursion-factorial.deal`) |
| `jvm-fn-self-recursion-fibonacci` | contains | `function fib: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-self-recursion-fibonacci.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-self-recursion-fibonacci.deal`) |
| `jvm-fn-void-recursion` | contains | `function walk: null` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-void-recursion.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-void-recursion.deal`) |
| `jvm-fn-arg-eval-order` | contains | `function mark: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-arg-eval-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-arg-eval-order.deal`) |
| `jvm-fn-arg-eval-order` | contains | `function pair: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-arg-eval-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-arg-eval-order.deal`) |
| `jvm-fn-arg-eval-order-hoisted` | contains | `function take: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-arg-eval-order-hoisted.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-arg-eval-order-hoisted.deal`) |
| `jvm-fn-param-shadow-let-block-nofield` | contains | `function f: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-param-shadow-let-block-nofield.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-param-shadow-let-block-nofield.deal`) |
| `jvm-fn-param-shadow-let-block-nofield` | contains | `let g: int` | `golden: test/ir-goldens/jvm-functions-slice-jvm-fn-param-shadow-let-block-nofield.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-functions-slice-jvm-fn-param-shadow-let-block-nofield.deal`) |

File totals: 17 `irContains`, 0 `irNotContains`, 17 pins — all retained (0 dropped).

## jvm-host-abi-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-host-export-presence` | contains | `import * as log from "host/log"` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-host-abi-slice.json :: jvm-host-export-presence (full-dump equality) |

File totals: 1 `irContains`, 0 `irNotContains`, 1 pins — all retained (0 dropped).

## jvm-integration-join.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-join-nullable-array-param-return` | contains | `param xs: ?[int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-array-param-return.deal`) |
| `jvm-join-nullable-array-param-return` | contains | `let got: ?int` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-array-param-return.deal`) |
| `jvm-join-nullable-array-param-return` | contains | `let none: ?int` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-array-param-return.deal`) |
| `jvm-join-nullable-elements-array-desc` | contains | `param xs: [?int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-elements-array-desc.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-elements-array-desc.deal`) |
| `jvm-join-nullable-elements-array-desc` | contains | `let xs: [?int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-elements-array-desc.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-elements-array-desc.deal`) |
| `jvm-join-nullable-elements-array-desc` | contains | `let e: ?int` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-elements-array-desc.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-elements-array-desc.deal`) |
| `jvm-join-nullable-class-param-return` | contains | `param u: ?User` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-class-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-class-param-return.deal`) |
| `jvm-join-nullable-class-param-return` | contains | `class User` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-class-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-class-param-return.deal`) |
| `jvm-join-fn-signature-arrays-classes-nullable` | contains | `param a: [int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.deal`) |
| `jvm-join-fn-signature-arrays-classes-nullable` | contains | `param p: ?Pair` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.deal`) |
| `jvm-join-fn-signature-arrays-classes-nullable` | contains | `param xs: ?[string]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.deal`) |
| `jvm-join-fn-signature-arrays-classes-nullable` | contains | `let out: ?int` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-fn-signature-arrays-classes-nullable.deal`) |
| `jvm-join-class-array-param-return` | contains | `param ps: [Pt]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-class-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-class-array-param-return.deal`) |
| `jvm-join-class-array-param-return` | contains | `let ps: [@fixture-jvm-join-class-array-param-return.deal/Pt]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-class-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-class-array-param-return.deal`) |
| `jvm-join-class-array-param-return` | contains | `let p: @fixture-jvm-join-class-array-param-return.deal/Pt` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-class-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-class-array-param-return.deal`) |
| `jvm-join-nullable-class-array-desc` | contains | `param ps: [?Pt]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-class-array-desc.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-class-array-desc.deal`) |
| `jvm-join-nullable-class-array-param-return` | contains | `param ps: ?[Pt]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-class-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-class-array-param-return.deal`) |
| `jvm-join-nullable-class-array-param-return` | contains | `let h: ?@fixture-jvm-join-nullable-class-array-param-return.deal/Pt` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-class-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-class-array-param-return.deal`) |
| `jvm-join-nullable-class-array-param-return` | contains | `let none: ?@fixture-jvm-join-nullable-class-array-param-return.deal/Pt` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-class-array-param-return.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-class-array-param-return.deal`) |
| `jvm-join-nullable-of-nullable-elements` | contains | `param xs: ?[?int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-of-nullable-elements.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-of-nullable-elements.deal`) |
| `jvm-join-nullable-of-nullable-elements` | contains | `let arr: [?int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nullable-of-nullable-elements.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nullable-of-nullable-elements.deal`) |
| `jvm-join-mismatch-nullable-int` | contains | `let n: ?int` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-mismatch-nullable-int.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-mismatch-nullable-int.deal`) |
| `jvm-join-mismatch-nullable-class` | contains | `let b: ?@fixture-jvm-join-mismatch-nullable-class.deal/B` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-mismatch-nullable-class.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-mismatch-nullable-class.deal`) |
| `jvm-join-mismatch-array-wrapper` | contains | `let xs: [int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-mismatch-array-wrapper.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-mismatch-array-wrapper.deal`) |
| `jvm-join-mismatch-nullable-array-gate` | contains | `let xs: ?[int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-mismatch-nullable-array-gate.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-mismatch-nullable-array-gate.deal`) |
| `jvm-join-table-desc` | contains | `let inner: table` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-table-desc.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-table-desc.deal`) |
| `jvm-join-stdlib-nullable-boundary` | contains | `import * as str from "std/string"` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-stdlib-nullable-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-stdlib-nullable-boundary.deal`) |
| `jvm-join-xmod-class-descriptor` | contains | `param p: @lib/Point` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-integration-join.json :: jvm-join-xmod-class-descriptor (full-dump equality) |
| `jvm-join-xmod-class-descriptor` | contains | `import * as lib from "./lib"` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-integration-join.json :: jvm-join-xmod-class-descriptor (full-dump equality) |
| `jvm-join-xmod-table-read-desc` | contains | `let i: @lib/Item` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-integration-join.json :: jvm-join-xmod-table-read-desc (full-dump equality) |
| `jvm-join-xmod-mismatch-desc` | contains | `let b: @modelb/Item` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-integration-join.json :: jvm-join-xmod-mismatch-desc (full-dump equality) |
| `jvm-join-nested-array-descriptor-ir` | contains | `param a: [[int]]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nested-array-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nested-array-descriptor-ir.deal`) |
| `jvm-join-nested-array-descriptor-ir` | not-contains | `int[][]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-nested-array-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-nested-array-descriptor-ir.deal`) |
| `jvm-join-async-completion-descriptor-ir` | contains | `async function fetch` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-async-completion-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-async-completion-descriptor-ir.deal`) |
| `jvm-join-async-completion-descriptor-ir` | contains | `async(int)->string` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-async-completion-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-async-completion-descriptor-ir.deal`) |
| `jvm-join-async-completion-descriptor-ir` | contains | `[boundary: async-completion]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-async-completion-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-async-completion-descriptor-ir.deal`) |
| `jvm-join-function-type-descriptor-ir` | contains | `param a: [int]` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-function-type-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-function-type-descriptor-ir.deal`) |
| `jvm-join-function-type-descriptor-ir` | contains | `param u: ?User` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-function-type-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-function-type-descriptor-ir.deal`) |
| `jvm-join-function-type-descriptor-ir` | contains | `let f: ([int],?@fixture-jvm-join-function-type-descriptor-ir.deal/User)->?@fixture-jvm-join-function-type-descriptor-ir.deal/User` | `golden: test/ir-goldens/jvm-integration-join-jvm-join-function-type-descriptor-ir.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-integration-join-jvm-join-function-type-descriptor-ir.deal`) |

File totals: 38 `irContains`, 1 `irNotContains`, 39 pins — all retained (0 dropped).

## jvm-jsonable-slice.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## jvm-modules-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-mod-imported-direct-call` | contains | `import * as lib from "./lib"` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-modules-slice.json :: jvm-mod-imported-direct-call (full-dump equality) |

File totals: 1 `irContains`, 0 `irNotContains`, 1 pins — all retained (0 dropped).

## jvm-nullable-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-nullable-int-locals-flow` | contains | `function test: int` | `golden: test/ir-goldens/jvm-nullable-slice-jvm-nullable-int-locals-flow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-nullable-slice-jvm-nullable-int-locals-flow.deal`) |
| `jvm-nullable-class-flow` | contains | `class Box` | `golden: test/ir-goldens/jvm-nullable-slice-jvm-nullable-class-flow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-nullable-slice-jvm-nullable-class-flow.deal`) |
| `jvm-nullable-fields` | contains | `class Holder` | `golden: test/ir-goldens/jvm-nullable-slice-jvm-nullable-fields.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-nullable-slice-jvm-nullable-fields.deal`) |
| `jvm-nullable-fields` | contains | `field i` | `golden: test/ir-goldens/jvm-nullable-slice-jvm-nullable-fields.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-nullable-slice-jvm-nullable-fields.deal`) |
| `jvm-nullable-class-arrays` | contains | `class Point` | `golden: test/ir-goldens/jvm-nullable-slice-jvm-nullable-class-arrays.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-nullable-slice-jvm-nullable-class-arrays.deal`) |
| `jvm-nullable-class-boundary-success` | contains | `class Item` | `golden: test/ir-goldens/jvm-nullable-slice-jvm-nullable-class-boundary-success.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-nullable-slice-jvm-nullable-class-boundary-success.deal`) |

File totals: 6 `irContains`, 0 `irNotContains`, 6 pins — all retained (0 dropped).

## jvm-semantic-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-slice-int-arithmetic` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-int-arithmetic.deal`) |
| `jvm-slice-int-arithmetic` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-int-arithmetic.deal`) |
| `jvm-slice-int-arithmetic` | contains | `binary * : int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-int-arithmetic.deal`) |
| `jvm-slice-int-arithmetic` | contains | `binary ** : int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-int-arithmetic.deal`) |
| `jvm-slice-number-arithmetic` | contains | `function test: number` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-number-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-number-arithmetic.deal`) |
| `jvm-slice-number-arithmetic` | contains | `binary * : number` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-number-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-number-arithmetic.deal`) |
| `jvm-slice-number-arithmetic` | contains | `binary % : number` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-number-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-number-arithmetic.deal`) |
| `jvm-slice-string-concat` | contains | `function test: string` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-string-concat.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-string-concat.deal`) |
| `jvm-slice-string-concat` | contains | `binary + : string` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-string-concat.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-string-concat.deal`) |
| `jvm-slice-template-basic` | contains | `function test: string` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-template-basic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-template-basic.deal`) |
| `jvm-slice-template-basic` | contains | `template-literal : string @fixture-jvm-slice-template-basic.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-template-basic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-template-basic.deal`) |
| `jvm-slice-template-plain-and-parts` | contains | `function test: string` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-template-plain-and-parts.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-template-plain-and-parts.deal`) |
| `jvm-slice-template-plain-and-parts` | contains | `template-literal : string @fixture-jvm-slice-template-plain-and-parts.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-template-plain-and-parts.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-template-plain-and-parts.deal`) |
| `jvm-slice-boolean-comparisons` | contains | `function test: boolean` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-boolean-comparisons.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-boolean-comparisons.deal`) |
| `jvm-slice-boolean-comparisons` | contains | `binary < : boolean` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-boolean-comparisons.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-boolean-comparisons.deal`) |
| `jvm-slice-boolean-comparisons` | contains | `binary && : boolean` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-boolean-comparisons.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-boolean-comparisons.deal`) |
| `jvm-slice-local-mutation` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-local-mutation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-local-mutation.deal`) |
| `jvm-slice-local-mutation` | contains | `let x: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-local-mutation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-local-mutation.deal`) |
| `jvm-slice-local-mutation` | contains | `assignment` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-local-mutation.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-local-mutation.deal`) |
| `jvm-slice-if-else` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-if-else.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-if-else.deal`) |
| `jvm-slice-if-else` | contains | `if @fixture-jvm-slice-if-else.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-if-else.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-if-else.deal`) |
| `jvm-slice-while-sum` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-sum.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-sum.deal`) |
| `jvm-slice-while-sum` | contains | `while @fixture-jvm-slice-while-sum.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-sum.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-sum.deal`) |
| `jvm-slice-while-sum` | contains | `binary < : boolean` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-sum.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-sum.deal`) |
| `jvm-slice-while-nested-shadow` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-nested-shadow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-nested-shadow.deal`) |
| `jvm-slice-while-nested-shadow` | contains | `while @fixture-jvm-slice-while-nested-shadow.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-nested-shadow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-nested-shadow.deal`) |
| `jvm-slice-while-false-skipped` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-false-skipped.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-false-skipped.deal`) |
| `jvm-slice-while-false-skipped` | contains | `while @fixture-jvm-slice-while-false-skipped.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-false-skipped.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-false-skipped.deal`) |
| `jvm-slice-while-false-skipped` | contains | `literal false : boolean` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-false-skipped.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-false-skipped.deal`) |
| `jvm-slice-while-hoisted-condition` | contains | `function test: int` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-hoisted-condition.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-hoisted-condition.deal`) |
| `jvm-slice-while-hoisted-condition` | contains | `while @fixture-jvm-slice-while-hoisted-condition.deal` | `golden: test/ir-goldens/jvm-semantic-slice-jvm-slice-while-hoisted-condition.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-semantic-slice-jvm-slice-while-hoisted-condition.deal`) |

File totals: 31 `irContains`, 0 `irNotContains`, 31 pins — all retained (0 dropped).

## jvm-skeleton.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-literals-output` | contains | `function test: string` | `golden: test/ir-goldens/jvm-skeleton-jvm-literals-output.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-literals-output.deal`) |
| `jvm-literals-output` | contains | `literal 42 : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-literals-output.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-literals-output.deal`) |
| `jvm-literals-output` | contains | `literal 3.5 : number` | `golden: test/ir-goldens/jvm-skeleton-jvm-literals-output.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-literals-output.deal`) |
| `jvm-int-arithmetic` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-arithmetic.deal`) |
| `jvm-int-arithmetic` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-arithmetic.deal`) |
| `jvm-int-arithmetic` | contains | `binary / : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-arithmetic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-arithmetic.deal`) |
| `jvm-local-variable` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-local-variable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-local-variable.deal`) |
| `jvm-local-variable` | contains | `let x: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-local-variable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-local-variable.deal`) |
| `jvm-local-variable` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-local-variable.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-local-variable.deal`) |
| `jvm-if-else` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-if-else.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-if-else.deal`) |
| `jvm-if-else` | contains | `if @fixture-jvm-if-else.deal` | `golden: test/ir-goldens/jvm-skeleton-jvm-if-else.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-if-else.deal`) |
| `jvm-if-else` | contains | `binary < : boolean` | `golden: test/ir-goldens/jvm-skeleton-jvm-if-else.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-if-else.deal`) |
| `jvm-null-return-log` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-return-log.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-return-log.deal`) |
| `jvm-null-return-log` | contains | `call : null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-return-log.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-return-log.deal`) |
| `jvm-null-return-helper` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-return-helper.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-return-helper.deal`) |
| `jvm-null-return-helper` | contains | `function helper: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-return-helper.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-return-helper.deal`) |
| `jvm-null-var-init` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-var-init.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-var-init.deal`) |
| `jvm-null-var-init` | contains | `let z: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-var-init.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-var-init.deal`) |
| `jvm-shadowed-initializer` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-shadowed-initializer.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-shadowed-initializer.deal`) |
| `jvm-shadowed-initializer` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-shadowed-initializer.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-shadowed-initializer.deal`) |
| `jvm-null-capture-reassigned` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-capture-reassigned.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-capture-reassigned.deal`) |
| `jvm-null-capture-reassigned` | contains | `call : null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-capture-reassigned.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-capture-reassigned.deal`) |
| `jvm-null-capture-parameter` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-capture-parameter.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-capture-parameter.deal`) |
| `jvm-null-capture-parameter` | contains | `function helper: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-capture-parameter.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-capture-parameter.deal`) |
| `jvm-null-arg-capture` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-arg-capture.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-arg-capture.deal`) |
| `jvm-null-arg-capture` | contains | `call : null` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-arg-capture.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-arg-capture.deal`) |
| `jvm-nummod-negative` | contains | `function test: number` | `golden: test/ir-goldens/jvm-skeleton-jvm-nummod-negative.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-nummod-negative.deal`) |
| `jvm-nummod-negative` | contains | `binary % : number` | `golden: test/ir-goldens/jvm-skeleton-jvm-nummod-negative.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-nummod-negative.deal`) |
| `jvm-string-eq-order` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-string-eq-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-string-eq-order.deal`) |
| `jvm-string-eq-order` | contains | `binary === : boolean` | `golden: test/ir-goldens/jvm-skeleton-jvm-string-eq-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-string-eq-order.deal`) |
| `jvm-console-error` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-console-error.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-console-error.deal`) |
| `jvm-console-error` | contains | `call : null` | `golden: test/ir-goldens/jvm-skeleton-jvm-console-error.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-console-error.deal`) |
| `jvm-null-equality` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-equality.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-equality.deal`) |
| `jvm-null-equality` | contains | `binary === : boolean` | `golden: test/ir-goldens/jvm-skeleton-jvm-null-equality.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-null-equality.deal`) |
| `jvm-standalone-expression` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-standalone-expression.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-standalone-expression.deal`) |
| `jvm-standalone-expression` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-standalone-expression.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-standalone-expression.deal`) |
| `jvm-standalone-expression-overflow` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-standalone-expression-overflow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-standalone-expression-overflow.deal`) |
| `jvm-standalone-expression-overflow` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-standalone-expression-overflow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-standalone-expression-overflow.deal`) |
| `jvm-nonfinite-literal` | contains | `function test: number` | `golden: test/ir-goldens/jvm-skeleton-jvm-nonfinite-literal.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-nonfinite-literal.deal`) |
| `jvm-nonfinite-literal` | contains | `literal Infinity : number` | `golden: test/ir-goldens/jvm-skeleton-jvm-nonfinite-literal.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-nonfinite-literal.deal`) |
| `jvm-nonfinite-negated` | contains | `function test: number` | `golden: test/ir-goldens/jvm-skeleton-jvm-nonfinite-negated.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-nonfinite-negated.deal`) |
| `jvm-shortcircuit-runs` | contains | `function test: boolean` | `golden: test/ir-goldens/jvm-skeleton-jvm-shortcircuit-runs.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-shortcircuit-runs.deal`) |
| `jvm-shortcircuit-runs` | contains | `binary && : boolean` | `golden: test/ir-goldens/jvm-skeleton-jvm-shortcircuit-runs.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-shortcircuit-runs.deal`) |
| `jvm-string-scalar-order` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-string-scalar-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-string-scalar-order.deal`) |
| `jvm-string-scalar-order` | contains | `binary < : boolean` | `golden: test/ir-goldens/jvm-skeleton-jvm-string-scalar-order.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-string-scalar-order.deal`) |
| `jvm-dead-code-function` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-dead-code-function.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-dead-code-function.deal`) |
| `jvm-dead-code-function` | contains | `if @fixture-jvm-dead-code-function.deal` | `golden: test/ir-goldens/jvm-skeleton-jvm-dead-code-function.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-dead-code-function.deal`) |
| `jvm-dead-code-block` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-dead-code-block.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-dead-code-block.deal`) |
| `jvm-dead-code-elseif-hoisted` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-dead-code-elseif-hoisted.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-dead-code-elseif-hoisted.deal`) |
| `jvm-dead-code-elseif-hoisted` | contains | `if @fixture-jvm-dead-code-elseif-hoisted.deal` | `golden: test/ir-goldens/jvm-skeleton-jvm-dead-code-elseif-hoisted.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-dead-code-elseif-hoisted.deal`) |
| `jvm-int-safe-range-boundary` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-boundary.deal`) |
| `jvm-int-safe-range-boundary` | contains | `literal 9007199254740991 : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-boundary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-boundary.deal`) |
| `jvm-int-safe-range-overflow` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-overflow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-overflow.deal`) |
| `jvm-int-safe-range-overflow` | contains | `binary + : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-overflow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-overflow.deal`) |
| `jvm-int-safe-range-sub` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-sub.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-sub.deal`) |
| `jvm-int-safe-range-sub` | contains | `binary - : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-sub.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-sub.deal`) |
| `jvm-int-safe-range-pow` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-pow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-pow.deal`) |
| `jvm-int-safe-range-pow` | contains | `binary ** : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-pow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-pow.deal`) |
| `jvm-int-safe-range-negpow` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-negpow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-negpow.deal`) |
| `jvm-int-safe-range-negpow` | contains | `binary ** : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-negpow.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-negpow.deal`) |
| `jvm-int-safe-range-intrinsic` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-intrinsic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-intrinsic.deal`) |
| `jvm-int-safe-range-intrinsic` | contains | `literal 9.007199254740992E15 : number` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-intrinsic.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-intrinsic.deal`) |
| `jvm-int-safe-range-literal` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-literal.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-literal.deal`) |
| `jvm-int-safe-range-literal` | contains | `literal 9223372036854775807 : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-literal.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-literal.deal`) |
| `jvm-int-safe-range-max` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-max.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-max.deal`) |
| `jvm-int-safe-range-max` | contains | `literal 9007199254740991 : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-max.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-max.deal`) |
| `jvm-int-safe-range-mod` | contains | `function test: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-mod.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-mod.deal`) |
| `jvm-int-safe-range-mod` | contains | `binary % : int` | `golden: test/ir-goldens/jvm-skeleton-jvm-int-safe-range-mod.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-int-safe-range-mod.deal`) |
| `jvm-eval-order-call-args` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-call-args.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-call-args.deal`) |
| `jvm-eval-order-call-args` | contains | `function f: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-call-args.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-call-args.deal`) |
| `jvm-eval-order-call-args` | contains | `function g: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-call-args.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-call-args.deal`) |
| `jvm-eval-order-binary` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-binary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-binary.deal`) |
| `jvm-eval-order-binary` | contains | `function f: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-binary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-binary.deal`) |
| `jvm-eval-order-binary` | contains | `function g: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-binary.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-binary.deal`) |
| `jvm-eval-order-shortcircuit` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-shortcircuit.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-shortcircuit.deal`) |
| `jvm-eval-order-shortcircuit` | contains | `function f: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-shortcircuit.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-shortcircuit.deal`) |
| `jvm-eval-order-shortcircuit` | contains | `function g: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-shortcircuit.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-shortcircuit.deal`) |
| `jvm-eval-order-nested` | contains | `function test: null` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-nested.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-nested.deal`) |
| `jvm-eval-order-nested` | contains | `function f: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-nested.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-nested.deal`) |
| `jvm-eval-order-nested` | contains | `function g: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-nested.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-nested.deal`) |
| `jvm-eval-order-nested` | contains | `function k: int` | `golden: test/ir-goldens/jvm-skeleton-jvm-eval-order-nested.ir.txt` (fixture: test/ir-goldens/fixtures/jvm-skeleton-jvm-eval-order-nested.deal`) |

File totals: 81 `irContains`, 0 `irNotContains`, 81 pins — all retained (0 dropped).

## jvm-stdlib-slice.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## jvm-v1.2-known-fail.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## jvm-v12-slice.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## jvm-xmod-classes-slice.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `jvm-xmod-class-export-import` | contains | `class Point` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-export-import (full-dump equality) |
| `jvm-xmod-class-export-import` | contains | `import * as lib from "./lib"` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-export-import (full-dump equality) |
| `jvm-xmod-class-construction-defaults` | contains | `class Point` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-construction-defaults (full-dump equality) |
| `jvm-xmod-class-construction-defaults` | contains | `[boundary: class-construct]` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-construction-defaults (full-dump equality) |
| `jvm-xmod-class-param-pass` | contains | `class Pair` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-param-pass (full-dump equality) |
| `jvm-xmod-class-param-pass` | contains | `import * as lib from "./lib"` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-param-pass (full-dump equality) |
| `jvm-xmod-class-return-mutate-roundtrip` | contains | `class Box` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-return-mutate-roundtrip (full-dump equality) |
| `jvm-xmod-same-name-isolation` | contains | `class Item` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-same-name-isolation (full-dump equality) |
| `jvm-xmod-class-param-return-local-fn` | contains | `class Point` | `JvmBackendTest.testIrDumpExactMigration` block: jvm-xmod-classes-slice.json :: jvm-xmod-class-param-return-local-fn (full-dump equality) |

File totals: 9 `irContains`, 0 `irNotContains`, 9 pins — all retained (0 dropped).

## luajit-std-json-unicode.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## module-semantics.json

This file carries the `irContains`/`irNotContains` keys with empty arrays only —
zero IR-containment pins to migrate.

## type-descriptors.json

| Case | Pin kind | Pin text | Destination |
|---|---|---|---|
| `primitive-null` | contains | `literal null : null` | `golden: test/ir-goldens/type-descriptors-primitive-null.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-primitive-null.deal`) |
| `primitive-boolean` | contains | `literal true : boolean` | `golden: test/ir-goldens/type-descriptors-primitive-boolean.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-primitive-boolean.deal`) |
| `primitive-int` | contains | `literal 42 : int` | `golden: test/ir-goldens/type-descriptors-primitive-int.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-primitive-int.deal`) |
| `primitive-number` | contains | `literal 3.14 : number` | `golden: test/ir-goldens/type-descriptors-primitive-number.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-primitive-number.deal`) |
| `primitive-string` | contains | `string` | `golden: test/ir-goldens/type-descriptors-primitive-string.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-primitive-string.deal`) |
| `primitive-table` | contains | `table` | `golden: test/ir-goldens/type-descriptors-primitive-table.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-primitive-table.deal`) |
| `array-int-spec-format` | contains | `param a: [int]` | `golden: test/ir-goldens/type-descriptors-array-int-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-array-int-spec-format.deal`) |
| `array-int-spec-format` | not-contains | `int[]` | `golden: test/ir-goldens/type-descriptors-array-int-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-array-int-spec-format.deal`) |
| `array-string-spec-format` | contains | `param a: [string]` | `golden: test/ir-goldens/type-descriptors-array-string-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-array-string-spec-format.deal`) |
| `array-string-spec-format` | not-contains | `string[]` | `golden: test/ir-goldens/type-descriptors-array-string-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-array-string-spec-format.deal`) |
| `nested-array-spec-format` | contains | `param a: [[int]]` | `golden: test/ir-goldens/type-descriptors-nested-array-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nested-array-spec-format.deal`) |
| `nested-array-spec-format` | not-contains | `int[][]` | `golden: test/ir-goldens/type-descriptors-nested-array-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nested-array-spec-format.deal`) |
| `nullable-int-spec-format` | contains | `param n: ?int` | `golden: test/ir-goldens/type-descriptors-nullable-int-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-int-spec-format.deal`) |
| `nullable-int-spec-format` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-nullable-int-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-int-spec-format.deal`) |
| `nullable-string-spec-format` | contains | `param n: ?string` | `golden: test/ir-goldens/type-descriptors-nullable-string-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-string-spec-format.deal`) |
| `nullable-string-spec-format` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-nullable-string-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-string-spec-format.deal`) |
| `class-descriptor-spec-format` | contains | `/User` | `golden: test/ir-goldens/type-descriptors-class-descriptor-spec-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-class-descriptor-spec-format.deal`) |
| `nullable-class-descriptor` | contains | `param u: ?User` | `golden: test/ir-goldens/type-descriptors-nullable-class-descriptor.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-class-descriptor.deal`) |
| `nullable-class-descriptor` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-nullable-class-descriptor.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-class-descriptor.deal`) |
| `function-type-array-param` | contains | `param xs: [int]` | `golden: test/ir-goldens/type-descriptors-function-type-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-array-param.deal`) |
| `function-type-array-param` | not-contains | `...` | `golden: test/ir-goldens/type-descriptors-function-type-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-array-param.deal`) |
| `fixed-params-mixed` | contains | `param values: [int]` | `golden: test/ir-goldens/type-descriptors-fixed-params-mixed.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-fixed-params-mixed.deal`) |
| `fixed-params-mixed` | contains | `param label: string` | `golden: test/ir-goldens/type-descriptors-fixed-params-mixed.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-fixed-params-mixed.deal`) |
| `fixed-params-mixed` | not-contains | `...` | `golden: test/ir-goldens/type-descriptors-fixed-params-mixed.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-fixed-params-mixed.deal`) |
| `nullable-array-param` | contains | `param a: ?[int]` | `golden: test/ir-goldens/type-descriptors-nullable-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-array-param.deal`) |
| `nullable-array-param` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-nullable-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-nullable-array-param.deal`) |
| `array-of-nullable-int` | contains | `param a: [?int]` | `golden: test/ir-goldens/type-descriptors-array-of-nullable-int.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-array-of-nullable-int.deal`) |
| `array-of-nullable-int` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-array-of-nullable-int.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-array-of-nullable-int.deal`) |
| `no-legacy-format` | contains | `[int]` | `golden: test/ir-goldens/type-descriptors-no-legacy-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-no-legacy-format.deal`) |
| `no-legacy-format` | contains | `?int` | `golden: test/ir-goldens/type-descriptors-no-legacy-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-no-legacy-format.deal`) |
| `no-legacy-format` | contains | `?string` | `golden: test/ir-goldens/type-descriptors-no-legacy-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-no-legacy-format.deal`) |
| `no-legacy-format` | contains | `[string]` | `golden: test/ir-goldens/type-descriptors-no-legacy-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-no-legacy-format.deal`) |
| `no-legacy-format` | not-contains | `int[]` | `golden: test/ir-goldens/type-descriptors-no-legacy-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-no-legacy-format.deal`) |
| `no-legacy-format` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-no-legacy-format.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-no-legacy-format.deal`) |
| `function-type-descriptor-multi-arg` | contains | `(int,int)->int` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-multi-arg.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-multi-arg.deal`) |
| `function-type-descriptor-null-return` | contains | `()->null` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-null-return.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-null-return.deal`) |
| `function-type-descriptor-nullable-return` | contains | `(int)->?int` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-nullable-return.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-nullable-return.deal`) |
| `function-type-descriptor-nullable-return` | not-contains | `\|null` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-nullable-return.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-nullable-return.deal`) |
| `function-type-descriptor-mixed-params` | contains | `(int,string)->boolean` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-mixed-params.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-mixed-params.deal`) |
| `function-type-descriptor-array-param` | contains | `([int])->int` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-array-param.deal`) |
| `function-type-descriptor-array-param` | not-contains | `int[]` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-array-param.deal`) |
| `function-type-descriptor-fixed-array-param` | contains | `(string,[int])->null` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-fixed-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-fixed-array-param.deal`) |
| `function-type-descriptor-fixed-array-param` | not-contains | `...` | `golden: test/ir-goldens/type-descriptors-function-type-descriptor-fixed-array-param.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-function-type-descriptor-fixed-array-param.deal`) |
| `async-function-descriptor` | contains | `async(int)->string` | `golden: test/ir-goldens/type-descriptors-async-function-descriptor.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-async-function-descriptor.deal`) |
| `async-function-descriptor` | contains | `async function fetch: string` | `golden: test/ir-goldens/type-descriptors-async-function-descriptor.ir.txt` (fixture: test/ir-goldens/fixtures/type-descriptors-async-function-descriptor.deal`) |

File totals: 30 `irContains`, 15 `irNotContains`, 45 pins — all retained (0 dropped).

## Grand totals

- Slice files carrying the `irContains`/`irNotContains` keys: 17 (all have a section above)
- Of those, files carrying non-empty pins: 14 (the remaining 3 carry empty arrays only)
- Cases carrying pins: 203
- Pins recorded: 380
- Dropped pins: 0

## Negative controls (verified during ISSUE-0358)

- Removing a migrated golden file (`jvm-skeleton-jvm-int-arithmetic.ir.txt`) in a
  scratch copy made `IrGoldenTest` fail with “missing golden file”.
- Perturbing one line of that golden (`binary + : int` → `binary - : int`) made
  `IrGoldenTest` fail with a diff at the perturbed line.
- Perturbing one line of a migrated IR-dump assertion in
  `JvmBackendTest.testIrDumpExactMigration` (`await : string` → `await : int`) made
  `JvmBackendTest` fail with “exact IR dump mismatch”.
