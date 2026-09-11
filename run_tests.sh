#!/bin/bash
set -e

DEFAULT_JOBS=1
JOBS="$DEFAULT_JOBS"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --jobs)
      JOBS="${2:--}"
      shift 2
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$JOBS" in
  ''|-|*[!0-9]*|0) echo "ERROR: --jobs requires a positive integer" >&2; exit 2 ;;
esac
echo "=== DEAL test parallelism: jobs=$JOBS ==="

mkdir -p build

# =========================================================================
# Strict mode (release-r0-r3-strict-gate-mechanics S2(c)/S2(d)): export
# the strict flag so every test JVM inherits it, and tee the complete
# output to the captured gate log build/strict-run-tests.log. The
# output assertion (tools/strict-output-assert.sh, S4) runs over the
# captured log after the background wait and before the final marker.
# Dev mode exports nothing, captures no log, and runs no assertion.
# =========================================================================
if [ -n "${DEAL_STRICT:-}" ]; then
  export DEAL_STRICT=1
  STRICT_LOG="build/strict-run-tests.log"
  STRICT_OUT_FIFO="$STRICT_LOG.stdout.fifo"
  STRICT_ERR_FIFO="$STRICT_LOG.stderr.fifo"
  rm -f "$STRICT_LOG" "$STRICT_OUT_FIFO" "$STRICT_ERR_FIFO"
  # fd 3/4 keep the original stdout/stderr for the post-capture restore.
  exec 3>&1 4>&2
  mkfifo "$STRICT_OUT_FIFO" "$STRICT_ERR_FIFO" \
    || { echo "ERROR: strict log capture fifo creation failed" >&2; exit 1; }
  # fd 5/6 are read-write fifo handles: the open never blocks, and
  # closing them (after the restore below) delivers EOF to the relays.
  exec 5<> "$STRICT_OUT_FIFO"
  exec 6<> "$STRICT_ERR_FIFO"
  # Two fifo/relay pairs keep the two live streams separate through the
  # capture: stdout content is appended to the merged captured log and
  # relayed to the original stdout (fd 3); stderr content is appended to
  # the same merged log and relayed to the original stderr (fd 4), so
  # error tokens such as TOOL_MISSING stay visible on stderr as the
  # strict-mode gate contract pins. Both relays append to the single
  # merged log -- the S4 assertion input -- one printf per line, so
  # concurrent relays never interleave inside a line and the assertion's
  # line surface stays intact.
  ( exec 5>&- 6>&-; while IFS= read -r line || [ -n "$line" ]; do
      printf '%s\n' "$line" >> "$STRICT_LOG"
      printf '%s\n' "$line" >&3
    done < "$STRICT_OUT_FIFO" ) &
  STRICT_RELAY_OUT_PID=$!
  ( exec 5>&- 6>&-; while IFS= read -r line || [ -n "$line" ]; do
      printf '%s\n' "$line" >> "$STRICT_LOG"
      printf '%s\n' "$line" >&4
    done < "$STRICT_ERR_FIFO" ) &
  STRICT_RELAY_ERR_PID=$!
  exec >&5 2>&6
fi

# Single compile/test-list authority: tools/gate-manifest.sh provides
# PROD_SOURCES + TEST_SOURCES (today's javac source list, verbatim) and
# TEST_MAINS (today's run phase, verbatim). See gate-manifest-authority.
source tools/gate-manifest.sh
# =========================================================================
# ISSUE-0541 (default planning epics): the planner-level battery —
# DefaultSemanticPlanner/DeclarationSemanticAnalyzer plans and the
# provisional occurrence data exercised through the real orchestrator
# path (ProjectLocator -> production CompilationOrchestrator ->
# compile()) plus the E4001/E3020/E3001 plan-shape gates. Joined here at
# the gate-script level, like the ISSUE-0474/0475 suites, so the single
# compile/test-list authority file (tools/gate-manifest.sh) stays
# untouched.
# =========================================================================
TEST_SOURCES+=(
  'test/DefaultSemanticPlannerTest.java'
  'deal/module/DefaultIrRecorderTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Default Semantic Planner Tests (ISSUE-0541) ===|java -ea -cp build deal.test.DefaultSemanticPlannerTest'
  'fg|=== Running Default IR Recorder Tests (ISSUE-0541) ===|java -ea -cp build deal.module.DefaultIrRecorderTest'
)

# =========================================================================
# ISSUE-0316 (E2 identity integration gates and the final release gate):
# the dedicated integration battery runs every pinned E2-integration
# scenario through the real production pipeline on both LuaJIT and JVM —
# multi-component roots (src, lib, lib/utils) with distinct byte-stable
# canonical descriptors and runtime tags, the dot-bearing configured root
# (src.models -> @src.models/User) and the dotted externals key
# (host.cfg -> @$external/host.cfg/ServerConfig) legal projections that
# parse, round-trip, and byte-match their runtime tags, the dotted-legacy
# tag failing E8011 end-to-end against the canonical projection, the
# unrepresentable required public identity (root text a@b, relative
# component x->y) failing E2010 at the class span before any
# metadata/artifact with class-free continuation in the same root, the
# class-free out-of-root artifact sets carrying no public descriptor, and
# the private-identity exclusion / legacy-emission grep pins over every
# identity-bearing artifact set. The conformance host-fixture projections
# merged by T5 stay verified by the gate's conformance suites
# (ConformanceTest / the lane pin tests) — this child verifies, never
# re-migrates. Joined here at the gate-script level, like the
# ISSUE-0474/0475 suites, so the single compile/test-list authority file
# (tools/gate-manifest.sh) stays untouched.
# =========================================================================
TEST_SOURCES+=( 'test/E2IdentityIntegrationGatesTest.java' )
TEST_MAINS+=(
  'fg|=== Running E2 Identity Integration Gates (ISSUE-0316) ===|java -ea -cp build deal.test.E2IdentityIntegrationGatesTest'
)

