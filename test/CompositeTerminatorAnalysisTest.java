package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.checker.CheckResult;
import deal.checker.ModuleResolver;
import deal.checker.NameResolver;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.checker.TypeChecker;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.CompilationOrchestrator;
import deal.parser.ParseResult;
import deal.parser.Parser;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectBuilder;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.LoweringSupport;
import deal.semantic.ModuleFact;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ScalarValue;
import deal.semantic.ir.SemanticIdAllocator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.types.Type;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CompositeTerminatorAnalysisTest {

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // The four named fixtures
    // =========================================================================

    private static final Path FIXTURE_ROOT =
        Path.of("test/conformance/backend-runtime");

    /**
     * One named fixture: the corpus path, its exported zero-arity probe,
     * and the pinned probe value (the DEAL expression the driver asserts).
     */
    private record Fixture(String relativePath, String probe, String pinnedValue,
                           boolean async) {

        /**
         * The pinned probe value as the raw runtime text of the executed
         * probe: a DEAL string literal loses its quotes, every other pinned
         * value ({@code 0}, {@code true}) is already its own runtime text.
         */
        String pinnedRuntimeText() {
            if (pinnedValue.length() >= 2 && pinnedValue.startsWith("\"")
                    && pinnedValue.endsWith("\"")) {
                return pinnedValue.substring(1, pinnedValue.length() - 1);
            }
            return pinnedValue;
        }
    }

    private static final List<Fixture> FIXTURES = List.of(
        new Fixture("async-await/async-if-branching", "f", "10", true),
        new Fixture("control-flow/return-inside-try", "test_return_inside_try", "7",
            false),
        new Fixture("descriptors/canonical-class-atom-error-roundtrip",
            "test_canonical_error_atom_roundtrip", "\"canonical error atom ok\"",
            false),
        new Fixture("error-handling/error-roundtrip", "test_error_roundtrip",
            "\"error roundtrip ok\"", false));

    /**
     * The named direct-recursion acceptance subject (ISSUE-0733): the
     * corpus fixture's nested self-recursive declaration — the shape the
     * removed SemanticProductionGateTest drove — compiles through the
     * release-owned production invocation to exactly one emitted project
     * artifact per target that runs.
     */
    private static final Fixture DIRECT_RECURSION_FIXTURE = new Fixture(
        "functions/direct-recursion", "test_direct_recursion", "0", false);

    /**
     * The named mutual-recursion acceptance subject: the corpus fixture's
     * module-level mutually recursive pair ({@code even}/{@code odd}) whose
     * exported {@code test_mutual_recursion} probe pins the boolean true.
     * The release-owned production invocation must stage exactly one project
     * artifact per target whose published surface runs the probe.
     */
    private static final Fixture MUTUAL_RECURSION_FIXTURE = new Fixture(
        "functions/mutual-recursion", "test_mutual_recursion", "true", false);

    /**
     * The residual acceptance subjects of the staged production drive and
     * the three-consumer differential drive: the direct and nested-scope
     * self-recursion, the mutual recursion, and the protected return.
     */
    private static final List<Fixture> RESIDUAL_ACCEPTANCE_FIXTURES = List.of(
        DIRECT_RECURSION_FIXTURE,
        MUTUAL_RECURSION_FIXTURE,
        new Fixture("functions/nested-scope-recursion", "test_nested_scope_recursion",
            "0", false),
        new Fixture("control-flow/return-in-try", "test_return_in_try_loop", "5", false));

    /**
     * The async-await-statement acceptance subject: the asynchronous export
     * {@code f} whose body discards the {@code await g()} completion — the
     * discarded-await probe. The staged drive executes the artifact's own
     * driven async entry, so the discarded await's completion check and the
     * fixture's guards run to completion on both targets.
     */
    private static final Fixture ASYNC_AWAIT_STATEMENT_FIXTURE = new Fixture(
        "async-await/async-await-statement", "f", "0", true);

    /**
     * The three recursive acceptance subjects: the differential harness's
     * two re-entry pairing notices (a recursive call op starts again before
     * its previous terminal and also terminates without a matching open
     * START) are the accepted shape of their recursive group, so only those
     * notices are tolerated; every other harness failure still fails.
     */
    private static final Set<String> RECURSIVE_FIXTURES = Set.of(
        "functions/direct-recursion", "functions/nested-scope-recursion",
        "functions/mutual-recursion");

    static final String NULL_BOTH_RETURN_TAIL_SOURCE = """
        export function probe(c: boolean): null {
          if (c) {
            return null;
            "nullTrueTailMarker";
            let x: int = 9715;
          } else {
            return null;
            "nullFalseTailMarker";
            let y: int = 9716;
          }
          "nullBodyTailMarker";
          let tailMarker: int = 715;
          if (tailMarker === 715) {
            throw { code: "TAIL_EXECUTED", message: "TAIL715" }
          }
        }

        export function main(): null {
          probe(true);
          probe(false);
          return null;
        }
        """;

    private static String fixtureSource(String relativePath) throws Exception {
        String corpusPath = relativePath + ".deal";
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(FIXTURE_ROOT.resolve(corpusPath),
                StandardCharsets.UTF_8),
            corpusPath);
    }

    /**
     * One fixture's own pinned shape and sidecar: the source carries the
     * composite the closed terminator analysis must find, and the sidecar
     * pins {@code runtime-ok} with exit 0 and both empty transcripts.
     */
    static void testCorpusInventory() throws Exception {
        System.out.println("-- the four named fixtures and their pinned shapes "
            + "and sidecars --");
        for (Fixture fixture : FIXTURES) {
            Path source = FIXTURE_ROOT.resolve(fixture.relativePath() + ".deal");
            Path sidecar = FIXTURE_ROOT.resolve(fixture.relativePath() + ".expect.json");
            check(Files.exists(source), fixture.relativePath()
                + ": the fixture is present");
            check(Files.exists(sidecar), fixture.relativePath()
                + ": the sidecar is present");
            if (!Files.exists(sidecar)) {
                continue;
            }
            String sidecarText = Files.readString(sidecar, StandardCharsets.UTF_8);
            check(sidecarText.contains("\"mode\": \"runtime-ok\""),
                fixture.relativePath() + ": the sidecar pins the runtime-ok mode");
            checkEq(0, sidecarExitCode(sidecarText), fixture.relativePath()
                + ": the sidecar pins exit code 0");
            checkEq("", sidecarTranscript(sidecarText, "stdout"),
                fixture.relativePath() + ": the sidecar pins an empty stdout");
            checkEq("", sidecarTranscript(sidecarText, "stderr"),
                fixture.relativePath() + ": the sidecar pins an empty stderr");
            String text = fixtureSource(fixture.relativePath());
            switch (fixture.relativePath()) {
                case "async-await/async-if-branching" -> {
                    check(text.contains("if (n > 0) {") && text.contains("return await g(n);")
                            && text.contains("return await g(0);"),
                        "async-if-branching: both branches of the if/else return "
                            + "(the BRANCH composite arm)");
                }
                case "control-flow/return-inside-try" -> check(
                    text.contains("try {") && text.contains("return 7;")
                        && text.contains("catch (e) {"),
                    "return-inside-try: the nested declaration's try/catch both "
                        + "return (the TRY_CATCH composite arm)");
                default -> check(text.contains("throw { code: \"E_")
                        && text.contains("} catch (e) {") && text.contains("return e;"),
                    fixture.relativePath() + ": the try block throws and the catch "
                        + "block returns (the TRY_CATCH composite arm)");
            }
        }
    }

    private static int sidecarExitCode(String sidecar) {
        Matcher matcher = Pattern.compile("\"exitCode\":\\s*(-?\\d+)").matcher(sidecar);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MIN_VALUE;
    }

    private static String sidecarTranscript(String sidecar, String channel) {
        Matcher matcher = Pattern.compile("\"" + channel
            + "\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(sidecar);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"");
    }

    // =========================================================================
    // 1. The unit battery through the registered lowering session
    // =========================================================================

    /** One lowered probe unit: the entry module unit and its block table. */
    private record Lowered(LoweredModuleUnit unit, StructuredBodyTable table,
                           ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries) {

        SemanticOp op(OpId id) {
            for (SemanticOp op : unit.ops()) {
                if (op.opId().equals(id)) {
                    return op;
                }
            }
            return null;
        }

        /** The ordered ops of one block. */
        List<SemanticOp> blockOps(BlockId block) {
            List<OpId> members = table.blockOps().get(block);
            List<SemanticOp> ops = new ArrayList<>();
            if (members != null) {
                for (OpId id : members) {
                    SemanticOp op = op(id);
                    if (op != null) {
                        ops.add(op);
                    }
                }
            }
            return ops;
        }

        /**
         * The body blocks of the module's declared function bodies in
         * source order (the {@code CLOSURE_NEW} ops appear in declaration
         * order and their identity keys the {@code LoweredBody} binding).
         */
        List<BlockId> declaredBodyBlocks() {
            List<BlockId> blocks = new ArrayList<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.CLOSURE_NEW) {
                    continue;
                }
                FunctionExecutionBinding binding = unit.functionBindings()
                    .get(new FunctionAllocationIdentity(((ValueId) op.result()).id()));
                if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                    blocks.add(body.blockId());
                }
            }
            return blocks;
        }

        /** The RETURN ops the implicit-return arm produced (SYNTHETIC origin). */
        List<SemanticOp> implicitReturns() {
            List<SemanticOp> found = new ArrayList<>();
            for (SemanticOp op : unit.ops()) {
                if (op.kind() == SemanticOpKind.RETURN
                        && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                    found.add(op);
                }
            }
            return found;
        }
    }

    private static Lowered lowerProbe(String name, String source) throws Exception {
        return lowerProbe(name, source, productionInvocation());
    }

    /**
     * One probe lowered through the named invocation. The negative
     * controls drive the harness invocation, whose implicit-null-return
     * tolerance keeps the closed analysis's {@code OPEN} marking
     * observable ({@link deal.checker.NullReturnFallOff} is the public
     * build's gate); the public build's rejection of the same program is
     * pinned in the negative section.
     */
    private static Lowered lowerProbe(String name, String source,
            CompilerInvocation invocation) throws Exception {
        Path root = Files.createTempDirectory("composite-terminator-");
        try {
            write(root, "src/app.deal", source);
            return lowerProjectRoot(root, "src/app.deal", invocation);
        } finally {
            deleteRecursively(root);
        }
    }

    private static Lowered lowerProjectRoot(Path root, String entryRelative)
            throws Exception {
        return lowerProjectRoot(root, entryRelative, productionInvocation());
    }

    /**
     * The compile plus the one project lowering of one project root whose
     * entry module is {@code src/app.deal} through the named invocation.
     */
    private static Lowered lowerProjectRoot(Path root, String entryRelative,
            CompilerInvocation invocation) throws Exception {
        CompilationOrchestrator orchestrator = compile(root.resolve("src"),
            root.resolve(entryRelative), Backend.LUAJIT, invocation);
        check(orchestrator.diagnostics().isEmpty(),
            "the project compiles through the release-owned " + invocation.purpose()
                + " invocation: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || manifests == null || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            fail("the project's checked facts are complete (built/manifests/surface)");
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            invocation, built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(), Map.of(),
            Map.of(), BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW), Set.of());
        check(!result.hasErrors() && result.project() != null,
            "the one project lowering reports zero diagnostics: "
                + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId entry = result.project().entryModule();
        return new Lowered(result.project().modules().get(entry),
            result.tables().get(entry), result.project(), result.tables(),
            result.registries());
    }

    /** One probe program plus its pinned entry shim. */
    private static String probeProgram(String probeSource) {
        return probeSource + """

            export function main(): null {
              return null;
            }
            """;
    }

    /** The body-block op kinds of one block (the unit's produced table). */
    private static List<String> kindsOf(Lowered lowered, BlockId block) {
        List<String> kinds = new ArrayList<>();
        for (SemanticOp op : lowered.blockOps(block)) {
            kinds.add(op.kind().name());
        }
        return kinds;
    }

    /**
     * The composite-marking positives: a composite whose every normal-exit
     * path is terminated marks its enclosing body non-{@code OPEN}, so the
     * null-returning body carries no implicit trailing synthetic null
     * return. The asserted marker is the last op of the probe's body
     * block.
     */
    static void testCompositeMarkingPositives() throws Exception {
        System.out.println("-- the composite positives: both-branch returns, the "
            + "else-if chain, try/catch both returning, the literal-true loops --");
        record Positive(String name, String source, String marker) {
        }
        List<Positive> positives = List.of(
            new Positive("if/else both returning", probeProgram("""
                export function probe(c: boolean): null {
                  if (c) { return null; } else { return null; }
                }
                """), "BRANCH"),
            new Positive("else-if chain", probeProgram("""
                export function probe(a: boolean, b: boolean): null {
                  if (a) { return null; } else if (b) { return null; } else { return null; }
                }
                """), "BRANCH"),
            new Positive("try/catch both returning", probeProgram("""
                export function probe(): null {
                  try { return null; } catch (e) { return null; }
                }
                """), "TRY_CATCH"),
            new Positive("while (true) returning", probeProgram("""
                export function probe(): null {
                  while (true) { return null; }
                }
                """), "LOOP"),
            new Positive("for (; true; …) returning", probeProgram("""
                export function probe(): null {
                  for (; true; ) { return null; }
                }
                """), "LOOP"),
            new Positive("test-less for (;;) returning", probeProgram("""
                export function probe(): null {
                  for (;;) { return null; }
                }
                """), "LOOP"),
            new Positive("for-let literal-true returning", probeProgram("""
                export function probe(): null {
                  for (let i: int = 0; true; i = i + 1) { return null; }
                }
                """), "LOOP"),
            new Positive("test-less for-let returning", probeProgram("""
                export function probe(): null {
                  for (let i: int = 0; ; i = i + 1) { return null; }
                }
                """), "LOOP"),
            new Positive("if/else both returning inside a literal-true loop",
                probeProgram("""
                    export function probe(c: boolean): null {
                      while (true) {
                        if (c) { return null; } else { return null; }
                      }
                    }
                    """), "LOOP"),
            new Positive("try/catch both returning inside a literal-true loop",
                probeProgram("""
                    export function probe(): null {
                      while (true) {
                        try { return null; } catch (e) { return null; }
                      }
                    }
                    """), "LOOP"));
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < positives.size(); i++) {
            String program = positives.get(i).source();
            source.append(program.substring(0, program.indexOf("export function main"))
                .replace("function probe(", "function positiveProbe" + i + "("));
        }
        Lowered lowered = lowerProbe("composite positive battery",
            probeProgram(source.toString()));
        if (lowered == null) {
            return;
        }
        List<BlockId> bodies = lowered.declaredBodyBlocks();
        checkEq(positives.size() + 1, bodies.size(),
            "the unit carries every positive probe and the entry main body");
        checkEq((long) bodies.size(), bodies.stream().distinct().count(),
            "the positive probes have distinct body blocks");
        check(lowered.implicitReturns().isEmpty(),
            "the terminated positive bodies carry no implicit trailing null return");
        if (bodies.size() != positives.size() + 1) {
            return;
        }
        for (int i = 0; i < positives.size(); i++) {
            Positive positive = positives.get(i);
            BlockId probeBody = bodies.get(i);
            List<SemanticOp> ops = lowered.blockOps(probeBody);
            check(!ops.isEmpty(), positive.name() + ": the probe body block is "
                + "non-empty");
            check(!ops.isEmpty()
                    && ops.get(ops.size() - 1).kind().name()
                        .equals(positive.marker()),
                positive.name() + ": the probe body block ends with the composite "
                    + positive.marker() + " (op kinds " + kindsOf(lowered, probeBody)
                    + ")");
            for (SemanticOp op : ops) {
                if (op.kind() == SemanticOpKind.RETURN) {
                    fail(positive.name() + ": the body block carries no RETURN op "
                        + "(a terminated body); found " + op.opId());
                    break;
                }
            }
            // The composite's own sub-blocks cannot complete normally: every
            // normal-exit path of the marker's payload reaches a transfer (an
            // else-if chain composes through its nested BRANCH).
            for (BlockId child : compositeChildBlocks(lowered, ops.get(ops.size() - 1))) {
                check(cannotCompleteNormally(lowered, child), positive.name()
                    + ": the composite sub-block " + child + " cannot complete "
                    + "normally (op kinds " + kindsOf(lowered, child) + ")");
            }
        }
    }

    static void testNullBothReturnTailMonotonicity() throws Exception {
        Lowered lowered = lowerProbe("null-both-return", NULL_BOTH_RETURN_TAIL_SOURCE);
        if (lowered == null) {
            return;
        }
        List<BlockId> bodies = lowered.declaredBodyBlocks();
        checkEq(2, bodies.size(), "null-both-return: the probe and main bodies resolve");
        if (bodies.size() != 2) {
            return;
        }
        BlockId probeBody = bodies.get(0);
        List<SemanticOp> bodyOps = lowered.blockOps(probeBody);
        SemanticOp composite = bodyOps.stream()
            .filter(op -> op.kind() == SemanticOpKind.BRANCH).findFirst().orElse(null);
        check(composite != null, "null-both-return: the returning BRANCH is represented");
        if (composite == null) {
            return;
        }
        check(bodyOps.stream().anyMatch(op -> op.origin().span().startLine() == 11
                && bodyOps.indexOf(op) > bodyOps.indexOf(composite)),
            "null-both-return: the body tail follows the returning composite");
        check(bodyOps.stream().noneMatch(op -> op.kind() == SemanticOpKind.RETURN
                && op.origin().kind() == SourceOriginKind.SYNTHETIC),
            "null-both-return: represented body tails do not fabricate a root RETURN");
        check(lowered.implicitReturns().isEmpty(),
            "null-both-return: no synthetic RETURN is fabricated in the unit");
        List<BlockId> children = compositeChildBlocks(lowered, composite);
        checkEq(2, children.size(), "null-both-return: both returning children resolve");
        for (int i = 0; i < children.size(); i++) {
            List<SemanticOp> ops = lowered.blockOps(children.get(i));
            SemanticOp terminator = ops.stream()
                .filter(op -> op.kind() == SemanticOpKind.RETURN).findFirst().orElse(null);
            int markerLine = i == 0 ? 4 : 8;
            check(terminator != null && ops.stream().anyMatch(op ->
                    op.origin().span().startLine() == markerLine
                        && ops.indexOf(op) > ops.indexOf(terminator)),
                "null-both-return: child " + i + " retains its tail after RETURN");
        }
    }

    /**
     * The composite-marking negatives: the body can complete normally, so
     * the pinned implicit null return still lowers as the body block's
     * trailing statement. The controls drive the harness invocation — the
     * public build rejects the same open bodies with the spec's
     * no-fall-off rule (one E5002), which each control also pins.
     */
    static void testCompositeMarkingNegatives() throws Exception {
        System.out.println("-- the negative controls: the implicit null return is "
            + "kept --");
        record Negative(String name, String source) {
        }
        List<Negative> negatives = List.of(
            new Negative("if without else", probeProgram("""
                export function probe(c: boolean): null {
                  if (c) { return null; }
                }
                """)),
            new Negative("if/else with one non-returning branch", probeProgram("""
                export function probe(c: boolean): null {
                  if (c) { return null; } else { }
                }
                """)),
            new Negative("try with a non-returning catch", probeProgram("""
                export function probe(): null {
                  try { return null; } catch (e) { }
                }
                """)),
            new Negative("literal-true loop with a break path", probeProgram("""
                export function probe(c: boolean): null {
                  while (true) {
                    if (c) { break; } else { return null; }
                  }
                }
                """)),
            new Negative("literal-true loop with a continue path", probeProgram("""
                export function probe(c: boolean): null {
                  while (true) {
                    if (c) { continue; } else { return null; }
                  }
                }
                """)),
            new Negative("literal-true loop whose body only transfers",
                probeProgram("""
                    export function probe(c: boolean): null {
                      while (true) {
                        if (c) { break; } else { continue; }
                      }
                    }
                    """)),
            new Negative("non-literal-true loop condition", probeProgram("""
                export function probe(limit: int): null {
                  for (let i: int = 0; i < limit; i = i + 1) { return null; }
                }
                """)),
            new Negative("while (false)", probeProgram("""
                export function probe(): null {
                  while (false) { return null; }
                }
                """)),
            new Negative("TRANSFER try/catch inside a literal-true loop",
                probeProgram("""
                    export function probe(): null {
                      while (true) {
                        try {
                          break;
                        } catch (e) {
                          return null;
                        }
                      }
                    }
                    """)));
        for (Negative negative : negatives) {
            // The harness invocation keeps the implicit-null-return
            // tolerance the closed analysis exercises (the public build's
            // fall-off gate is NullReturnFallOff); the same program's
            // public rejection is pinned below.
            Lowered lowered = lowerProbe(negative.name(), negative.source(),
                ConformanceHarnessMetadata.invocation(SemanticProfile.DEAL_V1_2_INT32));
            if (lowered == null) {
                continue;
            }
            List<SemanticOp> implicit = lowered.implicitReturns();
            checkEq(1, implicit.size(), negative.name()
                + ": the open body keeps the pinned implicit null return");
            List<BlockId> bodies = lowered.declaredBodyBlocks();
            if (bodies.isEmpty()) {
                continue;
            }
            BlockId probeBody = bodies.get(0);
            List<SemanticOp> ops = lowered.blockOps(probeBody);
            check(!ops.isEmpty() && ops.get(ops.size() - 1).kind()
                    == SemanticOpKind.RETURN,
                negative.name() + ": the implicit return is the body block's "
                    + "trailing statement (op kinds " + kindsOf(lowered, probeBody)
                    + ")");
            if (!implicit.isEmpty()) {
                check(ops.contains(implicit.get(0)), negative.name()
                    + ": the implicit RETURN is a member of the probe body block");
            }
            // The public compile surface rejects the same open body with
            // exactly one E5002 (the spec's no-fall-off rule; ISSUE-0717
            // defect snippets 25/26).
            checkPublicFallOff(negative.name(), negative.source());
        }
    }

    /**
     * The partial-transfer composition: an open-branch composite's
     * reachable {@code break}/{@code continue} path is preserved while the
     * statements of one block compose, so a following {@code return}/{@code
     * throw} classifies the literal-true loop body {@code TRANSFER} instead
     * of {@code RETURN_OR_THROW}. The loop can then exit normally through
     * the transfer, does not terminate the enclosing body, and the pinned
     * implicit null return is retained as the body block's trailing
     * statement — for every literal-true loop form and every partial shape.
     * The reachable break-exit shapes also pin the public compile surface's
     * one-E5002 rejection (the spec's no-fall-off rule; ISSUE-0717 defect
     * snippets 25/26); the retained harness profile is the one that keeps
     * the implicit null return observable.
     */
    static void testPartialTransferKeepsImplicitReturn() throws Exception {
        System.out.println("-- the partial transfers: an open-branch composite's "
            + "break/continue path keeps the literal-true loop's implicit null "
            + "return --");
        record LoopForm(String name, String header) {
        }
        List<LoopForm> forms = List.of(
            new LoopForm("while (true)", "while (true) {"),
            new LoopForm("for (; true; )", "for (; true; ) {"),
            new LoopForm("for (;;)", "for (;;) {"),
            new LoopForm("for-let", "for (let i: int = 0; true; i = i + 1) {"),
            new LoopForm("test-less for-let",
                "for (let i: int = 0; ; i = i + 1) {"));
        record PartialShape(String name, String body, boolean publicFallOff) {
        }
        List<PartialShape> shapes = List.of(
            new PartialShape("if without else then return", """
                if (c) { break; }
                return null;""", true),
            new PartialShape("if without else then throw", """
                if (c) { break; }
                throw { code: "PARTIAL", message: "partial" }""", true),
            new PartialShape("if/else with one open branch then return", """
                if (c) { break; } else { }
                return null;""", true),
            new PartialShape("try/catch with one open branch then return", """
                try { if (c) { break; } } catch (e) { }
                return null;""", true),
            new PartialShape("if/else with a transferring else then return", """
                if (c) { } else { break; }
                return null;""", true),
            new PartialShape("try/catch with a transferring catch then return", """
                try { } catch (e) { if (c) { break; } }
                return null;""", true),
            new PartialShape("if without else with continue then return", """
                if (c) { continue; }
                return null;""", false),
            new PartialShape("try/catch with continue then return", """
                try { if (c) { continue; } } catch (e) { }
                return null;""", false),
            new PartialShape("if without else with continue then throw", """
                if (c) { continue; }
                throw { code: "PARTIAL", message: "partial" }""", false),
            new PartialShape("try/catch with continue then throw", """
                try { if (c) { continue; } } catch (e) { }
                throw { code: "PARTIAL", message: "partial" }""", false));
        List<String> names = new ArrayList<>();
        StringBuilder source = new StringBuilder();
        int fallOffProbes = 0;
        for (LoopForm form : forms) {
            for (PartialShape shape : shapes) {
                source.append("export function probe").append(names.size())
                    .append("(c: boolean): null {\n")
                    .append("  ").append(form.header()).append('\n')
                    .append("    ").append(shape.body().replace("\n", "\n    "))
                    .append("\n  }\n}\n");
                names.add(form.name() + ", " + shape.name());
                if (shape.publicFallOff()) {
                    fallOffProbes++;
                }
            }
        }
        checkEq(50, names.size(), "the partial-transfer matrix carries all fifty probes");
        if (names.size() != 50) {
            return;
        }
        // The public build rejects every reachable break-exit body with one
        // E5002 (the spec's no-fall-off rule; ISSUE-0717 defect snippets
        // 25/26), so the batched public program is pinned once here; the
        // retained harness invocation — whose implicit-null-return tolerance
        // keeps the closed analysis's OPEN marking observable — drives the
        // batched lowering below.
        checkPublicFallOffCount("the partial-transfer matrix",
            probeProgram(source.toString()), fallOffProbes);
        Lowered lowered = lowerProbe("partial-transfer matrix",
            probeProgram(source.toString()),
            ConformanceHarnessMetadata.invocation(SemanticProfile.DEAL_V1_2_INT32));
        if (lowered == null) {
            return;
        }
        List<BlockId> bodies = lowered.declaredBodyBlocks();
        checkEq(51, bodies.size(), "the unit carries the fifty probe bodies in source "
            + "order and the entry main body");
        if (bodies.size() != 51) {
            return;
        }
        checkEq(51L, bodies.stream().distinct().count(),
            "the fifty probe bodies and main have distinct blocks");
        List<SemanticOp> allImplicit = lowered.implicitReturns();
        checkEq(50, allImplicit.size(), "only the fifty partial-transfer bodies keep "
            + "the pinned implicit null returns");
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            BlockId probeBody = bodies.get(i);
            List<SemanticOp> ops = lowered.blockOps(probeBody);
            List<SemanticOp> implicit = allImplicit.stream().filter(ops::contains).toList();
            checkEq(1, implicit.size(), name + ": the partial-transfer body "
                + "keeps the pinned implicit null return");
            check(!ops.isEmpty() && ops.get(ops.size() - 1).kind()
                    == SemanticOpKind.RETURN,
                name + ": the implicit return is the body block's trailing "
                    + "statement (op kinds " + kindsOf(lowered, probeBody) + ")");
            if (implicit.size() == 1) {
                check(ops.get(ops.size() - 1).equals(implicit.get(0)), name
                    + ": the synthetic implicit RETURN is the trailing member of "
                        + "the probe body block");
            }
        }
    }

    /**
     * The monotone partial-transfer composition: an unreachable tail behind
     * a terminator never flips a terminated body to {@code TRANSFER} (a
     * fabricated transfer path would wrongly keep a literal-true loop's
     * implicit return), and a nested body's partial transfer never reaches
     * an enclosing body's state (the bodies' blocks are disjoint — the
     * enclosing loop still terminates its body). The nested open body's
     * public one-E5002 rejection is pinned through the harness invocation
     * the open shape drives.
     */
    static void testPartialTransferMonotonicity() throws Exception {
        System.out.println("-- the unreachable tails and nested bodies never modify "
            + "the partial-transfer state --");
        record Terminated(String name, String source, int implicitReturns,
                          boolean publicFallOff) {
        }
        List<Terminated> terminated = List.of(
            new Terminated("return then break tail", probeProgram("""
                export function probe(c: boolean): null {
                  while (true) {
                    return null;
                    if (c) { break; }
                  }
                }
                """), 0, false),
            new Terminated("both-branch return then partial break tail",
                probeProgram("""
                    export function probe(c: boolean): null {
                      while (true) {
                        if (c) { return null; } else { return null; }
                        if (c) { break; }
                      }
                    }
                    """), 0, false),
            new Terminated("nested body's partial transfer", probeProgram("""
                export function probe(c: boolean): null {
                  while (true) {
                    function inner(d: boolean): null {
                      while (true) {
                        if (d) { break; }
                        return null;
                      }
                    }
                    return null;
                  }
                }
                """), 1, true));
        for (Terminated shape : terminated) {
            Lowered lowered = shape.publicFallOff()
                ? lowerProbe(shape.name(), shape.source(),
                    ConformanceHarnessMetadata.invocation(SemanticProfile.DEAL_V1_2_INT32))
                : lowerProbe(shape.name(), shape.source());
            if (lowered == null) {
                continue;
            }
            if (shape.publicFallOff()) {
                // The nested body's reachable break-exit falls off and is
                // rejected by the public surface; the enclosing body still
                // definitely returns and the retained harness profile keeps
                // the nested body's implicit null return observable.
                checkPublicFallOff(shape.name(), shape.source());
            }
            checkEq(shape.implicitReturns(), lowered.implicitReturns().size(),
                shape.name() + ": only the genuinely open bodies keep the pinned "
                    + "implicit null return");
            List<BlockId> bodies = lowered.declaredBodyBlocks();
            if (bodies.isEmpty()) {
                continue;
            }
            List<SemanticOp> probeOps = lowered.blockOps(bodies.get(0));
            check(!probeOps.isEmpty() && probeOps.get(probeOps.size() - 1).kind()
                    == SemanticOpKind.LOOP,
                shape.name() + ": the probe body block ends with its literal-true "
                    + "LOOP (op kinds " + kindsOf(lowered, bodies.get(0)) + ")");
            check(probeOps.stream().noneMatch(op -> op.kind() == SemanticOpKind.RETURN),
                shape.name() + ": the terminated probe body carries no RETURN op "
                    + "(op kinds " + kindsOf(lowered, bodies.get(0)) + ")");
        }
    }

    /**
     * The nested-body disjointness: a literal-true loop inside a nested
     * declared body marks the nested body, never the enclosing loop's body
     * — the enclosing body stays {@code OPEN} and keeps its implicit null
     * return, while the nested composite-terminated body carries none. The
     * enclosed transfer-less loop is not a terminating composite for the
     * enclosing body, so the probe drives the harness invocation (its
     * public one-E5002 rejection is pinned) like the negative controls.
     */
    static void testNestedBodyDisjointness() throws Exception {
        System.out.println("-- a nested body's transfer never contributes to an "
            + "enclosing loop's state --");
        String nestedProbe = probeProgram("""
            export function probe(): null {
              while (true) {
                function inner(): null {
                  while (true) { return null; }
                }
              }
            }
            """);
        Lowered lowered = lowerProbe("nested declared body", nestedProbe,
            ConformanceHarnessMetadata.invocation(SemanticProfile.DEAL_V1_2_INT32));
        if (lowered == null) {
            return;
        }
        List<BlockId> bodies = lowered.declaredBodyBlocks();
        checkEq(3, bodies.size(), "the unit carries the probe, the nested "
            + "declaration, and the entry main body");
        if (bodies.size() != 3) {
            return;
        }
        List<SemanticOp> probeOps = lowered.blockOps(bodies.get(0));
        checkEq(1, lowered.implicitReturns().size(), "the enclosing body stays OPEN "
            + "(the nested literal-true loop's return never contributes to the "
            + "outer loop's state)");
        check(!probeOps.isEmpty() && probeOps.get(probeOps.size() - 1).kind()
                == SemanticOpKind.RETURN,
            "the enclosing body block ends with its implicit null return (op "
                + "kinds " + kindsOf(lowered, bodies.get(0)) + ")");
        checkPublicFallOff("nested declared body", nestedProbe);
        List<SemanticOp> nestedOps = lowered.blockOps(bodies.get(1));
        check(!nestedOps.isEmpty() && nestedOps.get(nestedOps.size() - 1).kind()
                == SemanticOpKind.LOOP,
            "the nested declared body's block ends with its literal-true LOOP "
                + "(op kinds " + kindsOf(lowered, bodies.get(1)) + ")");
        for (SemanticOp op : nestedOps) {
            if (op.kind() == SemanticOpKind.RETURN) {
                fail("the nested terminated body carries no implicit return; found "
                    + op.opId());
                break;
            }
        }

        Lowered closure = lowerProbe("closure body", probeProgram("""
            export function probe(c: boolean): null {
              let f: () => null = function(): null {
                if (c) { return null; } else { return null; }
              };
              return null;
            }
            """));
        if (closure == null) {
            return;
        }
        List<BlockId> closureBodies = closure.declaredBodyBlocks();
        checkEq(3, closureBodies.size(), "the closure unit carries the probe, the "
            + "closure body, and the entry main body");
        if (closureBodies.size() != 3) {
            return;
        }
        List<SemanticOp> closureBodyOps = closure.blockOps(closureBodies.get(1));
        check(!closureBodyOps.isEmpty() && closureBodyOps.get(
                closureBodyOps.size() - 1).kind() == SemanticOpKind.BRANCH,
            "the closure body's block ends with its both-branch BRANCH (op kinds "
                + kindsOf(closure, closureBodies.get(1)) + ")");
        check(closure.implicitReturns().isEmpty(), "the composite-terminated "
            + "closure body carries no implicit return");
    }

    /**
     * The nested mutual-reference group (the B4 partition the lowering
     * applies, ISSUE-0731) keeps the landed implicit-null-return contract
     * on the public build, while a standalone nested declaration, a
     * module-level mutual group, and a nested function expression stay
     * rejected (the spec's no-fall-off rule; ISSUE-0717 defect snippets
     * 25/26).
     */
    static void testNestedGroupFallOffExemption() throws Exception {
        System.out.println("-- the nested mutual-reference group keeps its "
            + "implicit null returns on the public build --");
        String group = """
            export function main(): null {
              function f(n: int): null {
                if (n > 0) { g(n - 1); }
              }
              function g(n: int): null {
                if (n > 1) { f(n - 1); }
              }
              f(4);
              return null;
            }
            """;
        List<CompilerDiagnostic> groupDiagnostics = publicBuildDiagnostics(group);
        check(groupDiagnostics.stream().noneMatch(
                diagnostic -> "E5002".equals(diagnostic.code())),
            "the nested mutual-reference group's implicit null returns compile "
                + "on the public build: " + groupDiagnostics);

        String standalone = """
            export function main(): null {
              while (true) {
                function inner(d: boolean): null {
                  while (true) {
                    if (d) { break; }
                    return null;
                  }
                }
                return null;
              }
            }
            """;
        checkPublicFallOff("the standalone nested declaration", standalone);

        String moduleLevel = """
            function f(n: int): null {
              if (n > 0) { g(n - 1); }
            }
            function g(n: int): null {
              if (n > 1) { f(n - 1); }
            }
            export function main(): null { return null; }
            """;
        checkPublicFallOffCount("the module-level mutual group", moduleLevel, 2);

        String expression = """
            export function main(): null {
              let f: () => null = function(): null {};
              f();
              return null;
            }
            """;
        checkPublicFallOff("the nested function expression", expression);
    }

    static void testUnterminatedNonNullStillFailsClosed() throws Exception {
        System.out.println("-- a genuinely unterminated non-null body still fails "
            + "closed --");
        record FailClosed(String name, String source) {
        }
        List<FailClosed> cases = List.of(
            new FailClosed("if without else", """
                export function probe(c: boolean): int {
                  if (c) { return 1; }
                }
                """),
            new FailClosed("literal-true loop with a break path", """
                export function probe(c: boolean): int {
                  while (true) {
                    if (c) { break; } else { return 1; }
                  }
                }
                """));
        for (FailClosed failClosed : cases) {
            Session session = lowerSession(failClosed.name(), failClosed.source());
            if (session == null) {
                continue;
            }
            check(session.result().hasErrors() && session.result().unit() == null,
                failClosed.name() + ": the lowering fails closed (no unit)");
            boolean arm = false;
            for (CompilerDiagnostic diagnostic : session.result().diagnostics()) {
                if (diagnostic.message().contains("body is not terminated")) {
                    arm = true;
                }
            }
            check(arm, failClosed.name() + ": the landed arm is the cause: "
                + session.result().diagnostics());
        }
    }

    /**
     * The literal-true loop on a non-null body at the unit level: since
     * ISSUE-0717 (defect snippets 28/29) the checker's definite-return
     * analysis admits the shape — "loops are not assumed to return unless
     * they contain an unconditional return before any possible break" —
     * so the project entry reaches the loop rule too; the unit-level drive
     * keeps exercising the closed analysis directly: the body is
     * terminated, carries no implicit return, and produces no diagnostic.
     */
    static void testLiteralTrueLoopOnNonNullBodyAtUnitLevel() throws Exception {
        System.out.println("-- the literal-true loop on a non-null body at the unit "
            + "level (the checker admits the unconditional-return shape since "
            + "ISSUE-0717) --");
        record NonNullLoop(String name, String source) {
        }
        List<NonNullLoop> cases = List.of(
            new NonNullLoop("while (true)", """
                export function probe(): int {
                  while (true) { return 1; }
                }
                """),
            new NonNullLoop("test-less for-let", """
                export function probe(): int {
                  for (let i: int = 0; ; i = i + 1) { return 1; }
                }
                """),
            new NonNullLoop("for (;;)", """
                export function probe(): int {
                  for (;;) { return 1; }
                }
                """));
        for (NonNullLoop nonNull : cases) {
            Session session = lowerSession(nonNull.name(), nonNull.source());
            if (session == null) {
                continue;
            }
            boolean checkerRejects = false;
            for (CompilerDiagnostic diagnostic : session.checks().diagnostics()) {
                if ("E5002".equals(diagnostic.code())) {
                    checkerRejects = true;
                }
            }
            check(!checkerRejects, nonNull.name() + ": the checker's definite-return "
                + "analysis admits the unconditional return before any possible "
                + "break (ISSUE-0717) — the diagnostics are "
                + session.checks().diagnostics());
            check(!session.result().hasErrors() && session.result().unit() != null,
                nonNull.name() + ": the closed analysis finds the body terminated "
                    + "(no fail-closed arm): " + session.result().diagnostics());
            if (session.result().unit() == null) {
                continue;
            }
            Lowered lowered = new Lowered(session.result().unit(),
                session.result().table(), null, Map.of(), Map.of());
            List<BlockId> bodies = lowered.declaredBodyBlocks();
            checkEq(1, bodies.size(), nonNull.name()
                + ": the unit carries the probe body");
            if (bodies.isEmpty()) {
                continue;
            }
            check(lowered.implicitReturns().isEmpty(), nonNull.name()
                + ": the terminated non-null body carries no implicit null return");
            List<SemanticOp> ops = lowered.blockOps(bodies.get(0));
            check(!ops.isEmpty() && ops.get(ops.size() - 1).kind()
                    == SemanticOpKind.LOOP,
                nonNull.name() + ": the body block ends with its literal-true LOOP "
                    + "(op kinds " + kindsOf(lowered, bodies.get(0)) + ")");
        }
    }

    /**
     * The omitted-test for-let loop on the two release-owned production
     * lanes: the all-return body (the shape the public build admits)
     * compiles with zero diagnostics, stages exactly one artifact per
     * lane, and executes through the real toolchains with the loop body's
     * console effect printed exactly once. The partial break/continue
     * bodies are fall-off shapes the public build rejects (one E5002 per
     * open body, the spec's no-fall-off rule), so their retained implicit
     * null return is the retained-profile battery above.
     */
    static void testTestlessForLetProductionArtifacts() throws Exception {
        System.out.println("-- the omitted-test for-let loop through the two "
            + "release-owned production lanes (the all-return shape) --");
        String source = """
            import * as console from "std/console";

            export function terminateForLetTestless(): null {
              for (let i: int = 0; ; i = i + 1) {
                console.log("testless-for-let-terminate");
                return null;
              }
            }

            export function main(): null {
              terminateForLetTestless();
              return null;
            }
            """;
        Path root = Files.createTempDirectory("testless-for-let-production-");
        try {
            write(root, "src/main.deal", source);
            Path entry = root.resolve("src/main.deal");

            // LuaJIT: the production compile stages the entry chunk; the
            // real luajit execution prints the terminated body's effect
            // exactly once. The artifact is read and run before the JVM
            // lane's whole-compile publication replaces the output set.
            CompilationOrchestrator lua = productionCompile(root.resolve("src"),
                entry, Backend.LUAJIT);
            check(lua.diagnostics().isEmpty(), "LuaJIT: the omitted-test for-let "
                + "compiles with zero diagnostics: " + lua.diagnostics());
            check(lua.semanticEmissionCount() == 1, "LuaJIT: exactly one project "
                + "artifact stages: semantic=" + lua.semanticEmissionCount());
            Path out = root.resolve("out");
            Path chunk = out.resolve("main.lua");
            check(Files.isRegularFile(chunk), "LuaJIT: the one project artifact "
                + "stages at the entry path");
            if (Files.isRegularFile(chunk)) {
                ProcessOutcome run = runProcess(out, Map.of(), "luajit", "main.lua");
                checkEq(0, run.exitCode(), "LuaJIT: the staged chunk executes: "
                    + run.stderr());
                checkEq("testless-for-let-terminate\n", run.stdout(), "LuaJIT: the "
                    + "terminated loop body executes exactly once");
                checkEq("", run.stderr(), "LuaJIT: the staged chunk raises nothing");
            }

            // JVM: the production compile stages the entry class, which
            // compiles under javac --release 25 -proc:none and executes the
            // same terminated body once under the real java.
            CompilationOrchestrator jvm = productionCompile(root.resolve("src"),
                entry, Backend.JVM);
            check(jvm.diagnostics().isEmpty(), "JVM: the omitted-test for-let "
                + "compiles with zero diagnostics: " + jvm.diagnostics());
            check(jvm.semanticEmissionCount() == 1, "JVM: exactly one project "
                + "artifact stages: semantic=" + jvm.semanticEmissionCount());
            String className = JvmBackend.classNameFor("main");
            Path artifact = out.resolve(className + ".java");
            check(Files.isRegularFile(artifact), "JVM: the one project artifact is "
                + "the entry class " + className + ".java");
            if (Files.isRegularFile(artifact)) {
                Path classes = root.resolve("classes");
                Files.createDirectories(classes);
                ProcessOutcome javac = runProcess(out, Map.of(), "javac", "--release",
                    "25", "-proc:none", "-cp", absoluteClasspath(), "-d",
                    classes.toString(), artifact.toAbsolutePath().toString());
                checkEq(0, javac.exitCode(), "JVM: the staged artifact compiles under "
                    + "javac --release 25 -proc:none: " + javac.stderr());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(out, Map.of(), "java", "-cp",
                        absoluteClasspath() + File.pathSeparator + classes, className);
                    checkEq(0, run.exitCode(), "JVM: the staged artifact executes: "
                        + run.stderr());
                    checkEq("testless-for-let-terminate\n", run.stdout(), "JVM: the "
                        + "terminated loop body executes exactly once");
                    checkEq("", run.stderr(), "JVM: the staged artifact raises "
                        + "nothing");
                }
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /** One session-level lowering result (the full-program E7 session). */
    private record Session(SemanticLowerer.LoweringResult result, CheckResult checks) {
    }

    /**
     * Lowers one source text through the registered full-program E7
     * lowering session (the session the project entry drives per module);
     * a program the checker refuses still lowers (the session consumes the
     * checked facts, and the E5002 flow rejection is the checker's).
     */
    private static Session lowerSession(String name, String source) throws Exception {
        final String sourceId = "probe.deal";
        final ModuleId moduleId = new ModuleId("app");
        LexResult lex = new Lexer(source, sourceId).tokenize();
        check(lex.diagnostics().isEmpty(), name + ": the probe lexes: "
            + lex.diagnostics());
        ParseResult parse = new Parser(lex.tokens(), sourceId).parse();
        check(parse.diagnostics().isEmpty(), name + ": the probe parses: "
            + parse.diagnostics());
        if (!parse.diagnostics().isEmpty()) {
            return null;
        }
        NameResolver resolver = new NameResolver(sourceId, new ModuleResolver() {
            @Override
            public Map<String, Type> resolveModule(String modulePath,
                    String importingModule, Set<String> modulesInProgress)
                    throws ModuleNotFoundException {
                var exports = deal.module.StdlibModuleResolver
                    .stdlibExports(Path.of("std").toAbsolutePath().toString());
                if (!exports.containsKey(modulePath)) {
                    throw new ModuleNotFoundException("Module not found: "
                        + modulePath);
                }
                return exports.get(modulePath);
            }

            @Override
            public Symbol.ClassSymbol resolveClassSymbol(String className,
                    String modulePath, String importingModule) {
                return null;
            }
        });
        SymbolTable symbols = resolver.resolve(parse.program());
        check(resolver.diagnostics().isEmpty(), name + ": the probe resolves: "
            + resolver.diagnostics());
        if (!resolver.diagnostics().isEmpty()) {
            return null;
        }
        CheckResult checks = TypeChecker.check(sourceId, symbols, resolver,
            parse.program());
        CheckedProjectBuildResult built = CheckedProjectBuilder.build(
            productionInvocation(), moduleId,
            List.of(new ModuleFact(sourceId, moduleId, false, false,
                parse.program(), Map.of(), symbols, checks, List.of())));
        check(built != null && !built.hasErrors() && built.input() != null
                && built.index() != null,
            name + ": the checked project builds: "
                + (built == null ? "null" : built.diagnostics()));
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null) {
            return null;
        }
        RequirementManifestResult manifests = LoweringSupport.computeManifests(
            productionInvocation(), built.input(), built.index());
        check(manifests != null && !manifests.hasErrors()
                && manifests.manifests() != null
                && manifests.manifests().size() == 1,
            name + ": the requirement manifest computes: "
                + (manifests == null ? "null" : manifests.diagnostics()));
        if (manifests == null || manifests.hasErrors()
                || manifests.manifests() == null
                || manifests.manifests().size() != 1) {
            return null;
        }
        CheckedModuleInput module = built.input().modules().get(0);
        SemanticLowerer.FullProgramE7Result e7 =
            SemanticLowerer.lowerModuleFullProgramE7(module,
                SemanticProfile.DEAL_V1_2_INT32,
                manifests.manifests().get(0).constructCoverage(),
                built.index().interfaceIndexDigest(),
                productionInvocation().capabilityRegistryHash(),
                SemanticIdAllocator.over(List.of(module.moduleId())),
                Map.of(), Map.of(), Set.of());
        return new Session(e7.lowering(), checks);
    }

    /** The composite's child blocks (the BRANCH branches / TRY_CATCH blocks). */
    private static List<BlockId> compositeChildBlocks(Lowered lowered,
            SemanticOp composite) {
        List<BlockId> blocks = new ArrayList<>();
        switch (composite.kind()) {
            case BRANCH -> {
                KindPayload.BranchPayload payload =
                    (KindPayload.BranchPayload) composite.payload();
                blocks.add(payload.selectedBlock());
                if (payload.alternateBlock() != null) {
                    blocks.add(payload.alternateBlock());
                }
            }
            case TRY_CATCH -> {
                KindPayload.TryCatchPayload payload =
                    (KindPayload.TryCatchPayload) composite.payload();
                blocks.add(payload.tryBlock());
                blocks.add(payload.catchBlock());
            }
            case LOOP -> {
                KindPayload.LoopPayload payload =
                    (KindPayload.LoopPayload) composite.payload();
                blocks.add(payload.bodyBlock());
            }
            default -> {
            }
        }
        return blocks;
    }

    /**
     * Whether one block's emitted ops cannot complete normally: its last op
     * is a transfer, or a composite whose every sub-block cannot (the
     * else-if chain composes through the nested BRANCH).
     */
    private static boolean cannotCompleteNormally(Lowered lowered, BlockId block) {
        List<SemanticOp> ops = lowered.blockOps(block);
        if (ops.isEmpty()) {
            return false;
        }
        SemanticOp last = ops.get(ops.size() - 1);
        if (isTransfer(last.kind())) {
            return true;
        }
        return switch (last.kind()) {
            case BRANCH -> {
                KindPayload.BranchPayload payload =
                    (KindPayload.BranchPayload) last.payload();
                yield payload.alternateBlock() != null
                    && cannotCompleteNormally(lowered, payload.selectedBlock())
                    && cannotCompleteNormally(lowered, payload.alternateBlock());
            }
            case TRY_CATCH -> {
                KindPayload.TryCatchPayload payload =
                    (KindPayload.TryCatchPayload) last.payload();
                yield cannotCompleteNormally(lowered, payload.tryBlock())
                    && cannotCompleteNormally(lowered, payload.catchBlock());
            }
            default -> false;
        };
    }

    private static boolean isTransfer(SemanticOpKind kind) {
        return kind == SemanticOpKind.RETURN || kind == SemanticOpKind.THROW
            || kind == SemanticOpKind.BREAK || kind == SemanticOpKind.CONTINUE;
    }

    // =========================================================================
    // 2. The four fixtures on the real toolchains
    // =========================================================================

    /** One executed process with both captured streams. */
    private record ProcessOutcome(int exitCode, String stdout, String stderr) {
    }

    static void testProductionArtifacts() throws Exception {
        System.out.println("-- the named residual fixtures (the four composite "
            + "fixtures, the residual acceptance subjects — direct recursion, "
            + "mutual recursion, nested-scope recursion, return-in-try — and the "
            + "async-await-statement discarded-await subject) through the "
            + "release-owned production invocation on LuaJIT and JVM --");
        List<Fixture> stagedFixtures = new ArrayList<>(FIXTURES);
        stagedFixtures.addAll(RESIDUAL_ACCEPTANCE_FIXTURES);
        stagedFixtures.add(ASYNC_AWAIT_STATEMENT_FIXTURE);
        for (Fixture fixture : stagedFixtures) {
            Path root = Files.createTempDirectory("composite-terminator-fixture-");
            try {
                write(root, "src/" + fixture.relativePath() + ".deal",
                    fixtureSource(fixture.relativePath()));
                Path entry = root.resolve("src").resolve(
                    fixture.relativePath() + ".deal");

                // LuaJIT: one project artifact, no retained emission, the
                // real luajit execution with the pinned sidecar transcript.
                CompilationOrchestrator lua = productionCompile(root.resolve("src"),
                    entry, Backend.LUAJIT);
                checkDiagnostics(fixture, "LuaJIT", lua);
                check(lua.semanticEmissionCount() == 1,
                    fixture.relativePath() + " (LuaJIT): the production arm stages "
                        + "exactly one project artifact: semantic="
                        + lua.semanticEmissionCount());
                Path out = root.resolve("out");
                Path artifact = out.resolve(fixture.relativePath() + ".lua");
                check(Files.exists(artifact), fixture.relativePath() + " (LuaJIT): "
                    + "the one project artifact stages at the entry path");
                check(artifactFiles(out).stream()
                        .noneMatch(name -> name.endsWith(".deal.map.json")),
                    fixture.relativePath() + " (LuaJIT): the artifact set carries no "
                        + "source-map sidecar");
                if (Files.exists(artifact)) {
                    write(out, "fixture_driver.lua", luaDriver(fixture));
                    ProcessOutcome run = runProcess(out, Map.of("DEAL_DEFER_MAIN", "1"),
                        "luajit", "fixture_driver.lua");
                    assertSidecar(fixture, "LuaJIT", run);
                }

                // JVM: one project class, javac --release 25 -proc:none, the
                // real java execution with the pinned sidecar transcript.
                CompilationOrchestrator jvm = productionCompile(root.resolve("src"),
                    entry, Backend.JVM);
                checkDiagnostics(fixture, "JVM", jvm);
                check(jvm.semanticEmissionCount() == 1,
                    fixture.relativePath() + " (JVM): the production arm stages "
                        + "exactly one project artifact: semantic="
                        + jvm.semanticEmissionCount());
                String className = JvmBackend.classNameFor(fixture.relativePath());
                Path jvmArtifact = out.resolve(className + ".java");
                check(Files.exists(jvmArtifact), fixture.relativePath() + " (JVM): "
                    + "the one project artifact is the entry class " + className
                    + ".java");
                if (Files.exists(jvmArtifact)) {
                    Path classes = root.resolve("classes");
                    Files.createDirectories(classes);
                    write(out, "FixtureProbe.java", jvmDriver(fixture, className,
                        Files.readString(jvmArtifact, StandardCharsets.UTF_8)));
                    ProcessOutcome javac = runProcess(out, Map.of(), "javac",
                        "--release", "25", "-proc:none", "-cp", absoluteClasspath(),
                        "-d", classes.toString(),
                        jvmArtifact.toAbsolutePath().toString(),
                        out.resolve("FixtureProbe.java").toAbsolutePath().toString());
                    checkEq(0, javac.exitCode(), fixture.relativePath() + " (JVM): "
                        + "the artifact compiles under javac --release 25 -proc:none: "
                        + javac.stderr());
                    if (javac.exitCode() == 0) {
                        ProcessOutcome run = runProcess(out, Map.of("DEAL_DEFER_MAIN",
                            "1"), "java", "-cp", absoluteClasspath()
                            + File.pathSeparator + classes, "FixtureProbe");
                        assertSidecar(fixture, "JVM", run);
                    }
                }
            } finally {
                deleteRecursively(root);
            }
        }
    }

    /** The zero-E6005 gate of one production compile. */
    private static void checkDiagnostics(Fixture fixture, String target,
            CompilationOrchestrator orchestrator) {
        StringBuilder diagnostics = new StringBuilder();
        for (deal.diagnostics.CompilerDiagnostic diagnostic
                : orchestrator.diagnostics()) {
            diagnostics.append(diagnostic.code()).append(' ')
                .append(diagnostic.message()).append('\n');
        }
        check(diagnostics.isEmpty(), fixture.relativePath() + " (" + target
            + "): zero E6005 CONSTRUCT_UNLOWERED/RETAINED_ABI_DEFERRED/"
            + "SHARED_EMITTER_COVERAGE, zero diagnostics: " + diagnostics);
    }

    /** The sidecar-authoritative transcript comparison of one execution. */
    private static void assertSidecar(Fixture fixture, String target,
            ProcessOutcome run) throws Exception {
        String sidecar = Files.readString(FIXTURE_ROOT.resolve(
            fixture.relativePath() + ".expect.json"), StandardCharsets.UTF_8);
        checkEq(sidecarExitCode(sidecar), run.exitCode(), fixture.relativePath()
            + " (" + target + "): the executed probe's exit code equals the sidecar pin");
        checkEq(sidecarTranscript(sidecar, "stdout"), run.stdout(),
            fixture.relativePath() + " (" + target
                + "): stdout equals the sidecar pin (the probe is silent)");
        checkEq(sidecarTranscript(sidecar, "stderr"), run.stderr(),
            fixture.relativePath() + " (" + target
                + "): stderr equals the sidecar pin (the probe raises nothing)");
    }

    /**
     * The LuaJIT fixture driver: the deferred module walk, the published
     * entry surface, and the fixture's exported probe invoked once through
     * the surface's callable with its result compared to the pinned probe
     * value. Nothing is printed: the pinned outcome is the fixture's own
     * guard and the empty sidecar transcript.
     */
    private static String luaDriver(Fixture fixture) {
        if (fixture.async()) {
            return luaAsyncDriver(fixture);
        }
        return """
            local surface = dofile("%s.lua")
            local ok, err = __dealMain()
            if not ok then error(err, 0) end
            %s
            local value = probe.f()
            if tostring(value) ~= %s then
              error("the probe result " .. tostring(value), 0)
            end
            """.formatted(fixture.relativePath(), luaSurfaceProbeCheck(fixture),
                quotedStringLiteral(fixture.pinnedRuntimeText()));
    }

    /**
     * The LuaJIT driver of an async export: the deferred module walk, the
     * published surface, then the artifact's own recorded async entry
     * driven to completion ({@code __asyncEntries} with the drive enabled) —
     * a discarded await inside the body executes exactly once and the
     * completion value is compared to the pinned probe value. Nothing is
     * printed: the pinned outcome is the fixture's own guard and the empty
     * sidecar transcript.
     */
    private static String luaAsyncDriver(Fixture fixture) {
        return """
            local surface = dofile("%s.lua")
            local ok, err = __dealMain()
            if not ok then error(err, 0) end
            %s
            local entry = nil
            for key, candidate in pairs(__asyncEntries) do
              if string.sub(key, -%d) == "#%s" then entry = candidate end
            end
            assert(entry ~= nil, "the artifact publishes the async entry")
            local okA, res = pcall(entry, "-", true)
            if not okA then error(res, 0) end
            if tostring(res) ~= %s then
              error("the async probe result " .. tostring(res), 0)
            end
            """.formatted(fixture.relativePath(), luaSurfaceProbeCheck(fixture),
                fixture.probe().length() + 1, fixture.probe(),
                quotedStringLiteral(fixture.pinnedRuntimeText()));
    }

    /** The published-surface probe publication check of one fixture driver. */
    private static String luaSurfaceProbeCheck(Fixture fixture) {
        return """
            local probe = surface["%s"]
            assert(type(probe) == "table" and probe.__kind == "function"
              and type(probe.f) == "function",
              "the entry surface publishes the fixture probe")
            """.formatted(fixture.probe());
    }

    /** One runtime text as a quoted Lua/JVM string literal. */
    private static String quotedStringLiteral(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /**
     * The JVM fixture driver: the module walk, the published entry
     * surface, and the fixture's exported probe invoked once with its
     * result compared to the pinned probe value; silent on success so the
     * sidecar transcript comparison is the pin.
     */
    private static String jvmDriver(Fixture fixture, String className,
            String jvmSource) {
        if (fixture.async()) {
            return jvmAsyncDriver(fixture, className, jvmSource);
        }
        return """
            public final class FixtureProbe {
              public static void main(String[] args) {
                %s.main(new String[0]);
                deal.codegen.jvm.JvmRuntime.Table surface =
                    %s.EXPORT_SURFACES.get("%s");
                if (surface == null) {
                  throw new IllegalStateException("no entry surface");
                }
                Object probe = surface.read("%s");
                if (!(probe instanceof deal.codegen.jvm.JvmRuntime.FunctionValue fn)) {
                  throw new IllegalStateException(
                      "the entry surface publishes the fixture probe");
                }
                Object value = fn.fn.invoke(new Object[0]);
                if (!String.valueOf(value).equals(%s)) {
                  throw new IllegalStateException("the probe result " + value);
                }
              }
            }
            """.formatted(className, className, jvmModuleKey(fixture),
                fixture.probe(), quotedStringLiteral(fixture.pinnedRuntimeText()));
    }

    /**
     * The JVM driver of an async export: the module walk, then the
     * artifact's own driven async entry ({@code ae<id>}, resolved from the
     * staged source), whose completion value must equal the pinned probe
     * value. The drive executes the discarded-await statement and the
     * fixture's guards to completion; nothing is printed.
     */
    private static String jvmAsyncDriver(Fixture fixture, String className,
            String jvmSource) {
        String entry = asyncEntryMethod(jvmSource);
        return """
            public final class FixtureProbe {
              public static void main(String[] args) {
                %s.main(new String[0]);
                Object result = %s.%s("-", true, new Object[]{});
                if (!String.valueOf(result).equals(%s)) {
                  throw new IllegalStateException(
                      "the async probe result " + result);
                }
              }
            }
            """.formatted(className, className, entry,
                quotedStringLiteral(fixture.pinnedRuntimeText()));
    }

    /**
     * The single driven async-entry method of one staged project source
     * ({@code public static Object ae<id>(String, boolean, Object[])}).
     * These fixtures stage exactly one async export, so zero or several
     * entry methods is a fixture/driver defect, never a silent pick.
     */
    private static String asyncEntryMethod(String jvmSource) {
        Matcher matcher = Pattern.compile("public static Object (ae\\d+)"
            + "\\(String __parent, boolean __drive, Object\\[\\] __args\\)")
            .matcher(jvmSource);
        List<String> entries = new ArrayList<>();
        while (matcher.find()) {
            entries.add(matcher.group(1));
        }
        checkEq(1, entries.size(), "the staged JVM artifact publishes exactly one "
            + "driven async entry: " + entries);
        return entries.isEmpty() ? "aeMissing" : entries.get(0);
    }

    /** The internal module identity of one fixture's source location. */
    private static String jvmModuleKey(Fixture fixture) {
        return fixture.relativePath().replace('/', '.');
    }

    // =========================================================================
    // 3. The pinned probe values through the oracle and both artifacts
    // =========================================================================

    static void testDifferentialMatrix() throws Exception {
        System.out.println("-- the pinned probe values through the oracle and both "
            + "shared artifacts (the differential matrix) --");
        List<Fixture> differentialFixtures = new ArrayList<>(FIXTURES);
        differentialFixtures.add(ASYNC_AWAIT_STATEMENT_FIXTURE);
        driveDifferentialFixtures(differentialFixtures);
    }

    static void testResidualAcceptanceParity() throws Exception {
        driveDifferentialFixtures(RESIDUAL_ACCEPTANCE_FIXTURES);
    }

    /**
     * The seventeen partial-transfer probes of the real-toolchain matrix:
     * the seven break/return shapes (the five literal-true loop forms —
     * while, {@code for (; true; )}, test-less {@code for (;;)}, for-let,
     * test-less for-let — plus the open-branch {@code if}/{@code else} and
     * {@code try}/{@code catch} shapes), five finite partial-continue
     * probes (one per loop form) whose continue path executes exactly once
     * before the probe's break exit, and five partial-transfer-then-throw
     * probes (one per loop form) whose throw executes on the non-break
     * path and is caught by the body's own catch (so the loop itself never
     * fails and the three consumers stay event-for-event). Every
     * partial-transfer probe keeps its pinned implicit null return; the
     * unit additionally carries the all-return omitted-test for-let probe
     * (no implicit return, real-toolchain execution). The driver entry
     * executes every break exit (the retained implicit return), every
     * continue (the finite loop's first iteration), and every throw (the
     * caught error path).
     */
    private static final String PARTIAL_TRANSFER_PROBE_SOURCE = """
        export function probeWhile(c: boolean): null {
          while (true) {
            if (c) { break; }
            return null;
          }
        }

        export function probeFor(c: boolean): null {
          for (; true; ) {
            if (c) { break; }
            return null;
          }
        }

        export function probeTestless(c: boolean): null {
          for (;;) {
            if (c) { break; }
            return null;
          }
        }

        export function probeForLet(c: boolean): null {
          for (let i: int = 0; true; i = i + 1) {
            if (c) { break; }
            return null;
          }
        }

        export function probeForLetTestless(c: boolean): null {
          for (let i: int = 0; ; i = i + 1) {
            if (c) { break; }
            return null;
          }
        }

        export function probeTerminateForLetTestless(): null {
          for (let i: int = 0; ; i = i + 1) {
            return null;
          }
        }

        export function probeIfElse(c: boolean): null {
          while (true) {
            if (c) { break; } else { }
            return null;
          }
        }

        export function probeTryCatch(c: boolean): null {
          while (true) {
            try { if (c) { break; } } catch (e) { }
            return null;
          }
        }

        export function probeContinueWhile(c: boolean): null {
          let i: int = 0;
          while (true) {
            if (i < 1) { i = i + 1; continue; }
            if (c) { break; }
            return null;
          }
        }

        export function probeContinueFor(c: boolean): null {
          let i: int = 0;
          for (; true; ) {
            if (i < 1) { i = i + 1; continue; }
            if (c) { break; }
            return null;
          }
        }

        export function probeContinueTestless(c: boolean): null {
          let i: int = 0;
          for (;;) {
            if (i < 1) { i = i + 1; continue; }
            if (c) { break; }
            return null;
          }
        }

        export function probeContinueForLet(c: boolean): null {
          for (let i: int = 0; true; i = i + 1) {
            if (i < 1) { continue; }
            if (c) { break; }
            return null;
          }
        }

        export function probeContinueForLetTestless(c: boolean): null {
          for (let i: int = 0; ; i = i + 1) {
            if (i < 1) { continue; }
            if (c) { break; }
            return null;
          }
        }

        export function probeThrowWhile(c: boolean): null {
          while (true) {
            try {
              if (c) { break; }
              throw { code: "T_WHILE", message: "partial" }
            } catch (e) {
              return null;
            }
          }
        }

        export function probeThrowFor(c: boolean): null {
          for (; true; ) {
            try {
              if (c) { break; }
              throw { code: "T_FOR", message: "partial" }
            } catch (e) {
              return null;
            }
          }
        }

        export function probeThrowTestless(c: boolean): null {
          for (;;) {
            try {
              if (c) { break; }
              throw { code: "T_TESTLESS", message: "partial" }
            } catch (e) {
              return null;
            }
          }
        }

        export function probeThrowForLet(c: boolean): null {
          for (let i: int = 0; true; i = i + 1) {
            try {
              if (c) { break; }
              throw { code: "T_FORLET", message: "partial" }
            } catch (e) {
              return null;
            }
          }
        }

        export function probeThrowForLetTestless(c: boolean): null {
          for (let i: int = 0; ; i = i + 1) {
            try {
              if (c) { break; }
              throw { code: "T_FORLET_TESTLESS", message: "partial" }
            } catch (e) {
              return null;
            }
          }
        }

        export function main(): null {
          probeWhile(true);
          probeWhile(false);
          probeFor(true);
          probeFor(false);
          probeTestless(true);
          probeTestless(false);
          probeForLet(true);
          probeForLet(false);
          probeForLetTestless(true);
          probeForLetTestless(false);
          probeTerminateForLetTestless();
          probeIfElse(true);
          probeIfElse(false);
          probeTryCatch(true);
          probeTryCatch(false);
          probeContinueWhile(true);
          probeContinueWhile(false);
          probeContinueFor(true);
          probeContinueFor(false);
          probeContinueTestless(true);
          probeContinueTestless(false);
          probeContinueForLet(true);
          probeContinueForLet(false);
          probeContinueForLetTestless(true);
          probeContinueForLetTestless(false);
          probeThrowWhile(true);
          probeThrowWhile(false);
          probeThrowFor(true);
          probeThrowFor(false);
          probeThrowTestless(true);
          probeThrowTestless(false);
          probeThrowForLet(true);
          probeThrowForLet(false);
          probeThrowForLetTestless(true);
          probeThrowForLetTestless(false);
          return null;
        }
        """;

    /**
     * The partial-transfer probes on the real toolchains: the oracle and
     * both shared artifacts agree event-for-event with the pinned null
     * outcome (no trace mismatch is admitted), every retained implicit
     * null return is a member of its declared body block and executes
     * exactly once per consumer on its probe's break exit, and the
     * affected transfer/error paths execute — the five finite
     * partial-continue paths (each continue runs exactly once) and the
     * five caught partial-transfer throw bodies (each throw runs exactly
     * once) per consumer. The same program's public compile is rejected
     * with one E5002 per open probe body (the spec's no-fall-off rule;
     * ISSUE-0717 defect snippets 25/26), so the parity drive uses the
     * retained harness profile that keeps the landed implicit null
     * returns observable.
     */
    static void testPartialTransferProductionParity() throws Exception {
        System.out.println("-- the partial-transfer probes through the oracle and "
            + "both shared artifacts (the real toolchains) --");
        Path root = Files.createTempDirectory("partial-transfer-parity-");
        try {
            write(root, "src/app.deal", PARTIAL_TRANSFER_PROBE_SOURCE);
            // The open break-exit probes fall off under the spec's rule, so
            // the public build rejects all seventeen (pinned below); the
            // retained harness profile keeps the landed implicit null
            // returns the closed analysis marks and drives the same lowered
            // project through the real toolchains.
            Lowered lowered = lowerProjectRoot(root, "src/app.deal",
                ConformanceHarnessMetadata.invocation(SemanticProfile.DEAL_V1_2_INT32));
            if (lowered == null) {
                return;
            }
            checkPublicFallOffCount("the seventeen partial-transfer probes",
                PARTIAL_TRANSFER_PROBE_SOURCE, 17);
            List<SemanticOp> implicit = lowered.implicitReturns();
            checkEq(17, implicit.size(), "the seventeen partial-transfer probes each "
                + "retain their implicit null return");
            List<OpId> implicitIds = implicit.stream().map(SemanticOp::opId).toList();
            for (OpId id : implicitIds) {
                boolean member = false;
                for (BlockId body : lowered.declaredBodyBlocks()) {
                    for (SemanticOp op : lowered.blockOps(body)) {
                        if (op.opId().equals(id)) {
                            member = true;
                        }
                    }
                }
                check(member, "the retained implicit null return " + id
                    + " is a member of its declared body block");
            }
            Set<OpId> continueOps = new java.util.LinkedHashSet<>();
            Set<OpId> throwOps = new java.util.LinkedHashSet<>();
            for (SemanticOp op : lowered.unit().ops()) {
                if (op.kind() == SemanticOpKind.CONTINUE) {
                    continueOps.add(op.opId());
                }
                if (op.kind() == SemanticOpKind.THROW) {
                    throwOps.add(op.opId());
                }
            }
            checkEq(5, continueOps.size(), "the unit carries the five finite "
                + "partial-continue probes' continue ops");
            checkEq(5, throwOps.size(), "the unit carries the five partial-transfer"
                + " throw bodies' throw ops");
            Path workspace = Files.createTempDirectory("partial-transfer-parity-ws-");
            try {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.runProject(lowered.project(),
                        lowered.tables(), lowered.registries(),
                        SemanticDifferentialHarness.Expectation.success(
                            "partial-transfer probes", List.of(), "null"), workspace);
                check(verdict.pass(), "the oracle and both shared artifacts agree "
                    + "event-for-event and the probes complete with null (no trace "
                    + "mismatch is admitted): " + verdict.failures());
                checkEq(3, verdict.runs().size(), "the drive produced the exact "
                    + "three consumers: " + verdict.failures());
                for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                    check(run.terminal()
                            instanceof SemanticRuntimeModel.Terminal.Success success
                            && "null".equals(success.resultAtom()),
                        run.consumer() + " completes with the pinned null outcome: "
                            + run.terminal());
                    for (OpId id : implicitIds) {
                        int executed = 0;
                        for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                            if (event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                                    && event.op().equals(id)) {
                                executed++;
                            }
                        }
                        checkEq(1, executed, run.consumer() + " executed the retained "
                            + "implicit null return " + id + " on its probe's break "
                            + "exit");
                    }
                    int continues = 0;
                    int throwsExecuted = 0;
                    Set<OpId> executedContinues = new java.util.LinkedHashSet<>();
                    Set<OpId> executedThrows = new java.util.LinkedHashSet<>();
                    for (SemanticRuntimeModel.TraceEvent event : run.trace()) {
                        if (event.kind() == SemanticOpKind.CONTINUE
                                && event.phase() == SemanticRuntimeModel.Phase.SUCCESS) {
                            continues++;
                            executedContinues.add(event.op());
                        }
                        if (event.kind() == SemanticOpKind.THROW
                                && event.phase() == SemanticRuntimeModel.Phase.FAILURE) {
                            throwsExecuted++;
                            executedThrows.add(event.op());
                        }
                    }
                    checkEq(10, continues, run.consumer() + " executed the five "
                        + "finite partial-continue probes' continue path once per "
                        + "invocation (both the break and the return drive)");
                    checkEq(5, throwsExecuted, run.consumer() + " executed the five "
                        + "partial-transfer throw bodies exactly once each");
                    checkEq(continueOps, executedContinues, run.consumer()
                        + " executed the continue op of every finite "
                        + "partial-continue probe");
                    checkEq(throwOps, executedThrows, run.consumer()
                        + " executed the throw op of every partial-transfer throw "
                        + "body");
                }
            } finally {
                deleteRecursively(workspace);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * One all-throw for-let program of the emitter-closure drive: its
     * display name, whether the loop's test is omitted (the synthetic
     * {@code CONST true} row), and its source.
     */
    private record AllThrowForLetProgram(String name, boolean omittedTest,
                                         String source) {
    }

    /**
     * The all-throw for-let programs: the omitted test's synthetic
     * {@code CONST true} makes the loop literal-true, its body exits only
     * by throw, and the exception unwinds the loop — the exact combination
     * the closed terminator contract admits and ISSUE-0763's remediation
     * must verify on the oracle and both real artifacts. The explicit
     * {@code true} row drives the same unwind (the review observed the
     * missing LOOP terminal on that condition too, so the emitter-side
     * closure is not special to the omitted test). The driver's {@code main}
     * body *is* the loop, so the throw terminates it.
     */
    private static final List<AllThrowForLetProgram> ALL_THROW_FOR_LET_PROGRAMS =
        List.of(
            new AllThrowForLetProgram(
                "the all-throw omitted-test for-let", true, """
                export function main(): null {
                  for (let i: int = 0; ; i = i + 1) {
                    throw { code: "THROW_ONLY", message: "throw only" }
                  }
                }
                """),
            new AllThrowForLetProgram(
                "the all-throw explicit-true for-let", false, """
                export function main(): null {
                  for (let i: int = 0; true; i = i + 1) {
                    throw { code: "THROW_ONLY", message: "throw only" }
                  }
                }
                """));

    /**
     * The all-throw for-let bodies on the real toolchains: the public build
     * compiles each terminated body with no implicit trailing null return,
     * and the oracle and both shared artifacts agree event-for-event on the
     * pinned failure — including the LOOP's own FAILURE terminal, which the
     * error publishes as it unwinds the loop (the trace protocol's exactly-
     * one-terminal-per-START rule). A terminal-only comparison would hide
     * the closure event, and a trace comparison that dropped it would accept
     * an artifact leaving the loop's START unterminated, so the LOOP FAILURE
     * is asserted directly, immediately after the THROW FAILURE.
     */
    static void testAllThrowForLetParity() throws Exception {
        System.out.println("-- the all-throw for-let bodies through the oracle "
            + "and both shared artifacts (the real toolchains) --");
        for (AllThrowForLetProgram program : ALL_THROW_FOR_LET_PROGRAMS) {
            driveAllThrowForLet(program);
        }
    }

    /** One all-throw for-let program through the oracle and both artifacts. */
    private static void driveAllThrowForLet(AllThrowForLetProgram program)
            throws Exception {
        Path root = Files.createTempDirectory("all-throw-for-let-parity-");
        try {
            write(root, "src/app.deal", program.source());
            // The public build admits the terminated body (the acceptance
            // shape the ISSUE-0717 no-fall-off rule leaves in scope): the
            // one lowering reports zero diagnostics.
            Lowered lowered = lowerProjectRoot(root, "src/app.deal",
                productionInvocation());
            if (lowered == null) {
                return;
            }
            List<BlockId> bodies = lowered.declaredBodyBlocks();
            checkEq(1, bodies.size(), program.name() + " carries the entry main "
                + "body alone");
            check(lowered.implicitReturns().isEmpty(), program.name() + " keeps no "
                + "implicit trailing null return");
            if (bodies.size() != 1) {
                return;
            }
            List<SemanticOp> bodyOps = lowered.blockOps(bodies.get(0));
            SemanticOp loop = null;
            for (SemanticOp op : bodyOps) {
                if (op.kind() == SemanticOpKind.LOOP) {
                    loop = op;
                }
            }
            check(loop != null && !bodyOps.isEmpty()
                    && bodyOps.get(bodyOps.size() - 1).equals(loop),
                program.name() + " ends with its literal-true LOOP and no "
                    + "trailing statement (op kinds "
                    + kindsOf(lowered, bodies.get(0)) + ")");
            if (loop != null
                    && loop.payload() instanceof KindPayload.LoopPayload payload) {
                SemanticOp conditionProducer = null;
                for (OpId member : lowered.table().blockOps().get(payload.initBlock())) {
                    SemanticOp op = lowered.op(member);
                    if (op != null && payload.condition().equals(op.result())) {
                        conditionProducer = op;
                    }
                }
                // The omitted test is the synthetic CONST true of the init
                // block; the explicit true row produces the source literal
                // (the same one-CONST condition production).
                SourceOriginKind expectedOrigin = program.omittedTest()
                    ? SourceOriginKind.SYNTHETIC : SourceOriginKind.USER;
                check(conditionProducer != null
                        && conditionProducer.kind() == SemanticOpKind.CONST
                        && conditionProducer.origin().kind() == expectedOrigin
                        && conditionProducer.payload() instanceof KindPayload.ConstPayload constant
                        && constant.value() instanceof ScalarValue.Boolean bool
                        && bool.value(),
                    program.name() + " carries the literal true condition of its "
                        + "init block (expected origin " + expectedOrigin + ")");
                int bodyThrows = 0;
                boolean bodyReturn = false;
                for (SemanticOp op : lowered.blockOps(payload.bodyBlock())) {
                    if (op.kind() == SemanticOpKind.THROW) {
                        bodyThrows++;
                    }
                    if (op.kind() == SemanticOpKind.RETURN) {
                        bodyReturn = true;
                    }
                }
                checkEq(1, bodyThrows, program.name() + " body carries its "
                    + "THROW");
                check(!bodyReturn, program.name() + " body carries no RETURN op");
            }
            // The throw's own origin (line 3, column 5 of the source): the
            // pinned failure tuple of all three consumers.
            String originText = root.resolve("src/app.deal").toAbsolutePath()
                + ":3:5";
            Path workspace = Files.createTempDirectory("all-throw-for-let-ws-");
            try {
                SemanticDifferentialHarness.Verdict verdict =
                    SemanticDifferentialHarness.runProject(lowered.project(),
                        lowered.tables(), lowered.registries(),
                        SemanticDifferentialHarness.Expectation.failure(
                            program.name(), List.of(),
                            "THROW_ONLY", originText), workspace);
                check(verdict.pass(), "the oracle and both shared artifacts agree "
                    + "event-for-event on " + program.name() + " (the "
                    + "LOOP's FAILURE terminal included): " + verdict.failures());
                checkEq(3, verdict.runs().size(), "the drive produced the exact "
                    + "three consumers: " + verdict.failures());
                for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                    check(run.terminal()
                            instanceof SemanticRuntimeModel.Terminal.DealFailure,
                        run.consumer() + " fails with the thrown error: "
                            + run.terminal());
                    if (run.terminal() instanceof
                            SemanticRuntimeModel.Terminal.DealFailure failure) {
                        checkEq("THROW_ONLY", failure.error().code(), run.consumer()
                            + " carries the pinned failure code");
                        checkEq("throw only", failure.error().message(), run.consumer()
                            + " carries the pinned failure message");
                        checkEq(originText, failure.error().origin(), run.consumer()
                            + " carries the pinned throw origin");
                        checkEq(null, failure.error().expected(), run.consumer()
                            + " carries the pinned null expected token");
                        checkEq(null, failure.error().actual(), run.consumer()
                            + " carries the pinned null actual token");
                    }
                    int loopStarts = 0;
                    int loopFailures = 0;
                    int loopSuccesses = 0;
                    int throwFailures = 0;
                    int loopFailureIndex = -1;
                    int throwFailureIndex = -1;
                    List<SemanticRuntimeModel.TraceEvent> trace = run.trace();
                    for (int i = 0; i < trace.size(); i++) {
                        SemanticRuntimeModel.TraceEvent event = trace.get(i);
                        if (event.kind() == SemanticOpKind.LOOP) {
                            if (event.phase() == SemanticRuntimeModel.Phase.START) {
                                loopStarts++;
                            } else if (event.phase()
                                    == SemanticRuntimeModel.Phase.FAILURE) {
                                loopFailures++;
                                loopFailureIndex = i;
                            } else {
                                loopSuccesses++;
                            }
                        }
                        if (event.kind() == SemanticOpKind.THROW
                                && event.phase()
                                    == SemanticRuntimeModel.Phase.FAILURE) {
                            throwFailures++;
                            throwFailureIndex = i;
                        }
                    }
                    checkEq(1, loopStarts, run.consumer() + " starts the "
                        + program.name() + " LOOP exactly once");
                    checkEq(1, loopFailures, run.consumer() + " publishes the "
                        + "LOOP's own FAILURE terminal as the error unwinds it "
                        + "(never an unterminated START)");
                    checkEq(0, loopSuccesses, run.consumer() + " never completes "
                        + "the all-throw LOOP normally");
                    checkEq(1, throwFailures, run.consumer() + " publishes the "
                        + "THROW's FAILURE terminal exactly once");
                    check(throwFailureIndex >= 0 && loopFailureIndex >= 0
                            && loopFailureIndex == throwFailureIndex + 1,
                        run.consumer() + " publishes the LOOP FAILURE immediately "
                            + "after the THROW FAILURE (the unwind order; throw "
                            + "index " + throwFailureIndex + ", loop index "
                            + loopFailureIndex + ")");
                }
            } finally {
                deleteRecursively(workspace);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    private static void driveDifferentialFixtures(List<Fixture> fixtures) throws Exception {
        for (Fixture fixture : fixtures) {
            Path root = Files.createTempDirectory("composite-terminator-matrix-");
            try {
                Lowered lowered;
                if (fixture.async()) {
                    write(root, "src/" + fixture.relativePath() + ".deal",
                        fixtureSource(fixture.relativePath()));
                    lowered = lowerProjectRoot(root,
                        "src/" + fixture.relativePath() + ".deal");
                } else {
                    // The driver entry module asserts the fixture's pinned
                    // probe value; the fixture stays a companion module.
                    write(root, "src/" + fixture.relativePath() + ".deal",
                        fixtureSource(fixture.relativePath()));
                    write(root, "src/app.deal",
                        "import * as fx from \"./" + fixture.relativePath() + "\"\n\n"
                            + "export function main(): null {\n"
                            + "  if (fx." + fixture.probe() + "() !== "
                            + fixture.pinnedValue() + ") {\n"
                            + "    throw { code: \"TEST_FAIL\", message: \""
                            + fixture.relativePath() + " pinned probe value\" }\n"
                            + "  }\n"
                            + "  return null\n"
                            + "}\n");
                    lowered = lowerProjectRoot(root, "src/app.deal");
                }
                if (lowered == null) {
                    continue;
                }
                Path workspace = Files.createTempDirectory(
                    "composite-terminator-matrix-ws-");
                try {
                    SemanticDifferentialHarness.Verdict verdict = fixture.async()
                        ? SemanticDifferentialHarness.runAsyncEntry(lowered.project(),
                            lowered.tables(), fixture.probe(), List.of(),
                            SemanticDifferentialHarness.Expectation.success(
                                fixture.relativePath(), List.of(),
                                "int:" + fixture.pinnedValue()), workspace, null)
                        : SemanticDifferentialHarness.runProject(lowered.project(),
                            lowered.tables(), lowered.registries(),
                            SemanticDifferentialHarness.Expectation.success(
                                fixture.relativePath(), List.of(), "null"), workspace);
                    checkEq(3, verdict.runs().size(), fixture.relativePath()
                        + ": the drive produced the three consumers: "
                        + verdict.failures());
                    boolean accepted = RECURSIVE_FIXTURES.contains(fixture.relativePath())
                        ? verdict.failures().stream().allMatch(failure ->
                            failure.contains("starts again before its previous terminal")
                                || failure.contains("terminates without an open START"))
                        : verdict.pass();
                    check(accepted, fixture.relativePath()
                        + ": the oracle and both shared artifacts agree event-for-event "
                        + "with the pinned probe (only recursive pairing notices allowed): "
                        + verdict.failures());
                    for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                        check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success success
                                && (fixture.async() ? "int:" + fixture.pinnedValue() : "null")
                                    .equals(success.resultAtom()),
                            fixture.relativePath() + ": " + run.consumer()
                                + " completes with the pinned outcome: "
                                + run.terminal());
                    }
                } finally {
                    deleteRecursively(workspace);
                }
            } finally {
                deleteRecursively(root);
            }
        }
    }

    // =========================================================================
    // The production invocation and process helpers
    // =========================================================================

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static CompilationOrchestrator productionCompile(Path moduleRoot, Path entry,
            Backend backend) throws Exception {
        return compile(moduleRoot, entry, backend, productionInvocation());
    }

    /**
     * The public build's diagnostics for one probe program: the negative
     * controls' rejection through the release-owned production invocation
     * (the spec's no-fall-off rule, ISSUE-0717 defect snippets 25/26).
     */
    private static List<CompilerDiagnostic> publicBuildDiagnostics(String source)
            throws Exception {
        Path root = Files.createTempDirectory("composite-terminator-public-");
        try {
            write(root, "src/app.deal", source);
            CompilationOrchestrator orchestrator = productionCompile(
                root.resolve("src"), root.resolve("src/app.deal"), Backend.LUAJIT);
            return List.copyOf(orchestrator.diagnostics());
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * Pins the public build's rejection of one open {@code : null} body
     * with exactly one E5002 (the spec's no-fall-off rule; ISSUE-0717
     * defect snippets 25/26).
     */
    private static void checkPublicFallOff(String name, String source)
            throws Exception {
        checkPublicFallOffCount(name, source, 1);
    }

    /**
     * Pins the public build's rejection of a program with exactly the
     * expected number of E5002 fall-off diagnostics (one per open
     * {@code : null} body; the spec's no-fall-off rule, ISSUE-0717 defect
     * snippets 25/26).
     */
    private static void checkPublicFallOffCount(String name, String source,
                                                int expected) throws Exception {
        List<CompilerDiagnostic> diagnostics = publicBuildDiagnostics(source);
        List<String> fallOff = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if ("E5002".equals(diagnostic.code())) {
                fallOff.add(diagnostic.file() + ":" + diagnostic.line()
                    + ":" + diagnostic.column());
            }
        }
        checkEq(expected, fallOff.size(), name + ": the public build reports exactly "
            + expected + " E5002 fall-off rejection(s): " + diagnostics);
        checkEq((long) expected, fallOff.stream().distinct().count(), name
            + ": every E5002 fall-off rejection names a distinct open body: "
            + fallOff);
    }

    /** One orchestrator compile through the named invocation record. */
    private static CompilationOrchestrator compile(Path moduleRoot, Path entry,
            Backend backend, CompilerInvocation invocation) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry.toAbsolutePath(), moduleRoot.getParent().resolve("out"), false,
            false, false, false, backend, null, List.of(moduleRoot.toAbsolutePath()),
            null, null, invocation);
        compileQuietly(orchestrator);
        return orchestrator;
    }

    /** One orchestrator compile with the compiler's report captured (never printed). */
    private static void compileQuietly(CompilationOrchestrator orchestrator)
            throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(ByteArrayOutputStream.nullOutputStream(),
                true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(ByteArrayOutputStream.nullOutputStream(),
                true, StandardCharsets.UTF_8));
            orchestrator.compile();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static ProcessOutcome runProcess(Path directory, Map<String, String> env,
            String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(false);
        builder.environment().putAll(env);
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new ProcessOutcome(exit, stdout, stderr);
    }

    /** The absolute compile classpath of this test JVM (never cwd-relative). */
    private static String absoluteClasspath() {
        StringBuilder joined = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(File.pathSeparator);
            }
            joined.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return joined.toString();
    }

    private static List<String> artifactFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .sorted().toList();
        }
    }

    private static void write(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception ignored) {
            // Best-effort temp cleanup only; never part of a test result.
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Closed Composite Terminator Analysis Tests "
            + "(ISSUE-0712) ===\n");

        testCorpusInventory();
        testCompositeMarkingPositives();
        testNullBothReturnTailMonotonicity();
        testCompositeMarkingNegatives();
        testPartialTransferKeepsImplicitReturn();
        testPartialTransferMonotonicity();
        testNestedBodyDisjointness();
        testNestedGroupFallOffExemption();
        testUnterminatedNonNullStillFailsClosed();
        testLiteralTrueLoopOnNonNullBodyAtUnitLevel();
        testTestlessForLetProductionArtifacts();
        testProductionArtifacts();
        testDifferentialMatrix();
        testResidualAcceptanceParity();
        testPartialTransferProductionParity();
        testAllThrowForLetParity();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
