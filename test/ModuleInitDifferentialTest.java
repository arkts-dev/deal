package deal.test;

import deal.checker.CheckResult;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.lexer.Lexer;
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
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.StructuredBodyTable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The module-init differential corpus and the shared-emitter totality gate
 * (sequencing step 9, E1/E3/E8; ISSUE-0590).
 *
 * <p><b>Module-init seeds.</b> The lowerer-produced {@code MODULE_INIT}
 * envelope executes three-way over the production lowering pipeline: a
 * successful init publishes the op's SUCCESS with the walk's events
 * nested under it; a failing init publishes the op's single FAILURE
 * before the run's terminal (no export publication); a hand-built unit
 * without the op (the synthetic-unit surface) keeps the bare init-block
 * behavior.</p>
 *
 * <p><b>Totality gate.</b> Both emitters' {@code emitOp} switches carry
 * exactly one arm per closed {@code SemanticOpKind} value and retain the
 * default throw (the plain-javac toolchain does not compile-check
 * statement-switch exhaustiveness, so the assertion is a real
 * source-level gate over the closed enum), and a lowered feature corpus
 * is emitted and executed on both real targets ({@code luajit};
 * {@code javac --release 25 -proc:none} + {@code java}) with the three
 * consumers compared event-for-event. The lowerer's own decomposition
 * split does not admit one source unit carrying all 55 kinds (the class
 * pipeline rejects exported function declarations while the full-program
 * pipeline carries no class arm), so the behavioral exercise is the
 * corpus below and the per-kind coverage is the arm gate above.</p>
 */
