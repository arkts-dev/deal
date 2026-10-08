package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.jvm.JvmNames;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.FunctionId;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ISSUE-0731 acceptance remediation: the nested recursive-group member's body
 * invocation identity and return cell ({@code
 * residual-carrier-shapes-production-realization} D3 and the local and async
 * declared-function contract; {@code
 * luajit-jvm-single-lowering-production-cutover} C10/K12; {@code
 * dispatched-corpus-production-realization} R4 item 2).
 *
 * <p>A recursive group declared inside a function body is a nested group:
 * every member owns its body block, its {@code FunctionId}, its single return
 * boundary, and its reserved invocation identity, all materialized before any
 * member body walks. The reported defect was that {@code lowerGroup} reserved
 * and pushed a {@code FunctionContext} only for module-level members, so an
 * explicit {@code return} in a nested member lowered against the
 * <em>enclosing</em> function's context: the RETURN op named the enclosing
 * function while its block belonged to the member, and
 * {@code ControlFlowValidator.checkReturnTarget} rejected it with E6005
 * {@code CONTROL_EXIT}. A sibling direct call also failed closed because the
 * member had no registered context.</p>
 *
 * <p>Three measured surfaces per case:</p>
 *
 * <ol>
 *   <li><b>The release-owned production invocation.</b> The program compiles
 *       on the LuaJIT lane and the JVM lane with zero E6005 (no
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}), emits exactly one project artifact,
 *       and stages it; the staged JVM artifact compiles under {@code javac
 *       --release 25 -proc:none}.</li>
 *   <li><b>The lowered unit's member ownership.</b> Exactly one
 *       {@code RECURSIVE_GROUP_INIT} publishes the members; every member is a
 *       lowered function of the unit with its own body block; every RETURN op
 *       in a member names that member (never the enclosing function) and
 *       names exactly one return-boundary op; the enclosing function carries
 *       exactly one RETURN op. The member's reserved invocation identity is
 *       materialized by the group op when the group is never called, and by
 *       the member's first CALL when it is.</li>
 *   <li><b>The three-consumer differential matrix.</b> The semantic oracle,
 *       the shared LuaJIT artifact, and the shared JVM artifact execute the
 *       program to the pinned success terminal and the pinned console effects
 *       and agree event-for-event. A recursively re-entered call op produces
 *       the harness's two known re-entry pairing notices on each consumer (the
 *       accepted recursion shape of the direct-recursion fixtures); every
 *       other failure fails the case.</li>
 * </ol>
 */
public class NestedGroupProductionTest {

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

    private static final String DEAL_JSON =
        "{\n  \"languageVersion\": \"1.2\",\n  \"moduleRoots\": [\"src\"],\n"
            + "  \"output\": \"out\",\n  \"backend\": \"luajit\"\n}\n";

    /** The release-owned production invocation of the compile path. */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /**
     * One nested-group program: its name, its source, the pinned console
     * effect texts, whether its execution recursively re-enters a call op,
     * whether the group is called from the enclosing body, and whether the
     * member returns are the implicit trailing null returns (rather than
     * explicit source returns).
     */
    private record Case(String name, String source, List<String> effects,
                        boolean recursive, boolean called, boolean implicitReturns) {
    }

    /** The called, mutually recursive, explicit-return group (a computed value). */
    private static final Case CALLED_EXPLICIT = new Case(
        "called-explicit",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          function g(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + f(n - 1);
          }
          if (f(4) === 10) {
            console.log("nested-group-called-explicit-ok");
          } else {
            console.log("nested-group-called-explicit-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-called-explicit-ok"), true, true, false);

    /** The called, mutually recursive, implicit-null-return group. */
    private static final Case CALLED_IMPLICIT = new Case(
        "called-implicit",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): null {
            if (n > 0) {
              g(n - 1);
            }
          }
          function g(n: int): null {
            if (n > 1) {
              f(n - 1);
            }
          }
          f(4);
          console.log("nested-group-called-implicit-ok");
          return null;
        }
        """,
        List.of("nested-group-called-implicit-ok"), true, true, true);

    /** The called group whose SCC edge is a value reference, not a call. */
    private static final Case CALLED_VALUE_REFERENCE = new Case(
        "called-value-reference",
        """
        import * as console from "std/console"

        export function main(): null {
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return n;
          }
          if (f(4) === 7) {
            console.log("nested-group-called-value-reference-ok");
          } else {
            console.log("nested-group-called-value-reference-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-called-value-reference-ok"), false, true, false);

    /** The review's seed: a never-called nested group with explicit null returns. */
    private static final Case NEVER_CALLED_EXPLICIT_NULL = new Case(
        "never-called-explicit-null",
        """
        export function main(): null {
          function f(): null {
            let gRef: () => null = g;
            return null;
          }
          function g(): null {
            let fRef: () => null = f;
            return null;
          }
          return null;
        }
        """,
        List.of(), false, false, false);

