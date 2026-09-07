# tools/gate-manifest.sh — the single compile/test-list authority for the
# DEAL dev and release gates (gate-manifest-authority M1-M3;
# complete-coverage-release-gate D1).
#
# Consumers source this file from the repository root:
#   run_tests.sh               compile list + ordered run phase
#   tools/build-release.sh     PROD_SOURCES only (distribution compile)
#   DEAL_STRICT=1 coverage.sh  full-set strict coverage gate (E6)
#
# Contract: this file defines exactly three names and nothing else —
# PROD_SOURCES, TEST_SOURCES, TEST_MAINS. It executes no commands, writes
# nothing, prints nothing, sets no shell options, and mutates nothing
# beyond the three names.

# PROD_SOURCES: today's run_tests.sh production compile entries, verbatim,
# quoted globs, in today's order (19 entries, ending at deal/Main.java).
PROD_SOURCES=(
  'deal/source/*.java'
  'deal/ast/*.java'
  'deal/types/*.java'
  'deal/descriptors/*.java'
  'deal/diagnostics/*.java'
  'deal/compiler/*.java'
  'deal/lexer/*.java'
  'deal/parser/*.java'
  'deal/checker/*.java'
  'deal/codegen/*.java'
  'deal/codegen/lua/*.java'
  'deal/codegen/jvm/*.java'
  'deal/codegen/js/*.java'
  'deal/ir/*.java'
  'deal/semantic/*.java'
  'deal/semantic/ir/*.java'
  'deal/module/*.java'
  'deal/project/*.java'
  'deal/identity/*.java'
  'deal/Main.java'
  # ISSUE-0457 registration: the distribution-discovery package
  # (DistributionHome — runtime/stdlib resolution outside the checkout).
  'deal/distribution/*.java'
  # ISSUE-0458 registration: the transactional whole-project artifact
  # publication package (Artifact, ArtifactSet, PublicationStager).
  'deal/publication/*.java'
  # ISSUE-0162 registration: the validated C FFI metadata and forward
  # binding generation package (FfiDeclarationValidator, descriptor
  # records, CdefBundle, forward cells, LuaFfiBindingGenerator).
  'deal/ffi/*.java'
)