# =========================================================================
# ISSUE-0542 (canonical default serializer): the versioned canonical
# expression/statement grammars, digests, plan projections, provider
# digests, and the runtimeResources completion battery — exercised
# through the real orchestrator planning path (the E2 planner output)
# plus synthetic reentrant/broken-occurrence/range inputs. Joined here
# at the gate-script level, like the ISSUE-0541 suites, so the single
# compile/test-list authority file (tools/gate-manifest.sh) stays
# untouched.
# =========================================================================
TEST_SOURCES+=(
  'test/DefaultSemanticSerializerTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Default Semantic Serializer Tests (ISSUE-0542) ===|java -ea -cp build deal.test.DefaultSemanticSerializerTest'
)

# =========================================================================
# ISSUE-0543 (merged runtime dependency graph): the digest-free SCC
# pass over the merged ordinary (RUNTIME_USE) + default
# (DEFERRED_DEFAULT_BINDING) edges over the one RuntimeImportDependency
# carrier, E2005 with per-edge notes for default-only/ordinary-only/
# mixed runtime SCCs, the publication gate (no plan, FFI metadata, or
# artifact on E2005), type-only cycle legality, the acyclic path —
# provider digests requested only after the SCC pass, final
# digest-bearing dependency records, completed plans, and the
# initialization order preserving first-import order — exercised
# through the real orchestrator path (ProjectLocator -> production
# CompilationOrchestrator -> compile()) plus direct unit pins of the
# shared ordering algorithm. Joined here at the gate-script level, like
# the ISSUE-0541/0542 suites, so the single compile/test-list authority
# file (tools/gate-manifest.sh) stays untouched.
# =========================================================================
TEST_SOURCES+=(
  'test/ModuleDependencyGraphTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Module Dependency Graph Tests (ISSUE-0543) ===|java -ea -cp build deal.test.ModuleDependencyGraphTest'
)

# =========================================================================
# ISSUE-0544 (backend evaluator lowering and runtime plan realization):
# the compiler-to-lowerer battery — every published compiler default
# plan realized as executable LuaJIT, JVM, and JavaScript runtime plans
# (entry order, canonical descriptors, optional flags, evaluators
# exactly on required-present entries, labelled zero-argument evaluator
# closures over the declaring module scope, zero import-time
# evaluation, per-construction freshness, bytes reference retention,
# imported provider plans after dependency-ordered initialization, and
# the host defaults-map exemption) — exercised through the real
# orchestrator path (ProjectLocator -> production
# CompilationOrchestrator -> compile()) with real luajit / node /
# javac+java artifact execution plus the realized
# RuntimeClassDefaultPlan carriers and the D3 digests. Joined here at
# the gate-script level, like the ISSUE-0541/0542/0543 suites, so the
# single compile/test-list authority file
# (tools/gate-manifest.sh) stays untouched.
# =========================================================================
TEST_SOURCES+=(
  'test/RuntimeDefaultLoweringTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Runtime Default Lowering Tests (ISSUE-0544) ===|java -ea -cp build deal.test.RuntimeDefaultLoweringTest'
)

# =========================================================================
# ISSUE-0545 (construction-phase consumption and repeated-attempt/
# composition verification): the runtime consumption battery — repeated
# real constructions prove counts, ordering, freshness, isolation,
# retained function/bytes references, optional omission, failure
# timing, retained effects, and zero import-time evaluation across the
# normal, C-struct, and JSON construction paths on all three backends
# (LuaJIT/JVM/JavaScript real artifacts; the C-struct path runs the
# GCC-built committed native fixture through production load_ffi), plus
# the end-to-end composition scenario over the semantic identity,
# canonical descriptor, declaration marker, and bytes-value contracts
# with the stable-identity, provider-sensitive-identity, and
# broken-dependency negative arms. Joined here at the gate-script
# level, like the ISSUE-0541/0542/0543/0544 suites, so the single
# compile/test-list authority file (tools/gate-manifest.sh) stays
# untouched.
# =========================================================================
TEST_SOURCES+=(
  'test/RuntimeConstructionPhasesTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Runtime Construction Phases Tests (ISSUE-0545) ===|java -ea -cp build deal.test.RuntimeConstructionPhasesTest'
)

# =========================================================================
# ISSUE-0474 + ISSUE-0475 (Coverage Manifest Validator and Corpus
# Check): the reusable C7 validation component, its synthetic 26/0 unit
# matrix, and the real-manifest 82/0 mechanical check join the compile
# list and the unconditional run phase here, at the gate-script level
# (the authoring-time gate authority). The change boundary is
# deal/test/conformance/ plus run_tests.sh, so the single compile/
# test-list authority file (tools/gate-manifest.sh) stays untouched.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/CoverageManifestValidator.java'
  'deal/test/conformance/CoverageManifestValidatorTest.java'
  'deal/test/conformance/CoverageManifestCorpusTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Coverage Manifest Validator Tests (ISSUE-0474) ===|java -ea -cp build deal.test.conformance.CoverageManifestValidatorTest'
  'fg|=== Running Coverage Manifest Corpus Tests (ISSUE-0475) ===|java -ea -cp build deal.test.conformance.CoverageManifestCorpusTest'
)

# =========================================================================
# ISSUE-0488 (historical/legacy catalogs): the gate-run verification
# battery for the two catalogs joins the compile list and the run phase
# here. The catalogs themselves are test-harness data only (production
# code never depends on them); they compile from tools/gate-manifest.sh
# TEST_SOURCES (the manifest is the single compile-list authority and
# the strict full-set compile list), because the manifest-listed
# BackendConformanceTest/ConformanceTest runners consume them at
# startup. The conformance runners validate them at startup and record
# the signed-int32 historical executed evidence.
# =========================================================================
TEST_SOURCES+=(
  'test/HistoricalRegressionCatalogTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Historical / Legacy-Profile / Legacy-Capability Catalog Tests (ISSUE-0488) ===|java -ea -cp build deal.test.HistoricalRegressionCatalogTest'
)

