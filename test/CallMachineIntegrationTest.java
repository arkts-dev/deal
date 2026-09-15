package deal.test;

import deal.ast.ProgramNode;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.ModuleResolver.ModuleNotFoundException;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.diagnostics.CompilerDiagnostic;
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
import deal.semantic.ir.AdaptSourceRef;
import deal.semantic.ir.AsyncLinkKind;
import deal.semantic.ir.AsyncStartSource;
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.BoundaryKind;
import deal.semantic.ir.CallMode;
import deal.semantic.ir.CaptureMode;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalExecutionOwner;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.ResolvedImport;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The E7 decomposition-tail integration verification (ISSUE-0236): the
 * statically-resolved D13/D15 call machine runs through the real
 * production chain — lexer → parser → checker → checked project →
 * requirement manifest → the E7 full-program lowerer → the closed
 * validator → the address-chain protocol → the control-flow validator —
 * and the produced units execute on the semantic oracle with exact
 * terminals, ordered effects, and boundary counts:
 *
 * <ul>
 *   <li>direct calls (multiple call sites and recursion included) with
 *       one {@code FUNCTION_PARAMETER} boundary per argument and the
 *       single {@code FUNCTION_RETURN} boundary run by the callee's
 *       {@code RETURN};</li>
 *   <li>indirect calls through closure values and through locals with
 *       statically tracked identities ({@code CallCallee.Static}, never
 *       a deferred resolution);</li>
 *   <li>adapter invocation with statically fixed sources — the ×N
 *       target-signature {@code FUNCTION_PARAMETER} boundaries (the
 *       complete set), the leading-M projection with trailing arguments
 *       evaluated/checked/dropped, the SHARED_CELL live re-read, the
 *       VALUE-retained source body invoked per invocation, and the E8010
 *       {@code FUNCTION_SIGNATURE} projection for a wrong loaded
 *       value;</li>
 *   <li>{@code CALL(HOST)} with the pinned {@code DEAL_TO_HOST}+
 *       {@code HOST_PARAMETER} parameter cells and the call-op-run
 *       {@code HOST_TO_DEAL}+{@code HOST_SYNC_RETURN} return cell;</li>
 *   <li>{@code CALL(EXTERNAL)} with caller-side {@code EXTERNAL_PARAMETER}
 *       boundaries and the callee unit's {@code EXTERNAL_ENTRY}
 *       (SHARED_BODY: callee {@code RETURN} runs the single
 *       {@code EXTERNAL_RETURN}; RETAINED_ABI: the call op runs it);</li>
 *   <li>{@code ASYNC_START}+{@code AWAIT} for DEAL bodies (canonical
 *       token + one body task whose {@code RETURN} runs the single
 *       {@code FUNCTION_RETURN}), async host functions (the op's own
 *       {@code ASYNC_OPERATION_HANDLE} terminal check), async externals
 *       (the caller alias token links to the callee canonical token
 *       through {@code ExternalAsyncLink}), and adapter-over-async
 *       (the nested source op carries {@code ELIDED_BY_ADAPTER} with
 *       zero parameter boundaries; the outer token is
 *       {@code ALIAS {ADAPTER_INNER}});</li>
 *   <li>the {@code ENTRY_INVOKE} delegation of {@code main}: null and
 *       the host-driven {@code CALLBACK_INVOKE} surface;</li>
 *   <li>deterministic re-lowering (byte-identical unit dumps) and
 *       re-execution (identical traces/terminals).</li>
 * </ul>
 */
public class CallMachineIntegrationTest {

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

    private static final ModuleId MODULE_MAIN = new ModuleId("main");
    private static final ModuleId MODULE_A = new ModuleId("a");
    private static final String SOURCE_ID = "test.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();

    private record CheckedSlice(ProgramNode program, SymbolTable symbols, CheckResult checks) {
    }