# TEST_SOURCES: the remainder of today's compile list, verbatim, in
# today's order (no dedup, no filtering).
TEST_SOURCES=(
  'test/StubModuleResolver.java'
  'test/IdentityTestFixtures.java'
  'test/CheckedProjectBuilderTest.java'
  'test/LoweringSupportTest.java'
  'test/MigrationPlannerTest.java'
  'test/StagingPublicationTest.java'
  'test/FoundationIntegrationTest.java'
  'test/InvocationProfileRegistryTest.java'
  'test/DiagnosticRangeTest.java'
  'test/DiagnosticClassificationTest.java'
  'test/LoweringFoundationTest.java'
  'test/SemanticIrSchemaTest.java'
  'test/DescriptorServiceTest.java'
  'test/ContainerPayloadDescriptorsTest.java'
  'test/BoundaryExecutorTest.java'
  'test/ComparisonExecutorTest.java'
  'test/AddressChainProtocolTest.java'
  'test/AddressChainLoweringTest.java'
  'test/ControlFlowLoweringTest.java'
  'test/EvaluationOrderIntegrationTest.java'
  'test/RuntimeIntegrationMatrixTest.java'
  'test/UnicodeScalarsTest.java'
  'test/BoundaryRealizationReportTest.java'
  'test/FailureContractRegistryTest.java'
  'test/CanonicalJsonTest.java'
  'test/SemanticIrValidatorTest.java'
  'test/BoundaryTableCorpusTest.java'
  'test/BoundaryIntegrationTest.java'
  'test/ControlFlowValidatorTest.java'
  'test/SemanticIrDumperTest.java'
  'test/SemanticTableTest.java'
  'test/ContainerOpsExecutorTest.java'
  'test/ComparisonSelectorLoweringTest.java'
  'test/ContainerLoweringArmsTest.java'
  'test/ContainerClaimingSeamTest.java'
  'test/ContainerIntegrationTest.java'
  'test/BindingCoreLoweringTest.java'
  'test/ClosureLoweringTest.java'
  'test/RecursiveGroupLoweringTest.java'
  'test/FunctionBindingRegistryTest.java'
  'test/BindingImmutabilityProofTest.java'
  'test/AdapterCreationRuleTest.java'
  'test/AdapterShapeMapPayloadTest.java'
  'test/BindingsValidationTest.java'
  # ISSUE-0452 registration: the epic's integration verification tail
  # (Sequencing item 9): the fixed creation-rule/shape-map corpus and
  # the executed boundary-position E8010 drive through E4's machinery.
  'test/BindingsIntegrationVerificationTest.java'
  'test/AstAndTypesTest.java'
  'test/TypesBytesTest.java'
  'test/LexerTest.java'
  'test/ParserTest.java'
  'test/DirectiveTest.java'
  'test/CheckerTest.java'
  'test/IrDumperTest.java'
  'test/IrGoldenTest.java'
  'test/TypeDescriptorTest.java'
  'test/CanonicalRuntimeTypeDescriptorTest.java'
  'test/RuntimeTypeMatcherTest.java'
  'test/BackendConformanceTest.java'
  'test/JvmBackendTest.java'
  'test/JsBackendTest.java'
  'test/JsE2eTest.java'
  'test/LuaBackendTest.java'
  'test/LuaBackendIntegrationTest.java'
  'test/ModuleSystemTest.java'
  'test/ProjectMigrationIntegrationTest.java'
  'test/ProjectIntegrationGatesTest.java'
  'test/ProjectGraphFixturesGatesTest.java'
  'test/StdlibDeclParseTest.java'
  'test/SourceMapTest.java'
  'test/RuntimeSourceLocationTest.java'
  'test/StdlibContractTest.java'
  'test/StdlibTimePreActivationPinTest.java'
  'test/GenerateStdlibGoldenIr.java'
  'test/ConformanceTest.java'
  'test/ConformanceHarnessMetadata.java'
  'test/ConformanceHarnessMetadataTest.java'
  # ISSUE-0488 registration: the historical/legacy catalogs —
  # HistoricalRegressionCatalog (the closed historical pin authority
  # with pinned expectation baselines) and LegacyCapabilityCatalog (the
  # release-owned unsupported-legacy-slice authority) — are gate-run
  # conformance data consumed by the manifest-listed
  # BackendConformanceTest/ConformanceTest runners at startup; they
  # compile from the manifest so the strict full-set compile list (the
  # manifest exactly) is self-consistent.
  'test/HistoricalRegressionCatalog.java'
  'test/LegacyCapabilityCatalog.java'
  # ISSUE-0354 registration: the LegacyProfileRegressionCatalog A5
  # per-case profile-selection authority (extracted from
  # ConformanceTest) — consumed by the manifest-listed
  # ConformanceTest/JvmConformanceTest/FoundationIntegrationTest
  # runners.
  'test/LegacyProfileRegressionCatalog.java'
  'test/JvmConformanceTest.java'
  'test/JsConformanceTest.java'
  'test/LuaAbiTest.java'
  'test/LuaAbiBackendTest.java'
  'test/LuaJitAsyncExportInvokerTest.java'
  # ISSUE-0346 registration: the REGISTRY delegated-boundary
  # consumption test — D12-shaped async-export record projects
  # through the production orchestrator + production invoker.
  'test/RegistryAsyncExportBoundaryTest.java'
  'test/CrossModuleTypingTest.java'
  'test/ProtectedPathOpsTest.java'
  'test/CanonicalIdentityTest.java'
  'test/CompilerWorkspaceTest.java'
  'deal/test/containment/ContainedProcessBroker.java'
  'deal/test/containment/PreflightCoordinator.java'
  'deal/test/conformance/SidecarSchemaValidator.java'
  'deal/test/conformance/SidecarSchemaValidatorTest.java'
  'deal/test/conformance/SidecarCorpusValidationTest.java'
  'deal/test/containment/ContainedProcessBrokerFramingTest.java'
  'deal/test/containment/ContainedProcessBrokerStateTest.java'
  'deal/project/ProjectLocatorTest.java'
  'deal/module/ModuleIdentityResolverTest.java'
  # ISSUE-0457 registration: the DistributionHome tier-selection suite.
  'test/DistributionHomeTest.java'
  # ISSUE-0458 registration: the transactional publication contract
  # suite (forced-failure stage test, fresh-root publish, stale-set
  # purge, crash recovery, concurrency, IR dumps, out-of-checkout
  # copies, and the publish I/O failure path).
  'test/PublicationStagerTest.java'
  # ISSUE-0493 registration: the closed StdlibFunctionCatalog (the 20
  # declared stdlib exports with declared descriptors) and the
  # checked-fact stdlib recognition predicate battery (enumeration,
  # negative lookups, recognition over real checked projects, and the
  # anti-hollow spelling control).
  'test/StdlibFunctionCatalogTest.java'
  # ISSUE-0494 registration: the STDLIB_CALL lowering battery (D2,
  # Verification 4).
  'test/StdlibCallLoweringTest.java'
  # ISSUE-0495 registration: the SharedStdlibSemantics executor
  # battery (D4/D5, Verification 1 and 3; the combined T2/T1 drive).
  'test/SharedStdlibSemanticsTest.java'
  # ISSUE-0496 registration: the stdlib failure-projection wiring and
  # boundary realization reporting battery (D6, Verification 2 and 4).
  'test/StdlibFailureProjectionTest.java'
  # ISSUE-0497 registration: the STDLIB_SEMANTICS claiming arms (the
  # ContainerClaimingSeam STDLIB_CALL → STDLIB_SEMANTICS home row and
  # the plan-time manifest arm), the time-lock negative proofs, and the
  # stdlib-export value-read disposition battery
  # (stdlib-operations-and-time-lock D3/D8/D9, Verification 4 and 5).
  'test/StdlibClaimingTimeLockTest.java'
  # ISSUE-0498 registration: the target-helper equivalence battery —
  # every retained helper candidate (Lua std/*.lua, JS std/*.js, the
  # JVM backend's emitted stdlib helpers) compared against
  # SharedStdlibSemantics plus the projection wiring on the full
  # declared input domain, with the known divergent verdicts detected
  # by the comparison, the trim candidates, the wiring admission rule,
  # and the promotion-evidence registry
  # (stdlib-operations-and-time-lock D7, Verification 6; sequencing
  # item 6).
  'test/StdlibEquivalenceBatteryTest.java'
  # ISSUE-0499 registration: the epic's decomposition tail — the
  # end-to-end stdlib and time-lock integration verification
  # (stdlib-operations-and-time-lock Contracts §Integration verification
  # task, Verification 7-8; sequencing item 7, the last task).
  'test/StdlibIntegrationTest.java'
  # ISSUE-0162 registration: the C FFI declaration validation and
  # forward binding generation battery.
  'test/FfiDeclarationValidatorTest.java'
)