# ISSUE-0477 (ISSUE-0402 acceptance remediation): the gate-closure strict
# gate regression suite — the three registry-shape modes of the re-landed
# in-runner strict gate and the strict-mode residual report, each
# executed through a compiled scratch copy of the ConformanceTest runner
# over a single-fixture scratch corpus root (the runner source, the
# fixture, and the repository are never modified). Joined here at the
# gate-script level, like the ISSUE-0474/0475 suites, so the single
# compile/test-list authority file (tools/gate-manifest.sh) stays
# untouched.
# =========================================================================
TEST_SOURCES+=(
  'test/GateClosureStrictGateTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Gate Closure Strict Gate Tests (ISSUE-0477) ===|java -ea -cp build deal.test.GateClosureStrictGateTest'


)

# =========================================================================
# ISSUE-0354 (LuaJIT lane): the Lua lane of the differential gate plus its
# lane suite join the compile list and the run phase here. The lane
# implements the Shared Lane Contract (G4) over the absorbed
# ConformanceTest compile -> LuaBackend -> luajit path and reuses the
# shared canonical ErrorSnapshot serializer verbatim. The
# LegacyProfileRegressionCatalog authority (the A5 per-case profile
# selection) was extracted from test/ConformanceTest.java into its own
# file; it compiles from tools/gate-manifest.sh TEST_SOURCES (consumed
# by the manifest-listed conformance runners, so the strict full-set
# compile list is self-consistent). ConformanceTest keeps running
# unchanged in run_tests.sh until the flip retires it.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/LuaLane.java'
  'deal/test/conformance/LuaLaneTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Lua Lane Tests (ISSUE-0354) ===|java -ea -cp build deal.test.conformance.LuaLaneTest'
)

# =========================================================================
# ISSUE-0356 (JS lane): the JavaScript lane of the differential gate plus
# its lane suite join the compile list and the run phase here. The lane
# implements the Shared Lane Contract (G4) over the absorbed
# BackendConformanceTest JS adapter path (real frontend + JsBackend ->
# deal/runtime.js + std/*.js + host-fixtures/<name>.js deployment -> real
# node subprocess) and reuses the shared canonical ErrorSnapshot
# serializer verbatim. BackendConformanceTest/JsE2eTest keep running
# unchanged until the absorption/retirement children land.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/JsLane.java'
  'deal/test/conformance/JsLaneTest.java'
)
TEST_MAINS+=(
  'fg|=== Running JS Lane Tests (ISSUE-0356) ===|java -ea -cp build deal.test.conformance.JsLaneTest'
)

# =========================================================================
# ISSUE-0355 (JVM lane): the JVM lane of the differential gate plus its
# lane suite join the compile list and the run phase here. The lane
# implements the Shared Lane Contract (G4) over the absorbed
# JvmConformanceTest whole-project pipeline (ProjectLocator ->
# CompilationOrchestrator -> JvmBackend codegen -> javac -> real java
# subprocess) and reuses the shared canonical ErrorSnapshot serializer
# verbatim. Pre-flip, the lane keeps the absorbed skip registry as
# tracked non-fatal paths (G8); JvmConformanceTest keeps running
# unchanged in run_tests.sh until the flip retires it (G5's
# temporary-coexistence window).
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/JvmLane.java'
  'deal/test/conformance/JvmLaneTest.java'
)
TEST_MAINS+=(
  'fg|=== Running JVM Lane Tests (ISSUE-0355) ===|java -ea -cp build deal.test.conformance.JvmLaneTest'
)

# =========================================================================
# ISSUE-0536 (ISSUE-0372 acceptance remediation) with the ISSUE-0331
# closure: the JS corpus gate launches on every gate run inside the JS
# lane state pin test. The pin test launches the real JsConformanceTest
# as a subprocess and asserts the captured state field-exactly: the
# lane-wide activated invocation (COMMON_SHADOW + DEAL_V1_2_INT32 — the
# invocation the LuaJIT lane's A5 seam resolves for uncatalogued
# fixtures) is pinned field-exactly, the emitted entry module calls
# $rt.setInt32Mode(true), deal/runtime.js checkInt gates at the
# signed-32 boundary, and the flipped shared fixture
# backend-runtime/stdlib-edge/time-now-millis-positive.deal passes as
# runtime-error E8004 on the JS gate — the gate validity condition
# expectation(fixture) == landed std/time.js behavior
# (js-v12-completion-architecture D5). The JS completion gate closure
# (ISSUE-0331) retired the last three owner-delegated lane divergences
# (the E8003 array-element walk over json-array-marked Map tables and
# the E8007 defaults-map seam in deal/runtime.js, with the cfg host
# triplet carrying the Lua-mirroring $rt.MISSING marks), so the pin
# test now asserts the closed state: exit 0, all 303 node-executed
# backend-runtime fixtures passing with zero skips and a 100.0% pass
# rate, zero ] FAIL ( / GATE FAILURE lines, and the Gates PASSED
# summary. The unselected direct-caller default mode of the retained
# JS runtime stays the legacy range, so test_stdlib_js.js keeps
# running unselected and stays green unchanged.
# =========================================================================
TEST_SOURCES+=( 'test/JsLaneStatePinTest.java' )
TEST_MAINS+=(
  'bg|=== Launching JS Lane State Pin Tests (ISSUE-0536 with the ISSUE-0331 closure: the real JS corpus gate runs inside the pin test under its activated invocation and the closed-state output — 303/303, zero skips, exit 0 — is pinned field-exactly) ===|java -ea -cp build deal.test.JsLaneStatePinTest'
)

