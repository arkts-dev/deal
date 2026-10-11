package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JvmProductionProjectEmissionTest {

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static final ModuleId LIB = new ModuleId("lib");
    private static final ModuleId APP = new ModuleId("app");

    private static final String LIB_SOURCE = """
        export function add(a: int, b: int): int {
          return a + b;
        }

        export function twice(x: int): int {
          return x * 2;
        }
        """;

    /**
     * The entry module imports the implementation module (the closure
     * carries two modules) without any cross-module call — that shape
     * belongs to the calls child — and the module walk order plus the one
     * {@code ENTRY_INVOKE} delegation of the entry module are observable
     * through the runtime's module-init state.
     */
    private static final String APP_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    /** The conversion/arithmetic overflow terminal fixture (E8004). */
    private static final String OVERFLOW_SOURCE = """
        export function main(): null {
          let x: int = 2147483647 + 1
          return null
        }
        """;

    private record Fixture(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, FfiGeneratedModule> externCModules,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities) {
    }

    private record Baseline(
        SemanticLowerer.ProjectLoweringResult result,
        HostDeclarationSurface surface,
        JvmSemanticEmitter.EmissionResult emission) {
    }

    private static Baseline prepare(Fixture fixture) {
        SemanticLowerer.ProjectLoweringResult result = lower(fixture);
        JvmSemanticEmitter.EmissionResult emission = result.project() == null ? null
            : JvmSemanticEmitter.emitProductionProject(result.project(), result.tables(),
                result.registries(),
                JvmBackend.classNameFor(result.project().entryModule().path()),
                fixture.surface());
        return new Baseline(result, fixture.surface(), emission);
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** A real two-module checked project (lib plus the importing app entry). */
    private static Fixture compileProject() throws Exception {
        return compileProject(APP_SOURCE, "src/app.deal");
    }

    /** A real checked project with the given entry source and entry file. */
    private static Fixture compileProject(String entrySource, String entryRelative)
            throws Exception {
        return compileProject(LIB_SOURCE, entrySource, entryRelative);
    }

    /**
     * A real checked project with the given companion {@code src/lib.deal}
     * and the given entry source and entry file.
     */
    private static Fixture compileProject(String libSource, String entrySource,
                                          String entryRelative) throws Exception {
        Path root = Files.createTempDirectory("jvm-production-project");
        writeFileIn(root, "src/lib.deal", libSource);
        writeFileIn(root, entryRelative, entrySource);
        Path entry = root.resolve(entryRelative).toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry, output,
            false, null, List.of(root.resolve("src").toAbsolutePath()), null);
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null || manifests.manifests() == null) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the fixture project did not build: " + detail
                + " / " + orchestrator.diagnostics());
        }
        return new Fixture(root, built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), new LinkedHashMap<>(),
            new LinkedHashMap<>());
    }

    /** The one project lowering over the real checked project. */
    private static SemanticLowerer.ProjectLoweringResult lower(Fixture project) {
        return SemanticLowerer.lowerProject(invocation(), project.checkedProject(),
            project.index(), project.manifests(), project.surface(),
            project.declarationIdentities(), project.externCModules(),
            BuiltinErrorDeclaration.synthesized(
                project.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
    }

    /** One module's declared exports in declaration order (the op order). */
    private record ExportEntry(String name, String spec, long valueId) {
    }

    private static List<ExportEntry> exportsOf(LoweredModuleUnit unit) {
        List<ExportEntry> entries = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (op.kind() != SemanticOpKind.EXPORT_PUBLISH) {
                continue;
            }
            KindPayload.ExportPublishPayload payload =
                (KindPayload.ExportPublishPayload) op.payload();
            entries.add(new ExportEntry(payload.name(),
                payload.descriptor().canonicalSpecText(),
                ((ValueId) payload.value()).id()));
        }
        return entries;
    }

    // =========================================================================
    // 1. The one entry signature and the class-name use
    // =========================================================================

    private static void testEntrySignature() {
        System.out.println("-- the production project entry signature --");
        Method entry = null;
        for (Method method : JvmSemanticEmitter.class.getDeclaredMethods()) {
            // The reflected order of same-named overloads is unspecified:
            // select the five-input production entry by its signature.
            if (method.getName().equals("emitProductionProject")
                    && method.getParameterCount() == 5) {
                entry = method;
            }
        }
        check(entry != null, "JvmSemanticEmitter publishes emitProductionProject");
        if (entry == null) {
            return;
        }
        check(Modifier.isPublic(entry.getModifiers()) && Modifier.isStatic(entry.getModifiers()),
            "emitProductionProject is public static");
        checkEq(JvmSemanticEmitter.EmissionResult.class, entry.getReturnType(),
            "emitProductionProject returns the emitted artifact");
        Class<?>[] parameters = entry.getParameterTypes();
        checkEq(List.of(
                ExecutableLoweredProject.class,
                Map.class,
                Map.class,
                String.class,
                HostDeclarationSurface.class),
            List.of(parameters),
            "emitProductionProject consumes exactly the validated project, the tables, "
                + "the registries, the entry class name, and the host declaration "
                + "surface");
        for (Class<?> parameter : parameters) {
            String name = parameter.getName();
            check(!name.startsWith("deal.ast.") && !name.startsWith("deal.checker.")
                    && !name.equals("deal.codegen.HostModuleDeclarations")
                    && !name.equals("deal.ffi.FfiGeneratedModule")
                    && !name.equals("deal.semantic.RoutePlanResult")
                    && !name.equals("deal.semantic.ModuleRoutePlan")
                    && !name.equals("deal.semantic.CapabilityRegistry"),
                "emitProductionProject takes no AST/checker/route/host/extern-C input: "
                    + name);
        }

        Method projectEntry = null;
        Method moduleEntry = null;
        for (Method method : JvmSemanticEmitter.class.getDeclaredMethods()) {

            if (method.getName().equals("emitProject")
                    && method.getParameterCount() == 3) {
                projectEntry = method;
            }
            if (method.getName().equals("emitProductionModule")
                    && method.getParameterCount() == 4) {
                moduleEntry = method;
            }
        }
        check(projectEntry != null && projectEntry.getParameterCount() == 3,
            "emitProject keeps its three-input signature");
        check(moduleEntry != null && moduleEntry.getParameterCount() == 4
                && moduleEntry.getParameterTypes()[2] == boolean.class
                && moduleEntry.getParameterTypes()[3] == String.class,
            "emitProductionModule keeps its four-input signature");
    }

    // =========================================================================
    // 2. The emitted production artifact text
    // =========================================================================

    private static void testEmittedTextShapes(Baseline fixture) throws Exception {
        System.out.println("-- the production artifact: one class, trace suppressed, "
            + "the whole closure, the surface registry --");
        {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the fixture project lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            checkEq(List.of(LIB, APP), List.copyOf(project.modules().keySet()),
                "the closure carries the two modules in dependency order");
            String className = JvmBackend.classNameFor(project.entryModule().path());
            checkEq("App", className, "the entry class name derives from the entry path");

            JvmSemanticEmitter.EmissionResult emission = fixture.emission();
            String source = emission.source();
            checkEq(className, emission.className(), "the emission reports its class name");

            // One public final class with main and the dealMain drive.
            checkEq(1, countOccurrences(source, "public final class " + className + " {"),
                "the artifact is one public final class named for the className argument");
            List<String> imports = new ArrayList<>();
            for (String line : source.split("\n", -1)) {
                if (line.startsWith("import ")) {
                    imports.add(line);
                }
            }
            checkEq(List.of("import deal.codegen.jvm.JvmRuntime;",
                    "import deal.codegen.jvm.JvmJson;", "import java.util.List;"),
                imports,
                "the artifact imports only the compiler's runtime classes");
            check(source.contains("  public static void dealMain() {"),
                "the class carries the dealMain() drive");
            check(source.contains("  public static void main(String[] args) {"),
                "the class declares public static void main(String[])");
            checkEq(1, countOccurrences(source, "dealMain();"),
                "main drives dealMain() exactly once");
            check(!source.contains("SharedM"),
                "the production artifact does not use the shared conformance class name");

            // The trace protocol is suppressed.
            check(source.contains("JvmRuntime.setTraceEnabled(false);"),
                "the class disables the runtime trace protocol");
            check(!source.contains("setTraceEnabled(true)"),
                "the class enables no trace protocol");
            check(!source.contains("R|"),
                "the artifact carries no R| trace line");

            // The production terminal.
            check(source.contains("System.out.println(\"DEAL_ERROR_CODE: \" + e.code);"),
                "an uncaught DEAL failure publishes the DEAL_ERROR_CODE terminal");
            check(source.contains("System.exit(1);"),
                "an uncaught DEAL failure exits 1");
            check(!source.contains("DEAL_ERROR_SNAPSHOT"),
                "the artifact publishes no snapshot line (the lane composes it)");

            // The whole closure in dependency order: the two module walks,
            // and each module's function factories.
            int libWalk = source.indexOf("    MODULE = \"lib\";");
            int appWalk = source.indexOf("    MODULE = \"app\";");
            check(libWalk >= 0 && appWalk > libWalk,
                "the artifact walks every closure module in dependency order");
            LoweredModuleUnit appUnit = project.modules().get(APP);
            LoweredModuleUnit libUnit = project.modules().get(LIB);
            for (LoweredModuleUnit unit : List.of(libUnit, appUnit)) {
                for (var function : unit.functions().values()) {
                    check(source.contains("  static JvmRuntime.FunctionValue F"
                            + function.functionId().id() + "("),
                        "the artifact carries the factory of " + unit.moduleId().path()
                            + "#" + function.functionId().id());
                }
            }

            // The one entry delegation: the entry module's valid unit
            // carries exactly one ENTRY_INVOKE op delegating exactly one
            // CALL to its main, and the emitted walk drives that call once.
            int entryInvokes = 0;
            for (SemanticOp op : appUnit.ops()) {
                if (op.kind() != SemanticOpKind.ENTRY_INVOKE) {
                    continue;
                }
                entryInvokes++;
                int delegated = 0;
                for (SemanticOp candidate : appUnit.ops()) {
                    if (candidate.kind() == SemanticOpKind.CALL
                            && op.opId().equals(candidate.origin().parentOpId())) {
                        delegated++;
                    }
                }
                checkEq(1, delegated,
                    "the entry ENTRY_INVOKE delegates exactly one CALL to main");
            }
            checkEq(1, entryInvokes,
                "the entry module carries exactly one ENTRY_INVOKE delegation");
            checkEq(1, countOccurrences(source, ".fn.invoke(new Object[]{});"),
                "the emitted entry walk invokes the main delegate exactly once");
            check(source.indexOf(".fn.invoke(new Object[]{});") > appWalk,
                "the one main delegation runs inside the entry module's walk");
            int libInvokes = 0;
            for (SemanticOp op : libUnit.ops()) {
                if (op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    libInvokes++;
                }
            }
            checkEq(0, libInvokes,
                "the implementation module carries no ENTRY_INVOKE delegation");

            // The module export-surface registry (T1): one surface per
            // closure module keyed by the dotted module path, created before
            // the walks, and one EXPORT_PUBLISH write per declared export in
            // declaration order. The registry is hosted by the runtime
            // (M2: the program-scoped registry) and the class-level field is
            // the compatible per-class view of it.
            check(source.contains("static final java.util.LinkedHashMap<String, "
                    + "JvmRuntime.Table> EXPORT_SURFACES = "
                    + "JvmRuntime.EXPORT_SURFACES;"),
                "the class carries the class-level view of the runtime-hosted "
                    + "program-scoped export-surface registry");
            checkEq(List.of("    exportSurface(\"lib\");", "    exportSurface(\"app\");"),
                jvmSurfaceCreations(source),
                "one surface is created per closure module keyed by the module identity, "
                    + "before the module walks");
            int creationsAt = source.indexOf("    exportSurface(\"");
            check(creationsAt >= 0 && creationsAt < libWalk,
                "the surface creation precedes the first module walk");
            int previous = -1;
            List<String> writes = new ArrayList<>();
            for (ModuleId moduleId : project.modules().keySet()) {
                for (ExportEntry entry : exportsOf(project.modules().get(moduleId))) {
                    String spec = entry.spec();
                    check(spec != null && !spec.isEmpty(),
                        "the export " + moduleId.path() + "#" + entry.name()
                            + " carries a canonical spec text");
                }
            }
            checkEq(List.of("lib#add", "lib#twice", "app#main", "app#label"),
                exportOrder(project, writes),
                "the fixture's declared exports are covered in declaration order");
            for (String write : writes) {
                checkEq(1, countOccurrences(source, write),
                    "the artifact publishes " + write);
                int at = source.indexOf(write);
                check(at > previous, "the publications run in declaration order: " + write);
                previous = at;
            }

            // Determinism and the className passthrough.
            checkEq(source, JvmSemanticEmitter.emitProductionProject(project,
                    result.tables(), result.registries(), className,
                    fixture.surface()).source(),
                "the repeated production emission is byte-identical");
            JvmSemanticEmitter.EmissionResult custom =
                JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                    result.registries(), "CustomApp", fixture.surface());
            checkEq("CustomApp", custom.className(),
                "the className argument is used verbatim");
            checkEq(1, countOccurrences(custom.source(),
                    "public final class CustomApp {"),
                "a verbatim className lands in the class head");

            JvmSemanticEmitter.EmissionResult trace = JvmSemanticEmitter.emitProject(
                project, result.tables(), result.registries());
            check(trace.className().startsWith("SharedM") && trace.source().contains("R|"),
                "emitProject keeps its shared conformance class and trace protocol");
            JvmSemanticEmitter.EmissionResult module =
                JvmSemanticEmitter.emitProductionModule(appUnit, result.tables().get(APP),
                    true, "AppModule");
            checkEq("AppModule", module.className(),
                "emitProductionModule keeps using its className argument");
            check(module.source().contains("JvmRuntime.setTraceEnabled(false);"),
                "emitProductionModule keeps the suppressed trace protocol");
        }
    }

    /** The EXPORT_PUBLISH writes the artifact must carry, in declaration order. */
    private static List<String> exportOrder(ExecutableLoweredProject project,
            List<String> writes) {
        List<String> order = new ArrayList<>();
        for (ModuleId moduleId : project.modules().keySet()) {
            for (ExportEntry entry : exportsOf(project.modules().get(moduleId))) {
                order.add(moduleId.path() + "#" + entry.name());
                writes.add("exportSurface(\"" + moduleId.path() + "\").write(\""
                    + entry.name() + "\", v" + entry.valueId() + ");");
            }
        }
        return order;
    }

    // =========================================================================
    // 3. The artifact under javac --release 25 -proc:none and java
    // =========================================================================

    private static void testCompileAndRun(Baseline fixture) throws Exception {
        System.out.println("-- the production artifact compiles and runs under the "
            + "real toolchain --");
        Path workspace = Files.createTempDirectory("jvm-production-project-run");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the fixture project lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission = fixture.emission();
            Path source = workspace.resolve(className + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);

            List<String> modules = new ArrayList<>();
            List<List<ExportEntry>> entries = new ArrayList<>();
            for (ModuleId moduleId : project.modules().keySet()) {
                modules.add(moduleId.path());
                entries.add(exportsOf(project.modules().get(moduleId)));
            }

            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString()), workspace);
            check(javacRun.exitCode() == 0,
                "the production artifact compiles with javac --release 25 -proc:none: "
                    + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }

            // The artifact's own main: exit 0, empty output, no trace line.
            ProcessOutcome direct = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, className), workspace);
            check(direct.exitCode() == 0,
                "the executed artifact exits 0: exit=" + direct.exitCode() + " "
                    + direct.output());
            checkEq("", direct.stdout(),
                "the successful production run prints nothing on stdout");
            checkEq("", direct.stderr(),
                "the successful production run prints nothing on stderr");
            check(!direct.stdout().contains("R|") && !direct.stderr().contains("R|"),
                "the executed artifact emits no R| trace line");

            // The surface probe: the entry module's main drive runs once,
            // both per-module surfaces hold their declaration-order exports,
            // and a repeated dealMain() drive re-runs no initialized module.
            String driverName = "JvmProductionProjectProbe";
            Path driver = workspace.resolve(driverName + ".java");
            Files.writeString(driver, probeSource(className, driverName, modules, entries),
                StandardCharsets.UTF_8);
            ProcessOutcome probeJavac = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath + java.io.File.pathSeparator + classes.toString(),
                "-d", classes.toString(), driver.toAbsolutePath().toString()), workspace);
            check(probeJavac.exitCode() == 0,
                "the surface probe compiles: " + probeJavac.output());
            if (probeJavac.exitCode() != 0) {
                return;
            }
            ProcessOutcome probe = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, driverName), workspace);
            check(probe.exitCode() == 0 && probe.stdout().contains("PROBE-OK"),
                "the two per-module surfaces hold their declaration-order exports after "
                    + "the run, the entry main ran exactly once, and a repeated "
                    + "dealMain() drive re-runs no initialized module: exit="
                    + probe.exitCode() + " " + probe.output());
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 3b. The host-visible DEALRuntimeError: complete transport and named frames
    // =========================================================================

    /**
     * The host-error companion: a nested non-exported function raises the
     * typed boundary failure (the E8001 with the expected/actual pair and
     * the raising function's origin), the exported sync and async
     * functions call it across the module boundary, and the E8004
     * arithmetic overflow carries no expected/actual.
     */
    private static final String HOST_ERROR_LIB_SOURCE = """
        function inner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export function fail(): int {
          return inner();
        }

        async function ainner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export async function afail(): int {
          return await ainner();
        }

        export function boom(): int {
          return 2147483647 + 1;
        }

        export function echo(e: Error): int { throw e; }

        export function alias(): boolean {
          let first: Error = { code: "seed", message: "seed" };
          try { throw { code: "X", message: "x" }; } catch (caught) { first = caught; }
          first.code = "Z";
          let again: Error = { code: "seed", message: "seed" };
          try { throw first; } catch (caught) { again = caught; }
          return first === again && again.code === "Z";
        }
        """;

    /**
     * The host-capture entry: {@code run} reaches the companion failure
     * through a production call, {@code reraise} catches and rethrows it,
     * {@code leafFail} raises directly inside the exported function itself,
     * {@code swallow} handles a companion failure and returns normally
     * (the frame-cleanup arm), {@code arun}/{@code areraise} drive the same
     * shapes through {@code await}, {@code overflow} raises the
     * expected/actual-less E8004, {@code replaced} throws a new Error while
     * handling the caught one, {@code echo} rethrows its Error argument
     * across the module boundary, {@code alias} answers the
     * catch/rethrow/catch alias identity of a retained Error value, and
     * {@code mutate} edits a retained E8001 alias after the OUTER
     * replacement linked it as cause and then rethrows the OUTER error.
     */
    private static final String HOST_ERROR_APP_SOURCE = """
        import * as helper from "./lib";

        export function main(): null { return null; }

        export function run(): int { return helper.fail(); }

        export function reraise(): int {
          try { return helper.fail(); } catch (e) { throw e; }
        }

        export function leafFail(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export function swallow(): int {
          try { return helper.fail(); } catch (e) { return 0; }
        }

        export async function arun(): int { return await helper.afail(); }

        export async function areraise(): int {
          try { return await helper.afail(); } catch (e) { throw e; }
        }

        export function overflow(): int { return helper.boom(); }

        export function replaced(): int {
          try { return helper.fail(); }
          catch (e) { throw { code: "REPLACED", message: "handled" }; }
        }

        export function echo(e: Error): int { return helper.echo(e); }

        export function alias(): boolean { return helper.alias(); }

        export function mutate(): int {
          let saved: Error = { code: "seed", message: "seed" };

          function step(): int {
            try {
              try { return helper.fail(); }
              catch (e) {
                saved = e;
                throw { code: "OUTER", message: "outer" };
              }
            } catch (o) {
              saved.code = "CHANGED";
              saved.message = "changed";
              throw o;
            }
          }

          return step();
        }
        """;

    /** One host-captured error row: the artifact's own DEAL error fields. */
    private record HostErrorRow(String code, String message, String origin,
                                String expected, String actual, String frames,
                                String cause) {
    }

    private static void testHostErrorTransport() throws Exception {
        System.out.println("-- the host-visible DealError of the production "
            + "artifact: the complete transport through calls, await, and "
            + "catch/rethrow, with named frames --");
        Fixture fixture = compileProject(HOST_ERROR_LIB_SOURCE,
            HOST_ERROR_APP_SOURCE, "src/app.deal");
        Path workspace = Files.createTempDirectory("jvm-production-project-hosterror");
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            if (result.project() == null) {
                fail("the host-error fixture lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                    result.registries(), className, fixture.surface());
            Path source = workspace.resolve(className + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            SemanticOp arun = asyncEntry(project.modules().get(new ModuleId("app")),
                "arun");
            SemanticOp areraise = asyncEntry(project.modules().get(new ModuleId("app")),
                "areraise");
            check(arun != null && areraise != null,
                "the fixture records the async external entries of arun/areraise");
            if (arun == null || areraise == null) {
                return;
            }
            Path probe = workspace.resolve("HostProbe.java");
            Files.writeString(probe, jvmHostErrorProbe(className, arun, areraise),
                StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString(), probe.toAbsolutePath().toString()),
                workspace);
            check(javacRun.exitCode() == 0,
                "the host-error artifact and its host probe compile: "
                    + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, "HostProbe"),
                workspace);
            checkEq(0, run.exitCode(), "the host-error probe exits 0: "
                + run.output());
            Map<String, HostErrorRow> rows = hostErrorRows(run.stdout());
            checkEq(8, rows.size(),
                "the probe captures one host error per closed probe row: "
                    + run.output());

            // The production call arm: code/message/origin, the applicable
            // expected/actual pair, the named active frames, no cause. The
            // direct host invocation of the published callable names the
            // exported function too (app#run, the innermost production call
            // below it).
            HostErrorRow direct = rows.get("run");
            check(direct != null, "the probe captured the run error");
            if (direct == null) {
                return;
            }
            checkEq("E8001", direct.code(), "the companion failure's code");
            checkEq("expected int", direct.message(), "the companion failure's message");
            check(direct.origin().endsWith("src/lib.deal:3:16"),
                "the companion check's origin is the raising function's source "
                    + "location: " + direct.origin());
            checkEq("int", direct.expected(), "the applicable expected type");
            checkEq("string", direct.actual(), "the applicable actual kind");
            check(direct.frames().matches(
                    "lib#inner@F\\d+,lib#fail@F\\d+,app#run@F\\d+"),
                "the frames name the original and generated functions of every "
                    + "active DEAL call including the directly invoked export, "
                    + "innermost first: " + direct.frames());
            checkEq("-", direct.cause(), "a primary failure carries no cause");

            // Catch/rethrow: the original error identity survives the catch
            // and the rethrow (code/message/origin/expected/actual and the
            // frames captured at the raise), no cause is invented, and the
            // active export is named in the rethrow's own frames.
            HostErrorRow reraise = rows.get("reraise");
            check(reraise != null, "the probe captured the reraise error");
            if (reraise != null) {
                checkEq(direct.code(), reraise.code(), "the rethrow keeps the code");
                checkEq(direct.message(), reraise.message(),
                    "the rethrow keeps the message");
                checkEq(direct.origin(), reraise.origin(),
                    "the rethrow keeps the original source location");
                checkEq(direct.expected(), reraise.expected(),
                    "the rethrow keeps the expected type");
                checkEq(direct.actual(), reraise.actual(),
                    "the rethrow keeps the actual kind");
                check(reraise.frames().matches(
                        "lib#inner@F\\d+,lib#fail@F\\d+,app#reraise@F\\d+"),
                    "the rethrow keeps the frames captured at the raise, with "
                        + "its own directly invoked export named: "
                        + reraise.frames());
                checkEq("-", reraise.cause(), "the rethrow invents no cause");
            }

            // The leaf-export arm: a failure raised directly inside the
            // published callable is named by that callable's own frame — the
            // generated and original names of the invoked export.
            HostErrorRow leaf = rows.get("leafFail");
            check(leaf != null, "the probe captured the leaf-export error");
            if (leaf != null) {
                checkEq("E8001", leaf.code(), "the leaf failure's code");
                checkEq("expected int", leaf.message(),
                    "the leaf failure's message");
                check(leaf.origin().contains("src/app.deal:"),
                    "the leaf failure's origin is its own raising site: "
                        + leaf.origin());
                checkEq("int", leaf.expected(),
                    "the leaf failure's expected type");
                checkEq("string", leaf.actual(),
                    "the leaf failure's actual kind");
                check(leaf.frames().matches("app#leafFail@F\\d+"),
                    "the leaf failure's frames name the directly invoked export "
                        + "alone: " + leaf.frames());
                checkEq("-", leaf.cause(), "the leaf failure carries no cause");
            }

            // The success arm returns normally and leaves no active frame:
            // the cleanup probe below re-invokes the leaf export after it.
            check(run.stdout().contains("HOST-ERR|swallow|none|0"),
                "the handling export returns its own value: " + run.output());

            // The await arm: the awaited companion failure transports the
            // same complete surface through the artifact's async-entry
            // dispatch (the reserved entry the host invoker drives), and
            // its rethrow preserves it.
            HostErrorRow awaitDirect = rows.get("arun");
            HostErrorRow awaitReraise = rows.get("areraise");
            check(awaitDirect != null && awaitReraise != null,
                "the probe captured the await errors");
            if (awaitDirect != null) {
                checkEq("E8001", awaitDirect.code(), "the awaited failure's code");
                checkEq("expected int", awaitDirect.message(),
                    "the awaited failure's message");
                check(awaitDirect.origin().endsWith("src/lib.deal:13:16"),
                    "the awaited failure's origin is the raising async function's "
                        + "source location: " + awaitDirect.origin());
                checkEq("int", awaitDirect.expected(),
                    "the awaited failure's expected type");
                checkEq("string", awaitDirect.actual(),
                    "the awaited failure's actual kind");
                check(awaitDirect.frames().matches(
                        "lib#ainner@F\\d+,lib#afail@F\\d+,app#arun@F\\d+"),
                    "the await frames name every active DEAL call including the "
                        + "awaiting entry: " + awaitDirect.frames());
                checkEq("-", awaitDirect.cause(),
                    "the awaited failure carries no cause");
            }
            if (awaitReraise != null && awaitDirect != null) {
                checkEq(awaitDirect.code(), awaitReraise.code(),
                    "the await rethrow keeps the code");
                checkEq(awaitDirect.origin(), awaitReraise.origin(),
                    "the await rethrow keeps the original source location");
                checkEq(awaitDirect.expected(), awaitReraise.expected(),
                    "the await rethrow keeps the expected type");
                checkEq(awaitDirect.actual(), awaitReraise.actual(),
                    "the await rethrow keeps the actual kind");
                checkEq("-", awaitReraise.cause(),
                    "the await rethrow invents no cause");
                check(awaitReraise.frames().matches(
                        "lib#ainner@F\\d+,lib#afail@F\\d+,app#areraise@F\\d+"),
                    "the await rethrow keeps the original active frames with its "
                        + "own awaiting entry: " + awaitReraise.frames());
            }

            // The absent-field rule: the E8004 carries no expected/actual
            // and no cause, and its frames stay named — the directly invoked
            // export included.
            HostErrorRow overflow = rows.get("overflow");
            check(overflow != null, "the probe captured the overflow error");
            if (overflow != null) {
                checkEq("E8004", overflow.code(), "the overflow failure's code");
                checkEq("int out of safe range", overflow.message(),
                    "the overflow failure's message");
                check(overflow.origin().endsWith("src/lib.deal:22:10"),
                    "the overflow origin is the arithmetic site: "
                        + overflow.origin());
                checkEq("-", overflow.expected(),
                    "the overflow carries no expected type (absent, not empty)");
                checkEq("-", overflow.actual(),
                    "the overflow carries no actual kind (absent, not empty)");
                check(overflow.frames().matches(
                        "lib#boom@F\\d+,app#overflow@F\\d+"),
                    "the overflow frames name every active DEAL call: "
                        + overflow.frames());
                checkEq("-", overflow.cause(), "the overflow carries no cause");
            }

            // The applicable-cause rule: a new failure raised while handling
            // the caught one links the caught failure as its cause and names
            // the handler's own active frame.
            HostErrorRow replaced = rows.get("replaced");
            check(replaced != null, "the probe captured the replacement error");
            if (replaced != null) {
                checkEq("REPLACED", replaced.code(),
                    "the replacement failure's code");
                checkEq("handled", replaced.message(),
                    "the replacement failure's message");
                check(replaced.origin().contains("src/app.deal:"),
                    "the replacement failure raises at its own THROW origin: "
                        + replaced.origin());
                checkEq("-", replaced.expected(),
                    "the replacement failure carries no expected type");
                checkEq("-", replaced.actual(),
                    "the replacement failure carries no actual kind");
                check(replaced.frames().matches("app#replaced@F\\d+"),
                    "the replacement failure's frames name the handling export: "
                        + replaced.frames());
                checkEq("E8001", replaced.cause(),
                    "the replacement failure links the caught failure as its "
                        + "cause");
            }

            // The saved-cause-alias arm: the E8001 alias is edited after the
            // OUTER replacement linked it as cause, and the OUTER error is
            // rethrown; the linked cause keeps the transport captured at
            // catch time (never the later mutation).
            String mutated = hostFieldLine(run.stdout(), "HOST-CAUSE|mutate|");
            check(mutated != null, "the probe recorded the mutated-cause row: "
                + run.output());
            if (mutated != null) {
                String[] parts = mutated.split("\\|", -1);
                checkEq(9, parts.length,
                    "the mutated-cause row carries every compared field: " + mutated);
                if (parts.length == 9) {
                    checkEq("OUTER", parts[0],
                        "the rethrown failure keeps its code: " + mutated);
                    checkEq("outer", parts[1],
                        "the rethrown failure keeps its message: " + mutated);
                    checkEq("E8001", parts[2],
                        "the linked cause stays the E8001, never the edited "
                            + "alias: " + mutated);
                    checkEq("expected int", parts[3],
                        "the linked cause keeps its captured message: " + mutated);
                    check(parts[4].endsWith("src/lib.deal:3:16"),
                        "the linked cause keeps its captured source location: "
                            + mutated);
                    checkEq("int", parts[5],
                        "the linked cause keeps its captured expected type: "
                            + mutated);
                    checkEq("string", parts[6],
                        "the linked cause keeps its captured actual kind: "
                            + mutated);
                    check(parts[7].matches(
                            "lib#inner@F\\d+,lib#fail@F\\d+,app#step@F\\d+,"
                                + "app#mutate@F\\d+"),
                        "the linked cause keeps its captured frames: " + mutated);
                    checkEq("-", parts[8],
                        "the linked cause gains no invented cause: " + mutated);
                }
            }

            // The cleanup arm: after the failure and the success probes, a
            // fresh direct invocation of the leaf export names exactly its
            // own frame — every host-visible push was balanced by its pop.
            String clean = hostFieldLine(run.stdout(), "HOST-CLEAN|leafFail|");
            check(clean != null, "the probe recorded the frame-cleanup row: "
                + run.output());
            if (clean != null) {
                check(clean.matches("app#leafFail@F\\d+"),
                    "the frame stack is clean before the fresh invocation and "
                        + "balanced after it: " + clean);
            }

            // The identity arm: the DEAL probe's own catch/rethrow/catch
            // alias identity, and the host transport identity of an
            // unchanged rethrow (the host observes the identical failure
            // object and the identical reified Error value).
            Map<String, String> identities = hostIdentityRows(run.stdout());
            checkEq("true", identities.get("alias"),
                "the catch/rethrow/catch alias identity holds on the artifact "
                    + "(the rethrown error caught again is the identical Error "
                    + "value): " + run.output());
            String echo = identities.get("echo");
            check(echo != null, "the probe recorded the echo identity: "
                + run.output());
            if (echo != null) {
                String[] echoParts = echo.split("\\|", -1);
                checkEq(5, echoParts.length,
                    "the echo identity row carries every compared field: " + echo);
                if (echoParts.length == 5) {
                    checkEq("true", echoParts[0],
                        "an unchanged rethrow escapes as the identical DealError "
                            + "object: " + echo);
                    checkEq("true", echoParts[1],
                        "the rethrown error caught again reifies to the identical "
                            + "Error value: " + echo);
                    checkEq("E8001", echoParts[2],
                        "the rethrown failure keeps its code: " + echo);
                    check(echoParts[3].endsWith("src/lib.deal:3:16"),
                        "the rethrown failure keeps its original source location: "
                            + echo);
                    checkEq("-", echoParts[4],
                        "the rethrown failure invents no cause: " + echo);
                }
            }
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    /** The async external entry op of one export name, or {@code null}. */
    private static SemanticOp asyncEntry(LoweredModuleUnit unit, String exportName) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                    && payload.async() && payload.exportName().equals(exportName)) {
                return op;
            }
        }
        return null;
    }

    // =========================================================================
    // =========================================================================
    // 4. The non-entry module emission (ISSUE-0749 review)
    // =========================================================================

    /**
     * The non-entry module emission contract: the emitted artifact carries
     * no async-export host surface, so its production terminal must not
     * reference one, and the source must compile and run under the real
     * toolchain (the retained public {@code emitProductionModule} API).
     */
    private static void testNonEntryModuleEmissionCompiles(Baseline fixture)
            throws Exception {
        System.out.println("-- the non-entry module emission compiles and runs "
            + "under the real toolchain --");
        SemanticLowerer.ProjectLoweringResult result = fixture.result();
        if (result.project() == null) {
            fail("the fixture project lowers: " + result.diagnostics());
            return;
        }
        LoweredModuleUnit libUnit = result.project().modules().get(LIB);
        JvmSemanticEmitter.EmissionResult emission =
            JvmSemanticEmitter.emitProductionModule(libUnit, result.tables().get(LIB),
                false, "Library");
        checkEq("Library", emission.className(),
            "the non-entry emission keeps using its className argument");
        check(!emission.source().contains("$asyncExportHost"),
            "the non-entry artifact references no async-export host surface");
        check(emission.source().contains(
                "System.out.println(\"DEAL_ERROR_CODE: \" + e.code);"),
            "the non-entry artifact keeps the retained DEAL_ERROR_CODE terminal");

        Path workspace = Files.createTempDirectory("jvm-production-non-entry");
        try {
            Path source = workspace.resolve("Library.java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString()), workspace);
            checkEq(0, javacRun.exitCode(),
                "the non-entry production artifact compiles with javac --release 25 "
                    + "-proc:none: " + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome direct = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, "Library"), workspace);
            checkEq(0, direct.exitCode(),
                "the non-entry artifact runs its module walk and exits 0: "
                    + direct.output());
            checkEq("", direct.stdout(),
                "the non-entry production run prints nothing on stdout");
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 5. The DEAL_ERROR_CODE terminal (E8004)
    // =========================================================================

    private static void testOverflowTerminal() throws Exception {
        System.out.println("-- the conversion-overflow fixture: exit 1 and the "
            + "DEAL_ERROR_CODE terminal --");
        Fixture fixture = compileProject(OVERFLOW_SOURCE, "src/main.deal");
        Path workspace = Files.createTempDirectory("jvm-production-project-overflow");
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            if (result.project() == null) {
                fail("the overflow fixture project lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            checkEq(List.of(new ModuleId("main")), List.copyOf(project.modules().keySet()),
                "the overflow fixture is the single entry module");
            String className = JvmBackend.classNameFor(project.entryModule().path());
            checkEq("Main", className, "the overflow entry class name is Main");
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                    result.registries(), className, fixture.surface());

            Path source = workspace.resolve(className + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString()), workspace);
            check(javacRun.exitCode() == 0,
                "the overflow artifact compiles with javac --release 25 -proc:none: "
                    + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, className), workspace);
            check(run.exitCode() == 1,
                "the overflow run exits 1: exit=" + run.exitCode() + " " + run.output());
            check(run.stdout().contains("DEAL_ERROR_CODE: E8004"),
                "the overflow run prints DEAL_ERROR_CODE: E8004 on stdout: "
                    + run.output());
            check(!run.stdout().contains("DEAL_ERROR_SNAPSHOT"),
                "the production artifact prints no snapshot line");
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 6. The shadowing-binding probe (ISSUE-0717, defect snippet 65)
    // =========================================================================

    /**
     * The shadowing-binding shape: a shadowing declaration's
     * initializer and every reference before it resolve against the
     * enclosing binding, and the loop/catch frame fast path never
     * overrides a nearer shadowing declaration. The production artifact
     * must compile and exit 0 under the real toolchain.
     */
    private static void testShadowingBindings() throws Exception {
        System.out.println("-- the shadowing-binding fixture compiles and runs "
            + "under the real toolchain --");
        String shadowSource = """
            export function main(): null {
              let x: int = 3;
              {
                let x: int = x + 1;
                if (x !== 4) { throw { code: "SHADOW_FAIL", message: "shadowing initializer" }; }
              }
              if (x !== 3) { throw { code: "SHADOW_FAIL", message: "block outer restored" }; }
              if (true) {
                let x: int = 9;
                if (x !== 9) { throw { code: "SHADOW_FAIL", message: "if-body shadow" }; }
              }
              if (x !== 3) { throw { code: "SHADOW_FAIL", message: "if-body outer restored" }; }
              while (false) {
                let x: int = 11;
                if (x !== 11) { throw { code: "SHADOW_FAIL", message: "loop-body shadow" }; }
              }
              if (x !== 3) { throw { code: "SHADOW_FAIL", message: "loop-body outer restored" }; }
              let sum: int = 0;
              let values: int[] = [1, 2];
              for (let v: int of values) {
                let before: int = v;
                let v: int = 10;
                sum = sum + before + v;
              }
              if (sum !== 23) { throw { code: "SHADOW_FAIL", message: "for-of shadow" }; }
              let h: int = 3;
              let hsum: int = 0;
              for (let h: int = h + 1; hsum < 9;) { hsum = hsum + h; }
              if (hsum !== 12) { throw { code: "SHADOW_FAIL", message: "for-head initializer" }; }
              if (h !== 3) { throw { code: "SHADOW_FAIL", message: "for-head outer restored" }; }
              let nl: string | null = "ab";
              if (nl !== null) {
                for (let nl: int = 0; false;) {}
                let nlText: string = nl;
                if (nlText !== "ab") { throw { code: "SHADOW_FAIL", message: "for-head shadow restored" }; }
              }
              let nb: string | null = "cd";
              if (nb !== null) {
                { let nb: int = 0; if (true) {} else {} }
                let nbText: string = nb;
                if (nbText !== "cd") { throw { code: "SHADOW_FAIL", message: "branch shadow restored" }; }
              }
              try {
                throw { code: "SHADOW_X", message: "catch shadow" };
              } catch (e) {
                let e: Error = e;
                if (e.code !== "SHADOW_X") { throw { code: "SHADOW_FAIL", message: "catch shadow" }; }
              }
              return null;
            }
            """;
        Fixture fixture = compileProject(shadowSource, "src/main.deal");
        Path workspace = Files.createTempDirectory("jvm-production-project-shadow");
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            if (result.project() == null) {
                fail("the shadowing-binding fixture project lowers: "
                    + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                    result.registries(), className, fixture.surface());

            Path source = workspace.resolve(className + ".java");
            Files.writeString(source, emission.source(), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString()), workspace);
            check(javacRun.exitCode() == 0,
                "the shadowing-binding artifact compiles with javac --release 25 "
                    + "-proc:none: " + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, className), workspace);
            checkEq(0, run.exitCode(),
                "the shadowing-binding artifact exits 0: " + run.output());
            checkEq("", run.stdout(),
                "the shadowing-binding run prints nothing on stdout");
            checkEq("", run.stderr(),
                "the shadowing-binding run prints nothing on stderr");
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 7. The dynamic-key computed table read (ISSUE-0717 review)
    // =========================================================================

    /**
     * The dynamic-key computed table read: a variable key and an
     * effectful key expression both resolve the same read shape as the
     * literal spelling (receiver before key, contextual validation), and
     * a missing key reads null at a nullable position. The production
     * artifact must compile and exit 0 under the real toolchain.
     */
    private static void testDynamicTableRead() throws Exception {
        System.out.println("-- the dynamic-key computed table read compiles and "
            + "runs under the real toolchain --");
        String source = """
            export function main(): null {
              let t: table = { x: 7 };
              let k: string = "x";
              let n: int = t[k];
              if (n !== 7) { throw { code: "DYN_FAIL", message: "variable key" }; }
              let u: table = {};
              let mk: () => string = function(): string { return "y"; };
              u[mk()] = 9;
              let m: int = u[mk()];
              if (m !== 9) { throw { code: "DYN_FAIL", message: "effectful key" }; }
              let q: table = {};
              let miss: int | null = q[k];
              if (miss !== null) { throw { code: "DYN_FAIL", message: "missing" }; }
              let v: table = { s: 1 };
              let nn: int | null = v["s"];
              let got: int = 0;
              if (nn !== null) { got = nn; }
              if (got !== 1) { throw { code: "DYN_FAIL", message: "nullable" }; }
              return null;
            }
            """;
        Fixture fixture = compileProject(source, "src/main.deal");
        Path workspace = Files.createTempDirectory("jvm-production-project-dyntable");
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            if (result.project() == null) {
                fail("the dynamic-table-read fixture project lowers: "
                    + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            String className = JvmBackend.classNameFor(project.entryModule().path());
            JvmSemanticEmitter.EmissionResult emission =
                JvmSemanticEmitter.emitProductionProject(project, result.tables(),
                    result.registries(), className, fixture.surface());

            Path artifact = workspace.resolve(className + ".java");
            Files.writeString(artifact, emission.source(), StandardCharsets.UTF_8);
            Path classes = workspace.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                artifact.toAbsolutePath().toString()), workspace);
            check(javacRun.exitCode() == 0,
                "the dynamic-table-read artifact compiles with javac --release 25 "
                    + "-proc:none: " + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classpath + java.io.File.pathSeparator + classes, className), workspace);
            checkEq(0, run.exitCode(),
                "the dynamic-table-read artifact exits 0: " + run.output());
            checkEq("", run.stdout(),
                "the dynamic-table-read run prints nothing on stdout");
            checkEq("", run.stderr(),
                "the dynamic-table-read run prints nothing on stderr");
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // Driver sources
    // =========================================================================

    /**
     * The surface probe: the artifact's own {@code main} runs, the registry
     * carries one surface per module keyed by the dotted module path, each
     * surface holds its declared exports in declaration order with the
     * published {@code JvmRuntime.FunctionValue} carrier, the entry drive
     * initialized every module once, and a repeated {@code dealMain()}
     * drive re-runs no initialized module and keeps every surface.
     */
    private static String probeSource(String className, String driverName,
            List<String> modules, List<List<ExportEntry>> entries) {
        StringBuilder source = new StringBuilder();
        source.append("public class ").append(driverName).append(" {\n");
        source.append("  static int failures = 0;\n");
        source.append("  static void check(boolean condition, String what) {\n");
        source.append("    if (!condition) { failures++; "
            + "System.out.println(\"PROBE-FAIL: \" + what); }\n");
        source.append("  }\n");
        source.append("  public static void main(String[] args) {\n");
        source.append("    ").append(className).append(".main(new String[0]);\n");
        source.append("    java.util.LinkedHashMap<String, deal.codegen.jvm.JvmRuntime.Table>"
            + " surfaces = ").append(className).append(".EXPORT_SURFACES;\n");
        source.append("    check(surfaces.size() == ").append(modules.size())
            .append(", \"one surface per module of the closure; got \" + surfaces.size());\n");
        source.append("    check(new java.util.ArrayList<>(surfaces.keySet())"
            + ".equals(java.util.List.of(").append(javaStringList(modules))
            .append(")), \"the registry is keyed by the dotted module path in closure "
            + "order; got \" + surfaces.keySet());\n");
        for (int i = 0; i < modules.size(); i++) {
            String module = modules.get(i);
            List<ExportEntry> moduleEntries = entries.get(i);
            List<String> names = new ArrayList<>();
            for (ExportEntry entry : moduleEntries) {
                names.add(entry.name());
            }
            source.append("    { deal.codegen.jvm.JvmRuntime.Table surface = surfaces.get(")
                .append(javaString(module)).append(");\n");
            source.append("      check(surface != null, ")
                .append(javaString("the surface of " + module + " exists")).append(");\n");
            source.append("      if (surface != null) {\n");
            source.append("        check(new java.util.ArrayList<>(surface.entries.keySet())"
                + ".equals(java.util.List.of(").append(javaStringList(names))
                .append(")), ").append(javaString("the module " + module
                    + " surface holds its declared exports in declaration order; got "))
                .append(" + surface.entries.keySet());\n");
            for (ExportEntry entry : moduleEntries) {
                source.append("        { Object value = surface.entries.get(")
                    .append(javaString(entry.name())).append(");\n");
                source.append("          check(value instanceof "
                    + "deal.codegen.jvm.JvmRuntime.FunctionValue, ")
                    .append(javaString("the published " + module + "#" + entry.name()
                        + " is a JvmRuntime.FunctionValue carrier; got "))
                    .append(" + value);\n");
                source.append("          if (value instanceof "
                    + "deal.codegen.jvm.JvmRuntime.FunctionValue carrier) {\n");
                source.append("            check(").append(javaString(entry.spec()))
                    .append(".equals(carrier.spec), ")
                    .append(javaString("the published " + module + "#" + entry.name()
                        + " carries its canonical spec text; got "))
                    .append(" + carrier.spec);\n");
                source.append("          }\n        }\n");
            }
            source.append("      }\n    }\n");
        }
        // The entry drive completed every module's init walk exactly once:
        // the runtime's module-init state proves the guarded walks will not
        // run again (the ENTRY_INVOKE delegation lives inside the entry
        // module's single walk).
        source.append("    check(!deal.codegen.jvm.JvmRuntime.moduleInitNeeded(\"lib\"), "
            + "\"the entry drive initialized lib exactly once\");\n");
        source.append("    check(!deal.codegen.jvm.JvmRuntime.moduleInitNeeded(\"app\"), "
            + "\"the entry drive initialized app exactly once (the one "
            + "ENTRY_INVOKE delegation)\");\n");
        // A repeated dealMain() drive re-runs no initialized module and
        // keeps every published surface.
        source.append("    ").append(className).append(".dealMain();\n");
        source.append("    check(surfaces == ").append(className)
            .append(".EXPORT_SURFACES, \"the repeated dealMain() drive keeps the "
                + "registry instance\");\n");
        source.append("    check(surfaces.size() == ").append(modules.size())
            .append(", \"the repeated dealMain() drive keeps every surface; got \" + "
                + "surfaces.size());\n");
        source.append("    check(!deal.codegen.jvm.JvmRuntime.moduleInitNeeded(\"lib\"), "
            + "\"the repeated drive re-runs no initialized module (lib)\");\n");
        source.append("    check(!deal.codegen.jvm.JvmRuntime.moduleInitNeeded(\"app\"), "
            + "\"the repeated drive re-runs no initialized module (app)\");\n");
        for (String module : modules) {
            source.append("    check(surfaces.containsKey(").append(javaString(module))
                .append("), ").append(javaString("the repeated drive left the " + module
                    + " surface intact")).append(");\n");
        }
        source.append("    if (failures > 0) { System.out.println(\"PROBE-FAIL: \" + "
            + "failures + \" checks failed\"); System.exit(1); }\n");
        source.append("    System.out.println(\"PROBE-OK\");\n");
        source.append("  }\n}\n");
        return source.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The emitted JVM surface-creation statements, in text order. */
    private static List<String> jvmSurfaceCreations(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("    exportSurface(\"") && line.endsWith(");")
                    && !line.contains(").write(")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String javaStringList(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(javaString(values.get(i)));
        }
        return text.toString();
    }

    private static String javaString(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                default -> text.append(c);
            }
        }
        return text.append('"').toString();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + 1);
        }
        return count;
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {

        /** The combined output for diagnostics. */
        String output() {
            return "stdout=" + stdout.replace("\n", "\\n")
                + " stderr=" + stderr.replace("\n", "\\n");
        }
    }

    /**
     * The test's classpath entries resolved against the test process's
     * working directory: the emitted-artifact toolchain runs in an
     * isolated workspace directory, so a relative {@code build} entry
     * would not resolve there.
     */
    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(java.io.File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(java.io.File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        Path stderrFile = Files.createTempFile(workDir, "stderr", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectError(stderrFile.toFile());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Files.deleteIfExists(stderrFile);
        return new ProcessOutcome(exit, stdout, stderr);
    }

    /**
     * The generated JVM host probe: invokes every probe export once — the
     * sync exports through the artifact's published surface, and the async
     * exports through the artifact's reserved {@code ae<opId>} async-entry
     * dispatch (the entry the host invoker drives) — and prints the raw
     * {@code DealError} fields of the uncaught failure, with {@code -} for
     * every absent field.
     */
    private static String jvmHostErrorProbe(String className, SemanticOp arun,
            SemanticOp areraise) {
        return "import deal.codegen.jvm.JvmRuntime;\n"
            + "\n"
            + "public final class HostProbe {\n"
            + "  static final String[] SYNC = { \"run\", \"reraise\","
            + " \"leafFail\", \"swallow\", \"overflow\", \"replaced\","
            + " \"mutate\" };\n"
            + "  public static void main(String[] args) throws Exception {\n"
            + "    " + className + ".main(new String[0]);\n"
            + "    JvmRuntime.Table surface = " + className
            + ".EXPORT_SURFACES.get(\"app\");\n"
            + "    for (String name : SYNC) {\n"
            + "      JvmRuntime.FunctionValue fn =\n"
            + "          (JvmRuntime.FunctionValue) surface.read(name);\n"
            + "      try {\n"
            + "        System.out.println(\"HOST-ERR|\" + name + \"|none|\"\n"
            + "            + fn.fn.invoke(new Object[0]));\n"
            + "      } catch (Throwable t) {\n"
            + "        report(name, t);\n"
            + "      }\n"
            + "    }\n"
            + "    drive(\"arun\", " + arun.opId().id() + "L);\n"
            + "    drive(\"areraise\", " + areraise.opId().id() + "L);\n"
            + "    checkIdentity(surface);\n"
            + "    checkCleanup(surface);\n"
            + "    checkMutatedCause(surface);\n"
            + "  }\n"
            + "\n"
            + "  static void checkCleanup(JvmRuntime.Table surface) throws Exception {\n"
            + "    try {\n"
            + "      ((JvmRuntime.FunctionValue) surface.read(\"leafFail\"))\n"
            + "          .fn.invoke(new Object[0]);\n"
            + "      System.out.println(\"HOST-CLEAN|leafFail|none\");\n"
            + "    } catch (JvmRuntime.DealError e) {\n"
            + "      System.out.println(\"HOST-CLEAN|leafFail|\" + f(e.frames));\n"
            + "    }\n"
            + "  }\n"
            + "\n"
            + "  static void checkMutatedCause(JvmRuntime.Table surface) throws Exception {\n"
            + "    try {\n"
            + "      ((JvmRuntime.FunctionValue) surface.read(\"mutate\"))\n"
            + "          .fn.invoke(new Object[0]);\n"
            + "      System.out.println(\"HOST-CAUSE|mutate|none\");\n"
            + "    } catch (JvmRuntime.DealError e) {\n"
            + "      JvmRuntime.DealError c = e.cause;\n"
            + "      System.out.println(\"HOST-CAUSE|mutate|\" + e.code + \"|\" + e.msg"
            + " + \"|\" + (c == null ? \"-|-|-|-|-|-\" : c.code + \"|\" + c.msg"
            + " + \"|\" + f(c.origin) + \"|\" + f(c.expected) + \"|\" + f(c.actual)"
            + " + \"|\" + f(c.frames) + \"|\""
            + " + (c.cause == null ? \"-\" : c.cause.code)));\n"
            + "    }\n"
            + "  }\n"
            + "\n"
            + "  static void checkIdentity(JvmRuntime.Table surface) throws Exception {\n"
            + "    Object alias = ((JvmRuntime.FunctionValue) surface.read(\"alias\"))\n"
            + "        .fn.invoke(new Object[0]);\n"
            + "    System.out.println(\"HOST-ID|alias|\" + alias);\n"
            + "    JvmRuntime.DealError captured = null;\n"
            + "    try {\n"
            + "      ((JvmRuntime.FunctionValue) surface.read(\"run\"))\n"
            + "          .fn.invoke(new Object[0]);\n"
            + "    } catch (JvmRuntime.DealError e) {\n"
            + "      captured = e;\n"
            + "    }\n"
            + "    if (captured == null) {\n"
            + "      System.out.println(\"HOST-ID|echo|nocapture\");\n"
            + "      return;\n"
            + "    }\n"
            + "    JvmRuntime.ErrorValue value = captured.reify();\n"
            + "    try {\n"
            + "      ((JvmRuntime.FunctionValue) surface.read(\"echo\"))\n"
            + "          .fn.invoke(new Object[]{ value });\n"
            + "      System.out.println(\"HOST-ID|echo|none\");\n"
            + "    } catch (JvmRuntime.DealError e) {\n"
            + "      System.out.println(\"HOST-ID|echo|\" + (e == captured) + \"|\"\n"
            + "          + (e.reify() == value) + \"|\" + e.code + \"|\" + e.origin\n"
            + "          + \"|\" + (e.cause == null ? \"-\" : e.cause.code));\n"
            + "    }\n"
            + "  }\n"
            + "\n"
            + "  static void drive(String name, long opId) throws Exception {\n"
            + "    java.lang.reflect.Method entry = " + className
            + ".class.getMethod(\"ae\" + opId, String.class,\n"
            + "        boolean.class, Object[].class);\n"
            + "    try {\n"
            + "      System.out.println(\"HOST-ERR|\" + name + \"|none|\"\n"
            + "          + entry.invoke(null, \"-\", true, new Object[0]));\n"
            + "    } catch (java.lang.reflect.InvocationTargetException e) {\n"
            + "      report(name, e.getCause());\n"
            + "    }\n"
            + "  }\n"
            + "\n"
            + "  static void report(String name, Throwable t) {\n"
            + "    Throwable c = t;\n"
            + "    while (!(c instanceof JvmRuntime.DealError) && c.getCause() != null) {\n"
            + "      c = c.getCause();\n"
            + "    }\n"
            + "    if (!(c instanceof JvmRuntime.DealError e)) {\n"
            + "      System.out.println(\"HOST-ERR|\" + name + \"|raw|\" + t);\n"
            + "      return;\n"
            + "    }\n"
            + "    System.out.println(\"HOST-ERR|\" + name + \"|\" + e.code + \"|\" + e.msg\n"
            + "        + \"|\" + e.origin + \"|\" + f(e.expected) + \"|\" + f(e.actual)\n"
            + "        + \"|\" + f(e.frames) + \"|\"\n"
            + "        + (e.cause == null ? \"-\" : e.cause.code));\n"
            + "  }\n"
            + "\n"
            + "  static String f(String v) { return v == null ? \"-\" : v; }\n"
            + "}\n";
    }

    /**
     * The captured host-error rows keyed by probe export; a row whose
     * shape is not the closed {@code HOST-ERR} line is skipped (the caller
     * reports the missing row).
     */
    private static Map<String, HostErrorRow> hostErrorRows(String stdout) {
        Map<String, HostErrorRow> rows = new LinkedHashMap<>();
        for (String line : stdout.split("\n", -1)) {
            if (!line.startsWith("HOST-ERR|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 9) {
                continue;
            }
            rows.put(parts[1], new HostErrorRow(parts[2], parts[3], parts[4],
                parts[5], parts[6], parts[7], parts[8]));
        }
        return rows;
    }

    /**
     * The payload of the single probe line with one prefix (the text after
     * the prefix), or {@code null} when the line is absent.
     */
    private static String hostFieldLine(String stdout, String prefix) {
        for (String line : stdout.split("\n", -1)) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * The probe's identity rows keyed by probe name: the payload of every
     * {@code HOST-ID} line (the fields after the name) keyed by the name.
     */
    private static Map<String, String> hostIdentityRows(String stdout) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : stdout.split("\n", -1)) {
            if (!line.startsWith("HOST-ID|")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            StringBuilder payload = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) {
                    payload.append('|');
                }
                payload.append(parts[i]);
            }
            rows.put(parts[1], payload.toString());
        }
        return rows;
    }

    private static void writeFileIn(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (java.io.IOException ignored) {
                            // best effort
                        }
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== JVM Production Project Emission Tests (ISSUE-0641) ===\n");
        testEntrySignature();
        Fixture fixture = compileProject();
        try {
            Baseline baseline = prepare(fixture);
            testEmittedTextShapes(baseline);
            testCompileAndRun(baseline);
            testNonEntryModuleEmissionCompiles(baseline);
            testOverflowTerminal();
            testShadowingBindings();
            testDynamicTableRead();
            testHostErrorTransport();
        } finally {
            deleteRecursively(fixture.root());
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== JVM Production Project Emission Tests Passed ===");
    }
}
