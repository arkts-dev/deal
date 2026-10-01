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
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ConstructKind;
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
 *       the lowered unit's op ids and origins, not to a copy.</li>
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
 *       guards therefore stay false.</li>
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
                                  ModuleId entry, StructuredBodyTable entryTable,
                                  Long asyncEntryId) {
    }

    private static LoweredProject lowerFixture(String stem, Path entry,
            CliOverrides overrides) throws Exception {
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entry.toString(), overrides);
        check(located.context() != null, stem + ": the generated deal.json "
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
            stem + ": the oracle closure compiles: "
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
        check(result.project() != null, stem + ": the oracle closure lowers with "
            + "zero diagnostics: " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId entryModule = result.project().entryModule();
        Long asyncEntryId = asyncEntryIdOf(result.project().modules().get(entryModule),
            asyncExportOf(stem));
        check(asyncEntryId != null, stem + ": the unit records the async export's "
            + "EXTERNAL_ENTRY");
        return new LoweredProject(result.project(), result.tables(), entryModule,
            result.tables().get(entryModule), asyncEntryId);
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
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