# =========================================================================
# ISSUE-0353 (differential gate core): the gate components and their unit
# suites join the compile list and the run phase here. The gate's own
# deal.test entry point (deal.test.conformance.DifferentialGate) is NOT
# added to TEST_MAINS — it is exercised directly and is not wired into
# run_tests.sh until the flip (G5's temporary-coexistence window: the
# legacy runners keep executing the runtime corpus until the lanes land).
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/MismatchClass.java'
  'deal/test/conformance/GateMismatch.java'
  'deal/test/conformance/CorpusDiscovery.java'
  'deal/test/conformance/SidecarExpectations.java'
  'deal/test/conformance/ErrorSnapshot.java'
  'deal/test/conformance/StructuredExpectationComparator.java'
  'deal/test/conformance/FrontendCompiler.java'
  'deal/test/conformance/CompileDiagnosticComparator.java'
  'deal/test/conformance/Lane.java'
  'deal/test/conformance/LaneCase.java'
  'deal/test/conformance/LaneExecution.java'
  'deal/test/conformance/GateDispatcher.java'
  'deal/test/conformance/SidecarGateLoader.java'
  'deal/test/conformance/DifferentialGate.java'
  'deal/test/conformance/StructuredExpectationComparatorTest.java'
  'deal/test/conformance/CompileDiagnosticComparatorTest.java'
  'deal/test/conformance/GateDispatcherTest.java'
  'deal/test/conformance/GateClassificationTest.java'
  'deal/test/conformance/DifferentialGateCorpusTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Differential Gate Comparator Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.StructuredExpectationComparatorTest'
  'fg|=== Running Compile Diagnostic Comparator Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.CompileDiagnosticComparatorTest'
  'fg|=== Running Gate Dispatcher Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.GateDispatcherTest'
  'fg|=== Running Gate Classification Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.GateClassificationTest'
  'fg|=== Running Differential Gate Corpus Tests (ISSUE-0353) ===|java -ea -cp build deal.test.conformance.DifferentialGateCorpusTest'
)

# =========================================================================
# ISSUE-0357 (gate integration — cluster integration verification for the
# gate stage): the corpus-aware frontend resolver and the three-lane
# integration suite join the compile list and the run phase here. The
# suite runs the complete differential gate over the real corpus on all
# three production lanes (real luajit / javac+java / node subprocesses),
# pins the designated converged subset, the exact pre-flip failure set
# (tracked non-fatal + enumerated differential failures, zero skips), and
# the six controlled divergence experiments on scratch copies. The gate
# stays dev-time: the legacy runners keep executing the runtime corpus
# (G5 temporary-coexistence window) and run_tests.sh keeps exiting 0.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/CorpusFrontendResolver.java'
  'deal/test/conformance/DifferentialGateLanesCorpusTest.java'
)
TEST_MAINS+=(
  'fg|=== Running Differential Gate Lanes Corpus Tests (ISSUE-0357) ===|java -ea -cp build deal.test.conformance.DifferentialGateLanesCorpusTest'
)


# =========================================================================
# ISSUE-0360 (JSON slice absorption — pin-before-delete): the two
# dev-time evidence helpers join the COMPILE list only (not the run
# phase). JsonAbsorptionGateLog runs the full three-lane differential
# gate over the real corpus with the production lanes registered and
# prints the per-fixture per-backend VERDICT log committed at
# test/conformance/json-absorption/gate-pass-log.txt;
# JsonAbsorptionNegativeControls runs the oracle-negative control
# battery (Verification 4) over the absorbed destinations on scratch
# copies with its committed log at
# test/conformance/json-absorption/negative-control-log.txt. Both stay
# dev-time (G5's temporary-coexistence window): the legacy runners keep
# executing the corpus and the JSON path in run_tests.sh.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/JsonAbsorptionGateLog.java'
  'deal/test/conformance/JsonAbsorptionNegativeControls.java'
)
# =========================================================================
# ISSUE-0485 (CapabilityRegistry.withState transition surface): the
# single release-owned promotion/demotion transition surface and its D3
# invariant battery join the compile list and the unconditional run
# phase here, next to the FoundationIntegrationTest coverage it extends.
# The suite proves the six D3 invariants over every closed
# (capability x target x state) transition: totality, null rejection,
# source immutability incl. the release default, exactly-one-entry
# change, closed shape and pinned ordering, digest recomputation,
# no-op idempotence, and policy-freeness.
# =========================================================================
TEST_SOURCES+=( 'test/CapabilityRegistryTransitionTest.java' )
TEST_MAINS+=(
  'fg|=== Running Capability Registry Transition Surface Tests (ISSUE-0485) ===|java -ea -cp build deal.test.CapabilityRegistryTransitionTest'
)

# =========================================================================
# ISSUE-0490 (E12 public activation release action): the activation
# evidence record, the twelve-item promotion gate records, the release
# action, and the gate-run battery join the compile list and the
# unconditional run phase here. The suite proves the evidence-record
# content, every activation precondition negative (empty promotion
# list, missing SIGNED_INT32 for a shared target, missing/incomplete
# E11 evidence, a recorded pre-activation shared route, a gate-rejected
# pair), the promotion-attempt order/lock rejections, the digest
# recomputation over the flipped configuration (never an
# unchanged-hash claim), the committed flipped constant and promoted
# registry state, the A1 profile-mapping rows, the absence of any
# legacy-selection surface, and a concrete post-flip shared-routing
# plan for an int-using module (eligibility plus the SIGNED_INT32
# requirement). The committed release state stays flipped; every
# negative runs over a derived configuration.
# =========================================================================
TEST_SOURCES+=(
  'test/PromotionGateRecord.java'
  'test/ActivationEvidenceRecord.java'
  'test/E12ActivationReleaseAction.java'
  'test/E12ActivationReleaseTest.java'
)
TEST_MAINS+=(
  'fg|=== Running E12 Public Activation Release Action Tests (ISSUE-0490) ===|java -ea -cp build deal.test.E12ActivationReleaseTest'
)
# =========================================================================
# ISSUE-0239 (E10, the modules epic — production semantic-IR emission and
# atomic publication): the production gate compiles the applicable
# runtime fixtures through the real post-flip orchestrator on both
# targets and executes the emitted artifacts through the real toolchains
# (luajit; javac --release 25 -proc:none + java), pinning the production
# emitter seam (LoweredModuleUnit + StructuredBodyTable, never
# AST/CheckResult), the ModuleRoutePlan selection without fallback (one
# semantic/zero retained artifacts for the all-shared single-module
# projects; zero semantic/two retained for the multi-module graph; the
# dual-shape plan-time reroute), the retained DEAL_ERROR_CODE contract,
# and the atomic publication failure preservation (a failed shared
# lowering publishes nothing and preserves the prior artifact set
# byte-for-byte).
# =========================================================================
TEST_SOURCES+=( 'test/SemanticProductionGateTest.java' )
TEST_MAINS+=(
  'fg|=== Running Semantic Production Gate Tests (ISSUE-0239) ===|java -ea -cp build deal.test.SemanticProductionGateTest'
)

