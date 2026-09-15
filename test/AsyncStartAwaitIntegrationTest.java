package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
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
import deal.semantic.ModuleRoute;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BindingCellKind;
import deal.semantic.ir.BindingId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.BoundaryRealization;
import deal.semantic.ir.ClosedSelector;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ContractSnapshotCanonicalizer;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExportInterface;
import deal.semantic.ir.ExportPlan;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FailurePolicyId;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.InternalResultType;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.LoweringContextHash;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleInitPlan;
import deal.semantic.ir.OpId;
import deal.semantic.ir.OpResultType;
import deal.semantic.ir.OperationContractSnapshot;
import deal.semantic.ir.ParameterBoundaryMode;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticCapability;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SemanticValue;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StdlibFunctionId;
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
 * The CALLS family's ASYNC_START/AWAIT differential corpus (ISSUE-0583,
 * sequencing step 6 second half): every seed is lowered through the real
 * production chain (lexer → parser → checker → checked project →
 * requirement manifest → the E7 full-program lowerer → the closed
 * validator) or built over the closed {@code deal.semantic-ir/1} schema
 * (the FIFO two-task drain and the negative controls), and then executed
 * on the semantic oracle and BOTH shared emitters' real artifacts (the
 * shared LuaJIT script run by the real {@code luajit} binary; the shared
 * JVM class compiled with the real {@code javac --release 25
 * -proc:none} and executed by the real {@code java}) via
 * {@link SemanticDifferentialHarness} — the identical validated project
 * closure through all three consumers.
 *
 * <p>The async-entry dispatch entries are driven by real top-level host
 * invocations with scripted arguments and scripted host terminals (the
 * oracle's {@code invokeAsyncEntry} surface; the Lua artifacts' dispatch
 * entries called from a host driver script under the deferred-main flag;
 * the JVM artifacts' static dispatch entries called from a host driver
 * main) — never synthesized traces. The D13 modes covered: the DEAL body
 * task (LuaJIT coroutine task / JVM serial-executor future), the
 * adapter-over-async shape with its ELIDED_BY_ADAPTER nested source, the
 * async host shape/completion (bad handle, value completion, thrown
 * completion), cross-module async (the caller's alias token completing
 * through the callee canonical token with the completion check always at
 * the {@code AWAIT} site), and the deterministic FIFO drain pinned by
 * ordered side effects.</p>
 */