    /** A never-called nested group with explicit non-null returns. */
    private static final Case NEVER_CALLED_EXPLICIT_INT = new Case(
        "never-called-explicit-int",
        """
        export function main(): null {
          function f(): int {
            let gRef: () => int = g;
            return 0;
          }
          function g(): int {
            let fRef: () => int = f;
            return 1;
          }
          return null;
        }
        """,
        List.of(), false, false, false);

    /** A never-called nested group with implicit trailing null returns. */
    private static final Case NEVER_CALLED_IMPLICIT = new Case(
        "never-called-implicit",
        """
        export function main(): null {
          function f(): null {
            let gRef: () => null = g;
          }
          function g(): null {
            let fRef: () => null = f;
          }
          return null;
        }
        """,
        List.of(), false, false, true);

    /** A called nested group whose members capture an enclosing local. */
    private static final Case CAPTURED_CALLED = new Case(
        "captured-called",
        """
        import * as console from "std/console"

        export function main(): null {
          let base: int = 10;
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            return g(n - 1);
          }
          function g(n: int): int {
            return f(n);
          }
          if (f(3) === 10) {
            console.log("nested-group-captured-ok");
          } else {
            console.log("nested-group-captured-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-captured-ok"), true, true, false);

    /** A called nested group declared inside a nested function declaration. */
    private static final Case NESTED_INSIDE_FUNCTION = new Case(
        "nested-inside-function",
        """
        import * as console from "std/console"

        export function main(): null {
          function outer(): int {
            function f(n: int): int {
              if (n <= 0) {
                return 0;
              }
              return g(n - 1);
            }
            function g(n: int): int {
              return f(n);
            }
            return f(2);
          }
          if (outer() === 0) {
            console.log("nested-group-inside-function-ok");
          } else {
            console.log("nested-group-inside-function-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-inside-function-ok"), true, true, false);

    private static final List<Case> CASES = List.of(
        CALLED_EXPLICIT, CALLED_IMPLICIT, CALLED_VALUE_REFERENCE,
        NEVER_CALLED_EXPLICIT_NULL, NEVER_CALLED_EXPLICIT_INT, NEVER_CALLED_IMPLICIT,
        CAPTURED_CALLED, NESTED_INSIDE_FUNCTION);

    // =========================================================================
    // The case driver
    // =========================================================================

    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries,
                           LoweredModuleUnit unit, StructuredBodyTable table) {
    }

    private static void runCase(Case testCase) throws Exception {
        System.out.println("-- " + testCase.name() + " --");
        Path project = Files.createTempDirectory("nested-group-" + testCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), testCase.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            // Surface 1: both release-owned production lanes.
            for (String lane : List.of("luajit", "jvm")) {
                compileLane(testCase, project, entry, lane);
            }

            // Surface 2: the one lowering and the member ownership.
            Lowered lowered = lower(testCase, project, entry);
            if (lowered == null) {
                return;
            }

            // Surface 3: the three-consumer differential matrix.
            differential(testCase, lowered);
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // Surface 1: the release-owned production invocation on both lanes
    // =========================================================================

    private static void compileLane(Case testCase, Path project, Path entry,
                                    String lane) throws Exception {
        String outName = "out-" + lane;
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides(lane, project.resolve(outName).toString()));
        check(located.context() != null, testCase.name() + " [" + lane
            + "]: the generated deal.json locates strictly");
        if (located.context() == null) {
            return;
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entry, false, false, false, false, null,
            productionInvocation());
        boolean compiled = orchestrator.compile();
        List<String> errors = new ArrayList<>();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                errors.add(diagnostic.code() + ": " + diagnostic.message());
            }
        }
        boolean e6005 = errors.stream().anyMatch(error -> error.startsWith("E6005"));
        check(compiled && !e6005, testCase.name() + " [" + lane
            + "]: zero E6005 over the release-owned production invocation: " + errors);
        check(orchestrator.semanticEmissionCount() == 1, testCase.name() + " [" + lane
            + "]: exactly one project artifact: semantic="
            + orchestrator.semanticEmissionCount());
        if (!compiled) {
            return;
        }
        Path out = project.resolve(outName);
        if ("luajit".equals(lane)) {
            check(Files.isRegularFile(out.resolve("main.lua")), testCase.name()
                + " [luajit]: the project artifact is staged: " + out.resolve("main.lua"));
        } else {
            Path artifact = out.resolve(JvmNames.classNameFor("main") + ".java");
            check(Files.isRegularFile(artifact), testCase.name()
                + " [jvm]: the project artifact is staged: " + artifact);
            if (Files.isRegularFile(artifact)) {
                compileStagedJvm(testCase, out, artifact);
            }
        }
    }

    /** The parent epic's JVM criterion on the staged artifact. */
    private static void compileStagedJvm(Case testCase, Path out, Path artifact)
            throws Exception {
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString(),
            artifact.toString());
        javac.directory(out.toFile());
        javac.redirectErrorStream(true);
        Process compile = javac.start();
        String compileOut = new String(compile.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = compile.waitFor();
        check(exit == 0, testCase.name() + " [jvm]: the staged artifact compiles under "
            + "javac --release 25 -proc:none: " + compileOut);
    }

    // =========================================================================
    // Surface 2: the one lowering and the member ownership
    // =========================================================================

    private static Lowered lower(Case testCase, Path project, Path entry)
            throws Exception {
        Path oracleOut = project.resolve("out-oracle");
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides("luajit", oracleOut.toString()));
        check(located.context() != null, testCase.name()
            + ": the oracle closure locates strictly");
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
                && manifests.manifests() != null,
            testCase.name() + ": the oracle closure compiles: "
                + (built == null ? "no checked project" : built.diagnostics()));
        if (built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null
                || manifests.manifests() == null) {
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
        check(result.project() != null && result.diagnostics().isEmpty(),
            testCase.name() + ": the nested group lowers with zero diagnostics (no "
                + "CONSTRUCT_UNLOWERED/CONTROL_EXIT): " + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId entryModule = result.project().entryModule();
        LoweredModuleUnit unit = result.project().modules().get(entryModule);
        StructuredBodyTable table = result.tables().get(entryModule);
        check(unit != null && table != null, testCase.name()
            + ": the lowering produced the entry unit and its block table");
        if (unit == null || table == null) {
            return null;
        }
        checkMemberOwnership(testCase, unit, table);
        return new Lowered(result.project(), result.tables(), result.registries(),
            unit, table);
    }

    /**
     * The member ownership battery: the group publication, the per-member
     * return cell, and the per-member invocation identity.
     */
    private static void checkMemberOwnership(Case testCase, LoweredModuleUnit unit,
                                             StructuredBodyTable table) {
        List<SemanticOp> groupOps = new ArrayList<>();
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            byId.put(op.opId(), op);
            if (op.kind() == SemanticOpKind.RECURSIVE_GROUP_INIT) {
                groupOps.add(op);
            }
        }
        check(groupOps.size() == 1, testCase.name()
            + ": exactly one RECURSIVE_GROUP_INIT publishes the nested group; got "
            + groupOps.size());
        if (groupOps.size() != 1) {
            return;
        }
        SemanticOp groupOp = groupOps.get(0);
        if (!(groupOp.payload()
                instanceof KindPayload.RecursiveGroupInitPayload group)) {
            fail(testCase.name() + ": the group op carries the group payload");
            return;
        }
        check(group.functions().size() == 2 && group.bindings().size() == 2,
            testCase.name() + ": the group op publishes both members (functions="
                + group.functions() + " bindings=" + group.bindings() + ")");

        FunctionId enclosing = null;
        for (FunctionId function : unit.functions().keySet()) {
            if (!group.functions().contains(function)) {
                enclosing = function;
            }
        }
        check(enclosing != null, testCase.name()
            + ": the enclosing function is a lowered function of the unit");
        if (enclosing == null) {
            return;
        }

        Map<FunctionId, List<SemanticOp>> returnsByFunction = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.RETURN) {
                continue;
            }
            KindPayload.ReturnPayload returned = (KindPayload.ReturnPayload) op.payload();
            returnsByFunction.computeIfAbsent(returned.function(),
                ignored -> new ArrayList<>()).add(op);
        }
        // The defect's exact signature: a member's explicit return named the
        // enclosing function, so the enclosing function carried the members'
        // RETURN ops. A correct lowering keeps exactly one RETURN under the
        // enclosing function.
        int enclosingReturns = returnsByFunction
            .getOrDefault(enclosing, List.of()).size();
        check(enclosingReturns == 1, testCase.name()
            + ": exactly one RETURN op names the enclosing function (the members' "
            + "returns never do); got " + enclosingReturns);

        for (FunctionId member : group.functions()) {
            LoweredFunction lowered = unit.functions().get(member);
            check(lowered != null, testCase.name() + ": member " + member
                + " is a lowered function of the unit");
            if (lowered == null) {
                continue;
            }
            List<SemanticOp> memberReturns = returnsByFunction.getOrDefault(member,
                List.of());
            check(!memberReturns.isEmpty(), testCase.name() + ": member " + member
                + " owns at least one RETURN op");
            Set<OpId> boundaries = new LinkedHashSet<>();
            for (SemanticOp op : memberReturns) {
                KindPayload.ReturnPayload returned =
                    (KindPayload.ReturnPayload) op.payload();
                boundaries.add(returned.returnBoundaryOpId());
                check(byId.containsKey(returned.returnBoundaryOpId()), testCase.name()
                    + ": member " + member + "'s return boundary "
                    + returned.returnBoundaryOpId() + " is an op of the unit");
                check(table.opBlocks().get(op.opId()) != null, testCase.name()
                    + ": member " + member + "'s RETURN " + op.opId()
                    + " is a member of a block");
                if (testCase.implicitReturns()) {
                    check(op.origin().kind() == SourceOriginKind.SYNTHETIC,
                        testCase.name() + ": member " + member
                            + "'s implicit return is the synthetic trailing return");
                } else {
                    check(op.origin().kind() == SourceOriginKind.USER, testCase.name()
                        + ": member " + member
                        + "'s explicit return carries the source origin");
                }
                if (testCase.called()) {
                    SemanticOp invocation = byId.get(returned.enclosingInvocationOpId());
                    check(invocation != null
                            && invocation.kind() == SemanticOpKind.CALL
                            && isCallOf(invocation, member),
                        testCase.name() + ": member " + member
                            + "'s invocation identity is its own CALL op; got "
                            + returned.enclosingInvocationOpId());
                } else {
                    check(returned.enclosingInvocationOpId().equals(groupOp.opId()),
                        testCase.name() + ": the never-called member " + member
                            + "'s invocation identity is materialized by the group op "
                            + groupOp.opId() + "; got "
                            + returned.enclosingInvocationOpId());
                }
            }
            check(boundaries.size() == 1, testCase.name() + ": member " + member
                + " owns exactly one return boundary; got " + boundaries);
        }
    }

    /** Whether one CALL op statically binds the given body. */
    private static boolean isCallOf(SemanticOp callOp, FunctionId member) {
        if (!(callOp.payload() instanceof KindPayload.CallPayload payload)) {
            return false;
        }
        if (!(payload.callee() instanceof KindPayload.CallCallee.Static stat)) {
            return false;
        }
        return stat.binding() instanceof FunctionExecutionBinding.LoweredBody body
            && body.functionId().equals(member);
    }

    // =========================================================================
    // Surface 3: the three-consumer differential matrix
    // =========================================================================

    private static void differential(Case testCase, Lowered lowered) throws Exception {
        Path workspace = Files.createTempDirectory(
            "nested-group-matrix-" + testCase.name() + "-");
        try {
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(lowered.project(),
                    lowered.tables(), lowered.registries(),
                    SemanticDifferentialHarness.Expectation.success(testCase.name(),
                        testCase.effects(), "null"),
                    workspace);
            check(verdict.runs().size() == 3, testCase.name()
                + ": the differential matrix produced the three consumers: "
                + verdict.failures());
            List<String> unexplained = new ArrayList<>();
            for (String failure : verdict.failures()) {
                if (!isRecursivePairingNotice(failure)) {
                    unexplained.add(failure);
                }
            }
            String allowed = testCase.recursive()
                ? " (only the recursive re-entry pairing notices allowed)"
                : "";
            check(unexplained.isEmpty(), testCase.name() + ": the oracle and both "
                + "shared artifacts agree event-for-event with the pinned terminal "
                + "and effects" + allowed + ": " + unexplained);
            if (!testCase.recursive()) {
                check(verdict.pass(), testCase.name()
                    + ": a non-recursive case carries no accepted failure: "
                    + verdict.failures());
            }
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success success
                        && "null".equals(success.resultAtom()),
                    testCase.name() + " [" + run.consumer()
                        + "]: the pinned success terminal; got " + run.terminal());
                List<String> texts = new ArrayList<>();
                for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
                    texts.add(effect.text());
                }
                check(texts.equals(testCase.effects()), testCase.name() + " ["
                    + run.consumer() + "]: the pinned console effects " + testCase.effects()
                    + "; got " + texts);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /**
     * The harness's two re-entry pairing notices: a recursively re-entered
     * call op starts again before its previous terminal and therefore also
     * terminates without a matching open START. The direct-recursion fixtures
     * carry exactly this accepted shape; every other failure is a real
     * disagreement.
     */
    private static boolean isRecursivePairingNotice(String failure) {
        return failure.contains("starts again before its previous terminal")
            || failure.contains("terminates without an open START");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    public static void main(String[] args) throws Exception {
        System.out.println("=== Nested Group Production Test (ISSUE-0731) ===\n");
        for (Case testCase : CASES) {
            runCase(testCase);
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