# ISSUE-0378 D5 (JvmLaneStatePinTest substitution): the pin test owns
# both raw lane runs. It launches the real LuaJIT lane and the real JVM
# lane as subprocesses and asserts their captured failure sets
# field-exactly, so the gate stays green on the sanctioned pinned
# staged state while both raw lanes still run - inside the pin test -
# on every gate run. The two raw lane launches are substituted by the
# single pin-test launch; the fail-closed background wait and every
# other suite stay.
TEST_SOURCES+=( 'test/JvmLaneStatePinTest.java' )

REBUILT_MAINS=()
for record in "${TEST_MAINS[@]}"; do
  case "$record" in
    'bg|=== Launching Conformance Tests (background) ===|java -ea -cp build deal.test.ConformanceTest test/conformance/')
      # Removed: the LuaJIT lane runs inside the pin test instead.
      ;;
    'bg|=== Launching JVM Conformance Tests (background; ISSUE-0102 origin — ISSUE-0168 capability accounting) ===|java -ea -cp build deal.test.JvmConformanceTest test/conformance/')
      REBUILT_MAINS+=( 'bg|=== Launching JVM Lane State Pin Tests (ISSUE-0378: both real lanes run inside the pin test with field-exact failure-set assertions) ===|java -ea -cp build deal.test.JvmLaneStatePinTest' )
      ;;
    *)
      REBUILT_MAINS+=( "$record" )
      ;;
  esac
done
TEST_MAINS=( "${REBUILT_MAINS[@]}" )

# =========================================================================
# ISSUE-0157 (strict v1.2 feature catalog and backend matrix): the three
# reusable catalog components — the strict schema-v1 feature-record
# metadata parser, the architecture-owned backend matrix, and the
# catalog loader (root/support closure, linked records, production
# // @spec: E1044 rejection, main/oracle source shapes) — plus their
# unit batteries and the real-corpus gate join the compile list and the
# unconditional run phase here. The change boundary is
# deal/test/conformance/ plus test/features/ (the on-disk catalog
# corpus); the linked JVM C_FFI rejection record pins E6003 — the exact
# code the production JVM pipeline emits at @extern-c
# (deal/module/CompilationOrchestrator.java) — so
# deal/diagnostics/DiagnosticCode.java stays untouched by this change;
# the single compile/test-list authority file (tools/gate-manifest.sh)
# stays untouched.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/conformance/V12FeatureMetadata.java'
  'deal/test/conformance/FeatureBackendMatrix.java'
  'deal/test/conformance/V12FeatureCatalog.java'
  'deal/test/conformance/V12FeatureMetadataTest.java'
  'deal/test/conformance/FeatureBackendMatrixTest.java'
  'deal/test/conformance/V12FeatureCatalogTest.java'
  'deal/test/conformance/V12FeatureCatalogCorpusTest.java'
)
TEST_MAINS+=(
  'fg|=== Running V12 Feature Metadata Tests (ISSUE-0157) ===|java -ea -cp build deal.test.conformance.V12FeatureMetadataTest'
  'fg|=== Running Feature Backend Matrix Tests (ISSUE-0157) ===|java -ea -cp build deal.test.conformance.FeatureBackendMatrixTest'
  'fg|=== Running V12 Feature Catalog Tests (ISSUE-0157) ===|java -ea -cp build deal.test.conformance.V12FeatureCatalogTest'
  'fg|=== Running V12 Feature Catalog Corpus Tests (ISSUE-0157) ===|java -ea -cp build deal.test.conformance.V12FeatureCatalogCorpusTest'
)

# =========================================================================
# ISSUE-0165 (E13): the dedicated production-path ISSUE-0111 feature /
# native / cross-backend release gate. The strict sidecar catalog, the
# architecture-owned backend matrix, the fixture corpus under
# test/features-iss0111/, the production ProjectLocator/orchestrator/
# runtime execution wiring, the pinned-launcher (E12) containment of
# every tool and fixture process, the production async-export (E9)
# evidence step, and the contained real-native load_ffi (E11) probe join
# the compile list and the unconditional run phase here. Fail-closed: a
# missing tool, launcher, catalog defect, backend omission, containment
# failure, wrong DEAL code, or infrastructure failure makes this gate
# nonzero — never a skip. General corpus promotion remains ISSUE-0107.
# =========================================================================
TEST_SOURCES+=(
  'deal/test/feature/FeatureId.java'
  'deal/test/feature/V12FeatureMetadata.java'
  'deal/test/feature/FeatureBackendMatrix.java'
  'deal/test/feature/V12FeatureFixtureCatalog.java'
  'deal/test/feature/V12FeatureGate.java'
  'deal/test/feature/V12FeatureGateTest.java'
)
TEST_MAINS+=(
  'fg|=== Running V12 Feature Catalog/Matrix Tests (ISSUE-0165) ===|java -ea -cp build deal.test.feature.V12FeatureGateTest'
  'fg|=== Running the Production V12 Feature/Native Gate (ISSUE-0165) ===|java -ea -cp build deal.test.feature.V12FeatureGate'
)

