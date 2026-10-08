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

    private static final List<Fixture> NESTED_BODY_FIXTURES = List.of(
        new Fixture("functions/direct-recursion", "test_direct_recursion", "0", false),
        new Fixture("functions/nested-scope-recursion", "test_nested_scope_recursion",
            "0", false),
        new Fixture("control-flow/return-in-try", "test_return_in_try_loop", "5", false));

    private static final Set<String> RECURSIVE_FIXTURES = Set.of(
        "functions/direct-recursion", "functions/nested-scope-recursion");

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
        return ConformanceHarnessMetadata.stripClassificationHeaders(
            Files.readString(FIXTURE_ROOT.resolve(relativePath + ".deal"),
                StandardCharsets.UTF_8));
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
        Path root = Files.createTempDirectory("composite-terminator-");
        try {
            write(root, "src/app.deal", source);
            return lowerProjectRoot(root, "src/app.deal");
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * The production compile plus the release-owned one project lowering
     * of one project root whose entry module is {@code src/app.deal}.
     */
    private static Lowered lowerProjectRoot(Path root, String entryRelative)
            throws Exception {
        CompilationOrchestrator orchestrator = productionCompile(root.resolve("src"),
            root.resolve(entryRelative), Backend.LUAJIT);
        check(orchestrator.diagnostics().isEmpty(),
            "the project compiles through the release-owned production invocation: "
                + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || manifests == null || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            fail("the project's checked facts are complete (built/manifests/surface)");
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
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
        for (Positive positive : positives) {
            Lowered lowered = lowerProbe(positive.name(), positive.source());
            if (lowered == null) {
                continue;
            }
            check(lowered.implicitReturns().isEmpty(), positive.name()
                + ": the terminated body carries no implicit trailing synthetic "
                + "null return");
            List<BlockId> bodies = lowered.declaredBodyBlocks();
            checkEq(2, bodies.size(), positive.name()
                + ": the unit carries the probe body and the entry main body");
            if (bodies.size() != 2) {
                continue;
            }
            BlockId probeBody = bodies.get(0);
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
     * trailing statement.
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
            Lowered lowered = lowerProbe(negative.name(), negative.source());
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
        }
    }

    /**
     * The nested-body disjointness: a literal-true loop inside a nested
     * declared body marks the nested body, never the enclosing loop's body
     * — the enclosing body stays {@code OPEN} and keeps its implicit null
     * return, while the nested composite-terminated body carries none.
     */
    static void testNestedBodyDisjointness() throws Exception {
        System.out.println("-- a nested body's transfer never contributes to an "
            + "enclosing loop's state --");
        Lowered lowered = lowerProbe("nested declared body", probeProgram("""
            export function probe(): null {
              while (true) {
                function inner(): null {
                  while (true) { return null; }
                }
              }
            }
            """));
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
     * The literal-true loop on a non-null body at the unit level: the
     * checker's conservative loop analysis (E5002) makes the shape
     * unreachable through the project entry, so the loop rule's non-null
     * arm is exercised through the registered full-program session — the
     * body is terminated, carries no implicit return, and produces no
     * diagnostic.
     */
    static void testLiteralTrueLoopOnNonNullBodyAtUnitLevel() throws Exception {
        System.out.println("-- the literal-true loop on a non-null body at the unit "
            + "level (the checker's E5002 rejection makes it unreachable through the "
            + "project entry) --");
        record NonNullLoop(String name, String source) {
        }
        List<NonNullLoop> cases = List.of(
            new NonNullLoop("while (true)", """
                export function probe(): int {
                  while (true) { return 1; }
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
            check(checkerRejects, nonNull.name() + ": the checker's conservative "
                + "loop analysis rejects the shape with E5002 (the project entry "
                + "never reaches the rule)");
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
        System.out.println("-- the four fixtures through the release-owned production "
            + "invocation on LuaJIT and JVM --");
        for (Fixture fixture : FIXTURES) {
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
                    write(out, "FixtureProbe.java", jvmDriver(fixture, className));
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
     * the surface's callable (an async export's invocation drives the
     * operation to completion through the runtime's synchronous async
     * chain — the lane's invocation contract). Nothing is printed: the
     * pinned outcome is the fixture's own guard and the empty sidecar
     * transcript.
     */
    private static String luaDriver(Fixture fixture) {
        return """
            local surface = dofile("%s.lua")
            local ok, err = __dealMain()
            if not ok then error(err, 0) end
            local probe = surface["%s"]
            assert(type(probe) == "table" and probe.__kind == "function"
              and type(probe.f) == "function",
              "the entry surface publishes the fixture probe")
            probe.f()
            """.formatted(fixture.relativePath(), fixture.probe());
    }

    /**
     * The JVM fixture driver: the module walk, the published entry
     * surface, and the fixture's exported probe invoked once; silent on
     * success so the sidecar transcript comparison is the pin.
     */
    private static String jvmDriver(Fixture fixture, String className) {
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
                fn.fn.invoke(new Object[0]);
              }
            }
            """.formatted(className, className, jvmModuleKey(fixture),
                fixture.probe());
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
        driveDifferentialFixtures(FIXTURES);
    }

    static void testNestedBodyProductionParity() throws Exception {
        driveDifferentialFixtures(NESTED_BODY_FIXTURES);
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
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry.toAbsolutePath(), moduleRoot.getParent().resolve("out"), false,
            false, false, false, backend, null, List.of(moduleRoot.toAbsolutePath()),
            null, null, productionInvocation());
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
        testNestedBodyDisjointness();
        testUnterminatedNonNullStillFailsClosed();
        testLiteralTrueLoopOnNonNullBodyAtUnitLevel();
        testProductionArtifacts();
        testDifferentialMatrix();
        testNestedBodyProductionParity();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
