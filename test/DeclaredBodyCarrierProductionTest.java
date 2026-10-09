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
import deal.semantic.ir.BindingGeneration;
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
import deal.semantic.ir.ValueId;

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
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ISSUE-0757 acceptance remediation of ISSUE-0699: the declared body's
 * creation-site carrier ({@code residual-carrier-shapes-production-realization}
 * R4 and the local declared-body contract;
 * {@code luajit-jvm-single-lowering-production-cutover} C10/K12).
 *
 * <p>The reported defect: a declared-name call of a non-group declaration
 * reconstructed the callee's factory at the invocation site
 * ({@code CallCallee.Static}) and re-resolved the declaration's captures in
 * the calling function's frame. For a declaration whose creation site is an
 * enclosing invocation — the acyclic sibling shape {@code make(base)}
 * containing {@code target()} reading {@code base} and {@code caller()}
 * calling {@code target()} — the caller does not itself capture
 * {@code base}, so the reconstruction read the module-level cell slot and the
 * escaped closure failed at execution ({@code attempt to index upvalue ...
 * (a nil value)} under LuaJIT). The same restriction held in the await arm.
 * The fix routes every capture-carrying declared-name invocation through the
 * declaration's published closure carrier ({@code CallCallee.Indirect}),
 * preserving the declaration's own closure identity, reserved call-site
 * identity, and single return cell.</p>
 *
 * <p>The second reported integration gap: a forward module function alias —
 * {@code let alias = f} before {@code f}'s declaration — failed the
 * lowering with E6005 {@code CONSTRUCT_UNLOWERED} ("identity ... has no
 * registered FunctionExecutionBinding") because the body's execution
 * binding registered only at the target's own declaration arm, after the
 * referring body's walk. Module-level declared bodies now register at the
 * hoist, so the forward alias (and the capture-carrying declared-name call)
 * resolves the body from the hoist on.</p>
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
 *   <li><b>The declared-body carrier battery.</b> The control-flow validator
 *       admits the unit; every declaration materializes exactly one
 *       {@code CLOSURE_NEW}; no capture-carrying declared body is invoked as
 *       a call-site factory reconstruction ({@code Static}); every
 *       value-carried invocation resolves a registered {@code LoweredBody}
 *       through the declaration's own closure identity and records the
 *       body's single return cell.</li>
 *   <li><b>The three-consumer differential matrix.</b> The semantic oracle,
 *       the shared LuaJIT artifact, and the shared JVM artifact execute the
 *       program to the pinned success terminal and the pinned console
 *       effects and agree event-for-event.</li>
 *   <li><b>The staged production drive.</b> The staged LuaJIT chunk and the
 *       staged JVM class execute the case's own entry surface (the sync
 *       {@code main}, or the async export through the production async-entry
 *       surface) and publish the pinned effects and result.</li>
 * </ol>
 */
public class DeclaredBodyCarrierProductionTest {

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
     * One declared-body program: the source, the pinned console effects, the
     * driven async export (null for the sync-entry cases), the pinned result
     * atom of the differential terminal, and the minimum number of
     * carrier-carried declared-body invocations the unit must record.
     */
    private record Case(String name, String source, List<String> effects,
                        String asyncExport, String resultAtom,
                        int minCarrierInvocations) {

        Case {
            effects = List.copyOf(effects);
        }

        /** The sync-entry case: the {@code main} drive and the null terminal. */
        private Case(String name, String source, List<String> effects,
                     int minCarrierInvocations) {
            this(name, source, effects, null, "null", minCarrierInvocations);
        }
    }

    /**
     * The acyclic sibling shape of the reported counterexample: {@code make}
     * declares {@code target} (reading the parameter {@code base}) and
     * {@code caller} (calling {@code target}), and returns {@code caller}.
     * {@code caller} captures {@code target} but not {@code base}, so the
     * static reconstruction of {@code target} at the call site reads the
     * module-level {@code base} slot and the escaped closure fails at
     * execution. The carrier invocation of {@code target} reads
     * {@code target}'s own creation-site capture of {@code base}.
     */
    private static final Case ACYCLIC_SIBLING = new Case(
        "acyclic-sibling",
        """
        import * as console from "std/console"

        export function main(): null {
          let c: () => int = make(10);
          let v: int = c();
          if (v === 10) {
            console.log("declared-body-sibling-ok");
          } else {
            console.log("declared-body-sibling-bad");
          }
          return null;
        }

        function make(base: int): () => int {
          function target(): int {
            return base;
          }
          function caller(): int {
            return target();
          }
          return caller;
        }
        """,
        List.of("declared-body-sibling-ok"), 1);