# =========================================================================
# ISSUE-0517 (class-construction integration tail): the real
# end-to-end semantic-IR pipeline suite driving local/imported
# construction, provided/default order, mutable-default freshness,
# E8007, field failures, presence states, nested factory parenting,
# JSON round trips, null-on-input-failure, and first serialization
# failure through the production lowerer (lowerModuleClassCore with
# every production validator) and the assembled ClassOpsExecutor with
# the production BoundaryExecutor and JsonClassAlgorithmAdapter
# delegates. Joined here at the gate-script level, like the
# ISSUE-0474/0475 suites, so the single compile/test-list authority
# file (tools/gate-manifest.sh) stays untouched.
# =========================================================================
TEST_SOURCES+=( 'test/ClassConstructionIntegrationTailTest.java' )
TEST_MAINS+=(
  'fg|=== Running Class Construction Integration Tail Tests (ISSUE-0517) ===|java -ea -cp build deal.test.ClassConstructionIntegrationTailTest'
)

# =========================================================================
# DEALPG4 fail-closed toolchain preflight (ISSUE-0183,
# fail-closed-toolchain-preflight D1/D2/D5): one ordered, fail-closed
# phase sequence P0-P5 shared verbatim with coverage.sh via
# tools/preflight-lib.sh. P0 (launcher integrity), P1 (probe identity +
# LIMITS cross-check), P2 (native selftest), and P3 (fail-closed tool
# presence) run here, before any GCC/Javac/Java probe; P4 (bounded
# standalone javac under launcher run) replaces the raw compile below;
# P5 (outer feature supervisor + PreflightCoordinator) runs after the
# compile and before the legacy phases (P6, unchanged). No phase is
# skipped, downgraded, or retried; every failure prints its named token
# on stderr and exits nonzero immediately. No Java process in this
# script spawns the launcher or an outer (D7).
# =========================================================================
source tools/preflight-lib.sh
# PROD_SOURCES is expanded unquoted so the manifest's quoted globs are
# expanded here exactly as they were when the list lived inline (the
# identical expanded file list javac has always received).
DEALPG4_PREFLIGHT_JAVAC_ARGS=(
  javac --release 25 -proc:none -d build \
  -cp /usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar \
  # shellcheck disable=SC2206
  ${PROD_SOURCES[@]} "${TEST_SOURCES[@]}"
)
DEALPG4_PREFLIGHT_COORD_ARGS=(
  java -ea -cp build deal.test.containment.PreflightCoordinator
)

# =========================================================================
# Strict-mode bounded routing (bounded-step-table-and-library D8): every
# tool child dispatches through the shared step library under its pinned
# step name. Dev mode (no DEAL_STRICT) executes the child directly --
# the same child, the same output, the same exit status. Strict mode
# routes through tools/release-step-lib.sh (E5) under the table bound.
# =========================================================================
run_step() {
  local step="$1"; shift
  [ "$#" -ge 1 ] && [ "$1" = "--" ] || { echo "INTERNAL ERROR: malformed run_step invocation" >&2; exit 1; }
  shift
  if [ -n "${DEAL_STRICT:-}" ]; then
    RELEASE_EXPORT_ROOT="$PWD" tools/release-step-lib.sh step_run "$step" -- "$@"
  else
    "$@"
  fi
}

# Step name for a TEST_MAINS java record: the main class token after the
# -cp classpath, or the junit- bundle name for JUnitCore records
# (bounded-step-table-and-library D4).
main_step_name() {
  if [ "$5" = "org.junit.runner.JUnitCore" ]; then
    printf 'junit-%s\n' "$6"
  else
    printf '%s\n' "$5"
  fi
}

# Step name for a luajit/node tool suite invocation line, mapped by
# the invoked fixture file (bounded-step-table-and-library D4). The
# suites run unconditionally (ISSUE-0362); the step name is the strict
# step-library table key, not a guard.
tool_line_step() {
  # shellcheck disable=SC2124
  local file="${@: -1}"
  case "$file" in
    test_runtime.lua) printf 'suite-runtime-core\n' ;;
    test_runtime_int32.lua) printf 'suite-runtime-int32\n' ;;
    test/lua_async_export_driver_test.lua) printf 'suite-async-export-driver\n' ;;
    test_runtime_jsonable.lua) printf 'suite-runtime-jsonable\n' ;;
    test_jsonable_js.js) printf 'suite-runtime-js-jsonable\n' ;;
    test_host_js.js) printf 'suite-runtime-js-hostabi\n' ;;
    test_stdlib.lua) printf 'suite-stdlib-lua\n' ;;
    test_stdlib_js.js) printf 'suite-stdlib-js\n' ;;
    test_async_nesting.lua) printf 'suite-async-nesting\n' ;;
    *)
      echo "INTERNAL ERROR: no strict step name for guarded suite file: $file" >&2
      exit 1
      ;;
  esac
}

dealpg4_preflight_run

# =========================================================================
# Single compilation step: compile all source and test files at once.
# Incremental: when every .java source under deal/ and test/ is older
# than the recorded build stamp (and this script itself has not changed
# since the stamp), reuse the build/ classes. The stamp is updated after
# every successful full compile, so repeated gate runs on an unchanged
# tree skip the recompilation while a fresh checkout or any touched
# source still compiles everything.
#
# The compile is preflight P4: it runs under `launcher run` (bounded
# standalone javac — 45 s native deadline, 1 MiB drained output) instead
# of the raw javac; the stamp logic is unchanged.
# =========================================================================
STAMP="build/.deal-build-stamp"
# Strict mode (S2(b)): the incremental stamp is never read and never
# written -- a full compile runs unconditionally and a pre-existing stamp
# is left untouched. Dev mode keeps the stamp logic verbatim.
if [ -n "${DEAL_STRICT:-}" ]; then
  NEEDS_BUILD=1
