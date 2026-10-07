package deal.test;

import deal.ast.ProgramNode;
import deal.checker.BuiltinErrorDeclaration;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedModuleKind;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ControlFlowValidator;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticOracle;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ConstructKind;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalModuleInterface;
import deal.semantic.ir.ExternalModuleKind;
import deal.semantic.ir.InitializationMode;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ISSUE-0713: the represented unreachable tail and its per-target emission
 * ({@code residual-carrier-shapes-production-realization} D2 and the
 * terminator-and-unreachable-tail contract; {@code
 * dispatched-corpus-production-realization} R4 item 3 and R8;
 * {@code luajit-jvm-single-lowering-production-cutover} C1/C2).
 *
 * <p>Three measured surfaces:</p>
 *
 * <ol>
 *   <li><b>The lowering unit battery.</b> A lowered function carrying a
 *       tail after each of {@code return}/{@code throw}/{@code break}/
 *       {@code continue} validates, and every tail op is a member of the
 *       same block behind its terminator, in source order. The tail does
 *       not clear the block's termination state: a terminated null body
 *       whose {@code return} is followed by a tail carries no synthetic
 *       implicit return (a cleared state would append the pinned implicit
 *       null return).</li>
 *   <li><b>The artifact rule.</b> The Lua production chunk emits each
 *       tail op behind its terminator; the JVM production artifact keeps
 *       the JLS &sect;14.21 reachability skip — the
 *       {@code // unreachable:} marker directly follows the terminator's
 *       statement with no trailing statement emitted — and compiles under
 *       {@code javac --release 25 -proc:none}. The artifact text is tied to
 *       the lowered unit's op ids and origins, not to a copy. The same
 *       rule holds when the terminator is a composite whose every path
 *       transfers and whose sub-blocks carry the tail (an {@code if}/{@code
 *       else} with both branches returning, a {@code try}/{@code catch}
 *       whose protected and catch blocks both return): the JVM
 *       block-completion query walks the same reachable prefix the
 *       emission walks, so the composite is not misreported as completing
 *       and no unreachable trailing statement is emitted.</li>
 *   <li><b>The fixture drive.</b> The two tail fixtures
 *       ({@code async-await/async-error-propagation} and
 *       {@code async-await/async-throw-catch}) compile through the
 *       release-owned production invocation on LuaJIT and JVM with zero
 *       E6005 {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}, emit exactly one project artifact
 *       and no retained emission, and execute on the real toolchains with
 *       the pinned {@code runtime-ok} outcome (exit 0, empty
 *       stdout/stderr). The oracle completes the same async export and
 *       emits no event for any tail op; the fixtures' own {@code normalRan}
 *       guards therefore stay false. The registered three-consumer trace
 *       comparison then requires the differential verdict itself to pass:
 *       the oracle, the shared LuaJIT artifact, and the shared JVM artifact
 *       agree event-for-event with the fixture's pinned result atom and no
 *       consumer emits an event for a represented tail op.</li>
 *   <li><b>The in-scope loop transfer.</b> A loop declared inside a
 *       protected region keeps its label at its own level: a
 *       {@code break}/{@code continue} whose target loop is inside the try
 *       block takes the emitted jump there, and the try's transfer dispatch
 *       carries no arm that would jump to the out-of-scope label — the
 *       review's real-javac regression, with a represented tail behind the
 *       transfer, a passing three-consumer trace comparison, and both
 *       production artifacts executing with exit 0. The same rule names
 *       the target loop's own label: a for-of loop owns {@code FE<id>}, so
 *       a transfer out of a try inside its body jumps to {@code FE<id>}
 *       at the try's dispatch (never to the {@code LOOP<id>} label the
 *       other loop forms define).</li>
 * </ol>
 *
 * <p>Read-only over the repository: every temp project is removed on every
 * path, nothing is staged in the checkout, and no production file, sidecar,
 * schema, JS file, lane mechanism, or corpus member is modified.</p>
 */
public class UnreachableTailProductionTest {

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
    private static final String INTERFACE_HASH = new ProjectInterfaceIndex(
        ProjectInterfaceIndex.FORMAT_VERSION, Map.of(MODULE,
            new ExternalModuleInterface(MODULE,
                ExternalModuleKind.IMPLEMENTATION, List.of(), List.of(),
                List.of(), InitializationMode.ONCE_AFTER_DEPENDENCIES)))
        .interfaceIndexDigest();

    private static final Path CONFORMANCE = Path.of("test", "conformance");
    private static final String ASYNC_DIR = "backend-runtime/async-await";

    /** The two represented-tail fixtures (the slice's flips). */
    private static final List<String> TAIL_FIXTURES = List.of(
        "async-error-propagation", "async-throw-catch");

    private static String asyncExportOf(String stem) {
        return stem.equals("async-error-propagation") ? "outer" : "f";
    }

    /** The pinned async-entry result atom of one tail fixture. */
    private static String asyncResultAtomOf(String stem) {
        return stem.equals("async-error-propagation")
            ? "str:E_INNER" : "str:E_TEST";
    }

    private static final String DEAL_JSON =
        "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n";

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    // =========================================================================
    // 1. The lowering unit battery
    // =========================================================================

    /**
     * A tail statement after each of {@code return}/{@code throw}/
     * {@code break}/{@code continue}, at the pinned source lines the
     * assertions read back from the ops' origins.
     */
    private static final String TAIL_BATTERY_SOURCE = """
        function returnsTail(): null {
          return null;
          let returnTail: int = 2;
          returnTail = 3;
        }
        function throwsTail(): null {
          try {
            "boom";
          } catch (err) {
            throw err;
            let throwTail: int = 4;
          }
          return null;
        }
        function breaksTail(): null {
          for (;;) {
            break;
            let breakTail: int = 5;
          }
          return null;
        }
        function continuesTail(): null {
          for (;;) {
            continue;
            let continueTail: int = 6;
          }
          return null;
        }
        """;

    private record CheckedSlice(ProgramNode program, SymbolTable symbols,
                                CheckResult checks) {
    }

    private static CheckedSlice checkBattery() {
        LexResult lex = new Lexer(TAIL_BATTERY_SOURCE, SOURCE_ID).tokenize();
        check(lex.diagnostics().isEmpty(), "the battery lexes cleanly: "
            + lex.diagnostics());
        if (!lex.diagnostics().isEmpty()) {
            return null;
        }
        ParseResult parse = new Parser(lex.tokens(), SOURCE_ID).parse();
        check(parse.diagnostics().isEmpty(), "the battery parses cleanly: "
            + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver nr = new NameResolver(SOURCE_ID, (ModuleResolver) null);
        SymbolTable symbols = nr.resolve(parse.program());
        check(nr.diagnostics().isEmpty(), "the battery resolves cleanly: "
            + nr.diagnostics());
        if (!nr.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult checks = TypeChecker.check(SOURCE_ID, symbols, nr, parse.program());
        check(checks.diagnostics().isEmpty(), "the battery checks cleanly: "
            + checks.diagnostics());
        if (!checks.diagnostics().isEmpty()) {
            return null;
        }
        return new CheckedSlice(parse.program(), symbols, checks);
    }

    private static SemanticLowerer.LoweringResult lowerBattery(CheckedSlice slice) {
        CompilerInvocation invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseConfiguration.CURRENT_RELEASE_STATE,
            CapabilityRegistry.releaseRegistry());
        CheckedModuleInput input = new CheckedModuleInput(MODULE, SOURCE_ID,
            Path.of(SOURCE_ID), slice.program(), slice.checks(), List.of(), List.of(),
            CheckedModuleKind.IMPLEMENTATION);
        ModuleFact fact = new ModuleFact(SOURCE_ID, MODULE, false, false,
            slice.program(), Map.of(), slice.symbols(), slice.checks(), List.of());
        CheckedProjectBuildResult built = CheckedProjectBuilder.build(invocation,
            MODULE, List.of(fact));
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            "the battery checked project builds cleanly: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(
            invocation, built.input(), built.index());
        check(manifests != null && manifests.diagnostics().isEmpty()
                && manifests.manifests() != null && manifests.manifests().size() == 1,
            "the battery manifest computation is clean: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || !manifests.diagnostics().isEmpty()
                || manifests.manifests() == null || manifests.manifests().size() != 1) {
            return null;
        }
        Map<ConstructKind, List<SemanticOpKind>> coverage =
            manifests.manifests().get(0).constructCoverage();
        SemanticLowerer.LoweringResult lowering =
            SemanticLowerer.lowerModuleFullProgram(input, SemanticProfile.DEAL_V1_2_INT32,
                coverage, INTERFACE_HASH, REGISTRY_HASH,
                SemanticIdAllocator.over(List.of(MODULE)));
        // A clean result is the full lowering stack's acceptance: the
        // closed schema validator, the address-chain protocol, and
        // ControlFlowValidator (the dominance clause is removed, so the
        // tail is admitted).
        check(lowering != null && !lowering.hasErrors() && lowering.unit() != null
                && lowering.table() != null,
            "the battery lowers through the validator stack: "
                + (lowering == null ? "null" : lowering.diagnostics()));
        return lowering;
    }

