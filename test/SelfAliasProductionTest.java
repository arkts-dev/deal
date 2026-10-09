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
        import * as console from "std/console"

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

    private static final List<Case> CASES =
        List.of(NESTED_SYNC, MODULE_SYNC, NESTED_ASYNC, MODULE_ASYNC);

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
        checkSelfAliasOwnership(testCase, unit, table);
        return new Lowered(result.project(), result.tables(), result.registries(),
            unit, table);
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
                check(returned.returnBoundaryOpId() != null, testCase.name()
                    + ": the RETURN of function " + returned.function()
                    + " names its return-boundary op");
            }
        }

        // The self-alias invocation: a value-carried CALL(INDIRECT) (sync) or
        // ASYNC_START(Indirect) (the awaited twin) whose callee value is the
        // declaration's pre-allocated allocation identity and whose recorded
        // return cell is that body's own return boundary.
        List<SemanticOp> selfAliasInvocations = new ArrayList<>();
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
        check(selfAliasInvocations.size() == 1, testCase.name()
            + ": exactly one value-carried self-alias invocation at the declaration's "
            + "pre-allocated identity; got " + selfAliasInvocations.size());

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
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
