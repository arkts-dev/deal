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
import deal.semantic.ir.AsyncTokenId;
import deal.semantic.ir.AsyncTokenOwner;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.CallMode;
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
import deal.semantic.ir.StructuredBodyTable;

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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ISSUE-0743 acceptance remediation of ISSUE-0699: the declared body's
 * self-alias invocation ({@code
 * residual-carrier-shapes-production-realization} D3 and the local declared
 * and C10/K12 body-coverage contracts; {@code
 * luajit-jvm-single-lowering-production-cutover} C10).
 *
 * <p>A checker-valid self-reference of a non-group declared function — the
 * declaration invoked through an ordinary function value ({@code let again:
 * (n: int) =&gt; int = f; return again(n - 1);}) and its awaited twin ({@code
 * let again: async (n: int) =&gt; int = f; return await again(n - 1);}) — must
 * resolve to the declaration's pre-allocated body identity. The reported
 * defect was that {@code lowerBindingFunctionDecl} allocated the context and
 * closure identity before walking the body but registered the
 * {@code LoweredBody} execution binding only at the closing
 * {@code emitClosureNew}, after that walk; the alias load preserved {@code
 * f}'s identity while the registry had no registration yet, so the lowering
 * failed closed with E6005 {@code CONSTRUCT_UNLOWERED} on both the
 * synchronous call and the await guard. The local and module-level
 * declaration arms both register the body's execution binding before the
 * body walk now, and the pending body's value-carried classification keeps
 * the alias's creation-site captures instead of reconstructing the target's
 * factory at the call site.</p>
 *
 * <p>Per case, the measured surfaces:</p>
 *
 * <ol>
 *   <li><b>The release-owned production lanes.</b> The program compiles on
 *       the LuaJIT and the JVM lane with zero E6005 (no
 *       {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 *       {@code SHARED_EMITTER_COVERAGE}), emits exactly one project artifact,
 *       stages it, and the staged JVM artifact compiles under {@code javac
 *       --release 25 -proc:none}.</li>
 *   <li><b>The pre-registered body ownership.</b> Exactly one
 *       {@code CLOSURE_NEW} materializes the declaration's pre-allocated
 *       allocation identity, that identity carries exactly one
 *       {@code LoweredBody} registration whose {@code functionId}/{@code
 *       bodyBlock} match the closing closure, and the self-alias call is the
 *       value-carried {@code CALL(INDIRECT)} whose callee value is that same
 *       allocation identity and whose recorded return boundary is the body's
 *       single return cell. The nested cases' declaring body captures an
 *       enclosing cell, so the creation-site carrier — not a call-site
 *       factory reconstruction — is the load-bearing shape there.</li>
 *   <li><b>The three-consumer differential matrix.</b> The semantic oracle,
 *       the shared LuaJIT artifact, and the shared JVM artifact execute the
 *       program to the pinned success terminal and the pinned console
 *       effects and agree event-for-event.</li>
 *   <li><b>The staged production drive.</b> The staged LuaJIT chunk and the
 *       staged JVM class execute the case's own entry surface (the sync
 *       {@code main}, or the async export through the production
 *       async-entry surface) and publish the pinned effects and result.</li>
 * </ol>
 *
 * <p>ISSUE-0762 adds the accumulating recursion pairs: a non-exported async
 * declaration whose completion accumulates across the recursion ({@code
 * let v: int = await f(n - 1); return n + v;}), reached by the direct
 * declared name and through the declaration's typed alias, plus the
 * mid-level failure-propagation twins (the second activation fails with
 * E8005 after its await, so the outer awaits must not read a nested
 * activation's completion). Each awaited invocation keeps its canonical IR token
 * identity, owner, and return cell; every activation runs its own task
 * instance, so the outer await resolves its own completion (the pre-fix
 * artifact yielded the leaf value 3 instead of the pinned 6, and swallowed
 * the mid-level failure). The regression battery is the same four
 * surfaces: both production lanes, the one lowering's canonical identity,
 * the three-consumer differential matrix (the oracle and both real
 * toolchains), and the staged production drives.</p>
 *
 * <p>ISSUE-0762 review cycle 1 adds the consumed-activation release battery:
 * the per-activation task record is the single AWAIT's to consume, so a
 * repeated sequential await of one local body start (success and failure
 * paths) must retain no completed record. A frozen-JVM-runtime drive of
 * 10,000 successful and 10,000 failing sequential awaits counts the
 * runtime's canonical registry; the sequential-await case then counts the
 * oracle's own execution state and both staged artifacts' registries after
 * a 100-iteration loop with a caught mid-level E8005. The recursive
 * accumulation and failure-propagation cases stay in place, so activation
 * isolation and release are proven together.</p>
 */
public class SelfAliasProductionTest {

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
     * One self-alias program: the source, the pinned console effects, the
     * driven async export (null for the sync-entry cases), the pinned result
     * atom of the differential terminal, whether the staged production
     * artifacts drive the sync entry, and whether the declaring body captures
     * an enclosing cell (the nested cases; a module-level body captures
     * module-level cells only).
     */
    private record Case(String name, String source, List<String> effects,
                        String asyncExport, String resultAtom, boolean stagedSync,
                        boolean expectCaptures) {

        Case {
            effects = List.copyOf(effects);
        }

        /** The sync-entry case: the {@code main} drive and the null terminal. */
        private Case(String name, String source, List<String> effects,
                     boolean expectCaptures) {
            this(name, source, effects, null, "null", true, expectCaptures);
        }

        /** The async-entry case: the driven export, no sync staged drive. */
        private Case(String name, String source, List<String> effects,
                     String asyncExport, String resultAtom, boolean expectCaptures) {
            this(name, source, effects, asyncExport, resultAtom, false, expectCaptures);
        }
    }

    /**
     * The nested local declaration invoked through its own alias: {@code f}
     * is declared inside {@code main}, captures {@code base}, and recurses
     * through {@code again}. Before the fix this checker-valid program failed
     * closed with E6005 {@code CONSTRUCT_UNLOWERED} ("callee 'again' ...
     * has no registered FunctionExecutionBinding").
     */
    private static final Case NESTED_SYNC = new Case(
        "nested-sync",
        """
        import * as console from "std/console"

        export function main(): null {
          let base: int = 5;
          function f(n: int): int {
            if (n <= 0) {
              return base;
            }
            let again: (n: int) => int = f;
            return n + again(n - 1);
          }
          if (f(4) === 15) {
            console.log("self-alias-nested-sync-ok");
          } else {
            console.log("self-alias-nested-sync-bad");
          }
          return null;
        }
        """,
        List.of("self-alias-nested-sync-ok"), true);

    /**
     * The module-level declaration invoked through its own alias: {@code f}
     * is hoisted at module scope and recurses through {@code again}. The
     * module-level declaration arm had the identical late-registration
     * defect. (A module top level admits no {@code let}, so this body
     * captures no enclosing cell; the pre-registered identity is still the
     * load-bearing resolution.)
     */
    private static final Case MODULE_SYNC = new Case(
        "module-sync",
        """
        import * as console from "std/console"

        function f(n: int): int {
          if (n <= 0) {
            return 5;
          }
          let again: (n: int) => int = f;
          return n + again(n - 1);
        }

        export function main(): null {
          if (f(4) === 15) {
            console.log("self-alias-module-sync-ok");
          } else {
            console.log("self-alias-module-sync-bad");
          }
          return null;
        }
        """,
        List.of("self-alias-module-sync-ok"), false);

    /**
     * The awaited self-alias of a nested async declaration: the equivalent
     * shape reaches the await arm's missing-registration guard (E6005
     * "await callee 'again' ... has no registered FunctionExecutionBinding").
     * The leaf publishes its own effect, so the pinned effect order proves
     * the awaited recursion reached the captured-cell leaf; the completion
     * value travels back through the value-carried body tasks to the driven
     * export.
     */
    private static final Case NESTED_ASYNC = new Case(
        "nested-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let base: int = 5;
          async function f(n: int): int {
            if (n <= 0) {
              console.log("self-alias-nested-async-leaf");
              return base;
            }
            let again: async (n: int) => int = f;
            return await again(n - 1);
          }
          let v: int = await f(4);
          if (v === 5) {
            console.log("self-alias-nested-async-ok");
          } else {
            console.log("self-alias-nested-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("self-alias-nested-async-leaf", "self-alias-nested-async-ok"),
        "drive", "int:5", true);

    /** The awaited self-alias of a module-level async declaration. */
    private static final Case MODULE_ASYNC = new Case(
        "module-async",
        """
        import * as console from "std/console";

        async function f(n: int): int {
          if (n <= 0) {
            console.log("self-alias-module-async-leaf");
            return 5;
          }
          let again: async (n: int) => int = f;
          return await again(n - 1);
        }

        export async function drive(): int {
          let v: int = await f(4);
          if (v === 5) {
            console.log("self-alias-module-async-ok");
          } else {
            console.log("self-alias-module-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("self-alias-module-async-leaf", "self-alias-module-async-ok"),
        "drive", "int:5", false);

    /**
     * The accumulating direct-name recursion of a non-exported async
     * declaration: the leaf returns 0 and every activation adds its own
     * {@code n}, so a completion replacement (the outer await reading the
     * nested activation's completion through the static call-site token)
     * yields 3 instead of the pinned 6.
     */
    private static final Case DIRECT_ACCUM = new Case(
        "direct-accum",
        """
        import * as console from "std/console";

        async function f(n: int): int {
          if (n <= 0) {
            return 0;
          }
          let v: int = await f(n - 1);
          return n + v;
        }

        export async function drive(): int {
          let v: int = await f(3);
          if (v === 6) {
            console.log("async-recursion-direct-ok");
          } else {
            console.log("async-recursion-direct-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-recursion-direct-ok"), "drive", "int:6", false);

    /**
     * The accumulating typed-alias recursion: the same declaration reached
     * through its own {@code async (n: int) => int} function value, so the
     * awaited invocation is value-carried and each activation's await must
     * still receive its own invocation's completion.
     */
    private static final Case ALIAS_ACCUM = new Case(
        "alias-accum",
        """
        import * as console from "std/console";

        async function f(n: int): int {
          if (n <= 0) {
            return 0;
          }
          let again: async (n: int) => int = f;
          let v: int = await again(n - 1);
          return n + v;
        }

        export async function drive(): int {
          let v: int = await f(3);
          if (v === 6) {
            console.log("async-recursion-alias-ok");
          } else {
            console.log("async-recursion-alias-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-recursion-alias-ok"), "drive", "int:6", false);

    /**
     * The direct-name recursion's mid-level failure propagation: the
     * second activation fails (E8005 {@code integer division by zero})
     * after its own await, so the failure must travel through every
     * enclosing await to the drive's catch. A replaced completion would
     * let the outer awaits read the leaf's successful value and swallow
     * the mid-level failure.
     */
    private static final Case DIRECT_ACCUM_FAIL = new Case(
        "direct-accum-fail",
        """
        import * as console from "std/console";

        async function f(n: int): int {
          if (n <= 0) {
            return 0;
          }
          let v: int = await f(n - 1);
          let q: int = 1 / (n - 2);
          return n + v + q;
        }

        export async function drive(): int {
          try {
            let v: int = await f(3);
            console.log("async-recursion-direct-fail-bad");
            return v;
          } catch (e) {
            console.log("async-recursion-direct-fail:" + e.code);
            return 1;
          }
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-recursion-direct-fail:E8005"), "drive", "int:1", false);

    /**
     * The mid-level failure propagation through the typed alias: the same
     * E8005 at the second activation, awaited through the declaration's
     * {@code async (n: int) => int} function value.
     */
    private static final Case ALIAS_ACCUM_FAIL = new Case(
        "alias-accum-fail",
        """
        import * as console from "std/console";

        async function f(n: int): int {
          if (n <= 0) {
            return 0;
          }
          let again: async (n: int) => int = f;
          let v: int = await again(n - 1);
          let q: int = 1 / (n - 2);
          return n + v + q;
        }

        export async function drive(): int {
          try {
            let v: int = await f(3);
            console.log("async-recursion-alias-fail-bad");
            return v;
          } catch (e) {
            console.log("async-recursion-alias-fail:" + e.code);
            return 1;
          }
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("async-recursion-alias-fail:E8005"), "drive", "int:1", false);

    /**
     * The sequential-await release case: one local declared body start is
     * awaited {@code SEQUENTIAL_ITERATIONS} times in a loop and a second
     * declared body's activation fails with E8005 after it. Every
     * activation consumes its own completion record and releases it, so
     * the loop accumulates values (0+1+...+99 = 4950) without retaining
     * any completed activation record, and the failing activation's
     * record is released on the failure path.
     */
    private static final Case SEQUENTIAL_RELEASE = new Case(
        "seq-await",
        """
        import * as console from "std/console";

        async function step(n: int): int {
          return n;
        }

        async function fail(n: int): int {
          let q: int = 1 / (n - 1);
          return q;
        }

        export async function drive(): int {
          let i: int = 0;
          let total: int = 0;
          while (i < 100) {
            total = total + await step(i);
            i = i + 1;
          }
          try {
            let bad: int = await fail(1);
            console.log("seq-await-fail-bad");
          } catch (e) {
            console.log("seq-await-fail:" + e.code);
          }
          return total;
        }

        export function main(): null {
          return null;
        }
        """,
        List.of("seq-await-fail:E8005"), "drive", "int:4950", false);

    private static final List<Case> CASES =
        List.of(NESTED_SYNC, MODULE_SYNC, NESTED_ASYNC, MODULE_ASYNC,
            DIRECT_ACCUM, ALIAS_ACCUM, DIRECT_ACCUM_FAIL, ALIAS_ACCUM_FAIL,
            SEQUENTIAL_RELEASE);

    /**
     * The accumulating-recursion cases: the awaited invocation keeps its
     * canonical DEAL_BODY_TASK token identity and return cell, and the
     * activation isolation (the distinct completion instance per
     * activation) is proven by the accumulating values and the mid-level
     * failure through the differential matrix and the staged drives.
     */
    private static final Set<String> RECURSION_CASES = Set.of(
        "direct-accum", "alias-accum", "direct-accum-fail", "alias-accum-fail",
        "seq-await");

    /**
     * The consumed-activation release cases: after the drive, every
     * per-activation task record the sequential awaits consumed must be
     * released on the success and failure paths.
     */
    private static final Set<String> RELEASE_CASES = Set.of("seq-await");

    /** The sequential-await release case's loop bound (see the case source). */
    private static final int SEQUENTIAL_ITERATIONS = 100;

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
        Path project = Files.createTempDirectory("self-alias-" + testCase.name() + "-");
        try {
            Path src = project.resolve("src");
            Files.createDirectories(src);
            Files.writeString(src.resolve("main.deal"), testCase.source(),
                StandardCharsets.UTF_8);
            Files.writeString(project.resolve("deal.json"), DEAL_JSON,
                StandardCharsets.UTF_8);
            Path entry = src.resolve("main.deal");

            // Surface 1: both release-owned production lanes.
            CompilationOrchestrator luaCompilation =
                compileLane(testCase, project, entry, "luajit");
            compileLane(testCase, project, entry, "jvm");

            // Surface 2: the one lowering and the pre-registered body.
            Lowered lowered = lower(testCase, luaCompilation);
            if (lowered == null) {
                return;
            }

            // Surface 3: the three-consumer differential matrix.
            differential(testCase, lowered);

            // Surface 4: the staged production artifacts execute the case's
            // own entry surface.
            if (testCase.asyncExport() != null) {
                driveProductionAsync(testCase, project, lowered);
            } else {
                driveProductionSync(testCase, project);
            }

            // Surface 5: the consumed-activation release (the ISSUE-0762
            // review-cycle-1 correction): the oracle's own execution state
            // and both staged artifacts retain no consumed per-activation
            // record after the sequential awaits.
            if (RELEASE_CASES.contains(testCase.name())) {
                checkOracleActivationRelease(testCase, lowered);
                checkStagedActivationRelease(testCase, project, lowered);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    // =========================================================================
    // Surface 1: the release-owned production invocation on both lanes
    // =========================================================================

    private static CompilationOrchestrator compileLane(Case testCase, Path project,
                                                        Path entry, String lane)
            throws Exception {
        String outName = "out-" + lane;
        ProjectLocator.LocateResult located = ProjectLocator.locate(entry.toString(),
            new CliOverrides(lane, project.resolve(outName).toString()));
        check(located.context() != null, testCase.name() + " [" + lane
            + "]: the generated deal.json locates strictly");
        if (located.context() == null) {
            return null;
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
            return orchestrator;
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
        return orchestrator;
    }

    /** The parent epic's JVM criterion on the staged artifact. */
    private static void compileStagedJvm(Case testCase, Path out, Path artifact)
            throws Exception {
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);
        ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
            "-proc:none", "-cp", classes + File.pathSeparator + absoluteClasspath(),
            "-d", classes.toString(), artifact.toString());
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
    // Surface 2: the one lowering and the pre-registered body ownership
    // =========================================================================

    private static Lowered lower(Case testCase, CompilationOrchestrator orchestrator) {
        check(orchestrator != null, testCase.name()
            + ": the oracle closure locates strictly");
        if (orchestrator == null) {
            return null;
        }
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
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
        check(result.project() != null && result.diagnostics().isEmpty(),
            testCase.name() + ": the self-alias program lowers with zero diagnostics "
                + "(no CONSTRUCT_UNLOWERED/CONTROL_EXIT): " + result.diagnostics());
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
        if (RECURSION_CASES.contains(testCase.name())) {
            checkRecursionTaskIdentity(testCase, unit, table);
        } else {
            checkSelfAliasOwnership(testCase, unit, table);
        }
        return new Lowered(result.project(), result.tables(), result.registries(),
            unit, table);
    }

    /**
     * The accumulating-recursion IR battery: the control-flow validator's
     * admission, exactly one {@code CLOSURE_NEW} per declared function
     * (the reusable body identity), and every awaited invocation of a
     * declared body keeping its canonical {@code DEAL_BODY_TASK} token
     * identity and its recorded return cell equal to the body's own
     * return boundary. The activation isolation is a runtime property,
     * proven by the accumulating values and the mid-level failure through
     * the differential matrix and the staged production drives.
     */
    private static void checkRecursionTaskIdentity(Case testCase,
                                                   LoweredModuleUnit unit,
                                                   StructuredBodyTable table) {
        java.util.Optional<CompilerDiagnostic> admission =
            ControlFlowValidator.validate(unit, table);
        check(admission.isEmpty(), testCase.name()
            + ": the control-flow validator admits the recursion unit: " + admission);
        Map<FunctionId, Integer> closureNews = new LinkedHashMap<>();
        Map<FunctionId, OpId> returnCells = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CLOSURE_NEW
                    && op.payload() instanceof KindPayload.ClosureNewPayload closure) {
                closureNews.merge(closure.function(), 1, Integer::sum);
            }
            if (op.kind() == SemanticOpKind.RETURN
                    && op.payload() instanceof KindPayload.ReturnPayload returned) {
                returnCells.put(returned.function(), returned.returnBoundaryOpId());
            }
        }
        int awaitedDeclaredBodies = 0;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.ASYNC_START
                    || !(op.payload() instanceof KindPayload.AsyncStartPayload started)) {
                continue;
            }
            FunctionExecutionBinding binding = switch (started.callee()) {
                case KindPayload.CallCallee.Static staticCallee -> staticCallee.binding();
                case KindPayload.CallCallee.Indirect indirect -> unit.functionBindings()
                    .get(new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)) {
                continue;
            }
            awaitedDeclaredBodies++;
            check(op.result() instanceof AsyncTokenId.Canonical canonical
                    && canonical.owner() == AsyncTokenOwner.DEAL_BODY_TASK,
                testCase.name() + ": the awaited invocation " + op.opId()
                    + " keeps the canonical DEAL_BODY_TASK token identity; got "
                    + op.result());
            check(started.returnBoundaryOpId() != null && started.returnBoundaryOpId()
                    .equals(returnCells.get(body.functionId())),
                testCase.name() + ": the awaited invocation " + op.opId()
                    + " records the declaring body's own return cell "
                    + returnCells.get(body.functionId()) + "; got "
                    + started.returnBoundaryOpId());
            check(closureNews.getOrDefault(body.functionId(), 0) == 1, testCase.name()
                + ": exactly one CLOSURE_NEW materializes function "
                + body.functionId() + "; got "
                + closureNews.getOrDefault(body.functionId(), 0));
        }
        check(awaitedDeclaredBodies >= 2, testCase.name()
            + ": the recursion unit carries the drive's await and the declaration's "
            + "recursive await; got " + awaitedDeclaredBodies);
    }

    /**
     * The pre-registered body battery: the admission of the control-flow
     * validator, the exactly-once closure creation of the declaration's
     * pre-allocated identity, the single {@code LoweredBody} registration,
     * the value-carried self-alias invocation resolving that identity, and
     * the recorded return boundary equal to the body's own return cell. A
     * duplicate registration would already have failed the lowering (the
     * registry rejects a second registration at registration time), so the
     * registration count is asserted over the produced unit's map.
     */
    private static void checkSelfAliasOwnership(Case testCase, LoweredModuleUnit unit,
                                                StructuredBodyTable table) {
        java.util.Optional<CompilerDiagnostic> admission =
            ControlFlowValidator.validate(unit, table);
        check(admission.isEmpty(), testCase.name()
            + ": the control-flow validator admits the self-alias unit: " + admission);

        Map<FunctionId, Integer> closureNews = new LinkedHashMap<>();
        Map<Long, SemanticOp> closureByResult = new LinkedHashMap<>();
        Map<FunctionId, OpId> returnBoundaries = new LinkedHashMap<>();
        Map<FunctionId, Set<OpId>> declaringBodyInvocations = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CLOSURE_NEW
                    && op.payload() instanceof KindPayload.ClosureNewPayload closure) {
                closureNews.merge(closure.function(), 1, Integer::sum);
                if (op.result() instanceof deal.semantic.ir.ValueId value) {
                    closureByResult.put(value.id(), op);
                }
            }
            if (op.kind() == SemanticOpKind.RETURN
                    && op.payload() instanceof KindPayload.ReturnPayload returned) {
                returnBoundaries.put(returned.function(), returned.returnBoundaryOpId());
                declaringBodyInvocations
                    .computeIfAbsent(returned.function(),
                        ignored -> new java.util.LinkedHashSet<>())
                    .add(returned.enclosingInvocationOpId());
                check(returned.returnBoundaryOpId() != null, testCase.name()
                    + ": the RETURN of function " + returned.function()
                    + " names its return-boundary op");
            }
        }

        // The carrier invocations of a declared body: a value-carried
        // CALL(INDIRECT) (sync) or ASYNC_START(Indirect) (the awaited twin)
        // whose callee value is the declaration's pre-allocated allocation
        // identity and whose recorded return cell is that body's own return
        // boundary. The declaring body's own self-alias invocation is the one
        // whose op is the reserved invocation identity its RETURN ops name
        // (the enclosing direct call is value-carried too now).
        List<SemanticOp> selfAliasInvocations = new ArrayList<>();
        int declaringBodyCarrierInvocations = 0;
        for (SemanticOp op : unit.ops()) {
            KindPayload.CallCallee.Indirect indirect = null;
            OpId invocationReturnBoundary = null;
            BlockId invocationBody = null;
            if (op.kind() == SemanticOpKind.CALL
                    && op.payload() instanceof KindPayload.CallPayload call
                    && call.mode() == CallMode.INDIRECT
                    && call.callee() instanceof KindPayload.CallCallee.Indirect direct) {
                indirect = direct;
                invocationReturnBoundary = call.returnBoundaryOpId();
                invocationBody = call.bodyBlock();
            } else if (op.kind() == SemanticOpKind.ASYNC_START
                    && op.payload() instanceof KindPayload.AsyncStartPayload started
                    && started.callee()
                        instanceof KindPayload.CallCallee.Indirect direct) {
                indirect = direct;
                invocationReturnBoundary = started.returnBoundaryOpId();
            }
            if (indirect == null) {
                continue;
            }
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new FunctionAllocationIdentity(indirect.callee().id()));
            if (!(binding instanceof FunctionExecutionBinding.LoweredBody body)
                    || !closureByResult.containsKey(indirect.callee().id())) {
                continue;
            }
            if (invocationBody != null && !invocationBody.equals(body.blockId())) {
                continue;
            }
            selfAliasInvocations.add(op);
            if (declaringBodyInvocations.getOrDefault(body.functionId(), Set.of())
                    .contains(op.opId())) {
                declaringBodyCarrierInvocations++;
            }
            SemanticOp closure = closureByResult.get(indirect.callee().id());
            KindPayload.ClosureNewPayload closurePayload =
                (KindPayload.ClosureNewPayload) closure.payload();
            check(closurePayload.binding().equals(body), testCase.name()
                + ": the produced CLOSURE_NEW registration equals the resolved "
                + "LoweredBody registration of " + indirect.callee());
            check(unit.functions().containsKey(body.functionId()), testCase.name()
                + ": the self-alias invocation resolves the body's LoweredFunction record");
            LoweredFunction function = unit.functions().get(body.functionId());
            if (testCase.expectCaptures()) {
                check(function != null && !function.captures().isEmpty(), testCase.name()
                    + ": the declaring body carries creation-site captures (the "
                    + "value-carried classification is load-bearing)");
            } else {
                check(function != null, testCase.name()
                    + ": the declaring body carries its LoweredFunction record");
            }
            OpId bodyReturnBoundary = returnBoundaries.get(body.functionId());
            check(bodyReturnBoundary != null && invocationReturnBoundary != null
                    && invocationReturnBoundary.equals(bodyReturnBoundary),
                testCase.name() + ": the self-alias invocation records the declaring "
                    + "body's own return cell " + bodyReturnBoundary + "; got "
                    + invocationReturnBoundary);
            check(closureNews.getOrDefault(body.functionId(), 0) == 1,
                testCase.name() + ": exactly one CLOSURE_NEW materializes function "
                    + body.functionId() + " (exactly-once closure creation); got "
                    + closureNews.getOrDefault(body.functionId(), 0));
        }
        check(declaringBodyCarrierInvocations >= 1, testCase.name()
            + ": the declaring body's own alias invocation (its reserved call-site "
            + "identity) is value-carried at the declaration's pre-allocated identity; "
            + "the body's carrier invocations are " + selfAliasInvocations.size());

        // The single registration per pre-allocated identity: every
        // CLOSURE_NEW result identity of this unit resolves to exactly one
        // registration (the produced map is keyed, and a duplicate would have
        // failed the lowering at registration time).
        for (Map.Entry<Long, SemanticOp> entry : closureByResult.entrySet()) {
            FunctionExecutionBinding binding = unit.functionBindings().get(
                new FunctionAllocationIdentity(entry.getKey()));
            check(binding instanceof FunctionExecutionBinding.LoweredBody,
                testCase.name() + ": the CLOSURE_NEW identity " + entry.getKey()
                    + " carries exactly one LoweredBody registration; got " + binding);
        }
    }

    // =========================================================================
    // Surface 3: the three-consumer differential matrix
    // =========================================================================

    private static void differential(Case testCase, Lowered lowered) throws Exception {
        Path workspace = Files.createTempDirectory("self-alias-matrix-"
            + testCase.name() + "-");
        try {
            SemanticDifferentialHarness.Expectation expectation =
                SemanticDifferentialHarness.Expectation.success(testCase.name(),
                    testCase.effects(), testCase.resultAtom());
            SemanticDifferentialHarness.Verdict verdict = testCase.asyncExport() == null
                ? SemanticDifferentialHarness.runProject(lowered.project(),
                    lowered.tables(), lowered.registries(), expectation, workspace)
                : SemanticDifferentialHarness.runAsyncEntry(lowered.project(),
                    lowered.tables(), testCase.asyncExport(), List.of(), expectation,
                    workspace, null);
            check(verdict.runs().size() == 3, testCase.name()
                + ": the differential matrix produced the three consumers: "
                + verdict.failures());
            List<String> unexplained = new ArrayList<>();
            for (String failure : verdict.failures()) {
                if (!isRecursivePairingNotice(failure)) {
                    unexplained.add(failure);
                }
            }
            check(unexplained.isEmpty(), testCase.name() + ": the oracle and both shared "
                + "artifacts agree event-for-event with the pinned terminal and effects "
                + "(only the recursive re-entry pairing notices allowed): " + unexplained);
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                check(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.Success success
                        && testCase.resultAtom().equals(success.resultAtom()),
                    testCase.name() + " [" + run.consumer()
                        + "]: the pinned success terminal " + testCase.resultAtom()
                        + "; got " + run.terminal());
                List<String> texts = new ArrayList<>();
                for (SemanticRuntimeModel.EffectEvent effect : run.effects()) {
                    texts.add(effect.text());
                }
                check(texts.equals(testCase.effects()), testCase.name() + " ["
                    + run.consumer() + "]: the pinned console effects "
                    + testCase.effects() + "; got " + texts);
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
    // Surface 4: the staged production drives
    // =========================================================================

    /**
     * The staged production artifacts execute the case's async export on both
     * release-owned lanes through their production async-entry surface (no
     * trace instrumentation) and publish the pinned console effects and the
     * pinned result.
     */
    private static void driveProductionAsync(Case testCase, Path project, Lowered lowered)
            throws Exception {
        String marker = "SELF_ALIAS_ASYNC_RESULT:";
        String expectedResult = productionResultText(testCase.resultAtom());
        List<String> expectedStdout = new ArrayList<>(testCase.effects());
        expectedStdout.add(marker + expectedResult);

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the async case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            Path probe = luaOut.resolve("__self_alias_async_probe.lua");
            Files.writeString(probe,
                "dofile(\"" + luaString(chunk.toAbsolutePath().normalize().toString())
                    + "\")\n"
                    + "local __ok, __err = __dealMain()\n"
                    + "if not __ok then error(__err, 0) end\n"
                    + "local __okA, __resA = pcall(__asyncEntries[\""
                    + luaString(lowered.project().entryModule().path() + "#"
                        + testCase.asyncExport())
                    + "\"], \"-\", true)\n"
                    + "if not __okA then error(__resA, 0) end\n"
                    + "print(\"" + marker + "\" .. tostring(__resA))\n",
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit",
                probe.getFileName().toString());
            builder.directory(luaOut.toFile());
            builder.environment().put("DEAL_DEFER_MAIN", "1");
            ProcessOutput run = runProcess(builder);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [luajit]: the staged chunk executes the async case: " + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [luajit]: the staged chunk publishes the pinned effects and result "
                + expectedStdout + "; got " + run.stdout());
        }

        Path jvmOut = project.resolve("out-jvm");
        Path artifact = jvmOut.resolve(JvmNames.classNameFor("main") + ".java");
        check(Files.isRegularFile(artifact), testCase.name()
            + " [jvm]: the staged artifact carries the async case: " + artifact);
        Long entryId = asyncEntryIdOf(lowered.unit(), testCase.asyncExport());
        check(entryId != null, testCase.name() + " [jvm]: the unit records the async "
            + "export's EXTERNAL_ENTRY over the production entry surface");
        Path classes = jvmOut.resolve("classes");
        boolean artifactCompiled = Files.isRegularFile(
            classes.resolve(JvmNames.classNameFor("main") + ".class"));
        check(artifactCompiled, testCase.name()
            + " [jvm]: the staged artifact is compiled for the async drive");
        if (Files.isRegularFile(artifact) && entryId != null && artifactCompiled) {
            String probeClass = "SelfAliasAsyncProbe_"
                + UUID.randomUUID().toString().replace("-", "");
            Path probe = jvmOut.resolve(probeClass + ".java");
            Files.writeString(probe,
                "public final class " + probeClass + " {\n"
                    + "  public static void main(String[] args) {\n"
                    + "    Main.dealMain();\n"
                    + "    Object result = Main.ae" + entryId
                    + "(\"-\", true, new Object[]{});\n"
                    + "    System.out.println(\"" + marker + "\" + result);\n"
                    + "    System.exit(0);\n"
                    + "  }\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-sourcepath", "", "-cp",
                classes + File.pathSeparator + absoluteClasspath(),
                "-d", classes.toString(), probe.toString());
            javac.directory(jvmOut.toFile());
            ProcessOutput compile = runProcess(javac);
            check(compile.exit() == 0, testCase.name() + " [jvm]: the async probe "
                + "compiles against the staged classes under javac --release 25 -proc:none: "
                + compile.describe());
            if (compile.exit() == 0) {
                ProcessBuilder java = new ProcessBuilder("java", "-cp",
                    classes + File.pathSeparator + absoluteClasspath(), probeClass);
                java.directory(jvmOut.toFile());
                ProcessOutput run = runProcess(java);
                check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                    + " [jvm]: the staged artifact executes the async case: "
                    + run.describe());
                check(run.stdout().equals(expectedStdout), testCase.name()
                    + " [jvm]: the staged artifact publishes the pinned effects and "
                    + "result " + expectedStdout + "; got " + run.stdout());
            }
        }
    }

    /**
     * The staged production artifacts execute the case's sync entry on both
     * release-owned lanes through their production entry surface (no trace
     * instrumentation) and publish the pinned console effects.
     */
    private static void driveProductionSync(Case testCase, Path project)
            throws Exception {
        List<String> expectedStdout = testCase.effects();

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the sync case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            ProcessBuilder builder = new ProcessBuilder("luajit", "main.lua");
            builder.directory(luaOut.toFile());
            ProcessOutput run = runProcess(builder);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [luajit]: the staged chunk executes the sync case: "
                + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [luajit]: the staged chunk publishes the pinned effects "
                + expectedStdout + "; got " + run.stdout());
        }

        String className = JvmNames.classNameFor("main");
        Path jvmOut = project.resolve("out-jvm");
        Path classes = jvmOut.resolve("classes");
        check(Files.isRegularFile(classes.resolve(className + ".class")),
            testCase.name() + " [jvm]: the staged artifact is compiled for the "
                + "sync drive: " + classes.resolve(className + ".class"));
        if (Files.isRegularFile(classes.resolve(className + ".class"))) {
            ProcessBuilder java = new ProcessBuilder("java", "-cp",
                classes + File.pathSeparator + absoluteClasspath(), className);
            java.directory(jvmOut.toFile());
            ProcessOutput run = runProcess(java);
            check(run.exit() == 0 && run.stderr().isEmpty(), testCase.name()
                + " [jvm]: the staged artifact executes the sync case: "
                + run.describe());
            check(run.stdout().equals(expectedStdout), testCase.name()
                + " [jvm]: the staged artifact publishes the pinned effects "
                + expectedStdout + "; got " + run.stdout());
        }
    }

    // =========================================================================
    // Surface 5: the consumed-activation release (ISSUE-0762 review cycle 1)
    // =========================================================================

    /** The retained-record count printed by the release probes. */
    private static final String RETAINED_MARKER = "RETAINED:";

    /**
     * The oracle's consumed-activation release: the oracle's own execution
     * state is driven through the entry module's async export and the
     * retained task records are counted afterwards. Every per-activation
     * instance the sequential awaits consume (one per loop iteration plus
     * the failing activation) is released at its AWAIT on the success and
     * failure paths; only the entry's canonical static record stays. Before
     * the correction this drive retained one record per consumed activation
     * ({@link #SEQUENTIAL_ITERATIONS} instances plus the failing one).
     */
    private static void checkOracleActivationRelease(Case testCase, Lowered lowered)
            throws Exception {
        LoweredModuleUnit unit = lowered.unit();
        SemanticOp entry = null;
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                    && payload.async()
                    && testCase.asyncExport().equals(payload.exportName())) {
                entry = op;
                break;
            }
        }
        check(entry != null, testCase.name() + " [oracle]: the unit records the async"
            + " export's EXTERNAL_ENTRY for the release drive");
        if (entry == null) {
            return;
        }
        Class<?> executionClass =
            Class.forName("deal.semantic.SemanticOracle$Execution");
        java.lang.reflect.Constructor<?> constructor = executionClass
            .getDeclaredConstructor(ExecutableLoweredProject.class, Map.class,
                Class.forName("deal.semantic.SemanticOracle$HostResponder"));
        constructor.setAccessible(true);
        Object state = constructor.newInstance(lowered.project(), lowered.tables(), null);
        java.lang.reflect.Method runInit = executionClass
            .getDeclaredMethod("runModuleInit", LoweredModuleUnit.class);
        runInit.setAccessible(true);
        runInit.invoke(state, unit);
        java.lang.reflect.Method executeEntry = executionClass
            .getDeclaredMethod("executeAsyncEntry", SemanticOp.class, OpId.class,
                List.class, long.class);
        executeEntry.setAccessible(true);
        executeEntry.invoke(state, entry, null, List.of(), entry.opId().id());
        java.lang.reflect.Method drain =
            executionClass.getDeclaredMethod("drainReadyTasks");
        drain.setAccessible(true);
        drain.invoke(state);
        java.lang.reflect.Field tasks = executionClass.getDeclaredField("tasks");
        tasks.setAccessible(true);
        int retained = ((Map<?, ?>) tasks.get(state)).size();
        check(retained <= 2, testCase.name() + " [oracle]: the sequential awaits consume"
            + " and release their own activation records (the oracle retains only the"
            + " entry's canonical record); retained=" + retained);
    }

    /**
     * Both staged artifacts' consumed-activation release: after the drive,
     * the Lua chunk's chunk-global {@code __tasks} registry and the JVM
     * runtime's {@code TASKS} registry count no consumed activation record.
     * The Lua probe counts the emitted chunk's task table directly; the JVM
     * probe reads the frozen runtime's registry reflectively (the staged
     * class runs in its own JVM, so the count is program-scoped). Before the
     * correction each registry retained one record per consumed activation
     * ({@link #SEQUENTIAL_ITERATIONS} instances plus the failing one).
     */
    private static void checkStagedActivationRelease(Case testCase, Path project,
                                                     Lowered lowered) throws Exception {
        String surface = lowered.project().entryModule().path() + "#"
            + testCase.asyncExport();

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the release case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            Path probe = luaOut.resolve("__seq_await_release_probe.lua");
            Files.writeString(probe,
                "dofile(\"" + luaString(chunk.toAbsolutePath().normalize().toString())
                    + "\")\n"
                    + "local __ok, __err = __dealMain()\n"
                    + "if not __ok then error(__err, 0) end\n"
                    + "local __okA, __resA = pcall(__asyncEntries[\""
                    + luaString(surface) + "\"], \"-\", true)\n"
                    + "if not __okA then error(__resA, 0) end\n"
                    + "local __retained = 0\n"
                    + "for __id in pairs(__tasks) do __retained = __retained + 1 end\n"
                    + "print(\"" + RETAINED_MARKER + "\" .. tostring(__retained))\n",
                StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("luajit",
                probe.getFileName().toString());
            builder.directory(luaOut.toFile());
            builder.environment().put("DEAL_DEFER_MAIN", "1");
            ProcessOutput run = runProcess(builder);
            Integer retained = retainedOf(run.stdout());
            check(run.exit() == 0 && run.stderr().isEmpty() && retained != null
                    && retained <= 2,
                testCase.name() + " [luajit]: the emitted chunk releases every consumed"
                    + " per-activation record (retained <= 2); "
                    + (retained == null ? run.describe() : "retained=" + retained));
        }

        Path jvmOut = project.resolve("out-jvm");
        Path classes = jvmOut.resolve("classes");
        String className = JvmNames.classNameFor("main");
        Long entryId = asyncEntryIdOf(lowered.unit(), testCase.asyncExport());
        boolean compiled = Files.isRegularFile(classes.resolve(className + ".class"));
        check(entryId != null && compiled, testCase.name()
            + " [jvm]: the staged artifact is compiled and carries the async entry for"
            + " the release drive: entry=" + entryId + " compiled=" + compiled);
        if (entryId != null && compiled) {
            String probeClass = "SelfAliasReleaseProbe_"
                + UUID.randomUUID().toString().replace("-", "");
            Path probe = jvmOut.resolve(probeClass + ".java");
            Files.writeString(probe,
                "public final class " + probeClass + " {\n"
                    + "  public static void main(String[] args) throws Exception {\n"
                    + "    " + className + ".dealMain();\n"
                    + "    Object result = " + className + ".ae" + entryId
                    + "(\"-\", true, new Object[]{});\n"
                    + "    java.lang.reflect.Field tasks = Class"
                    + ".forName(\"deal.codegen.jvm.JvmRuntime\")"
                    + ".getDeclaredField(\"TASKS\");\n"
                    + "    tasks.setAccessible(true);\n"
                    + "    int retained = ((java.util.Map<?, ?>) tasks.get(null)).size();\n"
                    + "    System.out.println(\"" + RETAINED_MARKER + "\" + retained);\n"
                    + "  }\n"
                    + "}\n",
                StandardCharsets.UTF_8);
            ProcessBuilder javac = new ProcessBuilder("javac", "--release", "25",
                "-proc:none", "-sourcepath", "", "-cp",
                classes + File.pathSeparator + absoluteClasspath(),
                "-d", classes.toString(), probe.toString());
            javac.directory(jvmOut.toFile());
            ProcessOutput compile = runProcess(javac);
            check(compile.exit() == 0, testCase.name() + " [jvm]: the release probe"
                + " compiles under javac --release 25 -proc:none: " + compile.describe());
            if (compile.exit() == 0) {
                ProcessBuilder java = new ProcessBuilder("java", "-cp",
                    classes + File.pathSeparator + absoluteClasspath(), probeClass);
                java.directory(jvmOut.toFile());
                ProcessOutput run = runProcess(java);
                Integer retained = retainedOf(run.stdout());
                check(run.exit() == 0 && run.stderr().isEmpty() && retained != null
                        && retained <= 2,
                    testCase.name() + " [jvm]: the staged artifact releases every consumed"
                        + " per-activation record (retained <= 2); "
                        + (retained == null ? run.describe() : "retained=" + retained));
            }
        }
    }

    /** The probe's retained-record count, or null when it did not report. */
    private static Integer retainedOf(List<String> stdout) {
        for (String line : stdout) {
            if (line.startsWith(RETAINED_MARKER)) {
                try {
                    return Integer.parseInt(line.substring(RETAINED_MARKER.length()));
                } catch (NumberFormatException malformed) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * The frozen JVM runtime's consumed-activation release: 10,000
     * sequential successful awaits and 10,000 sequential failing awaits of
     * one local body start must leave the runtime's canonical task registry
     * at its pre-loop size (every fresh instance record is released at its
     * single consuming AWAIT on both paths) and the activation slot empty.
     * Before the correction each loop retained one record per iteration.
     */
    private static void checkFrozenRuntimeActivationRelease() throws Exception {
        java.lang.reflect.Field tasksField =
            deal.codegen.jvm.JvmRuntime.class.getDeclaredField("TASKS");
        tasksField.setAccessible(true);
        Map<?, ?> tasks = (Map<?, ?>) tasksField.get(null);
        java.lang.reflect.Field slotsField =
            deal.codegen.jvm.JvmRuntime.class.getDeclaredField("ENTRY_INSTANCES");
        slotsField.setAccessible(true);
        Map<?, ?> slots = (Map<?, ?>) slotsField.get(null);
        long token = 762_000_001L;
        int iterations = 10_000;

        int before = tasks.size();
        int mismatched = 0;
        for (int i = 0; i < iterations; i++) {
            final int value = i;
            deal.codegen.jvm.JvmRuntime.startBodyTaskInstance(token, "DEAL_BODY_TASK",
                () -> value);
            Object completed = deal.codegen.jvm.JvmRuntime.awaitEntryTask(token, false,
                "release-probe");
            if (!Integer.valueOf(value).equals(completed)) {
                mismatched++;
            }
        }
        check(mismatched == 0, "the frozen JVM runtime's sequential successful awaits"
            + " consume their own completions; mismatched=" + mismatched);
        check(tasks.size() - before <= 2, "the frozen JVM runtime releases every consumed"
            + " activation record on the success path (" + iterations
            + " sequential awaits); retained delta=" + (tasks.size() - before));

        before = tasks.size();
        int caught = 0;
        for (int i = 0; i < iterations; i++) {
            deal.codegen.jvm.JvmRuntime.startBodyTaskInstance(token, "DEAL_BODY_TASK",
                () -> {
                    throw new deal.codegen.jvm.JvmRuntime.DealError("E8005",
                        "integer division by zero", "-", null, null, "", null);
                });
            try {
                deal.codegen.jvm.JvmRuntime.awaitEntryTask(token, false, "release-probe");
            } catch (deal.codegen.jvm.JvmRuntime.DealError expected) {
                caught++;
            }
        }
        check(caught == iterations, "the frozen JVM runtime's sequential failing awaits"
            + " rethrow each activation's own error; caught=" + caught);
        check(tasks.size() - before <= 2, "the frozen JVM runtime releases every consumed"
            + " activation record on the failure path (" + iterations
            + " sequential awaits); retained delta=" + (tasks.size() - before));
        Object slot = slots.get(token);
        check(slot == null || ((java.util.Collection<?>) slot).isEmpty(),
            "the frozen JVM runtime leaves no residual activation slot for the"
                + " repeatedly awaited token; slot=" + slot);
    }

    /** The printed form of the pinned result atom (descriptor prefix removed). */
    private static String productionResultText(String atom) {
        int colon = atom.indexOf(':');
        return colon < 0 ? atom : atom.substring(colon + 1);
    }

    /** The async EXTERNAL_ENTRY op id of the entry module's export, or null. */
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

    /** The captured outcome of one real-toolchain process. */
    private record ProcessOutput(int exit, List<String> stdout, List<String> stderr) {

        String describe() {
            return "exit=" + exit + " stdout=" + stdout + " stderr=" + stderr;
        }
    }

    private static ProcessOutput runProcess(ProcessBuilder builder) throws Exception {
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new ProcessOutput(exit, splitLines(out), splitLines(err));
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String luaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
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
        System.out.println("=== Self-Alias Production Test (ISSUE-0743) ===\n");
        for (Case testCase : CASES) {
            runCase(testCase);
        }
        System.out.println("\n-- the frozen JVM runtime's consumed-activation release --");
        checkFrozenRuntimeActivationRelease();
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
