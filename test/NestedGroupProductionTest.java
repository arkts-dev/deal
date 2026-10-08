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
import deal.semantic.ControlFlowValidator;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.FunctionAllocationIdentity;
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
 * boundary, its reserved invocation identity, and its resolvable execution
 * binding, all materialized before any member body walks. The reported defect
 * was that {@code lowerGroup} reserved and pushed a {@code FunctionContext}
 * only for module-level members, so an explicit {@code return} in a nested
 * member lowered against the <em>enclosing</em> function's context: the RETURN
 * op named the enclosing function while its block belonged to the member, and
 * {@code ControlFlowValidator.checkReturnTarget} rejected it with E6005
 * {@code CONTROL_EXIT}. A sibling direct call also failed closed because the
 * member had no registered context.</p>
 *
 * <p>The review's follow-up defect was that each member's
 * {@code LoweredBody} execution binding was registered only after every member
 * body walk, so a sibling reference through an ordinary function value (a
 * {@code let} alias, then a call of that alias) still failed closed with E6005
 * {@code CONSTRUCT_UNLOWERED} inside the member walk. The member's execution
 * binding is now registered in the preallocation phase — exactly once per
 * pre-assigned member identity, before the first member body walk — and the
 * member's {@code LoweredFunction} record is finalized the moment its own walk
 * ends, so an alias invocation resolved while the target's capture list is
 * still open is classified value-carried (the alias holds the member's
 * published closure, whose creation-site captures travel with the carrier).
 * Every member of a size&gt;=2 reference SCC references a sibling, so a
 * member's final capture list is never empty and the pending classification
 * is the exact one.</p>
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
 *   <li><b>The called sibling alias.</b> A member invoked through an ordinary
 *       function-value alias (nested, capture-carrying, later-declared target,
 *       and module-level variants) compiles on both production lanes, its
 *       invocation identity is materialized by the alias's value-carried call,
 *       and the three consumers agree with the pinned terminal and effects.</li>
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
     * whether each member (in declaration order) is invoked by a call site,
     * whether the member returns are the implicit trailing null returns
     * (rather than explicit source returns), and whether the source invokes
     * a member through an ordinary function-value alias.
     */
    private record Case(String name, String source, List<String> effects,
                        boolean recursive, List<Boolean> memberCalled,
                        boolean implicitReturns, boolean aliasInvocation) {
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
        List.of("nested-group-called-explicit-ok"), true, List.of(true, true), false,
        false);

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
        List.of("nested-group-called-implicit-ok"), true, List.of(true, true), true,
        false);

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
        List.of("nested-group-called-value-reference-ok"), false, List.of(true, true),
        false, false);

    /**
     * The review's called sibling-alias seed: {@code g} invokes {@code f}
     * through an ordinary function-value alias ({@code let fRef = f}), so
     * {@code f}'s reserved invocation identity is materialized by the
     * alias's value-carried call inside {@code g}'s body. Before the fix,
     * the member's execution binding was registered only after every
     * member body walk, so this checker-valid shape failed closed with
     * E6005 {@code CONSTRUCT_UNLOWERED}.
     */
    private static final Case CALLED_ALIAS = new Case(
        "called-alias",
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
            return fRef(n);
          }
          if (f(4) === 10) {
            console.log("nested-group-alias-ok");
          } else {
            console.log("nested-group-alias-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-alias-ok"), true, List.of(true, true), false, true);

    /**
     * The capture-carrying sibling alias: the aliased member reads an
     * enclosing local, so the alias invocation must stay value-carried —
     * the closure carrier holds the creation-site captured cell (the
     * invocation itself proves the value the alias holds reaches the
     * member body; the captured-ok terminal is only produced when the
     * captured {@code base} is observed).
     */
    private static final Case CALLED_ALIAS_CAPTURED = new Case(
        "called-alias-captured",
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
            let fRef: (n: int) => int = f;
            return fRef(n);
          }
          if (f(3) === 10) {
            console.log("nested-group-alias-captured-ok");
          } else {
            console.log("nested-group-alias-captured-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-alias-captured-ok"), true, List.of(true, true), false,
        true);

    /**
     * The sibling alias whose target is declared <em>after</em> the
     * calling member: the target's capture list is not final while the
     * caller's body walk is open, so the pending classification must
     * already be value-carried (the alias holds the member's published
     * closure).
     */
    private static final Case CALLED_ALIAS_LATER = new Case(
        "called-alias-later",
        """
        import * as console from "std/console"

        export function main(): null {
          function g(n: int): int {
            let fRef: (n: int) => int = f;
            return fRef(n);
          }
          function f(n: int): int {
            if (n <= 0) {
              return 0;
            }
            return n + g(n - 1);
          }
          if (f(4) === 10) {
            console.log("nested-group-alias-later-ok");
          } else {
            console.log("nested-group-alias-later-bad");
          }
          return null;
        }
        """,
        List.of("nested-group-alias-later-ok"), true, List.of(true, true), false, true);

    /**
     * The module-level sibling alias: the same SCC-through-a-value shape at
     * module scope, where the group lowers at module-init top before the
     * member bodies walk. The module-level member had the identical
     * missing-registration defect before the fix.
     */
    private static final Case MODULE_GROUP_ALIAS = new Case(
        "module-group-alias",
        """
        import * as console from "std/console"

        function f(n: int): int {
          if (n <= 0) {
            return 0;
          }
          return n + g(n - 1);
        }
        function g(n: int): int {
          let fRef: (n: int) => int = f;
          return fRef(n);
        }

        export function main(): null {
          if (f(4) === 10) {
            console.log("module-group-alias-ok");
          } else {
            console.log("module-group-alias-bad");
          }
          return null;
        }
        """,
        List.of("module-group-alias-ok"), true, List.of(true, true), false, true);

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
        List.of(), false, List.of(false, false), false, false);

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
        List.of(), false, List.of(false, false), false, false);

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
        List.of(), false, List.of(false, false), true, false);

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
        List.of("nested-group-captured-ok"), true, List.of(true, true), false,
        false);

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
        List.of("nested-group-inside-function-ok"), true, List.of(true, true), false,
        false);

    private static final List<Case> CASES = List.of(
        CALLED_EXPLICIT, CALLED_IMPLICIT, CALLED_VALUE_REFERENCE,
        CALLED_ALIAS, CALLED_ALIAS_CAPTURED, CALLED_ALIAS_LATER, MODULE_GROUP_ALIAS,
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
        // The exact rule that rejected the pre-fix unit: a member's RETURN
        // named the enclosing function while its block belonged to the member
        // (CONTROL_EXIT).
        java.util.Optional<CompilerDiagnostic> admission =
            ControlFlowValidator.validate(unit, table);
        check(admission.isEmpty(), testCase.name()
            + ": the control-flow validator admits the nested members: " + admission);
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

        boolean hasEnclosing = unit.functions().keySet().stream()
            .anyMatch(function -> !group.functions().contains(function));
        check(hasEnclosing, testCase.name()
            + ": the enclosing function is a lowered function of the unit");

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
        // RETURN ops. Every non-member function still owns a return op, and
        // the validator above rejects any RETURN whose block is not rooted at
        // the named function's body.
        for (FunctionId function : unit.functions().keySet()) {
            if (group.functions().contains(function)) {
                continue;
            }
            check(!returnsByFunction.getOrDefault(function, List.of()).isEmpty(),
                testCase.name() + ": the non-member function " + function
                    + " owns a RETURN op");
        }

        for (int memberIndex = 0; memberIndex < group.functions().size();
                memberIndex++) {
            FunctionId member = group.functions().get(memberIndex);
            boolean memberCalled = memberIndex < testCase.memberCalled().size()
                && Boolean.TRUE.equals(testCase.memberCalled().get(memberIndex));
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
                if (memberCalled) {
                    SemanticOp invocation = byId.get(returned.enclosingInvocationOpId());
                    check(invocation != null
                            && invocation.kind() == SemanticOpKind.CALL
                            && isCallOf(invocation, member, unit),
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
        if (testCase.aliasInvocation()) {
            checkAliasInvocation(testCase, unit, group);
        }
    }

    /**
     * The sibling-alias battery: a call op resolves a member through an
     * ordinary function value (the {@code Indirect} callee shape — the
     * value-carried invocation), and the target member carries creation-site
     * captures (the group's sibling cells), which is exactly why the
     * invocation is value-carried rather than re-resolved at the call site.
     */
    private static void checkAliasInvocation(Case testCase, LoweredModuleUnit unit,
                                             KindPayload.RecursiveGroupInitPayload group) {
        int aliasCalls = 0;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.CALL
                    || !(op.payload() instanceof KindPayload.CallPayload payload)
                    || !(payload.callee()
                        instanceof KindPayload.CallCallee.Indirect indirect)) {
                continue;
            }
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new FunctionAllocationIdentity(indirect.callee().id()));
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)
                    || !group.functions().contains(body.functionId())) {
                continue;
            }
            aliasCalls++;
            LoweredFunction target = unit.functions().get(body.functionId());
            check(target != null && !target.captures().isEmpty(), testCase.name()
                + ": the sibling alias to member " + body.functionId()
                + " is value-carried with the target's creation-site captures; got "
                + (target == null ? "no lowered function" : target.captures()));
        }
        check(aliasCalls >= 1, testCase.name() + ": the sibling alias materializes a "
            + "member invocation through the value-carried callee shape; got "
            + aliasCalls);
    }

    /** Whether one CALL op resolves to a call of the given body. */
    private static boolean isCallOf(SemanticOp callOp, FunctionId member,
                                    LoweredModuleUnit unit) {
        if (!(callOp.payload() instanceof KindPayload.CallPayload payload)) {
            return false;
        }
        FunctionExecutionBinding binding = switch (payload.callee()) {
            case KindPayload.CallCallee.Static stat -> stat.binding();
            case KindPayload.CallCallee.Indirect indirect -> unit.functionBindings().get(
                new FunctionAllocationIdentity(indirect.callee().id()));
            case KindPayload.CallCallee.Dynamic ignored -> null;
        };
        return binding instanceof FunctionExecutionBinding.LoweredBody body
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
