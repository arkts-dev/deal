package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The CALLS family's FUNCTION_ADAPT/CALLBACK_INVOKE differential corpus
 * (ISSUE-0582, sequencing step 6 first half): every seed is lowered
 * through the real production chain (lexer → parser → checker → checked
 * project → requirement manifest → the E7 full-program lowerer → the
 * closed validator) or built over the closed {@code deal.semantic-ir/1}
 * schema (the shapes the statically-resolved slice's lowerer admits plus
 * the hand-built E8010/thunk-re-evaluation controls), and then executed
 * on the semantic oracle and BOTH shared emitters' real artifacts (the
 * shared LuaJIT script run by the real {@code luajit} binary; the shared
 * JVM class compiled with the real {@code javac --release 25
 * -proc:none} and executed by the real {@code java}) via
 * {@link SemanticDifferentialHarness} — the identical validated
 * {@link LoweredModuleUnit} plus its {@link StructuredBodyTable} through
 * all three consumers.
 *
 * <p>The callback dispatch entries are driven by real top-level host
 * invocations with scripted arguments (the oracle's
 * {@code invokeCallback} surface; the Lua artifact's dispatch table
 * called from a host driver script; the JVM artifact's static dispatch
 * entry called from a host driver main) — never synthesized traces.</p>
 */
public class CallAdapterCallbackIntegrationTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // Fixed invocation facts
    // =========================================================================

    private static final ModuleId MODULE = new ModuleId("main");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
    private static final String INTERFACE_HASH = new deal.semantic.ir.ProjectInterfaceIndex(
        deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new deal.semantic.ir.ExternalModuleInterface(MODULE,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                List.of(), deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();
    private static final String LCH =
        LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH);
    private static final SemanticIrValidator.ComparisonFacts FACTS =
        new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
            SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH);
    private static final Path WORKSPACE = Path.of("build/call-adapter-cb-work");

    private static final String CONSOLE = "import * as console from \"std/console\"\n";

    private static final SemanticDifferentialHarness.TerminalExpectation SUCCESS =
        new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null");
    private static final SemanticDifferentialHarness.TerminalExpectation E8001 =
        new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8001", null);

    // =========================================================================
    // The production-chain slice helpers
    // =========================================================================

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private static ModuleResolver stdlibResolver() {
        return new ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, java.util.Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                var exports = deal.module.StdlibModuleResolver.stdlibExports(
                    Path.of("std").toAbsolutePath().toString());
                if (!exports.containsKey(modulePath)) {
                    throw new ModuleNotFoundException("Module not found: " + modulePath);
                }
                return exports.get(modulePath);
            }

            @Override
            public deal.checker.Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
    }

    private static CheckedSlice checkSlice(String source, String what) {
        LexResult lex = new Lexer(source, SOURCE_ID).tokenize();
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID).parse();
        check(parse.diagnostics().isEmpty(), what + ": parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver(SOURCE_ID, stdlibResolver());
        SymbolTable symTable = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), what + ": resolves cleanly: " + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult result = TypeChecker.check(SOURCE_ID, symTable, nr, parse.program());
        check(result.diagnostics().isEmpty(), what + ": checks cleanly: " + result.diagnostics());
        if (!result.diagnostics().isEmpty()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symTable, result);
    }

    private static List<ResolvedImport> importsOf(ProgramNode program) {
        List<ResolvedImport> imports = new ArrayList<>();
        for (deal.ast.StatementNode stmt : program.statements()) {
            if (stmt instanceof deal.ast.ImportDeclaration imp) {
                String resolved = switch (imp.modulePath()) {
                    case "std/console" -> "std.console";
                    case "std/string" -> "std.string";
                    case "std/table" -> "std.table";
                    case "std/json" -> "std.json";
                    case "std/math" -> "std.math";
                    case "std/time" -> "std.time";
                    default -> imp.modulePath();
                };
                imports.add(new ResolvedImport(imp.alias(), imp.modulePath(),
                    new ModuleId(resolved),
                    deal.semantic.ir.ExternalModuleKind.STDLIB));
            }
        }
        return imports;
    }

    private static List<deal.semantic.ir.ExportInterface> exportsOf(ProgramNode program) {
        List<deal.semantic.ir.ExportInterface> exports = new ArrayList<>();
        for (deal.ast.StatementNode stmt : program.statements()) {
            if (stmt instanceof deal.ast.ExportDeclaration export
                    && export.declaration() instanceof deal.ast.FunctionDeclaration function) {
                exports.add(new deal.semantic.ir.ExportInterface(function.name(), "function"));
            }
        }
        return exports;
    }

    private record LoweredSlice(LoweredModuleUnit unit, StructuredBodyTable table) {
    }

    /** The E7 full-program lowering over the real chain facts. */
    private static LoweredSlice lowerE7(String source, String what, Set<String> callbacks) {
        CheckedSlice slice = checkSlice(source, what);
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program()), exportsOf(slice.program()),
            CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(new ModuleFact(SOURCE_ID, MODULE, false, false,
            slice.program(), Map.of(), slice.symbols(), slice.checks(), List.of()));
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(
            invocation, MODULE, facts);
        check(built != null && !built.hasErrors() && built.input() != null,
            what + ": the checked project builds cleanly");
        if (built == null || built.hasErrors() || built.input() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(
            invocation, built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty(),
            what + ": the manifest computation is clean");
        if (manifests == null || !manifests.diagnostics().isEmpty()) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            manifests.manifests().get(0).constructCoverage();
        SemanticLowerer.FullProgramE7Result result =
            SemanticLowerer.lowerModuleFullProgramE7(input,
                SemanticProfile.DEAL_V1_2_INT32, coverage, built.index().interfaceIndexDigest(),
                REGISTRY_HASH, SemanticIdAllocator.over(List.of(MODULE)),
                Map.of(), Map.of(), callbacks);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + ": the E7 lowerer produces a validated unit: "
                + (result == null ? "null"
                    : (result.lowering() == null ? "null" : result.lowering().diagnostics())));
        if (result == null || result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return null;
        }
        return new LoweredSlice(result.lowering().unit(), result.lowering().table());
    }

    /** Runs the three-consumer differential matrix for one seed. */
    private static SemanticDifferentialHarness.Verdict runMatrix(LoweredSlice lowered,
            List<String> expectedEffects, SemanticDifferentialHarness.TerminalExpectation terminal,
            String what) {
        if (lowered == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            lowered.unit(), lowered.table(),
            new SemanticDifferentialHarness.Expectation(expectedEffects, terminal, what),
            WORKSPACE);
        check(verdict.pass(), what + ": the three-consumer matrix verdict passes:\n"
            + verdict.report());
        return verdict;
    }

    private static List<SemanticOp> ofKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                found.add(op);
            }
        }
        return found;
    }

    private static SemanticOp opById(LoweredModuleUnit unit, OpId id) {
        for (SemanticOp op : unit.ops()) {
            if (op.opId().equals(id)) {
                return op;
            }
        }
        return null;
    }

    private static BoundaryKind boundaryKind(SemanticOp op) {
        return ((KindPayload.BoundaryPayload) op.payload()).kind();
    }

    // =========================================================================
    // 1. The production-chain adapter matrix (live reassignment, VALUE,
    //    trailing parameter boundaries)
    // =========================================================================

    static void testSharedCellReassignment() {
        System.out.println("-- SHARED_CELL live reassignment: the cell re-read observes the "
            + "retargeted source (jvm-fv-lua-ref-reassigned-adapter) --");
        String source = CONSOLE
            + "function inc(x: int): int { return x + 1; }\n"
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function main(): null {\n"
            + "  let f: (x: int) => int = inc;\n"
            + "  let h: (a: int, b: int) => int = f;\n"
            + "  let r1: int = h(3, 9);\n"
            + "  f = dbl;\n"
            + "  let r2: int = h(3, 9);\n"
            + "  if (r1 === 4 && r2 === 6) { console.log(\"retarget-ok\"); }\n"
            + "}\n";
        LoweredSlice lowered = lowerE7(source, "adapter SHARED_CELL reassignment", Set.of());
        if (lowered == null) {
            return;
        }
        List<SemanticOp> adapts = ofKind(lowered.unit(), SemanticOpKind.FUNCTION_ADAPT);
        check(adapts.size() == 1
                && ((KindPayload.FunctionAdaptPayload) adapts.get(0).payload()).mode()
                    == CaptureMode.SHARED_CELL
                && ((KindPayload.FunctionAdaptPayload) adapts.get(0).payload()).source()
                    instanceof AdaptSourceRef.SharedCell,
            "the reassignable-binding source records SHARED_CELL {binding, generation}");
        SemanticDifferentialHarness.Verdict verdict = runMatrix(lowered,
            List.of("retarget-ok"), SUCCESS, "adapter SHARED_CELL reassignment");
        if (verdict != null) {
            // The live reassignment is observed at invocation time: the
            // second adapter invocation's source body is dbl (3*2=6),
            // never the creation-time inc — a stale-cell consumer fails
            // the effect projection even with coincidental first calls.
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.effects().stream()
                        .anyMatch(e -> e.kind() == SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE
                            && e.text().equals("retarget-ok")),
                    "reassignment seed: " + run.consumer()
                        + " observes the retargeted source");
            }
        }
    }

    static void testValueRetainedSource() {
        System.out.println("-- VALUE retained source: the function-expression source adapts "
            + "as VALUE; the retained closure executes per invocation --");
        String source = CONSOLE
            + "function mark(s: string, v: int): int { console.log(s); return v; }\n"
            + "export function main(): null {\n"
            + "  let f: (a: int, b: int) => int = function(x: int): int "
            + "{ return mark(\"thunk\", x * 3); };\n"
            + "  let r1: int = f(2, 9);\n"
            + "  let r2: int = f(5, 9);\n"
            + "  if (r1 === 6 && r2 === 15) { console.log(\"thunk-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        LoweredSlice lowered = lowerE7(source, "adapter VALUE", Set.of());
        if (lowered == null) {
            return;
        }
        List<SemanticOp> adapts = ofKind(lowered.unit(), SemanticOpKind.FUNCTION_ADAPT);
        check(adapts.size() == 1
                && ((KindPayload.FunctionAdaptPayload) adapts.get(0).payload()).mode()
                    == CaptureMode.VALUE
                && ((KindPayload.FunctionAdaptPayload) adapts.get(0).payload()).source()
                    instanceof AdaptSourceRef.Value,
            "the function-expression source records VALUE with the retained operand");
        runMatrix(lowered, List.of("thunk", "thunk", "thunk-ok"), SUCCESS, "adapter VALUE");
    }

    static void testAdapterParameterBoundaries() {
        System.out.println("-- Adapter parameter boundaries: the complete xN set runs before "
            + "the D15 protocol; the source receives only the leading M --");
        String source = CONSOLE
            + "function mark(s: string, v: int): int { console.log(s); return v; }\n"
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function main(): null {\n"
            + "  let f: (a: int, b: int) => int = dbl;\n"
            + "  let r: int = f(mark(\"first\", 5), mark(\"second\", 9));\n"
            + "  if (r === 10) { console.log(\"adapter-ok\"); }\n"
            + "}\n";
        LoweredSlice lowered = lowerE7(source, "adapter parameter boundaries", Set.of());
        if (lowered == null) {
            return;
        }
        // Both target arguments evaluate (trailing value dropped after
        // the xN checks); the source receives only the leading M.
        runMatrix(lowered, List.of("first", "second", "adapter-ok"), SUCCESS,
            "adapter parameter boundaries");
    }

    // =========================================================================
    // 2. The host-driven callback dispatch matrix
    // =========================================================================

    static void testCallbackDispatch() {
        System.out.println("-- CALLBACK_INVOKE: HOST_TO_DEAL parameters, DEAL_TO_HOST by the "
            + "body RETURN; scripted success and E8001 drives --");
        String source = "export function cb(x: int): int { return x + 10; }\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerE7(source, "callback dispatch", Set.of("cb"));
        if (lowered == null) {
            return;
        }
        List<SemanticOp> callbacks = ofKind(lowered.unit(), SemanticOpKind.CALLBACK_INVOKE);
        check(callbacks.size() == 1, "the unit records exactly one CALLBACK_INVOKE");
        if (callbacks.size() != 1) {
            return;
        }
        KindPayload.CallbackInvokePayload payload =
            (KindPayload.CallbackInvokePayload) callbacks.get(0).payload();
        check(callbacks.get(0).origin().parentOpId() == null,
            "the CALLBACK_INVOKE is top-level (no parentOpId)");
        List<SemanticOp> params = new ArrayList<>();
        for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
            params.add(opById(lowered.unit(), boundaryId));
        }
        check(params.size() == 1 && params.stream().allMatch(p ->
                boundaryKind(p) == BoundaryKind.HOST_TO_DEAL),
            "the callback runs one HOST_TO_DEAL parameter boundary");
        SemanticOp ret = opById(lowered.unit(), payload.returnBoundaryOpId());
        check(ret != null && boundaryKind(ret) == BoundaryKind.DEAL_TO_HOST,
            "the callback's single return boundary is DEAL_TO_HOST");

        // The scripted success drive: int 32 → int 42.
        SemanticDifferentialHarness.Verdict successVerdict =
            SemanticDifferentialHarness.runCallback(lowered.unit(), lowered.table(),
                payload.function(),
                List.of(new SemanticDifferentialHarness.CallbackArg.Int(32)),
                new SemanticDifferentialHarness.Expectation(List.of(),
                    new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("int:42"),
                    "callback scripted success"),
                WORKSPACE);
        check(successVerdict.pass(), "callback success drive: the three-consumer matrix "
            + "verdict passes:\n" + successVerdict.report());
        if (successVerdict.pass()) {
            for (SemanticRuntimeModel.ConsumerRun run : successVerdict.runs()) {
                boolean started = false;
                boolean succeeded = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.CALLBACK_INVOKE
                            && event.phase() == SemanticRuntimeModel.Phase.START
                            && event.parentOp() == null
                            && event.inputs().equals(List.of("int:32"))) {
                        started = true;
                    }
                    if (event.kind() == SemanticOpKind.CALLBACK_INVOKE
                            && event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                            && event.parentOp() == null
                            && "int:42".equals(event.output())) {
                        succeeded = true;
                    }
                }
                check(started && succeeded,
                    "callback success drive: " + run.consumer() + " runs the dispatch "
                        + "entry's START (scripted inputs, no parentOpId) and SUCCESS "
                        + "(checked value) events");
            }
        }

        // The scripted failing drive: a string argument fails the
        // HOST_TO_DEAL int boundary with E8001 at the boundary origin.
        SemanticDifferentialHarness.Verdict failureVerdict =
            SemanticDifferentialHarness.runCallback(lowered.unit(), lowered.table(),
                payload.function(),
                List.of(new SemanticDifferentialHarness.CallbackArg.Str("nope")),
                new SemanticDifferentialHarness.Expectation(List.of(),
                    new SemanticDifferentialHarness.TerminalExpectation.FailureWith(
                        "E8001", null),
                    "callback scripted boundary failure"),
                WORKSPACE);
        check(failureVerdict.pass(), "callback E8001 drive: the three-consumer matrix "
            + "verdict passes:\n" + failureVerdict.report());
        if (failureVerdict.pass()) {
            for (SemanticRuntimeModel.ConsumerRun run : failureVerdict.runs()) {
                boolean failed = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.CALLBACK_INVOKE
                            && event.phase() == SemanticRuntimeModel.Phase.FAILURE
                            && event.parentOp() == null
                            && event.error() != null
                            && event.error().code().equals("E8001")) {
                        failed = true;
                    }
                }
                check(failed, "callback E8001 drive: " + run.consumer() + " closes the "
                    + "dispatch entry with its FAILURE event carrying the propagated "
                    + "E8001");
            }
        }
    }

    // =========================================================================
    // Synthetic unit helpers (the hand-built adapter/callback units)
    // =========================================================================

    private static int nextOp = 1;
    private static int nextVal = 1;
    private static int nextBlock = 1;
    private static int nextFn = 1;

    private static OpId nextOpId() {
        return new OpId(MODULE, nextOp++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextVal++);
    }

    private static BlockId nextBlock() {
        return new BlockId(nextBlock++);
    }

    private static FunctionId nextFunction() {
        return new FunctionId(nextFn++);
    }

    private static SourceOrigin origin(OpId parent) {
        return new SourceOrigin(SOURCE_ID, SourceSpan.synthetic(SOURCE_ID),
            SourceOriginKind.SYNTHETIC, new AnchorId(0), parent);
    }

    private static OperationContractSnapshot contractFor(SemanticOpKind kind,
            KindPayload payload, OpResultType resultType, FailurePolicyId policy,
            List<RuntimeDescriptor> operandTypes, String digest) {
        ClosedSelector selector = payload instanceof KindPayload.SelectorCarrying carrying
            ? carrying.selector() : null;
        return new OperationContractSnapshot(OperationContractSnapshot.VERSION, kind, resultType,
            operandTypes, selector, payload, policy, List.of(), digest);
    }

    private static SemanticOp opWith(OpId id, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy,
            OpId parent, List<ValueId> operands, List<RuntimeDescriptor> operandTypes) {
        OperationContractSnapshot contract =
            contractFor(kind, payload, resultType, policy, operandTypes, "placeholder");
        String digest = ContractSnapshotCanonicalizer.digest(contract);
        contract = contractFor(kind, payload, resultType, policy, operandTypes, digest);
        return new SemanticOp(id, kind, origin(parent), result, resultType, operands,
            operandTypes, payload, policy, contract);
    }

    private static SemanticOp opWith(OpId id, SemanticOpKind kind, KindPayload payload,
            SemanticValue result, OpResultType resultType, FailurePolicyId policy,
            OpId parent) {
        return opWith(id, kind, payload, result, resultType, policy, parent, List.of(),
            List.of());
    }

    private static SemanticOp boundaryWithInput(OpId id, BoundaryKind kind,
            RuntimeDescriptor descriptor, FailurePolicyId policy, OpId parent, ValueId input) {
        return opWith(id, SemanticOpKind.BOUNDARY,
            new KindPayload.BoundaryPayload(kind, descriptor, input,
                new BoundaryRealization.RuntimeValidation("check")),
            null, null, policy, parent);
    }

    private static SemanticOp boundaryWith(OpId id, BoundaryKind kind,
            RuntimeDescriptor descriptor, FailurePolicyId policy, OpId parent) {
        return boundaryWithInput(id, kind, descriptor, policy, parent, nextValue());
    }

    private static LoweredModuleUnit unit(
            Map<FunctionId, LoweredFunction> functions,
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings,
            List<SemanticOp> ops) {
        return new LoweredModuleUnit(LoweredModuleUnit.FORMAT_VERSION,
            SemanticProfile.DEAL_V1_2_INT32, MODULE, INTERFACE_HASH, LCH, Set.of(), Map.of(),
            Map.of(), functions, new ModuleInitPlan(List.of(), new BlockId(0)),
            ExportPlan.empty(), bindings, ops);
    }

    private static StructuredBodyTable table(Map<BlockId, List<OpId>> blockOps,
                                             Map<OpId, BlockId> opBlocks) {
        return new StructuredBodyTable(blockOps, opBlocks);
    }

    private static void assertPass(Optional<deal.diagnostics.CompilerDiagnostic> diagnostic,
                                   String what) {
        check(diagnostic.isEmpty(), what + " passes validation"
            + (diagnostic.isPresent() ? ": " + diagnostic.get().message() : ""));
    }

    // =========================================================================
    // 3. The hand-built E8010 wrong-source control (the failure origin pin)
    // =========================================================================

    /**
     * The E8010 wrong-source unit: an adapter whose recorded VALUE source
     * closure carries a signature different from the recorded source
     * signature. The invocation runs the xN parameter boundaries, then
     * the D15 source-signature check fails with E8010
     * {@code FUNCTION_SIGNATURE} at the invoking CALL's origin with the
     * active frames — no boundary events, no source invocation.
     */
    private static LoweredModuleUnit e8010Unit(OpId constOp, OpId closureOp, OpId adaptOp,
            OpId paramBoundary, OpId returnBoundary, OpId allocOp, OpId returnOp, OpId callOp,
            ValueId argValue, ValueId closureResult, ValueId adaptResult, ValueId callResult,
            BlockId bodyBlock, RuntimeDescriptor.Func carriedSignature,
            RuntimeDescriptor.Func recordedSourceSignature,
            RuntimeDescriptor.Func targetSignature) {
        FunctionExecutionBinding.AdapterBinding adapter =
            new FunctionExecutionBinding.AdapterBinding(adaptOp, CaptureMode.VALUE,
                new AdaptSourceRef.Value(closureResult), recordedSourceSignature,
                targetSignature);
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(opWith(constOp, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(5)), argValue,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(closureOp, SemanticOpKind.CLOSURE_NEW,
            new KindPayload.ClosureNewPayload(new FunctionId(2), carriedSignature, List.of(),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock)),
            closureResult, carriedSignature, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(adaptOp, SemanticOpKind.FUNCTION_ADAPT,
            new KindPayload.FunctionAdaptPayload(recordedSourceSignature, targetSignature,
                CaptureMode.VALUE, new AdaptSourceRef.Value(closureResult), null),
            adaptResult, targetSignature, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(paramBoundary, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, callOp,
            argValue));
        ops.add(boundaryWith(returnBoundary, BoundaryKind.FUNCTION_RETURN,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, returnOp));
        ops.add(opWith(allocOp, SemanticOpKind.BINDING_ALLOC,
            new KindPayload.BindingAllocPayload(new BindingId(4), bodyBlock, false,
                BindingCellKind.DIRECT, 0),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(returnOp, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(argValue, new FunctionId(2), callOp,
                returnBoundary),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(callOp, SemanticOpKind.CALL,
            new KindPayload.CallPayload(deal.semantic.ir.CallMode.INDIRECT,
                new KindPayload.CallCallee.Static(adapter), targetSignature,
                List.of(paramBoundary), returnBoundary, null, null, null),
            callResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        Map<FunctionId, LoweredFunction> functions = Map.of(
            new FunctionId(2),
            new LoweredFunction(new FunctionId(2), carriedSignature, List.of(), bodyBlock));
        Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
            new LinkedHashMap<>();
        bindings.put(new FunctionAllocationIdentity(closureResult.id()),
            new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock));
        bindings.put(new FunctionAllocationIdentity(adaptResult.id()), adapter);
        return unit(functions, bindings, ops);
    }

    static void testE8010WrongSource() {
        System.out.println("-- E8010 wrong-source: the D15 source-signature check fails at "
            + "the invoking CALL origin with the active frames --");
        RuntimeDescriptor.Func carriedSignature =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        RuntimeDescriptor.Func recordedSourceSignature =
            new RuntimeDescriptor.Func(
                List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        RuntimeDescriptor.Func targetSignature =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        OpId constOp = nextOpId();
        OpId closureOp = nextOpId();
        OpId adaptOp = nextOpId();
        OpId paramBoundary = nextOpId();
        OpId returnBoundary = nextOpId();
        OpId allocOp = nextOpId();
        OpId returnOp = nextOpId();
        OpId callOp = nextOpId();
        ValueId argValue = nextValue();
        ValueId closureResult = nextValue();
        ValueId adaptResult = nextValue();
        ValueId callResult = nextValue();
        BlockId bodyBlock = nextBlock();
        LoweredModuleUnit unit = e8010Unit(constOp, closureOp, adaptOp, paramBoundary,
            returnBoundary, allocOp, returnOp, callOp, argValue, closureResult, adaptResult,
            callResult, bodyBlock, carriedSignature, recordedSourceSignature,
            targetSignature);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of(constOp, closureOp, adaptOp, callOp));
        blockOps.put(bodyBlock, List.of(allocOp, returnOp));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(constOp, new BlockId(0));
        opBlocks.put(closureOp, new BlockId(0));
        opBlocks.put(adaptOp, new BlockId(0));
        opBlocks.put(callOp, new BlockId(0));
        opBlocks.put(allocOp, bodyBlock);
        opBlocks.put(returnOp, bodyBlock);
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        assertPass(SemanticIrValidator.validate(unit, FACTS),
            "the E8010 wrong-source unit passes validation");
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            unit, bodyTable,
            new SemanticDifferentialHarness.Expectation(List.of(),
                new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8010",
                    SOURCE_ID + ":1:1"),
                "E8010 wrong-source"),
            WORKSPACE);
        check(verdict.pass(), "E8010 wrong-source: the three-consumer matrix verdict "
            + "passes (E8010 at the invoking CALL origin):\n" + verdict.report());
        if (verdict.pass()) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                boolean pinned = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.CALL
                            && event.phase() == SemanticRuntimeModel.Phase.FAILURE) {
                        SemanticRuntimeModel.ErrorSnapshot error = event.error();
                        pinned = error != null
                            && error.code().equals("E8010")
                            && error.message().equals("function signature mismatch: "
                                + "expected (int,int)->int, got (int)->int")
                            && error.expected().equals("(int,int)->int")
                            && error.actual().equals("(int)->int");
                    }
                }
                check(pinned, "E8010 wrong-source: " + run.consumer() + " pins the exact "
                    + "E8010 projection (expected (int,int)->int, actual (int)->int)");
            }
        }
    }

    // =========================================================================
    // 4. The hand-built REEVALUATE_THUNK per-invoke re-evaluation control
    // =========================================================================

    /**
     * The REEVALUATE_THUNK unit: the adapter's detached thunk block holds
     * exactly one CLOSURE_NEW. Every invocation re-executes the thunk, so
     * the source closure is freshly allocated per invocation (the two
     * CLOSURE_NEW SUCCESS atoms carry distinct allocation identities) and
     * the source body executes with the leading M arguments.
     */
    private static LoweredModuleUnit thunkUnit(OpId arg1Op, OpId arg2Op, OpId arg3Op,
            OpId arg4Op, OpId adaptOp, OpId thunkClosureOp, OpId addOp, OpId bodyConstOp,
            OpId param1, OpId param2, OpId param3, OpId param4, OpId returnBoundary,
            OpId allocOp, OpId loadOp, OpId returnOp, OpId call1Op, OpId call2Op,
            ValueId arg1, ValueId arg2, ValueId arg3, ValueId arg4, ValueId adaptResult,
            ValueId thunkClosureResult, ValueId loadResult, ValueId addResult,
            ValueId call1Result, ValueId call2Result, BlockId bodyBlock, BlockId thunkBlock,
            RuntimeDescriptor.Func sourceSignature, RuntimeDescriptor.Func targetSignature) {
        FunctionExecutionBinding.AdapterBinding adapter =
            new FunctionExecutionBinding.AdapterBinding(adaptOp,
                CaptureMode.REEVALUATE_THUNK, new AdaptSourceRef.Thunk(thunkBlock, List.of()),
                sourceSignature, targetSignature);
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(opWith(arg1Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(5)), arg1,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(arg2Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(9)), arg2,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(arg3Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(7)), arg3,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(arg4Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(9)), arg4,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(adaptOp, SemanticOpKind.FUNCTION_ADAPT,
            new KindPayload.FunctionAdaptPayload(sourceSignature, targetSignature,
                CaptureMode.REEVALUATE_THUNK, new AdaptSourceRef.Thunk(thunkBlock, List.of()),
                null),
            adaptResult, targetSignature, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(thunkClosureOp, SemanticOpKind.CLOSURE_NEW,
            new KindPayload.ClosureNewPayload(new FunctionId(2), sourceSignature, List.of(),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock)),
            thunkClosureResult, sourceSignature, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(param1, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, call1Op, arg1));
        ops.add(boundaryWithInput(param2, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, call1Op, arg2));
        ops.add(boundaryWithInput(param3, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, call2Op, arg3));
        ops.add(boundaryWithInput(param4, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, call2Op, arg4));
        ops.add(boundaryWith(returnBoundary, BoundaryKind.FUNCTION_RETURN,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, returnOp));
        ops.add(opWith(allocOp, SemanticOpKind.BINDING_ALLOC,
            new KindPayload.BindingAllocPayload(new BindingId(6), bodyBlock, false,
                BindingCellKind.DIRECT, 0),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(loadOp, SemanticOpKind.BINDING_LOAD,
            new KindPayload.BindingLoadPayload(new BindingId(6), 0),
            loadResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        ops.add(opWith(bodyConstOp, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(10)), arg1,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(addOp, SemanticOpKind.BINARY,
            new KindPayload.BinaryPayload(deal.semantic.ir.BinarySelector.INT32_ADD, null,
                null),
            addResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.INT32_RESULT, null,
            List.of(loadResult, arg1), List.of(RuntimeDescriptor.Int.INSTANCE,
                RuntimeDescriptor.Int.INSTANCE)));
        ops.add(opWith(returnOp, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(addResult, new FunctionId(2), call1Op,
                returnBoundary),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(call1Op, SemanticOpKind.CALL,
            new KindPayload.CallPayload(deal.semantic.ir.CallMode.INDIRECT,
                new KindPayload.CallCallee.Static(adapter), targetSignature,
                List.of(param1, param2), returnBoundary, null, null, null),
            call1Result, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        ops.add(opWith(call2Op, SemanticOpKind.CALL,
            new KindPayload.CallPayload(deal.semantic.ir.CallMode.INDIRECT,
                new KindPayload.CallCallee.Static(adapter), targetSignature,
                List.of(param3, param4), returnBoundary, null, null, null),
            call2Result, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        Map<FunctionId, LoweredFunction> functions = Map.of(
            new FunctionId(2),
            new LoweredFunction(new FunctionId(2), sourceSignature, List.of(), bodyBlock));
        Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
            new LinkedHashMap<>();
        bindings.put(new FunctionAllocationIdentity(adaptResult.id()), adapter);
        bindings.put(new FunctionAllocationIdentity(thunkClosureResult.id()),
            new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock));
        return unit(functions, bindings, ops);
    }

    static void testThunkReevaluation() {
        System.out.println("-- REEVALUATE_THUNK per-invoke re-evaluation: the detached thunk "
            + "block re-executes on every invocation (fresh source identity each run) --");
        RuntimeDescriptor.Func sourceSignature =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        RuntimeDescriptor.Func targetSignature =
            new RuntimeDescriptor.Func(
                List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        OpId arg1Op = nextOpId();
        OpId arg2Op = nextOpId();
        OpId arg3Op = nextOpId();
        OpId arg4Op = nextOpId();
        OpId adaptOp = nextOpId();
        OpId thunkClosureOp = nextOpId();
        OpId addOp = nextOpId();
        OpId bodyConstOp = nextOpId();
        OpId param1 = nextOpId();
        OpId param2 = nextOpId();
        OpId param3 = nextOpId();
        OpId param4 = nextOpId();
        OpId returnBoundary = nextOpId();
        OpId allocOp = nextOpId();
        OpId loadOp = nextOpId();
        OpId returnOp = nextOpId();
        OpId call1Op = nextOpId();
        OpId call2Op = nextOpId();
        ValueId arg1 = nextValue();
        ValueId arg2 = nextValue();
        ValueId arg3 = nextValue();
        ValueId arg4 = nextValue();
        ValueId adaptResult = nextValue();
        ValueId thunkClosureResult = nextValue();
        ValueId loadResult = nextValue();
        ValueId addResult = nextValue();
        ValueId call1Result = nextValue();
        ValueId call2Result = nextValue();
        BlockId bodyBlock = nextBlock();
        BlockId thunkBlock = nextBlock();
        LoweredModuleUnit unit = thunkUnit(arg1Op, arg2Op, arg3Op, arg4Op, adaptOp,
            thunkClosureOp, addOp, bodyConstOp, param1, param2, param3, param4,
            returnBoundary, allocOp, loadOp, returnOp, call1Op, call2Op,
            arg1, arg2, arg3, arg4, adaptResult, thunkClosureResult, loadResult,
            addResult, call1Result, call2Result, bodyBlock, thunkBlock,
            sourceSignature, targetSignature);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of(arg1Op, arg2Op, arg3Op, arg4Op, adaptOp,
            call1Op, call2Op));
        blockOps.put(bodyBlock, List.of(allocOp, loadOp, bodyConstOp, addOp, returnOp));
        blockOps.put(thunkBlock, List.of(thunkClosureOp));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(arg1Op, new BlockId(0));
        opBlocks.put(arg2Op, new BlockId(0));
        opBlocks.put(arg3Op, new BlockId(0));
        opBlocks.put(arg4Op, new BlockId(0));
        opBlocks.put(adaptOp, new BlockId(0));
        opBlocks.put(call1Op, new BlockId(0));
        opBlocks.put(call2Op, new BlockId(0));
        opBlocks.put(allocOp, bodyBlock);
        opBlocks.put(loadOp, bodyBlock);
        opBlocks.put(bodyConstOp, bodyBlock);
        opBlocks.put(addOp, bodyBlock);
        opBlocks.put(returnOp, bodyBlock);
        opBlocks.put(thunkClosureOp, thunkBlock);
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        assertPass(SemanticIrValidator.validate(unit, FACTS),
            "the REEVALUATE_THUNK unit passes validation");
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            unit, bodyTable,
            new SemanticDifferentialHarness.Expectation(List.of(), SUCCESS,
                "REEVALUATE_THUNK per-invoke re-evaluation"),
            WORKSPACE);
        check(verdict.pass(), "REEVALUATE_THUNK: the three-consumer matrix verdict "
            + "passes:\n" + verdict.report());
        if (verdict.pass()) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                List<String> closureAtoms = new ArrayList<>();
                boolean call1Atom = false;
                boolean call2Atom = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.CLOSURE_NEW
                            && event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                            && event.op().equals(thunkClosureOp)) {
                        closureAtoms.add(event.output());
                    }
                    if (event.kind() == SemanticOpKind.CALL
                            && event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                        if (event.op().equals(call1Op)) {
                            call1Atom = "int:15".equals(event.output());
                        }
                        if (event.op().equals(call2Op)) {
                            call2Atom = "int:17".equals(event.output());
                        }
                    }
                }
                check(closureAtoms.size() == 2 && !closureAtoms.get(0)
                        .equals(closureAtoms.get(1)),
                    "REEVALUATE_THUNK: " + run.consumer() + " re-executes the thunk per "
                        + "invocation (two fresh CLOSURE_NEW identities: " + closureAtoms
                        + ")");
                check(call1Atom && call2Atom,
                    "REEVALUATE_THUNK: " + run.consumer() + " runs the fresh source body "
                        + "with the leading M arguments (5→15, 7→17)");
            }
        }
    }

    // =========================================================================
    // 5. The hand-built through-adapter body failure (the frame push pin)
    // =========================================================================

    static void testAdapterBodyFailureFrames() {
        System.out.println("-- Through-adapter body failure: the identical completion error "
            + "propagates with the source body frame pushed --");
        // The source body converts a null via int() — E8001 inside the
        // body; the adapter invocation must push the source function id
        // onto the active frames (the failure snapshot's frames = [2]).
        RuntimeDescriptor.Func sourceSignature =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        RuntimeDescriptor.Func targetSignature =
            new RuntimeDescriptor.Func(
                List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        OpId nullConstOp = nextOpId();
        OpId closureOp = nextOpId();
        OpId adaptOp = nextOpId();
        OpId arg1Op = nextOpId();
        OpId arg2Op = nextOpId();
        OpId param1 = nextOpId();
        OpId param2 = nextOpId();
        OpId returnBoundary = nextOpId();
        OpId allocOp = nextOpId();
        OpId intrinsicOp = nextOpId();
        OpId returnOp = nextOpId();
        OpId callOp = nextOpId();
        ValueId nullValue = nextValue();
        ValueId closureResult = nextValue();
        ValueId adaptResult = nextValue();
        ValueId arg1 = nextValue();
        ValueId arg2 = nextValue();
        ValueId intrinsicResult = nextValue();
        ValueId callResult = nextValue();
        BlockId bodyBlock = nextBlock();
        FunctionExecutionBinding.AdapterBinding adapter =
            new FunctionExecutionBinding.AdapterBinding(adaptOp, CaptureMode.VALUE,
                new AdaptSourceRef.Value(closureResult), sourceSignature, targetSignature);
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(opWith(nullConstOp, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(ScalarValue.Null.INSTANCE), nullValue,
            RuntimeDescriptor.Null.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(closureOp, SemanticOpKind.CLOSURE_NEW,
            new KindPayload.ClosureNewPayload(new FunctionId(2), sourceSignature, List.of(),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock)),
            closureResult, sourceSignature, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(adaptOp, SemanticOpKind.FUNCTION_ADAPT,
            new KindPayload.FunctionAdaptPayload(sourceSignature, targetSignature,
                CaptureMode.VALUE, new AdaptSourceRef.Value(closureResult), null),
            adaptResult, targetSignature, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(arg1Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(3)), arg1,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(arg2Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(9)), arg2,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(param1, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, callOp, arg1));
        ops.add(boundaryWithInput(param2, BoundaryKind.FUNCTION_PARAMETER,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, callOp, arg2));
        ops.add(boundaryWith(returnBoundary, BoundaryKind.FUNCTION_RETURN,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, returnOp));
        ops.add(opWith(allocOp, SemanticOpKind.BINDING_ALLOC,
            new KindPayload.BindingAllocPayload(new BindingId(4), bodyBlock, false,
                BindingCellKind.DIRECT, 0),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(intrinsicOp, SemanticOpKind.INTRINSIC_CALL,
            new KindPayload.IntrinsicCallPayload(IntrinsicKind.INT_CONVERT, nullValue),
            intrinsicResult, RuntimeDescriptor.Int.INSTANCE,
            FailurePolicyId.INT_CONVERSION, null, List.of(nullValue),
            List.of(RuntimeDescriptor.Null.INSTANCE)));
        ops.add(opWith(returnOp, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(intrinsicResult, new FunctionId(2), callOp,
                returnBoundary),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(callOp, SemanticOpKind.CALL,
            new KindPayload.CallPayload(deal.semantic.ir.CallMode.INDIRECT,
                new KindPayload.CallCallee.Static(adapter), targetSignature,
                List.of(param1, param2), returnBoundary, null, null, null),
            callResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        Map<FunctionId, LoweredFunction> functions = Map.of(
            new FunctionId(2),
            new LoweredFunction(new FunctionId(2), sourceSignature, List.of(), bodyBlock));
        Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
            new LinkedHashMap<>();
        bindings.put(new FunctionAllocationIdentity(closureResult.id()),
            new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock));
        bindings.put(new FunctionAllocationIdentity(adaptResult.id()), adapter);
        LoweredModuleUnit unit = unit(functions, bindings, ops);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of(nullConstOp, closureOp, adaptOp, arg1Op, arg2Op,
            callOp));
        blockOps.put(bodyBlock, List.of(allocOp, intrinsicOp, returnOp));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(nullConstOp, new BlockId(0));
        opBlocks.put(closureOp, new BlockId(0));
        opBlocks.put(adaptOp, new BlockId(0));
        opBlocks.put(arg1Op, new BlockId(0));
        opBlocks.put(arg2Op, new BlockId(0));
        opBlocks.put(callOp, new BlockId(0));
        opBlocks.put(allocOp, bodyBlock);
        opBlocks.put(intrinsicOp, bodyBlock);
        opBlocks.put(returnOp, bodyBlock);
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        assertPass(SemanticIrValidator.validate(unit, FACTS),
            "the through-adapter body failure unit passes validation");
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            unit, bodyTable,
            new SemanticDifferentialHarness.Expectation(List.of(), E8001,
                "through-adapter body failure"),
            WORKSPACE);
        check(verdict.pass(), "through-adapter body failure: the three-consumer matrix "
            + "verdict passes:\n" + verdict.report());
        if (verdict.pass()) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                boolean framesPinned = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.CALL
                            && event.phase() == SemanticRuntimeModel.Phase.FAILURE) {
                        SemanticRuntimeModel.ErrorSnapshot error = event.error();
                        framesPinned = error != null
                            && error.code().equals("E8001")
                            && error.frames().equals(List.of("2"));
                    }
                }
                check(framesPinned, "through-adapter body failure: " + run.consumer()
                    + " pins the failure frames [2] (the source body frame was pushed "
                    + "for the invocation)");
            }
        }
    }

    // =========================================================================
    // 6. Negatives: validator rejections and the failing differential verdict
    // =========================================================================

    static void testValidatorNegatives() {
        System.out.println("-- Validator negatives: wrong parameter cells, wrong return "
            + "cells, wrong capture mode --");
        // (a) A CALL whose parameter boundary checks the wrong descriptor.
        {
            RuntimeDescriptor.Func carriedSignature =
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                    RuntimeDescriptor.Int.INSTANCE, false);
            RuntimeDescriptor.Func recordedSourceSignature =
                new RuntimeDescriptor.Func(
                    List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
                    RuntimeDescriptor.Int.INSTANCE, false);
            RuntimeDescriptor.Func targetSignature =
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                    RuntimeDescriptor.Int.INSTANCE, false);
            OpId constOp = nextOpId();
            OpId closureOp = nextOpId();
            OpId adaptOp = nextOpId();
            OpId paramBoundary = nextOpId();
            OpId returnBoundary = nextOpId();
            OpId allocOp = nextOpId();
            OpId returnOp = nextOpId();
            OpId callOp = nextOpId();
            ValueId argValue = nextValue();
            ValueId closureResult = nextValue();
            ValueId adaptResult = nextValue();
            ValueId callResult = nextValue();
            BlockId bodyBlock = nextBlock();
            LoweredModuleUnit unit = e8010Unit(constOp, closureOp, adaptOp, paramBoundary,
                returnBoundary, allocOp, returnOp, callOp, argValue, closureResult,
                adaptResult, callResult, bodyBlock, carriedSignature,
                recordedSourceSignature, targetSignature);
            // Mutate the parameter boundary's descriptor to string (the
            // declared parameter descriptor is int).
            List<SemanticOp> mutated = new ArrayList<>();
            for (SemanticOp op : unit.ops()) {
                if (op.opId().equals(paramBoundary)) {
                    KindPayload.BoundaryPayload payload =
                        (KindPayload.BoundaryPayload) op.payload();
                    mutated.add(boundaryWithInput(op.opId(), payload.kind(),
                        RuntimeDescriptor.String.INSTANCE, payload.descriptor() == null
                            ? FailurePolicyId.TYPE_DESCRIPTOR
                            : descriptorKindPolicy(RuntimeDescriptor.String.INSTANCE),
                        op.origin().parentOpId(), payload.input()));
                } else {
                    mutated.add(op);
                }
            }
            LoweredModuleUnit mutatedUnit = unit(unit.functions(), unit.functionBindings(),
                mutated);
            Optional<deal.diagnostics.CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(mutatedUnit, FACTS);
            check(diagnostic.isPresent() && "E6005".equals(diagnostic.get().code())
                    && diagnostic.get().message().contains("R-BOUNDARY-TRIPLE"),
                "wrong parameter descriptor: the validator rejects with E6005 "
                    + "R-BOUNDARY-TRIPLE: " + diagnostic);
        }
        // (b) A CALLBACK_INVOKE whose return boundary is not DEAL_TO_HOST.
        {
            RuntimeDescriptor.Func descriptor =
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                    RuntimeDescriptor.Int.INSTANCE, false);
            OpId closureOp = nextOpId();
            OpId paramBoundary = nextOpId();
            OpId returnBoundary = nextOpId();
            OpId allocOp = nextOpId();
            OpId loadOp = nextOpId();
            OpId addOp = nextOpId();
            OpId constOp = nextOpId();
            OpId returnOp = nextOpId();
            OpId callbackOp = nextOpId();
            ValueId closureResult = nextValue();
            ValueId loadResult = nextValue();
            ValueId addResult = nextValue();
            ValueId constResult = nextValue();
            BlockId bodyBlock = nextBlock();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(opWith(closureOp, SemanticOpKind.CLOSURE_NEW,
                new KindPayload.ClosureNewPayload(new FunctionId(2), descriptor, List.of(),
                    new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock)),
                closureResult, descriptor, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWithInput(paramBoundary, BoundaryKind.HOST_TO_DEAL,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, callbackOp,
                nextValue()));
            ops.add(boundaryWith(returnBoundary, BoundaryKind.FUNCTION_RETURN,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, returnOp));
            ops.add(opWith(allocOp, SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(new BindingId(4), bodyBlock, false,
                    BindingCellKind.DIRECT, 0),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(loadOp, SemanticOpKind.BINDING_LOAD,
                new KindPayload.BindingLoadPayload(new BindingId(4), 0),
                loadResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
                null));
            ops.add(opWith(constOp, SemanticOpKind.CONST,
                new KindPayload.ConstPayload(new ScalarValue.Int(10)), constResult,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(addOp, SemanticOpKind.BINARY,
                new KindPayload.BinaryPayload(deal.semantic.ir.BinarySelector.INT32_ADD,
                    null, null),
                addResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.INT32_RESULT,
                null, List.of(loadResult, constResult), List.of(
                    RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE)));
            ops.add(opWith(returnOp, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(addResult, new FunctionId(2), callbackOp,
                    returnBoundary),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(callbackOp, SemanticOpKind.CALLBACK_INVOKE,
                new KindPayload.CallbackInvokePayload(closureResult, descriptor,
                    List.of(paramBoundary), returnBoundary),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            Map<FunctionId, LoweredFunction> functions = Map.of(
                new FunctionId(2),
                new LoweredFunction(new FunctionId(2), descriptor, List.of(), bodyBlock));
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
                new LinkedHashMap<>();
            bindings.put(new FunctionAllocationIdentity(closureResult.id()),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock));
            LoweredModuleUnit unit = unit(functions, bindings, ops);
            Optional<deal.diagnostics.CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(unit, FACTS);
            check(diagnostic.isPresent() && "E6005".equals(diagnostic.get().code())
                    && diagnostic.get().message().contains("R-BOUNDARY-TRIPLE"),
                "wrong callback return cell: the validator rejects with E6005 "
                    + "R-BOUNDARY-TRIPLE: " + diagnostic);
        }
        // (c) A CALLBACK parameter boundary parented outside the invocation.
        {
            RuntimeDescriptor.Func descriptor =
                new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                    RuntimeDescriptor.Int.INSTANCE, false);
            OpId closureOp = nextOpId();
            OpId paramBoundary = nextOpId();
            OpId returnBoundary = nextOpId();
            OpId allocOp = nextOpId();
            OpId loadOp = nextOpId();
            OpId returnOp = nextOpId();
            OpId callbackOp = nextOpId();
            ValueId closureResult = nextValue();
            ValueId loadResult = nextValue();
            BlockId bodyBlock = nextBlock();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(opWith(closureOp, SemanticOpKind.CLOSURE_NEW,
                new KindPayload.ClosureNewPayload(new FunctionId(2), descriptor, List.of(),
                    new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock)),
                closureResult, descriptor, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWithInput(paramBoundary, BoundaryKind.HOST_TO_DEAL,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR,
                closureOp, nextValue()));
            ops.add(boundaryWith(returnBoundary, BoundaryKind.DEAL_TO_HOST,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, returnOp));
            ops.add(opWith(allocOp, SemanticOpKind.BINDING_ALLOC,
                new KindPayload.BindingAllocPayload(new BindingId(4), bodyBlock, false,
                    BindingCellKind.DIRECT, 0),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(loadOp, SemanticOpKind.BINDING_LOAD,
                new KindPayload.BindingLoadPayload(new BindingId(4), 0),
                loadResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
                null));
            ops.add(opWith(returnOp, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(loadResult, new FunctionId(2), callbackOp,
                    returnBoundary),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(callbackOp, SemanticOpKind.CALLBACK_INVOKE,
                new KindPayload.CallbackInvokePayload(closureResult, descriptor,
                    List.of(paramBoundary), returnBoundary),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            Map<FunctionId, LoweredFunction> functions = Map.of(
                new FunctionId(2),
                new LoweredFunction(new FunctionId(2), descriptor, List.of(), bodyBlock));
            Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
                new LinkedHashMap<>();
            bindings.put(new FunctionAllocationIdentity(closureResult.id()),
                new FunctionExecutionBinding.LoweredBody(new FunctionId(2), bodyBlock));
            LoweredModuleUnit unit = unit(functions, bindings, ops);
            Optional<deal.diagnostics.CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(unit, FACTS);
            check(diagnostic.isPresent() && "E6005".equals(diagnostic.get().code())
                    && diagnostic.get().message().contains("R-BOUNDARY-TRIPLE"),
                "wrong callback parameter parentage: the validator rejects with E6005 "
                    + "R-BOUNDARY-TRIPLE: " + diagnostic);
        }
    }

    private static FailurePolicyId descriptorKindPolicy(RuntimeDescriptor descriptor) {
        return descriptor instanceof RuntimeDescriptor.Func
            ? FailurePolicyId.FUNCTION_SIGNATURE : FailurePolicyId.TYPE_DESCRIPTOR;
    }

    static void testWrongCaptureModeNegative() {
        System.out.println("-- Wrong capture mode negative: the fail-closed emitters and the "
            + "differential verdict never pass inconsistent IR --");
        // The E8010 unit with the payload mode mutated to SHARED_CELL
        // while the source reference stays Value(closure): the mode and
        // the source shape disagree. The differential verdict over the
        // mutated unit must fail (the consumers fail closed — a
        // coincidental-output pass is the defect this control pins).
        RuntimeDescriptor.Func carriedSignature =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        RuntimeDescriptor.Func recordedSourceSignature =
            new RuntimeDescriptor.Func(
                List.of(RuntimeDescriptor.Int.INSTANCE, RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        RuntimeDescriptor.Func targetSignature =
            new RuntimeDescriptor.Func(List.of(RuntimeDescriptor.Int.INSTANCE),
                RuntimeDescriptor.Int.INSTANCE, false);
        OpId constOp = nextOpId();
        OpId closureOp = nextOpId();
        OpId adaptOp = nextOpId();
        OpId paramBoundary = nextOpId();
        OpId returnBoundary = nextOpId();
        OpId allocOp = nextOpId();
        OpId returnOp = nextOpId();
        OpId callOp = nextOpId();
        ValueId argValue = nextValue();
        ValueId closureResult = nextValue();
        ValueId adaptResult = nextValue();
        ValueId callResult = nextValue();
        BlockId bodyBlock = nextBlock();
        LoweredModuleUnit unit = e8010Unit(constOp, closureOp, adaptOp, paramBoundary,
            returnBoundary, allocOp, returnOp, callOp, argValue, closureResult, adaptResult,
            callResult, bodyBlock, carriedSignature, recordedSourceSignature,
            targetSignature);
        List<SemanticOp> mutated = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.FUNCTION_ADAPT) {
                KindPayload.FunctionAdaptPayload payload =
                    (KindPayload.FunctionAdaptPayload) op.payload();
                mutated.add(opWith(op.opId(), op.kind(),
                    new KindPayload.FunctionAdaptPayload(payload.sourceSignature(),
                        payload.targetSignature(), CaptureMode.SHARED_CELL,
                        payload.source(), payload.proof()),
                    op.result(), op.resultType(), op.failurePolicy(),
                    op.origin().parentOpId()));
            } else {
                mutated.add(op);
            }
        }
        LoweredModuleUnit mutatedUnit = unit(unit.functions(), unit.functionBindings(),
            mutated);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of(constOp, closureOp, adaptOp, callOp));
        blockOps.put(bodyBlock, List.of(allocOp, returnOp));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(constOp, new BlockId(0));
        opBlocks.put(closureOp, new BlockId(0));
        opBlocks.put(adaptOp, new BlockId(0));
        opBlocks.put(callOp, new BlockId(0));
        opBlocks.put(allocOp, bodyBlock);
        opBlocks.put(returnOp, bodyBlock);
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        boolean producedFailingVerdict;
        try {
            SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
                mutatedUnit, bodyTable,
                new SemanticDifferentialHarness.Expectation(List.of(), SUCCESS,
                    "wrong capture mode negative"),
                WORKSPACE);
            producedFailingVerdict = !verdict.pass();
            check(!verdict.pass(), "wrong capture mode: the differential verdict over the "
                + "mutated unit fails (never a coincidental pass):\n" + verdict.report());
        } catch (RuntimeException exception) {
            producedFailingVerdict = true;
            check(true, "wrong capture mode: the consumers fail closed on the "
                + "mode/source mismatch (" + exception.getClass().getSimpleName() + ")");
        }
        check(producedFailingVerdict,
            "wrong capture mode: the negative control produced a failing outcome");
    }

    // =========================================================================
    // 7. EmitOp totality pins and the production-mode realization
    // =========================================================================

    static void testProductionModeRealization() {
        System.out.println("-- Production-mode realization: the adapter and callback arms "
            + "emit through the production surfaces and run under the real toolchains --");
        String source = CONSOLE
            + "function inc(x: int): int { return x + 1; }\n"
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function cb(x: int): int { return x + 10; }\n"
            + "export function main(): null {\n"
            + "  let f: (x: int) => int = inc;\n"
            + "  let h: (a: int, b: int) => int = f;\n"
            + "  let r1: int = h(3, 9);\n"
            + "  f = dbl;\n"
            + "  let r2: int = h(3, 9);\n"
            + "  if (r1 === 4 && r2 === 6) { console.log(\"prod-ok\"); }\n"
            + "}\n";
        LoweredSlice lowered = lowerE7(source, "production-mode realization", Set.of("cb"));
        if (lowered == null) {
            return;
        }
        try {
            String lua = deal.codegen.lua.LuaSemanticEmitter.emitProductionModule(
                lowered.unit(), lowered.table(), true);
            check(lua.contains("__adaptInvoke") && lua.contains("__callbacks[\"cb"),
                "the production shared-LuaJIT artifact realizes the adapter protocol "
                    + "and the callback dispatch entry");
            Path script = WORKSPACE.resolve("prod-adapter-cb.lua");
            Files.writeString(script, lua, StandardCharsets.UTF_8);
            Process luaRun = new ProcessBuilder("luajit",
                script.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
            String luaOutput = new String(luaRun.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int luaExit = luaRun.waitFor();
            check(luaExit == 0 && "prod-ok\n".equals(luaOutput),
                "the production shared-LuaJIT adapter/callback artifact runs the "
                    + "reassigned adapter with the retained production output (exit "
                    + luaExit + ", output " + luaOutput.trim() + ")");

            deal.codegen.jvm.JvmSemanticEmitter.EmissionResult emission =
                deal.codegen.jvm.JvmSemanticEmitter.emitProductionModule(lowered.unit(),
                    lowered.table(), true, "AdapterCbProdMain");
            check(emission.source().contains("JvmRuntime.invokeAdapter")
                    && emission.source().contains("public static Object cb"),
                "the production shared-JVM artifact realizes the adapter protocol and "
                    + "the callback dispatch entry");
            Path sourceFile = WORKSPACE.resolve("AdapterCbProdMain.java");
            Files.writeString(sourceFile, emission.source(), StandardCharsets.UTF_8);
            Path classes = WORKSPACE.resolve("prod-adapter-cb-classes");
            Files.createDirectories(classes);
            String classpath = System.getProperty("java.class.path", "");
            Process compile = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                sourceFile.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
            String compileOutput = new String(compile.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int compileExit = compile.waitFor();
            check(compileExit == 0,
                "the production shared-JVM adapter/callback artifact compiles: "
                    + compileOutput.replace("\n", "\\n"));
            if (compileExit == 0) {
                Process run = new ProcessBuilder("java", "-cp",
                    classpath + java.io.File.pathSeparator + classes, "AdapterCbProdMain")
                    .redirectErrorStream(true).start();
                String runOutput = new String(run.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                int runExit = run.waitFor();
                check(runExit == 0 && "prod-ok\n".equals(runOutput),
                    "the production shared-JVM adapter/callback artifact runs the "
                        + "reassigned adapter with the retained production output (exit "
                        + runExit + ", output " + runOutput.trim() + ")");
            }
        } catch (java.io.IOException | InterruptedException exception) {
            fail("production-mode realization infrastructure failure: "
                + exception.getMessage());
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        try {
            Files.createDirectories(WORKSPACE);
        } catch (java.io.IOException ignored) {
            // the harness creates its workspace per run
        }
        testSharedCellReassignment();
        testValueRetainedSource();
        testAdapterParameterBoundaries();
        testCallbackDispatch();
        testE8010WrongSource();
        testThunkReevaluation();
        testAdapterBodyFailureFrames();
        testValidatorNegatives();
        testWrongCaptureModeNegative();
        testProductionModeRealization();
        System.out.println("CallAdapterCallbackIntegrationTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