    private static void testTailBattery() {
        System.out.println("-- The represented unreachable tail: unit battery --");

        CheckedSlice slice = checkBattery();
        if (slice == null) {
            return;
        }
        SemanticLowerer.LoweringResult lowering = lowerBattery(slice);
        if (lowering == null || lowering.hasErrors() || lowering.unit() == null) {
            return;
        }
        LoweredModuleUnit unit = lowering.unit();
        StructuredBodyTable table = lowering.table();
        check(ControlFlowValidator.validate(unit, table).isEmpty(),
            "the battery unit passes ControlFlowValidator with the tails present");

        // The tail after each terminator: the tail op is a member of the
        // terminator's own block, in source order behind it.
        record Terminator(SemanticOpKind kind, int line, int tailLine) {
        }
        List<Terminator> terminators = List.of(
            new Terminator(SemanticOpKind.RETURN, 2, 3),
            new Terminator(SemanticOpKind.THROW, 10, 11),
            new Terminator(SemanticOpKind.BREAK, 17, 18),
            new Terminator(SemanticOpKind.CONTINUE, 24, 25));
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            byId.put(op.opId(), op);
        }
        for (Terminator terminator : terminators) {
            List<SemanticOp> terminatorOps = unit.ops().stream()
                .filter(op -> op.kind() == terminator.kind()
                    && op.origin().span().startLine() == terminator.line())
                .toList();
            check(terminatorOps.size() == 1, "exactly one " + terminator.kind()
                + " op at line " + terminator.line() + "; got " + terminatorOps.size());
            if (terminatorOps.size() != 1) {
                continue;
            }
            SemanticOp op = terminatorOps.get(0);
            List<SemanticOp> tailOps = unit.ops().stream()
                .filter(candidate -> candidate.origin().span().startLine()
                    == terminator.tailLine())
                .toList();
            check(!tailOps.isEmpty(), "the tail at line " + terminator.tailLine()
                + " is represented in the lowered unit");
            for (SemanticOp tailOp : tailOps) {
                if (table.opBlocks().get(tailOp.opId()) == null) {
                    fail("the tail op " + tailOp.opId() + " is a member of no block");
                    continue;
                }
                if (!table.opBlocks().get(tailOp.opId())
                        .equals(table.opBlocks().get(op.opId()))) {
                    fail("the tail op " + tailOp.opId()
                        + " is not a member of its terminator's block");
                    continue;
                }
                List<OpId> members = table.blockOps().get(table.opBlocks().get(op.opId()));
                check(members.indexOf(op.opId()) < members.indexOf(tailOp.opId()),
                    "the tail op " + tailOp.opId() + " follows " + terminator.kind()
                        + " " + op.opId() + " in block order");
            }
        }

        // The tail does not clear the termination state: the terminated
        // returnsTail body carries no synthetic implicit return (a cleared
        // state would append the pinned implicit null return).
        List<SemanticOp> syntheticReturns = unit.ops().stream()
            .filter(op -> op.kind() == SemanticOpKind.RETURN
                && op.origin().kind() == SourceOriginKind.SYNTHETIC)
            .toList();
        check(syntheticReturns.isEmpty(), "no synthetic implicit return was appended "
            + "to a terminated body; got " + syntheticReturns.size());

