#!/bin/bash

# Complete suite selection used by both test and coverage runners.
TEST_MAINS=(
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
  # ISSUE-0698 (the @jsonable helper operations on the one-lowering
  # production path; design source dispatched-corpus-production-realization
  # R3 and the helper contract, luajit-jvm-single-lowering-production-
  # cutover C1/C2/C4): the family production drive — every fixture of the
  # family (the 30 sidecar fixtures of backend-runtime/jsonable/**, the
  # near-collision and helper-export-keys structural fixtures, and the
  # pinned compile-error fixture) compiles through the release-owned
  # production invocation on LuaJIT and JVM with zero E6005, publishes its
  # artifact, and executes under the real toolchain with the sidecar
  # byte-exact (the runtime-ok transcripts and exit codes; the cyclic-table
  # failure's code/message/span/expected/actual at the raw corpus
  # coordinates). The helper surface is pinned in the produced unit: exactly
  # one generated closure and one LoweredBody binding per class helper, one
  # EXPORT_PUBLISH and one recorded EXTERNAL_ENTRY per helper export, the
  # same-module call resolving the declaration's generated closure identity
  # through the landed LoweredBody call shape, the cross-module call bound
  # through EXPORT_READ plus the closed ExternalFunction SHARED_BODY binding
  # over the callee's recorded entry, the near-collision identifier changing
  # no outcome, and a non-@jsonable class gaining no helper. The combined
  # dependency step runs the oracle over every fixture of the family through
  # the same one-lowering closure (the runtime-ok success or the pinned
  # error tuple at the raw
  # corpus coordinates): each fixture's main runs through its own entry
  # delegation (the fixture lowered as the entry) exactly once before the
  # ordered non-main export loop's driver leg, the cross-module helper call
  # succeeds and the pinned helper JSON failure renders the cycle tuple at
  # the invoking call expression, so the drive fails if the canonical
  # failure projection authority is broken. The three-consumer regressions close the walk's
  # remaining arms: a helper invoked through a function-typed parameter
  # (f(w) inside the callee body) renders its pinned JSON failure at the
  # f(w) call expression in the oracle, the LuaJIT artifact, and the JVM
  # artifact alike; a class instance inside an array nested in a table
  # field is rejected by all three consumers (the table-content walk stays
  # JSON-shaped); a nested class's table field spells its keys in
  # first-insertion order and its int/number leaves by their own variant
  # (exact text on all three consumers); the recursive nested-class
  # walk stops at the shared JSON depth bound (511 chain links succeed,
  # 512 fail through the walk arm); the oracle's array executor view stays
  # live behind a class field's in-place element replacement, append, and
  # deletion (exact text for replacement and append, outcome parity for
  # the deleted missing slot); a null element in (int | null)[] roundtrips
  # on all three consumers while the same document stays rejected by
  # int[]; a read-derived numeric variant carrier serializes at a
  # declared number field and array element with its own variant's
  # spelling, and the carrier produced by an omitted nested-class default
  # conforms at its declared number, number | null, and number[] positions
  # (with its explicit-value control). The pinned-failure sidecar
  # comparison is covered by negative controls for unexpected process
  # stdout, an incorrect process exit status, or wrong stderr; a main-only
  # control proves the oracle's main leg captures a deliberate main
  # failure exactly once; and the inconsistent-fact negatives prove that a
  # missing generated helper export fails the production arm closed with
  # one E6005 through the producer guard staging nothing (both targets), a
  # missing recorded callee entry fails the arm closed the same way through
  # a checker-valid cross-module call to an entry-delegation-owned export,
  # and a removed EXPORT_PUBLISH publication and a removed recorded
  # EXTERNAL_ENTRY are each rejected by both production emitters with the
  # named producer defect.
  'fg|=== Running the @jsonable Helper Production Drive (ISSUE-0698) ===|java -ea -cp build deal.test.JsonableHelperProductionDriveTest'
  'fg|=== Running Closed Composite Terminator Analysis Tests (ISSUE-0712) ===|java -ea -cp build deal.test.CompositeTerminatorAnalysisTest'
  'fg|=== Running the Truncating Int32 Remainder Prelude Tests (ISSUE-0714) ===|java -ea -cp build deal.test.Int32ModTruncPreludeTest'
  'fg|=== Running LuaJIT Chunk Local Bound Tests (ISSUE-0716) ===|java -ea -cp build deal.test.LuaJitChunkLocalBoundTest'
  'fg|=== Running Unreachable Tail Production Tests (ISSUE-0713) ===|java -ea -cp build deal.test.UnreachableTailProductionTest'
  'fg|=== Running Nested Group Production Tests (ISSUE-0731) ===|java -ea -cp build deal.test.NestedGroupProductionTest'
  'fg|=== Running Self-Alias Production Tests (ISSUE-0743) ===|java -ea -cp build deal.test.SelfAliasProductionTest'
)