# TEST_MAINS: ordered "<class>|<banner>|<command>" records reproducing
# today's run phase exactly. class is one of bg, fg, luajit, node,
# golden-ir. For guarded luajit/node records the command field carries
# today's command lines followed by today's verbatim WARNING skip line.
TEST_MAINS=(
  'bg|=== Launching Backend Conformance Tests (background) ===|java -ea -cp build deal.test.BackendConformanceTest'
  'bg|=== Launching JVM Backend Tests (background) ===|java -ea -cp build deal.test.JvmBackendTest'
  'bg|=== Launching Lua ABI Unit Tests (background; JUnit4 + Hamcrest) ===|java -ea -cp build:/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar org.junit.runner.JUnitCore deal.test.LuaAbiTest deal.test.LuaAbiBackendTest deal.test.CrossModuleTypingTest'
  'bg|=== Launching Conformance Tests (background) ===|java -ea -cp build deal.test.ConformanceTest test/conformance/'
  'bg|=== Launching JVM Conformance Tests (background; ISSUE-0102 origin — ISSUE-0168 capability accounting) ===|java -ea -cp build deal.test.JvmConformanceTest test/conformance/'
  'fg|=== Running ContainedProcessBroker Framing Tests ===|java -ea -cp build deal.test.containment.ContainedProcessBrokerFramingTest'
  'fg|=== Running ContainedProcessBroker State Tests ===|java -ea -cp build deal.test.containment.ContainedProcessBrokerStateTest'
  'fg|=== Running Diagnostic Range Tests ===|java -ea -cp build deal.test.DiagnosticRangeTest'
  'fg|=== Running Diagnostic Classification Tests ===|java -ea -cp build deal.test.DiagnosticClassificationTest'
  'fg|=== Running Lowering Foundation Tests (ISSUE-0281) ===|java -ea -cp build deal.test.LoweringFoundationTest'
  'fg|=== Running Checked Project Builder Tests (ISSUE-0288) ===|java -ea -cp build deal.test.CheckedProjectBuilderTest'
  'fg|=== Running Lowering Support / Requirement Manifest Tests (ISSUE-0289) ===|java -ea -cp build deal.test.LoweringSupportTest'
  'fg|=== Running Migration Planner / Route Plan Tests (ISSUE-0290) ===|java -ea -cp build deal.semantic.MigrationPlannerTest'
  'fg|=== Running Staging / ABI Validation / Atomic Publication Tests (ISSUE-0291) ===|java -ea -cp build deal.semantic.StagingPublicationTest'
  'fg|=== Running Foundation Integration Tests (ISSUE-0292) ===|java -ea -cp build deal.test.FoundationIntegrationTest'
  'fg|=== Running Invocation / Profile / Capability Registry Tests (ISSUE-0284) ===|java -ea -cp build deal.test.InvocationProfileRegistryTest'
  'fg|=== Running Semantic IR Schema Tests (ISSUE-0282) ===|java -ea -cp build deal.test.SemanticIrSchemaTest'
  'fg|=== Running Descriptor Service Tests (ISSUE-0233 D1/D2) ===|java -ea -cp build deal.test.DescriptorServiceTest'
  'fg|=== Running Container Payload Descriptor Bridge Tests (ISSUE-0232 D2) ===|java -ea -cp build deal.test.ContainerPayloadDescriptorsTest'
  'fg|=== Running Boundary Executor Tests (ISSUE-0364 D3) ===|java -ea -cp build deal.test.BoundaryExecutorTest'
  'fg|=== Running Comparison Operand View and Executor Tests (ISSUE-0406, ISSUE-0234 B-D1/B-D2/B-D4) ===|java -ea -cp build deal.test.ComparisonExecutorTest'
  'fg|=== Running Address Chain Protocol / Normalized Slot Tests (ISSUE-0234 A-D1/A-D3/A-D9) ===|java -ea -cp build deal.test.AddressChainProtocolTest'
  'fg|=== Running Address Chain Lowering Tests (ISSUE-0405 ASSIGN/DELETE chains) ===|java -ea -cp build deal.test.AddressChainLoweringTest'
  'fg|=== Running Control Flow Lowering Tests (ISSUE-0409 BRANCH/LOOP/FOR_EACH/TRY_CATCH/THROW/BREAK/CONTINUE/DISCARD) ===|java -ea -cp build deal.test.ControlFlowLoweringTest'
  'fg|=== Running Evaluation Order Integration Tests (ISSUE-0410, decomposition tail) ===|java -ea -cp build deal.test.EvaluationOrderIntegrationTest'
  'fg|=== Running the Runtime Integration Matrix (ISSUE-0410: semantic oracle + shared LuaJIT + shared JVM) ===|java -ea -cp build deal.test.RuntimeIntegrationMatrixTest'
  'fg|=== Running Unicode Scalars Tests (ISSUE-0382, ISSUE-0232 D5) ===|java -ea -cp build deal.test.UnicodeScalarsTest'
  'fg|=== Running Boundary Realization Report Tests (ISSUE-0365 D4) ===|java -ea -cp build deal.test.BoundaryRealizationReportTest'
  'fg|=== Running Failure Contract Registry Tests (ISSUE-0285) ===|java -ea -cp build deal.test.FailureContractRegistryTest'
  'fg|=== Running Canonical JSON / Snapshot Digest Tests (ISSUE-0283) ===|java -ea -cp build deal.test.CanonicalJsonTest'
  'fg|=== Running Semantic IR Validator Tests (ISSUE-0286) ===|java -ea -cp build deal.test.SemanticIrValidatorTest'
  'fg|=== Running Boundary Table Corpus Tests (ISSUE-0366, wiki Verification 4) ===|java -ea -cp build deal.test.BoundaryTableCorpusTest'
  'fg|=== Running Boundary Integration Tests (ISSUE-0367, decomposition tail) ===|java -ea -cp build deal.test.BoundaryIntegrationTest'
  'fg|=== Running Control Flow Validator Tests (ISSUE-0408) ===|java -ea -cp build deal.test.ControlFlowValidatorTest'
  'fg|=== Running Semantic IR Dumper / ID Allocator Tests (ISSUE-0287) ===|java -ea -cp build deal.test.SemanticIrDumperTest'
  'fg|=== Running Semantic Table / Array Value Model Tests (ISSUE-0383 C2) ===|java -ea -cp build deal.test.SemanticTableTest'
  'fg|=== Running Container Ops Executor Tests (ISSUE-0384 C3) ===|java -ea -cp build deal.test.ContainerOpsExecutorTest'
  'fg|=== Running Comparison Selector Lowering Tests (ISSUE-0407) ===|java -ea -cp build deal.test.ComparisonSelectorLoweringTest'
  'fg|=== Running Container Lowering Arms Tests (ISSUE-0386) ===|java -ea -cp build deal.test.ContainerLoweringArmsTest'
  'fg|=== Running Container Claiming Seam Tests (ISSUE-0387) ===|java -ea -cp build deal.test.ContainerClaimingSeamTest'
  'fg|=== Running Container Integration Tests (ISSUE-0388, decomposition tail) ===|java -ea -cp build deal.test.ContainerIntegrationTest'
  'fg|=== Running Binding Core Lowering Tests (ISSUE-0444 binding-core child) ===|java -ea -cp build deal.test.BindingCoreLoweringTest'
  'fg|=== Running Closure Lowering Tests (ISSUE-0445 closure child) ===|java -ea -cp build deal.test.ClosureLoweringTest'
  'fg|=== Running Recursive Group Lowering Tests (ISSUE-0446 group child) ===|java -ea -cp build deal.test.RecursiveGroupLoweringTest'
  'fg|=== Running Function Binding Registry Tests (ISSUE-0447 registry child) ===|java -ea -cp build deal.test.FunctionBindingRegistryTest'
'fg|=== Running Binding Immutability Proof Tests (ISSUE-0448 proof child) ===|java -ea -cp build deal.test.BindingImmutabilityProofTest'
'fg|=== Running Adapter Creation Rule Tests (ISSUE-0449 creation-rule child) ===|java -ea -cp build deal.test.AdapterCreationRuleTest'
'fg|=== Running Adapter Shape Map / Payload Tests (ISSUE-0450 shape-map child) ===|java -ea -cp build deal.test.AdapterShapeMapPayloadTest'
'fg|=== Running Bindings Production Validation Tests (ISSUE-0451 B9 validation child) ===|java -ea -cp build deal.test.BindingsValidationTest'
'fg|=== Running Bindings Integration Verification (ISSUE-0452, sequencing item 9) ===|java -ea -cp build deal.test.BindingsIntegrationVerificationTest'
  'fg|=== Running Protected Path Ops Tests (ISSUE-0262) ===|java -ea -cp build deal.test.ProtectedPathOpsTest'
  'fg|=== Running Identity Carrier Package Tests (ISSUE-0309) ===|java -ea -cp build deal.test.CanonicalIdentityTest'
  'fg|=== Running Compiler Workspace Protocol Tests ===|java -ea -cp build deal.test.CompilerWorkspaceTest'
  'fg|=== Running Sidecar Schema Validator Tests (ISSUE-0348) ===|java -ea -cp build deal.test.conformance.SidecarSchemaValidatorTest'
  'fg|=== Running Sidecar Corpus Validation Tests (ISSUE-0349) ===|java -ea -cp build deal.test.conformance.SidecarCorpusValidationTest'
  'fg|=== Running Strict Manifest Parser Tests (ISSUE-0263 T2) ===|java -ea -cp build deal.project.StrictManifestParserTest'
  'fg|=== Running Output Config Resolver Tests (ISSUE-0264 T3) ===|java -ea -cp build deal.project.OutputConfigResolverTest'
  'fg|=== Running Project Locator Tests (ISSUE-0265 T4) ===|java -ea -cp build deal.project.ProjectLocatorTest'
  'fg|=== Running Module Identity Resolver Classifier Tests (ISSUE-0266 T5) ===|java -ea -cp build deal.module.ModuleIdentityResolverTest'
  'fg|=== Running Module Identity Assembly Tests (ISSUE-0268 T7) ===|java -ea -cp build deal.module.ModuleIdentityAssemblyTest'
  'fg|=== Running C FFI Declaration Validation and Forward Binding Tests (ISSUE-0162) ===|java -ea -cp build deal.test.FfiDeclarationValidatorTest'
  'fg|=== Running AST/Types Tests ===|java -ea -cp build deal.test.AstAndTypesTest'
  'fg|=== Running Types Bytes Tests (ISSUE-0308) ===|java -ea -cp build deal.test.TypesBytesTest'
  'fg|=== Running Directive Tests ===|java -ea -cp build deal.test.DirectiveTest'
  'fg|=== Running Lexer Tests ===|java -ea -cp build deal.test.LexerTest'
  'fg|=== Running Parser Tests ===|java -ea -cp build deal.test.ParserTest'
  'fg|=== Running Checker Tests ===|java -ea -cp build deal.test.CheckerTest'
  'fg|=== Running IR Dumper Tests ===|java -ea -cp build deal.test.IrDumperTest'
  'fg|=== Running IR Golden Tests ===|java -ea -cp build deal.test.IrGoldenTest'
  'fg|=== Running Type Descriptor Tests ===|java -ea -cp build deal.test.TypeDescriptorTest'
  'fg|=== Running Canonical Runtime Type Descriptor Tests (ISSUE-0310/0311/0314) ===|java -ea -cp build deal.test.CanonicalRuntimeTypeDescriptorTest'
  'fg|=== Running Runtime Type Matcher Tests (ISSUE-0312) ===|java -ea -cp build deal.test.RuntimeTypeMatcherTest'
  'fg|=== Running JS Backend Unit Tests ===|java -ea -cp build deal.test.JsBackendTest'
  'fg|=== Running JS E2E Tests ===|java -ea -cp build deal.test.JsE2eTest'
  'fg|=== Running Lua Backend Tests ===|java -ea -cp build deal.test.LuaBackendTest'
  'fg|=== Running Lua Backend Integration Tests ===|java -ea -cp build deal.test.LuaBackendIntegrationTest'
  'fg|=== Running Module System Tests ===|java -ea -cp build deal.test.ModuleSystemTest'
  'fg|=== Running Project Migration Integration Tests (ISSUE-0269 T8) ===|java -ea -cp build deal.test.ProjectMigrationIntegrationTest'
  'fg|=== Running Project Integration Gates (ISSUE-0270 T9: out-of-root both-backend gates) ===|java -ea -cp build deal.test.ProjectIntegrationGatesTest'
  'fg|=== Running Production Project-Graph Fixture Gates (ISSUE-0506 D11) ===|java -ea -cp build deal.test.ProjectGraphFixturesGatesTest'
  'fg|=== Running Source Module Resolver Tests (ISSUE-0267 T6) ===|java -ea -cp build deal.module.SourceModuleResolverTest'
  'fg|=== Running LuaJIT Async Export Invoker Tests (ISSUE-0417 component, ISSUE-0418 verification matrix) ===|java -ea -cp build:/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar org.junit.runner.JUnitCore deal.test.LuaJitAsyncExportInvokerTest'
'fg|=== Running Registry Async-Export Boundary Tests (ISSUE-0346 REGISTRY) ===|java -ea -cp build:/usr/share/java/junit4.jar:/usr/share/java/hamcrest-core.jar org.junit.runner.JUnitCore deal.test.RegistryAsyncExportBoundaryTest'
  'fg|=== Running Stdlib .d.deal Parse Tests ===|java -ea -cp build deal.test.StdlibDeclParseTest'
  'fg|=== Running Source Map Tests ===|java -ea -cp build deal.test.SourceMapTest'
  'fg|=== Running Runtime Source Location Tests ===|java -ea -cp build deal.test.RuntimeSourceLocationTest'
  'luajit|=== Running Runtime Library Tests ===|luajit test_runtime.lua
luajit test_runtime_int32.lua
WARNING: luajit not found, skipping runtime library tests'
  'luajit|=== Running Lua Async Export Driver Tests ===|luajit test/lua_async_export_driver_test.lua
WARNING: luajit not found, skipping async export driver tests'
  'luajit|=== Running Jsonable Runtime Tests ===|luajit test_runtime_jsonable.lua
WARNING: luajit not found, skipping jsonable runtime tests'
  'node|=== Running Jsonable Runtime JS Tests ===|node test_jsonable_js.js
WARNING: node not found, skipping jsonable runtime JS tests'
  'node|=== Running Host ABI Runtime JS Tests ===|node test_host_js.js
WARNING: node not found, skipping host ABI runtime JS tests'
  'luajit|=== Running Standard Library Tests ===|luajit test_stdlib.lua
WARNING: luajit not found, skipping standard library tests'
  'node|=== Running Standard Library JS Tests ===|node test_stdlib_js.js
WARNING: node not found, skipping standard library JS tests'
  'luajit|=== Running Async Nesting Stress Tests ===|luajit test_async_nesting.lua
WARNING: luajit not found, skipping async nesting stress tests'
  'fg||java -ea -cp build deal.test.StdlibContractTest'
  'fg|=== std/time.nowMillis Pre-Activation Pin (ISSUE-0369) ===|java -ea -cp build deal.test.StdlibTimePreActivationPinTest'
  'golden-ir|=== Stdlib Golden IR Check ===|java -ea -cp build deal.test.GenerateStdlibGoldenIr "$TEMP_FILE" 2>/dev/null'
  'fg|=== Running Conformance Harness Metadata Seam Tests ===|java -ea -cp build deal.test.ConformanceHarnessMetadataTest'
  # ISSUE-0457 registration: the DistributionHome tier-selection proofs
  # (project-local, classpath-resource, DEAL_HOME, and CWD tiers).
  'fg|=== Running Distribution Home Tier-Selection Tests (ISSUE-0457) ===|java -ea -cp build deal.test.DistributionHomeTest'
  # ISSUE-0458 registration: the transactional publication contract
  # suite (whole-project-artifact-publication Verification 1-7).
  'fg|=== Running Publication Stager Tests (ISSUE-0458) ===|java -ea -cp build deal.test.PublicationStagerTest'
  # ISSUE-0493 registration: the closed stdlib catalog and the
  # checked-fact recognition predicate (stdlib-operations-and-time-lock
  # D1, sequencing item 1).
  'fg|=== Running Stdlib Function Catalog / Checked-Fact Recognition Tests (ISSUE-0493) ===|java -ea -cp build deal.test.StdlibFunctionCatalogTest'
  # ISSUE-0494 registration: the STDLIB_CALL lowering arm — the
  # 20-id battery, argument operand completion, descriptor-kind-rule
  # boundaries, single-source policy stamping, dump determinism, the
  # validator negative, and the time lock (stdlib-operations-and-time-lock
  # D2, Verification 4; sequencing item 2).
  'fg|=== Running Stdlib STDLIB_CALL Lowering Tests (ISSUE-0494) ===|java -ea -cp build deal.test.StdlibCallLoweringTest'
  # ISSUE-0495 registration: the single stdlib algorithm executor —
  # the 20-id family batteries, exact projections, the console effect
  # contract, boundary precedence, and the combined T2/T1 drive
  # (stdlib-operations-and-time-lock D4/D5, Verification 1 and 3;
  # sequencing item 3).
  'fg|=== Running Shared Stdlib Semantics Tests (ISSUE-0495) ===|java -ea -cp build deal.test.SharedStdlibSemanticsTest'
  # ISSUE-0496 registration: the stdlib failure-projection wiring and
  # boundary realization reporting — exact projections, precedence,
  # realization reports, and the validator stdlibCell negative
  # (stdlib-operations-and-time-lock D6, Verification 2 and 4;
  # sequencing item 4).
  'fg|=== Running Stdlib Failure Projection / Boundary Realization Tests (ISSUE-0496) ===|java -ea -cp build deal.test.StdlibFailureProjectionTest'
  # ISSUE-0497 registration: the STDLIB_SEMANTICS claiming arms, the
  # time-lock negative proofs, and the stdlib-export value-read
  # disposition (stdlib-operations-and-time-lock D3/D8/D9,
  # Verification 4 and 5; sequencing item 5).
  'fg|=== Running Stdlib Claiming / Time-Lock Tests (ISSUE-0497) ===|java -ea -cp build deal.test.StdlibClaimingTimeLockTest'
  # ISSUE-0498 registration: the target-helper equivalence battery —
  # the retained Lua/JS/JVM helper candidates actually run against the
  # common algorithm plus the projection wiring, the known divergent
  # verdicts detected by the comparison, the wiring admission rule,
  # and the STDLIB_SEMANTICS promotion-evidence registry
  # (stdlib-operations-and-time-lock D7, Verification 6; sequencing
  # item 6).
  'fg|=== Running Stdlib Target-Helper Equivalence Battery (ISSUE-0498) ===|java -ea -cp build deal.test.StdlibEquivalenceBatteryTest'
  # ISSUE-0499 registration: the epic's decomposition tail — one
  # pipeline drives T1-T6 end-to-end (catalog, lowering, the shared
  # algorithms, the exact projections, the claiming seam plus the time
  # lock and the D3 disposition, and the equivalence-battery verdicts),
  # and the injected-fault variants prove the all-constituents contract
  # (stdlib-operations-and-time-lock Contracts §Integration verification
  # task, Verification 7-8; sequencing item 7, the last task).
  'fg|=== Running Stdlib Integration Verification (ISSUE-0499, decomposition tail) ===|java -ea -cp build deal.test.StdlibIntegrationTest'
)