        // Running a tail after a terminator registers exactly the tail
        // statements' own ops (the ASSIGN/BINDING_STORE chain), never a
        // dropped statement.
        SemanticOp declareTail = unit.ops().stream()
            .filter(op -> op.origin().span().startLine() == 3)
            .findFirst().orElse(null);
        check(declareTail != null, "the return tail's declaration op exists "
            + "(the statement is not dropped)");
    }

    // =========================================================================
    // 2. The two fixtures: production compile, artifacts, oracle, drive
    // =========================================================================

    /** One terminator and the first non-owned op behind it in its block. */
    private record TailPair(OpId terminator, OpId tail, String terminatorOrigin,
                            List<OpId> tailOps, List<String> tailOrigins) {
    }

    private static boolean isTerminator(SemanticOpKind kind) {
        return kind == SemanticOpKind.RETURN || kind == SemanticOpKind.THROW
            || kind == SemanticOpKind.BREAK || kind == SemanticOpKind.CONTINUE;
    }

    private static List<TailPair> tailPairs(LoweredModuleUnit unit,
                                            StructuredBodyTable table) {
        Set<OpId> owned = ChainOperandCompletion.structuralOwners(unit);
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            byId.put(op.opId(), op);
        }
        List<TailPair> pairs = new ArrayList<>();
        for (List<OpId> members : table.blockOps().values()) {
            for (int i = 0; i < members.size(); i++) {
                if (owned.contains(members.get(i))) {
                    continue;
                }
                SemanticOp op = byId.get(members.get(i));
                if (op == null || !isTerminator(op.kind())) {
                    continue;
                }
                for (int j = i + 1; j < members.size(); j++) {
                    if (owned.contains(members.get(j))) {
                        continue;
                    }
                    List<OpId> tailOps = new ArrayList<>();
                    List<String> tailOrigins = new ArrayList<>();
                    for (int k = j; k < members.size(); k++) {
                        SemanticOp tailOp = byId.get(members.get(k));
                        if (tailOp == null) {
                            continue;
                        }
                        tailOps.add(tailOp.opId());
                        tailOrigins.add(originOf(tailOp));
                    }
                    pairs.add(new TailPair(op.opId(), members.get(j), originOf(op),
                        List.copyOf(tailOps), List.copyOf(tailOrigins)));
                    break;
                }
            }
        }
        return pairs;
    }

    private static String originOf(SemanticOp op) {
        SourceSpan span = op.origin().span();
        return op.origin().sourceId() + ":" + span.startLine() + ":"
            + span.startColumn();
    }

    private static String quotedOpId(OpId opId) {
        return "\"" + opId.module().path() + "#" + opId.id() + "\"";
    }

    private record LoweredProject(ExecutableLoweredProject project,
                                  Map<ModuleId, StructuredBodyTable> tables,
                                  Map<ModuleId, ClassFactoryRegistry> registries,
                                  ModuleId entry, StructuredBodyTable entryTable,
                                  Long asyncEntryId) {
    }

    private static LoweredProject lowerFixture(String stem, Path entry,
            CliOverrides overrides) throws Exception {
        LoweredProject lowered = lowerProgram(stem, entry, overrides);
        if (lowered == null) {
            return null;
        }
        Long asyncEntryId = asyncEntryIdOf(
            lowered.project().modules().get(lowered.entry()), asyncExportOf(stem));
        check(asyncEntryId != null, stem + ": the unit records the async export's "
            + "EXTERNAL_ENTRY");
        return new LoweredProject(lowered.project(), lowered.tables(),
            lowered.registries(), lowered.entry(), lowered.entryTable(),
            asyncEntryId);
    }

    /**
     * Lowers one generated temp project through the project entry with the
     * same release-owned inputs {@link #lowerFixture} uses; a program
     * without an async export carries no async entry id.
     */
    private static LoweredProject lowerProgram(String name, Path entry,
            CliOverrides overrides) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toString(), overrides);
        check(located.context() != null, name + ": the generated deal.json "
            + "locates strictly");
        if (located.context() == null) {
            return null;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        check(built != null && built.input() != null && built.index() != null
                && !built.hasErrors() && manifests != null
                && manifests.manifests() != null
                && orchestrator.hostDeclarationSurface() != null,
            name + ": the oracle closure compiles: "
                + (built == null ? "no checked project" : built.diagnostics()));
        if (built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT),
            Set.of());
        check(result.project() != null, name + ": the oracle closure lowers with "
            + "zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId entryModule = result.project().entryModule();
        return new LoweredProject(result.project(), result.tables(),
            result.registries(), entryModule, result.tables().get(entryModule),
            null);
    }

    private static Long asyncEntryIdOf(LoweredModuleUnit unit, String export) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                    && payload.async() && export.equals(payload.exportName())) {
                return op.opId().id();
            }
        }
        return null;
    }

    private static void testFixture(String stem) throws Exception {
        Path project = Files.createTempDirectory("deal-unreachable-tail-" + stem + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"),
                ConformanceHarnessMetadata.stripClassificationHeaders(
                    Files.readString(CONFORMANCE.resolve(ASYNC_DIR)
                        .resolve(stem + ".deal"), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");
            String export = asyncExportOf(stem);

            LoweredProject lowered = lowerFixture(stem, entry,
                new CliOverrides("luajit",
                    project.resolve("out-oracle").toString()));
            if (lowered == null) {
                return;
            }
            List<TailPair> pairs = tailPairs(
                lowered.project().modules().get(lowered.entry()), lowered.entryTable());
            check(!pairs.isEmpty(), stem + ": the lowered unit represents a tail "
                + "behind a terminator");
            for (TailPair pair : pairs) {
                System.out.println("   " + stem + ": tail " + pair.tail()
                    + " behind " + pair.terminator() + " at "
                    + pair.terminatorOrigin());
            }

            // The oracle never executes the tail: the async export completes
            // with the pinned outcome and the trace carries no tail event.
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.invokeAsyncEntry(
                lowered.project(), lowered.tables(),
                new SemanticOracle.HostResponder() {
                }, lowered.entry(), export, List.of());
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                stem + ": the oracle completes the async export (the fixtures' "
                    + "guards stay false): " + run.terminal());
            for (TailPair pair : pairs) {
                check(run.trace().stream()
                        .noneMatch(event -> event.op().equals(pair.tail())),
                    stem + ": the oracle emits no event for the tail op "
                        + pair.tail());
            }

            // The required three-consumer trace comparison: the oracle and
            // both shared artifacts agree event-for-event with the pinned
            // terminal of the fixture and emit no event for any represented
            // tail op. Process survival is not a substitute for oracle
            // agreement, so the differential verdict itself must pass.
            Path matrixWorkspace = Files.createTempDirectory(
                "deal-unreachable-tail-matrix-" + stem + "-");
            try {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.runAsyncEntry(lowered.project(),
                        lowered.tables(), export, List.of(),
                        SemanticDifferentialHarness.Expectation.success(stem,
                            List.of(), asyncResultAtomOf(stem)),
                        matrixWorkspace, null);
                check(verdict.runs().size() == 3, stem + ": the differential "
                    + "matrix produced the three consumers: " + verdict.failures());
                check(verdict.pass(), stem + ": the oracle and both shared "
                    + "artifacts agree event-for-event with the pinned terminal "
                    + "and emit no tail event: " + verdict.failures());
                for (SemanticRuntimeModel.ConsumerRun consumer : verdict.runs()) {
                    for (TailPair pair : pairs) {
                        for (OpId tailOp : pair.tailOps()) {
                            check(consumer.trace().stream().noneMatch(
                                    event -> event.op().equals(tailOp)), stem + ": "
                                + consumer.consumer() + " emits no event for the "
                                + "represented tail op " + tailOp);
                        }
                    }
                }
                check(verdict.runs().stream().allMatch(consumer ->
                        consumer.terminal() instanceof SemanticRuntimeModel.Terminal.Success),
                    stem + ": every consumer of the matrix completes with the "
                        + "pinned runtime-ok outcome");
            } finally {
                deleteRecursively(matrixWorkspace);
            }

            for (String lane : List.of("luajit", "jvm")) {
                String outName = "out-" + lane;
                ProjectLocator.LocateResult located = ProjectLocator.locate(
                    entry.toString(),
                    new CliOverrides(lane, project.resolve(outName).toString()));
                check(located.context() != null, stem + " [" + lane
                    + "]: the generated deal.json locates strictly");
                if (located.context() == null) {
                    continue;
                }
                CompilationOrchestrator orchestrator = new CompilationOrchestrator(
                    located.context(), entry, false, false, false, false, null,
                    productionInvocation());
                boolean compiled = orchestrator.compile();
                List<String> errors = orchestrator.diagnostics().stream()
                    .filter(diagnostic -> "error".equals(diagnostic.severity()))
                    .map(CompilerDiagnostic::message).toList();
                check(compiled && errors.isEmpty(), stem + " [" + lane
                    + "]: zero E6005 over the release-owned production invocation: "
                    + errors);
                check(orchestrator.semanticEmissionCount() == 1
                        && orchestrator.retainedEmissionCount() == 0,
                    stem + " [" + lane + "]: exactly one project artifact and no "
                        + "retained emission: semantic="
                        + orchestrator.semanticEmissionCount() + " retained="
                        + orchestrator.retainedEmissionCount());
                if (!compiled) {
                    continue;
                }
                SidecarExpectations.RuntimeExpectation.Executed pinned =
                    pinnedOutcome(stem, lane);
                Path out = project.resolve(outName);
                if (lane.equals("luajit")) {
                    Path chunk = out.resolve("main.lua");
                    check(Files.isRegularFile(chunk), stem + " [luajit]: the project "
                        + "artifact is staged: " + chunk);
                    if (!Files.isRegularFile(chunk)) {
                        continue;
                    }
                    String text = Files.readString(chunk, StandardCharsets.UTF_8);
                    for (TailPair pair : pairs) {
                        int terminatorAt = text.lastIndexOf(
                            quotedOpId(pair.terminator()));
                        int tailAt = text.indexOf(quotedOpId(pair.tail()));
                        check(terminatorAt >= 0 && tailAt > terminatorAt, stem
                            + " [luajit]: the emitted chunk carries the tail op "
                            + pair.tail() + " behind " + pair.terminator());
                    }
                    driveLua(stem, out, chunk, export, pinned);
                } else {
                    Path artifact = out.resolve(
                        JvmBackend.classNameFor("main") + ".java");
                    check(Files.isRegularFile(artifact), stem + " [jvm]: the project "
                        + "artifact is staged: " + artifact);
                    if (!Files.isRegularFile(artifact)) {
                        continue;
                    }
                    String text = Files.readString(artifact, StandardCharsets.UTF_8);
                    List<String> lines = text.lines().toList();
                    for (TailPair pair : pairs) {
                        int originLine = -1;
                        int occurrences = 0;
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(pair.terminatorOrigin())) {
                                originLine = i;
                                occurrences++;
                            }
                        }
                        check(occurrences == 1, stem + " [jvm]: the terminator "
                            + pair.terminator() + " origin appears exactly once in "
                            + "the emitted Java; got " + occurrences);
                        if (occurrences != 1) {
                            continue;
                        }
                        // The terminator's own emission is the THROW arm's
                        // DealError construction carrying the origin and its
                        // `throw __thrown;` statement; the JLS \u00a714.21 skip
                        // marker is the next non-blank line, so no tail
                        // statement is emitted between them.
                        check(lines.get(originLine).contains(
                                "JvmRuntime.DealError __thrown = new JvmRuntime"
                                    + ".DealError("),
                            stem + " [jvm]: the terminator origin line is the THROW "
                                + "arm's DealError construction");
                        int after = originLine + 1;
                        while (after < lines.size() && lines.get(after).isBlank()) {
                            after++;
                        }
                        check(after < lines.size() && lines.get(after).trim()
                                .equals("throw __thrown;"),
                            stem + " [jvm]: the terminator emits its transfer "
                                + "statement: "
                                + (after < lines.size() ? lines.get(after).trim() : "<eof>"));
                        int marker = after + 1;
                        while (marker < lines.size() && lines.get(marker).isBlank()) {
                            marker++;
                        }
                        check(marker < lines.size() && lines.get(marker)
                                .contains("// unreachable: the preceding statement"
                                    + " cannot complete normally"),
                            stem + " [jvm]: the JLS \u00a714.21 skip marker directly "
                                + "follows the terminator " + pair.terminator()
                                + " with no tail statement emitted");
                        // No tail op contributes an emitted origin to the
                        // artifact: the whole tail statement is skipped.
                        for (String tailOrigin : pair.tailOrigins()) {
                            check(!text.contains(tailOrigin), stem + " [jvm]: the "
                                + "tail op origin " + tailOrigin + " is absent from "
                                + "the emitted Java (the tail statement is skipped)");
                        }
                    }
                    driveJvm(stem, out, artifact, lowered.asyncEntryId(), pinned);
                }
            }
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // 3. The real-toolchain drives
    // =========================================================================

    /** The sidecar-pinned executed outcome of one fixture and lane. */
    private static SidecarExpectations.RuntimeExpectation.Executed pinnedOutcome(
            String stem, String lane) throws Exception {
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(CONFORMANCE.resolve(ASYNC_DIR)
                    .resolve(stem + ".expect.json"), StandardCharsets.UTF_8));
        SidecarExpectations.RuntimeExpectation expectation =
            sidecar.expectationFor(lane);
        check(expectation instanceof SidecarExpectations.RuntimeExpectation.Executed,
            stem + " [" + lane + "]: the sidecar pins an executed outcome");
        if (!(expectation
                instanceof SidecarExpectations.RuntimeExpectation.Executed executed)) {
            throw new IllegalStateException(stem + " [" + lane
                + "]: the sidecar pins no executed outcome");
        }
        check("runtime-ok".equals(executed.mode()) && executed.exitCode() == 0
                && executed.stdout().length == 0 && executed.stderr().length == 0,
            stem + " [" + lane + "]: the sidecar pins the runtime-ok outcome "
                + "(exit 0, empty transcript): mode=" + executed.mode()
                + " exit=" + executed.exitCode());
        return executed;
    }

    private static void driveLua(String stem, Path out, Path chunk, String export,
                                 SidecarExpectations.RuntimeExpectation.Executed pinned)
            throws Exception {
        Path probe = out.resolve("__probe.lua");
        String source = "dofile(\"" + chunk.toAbsolutePath().normalize() + "\")\n"
            + "local __ok, __err = __dealMain()\n"
            + "if not __ok then os.exit(1) end\n"
            + "local __okA, __errA = pcall(__asyncEntries[\"main#" + export
            + "\"], \"-\", true)\n"
            + "if not __okA then os.exit(1) end\n"
            + "os.exit(0)\n";
        Files.writeString(probe, source, StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == pinned.exitCode()
                && stdout.equals(new String(pinned.stdout(), StandardCharsets.UTF_8))
                && stderr.equals(new String(pinned.stderr(), StandardCharsets.UTF_8)),
            stem + " [luajit]: the artifact executes with the pinned runtime-ok "
            + "outcome (exit 0, empty stdout/stderr): exit=" + exit
            + " stdout=\"" + stdout + "\" stderr=\"" + stderr + "\"");
    }

    private static void driveJvm(String stem, Path out, Path artifact,
                                 Long asyncEntryId,
                                 SidecarExpectations.RuntimeExpectation.Executed pinned)
            throws Exception {
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        Files.writeString(out.resolve("Probe.java"),
            "public final class Probe {\n"
                + "  public static void main(String[] args) {\n"
                + "    Main.dealMain();\n"
                + "    Main.ae" + (asyncEntryId == null ? "MISSING" : asyncEntryId)
                + "(\"-\", true, new Object[]{});\n"
                + "    System.exit(0);\n"
                + "  }\n"
                + "}\n",
            StandardCharsets.UTF_8);
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toString(), out.resolve("Probe.java").toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        check(compileExit == 0, stem + " [jvm]: the artifact compiles under "
            + "javac --release 25 -proc:none: " + compileOut);
        if (compileExit != 0) {
            return;
        }
        ProcessBuilder run = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Probe");
        run.directory(out.toFile());
        Process process = run.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == pinned.exitCode()
                && stdout.equals(new String(pinned.stdout(), StandardCharsets.UTF_8))
                && stderr.equals(new String(pinned.stderr(), StandardCharsets.UTF_8)),
            stem + " [jvm]: the artifact executes with the pinned runtime-ok outcome "
            + "(exit 0, empty stdout/stderr): exit=" + exit
            + " stdout=\"" + stdout + "\" stderr=\"" + stderr + "\"");
    }

    // =========================================================================
    // 4. The composite-block regression: a composite whose every path
    //    transfers and whose sub-blocks carry a represented tail
    // =========================================================================

    /**
     * One composite shape carrying a represented tail in each of its
     * sub-blocks. The marker expression statements make the tail's
     * emission observable in the emitted Lua text and its absence
     * observable in the emitted Java (the JLS &sect;14.21 skip); the
     * trailing {@code return} behind the composite is the statement a raw
     * block-tail completion read would wrongly emit as unreachable Java.
     */
    private record CompositeCase(String name, String source,
                                 List<String> tailMarkers) {
    }

    /** One represented tail statement: its marker op, its text, and the
     *  terminator op it sits behind in its block. */
    private record TailMarker(OpId op, String text, OpId terminator) {
    }

    private static final String IF_ELSE_TAIL_SOURCE = """
        export function f(): int {
          if (true) {
            return 1;
            "ifElseTailMarker";
            let ifTail: int = 2;
          } else {
            return 3;
            "elseTailMarker";
            let elseTail: int = 4;
          }
          return 5;
        }

        export function main(): null {
          let value: int = f();
          if (value !== 1) {
            throw { code: "TEST_FAIL", message: "f returned the wrong value" };
          }
          return null;
        }
        """;

    private static final String TRY_CATCH_TAIL_SOURCE = """
        export function g(): int {
          try {
            return 1;
            "tryTailMarker";
            let tryTail: int = 2;
          } catch (err) {
            return 3;
            "catchTailMarker";
            let catchTail: int = 4;
          }
          return 5;
        }

        export function main(): null {
          let value: int = g();
          if (value !== 1) {
            throw { code: "TEST_FAIL", message: "g returned the wrong value" };
          }
          return null;
        }
        """;

    private static final List<CompositeCase> COMPOSITE_CASES = List.of(
        new CompositeCase("if-else", IF_ELSE_TAIL_SOURCE,
            List.of("ifElseTailMarker", "elseTailMarker")),
        new CompositeCase("try-catch", TRY_CATCH_TAIL_SOURCE,
            List.of("tryTailMarker", "catchTailMarker")),
        new CompositeCase("null-both-return",
            CompositeTerminatorAnalysisTest.NULL_BOTH_RETURN_TAIL_SOURCE,
            List.of("nullTrueTailMarker", "nullFalseTailMarker", "nullBodyTailMarker")));

    private static void testCompositeBlockTails() throws Exception {
        System.out.println("-- The composite-block tail: real-javac regression --");
        for (CompositeCase composite : COMPOSITE_CASES) {
            testCompositeCase(composite);
        }
    }

    private static void testCompositeCase(CompositeCase composite) throws Exception {
        Path project = Files.createTempDirectory(
            "deal-unreachable-tail-" + composite.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), composite.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            LoweredProject lowered = lowerProgram(composite.name(), entry,
                new CliOverrides("jvm",
                    project.resolve("out-oracle").toString()));
            if (lowered == null) {
                return;
            }
            LoweredModuleUnit unit = lowered.project().modules().get(lowered.entry());
            Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                byId.put(op.opId(), op);
            }
            List<TailMarker> markers = new ArrayList<>();
            for (String marker : composite.tailMarkers()) {
                SemanticOp markerOp = opWithString(unit.ops(), marker);
                check(markerOp != null, composite.name() + ": the tail statement '"
                    + marker + "' is represented in the lowered unit");
                if (markerOp == null) {
                    continue;
                }
                BlockId owner = lowered.entryTable().opBlocks().get(markerOp.opId());
                check(owner != null, composite.name() + ": the tail op of '" + marker
                    + "' is a member of its block");
                if (owner == null) {
                    continue;
                }
                List<OpId> members = lowered.entryTable().blockOps().get(owner);
                int index = members.indexOf(markerOp.opId());
                OpId terminator = null;
                for (int i = 0; i < index; i++) {
                    SemanticOp candidate = byId.get(members.get(i));
                    if (candidate != null && (isTerminator(candidate.kind())
                            || (marker.equals("nullBodyTailMarker")
                                && candidate.kind() == SemanticOpKind.BRANCH))) {
                        terminator = candidate.opId();
                    }
                }
                check(terminator != null, composite.name() + ": the tail statement '"
                    + marker + "' follows a terminator in its block");
                markers.add(new TailMarker(markerOp.opId(), marker, terminator));
            }

            // The oracle runs main and never executes a tail statement.
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(
                lowered.project(), lowered.tables(),
                new SemanticOracle.HostResponder() {
                });
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                composite.name() + ": the oracle completes main: " + run.terminal());
            for (TailMarker marker : markers) {
                check(run.trace().stream().noneMatch(
                        event -> event.op().equals(marker.op())),
                    composite.name() + ": the oracle emits no event for the tail op "
                        + marker.op());
            }

            if (composite.name().equals("null-both-return")) {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.runProject(lowered.project(),
                        lowered.tables(), lowered.registries(),
                        SemanticDifferentialHarness.Expectation.success(
                            composite.name(), List.of(), "null"), project.resolve("matrix"));
                check(verdict.runs().size() == 3, composite.name()
                    + ": the differential matrix produced all three consumers: "
                    + verdict.failures());
                check(verdict.pass(), composite.name()
                    + ": the oracle and both shared artifacts agree event-for-event: "
                    + verdict.failures());
                for (SemanticRuntimeModel.ConsumerRun consumer : verdict.runs()) {
                    for (TailMarker marker : markers) {
                        check(consumer.trace().stream().noneMatch(
                                event -> event.op().equals(marker.op())),
                            composite.name() + ": " + consumer.consumer()
                                + " emits no event for tail " + marker.op());
                    }
                }
            }
            compositeJvmDrive(composite, project, entry);
            compositeLuaDrive(composite, markers, project, entry);
        } finally {
            deleteRecursively(project);
        }
    }

    private static SemanticOp opWithString(List<SemanticOp> ops, String value) {
        for (SemanticOp op : ops) {
            if (op.kind() == SemanticOpKind.CONST
                    && op.payload() instanceof KindPayload.ConstPayload payload
                    && payload.value() instanceof ScalarValue.String text
                    && text.value().equals(value)) {
                return op;
            }
        }
        return null;
    }

    /**
     * The JVM leg: the project artifact keeps the reachability skip with no
     * tail statement emitted, compiles under
     * {@code javac --release 25 -proc:none}, and executes with the pinned
     * outcome.
     */
    private static void compositeJvmDrive(CompositeCase composite, Path project,
                                          Path entry) throws Exception {
        Path out = project.resolve("out-jvm");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("jvm", out.toString()));
        check(located.context() != null, composite.name() + " [jvm]: the "
            + "generated deal.json locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), composite.name() + " [jvm]: zero E6005 "
            + "over the release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            composite.name() + " [jvm]: exactly one project artifact and no "
                + "retained emission: semantic="
                + orchestrator.semanticEmissionCount() + " retained="
                + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path artifact = out.resolve(JvmBackend.classNameFor("main") + ".java");
        check(Files.isRegularFile(artifact), composite.name() + " [jvm]: the "
            + "project artifact is staged: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String text = Files.readString(artifact, StandardCharsets.UTF_8);
        for (String marker : composite.tailMarkers()) {
            check(!text.contains(marker), composite.name() + " [jvm]: the tail "
                + "statement '" + marker + "' is skipped from the emitted Java");
        }
        check(text.contains("// unreachable: the preceding statement cannot"
                + " complete normally"), composite.name() + " [jvm]: the artifact "
            + "keeps the JLS \u00a714.21 reachability skip marker");

        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        check(compileExit == 0, composite.name() + " [jvm]: the artifact compiles "
            + "under javac --release 25 -proc:none: " + compileOut);
        if (compileExit != 0) {
            return;
        }
        ProcessBuilder runner = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Main");
        runner.directory(out.toFile());
        Process process = runner.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), composite.name()
            + " [jvm]: the artifact executes with exit 0 and an empty transcript: "
            + "exit=" + exit + " stdout=\"" + stdout + "\" stderr=\"" + stderr
            + "\"");
    }

    /**
     * The LuaJIT leg: the production chunk emits each tail statement behind
     * its terminator in block order and executes with the pinned outcome
     * (the tail never runs).
     */
    private static void compositeLuaDrive(CompositeCase composite,
                                          List<TailMarker> markers, Path project,
                                          Path entry) throws Exception {
        Path out = project.resolve("out-luajit");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("luajit", out.toString()));
        check(located.context() != null, composite.name() + " [luajit]: the "
            + "generated deal.json locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), composite.name() + " [luajit]: zero "
            + "E6005 over the release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            composite.name() + " [luajit]: exactly one project artifact and no "
                + "retained emission: semantic="
                + orchestrator.semanticEmissionCount() + " retained="
                + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path chunk = out.resolve("main.lua");
        check(Files.isRegularFile(chunk), composite.name() + " [luajit]: the "
            + "project artifact is staged: " + chunk);
        if (!Files.isRegularFile(chunk)) {
            return;
        }
        String text = Files.readString(chunk, StandardCharsets.UTF_8);
        for (TailMarker marker : markers) {
            check(text.contains(marker.text()), composite.name() + " [luajit]: "
                + "the emitted chunk carries the tail statement '" + marker.text()
                + "'");
            if (marker.terminator() != null) {
                int tailAt = text.indexOf(quotedOpId(marker.op()));
                int terminatorAt = text.lastIndexOf(
                    quotedOpId(marker.terminator()), tailAt);
                check(tailAt >= 0 && terminatorAt >= 0 && terminatorAt < tailAt,
                    composite.name() + " [luajit]: the emitted chunk places the "
                    + "tail op " + marker.op() + " behind " + marker.terminator()
                    + " in block order");
            }
        }
        Path probe = out.resolve("__probe.lua");
        Files.writeString(probe, "dofile(\""
            + chunk.toAbsolutePath().normalize() + "\")\n"
            + "local __ok, __err = __dealMain()\n"
            + "if not __ok then os.exit(1) end\n"
            + "os.exit(0)\n", StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), composite.name()
            + " [luajit]: the artifact executes with exit 0 and an empty "
            + "transcript: exit=" + exit + " stdout=\"" + stdout + "\" stderr=\""
            + stderr + "\"");
    }

    // =========================================================================
    // 5. The unreachable loop tail: the JVM dispatch excludes skipped transfers
    // =========================================================================

    /**
     * The review's reproduction: a protected block whose tail is a loop
     * carrying a {@code break}/{@code continue}. The loop sits behind the
     * {@code throw}, so the JVM emission skips it (JLS &sect;14.21) and the
     * label its transfer would jump to is never defined. The enclosing
     * transfer dispatch must collect only the transfers of the emitted
     * reachable prefix; a raw structural collection emits a jump to the
     * undefined label and javac rejects the artifact.
     */
    private record LoopTransferCase(String name, String source,
                                    SemanticOpKind transferKind) {
    }

    private static final String UNREACHABLE_BREAK_LOOP_SOURCE = """
        export function main(): null {
          try {
            throw { code: "EXPECTED", message: "expected" };
            for (;;) {
              break;
            }
          } catch (e) {
          }
          return null;
        }
        """;

    private static final String UNREACHABLE_CONTINUE_LOOP_SOURCE = """
        export function main(): null {
          try {
            throw { code: "EXPECTED", message: "expected" };
            for (;;) {
              continue;
            }
          } catch (e) {
          }
          return null;
        }
        """;

    private static final List<LoopTransferCase> LOOP_TRANSFER_CASES = List.of(
        new LoopTransferCase("unreachable-break-loop",
            UNREACHABLE_BREAK_LOOP_SOURCE, SemanticOpKind.BREAK),
        new LoopTransferCase("unreachable-continue-loop",
            UNREACHABLE_CONTINUE_LOOP_SOURCE, SemanticOpKind.CONTINUE));

    private static void testUnreachableLoopTransferDispatch() throws Exception {
        System.out.println("-- The unreachable loop tail: real-javac dispatch "
            + "regression --");
        for (LoopTransferCase loopCase : LOOP_TRANSFER_CASES) {
            testLoopTransferCase(loopCase);
        }
    }

    private static OpId loopIdOf(SemanticOp transfer) {
        return switch (transfer.kind()) {
            case BREAK -> ((KindPayload.BreakPayload) transfer.payload()).loopId();
            case CONTINUE ->
                ((KindPayload.ContinuePayload) transfer.payload()).loopId();
            default -> null;
        };
    }

    private static void testLoopTransferCase(LoopTransferCase loopCase)
            throws Exception {
        Path project = Files.createTempDirectory("deal-unreachable-tail-"
            + loopCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), loopCase.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            LoweredProject lowered = lowerProgram(loopCase.name(), entry,
                new CliOverrides("jvm", project.resolve("out-oracle").toString()));
            if (lowered == null) {
                return;
            }
            LoweredModuleUnit unit = lowered.project().modules().get(lowered.entry());
            Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                byId.put(op.opId(), op);
            }
            SemanticOp transfer = unit.ops().stream()
                .filter(op -> op.kind() == loopCase.transferKind())
                .findFirst().orElse(null);
            check(transfer != null, loopCase.name() + ": the tail "
                + loopCase.transferKind() + " op is represented in the lowered "
                + "unit");
            if (transfer == null) {
                return;
            }
            OpId loopId = loopIdOf(transfer);
            SemanticOp loop = loopId == null ? null : byId.get(loopId);
            check(loop != null && loop.kind() == SemanticOpKind.LOOP, loopCase.name()
                + ": the tail transfer targets its loop op");
            if (loopId == null || loop == null) {
                return;
            }
            // The tail loop is a member of the try block behind the throw.
            BlockId owner = lowered.entryTable().opBlocks().get(loopId);
            check(owner != null, loopCase.name() + ": the tail loop is a member of "
                + "its block");
            if (owner == null) {
                return;
            }
            List<OpId> members = lowered.entryTable().blockOps().get(owner);
            int index = members.indexOf(loopId);
            OpId terminator = null;
            for (int i = 0; i < index; i++) {
                SemanticOp candidate = byId.get(members.get(i));
                if (candidate != null && isTerminator(candidate.kind())) {
                    terminator = candidate.opId();
                }
            }
            check(index > 0 && terminator != null, loopCase.name() + ": the tail "
                + "loop sits behind a terminator in its block");

            // The oracle runs the throw/catch path and never reaches the tail.
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(
                lowered.project(), lowered.tables(),
                new SemanticOracle.HostResponder() {
                });
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                loopCase.name() + ": the oracle completes main: " + run.terminal());
            check(run.trace().stream().noneMatch(event ->
                    event.op().equals(loopId) || event.op().equals(transfer.opId())),
                loopCase.name() + ": the oracle emits no event for the skipped tail "
                + "loop or its transfer");

            loopTransferJvmDrive(loopCase, loopId, project, entry);
            loopTransferLuaDrive(loopCase, loopId, transfer.opId(), project, entry);
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The JVM leg: the artifact keeps the reachability skip and defines no
     * label for the skipped tail loop (the dispatch never jumps to it),
     * compiles under {@code javac --release 25 -proc:none}, and executes
     * with the pinned outcome.
     */
    private static void loopTransferJvmDrive(LoopTransferCase loopCase, OpId loopId,
                                             Path project, Path entry)
            throws Exception {
        String name = loopCase.name();
        Path out = project.resolve("out-jvm");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("jvm", out.toString()));
        check(located.context() != null, name + " [jvm]: the generated deal.json "
            + "locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), name + " [jvm]: zero E6005 over the "
            + "release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            name + " [jvm]: exactly one project artifact and no retained "
                + "emission: semantic=" + orchestrator.semanticEmissionCount()
                + " retained=" + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path artifact = out.resolve(JvmBackend.classNameFor("main") + ".java");
        check(Files.isRegularFile(artifact), name + " [jvm]: the project artifact "
            + "is staged: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String text = Files.readString(artifact, StandardCharsets.UTF_8);
        check(text.contains("// unreachable: the preceding statement cannot"
                + " complete normally"), name + " [jvm]: the artifact keeps the "
            + "JLS \u00a714.21 reachability skip marker");
        long id = loopId.id();
        check(!text.contains("LOOP" + id + ":")
                && !text.contains("LOOP" + id + ";")
                && !text.contains("CONT" + id + ";"),
            name + " [jvm]: the transfer dispatch emits no arm for the skipped "
                + "tail loop " + loopId);

        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        check(compileExit == 0, name + " [jvm]: the artifact compiles under "
            + "javac --release 25 -proc:none: " + compileOut);
        if (compileExit != 0) {
            return;
        }
        ProcessBuilder runner = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Main");
        runner.directory(out.toFile());
        Process process = runner.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), name
            + " [jvm]: the artifact executes with exit 0 and an empty transcript: "
            + "exit=" + exit + " stdout=\"" + stdout + "\" stderr=\""
            + stderr + "\"");
    }

    /**
     * The LuaJIT leg: the chunk carries the tail loop and its transfer behind
     * the terminator, and executes with the pinned outcome (the tail never
     * runs).
     */
    private static void loopTransferLuaDrive(LoopTransferCase loopCase, OpId loopId,
                                             OpId transferId, Path project, Path entry)
            throws Exception {
        String name = loopCase.name();
        Path out = project.resolve("out-luajit");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("luajit", out.toString()));
        check(located.context() != null, name + " [luajit]: the generated deal.json "
            + "locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), name + " [luajit]: zero E6005 over the "
            + "release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            name + " [luajit]: exactly one project artifact and no retained "
                + "emission: semantic=" + orchestrator.semanticEmissionCount()
                + " retained=" + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path chunk = out.resolve("main.lua");
        check(Files.isRegularFile(chunk), name + " [luajit]: the project artifact "
            + "is staged: " + chunk);
        if (!Files.isRegularFile(chunk)) {
            return;
        }
        String text = Files.readString(chunk, StandardCharsets.UTF_8);
        check(text.contains(quotedOpId(loopId)), name + " [luajit]: the emitted "
            + "chunk carries the tail loop " + loopId);
        check(text.contains(quotedOpId(transferId)), name + " [luajit]: the "
            + "emitted chunk carries the tail transfer " + transferId);
        check(text.indexOf(quotedOpId(transferId)) > text.indexOf(quotedOpId(loopId)),
            name + " [luajit]: the emitted chunk places the tail transfer behind "
                + "its loop op");
        Path probe = out.resolve("__probe.lua");
        Files.writeString(probe, "dofile(\""
            + chunk.toAbsolutePath().normalize() + "\")\n"
            + "local __ok, __err = __dealMain()\n"
            + "if not __ok then os.exit(1) end\n"
            + "os.exit(0)\n", StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), name
            + " [luajit]: the artifact executes with exit 0 and an empty "
            + "transcript: exit=" + exit + " stdout=\"" + stdout + "\" stderr=\""
            + stderr + "\"");
    }

    // =========================================================================
    // 6. The in-scope loop transfer: a loop declared inside a protected
    //    region keeps its label at its own level (the review's break and
    //    continue regressions with represented tails)
    // =========================================================================

    /**
     * A loop declared inside a protected region whose body carries a
     * represented tail behind its {@code break}/{@code continue}. The
     * transfer's target label is defined at the loop's own level inside
     * the try block, so the transfer takes the emitted jump there and the
     * try's transfer dispatch must carry no arm that would jump to the
     * out-of-scope label. A dispatch that signals every transfer and jumps
     * at the enclosing catch emits {@code break LOOP<id>;} /
     * {@code continue CONT<id>;} where the label is not in scope, and
     * {@code javac --release 25 -proc:none} rejects the artifact with
     * {@code undefined label}. The same jump must name the target loop's
     * own label: a for-of loop owns {@code FE<id>}, never {@code LOOP<id>},
     * so a transfer out of a try inside its body jumps to {@code FE<id>} at
     * the try's dispatch.
     */
    private record InTryLoopCase(String name, String source, String tailMarker,
                                 SemanticOpKind transferKind, String jumpText,
                                 boolean directInTry) {
    }

    private static final String IN_TRY_BREAK_SOURCE = """
        export function main(): null {
          try {
            for (;;) {
              break;
              "loopBreakTail";
              let breakTail: int = 1;
            }
          } catch (e) {
          }
          return null;
        }
        """;

    private static final String IN_TRY_CONTINUE_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              if (i === 0) {
                continue;
                "loopContinueTail";
                let continueTail: int = 2;
              }
            }
          } catch (e) {
          }
          return null;
        }
        """;

    private static final String FOR_EACH_BREAK_TAIL_SOURCE = """
        export function main(): null {
          let arr: int[] = [1, 2, 3];
          let n: int = 0;
          for (let x: int of arr) {
            try {
              break;
              "foreachBreakTail";
            } catch (e) {
            }
            n = n + 1;
          }
          if (n !== 0) {
            throw { code: "TEST_FAIL", message: "for-of break tail" };
          }
          return null;
        }
        """;

    private static final String FOR_EACH_CONTINUE_TAIL_SOURCE = """
        export function main(): null {
          let arr: int[] = [1, 2, 3];
          let sum: int = 0;
          for (let x: int of arr) {
            try {
              continue;
              "foreachContinueTail";
            } catch (e) {
            }
            sum = sum + x;
          }
          if (sum !== 0) {
            throw { code: "TEST_FAIL", message: "for-of continue tail" };
          }
          return null;
        }
        """;

    private static final List<InTryLoopCase> IN_TRY_LOOP_CASES = List.of(
        new InTryLoopCase("in-try-break-loop", IN_TRY_BREAK_SOURCE,
            "loopBreakTail", SemanticOpKind.BREAK, "break LOOP", true),
        new InTryLoopCase("in-try-continue-loop", IN_TRY_CONTINUE_SOURCE,
            "loopContinueTail", SemanticOpKind.CONTINUE, "continue CONT", true),
        new InTryLoopCase("foreach-break-tail", FOR_EACH_BREAK_TAIL_SOURCE,
            "foreachBreakTail", SemanticOpKind.BREAK, "break FE", false),
        new InTryLoopCase("foreach-continue-tail", FOR_EACH_CONTINUE_TAIL_SOURCE,
            "foreachContinueTail", SemanticOpKind.CONTINUE, "continue FE", false));

    private static final Set<String> IN_TRY_LOOP_MATRIX_CASES = Set.of(
        "in-try-break-loop", "in-try-continue-loop", "foreach-continue-tail");

    private static void testInTryLoopTransferDispatch() throws Exception {
        System.out.println("-- The in-scope loop transfer: real-javac break and "
            + "continue regressions --");
        for (InTryLoopCase loopCase : IN_TRY_LOOP_CASES) {
            testInTryLoopCase(loopCase);
        }
    }

    private static void testInTryLoopCase(InTryLoopCase loopCase) throws Exception {
        Path project = Files.createTempDirectory("deal-unreachable-tail-"
            + loopCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), loopCase.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            LoweredProject lowered = lowerProgram(loopCase.name(), entry,
                new CliOverrides("jvm", project.resolve("out-oracle").toString()));
            if (lowered == null) {
                return;
            }
            LoweredModuleUnit unit = lowered.project().modules().get(lowered.entry());
            SemanticOp marker = opWithString(unit.ops(), loopCase.tailMarker());
            check(marker != null, loopCase.name() + ": the tail statement '"
                + loopCase.tailMarker() + "' is represented in the lowered unit");
            SemanticOp transfer = unit.ops().stream()
                .filter(op -> op.kind() == loopCase.transferKind())
                .findFirst().orElse(null);
            check(transfer != null, loopCase.name() + ": the "
                + loopCase.transferKind() + " op is represented in the lowered "
                + "unit");
            if (marker == null || transfer == null) {
                return;
            }
            BlockId owner = lowered.entryTable().opBlocks().get(marker.opId());
            check(owner != null && owner.equals(
                    lowered.entryTable().opBlocks().get(transfer.opId())),
                loopCase.name() + ": the tail op " + marker.opId() + " is a "
                    + "member of its transfer's block");
            if (owner != null) {
                List<OpId> members = lowered.entryTable().blockOps().get(owner);
                check(members.indexOf(transfer.opId()) < members.indexOf(marker.opId()),
                    loopCase.name() + ": the tail op follows its "
                        + loopCase.transferKind() + " in block order");
            }
            OpId loopId = loopIdOf(transfer);
            check(loopId != null, loopCase.name() + ": the transfer targets a loop op");
            if (loopId == null) {
                return;
            }

            // The oracle executes the loop, takes the transfer, and never
            // executes the represented tail statement.
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(
                lowered.project(), lowered.tables(),
                new SemanticOracle.HostResponder() {
                });
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                loopCase.name() + ": the oracle completes main: " + run.terminal());
            check(run.trace().stream().noneMatch(
                    event -> event.op().equals(marker.opId())),
                loopCase.name() + ": the oracle emits no event for the tail op "
                    + marker.opId());

            // The three-consumer trace comparison over the in-scope transfer:
            // the oracle and both shared artifacts agree event-for-event.
            if (IN_TRY_LOOP_MATRIX_CASES.contains(loopCase.name())) {
                Path workspace = Files.createTempDirectory(
                    "deal-unreachable-tail-in-try-matrix-" + loopCase.name() + "-");
                try {
                    SemanticDifferentialHarness.Verdict verdict =
                        SemanticDifferentialHarness.runProject(lowered.project(),
                            lowered.tables(), lowered.registries(),
                            SemanticDifferentialHarness.Expectation.success(
                                loopCase.name(), List.of(), "null"), workspace);
                    check(verdict.runs().size() == 3, loopCase.name() + ": the "
                        + "differential matrix produced the three consumers: "
                        + verdict.failures());
                    check(verdict.pass(), loopCase.name() + ": the oracle and both "
                        + "shared artifacts agree event-for-event over the in-scope "
                        + "loop transfer: " + verdict.failures());
                    for (SemanticRuntimeModel.ConsumerRun consumer : verdict.runs()) {
                        check(consumer.trace().stream().noneMatch(
                                event -> event.op().equals(marker.opId())),
                            loopCase.name() + ": " + consumer.consumer() + " emits no "
                                + "event for the represented tail op " + marker.opId());
                    }
                } finally {
                    deleteRecursively(workspace);
                }
            }

            inTryLoopJvmDrive(loopCase, loopId, marker, project, entry);
            inTryLoopLuaDrive(loopCase, loopId, marker.opId(), project, entry);
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The JVM leg: the artifact carries the target loop's own jump, keeps
     * the reachability skip for the represented tail with no tail origin,
     * and never references an out-of-scope label (the dispatch arm re-raises
     * the marker for a target inside the protected region), compiles under
     * {@code javac --release 25 -proc:none}, and executes with exit 0 and an
     * empty transcript.
     */
    private static void inTryLoopJvmDrive(InTryLoopCase loopCase, OpId loopId,
                                          SemanticOp marker, Path project, Path entry)
            throws Exception {
        String name = loopCase.name();
        Path out = project.resolve("out-jvm");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("jvm", out.toString()));
        check(located.context() != null, name + " [jvm]: the generated deal.json "
            + "locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), name + " [jvm]: zero E6005 over the "
            + "release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            name + " [jvm]: exactly one project artifact and no retained "
                + "emission: semantic=" + orchestrator.semanticEmissionCount()
                + " retained=" + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path artifact = out.resolve(JvmBackend.classNameFor("main") + ".java");
        check(Files.isRegularFile(artifact), name + " [jvm]: the project artifact "
            + "is staged: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String text = Files.readString(artifact, StandardCharsets.UTF_8);
        long id = loopId.id();
        check(text.contains(loopCase.jumpText() + id),
            name + " [jvm]: the artifact carries the target loop's own jump ("
                + loopCase.jumpText() + id + ";)");
        // The dispatch arm's body must never reference a label outside its
        // scope: with the loop inside the try the label is only in scope at
        // the transfer site, so the arm re-raises the marker; with the loop
        // enclosing the try the arm takes the target loop's own jump.
        String arm = "__tr.id == " + id + "L) {";
        int armAt = text.indexOf(arm);
        check(armAt >= 0, name + " [jvm]: the try's transfer dispatch carries "
            + "the collected transfer's arm");
        if (armAt >= 0) {
            String armBody = text.substring(armAt + arm.length()).stripLeading();
            if (loopCase.directInTry()) {
                check(armBody.startsWith("throw __tr;"), name + " [jvm]: the "
                    + "dispatch arm re-raises the marker instead of jumping to "
                    + "the out-of-scope label: "
                    + armBody.lines().findFirst().orElse("<eof>"));
            } else {
                check(armBody.startsWith(loopCase.jumpText() + id + ";"), name
                    + " [jvm]: the dispatch arm jumps to the target loop's own "
                    + "label: " + armBody.lines().findFirst().orElse("<eof>"));
            }
        }
        check(text.contains("// unreachable: the preceding statement cannot"
                + " complete normally"), name + " [jvm]: the artifact keeps the "
            + "JLS \u00a714.21 reachability skip marker");
        check(!text.contains(loopCase.tailMarker()), name + " [jvm]: the tail "
            + "statement is skipped from the emitted Java");
        check(!text.contains(originOf(marker)), name + " [jvm]: the tail op origin "
            + originOf(marker) + " is absent from the emitted Java");

        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        check(compileExit == 0, name + " [jvm]: the artifact compiles under "
            + "javac --release 25 -proc:none: " + compileOut);
        if (compileExit != 0) {
            return;
        }
        ProcessBuilder runner = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Main");
        runner.directory(out.toFile());
        Process process = runner.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), name
            + " [jvm]: the artifact executes with exit 0 and an empty transcript: "
            + "exit=" + exit + " stdout=\"" + stdout + "\" stderr=\""
            + stderr + "\"");
    }

    /**
     * The LuaJIT leg: the chunk carries the represented tail behind its
     * transfer and jumps at the loop's own level, and executes with exit 0
     * and an empty transcript.
     */
    private static void inTryLoopLuaDrive(InTryLoopCase loopCase, OpId loopId,
                                          OpId markerId, Path project, Path entry)
            throws Exception {
        String name = loopCase.name();
        Path out = project.resolve("out-luajit");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("luajit", out.toString()));
        check(located.context() != null, name + " [luajit]: the generated deal.json "
            + "locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), name + " [luajit]: zero E6005 over the "
            + "release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            name + " [luajit]: exactly one project artifact and no retained "
                + "emission: semantic=" + orchestrator.semanticEmissionCount()
                + " retained=" + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path chunk = out.resolve("main.lua");
        check(Files.isRegularFile(chunk), name + " [luajit]: the project artifact "
            + "is staged: " + chunk);
        if (!Files.isRegularFile(chunk)) {
            return;
        }
        String text = Files.readString(chunk, StandardCharsets.UTF_8);
        check(text.contains(quotedOpId(loopId)), name + " [luajit]: the emitted "
            + "chunk carries the loop " + loopId);
        check(text.contains(quotedOpId(markerId)), name + " [luajit]: the emitted "
            + "chunk carries the represented tail " + markerId);
        check(text.indexOf(quotedOpId(markerId)) > text.indexOf(quotedOpId(loopId)),
            name + " [luajit]: the emitted chunk places the tail behind its loop");
        Path probe = out.resolve("__probe.lua");
        Files.writeString(probe, "dofile(\""
            + chunk.toAbsolutePath().normalize() + "\")\n"
            + "local __ok, __err = __dealMain()\n"
            + "if not __ok then os.exit(1) end\n"
            + "os.exit(0)\n", StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("luajit",
            probe.toAbsolutePath().toString());
        builder.directory(out.toFile());
        builder.environment().put("DEAL_DEFER_MAIN", "1");
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), name
            + " [luajit]: the artifact executes with exit 0 and an empty "
            + "transcript: exit=" + exit + " stdout=\"" + stdout + "\" stderr=\""
            + stderr + "\"");
    }

    // =========================================================================
    // 7. The updated FOR inside TRY_CATCH: the update reachability
    // =========================================================================

    /**
     * The review's reproduction and its variants: an updated FOR inside
     * {@code TRY_CATCH} whose emitted {@code CONT} do statement cannot
     * complete normally (every emitted path of the body leaves by
     * break/return/throw and no reachable continue targets the loop). The
     * update ops stay in the lowered unit; the JVM artifact replaces them
     * with the JLS &sect;14.21 skip marker, and the emitted Java compiles
     * under {@code javac --release 25 -proc:none} and runs. A reachable
     * continue targeting the loop completes the emitted do statement
     * through the {@code CONT} label, so its update stays emitted and
     * executed.
     *
     * <p>The distinction is exact: a composite's ordinary fall-through is
     * not an immediate answer — the walk keeps examining the remaining
     * members of the enclosing block and stops at the first non-completing
     * statement after it — and only a reachable continue targeting the loop
     * (directly or on a composite path) answers update-reachable at once.
     * The composite-prefix cases cover both composite arms and all three
     * transfers, and the composite-continue case keeps the
     * continue-to-update positive under the corrected rule.</p>
     *
     * <p>{@code traceCompared} is false only for the throw bodies: a
     * {@code DealFailure} propagating through a composite leaves the
     * structurally enclosing composite op with a START and no terminal in
     * the shared artifacts while the oracle emits the FAILURE terminal — a
     * pre-existing oracle/emitter terminal projection gap that a no-tail,
     * update-reachable minimal program reproduces identically with and
     * without this change, so it is not a property of the update
     * reachability. The throw case still requires the oracle's completion,
     * the represented tail's absence, and the real
     * {@code javac --release 25 -proc:none} compilation.</p>
     */
    private record ForUpdateCase(String name, String source, String tailMarker,
                                 boolean updateSkipped, boolean traceCompared) {
    }

    private static final String FOR_UPDATE_BREAK_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              break;
              "forUpdateBreakTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    private static final String FOR_UPDATE_RETURN_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              return null;
              "forUpdateReturnTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    private static final String FOR_UPDATE_THROW_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              throw { code: "EXPECTED", message: "expected" };
              "forUpdateThrowTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    /**
     * The continue-to-update case: the body's only update-reaching path is
     * the {@code continue} (the {@code if} branch continues until the
     * guard breaks in its {@code else}), so a skipped update would fail the
     * post-loop tick check instead of looping forever.
     */
    private static final String FOR_UPDATE_CONTINUE_SOURCE = """
        export function main(): null {
          let ticks: int = 0;
          let guard: int = 0;
          try {
            for (let i: int = 0; i < 1000000; ticks = ticks + 1) {
              guard = guard + 1;
              if (guard < 6) {
                continue;
                "forUpdateContinueTail";
              } else {
                break;
              }
            }
          } catch (e) {
          }
          if (ticks !== 5) {
            throw { code: "TEST_FAIL", message: "the for update did not run" };
          }
          return null;
        }
        """;

    /**
     * The reviewer's exact reproduction: a completing composite prefix (the
     * else-less {@code if}) followed by an unconditional {@code break}. The
     * composite's ordinary fall-through returns to the enclosing block, so
     * the walk keeps examining the remaining members and stops at the
     * {@code break}: the update is unreachable and must be skipped. Reading
     * the composite alone as update-reaching emitted the update statements,
     * and real {@code javac --release 25 -proc:none} rejected them with
     * {@code unreachable statement}.
     */
    private static final String FOR_UPDATE_IF_BREAK_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              if (i === 0) {
                "reachable";
              }
              break;
              "forUpdateIfBreakTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    /**
     * The composite-prefix distinction with both arms present and a
     * {@code return} after the prefix.
     */
    private static final String FOR_UPDATE_ELSE_RETURN_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              if (i === 0) {
                "selected";
              } else {
                "alternate";
              }
              return null;
              "forUpdateElseReturnTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    /**
     * The composite-prefix distinction with a {@code throw} after the
     * prefix.
     */
    private static final String FOR_UPDATE_IF_THROW_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              if (i === 0) {
                "reachable";
              }
              throw { code: "EXPECTED", message: "expected" };
              "forUpdateIfThrowTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    /**
     * The composite-prefix distinction for the try arm: the protected block
     * completes normally, so its fall-through alone must not answer
     * update-reaching; the following {@code break} must.
     */
    private static final String FOR_UPDATE_TRY_BREAK_SOURCE = """
        export function main(): null {
          try {
            for (let i: int = 0; i < 2; i = i + 1) {
              try {
                if (i === 0) {
                  "reachable";
                }
              } catch (e) {
              }
              break;
              "forUpdateTryBreakTail";
            }
          } catch (e) {
          }
          return null;
        }
        """;

    /**
     * The continue-to-update positive under the corrected distinction: the
     * composite prefix carries the only update-reaching continue, the
     * unconditional {@code break} behind it leaves every other path out of
     * the loop, and the update must still be emitted and executed (the
     * post-loop tick check fails if it is skipped, and the guard bound keeps
     * a skipped update from looping forever).
     */
    private static final String FOR_UPDATE_IF_CONTINUE_SOURCE = """
        export function main(): null {
          let ticks: int = 0;
          let guard: int = 0;
          try {
            for (let i: int = 0; i < 1000000; ticks = ticks + 1) {
              guard = guard + 1;
              if (guard < 6) {
                continue;
                "forUpdateIfContinueTail";
              }
              break;
            }
          } catch (e) {
          }
          if (ticks !== 5) {
            throw { code: "TEST_FAIL", message: "the for update did not run" };
          }
          return null;
        }
        """;

    /**
     * The try-arm continue-to-update positive under the corrected
     * distinction: the only update-reaching path is the continue inside the
     * protected block (the unreachable tail sits behind it), the
     * unconditional {@code break} behind the composite leaves every other
     * path out of the loop, and the update must still be emitted and
     * executed.
     */
    private static final String FOR_UPDATE_TRY_CONTINUE_SOURCE = """
        export function main(): null {
          let ticks: int = 0;
          let guard: int = 0;
          try {
            for (let i: int = 0; i < 1000000; ticks = ticks + 1) {
              guard = guard + 1;
              try {
                if (guard < 6) {
                  continue;
                  "forUpdateTryContinueTail";
                }
              } catch (e) {
              }
              break;
            }
          } catch (e) {
          }
          if (ticks !== 5) {
            throw { code: "TEST_FAIL", message: "the for update did not run" };
          }
          return null;
        }
        """;

    private static final List<ForUpdateCase> FOR_UPDATE_CASES = List.of(
        new ForUpdateCase("for-update-break-tail", FOR_UPDATE_BREAK_SOURCE,
            "forUpdateBreakTail", true, true),
        new ForUpdateCase("for-update-return-tail", FOR_UPDATE_RETURN_SOURCE,
            "forUpdateReturnTail", true, true),
        new ForUpdateCase("for-update-throw-tail", FOR_UPDATE_THROW_SOURCE,
            "forUpdateThrowTail", true, false),
        new ForUpdateCase("for-update-continue-tail", FOR_UPDATE_CONTINUE_SOURCE,
            "forUpdateContinueTail", false, true),
        new ForUpdateCase("for-update-if-break-tail", FOR_UPDATE_IF_BREAK_SOURCE,
            "forUpdateIfBreakTail", true, true),
        new ForUpdateCase("for-update-else-return-tail",
            FOR_UPDATE_ELSE_RETURN_SOURCE, "forUpdateElseReturnTail", true, true),
        new ForUpdateCase("for-update-if-throw-tail",
            FOR_UPDATE_IF_THROW_SOURCE, "forUpdateIfThrowTail", true, false),
        new ForUpdateCase("for-update-try-break-tail", FOR_UPDATE_TRY_BREAK_SOURCE,
            "forUpdateTryBreakTail", true, true),
        new ForUpdateCase("for-update-if-continue-tail",
            FOR_UPDATE_IF_CONTINUE_SOURCE, "forUpdateIfContinueTail", false, true),
        new ForUpdateCase("for-update-try-continue-tail",
            FOR_UPDATE_TRY_CONTINUE_SOURCE, "forUpdateTryContinueTail", false, true));

    private static final Set<String> FOR_UPDATE_MATRIX_CASES = Set.of(
        "for-update-if-break-tail", "for-update-if-continue-tail",
        "for-update-try-continue-tail");

    private static void testForUpdateReachability() throws Exception {
        System.out.println("-- The updated FOR inside TRY_CATCH: real-javac update "
            + "reachability regression --");
        for (ForUpdateCase forCase : FOR_UPDATE_CASES) {
            testForUpdateCase(forCase);
        }
    }

    private static void testForUpdateCase(ForUpdateCase forCase) throws Exception {
        Path project = Files.createTempDirectory("deal-unreachable-tail-"
            + forCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), forCase.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            LoweredProject lowered = lowerProgram(forCase.name(), entry,
                new CliOverrides("jvm", project.resolve("out-oracle").toString()));
            if (lowered == null) {
                return;
            }
            LoweredModuleUnit unit = lowered.project().modules().get(lowered.entry());
            Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
            for (SemanticOp op : unit.ops()) {
                byId.put(op.opId(), op);
            }
            SemanticOp loop = unit.ops().stream()
                .filter(op -> op.kind() == SemanticOpKind.LOOP)
                .filter(op -> ((KindPayload.LoopPayload) op.payload()).selector()
                    == ControlSelector.FOR)
                .findFirst().orElse(null);
            check(loop != null, forCase.name() + ": the updated FOR loop op is "
                + "represented in the lowered unit");
            if (loop == null) {
                return;
            }
            KindPayload.LoopPayload payload =
                (KindPayload.LoopPayload) loop.payload();
            List<OpId> updateMembers = payload.updateBlock() == null ? null
                : lowered.entryTable().blockOps().get(payload.updateBlock());
            check(updateMembers != null && !updateMembers.isEmpty(), forCase.name()
                + ": the update block keeps its ops in the lowered unit");
            if (updateMembers == null || updateMembers.isEmpty()) {
                return;
            }
            for (OpId updateOp : updateMembers) {
                check(byId.containsKey(updateOp), forCase.name() + ": the update op "
                    + updateOp + " is represented in the lowered unit");
            }
            SemanticOp marker = opWithString(unit.ops(), forCase.tailMarker());
            check(marker != null, forCase.name() + ": the tail statement '"
                + forCase.tailMarker() + "' is represented in the lowered unit");
            if (marker == null) {
                return;
            }

            // The oracle executes the loop, takes the transfer (or the
            // continue path), and never executes the represented tail.
            SemanticRuntimeModel.ConsumerRun run = SemanticOracle.execute(
                lowered.project(), lowered.tables(),
                new SemanticOracle.HostResponder() {
                });
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                forCase.name() + ": the oracle completes main: " + run.terminal());
            check(run.trace().stream().noneMatch(
                    event -> event.op().equals(marker.opId())),
                forCase.name() + ": the oracle emits no event for the tail op "
                    + marker.opId());

            if (forCase.traceCompared()
                    && FOR_UPDATE_MATRIX_CASES.contains(forCase.name())) {
                Path workspace = Files.createTempDirectory(
                    "deal-unreachable-tail-for-update-matrix-" + forCase.name() + "-");
                try {
                    SemanticDifferentialHarness.Verdict verdict =
                        SemanticDifferentialHarness.runProject(lowered.project(),
                            lowered.tables(), lowered.registries(),
                            SemanticDifferentialHarness.Expectation.success(
                                forCase.name(), List.of(), "null"), workspace);
                    check(verdict.runs().size() == 3, forCase.name() + ": the "
                        + "differential matrix produced the three consumers: "
                        + verdict.failures());
                    check(verdict.pass(), forCase.name() + ": the oracle and both "
                        + "shared artifacts agree event-for-event over the updated "
                        + "FOR: " + verdict.failures());
                    for (SemanticRuntimeModel.ConsumerRun consumer : verdict.runs()) {
                        check(consumer.trace().stream().noneMatch(
                                event -> event.op().equals(marker.opId())),
                            forCase.name() + ": " + consumer.consumer() + " emits no "
                                + "event for the represented tail op " + marker.opId());
                    }
                } finally {
                    deleteRecursively(workspace);
                }
            }

            forUpdateJvmDrive(forCase, loop, updateMembers, marker, project, entry);
        } finally {
            deleteRecursively(project);
        }
    }

    /**
     * The JVM leg: the artifact skips the update of a CONT do statement
     * that cannot complete normally (the marker replaces the update
     * statements, whose ops stay in the lowered unit) or emits it when a
     * reachable continue completes the do statement, compiles under
     * {@code javac --release 25 -proc:none}, and executes with exit 0 and
     * an empty transcript.
     */
    private static void forUpdateJvmDrive(ForUpdateCase forCase, SemanticOp loop,
                                          List<OpId> updateMembers, SemanticOp marker,
                                          Path project, Path entry) throws Exception {
        String name = forCase.name();
        Path out = project.resolve("out-jvm");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("jvm", out.toString()));
        check(located.context() != null, name + " [jvm]: the generated deal.json "
            + "locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = orchestrator.diagnostics().stream()
            .filter(diagnostic -> "error".equals(diagnostic.severity()))
            .map(CompilerDiagnostic::message).toList();
        check(compiled && errors.isEmpty(), name + " [jvm]: zero E6005 over the "
            + "release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1
                && orchestrator.retainedEmissionCount() == 0,
            name + " [jvm]: exactly one project artifact and no retained "
                + "emission: semantic=" + orchestrator.semanticEmissionCount()
                + " retained=" + orchestrator.retainedEmissionCount());
        if (!compiled) {
            return;
        }
        Path artifact = out.resolve(JvmBackend.classNameFor("main") + ".java");
        check(Files.isRegularFile(artifact), name + " [jvm]: the project artifact "
            + "is staged: " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String text = Files.readString(artifact, StandardCharsets.UTF_8);
        long id = loop.opId().id();
        String doOpen = "CONT" + id + ": do {";
        int doOpenAt = text.indexOf(doOpen);
        check(doOpenAt >= 0, name + " [jvm]: the artifact carries the loop's "
            + "CONT do statement (" + doOpen + ")");
        int doCloseAt = doOpenAt < 0 ? -1
            : text.indexOf("} while (false);", doOpenAt);
        check(doCloseAt >= 0, name + " [jvm]: the artifact closes the CONT do "
            + "statement");
        if (doCloseAt >= 0) {
            String afterDo = text
                .substring(doCloseAt + "} while (false);".length()).stripLeading();
            if (forCase.updateSkipped()) {
                check(afterDo.startsWith("// unreachable:"), name + " [jvm]: the "
                    + "update of the non-completing CONT do statement is replaced "
                    + "by the JLS \u00a714.21 skip marker: "
                    + afterDo.lines().findFirst().orElse("<eof>"));
                for (OpId updateOp : updateMembers) {
                    check(!text.contains(quotedOpId(updateOp)), name + " [jvm]: the "
                        + "skipped update op " + updateOp + " is not emitted");
                }
            } else {
                check(!afterDo.startsWith("// unreachable:"), name + " [jvm]: the "
                    + "reachable update of the CONT do statement is emitted");
                check(updateMembers.stream().anyMatch(
                        updateOp -> text.contains(quotedOpId(updateOp))),
                    name + " [jvm]: the emitted update carries its lowered ops");
                check(text.contains("continue CONT" + id + ";"), name + " [jvm]: "
                    + "the reachable continue jumps to the CONT label");
            }
        }
        check(text.contains("// unreachable: the preceding statement cannot"
                + " complete normally"), name + " [jvm]: the artifact keeps the "
            + "JLS \u00a714.21 reachability skip marker");
        check(!text.contains(forCase.tailMarker()), name + " [jvm]: the tail "
            + "statement is skipped from the emitted Java");
        check(!text.contains(originOf(marker)), name + " [jvm]: the tail op origin "
            + originOf(marker) + " is absent from the emitted Java");

        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        check(compileExit == 0, name + " [jvm]: the artifact compiles under "
            + "javac --release 25 -proc:none: " + compileOut);
        if (compileExit != 0) {
            return;
        }
        ProcessBuilder runner = new ProcessBuilder("java", "-cp",
            classpath + File.pathSeparator + classes, "Main");
        runner.directory(out.toFile());
        Process process = runner.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        check(exit == 0 && stdout.isEmpty() && stderr.isEmpty(), name
            + " [jvm]: the artifact executes with exit 0 and an empty transcript: "
            + "exit=" + exit + " stdout=\"" + stdout + "\" stderr=\""
            + stderr + "\"");
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path, FileVisitOption.values())) {
            walk.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Unreachable Tail Production Test (ISSUE-0713) ===\n");
        testTailBattery();
        for (String stem : TAIL_FIXTURES) {
            testFixture(stem);
        }
        testCompositeBlockTails();
        testUnreachableLoopTransferDispatch();
        testInTryLoopTransferDispatch();
        testForUpdateReachability();
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
