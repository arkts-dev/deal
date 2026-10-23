# DEAL Compiler v1.4

Compiler, semantic IR, runtimes and standard library for DEAL v1.4, targeting
JavaScript, LuaJIT and JVM. The sole language authority is
[`docs/spec-v1.4.md`](docs/spec-v1.4.md). Projects require
`"languageVersion": "1.4"`; the standard-library version is also `"1.4"`.

The language has binary64 `number` values and structural class declarations
with explicitly supplied required fields. Removed source version directives,
C-style loops, remainder, C FFI, bytes, integer/conversion intrinsics and class
field initializers are not compatibility features.

See [`skills/write-deal/SKILL.md`](skills/write-deal/SKILL.md) for authoring
help and [`docs/deal-v1.4-capabilities.deal`](docs/deal-v1.4-capabilities.deal)
for an executable example.

## Build and Test

```bash
./run_tests.sh --jobs 1
```

Compiles all production and remaining test sources with `javac --release 25`
and executes the complete selection in `tools/gate-manifest.sh` with
assertions enabled: 87 Java suites and five Lua/JS runtime records. Java
suites run sequentially; `--jobs` controls parallel work inside suites.
There is no alternate full-suite profile.

`run_tests.sh` first preflights its required tools (`javac`, `java`, `luajit`,
`node`, `gcc`). A missing required tool fails the run with the named
`TOOL_MISSING` outcome before any build work, and the LuaJIT/Node records and
JS production suites have no skip path: a run that omits a supported lane is
a failure, never a pass.

## Distribution

Build the packaged compiler with its matched runtime, declarations, drivers, and
standard library:

```bash
./tools/build-release.sh
```

Verify a packaged distribution outside the checkout:

```bash
./tools/verify-release.sh [dist]
```

The verification copies private projects into a scratch directory, compiles and
executes the committed smoke project through the packaged launcher on every
supported backend (`luajit`, `jvm`, `js`), and rejects old versions, missing or
mismatched resources, broken deployment copies, and failing executions. It needs
`luajit`, Java 25 `javac`/`java`, and `node` on `PATH`; a missing tool or artifact
is a failure, not a skip.

## Coverage

Provide JaCoCo agent and CLI jars compatible with Java 25 (tested with
JaCoCo 0.8.14), then run:

```bash
JACOCO_DIR=/path/to/jacoco ./coverage.sh --jobs 1
```

Coverage executes the same test runner and instruments Java subprocesses
without Java startup-option environment variables. Production classes compile into `build/deal`; test classes compile separately
into `build/test-classes`. Only `build/deal` enters the report; test helpers
and generated probe classes are excluded. Lua/JS execution is not counted by JaCoCo.
Minimum production coverage is **80% lines** and **70% branches**.
Reports are written to `build/coverage.csv`, `build/coverage.xml`, and
`build/coverage-html/index.html`.