    /**
     * Two distinct escaped instances of the same shape: {@code make(10)} and
     * {@code make(20)} each publish their own {@code caller}/{@code target}
     * carriers, and each returned closure must observe its own creation's
     * {@code base}. A call-site factory reconstruction of {@code target}
     * resolves {@code base} from whichever frame is current (or from the
     * stale module-level slot after {@code make} returned), so both closures
     * would observe one value.
     */
    private static final Case TWO_ESCAPED_INSTANCES = new Case(
        "two-escaped-instances",
        """
        import * as console from "std/console"

        export function main(): null {
          let a: () => int = make(10);
          let b: () => int = make(20);
          let av: int = a();
          if (av === 10) {
            console.log("declared-body-instance-a-ok");
          } else {
            console.log("declared-body-instance-a-bad");
          }
          let bv: int = b();
          if (bv === 20) {
            console.log("declared-body-instance-b-ok");
          } else {
            console.log("declared-body-instance-b-bad");
          }
          return null;
        }

        function make(base: int): () => int {
          function target(): int {
            return base;
          }
          function caller(): int {
            return target();
          }
          return caller;
        }
        """,
        List.of("declared-body-instance-a-ok", "declared-body-instance-b-ok"), 1);

    /**
     * The awaited equivalent of the acyclic sibling shape: an async
     * declaration awaited by name. The await arm has the same restriction as
     * the synchronous call arm, so the awaited call must carry the
     * declaration's published closure value ({@code ASYNC_START} with the
     * {@code Indirect} callee) instead of reconstructing the body's factory
     * at the await site.
     */
    private static final Case ACYCLIC_SIBLING_ASYNC = new Case(
        "acyclic-sibling-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let c: async () => int = make(10);
          let v: int = await c();
          if (v === 10) {
            console.log("declared-body-sibling-async-ok");
          } else {
            console.log("declared-body-sibling-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        function make(base: int): async () => int {
          async function target(): int {
            return base;
          }
          async function caller(): int {
            return await target();
          }
          return caller;
        }
        """,
        List.of("declared-body-sibling-async-ok"), "drive", "int:10", 1);

    /** Two distinct escaped async instances of the awaited sibling shape. */
    private static final Case TWO_ESCAPED_INSTANCES_ASYNC = new Case(
        "two-escaped-instances-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let a: async () => int = make(10);
          let b: async () => int = make(20);
          let av: int = await a();
          if (av === 10) {
            console.log("declared-body-async-instance-a-ok");
          } else {
            console.log("declared-body-async-instance-a-bad");
          }
          let bv: int = await b();
          if (bv === 20) {
            console.log("declared-body-async-instance-b-ok");
          } else {
            console.log("declared-body-async-instance-b-bad");
          }
          return av + bv;
        }

        export function main(): null {
          return null;
        }

        function make(base: int): async () => int {
          async function target(): int {
            return base;
          }
          async function caller(): int {
            return await target();
          }
          return caller;
        }
        """,
        List.of("declared-body-async-instance-a-ok",
            "declared-body-async-instance-b-ok"), "drive", "int:30", 1);

    /**
     * The forward module function alias: {@code g} holds a function-typed
     * alias of the later-declared, capture-carrying {@code f} and calls it.
     * Before the fix the alias load preserved {@code f}'s pre-allocated
     * identity while the registry held no registration for it yet (the
     * declaration arm registers the body before its own walk, after
     * {@code g}'s walk), so the checker-valid program failed closed with
     * E6005 {@code CONSTRUCT_UNLOWERED}. {@code f}'s own call of the
     * later-declared {@code helper} is the same forward shape through the
     * declared name.
     */
    private static final Case FORWARD_ALIAS = new Case(
        "forward-alias",
        """
        import * as console from "std/console"

        export function main(): null {
          let v: int = g();
          if (v === 5) {
            console.log("declared-body-forward-alias-ok");
          } else {
            console.log("declared-body-forward-alias-bad");
          }
          return null;
        }

        function g(): int {
          let alias: () => int = f;
          return alias();
        }

        function f(): int {
          return helper();
        }

        function helper(): int {
          return 5;
        }
        """,
        List.of("declared-body-forward-alias-ok"), 0);

    /** The awaited forward alias: the async twin of {@link #FORWARD_ALIAS}. */
    private static final Case FORWARD_ALIAS_ASYNC = new Case(
        "forward-alias-async",
        """
        import * as console from "std/console"

        export async function drive(): int {
          let v: int = await g();
          if (v === 5) {
            console.log("declared-body-forward-alias-async-ok");
          } else {
            console.log("declared-body-forward-alias-async-bad");
          }
          return v;
        }

        export function main(): null {
          return null;
        }

        async function g(): int {
          let alias: async () => int = f;
          return await alias();
        }

        async function f(): int {
          return await helper();
        }

        async function helper(): int {
          return 5;
        }
        """,
        List.of("declared-body-forward-alias-async-ok"), "drive", "int:5", 0);

    private static final List<Case> CASES = List.of(
        ACYCLIC_SIBLING, TWO_ESCAPED_INSTANCES, ACYCLIC_SIBLING_ASYNC,
        TWO_ESCAPED_INSTANCES_ASYNC, FORWARD_ALIAS, FORWARD_ALIAS_ASYNC);

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
        Path project = Files.createTempDirectory(
            "declared-body-carrier-" + testCase.name() + "-");
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

            // Surface 2: the one lowering and the carrier battery.
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
    // Surface 2: the one lowering and the declared-body carrier battery
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
            testCase.name() + ": the declared-body program lowers with zero "
                + "diagnostics (no CONSTRUCT_UNLOWERED/CONTROL_EXIT): "
                + result.diagnostics());
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
        checkDeclaredBodyCarriers(testCase, unit, table);
        return new Lowered(result.project(), result.tables(), result.registries(),
            unit, table);
    }

    /**
     * The declared-body carrier battery: the validator admission, exactly one
     * closure creation per declaration, the absence of any call-site factory
     * reconstruction of a capture-carrying body, and the value-carried
     * invocation's resolution of the declaration's own closure identity and
     * single return cell.
     */
    private static void checkDeclaredBodyCarriers(Case testCase, LoweredModuleUnit unit,
                                                  StructuredBodyTable table) {
        Optional<CompilerDiagnostic> admission = ControlFlowValidator.validate(unit, table);
        check(admission.isEmpty(), testCase.name()
            + ": the control-flow validator admits the unit: " + admission);

        Map<FunctionId, Integer> closureNews = new LinkedHashMap<>();
        Map<Long, SemanticOp> closureByResult = new LinkedHashMap<>();
        Map<FunctionId, OpId> returnBoundaries = new LinkedHashMap<>();
        Set<FunctionId> entryDelegated = new java.util.LinkedHashSet<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.CLOSURE_NEW
                    && op.payload() instanceof KindPayload.ClosureNewPayload closure) {
                closureNews.merge(closure.function(), 1, Integer::sum);
                if (op.result() instanceof ValueId value) {
                    closureByResult.put(value.id(), op);
                }
            }
            if (op.kind() == SemanticOpKind.RETURN
                    && op.payload() instanceof KindPayload.ReturnPayload returned) {
                returnBoundaries.put(returned.function(),
                    returned.returnBoundaryOpId());
            }
            if (op.payload() instanceof KindPayload.EntryInvokePayload entry) {
                // The runtime entry delegation is emitted by the entry
                // surface, not by a source reference: its static invocation
                // of the entry body reconstructs the module-level
                // declaration's single-incarnation captures exactly (the
                // entry op runs when every declaration cell is initialized).
                entryDelegated.add(entry.mainFunction());
            }
        }
        for (Map.Entry<FunctionId, Integer> entry : closureNews.entrySet()) {
            check(entry.getValue() == 1, testCase.name() + ": exactly one CLOSURE_NEW "
                + "materializes function " + entry.getKey() + " (exactly-once closure "
                + "creation); got " + entry.getValue());
            check(unit.functions().containsKey(entry.getKey()), testCase.name()
                + ": function " + entry.getKey() + " carries its LoweredFunction record");
        }

        int carrierInvocations = 0;
        int staticCaptureInvocations = 0;
        for (SemanticOp op : unit.ops()) {
            KindPayload.CallCallee callee = invocationCallee(op);
            if (callee == null) {
                continue;
            }
            FunctionExecutionBinding resolved = switch (callee) {
                case KindPayload.CallCallee.Static stat -> stat.binding();
                case KindPayload.CallCallee.Indirect indirect ->
                    unit.functionBindings().get(
                        new FunctionAllocationIdentity(indirect.callee().id()));
                case KindPayload.CallCallee.Dynamic ignored -> null;
            };
            if (!(resolved instanceof FunctionExecutionBinding.LoweredBody body)) {
                continue;
            }
            LoweredFunction target = unit.functions().get(body.functionId());
            check(target != null, testCase.name() + ": the invocation " + op.opId()
                + " resolves the body's LoweredFunction record");
            // Every declared-body invocation records the declaring body's own
            // single return cell — the pre-allocated identity is resolvable
            // from the hoist on, so a forward reference names it too.
            OpId bodyBoundary = returnBoundaries.get(body.functionId());
            OpId invocationBoundary = invocationReturnBoundary(op);
            check(bodyBoundary != null && bodyBoundary.equals(invocationBoundary),
                testCase.name() + ": the invocation " + op.opId()
                    + " records the declaring body's own return cell " + bodyBoundary
                    + "; got " + invocationBoundary);
            if (callee instanceof KindPayload.CallCallee.Indirect indirect) {
                carrierInvocations++;
                check(closureByResult.containsKey(indirect.callee().id()), testCase.name()
                    + ": the carrier invocation " + op.opId() + " reads the "
                    + "declaration's own closure identity " + indirect.callee()
                    + " (the published CLOSURE_NEW result); got a value outside the "
                    + "unit's closure identities");
            } else if (target != null && !target.captures().isEmpty()
                    && !entryDelegated.contains(body.functionId())
                    && !moduleStableCaptures(unit, target)) {
                staticCaptureInvocations++;
                System.err.println("FAIL: " + testCase.name() + ": the invocation "
                    + op.opId() + " of capture-carrying function " + body.functionId()
                    + " is a factory reconstruction at the call site; the "
                    + "value-carried carrier is required");
            }
        }
        check(staticCaptureInvocations == 0, testCase.name()
            + ": no capture-carrying declared body is invoked as a call-site factory "
            + "reconstruction; got " + staticCaptureInvocations);
        check(carrierInvocations >= testCase.minCarrierInvocations(), testCase.name()
            + ": the unit records at least " + testCase.minCarrierInvocations()
            + " carrier-carried declared-body invocation(s); got " + carrierInvocations);
    }

    /**
     * Whether every capture of one declaration resolves to a module-init
     * incarnation: a module-level declaration's single-incarnation cells, so
     * a call-site factory reconstruction of that body passes exactly the
     * cells its creation site passed. A capture allocated in a body block is
     * per-invocation and only the published carrier names the right one.
     */
    private static boolean moduleStableCaptures(LoweredModuleUnit unit,
                                                LoweredFunction function) {
        for (BindingGeneration capture : function.captures()) {
            boolean found = false;
            for (SemanticOp op : unit.ops()) {
                if (op.payload() instanceof KindPayload.BindingAllocPayload alloc
                        && alloc.binding().equals(capture.binding())
                        && alloc.generation() == capture.generation()) {
                    if (!alloc.scope().equals(unit.moduleInit().initBlock())) {
                        return false;
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** The callee reference of one invocation op (CALL or ASYNC_START). */
    private static KindPayload.CallCallee invocationCallee(SemanticOp invocation) {
        return switch (invocation.kind()) {
            case CALL -> invocation.payload()
                instanceof KindPayload.CallPayload call ? call.callee() : null;
            case ASYNC_START -> invocation.payload()
                instanceof KindPayload.AsyncStartPayload start ? start.callee() : null;
            default -> null;
        };
    }

    /** The recorded return boundary of one invocation op (CALL or ASYNC_START). */
    private static OpId invocationReturnBoundary(SemanticOp invocation) {
        return switch (invocation.kind()) {
            case CALL -> invocation.payload()
                instanceof KindPayload.CallPayload call ? call.returnBoundaryOpId() : null;
            case ASYNC_START -> invocation.payload()
                instanceof KindPayload.AsyncStartPayload start
                    ? start.returnBoundaryOpId() : null;
            default -> null;
        };
    }

    // =========================================================================
    // Surface 3: the three-consumer differential matrix
    // =========================================================================

    private static void differential(Case testCase, Lowered lowered) throws Exception {
        Path workspace = Files.createTempDirectory("declared-body-matrix-"
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
            check(verdict.failures().isEmpty(), testCase.name() + ": the oracle and both "
                + "shared artifacts agree event-for-event with the pinned terminal and "
                + "effects: " + verdict.failures());
            check(verdict.pass(), testCase.name()
                + ": the matrix carries no accepted failure: " + verdict.failures());
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

    // =========================================================================
    // Surface 4: the staged production drives
    // =========================================================================

    /**
     * The staged production artifacts execute the case's async export on both
     * release-owned lanes through their production async-entry surface and
     * publish the pinned console effects and the pinned result.
     */
    private static void driveProductionAsync(Case testCase, Path project, Lowered lowered)
            throws Exception {
        String marker = "DECLARED_BODY_ASYNC_RESULT:";
        String expectedResult = productionResultText(testCase.resultAtom());
        List<String> expectedStdout = new ArrayList<>(testCase.effects());
        expectedStdout.add(marker + expectedResult);

        Path luaOut = project.resolve("out-luajit");
        Path chunk = luaOut.resolve("main.lua");
        check(Files.isRegularFile(chunk), testCase.name()
            + " [luajit]: the staged chunk carries the async case: " + chunk);
        if (Files.isRegularFile(chunk)) {
            Path probe = luaOut.resolve("__declared_body_async_probe.lua");
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
            String probeClass = "DeclaredBodyAsyncProbe_"
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
     * release-owned lanes through their production entry surface and publish
     * the pinned console effects.
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
        System.out.println("=== Declared-Body Carrier Production Test (ISSUE-0757) ===\n");
        for (Case testCase : CASES) {
            runCase(testCase);
        }
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
