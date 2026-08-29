# Backend Rejection Detail-Text Pin Migration Record (ISSUE-0359)

This record is part of the JSON slice corpus absorption
(`v12-three-backend-conformance-corpus` C1: one-way absorption with a
named destination per pin class). Backend-internal rejection detail
texts are never corpus fields (C6): the conformance corpus may pin the
exact diagnostic **code** of a backend rejection, but the **detail text**
of a backend-internal rejection belongs to the owning backend's unit
tests. This change moves every backend-internal rejection detail-text
pin out of the conformance path and into
`testUnsupportedConstructsRejected`-pattern assertions that pin the exact
current detail text.

No JSON file is deleted or modified by this migration: the corpus cases
below keep pinning only their rejection codes (`expectedCompileError:
"E6000"`), and the corpus keeps carrying no rejection detail text in any
fixture field.

## Migrated pins and destinations

| Corpus pin (JSON case) | Migrated detail text (exact, current emission) | Destination unit assertion |
|---|---|---|
| `test/conformance/fixtures/jvm-function-values-slice.json` — `jvm-fv-xmod-callback-arg-e6000` | `JVM backend (skeleton) does not support function values passed to an imported module call (the per-module wrapper classes cannot cross a module boundary — cross-module function values are deferred to ISSUE-0110) yet` | `test/JvmBackendTest.java` — `testUnsupportedConstructsRejected`, cross-module function-value rejection pins (`jvmtest-xmod-rejection.deal`) |
| `test/conformance/fixtures/jvm-function-values-slice.json` — `jvm-fv-xmod-arity-extension-arg-e6000` | same text as the callback pin (the same backend rejection arm) | `test/JvmBackendTest.java` — `testUnsupportedConstructsRejected`, cross-module function-value rejection pins |
| `test/conformance/fixtures/jvm-function-values-slice.json` — `jvm-fv-xmod-return-out-e6000` | `JVM backend (skeleton) does not support function values returned from an imported module call (the per-module wrapper classes cannot cross a module boundary — cross-module function values are deferred to ISSUE-0110) yet` | `test/JvmBackendTest.java` — `testUnsupportedConstructsRejected`, cross-module function-value rejection pins |
| `test/conformance/fixtures/jvm-function-values-slice.json` — `jvm-fv-xmod-callresult-callee-e6000` | same text as the return-out pin (the same backend rejection arm) | `test/JvmBackendTest.java` — `testUnsupportedConstructsRejected`, cross-module function-value rejection pins |
| `test/conformance/fixtures/jvm-jsonable-slice.json` — `jvm-jsonable-optional-table-rejected` | `JVM backend (skeleton) does not support optional table fields of @jsonable class 'Wrap' (the read of 'data' yields \`table \| null\`, which stays out of the slice's typed positions) yet` | `test/JvmBackendTest.java` — `testUnsupportedConstructsRejected`, @jsonable optional-table-field pin (`jvmtest-jsonable-optional-table.deal`) |
| JS adapter rejection gate (`test/BackendConformanceTest.java` `runJsAssertions`: E6000/E6003 codegen rejections fail the fixture; no JSON case carries a backend rejection detail text) | `JavaScript backend: @extern-c imports are not supported (FFI_UNSUPPORTED_BACKEND, ISSUE-0169 skeleton)` — code **E6003**, emitted at `deal/codegen/js/JsBackend.java:799` | `test/JsBackendTest.java` — `testUnsupportedConstructsRejected`, the @extern-c exact-text pin (`rej-extern`) |

## Sequencing notes

- The JS @extern-c pin holds the **current** emission (E6003). The
  backend epic ISSUE-0277 owns the E6003 → E6006 migration and updates
  this unit pin together with that change; the zero-skip flip (T14 of
  `v12-zero-skip-conformance-gate`) verifies the migration. This change
  does not modify the production emission and does not pin E6006.
- The JVM E6000 detail-text pins hold the current `JVM backend
  (skeleton) does not support … yet` text shape. Any production text
  drift in `deal/codegen/jvm/JvmBackend.java` trips the owning unit test
  (negative control verified in this change), while the corpus cases
  keep pinning only the codes.
