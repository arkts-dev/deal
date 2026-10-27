#!/bin/bash

# Complete suite selection used by both test and coverage runners.
#
# Records are "<class>|<banner>|<command>" with one command per line. The
# luajit/node records execute the real LuaJIT and Node runtime scripts, one
# process per line, and the runner preflights those tools before any build
# work: an absent tool fails the run with the named TOOL_MISSING outcome. A
# record therefore carries no warning, skip or fallback line: a missing tool
# never removes a supported case from the selection.
TEST_MAINS=(
  'node|=== Checking TypeScript Syntactic and Grammar Compatibility ===|node test/typescript/check-syntax.cjs'
  'node|=== Testing TypeScript Syntax Checker Failure Controls ===|node test/typescript/check-syntax.test.cjs'
  'fg|=== Running v1.5 Frontend Contracts ===|java -ea -cp build deal.test.Language15FrontendTest'
  'fg|=== Running v1.5 Production Runtime Contracts ===|java -ea -cp build deal.test.Language15RuntimeTest'
  'bg|=== Launching Production Corpus Conformance (JS/LuaJIT/JVM) ===|java -ea -cp build deal.test.CorpusConformanceTest test/conformance/'
  'fg|=== Running Diagnostic Range Tests ===|java -ea -cp build deal.test.DiagnosticRangeTest'
  'fg|=== Running Diagnostic Classification Tests ===|java -ea -cp build deal.test.DiagnosticClassificationTest'
  'fg|=== Running Checked Project Builder Tests (ISSUE-0288) ===|java -ea -cp build deal.test.CheckedProjectBuilderTest'
  'fg|=== Running Lowering Support / Requirement Manifest Tests (ISSUE-0289) ===|java -ea -cp build deal.test.LoweringSupportTest'
  'fg|=== Running Boundary Executor Tests (ISSUE-0364 D3) ===|java -ea -cp build deal.test.BoundaryExecutorTest'
  'fg|=== Running Comparison Operand View and Executor Tests (ISSUE-0406, ISSUE-0234 B-D1/B-D2/B-D4) ===|java -ea -cp build deal.test.ComparisonExecutorTest'
  'fg|=== Running Address Chain Lowering Tests (ISSUE-0405 ASSIGN/DELETE chains) ===|java -ea -cp build deal.test.AddressChainLoweringTest'
  'fg|=== Running Control Flow Lowering Tests (ISSUE-0409 BRANCH/LOOP/FOR_EACH/TRY_CATCH/THROW/BREAK/CONTINUE/DISCARD) ===|java -ea -cp build deal.test.ControlFlowLoweringTest'
  'fg|=== Running Unicode Scalars Tests (ISSUE-0382, ISSUE-0232 D5) ===|java -ea -cp build deal.test.UnicodeScalarsTest'
  'fg|=== Running Failure Contract Registry Tests (ISSUE-0285) ===|java -ea -cp build deal.test.FailureContractRegistryTest'
  'fg|=== Running Failure Arm Authority Tests (ISSUE-0704) ===|java -ea -cp build deal.test.FailureArmAuthorityTest'
  'fg|=== Running Canonical Projection Parity Tests (ISSUE-0705) ===|java -ea -cp build deal.test.CanonicalProjectionParityTest'
  'fg|=== Running Canonical JSON / Snapshot Digest Tests (ISSUE-0283) ===|java -ea -cp build deal.test.CanonicalJsonTest'
  'fg|=== Running Semantic IR Validator Tests (ISSUE-0286) ===|java -ea -cp build deal.test.SemanticIrValidatorTest'
  'fg|=== Running Dynamic Resolution IR Tests (ISSUE-0531) ===|java -ea -cp build deal.test.DynamicResolutionIrTest'
  'fg|=== Running Container Ops Executor Tests (ISSUE-0384 C3) ===|java -ea -cp build deal.test.ContainerOpsExecutorTest'
  'fg|=== Running Container Lowering Arms Tests (ISSUE-0386) ===|java -ea -cp build deal.test.ContainerLoweringArmsTest'
  'fg|=== Running Closure Lowering Tests (ISSUE-0445 closure child) ===|java -ea -cp build deal.test.ClosureLoweringTest'
  'fg|=== Running Function Binding Registry Tests (ISSUE-0447 registry child) ===|java -ea -cp build deal.test.FunctionBindingRegistryTest'
  'fg|=== Running Adapter Shape Map / Payload Tests (ISSUE-0450 shape-map child) ===|java -ea -cp build deal.test.AdapterShapeMapPayloadTest'
  'fg|=== Running Bindings Production Validation Tests (ISSUE-0451 B9 validation child) ===|java -ea -cp build deal.test.BindingsValidationTest'
  'fg|=== Running Dynamic Function Value Gate / Identity-Preserving Intrinsic Load Tests (ISSUE-0674) ===|java -ea -cp build deal.semantic.DynamicFunctionValueGateTest'
  'fg|=== Running Dynamic Producer Rule / Materialization Arms Tests (ISSUE-0675) ===|java -ea -cp build deal.semantic.DynamicProducerRuleTest'
  'fg|=== Running Strict Manifest Parser Tests (ISSUE-0263 T2) ===|java -ea -cp build deal.project.StrictManifestParserTest'
  'fg|=== Running Output Config Resolver Tests (ISSUE-0264 T3) ===|java -ea -cp build deal.project.OutputConfigResolverTest'
  'fg|=== Running Project Locator Tests (ISSUE-0265 T4) ===|java -ea -cp build deal.project.ProjectLocatorTest'
  'fg|=== Running Module Identity Resolver Classifier Tests (ISSUE-0266 T5) ===|java -ea -cp build deal.module.ModuleIdentityResolverTest'
  'fg|=== Running Nullable Type Tests ===|java -ea -cp build deal.test.TypesNullableTest'
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
  'fg|=== Running JS Semantic-IR Emitter Tests (ISSUE-0798/0800) ===|java -ea -cp build deal.test.JsSemanticEmitterTest'
  'fg|=== Running Source Module Resolver Tests (ISSUE-0267 T6) ===|java -ea -cp build deal.module.SourceModuleResolverTest'
  'luajit|=== Running Runtime Library Tests ===|luajit test_runtime.lua
luajit test_runtime_number.lua'
  'luajit|=== Running Jsonable Runtime Tests ===|luajit test_runtime_jsonable.lua'
  'node|=== Running Jsonable Runtime JS Tests ===|node test_jsonable_js.js'
  'luajit|=== Running Standard Library Tests ===|luajit test_stdlib.lua'
  'node|=== Running Standard Library JS Tests ===|node test_stdlib_js.js'
  'fg|=== Running Distribution Home Tier-Selection Tests (ISSUE-0457) ===|java -ea -cp build deal.test.DistributionHomeTest'
  'fg|=== Running Publication Stager Tests (ISSUE-0458) ===|java -ea -cp build deal.test.PublicationStagerTest'
  'fg|=== Running Stdlib STDLIB_CALL Lowering Tests (ISSUE-0494) ===|java -ea -cp build deal.test.StdlibCallLoweringTest'
  'fg|=== Running Shared Stdlib Semantics Tests (ISSUE-0495) ===|java -ea -cp build deal.test.SharedStdlibSemanticsTest'
  'fg|=== Running Class Ops Executor Tests (ISSUE-0512 K-D4/K-D11) ===|java -ea -cp build deal.test.ClassOpsExecutorTest'
  'fg|=== Running Class New Lowering Tests (ISSUE-0512 LOCAL arm) ===|java -ea -cp build deal.test.ClassNewLoweringTest'
  'fg|=== Running Field Ops Executor Tests (ISSUE-0513 K-D6/K-D7) ===|java -ea -cp build deal.test.FieldOpsExecutorTest'
  'fg|=== Running Field Ops Lowering Tests (ISSUE-0513 K-D6/K-D7 arms) ===|java -ea -cp build deal.test.FieldOpsLoweringTest'
  'fg|=== Running Json Class Executor Tests (ISSUE-0515 K-D8/K-D9/K-D10/K-D11) ===|java -ea -cp build deal.test.JsonClassExecutorTest'
  'fg|=== Running Json Class Lowering Tests (ISSUE-0515 K-D8/K-D10) ===|java -ea -cp build deal.test.JsonClassLoweringTest'
  'fg|=== Running Class Construction Validator Tests (ISSUE-0516 K-D11) ===|java -ea -cp build deal.test.ClassConstructionValidatorTest'
  'fg|=== Running Default Semantic Planner Tests (ISSUE-0541) ===|java -ea -cp build deal.test.DefaultSemanticPlannerTest'
  'fg|=== Running Default IR Recorder Tests (ISSUE-0541) ===|java -ea -cp build deal.module.DefaultIrRecorderTest'
  'fg|=== Running Default Semantic Serializer Tests (ISSUE-0542) ===|java -ea -cp build deal.test.DefaultSemanticSerializerTest'
  'fg|=== Running Project Lowering Entry Tests (ISSUE-0634) ===|java -ea -cp build deal.test.ProjectLoweringTest'
  'fg|=== Running Body-Invocation Identity Tests (ISSUE-0635) ===|java -ea -cp build deal.test.BodyInvocationIdentityTest'
  'fg|=== Running Error Rethrow Transport Production Tests (ISSUE-0751) ===|java -ea -cp build deal.test.ErrorRethrowTransportProductionTest'
  'fg|=== Running Error Identity Production Tests (ISSUE-0881) ===|java -ea -cp build deal.test.ErrorIdentityProductionTest'
  'fg|=== Running Narrowed Throw Admission Production Tests (ISSUE-0887) ===|java -ea -cp build deal.test.NarrowedThrowAdmissionProductionTest'
  'fg|=== Running Production Project Emission Tests (ISSUE-0642) ===|java -ea -cp build deal.test.ProductionProjectEmissionTest'
  'fg|=== Running Production Dispatch and Cutover Acceptance Tests (ISSUE-0643) ===|java -ea -cp build deal.test.ProductionDispatchTest'
  'fg|=== Running Async-Export Invoker Production Tests (ISSUE-0749) ===|java -ea -cp build deal.test.AsyncExportInvokerProductionTest'
  'fg|=== Running Host Module Load / JVM Host ABI Surface Tests (ISSUE-0650) ===|java -ea -cp build deal.test.HostModuleLoadEmissionTest'
  'fg|=== Running Cross-Module Call Realization Tests (ISSUE-0654) ===|java -ea -cp build deal.test.CrossModuleCallRealizationTest'
  'fg|=== Running Number Production Tests ===|java -ea -cp build deal.test.NumberProductionTest'
  'fg|=== Running Class Presence Production Tests ===|java -ea -cp build deal.test.ClassPresenceProductionTest'
  'fg|=== Running Class Presence Admission Tests ===|java -ea -cp build deal.test.ClassPresenceAdmissionTest'
  'fg|=== Running Container Boundary Production Tests ===|java -ea -cp build deal.test.ContainerBoundaryProductionTest'
  'fg|=== Running the @jsonable Helper Production Drive (ISSUE-0698) ===|java -ea -cp build deal.test.JsonableHelperProductionDriveTest'
  'fg|=== Running Closed Composite Terminator Analysis Tests (ISSUE-0712) ===|java -ea -cp build deal.test.CompositeTerminatorAnalysisTest'
  'fg|=== Running LuaJIT Chunk Local Bound Tests (ISSUE-0716) ===|java -ea -cp build deal.test.LuaJitChunkLocalBoundTest'
  'fg|=== Running JVM Method Size Production Tests ===|java -ea -cp build deal.test.JvmMethodSizeProductionTest'
  'fg|=== Running Nullable-Null Comparison Production Tests (ISSUE-0870) ===|java -ea -cp build deal.test.NullableNullComparisonTest'
  'fg|=== Running Optional-Null Field Read Production Tests (ISSUE-0883) ===|java -ea -cp build deal.test.OptionalNullFieldReadProductionTest'
  'fg|=== Running Null-Array Suffix Production Tests (ISSUE-0879) ===|java -ea -cp build deal.test.NullArraySuffixProductionTest'
  'fg|=== Running Function Arity Extension Position Production Tests (ISSUE-0877) ===|java -ea -cp build deal.test.FunctionArityExtensionProductionTest'
  'fg|=== Running Unreachable Tail Production Tests (ISSUE-0713) ===|java -ea -cp build deal.test.UnreachableTailProductionTest'
  'fg|=== Running For-Of Binding Mutation Production Tests (ISSUE-0876) ===|java -ea -cp build deal.test.ForOfBindingMutationProductionTest'
  'fg|=== Running Short-Circuit Assignment Production Tests (ISSUE-0890) ===|java -ea -cp build deal.test.ShortCircuitAssignmentProductionTest'
  'fg|=== Running Catch Binding Production Tests (ISSUE-0882) ===|java -ea -cp build deal.test.CatchBindingProductionTest'
  'fg|=== Running Catch Narrowing Isolation Production Tests (ISSUE-0888) ===|java -ea -cp build deal.test.CatchNarrowingIsolationProductionTest'
  'fg|=== Running Declared-Body Carrier Production Tests (ISSUE-0757, ISSUE-0758) ===|java -ea -cp build deal.test.DeclaredBodyCarrierProductionTest'
  'fg|=== Running Fail-Closed Guard Retargeting Tests (ISSUE-0702) ===|java -ea -cp build deal.test.FailClosedGuardRetargetingTest'
  'fg|=== Running Intrinsic Shadowing Production Tests (ISSUE-0878) ===|java -ea -cp build deal.test.IntrinsicShadowingProductionTest'
  'fg|=== Running Required-Tool Fail-Closed Preflight Tests (ISSUE-0874) ===|java -ea -cp build deal.test.ToolchainPreflightTest'
  'fg|=== Running C11 Runtime Deployment Set Tests (ISSUE-0902 D6) ===|java -ea -cp build deal.test.NativeRuntimeDeploymentTest'
  'fg|=== Running C11 Runtime ABI / Platform / Terminal Tests (ISSUE-0902) ===|java -ea -cp build deal.test.NativeRuntimeAbiTest'
  'fg|=== Running LLVM Backend Selection Tests (ISSUE-0903 D1) ===|java -ea -cp build deal.test.LlvmBackendSelectionTest'
  'fg|=== Running LLVM Project Emission Tests (ISSUE-0903 D2/D6) ===|java -ea -cp build deal.test.LlvmProjectEmissionTest'
  'fg|=== Running LLVM Runtime ABI Mirror Tests (ISSUE-0903) ===|java -ea -cp build deal.test.LlvmRuntimeAbiMirrorTest'
  'fg|=== Running LLVM Stage Classifier Tests (ISSUE-0903) ===|java -ea -cp build deal.test.LlvmStageClassifierTest'
)

# No record may carry a warning, skip or fallback line: a missing required tool
# is the runner's TOOL_MISSING preflight failure, never a downgraded record. This
# validation refuses a reintroduced skip line instead of letting it silently
# remove a supported case from the selection.
for _manifest_record in "${TEST_MAINS[@]}"; do
  case "$_manifest_record" in
    *WARNING:*|*"not found, skipping"*|*SKIP:*)
      echo "INTERNAL ERROR: gate-manifest record carries a skip/warning line:" >&2
      echo "$_manifest_record" >&2
      return 1 2>/dev/null || exit 1
      ;;
  esac
done
unset _manifest_record
