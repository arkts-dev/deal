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
import deal.test.HostModuleLoadEmissionTest.ProcessOutcome;
import deal.types.Type;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static deal.test.HostModuleLoadEmissionTest.absoluteClasspath;
import static deal.test.HostModuleLoadEmissionTest.deleteRecursively;
import static deal.test.HostModuleLoadEmissionTest.writeFileIn;

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

    // The four named fixtures

    private static final Path FIXTURE_ROOT =
        Path.of("test/conformance/backend-runtime");

    private record Fixture(String relativePath, String probe, String pinnedValue,
                           boolean async) {

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

    private static final Fixture DIRECT_RECURSION_FIXTURE = new Fixture(
        "functions/direct-recursion", "test_direct_recursion", "0", false);

    private static final Fixture MUTUAL_RECURSION_FIXTURE = new Fixture(
        "functions/mutual-recursion", "test_mutual_recursion", "true", false);

    private static final List<Fixture> RESIDUAL_ACCEPTANCE_FIXTURES = List.of(
        DIRECT_RECURSION_FIXTURE,
        MUTUAL_RECURSION_FIXTURE,
        new Fixture("functions/nested-scope-recursion", "test_nested_scope_recursion",
            "0", false),
        new Fixture("control-flow/return-in-try", "test_return_in_try_loop", "5", false));

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
            throw { code: "TAIL_EXECUTED", message: "TAIL715" };
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

    // 1. The unit battery through the registered lowering session

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
            return unit.ops().stream().filter(op -> op.kind() == SemanticOpKind.RETURN
                && op.origin().kind() == SourceOriginKind.SYNTHETIC).toList();
        }
    }

    private static Lowered lowerProbe(String name, String source) throws Exception {
        return lowerProbe(name, source, productionInvocation());
    }

    private static Lowered lowerProbe(String name, String source,
            CompilerInvocation invocation) throws Exception {
        Path root = Files.createTempDirectory("composite-terminator-");
        try {
            writeFileIn(root, "src/app.deal", source);
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
        return lowerCompiledProject(compile(root.resolve("src"), root.resolve(entryRelative),
            Backend.LUAJIT, invocation), invocation);
    }

    private static Lowered lowerCompiledProject(CompilationOrchestrator orchestrator,
            CompilerInvocation invocation) {
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
        return lowered.blockOps(block).stream().map(op -> op.kind().name()).toList();
    }

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
                throw { code: "PARTIAL", message: "partial" };""", true),
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
                throw { code: "PARTIAL", message: "partial" };""", false),
            new PartialShape("try/catch with continue then throw", """
                try { if (c) { continue; } } catch (e) { }
                throw { code: "PARTIAL", message: "partial" };""", false));
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
            writeFileIn(root, "src/main.deal", source);
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
                        classes + File.pathSeparator + absoluteClasspath(), className);
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

    // 2. The four fixtures on the real toolchains

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
                writeFileIn(root, "src/" + fixture.relativePath() + ".deal",
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
                    writeFileIn(out, "fixture_driver.lua", luaDriver(fixture));
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
                    String driver = "FixtureProbe_"
                        + java.util.UUID.randomUUID().toString().replace("-", "");
                    writeFileIn(out, driver + ".java", jvmDriver(fixture, className,
                        Files.readString(jvmArtifact, StandardCharsets.UTF_8))
                            .replace("FixtureProbe", driver));
                    ProcessOutcome javac = runProcess(out, Map.of(), "javac",
                        "--release", "25", "-proc:none", "-cp", absoluteClasspath(),
                        "-d", classes.toString(),
                        jvmArtifact.toAbsolutePath().toString(),
                        out.resolve(driver + ".java").toAbsolutePath().toString());
                    checkEq(0, javac.exitCode(), fixture.relativePath() + " (JVM): "
                        + "the artifact compiles under javac --release 25 -proc:none: "
                        + javac.stderr());
                    if (javac.exitCode() == 0) {
                        ProcessOutcome run = runProcess(out, Map.of("DEAL_DEFER_MAIN",
                            "1"), "java", "-cp", classes
                            + File.pathSeparator + absoluteClasspath(), driver);
                        assertSidecar(fixture, "JVM", run);
                    }
                }
                driveDifferentialFixture(fixture, root, lua);
            } finally {
                deleteRecursively(root);
            }
        }
    }

    /** The zero-E6005 gate of one production compile. */
    private static void checkDiagnostics(Fixture fixture, String target,
            CompilationOrchestrator orchestrator) {
        check(orchestrator.diagnostics().isEmpty(), fixture.relativePath() + " (" + target
            + "): zero production diagnostics: " + orchestrator.diagnostics());
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

    private static String luaDriver(Fixture fixture) {
        String invoke = fixture.async() ? """
            local entry = nil
            for key, candidate in pairs(__asyncEntries) do
              if string.sub(key, -%d) == "#%s" then entry = candidate end
            end
            assert(entry ~= nil, "the artifact publishes the async entry")
            local okA, value = pcall(entry, "-", true)
            if not okA then error(value, 0) end
            """.formatted(fixture.probe().length() + 1, fixture.probe())
            : "local value = probe.f()";
        return """
            local surface = dofile("%s.lua")
            local ok, err = __dealMain()
            if not ok then error(err, 0) end
            local probe = surface["%s"]
            assert(type(probe) == "table" and probe.__kind == "function"
              and type(probe.f) == "function", "the entry surface publishes the fixture probe")
            %s
            assert(tostring(value) == %s, "the probe result " .. tostring(value))
            """.formatted(fixture.relativePath(), fixture.probe(), invoke,
                quotedStringLiteral(fixture.pinnedRuntimeText()));
    }

    /** One runtime text as a quoted Lua/JVM string literal. */
    private static String quotedStringLiteral(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String jvmDriver(Fixture fixture, String className, String jvmSource) {
        String invoke = fixture.async()
            ? "Object value = " + className + "." + asyncEntryMethod(jvmSource)
                + "(\"-\", true, new Object[]{});"
            : """
                deal.codegen.jvm.JvmRuntime.Table surface = %s.EXPORT_SURFACES.get("%s");
                if (surface == null) { throw new IllegalStateException("no entry surface"); }
                Object probe = surface.read("%s");
                if (!(probe instanceof deal.codegen.jvm.JvmRuntime.FunctionValue fn)) {
                  throw new IllegalStateException("the entry surface publishes the fixture probe");
                }
                Object value = fn.fn.invoke(new Object[0]);
                """.formatted(className, jvmModuleKey(fixture), fixture.probe());
        return """
            public final class FixtureProbe {
              public static void main(String[] args) {
                %s.main(new String[0]);
                %s
                if (!String.valueOf(value).equals(%s)) {
                  throw new IllegalStateException("the probe result " + value);
                }
              }
            }
            """.formatted(className, invoke, quotedStringLiteral(fixture.pinnedRuntimeText()));
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

    // 3. The pinned probe values through the oracle and both artifacts

    private static final String PARTIAL_TRANSFER_PROBE_SOURCE = partialTransferSource();

    private static String partialTransferSource() {
        String[] names = { "While", "For", "Testless", "ForLet", "ForLetTestless" };
        String[] headers = { "while (true)", "for (; true; )", "for (;;)",
            "for (let i: int = 0; true; i = i + 1)",
            "for (let i: int = 0; ; i = i + 1)" };
        String[] codes = { "T_WHILE", "T_FOR", "T_TESTLESS", "T_FORLET", "T_FORLET_TESTLESS" };
        StringBuilder source = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        for (String variant : List.of("", "Continue", "Throw")) {
            for (int i = 0; i < headers.length; i++) {
                String name = "probe" + variant + names[i];
                String setup = variant.equals("Continue") && i < 3 ? "let i: int = 0;\n" : "";
                String body = switch (variant) {
                    case "Continue" -> "if (i < 1) { "
                        + (i < 3 ? "i = i + 1; " : "")
                        + "continue; }\nif (c) { break; }\nreturn null;";
                    case "Throw" -> "try {\nif (c) { break; }\nthrow { code: \""
                        + codes[i] + "\", message: \"partial\" };\n} catch (e) { return null; }";
                    default -> "if (c) { break; }\nreturn null;";
                };
                source.append("export function ").append(name).append("(c: boolean): null {\n")
                    .append(setup).append(headers[i]).append(" {\n").append(body)
                    .append("\n}\n}\n");
                calls.append(name).append("(true);\n").append(name).append("(false);\n");
            }
        }
        for (String shape : List.of("IfElse", "TryCatch")) {
            String name = "probe" + shape;
            source.append("export function ").append(name).append("(c: boolean): null {\n")
                .append("while (true) {\n")
                .append(shape.equals("IfElse") ? "if (c) { break; } else { }\n"
                    : "try { if (c) { break; } } catch (e) { }\n")
                .append("return null;\n}\n}\n");
            calls.append(name).append("(true);\n").append(name).append("(false);\n");
        }
        return source + "export function probeTerminateForLetTestless(): null {\n"
            + headers[4] + " { return null; }\n}\n"
            + "export function main(): null {\n" + calls
            + "probeTerminateForLetTestless();\nreturn null;\n}\n";
    }

    static void testPartialTransferProductionParity() throws Exception {
        System.out.println("-- the partial-transfer probes through the oracle and "
            + "both shared artifacts (the real toolchains) --");
        Path root = Files.createTempDirectory("partial-transfer-parity-");
        try {
            writeFileIn(root, "src/app.deal", PARTIAL_TRANSFER_PROBE_SOURCE);
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
            Set<OpId> bodyMembers = lowered.declaredBodyBlocks().stream()
                .flatMap(body -> lowered.blockOps(body).stream()).map(SemanticOp::opId)
                .collect(java.util.stream.Collectors.toSet());
            for (OpId id : implicitIds) {
                check(bodyMembers.contains(id), id + ": implicit RETURN belongs to a declared body");
            }
            Set<OpId> continueOps = opIds(lowered, SemanticOpKind.CONTINUE);
            Set<OpId> throwOps = opIds(lowered, SemanticOpKind.THROW);
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
                        long executed = run.trace().stream().filter(event ->
                            event.phase() == SemanticRuntimeModel.Phase.SUCCESS
                                && event.op().equals(id)).count();
                        checkEq(1L, executed, run.consumer() + " executed the retained "
                            + "implicit null return " + id + " on its probe's break "
                            + "exit");
                    }
                    List<SemanticRuntimeModel.TraceEvent> continues = events(run,
                        SemanticOpKind.CONTINUE, SemanticRuntimeModel.Phase.SUCCESS);
                    List<SemanticRuntimeModel.TraceEvent> throwsExecuted = events(run,
                        SemanticOpKind.THROW, SemanticRuntimeModel.Phase.FAILURE);
                    checkEq(10, continues.size(), run.consumer() + ": continue once per invocation");
                    checkEq(5, throwsExecuted.size(), run.consumer() + ": throw once per body");
                    checkEq(continueOps, eventOps(continues), run.consumer() + ": every continue");
                    checkEq(throwOps, eventOps(throwsExecuted), run.consumer() + ": every throw");
                }
            } finally {
                deleteRecursively(workspace);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    private static Set<OpId> opIds(Lowered lowered, SemanticOpKind kind) {
        return lowered.unit().ops().stream().filter(op -> op.kind() == kind)
            .map(SemanticOp::opId).collect(java.util.stream.Collectors.toSet());
    }

    private static List<SemanticRuntimeModel.TraceEvent> events(
            SemanticRuntimeModel.ConsumerRun run, SemanticOpKind kind,
            SemanticRuntimeModel.Phase phase) {
        return run.trace().stream().filter(event -> event.kind() == kind && event.phase() == phase)
            .toList();
    }

    private static Set<OpId> eventOps(List<SemanticRuntimeModel.TraceEvent> events) {
        return events.stream().map(SemanticRuntimeModel.TraceEvent::op)
            .collect(java.util.stream.Collectors.toSet());
    }

    private record AllThrowForLetProgram(String name, boolean omittedTest,
                                         String source) {
    }

    private static final List<AllThrowForLetProgram> ALL_THROW_FOR_LET_PROGRAMS =
        List.of(
            new AllThrowForLetProgram(
                "the all-throw omitted-test for-let", true, """
                export function main(): null {
                  for (let i: int = 0; ; i = i + 1) {
                    throw { code: "THROW_ONLY", message: "throw only" };
                  }
                }
                """),
            new AllThrowForLetProgram(
                "the all-throw explicit-true for-let", false, """
                export function main(): null {
                  for (let i: int = 0; true; i = i + 1) {
                    throw { code: "THROW_ONLY", message: "throw only" };
                  }
                }
                """));

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
            writeFileIn(root, "src/app.deal", program.source());
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
            SemanticOp loop = bodyOps.stream().filter(op -> op.kind() == SemanticOpKind.LOOP)
                .findFirst().orElse(null);
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
                List<SemanticOp> loopBody = lowered.blockOps(payload.bodyBlock());
                checkEq(1L, loopBody.stream().filter(op -> op.kind() == SemanticOpKind.THROW)
                    .count(), program.name() + ": body carries one THROW");
                check(loopBody.stream().noneMatch(op -> op.kind() == SemanticOpKind.RETURN),
                    program.name() + ": body carries no RETURN");
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
                    List<SemanticRuntimeModel.TraceEvent> loopFailures = events(run,
                        SemanticOpKind.LOOP, SemanticRuntimeModel.Phase.FAILURE);
                    List<SemanticRuntimeModel.TraceEvent> throwFailures = events(run,
                        SemanticOpKind.THROW, SemanticRuntimeModel.Phase.FAILURE);
                    checkEq(1, events(run, SemanticOpKind.LOOP,
                        SemanticRuntimeModel.Phase.START).size(), run.consumer() + ": one LOOP START");
                    checkEq(1, loopFailures.size(), run.consumer() + ": one LOOP FAILURE");
                    checkEq(0, events(run, SemanticOpKind.LOOP,
                        SemanticRuntimeModel.Phase.SUCCESS).size(), run.consumer() + ": no LOOP SUCCESS");
                    checkEq(1, throwFailures.size(), run.consumer() + ": one THROW FAILURE");
                    check(loopFailures.size() == 1 && throwFailures.size() == 1
                            && run.trace().indexOf(loopFailures.get(0))
                                == run.trace().indexOf(throwFailures.get(0)) + 1,
                        run.consumer() + ": LOOP FAILURE immediately follows THROW FAILURE");
                }
            } finally {
                deleteRecursively(workspace);
            }
        } finally {
            deleteRecursively(root);
        }
    }

    private static void driveDifferentialFixture(Fixture fixture, Path root,
            CompilationOrchestrator prepared) throws Exception {
        Lowered lowered;
        if (fixture.async()) {
            lowered = lowerCompiledProject(prepared, productionInvocation());
        } else {
            writeFileIn(root, "src/app.deal",
                "import * as fx from \"./" + fixture.relativePath() + "\";\n\n"
                    + "export function main(): null {\n"
                    + "  if (fx." + fixture.probe() + "() !== " + fixture.pinnedValue() + ") {\n"
                    + "    throw { code: \"TEST_FAIL\", message: \""
                    + fixture.relativePath() + " pinned probe value\" };\n"
                    + "  }\n  return null;\n}\n");
            lowered = lowerProjectRoot(root, "src/app.deal");
        }
        if (lowered == null) {
            return;
        }
        Path workspace = Files.createTempDirectory("composite-terminator-matrix-ws-");
        try {
            SemanticDifferentialHarness.Verdict verdict = fixture.async()
                ? SemanticDifferentialHarness.runAsyncEntry(lowered.project(), lowered.tables(),
                    fixture.probe(), List.of(), SemanticDifferentialHarness.Expectation.success(
                        fixture.relativePath(), List.of(), "int:" + fixture.pinnedValue()),
                    workspace, null)
                : SemanticDifferentialHarness.runProject(lowered.project(), lowered.tables(),
                    lowered.registries(), SemanticDifferentialHarness.Expectation.success(
                        fixture.relativePath(), List.of(), "null"), workspace);
            checkEq(3, verdict.runs().size(), fixture.relativePath()
                + ": three consumers: " + verdict.failures());
            boolean accepted = RECURSIVE_FIXTURES.contains(fixture.relativePath())
                ? verdict.failures().stream().allMatch(failure ->
                    failure.contains("starts again before its previous terminal")
                        || failure.contains("terminates without an open START"))
                : verdict.pass();
            check(accepted, fixture.relativePath()
                + ": event parity (only recursive pairing notices allowed): " + verdict.failures());
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success success
                        && (fixture.async() ? "int:" + fixture.pinnedValue() : "null")
                            .equals(success.resultAtom()),
                    fixture.relativePath() + ": " + run.consumer() + ": " + run.terminal());
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    // The production invocation and process helpers

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
            writeFileIn(root, "src/app.deal", source);
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
        List<String> fallOff = diagnostics.stream().filter(d -> "E5002".equals(d.code()))
            .map(d -> d.file() + ":" + d.line() + ":" + d.column()).toList();
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
        return HostModuleLoadEmissionTest.runProcess(List.of(command), directory,
            "1".equals(env.get("DEAL_DEFER_MAIN")));
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

    // Main

    public static void main(String[] args) throws Exception {
        System.out.println("=== Closed Composite Terminator Analysis Tests "
            + "(ISSUE-0712) ===\n");

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
        testPartialTransferProductionParity();
        testAllThrowForLetParity();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