else
  NEEDS_BUILD=0
  if [ ! -f "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ run_tests.sh -nt "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ tools/preflight-lib.sh -nt "$STAMP" ]; then
    NEEDS_BUILD=1
  elif [ -n "$(find deal test -name '*.java' -newer "$STAMP" -print -quit)" ]; then
    NEEDS_BUILD=1
  fi
fi

if [ "$NEEDS_BUILD" = "1" ]; then
  echo "=== Compiling all DEAL sources and tests ==="
# -proc:none: no DEAL/test source uses an annotation processor, so javac's
# default processor-discovery pass is pure per-task startup cost (the gate
# budget is shared with the JVM artifact suites; measured ~40% faster
# compile under load).
# PROD_SOURCES is expanded unquoted so the manifest's quoted globs are
# expanded here exactly as they were when the list lived inline; javac
# receives the identical expanded file list it receives today.
  # Strict mode routes the compile through the step library under the
  # compile-dev table entry (S2(e)); dev mode keeps the preflight P4
  # launcher-bounded javac and the stamp update verbatim.
  if [ -n "${DEAL_STRICT:-}" ]; then
    run_step compile-dev -- "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
  else
    dealpg4_preflight_javac "${DEALPG4_PREFLIGHT_JAVAC_ARGS[@]}"
    touch "$STAMP"
  fi
else
  echo "=== DEAL sources and tests unchanged since the last build; reusing build/ ==="
fi

# =========================================================================
# Preflight P5: the outer feature supervisor runs the
# deal.test.containment.PreflightCoordinator JVM (the only post-readiness
# JVM this epic owns): authenticated broker HELLO/FEATURE_READY, bounded
# round-trip nested invocations (luajit -v, /bin/true) through the
# inherited broker, a clean session end (the coordinator closes the
# broker and exits 0; the outer's clean-exit discrimination takes the
# BYE-less short run — the frame pins DONE -> BYE, and DONE lands only
# at the 14:40 cutoff), and a clean outer final proof. Any FAILED
# record or coordinator failure token exits nonzero. The broker socket
# is unlinked by the outer's final proof.
# =========================================================================
dealpg4_preflight_outer