public class ModuleInitDifferentialTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static final ModuleId MODULE = new ModuleId("main");
    private static final String SOURCE_ID = "main.deal";
    private static final String REGISTRY_HASH =
        CapabilityRegistry.releaseRegistry().capabilityRegistryHash();
    private static final Path WORKSPACE = Path.of("build/module-init-diff");

    // =========================================================================
    // The single-module class pipeline (the ISSUE-0516/ISSUE-0586 pattern)
    // =========================================================================

    private record CheckedSlice(deal.ast.ProgramNode program, SymbolTable symbols,
                                CheckResult checks) {
    }

    private static CheckedSlice checkSlice(String source) {
        Lexer lexer = new Lexer(source, SOURCE_ID);
        deal.lexer.LexResult lex = lexer.tokenize();
        Parser parser = new Parser(lex.tokens(), SOURCE_ID, lex.directiveEvents());
        deal.parser.ParseResult parse = parser.parse();
        check(parse.diagnostics().isEmpty(),
            "the slice parses cleanly: " + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver names = new NameResolver(MODULE.path(), new StubModuleResolver());
        SymbolTable symbols = names.resolve(parse.program());
        check(names.diagnostics().isEmpty(),
            "the slice resolves cleanly: " + names.diagnostics());
        if (!names.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult checks = TypeChecker.check(MODULE.path(), symbols, names,
            parse.program());
        check(checks.diagnostics().isEmpty(),
            "the slice checks cleanly: " + checks.diagnostics());
        if (!checks.diagnostics().isEmpty()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symbols, checks);
    }

    /** The exported class types (and @jsonable synthetics) of the slice. */
    private static Map<String, deal.types.Type> sliceExports(CheckedSlice slice,
                                                             SymbolTable symbols) {
        Map<String, deal.types.Type> exports = new LinkedHashMap<>();
        for (deal.ast.StatementNode stmt : slice.program().statements()) {
            if (stmt instanceof deal.ast.ExportDeclaration exp
                    && exp.declaration() instanceof deal.ast.ClassDeclaration cd) {
                deal.checker.Symbol sym = symbols.resolve(cd.name());
                if (sym instanceof deal.checker.Symbol.ClassSymbol cs) {
                    exports.put(cd.name(),
                        deal.types.Types.classType(cs.name(), cs.identity()));
                    if (cd.isJsonable()) {
                        deal.checker.Symbol fromSym = symbols.resolve(cd.name() + "$fromJson");
                        deal.checker.Symbol toSym = symbols.resolve(cd.name() + "$toJson");
                        if (fromSym instanceof deal.checker.Symbol.FunctionSymbol from) {
                            exports.put(cd.name() + "$fromJson", from.funcType());
                        }
                        if (toSym instanceof deal.checker.Symbol.FunctionSymbol to) {
                            exports.put(cd.name() + "$toJson", to.funcType());
                        }
                    }
                }
            }
        }
        return exports;
    }

    private record Lowered(LoweredModuleUnit unit, StructuredBodyTable table) {
    }

    private static Lowered lower(String source, String what) {
        CheckedSlice slice = checkSlice(source);
        if (slice == null) {
            return null;
        }
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of(SOURCE_ID), slice.program(), slice.checks(), List.of(), List.of(),
            CheckedModuleKind.IMPLEMENTATION);
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false, slice.program(),
            sliceExports(slice, slice.symbols()), slice.symbols(), slice.checks(), List.of());
        deal.semantic.CheckedProjectBuildResult built = CheckedProjectBuilder.build(
            invocation, MODULE, List.of(fact));
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            what + ": the checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(invocation,
            built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty()
                && manifests.manifests().size() == 1,
            what + ": the foundation detector produces exactly one manifest: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests().size() != 1) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            new LinkedHashMap<>(manifests.manifests().get(0).constructCoverage());
        coverage.remove(ConstructKind.IMPORT_EXPORT_ENTRY);
        ExternalModuleInterface ownInterface = built.index().modules().get(MODULE);
        SemanticLowerer.ClassDeclarationCoreResult result =
            SemanticLowerer.lowerModuleClassCore(input, SemanticProfile.DEAL_V1_2_INT32,
                coverage, built.index().interfaceIndexDigest(), REGISTRY_HASH,
                ownInterface, Map.of(), SemanticIdAllocator.over(List.of(MODULE)));
        check(result != null && result.lowering() != null && !result.lowering().hasErrors()
                && result.lowering().unit() != null,
            what + " lowers to a validated unit: "
                + (result == null || result.lowering() == null ? "null"
                    : result.lowering().diagnostics()));
        if (result == null || result.lowering() == null || result.lowering().hasErrors()
                || result.lowering().unit() == null) {
            return null;
        }
        return new Lowered(result.lowering().unit(), result.lowering().table());
    }

    private static SemanticDifferentialHarness.Verdict run(Lowered lowered,
            SemanticDifferentialHarness.TerminalExpectation terminal, String what) {
        if (lowered == null) {
            return null;
        }
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            lowered.unit(), lowered.table(),
            new SemanticDifferentialHarness.Expectation(List.of(), terminal, what),
            WORKSPACE);
        check(verdict.pass(), what + ": the three-consumer matrix verdict passes:\n"
            + verdict.report());
        return verdict;
    }

    private static List<SemanticOp> ofKind(LoweredModuleUnit unit, SemanticOpKind kind) {
        List<SemanticOp> matches = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == kind) {
                matches.add(op);
            }
        }
        return matches;
    }

    // =========================================================================
    // 1. The module-init envelope (successful init)
    // =========================================================================

    static void testSuccessfulInit() {
        System.out.println("-- Module init: the MODULE_INIT envelope runs the init "
            + "block and publishes its SUCCESS --");
        Lowered lowered = lower("""
            export class Person {
              name: string = "anon";
              age: int = 0;
            }
            let p: Person = {name: "bob"}
            """, "successful init");
        if (lowered == null) {
            return;
        }
        List<SemanticOp> initOps = ofKind(lowered.unit(), SemanticOpKind.MODULE_INIT);
        check(initOps.size() == 1,
            "the lowerer produces exactly one MODULE_INIT op per unit; got "
                + initOps.size());
        if (initOps.size() == 1) {
            SemanticOp initOp = initOps.get(0);
            check(initOp.payload()
                    instanceof deal.semantic.ir.KindPayload.ModuleInitPayload payload
                    && payload.module().equals(MODULE)
                    && payload.initBlock().equals(lowered.unit().moduleInit().initBlock()),
                "the MODULE_INIT payload names the module and the module-init block "
                    + "(the ModuleInitPlan carrier)");
            check(initOp.origin().parentOpId() == null && initOp.result() == null
                    && initOp.resultType() == null,
                "the MODULE_INIT op is detached, result-less, and parentless");
            check(!lowered.table().opBlocks().containsKey(initOp.opId()),
                "the MODULE_INIT op is a module-level op outside the "
                    + "block-membership table (the C-D1 exemption)");
        }
        SemanticDifferentialHarness.Verdict verdict = run(lowered,
            new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
            "successful init");
        if (verdict == null) {
            return;
        }
        for (SemanticRuntimeModel.ConsumerRun consumerRun : verdict.runs()) {
            boolean sawStart = false;
            boolean sawSuccess = false;
            String output = null;
            int starts = 0;
            for (SemanticRuntimeModel.TraceEvent event : consumerRun.trace()) {
                if (event.kind() != SemanticOpKind.MODULE_INIT) {
                    continue;
                }
                if (event.phase() == SemanticRuntimeModel.Phase.START) {
                    sawStart = true;
                    starts++;
                } else {
                    sawSuccess = event.phase() == SemanticRuntimeModel.Phase.SUCCESS;
                    output = event.output();
                }
            }
            check(sawStart && sawSuccess && starts == 1
                    && "state:INITIALIZED".equals(output),
                consumerRun.consumer() + " publishes exactly one MODULE_INIT "
                    + "START/SUCCESS pair with the INITIALIZED state; got starts="
                    + starts + " success=" + sawSuccess + " output=" + output);
        }
    }

    // =========================================================================
    // 2. A failing init publishes the envelope's FAILURE
    // =========================================================================

    static void testFailingInit() {
        System.out.println("-- Module init: a failing walk publishes the envelope's "
            + "FAILURE and no export publication --");
        Lowered lowered = lower("""
            export class Person {
              name: string = "anon";
            }
            let broken: int = 1 / 0
            let p: Person = {}
            """, "failing init");
        if (lowered == null) {
            return;
        }
        SemanticDifferentialHarness.Verdict verdict = run(lowered,
            new SemanticDifferentialHarness.TerminalExpectation.FailureWith("E8005",
                "main.deal:4:19"),
            "failing init");
        if (verdict == null) {
            return;
        }
        for (SemanticRuntimeModel.ConsumerRun consumerRun : verdict.runs()) {
            int starts = 0;
            int failures = 0;
            int successes = 0;
            for (SemanticRuntimeModel.TraceEvent event : consumerRun.trace()) {
                if (event.kind() != SemanticOpKind.MODULE_INIT) {
                    continue;
                }
                switch (event.phase()) {
                    case START -> starts++;
                    case FAILURE -> failures++;
                    case SUCCESS -> successes++;
                }
            }
            check(starts == 1 && failures == 1 && successes == 0,
                consumerRun.consumer() + " publishes one MODULE_INIT START and exactly "
                    + "one FAILURE (no SUCCESS) for the failing walk; got starts="
                    + starts + " failures=" + failures + " successes=" + successes);
            boolean publishedAfterFailure = false;
            boolean failedWalk = false;
            for (SemanticRuntimeModel.TraceEvent event : consumerRun.trace()) {
                if (event.kind() == SemanticOpKind.MODULE_INIT
                        && event.phase() == SemanticRuntimeModel.Phase.FAILURE) {
                    failedWalk = true;
                }
                if (failedWalk && event.kind() == SemanticOpKind.EXPORT_PUBLISH
                        && event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                    publishedAfterFailure = true;
                }
            }
            check(!publishedAfterFailure,
                consumerRun.consumer() + " publishes no export after the MODULE_INIT "
                    + "FAILURE");
        }
    }

    // =========================================================================
    // 3. A hand-built unit without the op keeps the bare init block
    // =========================================================================

    static void testHandBuiltUnitWithoutOp() {
        System.out.println("-- Module init: a hand-built unit without the op keeps the "
            + "bare init block (the synthetic-unit surface) --");
        Lowered lowered = lower("let x: int = 1", "hand-built baseline");
        if (lowered == null) {
            return;
        }
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() != SemanticOpKind.MODULE_INIT) {
                ops.add(op);
            }
        }
        LoweredModuleUnit stripped = new LoweredModuleUnit(
            lowered.unit().formatVersion(), lowered.unit().semanticProfile(),
            lowered.unit().moduleId(), lowered.unit().interfaceHash(),
            lowered.unit().loweringContextHash(), lowered.unit().requiredCapabilities(),
            lowered.unit().constructCoverage(), lowered.unit().classLayouts(),
            lowered.unit().functions(), lowered.unit().moduleInit(),
            lowered.unit().exportPlan(), lowered.unit().functionBindings(), ops);
        SemanticDifferentialHarness.Verdict verdict = SemanticDifferentialHarness.run(
            stripped, lowered.table(),
            new SemanticDifferentialHarness.Expectation(List.of(),
                new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
                "hand-built baseline"),
            WORKSPACE);
        check(verdict.pass(), "the stripped unit runs three-way without the envelope:\n"
            + verdict.report());
        for (SemanticRuntimeModel.ConsumerRun consumerRun : verdict.runs()) {
            boolean sawInit = false;
            for (SemanticRuntimeModel.TraceEvent event : consumerRun.trace()) {
                if (event.kind() == SemanticOpKind.MODULE_INIT) {
                    sawInit = true;
                }
            }
            check(!sawInit, consumerRun.consumer() + " emits no MODULE_INIT event for "
                + "the stripped unit");
        }
    }

    // =========================================================================
    // 4. The totality gate (one arm per closed kind, default throw retained)
    // =========================================================================

    static void testTotalityGate() {
        System.out.println("-- Totality gate: one emitOp arm per closed kind in both "
            + "emitters; the default throw remains --");
        checkArmCoverage("deal/codegen/lua/LuaSemanticEmitter.java", "shared LuaJIT");
        checkArmCoverage("deal/codegen/jvm/JvmSemanticEmitter.java", "shared JVM");
    }

    private static void checkArmCoverage(String path, String what) {
        String source;
        try {
            source = java.nio.file.Files.readString(Path.of(path));
        } catch (java.io.IOException exception) {
            check(false, what + " emitter source is unreadable: "
                + exception.getMessage());
            return;
        }
        int switchStart = source.indexOf("private void emitOp(SemanticOp op");
        check(switchStart >= 0, what + " emitter carries the emitOp switch");
        if (switchStart < 0) {
            return;
        }
        int defaultAt = source.indexOf("default -> throw new IllegalStateException",
            switchStart);
        check(defaultAt >= 0, what + " emitOp keeps the default throw (the fail-closed "
            + "E6005 backstop)");
        if (defaultAt < 0) {
            return;
        }
        String switchText = source.substring(switchStart, defaultAt);
        for (SemanticOpKind kind : EnumSet.allOf(SemanticOpKind.class)) {
            int count = 0;
            int index = 0;
            while ((index = switchText.indexOf("case " + kind.name() + " ", index)) >= 0) {
                count++;
                index += 1;
            }
            check(count == 1, what + " emitOp realizes " + kind.name()
                + " with exactly one arm; got " + count);
        }
    }

    // =========================================================================
    // 5. The behavioral feature corpus (real toolchains)
    // =========================================================================

    static void testFeatureCorpus() {
        System.out.println("-- Totality gate: the feature corpus emits and runs on "
            + "both real targets three-way --");
        Map<String, String> corpus = new LinkedHashMap<>();
        corpus.put("containers", """
            let xs: int[] = [1, 2, 3]
            let t: table = {a: 1}
            let n: int = xs.length
            let first: int | null = xs[0]
            xs[1] = 7
            delete t.a
            """);
        corpus.put("control", """
            let total: int = 0
            let other: int = total - 1
            let text: string = "a" + "b"
            """);
        corpus.put("classes", """
            export class Person {
              name: string = "anon";
              age: int = 0;
              note?: string | null;
            }
            let p: Person = {name: "bob", age: 3}
            p.age = 4
            let present: boolean = has(p.note)
            delete p.note
            """);
        corpus.put("json", """
            // @jsonable
            export class Card {
              rank: int = 0;
              label: string = "x";
            }
            let c: Card = {rank: 1}
            """);
        int units = 0;
        int executed = 0;
        Set<SemanticOpKind> union = EnumSet.noneOf(SemanticOpKind.class);
        for (Map.Entry<String, String> entry : corpus.entrySet()) {
            Lowered lowered = lower(entry.getValue(), entry.getKey());
            if (lowered == null) {
                continue;
            }
            units++;
            for (SemanticOp op : lowered.unit().ops()) {
                union.add(op.kind());
            }
            SemanticDifferentialHarness.Verdict verdict = run(lowered,
                new SemanticDifferentialHarness.TerminalExpectation.SuccessWith("null"),
                entry.getKey() + " feature corpus");
            if (verdict != null && verdict.pass()) {
                executed++;
            }
        }
        check(units == corpus.size(), "the feature corpus lowers every unit; got "
            + units + " of " + corpus.size());
        check(executed == units, "every corpus unit runs green three-way; got "
            + executed + " of " + units);
        check(union.contains(SemanticOpKind.MODULE_INIT)
                && union.contains(SemanticOpKind.CLASS_NEW)
                && union.contains(SemanticOpKind.CLASS_DEFAULT)
                && union.contains(SemanticOpKind.CLASS_FACTORY)
                && union.contains(SemanticOpKind.JSON_FROM_CLASS)
                && union.contains(SemanticOpKind.JSON_TO_CLASS)
                && union.contains(SemanticOpKind.FIELD_WRITE)
                && union.contains(SemanticOpKind.HAS_FIELD)
                && union.contains(SemanticOpKind.INDEX_READ)
                && union.contains(SemanticOpKind.DELETE),
            "the corpus exercises the module/class/JSON/container/control families; got "
                + union);
    }

    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Module Init Differential Test + Shared-Emitter Totality "
            + "Gate (ISSUE-0590) ===");
        System.out.println();
        testSuccessfulInit();
        testFailingInit();
        testHandBuiltUnitWithoutOp();
        testTotalityGate();
        testFeatureCorpus();
        System.out.println();
        System.out.println("ModuleInitDifferentialTest passed=" + passed + " failed="
            + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