public class AsyncStartAwaitIntegrationTest {

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
    private static final ModuleId MODULE_A = new ModuleId("a");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
    private static final String INTERFACE_HASH = new ProjectInterfaceIndex(
        ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new deal.semantic.ir.ExternalModuleInterface(MODULE,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                List.of(), deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();
    private static final String LCH =
        LoweringContextHash.of(SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH);
    private static final SemanticIrValidator.ComparisonFacts FACTS =
        new SemanticIrValidator.ComparisonFacts(INTERFACE_HASH,
            SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH);
    private static final Path WORKSPACE = Path.of("build/async-start-await-work");

    private static final String CONSOLE = "import * as console from \"std/console\"\n";

    private static final SemanticDifferentialHarness.TerminalExpectation SUCCESS =
        new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null");

    // =========================================================================
    // The production-chain slice helpers
    // =========================================================================

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    private static ModuleResolver stdlibResolver() {
        return new ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                var exports = deal.module.StdlibModuleResolver.stdlibExports(
                    Path.of("std").toAbsolutePath().toString());
                if (!exports.containsKey(modulePath)) {
                    throw new ModuleNotFoundException("Module not found: " + modulePath);
                }
                return exports.get(modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                return null;
            }
        };
    }

    /** A resolver serving the stdlib plus the given module-path export maps. */
    private static ModuleResolver resolverWith(Map<String, Map<String, deal.types.Type>>
            moduleExports) {
        return new ModuleResolver() {
            @Override
            public Map<String, deal.types.Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                if (moduleExports.containsKey(modulePath)) {
                    return moduleExports.get(modulePath);
                }
                var exports = deal.module.StdlibModuleResolver.stdlibExports(
                    Path.of("std").toAbsolutePath().toString());
                if (!exports.containsKey(modulePath)) {
                    throw new ModuleNotFoundException("Module not found: " + modulePath);
                }
                return exports.get(modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className, String modulePath,
                    String importingModule) throws ModuleNotFoundException {
                return null;
            }
        };
    }

    private static CheckedSlice checkSlice(String source, ModuleResolver resolver,
                                           String what) {
        LexResult lex = new Lexer(source, SOURCE_ID).tokenize();
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID).parse();
        check(parse.diagnostics().isEmpty(), what + ": parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver(SOURCE_ID, resolver);
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

    private static List<ResolvedImport> importsOf(ProgramNode program,
            Map<String, ExternalModuleKind> kinds) {
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
                    case "./a" -> "a";
                    default -> imp.modulePath();
                };
                imports.add(new ResolvedImport(imp.alias(), imp.modulePath(),
                    new ModuleId(resolved),
                    kinds.getOrDefault(imp.modulePath(), ExternalModuleKind.STDLIB)));
            }
        }
        return imports;
    }

    private static List<ExportInterface> exportsOf(ProgramNode program) {
        List<ExportInterface> exports = new ArrayList<>();
        for (deal.ast.StatementNode stmt : program.statements()) {
            if (stmt instanceof deal.ast.ExportDeclaration export
                    && export.declaration() instanceof deal.ast.FunctionDeclaration function) {
                exports.add(new ExportInterface(function.name(), "function"));
            }
        }
        return exports;
    }

    private record LoweredSlice(LoweredModuleUnit unit, StructuredBodyTable table) {
    }

    /** The E7 full-program lowering of one module over the real chain facts. */
    private static LoweredSlice lowerE7(String source, String what, Set<String> callbacks) {
        CheckedSlice slice = checkSlice(source, stdlibResolver(), what);
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program(), Map.of()), exportsOf(slice.program()),
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

    private record CheckedProject(deal.semantic.CheckedProjectBuildResult built,
                                  List<Map<ConstructKind, List<SemanticOpKind>>> coverage) {
    }

    /** Builds the checked project + requirement manifests of the given modules. */
    private static CheckedProject buildProject(List<ModuleFact> facts, ModuleId entry) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(
            invocation, entry, facts);
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            "the checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty(),
            "the manifest computation is clean: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()) {
            return null;
        }
        List<Map<ConstructKind, List<SemanticOpKind>>> views = new ArrayList<>();
        for (deal.semantic.SemanticRequirementManifest manifest : manifests.manifests()) {
            views.add(manifest.constructCoverage());
        }
        return new CheckedProject(built, views);
    }

    private record LoweredE7(SemanticLowerer.FullProgramE7Result result) {
    }

    /** The E7 full-program lowering of one module inside the project closure. */
    private static LoweredE7 lowerE7(CheckedModuleInput input, CheckedProject project,
                                     Map<ConstructKind, List<SemanticOpKind>> coverage,
                                     ModuleId module, Map<ModuleId, ModuleRoute> routes,
                                     Map<ModuleId, Map<String, OpId>> entries,
                                     Set<String> callbacks, String what,
                                     SemanticIdAllocator allocator) {
        SemanticLowerer.FullProgramE7Result result =
            SemanticLowerer.lowerModuleFullProgramE7(input,
                SemanticProfile.DEAL_V1_2_INT32, coverage, project.built.index().interfaceIndexDigest(),
                REGISTRY_HASH, allocator, routes, entries, callbacks);
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + ": the E7 lowerer produces a validated unit: "
                + (result == null ? "null"
                    : (result.lowering() == null ? "null" : result.lowering().diagnostics())));
        if (result == null || result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return null;
        }
        return new LoweredE7(result);
    }

    /** Runs the three-consumer async-entry matrix for one seed. */
    private static SemanticDifferentialHarness.Verdict runAsyncEntry(
            ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
            String exportName, List<SemanticDifferentialHarness.CallbackArg> args,
            List<String> expectedEffects,
            SemanticDifferentialHarness.TerminalExpectation terminal, String what) {
        return runAsyncEntry(project, tables, exportName, args, null, expectedEffects,
            terminal, what);
    }

    /** Runs the three-consumer async-entry matrix for one host-scripted seed. */
    private static SemanticDifferentialHarness.Verdict runAsyncEntry(
            ExecutableLoweredProject project, Map<ModuleId, StructuredBodyTable> tables,
            String exportName, List<SemanticDifferentialHarness.CallbackArg> args,
            SemanticDifferentialHarness.AsyncHostScript host,
            List<String> expectedEffects,
            SemanticDifferentialHarness.TerminalExpectation terminal, String what) {
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.runAsyncEntry(
            project, tables, exportName, args,
            new SemanticDifferentialHarness.Expectation(expectedEffects, terminal, what),
            WORKSPACE, host);
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

    /** The canonical origin text of one op (the failure-projection pin). */
    private static String originTextOf(SemanticOp op) {
        SourceOrigin origin = op.origin();
        SourceSpan span = origin.span();
        return SemanticRuntimeModel.originAtom(origin.sourceId(),
            span == null ? null : span.startLine(),
            span == null ? null : span.startColumn());
    }

    // =========================================================================
    // 1. The DEAL body task seed (coroutine task / serial-executor future)
    // =========================================================================

    static void testBodyTaskDirect() {
        System.out.println("-- ASYNC_START(DEAL_BODY)+AWAIT: canonical token, one body task, "
            + "FUNCTION_RETURN + ASYNC_COMPLETION, real subprocess runs --");
        String source = CONSOLE
            + "async function compute(x: int): int { console.log(\"compute\"); return x * 2; }\n"
            + "export async function test(): int {\n"
            + "  let v: int = await compute(21);\n"
            + "  if (v === 42) { return v; }\n"
            + "  return 0;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerE7(source, "async body task", Set.of());
        if (lowered == null) {
            return;
        }
        List<SemanticOp> starts = ofKind(lowered.unit(), SemanticOpKind.ASYNC_START);
        check(starts.size() == 1, "the unit carries exactly one ASYNC_START");
        if (starts.size() == 1) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) starts.get(0).payload();
            check(payload.source() == AsyncStartSource.DEAL_BODY,
                "the async source is DEAL_BODY");
            check(payload.returnBoundaryOpId() != null,
                "the DEAL_BODY start names its body-task return boundary");
            check(payload.parameterBoundaryOpIds().size() == 1,
                "the DEAL_BODY start carries one FUNCTION_PARAMETER boundary");
            check(starts.get(0).result() instanceof AsyncTokenId.Canonical canonical
                    && canonical.owner() == AsyncTokenOwner.DEAL_BODY_TASK,
                "the published token is canonical, owned by the DEAL body task");
        }
        List<SemanticOp> awaits = ofKind(lowered.unit(), SemanticOpKind.AWAIT);
        check(awaits.size() == 1, "the unit carries exactly one AWAIT");
        if (awaits.size() == 1) {
            KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) awaits.get(0).payload();
            SemanticOp boundary = opById(lowered.unit(), payload.completionBoundaryOpId());
            check(boundary != null && boundaryKind(boundary) == BoundaryKind.ASYNC_COMPLETION
                    && awaits.get(0).opId().equals(boundary.origin().parentOpId()),
                "the single ASYNC_COMPLETION boundary parents to the AWAIT");
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, loweredProjectIndex(lowered),
            Map.of(MODULE, lowered.unit()), MODULE);
        SemanticDifferentialHarness.Verdict verdict = runAsyncEntry(project,
            Map.of(MODULE, lowered.table()), "test", List.of(),
            List.of("compute"),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("int:42"),
            "async body task");
        if (verdict != null) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                long returnStarts = 0;
                long completionStarts = 0;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.BOUNDARY
                            && event.phase() == SemanticRuntimeModel.Phase.START) {
                        SemanticOp op = opById(lowered.unit(), event.op());
                        if (op != null
                                && boundaryKind(op) == BoundaryKind.FUNCTION_RETURN) {
                            returnStarts++;
                        }
                        if (op != null
                                && boundaryKind(op) == BoundaryKind.ASYNC_COMPLETION) {
                            completionStarts++;
                        }
                    }
                }
                check(returnStarts == 3,
                    "exactly one FUNCTION_RETURN per body invocation (compute's task, "
                        + "test's entry task, main's entry call): got " + returnStarts);
                check(completionStarts == 1,
                    "the AWAIT runs exactly one ASYNC_COMPLETION; got " + completionStarts);
            }
        }
    }

    /** The single-module project interface index. */
    private static ProjectInterfaceIndex loweredProjectIndex(LoweredSlice lowered) {
        return new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new deal.semantic.ir.ExternalModuleInterface(MODULE,
                deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                List.of(), deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES)));
    }

    /** The single-module project closure over a hand-built unit. */
    private static ExecutableLoweredProject projectOf(LoweredModuleUnit unit) {
        return new ExecutableLoweredProject(SemanticProfile.DEAL_V1_2_INT32,
            new ProjectInterfaceIndex(ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
                new deal.semantic.ir.ExternalModuleInterface(MODULE,
                    deal.semantic.ir.ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                    List.of(),
                    deal.semantic.ir.InitializationMode.ONCE_AFTER_DEPENDENCIES))),
            Map.of(MODULE, unit), MODULE);
    }

    // =========================================================================
    // 2. The async host shape: value completion, bad handle, thrown completion
    // =========================================================================

    static void testAsyncHostCompletion() {
        System.out.println("-- ASYNC_START(HOST): AsyncStart effect, scripted value "
            + "completion through the artifact's host-seam entries --");
        Map<String, deal.types.Type> ops = Map.of("fetch",
            new deal.types.Type.Func(List.of(), deal.types.Type.Int.INSTANCE, true));
        String source = "import * as host from \"host/ops\"\n"
            + "export async function test(): int {\n"
            + "  let v: int = await host.fetch();\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerHostSlice(source, ops, "async host completion");
        if (lowered == null) {
            return;
        }
        List<SemanticOp> starts = ofKind(lowered.unit(), SemanticOpKind.ASYNC_START);
        check(starts.size() == 1, "the unit carries exactly one ASYNC_START(HOST)");
        if (starts.size() == 1) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) starts.get(0).payload();
            check(payload.source() == AsyncStartSource.HOST
                    && payload.hostOperationLabel() != null,
                "the async source is HOST with a deterministic operation label");
            check(payload.returnBoundaryOpId() == null,
                "ASYNC_START(HOST) runs zero return boundaries");
            check(starts.get(0).result() instanceof AsyncTokenId.Canonical canonical
                    && canonical.owner() == AsyncTokenOwner.HOST_OPERATION,
                "the published token is canonical, bound to the host operation");
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, loweredProjectIndex(lowered),
            Map.of(MODULE, lowered.unit()), MODULE);
        SemanticDifferentialHarness.AsyncHostScript script =
            new SemanticDifferentialHarness.AsyncHostScript("host/ops.fetch",
                new SemanticDifferentialHarness.AsyncHostCompletion.Returned(
                    new SemanticDifferentialHarness.CallbackArg.Int(42)));
        SemanticDifferentialHarness.Verdict verdict = runAsyncEntry(project,
            Map.of(MODULE, lowered.table()), "test", List.of(), script,
            List.of("host/ops.fetch", "host/ops.fetch=int:42"),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("int:42"),
            "async host value completion");
        if (verdict != null) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.effects().stream().anyMatch(e ->
                        e.kind() == SemanticRuntimeModel.EffectEvent.Kind.ASYNC_START_OP),
                    "the async host start records the ordered AsyncStart effect");
                check(run.effects().stream().anyMatch(e ->
                        e.kind() == SemanticRuntimeModel.EffectEvent.Kind.ASYNC_COMPLETE_RETURN),
                    "the async host completion records the ordered completion effect");
            }
        }
    }

    static void testAsyncHostBadHandle() {
        System.out.println("-- ASYNC_START(HOST) bad handle: the op's own "
            + "ASYNC_OPERATION_HANDLE terminal check (E8010) --");
        Map<String, deal.types.Type> ops = Map.of("fetch",
            new deal.types.Type.Func(List.of(), deal.types.Type.Int.INSTANCE, true));
        String source = "import * as host from \"host/ops\"\n"
            + "export async function test(): int {\n"
            + "  let v: int = await host.fetch();\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerHostSlice(source, ops, "async host bad handle");
        if (lowered == null) {
            return;
        }
        SemanticOp start = ofKind(lowered.unit(), SemanticOpKind.ASYNC_START).get(0);
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, loweredProjectIndex(lowered),
            Map.of(MODULE, lowered.unit()), MODULE);
        SemanticDifferentialHarness.AsyncHostScript script =
            new SemanticDifferentialHarness.AsyncHostScript(null,
                new SemanticDifferentialHarness.AsyncHostCompletion.Returned(
                    new SemanticDifferentialHarness.CallbackArg.Int(42)));
        runAsyncEntry(project, Map.of(MODULE, lowered.table()), "test", List.of(), script,
            List.of("host/ops.fetch"),
            new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8010",
                originTextOf(start)),
            "async host bad handle");
    }

    static void testAsyncHostThrownCompletion() {
        System.out.println("-- ASYNC_START(HOST) thrown completion: the identical host error "
            + "publishes at the AWAIT site (ASYNC_COMPLETE_THROW effect) --");
        Map<String, deal.types.Type> ops = Map.of("fetch",
            new deal.types.Type.Func(List.of(), deal.types.Type.Int.INSTANCE, true));
        String source = "import * as host from \"host/ops\"\n"
            + "export async function test(): int {\n"
            + "  let v: int = await host.fetch();\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerHostSlice(source, ops, "async host thrown completion");
        if (lowered == null) {
            return;
        }
        SemanticOp await = ofKind(lowered.unit(), SemanticOpKind.AWAIT).get(0);
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, loweredProjectIndex(lowered),
            Map.of(MODULE, lowered.unit()), MODULE);
        SemanticDifferentialHarness.AsyncHostScript script =
            new SemanticDifferentialHarness.AsyncHostScript("host/ops.fetch",
                new SemanticDifferentialHarness.AsyncHostCompletion.Thrown("E9999", "boom"));
        runAsyncEntry(project, Map.of(MODULE, lowered.table()), "test", List.of(), script,
            List.of("host/ops.fetch", "host/ops.fetch!E9999"),
            new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E9999",
                originTextOf(await)),
            "async host thrown completion");
    }

    static void testAsyncHostFunctionValue() {
        System.out.println("-- ASYNC_START(HOST) HostFunctionValue: the materializing-boundary "
            + "export cell (@value#N) reaches the scripted host seam identically on all "
            + "three consumers --");
        // Hand-built over the closed schema (the lowerer's lowerAwaitCall
        // HostFunctionValue arm shape — no DEAL source registers a
        // HostFunctionValue binding today, so the corpus seed builds the
        // exact IR the statically-resolved slice records: CallCallee.Static(
        // HostFunctionValue {hostModuleId, materializingBoundaryOpId,
        // descriptor}), AsyncStartSource.HOST, host operation label
        // module.@value). The scripted host seam keys on the export cell
        // (@value#<materializingBoundaryOpId>), so a consumer passing any
        // other cell fails the differential verdict.
        OpId entryOp = nextOpId();
        OpId startOp = nextOpId();
        OpId awaitOp = nextOpId();
        OpId cb = nextOpId();
        OpId rb = nextOpId();
        OpId retOp = nextOpId();
        OpId materializingBoundary = nextOpId();
        ValueId awaitResult = nextValue();
        BlockId body = nextBlock();
        ModuleId hostModule = new ModuleId("host/ops");
        RuntimeDescriptor.Func hostDescriptor =
            new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Int.INSTANCE, true);
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(opWith(entryOp, SemanticOpKind.EXTERNAL_ENTRY,
            new KindPayload.ExternalEntryPayload("test", new FunctionId(1),
                hostDescriptor, true, rb, RuntimeDescriptor.Int.INSTANCE),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(startOp, SemanticOpKind.ASYNC_START,
            new KindPayload.AsyncStartPayload(
                new KindPayload.CallCallee.Static(
                    new FunctionExecutionBinding.HostFunctionValue(hostModule,
                        materializingBoundary, hostDescriptor)),
                AsyncStartSource.HOST, ParameterBoundaryMode.RUN, List.of(),
                RuntimeDescriptor.Int.INSTANCE, null, "host/ops.@value", null),
            new AsyncTokenId.Canonical(601, AsyncTokenOwner.HOST_OPERATION),
            InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(cb, BoundaryKind.ASYNC_COMPLETION,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, awaitOp,
            awaitResult));
        ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
            new KindPayload.AwaitPayload(
                new AsyncTokenId.Canonical(601, AsyncTokenOwner.HOST_OPERATION),
                RuntimeDescriptor.Int.INSTANCE, cb),
            awaitResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        ops.add(boundaryWithInput(rb, BoundaryKind.FUNCTION_RETURN,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, retOp,
            awaitResult));
        ops.add(opWith(retOp, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(awaitResult, new FunctionId(1), entryOp, rb),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        Map<FunctionId, LoweredFunction> functions = Map.of(
            new FunctionId(1), new LoweredFunction(new FunctionId(1), hostDescriptor,
                List.of(), body));
        LoweredModuleUnit unit = unit(functions, new LinkedHashMap<>(), ops);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of());
        blockOps.put(body, List.of(startOp, awaitOp, retOp));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(startOp, body);
        opBlocks.put(awaitOp, body);
        opBlocks.put(retOp, body);
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        Optional<deal.diagnostics.CompilerDiagnostic> validation =
            SemanticIrValidator.validate(unit, FACTS);
        check(validation.isEmpty(), "the HostFunctionValue async host unit passes "
            + "validation: " + validation);
        if (validation.isPresent()) {
            return;
        }
        List<SemanticOp> starts = ofKind(unit, SemanticOpKind.ASYNC_START);
        check(starts.size() == 1, "the unit carries exactly one ASYNC_START(HOST)");
        if (starts.size() == 1) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) starts.get(0).payload();
            check(payload.source() == AsyncStartSource.HOST
                    && "host/ops.@value".equals(payload.hostOperationLabel()),
                "the async source is HOST with the module.@value operation label");
            check(payload.callee() instanceof KindPayload.CallCallee.Static staticCallee
                    && staticCallee.binding()
                        instanceof FunctionExecutionBinding.HostFunctionValue hostValue
                    && hostValue.materializingBoundaryOpId().equals(materializingBoundary),
                "the callee is the HostFunctionValue binding naming its materializing "
                    + "boundary");
            check(starts.get(0).result() instanceof AsyncTokenId.Canonical canonical
                    && canonical.owner() == AsyncTokenOwner.HOST_OPERATION,
                "the published token is canonical, bound to the host operation");
        }
        ExecutableLoweredProject project = projectOf(unit);
        // The scripted seam keys on the export cell: a start whose export
        // cell is not @value#<materializingBoundary> scripts the bad-handle
        // terminal, so the three consumers' export cells must match
        // exactly (the oracle and the JVM emitter pass the boundary-
        // qualified cell; the shared LuaJIT consumer must too).
        SemanticDifferentialHarness.AsyncHostScript script =
            new SemanticDifferentialHarness.AsyncHostScript("host/ops.@value",
                "@value#" + materializingBoundary.id(),
                new SemanticDifferentialHarness.AsyncHostCompletion.Returned(
                    new SemanticDifferentialHarness.CallbackArg.Int(42)));
        SemanticDifferentialHarness.Verdict verdict = runAsyncEntry(project,
            Map.of(MODULE, bodyTable), "test", List.of(), script,
            List.of("host/ops.@value", "host/ops.@value=int:42"),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("int:42"),
            "async host function value");
        if (verdict != null) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.effects().stream().anyMatch(e ->
                        e.kind() == SemanticRuntimeModel.EffectEvent.Kind.ASYNC_START_OP),
                    "the host-value start records the ordered AsyncStart effect");
                check(run.effects().stream().anyMatch(e ->
                        e.kind() == SemanticRuntimeModel.EffectEvent.Kind.ASYNC_COMPLETE_RETURN),
                    "the host-value completion records the ordered completion effect");
            }
        }
    }

    /** The host-module slice lowering (HOST import + async host export). */
    private static LoweredSlice lowerHostSlice(String source,
            Map<String, deal.types.Type> ops, String what) {
        CheckedSlice slice = checkSlice(source, resolverWith(Map.of("host/ops", ops)), what);
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program(), Map.of("host/ops", ExternalModuleKind.HOST)),
            exportsOf(slice.program()), CheckedModuleKind.IMPLEMENTATION);
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
                Map.of(), Map.of(), Set.of());
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

    // =========================================================================
    // 3. The adapter-over-async shape (ADAPTER_INNER + ELIDED_BY_ADAPTER)
    // =========================================================================

    static void testAdapterOverAsync() {
        System.out.println("-- Adapter-over-async: ADAPTER_INNER alias, nested "
            + "ELIDED_BY_ADAPTER source start, zero outer return boundaries; the FIFO "
            + "drain runs the outer adapter task before the nested source task --");
        String source = CONSOLE
            + "async function one(x: int): int { console.log(\"one\"); return x; }\n"
            + "export async function test(): int {\n"
            + "  let g: async (x: int) => int = one;\n"
            + "  let h: async (x: int, y: int) => int = g;\n"
            + "  let r: int = await h(1, 2);\n"
            + "  console.log(\"done\");\n"
            + "  return r;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerE7(source, "adapter over async", Set.of());
        if (lowered == null) {
            return;
        }
        List<SemanticOp> starts = ofKind(lowered.unit(), SemanticOpKind.ASYNC_START);
        check(starts.size() == 2,
            "the adapter-over-async shape carries the outer and nested ASYNC_START ops");
        if (starts.size() == 2) {
            SemanticOp outer;
            SemanticOp nested;
            if (starts.get(1).origin().parentOpId() != null
                    && starts.get(1).origin().parentOpId().equals(starts.get(0).opId())) {
                outer = starts.get(0);
                nested = starts.get(1);
            } else if (starts.get(0).origin().parentOpId() != null
                    && starts.get(0).origin().parentOpId().equals(starts.get(1).opId())) {
                outer = starts.get(1);
                nested = starts.get(0);
            } else {
                fail("adapter-over-async: the nested op must parent to the outer op");
                return;
            }
            KindPayload.AsyncStartPayload outerPayload =
                (KindPayload.AsyncStartPayload) outer.payload();
            KindPayload.AsyncStartPayload nestedPayload =
                (KindPayload.AsyncStartPayload) nested.payload();
            check(outerPayload.parameterBoundaryMode() == ParameterBoundaryMode.RUN
                    && outerPayload.parameterBoundaryOpIds().size() == 2,
                "the outer op runs the xN target-signature boundaries (complete set)");
            check(outerPayload.returnBoundaryOpId() == null,
                "the outer task runs zero return boundaries (delegation)");
            check(outer.result() instanceof AsyncTokenId.Alias alias
                    && alias.linkKind() == AsyncLinkKind.ADAPTER_INNER,
                "the outer token is ALIAS {ADAPTER_INNER} — never canonical");
            check(nestedPayload.parameterBoundaryMode()
                    == ParameterBoundaryMode.ELIDED_BY_ADAPTER
                    && nestedPayload.parameterBoundaryOpIds().isEmpty(),
                "the nested source op carries ELIDED_BY_ADAPTER with zero parameter "
                    + "boundaries");
            check(nested.origin().parentOpId() != null
                    && nested.origin().parentOpId().equals(outer.opId()),
                "the nested source op parents to the outer ASYNC_START");
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, loweredProjectIndex(lowered),
            Map.of(MODULE, lowered.unit()), MODULE);
        runAsyncEntry(project, Map.of(MODULE, lowered.table()), "test", List.of(),
            List.of("one", "done"),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("int:1"),
            "adapter over async");
    }

    // =========================================================================
    // 4. Cross-module async (alias-token completion through the callee entry)
    // =========================================================================

    static void testAsyncExternal() {
        System.out.println("-- ASYNC_START(EXTERNAL): the caller alias token links the "
            + "callee canonical token; callee-task FUNCTION_RETURN + caller "
            + "ASYNC_COMPLETION at the single AWAIT --");
        CheckedSlice sliceA = checkSlice(
            CONSOLE + "export async function compute(x: int): int { "
                + "console.log(\"a-compute\"); "
                + "return x * 2; }\n",
            stdlibResolver(), "module a (async)");
        if (sliceA == null) {
            return;
        }
        CheckedModuleInput inputA = new CheckedModuleInput(MODULE_A, "a.deal",
            Path.of("a.deal"), sliceA.program(), sliceA.checks(),
            importsOf(sliceA.program(), Map.of()),
            exportsOf(sliceA.program()), CheckedModuleKind.IMPLEMENTATION);
        String mainSource = "import * as a from \"./a\"\n"
            + CONSOLE
            + "export async function test(): int {\n"
            + "  let v: int = await a.compute(21);\n"
            + "  console.log(\"main-done\");\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        ModuleResolver mainResolver = resolverWith(Map.of("./a", Map.of("compute",
            new deal.types.Type.Func(List.of(deal.types.Type.Int.INSTANCE),
                deal.types.Type.Int.INSTANCE, true))));
        CheckedSlice sliceMain = checkSlice(mainSource, mainResolver, "module main (async)");
        if (sliceMain == null) {
            return;
        }
        CheckedModuleInput inputMain = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of("test.deal"), sliceMain.program(), sliceMain.checks(),
            importsOf(sliceMain.program(), Map.of("./a", ExternalModuleKind.IMPLEMENTATION)),
            exportsOf(sliceMain.program()), CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(
            new ModuleFact("a.deal", MODULE_A, false, false, sliceA.program(), Map.of(),
                sliceA.symbols(), sliceA.checks(), List.of()),
            new ModuleFact(SOURCE_ID, MODULE, false, false, sliceMain.program(),
                Map.of(), sliceMain.symbols(), sliceMain.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE);
        if (project == null) {
            return;
        }
        SemanticIdAllocator allocator = SemanticIdAllocator.over(List.of(MODULE_A, MODULE));
        LoweredE7 loweredA = lowerE7(inputA, project, project.coverage().get(0), MODULE_A,
            Map.of(), Map.of(), Set.of(), "module a (async)", allocator);
        if (loweredA == null) {
            return;
        }
        LoweredE7 loweredMain = lowerE7(inputMain, project, project.coverage().get(1),
            MODULE, Map.of(MODULE_A, ModuleRoute.SHARED),
            Map.of(MODULE_A, loweredA.result().externalEntries()), Set.of(),
            "module main (async)", allocator);
        if (loweredMain == null) {
            return;
        }
        LoweredModuleUnit unitA = loweredA.result().lowering().unit();
        LoweredModuleUnit unitMain = loweredMain.result().lowering().unit();
        List<SemanticOp> starts = ofKind(unitMain, SemanticOpKind.ASYNC_START);
        check(starts.size() == 1, "module main carries exactly one ASYNC_START(EXTERNAL)");
        if (starts.size() == 1) {
            KindPayload.AsyncStartPayload payload =
                (KindPayload.AsyncStartPayload) starts.get(0).payload();
            check(payload.source() == AsyncStartSource.EXTERNAL,
                "the async source is EXTERNAL");
            check(payload.externalAsyncLink() != null
                    && payload.externalAsyncLink().calleeModuleId().equals(MODULE_A),
                "the caller link names the callee module's canonical token");
            check(starts.get(0).result() instanceof AsyncTokenId.Alias alias
                    && alias.linkKind() == AsyncLinkKind.EXTERNAL_LINK,
                "the caller token is ALIAS {EXTERNAL_LINK}");
        }
        ExecutableLoweredProject projectIr = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, project.built().index(),
            Map.of(MODULE_A, unitA, MODULE, unitMain), MODULE);
        Optional<deal.diagnostics.CompilerDiagnostic> validation =
            SemanticIrValidator.validate(projectIr,
                new SemanticIrValidator.ComparisonFacts(
                    project.built().index().interfaceIndexDigest(),
                    SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH));
        check(validation.isEmpty(), "the async project validates: " + validation);
        if (validation.isPresent()) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = runAsyncEntry(projectIr,
            Map.of(MODULE_A, loweredA.result().lowering().table(),
                MODULE, loweredMain.result().lowering().table()),
            "test", List.of(), List.of("a-compute", "main-done"),
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("int:42"),
            "async external");
        if (verdict != null) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                long calleeReturns = 0;
                long completions = 0;
                boolean aliasAtom = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.BOUNDARY
                            && event.phase() == SemanticRuntimeModel.Phase.START) {
                        SemanticOp op = opById(unitA, event.op());
                        if (op != null
                                && boundaryKind(op) == BoundaryKind.FUNCTION_RETURN) {
                            calleeReturns++;
                        }
                        op = opById(unitMain, event.op());
                        if (op != null
                                && boundaryKind(op) == BoundaryKind.ASYNC_COMPLETION) {
                            completions++;
                        }
                    }
                    if (event.kind() == SemanticOpKind.ASYNC_START
                            && event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                            && event.output() != null
                            && event.output().startsWith("alias:")) {
                        aliasAtom = true;
                    }
                }
                check(calleeReturns == 1,
                    "the callee body task runs exactly one FUNCTION_RETURN; got "
                        + calleeReturns);
                check(completions == 1,
                    "the caller AWAIT runs exactly one ASYNC_COMPLETION; got " + completions);
                check(aliasAtom,
                    "the caller ASYNC_START publishes the alias token atom (the alias "
                        + "completes through its canonical referent)");
            }
        }
    }

    // =========================================================================
    // 5. The hand-built two-task FIFO seed (deterministic drain order)
    // =========================================================================

    static void testFifoDrain() {
        System.out.println("-- FIFO drain: two pending DEAL body tasks complete in "
            + "submission order at the single AWAIT (side effects pinned) --");
        OpId str1Op = nextOpId();
        OpId str2Op = nextOpId();
        OpId log1Op = nextOpId();
        OpId log2Op = nextOpId();
        OpId p1 = nextOpId();
        OpId p2 = nextOpId();
        OpId r1 = nextOpId();
        OpId r2 = nextOpId();
        OpId c1Op = nextOpId();
        OpId c2Op = nextOpId();
        OpId ret1 = nextOpId();
        OpId ret2 = nextOpId();
        OpId rb1 = nextOpId();
        OpId rb2 = nextOpId();
        OpId start1 = nextOpId();
        OpId start2 = nextOpId();
        OpId awaitOp = nextOpId();
        OpId cb = nextOpId();
        ValueId str1 = nextValue();
        ValueId str2 = nextValue();
        ValueId log1 = nextValue();
        ValueId log2 = nextValue();
        ValueId c1 = nextValue();
        ValueId c2 = nextValue();
        ValueId awaitResult = nextValue();
        BlockId body1 = nextBlock();
        BlockId body2 = nextBlock();
        RuntimeDescriptor.Func signature =
            new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Int.INSTANCE, true);
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(opWith(str1Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.String("first")), str1,
            RuntimeDescriptor.String.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(str2Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.String("second")), str2,
            RuntimeDescriptor.String.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(p1, BoundaryKind.STDLIB_PARAMETER,
            RuntimeDescriptor.String.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, log1Op,
            str1));
        ops.add(boundaryWithInput(p2, BoundaryKind.STDLIB_PARAMETER,
            RuntimeDescriptor.String.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, log2Op,
            str2));
        ops.add(boundaryWithInput(r1, BoundaryKind.STDLIB_RETURN,
            RuntimeDescriptor.Null.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, log1Op,
            log1));
        ops.add(boundaryWithInput(r2, BoundaryKind.STDLIB_RETURN,
            RuntimeDescriptor.Null.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, log2Op,
            log2));
        ops.add(opWith(log1Op, SemanticOpKind.STDLIB_CALL,
            new KindPayload.StdlibCallPayload(StdlibFunctionId.CONSOLE_LOG, List.of(str1),
                SemanticCapability.STDLIB_SEMANTICS),
            log1, RuntimeDescriptor.Null.INSTANCE, FailurePolicyId.INFRASTRUCTURE_ONLY, null,
            List.of(str1), List.of(RuntimeDescriptor.String.INSTANCE)));
        ops.add(opWith(log2Op, SemanticOpKind.STDLIB_CALL,
            new KindPayload.StdlibCallPayload(StdlibFunctionId.CONSOLE_LOG, List.of(str2),
                SemanticCapability.STDLIB_SEMANTICS),
            log2, RuntimeDescriptor.Null.INSTANCE, FailurePolicyId.INFRASTRUCTURE_ONLY, null,
            List.of(str2), List.of(RuntimeDescriptor.String.INSTANCE)));
        ops.add(opWith(c1Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(1)), c1,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(c2Op, SemanticOpKind.CONST,
            new KindPayload.ConstPayload(new ScalarValue.Int(2)), c2,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(rb1, BoundaryKind.FUNCTION_RETURN,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, ret1, c1));
        ops.add(boundaryWithInput(rb2, BoundaryKind.FUNCTION_RETURN,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, ret2, c2));
        ops.add(opWith(ret1, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(c1, new FunctionId(1), start1, rb1),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(ret2, SemanticOpKind.RETURN,
            new KindPayload.ReturnPayload(c2, new FunctionId(2), start2, rb2),
            null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(start1, SemanticOpKind.ASYNC_START,
            new KindPayload.AsyncStartPayload(
                new KindPayload.CallCallee.Static(
                    new FunctionExecutionBinding.LoweredBody(new FunctionId(1), body1)),
                AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.RUN, List.of(),
                RuntimeDescriptor.Int.INSTANCE, rb1, null, null),
            new AsyncTokenId.Canonical(101, AsyncTokenOwner.DEAL_BODY_TASK),
            InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(opWith(start2, SemanticOpKind.ASYNC_START,
            new KindPayload.AsyncStartPayload(
                new KindPayload.CallCallee.Static(
                    new FunctionExecutionBinding.LoweredBody(new FunctionId(2), body2)),
                AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.RUN, List.of(),
                RuntimeDescriptor.Int.INSTANCE, rb2, null, null),
            new AsyncTokenId.Canonical(102, AsyncTokenOwner.DEAL_BODY_TASK),
            InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
        ops.add(boundaryWithInput(cb, BoundaryKind.ASYNC_COMPLETION,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, awaitOp,
            awaitResult));
        ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
            new KindPayload.AwaitPayload(
                new AsyncTokenId.Canonical(102, AsyncTokenOwner.DEAL_BODY_TASK),
                RuntimeDescriptor.Int.INSTANCE, cb),
            awaitResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        Map<FunctionId, LoweredFunction> functions = Map.of(
            new FunctionId(1), new LoweredFunction(new FunctionId(1), signature, List.of(),
                body1),
            new FunctionId(2), new LoweredFunction(new FunctionId(2), signature, List.of(),
                body2));
        Map<FunctionAllocationIdentity, FunctionExecutionBinding> bindings =
            new LinkedHashMap<>();
        LoweredModuleUnit unit = unit(functions, bindings, ops);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of(start1, start2, awaitOp));
        blockOps.put(body1, List.of(str1Op, log1Op, c1Op, ret1));
        blockOps.put(body2, List.of(str2Op, log2Op, c2Op, ret2));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(start1, new BlockId(0));
        opBlocks.put(start2, new BlockId(0));
        opBlocks.put(awaitOp, new BlockId(0));
        opBlocks.put(str1Op, body1);
        opBlocks.put(log1Op, body1);
        opBlocks.put(c1Op, body1);
        opBlocks.put(ret1, body1);
        opBlocks.put(str2Op, body2);
        opBlocks.put(log2Op, body2);
        opBlocks.put(c2Op, body2);
        opBlocks.put(ret2, body2);
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        Optional<deal.diagnostics.CompilerDiagnostic> validation =
            SemanticIrValidator.validate(unit, FACTS);
        check(validation.isEmpty(), "the FIFO two-task unit passes validation: "
            + validation);
        if (validation.isPresent()) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            unit, bodyTable,
            new SemanticDifferentialHarness.Expectation(List.of("first", "second"),
                new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
                "FIFO two-task drain"),
            WORKSPACE);
        check(verdict.pass(), "FIFO drain: the three-consumer matrix verdict passes "
            + "(the first body completes before the second — submission-order FIFO):\n"
            + verdict.report());
        if (verdict != null) {
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                boolean awaitAtom = false;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.AWAIT
                            && event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                            && "int:2".equals(event.output())) {
                        awaitAtom = true;
                    }
                }
                check(awaitAtom, "FIFO drain: " + run.consumer()
                    + " completes the awaited second task with int:2 (the first task's "
                    + "effects already ordered before it)");
            }
        }
    }

    // =========================================================================
    // 6. Negatives: validator rejections and the failing differential verdict
    // =========================================================================

    static void testValidatorNegatives() {
        System.out.println("-- Validator negatives: misplaced completion check, extra "
            + "completion boundary, alias cycle --");
        // (a) The ASYNC_COMPLETION boundary parented to the ASYNC_START op
        // instead of the AWAIT (the misplaced completion check).
        {
            OpId startOp = nextOpId();
            OpId awaitOp = nextOpId();
            OpId cb = nextOpId();
            OpId rb = nextOpId();
            OpId retOp = nextOpId();
            OpId constOp = nextOpId();
            ValueId c = nextValue();
            ValueId awaitResult = nextValue();
            BlockId bodyBlock = nextBlock();
            RuntimeDescriptor.Func signature =
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Int.INSTANCE, true);
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(opWith(constOp, SemanticOpKind.CONST,
                new KindPayload.ConstPayload(new ScalarValue.Int(1)), c,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWithInput(rb, BoundaryKind.FUNCTION_RETURN,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, retOp, c));
            ops.add(opWith(retOp, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(c, new FunctionId(1), startOp, rb),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(startOp, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(
                        new FunctionExecutionBinding.LoweredBody(new FunctionId(1),
                            bodyBlock)),
                    AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.RUN, List.of(),
                    RuntimeDescriptor.Int.INSTANCE, rb, null, null),
                new AsyncTokenId.Canonical(201, AsyncTokenOwner.DEAL_BODY_TASK),
                InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
            // The misplaced completion boundary: parented to the ASYNC_START
            // op while the AWAIT payload still names it.
            ops.add(boundaryWithInput(cb, BoundaryKind.ASYNC_COMPLETION,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, startOp,
                awaitResult));
            ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(
                    new AsyncTokenId.Canonical(201, AsyncTokenOwner.DEAL_BODY_TASK),
                    RuntimeDescriptor.Int.INSTANCE, cb),
                awaitResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
                null));
            Map<FunctionId, LoweredFunction> functions = Map.of(
                new FunctionId(1), new LoweredFunction(new FunctionId(1), signature,
                    List.of(), bodyBlock));
            LoweredModuleUnit unit = unit(functions, new LinkedHashMap<>(), ops);
            Optional<deal.diagnostics.CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(unit, FACTS);
            check(diagnostic.isPresent() && "E6005".equals(diagnostic.get().code())
                    && diagnostic.get().message().contains("R-BOUNDARY-TRIPLE"),
                "misplaced completion check: the validator rejects with E6005 "
                    + "R-BOUNDARY-TRIPLE: " + diagnostic);
        }
        // (b) A second ASYNC_COMPLETION boundary parented to the AWAIT (wrong
        // boundary count — the payload names only the first).
        {
            OpId startOp = nextOpId();
            OpId awaitOp = nextOpId();
            OpId cb = nextOpId();
            OpId extra = nextOpId();
            OpId rb = nextOpId();
            OpId retOp = nextOpId();
            OpId constOp = nextOpId();
            ValueId c = nextValue();
            ValueId awaitResult = nextValue();
            BlockId bodyBlock = nextBlock();
            RuntimeDescriptor.Func signature =
                new RuntimeDescriptor.Func(List.of(), RuntimeDescriptor.Int.INSTANCE, true);
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(opWith(constOp, SemanticOpKind.CONST,
                new KindPayload.ConstPayload(new ScalarValue.Int(1)), c,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWithInput(rb, BoundaryKind.FUNCTION_RETURN,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.TYPE_DESCRIPTOR, retOp, c));
            ops.add(opWith(retOp, SemanticOpKind.RETURN,
                new KindPayload.ReturnPayload(c, new FunctionId(1), startOp, rb),
                null, null, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(opWith(startOp, SemanticOpKind.ASYNC_START,
                new KindPayload.AsyncStartPayload(
                    new KindPayload.CallCallee.Static(
                        new FunctionExecutionBinding.LoweredBody(new FunctionId(1),
                            bodyBlock)),
                    AsyncStartSource.DEAL_BODY, ParameterBoundaryMode.RUN, List.of(),
                    RuntimeDescriptor.Int.INSTANCE, rb, null, null),
                new AsyncTokenId.Canonical(301, AsyncTokenOwner.DEAL_BODY_TASK),
                InternalResultType.INTERNAL_ASYNC, FailurePolicyId.NO_DEAL_FAILURE, null));
            ops.add(boundaryWithInput(cb, BoundaryKind.ASYNC_COMPLETION,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, awaitOp,
                awaitResult));
            ops.add(boundaryWithInput(extra, BoundaryKind.ASYNC_COMPLETION,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, awaitOp,
                nextValue()));
            ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(
                    new AsyncTokenId.Canonical(301, AsyncTokenOwner.DEAL_BODY_TASK),
                    RuntimeDescriptor.Int.INSTANCE, cb),
                awaitResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
                null));
            Map<FunctionId, LoweredFunction> functions = Map.of(
                new FunctionId(1), new LoweredFunction(new FunctionId(1), signature,
                    List.of(), bodyBlock));
            LoweredModuleUnit unit = unit(functions, new LinkedHashMap<>(), ops);
            Optional<deal.diagnostics.CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(unit, FACTS);
            check(diagnostic.isPresent() && "E6005".equals(diagnostic.get().code())
                    && diagnostic.get().message().contains("R-BOUNDARY-TRIPLE"),
                "wrong completion boundary count: the validator rejects with E6005 "
                    + "R-BOUNDARY-TRIPLE: " + diagnostic);
        }
        // (c) A self-referencing alias token (R-ALIAS-CYCLE).
        {
            OpId awaitOp = nextOpId();
            OpId cb = nextOpId();
            ValueId awaitResult = nextValue();
            List<SemanticOp> ops = new ArrayList<>();
            ops.add(boundaryWithInput(cb, BoundaryKind.ASYNC_COMPLETION,
                RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, awaitOp,
                awaitResult));
            AsyncTokenId selfAlias = new AsyncTokenId.Alias(401,
                new AsyncTokenId.Alias(401, new AsyncTokenId.Canonical(402,
                    AsyncTokenOwner.DEAL_BODY_TASK), AsyncLinkKind.EXTERNAL_LINK),
                AsyncLinkKind.EXTERNAL_LINK);
            ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
                new KindPayload.AwaitPayload(selfAlias, RuntimeDescriptor.Int.INSTANCE, cb),
                awaitResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
                null));
            LoweredModuleUnit unit = unit(Map.of(), new LinkedHashMap<>(), ops);
            Optional<deal.diagnostics.CompilerDiagnostic> diagnostic =
                SemanticIrValidator.validate(unit, FACTS);
            check(diagnostic.isPresent() && "E6005".equals(diagnostic.get().code())
                    && diagnostic.get().message().contains("R-ALIAS-CYCLE"),
                "alias token cycle: the validator rejects with E6005 R-ALIAS-CYCLE: "
                    + diagnostic);
        }
    }

    static void testWrongAliasReferentNegative() {
        System.out.println("-- Wrong alias referent negative: the fail-closed consumers "
            + "never pass a dangling alias completion (unbound canonical referent) --");
        OpId awaitOp = nextOpId();
        OpId cb = nextOpId();
        ValueId awaitResult = nextValue();
        List<SemanticOp> ops = new ArrayList<>();
        ops.add(boundaryWithInput(cb, BoundaryKind.ASYNC_COMPLETION,
            RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.ASYNC_COMPLETION, awaitOp,
            awaitResult));
        // The alias referent 4242 has no producing op: every consumer must
        // fail closed (the oracle raises; the artifacts' unbound-task check
        // fails) — never a coincidental pass.
        AsyncTokenId dangling = new AsyncTokenId.Alias(403,
            new AsyncTokenId.Canonical(4242, AsyncTokenOwner.DEAL_BODY_TASK),
            AsyncLinkKind.EXTERNAL_LINK);
        ops.add(opWith(awaitOp, SemanticOpKind.AWAIT,
            new KindPayload.AwaitPayload(dangling, RuntimeDescriptor.Int.INSTANCE, cb),
            awaitResult, RuntimeDescriptor.Int.INSTANCE, FailurePolicyId.NO_DEAL_FAILURE,
            null));
        LoweredModuleUnit unit = unit(Map.of(), new LinkedHashMap<>(), ops);
        Map<BlockId, List<OpId>> blockOps = new LinkedHashMap<>();
        blockOps.put(new BlockId(0), List.of(awaitOp));
        Map<OpId, BlockId> opBlocks = new LinkedHashMap<>();
        opBlocks.put(awaitOp, new BlockId(0));
        StructuredBodyTable bodyTable = table(blockOps, opBlocks);
        boolean producedFailingVerdict;
        try {
            SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
                unit, bodyTable,
                new SemanticDifferentialHarness.Expectation(List.of(),
                    new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
                    "wrong alias referent negative"),
                WORKSPACE);
            producedFailingVerdict = !verdict.pass();
            check(!verdict.pass(), "wrong alias referent: the differential verdict over "
                + "the dangling-alias unit fails (never a coincidental pass):\n"
                + verdict.report());
        } catch (RuntimeException exception) {
            producedFailingVerdict = true;
            check(true, "wrong alias referent: the consumers fail closed on the "
                + "unbound canonical referent ("
                + exception.getClass().getSimpleName() + ")");
        }
        check(producedFailingVerdict,
            "wrong alias referent: the negative control produced a failing outcome");
    }

    // =========================================================================
    // 7. EmitOp totality pins and the production-mode realization
    // =========================================================================

    static void testArmPins() {
        System.out.println("-- ASYNC_START/AWAIT emitOp pins: one arm per emitter, the "
            + "default throw retained in both switches --");
        for (String path : List.of("deal/codegen/lua/LuaSemanticEmitter.java",
                "deal/codegen/jvm/JvmSemanticEmitter.java")) {
            String text;
            try {
                text = Files.readString(Path.of(path));
            } catch (java.io.IOException exception) {
                fail(path + " cannot be read for the emitOp arm pin: "
                    + exception.getMessage());
                continue;
            }
            for (String kind : List.of("ASYNC_START", "AWAIT")) {
                int arms = 0;
                int index = 0;
                while ((index = text.indexOf("case " + kind + " ->", index)) >= 0) {
                    arms++;
                    index++;
                }
                check(arms == 1, path + " carries exactly one " + kind
                    + " realization arm in its emitOp switch; got " + arms);
            }
            check(text.contains("default -> throw new IllegalStateException"),
                path + " retains the fail-closed default throw (the E6005-converted "
                    + "backstop)");
        }
    }

    static void testProductionModeRealization() {
        System.out.println("-- Production-mode realization: the async arms emit through "
            + "the production surfaces and run under the real toolchains (retained "
            + "terminal contract) --");
        String source = "async function compute(x: int): int { return x * 2; }\n"
            + "export async function test(): int {\n"
            + "  let v: int = await compute(21);\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice lowered = lowerE7(source, "production-mode async realization", Set.of());
        if (lowered == null) {
            return;
        }
        SemanticOp entry = null;
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && ((KindPayload.ExternalEntryPayload) op.payload()).async()
                    && ((KindPayload.ExternalEntryPayload) op.payload()).exportName()
                        .equals("test")) {
                entry = op;
            }
        }
        check(entry != null, "the unit records the async EXTERNAL_ENTRY for test");
        if (entry == null) {
            return;
        }
        try {
            String lua = deal.codegen.lua.LuaSemanticEmitter.emitProductionModule(
                lowered.unit(), lowered.table(), true);
            check(lua.contains("coroutine.create") && lua.contains("__asyncEntries["),
                "the production shared-LuaJIT artifact realizes the coroutine task and "
                    + "the async-entry dispatch entry");
            Path script = WORKSPACE.resolve("prod-async.lua");
            Files.writeString(script, lua, StandardCharsets.UTF_8);
            Path driver = WORKSPACE.resolve("prod-async-driver.lua");
            Files.writeString(driver,
                "dofile(" + quoteLua(script.toAbsolutePath().toString()) + ")\n"
                    + "local __okM, __errM = __dealMain()\n"
                    + "if not __okM then\n"
                    + "  print(\"DEAL_ERROR_CODE: \"..__errM.code)\n"
                    + "  os.exit(1)\n"
                    + "end\n"
                    + "local __okE, __resE = pcall(__asyncEntries[\"main#test\"], \"-\", true)\n"
                    + "if not __okE then\n"
                    + "  if type(__resE) == \"table\" and __resE.__d then\n"
                    + "    print(\"DEAL_ERROR_CODE: \"..__resE.code)\n"
                    + "  else\n"
                    + "    error(__resE, 0)\n"
                    + "  end\n"
                    + "  os.exit(1)\n"
                    + "end\n"
                    + "print(\"async-prod:\"..tostring(__resE))\n",
                StandardCharsets.UTF_8);
            ProcessBuilder luaBuilder = new ProcessBuilder("luajit",
                driver.toAbsolutePath().toString())
                .redirectErrorStream(true);
            luaBuilder.environment().put("DEAL_DEFER_MAIN", "1");
            Process luaRun = luaBuilder.start();
            String luaOutput = new String(luaRun.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int luaExit = luaRun.waitFor();
            check(luaExit == 0 && "async-prod:42\n".equals(luaOutput),
                "the production shared-LuaJIT async artifact runs the coroutine task "
                    + "end-to-end with the retained production output (exit " + luaExit
                    + ", output " + luaOutput.trim() + ")");

            deal.codegen.jvm.JvmSemanticEmitter.EmissionResult emission =
                deal.codegen.jvm.JvmSemanticEmitter.emitProductionModule(lowered.unit(),
                    lowered.table(), true, "AsyncProdMain");
            check(emission.source().contains("JvmRuntime.startBodyTask")
                    && emission.source().contains("JvmRuntime.awaitTask")
                    && emission.source().contains("public static Object ae"),
                "the production shared-JVM artifact realizes the serial-executor task "
                    + "and the async-entry dispatch entry");
            Path sourceFile = WORKSPACE.resolve("AsyncProdMain.java");
            Files.writeString(sourceFile, emission.source(), StandardCharsets.UTF_8);
            Path classes = WORKSPACE.resolve("prod-async-classes");
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
                "the production shared-JVM async artifact compiles: "
                    + compileOutput.replace("\n", "\\n"));
            if (compileExit == 0) {
                Path driverSource = WORKSPACE.resolve("AsyncProdDriver.java");
                Files.writeString(driverSource,
                    "public class AsyncProdDriver {\n"
                        + "  public static void main(String[] args) {\n"
                        + "    try {\n"
                        + "      AsyncProdMain.dealMain();\n"
                        + "      Object r = AsyncProdMain.ae" + entry.opId().id()
                        + "(\"-\", true, new Object[]{});\n"
                        + "      System.out.println(\"async-prod:\" + r);\n"
                        + "    } catch (deal.codegen.jvm.JvmRuntime.DealError e) {\n"
                        + "      System.out.println(\"DEAL_ERROR_CODE: \" + e.code);\n"
                        + "      System.exit(1);\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n",
                    StandardCharsets.UTF_8);
                Process driverCompile = new ProcessBuilder("javac", "--release", "25",
                    "-proc:none", "-cp", classpath + java.io.File.pathSeparator + classes,
                    "-d", classes.toString(), driverSource.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
                String driverOut = new String(driverCompile.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                int driverExit = driverCompile.waitFor();
                check(driverExit == 0,
                    "the production shared-JVM async driver compiles: "
                        + driverOut.replace("\n", "\\n"));
                if (driverExit == 0) {
                    Process run = new ProcessBuilder("java", "-cp",
                        classpath + java.io.File.pathSeparator + classes, "AsyncProdDriver")
                        .redirectErrorStream(true).start();
                    String runOutput = new String(run.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                    int runExit = run.waitFor();
                    check(runExit == 0 && "async-prod:42\n".equals(runOutput),
                        "the production shared-JVM async artifact runs the future task "
                            + "end-to-end with the retained production output (exit "
                            + runExit + ", output " + runOutput.trim() + ")");
                }
            }
        } catch (java.io.IOException | InterruptedException exception) {
            fail("production-mode async realization infrastructure failure: "
                + exception.getMessage());
        }

        // The retained failure terminal: a body-task E8005 publishes
        // DEAL_ERROR_CODE and exits 1 (never a trace line).
        String badSource = "async function bad(x: int): int { return x / (x - x); }\n"
            + "export async function test(): int {\n"
            + "  let v: int = await bad(7);\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSlice badLowered = lowerE7(badSource, "production-mode async failure",
            Set.of());
        if (badLowered == null) {
            return;
        }
        try {
            String lua = deal.codegen.lua.LuaSemanticEmitter.emitProductionModule(
                badLowered.unit(), badLowered.table(), true);
            Path script = WORKSPACE.resolve("prod-async-bad.lua");
            Files.writeString(script, lua, StandardCharsets.UTF_8);
            Path driver = WORKSPACE.resolve("prod-async-bad-driver.lua");
            Files.writeString(driver,
                "dofile(" + quoteLua(script.toAbsolutePath().toString()) + ")\n"
                    + "local __okM, __errM = __dealMain()\n"
                    + "if not __okM then\n"
                    + "  print(\"DEAL_ERROR_CODE: \"..__errM.code)\n"
                    + "  os.exit(1)\n"
                    + "end\n"
                    + "local __okE, __resE = pcall(__asyncEntries[\"main#test\"], \"-\", true)\n"
                    + "if not __okE then\n"
                    + "  if type(__resE) == \"table\" and __resE.__d then\n"
                    + "    print(\"DEAL_ERROR_CODE: \"..__resE.code)\n"
                    + "  else\n"
                    + "    error(__resE, 0)\n"
                    + "  end\n"
                    + "  os.exit(1)\n"
                    + "end\n"
                    + "print(\"async-prod:\"..tostring(__resE))\n",
                StandardCharsets.UTF_8);
            ProcessBuilder luaBuilder = new ProcessBuilder("luajit",
                driver.toAbsolutePath().toString())
                .redirectErrorStream(true);
            luaBuilder.environment().put("DEAL_DEFER_MAIN", "1");
            Process luaRun = luaBuilder.start();
            String luaOutput = new String(luaRun.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            int luaExit = luaRun.waitFor();
            check(luaExit == 1 && "DEAL_ERROR_CODE: E8005\n".equals(luaOutput),
                "the production shared-LuaJIT async failure publishes the retained "
                    + "DEAL_ERROR_CODE terminal and exits 1 (exit " + luaExit
                    + ", output " + luaOutput.trim() + ")");
        } catch (java.io.IOException | InterruptedException exception) {
            fail("production-mode async failure infrastructure failure: "
                + exception.getMessage());
        }
    }

    private static String quoteLua(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // =========================================================================
    // Hand-built unit helpers
    // =========================================================================

    private static long nextOp = 1;
    private static long nextVal = 1;
    private static long nextBlk = 1;

    private static OpId nextOpId() {
        return new OpId(MODULE, nextOp++);
    }

    private static ValueId nextValue() {
        return new ValueId(nextVal++);
    }

    private static BlockId nextBlock() {
        return new BlockId(nextBlk++);
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

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        try {
            Files.createDirectories(WORKSPACE);
        } catch (java.io.IOException ignored) {
            // the harness creates its workspace per run
        }
        testBodyTaskDirect();
        testAsyncHostCompletion();
        testAsyncHostBadHandle();
        testAsyncHostThrownCompletion();
        testAsyncHostFunctionValue();
        testAdapterOverAsync();
        testAsyncExternal();
        testFifoDrain();
        testValidatorNegatives();
        testWrongAliasReferentNegative();
        testArmPins();
        testProductionModeRealization();
        System.out.println("AsyncStartAwaitIntegrationTest: " + passed + " passed, "
            + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
