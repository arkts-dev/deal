package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.lua.FfiEmissionInput;
import deal.codegen.lua.LuaSemanticEmitter;
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
import deal.semantic.ir.ValueId;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LuaProductionProjectEmissionTest {

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
    // Fixtures: two-module projects (the implementation import makes the
    // closure carry two modules without any cross-module call)
    // =========================================================================

    private static final ModuleId LIB = new ModuleId("lib");
    private static final ModuleId APP = new ModuleId("app");

    private static final String LIB_SOURCE = """
        export class Counter {
          n: int = 0;
        }

        export function add(a: int, b: int): int {
          return a + b;
        }

        export function twice(x: int): int {
          return x * 2;
        }
        """;

    /**
     * The observable one-main probe: {@code main} publishes one
     * {@code std/console.log} effect byte line per execution, and the
     * entry module also exports {@code label} so its surface carries more
     * than the delegated entry.
     */
    private static final String CONSOLE_APP_SOURCE = """
        import * as lib from "./lib"
        import * as console from "std/console"

        export function main(): null {
          console.log("PROBE|MAIN-ONCE");
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    /**
     * The non-entry-main probe fixture: {@code lib} exports its own
     * {@code main} with an observable effect, so the fixture proves the
     * production chunk delegates the entry module's {@code ENTRY_INVOKE}
     * only (lib's delegation op and its delegated CALL stay skipped).
     */
    private static final String LIB_WITH_MAIN_SOURCE = """
        import * as console from "std/console"

        export function add(a: int, b: int): int {
          return a + b;
        }

        export function main(): null {
          console.log("PROBE|LIB-MAIN");
          return null;
        }
        """;

    /** The covered-construct fixture: runs clean with no output. */
    private static final String QUIET_APP_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    /** The conversion-overflow fixture: E8004 at execution. */
    private static final String OVERFLOW_APP_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let x: int = 2147483647 + 1;
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    private record Fixture(
        Path root,
        CheckedProjectInput checkedProject,
        ProjectInterfaceIndex index,
        List<SemanticRequirementManifest> manifests,
        HostDeclarationSurface surface,
        Map<ModuleId, FfiGeneratedModule> externCModules,
        Map<ModuleId, CanonicalModuleIdentity> declarationIdentities,
        ExecutableLoweredProject producedProject, String lua) {
    }

    private record Baseline(
        SemanticLowerer.ProjectLoweringResult result,
        HostDeclarationSurface surface,
        String lua) {
    }

    private static Baseline prepare(Fixture fixture, List<Path> roots) {
        roots.add(fixture.root());
        SemanticLowerer.ProjectLoweringResult result = lower(fixture);
        String lua = fixture.lua();
        return new Baseline(result, fixture.surface(), lua);
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Fixture compileProject(String appSource) throws Exception {
        return compileProject(LIB_SOURCE, appSource);
    }

    private static Fixture compileProject(String libSource, String appSource)
            throws Exception {
        Path root = Files.createTempDirectory("lua-production-project");
        writeFileIn(root, "src/lib.deal", libSource);
        writeFileIn(root, "src/app.deal", appSource);
        Path entry = root.resolve("src/app.deal").toAbsolutePath();
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
            new LinkedHashMap<>(), orchestrator.producedProject(),
            Files.readString(output.resolve("app.lua"), StandardCharsets.UTF_8));
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
    // 1. The entry's signature: project + tables + registries, nothing else
    // =========================================================================

    private static void testEntrySignatureAndInputs() throws Exception {
        System.out.println("-- the production project entry consumes only the "
            + "validated project, the tables, and the registries --");

        Method entry = LuaSemanticEmitter.class.getDeclaredMethod("emitProductionProject",
            ExecutableLoweredProject.class, Map.class, Map.class,
            HostDeclarationSurface.class);
        check(java.lang.reflect.Modifier.isStatic(entry.getModifiers())
                && java.lang.reflect.Modifier.isPublic(entry.getModifiers()),
            "emitProductionProject is a public static entry");
        checkEq(String.class, entry.getReturnType(),
            "the entry returns the one project artifact source text");

        Class<?>[] parameters = entry.getParameterTypes();
        checkEq(List.of(ExecutableLoweredProject.class, Map.class, Map.class,
                HostDeclarationSurface.class),
            List.of(parameters),
            "the entry takes exactly the validated project, the tables, the "
                + "registries, and the host declaration surface");
        for (Parameter parameter : entry.getParameters()) {
            if (parameter.getType() == Map.class) {
                checkEq("java.util.Map", parameter.getType().getTypeName(),
                    "the tables/registries parameters are the per-module maps");
            }
        }
        List<String> genericTypes = new ArrayList<>();
        for (java.lang.reflect.Type type : entry.getGenericParameterTypes()) {
            genericTypes.add(type.getTypeName());
        }
        checkEq(List.of(
                "deal.semantic.ir.ExecutableLoweredProject",
                "java.util.Map<deal.semantic.ir.ModuleId, "
                    + "deal.semantic.ir.StructuredBodyTable>",
                "java.util.Map<deal.semantic.ir.ModuleId, "
                    + "deal.semantic.ir.ClassFactoryRegistry>",
                "deal.semantic.HostDeclarationSurface"),
            genericTypes,
            "the entry's declared inputs are exactly the project lowering result's "
                + "tables and registries plus the host declaration surface");

        for (Parameter parameter : entry.getParameters()) {
            String typeName = parameter.getType().getName();
            for (String forbidden : List.of("deal.ast.", "deal.checker.",
                    "deal.parser.", "Route", "MigrationPlanner", "CapabilityRegistry",
                    "HostModuleDeclarations", "FfiGenerated",
                    "CanonicalModuleIdentity", "CheckResult", "ProgramNode")) {
                check(!typeName.contains(forbidden),
                    "emitProductionProject takes no " + forbidden + " input: "
                        + typeName);
            }
        }

        // The pre-existing entries keep their signatures.
        Method trace = LuaSemanticEmitter.class.getDeclaredMethod("emitProject",
            ExecutableLoweredProject.class, Map.class, Map.class);
        checkEq(String.class, trace.getReturnType(),
            "emitProject keeps its signature and return type");
        Method module = LuaSemanticEmitter.class.getDeclaredMethod("emitProductionModule",
            deal.semantic.ir.LoweredModuleUnit.class,
            deal.semantic.ir.StructuredBodyTable.class, boolean.class);
        checkEq(String.class, module.getReturnType(),
            "emitProductionModule keeps its signature and return type");

        // The entry's null guards: the four declared inputs are required.
        try {
            LuaSemanticEmitter.emitProductionProject(null, Map.of(), Map.of(),
                new HostDeclarationSurface(Map.of()));
            fail("a null project is rejected");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Exception unexpected) {
            fail("a null project fails with "
                + unexpected.getClass().getSimpleName() + ", not NullPointerException");
        }

        Method ffiEntry = LuaSemanticEmitter.class.getDeclaredMethod(
            "emitProductionProject", ExecutableLoweredProject.class, Map.class,
            Map.class, HostDeclarationSurface.class, FfiEmissionInput.class);
        check(java.lang.reflect.Modifier.isStatic(ffiEntry.getModifiers())
                && java.lang.reflect.Modifier.isPublic(ffiEntry.getModifiers()),
            "the FFI-capable emitProductionProject is a public static entry");
        checkEq(String.class, ffiEntry.getReturnType(),
            "the FFI-capable entry returns the one project artifact source text");
        checkEq(List.of(ExecutableLoweredProject.class, Map.class, Map.class,
                HostDeclarationSurface.class, FfiEmissionInput.class),
            List.of(ffiEntry.getParameterTypes()),
            "the FFI-capable entry takes the four landed inputs plus exactly "
                + "the FFI emission input");
        List<String> ffiGenericTypes = new ArrayList<>();
        for (java.lang.reflect.Type type : ffiEntry.getGenericParameterTypes()) {
            ffiGenericTypes.add(type.getTypeName());
        }
        checkEq(List.of(
                "deal.semantic.ir.ExecutableLoweredProject",
                "java.util.Map<deal.semantic.ir.ModuleId, "
                    + "deal.semantic.ir.StructuredBodyTable>",
                "java.util.Map<deal.semantic.ir.ModuleId, "
                    + "deal.semantic.ir.ClassFactoryRegistry>",
                "deal.semantic.HostDeclarationSurface",
                "deal.codegen.lua.FfiEmissionInput"),
            ffiGenericTypes,
            "the FFI-capable entry's declared inputs are the four landed "
                + "inputs plus the FFI emission input");
        for (Parameter parameter : ffiEntry.getParameters()) {
            String typeName = parameter.getType().getName();
            for (String forbidden : List.of("deal.ast.", "deal.checker.",
                    "deal.parser.", "Route", "MigrationPlanner", "CapabilityRegistry",
                    "HostModuleDeclarations", "FfiGenerated",
                    "CanonicalModuleIdentity", "CheckResult", "ProgramNode")) {
                check(!typeName.contains(forbidden),
                    "the FFI-capable entry takes no " + forbidden + " input: "
                        + typeName);
            }
        }
        try {
            LuaSemanticEmitter.emitProductionProject(null, Map.of(), Map.of(),
                new HostDeclarationSurface(Map.of()),
                new FfiEmissionInput(Map.of(), ""));
            fail("a null project is rejected by the FFI-capable entry");
        } catch (NullPointerException expected) {
            passed++;
        } catch (Exception unexpected) {
            fail("a null project fails with "
                + unexpected.getClass().getSimpleName() + ", not NullPointerException");
        }
    }

    // =========================================================================
    // 2. The emitted chunk: one artifact, trace suppressed, the terminal
    // =========================================================================

    private static void testEmittedChunkText(Baseline fixture) throws Exception {
        System.out.println("-- the emitted production chunk: one artifact, the trace "
            + "protocol suppressed, the DEAL_ERROR_CODE terminal --");
        {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the fixture project lowers: " + result.diagnostics());
                return;
            }
            ExecutableLoweredProject project = result.project();
            checkEq(List.of(LIB, APP), List.copyOf(project.modules().keySet()),
                "the closure carries the two modules in dependency order");
            checkEq(APP, project.entryModule(), "the entry module is app");

            String lua = fixture.lua();

            // Exactly one chunk: one header, one prelude, one deferred main,
            // one entry-surface return.
            checkEq(1, countOccurrences(lua,
                    "-- deal.semantic-ir/1 shared LuaJIT artifact"),
                "the artifact carries exactly one chunk header");
            checkEq(1, countOccurrences(lua, "-- ==== shared runtime prelude ===="),
                "the artifact carries the shared runtime prelude exactly once");
            checkEq(1, countOccurrences(lua, "__dealMain = function()"),
                "the artifact declares exactly one deferred main");
            checkEq(1, countOccurrences(lua,
                    "local __mainOk, __mainErr = __dealMain()"),
                "the deferred main is driven exactly once at load");
            checkEq(1, countOccurrences(lua, "return __exportSurfaces[\"app\"]\n"),
                "the production chunk returns the entry module's surface once");

            // The trace protocol is suppressed: no event helper output and no
            // protocol terminal.
            check(lua.contains("__ev = function() end"),
                "the trace event helper is the production no-op");
            check(!lua.contains("io.stderr:write(\"R|"),
                "the artifact carries no R| terminal writer");
            // The only F| writers of the production chunk are the mode-gated
            // console records of the shared row invoker (M4): the chunk's mode
            // flag is false, so no record reaches stderr at run time.
            check(lua.contains("__traceMode = false"),
                "the production chunk's mode flag is false");
            int consoleEffectAt = lua.indexOf("local function __consoleEffect(channel, "
                + "text)");
            int consoleTextAt = lua.indexOf("local function __consoleText(...)");
            int consoleRecordWriters = 0;
            for (int at = lua.indexOf("io.stderr:write(\"F|"); at >= 0;
                    at = lua.indexOf("io.stderr:write(\"F|", at + 1)) {
                if (consoleEffectAt >= 0 && at > consoleEffectAt
                        && consoleTextAt > at) {
                    consoleRecordWriters++;
                    continue;
                }
                fail("the production artifact carries an unguarded F| event writer at "
                    + at);
            }
            checkEq(2, consoleRecordWriters,
                "every F| writer of the production chunk is the mode-gated console "
                    + "record of the row invoker");
            check(!lua.contains("\"R|success|null\""),
                "the artifact carries no success terminal text");
            String trace = LuaSemanticEmitter.emitProject(project, result.tables(),
                result.registries());
            check(trace.contains("io.stderr:write(\"R|success|null\\n\")"),
                "emitProject keeps its trace terminal (the entry differs only by mode)");

            // The production terminal: code line + exit 1 on a DEAL failure,
            // rethrow of a non-DEAL failure, silence on success.
            check(lua.contains("if type(__mainErr) == \"table\" and __mainErr.__d then"),
                "the terminal discriminates the DEAL failure carrier");
            check(lua.contains("print(\"DEAL_ERROR_CODE: \"..__mainErr.code)"),
                "the terminal publishes the retained DEAL_ERROR_CODE line on stdout");
            check(lua.contains("error(__mainErr, 0)"),
                "the terminal rethrows a non-DEAL failure");
            check(lua.contains("os.exit(1)"),
                "the terminal exits 1 after the failure line");

            // The module surface registry: one surface per module keyed by the
            // module identity, created before the walks, written by
            // EXPORT_PUBLISH in declaration order.
            checkEq(1, countOccurrences(lua,
                    "__exportSurfaces = __exportSurfaces or {}\n"),
                "the chunk declares the chunk-global registry exactly once");
            // One surface per closure module in closure order, then one per
            // imported STDLIB module (the cataloged callable surface, M4/K15):
            // every surface is keyed by the dotted module path.
            List<String> expectedSurfaces = new ArrayList<>();
            for (ModuleId moduleId : project.modules().keySet()) {
                expectedSurfaces.add("__exportSurfaces[\"" + moduleId.path()
                    + "\"] = __exportSurfaces[\"" + moduleId.path() + "\"] or {}");
            }
            expectedSurfaces.add("__exportSurfaces[\"std.console\"] = "
                + "__exportSurfaces[\"std.console\"] or {}");
            checkEq(expectedSurfaces, luaSurfaceCreations(lua),
                "one surface per closure module plus the imported STDLIB module, keyed "
                    + "by the dotted module path, in closure order");
            List<String> expectedWrites = new ArrayList<>();
            for (ModuleId moduleId : project.modules().keySet()) {
                for (ExportEntry entry : exportsOf(project.modules().get(moduleId))) {
                    expectedWrites.add("__exportSurfaces[\"" + moduleId.path() + "\"][\""
                        + entry.name() + "\"] = {__kind = \"function\", sig = \""
                        + entry.spec() + "\", f = __unfn(S.v" + entry.valueId()
                        + "), __val = S.v" + entry.valueId() + "}");
                }
            }
            checkEq(4, expectedWrites.size(),
                "the fixture declares four exports (lib: add, twice; app: main, label)");
            int previous = lua.indexOf("__exportSurfaces = __exportSurfaces or {}");
            for (String write : expectedWrites) {
                checkEq(1, countOccurrences(lua, write),
                    "the artifact publishes " + write);
                int at = lua.indexOf(write);
                check(at > previous, "the publications run in declaration order: " + write);
                previous = at;
            }
            int walksAt = lua.indexOf("__dealMain = function()");
            check(previous > walksAt,
                "every publication is inside the module walks, after the registry");

            // The init walks run in dependency order, each under its own tag.
            int libWalk = lua.indexOf("__module = \"lib\"", walksAt);
            int appWalk = lua.indexOf("__module = \"app\"", walksAt);
            check(libWalk > walksAt && appWalk > libWalk,
                "the module walks run in dependency order under their own __module tag");

            // The whole closure is present: every module's factories and
            // every detached class-default function of the closure are
            // fields of the one bounded chunk-level factory store (never
            // one pre-declared chunk local per factory — LuaJIT bounds one
            // function at 200 locals), assigned before the walks, so a
            // construction inside a function body resolves the field from
            // the one store instead of a nil global.
            int storeAt = lua.indexOf("local __factories = {}");
            check(storeAt >= 0 && storeAt < walksAt,
                "the chunk declares the one bounded factory store before the "
                    + "walks");
            int classDefaults = 0;
            for (ModuleId moduleId : project.modules().keySet()) {
                LoweredModuleUnit unit = project.modules().get(moduleId);
                for (deal.semantic.ir.LoweredFunction function
                        : unit.functions().values()) {
                    String factory = "__factories.F" + function.functionId().id()
                        + " = function(";
                    int factoryAt = lua.indexOf(factory);
                    check(factoryAt > storeAt && factoryAt < walksAt,
                        "the closure carries the factory of " + moduleId.path() + "#"
                            + function.functionId() + " as a store field");
                }
                for (SemanticOp op : unit.ops()) {
                    if (op.kind() != SemanticOpKind.CLASS_DEFAULT) {
                        continue;
                    }
                    classDefaults++;
                    String defaultFn = "__factories.D" + op.opId().id()
                        + " = function()";
                    int defaultAt = lua.indexOf(defaultFn);
                    check(defaultAt > storeAt && defaultAt < walksAt,
                        "the closure carries the detached class-default function "
                            + defaultFn + " assigned after the store declaration "
                            + "and before the walks");
                }
            }
            check(classDefaults > 0,
                "the fixture exercises the detached class-default surface (Counter.n)");

            // Determinism: byte-identical repeated emission.
            checkEq(lua, LuaSemanticEmitter.emitProductionProject(project,
                    result.tables(), result.registries(), fixture.surface()),
                "the repeated production project emission is byte-identical");

            // The tables/registries inputs are required.
            try {
                LuaSemanticEmitter.emitProductionProject(project, null,
                    result.registries(), fixture.surface());
                fail("a null tables map is rejected");
            } catch (NullPointerException expected) {
                passed++;
            }
            try {
                LuaSemanticEmitter.emitProductionProject(project, result.tables(), null,
                    fixture.surface());
                fail("a null registries map is rejected");
            } catch (NullPointerException expected) {
                passed++;
            }
        }
    }

    // =========================================================================
    // 3. Real luajit: the observable one-main probe, the clean run, and the
    //    conversion-overflow terminal
    // =========================================================================

    private static void testUnderLuaJit(Baseline fixture, Fixture quiet)
            throws Exception {
        System.out.println("-- the production chunk under real luajit: the one-main "
            + "observable, the clean run, and the E8004 terminal --");

        // (a) The observable one-main probe: main's console.log effect appears
        //     exactly once, and the trace protocol publishes nothing on stderr.
        Path workspace = Files.createTempDirectory("lua-production-project-run");
        try {
            SemanticLowerer.ProjectLoweringResult result = fixture.result();
            if (result.project() == null) {
                fail("the console fixture lowers: " + result.diagnostics());
                return;
            }
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), workspace);
            checkEq(0, run.exitCode(),
                "the console fixture exits 0: stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
            checkEq("PROBE|MAIN-ONCE\n", run.stdout(),
                "the entry main runs exactly once (one observable effect byte line)");
            checkEq("", run.stderr(),
                "the production chunk publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(workspace);
        }

        // (b) The covered-construct fixture: exit 0 with empty output.
        Path quietWorkspace = Files.createTempDirectory("lua-production-project-quiet");
        try {
            Path artifact = quietWorkspace.resolve("project.lua");
            Files.writeString(artifact, quiet.lua(), StandardCharsets.UTF_8);
            deployRuntime(quietWorkspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), quietWorkspace);
            checkEq(0, run.exitCode(),
                "the covered-construct fixture exits 0: stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
            checkEq("", run.stdout(), "the covered-construct fixture is silent on stdout");
            checkEq("", run.stderr(), "the covered-construct fixture is silent on stderr");
        } finally {
            deleteRecursively(quietWorkspace);
        }

        // (b2) The chunk-local growth probe (ISSUE-0717, defect snippet
        //      31): a 201-export module must load and run under real
        //      luajit — the emitted function-factory table keeps the
        //      chunk under LuaJIT's 200-local limit.
        StringBuilder growthSource = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            growthSource.append("export function f")
                .append(String.format("%03d", i))
                .append("(): null { return null; }\n");
        }
        growthSource.append("export function main(): null { f000(); f100();"
            + " f200(); return null; }\n");
        Fixture growth = compileProject(growthSource.toString());
        Path growthWorkspace = Files.createTempDirectory("lua-production-project-growth");
        try {
            Path artifact = growthWorkspace.resolve("project.lua");
            Files.writeString(artifact, growth.lua(), StandardCharsets.UTF_8);
            deployRuntime(growthWorkspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), growthWorkspace);
            checkEq(0, run.exitCode(),
                "the chunk-local growth fixture loads and exits 0 under luajit: "
                    + "stdout=" + escaped(run.stdout()) + " stderr="
                    + escaped(run.stderr()));
            checkEq("", run.stderr(),
                "the chunk-local growth fixture publishes no trace protocol "
                    + "on stderr");
        } finally {
            deleteRecursively(growthWorkspace);
            deleteRecursively(growth.root());
        }

        // (b3) The shadowing-binding probe (ISSUE-0717, defect snippet
        //      65): a shadowing declaration's initializer and every
        //      reference before it resolve against the enclosing
        //      binding — the loop/catch frame fast path never overrides
        //      a nearer shadowing declaration. Real luajit must exit 0.
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
        Fixture shadow = compileProject(shadowSource);
        Path shadowWorkspace = Files.createTempDirectory("lua-production-project-shadow");
        try {
            Path artifact = shadowWorkspace.resolve("project.lua");
            Files.writeString(artifact, shadow.lua(), StandardCharsets.UTF_8);
            deployRuntime(shadowWorkspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), shadowWorkspace);
            checkEq(0, run.exitCode(),
                "the shadowing-binding fixture exits 0 under luajit: stdout="
                    + escaped(run.stdout()) + " stderr=" + escaped(run.stderr()));
            checkEq("", run.stderr(),
                "the shadowing-binding fixture publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(shadowWorkspace);
            deleteRecursively(shadow.root());
        }

        // (b4) The dynamic-key computed table read probe (ISSUE-0717
        //      review): a variable key and an effectful key expression
        //      resolve the same read shape as the literal spelling, and a
        //      missing key reads null at a nullable position. Real luajit
        //      must exit 0.
        String dynamicTableSource = """
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
        Fixture dynamicTable = compileProject(LIB_SOURCE, dynamicTableSource);
        Path dynamicWorkspace = Files.createTempDirectory("lua-production-project-dyntable");
        try {
            Path artifact = dynamicWorkspace.resolve("project.lua");
            Files.writeString(artifact, dynamicTable.lua(), StandardCharsets.UTF_8);
            deployRuntime(dynamicWorkspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), dynamicWorkspace);
            checkEq(0, run.exitCode(),
                "the dynamic-table-read fixture exits 0 under luajit: stdout="
                    + escaped(run.stdout()) + " stderr=" + escaped(run.stderr()));
            checkEq("", run.stderr(),
                "the dynamic-table-read fixture publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(dynamicWorkspace);
            deleteRecursively(dynamicTable.root());
        }

        // (c) The conversion-overflow fixture: exit 1, DEAL_ERROR_CODE: E8004
        //     on stdout (never a trace terminal).
        Fixture overflow = compileProject(OVERFLOW_APP_SOURCE);
        Path overflowWorkspace = Files.createTempDirectory("lua-production-project-ovf");
        try {
            Path artifact = overflowWorkspace.resolve("project.lua");
            Files.writeString(artifact, overflow.lua(), StandardCharsets.UTF_8);
            deployRuntime(overflowWorkspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), overflowWorkspace);
            checkEq(1, run.exitCode(),
                "the conversion-overflow fixture exits 1: stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
            check(run.stdout().contains("DEAL_ERROR_CODE: E8004\n"),
                "the overflow publishes the retained DEAL_ERROR_CODE: E8004 line on "
                    + "stdout: " + escaped(run.stdout()));
            checkEq("", run.stderr(),
                "the overflow publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(overflowWorkspace);
            deleteRecursively(overflow.root());
        }
    }

    // =========================================================================
    // 4. The T1 dependency: the executed chunk's per-module surfaces hold
    //    each module's exports
    // =========================================================================

    private static void testExecutedSurfaces(Fixture fixture) throws Exception {
        System.out.println("-- the executed chunk's per-module surfaces hold each "
            + "module's exports (the T1 dependency) --");
        Path workspace = Files.createTempDirectory("lua-production-project-surface");
        try {
            ExecutableLoweredProject project = fixture.producedProject();
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);

            List<String> modules = new ArrayList<>();
            List<List<ExportEntry>> entries = new ArrayList<>();
            for (ModuleId moduleId : project.modules().keySet()) {
                modules.add(moduleId.path());
                entries.add(exportsOf(project.modules().get(moduleId)));
            }
            Path probe = workspace.resolve("surface-probe.lua");
            Files.writeString(probe, luaSurfaceProbe(artifact, modules, entries),
                StandardCharsets.UTF_8);
            deployRuntime(workspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace);
            check(run.exitCode() == 0 && run.stdout().contains("PROBE-OK"),
                "the executed chunk's registry carries one surface per module keyed by "
                    + "the dotted module path with the declared entries in declaration "
                    + "order and the published callable: exit=" + run.exitCode()
                    + " stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 4b. The host-visible DEALRuntimeError: complete transport and named frames
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
     * handling the caught one, {@code alias} answers the
     * catch/rethrow/catch alias identity of a retained Error value, and
     * {@code mutate} edits a retained E8001 alias after the OUTER
     * replacement linked it as cause and then rethrows the OUTER error.
     * {@code recur} drives the host re-entry arm: the host-supplied step
     * calls the same published export back while two activations of it are
     * live, so three identical frame labels stack and none is lost.
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

        export function recur(n: int, step: (n: int) => int): int {
          if (n === 0) {
            let t: table = { value: "bad" };
            let x: int = t.value;
            return x;
          }
          return step(n - 1);
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

    /** The probe exports of the host-capture battery, in probe order. */
    private static final List<String> HOST_ERROR_EXPORTS = List.of(
        "run", "reraise", "leafFail", "swallow", "arun", "areraise",
        "overflow", "replaced", "mutate");

    private static void testHostErrorTransport() throws Exception {
        System.out.println("-- the host-visible DEALRuntimeError of the production "
            + "artifact: the complete transport through calls, await, and "
            + "catch/rethrow, with named frames --");
        Fixture fixture = compileProject(HOST_ERROR_LIB_SOURCE, HOST_ERROR_APP_SOURCE);
        Path workspace = Files.createTempDirectory("lua-production-project-hosterror");
        try {
            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, fixture.lua(), StandardCharsets.UTF_8);
            deployRuntime(workspace);
            Path probe = workspace.resolve("host-error-probe.lua");
            Files.writeString(probe, luaHostErrorProbe(artifact), StandardCharsets.UTF_8);
            ProcessOutcome run = runProcess(List.of("luajit",
                probe.toAbsolutePath().toString()), workspace);
            checkEq(0, run.exitCode(), "the host-error probe exits 0: stdout="
                + escaped(run.stdout()) + " stderr=" + escaped(run.stderr()));
            Map<String, HostErrorRow> rows = hostErrorRows(run.stdout());
            checkEq(8, rows.size(),
                "the probe captures one closed host error row per failing probe "
                    + "export (the success arm publishes the none row): "
                    + escaped(run.stdout()));

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
                "the handling export returns its own value: " + escaped(run.stdout()));

            // The await arm: the awaited companion failure transports the
            // same complete surface, and its rethrow preserves it.
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
                    "the replacement failure links the caught failure as its cause");
            }

            // The saved-cause-alias arm: the E8001 alias is edited after the
            // OUTER replacement linked it as cause, and the OUTER error is
            // rethrown; the linked cause keeps the transport captured at
            // catch time (never the later mutation).
            String mutated = hostFieldLine(run.stdout(), "HOST-CAUSE|mutate|");
            check(mutated != null, "the probe recorded the mutated-cause row: "
                + escaped(run.stdout()));
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

            // The host re-entry arm: the host-supplied step invokes the
            // same published export back while two activations of it are
            // live, so the innermost failure names three identical active
            // invocations — the label is never deduplicated against an
            // earlier activation of the same function.
            String reentry = hostFieldLine(run.stdout(), "HOST-REENTRY|recur|");
            check(reentry != null, "the probe recorded the host re-entry row: "
                + escaped(run.stdout()));
            if (reentry != null) {
                String[] parts = reentry.split("\\|", -1);
                checkEq(7, parts.length,
                    "the host re-entry row carries every compared field: "
                        + reentry);
                if (parts.length == 7) {
                    checkEq("E8001", parts[0],
                        "the re-entered failure keeps its code: " + reentry);
                    checkEq("expected int", parts[1],
                        "the re-entered failure keeps its message: " + reentry);
                    check(parts[2].contains("src/app.deal:"),
                        "the re-entered failure keeps its own raising site: "
                            + reentry);
                    checkEq("int", parts[3],
                        "the re-entered failure keeps its expected type: " + reentry);
                    checkEq("string", parts[4],
                        "the re-entered failure keeps its actual kind: " + reentry);
                    check(parts[5].matches("app#recur@F\\d+,app#recur@F\\d+,"
                            + "app#recur@F\\d+"),
                        "the re-entered failure names every active invocation of "
                            + "the same function: " + reentry);
                    String[] frames = parts[5].split(",");
                    check(frames.length == 3 && frames[0].equals(frames[1])
                            && frames[1].equals(frames[2]),
                        "the three re-entered invocations carry the identical "
                            + "label: " + reentry);
                    checkEq("-", parts[6],
                        "the re-entered failure invents no cause: " + reentry);
                }
            }
            // The frame stack is empty right after the re-entered failure:
            // every re-entered activation popped its own frame as the error
            // propagated out to the host.
            String empty = hostFieldLine(run.stdout(), "HOST-EMPTY|recur|");
            checkEq("-", empty,
                "the re-entered activations leave no frame behind: "
                    + escaped(run.stdout()));

            // The cleanup arm: after the failure and the success probes, a
            // fresh direct invocation of the leaf export names exactly its
            // own frame — every host-visible push was balanced by its pop,
            // including the re-entered activations above.
            String clean = hostFieldLine(run.stdout(), "HOST-CLEAN|leafFail|");
            check(clean != null, "the probe recorded the frame-cleanup row: "
                + escaped(run.stdout()));
            if (clean != null) {
                check(clean.matches("app#leafFail@F\\d+"),
                    "the frame stack is clean before the fresh invocation and "
                        + "balanced after it: " + clean);
            }

            // The identity arm: the DEAL probe's own catch/rethrow/catch
            // alias identity — the rethrown error caught again is the
            // identical Error value.
            Map<String, String> identities = hostIdentityRows(run.stdout());
            checkEq("true", identities.get("alias"),
                "the catch/rethrow/catch alias identity holds on the artifact "
                    + "(the rethrown error caught again is the identical Error "
                    + "value): " + escaped(run.stdout()) + " " + escaped(run.stderr()));
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 5. Every non-entry module's ENTRY_INVOKE delegation stays skipped
    // =========================================================================

    private static void testNonEntryMainStaysSkipped() throws Exception {
        System.out.println("-- every non-entry module's ENTRY_INVOKE delegation stays "
            + "skipped (the lib main never runs) --");
        Fixture fixture = compileProject(LIB_WITH_MAIN_SOURCE, CONSOLE_APP_SOURCE);
        Path workspace = Files.createTempDirectory("lua-production-project-nonentry");
        try {
            ExecutableLoweredProject project = fixture.producedProject();
            // The non-vacuity check: both units carry their own lowered
            // ENTRY_INVOKE delegation op, so the skip is exercised.
            int libEntryOps = 0;
            int appEntryOps = 0;
            for (SemanticOp op : project.modules().get(LIB).ops()) {
                if (op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    libEntryOps++;
                }
            }
            for (SemanticOp op : project.modules().get(APP).ops()) {
                if (op.kind() == SemanticOpKind.ENTRY_INVOKE) {
                    appEntryOps++;
                }
            }
            checkEq(1, libEntryOps,
                "the fixture's lib unit carries its own ENTRY_INVOKE delegation op");
            checkEq(1, appEntryOps,
                "the fixture's entry unit carries its ENTRY_INVOKE delegation op");

            String lua = fixture.lua();
            checkEq(1, countOccurrences(lua, "\"START\", \"ENTRY_INVOKE\""),
                "the production chunk emits exactly one ENTRY_INVOKE delegation "
                    + "(the entry module's)");

            Path artifact = workspace.resolve("project.lua");
            Files.writeString(artifact, lua, StandardCharsets.UTF_8);
            deployRuntime(workspace);
            ProcessOutcome run = runProcess(List.of("luajit",
                artifact.toAbsolutePath().toString()), workspace);
            checkEq(0, run.exitCode(),
                "the non-entry-main fixture exits 0: stdout=" + escaped(run.stdout())
                    + " stderr=" + escaped(run.stderr()));
            checkEq("PROBE|MAIN-ONCE\n", run.stdout(),
                "the entry main runs exactly once and the non-entry main runs not "
                    + "at all");
            checkEq("", run.stderr(),
                "the non-entry-main fixture publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // Driver sources
    // =========================================================================

    /**
     * The host-capture probe: loads the production artifact, invokes every
     * probe export exactly once through its published callable (stepping a
     * published async operation to completion when the surface publishes
     * one), and prints the raw DEAL error fields of the uncaught failure —
     * the host's own view, with {@code -} for every absent field.
     */
    private static String luaHostErrorProbe(Path artifact) {
        StringBuilder lua = new StringBuilder();
        lua.append("package.path = './?.lua;./std/?.lua;' .. package.path\n");
        lua.append("local __rt = require(\"deal.runtime\")\n");
        lua.append("local __surface = dofile(")
            .append(luaString(artifact.toAbsolutePath().toString())).append(")\n");
        lua.append("local __names = {");
        for (String name : HOST_ERROR_EXPORTS) {
            lua.append(" ").append(luaString(name)).append(",");
        }
        lua.append(" }\n");
        lua.append("local function __field(v)\n");
        lua.append("  if v == nil then return \"-\" end\n");
        lua.append("  return tostring(v)\n");
        lua.append("end\n");
        lua.append("local function __cause(e)\n");
        lua.append("  if type(e) == \"table\" and e.cause ~= nil then\n");
        lua.append("    return __field(e.cause.code)\n");
        lua.append("  end\n");
        lua.append("  return \"-\"\n");
        lua.append("end\n");
        lua.append("for _, __name in ipairs(__names) do\n");
        lua.append("  local __ok, __err = pcall(__surface[__name].f)\n");
        lua.append("  if __ok and type(__err) == \"table\"\n");
        lua.append("      and __err.__kind == \"async\" then\n");
        lua.append("    __ok, __err = pcall(__rt.async_step, __err)\n");
        lua.append("  end\n");
        lua.append("  if __ok then\n");
        lua.append("    print(\"HOST-ERR|\" .. __name .. \"|none|\" .. tostring(__err))\n");
        lua.append("  elseif type(__err) == \"table\" then\n");
        lua.append("    print(\"HOST-ERR|\" .. __name .. \"|\" .. __field(__err.code)\n");
        lua.append("      .. \"|\" .. __field(__err.m) .. \"|\" .. __field(__err.o)\n");
        lua.append("      .. \"|\" .. __field(__err.e) .. \"|\" .. __field(__err.a)\n");
        lua.append("      .. \"|\" .. __field(__err.f) .. \"|\" .. __cause(__err))\n");
        lua.append("  else\n");
        lua.append("    print(\"HOST-ERR|\" .. __name .. \"|raw|\" .. tostring(__err))\n");
        lua.append("  end\n");
        lua.append("end\n");
        // The host re-entry probe: the host-supplied step invokes the same
        // published export back while two activations of it are live, so
        // the innermost failure names three identical active invocations —
        // the label is never deduplicated against an earlier activation of
        // the same function. It runs before the cleanup probe below, so
        // that row proves the re-entered activations left no stale frame.
        lua.append("local __recur = __surface.recur.f\n");
        lua.append("local __step\n");
        lua.append("__step = {__kind = \"function\", sig = \"(int)->int\",\n");
        lua.append("  f = function(n, origin, line, column) return __recur(n, __step) end}\n");
        lua.append("local __okR, __errR = pcall(__recur, 2, __step)\n");
        lua.append("if __okR or type(__errR) ~= \"table\" then\n");
        lua.append("  print(\"HOST-REENTRY|recur|none\")\n");
        lua.append("else\n");
        lua.append("  print(\"HOST-REENTRY|recur|\" .. __field(__errR.code)\n");
        lua.append("    .. \"|\" .. __field(__errR.m) .. \"|\" .. __field(__errR.o)\n");
        lua.append("    .. \"|\" .. __field(__errR.e) .. \"|\" .. __field(__errR.a)\n");
        lua.append("    .. \"|\" .. __field(__errR.f) .. \"|\" .. __cause(__errR))\n");
        lua.append("end\n");
        // The frame stack is empty right after the re-entered failure: every
        // re-entered activation popped its own frame as the error propagated
        // out to the host.
        lua.append("if __frames == nil or next(__frames) == nil then\n");
        lua.append("  print(\"HOST-EMPTY|recur|-\")\n");
        lua.append("else\n");
        lua.append("  print(\"HOST-EMPTY|recur|\" .. table.concat(__frames, \",\"))\n");
        lua.append("end\n");
        // The cleanup probe: a fresh direct invocation of the leaf export
        // after the failure and the success probes names exactly its own
        // frame — every host-visible push was balanced by its pop.
        lua.append("local __okC, __errC = pcall(__surface.leafFail.f)\n");
        lua.append("if __okC or type(__errC) ~= \"table\" then\n");
        lua.append("  print(\"HOST-CLEAN|leafFail|none\")\n");
        lua.append("else\n");
        lua.append("  print(\"HOST-CLEAN|leafFail|\" .. __field(__errC.f))\n");
        lua.append("end\n");
        // The saved-cause-alias probe: the E8001 alias is edited after the
        // OUTER replacement linked it as cause, and the OUTER error is
        // rethrown; the linked cause keeps the transport captured at catch
        // time (never the later mutation).
        lua.append("local __okM, __errM = pcall(__surface.mutate.f)\n");
        lua.append("if __okM or type(__errM) ~= \"table\" then\n");
        lua.append("  print(\"HOST-CAUSE|mutate|none\")\n");
        lua.append("else\n");
        lua.append("  local __cm = __errM.cause\n");
        lua.append("  if __cm == nil then\n");
        lua.append("    print(\"HOST-CAUSE|mutate|\" .. __field(__errM.code)"
            + " .. \"|\" .. __field(__errM.m) .. \"|-|-|-|-|-|-\")\n");
        lua.append("  else\n");
        lua.append("    print(\"HOST-CAUSE|mutate|\" .. __field(__errM.code)"
            + " .. \"|\" .. __field(__errM.m) .. \"|\" .. __field(__cm.code)"
            + " .. \"|\" .. __field(__cm.m) .. \"|\" .. __field(__cm.o)"
            + " .. \"|\" .. __field(__cm.e) .. \"|\" .. __field(__cm.a)"
            + " .. \"|\" .. __field(__cm.f) .. \"|\""
            + " .. (__cm.cause == nil and \"-\" or __field(__cm.cause.code)))\n");
        lua.append("  end\n");
        lua.append("end\n");
        // The catch/rethrow/catch alias identity probe: the DEAL export's
        // own === answer for an Error value caught, rethrown and caught
        // again — one identity on every consumer.
        lua.append("local __okI, __errI = pcall(__surface.alias.f)\n");
        lua.append("if __okI and type(__errI) == \"boolean\" then\n");
        lua.append("  print(\"HOST-ID|alias|\" .. tostring(__errI))\n");
        lua.append("else\n");
        lua.append("  print(\"HOST-ID|alias|missing\")\n");
        lua.append("end\n");
        return lua.toString();
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

    private static String luaSurfaceProbe(Path artifact, List<String> modules,
            List<List<ExportEntry>> entries) {
        StringBuilder lua = new StringBuilder();
        lua.append("local __expected = {\n");
        for (int i = 0; i < modules.size(); i++) {
            lua.append("  {module = ").append(luaString(modules.get(i)))
                .append(", entries = {\n");
            for (ExportEntry entry : entries.get(i)) {
                lua.append("    {name = ").append(luaString(entry.name()))
                    .append(", sig = ").append(luaString(entry.spec())).append("},\n");
            }
            lua.append("  }},\n");
        }
        lua.append("}\n");
        lua.append("local function __fail(message)\n");
        lua.append("  print(\"PROBE-FAIL: \"..message)\n");
        lua.append("  os.exit(1)\n");
        lua.append("end\n");
        lua.append("local __returned = dofile(")
            .append(luaString(artifact.toAbsolutePath().toString())).append(")\n");
        lua.append("local __count = 0\n");
        lua.append("for __ in pairs(__exportSurfaces) do __count = __count + 1 end\n");
        lua.append("if __count ~= ").append(modules.size())
            .append(" then __fail(\"the registry carries \"..__count..\" surfaces\") end\n");
        lua.append("if __returned ~= __exportSurfaces[")
            .append(luaString(modules.get(modules.size() - 1)))
            .append("] then __fail(\"the chunk returned no entry-module surface\") end\n");
        lua.append("for _, __m in ipairs(__expected) do\n");
        lua.append("  local __surface = __exportSurfaces[__m.module]\n");
        lua.append("  if type(__surface) ~= \"table\" then __fail(\"no surface for \"..__m.module) end\n");
        lua.append("  local __held = 0\n");
        lua.append("  for __ in pairs(__surface) do __held = __held + 1 end\n");
        lua.append("  if __held ~= #__m.entries then __fail(__m.module..\" holds \"..__held..\" entries\") end\n");
        lua.append("  local __order = {}\n");
        lua.append("  for _, __e in ipairs(__m.entries) do\n");
        lua.append("    local __entry = __surface[__e.name]\n");
        lua.append("    if type(__entry) ~= \"table\" then __fail(\"no entry \"..__m.module..\"#\"..__e.name) end\n");
        lua.append("    if __entry.__kind ~= \"function\" then __fail(\"entry kind \"..__m.module..\"#\"..__e.name) end\n");
        lua.append("    if __entry.sig ~= __e.sig then __fail(\"entry sig \"..__m.module..\"#\"..__e.name..\": \"..tostring(__entry.sig)) end\n");
        lua.append("    if type(__entry.f) ~= \"function\" then __fail(\"entry callable \"..__m.module..\"#\"..__e.name) end\n");
        lua.append("  end\n");
        lua.append("end\n");
        lua.append("local __okCall, __value = pcall(__exportSurfaces[\"lib\"][\"add\"].f, 2, 3)\n");
        lua.append("if not __okCall then __fail(\"the published callable failed: \"..tostring(__value)) end\n");
        lua.append("if __value ~= 5 then __fail(\"the published callable returned \"..tostring(__value)) end\n");
        lua.append("print(\"PROBE-OK\")\n");
        return lua.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The emitted Lua surface-creation statements, in text order. */
    private static List<String> luaSurfaceCreations(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("__exportSurfaces[\"")
                    && line.contains("\"] = __exportSurfaces[\"")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String escaped(String text) {
        return text.replace("\n", "\\n");
    }

    private static String luaString(String value) {
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
    }

    /**
     * Deploys the unchanged runtime/stdlib copies next to the emitted chunk
     * (the production artifact set the compile publishes): the executed
     * workspace carries {@code deal/runtime.lua} and every {@code std/*.lua}.
     */
    private static void deployRuntime(Path workspace) throws Exception {
        Path runtime = Path.of("deal", "runtime.lua");
        check(Files.isRegularFile(runtime),
            "the checkout runtime deal/runtime.lua is available for deployment");
        Path std = Path.of("std");
        check(Files.isDirectory(std),
            "the checkout stdlib surface std/ is available for deployment");
        Path runtimeTarget = workspace.resolve("deal").resolve("runtime.lua");
        Files.createDirectories(runtimeTarget.getParent());
        Files.copy(runtime, runtimeTarget);
        Path stdTarget = workspace.resolve("std");
        Files.createDirectories(stdTarget);
        try (var listing = Files.list(std)) {
            for (Path file : listing.sorted().toList()) {
                if (file.getFileName().toString().endsWith(".lua")) {
                    Files.copy(file, stdTarget.resolve(file.getFileName()));
                }
            }
        }
        check(Files.isRegularFile(runtimeTarget)
                && Files.isRegularFile(stdTarget.resolve("console.lua")),
            "the workspace carries the runtime and stdlib deployment copies");
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
        System.out.println("=== Lua Production Project Emission Tests (ISSUE-0640) ===\n");
        testEntrySignatureAndInputs();
        List<Path> roots = new ArrayList<>();
        try {
            Baseline console = prepare(compileProject(CONSOLE_APP_SOURCE), roots);
            Fixture quiet = compileProject(QUIET_APP_SOURCE);
            roots.add(quiet.root());
            testEmittedChunkText(console);
            testUnderLuaJit(console, quiet);
            testExecutedSurfaces(quiet);
            testHostErrorTransport();
            testNonEntryMainStaysSkipped();
        } finally {
            for (Path root : roots) {
                deleteRecursively(root);
            }
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Lua Production Project Emission Tests Passed ===");
    }
}
