#!/bin/bash

# Complete suite selection used by both test and coverage runners.
TEST_MAINS=(
  'bg|=== Launching Conformance Tests (background) ===|java -ea -cp build deal.test.ConformanceTest test/conformance/'
  'bg|=== Launching JVM Conformance Tests (background; ISSUE-0102 origin — ISSUE-0168 capability accounting) ===|java -ea -cp build deal.test.JvmConformanceTest test/conformance/'
  'bg|=== Launching JS Conformance Tests (background) ===|java -ea -cp build deal.test.JsConformanceTest test/conformance/'
  'fg|=== Running Diagnostic Range Tests ===|java -ea -cp build deal.test.DiagnosticRangeTest'
  'fg|=== Running Diagnostic Classification Tests ===|java -ea -cp build deal.test.DiagnosticClassificationTest'
  'fg|=== Running Checked Project Builder Tests (ISSUE-0288) ===|java -ea -cp build deal.test.CheckedProjectBuilderTest'
  'fg|=== Running Lowering Support / Requirement Manifest Tests (ISSUE-0289) ===|java -ea -cp build deal.test.LoweringSupportTest'
  'fg|=== Running Boundary Executor Tests (ISSUE-0364 D3) ===|java -ea -cp build deal.test.BoundaryExecutorTest'
  'fg|=== Running Comparison Operand View and Executor Tests (ISSUE-0406, ISSUE-0234 B-D1/B-D2/B-D4) ===|java -ea -cp build deal.test.ComparisonExecutorTest'
  'fg|=== Running Address Chain Protocol / Normalized Slot Tests (ISSUE-0234 A-D1/A-D3/A-D9) ===|java -ea -cp build deal.test.AddressChainProtocolTest'
  'fg|=== Running Address Chain Lowering Tests (ISSUE-0405 ASSIGN/DELETE chains) ===|java -ea -cp build deal.test.AddressChainLoweringTest'
  'fg|=== Running Control Flow Lowering Tests (ISSUE-0409 BRANCH/LOOP/FOR_EACH/TRY_CATCH/THROW/BREAK/CONTINUE/DISCARD) ===|java -ea -cp build deal.test.ControlFlowLoweringTest'
  'fg|=== Running the Runtime Integration Matrix (ISSUE-0410: semantic oracle + shared LuaJIT + shared JVM) ===|java -ea -cp build deal.test.RuntimeIntegrationMatrixTest'
  'fg|=== Running Unicode Scalars Tests (ISSUE-0382, ISSUE-0232 D5) ===|java -ea -cp build deal.test.UnicodeScalarsTest'
  'fg|=== Running Failure Contract Registry Tests (ISSUE-0285) ===|java -ea -cp build deal.test.FailureContractRegistryTest'
  'fg|=== Running Failure Arm Authority Tests (ISSUE-0704) ===|java -ea -cp build deal.test.FailureArmAuthorityTest'
  'fg|=== Running Canonical Projection Parity Tests (ISSUE-0705) ===|java -ea -cp build deal.test.CanonicalProjectionParityTest'
  'fg|=== Running Canonical JSON / Snapshot Digest Tests (ISSUE-0283) ===|java -ea -cp build deal.test.CanonicalJsonTest'
  'fg|=== Running Semantic IR Validator Tests (ISSUE-0286) ===|java -ea -cp build deal.test.SemanticIrValidatorTest'
  'fg|=== Running Dynamic Resolution IR Tests (ISSUE-0531) ===|java -ea -cp build deal.test.DynamicResolutionIrTest'
  'fg|=== Running Control Flow Validator Tests (ISSUE-0408) ===|java -ea -cp build deal.test.ControlFlowValidatorTest'
  'fg|=== Running Container Ops Executor Tests (ISSUE-0384 C3) ===|java -ea -cp build deal.test.ContainerOpsExecutorTest'
  'fg|=== Running Container Lowering Arms Tests (ISSUE-0386) ===|java -ea -cp build deal.test.ContainerLoweringArmsTest'
  'fg|=== Running Closure Lowering Tests (ISSUE-0445 closure child) ===|java -ea -cp build deal.test.ClosureLoweringTest'
  'fg|=== Running Recursive Group Lowering Tests (ISSUE-0446 group child) ===|java -ea -cp build deal.test.RecursiveGroupLoweringTest'
  'fg|=== Running Function Binding Registry Tests (ISSUE-0447 registry child) ===|java -ea -cp build deal.test.FunctionBindingRegistryTest'
  'fg|=== Running Adapter Shape Map / Payload Tests (ISSUE-0450 shape-map child) ===|java -ea -cp build deal.test.AdapterShapeMapPayloadTest'
  'fg|=== Running Bindings Production Validation Tests (ISSUE-0451 B9 validation child) ===|java -ea -cp build deal.test.BindingsValidationTest'
  'fg|=== Running Dynamic Function Value Gate / Identity-Preserving Intrinsic Load Tests (ISSUE-0674) ===|java -ea -cp build deal.semantic.DynamicFunctionValueGateTest'
  'fg|=== Running Dynamic Producer Rule / Materialization Arms Tests (ISSUE-0675) ===|java -ea -cp build deal.semantic.DynamicProducerRuleTest'
  'fg|=== Running Strict Manifest Parser Tests (ISSUE-0263 T2) ===|java -ea -cp build deal.project.StrictManifestParserTest'
  'fg|=== Running Output Config Resolver Tests (ISSUE-0264 T3) ===|java -ea -cp build deal.project.OutputConfigResolverTest'
  'fg|=== Running Project Locator Tests (ISSUE-0265 T4) ===|java -ea -cp build deal.project.ProjectLocatorTest'
  'fg|=== Running Module Identity Resolver Classifier Tests (ISSUE-0266 T5) ===|java -ea -cp build deal.module.ModuleIdentityResolverTest'
  'fg|=== Running C FFI Declaration Validation and Forward Binding Tests (ISSUE-0162) ===|java -ea -cp build deal.test.FfiDeclarationValidatorTest'
  'fg|=== Running Types Bytes Tests (ISSUE-0308) ===|java -ea -cp build deal.test.TypesBytesTest'
  'fg|=== Running Directive Tests ===|java -ea -cp build deal.test.DirectiveTest'
  'fg|=== Running Lexer Tests ===|java -ea -cp build deal.test.LexerTest'
  'fg|=== Running Parser Tests ===|java -ea -cp build deal.test.ParserTest'
  'fg|=== Running Checker Tests ===|java -ea -cp build deal.test.CheckerTest'
  'fg|=== Running Amend Workspace Tests ===|java -ea -cp build deal.test.AmendWorkspaceTest'
  'fg|=== Running Repair Workspace Tests ===|java -ea -cp build deal.test.RepairWorkspaceTest'
  'fg|=== Running IR Dumper Tests ===|java -ea -cp build deal.test.IrDumperTest'
  'fg|=== Running IR Golden Tests ===|java -ea -cp build deal.test.IrGoldenTest'
  'fg|=== Running Type Descriptor Tests ===|java -ea -cp build deal.test.TypeDescriptorTest'
  'fg|=== Running Canonical Runtime Type Descriptor Tests (ISSUE-0310/0311/0314) ===|java -ea -cp build deal.test.CanonicalRuntimeTypeDescriptorTest'
  'fg|=== Running Runtime Type Matcher Tests (ISSUE-0312) ===|java -ea -cp build deal.test.RuntimeTypeMatcherTest'
  'fg|=== Running JS Backend Unit Tests ===|java -ea -cp build deal.test.JsBackendTest'
  'fg|=== Running Source Module Resolver Tests (ISSUE-0267 T6) ===|java -ea -cp build deal.module.SourceModuleResolverTest'
  'luajit|=== Running Runtime Library Tests ===|luajit test_runtime.lua
luajit test_runtime_int32.lua
WARNING: luajit not found, skipping runtime library tests'
  'luajit|=== Running Jsonable Runtime Tests ===|luajit test_runtime_jsonable.lua
WARNING: luajit not found, skipping jsonable runtime tests'
  'node|=== Running Jsonable Runtime JS Tests ===|node test_jsonable_js.js
WARNING: node not found, skipping jsonable runtime JS tests'
  'luajit|=== Running Standard Library Tests ===|luajit test_stdlib.lua
WARNING: luajit not found, skipping standard library tests'
  'node|=== Running Standard Library JS Tests ===|node test_stdlib_js.js
WARNING: node not found, skipping standard library JS tests'
  'fg|=== Running Distribution Home Tier-Selection Tests (ISSUE-0457) ===|java -ea -cp build deal.test.DistributionHomeTest'
  'fg|=== Running Publication Stager Tests (ISSUE-0458) ===|java -ea -cp build deal.test.PublicationStagerTest'
  'fg|=== Running Stdlib STDLIB_CALL Lowering Tests (ISSUE-0494) ===|java -ea -cp build deal.test.StdlibCallLoweringTest'
  'fg|=== Running Shared Stdlib Semantics Tests (ISSUE-0495) ===|java -ea -cp build deal.test.SharedStdlibSemanticsTest'
  'fg|=== Running Stdlib Target-Helper Equivalence Battery (ISSUE-0498) ===|java -ea -cp build deal.test.StdlibEquivalenceBatteryTest'
  'fg|=== Running Class Ops Executor Tests (ISSUE-0512 K-D4/K-D11) ===|java -ea -cp build deal.test.ClassOpsExecutorTest'
  'fg|=== Running Class New Lowering Tests (ISSUE-0512 LOCAL arm) ===|java -ea -cp build deal.test.ClassNewLoweringTest'
  'fg|=== Running Field Ops Executor Tests (ISSUE-0513 K-D6/K-D7) ===|java -ea -cp build deal.test.FieldOpsExecutorTest'
  'fg|=== Running Field Ops Lowering Tests (ISSUE-0513 K-D6/K-D7 arms) ===|java -ea -cp build deal.test.FieldOpsLoweringTest'
  'fg|=== Running Shared Factory Executor Tests (ISSUE-0514 K-D4/K-D5) ===|java -ea -cp build deal.test.SharedFactoryExecutorTest'
  'fg|=== Running Json Class Executor Tests (ISSUE-0515 K-D8/K-D9/K-D10/K-D11) ===|java -ea -cp build deal.test.JsonClassExecutorTest'
  'fg|=== Running Json Class Lowering Tests (ISSUE-0515 K-D8/K-D10) ===|java -ea -cp build deal.test.JsonClassLoweringTest'
  'fg|=== Running Class Construction Validator Tests (ISSUE-0516 K-D11) ===|java -ea -cp build deal.test.ClassConstructionValidatorTest'
  'fg|=== Running Default Semantic Planner Tests (ISSUE-0541) ===|java -ea -cp build deal.test.DefaultSemanticPlannerTest'
  'fg|=== Running Default IR Recorder Tests (ISSUE-0541) ===|java -ea -cp build deal.module.DefaultIrRecorderTest'
  'fg|=== Running Default Semantic Serializer Tests (ISSUE-0542) ===|java -ea -cp build deal.test.DefaultSemanticSerializerTest'
  'fg|=== Running Differential Gate Lanes Corpus Tests (ISSUE-0357) ===|java -ea -cp build deal.test.conformance.DifferentialGateLanesCorpusTest'
  'fg|=== Running Project Lowering Entry Tests (ISSUE-0634) ===|java -ea -cp build deal.test.ProjectLoweringTest'
  'fg|=== Running Body-Invocation Identity Tests (ISSUE-0635) ===|java -ea -cp build deal.test.BodyInvocationIdentityTest'
  'fg|=== Running JVM Production Project Emission Tests (ISSUE-0641) ===|java -ea -cp build deal.test.JvmProductionProjectEmissionTest'
  'fg|=== Running Lua Production Project Emission Tests (ISSUE-0640) ===|java -ea -cp build deal.test.LuaProductionProjectEmissionTest'
  'fg|=== Running Production Project Emission Tests (ISSUE-0642) ===|java -ea -cp build deal.test.ProductionProjectEmissionTest'
  'fg|=== Running Production Dispatch and Cutover Acceptance Tests (ISSUE-0643) ===|java -ea -cp build deal.test.ProductionDispatchTest'
  'fg|=== Running Host Module Load / JVM Host ABI Surface Tests (ISSUE-0650) ===|java -ea -cp build deal.test.HostModuleLoadEmissionTest'
  'fg|=== Running Cross-Module Call Realization Tests (ISSUE-0654) ===|java -ea -cp build deal.test.CrossModuleCallRealizationTest'
  'fg|=== Running FFI Plan Projection Oracle Tests (ISSUE-0667) ===|java -ea -cp build deal.test.FfiPlanProjectionOracleTest'
  'fg|=== Running Bytes Coverage and Element Contract Tests (ISSUE-0626) ===|java -ea -cp build deal.test.BytesCoverageTest'
  'fg|=== Running the Lane-Equivalent Bytes Production Drive (ISSUE-0707) ===|java -ea -cp build deal.test.BytesProductionDriveTest'
  'fg|=== Running Closed Composite Terminator Analysis Tests (ISSUE-0712) ===|java -ea -cp build deal.test.CompositeTerminatorAnalysisTest'
  'fg|=== Running the Truncating Int32 Remainder Prelude Tests (ISSUE-0714) ===|java -ea -cp build deal.test.Int32ModTruncPreludeTest'
  'fg|=== Running LuaJIT Chunk Local Bound Tests (ISSUE-0716) ===|java -ea -cp build deal.test.LuaJitChunkLocalBoundTest'
  'fg|=== Running Unreachable Tail Production Tests (ISSUE-0713) ===|java -ea -cp build deal.test.UnreachableTailProductionTest'
  'fg|=== Running Legacy Backend Retirement Audit Tests (ISSUE-0688) ===|java -ea -cp build deal.test.LegacyBackendRetirementTest'
  # ISSUE-0715 registration (the registered residual-carrier union acceptance
  # drive and the joint BYTES dual-mechanism acceptance; design source
  # residual-carrier-shapes-production-realization D1/D2/D3/D4 and the
  # acceptance; dispatched-corpus-production-realization R4;
  # luajit-jvm-single-lowering-production-cutover C1/C2/C7/C8;
  # dispatched-corpus-production-acceptance A1-A4;
  # bytes-value-semantics-production-realization B5): the eleven owned
  # residual fixtures (functions/direct-recursion,
  # functions/nested-scope-recursion, async-await/async-await-statement,
  # async-await/async-error-propagation, async-await/async-if-branching,
  # async-await/async-throw-catch, control-flow/return-in-try,
  # control-flow/return-inside-try, arithmetic/int32-div-rem-boundaries,
  # descriptors/canonical-class-atom-error-roundtrip,
  # error-handling/error-roundtrip) and the two dual-mechanism bytes fixtures
  # (bytes/bytes-boundary-order, bytes/bytes-async-closure) are enumerated on
  # both targets and skipped nowhere: each compiles through the release-owned
  # production invocation with zero E6005 CONSTRUCT_UNLOWERED/
  # RETAINED_ABI_DEFERRED/SHARED_EMITTER_COVERAGE, stages exactly one project
  # artifact with no retained emission and no route plan, stages byte-identical
  # artifact bytes on a repeated compile, and executes the staged artifact on
  # the real toolchain (luajit; javac --release 25 -proc:none plus java) with
  # the fixture's exported probe exercised — the sync and main probes called
  # directly, the async probes through the recorded async dispatch entry —
  # against the sidecar-pinned runtime-ok outcome (exit 0, both empty
  # transcripts), while the semantic oracle drives the same probes
  # event-for-event through the differential matrix (sync exports through a
  # driver entry asserting the pinned value, main through the project run,
  # async exports through the recorded async entry) with no divergence. The
  # two dual-mechanism fixtures are accepted jointly with landed BYTES: their
  # lowered units carry the nested declared bodies (the boundary-order write's
  # target/index/value operands) and the nested async declaration awaited in
  # place (bytes-async-closure's counter) together with the bytes element
  # contract, so a broken bytes realization or a broken residual mechanism
  # fails the same drive. The represented-tail monotonicity is asserted on the
  # union: the two tail fixtures keep their marked block non-OPEN with the tail
  # represented as a block member after the terminator and no implicit return
  # fabricated, and a unit-level both-branch-return body with a following
  # unreachable statement emits the tail only in the Lua artifact, keeps the
  # JVM reachability skip (compiling under javac), and never executes it. The
  # union invariants are asserted with the drive: the sidecars and fixture
  # sources are read and never written (byte-identical before and after), the
  # dispatched corpus count pin (390) stays landed, the three guard
  # identifiers stay landed, the retargeted control-flow pin files stay
  # present, and this drive is a foreground record of this manifest.
  'fg|=== Running Residual Carrier Shapes Acceptance Tests (ISSUE-0715) ===|java -ea -cp build deal.test.ResidualCarrierShapesAcceptanceTest'
)