# =========================================================================
# Identity package gate (ISSUE-0309): deal.identity is the neutral
# JDK-only carrier package. A standalone compile against an empty
# classpath fails on any symbol outside java.* and deal.identity, and an
# import scan pins the allowed import surface.
# =========================================================================
echo ""
echo "=== Identity Package Gate: JDK-only closure ==="
mkdir -p build/identity-cp-empty build/identity-standalone
run_step compile-identity -- javac --release 25 -proc:none \
  -cp build/identity-cp-empty \
  -d build/identity-standalone deal/identity/*.java
IDENTITY_IMPORTS="$(grep -hE '^import ' deal/identity/*.java || true)"
IDENTITY_BAD_IMPORTS="$(echo "$IDENTITY_IMPORTS" \
  | grep -vE '^import java\.' \
  | grep -vE '^import deal\.identity\.' || true)"
if [ -n "$IDENTITY_BAD_IMPORTS" ]; then
  echo "  ERROR: deal.identity imports outside java.* and deal.identity:"
  echo "$IDENTITY_BAD_IMPORTS"
  exit 1
fi
echo "  deal.identity is JDK-only (standalone compile and import scan pass)."

# =========================================================================
# Migration gate (verification 7): the legacy start-only record is gone
# (compile-time enforced: deal/lexer/Diagnostic.java is deleted, so any
# leftover start-only call site fails compilation), and two source scans
# assert that (1) no reference to deal.lexer.Diagnostic remains anywhere
# in deal/ or test/ and (2) no production source creates an
# internal-defect note outside the D9 normalization carrier in
# deal/diagnostics/CompilerDiagnostic.java. Either scan tripping fails
# the gate.
# =========================================================================
echo ""
echo "=== Migration Gate: legacy diagnostic surface scans ==="
if [ -e deal/lexer/Diagnostic.java ]; then
  echo "  ERROR: deal/lexer/Diagnostic.java still exists; the legacy start-only record must be deleted."
  exit 1
fi
LEGACY_REFS="$(grep -rn 'deal\.lexer\.Diagnostic' deal test --include='*.java' 2>/dev/null || true)"
if [ -n "$LEGACY_REFS" ]; then
  echo "  ERROR: references to the legacy deal.lexer.Diagnostic record remain:"
  echo "$LEGACY_REFS"
  exit 1
fi
DEFECT_NOTES="$(grep -rn '"internal range defect' deal --include='*.java' 2>/dev/null | grep -v '^deal/diagnostics/CompilerDiagnostic.java:' || true)"
if [ -n "$DEFECT_NOTES" ]; then
  echo "  ERROR: production sources create internal-defect notes outside the D9 normalization carrier:"
  echo "$DEFECT_NOTES"
  exit 1
fi
echo "  Migration gate scans pass (no legacy record, no legacy references, no production defect-note creation)."

# =========================================================================
# Run all tests.
#
# The five heavy suites (backend conformance, JVM backend, the JUnit
# ABI/typing suite, and the two conformance runners) are independent:
# each confines its generated artifacts and subprocess work to its own
# PID-unique temp directories and reads the shared fixture/stdlib trees
# read-only. They run concurrently in the background while the remaining
# phases run sequentially in the foreground, so the whole gate fits its
# wall-clock budget even on a loaded machine. Each background suite is
# waited on before the final verdict; any background failure fails the
# gate exactly like a foreground failure.
#
# The run phase is driven by TEST_MAINS from tools/gate-manifest.sh: each
# record is "<class>|<banner>|<command>"; the dispatcher below reproduces
# today's run order verbatim — background launches first, then the
# foreground mains with the luajit/node suites running unconditionally
# (ISSUE-0362, v12-zero-skip-conformance-gate G3: tool absence is a
# preflight P3 failure — TOOL_MISSING <tool> — before any suite starts,
# so no suite is ever skipped) and the golden-IR check between the
# pre-activation pin and the conformance harness metadata tests.
# =========================================================================
BACKGROUND_PIDS=""

launch_background() {
  local step
  step="$(main_step_name "$@")"
  run_step "$step" -- java "-Ddeal.test.jobs=$JOBS" "${@:2}" &
  BACKGROUND_PIDS="$BACKGROUND_PIDS $!"
}

cleanup_background() {
  # shellcheck disable=SC2086
  if [ -n "$BACKGROUND_PIDS" ]; then
    kill $BACKGROUND_PIDS 2>/dev/null || true
  fi
}
trap cleanup_background EXIT

for record in "${TEST_MAINS[@]}"; do
  record_class="${record%%|*}"
  record_rest="${record#*|}"
  record_banner="${record_rest%%|*}"
  record_command="${record_rest#*|}"
  if [ -n "$record_banner" ]; then
    echo ""
    echo "$record_banner"
  fi
  case "$record_class" in
    bg)
      read -r -a record_args <<< "$record_command"
      launch_background "${record_args[@]}"
      ;;
    fg)
      read -r -a record_args <<< "$record_command"
      run_step "$(main_step_name "${record_args[@]}")" -- java "-Ddeal.test.jobs=$JOBS" "${record_args[@]:1}"
      ;;
    luajit|node)
      # ISSUE-0362 (v12-zero-skip-conformance-gate G3/G8): the
      # luajit/node suites run unconditionally — the removed
      # `command -v` conditional branch is the issue's retired
      # tool-absence skip. Tool absence now fails preflight P3
      # (TOOL_MISSING <tool>) before any suite starts, and a missing
      # tool reaching this point fails the run via `set -e` (exit 127),
      # never a skip. The record's trailing WARNING: skip line is
      # legacy manifest content and is never printed (the manifest
      # stays untouched).
      while IFS= read -r record_line; do
        case "$record_line" in
          WARNING:*) ;;
          *)
            read -r -a record_args <<< "$record_line"
            run_step "$(tool_line_step "${record_args[@]}")" -- "${record_args[@]}"
            ;;
        esac
      done <<< "$record_command"
      ;;
    golden-ir)
      GOLDEN_FILE="test/goldens/stdlib-declarations.ir.txt"
      TEMP_FILE="/tmp/deal-stdlib-ir-$$.txt"
      # Strict mode drops the dev-only stderr redirect: the library's
      # capped stderr relay carries the tool's stderr (bounded-step-
      # table-and-library D8). Dev mode keeps the exact redirect.
      if [ -n "${DEAL_STRICT:-}" ]; then
        run_step deal.test.GenerateStdlibGoldenIr -- java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE"
      else
        run_step deal.test.GenerateStdlibGoldenIr -- java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null
      fi
      if [ "${DEAL_UPDATE_GOLDENS}" = "true" ]; then
        cp "$TEMP_FILE" "$GOLDEN_FILE"
        echo "  Golden IR file updated"
      else
        if diff -q "$GOLDEN_FILE" "$TEMP_FILE" > /dev/null 2>&1; then
          echo "  Golden IR file is current"
        else
          echo "  ERROR: Golden IR file differs from generated output!"
          echo "  Run 'DEAL_UPDATE_GOLDENS=true ./run_tests.sh' to update."
          diff "$GOLDEN_FILE" "$TEMP_FILE" || true
          rm -f "$TEMP_FILE"
          exit 1
        fi
      fi
      rm -f "$TEMP_FILE"
      ;;
    *)
      echo "INTERNAL ERROR: unknown TEST_MAINS record class '${record_class}'" >&2
      exit 1
      ;;
  esac
done

# =========================================================================
# ISSUE-0455 (luajit-ffigen-boundary-integration D3/D7; Architecture item
# 4): the committed FFI integration driver runs after the manifest-driven
# run phase, adjacent to the LuaJIT suites, and is fail-closed — no
# `command -v` guard, no skip path. The driver bootstraps the T2 native
# fixture with GCC, compiles the three committed extern-c fixture projects
# through the production CLI, runs the generated-artifact surface scan
# (gate half 3), and executes the eight-phase scenario matrix under real
# LuaJIT. Any bootstrap failure, scan mismatch, or assertion failure exits
# nonzero; `set -e` (line 2) propagates it and fails the gate, and a
# missing luajit/gcc/java/build/ fails the gate, never a warning.
# =========================================================================
echo ""
echo "=== Running FFI Integration Driver (ISSUE-0455) ==="
luajit test/ffigen_integration.lua

echo ""
echo "=== Waiting for background suites ==="
BACKGROUND_FAILED=0
# shellcheck disable=SC2086
for pid in $BACKGROUND_PIDS; do
  if ! wait "$pid"; then
    BACKGROUND_FAILED=1
  fi
done
if [ "$BACKGROUND_FAILED" -eq 1 ]; then
  echo "=== A background test suite failed ===" >&2
  exit 1
fi
trap - EXIT

# Strict mode (S2(d)/S4): restore the live streams, close the capture,
# and assert the captured log before the final marker. An assertion
# failure fails the script with STRICT_OUTPUT_VIOLATION on stderr.
if [ -n "${DEAL_STRICT:-}" ]; then
  exec >&3 2>&4
  exec 5>&- 6>&-
  wait "$STRICT_RELAY_OUT_PID" || true
  wait "$STRICT_RELAY_ERR_PID" || true
  rm -f "$STRICT_OUT_FIFO" "$STRICT_ERR_FIFO"
  tools/strict-output-assert.sh "$STRICT_LOG"
fi

echo "=== All Tests Passed ==="