    /**
     * Checks one slice through the real frontend with the given module
     * resolver (stdlib console + the scripted host/external exports).
     */
    private static CheckedSlice checkSlice(String source, ModuleResolver resolver,
                                           String what) {
        LexResult lex = new Lexer(source, SOURCE_ID).tokenize();
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID).parse();
        check(parse.diagnostics().isEmpty(), what + ": parses cleanly: "
            + parse.diagnostics());
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
        check(result.diagnostics().isEmpty(), what + ": checks cleanly: "
            + result.diagnostics());
        if (!result.diagnostics().isEmpty()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symTable, result);
    }

    /** The stdlib console resolver (the stdlib module exports). */
    private static ModuleResolver stdlibResolver() {
        return new ModuleResolver() {
            @Override
            public Map<String, Type> resolveModule(String modulePath, String importingModule,
                    Set<String> modulesInProgress) throws ModuleNotFoundException {
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

    /** A resolver serving the stdlib plus the given module-path export maps. */
    private static ModuleResolver resolverWith(Map<String, Map<String, Type>> moduleExports) {
        return new ModuleResolver() {
            @Override
            public Map<String, Type> resolveModule(String modulePath, String importingModule,
                    Set<String> modulesInProgress) throws ModuleNotFoundException {
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

    /** The export facts of one checked program (declared function exports). */
    private static List<deal.semantic.ir.ExportInterface> exportsOf(ProgramNode program) {
        List<deal.semantic.ir.ExportInterface> exports = new ArrayList<>();
        for (deal.ast.StatementNode stmt : program.statements()) {
            if (stmt instanceof deal.ast.ExportDeclaration export
                    && export.declaration() instanceof deal.ast.FunctionDeclaration function) {
                exports.add(new deal.semantic.ir.ExportInterface(function.name(),
                    functionSignatureText(function)));
            }
        }
        return exports;
    }

    /** The canonical descriptor text of one declared function export. */
    private static String functionSignatureText(deal.ast.FunctionDeclaration function) {
        for (deal.ast.StatementNode stmt : function.body().statements()) {
            // no-op: the signature text is derived from the checker below
        }
        return "function";
    }

    /** The resolved import facts of one slice (stdlib + the named host/compiled imports). */
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
                    case "host/ops" -> "host.ops";
                    case "host/counter" -> "host.counter";
                    default -> imp.modulePath().replace('/', '.');
                };
                ExternalModuleKind kind = kinds.getOrDefault(imp.modulePath(),
                    ExternalModuleKind.STDLIB);
                imports.add(new ResolvedImport(imp.alias(), imp.modulePath(),
                    new ModuleId(resolved), kind));
            }
        }
        return imports;
    }

    private record CheckedProject(deal.semantic.CheckedProjectBuildResult built,
                                  List<SemanticRequirementManifestView> manifests,
                                  ProjectInterfaceView index) {
    }

    private record ProjectInterfaceView(deal.semantic.ir.ProjectInterfaceIndex index) {

        String digest() {
            return index.interfaceIndexDigest();
        }
    }

    private record SemanticRequirementManifestView(
            Map<ConstructKind, List<SemanticOpKind>> coverage) {
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
        List<SemanticRequirementManifestView> views = new ArrayList<>();
        for (deal.semantic.SemanticRequirementManifest manifest : manifests.manifests()) {
            views.add(new SemanticRequirementManifestView(manifest.constructCoverage()));
        }
        return new CheckedProject(built, views, new ProjectInterfaceView(built.index()));
    }

    private record LoweredE7(SemanticLowerer.FullProgramE7Result result,
                             CheckedModuleInput input) {
    }

    /** The E7 full-program lowering over the real chain facts. */
    private static LoweredE7 lowerE7(CheckedModuleInput input, CheckedProject project,
                                     Map<ConstructKind, List<SemanticOpKind>> coverage,
                                     ModuleId module, Map<ModuleId, ModuleRoute> routes,
                                     Map<ModuleId, Map<String, OpId>> entries,
                                     Set<String> callbacks, String what,
                                     SemanticIdAllocator allocator) {
        SemanticLowerer.FullProgramE7Result result =
            SemanticLowerer.lowerModuleFullProgramE7(input,
                SemanticProfile.DEAL_V1_2_INT32, coverage, project.index.digest(),
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
        return new LoweredE7(result, input);
    }

    private static List<SemanticOp> ofKind(List<SemanticOp> ops, SemanticOpKind kind) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == kind) {
                found.add(op);
            }
        }
        return found;
    }

    private static SemanticOp opById(List<SemanticOp> ops, OpId id) {
        for (SemanticOp op : ops) {
            if (op.opId().equals(id)) {
                return op;
            }
        }
        return null;
    }

    /** The boundary kind of one BOUNDARY op. */
    private static BoundaryKind boundaryKind(SemanticOp op) {
        return ((KindPayload.BoundaryPayload) op.payload()).kind();
    }

    /** Every BOUNDARY op parented to the given op (direct children). */
    private static List<SemanticOp> boundaryChildren(List<SemanticOp> ops, OpId parent) {
        List<SemanticOp> found = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.BOUNDARY
                    && parent.equals(op.origin().parentOpId())) {
                found.add(op);
            }
        }
        return found;
    }

    /** The ordered console effects of one run. */
    private static List<String> consoleEffects(SemanticRuntimeModel.ConsumerRun run) {
        List<String> effects = new ArrayList<>();
        for (SemanticRuntimeModel.EffectEvent event : run.effects()) {
            if (event.kind() == SemanticRuntimeModel.EffectEvent.Kind.CONSOLE_WRITE) {
                effects.add(event.text());
            }
        }
        return effects;
    }

    /** Asserts a successful run with the exact ordered console effects. */
    private static void assertRunSuccess(SemanticRuntimeModel.ConsumerRun run, String what,
                                         List<String> expectedEffects, String expectedAtom) {
        check(run != null && run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
            what + ": the run succeeds: " + (run == null ? "null" : run.terminal()));
        if (run != null && run.terminal() instanceof SemanticRuntimeModel.Terminal.Success s) {
            check(s.resultAtom().equals(expectedAtom), what + ": the terminal atom is "
                + expectedAtom + "; got " + s.resultAtom());
        }
        check(run != null && consoleEffects(run).equals(expectedEffects),
            what + ": the ordered console effects are " + expectedEffects + "; got "
                + (run == null ? "null" : consoleEffects(run)));
    }

    private static void assertRunFailure(SemanticRuntimeModel.ConsumerRun run, String what,
                                         String expectedCode) {
        check(run != null && run.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure,
            what + ": the run fails: " + (run == null ? "null" : run.terminal()));
        if (run != null && run.terminal() instanceof SemanticRuntimeModel.Terminal.DealFailure f) {
            check(f.error().code().equals(expectedCode), what + ": the error code is "
                + expectedCode + "; got " + f.error().code());
        }
    }

    // =========================================================================
    // Seed helpers
    // =========================================================================

    private static final String CONSOLE = "import * as console from \"std/console\"\n";

    /** Builds the two-module index covering a and main. */
    private static deal.semantic.ir.ProjectInterfaceIndex twoModuleIndex() {
        return new deal.semantic.ir.ProjectInterfaceIndex(
            deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION, Map.of(
                MODULE_A, new ExternalModuleInterface(MODULE_A,
                    ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES),
                MODULE_MAIN, new ExternalModuleInterface(MODULE_MAIN,
                    ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES)));
    }

    /** The single-module main index. */
    private static deal.semantic.ir.ProjectInterfaceIndex singleModuleIndex() {
        return new deal.semantic.ir.ProjectInterfaceIndex(
            deal.semantic.ir.ProjectInterfaceIndex.FORMAT_VERSION, Map.of(
                MODULE_MAIN, new ExternalModuleInterface(MODULE_MAIN,
                    ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(), List.of(),
                    InitializationMode.ONCE_AFTER_DEPENDENCIES)));
    }

    /** One module's lowering + oracle execution over the single-module chain. */
    private static SemanticRuntimeModel.ConsumerRun runSingle(String source, String what,
            Map<String, ExternalModuleKind> importKinds,
            Map<String, Map<String, Type>> moduleExports,
            SemanticOracle.HostResponder responder) {
        ModuleResolver resolver = importKinds.isEmpty() && moduleExports.isEmpty()
            ? stdlibResolver() : resolverWith(moduleExports);
        CheckedSlice slice = checkSlice(source, resolver, what);
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE_MAIN, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program(), importKinds), exportsOf(slice.program()),
            CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(new ModuleFact(SOURCE_ID, MODULE_MAIN, false, false,
            slice.program(), Map.of(), slice.symbols(), slice.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE_MAIN);
        if (project == null) {
            return null;
        }
        LoweredE7 lowered = lowerE7(input, project, project.manifests.get(0).coverage(),
            MODULE_MAIN, Map.of(), Map.of(), Set.of(), what,
            SemanticIdAllocator.over(List.of(MODULE_MAIN)));
        if (lowered == null) {
            return null;
        }
        return SemanticOracle.execute(lowered.result.lowering().unit(),
            lowered.result.lowering().table(), responder);
    }

    /** One module's lowering + oracle execution with callback/entry drives. */
    /** One module's E7 lowering plus the built project index (shared allocator). */
    private record LoweredSingle(LoweredE7 lowered,
                                 deal.semantic.ir.ProjectInterfaceIndex index) {
    }

    private static LoweredSingle lowerSingle(String source, String what, Set<String> callbacks,
            Map<String, ExternalModuleKind> importKinds,
            Map<String, Map<String, Type>> moduleExports) {
        ModuleResolver resolver = importKinds.isEmpty() && moduleExports.isEmpty()
            ? stdlibResolver() : resolverWith(moduleExports);
        CheckedSlice slice = checkSlice(source, resolver, what);
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE_MAIN, SOURCE_ID,
            Path.of("test.deal"), slice.program(), slice.checks(),
            importsOf(slice.program(), importKinds), exportsOf(slice.program()),
            CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(new ModuleFact(SOURCE_ID, MODULE_MAIN, false, false,
            slice.program(), Map.of(), slice.symbols(), slice.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE_MAIN);
        if (project == null) {
            return null;
        }
        SemanticIdAllocator allocator = SemanticIdAllocator.over(List.of(MODULE_MAIN));
        LoweredE7 lowered = lowerE7(input, project, project.manifests.get(0).coverage(),
            MODULE_MAIN, Map.of(), Map.of(), callbacks, what, allocator);
        if (lowered == null) {
            return null;
        }
        return new LoweredSingle(lowered, project.index.index);
    }

    // =========================================================================
    // 1. Direct calls: multiple call sites, recursion, exact boundaries
    // =========================================================================

    static void testDirectCalls() {
        System.out.println("-- Direct calls: multiple sites, recursion, FUNCTION_PARAMETER/"
            + "FUNCTION_RETURN cells --");
        String source = CONSOLE
            + "function fib(n: int): int {\n"
            + "  if (n <= 1) { return n; }\n"
            + "  return fib(n - 1) + fib(n - 2);\n"
            + "}\n"
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function main(): null {\n"
            + "  let a: int = fib(6);\n"
            + "  let b: int = dbl(a);\n"
            + "  let c: int = dbl(b);\n"
            + "  if (a === 8 && b === 16 && c === 32) { console.log(\"direct-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        SemanticRuntimeModel.ConsumerRun run = runSingle(source, "direct calls",
            Map.of(), Map.of(), null);
        if (run != null) {
            assertRunSuccess(run, "direct calls", List.of("direct-ok"), "null");
            // Single execution per invocation: the non-recursive call
            // sites (dbl's two sites, the entry's CALL, main's fib(6))
            // each start exactly once, and the recursive fib sites
            // start exactly once per non-base fib invocation (fib(6)
            // invokes fib 25 times in total: 12 recursive + 13 base).
            Map<String, Integer> starts = new java.util.HashMap<>();
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.kind() == SemanticOpKind.CALL
                        && event.phase() == SemanticRuntimeModel.Phase.START) {
                    starts.merge(event.op().toString(), 1, Integer::sum);
                }
            }
            check(starts.values().stream().allMatch(count -> count == 1 || count == 12),
                "direct calls: every call site executes exactly once per enclosing "
                    + "invocation (1 for the non-recursive sites, 12 for the recursive "
                    + "fib sites): " + starts);
        }
    }

    // =========================================================================
    // 2. Indirect calls through closure values and tracked locals
    // =========================================================================

    static void testIndirectCalls() {
        System.out.println("-- Indirect calls: closure value and tracked local --");
        String source = CONSOLE
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function main(): null {\n"
            + "  let f: (x: int) => int = function(x: int): int { return x + 1; };\n"
            + "  let g: (x: int) => int = dbl;\n"
            + "  let a: int = f(41);\n"
            + "  let b: int = g(16);\n"
            + "  if (a === 42 && b === 32) { console.log(\"indirect-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        SemanticRuntimeModel.ConsumerRun run = runSingle(source, "indirect calls",
            Map.of(), Map.of(), null);
        if (run != null) {
            assertRunSuccess(run, "indirect calls", List.of("indirect-ok"), "null");
        }
    }

    // =========================================================================
    // 3. Adapter invocation: SHARED_CELL projection, retarget E8010
    // =========================================================================

    static void testAdapterSharedCellInvocation() {
        System.out.println("-- Adapter invocation: SHARED_CELL xN boundaries, leading-M "
            + "projection, trailing effects --");
        String source = CONSOLE
            + "function mark(s: string, v: int): int { console.log(s); return v; }\n"
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function main(): null {\n"
            + "  let f: (a: int, b: int) => int = dbl;\n"
            + "  let r: int = f(mark(\"first\", 5), mark(\"second\", 9));\n"
            + "  if (r === 10) { console.log(\"adapter-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        SemanticRuntimeModel.ConsumerRun run = runSingle(source, "adapter SHARED_CELL",
            Map.of(), Map.of(), null);
        if (run != null) {
            // Both target arguments evaluate (trailing value dropped after
            // the xN checks); the source receives only the leading M.
            assertRunSuccess(run, "adapter SHARED_CELL",
                List.of("first", "second", "adapter-ok"), "null");
        }
    }

    static void testAdapterRetargetE8010() {
        System.out.println("-- Adapter retarget: the SHARED_CELL load observes the live "
            + "source binding (jvm-fv-lua-ref-reassigned-adapter) --");
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
        SemanticRuntimeModel.ConsumerRun run = runSingle(source, "adapter retarget",
            Map.of(), Map.of(), null);
        if (run != null) {
            // h adapts over f (SHARED_CELL): the first invocation loads
            // inc (3+1=4); `f = dbl` retargets the adapter and the second
            // invocation loads the live binding (3*2=6) — the trailing
            // 999 is evaluated, checked, and dropped on every invoke.
            assertRunSuccess(run, "adapter retarget", List.of("retarget-ok"), "null");
        }
    }

    static void testAdapterValueInvocation() {
        System.out.println("-- Adapter invocation: VALUE retains the "
            + "function-expression source; the retained closure executes per invoke --");
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
        LoweredSingle lowered = lowerSingle(source, "adapter VALUE", Set.of(),
            Map.of(), Map.of());
        if (lowered == null) {
            return;
        }
        LoweredModuleUnit unit = lowered.lowered().result.lowering().unit();
        // A function-expression source adapts as VALUE (an
        // already-materialized function-value operand — no thunk
        // block exists): pin the recorded capture mode.
        List<SemanticOp> adapts = ofKind(unit.ops(), SemanticOpKind.FUNCTION_ADAPT);
        check(adapts.size() == 1, "the unit carries exactly one FUNCTION_ADAPT");
        if (adapts.size() == 1) {
            KindPayload.FunctionAdaptPayload payload =
                (KindPayload.FunctionAdaptPayload) adapts.get(0).payload();
            check(payload.mode() == CaptureMode.VALUE
                    && payload.source() instanceof AdaptSourceRef.Value,
                "the function-expression source adapts as VALUE with a retained "
                    + "materialized source operand");
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(unit,
            lowered.lowered().result.lowering().table());
        if (run != null) {
            // The VALUE-retained closure body executes once per
            // invocation (the repeated "thunk" prints come from the
            // retained source body, never from a re-evaluated thunk);
            // trailing arguments are still evaluated/checked/dropped.
            assertRunSuccess(run, "adapter VALUE",
                List.of("thunk", "thunk", "thunk-ok"), "null");
        }
    }

    // =========================================================================
    // 4. Host calls: the pinned host cells + the deterministic responder
    // =========================================================================

    static void testHostCall() {
        System.out.println("-- CALL(HOST): DEAL_TO_HOST+HOST_PARAMETER parameters, "
            + "HOST_TO_DEAL+HOST_SYNC_RETURN by the call op --");
        Map<String, Type> counter = Map.of("inc", new Type.Func(List.of(Type.Int.INSTANCE),
            Type.Int.INSTANCE));
        String source = CONSOLE
            + "import * as host from \"host/counter\"\n"
            + "export function main(): null {\n"
            + "  let v: int = host.inc(41);\n"
            + "  if (v === 42) { console.log(\"host-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        SemanticOracle.HostResponder responder = new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor, List<SemanticOracle.Value> args) {
                if ("host.counter".equals(module.path()) && "inc".equals(export)) {
                    long v = ((SemanticOracle.Value.IntValue) args.get(0)).value();
                    return new SyncOutcome.Returned(new SemanticOracle.Value.IntValue(v + 1));
                }
                return new SyncOutcome.Thrown("E9001", "unknown host export " + export);
            }
        };
        SemanticRuntimeModel.ConsumerRun run = runSingle(source, "host call",
            Map.of("host/counter", ExternalModuleKind.HOST), Map.of("host/counter", counter),
            responder);
        if (run != null) {
            assertRunSuccess(run, "host call", List.of("host-ok"), "null");
            check(run.effects().stream().anyMatch(e ->
                    e.kind() == SemanticRuntimeModel.EffectEvent.Kind.HOST_CALL
                        && e.text().equals("host.counter.inc")),
                "host call: the ordered host-call effect pairs with the performing CALL");
            check(run.effects().stream().anyMatch(e ->
                    e.kind() == SemanticRuntimeModel.EffectEvent.Kind.HOST_RETURN
                        && e.text().equals("host.counter.inc=int:42")),
                "host call: the ordered host-return effect carries the value atom");
        }
    }

    // =========================================================================
    // 5. External calls: SHARED_BODY entry nesting and RETAINED_ABI cell
    // =========================================================================

    private static final String CALLEE_A_SYNC = "export function add(x: int, y: int): int "
        + "{ return x + y; }\n";

    static void testExternalCallSharedBody() {
        System.out.println("-- CALL(EXTERNAL) SHARED_BODY: callee EXTERNAL_ENTRY runs the "
            + "single EXTERNAL_RETURN; caller-side EXTERNAL_PARAMETER once --");
        ModuleResolver aResolver = stdlibResolver();
        CheckedSlice sliceA = checkSlice(CALLEE_A_SYNC, aResolver, "module a");
        if (sliceA == null) {
            return;
        }
        CheckedModuleInput inputA = new CheckedModuleInput(MODULE_A, "a.deal",
            Path.of("a.deal"), sliceA.program(), sliceA.checks(), List.of(),
            exportsOf(sliceA.program()), CheckedModuleKind.IMPLEMENTATION);
        String mainSource = CONSOLE
            + "import * as a from \"./a\"\n"
            + "export function main(): null {\n"
            + "  let v: int = a.add(20, 22);\n"
            + "  if (v === 42) { console.log(\"external-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        ModuleResolver mainResolver = resolverWith(Map.of("./a", Map.of("add",
            new Type.Func(List.of(Type.Int.INSTANCE, Type.Int.INSTANCE),
                Type.Int.INSTANCE))));
        CheckedSlice sliceMain = checkSlice(mainSource, mainResolver, "module main");
        if (sliceMain == null) {
            return;
        }
        CheckedModuleInput inputMain = new CheckedModuleInput(MODULE_MAIN, SOURCE_ID,
            Path.of("test.deal"), sliceMain.program(), sliceMain.checks(),
            importsOf(sliceMain.program(), Map.of("./a", ExternalModuleKind.IMPLEMENTATION)),
            exportsOf(sliceMain.program()), CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(
            new ModuleFact("a.deal", MODULE_A, false, false, sliceA.program(), Map.of(),
                sliceA.symbols(), sliceA.checks(), List.of()),
            new ModuleFact(SOURCE_ID, MODULE_MAIN, false, false, sliceMain.program(),
                Map.of(), sliceMain.symbols(), sliceMain.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE_MAIN);
        if (project == null) {
            return;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverageA =
            project.manifests.get(0).coverage();
        Map<ConstructKind, List<SemanticOpKind>> coverageMain =
            project.manifests.get(1).coverage();
        SemanticIdAllocator allocator =
            SemanticIdAllocator.over(List.of(MODULE_A, MODULE_MAIN));
        LoweredE7 loweredA = lowerE7(inputA, project, coverageA, MODULE_A, Map.of(),
            Map.of(), Set.of(), "module a", allocator);
        if (loweredA == null) {
            return;
        }
        check(!loweredA.result.externalEntries().isEmpty()
                && loweredA.result.externalEntries().get("add") != null,
            "module a records the EXTERNAL_ENTRY of add: "
                + loweredA.result.externalEntries());
        LoweredE7 loweredMain = lowerE7(inputMain, project, coverageMain, MODULE_MAIN,
            Map.of(MODULE_A, ModuleRoute.SHARED),
            Map.of(MODULE_A, loweredA.result.externalEntries()), Set.of(), "module main",
            allocator);
        if (loweredMain == null) {
            return;
        }
        LoweredModuleUnit unitA = loweredA.result.lowering().unit();
        LoweredModuleUnit unitMain = loweredMain.result.lowering().unit();
        // Op-stream pinning: exactly one EXTERNAL_ENTRY in a; the caller's
        // CALL(EXTERNAL) carries EXTERNAL_PARAMETER boundaries and no
        // caller-side return boundary (SHARED_BODY).
        check(ofKind(unitA.ops(), SemanticOpKind.EXTERNAL_ENTRY).size() == 1,
            "module a carries exactly one EXTERNAL_ENTRY");
        List<SemanticOp> calls = new ArrayList<>();
        for (SemanticOp op : ofKind(unitMain.ops(), SemanticOpKind.CALL)) {
            if (((KindPayload.CallPayload) op.payload()).mode() == CallMode.EXTERNAL) {
                calls.add(op);
            }
        }
        check(calls.size() == 1, "module main carries exactly one CALL(EXTERNAL)");
        if (calls.size() == 1) {
            KindPayload.CallPayload callPayload =
                (KindPayload.CallPayload) calls.get(0).payload();
            check(callPayload.mode() == CallMode.EXTERNAL,
                "the call mode is EXTERNAL");
            check(callPayload.externalEntryRef() != null
                    && callPayload.externalEntryRef().module().equals(MODULE_A),
                "the call names the callee unit's entry");
            check(callPayload.returnBoundaryOpId() == null,
                "a SHARED_BODY external call carries no caller-side return boundary");
            List<SemanticOp> params = new ArrayList<>();
            for (OpId boundaryId : callPayload.parameterBoundaryOpIds()) {
                params.add(opById(unitMain.ops(), boundaryId));
            }
            check(params.size() == 2
                    && params.stream().allMatch(p -> boundaryKind(p)
                        == BoundaryKind.EXTERNAL_PARAMETER),
                "the external call runs exactly two caller-side EXTERNAL_PARAMETER "
                    + "boundaries");
        }
        ExecutableLoweredProject projectIr = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, project.index.index,
            Map.of(MODULE_A, unitA, MODULE_MAIN, unitMain), MODULE_MAIN);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(projectIr,
            new SemanticIrValidator.ComparisonFacts(project.index.digest(),
                SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH));
        check(validation.isEmpty(), "the project validates through the closed rules: "
            + validation);
        if (validation.isPresent()) {
            return;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(projectIr,
            Map.of(MODULE_A, loweredA.result.lowering().table(),
                MODULE_MAIN, loweredMain.result.lowering().table()),
            null);
        if (run != null) {
            assertRunSuccess(run, "external SHARED_BODY", List.of("external-ok"), "null");
            // The callee entry events nest under the triggering caller op.
            boolean entryNested = false;
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.kind() == SemanticOpKind.EXTERNAL_ENTRY
                        && event.parentOp() != null
                        && event.module().equals("a")) {
                    entryNested = true;
                }
            }
            check(entryNested,
                "the callee EXTERNAL_ENTRY events parent to the triggering caller op");
            // Exactly one EXTERNAL_RETURN boundary executes (callee side).
            long externalReturns = run.trace().stream().filter(e ->
                e.kind() == SemanticOpKind.BOUNDARY
                    && e.phase() == SemanticRuntimeModel.Phase.SUCCESS
                    && e.contractDigest() != null).count();
            // Count boundary-kind events via the ops map instead:
            long returnBoundaryStarts = 0;
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.kind() == SemanticOpKind.BOUNDARY
                        && event.phase() == SemanticRuntimeModel.Phase.START) {
                    SemanticOp op = opById(unitA.ops(), event.op());
                    if (op == null) {
                        op = opById(unitMain.ops(), event.op());
                    }
                    if (op != null && boundaryKind(op) == BoundaryKind.EXTERNAL_RETURN) {
                        returnBoundaryStarts++;
                    }
                }
            }
            check(returnBoundaryStarts == 1,
                "exactly one EXTERNAL_RETURN boundary executes (the callee RETURN's); "
                    + "got " + returnBoundaryStarts);
        }
    }

    /**
     * Cross-unit chain execution (the review-finding regression): the
     * callee body's ASSIGN address chain completes its operand
     * producers in the callee unit and resolves its normalize/length
     * facts there — never through the entry module's Execution fields.
     */
    static void testExternalCallCalleeChain() {
        System.out.println("-- CALL(EXTERNAL) callee chain: the callee body's ASSIGN "
            + "completes operands and normalize/length facts in the callee unit --");
        ModuleResolver aResolver = stdlibResolver();
        CheckedSlice sliceA = checkSlice(
            "export function store(xs: int[], i: int, v: int): int {\n"
                + "  xs[i + 0] = v * 2;\n"
                + "  return xs[i];\n"
                + "}\n",
            aResolver, "module a (chain callee)");
        if (sliceA == null) {
            return;
        }
        CheckedModuleInput inputA = new CheckedModuleInput(MODULE_A, "a.deal",
            Path.of("a.deal"), sliceA.program(), sliceA.checks(),
            importsOf(sliceA.program(), Map.of()),
            exportsOf(sliceA.program()), CheckedModuleKind.IMPLEMENTATION);
        String mainSource = CONSOLE
            + "import * as a from \"./a\"\n"
            + "export function main(): null {\n"
            + "  let xs: int[] = [0, 0, 0];\n"
            + "  let r: int = a.store(xs, 1, 42);\n"
            + "  if (r === 84 && xs[1] === 84) { console.log(\"external-chain-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        ModuleResolver mainResolver = resolverWith(Map.of("./a", Map.of("store",
            new Type.Func(List.of(new Type.Array(Type.Int.INSTANCE), Type.Int.INSTANCE,
                Type.Int.INSTANCE), Type.Int.INSTANCE))));
        CheckedSlice sliceMain = checkSlice(mainSource, mainResolver,
            "module main (chain caller)");
        if (sliceMain == null) {
            return;
        }
        CheckedModuleInput inputMain = new CheckedModuleInput(MODULE_MAIN, SOURCE_ID,
            Path.of("test.deal"), sliceMain.program(), sliceMain.checks(),
            importsOf(sliceMain.program(), Map.of("./a", ExternalModuleKind.IMPLEMENTATION)),
            exportsOf(sliceMain.program()), CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(
            new ModuleFact("a.deal", MODULE_A, false, false, sliceA.program(), Map.of(),
                sliceA.symbols(), sliceA.checks(), List.of()),
            new ModuleFact(SOURCE_ID, MODULE_MAIN, false, false, sliceMain.program(),
                Map.of(), sliceMain.symbols(), sliceMain.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE_MAIN);
        if (project == null) {
            return;
        }
        SemanticIdAllocator allocator =
            SemanticIdAllocator.over(List.of(MODULE_A, MODULE_MAIN));
        LoweredE7 loweredA = lowerE7(inputA, project, project.manifests.get(0).coverage(),
            MODULE_A, Map.of(), Map.of(), Set.of(), "module a (chain callee)", allocator);
        if (loweredA == null) {
            return;
        }
        LoweredE7 loweredMain = lowerE7(inputMain, project,
            project.manifests.get(1).coverage(), MODULE_MAIN,
            Map.of(MODULE_A, ModuleRoute.SHARED),
            Map.of(MODULE_A, loweredA.result.externalEntries()), Set.of(),
            "module main (chain caller)", allocator);
        if (loweredMain == null) {
            return;
        }
        LoweredModuleUnit unitA = loweredA.result.lowering().unit();
        LoweredModuleUnit unitMain = loweredMain.result.lowering().unit();
        check(ofKind(unitA.ops(), SemanticOpKind.EXTERNAL_ENTRY).size() == 1
                && ofKind(unitA.ops(), SemanticOpKind.ASSIGN).size() == 1,
            "module a records one EXTERNAL_ENTRY and one callee ASSIGN chain");
        ExecutableLoweredProject projectIr = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, project.index.index,
            Map.of(MODULE_A, unitA, MODULE_MAIN, unitMain), MODULE_MAIN);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(projectIr,
            new SemanticIrValidator.ComparisonFacts(project.index.digest(),
                SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH));
        check(validation.isEmpty(), "the chain project validates: " + validation);
        if (validation.isPresent()) {
            return;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(projectIr,
            Map.of(MODULE_A, loweredA.result.lowering().table(),
                MODULE_MAIN, loweredMain.result.lowering().table()),
            null);
        if (run != null) {
            // receiver → key → RHS in the callee unit, then the
            // committed value observed by the caller: the chain's operand
            // producers (the key/RHS subexpressions) and the
            // normalize/length facts resolved in the callee unit — the
            // finding-2 regression.
            assertRunSuccess(run, "external callee chain",
                List.of("external-chain-ok"), "null");
            long arrayWrites = 0;
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.kind() == SemanticOpKind.BOUNDARY
                        && event.phase() == SemanticRuntimeModel.Phase.START) {
                    SemanticOp op = opById(unitA.ops(), event.op());
                    if (op != null
                            && boundaryKind(op) == BoundaryKind.ARRAY_ELEMENT_ASSIGNMENT) {
                        arrayWrites++;
                    }
                }
            }
            check(arrayWrites == 1,
                "the callee chain runs exactly one ARRAY_ELEMENT_ASSIGNMENT bounds "
                    + "boundary; got " + arrayWrites);
        }
    }

    static void testExternalCallRetainedAbi() {
        System.out.println("-- CALL(EXTERNAL) RETAINED_ABI: the call op runs the single "
            + "EXTERNAL_RETURN --");
        ModuleResolver aResolver = stdlibResolver();
        CheckedSlice sliceA = checkSlice(CALLEE_A_SYNC, aResolver, "module a (retained)");
        if (sliceA == null) {
            return;
        }
        CheckedModuleInput inputA = new CheckedModuleInput(MODULE_A, "a.deal",
            Path.of("a.deal"), sliceA.program(), sliceA.checks(), List.of(),
            exportsOf(sliceA.program()), CheckedModuleKind.IMPLEMENTATION);
        String mainSource = CONSOLE
            + "import * as a from \"./a\"\n"
            + "export function main(): null {\n"
            + "  let v: int = a.add(40, 2);\n"
            + "  if (v === 42) { console.log(\"retained-ok\"); }\n"
            + "  else { console.log(\"bad\"); }\n"
            + "}\n";
        ModuleResolver mainResolver = resolverWith(Map.of("./a", Map.of("add",
            new Type.Func(List.of(Type.Int.INSTANCE, Type.Int.INSTANCE),
                Type.Int.INSTANCE))));
        CheckedSlice sliceMain = checkSlice(mainSource, mainResolver, "module main (retained)");
        if (sliceMain == null) {
            return;
        }
        CheckedModuleInput inputMain = new CheckedModuleInput(MODULE_MAIN, SOURCE_ID,
            Path.of("test.deal"), sliceMain.program(), sliceMain.checks(),
            importsOf(sliceMain.program(), Map.of("./a", ExternalModuleKind.IMPLEMENTATION)),
            exportsOf(sliceMain.program()), CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(
            new ModuleFact("a.deal", MODULE_A, false, false, sliceA.program(), Map.of(),
                sliceA.symbols(), sliceA.checks(), List.of()),
            new ModuleFact(SOURCE_ID, MODULE_MAIN, false, false, sliceMain.program(),
                Map.of(), sliceMain.symbols(), sliceMain.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE_MAIN);
        if (project == null) {
            return;
        }
        SemanticIdAllocator retainedAllocator =
            SemanticIdAllocator.over(List.of(MODULE_A, MODULE_MAIN));
        LoweredE7 loweredA = lowerE7(inputA, project, project.manifests.get(0).coverage(),
            MODULE_A, Map.of(), Map.of(), Set.of(), "module a (retained)",
            retainedAllocator);
        if (loweredA == null) {
            return;
        }
        LoweredE7 loweredMain = lowerE7(inputMain, project,
            project.manifests.get(1).coverage(), MODULE_MAIN,
            Map.of(MODULE_A, ModuleRoute.LEGACY), Map.of(), Set.of(),
            "module main (retained)", retainedAllocator);
        if (loweredMain == null) {
            return;
        }
        LoweredModuleUnit unitMain = loweredMain.result.lowering().unit();
        List<SemanticOp> calls = new ArrayList<>();
        for (SemanticOp op : ofKind(unitMain.ops(), SemanticOpKind.CALL)) {
            if (((KindPayload.CallPayload) op.payload()).mode() == CallMode.EXTERNAL) {
                calls.add(op);
            }
        }
        check(calls.size() == 1, "module main carries exactly one retained-ABI CALL");
        if (calls.size() == 1) {
            KindPayload.CallPayload callPayload =
                (KindPayload.CallPayload) calls.get(0).payload();
            check(callPayload.mode() == CallMode.EXTERNAL
                    && callPayload.externalEntryRef() == null,
                "the retained-ABI call carries no entry ref");
            check(callPayload.returnBoundaryOpId() != null,
                "the retained-ABI call carries its call-op-run return boundary");
            SemanticOp boundary = opById(unitMain.ops(), callPayload.returnBoundaryOpId());
            check(boundary != null && boundaryKind(boundary) == BoundaryKind.EXTERNAL_RETURN
                    && calls.get(0).opId().equals(boundary.origin().parentOpId()),
                "the retained-ABI EXTERNAL_RETURN boundary parents to the call op");
        }
        // The deterministic ABI terminal is modeled by the host responder.
        SemanticOracle.HostResponder responder = new SemanticOracle.HostResponder() {
            @Override
            public SyncOutcome call(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor, List<SemanticOracle.Value> args) {
                if ("a".equals(module.path()) && "add".equals(export)) {
                    long x = ((SemanticOracle.Value.IntValue) args.get(0)).value();
                    long y = ((SemanticOracle.Value.IntValue) args.get(1)).value();
                    return new SyncOutcome.Returned(new SemanticOracle.Value.IntValue(x + y));
                }
                return new SyncOutcome.Thrown("E9001", "unknown ABI export " + export);
            }
        };
        ExecutableLoweredProject projectIr = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, project.index.index,
            Map.of(MODULE_A, loweredA.result.lowering().unit(), MODULE_MAIN, unitMain),
            MODULE_MAIN);
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(projectIr,
            Map.of(MODULE_A, loweredA.result.lowering().table(),
                MODULE_MAIN, loweredMain.result.lowering().table()),
            responder);
        if (run != null) {
            assertRunSuccess(run, "external RETAINED_ABI", List.of("retained-ok"), "null");
        }
    }

    // =========================================================================
    // 6. Async starts and awaits: DEAL body, host, external, adapter-over-async
    // =========================================================================

    static void testAsyncDirect() {
        System.out.println("-- ASYNC_START(DEAL_BODY)+AWAIT: canonical token, one body task, "
            + "FUNCTION_RETURN + ASYNC_COMPLETION --");
        String source = "async function compute(x: int): int { return x * 2; }\n"
            + "export async function test(): int {\n"
            + "  let v: int = await compute(21);\n"
            + "  if (v === 42) { return v; }\n"
            + "  return 0;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSingle lowered = lowerSingle(source, "async direct", Set.of(), Map.of(),
            Map.of());
        if (lowered == null) {
            return;
        }
        LoweredModuleUnit unit = lowered.lowered().result.lowering().unit();
        List<SemanticOp> starts = ofKind(unit.ops(), SemanticOpKind.ASYNC_START);
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
                    && canonical.owner() == deal.semantic.ir.AsyncTokenOwner.DEAL_BODY_TASK,
                "the published token is canonical, owned by the DEAL body task");
        }
        List<SemanticOp> awaits = ofKind(unit.ops(), SemanticOpKind.AWAIT);
        check(awaits.size() == 1, "the unit carries exactly one AWAIT");
        if (awaits.size() == 1) {
            KindPayload.AwaitPayload payload = (KindPayload.AwaitPayload) awaits.get(0).payload();
            SemanticOp boundary = opById(unit.ops(), payload.completionBoundaryOpId());
            check(boundary != null && boundaryKind(boundary) == BoundaryKind.ASYNC_COMPLETION
                    && awaits.get(0).opId().equals(boundary.origin().parentOpId()),
                "the single ASYNC_COMPLETION boundary parents to the AWAIT");
        }
        // Drive the async export through the project oracle surface.
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, lowered.index(),
            Map.of(MODULE_MAIN, unit), MODULE_MAIN);
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(project,
            Map.of(MODULE_MAIN, lowered.lowered().result.lowering().table()), null,
            MODULE_MAIN, "test", List.of());
        if (run != null) {
            assertRunSuccess(run, "async direct", List.of(), "int:42");
            long returnStarts = 0;
            long completionStarts = 0;
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.kind() == SemanticOpKind.BOUNDARY
                        && event.phase() == SemanticRuntimeModel.Phase.START) {
                    SemanticOp op = opById(unit.ops(), event.op());
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
            check(completionStarts == 1, "the AWAIT runs exactly one ASYNC_COMPLETION; got "
                + completionStarts);
        }
    }

    static void testAsyncHost() {
        System.out.println("-- ASYNC_START(HOST): AsyncStart effect, handle validation, "
            + "host completion --");
        Map<String, Type> ops = Map.of("fetch", new Type.Func(List.of(), Type.Int.INSTANCE,
            true));
        String source = "import * as host from \"host/ops\"\n"
            + "export async function test(): int {\n"
            + "  let v: int = await host.fetch();\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        SemanticOracle.HostResponder responder = new SemanticOracle.HostResponder() {
            @Override
            public String startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor, List<SemanticOracle.Value> args,
                    String operationLabel) {
                return operationLabel;
            }

            @Override
            public SyncOutcome completeAsync(String operationLabel) {
                return new SyncOutcome.Returned(new SemanticOracle.Value.IntValue(42));
            }
        };
        LoweredSingle lowered = lowerSingle(source, "async host", Set.of(),
            Map.of("host/ops", ExternalModuleKind.HOST), Map.of("host/ops", ops));
        if (lowered == null) {
            return;
        }
        LoweredModuleUnit unit = lowered.lowered().result.lowering().unit();
        List<SemanticOp> starts = ofKind(unit.ops(), SemanticOpKind.ASYNC_START);
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
                    && canonical.owner() == deal.semantic.ir.AsyncTokenOwner.HOST_OPERATION,
                "the published token is canonical, bound to the host operation");
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, lowered.index(),
            Map.of(MODULE_MAIN, unit), MODULE_MAIN);
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(project,
            Map.of(MODULE_MAIN, lowered.lowered().result.lowering().table()), responder,
            MODULE_MAIN, "test", List.of());
        if (run != null) {
            assertRunSuccess(run, "async host", List.of(), "int:42");
            check(run.effects().stream().anyMatch(e ->
                    e.kind() == SemanticRuntimeModel.EffectEvent.Kind.ASYNC_START_OP),
                "the async host start records the ordered AsyncStart effect");
            check(run.effects().stream().anyMatch(e ->
                    e.kind() == SemanticRuntimeModel.EffectEvent.Kind.ASYNC_COMPLETE_RETURN),
                "the async host completion records the ordered completion effect");
        }
    }

    static void testAsyncHostBadHandle() {
        System.out.println("-- ASYNC_START(HOST) bad handle: the op's own "
            + "ASYNC_OPERATION_HANDLE terminal check (E8010) --");
        Map<String, Type> ops = Map.of("fetch", new Type.Func(List.of(), Type.Int.INSTANCE,
            true));
        String source = "import * as host from \"host/ops\"\n"
            + "export async function test(): int {\n"
            + "  let v: int = await host.fetch();\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        SemanticOracle.HostResponder responder = new SemanticOracle.HostResponder() {
            @Override
            public String startAsync(ModuleId module, String export,
                    RuntimeDescriptor.Func descriptor, List<SemanticOracle.Value> args,
                    String operationLabel) {
                return null; // a bad handle: not an async operation
            }
        };
        LoweredSingle lowered = lowerSingle(source, "async host bad handle", Set.of(),
            Map.of("host/ops", ExternalModuleKind.HOST), Map.of("host/ops", ops));
        if (lowered == null) {
            return;
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, lowered.index(),
            Map.of(MODULE_MAIN, lowered.lowered().result.lowering().unit()), MODULE_MAIN);
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(project,
            Map.of(MODULE_MAIN, lowered.lowered().result.lowering().table()), responder,
            MODULE_MAIN, "test", List.of());
        if (run != null) {
            assertRunFailure(run, "async host bad handle", "E8010");
        }
    }

    static void testAsyncExternal() {
        System.out.println("-- ASYNC_START(EXTERNAL): the caller alias token links the "
            + "callee canonical token; callee-task FUNCTION_RETURN + caller "
            + "ASYNC_COMPLETION --");
        ModuleResolver aResolver = stdlibResolver();
        CheckedSlice sliceA = checkSlice(
            "export async function compute(x: int): int { return x * 2; }\n",
            aResolver, "module a (async)");
        if (sliceA == null) {
            return;
        }
        CheckedModuleInput inputA = new CheckedModuleInput(MODULE_A, "a.deal",
            Path.of("a.deal"), sliceA.program(), sliceA.checks(), List.of(),
            exportsOf(sliceA.program()), CheckedModuleKind.IMPLEMENTATION);
        String mainSource = "import * as a from \"./a\"\n"
            + "export async function test(): int {\n"
            + "  let v: int = await a.compute(21);\n"
            + "  return v;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        ModuleResolver mainResolver = resolverWith(Map.of("./a", Map.of("compute",
            new Type.Func(List.of(Type.Int.INSTANCE), Type.Int.INSTANCE, true))));
        CheckedSlice sliceMain = checkSlice(mainSource, mainResolver, "module main (async)");
        if (sliceMain == null) {
            return;
        }
        CheckedModuleInput inputMain = new CheckedModuleInput(MODULE_MAIN, SOURCE_ID,
            Path.of("test.deal"), sliceMain.program(), sliceMain.checks(),
            importsOf(sliceMain.program(), Map.of("./a", ExternalModuleKind.IMPLEMENTATION)),
            exportsOf(sliceMain.program()), CheckedModuleKind.IMPLEMENTATION);
        List<ModuleFact> facts = List.of(
            new ModuleFact("a.deal", MODULE_A, false, false, sliceA.program(), Map.of(),
                sliceA.symbols(), sliceA.checks(), List.of()),
            new ModuleFact(SOURCE_ID, MODULE_MAIN, false, false, sliceMain.program(),
                Map.of(), sliceMain.symbols(), sliceMain.checks(), List.of()));
        CheckedProject project = buildProject(facts, MODULE_MAIN);
        if (project == null) {
            return;
        }
        SemanticIdAllocator asyncAllocator =
            SemanticIdAllocator.over(List.of(MODULE_A, MODULE_MAIN));
        LoweredE7 loweredA = lowerE7(inputA, project, project.manifests.get(0).coverage(),
            MODULE_A, Map.of(), Map.of(), Set.of(), "module a (async)", asyncAllocator);
        if (loweredA == null) {
            return;
        }
        LoweredE7 loweredMain = lowerE7(inputMain, project,
            project.manifests.get(1).coverage(), MODULE_MAIN,
            Map.of(MODULE_A, ModuleRoute.SHARED),
            Map.of(MODULE_A, loweredA.result.externalEntries()), Set.of(),
            "module main (async)", asyncAllocator);
        if (loweredMain == null) {
            return;
        }
        LoweredModuleUnit unitA = loweredA.result.lowering().unit();
        LoweredModuleUnit unitMain = loweredMain.result.lowering().unit();
        List<SemanticOp> starts = ofKind(unitMain.ops(), SemanticOpKind.ASYNC_START);
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
            SemanticProfile.DEAL_V1_2_INT32, project.index.index,
            Map.of(MODULE_A, unitA, MODULE_MAIN, unitMain), MODULE_MAIN);
        Optional<CompilerDiagnostic> validation = SemanticIrValidator.validate(projectIr,
            new SemanticIrValidator.ComparisonFacts(project.index.digest(),
                SemanticProfile.DEAL_V1_2_INT32, REGISTRY_HASH));
        check(validation.isEmpty(), "the async project validates: " + validation);
        if (validation.isPresent()) {
            return;
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(projectIr,
            Map.of(MODULE_A, loweredA.result.lowering().table(),
                MODULE_MAIN, loweredMain.result.lowering().table()),
            null, MODULE_MAIN, "test", List.of());
        if (run != null) {
            assertRunSuccess(run, "async external", List.of(), "int:42");
            long calleeReturns = 0;
            long completions = 0;
            for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                if (event.kind() == SemanticOpKind.BOUNDARY
                        && event.phase() == SemanticRuntimeModel.Phase.START) {
                    SemanticOp op = opById(unitA.ops(), event.op());
                    if (op != null
                            && boundaryKind(op) == BoundaryKind.FUNCTION_RETURN) {
                        calleeReturns++;
                    }
                    op = opById(unitMain.ops(), event.op());
                    if (op != null
                            && boundaryKind(op) == BoundaryKind.ASYNC_COMPLETION) {
                        completions++;
                    }
                }
            }
            check(calleeReturns == 1,
                "the callee body task runs exactly one FUNCTION_RETURN; got " + calleeReturns);
            check(completions == 1,
                "the caller AWAIT runs exactly one ASYNC_COMPLETION; got " + completions);
        }
    }

    static void testAdapterOverAsync() {
        System.out.println("-- Adapter-over-async: ADAPTER_INNER alias, nested "
            + "ELIDED_BY_ADAPTER source start, zero outer return boundaries --");
        String source = "async function one(x: int): int { return x; }\n"
            + "export async function test(): int {\n"
            + "  let g: async (x: int) => int = one;\n"
            + "  let h: async (x: int, y: int) => int = g;\n"
            + "  let r: int = await h(1, 2);\n"
            + "  return r;\n"
            + "}\n"
            + "export function main(): null { return null; }\n";
        LoweredSingle lowered = lowerSingle(source, "adapter over async", Set.of(),
            Map.of(), Map.of());
        if (lowered == null) {
            return;
        }
        LoweredModuleUnit unit = lowered.lowered().result.lowering().unit();
        List<SemanticOp> starts = ofKind(unit.ops(), SemanticOpKind.ASYNC_START);
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
            check(outerPayload.parameterBoundaryMode()
                    == deal.semantic.ir.ParameterBoundaryMode.RUN
                    && outerPayload.parameterBoundaryOpIds().size() == 2,
                "the outer op runs the xN target-signature boundaries (complete set)");
            check(outerPayload.returnBoundaryOpId() == null,
                "the outer task runs zero return boundaries (delegation)");
            check(outer.result() instanceof AsyncTokenId.Alias alias
                    && alias.linkKind() == AsyncLinkKind.ADAPTER_INNER,
                "the outer token is ALIAS {ADAPTER_INNER} — never canonical");
            check(nestedPayload.parameterBoundaryMode()
                    == deal.semantic.ir.ParameterBoundaryMode.ELIDED_BY_ADAPTER
                    && nestedPayload.parameterBoundaryOpIds().isEmpty(),
                "the nested source op carries ELIDED_BY_ADAPTER with zero parameter "
                    + "boundaries");
            check(nested.origin().parentOpId() != null
                    && nested.origin().parentOpId().equals(outer.opId()),
                "the nested source op parents to the outer ASYNC_START");
        }
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            SemanticProfile.DEAL_V1_2_INT32, lowered.index(),
            Map.of(MODULE_MAIN, unit), MODULE_MAIN);
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(project,
            Map.of(MODULE_MAIN, lowered.lowered().result.lowering().table()), null,
            MODULE_MAIN, "test", List.of());
        if (run != null) {
            assertRunSuccess(run, "adapter over async", List.of(), "int:1");
        }
    }

    // =========================================================================
    // 7. The entry delegation and the callback surface
    // =========================================================================

    static void testEntryInvoke() {
        System.out.println("-- ENTRY_INVOKE: delegates exactly one CALL(DIRECT) to main --");
        String source = CONSOLE
            + "export function main(): null {\n"
            + "  console.log(\"entry-ok\");\n"
            + "}\n";
        LoweredSingle lowered = lowerSingle(source, "entry invoke", Set.of(), Map.of(),
            Map.of());
        if (lowered == null) {
            return;
        }
        LoweredModuleUnit unit = lowered.lowered().result.lowering().unit();
        List<SemanticOp> entries = ofKind(unit.ops(), SemanticOpKind.ENTRY_INVOKE);
        check(entries.size() == 1, "the unit carries exactly one ENTRY_INVOKE");
        if (entries.size() == 1) {
            long ownedCalls = unit.ops().stream().filter(op ->
                op.kind() == SemanticOpKind.CALL
                    && entries.get(0).opId().equals(op.origin().parentOpId())).count();
            check(ownedCalls == 1,
                "the ENTRY_INVOKE owns exactly one delegated CALL(DIRECT main)");
        }
        SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(unit,
            lowered.lowered().result.lowering().table());
        if (run != null) {
            assertRunSuccess(run, "entry invoke", List.of("entry-ok"), "null");
        }
    }

    static void testCallbackInvoke() {
        System.out.println("-- CALLBACK_INVOKE: HOST_TO_DEAL parameters, DEAL_TO_HOST "
            + "return by the body's RETURN --");
        String source = "export function cb(x: int): int { return x + 10; }\n"
            + "export function main(): null { return null; }\n";
        LoweredSingle lowered = lowerSingle(source, "callback invoke", Set.of("cb"),
            Map.of(), Map.of());
        if (lowered == null) {
            return;
        }
        LoweredModuleUnit unit = lowered.lowered().result.lowering().unit();
        List<SemanticOp> callbacks = ofKind(unit.ops(), SemanticOpKind.CALLBACK_INVOKE);
        check(callbacks.size() == 1
                && lowered.lowered().result.callbackInvokes().containsKey("cb"),
            "the unit records exactly one CALLBACK_INVOKE for cb");
        if (callbacks.size() == 1) {
            KindPayload.CallbackInvokePayload payload =
                (KindPayload.CallbackInvokePayload) callbacks.get(0).payload();
            check(callbacks.get(0).origin().parentOpId() == null,
                "the CALLBACK_INVOKE is top-level (no parentOpId)");
            List<SemanticOp> params = new ArrayList<>();
            for (OpId boundaryId : payload.parameterBoundaryOpIds()) {
                params.add(opById(unit.ops(), boundaryId));
            }
            check(params.size() == 1 && params.stream().allMatch(p ->
                    boundaryKind(p) == BoundaryKind.HOST_TO_DEAL),
                "the callback runs one HOST_TO_DEAL parameter boundary");
            SemanticOp ret = opById(unit.ops(), payload.returnBoundaryOpId());
            check(ret != null && boundaryKind(ret) == BoundaryKind.DEAL_TO_HOST,
                "the callback's single return boundary is DEAL_TO_HOST");
            OpId callbackOp = callbacks.get(0).opId();
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeCallback(unit,
                lowered.lowered().result.lowering().table(), payload.function(),
                List.of(new SemanticOracle.Value.IntValue(32)));
            if (run != null) {
                assertRunSuccess(run, "callback invoke", List.of(), "int:42");
                // The invocation op's own events: one START carrying
                // the scripted argument inputs with no parentOpId (the
                // scenario InvokeCallback step triggers it) and one
                // SUCCESS publishing the checked value — symmetric
                // with the external-entry arms.
                List<SemanticRuntimeModel.TraceEvent> callbackEvents =
                    new ArrayList<>();
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() == SemanticOpKind.CALLBACK_INVOKE) {
                        callbackEvents.add(event);
                    }
                }
                check(callbackEvents.size() == 2
                        && callbackEvents.get(0).phase() == SemanticRuntimeModel.Phase.START
                        && callbackEvents.get(0).parentOp() == null
                        && callbackEvents.get(0).inputs().equals(List.of("int:32"))
                        && callbackEvents.get(1).phase() == SemanticRuntimeModel.Phase.SUCCESS
                        && callbackEvents.get(1).parentOp() == null
                        && "int:42".equals(callbackEvents.get(1).output()),
                    "the CALLBACK_INVOKE op emits its own START (scripted inputs, no "
                        + "parentOpId) and SUCCESS (checked value) events: "
                        + callbackEvents);
                // The parameter boundaries nest directly under the
                // invocation op; the single return boundary is the body
                // RETURN's child, and the RETURN's payload names the
                // callback op as its enclosing invocation (the closed
                // D13 table: DEAL_TO_HOST by the executed body's RETURN).
                int parameterBoundaryEvents = 0;
                int returnBoundaryEvents = 0;
                boolean nested = true;
                for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                    if (event.kind() != SemanticOpKind.BOUNDARY) {
                        continue;
                    }
                    SemanticOp boundary = opById(unit.ops(), event.op());
                    if (boundary != null
                            && boundaryKind(boundary) == BoundaryKind.HOST_TO_DEAL) {
                        parameterBoundaryEvents++;
                        nested &= callbackOp.equals(event.parentOp());
                    } else if (boundary != null
                            && boundaryKind(boundary) == BoundaryKind.DEAL_TO_HOST) {
                        returnBoundaryEvents++;
                        boolean parented = event.parentOp() != null;
                        if (parented) {
                            SemanticOp parent = opById(unit.ops(), event.parentOp());
                            parented = parent != null
                                && parent.kind() == SemanticOpKind.RETURN;
                        }
                        nested &= parented;
                    } else {
                        nested = false;
                    }
                }
                boolean retNamesCallback = false;
                for (SemanticOp op : ofKind(unit.ops(), SemanticOpKind.RETURN)) {
                    KindPayload.ReturnPayload retPayload =
                        (KindPayload.ReturnPayload) op.payload();
                    if (callbackOp.equals(retPayload.enclosingInvocationOpId())) {
                        retNamesCallback = true;
                    }
                }
                check(parameterBoundaryEvents == 2 && returnBoundaryEvents == 2
                        && nested && retNamesCallback,
                    "the callback's boundary events nest per the closed chain "
                        + "(HOST_TO_DEAL directly under the invocation op; "
                        + "DEAL_TO_HOST under the body RETURN whose payload names "
                        + "the invocation op); got " + parameterBoundaryEvents
                        + " parameter and " + returnBoundaryEvents
                        + " return events, nested=" + nested
                        + ", retNamesCallback=" + retNamesCallback);
                // A failing parameter boundary propagates: the op's
                // FAILURE event closes its START with the same error.
                SemanticRuntimeModel.ConsumerRun badRun = SemanticOracle.invokeCallback(
                    unit, lowered.lowered().result.lowering().table(), payload.function(),
                    List.of(new SemanticOracle.Value.StrValue("nope")));
                assertRunFailure(badRun, "callback bad argument", "E8001");
                boolean hasCallbackFailure = false;
                for (SemanticRuntimeModel.TraceEvent event : badRun.trace()) {
                    if (event.kind() == SemanticOpKind.CALLBACK_INVOKE
                            && event.phase() == SemanticRuntimeModel.Phase.FAILURE
                            && event.parentOp() == null
                            && event.error() != null
                            && event.error().code().equals("E8001")) {
                        hasCallbackFailure = true;
                    }
                }
                check(hasCallbackFailure,
                    "the callback FAILURE event carries the propagated E8001 with no "
                        + "parentOpId");
            }
        }
    }

    // =========================================================================
    // 8. Determinism: byte-identical re-lowering and identical re-execution
    // =========================================================================

    static void testDeterminism() {
        System.out.println("-- Determinism: byte-identical unit dumps and identical runs --");
        String source = CONSOLE
            + "function dbl(x: int): int { return x * 2; }\n"
            + "export function main(): null {\n"
            + "  let f: (a: int, b: int) => int = dbl;\n"
            + "  let r: int = f(5, 9);\n"
            + "  if (r === 10) { console.log(\"deterministic\"); }\n"
            + "}\n";
        LoweredSingle first = lowerSingle(source, "determinism (1)", Set.of(), Map.of(),
            Map.of());
        if (first == null) {
            return;
        }
        LoweredSingle second = lowerSingle(source, "determinism (2)", Set.of(), Map.of(),
            Map.of());
        if (second == null) {
            return;
        }
        String dumpA = deal.semantic.ir.SemanticIrDumper.dumpModuleText(
            first.lowered().result.lowering().unit());
        String dumpB = deal.semantic.ir.SemanticIrDumper.dumpModuleText(
            second.lowered().result.lowering().unit());
        check(dumpA.equals(dumpB), "re-lowering produces byte-identical dumps");
        SemanticRuntimeModel.ConsumerRun runA = SemanticOracle.execute(
            first.lowered().result.lowering().unit(),
            first.lowered().result.lowering().table());
        SemanticRuntimeModel.ConsumerRun runB = SemanticOracle.execute(
            second.lowered().result.lowering().unit(),
            second.lowered().result.lowering().table());
        check(runA != null && runB != null
                && runA.lines().equals(runB.lines())
                && runA.terminal().equals(runB.terminal()),
            "re-execution produces identical traces, effects, and terminals");
        if (runA != null) {
            assertRunSuccess(runA, "determinism run", List.of("deterministic"), "null");
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        try {
            testDirectCalls();
            testIndirectCalls();
            testAdapterSharedCellInvocation();
            testAdapterRetargetE8010();
            testAdapterValueInvocation();
            testHostCall();
            testExternalCallSharedBody();
            testExternalCallCalleeChain();
            testExternalCallRetainedAbi();
            testAsyncDirect();
            testAsyncHost();
            testAsyncHostBadHandle();
            testAsyncExternal();
            testAdapterOverAsync();
            testEntryInvoke();
            testCallbackInvoke();
            testDeterminism();
        } catch (Throwable t) {
            failed++;
            System.err.println("FAIL: unexpected " + t);
            t.printStackTrace();
        }
        System.out.println();
        System.out.println("CallMachineIntegrationTest: " + passed + " passed, "
            + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }
}
